package sde.expr

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** A parsed formula such as `kappa*(theta - x)`. Build it with [Parser] or the helpers in [Exprs]. */
sealed interface Expr {
    data class Num(val value: Double) : Expr
    data class Var(val name: String) : Expr
    data class Neg(val operand: Expr) : Expr
    data class Binary(val op: Op, val left: Expr, val right: Expr) : Expr
    data class Call(val function: Function, val args: List<Expr>) : Expr

    enum class Op(val symbol: String) { ADD("+"), SUB("-"), MUL("*"), DIV("/"), POW("^") }
}

/** The functions a formula may call. `log` is the natural logarithm, as in R and numpy. */
enum class Function(val names: List<String>, val arity: Int) {
    SIN(listOf("sin"), 1), COS(listOf("cos"), 1), TAN(listOf("tan"), 1),
    SINH(listOf("sinh"), 1), COSH(listOf("cosh"), 1), TANH(listOf("tanh"), 1),
    EXP(listOf("exp"), 1), LOG(listOf("log", "ln"), 1), LOG10(listOf("log10"), 1),
    SQRT(listOf("sqrt"), 1), ABS(listOf("abs"), 1),
    /** −1, 0 or 1. */
    SIGN(listOf("sign"), 1),
    /** Heaviside step: 1 for a positive argument, else 0. */
    STEP(listOf("step"), 1),
    MIN(listOf("min"), 2), MAX(listOf("max"), 2);

    val displayName: String get() = names.first()

    companion object {
        private val byName = entries.flatMap { f -> f.names.map { it to f } }.toMap()
        fun named(name: String): Function? = byName[name]
    }
}

/** Names that are always available in a formula. */
object Names {
    const val TIME = "t"
    val STATE = setOf("x", "X")
    val CONSTANTS = mapOf("pi" to kotlin.math.PI, "π" to kotlin.math.PI, "e" to kotlin.math.E)
}

/**
 * Constructors that fold constants, drop identities, gather constant factors in front and combine
 * like terms, so derivatives and Itô's formula stay readable: x*(3*x^2) becomes 3*x^3, and
 * 3*x^3 + 3*x^3 becomes 6*x^3. They are local rewrites, not a full simplifier.
 */
object Exprs {
    val ZERO = Expr.Num(0.0)
    val ONE = Expr.Num(1.0)
    private val MINUS_ONE = Expr.Num(-1.0)

    private fun Expr.isReciprocal() = this is Expr.Binary && op == Expr.Op.DIV && left == ONE

    /** A number, with −0 stored as 0 so that it equals [ZERO]. */
    fun num(v: Double) = Expr.Num(if (v == 0.0) 0.0 else v)

    fun neg(a: Expr): Expr = when {
        a is Expr.Num -> num(-a.value)
        a is Expr.Neg -> a.operand
        a is Expr.Binary && a.op == Expr.Op.MUL && a.left is Expr.Num -> scaled(-(a.left as Expr.Num).value, a.right)
        a is Expr.Binary && a.op == Expr.Op.SUB -> sub(a.right, a.left) // −(a − b) = b − a
        a is Expr.Binary && a.op == Expr.Op.ADD -> sub(neg(a.left), a.right) // −(a + b) = −a − b
        else -> Expr.Neg(a)
    }

    fun add(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num -> num(a.value + b.value)
        a == ZERO -> b
        b == ZERO -> a
        b is Expr.Neg -> sub(a, b.operand)
        b is Expr.Num && b.value < 0 -> sub(a, num(-b.value))
        a is Expr.Neg -> sub(b, a.operand) // −a + b = b − a
        else -> combineLikeTerms(a, b, 1.0) ?: Expr.Binary(Expr.Op.ADD, a, b)
    }

    fun sub(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num -> num(a.value - b.value)
        b == ZERO -> a
        a == ZERO -> neg(b)
        b is Expr.Neg -> add(a, b.operand)
        else -> combineLikeTerms(a, b, -1.0) ?: Expr.Binary(Expr.Op.SUB, a, b)
    }

    fun mul(a: Expr, b: Expr): Expr {
        when {
            a is Expr.Num && b is Expr.Num -> return num(a.value * b.value)
            a == ZERO || b == ZERO -> return ZERO
            a == ONE -> return b
            b == ONE -> return a
            a == MINUS_ONE -> return neg(b)
            b == MINUS_ONE -> return neg(a)
            b.isReciprocal() -> return div(a, (b as Expr.Binary).right) // a*(1/b) = a/b
            a.isReciprocal() -> return div(b, (a as Expr.Binary).right)
            a is Expr.Neg -> return neg(mul(a.operand, b))
            b is Expr.Neg -> return neg(mul(a, b.operand))
        }
        // Constants go in front, multiplied together: 2*(3*x) = 6*x and x*2 = 2*x.
        val (ca, ra) = coefficient(a)
        val (cb, rb) = coefficient(b)
        return scaled(ca * cb, productOfFactors(ra, rb))
    }

    fun div(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num && b.value != 0.0 -> num(a.value / b.value)
        a == ZERO -> ZERO
        b == ONE -> a
        a == b -> ONE
        else -> Expr.Binary(Expr.Op.DIV, a, b)
    }

    fun pow(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num -> num(a.value.pow(b.value))
        b == ZERO -> ONE
        b == ONE -> a
        else -> Expr.Binary(Expr.Op.POW, a, b)
    }

    fun call(f: Function, vararg args: Expr): Expr {
        val call = Expr.Call(f, args.toList())
        return if (args.all { it is Expr.Num }) num(Evaluator.evaluate(call, 0.0, 0.0)) else call
    }

    /** c·r with c a number: 3*x is (3, x), -x is (−1, x), a number c is (c, 1), anything else (1, itself). */
    private fun coefficient(e: Expr): Pair<Double, Expr> = when {
        e is Expr.Num -> e.value to ONE
        e is Expr.Neg -> coefficient(e.operand).let { (c, r) -> -c to r }
        e is Expr.Binary && e.op == Expr.Op.MUL && e.left is Expr.Num -> (e.left as Expr.Num).value to e.right
        else -> 1.0 to e
    }

    /** c·r, written as plainly as possible. */
    private fun scaled(c: Double, r: Expr): Expr = when {
        c == 0.0 -> ZERO
        r == ONE -> num(c)
        c == 1.0 -> r
        c == -1.0 -> neg(r)
        else -> Expr.Binary(Expr.Op.MUL, num(c), r)
    }

    /** The product of two factors without constants, merging powers of one base: x*x^2 = x^3. */
    private fun productOfFactors(a: Expr, b: Expr): Expr {
        if (a == ONE) return b
        if (b == ONE) return a
        val (baseA, powerA) = powerOf(a)
        val (baseB, powerB) = powerOf(b)
        return if (baseA == baseB) pow(baseA, num(powerA + powerB)) else Expr.Binary(Expr.Op.MUL, a, b)
    }

    /** x^n is (x, n) for a number n; anything else is (itself, 1). */
    private fun powerOf(e: Expr): Pair<Expr, Double> =
        if (e is Expr.Binary && e.op == Expr.Op.POW && e.right is Expr.Num) e.left to (e.right as Expr.Num).value else e to 1.0

    /** a ± b as one term when they differ only by a constant factor: 3*x^3 + 3*x^3 = 6*x^3. */
    private fun combineLikeTerms(a: Expr, b: Expr, sign: Double): Expr? {
        val (ca, ra) = coefficient(a)
        val (cb, rb) = coefficient(b)
        return if (ra == rb && ra != ONE) scaled(ca + sign * cb, ra) else null
    }
}

/** True if [this] depends on the variable [name]. */
fun Expr.dependsOn(name: String): Boolean = when (this) {
    is Expr.Num -> false
    is Expr.Var -> this.name == name
    is Expr.Neg -> operand.dependsOn(name)
    is Expr.Binary -> left.dependsOn(name) || right.dependsOn(name)
    is Expr.Call -> args.any { it.dependsOn(name) }
}

/** Every variable name in the formula, in order of first appearance. */
fun Expr.names(): Set<String> = LinkedHashSet<String>().also { collectNames(this, it) }

private fun collectNames(e: Expr, into: MutableSet<String>) {
    when (e) {
        is Expr.Num -> {}
        is Expr.Var -> into += e.name
        is Expr.Neg -> collectNames(e.operand, into)
        is Expr.Binary -> { collectNames(e.left, into); collectNames(e.right, into) }
        is Expr.Call -> e.args.forEach { collectNames(it, into) }
    }
}

/** Prints a formula back as text the [Parser] reads, with as few parentheses as possible. */
fun Expr.format(): String = when (this) {
    is Expr.Num -> formatNumber(value)
    is Expr.Var -> name
    // −(a*b) = (−a)*b, so a product needs no parentheses after the minus sign.
    is Expr.Neg -> "-" + operand.wrap(if (operand is Expr.Binary && operand.op != Expr.Op.POW) 2 else 4)
    is Expr.Call -> function.displayName + args.joinToString(", ", "(", ")") { it.format() }
    is Expr.Binary -> when (op) {
        Expr.Op.ADD -> left.wrap(1) + " + " + right.wrap(1)
        Expr.Op.SUB -> left.wrap(1) + " - " + right.wrap(2)
        // Products are associative, so a*(b*c) prints as a*b*c.
        Expr.Op.MUL -> left.wrap(2) + "*" + right.wrap(if (right is Expr.Binary && right.op == Expr.Op.MUL) 2 else 4)
        Expr.Op.DIV -> left.wrap(2) + "/" + right.wrap(4)
        Expr.Op.POW -> left.wrap(5) + "^" + right.wrap(4)
    }
}

/** Binding strength: 1 for + and −, 2 for * and /, 3 for unary minus, 4 for ^, 5 for atoms. */
private fun Expr.precedence(): Int = when (this) {
    is Expr.Num -> if (value < 0) 3 else 5
    is Expr.Var, is Expr.Call -> 5
    is Expr.Neg -> 3
    is Expr.Binary -> when (op) {
        Expr.Op.ADD, Expr.Op.SUB -> 1
        Expr.Op.MUL, Expr.Op.DIV -> 2
        Expr.Op.POW -> 4
    }
}

private fun Expr.wrap(minimum: Int): String = if (precedence() < minimum) "(${format()})" else format()

private fun formatNumber(v: Double): String =
    if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()
