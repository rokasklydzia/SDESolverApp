package sde.ito

import sde.expr.CompiledExpr
import sde.expr.Derivative
import sde.expr.Expr
import sde.expr.Exprs.add
import sde.expr.Exprs.mul
import sde.expr.Exprs.num
import sde.expr.Exprs.pow
import sde.montecarlo.TimeGrid

/** dY = [dt]·dt + [dB]·dB, the differential of a process driven by one Brownian motion. */
data class ItoDifferential(val dt: Expr, val dB: Expr)

object ItoFormula {
    /**
     * Itô's formula for Y = f(t, X) with dX = μ dt + σ dB:
     * dY = (f_t + μ f_x + ½σ² f_xx) dt + σ f_x dB, derived symbolically.
     */
    fun apply(f: Expr, drift: Expr, diffusion: Expr): ItoDifferential {
        val fx = Derivative.dx(f)
        val fxx = Derivative.dx(fx)
        val ft = Derivative.dt(f)
        val dt = add(add(ft, mul(drift, fx)), mul(mul(num(0.5), pow(diffusion, num(2.0))), fxx))
        return ItoDifferential(dt = dt, dB = mul(diffusion, fx))
    }
}

/** The formula evaluated at every point of a path x on [grid]. */
fun CompiledExpr.alongPath(grid: TimeGrid, x: DoubleArray): DoubleArray {
    require(x.size == grid.steps + 1) { "the path needs ${grid.steps + 1} points, not ${x.size}" }
    return DoubleArray(x.size) { eval(grid.time(it), x[it]) }
}
