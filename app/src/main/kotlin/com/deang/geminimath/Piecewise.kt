package com.deang.geminimath

/**
 * Piecewise functions, e.g. f(x) = { x if x > 1 ; 2 if x ≤ 1 }.
 *
 * The recogniser writes them as \begin{cases} … \end{cases} or \left\{ \begin{array}{ll} … \right.,
 * one row per piece, with "&" (sometimes missing) between the expression and its condition.
 * A parsed function is passed around as a plain string, "piecewise:expr|cond;expr|cond", so the
 * graph code can evaluate it with [LocalMathEngine.evalAt] like any other function.
 */
object Piecewise {
  const val PREFIX = "piecewise:"

  /** One piece: a plain expression and its condition ("x>1", "-1<x<=2", or "else"). */
  data class Piece(val expr: String, val cond: String, /** The condition was unreadable and inferred. */ val guessed: Boolean = false)

  private val OPEN = Regex("""\\left\s*\\\{|\\begin\s*\{\s*cases\s*\}|\\lbrace""")
  private val LAYOUT = Regex("""\\left\s*\\\{|\\lbrace|\\right\s*\.|\\right\s*\\\}|\\(?:begin|end)\s*\{\s*(?:cases|array|matrix|aligned)\s*\}(?:\s*\{[lcr|\s]*\})?""")
  private val OP = Regex("""\\leq|\\geq|\\le(?![a-z])|\\ge(?![a-z])|\\lt|\\gt|≤|≥|<=|>=|<|>""")
  private val WORDS = Regex("""\b(?:if|for|when|se|per|otherwise|altrimenti)\b""")

  fun looksPiecewise(latex: String) = OPEN.containsMatchIn(latex)

  /** Reads the pieces from LaTeX (with or without "f(x) =" in front). Null if it isn't piecewise. */
  fun parse(latex: String): List<Piece>? {
    if (!looksPiecewise(latex)) return null
    // Whatever comes after "f(x) =" (the brace starts the definition)
    val start = OPEN.find(latex)!!.range.first
    var body = latex.substring(start).replace(LAYOUT, " ")
    body = body.replace(Regex("""\\(?:quad|qquad|,|;|:|!)"""), " ")
    val rows = body.split("\\\\").map { it.trim() }.filter { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } }
    val parsed = rows.mapNotNull { row(it) }
    if (parsed.size < 2) return null
    // A row whose condition couldn't be read: with two pieces it's the opposite of the other one
    // ("x ≤ 1" → "x > 1"); otherwise it's "everything else".
    val missing = parsed.indices.filter { parsed[it].cond == UNREAD }
    if (missing.size > 1) return null
    if (missing.size == 1) {
      val other = parsed.filterIndexed { i, _ -> i != missing[0] }
      val cond = if (parsed.size == 2) negate(other[0].cond) ?: "else" else "else"
      return parsed.mapIndexed { i, p -> if (i == missing[0]) p.copy(cond = cond, guessed = true) else p }
    }
    return parsed
  }

  private const val UNREAD = "?"

  /** "x<=1" → "x>1" (single comparisons only). */
  private fun negate(cond: String): String? {
    val ops = Regex("<=|>=|<|>").findAll(cond).toList()
    if (ops.size != 1) return null
    val op = ops[0]
    val flipped = when (op.value) { "<=" -> ">"; ">=" -> "<"; "<" -> ">="; else -> "<=" }
    return cond.substring(0, op.range.first) + flipped + cond.substring(op.range.last + 1)
  }

  private fun unbrace(s: String): String {
    var t = s.trim()
    while (t.startsWith("{") && t.endsWith("}") && matchingClose(t) == t.lastIndex) t = t.substring(1, t.length - 1).trim()
    return t
  }

  private fun matchingClose(t: String): Int {
    var d = 0
    for ((i, c) in t.withIndex()) { if (c == '{') d++; if (c == '}') { d--; if (d == 0) return i } }
    return -1
  }

  private fun row(rowRaw: String): Piece? {
    var row = rowRaw.replace(",", " ").replace(WORDS, " ").trim()
    // A handwritten x is often read as "×": just before a comparison, or in a row with no comparison
    row = row.replace(Regex("""\\times\s*(?=${OP.pattern})"""), " x ")
    if (OP.find(row) == null) row = row.replace("\\times", " x ")
    val (exprPart, condPart) = if (row.contains('&')) {
      row.substringBefore('&') to row.substringAfter('&').replace("&", " ")
    } else {
      // No "&": the condition starts at the x (or number, in "-1 < x < 2") just before the first comparison
      val op = OP.find(row)
      if (op == null) {
        // No readable comparison. "x x 2 1" is the expression "x" and a garbled "x > 1"
        val garbled = Regex("""^(.+?)\s+x\s+[-\d\s.]+$""").find(row)
        val e = plain(unbrace(garbled?.groupValues?.get(1) ?: row)) ?: return null
        return Piece(e, if (garbled != null) UNREAD else "else")
      }
      val left = row.substring(0, op.range.first).trimEnd()
      val m = Regex("""(?:-?\s*\d+(?:\.\d+)?|x|\{\s*x\s*\})\s*$""").find(left) ?: return null
      row.substring(0, m.range.first) to row.substring(m.range.first)
    }
    val expr = plain(unbrace(exprPart)) ?: return null
    val condRaw = unbrace(condPart)
    val cond = if (condRaw.isBlank() || OP.find(condRaw) == null) "else" else condition(condRaw) ?: return null
    return Piece(expr, cond)
  }

  private fun plain(latex: String): String? {
    if (latex.isBlank()) return null
    val e = LocalMathEngine.latexToMathExpr(latex)
    return e.takeIf { LocalMathEngine.parse(it) != null }
  }

  /** "x \leq 1" → "x<=1"; each side becomes a plain expression. */
  private fun condition(latex: String): String? {
    val ops = OP.findAll(latex).toList()
    if (ops.isEmpty()) return null
    val sides = OP.split(latex).map { unbrace(it) }
    if (sides.any { it.isBlank() }) return null
    val out = StringBuilder()
    for ((i, side) in sides.withIndex()) {
      out.append(plain(side) ?: return null)
      if (i < ops.size) out.append(
        when (ops[i].value) { "\\leq", "\\le", "≤", "<=" -> "<="; "\\geq", "\\ge", "≥", ">=" -> ">="; "\\lt", "<" -> "<"; else -> ">" }
      )
    }
    return out.toString()
  }

  fun encode(pieces: List<Piece>) = PREFIX + pieces.joinToString(";") { "${it.expr}|${it.cond}" }

  private val cache = HashMap<String, List<Piece>>()
  fun decode(spec: String): List<Piece> = synchronized(cache) {
    cache.getOrPut(spec) {
      spec.removePrefix(PREFIX).split(';').mapNotNull { p -> p.split('|').takeIf { it.size == 2 }?.let { Piece(it[0], it[1]) } }
    }
  }

  private fun holds(cond: String, x: Double, degrees: Boolean): Boolean {
    if (cond == "else") return true
    val ops = Regex("<=|>=|<|>").findAll(cond).map { it.value }.toList()
    val vals = cond.split(Regex("<=|>=|<|>")).map { LocalMathEngine.evalAt(it, x, degrees) }
    for (i in ops.indices) {
      val a = vals[i]; val b = vals[i + 1]
      val ok = when (ops[i]) { "<" -> a < b; "<=" -> a <= b + 1e-12; ">" -> a > b; else -> a >= b - 1e-12 }
      if (!ok) return false
    }
    return true
  }

  /** Which piece applies at [x] (the first whose condition holds; "else" last), or −1. */
  fun index(spec: String, x: Double, degrees: Boolean = false): Int {
    val ps = decode(spec)
    ps.forEachIndexed { i, p -> if (p.cond != "else" && holds(p.cond, x, degrees)) return i }
    return ps.indexOfFirst { it.cond == "else" }
  }

  fun eval(spec: String, x: Double, degrees: Boolean = false): Double {
    val i = index(spec, x, degrees)
    if (i < 0) return Double.NaN
    return LocalMathEngine.evalAt(decode(spec)[i].expr, x, degrees)
  }

  /** The numbers where conditions change (e.g. 1 in "x>1"), for the continuity check. */
  fun breakpoints(pieces: List<Piece>): List<Double> =
    pieces.flatMap { p -> if (p.cond == "else") emptyList() else p.cond.split(Regex("<=|>=|<|>")) }
      .filter { !it.contains('x') }
      .map { LocalMathEngine.evalAt(it, 0.0) }
      .filter { it.isFinite() }
      .distinct().sorted()

  /** As typed in the calculator: "{x>1: x, x≤1: 2}". */
  fun toTyped(spec: String): String = "{" + decode(spec).joinToString(", ") { p ->
    val e = typed(p.expr)
    if (p.cond == "else") e else typed(p.cond).replace("<=", "≤").replace(">=", "≥") + ": " + e
  } + "}"

  private fun typed(plain: String) = plain.replace("*", "×").replace("sqrt", "√").replace(Regex("""\bpi\b"""), "π")

  /** Reads the calculator form "{x>1: x, x≤1: 2}" (plain expressions). Null if it isn't one. */
  fun fromTyped(input: String, normalise: (String) -> String?): String? {
    val s = input.trim()
    if (!s.startsWith("{") || !s.endsWith("}")) return null
    val parts = s.substring(1, s.length - 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val pieces = parts.map { part ->
      val colon = part.indexOf(':')
      if (colon < 0) Piece(normalise(part) ?: return null, "else")
      else {
        val condText = part.substring(0, colon).replace("≤", "<=").replace("≥", ">=")
        val ops = Regex("<=|>=|<|>").findAll(condText).map { it.value }.toList()
        if (ops.isEmpty()) return null
        val sides = condText.split(Regex("<=|>=|<|>")).map { normalise(it) ?: return null }
        val cond = StringBuilder(sides[0]).apply { ops.forEachIndexed { i, op -> append(op).append(sides[i + 1]) } }.toString()
        Piece(normalise(part.substring(colon + 1)) ?: return null, cond)
      }
    }
    return encode(pieces)
  }

  fun pretty(cond: String) = cond.replace("<=", " ≤ ").replace(">=", " ≥ ").replace("<", " < ").replace(">", " > ").replace("*", "·")
}
