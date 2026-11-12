package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloodFillTest {
  private fun grid(vararg rows: String): Triple<BooleanArray, Int, Int> {
    val h = rows.size; val w = rows[0].length
    return Triple(BooleanArray(w * h) { rows[it / w][it % w] == '#' }, w, h)
  }

  @Test fun fillsInsideAClosedBox() {
    val (g, w, h) = grid(
      "..........",
      ".########.",
      ".#......#.",
      ".#......#.",
      ".########.",
      ".........."
    )
    val m = FloodFill.fill(g, w, h, 4, 3)
    assertNotNull(m)
    assertEquals(12, m!!.count { it }) // the 6×2 inside
    assertTrue(!m[0]) // outside untouched
  }

  @Test fun openShapeIsRejected() {
    val (g, w, h) = grid(
      "..........",
      ".########.",
      ".#.......#",
      ".#......#.",
      ".####.###.",
      ".........."
    )
    assertNull(FloodFill.fill(g, w, h, 4, 3))
  }

  @Test fun startingOnAWallFillsNothing() {
    val (g, w, h) = grid("###", "#.#", "###")
    assertNull(FloodFill.fill(g, w, h, 0, 0))
  }

  @Test fun dilateGrowsByOne() {
    val w = 5; val h = 5
    val m = BooleanArray(25).also { it[12] = true }
    assertEquals(5, FloodFill.dilate(m, w, h, 1).count { it })
  }
}
