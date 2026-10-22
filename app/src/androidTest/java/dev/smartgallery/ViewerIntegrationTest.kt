package dev.smartgallery

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.smartgallery.data.GalleryRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerIntegrationTest {
    @Test
    fun deletingATestOwnedPhotoClosesTheViewerAfterReconciliation() = runBlocking {
        withFixture(false) { name, _, _, removeFixture ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            launchFresh().use {
                assertTrue(device.wait(Until.hasObject(By.descContains(name)), 15_000))
                device.findObject(By.descContains(name)).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Photo: $name")), 10_000))
                removeFixture()
                GalleryRepository(context).reconcile()
                assertTrue(device.wait(Until.hasObject(By.text("Smart Gallery")), 10_000))
                assertFalse(device.hasObject(By.desc("Close viewer")))
            }
        }
    }

    @Test
    fun accessiblePhotoLoadsZoomsAndOpensDetailsWithoutChangingOriginal() = runBlocking {
        withFixture(false) { name, uri, bytes, _ ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(instrumentation)
            launchFresh().use {
                assertTrue(device.wait(Until.hasObject(By.text("Smart Gallery")), 10_000))
                val found = device.wait(Until.hasObject(By.descContains(name)), 15_000)
                if (!found) {
                    device.takeScreenshot(
                        File(instrumentation.targetContext.cacheDir, "viewer-missing-fixture.png")
                    )
                    device.dumpWindowHierarchy(
                        File(instrumentation.targetContext.cacheDir, "viewer-missing-fixture.xml")
                    )
                }
                assertTrue("Photo fixture must be visible in Photos", found)
                device.findObject(By.descContains(name)).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Details and corrections")), 10_000))
                assertTrue(device.wait(Until.gone(By.desc("Loading photo")), 10_000))
                assertFalse(
                    device.hasObject(
                        By.text("This item is no longer accessible or could not be opened.")
                    )
                )
                device.findObject(By.desc("Photo: $name")).pinchOpen(.4f)
                device.takeScreenshot(
                    File(instrumentation.targetContext.cacheDir, "viewer-zoom-test.png")
                )
                assertTrue(device.hasObject(By.desc("Share")))
                device.findObject(By.desc("Details and corrections")).click()
                assertTrue(
                    device.wait(Until.hasObject(By.text("Pictures/SmartGalleryTests/")), 10_000)
                )
                device.findObject(By.desc("Close viewer")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Smart Gallery")), 10_000))
            }
            assertArrayEquals(
                bytes,
                instrumentation.targetContext.contentResolver.openInputStream(uri)!!.use {
                    it.readBytes()
                },
            )
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun accessibleVideoOpensAndPlaysWithNativeControls() = runBlocking {
        withFixture(true) { name, _, _, _ ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            launchFresh().use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("Smart Gallery")), 10_000))
                val found = device.wait(Until.hasObject(By.descContains(name)), 15_000)
                if (!found) {
                    device.takeScreenshot(
                        File(
                            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                            "viewer-missing-fixture.png",
                        )
                    )
                    device.dumpWindowHierarchy(
                        File(
                            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                            "viewer-missing-fixture.xml",
                        )
                    )
                }
                assertTrue("Video fixture must be visible in Photos", found)
                device.waitForIdle()
                device.findObject(By.descContains(name)).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Play")), 10_000))
                device.waitForIdle()
                device.findObject(By.desc("Play")).click()
                fun playerView(view: View): PlayerView? {
                    if (view is PlayerView) return view
                    if (view is ViewGroup)
                        for (index in 0 until view.childCount) {
                            playerView(view.getChildAt(index))?.let {
                                return it
                            }
                        }
                    return null
                }
                // Native controls auto-hide. Observe actual playback after the real Play tap.
                val advanced =
                    withTimeoutOrNull(10_000) {
                        while (true) {
                            var playing = false
                            scenario.onActivity { activity ->
                                val player =
                                    WindowInspector.getGlobalWindowViews()
                                        .asSequence()
                                        .mapNotNull(::playerView)
                                        .firstOrNull()
                                        ?.player
                                playing =
                                    player != null &&
                                        player.currentPosition >= 500 &&
                                        (player.isPlaying ||
                                            player.playbackState == Player.STATE_ENDED)
                            }
                            if (playing) return@withTimeoutOrNull true
                            delay(100)
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } == true
                if (!advanced) {
                    val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                    device.takeScreenshot(File(cache, "viewer-playback-failure.png"))
                    device.dumpWindowHierarchy(File(cache, "viewer-playback-failure.xml"))
                }
                assertTrue("Native play control must advance actual video playback", advanced)
                assertFalse(
                    device.hasObject(
                        By.text("This item is no longer accessible or could not be opened.")
                    )
                )
                device.findObject(By.desc("Close viewer")).click()
            }
        }
    }

    private suspend fun withFixture(
        video: Boolean,
        block: suspend (String, Uri, ByteArray, () -> Unit) -> Unit,
    ) {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        requireFixtureOnlyGalleryAccess(context)
        val name = "viewer-${UUID.randomUUID()}.${if(video)"mp4" else "png"}"
        val bytes =
            instrumentation.context.assets
                .open(if (video) "later-appearance.mp4" else "astronaut.png")
                .use { it.readBytes() }
        val collection =
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri =
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/png")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (video) "Movies/SmartGalleryTests/" else "Pictures/SmartGalleryTests/",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            )!!
        var removed = false
        fun removeFixture() {
            if (!removed) {
                context.contentResolver.delete(uri, null, null)
                removed = true
            }
        }
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            block(name, uri, bytes, ::removeFixture)
        } finally {
            // Remove only this test-created public-domain fixture.
            removeFixture()
            GalleryRepository(context).reconcile()
        }
    }

    private fun launchFresh(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            Intent(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    MainActivity::class.java,
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
}
