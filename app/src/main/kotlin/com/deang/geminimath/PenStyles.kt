package com.deang.geminimath

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Samsung Notes pen types. The ink looks different for each (see [drawInkStroke]). */
enum class PenType(val label: String, @DrawableRes val body: Int, @DrawableRes val effect: Int, @DrawableRes val bandOn: Int, @DrawableRes val bandOff: Int) {
  FOUNTAIN("Fountain pen", R.drawable.pentype_fountain_body, R.drawable.pentype_fountain_effect, R.drawable.pentype_fountain_band_selected, R.drawable.pentype_fountain_band_unselected),
  CALLIGRAPHY("Calligraphy pen", R.drawable.pentype_calligraphy_body, R.drawable.pentype_calligraphy_effect, R.drawable.pentype_calligraphy_band_selected, R.drawable.pentype_calligraphy_band_unselected),
  PEN("Pen", R.drawable.pentype_pen_body, R.drawable.pentype_pen_effect, R.drawable.pentype_pen_band_selected, R.drawable.pentype_pen_band_unselected),
  PENCIL("Pencil", R.drawable.pentype_pencil_body, R.drawable.pentype_pencil_effect, R.drawable.pentype_pencil_band_selected, R.drawable.pentype_pencil_band_unselected),
  BRUSH("Brush", R.drawable.pentype_brush_body, R.drawable.pentype_brush_effect, R.drawable.pentype_brush_band_selected, R.drawable.pentype_brush_band_unselected)
}

/** Samsung Notes line styles. */
enum class LineStyle(val label: String) { SOLID("Solid"), TAPERED("Tapered"), DASHED("Dashed"), DOTTED("Dotted") }

/** Samsung Notes' palette (first page). */
val SamsungPalette = listOf(
  0xFFFF3B30L, 0xFFFFC233L, 0xFFF7E600L, 0xFF2EE6CFL, 0xFF3399FFL,
  0xFF1F4EFFL, 0xFF9B30FFL, 0xFF262626L
)

/** Pen sizes 1–100 on the slider, in page pixels: each step is the same 0.25 px, from 0.75 to 25.5. */
const val PEN_SIZE_STEPS = 100
fun sliderToWidth(step: Int): Float = 0.5f + step.coerceIn(1, PEN_SIZE_STEPS) * 0.25f
fun widthToSlider(width: Float): Int = ((width - 0.5f) / 0.25f).roundToInt().coerceIn(1, PEN_SIZE_STEPS)

/**
 * Draws one stroke in the look of its pen type and line style:
 * pen = even line; fountain = thinner when fast; calligraphy = flat 45° nib; pencil = grainy and
 * lighter; brush = wide and soft. Line styles: tapered ends, dashes or dots.
 */
fun DrawScope.drawInkStroke(s: InkStroke, color: Color) {
  val pts = s.points
  if (pts.isEmpty()) return
  val alpha = when (s.penType) { PenType.PENCIL -> 0.78f; PenType.BRUSH -> 0.55f; else -> 1f }
  val col = color.copy(alpha = color.alpha * alpha)
  val baseW = if (s.penType == PenType.BRUSH) s.width * 1.6f else s.width
  if (pts.size == 1) {
    drawCircle(col, radius = baseW / 2f, center = Offset(pts[0].x, pts[0].y))
    return
  }
  val variable = s.penType == PenType.FOUNTAIN || s.penType == PenType.CALLIGRAPHY || s.lineStyle == LineStyle.TAPERED
  if (!variable) {
    // Smooth curves through the pen's points (each point is a control point, the curve runs through
    // the midpoints between them), so fast writing doesn't show corners between samples
    val path = Path().apply {
      moveTo(pts[0].x, pts[0].y)
      if (pts.size == 2 || s.isTape) for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
      else {
        // A point with a long side next to it is a real corner (a shape, a straightened line):
        // it's drawn sharp, not rounded off
        val longSide = baseW * 2.5f + 10f
        for (i in 1 until pts.size - 1) {
          val o = pts[i - 1]; val p = pts[i]; val q = pts[i + 1]
          if (hypot(p.x - o.x, p.y - o.y) > longSide || hypot(q.x - p.x, q.y - p.y) > longSide) lineTo(p.x, p.y)
          else quadraticTo(p.x, p.y, (p.x + q.x) / 2f, (p.y + q.y) / 2f)
        }
        lineTo(pts.last().x, pts.last().y)
      }
    }
    val effect = when (s.lineStyle) {
      LineStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(baseW * 3f, baseW * 2.4f))
      LineStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(0.01f, baseW * 2.2f))
      else -> null
    }
    // A chisel highlighter has flat ends
    val cap = if (s.isHighlighter && s.chisel) StrokeCap.Butt else StrokeCap.Round
    drawPath(path, col, style = Stroke(width = baseW, cap = cap, join = StrokeJoin.Round, pathEffect = effect))
    return
  }
  // Variable width: draw segment by segment
  val n = pts.size - 1
  var prevW = baseW
  for (i in 1..n) {
    val a = pts[i - 1]; val b = pts[i]
    var w = baseW
    when (s.penType) {
      PenType.FOUNTAIN -> {
        val dt = (b.t - a.t).coerceAtLeast(1L).toFloat()
        val speed = hypot(b.x - a.x, b.y - a.y) / dt
        w = baseW * (1.25f - speed * 0.35f).coerceIn(0.45f, 1.25f)
      }
      PenType.CALLIGRAPHY -> {
        val angle = atan2(b.y - a.y, b.x - a.x)
        w = baseW * (0.28f + 0.95f * abs(sin(angle - PI.toFloat() / 4f)))
      }
      else -> {}
    }
    if (s.lineStyle == LineStyle.TAPERED) {
      val t = i / n.toFloat()
      w *= minOf(1f, t * 5f, (1f - t) * 5f + 0.15f).coerceAtLeast(0.2f)
    }
    w = prevW * 0.6f + w * 0.4f // smooth width changes
    prevW = w
    drawLine(col, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = w, cap = StrokeCap.Round)
  }
}

/** One Samsung pen illustration: white body, a colour band in the pen colour, then its details. */
@Composable
fun PenIllustration(type: PenType, selected: Boolean, color: Color, modifier: Modifier = Modifier) {
  Box(modifier) {
    Image(painterResource(type.body), null, Modifier.matchParentSize())
    Icon(painterResource(if (selected) type.bandOn else type.bandOff), null, tint = color, modifier = Modifier.matchParentSize())
    Image(painterResource(type.effect), null, Modifier.matchParentSize())
  }
}

private val PanelText get() = EditorChrome.text

/**
 * Samsung Notes' pen settings: pen types, size, line style, Straighten lines and colours.
 * Used on the tablet (tap the selected pen) and as the top of the phone toolbar.
 */
@Composable
fun PenSettingsPanel(
  penType: PenType,
  lineStyle: LineStyle,
  width: Float,
  color: Long,
  straighten: Boolean,
  customColors: List<Long>,
  onPenType: (PenType) -> Unit,
  onLineStyle: (LineStyle) -> Unit,
  onWidth: (Float) -> Unit,
  onColor: (Long) -> Unit,
  onStraighten: (Boolean) -> Unit,
  onAddCustomColor: (Long) -> Unit,
  modifier: Modifier = Modifier,
  /** Smaller pens and controls (phone toolbar). */
  compact: Boolean = false,
  /** Stroke stabiliser 0–10; the row is hidden when [onStabilizer] is null. */
  stabilizer: Int = 0,
  onStabilizer: ((Int) -> Unit)? = null
) {
  var showPicker by remember { mutableStateOf(false) }
  Column(modifier.padding(horizontal = if (compact) 16.dp else 22.dp, vertical = if (compact) 12.dp else 18.dp)) {
    // Pen types, the selected one raised
    Row(
      Modifier.fillMaxWidth().height(if (compact) 124.dp else 170.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.Bottom
    ) {
      PenType.entries.forEach { t ->
        val sel = t == penType
        Box(
          Modifier
            .width(46.dp)
            .height(if (compact) (if (sel) 118.dp else 94.dp) else (if (sel) 160.dp else 128.dp))
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .clickable { onPenType(t) }
            .semantics { contentDescription = t.label; selected = sel },
          contentAlignment = Alignment.BottomCenter
        ) {
          PenIllustration(t, sel, Color(color), Modifier.width(if (compact) 28.dp else 36.dp).height(if (compact) (if (sel) 110.dp else 86.dp) else (if (sel) 150.dp else 118.dp)))
        }
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(EditorChrome.divider))
    Spacer(Modifier.height(18.dp))

    // Size: − slider +
    val step = widthToSlider(width)
    Row(verticalAlignment = Alignment.CenterVertically) {
      RepeatButton("Thinner", onStep = { onWidth(sliderToWidth(step - 1)) }) {
        MyScriptIcons.Minus(PanelText, 20.dp)
      }
      SizeSlider(step, onStep = { onWidth(sliderToWidth(it)) }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), max = PEN_SIZE_STEPS)
      RepeatButton("Thicker", onStep = { onWidth(sliderToWidth(step + 1)) }) {
        MyScriptIcons.Plus(PanelText, 20.dp)
      }
    }
    Spacer(Modifier.height(14.dp))

    // Line styles
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      LineStyle.entries.forEach { ls ->
        val sel = ls == lineStyle
        Box(
          Modifier
            .weight(1f)
            .height(if (compact) 36.dp else 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (sel) EditorChrome.selected.copy(alpha = 1f) else if (EditorChrome.dark) Color(0xFF2A2A2D) else Color(0xFFF3F3F5))
            .clickable { onLineStyle(ls) }
            .semantics { contentDescription = "${ls.label} line"; selected = sel },
          contentAlignment = Alignment.Center
        ) {
          Canvas(Modifier.width(48.dp).height(10.dp)) {
            val y = size.height / 2f
            val c = PanelText
            when (ls) {
              LineStyle.SOLID -> drawLine(c, Offset(0f, y), Offset(size.width, y), 7f, StrokeCap.Round)
              LineStyle.TAPERED -> {
                val p = Path().apply { moveTo(0f, y); lineTo(size.width * 0.5f, y - 3f); lineTo(size.width, y); lineTo(size.width * 0.5f, y + 3f); close() }
                drawPath(p, c)
              }
              LineStyle.DASHED -> drawLine(c, Offset(0f, y), Offset(size.width, y), 7f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(28f, 18f)))
              LineStyle.DOTTED -> drawLine(c, Offset(4f, y), Offset(size.width, y), 7f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(0.01f, 15f)))
            }
          }
        }
      }
    }
    Spacer(Modifier.height(10.dp))

    // Straighten lines
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Straighten lines", fontSize = if (compact) 15.sp else 17.sp, color = PanelText, modifier = Modifier.weight(1f))
      Switch(
        checked = straighten,
        onCheckedChange = onStraighten,
        colors = SwitchDefaults.colors(checkedTrackColor = OneUi9Tokens.SamsungBlue, uncheckedTrackColor = Color(0xFFD5D5D9), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent)
      )
    }
    if (onStabilizer != null) {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Stabiliser", fontSize = if (compact) 15.sp else 17.sp, color = PanelText, modifier = Modifier.width(if (compact) 92.dp else 110.dp))
        androidx.compose.material3.Slider(
          value = stabilizer.toFloat(),
          onValueChange = { onStabilizer(it.roundToInt()) },
          valueRange = 0f..10f,
          steps = 9,
          colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = OneUi9Tokens.SamsungBlue,
            activeTrackColor = OneUi9Tokens.SamsungBlue,
            inactiveTrackColor = if (EditorChrome.dark) Color(0xFF3A3A3E) else Color(0xFFDCDCE0)
          ),
          modifier = Modifier.weight(1f).semantics { contentDescription = "Stroke stabiliser" }
        )
        Text(if (stabilizer == 0) "Off" else stabilizer.toString(), fontSize = 14.sp, color = EditorChrome.secondaryText, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
      }
    }
    Spacer(Modifier.height(10.dp))

    // Colours: Samsung's palette, your own colours, and the full picker
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      (SamsungPalette + customColors).distinct().forEach { c -> ColorSwatch(c, c == color, if (compact) 32.dp else 38.dp) { onColor(c) } }
      // Rainbow ring: any sRGB colour or a hex code
      Box(
        Modifier
          .size(38.dp)
          .clip(CircleShape)
          .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
          .clickable { showPicker = true }
          .semantics { contentDescription = "More colours" },
        contentAlignment = Alignment.Center
      ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(EditorChrome.menu), contentAlignment = Alignment.Center) {
          Box(Modifier.size(20.dp).clip(CircleShape).background(Color(color)))
        }
      }
    }
  }
  if (showPicker) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { showPicker = false }) {
      androidx.compose.material3.Surface(shape = RoundedCornerShape(30.dp), color = EditorChrome.menu) {
        SamsungColorPicker(
          initial = color,
          recent = customColors,
          onDone = { c -> onAddCustomColor(c); onColor(c); showPicker = false },
          onCancel = { showPicker = false }
        )
      }
    }
  }
}

/**
 * Samsung Notes' "Change style" for a lasso selection: size and colour, applied to the selection
 * as you change them (shown live), kept with Done or undone with Cancel. [color] is null when the
 * selection has more than one colour.
 */
@Composable
fun ChangeStylePanel(
  step: Int, color: Long?, customColors: List<Long>,
  onStep: (Int) -> Unit, onColor: (Long) -> Unit, onAddCustomColor: (Long) -> Unit,
  onCancel: () -> Unit, onDone: () -> Unit,
  modifier: Modifier = Modifier
) {
  var showPicker by remember { mutableStateOf(false) }
  Column(modifier.padding(horizontal = 26.dp, vertical = 22.dp)) {
    Text("Change style", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = PanelText)
    Spacer(Modifier.height(26.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      RepeatButton("Thinner", onStep = { onStep((step - 1).coerceAtLeast(1)) }) { MyScriptIcons.Minus(PanelText, 20.dp) }
      SizeSlider(step, onStep = onStep, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), max = PEN_SIZE_STEPS)
      RepeatButton("Thicker", onStep = { onStep((step + 1).coerceAtMost(PEN_SIZE_STEPS)) }) { MyScriptIcons.Plus(PanelText, 20.dp) }
    }
    Spacer(Modifier.height(18.dp))
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      (SamsungPalette + customColors).distinct().forEach { c ->
        val sel = color != null && (c or 0xFF000000L) == (color or 0xFF000000L)
        // Samsung marks the chosen colour with a tick
        Box(contentAlignment = Alignment.Center) {
          ColorSwatch(c, false, 38.dp) { onColor(c) }
          if (sel) Canvas(Modifier.size(18.dp)) {
            val tick = Path().apply { moveTo(size.width * 0.12f, size.height * 0.52f); lineTo(size.width * 0.42f, size.height * 0.8f); lineTo(size.width * 0.9f, size.height * 0.22f) }
            val lum = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            val ink = if (lum < 170f) Color.White else Color(0xFF222222)
            drawPath(tick, ink, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
          }
        }
      }
      Box(
        Modifier.size(38.dp).clip(CircleShape)
          .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
          .clickable { showPicker = true }
          .semantics { contentDescription = "More colours" }
      )
    }
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
        Text("Cancel", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = PanelText)
      }
      Box(Modifier.width(1.dp).height(22.dp).background(EditorChrome.divider))
      Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
        Text("Done", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = PanelText)
      }
    }
  }
  if (showPicker) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { showPicker = false }) {
      androidx.compose.material3.Surface(shape = RoundedCornerShape(30.dp), color = EditorChrome.menu) {
        SamsungColorPicker(
          initial = color ?: 0xFF262626L,
          recent = customColors,
          onDone = { c -> onAddCustomColor(c); onColor(c); showPicker = false },
          onCancel = { showPicker = false }
        )
      }
    }
  }
}

/** Patterns printed on tape, like washi tape. */
enum class TapePattern(val label: String) { PLAIN("Plain"), STRIPES("Stripes"), DOTS("Dots"), GRID("Grid") }

/** Tape sizes 1–10 on the slider, in page pixels (size 3 is the original 34 px). */
fun tapeSliderToWidth(step: Int): Float = 10f + step * 8f

/** Samsung Notes' tape colours, plus the original masking-tape beige first. */
val TapeColors = listOf(TAPE_COLOR, 0xFFF7DF6FL, 0xFFE05566L, 0xFF84D98FL, 0xFF6F7FF0L, 0xFFD6D6D6L, 0xFF555555L, 0xFFF5B83DL, 0xFF5BC764L)

/** Prints [s]'s pattern over a tape stroke already drawn, kept inside the tape's outline. */
fun DrawScope.drawTapePattern(s: InkStroke, color: Color) {
  if (s.tapePattern == TapePattern.PLAIN || s.points.size < 2) return
  val src = android.graphics.Path().apply {
    moveTo(s.points[0].x, s.points[0].y)
    for (i in 1 until s.points.size) lineTo(s.points[i].x, s.points[i].y)
  }
  val outline = android.graphics.Path()
  android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = s.width; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    .getFillPath(src, outline)
  val b = android.graphics.RectF().also { outline.computeBounds(it, true) }
  // A darker, see-through version of the tape colour
  val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    this.color = android.graphics.Color.argb((color.alpha * 0.55f * 255).toInt(), (color.red * 150).toInt(), (color.green * 150).toInt(), (color.blue * 150).toInt())
    strokeWidth = maxOf(2f, s.width / 9f)
  }
  val step = maxOf(10f, s.width / 2.6f)
  val c = drawContext.canvas.nativeCanvas
  c.save()
  c.clipPath(outline)
  when (s.tapePattern) {
    TapePattern.STRIPES -> {
      var x = b.left - b.height()
      while (x < b.right) { c.drawLine(x, b.bottom, x + b.height(), b.top, ink); x += step }
    }
    TapePattern.DOTS -> {
      var y = b.top + step / 2; var row = 0
      while (y < b.bottom) {
        var x = b.left + (if (row % 2 == 0) step / 2 else step)
        while (x < b.right) { c.drawCircle(x, y, ink.strokeWidth * 0.9f, ink); x += step }
        y += step * 0.8f; row++
      }
    }
    TapePattern.GRID -> {
      ink.strokeWidth = maxOf(1.5f, s.width / 14f)
      var x = b.left; while (x < b.right) { c.drawLine(x, b.top, x, b.bottom, ink); x += step }
      var y = b.top; while (y < b.bottom) { c.drawLine(b.left, y, b.right, y, ink); y += step }
    }
    TapePattern.PLAIN -> {}
  }
  c.restore()
}

/**
 * Samsung Notes' tape settings: show or hide all tape, size, straighten lines, colour or pattern,
 * and remove all tape. Tap tape on the page to peel it back; tap again to cover.
 */
@Composable
fun TapeSettingsPanel(
  size: Int, color: Long, pattern: TapePattern, straighten: Boolean, allPeeled: Boolean,
  onSize: (Int) -> Unit, onColor: (Long) -> Unit, onPattern: (TapePattern) -> Unit, onStraighten: (Boolean) -> Unit,
  onTogglePeelAll: () -> Unit, onRemoveAll: () -> Unit, onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  var patternTab by remember { mutableStateOf(false) }
  Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Tape", fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = PanelText, modifier = Modifier.weight(1f))
      // Peel back all tape / cover it all again
      Box(
        Modifier.size(36.dp).clip(CircleShape).background(if (allPeeled) EditorChrome.selected else Color.Transparent)
          .clickable(onClick = onTogglePeelAll).semantics { contentDescription = if (allPeeled) "Cover all tape" else "Peel back all tape" },
        contentAlignment = Alignment.Center
      ) {
        Icon(painterResource(if (allPeeled) R.drawable.snotes_visible else R.drawable.snotes_hidden), null, tint = PanelText, modifier = Modifier.size(20.dp))
      }
      Spacer(Modifier.width(6.dp))
      Box(Modifier.size(36.dp).clip(CircleShape).background(if (EditorChrome.dark) Color(0xFF2A2A2D) else Color(0xFFF0F0F3)).clickable(onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
        MyScriptIcons.CloseX(color = PanelText, size = 14.dp)
      }
    }
    Spacer(Modifier.height(12.dp))
    // Preview
    Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(16.dp)).background(if (EditorChrome.dark) Color(0xFF2A2A2D) else Color(0xFFF3F3F5)), contentAlignment = Alignment.Center) {
      Canvas(Modifier.fillMaxWidth(0.55f).height(60.dp)) {
        val w = this.size.width; val h = this.size.height
        val preview = InkStroke(
          (0..12).map { i -> TimedPoint(w * 0.08f + w * 0.84f * i / 12f, h / 2 + kotlin.math.sin(i / 12f * 3.14f) * h * 0.08f, 0) },
          width = tapeSliderToWidth(size) * 0.55f, colorHex = color, isTape = true, tapePattern = pattern
        )
        drawInkStroke(preview, Color(color))
        drawTapePattern(preview, Color(color))
      }
    }
    Spacer(Modifier.height(12.dp))
    SizeRow(size, onSize)
    SwitchRow("Straighten lines", straighten, onStraighten)
    Spacer(Modifier.height(6.dp))
    // Colour / Pattern
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (EditorChrome.dark) Color(0xFF2A2A2D) else Color(0xFFF3F3F5)).padding(4.dp)) {
      for ((label, isPattern) in listOf("Colour" to false, "Pattern" to true)) {
        val sel = patternTab == isPattern
        Box(
          Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (sel) EditorChrome.menu else Color.Transparent)
            .clickable { patternTab = isPattern }.semantics { selected = sel },
          contentAlignment = Alignment.Center
        ) { Text(label, fontSize = 16.sp, color = if (sel) PanelText else EditorChrome.secondaryText, fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold else null) }
      }
    }
    Spacer(Modifier.height(14.dp))
    if (!patternTab) {
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TapeColors.forEach { c -> ColorSwatch(c, (c or 0xFF000000L) == (color or 0xFF000000L), 36.dp) { onColor(c) } }
      }
    } else {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TapePattern.entries.forEach { p ->
          val sel = p == pattern
          Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
              .border(if (sel) 2.5.dp else 1.dp, if (sel) OneUi9Tokens.SamsungBlue else EditorChrome.divider, RoundedCornerShape(14.dp))
              .clickable { onPattern(p) }.semantics { contentDescription = "${p.label} tape"; selected = sel },
            contentAlignment = Alignment.Center
          ) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
              val w = this.size.width; val h = this.size.height
              val sample = InkStroke(listOf(TimedPoint(h / 2, h / 2, 0), TimedPoint(w - h / 2, h / 2, 0)), width = h, colorHex = color, isTape = true, tapePattern = p)
              drawInkStroke(sample, Color(color))
              drawTapePattern(sample, Color(color))
            }
          }
        }
      }
    }
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(EditorChrome.divider))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onRemoveAll).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
      Text("Remove all tape", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = PanelText)
    }
  }
}

/** Highlighter sizes 1–10 on the slider, in page pixels. */
fun highlighterSliderToWidth(step: Int): Float = 8f + step * 5f

/** A panel header: title on the left, ✕ on the right (as in Samsung Notes' tool panels). */
@Composable
private fun PanelHeader(title: String?, onClose: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    if (title != null) Text(title, fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = PanelText, modifier = Modifier.weight(1f))
    else Spacer(Modifier.weight(1f))
    Box(Modifier.size(36.dp).clip(CircleShape).background(if (EditorChrome.dark) Color(0xFF2A2A2D) else Color(0xFFF0F0F3)).clickable(onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
      MyScriptIcons.CloseX(color = PanelText, size = 14.dp)
    }
  }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 12.dp)
      .semantics { this.selected = selected },
    verticalAlignment = Alignment.CenterVertically
  ) {
    androidx.compose.material3.RadioButton(
      selected = selected, onClick = onClick,
      colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = OneUi9Tokens.SamsungBlue, unselectedColor = EditorChrome.secondaryText)
    )
    if (icon != null) { Spacer(Modifier.width(6.dp)); icon(); Spacer(Modifier.width(10.dp)) } else Spacer(Modifier.width(8.dp))
    Text(label, fontSize = 17.sp, color = PanelText)
  }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, fontSize = 17.sp, color = PanelText, modifier = Modifier.weight(1f))
    Switch(
      checked = checked, onCheckedChange = onChange,
      colors = SwitchDefaults.colors(checkedTrackColor = OneUi9Tokens.SamsungBlue, uncheckedTrackColor = Color(0xFFD5D5D9), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent)
    )
  }
}

@Composable
private fun SizeRow(step: Int, onStep: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable { onStep((step - 1).coerceAtLeast(1)) }.semantics { contentDescription = "Smaller" }, contentAlignment = Alignment.Center) { MyScriptIcons.Minus(PanelText, 20.dp) }
    SizeSlider(step, onStep = onStep, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
    Box(Modifier.size(40.dp).clip(CircleShape).clickable { onStep((step + 1).coerceAtMost(10)) }.semantics { contentDescription = "Bigger" }, contentAlignment = Alignment.Center) { MyScriptIcons.Plus(PanelText, 20.dp) }
  }
}

/**
 * Samsung Notes' highlighter settings: chisel or round tip, size and colour. The highlighter
 * keeps its own colour, separate from the pen's.
 */
@Composable
fun HighlighterSettingsPanel(
  chisel: Boolean, size: Int, color: Long, customColors: List<Long>,
  onChisel: (Boolean) -> Unit, onSize: (Int) -> Unit, onColor: (Long) -> Unit, onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
    PanelHeader(null, onClose)
    // The two highlighters, the selected one raised, drawn in the highlighter's colour
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
      for (isChisel in listOf(true, false)) {
        val sel = isChisel == chisel
        Box(
          Modifier.width(70.dp).height(if (sel) 146.dp else 118.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .clickable { onChisel(isChisel) }
            .semantics { contentDescription = if (isChisel) "Chisel highlighter" else "Round highlighter"; selected = sel },
          contentAlignment = Alignment.BottomCenter
        ) {
          Canvas(Modifier.width(34.dp).height(if (sel) 140.dp else 112.dp)) {
            val w = this.size.width; val h = this.size.height
            val ink = Color(color)
            // Body in the colour, dark cap, then the tip
            drawRoundRect(ink.copy(alpha = 0.75f), Offset(w * 0.1f, h * 0.45f), androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.55f), androidx.compose.ui.geometry.CornerRadius(6f))
            drawRoundRect(Color(0xFF4A4A4E), Offset(w * 0.1f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.26f), androidx.compose.ui.geometry.CornerRadius(6f))
            val tip = Path().apply {
              if (isChisel) { moveTo(w * 0.28f, h * 0.22f); lineTo(w * 0.28f, h * 0.06f); lineTo(w * 0.72f, 0f); lineTo(w * 0.72f, h * 0.22f); close() }
              else { moveTo(w * 0.3f, h * 0.22f); cubicTo(w * 0.3f, h * 0.02f, w * 0.7f, h * 0.02f, w * 0.7f, h * 0.22f); close() }
            }
            drawPath(tip, ink)
          }
        }
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(EditorChrome.divider))
    Spacer(Modifier.height(16.dp))
    SizeRow(size, onSize)
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      (listOf(0xFFFFE600L) + SamsungPalette + customColors).distinct().forEach { c -> ColorSwatch(c, (c or 0xFF000000L) == (color or 0xFF000000L), 38.dp) { onColor(c) } }
    }
  }
}

/** Samsung Notes' eraser settings: stroke or area eraser (with a size), and erase highlighter only. */
@Composable
fun EraserSettingsPanel(
  area: Boolean, size: Int, highlighterOnly: Boolean,
  onArea: (Boolean) -> Unit, onSize: (Int) -> Unit, onHighlighterOnly: (Boolean) -> Unit, onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
    PanelHeader("Handwriting eraser", onClose)
    Spacer(Modifier.height(8.dp))
    RadioRow("Stroke eraser", !area) { onArea(false) }
    RadioRow("Area eraser", area) { onArea(true) }
    if (area) {
      SizeRow(size, onSize)
      Spacer(Modifier.height(6.dp))
    }
    SwitchRow("Erase highlighter only", highlighterOnly, onHighlighterOnly)
  }
}

/** Samsung Notes' selection settings: lasso or rectangle, and include partially selected objects. */
@Composable
fun LassoSettingsPanel(
  rectangle: Boolean, partial: Boolean,
  onRectangle: (Boolean) -> Unit, onPartial: (Boolean) -> Unit, onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
    PanelHeader("Selection mode", onClose)
    Spacer(Modifier.height(8.dp))
    RadioRow("Lasso", !rectangle, icon = {
      Canvas(Modifier.size(26.dp)) {
        drawOval(PanelText, style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
      }
    }) { onRectangle(false) }
    RadioRow("Rectangle", rectangle, icon = {
      Canvas(Modifier.size(26.dp)) {
        drawRoundRect(PanelText, cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f), style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
      }
    }) { onRectangle(true) }
    SwitchRow("Include partially selected objects", partial, onPartial)
  }
}

@Composable
private fun ColorSwatch(c: Long, selected: Boolean, size: Dp = 38.dp, onClick: () -> Unit) {
  Box(
    Modifier
      .size(size)
      .clip(CircleShape)
      .background(Color(c))
      .border(if (selected) 3.dp else 1.dp, if (selected) EditorChrome.text else EditorChrome.swatchRing(c), CircleShape)
      .clickable(onClick = onClick)
      .semantics { contentDescription = "Colour #" + "%06X".format(c and 0xFFFFFF); this.selected = selected }
  )
}

/**
 * A − / + button: one step per tap, and held down it keeps stepping, faster after a moment, so the
 * 100 pen sizes are quick to go through.
 */
@Composable
private fun RepeatButton(label: String, onStep: () -> Unit, content: @Composable () -> Unit) {
  val step by androidx.compose.runtime.rememberUpdatedState(onStep)
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  Box(
    Modifier.size(40.dp).clip(CircleShape)
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown()
          step()
          val job = scope.launch {
            kotlinx.coroutines.delay(400)
            var n = 0
            while (true) { step(); n++; kotlinx.coroutines.delay(if (n < 8) 90 else 35) }
          }
          try { waitForUpOrCancellation() } finally { job.cancel() }
        }
      }
      .semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button },
    contentAlignment = Alignment.Center
  ) { content() }
}

/** Samsung's size slider: a track of ten dots and a round handle showing the size (1–[max]). */
@Composable
private fun SizeSlider(step: Int, onStep: (Int) -> Unit, modifier: Modifier = Modifier, max: Int = 10) {
  BoxWithConstraints(modifier.height(44.dp)) {
    val widthPx = constraints.maxWidth.toFloat()
    val trackWidth = maxWidth
    val handlePx = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
    // The handle's centre runs from half a handle in from each end, so the finger stays on it
    fun stepAt(x: Float) = (1 + ((x - handlePx / 2f) / (widthPx - handlePx).coerceAtLeast(1f) * (max - 1)).roundToInt()).coerceIn(1, max)
    Box(
      Modifier
        .fillMaxSize()
        .pointerInput(widthPx, max) {
          detectTapGestures { onStep(stepAt(it.x)) }
        }
        .pointerInput(widthPx, max) {
          detectDragGestures(onDragStart = { onStep(stepAt(it.x)) }) { change, _ -> change.consume(); onStep(stepAt(change.position.x)) }
        }
        .semantics { contentDescription = "Size $step of $max" }
    ) {
      Box(Modifier.align(Alignment.Center).fillMaxWidth().height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (EditorChrome.dark) Color(0xFF2A2A2D) else Color(0xFFF1F1F3)))
      Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(10) { Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFBDBDC4))) }
      }
      Box(
        Modifier
          .align(Alignment.CenterStart)
          .offset(x = (trackWidth - 44.dp) * ((step - 1) / (max - 1).coerceAtLeast(1).toFloat()))
          .size(44.dp)
          .clip(CircleShape)
          .background(EditorChrome.menu)
          .border(1.dp, Color(0xFFDCDCE0), CircleShape),
        contentAlignment = Alignment.Center
      ) { Text(step.toString(), fontSize = 16.sp, color = PanelText) }
    }
  }
}

