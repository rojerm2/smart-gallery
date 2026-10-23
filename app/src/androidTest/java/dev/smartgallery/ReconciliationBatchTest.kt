package dev.smartgallery

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.FaceEmbedder
import dev.smartgallery.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory databases and synthetic references only; never queries the shared gallery. */
@RunWith(AndroidJUnit4::class)
class ReconciliationBatchTest {
    private suspend fun isolated(check: suspend (GalleryRepository) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "metadata-${UUID.randomUUID()}"
        val root = File(base.noBackupFilesDir, key).apply { mkdirs() }
        val context =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir(): File = root

                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences("$key-$name", mode)
            }
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            check(GalleryRepository(context, db))
        } finally {
            db.close()
            root.deleteRecursively()
            base.deleteSharedPreferences("$key-settings")
        }
    }

    private fun snapshot() =
        Media(
            "fixture",
            "content://synthetic/fixture",
            "example.png",
            "Fixtures/",
            "image/png",
            1,
            1,
            10,
        )

    @Test
    fun photoRetryUpgradeOnlyRequeuesFacesAndKeepsCorrectionsAndEmbeddingCompatibility() =
        runBlocking {
            isolated { repo ->
                val photo = snapshot().copy(hash = "fixture-hash", analyzed = true)
                repo.dao.media(photo)
                val hash = Stage(photo.id, "0hash", "done")
                val content =
                    Stage(photo.id, "2content", "done", version = GalleryRepository.CONTENT_VERSION)
                repo.dao.stage(hash)
                repo.dao.stage(content)
                repo.dao.stage(Stage(photo.id, "1faces", "done", version = FaceEmbedder.VERSION))
                val face =
                    Face(
                        "confirmed",
                        photo.id,
                        photo.hash,
                        0,
                        byteArrayOf(1, 2, 3, 4),
                        FaceEmbedder.VERSION,
                        90.0,
                        "fixture.jpg",
                        location = "100-100-300-300",
                        userIncluded = true,
                    )
                repo.dao.face(face)
                repo.dao.face(face.copy(id = "automatic", userIncluded = false))
                repo.dao.person(Person("person", "Confirmed fixture", true))
                val membership =
                    Membership(
                        face.id,
                        "person",
                        manual = true,
                        anchor = GalleryRepository.anchor(face),
                    )
                val negative = GalleryRepository.negative("a:${membership.anchor}", "p:other")
                repo.dao.membership(membership)
                repo.dao.membership(Membership("automatic", "person"))
                repo.dao.negative(negative)
                repo.label(photo.id, "Manual category", true)
                val overrides = repo.dao.overrides(photo.id)
                repo.reconcileAnalysisVersions(repo.settings.generation)
                assertFalse(repo.dao.mediaById(photo.id)!!.analyzed)
                assertEquals(photo.hash, repo.dao.mediaById(photo.id)!!.hash)
                assertEquals(hash, repo.dao.stageById(photo.id, "0hash"))
                assertEquals(content, repo.dao.stageById(photo.id, "2content"))
                val pending = repo.dao.stageById(photo.id, "1faces")!!
                assertEquals("pending", pending.state)
                assertEquals(GalleryRepository.PHOTO_FACE_STAGE_VERSION, pending.version)
                val savedFace = repo.dao.faceById(face.id)!!
                assertEquals(FaceEmbedder.VERSION, savedFace.model)
                assertArrayEquals(face.embedding, savedFace.embedding)
                assertTrue(savedFace.userIncluded)
                assertEquals(membership, repo.dao.membershipById(face.id))
                assertNull(repo.dao.membershipById("automatic"))
                assertEquals(listOf(negative), repo.dao.negatives())
                assertEquals(overrides, repo.dao.overrides(photo.id))
                // Repeated discovery cannot reset an attempt/checkpoint of the current stage.
                val running = pending.copy(state = "running", attempts = 1, checkpoint = 3)
                repo.dao.stage(running)
                repo.reconcileAnalysisVersions(repo.settings.generation)
                assertEquals(running, repo.dao.stageById(photo.id, "1faces"))
            }
        }

    @Test
    fun contentOnlyUpgradeLeavesPhotoFacesAndCurrentVideoWorkUntouched() = runBlocking {
        isolated { repo ->
            val photo = snapshot().copy(hash = "photo-hash", analyzed = true)
            val video = photo.copy(id = "video", mime = "video/mp4", duration = 12000)
            repo.dao.media(photo)
            repo.dao.media(video)
            val faces =
                Stage(
                    photo.id,
                    "1faces",
                    "done",
                    version = GalleryRepository.PHOTO_FACE_STAGE_VERSION,
                )
            val videoStage =
                Stage(
                    video.id,
                    "1video",
                    "running",
                    checkpoint = 27,
                    version = GalleryRepository.videoVersion(true),
                )
            repo.dao.stage(faces)
            repo.dao.stage(Stage(photo.id, "2content", "done", version = "older-content"))
            repo.dao.stage(videoStage)
            repo.reconcileAnalysisVersions(repo.settings.generation)
            assertEquals(faces, repo.dao.stageById(photo.id, "1faces"))
            assertEquals("pending", repo.dao.stageById(photo.id, "2content")!!.state)
            assertEquals(
                GalleryRepository.CONTENT_VERSION,
                repo.dao.stageById(photo.id, "2content")!!.version,
            )
            assertEquals(videoStage, repo.dao.stageById(video.id, "1video"))
            assertTrue(repo.dao.mediaById(video.id)!!.analyzed)
            assertFalse(repo.dao.mediaById(photo.id)!!.analyzed)
        }
    }

    @Test
    fun oldDiscoverySnapshotCannotUndoNewFolderOrMediaExclusions() = runBlocking {
        for (kind in listOf("folder", "media")) isolated { repo ->
            val snapshot = snapshot()
            repo.dao.media(snapshot)
            repo.dao.stage(Stage(snapshot.id, "0hash"))
            repo.label(snapshot.id, "Manual fixture category", true)
            repo.exclude(kind, if (kind == "folder") snapshot.folder else snapshot.id)
            // Discovery collected this unexcluded snapshot before the user's exclusion.
            repo.reconcileBatch(listOf(snapshot), repo.settings.generation)
            assertTrue(repo.dao.mediaById(snapshot.id)!!.excluded)
            assertNull(repo.dao.nextStage())
            assertTrue(repo.dao.overrides(snapshot.id).single().included)
            assertEquals(snapshot.uri, repo.dao.mediaById(snapshot.id)!!.uri)
        }
    }

    @Test
    fun oldDiscoverySnapshotCannotOverwriteNewAnalysisCompletion() = runBlocking {
        isolated { repo ->
            val snapshot = snapshot()
            repo.dao.media(snapshot)
            repo.dao.hash(snapshot.id, "new-fixture-hash")
            repo.dao.analyzed(snapshot.id, true)
            repo.label(snapshot.id, "Manual fixture category", true)
            val search = repo.dao.mediaById(snapshot.id)!!.searchText
            repo.reconcileBatch(listOf(snapshot), repo.settings.generation)
            val current = repo.dao.mediaById(snapshot.id)!!
            assertTrue(current.analyzed)
            assertEquals("new-fixture-hash", current.hash)
            assertEquals(search, current.searchText)
            assertTrue(repo.dao.overrides(snapshot.id).single().included)
        }
    }

    @Test
    fun oldDiscoveryGenerationCannotRepopulateAfterReset() = runBlocking {
        isolated { repo ->
            val generation = repo.settings.generation
            repo.settings.generation++
            try {
                repo.reconcileBatch(listOf(snapshot()), generation)
                fail("A reset must invalidate the older metadata snapshot")
            } catch (_: CancellationException) {}
            assertTrue(repo.dao.accessibleMedia().isEmpty())
            assertNull(repo.dao.nextStage())
        }
    }
}
