package app.sde

import android.content.Context
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
        // Start past the first-launch intro.
        instrumentation.targetContext.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(AppSettings.INTRO_SEEN, true).commit()
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
            // These two simulate many paths and start their run once they have them. Without
            // looping the run stops at its end, with every curve drawn.
            tap("Martingales")
            tap("Loop")
            settle()
            Thread.sleep(9_000)
            shot("04-martingales")
            tapLastInRow(anchor = "Martingales", arrived = "E[g(X(T))]")
            tap("Loop")
            settle()
            Thread.sleep(9_000)
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
        dismissNotRespondingDialog()
        // Generous: drawing a thousand paths in software rendering keeps the emulator busy.
        val target = device.wait(Until.findObject(By.text(text)), 30_000)
        if (target == null) {
            device.dumpWindowHierarchy(File(out, "hierarchy.xml"))
            error("\"$text\" is not on screen")
        }
        target.click()
    }

    /** Answers "Wait" if a slow system app (usually the launcher) shows "isn't responding". */
    private fun dismissNotRespondingDialog() {
        device.findObject(By.res("android", "aerr_wait"))?.click()
    }

    /** Waits while a screen shows "Simulating…" instead of its chart; the emulator is slow. */
    private fun settle() {
        Thread.sleep(1_000)
        check(device.wait(Until.gone(By.textStartsWith("Simulating")), 240_000)) { "Still simulating" }
    }

    /**
     * Taps the last chip of the row that holds [anchor], which starts off screen. The row's own
     * scroll action moves it reliably, but the accessibility tree that UI Automator reads lags
     * behind the busy emulator's screen, so the chip is tapped by position at the row's end, and
     * [arrived] confirms the tap.
     */
    private fun tapLastInRow(anchor: String, arrived: String) {
        val roots = listOf(instrumentation.uiAutomation.rootInActiveWindow) +
            instrumentation.uiAutomation.windows.map { it.root }
        // Both the screen and the chip row scroll; the row is the shorter one.
        val row = roots.flatMap { scrollablesHolding(anchor, it) }
            .minByOrNull { node -> Rect().also(node::getBoundsInScreen).height() }
            ?: error("No row holds \"$anchor\"")
        val bounds = Rect().also(row::getBoundsInScreen)
        repeat(5) {
            if (!row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return@repeat
            Thread.sleep(500)
        }
        Thread.sleep(1_000)
        val halfChip = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        device.click(bounds.right - halfChip, bounds.centerY())
        if (!device.wait(Until.hasObject(By.textContains(arrived)), 30_000)) {
            device.dumpWindowHierarchy(File(out, "hierarchy.xml"))
            error("Tapping the end of the row did not open \"$arrived\"")
        }
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
