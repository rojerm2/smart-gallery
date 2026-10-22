package dev.smartgallery

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.VideoFrameReader
import dev.smartgallery.analysis.VisionEngine
import dev.smartgallery.domain.VideoPlan
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoIntegrationTest {
    @Test
    fun realSampledVideoFindsAppearanceBeyondOpeningFrame() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "licensed-video-test.mp4")
        instrumentation.context.assets.open("later-appearance.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val retriever = VideoFrameReader(context, Uri.fromFile(file))
        try {
            val duration = retriever.durationMs
            val times = VideoPlan.timestamps(duration, false)
            val matches = mutableListOf<Long>()
            VisionEngine(context).use { engine ->
                for (time in times) {
                    val decoded = retriever.frameAt(time)
                    val frame = decoded.bitmap
                    val faces = engine.faces(frame, false)
                    if (time == 0L)
                        File(context.cacheDir, "video-first-device.png").outputStream().use {
                            frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString(
                                "stream",
                                "\nFixture frame requestedMs=$time dimensions=${frame.width}x${frame.height} pixel=${frame.getPixel(0,0)} faces=${faces.size} usable=${faces.count{it.usable}}\n",
                            )
                        },
                    )
                    if (faces.any { it.usable }) matches += decoded.timestampMs
                    faces.forEach { it.crop.recycle() }
                    frame.recycle()
                }
            }
            assertFalse(matches.contains(0L))
            assertTrue(
                "Appearance after the opening frame must be found",
                matches.any { it in 4_000..10_000 },
            )
            assertTrue(times.size <= 120)
        } finally {
            retriever.close()
            file.delete()
        }
    }

    @Test
    fun corruptVideoRaisesRecoverableExceptionAndDecoderCanBeReleased() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file =
            File(context.cacheDir, "corrupt-test.mp4").apply {
                writeText("synthetic invalid video")
            }
        var retriever: VideoFrameReader? = null
        var rejected = false
        try {
            try {
                retriever = VideoFrameReader(context, Uri.fromFile(file))
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            retriever?.close()
            file.delete()
        }
    }
}
