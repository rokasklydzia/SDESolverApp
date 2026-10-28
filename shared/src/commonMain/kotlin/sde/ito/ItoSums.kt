package sde.ito

import sde.montecarlo.TimeGrid
import sde.random.RandomSource
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Where a Riemann–Stieltjes sum Σ h·ΔX evaluates its integrand on each step. */
enum class EvaluationPoint(val label: String, val weightOfRight: Double) {
    /** h(tᵢ): the Itô integral, a martingale when X is. */
    LEFT("Itô (left point)", 0.0),
    /** ½(h(tᵢ) + h(tᵢ₊₁)): the Stratonovich integral, which obeys the ordinary chain rule. */
    MIDPOINT("Stratonovich (midpoint)", 0.5),
    /** h(tᵢ₊₁): the backward Itô integral. */
    RIGHT("Backward (right point)", 1.0),
}

/**
 * Discrete stochastic integrals and variations along one path, as running sums so a chart can
 * animate them. Every function takes paths sampled on a grid (x[0] = X(0)) and returns an array of
 * the same length whose entry i is the sum up to time tᵢ.
 */
object ItoSums {
    /** A standard Brownian path on [grid], starting at 0. */
    fun brownianPath(grid: TimeGrid, random: RandomSource): DoubleArray {
        val w = DoubleArray(grid.steps + 1)
        val sd = sqrt(grid.dt)
        for (i in 1..grid.steps) w[i] = w[i - 1] + sd * random.nextGaussian()
        return w
    }

    /**
     * Σ h·ΔX with h taken at [point] of each step. For h = f(t, X) the right point sum exceeds the
     * left point sum by Σ Δh·ΔX, which tends to the covariation ∫ f_x d⟨X⟩; the midpoint sum lies
     * halfway between.
     */
    fun riemann(integrand: DoubleArray, x: DoubleArray, point: EvaluationPoint): DoubleArray {
        require(integrand.size == x.size) { "integrand and path must have the same length" }
        val right = point.weightOfRight
        val sum = DoubleArray(x.size)
        for (i in 1 until x.size) {
            val h = (1 - right) * integrand[i - 1] + right * integrand[i]
            sum[i] = sum[i - 1] + h * (x[i] - x[i - 1])
        }
        return sum
    }

    /** Itô sum Σ f(tᵢ)·(W(tᵢ₊₁) − W(tᵢ)): the integrand is evaluated at the left end point. */
    fun ito(integrand: DoubleArray, w: DoubleArray): DoubleArray = riemann(integrand, w, EvaluationPoint.LEFT)

    /** Stratonovich sum Σ ½(f(tᵢ) + f(tᵢ₊₁))·ΔW: the integrand is averaged over each step. */
    fun stratonovich(integrand: DoubleArray, w: DoubleArray): DoubleArray = riemann(integrand, w, EvaluationPoint.MIDPOINT)

    /** Backward sum Σ f(tᵢ₊₁)·ΔW: the integrand is evaluated at the right end point. */
    fun backward(integrand: DoubleArray, w: DoubleArray): DoubleArray = riemann(integrand, w, EvaluationPoint.RIGHT)

    /** Quadratic variation Σ (ΔW)², which converges to t as the grid is refined. */
    fun quadraticVariation(w: DoubleArray): DoubleArray = variation(w, 2.0)

    /**
     * The p-variation Σ |ΔX|ᵖ. For an Itô process it tends to ∞ for p < 2, to ⟨X⟩ for p = 2 and
     * to 0 for p > 2 as the grid is refined.
     */
    fun variation(x: DoubleArray, p: Double): DoubleArray {
        val sum = DoubleArray(x.size)
        for (i in 1 until x.size) sum[i] = sum[i - 1] + abs(x[i] - x[i - 1]).pow(p)
        return sum
    }

    /** Σ Y(tᵢ)·(ΔX)ⁿ with the sign of ΔX kept, e.g. Σ Y(ΔX)³, which tends to 0 for n ≥ 3. */
    fun weightedPowerSum(y: DoubleArray, x: DoubleArray, n: Int): DoubleArray {
        require(y.size == x.size) { "both paths must have the same length" }
        val sum = DoubleArray(x.size)
        for (i in 1 until x.size) {
            var d = 1.0
            val dx = x[i] - x[i - 1]
            repeat(n) { d *= dx }
            sum[i] = sum[i - 1] + y[i - 1] * d
        }
        return sum
    }

    /** The covariation Σ ΔX·ΔY, which tends to ⟨X, Y⟩. */
    fun covariation(x: DoubleArray, y: DoubleArray): DoubleArray {
        require(x.size == y.size) { "both paths must have the same length" }
        val sum = DoubleArray(x.size)
        for (i in 1 until x.size) sum[i] = sum[i - 1] + (x[i] - x[i - 1]) * (y[i] - y[i - 1])
        return sum
    }

    /** ∫₀ᵗ g ds by the left point rule, for compensators and ∫ σ² ds. */
    fun timeIntegral(g: DoubleArray, grid: TimeGrid): DoubleArray {
        require(g.size == grid.steps + 1) { "the integrand needs ${grid.steps + 1} points, not ${g.size}" }
        val sum = DoubleArray(g.size)
        for (i in 1 until g.size) sum[i] = sum[i - 1] + g[i - 1] * (grid.time(i) - grid.time(i - 1))
        return sum
    }

    /** Every [every]-th point of [x], the same path seen on a grid [every] times coarser. */
    fun coarsen(x: DoubleArray, every: Int): DoubleArray {
        require(every >= 1 && (x.size - 1) % every == 0) { "${x.size - 1} steps cannot be grouped in blocks of $every" }
        return DoubleArray((x.size - 1) / every + 1) { x[it * every] }
    }

    /** ½W(t)² − ½t, the Itô integral ∫₀ᵗ W dW in closed form. */
    fun itoWdWClosedForm(grid: TimeGrid, w: DoubleArray): DoubleArray = wdWClosedForm(grid, w, EvaluationPoint.LEFT)

    /** ∫₀ᵗ W dW in closed form: ½W² − ½t (Itô), ½W² (Stratonovich) or ½W² + ½t (backward). */
    fun wdWClosedForm(grid: TimeGrid, w: DoubleArray, point: EvaluationPoint): DoubleArray {
        val correction = point.weightOfRight - 0.5
        return DoubleArray(w.size) { 0.5 * w[it] * w[it] + correction * grid.time(it) }
    }
}
