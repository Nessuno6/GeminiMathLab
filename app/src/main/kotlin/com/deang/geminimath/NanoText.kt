package com.deang.geminimath

/**
 * Turns Gemini Nano's Markdown + LaTeX replies ("The answer is $e^{x}(x-1) + C$ **because**…")
 * into plain readable text ("The answer is eˣ(x - 1) + C because…").
 * Works on partial text too, since it is applied to the stream as it arrives.
 */
object NanoText {
  private const val SUP_FROM = "0123456789+-=()nixyk"
  private const val SUP_TO = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱˣʸᵏ"
  private const val SUB_FROM = "0123456789+-=()"
  private const val SUB_TO = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎"

  private val SYMBOLS = linkedMapOf(
    "\\int" to "∫", "\\cdot" to "·", "\\times" to "×", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
    "\\infty" to "∞", "\\to" to "→", "\\rightarrow" to "→", "\\Rightarrow" to "⇒", "\\implies" to "⇒",
    "\\leftarrow" to "←", "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥", "\\neq" to "≠",
    "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡", "\\pi" to "π", "\\theta" to "θ", "\\alpha" to "α",
    "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\Delta" to "Δ", "\\lambda" to "λ", "\\mu" to "μ",
    "\\sigma" to "σ", "\\phi" to "φ", "\\omega" to "ω", "\\sum" to "Σ", "\\prod" to "Π", "\\partial" to "∂",
    "\\in" to "∈", "\\mathbb{R}" to "ℝ", "\\mathbb{Z}" to "ℤ", "\\circ" to "°", "\\ldots" to "…", "\\cdots" to "…",
    "\\lim" to "lim", "\\sin" to "sin", "\\cos" to "cos", "\\tan" to "tan", "\\ln" to "ln", "\\log" to "log",
    "\\exp" to "exp", "\\arctan" to "arctan", "\\arcsin" to "arcsin", "\\arccos" to "arccos"
  )

  fun clean(raw: String): String {
    var s = raw.replace("\r", "")
    // Math delimiters: $$…$$, \[…\], \(…\), $…$
    s = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL).replace(s) { latexToText(it.groupValues[1]) }
    s = Regex("""\\\[(.+?)\\]""", RegexOption.DOT_MATCHES_ALL).replace(s) { latexToText(it.groupValues[1]) }
    s = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL).replace(s) { latexToText(it.groupValues[1]) }
    s = Regex("""\$([^$]+?)\$""").replace(s) { latexToText(it.groupValues[1]) }
    // Markdown
    s = s.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    s = s.replace(Regex("""__(.+?)__"""), "$1")
    s = s.replace(Regex("""(?<![\w*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![\w*])"""), "$1")
    s = s.replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
    s = s.replace(Regex("""^\s*[-*]\s+""", RegexOption.MULTILINE), "• ")
    s = s.replace("`", "")
    // Leftovers: an unclosed $ while streaming, a dangling **, LaTeX written without delimiters
    s = s.replace("$", "").replace("**", "")
    // A * between two terms is multiplication: u*v → u·v
    s = s.replace(Regex("""(?<=[\w)²³ˣ])\s*\*\s*(?=[\w(])"""), "·")
    if (s.contains('\\') || Regex("""[\^_]\s*\{|\^[0-9a-zA-Z]""").containsMatchIn(s)) s = latexToText(s)
    return s.replace(Regex("""[ \t]{2,}"""), " ").trim()
  }

  /** LaTeX → readable Unicode: \frac{a}{b} → a/b, x^{2} → x², \sqrt{x} → √x, \int → ∫ … */
  fun latexToText(latex: String): String {
    var s = latex
      .replace(Regex("""\\(?:left|right|big|Big|bigg|Bigg)(?![a-zA-Z])"""), "")
      .replace(Regex("""\\[,;:!]"""), " ")
      .replace(Regex("""\\(?:quad|qquad|displaystyle)(?![a-zA-Z])"""), " ")
      .replace("\\ ", " ")
      .replace(Regex("""\\(?:text|mathrm|operatorname|mathbf|mathit|textbf)\s*\{([^{}]*)\}"""), "$1")
      .replace("\\dfrac", "\\frac").replace("\\tfrac", "\\frac")

    // Powers and subscripts first, so \frac{x^{2}}{2} has no nested braces left
    s = scripts(s)
    // \frac and \sqrt, innermost first
    repeat(6) {
      s = Regex("""\\frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""").replace(s) { m ->
        "${group(m.groupValues[1])}/${group(m.groupValues[2])}"
      }
      s = Regex("""\\sqrt\s*\{([^{}]*)\}""").replace(s) { m ->
        val inner = m.groupValues[1].trim()
        if (inner.length == 1 || inner.all { it.isLetterOrDigit() }) "√$inner" else "√($inner)"
      }
    }
    // Longest command names first so \leq isn't read as \le + q
    for ((k, v) in SYMBOLS.entries.sortedByDescending { it.key.length }) {
      s = s.replace(Regex(Regex.escape(k) + "(?![a-zA-Z])"), v)
    }
    s = scripts(s)
    // Any other command: keep its name; drop grouping braces
    s = s.replace(Regex("""\\([a-zA-Z]+)"""), "$1")
    s = s.replace("{", "").replace("}", "")
    return s.replace(Regex("""\s{2,}"""), " ").trim()
  }

  private fun scripts(input: String): String {
    var s = input
    s = Regex("""\^\s*\{([^{}]*)\}""").replace(s) { script(it.groupValues[1], SUP_FROM, SUP_TO, "^") }
    s = Regex("""\^\s*([0-9a-zA-Z+\-])""").replace(s) { script(it.groupValues[1], SUP_FROM, SUP_TO, "^") }
    s = Regex("""_\s*\{([^{}]*)\}""").replace(s) { script(it.groupValues[1], SUB_FROM, SUB_TO, "_") }
    s = Regex("""_\s*([0-9])""").replace(s) { script(it.groupValues[1], SUB_FROM, SUB_TO, "_") }
    return s
  }

  private fun group(t: String): String {
    val x = t.trim()
    // Bracket only parts with an operator: x² stays x², x + 1 becomes (x + 1)
    val compound = x.drop(1).any { it in "+-*/=· " }
    return if (compound) "($x)" else x
  }

  private fun script(text: String, from: String, to: String, marker: String): String {
    val t = text.trim()
    if (t.isNotEmpty() && t.all { from.indexOf(it) >= 0 }) return t.map { to[from.indexOf(it)] }.joinToString("")
    return if (t.length == 1) "$marker$t" else "$marker($t)"
  }
}
