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

/** Constructors that fold constants and drop identities, so derivatives stay readable. */
object Exprs {
    val ZERO = Expr.Num(0.0)
    val ONE = Expr.Num(1.0)
    private val MINUS_ONE = Expr.Num(-1.0)

    private fun Expr.isReciprocal() = this is Expr.Binary && op == Expr.Op.DIV && left == ONE

    fun num(v: Double) = Expr.Num(v)

    fun neg(a: Expr): Expr = when (a) {
        is Expr.Num -> Expr.Num(-a.value)
        is Expr.Neg -> a.operand
        else -> Expr.Neg(a)
    }

    fun add(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num -> Expr.Num(a.value + b.value)
        a == ZERO -> b
        b == ZERO -> a
        b is Expr.Neg -> sub(a, b.operand)
        else -> Expr.Binary(Expr.Op.ADD, a, b)
    }

    fun sub(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num -> Expr.Num(a.value - b.value)
        b == ZERO -> a
        a == ZERO -> neg(b)
        a == b -> ZERO
        else -> Expr.Binary(Expr.Op.SUB, a, b)
    }

    fun mul(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num -> Expr.Num(a.value * b.value)
        a == ZERO || b == ZERO -> ZERO
        a == ONE -> b
        b == ONE -> a
        a == MINUS_ONE -> neg(b)
        b == MINUS_ONE -> neg(a)
        b.isReciprocal() -> div(a, (b as Expr.Binary).right) // a*(1/b) = a/b
        a.isReciprocal() -> div(b, (a as Expr.Binary).right)
        a is Expr.Neg -> neg(mul(a.operand, b))
        b is Expr.Neg -> neg(mul(a, b.operand))
        b is Expr.Num -> Expr.Binary(Expr.Op.MUL, b, a) // constants first: 2*x, not x*2
        else -> Expr.Binary(Expr.Op.MUL, a, b)
    }

    fun div(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num && b.value != 0.0 -> Expr.Num(a.value / b.value)
        a == ZERO -> ZERO
        b == ONE -> a
        else -> Expr.Binary(Expr.Op.DIV, a, b)
    }

    fun pow(a: Expr, b: Expr): Expr = when {
        a is Expr.Num && b is Expr.Num -> Expr.Num(a.value.pow(b.value))
        b == ZERO -> ONE
        b == ONE -> a
        else -> Expr.Binary(Expr.Op.POW, a, b)
    }

    fun call(f: Function, vararg args: Expr): Expr {
        val call = Expr.Call(f, args.toList())
        return if (args.all { it is Expr.Num }) Expr.Num(Evaluator.evaluate(call, 0.0, 0.0)) else call
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
    is Expr.Neg -> "-" + operand.wrap(4)
    is Expr.Call -> function.displayName + args.joinToString(", ", "(", ")") { it.format() }
    is Expr.Binary -> when (op) {
        Expr.Op.ADD -> left.wrap(1) + " + " + right.wrap(1)
        Expr.Op.SUB -> left.wrap(1) + " - " + right.wrap(2)
        Expr.Op.MUL -> left.wrap(2) + "*" + right.wrap(4)
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
