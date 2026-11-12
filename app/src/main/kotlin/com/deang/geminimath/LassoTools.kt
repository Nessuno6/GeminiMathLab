package com.deang.geminimath

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Geometry helpers for lasso selection and ink editing. */
object LassoTools {
  /** Ray-casting point-in-polygon test. */
  fun contains(poly: List<TimedPoint>, x: Float, y: Float): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
      val a = poly[i]
      val b = poly[j]
      if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
      j = i
    }
    return inside
  }

  /**
   * Strokes inside the lasso loop: most of their points, or, with [partial] ("Include partially
   * selected objects"), any of them. Pictures and fills count by their corners and centre.
   */
  fun strokesInside(strokes: List<InkStroke>, loop: List<TimedPoint>, partial: Boolean = false): List<InkStroke> {
    if (loop.size < 3) return emptyList()
    return strokes.filter { s ->
      if (s.points.isEmpty()) return@filter false
      val pts = if (s.picture != null || s.fill != null) boxPoints(s) else alongLine(s)
      val inside = pts.count { contains(loop, it.x, it.y) }
      if (partial) inside > 0 else inside >= pts.size * 0.6f
    }
  }

  /**
   * Points spread along the stroke's whole line, so a shape (stored as its corners only) counts by
   * how much of its outline is in the loop, and a loop across one of its sides still finds it.
   */
  private fun alongLine(s: InkStroke): List<TimedPoint> {
    val b = bounds(listOf(s)) ?: return s.points
    val step = (max(b.width, b.height) / 40f).coerceAtLeast(2f)
    return InkTools.densify(s, step).points
  }

  /** How far page point ([x], [y]) is from the stroke's line (its points and the straight bits between them). */
  fun distanceTo(s: InkStroke, x: Float, y: Float): Float {
    val pts = s.points
    if (pts.isEmpty()) return Float.MAX_VALUE
    var best = kotlin.math.hypot(pts[0].x - x, pts[0].y - y)
    for (i in 1 until pts.size) {
      val a = pts[i - 1]; val c = pts[i]
      val dx = c.x - a.x; val dy = c.y - a.y
      val len2 = dx * dx + dy * dy
      val t = if (len2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
      best = min(best, kotlin.math.hypot(x - (a.x + t * dx), y - (a.y + t * dy)))
    }
    return best
  }

  private fun boxPoints(s: InkStroke): List<TimedPoint> {
    val a = s.points[0]; val b = s.points.getOrElse(1) { a }
    return listOf(a, TimedPoint(b.x, a.y, 0), b, TimedPoint(a.x, b.y, 0), TimedPoint((a.x + b.x) / 2, (a.y + b.y) / 2, 0))
  }

  /** The rectangle from a drag's first point to its last, as a closed loop (rectangle selection). */
  fun rectangleLoop(drag: List<TimedPoint>): List<TimedPoint> {
    if (drag.isEmpty()) return drag
    val a = drag.first(); val b = drag.last()
    return listOf(a, TimedPoint(b.x, a.y, a.t), b, TimedPoint(a.x, b.y, b.t), a)
  }

  /**
   * Area eraser: the parts of [s] outside the circle at ([cx], [cy]) with radius [r], as separate
   * strokes. Empty when it's all rubbed out; [s] itself when the circle misses it.
   */
  fun eraseArea(s: InkStroke, cx: Float, cy: Float, r: Float): List<InkStroke> {
    val reach = r + s.width / 2f
    if (s.points.none { kotlin.math.hypot(it.x - cx, it.y - cy) < reach }) return listOf(s)
    val pieces = mutableListOf<MutableList<TimedPoint>>()
    var cur = mutableListOf<TimedPoint>()
    for (p in s.points) {
      if (kotlin.math.hypot(p.x - cx, p.y - cy) < reach) { if (cur.isNotEmpty()) { pieces += cur; cur = mutableListOf() } }
      else cur += p
    }
    if (cur.isNotEmpty()) pieces += cur
    // Leftover crumbs of a point or two aren't worth keeping
    return pieces.filter { it.size >= 2 || s.points.size == 1 }.map { s.copy(points = it) }
  }

  /** Whether segment a–b crosses segment c–d. */
  fun segmentsCross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
    fun side(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
    val d1 = side(cx, cy, dx, dy, ax, ay); val d2 = side(cx, cy, dx, dy, bx, by)
    val d3 = side(ax, ay, bx, by, cx, cy); val d4 = side(ax, ay, bx, by, dx, dy)
    return ((d1 > 0f) != (d2 > 0f)) && ((d3 > 0f) != (d4 > 0f))
  }

  fun bounds(strokes: List<InkStroke>): Rect? {
    var l = Float.MAX_VALUE
    var t = Float.MAX_VALUE
    var r = -Float.MAX_VALUE
    var b = -Float.MAX_VALUE
    for (s in strokes) for (p in s.points) {
      l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
    }
    return if (l == Float.MAX_VALUE) null else Rect(l, t, r, b)
  }

  fun translate(s: InkStroke, dx: Float, dy: Float): InkStroke =
    s.copy(points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) })

  /** Scales a stroke by [k] around ([px], [py]); ink gets thicker or thinner with it. */
  fun scale(s: InkStroke, k: Float, px: Float, py: Float): InkStroke {
    val pts = s.points.map { it.copy(x = px + (it.x - px) * k, y = py + (it.y - py) * k) }
    val boxed = s.picture != null || s.fill != null
    return s.copy(points = pts, width = if (boxed) s.width else (s.width * k).coerceIn(0.3f, 400f))
  }

  /**
   * Rotates a stroke by [radians] around ([cx], [cy]). Pictures and fills are kept upright (they
   * are stored as a box) and only move with the rotation.
   */
  fun rotate(s: InkStroke, radians: Float, cx: Float, cy: Float): InkStroke {
    val c = kotlin.math.cos(radians); val sn = kotlin.math.sin(radians)
    fun rx(x: Float, y: Float) = cx + (x - cx) * c - (y - cy) * sn
    fun ry(x: Float, y: Float) = cy + (x - cx) * sn + (y - cy) * c
    if (s.picture != null || s.fill != null) {
      val b = bounds(listOf(s)) ?: return s
      val mx = b.center.x; val my = b.center.y
      return translate(s, rx(mx, my) - mx, ry(mx, my) - my)
    }
    return s.copy(points = s.points.map { it.copy(x = rx(it.x, it.y), y = ry(it.x, it.y)) })
  }

  /**
   * Stretches a stroke by [k] along the unit direction ([ax], [ay]) only, around ([px], [py])
   * (a side handle of the selection box).
   */
  fun stretch(s: InkStroke, k: Float, ax: Float, ay: Float, px: Float, py: Float): InkStroke {
    val pts = s.points.map {
      val d = (it.x - px) * ax + (it.y - py) * ay
      it.copy(x = it.x + (k - 1f) * d * ax, y = it.y + (k - 1f) * d * ay)
    }
    if (s.picture != null || s.fill != null) {
      // Boxes stay boxes: top-left and bottom-right of where the corners went
      val a = pts[0]; val b = pts.getOrElse(1) { a }
      return s.copy(points = listOf(TimedPoint(min(a.x, b.x), min(a.y, b.y), a.t), TimedPoint(max(a.x, b.x), max(a.y, b.y), b.t)))
    }
    return s.copy(points = pts, width = (s.width * kotlin.math.sqrt(k)).coerceIn(0.3f, 400f))
  }

  /**
   * The selection box turned by [angle] (radians): its corners in page coordinates, in the
   * order top-left, top-right, bottom-right, bottom-left of the turned box.
   */
  fun orientedBox(strokes: List<InkStroke>, angle: Float): List<Offset>? {
    val c = kotlin.math.cos(angle); val sn = kotlin.math.sin(angle)
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    fun take(x: Float, y: Float) {
      // Into the box's own frame (turned back by the angle)
      val u = x * c + y * sn; val v = -x * sn + y * c
      l = min(l, u); t = min(t, v); r = max(r, u); b = max(b, v)
    }
    for (s in strokes) {
      if ((s.picture != null || s.fill != null) && s.points.size >= 2) {
        val p = s.points[0]; val q = s.points[1]
        take(p.x, p.y); take(q.x, p.y); take(q.x, q.y); take(p.x, q.y)
      } else for (p in s.points) take(p.x, p.y)
    }
    if (l == Float.MAX_VALUE) return null
    fun back(u: Float, v: Float) = Offset(u * c - v * sn, u * sn + v * c)
    return listOf(back(l, t), back(r, t), back(r, b), back(l, b))
  }

  /** Groups strokes into lines of writing (by vertical overlap), top to bottom, each left to right. */
  fun splitLines(strokes: List<InkStroke>): List<List<InkStroke>> {
    val boxed = strokes.mapNotNull { s -> bounds(listOf(s))?.let { s to it } }.sortedBy { it.second.center.y }
    val lines = ArrayList<MutableList<Pair<InkStroke, Rect>>>()
    for (item in boxed) {
      val r = item.second
      val line = lines.lastOrNull()
      if (line != null) {
        val top = line.minOf { it.second.top }; val bottom = line.maxOf { it.second.bottom }
        val h = bottom - top
        // Same line when the stroke's middle falls inside the line's band (with some slack)
        if (r.center.y in (top - h * 0.15f)..(bottom + h * 0.15f) || (r.height < h * 0.5f && r.center.y < bottom + h * 0.4f)) {
          line += item; continue
        }
      }
      lines += mutableListOf(item)
    }
    return lines.map { l -> l.sortedBy { it.second.left }.map { it.first } }
  }

  /**
   * Samsung Notes' "Align handwriting": each line of writing is levelled (its slope taken out)
   * and the lines start at the same left edge. Returns (old stroke, new stroke) pairs.
   */
  fun alignHandwriting(strokes: List<InkStroke>): List<Pair<InkStroke, InkStroke>> {
    val ink = strokes.filter { it.isInk && it.points.isNotEmpty() }
    val lines = splitLines(ink)
    if (lines.isEmpty()) return emptyList()
    val left = lines.minOf { l -> bounds(l)!!.left }
    val out = ArrayList<Pair<InkStroke, InkStroke>>()
    for (line in lines) {
      var cur = line
      if (line.size >= 2) {
        // Slope of the line through the strokes' bottoms (the baseline), by least squares
        val pts = line.map { s -> val b = bounds(listOf(s))!!; b.center.x to b.bottom }
        val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx > 1e-3) {
          val slope = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
          val a = kotlin.math.atan(slope).toFloat()
          if (abs(a) > 0.008f && abs(a) < 0.7f) {
            val c = bounds(line)!!.center
            cur = line.map { rotate(it, -a, c.x, c.y) }
          }
        }
      }
      val dx = left - bounds(cur)!!.left
      line.forEachIndexed { i, s -> out += s to (if (abs(dx) > 0.01f) translate(cur[i], dx, 0f) else cur[i]) }
    }
    return out
  }

  fun pathLength(points: List<TimedPoint>): Float {
    var d = 0f
    for (i in 1 until points.size) d += kotlin.math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    return d
  }

  /**
   * The strokes on the same line as a just-written "=" and to its left, so auto-solve reads
   * "1 + 1 =" without picking up other expressions on the page.
   */
  fun lineEndingAtEquals(strokes: List<InkStroke>): List<InkStroke> {
    if (strokes.size < 2) return strokes
    val eq = bounds(strokes.takeLast(2)) ?: return strokes
    val half = max(90f, eq.width * 1.8f)
    val top = eq.center.y - half
    val bottom = eq.center.y + half
    return strokes.filter { s ->
      val b = bounds(listOf(s)) ?: return@filter false
      b.center.y in top..bottom && b.left <= eq.right + 4f && b.right >= eq.left - 2000f
    }
  }

  /** True when the last two strokes look like a handwritten "=" (two short stacked bars). */
  fun endsWithEquals(strokes: List<InkStroke>): Boolean {
    if (strokes.size < 2) return false
    val a = bounds(listOf(strokes[strokes.size - 2])) ?: return false
    val b = bounds(listOf(strokes[strokes.size - 1])) ?: return false
    fun isBar(r: Rect) = r.width >= 12f && r.width > 2.2f * max(r.height, 1f)
    if (!isBar(a) || !isBar(b)) return false
    val overlap = min(a.right, b.right) - max(a.left, b.left)
    if (overlap < 0.5f * min(a.width, b.width)) return false
    val gap = abs(a.center.y - b.center.y)
    val w = max(a.width, b.width)
    return gap in (0.12f * w)..(1.3f * w)
  }
}
