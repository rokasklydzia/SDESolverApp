package sde.chart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** Axis ticks at "nice" steps of 1, 2 or 5 × 10ⁿ. */
object Ticks {
    /** About [target] tick values covering [min, max], with the step that separates them. */
    fun nice(min: Double, max: Double, target: Int = 5): Pair<List<Double>, Double> {
        require(max > min) { "an axis needs max > min" }
        val rough = (max - min) / target.coerceAtLeast(1)
        val magnitude = 10.0.pow(floor(log10(rough)))
        val step = when (rough / magnitude) {
            in 0.0..1.5 -> 1.0
            in 1.5..3.0 -> 2.0
            in 3.0..7.0 -> 5.0
            else -> 10.0
        } * magnitude
        val first = ceil(min / step)
        val last = floor(max / step)
        val ticks = (first.toLong()..last.toLong()).map { cleanZero(it * step) }
        return ticks to step
    }

    /** A tick label with just enough decimals for [step]: 0.25 steps give "0.25", 50 steps give "150". */
    fun label(value: Double, step: Double, decimalSeparator: Char = '.'): String =
        formatFixed(value, decimalsFor(step), decimalSeparator)

    fun decimalsFor(step: Double): Int = (-floor(log10(step) + 1e-9)).toInt().coerceIn(0, 10)

    /** Fixed-point formatting without platform APIs, so it works the same on Android and iOS. */
    fun formatFixed(value: Double, decimals: Int, decimalSeparator: Char = '.'): String {
        val factor = 10.0.pow(decimals)
        val scaled = (abs(value) * factor).roundToLong()
        val whole = scaled / factor.toLong()
        val fraction = scaled % factor.toLong()
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        if (decimals == 0) return "$sign$whole"
        return "$sign$whole$decimalSeparator${fraction.toString().padStart(decimals, '0')}"
    }

    private fun cleanZero(v: Double) = if (abs(v) < 1e-12) 0.0 else v
}
