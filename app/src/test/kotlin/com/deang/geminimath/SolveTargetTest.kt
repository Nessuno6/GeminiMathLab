package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Test

class SolveTargetTest {
  private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, t: Long) =
    InkStroke(listOf(TimedPoint(x0, y0, t), TimedPoint(x1, y1, t + 10)))

  @Test fun bigDrawingIsLeftOut() {
    val chars = (0 until 8).map { stroke(100f + it * 50, 100f, 130f + it * 50, 140f, it * 100L) }
    val circle = InkStroke((0..36).map { a ->
      val r = Math.toRadians(a * 10.0)
      TimedPoint((600 + 300 * Math.cos(r)).toFloat(), (500 + 300 * Math.sin(r)).toFloat(), 5000L + a)
    })
    val picked = SolveTarget.pick(chars + circle)
    assertEquals(chars, picked)
  }

  @Test fun newestLineWins() {
    val first = (0 until 5).map { stroke(100f + it * 50, 100f, 130f + it * 50, 140f, it * 100L) }
    val second = (0 until 5).map { stroke(100f + it * 50, 400f, 130f + it * 50, 440f, 2000L + it * 100) }
    assertEquals(second, SolveTarget.pick(first + second))
  }

  @Test fun fractionStaysOneLine() {
    val num = stroke(100f, 100f, 130f, 140f, 0)
    val bar = stroke(90f, 150f, 200f, 152f, 100)
    val den = stroke(100f, 160f, 130f, 200f, 200)
    assertEquals(3, SolveTarget.pick(listOf(num, bar, den)).size)
  }
}
