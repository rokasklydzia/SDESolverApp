package app.sde.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sde.chart.Ticks
import sde.chart.Viewport
import sde.live.Ensemble
import java.text.DecimalFormatSymbols
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What the chart shows. */
data class ChartOptions(
    val shownPaths: Int,
    val showBand: Boolean = true,
    val showMean: Boolean = true,
    val showHistogram: Boolean = true,
)

/**
 * The live chart: paths that draw themselves up to [revealed] (a fractional grid step), the mean
 * and quantile bands, and beside it the histogram of X at the revealed time against the exact
 * density [pdf] when the model has one. Pinch zooms, drag pans, double tap resets.
 */
@Composable
fun LiveChart(
    ensemble: Ensemble,
    /** Read while drawing, not composing, so the animation only redraws the canvas. */
    revealed: () -> Float,
    viewport: Viewport,
    onViewportChange: (Viewport) -> Unit,
    onResetViewport: () -> Unit,
    options: ChartOptions,
    pdf: ((t: Double, x: Double) -> Double)?,
    modifier: Modifier = Modifier,
    height: Dp = 320.dp,
) {
    val colors = LocalChartColors.current
    val textMeasurer = rememberTextMeasurer()
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val labelStyle = TextStyle(color = colors.label, fontSize = 10.sp)
    // Gesture handlers outlive a composition, so they read the latest values through these.
    val currentViewport by rememberUpdatedState(viewport)
    val currentOnChange by rememberUpdatedState(onViewportChange)
    val currentOnReset by rememberUpdatedState(onResetViewport)

    Row(modifier.fillMaxWidth().height(height)) {
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { currentOnReset() })
                }
                .pointerInput(ensemble.grid.horizon) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val plot = plotArea(Size(size.width.toFloat(), size.height.toFloat()), this)
                        val v = currentViewport
                        val focusT = v.tMin + (centroid.x - plot.left) / plot.width * v.width
                        val focusX = v.xMax - (centroid.y - plot.top) / plot.height * v.height
                        val zoomed = v.zoomed(zoom.toDouble(), zoom.toDouble(), focusT, focusX)
                        val moved = zoomed
                            .panned(-pan.x / plot.width * zoomed.width, pan.y / plot.height * zoomed.height)
                            .constrainedTime(0.0..ensemble.grid.horizon)
                        currentOnChange(moved)
                    }
                },
        ) {
            val plot = plotArea(size, this)
            drawAxes(plot, viewport, textMeasurer, labelStyle, colors, separator)
            clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                drawEnsemble(plot, ensemble, viewport, revealed(), options, colors)
            }
        }
        if (options.showHistogram) {
            Canvas(Modifier.width(72.dp).fillMaxHeight()) {
                val chartPlot = plotArea(size, this)
                val plot = PlotArea(0f, chartPlot.top, size.width - 4.dp.toPx(), chartPlot.bottom)
                drawMarginal(plot, ensemble, viewport, revealed(), pdf, colors)
            }
        }
    }
}

data class PlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Room for the value labels on the left and the time labels below. */
private fun plotArea(size: Size, density: Density) = with(density) {
    PlotArea(left = 40.dp.toPx(), top = 6.dp.toPx(), right = size.width - 4.dp.toPx(), bottom = size.height - 20.dp.toPx())
}

private fun toX(plot: PlotArea, v: Viewport, t: Double) = (plot.left + v.fractionT(t) * plot.width).toFloat()
private fun toY(plot: PlotArea, v: Viewport, x: Double) = (plot.bottom - v.fractionX(x) * plot.height).toFloat()

private fun DrawScope.drawAxes(
    plot: PlotArea,
    v: Viewport,
    measurer: TextMeasurer,
    style: TextStyle,
    colors: ChartColors,
    separator: Char,
) {
    val (xTicks, xStep) = Ticks.nice(v.xMin, v.xMax, 5)
    for (x in xTicks) {
        val y = toY(plot, v, x)
        drawLine(colors.grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
        val label = measurer.measure(Ticks.label(x, xStep, separator), style)
        drawText(label, topLeft = Offset(plot.left - label.size.width - 4.dp.toPx(), y - label.size.height / 2f))
    }
    val (tTicks, tStep) = Ticks.nice(v.tMin, v.tMax, 5)
    for (t in tTicks) {
        val x = toX(plot, v, t)
        drawLine(colors.grid, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
        val label = measurer.measure(Ticks.label(t, tStep, separator), style)
        drawText(label, topLeft = Offset(x - label.size.width / 2f, plot.bottom + 3.dp.toPx()))
    }
    drawLine(colors.axis, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1.dp.toPx())
    drawLine(colors.axis, Offset(plot.left, plot.top), Offset(plot.left, plot.bottom), strokeWidth = 1.dp.toPx())
}

private fun DrawScope.drawEnsemble(
    plot: PlotArea,
    ensemble: Ensemble,
    v: Viewport,
    revealed: Float,
    options: ChartOptions,
    colors: ChartColors,
) {
    val grid = ensemble.grid
    val last = min(revealed, grid.steps.toFloat())
    val lastWhole = floor(last).toInt()
    val fraction = last - lastWhole
    // Only the visible time window is drawn, at most about two points per pixel.
    val first = max(0, floor(v.tMin / grid.dt).toInt() - 1)
    val end = min(lastWhole, (v.tMax / grid.dt).toInt() + 1)
    val stride = max(1, (end - first) / max(1, (plot.width * 2).toInt()))

    /** Linear interpolation inside the current step, so heads glide instead of jumping. */
    fun head(value: (Int) -> Float): Float {
        val next = min(lastWhole + 1, grid.steps)
        return value(lastWhole) + fraction * (value(next) - value(lastWhole))
    }

    fun series(value: (Int) -> Float): Path {
        val path = Path()
        if (end < first) return path
        path.moveTo(toX(plot, v, grid.time(first)), toY(plot, v, value(first).toDouble()))
        var i = first + stride
        while (i <= end) {
            path.lineTo(toX(plot, v, grid.time(i)), toY(plot, v, value(i).toDouble()))
            i += stride
        }
        if (fraction > 0f && lastWhole < grid.steps) {
            path.lineTo(toX(plot, v, last.toDouble() * grid.dt), toY(plot, v, head(value).toDouble()))
        }
        return path
    }

    if (options.showBand && ensemble.bands.size >= 2 && end >= first) {
        // Outer band (first and last levels), then inner band (second and second to last).
        val levels = ensemble.bands.size
        val pairs = listOf(0 to levels - 1, 1 to levels - 2).filter { it.first < it.second }
        for ((index, pair) in pairs.withIndex()) {
            val lower = ensemble.bands[pair.first]
            val upper = ensemble.bands[pair.second]
            val band = Path()
            band.moveTo(toX(plot, v, grid.time(first)), toY(plot, v, upper[first].toDouble()))
            var i = first
            while (i <= end) {
                band.lineTo(toX(plot, v, grid.time(i)), toY(plot, v, upper[i].toDouble()))
                i += stride
            }
            i = end
            while (i >= first) {
                band.lineTo(toX(plot, v, grid.time(i)), toY(plot, v, lower[i].toDouble()))
                i -= stride
            }
            band.close()
            drawPath(band, colors.band.copy(alpha = if (index == 0) 0.10f else 0.14f))
        }
    }

    val shown = min(options.shownPaths, ensemble.paths)
    val thin = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Many paths fade so their density shows; a few stay crisp.
    val pathAlpha = (8f / (shown + 8)).coerceIn(0.10f, 0.8f)
    for (p in 1 until shown) {
        drawPath(series { ensemble.value(p, it) }, colors.path.copy(alpha = pathAlpha), style = thin)
    }
    if (options.showMean) {
        drawPath(
            series { ensemble.mean[it] },
            colors.mean,
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }
    if (shown >= 1) {
        // One path in the accent colour, with a dot at its head, to follow by eye.
        drawPath(
            series { ensemble.value(0, it) },
            colors.highlight,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val tip = Offset(toX(plot, v, last.toDouble() * grid.dt), toY(plot, v, head { ensemble.value(0, it) }.toDouble()))
        drawCircle(colors.highlight, radius = 3.5.dp.toPx(), center = tip)
    }
}

/** The distribution of X at the revealed time, sideways so its value axis lines up with the chart's. */
private fun DrawScope.drawMarginal(
    plot: PlotArea,
    ensemble: Ensemble,
    v: Viewport,
    revealed: Float,
    pdf: ((Double, Double) -> Double)?,
    colors: ChartColors,
) {
    val step = floor(min(revealed, ensemble.grid.steps.toFloat())).toInt()
    val t = ensemble.grid.time(step)
    val bins = 36
    val histogram = ensemble.histogram(step, bins, v.xMin, v.xMax)
    val samples = 120
    val curve = if (pdf != null && t > 0) DoubleArray(samples + 1) { pdf(t, v.xMin + it * v.height / samples) } else null
    var peak = (0 until bins).maxOf { histogram.density(it) }
    if (curve != null) peak = max(peak, curve.max())
    if (!(peak > 0)) return

    val binHeight = plot.height / bins
    for (b in 0 until bins) {
        val length = (histogram.density(b) / peak * plot.width).toFloat()
        val top = plot.bottom - (b + 1) * binHeight
        drawRect(colors.histogram.copy(alpha = 0.45f), topLeft = Offset(plot.left, top + 0.5f), size = Size(length, binHeight - 1f))
    }
    if (curve != null) {
        val path = Path()
        for (i in curve.indices) {
            val x = plot.left + (curve[i] / peak * plot.width).toFloat()
            val y = plot.bottom - i.toFloat() / samples * plot.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.density, style = Stroke(width = 1.5.dp.toPx()))
    }
    drawLine(colors.axis, Offset(plot.left, plot.top), Offset(plot.left, plot.bottom), strokeWidth = 1.dp.toPx())
}
