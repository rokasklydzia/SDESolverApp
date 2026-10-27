package sde.expr

/** A formula that could not be read; [position] is the 0-based character index of the problem. */
class ExpressionException(message: String, val position: Int = -1) : IllegalArgumentException(message)

/**
 * Reads formulas such as `kappa*(theta - x)`, `0.3 sqrt(x)` or `sigma x^2`.
 *
 * Precedence, loosest first: `+ -` · `* /` and implicit multiplication · unary minus · `^`.
 * So `-x^2` is −(x²), `2x^2` is 2·x², and `a^b^c` is a^(b^c). `**` is accepted for `^`, and
 * `×`, `·` and `−` for `*`, `*` and `-`, so formulas pasted from Python or a PDF still read.
 */
class Parser private constructor(private val tokens: List<Token>) {
    private var i = 0

    companion object {
        fun parse(text: String): Expr {
            val parser = Parser(Lexer(text).tokens())
            if (parser.peek().kind == Kind.END) throw ExpressionException("the formula is empty", 0)
            val e = parser.expression()
            val rest = parser.peek()
            if (rest.kind != Kind.END) {
                val what = if (rest.kind == Kind.RPAREN) "an unmatched ')'" else "'${rest.text}'"
                throw ExpressionException("unexpected $what", rest.position)
            }
            return e
        }
    }

    private fun peek() = tokens[i]
    private fun next() = tokens[i++]

    private fun expression(): Expr {
        var left = term()
        while (true) {
            val t = peek()
            left = when (t.kind) {
                Kind.PLUS -> { next(); Expr.Binary(Expr.Op.ADD, left, term()) }
                Kind.MINUS -> { next(); Expr.Binary(Expr.Op.SUB, left, term()) }
                else -> return left
            }
        }
    }

    private fun term(): Expr {
        var left = unary()
        while (true) {
            val t = peek()
            left = when {
                t.kind == Kind.STAR -> { next(); Expr.Binary(Expr.Op.MUL, left, unary()) }
                t.kind == Kind.SLASH -> { next(); Expr.Binary(Expr.Op.DIV, left, unary()) }
                startsImplicitFactor(t) -> Expr.Binary(Expr.Op.MUL, left, power())
                else -> return left
            }
        }
    }

    /** `2x`, `x(1 - x)`, `(a)(b)`; but `2 3` is an error, not 6. */
    private fun startsImplicitFactor(t: Token): Boolean = when (t.kind) {
        Kind.IDENT, Kind.LPAREN -> true
        Kind.NUMBER -> {
            val previous = tokens[i - 1]
            if (previous.kind == Kind.NUMBER) throw ExpressionException("missing operator between numbers", t.position)
            true
        }
        else -> false
    }

    private fun unary(): Expr = when (peek().kind) {
        Kind.MINUS -> { next(); Expr.Neg(unary()) }
        Kind.PLUS -> { next(); unary() }
        else -> power()
    }

    private fun power(): Expr {
        val base = primary()
        if (peek().kind != Kind.CARET) return base
        next()
        return Expr.Binary(Expr.Op.POW, base, unary())
    }

    private fun primary(): Expr {
        val t = next()
        return when (t.kind) {
            Kind.NUMBER -> Expr.Num(t.text.toDouble())
            Kind.LPAREN -> {
                val inner = expression()
                expect(Kind.RPAREN, "missing ')'", t.position)
                inner
            }
            Kind.IDENT -> identifier(t)
            Kind.END -> throw ExpressionException("the formula ends too early", t.position)
            Kind.RPAREN -> throw ExpressionException("unexpected ')'", t.position)
            else -> throw ExpressionException("unexpected '${t.text}'", t.position)
        }
    }

    private fun identifier(t: Token): Expr {
        val function = Function.named(t.text)
        if (function == null) {
            Names.CONSTANTS[t.text]?.let { return Expr.Num(it) }
            return Expr.Var(t.text)
        }
        if (peek().kind != Kind.LPAREN) throw ExpressionException("${t.text} needs parentheses, e.g. ${t.text}(x)", t.position)
        val open = next()
        val args = mutableListOf(expression())
        while (peek().kind == Kind.COMMA) { next(); args += expression() }
        expect(Kind.RPAREN, "missing ')'", open.position)
        if (args.size != function.arity) {
            val expected = if (function.arity == 1) "1 argument" else "${function.arity} arguments"
            throw ExpressionException("${t.text} takes $expected, not ${args.size}", t.position)
        }
        return Expr.Call(function, args)
    }

    private fun expect(kind: Kind, message: String, position: Int) {
        if (peek().kind != kind) throw ExpressionException(message, position)
        next()
    }
}

internal enum class Kind { NUMBER, IDENT, PLUS, MINUS, STAR, SLASH, CARET, LPAREN, RPAREN, COMMA, END }

internal class Token(val kind: Kind, val text: String, val position: Int)

internal class Lexer(private val text: String) {
    private var i = 0

    fun tokens(): List<Token> {
        val out = mutableListOf<Token>()
        while (true) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) {
                out += Token(Kind.END, "", text.length)
                return out
            }
            out += token()
        }
    }

    private fun token(): Token {
        val start = i
        val c = text[i]
        if (c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit())) return number()
        if (c.isLetter() || c == '_') {
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            return Token(Kind.IDENT, text.substring(start, i), start)
        }
        i++
        val kind = when (c) {
            '+' -> Kind.PLUS
            '-', '−' -> Kind.MINUS
            '*' -> if (i < text.length && text[i] == '*') { i++; Kind.CARET } else Kind.STAR
            '×', '·' -> Kind.STAR
            '/' -> Kind.SLASH
            '^' -> Kind.CARET
            '(' -> Kind.LPAREN
            ')' -> Kind.RPAREN
            ',' -> Kind.COMMA
            else -> throw ExpressionException("unexpected character '$c'", start)
        }
        return Token(kind, text.substring(start, i), start)
    }

    private fun number(): Token {
        val start = i
        while (i < text.length && text[i].isDigit()) i++
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i].isDigit()) i++
        }
        // An exponent only if digits follow, so `2e` still reads as 2·e.
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            var j = i + 1
            if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
            if (j < text.length && text[j].isDigit()) {
                i = j
                while (i < text.length && text[i].isDigit()) i++
            }
        }
        return Token(Kind.NUMBER, text.substring(start, i), start)
    }
}
