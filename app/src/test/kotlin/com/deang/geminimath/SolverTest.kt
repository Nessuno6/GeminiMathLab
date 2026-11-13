package com.deang.geminimath

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SolverTest {
  @After
  fun resetSettings() {
    MathSettings.angleDegrees = false
    MathSettings.exactFractions = true
    MathSettings.decimals = 4
  }

  private fun solve(latex: String) = SymbolicStepEngine.solveFromLatex(latex)
  private fun answer(latex: String) = solve(latex).finalAnswer

  /** Checks d/dx of the antiderivative equals the integrand at a few points. */
  private fun assertAntiderivative(latex: String, integrand: String) {
    val s = solve(latex)
    val prim = s.antiderivativeExpr
    assertNotNull("no antiderivative for $latex (answer: ${s.finalAnswer})", prim)
    var checked = 0
    for (x in doubleArrayOf(0.4, 1.1, 1.9, 2.6)) {
      val f = LocalMathEngine.evalAt(integrand, x)
      val h = 1e-5
      val d = (LocalMathEngine.evalAt(prim!!, x + h) - LocalMathEngine.evalAt(prim, x - h)) / (2 * h)
      if (!f.isFinite()) continue
      assertTrue("d/dx F != f at x=$x for $latex: $d vs $f", abs(d - f) < 1e-4 * (1 + abs(f)))
      checked++
    }
    assertTrue(checked >= 2)
  }

  // ---------- Parsing ----------

  @Test fun implicitMultiplication() {
    assertEquals(6.0, LocalMathEngine.evalAt("2x", 3.0), 1e-12)
    assertEquals(3.0 * Math.exp(3.0), LocalMathEngine.evalAt("xe^x", 3.0), 1e-9)
    assertEquals(Math.sin(2.0), LocalMathEngine.evalAt("sinx", 2.0), 1e-12)
    assertEquals(Math.sin(4.0), LocalMathEngine.evalAt("sin2x", 2.0), 1e-12)
    assertEquals(Math.sin(2.0) * Math.cos(2.0), LocalMathEngine.evalAt("sinxcosx", 2.0), 1e-12)
    assertEquals(8.0, LocalMathEngine.evalAt("(x+1)(x-1)", 3.0), 1e-12)
    assertEquals(-9.0, LocalMathEngine.evalAt("-x^2", 3.0), 1e-12)
    assertEquals(Math.pow(Math.sin(2.0), 2.0), LocalMathEngine.evalAt("sin^2x", 2.0), 1e-12)
    assertEquals(2.0, LocalMathEngine.evalAt("log100", 0.0), 1e-12)
    assertEquals(120.0, LocalMathEngine.evalAt("5!", 0.0), 1e-12)
    assertEquals(-2.0, LocalMathEngine.evalAt("(-8)^(1/3)", 0.0), 1e-12)
  }

  @Test fun latexConversion() {
    assertEquals(0.5, LocalMathEngine.evalAt(LocalMathEngine.latexToMathExpr("\\frac{1}{2}"), 0.0), 1e-12)
    assertEquals(3.0, LocalMathEngine.evalAt(LocalMathEngine.latexToMathExpr("\\sqrt[3]{27}"), 0.0), 1e-12)
    assertEquals(4.0, LocalMathEngine.evalAt(LocalMathEngine.latexToMathExpr("\\left|x-5\\right|"), 1.0), 1e-12)
    assertEquals(4.0, LocalMathEngine.evalAt(LocalMathEngine.latexToMathExpr("|x-5|"), 1.0), 1e-12)
    assertEquals(Math.sin(1.0), LocalMathEngine.evalAt(LocalMathEngine.latexToMathExpr("\\sin x"), 1.0), 1e-12)
    assertEquals(2.0, LocalMathEngine.evalAt(LocalMathEngine.latexToMathExpr("x \\! \\cdot 2"), 1.0), 1e-12)
  }

  // ---------- Arithmetic ----------

  @Test fun arithmetic() {
    assertEquals("14", answer("2+3\\times4"))
    assertEquals("5/6", answer("\\frac{1}{2}+\\frac{1}{3}"))
    assertEquals("1024", answer("2^{10}"))
    assertEquals("4", answer("\\sqrt{16}"))
    assertEquals("1", answer("\\sin\\left(\\frac{\\pi}{2}\\right)"))
    assertEquals("√2", answer("\\sqrt{2}"))
  }

  @Test fun trailingEqualsEvaluates() {
    assertEquals("5", answer("2+3="))
    assertEquals("5", answer("2+3=?"))
  }

  @Test fun degreesSetting() {
    MathSettings.angleDegrees = true
    assertEquals("1/2", answer("\\sin 30"))
  }

  @Test fun decimalSetting() {
    MathSettings.exactFractions = false
    MathSettings.decimals = 3
    assertEquals("0.333", answer("\\frac{1}{3}"))
  }

  @Test fun unreadableInputIsReported() {
    val s = solve("\\alpha \\beta +")
    assertTrue(s.isUnreadable)
    assertFalse(s.finalAnswer.contains("undefined"))
  }

  // ---------- Equations ----------

  @Test fun linearEquations() {
    assertEquals("x = 2", answer("2x+3=7"))
    assertEquals("x = 4", answer("3(x-1)=x+5"))
    assertEquals("x = 2", answer("\\frac{x+1}{x-1}=3"))
  }

  @Test fun quadraticEquations() {
    assertEquals("x₁ = 2, x₂ = 3", answer("x^{2}-5x+6=0"))
    assertEquals("x₁ = 0, x₂ = 2", answer("x(x-2)=0"))
    assertEquals("x = ±1i", answer("x^{2}=-1"))
    assertEquals("x₁ = -√2, x₂ = √2", answer("x^2=2"))
  }

  @Test fun cubicEquation() {
    assertEquals("x = 1, x = 2, x = 3", answer("x^3-6x^2+11x-6=0"))
  }

  @Test fun equalityCheck() {
    assertEquals("True", answer("2+2=4"))
    assertEquals("False", answer("2+2=5"))
  }

  // ---------- Derivatives ----------

  @Test fun derivatives() {
    assertEquals("f'(x) = 2x", answer("\\frac{d}{dx} x^2"))
    assertEquals("f'(x) = cos(x)", answer("\\frac{d}{dx}\\sin x"))
  }

  // ---------- Integrals ----------

  @Test fun integralFromTabletOcr() {
    // Exactly what the handwriting model produced for ∫x·eˣ dx on the device
    val s = solve("\\int\\!x\\cdot e^{x}\\alpha x")
    assertEquals("eˣ(x - 1) + C", s.finalAnswer)
    assertAntiderivative("\\int\\!x\\cdot e^{x}\\alpha x", "x*e^x")
  }

  @Test fun integralsByParts() {
    assertEquals("eˣ(x - 1) + C", answer("\\int x \\cdot e^{x} d x"))
    assertEquals("eˣ(x² - 2x + 2) + C", answer("\\int x^2 e^x dx"))
    assertAntiderivative("\\int x \\sin x dx", "x*sin(x)")
    assertAntiderivative("\\int x^2 \\cos(3x) dx", "x^2*cos(3x)")
    assertAntiderivative("\\int x \\ln x dx", "x*ln(x)")
    assertAntiderivative("\\int \\ln x dx", "ln(x)")
  }

  @Test fun basicIntegrals() {
    // Used to be answered with eˣ(x - 1) because of a substring match
    assertEquals("eˣ + C", answer("\\int e^{x} dx"))
    assertEquals("x⁴/4 + C", answer("\\int x^{3} dx"))
    assertEquals("-cos(x) + C", answer("\\int \\sin x dx"))
    assertEquals("sin(2x)/2 + C", answer("\\int \\cos(2x) dx"))
    assertEquals("ln|x| + C", answer("\\int \\frac{1}{x} dx"))
    assertEquals("arctan(x) + C", answer("\\int \\frac{1}{1+x^2} dx"))
    assertAntiderivative("\\int \\sqrt{x} dx", "sqrt(x)")
    assertAntiderivative("\\int e^{2x+1} dx", "e^(2x+1)")
    assertAntiderivative("\\int (x+1)^2 dx", "(x+1)^2")
    assertAntiderivative("\\int x + e^x + \\sin x dx", "x+e^x+sin(x)")
    assertAntiderivative("\\int \\frac{x^2+1}{x-1} dx", "(x^2+1)/(x-1)")
    assertAntiderivative("\\int \\frac{1}{x^2-1} dx", "1/(x^2-1)")
    assertAntiderivative("\\int \\tan x dx", "tan(x)")
    assertAntiderivative("\\int 2^x dx", "2^x")
  }

  @Test fun integralWithoutDx() {
    assertEquals("x³/3 + C", answer("\\int x^2"))
  }

  @Test fun noClosedForm() {
    assertEquals("No closed form found", answer("\\int e^{x^2} dx"))
  }

  @Test fun divergentIntegrals() {
    assertEquals("The integral diverges", answer("\\int_{0}^{1} \\frac{1}{x} dx"))
    assertEquals("The integral diverges", answer("\\int_{-1}^{1} \\frac{1}{x^2} dx"))
  }

  // ---------- Second round: bugs found by probing ----------

  @Test fun logWithBase() {
    assertEquals("3", answer("\\log_{2} 8")) // used to drop the base and give 0.9031
    assertEquals("2", answer("\\log_3 9"))
  }

  @Test fun percentAndDegreeSign() {
    assertEquals("3/25", answer("12\\%"))
    assertEquals("1/2", answer("\\sin 30^{\\circ}"))
  }

  @Test fun piResults() {
    assertEquals("2π ≈ 6.2832", answer("2\\pi"))
    assertEquals("π/3 ≈ 1.0472", answer("\\frac{\\pi}{3}"))
  }

  @Test fun undefinedArithmeticIsExplained() {
    assertEquals("Undefined (division by zero)", answer("3/0"))
    assertEquals("Not a real number", answer("\\sqrt{-4}"))
  }

  @Test fun functionDefinitions() {
    // The function is shown as recognised; the answer is its study
    assertEquals("f(x) = 2x + 1", solve("y=2x+1").normalizedMath)
    assertEquals("Zeros: x = -1/2", answer("y=2x+1"))
    assertEquals("f(x) = x² - 4", solve("f(x)=x^2-4").normalizedMath)
    assertEquals("Zeros: x = -2, x = 2 · Minimum (0, -4)", answer("f(x)=x^2-4"))
  }

  @Test fun derivativesAreSimplified() {
    assertEquals("f'(x) = 2e^(2x)", answer("\\frac{d}{dx} e^{2x}")) // used to show ·ln(e)
    assertEquals("f'(x) = 2x/(x² + 1)", answer("\\frac{d}{dx}\\ln(x^2+1)"))
    assertEquals("f'(x) = -1/x²", answer("\\frac{d}{dx}\\frac{1}{x}"))
  }

  @Test fun trigEquationsGiveGeneralSolution() {
    assertEquals("x = π/6 + 2kπ, x = 5π/6 + 2kπ  (k ∈ ℤ)", answer("\\sin x = 0.5"))
    assertEquals("x = π/4 + kπ  (k ∈ ℤ)", answer("\\tan x = 1"))
  }

  @Test fun moreIntegrals() {
    assertEquals("2x^(5/2)/5 + C", answer("\\int x\\sqrt{x}dx"))
    assertEquals("2√x + C", answer("\\int \\frac{1}{\\sqrt{x}} dx"))
    assertEquals("C", answer("\\int 0 dx"))
    assertAntiderivative("\\int \\sin^2 x dx", "sin(x)^2")
    assertAntiderivative("\\int \\cos^2(3x) dx", "cos(3x)^2")
    assertAntiderivative("\\int \\sin x \\cos x dx", "sin(x)cos(x)")
    assertAntiderivative("\\int e^x \\sin x dx", "e^x*sin(x)")
    assertAntiderivative("\\int e^{2x} \\cos(3x) dx", "e^(2x)*cos(3x)")
  }

  @Test fun improperIntegrals() {
    assertEquals("I = 1", answer("\\int_{0}^{\\infty} e^{-x} dx"))
    assertEquals("I = 1", answer("\\int_{1}^{\\infty} \\frac{1}{x^2} dx"))
    assertEquals("The integral diverges", answer("\\int_{1}^{\\infty} \\frac{1}{x} dx"))
  }

  @Test fun limits() {
    assertEquals("1", answer("\\lim_{x\\to0}\\frac{\\sin x}{x}"))
    assertEquals("4", answer("\\lim_{x \\to 2}\\frac{x^2-4}{x-2}"))
    assertEquals("0", answer("\\lim_{x\\to\\infty}\\frac{1}{x}"))
    assertEquals("The limit does not exist (left -1, right 1)", answer("\\lim_{x\\to0}\\frac{|x|}{x}"))
    assertEquals("3", answer("\\lim_{t\\to1} (t+2)"))
  }

  @Test fun sums() {
    assertEquals("55", answer("\\sum_{n=1}^{10} n"))
    assertEquals("385", answer("\\sum_{k=1}^{10} k^2"))
    assertEquals("The series diverges", answer("\\sum_{n=1}^{\\infty} \\frac{1}{n}"))
    assertTrue(answer("\\sum_{n=1}^{\\infty} \\frac{1}{n^2}").startsWith("≈ 1.644"))
  }

  @Test fun inequalities() {
    assertEquals("x < -2 or x > 2", answer("x^2 > 4"))
    assertEquals("-2 ≤ x ≤ 2", answer("x^2 \\leq 4"))
    assertEquals("x > 3/2", answer("2x - 3 > 0"))
    assertEquals("x > 0", answer("\\frac{1}{x} > 0"))
    assertEquals("All real numbers", answer("x^2 \\geq 0"))
    assertEquals("No solution", answer("x^2 < 0"))
    assertEquals("x = 1", answer("(x-1)^2 \\leq 0"))
  }

  // ---------- Lasso geometry ----------

  private fun bar(x0: Float, x1: Float, y: Float) = InkStroke((0..10).map { TimedPoint(x0 + (x1 - x0) * it / 10f, y, 0L) })

  @Test fun lassoSelectsStrokesInsideLoop() {
    val inside = bar(10f, 50f, 20f)
    val outside = bar(200f, 260f, 20f)
    val loop = listOf(TimedPoint(0f, 0f, 0), TimedPoint(100f, 0f, 0), TimedPoint(100f, 100f, 0), TimedPoint(0f, 100f, 0))
    assertEquals(listOf(inside), LassoTools.strokesInside(listOf(inside, outside), loop))
  }

  @Test fun equalsSignDetection() {
    assertTrue(LassoTools.endsWithEquals(listOf(bar(0f, 60f, 100f), bar(2f, 58f, 125f))))
    assertFalse(LassoTools.endsWithEquals(listOf(bar(0f, 60f, 100f), bar(200f, 260f, 125f)))) // not stacked
    assertFalse(LassoTools.endsWithEquals(listOf(bar(0f, 60f, 100f))))
  }

  @Test fun definiteIntegrals() {
    assertEquals("I = 1/3", answer("\\int_{0}^{1} x^{2} dx"))
    assertEquals("I = 2", answer("\\int_{0}^{\\pi} \\sin x dx"))
    assertEquals("I = 1", answer("\\int_{0}^{1} x e^x dx"))
    assertEquals("I = -4", answer("\\int_{0}^{1} \\frac{\\ln(x)}{\\sqrt{x}} dx"))
    assertEquals("I = 1/3", answer("\\int^{1}_{0} x^{2} dx"))
  }

  @Test fun productSignBeforeDxIsIgnored() {
    // Exactly what Pix2Text produced for a handwritten ∫ eˣ·x²·√x dx
    val s = solve("\\int e ^ { x } \\cdot x ^ { 2 } \\cdot \\sqrt { x } \\cdot dx")
    assertFalse("should parse: ${s.steps}", s.isUnreadable)
    assertTrue(s.finalAnswer, s.finalAnswer.contains("erfi(√x)"))
    assertEquals("eˣ(x^(5/2) − (5/2)·x^(3/2) + (15/4)·√x) − (15√π/8)·erfi(√x) + C", s.finalAnswer)
    // And a normal one with a stray \cdot still works
    val simple = solve("\\int x^{2} \\cdot dx")
    assertFalse(simple.isUnreadable)
    assertEquals(answer("\\int x^{2} dx"), simple.finalAnswer)
  }

  @Test fun halfPowerExpVariants() {
    assertEquals("√π·erfi(√x) + C", answer("\\int \\frac{e^{x}}{\\sqrt{x}} dx"))
    val neg = answer("\\int \\sqrt{x} e^{-x} dx")
    assertTrue(neg, neg.contains("erf(√x)") && neg.startsWith("−e⁻ˣ(√x)"))
  }

  /** Real Pix2Text outputs captured on the tablet: each must be understood, not "Couldn't read". */
  @Test fun realOcrOutputsAreUnderstood() {
    val cases = listOf(
      "x ^ { 2 } - 5 x + 6 = 0",
      "\\int _ { 0 } ^ { 1 } \\frac { \\operatorname { l n } \\left( x \\right) } { \\sqrt { x } } \\ d x",
      "\\frac { 2 x + 3 } { x - 1 } = 5",
      "\\int _ { 0 } ^ { \\pi } \\, \\operatorname { s i n } \\left( x \\right) \\, \\mathrm { d } x",
      "\\int \\mathbf { e ^ { x } \\cdot x \\, d x }",
      "\\int \\! \\mathbf { e ^ { x } \\cdot x } \\, \\mathbf { d x }",
      "\\int e ^ { x } \\cdot x ^ { 2 } \\cdot \\sqrt { x } \\cdot dx",
      "\\begin{matrix} y ( x ) = x ^ { 2 } - s x + 7 0 } \\\\ \\end{matrix}",
      "f ( x ) = x ^ { 2 } - 5 x + 1 0"
    )
    val failed = cases.filter { solve(it).isUnreadable }.map { it + " → " + solve(it).steps }
    assertTrue("not understood:\n" + failed.joinToString("\n"), failed.isEmpty())
    assertEquals("I = -4", answer("\\int _ { 0 } ^ { 1 } \\frac { \\operatorname { l n } \\left( x \\right) } { \\sqrt { x } } \\ d x"))
    assertEquals("I = 2", answer("\\int _ { 0 } ^ { \\pi } \\, \\operatorname { s i n } \\left( x \\right) \\, \\mathrm { d } x"))
    assertEquals(answer("\\int e^{x} \\cdot x dx"), answer("\\int \\mathbf { e ^ { x } \\cdot x \\, d x }"))
    assertEquals(answer("x^{2}-5x+6=0"), answer("\\begin{matrix} x ^ { 2 } - 5 x + 6 = 0 } \\\\ \\end{matrix}"))
  }

  // ---------- Systems of Equations, Matrices, ODEs & Partial Fractions ----------

  @Test fun linearSystems2x2And3x3() {
    assertEquals("x = 2, y = 1", answer("\\begin{cases} 2x + y = 5 \\\\ x - y = 1 \\end{cases}"))
    assertEquals("x = 3, y = -1", answer("x + y = 2 ; 2x - y = 7"))
    assertEquals("No solution", answer("\\begin{cases} x + y = 1 \\\\ x + y = 3 \\end{cases}"))
    assertEquals("x = 1, y = 2, z = 3", answer("\\begin{cases} x + y + z = 6 \\\\ 2x - y + z = 3 \\\\ x + 2y - z = 2 \\end{cases}"))
  }

  @Test fun nonlinearSystem2x2() {
    assertEquals("(-1, 1), (2, 4)", answer("\\begin{cases} y = x^2 \\\\ y = x + 2 \\end{cases}"))
  }

  @Test fun matricesAndDeterminants() {
    assertEquals("-2", answer("\\begin{vmatrix} 1 & 2 \\\\ 3 & 4 \\end{vmatrix}"))
    assertEquals("-2", answer("\\det \\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}"))
    assertEquals("[-2, 1; 3/2, -1/2]", answer("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}^{-1}"))
    assertEquals("[1, 3; 2, 4]", answer("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}^{T}"))
    assertEquals("[19, 22; 43, 50]", answer("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix} \\cdot \\begin{pmatrix} 5 & 6 \\\\ 7 & 8 \\end{pmatrix}"))
    assertEquals("[6, 8; 10, 12]", answer("\\begin{bmatrix} 1 & 2 \\\\ 3 & 4 \\end{bmatrix} + \\begin{bmatrix} 5 & 6 \\\\ 7 & 8 \\end{bmatrix}"))
    val study = solve("\\begin{pmatrix} 2 & 0 \\\\ 0 & 3 \\end{pmatrix}")
    assertEquals("det = 6 · λ₁ = 2, λ₂ = 3", study.finalAnswer)
  }

  @Test fun firstOrderDifferentialEquations() {
    assertEquals("y(x) = x² + C", answer("y' = 2x"))
    assertEquals("y(x) = C·e^(2x)", answer("\\frac{dy}{dx} = 2y"))
    assertEquals("y(x) = 3 + C·e⁻ˣ", answer("y' + y = 3"))
  }

  @Test fun explicitPartialFractionsIntegration() {
    val s = solve("\\int \\frac{3x+5}{x^2-x-2} dx")
    assertFalse(s.isUnreadable)
    assertTrue(s.steps.any { it.contains("partial fractions", ignoreCase = true) })
    assertAntiderivative("\\int \\frac{3x+5}{x^2-x-2} dx", "(3x+5)/(x^2-x-2)")
  }
}
