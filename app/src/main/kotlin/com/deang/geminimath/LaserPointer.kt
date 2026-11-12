package com.deang.geminimath

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

/** Laser colour value meaning "rainbow". */
const val LASER_RAINBOW = 0L

/** The laser's colours, as in Samsung Notes' laser pointer, then rainbow. */
val LaserColors = listOf(0xFFFF3B30L, 0xFFFF9500L, 0xFFFFE600L, 0xFF34C759L, 0xFF2E9BFFL, 0xFFAF52DEL, 0xFFFF2D92L, 0xFFFFFFFFL)

/**
 * The laser pointer's trail. It lives apart from the ink so moving the laser only redraws the
 * laser's own layer, not the whole page. Each point fades out on its own a moment after it was
 * drawn, so the trail follows the pen like a comet tail.
 *
 * Points are in page coordinates; times are [SystemClock.uptimeMillis] (the same clock as touch events).
 */
class LaserTrail {
  class Pt(val x: Float, val y: Float, val t: Long, val dist: Float)

  val points = ArrayList<Pt>()
  /** Where the pen is predicted to be by the time this frame shows (drawn as the tip), or null. */
  var tip: Offset? = null
  var penDown = false
  /** Bumped on every change so the laser layer redraws (and only it). */
  var tick by mutableLongStateOf(0L)

  fun start(x: Float, y: Float, t: Long) {
    // A new stroke continues the old tail (it keeps fading on its own)
    points += Pt(x, y, t, (points.lastOrNull()?.dist ?: 0f) + 1000f)
    penDown = true
    tip = null
    tick++
  }

  fun add(x: Float, y: Float, t: Long) {
    val last = points.lastOrNull()
    val d = if (last == null) 0f else hypot(x - last.x, y - last.y)
    if (last != null && d < 0.3f) return
    points += Pt(x, y, t, (last?.dist ?: 0f) + d)
  }

  fun lift() { penDown = false; tip = null; tick++ }

  /** Drops points that have faded out; true while there is anything left to draw. */
  fun prune(now: Long): Boolean {
    var drop = 0
    while (drop < points.size && now - points[drop].t > LIFE_MS) drop++
    if (drop > 0) points.subList(0, drop).clear()
    return points.isNotEmpty() || penDown
  }

  companion object {
    /** A point stays fully bright this long… */
    const val HOLD_MS = 450L
    /** …then fades out, gone after this long. */
    const val LIFE_MS = 900L

    fun alpha(age: Long): Float = when {
      age <= HOLD_MS -> 1f
      age >= LIFE_MS -> 0f
      else -> 1f - (age - HOLD_MS) / (LIFE_MS - HOLD_MS).toFloat()
    }

    /** Rainbow colour along the trail: the hue moves with distance and slowly with time. */
    fun rainbow(dist: Float, now: Long): Color {
      val hue = ((dist * 0.6f + now * 0.12f) % 360f + 360f) % 360f
      return Color.hsv(hue, 0.85f, 1f)
    }
  }
}

/**
 * Draws the trail: a soft glow, then a bright core in the laser colour with a white centre, and a
 * glowing dot at the tip while the pen is down. [scale] is the page zoom, so the laser looks the
 * same size on screen at any zoom.
 */
fun DrawScope.drawLaser(trail: LaserTrail, color: Long, scale: Float, density: Float) {
  val now = SystemClock.uptimeMillis()
  val pts = trail.points
  val glowW = 16f * density / scale
  val coreW = 6f * density / scale
  val centreW = 2.2f * density / scale
  fun colourAt(p: LaserTrail.Pt) = if (color == LASER_RAINBOW) LaserTrail.rainbow(p.dist, now) else Color(color)
  for (pass in 0..2) {
    for (i in 1 until pts.size) {
      val a = pts[i - 1]; val b = pts[i]
      if (b.dist - a.dist > 999f) continue // a gap between two strokes
      val alpha = LaserTrail.alpha(now - b.t)
      if (alpha <= 0f) continue
      val c = colourAt(b)
      // The tail gets thinner as it fades
      val k = 0.45f + 0.55f * alpha
      when (pass) {
        0 -> drawLine(c.copy(alpha = 0.22f * alpha), Offset(a.x, a.y), Offset(b.x, b.y), glowW * k, StrokeCap.Round)
        1 -> drawLine(c.copy(alpha = alpha), Offset(a.x, a.y), Offset(b.x, b.y), coreW * k, StrokeCap.Round)
        else -> drawLine(Color.White.copy(alpha = 0.85f * alpha), Offset(a.x, a.y), Offset(b.x, b.y), centreW * k, StrokeCap.Round)
      }
    }
  }
  if (trail.penDown) {
    val last = pts.lastOrNull() ?: return
    val head = trail.tip ?: Offset(last.x, last.y)
    val c = colourAt(last)
    // Bridge to the predicted tip so the laser keeps up with a fast pen
    if (trail.tip != null) {
      drawLine(c, Offset(last.x, last.y), head, coreW, StrokeCap.Round)
      drawLine(Color.White.copy(alpha = 0.85f), Offset(last.x, last.y), head, centreW, StrokeCap.Round)
    }
    drawCircle(c.copy(alpha = 0.28f), radius = 13f * density / scale, center = head)
    drawCircle(c, radius = 5.5f * density / scale, center = head)
    drawCircle(Color.White, radius = 2.4f * density / scale, center = head)
  }
}

/** Laser pointer settings (tap the selected laser): its colour, or rainbow. */
@Composable
fun LaserSettingsPanel(color: Long, onColor: (Long) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Laser pointer", fontSize = 18.sp, color = EditorChrome.text, modifier = Modifier.weight(1f))
      Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
        Text("✕", fontSize = 16.sp, color = EditorChrome.secondaryText)
      }
    }
    Spacer(Modifier.height(10.dp))
    // A preview of the laser in the chosen colour
    Box(
      Modifier.fillMaxWidth().height(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        .background(if (EditorChrome.dark) Color(0xFF1E1E21) else Color(0xFF2A2A2E))
    ) {
      Canvas(Modifier.fillMaxSize()) {
        val n = 40
        val w = size.width; val h = size.height
        val pts = (0..n).map { i ->
          val f = i / n.toFloat()
          Offset(w * (0.08f + 0.84f * f), h * (0.5f + 0.25f * kotlin.math.sin(f * 6.28f)))
        }
        for (i in 1..n) {
          val f = i / n.toFloat()
          val c = if (color == LASER_RAINBOW) Color.hsv(f * 330f, 0.85f, 1f) else Color(color)
          drawLine(c.copy(alpha = 0.25f * f), pts[i - 1], pts[i], 18f * f + 4f, StrokeCap.Round)
          drawLine(c.copy(alpha = f), pts[i - 1], pts[i], 7f * f + 2f, StrokeCap.Round)
          drawLine(Color.White.copy(alpha = 0.85f * f), pts[i - 1], pts[i], 2.5f * f + 1f, StrokeCap.Round)
        }
        val end = pts.last()
        val c = if (color == LASER_RAINBOW) Color.hsv(330f, 0.85f, 1f) else Color(color)
        drawCircle(c.copy(alpha = 0.3f), 18f, end); drawCircle(c, 8f, end); drawCircle(Color.White, 3.5f, end)
      }
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      (LaserColors + LASER_RAINBOW).forEach { c ->
        val sel = c == color
        val bg = if (c == LASER_RAINBOW) Modifier.background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
        else Modifier.background(Color(c))
        Box(
          Modifier.size(36.dp).clip(CircleShape).then(bg)
            .border(if (sel) 3.dp else 1.dp, if (sel) EditorChrome.text else EditorChrome.swatchRing(if (c == LASER_RAINBOW) 0xFF808080L else c), CircleShape)
            .clickable { onColor(c) }
            .semantics { contentDescription = if (c == LASER_RAINBOW) "Rainbow laser" else "Laser colour #" + "%06X".format(c and 0xFFFFFF); selected = sel }
        )
      }
    }
  }
}
