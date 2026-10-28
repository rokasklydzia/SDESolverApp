package sde.model

import sde.montecarlo.TimeGrid

/**
 * Solves the SDE along a given Brownian path w on [grid] (w[0] = W(0), usually 0). Driving
 * several schemes, or several SDEs, with the same w compares them path by path: Euler against
 * Milstein against the exact solution, or X and Y in a covariation ⟨X, Y⟩.
 */
fun ScalarSde.solveAlong(grid: TimeGrid, x0: Double, w: DoubleArray, scheme: Scheme = EulerMaruyama): DoubleArray {
    require(w.size == grid.steps + 1) { "the Brownian path needs ${grid.steps + 1} points, not ${w.size}" }
    val x = DoubleArray(w.size)
    x[0] = x0
    for (i in 0 until grid.steps) {
        x[i + 1] = scheme.step(this, grid.time(i), x[i], grid.dt, w[i + 1] - w[i])
    }
    return x
}
