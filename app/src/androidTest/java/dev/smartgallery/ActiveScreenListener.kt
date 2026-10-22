package dev.smartgallery

import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunListener

/** Explicit host opt-in; bounded screen hold only, with no keyguard or system-setting changes. */
@Suppress("DEPRECATION")
class ActiveScreenListener : RunListener() {
    private var screen: PowerManager.WakeLock? = null

    override fun testRunStarted(description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "dev.smartgallery.validation")
        val timeout =
            InstrumentationRegistry.getArguments()
                .getString("screenHoldMs")
                ?.toLongOrNull()
                ?.coerceIn(60_000L, 420_000L) ?: 180_000L
        screen =
            context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "SmartGalleryValidation:fixture-ui",
                )
                .apply { acquire(timeout) }
        UiDevice.getInstance(instrumentation).wakeUp()
    }

    override fun testRunFinished(result: Result) {
        screen?.let { if (it.isHeld) it.release() }
        screen = null
    }
}
