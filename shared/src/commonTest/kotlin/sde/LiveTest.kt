package sde

import kotlinx.coroutines.test.runTest
import sde.chart.Ticks
import sde.chart.Viewport
import sde.live.Ensemble
import sde.model.ArithmeticBrownianMotion
import sde.model.Exact
import sde.model.GeometricBrownianMotion
import sde.model.Heston
import sde.model.MertonJumpDiffusion
import sde.model.Milstein
import sde.model.OrnsteinUhlenbeck
import sde.model.ScalarProcess
import sde.model.ScaledRandomWalk
import sde.model.marginalDensity
import sde.model.solveAlong
import sde.ito.ItoSums
import sde.montecarlo.MonteCarlo
import sde.montecarlo.MonteCarloSettings
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTest {
    @Test
    fun ensemblePathsAreTheMonteCarloPaths() = runTest {
        val process = ScalarProcess(GeometricBrownianMotion(), 100.0)
        val grid = TimeGrid(1.0, 200)
        val ensemble = Ensemble.simulate(process, grid, paths = 1200, seed = 4)
        val mc = MonteCarlo.run(process, MonteCarloSettings(grid, paths = 1200, seed = 4, keepPaths = 1200))
        for (p in listOf(0, 511, 512, 1199)) {
            assertEquals(mc.keptPaths[p].values[0][137].toFloat(), ensemble.value(p, 137))
        }
        assertEquals(mc.meanPath[200], ensemble.mean[200].toDouble(), 1e-3)
    }

    @Test
    fun bandsAreOrderedAndMatchNormalQuantiles() = runTest {
        val bm = ArithmeticBrownianMotion(0.0, 1.0)
        val ensemble = Ensemble.simulate(ScalarProcess(bm, 0.0), TimeGrid(1.0, 100), paths = 20_000, seed = 8)
        val (q05, q25, q75, q95) = ensemble.bands.toList()
        for (i in 1..100) assertTrue(q05[i] <= q25[i] && q25[i] <= q75[i] && q75[i] <= q95[i])
        // At t = 1 the 95% quantile of W is 1.645.
        assertEquals(1.645, q95[100].toDouble(), 0.03)
        assertEquals(-1.645, q05[100].toDouble(), 0.03)
        assertEquals(0.0, q05[0].toDouble())
        val range = ensemble.bandRange()
        assertTrue(range.start < -1.6 && range.endInclusive > 1.6)
    }

    @Test
    fun histogramFollowsTheExactDensity() = runTest {
        val process = ScalarProcess(OrnsteinUhlenbeck(2.0, 1.0, 0.5), -1.0, Exact)
        val ensemble = Ensemble.simulate(process, TimeGrid(2.0, 40), paths = 50_000, seed = 6)
        val density = process.marginalDensity()!!
        val histogram = ensemble.histogram(20, bins = 30, min = -1.0, max = 2.0)
        var worst = 0.0
        for (b in 0 until histogram.bins) {
            worst = maxOf(worst, abs(histogram.density(b) - density(1.0, histogram.binCenter(b))))
        }
        assertTrue(worst < 0.08, "largest gap between histogram and density: $worst")
    }

    @Test
    fun densitiesIntegrateToOne() {
        fun integral(f: (Double) -> Double, lo: Double, hi: Double, n: Int = 20_000): Double {
            val h = (hi - lo) / n
            return (0 until n).sumOf { f(lo + (it + 0.5) * h) } * h
        }
        val cases = listOf(
            ScalarProcess(ArithmeticBrownianMotion(0.3, 1.2), 1.0) to (-10.0..12.0),
            ScalarProcess(GeometricBrownianMotion(0.05, 0.3), 100.0) to (0.0..600.0),
            ScalarProcess(OrnsteinUhlenbeck(1.0, 0.0, 0.3), 1.0) to (-3.0..3.0),
            ScaledRandomWalk(0.0, 1.0) to (-10.0..10.0),
            MertonJumpDiffusion(lambda = 2.0) to (0.0..600.0),
        )
        for ((process, range) in cases) {
            val f = process.marginalDensity()!!
            assertEquals(1.0, integral({ f(1.0, it) }, range.start, range.endInclusive), 1e-3, process.toString())
        }
        assertNull(Heston().marginalDensity())
    }

    @Test
    fun schemesDrivenByTheSameBrownianPath() {
        val grid = TimeGrid(1.0, 1000)
        val w = ItoSums.brownianPath(grid, SeededRandom(12))
        val gbm = GeometricBrownianMotion(0.05, 0.4)
        val exact = gbm.solveAlong(grid, 1.0, w, Exact)
        val milstein = gbm.solveAlong(grid, 1.0, w, Milstein)
        val euler = gbm.solveAlong(grid, 1.0, w)
        assertEquals(gbm.solution(1.0, 1.0, w.last()), exact.last(), 1e-12)
        assertTrue(abs(milstein.last() - exact.last()) < abs(euler.last() - exact.last()))
    }

    @Test
    fun viewportZoomKeepsTheFocusFixed() {
        val v = Viewport(0.0, 1.0, -2.0, 2.0)
        val z = v.zoomed(2.0, 4.0, focusT = 0.25, focusX = 1.0)
        assertEquals(v.fractionT(0.25), z.fractionT(0.25), 1e-12)
        assertEquals(v.fractionX(1.0), z.fractionX(1.0), 1e-12)
        assertEquals(0.5, z.width, 1e-12)
        assertEquals(1.0, z.height, 1e-12)
        val panned = z.panned(-1.0, 0.5).constrainedTime(0.0..1.0)
        assertEquals(0.0, panned.tMin, 1e-12)
        assertEquals(0.5, panned.width, 1e-12)
        val zoomedOut = v.zoomed(0.1, 1.0, 0.5, 0.0).constrainedTime(0.0..1.0)
        assertEquals(0.0, zoomedOut.tMin, 1e-12)
        assertEquals(1.0, zoomedOut.tMax, 1e-12)
        val flat = Viewport.fit(0.0..1.0, 3.0..3.0)
        assertTrue(flat.xMin < 3.0 && flat.xMax > 3.0)
    }

    @Test
    fun ticksAreNiceAndLabelsHaveTheRightDecimals() {
        val (ticks, step) = Ticks.nice(-1.7, 2.3, 5)
        assertEquals(1.0, step)
        assertEquals(listOf(-1.0, 0.0, 1.0, 2.0), ticks)
        val (fine, fineStep) = Ticks.nice(0.0, 1.0, 4)
        assertEquals(0.2, fineStep, 1e-12)
        assertEquals("0.6", Ticks.label(fine[3], fineStep))
        assertEquals("0,25", Ticks.label(0.25, 0.05, ','))
        assertEquals("150", Ticks.label(150.0, 50.0))
        assertEquals("-0.05", Ticks.formatFixed(-0.05, 2))
        assertEquals("0", Ticks.formatFixed(-0.0001, 0))
        assertEquals("1.000", Ticks.formatFixed(0.9999, 3))
    }
}
