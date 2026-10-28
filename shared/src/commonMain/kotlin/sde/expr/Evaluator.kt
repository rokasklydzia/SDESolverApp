package sde.expr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** A formula turned into a function of time and state, with every parameter fixed. */
fun interface CompiledExpr {
    fun eval(t: Double, x: Double): Double
}

object Evaluator {
    /**
     * Compiles [expr] into a tree of small closures, which runs far faster than walking the
     * syntax tree on every call. Parameters are baked in as constants, so recompile when a slider
     * moves. Throws [ExpressionException] for a name that is neither t, x nor a parameter.
     */
    fun compile(expr: Expr, parameters: Map<String, Double> = emptyMap()): CompiledExpr {
        val unknown = expr.names().filter { it != Names.TIME && it !in Names.STATE && it !in parameters }
        if (unknown.isNotEmpty()) {
            val allowed = (listOf(Names.TIME, "x") + parameters.keys).joinToString(", ")
            throw ExpressionException("unknown name '${unknown.first()}' (available: $allowed)")
        }
        return build(expr, parameters)
    }

    /** Evaluates directly, for one-off values. Unknown names count as 0. */
    fun evaluate(expr: Expr, t: Double, x: Double, parameters: Map<String, Double> = emptyMap()): Double =
        build(expr, parameters).eval(t, x)

    private fun build(e: Expr, p: Map<String, Double>): CompiledExpr = when (e) {
        is Expr.Num -> { val v = e.value; CompiledExpr { _, _ -> v } }
        is Expr.Var -> when (e.name) {
            Names.TIME -> CompiledExpr { t, _ -> t }
            in Names.STATE -> CompiledExpr { _, x -> x }
            else -> { val v = p[e.name] ?: 0.0; CompiledExpr { _, _ -> v } }
        }
        is Expr.Neg -> { val a = build(e.operand, p); CompiledExpr { t, x -> -a.eval(t, x) } }
        is Expr.Binary -> binary(e.op, build(e.left, p), build(e.right, p), e.right)
        is Expr.Call -> call(e.function, e.args.map { build(it, p) })
    }

    private fun binary(op: Expr.Op, a: CompiledExpr, b: CompiledExpr, right: Expr): CompiledExpr = when (op) {
        Expr.Op.ADD -> CompiledExpr { t, x -> a.eval(t, x) + b.eval(t, x) }
        Expr.Op.SUB -> CompiledExpr { t, x -> a.eval(t, x) - b.eval(t, x) }
        Expr.Op.MUL -> CompiledExpr { t, x -> a.eval(t, x) * b.eval(t, x) }
        Expr.Op.DIV -> CompiledExpr { t, x -> a.eval(t, x) / b.eval(t, x) }
        Expr.Op.POW -> when ((right as? Expr.Num)?.value) {
            // Small whole powers are common (x^2) and much cheaper as products than pow().
            2.0 -> CompiledExpr { t, x -> val v = a.eval(t, x); v * v }
            3.0 -> CompiledExpr { t, x -> val v = a.eval(t, x); v * v * v }
            0.5 -> CompiledExpr { t, x -> sqrt(a.eval(t, x)) }
            else -> CompiledExpr { t, x -> a.eval(t, x).pow(b.eval(t, x)) }
        }
    }

    private fun call(f: Function, args: List<CompiledExpr>): CompiledExpr {
        val a = args[0]
        return when (f) {
            Function.SIN -> CompiledExpr { t, x -> sin(a.eval(t, x)) }
            Function.COS -> CompiledExpr { t, x -> cos(a.eval(t, x)) }
            Function.TAN -> CompiledExpr { t, x -> tan(a.eval(t, x)) }
            Function.SINH -> CompiledExpr { t, x -> sinh(a.eval(t, x)) }
            Function.COSH -> CompiledExpr { t, x -> cosh(a.eval(t, x)) }
            Function.TANH -> CompiledExpr { t, x -> tanh(a.eval(t, x)) }
            Function.EXP -> CompiledExpr { t, x -> exp(a.eval(t, x)) }
            Function.LOG -> CompiledExpr { t, x -> ln(a.eval(t, x)) }
            Function.LOG10 -> CompiledExpr { t, x -> log10(a.eval(t, x)) }
            Function.SQRT -> CompiledExpr { t, x -> sqrt(a.eval(t, x)) }
            Function.ABS -> CompiledExpr { t, x -> abs(a.eval(t, x)) }
            Function.SIGN -> CompiledExpr { t, x -> sign(a.eval(t, x)) }
            Function.STEP -> CompiledExpr { t, x -> if (a.eval(t, x) > 0) 1.0 else 0.0 }
            Function.MIN -> { val b = args[1]; CompiledExpr { t, x -> min(a.eval(t, x), b.eval(t, x)) } }
            Function.MAX -> { val b = args[1]; CompiledExpr { t, x -> max(a.eval(t, x), b.eval(t, x)) } }
        }
    }
}
