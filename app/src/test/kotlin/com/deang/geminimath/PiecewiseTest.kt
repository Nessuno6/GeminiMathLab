package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiecewiseTest {
  private fun solve(l: String) = SymbolicStepEngine.solveFromLatex(l)

  @Test fun realOcrOutputFromTheTablet() {
    // Handwritten: f(x) = { x if x > 1 ; 2 if x ≤ 1 }. The x's came out as "×", the ">" as a 2.
    val s = solve("f ( x ) = \\left\\{ \\begin{matrix} x \\times 2 1 \\\\ 2 \\times \\leq 1 \\end{matrix} \\right.")
    assertFalse("not read: ${s.steps}", s.isUnreadable)
    assertEquals("Piecewise function", s.problemType)
    val spec = s.plottableExpr!!
    assertEquals(5.0, LocalMathEngine.evalAt(spec, 5.0), 1e-9)   // x for x > 1
    assertEquals(2.0, LocalMathEngine.evalAt(spec, 1.0), 1e-9)   // 2 for x ≤ 1
    assertEquals(2.0, LocalMathEngine.evalAt(spec, -3.0), 1e-9)
    assertEquals("Jump at x = 1", s.finalAnswer)
  }

  @Test fun casesWithAmpersands() {
    val s = solve("f(x)=\\begin{cases} x^{2} & x < 0 \\\\ 2x & x \\geq 0 \\end{cases}")
    assertEquals("Piecewise function", s.problemType)
    assertEquals(4.0, LocalMathEngine.evalAt(s.plottableExpr!!, -2.0), 1e-9)
    assertEquals(6.0, LocalMathEngine.evalAt(s.plottableExpr!!, 3.0), 1e-9)
    assertTrue(s.finalAnswer, s.finalAnswer.startsWith("Continuous"))
  }

  @Test fun arrayFormWithOtherwise() {
    val s = solve("f ( x ) = \\left\\{ \\begin{array} { l l } { x + 1 } & { x > 2 } \\\\ { 0 } & { \\text { otherwise } } \\end{array} \\right.")
    assertNotNull(s.plottableExpr)
    assertEquals(4.0, LocalMathEngine.evalAt(s.plottableExpr!!, 3.0), 1e-9)
    assertEquals(0.0, LocalMathEngine.evalAt(s.plottableExpr!!, 0.0), 1e-9)
  }

  @Test fun calculatorTypedForm() {
    val spec = GraphRender.normalise("y={x<0: −x, x≥0: x^2}")!!
    assertEquals(3.0, LocalMathEngine.evalAt(spec, -3.0), 1e-9)
    assertEquals(4.0, LocalMathEngine.evalAt(spec, 2.0), 1e-9)
    // Round trip from a solved note
    val solved = solve("f(x)=\\begin{cases} x & x > 1 \\\\ 2 & x \\leq 1 \\end{cases}").plottableExpr!!
    val typed = Piecewise.toTyped(solved)
    assertEquals("{x>1: x, x≤1: 2}", typed)
    assertEquals(LocalMathEngine.evalAt(solved, 0.0), LocalMathEngine.evalAt(GraphRender.normalise(typed)!!, 0.0), 1e-9)
  }

  @Test fun graphDoesNotJoinPieces() {
    val spec = solve("f(x)=\\begin{cases} x & x > 1 \\\\ 2 & x \\leq 1 \\end{cases}").plottableExpr!!
    assertEquals(0, Piecewise.index(spec, 2.0))
    assertEquals(1, Piecewise.index(spec, 0.0))
  }
}
