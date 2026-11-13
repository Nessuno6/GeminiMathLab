package com.deang.geminimath

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Samsung Notes' selection blue (box, handles, outline around selected ink). */
val SelectionBlue = Color(0xFF3B8BF0)

/** What the selection menu can do. Null actions are left out. */
class LassoActions(
  val solve: () -> Unit,
  val cut: () -> Unit,
  val copy: () -> Unit,
  val delete: () -> Unit,
  val changeStyle: () -> Unit,
  val convertToText: () -> Unit,
  val convertToMaths: () -> Unit,
  val alignHandwriting: () -> Unit,
  /** To the layer above; null (shown greyed out) on the top layer. */
  val moveForwards: (() -> Unit)?,
  /** To the layer below; null (shown greyed out) on the bottom layer, Layer 0. */
  val moveBackwards: (() -> Unit)?,
  val clipboard: () -> Unit,
  val duplicate: () -> Unit,
  val cleanUp: () -> Unit,
  /** Draws the selected function as a picture (JPG) next to it. */
  val graph: (() -> Unit)?
)

/**
 * Samsung Notes' selection menu: a white rounded bar of words, centred above the selection
 * (below it when there's no room), with ⋮ for the rest. ✦ Solve comes first, as it's this app's own.
 */
@Composable
fun LassoActionMenu(selection: Rect, actions: LassoActions) {
  val d = LocalDensity.current
  val gap = with(d) { 58.dp.toPx() } // clears the rotate handle
  val edge = with(d) { 12.dp.toPx() }
  var more by remember { mutableStateOf(false) }
  // On a phone the bar would be too wide: Change style moves into ⋮, as in Samsung Notes
  val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
  Box(
    Modifier.layout { m, c ->
      val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
      layout(p.width, p.height) {
        val maxW = c.maxWidth.toFloat(); val maxH = c.maxHeight.toFloat()
        val x = (selection.center.x - p.width / 2f).coerceIn(edge, (maxW - p.width - edge).coerceAtLeast(edge))
        val above = selection.top - gap - p.height
        val y = if (above > edge) above else (selection.bottom + with(d) { 24.dp.toPx() }).coerceAtMost(maxH - p.height - edge)
        p.place(IntOffset(x.roundToInt(), y.roundToInt()))
      }
    }
  ) {
    Surface(shape = RoundedCornerShape(20.dp), color = EditorChrome.menu, shadowElevation = 10.dp, tonalElevation = 0.dp) {
      Row(Modifier.height(46.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
          Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClickLabel = "Solve selection", onClick = actions.solve)
            .padding(horizontal = 9.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          MyScriptIcons.SparkleAi(color = OneUi9Tokens.SamsungBlue, size = 13.dp)
          Spacer(Modifier.width(4.dp))
          Text("Solve", color = OneUi9Tokens.SamsungBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
        MenuWord("Cut", actions.cut)
        MenuWord("Copy", actions.copy)
        MenuWord("Delete", actions.delete)
        if (!narrow) MenuWord("Change style", actions.changeStyle)
        Box {
          Box(
            Modifier.size(34.dp).clip(CircleShape).clickable { more = true }.semantics { contentDescription = "More options" },
            contentAlignment = Alignment.Center
          ) {
            MyScriptIcons.MoreVerticalDots(EditorChrome.text, 18.dp)
          }
          // Samsung's ⋮ list, then this app's extras
          DropdownMenu(
            expanded = more, onDismissRequest = { more = false },
            shape = RoundedCornerShape(22.dp), containerColor = EditorChrome.menu, shadowElevation = 10.dp
          ) {
            @Composable fun Item(label: String, action: (() -> Unit)?) =
              DropdownMenuItem(
                text = { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (action != null) EditorChrome.text else EditorChrome.secondaryText.copy(alpha = 0.5f), maxLines = 1) },
                onClick = { if (action != null) { more = false; action() } },
                enabled = action != null,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp)
              )
            if (narrow) Item("Change style", actions.changeStyle)
            Item("Convert to text", actions.convertToText)
            Item("Convert to maths", actions.convertToMaths)
            Item("Align handwriting", actions.alignHandwriting)
            Item("Move forwards", actions.moveForwards)
            Item("Move backwards", actions.moveBackwards)
            Item("Clipboard", actions.clipboard)
            HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), color = EditorChrome.divider)
            Item("Duplicate", actions.duplicate)
            Item("Clean up handwriting", actions.cleanUp)
            actions.graph?.let { Item("Graph to picture", it) }
          }
        }
      }
    }
  }
}

/** The "Paste" bubble shown where you tapped empty space with the lasso. */
@Composable
fun LassoPasteMenu(onPaste: () -> Unit, modifier: Modifier = Modifier) {
  Surface(shape = RoundedCornerShape(22.dp), color = EditorChrome.menu, shadowElevation = 10.dp, modifier = modifier) {
    Row(Modifier.height(46.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      MenuWord("Paste", onPaste)
    }
  }
}

@Composable
private fun MenuWord(label: String, onClick: () -> Unit) {
  Box(
    Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
    contentAlignment = Alignment.Center
  ) { Text(label, color = EditorChrome.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false) }
}

/**
 * Samsung Notes' selection box, on screen: a thin blue frame that turns with the selection, round
 * handles at the corners (resize, keeping the shape) and in the middle of each side (stretch that
 * way only), and a rotate handle off the top side. Only the handles take touches; dragging inside
 * the box (moving it) is left to the page.
 *
 * [corners] are the box's corners on screen (top-left, top-right, bottom-right, bottom-left of the
 * turned box) and [angle] its turn. Callbacks get screen coordinates, for every finger movement:
 * [onScale] a factor and the fixed point, [onStretch] a factor, the direction and the fixed point,
 * [onRotate] a step angle (radians) and the centre.
 */
@Composable
fun LassoSelectionOverlay(
  corners: List<Offset>,
  angle: Float,
  onStart: () -> Unit,
  onScale: (Float, Offset) -> Unit,
  onStretch: (Float, Offset, Offset) -> Unit,
  onRotate: (Float, Offset) -> Unit,
  onEnd: () -> Unit
) {
  val d = LocalDensity.current
  val pad = with(d) { 10.dp.toPx() }
  val stem = with(d) { 30.dp.toPx() }
  val touch = with(d) { 44.dp.toPx() }
  val half = Offset(touch / 2f, touch / 2f)
  val u = Offset(cos(angle), sin(angle))   // along the box's top side
  val v = Offset(-sin(angle), cos(angle))  // down the box's left side
  // The frame sits a little outside the ink
  val box = listOf(
    corners[0] - u * pad - v * pad, corners[1] + u * pad - v * pad,
    corners[2] + u * pad + v * pad, corners[3] - u * pad + v * pad
  )
  class Geo(val box: List<Offset>, val u: Offset, val v: Offset) {
    val centre = (box[0] + box[2]) / 2f
    fun mid(e: Int) = (box[e] + box[(e + 1) % 4]) / 2f
    val rotor get() = mid(0) - v * stem
  }
  val geo = Geo(box, u, v)
  val current by rememberUpdatedState(geo)
  val dark = EditorChrome.dark
  val handleFill = if (dark) Color(0xFF1E1E21) else Color.White

  Canvas(Modifier.fillMaxSize()) {
    val line = 1.5.dp.toPx()
    val frame = Path().apply { moveTo(box[0].x, box[0].y); for (i in 1..3) lineTo(box[i].x, box[i].y); close() }
    drawPath(frame, SelectionBlue, style = Stroke(width = line))
    val rotC = geo.rotor
    val rr = 11.dp.toPx()
    drawLine(SelectionBlue, geo.mid(0), rotC + v * rr, line)
    val hr = 7.dp.toPx()
    for (p in box + (0..3).map { geo.mid(it) }) {
      drawCircle(handleFill, hr, p)
      drawCircle(SelectionBlue, hr, p, style = Stroke(width = line))
    }
    // Rotate handle: a ring with two curved arrows inside
    drawCircle(handleFill, rr, rotC)
    drawCircle(SelectionBlue, rr, rotC, style = Stroke(width = line))
    val ar = rr * 0.52f
    val tl = Offset(rotC.x - ar, rotC.y - ar); val sz = androidx.compose.ui.geometry.Size(ar * 2, ar * 2)
    drawArc(SelectionBlue, -70f, 130f, false, tl, sz, style = Stroke(width = line))
    drawArc(SelectionBlue, 110f, 130f, false, tl, sz, style = Stroke(width = line))
    fun head(degAt: Float) {
      val a = Math.toRadians(degAt.toDouble()).toFloat()
      val p = Offset(rotC.x + ar * cos(a), rotC.y + ar * sin(a))
      val t = a + Math.PI.toFloat() / 2f
      val hl = rr * 0.32f
      val path = Path().apply {
        moveTo(p.x + cos(t) * hl, p.y + sin(t) * hl)
        lineTo(p.x + cos(t + 2.4f) * hl * 0.8f, p.y + sin(t + 2.4f) * hl * 0.8f)
        lineTo(p.x + cos(t - 2.4f) * hl * 0.8f, p.y + sin(t - 2.4f) * hl * 0.8f)
        close()
      }
      drawPath(path, SelectionBlue)
    }
    head(60f); head(240f)
  }

  // Handles move with the box while dragged, so the finger's place on screen is worked out
  // from where the handle is now plus where the finger is on it
  @Composable
  fun Handle(key: Int, at: Offset, label: String, onDrag: (finger: Offset) -> Unit, onDown: () -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val down by rememberUpdatedState(onDown)
    val end by rememberUpdatedState(onEnd)
    Box(
      Modifier.offset { IntOffset((at.x - touch / 2f).roundToInt(), (at.y - touch / 2f).roundToInt()) }
        .size(44.dp)
        .semantics { contentDescription = label }
        .pointerInput(key) {
          detectDragGestures(onDragStart = { down() }, onDragEnd = { end() }, onDragCancel = { end() }) { change, _ ->
            change.consume()
            drag(change.position - half)
          }
        }
    )
  }

  // Corners: resize around the opposite corner, keeping the shape
  for (i in 0..3) {
    var pivot by remember { mutableStateOf(Offset.Zero) }
    Handle(i, box[i], "Resize selection", onDown = { pivot = current.box[(i + 2) % 4]; onStart() }, onDrag = { rel ->
      val corner = current.box[i]
      val finger = corner + rel
      val before = hypot(corner.x - pivot.x, corner.y - pivot.y)
      if (before > 1f) {
        val k = (hypot(finger.x - pivot.x, finger.y - pivot.y) / before).coerceIn(0.7f, 1.4f)
        if (!(k < 1f && before < touch * 1.4f)) onScale(k, pivot)
      }
    })
  }
  // Sides: stretch across that side only, around the opposite side
  for (e in 0..3) {
    var pivot by remember { mutableStateOf(Offset.Zero) }
    Handle(10 + e, geo.mid(e), "Stretch selection", onDown = { pivot = current.mid((e + 2) % 4); onStart() }, onDrag = { rel ->
      val g = current
      val axis = if (e % 2 == 0) g.v else g.u
      val handle = g.mid(e)
      val finger = handle + rel
      val before = (handle - pivot).let { it.x * axis.x + it.y * axis.y }
      val after = (finger - pivot).let { it.x * axis.x + it.y * axis.y }
      if (kotlin.math.abs(before) > 1f && after / before > 0f) {
        val k = (after / before).coerceIn(0.7f, 1.4f)
        if (!(k < 1f && kotlin.math.abs(before) < touch * 0.8f)) onStretch(k, axis, pivot)
      }
    })
  }
  // Rotate handle: turns the selection around its centre
  run {
    var centre by remember { mutableStateOf(Offset.Zero) }
    var last by remember { mutableStateOf(0f) }
    Handle(20, geo.rotor, "Rotate selection", onDown = {
      centre = current.centre
      last = Float.NaN // set by the first movement, so where you grab the handle doesn't jump it
      onStart()
    }, onDrag = { rel ->
      val finger = current.rotor + rel
      val a = atan2(finger.y - centre.y, finger.x - centre.x)
      if (last.isNaN()) { last = a; return@Handle }
      var step = a - last
      if (step > Math.PI) step -= (2 * Math.PI).toFloat()
      if (step < -Math.PI) step += (2 * Math.PI).toFloat()
      last = a
      onRotate(step, centre)
    })
  }
}
