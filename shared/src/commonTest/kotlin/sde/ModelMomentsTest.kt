package sde

import kotlinx.coroutines.test.runTest
import sde.model.ArithmeticBrownianMotion
import sde.model.BrownianBridge
import sde.model.CoxIngersollRoss
import sde.model.EulerMaruyama
import sde.model.Exact
import sde.model.GeometricBrownianMotion
import sde.model.Heston
import sde.model.KnownMoments
import sde.model.MertonJumpDiffusion
import sde.model.Milstein
import sde.model.OrnsteinUhlenbeck
import sde.model.ScalarProcess
import sde.model.ScaledRandomWalk
import sde.model.Scheme
import sde.model.StochasticProcess
import sde.montecarlo.MonteCarlo
import sde.montecarlo.MonteCarloSettings
import sde.montecarlo.TimeGrid
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Simulated means and variances against the closed forms. Seeds are fixed, so these are
 * deterministic; the tolerance is in standard errors, so a real bias fails and noise does not.
 */
class ModelMomentsTest {
    private val paths = 40_000

    private suspend fun checkMoments(
        label: String,
        process: StochasticProcess,
        moments: KnownMoments,
        x0: Double,
        grid: TimeGrid,
        /** Allowed discretisation bias, relative to the mean, on top of the statistical error. */
        bias: Double = 0.0,
    ) {
        val result = MonteCarlo.run(process, MonteCarloSettings(grid, paths, seed = 2024))
        for (i in listOf(grid.steps / 4, grid.steps / 2, grid.steps)) {
            val t = grid.time(i)
            val mean = moments.mean(t, x0)
            val variance = moments.variance(t, x0)
            val meanError = abs(result.meanPath[i] - mean)
            val meanTolerance = 4.5 * sqrt(variance / paths) + bias * abs(mean)
            assertTrue(meanError <= meanTolerance, "$label: E[X($t)] = ${result.meanPath[i]}, expected $mean ± $meanTolerance")
            // The sample variance of n draws has a standard error of about σ²·√(2/n) for normal data;
            // heavier tails (GBM, Merton) need more room, hence 6 and the relative margin.
            val varianceTolerance = 6 * variance * sqrt(2.0 / paths) + 0.03 * variance + bias * variance
            assertTrue(
                abs(result.variancePath[i] - variance) <= varianceTolerance,
                "$label: Var[X($t)] = ${result.variancePath[i]}, expected $variance ± $varianceTolerance",
            )
        }
    }

    @Test
    fun brownianMotion() = runTest {
        val sde = ArithmeticBrownianMotion(mu = 0.5, sigma = 1.2)
        checkMoments("ABM", ScalarProcess(sde, 1.0), sde, 1.0, TimeGrid(2.0, 100))
    }

    @Test
    fun geometricBrownianMotionWithEveryScheme() = runTest {
        val sde = GeometricBrownianMotion(mu = 0.1, sigma = 0.3)
        for (scheme in listOf<Scheme>(EulerMaruyama, Milstein, Exact)) {
            checkMoments("GBM ${scheme.label}", ScalarProcess(sde, 100.0, scheme), sde, 100.0, TimeGrid(1.0, 200), bias = 0.002)
        }
    }

    @Test
    fun ornsteinUhlenbeckEulerAndExact() = runTest {
        val sde = OrnsteinUhlenbeck(kappa = 2.0, longRunMean = 1.0, sigma = 0.5)
        checkMoments("OU Euler", ScalarProcess(sde, -1.0), sde, -1.0, TimeGrid(2.0, 400), bias = 0.01)
        checkMoments("OU exact", ScalarProcess(sde, -1.0, Exact), sde, -1.0, TimeGrid(2.0, 20))
    }

    @Test
    fun coxIngersollRossWithFullTruncation() = runTest {
        val sde = CoxIngersollRoss(kappa = 2.0, theta = 0.04, sigma = 0.2)
        assertTrue(sde.fellerConditionHolds)
        checkMoments("CIR Euler", ScalarProcess(sde, 0.03), sde, 0.03, TimeGrid(2.0, 400), bias = 0.01)
        checkMoments("CIR Milstein", ScalarProcess(sde, 0.03, Milstein), sde, 0.03, TimeGrid(2.0, 400), bias = 0.01)
    }

    @Test
    fun brownianBridgeIsPinnedAtTheEnd() = runTest {
        val sde = BrownianBridge(end = 2.0, horizon = 1.0, sigma = 1.0)
        val grid = TimeGrid(1.0, 1000)
        val result = MonteCarlo.run(ScalarProcess(sde, 0.0), MonteCarloSettings(grid, 20_000, seed = 9))
        for (i in listOf(250, 500, 750)) {
            val t = grid.time(i)
            assertTrue(abs(result.meanPath[i] - sde.mean(t, 0.0)) < 0.03, "bridge mean at $t")
            assertTrue(abs(result.variancePath[i] - sde.variance(t, 0.0)) < 0.03 * sde.variance(t, 0.0) + 0.005, "bridge variance at $t")
        }
        // The last Euler step lands on b plus one increment of noise, of variance dt.
        assertTrue(abs(result.meanPath[grid.steps] - 2.0) < 0.01)
        assertTrue(result.variancePath[grid.steps] < 3 * grid.dt)
    }

    @Test
    fun randomWalkHasBrownianMoments() = runTest {
        val walk = ScaledRandomWalk(x0 = 0.0, sigma = 1.5)
        checkMoments("random walk", walk, walk, 0.0, TimeGrid(1.0, 100))
    }

    @Test
    fun mertonJumpDiffusion() = runTest {
        val model = MertonJumpDiffusion(s0 = 100.0, mu = 0.05, sigma = 0.2, lambda = 3.0, jumpMean = -0.1, jumpVol = 0.15)
        checkMoments("Merton", model, model, 100.0, TimeGrid(1.0, 50))
    }

    @Test
    fun hestonPriceMeanIsExactAndVarianceMeanReverts() = runTest {
        val model = Heston()
        val grid = TimeGrid(1.0, 250)
        val prices = MonteCarlo.run(model, MonteCarloSettings(grid, paths, seed = 77, component = 0))
        val price = prices.terminalMean()
        assertTrue(abs(price.zScore(model.meanPrice(1.0))) < 4.5, "E[S(1)] = ${price.mean}, expected ${model.meanPrice(1.0)}")
        val variances = MonteCarlo.run(model, MonteCarloSettings(grid, paths, seed = 77, component = 1))
        val v = variances.terminalMean()
        // Full truncation biases E[v] slightly upwards; 2% covers it at 250 steps a year.
        assertTrue(abs(v.mean - model.meanVariance(1.0)) < 4.5 * v.standardError + 0.02 * model.meanVariance(1.0), "E[v(1)] = ${v.mean}")
    }
}
