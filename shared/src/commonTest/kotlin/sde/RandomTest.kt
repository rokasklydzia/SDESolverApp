package sde

import sde.montecarlo.Estimate
import sde.random.AntitheticSource
import sde.random.SeededRandom
import sde.random.poissonFromUniform
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomTest {
    @Test
    fun sameSeedGivesSameNumbers() {
        val a = SeededRandom(42)
        val b = SeededRandom(42)
        repeat(1000) { assertEquals(a.nextGaussian(), b.nextGaussian()) }
    }

    @Test
    fun gaussiansHaveMeanZeroVarianceOneAndNormalTails() {
        val random = SeededRandom(7)
        val n = 400_000
        val z = DoubleArray(n) { random.nextGaussian() }
        val mean = Estimate.of(z)
        assertTrue(abs(mean.zScore(0.0)) < 4, "mean ${mean.mean}")
        val variance = z.sumOf { it * it } / n
        assertEquals(1.0, variance, 0.01)
        // P(|Z| > 1.96) = 5%
        val tail = z.count { abs(it) > 1.959964 }.toDouble() / n
        assertEquals(0.05, tail, 0.002)
    }

    @Test
    fun uniformsStayInsideTheOpenInterval() {
        val random = SeededRandom(3)
        repeat(100_000) {
            val u = random.nextUniform()
            assertTrue(u > 0.0 && u < 1.0)
        }
    }

    @Test
    fun antitheticMirrorReplaysNegatedDraws() {
        val source = AntitheticSource(SeededRandom(11))
        source.beginOriginal()
        val original = listOf(source.nextGaussian(), source.nextUniform(), source.nextGaussian())
        source.beginMirror()
        val mirror = listOf(source.nextGaussian(), source.nextUniform(), source.nextGaussian())
        assertEquals(-original[0], mirror[0])
        assertEquals(1 - original[1], mirror[1], 1e-15)
        assertEquals(-original[2], mirror[2])
    }

    @Test
    fun poissonInversionHasTheRightMean() {
        val random = SeededRandom(5)
        val lambda = 0.8
        val n = 200_000
        val counts = DoubleArray(n) { poissonFromUniform(random.nextUniform(), lambda).toDouble() }
        val estimate = Estimate.of(counts)
        assertTrue(abs(estimate.zScore(lambda)) < 4, "mean ${estimate.mean}")
        assertEquals(0, poissonFromUniform(0.1, 0.0))
    }
}
