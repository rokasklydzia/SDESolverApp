package sde.model

import sde.analytic.Normal
import sde.analytic.lognormalPdf
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Brownian motion with drift: dX = μ dt + σ dW. */
class ArithmeticBrownianMotion(val mu: Double = 0.0, val sigma: Double = 1.0) :
    ScalarSde, KnownMoments, KnownDensity, PathwiseSolution, ExactTransition {
    override fun drift(t: Double, x: Double) = mu
    override fun diffusion(t: Double, x: Double) = sigma
    override fun diffusionTimesSlope(t: Double, x: Double) = 0.0
    override fun mean(t: Double, x0: Double) = x0 + mu * t
    override fun variance(t: Double, x0: Double) = sigma * sigma * t
    override fun density(t: Double, x0: Double, x: Double) = Normal.pdf(x, mean(t, x0), sqrt(variance(t, x0)))
    override fun solution(t: Double, x0: Double, w: Double) = x0 + mu * t + sigma * w
    override fun transition(t: Double, x: Double, dt: Double, dW: Double) = x + mu * dt + sigma * dW
}

/** Geometric Brownian motion: dS = μS dt + σS dW, the Black–Scholes stock price. */
class GeometricBrownianMotion(val mu: Double = 0.05, val sigma: Double = 0.2) :
    ScalarSde, KnownMoments, KnownDensity, PathwiseSolution, ExactTransition {
    override fun drift(t: Double, x: Double) = mu * x
    override fun diffusion(t: Double, x: Double) = sigma * x
    override fun diffusionTimesSlope(t: Double, x: Double) = sigma * sigma * x
    override fun mean(t: Double, x0: Double) = x0 * exp(mu * t)
    override fun variance(t: Double, x0: Double) = x0 * x0 * exp(2 * mu * t) * (exp(sigma * sigma * t) - 1)

    /** S(t) is lognormal: log S(t) ~ N(log S0 + (μ − σ²/2)t, σ²t). */
    override fun density(t: Double, x0: Double, x: Double) =
        lognormalPdf(x, ln(x0) + (mu - 0.5 * sigma * sigma) * t, sigma * sqrt(t))

    /** S(t) = S0·exp((μ − σ²/2)t + σW(t)), from Itô's lemma applied to log S. */
    override fun solution(t: Double, x0: Double, w: Double) = x0 * exp((mu - 0.5 * sigma * sigma) * t + sigma * w)
    override fun transition(t: Double, x: Double, dt: Double, dW: Double) = solution(dt, x, dW)
}

/**
 * Ornstein–Uhlenbeck: dX = κ(m − X) dt + σ dW. Mean reverting to m at speed κ; with X as the
 * short rate this is the Vasicek model.
 */
class OrnsteinUhlenbeck(val kappa: Double = 1.0, val longRunMean: Double = 0.0, val sigma: Double = 0.3) :
    ScalarSde, KnownMoments, KnownDensity, ExactTransition {
    init { require(kappa > 0) { "κ must be positive" } }

    override fun drift(t: Double, x: Double) = kappa * (longRunMean - x)
    override fun diffusion(t: Double, x: Double) = sigma
    override fun diffusionTimesSlope(t: Double, x: Double) = 0.0
    override fun mean(t: Double, x0: Double) = longRunMean + (x0 - longRunMean) * exp(-kappa * t)
    override fun variance(t: Double, x0: Double) = sigma * sigma / (2 * kappa) * (1 - exp(-2 * kappa * t))
    override fun density(t: Double, x0: Double, x: Double) = Normal.pdf(x, mean(t, x0), sqrt(variance(t, x0)))

    /** X(t + dt) | X(t) = x is normal with the mean and variance above; dW/√dt is the standard normal. */
    override fun transition(t: Double, x: Double, dt: Double, dW: Double): Double {
        val decay = exp(-kappa * dt)
        val sd = sigma * sqrt((1 - decay * decay) / (2 * kappa))
        return longRunMean + (x - longRunMean) * decay + sd * dW / sqrt(dt)
    }
}

/**
 * Cox–Ingersoll–Ross: dX = κ(θ − X) dt + σ√X dW, the CIR short-rate model. Discretised with full
 * truncation (Lord, Koekkoek and van Dijk, 2010): the coefficients see max(X, 0), so a path that
 * dips below zero is pulled back by the drift instead of crashing on √X.
 */
class CoxIngersollRoss(val kappa: Double = 2.0, val theta: Double = 0.04, val sigma: Double = 0.2) :
    ScalarSde, KnownMoments {
    init { require(kappa > 0) { "κ must be positive" } }

    /** The Feller condition 2κθ ≥ σ²: when it holds, the exact process never reaches zero. */
    val fellerConditionHolds: Boolean get() = 2 * kappa * theta >= sigma * sigma

    override fun drift(t: Double, x: Double) = kappa * (theta - max(x, 0.0))
    override fun diffusion(t: Double, x: Double) = sigma * sqrt(max(x, 0.0))

    /** σ√x · σ/(2√x) = σ²/2 for x > 0. */
    override fun diffusionTimesSlope(t: Double, x: Double) = if (x > 0) 0.5 * sigma * sigma else 0.0

    override fun mean(t: Double, x0: Double) = theta + (x0 - theta) * exp(-kappa * t)
    override fun variance(t: Double, x0: Double): Double {
        val e = exp(-kappa * t)
        return x0 * sigma * sigma / kappa * (e - e * e) + theta * sigma * sigma / (2 * kappa) * (1 - e) * (1 - e)
    }
}

/**
 * Brownian bridge from x0 at time 0 to [end] at time [horizon]: dX = (b − X)/(T − t) dt + σ dW.
 * The drift grows without bound near T, which is what pins the path to b.
 */
class BrownianBridge(val end: Double = 0.0, val horizon: Double = 1.0, val sigma: Double = 1.0) :
    ScalarSde, KnownMoments, KnownDensity {
    init { require(horizon > 0) { "the horizon T must be positive" } }

    override fun drift(t: Double, x: Double): Double {
        val remaining = horizon - t
        return if (remaining > 0) (end - x) / remaining else 0.0
    }

    override fun diffusion(t: Double, x: Double) = sigma
    override fun diffusionTimesSlope(t: Double, x: Double) = 0.0
    override fun mean(t: Double, x0: Double) = x0 + (end - x0) * t / horizon
    override fun variance(t: Double, x0: Double) = sigma * sigma * t * (horizon - t) / horizon
    override fun density(t: Double, x0: Double, x: Double) = Normal.pdf(x, mean(t, x0), sqrt(variance(t, x0)))
}
