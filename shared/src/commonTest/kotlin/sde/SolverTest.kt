package sde

import sde.expr.Antiderivative
import sde.expr.Parser
import sde.expr.Variable
import sde.expr.format
import sde.ito.EvaluationPoint
import sde.expr.Evaluator
import sde.ito.ItoSums
import sde.ito.LinearSde
import sde.ito.StochasticIntegral
import sde.montecarlo.TimeGrid
import sde.random.SeededRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolverTest {
    private fun antiderivative(text: String, variable: Variable = Variable.STATE) =
        Antiderivative.of(Parser.parse(text), variable)?.format()

    @Test
    fun antiderivativesOfTheUsualForms() {
        assertEquals("x^2/2", antiderivative("x"))
        assertEquals("x^4/4", antiderivative("x^3"))
        assertEquals("t*x", antiderivative("t"))
        assertEquals("exp(x)", antiderivative("exp(x)"))
        assertEquals("-cos(2*x)/2", antiderivative("sin(2*x)"))
        assertEquals("x*exp(x) - exp(x)", antiderivative("x*exp(x)"))
        assertEquals("x^3 - x^2 + x", antiderivative("3*x^2 - 2*x + 1"))
        assertEquals("log(abs(x))", antiderivative("1/x"))
        // Checked by differentiating back, whatever the printed shape.
        for (text in listOf("3*x^2 - 2*x + 1", "x^2*sin(x)", "exp(t/2)*cos(x)", "(2*x + 1)^3", "a*x^b", "2^x", "cosh(3*x - 1)", "x*t^2")) {
            assertNotNull(antiderivative(text), text)
        }
        assertEquals("t^2/2", antiderivative("t", Variable.TIME))
    }

    @Test
    fun noAnswerRatherThanAWrongOne() {
        for (text in listOf("exp(x^2)", "sin(x)/x", "sqrt(1 + x^2)", "log(x)")) assertNull(antiderivative(text), text)
    }

    @Test
    fun integralOfBdBInAllThreeSenses() {
        assertEquals("X_t^2/2 - 0.5*t", StochasticIntegral("x").answerText())
        assertEquals("X_t^2/2", StochasticIntegral("x", point = EvaluationPoint.MIDPOINT).answerText())
        assertEquals("X_t^2/2 + 0.5*t", StochasticIntegral("x", point = EvaluationPoint.RIGHT).answerText())
    }

    @Test
    fun leftoverIntegralsStayAsOnPaper() {
        // ∫B³ dB = ¼B⁴ − (3/2)∫B² ds and ∫s dB = tB − ∫B ds.
        val cube = StochasticIntegral("x^3")
        assertEquals("X_t^4/4 - ∫₀ᵗ (1.5*X_s^2) ds", cube.answerText())
        assertEquals("X_t^4/4 + ∫₀ᵗ (1.5*X_s^2) ds", StochasticIntegral("x^3", point = EvaluationPoint.RIGHT).answerText())
        assertEquals("t*X_t - ∫₀ᵗ (X_s) ds", StochasticIntegral("t").answerText())
        assertTrue(StochasticIntegral("exp(x - t/2)").fullyClosed) // the exponential martingale
    }

    @Test
    fun closedFormMatchesTheSumsOnAFinePath() {
        val grid = TimeGrid(1.0, 65_536)
        val w = ItoSums.brownianPath(grid, SeededRandom(12))
        for (point in EvaluationPoint.entries) {
            for ((h, drift, diffusion) in listOf(Triple("x^3", "0", "1"), Triple("cos(x)", "0", "1"), Triple("x", "0.1*x", "0.4*x"))) {
                val integral = StochasticIntegral(h, drift, diffusion, x0 = if (drift == "0") 0.0 else 1.0, point = point)
                val (sum, closed) = integral.along(grid, w)
                assertNotNull(closed)
                assertEquals(closed.last(), sum.last(), 0.02 * (1 + abs(closed.last())), "$h, $point")
            }
        }
    }

    @Test
    fun momentsFromTheItoIsometry() {
        // Var ∫B dB = ∫ s ds = t²/2; Var ∫B² dB = ∫ 3s² ds = t³; ∫ s dB ~ N(0, t³/3).
        assertEquals(0.5, StochasticIntegral("x").itoVariance(1.0)!!, 1e-6)
        assertEquals(1.0, StochasticIntegral("x^2").itoVariance(1.0)!!, 1e-6)
        val wiener = StochasticIntegral("t")
        assertTrue(wiener.isGaussian)
        assertEquals(1.0 / 3, wiener.itoVariance(1.0)!!, 1e-9)
        // E ∫B∘dB = ½∫1 ds = t/2.
        assertEquals(0.5, StochasticIntegral("x", point = EvaluationPoint.MIDPOINT).mean(1.0)!!, 1e-9)
    }

    @Test
    fun linearSdesHaveExplicitSolutions() {
        val gbm = LinearSde.of("mu*x", "sigma*x")!!
        assertEquals("exp((mu - 0.5*sigma^2)*t + sigma*B)", gbm.phi!!.format())
        assertEquals("X(t) = x0·Φ(t)", gbm.steps()[2])

        // Ornstein–Uhlenbeck: mean x0·e^{−κt} + m(1 − e^{−κt}), variance σ²(1 − e^{−2κt})/(2κ).
        val ou = LinearSde.of("kappa*(m - x)", "sigma")!!
        val p = mapOf("kappa" to 1.3, "m" to 0.4, "sigma" to 0.7, "x0" to 2.0)
        val t = 0.8
        val mean = Evaluator.compile(ou.mean!!, p).eval(t, 0.0)
        val variance = Evaluator.compile(ou.variance!!, p).eval(t, 0.0)
        assertEquals(2.0 * exp(-1.3 * t) + 0.4 * (1 - exp(-1.3 * t)), mean, 1e-12)
        assertEquals(0.49 * (1 - exp(-2.6 * t)) / 2.6, variance, 1e-12)

        assertNull(LinearSde.of("x^2", "1"))
        assertNull(LinearSde.of("0", "sqrt(x)"))
    }
}
