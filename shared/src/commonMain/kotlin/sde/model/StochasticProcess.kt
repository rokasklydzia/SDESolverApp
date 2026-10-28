package sde.model

import sde.analytic.lognormalPdf
import sde.analytic.Normal
import sde.random.RandomSource
import sde.random.poissonFromUniform
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Anything that can be simulated forward on a time grid: a scalar SDE with a scheme, or a
 * multi-factor model such as Heston. The state may have several components; the first one is
 * the one charted and summarised by default.
 */
interface StochasticProcess {
    /** Display names of the state components, e.g. ["S", "v"] for Heston. */
    val components: List<String>

    /** The state at time 0. Returns a new array each call. */
    fun initialState(): DoubleArray

    /** Advances [state] in place from time t to t + dt. */
    fun advance(t: Double, dt: Double, state: DoubleArray, random: RandomSource)
}

/** A [ScalarSde] started at [x0] and stepped with [scheme]. Draws one normal per step. */
class ScalarProcess(
    val sde: ScalarSde,
    val x0: Double,
    val scheme: Scheme = EulerMaruyama,
    name: String = "X",
) : StochasticProcess {
    init {
        require(scheme != Exact || sde is ExactTransition) { "the Exact scheme needs a model with an exact transition" }
    }

    override val components = listOf(name)
    override fun initialState() = doubleArrayOf(x0)

    override fun advance(t: Double, dt: Double, state: DoubleArray, random: RandomSource) {
        val dW = sqrt(dt) * random.nextGaussian()
        state[0] = scheme.step(sde, t, state[0], dt, dW)
    }
}

/**
 * Symmetric random walk with steps ±σ√dt. By Donsker's theorem it converges to σW as dt → 0,
 * which is the demo of Brownian motion as a limit of coin flips. Draws one uniform per step.
 */
class ScaledRandomWalk(val x0: Double = 0.0, val sigma: Double = 1.0) : StochasticProcess, KnownMoments, KnownDensity {
    override val components = listOf("X")
    override fun initialState() = doubleArrayOf(x0)

    override fun advance(t: Double, dt: Double, state: DoubleArray, random: RandomSource) {
        val sign = if (random.nextUniform() < 0.5) -1.0 else 1.0
        state[0] += sign * sigma * sqrt(dt)
    }

    override fun mean(t: Double, x0: Double) = x0
    override fun variance(t: Double, x0: Double) = sigma * sigma * t

    /** The limit as dt → 0, N(x0, σ²t): the walk itself is discrete, which is what the demo shows. */
    override fun density(t: Double, x0: Double, x: Double) = Normal.pdf(x, x0, sigma * sqrt(t))
}

/**
 * Heston stochastic volatility:
 *   dS = μS dt + √v S dW₁,  dv = κ(θ − v) dt + ξ√v dW₂,  d⟨W₁, W₂⟩ = ρ dt.
 * S is stepped in logs (exact given v) and v by full-truncation Euler, so E[S(t)] = S0·e^{μt}
 * holds exactly even after discretisation. Draws two normals per step.
 */
class Heston(
    val s0: Double = 100.0,
    val v0: Double = 0.04,
    val mu: Double = 0.05,
    val kappa: Double = 2.0,
    val theta: Double = 0.04,
    val xi: Double = 0.3,
    val rho: Double = -0.7,
) : StochasticProcess {
    init { require(rho in -1.0..1.0) { "ρ must lie in [−1, 1]" } }

    override val components = listOf("S", "v")
    override fun initialState() = doubleArrayOf(s0, v0)

    override fun advance(t: Double, dt: Double, state: DoubleArray, random: RandomSource) {
        val z1 = random.nextGaussian()
        val z2 = rho * z1 + sqrt(1 - rho * rho) * random.nextGaussian()
        val v = max(state[1], 0.0)
        val sd = sqrt(v * dt)
        state[0] *= exp((mu - 0.5 * v) * dt + sd * z1)
        state[1] += kappa * (theta - v) * dt + xi * sd * z2
    }

    /** E[S(t)] = S0·e^{μt}. */
    fun meanPrice(t: Double) = s0 * exp(mu * t)

    /** E[v(t)] of the exact CIR variance process. */
    fun meanVariance(t: Double) = theta + (v0 - theta) * exp(-kappa * t)
}

/**
 * Merton jump diffusion: dS/S₋ = (μ − λk) dt + σ dW + (J − 1) dN, with N a Poisson process of
 * rate λ, log J ~ N(m, δ²) and k = E[J − 1] = e^{m + δ²/2} − 1, so E[S(t)] = S0·e^{μt}.
 * Stepped exactly: n jumps in a step add a N(n·m, n·δ²) log return. Draws normal, uniform, normal.
 */
class MertonJumpDiffusion(
    val s0: Double = 100.0,
    val mu: Double = 0.05,
    val sigma: Double = 0.2,
    val lambda: Double = 1.0,
    val jumpMean: Double = -0.1,
    val jumpVol: Double = 0.15,
) : StochasticProcess, KnownMoments, KnownDensity {
    init { require(lambda >= 0) { "λ cannot be negative" } }

    private val k = exp(jumpMean + 0.5 * jumpVol * jumpVol) - 1

    override val components = listOf("S")
    override fun initialState() = doubleArrayOf(s0)

    override fun advance(t: Double, dt: Double, state: DoubleArray, random: RandomSource) {
        val diffusive = (mu - lambda * k - 0.5 * sigma * sigma) * dt + sigma * sqrt(dt) * random.nextGaussian()
        val jumps = poissonFromUniform(random.nextUniform(), lambda * dt)
        val jumpZ = random.nextGaussian()
        val jumpReturn = if (jumps == 0) 0.0 else jumps * jumpMean + sqrt(jumps.toDouble()) * jumpVol * jumpZ
        state[0] *= exp(diffusive + jumpReturn)
    }

    override fun mean(t: Double, x0: Double) = x0 * exp(mu * t)

    override fun variance(t: Double, x0: Double): Double {
        val secondMoment = x0 * x0 * exp(
            2 * mu * t - 2 * lambda * k * t + sigma * sigma * t +
                lambda * t * (exp(2 * jumpMean + 2 * jumpVol * jumpVol) - 1),
        )
        val m = mean(t, x0)
        return secondMoment - m * m
    }

    /** A Poisson mixture of lognormals: given n jumps, log S(t) ~ N(log S0 + (μ − λk − σ²/2)t + n·m, σ²t + n·δ²). */
    override fun density(t: Double, x0: Double, x: Double): Double {
        if (x <= 0) return 0.0
        val mean = lambda * t
        var weight = exp(-mean)
        var total = 0.0
        var n = 0
        while (true) {
            val logMean = ln(x0) + (mu - lambda * k - 0.5 * sigma * sigma) * t + n * jumpMean
            val logSd = sqrt(sigma * sigma * t + n * jumpVol * jumpVol)
            total += weight * lognormalPdf(x, logMean, logSd)
            n++
            if (n > mean && weight < 1e-12 || n > 200) return total
            weight *= mean / n
        }
    }
}

/**
 * The exact density of the given component at time t > 0, or null when no closed form is known
 * (CIR, Heston, typed formulas). For a scheme with discretisation error this is the density the
 * simulation is converging to, not the simulation's own.
 */
fun StochasticProcess.marginalDensity(component: Int = 0): ((t: Double, x: Double) -> Double)? {
    if (component != 0) return null
    return when (this) {
        is ScalarProcess -> (sde as? KnownDensity)?.let { d -> { t, x -> d.density(t, x0, x) } }
        is ScaledRandomWalk -> { t, x -> density(t, x0, x) }
        is MertonJumpDiffusion -> { t, x -> density(t, s0, x) }
        else -> null
    }
}
