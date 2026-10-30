package sde

import sde.catalog.ModelCatalog
import sde.expr.Parser
import sde.expr.differentialTex
import sde.expr.toTex
import sde.ito.EvaluationPoint
import sde.ito.LinearSde
import sde.ito.StochasticIntegral
import sde.typeset.MathNode
import sde.typeset.MathNode.Kind
import sde.typeset.TexParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TypesetTest {
    private fun tex(formula: String, separator: Char = '.') = Parser.parse(formula).toTex(separator)

    private fun glyphs(text: String, kind: Kind) = MathNode.Glyphs(text, kind)

    @Test
    fun formulasBecomeTex() {
        assertEquals("x^{2} - t", tex("x^2 - t"))
        assertEquals("\\kappa \\left(m - x\\right)", tex("kappa*(m - x)"))
        assertEquals("\\sigma \\sqrt{\\left|x\\right|}", tex("sigma*sqrt(abs(x))"))
        assertEquals("e^{x - t/2}", tex("exp(x - t/2)"))
        assertEquals("\\frac{x^{4}}{4}", tex("x^4/4"))
        assertEquals("0.5 x", tex("0.5*x"))
        assertEquals("0,5 x", tex("0.5*x", separator = ','))
        assertEquals("\\max\\left(x - 100, 0\\right)", tex("max(x - 100, 0)"))
        assertEquals("\\ln\\left(x\\right)", tex("log(x)"))
    }

    @Test
    fun productsAreJoinedOnlyWhereTheyCannotBeMisread() {
        assertEquals("2 \\cdot 3^{x}", tex("2*3^x"))
        assertEquals("\\mathrm{rate} \\cdot x", tex("rate*x"))
        assertEquals("t x", tex("t*x"))
        assertEquals("x_{0} \\sigma", tex("x0*sigma"))
    }

    @Test
    fun parsesScriptsFractionsAndLimits() {
        val integral = TexParser.parse("\\int_0^t h\\,\\mathrm{d}X")
        assertIs<MathNode.Row>(integral)
        val op = integral.items[0]
        assertIs<MathNode.BigOperator>(op)
        assertEquals("∫", op.symbol)
        assertEquals(glyphs("0", Kind.UPRIGHT), op.lower)
        assertEquals(glyphs("t", Kind.VARIABLE), op.upper)
        assertEquals(glyphs("d", Kind.UPRIGHT), integral.items[3])

        val fraction = TexParser.parse("\\frac{x^{4}}{4}")
        assertIs<MathNode.Fraction>(fraction)
        assertEquals(MathNode.Scripts(glyphs("x", Kind.VARIABLE), glyphs("4", Kind.UPRIGHT), null), fraction.numerator)
    }

    @Test
    fun minusIsUnaryAtTheStartAndAfterOperators() {
        val row = TexParser.parse("-x + -2 = (-t)") as MathNode.Row
        val minus = row.items.filterIsInstance<MathNode.Glyphs>().filter { it.text == "−" }
        assertEquals(listOf(Kind.UPRIGHT, Kind.UPRIGHT, Kind.UPRIGHT), minus.map { it.kind })
        val binary = TexParser.parse("x - 2") as MathNode.Row
        assertEquals(glyphs("−", Kind.OPERATOR), binary.items[1])
    }

    @Test
    fun numbersKeepTheirDecimalMark() {
        assertEquals(glyphs("12.5", Kind.UPRIGHT), TexParser.parse("12.5"))
        assertEquals(glyphs("0,25", Kind.UPRIGHT), TexParser.parse("0,25"))
        // A comma followed by a space is punctuation, as in max(x, 0).
        val args = TexParser.parse("1, 0") as MathNode.Row
        assertEquals(glyphs(",", Kind.PUNCTUATION), args.items[1])
    }

    @Test
    fun fencesGrowAroundTheirBody() {
        val fenced = TexParser.parse("\\left(\\frac{1}{2}\\right)")
        assertIs<MathNode.Fenced>(fenced)
        assertEquals("(", fenced.open)
        assertEquals(")", fenced.close)
        assertIs<MathNode.Fraction>(fenced.body)
        assertFailsWith<IllegalArgumentException> { TexParser.parse("\\left( x") }
    }

    @Test
    fun everyFormulaTheAppWritesCanBeRead() {
        for (formula in listOf("x^3", "exp(2*x - 3*t)", "kappa*(theta - x)", "sigma*sqrt(x)", "-x/(1 + t)^2", "step(x - 100)", "x^-2")) {
            TexParser.parse(tex(formula))
        }
    }

    @Test
    fun unknownCommandsStayVisible() {
        assertEquals(glyphs("\\foo", Kind.UPRIGHT), TexParser.parse("\\foo"))
    }

    @Test
    fun differentialsLeaveOutZerosAndOnes() {
        assertEquals("dX_t = -x\\,dB_t", differentialTex("dX_t", Parser.parse("0"), Parser.parse("-x")))
        assertEquals("dX_t = -x\\,dt + dB_t", differentialTex("dX_t", Parser.parse("-x"), Parser.parse("1")))
        assertEquals("dX_t = \\left(1 + x\\right)\\,dt + \\left(-x\\right)\\,dB_t", differentialTex("dX_t", Parser.parse("1 + x"), Parser.parse("-x")))
        assertEquals("dM_t = 0", differentialTex("dM_t", Parser.parse("0"), Parser.parse("0")))
    }

    @Test
    fun solverAnswersAreTypeset() {
        val cube = StochasticIntegral("x^3")
        assertEquals("\\int_0^t X_{s}^{3}\\,dX_s", cube.questionTex())
        assertEquals("\\frac{X_{t}^{4}}{4} - \\int_0^t 1.5 X_{s}^{2}\\,ds", cube.answerTex())
        assertEquals("\\frac{X_{t}^{2}}{2} - 0.5 t", StochasticIntegral("x").answerTex())
        for (point in EvaluationPoint.entries) {
            val s = StochasticIntegral("x^2", point = point)
            TexParser.parse(s.questionTex())
            s.answerTex()?.let { TexParser.parse(it) }
        }
        val linear = LinearSde.of("kappa*(m - x)", "sigma")!!
        for (line in linear.stepsTex()) TexParser.parse(line)
        for (line in LinearSde.of("x", "x")!!.stepsTex()) TexParser.parse(line)
    }

    @Test
    fun everyModelEquationCanBeRead() {
        for (model in ModelCatalog.all) TexParser.parse(model.equation)
    }
}
