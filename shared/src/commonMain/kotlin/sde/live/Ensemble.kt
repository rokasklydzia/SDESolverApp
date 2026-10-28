package sde.live

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import sde.model.StochasticProcess
import sde.montecarlo.Histogram
import sde.montecarlo.MonteCarlo
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import sde.random.SplitMix64
import kotlin.coroutines.coroutineContext

/**
 * Many simulated paths of one state component, kept whole for drawing and animation, with the
 * mean and quantile bands at every grid time. Values are Floats to halve the memory: 2,000 paths
 * of 1,000 steps take 8 MB, and a screen cannot show more precision anyway.
 *
 * Path p uses the same random stream as path p of [MonteCarlo.run] with the same seed, so the
 * paths on screen are exactly the ones the statistics are computed from.
 */
class Ensemble internal constructor(
    val grid: TimeGrid,
    val paths: Int,
    /** values[p * (grid.steps + 1) + i] is path p at grid.time(i). */
    private val values: FloatArray,
    /** E[X(tᵢ)] over all paths. */
    val mean: FloatArray,
    /** The quantile levels of [bands], e.g. 0.05 and 0.95. */
    val quantileLevels: DoubleArray,
    /** bands[q][i] is the quantileLevels[q] quantile of X(tᵢ) over all paths. */
    val bands: Array<FloatArray>,
) {
    private val stride = grid.steps + 1

    fun value(path: Int, step: Int): Float = values[path * stride + step]

    /** X(tᵢ) for every path, for a histogram at time tᵢ. */
    fun column(step: Int): DoubleArray = DoubleArray(paths) { values[it * stride + step].toDouble() }

    fun histogram(step: Int, bins: Int, min: Double, max: Double): Histogram = Histogram.of(column(step), bins, min, max)

    /** The smallest and largest value the bands reach over the whole horizon, for fitting the axes. */
    fun bandRange(): ClosedFloatingPointRange<Double> {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (band in bands) for (v in band) { lo = minOf(lo, v.toDouble()); hi = maxOf(hi, v.toDouble()) }
        for (v in mean) { lo = minOf(lo, v.toDouble()); hi = maxOf(hi, v.toDouble()) }
        return lo..hi
    }

    companion object {
        val DEFAULT_LEVELS = doubleArrayOf(0.05, 0.25, 0.75, 0.95)

        suspend fun simulate(
            process: StochasticProcess,
            grid: TimeGrid,
            paths: Int,
            seed: Long = 1L,
            component: Int = 0,
            quantileLevels: DoubleArray = DEFAULT_LEVELS,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): Ensemble {
            require(paths > 0) { "at least one path" }
            require(component in process.components.indices) { "no component $component" }
            val stride = grid.steps + 1
            val values = FloatArray(paths * stride)
            val chunkSize = MonteCarlo.CHUNK_SIZE
            val chunks = (paths + chunkSize - 1) / chunkSize

            coroutineScope {
                (0 until chunks).map { chunk ->
                    async(dispatcher) {
                        val random = SeededRandom(SplitMix64.streamSeed(seed, chunk))
                        val first = chunk * chunkSize
                        for (p in first until minOf(first + chunkSize, paths)) {
                            coroutineContext.ensureActive()
                            val state = process.initialState()
                            val offset = p * stride
                            values[offset] = state[component].toFloat()
                            for (i in 0 until grid.steps) {
                                process.advance(grid.time(i), grid.dt, state, random)
                                values[offset + i + 1] = state[component].toFloat()
                            }
                        }
                    }
                }.awaitAll()
            }

            // Mean and quantiles per time step, columns split across cores.
            val mean = FloatArray(stride)
            val bands = Array(quantileLevels.size) { FloatArray(stride) }
            val columnChunk = 64
            coroutineScope {
                (0 until stride step columnChunk).map { start ->
                    async(dispatcher) {
                        val column = FloatArray(paths)
                        for (i in start until minOf(start + columnChunk, stride)) {
                            var sum = 0.0
                            for (p in 0 until paths) {
                                val v = values[p * stride + i]
                                column[p] = v
                                sum += v
                            }
                            mean[i] = (sum / paths).toFloat()
                            column.sort()
                            for ((q, level) in quantileLevels.withIndex()) bands[q][i] = quantile(column, level)
                        }
                    }
                }.awaitAll()
            }
            return Ensemble(grid, paths, values, mean, quantileLevels, bands)
        }

        /** Type 7 quantile of sorted Floats, as in [sde.montecarlo.quantileOfSorted]. */
        private fun quantile(sorted: FloatArray, p: Double): Float {
            val h = (sorted.size - 1) * p
            val lo = h.toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return (sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])).toFloat()
        }
    }
}
