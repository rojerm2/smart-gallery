package dev.smartgallery

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.smartgallery.analysis.IndexWorker
import dev.smartgallery.analysis.VideoFrameReader
import dev.smartgallery.data.GalleryDatabase
import dev.smartgallery.data.GalleryRepository
import dev.smartgallery.domain.VideoPlan
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly orchestrated by tools/test-process-recovery.ps1; never uses private gallery media. */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryTest {
    private class FixtureContext(base: Context, val key: String) : ContextWrapper(base) {
        val root = File(base.noBackupFilesDir, key).apply { mkdirs() }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }

        override fun getFilesDir() = File(root, "files").apply { mkdirs() }

        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$key-$name", mode)
    }

    private fun worker(context: Context, repo: GalleryRepository) =
        TestListenableWorkerBuilder<IndexWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        context: Context,
                        name: String,
                        params: WorkerParameters,
                    ): ListenableWorker? =
                        if (name == IndexWorker::class.java.name) IndexWorker(context, params, repo)
                        else null
                }
            )
            .build()

    @Test
    fun recoverAfterRequestedProcessKill() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("recoveryPhase")
        assumeTrue(
            "Run the explicit process-recovery orchestrator; no process is killed by the normal suite",
            phase != null,
        )
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        require(phase in setOf("prepare", "resume", "cleanup"))
        val run = UUID.fromString(requireNotNull(args.getString("recoveryRun"))).toString()
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = FixtureContext(base, "recovery-$run")
        if (phase == "cleanup") {
            cleanup(context)
            return@runBlocking
        }
        requireFixtureOnlyGalleryAccess(base)
        val db =
            Room.databaseBuilder(
                    context,
                    GalleryDatabase::class.java,
                    File(context.root, "recovery.db").absolutePath,
                )
                .build()
        try {
            val repo = GalleryRepository(context, db)
            if (phase == "prepare") prepare(context, repo) else resume(context, repo)
        } finally {
            db.close()
            if (phase == "resume") cleanup(context)
        }
    }

    private suspend fun prepare(context: FixtureContext, repo: GalleryRepository): Unit =
        coroutineScope {
            check(!File(context.root, "state.json").exists()) { "Use a fresh recovery run UUID" }
            // Repeat compressed packets from the existing licensed fixture. No new model/test asset
            // is
            // shipped.
            val video = File(context.cacheDir, "repeated.mp4")
            val duration = repeatedVideoFixture(context, video)
            val uri =
                context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "${context.key}.mp4")
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SmartGalleryTests/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        put(MediaStore.Video.VideoColumns.DURATION, duration)
                    },
                )!!
            val state = JSONObject().put("uri", uri.toString()).put("pid", Process.myPid())
            writeJson(File(context.root, "state.json"), state)
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                video.inputStream().use { it.copyTo(out) }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            state.put("hash", digest(context, uri))
            repo.settings.thorough = true
            repo.settings.chargingOnly =
                false // Direct worker execution; this does not test OS charging constraints.
            repo.settings.paused = false
            repo.reconcile()
            // MediaStore can expose the same owned row through external and external_primary
            // aliases.
            val media = repo.dao.accessibleMedia().single { it.name == "${context.key}.mp4" }
            val planned =
                VideoFrameReader(context, uri).use {
                    VideoPlan.timestamps(it.durationMs, true).size
                }
            state.put("mediaId", media.id).put("planned", planned)
            writeJson(File(context.root, "state.json"), state)
            val job = async(Dispatchers.IO) { worker(context, repo).doWork() }
            try {
                withTimeout(90_000) {
                    while (true) {
                        val stage = repo.dao.stageById(media.id, "1video")
                        val face =
                            repo.dao.facesForMedia(media.id).firstOrNull { it.usable && it.active }
                        if (
                            stage != null &&
                                stage.state == "running" &&
                                stage.checkpoint >= 4 &&
                                face != null
                        ) {
                            repo.assign(face.id, null, "Recovery fixture person")
                            repo.label(media.id, "Recovery fixture", true)
                            val current = repo.dao.stageById(media.id, "1video")!!
                            check(
                                current.state == "running" &&
                                    current.checkpoint < state.getInt("planned")
                            )
                            state
                                .put("checkpoint", current.checkpoint)
                                .put("faceId", face.id)
                                .put("personId", repo.dao.membershipById(face.id)!!.personId)
                            writeJson(File(context.root, "state.json"), state)
                            writeJson(File(context.root, "ready.json"), state)
                            break
                        }
                        check(job.isActive) {
                            "Worker finished before an interruptible checkpoint: ${job.await()}"
                        }
                        delay(30)
                    }
                }
                // Host requests self-SIGKILL while the real worker continues inference.
                // A completed checkpoint is rejected rather than counted as interruption coverage.
                while (!File(context.root, "interrupt.request").exists()) {
                    check(job.isActive) { "Worker completed before host requested interruption" }
                    delay(30)
                }
                val atKill = repo.dao.stageById(media.id, "1video")!!
                check(atKill.state == "running" && atKill.checkpoint < state.getInt("planned"))
                state.put("checkpoint", atKill.checkpoint).put("killRequested", true)
                writeJson(File(context.root, "state.json"), state)
                InstrumentationRegistry.getInstrumentation()
                    .sendStatus(
                        2,
                        android.os.Bundle().apply {
                            putString(
                                "stream",
                                "Test requests SIGKILL at checkpoint ${atKill.checkpoint}/${state.getInt("planned")}\n",
                            )
                        },
                    )
                Process.killProcess(Process.myPid())
                awaitCancellation()
            } finally {
                job.cancelAndJoin()
            }
        }

    private suspend fun resume(context: FixtureContext, repo: GalleryRepository) {
        val state = readJson(File(context.root, "state.json"))
        assertTrue("Preparation must reach the SIGKILL request", state.getBoolean("killRequested"))
        assertNotEquals(
            "Recovery must use a different Linux process",
            state.getInt("pid"),
            Process.myPid(),
        )
        val id = state.getString("mediaId")
        val before = repo.dao.stageById(id, "1video")!!
        assertEquals("Host must interrupt actual running work", "running", before.state)
        assertTrue(before.checkpoint >= state.getInt("checkpoint"))
        assertTrue(
            "Worker finished before host interruption",
            before.checkpoint < state.getInt("planned"),
        )
        assertTrue(repo.settings.thorough)
        assertFalse(repo.settings.paused)
        assertFalse(repo.settings.chargingOnly)
        val faceId = state.getString("faceId")
        val personId = state.getString("personId")
        assertEquals(personId, repo.dao.membershipById(faceId)!!.personId)
        assertTrue(repo.dao.membershipById(faceId)!!.manual)
        assertEquals("Recovery fixture person", repo.dao.personById(personId)!!.name)
        assertTrue(repo.dao.overrides(id).any { it.label == "Recovery fixture" && it.included })
        assertEquals(ListenableWorker.Result.success(), worker(context, repo).doWork())
        assertTrue(repo.dao.mediaById(id)!!.analyzed)
        val completed = repo.dao.stageById(id, "1video")!!
        assertEquals("done", completed.state)
        assertEquals(state.getInt("planned"), completed.checkpoint)
        assertEquals(personId, repo.dao.membershipById(faceId)!!.personId)
        assertTrue(repo.dao.membershipById(faceId)!!.manual)
        val cards = repo.dao.peopleCards().first()
        assertTrue(cards.all { it.support <= 1 })
        assertEquals(1, cards.single { it.id == personId }.support)
        val count = repo.dao.facesForMedia(id).size
        assertEquals(ListenableWorker.Result.success(), worker(context, repo).doWork())
        assertEquals(count, repo.dao.facesForMedia(id).size)
        assertEquals(state.getString("hash"), digest(context, Uri.parse(state.getString("uri"))))
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(
                2,
                android.os.Bundle().apply {
                    putString(
                        "stream",
                        "Actual process recovery: checkpoint ${before.checkpoint}/${state.getInt("planned")} resumed to ${completed.checkpoint}; manual identity/category preserved; original unchanged.\n",
                    )
                },
            )
    }

    private fun cleanup(context: FixtureContext) {
        val file = File(context.root, "state.json")
        if (file.exists()) {
            val uri = Uri.parse(readJson(file).getString("uri"))
            // Only the exact URI inserted by this run; never query/delete user gallery items.
            context.contentResolver.delete(uri, null, null)
        }
        context.root.deleteRecursively()
        context.deleteSharedPreferences("${context.key}-settings")
    }

    private fun writeJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(value.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun readJson(file: File) =
        JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })

    private fun digest(context: Context, uri: Uri): String {
        val sha = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                sha.update(bytes, 0, n)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun repeatedVideoFixture(context: Context, output: File, repetitions: Int = 10): Long {
    val seed = File(context.cacheDir, "seed.mp4")
    InstrumentationRegistry.getInstrumentation().context.assets.open("later-appearance.mp4").use {
        input ->
        seed.outputStream().use { input.copyTo(it) }
    }
    val extractor = MediaExtractor()
    val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
        extractor.setDataSource(seed.path)
        val track =
            (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
        val format = extractor.getTrackFormat(track)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        extractor.selectTrack(track)
        val target = muxer.addTrack(format)
        muxer.start()
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        repeat(repetitions) { loop ->
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size <= buffer.capacity())
                info.set(0, size, loop * durationUs + extractor.sampleTime, extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(target, buffer, info)
                extractor.advance()
            }
        }
        muxer.stop()
        return durationUs * repetitions / 1000
    } finally {
        muxer.release()
        extractor.release()
    }
}
