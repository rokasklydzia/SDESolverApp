package sde

import sde.analytic.BlackScholes
import sde.analytic.Normal
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticTest {
    @Test
    fun normalCdfMatchesReferenceValues() {
        // Reference values from scipy.stats.norm.cdf.
        assertEquals(0.5, Normal.cdf(0.0), 1e-15)
        assertEquals(0.8413447460685429, Normal.cdf(1.0), 1e-14)
        assertEquals(0.9750021048517795, Normal.cdf(1.96), 1e-14)
        assertEquals(0.022750131948179195, Normal.cdf(-2.0), 1e-15)
        // In the far tail the error is about 1e-8 relative to Φ(x) itself.
        assertEquals(2.866515718791933e-7, Normal.cdf(-5.0), 2.9e-15)
        assertEquals(7.61985302416047e-24, Normal.cdf(-10.0), 7.6e-32)
    }

    @Test
    fun normalCdfIsSymmetric() {
        for (x in listOf(0.1, 0.7, 1.5, 3.2, 6.9, 7.2, 12.0)) {
            assertEquals(1.0, Normal.cdf(x) + Normal.cdf(-x), 1e-15)
        }
    }

    @Test
    fun blackScholesMatchesTextbookValueAndPutCallParity() {
        // Hull's example: S = 42, K = 40, r = 10%, σ = 20%, T = 0.5 gives c = 4.76, p = 0.81.
        val call = BlackScholes.call(42.0, 40.0, 0.1, 0.2, 0.5)
        val put = BlackScholes.put(42.0, 40.0, 0.1, 0.2, 0.5)
        assertEquals(4.76, call, 0.005)
        assertEquals(0.81, put, 0.005)
        assertEquals(42.0 - 40.0 * exp(-0.1 * 0.5), call - put, 1e-12)
    }
}
