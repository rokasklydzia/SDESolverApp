package sde.analytic

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Black–Scholes prices of European options on a stock without dividends. */
object BlackScholes {
    fun call(spot: Double, strike: Double, rate: Double, volatility: Double, maturity: Double): Double {
        val (d1, d2) = d(spot, strike, rate, volatility, maturity)
        return spot * Normal.cdf(d1) - strike * exp(-rate * maturity) * Normal.cdf(d2)
    }

    fun put(spot: Double, strike: Double, rate: Double, volatility: Double, maturity: Double): Double {
        val (d1, d2) = d(spot, strike, rate, volatility, maturity)
        return strike * exp(-rate * maturity) * Normal.cdf(-d2) - spot * Normal.cdf(-d1)
    }

    private fun d(spot: Double, strike: Double, rate: Double, volatility: Double, maturity: Double): Pair<Double, Double> {
        require(spot > 0 && strike > 0 && volatility > 0 && maturity > 0) { "spot, strike, volatility and maturity must be positive" }
        val volSqrtT = volatility * sqrt(maturity)
        val d1 = (ln(spot / strike) + (rate + 0.5 * volatility * volatility) * maturity) / volSqrtT
        return d1 to d1 - volSqrtT
    }
}
