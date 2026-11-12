package com.deang.geminimath

import kotlin.math.hypot
import kotlin.math.max

/**
 * Stroke stabiliser, the "pulled string" (lazy brush) method used by Clip Studio, Krita, ZBrush
 * (Lazy Radius) and Blender (Lazy Mouse): the ink is pulled behind the pen on a string of length
 * [radius]. While the pen moves inside the radius nothing is drawn, so wobble and jitter vanish;
 * beyond it, the ink follows along the pen's path, smoothing curves. It depends on distance only,
 * so it behaves the same whatever the speed or how often the tablet reports the pen.
 *
 * When the pen lifts, [finish] draws the last stretch to where the pen actually is ("finish
 * line"), so strokes end where you stopped instead of short of it.
 */
object StrokeStabilizer {
  /** String length in screen pixels for stabiliser [level] 1–10 (0 = off), before dividing by zoom. */
  fun radiusPx(level: Int, density: Float): Float = if (level <= 0) 0f else level * 2.6f * density

  /**
   * Where the ink goes when the pen is at ([px], [py]) and the ink is at ([bx], [by]). Null when
   * the pen is still within the string's length (the ink doesn't move).
   */
  fun pull(bx: Float, by: Float, px: Float, py: Float, radius: Float): Pair<Float, Float>? {
    val dx = px - bx; val dy = py - by
    val d = hypot(dx, dy)
    if (d <= radius) return null
    val k = (d - radius) / d
    return (bx + dx * k) to (by + dy * k)
  }

  /** Runs [points] (raw pen positions) through the string; the first point is where the ink starts. */
  fun apply(points: List<TimedPoint>, radius: Float): List<TimedPoint> {
    if (radius <= 0f || points.size < 2) return points
    val out = ArrayList<TimedPoint>(points.size)
    out += points[0]
    for (i in 1 until points.size) {
      val last = out.last(); val p = points[i]
      pull(last.x, last.y, p.x, p.y, radius)?.let { (x, y) -> out += TimedPoint(x, y, p.t) }
    }
    return out
  }

  /**
   * The finishing stretch from the ink's last point to the pen, eased in a few steps so the end of
   * the stroke curves into place instead of ending in a straight jump.
   */
  fun finish(last: TimedPoint, pen: TimedPoint, previous: TimedPoint?): List<TimedPoint> {
    val d = hypot(pen.x - last.x, pen.y - last.y)
    if (d < 0.5f) return emptyList()
    // Keep going in the stroke's direction a little before turning to the pen (a quadratic curve)
    val dirX: Float; val dirY: Float
    if (previous != null) {
      val pd = max(0.001f, hypot(last.x - previous.x, last.y - previous.y))
      dirX = (last.x - previous.x) / pd; dirY = (last.y - previous.y) / pd
    } else { dirX = 0f; dirY = 0f }
    val cx = last.x + dirX * d * 0.35f; val cy = last.y + dirY * d * 0.35f
    val steps = (d / 3f).toInt().coerceIn(2, 12)
    return (1..steps).map { i ->
      val t = i / steps.toFloat(); val u = 1 - t
      TimedPoint(
        u * u * last.x + 2 * u * t * cx + t * t * pen.x,
        u * u * last.y + 2 * u * t * cy + t * t * pen.y,
        last.t + ((pen.t - last.t) * t).toLong()
      )
    }
  }
}
