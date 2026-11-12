package com.deang.geminimath

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Undo/redo by snapshots of the whole stroke list. Strokes are immutable, so a snapshot only copies
 * references. Call [checkpoint] just before any change (a new stroke, erasing, moving, clean up…).
 */
class InkHistory(private val limit: Int = 200) {
  private val undoStack = ArrayDeque<List<InkStroke>>()
  private val redoStack = ArrayDeque<List<InkStroke>>()
  var canUndo by mutableStateOf(false)
    private set
  var canRedo by mutableStateOf(false)
    private set

  fun checkpoint(current: List<InkStroke>) {
    undoStack.addLast(current.toList())
    if (undoStack.size > limit) undoStack.removeFirst()
    redoStack.clear()
    sync()
  }

  fun undo(strokes: MutableList<InkStroke>): Boolean {
    val previous = undoStack.removeLastOrNull() ?: return false
    redoStack.addLast(strokes.toList())
    strokes.clear()
    strokes.addAll(previous)
    sync()
    return true
  }

  fun redo(strokes: MutableList<InkStroke>): Boolean {
    val next = redoStack.removeLastOrNull() ?: return false
    undoStack.addLast(strokes.toList())
    strokes.clear()
    strokes.addAll(next)
    sync()
    return true
  }

  fun clear() {
    undoStack.clear()
    redoStack.clear()
    sync()
  }

  private fun sync() {
    canUndo = undoStack.isNotEmpty()
    canRedo = redoStack.isNotEmpty()
  }
}

/** Geometry helpers behind "Clean up handwriting" and "Auto-fix shapes". */
object InkTools {
  private data class P(val x: Float, val y: Float)

  private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

  private fun distToSegment(p: P, a: P, b: P): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 == 0f) return dist(p, a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return dist(p, P(a.x + t * dx, a.y + t * dy))
  }

  /** Ramer–Douglas–Peucker simplification. */
  private fun simplify(pts: List<P>, epsilon: Float): List<P> {
    if (pts.size < 3) return pts
    var maxD = 0f
    var idx = 0
    for (i in 1 until pts.size - 1) {
      val d = distToSegment(pts[i], pts.first(), pts.last())
      if (d > maxD) { maxD = d; idx = i }
    }
    if (maxD <= epsilon) return listOf(pts.first(), pts.last())
    val left = simplify(pts.subList(0, idx + 1), epsilon)
    val right = simplify(pts.subList(idx, pts.size), epsilon)
    return left.dropLast(1) + right
  }

  /** Chaikin corner cutting: rounds a polyline while keeping its ends. */
  private fun chaikin(pts: List<P>, rounds: Int): List<P> {
    var cur = pts
    repeat(rounds) {
      if (cur.size < 3) return cur
      val out = ArrayList<P>(cur.size * 2)
      out += cur.first()
      for (i in 0 until cur.size - 1) {
        val a = cur[i]
        val b = cur[i + 1]
        out += P(0.75f * a.x + 0.25f * b.x, 0.75f * a.y + 0.25f * b.y)
        out += P(0.25f * a.x + 0.75f * b.x, 0.25f * a.y + 0.75f * b.y)
      }
      out += cur.last()
      cur = out
    }
    return cur
  }

  private fun toPoints(s: InkStroke) = s.points.map { P(it.x, it.y) }

  private fun withPoints(s: InkStroke, pts: List<P>): InkStroke {
    val t0 = s.points.firstOrNull()?.t ?: 0L
    val t1 = s.points.lastOrNull()?.t ?: t0
    val n = max(1, pts.size - 1)
    return s.copy(points = pts.mapIndexed { i, p -> TimedPoint(p.x, p.y, t0 + (t1 - t0) * i / n) })
  }

  /**
   * "Clean up handwriting": removes jitter and wobble but keeps the shape of the writing.
   * Small strokes (dots, short dashes) are left as they are.
   */
  fun cleanUp(s: InkStroke): InkStroke {
    if (s.points.size < 4 || s.isTape) return s
    val pts = toPoints(s)
    val simplified = simplify(pts, max(0.9f, s.width * 0.12f))
    if (simplified.size < 2) return s
    return withPoints(s, chaikin(simplified, 2))
  }

  /** The shapes "Auto-fix shapes" and hold-to-shape recognise. */
  enum class Shape { LINE, POLYLINE, TRIANGLE, RECTANGLE, POLYGON, ELLIPSE }

  data class Fit(val shape: Shape, val stroke: InkStroke)

  /**
   * Recognises a hand-drawn line, triangle, rectangle/square, polygon (up to 6 sides) or
   * ellipse/circle and returns the neat version (its corners only; see [densify]), or null when
   * the stroke isn't clearly one of them, so normal writing is untouched.
   *
   * [lenient] is for "hold to straighten": you held the pen to ask for a shape, so rougher
   * drawings count, and an open zigzag or "L" becomes straight segments.
   */
  fun fitShape(s: InkStroke, lenient: Boolean = false): Fit? {
    if (s.isTape || s.isHighlighter || s.picture != null || s.fill != null) return null
    val pts = dedupe(toPoints(s))
    if (pts.size < 2) return null
    val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
    val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
    val diag = hypot(maxX - minX, maxY - minY)
    if (diag < (if (lenient) 20f else 60f)) return null // too small: probably a letter
    val pathLen = length(pts, closed = false)

    // Closed: the pen comes back near where it started (a small gap or overshoot is fine)
    val loopEnd = closingIndex(pts, pathLen, diag, lenient)
    if (loopEnd < 0) return fitOpen(s, pts, pathLen, diag, lenient)
    val loop = pts.subList(0, loopEnd + 1)
    val ring = resample(loop, 96, closed = true)
    val tol = if (lenient) 1.3f else 1f

    val poly = polygonCorners(ring, diag)
    val polyErr = if (poly.size in 3..6) meanDistToPolygon(ring, poly) / diag else Float.MAX_VALUE
    val ell = fitEllipse(ring)
    val ellErr = ell?.let { meanDistToEllipse(ring, it) / diag } ?: Float.MAX_VALUE

    val polygonWins = poly.size in 3..6 && polyErr < 0.035f * tol && polyErr < ellErr * 0.8f
    if (polygonWins) return polygonFit(s, poly, diag)
    if (ell != null && ellErr < 0.04f * tol && ell.a > 8f && ell.b > 8f) return ellipseFit(s, ell)
    if (poly.size in 3..6 && polyErr < 0.045f * tol) return polygonFit(s, poly, diag)
    return null
  }

  /** Extra points along each straight side, [step] apart, so the eraser and lasso find every part. */
  fun densify(s: InkStroke, step: Float): InkStroke {
    val pts = s.points
    if (pts.size < 2 || step <= 0f || s.picture != null || s.fill != null) return s
    var needed = false
    for (i in 1 until pts.size) if (hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y) > step) { needed = true; break }
    if (!needed) return s
    val out = ArrayList<TimedPoint>(pts.size * 2)
    out += pts[0]
    for (i in 1 until pts.size) {
      val a = pts[i - 1]; val b = pts[i]
      val n = ceil(hypot(b.x - a.x, b.y - a.y) / step).toInt().coerceIn(1, 4000)
      for (k in 1..n) {
        val t = k / n.toFloat()
        out += TimedPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.t + ((b.t - a.t) * t).toLong())
      }
    }
    return s.copy(points = out)
  }

  // ---- Open strokes ----

  private fun fitOpen(s: InkStroke, pts: List<P>, pathLen: Float, diag: Float, lenient: Boolean): Fit? {
    val a = pts.first(); val b = pts.last()
    val chord = dist(a, b)
    if (chord >= (if (lenient) 20f else 60f)) {
      val maxDev = pts.maxOf { distToSegment(it, a, b) }
      val straight = if (lenient) maxDev <= max(8f, chord * 0.14f) && pathLen <= chord * 1.3f
      else maxDev <= max(6f, chord * 0.05f) && pathLen <= chord * 1.12f
      if (straight) return Fit(Shape.LINE, withPoints(s, listOf(a, b)))
    }
    if (!lenient) return null
    // Held on an "L", a "V" or a zigzag: straight segments between its corners
    val line = resample(pts, 80, closed = false)
    val corners = cleanCorners(simplify(line, max(4f, diag * 0.05f)), diag, closed = false)
    if (corners.size !in 3..7) return null
    var err = 0f
    for (p in line) err += distToPolyline(p, corners)
    if (err / line.size / diag > 0.035f) return null
    return Fit(Shape.POLYLINE, withPoints(s, snapEdges(corners, closed = false)))
  }

  // ---- Closed strokes ----

  /** Where the loop closes (the point near the start, after most of the path), or -1 if it's open. */
  private fun closingIndex(pts: List<P>, pathLen: Float, diag: Float, lenient: Boolean): Int {
    if (pts.size < 6 || pathLen < diag * 1.7f) return -1
    val tol = max(16f, diag * (if (lenient) 0.25f else 0.2f))
    val start = pts.first()
    var walked = 0f
    var best = -1; var bestD = Float.MAX_VALUE
    for (i in 1 until pts.size) {
      walked += dist(pts[i - 1], pts[i])
      if (walked < pathLen * 0.6f) continue
      val d = dist(pts[i], start)
      if (d < bestD) { bestD = d; best = i }
    }
    return if (best > 0 && bestD <= tol) best else -1
  }

  /**
   * The corners of a closed outline. The loop is opened at the point farthest from its centre
   * (always a corner on a polygon), so a shape started halfway along a side has no extra corner.
   */
  private fun polygonCorners(ring: List<P>, diag: Float): List<P> {
    val cx = ring.sumOf { it.x.toDouble() }.toFloat() / ring.size
    val cy = ring.sumOf { it.y.toDouble() }.toFloat() / ring.size
    var k = 0; var far = -1f
    for (i in ring.indices) { val d = hypot(ring[i].x - cx, ring[i].y - cy); if (d > far) { far = d; k = i } }
    val seq = ring.subList(k, ring.size) + ring.subList(0, k) + ring[k]
    return cleanCorners(simplify(seq, max(3f, diag * 0.045f)).dropLast(1), diag, closed = true)
  }

  /** Drops corners that are nearly straight or that make a very short side. */
  private fun cleanCorners(input: List<P>, diag: Float, closed: Boolean): List<P> {
    val c = input.toMutableList()
    var changed = true
    while (changed && c.size > (if (closed) 3 else 2)) {
      changed = false
      val n = c.size
      val range = if (closed) 0 until n else 1 until n - 1
      // The flattest corner first
      var worst = -1; var worstAngle = 0f
      for (i in range) {
        val ang = cornerAngle(c[(i + n - 1) % n], c[i], c[(i + 1) % n])
        val shortSide = min(dist(c[(i + n - 1) % n], c[i]), dist(c[i], c[(i + 1) % n])) < diag * 0.1f
        val score = if (shortSide) 360f else ang
        if ((ang > 150f || shortSide) && score > worstAngle) { worst = i; worstAngle = score }
      }
      if (worst >= 0) { c.removeAt(worst); changed = true }
    }
    return c
  }

  private fun polygonFit(s: InkStroke, corners: List<P>, diag: Float): Fit {
    if (corners.size == 4 && corners.indices.all { abs(cornerAngle(corners[(it + 3) % 4], corners[it], corners[(it + 1) % 4]) - 90f) < 25f }) {
      val rect = squareUp(corners)
      return Fit(Shape.RECTANGLE, withPoints(s, rect + rect.first()))
    }
    val snapped = snapEdges(corners, closed = true)
    val shape = if (corners.size == 3) Shape.TRIANGLE else Shape.POLYGON
    return Fit(shape, withPoints(s, snapped + snapped.first()))
  }

  /**
   * A true rectangle through four rough corners: turned to the sides' average angle (level when
   * that's within 8°), each side halfway between the two drawn corners, and a square when the
   * sides are within 10% of each other.
   */
  private fun squareUp(c: List<P>): List<P> {
    var sx = 0.0; var sy = 0.0
    for (i in 0 until 4) {
      val a = c[i]; val b = c[(i + 1) % 4]
      val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
      val len = dist(a, b).toDouble()
      sx += len * cos(4 * ang); sy += len * sin(4 * ang)
    }
    var theta = (atan2(sy, sx) / 4).toFloat()
    if (abs(theta) < 8f * PI.toFloat() / 180f) theta = 0f
    val ux = cos(theta); val uy = sin(theta)
    val us = c.map { it.x * ux + it.y * uy }.sorted()
    val vs = c.map { -it.x * uy + it.y * ux }.sorted()
    var u0 = (us[0] + us[1]) / 2f; var u1 = (us[2] + us[3]) / 2f
    var v0 = (vs[0] + vs[1]) / 2f; var v1 = (vs[2] + vs[3]) / 2f
    val w = u1 - u0; val h = v1 - v0
    if (abs(w - h) < max(w, h) * 0.1f) {
      val side = (w + h) / 2f; val uc = (u0 + u1) / 2f; val vc = (v0 + v1) / 2f
      u0 = uc - side / 2f; u1 = uc + side / 2f; v0 = vc - side / 2f; v1 = vc + side / 2f
    }
    fun back(u: Float, v: Float) = P(u * ux - v * uy, u * uy + v * ux)
    return listOf(back(u0, v0), back(u1, v0), back(u1, v1), back(u0, v1))
  }

  /** Sides within 6° of level or upright are made exactly level or upright. */
  private fun snapEdges(input: List<P>, closed: Boolean): List<P> {
    val c = input.toMutableList()
    val n = c.size
    val edges = if (closed) n else n - 1
    val limit = 6f * PI.toFloat() / 180f
    for (i in 0 until edges) {
      val j = (i + 1) % n
      val a = c[i]; val b = c[j]
      val ang = atan2(b.y - a.y, b.x - a.x)
      val off = abs(ang) % (PI.toFloat() / 2f)
      val nearAxis = min(off, PI.toFloat() / 2f - off)
      if (nearAxis > limit) continue
      if (abs(b.x - a.x) >= abs(b.y - a.y)) { val y = (a.y + b.y) / 2f; c[i] = P(a.x, y); c[j] = P(b.x, y) }
      else { val x = (a.x + b.x) / 2f; c[i] = P(x, a.y); c[j] = P(x, b.y) }
    }
    return c
  }

  private class Ellipse(val cx: Float, val cy: Float, val a: Float, val b: Float, val theta: Float)

  /** The ellipse along the outline's main axes (from its spread), sized to its extents on them. */
  private fun fitEllipse(ring: List<P>): Ellipse? {
    val mx = ring.sumOf { it.x.toDouble() } / ring.size
    val my = ring.sumOf { it.y.toDouble() } / ring.size
    var sxx = 0.0; var syy = 0.0; var sxy = 0.0
    for (p in ring) { val dx = p.x - mx; val dy = p.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
    var theta = (0.5 * atan2(2 * sxy, sxx - syy)).toFloat()
    val ux = cos(theta); val uy = sin(theta)
    val us = ring.map { it.x * ux + it.y * uy }
    val vs = ring.map { -it.x * uy + it.y * ux }
    val u0 = us.min(); val u1 = us.max(); val v0 = vs.min(); val v1 = vs.max()
    var a = (u1 - u0) / 2f; var b = (v1 - v0) / 2f
    if (a < 1f || b < 1f) return null
    val uc = (u0 + u1) / 2f; val vc = (v0 + v1) / 2f
    val cx = uc * ux - vc * uy; val cy = uc * uy + vc * ux
    if (abs(a - b) < max(a, b) * 0.1f) { val r = (a + b) / 2f; a = r; b = r; theta = 0f }
    else {
      // Level or upright when it's within 10° of it
      val deg = 10f * PI.toFloat() / 180f
      val half = PI.toFloat() / 2f
      val off = ((theta % half) + half) % half
      if (off < deg) theta -= off else if (half - off < deg) theta += half - off
    }
    return Ellipse(cx, cy, a, b, theta)
  }

  private fun meanDistToEllipse(ring: List<P>, e: Ellipse): Float {
    val ux = cos(e.theta); val uy = sin(e.theta)
    var sum = 0f
    for (p in ring) {
      val dx = p.x - e.cx; val dy = p.y - e.cy
      val u = dx * ux + dy * uy; val v = -dx * uy + dy * ux
      val r = hypot(u / e.a, v / e.b)
      // Distance along the ray from the centre to the outline
      sum += if (r < 1e-4f) min(e.a, e.b) else abs(1f - 1f / r) * hypot(u, v)
    }
    return sum / ring.size
  }

  private fun ellipseFit(s: InkStroke, e: Ellipse): Fit {
    val ux = cos(e.theta); val uy = sin(e.theta)
    val ring = (0..72).map { i ->
      val t = 2.0 * PI * i / 72
      val u = e.a * cos(t).toFloat(); val v = e.b * sin(t).toFloat()
      P(e.cx + u * ux - v * uy, e.cy + u * uy + v * ux)
    }
    return Fit(Shape.ELLIPSE, withPoints(s, ring))
  }

  // ---- Geometry ----

  private fun dedupe(pts: List<P>): List<P> {
    val out = ArrayList<P>(pts.size)
    for (p in pts) if (out.isEmpty() || dist(out.last(), p) > 0.5f) out += p
    return out
  }

  private fun length(pts: List<P>, closed: Boolean): Float {
    var l = 0f
    for (i in 1 until pts.size) l += dist(pts[i - 1], pts[i])
    if (closed && pts.size > 1) l += dist(pts.last(), pts.first())
    return l
  }

  /** [n] points evenly spaced along the path (around the loop when [closed]). */
  private fun resample(pts: List<P>, n: Int, closed: Boolean): List<P> {
    val path = if (closed) pts + pts.first() else pts
    val total = length(path, closed = false)
    if (total <= 0f) return listOf(pts.first())
    val steps = if (closed) n else n - 1
    val step = total / steps
    val out = ArrayList<P>(n)
    out += path[0]
    var i = 1; var prev = path[0]; var acc = 0f
    while (out.size < n && i < path.size) {
      val d = dist(prev, path[i])
      if (acc + d >= step && d > 0f) {
        val t = (step - acc) / d
        val q = P(prev.x + (path[i].x - prev.x) * t, prev.y + (path[i].y - prev.y) * t)
        out += q; prev = q; acc = 0f
      } else { acc += d; prev = path[i]; i++ }
    }
    if (!closed && out.size < n) out += path.last()
    return out
  }

  private fun cornerAngle(a: P, b: P, c: P): Float {
    val v1x = a.x - b.x; val v1y = a.y - b.y; val v2x = c.x - b.x; val v2y = c.y - b.y
    val cosA = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y)).coerceAtLeast(1e-3f)
    return acos(min(1f, max(-1f, cosA))) * 180f / PI.toFloat()
  }

  private fun meanDistToPolygon(ring: List<P>, poly: List<P>): Float {
    var sum = 0f
    for (p in ring) {
      var best = Float.MAX_VALUE
      for (i in poly.indices) best = min(best, distToSegment(p, poly[i], poly[(i + 1) % poly.size]))
      sum += best
    }
    return sum / ring.size
  }

  private fun distToPolyline(p: P, line: List<P>): Float {
    var best = Float.MAX_VALUE
    for (i in 1 until line.size) best = min(best, distToSegment(p, line[i - 1], line[i]))
    return best
  }
}
