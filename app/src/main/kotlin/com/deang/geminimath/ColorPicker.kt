package com.deang.geminimath

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** sRGB helpers (0xAARRGGBB longs). */
object Srgb {
  fun r(c: Long) = ((c shr 16) and 0xFF).toInt()
  fun g(c: Long) = ((c shr 8) and 0xFF).toInt()
  fun b(c: Long) = (c and 0xFF).toInt()
  fun of(r: Int, g: Int, b: Int): Long = 0xFF000000L or (r.coerceIn(0, 255).toLong() shl 16) or (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()
  fun hex(c: Long) = "%06X".format(c and 0xFFFFFF)
  fun parseHex(s: String): Long? = s.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { it or 0xFF000000L }
  fun hsv(h: Float, s: Float, v: Float): Long = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL
  fun toHsv(c: Long): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toInt(), it) }
  fun hsl(h: Float, s: Float, l: Float): Long =
    androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, l)).toLong() and 0xFFFFFFFFL
}

/** Samsung's swatch grid: a grey column, then 12 hues from light tints (top) to dark shades. */
private val SwatchHues = listOf(0f, 25f, 42f, 56f, 85f, 140f, 165f, 185f, 212f, 232f, 268f, 300f)
private const val SWATCH_ROWS = 13
private fun swatch(col: Int, row: Int): Long {
  val l = 0.94f - row * (0.86f / (SWATCH_ROWS - 1))
  return if (col == 0) Srgb.hsl(0f, 0f, (1f - row / (SWATCH_ROWS - 1f)).coerceIn(0f, 1f))
  else Srgb.hsl(SwatchHues[col - 1], if (row < 2) 0.9f else 1f, l)
}

/**
 * Samsung Notes' colour menu: Swatches or Spectrum, the old and new colour side by side, Hex and
 * Red/Green/Blue fields you can type into, your recent colours and an eyedropper.
 */
@Composable
fun SamsungColorPicker(
  initial: Long,
  recent: List<Long>,
  onDone: (Long) -> Unit,
  onCancel: () -> Unit,
  onEyedropper: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var color by remember { mutableStateOf(initial or 0xFF000000L) }
  var spectrum by remember { mutableStateOf(false) }
  val hsv = remember(color) { Srgb.toHsv(color) }
  // Spectrum keeps hue/saturation while brightness moves
  var hue by remember { mutableStateOf(hsv[0]) }
  var sat by remember { mutableStateOf(hsv[1]) }
  var bright by remember { mutableStateOf(hsv[2]) }
  fun setFromSpectrum() { color = Srgb.hsv(hue, sat, bright) }
  fun setColor(c: Long) { color = c; Srgb.toHsv(c).let { hue = it[0]; sat = it[1]; bright = it[2] } }

  val txt = EditorChrome.text
  val sub = EditorChrome.secondaryText
  Column(modifier.width(400.dp).padding(horizontal = 26.dp, vertical = 22.dp)) {
    // Tabs
    Row(Modifier.fillMaxWidth().height(46.dp)) {
      listOf(false to "Swatches", true to "Spectrum").forEach { (isSpec, label) ->
        val sel = spectrum == isSpec
        Box(
          Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(23.dp))
            .background(if (sel) EditorChrome.selected else Color.Transparent)
            .clickable { spectrum = isSpec }
            .semantics { selected = sel },
          contentAlignment = Alignment.Center
        ) { Text(label, fontSize = 16.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) txt else sub) }
      }
    }
    Spacer(Modifier.height(22.dp))

    if (!spectrum) {
      // Swatches
      BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(13f / SWATCH_ROWS)) {
        val cols = 13
        val cw = constraints.maxWidth / cols.toFloat()
        val ch = constraints.maxHeight / SWATCH_ROWS.toFloat()
        Canvas(
          Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
              fun pick(p: Offset) {
                val c = (p.x / cw).toInt().coerceIn(0, cols - 1); val r = (p.y / ch).toInt().coerceIn(0, SWATCH_ROWS - 1)
                setColor(swatch(c, r))
              }
              detectTapGestures { pick(it) }
            }
            .pointerInput(Unit) {
              detectDragGestures { ch2, _ ->
                ch2.consume()
                val c = (ch2.position.x / cw).toInt().coerceIn(0, cols - 1); val r = (ch2.position.y / ch).toInt().coerceIn(0, SWATCH_ROWS - 1)
                setColor(swatch(c, r))
              }
            }
            .semantics { contentDescription = "Colour swatches" }
        ) {
          var selRect: Pair<Offset, Size>? = null
          for (c in 0 until cols) for (r in 0 until SWATCH_ROWS) {
            val sw = swatch(c, r)
            val tl = Offset(c * cw, r * ch)
            drawRect(Color(sw), tl, Size(cw + 0.5f, ch + 0.5f))
            if (sw == color) selRect = tl to Size(cw, ch)
          }
          selRect?.let { (tl, sz) ->
            drawRoundRect(Color(color), tl - Offset(4f, 4f), Size(sz.width + 8f, sz.height + 8f), CornerRadius(8f))
            drawRoundRect(Color.White, tl - Offset(4f, 4f), Size(sz.width + 8f, sz.height + 8f), CornerRadius(8f), style = Stroke(4f))
          }
        }
      }
    } else {
      // Spectrum: hue across, saturation down; brightness slider below
      BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.27f).clip(RoundedCornerShape(6.dp))) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        fun at(p: Offset) { hue = (p.x / w).coerceIn(0f, 1f) * 360f; sat = (p.y / h).coerceIn(0f, 1f); setFromSpectrum() }
        Canvas(
          Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { at(it) } }
            .pointerInput(Unit) { detectDragGestures { c, _ -> c.consume(); at(c.position) } }
            .semantics { contentDescription = "Colour spectrum" }
        ) {
          drawRect(Brush.horizontalGradient((0..12).map { Color(Srgb.hsv(it * 30f, 1f, 1f)) }))
          drawRect(Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0f))))
          drawRect(Color.Black.copy(alpha = 1f - bright))
          val c = Offset(hue / 360f * size.width, sat * size.height)
          drawCircle(Color(color), 26f, c)
          drawCircle(Color.White, 26f, c, style = Stroke(5f))
        }
      }
      Spacer(Modifier.height(22.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f).height(26.dp)) {
          val w = constraints.maxWidth.toFloat()
          fun at(x: Float) { bright = (x / w).coerceIn(0f, 1f); setFromSpectrum() }
          Canvas(
            Modifier
              .fillMaxSize()
              .pointerInput(Unit) { detectTapGestures { at(it.x) } }
              .pointerInput(Unit) { detectDragGestures { c, _ -> c.consume(); at(c.position.x) } }
              .semantics { contentDescription = "Brightness" }
          ) {
            drawRoundRect(Brush.horizontalGradient(listOf(Color.Black, Color(Srgb.hsv(hue, sat, 1f)))), cornerRadius = CornerRadius(size.height / 2f))
            val x = (bright * size.width).coerceIn(size.height / 2f, size.width - size.height / 2f)
            drawCircle(Color.White, size.height / 3f, Offset(x, size.height / 2f))
          }
        }
        Text("${(bright * 100).roundToInt()}%", fontSize = 15.sp, color = sub, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
      }
    }

    Spacer(Modifier.height(22.dp))
    // Old | new, then Hex and R G B
    Row(verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.width(66.dp).height(42.dp).clip(RoundedCornerShape(8.dp))) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(initial or 0xFF000000L)))
        Box(Modifier.width(1.5.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.7f)))
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(color)))
      }
      Spacer(Modifier.width(14.dp))
      ValueField("Hex", "#" + Srgb.hex(color), Modifier.weight(1.5f), hex = true) { t -> Srgb.parseHex(t)?.let { setColor(it) } }
      ValueField("Red", Srgb.r(color).toString(), Modifier.weight(1f)) { t -> t.toIntOrNull()?.let { setColor(Srgb.of(it, Srgb.g(color), Srgb.b(color))) } }
      ValueField("Green", Srgb.g(color).toString(), Modifier.weight(1f)) { t -> t.toIntOrNull()?.let { setColor(Srgb.of(Srgb.r(color), it, Srgb.b(color))) } }
      ValueField("Blue", Srgb.b(color).toString(), Modifier.weight(1f)) { t -> t.toIntOrNull()?.let { setColor(Srgb.of(Srgb.r(color), Srgb.g(color), it)) } }
    }
    Spacer(Modifier.height(16.dp))
    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
      drawLine(EditorChrome.divider, Offset(0f, 1f), Offset(size.width, 1f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
    }
    Spacer(Modifier.height(16.dp))
    // Recent colours (5 slots) and the eyedropper
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      val slots = recent.take(5)
      repeat(5) { i ->
        val c = slots.getOrNull(i)
        Box(
          Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (c != null) Color(c) else if (EditorChrome.dark) Color(0xFF3A3A3E) else Color(0xFFF0F0F2))
            .border(if (c != null && c == color) 2.5.dp else 0.dp, EditorChrome.text, CircleShape)
            .clickable(enabled = c != null) { c?.let { setColor(it) } }
            .semantics { contentDescription = if (c != null) "Recent colour #${Srgb.hex(c)}" else "Empty" }
        )
      }
      if (onEyedropper != null) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { onEyedropper() }.semantics { contentDescription = "Pick a colour from the page" }, contentAlignment = Alignment.Center) {
          Canvas(Modifier.size(22.dp)) {
            // Eyedropper: a slanted pipette
            drawLine(EditorChrome.text, Offset(size.width * 0.18f, size.height * 0.82f), Offset(size.width * 0.62f, size.height * 0.38f), 3.2f)
            drawCircle(EditorChrome.text, size.width * 0.17f, Offset(size.width * 0.72f, size.height * 0.28f))
            drawCircle(EditorChrome.text, 2.2f, Offset(size.width * 0.16f, size.height * 0.84f))
          }
        }
      }
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).clickable { onCancel() }, contentAlignment = Alignment.Center) {
        Text("Cancel", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = txt)
      }
      Box(Modifier.width(1.dp).height(18.dp).background(EditorChrome.divider))
      Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).clickable { onDone(color) }, contentAlignment = Alignment.Center) {
        Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = txt)
      }
    }
  }
}

/** A small label over an editable value (Samsung's Hex / Red / Green / Blue). */
@Composable
private fun ValueField(label: String, value: String, modifier: Modifier, hex: Boolean = false, onCommit: (String) -> Unit) {
  var text by remember(value) { mutableStateOf(value) }
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, fontSize = 13.sp, color = EditorChrome.secondaryText)
    BasicTextField(
      value = text,
      onValueChange = { raw ->
        val t = if (hex) "#" + raw.removePrefix("#").uppercase().filter { it in "0123456789ABCDEF" }.take(6)
        else raw.filter { it.isDigit() }.take(3)
        text = t
        if (hex && t.length == 7) onCommit(t)
        if (!hex && t.isNotEmpty() && t.toInt() <= 255) onCommit(t)
      },
      singleLine = true,
      textStyle = TextStyle(fontSize = 16.sp, color = EditorChrome.text, textAlign = TextAlign.Center),
      cursorBrush = SolidColor(OneUi9Tokens.SamsungBlue),
      keyboardOptions = if (hex) KeyboardOptions(capitalization = KeyboardCapitalization.Characters) else KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = label }
    )
  }
}
