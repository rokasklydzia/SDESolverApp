package sde.montecarlo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import sde.model.StochasticProcess
import sde.random.AntitheticSource
import sde.random.RandomSource
import sde.random.SeededRandom
import sde.random.SplitMix64
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/** What to simulate and what to keep. */
data class MonteCarloSettings(
    val grid: TimeGrid,
    val paths: Int,
    val seed: Long = 1L,
    /** Pair every path with its mirror image (Z → −Z). Needs an even number of paths. */
    val antithetic: Boolean = false,
    /** How many whole paths to keep for charts. All paths still feed the statistics. */
    val keepPaths: Int = 100,
    /** The state component that statistics are collected for. */
    val component: Int = 0,
) {
    init {
        require(paths > 0) { "at least one path" }
        require(!antithetic || paths % 2 == 0) { "antithetic sampling needs an even number of paths" }
        require(keepPaths >= 0) { "keepPaths cannot be negative" }
    }
}

class MonteCarloResult(
    val settings: MonteCarloSettings,
    /** E[X(tᵢ)] estimated at every grid time. */
    val meanPath: DoubleArray,
    /** Var[X(tᵢ)] estimated at every grid time. */
    val variancePath: DoubleArray,
    /** X(T) of every path, in path order (mirror pairs adjacent when antithetic). */
    val terminalValues: DoubleArray,
    /** The first settings.keepPaths paths, all components. */
    val keptPaths: List<Path>,
    val elapsed: Duration,
) {
    val grid: TimeGrid get() = settings.grid

    /** E[f(X(T))] with a standard error that respects antithetic pairing. */
    fun expectation(f: (Double) -> Double): Estimate {
        val values = terminalValues
        if (!settings.antithetic) return Estimate.of(DoubleArray(values.size) { f(values[it]) })
        // Mirror pairs are dependent; their averages are independent, so the error is computed over pairs.
        return Estimate.of(DoubleArray(values.size / 2) { 0.5 * (f(values[2 * it]) + f(values[2 * it + 1])) })
    }

    /** E[X(T)]. */
    fun terminalMean(): Estimate = expectation { it }

    fun terminalQuantile(p: Double): Double = quantileOfSorted(sortedTerminal, p)

    fun terminalHistogram(bins: Int = 50): Histogram = Histogram.of(terminalValues, bins)

    private val sortedTerminal: DoubleArray by lazy { terminalValues.copyOf().also { it.sort() } }
}

object MonteCarlo {
    /**
     * Paths are simulated in fixed-size chunks, each with its own random stream derived from the
     * seed, and the chunks are merged in order. So a seed gives the same result whatever the
     * number of CPU cores.
     */
    const val CHUNK_SIZE = 512

    suspend fun run(
        process: StochasticProcess,
        settings: MonteCarloSettings,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): MonteCarloResult {
        require(settings.component in process.components.indices) { "no component ${settings.component}" }
        val mark = TimeSource.Monotonic.markNow()
        val chunkCount = (settings.paths + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunks = coroutineScope {
            (0 until chunkCount).map { index ->
                async(dispatcher) { runChunk(process, settings, index) }
            }.awaitAll()
        }

        val steps = settings.grid.steps
        val stats = Array(steps + 1) { RunningStats() }
        val terminal = DoubleArray(settings.paths)
        val kept = ArrayList<Path>(minOf(settings.keepPaths, settings.paths))
        var offset = 0
        for (chunk in chunks) {
            for (i in 0..steps) stats[i].merge(chunk.stats[i])
            chunk.terminal.copyInto(terminal, offset)
            offset += chunk.terminal.size
            kept += chunk.kept
        }
        return MonteCarloResult(
            settings = settings,
            meanPath = DoubleArray(steps + 1) { stats[it].mean },
            variancePath = DoubleArray(steps + 1) { stats[it].variance },
            terminalValues = terminal,
            keptPaths = kept,
            elapsed = mark.elapsedNow(),
        )
    }

    private class Chunk(val stats: Array<RunningStats>, val terminal: DoubleArray, val kept: List<Path>)

    private suspend fun runChunk(process: StochasticProcess, settings: MonteCarloSettings, index: Int): Chunk {
        val grid = settings.grid
        val first = index * CHUNK_SIZE
        val count = minOf(CHUNK_SIZE, settings.paths - first)
        val base = SeededRandom(SplitMix64.streamSeed(settings.seed, index))
        val antithetic = if (settings.antithetic) AntitheticSource(base) else null
        val random: RandomSource = antithetic ?: base
        val c = settings.component
        val stats = Array(grid.steps + 1) { RunningStats() }
        val terminal = DoubleArray(count)
        val kept = ArrayList<Path>()

        for (p in 0 until count) {
            if (p % 64 == 0) coroutineContext.ensureActive()
            if (antithetic != null) {
                if (p % 2 == 0) antithetic.beginOriginal() else antithetic.beginMirror()
            }
            if (first + p < settings.keepPaths) {
                val path = process.simulatePath(grid, random)
                val values = path.values[c]
                for (i in 0..grid.steps) stats[i].add(values[i])
                terminal[p] = values[grid.steps]
                kept += path
            } else {
                val state = process.initialState()
                stats[0].add(state[c])
                for (i in 0 until grid.steps) {
                    process.advance(grid.time(i), grid.dt, state, random)
                    stats[i + 1].add(state[c])
                }
                terminal[p] = state[c]
            }
        }
        return Chunk(stats, terminal, kept)
    }
}
