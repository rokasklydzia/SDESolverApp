package sde

import sde.model.EulerMaruyama
import sde.model.GeometricBrownianMotion
import sde.model.Milstein
import sde.model.Scheme
import sde.random.SeededRandom
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Strong convergence: E|X_N − X(T)| ≈ C·dt^γ, measured against GBM's exact solution driven by
 * the same Brownian path. The order γ is the slope of log error against log dt.
 */
class ConvergenceTest {
    private val sde = GeometricBrownianMotion(mu = 0.06, sigma = 0.4)
    private val x0 = 1.0
    private val horizon = 1.0

    /** Mean absolute terminal error for each step count, all levels on the same Brownian paths. */
    private fun strongErrors(scheme: Scheme, levels: List<Int>, paths: Int): DoubleArray {
        val finest = levels.max()
        val random = SeededRandom(31)
        val errors = DoubleArray(levels.size)
        val dW = DoubleArray(finest)
        repeat(paths) {
            for (j in 0 until finest) dW[j] = sqrt(horizon / finest) * random.nextGaussian()
            val exact = sde.solution(horizon, x0, dW.sum())
            for ((k, n) in levels.withIndex()) {
                val dt = horizon / n
                val per = finest / n
                var x = x0
                for (i in 0 until n) {
                    var increment = 0.0
                    for (j in i * per until (i + 1) * per) increment += dW[j]
                    x = scheme.step(sde, i * dt, x, dt, increment)
                }
                errors[k] += abs(x - exact) / paths
            }
        }
        return errors
    }

    private fun slope(levels: List<Int>, errors: DoubleArray): Double {
        val xs = levels.map { ln(horizon / it) }
        val ys = errors.map { ln(it) }
        val mx = xs.average()
        val my = ys.average()
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        return sxy / sxx
    }

    @Test
    fun eulerMaruyamaHasStrongOrderOneHalf() {
        val levels = listOf(8, 16, 32, 64, 128, 256)
        val order = slope(levels, strongErrors(EulerMaruyama, levels, 4000))
        println("Euler–Maruyama strong order ≈ $order")
        assertTrue(order in 0.4..0.62, "Euler–Maruyama strong order $order")
    }

    @Test
    fun milsteinHasStrongOrderOne() {
        val levels = listOf(8, 16, 32, 64, 128, 256)
        val order = slope(levels, strongErrors(Milstein, levels, 4000))
        println("Milstein strong order ≈ $order")
        assertTrue(order in 0.88..1.12, "Milstein strong order $order")
    }
}
