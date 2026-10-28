package sde.montecarlo

import kotlin.math.floor
import kotlin.math.sqrt

/** Streaming mean and variance (Welford), mergeable across threads (Chan, Golub and LeVeque). */
class RunningStats {
    var count = 0L
        private set
    var mean = 0.0
        private set
    private var m2 = 0.0

    fun add(x: Double) {
        count++
        val delta = x - mean
        mean += delta / count
        m2 += delta * (x - mean)
    }

    fun merge(other: RunningStats) {
        if (other.count == 0L) return
        if (count == 0L) {
            count = other.count; mean = other.mean; m2 = other.m2
            return
        }
        val n = count + other.count
        val delta = other.mean - mean
        mean += delta * other.count / n
        m2 += other.m2 + delta * delta * count.toDouble() * other.count / n
        count = n
    }

    /** The unbiased sample variance (divides by n − 1). */
    val variance: Double get() = if (count > 1) m2 / (count - 1) else 0.0
}

/** A Monte Carlo estimate with its standard error and a 95% confidence interval. */
data class Estimate(val mean: Double, val standardError: Double, val samples: Long) {
    val ci95Low: Double get() = mean - Z_95 * standardError
    val ci95High: Double get() = mean + Z_95 * standardError

    /** How many standard errors the estimate lies from [value]. */
    fun zScore(value: Double): Double = (mean - value) / standardError

    companion object {
        /** Φ⁻¹(0.975). */
        const val Z_95 = 1.959963984540054

        fun of(values: DoubleArray): Estimate {
            val stats = RunningStats()
            for (v in values) stats.add(v)
            return Estimate(stats.mean, sqrt(stats.variance / stats.count), stats.count)
        }
    }
}

/**
 * The p-quantile of [sorted] values by linear interpolation between order statistics, the
 * definition R uses by default (type 7) and numpy uses as "linear".
 */
fun quantileOfSorted(sorted: DoubleArray, p: Double): Double {
    require(sorted.isNotEmpty()) { "no values" }
    require(p in 0.0..1.0) { "p must lie in [0, 1]" }
    val h = (sorted.size - 1) * p
    val lo = floor(h).toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
}

/** A histogram over [min, max] with equal-width bins, normalised so the bars integrate to 1. */
class Histogram(val min: Double, val max: Double, val counts: IntArray, val total: Int) {
    val bins: Int get() = counts.size
    val binWidth: Double get() = (max - min) / bins

    fun binCenter(i: Int): Double = min + (i + 0.5) * binWidth

    /** The height of bar i as a probability density, comparable with a theoretical pdf. */
    fun density(i: Int): Double = counts[i] / (total * binWidth)

    companion object {
        /** Values outside [min, max] are counted in [total] but not drawn. */
        fun of(values: DoubleArray, bins: Int, min: Double = values.min(), max: Double = values.max()): Histogram {
            require(bins > 0) { "a histogram needs at least one bin" }
            val hi = if (max > min) max else min + 1.0
            val counts = IntArray(bins)
            val width = (hi - min) / bins
            for (v in values) {
                if (v < min || v > hi) continue
                counts[minOf(((v - min) / width).toInt(), bins - 1)]++
            }
            return Histogram(min, hi, counts, values.size)
        }
    }
}
