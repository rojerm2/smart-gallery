package dev.smartgallery

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.smartgallery.analysis.IndexWorker
import dev.smartgallery.data.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PipelineIntegrationTest {
    private class IsolatedContext(base: Context, val key: String) : ContextWrapper(base) {
        private val root = File(base.noBackupFilesDir, key).apply { mkdirs() }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$key-$name", mode)

        fun cleanup() {
            root.deleteRecursively()
            deleteSharedPreferences("$key-settings")
        }
    }

    private fun worker(context: Context, repo: GalleryRepository) =
        TestListenableWorkerBuilder<IndexWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker? =
                        if (workerClassName == IndexWorker::class.java.name)
                            IndexWorker(context, workerParameters, repo)
                        else null
                }
            )
            .build()

    @Test
    fun realWorkerResumesVideoAfterDatabaseReopenAndPreservesCorrectionsOnReindex() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        requireFixtureOnlyGalleryAccess(instrumentation.targetContext)
        val context =
            IsolatedContext(instrumentation.targetContext, "pipeline-${UUID.randomUUID()}")
        val bytes =
            instrumentation.context.assets.open("later-appearance.mp4").use { it.readBytes() }
        val hash =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        val uri =
            context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${context.key}.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SmartGalleryTests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.Video.VideoColumns.DURATION, 12000L)
                },
            )!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        val name = "${context.key}.db"
        var db = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
        try {
            var repo = GalleryRepository(context, db)
            repo.reconcile()
            val item = repo.dao.accessibleMedia().first { it.name == "${context.key}.mp4" }
            repo.dao.hash(item.id, hash)
            repo.dao.stage(Stage(item.id, "0hash", state = "done"))
            repo.dao.stage(
                Stage(
                    item.id,
                    "1video",
                    state = "running",
                    checkpoint = 1,
                    version = GalleryRepository.videoVersion(false),
                )
            )
            repo.settings.paused = true
            assertEquals(ListenableWorker.Result.success(), worker(context, repo).doWork())
            assertEquals(1, repo.dao.stageById(item.id, "1video")!!.checkpoint)
            db.close()
            db = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
            repo = GalleryRepository(context, db)
            assertEquals(1, repo.dao.stageById(item.id, "1video")!!.checkpoint)
            repo.settings.paused = false
            assertEquals(ListenableWorker.Result.success(), worker(context, repo).doWork())
            assertTrue(repo.dao.mediaById(item.id)!!.analyzed)
            val faces = repo.dao.facesForMedia(item.id).filter { it.usable && it.active }
            assertTrue(faces.any { it.timestamp > 0 })
            assertTrue(repo.dao.peopleCards().first().all { it.support == 1 && !it.promoted })
            val face = faces.first()
            repo.assign(face.id, null, "Confirmed fixture person")
            val person = repo.dao.membershipById(face.id)!!.personId
            repo.settings.thorough = true
            repo.reindex()
            val active = async(Dispatchers.Default) { worker(context, repo).doWork() }
            try {
                withTimeout(60_000) {
                    while (
                        repo.dao.stageById(item.id, "1video")!!.let {
                            it.state != "running" || it.checkpoint < 2
                        }
                    ) {
                        check(!active.isCompleted) { "Need an actual in-flight video checkpoint" }
                        delay(10)
                    }
                }
                // An old decode must not overwrite this new queue's checkpoint or completion state.
                repo.reindex()
                active.await()
            } finally {
                active.cancelAndJoin()
            }
            assertEquals(0, repo.dao.stageById(item.id, "1video")!!.checkpoint)
            assertEquals("pending", repo.dao.stageById(item.id, "1video")!!.state)
            assertFalse(repo.dao.mediaById(item.id)!!.analyzed)
            assertEquals(ListenableWorker.Result.success(), worker(context, repo).doWork())
            assertEquals(person, repo.dao.membershipById(face.id)!!.personId)
            assertTrue(repo.dao.membershipById(face.id)!!.manual)
            val count = repo.dao.facesForMedia(item.id).size
            assertEquals(ListenableWorker.Result.success(), worker(context, repo).doWork())
            assertEquals(count, repo.dao.facesForMedia(item.id).size)
            File(repo.crops, GalleryRepository.cropPrefix(item.id) + "-orphan.jpg")
                .writeText("synthetic interrupted-write fixture")
            repo.exclude("media", item.id)
            assertTrue(repo.dao.facesForMedia(item.id).isEmpty())
            assertTrue(repo.crops.listFiles()!!.isEmpty())
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(bytes, original)
        } finally {
            db.close()
            context.deleteDatabase(name)
            // Only this test's newly created fixture is removed; no user original is touched.
            context.contentResolver.delete(uri, null, null)
            context.cleanup()
        }
    }
}
