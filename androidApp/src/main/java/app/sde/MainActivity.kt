package app.sde

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sde.catalog.ModelCatalog
import sde.model.EulerMaruyama
import sde.model.GeometricBrownianMotion
import sde.model.ScalarProcess
import sde.montecarlo.MonteCarlo
import sde.montecarlo.MonteCarloSettings
import sde.montecarlo.TimeGrid

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { EnginePreview() }
            }
        }
    }
}

/** A placeholder screen until the charts arrive in step 2: the model list and an on-device speed test. */
@Composable
private fun EnginePreview() {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("SDE Solver", style = MaterialTheme.typography.headlineMedium)
        Text("Engine preview. Animated charts arrive in step 2.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Button(
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

        Spacer(Modifier.height(24.dp))
        Text("Models in the engine", style = MaterialTheme.typography.titleMedium)
        for (model in ModelCatalog.all) {
            Spacer(Modifier.height(12.dp))
            Text(model.name, style = MaterialTheme.typography.bodyLarge)
            Text(model.summary, style = MaterialTheme.typography.bodySmall)
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
