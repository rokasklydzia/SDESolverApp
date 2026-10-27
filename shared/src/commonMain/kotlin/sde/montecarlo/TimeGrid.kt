package sde.montecarlo

import sde.model.StochasticProcess
import sde.random.RandomSource

/** An equally spaced grid 0 = t₀ < t₁ < … < t_N = [horizon]. */
data class TimeGrid(val horizon: Double, val steps: Int) {
    init {
        require(horizon > 0) { "the horizon must be positive" }
        require(steps > 0) { "a grid needs at least one step" }
    }

    val dt: Double get() = horizon / steps

    fun time(i: Int): Double = if (i == steps) horizon else i * dt

    fun times(): DoubleArray = DoubleArray(steps + 1) { time(it) }
}

/** One simulated path: values[c][i] is component c at time grid.time(i). */
class Path(val grid: TimeGrid, val values: Array<DoubleArray>) {
    val terminal: DoubleArray get() = DoubleArray(values.size) { values[it][grid.steps] }
}

/** Simulates a single path of every component of the process. */
fun StochasticProcess.simulatePath(grid: TimeGrid, random: RandomSource): Path {
    val state = initialState()
    val values = Array(state.size) { DoubleArray(grid.steps + 1) }
    for (c in state.indices) values[c][0] = state[c]
    for (i in 0 until grid.steps) {
        advance(grid.time(i), grid.dt, state, random)
        for (c in state.indices) values[c][i + 1] = state[c]
    }
    return Path(grid, values)
}
