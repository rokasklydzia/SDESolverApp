package sde.catalog

import sde.model.ArithmeticBrownianMotion
import sde.model.BrownianBridge
import sde.model.CoxIngersollRoss
import sde.model.EulerMaruyama
import sde.model.GeometricBrownianMotion
import sde.model.Heston
import sde.model.MertonJumpDiffusion
import sde.model.OrnsteinUhlenbeck
import sde.model.ScalarProcess
import sde.model.ScaledRandomWalk
import sde.model.Scheme
import sde.model.StochasticProcess

/** One slider of a model: its key, how it is written, and its range. */
data class Parameter(
    val key: String,
    val symbol: String,
    val description: String,
    val default: Double,
    val min: Double,
    val max: Double,
)

/**
 * A preset the app offers. [equation] is LaTeX for the formula shown above the chart.
 * [create] builds the process from slider values; missing keys take their defaults.
 */
class PresetModel(
    val id: String,
    val name: String,
    val equation: String,
    val summary: String,
    val parameters: List<Parameter>,
    /** False for models with their own built-in scheme (Heston, Merton, random walk). */
    val supportsSchemeChoice: Boolean,
    private val factory: (Values, Scheme) -> StochasticProcess,
) {
    fun create(values: Map<String, Double> = emptyMap(), scheme: Scheme = EulerMaruyama): StochasticProcess =
        factory(Values(values, parameters), scheme)

    class Values(private val values: Map<String, Double>, private val parameters: List<Parameter>) {
        operator fun get(key: String): Double =
            values[key] ?: parameters.firstOrNull { it.key == key }?.default ?: throw IllegalArgumentException("no parameter $key")
    }
}

object ModelCatalog {
    private val x0 = Parameter("x0", "x_0", "starting value", 0.0, -5.0, 5.0)
    private val s0 = Parameter("s0", "S_0", "starting price", 100.0, 1.0, 500.0)

    val brownianMotion = PresetModel(
        id = "brownian-motion",
        name = "Brownian motion",
        equation = "dX_t = \\mu\\,dt + \\sigma\\,dW_t",
        summary = "The building block of stochastic calculus: independent normal increments with variance proportional to time.",
        parameters = listOf(
            x0,
            Parameter("mu", "\\mu", "drift", 0.0, -2.0, 2.0),
            Parameter("sigma", "\\sigma", "volatility", 1.0, 0.01, 3.0),
        ),
        supportsSchemeChoice = true,
    ) { v, scheme -> ScalarProcess(ArithmeticBrownianMotion(v["mu"], v["sigma"]), v["x0"], scheme) }

    val randomWalk = PresetModel(
        id = "random-walk",
        name = "Random walk",
        equation = "X_{t+\\Delta t} = X_t \\pm \\sigma\\sqrt{\\Delta t}",
        summary = "Coin flips scaled by √Δt. As the step shrinks it becomes Brownian motion (Donsker's theorem).",
        parameters = listOf(x0, Parameter("sigma", "\\sigma", "step scale", 1.0, 0.01, 3.0)),
        supportsSchemeChoice = false,
    ) { v, _ -> ScaledRandomWalk(v["x0"], v["sigma"]) }

    val geometricBrownianMotion = PresetModel(
        id = "gbm",
        name = "Geometric Brownian motion",
        equation = "dS_t = \\mu S_t\\,dt + \\sigma S_t\\,dW_t",
        summary = "The Black–Scholes stock price: log returns are normal, so prices stay positive.",
        parameters = listOf(
            s0,
            Parameter("mu", "\\mu", "expected return", 0.05, -0.5, 0.5),
            Parameter("sigma", "\\sigma", "volatility", 0.2, 0.01, 1.0),
        ),
        supportsSchemeChoice = true,
    ) { v, scheme -> ScalarProcess(GeometricBrownianMotion(v["mu"], v["sigma"]), v["s0"], scheme, "S") }

    val ornsteinUhlenbeck = PresetModel(
        id = "ou",
        name = "Ornstein–Uhlenbeck / Vasicek",
        equation = "dX_t = \\kappa(m - X_t)\\,dt + \\sigma\\,dW_t",
        summary = "Mean reversion: pulled back towards m at speed κ. As a short rate it is the Vasicek model.",
        parameters = listOf(
            Parameter("x0", "x_0", "starting value", 1.0, -5.0, 5.0),
            Parameter("kappa", "\\kappa", "speed of mean reversion", 1.0, 0.01, 10.0),
            Parameter("m", "m", "long-run mean", 0.0, -5.0, 5.0),
            Parameter("sigma", "\\sigma", "volatility", 0.3, 0.01, 3.0),
        ),
        supportsSchemeChoice = true,
    ) { v, scheme -> ScalarProcess(OrnsteinUhlenbeck(v["kappa"], v["m"], v["sigma"]), v["x0"], scheme) }

    val coxIngersollRoss = PresetModel(
        id = "cir",
        name = "Cox–Ingersoll–Ross",
        equation = "dr_t = \\kappa(\\theta - r_t)\\,dt + \\sigma\\sqrt{r_t}\\,dW_t",
        summary = "A mean-reverting interest rate that stays non-negative; it never hits zero when 2κθ ≥ σ².",
        parameters = listOf(
            Parameter("r0", "r_0", "starting rate", 0.03, 0.0, 0.2),
            Parameter("kappa", "\\kappa", "speed of mean reversion", 2.0, 0.01, 10.0),
            Parameter("theta", "\\theta", "long-run rate", 0.04, 0.0, 0.2),
            Parameter("sigma", "\\sigma", "volatility", 0.2, 0.01, 1.0),
        ),
        supportsSchemeChoice = true,
    ) { v, scheme -> ScalarProcess(CoxIngersollRoss(v["kappa"], v["theta"], v["sigma"]), v["r0"], scheme, "r") }

    val brownianBridge = PresetModel(
        id = "bridge",
        name = "Brownian bridge",
        equation = "dX_t = \\frac{b - X_t}{T - t}\\,dt + \\sigma\\,dW_t",
        summary = "Brownian motion conditioned to end at b at time T.",
        parameters = listOf(
            x0,
            Parameter("b", "b", "end point", 0.0, -5.0, 5.0),
            Parameter("T", "T", "time it must arrive", 1.0, 0.1, 10.0),
            Parameter("sigma", "\\sigma", "volatility", 1.0, 0.01, 3.0),
        ),
        supportsSchemeChoice = true,
    ) { v, scheme -> ScalarProcess(BrownianBridge(v["b"], v["T"], v["sigma"]), v["x0"], scheme) }

    val heston = PresetModel(
        id = "heston",
        name = "Heston stochastic volatility",
        equation = "dS_t = \\mu S_t\\,dt + \\sqrt{v_t}S_t\\,dW^1_t,\\quad dv_t = \\kappa(\\theta - v_t)\\,dt + \\xi\\sqrt{v_t}\\,dW^2_t",
        summary = "The variance itself is random and mean reverting; ρ < 0 makes prices fall when volatility rises.",
        parameters = listOf(
            s0,
            Parameter("v0", "v_0", "starting variance", 0.04, 0.0, 0.5),
            Parameter("mu", "\\mu", "expected return", 0.05, -0.5, 0.5),
            Parameter("kappa", "\\kappa", "speed of mean reversion", 2.0, 0.01, 10.0),
            Parameter("theta", "\\theta", "long-run variance", 0.04, 0.0, 0.5),
            Parameter("xi", "\\xi", "volatility of variance", 0.3, 0.01, 2.0),
            Parameter("rho", "\\rho", "correlation", -0.7, -1.0, 1.0),
        ),
        supportsSchemeChoice = false,
    ) { v, _ -> Heston(v["s0"], v["v0"], v["mu"], v["kappa"], v["theta"], v["xi"], v["rho"]) }

    val merton = PresetModel(
        id = "merton",
        name = "Merton jump diffusion",
        equation = "\\frac{dS_t}{S_{t^-}} = (\\mu - \\lambda k)\\,dt + \\sigma\\,dW_t + (J - 1)\\,dN_t",
        summary = "Geometric Brownian motion plus sudden jumps arriving at rate λ, with log J ~ N(m, δ²).",
        parameters = listOf(
            s0,
            Parameter("mu", "\\mu", "expected return", 0.05, -0.5, 0.5),
            Parameter("sigma", "\\sigma", "volatility", 0.2, 0.01, 1.0),
            Parameter("lambda", "\\lambda", "jumps per year", 1.0, 0.0, 20.0),
            Parameter("m", "m", "mean log jump", -0.1, -1.0, 1.0),
            Parameter("delta", "\\delta", "log jump volatility", 0.15, 0.0, 1.0),
        ),
        supportsSchemeChoice = false,
    ) { v, _ -> MertonJumpDiffusion(v["s0"], v["mu"], v["sigma"], v["lambda"], v["m"], v["delta"]) }

    val all: List<PresetModel> = listOf(
        brownianMotion, randomWalk, geometricBrownianMotion, ornsteinUhlenbeck,
        coxIngersollRoss, brownianBridge, heston, merton,
    )

    fun byId(id: String): PresetModel? = all.firstOrNull { it.id == id }
}
