package dev.smartgallery

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconciliationIntegrationTest {
    @Test
    fun batchesDiscoverEveryItemAndRefreshMetadataWithoutLosingAnalysisOrOverrides() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        requireFixtureOnlyGalleryAccess(base)
        val key = "batch-${UUID.randomUUID()}"
        val root = File(base.noBackupFilesDir, key).apply { mkdirs() }
        val context =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir(): File = root

                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences("$key-$name", mode)
            }
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val owned = mutableListOf<Uri>()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes =
            ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        bitmap.recycle()
        try {
            removeAbandonedPublicBatchFixtures(base, bytes)
            // Cross a full batch and its partial tail, using only UUID-owned tiny fixtures.
            repeat(103) { n ->
                val uri =
                    context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "$key-$n.png")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$key/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )!!
                owned += uri
                context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            val repo = GalleryRepository(context, db)
            repo.reconcile()
            val items = repo.dao.accessibleMedia().filter { it.folder == "Pictures/$key/" }
            assertEquals(103, items.size)
            assertTrue(items.all { repo.dao.stageById(it.id, "0hash")?.state == "pending" })
            assertTrue(repo.dao.analysisVersions("").isEmpty())
            // Exercise both pages of the version join, retaining completed current stages.
            items.forEachIndexed { index, existing ->
                val hash = "reconciliation-test-$index"
                repo.dao.hash(existing.id, hash)
                repo.dao.stage(Stage(existing.id, "0hash", "done"))
                repo.enqueueAnalysis(existing.copy(hash = hash))
                repo.dao.stage(repo.dao.stageById(existing.id, "1faces")!!.copy(state = "done"))
                repo.dao.stage(repo.dao.stageById(existing.id, "2content")!!.copy(state = "done"))
                repo.dao.analyzed(existing.id, true)
            }
            val firstPage = repo.dao.analysisVersions("")
            assertEquals(100, firstPage.size)
            assertEquals(3, repo.dao.analysisVersions(firstPage.last().mediaId).size)
            val outdated = items.sortedBy { it.id }.takeLast(3).first { it.id != items.first().id }
            repo.dao.stage(
                repo.dao.stageById(outdated.id, "1faces")!!.copy(version = "obsolete-test-model")
            )
            val item = items.first()
            // Existing analysis must survive an unchanged snapshot and a simple rename.
            repo.dao.hash(item.id, "reconciliation-test-hash")
            repo.dao.stage(Stage(item.id, "0hash", "done"))
            repo.enqueueAnalysis(item.copy(hash = "reconciliation-test-hash"))
            repo.dao.stage(repo.dao.stageById(item.id, "1faces")!!.copy(state = "done"))
            repo.dao.stage(repo.dao.stageById(item.id, "2content")!!.copy(state = "done"))
            repo.dao.analyzed(item.id, true)
            repo.label(item.id, "Manual category", true)
            repo.reconcile()
            assertTrue(repo.dao.mediaById(item.id)!!.analyzed)
            assertEquals("done", repo.dao.stageById(item.id, "0hash")!!.state)
            assertTrue(repo.dao.overrides(item.id).single().included)
            assertFalse(repo.dao.mediaById(outdated.id)!!.analyzed)
            assertEquals("pending", repo.dao.stageById(outdated.id, "1faces")!!.state)
            assertEquals(
                GalleryRepository.PHOTO_FACE_STAGE_VERSION,
                repo.dao.stageById(outdated.id, "1faces")!!.version,
            )
            context.contentResolver.update(
                Uri.parse(item.uri),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$key-renamed.png")
                },
                null,
                null,
            )
            repo.reconcile()
            val renamed = repo.dao.mediaById(item.id)!!
            assertEquals("$key-renamed.png", renamed.name)
            assertTrue(renamed.searchText.contains("manual category"))
            assertTrue(repo.dao.overrides(item.id).single().included)
            // Reconciliation and renaming must leave the original bytes unchanged.
            assertArrayEquals(
                bytes,
                context.contentResolver.openInputStream(Uri.parse(item.uri))!!.use {
                    it.readBytes()
                },
            )
            val removed = items.last()
            val removedUri =
                owned.single {
                    ContentUris.parseId(it) == ContentUris.parseId(Uri.parse(removed.uri))
                }
            context.contentResolver.delete(removedUri, null, null)
            owned.remove(removedUri)
            repo.reconcile()
            assertFalse(repo.dao.mediaById(removed.id)!!.accessible)
            assertNull(repo.dao.stageById(removed.id, "0hash"))
            assertEquals(102, repo.dao.accessibleMedia().count { it.folder == "Pictures/$key/" })
        } finally {
            db.close()
            owned.forEach { context.contentResolver.delete(it, null, null) }
            root.deleteRecursively()
            base.deleteSharedPreferences("$key-settings")
        }
    }

    /** Recover only this helper's byte-identical synthetic fixtures after interrupted cleanup. */
    private fun removeAbandonedPublicBatchFixtures(context: Context, expected: ByteArray) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val keys = mutableSetOf<String>()
        val fixtures = mutableListOf<Uri>()
        context.contentResolver
            .query(
                collection,
                arrayOf("_id", "relative_path", "_display_name"),
                "relative_path LIKE ?",
                arrayOf("Pictures/batch-%/"),
                null,
            )!!
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(1).removePrefix("Pictures/").removeSuffix("/")
                    val run = key.removePrefix("batch-")
                    if (runCatching { UUID.fromString(run).toString() }.getOrNull() != run) continue
                    val suffix = cursor.getString(2).removePrefix("$key-").removeSuffix(".png")
                    if (suffix != "renamed" && suffix.toIntOrNull() !in 0..102) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    val bytes =
                        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if (!bytes.contentEquals(expected)) continue
                    fixtures += uri
                    keys += key
                }
            }
        fixtures.forEach { context.contentResolver.delete(it, null, null) }
        keys.forEach {
            File(context.noBackupFilesDir, it).deleteRecursively()
            context.deleteSharedPreferences("$it-settings")
        }
    }
}
