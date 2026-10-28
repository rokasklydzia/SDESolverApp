package sde.model

/** A one-step discretisation of a [ScalarSde]. */
sealed interface Scheme {
    val label: String

    /** X(t + dt) from X(t) = x and the Brownian increment dW ~ N(0, dt). */
    fun step(sde: ScalarSde, t: Double, x: Double, dt: Double, dW: Double): Double
}

/** Euler–Maruyama: strong order 0.5, weak order 1. */
data object EulerMaruyama : Scheme {
    override val label = "Euler–Maruyama"

    override fun step(sde: ScalarSde, t: Double, x: Double, dt: Double, dW: Double): Double =
        x + sde.drift(t, x) * dt + sde.diffusion(t, x) * dW
}

/** Milstein: adds ½σσ'(dW² − dt), which raises the strong order to 1. */
data object Milstein : Scheme {
    override val label = "Milstein"

    override fun step(sde: ScalarSde, t: Double, x: Double, dt: Double, dW: Double): Double =
        x + sde.drift(t, x) * dt + sde.diffusion(t, x) * dW +
            0.5 * sde.diffusionTimesSlope(t, x) * (dW * dW - dt)
}

/** The exact transition, for models that implement [ExactTransition]. No discretisation error. */
data object Exact : Scheme {
    override val label = "Exact"

    override fun step(sde: ScalarSde, t: Double, x: Double, dt: Double, dW: Double): Double {
        val exact = sde as? ExactTransition ?: throw IllegalArgumentException("${sde::class.simpleName} has no exact transition")
        return exact.transition(t, x, dt, dW)
    }
}
