package sde.ito

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import sde.expr.Derivative
import sde.expr.Evaluator
import sde.expr.Expr
import sde.expr.Exprs.add
import sde.expr.Exprs.mul
import sde.expr.Exprs.neg
import sde.expr.Exprs.num
import sde.expr.Exprs.pow
import sde.expr.Names
import sde.expr.Parser
import sde.expr.names
import sde.model.CustomSde
import sde.model.EulerMaruyama
import sde.model.Scheme
import sde.montecarlo.MonteCarlo
import sde.montecarlo.RunningStats
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import sde.random.SplitMix64
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Is M(t) = f(t, X(t)) + ∫₀ᵗ g(s, X(s)) ds a martingale, for dX = μ(t, X) dt + σ(t, X) dB?
 *
 * By Itô's formula dM = (f_t + μ f_x + ½σ² f_xx + g) dt + σ f_x dB, so M is a local martingale
 * exactly when the dt term vanishes, and g = −(f_t + μ f_x + ½σ² f_xx) is the compensator that
 * makes f(t, X) into one. The check is symbolic first and then numerical: [simulate] estimates
 * E[M(t)] over many paths, which must stay at M(0) within its standard error.
 */
class MartingaleCheck(
    val driftText: String,
    val diffusionText: String,
    val fText: String,
    val compensatorText: String = "0",
    val parameters: Map<String, Double> = emptyMap(),
) {
    val sde = CustomSde(driftText, diffusionText, parameters)
    val f: Expr = Parser.parse(fText)
    val compensator: Expr = Parser.parse(compensatorText)

    /** dM without the compensator, i.e. Itô's formula for f(t, X). */
    val differential: ItoDifferential = ItoFormula.apply(f, sde.driftExpr, sde.diffusionExpr)

    /** The dt term of dM, which must vanish. */
    val driftOfM: Expr = add(differential.dt, compensator)

    /** The g that makes M a martingale: −(f_t + μ f_x + ½σ² f_xx). */
    val requiredCompensator: Expr = neg(differential.dt)

    private val fCompiled = Evaluator.compile(f, parameters)
    private val gCompiled = Evaluator.compile(compensator, parameters)

    /** The four parts of the dt term, kept apart so their sizes give a scale for "zero". */
    private val driftParts = run {
        val fx = Derivative.dx(f)
        listOf(
            Derivative.dt(f),
            mul(sde.driftExpr, fx),
            mul(mul(num(0.5), pow(sde.diffusionExpr, num(2.0))), Derivative.dx(fx)),
            compensator,
        ).map { Evaluator.compile(it, parameters) }
    }

    /**
     * The largest |dt term of dM| over a 17 × 17 grid of t in [0, horizon] and x in [xRange],
     * divided by the largest of its parts there. Rounding leaves about 1e-16 when the dt term
     * vanishes identically but the formulas did not cancel symbolically, e.g. for sin²x + cos²x − 1.
     * Points where a formula is undefined (log of a negative number) are skipped.
     */
    fun relativeDrift(horizon: Double, xRange: ClosedFloatingPointRange<Double>): Double {
        var worst = 0.0
        var scale = 0.0
        val n = 16
        for (i in 0..n) for (j in 0..n) {
            val t = horizon * i / n
            val x = xRange.start + (xRange.endInclusive - xRange.start) * j / n
            var sum = 0.0
            var size = 0.0
            for (part in driftParts) {
                val v = part.eval(t, x)
                sum += v
                size = max(size, abs(v))
            }
            if (!sum.isFinite()) continue
            worst = max(worst, abs(sum))
            scale = max(scale, size)
        }
        return if (scale == 0.0) 0.0 else worst / scale
    }

    /** True when the dt term of dM is zero on the sampled (t, x) points, up to rounding. */
    fun driftVanishes(horizon: Double, xRange: ClosedFloatingPointRange<Double>): Boolean =
        relativeDrift(horizon, xRange) < 1e-9

    /**
     * Simulates M along [paths] paths of X from [x0], with ∫ g ds by the left point rule. Path p
     * draws from the same random stream as in [MonteCarlo.run] with the same seed. The first
     * [keepPaths] paths of M are kept for drawing.
     */
    suspend fun simulate(
        x0: Double,
        grid: TimeGrid,
        paths: Int,
        seed: Long = 1L,
        keepPaths: Int = 30,
        scheme: Scheme = EulerMaruyama,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): MartingaleRun {
        require(paths > 1) { "at least two paths, for a standard error" }
        val chunkSize = MonteCarlo.CHUNK_SIZE
        val chunkCount = (paths + chunkSize - 1) / chunkSize
        val chunks = coroutineScope {
            (0 until chunkCount).map { index ->
                async(dispatcher) {
                    val first = index * chunkSize
                    val count = minOf(chunkSize, paths - first)
                    val random = SeededRandom(SplitMix64.streamSeed(seed, index))
                    val stats = Array(grid.steps + 1) { RunningStats() }
                    val kept = ArrayList<DoubleArray>()
                    var lo = x0
                    var hi = x0
                    val sd = sqrt(grid.dt)
                    for (p in 0 until count) {
                        if (p % 64 == 0) coroutineContext.ensureActive()
                        val keep = first + p < keepPaths
                        val m = if (keep) DoubleArray(grid.steps + 1) else null
                        var x = x0
                        var integral = 0.0
                        for (i in 0..grid.steps) {
                            val t = grid.time(i)
                            val value = fCompiled.eval(t, x) + integral
                            stats[i].add(value)
                            m?.set(i, value)
                            if (i == grid.steps) break
                            integral += gCompiled.eval(t, x) * grid.dt
                            x = scheme.step(sde, t, x, grid.dt, sd * random.nextGaussian())
                            if (x < lo) lo = x
                            if (x > hi) hi = x
                        }
                        if (m != null) kept += m
                    }
                    Chunk(stats, kept, lo, hi)
                }
            }.awaitAll()
        }

        val stats = Array(grid.steps + 1) { RunningStats() }
        val kept = ArrayList<DoubleArray>()
        var lo = x0
        var hi = x0
        for (chunk in chunks) {
            for (i in 0..grid.steps) stats[i].merge(chunk.stats[i])
            kept += chunk.kept
            lo = minOf(lo, chunk.lo)
            hi = maxOf(hi, chunk.hi)
        }
        return MartingaleRun(
            grid = grid,
            paths = paths,
            mean = DoubleArray(grid.steps + 1) { stats[it].mean },
            standardError = DoubleArray(grid.steps + 1) { sqrt(stats[it].variance / stats[it].count) },
            keptPaths = kept,
            xRange = lo..hi,
        )
    }

    private class Chunk(val stats: Array<RunningStats>, val kept: List<DoubleArray>, val lo: Double, val hi: Double)

    companion object {
        /** The parameter names the four formulas use, for building sliders. */
        fun parameterNames(vararg texts: String): List<String> =
            texts.flatMap { Parser.parse(it).names() }
                .filter { it != Names.TIME && it !in Names.STATE && it !in Names.CONSTANTS }
                .distinct()
    }
}

/** E[M(tᵢ)] over many paths, with its standard error, and a few whole paths of M. */
class MartingaleRun(
    val grid: TimeGrid,
    val paths: Int,
    val mean: DoubleArray,
    val standardError: DoubleArray,
    val keptPaths: List<DoubleArray>,
    /** The smallest and largest X reached, where [MartingaleCheck.relativeDrift] should look. */
    val xRange: ClosedFloatingPointRange<Double>,
) {
    /** How many standard errors E[M(tᵢ)] lies from M(0). Beyond about ±3 at the end is evidence against a martingale. */
    fun zScore(step: Int): Double {
        val se = standardError[step]
        return if (se > 0) (mean[step] - mean[0]) / se else 0.0
    }
}
