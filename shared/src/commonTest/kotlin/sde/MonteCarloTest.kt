package sde

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import sde.analytic.BlackScholes
import sde.catalog.ModelCatalog
import sde.model.Exact
import sde.model.GeometricBrownianMotion
import sde.model.ScalarProcess
import sde.montecarlo.MonteCarlo
import sde.montecarlo.MonteCarloSettings
import sde.montecarlo.TimeGrid
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonteCarloTest {
    private val gbm = ScalarProcess(GeometricBrownianMotion(mu = 0.05, sigma = 0.2), 100.0, Exact)

    @Test
    fun aSeedGivesTheSameResultOnAnyNumberOfThreads() = runTest {
        val settings = MonteCarloSettings(TimeGrid(1.0, 50), paths = 3000, seed = 5, keepPaths = 10)
        val parallel = MonteCarlo.run(gbm, settings, Dispatchers.Default)
        val single = MonteCarlo.run(gbm, settings, StandardTestDispatcher(testScheduler))
        assertContentEquals(parallel.terminalValues, single.terminalValues)
        assertContentEquals(parallel.meanPath, single.meanPath)
        assertEquals(10, parallel.keptPaths.size)
        assertEquals(parallel.keptPaths[3].values[0][50], parallel.terminalValues[3])
    }

    @Test
    fun europeanCallConvergesToBlackScholes() = runTest {
        val r = 0.05
        val strike = 105.0
        val result = MonteCarlo.run(gbm, MonteCarloSettings(TimeGrid(1.0, 1), paths = 200_000, seed = 13))
        val price = result.expectation { exp(-r) * max(it - strike, 0.0) }
        val exact = BlackScholes.call(100.0, strike, r, 0.2, 1.0)
        assertTrue(abs(price.zScore(exact)) < 4, "MC ${price.mean} ± ${price.standardError}, Black–Scholes $exact")
        assertTrue(exact in price.ci95Low - 2 * price.standardError..price.ci95High + 2 * price.standardError)
    }

    @Test
    fun antitheticVariatesShrinkTheErrorAndStayUnbiased() = runTest {
        val grid = TimeGrid(1.0, 1)
        val plain = MonteCarlo.run(gbm, MonteCarloSettings(grid, paths = 100_000, seed = 21))
        val anti = MonteCarlo.run(gbm, MonteCarloSettings(grid, paths = 100_000, seed = 21, antithetic = true))
        val call = { s: Double -> exp(-0.05) * max(s - 100.0, 0.0) }
        val plainPrice = plain.expectation(call)
        val antiPrice = anti.expectation(call)
        val exact = BlackScholes.call(100.0, 100.0, 0.05, 0.2, 1.0)
        assertTrue(abs(antiPrice.zScore(exact)) < 4, "antithetic ${antiPrice.mean}, exact $exact")
        assertTrue(antiPrice.standardError < 0.8 * plainPrice.standardError, "${antiPrice.standardError} vs ${plainPrice.standardError}")
        // Mirror pairs are adjacent: their log returns are symmetric about (μ − σ²/2)T.
        val logSum = ln(anti.terminalValues[0] / 100.0) + ln(anti.terminalValues[1] / 100.0)
        assertEquals(2 * (0.05 - 0.02), logSum, 1e-12)
    }

    @Test
    fun quantilesAndHistogramDescribeTheTerminalDistribution() = runTest {
        val result = MonteCarlo.run(gbm, MonteCarloSettings(TimeGrid(1.0, 1), paths = 100_000, seed = 3))
        // The median of S0·exp((μ − σ²/2)T + σW) is S0·exp(μ − σ²/2).
        val median = 100.0 * exp(0.05 - 0.02)
        assertEquals(median, result.terminalQuantile(0.5), 0.5)
        val histogram = result.terminalHistogram(40)
        val area = (0 until histogram.bins).sumOf { histogram.density(it) * histogram.binWidth }
        assertEquals(1.0, area, 1e-9)
    }

    @Test
    fun everyCatalogModelBuildsAndRuns() = runTest {
        for (model in ModelCatalog.all) {
            val process = model.create()
            val result = MonteCarlo.run(process, MonteCarloSettings(TimeGrid(1.0, 100), paths = 200, keepPaths = 5))
            assertTrue(result.terminalValues.all { it.isFinite() }, "${model.name} produced a non-finite value")
            assertEquals(5, result.keptPaths.size)
            assertEquals(process.components.size, result.keptPaths[0].values.size)
        }
    }
}
