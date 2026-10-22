package dev.smartgallery

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @Test
    fun deniedAccessGalleryNavigationAndSettingsRemainUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(
            "Denied-access test requires no storage permission",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED,
        )
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Smart Gallery")), 10_000))
            assertTrue(device.wait(Until.hasObject(By.text("Allow photos & videos")), 10_000))
            assertTrue(device.takeScreenshot(File(context.cacheDir, "gallery-denied-test.png")))
            device.findObject(By.text("People")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Familiar faces")), 10_000))
            device.findObject(By.text("Review faces")).click()
            assertTrue(
                device.wait(Until.hasObject(By.text("A little help, when you like")), 10_000)
            )
            device.waitForIdle()
            device.findObject(By.desc("Settings and indexing")).click()
            val settingsVisible = device.wait(Until.hasObject(By.text("On this phone")), 10_000)
            if (!settingsVisible) {
                device.takeScreenshot(File(context.cacheDir, "settings-navigation-failure.png"))
                device.dumpWindowHierarchy(
                    File(context.cacheDir, "settings-navigation-failure.xml")
                )
            }
            assertTrue("Settings must open from Review faces", settingsVisible)
            device.waitForIdle()
            device.findObject(By.desc("Back")).click()
            device.waitForIdle()
            assertTrue(device.wait(Until.hasObject(By.text("Review faces")), 10_000))
        }
    }
}
