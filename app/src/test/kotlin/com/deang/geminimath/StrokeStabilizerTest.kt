package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class StrokeStabilizerTest {
  private fun pt(x: Float, y: Float, t: Long = 0) = TimedPoint(x, y, t)

  @Test fun jitterInsideTheStringIsIgnored() {
    // A hand shaking ±3 px around one spot: with a 10 px string, nothing is drawn
    val shaky = (0..50).map { pt(100f + 3f * sin(it * 1.7f), 100f + 3f * sin(it * 2.3f), it.toLong()) }
    assertEquals(1, StrokeStabilizer.apply(shaky, 10f).size)
  }

  @Test fun followsAStraightLineAStringLengthBehind() {
    val line = (0..100).map { pt(it * 2f, 0f, it.toLong()) }
    val out = StrokeStabilizer.apply(line, 10f)
    assertEquals(190f, out.last().x, 0.01f) // 10 px behind the pen at 200
    assertTrue(out.all { abs(it.y) < 1e-4f })
  }

  @Test fun wobblyLineComesOutSmoother() {
    // A line with 4 px wobble: the stabilised version wobbles much less
    val wobbly = (0..300).map { pt(it * 1f, 4f * sin(it * 0.9f), it.toLong()) }
    val out = StrokeStabilizer.apply(wobbly, 12f)
    fun roughness(p: List<TimedPoint>) = p.zipWithNext().sumOf { (a, b) -> abs(b.y - a.y).toDouble() } / p.size
    assertTrue("${roughness(out)} vs ${roughness(wobbly)}", roughness(out) < roughness(wobbly) * 0.3)
  }

  @Test fun finishReachesThePen() {
    val end = StrokeStabilizer.finish(pt(190f, 0f, 90), pt(200f, 0f, 100), pt(185f, 0f, 85))
    assertEquals(200f, end.last().x, 0.01f)
    assertEquals(0f, end.last().y, 0.01f)
    // No jump: consecutive points are close together
    val all = listOf(pt(190f, 0f)) + end
    assertTrue(all.zipWithNext().all { (a, b) -> hypot(b.x - a.x, b.y - a.y) < 6f })
  }

  @Test fun offMeansUnchanged() {
    val line = listOf(pt(0f, 0f), pt(1f, 1f), pt(2f, 0f))
    assertEquals(line, StrokeStabilizer.apply(line, 0f))
    assertEquals(0f, StrokeStabilizer.radiusPx(0, 2.75f))
  }
}
