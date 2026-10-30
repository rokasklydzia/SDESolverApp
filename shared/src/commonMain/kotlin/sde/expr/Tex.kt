package sde.expr

import kotlin.math.abs
import kotlin.math.floor

/**
 * Writes a formula as TeX for [sde.typeset.TexParser]: fractions for division, superscripts for
 * powers, e^{…} for exp, √ for sqrt, Greek letters for their names, and products side by side
 * where that cannot be misread (2x, σx, but rate·x). Numbers use [separator] as decimal mark.
 */
fun Expr.toTex(separator: Char = '.'): String = TexWriter(separator).write(this, inScript = false)

private val greekNames = setOf(
    "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu", "nu",
    "xi", "pi", "rho", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega",
    "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Phi", "Psi", "Omega",
)

/** A variable name in TeX: sigma → \sigma, x0 → x_{0}, sigma_v → \sigma_{v}, rate → \mathrm{rate}. */
fun texName(name: String): String {
    if (name == "π") return "\\pi"
    val underscore = name.indexOf('_')
    if (underscore > 0) return texSymbol(name.substring(0, underscore)) + "_{" + texName(name.substring(underscore + 1)) + "}"
    val digits = name.takeLastWhile { it.isDigit() }
    if (digits.isNotEmpty() && digits.length < name.length) return texSymbol(name.dropLast(digits.length)) + "_{" + digits + "}"
    return texSymbol(name)
}

private fun texSymbol(name: String): String = when {
    name in greekNames -> "\\$name"
    name.length == 1 -> name
    else -> "\\mathrm{$name}"
}

private class TexWriter(val separator: Char) {
    fun write(e: Expr, inScript: Boolean): String = when (e) {
        is Expr.Num -> number(e.value)
        is Expr.Var -> texName(e.name)
        is Expr.Neg -> "-" + wrap(e.operand, if (e.operand is Expr.Binary && e.operand.op != Expr.Op.POW) 2 else 4, inScript)
        is Expr.Call -> call(e, inScript)
        is Expr.Binary -> when (e.op) {
            Expr.Op.ADD -> wrap(e.left, 1, inScript) + " + " + wrap(e.right, 1, inScript)
            Expr.Op.SUB -> wrap(e.left, 1, inScript) + " - " + wrap(e.right, 2, inScript)
            Expr.Op.MUL -> product(e, inScript)
            // Fractions in a superscript would be too small to read, so they stay on the line there.
            Expr.Op.DIV -> if (inScript) wrap(e.left, 2, true) + "/" + wrap(e.right, 4, true)
            else "\\frac{" + write(e.left, false) + "}{" + write(e.right, false) + "}"
            Expr.Op.POW -> {
                val base = e.left
                val needsBrackets = base.precedence() < 5 || (base is Expr.Binary && base.op == Expr.Op.DIV && !inScript) ||
                    (base is Expr.Call && base.function == Function.EXP)
                (if (needsBrackets) fenced(write(base, inScript)) else write(base, inScript)) + "^{" + write(e.right, true) + "}"
            }
        }
    }

    private fun call(e: Expr.Call, inScript: Boolean): String {
        val arg = e.args.first()
        return when (e.function) {
            Function.EXP -> "e^{" + write(arg, true) + "}"
            Function.SQRT -> "\\sqrt{" + write(arg, inScript) + "}"
            Function.ABS -> "\\left|" + write(arg, inScript) + "\\right|"
            Function.LOG -> "\\ln" + fenced(write(arg, inScript))
            Function.LOG10 -> "\\log_{10}" + fenced(write(arg, inScript))
            Function.SIGN, Function.STEP -> "\\operatorname{${e.function.displayName}}" + fenced(write(arg, inScript))
            else -> "\\" + e.function.displayName + fenced(e.args.joinToString(", ") { write(it, inScript) })
        }
    }

    /** a·b, written side by side when the reader cannot take it for something else. */
    private fun product(e: Expr.Binary, inScript: Boolean): String {
        val left = wrap(e.left, 2, inScript)
        val right = wrap(e.right, if (e.right is Expr.Binary && e.right.op == Expr.Op.MUL) 2 else 4, inScript)
        val joinable = !startsWithNumber(e.right) && !endsWithWord(e.left) && !startsWithWord(e.right) &&
            !(e.left is Expr.Binary && e.left.op == Expr.Op.DIV && !inScript)
        return if (joinable) "$left $right" else "$left \\cdot $right"
    }

    private fun startsWithNumber(e: Expr): Boolean = when (e) {
        is Expr.Num -> true
        is Expr.Binary -> e.op != Expr.Op.DIV && startsWithNumber(e.left) && e.left.precedence() >= 2
        else -> false
    }

    /** True for a multi-letter name such as rate, which would read as r·a·t·e beside another factor. */
    private fun isWord(name: String) = texName(name).startsWith("\\mathrm")

    private fun endsWithWord(e: Expr): Boolean = when (e) {
        is Expr.Var -> isWord(e.name)
        is Expr.Binary -> e.op == Expr.Op.MUL && endsWithWord(e.right)
        else -> false
    }

    private fun startsWithWord(e: Expr): Boolean = when (e) {
        is Expr.Var -> isWord(e.name)
        is Expr.Binary -> (e.op == Expr.Op.MUL || e.op == Expr.Op.POW) && startsWithWord(e.left)
        else -> false
    }

    private fun wrap(e: Expr, minimum: Int, inScript: Boolean): String =
        if (e.precedence() < minimum) fenced(write(e, inScript)) else write(e, inScript)

    private fun fenced(body: String) = "\\left($body\\right)"

    private fun number(v: Double): String {
        val text = if (v == floor(v) && abs(v) < 1e15) {
            v.toLong().toString()
        } else {
            val plain = v.toString()
            val exponent = plain.indexOfFirst { it == 'E' || it == 'e' }
            if (exponent < 0) plain else plain.substring(0, exponent) + " \\cdot 10^{" + plain.substring(exponent + 1) + "}"
        }
        return text.replace('.', separator)
    }
}

/** Binding strength, as in [format]: 1 for + and −, 2 for * and /, 3 for unary minus, 4 for ^, 5 for atoms. */
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

/** The formula with the state x (or X) renamed, e.g. to X_t for display. */
fun Expr.withState(name: String): Expr = substitute("x", Expr.Var(name)).substitute("X", Expr.Var(name))

/**
 * TeX for the formula as a factor in front of something else, such as a dt: in brackets when it is
 * a sum, or when [signed] is false and it starts with a minus sign (+ (−x) dB, not + −x dB).
 */
fun Expr.toTexFactor(separator: Char = '.', signed: Boolean = false): String {
    val needsBrackets = precedence() == 1 || (!signed && precedence() == 3)
    return if (needsBrackets) "\\left(" + toTex(separator) + "\\right)" else toTex(separator)
}

/** A differential such as dX_t = μ dt + σ dB_t, leaving out terms that are 0 and factors that are 1. */
fun differentialTex(left: String, dt: Expr, dB: Expr, noise: String = "dB_t", separator: Char = '.'): String {
    fun term(coefficient: Expr, differential: String, first: Boolean): String? = when (coefficient) {
        Exprs.ZERO -> null
        Exprs.ONE -> differential
        else -> coefficient.toTexFactor(separator, signed = first) + "\\," + differential
    }
    val drift = term(dt, "dt", first = true)
    val noiseTerm = term(dB, noise, first = drift == null)
    val terms = listOfNotNull(drift, noiseTerm)
    return "$left = " + if (terms.isEmpty()) "0" else terms.joinToString(" + ")
}
