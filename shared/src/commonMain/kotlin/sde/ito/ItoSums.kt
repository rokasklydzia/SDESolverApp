package sde.ito

import sde.montecarlo.TimeGrid
import sde.random.RandomSource
import kotlin.math.sqrt

/**
 * Discrete stochastic integrals along one Brownian path, as running sums so a chart can animate
 * them. Every function takes W sampled on a grid (w[0] = W(0)) and returns an array of the same
 * length whose entry i is the sum up to time tᵢ.
 */
object ItoSums {
    /** A standard Brownian path on [grid], starting at 0. */
    fun brownianPath(grid: TimeGrid, random: RandomSource): DoubleArray {
        val w = DoubleArray(grid.steps + 1)
        val sd = sqrt(grid.dt)
        for (i in 1..grid.steps) w[i] = w[i - 1] + sd * random.nextGaussian()
        return w
    }

    /** Itô sum Σ f(tᵢ)·(W(tᵢ₊₁) − W(tᵢ)): the integrand is evaluated at the left end point. */
    fun ito(integrand: DoubleArray, w: DoubleArray): DoubleArray {
        require(integrand.size == w.size) { "integrand and path must have the same length" }
        val sum = DoubleArray(w.size)
        for (i in 1 until w.size) sum[i] = sum[i - 1] + integrand[i - 1] * (w[i] - w[i - 1])
        return sum
    }

    /** Stratonovich sum Σ ½(f(tᵢ) + f(tᵢ₊₁))·ΔW: the integrand is averaged over each step. */
    fun stratonovich(integrand: DoubleArray, w: DoubleArray): DoubleArray {
        require(integrand.size == w.size) { "integrand and path must have the same length" }
        val sum = DoubleArray(w.size)
        for (i in 1 until w.size) sum[i] = sum[i - 1] + 0.5 * (integrand[i - 1] + integrand[i]) * (w[i] - w[i - 1])
        return sum
    }

    /** Quadratic variation Σ (ΔW)², which converges to t as the grid is refined. */
    fun quadraticVariation(w: DoubleArray): DoubleArray {
        val sum = DoubleArray(w.size)
        for (i in 1 until w.size) {
            val d = w[i] - w[i - 1]
            sum[i] = sum[i - 1] + d * d
        }
        return sum
    }

    /** ½W(t)² − ½t, the Itô integral ∫₀ᵗ W dW in closed form. */
    fun itoWdWClosedForm(grid: TimeGrid, w: DoubleArray): DoubleArray =
        DoubleArray(w.size) { 0.5 * w[it] * w[it] - 0.5 * grid.time(it) }
}
