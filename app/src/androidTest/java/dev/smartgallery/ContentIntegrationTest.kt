package dev.smartgallery

import android.graphics.*
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.VisionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentIntegrationTest {
    @Test
    fun realBundledLabelsRecognizeCc0AnimalAndObjectFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        VisionEngine(instrumentation.targetContext).use { engine ->
            for ((file, expected) in
                listOf(
                    "chelsea.png" to setOf("cat"),
                    "coffee.png" to setOf("coffee", "cup", "coffee cup"),
                )) {
                val bitmap =
                    instrumentation.context.assets.open(file).use { BitmapFactory.decodeStream(it) }
                val result = engine.content(bitmap, false)
                val labels = result.labels.map { it.first.lowercase() }.toSet()
                assertTrue(
                    "Actual supported label expected on CC0 fixture: $expected, got $labels",
                    labels.any { it in expected },
                )
                assertFalse(
                    result.labels.any { it.first in setOf("Document", "Receipt", "ID / document") }
                )
                bitmap.recycle()
            }
        }
    }

    @Test
    fun actualOcrAndVisualLabelsCombineForDocumentsWithScreenshotNegatives() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val receipt =
            sheet(
                listOf(
                    "SMART GALLERY STORE",
                    "RECEIPT",
                    "2026-10-22",
                    "TOTAL PHP 125.00",
                    "CASH PHP 125.00",
                    "TAX PHP 0.00",
                )
            )
        val identity =
            sheet(
                listOf(
                    "REPUBLIC OF EXAMPLE",
                    "SPECIMEN IDENTITY CARD",
                    "DATE OF BIRTH 2000-01-01",
                    "NATIONALITY EXAMPLE",
                    "NOT A REAL DOCUMENT",
                )
            )
        val letter =
            sheet(
                listOf(
                    "A LETTER TO A FRIEND",
                    "The weather has been pleasant.",
                    "We walked to the park today.",
                    "There were birds near the pond.",
                    "We will visit again next week.",
                )
            )
        VisionEngine(instrumentation.targetContext).use { engine ->
            val r = engine.content(receipt, false)
            val i = engine.content(identity, false)
            val l = engine.content(letter, false)
            fun labels(result: dev.smartgallery.analysis.ContentResult) =
                result.labels.map { it.first }.toSet()
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "\nSynthetic content model labels: receipt=${labels(r)} specimen=${labels(i)} letter=${labels(l)}\n",
                    )
                },
            )
            assertTrue("Receipt" in labels(r))
            assertTrue("Document" in labels(r))
            assertTrue("ID / document" in labels(i))
            assertFalse("Receipt" in labels(l))
            assertFalse("ID / document" in labels(l))
            val screenshot = engine.content(receipt, true)
            assertTrue(screenshot.labels.any { it.first == "Screenshot" })
            assertFalse(screenshot.labels.any { it.first in setOf("Receipt", "ID / document") })
        }
        receipt.recycle()
        identity.recycle()
        letter.recycle()
    }

    @Test
    fun dimWarmPaperSupportsRealOcrAndCommonReceiptLayout() = runBlocking {
        val bitmap =
            sheet(
                listOf("EXAMPLE CAFE", "COFFEE 8.00", "TOTAL 9.00", "TAX 1.00", "THANK YOU"),
                Color.rgb(210, 195, 175),
                Color.rgb(95, 90, 85),
            )
        try {
            assertTrue(
                "Dim photographed paper still provides visual evidence",
                VisionEngine.hasPaperRegion(bitmap),
            )
            VisionEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
                val labels = engine.content(bitmap, false).labels.map { it.first }.toSet()
                assertTrue("Document" in labels)
                assertTrue("Receipt" in labels)
                assertFalse("ID / document" in labels)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun tightlyCroppedPaperSupportsIdsButScreenshotMetadataStillExcludesThem() = runBlocking {
        val paper = Color.rgb(210, 200, 185)
        val bitmap =
            sheet(
                listOf(
                    "SPECIMEN IDENTITY CARD",
                    "REPUBLIC OF EXAMPLE",
                    "DATE OF BIRTH 2000-01-01",
                    "NATIONALITY EXAMPLE",
                    "NOT A REAL DOCUMENT",
                ),
                paper,
                paper,
            )
        try {
            assertTrue(
                "A scan need not have an external desk border",
                VisionEngine.hasPaperRegion(bitmap),
            )
            VisionEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
                val document = engine.content(bitmap, false).labels.map { it.first }.toSet()
                assertTrue("Document" in document)
                assertTrue("ID / document" in document)
                val screenshot = engine.content(bitmap, true).labels.map { it.first }.toSet()
                assertTrue("Screenshot" in screenshot)
                assertFalse("ID / document" in screenshot)
                assertFalse("Receipt" in screenshot)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sheet(
        lines: List<String>,
        paperColor: Int = Color.WHITE,
        backgroundColor: Int = Color.rgb(220, 218, 210),
    ) =
        Bitmap.createBitmap(1200, 1100, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(backgroundColor)
                val white = Paint().apply { color = paperColor }
                drawRect(40f, 40f, 1160f, 1060f, white)
                val text =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 52f
                        typeface = Typeface.MONOSPACE
                    }
                lines.forEachIndexed { i, line -> drawText(line, 80f, 150f + i * 130, text) }
            }
        }
}
