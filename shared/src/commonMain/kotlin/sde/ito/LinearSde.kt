package sde.ito

import sde.expr.Antiderivative
import sde.expr.Derivative
import sde.expr.Expr
import sde.expr.Exprs
import sde.expr.Exprs.add
import sde.expr.Exprs.call
import sde.expr.Exprs.mul
import sde.expr.Exprs.neg
import sde.expr.Exprs.num
import sde.expr.Exprs.pow
import sde.expr.Exprs.sub
import sde.expr.Function
import sde.expr.Names
import sde.expr.Parser
import sde.expr.Variable
import sde.expr.dependsOn
import sde.expr.dependsOnState
import sde.expr.format
import sde.expr.substitute

/**
 * The explicit solution of a linear SDE dX = (a(t)X + b(t)) dt + (c(t)X + d(t)) dB, X(0) = x0.
 *
 * With the stochastic exponential Φ(t) = exp(∫₀ᵗ (a − ½c²) ds + ∫₀ᵗ c dB),
 *
 *     X(t) = Φ(t)·(x0 + ∫₀ᵗ Φ(s)⁻¹(b − cd) ds + ∫₀ᵗ Φ(s)⁻¹ d dB).
 *
 * When c = 0 the noise is additive, Φ is deterministic and X(t) is normal; its mean and variance
 * are then found in closed form whenever the time integrals have one. GBM, Ornstein–Uhlenbeck and
 * Brownian motion with drift are all of this form.
 */
class LinearSde private constructor(
    val a: Expr,
    val b: Expr,
    val c: Expr,
    val d: Expr,
) {
    /** ∫₀ᵗ (a − ½c²) ds, or null when it has no closed form. */
    val exponent: Expr? = definite(sub(a, mul(num(0.5), pow(c, num(2.0)))))

    /** True when c = 0: additive noise, a deterministic Φ and a normal X(t). */
    val additiveNoise: Boolean = c == Exprs.ZERO

    /** Φ(t), written with B for B_t, or null when ∫c dB is not c·B_t or the exponent has no closed form. */
    val phi: Expr? = exponent?.let { k ->
        val noise = when {
            additiveNoise -> Exprs.ZERO
            !c.dependsOn(Names.TIME) -> mul(c, B)
            else -> return@let null
        }
        call(Function.EXP, add(k, noise))
    }

    /** E[X(t)] when the noise is additive, in t and x0. */
    val mean: Expr? = if (!additiveNoise) null else exponent?.let { k ->
        val inverse = call(Function.EXP, neg(k))
        val forcing = definite(mul(inverse, b)) ?: return@let null
        mul(call(Function.EXP, k), add(X0, forcing))
    }

    /** Var[X(t)] when the noise is additive: Φ(t)²·∫₀ᵗ Φ(s)⁻² d² ds. */
    val variance: Expr? = if (!additiveNoise) null else exponent?.let { k ->
        val integral = definite(mul(call(Function.EXP, mul(num(-2.0), k)), pow(d, num(2.0)))) ?: return@let null
        mul(call(Function.EXP, mul(num(2.0), k)), integral)
    }

    /** The solution formula, step by step, for display. */
    fun steps(): List<String> = buildList {
        add("a(t) = ${a.format()},  b(t) = ${b.format()},  c(t) = ${c.format()},  d(t) = ${d.format()}")
        val k = exponent?.format() ?: "∫₀ᵗ (${sub(a, mul(num(0.5), pow(c, num(2.0)))).format()}) ds"
        val noise = when {
            additiveNoise -> ""
            !c.dependsOn(Names.TIME) -> " + ${mul(c, B).format()}"
            else -> " + ∫₀ᵗ ${c.format()} dB"
        }
        add("Φ(t) = ${phi?.format() ?: "exp($k$noise)"}")
        val drift = sub(b, mul(c, d))
        val parts = buildList {
            add("x0")
            if (drift != Exprs.ZERO) add("∫₀ᵗ Φ(s)⁻¹·(${drift.format()}) ds")
            if (d != Exprs.ZERO) add("∫₀ᵗ Φ(s)⁻¹·(${d.format()}) dB")
        }
        add(if (parts.size == 1) "X(t) = x0·Φ(t)" else "X(t) = Φ(t)·(${parts.joinToString(" + ")})")
        if (additiveNoise) {
            mean?.let { add("E[X(t)] = ${it.format()}") }
            variance?.let { add("Var[X(t)] = ${it.format()}") }
            add("X(t) is normal: the noise enters additively, so X(t) is a Wiener integral plus a deterministic function.")
        }
    }

    companion object {
        private val B = Expr.Var("B")
        private val X0 = Expr.Var("x0")

        /** The solution if drift and diffusion are both affine in x, else null. */
        fun of(driftText: String, diffusionText: String): LinearSde? {
            val drift = Parser.parse(driftText)
            val diffusion = Parser.parse(diffusionText)
            val a = Derivative.dx(drift)
            val c = Derivative.dx(diffusion)
            if (a.dependsOnState() || c.dependsOnState()) return null
            val b = drift.substitute("x", Exprs.ZERO).substitute("X", Exprs.ZERO)
            val d = diffusion.substitute("x", Exprs.ZERO).substitute("X", Exprs.ZERO)
            return LinearSde(a, b, c, d)
        }

        /** ∫₀ᵗ g ds in closed form, or null. */
        private fun definite(g: Expr): Expr? {
            if (g == Exprs.ZERO) return Exprs.ZERO
            val antiderivative = Antiderivative.of(g, Variable.TIME) ?: return null
            return sub(antiderivative, antiderivative.substitute(Names.TIME, Exprs.ZERO))
        }
    }
}
