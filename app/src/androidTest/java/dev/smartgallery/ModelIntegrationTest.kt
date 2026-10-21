package dev.smartgallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.smartgallery.analysis.FaceEmbedder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun bundledModelLoadsAndProducesDeterministicNormalizedEmbeddings() {
        val face = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        Canvas(face).apply {
            drawColor(Color.rgb(180, 140, 100))
            val paint = Paint().apply { color = Color.DKGRAY }
            drawCircle(36f, 45f, 8f, paint)
            drawCircle(76f, 45f, 8f, paint)
            drawRect(38f, 78f, 74f, 83f, paint)
        }
        FaceEmbedder(instrumentation.targetContext).use {
            val a = it.embed(face)
            val b = it.embed(face)
            assertEquals(128, a.size)
            assertEquals(1.0, a.sumOf { v -> (v * v).toDouble() }, 1e-5)
            assertTrue(a.indices.all { i -> abs(a[i] - b[i]) < 1e-5 })
        }
        face.recycle()
    }

    @Test
    fun bundledOcrReadsSyntheticReceiptWithoutDownloads() {
        val bitmap = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 56f
                }
            listOf(
                    "SMART GALLERY STORE",
                    "RECEIPT",
                    "2026-10-22",
                    "TOTAL PHP 125.00",
                    "CASH 125.00",
                )
                .forEachIndexed { i, s -> drawText(s, 50f, 100f + i * 110f, paint) }
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use {
            val text =
                Tasks.await(it.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS).text
            assertTrue(text.contains("125"))
            assertTrue(text.contains("RECEIPT", true))
        }
        bitmap.recycle()
    }

    @Test
    fun bundledDetectorAndLabelsRunOnPublicDomainAstronaut() {
        val bitmap =
            instrumentation.context.assets.open("astronaut.png").use {
                BitmapFactory.decodeStream(it)
            }
        val input = InputImage.fromBitmap(bitmap, 0)
        FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
            )
            .use {
                val faces = Tasks.await(it.process(input), 60, TimeUnit.SECONDS)
                assertTrue("Public domain astronaut face must be detected", faces.isNotEmpty())
            }
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS).use {
            val labels = Tasks.await(it.process(input), 60, TimeUnit.SECONDS)
            assertTrue("Actual image model must emit labels", labels.isNotEmpty())
        }
        bitmap.recycle()
    }
}
