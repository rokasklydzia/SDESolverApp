package app.sde.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.sde.AppSettings
import app.sde.ThemeMode
import kotlinx.coroutines.launch
import sde.catalog.ModelCatalog
import sde.model.EulerMaruyama
import sde.model.GeometricBrownianMotion
import sde.model.ScalarProcess
import sde.montecarlo.MonteCarlo
import sde.montecarlo.MonteCarloSettings
import sde.montecarlo.TimeGrid

/** What the app is, the appearance setting, the speed test from step 1 and the list of models. */
@Composable
fun AboutScreen(settings: AppSettings) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("SDE Solver", style = MaterialTheme.typography.headlineSmall)
        Note("Version $version")
        Spacer(Modifier.height(8.dp))
        Text(
            "Stochastic differential equations, Itô calculus and Monte Carlo, simulated live on your phone. " +
                "Made as a project for a course in stochastic analysis.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { settings.introSeen = false }) { Text("Show the intro again") }

        SectionTitle("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in ThemeMode.entries) {
                FilterChip(selected = settings.themeMode == mode, onClick = { settings.themeMode = mode }, label = { Text(mode.label) })
            }
        }

        SectionTitle("Speed test")
        Note("10,000 paths of geometric Brownian motion, 1,000 Euler–Maruyama steps each, on all cores.")
        Spacer(Modifier.height(4.dp))
        FilledTonalButton(
            enabled = !running,
            onClick = {
                running = true
                scope.launch {
                    report = runBenchmark()
                    running = false
                }
            },
        ) { Text(if (running) "Running…" else "Run speed test") }
        report?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }

        SectionTitle("Models in the engine")
        for (model in ModelCatalog.all) {
            Spacer(Modifier.height(8.dp))
            Text(model.name, style = MaterialTheme.typography.bodyLarge)
            Note(model.summary)
        }
    }
}

/** 10,000 GBM paths × 1,000 Euler–Maruyama steps, the same workload as the JVM BenchmarkTest. */
private suspend fun runBenchmark(): String {
    val gbm = GeometricBrownianMotion(mu = 0.05, sigma = 0.2)
    val process = ScalarProcess(gbm, 100.0, EulerMaruyama, "S")
    val settings = MonteCarloSettings(TimeGrid(1.0, 1000), paths = 10_000, keepPaths = 0)
    MonteCarlo.run(process, settings.copy(paths = 1_000)) // let the runtime compile the hot loop first
    val result = MonteCarlo.run(process, settings)
    val mean = result.terminalMean()
    val ms = result.elapsed.inWholeMilliseconds
    val stepsPerSecond = settings.paths.toDouble() * settings.grid.steps / result.elapsed.inWholeMicroseconds.coerceAtLeast(1) * 1e6
    return buildString {
        appendLine("10,000 paths × 1,000 steps: $ms ms")
        appendLine("%.1f million steps per second".format(stepsPerSecond / 1e6))
        appendLine("E[S(1)] = %.3f ± %.3f".format(mean.mean, mean.ci95High - mean.mean))
        append("exact   = %.3f".format(gbm.mean(1.0, 100.0)))
    }
}
