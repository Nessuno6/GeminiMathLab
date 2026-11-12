package com.deang.geminimath

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Paper drawn in screen space, tiled over whatever part of the infinite page is visible. */
object PaperDraw {
  /** Where the Axes paper puts x = 0, y = 0, in page coordinates (near the top-left of a new note). */
  val AXES_ORIGIN = Offset(560f, 420f)

  private const val GRID_STEP = 28f
  private const val GRAPH_STEP = 14f
  private const val LINE_STEP = 56f
  private const val LINE_FIRST = 70f
  private const val DOT_STEP = 42f

  fun DrawScope.drawPaper(pattern: PaperPattern, paper: PaperColor, view: CanvasView) {
    drawRect(Color(paper.argb))
    val dark = paper.isDark
    val minor = if (dark) Color(0xFF2A2A2E) else Color(0xFFE2E2E2)
    val major = if (dark) Color(0xFF3C3C42) else Color(0xFFD0D0D0)
    val dot = if (dark) Color(0xFF55555C) else Color(0xFFBEBEBE)
    val area = view.visiblePage(size.width, size.height)

    fun gridLines(step: Float, majorEvery: Int) {
      val showMinor = step * view.scale >= 6f
      var x = CanvasView.firstLine(area.left, step)
      while (x <= area.right) {
        val isMajor = Math.round(x / step) % majorEvery == 0
        if (isMajor || showMinor) {
          val sx = x * view.scale + view.offset.x
          drawLine(if (isMajor) major else minor, Offset(sx, 0f), Offset(sx, size.height), if (isMajor) 1.4f else 1f)
        }
        x += step
      }
      var y = CanvasView.firstLine(area.top, step)
      while (y <= area.bottom) {
        val isMajor = Math.round(y / step) % majorEvery == 0
        if (isMajor || showMinor) {
          val sy = y * view.scale + view.offset.y
          drawLine(if (isMajor) major else minor, Offset(0f, sy), Offset(size.width, sy), if (isMajor) 1.4f else 1f)
        }
        y += step
      }
    }

    when (pattern) {
      PaperPattern.GRID -> gridLines(GRID_STEP, 5)
      PaperPattern.GRAPH -> gridLines(GRAPH_STEP, 5)
      PaperPattern.AXES -> {
        gridLines(GRID_STEP, 5)
        val axis = if (dark) Color(0xFF9A9AA2) else Color(0xFF6E6E73)
        val o = view.toScreen(AXES_ORIGIN)
        drawLine(axis, Offset(0f, o.y), Offset(size.width, o.y), 2.2f)
        drawLine(axis, Offset(o.x, 0f), Offset(o.x, size.height), 2.2f)
      }
      PaperPattern.LINES -> {
        var y = CanvasView.firstLine(area.top - LINE_FIRST, LINE_STEP) + LINE_FIRST
        while (y <= area.bottom) {
          val sy = y * view.scale + view.offset.y
          drawLine(if (dark) major else Color(0xFFD6D6D6), Offset(0f, sy), Offset(size.width, sy), 1.4f)
          y += LINE_STEP
        }
      }
      PaperPattern.DOTS -> {
        if (DOT_STEP * view.scale >= 5f) {
          val r = (2f * view.scale).coerceIn(1f, 4f)
          var x = CanvasView.firstLine(area.left, DOT_STEP)
          while (x <= area.right) {
            var y = CanvasView.firstLine(area.top, DOT_STEP)
            val sx = x * view.scale + view.offset.x
            while (y <= area.bottom) {
              drawCircle(dot, radius = r, center = Offset(sx, y * view.scale + view.offset.y))
              y += DOT_STEP
            }
            x += DOT_STEP
          }
        }
      }
      PaperPattern.BLANK -> {}
    }
  }

  /** Ink colour as shown: near-black ink turns white on dark pages so it stays readable. */
  fun displayInk(argb: Long, paper: PaperColor): Long {
    if (!paper.isDark) return argb
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return if (r < 0x40 && g < 0x40 && b < 0x40) (argb and 0xFF000000L) or 0xF2F2F2L else argb
  }
}
