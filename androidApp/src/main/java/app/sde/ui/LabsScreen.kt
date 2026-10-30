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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import sde.analytic.Quadrature
import sde.catalog.ModelCatalog
import sde.catalog.PresetModel
import sde.chart.Viewport
import sde.expr.CompiledExpr
import sde.expr.Derivative
import sde.expr.Evaluator
import sde.expr.Exprs
import sde.expr.Parser
import sde.expr.differentialTex
import sde.expr.format
import sde.expr.withState
import sde.ito.CovariationSetup
import sde.ito.EvaluationPoint
import sde.ito.ItoFormula
import sde.ito.ItoSums
import sde.ito.MartingaleCheck
import sde.ito.MartingaleRun
import sde.ito.alongPath
import sde.model.CustomSde
import sde.model.marginalDensity
import sde.model.solveAlong
import sde.montecarlo.Estimate
import sde.montecarlo.MonteCarlo
import sde.montecarlo.MonteCarloSettings
import sde.montecarlo.RunningEstimate
import sde.montecarlo.TimeGrid
import sde.montecarlo.quantileOfSorted
import sde.random.SeededRandom
import kotlin.math.abs

private val labs = listOf("Integrals", "Variation", "Martingales", "Covariation", "Monte Carlo")

/** Every lab runs on [0, 1] and reveals its curves over [PLAYBACK_STEPS] animation steps. */
private const val HORIZON = 1.0
private const val PLAYBACK_STEPS = 1000

/** The finest mesh: one path is simulated on it, and coarser meshes see every k-th point of it. */
private const val FINE_STEPS = 4096

/** Hands-on versions of the course's Itô calculus: integrals, variations, martingales, covariations and Monte Carlo. */
@Composable
fun LabsScreen() {
    var lab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labs.forEachIndexed { index, name ->
                FilterChip(selected = index == lab, onClick = { lab = index }, label = { Text(name) })
            }
        }
        Spacer(Modifier.height(8.dp))
        when (lab) {
            0 -> IntegralsLab()
            1 -> VariationLab()
            2 -> MartingaleLab()
            3 -> CovariationLab()
            else -> MonteCarloLab()
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Integrals: left, mid and right point sums along one path.

/** An integrator dX = μ dt + σ dB with X(0) = x0, as chips in the Labs and Solver tabs. */
internal class IntegratorPreset(val label: String, val drift: String, val diffusion: String, val x0: String)

private val integrators = listOf(
    IntegratorPreset("Brownian motion", "0", "1", "0"),
    IntegratorPreset("GBM", "0.1*x", "0.4*x", "1"),
    IntegratorPreset("Ornstein–Uhlenbeck", "-x", "1", "0"),
)

private val integrands = listOf("x", "x^2", "cos(x)", "exp(x)")

private class IntegralsResult(
    val coarse: TimeGrid,
    val sums: Map<EvaluationPoint, DoubleArray>,
    val fine: TimeGrid,
    val limits: Map<EvaluationPoint, DoubleArray>,
    /** ∫ h_x d⟨X⟩ on the fine mesh: the limit of right − left. */
    val gap: DoubleArray,
)

private fun computeIntegrals(integrand: String, drift: String, diffusion: String, x0: Double, mesh: Int, seed: Long): IntegralsResult {
    val fine = TimeGrid(HORIZON, FINE_STEPS)
    val sde = CustomSde(drift, diffusion)
    val w = ItoSums.brownianPath(fine, SeededRandom(seed))
    val x = sde.solveAlong(fine, x0, w)
    val h = Parser.parse(integrand)
    val hCompiled = Evaluator.compile(h)
    val hx = Evaluator.compile(Derivative.dx(h))
    val sigma = Evaluator.compile(sde.diffusionExpr)

    val coarse = TimeGrid(HORIZON, mesh)
    val xCoarse = ItoSums.coarsen(x, FINE_STEPS / mesh)
    val hCoarse = hCompiled.alongPath(coarse, xCoarse)
    val sums = EvaluationPoint.entries.associateWith { ItoSums.riemann(hCoarse, xCoarse, it) }

    val ito = ItoSums.riemann(hCompiled.alongPath(fine, x), x, EvaluationPoint.LEFT)
    val gap = ItoSums.timeIntegral(
        DoubleArray(x.size) { val t = fine.time(it); val s = sigma.eval(t, x[it]); hx.eval(t, x[it]) * s * s },
        fine,
    )
    val limits = EvaluationPoint.entries.associateWith { point ->
        DoubleArray(x.size) { ito[it] + point.weightOfRight * gap[it] }
    }
    return IntegralsResult(coarse, sums, fine, limits, gap)
}

@Composable
private fun IntegralsLab() {
    val colors = LocalChartColors.current
    var integrand by remember { mutableStateOf("x") }
    var drift by remember { mutableStateOf("0") }
    var diffusion by remember { mutableStateOf("1") }
    var x0Text by remember { mutableStateOf("0") }
    var mesh by remember { mutableIntStateOf(64) }
    var seed by remember { mutableLongStateOf(1L) }
    val playback = rememberPlayback(PLAYBACK_STEPS)
    PlaybackClock(playback, onRunFinished = { seed++ })

    Text("Itô, Stratonovich and backward integrals", style = MaterialTheme.typography.titleMedium)
    MathFormula("\\sum_i h\\left(X_{t_i}\\right)\\,\\Delta X_i \\;\\to\\; \\int_0^t h(X_s)\\,dX_s")
    Note(
        "One path of X, and the sum Σ h(X)·ΔX with h taken at the left point, the average of both ends, or the right " +
            "point of each step. For ordinary integrals the choice would not matter. Here the sums settle on three " +
            "different limits (dashed), spaced ½∫h′(X) d⟨X⟩ apart. Refine the mesh to watch them converge.",
    )
    Spacer(Modifier.height(8.dp))

    val result = remember(integrand, drift, diffusion, x0Text, mesh, seed) {
        runCatching { computeIntegrals(integrand, drift, diffusion, parseNumber(x0Text), mesh, seed) }
    }
    val pointColors = EvaluationPoint.entries.associateWith { colors.series[it.ordinal] }

    result.onSuccess { r ->
        val curves = buildList {
            for (point in EvaluationPoint.entries) {
                add(Curve(r.fine, r.limits.getValue(point), pointColors.getValue(point).copy(alpha = 0.55f), width = 1.5.dp, dashed = true))
            }
            for (point in EvaluationPoint.entries) {
                add(Curve(r.coarse, r.sums.getValue(point), pointColors.getValue(point), head = true))
            }
        }
        val fit = Viewport.fit(0.0..HORIZON, valueRange(curves))
        var viewport by remember(r) { mutableStateOf(fit) }
        CurveChart(
            horizon = HORIZON,
            curves = curves,
            revealedTime = { playback.revealed / PLAYBACK_STEPS * HORIZON },
            viewport = viewport,
            onViewportChange = { viewport = it },
            onResetViewport = { viewport = fit },
        )
        LegendRows(EvaluationPoint.entries.map { pointColors.getValue(it) to it.label } + (colors.label to "dashed: limits as the mesh → 0"))
        val end = r.coarse.steps
        val difference = r.sums.getValue(EvaluationPoint.RIGHT)[end] - r.sums.getValue(EvaluationPoint.LEFT)[end]
        Note(
            "At t = 1 on $mesh steps: right − left = Σ Δh·ΔX = ${fmt(difference)}, " +
                "while its limit ∫h′(X) d⟨X⟩ = ${fmt(r.gap.last())} on this path.",
        )
    }
    result.onFailure { ErrorText(it.message ?: "Cannot evaluate these formulas") }

    PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })

    SectionTitle("Mesh")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in listOf(8, 32, 128, 512, 2048)) {
            FilterChip(selected = n == mesh, onClick = { mesh = n }, label = { Text("n = $n") })
        }
    }

    SectionTitle("Integrand h(x)")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in integrands) {
            FilterChip(selected = preset == integrand, onClick = { integrand = preset }, label = { Text(preset) })
        }
    }
    FormulaField("h(t, x)", integrand, { integrand = it }, Modifier.fillMaxWidth())

    SectionTitle("Integrator dX = μ dt + σ dB")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in integrators) {
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
    Note(
        "Backward Itô formula: with h = F′ the backward integral gives F(X(t)) = F(X(0)) + ∫F′(X) d̂X − ½∫F″(X) d⟨X⟩, " +
            "a minus where the forward formula has a plus.",
    )
}

// ---------------------------------------------------------------------------------------------
// Variation: Σ|ΔX|ᵖ on finer and finer meshes of one path.

private enum class VariationMeasure(val label: String) {
    FIRST("Σ|ΔX|"),
    QUADRATIC("Σ(ΔX)²"),
    CUBIC("Σ|ΔX|³"),
    WEIGHTED_CUBIC("Σ X·(ΔX)³"),
}

private val transforms = listOf("x" to "B", "exp(2*x - 3*t)" to "e^(2B − 3t)", "x^2" to "B²", "t*x" to "tB")

private val variationMeshes = listOf(16, 64, 256, 1024, 4096)

private class VariationResult(val sums: List<Pair<Int, DoubleArray>>, val limit: DoubleArray?, val fine: TimeGrid)

private fun computeVariation(transform: String, measure: VariationMeasure, seed: Long): VariationResult {
    val fine = TimeGrid(HORIZON, FINE_STEPS)
    val w = ItoSums.brownianPath(fine, SeededRandom(seed))
    val f = Parser.parse(transform)
    val x = Evaluator.compile(f).alongPath(fine, w)
    val sums = variationMeshes.map { n ->
        val xc = ItoSums.coarsen(x, FINE_STEPS / n)
        n to when (measure) {
            VariationMeasure.FIRST -> ItoSums.variation(xc, 1.0)
            VariationMeasure.QUADRATIC -> ItoSums.variation(xc, 2.0)
            VariationMeasure.CUBIC -> ItoSums.variation(xc, 3.0)
            VariationMeasure.WEIGHTED_CUBIC -> ItoSums.weightedPowerSum(xc, xc, 3)
        }
    }
    val limit = when (measure) {
        VariationMeasure.FIRST -> null
        // X = f(t, B) has dX = (…) dt + f_x dB, so ⟨X⟩ = ∫ f_x² ds.
        VariationMeasure.QUADRATIC -> {
            val dB = Evaluator.compile(ItoFormula.apply(f, Exprs.ZERO, Exprs.ONE).dB).alongPath(fine, w)
            ItoSums.timeIntegral(DoubleArray(dB.size) { dB[it] * dB[it] }, fine)
        }
        VariationMeasure.CUBIC, VariationMeasure.WEIGHTED_CUBIC -> DoubleArray(fine.steps + 1)
    }
    return VariationResult(sums, limit, fine)
}

@Composable
private fun VariationLab() {
    val colors = LocalChartColors.current
    var transform by remember { mutableStateOf("x") }
    var measure by remember { mutableStateOf(VariationMeasure.QUADRATIC) }
    var seed by remember { mutableLongStateOf(1L) }
    val playback = rememberPlayback(PLAYBACK_STEPS)
    PlaybackClock(playback, onRunFinished = { seed++ })

    Text("p-variation", style = MaterialTheme.typography.titleMedium)
    MathFormula("\\sum_i \\left(\\Delta X_i\\right)^2 \\;\\to\\; \\langle X\\rangle_t = \\int_0^t f_x(s, B_s)^2\\,ds")
    Note(
        "The same path of X = f(t, B) on meshes of 16 to 4096 steps, fainter for coarser meshes. As the mesh shrinks " +
            "Σ|ΔX| blows up, Σ(ΔX)² settles on ⟨X⟩ = ∫f_x² ds (dashed), and the third powers vanish. " +
            "That is why only dt, dB and dB·dB = dt survive in Itô's formula.",
    )
    Spacer(Modifier.height(8.dp))

    val result = remember(transform, measure, seed) { runCatching { computeVariation(transform, measure, seed) } }
    result.onSuccess { r ->
        val base = colors.series[0]
        val curves = buildList {
            r.limit?.let { add(Curve(r.fine, it, colors.highlight, width = 2.dp, dashed = true)) }
            r.sums.forEachIndexed { index, (n, sum) ->
                val weight = index.toFloat() / (r.sums.size - 1)
                add(Curve(TimeGrid(HORIZON, n), sum, base.copy(alpha = 0.3f + 0.7f * weight), width = (1 + 1.5f * weight).dp, head = index == r.sums.lastIndex))
            }
        }
        val fit = Viewport.fit(0.0..HORIZON, valueRange(curves))
        var viewport by remember(r) { mutableStateOf(fit) }
        CurveChart(
            horizon = HORIZON,
            curves = curves,
            revealedTime = { playback.revealed / PLAYBACK_STEPS * HORIZON },
            viewport = viewport,
            onViewportChange = { viewport = it },
            onResetViewport = { viewport = fit },
        )
        Column(Modifier.padding(top = 4.dp)) {
            for ((n, sum) in r.sums) Note("n = $n: ${measure.label} = ${fmt(sum.last())}")
            r.limit?.let { Note("limit: ${fmt(it.last())}") }
        }
    }
    result.onFailure { ErrorText(it.message ?: "Cannot evaluate this formula") }

    PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })

    SectionTitle("Sum")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in VariationMeasure.entries) {
            FilterChip(selected = m == measure, onClick = { measure = m }, label = { Text(m.label) })
        }
    }
    SectionTitle("Process X(t) = f(t, B(t))")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((formula, name) in transforms) {
            FilterChip(selected = formula == transform, onClick = { transform = formula }, label = { Text(name) })
        }
    }
    FormulaField("f(t, x), with x standing for B(t)", transform, { transform = it }, Modifier.fillMaxWidth())
}

// ---------------------------------------------------------------------------------------------
// Martingales: Itô's formula symbolically, then E[M(t)] over many paths.

private class MartingaleExample(
    val label: String,
    val drift: String,
    val diffusion: String,
    val f: String,
    val compensator: String,
    val x0: String,
)

private val martingaleExamples = listOf(
    MartingaleExample("B² − t", "0", "1", "x^2 - t", "0", "0"),
    MartingaleExample("tB − ∫B ds", "0", "1", "t*x", "-x", "0"),
    MartingaleExample("e^(t/2)·cos B", "0", "1", "exp(t/2)*cos(x)", "0", "0"),
    MartingaleExample("B³", "0", "1", "x^3", "0", "0"),
    MartingaleExample("X³, dX = X dt + X dB", "x", "x", "x^3", "0", "1"),
    MartingaleExample("e^(B − t/2)", "0", "1", "exp(x - t/2)", "0", "0"),
)

private const val MARTINGALE_PATHS = 10_000
private const val MARTINGALE_STEPS = 400

@Composable
private fun MartingaleLab() {
    val colors = LocalChartColors.current
    var drift by remember { mutableStateOf("0") }
    var diffusion by remember { mutableStateOf("1") }
    var f by remember { mutableStateOf("x^2 - t") }
    var compensator by remember { mutableStateOf("0") }
    var x0Text by remember { mutableStateOf("0") }
    var seed by remember { mutableLongStateOf(1L) }
    val playback = rememberPlayback(PLAYBACK_STEPS)

    Text("Martingale checker", style = MaterialTheme.typography.titleMedium)
    MathFormula("M_t = f(t, X_t) + \\int_0^t g(s, X_s)\\,ds")
    Note(
        "Is M(t) = f(t, X(t)) + ∫₀ᵗ g(s, X(s)) ds a martingale? Itô's formula gives its dt term symbolically, and " +
            "$MARTINGALE_PATHS simulated paths show whether E[M(t)] really stays at M(0). One path proves nothing.",
    )
    Spacer(Modifier.height(8.dp))

    val setup = remember(drift, diffusion, f, compensator, x0Text) {
        runCatching { MartingaleCheck(drift, diffusion, f, compensator) to parseNumber(x0Text) }
    }
    // A new seed keeps the last run on screen until the next one is ready; new formulas clear it.
    var simulation by remember(setup) { mutableStateOf<MartingaleRun?>(null) }
    var simulating by remember { mutableStateOf(false) }
    LaunchedEffect(setup, seed) {
        val (check, x0) = setup.getOrNull() ?: return@LaunchedEffect
        simulating = true
        try {
            simulation = check.simulate(x0, TimeGrid(HORIZON, MARTINGALE_STEPS), MARTINGALE_PATHS, seed)
            playback.restart()
        } finally {
            simulating = false
        }
    }
    // Fresh paths for each looped run, unless the last batch is still being simulated.
    PlaybackClock(playback, onRunFinished = { if (!simulating) seed++ })

    setup.onFailure { ErrorText(it.message ?: "Cannot read these formulas") }
    setup.onSuccess { (check, _) ->
        MathFormula(differentialTex("dM_t", check.driftOfM.withState("X_t"), check.differential.dB.withState("X_t"), separator = separator))
        val current = simulation
        val vanishes = check.driftOfM == Exprs.ZERO ||
            (current != null && check.driftVanishes(HORIZON, current.xRange))
        if (vanishes) {
            Note("The dt term is 0, so M is a local martingale (a true one when E∫(σf_x)² ds < ∞).")
        } else {
            val g = Exprs.add(Parser.parse(compensator), check.requiredCompensator)
            Note("The dt term is not 0. Adding ∫ g ds with g(t, x) = ${check.requiredCompensator.format()} removes it.")
            TextButton(onClick = { compensator = g.format() }) { Text("Use g(t, x) = ${g.format()}") }
        }

        if (current == null) {
            Text("Simulating…", Modifier.padding(24.dp))
        } else {
            MartingaleChart(current, playback, colors)
        }
    }

    PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })

    SectionTitle("Examples from the course")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (e in martingaleExamples) {
            val selected = e.drift == drift && e.diffusion == diffusion && e.f == f && e.x0 == x0Text
            FilterChip(
                selected = selected,
                onClick = {
                    drift = e.drift; diffusion = e.diffusion; f = e.f; compensator = e.compensator; x0Text = e.x0
                    playback.restart()
                },
                label = { Text(e.label) },
            )
        }
    }
    SectionTitle("dX = μ dt + σ dB")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormulaField("μ(t, x)", drift, { drift = it }, Modifier.weight(1f))
        FormulaField("σ(t, x)", diffusion, { diffusion = it }, Modifier.weight(1f))
        FormulaField("X(0)", x0Text, { x0Text = it }, Modifier.weight(0.6f))
    }
    SectionTitle("M(t) = f(t, X) + ∫ g(s, X) ds")
    FormulaField("f(t, x)", f, { f = it }, Modifier.fillMaxWidth())
    FormulaField("g(t, x)", compensator, { compensator = it }, Modifier.fillMaxWidth())
}

@Composable
private fun MartingaleChart(run: MartingaleRun, playback: Playback, colors: ChartColors) {
    val grid = run.grid
    val start = DoubleArray(grid.steps + 1) { run.mean[0] }
    val lower = DoubleArray(grid.steps + 1) { run.mean[it] - 2 * run.standardError[it] }
    val upper = DoubleArray(grid.steps + 1) { run.mean[it] + 2 * run.standardError[it] }
    val curves = buildList {
        for (path in run.keptPaths) add(Curve(grid, path, colors.path.copy(alpha = 0.25f), width = 1.dp))
        add(Curve(grid, start, colors.mean, width = 1.5.dp, dashed = true))
        add(Curve(grid, run.mean, colors.highlight, width = 2.5.dp, head = true))
    }
    val band = Band(grid, lower, upper, colors.highlight.copy(alpha = 0.22f))
    // The kept paths can wander far; fit the axes to their middle 90% and to the mean's band.
    val fit = remember(run) {
        val values = run.keptPaths.flatMap { it.asList() }.filter { it.isFinite() }.sorted().toDoubleArray()
        val mean = valueRange(listOf(curves.last()), listOf(band))
        val lo = if (values.isEmpty()) mean.start else minOf(mean.start, quantileOfSorted(values, 0.05))
        val hi = if (values.isEmpty()) mean.endInclusive else maxOf(mean.endInclusive, quantileOfSorted(values, 0.95))
        Viewport.fit(0.0..grid.horizon, lo..hi)
    }
    var viewport by remember(run) { mutableStateOf(fit) }
    CurveChart(
        horizon = grid.horizon,
        curves = curves,
        bands = listOf(band),
        revealedTime = { playback.revealed / PLAYBACK_STEPS * grid.horizon },
        viewport = viewport,
        onViewportChange = { viewport = it },
        onResetViewport = { viewport = fit },
    )
    LegendRows(
        listOf(
            colors.highlight to "E[M(t)] over ${run.paths} paths, ± 2 standard errors",
            colors.mean to "M(0)",
            colors.path to "${run.keptPaths.size} of the paths",
        ),
    )
    val end = grid.steps
    val z = run.zScore(end)
    Note(
        "E[M(1)] = ${fmt(run.mean[end])} ± ${fmt(run.standardError[end])}, M(0) = ${fmt(run.mean[0])}: " +
            "${fmt(abs(z), 1)} standard errors apart. " +
            if (abs(z) < 3) "Consistent with a martingale." else "Too far for a martingale.",
    )
}

// ---------------------------------------------------------------------------------------------
// Covariation: ⟨U, V⟩ for U = f(t, X) and V = g(t, Y) driven by one Brownian path.

private class CovariationExample(
    val label: String,
    val xDrift: String,
    val xDiffusion: String,
    val x0: String,
    val f: String,
    val yDrift: String,
    val yDiffusion: String,
    val y0: String,
    val g: String,
    /** E⟨U, V⟩(t) in closed form, or "" when the course leaves it open. */
    val expected: String,
)

private val covariationExamples = listOf(
    CovariationExample("⟨B, B⟩ = t", "0", "1", "0", "x", "0", "1", "0", "x", "t"),
    CovariationExample("⟨B⁴, B²⟩", "0", "1", "0", "x^4", "0", "1", "0", "x^2", "8*t^3"),
    CovariationExample("⟨sin X⟩, X Ornstein–Uhlenbeck", "-x", "0.5", "1", "sin(x)", "-x", "0.5", "1", "sin(x)", ""),
    CovariationExample("⟨B³, Y⟩, dY = cos²Y dt + dB", "0", "1", "0", "x^3", "cos(x)^2", "1", "0", "x", "1.5*t^2"),
    CovariationExample("⟨B, t⟩ = 0", "0", "1", "0", "x", "0", "1", "0", "t", "0"),
)

private const val COVARIATION_PATHS = 2000
private const val COVARIATION_STEPS = 256

@Composable
private fun CovariationLab() {
    val colors = LocalChartColors.current
    val start = covariationExamples[1]
    var xDrift by remember { mutableStateOf(start.xDrift) }
    var xDiffusion by remember { mutableStateOf(start.xDiffusion) }
    var x0Text by remember { mutableStateOf(start.x0) }
    var f by remember { mutableStateOf(start.f) }
    var yDrift by remember { mutableStateOf(start.yDrift) }
    var yDiffusion by remember { mutableStateOf(start.yDiffusion) }
    var y0Text by remember { mutableStateOf(start.y0) }
    var g by remember { mutableStateOf(start.g) }
    var expectedText by remember { mutableStateOf(start.expected) }
    var mesh by remember { mutableIntStateOf(256) }
    var seed by remember { mutableLongStateOf(1L) }
    val playback = rememberPlayback(PLAYBACK_STEPS)
    PlaybackClock(playback, onRunFinished = { seed++ })

    Text("Covariation", style = MaterialTheme.typography.titleMedium)
    MathFormula("\\sum_i \\Delta U_i\\,\\Delta V_i \\;\\to\\; \\langle U, V\\rangle_t = \\int_0^t \\sigma_U \\sigma_V\\,ds")
    Note(
        "U = f(t, X) and V = g(t, Y), where X and Y are driven by the same Brownian path. On one path Σ ΔU·ΔV " +
            "converges to ∫σ_U σ_V ds (dashed), with σ_U and σ_V the dB terms of Itô's formula. Averaged over " +
            "$COVARIATION_PATHS paths it gives E⟨U, V⟩(t), which you can compare with a closed form.",
    )
    Spacer(Modifier.height(8.dp))

    val setup = remember(xDrift, xDiffusion, x0Text, f, yDrift, yDiffusion, y0Text, g) {
        runCatching { CovariationSetup(xDrift, xDiffusion, parseNumber(x0Text), f, yDrift, yDiffusion, parseNumber(y0Text), g) }
    }
    val expectedFormula = remember(expectedText) {
        if (expectedText.isBlank()) null else runCatching { Evaluator.compile(Parser.parse(expectedText)) }
    }
    var expected by remember { mutableStateOf<Pair<DoubleArray, DoubleArray>?>(null) }
    LaunchedEffect(setup) {
        expected = null
        val s = setup.getOrNull() ?: return@LaunchedEffect
        expected = s.expectedLimit(TimeGrid(HORIZON, COVARIATION_STEPS), COVARIATION_PATHS)
    }

    setup.onFailure { ErrorText(it.message ?: "Cannot read these formulas") }
    setup.onSuccess { s ->
        MathFormula("\\sigma_U = " + s.uNoise.withState("X_t").tex())
        MathFormula("\\sigma_V = " + s.vNoise.withState("Y_t").tex())

        val path = remember(s, seed) {
            val fine = TimeGrid(HORIZON, FINE_STEPS)
            s.along(fine, ItoSums.brownianPath(fine, SeededRandom(seed)))
        }
        val coarse = TimeGrid(HORIZON, mesh)
        val realized = remember(path, mesh) { path.realized(FINE_STEPS / mesh) }
        val expectedGrid = TimeGrid(HORIZON, COVARIATION_STEPS)
        val closedForm = expectedFormula?.getOrNull()?.let { formula -> DoubleArray(COVARIATION_STEPS + 1) { formula.eval(expectedGrid.time(it), 0.0) } }

        val bands = buildList {
            expected?.let { (mean, se) ->
                add(Band(expectedGrid, DoubleArray(mean.size) { mean[it] - 2 * se[it] }, DoubleArray(mean.size) { mean[it] + 2 * se[it] }, colors.series[1].copy(alpha = 0.2f)))
            }
        }
        val curves = buildList {
            expected?.let { (mean, _) -> add(Curve(expectedGrid, mean, colors.series[1], head = true)) }
            closedForm?.let { add(Curve(expectedGrid, it, colors.series[2], width = 2.dp, dashed = true)) }
            add(Curve(path.grid, path.limit, colors.series[0].copy(alpha = 0.6f), width = 1.5.dp, dashed = true))
            add(Curve(coarse, realized, colors.series[0], head = true))
        }
        val fit = Viewport.fit(0.0..HORIZON, valueRange(curves, bands))
        var viewport by remember(path, expected) { mutableStateOf(fit) }
        CurveChart(
            horizon = HORIZON,
            curves = curves,
            bands = bands,
            revealedTime = { playback.revealed / PLAYBACK_STEPS * HORIZON },
            viewport = viewport,
            onViewportChange = { viewport = it },
            onResetViewport = { viewport = fit },
        )
        LegendRows(
            buildList {
                add(colors.series[0] to "Σ ΔU·ΔV on $mesh steps of this path; dashed: its limit")
                add(colors.series[1] to "E⟨U, V⟩(t) over $COVARIATION_PATHS paths, ± 2 standard errors")
                if (closedForm != null) add(colors.series[2] to "your closed form for E⟨U, V⟩(t)")
            },
        )
        val readout = buildString {
            append("At t = 1: Σ ΔU·ΔV = ${fmt(realized.last())}, its limit on this path ${fmt(path.limit.last())}")
            expected?.let { (mean, se) -> append(", E⟨U, V⟩(1) ≈ ${fmt(mean.last())} ± ${fmt(se.last())}") }
            closedForm?.let { append(", closed form ${fmt(it.last())}") }
            append(".")
        }
        Note(readout)
        if (expected == null) Note("Averaging over $COVARIATION_PATHS paths…")
    }
    expectedFormula?.exceptionOrNull()?.let { ErrorText("Closed form: ${it.message}") }

    PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })

    SectionTitle("Mesh")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in listOf(16, 64, 256, 1024, 4096)) {
            FilterChip(selected = n == mesh, onClick = { mesh = n }, label = { Text("n = $n") })
        }
    }
    SectionTitle("Examples from the course")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (e in covariationExamples) {
            val selected = e.xDrift == xDrift && e.xDiffusion == xDiffusion && e.x0 == x0Text && e.f == f &&
                e.yDrift == yDrift && e.yDiffusion == yDiffusion && e.y0 == y0Text && e.g == g
            FilterChip(
                selected = selected,
                onClick = {
                    xDrift = e.xDrift; xDiffusion = e.xDiffusion; x0Text = e.x0; f = e.f
                    yDrift = e.yDrift; yDiffusion = e.yDiffusion; y0Text = e.y0; g = e.g
                    expectedText = e.expected
                    playback.restart()
                },
                label = { Text(e.label) },
            )
        }
    }
    SectionTitle("dX = μ dt + σ dB and U = f(t, X)")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormulaField("μ(t, x)", xDrift, { xDrift = it }, Modifier.weight(1f))
        FormulaField("σ(t, x)", xDiffusion, { xDiffusion = it }, Modifier.weight(1f))
        FormulaField("X(0)", x0Text, { x0Text = it }, Modifier.weight(0.6f))
    }
    FormulaField("f(t, x)", f, { f = it }, Modifier.fillMaxWidth())
    SectionTitle("dY = μ dt + σ dB and V = g(t, Y), same B")
    Note("Write Y as x in these formulas too.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormulaField("μ(t, x)", yDrift, { yDrift = it }, Modifier.weight(1f))
        FormulaField("σ(t, x)", yDiffusion, { yDiffusion = it }, Modifier.weight(1f))
        FormulaField("Y(0)", y0Text, { y0Text = it }, Modifier.weight(0.6f))
    }
    FormulaField("g(t, x)", g, { g = it }, Modifier.fillMaxWidth())
    SectionTitle("Closed form to compare")
    FormulaField("E⟨U, V⟩(t), optional", expectedText, { expectedText = it }, Modifier.fillMaxWidth())
}

// ---------------------------------------------------------------------------------------------
// Monte Carlo: E[g(X(T))] as the paths come in, plain against antithetic.

private val payoffs = listOf(
    "X(T)" to "x",
    "X(T)²" to "x^2",
    "call, K = 100" to "max(x - 100, 0)",
    "put, K = 100" to "max(100 - x, 0)",
    "digital, K = 100" to "step(x - 100)",
)

private const val MC_PATHS = 20_000
private const val MC_STEPS = 200

private class MonteCarloLabResult(
    val plain: RunningEstimate,
    val antithetic: RunningEstimate,
    /** ∫ g(x) p(T, x) dx when the model has a density, else null. */
    val exact: Double?,
)

private suspend fun runMonteCarloLab(model: PresetModel, payoff: CompiledExpr, seed: Long): MonteCarloLabResult {
    val process = model.create()
    val grid = TimeGrid(HORIZON, MC_STEPS)
    val plain = MonteCarlo.run(process, MonteCarloSettings(grid, MC_PATHS, seed, keepPaths = 0))
    val antithetic = MonteCarlo.run(process, MonteCarloSettings(grid, MC_PATHS, seed, antithetic = true, keepPaths = 0))
    fun g(values: DoubleArray) = DoubleArray(values.size) { payoff.eval(HORIZON, values[it]) }

    // The random walk's "density" is only its Brownian limit, so it gets no exact value.
    val pdf = if (model.id == ModelCatalog.randomWalk.id) null else process.marginalDensity()
    val exact = pdf?.let { p ->
        val lo = plain.terminalValues.min()
        val hi = plain.terminalValues.max()
        val span = maxOf(hi - lo, 1e-6)
        Quadrature.simpson(lo - span, hi + span) { x -> payoff.eval(HORIZON, x) * p(HORIZON, x) }.takeIf { it.isFinite() }
    }
    return MonteCarloLabResult(
        plain = RunningEstimate.of(g(plain.terminalValues)),
        antithetic = RunningEstimate.of(g(antithetic.terminalValues), antithetic = true),
        exact = exact,
    )
}

@Composable
private fun MonteCarloLab() {
    val colors = LocalChartColors.current
    var model by remember { mutableStateOf(ModelCatalog.geometricBrownianMotion) }
    var payoffText by remember { mutableStateOf("max(x - 100, 0)") }
    var seed by remember { mutableLongStateOf(1L) }
    val playback = rememberPlayback(PLAYBACK_STEPS)

    Text("Monte Carlo", style = MaterialTheme.typography.titleMedium)
    MathFormula("m_N = \\frac{1}{N}\\sum_{k=1}^{N} g\\left(X_T^{(k)}\\right) \\;\\to\\; \\mathrm{E}\\left[g(X_T)\\right],\\quad m_N \\pm 1${separator}96\\,\\frac{s_N}{\\sqrt{N}}")
    Note(
        "E[g(X(T))] at T = 1 estimated from more and more paths, with a 95% confidence band. The error falls like " +
            "1/√N, so four times the paths only halve it. Antithetic variates pair every path with its mirror image " +
            "(Z → −Z), which often buys the same accuracy for far fewer paths.",
    )
    Spacer(Modifier.height(8.dp))

    val payoff = remember(payoffText) { runCatching { Evaluator.compile(Parser.parse(payoffText)) } }
    // As in the martingale lab: a new seed keeps the last estimate on screen, a new question clears it.
    var result by remember(model, payoff) { mutableStateOf<MonteCarloLabResult?>(null) }
    var simulating by remember { mutableStateOf(false) }
    LaunchedEffect(model, payoff, seed) {
        val g = payoff.getOrNull() ?: return@LaunchedEffect
        simulating = true
        try {
            result = runMonteCarloLab(model, g, seed)
            playback.restart()
        } finally {
            simulating = false
        }
    }
    PlaybackClock(playback, onRunFinished = { if (!simulating) seed++ })

    payoff.onFailure { ErrorText(it.message ?: "Cannot read this formula") }
    val current = result
    if (current == null && payoff.isSuccess) Text("Simulating ${2 * MC_PATHS} paths…", Modifier.padding(24.dp))
    current?.let { r ->
        val grid = TimeGrid(MC_PATHS.toDouble(), r.plain.paths.size - 1)
        fun band(e: RunningEstimate, color: Color) = Band(
            grid,
            DoubleArray(e.mean.size) { e.mean[it] - Estimate.Z_95 * e.standardError[it] },
            DoubleArray(e.mean.size) { e.mean[it] + Estimate.Z_95 * e.standardError[it] },
            color.copy(alpha = 0.18f),
        )
        val bands = listOf(band(r.plain, colors.series[0]), band(r.antithetic, colors.series[2]))
        val curves = buildList {
            r.exact?.let { add(Curve(grid, DoubleArray(grid.steps + 1) { _ -> it }, colors.highlight, width = 2.dp, dashed = true)) }
            add(Curve(grid, r.plain.mean, colors.series[0], head = true))
            add(Curve(grid, r.antithetic.mean, colors.series[2], head = true))
        }
        // The first few hundred paths have wide bands; fit the axes to the rest.
        val fit = remember(r) {
            val skip = grid.steps / 10
            fun tail(values: DoubleArray) = values.copyOfRange(skip, values.size)
            val trimmed = bands.map { Band(grid, tail(it.lower), tail(it.upper), it.color) }
            Viewport.fit(0.0..grid.horizon, valueRange(curves.map { Curve(grid, tail(it.values), it.color) }, trimmed))
        }
        var viewport by remember(r) { mutableStateOf(fit) }
        CurveChart(
            horizon = grid.horizon,
            curves = curves,
            bands = bands,
            revealedTime = { playback.revealed / PLAYBACK_STEPS * grid.horizon },
            viewport = viewport,
            onViewportChange = { viewport = it },
            onResetViewport = { viewport = fit },
        )
        Note("Horizontal axis: number of paths N")
        LegendRows(
            buildList {
                add(colors.series[0] to "plain Monte Carlo, 95% band")
                add(colors.series[2] to "antithetic variates, 95% band")
                if (r.exact != null) add(colors.highlight to "exact, from the model's density")
            },
        )
        val plain = r.plain.last
        val anti = r.antithetic.last
        Note("Plain: ${fmt(plain.mean)} ± ${fmt(plain.standardError)}, 95% CI [${fmt(plain.ci95Low)}, ${fmt(plain.ci95High)}]")
        Note("Antithetic: ${fmt(anti.mean)} ± ${fmt(anti.standardError)}, 95% CI [${fmt(anti.ci95Low)}, ${fmt(anti.ci95High)}]")
        if (anti.standardError > 0) {
            val factor = (plain.standardError / anti.standardError).let { it * it }
            Note("Antithetic sampling did the work of ${fmt(factor, 1)}× as many plain paths here.")
        }
        Note(r.exact?.let { "Exact: ${fmt(it)}" } ?: "No closed-form density for this model, so no exact value.")
    }

    PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })

    SectionTitle("Model (default parameters)")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in ModelCatalog.all) {
            FilterChip(selected = m.id == model.id, onClick = { model = m; playback.restart() }, label = { Text(m.name) })
        }
    }
    SectionTitle("g(X(T))")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, formula) in payoffs) {
            FilterChip(selected = formula == payoffText, onClick = { payoffText = formula }, label = { Text(label) })
        }
    }
    FormulaField("g(x)", payoffText, { payoffText = it }, Modifier.fillMaxWidth())
}
