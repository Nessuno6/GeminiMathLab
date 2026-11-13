package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRenderTest {
  private fun at(input: String, x: Double): Double {
    val e = GraphRender.normalise(input)
    assertNotNull("can't read '$input'", e)
    return LocalMathEngine.evalAt(e!!, x)
  }

  @Test fun calculatorKeysProduceReadableExpressions() {
    assertEquals(9.0, at("y=x^2", 3.0), 1e-9)
    assertEquals(9.0, at("f(x)=x^2", 3.0), 1e-9)
    assertEquals(2.0, at("√(x)", 4.0), 1e-9)
    assertEquals(3.0, at("∛(27)", 0.0), 1e-9)
    assertEquals(Math.PI * 2, at("2×π", 0.0), 1e-9)
    assertEquals(Math.E, at("e^(x)", 1.0), 1e-9)
    assertEquals(4.0, at("abs(x)", -4.0), 1e-9)
    assertEquals(2.0, at("8÷4", 0.0), 1e-9)
    assertEquals(-1.0, at("x−3", 2.0), 1e-9)
    assertEquals(0.0, at("sin(x)", 0.0), 1e-9)
    assertEquals(6.0, at("2x", 3.0), 1e-9)
  }

  @Test fun equationsAndJunkAreRejected() {
    assertNull(GraphRender.normalise("x^2+y^2=1"))
    assertNull(GraphRender.normalise(""))
    assertNull(GraphRender.normalise("sin("))
  }

  @Test fun panAndZoom() {
    val w = GraphWindow(-10.0, 10.0, -5.0, 5.0)
    val z = w.zoom(2f, 50f, 50f, 100f, 100f) // zoom in about the centre
    assertEquals(-5.0, z.xMin, 1e-9); assertEquals(5.0, z.xMax, 1e-9)
    val p = w.pan(10f, 0f, 100f, 100f) // drag right: view moves left
    assertEquals(-12.0, p.xMin, 1e-5)
    assertTrue(GraphRender.niceStep(20.0) in listOf(2.0, 5.0))
  }

  @Test fun multiCurveSplitAndSpecialPoints() {
    val expr = "x^2 - 4; x + 2"
    val parts = GraphRender.splitExpressions(expr)
    assertEquals(listOf("x^2 - 4", "x + 2"), parts)
    assertEquals(2, GraphRender.curvesFor(expr).size)

    val win = GraphWindow(-5.0, 5.0, -6.0, 10.0)
    val pts = GraphRender.specialPoints(expr, win)

    // Parabola x^2 - 4 has roots at -2 and 2, minimum at (0, -4), and intersections with x+2 at (-2,0) & (3,5)
    assertTrue("Expected Min at (0, -4) in $pts", pts.any { it.kind == "Min" && kotlin.math.abs(it.x) < 1e-3 && kotlin.math.abs(it.y + 4.0) < 1e-3 })
    assertTrue("Expected Root at x=2 in $pts", pts.any { it.kind == "Root" && kotlin.math.abs(it.x - 2.0) < 1e-3 })
    assertTrue("Expected Intersection at (3, 5) in $pts", pts.any { it.kind == "Intersection" && kotlin.math.abs(it.x - 3.0) < 1e-3 && kotlin.math.abs(it.y - 5.0) < 1e-3 })

    // snapTrace near x=2.05 should snap onto the Root at x=2.0
    val snapped = GraphRender.snapTrace("x^2 - 4", 2.05, win)
    assertNotNull(snapped)
    assertEquals("Root", snapped!!.kind)
    assertEquals(2.0, snapped.x, 1e-3)

    // Multi-curve autoWindow fits both expressions
    val auto = GraphRender.autoWindow("x^2 - 4; x + 2")
    assertTrue(auto.yMin <= -4.0 && auto.yMax >= 4.0)
  }
}
