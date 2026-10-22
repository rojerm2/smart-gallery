package dev.smartgallery

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.FaceEmbedder
import dev.smartgallery.data.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory migration tests only; no shared-media access or personal database changes. */
@RunWith(AndroidJUnit4::class)
class SourceDetailRecoveryTest {
    private suspend fun isolated(check: suspend (GalleryRepository) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "detail-recovery-${UUID.randomUUID()}"
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

    private fun media(id: String) =
        Media(
            id,
            "content://synthetic/$id",
            "$id.png",
            "Fixtures/",
            "image/png",
            1,
            1,
            10,
            hash = "fixture-hash",
        )

    private fun failed(id: String, kind: String = "1faces") =
        Stage(
            id,
            kind,
            "failed",
            attempts = 3,
            version = FaceEmbedder.VERSION,
            error = "Analysis could not complete",
        )

    @Test
    fun retryEligibleFaceFailuresOnceWithoutChangingCompletedWorkOrCorrections() = runBlocking {
        isolated { repo ->
            val item = media("photo")
            repo.dao.media(item)
            val face =
                Face(
                    "face",
                    item.id,
                    item.hash,
                    0,
                    ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).array(),
                    FaceEmbedder.VERSION,
                    100.0,
                    "",
                )
            repo.dao.face(face)
            repo.dao.person(Person("person", "Confirmed fixture", true))
            val membership = Membership(face.id, "person", true, confident = true)
            repo.dao.membership(membership)
            val negative = Negative("f:face", "p:another-fixture")
            repo.dao.negative(negative)
            repo.dao.overrideLabel(LabelOverride(item.id, "Confirmed category", true))
            val hash = Stage(item.id, "0hash", "done")
            repo.dao.stage(hash)
            repo.dao.stage(failed(item.id))
            repo.dao.stage(failed(item.id, "2content"))
            repo.dao.media(media("video"))
            repo.dao.stage(failed("video", "1video"))
            assertEquals(1, repo.recoverSourceDetailFailures())
            val restored = repo.dao.stageById(item.id, "1faces")!!
            assertEquals("pending", restored.state)
            assertEquals(0, restored.attempts)
            assertEquals("", restored.error)
            assertEquals(FaceEmbedder.VERSION, restored.version)
            assertEquals(hash, repo.dao.stageById(item.id, "0hash"))
            assertEquals("failed", repo.dao.stageById(item.id, "2content")!!.state)
            assertEquals("failed", repo.dao.stageById("video", "1video")!!.state)
            assertEquals(membership, repo.dao.membershipById(face.id))
            val retainedFace = repo.dao.faceById(face.id)!!
            assertArrayEquals(face.embedding, retainedFace.embedding)
            assertEquals(face, retainedFace.copy(embedding = face.embedding))
            assertEquals(listOf(negative), repo.dao.negatives())
            assertTrue(repo.dao.overrides(item.id).single().included)
            // A genuine later failure retains the normal bounded retry policy.
            repo.dao.stage(failed(item.id))
            assertEquals(0, repo.recoverSourceDetailFailures())
            assertEquals(3, repo.dao.stageById(item.id, "1faces")!!.attempts)
            assertEquals("failed", repo.dao.stageById(item.id, "1faces")!!.state)
        }
    }

    @Test
    fun excludedOrInaccessibleStagesAreNotRevivedAndFreshInstallsDoNotLoopRetries() = runBlocking {
        isolated { repo ->
            repo.dao.media(media("excluded").copy(excluded = true))
            repo.dao.media(media("inaccessible").copy(accessible = false))
            repo.dao.stage(failed("excluded"))
            repo.dao.stage(failed("inaccessible"))
            assertEquals(0, repo.recoverSourceDetailFailures())
            assertEquals("failed", repo.dao.stageById("excluded", "1faces")!!.state)
            assertEquals("failed", repo.dao.stageById("inaccessible", "1faces")!!.state)
            repo.dao.media(media("later"))
            repo.dao.stage(failed("later"))
            assertEquals(0, repo.recoverSourceDetailFailures())
            assertEquals("failed", repo.dao.stageById("later", "1faces")!!.state)
        }
    }
}
