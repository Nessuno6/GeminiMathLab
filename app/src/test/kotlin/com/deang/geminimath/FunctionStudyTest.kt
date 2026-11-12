package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases from the on-device solver self-test, so they're checked on every build. */
class FunctionStudyTest {
  private fun answer(l: String) = SymbolicStepEngine.solveFromLatex(l).finalAnswer
  private fun has(l: String, vararg parts: String) {
    val a = answer(l)
    parts.forEach { assertTrue("'$l' → '$a' should contain '$it'", a.contains(it)) }
  }

  @Test fun parabola() {
    has("f(x)=x^{2}-4x+3", "x = 1", "x = 3", "Minimum (2, -1)")
    has("f(x)=5x^{2}-3x+2", "No real zeros", "Minimum (3/10, 31/20)")
  }

  @Test fun lineAndCubic() {
    has("y=3x-2", "x = 2/3")
    has("f(x)=x^{3}-3x", "x = 0", "√3", "Maximum (-1, 2)", "Minimum (1, -2)")
  }

  @Test fun rationalHasAsymptotes() {
    has("f(x)=\\frac{1}{x-2}", "x ≠ 2", "Asymptote x = 2", "Asymptote y = 0")
    has("f(x)=\\frac{x^{2}-1}{x+3}", "x = -1", "x = 1", "Asymptote x = -3")
  }

  @Test fun restrictedDomains() {
    has("f(x)=\\sqrt{4-x^{2}}", "Domain: -2 ≤ x ≤ 2", "x = -2", "x = 2", "Maximum (0, 2)")
    has("f(x)=\\ln(x)", "Domain: x > 0", "x = 1", "Asymptote x = 0")
  }

  @Test fun touchingZeroAndSymmetry() {
    has("f(x)=\\ln(x^{2}+1)", "x = 0", "Minimum (0, 0)")
    val s = SymbolicStepEngine.solveFromLatex("f(x)=x^{3}-3x")
    assertTrue(s.steps.any { it.contains("odd") })
  }

  @Test fun exactExpAndLog() {
    assertEquals("x = e²", answer("\\ln(x)=2"))
    assertEquals("x = ln 5", answer("e^{x}=5"))
  }

  @Test fun recogniserNotation() {
    // Seen on the tablet: old-style bold, the root sign as \surd or misread as ∀, dotted x
    assertFalse(SymbolicStepEngine.solveFromLatex("( 2 { \\bf x } + 3 ) / ( { \\bf x } - 1 ) = 5").isUnreadable)
    assertEquals("x = 8/3", answer("( 2 { \\bf x } + 3 ) / ( { \\bf x } - 1 ) = 5"))
    assertEquals("x = 6", answer("\\forall ( \\mathbf { x } + \\mathbf { 3 } ) = \\mathbf { x } - \\mathbf { 3 }"))
    has("\\mathbf { f } ( \\mathbf { x } ) = \\surd ( 4 - \\mathbf { x } ^ { 2 } )", "Domain: -2 ≤ x ≤ 2")
    has("\\dot { x } ^ { 4 } - 5 \\dot { x } ^ { 2 } + 4 = 0", "x = -2", "x = 2")
  }
}
