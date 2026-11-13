package com.deang.geminimath

import kotlin.math.*

/**
 * Deterministic step-by-step solver. Takes the LaTeX recognised from handwriting and returns
 * steps and a final answer for arithmetic, equations, derivatives and integrals.
 */
object SymbolicStepEngine {
  data class SymbolicSolution(
    val problemType: String,
    val normalizedMath: String,
    val steps: List<String>,
    val finalAnswer: String,
    val plottableExpr: String?,
    val elapsedMs: Long,
    /** Plain-text antiderivative for integrals, so callers and tests can check it. */
    val antiderivativeExpr: String? = null
  ) {
    /** True when the input couldn't be read, so explanations should not be generated for it. */
    val isUnreadable: Boolean get() = problemType == UNREADABLE
  }

  const val UNREADABLE = "Unrecognised input"

  // The differential: dx, \mathrm{d}x, and \alpha x / \partial x, which the handwriting model
  // sometimes outputs for a handwritten "d".
  private const val DIFF = """(?:\\mathrm\s*\{\s*d\s*\}|\\operatorname\s*\{\s*d\s*\}|\\alpha|\\partial|\\delta|d)\s*x"""
  private const val BOUND = """(\{[^{}]*\}|\\[a-zA-Z]+|-?[0-9.]+|[a-zA-Z])"""
  private val DEF_LOWER_FIRST = Regex("""^\\int\s*_\s*$BOUND\s*\^\s*$BOUND\s*(.+?)\s*(?:$DIFF)?\s*$""")
  private val DEF_UPPER_FIRST = Regex("""^\\int\s*\^\s*$BOUND\s*_\s*$BOUND\s*(.+?)\s*(?:$DIFF)?\s*$""")
  private val INDEF = Regex("""^\\int\s*(.+?)\s*(?:$DIFF)?\s*$""")
  private val DERIV = Regex("""^\\frac\s*\{\s*(?:\\mathrm\s*\{\s*d\s*\}|d)\s*\}\s*\{\s*(?:\\mathrm\s*\{\s*d\s*\}|d)\s*x\s*\}\s*(.+)$""")
  private val TRAILING_EQUALS = Regex("""=\s*\??\s*$""")
  private val PRODUCT_BEFORE_DIFF = Regex("""\s*(?:\\cdot|\\times|\*|·)\s*(?=$DIFF\s*$)""")
  private val FUNC_DEF = Regex("""^(?:y|[a-wyzA-Z]\s*\(\s*x\s*\))\s*=\s*(.+)$""")
  private val LIM = Regex("""^\\lim\s*_\s*\{\s*([a-zA-Z])\s*(?:\\to|\\rightarrow|→|->)\s*([^{}]+?)\s*\}\s*(.+)$""")
  private val SUM = Regex("""^\\sum\s*_\s*\{\s*([a-zA-Z])\s*=\s*([^{}]+)\}\s*\^\s*(\{[^{}]*\}|\\infty|[0-9]+)\s*(.+)$""")
  // (?![a-zA-Z]) stops \le from matching the start of \left
  private val INEQ = Regex("""^(.+?)\s*((?:\\leq|\\geq|\\le|\\ge|\\lt|\\gt)(?![a-zA-Z])|≤|≥|<|>)\s*(.+)$""")

  /** Rewrites a single-letter variable (n, k, t …) as x so the engine can evaluate it. */
  private fun renameVar(latex: String, v: String): String =
    if (v == "x") latex else latex.replace(Regex("""(?<![a-zA-Z\\])$v(?![a-zA-Z])"""), "x")

  private fun fmt(v: Double) = MathFormat.num(v)
  private fun ms(t0: Long) = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)

  // Letters the handwriting model often writes for digits, when they stand alone (not in \sin, \ln …)
  private val LOOKALIKES = listOf(
    Regex("""(?<![a-zA-Z\\])[sS](?![a-zA-Z])""") to "5",
    Regex("""(?<![a-zA-Z\\])[oO](?![a-zA-Z])""") to "0",
    Regex("""(?<![a-zA-Z\\])l(?![a-zA-Z])""") to "1"
  )

  /**
   * Solves [rawLatex]. If it can't be read, tries again with digit look-alikes swapped in
   * ("s x" → "5 x"), since the handwriting model often confuses them.
   */
  fun solveFromLatex(rawLatex: String): SymbolicSolution {
    val first = solveOnce(rawLatex)
    if (!first.isUnreadable) return first
    var fixed = rawLatex
    for ((re, digit) in LOOKALIKES) fixed = fixed.replace(re, digit)
    if (fixed == rawLatex) return first
    val second = solveOnce(fixed)
    if (second.isUnreadable) return first
    return second.copy(steps = listOf("Read the handwriting as: ${second.normalizedMath} (letters that look like digits were read as digits)") + second.steps)
  }

  private fun solveOnce(rawLatex: String): SymbolicSolution {
    val t0 = System.nanoTime()
    // Font commands (\mathbf{e^x · x dx}) change nothing mathematically but hide the dx: unwrap them
    val unwrapped = unwrapFonts(fixOcrNotation(LocalMathEngine.cleanLatexSpacing(rawLatex)))
    // Piecewise functions keep their rows, so read them before the layout is stripped
    Piecewise.parse(unwrapped)?.let { return piecewise(it, t0) }
    // Matrices, determinants, systems of equations, and 1st-order differential equations
    LinearAlgebraAndSystems.trySolve(unwrapped, t0)?.let { return it }
    val raw = stripLayout(unwrapped)
    if (raw.isBlank()) return waiting()
    // The OCR often writes a product sign before the differential ("… \cdot dx"): drop it
    val body = raw.replace(TRAILING_EQUALS, "").trim().replace(PRODUCT_BEFORE_DIFF, " ")
    if (body.isBlank()) return waiting()

    DERIV.find(body)?.let { return solveDerivative(it.groupValues[1], t0) }
    LIM.find(body)?.let { m ->
      val v = m.groupValues[1]
      return solveLimit(renameVar(m.groupValues[3], v), m.groupValues[2], t0)
    }
    SUM.find(body)?.let { m ->
      val v = m.groupValues[1]
      return solveSum(renameVar(m.groupValues[4], v), m.groupValues[2], m.groupValues[3], v, t0)
    }

    DEF_LOWER_FIRST.find(body)?.let {
      return solveDefiniteIntegral(it.groupValues[1], it.groupValues[2], it.groupValues[3], t0)
    }
    DEF_UPPER_FIRST.find(body)?.let {
      return solveDefiniteIntegral(it.groupValues[2], it.groupValues[1], it.groupValues[3], t0)
    }
    INDEF.find(body)?.let { return solveIndefiniteIntegral(it.groupValues[1], t0) }

    INEQ.find(body)?.let { m -> return solveInequality(m.groupValues[1], m.groupValues[2], m.groupValues[3], t0) }

    FUNC_DEF.find(body)?.let { m ->
      val expr = LocalMathEngine.latexToMathExpr(m.groupValues[1])
      val ex = LocalMathEngine.parse(expr) ?: return unreadable(raw, t0)
      return if (ex.hasX()) analyseFunction(ex, expr, t0) else evaluate(ex, t0)
    }

    if (body.contains("=")) {
      val parts = body.split("=")
      val lhsLatex = parts[0].trim()
      val rhsLatex = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() } ?: "0"
      val topFrac = extractTopLevelFraction(lhsLatex)
      val rhsExpr = LocalMathEngine.latexToMathExpr(rhsLatex)
      val lhsExpr = LocalMathEngine.latexToMathExpr(lhsLatex)
      if (LocalMathEngine.parse(lhsExpr) == null || LocalMathEngine.parse(rhsExpr) == null) return unreadable(raw, t0)
      if (topFrac != null && topFrac.second.contains("x", ignoreCase = true)) {
        return solveRationalEquation(topFrac.first, topFrac.second, rhsExpr, t0)
      }
      return solveAlgebraicEquation(lhsExpr, rhsExpr, t0)
    }

    val expr = LocalMathEngine.latexToMathExpr(body)
    val ex = LocalMathEngine.parse(expr) ?: return unreadable(raw, t0)
    return if (ex.hasX()) analyseFunction(ex, expr, t0) else evaluate(ex, t0)
  }

  /** f(x) = { piece if condition ; … }: the pieces, and whether f is continuous where they meet. */
  private fun piecewise(pieces: List<Piecewise.Piece>, t0: Long): SymbolicSolution {
    val spec = Piecewise.encode(pieces)
    fun p(e: String) = LocalMathEngine.parse(e)?.let { MathFormat.pretty(it) } ?: e
    fun cond(c: String) = if (c == "else") "otherwise" else "for " + Piecewise.pretty(c).trim()
    val shown = "f(x) = { " + pieces.joinToString(" ; ") { "${p(it.expr)} ${cond(it.cond)}" } + " }"
    val steps = mutableListOf("Recognised a piecewise function: $shown")
    pieces.filter { it.guessed }.forEach {
      steps += "The condition next to ${p(it.expr)} wasn't clear, so it was read as the rest of the line: ${Piecewise.pretty(it.cond).trim()}."
    }
    pieces.forEach { steps += "${if (it.cond == "else") "For every other x" else "For " + Piecewise.pretty(it.cond).trim()}: f(x) = ${p(it.expr)}." }
    val jumps = mutableListOf<String>()
    for (b in Piecewise.breakpoints(pieces)) {
      val h = 1e-7 * maxOf(1.0, kotlin.math.abs(b))
      val left = Piecewise.eval(spec, b - h); val right = Piecewise.eval(spec, b + h); val at = Piecewise.eval(spec, b)
      val bx = fmt(b)
      if (!left.isFinite() || !right.isFinite()) continue
      val same = kotlin.math.abs(left - right) < 1e-5 * maxOf(1.0, kotlin.math.abs(left))
      steps += "At x = $bx: from the left f → ${fmt(left)}, from the right f → ${fmt(right)}, and f($bx) = ${fmt(at)}."
      if (same && kotlin.math.abs(at - left) < 1e-5 * maxOf(1.0, kotlin.math.abs(left))) {
        steps += "Both sides meet at f($bx), so f is continuous at x = $bx."
      } else {
        steps += "The two sides don't meet (a jump of ${fmt(kotlin.math.abs(right - left))}), so f isn't continuous at x = $bx."
        jumps += bx
      }
    }
    val answer = when {
      jumps.isEmpty() -> "Continuous at the break point" + if (Piecewise.breakpoints(pieces).size > 1) "s" else ""
      else -> "Jump at x = " + jumps.joinToString(", ")
    }
    return SymbolicSolution(
      problemType = "Piecewise function",
      normalizedMath = shown,
      steps = steps,
      finalAnswer = answer,
      plottableExpr = spec,
      elapsedMs = (System.nanoTime() - t0) / 1_000_000
    )
  }

  private val LAYOUT_ENV = Regex("""\\(?:begin|end)\s*\{\s*(?:matrix|pmatrix|bmatrix|array|aligned|align\*?|gathered|gather\*?|split|cases)\s*\}(?:\s*\{[lcr|]*\})?""")

  /**
   * Undoes layout the recogniser adds around a formula: \begin{matrix}…\end{matrix}, row breaks
   * (\\) and column marks (&). With several rows, the longest is kept. Then unmatched braces
   * are dropped or closed, so "x + 70 }" still parses.
   */
  internal fun stripLayout(s: String): String {
    if (!s.contains("\\begin") && !s.contains("\\\\") && s.count { it == '{' } == s.count { it == '}' }) return s
    val rows = s.replace(LAYOUT_ENV, " ").split("\\\\").map { it.replace("&", " ").trim() }.filter { it.isNotEmpty() }
    val row = rows.maxByOrNull { it.length } ?: return ""
    val out = StringBuilder()
    var depth = 0
    for (c in row) {
      when (c) {
        '{' -> { depth++; out.append(c) }
        '}' -> if (depth > 0) { depth--; out.append(c) }
        else -> out.append(c)
      }
    }
    repeat(depth) { out.append('}') }
    return out.toString().trim()
  }

  private val OLD_FONT = Regex("""\{\s*\\(?:bf|rm|it|sf|tt|cal|mit|bm)\s+([^{}]*)\}""")
  private val ROOT_SIGN = Regex("""\\(?:surd|forall)(?![a-zA-Z])""")

  /**
   * Notation the handwriting model writes that means something simpler: old-style fonts
   * ({\bf x} → x), and the root sign written as \surd or misread as ∀ (\forall(x+3) → √(x+3)).
   */
  internal fun fixOcrNotation(s: String): String {
    var out = s.replace(OLD_FONT) { " " + it.groupValues[1] + " " }
    while (true) {
      val m = ROOT_SIGN.find(out) ?: break
      var i = m.range.last + 1
      while (i < out.length && out[i] == ' ') i++
      val open = out.getOrNull(i)
      val close = when (open) { '(' -> ')'; '{' -> '}'; '[' -> ']'; else -> null }
      if (close == null) { out = out.substring(0, m.range.first) + "\\sqrt " + out.substring(m.range.last + 1); continue }
      var depth = 0; var j = i
      while (j < out.length) {
        if (out[j] == open) depth++ else if (out[j] == close) { depth--; if (depth == 0) break }
        j++
      }
      if (j >= out.length) { out = out.substring(0, m.range.first) + "\\sqrt " + out.substring(m.range.last + 1); continue }
      out = out.substring(0, m.range.first) + "\\sqrt{" + out.substring(i + 1, j) + "}" + out.substring(j + 1)
    }
    return out
  }

  private val FONT_CMD = Regex("""\\(?:dot|ddot|hat|tilde|acute|grave|breve|check|mathbf|mathrm|mathit|mathsf|mathtt|boldsymbol|bm|textbf|textrm|textit|text)\s*\{""")

  /** Replaces \mathbf{…} (and similar) with its contents, handling nested braces. */
  internal fun unwrapFonts(s: String): String {
    var out = s
    while (true) {
      val m = FONT_CMD.find(out) ?: return out
      var depth = 1
      var i = m.range.last + 1
      while (i < out.length && depth > 0) {
        when (out[i]) { '{' -> depth++; '}' -> depth-- }
        i++
      }
      if (depth != 0) return out
      out = out.substring(0, m.range.first) + " " + out.substring(m.range.last + 1, i - 1) + " " + out.substring(i)
    }
  }

  private fun waiting() = SymbolicSolution(
    problemType = "Waiting for input",
    normalizedMath = "",
    steps = emptyList(),
    finalAnswer = "Write a math expression on the canvas",
    plottableExpr = null,
    elapsedMs = 0L
  )

  private fun unreadable(raw: String, t0: Long) = SymbolicSolution(
    problemType = UNREADABLE,
    normalizedMath = raw,
    steps = listOf(
      "Recognised as: $raw",
      "This doesn't form a valid expression. Rewrite the part that was misread, or select only the expression with the lasso."
    ),
    finalAnswer = "Couldn't read this expression",
    plottableExpr = null,
    elapsedMs = ms(t0)
  )

  private fun evaluate(ex: Ex, t0: Long): SymbolicSolution {
    val v = ex.eval(0.0, MathSettings.angleDegrees)
    val shown = ex.pretty()
    val answer = when {
      v.isInfinite() -> "Undefined (division by zero)"
      v.isNaN() -> "Not a real number"
      else -> {
        val plain = fmt(v)
        val withPi = MathFormat.withPi(v)
        // Keep π in the answer when the input used π: 2π ≈ 6.2832
        if (shown.contains('π') && withPi.contains('π') && withPi != plain) "$withPi ≈ ${String.format(java.util.Locale.US, "%.4f", v)}" else plain
      }
    }
    return SymbolicSolution(
      problemType = "Arithmetic",
      normalizedMath = shown,
      steps = listOf("Evaluate: $shown = $answer" + if (MathSettings.angleDegrees && shown.contains("sin|cos|tan".toRegex())) "  (angles in degrees)" else ""),
      finalAnswer = answer,
      plottableExpr = null,
      elapsedMs = ms(t0)
    )
  }

  private fun analyseFunction(ex: Ex, expr: String, t0: Long): SymbolicSolution {
    val steps = ArrayList<String>()
    val d = Deriv.of(ex)
    steps.add("Derivative: f'(x) = ${d.pretty()}")
    val deg = MathSettings.angleDegrees
    val study = FunctionStudy.study({ ex.eval(it, deg) }, { d.eval(it, deg) })
    val (studySteps, summary) = FunctionStudy.describe(study)
    steps.addAll(studySteps)
    return SymbolicSolution(
      problemType = "Function f(x)",
      normalizedMath = "f(x) = ${ex.pretty()}",
      steps = steps,
      finalAnswer = summary,
      plottableExpr = expr,
      elapsedMs = ms(t0)
    )
  }

  private fun solveDerivative(bodyLatex: String, t0: Long): SymbolicSolution {
    val expr = LocalMathEngine.latexToMathExpr(bodyLatex)
    val f = LocalMathEngine.parse(expr) ?: return unreadable(bodyLatex, t0)
    val d = Deriv.of(f)
    return SymbolicSolution(
      problemType = "Derivative",
      normalizedMath = "d/dx [${f.pretty()}]",
      steps = listOf(
        "Differentiate with the sum, product, quotient and chain rules",
        "f'(x) = ${d.pretty()}"
      ),
      finalAnswer = "f'(x) = ${d.pretty()}",
      plottableExpr = d.toPlain(),
      elapsedMs = ms(t0)
    )
  }

  private fun boundValue(raw: String): Pair<Double, String> {
    val inner = raw.removePrefix("{").removeSuffix("}").trim()
    if (inner.contains("\\infty") || inner.contains("∞")) {
      return if (inner.trimStart().startsWith("-")) Double.NEGATIVE_INFINITY to "-∞" else Double.POSITIVE_INFINITY to "∞"
    }
    val v = LocalMathEngine.evalAt(LocalMathEngine.latexToMathExpr(inner), 0.0)
    val label = LocalMathEngine.parse(LocalMathEngine.latexToMathExpr(inner))?.pretty() ?: fmt(v)
    return v to label
  }

  private fun solveDefiniteIntegral(aRaw: String, bRaw: String, bodyRaw: String, t0: Long): SymbolicSolution {
    val fExpr = LocalMathEngine.latexToMathExpr(bodyRaw)
    val f = LocalMathEngine.parse(fExpr) ?: return unreadable("\\int_{$aRaw}^{$bRaw} $bodyRaw dx", t0)
    val (a, aLabel) = boundValue(aRaw)
    val (b, bLabel) = boundValue(bRaw)
    if (a.isNaN() || b.isNaN()) return unreadable("\\int_{$aRaw}^{$bRaw} $bodyRaw dx", t0)

    val prim = Integrator.integrate(f)
    if (a.isInfinite() || b.isInfinite()) return solveImproperIntegral(f, fExpr, prim, a, aLabel, b, bLabel, t0)
    val numeric = integrateSubst(fExpr, a, b)
    val steps = ArrayList<String>()
    var answer: Double = numeric
    var diverges = false

    if (prim != null) {
      val fa = limitAt(prim.expr, a, towards = b)
      val fb = limitAt(prim.expr, b, towards = a)
      val exact = fb - fa
      steps.add("Find an antiderivative: F(x) = ${prim.pretty}")
      // F must be continuous strictly inside the interval for F(b) - F(a) to hold
      val interiorOk = (1 until 400).all { i ->
        val x = a + (b - a) * i / 400.0
        LocalMathEngine.evalAt(prim.expr, x).isFinite() && abs(f.eval(x)) < 1e7
      }
      when {
        fa.isInfinite() || fb.isInfinite() -> diverges = true
        !interiorOk -> diverges = true
        exact.isFinite() && (!numeric.isFinite() || abs(exact - numeric) < 1e-2 * (1 + abs(exact))) -> {
          answer = exact
          steps.add("Evaluate at the bounds: F($bLabel) - F($aLabel) = ${fmt(fb)} - (${fmt(fa)}) = ${fmt(exact)}")
        }
        else -> steps.add("F has a singularity between the bounds, so evaluate the integral numerically: ${fmt(numeric)}")
      }
    } else {
      steps.add("No elementary antiderivative found; integrate numerically (Gauss–Legendre)")
      steps.add("∫ from $aLabel to $bLabel of ${f.pretty()} dx ≈ ${fmt(numeric)}")
    }
    if (diverges) steps.add("F(x) is unbounded at a bound, so the integral diverges")

    return SymbolicSolution(
      problemType = "Definite integral",
      normalizedMath = "∫ from $aLabel to $bLabel of ${f.pretty()} dx",
      steps = steps,
      finalAnswer = if (diverges) "The integral diverges" else "I = ${fmt(answer)}",
      plottableExpr = fExpr,
      elapsedMs = ms(t0),
      antiderivativeExpr = prim?.expr
    )
  }

  /** Value of F as x → ±∞, or ±∞ if it grows without bound, or NaN if it oscillates. */
  private fun limitAtInfinity(expr: String, sign: Double): Double {
    val xs = doubleArrayOf(1e2, 1e3, 1e4, 1e5, 1e6, 1e7)
    val vs = xs.map { LocalMathEngine.evalAt(expr, sign * it) }
    val last = vs.last()
    val prev = vs[vs.size - 2]
    if (last.isFinite() && prev.isFinite() && abs(last - prev) < 1e-6 * (1 + abs(last))) return snap(last)
    if (last.isInfinite()) return last
    if (vs.takeLast(3).all { it.isFinite() } && abs(last) > abs(prev) && abs(prev) > abs(vs[vs.size - 3]) && abs(last) > 1e5) {
      return Double.POSITIVE_INFINITY * sign(last)
    }
    return Double.NaN
  }

  /** Rounds away numerical noise (values that are 6-decimal exact). */
  private fun snap(v: Double): Double {
    val r = Math.rint(v * 1e6) / 1e6
    return if (abs(v - r) < 1e-7 * max(1.0, abs(v))) r else v
  }

  private fun solveImproperIntegral(
    f: Ex, fExpr: String, prim: Integrator.Result?,
    a: Double, aLabel: String, b: Double, bLabel: String, t0: Long
  ): SymbolicSolution {
    val title = "∫ from $aLabel to $bLabel of ${f.pretty()} dx"
    if (prim == null) {
      return SymbolicSolution("Improper integral", title,
        listOf("No elementary antiderivative found, so this improper integral can't be evaluated"),
        "No closed form found", fExpr, ms(t0))
    }
    val fa = if (a.isInfinite()) limitAtInfinity(prim.expr, sign(a)) else limitAt(prim.expr, a, towards = a + 1)
    val fb = if (b.isInfinite()) limitAtInfinity(prim.expr, sign(b)) else limitAt(prim.expr, b, towards = b - 1)
    val steps = mutableListOf(
      "Find an antiderivative: F(x) = ${prim.pretty}",
      "Take limits at the bounds: F($bLabel) = ${fmt(fb)}, F($aLabel) = ${fmt(fa)}"
    )
    val value = fb - fa
    val diverges = !value.isFinite()
    if (diverges) steps.add("A limit is infinite or doesn't exist, so the integral diverges")
    return SymbolicSolution(
      "Improper integral", title, steps,
      if (diverges) "The integral diverges" else "I = ${fmt(value)}",
      fExpr, ms(t0), prim.expr
    )
  }

  private fun solveLimit(bodyLatex: String, targetRaw: String, t0: Long): SymbolicSolution {
    val expr = LocalMathEngine.latexToMathExpr(bodyLatex)
    val f = LocalMathEngine.parse(expr) ?: return unreadable("\\lim $bodyLatex", t0)
    val tgt = targetRaw.trim()
    val (t, tLabel) = boundValue(tgt)
    if (t.isNaN()) return unreadable("\\lim_{x \\to $tgt} $bodyLatex", t0)
    val title = "lim x→$tLabel of ${f.pretty()}"
    val steps = ArrayList<String>()
    val answer: String
    if (t.isInfinite()) {
      val v = limitAtInfinity(expr, sign(t))
      steps.add("Evaluate f(x) for increasingly large ${if (t > 0) "" else "negative "}x")
      answer = when {
        v.isNaN() -> "The limit does not exist"
        v.isInfinite() -> if (v > 0) "∞" else "-∞"
        else -> fmt(v)
      }
    } else {
      val direct = f.eval(t)
      val near = f.eval(t + 1e-7)
      if (direct.isFinite() && near.isFinite() && abs(direct - near) < 1e-4 * (1 + abs(direct))) {
        steps.add("f is continuous at x = $tLabel, so substitute directly: f($tLabel) = ${fmt(direct)}")
        answer = fmt(direct)
      } else {
        fun side(dir: Double): Double {
          val hs = doubleArrayOf(1e-3, 1e-4, 1e-5, 1e-6, 1e-7)
          val vs = hs.map { f.eval(t + dir * it) }
          val last = vs.last()
          val prev = vs[vs.size - 2]
          if (last.isFinite() && prev.isFinite() && abs(last - prev) < 1e-4 * (1 + abs(last))) return snap(last)
          if (last.isFinite() && abs(last) > 1e5 && abs(last) > abs(prev)) return Double.POSITIVE_INFINITY * sign(last)
          return Double.NaN
        }
        val r = side(1.0)
        val l = side(-1.0)
        steps.add("Direct substitution gives ${if (direct.isNaN()) "an indeterminate form" else "an undefined value"}, so approach x = $tLabel from both sides")
        steps.add("From the left: ${fmt(l)}; from the right: ${fmt(r)}".replace("undefined", "doesn't settle"))
        answer = when {
          l.isNaN() || r.isNaN() -> "The limit does not exist"
          l == r && l.isInfinite() -> if (l > 0) "∞" else "-∞"
          abs(l - r) < 1e-4 * (1 + abs(r)) -> fmt(r)
          else -> "The limit does not exist (left ${fmt(l)}, right ${fmt(r)})"
        }
      }
    }
    return SymbolicSolution("Limit", title, steps, answer, expr, ms(t0))
  }

  private fun solveSum(bodyLatex: String, fromRaw: String, toRaw: String, v: String, t0: Long): SymbolicSolution {
    val expr = LocalMathEngine.latexToMathExpr(bodyLatex)
    val f = LocalMathEngine.parse(expr) ?: return unreadable("\\sum $bodyLatex", t0)
    val from = LocalMathEngine.evalAt(LocalMathEngine.latexToMathExpr(fromRaw), 0.0)
    val (to, toLabel) = boundValue(toRaw)
    if (!from.isFinite() || from != Math.rint(from) || to.isNaN()) return unreadable("\\sum_{$v=$fromRaw}^{$toRaw} $bodyLatex", t0)
    val title = "Σ from $v = ${fmt(from)} to $toLabel of ${f.pretty().replace("x", v)}"
    if (to.isInfinite()) {
      var s = 0.0
      var s5 = 0.0
      var n = from.toLong()
      val end = from.toLong() + 1_000_000L
      while (n < end) {
        s += f.eval(n.toDouble())
        if (n == from.toLong() + 100_000L) s5 = s
        n++
      }
      val converges = s.isFinite() && abs(s - s5) < 1e-4 * (1 + abs(s))
      return SymbolicSolution(
        "Infinite series", title,
        listOf("Add terms until the partial sums settle (1,000,000 terms)", if (converges) "Partial sums converge to about ${fmt(s)}" else "Partial sums keep growing"),
        if (converges) "≈ ${fmt(s)}" else "The series diverges",
        null, ms(t0)
      )
    }
    if (to < from) return SymbolicSolution("Sum", title, listOf("The upper bound is below the lower bound, so the sum is empty"), "0", null, ms(t0))
    if (to - from > 10_000_000) return unreadable("\\sum_{$v=$fromRaw}^{$toRaw} $bodyLatex", t0)
    var s = 0.0
    var n = from
    while (n <= to) { s += f.eval(n); n += 1.0 }
    val first = (0 until min(3, (to - from + 1).toInt())).joinToString(" + ") { fmt(f.eval(from + it)) }
    return SymbolicSolution(
      "Sum", title,
      listOf("Add the terms for $v = ${fmt(from)} … ${fmt(to)}: $first${if (to - from >= 3) " + …" else ""}"),
      fmt(s), null, ms(t0)
    )
  }

  private fun solveInequality(lhsLatex: String, opRaw: String, rhsLatex: String, t0: Long): SymbolicSolution {
    val op = when (opRaw) {
      "\\leq", "\\le", "≤" -> "≤"
      "\\geq", "\\ge", "≥" -> "≥"
      "\\lt", "<" -> "<"
      else -> ">"
    }
    val lhs = LocalMathEngine.latexToMathExpr(lhsLatex)
    val rhs = LocalMathEngine.latexToMathExpr(rhsLatex)
    val f = LocalMathEngine.parse("($lhs)-($rhs)") ?: return unreadable("$lhsLatex $opRaw $rhsLatex", t0)
    val shown = "${prettyOf(lhs)} $op ${prettyOf(rhs)}"
    fun holds(v: Double) = when (op) { ">", "≥" -> v > 0; else -> v < 0 }
    val strict = op == "<" || op == ">"
    if (!f.hasX()) {
      val v = f.eval(0.0, MathSettings.angleDegrees)
      val ok = holds(v) || (!strict && abs(v) < 1e-12)
      return SymbolicSolution("Inequality check", shown, listOf("Both sides are numbers: left - right = ${fmt(v)}"), if (ok) "True" else "False", null, ms(t0))
    }

    // Boundaries: zeros (included for ≤/≥) and poles (never included)
    data class Edge(val x: Double, val isRoot: Boolean)
    val edges = ArrayList<Edge>()
    val n = 4000
    val lo = -100.0
    val hi = 100.0
    // Compare each finite sample with the previous finite one, so a pole that lands
    // exactly on a sample point (1/x at 0) still shows up as a sign change
    var px = lo + 1e-7
    var py = f.eval(px)
    for (i in 1..n) {
      val cx = lo + (hi - lo) * i / n + 1e-7
      val cy = f.eval(cx)
      if (!cy.isFinite()) continue
      if (py.isFinite() && abs(py) < 1e-12) edges.add(Edge(px, true))
      else if (py.isFinite() && py * cy < 0) {
        var a = px; var b = cx; var ya = py
        repeat(60) {
          val m = 0.5 * (a + b)
          val ym = f.eval(m)
          if (!ym.isFinite() || ya * ym <= 0) b = m else { a = m; ya = ym }
        }
        val m = 0.5 * (a + b)
        val ym = f.eval(m)
        edges.add(Edge(snap(m), ym.isFinite() && abs(ym) < 1e-6 * (1 + abs(py) + abs(cy))))
      }
      px = cx; py = cy
    }
    val pts = edges.distinctBy { Math.rint(it.x * 1e6) }.sortedBy { it.x }
    data class Iv(val a: Double, val b: Double, val aIn: Boolean, val bIn: Boolean)
    val ivs = ArrayList<Iv>()
    val bounds = listOf(Double.NEGATIVE_INFINITY) + pts.map { it.x } + listOf(Double.POSITIVE_INFINITY)
    for (i in 0 until bounds.size - 1) {
      val a = bounds[i]; val b = bounds[i + 1]
      val mid = when {
        a.isInfinite() && b.isInfinite() -> 0.0
        a.isInfinite() -> b - 1
        b.isInfinite() -> a + 1
        else -> 0.5 * (a + b)
      }
      if (holds(f.eval(mid))) {
        val aIn = !strict && i > 0 && pts[i - 1].isRoot
        val bIn = !strict && i < pts.size && pts[i].isRoot
        val prev = ivs.lastOrNull()
        if (prev != null && prev.b == a && (prev.bIn || aIn)) ivs[ivs.size - 1] = prev.copy(b = b, bIn = bIn)
        else ivs.add(Iv(a, b, aIn, bIn))
      }
    }
    // Isolated roots that satisfy ≤ / ≥ (e.g. (x-1)² ≤ 0)
    if (!strict) for (p in pts) if (p.isRoot && ivs.none { p.x >= it.a && p.x <= it.b }) ivs.add(Iv(p.x, p.x, true, true))
    ivs.sortBy { it.a }

    fun lt(inc: Boolean) = if (inc) "≤" else "<"
    val answer = when {
      ivs.isEmpty() -> "No solution"
      ivs.size == 1 && ivs[0].a.isInfinite() && ivs[0].b.isInfinite() -> "All real numbers"
      else -> ivs.joinToString(" or ") { iv ->
        when {
          iv.a == iv.b -> "x = ${fmt(iv.a)}"
          iv.a.isInfinite() -> "x ${lt(iv.bIn)} ${fmt(iv.b)}"
          iv.b.isInfinite() -> "x ${if (iv.aIn) "≥" else ">"} ${fmt(iv.a)}"
          else -> "${fmt(iv.a)} ${lt(iv.aIn)} x ${lt(iv.bIn)} ${fmt(iv.b)}"
        }
      }
    }
    val roots = pts.filter { it.isRoot }.joinToString(", ") { fmt(it.x) }
    val poles = pts.filter { !it.isRoot }.joinToString(", ") { fmt(it.x) }
    val steps = listOfNotNull(
      "Move everything to one side: ${prettyOf("($lhs)-($rhs)")} $op 0",
      if (roots.isNotEmpty()) "Zeros at x = $roots" else "No zeros",
      if (poles.isNotEmpty()) "Undefined at x = $poles" else null,
      "Check the sign between these points"
    )
    return SymbolicSolution("Inequality", shown, steps, answer, "($lhs)-($rhs)", ms(t0))
  }

  /**
   * F(x0), or its one-sided limit from the [towards] side when F(x0) is 0·∞ or similar
   * (e.g. √x·ln(x) at 0). Returns ±∞ when F really blows up there.
   */
  private fun limitAt(expr: String, x0: Double, towards: Double): Double {
    val direct = LocalMathEngine.evalAt(expr, x0)
    if (direct.isFinite()) return direct
    val dir = if (towards > x0) 1.0 else -1.0
    // Terms like √x·ln(x) approach their limit slowly, so probe very close to the bound
    val d1 = if (x0 == 0.0) 1e-14 else 1e-10 * abs(x0)
    val near = LocalMathEngine.evalAt(expr, x0 + dir * d1)
    val nearer = LocalMathEngine.evalAt(expr, x0 + dir * d1 / 100)
    if (!near.isFinite() || !nearer.isFinite()) return if (direct.isInfinite()) direct else Double.NaN
    return if (abs(near - nearer) < 1e-4 * (1 + abs(nearer))) nearer
    else if (abs(nearer) > abs(near)) Double.POSITIVE_INFINITY * sign(nearer) else Double.NaN
  }

  private fun solveIndefiniteIntegral(bodyRaw: String, t0: Long): SymbolicSolution {
    val fExpr = LocalMathEngine.latexToMathExpr(bodyRaw)
    val f = LocalMathEngine.parse(fExpr) ?: return unreadable("\\int $bodyRaw dx", t0)
    val prim = Integrator.integrate(f)
    return if (prim != null) {
      SymbolicSolution(
        problemType = "Indefinite integral",
        normalizedMath = "∫ ${f.pretty()} dx",
        steps = prim.steps,
        finalAnswer = if (prim.pretty == "0") "C" else "${prim.pretty} + C",
        plottableExpr = fExpr,
        elapsedMs = ms(t0),
        antiderivativeExpr = prim.expr
      )
    } else {
      halfPowerTimesExp(fExpr)?.let { (steps, answer) ->
        return SymbolicSolution(
          problemType = "Indefinite integral",
          normalizedMath = "∫ ${f.pretty()} dx",
          steps = steps,
          finalAnswer = "$answer + C",
          plottableExpr = fExpr,
          elapsedMs = ms(t0)
        )
      }
      val i01 = integrateSubst(fExpr, 0.0, 1.0)
      SymbolicSolution(
        problemType = "Indefinite integral",
        normalizedMath = "∫ ${f.pretty()} dx",
        steps = listOf(
          "No elementary antiderivative found for ${f.pretty()}",
          "For reference, ∫ from 0 to 1 ≈ ${fmt(i01)}"
        ),
        finalAnswer = "No closed form found",
        plottableExpr = fExpr,
        elapsedMs = ms(t0)
      )
    }
  }

  /**
   * ∫ c·x^p·e^(±x) dx with p = n + ½ (for example x²·√x·eˣ). There is no elementary answer:
   * integrating by parts n+1 times leaves ∫ x^(-½)·e^(±x) dx = √π·erfi(√x) (or √π·erf(√x)).
   * The integrand is identified numerically, so any way of writing it works.
   * Returns (steps, answer without "+ C"), or null when the integrand isn't of this form.
   */
  internal fun halfPowerTimesExp(fExpr: String): Pair<List<String>, String>? {
    val samples = listOf(0.3, 0.7, 1.1, 1.6, 2.3)
    val f = samples.map { LocalMathEngine.evalAt(fExpr, it, false).takeIf { v -> v.isFinite() } ?: return null }
    for (sign in listOf(1, -1)) for (twoP in -1..11 step 2) {
      val p = twoP / 2.0
      val g = samples.map { x -> Math.pow(x, p) * Math.exp(sign * x) }
      val c = f[0] / g[0]
      if (!c.isFinite() || abs(c) < 1e-9) continue
      if (samples.indices.any { i -> abs(f[i] - c * g[i]) > 1e-7 * (1 + abs(f[i])) }) continue
      // Exact rational coefficients: numerators over powers of 2
      val n = (twoP + 1) / 2 // number of integrations by parts before x^(-1/2)
      val terms = ArrayList<String>()
      var num = 1L; var den = 1L // running product p(p-1)…, as num/den
      for (k in 0 until n) {
        val exp = twoP - 2 * k // exponent ×2
        val coef = if (sign == 1) (if (k % 2 == 0) 1 else -1) else 1
        terms += signedTerm(coef * num, den, exp, first = k == 0)
        num *= (twoP - 2 * k).toLong(); den *= 2
      }
      val (rn, rd) = reduce(num, den)
      val special = if (sign == 1) "erfi(√x)" else "erf(√x)"
      val tailSign = if (sign == 1) (if (n % 2 == 0) 1 else -1) else 1
      val tail = (if (tailSign * rn > 0) " + " else " − ") + frac(abs(rn), rd, "√π") + "·$special"
      val ex = if (sign == 1) "eˣ" else "−e⁻ˣ"
      val cPrefix = if (abs(c - 1) < 1e-9) "" else "${fmt(c)}·"
      val body = if (terms.isEmpty()) "" else "$ex(${terms.joinToString("")})"
      // With no parts left (p = −½) the special-function term stands alone
      val expr = if (body.isNotEmpty()) body + tail
      else tail.trim().let { if (it.startsWith("+ ")) it.removePrefix("+ ") else "−" + it.removePrefix("− ") }
      val answer = if (cPrefix.isEmpty()) expr else "$cPrefix($expr)"
      val pStr = if (twoP % 2 == 0) "${twoP / 2}" else "$twoP/2"
      val steps = listOf(
        "The integrand is ${if (cPrefix.isEmpty()) "" else cPrefix}x^($pStr)·${if (sign == 1) "eˣ" else "e⁻ˣ"}: a half-integer power times an exponential",
        "Integrate by parts ${if (n == 1) "once" else "$n times"} (u = power of x, dv = ${if (sign == 1) "eˣ" else "e⁻ˣ"} dx); each time the power drops by 1",
        "That leaves ∫ x^(−1/2)·${if (sign == 1) "eˣ" else "e⁻ˣ"} dx. Substituting x = t² gives 2∫ e^(${if (sign == 1) "" else "−"}t²) dt = √π·$special",
        "There is no answer in elementary functions: it needs the ${if (sign == 1) "imaginary error function erfi" else "error function erf"}"
      )
      return steps to answer
    }
    return null
  }

  private fun gcd(a: Long, b: Long): Long = if (b == 0L) abs(a) else gcd(b, a % b)
  private fun reduce(n: Long, d: Long): Pair<Long, Long> { val g = gcd(n, d).coerceAtLeast(1); return n / g to d / g }
  private fun frac(n: Long, d: Long, factor: String = ""): String = when {
    d == 1L && n == 1L && factor.isNotEmpty() -> factor
    d == 1L -> "$n$factor"
    else -> "($n$factor/$d)"
  }
  private fun powX(twoE: Int): String = when (twoE) {
    1 -> "√x"
    2 -> "x"
    else -> if (twoE % 2 == 0) "x^${twoE / 2}" else "x^($twoE/2)"
  }
  private fun signedTerm(numSigned: Long, den: Long, twoE: Int, first: Boolean): String {
    val (n, d) = reduce(abs(numSigned), den)
    val coef = if (n == 1L && d == 1L) "" else (if (d == 1L) "$n·" else "($n/$d)·")
    val s = when {
      first -> if (numSigned < 0) "−" else ""
      numSigned < 0 -> " − "
      else -> " + "
    }
    return s + coef + powX(twoE)
  }

  private fun prettyOf(expr: String) = LocalMathEngine.parse(expr)?.pretty() ?: expr

  private fun solveRationalEquation(numLatex: String, denLatex: String, rhsExpr: String, t0: Long): SymbolicSolution {
    val numExpr = LocalMathEngine.latexToMathExpr(numLatex)
    val denExpr = LocalMathEngine.latexToMathExpr(denLatex)
    val steps = mutableListOf<String>()
    val nP = prettyOf(numExpr)
    val dP = prettyOf(denExpr)
    val rP = prettyOf(rhsExpr)

    val denPoly = polyCoeffs(denExpr)
    val excluded = mutableListOf<Double>()
    if (denPoly != null && abs(denPoly.a) < 1e-9 && abs(denPoly.b) > 1e-9) {
      val xExcl = -denPoly.c / denPoly.b
      excluded.add(xExcl)
      steps.add("Domain: $dP ≠ 0 ⇒ x ≠ ${fmt(xExcl)}")
    } else {
      steps.add("Domain: $dP ≠ 0")
    }
    steps.add("Multiply both sides by ($dP): $nP = ($rP)·($dP)")

    val combExpr = "($numExpr)-($rhsExpr)*($denExpr)"
    val poly = polyCoeffs(combExpr)
    if (poly != null && abs(poly.a) < 1e-9 && abs(poly.b) > 1e-9) {
      val xSol = -poly.c / poly.b
      steps.add("Collect like terms: ${MathFormat.poly(doubleArrayOf(poly.c, poly.b))} = 0")
      steps.add("Solve for x: x = ${fmt(xSol)}")
      val ok = excluded.none { abs(it - xSol) < 1e-7 }
      if (!ok) steps.add("x = ${fmt(xSol)} makes the denominator zero, so it is rejected")
      return SymbolicSolution(
        problemType = "Rational equation",
        normalizedMath = "($nP)/($dP) = $rP",
        steps = steps,
        finalAnswer = if (ok) "x = ${fmt(xSol)}" else "No solution",
        plottableExpr = "(($numExpr)/($denExpr))-($rhsExpr)",
        elapsedMs = ms(t0)
      )
    }
    return solveAlgebraicEquation("($numExpr)/($denExpr)", rhsExpr, t0)
  }

  private fun solveAlgebraicEquation(lhsExpr: String, rhsExpr: String, t0: Long): SymbolicSolution {
    val diffExpr = if (rhsExpr == "0") lhsExpr else "($lhsExpr)-($rhsExpr)"
    val shown = "${prettyOf(lhsExpr)} = ${prettyOf(rhsExpr)}"
    val diffEx = LocalMathEngine.parse(diffExpr)

    if (diffEx != null && !diffEx.hasX()) {
      val v = diffEx.eval(0.0, MathSettings.angleDegrees)
      val holds = v.isFinite() && abs(v) < 1e-9
      return SymbolicSolution(
        problemType = "Equality check",
        normalizedMath = shown,
        steps = listOf("Both sides are numbers: left - right = ${fmt(v)}"),
        finalAnswer = if (holds) "True" else "False",
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    }

    val coeffs = diffEx?.let { e -> PolyFit.fit { e.eval(it) } }
    if (coeffs != null) {
      val deg = PolyFit.degree(coeffs)
      val c = coeffs.getOrElse(0) { 0.0 }
      val b = coeffs.getOrElse(1) { 0.0 }
      val a = coeffs.getOrElse(2) { 0.0 }
      if (deg == 0) {
        return SymbolicSolution(
          problemType = "Equation",
          normalizedMath = shown,
          steps = listOf("x cancels out, leaving ${fmt(c)} = 0"),
          finalAnswer = if (abs(c) < 1e-12) "True for every x" else "No solution",
          plottableExpr = null,
          elapsedMs = ms(t0)
        )
      }
      if (deg == 1) {
        val xSol = -c / b
        return SymbolicSolution(
          problemType = "Linear equation",
          normalizedMath = shown,
          steps = listOf(
            "Write in the form ax + b = 0: ${MathFormat.poly(doubleArrayOf(c, b))} = 0",
            "Move the constant to the right: ${MathFormat.monomial(b, 1)} = ${fmt(-c)}",
            "Divide both sides by ${fmt(b)}: x = ${fmt(xSol)}"
          ),
          finalAnswer = "x = ${fmt(xSol)}",
          plottableExpr = diffExpr,
          elapsedMs = ms(t0)
        )
      }
      if (deg == 2) {
        val delta = b * b - 4.0 * a * c
        val steps = mutableListOf(
          "Write in the form ax² + bx + c = 0: ${MathFormat.poly(doubleArrayOf(c, b, a))} = 0",
          "Discriminant: Δ = b² - 4ac = ${fmt(delta)}"
        )
        val type: String
        val answer: String
        when {
          delta > 1e-12 -> {
            val sq = sqrt(delta)
            val r1 = min((-b - sq) / (2 * a), (-b + sq) / (2 * a))
            val r2 = max((-b - sq) / (2 * a), (-b + sq) / (2 * a))
            steps.add("Quadratic formula: x = (-b ± √Δ) / 2a")
            type = "Quadratic equation"
            answer = "x₁ = ${fmt(r1)}, x₂ = ${fmt(r2)}"
          }
          abs(delta) <= 1e-12 -> {
            steps.add("Δ = 0, so there is one repeated root x = -b / 2a")
            type = "Quadratic equation"
            answer = "x = ${fmt(-b / (2 * a))}"
          }
          else -> {
            val re = -b / (2 * a)
            val im = sqrt(-delta) / abs(2 * a)
            steps.add("Δ < 0, so the roots are complex: x = -b/2a ± i√(-Δ)/2a")
            type = "Quadratic equation (complex roots)"
            answer = if (abs(re) < 1e-12) "x = ±${fmt(im)}i" else "x = ${fmt(re)} ± ${fmt(im)}i"
          }
        }
        return SymbolicSolution(type, shown, steps, answer, diffExpr, ms(t0))
      }
    }

    // Periodic (trigonometric) equations: give the solutions in one period plus "+ kP"
    if (diffEx != null) {
      fun repeatsEvery(p: Double) = doubleArrayOf(0.31, 1.13, 2.71, -1.9, 4.4).all { x ->
        val y0 = diffEx.eval(x)
        val y1 = diffEx.eval(x + p)
        y0.isFinite() && y1.isFinite() && abs(y0 - y1) < 1e-9 * (1 + abs(y0))
      }
      val period = when {
        repeatsEvery(PI) -> PI
        repeatsEvery(2 * PI) -> 2 * PI
        else -> null
      }
      if (period != null) {
        val pLabel = if (period == PI) "π" else "2π"
        val roots = findRoots(diffExpr)
          .map { if (abs(it) < 1e-9) 0.0 else it }
          .filter { it >= 0.0 && it < period - 1e-9 }
        val ans = if (roots.isEmpty()) "No real solutions"
        else roots.joinToString(", ") { "x = ${MathFormat.withPi(it)} + ${if (period == PI) "kπ" else "2kπ"}" } + "  (k ∈ ℤ)"
        return SymbolicSolution(
          problemType = "Trigonometric equation",
          normalizedMath = shown,
          steps = listOf(
            "Rewrite as f(x) = 0 with f(x) = ${prettyOf(diffExpr)}",
            "f repeats every $pLabel, so find the solutions in one period and add multiples of $pLabel",
            "Solutions in [0, $pLabel): " + (if (roots.isEmpty()) "none" else roots.joinToString(", ") { MathFormat.withPi(it) })
          ),
          finalAnswer = ans,
          plottableExpr = diffExpr,
          elapsedMs = ms(t0)
        )
      }
    }

    val roots = findRoots(diffExpr)
    val ans = if (roots.isEmpty()) "No real solutions in [-20, 20]" else roots.joinToString(", ") { "x = ${fmt(it)}" }
    val degNote = coeffs?.let { "Polynomial of degree ${PolyFit.degree(it)}: " } ?: ""
    return SymbolicSolution(
      problemType = "Equation",
      normalizedMath = shown,
      steps = listOf(
        "Rewrite as f(x) = 0 with f(x) = ${prettyOf(diffExpr)}",
        "${degNote}find the roots numerically (sign changes refined by bisection)"
      ),
      finalAnswer = ans,
      plottableExpr = diffExpr,
      elapsedMs = ms(t0)
    )
  }

  private data class PolyCoeffs(val a: Double, val b: Double, val c: Double)

  /** Coefficients when [expr] is a polynomial of degree ≤ 2. */
  private fun polyCoeffs(expr: String): PolyCoeffs? {
    val e = LocalMathEngine.parse(expr) ?: return null
    val c = PolyFit.fit { e.eval(it) } ?: return null
    if (PolyFit.degree(c) > 2) return null
    return PolyCoeffs(c.getOrElse(2) { 0.0 }, c.getOrElse(1) { 0.0 }, c[0])
  }

  private fun extractTopLevelFraction(latex: String): Pair<String, String>? {
    val s = latex.trim()
    if (!s.startsWith("\\frac")) return null
    val first = extractBracedGroup(s, 5) ?: return null
    val second = extractBracedGroup(s, first.second) ?: return null
    if (s.substring(second.second).isNotBlank()) return null
    return first.first to second.first
  }

  private fun extractBracedGroup(s: String, start: Int): Pair<String, Int>? {
    var i = start
    while (i < s.length && s[i].isWhitespace()) i++
    if (i >= s.length || s[i] != '{') return null
    var depth = 0
    val contentStart = i + 1
    while (i < s.length) {
      if (s[i] == '{') depth++
      else if (s[i] == '}') {
        depth--
        if (depth == 0) return s.substring(contentStart, i) to (i + 1)
      }
      i++
    }
    return null
  }

  private fun findRoots(expr: String): List<Double> {
    val roots = mutableListOf<Double>()
    val n = 800
    val minX = -20.0
    val maxX = 20.0
    val step = (maxX - minX) / n
    var prevX = minX
    var prevY = LocalMathEngine.evalAt(expr, prevX)
    for (i in 1..n) {
      val curX = minX + i * step
      val curY = LocalMathEngine.evalAt(expr, curX)
      if (prevY.isFinite() && abs(prevY) < 1e-9) {
        addUniqueRoot(roots, prevX)
      } else if (prevY.isFinite() && curY.isFinite() && prevY * curY < 0.0) {
        var lo = prevX
        var hi = curX
        var yLo = prevY
        repeat(60) {
          val mid = 0.5 * (lo + hi)
          val yMid = LocalMathEngine.evalAt(expr, mid)
          if (!yMid.isFinite()) return@repeat
          if (yLo * yMid <= 0.0) hi = mid else { lo = mid; yLo = yMid }
        }
        val cand = 0.5 * (lo + hi)
        val yCand = LocalMathEngine.evalAt(expr, cand)
        // Reject sign changes caused by poles (e.g. 1/x at 0)
        if (yCand.isFinite() && abs(yCand) < 1e-6 * (1 + abs(prevY) + abs(curY))) addUniqueRoot(roots, cand)
      }
      prevX = curX
      prevY = curY
    }
    return roots
  }

  private fun addUniqueRoot(roots: MutableList<Double>, r: Double) {
    var clean = if (abs(r) < 1e-9) 0.0 else r
    val rounded = Math.rint(clean * 1e9) / 1e9
    if (abs(rounded - clean) < 1e-9) clean = rounded
    if (roots.none { abs(it - clean) < 1e-6 }) roots.add(clean)
  }

  /** Gauss–Legendre quadrature with x = a + (b - a)u², which tames a singularity at the lower bound. */
  private fun integrateSubst(expr: String, a: Double, b: Double): Double {
    if (!a.isFinite() || !b.isFinite()) return Double.NaN
    val span = b - a
    val nodes = doubleArrayOf(
      -0.9739065285, -0.8650633667, -0.6794095683, -0.4333953941, -0.1488743390,
      0.1488743390, 0.4333953941, 0.6794095683, 0.8650633667, 0.9739065285
    )
    val weights = doubleArrayOf(
      0.0666713443, 0.1494513492, 0.2190863625, 0.2692667193, 0.2955242247,
      0.2955242247, 0.2692667193, 0.2190863625, 0.1494513492, 0.0666713443
    )
    val panels = 30
    var sum = 0.0
    for (p in 0 until panels) {
      val u0 = p.toDouble() / panels
      val u1 = (p + 1).toDouble() / panels
      val uMid = 0.5 * (u0 + u1)
      val uHalf = 0.5 * (u1 - u0)
      for (k in nodes.indices) {
        val u = uMid + uHalf * nodes[k]
        val x = a + span * u * u
        val fx = LocalMathEngine.evalAt(expr, x)
        if (fx.isFinite()) sum += weights[k] * fx * 2.0 * span * u * uHalf
      }
    }
    return sum
  }
}
