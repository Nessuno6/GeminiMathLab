package com.deang.geminimath

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** One line of the calculator: what's typed and where the cursor is. */
private class CalcRow(text: String, val colorIndex: Int) {
  var text by mutableStateOf(text)
  var cursor by mutableStateOf(text.length)
  /** The expression the engine reads, or null when it can't be read. */
  val expr: String? get() = GraphRender.normalise(text)
}

/** A calculator key: [label] on the key, [insert] typed before the cursor and [after] typed after it. */
private data class CalcKey(val label: String, val insert: String = label, val after: String = "", val wide: Boolean = false)

private val Keys = listOf(
  listOf(CalcKey("x"), CalcKey("y"), CalcKey("a²", "^2"), CalcKey("aᵇ", "^(", ")"), CalcKey("√", "√(", ")"), CalcKey("∛", "∛(", ")"), CalcKey("π"), CalcKey("e")),
  listOf(CalcKey("7"), CalcKey("8"), CalcKey("9"), CalcKey("÷"), CalcKey("("), CalcKey(")"), CalcKey("sin", "sin(", ")"), CalcKey("cos", "cos(", ")")),
  listOf(CalcKey("4"), CalcKey("5"), CalcKey("6"), CalcKey("×"), CalcKey("|a|", "abs(", ")"), CalcKey("eˣ", "e^(", ")"), CalcKey("tan", "tan(", ")"), CalcKey("ln", "ln(", ")")),
  listOf(CalcKey("1"), CalcKey("2"), CalcKey("3"), CalcKey("−"), CalcKey("="), CalcKey("log", "log(", ")"), CalcKey("sin⁻¹", "arcsin(", ")"), CalcKey("cos⁻¹", "arccos(", ")")),
  // Piecewise, as in Desmos: {x<0: −x, x≥0: x}
  listOf(CalcKey("{ }", "{", "}", wide = true), CalcKey(":", ": "), CalcKey(",", ", "), CalcKey("<"), CalcKey(">"), CalcKey("≤"), CalcKey("≥")),
  listOf(CalcKey("0"), CalcKey("."), CalcKey("+"), CalcKey("←"), CalcKey("→"), CalcKey("⌫"), CalcKey("↵", wide = true))
)

/**
 * A Desmos-style graphing calculator. Type functions with the keyboard of maths keys; each one
 * is drawn in its own colour. Drag to move the graph, pinch to zoom, tap a curve to read a point.
 * "Add to note" puts the current view in the note as a picture.
 */
@Composable
fun GraphingCalculatorScreen(
  dark: Boolean,
  initial: List<String>,
  onClose: () -> Unit,
  onInsert: (Bitmap) -> Unit
) {
  val bg = if (dark) Color(0xFF000000) else Color(0xFFF6F6F8)
  val panel = if (dark) Color(0xFF17171A) else Color.White
  val text = if (dark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
  val muted = if (dark) Color(0xFF9A9AA0) else Color(0xFF6E6E73)
  val keyBg = if (dark) Color(0xFF2A2A2E) else Color(0xFFE9E9EE)
  val fnKeyBg = if (dark) Color(0xFF34343A) else Color(0xFFDCDCE3)
  val blue = OneUi9Tokens.SamsungBlue

  val rows = remember { mutableStateListOf<CalcRow>().apply { (initial.ifEmpty { listOf("") }).forEachIndexed { i, t -> add(CalcRow(t, i)) } } }
  var focused by remember { mutableStateOf(rows.lastIndex) }
  var window by remember { mutableStateOf<GraphWindow?>(null) }
  var trace by remember { mutableStateOf<Pair<Double, Double>?>(null) }
  var traceColor by remember { mutableStateOf(0) }
  var viewSize by remember { mutableStateOf(1f to 1f) }

  fun curves() = rows.mapNotNull { r -> r.expr?.let { GraphCurve(it, GraphRender.CurveColors[r.colorIndex % GraphRender.CurveColors.size]) } }

  fun press(k: CalcKey) {
    val row = rows.getOrNull(focused) ?: return
    when (k.label) {
      "←" -> row.cursor = (row.cursor - 1).coerceAtLeast(0)
      "→" -> row.cursor = (row.cursor + 1).coerceAtMost(row.text.length)
      "⌫" -> if (row.cursor > 0) {
        // Delete a whole function name with its bracket ("sin(" → ""), else one character
        val before = row.text.substring(0, row.cursor)
        val fn = Regex("""(arcsin|arccos|sin|cos|tan|log|ln|abs|e\^|√|∛|\^)\($""").find(before)
        val cut = fn?.value?.length ?: 1
        val closes = fn != null && row.text.getOrNull(row.cursor) == ')'
        row.text = before.dropLast(cut) + row.text.substring(row.cursor + if (closes) 1 else 0)
        row.cursor -= cut
      } else if (row.text.isEmpty() && rows.size > 1) {
        rows.removeAt(focused); focused = (focused - 1).coerceAtLeast(0)
      }
      "↵" -> {
        val next = CalcRow("", (rows.maxOfOrNull { it.colorIndex } ?: -1) + 1)
        rows.add(focused + 1, next); focused += 1
      }
      else -> {
        row.text = row.text.substring(0, row.cursor) + k.insert + k.after + row.text.substring(row.cursor)
        row.cursor += k.insert.length
      }
    }
    trace = null
  }

  @Composable
  fun ExpressionList(modifier: Modifier) {
    LazyColumn(modifier) {
      itemsIndexed(rows, key = { _, r -> r.hashCode() }) { i, r ->
        val color = Color(GraphRender.CurveColors[r.colorIndex % GraphRender.CurveColors.size])
        val isFocused = i == focused
        val expr = r.expr
        Row(
          Modifier
            .fillMaxWidth()
            .background(if (isFocused) blue.copy(alpha = if (dark) 0.16f else 0.07f) else Color.Transparent)
            .clickable { focused = i; r.cursor = r.text.length }
            .padding(horizontal = 14.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(Modifier.size(14.dp).clip(CircleShape).background(if (expr != null || r.text.isBlank()) color else color.copy(alpha = 0.3f)))
          Spacer(Modifier.width(12.dp))
          Text(
            buildAnnotatedString {
              val shown = r.text
              append(shown.substring(0, r.cursor.coerceIn(0, shown.length)))
              if (isFocused) withStyle(SpanStyle(color = blue, fontWeight = FontWeight.Bold)) { append("|") }
              append(shown.substring(r.cursor.coerceIn(0, shown.length)))
              if (shown.isEmpty() && !isFocused) withStyle(SpanStyle(color = muted)) { append("Type a function, e.g. x²") }
            },
            color = text, fontSize = 20.sp, fontFamily = FontFamily.Serif,
            modifier = Modifier.weight(1f)
          )
          // A constant shows its value; something unreadable is flagged
          val value = expr?.let { e -> LocalMathEngine.parse(e)?.takeIf { !it.hasX() }?.let { MathFormat.num(LocalMathEngine.evalAt(e, 0.0)) } }
          when {
            value != null -> Text("= $value", color = muted, fontSize = 16.sp)
            expr == null && r.text.isNotBlank() -> Text("?", color = Color(0xFFD32F2F), fontSize = 18.sp, fontWeight = FontWeight.Bold,
              modifier = Modifier.semantics { contentDescription = "Can't read this" })
          }
          if (rows.size > 1) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).clickable {
              rows.removeAt(i); focused = focused.coerceAtMost(rows.lastIndex)
            }.semantics { contentDescription = "Delete expression" }, contentAlignment = Alignment.Center) {
              MyScriptIcons.CloseX(color = muted, size = 12.dp)
            }
          }
        }
      }
      item {
        Text(
          "+  Add expression", color = blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
          modifier = Modifier.fillMaxWidth().clickable {
            rows.add(CalcRow("", (rows.maxOfOrNull { it.colorIndex } ?: -1) + 1)); focused = rows.lastIndex
          }.padding(horizontal = 18.dp, vertical = 14.dp)
        )
      }
    }
  }

  @Composable
  fun RowScope.Key(k: CalcKey, height: Int) {
    val isFn = k.label.length > 1 || k.label in listOf("√", "∛", "π", "e", "÷", "×", "−", "+", "=", "(", ")")
    val isAction = k.label in listOf("←", "→", "⌫", "↵")
    Box(
      Modifier
        .weight(if (k.wide) 2f else 1f)
        .height(height.dp)
        .padding(3.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(if (k.label == "↵") blue else if (isAction || isFn) fnKeyBg else keyBg)
        .clickable { press(k) }
        .semantics { contentDescription = when (k.label) { "⌫" -> "Delete"; "↵" -> "New expression"; "←" -> "Cursor left"; "→" -> "Cursor right"; else -> k.label } },
      contentAlignment = Alignment.Center
    ) {
      Text(k.label, color = if (k.label == "↵") Color.White else text, fontSize = if (k.label.length > 3) 14.sp else 18.sp, fontFamily = if (k.label.length == 1 && k.label[0].isLetter()) FontFamily.Serif else FontFamily.Default)
    }
  }

  @Composable
  fun Keyboard(keyHeight: Int) {
    Column(Modifier.fillMaxWidth().background(panel).padding(6.dp)) {
      for (r in Keys) Row(Modifier.fillMaxWidth()) { for (k in r) Key(k, keyHeight) }
    }
  }

  @Composable
  fun Graph(modifier: Modifier) {
    val density = LocalDensity.current.density
    Box(modifier.clipToBounds()) {
      Canvas(
        Modifier
          .fillMaxSize()
          .onSizeChanged { s ->
            val w = s.width.toFloat().coerceAtLeast(1f); val h = s.height.toFloat().coerceAtLeast(1f)
            viewSize = w to h
            // Keep the x range when the view is resized; fit y to the new shape
            window = window?.let { old ->
              val cy = (old.yMin + old.yMax) / 2; val halfH = old.width / 2 * h / w
              GraphWindow(old.xMin, old.xMax, cy - halfH, cy + halfH)
            } ?: GraphWindow.standard(w, h)
          }
          .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
              val w = size.width.toFloat(); val h = size.height.toFloat()
              var win = window ?: return@detectTransformGestures
              if (zoom != 1f) win = win.zoom(zoom, centroid.x, centroid.y, w, h)
              window = win.pan(pan.x, pan.y, w, h)
            }
          }
          .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { p ->
              // Double-tap zooms in where you tap
              window = window?.zoom(2f, p.x, p.y, size.width.toFloat(), size.height.toFloat())
              trace = null
            }) { p ->
              val win = window ?: return@detectTapGestures
              val w = size.width.toFloat(); val h = size.height.toFloat()
              val x = win.xMin + p.x / w * win.width
              // The curve nearest the tap at that x
              var best: Pair<Double, Double>? = null; var bestD = Float.MAX_VALUE; var bestC = 0
              for (c in curves()) {
                val y = LocalMathEngine.evalAt(c.expr, x, MathSettings.angleDegrees)
                if (!y.isFinite()) continue
                val sy = ((win.yMax - y) / win.height * h).toFloat()
                val d = abs(sy - p.y)
                if (d < bestD) { bestD = d; best = x to y; bestC = c.argb }
              }
              trace = if (best != null && bestD < 48 * density) best else null
              traceColor = bestC
            }
          }
      ) {
        val w = size.width; val h = size.height
        val win = window ?: return@Canvas
        drawIntoCanvas { GraphRender.draw(it.nativeCanvas, w, h, win, curves(), dark, density) }
        trace?.let { (tx, ty) ->
          val px = ((tx - win.xMin) / win.width * w).toFloat(); val py = ((win.yMax - ty) / win.height * h).toFloat()
          drawCircle(Color(traceColor), 7 * density, Offset(px, py))
          drawCircle(Color.White, 3.5f * density, Offset(px, py))
          drawIntoCanvas { c ->
            val label = "(${MathFormat.num(tx)}, ${MathFormat.num(ty)})"
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
              textSize = 14 * density; color = if (dark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
            }
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xE0303034.toInt() else 0xF0FFFFFF.toInt() }
            val tw = paint.measureText(label)
            val lx = (px + 12 * density).coerceAtMost(w - tw - 16 * density); val ly = (py - 14 * density).coerceAtLeast(24 * density)
            c.nativeCanvas.drawRoundRect(lx - 8 * density, ly - 18 * density, lx + tw + 8 * density, ly + 8 * density, 8 * density, 8 * density, bgPaint)
            c.nativeCanvas.drawText(label, lx, ly, paint)
          }
        }
      }
      // Zoom and reset buttons
      Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, desc) in listOf("+" to "Zoom in", "−" to "Zoom out", "⌂" to "Reset view")) {
          Surface(shape = CircleShape, color = panel, shadowElevation = 3.dp, modifier = Modifier.size(40.dp).semantics { contentDescription = desc }.clickable {
            val (w, h) = viewSize
            window = when (label) {
              "+" -> window?.zoom(1.5f, w / 2, h / 2, w, h)
              "−" -> window?.zoom(1 / 1.5f, w / 2, h / 2, w, h)
              else -> GraphWindow.standard(w, h)
            }
          }) { Box(contentAlignment = Alignment.Center) { Text(label, color = text, fontSize = 20.sp) } }
        }
      }
    }
  }

  BoxWithConstraints(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.systemBars)) {
    val wide = maxWidth >= 700.dp
    Column(Modifier.fillMaxSize()) {
      // Header
      Row(
        Modifier.fillMaxWidth().height(56.dp).background(panel).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose).semantics { contentDescription = "Close calculator" }, contentAlignment = Alignment.Center) {
          MyScriptIcons.BackArrow(color = text, size = 18.dp)
        }
        Text("Graphing calculator", color = text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
        Box(
          Modifier.clip(RoundedCornerShape(20.dp)).background(blue)
            .clickable {
              val win = window ?: return@clickable
              val (w, h) = viewSize
              val outW = 1600; val outH = (1600 * h / w.coerceAtLeast(1f)).toInt().coerceIn(600, 2400)
              onInsert(GraphRender.bitmap(curves(), win, outW, outH, dark))
            }
            .padding(horizontal = 16.dp, vertical = 9.dp)
        ) { Text("Add to note", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(8.dp))
      }
      if (wide) {
        Row(Modifier.fillMaxSize()) {
          Column(Modifier.width(400.dp).fillMaxHeight().background(panel).border(0.5.dp, keyBg)) {
            ExpressionList(Modifier.weight(1f).fillMaxWidth())
            Keyboard(keyHeight = 56)
          }
          Graph(Modifier.weight(1f).fillMaxHeight())
        }
      } else {
        Graph(Modifier.weight(1f).fillMaxWidth())
        ExpressionList(Modifier.fillMaxWidth().heightIn(max = 170.dp).background(panel))
        Keyboard(keyHeight = 46)
      }
    }
  }
}
