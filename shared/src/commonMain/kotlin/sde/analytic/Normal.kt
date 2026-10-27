package sde.analytic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** The standard normal distribution. */
object Normal {
    private val INV_SQRT_2PI = 1.0 / sqrt(2.0 * PI)

    fun pdf(x: Double): Double = INV_SQRT_2PI * exp(-0.5 * x * x)

    /**
     * Φ(x) to about 2e-16 absolute error (1e-8 relative in the far tail): Hart's double precision
     * rational approximation as given by G. West, "Better approximations to cumulative normal
     * functions" (2005).
     */
    fun cdf(x: Double): Double {
        val a = abs(x)
        val tail = when {
            a > 37.0 -> 0.0
            a < 7.07106781186547 -> {
                val e = exp(-a * a / 2)
                var n = 3.52624965998911e-02 * a + 0.700383064443688
                n = n * a + 6.37396220353165
                n = n * a + 33.912866078383
                n = n * a + 112.079291497871
                n = n * a + 221.213596169931
                n = n * a + 220.206867912376
                var d = 8.83883476483184e-02 * a + 1.75566716318264
                d = d * a + 16.064177579207
                d = d * a + 86.7807322029461
                d = d * a + 296.564248779674
                d = d * a + 637.333633378831
                d = d * a + 793.826512519948
                d = d * a + 440.413735824752
                e * n / d
            }
            else -> {
                val e = exp(-a * a / 2)
                var b = a + 0.65
                b = a + 4 / b
                b = a + 3 / b
                b = a + 2 / b
                b = a + 1 / b
                e / b / 2.506628274631
            }
        }
        return if (x > 0) 1 - tail else tail
    }

    /** The density of N(mean, sd²) at x. */
    fun pdf(x: Double, mean: Double, sd: Double): Double = pdf((x - mean) / sd) / sd
}

/** The density of a lognormal variable whose logarithm is N(mu, sigma²). */
fun lognormalPdf(x: Double, mu: Double, sigma: Double): Double =
    if (x <= 0) 0.0 else Normal.pdf((kotlin.math.ln(x) - mu) / sigma) / (x * sigma)
