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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sde.catalog.ModelCatalog
import sde.catalog.Parameter
import sde.catalog.PresetModel
import sde.chart.Ticks
import sde.chart.Viewport
import sde.expr.format
import sde.live.Ensemble
import sde.model.CustomSde
import sde.model.EulerMaruyama
import sde.model.Exact
import sde.model.ExactTransition
import sde.model.Milstein
import sde.model.ScalarProcess
import sde.model.Scheme
import sde.model.StochasticProcess
import sde.model.marginalDensity
import sde.montecarlo.TimeGrid
import java.text.DecimalFormatSymbols

private const val ENSEMBLE_PATHS = 1000
private const val STEPS = 500

/** Pick a model, set its parameters, and watch its paths unfold. */
@Composable
fun SimulateScreen() {
    var model by remember { mutableStateOf(ModelCatalog.geometricBrownianMotion) }
    val values = remember(model) { mutableStateMapOf<String, Double>().apply { model.parameters.forEach { put(it.key, it.default) } } }
    var scheme by remember(model) { mutableStateOf<Scheme>(EulerMaruyama) }
    var horizon by remember(model) { mutableStateOf(defaultHorizon(model)) }
    var component by remember(model) { mutableIntStateOf(0) }
    var shownPaths by remember { mutableIntStateOf(40) }
    var seed by remember { mutableLongStateOf(1L) }
    var options by remember { mutableStateOf(ChartOptions(shownPaths = 40)) }

    // "Your own SDE": typed formulas, with a slider for every parameter name they use.
    var custom by remember { mutableStateOf(false) }
    var drift by remember { mutableStateOf("kappa*(m - x)") }
    var diffusion by remember { mutableStateOf("sigma*sqrt(abs(x))") }
    var x0Text by remember { mutableStateOf("1") }
    val customValues = remember { mutableStateMapOf<String, Double>() }
    val customNames = remember(drift, diffusion) {
        runCatching { CustomSde.parameterNames(drift, diffusion) }.getOrDefault(emptyList())
    }
    val customParameters = customNames.associateWith { customValues[it] ?: 1.0 }
    val customAttempt = remember(custom, drift, diffusion, x0Text, customParameters, scheme) {
        if (!custom) null else runCatching {
            ScalarProcess(CustomSde(drift, diffusion, customParameters), parseNumber(x0Text), scheme)
        }
    }
    // While a formula is half typed, the chart keeps showing the last one that worked.
    var lastCustom by remember { mutableStateOf<StochasticProcess?>(null) }
    LaunchedEffect(customAttempt) { customAttempt?.onSuccess { lastCustom = it } }

    val presetProcess = remember(model, values.toMap(), scheme) { model.create(values.toMap(), scheme) }
    val process = if (custom) lastCustom ?: presetProcess else presetProcess
    // The bridge must end exactly at its own T.
    val effectiveHorizon = if (!custom && model.id == ModelCatalog.brownianBridge.id) values["T"] ?: 1.0 else horizon
    val grid = remember(effectiveHorizon) { TimeGrid(effectiveHorizon, STEPS) }

    var ensemble by remember { mutableStateOf<Ensemble?>(null) }
    var viewport by remember { mutableStateOf(Viewport(0.0, 1.0, -1.0, 1.0)) }
    var autoFit by remember(model, component, custom) { mutableStateOf(true) }
    val playback = rememberPlayback(STEPS)

    var simulating by remember { mutableStateOf(false) }
    LaunchedEffect(process, grid, seed, component) {
        simulating = true
        try {
            val fresh = Ensemble.simulate(process, grid, ENSEMBLE_PATHS, seed, component)
            ensemble = fresh
            if (autoFit || viewport.tMax > grid.horizon) {
                viewport = Viewport.fit(0.0..grid.horizon, fresh.bandRange())
            }
        } finally {
            simulating = false
        }
    }
    // A slow phone may still be simulating when a run ends; then it replays the paths it has.
    PlaybackClock(playback, onRunFinished = { if (!simulating) seed++ })

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in ModelCatalog.all) {
                FilterChip(selected = !custom && m.id == model.id, onClick = { model = m; custom = false; playback.restart() }, label = { Text(m.name) })
            }
            FilterChip(selected = custom, onClick = { custom = true; scheme = EulerMaruyama; playback.restart() }, label = { Text("Your own SDE") })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (custom) "dX = μ(t, X) dt + σ(t, X) dB with your own formulas. Every other name in them becomes a parameter with a slider." else model.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        val current = ensemble
        if (current == null) {
            Text("Simulating…", Modifier.padding(32.dp))
        } else {
            LiveChart(
                ensemble = current,
                revealed = { playback.revealed },
                viewport = viewport,
                onViewportChange = { viewport = it; autoFit = false },
                onResetViewport = { viewport = Viewport.fit(0.0..current.grid.horizon, current.bandRange()); autoFit = true },
                options = options.copy(shownPaths = shownPaths),
                pdf = process.marginalDensity(component),
            )
            Legend(current, playback, process.components.getOrElse(component) { "X" })
        }

        PlaybackControls(playback, onNewPaths = { seed++; playback.restart() })

        if (process.components.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                process.components.forEachIndexed { index, name ->
                    FilterChip(selected = index == component, onClick = { component = index }, label = { Text("Show $name") })
                }
            }
        }

        if (custom) {
            SectionTitle("Your SDE")
            FormulaField("Drift μ(t, x)", drift, { drift = it }, Modifier.fillMaxWidth())
            FormulaField("Diffusion σ(t, x)", diffusion, { diffusion = it }, Modifier.fillMaxWidth())
            FormulaField("X(0)", x0Text, { x0Text = it }, Modifier.fillMaxWidth())
            customAttempt?.exceptionOrNull()?.let { ErrorText(it.message ?: "Cannot read these formulas") }
            (lastCustom as? ScalarProcess)?.let { p ->
                (p.sde as? CustomSde)?.let { Note("Milstein uses ∂σ/∂x = ${it.diffusionSlopeExpr.format()}") }
            }
        }

        SectionTitle("Parameters")
        if (custom) {
            for (name in customNames) {
                LabeledSlider(name, customParameters.getValue(name), -5.0..5.0) { customValues[name] = it }
            }
        } else {
            for (parameter in model.parameters) {
                ParameterSlider(parameter, values[parameter.key] ?: parameter.default) { values[parameter.key] = it }
            }
        }
        if (custom || model.id != ModelCatalog.brownianBridge.id) {
            LabeledSlider("Horizon T", horizon, 0.25..10.0) { horizon = it }
        }

        if (custom || model.supportsSchemeChoice) {
            SectionTitle("Scheme")
            val schemes = buildList<Scheme> {
                add(EulerMaruyama)
                add(Milstein)
                if (!custom && (process as? ScalarProcess)?.sde is ExactTransition) add(Exact)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in schemes) FilterChip(selected = s == scheme, onClick = { scheme = s }, label = { Text(s.label) })
            }
        }

        SectionTitle("Display")
        LabeledSlider("Paths drawn", shownPaths.toDouble(), 1.0..200.0, decimals = 0) { shownPaths = it.toInt() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = options.showBand, onClick = { options = options.copy(showBand = !options.showBand) }, label = { Text("5–95% band") })
            FilterChip(selected = options.showMean, onClick = { options = options.copy(showMean = !options.showMean) }, label = { Text("Mean") })
            FilterChip(selected = options.showHistogram, onClick = { options = options.copy(showHistogram = !options.showHistogram) }, label = { Text("Histogram") })
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun defaultHorizon(model: PresetModel) = when (model.id) {
    ModelCatalog.ornsteinUhlenbeck.id, ModelCatalog.coxIngersollRoss.id, ModelCatalog.heston.id -> 3.0
    else -> 1.0
}

@Composable
private fun Legend(ensemble: Ensemble, playback: Playback, name: String) {
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val step = playback.revealed.toInt().coerceIn(0, ensemble.grid.steps)
    val t = ensemble.grid.time(step)
    val mean = ensemble.mean[step].toDouble()
    Text(
        "t = ${Ticks.formatFixed(t, 2, separator)}    mean of $name = ${Ticks.formatFixed(mean, 3, separator)}    " +
            "${ensemble.paths} paths, ${ensemble.grid.steps} steps",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
fun PlaybackControls(playback: Playback, onNewPaths: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            if (playback.finished) playback.restart() else playback.playing = !playback.playing
        }) { Text(if (playback.playing) "Pause" else "Play") }
        OutlinedButton(onClick = onNewPaths) { Text("New paths") }
        FilterChip(selected = playback.loop, onClick = { playback.loop = !playback.loop }, label = { Text("Loop") })
    }
    Slider(
        value = if (playback.steps > 0) playback.revealed / playback.steps else 0f,
        onValueChange = { playback.scrubTo(it) },
        modifier = Modifier.fillMaxWidth(),
    )
    LabeledSlider("Seconds per run", playback.secondsPerRun.toDouble(), 2.0..30.0, decimals = 0) {
        playback.secondsPerRun = it.toFloat()
    }
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun ParameterSlider(parameter: Parameter, value: Double, onChange: (Double) -> Unit) {
    LabeledSlider("${parameter.description} (${parameter.key})", value, parameter.min..parameter.max, onChange = onChange)
}

@Composable
fun LabeledSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    decimals: Int = 2,
    onChange: (Double) -> Unit,
) {
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(Ticks.formatFixed(value, decimals, separator), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(4.dp))
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toDouble()) },
        valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        modifier = Modifier.fillMaxWidth().height(32.dp),
    )
}
