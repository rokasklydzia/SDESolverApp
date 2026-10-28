package app.sde.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import sde.chart.Viewport
import sde.expr.format
import sde.ito.EvaluationPoint
import sde.ito.ItoSums
import sde.ito.LinearSde
import sde.ito.StochasticIntegral
import sde.montecarlo.Estimate
import sde.montecarlo.RunningStats
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.sqrt

private const val HORIZON = 1.0
private const val PLAYBACK_STEPS = 1000

/** The closed form is drawn on a fine mesh of the path; the Riemann sum on a coarser one. */
private const val FINE_STEPS = 4096
private const val SUM_STEPS = 256

/** Paths for the Monte Carlo check of the mean and variance. */
private const val CHECK_PATHS = 2000
private const val CHECK_STEPS = 512

private val integrandPresets = listOf(
    "B" to "x",
    "B³" to "x^3",
    "t" to "t",
    "e^B" to "exp(x)",
    "cos B" to "cos(x)",
    "B·e^B" to "x*exp(x)",
    "e^(B − t/2)" to "exp(x - t/2)",
)

private class IntegratorPreset(val label: String, val drift: String, val diffusion: String, val x0: String)

private val solverIntegrators = listOf(
    IntegratorPreset("dB", "0", "1", "0"),
    IntegratorPreset("GBM", "0.1*x", "0.4*x", "1"),
    IntegratorPreset("Ornstein–Uhlenbeck", "-x", "1", "0"),
)

private val linearPresets = listOf(
    Triple("GBM", "mu*x", "sigma*x"),
    Triple("Ornstein–Uhlenbeck", "kappa*(m - x)", "sigma"),
    Triple("BM with drift", "mu", "sigma"),
    Triple("dX = −tX dt + dB", "-t*x", "1"),
    Triple("General", "a*x + b", "c*x + d"),
)

/** Stochastic integrals in closed form, and linear SDEs solved explicitly. */
@Composable
fun SolverScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        IntegralSolver()
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        LinearSdeSolver()
        Spacer(Modifier.height(24.dp))
    }
}

/** Sample mean and variance of the integral at t = 1 over many paths. */
private class IntegralCheck(val mean: Estimate, val variance: Double)

private suspend fun monteCarloCheck(integral: StochasticIntegral): IntegralCheck = withContext(Dispatchers.Default) {
    val grid = TimeGrid(HORIZON, CHECK_STEPS)
    val random = SeededRandom(99)
    val stats = RunningStats()
    repeat(CHECK_PATHS) {
        if (it % 64 == 0) ensureActive()
        stats.add(integral.along(grid, ItoSums.brownianPath(grid, random)).first.last())
    }
    IntegralCheck(Estimate(stats.mean, sqrt(stats.variance / stats.count), stats.count), stats.variance)
}

@Composable
private fun IntegralSolver() {
    val colors = LocalChartColors.current
    var integrand by remember { mutableStateOf("x^3") }
    var drift by remember { mutableStateOf("0") }
    var diffusion by remember { mutableStateOf("1") }
    var x0Text by remember { mutableStateOf("0") }
    var point by remember { mutableStateOf(EvaluationPoint.LEFT) }
    var seed by remember { mutableLongStateOf(1L) }
    val playback = rememberPlayback(PLAYBACK_STEPS)
    PlaybackClock(playback, onRunFinished = { seed++ })

    Text("Stochastic integral solver", style = MaterialTheme.typography.titleMedium)
    Note(
        "Finds ∫₀ᵗ h(s, X) dX by running Itô's formula backwards: if F_x = h, then " +
            "∫h dX = F(t, X_t) − F(0, X₀) − ∫(F_t + (½ − θ)σ²h_x) ds, with θ = 0 for Itô, ½ for Stratonovich and 1 for " +
            "the backward integral. Write X as x.",
    )
    Spacer(Modifier.height(8.dp))

    val solution = remember(integrand, drift, diffusion, x0Text, point) {
        runCatching { StochasticIntegral(integrand, drift, diffusion, parseNumber(x0Text), point) }
    }
    var check by remember { mutableStateOf<IntegralCheck?>(null) }
    LaunchedEffect(solution) {
        check = null
        val s = solution.getOrNull() ?: return@LaunchedEffect
        check = monteCarloCheck(s)
    }

    solution.onFailure { ErrorText(it.message ?: "Cannot read these formulas") }
    solution.onSuccess { s ->
        val answer = s.answerText()
        if (answer == null) {
            Formula("∫₀ᵗ (${s.integrand.format()}) dX = ?")
            Note(
                if (s.antiderivative == null) "No antiderivative of h in x in the solver's table (powers, exp, sin, cos and polynomials times them), so only the numbers below."
                else "F(0, X₀) is undefined for this starting point, so only the numbers below.",
            )
        } else {
            Formula("∫₀ᵗ (${s.integrand.format()}) dX = $answer")
            s.antiderivative?.let { Note("F(t, x) = ${it.format()}") }
            s.remainder?.let { Note("R = F_t + (½ − θ)σ²h_x = ${it.format()}") }
            Note(if (s.fullyClosed) "Fully closed form." else "The ds-integral is a pathwise Riemann integral and generally has no closed form.")
        }

        // Moments: exact where known, then a Monte Carlo check.
        val exactMean = s.mean(HORIZON)
        val exactVariance = s.itoVariance(HORIZON)
        if (exactMean != null) {
            val variancePart = exactVariance?.let { ", Var = ${fmt(it)} (Itô isometry: ∫E[h²] ds)" } ?: ""
            Note("At t = 1: E = ${fmt(exactMean)}$variancePart.")
        }
        if (s.isGaussian) Note("h does not involve B, so this is a Wiener integral: exactly normal, N(0, ∫h² ds).")
        check?.let { c ->
            Note("Monte Carlo over $CHECK_PATHS paths: mean ${fmt(c.mean.mean)} ± ${fmt(c.mean.standardError)}, variance ${fmt(c.variance)}.")
        } ?: Note("Checking over $CHECK_PATHS paths…")

        // One path: the Riemann sum on a coarse mesh against the closed form.
        val fine = TimeGrid(HORIZON, FINE_STEPS)
        val path = remember(s, seed) {
            val w = ItoSums.brownianPath(fine, SeededRandom(seed))
            val (_, closed) = s.along(fine, w)
            val coarse = TimeGrid(HORIZON, SUM_STEPS)
            val (sum, _) = s.along(coarse, ItoSums.coarsen(w, FINE_STEPS / SUM_STEPS))
            Triple(coarse, sum, closed)
        }
        val (coarse, sum, closed) = path
        val curves = buildList {
            closed?.let { add(Curve(fine, it, colors.series[1], width = 2.dp, dashed = true)) }
            add(Curve(coarse, sum, colors.series[0], head = true))
        }
        val fit = Viewport.fit(0.0..HORIZON, valueRange(curves))
        var viewport by remember(path) { mutableStateOf(fit) }
        CurveChart(
            horizon = HORIZON,
            curves = curves,
            revealedTime = { playback.revealed / PLAYBACK_STEPS * HORIZON },
            viewport = viewport,
            onViewportChange = { viewport = it },
            onResetViewport = { viewport = fit },
            height = 240.dp,
        )
        LegendRows(
            buildList {
                add(colors.series[0] to "${point.label} sum on $SUM_STEPS steps of one path")
                if (closed != null) add(colors.series[1] to "the closed form on the same path")
            },
        )
    }

    PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })

    SectionTitle("Integrand h(t, x)")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, formula) in integrandPresets) {
            FilterChip(selected = formula == integrand, onClick = { integrand = formula }, label = { Text(label) })
        }
    }
    FormulaField("h(t, x)", integrand, { integrand = it }, Modifier.fillMaxWidth())

    SectionTitle("Integral")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in EvaluationPoint.entries) {
            FilterChip(selected = p == point, onClick = { point = p }, label = { Text(p.label) })
        }
    }

    SectionTitle("Integrator dX = μ dt + σ dB")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in solverIntegrators) {
            val selected = preset.drift == drift && preset.diffusion == diffusion && preset.x0 == x0Text
            FilterChip(
                selected = selected,
                onClick = { drift = preset.drift; diffusion = preset.diffusion; x0Text = preset.x0 },
                label = { Text(preset.label) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormulaField("μ(t, x)", drift, { drift = it }, Modifier.weight(1f))
        FormulaField("σ(t, x)", diffusion, { diffusion = it }, Modifier.weight(1f))
        FormulaField("X(0)", x0Text, { x0Text = it }, Modifier.weight(0.6f))
    }
}

@Composable
private fun LinearSdeSolver() {
    var drift by remember { mutableStateOf("kappa*(m - x)") }
    var diffusion by remember { mutableStateOf("sigma") }

    Text("Linear SDE solver", style = MaterialTheme.typography.titleMedium)
    Note(
        "Solves dX = (a(t)X + b(t)) dt + (c(t)X + d(t)) dB explicitly with the stochastic exponential Φ. " +
            "B stands for B_t; parameters stay as letters.",
    )
    Spacer(Modifier.height(8.dp))

    val solution = remember(drift, diffusion) { runCatching { LinearSde.of(drift, diffusion) } }
    solution.onFailure { ErrorText(it.message ?: "Cannot read these formulas") }
    solution.onSuccess { linear ->
        if (linear == null) {
            Note("Not linear in x: the drift must be a(t)x + b(t) and the diffusion c(t)x + d(t).")
        } else {
            for (line in linear.steps()) Formula(line)
        }
    }

    SectionTitle("Examples")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, d, s) in linearPresets) {
            FilterChip(selected = d == drift && s == diffusion, onClick = { drift = d; diffusion = s }, label = { Text(label) })
        }
    }
    FormulaField("Drift μ(t, x)", drift, { drift = it }, Modifier.fillMaxWidth())
    FormulaField("Diffusion σ(t, x)", diffusion, { diffusion = it }, Modifier.fillMaxWidth())
}
