package sde.ito

import sde.analytic.Quadrature
import sde.expr.Antiderivative
import sde.expr.Derivative
import sde.expr.Evaluator
import sde.expr.Expr
import sde.expr.Exprs
import sde.expr.Exprs.add
import sde.expr.Exprs.mul
import sde.expr.Exprs.num
import sde.expr.Exprs.pow
import sde.expr.Exprs.sub
import sde.expr.Names
import sde.expr.Parser
import sde.expr.Variable
import sde.expr.dependsOnState
import sde.expr.format
import sde.expr.substitute
import sde.expr.toTex
import sde.expr.toTexFactor
import sde.expr.withState
import sde.model.CustomSde
import sde.model.solveAlong
import sde.montecarlo.TimeGrid
import kotlin.math.sqrt

/**
 * The stochastic integral ∫₀ᵗ h(s, X_s) dX_s, with dX = μ dt + σ dB and X(0) = x0, evaluated at
 * the left point (Itô), the midpoint (Stratonovich) or the right point (backward).
 *
 * The closed form comes from running Itô's formula backwards. If F_x = h, then
 * dF(t, X) = (F_t + ½σ²F_xx) dt + h dX, so
 *
 *     ∫₀ᵗ h dX = F(t, X_t) − F(0, x0) − ∫₀ᵗ R(s, X_s) ds,   R = F_t + (½ − θ)σ² h_x,
 *
 * with θ = 0, ½, 1 for the three evaluation points: Stratonovich keeps the ordinary chain rule
 * and the backward integral flips the sign of the correction. When R does not involve X its
 * integral is found in closed form too; otherwise ∫R ds is left as a pathwise Riemann integral,
 * as on paper (∫B³dB = ¼B⁴ − (3/2)∫B² ds).
 */
class StochasticIntegral(
    val integrandText: String,
    val driftText: String = "0",
    val diffusionText: String = "1",
    val x0: Double = 0.0,
    val point: EvaluationPoint = EvaluationPoint.LEFT,
    val parameters: Map<String, Double> = emptyMap(),
) {
    val integrand: Expr = Parser.parse(integrandText)
    val sde = CustomSde(driftText, diffusionText, parameters)

    /** True when the integrator is B itself: μ = 0, σ = 1. */
    val isBrownian: Boolean = sde.driftExpr == Exprs.ZERO && sde.diffusionExpr == Exprs.ONE

    /** F with F_x = h, or null when [Antiderivative] has no rule for h. */
    val antiderivative: Expr? = Antiderivative.of(integrand, Variable.STATE)

    /** R in ∫h dX = F(t, X_t) − F(0, x0) − ∫R ds; null without F. */
    val remainder: Expr? = antiderivative?.let { f ->
        val correction = mul(mul(num(0.5 - point.weightOfRight), pow(sde.diffusionExpr, num(2.0))), Derivative.dx(integrand))
        add(Derivative.dt(f), correction)
    }

    /** ∫₀ᵗ R ds in closed form, a function of t, when R does not involve X and can be integrated. */
    val remainderIntegral: Expr? = remainder?.takeIf { !it.dependsOnState() }?.let { r ->
        Antiderivative.of(r, Variable.TIME)?.let { g -> sub(g, g.substitute(Names.TIME, Exprs.ZERO)) }
    }

    /** F(0, x0), the constant subtracted from F(t, X_t); null when F is undefined there (log 0). */
    val start: Double? = antiderivative?.let { Evaluator.evaluate(it, 0.0, x0, parameters) }?.takeIf { it.isFinite() }

    /**
     * Everything in the answer except a leftover ∫R ds: F(t, x) − F(0, x0), minus ∫R ds when that
     * has a closed form. A formula in t and x, with x standing for X_t.
     */
    val closedPart: Expr? = antiderivative?.let { f ->
        val shifted = sub(f, num(start ?: return@let null))
        remainderIntegral?.let { sub(shifted, it) } ?: shifted
    }

    /** R when ∫R ds stays a pathwise integral, i.e. when it involves X and is not zero. */
    val leftover: Expr? = remainder?.takeIf { remainderIntegral == null && it != Exprs.ZERO }

    /** True when the answer has no integral left in it. */
    val fullyClosed: Boolean get() = closedPart != null && leftover == null

    /** The answer as text, e.g. "0.5*x^2 - 0.5*t" or "0.25*x^4 - ∫ 1.5*x^2 ds". */
    fun answerText(stateName: String = "X_t"): String? {
        val closed = closedPart ?: return null
        val main = closed.format().replace(Regex("\\bx\\b"), stateName)
        val rest = leftover ?: return main
        // − ∫(−1.5X²) ds reads better as + ∫(1.5X²) ds.
        val negative = rest is Expr.Neg || Exprs.hasNegativeCoefficient(rest)
        val body = (if (negative) Exprs.neg(rest) else rest).format()
            .replace(Regex("\\bx\\b"), "X_s").replace(Regex("\\bt\\b"), "s")
        val sign = if (negative) "+" else "-"
        return if (closed == Exprs.ZERO) "${if (negative) "" else "-"}∫₀ᵗ ($body) ds" else "$main $sign ∫₀ᵗ ($body) ds"
    }

    /** The question as TeX, e.g. ∫₀ᵗ X_s³ dX_s, with ∘ for Stratonovich and d̂ for the backward integral. */
    fun questionTex(separator: Char = '.'): String {
        val h = integrand.withState("X_s").substitute(Names.TIME, Expr.Var("s")).toTexFactor(separator)
        val differential = when (point) {
            EvaluationPoint.LEFT -> "dX_s"
            EvaluationPoint.MIDPOINT -> "\\circ dX_s"
            EvaluationPoint.RIGHT -> "d\u0302X_s"
        }
        return "\\int_0^t $h\\,$differential"
    }

    /** [answerText] as TeX, with X_t for the state and the leftover integral over s. */
    fun answerTex(separator: Char = '.'): String? {
        val closed = closedPart ?: return null
        val main = closed.withState("X_t").toTex(separator)
        val rest = leftover ?: return main
        val negative = rest is Expr.Neg || Exprs.hasNegativeCoefficient(rest)
        val body = (if (negative) Exprs.neg(rest) else rest).withState("X_s").substitute(Names.TIME, Expr.Var("s"))
        val integral = "\\int_0^t " + body.toTexFactor(separator) + "\\,ds"
        val sign = if (negative) "+" else "-"
        return if (closed == Exprs.ZERO) "${if (negative) "" else "-"}$integral" else "$main $sign $integral"
    }

    /**
     * E of the integral at time t, for X = B. Itô integrals have mean 0 (when E∫h² ds < ∞); the
     * Stratonovich and backward ones add θ·∫E[h_x(s, B_s)] ds. Null for other integrators.
     */
    fun mean(t: Double, intervals: Int = 200): Double? {
        if (!isBrownian) return null
        if (point == EvaluationPoint.LEFT) return 0.0
        val hx = Evaluator.compile(Derivative.dx(integrand), parameters)
        return point.weightOfRight * Quadrature.simpson(0.0, t, intervals) { s ->
            Quadrature.gaussianExpectation(x0, sqrt(s)) { hx.eval(s, it) }
        }
    }

    /**
     * Var of the Itô integral at time t for X = B, by the Itô isometry: ∫₀ᵗ E[h(s, B_s)²] ds.
     * Null for other evaluation points or integrators.
     */
    fun itoVariance(t: Double, intervals: Int = 200): Double? {
        if (!isBrownian || point != EvaluationPoint.LEFT) return null
        val h = Evaluator.compile(integrand, parameters)
        return Quadrature.simpson(0.0, t, intervals) { s ->
            Quadrature.gaussianExpectation(x0, sqrt(s)) { val v = h.eval(s, it); v * v }
        }
    }

    /**
     * True when h does not involve X and X = B. Then the integral is a Wiener integral and has
     * exactly the normal law N(0, ∫h² ds), whatever the evaluation point.
     */
    val isGaussian: Boolean get() = isBrownian && !integrand.dependsOnState()

    /**
     * Along one Brownian path w on [grid]: the Riemann sum Σ h·ΔX at [point], and the closed form
     * F(t, X_t) − F(0, x0) − ∫R ds with the leftover integral done numerically on the same path.
     * The two agree better and better as the grid is refined. The second is null without F.
     */
    fun along(grid: TimeGrid, w: DoubleArray): Pair<DoubleArray, DoubleArray?> {
        val x = sde.solveAlong(grid, x0, w)
        val sum = ItoSums.riemann(Evaluator.compile(integrand, parameters).alongPath(grid, x), x, point)
        val closed = closedPart?.let { Evaluator.compile(it, parameters).alongPath(grid, x) } ?: return sum to null
        val rest = leftover?.let { ItoSums.timeIntegral(Evaluator.compile(it, parameters).alongPath(grid, x), grid) }
        return sum to if (rest == null) closed else DoubleArray(closed.size) { closed[it] - rest[it] }
    }
}
