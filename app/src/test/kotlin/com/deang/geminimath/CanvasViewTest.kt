package com.deang.geminimath

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasViewTest {
  private fun near(expected: Offset, actual: Offset) {
    assertEquals(expected.x, actual.x, 0.01f)
    assertEquals(expected.y, actual.y, 0.01f)
  }

  @Test fun defaultViewIsIdentity() {
    val v = CanvasView()
    near(Offset(120f, 340f), v.toPage(Offset(120f, 340f)))
  }

  @Test fun pageAndScreenRoundTrip() {
    val v = CanvasView(Offset(-300f, 75f), 2.5f)
    val p = Offset(512f, -48f)
    near(p, v.toPage(v.toScreen(p)))
  }

  @Test fun zoomKeepsFocusPointStill() {
    val v = CanvasView(Offset(40f, 10f), 1f)
    val focus = Offset(800f, 600f)
    val before = v.toPage(focus)
    val z = v.zoomTo(3f, focus)
    near(before, z.toPage(focus))
    assertEquals(3f, z.scale, 0f)
  }

  @Test fun zoomIsClamped() {
    assertEquals(CanvasView.MAX_SCALE, CanvasView().zoomTo(50f, Offset.Zero).scale, 0f)
    assertEquals(CanvasView.MIN_SCALE, CanvasView().zoomTo(0.01f, Offset.Zero).scale, 0f)
  }

  @Test fun pinchSpreadDoublesScaleAroundMidpoint() {
    val start = CanvasView()
    val g = PinchGesture(Offset(400f, 500f), Offset(600f, 500f), start, zoomLocked = false)
    val v = g.update(Offset(300f, 500f), Offset(700f, 500f))
    assertEquals(2f, v.scale, 0.001f)
    near(Offset(500f, 500f), v.toPage(Offset(500f, 500f)))
  }

  @Test fun pinchPansWhenFingersMoveTogether() {
    val g = PinchGesture(Offset(100f, 100f), Offset(200f, 100f), CanvasView(), zoomLocked = false)
    val v = g.update(Offset(150f, 300f), Offset(250f, 300f))
    assertEquals(1f, v.scale, 0.001f)
    near(Offset(50f, 200f), v.offset)
  }

  @Test fun lockedZoomOnlyPans() {
    val g = PinchGesture(Offset(100f, 100f), Offset(200f, 100f), CanvasView(), zoomLocked = true)
    assertEquals(1f, g.update(Offset(0f, 100f), Offset(400f, 100f)).scale, 0f)
  }

  @Test fun paperLinesStartOnTheGrid() {
    assertEquals(-56f, CanvasView.firstLine(-30f, 28f), 0f)
    assertEquals(84f, CanvasView.firstLine(100f, 28f), 0f)
  }
}
