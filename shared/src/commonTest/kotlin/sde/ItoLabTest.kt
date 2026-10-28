package sde

import kotlinx.coroutines.test.runTest
import sde.expr.Evaluator
import sde.expr.Exprs
import sde.expr.Parser
import sde.expr.format
import sde.ito.EvaluationPoint
import sde.ito.ItoFormula
import sde.ito.ItoSums
import sde.ito.MartingaleCheck
import sde.ito.alongPath
import sde.model.CustomSde
import sde.model.solveAlong
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The step 3 labs, checked against the course exercises they demonstrate. */
class ItoLabTest {
    private val grid = TimeGrid(1.0, 65_536)
    private val w = ItoSums.brownianPath(grid, SeededRandom(23))

    @Test
    fun itoFormulaForBSquared() {
        val d = ItoFormula.apply(Parser.parse("x^2"), Exprs.ZERO, Exprs.ONE)
        assertEquals("1", d.dt.format())
        assertEquals("2*x", d.dB.format())
    }

    @Test
    fun tBMinusIntegralOfBIsAMartingaleSymbolically() {
        // Exercise 3: d(tB) = B dt + t dB, so tB − ∫B ds has no dt term at all.
        val check = MartingaleCheck("0", "1", "t*x", compensatorText = "-x")
        assertEquals(Exprs.ZERO, check.driftOfM)
    }

    @Test
    fun expHalfTCosBIsAMartingale() {
        // Exercise 4: f_t = ½e^{t/2}cos B and ½f_xx = −½e^{t/2}cos B cancel.
        val check = MartingaleCheck("0", "1", "exp(t/2)*cos(x)")
        assertEquals(Exprs.ZERO, check.driftOfM)
        assertTrue(check.driftVanishes(1.0, -3.0..3.0), "relative drift ${check.relativeDrift(1.0, -3.0..3.0)}")
        assertFalse(MartingaleCheck("0", "1", "exp(t)*cos(x)").driftVanishes(1.0, -3.0..3.0))
    }

    @Test
    fun compensatorForXCubed() {
        // Exercise 13: dX = X dt + X dB gives d(X³) = 6X³ dt + 3X³ dB, so g = −6x³.
        val check = MartingaleCheck("x", "x", "x^3")
        val g = Evaluator.compile(check.requiredCompensator)
        for (x in listOf(-2.0, 0.5, 1.7)) assertEquals(-6 * x * x * x, g.eval(0.3, x), 1e-12)
        assertEquals("-6*x^3", check.requiredCompensator.format())
        val fixed = MartingaleCheck("x", "x", "x^3", check.requiredCompensator.format())
        assertTrue(fixed.driftVanishes(1.0, 0.1..5.0))
    }

    @Test
    fun simulatedMeanStaysPutOnlyForAMartingale() = runTest {
        val grid = TimeGrid(1.0, 200)
        val martingale = MartingaleCheck("0", "1", "x^2 - t").simulate(0.0, grid, paths = 20_000, seed = 5)
        assertTrue(abs(martingale.zScore(grid.steps)) < 4, "B² − t drifted: z = ${martingale.zScore(grid.steps)}")
        val notMartingale = MartingaleCheck("0", "1", "x^2").simulate(0.0, grid, paths = 20_000, seed = 5)
        assertEquals(1.0, notMartingale.mean[grid.steps], 0.05)
        assertTrue(notMartingale.zScore(grid.steps) > 20)
        assertEquals(30, martingale.keptPaths.size)
    }

    @Test
    fun theThreeSumsOfWdWDifferByTheQuadraticVariation() {
        val left = ItoSums.riemann(w, w, EvaluationPoint.LEFT)
        val mid = ItoSums.riemann(w, w, EvaluationPoint.MIDPOINT)
        val right = ItoSums.riemann(w, w, EvaluationPoint.RIGHT)
        val qv = ItoSums.quadraticVariation(w)
        assertEquals(qv.last(), right.last() - left.last(), 1e-9)
        assertEquals(0.5 * qv.last(), mid.last() - left.last(), 1e-9)
        val closed = ItoSums.wdWClosedForm(grid, w, EvaluationPoint.RIGHT)
        assertEquals(closed.last(), right.last(), 0.02)
    }

    @Test
    fun pVariationBlowsUpBelowTwoAndVanishesAboveTwo() {
        val coarse = ItoSums.coarsen(w, 256)
        for ((p, grows) in listOf(1.0 to true, 3.0 to false)) {
            val fine = ItoSums.variation(w, p).last()
            val rough = ItoSums.variation(coarse, p).last()
            assertEquals(grows, fine > rough, "p = $p: fine $fine, coarse $rough")
        }
        assertEquals(1.0, ItoSums.variation(w, 2.0).last(), 0.02)
    }

    @Test
    fun variationsOfExpTwoBMinusThreeT() {
        // Exercise 12: X = e^{2B − 3t} has dX = −X dt + 2X dB, so Σ(ΔX)² → ∫4X² dt and Σ X(ΔX)³ → 0.
        val x = DoubleArray(w.size) { exp(2 * w[it] - 3 * grid.time(it)) }
        val qv = ItoSums.variation(x, 2.0).last()
        val predicted = ItoSums.timeIntegral(DoubleArray(x.size) { 4 * x[it] * x[it] }, grid).last()
        assertEquals(predicted, qv, 0.05 * predicted)
        val cubic = ItoSums.weightedPowerSum(x, x, 3).last()
        assertTrue(abs(cubic) < 0.02 * predicted, "Σ X(ΔX)³ = $cubic")
    }

    @Test
    fun covariationOfBFourAndBSquared() {
        // Exercise 5: d⟨B⁴, B²⟩ = 4B³·2B dt = 8B⁴ dt.
        val b4 = DoubleArray(w.size) { w[it] * w[it] * w[it] * w[it] }
        val b2 = DoubleArray(w.size) { w[it] * w[it] }
        val covariation = ItoSums.covariation(b4, b2).last()
        val predicted = ItoSums.timeIntegral(DoubleArray(w.size) { 8 * b4[it] }, grid).last()
        assertEquals(predicted, covariation, 0.05 * predicted + 0.01)
    }

    @Test
    fun covariationOfTwoSdesOnOneBrownianPath() {
        // Exercise 7 style: X = B and dY = cos²Y dt + dB share their noise, so d⟨X, Y⟩ = dt.
        val y = CustomSde("cos(x)^2", "1").solveAlong(grid, 0.0, w)
        assertEquals(1.0, ItoSums.covariation(w, y).last(), 0.02)
        val sigma = Evaluator.compile(Parser.parse("x")).alongPath(grid, w)
        assertEquals(w.last(), sigma.last(), 0.0)
    }
}
