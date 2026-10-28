package sde.ito

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import sde.expr.Evaluator
import sde.expr.Expr
import sde.expr.Parser
import sde.model.CustomSde
import sde.model.solveAlong
import sde.montecarlo.MonteCarlo
import sde.montecarlo.RunningStats
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import sde.random.SplitMix64
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Two processes U = f(t, X) and V = g(t, Y), where dX = μ_X dt + σ_X dB and dY = μ_Y dt + σ_Y dB
 * are driven by the same Brownian motion B. By Itô's formula dU = … dt + σ_X f_x dB and
 * dV = … dt + σ_Y g_y dB, so d⟨U, V⟩ = σ_X f_x(t, X) · σ_Y g_y(t, Y) dt, and the sums Σ ΔU·ΔV
 * converge to its integral path by path.
 */
class CovariationSetup(
    xDrift: String,
    xDiffusion: String,
    val x0: Double,
    fText: String,
    yDrift: String,
    yDiffusion: String,
    val y0: Double,
    gText: String,
) {
    val x = CustomSde(xDrift, xDiffusion)
    val y = CustomSde(yDrift, yDiffusion)
    val f: Expr = Parser.parse(fText)
    val g: Expr = Parser.parse(gText)

    /** The dB coefficient of dU, a formula in t and X. */
    val uNoise: Expr = ItoFormula.apply(f, x.driftExpr, x.diffusionExpr).dB

    /** The dB coefficient of dV, a formula in t and Y. */
    val vNoise: Expr = ItoFormula.apply(g, y.driftExpr, y.diffusionExpr).dB

    private val fCompiled = Evaluator.compile(f)
    private val gCompiled = Evaluator.compile(g)
    private val uNoiseCompiled = Evaluator.compile(uNoise)
    private val vNoiseCompiled = Evaluator.compile(vNoise)

    /** U, V and the limit of their covariation along one Brownian path w on [grid]. */
    fun along(grid: TimeGrid, w: DoubleArray): CovariationPath {
        val xs = x.solveAlong(grid, x0, w)
        val ys = y.solveAlong(grid, y0, w)
        val rate = DoubleArray(w.size) { val t = grid.time(it); uNoiseCompiled.eval(t, xs[it]) * vNoiseCompiled.eval(t, ys[it]) }
        return CovariationPath(
            grid = grid,
            u = fCompiled.alongPath(grid, xs),
            v = gCompiled.alongPath(grid, ys),
            limit = ItoSums.timeIntegral(rate, grid),
        )
    }

    /**
     * E⟨U, V⟩(t) over [paths] Brownian paths, with its standard error, for comparing with a
     * closed form such as E⟨B⁴, B²⟩ = 8t³. Path p uses the same stream as in [MonteCarlo.run].
     */
    suspend fun expectedLimit(
        grid: TimeGrid,
        paths: Int,
        seed: Long = 1L,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Pair<DoubleArray, DoubleArray> {
        require(paths > 1) { "at least two paths, for a standard error" }
        val chunkSize = MonteCarlo.CHUNK_SIZE
        val chunks = coroutineScope {
            (0 until (paths + chunkSize - 1) / chunkSize).map { index ->
                async(dispatcher) {
                    val random = SeededRandom(SplitMix64.streamSeed(seed, index))
                    val stats = Array(grid.steps + 1) { RunningStats() }
                    repeat(minOf(chunkSize, paths - index * chunkSize)) { p ->
                        if (p % 64 == 0) coroutineContext.ensureActive()
                        val limit = along(grid, ItoSums.brownianPath(grid, random)).limit
                        for (i in limit.indices) stats[i].add(limit[i])
                    }
                    stats
                }
            }.awaitAll()
        }
        val stats = Array(grid.steps + 1) { RunningStats() }
        for (chunk in chunks) for (i in stats.indices) stats[i].merge(chunk[i])
        return DoubleArray(stats.size) { stats[it].mean } to
            DoubleArray(stats.size) { sqrt(stats[it].variance / stats[it].count) }
    }
}

/** U and V on one path, and ∫ d⟨U, V⟩ computed from Itô's formula. */
class CovariationPath(val grid: TimeGrid, val u: DoubleArray, val v: DoubleArray, val limit: DoubleArray) {
    /** Σ ΔU·ΔV on a mesh [every] times coarser than [grid]. */
    fun realized(every: Int = 1): DoubleArray = ItoSums.covariation(ItoSums.coarsen(u, every), ItoSums.coarsen(v, every))
}
