package com.deang.geminimath

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The part of the plane a graph shows. */
data class GraphWindow(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
  val width get() = xMax - xMin
  val height get() = yMax - yMin

  /** Moves the window by a screen drag of (dx, dy) pixels on a w × h view. */
  fun pan(dx: Float, dy: Float, w: Float, h: Float): GraphWindow {
    val ux = -dx / w * width; val uy = dy / h * height
    return GraphWindow(xMin + ux, xMax + ux, yMin + uy, yMax + uy)
  }

  /** Zooms by [factor] (> 1 zooms in) around the screen point (px, py). */
  fun zoom(factor: Float, px: Float, py: Float, w: Float, h: Float): GraphWindow {
    val cx = xMin + px / w * width; val cy = yMax - py / h * height
    val f = 1.0 / factor.coerceIn(0.2f, 5f)
    val nw = (width * f).coerceIn(1e-6, 1e7); val nh = (height * f).coerceIn(1e-6, 1e7)
    val rx = (cx - xMin) / width; val ry = (yMax - cy) / height
    return GraphWindow(cx - rx * nw, cx - rx * nw + nw, cy + ry * nh - nh, cy + ry * nh)
  }

  companion object {
    /** A square-ish window centred on the origin, fitted to the view's aspect ratio. */
    fun standard(w: Float, h: Float, halfWidth: Double = 10.0): GraphWindow {
      val half = halfWidth * (h / w.coerceAtLeast(1f))
      return GraphWindow(-halfWidth, halfWidth, -half, half)
    }
  }
}

/** One curve: a plain expression in x (as [LocalMathEngine] reads it) and its colour. */
data class GraphCurve(val expr: String, val argb: Int)

object GraphRender {
  val CurveColors = intArrayOf(
    0xFFC74440.toInt(), 0xFF2D70B3.toInt(), 0xFF388C46.toInt(),
    0xFF6042A6.toInt(), 0xFFFA7E19.toInt(), 0xFF000000.toInt()
  )

  /** A tidy grid spacing (1, 2 or 5 × 10ⁿ) giving about [target] lines across [span]. */
  fun niceStep(span: Double, target: Int = 8): Double {
    val raw = span / target
    val mag = 10.0.pow(floor(log10(raw)))
    val n = raw / mag
    return mag * when { n < 1.5 -> 1.0; n < 3.5 -> 2.0; n < 7.5 -> 5.0; else -> 10.0 }
  }

  private fun label(v: Double, step: Double): String {
    if (abs(v) < step * 1e-6) return "0"
    val decimals = (-floor(log10(step))).toInt().coerceIn(0, 6)
    return if (decimals == 0) v.toLong().toString() else "%.${decimals}f".format(v)
  }

  /**
   * Picks a window for one function: x from −10 to 10, y fitted to where the curve is
   * (ignoring the extremes, so asymptotes don't flatten everything).
   */
  fun autoWindow(expr: String, aspect: Double = 0.62): GraphWindow {
    val all = (0..400).map { -10.0 + it * 0.05 }.map { it to LocalMathEngine.evalAt(expr, it) }
    val defined = all.filter { it.second.isFinite() }
    if (defined.size < 10) return GraphWindow(-10.0, 10.0, -10 * aspect, 10 * aspect)
    // x: only where the function exists (√x starts at 0), with a little room either side
    var x0 = defined.first().first; var x1 = defined.last().first
    if (x1 - x0 < 2) { x0 -= 1; x1 += 1 }
    val padX = (x1 - x0) * 0.06
    x0 = if (x0 > -10 + 1e-9) x0 - padX else -10.0; x1 = if (x1 < 10 - 1e-9) x1 + padX else 10.0
    // y: the middle 80% of the values (fast-growing curves leave the top instead of flattening the rest)
    val ys = defined.map { it.second }.sorted()
    var lo = ys[(ys.size * 0.05).toInt()]; var hi = ys[(ys.size * 0.85).toInt().coerceAtMost(ys.size - 1)]
    lo = minOf(lo, 0.0); hi = maxOf(hi, 0.0)
    if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
    val pad = (hi - lo) * 0.12
    return GraphWindow(x0, x1, lo - pad, hi + pad)
  }

  /** Draws grid, axes, labels and [curves] on [c] (w × h pixels). */
  fun draw(c: Canvas, w: Float, h: Float, win: GraphWindow, curves: List<GraphCurve>, dark: Boolean = false, density: Float = 2f) {
    fun sx(x: Double) = ((x - win.xMin) / win.width * w).toFloat()
    fun sy(y: Double) = ((win.yMax - y) / win.height * h).toFloat()
    c.drawColor(if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt())

    val minor = Paint().apply { color = if (dark) 0xFF262626.toInt() else 0xFFEEEEEE.toInt(); strokeWidth = 1f }
    val major = Paint().apply { color = if (dark) 0xFF3A3A3A.toInt() else 0xFFD6D6D6.toInt(); strokeWidth = 1.2f * density / 2 }
    val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFFBDBDBD.toInt() else 0xFF404040.toInt(); strokeWidth = 1.6f * density / 2 + 1 }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFFBDBDBD.toInt() else 0xFF505050.toInt(); textSize = 11f * density }

    // About 8 grid lines across, and as many down as fit the same spacing on screen
    val step = niceStep(win.width, 8)
    val stepY = niceStep(win.height, (8 * h / w.coerceAtLeast(1f)).toInt().coerceAtLeast(4))
    val sub = step / 5; val subY = stepY / 5
    var x = ceil(win.xMin / sub) * sub
    while (x <= win.xMax) { c.drawLine(sx(x), 0f, sx(x), h, minor); x += sub }
    var y = ceil(win.yMin / subY) * subY
    while (y <= win.yMax) { c.drawLine(0f, sy(y), w, sy(y), minor); y += subY }
    x = ceil(win.xMin / step) * step
    while (x <= win.xMax) { c.drawLine(sx(x), 0f, sx(x), h, major); x += step }
    y = ceil(win.yMin / stepY) * stepY
    while (y <= win.yMax) { c.drawLine(0f, sy(y), w, sy(y), major); y += stepY }

    // Axes, with labels kept on screen when the origin is off to one side
    val ax = sx(0.0).coerceIn(0f, w); val ay = sy(0.0).coerceIn(0f, h)
    c.drawLine(0f, ay, w, ay, axis); c.drawLine(ax, 0f, ax, h, axis)
    x = ceil(win.xMin / step) * step
    while (x <= win.xMax) {
      if (abs(x) > step * 1e-6) {
        val s = label(x, step); val tw = text.measureText(s)
        val ty = (ay + text.textSize + 4).let { if (it > h - 4) ay - 6 else it }
        val tx = sx(x) - tw / 2
        if (tx > 2 && tx + tw < w - 2) c.drawText(s, tx, ty, text)
      }
      x += step
    }
    y = ceil(win.yMin / stepY) * stepY
    while (y <= win.yMax) {
      if (abs(y) > stepY * 1e-6) {
        val s = label(y, stepY); val tw = text.measureText(s)
        val tx = (ax - tw - 6).let { if (it < 4) ax + 6 else it }
        val ty = sy(y) + text.textSize / 3
        if (ty > text.textSize && ty < h - 4) c.drawText(s, tx, ty, text)
      }
      y += stepY
    }
    c.drawText("0", (ax - text.measureText("0") - 6).coerceAtLeast(4f), (ay + text.textSize + 4).coerceAtMost(h - 4), text)

    // Curves: one sample per pixel or two, broken at gaps and vertical asymptotes
    for (curve in curves) {
      val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = curve.argb; style = Paint.Style.STROKE; strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
      }
      val path = Path()
      var drawing = false
      var lastY = Double.NaN
      val n = (w / 1.5f).toInt().coerceAtLeast(200)
      // Piecewise functions: never join two pieces across the point where they change
      val pieceWise = curve.expr.startsWith(Piecewise.PREFIX)
      var lastPiece = -2
      for (i in 0..n) {
        val xv = win.xMin + win.width * i / n
        val yv = LocalMathEngine.evalAt(curve.expr, xv, MathSettings.angleDegrees)
        val ok = yv.isFinite() && abs(yv - win.yMin) < win.height * 50
        val piece = if (pieceWise) Piecewise.index(curve.expr, xv, MathSettings.angleDegrees) else 0
        val changed = pieceWise && piece != lastPiece
        lastPiece = piece
        // A jump across most of the view between neighbouring samples is an asymptote, not a line
        val jump = changed || drawing && lastY.isFinite() && abs(yv - lastY) > win.height * 1.5
        if (!ok || jump) { drawing = false; lastY = yv; if (!ok) continue }
        val px = sx(xv); val py = sy(yv).coerceIn(-h * 4, h * 5)
        if (!drawing) { path.moveTo(px, py); drawing = true } else path.lineTo(px, py)
        lastY = yv
      }
      c.drawPath(path, p)
    }
  }

  fun bitmap(curves: List<GraphCurve>, win: GraphWindow, w: Int = 1600, h: Int = 1000, dark: Boolean = false): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    // Text and lines sized for the picture to be shown about 600 px wide
    draw(Canvas(bmp), w.toFloat(), h.toFloat(), win, curves, dark, density = w / 520f)
    return bmp
  }

  /**
   * Turns what someone typed ("y = x^2", "f(x)=sin x", "2·π") into an expression the engine
   * reads, or null if it isn't a function of x.
   */
  fun normalise(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    s = s.replace("−", "-").replace("·", "*").replace("×", "*").replace("÷", "/")
      .replace("π", "pi").replace("√", "sqrt").replace("∛", "cbrt")
      .replace("²", "^2").replace("³", "^3")
    s = s.replace(Regex("""^\s*(?:y|[a-wzA-Z]\s*\(\s*x\s*\))\s*=\s*"""), "")
    // Piecewise, as in Desmos: {x>1: x, x≤1: 2}
    if (s.startsWith("{")) return Piecewise.fromTyped(s) { normalise(it) }
    if (s.contains('=')) return null
    return s.takeIf { LocalMathEngine.parse(it) != null }
  }
}
