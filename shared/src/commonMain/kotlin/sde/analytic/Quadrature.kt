package sde.analytic

/** Numerical integration, for exact expectations E[g(X)] = ∫ g(x)·p(x) dx from a known density p. */
object Quadrature {
    /**
     * ∫ₐᵇ f(x) dx by the composite Simpson rule on [intervals] steps (rounded up to an even
     * number). The error is O(h⁴) for smooth f and O(h²) across a kink such as max(x − K, 0).
     */
    fun simpson(a: Double, b: Double, intervals: Int = 4000, f: (Double) -> Double): Double {
        require(b > a) { "the interval [$a, $b] is empty" }
        val n = if (intervals % 2 == 0) intervals else intervals + 1
        val h = (b - a) / n
        var sum = f(a) + f(b)
        for (i in 1 until n) sum += (if (i % 2 == 1) 4 else 2) * f(a + i * h)
        return sum * h / 3
    }
}
