package sde.typeset

/**
 * A formula laid out as boxes, ready to be measured and drawn: what a TeX engine builds before it
 * places glyphs. [TexParser] makes one from a small subset of TeX.
 */
sealed interface MathNode {
    /** A run of glyphs of one kind, such as `x`, `12.5`, `sin` or `+`. */
    data class Glyphs(val text: String, val kind: Kind) : MathNode

    data class Row(val items: List<MathNode>) : MathNode

    data class Scripts(val base: MathNode, val sup: MathNode?, val sub: MathNode?) : MathNode

    data class Fraction(val numerator: MathNode, val denominator: MathNode) : MathNode

    data class Root(val radicand: MathNode) : MathNode

    /** A body between delimiters that grow with it, as `\left( … \right)`. An empty delimiter is left out. */
    data class Fenced(val open: String, val body: MathNode, val close: String) : MathNode

    /** ∫ or Σ drawn large, with its limits beside it. */
    data class BigOperator(val symbol: String, val lower: MathNode?, val upper: MathNode?) : MathNode

    /** Horizontal space in ems; negative to pull neighbours together. */
    data class Space(val em: Float) : MathNode

    /** What a glyph run is, which sets its font style and the space around it. */
    enum class Kind {
        /** Italic: x, t, σ. */
        VARIABLE,

        /** Upright and unspaced: digits, function names, brackets, unary minus. */
        UPRIGHT,

        /** A binary operator with medium space on both sides: + − ·. */
        OPERATOR,

        /** A relation with thick space on both sides: = ≈ →. */
        RELATION,

        /** A comma or semicolon, with a thin space after it. */
        PUNCTUATION,

        /** Upright words with their spaces kept, from `\text{…}`. */
        TEXT,
    }
}
