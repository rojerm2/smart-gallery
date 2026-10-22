package dev.smartgallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.smartgallery.analysis.IndexScheduler
import dev.smartgallery.analysis.IndexWorker
import dev.smartgallery.data.GallerySettings
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real WorkManager request replacement, confined to the disposable validation installation. */
@RunWith(AndroidJUnit4::class)
class SchedulerIntegrationTest {
    @Test
    fun automaticRefreshKeepsPendingWorkButExplicitScanReplacesAnOldDelay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireFixtureOnlyGalleryAccess(context)
        check(context.packageName == "dev.smartgallery.validation")
        val settings = GallerySettings(context)
        val paused = settings.paused
        val charging = settings.chargingOnly
        val reset = settings.itemRetryQueueResetApplied
        val manager = WorkManager.getInstance(context)
        try {
            settings.paused = false
            settings.chargingOnly = true
            settings.itemRetryQueueResetApplied = true
            val delayed =
                OneTimeWorkRequestBuilder<IndexWorker>()
                    .setInitialDelay(30, TimeUnit.MINUTES)
                    .build()
            manager
                .enqueueUniqueWork(IndexScheduler.NOW, ExistingWorkPolicy.REPLACE, delayed)
                .result
                .get(15, TimeUnit.SECONDS)
            assertEquals(
                TimeUnit.MINUTES.toMillis(30),
                manager.getWorkInfoById(delayed.id).get(15, TimeUnit.SECONDS)!!.initialDelayMillis,
            )
            IndexScheduler.schedule(context)!!.result.get(15, TimeUnit.SECONDS)
            assertEquals(
                delayed.id,
                manager
                    .getWorkInfosForUniqueWork(IndexScheduler.NOW)
                    .get(15, TimeUnit.SECONDS)
                    .single()
                    .id,
            )
            IndexScheduler.resume(context, restartNow = true)!!.result.get(15, TimeUnit.SECONDS)
            val replacement =
                manager
                    .getWorkInfosForUniqueWork(IndexScheduler.NOW)
                    .get(15, TimeUnit.SECONDS)
                    .single()
            assertNotEquals(delayed.id, replacement.id)
            assertEquals(0L, replacement.initialDelayMillis)
            assertTrue(replacement.runAttemptCount <= 1)
            assertTrue(replacement.constraints.requiresCharging())
            assertTrue(replacement.constraints.requiresBatteryNotLow())
            assertTrue(replacement.constraints.requiresStorageNotLow())
        } finally {
            manager.cancelUniqueWork(IndexScheduler.NOW).result.get(15, TimeUnit.SECONDS)
            manager.cancelUniqueWork(IndexScheduler.PERIODIC).result.get(15, TimeUnit.SECONDS)
            settings.paused = paused
            settings.chargingOnly = charging
            settings.itemRetryQueueResetApplied = reset
            if (!paused) IndexScheduler.schedule(context, now = false)
        }
    }
}
