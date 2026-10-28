package sde.model

import sde.expr.CompiledExpr
import sde.expr.Derivative
import sde.expr.Evaluator
import sde.expr.Expr
import sde.expr.Names
import sde.expr.Parser
import sde.expr.names

/**
 * An SDE typed by the user: dX = μ(t, X) dt + σ(t, X) dW with μ and σ as formulas in t, x and
 * any named parameters, e.g. drift `kappa*(theta - x)` and diffusion `sigma*sqrt(x)`.
 * Milstein's ∂σ/∂x is derived symbolically. Throws [sde.expr.ExpressionException] for a bad formula.
 */
class CustomSde(
    val driftText: String,
    val diffusionText: String,
    val parameters: Map<String, Double> = emptyMap(),
) : ScalarSde {
    val driftExpr: Expr = Parser.parse(driftText)
    val diffusionExpr: Expr = Parser.parse(diffusionText)

    /** ∂σ/∂x, shown to the user next to the Milstein option. */
    val diffusionSlopeExpr: Expr = Derivative.dx(diffusionExpr)

    private val mu: CompiledExpr = Evaluator.compile(driftExpr, parameters)
    private val sigma: CompiledExpr = Evaluator.compile(diffusionExpr, parameters)
    private val sigmaSlope: CompiledExpr = Evaluator.compile(diffusionSlopeExpr, parameters)

    override fun drift(t: Double, x: Double) = mu.eval(t, x)
    override fun diffusion(t: Double, x: Double) = sigma.eval(t, x)
    override fun diffusionTimesSlope(t: Double, x: Double) = sigma.eval(t, x) * sigmaSlope.eval(t, x)

    /** The same formulas with new parameter values, e.g. after a slider moves. */
    fun withParameters(values: Map<String, Double>) = CustomSde(driftText, diffusionText, values)

    companion object {
        /** The parameter names the formulas use (everything except t, x and constants), for building sliders. */
        fun parameterNames(driftText: String, diffusionText: String): List<String> =
            (Parser.parse(driftText).names() + Parser.parse(diffusionText).names())
                .filter { it != Names.TIME && it !in Names.STATE }
                .distinct()
    }
}
