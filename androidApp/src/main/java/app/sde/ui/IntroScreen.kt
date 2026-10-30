package app.sde.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sde.ito.ItoSums
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.sqrt

private class IntroPage(val title: String, val body: String)

private val introPages = listOf(
    IntroPage(
        "Stochastic calculus you can watch",
        "Simulate stochastic differential equations path by path, and see Itô calculus happen on the screen " +
            "instead of only on paper.",
    ),
    IntroPage(
        "Simulate",
        "Pick a model such as geometric Brownian motion, Ornstein–Uhlenbeck or Heston, or type your own drift and " +
            "diffusion. The paths, their mean, the 5–95% band and the density of X(t) grow together.",
    ),
    IntroPage(
        "Labs and Solver",
        "The Labs put the course on screen: Itô against Stratonovich sums, quadratic variation, martingale checks, " +
            "covariation and Monte Carlo. The Solver finds ∫h dX in closed form and solves linear SDEs.",
    ),
    IntroPage(
        "Getting around a chart",
        "Pinch to zoom, drag to pan and double-tap to fit the chart again. Play and Pause run the clock, the " +
            "slider scrubs through time, and New paths draws a fresh sample.",
    ),
)

/** A few pages shown on first launch, and again from the About tab, over a live fan of Brownian paths. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { introPages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == introPages.lastIndex
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text("Skip") }
            }
            BrownianFan(Modifier.fillMaxWidth().height(240.dp))
            Spacer(Modifier.height(24.dp))
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { index ->
                val page = introPages[index]
                Column {
                    Text(page.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(page.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(introPages.size) { index ->
                        val color = if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    }
                }
                Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Text(if (last) "Start" else "Next")
                }
            }
        }
    }
}

private const val FAN_PATHS = 14
private const val FAN_STEPS = 240

/** Brownian paths drawing themselves next to the ±2√t envelope, a fresh set every run. */
@Composable
private fun BrownianFan(modifier: Modifier) {
    val colors = LocalChartColors.current
    var seed by remember { mutableLongStateOf(1L) }
    val playback = remember { Playback(FAN_STEPS).apply { secondsPerRun = 4f } }
    PlaybackClock(playback, onRunFinished = { seed++ })
    val grid = remember { TimeGrid(1.0, FAN_STEPS) }
    val paths = remember(seed) {
        val random = SeededRandom(seed)
        List(FAN_PATHS) { ItoSums.brownianPath(grid, random) }
    }

    Canvas(modifier) {
        val scale = size.height / 2 / 3.0
        fun x(i: Int) = (i.toFloat() / FAN_STEPS) * size.width
        fun y(v: Double) = (size.height / 2 - v * scale).toFloat()
        val shown = playback.revealed.toInt().coerceIn(0, FAN_STEPS)

        drawLine(colors.grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
        for (sign in listOf(1.0, -1.0)) {
            val envelope = Path().apply {
                moveTo(x(0), y(0.0))
                for (i in 1..FAN_STEPS) lineTo(x(i), y(sign * 2 * sqrt(grid.time(i))))
            }
            drawPath(envelope, colors.label.copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
        }
        // The highlighted path last, so it stays on top.
        for (index in paths.indices.reversed()) {
            val w = paths[index]
            val path = Path().apply {
                moveTo(x(0), y(w[0]))
                for (i in 1..shown) lineTo(x(i), y(w[i]))
            }
            val highlighted = index == 0
            drawPath(
                path,
                if (highlighted) colors.highlight else colors.path.copy(alpha = 0.55f),
                style = Stroke(if (highlighted) 2.5.dp.toPx() else 1.5.dp.toPx()),
            )
        }
    }
}
