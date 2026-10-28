package sde

import kotlinx.coroutines.test.runTest
import sde.analytic.BlackScholes
import sde.analytic.Normal
import sde.analytic.Quadrature
import sde.catalog.ModelCatalog
import sde.model.marginalDensity
import sde.expr.format
import sde.ito.CovariationSetup
import sde.ito.ItoSums
import sde.montecarlo.Estimate
import sde.montecarlo.RunningEstimate
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CovariationTest {
    private fun brownian(f: String, g: String) = CovariationSetup("0", "1", 0.0, f, "0", "1", 0.0, g)

    @Test
    fun realizedCovariationConvergesToItsLimitOnOnePath() {
        val grid = TimeGrid(1.0, 65_536)
        val path = brownian("x^4", "x^2").along(grid, ItoSums.brownianPath(grid, SeededRandom(3)))
        val limit = path.limit.last()
        assertEquals(limit, path.realized().last(), 0.05 * limit + 0.01)
    }

    @Test
    fun expectedCovariationOfBFourAndBSquaredIsEightTCubed() = runTest {
        // Exercise 5.
        val setup = brownian("x^4", "x^2")
        assertEquals("4*x^3", setup.uNoise.format())
        val (mean, se) = setup.expectedLimit(TimeGrid(1.0, 200), paths = 4000, seed = 9)
        assertTrue(abs(mean.last() - 8.0) < 4 * se.last(), "E⟨B⁴, B²⟩(1) = ${mean.last()} ± ${se.last()}")
        assertTrue(abs(mean[100] - 1.0) < 4 * se[100] + 0.05, "E⟨B⁴, B²⟩(½) = ${mean[100]}")
    }

    @Test
    fun expectedCovariationOfBCubedAndYIsThreeHalvesTSquared() = runTest {
        // Exercise 7: Y has dY = cos²Y dt + dB, so d⟨B³, Y⟩ = 3B² dt and E⟨B³, Y⟩ = 3t²/2.
        val setup = CovariationSetup("0", "1", 0.0, "x^3", "cos(x)^2", "1", 0.0, "x")
        val (mean, se) = setup.expectedLimit(TimeGrid(1.0, 200), paths = 4000, seed = 4)
        assertTrue(abs(mean.last() - 1.5) < 4 * se.last(), "E⟨B³, Y⟩(1) = ${mean.last()} ± ${se.last()}")
    }

    @Test
    fun quadraticVariationOfAFunctionOfAnSde() {
        // Exercise 6: ⟨f(X)⟩ = ∫ f′(X)² d⟨X⟩, here for f = sin and an Ornstein–Uhlenbeck X.
        val setup = CovariationSetup("-x", "0.5", 1.0, "sin(x)", "-x", "0.5", 1.0, "sin(x)")
        val grid = TimeGrid(1.0, 65_536)
        val path = setup.along(grid, ItoSums.brownianPath(grid, SeededRandom(8)))
        assertEquals(path.limit.last(), path.realized().last(), 0.05 * path.limit.last())
    }

    @Test
    fun runningEstimateEndsAtTheFullEstimate() {
        val random = SeededRandom(2)
        val values = DoubleArray(1000) { random.nextGaussian() }
        val running = RunningEstimate.of(values, points = 50)
        val full = Estimate.of(values)
        assertEquals(1000, running.paths.last())
        assertEquals(full.mean, running.last.mean, 1e-12)
        assertEquals(full.standardError, running.last.standardError, 1e-12)
        assertTrue(running.paths.toList().zipWithNext().all { (a, b) -> a <= b })

        val paired = RunningEstimate.of(values, antithetic = true, points = 10)
        assertEquals(1000, paired.paths.last())
        assertContentEquals(IntArray(11) { maxOf(2, 50 * it) * 2 }, paired.paths)
    }

    @Test
    fun exactExpectationsFromADensity() {
        assertEquals(1.0, Quadrature.simpson(-10.0, 10.0) { Normal.pdf(it, 0.0, 1.0) }, 1e-10)
        // The Monte Carlo lab's "exact" call value: ∫ max(x − K, 0) p(1, x) dx under GBM is the undiscounted Black–Scholes price.
        val pdf = ModelCatalog.geometricBrownianMotion.create().marginalDensity()!!
        val integral = Quadrature.simpson(1.0, 400.0) { max(it - 100.0, 0.0) * pdf(1.0, it) }
        assertEquals(BlackScholes.call(100.0, 100.0, 0.05, 0.2, 1.0) * exp(0.05), integral, 1e-4)
    }
}
