package app.sde

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
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
        // The charts animate without pause, so the screen is never idle: don't wait for it before
        // every tap, or the taps land seconds late.
        Configurator.getInstance().waitForIdleTimeout = 0
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
            // These two simulate many paths, which takes longer than an 8 second run on the
            // emulator; looping would start a fresh batch every run, so stop it first.
            tap("Martingales")
            tap("Loop")
            settle()
            Thread.sleep(2_000)
            shot("04-martingales")
            tapInRow("Monte Carlo", anchor = "Martingales")
            tap("Loop")
            settle()
            Thread.sleep(2_000)
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
        // Generous: drawing a thousand paths in software rendering keeps the emulator busy.
        val target = device.wait(Until.findObject(By.text(text)), 30_000) ?: error("\"$text\" is not on screen")
        target.click()
    }

    /** Waits while a screen shows "Simulating…" instead of its chart; the emulator is slow. */
    private fun settle() {
        Thread.sleep(1_000)
        check(device.wait(Until.gone(By.textStartsWith("Simulating")), 240_000)) { "Still simulating" }
    }

    /** Taps a chip that may be off screen, scrolling its row (the one holding [anchor]) until it shows. */
    private fun tapInRow(text: String, anchor: String) {
        // Both the screen and the chip row scroll; the row is the shorter one.
        val row = device.findObjects(By.scrollable(true).hasDescendant(By.text(anchor)))
            .minByOrNull { it.visibleBounds.height() } ?: error("No row holds \"$anchor\"")
        repeat(4) {
            device.wait(Until.findObject(By.text(text)), 2_000)?.let { it.click(); return }
            row.scroll(Direction.RIGHT, 0.8f)
        }
        error("\"$text\" is not on screen")
    }

    private fun shot(name: String) {
        device.takeScreenshot(File(out, "$name.png"))
    }

    private companion object {
        /** Written by the shell user, so it lives where adb can pull it. */
        const val RECORDING = "/data/local/tmp/simulate.mp4"
    }
}
