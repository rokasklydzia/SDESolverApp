package sde.random

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The random numbers a simulation consumes. Every model draws through this interface, so a
 * seeded source reproduces a run exactly and [AntitheticSource] can mirror one.
 */
interface RandomSource {
    /** A standard normal draw, N(0, 1). */
    fun nextGaussian(): Double

    /** A uniform draw on the open interval (0, 1). */
    fun nextUniform(): Double
}

/**
 * xoshiro256++ (Blackman and Vigna, 2019) seeded through SplitMix64, with normals from
 * Marsaglia's polar method. Pure Kotlin, so a seed gives the same numbers on Android and iOS.
 */
class SeededRandom(seed: Long) : RandomSource {
    private var s0: Long
    private var s1: Long
    private var s2: Long
    private var s3: Long
    private var spare = 0.0
    private var hasSpare = false

    init {
        val mix = SplitMix64(seed)
        s0 = mix.next(); s1 = mix.next(); s2 = mix.next(); s3 = mix.next()
    }

    fun nextLong(): Long {
        val result = (s0 + s3).rotateLeft(23) + s0
        val t = s1 shl 17
        s2 = s2 xor s0
        s3 = s3 xor s1
        s1 = s1 xor s2
        s0 = s0 xor s3
        s2 = s2 xor t
        s3 = s3.rotateLeft(45)
        return result
    }

    /** Uniform on [0, 1) with 53 random bits. */
    fun nextDouble(): Double = (nextLong() ushr 11) * TWO_POW_MINUS_53

    override fun nextUniform(): Double {
        while (true) {
            val u = nextDouble()
            if (u > 0.0) return u
        }
    }

    override fun nextGaussian(): Double {
        if (hasSpare) {
            hasSpare = false
            return spare
        }
        while (true) {
            val u = 2.0 * nextDouble() - 1.0
            val v = 2.0 * nextDouble() - 1.0
            val s = u * u + v * v
            if (s > 0.0 && s < 1.0) {
                val m = sqrt(-2.0 * ln(s) / s)
                spare = v * m
                hasSpare = true
                return u * m
            }
        }
    }

    private companion object {
        const val TWO_POW_MINUS_53 = 1.0 / (1L shl 53)
    }
}

/** SplitMix64, used to expand one seed into well-mixed state words and independent stream seeds. */
class SplitMix64(private var state: Long) {
    fun next(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    companion object {
        /** The seed of stream [index] derived from a master [seed]; streams do not overlap in practice. */
        fun streamSeed(seed: Long, index: Int): Long = SplitMix64(seed xor (index.toLong() * -0x61c8864680b583ebL)).next()
    }
}

/**
 * Antithetic variates. During an original path the draws of [base] are recorded; during the
 * mirrored path they are replayed as Z → −Z and U → 1 − U. Models must therefore consume the same
 * sequence of draw kinds on every path, which all models in this engine do.
 */
class AntitheticSource(private val base: RandomSource) : RandomSource {
    private var draws = DoubleArray(1024)
    private var size = 0
    private var cursor = 0
    private var mirroring = false

    /** Starts an original path: fresh draws from the base source, recorded for the mirror. */
    fun beginOriginal() {
        mirroring = false
        size = 0
    }

    /** Starts the mirrored partner of the last original path. */
    fun beginMirror() {
        mirroring = true
        cursor = 0
    }

    override fun nextGaussian(): Double = if (mirroring) -replay() else record(base.nextGaussian())

    override fun nextUniform(): Double = if (mirroring) 1.0 - replay() else record(base.nextUniform())

    private fun record(value: Double): Double {
        if (size == draws.size) draws = draws.copyOf(size * 2)
        draws[size++] = value
        return value
    }

    private fun replay(): Double {
        check(cursor < size) { "the mirrored path drew more numbers than the original" }
        return draws[cursor++]
    }
}

/**
 * A Poisson(mean) count from a single uniform by inverting the CDF. One uniform per call keeps
 * the draw sequence fixed, which antithetic mirroring relies on. Meant for small means (λ·dt).
 */
fun poissonFromUniform(u: Double, mean: Double): Int {
    require(mean >= 0.0 && mean < 700.0) { "Poisson mean $mean is outside the supported range [0, 700)" }
    var k = 0
    var p = exp(-mean)
    var cdf = p
    while (u > cdf && p > 0.0) {
        k++
        p *= mean / k
        cdf += p
    }
    return k
}
