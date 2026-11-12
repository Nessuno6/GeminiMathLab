package com.deang.geminimath

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Where the infinite page is on screen: screen = page × scale + offset.
 * Ink is stored in page coordinates, so at 100% with no panning page = screen,
 * which keeps notes saved before the canvas could move exactly where they were.
 */
data class CanvasView(val offset: Offset = Offset.Zero, val scale: Float = 1f) {
  fun toPage(screen: Offset): Offset = Offset((screen.x - offset.x) / scale, (screen.y - offset.y) / scale)
  fun toScreen(page: Offset): Offset = Offset(page.x * scale + offset.x, page.y * scale + offset.y)
  fun toScreen(page: Rect): Rect = Rect(toScreen(page.topLeft), toScreen(page.bottomRight))

  /** The part of the page that's visible in a viewport of [width] × [height] pixels. */
  fun visiblePage(width: Float, height: Float): Rect =
    Rect(toPage(Offset.Zero), toPage(Offset(width, height)))

  fun panBy(dx: Float, dy: Float) = copy(offset = Offset(offset.x + dx, offset.y + dy))

  /** Zooms to [newScale] keeping the page point under [focus] (a screen point) fixed. */
  fun zoomTo(newScale: Float, focus: Offset): CanvasView {
    val s = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
    val anchor = toPage(focus)
    return CanvasView(Offset(focus.x - anchor.x * s, focus.y - anchor.y * s), s)
  }

  val percent: Int get() = Math.round(scale * 100f)

  companion object {
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 4f

    /** First multiple of [step] at or before [from]: where repeating paper lines start. */
    fun firstLine(from: Float, step: Float): Float = floor(from / step) * step
  }
}

/**
 * Two-finger pinch and pan. Start it with the two finger positions and the view at that moment;
 * each move gives the new view. Keeps the page point under the fingers' midpoint under it.
 */
class PinchGesture(a: Offset, b: Offset, private val start: CanvasView, private val zoomLocked: Boolean) {
  private val startMid = (a + b) / 2f
  private val startSpan = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
  private val anchor = start.toPage(startMid)

  fun update(a: Offset, b: Offset): CanvasView {
    val mid = (a + b) / 2f
    val span = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
    val s = if (zoomLocked) start.scale
    else (start.scale * span / startSpan).coerceIn(CanvasView.MIN_SCALE, CanvasView.MAX_SCALE)
    return CanvasView(Offset(mid.x - anchor.x * s, mid.y - anchor.y * s), s)
  }
}
