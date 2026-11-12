package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class InkToolsTest {
  private fun stroke(vararg xy: Pair<Float, Float>) =
    InkStroke(xy.mapIndexed { i, (x, y) -> TimedPoint(x, y, i * 10L) })

  /** A wobbly polyline through [corners], [n] points per side. */
  private fun wobbly(corners: List<Pair<Float, Float>>, n: Int = 12, wobble: Float = 3f): InkStroke {
    val pts = ArrayList<Pair<Float, Float>>()
    for (i in 0 until corners.size - 1) {
      val (ax, ay) = corners[i]; val (bx, by) = corners[i + 1]
      for (k in 0 until n) {
        val t = k / n.toFloat()
        val w = if (k % 2 == 0) wobble else -wobble
        pts += (ax + (bx - ax) * t + w * 0.3f) to (ay + (by - ay) * t + w)
      }
    }
    pts += corners.last()
    return stroke(*pts.toTypedArray())
  }

  @Test fun historyUndoesAndRedoesSnapshots() {
    val h = InkHistory()
    val strokes = mutableListOf<InkStroke>()
    h.checkpoint(strokes); strokes += stroke(0f to 0f, 10f to 10f)
    h.checkpoint(strokes); strokes += stroke(5f to 5f, 20f to 20f)
    assertTrue(h.undo(strokes)); assertEquals(1, strokes.size)
    assertTrue(h.undo(strokes)); assertEquals(0, strokes.size)
    assertFalse(h.undo(strokes))
    assertTrue(h.redo(strokes)); assertEquals(1, strokes.size)
    assertTrue(h.canRedo)
    h.checkpoint(strokes) // a new change clears redo
    assertFalse(h.canRedo)
  }

  @Test fun wobblyLineBecomesStraight() {
    val fit = InkTools.fitShape(wobbly(listOf(100f to 100f, 400f to 130f), n = 30, wobble = 2f))
    assertNotNull(fit)
    assertEquals(InkTools.Shape.LINE, fit!!.shape)
    assertEquals(2, fit.stroke.points.size)
  }

  @Test fun roughRectangleIsSquaredUp() {
    val fit = InkTools.fitShape(wobbly(listOf(100f to 100f, 400f to 104f, 398f to 300f, 102f to 296f, 100f to 100f)))
    assertNotNull(fit)
    assertEquals(InkTools.Shape.RECTANGLE, fit!!.shape)
    val ys = fit.stroke.points.map { it.y }.toSet()
    assertEquals(2, ys.size) // only top and bottom edges: it's level
  }

  @Test fun roughTriangleIsRecognised() {
    val fit = InkTools.fitShape(wobbly(listOf(100f to 300f, 250f to 80f, 400f to 300f, 100f to 300f)))
    assertEquals(InkTools.Shape.TRIANGLE, fit?.shape)
  }

  @Test fun roughCircleBecomesEllipse() {
    val pts = (0..40).map { i ->
      val a = 2 * PI * i / 40
      val r = 150f + if (i % 2 == 0) 4f else -4f
      (300f + r * cos(a).toFloat()) to (300f + r * sin(a).toFloat())
    }
    assertEquals(InkTools.Shape.ELLIPSE, InkTools.fitShape(stroke(*pts.toTypedArray()))?.shape)
  }

  @Test fun handwritingIsLeftAlone() {
    // An "S"-like squiggle and a small letter
    val s = stroke(300f to 100f, 200f to 90f, 150f to 150f, 250f to 200f, 320f to 260f, 260f to 330f, 150f to 320f)
    assertNull(InkTools.fitShape(s))
    assertNull(InkTools.fitShape(stroke(10f to 10f, 20f to 30f, 30f to 10f, 40f to 30f, 50f to 10f, 55f to 20f)))
  }

  private fun corners(f: InkTools.Fit) = f.stroke.points.dropLast(1)

  @Test fun squareStartedHalfwayAlongASideHasFourCorners() {
    val fit = InkTools.fitShape(wobbly(listOf(200f to 100f, 300f to 102f, 301f to 300f, 99f to 298f, 100f to 101f, 200f to 100f)), lenient = true)
    assertEquals(InkTools.Shape.RECTANGLE, fit?.shape)
    val c = corners(fit!!)
    assertEquals(4, c.size)
    // A square: all sides the same
    val sides = c.indices.map { i -> val a = c[i]; val b = c[(i + 1) % 4]; kotlin.math.hypot(a.x - b.x, a.y - b.y) }
    assertTrue("sides $sides", sides.max() - sides.min() < 1f)
  }

  @Test fun squareWithOvershootAndHeldEndIsClean() {
    // Goes round, past the start, then the pen is held still (many points in one place)
    val base = wobbly(listOf(100f to 100f, 300f to 103f, 302f to 300f, 98f to 297f, 100f to 100f, 160f to 102f))
    val held = base.points + (1..40).map { TimedPoint(160f + (it % 3), 102f + (it % 2), 2000L + it) }
    val fit = InkTools.fitShape(base.copy(points = held), lenient = true)
    assertEquals(InkTools.Shape.RECTANGLE, fit?.shape)
    assertEquals(4, corners(fit!!).size)
  }

  @Test fun squareWithGapIsStillClosed() {
    val fit = InkTools.fitShape(wobbly(listOf(100f to 100f, 300f to 100f, 300f to 300f, 100f to 300f, 100f to 140f)), lenient = true)
    assertEquals(InkTools.Shape.RECTANGLE, fit?.shape)
  }

  @Test fun tiltedRectangleKeepsItsAngle() {
    val a = 30.0 * PI / 180
    fun rot(x: Float, y: Float) = (200f + x * cos(a).toFloat() - y * sin(a).toFloat()) to (200f + x * sin(a).toFloat() + y * cos(a).toFloat())
    val fit = InkTools.fitShape(wobbly(listOf(rot(0f, 0f), rot(240f, 0f), rot(240f, 120f), rot(0f, 120f), rot(0f, 0f))), lenient = true)
    assertEquals(InkTools.Shape.RECTANGLE, fit?.shape)
    val c = corners(fit!!)
    val ang = kotlin.math.atan2(c[1].y - c[0].y, c[1].x - c[0].x) * 180 / PI
    val off = ((ang % 90) + 90) % 90
    assertTrue("angle $ang", kotlin.math.abs(off - 30) < 4)
  }

  @Test fun circleStartedAnywhereIsACircle() {
    val pts = (0..60).map { i ->
      val t = 2 * PI * (i / 56.0) + 1.3 // a little more than once round
      val r = 120f + if (i % 3 == 0) 3f else -2f
      (300f + r * cos(t).toFloat()) to (300f + r * sin(t).toFloat())
    }
    val fit = InkTools.fitShape(stroke(*pts.toTypedArray()), lenient = true)
    assertEquals(InkTools.Shape.ELLIPSE, fit?.shape)
  }

  @Test fun heldLShapeBecomesStraightSegments() {
    val fit = InkTools.fitShape(wobbly(listOf(100f to 100f, 102f to 300f, 300f to 302f)), lenient = true)
    assertEquals(InkTools.Shape.POLYLINE, fit?.shape)
    assertEquals(3, fit!!.stroke.points.size)
  }

  @Test fun heldScribbleIsLeftAlone() {
    val s = stroke(300f to 100f, 200f to 90f, 150f to 150f, 250f to 200f, 320f to 260f, 260f to 330f, 150f to 320f, 120f to 250f, 200f to 240f)
    assertNull(InkTools.fitShape(s, lenient = true))
  }

  @Test fun densifyFillsSidesAndKeepsCorners() {
    val sq = stroke(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f, 0f to 0f)
    val d = InkTools.densify(sq, 5f)
    assertTrue(d.points.size >= 80)
    for (i in 1 until d.points.size) assertTrue(kotlin.math.hypot(d.points[i].x - d.points[i - 1].x, d.points[i].y - d.points[i - 1].y) <= 5.01f)
    sq.points.forEach { c -> assertTrue(d.points.any { it.x == c.x && it.y == c.y }) }
  }

  @Test fun eraserHitsTheMiddleOfAShapeSide() {
    // The side from (0,0) to (300,0) has no points in its middle
    assertTrue(LassoTools.segmentsCross(0f, 0f, 300f, 0f, 150f, -20f, 150f, 20f))
    val pieces = LassoTools.eraseArea(InkTools.densify(stroke(0f to 0f, 300f to 0f), 5f), 150f, 0f, 10f)
    assertEquals(2, pieces.size)
  }

  @Test fun lassoFindsAShapeByItsSides() {
    // A square stored as its corners only, and a loop that crosses its top side without any corner
    val sq = stroke(0f to 0f, 300f to 0f, 300f to 300f, 0f to 300f, 0f to 0f)
    val loop = listOf(TimedPoint(100f, -50f, 0), TimedPoint(200f, -50f, 0), TimedPoint(200f, 50f, 0), TimedPoint(100f, 50f, 0))
    assertEquals(1, LassoTools.strokesInside(listOf(sq), loop, partial = true).size)
    assertEquals(0, LassoTools.strokesInside(listOf(sq), loop, partial = false).size)
    val around = listOf(TimedPoint(-20f, -20f, 0), TimedPoint(320f, -20f, 0), TimedPoint(320f, 320f, 0), TimedPoint(-20f, 320f, 0))
    assertEquals(1, LassoTools.strokesInside(listOf(sq), around).size)
  }

  @Test fun tapOnAShapeSideIsNearIt() {
    val line = stroke(0f to 0f, 300f to 0f)
    assertEquals(5f, LassoTools.distanceTo(line, 150f, 5f), 0.01f)
  }

  @Test fun smallWritingIsEnlargedForReading() {
    val small = listOf(stroke(0f to 0f, 10f to 20f), stroke(10f to 0f, 0f to 20f), stroke(30f to 0f, 30f to 25f))
    val big = normalizeInkSize(small)
    val b = LassoTools.bounds(big)!!
    assertTrue("height ${b.height}", b.height > 55f)
    // Writing of the usual size is left as it is
    val normal = listOf(stroke(0f to 0f, 40f to 60f), stroke(40f to 0f, 0f to 60f), stroke(80f to 0f, 80f to 70f))
    assertTrue(normalizeInkSize(normal) === normal)
  }

  @Test fun cleanUpKeepsEndsAndRemovesJitter() {
    val raw = wobbly(listOf(0f to 0f, 300f to 0f), n = 60, wobble = 1f)
    val clean = InkTools.cleanUp(raw)
    assertEquals(raw.points.first().x, clean.points.first().x, 0.01f)
    assertEquals(raw.points.last().x, clean.points.last().x, 0.01f)
    val maxDy = clean.points.maxOf { kotlin.math.abs(it.y) }
    assertTrue("jitter left: $maxDy", maxDy < 1.5f)
  }
}
