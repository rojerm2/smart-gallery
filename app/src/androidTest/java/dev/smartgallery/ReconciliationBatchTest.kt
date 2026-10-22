package dev.smartgallery

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
