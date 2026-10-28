package app.sde.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sde.chart.Viewport
import sde.montecarlo.TimeGrid
import java.text.DecimalFormatSymbols
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One line on a [CurveChart]: [values] sampled on [grid]. */
class Curve(
    val grid: TimeGrid,
    val values: DoubleArray,
    val color: Color,
    val width: Dp = 2.dp,
    val dashed: Boolean = false,
    /** A dot at the head while the curve draws itself. */
    val head: Boolean = false,
)

/** A shaded region between two curves on the same grid, e.g. mean ± 2 standard errors. */
class Band(val grid: TimeGrid, val lower: DoubleArray, val upper: DoubleArray, val color: Color)

/** The smallest and largest value of every curve and band, for fitting the axes. */
fun valueRange(curves: List<Curve>, bands: List<Band> = emptyList()): ClosedFloatingPointRange<Double> {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    fun take(values: DoubleArray) {
        for (v in values) if (v.isFinite()) { lo = min(lo, v); hi = max(hi, v) }
    }
    curves.forEach { take(it.values) }
    bands.forEach { take(it.lower); take(it.upper) }
    return if (lo <= hi) lo..hi else -1.0..1.0
}

/**
 * Curves in time that draw themselves up to the time [revealedTime] returns, with the same axes
 * and gestures as [LiveChart]. Curves may sit on different grids, e.g. a sum on a coarse mesh
 * against its limit on a fine one.
 */
@Composable
fun CurveChart(
    horizon: Double,
    curves: List<Curve>,
    revealedTime: () -> Double,
    viewport: Viewport,
    onViewportChange: (Viewport) -> Unit,
    onResetViewport: () -> Unit,
    modifier: Modifier = Modifier,
    bands: List<Band> = emptyList(),
    height: Dp = 300.dp,
) {
    val colors = LocalChartColors.current
    val textMeasurer = rememberTextMeasurer()
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val labelStyle = TextStyle(color = colors.label, fontSize = 10.sp)
    val currentViewport by rememberUpdatedState(viewport)
    val currentOnChange by rememberUpdatedState(onViewportChange)
    val currentOnReset by rememberUpdatedState(onResetViewport)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .chartGestures(horizon, { currentViewport }, { currentOnChange(it) }, { currentOnReset() }),
    ) {
        val plot = plotArea(size, this)
        drawAxes(plot, viewport, textMeasurer, labelStyle, colors, separator)
        val until = revealedTime()
        clipRect(plot.left, plot.top, plot.right, plot.bottom) {
            for (band in bands) drawBand(plot, viewport, band, until)
            for (curve in curves) drawCurve(plot, viewport, curve, until)
        }
    }
}

/** The index range of [grid] points inside the viewport and before [until], with a stride of about two points per pixel. */
private class Visible(val first: Int, val end: Int, val stride: Int, val last: Double)

private fun visible(plot: PlotArea, v: Viewport, grid: TimeGrid, until: Double): Visible {
    val last = min(until, grid.horizon) / grid.dt
    val first = max(0, floor(v.tMin / grid.dt).toInt() - 1)
    val end = min(floor(last).toInt(), (v.tMax / grid.dt).toInt() + 1)
    val stride = max(1, (end - first) / max(1, (plot.width * 2).toInt()))
    return Visible(first, end, stride, last)
}

/** Value at the fractional grid index [at], linear between grid points so heads glide. */
private fun interpolate(values: DoubleArray, at: Double): Double {
    val i = floor(at).toInt().coerceIn(0, values.size - 1)
    val next = min(i + 1, values.size - 1)
    return values[i] + (at - i) * (values[next] - values[i])
}

private fun DrawScope.drawCurve(plot: PlotArea, v: Viewport, curve: Curve, until: Double) {
    val grid = curve.grid
    val r = visible(plot, v, grid, until)
    if (r.end < r.first) return
    val path = Path()
    path.moveTo(toX(plot, v, grid.time(r.first)), toY(plot, v, curve.values[r.first]))
    var i = r.first + r.stride
    while (i <= r.end) {
        path.lineTo(toX(plot, v, grid.time(i)), toY(plot, v, curve.values[i]))
        i += r.stride
    }
    val headValue = interpolate(curve.values, r.last)
    val headTime = r.last * grid.dt
    if (r.last > r.end) path.lineTo(toX(plot, v, headTime), toY(plot, v, headValue))
    val width = curve.width.toPx()
    val effect = if (curve.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
    drawPath(path, curve.color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    if (curve.head && r.last < grid.steps) {
        drawCircle(curve.color, radius = width + 1.5.dp.toPx(), center = Offset(toX(plot, v, headTime), toY(plot, v, headValue)))
    }
}

private fun DrawScope.drawBand(plot: PlotArea, v: Viewport, band: Band, until: Double) {
    val grid = band.grid
    val r = visible(plot, v, grid, until)
    if (r.end <= r.first) return
    val path = Path()
    path.moveTo(toX(plot, v, grid.time(r.first)), toY(plot, v, band.upper[r.first]))
    var i = r.first
    while (i <= r.end) {
        path.lineTo(toX(plot, v, grid.time(i)), toY(plot, v, band.upper[i]))
        i += r.stride
    }
    i = r.end
    while (i >= r.first) {
        path.lineTo(toX(plot, v, grid.time(i)), toY(plot, v, band.lower[i]))
        i -= r.stride
    }
    path.close()
    drawPath(path, band.color)
}
