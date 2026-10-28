package app.sde.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sde.chart.Ticks
import sde.chart.Viewport
import sde.expr.Derivative
import sde.expr.Evaluator
import sde.expr.Exprs
import sde.expr.Parser
import sde.expr.format
import sde.ito.EvaluationPoint
import sde.ito.ItoFormula
import sde.ito.ItoSums
import sde.ito.MartingaleCheck
import sde.ito.MartingaleRun
import sde.ito.alongPath
import sde.model.CustomSde
import sde.model.solveAlong
import sde.montecarlo.TimeGrid
import sde.montecarlo.quantileOfSorted
import sde.random.SeededRandom
import java.text.DecimalFormatSymbols
import kotlin.math.abs

private val labs = listOf("Integrals", "Variation", "Martingales")

/** Every lab runs on [0, 1] and reveals its curves over [PLAYBACK_STEPS] animation steps. */
private const val HORIZON = 1.0
private const val PLAYBACK_STEPS = 1000

/** The finest mesh: one path is simulated on it, and coarser meshes see every k-th point of it. */
private const val FINE_STEPS = 4096

/** Hands-on versions of the course's Itô calculus: integrals, variations and martingales. */
@Composable
fun LabsScreen() {
    var lab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labs.forEachIndexed { index, name ->
                FilterChip(selected = index == lab, onClick = { lab = index }, label = { Text(name) })
            }
        }
        Spacer(Modifier.height(8.dp))
        when (lab) {
            0 -> IntegralsLab()
            1 -> VariationLab()
            else -> MartingaleLab()
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Integrals: left, mid and right point sums along one path.

private class IntegratorPreset(val label: String, val drift: String, val diffusion: String, val x0: String)

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
    PlaybackClock(playback, onRunFinished = { seed++ })

    Text("Martingale checker", style = MaterialTheme.typography.titleMedium)
    Note(
        "Is M(t) = f(t, X(t)) + ∫₀ᵗ g(s, X(s)) ds a martingale? Itô's formula gives its dt term symbolically, and " +
            "$MARTINGALE_PATHS simulated paths show whether E[M(t)] really stays at M(0). One path proves nothing.",
    )
    Spacer(Modifier.height(8.dp))

    val setup = remember(drift, diffusion, f, compensator, x0Text) {
        runCatching { MartingaleCheck(drift, diffusion, f, compensator) to parseNumber(x0Text) }
    }
    var simulation by remember { mutableStateOf<MartingaleRun?>(null) }
    LaunchedEffect(setup, seed) {
        simulation = null
        val (check, x0) = setup.getOrNull() ?: return@LaunchedEffect
        simulation = check.simulate(x0, TimeGrid(HORIZON, MARTINGALE_STEPS), MARTINGALE_PATHS, seed)
    }

    setup.onFailure { ErrorText(it.message ?: "Cannot read these formulas") }
    setup.onSuccess { (check, _) ->
        Formula("dM = (${check.driftOfM.format()}) dt + (${check.differential.dB.format()}) dB")
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
// Small shared pieces.

@Composable
private fun FormulaField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun Formula(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
}

/** A short line in each colour with its meaning, one per row. */
@Composable
private fun LegendRows(items: List<Pair<Color, String>>) {
    Column(Modifier.padding(top = 4.dp)) {
        for ((color, label) in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
                    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.5.dp.toPx())
                }
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val separator: Char by lazy { DecimalFormatSymbols.getInstance().decimalSeparator }

private fun fmt(value: Double, decimals: Int = 3): String = Ticks.formatFixed(value, decimals, separator)

/** A number typed with either decimal separator. */
private fun parseNumber(text: String): Double =
    text.trim().replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("'$text' is not a number")
