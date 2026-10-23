package dev.smartgallery

import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import dev.smartgallery.analysis.IndexScheduler
import dev.smartgallery.analysis.IndexWorker
import dev.smartgallery.data.GalleryRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real OS constraints, controlled by tools/test-resource-constraints.ps1; no private media. */
@RunWith(AndroidJUnit4::class)
class ResourceConstraintTest {
    @Test
    fun productionJobDefersAndResumesWithTheRealSystemResourceSignal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("resourceMode")
        assumeTrue("Requires the explicit resource-test host", mode != null)
        assumeTrue("Resource host requires Android 10+ thermal APIs", Build.VERSION.SDK_INT >= 29)
        val context = instrumentation.targetContext
        check(context.packageName == "dev.smartgallery.validation")
        val run = UUID.fromString(args.getString("resourceRun")).toString()
        val marker = File(context.noBackupFilesDir, "resource-$run.json")
        val repo = GalleryRepository(context)
        val manager = WorkManager.getInstance(context)
        suspend fun cleanup() {
            IndexScheduler.pause(context)
            manager.cancelAllWork().result.get()
            if (marker.exists()) {
                val state = JSONObject(marker.readText())
                context.contentResolver.delete(Uri.parse(state.getString("uri")), null, null)
            }
            repo.reset()
            marker.delete()
        }
        if (mode == "cleanup") {
            cleanup()
            return@runBlocking
        }
        require(mode in setOf("charging", "thermal", "storage"))
        requireFixtureOnlyGalleryAccess(context)
        check(!marker.exists())
        IndexScheduler.pause(context)
        manager.cancelAllWork().result.get()
        repo.reset()
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        fun storageLow() =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)) != null
        when (mode) {
            "charging" -> assertFalse(battery.isCharging)
            "thermal" ->
                assertTrue(power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
            "storage" ->
                assertTrue("Controlled OS low-storage signal must be present", storageLow())
        }
        val bytes = instrumentation.context.assets.open("astronaut.png").use { it.readBytes() }
        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "resource-$run.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SmartGalleryTests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            )!!
        val state = JSONObject().put("uri", uri.toString()).put("blocked", false)
        marker.writeText(state.toString())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // Active-screen constraint check; unattended background timing is tested separately.
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            // Thermal policy must be observable independently of Android's charging debounce.
            repo.settings.chargingOnly = mode == "charging"
            repo.settings.paused = false
            repo.reconcile()
            val media = repo.dao.accessibleMedia().single { it.name == "resource-$run.png" }
            IndexScheduler.schedule(context)
            if (mode == "charging" || mode == "storage") {
                // Exercise actual JobScheduler constraints, not WorkManager's test driver.
                repeat(30) {
                    if (mode == "charging") assertFalse(battery.isCharging)
                    else assertTrue(storageLow())
                    assertTrue(repo.dao.mediaById(media.id)!!.hash.isEmpty())
                    assertEquals("pending", repo.dao.stageById(media.id, "0hash")!!.state)
                    delay(500)
                }
                val work = manager.getWorkInfosForUniqueWork(IndexScheduler.NOW).get()
                assertTrue(work.any { it.state == WorkInfo.State.ENQUEUED })
                assertTrue(work.all { it.constraints.requiresStorageNotLow() })
                if (mode == "charging") {
                    // Independent power guard, including stale CHARGING without a source.
                    val worker = TestListenableWorkerBuilder<IndexWorker>(context).build()
                    assertEquals(ListenableWorker.Result.retry(), worker.doWork())
                    assertEquals("Waiting for resources or resume", repo.settings.reason)
                    assertTrue(repo.dao.mediaById(media.id)!!.hash.isEmpty())
                }
                // The storage override changes the OS scheduling signal, not actual free
                // bytes. Do not claim that this exercises the separate StatFs worker guard.
            } else {
                // Severe thermal policy can prevent JobScheduler from invoking a job at all.
                // Also run the real worker against the real PowerManager signal to verify its
                // independent guard; queued OS work must later resume without a test driver.
                val worker = TestListenableWorkerBuilder<IndexWorker>(context).build()
                assertEquals(ListenableWorker.Result.retry(), worker.doWork())
                assertEquals("Waiting for resources or resume", repo.settings.reason)
                assertTrue(repo.dao.mediaById(media.id)!!.hash.isEmpty())
                assertEquals("pending", repo.dao.stageById(media.id, "0hash")!!.state)
            }
            state.put("blocked", true)
            val temp = File(marker.parentFile, "resource-$run.tmp")
            temp.writeText(state.toString())
            check(temp.renameTo(marker))
            // Host restores the real physical sensor reporting before work can proceed.
            withTimeout(240_000) {
                var observations = 0
                while (!repo.dao.mediaById(media.id)!!.analyzed) {
                    if (observations++ % 25 == 0) {
                        val signal =
                            context.registerReceiver(
                                null,
                                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                            )
                        val work = manager.getWorkInfosForUniqueWork(IndexScheduler.NOW).get()
                        val current = repo.dao.mediaById(media.id)!!
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString(
                                    "stream",
                                    "Resource observation: mode=$mode charging=${battery.isCharging} plugged=${signal?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)} status=${signal?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)} thermal=${power.currentThermalStatus} storageLow=${storageLow()} hashReady=${current.hash.isNotBlank()} work=${work.map { "${it.state}/${it.runAttemptCount}" }} reason=${repo.settings.reason}\n",
                                )
                            },
                        )
                    }
                    delay(200)
                }
            }
            if (mode == "charging") {
                val restored =
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!
                assertTrue(restored.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
                assertTrue(
                    restored.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
                        setOf(
                            BatteryManager.BATTERY_STATUS_CHARGING,
                            BatteryManager.BATTERY_STATUS_FULL,
                        )
                )
            }
            assertTrue(power.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE)
            if (mode == "storage") assertFalse(storageLow())
            assertTrue(repo.dao.facesForMedia(media.id).any { it.active && it.usable })
            assertArrayEquals(
                bytes,
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
            )
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "Resource constraint $mode: production job deferred without hashing, then completed real inference after sensor restoration; original unchanged.\n",
                    )
                },
            )
        } finally {
            cleanup()
            scenario.close()
        }
    }
}
