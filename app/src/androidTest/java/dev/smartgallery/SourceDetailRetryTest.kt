package dev.smartgallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.FaceResult
import dev.smartgallery.analysis.VisionEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public test-only sources in a private UUID file; never reads or writes the user's gallery. */
@RunWith(AndroidJUnit4::class)
class SourceDetailRetryTest {
    @Test
    fun blurryTargetCannotBorrowTheSharpNeighborInsideItsExpandedRegion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scene = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "neighbors-${UUID.randomUUID()}.png")
        var preview: FaceResult? = null
        var retried: FaceResult? = null
        try {
            VisionEngine(context).use { engine ->
                val source =
                    instrumentation.context.assets.open("astronaut.png").use {
                        BitmapFactory.decodeStream(it)
                    }
                val detected = engine.faces(source, false)
                val bounds = detected.first { it.usable }.key.split('-').map { it.toInt() }
                val face =
                    Bitmap.createBitmap(
                        source,
                        bounds[0] * source.width / 1000,
                        bounds[1] * source.height / 1000,
                        bounds[2] * source.width / 1000,
                        bounds[3] * source.height / 1000,
                    )
                val blurred = Bitmap.createScaledBitmap(face, 6, 6, true)
                val canvas = Canvas(scene)
                canvas.drawColor(Color.DKGRAY)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(blurred, null, Rect(300, 200, 600, 560), paint)
                canvas.drawBitmap(face, null, Rect(610, 280, 740, 440), paint)
                detected.forEach { it.crop.recycle() }
                source.recycle()
                face.recycle()
                blurred.recycle()
                file.outputStream().use { scene.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // The target has no recoverable detail. A sharp real face is inside the
                // expanded retry ROI, outside the target box. All inference remains real.
                preview =
                    FaceResult(
                        "250-250-250-450",
                        ByteArray(0),
                        0.0,
                        Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888),
                        false,
                        "Insufficient face detail, sharpness, pose or landmarks",
                    )
                val neighbors = engine.faces(scene, false)
                try {
                    assertTrue(
                        "The fixture must contain a real usable neighbor",
                        neighbors.any { it.usable && it.key.split('-')[0].toInt() > 490 },
                    )
                } finally {
                    neighbors.forEach { it.crop.recycle() }
                }
                retried = engine.retrySourceDetail(Uri.fromFile(file), preview!!, false)
                assertTrue(
                    "An unrelated neighbor must not replace the excluded target",
                    preview === retried,
                )
                assertFalse(retried!!.usable)
                assertTrue(retried!!.embedding.isEmpty())
                assertFalse(preview!!.crop.isRecycled)
            }
        } finally {
            preview?.crop?.let { if (!it.isRecycled) it.recycle() }
            retried?.crop?.let { if (!it.isRecycled) it.recycle() }
            scene.recycle()
            file.delete()
        }
    }

    private suspend fun source(check: suspend (Uri, ByteArray) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("astronaut.png").use { it.readBytes() }
        val file = File(instrumentation.targetContext.cacheDir, "detail-${UUID.randomUUID()}.png")
        try {
            file.writeBytes(bytes)
            check(Uri.fromFile(file), bytes)
            assertArrayEquals(
                "Retrying must leave original fixture bytes unchanged",
                bytes,
                file.readBytes(),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun tinyNaturalRegionsStayExcludedWithoutUpscalingOrFailingThePhoto() = runBlocking {
        source { uri, _ ->
            VisionEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
                // Includes a region below the SDK input floor, one above it but below usable
                // face size, and a very narrow region. These are deliberately unusable inputs.
                for (key in listOf("0-0-10-10", "0-0-70-70", "0-0-10-1000")) {
                    val crop = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
                    val excluded =
                        FaceResult(
                            key,
                            ByteArray(0),
                            0.0,
                            crop,
                            false,
                            "Insufficient face detail, sharpness, pose or landmarks",
                        )
                    try {
                        val retried = engine.retrySourceDetail(uri, excluded, false)
                        assertSame(excluded, retried)
                        assertFalse(retried.usable)
                        assertTrue(retried.embedding.isEmpty())
                        assertFalse("Caller still owns the excluded preview", crop.isRecycled)
                    } finally {
                        if (!crop.isRecycled) crop.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun enoughNaturalSourceDetailStillProducesARealUsableEmbedding() = runBlocking {
        source { uri, bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            var initial = emptyList<FaceResult>()
            var recovered: FaceResult? = null
            try {
                VisionEngine(InstrumentationRegistry.getInstrumentation().targetContext).use {
                    engine ->
                    initial = engine.faces(bitmap, false)
                    val actual = initial.first { it.usable }
                    val preview =
                        actual.copy(
                            embedding = ByteArray(0),
                            usable = false,
                            reason = "Insufficient face detail, sharpness, pose or landmarks",
                        )
                    recovered = engine.retrySourceDetail(uri, preview, false)
                    assertTrue(
                        "The guard must retain recovery with sufficient real pixels",
                        recovered!!.usable,
                    )
                    assertEquals(512, recovered!!.embedding.size)
                    assertEquals(actual.key, recovered!!.key)
                }
            } finally {
                initial.forEach { if (!it.crop.isRecycled) it.crop.recycle() }
                recovered?.crop?.let { if (!it.isRecycled) it.recycle() }
                bitmap.recycle()
            }
        }
    }
}
