package com.deang.geminimath

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 2D mathematical typesetting for Compose (`StepsCard` and `AiAssistant`).
 * Parses both LaTeX and the solver's pretty Unicode strings into 2D layout nodes:
 * stacked fractions, radicals with vinculum overbars, superscripts/subscripts,
 * integral/sum bounds, and 2D bracketed matrices.
 */
object MathTypesetter {

  sealed interface MathNode {
    data class Text(val text: String) : MathNode
    data class Frac(val num: MathNode, val den: MathNode) : MathNode
    data class Sqrt(val radicand: MathNode, val index: MathNode? = null) : MathNode
    data class SupSub(val base: MathNode, val sup: MathNode? = null, val sub: MathNode? = null) : MathNode
    data class BigOp(val symbol: String, val lower: MathNode? = null, val upper: MathNode? = null) : MathNode
    data class Matrix(val rows: List<List<MathNode>>) : MathNode
    data class Row(val children: List<MathNode>) : MathNode
  }

  private val MATRIX_BRACKET_RE = Regex("""\[\s*([^\[\];]+(?:\s*;\s*[^\[\];]+)+)\s*\]""")
  private val INT_FROM_TO_RE = Regex("""∫\s+from\s+([^\s]+)\s+to\s+([^\s]+)\s+of\s+""")
  private val SUM_FROM_TO_RE = Regex("""Σ\s+from\s+([^\s]+\s*=\s*[^\s]+)\s+to\s+([^\s]+)\s+of\s+""")
  private val LIM_TO_RE = Regex("""lim\s+([a-zA-Z]→[^\s]+)\s+of\s+""")

  /** Parses a mixed prose/math string into a [MathNode] tree for 2D layout. */
  fun parse(raw: String): MathNode {
    if (raw.isEmpty()) return MathNode.Text("")
    val nodes = parseSequence(raw)
    return when (nodes.size) {
      0 -> MathNode.Text("")
      1 -> nodes[0]
      else -> MathNode.Row(nodes)
    }
  }

  private fun parseSequence(input: String): List<MathNode> {
    var s = input
    val out = ArrayList<MathNode>()

    // 1. Check for 2D bracketed matrix notation: [a, b; c, d]
    val matMatch = MATRIX_BRACKET_RE.find(s)
    if (matMatch != null) {
      val rowsRaw = matMatch.groupValues[1].split(';').map { r -> r.split(',').map { it.trim() } }
      val cols = rowsRaw.firstOrNull()?.size ?: 0
      if (cols >= 1 && rowsRaw.all { it.size == cols }) {
        if (matMatch.range.first > 0) out += parseSequence(s.substring(0, matMatch.range.first))
        out += MathNode.Matrix(rowsRaw.map { row -> row.map { cell -> parse(cell) } })
        if (matMatch.range.last + 1 < s.length) out += parseSequence(s.substring(matMatch.range.last + 1))
        return mergeAdjacentText(out)
      }
    }

    // 2. Check for "∫ from a to b of ", "Σ from n = 1 to 10 of ", "lim x→0 of "
    INT_FROM_TO_RE.find(s)?.let { m ->
      if (m.range.first > 0) out += parseSequence(s.substring(0, m.range.first))
      out += MathNode.BigOp("∫", parse(m.groupValues[1]), parse(m.groupValues[2]))
      if (m.range.last + 1 < s.length) out += parseSequence(s.substring(m.range.last + 1))
      return mergeAdjacentText(out)
    }
    SUM_FROM_TO_RE.find(s)?.let { m ->
      if (m.range.first > 0) out += parseSequence(s.substring(0, m.range.first))
      out += MathNode.BigOp("Σ", parse(m.groupValues[1]), parse(m.groupValues[2]))
      if (m.range.last + 1 < s.length) out += parseSequence(s.substring(m.range.last + 1))
      return mergeAdjacentText(out)
    }
    LIM_TO_RE.find(s)?.let { m ->
      if (m.range.first > 0) out += parseSequence(s.substring(0, m.range.first))
      out += MathNode.BigOp("lim", parse(m.groupValues[1]), null)
      if (m.range.last + 1 < s.length) out += parseSequence(s.substring(m.range.last + 1))
      return mergeAdjacentText(out)
    }

    var i = 0
    val textBuf = StringBuilder()
    fun flushText() {
      if (textBuf.isNotEmpty()) {
        out += MathNode.Text(textBuf.toString())
        textBuf.setLength(0)
      }
    }

    while (i < s.length) {
      // LaTeX \frac{a}{b}
      if (s.startsWith("\\frac", i)) {
        val g1 = extractGroup(s, i + 5, '{', '}')
        val g2 = g1?.let { extractGroup(s, it.second, '{', '}') }
        if (g1 != null && g2 != null) {
          flushText()
          out += MathNode.Frac(parse(g1.first), parse(g2.first))
          i = g2.second
          continue
        }
      }

      // LaTeX \sqrt{a} or √(...) or √token
      if (s.startsWith("\\sqrt", i)) {
        val opt = extractGroup(s, i + 5, '[', ']')
        val afterOpt = opt?.second ?: (i + 5)
        val g = extractGroup(s, afterOpt, '{', '}')
        if (g != null) {
          flushText()
          out += MathNode.Sqrt(parse(g.first), opt?.let { parse(it.first) })
          i = g.second
          continue
        }
      }
      if (s[i] == '√') {
        flushText()
        val nextIdx = i + 1
        val paren = extractGroup(s, nextIdx, '(', ')')
        if (paren != null) {
          out += MathNode.Sqrt(parse(paren.first))
          i = paren.second
          continue
        }
        var end = nextIdx
        while (end < s.length && (s[end].isLetterOrDigit() || s[end] in "²³⁴⁵⁶⁷⁸⁹⁰ˣ⁻")) end++
        if (end > nextIdx) {
          out += MathNode.Sqrt(parse(s.substring(nextIdx, end)))
          i = end
          continue
        }
      }

      // Derivative d/dx or dy/dx
      if ((s.startsWith("d/dx", i) || s.startsWith("dy/dx", i)) &&
        (i == 0 || !s[i - 1].isLetter()) &&
        (i + (if (s.startsWith("dy/dx", i)) 5 else 4) >= s.length || !s[i + (if (s.startsWith("dy/dx", i)) 5 else 4)].isLetter())
      ) {
        val isDy = s.startsWith("dy/dx", i)
        flushText()
        out += MathNode.Frac(MathNode.Text(if (isDy) "dy" else "d"), MathNode.Text("dx"))
        i += if (isDy) 5 else 4
        continue
      }

      // Parenthesized or token fraction: (A)/(B), (A)/token, token/(B), or math token/token
      if (s[i] == '(') {
        val g1 = extractGroup(s, i, '(', ')')
        if (g1 != null) {
          var slashIdx = g1.second
          while (slashIdx < s.length && s[slashIdx] == ' ') slashIdx++
          if (slashIdx < s.length && s[slashIdx] == '/') {
            var denStart = slashIdx + 1
            while (denStart < s.length && s[denStart] == ' ') denStart++
            val g2 = extractGroup(s, denStart, '(', ')')
            if (g2 != null) {
              flushText()
              out += MathNode.Frac(parse(g1.first), parse(g2.first))
              i = g2.second
              continue
            }
            val denTok = readMathAtom(s, denStart)
            if (denTok != null) {
              flushText()
              out += MathNode.Frac(parse(g1.first), parse(denTok.first))
              i = denTok.second
              continue
            }
          }
        }
      }

      // Explicit caret exponent: base^(exp) or base^{exp}
      if (s[i] == '^' && i + 1 < s.length && (s[i + 1] == '(' || s[i + 1] == '{')) {
        val open = s[i + 1]
        val close = if (open == '(') ')' else '}'
        val expGroup = extractGroup(s, i + 1, open, close)
        if (expGroup != null) {
          val baseStr = popTrailingBase(textBuf)
          flushText()
          val baseNode = if (baseStr.isNotEmpty()) MathNode.Text(baseStr) else out.removeLastOrNull() ?: MathNode.Text("")
          out += MathNode.SupSub(baseNode, sup = parse(expGroup.first))
          i = expGroup.second
          continue
        }
      }

      // Simple inline math fraction like 1/2, -3/4, x²/2, π/6, Dₓ/D, b/2a
      if (s[i] == '/' && textBuf.isNotEmpty()) {
        val numStr = popTrailingFractionNumerator(textBuf)
        if (numStr != null) {
          var denStart = i + 1
          val g2 = extractGroup(s, denStart, '(', ')')
          if (g2 != null) {
            flushText()
            out += MathNode.Frac(parse(numStr), parse(g2.first))
            i = g2.second
            continue
          }
          val denTok = readMathAtom(s, denStart)
          if (denTok != null && isMathFractionPair(numStr, denTok.first)) {
            flushText()
            out += MathNode.Frac(parse(numStr), parse(denTok.first))
            i = denTok.second
            continue
          } else {
            textBuf.append(numStr)
          }
        }
      }

      textBuf.append(s[i])
      i++
    }
    flushText()
    return mergeAdjacentText(out)
  }

  private fun isMathChar(c: Char): Boolean =
    c.isDigit() || c in "xyzXYZπeC²³⁴⁵⁶⁷⁸⁹⁰₁₂₃ₓᵧ⁻⁺√|"

  private fun isMathFractionPair(num: String, den: String): Boolean {
    // Reject prose slashes like "and/or", "true/false", "sin/cos"
    if (num.all { it.isLetter() } && num.length >= 3 && den.all { it.isLetter() } && den.length >= 3) return false
    return num.any { isMathChar(it) } || den.any { isMathChar(it) }
  }

  private fun popTrailingBase(sb: StringBuilder): String {
    if (sb.isEmpty()) return ""
    var k = sb.length - 1
    while (k >= 0 && (sb[k].isLetterOrDigit() || sb[k] in "π)")) {
      if (sb[k] == ')') break
      k--
    }
    val start = k + 1
    if (start >= sb.length) return ""
    val res = sb.substring(start)
    sb.setLength(start)
    return res
  }

  private fun popTrailingFractionNumerator(sb: StringBuilder): String? {
    if (sb.isEmpty()) return null
    var k = sb.length - 1
    while (k >= 0 && (sb[k].isLetterOrDigit() || sb[k] in "π²³⁴⁵⁶⁷⁸⁹⁰₁₂₃ₓᵧ⁻·")) k--
    // Include leading minus if preceded by space or start or '='
    if (k >= 0 && (sb[k] == '-' || sb[k] == '−') && (k == 0 || sb[k - 1] in " =(+")) k--
    val start = k + 1
    if (start >= sb.length) return null
    val token = sb.substring(start)
    sb.setLength(start)
    return token
  }

  private fun readMathAtom(s: String, start: Int): Pair<String, Int>? {
    if (start >= s.length) return null
    var i = start
    if (s[i] == '-' || s[i] == '−') i++
    val bodyStart = i
    while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "π²³⁴⁵⁶⁷⁸⁹⁰₁₂₃ₓᵧ⁻·|")) i++
    if (i <= bodyStart) return null
    // Check if followed by (arg) such as cos(x) or ln(x)
    if (i < s.length && s[i] == '(') {
      val g = extractGroup(s, i, '(', ')')
      if (g != null) return s.substring(start, g.second) to g.second
    }
    return s.substring(start, i) to i
  }

  private fun extractGroup(s: String, startIdx: Int, open: Char, close: Char): Pair<String, Int>? {
    var i = startIdx
    while (i < s.length && s[i] == ' ') i++
    if (i >= s.length || s[i] != open) return null
    var depth = 0
    val contentStart = i + 1
    while (i < s.length) {
      if (s[i] == open) depth++
      else if (s[i] == close) {
        depth--
        if (depth == 0) return s.substring(contentStart, i) to (i + 1)
      }
      i++
    }
    return null
  }

  private fun mergeAdjacentText(nodes: List<MathNode>): List<MathNode> {
    if (nodes.size <= 1) return nodes
    val out = ArrayList<MathNode>(nodes.size)
    for (n in nodes) {
      val last = out.lastOrNull()
      if (last is MathNode.Text && n is MathNode.Text) {
        out[out.lastIndex] = MathNode.Text(last.text + n.text)
      } else {
        out += n
      }
    }
    return out
  }
}

/**
 * Renders mixed text and 2D mathematical expressions (stacked fractions, radicals with overbars,
 * superscripts/subscripts, integral bounds, and matrices).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypesetMathText(
  text: String,
  color: Color,
  fontSize: TextUnit = 13.sp,
  fontFamily: FontFamily? = null,
  fontStyle: FontStyle? = null,
  fontWeight: FontWeight? = null,
  modifier: Modifier = Modifier
) {
  val root = remember(text) { MathTypesetter.parse(text) }
  RenderMathNode(
    node = root,
    color = color,
    fontSize = fontSize,
    fontFamily = fontFamily,
    fontStyle = fontStyle,
    fontWeight = fontWeight,
    isTopLevel = true,
    modifier = modifier
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderMathNode(
  node: MathTypesetter.MathNode,
  color: Color,
  fontSize: TextUnit,
  fontFamily: FontFamily?,
  fontStyle: FontStyle?,
  fontWeight: FontWeight?,
  isTopLevel: Boolean,
  modifier: Modifier = Modifier
) {
  when (node) {
    is MathTypesetter.MathNode.Text -> {
      if (isTopLevel && node.text.contains(' ') && node.text.length > 28) {
        Text(
          text = node.text,
          color = color,
          fontSize = fontSize,
          fontFamily = fontFamily,
          fontStyle = fontStyle,
          fontWeight = fontWeight,
          lineHeight = fontSize * 1.35f,
          modifier = modifier
        )
      } else {
        // Split long text segments into words at top level so FlowRow can wrap naturally around 2D math nodes
        Text(
          text = node.text,
          color = color,
          fontSize = fontSize,
          fontFamily = fontFamily,
          fontStyle = fontStyle,
          fontWeight = fontWeight,
          modifier = modifier
        )
      }
    }

    is MathTypesetter.MathNode.Row -> {
      if (isTopLevel) {
        FlowRow(
          modifier = modifier,
          horizontalArrangement = Arrangement.Start,
          verticalArrangement = Arrangement.Center
        ) {
          for (child in node.children) {
            if (child is MathTypesetter.MathNode.Text && child.text.contains(' ')) {
              val tokens = splitKeepingSpaces(child.text)
              for (tok in tokens) {
                Text(
                  text = tok,
                  color = color,
                  fontSize = fontSize,
                  fontFamily = fontFamily,
                  fontStyle = fontStyle,
                  fontWeight = fontWeight,
                  modifier = Modifier.align(Alignment.CenterVertically)
                )
              }
            } else {
              Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                RenderMathNode(child, color, fontSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
              }
            }
          }
        }
      } else {
        Row(
          modifier = modifier,
          verticalAlignment = Alignment.CenterVertically
        ) {
          for (child in node.children) {
            RenderMathNode(child, color, fontSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
          }
        }
      }
    }

    is MathTypesetter.MathNode.Frac -> {
      val childSize = fontSize * 0.86f
      Column(
        modifier = modifier
          .padding(horizontal = 2.dp, vertical = 1.dp)
          .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Box(modifier = Modifier.padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
          RenderMathNode(node.num, color, childSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
        }
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(1.2.dp)
            .background(color.copy(alpha = 0.9f))
        )
        Box(modifier = Modifier.padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
          RenderMathNode(node.den, color, childSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
        }
      }
    }

    is MathTypesetter.MathNode.Sqrt -> {
      Row(
        modifier = modifier
          .padding(horizontal = 1.dp)
          .drawBehind {
            val strokePx = 1.3.dp.toPx()
            val radicalW = 8.dp.toPx()
            val topY = 1.5.dp.toPx()
            val btmY = size.height - 1.5.dp.toPx()
            val midY = topY + (btmY - topY) * 0.58f
            val path = Path().apply {
              moveTo(0f, midY)
              lineTo(radicalW * 0.38f, btmY)
              lineTo(radicalW, topY)
              lineTo(size.width, topY)
            }
            drawPath(path, color = color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
          }
          .padding(start = 9.dp, top = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        RenderMathNode(node.radicand, color, fontSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
      }
    }

    is MathTypesetter.MathNode.SupSub -> {
      val scriptSize = fontSize * 0.72f
      Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top
      ) {
        Box(modifier = Modifier.align(Alignment.CenterVertically)) {
          RenderMathNode(node.base, color, fontSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
        }
        Column(
          modifier = Modifier.padding(start = 1.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          if (node.sup != null) {
            RenderMathNode(node.sup, color, scriptSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
          }
          if (node.sub != null) {
            RenderMathNode(node.sub, color, scriptSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
          }
        }
      }
    }

    is MathTypesetter.MathNode.BigOp -> {
      val boundSize = fontSize * 0.68f
      Row(
        modifier = modifier.padding(end = 3.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = node.symbol,
          color = color,
          fontSize = if (node.symbol == "lim") fontSize else fontSize * 1.25f,
          fontFamily = fontFamily,
          fontWeight = fontWeight
        )
        Column(
          modifier = Modifier.padding(start = 1.dp),
          horizontalAlignment = Alignment.Start
        ) {
          if (node.upper != null) {
            RenderMathNode(node.upper, color, boundSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
          }
          if (node.lower != null) {
            RenderMathNode(node.lower, color, boundSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
          }
        }
      }
    }

    is MathTypesetter.MathNode.Matrix -> {
      val cellSize = fontSize * 0.9f
      Row(
        modifier = modifier
          .padding(horizontal = 3.dp, vertical = 2.dp)
          .drawBehind {
            val s = 1.4.dp.toPx()
            val arm = 5.dp.toPx()
            val h = size.height
            val w = size.width
            // Left bracket [
            drawLine(color, Offset(arm, 0f), Offset(0f, 0f), s)
            drawLine(color, Offset(0f, 0f), Offset(0f, h), s)
            drawLine(color, Offset(0f, h), Offset(arm, h), s)
            // Right bracket ]
            drawLine(color, Offset(w - arm, 0f), Offset(w, 0f), s)
            drawLine(color, Offset(w, 0f), Offset(w, h), s)
            drawLine(color, Offset(w, h), Offset(w - arm, h), s)
          }
          .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          for (r in node.rows) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              for (cell in r) {
                Box(contentAlignment = Alignment.Center) {
                  RenderMathNode(cell, color, cellSize, fontFamily, fontStyle, fontWeight, isTopLevel = false)
                }
              }
            }
          }
        }
      }
    }
  }
}

private fun splitKeepingSpaces(text: String): List<String> {
  val out = ArrayList<String>()
  var i = 0
  while (i < text.length) {
    val start = i
    while (i < text.length && text[i] != ' ') i++
    while (i < text.length && text[i] == ' ') i++
    out += text.substring(start, i)
  }
  return out
}
