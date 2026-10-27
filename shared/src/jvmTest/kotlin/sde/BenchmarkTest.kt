package sde

import kotlinx.coroutines.runBlocking
import sde.model.CustomSde
import sde.model.EulerMaruyama
import sde.model.GeometricBrownianMotion
import sde.model.Heston
import sde.model.ScalarProcess
import sde.model.StochasticProcess
import sde.montecarlo.MonteCarlo
import sde.montecarlo.MonteCarloSettings
import sde.montecarlo.TimeGrid
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How long 10,000 paths × 1,000 steps take. Run with -Psde.showOutput=true to see the numbers;
 * the same workload runs on the phone from the app's benchmark button.
 */
class BenchmarkTest {
    private val settings = MonteCarloSettings(TimeGrid(1.0, 1000), paths = 10_000, keepPaths = 0)

    private fun time(label: String, process: StochasticProcess) = runBlocking {
        MonteCarlo.run(process, settings) // warm up the JIT
        val result = MonteCarlo.run(process, settings)
        val stepsPerSecond = settings.paths * settings.grid.steps / result.elapsed.inWholeMicroseconds.coerceAtLeast(1) * 1e6
        println("$label: ${result.elapsed.inWholeMilliseconds} ms, ${"%.0f".format(stepsPerSecond / 1e6)} million steps/s")
        assertTrue(result.terminalValues.all { it.isFinite() })
    }

    @Test
    fun presetGbm() = time("GBM preset, Euler–Maruyama", ScalarProcess(GeometricBrownianMotion(), 100.0, EulerMaruyama))

    @Test
    fun typedGbm() = time("GBM typed as formulas", ScalarProcess(CustomSde("mu*x", "sigma*x", mapOf("mu" to 0.05, "sigma" to 0.2)), 100.0))

    @Test
    fun heston() = time("Heston", Heston())
}
