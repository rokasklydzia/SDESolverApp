package app.sde

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
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
        // The charts animate without pause, so the screen is never idle: wait for it only briefly
        // before each query, or the taps land seconds late. Not at all leaves the view stale.
        Configurator.getInstance().waitForIdleTimeout = 1_000
        ActivityScenario.launch(MainActivity::class.java).use {
            device.wait(Until.hasObject(By.text("Simulate")), 20_000)

            settle()

            // Tapping the selected model again restarts its 8 second run on the paths already
            // simulated, so the recording starts with empty axes and ends with the paths drawn.
            val recording = instrumentation.uiAutomation.executeShellCommand(
                "screenrecord --time-limit 11 --size 720x1600 --bit-rate 8000000 $RECORDING",
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
            Thread.sleep(7_000)
            shot("06-solver")
            tap("Demos")
            Thread.sleep(7_000)
            shot("07-demos")
        }
    }

    private fun tap(text: String) {
        // Generous: drawing a thousand paths in software rendering keeps the emulator busy.
        val target = device.wait(Until.findObject(By.text(text)), 30_000)
        if (target == null) {
            device.dumpWindowHierarchy(File(out, "hierarchy.xml"))
            error("\"$text\" is not on screen")
        }
        target.click()
    }

    /** Waits while a screen shows "Simulating…" instead of its chart; the emulator is slow. */
    private fun settle() {
        Thread.sleep(1_000)
        check(device.wait(Until.gone(By.textStartsWith("Simulating")), 240_000)) { "Still simulating" }
    }

    /**
     * Taps a chip that may be off screen, scrolling its row (the one holding [anchor]) until it
     * shows. Off-screen chips are not in the accessibility tree, and swipes over the chips don't
     * reliably scroll on the emulator, so this asks the row itself to scroll.
     */
    private fun tapInRow(text: String, anchor: String) {
        repeat(5) {
            device.wait(Until.findObject(By.text(text)), 2_000)?.let { it.click(); return }
            val roots = listOf(instrumentation.uiAutomation.rootInActiveWindow) +
                instrumentation.uiAutomation.windows.map { it.root }
            // Both the screen and the chip row scroll; the row is the shorter one.
            val row = roots.flatMap { scrollablesHolding(anchor, it) }
                .minByOrNull { node -> Rect().also(node::getBoundsInScreen).height() }
            if (row != null) {
                row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } else {
                // Fall back to a swipe along the row.
                val y = device.findObject(By.text(anchor))?.visibleCenter?.y ?: error("\"$anchor\" is not on screen")
                device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 40)
            }
            Thread.sleep(1_000)
        }
        device.dumpWindowHierarchy(File(out, "hierarchy.xml"))
        error("\"$text\" is not on screen")
    }

    /** The scrollable nodes under [node] whose subtree shows [text]. */
    private fun scrollablesHolding(text: String, node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        val children = (0 until node.childCount).mapNotNull { node.getChild(it) }
        val below = children.flatMap { scrollablesHolding(text, it) }
        return if (node.isScrollable && shows(text, node)) below + node else below
    }

    private fun shows(text: String, node: AccessibilityNodeInfo): Boolean =
        node.text?.toString() == text || (0 until node.childCount).any { i -> node.getChild(i)?.let { shows(text, it) } == true }

    private fun shot(name: String) {
        device.takeScreenshot(File(out, "$name.png"))
    }

    private companion object {
        /** Written by the shell user, so it lives where adb can pull it. */
        const val RECORDING = "/data/local/tmp/simulate.mp4"
    }
}
