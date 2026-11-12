package com.deang.geminimath

/**
 * Turns recognised LaTeX into plain expressions ("x*e^(x)") and evaluates them.
 * Parsing goes through [ExParser]; parsed trees are cached because plotting and root finding
 * evaluate the same expression many times.
 */
object LocalMathEngine {
  private val cache = object : LinkedHashMap<String, Ex?>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ex?>?) = size > 64
  }

  /** Removes LaTeX spacing commands that carry no math (\! \, \; \quad …). */
  fun cleanLatexSpacing(latex: String): String = latex
    // Spacing commands (\, \; \! …), but not the row break "\\" followed by a space
    .replace(Regex("""(?<!\\)\\[!,;:> ]"""), " ")
    .replace(Regex("""\\(?:quad|qquad|displaystyle|textstyle|limits|nolimits)(?![a-zA-Z])"""), " ")
    .replace('~', ' ')
    .replace("$$", "")
    .replace("$", "")
    .trim()

  fun latexToMathExpr(raw: String): String {
    var s = cleanLatexSpacing(raw)
      // log with a base: \log_{2} 8 → ln 8 / ln 2
      .replace(Regex("""\\log\s*_\s*\{([^{}]+)\}""")) { "\\frac{1}{\\ln(${it.groupValues[1]})}\\ln " }
      .replace(Regex("""\\log\s*_\s*([0-9a-zA-Z])""")) { "\\frac{1}{\\ln(${it.groupValues[1]})}\\ln " }
      // degrees and percent
      // No "*": sin 30° must stay sin(30·π/180), so the factor joins the function's argument
      .replace(Regex("""\^\s*\{\s*\\circ\s*\}|\^\s*\\circ|°"""), "(\\pi/180)")
      .replace("\\%", "/100").replace("%", "/100")
      .replace("\\dfrac", "\\frac")
      .replace("\\tfrac", "\\frac")
      .replace("\\left|", "\\abs{").replace("\\right|", "}")
      .replace("\\lvert", "\\abs{").replace("\\rvert", "}")
      .replace("\\left<", "(").replace("\\right>", ")")
      .replace("\\left", "").replace("\\right", "")
      .replace("\\operatorname { i n }", "in")
      .replace("\\operatorname{in}", "in")
      .replace(Regex("""\\(?:operatorname|mathrm|text|textsc|textrm|textit|textbf|mathit|mathbf|boldsymbol)\s*\{\s*([^{}]+)\s*\}""")) {
        it.groupValues[1].replace(" ", "")
      }
      .replace("s in", "sin")
      .replace("l n", "ln")
      .replace("c o s", "cos")
      .replace("t a n", "tan")
      .replace("l o g", "log")
      .replace("\\cdot", "*")
      .replace("\\times", "*")
      .replace("\\ast", "*")
      .replace("\\div", "/")
      .replace("\\pi", "pi")
      .replace("π", "pi")
      .replace("²", "^2")
      .replace("³", "^3")
      .replace("×", "*")
      .replace("÷", "/")
      .replace("−", "-")
      .replace("·", "*")

    repeat(8) {
      s = replaceLatexFrac(s)
      s = replaceNthRoot(s)
      s = replaceLatexCmd1(s, "\\sqrt", "sqrt")
      s = replaceLatexCmd1(s, "\\abs", "abs")
    }
    s = s.replace(Regex("""\\sqrt\s*([a-zA-Z0-9]+)"""), "sqrt($1)")
    s = s.replace("\\sqrt", "sqrt")
    s = s.replace(Regex("""_\s*\{[^{}]*\}"""), "").replace(Regex("""_\s*[a-zA-Z0-9]"""), "")
    s = s.replace("\\", "")
    s = s.replace("{", "(").replace("}", ")")
    s = pairAbsBars(s)
    s = s.replace(" ", "")
    return s
  }

  /** "|x-1|" → "abs(x-1)": bars alternate between opening and closing. */
  private fun pairAbsBars(s: String): String {
    if (!s.contains('|')) return s
    val sb = StringBuilder()
    var open = false
    for (c in s) {
      if (c == '|') {
        sb.append(if (open) ")" else "abs(")
        open = !open
      } else sb.append(c)
    }
    if (open) sb.append(')')
    return sb.toString()
  }

  private fun replaceLatexFrac(input: String): String {
    val idx = input.indexOf("\\frac")
    if (idx < 0) return input
    val arg1 = extractBraced(input, idx + 5) ?: return input
    val arg2 = extractBraced(input, arg1.nextIndex) ?: return input
    return input.substring(0, idx) + "((" + arg1.content + ")/(" + arg2.content + "))" + input.substring(arg2.nextIndex)
  }

  /** \sqrt[n]{a} → ((a)^(1/(n))) */
  private fun replaceNthRoot(input: String): String {
    val m = Regex("""\\sqrt\s*\[([^\]]+)]""").find(input) ?: return input
    val arg = extractBraced(input, m.range.last + 1) ?: return input
    return input.substring(0, m.range.first) + "((" + arg.content + ")^(1/(" + m.groupValues[1] + ")))" + input.substring(arg.nextIndex)
  }

  private fun replaceLatexCmd1(input: String, cmd: String, fn: String): String {
    var from = 0
    while (true) {
      val idx = input.indexOf(cmd, from)
      if (idx < 0) return input
      val after = idx + cmd.length
      // Make sure this is the whole command name (\sqrt, not \sqrtx)
      if (after < input.length && input[after].isLetter()) { from = after; continue }
      val arg1 = extractBraced(input, after) ?: return input
      return input.substring(0, idx) + "$fn(" + arg1.content + ")" + input.substring(arg1.nextIndex)
    }
  }

  private data class BracedArg(val content: String, val nextIndex: Int)

  private fun extractBraced(s: String, startIdx: Int): BracedArg? {
    var i = startIdx
    while (i < s.length && s[i].isWhitespace()) i++
    if (i >= s.length || s[i] != '{') return null
    var depth = 0
    val contentStart = i + 1
    while (i < s.length) {
      if (s[i] == '{') depth++
      else if (s[i] == '}') {
        depth--
        if (depth == 0) return BracedArg(s.substring(contentStart, i), i + 1)
      }
      i++
    }
    return null
  }

  /** Parsed tree for a plain expression, or null when it can't be read. */
  fun parse(expr: String): Ex? = synchronized(cache) {
    if (cache.containsKey(expr)) return cache[expr]
    val e = runCatching { ExParser.parse(expr) }.getOrNull()
    cache[expr] = e
    e
  }

  fun evalAt(expr: String, xVal: Double, degrees: Boolean = false): Double =
    if (expr.startsWith(Piecewise.PREFIX)) Piecewise.eval(expr, xVal, degrees)
    else parse(expr)?.eval(xVal, degrees) ?: Double.NaN
}
