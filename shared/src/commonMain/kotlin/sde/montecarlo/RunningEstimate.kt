package sde.montecarlo

import kotlin.math.sqrt

/**
 * A Monte Carlo estimate as the samples come in: the mean and standard error after the first
 * [paths]ᵢ paths, for watching a confidence interval shrink like 1/√N.
 */
class RunningEstimate(
    /** paths[i] is the number of paths behind mean[i]; it rises evenly from 2 to all of them. */
    val paths: IntArray,
    val mean: DoubleArray,
    val standardError: DoubleArray,
) {
    val last: Estimate get() = Estimate(mean.last(), standardError.last(), paths.last().toLong())

    companion object {
        /**
         * Running estimates of E[values] at [points] + 1 evenly spaced path counts. With
         * [antithetic] the values come in mirror pairs, which are dependent, so the error is
         * computed over pair averages, as in [MonteCarloResult.expectation].
         */
        fun of(values: DoubleArray, antithetic: Boolean = false, points: Int = 200): RunningEstimate {
            val samples = if (antithetic) DoubleArray(values.size / 2) { 0.5 * (values[2 * it] + values[2 * it + 1]) } else values
            val perSample = if (antithetic) 2 else 1
            require(samples.size >= 2) { "at least two samples" }
            val counts = IntArray(points + 1) { maxOf(2, (samples.size.toLong() * it / points).toInt()) }
            val mean = DoubleArray(points + 1)
            val standardError = DoubleArray(points + 1)
            val stats = RunningStats()
            var added = 0
            for ((i, count) in counts.withIndex()) {
                while (added < count) stats.add(samples[added++])
                mean[i] = stats.mean
                standardError[i] = sqrt(stats.variance / stats.count)
            }
            return RunningEstimate(IntArray(points + 1) { counts[it] * perSample }, mean, standardError)
        }
    }
}
