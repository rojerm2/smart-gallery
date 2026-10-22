package dev.smartgallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
