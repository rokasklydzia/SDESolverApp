package sde

import sde.expr.Derivative
import sde.expr.Evaluator
import sde.expr.ExpressionException
import sde.expr.Parser
import sde.expr.format
import sde.model.CustomSde
import sde.model.GeometricBrownianMotion
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExpressionTest {
    private fun value(text: String, x: Double = 2.0, t: Double = 0.5, p: Map<String, Double> = emptyMap()) =
        Evaluator.compile(Parser.parse(text), p).eval(t, x)

    @Test
    fun precedenceFollowsMathematicalConvention() {
        assertEquals(7.0, value("1 + 2*3"))
        assertEquals(-4.0, value("-x^2")) // −(x²), as on paper
        assertEquals(512.0, value("2^3^2")) // 2^(3^2)
        assertEquals(0.25, value("x^-2"))
        assertEquals(1.0, value("8/4/2"))
        assertEquals(64.0, value("2**6"))
    }

    @Test
    fun implicitMultiplicationReadsLikeHandwriting() {
        assertEquals(8.0, value("2x^2"))
        assertEquals(-2.0, value("x(1 - x)"))
        assertEquals(0.6, value("0.3 sigma x", p = mapOf("sigma" to 1.0)), 1e-15)
        assertEquals(2 * PI, value("2pi"), 1e-15)
        assertEquals(2 * E, value("2e"), 1e-15)
        assertEquals(0.002, value("2e-3"), 1e-18)
    }

    @Test
    fun functionsConstantsAndUnicodeOperators() {
        assertEquals(sqrt(2.0), value("sqrt(x)"), 1e-15)
        assertEquals(3.0, value("max(x, 3)"))
        assertEquals(1.0, value("log(e)"), 1e-15)
        assertEquals(1.0, value("2 × 3 − 5"))
        assertEquals(0.5, value("t"))
        assertEquals(0.08, value("kappa*(theta - x)", p = mapOf("kappa" to 2.0, "theta" to 2.04)), 1e-12)
    }

    @Test
    fun errorsPointAtTheProblem() {
        assertEquals(2, assertFailsWith<ExpressionException> { Parser.parse("2*(x + 1") }.position) // the unmatched "("
        assertEquals(0, assertFailsWith<ExpressionException> { Parser.parse("") }.position)
        assertEquals(4, assertFailsWith<ExpressionException> { Parser.parse("x + ) 1") }.position)
        assertEquals(2, assertFailsWith<ExpressionException> { Parser.parse("x $ 1") }.position)
        assertEquals(0, assertFailsWith<ExpressionException> { Parser.parse("sqrt x") }.position)
        assertEquals(2, assertFailsWith<ExpressionException> { Parser.parse("2 3") }.position)
        assertFailsWith<ExpressionException> { Parser.parse("max(1)") }
        assertFailsWith<ExpressionException> { Evaluator.compile(Parser.parse("sigmax")) }
    }

    @Test
    fun printingGivesTheFormulaBack() {
        for (text in listOf("kappa*(theta - x)", "-x^2", "(-x)^2", "x^(-1)", "a - (b - c)", "a/(b*c)", "sigma*sqrt(x)")) {
            val e = Parser.parse(text)
            assertEquals(e, Parser.parse(e.format()), "round trip of $text")
        }
        assertEquals("kappa*(theta - x)", Parser.parse("kappa*(theta - x)").format())
    }

    @Test
    fun derivativesAreSimplified() {
        fun dx(text: String) = Derivative.dx(Parser.parse(text)).format()
        assertEquals("sigma", dx("sigma*x"))
        assertEquals("2*x", dx("x^2"))
        assertEquals("0", dx("sigma*t"))
        assertEquals("-kappa", dx("kappa*(theta - x)"))
        assertEquals("sigma/(2*sqrt(x))", dx("sigma*sqrt(x)"))
        assertEquals("cos(x)", dx("sin(x)"))
    }

    @Test
    fun derivativesAgreeWithFiniteDifferences() {
        val formulas = listOf(
            "x^3 - 2x", "exp(-x^2)", "log(1 + x^2)", "sin(x)cos(t x)", "x^x", "2^x", "sqrt(1 + x)/x",
            "tanh(x)", "abs(x - 1)", "max(x, 1)", "min(x^2, 3)", "log10(x)", "cosh(x)/sinh(x)",
        )
        for (text in formulas) {
            val e = Parser.parse(text)
            val f = Evaluator.compile(e)
            val df = Evaluator.compile(Derivative.dx(e))
            for (x in listOf(0.3, 1.7, 2.6)) {
                val h = 1e-6
                val numeric = (f.eval(0.4, x + h) - f.eval(0.4, x - h)) / (2 * h)
                assertEquals(numeric, df.eval(0.4, x), 1e-5 * maxOf(1.0, kotlin.math.abs(numeric)), "d/dx $text at $x")
            }
        }
    }

    @Test
    fun customSdeMatchesThePresetItDescribes() {
        val custom = CustomSde("mu*x", "sigma*x", mapOf("mu" to 0.05, "sigma" to 0.2))
        val preset = GeometricBrownianMotion(0.05, 0.2)
        for (x in listOf(50.0, 100.0, 150.0)) {
            assertEquals(preset.drift(0.0, x), custom.drift(0.0, x), 1e-12)
            assertEquals(preset.diffusion(0.0, x), custom.diffusion(0.0, x), 1e-12)
            assertEquals(preset.diffusionTimesSlope(0.0, x), custom.diffusionTimesSlope(0.0, x), 1e-9)
        }
        assertEquals(listOf("kappa", "theta", "sigma"), CustomSde.parameterNames("kappa*(theta - x)", "sigma*sqrt(x)"))
    }
}
