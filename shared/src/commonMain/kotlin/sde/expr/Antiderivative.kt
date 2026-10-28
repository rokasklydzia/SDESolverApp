package sde.expr

import sde.expr.Exprs.add
import sde.expr.Exprs.call
import sde.expr.Exprs.div
import sde.expr.Exprs.mul
import sde.expr.Exprs.neg
import sde.expr.Exprs.num
import sde.expr.Exprs.pow
import sde.expr.Exprs.sub
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** The variable an [Antiderivative] integrates over: the state x or the time t. */
enum class Variable {
    STATE, TIME;

    internal fun matches(name: String) = if (this == STATE) name in Names.STATE else name == Names.TIME
    internal fun derivative(e: Expr) = if (this == STATE) Derivative.dx(e) else Derivative.dt(e)
    internal val symbol: Expr get() = Expr.Var(if (this == STATE) "x" else Names.TIME)
}

/**
 * Symbolic antiderivatives for the forms a stochastic calculus course meets: sums, constant
 * multiples, powers of a linear expression, exp, sin, cos, sinh and cosh of a linear expression,
 * 1/(ax + b), and a polynomial times one of those (integration by parts, done as a table).
 * Not a general integrator: anything else returns null.
 *
 * Every answer is checked by differentiating it back and comparing values at sample points, so a
 * gap in the rules gives null rather than a wrong answer.
 */
object Antiderivative {
    /** An F with ∂F/∂[variable] = [e], treating every other name as a constant, or null. */
    fun of(e: Expr, variable: Variable = Variable.STATE): Expr? {
        val result = Rules(variable).integrate(e) ?: return null
        return if (verify(e, result, variable)) result else null
    }

    private class Rules(val v: Variable) {
        fun depends(e: Expr) = e.names().any(v::matches)

        fun integrate(e: Expr): Expr? {
            if (!depends(e)) return mul(e, v.symbol)
            return when (e) {
                is Expr.Num -> mul(e, v.symbol)
                is Expr.Var -> div(pow(e, num(2.0)), num(2.0))
                is Expr.Neg -> integrate(e.operand)?.let(::neg)
                is Expr.Binary -> when (e.op) {
                    Expr.Op.ADD -> both(e.left, e.right) { a, b -> add(a, b) }
                    Expr.Op.SUB -> both(e.left, e.right) { a, b -> sub(a, b) }
                    Expr.Op.MUL -> product(factors(e))
                    Expr.Op.DIV -> when {
                        !depends(e.right) -> integrate(e.left)?.let { div(it, e.right) }
                        !depends(e.left) -> reciprocal(e.right)?.let { mul(e.left, it) }
                        else -> null
                    }
                    Expr.Op.POW -> power(e)
                }
                is Expr.Call -> elementary(e)
            }
        }

        private fun both(a: Expr, b: Expr, combine: (Expr, Expr) -> Expr): Expr? {
            val ia = integrate(a) ?: return null
            val ib = integrate(b) ?: return null
            return combine(ia, ib)
        }

        /** The slope a when [e] = a·v + b, with a free of v; null when [e] is not linear in v. */
        fun slope(e: Expr): Expr? {
            val a = v.derivative(e)
            return if (depends(a) || a == Exprs.ZERO) null else a
        }

        /** ∫ 1/u for u = av + b: log|u|/a. */
        fun reciprocal(u: Expr): Expr? {
            if (u is Expr.Binary && u.op == Expr.Op.POW) {
                return power(Expr.Binary(Expr.Op.POW, u.left, neg(u.right)))
            }
            val a = slope(u) ?: return null
            return div(call(Function.LOG, call(Function.ABS, u)), a)
        }

        /** ∫ uⁿ for u = av + b and a number n: u^(n+1)/(a(n+1)), or log|u|/a when n = −1. */
        fun power(e: Expr.Binary): Expr? {
            val n = (e.right as? Expr.Num)?.value
            if (n == null) {
                if (!depends(e.right)) {
                    // u^p with a symbolic constant p, assumed ≠ −1.
                    val a = slope(e.left) ?: return null
                    val p1 = add(e.right, Exprs.ONE)
                    return div(pow(e.left, p1), mul(a, p1))
                }
                // c^(av + b) = exp((av + b)·ln c)
                if (depends(e.left)) return null
                val a = slope(e.right) ?: return null
                return div(e, mul(a, call(Function.LOG, e.left)))
            }
            val a = slope(e.left) ?: return null
            if (n == -1.0) return div(call(Function.LOG, call(Function.ABS, e.left)), a)
            return div(pow(e.left, num(n + 1)), mul(a, num(n + 1)))
        }

        /** exp, sin, cos, sinh and cosh of a linear argument. */
        fun elementary(e: Expr.Call): Expr? {
            val u = e.args.firstOrNull() ?: return null
            val a = slope(u) ?: return null
            val f = when (e.function) {
                Function.EXP -> e
                Function.SIN -> neg(call(Function.COS, u))
                Function.COS -> call(Function.SIN, u)
                Function.SINH -> call(Function.COSH, u)
                Function.COSH -> call(Function.SINH, u)
                else -> return null
            }
            return div(f, a)
        }

        fun factors(e: Expr): List<Expr> =
            if (e is Expr.Binary && e.op == Expr.Op.MUL) factors(e.left) + factors(e.right) else listOf(e)

        /**
         * Constants come out front. One factor left is integrated directly; a polynomial p times
         * a factor g is integrated by parts as ∫pg = pG₁ − p′G₂ + p″G₃ − …, Gₖ being the k-th
         * antiderivative of g, which ends because p has finitely many derivatives.
         */
        fun product(all: List<Expr>): Expr? {
            val (constant, varying) = all.partition { !depends(it) }
            val c = constant.fold(Exprs.ONE as Expr) { acc, f -> mul(acc, f) }
            val integral = when (varying.size) {
                0 -> v.symbol
                1 -> integrate(varying[0])
                2 -> byParts(varying[0], varying[1]) ?: byParts(varying[1], varying[0])
                else -> {
                    // Several polynomial factors multiply out to one polynomial factor.
                    val (polys, others) = varying.partition(::isPolynomial)
                    if (others.size > 1 || polys.isEmpty()) return null
                    val p = polys.reduce { acc, f -> mul(acc, f) }
                    if (others.isEmpty()) integrate(p) else byParts(p, others[0])
                }
            } ?: return null
            return mul(c, integral)
        }

        fun byParts(p: Expr, g: Expr): Expr? {
            if (!isPolynomial(p)) return null
            var result: Expr = Exprs.ZERO
            var derivative = p
            var antiderivative = integrate(g) ?: return null
            var sign = 1.0
            repeat(MAX_DEGREE + 1) {
                val term = mul(derivative, antiderivative)
                result = if (sign > 0) add(result, term) else sub(result, term)
                derivative = v.derivative(derivative)
                if (derivative == Exprs.ZERO) return result
                antiderivative = integrate(antiderivative) ?: return null
                sign = -sign
            }
            return null
        }

        /** A polynomial in v: sums and products of constants and v, with whole powers. */
        fun isPolynomial(e: Expr): Boolean = when (e) {
            is Expr.Num, is Expr.Var -> true
            is Expr.Neg -> isPolynomial(e.operand)
            is Expr.Call -> !depends(e)
            is Expr.Binary -> when (e.op) {
                Expr.Op.ADD, Expr.Op.SUB, Expr.Op.MUL -> isPolynomial(e.left) && isPolynomial(e.right)
                Expr.Op.DIV -> isPolynomial(e.left) && !depends(e.right)
                Expr.Op.POW -> !depends(e) || (isPolynomial(e.left) && (e.right as? Expr.Num)?.value?.let { it >= 0 && it == floor(it) } == true)
            }
        }
    }

    /** Checks dF/dv = e at a spread of points, with parameters set to awkward values. */
    private fun verify(e: Expr, f: Expr, variable: Variable): Boolean {
        val df = variable.derivative(f)
        val parameters = (e.names() + f.names()).filter { it != Names.TIME && it !in Names.STATE }
            .withIndex().associate { (i, name) -> name to 0.7 + 0.37 * i }
        val expected = Evaluator.compile(e, parameters)
        val actual = Evaluator.compile(df, parameters)
        var checked = 0
        for (t in doubleArrayOf(0.3, 1.1, 2.3)) for (x in doubleArrayOf(-1.7, -0.4, 0.6, 1.3, 2.9)) {
            val want = expected.eval(t, x)
            val got = actual.eval(t, x)
            if (!want.isFinite() || !got.isFinite()) continue
            if (abs(want - got) > 1e-8 * max(1.0, abs(want))) return false
            checked++
        }
        return checked > 0
    }

    private const val MAX_DEGREE = 12
}
