package app.sde

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks through the app and saves the screenshots and the screen recording shown in the README.
 * Not a check of behaviour: the Screenshots workflow runs it on an emulator and collects the files.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val out = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")

    @Test
    fun tour() {
        out.deleteRecursively()
        out.mkdirs()
        ActivityScenario.launch(MainActivity::class.java).use {
            device.wait(Until.hasObject(By.text("Simulate")), 20_000)

            settle()

            // Tapping the selected model again restarts its 8 second run on the paths already
            // simulated, so the recording starts with empty axes and ends with the paths drawn.
            val recording = instrumentation.uiAutomation.executeShellCommand(
                "screenrecord --time-limit 9 --size 720x1600 --bit-rate 8000000 $RECORDING",
            )
            Thread.sleep(500)
            tap("Geometric Brownian motion")
            Thread.sleep(7_000)
            shot("01-simulate")
            ParcelFileDescriptor.AutoCloseInputStream(recording).use { it.readBytes() }

            tap("Labs")
            Thread.sleep(7_000)
            shot("02-integrals")
            tap("Variation")
            Thread.sleep(7_000)
            shot("03-variation")
            tap("Martingales")
            settle()
            Thread.sleep(7_000)
            shot("04-martingales")
            scrollChipsLeft(anchor = "Martingales")
            tap("Monte Carlo")
            settle()
            Thread.sleep(7_000)
            shot("05-monte-carlo")

            tap("Solver")
            Thread.sleep(9_000)
            shot("06-solver")
            tap("Demos")
            Thread.sleep(7_000)
            shot("07-demos")
        }
    }

    private fun tap(text: String) {
        val target = device.wait(Until.findObject(By.text(text)), 10_000) ?: error("\"$text\" is not on screen")
        target.click()
    }

    /** Waits while a screen shows "Simulating…" instead of its chart; the emulator is slow. */
    private fun settle() {
        device.wait(Until.gone(By.textStartsWith("Simulating")), 180_000)
    }

    /** Swipes the row of chips that holds [anchor] to the left, to bring its last chips on screen. */
    private fun scrollChipsLeft(anchor: String) {
        val y = device.findObject(By.text(anchor))?.visibleCenter?.y ?: return
        val width = device.displayWidth
        device.swipe((width * 0.85).toInt(), y, (width * 0.15).toInt(), y, 20)
        Thread.sleep(500)
    }

    private fun shot(name: String) {
        device.takeScreenshot(File(out, "$name.png"))
    }

    private companion object {
        /** Written by the shell user, so it lives where adb can pull it. */
        const val RECORDING = "/data/local/tmp/simulate.mp4"
    }
}
