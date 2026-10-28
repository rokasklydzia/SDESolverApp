package sde.chart

import kotlin.math.abs
import kotlin.math.max

/**
 * The window of the (t, x) plane a chart shows. Gestures produce new viewports: pinch with
 * [zoomed], drag with [panned]. Screen mapping happens in the UI, since only it knows the pixels.
 */
data class Viewport(val tMin: Double, val tMax: Double, val xMin: Double, val xMax: Double) {
    init {
        require(tMax > tMin && xMax > xMin) { "a viewport needs a positive width and height" }
    }

    val width: Double get() = tMax - tMin
    val height: Double get() = xMax - xMin

    /** Fraction across the window, 0 at the left or bottom edge and 1 at the right or top. */
    fun fractionT(t: Double): Double = (t - tMin) / width
    fun fractionX(x: Double): Double = (x - xMin) / height

    /**
     * Zooms by [factorT] and [factorX] (above 1 zooms in) keeping the point (focusT, focusX) fixed
     * on screen, which is how a pinch should feel.
     */
    fun zoomed(factorT: Double, factorX: Double, focusT: Double, focusX: Double): Viewport {
        require(factorT > 0 && factorX > 0) { "zoom factors must be positive" }
        return Viewport(
            tMin = focusT - (focusT - tMin) / factorT,
            tMax = focusT + (tMax - focusT) / factorT,
            xMin = focusX - (focusX - xMin) / factorX,
            xMax = focusX + (xMax - focusX) / factorX,
        )
    }

    /** Moves the window by (dt, dx) in data units. */
    fun panned(dt: Double, dx: Double): Viewport = Viewport(tMin + dt, tMax + dt, xMin + dx, xMax + dx)

    /**
     * Keeps the time window inside [bounds] in time (no scrolling past the simulated horizon) and
     * not wider than it, and stops zooming in once [minWidthFraction] of the horizon is shown.
     */
    fun constrainedTime(bounds: ClosedFloatingPointRange<Double>, minWidthFraction: Double = 0.01): Viewport {
        val full = bounds.endInclusive - bounds.start
        val w = width.coerceIn(full * minWidthFraction, full)
        val start = (tMin + (width - w) / 2).coerceIn(bounds.start, bounds.endInclusive - w)
        return copy(tMin = start, tMax = start + w)
    }

    companion object {
        /** A viewport over the time range and x range with a margin of [padding] × the x span above and below. */
        fun fit(t: ClosedFloatingPointRange<Double>, x: ClosedFloatingPointRange<Double>, padding: Double = 0.08): Viewport {
            var lo = x.start
            var hi = x.endInclusive
            if (!(hi > lo)) {
                val half = max(abs(lo) * 0.1, 0.5)
                lo -= half
                hi += half
            }
            val margin = (hi - lo) * padding
            return Viewport(t.start, t.endInclusive, lo - margin, hi + margin)
        }
    }
}
