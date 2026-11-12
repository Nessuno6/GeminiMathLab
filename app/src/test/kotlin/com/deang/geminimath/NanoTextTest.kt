package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NanoTextTest {
  private fun clean(s: String) = NanoText.clean(s)

  @Test fun inlineMath() {
    // The reply seen on the tablet
    assertEquals(
      "The solution uses integration by parts with u=x and dv=eˣ dx. This yields eˣ(x-1) + C.",
      clean("The solution uses integration by parts with \$u=x\$ and \$dv=e^x dx\$. This yields \$e^x(x-1) + C\$.")
    )
  }

  @Test fun fractionsRootsAndSymbols() {
    assertEquals("x²/2 + C", clean("\$\\frac{x^{2}}{2} + C\$"))
    assertEquals("∫ √x dx = 2/3 x^(3/2)", clean("\$\\int \\sqrt{x}\\,dx = \\frac{2}{3} x^{3/2}\$"))
    assertEquals("(x + 1)/(x - 1)", clean("\$\\frac{x + 1}{x - 1}\$"))
    assertEquals("x ≤ 2 and x ≠ 0, θ → π", clean("\$x \\leq 2\$ and \$x \\neq 0\$, \$\\theta \\to \\pi\$"))
    assertEquals("x₁ = 2", clean("\$x_1 = 2\$"))
    assertEquals("sin(x) · cos(x)", clean("\$\\sin(x) \\cdot \\cos(x)\$"))
    assertEquals("the integral ∫ x dx", clean("the integral \\int x dx"))
  }

  @Test fun displayMathDelimiters() {
    assertEquals("Result: eˣ", clean("Result: \$\$e^{x}\$\$"))
    assertEquals("Result: eˣ", clean("Result: \\[e^{x}\\]"))
    assertEquals("Result: eˣ", clean("Result: \\(e^{x}\\)"))
  }

  @Test fun markdown() {
    assertEquals("The answer is correct.", clean("The answer is **correct**."))
    assertEquals("Note: check signs", clean("*Note*: check `signs`"))
    assertEquals("Steps\n• first\n• second", clean("## Steps\n- first\n* second"))
  }

  @Test fun partialStreamHasNoStraySymbols() {
    val partial = clean("so we get \$e^{x}(x - 1")
    assertFalse(partial.contains('$'))
    assertFalse(partial.contains('\\'))
    assertEquals("so we get eˣ(x - 1", partial)
    assertFalse(clean("this is **impor").contains("*"))
  }

  @Test fun asteriskMultiplication() {
    // Seen on the tablet: "the formula u*v minus the integral of v du"
    assertEquals("the formula u·v minus", clean("the formula u*v minus"))
    assertEquals("2·(x + 1)", clean("2 * (x + 1)"))
  }

  @Test fun plainTextUnchanged() {
    assertEquals("Integrate by parts, then simplify.", clean("Integrate by parts, then simplify."))
    assertEquals("It costs 5 dollars", clean("It costs 5 dollars"))
  }
}
