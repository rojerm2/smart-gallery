package dev.smartgallery

import android.content.ContentUris
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.FaceResult
import dev.smartgallery.analysis.IndexScheduler
import dev.smartgallery.analysis.VisionEngine
import dev.smartgallery.data.GallerySettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, consented phone-folder diagnostics. No images/OCR/embeddings leave the phone. */
@RunWith(AndroidJUnit4::class)
class PrivateFolderEvaluationTest {
    @Test
    fun reportOnlyAggregateDetectionAndResourceCountsForTheRequestedFolder() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("privateFolder")
        val cleanup = args.getString("folderEvalCleanup") == "true"
        assumeTrue("Requires an explicitly consented folder argument", requested != null || cleanup)
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = instrumentation.targetContext
        val settings = GallerySettings(context)
        val run = UUID.fromString(args.getString("folderEvalRun")).toString()
        val marker = File(context.noBackupFilesDir, "folder-evaluation-$run.json")
        fun restorePause() {
            if (marker.exists()) {
                val prior = JSONObject(marker.readText()).getBoolean("paused")
                if (!prior) IndexScheduler.resume(context) else IndexScheduler.pause(context)
                marker.delete()
            }
        }
        if (cleanup) {
            restorePause()
            return@runBlocking
        }
        val folder = requested!!.trimEnd('/') + "/"
        require(folder.startsWith("Pictures/") && !folder.contains("..") && !folder.contains('\\'))
        val limit = (args.getString("sampleLimit")?.toIntOrNull() ?: 5).coerceIn(1, 20)
        val offset = (args.getString("sampleOffset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val wasPaused = settings.paused
        check(!marker.exists())
        marker.writeText(JSONObject().put("paused", wasPaused).toString())
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val batteryBefore = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery.isCharging
        var folderPhotos = 0
        var processed = 0
        var failed = 0
        var facesTotal = 0
        var usableTotal = 0
        var qualityExcluded = 0
        var documentExcluded = 0
        var noUsablePhotos = 0
        var multipleUsablePhotos = 0
        var peakPss = Debug.getPss()
        var maximumThermal = power.currentThermalStatus
        var elapsed = 0L
        // Temporarily suspend ordinary library scheduling; do not reconcile/reset its database.
        IndexScheduler.pause(context)
        try {
            val collection =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val ids = mutableListOf<Pair<Long, Boolean>>()
            try {
                context.contentResolver
                    .query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf(folder),
                        "${MediaStore.MediaColumns._ID} ASC",
                    )
                    ?.use { cursor ->
                        folderPhotos = cursor.count
                        if (cursor.moveToPosition(offset))
                            do {
                                ids +=
                                    cursor.getLong(0) to
                                        (folder.contains("screenshot", true) ||
                                            cursor
                                                .getString(1)
                                                .orEmpty()
                                                .startsWith("screenshot", true))
                            } while (ids.size < limit && cursor.moveToNext())
                    } ?: error("Requested media collection is unavailable")
            } catch (_: SecurityException) {
                error("Requested folder is not accessible with current media permissions")
            }
            val start = SystemClock.elapsedRealtime()
            VisionEngine(context).use { engine ->
                for ((id, screenshot) in ids) {
                    val uri = ContentUris.withAppendedId(collection, id)
                    var bitmap: android.graphics.Bitmap? = null
                    var faces = emptyList<FaceResult>()
                    try {
                        bitmap = VisionEngine.decode(context, uri, 1280)
                        // Same document eligibility and source-detail retries as the photo worker.
                        val document =
                            engine.content(bitmap, screenshot).labels.any {
                                it.first in setOf("Document", "ID / document")
                            }
                        faces = engine.faces(bitmap, document)
                        if (faces.isEmpty() || faces.any { !it.usable }) {
                            faces.forEach { it.crop.recycle() }
                            faces = emptyList()
                            bitmap.recycle()
                            bitmap = null
                            bitmap = VisionEngine.decode(context, uri, 2560)
                            faces = engine.faces(bitmap, document)
                        }
                        val retried = faces.toMutableList()
                        faces = retried
                        for (i in retried.indices) retried[i] =
                            engine.retrySourceDetail(uri, retried[i], document)
                        val usable = faces.count { it.usable }
                        processed++
                        facesTotal += faces.size
                        usableTotal += usable
                        qualityExcluded +=
                            faces.count {
                                !it.usable && it.reason != "Small portrait on a document"
                            }
                        documentExcluded +=
                            faces.count { it.reason == "Small portrait on a document" }
                        if (usable == 0) noUsablePhotos++
                        if (usable > 1) multipleUsablePhotos++
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // Private source names, URIs, OCR and exception messages are never
                        // reported.
                        failed++
                    } finally {
                        faces.forEach { if (!it.crop.isRecycled) it.crop.recycle() }
                        bitmap?.let { if (!it.isRecycled) it.recycle() }
                    }
                    peakPss = maxOf(peakPss, Debug.getPss())
                    maximumThermal = maxOf(maximumThermal, power.currentThermalStatus)
                }
            }
            elapsed = SystemClock.elapsedRealtime() - start
        } finally {
            restorePause()
        }
        val report =
            JSONObject()
                .put("accessibleFolderPhotos", folderPhotos)
                .put("sampleOffset", offset)
                .put("requestedLimit", limit)
                .put("processed", processed)
                .put("failed", failed)
                .put("detectedFaces", facesTotal)
                .put("usableFaces", usableTotal)
                .put("qualityExcluded", qualityExcluded)
                .put("documentPortraitExcluded", documentExcluded)
                .put("noUsableFacePhotos", noUsablePhotos)
                .put("multipleUsableFacePhotos", multipleUsablePhotos)
                .put("elapsedMs", elapsed)
                .put("sampledPeakProcessPssKb", peakPss)
                .put("chargingAtStart", charging)
                .put("batteryPercentBefore", batteryBefore)
                .put(
                    "batteryPercentAfter",
                    battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                )
                .put("maximumThermalStatus", maximumThermal)
                .put("originalPauseStateRestored", settings.paused == wasPaused)
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "Consented folder aggregates: $report\nCounts have no ground-truth face/identity labels; no detection recall, matching accuracy, category accuracy, battery-drain or whole-library throughput claim. No media/OCR/embeddings exported.\n",
                )
            },
        )
    }
}
