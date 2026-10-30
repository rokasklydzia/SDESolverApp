package sde.typeset

import sde.typeset.MathNode.Kind

/**
 * Reads the subset of TeX the app's formulas use: letters, numbers, operators, `^` and `_`,
 * `\frac`, `\sqrt`, `\int` and `\sum` with limits, `\left … \right`, Greek letters, function
 * names, `\mathrm`, `\text`, spacing commands and a few symbols. An unknown command is shown as
 * written, so a typo stays visible instead of failing.
 */
object TexParser {
    fun parse(tex: String): MathNode = Reader(tex).run {
        val row = row()
        if (!atEnd()) throw IllegalArgumentException("Unexpected '${peek()}' in $tex")
        row
    }

    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ",
        "tau" to "τ", "upsilon" to "υ", "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    )

    // Capital Greek letters are upright in TeX.
    private val capitalGreek = mapOf(
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    )

    private val functions = setOf(
        "sin", "cos", "tan", "sinh", "cosh", "tanh", "exp", "log", "ln", "max", "min", "sup", "inf", "lim", "det",
    )

    private val symbols = mapOf(
        "cdot" to ("·" to Kind.OPERATOR), "times" to ("×" to Kind.OPERATOR), "pm" to ("±" to Kind.OPERATOR),
        "mp" to ("∓" to Kind.OPERATOR), "to" to ("→" to Kind.RELATION), "rightarrow" to ("→" to Kind.RELATION),
        "le" to ("≤" to Kind.RELATION), "leq" to ("≤" to Kind.RELATION), "ge" to ("≥" to Kind.RELATION),
        "geq" to ("≥" to Kind.RELATION), "ne" to ("≠" to Kind.RELATION), "approx" to ("≈" to Kind.RELATION),
        "sim" to ("∼" to Kind.RELATION), "equiv" to ("≡" to Kind.RELATION), "infty" to ("∞" to Kind.UPRIGHT),
        "partial" to ("∂" to Kind.UPRIGHT), "langle" to ("⟨" to Kind.UPRIGHT), "rangle" to ("⟩" to Kind.UPRIGHT),
        "ldots" to ("…" to Kind.UPRIGHT), "dots" to ("…" to Kind.UPRIGHT), "prime" to ("′" to Kind.UPRIGHT),
        "mid" to ("|" to Kind.RELATION), "circ" to ("∘" to Kind.OPERATOR), "{" to ("{" to Kind.UPRIGHT), "}" to ("}" to Kind.UPRIGHT),
        "|" to ("‖" to Kind.UPRIGHT),
    )

    private val spaces = mapOf(
        "," to 0.167f, ":" to 0.222f, ">" to 0.222f, ";" to 0.278f, "!" to -0.167f, "quad" to 1f, "qquad" to 2f, " " to 0.25f,
    )

    private val bigOperators = mapOf("int" to "∫", "sum" to "∑", "prod" to "∏", "oint" to "∮")

    private val delimiters = mapOf(
        "(" to "(", ")" to ")", "[" to "[", "]" to "]", "|" to "|", "." to "",
        "\\{" to "{", "\\}" to "}", "\\langle" to "⟨", "\\rangle" to "⟩", "\\|" to "‖",
    )

    private class Reader(val text: String) {
        var pos = 0

        fun atEnd(): Boolean {
            skipSpaces()
            return pos >= text.length
        }

        fun peek(): Char = text[pos]

        fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        /** Items up to the end, a closing brace or `\right`, which it leaves unread. */
        fun row(): MathNode {
            val items = mutableListOf<MathNode>()
            while (true) {
                skipSpaces()
                if (pos >= text.length || text[pos] == '}' || text.startsWith("\\right", pos)) break
                val atom = atom(items) ?: continue
                items += scripts(atom)
            }
            return if (items.size == 1) items[0] else MathNode.Row(items)
        }

        /** Superscripts and subscripts after [base], in either order. */
        fun scripts(base: MathNode): MathNode {
            var sup: MathNode? = null
            var sub: MathNode? = null
            while (true) {
                skipSpaces()
                if (pos >= text.length) break
                when (text[pos]) {
                    '^' -> { pos++; sup = argument() }
                    '_' -> { pos++; sub = argument() }
                    '\'' -> { pos++; sup = MathNode.Glyphs("′", Kind.UPRIGHT) }
                    else -> break
                }
            }
            if (sup == null && sub == null) return base
            return if (base is MathNode.BigOperator) MathNode.BigOperator(base.symbol, sub, sup) else MathNode.Scripts(base, sup, sub)
        }

        /** A braced group or a single item, as after `^` or `\frac`. */
        fun argument(): MathNode {
            skipSpaces()
            require(pos < text.length) { "A formula ends where an argument should be: $text" }
            if (text[pos] == '{') {
                pos++
                val inner = row()
                expect('}')
                return inner
            }
            // A single digit, letter or command; a number is cut to its first digit, as in TeX.
            if (text[pos].isDigit()) return MathNode.Glyphs(text[pos++].toString(), Kind.UPRIGHT)
            return atom(mutableListOf()) ?: MathNode.Row(emptyList())
        }

        fun expect(c: Char) {
            skipSpaces()
            require(pos < text.length && text[pos] == c) { "Expected '$c' in $text" }
            pos++
        }

        /** Braced raw text, for `\mathrm{…}` and `\text{…}`. */
        fun rawGroup(): String {
            skipSpaces()
            if (pos >= text.length || text[pos] != '{') return if (pos < text.length) text[pos++].toString() else ""
            val start = ++pos
            var depth = 1
            while (pos < text.length) {
                when (text[pos]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) break
                }
                pos++
            }
            return text.substring(start, pos).also { if (pos < text.length) pos++ }
        }

        fun command(): String {
            val start = ++pos // past the backslash
            if (pos < text.length && text[pos].isLetter()) {
                while (pos < text.length && text[pos].isLetter()) pos++
            } else if (pos < text.length) {
                pos++
            }
            return text.substring(start, pos)
        }

        fun delimiter(): String {
            skipSpaces()
            require(pos < text.length) { "A delimiter is missing in $text" }
            val token = if (text[pos] == '\\') "\\" + command() else text[pos++].toString()
            return delimiters[token] ?: throw IllegalArgumentException("Unknown delimiter $token in $text")
        }

        /** One item; [before] decides whether a minus is unary. Null for something that adds nothing. */
        fun atom(before: List<MathNode>): MathNode? {
            val c = text[pos]
            return when {
                c == '{' -> { pos++; row().also { expect('}') } }
                c == '\\' -> commandAtom()
                c.isDigit() || (c == '.' && pos + 1 < text.length && text[pos + 1].isDigit()) -> number()
                c.isLetter() -> {
                    // A combining accent stays with its letter, as in d̂.
                    val start = pos++
                    while (pos < text.length && text[pos] in '\u0300'..'\u036F') pos++
                    MathNode.Glyphs(text.substring(start, pos), Kind.VARIABLE)
                }
                c == '+' || c == '-' || c == '*' -> {
                    pos++
                    val glyph = when (c) { '-' -> "−"; '*' -> "∗"; else -> "+" }
                    MathNode.Glyphs(glyph, if (startsOperand(before)) Kind.UPRIGHT else Kind.OPERATOR)
                }
                c == '=' || c == '<' || c == '>' -> { pos++; MathNode.Glyphs(c.toString(), Kind.RELATION) }
                c == ',' || c == ';' -> { pos++; MathNode.Glyphs(c.toString(), Kind.PUNCTUATION) }
                c == '~' -> { pos++; MathNode.Space(0.25f) }
                c == '^' || c == '_' -> { pos++; MathNode.Scripts(MathNode.Row(emptyList()), if (c == '^') argument() else null, if (c == '_') argument() else null) }
                else -> { pos++; MathNode.Glyphs(c.toString(), Kind.UPRIGHT) }
            }
        }

        /** True where a sign starts an operand: at the start, or after an operator, relation, comma or opening bracket. */
        private fun startsOperand(before: List<MathNode>): Boolean {
            val last = before.lastOrNull { it !is MathNode.Space } ?: return true
            return when (last) {
                is MathNode.Glyphs -> last.kind == Kind.OPERATOR || last.kind == Kind.RELATION ||
                    last.kind == Kind.PUNCTUATION || last.text in setOf("(", "[", "⟨", "{")
                is MathNode.BigOperator -> true
                else -> false
            }
        }

        /** Digits with at most one decimal point or comma that has a digit right after it. */
        fun number(): MathNode {
            val start = pos
            var seenPoint = false
            while (pos < text.length) {
                val ch = text[pos]
                if (ch.isDigit()) {
                    pos++
                } else if ((ch == '.' || ch == ',') && !seenPoint && pos + 1 < text.length && text[pos + 1].isDigit()) {
                    seenPoint = true
                    pos++
                } else {
                    break
                }
            }
            return MathNode.Glyphs(text.substring(start, pos), Kind.UPRIGHT)
        }

        fun commandAtom(): MathNode? {
            val name = command()
            greek[name]?.let { return MathNode.Glyphs(it, Kind.VARIABLE) }
            capitalGreek[name]?.let { return MathNode.Glyphs(it, Kind.UPRIGHT) }
            spaces[name]?.let { return MathNode.Space(it) }
            symbols[name]?.let { (glyph, kind) -> return MathNode.Glyphs(glyph, kind) }
            bigOperators[name]?.let { return MathNode.BigOperator(it, null, null) }
            if (name in functions) return MathNode.Glyphs(name, Kind.UPRIGHT)
            return when (name) {
                "frac", "tfrac", "dfrac" -> MathNode.Fraction(argument(), argument())
                "sqrt" -> MathNode.Root(argument())
                "mathrm", "operatorname" -> MathNode.Glyphs(rawGroup(), Kind.UPRIGHT)
                "text", "textrm" -> MathNode.Glyphs(rawGroup(), Kind.TEXT)
                "left" -> {
                    val open = delimiter()
                    val body = row()
                    require(text.startsWith("\\right", pos)) { "\\left without \\right in $text" }
                    pos += "\\right".length
                    MathNode.Fenced(open, body, delimiter())
                }
                "right" -> throw IllegalArgumentException("\\right without \\left in $text")
                // \displaystyle and friends change nothing here.
                "displaystyle", "textstyle", "limits", "nolimits" -> null
                else -> MathNode.Glyphs("\\$name", Kind.UPRIGHT)
            }
        }
    }
}
