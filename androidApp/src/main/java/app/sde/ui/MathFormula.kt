package app.sde.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sde.typeset.MathNode
import sde.typeset.MathNode.Kind
import sde.typeset.TexParser

/**
 * A formula written in TeX (see [TexParser] for the subset), typeset with fractions, scripts,
 * roots and growing brackets and drawn in the theme's colour. Too wide for the screen, it scrolls
 * sideways. A formula that cannot be read is shown as plain text.
 */
@Composable
fun MathFormula(
    tex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val node = remember(tex) { runCatching { TexParser.parse(tex) }.getOrNull() }
    if (node == null) {
        Formula(tex)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val em = with(density) { fontSize.toPx() }
    val box = remember(node, em, measurer, density) { Typesetter(measurer, em, density).layout(node, 1f) }
    val pad = 0.12f * em
    with(density) {
        Box(modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
            Canvas(Modifier.size((box.width + 2 * pad).toDp(), (box.ascent + box.descent + 2 * pad).toDp())) {
                box.draw(this, pad, pad + box.ascent, color)
            }
        }
    }
}

/** A measured piece of a formula: drawn with its left end at x and its baseline at y. */
private class LaidOut(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val draw: DrawScope.(x: Float, baseline: Float, color: Color) -> Unit,
)

/** Places [MathNode]s the way TeX would, roughly, in units of the font size [em]. */
private class Typesetter(val measurer: TextMeasurer, val em: Float, val density: Density) {
    private val family = FontFamily.Serif

    fun layout(node: MathNode, scale: Float): LaidOut = when (node) {
        is MathNode.Glyphs -> glyphs(node, scale)
        is MathNode.Row -> row(node.items.map { layout(it, scale) })
        is MathNode.Space -> LaidOut(node.em * em * scale, 0f, 0f) { _, _, _ -> }
        is MathNode.Scripts -> scripts(layout(node.base, scale), node.sup, node.sub, scale, operator = false)
        is MathNode.BigOperator -> scripts(
            glyphs(MathNode.Glyphs(node.symbol, Kind.UPRIGHT), scale * 1.45f),
            node.upper,
            node.lower,
            scale,
            operator = true,
        )
        is MathNode.Fraction -> fraction(node, scale)
        is MathNode.Root -> root(layout(node.radicand, scale), scale)
        is MathNode.Fenced -> fenced(node, scale)
    }

    private fun text(text: String, scale: Float, italic: Boolean): LaidOut {
        val style = TextStyle(
            fontFamily = family,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontSize = with(density) { (em * scale).toSp() },
        )
        val result = measurer.measure(text, style, softWrap = false, maxLines = 1)
        val baseline = result.firstBaseline
        // Fixed ascent and descent keep fractions and scripts evenly spaced, whatever the glyphs.
        return LaidOut(result.size.width.toFloat(), 0.72f * em * scale, 0.22f * em * scale) { x, y, color ->
            drawText(result, color, Offset(x, y - baseline))
        }
    }

    private fun glyphs(node: MathNode.Glyphs, scale: Float): LaidOut {
        val italic = node.kind == Kind.VARIABLE
        val core = text(node.text, scale, italic)
        // Operators get less room in scripts, as in TeX.
        val room = if (scale < 1f) 0.5f else 1f
        val (before, after) = when (node.kind) {
            Kind.OPERATOR -> 0.22f * room to 0.22f * room
            Kind.RELATION -> 0.28f * room to 0.28f * room
            Kind.PUNCTUATION -> 0f to 0.17f
            // A little room for the lean of italic letters.
            Kind.VARIABLE -> 0f to 0.05f
            else -> 0f to 0f
        }
        val left = before * em * scale
        return LaidOut(left + core.width + after * em * scale, core.ascent, core.descent) { x, y, color ->
            core.draw(this, x + left, y, color)
        }
    }

    private fun row(items: List<LaidOut>): LaidOut = LaidOut(
        items.sumOf { it.width.toDouble() }.toFloat(),
        items.maxOfOrNull { it.ascent } ?: 0f,
        items.maxOfOrNull { it.descent } ?: 0f,
    ) { x, y, color ->
        var at = x
        for (item in items) {
            item.draw(this, at, y, color)
            at += item.width
        }
    }

    private fun scripts(base: LaidOut, supNode: MathNode?, subNode: MathNode?, scale: Float, operator: Boolean): LaidOut {
        val small = (scale * 0.7f).coerceAtLeast(0.5f)
        val sup = supNode?.let { layout(it, small) }
        val sub = subNode?.let { layout(it, small) }
        val unit = em * scale
        var supShift = if (operator) base.ascent * 0.8f else maxOf(0.42f * unit, base.ascent - 0.3f * unit)
        val subShift = if (operator) base.descent * 0.9f else maxOf(0.22f * unit, base.descent)
        if (sup != null && sub != null) {
            // Keep a gap between the two scripts.
            val gap = (supShift - sup.descent) - (sub.ascent - subShift)
            if (gap < 0.12f * unit) supShift += 0.12f * unit - gap
        }
        val scriptWidth = maxOf(sup?.width ?: 0f, sub?.width ?: 0f)
        val trailing = 0.06f * unit
        return LaidOut(
            base.width + scriptWidth + trailing,
            maxOf(base.ascent, sup?.let { supShift + it.ascent } ?: 0f),
            maxOf(base.descent, sub?.let { subShift + it.descent } ?: 0f),
        ) { x, y, color ->
            base.draw(this, x, y, color)
            sup?.draw?.invoke(this, x + base.width, y - supShift, color)
            sub?.draw?.invoke(this, x + base.width, y + subShift, color)
        }
    }

    private fun fraction(node: MathNode.Fraction, scale: Float): LaidOut {
        val inner = (scale * 0.88f).coerceAtLeast(0.55f)
        val num = layout(node.numerator, inner)
        val den = layout(node.denominator, inner)
        val unit = em * scale
        val axis = 0.25f * unit
        val bar = maxOf(1f, 0.05f * unit)
        val gap = 0.12f * unit
        val side = 0.1f * unit
        val width = maxOf(num.width, den.width) + 2 * side
        val numBaseline = axis + bar / 2 + gap + num.descent
        val denBaseline = -axis + bar / 2 + gap + den.ascent
        return LaidOut(width, numBaseline + num.ascent, denBaseline + den.descent) { x, y, color ->
            drawLine(color, Offset(x + side * 0.5f, y - axis), Offset(x + width - side * 0.5f, y - axis), strokeWidth = bar)
            num.draw(this, x + (width - num.width) / 2, y - numBaseline, color)
            den.draw(this, x + (width - den.width) / 2, y + denBaseline, color)
        }
    }

    private fun root(radicand: LaidOut, scale: Float): LaidOut {
        val unit = em * scale
        val stroke = 0.055f * unit
        val sign = 0.55f * unit
        val top = radicand.ascent + 0.14f * unit
        val bottom = radicand.descent + 0.04f * unit
        val width = sign + radicand.width + 0.1f * unit
        return LaidOut(width, top + stroke, bottom + stroke) { x, y, color ->
            val mid = y - 0.3f * unit
            val path = Path().apply {
                moveTo(x + 0.02f * unit, mid + 0.04f * unit)
                lineTo(x + 0.13f * unit, mid - 0.04f * unit)
                lineTo(x + 0.3f * unit, y + bottom)
                lineTo(x + sign, y - top)
                lineTo(x + width, y - top)
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            radicand.draw(this, x + sign + 0.04f * unit, y, color)
        }
    }

    private fun fenced(node: MathNode.Fenced, scale: Float): LaidOut {
        val body = layout(node.body, scale)
        val unit = em * scale
        // Around one line of text the font's own brackets look best; taller bodies get drawn ones.
        if (body.ascent <= 0.8f * unit && body.descent <= 0.3f * unit) {
            val parts = listOfNotNull(
                node.open.takeIf { it.isNotEmpty() }?.let { text(it, scale, italic = false) },
                body,
                node.close.takeIf { it.isNotEmpty() }?.let { text(it, scale, italic = false) },
            )
            return row(parts)
        }
        val top = body.ascent + 0.08f * unit
        val bottom = body.descent + 0.08f * unit
        val height = top + bottom
        val delimiterWidth = (0.22f + 0.06f * height / unit).coerceAtMost(0.5f) * unit
        val open = delimiter(node.open, delimiterWidth, top, bottom, unit, opening = true)
        val close = delimiter(node.close, delimiterWidth, top, bottom, unit, opening = false)
        return row(listOfNotNull(open, body, close))
    }

    private fun delimiter(symbol: String, width: Float, top: Float, bottom: Float, unit: Float, opening: Boolean): LaidOut? {
        if (symbol.isEmpty()) return null
        val stroke = 0.06f * unit
        val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        return LaidOut(width, top, bottom) { x, y, color ->
            val upper = y - top
            val lower = y + bottom
            val inner = if (opening) x + width * 0.85f else x + width * 0.15f
            val outer = if (opening) x + width * 0.2f else x + width * 0.8f
            val centre = x + width / 2
            val path = Path()
            when (symbol) {
                "(", ")" -> {
                    path.moveTo(inner, upper)
                    path.cubicTo(outer, upper + (lower - upper) * 0.25f, outer, lower - (lower - upper) * 0.25f, inner, lower)
                }
                "[", "]" -> {
                    path.moveTo(inner, upper)
                    path.lineTo(outer, upper)
                    path.lineTo(outer, lower)
                    path.lineTo(inner, lower)
                }
                "⟨", "⟩" -> {
                    path.moveTo(inner, upper)
                    path.lineTo(outer, (upper + lower) / 2)
                    path.lineTo(inner, lower)
                }
                "‖" -> {
                    path.moveTo(centre - 0.06f * unit, upper)
                    path.lineTo(centre - 0.06f * unit, lower)
                    path.moveTo(centre + 0.06f * unit, upper)
                    path.lineTo(centre + 0.06f * unit, lower)
                }
                "{", "}" -> {
                    val mid = (upper + lower) / 2
                    path.moveTo(inner, upper)
                    path.quadraticBezierTo((inner + outer) / 2, upper, (inner + outer) / 2, upper + (mid - upper) * 0.3f)
                    path.lineTo((inner + outer) / 2, mid - (mid - upper) * 0.3f)
                    path.quadraticBezierTo((inner + outer) / 2, mid, outer, mid)
                    path.quadraticBezierTo((inner + outer) / 2, mid, (inner + outer) / 2, mid + (lower - mid) * 0.3f)
                    path.lineTo((inner + outer) / 2, lower - (lower - mid) * 0.3f)
                    path.quadraticBezierTo((inner + outer) / 2, lower, inner, lower)
                }
                else -> {
                    // | and anything else: a straight bar.
                    path.moveTo(centre, upper)
                    path.lineTo(centre, lower)
                }
            }
            drawPath(path, color, style = style)
        }
    }
}
