package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTweaksTest {
  @Test fun penSizeHasHundredEvenSteps() {
    assertEquals(1, widthToSlider(sliderToWidth(1)))
    assertEquals(100, widthToSlider(sliderToWidth(100)))
    for (s in 1..100) assertEquals(s, widthToSlider(sliderToWidth(s)))
    val gaps = (1 until 100).map { sliderToWidth(it + 1) - sliderToWidth(it) }
    assertTrue(gaps.all { kotlin.math.abs(it - gaps[0]) < 1e-4f })
    // Old sizes still fit (the old 1–10 went from 3.55 to 22 px)
    assertTrue(sliderToWidth(1) < 3.55f && sliderToWidth(100) > 22f)
  }

  private val stroke = InkStroke(listOf(TimedPoint(0f, 0f, 0), TimedPoint(10f, 0f, 1)), 4f, 0xFF000000L, false)

  @Test fun scaleAroundAPoint() {
    val s = LassoTools.scale(stroke, 2f, 0f, 0f)
    assertEquals(20f, s.points[1].x, 1e-4f)
    assertEquals(8f, s.width, 1e-4f)
  }

  @Test fun rotateQuarterTurn() {
    val r = LassoTools.rotate(stroke, (Math.PI / 2).toFloat(), 0f, 0f)
    assertEquals(0f, r.points[1].x, 1e-3f)
    assertEquals(10f, r.points[1].y, 1e-3f)
  }

  private fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
    InkStroke(listOf(TimedPoint(x0, y0, 0), TimedPoint(x1, y1, 1)), 3f, 0xFF000000L, false)

  @Test fun orientedBoxFollowsTheTurn() {
    val s = line(0f, 0f, 100f, 0f)
    val flat = LassoTools.orientedBox(listOf(s), 0f)!!
    assertEquals(100f, flat[1].x - flat[0].x, 1e-3f)
    // Turned 30°, the stroke and its box turn together: the box is still 100 long and flat on it
    val a = (Math.PI / 6).toFloat()
    val turned = LassoTools.rotate(s, a, 0f, 0f)
    val box = LassoTools.orientedBox(listOf(turned), a)!!
    assertEquals(100f, kotlin.math.hypot(box[1].x - box[0].x, box[1].y - box[0].y), 1e-2f)
    assertEquals(0f, kotlin.math.hypot(box[3].x - box[0].x, box[3].y - box[0].y), 1e-2f)
  }

  @Test fun stretchOnlyAlongOneSide() {
    val s = LassoTools.stretch(line(0f, 0f, 10f, 10f), 2f, 1f, 0f, 0f, 0f)
    assertEquals(20f, s.points[1].x, 1e-4f)
    assertEquals(10f, s.points[1].y, 1e-4f)
  }

  @Test fun alignLevelsASlopedLine() {
    // Three "letters" climbing to the right, and a second line starting further right
    val l1 = (0..2).map { i -> line(i * 40f, 100f - i * 10f, i * 40f + 20f, 120f - i * 10f) }
    val l2 = (0..1).map { i -> line(60f + i * 40f, 300f, 80f + i * 40f, 320f) }
    val pairs = LassoTools.alignHandwriting(l1 + l2)
    val out = pairs.associate { (o, n) -> System.identityHashCode(o) to n }
    val bottoms = l1.map { LassoTools.bounds(listOf(out[System.identityHashCode(it)]!!))!!.bottom }
    assertTrue("$bottoms", bottoms.max() - bottoms.min() < 3f)
    val lefts = listOf(l1, l2).map { l -> LassoTools.bounds(l.map { out[System.identityHashCode(it)]!! })!!.left }
    assertEquals(lefts[0], lefts[1], 0.5f)
  }

  @Test fun layersAreNumberedFromZero() {
    val old = listOf(NoteLayer(0, "Layer 1"), NoteLayer(1, "Layer 2"), NoteLayer(2, "Sketch"))
    assertEquals(listOf("Layer 0", "Layer 1", "Sketch"), LayerNames.renumber(old).map { it.name })
    val new = listOf(NoteLayer(0, "Layer 0"), NoteLayer(1, "Layer 1"))
    assertEquals(new, LayerNames.renumber(new))
  }

  @Test fun laserTrailFadesAndClears() {
    assertEquals(1f, LaserTrail.alpha(0), 0f)
    assertTrue(LaserTrail.alpha(LaserTrail.HOLD_MS + 100) in 0.01f..0.99f)
    assertEquals(0f, LaserTrail.alpha(LaserTrail.LIFE_MS), 0f)
    val t = LaserTrail()
    t.start(0f, 0f, 1000); t.add(5f, 5f, 1010); t.lift()
    assertTrue(t.prune(1100))
    assertFalse(t.prune(1000 + LaserTrail.LIFE_MS + 50))
  }
}
