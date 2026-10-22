package dev.smartgallery

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.FaceEmbedder
import dev.smartgallery.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResetIntegrationTest {
    @Test
    fun resetRemovesSensitiveResultsKeepsOriginalAndPreservesPrivacyExclusions() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "reset-${UUID.randomUUID()}"
        val root = File(base.noBackupFilesDir, key).apply { mkdirs() }
        val context =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }

                override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    base.getSharedPreferences("$key-$name", mode)
            }
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val original =
                File(root, "original.jpg").apply { writeText("unchanged synthetic original") }
            val repo = GalleryRepository(context, db)
            repo.dao.media(
                Media(
                    "media",
                    Uri.fromFile(original).toString(),
                    "original.jpg",
                    "Camera/",
                    "image/jpeg",
                    1,
                    1,
                    original.length(),
                    hash = "fixture",
                )
            )
            val face =
                Face(
                    "face",
                    "media",
                    "fixture",
                    0,
                    byteArrayOf(1, 2, 3, 4),
                    FaceEmbedder.VERSION,
                    100.0,
                    "derived.jpg",
                )
            repo.dao.face(face)
            File(repo.crops, face.crop).writeText("private derived crop")
            repo.assign("face", null, "Synthetic person")
            repo.dao.ocr(Ocr("media", 0, "synthetic recognized text"))
            repo.dao.label(Label("media", "Dog"))
            repo.dao.stage(Stage("media", "1faces", state = "running"))
            repo.dao.exclusion(Exclusion("folder", "Private/"))
            repo.reset()
            assertEquals("unchanged synthetic original", original.readText())
            assertTrue(repo.dao.accessibleMedia().isEmpty())
            assertTrue(repo.dao.allPeople().isEmpty())
            assertNull(repo.dao.faceById("face"))
            assertTrue(repo.dao.ocrForMedia("media").isEmpty())
            assertTrue(repo.dao.memberships().isEmpty())
            assertNull(repo.dao.lastUndo())
            assertNull(repo.dao.nextStage())
            assertTrue(repo.crops.listFiles()!!.isEmpty())
            assertTrue(repo.settings.paused)
            assertEquals(listOf(Exclusion("folder", "Private/")), repo.dao.exclusionList())
        } finally {
            db.close()
            root.deleteRecursively()
            base.deleteSharedPreferences("$key-settings")
        }
    }
}
