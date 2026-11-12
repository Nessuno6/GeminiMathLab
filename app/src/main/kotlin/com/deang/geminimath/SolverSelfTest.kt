package com.deang.geminimath

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log

/**
 * Debug builds only (adb: am start … --ez solver_selftest true): runs equations and functions
 * through the on-device solver, and through the handwriting model + solver, and logs each result
 * under the "SolverSelfTest" tag with PASS/FAIL against the expected answer.
 */
object SolverSelfTest {
  @Volatile var requested = false
  private const val TAG = "SolverSelfTest"

  /** [shown]: as written (rendered for the recogniser); [expect]: all must appear in the answer. */
  data class Case(val shown: String, val latex: String, val expect: List<String>)

  val cases = listOf(
    // Equations
    Case("2x + 3 = 11", "2x+3=11", listOf("4")),
    Case("x² − 5x + 6 = 0", "x^{2}-5x+6=0", listOf("2", "3")),
    Case("x² + 1 = 0", "x^{2}+1=0", listOf("i")),
    Case("3x² − 12 = 0", "3x^{2}-12=0", listOf("2", "-2")),
    Case("x³ − 6x² + 11x − 6 = 0", "x^{3}-6x^{2}+11x-6=0", listOf("1", "2", "3")),
    Case("(2x + 3)/(x − 1) = 5", "\\frac{2x+3}{x-1}=5", listOf("8/3")),
    Case("√(x + 3) = x − 3", "\\sqrt{x+3}=x-3", listOf("6")),
    Case("2ˣ = 8", "2^{x}=8", listOf("3")),
    Case("ln(x) = 2", "\\ln(x)=2", listOf("e")),
    Case("|x − 2| = 3", "|x-2|=3", listOf("5", "-1")),
    Case("sin(x) = 1/2", "\\sin(x)=\\frac{1}{2}", listOf("π")),
    Case("eˣ = 5", "e^{x}=5", listOf("ln")),
    Case("x⁴ − 5x² + 4 = 0", "x^{4}-5x^{2}+4=0", listOf("1", "2")),
    // Inequalities
    Case("x² − 4 < 0", "x^{2}-4<0", listOf("2")),
    Case("2x − 1 ≥ 5", "2x-1\\geq5", listOf("3")),
    // Functions
    Case("f(x) = x² − 4x + 3", "f(x)=x^{2}-4x+3", listOf("1", "3")),
    Case("f(x) = 5x² − 3x + 2", "f(x)=5x^{2}-3x+2", listOf("Minimum (3/10")),
    Case("y = 3x − 2", "y=3x-2", listOf("2/3")),
    Case("f(x) = x³ − 3x", "f(x)=x^{3}-3x", listOf("0")),
    Case("f(x) = 1/(x − 2)", "f(x)=\\frac{1}{x-2}", listOf("2")),
    Case("f(x) = √(4 − x²)", "f(x)=\\sqrt{4-x^{2}}", listOf("2")),
    Case("f(x) = ln(x² + 1)", "f(x)=\\ln(x^{2}+1)", listOf("0")),
    Case("f(x) = eˣ·sin(x)", "f(x)=e^{x}\\sin(x)", emptyList()),
    Case("f(x) = (x² − 1)/(x + 3)", "f(x)=\\frac{x^{2}-1}{x+3}", listOf("-3")),
    Case("piecewise", "f(x)=\\begin{cases} x & x>1 \\\\ 2 & x\\leq1 \\end{cases}", listOf("x = 1"))
  )

  private fun normalise(s: String) = s.replace("−", "-").replace(" ", "").replace("·", "")

  private fun verdict(answer: String, c: Case): String {
    val a = normalise(answer)
    val missing = c.expect.filter { !a.contains(normalise(it)) }
    return when {
      answer.isBlank() || answer.contains("Couldn't read") -> "FAIL (not read)"
      missing.isEmpty() -> "PASS"
      else -> "FAIL (missing ${missing.joinToString()})"
    }
  }

  /** The text drawn as a printed formula, for the recogniser. */
  private fun render(text: String): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 72f; color = Color.BLACK; typeface = Typeface.SERIF }
    val w = (p.measureText(text) + 80).toInt(); val h = 160
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
      Canvas(b).apply { drawColor(Color.WHITE); drawText(text, 40f, 105f, p) }
    }
  }

  /**
   * Runs everything. [ocr] reads a bitmap with the handwriting model; [notes] is the user's ink
   * (title → strokes) to try on real handwriting.
   */
  fun run(ocr: (Bitmap) -> String?, notes: List<Pair<String, List<InkStroke>>>) {
    var pass = 0; var fail = 0
    Log.i(TAG, "=== 1. Solver only (${cases.size} cases)")
    for (c in cases) {
      val s = runCatching { SymbolicStepEngine.solveFromLatex(c.latex) }.getOrNull()
      val v = s?.let { verdict(it.finalAnswer, c) } ?: "FAIL (crashed)"
      if (v == "PASS") pass++ else fail++
      Log.i(TAG, "$v | ${c.shown} | ${s?.problemType} | ${s?.finalAnswer} | ${s?.steps?.size} steps | ${s?.elapsedMs} ms")
    }
    Log.i(TAG, "solver: $pass passed, $fail failed")

    var opass = 0; var ofail = 0
    Log.i(TAG, "=== 2. Recogniser + solver on printed formulas")
    for (c in cases.filter { it.shown != "piecewise" }) {
      val t0 = System.nanoTime()
      val latex = runCatching { ocr(render(c.shown)) }.getOrNull().orEmpty()
      val s = runCatching { SymbolicStepEngine.solveFromLatex(latex) }.getOrNull()
      val v = s?.let { verdict(it.finalAnswer, c) } ?: "FAIL (crashed)"
      if (v == "PASS") opass++ else ofail++
      Log.i(TAG, "$v | ${c.shown} | read: $latex | ${s?.finalAnswer} | ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
    Log.i(TAG, "recogniser + solver: $opass passed, $ofail failed")

    Log.i(TAG, "=== 3. Your handwriting (newest line of each note)")
    for ((title, ink) in notes) {
      val line = SolveTarget.pick(ink.filter { it.isInk })
      if (line.isEmpty()) continue
      val latex = runCatching { ocr(renderStrokesToBitmap(line)) }.getOrNull().orEmpty()
      val s = runCatching { SymbolicStepEngine.solveFromLatex(latex) }.getOrNull()
      Log.i(TAG, "$title | read: $latex | ${s?.problemType} | ${s?.finalAnswer}")
    }
    Log.i(TAG, "done")
  }
}
