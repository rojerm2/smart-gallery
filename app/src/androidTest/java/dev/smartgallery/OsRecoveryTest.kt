package dev.smartgallery

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in OS scheduling/reboot check. Never runs against the personal installation. */
@RunWith(AndroidJUnit4::class)
class OsRecoveryTest {
    @Test
    fun productionWorkContinuesAfterAnOsInterruption() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("osPhase")
        assumeTrue("Requires explicit host orchestration", phase != null)
        val context = instrumentation.targetContext
        check(context.packageName == "dev.smartgallery.validation")
        val run =
            UUID.fromString(InstrumentationRegistry.getArguments().getString("osRun")).toString()
        val root = File(context.noBackupFilesDir, "os-recovery-$run")
        val marker = File(root, "state.json")
        val repo = GalleryRepository(context)
        suspend fun cleanup() {
            IndexScheduler.pause(context)
            WorkManager.getInstance(context).cancelAllWork().result.get()
            if (marker.exists()) {
                val state = JSONObject(marker.readText())
                // Exact URI recorded immediately after insertion; no broad deletion.
                context.contentResolver.delete(Uri.parse(state.getString("uri")), null, null)
            }
            repo.reset()
            root.deleteRecursively()
        }
        when (phase) {
            "cleanup" -> cleanup()
            "prepare" -> {
                requireFixtureOnlyGalleryAccess(context)
                check(!root.exists())
                // Only the disposable validation installation is reset.
                IndexScheduler.pause(context)
                WorkManager.getInstance(context).cancelAllWork().result.get()
                repo.reset()
                root.mkdirs()
                val video = File(root, "repeated.mp4")
                val duration = repeatedVideoFixture(context, video, 100)
                val uri =
                    context.contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "os-recovery-$run.mp4")
                            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SmartGalleryTests/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                            put(MediaStore.Video.VideoColumns.DURATION, duration)
                        },
                    )!!
                val state = JSONObject().put("uri", uri.toString()).put("pid", Process.myPid())
                marker.writeText(state.toString())
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    video.inputStream().use { it.copyTo(out) }
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                video.delete()
                state
                    .put("hash", digest(uri))
                    .put(
                        "planned",
                        VideoFrameReader(context, uri).use {
                            VideoPlan.timestamps(it.durationMs, true).size
                        },
                    )
                repo.settings.thorough = true
                repo.settings.chargingOnly = true
                repo.settings.paused = false
                repo.reconcile()
                val media = repo.dao.accessibleMedia().single { it.name == "os-recovery-$run.mp4" }
                state.put("mediaId", media.id)
                marker.writeText(state.toString())
                // The actual production scheduler/worker/default database must survive boot.
                IndexScheduler.schedule(context)
                withTimeout(110_000) {
                    while (true) {
                        val stage = repo.dao.stageById(media.id, "1video")
                        val face =
                            repo.dao.facesForMedia(media.id).firstOrNull { it.active && it.usable }
                        if (stage?.state == "running" && stage.checkpoint >= 4 && face != null) {
                            repo.assign(face.id, null, "OS recovery fixture person")
                            repo.label(media.id, "OS recovery fixture", true)
                            val current = repo.dao.stageById(media.id, "1video")!!
                            check(current.checkpoint < state.getInt("planned"))
                            state
                                .put("checkpoint", current.checkpoint)
                                .put("faceId", face.id)
                                .put("personId", repo.dao.membershipById(face.id)!!.personId)
                                .put("ready", true)
                            // Atomic rename prevents a host probe reading partially written state.
                            val temp = File(root, "state.tmp")
                            temp.writeText(state.toString())
                            check(temp.renameTo(marker))
                            break
                        }
                        delay(100)
                    }
                }
                // Host reboots only after verifying this marker and incomplete real checkpoint.
                // Preparation intentionally ends through reboot, not through test success.
                withTimeout(90_000) { while (true) delay(500) }
            }
            "verify" -> {
                requireFixtureOnlyGalleryAccess(context)
                val state = JSONObject(marker.readText())
                check(state.getBoolean("ready"))
                assertNotEquals(state.getInt("pid"), Process.myPid())
                val stage = repo.dao.stageById(state.getString("mediaId"), "1video")!!
                assertTrue(
                    "OS must advance before host verification starts",
                    stage.checkpoint > state.getInt("checkpoint"),
                )
                val membership = repo.dao.membershipById(state.getString("faceId"))!!
                assertTrue(membership.manual)
                assertEquals(state.getString("personId"), membership.personId)
                assertEquals(
                    "OS recovery fixture person",
                    repo.dao.personById(membership.personId)!!.name,
                )
                assertTrue(
                    repo.dao.overrides(state.getString("mediaId")).any {
                        it.label == "OS recovery fixture" && it.included
                    }
                )
                assertTrue(repo.settings.chargingOnly)
                assertTrue(repo.settings.thorough)
                assertFalse(repo.settings.paused)
                assertEquals(state.getString("hash"), digest(Uri.parse(state.getString("uri"))))
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "stream",
                            "OS recovery assertion: checkpoint ${state.getInt("checkpoint")} -> ${stage.checkpoint}/${state.getInt("planned")}; corrections/settings/original preserved.\n",
                        )
                    },
                )
            }
            else -> error("Unknown explicit OS test phase")
        }
    }

    private fun digest(uri: Uri): String {
        val sha = MessageDigest.getInstance("SHA-256")
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .contentResolver
            .openInputStream(uri)!!
            .use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sha.update(buffer, 0, n)
                }
            }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
