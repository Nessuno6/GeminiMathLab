package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Uses a real file exported from Samsung Notes on a Galaxy Tab S10 Ultra (6 strokes: "2x+1"). */
class SdocxParserTest {
  private val doc by lazy {
    val bytes = javaClass.classLoader!!.getResourceAsStream("samsung_test_note.sdocx")!!.readBytes()
    SdocxParser.parse(bytes)
  }

  @Test fun readsPageAndStrokes() {
    assertEquals(1, doc.pages.size)
    val page = doc.pages[0]
    println("page ${page.width}x${page.height}, strokes=${page.strokes.size}")
    page.strokes.forEachIndexed { i, s ->
      println("stroke $i: n=${s.xs.size} first=(${s.xs.first()}, ${s.ys.first()}) last=(${s.xs.last()}, ${s.ys.last()}) " +
        "colour=${java.lang.Long.toHexString(s.colorArgb)} size=${s.penSize} p0=${s.pressures.first()}")
    }
    assertEquals(1848, page.width)
    assertEquals(6, page.strokes.size)
  }

  @Test fun coordinatesMatchWhatWasDrawn() {
    val s = doc.pages[0].strokes
    // Stroke 0 was drawn from (700,560) to (810,700) on screen: offset (110, 140).
    // Samsung Notes showed the page zoomed 1.2×, so in page units that's (110, 140) / 1.2.
    assertEquals(110.0 / 1.2, s[0].xs.last() - s[0].xs.first(), 2.0)
    assertEquals(140.0 / 1.2, s[0].ys.last() - s[0].ys.first(), 2.0)
    // "x" (strokes 1, 2) was drawn 80 px wide on screen
    assertEquals(80.0 / 1.2, s[1].xs.last() - s[1].xs.first(), 2.0)
    // The "+" horizontal bar (stroke 3) is flat
    assertTrue(abs(s[3].ys.last() - s[3].ys.first()) < 2.0)
    // All points lie on the page
    for (st in s) for (i in st.xs.indices) {
      assertTrue(st.xs[i] in 0.0..1848.0)
      assertTrue(st.ys[i] >= 0.0)
    }
  }

  @Test fun coloursAndWidths() {
    val s = doc.pages[0].strokes
    for (i in 0..4) assertEquals(0xFF000000L, s[i].colorArgb and 0xFFFFFF00L or (s[i].colorArgb and 0xFFL))
    assertNotEquals("last stroke was drawn in Eggshell blue", 0xFF000000L, s[5].colorArgb)
    assertTrue(s.all { it.penSize > 0f && it.penSize < 100f })
  }
}
