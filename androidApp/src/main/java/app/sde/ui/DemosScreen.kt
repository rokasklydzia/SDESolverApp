package app.sde.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import sde.chart.Viewport
import sde.live.Ensemble
import sde.model.ScaledRandomWalk
import sde.model.marginalDensity
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

@Composable
fun DemosScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("White noise and its integral", style = MaterialTheme.typography.titleMedium)
        MathFormula("B_t = \\int_0^t \\xi_s\\,ds,\\quad \\xi_{t_i} \\approx \\frac{\\Delta B_i}{\\Delta t} \\sim N\\left(0, \\frac{1}{\\Delta t}\\right)")
        Text(
            "White noise ξ = ΔW/Δt has spikes of size 1/√Δt, so it has no limit as Δt → 0. " +
                "Its running sum W = ΣΔW does: that limit is Brownian motion.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        WhiteNoiseScope()

        Spacer(Modifier.height(24.dp))
        Text("Random walk becoming Brownian motion", style = MaterialTheme.typography.titleMedium)
        MathFormula("S^{(n)}_t = \\frac{1}{\\sqrt{n}} \\sum_{k \\le nt} \\varepsilon_k \\;\\to\\; B_t,\\quad \\varepsilon_k = \\pm 1")
        Text(
            "Each path flips a coin n times and steps ±1/√n. As n grows the paths roughen into Brownian motion " +
                "and the histogram of the end points settles on N(0, t) (Donsker's theorem).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        RandomWalkDemo()
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A scrolling oscilloscope: new samples of white noise arrive on the right at a slow, steady pace,
 * with the Brownian path they integrate to underneath.
 */
@Composable
private fun WhiteNoiseScope() {
    val colors = LocalChartColors.current
    var samplesPerUnit by remember { mutableIntStateOf(100) }
    val window = 1.0 // time units on screen
    val scrollSpeed = 0.15 // time units per second
    val buffer = remember(samplesPerUnit) { NoiseBuffer(samplesPerUnit, (window * samplesPerUnit).toInt(), SeededRandom(7)) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(buffer) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) buffer.advance((now - previous) / 1e9 * scrollSpeed)
                previous = now
                frame = now
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in listOf(20, 100, 500)) {
            FilterChip(selected = n == samplesPerUnit, onClick = { samplesPerUnit = n }, label = { Text("Δt = 1/$n") })
        }
    }
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        frame // redraw every frame
        val noise = buffer.noise
        val w = buffer.brownian
        val count = noise.size
        if (count < 2) return@Canvas
        val half = size.height / 2f
        val dx = size.width / (buffer.capacity - 1)
        val left = size.width - (count - 1) * dx

        // Top: the noise, scaled to ±4/√Δt so its growth with n stays visible.
        val noiseScale = (half * 0.45f) / (4f * sqrt(samplesPerUnit.toFloat()))
        val noiseMid = half / 2f
        drawLine(colors.grid, Offset(0f, noiseMid), Offset(size.width, noiseMid))
        for (i in 0 until count) {
            val x = left + i * dx
            drawLine(colors.path.copy(alpha = 0.8f), Offset(x, noiseMid), Offset(x, noiseMid - noise[i] * noiseScale), strokeWidth = max(1f, dx * 0.6f))
        }

        // Bottom: the running sum, rescaled to the window so it fills its half.
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in w) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        val span = max(hi - lo, 0.5f)
        val mid = (hi + lo) / 2f
        val top = half + 8.dp.toPx()
        val height = half - 8.dp.toPx()
        val path = Path()
        for (i in 0 until count) {
            val x = left + i * dx
            val y = top + height / 2f - (w[i] - mid) / span * height * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(colors.grid, Offset(0f, half + 4.dp.toPx()), Offset(size.width, half + 4.dp.toPx()))
        drawPath(path, colors.highlight, style = Stroke(width = 2.dp.toPx()))
    }
}

/** The last [capacity] samples of ξ = Z/√Δt and of W, advanced in steps of Δt = 1/[samplesPerUnit]. */
private class NoiseBuffer(val samplesPerUnit: Int, val capacity: Int, private val random: SeededRandom) {
    val noise = ArrayDeque<Float>()
    val brownian = ArrayDeque<Float>()
    private var pending = 0.0
    private var w = 0.0

    fun advance(time: Double) {
        pending += time * samplesPerUnit
        while (pending >= 1.0) {
            pending -= 1.0
            val z = random.nextGaussian()
            noise.addLast((z * sqrt(samplesPerUnit.toDouble())).toFloat())
            w += z / sqrt(samplesPerUnit.toDouble())
            brownian.addLast(w.toFloat())
            if (noise.size > capacity) {
                noise.removeFirst()
                brownian.removeFirst()
            }
        }
        if (abs(w) > 1e6) w = 0.0
    }
}

@Composable
private fun RandomWalkDemo() {
    var steps by remember { mutableIntStateOf(8) }
    var seed by remember { mutableLongStateOf(3L) }
    val walk = remember { ScaledRandomWalk(0.0, 1.0) }
    var ensemble by remember { mutableStateOf<Ensemble?>(null) }
    var viewport by remember { mutableStateOf(Viewport(0.0, 1.0, -2.5, 2.5)) }
    val playback = rememberPlayback(steps)

    LaunchedEffect(steps, seed) {
        ensemble = Ensemble.simulate(walk, TimeGrid(1.0, steps), paths = 4000, seed = seed)
    }
    PlaybackClock(playback, onRunFinished = { seed++ })

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in listOf(4, 16, 64, 256, 1024)) {
            FilterChip(selected = n == steps, onClick = { steps = n; playback.restart() }, label = { Text("n = $n") })
        }
    }
    ensemble?.let { current ->
        if (current.grid.steps == steps) {
            LiveChart(
                ensemble = current,
                revealed = { playback.revealed },
                viewport = viewport,
                onViewportChange = { viewport = it },
                onResetViewport = { viewport = Viewport(0.0, 1.0, -2.5, 2.5) },
                options = ChartOptions(shownPaths = 25, showBand = false),
                pdf = walk.marginalDensity(),
                height = 260.dp,
            )
        }
    }
    PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })
}
