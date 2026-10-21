package dev.smartgallery

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.VisionEngine
import dev.smartgallery.domain.IdentityPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
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
    )

    @Test
    fun evaluateAnnotatedLocalFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val text =
            runCatching {
                    assets.open("evaluation/manifest.json").bufferedReader().use { it.readText() }
                }
                .getOrNull()
        assumeTrue(
            "No private labeled fixture set supplied; representative evaluation remains pending",
            text != null,
        )
        val items = JSONObject(text!!).getJSONArray("items")
        require(items.length() > 0)
        val samples = mutableListOf<Sample>()
        var expected = 0
        var detected = 0
        var usable = 0
        var exclusions = 0
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
                val bytes = assets.open(filename).use { it.readBytes() }
                val bitmap =
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("Evaluation image decode failed")
                require(bitmap.width.toLong() * bitmap.height <= 16_000_000) {
                    "Resize evaluation sources to the documented bounded limit"
                }
                val faces = engine.faces(bitmap, false)
                expected += item.optInt("expectedFaces", 0)
                detected += faces.size
                usable += faces.count { it.usable }
                exclusions += faces.count { !it.usable }
                val first = faces.firstOrNull { it.usable }
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
                    )
                val result = engine.content(bitmap, item.optBoolean("screenshot", false))
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
                "\nPrivate aggregate evaluation: items=${items.length()} expectedFaces=$expected detectedFaces=$detected usable=$usable qualityExcluded=$exclusions OCRterms=$ocrFound/$ocrExpected elapsedMs=${SystemClock.elapsedRealtime()-start} sampledPeakProcessPssKb=$peakPss\n"
            )
        for (split in listOf("calibration", "heldout")) {
            val s = samples.filter { it.split == split && it.identity.isNotBlank() }
            for (threshold in listOf(.7f, .8f, IdentityPolicy.MATCH, .9f, .95f).distinct()) {
                var same = 0
                var different = 0
                var missed = 0
                var falseMerges = 0
                var unresolved = 0
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
                    val match = IdentityPolicy.cosine(a.vector, b.vector) >= threshold
                    if (positive && !match) missed++
                    if (!positive && match) falseMerges++
                }
                output.append(
                    "$split threshold=$threshold samePairs=$same differentPairs=$different missedMatches=$missed falseIdentityMerges=$falseMerges unresolvedPairs=$unresolved\n"
                )
            }
            output.append(
                "$split unresolvedMedia=${s.count{it.vector==null}}/${s.size}; pair metrics do not estimate library review volume or clustering accuracy.\n"
            )
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

    private fun strings(array: JSONArray?) =
        if (array == null) emptyList() else List(array.length()) { array.getString(it) }

    private fun ratio(n: Int, d: Int) =
        if (d == 0) "unmeasured" else "%.4f".format(n.toDouble() / d)
}
