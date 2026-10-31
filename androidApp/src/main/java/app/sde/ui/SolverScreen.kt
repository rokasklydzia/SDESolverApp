package app.sde.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import sde.chart.Viewport
import sde.expr.Parser
import sde.expr.differentialTex
import sde.expr.withState
import sde.ito.EvaluationPoint
import sde.ito.ItoSums
import sde.ito.LinearSde
import sde.ito.StochasticIntegral
import sde.montecarlo.Estimate
import sde.montecarlo.RunningStats
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.floor
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

private val solvers = listOf("Stochastic integral", "Linear SDE")

/** Stochastic integrals in closed form, and linear SDEs solved explicitly, one at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolverScreen() {
    var solver by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            solvers.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == solver,
                    onClick = { solver = index },
                    shape = SegmentedButtonDefaults.itemShape(index, solvers.size),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (solver == 0) IntegralSolver() else LinearSdeSolver()
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

/** How each kind of integral is called in a sentence. */
private val EvaluationPoint.integralName: String
    get() = when (this) {
        EvaluationPoint.LEFT -> "The Itô integral"
        EvaluationPoint.MIDPOINT -> "The Stratonovich integral"
        EvaluationPoint.RIGHT -> "The backward Itô integral"
    }

/** The integrator in words a reader expects: X = B, or its SDE and starting point. */
private fun integratorTex(s: StochasticIntegral): String =
    if (s.isBrownian && s.x0 == 0.0) "X_t = B_t" else
        differentialTex("dX_t", s.sde.driftExpr.withState("X_t"), s.sde.diffusionExpr.withState("X_t"), separator = separator) +
            ",\\quad X_0 = " + (if (s.x0 == floor(s.x0)) s.x0.toLong().toString() else fmt(s.x0, 2))

@Composable
private fun IntegralSolver() {
    val colors = LocalChartColors.current
    var integrand by remember { mutableStateOf("x^3") }
    var drift by remember { mutableStateOf("0") }
    var diffusion by remember { mutableStateOf("1") }
    var x0Text by remember { mutableStateOf("0") }
    var point by remember { mutableStateOf(EvaluationPoint.LEFT) }
    var seed by remember { mutableLongStateOf(1L) }
    var showWorking by remember { mutableStateOf(false) }
    val playback = rememberPlayback(PLAYBACK_STEPS)
    PlaybackClock(playback, onRunFinished = { seed++ })

    val solution = remember(integrand, drift, diffusion, x0Text, point) {
        runCatching { StochasticIntegral(integrand, drift, diffusion, parseNumber(x0Text), point) }
    }
    var check by remember { mutableStateOf<IntegralCheck?>(null) }
    LaunchedEffect(solution) {
        check = null
        val s = solution.getOrNull() ?: return@LaunchedEffect
        check = monteCarloCheck(s)
    }
    val s = solution.getOrNull()

    SolverCard("What's calculated") {
        Text("${point.integralName} of h(t, X) with respect to X, from 0 to t, in closed form.", style = MaterialTheme.typography.bodyMedium)
        MathFormula((s?.questionTex(separator) ?: "\\int_0^t h(s, X_s)\\,dX_s") + " = \\;?", fontSize = 22.sp)
        s?.let { MathFormula("\\text{where }\\; " + integratorTex(it), fontSize = 16.sp) }
    }

    SolverCard("Inputs") {
        StepLabel(1, "Integrand h(t, x)")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, formula) in integrandPresets) {
                FilterChip(selected = formula == integrand, onClick = { integrand = formula }, label = { Text(label) })
            }
        }
        FormulaField("h(t, x)", integrand, { integrand = it }, Modifier.fillMaxWidth())
        Note("Write X as x and time as t, e.g. x^3, exp(x - t/2) or t*cos(x).")

        StepLabel(2, "Kind of integral")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in EvaluationPoint.entries) {
                FilterChip(selected = p == point, onClick = { point = p }, label = { Text(p.label) })
            }
        }

        StepLabel(3, "Integrator dX = μ dt + σ dB")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in solverIntegrators) {
                val selected = preset.drift == drift && preset.diffusion == diffusion && preset.x0 == x0Text
                FilterChip(
                    selected = selected,
                    onClick = { drift = preset.drift; diffusion = preset.diffusion; x0Text = preset.x0 },
                    label = { Text(if (preset.label == "dB") "Brownian motion B" else preset.label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormulaField("μ(t, x)", drift, { drift = it }, Modifier.weight(1f))
            FormulaField("σ(t, x)", diffusion, { diffusion = it }, Modifier.weight(1f))
            FormulaField("X(0)", x0Text, { x0Text = it }, Modifier.weight(0.6f))
        }
    }

    SolverCard("Result", highlighted = true) {
        val muted = LocalContentColor.current.copy(alpha = 0.8f)
        solution.onFailure { ErrorText(it.message ?: "Cannot read these formulas") }
        if (s != null) {
            val answer = s.answerTex(separator)
            if (answer == null) {
                MathFormula(s.questionTex(separator) + " = \\;?", fontSize = 22.sp)
                Note(
                    if (s.antiderivative == null) "No closed form: h has no antiderivative in x in the solver's table (powers, exp, sin, cos and polynomials times them). The numbers below still hold."
                    else "No closed form: F(0, X₀) is undefined for this starting point. The numbers below still hold.",
                    color = muted,
                )
            } else {
                MathFormula(s.questionTex(separator) + " = " + answer, fontSize = 22.sp)
                Note(
                    if (s.fullyClosed) "A closed form: no integral is left in the answer."
                    else "Closed up to a ds-integral, which is an ordinary (pathwise) integral with no closed form in general.",
                    color = muted,
                )
            }

            Spacer(Modifier.height(8.dp))
            val exactMean = s.mean(HORIZON)
            val exactVariance = s.itoVariance(HORIZON)
            if (exactMean != null) {
                Text("Mean at t = 1: ${fmt(exactMean)}", style = MaterialTheme.typography.bodyMedium)
            }
            exactVariance?.let { Text("Variance at t = 1: ${fmt(it)}  (Itô isometry, ∫E[h²] ds)", style = MaterialTheme.typography.bodyMedium) }
            if (s.isGaussian) Note("h does not involve B, so this is a Wiener integral: exactly normal, N(0, ∫h² ds).", color = muted)
            check?.let { c ->
                Note("Simulated over $CHECK_PATHS paths: mean ${fmt(c.mean.mean)} ± ${fmt(c.mean.standardError)}, variance ${fmt(c.variance)}.", color = muted)
            } ?: Note("Checking over $CHECK_PATHS simulated paths…", color = muted)

            if (s.antiderivative != null) {
                TextButton(onClick = { showWorking = !showWorking }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showWorking) "Hide working" else "Show working")
                }
            }
            if (showWorking) {
                Note(
                    "Itô's formula run backwards: if F_x = h, then ∫h dX = F(t, X_t) − F(0, X₀) − ∫R ds, with " +
                        "R = F_t + (½ − θ)σ²h_x and θ = 0 for Itô, ½ for Stratonovich, 1 for backward.",
                    color = muted,
                )
                s.antiderivative?.let { MathFormula("F(t, x) = " + it.tex(), fontSize = 16.sp) }
                s.remainder?.let { MathFormula("R(t, x) = " + it.tex(), fontSize = 16.sp) }
            }
        }
    }

    // One path: the Riemann sum on a coarse mesh against the closed form.
    if (s != null) {
        SectionTitle("Check on one simulated path")
        Note("The ${point.label} Riemann sum on $SUM_STEPS steps should follow the closed form.")
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
        PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })
    }
}

@Composable
private fun LinearSdeSolver() {
    var drift by remember { mutableStateOf("kappa*(m - x)") }
    var diffusion by remember { mutableStateOf("sigma") }
    var showWorking by remember { mutableStateOf(false) }

    val parsed = remember(drift, diffusion) { runCatching { Parser.parse(drift) to Parser.parse(diffusion) }.getOrNull() }
    val solution = remember(drift, diffusion) { runCatching { LinearSde.of(drift, diffusion) } }

    SolverCard("What's calculated") {
        Text(
            "The explicit solution X_t of a linear SDE: one whose drift and diffusion are both linear in X.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val sde = parsed?.let { (mu, sigma) -> differentialTex("dX_t", mu.withState("X_t"), sigma.withState("X_t"), separator = separator) }
            ?: "dX_t = \\left(a(t) X_t + b(t)\\right) dt + \\left(c(t) X_t + d(t)\\right) dB_t"
        MathFormula("$sde,\\quad X_0 = x_0", fontSize = 20.sp)
    }

    SolverCard("Inputs") {
        StepLabel(1, "Pick an example, or type your own")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, d, s) in linearPresets) {
                FilterChip(selected = d == drift && s == diffusion, onClick = { drift = d; diffusion = s }, label = { Text(label) })
            }
        }
        StepLabel(2, "Drift μ(t, x)")
        FormulaField("μ(t, x)", drift, { drift = it }, Modifier.fillMaxWidth())
        StepLabel(3, "Diffusion σ(t, x)")
        FormulaField("σ(t, x)", diffusion, { diffusion = it }, Modifier.fillMaxWidth())
        Note("Write X as x. Other names such as kappa or sigma stay as letters in the answer.")
    }

    SolverCard("Result", highlighted = true) {
        val muted = LocalContentColor.current.copy(alpha = 0.8f)
        solution.onFailure { ErrorText(it.message ?: "Cannot read these formulas") }
        solution.onSuccess { linear ->
            if (linear == null) {
                Note("Not linear in x: the drift must be a(t)x + b(t) and the diffusion c(t)x + d(t).", color = muted)
            } else {
                val tex = linear.texSolution(separator)
                MathFormula(tex.solution, fontSize = 20.sp)
                MathFormula("\\text{where }\\; " + tex.phi, fontSize = 16.sp)
                tex.mean?.let { MathFormula(it, fontSize = 16.sp) }
                tex.variance?.let { MathFormula(it, fontSize = 16.sp) }
                if (linear.additiveNoise) Note("X_t is normal: the noise enters additively, so X_t is a Wiener integral plus a deterministic function.", color = muted)
                TextButton(onClick = { showWorking = !showWorking }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showWorking) "Hide working" else "Show working")
                }
                if (showWorking) {
                    Note(
                        "Write the drift as a(t)x + b(t) and the diffusion as c(t)x + d(t). The stochastic exponential " +
                            "Φ solves the homogeneous part (b = d = 0); variation of constants gives the rest.",
                        color = muted,
                    )
                    MathFormula(tex.coefficients, fontSize = 16.sp)
                }
            }
        }
    }
}
