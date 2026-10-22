package dev.smartgallery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.common.MlKitException
import dev.smartgallery.analysis.FaceResult
import dev.smartgallery.analysis.VisionEngine
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, on-phone reproduction. No database writes, private identifiers or media exports. */
@RunWith(AndroidJUnit4::class)
class FailedPhotoDiagnosticsTest {
    private data class Source(
        val id: String,
        val uri: Uri,
        val screenshot: Boolean,
        val hash: String,
    )

    @Test
    fun reproduceFailedPhotoStagesWithAggregateExceptionSitesOnly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Failed-photo diagnosis requires explicit host opt-in",
            args.getString("failedPhotoDiagnostics") == "true",
        )
        val context = instrumentation.targetContext
        check(context.packageName == "dev.smartgallery")
        val mode = args.getString("failureCohortMode")
        val cohort =
            args.getString("failureCohortRun")?.let {
                val root = File(context.noBackupFilesDir, "failure-diagnosis").canonicalFile
                File(root, UUID.fromString(it).toString() + ".json").canonicalFile.also { file ->
                    require(file.parentFile == root)
                }
            }
        require(mode == null || mode in setOf("record", "replay", "status", "cleanup"))
        require(mode == null || cohort != null)
        if (mode == "cleanup") {
            check(!cohort!!.exists() || cohort.delete())
            if (cohort.parentFile!!.list()?.isEmpty() == true) cohort.parentFile!!.delete()
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "\nCleaned private failure-diagnosis cohort.\n")
                },
            )
            return@runBlocking
        }
        val limit = args.getString("failureSampleLimit")?.toIntOrNull()?.coerceIn(1, 8) ?: 6
        val sources = mutableListOf<Source>()
        if (mode == "replay" || mode == "status") sources += readCohort(cohort!!)
        else
            SQLiteDatabase.openDatabase(
                    context.getDatabasePath("gallery.db").path,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
                .use { db ->
                    db.rawQuery(
                            "SELECT m.id,m.uri,(lower(m.folder) LIKE '%screenshot%' OR lower(m.name) LIKE 'screenshot%'),m.hash " +
                                "FROM media m JOIN stages s ON s.mediaId=m.id WHERE m.accessible=1 AND m.excluded=0 " +
                                "AND s.kind='1faces' AND s.state='failed' ORDER BY m.id LIMIT ?",
                            arrayOf(limit.toString()),
                        )
                        .use { cursor ->
                            while (cursor.moveToNext()) {
                                val uri = Uri.parse(cursor.getString(1))
                                check(uri.scheme == "content" && uri.authority == "media")
                                sources +=
                                    Source(
                                        cursor.getString(0),
                                        uri,
                                        cursor.getInt(2) != 0,
                                        cursor.getString(3),
                                    )
                            }
                        }
                }
        if (mode == "record") {
            require(sources.isNotEmpty())
            check(!cohort!!.exists())
            cohort.parentFile!!.mkdirs()
            cohort.writeText(
                JSONArray(
                        sources.map {
                            JSONObject()
                                .put("id", it.id)
                                .put("uri", it.uri.toString())
                                .put("screenshot", it.screenshot)
                                .put("hash", it.hash)
                        }
                    )
                    .toString()
            )
        }
        val errors = sortedMapOf<String, Int>()
        var completed = 0
        var tinySources = 0
        var detailRetries = 0
        var minimumInputSizeErrors = 0
        if (mode != "status")
            withTimeout(120_000) {
                VisionEngine(context).use { engine ->
                    for (source in sources) {
                        var phase = "decode1280"
                        var bitmap: Bitmap? = null
                        var faces = mutableListOf<FaceResult>()
                        try {
                            bitmap = VisionEngine.decode(context, source.uri, 1280)
                            if (bitmap.width < 32 || bitmap.height < 32) tinySources++
                            phase = "content1280"
                            val document =
                                engine.content(bitmap, source.screenshot).labels.any {
                                    it.first in setOf("Document", "ID / document")
                                }
                            phase = "faces1280"
                            faces = engine.faces(bitmap, document).toMutableList()
                            if (faces.isEmpty() || faces.any { !it.usable }) {
                                faces.forEach { if (!it.crop.isRecycled) it.crop.recycle() }
                                faces.clear()
                                bitmap.recycle()
                                phase = "decode2560"
                                bitmap = VisionEngine.decode(context, source.uri, 2560)
                                phase = "faces2560"
                                faces = engine.faces(bitmap, document).toMutableList()
                            }
                            phase = "sourceDetail"
                            for (index in faces.indices) {
                                if (
                                    !faces[index].usable &&
                                        faces[index].reason != "Small portrait on a document"
                                )
                                    detailRetries++
                                faces[index] =
                                    engine.retrySourceDetail(source.uri, faces[index], document)
                            }
                            completed++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val causes =
                                generateSequence<Throwable>(e) { it.cause }.take(5).toList()
                            // Match one fixed SDK validation signature locally, without exporting
                            // its message (which is otherwise deliberately excluded from reports).
                            if (
                                causes.any {
                                    it.message?.contains(
                                        "width and height should be at least 32",
                                        true,
                                    ) == true
                                }
                            )
                                minimumInputSizeErrors++
                            val code =
                                causes.filterIsInstance<MlKitException>().firstOrNull()?.errorCode
                                    ?: -1
                            // Fixed code locations/classes only; never exception messages or raw
                            // stacks.
                            val site =
                                e.stackTrace
                                    .firstOrNull {
                                        it.className.startsWith("dev.smartgallery.analysis.")
                                    }
                                    ?.let {
                                        "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                                    } ?: "sdk"
                            val bin =
                                "$phase/${e.javaClass.simpleName}/code=$code/root=${causes.last().javaClass.simpleName}/$site"
                            errors[bin] = (errors[bin] ?: 0) + 1
                        } finally {
                            faces.forEach { if (!it.crop.isRecycled) it.crop.recycle() }
                            bitmap?.let { if (!it.isRecycled) it.recycle() }
                        }
                    }
                }
            }
        val cohortReport = if (cohort != null) cohortStatus(context, sources) else "not-recorded"
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "\nFailed-photo aggregate diagnosis: selected=${sources.size} reproduced=${errors.values.sum()} completed=$completed tinySourceImages=$tinySources detailRetries=$detailRetries minimumInputSizeErrors=$minimumInputSizeErrors errorBins=$errors\nCohort mode=$mode $cohortReport\nNo media, filenames, URIs, OCR, embeddings or exception messages exported; the diagnostic makes no personal database/preferences writes.\n",
                )
            },
        )
    }

    @Test
    fun malformedPrivateCohortNeverExposesItsContentInAnException() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "bad-cohort-${UUID.randomUUID()}.json")
        val canary = "synthetic-private-content-canary"
        try {
            file.writeText("[{\"uri\":\"$canary")
            try {
                readCohort(file)
                org.junit.Assert.fail("Malformed cohort must be rejected")
            } catch (e: IllegalStateException) {
                org.junit.Assert.assertFalse(e.toString().contains(canary))
                org.junit.Assert.assertNull(e.cause)
                org.junit.Assert.assertTrue(e.suppressed.isEmpty())
            }
        } finally {
            file.delete()
        }
    }

    private fun readCohort(file: File): List<Source> =
        try {
            val rows = JSONArray(file.readText())
            require(rows.length() in 1..8)
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val uri = Uri.parse(row.getString("uri"))
                check(uri.scheme == "content" && uri.authority == "media")
                Source(
                    row.getString("id"),
                    uri,
                    row.getBoolean("screenshot"),
                    row.getString("hash"),
                )
            }
        } catch (_: Exception) {
            // JSON parser messages can include their input. Do not attach a private cause.
            throw IllegalStateException("Private diagnostic cohort could not be read")
        }

    private fun cohortStatus(context: Context, sources: List<Source>): String {
        var analyzed = 0
        var retainedHashes = 0
        var originalDigests = 0
        var integrityReadsFailed = 0
        SQLiteDatabase.openDatabase(
                context.getDatabasePath("gallery.db").path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            .use { db ->
                for (source in sources) {
                    db.rawQuery(
                            "SELECT analyzed,hash,accessible,excluded FROM media WHERE id=?",
                            arrayOf(source.id),
                        )
                        .use { cursor ->
                            if (cursor.moveToFirst()) {
                                if (
                                    cursor.getInt(0) != 0 &&
                                        cursor.getInt(2) != 0 &&
                                        cursor.getInt(3) == 0
                                )
                                    analyzed++
                                if (cursor.getString(1) == source.hash) retainedHashes++
                            }
                        }
                }
            }
        for (source in sources) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(source.uri)!!.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                if (digest.digest().joinToString("") { "%02x".format(it) } == source.hash)
                    originalDigests++
            } catch (_: Exception) {
                integrityReadsFailed++
            }
        }
        return "selected=${sources.size} analyzed=$analyzed retainedHashes=$retainedHashes originalDigestsUnchanged=$originalDigests integrityReadsFailed=$integrityReadsFailed work=${workCounters(context)}"
    }

    private fun workCounters(context: Context): String {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        if (!file.isFile) return "unavailable"
        return try {
            val rows = mutableListOf<String>()
            SQLiteDatabase.openDatabase(
                    file.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
                .use { db ->
                    db.rawQuery(
                            "SELECT n.name,s.state,s.run_attempt_count,count(*) FROM WorkSpec s JOIN WorkName n ON n.work_spec_id=s.id " +
                                "WHERE n.name IN ('gallery-index-now','gallery-index-periodic') AND s.state IN (0,1,4) GROUP BY n.name,s.state,s.run_attempt_count",
                            null,
                        )
                        .use { cursor ->
                            while (cursor.moveToNext()) rows +=
                                "${cursor.getString(0)}/${cursor.getInt(1)}/${cursor.getInt(2)}/${cursor.getInt(3)}"
                        }
                }
            // Fixed job names/state/attempt/count only; no work IDs, input data or timestamps.
            rows.joinToString(";")
        } catch (_: Exception) {
            "unavailable"
        }
    }
}
