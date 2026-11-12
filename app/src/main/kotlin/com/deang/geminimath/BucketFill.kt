package com.deang.geminimath

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Scanline flood fill over a wall grid. Pure Kotlin so it can be unit-tested. */
object FloodFill {
  /**
   * Fills from (sx, sy) over pixels that aren't walls. Returns the filled pixels, or null when the
   * start is on a wall or the fill reaches the edge of the grid (the shape isn't closed).
   */
  fun fill(walls: BooleanArray, w: Int, h: Int, sx: Int, sy: Int): BooleanArray? {
    if (sx !in 0 until w || sy !in 0 until h || walls[sy * w + sx]) return null
    val filled = BooleanArray(w * h)
    val stack = IntArray(w * h / 4 + 16)
    var sp = 0
    fun push(x: Int, y: Int): Boolean {
      if (sp >= stack.size) return false
      stack[sp++] = y * w + x; return true
    }
    push(sx, sy)
    while (sp > 0) {
      val idx = stack[--sp]
      val y = idx / w
      var x = idx % w
      if (filled[idx]) continue
      // Walk left to the start of this run
      while (x > 0 && !walls[y * w + x - 1] && !filled[y * w + x - 1]) x--
      var spanUp = false; var spanDown = false
      while (x < w && !walls[y * w + x] && !filled[y * w + x]) {
        filled[y * w + x] = true
        // Touching the border means the region is open
        if (x == 0 || x == w - 1 || y == 0 || y == h - 1) return null
        val up = y - 1; val down = y + 1
        if (!walls[up * w + x] && !filled[up * w + x]) { if (!spanUp) { if (!push(x, up)) return null; spanUp = true } } else spanUp = false
        if (!walls[down * w + x] && !filled[down * w + x]) { if (!spanDown) { if (!push(x, down)) return null; spanDown = true } } else spanDown = false
        x++
      }
    }
    return filled
  }

  /** Grows the filled area by [r] pixels so it tucks under the outline instead of leaving a gap. */
  fun dilate(
    mask: BooleanArray, w: Int, h: Int, r: Int,
    x0: Int = 0, y0: Int = 0, x1: Int = w - 1, y1: Int = h - 1  // only this part can change
  ): BooleanArray {
    var cur = mask
    val xa = x0.coerceAtLeast(0); val xb = x1.coerceAtMost(w - 1)
    val ya = y0.coerceAtLeast(0); val yb = y1.coerceAtMost(h - 1)
    repeat(r) {
      val next = cur.copyOf()
      for (y in ya..yb) for (x in xa..xb) {
        if (cur[y * w + x]) continue
        if ((x > 0 && cur[y * w + x - 1]) || (x < w - 1 && cur[y * w + x + 1]) ||
          (y > 0 && cur[(y - 1) * w + x]) || (y < h - 1 && cur[(y + 1) * w + x])
        ) next[y * w + x] = true
      }
      cur = next
    }
    return cur
  }
}

/** Builds bucket fills from the ink on the page. */
object BucketFill {
  /** Largest raster side used to find the region (bigger = sharper fills, slower). */
  private const val MAX_SIDE = 1600

  /**
   * Fills the closed shape around [tap] (page coordinates), looking only at [area] (the visible
   * part of the page). Returns the fill as a stroke, or null when the shape isn't closed.
   */
  fun build(walls: List<InkStroke>, tap: Offset, area: Rect, argb: Long, layer: Int): InkStroke? {
    // Page px per raster px. Zoomed in, a raster px is smaller than a page px, so the fill's edge
    // stays as sharp on screen as at 100%
    val step = (maxOf(area.width, area.height) / MAX_SIDE).coerceAtLeast(0.2f)
    val w = (area.width / step).toInt().coerceAtLeast(4)
    val h = (area.height / step).toInt().coerceAtLeast(4)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint().apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
      isAntiAlias = false
    }
    for (s in walls) {
      if (!s.isInk || s.points.isEmpty()) continue
      // A little thicker than drawn, so tiny gaps where lines nearly meet still count as closed
      paint.strokeWidth = (s.width / step + 3f).coerceAtLeast(2f)
      val path = android.graphics.Path()
      val p0 = s.points[0]
      path.moveTo((p0.x - area.left) / step, (p0.y - area.top) / step)
      if (s.points.size == 1) path.lineTo((p0.x - area.left) / step + 0.1f, (p0.y - area.top) / step)
      for (i in 1 until s.points.size) {
        val p = s.points[i]
        path.lineTo((p.x - area.left) / step, (p.y - area.top) / step)
      }
      canvas.drawPath(path, paint)
    }
    val alpha = ByteArray(w * h)
    bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(alpha))
    bmp.recycle()
    val wallGrid = BooleanArray(w * h) { (alpha[it].toInt() and 0xFF) > 0 }
    val sx = ((tap.x - area.left) / step).toInt()
    val sy = ((tap.y - area.top) / step).toInt()
    val filled = FloodFill.fill(wallGrid, w, h, sx, sy) ?: return null

    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    for (y in 0 until h) for (x in 0 until w) if (filled[y * w + x]) {
      if (x < minX) minX = x; if (x > maxX) maxX = x
      if (y < minY) minY = y; if (y > maxY) maxY = y
    }
    if (maxX < 0) return null
    // Grown out to the middle of the outline (the walls are drawn a little wider than the ink), so
    // the colour goes right up under the line with no paper showing between them
    val region = Rect(area.left + minX * step, area.top + minY * step, area.left + (maxX + 1) * step, area.top + (maxY + 1) * step)
    val thinnest = walls.filter { it.isInk && it.points.isNotEmpty() }
      .filter { s -> LassoTools.bounds(listOf(s))?.inflate(s.width)?.overlaps(region.inflate(4f * step)) == true }
      .minOfOrNull { it.width } ?: 0f
    val grow = ((thinnest / step + 3f) / 2f).toInt().coerceIn(2, 24)
    val grown = FloodFill.dilate(filled, w, h, grow, minX - grow, minY - grow, maxX + grow, maxY + grow)
    minX = (minX - grow).coerceAtLeast(0); minY = (minY - grow).coerceAtLeast(0)
    maxX = (maxX + grow).coerceAtMost(w - 1); maxY = (maxY + grow).coerceAtMost(h - 1)
    val mw = maxX - minX + 1
    val mh = maxY - minY + 1
    val mask = ByteArray(mw * mh)
    for (y in 0 until mh) for (x in 0 until mw) if (grown[(y + minY) * w + x + minX]) mask[y * mw + x] = 0xFF.toByte()
    val left = area.left + minX * step
    val top = area.top + minY * step
    return InkStroke(
      points = listOf(TimedPoint(left, top, 0L), TimedPoint(left + mw * step, top + mh * step, 0L)),
      width = 0f,
      colorHex = argb,
      layer = layer,
      fill = FillMask(mw, mh, mask)
    )
  }

  /** White image with the mask as alpha, tinted with the fill colour when drawn. */
  fun image(m: FillMask): ImageBitmap {
    m.image?.let { return it }
    // Each mask byte is the coverage (bucket fills use 0 or 255; typed text has soft edges)
    val px = IntArray(m.w * m.h) { i -> ((m.mask[i].toInt() and 0xFF) shl 24) or 0xFFFFFF }
    val bmp = Bitmap.createBitmap(px, m.w, m.h, Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap().also { m.image = it }
  }

  /** Whether page point [p] lands on this fill. */
  fun hits(s: InkStroke, p: Offset): Boolean {
    val m = s.fill ?: return false
    val a = s.points[0]; val b = s.points[1]
    if (p.x < a.x || p.y < a.y || p.x > b.x || p.y > b.y) return false
    val x = ((p.x - a.x) / (b.x - a.x) * m.w).toInt().coerceIn(0, m.w - 1)
    val y = ((p.y - a.y) / (b.y - a.y) * m.h).toInt().coerceIn(0, m.h - 1)
    return m.mask[y * m.w + x].toInt() != 0
  }
}
