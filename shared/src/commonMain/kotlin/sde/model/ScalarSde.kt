package sde.model

import kotlin.math.abs
import kotlin.math.max

/** A one-dimensional Itô SDE dX = μ(t, X) dt + σ(t, X) dW. */
interface ScalarSde {
    /** The drift μ(t, x). */
    fun drift(t: Double, x: Double): Double

    /** The diffusion σ(t, x). */
    fun diffusion(t: Double, x: Double): Double

    /**
     * σ(t, x)·∂σ/∂x(t, x), the coefficient of Milstein's correction term. The default uses a
     * central difference; models override it with the exact expression.
     */
    fun diffusionTimesSlope(t: Double, x: Double): Double {
        val h = 1e-6 * max(1.0, abs(x))
        val slope = (diffusion(t, x + h) - diffusion(t, x - h)) / (2.0 * h)
        return diffusion(t, x) * slope
    }
}

/** A model whose mean and variance at time t are known in closed form, starting from x0 at time 0. */
interface KnownMoments {
    fun mean(t: Double, x0: Double): Double
    fun variance(t: Double, x0: Double): Double
}

/** A model whose solution is a known function of the driving Brownian motion: X(t) = F(t, x0, W(t)). */
interface PathwiseSolution {
    fun solution(t: Double, x0: Double, w: Double): Double
}

/**
 * A model that can be stepped exactly in distribution: X(t + dt) given X(t) = x, using the
 * Brownian increment dW ~ N(0, dt) as the source of randomness.
 */
interface ExactTransition {
    fun transition(t: Double, x: Double, dt: Double, dW: Double): Double
}

/** A model whose density of X(t), started from x0 at time 0, is known in closed form (for t > 0). */
interface KnownDensity {
    fun density(t: Double, x0: Double, x: Double): Double
}
