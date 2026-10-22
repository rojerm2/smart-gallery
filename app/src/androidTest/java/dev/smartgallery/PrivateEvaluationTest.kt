package dev.smartgallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import dev.smartgallery.analysis.FaceEmbedder
import dev.smartgallery.analysis.VisionEngine
import dev.smartgallery.data.Face
import dev.smartgallery.data.GalleryDatabase
import dev.smartgallery.data.GalleryRepository
import dev.smartgallery.data.Media
import dev.smartgallery.domain.IdentityPolicy
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional private fixture set; no media, identities, OCR or embeddings enter reports. */
@RunWith(AndroidJUnit4::class)
class PrivateEvaluationTest {
    private data class Sample(
        val identity: String,
        val split: String,
        val hash: String,
        val vector: FloatArray?,
        val usableDetections: Int,
    )

    @Test
    fun evaluateAnnotatedLocalFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val privateRoot =
            InstrumentationRegistry.getArguments().getString("privateEvalRun")?.let {
                check(instrumentation.targetContext.packageName == "dev.smartgallery.validation")
                val run = UUID.fromString(it).toString()
                File(instrumentation.targetContext.noBackupFilesDir, "private-evaluation/$run")
                    .canonicalFile
            }
        fun openFixture(path: String): InputStream {
            require(path.startsWith("evaluation/") && !path.contains("..") && !path.contains('\\'))
            if (privateRoot == null) return assets.open(path)
            val file = File(privateRoot, path).canonicalFile
            require(file.path.startsWith(privateRoot.path + File.separator)) {
                "Evaluation source escaped scoped root"
            }
            require(file.isFile) { "Requested evaluation source is missing" }
            return file.inputStream()
        }
        val text =
            if (privateRoot != null)
                openFixture("evaluation/manifest.json").bufferedReader().use { it.readText() }
            else
                runCatching {
                        openFixture("evaluation/manifest.json").bufferedReader().use {
                            it.readText()
                        }
                    }
                    .getOrNull()
        assumeTrue(
            "No private labeled fixture set supplied; representative evaluation remains pending",
            text != null,
        )
        val items = JSONObject(text!!).getJSONArray("items")
        require(items.length() > 0)
        val samples = mutableListOf<Sample>()
        val geometry = JSONArray()
        val exportGeometry =
            InstrumentationRegistry.getArguments().getString("evaluationGeometry") == "true"
        var expected = 0
        var detected = 0
        var usable = 0
        var exclusions = 0
        var documentExclusions = 0
        var documentLikeMedia = 0
        var ocrExpected = 0
        var ocrFound = 0
        var peakPss = 0L
        val category =
            mutableMapOf<String, IntArray>() // TP, FP, FN, TN on explicitly labeled examples.
        val start = SystemClock.elapsedRealtime()
        VisionEngine(instrumentation.targetContext).use { engine ->
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val filename = item.getString("file")
                require(filename.startsWith("evaluation/") && !filename.contains(".."))
                val bytes = openFixture(filename).use { it.readBytes() }
                val bitmap =
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("Evaluation image decode failed")
                require(bitmap.width.toLong() * bitmap.height <= 16_000_000) {
                    "Resize evaluation sources to the documented bounded limit"
                }
                val result = engine.content(bitmap, item.optBoolean("screenshot", false))
                val document = result.labels.any { it.first in setOf("Document", "ID / document") }
                if (document) documentLikeMedia++
                val faces = engine.faces(bitmap, document)
                if (exportGeometry)
                    geometry.put(
                        JSONObject()
                            .put("index", i)
                            .put(
                                "landmarkQA",
                                if (faces.count { it.usable } > 1) landmarkGeometry(bitmap)
                                else JSONArray(),
                            )
                            .put(
                                "withinImageCosines",
                                JSONArray(
                                    faces.indices.flatMap { a ->
                                        (a + 1 until faces.size).mapNotNull { b ->
                                            if (
                                                faces[a].embedding.isEmpty() ||
                                                    faces[b].embedding.isEmpty()
                                            )
                                                null
                                            else
                                                JSONObject()
                                                    .put("a", a)
                                                    .put("b", b)
                                                    .put(
                                                        "cosine",
                                                        IdentityPolicy.cosine(
                                                            floats(faces[a].embedding),
                                                            floats(faces[b].embedding),
                                                        ),
                                                    )
                                        }
                                    }
                                ),
                            )
                            .put(
                                "faces",
                                JSONArray(
                                    faces.map { f ->
                                        JSONObject()
                                            .put("box", f.key)
                                            .put("usable", f.usable)
                                            .put("reason", f.reason)
                                    }
                                ),
                            )
                    )
                expected += item.optInt("expectedFaces", 0)
                detected += faces.size
                usable += faces.count { it.usable }
                exclusions +=
                    faces.count { !it.usable && it.reason != "Small portrait on a document" }
                documentExclusions +=
                    faces.count { !it.usable && it.reason == "Small portrait on a document" }
                val first = faces.singleOrNull { it.usable }
                val vector =
                    first?.let { f ->
                        val b = ByteBuffer.wrap(f.embedding).order(ByteOrder.LITTLE_ENDIAN)
                        FloatArray(f.embedding.size / 4) { b.float }
                    }
                val hash =
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    }
                samples +=
                    Sample(
                        item.optString("identity", ""),
                        item.optString("split", "heldout"),
                        hash,
                        vector,
                        faces.count { it.usable },
                    )
                val labels = result.labels.map { it.first }.toSet()
                for (label in strings(item.optJSONArray("labelsPresent"))) {
                    val c = category.getOrPut(label) { IntArray(4) }
                    if (label in labels) c[0]++ else c[2]++
                }
                for (label in strings(item.optJSONArray("labelsAbsent"))) {
                    val c = category.getOrPut(label) { IntArray(4) }
                    if (label in labels) c[1]++ else c[3]++
                }
                for (term in strings(item.optJSONArray("ocrContains"))) {
                    ocrExpected++
                    if (result.text.contains(term, true)) ocrFound++
                }
                peakPss = maxOf(peakPss, Debug.getPss())
                faces.forEach { it.crop.recycle() }
                bitmap.recycle()
            }
        }
        val output =
            StringBuilder(
                "\nPrivate aggregate evaluation: items=${items.length()} expectedFaces=$expected detectedFaces=$detected usable=$usable qualityExcluded=$exclusions documentPortraitExcluded=$documentExclusions documentLikeMedia=$documentLikeMedia OCRterms=$ocrFound/$ocrExpected elapsedMs=${SystemClock.elapsedRealtime()-start} sampledPeakProcessPssKb=$peakPss\n"
            )
        if (exportGeometry)
            File(instrumentation.targetContext.cacheDir, "evaluation-geometry.json")
                .writeText(geometry.toString())
        for (split in listOf("calibration", "heldout")) {
            val s = samples.filter { it.split == split && it.identity.isNotBlank() }
            if (s.isEmpty()) {
                output.append("$split not supplied; no metrics.\n")
                continue
            }
            var minimumSame: Float? = null
            var maximumDifferent: Float? = null
            for (i in s.indices) for (j in i + 1 until s.size) {
                val a = s[i]
                val b = s[j]
                if (a.hash == b.hash || a.vector == null || b.vector == null) continue
                val score = IdentityPolicy.cosine(a.vector, b.vector)
                if (a.identity == b.identity) minimumSame = minOf(minimumSame ?: score, score)
                else maximumDifferent = maxOf(maximumDifferent ?: score, score)
            }
            output.append(
                "$split minimumSameCosine=$minimumSame maximumDifferentCosine=$maximumDifferent\n"
            )
            val thresholds =
                if (split == "heldout") listOf(IdentityPolicy.MATCH)
                else
                    listOf(
                            .6f,
                            .65f,
                            .7f,
                            .725f,
                            .75f,
                            .775f,
                            .8f,
                            .825f,
                            .85f,
                            IdentityPolicy.MATCH,
                            .875f,
                            .9f,
                            .925f,
                            .95f,
                        )
                        .distinct()
                        .sorted()
            for (threshold in thresholds) {
                var same = 0
                var different = 0
                var missed = 0
                var falseMerges = 0
                var unresolved = 0
                var comparedSame = 0
                var comparedDifferent = 0
                for (i in s.indices) for (j in i + 1 until s.size) {
                    val a = s[i]
                    val b = s[j]
                    if (a.hash == b.hash) continue
                    val positive = a.identity == b.identity
                    if (positive) same++ else different++
                    if (a.vector == null || b.vector == null) {
                        unresolved++
                        if (positive) missed++
                        continue
                    }
                    if (positive) comparedSame++ else comparedDifferent++
                    val match = IdentityPolicy.cosine(a.vector, b.vector) >= threshold
                    if (positive && !match) missed++
                    if (!positive && match) falseMerges++
                }
                output.append(
                    "$split threshold=$threshold samePairs=$same differentPairs=$different comparedSamePairs=$comparedSame comparedDifferentPairs=$comparedDifferent missedMatches=$missed falseIdentityMerges=$falseMerges unresolvedPairs=$unresolved\n"
                )
            }
            output.append(
                "$split unresolvedMedia=${s.count{it.vector==null}}/${s.size} noUsableFaceMedia=${s.count{it.usableDetections==0}} ambiguousFaceMedia=${s.count{it.usableDetections>1}}; pair metrics do not estimate library review volume or clustering accuracy.\n"
            )
            for ((order, rows) in listOf(s, s.reversed(), s.shuffled(Random(42))).withIndex()) {
                output.append(groupingMetrics(split, order, rows))
            }
        }
        category.forEach { (label, c) ->
            output.append(
                "Category $label TP=${c[0]} FP=${c[1]} FN=${c[2]} TN=${c[3]} precision=${ratio(c[0],c[0]+c[1])} recall=${ratio(c[0],c[0]+c[2])}\n"
            )
        }
        output.append(
            "Model/config=${dev.smartgallery.analysis.FaceEmbedder.VERSION}/${IdentityPolicy.CONFIG}. Count coverage lacks IoU annotations; no universal reliability claim.\n"
        )
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", output.toString()) })
    }

    /**
     * Exercise the production repository on isolated in-memory data, without publishing identities.
     */
    private suspend fun groupingMetrics(split: String, order: Int, samples: List<Sample>): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            val eligible = samples.filter { it.vector != null }
            GalleryRepository.gate.withLock {
                db.withTransaction {
                    for ((index, sample) in eligible.withIndex()) {
                        val id = "evaluation-$index"
                        repo.dao.media(
                            Media(
                                id,
                                "content://evaluation/$index",
                                "fixture",
                                "Evaluation/",
                                "image/jpeg",
                                0,
                                0,
                                0,
                                hash = sample.hash,
                            )
                        )
                        val vector = sample.vector!!
                        val bytes =
                            ByteBuffer.allocate(vector.size * 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .apply { vector.forEach { putFloat(it) } }
                                .array()
                        val face =
                            Face(
                                id,
                                id,
                                sample.hash,
                                0,
                                bytes,
                                FaceEmbedder.VERSION,
                                100.0,
                                "",
                                location = "evaluation",
                            )
                        repo.dao.face(face)
                        repo.group(face)
                    }
                }
            }
            val assigned =
                eligible.mapIndexed { index, sample ->
                    sample.identity to repo.dao.membershipById("evaluation-$index")!!.personId
                }
            val groups = assigned.groupBy { it.second }
            val mergedGroups =
                groups.values.count { group -> group.map { it.first }.distinct().size > 1 }
            val minorityMedia =
                groups.values.sumOf { group ->
                    group.size - (group.groupingBy { it.first }.eachCount().values.maxOrNull() ?: 0)
                }
            val splitIdentities =
                assigned
                    .groupBy { it.first }
                    .values
                    .count { identity -> identity.map { it.second }.distinct().size > 1 }
            val cards = repo.dao.peopleCards().first()
            val visible = cards.filter { it.promoted || it.support >= 2 }.map { it.id }.toSet()
            val reviewMedia = assigned.count { it.second !in visible }
            return "$split actualGrouping order=$order threshold=${IdentityPolicy.MATCH} eligibleMedia=${eligible.size} groups=${groups.size} mixedIdentityGroups=$mergedGroups minorityMediaInMixedGroups=$minorityMedia identitiesSplit=$splitIdentities peopleGroups=${visible.size} reviewMedia=$reviewMedia/${eligible.size}; dataset-scoped, three fixed orders, not a library estimate.\n"
        } finally {
            db.close()
        }
    }

    private fun strings(array: JSONArray?) =
        if (array == null) emptyList() else List(array.length()) { array.getString(it) }

    private fun floats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    private suspend fun landmarkGeometry(bitmap: Bitmap): JSONArray {
        val detector =
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setMinFaceSize(.025f)
                    .build()
            )
        try {
            return JSONArray(
                detector.process(InputImage.fromBitmap(bitmap, 0)).await().map { face ->
                    JSONObject()
                        .put(
                            "landmarks",
                            JSONArray(
                                listOf(
                                        FaceLandmark.LEFT_EYE,
                                        FaceLandmark.RIGHT_EYE,
                                        FaceLandmark.NOSE_BASE,
                                        FaceLandmark.MOUTH_LEFT,
                                        FaceLandmark.MOUTH_RIGHT,
                                    )
                                    .mapNotNull { type ->
                                        face.getLandmark(type)?.position?.let { point ->
                                            JSONObject()
                                                .put("type", type)
                                                .put("x", point.x * 1000 / bitmap.width)
                                                .put("y", point.y * 1000 / bitmap.height)
                                        }
                                    }
                            ),
                        )
                }
            )
        } finally {
            detector.close()
        }
    }

    private fun ratio(n: Int, d: Int) =
        if (d == 0) "unmeasured" else "%.4f".format(n.toDouble() / d)
}
