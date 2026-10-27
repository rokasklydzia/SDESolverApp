package sde

import sde.ito.ItoSums
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItoTest {
    private val grid = TimeGrid(1.0, 100_000)
    private val w = ItoSums.brownianPath(grid, SeededRandom(17))

    @Test
    fun itoIntegralOfWdWIsHalfWSquaredMinusHalfT() {
        val ito = ItoSums.ito(w, w)
        val closedForm = ItoSums.itoWdWClosedForm(grid, w)
        // Exactly, Σ W ΔW = ½W² − ½Σ(ΔW)², so the gap is half the quadratic variation's error.
        assertTrue(abs(ito.last() - closedForm.last()) < 0.01, "Itô sum ${ito.last()} vs ${closedForm.last()}")
    }

    @Test
    fun stratonovichIntegralFollowsOrdinaryCalculus() {
        val strat = ItoSums.stratonovich(w, w)
        assertEquals(0.5 * w.last() * w.last(), strat.last(), 1e-9)
    }

    @Test
    fun quadraticVariationConvergesToT() {
        val qv = ItoSums.quadraticVariation(w)
        assertEquals(1.0, qv.last(), 0.015)
        assertEquals(0.5, qv[grid.steps / 2], 0.015)
    }
}
