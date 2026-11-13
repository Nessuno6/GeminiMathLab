package com.deang.geminimath

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.acos

/**
 * Solves systems of equations (2×2 and 3×3 linear systems, plus 2×2 nonlinear substitution),
 * matrix algebra (determinants, inverses, transposes, eigenvalues, matrix addition/multiplication),
 * and first-order ordinary differential equations (direct, separable, and linear ODEs).
 */
object LinearAlgebraAndSystems {
  private fun fmt(v: Double): String = MathFormat.num(if (abs(v) < 1e-11) 0.0 else v)

  private val MATRIX_BLOCK = Regex(
    """(\\det\s*)?\\begin\s*\{\s*(pmatrix|bmatrix|vmatrix|Vmatrix|Bmatrix|matrix)\s*\}(.+?)\\end\s*\{\s*\2\s*\}(\s*\^\s*(?:\{\s*(-1|T|\\top|\\mathsf\{T\}|[23])\s*\}|(-1|T|[23])))?""",
    setOf(RegexOption.DOT_MATCHES_ALL)
  )

  private val ABS_MATRIX_BLOCK = Regex(
    """\\left\|\s*\\begin\s*\{\s*(?:matrix|pmatrix|bmatrix)\s*\}(.+?)\\end\s*\{\s*(?:matrix|pmatrix|bmatrix)\s*\}\s*\\right\|""",
    setOf(RegexOption.DOT_MATCHES_ALL)
  )

  /**
   * Attempts to solve [unwrappedLatex] as a matrix problem, a system of equations, or a first-order ODE.
   * Returns null when the input is a regular single-variable expression/equation handled by [SymbolicStepEngine].
   */
  fun trySolve(unwrappedLatex: String, t0: Long): SymbolicStepEngine.SymbolicSolution? {
    val trimmed = unwrappedLatex.trim().replace(Regex("""=\s*\??\s*$"""), "").trim()
    if (trimmed.isEmpty()) return null

    // 1. Matrix / Determinant expressions
    trySolveMatrix(trimmed, t0)?.let { return it }

    // 2. Multi-equation Systems (2×2 or 3×3)
    trySolveSystem(trimmed, t0)?.let { return it }

    // 3. First-Order Ordinary Differential Equations (y', dy/dx)
    trySolveOde(trimmed, t0)?.let { return it }

    return null
  }

  // =============================================================================================
  // 1. MATRICES & DETERMINANTS
  // =============================================================================================

  private data class ParsedMatrix(
    val env: String,
    val detPrefix: Boolean,
    val rows: List<List<Double>>,
    val power: String?,
    val fullMatchRange: IntRange
  )

  private fun parseMatrixCells(body: String): List<List<Double>>? {
    val rawRows = body.split("\\\\").map { it.trim() }.filter { it.isNotEmpty() }
    if (rawRows.isEmpty()) return null
    val table = ArrayList<List<Double>>(rawRows.size)
    for (r in rawRows) {
      // A single-equation row wrapped in \begin{matrix} (e.g. "x^2 - 5x + 6 = 0") is not a matrix
      if (r.contains('=') || r.contains('<') || r.contains('>') || r.contains("\\int") || r.contains("\\lim")) return null
      val cells = if (r.contains('&')) {
        r.split('&').map { it.trim() }
      } else {
        // OCR sometimes drops '&' and separates numbers by spaces inside pmatrix/bmatrix
        r.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
      }
      if (cells.isEmpty()) continue
      val vals = cells.map { cell ->
        val expr = LocalMathEngine.latexToMathExpr(cell)
        val ex = LocalMathEngine.parse(expr) ?: return null
        if (ex.hasX()) return null
        val v = ex.eval(0.0, MathSettings.angleDegrees)
        if (!v.isFinite()) return null
        v
      }
      table.add(vals)
    }
    if (table.isEmpty()) return null
    val cols = table[0].size
    if (table.any { it.size != cols }) return null
    // A 1×1 "\begin{matrix}" without pmatrix/bmatrix/vmatrix is just layout wrapper
    if (table.size == 1 && cols == 1) return null
    return table
  }

  private fun formatMatrix(m: List<List<Double>>): String =
    "[" + m.joinToString("; ") { row -> row.joinToString(", ") { fmt(it) } } + "]"

  private fun det2(m: List<List<Double>>): Double = m[0][0] * m[1][1] - m[0][1] * m[1][0]

  private fun det3(m: List<List<Double>>): Double =
    m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
      m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
      m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

  private fun transpose(m: List<List<Double>>): List<List<Double>> =
    List(m[0].size) { c -> List(m.size) { r -> m[r][c] } }

  private fun multiply(a: List<List<Double>>, b: List<List<Double>>): List<List<Double>>? {
    if (a[0].size != b.size) return null
    val kMax = a[0].size
    return List(a.size) { r ->
      List(b[0].size) { c ->
        var s = 0.0
        for (k in 0 until kMax) s += a[r][k] * b[k][c]
        s
      }
    }
  }

  private fun inverse2(m: List<List<Double>>, d: Double): List<List<Double>> = listOf(
    listOf(m[1][1] / d, -m[0][1] / d),
    listOf(-m[1][0] / d, m[0][0] / d)
  )

  private fun inverse3(m: List<List<Double>>, d: Double): List<List<Double>> {
    fun cof(r: Int, c: Int): Double {
      val r0 = if (r == 0) 1 else 0
      val r1 = if (r == 2) 1 else 2
      val c0 = if (c == 0) 1 else 0
      val c1 = if (c == 2) 1 else 2
      val minor = m[r0][c0] * m[r1][c1] - m[r0][c1] * m[r1][c0]
      return if ((r + c) % 2 == 0) minor else -minor
    }
    return List(3) { r -> List(3) { c -> cof(c, r) / d } }
  }

  private fun trySolveMatrix(s: String, t0: Long): SymbolicStepEngine.SymbolicSolution? {
    // Check \left| \begin{matrix} ... \end{matrix} \right| first
    ABS_MATRIX_BLOCK.matchEntire(s)?.let { m ->
      val cells = parseMatrixCells(m.groupValues[1]) ?: return null
      return solveSingleMatrix(cells, isDetOnly = true, power = null, t0 = t0)
    }

    val matches = MATRIX_BLOCK.findAll(s).mapNotNull { m ->
      val env = m.groupValues[2]
      val detPrefix = m.groupValues[1].isNotBlank() || env.equals("vmatrix", ignoreCase = true)
      val cells = parseMatrixCells(m.groupValues[3]) ?: return@mapNotNull null
      // Plain \begin{matrix} with 1 column and no det/power is only a layout wrapper unless >1 col
      if (env == "matrix" && !detPrefix && cells[0].size < 2) return@mapNotNull null
      val pow = m.groupValues[5].ifEmpty { m.groupValues[6] }.takeIf { it.isNotEmpty() }
      ParsedMatrix(env, detPrefix, cells, pow, m.range)
    }.toList()

    if (matches.isEmpty()) return null

    if (matches.size == 1 && matches[0].fullMatchRange.first == 0 && matches[0].fullMatchRange.last == s.length - 1) {
      val pm = matches[0]
      return solveSingleMatrix(pm.rows, pm.detPrefix, pm.power, t0)
    }

    if (matches.size == 2 && matches[0].fullMatchRange.first == 0 && matches[1].fullMatchRange.last == s.length - 1) {
      val between = s.substring(matches[0].fullMatchRange.last + 1, matches[1].fullMatchRange.first).trim()
      val a = matches[0].rows
      val b = matches[1].rows
      return solveBinaryMatrix(a, b, between, t0)
    }

    return null
  }

  private fun solveSingleMatrix(
    m: List<List<Double>>,
    isDetOnly: Boolean,
    power: String?,
    t0: Long
  ): SymbolicStepEngine.SymbolicSolution? {
    val nRows = m.size
    val nCols = m[0].size
    val matStr = formatMatrix(m)

    // Transpose
    if (power != null && (power == "T" || power == "\\top" || power.contains("T"))) {
      val tr = transpose(m)
      val trStr = formatMatrix(tr)
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "Matrix transpose",
        normalizedMath = "${matStr}ᵀ",
        steps = listOf(
          "Swap rows and columns: (Aᵀ)ᵢⱼ = Aⱼᵢ",
          "Aᵀ = $trStr"
        ),
        finalAnswer = trStr,
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    }

    if (nRows != nCols || nRows !in 2..3) {
      val tr = transpose(m)
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "Matrix (${nRows}×$nCols)",
        normalizedMath = matStr,
        steps = listOf(
          "Dimensions: $nRows rows × $nCols columns",
          "Transpose Aᵀ = ${formatMatrix(tr)}"
        ),
        finalAnswer = "Aᵀ = ${formatMatrix(tr)}",
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    }

    val det = if (nRows == 2) det2(m) else det3(m)
    val detStep = if (nRows == 2) {
      "det(A) = (${fmt(m[0][0])})·(${fmt(m[1][1])}) - (${fmt(m[0][1])})·(${fmt(m[1][0])}) = ${fmt(det)}"
    } else {
      val c0 = m[1][1] * m[2][2] - m[1][2] * m[2][1]
      val c1 = m[1][0] * m[2][2] - m[1][2] * m[2][0]
      val c2 = m[1][0] * m[2][1] - m[1][1] * m[2][0]
      "Expand along the first row: det(A) = ${fmt(m[0][0])}·(${fmt(c0)}) - ${fmt(m[0][1])}·(${fmt(c1)}) + ${fmt(m[0][2])}·(${fmt(c2)}) = ${fmt(det)}"
    }

    if (isDetOnly) {
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "Determinant (${nRows}×$nRows)",
        normalizedMath = "det $matStr",
        steps = listOf(detStep),
        finalAnswer = fmt(det),
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    }

    if (power == "-1") {
      if (abs(det) < 1e-11) {
        return SymbolicStepEngine.SymbolicSolution(
          problemType = "Matrix inverse",
          normalizedMath = "${matStr}⁻¹",
          steps = listOf(detStep, "Since det(A) = 0, the matrix is singular and has no inverse"),
          finalAnswer = "Singular matrix (not invertible)",
          plottableExpr = null,
          elapsedMs = ms(t0)
        )
      }
      val inv = if (nRows == 2) inverse2(m, det) else inverse3(m, det)
      val invStr = formatMatrix(inv)
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "Matrix inverse",
        normalizedMath = "${matStr}⁻¹",
        steps = listOf(
          detStep,
          if (nRows == 2) "Use A⁻¹ = (1/det(A)) · [d, -b; -c, a]" else "Compute the adjugate matrix adj(A) from the transpose of the cofactors and divide by det(A)",
          "A⁻¹ = $invStr"
        ),
        finalAnswer = invStr,
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    }

    if (power == "2" || power == "3") {
      val a2 = multiply(m, m) ?: return null
      val res = if (power == "2") a2 else multiply(a2, m) ?: return null
      val resStr = formatMatrix(res)
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "Matrix power",
        normalizedMath = "$matStr^$power",
        steps = listOf(
          "Multiply row by column: A² = ${formatMatrix(a2)}",
          if (power == "3") "Multiply by A again: A³ = $resStr" else "Result: $resStr"
        ),
        finalAnswer = resStr,
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    }

    // Full square matrix study: det, trace, eigenvalues, inverse
    val trace = (0 until nRows).sumOf { m[it][it] }
    val steps = ArrayList<String>()
    steps += detStep
    steps += "Trace: tr(A) = " + (0 until nRows).joinToString(" + ") { fmt(m[it][it]) } + " = ${fmt(trace)}"

    if (nRows == 2) {
      val disc = trace * trace - 4.0 * det
      val eigStr = when {
        disc > 1e-11 -> {
          val l1 = (trace - sqrt(disc)) / 2.0
          val l2 = (trace + sqrt(disc)) / 2.0
          "λ₁ = ${fmt(l1)}, λ₂ = ${fmt(l2)}"
        }
        abs(disc) <= 1e-11 -> "λ = ${fmt(trace / 2.0)}"
        else -> {
          val re = trace / 2.0
          val im = sqrt(-disc) / 2.0
          "λ = ${fmt(re)} ± ${fmt(im)}i"
        }
      }
      steps += "Characteristic equation: λ² - (${fmt(trace)})λ + (${fmt(det)}) = 0 ⇒ $eigStr"
      if (abs(det) > 1e-11) {
        steps += "Inverse: A⁻¹ = ${formatMatrix(inverse2(m, det))}"
      } else {
        steps += "Matrix is singular (det = 0), so A⁻¹ does not exist"
      }
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "Matrix (2×2)",
        normalizedMath = "A = $matStr",
        steps = steps,
        finalAnswer = "det = ${fmt(det)} · $eigStr",
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    } else {
      if (abs(det) > 1e-11) {
        steps += "Inverse: A⁻¹ = ${formatMatrix(inverse3(m, det))}"
      } else {
        steps += "Matrix is singular (det = 0), so A⁻¹ does not exist"
      }
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "Matrix (3×3)",
        normalizedMath = "A = $matStr",
        steps = steps,
        finalAnswer = "det = ${fmt(det)} · tr = ${fmt(trace)}",
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    }
  }

  private fun solveBinaryMatrix(
    a: List<List<Double>>,
    b: List<List<Double>>,
    opRaw: String,
    t0: Long
  ): SymbolicStepEngine.SymbolicSolution? {
    val aStr = formatMatrix(a)
    val bStr = formatMatrix(b)
    val op = opRaw.replace("\\cdot", "*").replace("\\times", "*").replace("·", "*").trim()
    when (op) {
      "+", "-" -> {
        if (a.size != b.size || a[0].size != b[0].size) {
          return SymbolicStepEngine.SymbolicSolution(
            problemType = "Matrix $op",
            normalizedMath = "$aStr $op $bStr",
            steps = listOf("Matrices have different sizes (${a.size}×${a[0].size} and ${b.size}×${b[0].size}) and cannot be added or subtracted"),
            finalAnswer = "Dimension mismatch",
            plottableExpr = null,
            elapsedMs = ms(t0)
          )
        }
        val sgn = if (op == "+") 1.0 else -1.0
        val res = List(a.size) { r -> List(a[0].size) { c -> a[r][c] + sgn * b[r][c] } }
        val resStr = formatMatrix(res)
        return SymbolicStepEngine.SymbolicSolution(
          problemType = if (op == "+") "Matrix addition" else "Matrix subtraction",
          normalizedMath = "$aStr $op $bStr",
          steps = listOf(
            "Combine corresponding entries: Cᵢⱼ = Aᵢⱼ $op Bᵢⱼ",
            "Result: $resStr"
          ),
          finalAnswer = resStr,
          plottableExpr = null,
          elapsedMs = ms(t0)
        )
      }
      "", "*" -> {
        val prod = multiply(a, b) ?: return SymbolicStepEngine.SymbolicSolution(
          problemType = "Matrix multiplication",
          normalizedMath = "$aStr · $bStr",
          steps = listOf("Columns of A (${a[0].size}) must match rows of B (${b.size})"),
          finalAnswer = "Dimension mismatch",
          plottableExpr = null,
          elapsedMs = ms(t0)
        )
        val prodStr = formatMatrix(prod)
        val sampleDot = (0 until a[0].size).joinToString(" + ") { k -> "(${fmt(a[0][k])})·(${fmt(b[k][0])})" }
        return SymbolicStepEngine.SymbolicSolution(
          problemType = "Matrix multiplication",
          normalizedMath = "$aStr · $bStr",
          steps = listOf(
            "Multiply rows of A by columns of B: C₁₁ = $sampleDot = ${fmt(prod[0][0])}",
            "Product: $prodStr"
          ),
          finalAnswer = prodStr,
          plottableExpr = null,
          elapsedMs = ms(t0)
        )
      }
      else -> return null
    }
  }

  // =============================================================================================
  // 2. SYSTEMS OF EQUATIONS (2×2 AND 3×3)
  // =============================================================================================

  private val SYSTEM_LAYOUT_ENV = Regex("""\\(?:begin|end)\s*\{\s*(?:cases|aligned|align\*?|array|matrix|gathered|gather\*?|split)\s*\}(?:\s*\{[lcr|]*\})?""")

  private fun extractSystemEquations(raw: String): List<String>? {
    val cleaned = raw.replace(SYSTEM_LAYOUT_ENV, " ")
      .replace("\\left\\{", " ")
      .replace("\\right.", " ")
      .trim()
    val rows = when {
      cleaned.contains("\\\\") -> cleaned.split("\\\\")
      cleaned.contains(';') -> cleaned.split(';')
      cleaned.contains('\n') -> cleaned.split('\n')
      // Comma-separated equations when both sides have '=' and both x and y appear
      cleaned.count { it == '=' } >= 2 && cleaned.contains(',') &&
        cleaned.contains(Regex("""(?<![a-zA-Z\\])[yY](?![a-zA-Z])""")) -> cleaned.split(',')
      else -> return null
    }.map { it.replace("&", " ").replace(Regex("""^\s*\{|\}\s*$"""), "").trim() }
      .filter { it.isNotEmpty() }

    if (rows.size !in 2..3) return null
    if (rows.any { !it.contains('=') }) return null
    return rows
  }

  /** Evaluates a plain expression containing x, y, z by substituting numeric literals for y and z. */
  private fun evalXYZ(plainExpr: String, xVal: Double, yVal: Double, zVal: Double = 0.0): Double {
    val sub = plainExpr
      .replace(Regex("""(?<![a-zA-Z])y(?![a-zA-Z])"""), "(${MathFormat.plainNumber(yVal)})")
      .replace(Regex("""(?<![a-zA-Z])z(?![a-zA-Z])"""), "(${MathFormat.plainNumber(zVal)})")
    val ex = LocalMathEngine.parse(sub) ?: return Double.NaN
    return ex.eval(xVal, MathSettings.angleDegrees)
  }

  private fun toPlainXYZ(latex: String): String {
    // Wrap y and z in parentheses so implicit multiplication (2y -> 2(y), xy -> x(y)) never glues digits/letters
    val withTokens = latex
      .replace(Regex("""(?<![a-zA-Z\\])y(?![a-zA-Z])"""), "(y)")
      .replace(Regex("""(?<![a-zA-Z\\])z(?![a-zA-Z])"""), "(z)")
    return LocalMathEngine.latexToMathExpr(withTokens)
  }

  private data class LinEq2(val a: Double, val b: Double, val c: Double) // a*x + b*y = c
  private data class LinEq3(val a: Double, val b: Double, val c: Double, val d: Double) // a*x + b*y + c*z = d

  private fun parseLinear2(eqLatex: String): LinEq2? {
    val parts = eqLatex.split('=')
    if (parts.size != 2) return null
    val lhs = toPlainXYZ(parts[0])
    val rhs = toPlainXYZ(parts[1])
    val diff = "($lhs)-($rhs)"
    val f00 = evalXYZ(diff, 0.0, 0.0)
    val f10 = evalXYZ(diff, 1.0, 0.0)
    val f01 = evalXYZ(diff, 0.0, 1.0)
    if (!f00.isFinite() || !f10.isFinite() || !f01.isFinite()) return null
    val a = f10 - f00
    val b = f01 - f00
    val c = -f00
    // Verify linearity at two non-collinear test points
    for ((tx, ty) in listOf(1.0 to 1.0, 2.3 to -1.7, -1.5 to 3.1)) {
      val actual = evalXYZ(diff, tx, ty)
      val expected = a * tx + b * ty - c
      if (!actual.isFinite() || abs(actual - expected) > 1e-7 * (1.0 + abs(expected))) return null
    }
    if (abs(a) < 1e-11 && abs(b) < 1e-11) return null
    return LinEq2(a, b, c)
  }

  private fun parseLinear3(eqLatex: String): LinEq3? {
    val parts = eqLatex.split('=')
    if (parts.size != 2) return null
    val lhs = toPlainXYZ(parts[0])
    val rhs = toPlainXYZ(parts[1])
    val diff = "($lhs)-($rhs)"
    val f0 = evalXYZ(diff, 0.0, 0.0, 0.0)
    val fx = evalXYZ(diff, 1.0, 0.0, 0.0)
    val fy = evalXYZ(diff, 0.0, 1.0, 0.0)
    val fz = evalXYZ(diff, 0.0, 0.0, 1.0)
    if (!f0.isFinite() || !fx.isFinite() || !fy.isFinite() || !fz.isFinite()) return null
    val a = fx - f0
    val b = fy - f0
    val c = fz - f0
    val d = -f0
    for ((tx, ty, tz) in listOf(Triple(1.0, 1.0, 1.0), Triple(2.1, -1.3, 0.7))) {
      val actual = evalXYZ(diff, tx, ty, tz)
      val expected = a * tx + b * ty + c * tz - d
      if (!actual.isFinite() || abs(actual - expected) > 1e-7 * (1.0 + abs(expected))) return null
    }
    if (abs(a) < 1e-11 && abs(b) < 1e-11 && abs(c) < 1e-11) return null
    return LinEq3(a, b, c, d)
  }

  private fun formatLin2(e: LinEq2): String {
    val terms = ArrayList<String>()
    if (abs(e.a) > 1e-11) terms += MathFormat.monomial(e.a, 1)
    if (abs(e.b) > 1e-11) terms += MathFormat.monomial(e.b, 1).replace("x", "y")
    return "${MathFormat.joinSigned(terms)} = ${fmt(e.c)}"
  }

  private fun formatLin3(e: LinEq3): String {
    val terms = ArrayList<String>()
    if (abs(e.a) > 1e-11) terms += MathFormat.monomial(e.a, 1)
    if (abs(e.b) > 1e-11) terms += MathFormat.monomial(e.b, 1).replace("x", "y")
    if (abs(e.c) > 1e-11) terms += MathFormat.monomial(e.c, 1).replace("x", "z")
    return "${MathFormat.joinSigned(terms)} = ${fmt(e.d)}"
  }

  private fun trySolveSystem(s: String, t0: Long): SymbolicStepEngine.SymbolicSolution? {
    val eqs = extractSystemEquations(s) ?: return null
    val hasZ = eqs.any { it.contains(Regex("""(?<![a-zA-Z\\])z(?![a-zA-Z])""")) }

    if (eqs.size == 2 && !hasZ) {
      val l1 = parseLinear2(eqs[0])
      val l2 = parseLinear2(eqs[1])
      if (l1 != null && l2 != null) {
        return solveLinearSystem2(l1, l2, t0)
      }
      return solveNonlinearSystem2(eqs[0], eqs[1], t0)
    }

    if (eqs.size == 3) {
      val l1 = parseLinear3(eqs[0]) ?: return null
      val l2 = parseLinear3(eqs[1]) ?: return null
      val l3 = parseLinear3(eqs[2]) ?: return null
      return solveLinearSystem3(l1, l2, l3, t0)
    }

    return null
  }

  private fun solveLinearSystem2(e1: LinEq2, e2: LinEq2, t0: Long): SymbolicStepEngine.SymbolicSolution {
    val shown = "{ ${formatLin2(e1)} ; ${formatLin2(e2)} }"
    val d = e1.a * e2.b - e2.a * e1.b
    val dx = e1.c * e2.b - e2.c * e1.b
    val dy = e1.a * e2.c - e2.a * e1.c
    val plotExpr = if (abs(e1.b) > 1e-9 && abs(e2.b) > 1e-9) {
      val f1 = "(${MathFormat.plainNumber(-e1.a / e1.b)})*x+(${MathFormat.plainNumber(e1.c / e1.b)})"
      val f2 = "(${MathFormat.plainNumber(-e2.a / e2.b)})*x+(${MathFormat.plainNumber(e2.c / e2.b)})"
      "$f1;$f2"
    } else null

    if (abs(d) < 1e-11) {
      val infinite = abs(dx) < 1e-11 && abs(dy) < 1e-11
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "System of linear equations (2×2)",
        normalizedMath = shown,
        steps = listOf(
          "Write in standard form: (1) ${formatLin2(e1)}, (2) ${formatLin2(e2)}",
          "Determinant D = (${fmt(e1.a)})·(${fmt(e2.b)}) - (${fmt(e2.a)})·(${fmt(e1.b)}) = 0",
          if (infinite) "Both equations describe the same line, so there are infinitely many solutions"
          else "The two lines are parallel and distinct (Dₓ = ${fmt(dx)}, Dᵧ = ${fmt(dy)}), so there is no solution"
        ),
        finalAnswer = if (infinite) "Infinitely many solutions" else "No solution",
        plottableExpr = plotExpr,
        elapsedMs = ms(t0)
      )
    }

    val x = dx / d
    val y = dy / d
    return SymbolicStepEngine.SymbolicSolution(
      problemType = "System of linear equations (2×2)",
      normalizedMath = shown,
      steps = listOf(
        "Standard form: (1) ${formatLin2(e1)},  (2) ${formatLin2(e2)}",
        "Determinant: D = (${fmt(e1.a)})·(${fmt(e2.b)}) - (${fmt(e2.a)})·(${fmt(e1.b)}) = ${fmt(d)}",
        "Cramer's rule: Dₓ = (${fmt(e1.c)})·(${fmt(e2.b)}) - (${fmt(e2.c)})·(${fmt(e1.b)}) = ${fmt(dx)}  ⇒  x = Dₓ/D = ${fmt(x)}",
        "Dᵧ = (${fmt(e1.a)})·(${fmt(e2.c)}) - (${fmt(e2.a)})·(${fmt(e1.c)}) = ${fmt(dy)}  ⇒  y = Dᵧ/D = ${fmt(y)}"
      ),
      finalAnswer = "x = ${fmt(x)}, y = ${fmt(y)}",
      plottableExpr = plotExpr,
      elapsedMs = ms(t0)
    )
  }

  private fun solveLinearSystem3(e1: LinEq3, e2: LinEq3, e3: LinEq3, t0: Long): SymbolicStepEngine.SymbolicSolution {
    val shown = "{ ${formatLin3(e1)} ; ${formatLin3(e2)} ; ${formatLin3(e3)} }"
    val m = listOf(
      listOf(e1.a, e1.b, e1.c),
      listOf(e2.a, e2.b, e2.c),
      listOf(e3.a, e3.b, e3.c)
    )
    val d = det3(m)
    val dx = det3(listOf(listOf(e1.d, e1.b, e1.c), listOf(e2.d, e2.b, e2.c), listOf(e3.d, e3.b, e3.c)))
    val dy = det3(listOf(listOf(e1.a, e1.d, e1.c), listOf(e2.a, e2.d, e2.c), listOf(e3.a, e3.d, e3.c)))
    val dz = det3(listOf(listOf(e1.a, e1.b, e1.d), listOf(e2.a, e2.b, e2.d), listOf(e3.a, e3.b, e3.d)))

    if (abs(d) < 1e-11) {
      val infinite = abs(dx) < 1e-11 && abs(dy) < 1e-11 && abs(dz) < 1e-11
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "System of linear equations (3×3)",
        normalizedMath = shown,
        steps = listOf(
          "Standard form: ${formatLin3(e1)}, ${formatLin3(e2)}, ${formatLin3(e3)}",
          "Coefficient determinant D = 0",
          if (infinite) "The equations are dependent" else "The system is inconsistent"
        ),
        finalAnswer = if (infinite) "Infinitely many solutions" else "No solution",
        plottableExpr = null,
        elapsedMs = ms(t0)
      )
    }

    val x = dx / d
    val y = dy / d
    val z = dz / d
    return SymbolicStepEngine.SymbolicSolution(
      problemType = "System of linear equations (3×3)",
      normalizedMath = shown,
      steps = listOf(
        "Standard form: (1) ${formatLin3(e1)}, (2) ${formatLin3(e2)}, (3) ${formatLin3(e3)}",
        "Main determinant: D = ${fmt(d)}",
        "Cramer's determinants: Dₓ = ${fmt(dx)}, Dᵧ = ${fmt(dy)}, D_z = ${fmt(dz)}",
        "x = Dₓ/D = ${fmt(x)},  y = Dᵧ/D = ${fmt(y)},  z = D_z/D = ${fmt(z)}"
      ),
      finalAnswer = "x = ${fmt(x)}, y = ${fmt(y)}, z = ${fmt(z)}",
      plottableExpr = null,
      elapsedMs = ms(t0)
    )
  }

  /**
   * Solves a 2×2 nonlinear system where at least one equation expresses y = f(x) (or is linear in y).
   */
  private fun solveNonlinearSystem2(eq1: String, eq2: String, t0: Long): SymbolicStepEngine.SymbolicSolution? {
    fun isolateY(eq: String): String? {
      val parts = eq.split('=')
      if (parts.size != 2) return null
      val l = parts[0].trim()
      val r = parts[1].trim()
      if (l == "y" && !r.contains(Regex("""(?<![a-zA-Z\\])y(?![a-zA-Z])"""))) {
        val expr = LocalMathEngine.latexToMathExpr(r)
        if (LocalMathEngine.parse(expr) != null) return expr
      }
      if (r == "y" && !l.contains(Regex("""(?<![a-zA-Z\\])y(?![a-zA-Z])"""))) {
        val expr = LocalMathEngine.latexToMathExpr(l)
        if (LocalMathEngine.parse(expr) != null) return expr
      }
      val lin = parseLinear2(eq)
      if (lin != null && abs(lin.b) > 1e-11) {
        return "(${MathFormat.plainNumber(-lin.a / lin.b)})*x+(${MathFormat.plainNumber(lin.c / lin.b)})"
      }
      return null
    }

    val yFrom1 = isolateY(eq1)
    val yFrom2 = isolateY(eq2)
    val (yExpr, otherEq) = when {
      yFrom1 != null -> yFrom1 to eq2
      yFrom2 != null -> yFrom2 to eq1
      else -> return null
    }
    val otherParts = otherEq.split('=')
    if (otherParts.size != 2) return null
    val subLhs = toPlainXYZ(otherParts[0]).replace(Regex("""(?<![a-zA-Z])y(?![a-zA-Z])"""), "($yExpr)")
    val subRhs = toPlainXYZ(otherParts[1]).replace(Regex("""(?<![a-zA-Z])y(?![a-zA-Z])"""), "($yExpr)")
    val subSingle = SymbolicStepEngine.solveFromLatex("$subLhs = $subRhs")
    if (subSingle.isUnreadable) return null

    // Extract root x-values by scanning [-20, 20] or quadratic/linear exact roots
    val diffExpr = "($subLhs)-($subRhs)"
    val diffEx = LocalMathEngine.parse(diffExpr) ?: return null
    val xs = findSingleVarRoots(diffEx)
    val yEx = LocalMathEngine.parse(yExpr) ?: return null
    val yPretty = yEx.pretty()
    val otherYExpr = if (yFrom1 != null) yFrom2 else yFrom1
    val plotExpr = if (otherYExpr != null) "$yExpr;$otherYExpr" else yExpr

    val pairs = xs.mapNotNull { x ->
      val y = yEx.eval(x, MathSettings.angleDegrees)
      if (y.isFinite()) x to y else null
    }
    val answer = if (pairs.isEmpty()) "No real solutions"
    else pairs.joinToString(", ") { (x, y) -> "(${fmt(x)}, ${fmt(y)})" }

    return SymbolicStepEngine.SymbolicSolution(
      problemType = "Nonlinear system (2×2)",
      normalizedMath = "{ y = $yPretty ; ${otherEq.trim()} }",
      steps = listOf(
        "Express y in terms of x: y = $yPretty",
        "Substitute into the other equation: ${diffEx.pretty()} = 0",
        if (pairs.isEmpty()) "No real roots found for x"
        else "Solve for x and substitute back into y = $yPretty: $answer"
      ),
      finalAnswer = answer,
      plottableExpr = plotExpr,
      elapsedMs = ms(t0)
    )
  }

  private fun findSingleVarRoots(ex: Ex): List<Double> {
    val coeffs = PolyFit.fit { ex.eval(it) }
    if (coeffs != null) {
      val deg = PolyFit.degree(coeffs)
      if (deg == 1) return listOf(-coeffs[0] / coeffs[1])
      if (deg == 2) {
        val c = coeffs[0]; val b = coeffs[1]; val a = coeffs[2]
        val d = b * b - 4 * a * c
        if (d > 1e-12) return listOf((-b - sqrt(d)) / (2 * a), (-b + sqrt(d)) / (2 * a)).sorted()
        if (abs(d) <= 1e-12) return listOf(-b / (2 * a))
        return emptyList()
      }
    }
    val roots = ArrayList<Double>()
    val n = 800
    val minX = -20.0
    val maxX = 20.0
    var px = minX
    var py = ex.eval(px)
    for (i in 1..n) {
      val cx = minX + (maxX - minX) * i / n
      val cy = ex.eval(cx)
      if (py.isFinite() && abs(py) < 1e-9) {
        if (roots.none { abs(it - px) < 1e-5 }) roots += px
      } else if (py.isFinite() && cy.isFinite() && py * cy < 0) {
        var lo = px; var hi = cx; var yLo = py
        repeat(55) {
          val mid = 0.5 * (lo + hi)
          val ym = ex.eval(mid)
          if (yLo * ym <= 0) hi = mid else { lo = mid; yLo = ym }
        }
        val r = 0.5 * (lo + hi)
        if (roots.none { abs(it - r) < 1e-5 }) roots += r
      }
      px = cx; py = cy
    }
    return roots
  }

  // =============================================================================================
  // 3. FIRST-ORDER ORDINARY DIFFERENTIAL EQUATIONS (ODEs)
  // =============================================================================================

  private val Y_PRIME = Regex("""(?:\\frac\s*\{\s*(?:\\mathrm\s*\{\s*d\s*\}|d)\s*y\s*\}\s*\{\s*(?:\\mathrm\s*\{\s*d\s*\}|d)\s*x\s*\}|y\s*'\s*(?:\(\s*x\s*\))?|y\s*\^\s*\{\s*\\prime\s*\})""")

  private fun trySolveOde(s: String, t0: Long): SymbolicStepEngine.SymbolicSolution? {
    if (!s.contains('=') || !Y_PRIME.containsMatchIn(s)) return null
    // Replace y' or dy/dx with token "z" so we can parse lhs and rhs in (x, y, y'=z)
    val replaced = s.replace(Y_PRIME, " z ")
    val parts = replaced.split('=')
    if (parts.size != 2) return null
    val lhs = toPlainXYZ(parts[0])
    val rhs = toPlainXYZ(parts[1])
    val diff = "($lhs)-($rhs)" // F(x, y, y') = 0

    // Check that F is linear in z = y' with coefficient 1 (or constant): F(x, y, z) = a*z + G(x, y)
    val fZ0 = evalXYZ(diff, 1.3, 0.7, 0.0)
    val fZ1 = evalXYZ(diff, 1.3, 0.7, 1.0)
    val aZ = fZ1 - fZ0
    if (!aZ.isFinite() || abs(aZ) < 1e-9) return null

    // So y' = rhsOde(x, y) = -F(x, y, 0) / aZ
    fun rhsAt(x: Double, y: Double): Double = -evalXYZ(diff, x, y, 0.0) / aZ

    // Case A: Direct integration y' = f(x) (independent of y)
    val indepOfY = listOf(0.5, 1.2, 2.3).all { x ->
      val r0 = rhsAt(x, 0.0)
      val r1 = rhsAt(x, 1.0)
      val r2 = rhsAt(x, -2.0)
      r0.isFinite() && r1.isFinite() && r2.isFinite() && abs(r1 - r0) < 1e-9 && abs(r2 - r0) < 1e-9
    }
    if (indepOfY) {
      val fxPlain = "(-($diff))/(${MathFormat.plainNumber(aZ)})"
        .replace(Regex("""(?<![a-zA-Z])z(?![a-zA-Z])"""), "(0)")
        .replace(Regex("""(?<![a-zA-Z])y(?![a-zA-Z])"""), "(0)")
      val fEx = LocalMathEngine.parse(fxPlain)?.let { Deriv.simplify(it) } ?: return null
      val prim = Integrator.integrate(fEx) ?: return null
      val ans = if (prim.pretty == "0") "y(x) = C" else "y(x) = ${prim.pretty} + C"
      return SymbolicStepEngine.SymbolicSolution(
        problemType = "Differential equation (1st order)",
        normalizedMath = "y' = ${fEx.pretty()}",
        steps = listOf(
          "Direct integration: dy/dx = ${fEx.pretty()}  ⇒  y(x) = ∫ ${fEx.pretty()} dx",
          *prim.steps.toTypedArray(),
          "General solution: $ans"
        ),
        finalAnswer = ans,
        plottableExpr = prim.expr,
        elapsedMs = ms(t0),
        antiderivativeExpr = prim.expr
      )
    }

    // Case B: Linear first-order ODE: y' + P(x)·y = Q(x)  ⇔  rhs(x, y) = -P(x)·y + Q(x)
    // Check if rhs(x, y) is affine in y for several x values
    val linearInY = listOf(0.5, 1.1, 1.8, 2.4).all { x ->
      val r0 = rhsAt(x, 0.0)
      val r1 = rhsAt(x, 1.0)
      val r2 = rhsAt(x, 2.0)
      r0.isFinite() && r1.isFinite() && r2.isFinite() && abs((r2 - r1) - (r1 - r0)) < 1e-8 * (1 + abs(r1))
    }
    if (linearInY) {
      val qPlain = "(-($diff))/(${MathFormat.plainNumber(aZ)})"
        .replace(Regex("""(?<![a-zA-Z])z(?![a-zA-Z])"""), "(0)")
        .replace(Regex("""(?<![a-zA-Z])y(?![a-zA-Z])"""), "(0)")
      val r1Plain = "(-($diff))/(${MathFormat.plainNumber(aZ)})"
        .replace(Regex("""(?<![a-zA-Z])z(?![a-zA-Z])"""), "(0)")
        .replace(Regex("""(?<![a-zA-Z])y(?![a-zA-Z])"""), "(1)")
      val pPlain = "($qPlain)-($r1Plain)" // P(x) = Q(x) - rhs(x, 1)
      val pEx = LocalMathEngine.parse(pPlain)?.let { Deriv.simplify(it) } ?: return null
      val qEx = LocalMathEngine.parse(qPlain)?.let { Deriv.simplify(it) } ?: return null

      // Subcase B1: Separable homogeneous y' + P(x)y = 0  (Q(x) == 0)
      val qIsZero = listOf(0.4, 1.2, 2.1).all { abs(qEx.eval(it)) < 1e-10 }
      val intP = Integrator.integrate(pEx) ?: return null
      if (qIsZero) {
        val negP = Deriv.simplify(Ex.Neg(pEx))
        val intNegP = Integrator.integrate(negP) ?: return null
        val ans = "y(x) = C·e^(${intNegP.pretty})"
        return SymbolicStepEngine.SymbolicSolution(
          problemType = "Separable differential equation",
          normalizedMath = "y' = (${negP.pretty()})·y",
          steps = listOf(
            "Separate variables: (1/y) dy = (${negP.pretty()}) dx",
            "Integrate both sides: ln|y| = ${intNegP.pretty} + C₁",
            "Exponentiate: $ans"
          ),
          finalAnswer = ans,
          plottableExpr = "exp(${intNegP.expr})",
          elapsedMs = ms(t0)
        )
      }

      // Subcase B2: Inhomogeneous linear ODE y' + P(x)y = Q(x)
      val muExpr = "exp(${intP.expr})"
      val muPretty = if (intP.pretty == "x") "eˣ" else "e^(${intP.pretty})"
      val muQEx = LocalMathEngine.parse("($muExpr)*(${qEx.toPlain()})")?.let { Deriv.simplify(it) } ?: return null
      val intMuQ = Integrator.integrate(muQEx)
      if (intMuQ != null) {
        val particularEx = LocalMathEngine.parse("(${intMuQ.expr})/($muExpr)")?.let { Deriv.simplify(it) }
        // If P(x) = a is constant and Q(x) = b is constant, simplify nicely: y(x) = b/a + C·e^(-ax)
        val pConst = !pEx.hasX()
        val qConst = !qEx.hasX()
        val ans = if (pConst && qConst) {
          val pv = pEx.eval(0.0)
          val qv = qEx.eval(0.0)
          val yp = fmt(qv / pv)
          val expPart = if (abs(pv - 1.0) < 1e-11) "e⁻ˣ" else if (abs(pv + 1.0) < 1e-11) "eˣ" else "e^(${fmt(-pv)}x)"
          "y(x) = $yp + C·$expPart"
        } else {
          val homPart = if (intP.pretty == "x") "C·e⁻ˣ" else "C·e^(-(${intP.pretty}))"
          "y(x) = (${intMuQ.pretty})/$muPretty + $homPart"
        }
        return SymbolicStepEngine.SymbolicSolution(
          problemType = "Linear differential equation (1st order)",
          normalizedMath = "y' + (${pEx.pretty()})·y = ${qEx.pretty()}",
          steps = listOf(
            "Standard linear form: y' + P(x)·y = Q(x) with P(x) = ${pEx.pretty()}, Q(x) = ${qEx.pretty()}",
            "Integrating factor: μ(x) = e^(∫ P(x) dx) = $muPretty",
            "Multiply by μ(x) and integrate: μ(x)·y = ∫ $muPretty·(${qEx.pretty()}) dx = ${intMuQ.pretty} + C",
            "Divide by μ(x): $ans"
          ),
          finalAnswer = ans,
          plottableExpr = particularEx?.toPlain(),
          elapsedMs = ms(t0)
        )
      }
    }

    return null
  }

  private fun ms(t0: Long): Long = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
}
