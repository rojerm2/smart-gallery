package dev.smartgallery.analysis

import android.content.Context
import android.graphics.*
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.smartgallery.domain.DetectionPolicy
import dev.smartgallery.domain.DocumentPolicy
import dev.smartgallery.domain.QualityPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlinx.coroutines.tasks.await

data class FaceResult(
    val key: String,
    val embedding: ByteArray,
    val quality: Double,
    val crop: Bitmap,
    val usable: Boolean,
    val reason: String,
)

data class ContentResult(val labels: List<Pair<String, Float>>, val text: String)

class VisionEngine(private val context: Context) : AutoCloseable {
    private val detector =
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.025f)
                .build()
        )
    private val embedder = FaceEmbedder(context)
    private val labeler =
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.65f).build())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun faces(bitmap: Bitmap, document: Boolean): List<FaceResult> {
        val found = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
        val accepted = mutableListOf<Pair<DetectionPolicy.Region, List<DetectionPolicy.Point>>>()
        return found.take(80).mapNotNull { face ->
            val box = Rect(face.boundingBox)
            if (!box.intersect(0, 0, bitmap.width, bitmap.height)) return@mapNotNull null
            if (box.width() <= 0 || box.height() <= 0) return@mapNotNull null
            val landmarks =
                listOf(
                        FaceLandmark.LEFT_EYE,
                        FaceLandmark.RIGHT_EYE,
                        FaceLandmark.NOSE_BASE,
                        FaceLandmark.MOUTH_LEFT,
                        FaceLandmark.MOUTH_RIGHT,
                    )
                    .mapNotNull { face.getLandmark(it)?.position }
            // ML Kit left/right are anatomical; the canonical reference is in image x order.
            val ordered =
                if (landmarks.size == 5) {
                    val eyes = landmarks.take(2).sortedBy { it.x }
                    val mouth = landmarks.takeLast(2).sortedBy { it.x }
                    eyes + landmarks[2] + mouth
                } else emptyList()
            val region =
                DetectionPolicy.Region(
                    box.left.toFloat(),
                    box.top.toFloat(),
                    box.right.toFloat(),
                    box.bottom.toFloat(),
                )
            val points = ordered.map { DetectionPolicy.Point(it.x, it.y) }
            if (
                accepted.any { (other, landmarks) ->
                    DetectionPolicy.sameDetection(region, points, other, landmarks)
                }
            )
                return@mapNotNull null
            accepted += region to points
            val sharp = sharpness(bitmap, box)
            val quality =
                QualityPolicy.usable(
                    box.width(),
                    box.height(),
                    sharp,
                    face.headEulerAngleY,
                    face.headEulerAngleX,
                    ordered.size,
                )
            val smallDocumentPortrait = document && box.width().toFloat() / bitmap.width < 0.24f
            val aligned =
                if (ordered.size == 5) align(bitmap, ordered)
                else
                    Bitmap.createScaledBitmap(
                        Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height()),
                        112,
                        112,
                        true,
                    )
            val vector = if (quality) embedder.embed(aligned) else FloatArray(0)
            val bytes =
                ByteBuffer.allocate(vector.size * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply { vector.forEach { putFloat(it) } }
                    .array()
            val key =
                listOf(
                        box.left * 1000 / bitmap.width,
                        box.top * 1000 / bitmap.height,
                        box.width() * 1000 / bitmap.width,
                        box.height() * 1000 / bitmap.height,
                    )
                    .joinToString("-")
            FaceResult(
                key,
                bytes,
                sharp,
                aligned,
                quality && !smallDocumentPortrait,
                when {
                    !quality -> "Insufficient face detail, sharpness, pose or landmarks"
                    smallDocumentPortrait -> "Small portrait on a document"
                    else -> ""
                },
            )
        }
    }

    suspend fun content(bitmap: Bitmap, screenshot: Boolean): ContentResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val labels = labeler.process(image).await().map { it.text to it.confidence }
        val ocr = recognizer.process(image).await()
        val categories =
            DocumentPolicy.classify(
                labels.map { it.first }.toSet(),
                ocr.text,
                screenshot,
                ocr.textBlocks.sumOf { it.lines.size },
                paperRegion = hasPaperRegion(bitmap),
            )
        return ContentResult(
            (labels + categories.map { it to 1f }).distinctBy { it.first },
            ocr.text.take(60_000),
        )
    }

    /**
     * Retry a small detection using actual source pixels in a bounded region, never upscaled
     * detail.
     */
    suspend fun retrySourceDetail(uri: Uri, result: FaceResult, document: Boolean): FaceResult {
        if (result.usable || result.reason == "Small portrait on a document") return result
        val bounds = result.key.split('-').mapNotNull { it.toIntOrNull() }
        if (bounds.size != 4) return result
        val region =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) {
                decoder,
                info,
                _ ->
                val w = info.size.width
                val h = info.size.height
                val x = bounds[0] * w / 1000
                val y = bounds[1] * h / 1000
                val fw = max(1, bounds[2] * w / 1000)
                val fh = max(1, bounds[3] * h / 1000)
                val rect =
                    Rect(
                        (x - fw / 2).coerceAtLeast(0),
                        (y - fh / 2).coerceAtLeast(0),
                        (x + fw * 3 / 2).coerceAtMost(w),
                        (y + fh * 3 / 2).coerceAtMost(h),
                    )
                val scale = min(1.0, 640.0 / max(rect.width(), rect.height()))
                decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
                decoder.setCrop(
                    Rect(
                        (rect.left * scale).toInt(),
                        (rect.top * scale).toInt(),
                        (rect.right * scale).toInt(),
                        (rect.bottom * scale).toInt(),
                    )
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        try {
            // No eligible face can fit this crop at its actual decoded resolution. Keep the
            // original exclusion rather than upscaling or sending an undersized ROI to ML Kit.
            if (
                region.width < QualityPolicy.MIN_FACE_EDGE ||
                    region.height < QualityPolicy.MIN_FACE_EDGE
            )
                return result
            val candidates = faces(region, false)
            val best = candidates.maxByOrNull { it.quality }
            candidates.filter { it !== best }.forEach { it.crop.recycle() }
            if (best != null && best.usable) {
                result.crop.recycle()
                val excluded = document && bounds[2] < 240
                return best.copy(
                    key = result.key,
                    usable = !excluded,
                    reason = if (excluded) "Small portrait on a document" else "",
                )
            }
            best?.crop?.recycle()
            return result
        } finally {
            region.recycle()
        }
    }

    override fun close() {
        detector.close()
        embedder.close()
        labeler.close()
        recognizer.close()
    }

    companion object {
        /**
         * Visual evidence: a large, mostly filled neutral paper region. Allow normal shadows, warm
         * lighting and tightly cropped scans; photographed paper is rarely pure white.
         */
        fun hasPaperRegion(bitmap: Bitmap): Boolean {
            val n = 64
            val pale = BooleanArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                val p = bitmap.getPixel(x * bitmap.width / n, y * bitmap.height / n)
                val r = p shr 16 and 255
                val g = p shr 8 and 255
                val b = p and 255
                pale[y * n + x] = minOf(r, g, b) > 150 && maxOf(r, g, b) - minOf(r, g, b) < 65
            }
            val seen = BooleanArray(n * n)
            val queue = IntArray(n * n)
            for (start in pale.indices) {
                if (!pale[start] || seen[start]) continue
                var head = 0
                var tail = 1
                queue[0] = start
                seen[start] = true
                var left = n
                var right = 0
                var top = n
                var bottom = 0
                while (head < tail) {
                    val p = queue[head++]
                    val x = p % n
                    val y = p / n
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                    for (next in
                        intArrayOf(
                            if (x > 0) p - 1 else -1,
                            if (x < n - 1) p + 1 else -1,
                            if (y > 0) p - n else -1,
                            if (y < n - 1) p + n else -1,
                        )) if (next >= 0 && pale[next] && !seen[next]) {
                        seen[next] = true
                        queue[tail++] = next
                    }
                }
                val w = right - left + 1
                val h = bottom - top + 1
                val insetEdges =
                    listOf(left > 0, right < n - 1, top > 0, bottom < n - 1).count { it }
                if (
                    tail > n * n * .35 &&
                        tail < w * h * .99 &&
                        tail > w * h * .65 &&
                        w > n * .4 &&
                        h > n * .4 &&
                        (insetEdges >= 2 || tail > n * n * .65)
                )
                    return true
            }
            return false
        }

        /** Similarity transform fitted over all five points; no perspective warp of the face. */
        fun align(bitmap: Bitmap, points: List<PointF>): Bitmap {
            require(points.size == 5)
            val target =
                listOf(
                    PointF(38.2946f, 51.6963f),
                    PointF(73.5318f, 51.5014f),
                    PointF(56.0252f, 71.7366f),
                    PointF(41.5493f, 92.3655f),
                    PointF(70.7299f, 92.2041f),
                )
            val sx = points.map { it.x }.average()
            val sy = points.map { it.y }.average()
            val tx = target.map { it.x }.average()
            val ty = target.map { it.y }.average()
            var denom = 0.0
            var real = 0.0
            var imag = 0.0
            for (i in points.indices) {
                val x = points[i].x - sx
                val y = points[i].y - sy
                val u = target[i].x - tx
                val v = target[i].y - ty
                denom += x * x + y * y
                real += x * u + y * v
                imag += x * v - y * u
            }
            require(denom > 1e-5)
            val a = real / denom
            val b = imag / denom
            val matrix =
                Matrix().apply {
                    setValues(
                        floatArrayOf(
                            a.toFloat(),
                            -b.toFloat(),
                            (tx - a * sx + b * sy).toFloat(),
                            b.toFloat(),
                            a.toFloat(),
                            (ty - b * sx - a * sy).toFloat(),
                            0f,
                            0f,
                            1f,
                        )
                    )
                }
            return Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).also {
                Canvas(it)
                    .drawBitmap(
                        bitmap,
                        matrix,
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
            }
        }

        fun sharpness(bitmap: Bitmap, box: Rect): Double {
            val w = min(box.width(), 180)
            val h = min(box.height(), 180)
            if (w < 3 || h < 3) return 0.0
            // Sample existing pixels; no enlargement is treated as recovered detail.
            fun gray(x: Int, y: Int): Double {
                val p =
                    bitmap.getPixel(box.left + x * box.width() / w, box.top + y * box.height() / h)
                return ((p shr 16 and 255) * 0.299 +
                    (p shr 8 and 255) * 0.587 +
                    (p and 255) * 0.114)
            }
            var sum = 0.0
            var sq = 0.0
            var n = 0
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val l =
                    gray(x - 1, y) + gray(x + 1, y) + gray(x, y - 1) + gray(x, y + 1) -
                        4 * gray(x, y)
                sum += l
                sq += l * l
                n++
            }
            return sq / n - (sum / n).pow(2)
        }

        fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap {
            // ImageDecoder applies EXIF orientation and uses a software allocator for CPU
            // inference.
            if (android.os.Build.VERSION.SDK_INT >= 28)
                return ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri)
                ) { decoder, info, _ ->
                    val scale =
                        min(1.0, maxEdge.toDouble() / max(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).toInt()),
                        max(1, (info.size.height * scale).toInt()),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            error("Image decoding requires Android 9 or newer")
        }
    }
}
