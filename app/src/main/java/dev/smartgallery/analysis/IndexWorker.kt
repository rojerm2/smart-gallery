package dev.smartgallery.analysis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.work.*
import dev.smartgallery.data.*
import dev.smartgallery.domain.VideoPlan
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object IndexScheduler {
    const val NOW = "gallery-index-now"
    const val PERIODIC = "gallery-index-periodic"

    private fun constraints(context: Context) =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiresCharging(GallerySettings(context).chargingOnly)
            .build()

    fun schedule(context: Context, now: Boolean = true, restartNow: Boolean = false): Operation? {
        if (GallerySettings(context).paused) return null
        val work = WorkManager.getInstance(context)
        val periodic =
            work.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<IndexWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints(context))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        return if (now)
            work.enqueueUniqueWork(
                NOW,
                if (restartNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>()
                    .setConstraints(constraints(context))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build(),
            )
        else periodic
    }

    fun pause(context: Context) {
        GallerySettings(context).paused = true
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    fun resume(context: Context, restartNow: Boolean = false): Operation? {
        GallerySettings(context).paused = false
        return schedule(context, restartNow = restartNow)
    }

    /** Fresh slices isolate item retries; resource/provider deferrals use WorkManager backoff. */
    fun continueSoon(context: Context, delaySeconds: Long = 0): Operation? {
        if (GallerySettings(context).paused) return null
        return WorkManager.getInstance(context)
            .enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<IndexWorker>()
                    .setConstraints(constraints(context))
                    .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build(),
            )
    }
}

class IndexWorker
@JvmOverloads
constructor(
    context: Context,
    params: WorkerParameters,
    private val repository: GalleryRepository = GalleryRepository(context),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        workerMutex.withLock {
            withContext(Dispatchers.IO) {
                val repo = repository
                val dao = repo.dao
                if (repo.settings.paused) return@withContext Result.success()
                val generation = repo.settings.generation
                fun shouldYield(): Boolean {
                    val power = applicationContext.getSystemService(PowerManager::class.java)
                    return isStopped ||
                        repo.settings.paused ||
                        repo.settings.generation != generation ||
                        (repo.settings.chargingOnly && !hasChargingPower()) ||
                        StatFs(applicationContext.filesDir.path).availableBytes <
                            200L * 1024 * 1024 ||
                        (android.os.Build.VERSION.SDK_INT >= 29 &&
                            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
                }
                try {
                    repo.settings.reason = "Checking accessible media"
                    repo.reconcile()
                    repo.recoverSourceDetailFailures()
                    // Initial discovery can exceed a slice on a large library. Give inference
                    // its own budget so every continuation can actually consume the queue.
                    val start = elapsedRealtime()
                    if (dao.nextStage() == null) {
                        repo.settings.reason = "Up to date"
                        return@withContext Result.success()
                    }
                    // Scheduler constraints can lag a power/thermal transition on some devices.
                    // Recheck before allocating inference resources as well as at checkpoints.
                    if (shouldYield()) {
                        repo.settings.reason = "Waiting for resources or resume"
                        return@withContext if (repo.settings.paused) Result.success()
                        else Result.retry()
                    }
                    VisionEngine(applicationContext).use { engine ->
                        while (elapsedRealtime() - start < 120_000) {
                            if (shouldYield()) {
                                repo.settings.reason = "Waiting for resources or resume"
                                return@withContext if (repo.settings.paused) Result.success()
                                else Result.retry()
                            }
                            val stage =
                                dao.nextStage()
                                    ?: run {
                                        repo.settings.reason = "Up to date"
                                        return@withContext Result.success()
                                    }
                            val item = dao.mediaById(stage.mediaId)
                            if (item == null || !item.accessible || item.excluded) {
                                dao.deleteStages(stage.mediaId)
                                continue
                            }
                            repo.settings.reason =
                                if (item.video) "Analyzing video frames" else "Analyzing photos"
                            val claimed =
                                GalleryRepository.gate.withLock {
                                    repo.db.withTransaction {
                                        if (
                                            !valid(repo, item, generation) ||
                                                dao.stageById(stage.mediaId, stage.kind) != stage
                                        )
                                            false
                                        else {
                                            dao.stage(stage.copy(state = "running"))
                                            true
                                        }
                                    }
                                }
                            if (!claimed) continue
                            try {
                                when (stage.kind) {
                                    "0hash" -> {
                                        val hash = hash(Uri.parse(item.uri))
                                        GalleryRepository.gate.withLock {
                                            repo.db.withTransaction {
                                                if (!valid(repo, item, generation))
                                                    return@withTransaction
                                                if (item.hash.isNotBlank() && hash != item.hash)
                                                    repo.purgeDerived(item.id)
                                                dao.hash(item.id, hash)
                                                dao.stage(stage.copy(state = "done"))
                                                repo.enqueueAnalysis(item.copy(hash = hash))
                                            }
                                        }
                                    }
                                    "1faces" -> {
                                        // Try a moderate decode first, then real higher-resolution
                                        // pixels for face detail.
                                        var bitmap =
                                            VisionEngine.decode(
                                                applicationContext,
                                                Uri.parse(item.uri),
                                                1280,
                                            )
                                        val document =
                                            engine.content(bitmap, isScreenshot(item)).labels.any {
                                                it.first in setOf("Document", "ID / document")
                                            }
                                        var faces = engine.faces(bitmap, document = document)
                                        if (faces.isEmpty() || faces.any { !it.usable }) {
                                            faces.forEach { it.crop.recycle() }
                                            bitmap.recycle()
                                            bitmap =
                                                VisionEngine.decode(
                                                    applicationContext,
                                                    Uri.parse(item.uri),
                                                    2560,
                                                )
                                            faces = engine.faces(bitmap, document = document)
                                        }
                                        faces =
                                            faces.map {
                                                engine.retrySourceDetail(
                                                    Uri.parse(item.uri),
                                                    it,
                                                    document,
                                                )
                                            }
                                        try {
                                            saveFaces(repo, item, 0, faces, generation)
                                            finish(repo, stage, item, generation)
                                        } finally {
                                            faces.forEach { it.crop.recycle() }
                                            bitmap.recycle()
                                        }
                                    }
                                    "2content" -> {
                                        val bitmap =
                                            VisionEngine.decode(
                                                applicationContext,
                                                Uri.parse(item.uri),
                                                1600,
                                            )
                                        try {
                                            val content = engine.content(bitmap, isScreenshot(item))
                                            saveContent(repo, item, 0, content, generation)
                                            finish(repo, stage, item, generation)
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                    "1video" -> {
                                        val thorough = stage.version.endsWith("-thorough")
                                        val retriever =
                                            VideoFrameReader(
                                                applicationContext,
                                                Uri.parse(item.uri),
                                            )
                                        try {
                                            val frames =
                                                VideoPlan.timestamps(
                                                    retriever.durationMs.takeIf { it > 0 }
                                                        ?: item.duration,
                                                    thorough,
                                                )
                                            var index = stage.checkpoint
                                            while (
                                                index < frames.size &&
                                                    elapsedRealtime() - start < 120_000 &&
                                                    !shouldYield()
                                            ) {
                                                val decoded = retriever.frameAt(frames[index])
                                                val timestamp = decoded.timestampMs
                                                val frame = decoded.bitmap
                                                try {
                                                    val content =
                                                        engine.content(frame, isScreenshot(item))
                                                    val faces =
                                                        engine.faces(
                                                            frame,
                                                            content.labels.any {
                                                                it.first == "Document"
                                                            },
                                                        )
                                                    try {
                                                        // Results and checkpoint committed
                                                        // together; retrying cannot inflate rows.
                                                        GalleryRepository.gate.withLock {
                                                            repo.db.withTransaction {
                                                                if (!valid(repo, item, generation))
                                                                    return@withTransaction
                                                                if (index == 0) {
                                                                    dao.deleteLabels(item.id)
                                                                    dao.deleteOcr(item.id)
                                                                }
                                                                saveContentUnlocked(
                                                                    repo,
                                                                    item,
                                                                    timestamp,
                                                                    content,
                                                                )
                                                                saveFacesUnlocked(
                                                                    repo,
                                                                    item,
                                                                    timestamp,
                                                                    faces,
                                                                )
                                                                dao.stage(
                                                                    stage.copy(
                                                                        state = "running",
                                                                        checkpoint = index + 1,
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    } finally {
                                                        faces.forEach { it.crop.recycle() }
                                                    }
                                                } finally {
                                                    frame.recycle()
                                                }
                                                index++
                                            }
                                            if (index == frames.size)
                                                finish(
                                                    repo,
                                                    stage.copy(checkpoint = index),
                                                    item,
                                                    generation,
                                                )
                                            else {
                                                GalleryRepository.gate.withLock {
                                                    if (valid(repo, item, generation))
                                                        dao.stage(
                                                            stage.copy(
                                                                state = "pending",
                                                                checkpoint = index,
                                                            )
                                                        )
                                                }
                                                repo.refreshSearch(item.id)
                                                IndexScheduler.continueSoon(applicationContext)
                                                return@withContext Result.success()
                                            }
                                        } finally {
                                            retriever.close()
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: SecurityException) {
                                GalleryRepository.gate.withLock {
                                    repo.db.withTransaction {
                                        if (!valid(repo, item, generation)) return@withTransaction
                                        dao.inaccessible(item.id)
                                        repo.purgeDerived(item.id)
                                    }
                                }
                            } catch (_: java.io.FileNotFoundException) {
                                GalleryRepository.gate.withLock {
                                    repo.db.withTransaction {
                                        if (!valid(repo, item, generation)) return@withTransaction
                                        dao.inaccessible(item.id)
                                        repo.purgeDerived(item.id)
                                    }
                                }
                            } catch (_: Exception) {
                                // No URI, identity, OCR, image contents or exception message is
                                // logged.
                                var failed = true
                                GalleryRepository.gate.withLock {
                                    repo.db.withTransaction {
                                        if (!valid(repo, item, generation)) return@withTransaction
                                        val latest = dao.stageById(item.id, stage.kind) ?: stage
                                        failed = latest.attempts + 1 >= 3
                                        dao.stage(
                                            latest.copy(
                                                state = if (failed) "failed" else "retry",
                                                attempts = latest.attempts + 1,
                                                error = "Analysis could not complete",
                                            )
                                        )
                                        dao.analyzed(
                                            item.id,
                                            false,
                                            "Analysis failed; retry or exclude this item",
                                        )
                                    }
                                }
                                if (!failed) {
                                    // Per-item attempts are persisted separately. A fresh delayed
                                    // slice avoids compounding global backoff across bad files.
                                    repo.settings.reason = "Queued to retry an item"
                                    IndexScheduler.continueSoon(
                                        applicationContext,
                                        delaySeconds = 10,
                                    )
                                    return@withContext Result.success()
                                }
                            }
                        }
                    }
                    repo.settings.reason = "Queued to continue"
                    IndexScheduler.continueSoon(applicationContext)
                    Result.success()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    repo.settings.reason = "Indexing interrupted; will retry"
                    Result.retry()
                }
            }
        }

    private suspend fun valid(repo: GalleryRepository, item: Media, generation: Long): Boolean {
        val current = repo.dao.mediaById(item.id)
        return current != null &&
            current.accessible &&
            !current.excluded &&
            current.modified == item.modified &&
            current.size == item.size &&
            repo.settings.generation == generation
    }

    private suspend fun finish(
        repo: GalleryRepository,
        stage: Stage,
        item: Media,
        generation: Long,
    ) =
        GalleryRepository.gate.withLock {
            repo.db.withTransaction {
                if (!valid(repo, item, generation)) return@withTransaction
                repo.dao.stage(stage.copy(state = "done", error = ""))
                val kinds =
                    if (item.video) listOf("0hash", "1video")
                    else listOf("0hash", "1faces", "2content")
                if (kinds.all { repo.dao.stageById(item.id, it)?.state == "done" })
                    repo.dao.analyzed(item.id, true)
                repo.refreshSearch(item.id)
            }
        }

    private suspend fun saveFaces(
        repo: GalleryRepository,
        item: Media,
        timestamp: Long,
        faces: List<FaceResult>,
        generation: Long,
    ) =
        GalleryRepository.gate.withLock {
            repo.db.withTransaction {
                if (valid(repo, item, generation)) saveFacesUnlocked(repo, item, timestamp, faces)
            }
        }

    private suspend fun saveFacesUnlocked(
        repo: GalleryRepository,
        item: Media,
        timestamp: Long,
        faces: List<FaceResult>,
    ) {
        val previous = repo.dao.facesForMedia(item.id).filter { it.timestamp == timestamp }
        val used = mutableSetOf<String>()
        for (result in faces) {
            val prior =
                previous
                    .filter { it.id !in used && it.hash == item.hash }
                    .maxByOrNull { overlap(it.location, result.key) }
                    ?.takeIf { overlap(it.location, result.key) > .6 }
            val key =
                prior?.id
                    ?: MessageDigest.getInstance("SHA-256")
                        .digest("${item.id}:${item.hash}:$timestamp:${result.key}".toByteArray())
                        .joinToString("") { "%02x".format(it) }
            used += key
            val filename = "${GalleryRepository.cropPrefix(item.id)}-$key.jpg"
            val file = File(repo.crops, filename)
            // App-private derived file; a crash before transaction leaves only a replaceable
            // orphan.
            file.outputStream().use {
                result.crop.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
            }
            val existing = repo.dao.faceById(key)
            if (
                existing != null &&
                    existing.model != FaceEmbedder.VERSION &&
                    repo.dao.membershipById(key)?.manual == false
            )
                repo.dao.deleteMembership(key)
            val face =
                Face(
                    key,
                    item.id,
                    item.hash,
                    timestamp,
                    result.embedding,
                    FaceEmbedder.VERSION,
                    result.quality,
                    filename,
                    result.usable ||
                        (existing?.userIncluded == true && result.embedding.isNotEmpty()),
                    true,
                    result.reason,
                    prior?.location ?: result.key,
                    existing?.userIncluded ?: false,
                )
            repo.dao.face(face)
            if (face.usable) repo.group(face)
        }
        previous
            .filter { it.id !in used }
            .forEach {
                // A failed rediscovery must not remove a user's confirmed membership from view.
                repo.dao.face(it.copy(active = repo.dao.membershipById(it.id)?.manual == true))
            }
    }

    private fun overlap(a: String, b: String): Double {
        val x = a.split('-').mapNotNull { it.toIntOrNull() }
        val y = b.split('-').mapNotNull { it.toIntOrNull() }
        if (x.size != 4 || y.size != 4) return 0.0
        val w = (minOf(x[0] + x[2], y[0] + y[2]) - maxOf(x[0], y[0])).coerceAtLeast(0)
        val h = (minOf(x[1] + x[3], y[1] + y[3]) - maxOf(x[1], y[1])).coerceAtLeast(0)
        val area = w * h.toDouble()
        return area / (x[2] * x[3] + y[2] * y[3] - area).coerceAtLeast(1.0)
    }

    private suspend fun saveContent(
        repo: GalleryRepository,
        item: Media,
        timestamp: Long,
        content: ContentResult,
        generation: Long,
    ) =
        GalleryRepository.gate.withLock {
            repo.db.withTransaction {
                if (valid(repo, item, generation))
                    saveContentUnlocked(repo, item, timestamp, content)
            }
        }

    private suspend fun saveContentUnlocked(
        repo: GalleryRepository,
        item: Media,
        timestamp: Long,
        content: ContentResult,
    ) {
        if (!item.video) {
            repo.dao.deleteLabels(item.id)
            repo.dao.deleteOcr(item.id)
        }
        for ((label, confidence) in content.labels) repo.dao.label(
            Label(item.id, label, timestamp, confidence)
        )
        repo.dao.ocr(Ocr(item.id, timestamp, content.text))
    }

    private suspend fun hash(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        applicationContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (isStopped) throw CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasChargingPower(): Boolean {
        // BatteryManager.isCharging uses a debounced BatteryStats signal on some devices.
        // Match the battery status used by WorkManager, also requiring a connected source:
        // an unplug transition can briefly retain CHARGING status on the tested HyperOS.
        val signal =
            applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return false
        val status = signal.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return signal.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 &&
            status in
                setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun isScreenshot(item: Media) =
        item.folder.contains("screenshot", true) || item.name.startsWith("screenshot", true)

    companion object {
        private val workerMutex = kotlinx.coroutines.sync.Mutex()
    }
}
