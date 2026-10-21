package dev.smartgallery

import android.graphics.*
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.FaceResult
import dev.smartgallery.analysis.VisionEngine
import dev.smartgallery.domain.IdentityPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealVisionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun vector(face: FaceResult) =
        ByteBuffer.wrap(face.embedding).order(ByteOrder.LITTLE_ENDIAN).let { b ->
            FloatArray(face.embedding.size / 4) { b.float }
        }

    @Test
    fun alignedRealFacesHaveFiniteEmbeddingsAndSeparateTheTwoFixtureIdentities() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        val images =
            listOf("astronaut.png", "grace_hopper.jpg").map { name ->
                instrumentation.context.assets.open(name).use { BitmapFactory.decodeStream(it) }
            }
        VisionEngine(instrumentation.targetContext).use { engine ->
            val results = images.map { engine.faces(it, false) }
            assertTrue(
                "Both real fixtures must produce usable aligned recognition",
                results.all { fs -> fs.any { it.usable && it.embedding.size == 512 } },
            )
            val a = results[0].first { it.usable }
            val b = results[1].first { it.usable }
            val negative = IdentityPolicy.cosine(vector(a), vector(b))
            assertTrue(
                "Different fixture identities must not auto-match",
                negative < IdentityPolicy.MATCH,
            )
            val altered =
                Bitmap.createBitmap(images[0].width, images[0].height, Bitmap.Config.ARGB_8888)
            Canvas(altered)
                .drawBitmap(
                    images[0],
                    0f,
                    0f,
                    Paint().apply {
                        colorFilter =
                            ColorMatrixColorFilter(
                                ColorMatrix(
                                    floatArrayOf(
                                        .9f,
                                        0f,
                                        0f,
                                        0f,
                                        8f,
                                        0f,
                                        .9f,
                                        0f,
                                        0f,
                                        8f,
                                        0f,
                                        0f,
                                        .9f,
                                        0f,
                                        8f,
                                        0f,
                                        0f,
                                        0f,
                                        1f,
                                        0f,
                                    )
                                )
                            )
                    },
                )
            val variants = engine.faces(altered, false)
            val positive =
                variants.filter { it.usable }.maxOf { IdentityPolicy.cosine(vector(a), vector(it)) }
            assertTrue(
                "Mild exposure variation should remain recognizable",
                positive >= IdentityPolicy.MATCH,
            )
            val tiny = Bitmap.createScaledBitmap(images[0], 128, 128, true)
            val tinyFaces = engine.faces(tiny, false)
            assertTrue(
                "Tiny source faces cannot create usable embeddings",
                tinyFaces.none { it.usable },
            )
            val status =
                Bundle().apply {
                    putString(
                        "stream",
                        "\nLicensed fixture micro-evaluation: detected=${results.sumOf{it.size}} usable=${results.sumOf{fs->fs.count{it.usable}}} negativeCosine=$negative augmentedPositiveCosine=$positive elapsedMs=${SystemClock.elapsedRealtime()-start} processPssKb=${Debug.getPss()}\nOnly two identities and one augmented positive; no held-out accuracy estimate.\n",
                    )
                }
            instrumentation.sendStatus(2, status)
            results.flatten().forEach { it.crop.recycle() }
            variants.forEach { it.crop.recycle() }
            tinyFaces.forEach { it.crop.recycle() }
            altered.recycle()
            tiny.recycle()
        }
        images.forEach { it.recycle() }
    }
}
