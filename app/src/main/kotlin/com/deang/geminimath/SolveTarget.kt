package com.deang.geminimath

/**
 * Chooses what "Solve" reads when nothing is selected. The whole page often holds drawings and
 * several formulas, and the recogniser reads them all as one garbled block. So drawings (strokes
 * far bigger than the handwriting) are left out, the rest is grouped into lines, and the line
 * written last is used.
 */
object SolveTarget {
  private class Box(val s: InkStroke, val top: Float, val bottom: Float, val size: Float, val order: Int, val time: Long)

  fun pick(strokes: List<InkStroke>): List<InkStroke> {
    val boxes = strokes.mapIndexedNotNull { i, s ->
      if (s.points.isEmpty()) return@mapIndexedNotNull null
      val ys = s.points.map { it.y }; val xs = s.points.map { it.x }
      val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
      Box(s, ys.min(), ys.max(), maxOf(w, h), i, s.points.last().t)
    }
    if (boxes.size < 2) return strokes
    val median = boxes.map { it.size }.sorted()[boxes.size / 2].coerceAtLeast(8f)
    // Drawings: much bigger than a typical character (fraction bars are wide but flat, so use height too)
    val writing = boxes.filter { b ->
      val h = b.bottom - b.top
      !(b.size > median * 6 && h > median * 3)
    }.ifEmpty { boxes }

    // Group into lines: strokes whose vertical ranges overlap (or nearly touch) belong together
    val tol = median * 0.35f
    val lines = mutableListOf<MutableList<Box>>()
    var bottom = Float.NEGATIVE_INFINITY
    for (b in writing.sortedBy { it.top }) {
      if (lines.isEmpty() || b.top > bottom + tol) { lines += mutableListOf(b); bottom = b.bottom }
      else { lines.last() += b; bottom = maxOf(bottom, b.bottom) }
    }
    if (lines.size == 1) return lines[0].sortedBy { it.order }.map { it.s }
    // The line with the newest stroke (by time; by list order for strokes without times)
    val useTime = writing.any { it.time > 0 }
    val newest = lines.maxBy { l -> if (useTime) l.maxOf { it.time }.toDouble() else l.maxOf { it.order }.toDouble() }
    return newest.sortedBy { it.order }.map { it.s }
  }
}
