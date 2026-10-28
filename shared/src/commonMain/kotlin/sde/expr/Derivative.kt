package sde.expr

import sde.expr.Exprs.add
import sde.expr.Exprs.call
import sde.expr.Exprs.div
import sde.expr.Exprs.mul
import sde.expr.Exprs.neg
import sde.expr.Exprs.num
import sde.expr.Exprs.pow
import sde.expr.Exprs.sub
import kotlin.math.ln

/** Symbolic differentiation, used for the ∂σ/∂x in Milstein's scheme and Itô's lemma. */
object Derivative {
    /** ∂e/∂x, treating t and every parameter as constants. */
    fun dx(e: Expr): Expr = d(e) { it in Names.STATE }

    /** ∂e/∂t, treating x and every parameter as constants. */
    fun dt(e: Expr): Expr = d(e) { it == Names.TIME }

    private fun d(e: Expr, isVariable: (String) -> Boolean): Expr {
        fun d(u: Expr) = d(u, isVariable)
        fun depends(u: Expr) = u.names().any(isVariable)
        return when (e) {
            is Expr.Num -> Exprs.ZERO
            is Expr.Var -> if (isVariable(e.name)) Exprs.ONE else Exprs.ZERO
            is Expr.Neg -> neg(d(e.operand))
            is Expr.Binary -> {
                val u = e.left
                val v = e.right
                when (e.op) {
                    Expr.Op.ADD -> add(d(u), d(v))
                    Expr.Op.SUB -> sub(d(u), d(v))
                    Expr.Op.MUL -> add(mul(d(u), v), mul(u, d(v)))
                    Expr.Op.DIV -> div(sub(mul(d(u), v), mul(u, d(v))), pow(v, num(2.0)))
                    Expr.Op.POW -> when {
                        !depends(v) -> mul(mul(v, pow(u, sub(v, Exprs.ONE))), d(u))
                        !depends(u) -> mul(mul(e, call(Function.LOG, u)), d(v))
                        // d(u^v) = u^v·(v'·ln u + v·u'/u)
                        else -> mul(e, add(mul(d(v), call(Function.LOG, u)), div(mul(v, d(u)), u)))
                    }
                }
            }
            is Expr.Call -> {
                val u = e.args[0]
                val du = d(u)
                when (e.function) {
                    Function.SIN -> mul(call(Function.COS, u), du)
                    Function.COS -> neg(mul(call(Function.SIN, u), du))
                    Function.TAN -> div(du, pow(call(Function.COS, u), num(2.0)))
                    Function.SINH -> mul(call(Function.COSH, u), du)
                    Function.COSH -> mul(call(Function.SINH, u), du)
                    Function.TANH -> mul(sub(Exprs.ONE, pow(e, num(2.0))), du)
                    Function.EXP -> mul(e, du)
                    Function.LOG -> div(du, u)
                    Function.LOG10 -> div(du, mul(num(ln(10.0)), u))
                    Function.SQRT -> div(du, mul(num(2.0), e))
                    Function.ABS -> mul(call(Function.SIGN, u), du)
                    Function.SIGN, Function.STEP -> Exprs.ZERO
                    // min(u, v) is u where v − u > 0; max(u, v) is u where u − v > 0.
                    Function.MIN -> piecewise(call(Function.STEP, sub(e.args[1], u)), du, d(e.args[1]))
                    Function.MAX -> piecewise(call(Function.STEP, sub(u, e.args[1])), du, d(e.args[1]))
                }
            }
        }
    }

    private fun piecewise(firstWins: Expr, dFirst: Expr, dSecond: Expr): Expr =
        add(mul(firstWins, dFirst), mul(sub(Exprs.ONE, firstWins), dSecond))
}
