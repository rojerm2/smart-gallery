package dev.smartgallery

import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.AtomicFile
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.smartgallery.analysis.IndexScheduler
import dev.smartgallery.analysis.VideoFrameReader
import dev.smartgallery.data.GalleryRepository
import dev.smartgallery.domain.VideoPlan
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual scheduled production work, confined to one owned public fixture and validation UID. */
@RunWith(AndroidJUnit4::class)
class LongVideoIntegrationTest {
    @Test
    fun scheduledThoroughAnalysisCompletesAllSixHundredSamples() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit long-video host", args.getString("longVideoRun") != null)
        assumeTrue(
            "Long-video resource observations require Android 10+",
            Build.VERSION.SDK_INT >= 29,
        )
        val context = instrumentation.targetContext
        check(context.packageName == "dev.smartgallery.validation")
        requireFixtureOnlyGalleryAccess(context)
        val run = UUID.fromString(args.getString("longVideoRun")).toString()
        val root = File(context.noBackupFilesDir, "long-video-$run").canonicalFile
        check(root.parentFile == context.noBackupFilesDir.canonicalFile)
        val marker = File(root, "state.json")
        val repo = GalleryRepository(context)
        val manager = WorkManager.getInstance(context)
        suspend fun cleanup() {
            if (!marker.exists()) return
            val state = readState(marker)
            IndexScheduler.pause(context)
            manager.cancelUniqueWork(IndexScheduler.NOW).result.get(15, TimeUnit.SECONDS)
            manager.cancelUniqueWork(IndexScheduler.PERIODIC).result.get(15, TimeUnit.SECONDS)
            var cleaned = false
            try {
                state
                    .optString("uri")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        val uri = Uri.parse(it)
                        check(uri.scheme == "content" && uri.authority == "media")
                        context.contentResolver.delete(uri, null, null)
                    }
                repo.reset()
                state.optJSONArray("temporaryExclusions")?.let { ids ->
                    for (i in 0 until ids.length()) repo.dao.removeExclusion(
                        "media",
                        ids.getString(i),
                    )
                }
                cleaned = true
            } finally {
                repo.settings.chargingOnly = state.getBoolean("charging")
                repo.settings.thorough = state.getBoolean("thorough")
                repo.settings.itemRetryQueueResetApplied = state.getBoolean("queueReset")
                repo.settings.paused = state.getBoolean("paused")
                if (!repo.settings.paused) IndexScheduler.schedule(context, now = false)
                assertEquals(state.getBoolean("paused"), repo.settings.paused)
                assertEquals(state.getBoolean("charging"), repo.settings.chargingOnly)
                assertEquals(state.getBoolean("thorough"), repo.settings.thorough)
                // Preserve the marker if cleanup fails so the host can retry the exact URI.
                if (cleaned) root.deleteRecursively()
            }
        }
        if (args.getString("longVideoPhase") == "cleanup") {
            cleanup()
            return@runBlocking
        }
        check(!root.exists()) { "Use a fresh long-video UUID" }
        check(root.mkdirs())
        val state =
            JSONObject()
                .put("paused", repo.settings.paused)
                .put("charging", repo.settings.chargingOnly)
                .put("thorough", repo.settings.thorough)
                .put("queueReset", repo.settings.itemRetryQueueResetApplied)
        writeState(marker, state)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            IndexScheduler.pause(context)
            manager.cancelUniqueWork(IndexScheduler.NOW).result.get(15, TimeUnit.SECONDS)
            manager.cancelUniqueWork(IndexScheduler.PERIODIC).result.get(15, TimeUnit.SECONDS)
            repo.reset() // Only this denied-gallery disposable installation.
            repo.settings.itemRetryQueueResetApplied = true
            val video = File(root, "repeated.mp4")
            val duration = repeatedVideoFixture(context, video, 100)
            val expectedSize = video.length()
            val uri =
                context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "long-video-$run.mp4")
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SmartGalleryTests/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        put(MediaStore.Video.VideoColumns.DURATION, duration)
                    },
                )!!
            state.put("uri", uri.toString())
            writeState(marker, state)
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                video.inputStream().use { it.copyTo(output) }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            // Let the actual provider publish size/metadata before testing monotonic checkpoints.
            withTimeout(20_000) {
                var stable = 0
                var previousModified = -1L
                while (stable < 2) {
                    context.contentResolver
                        .query(uri, arrayOf("_size", "date_modified"), null, null, null)!!
                        .use {
                            check(it.moveToFirst())
                            val modified = it.getLong(1)
                            stable =
                                if (it.getLong(0) == expectedSize && modified == previousModified)
                                    stable + 1
                                else 0
                            previousModified = modified
                        }
                    delay(500)
                }
            }
            video.delete()
            val originalDigest = digest(context, uri)
            val actualDuration = VideoFrameReader(context, uri).use { it.durationMs }
            assertTrue("Fixture must really exceed twenty minutes", actualDuration >= 1_200_000)
            val planned = VideoPlan.timestamps(actualDuration, true).size
            assertEquals(600, planned)
            repo.settings.thorough = true
            repo.settings.chargingOnly = false // Charging policy has a separate controlled test.
            repo.reconcile()
            val media =
                repo.dao.accessibleMedia().single {
                    it.uri == uri.toString() || it.name == "long-video-$run.mp4"
                }
            val others = repo.dao.accessibleMedia().filter { it.id != media.id }
            val existingExclusions =
                repo.dao.exclusionList().filter { it.kind == "media" }.map { it.value }.toSet()
            state.put(
                "temporaryExclusions",
                JSONArray(others.filter { it.id !in existingExclusions }.map { it.id }),
            )
            writeState(marker, state)
            for (other in others) repo.exclude("media", other.id) // Derived validation data only.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val interactiveAtStart = power.isInteractive
            val lockedAtStart = keyguard.isKeyguardLocked
            val start = SystemClock.elapsedRealtime()
            val cpuStart = Process.getElapsedCpuTime()
            var lastCheckpoint = 0
            var person: String? = null
            var confirmedFace: String? = null
            var pss = 0L
            var maxFds = 0
            var maxThermal = 0
            var maxUiCallbackMs = 0L
            var nextObservation = 0L
            val workRequests = mutableSetOf<UUID>()
            repo.settings.paused = false
            IndexScheduler.schedule(context, restartNow = true)!!.result.get(15, TimeUnit.SECONDS)
            withTimeout(900_000) {
                while (true) {
                    val stage = repo.dao.stageById(media.id, "1video")
                    if (stage != null) {
                        assertTrue(
                            "Persisted checkpoint must not move backwards",
                            stage.checkpoint >= lastCheckpoint,
                        )
                        assertNotEquals("Long video must not fail analysis", "failed", stage.state)
                        lastCheckpoint = stage.checkpoint
                    }
                    if (person == null && lastCheckpoint >= 4) {
                        repo.dao
                            .facesForMedia(media.id)
                            .firstOrNull { it.active && it.usable }
                            ?.let {
                                repo.assign(it.id, null, "Long-video fixture person")
                                repo.label(media.id, "Long-video fixture category", true)
                                confirmedFace = it.id
                                person = repo.dao.membershipById(it.id)!!.personId
                            }
                    }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    if (elapsed >= nextObservation) {
                        pss = maxOf(pss, Debug.getPss())
                        maxFds = maxOf(maxFds, File("/proc/self/fd").list()?.size ?: 0)
                        maxThermal = maxOf(maxThermal, power.currentThermalStatus)
                        val beforeUi = SystemClock.elapsedRealtime()
                        instrumentation.runOnMainSync {}
                        maxUiCallbackMs =
                            maxOf(maxUiCallbackMs, SystemClock.elapsedRealtime() - beforeUi)
                        val work =
                            manager
                                .getWorkInfosForUniqueWork(IndexScheduler.NOW)
                                .get(15, TimeUnit.SECONDS)
                        workRequests += work.map { it.id }
                        instrumentation.sendStatus(
                            2,
                            android.os.Bundle().apply {
                                putString(
                                    "stream",
                                    "Long-video progress: checkpoint=$lastCheckpoint/$planned state=${stage?.state ?: "pending-hash"} elapsedMs=$elapsed pssKb=${Debug.getPss()} fds=${File("/proc/self/fd").list()?.size ?: 0} thermal=${power.currentThermalStatus} work=${work.map { "${it.state}/${it.runAttemptCount}" }}\n",
                                )
                            },
                        )
                        nextObservation = elapsed + 15_000
                    }
                    if (repo.dao.mediaById(media.id)!!.analyzed) break
                    delay(500)
                }
            }
            val completed = repo.dao.stageById(media.id, "1video")!!
            assertEquals("done", completed.state)
            assertEquals(600, completed.checkpoint)
            assertNotNull(person)
            val membership = repo.dao.membershipById(confirmedFace!!)!!
            assertTrue(membership.manual)
            assertEquals(person, membership.personId)
            assertTrue(
                repo.dao.overrides(media.id).any {
                    it.label == "Long-video fixture category" && it.included
                }
            )
            val faces = repo.dao.facesForMedia(media.id)
            assertTrue(
                "Find actual appearances late in the clip",
                faces.any { it.active && it.usable && it.timestamp > actualDuration * 9 / 10 },
            )
            assertTrue(repo.dao.peopleCards().first().all { it.support <= 1 })
            assertEquals(1, repo.dao.peopleCards().first().single { it.id == person }.support)
            assertTrue(
                "Original fixture must remain unchanged",
                originalDigest == digest(context, uri),
            )
            // A second actual enqueue must observe completed checkpoints and produce no duplicates.
            IndexScheduler.schedule(context)!!.result.get(15, TimeUnit.SECONDS)
            withTimeout(30_000) {
                while (
                    manager
                        .getWorkInfosForUniqueWork(IndexScheduler.NOW)
                        .get(15, TimeUnit.SECONDS)
                        .any { !it.state.isFinished }
                ) delay(200)
            }
            assertEquals(faces.size, repo.dao.facesForMedia(media.id).size)
            assertEquals(600, repo.dao.stageById(media.id, "1video")!!.checkpoint)
            assertTrue(maxUiCallbackMs < 3_000)
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString(
                        "stream",
                        "Long-video complete: durationMs=$actualDuration checkpoints=600/600 requestsObserved=${workRequests.size} faces=${faces.size} elapsedMs=${SystemClock.elapsedRealtime()-start} appCpuMs=${Process.getElapsedCpuTime()-cpuStart} sampledPeakPssKb=$pss sampledMaxFds=$maxFds maxThermal=$maxThermal maxMainCallbackMs=$maxUiCallbackMs interactiveStart=$interactiveAtStart lockedStart=$lockedAtStart interactiveEnd=${power.isInteractive} lockedEnd=${keyguard.isKeyguardLocked}; corrections/original/idempotence preserved; activity screen-hold requested, public repeated H264 fixture, not a battery or general throughput benchmark.\n",
                    )
                },
            )
        } finally {
            try {
                cleanup()
            } finally {
                scenario?.close()
            }
        }
    }

    private fun readState(file: File): JSONObject =
        AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()) }

    private fun writeState(file: File, state: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(state.toString().toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }

    private fun digest(context: Context, uri: Uri): String {
        val hash = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
