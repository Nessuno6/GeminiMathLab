package com.deang.geminimath

import android.graphics.RenderNode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Keeps the finished ink recorded in small groups ("chunks") of strokes, each its own GPU
 * display list. When a stroke is erased, added or changed, only its chunk is recorded again; the
 * rest of the page is replayed as it is. On a big note (thousands of strokes) re-recording the
 * whole page after every change took long enough to freeze the screen while erasing.
 *
 * Strokes keep the chunk they were first put in (by identity), so removing one never moves the
 * others. Chunks are per layer and per kind (pictures, fills, highlighter, ink, tape), so the
 * drawing order between kinds stays as before.
 */
class StrokeCache(private val chunkSize: Int = 48) {
  private class Chunk(val id: Int) {
    // Clipped to its strokes' box (with room for the widest pen): the GPU skips every chunk that's
    // off screen, so only the strokes you can see are drawn
    val node = RenderNode("ink").apply { setClipToBounds(true) }
    var key: List<Any?> = emptyList()
  }

  private val chunkOf = IdentityHashMap<InkStroke, Chunk>()
  private val fill = HashMap<String, Chunk>()   // the chunk new strokes of a (layer, kind) go into
  private val count = HashMap<Chunk, Int>()
  private val chunks = ArrayList<Chunk>()
  private var nextId = 0

  private fun assign(s: InkStroke, group: String): Chunk {
    chunkOf[s]?.let { return it }
    var c = fill[group]
    if (c == null || (count[c] ?: 0) >= chunkSize) {
      c = Chunk(nextId++); chunks += c; fill[group] = c
    }
    count[c] = (count[c] ?: 0) + 1
    chunkOf[s] = c
    return c
  }

  /**
   * Draws [strokes] (already in drawing order, for one layer and kind) through the cache.
   * [version] is anything else that changes how they look (page colour, peeled tape, a picture
   * that has just loaded); [draw] draws one stroke.
   */
  fun DrawScope.drawCached(
    group: String,
    strokes: List<InkStroke>,
    version: (InkStroke) -> Any?,
    draw: DrawScope.(InkStroke) -> Unit
  ) {
    if (strokes.isEmpty()) return
    // Strokes by chunk, in page order
    val byChunk = LinkedHashMap<Chunk, ArrayList<InkStroke>>()
    for (s in strokes) byChunk.getOrPut(assign(s, group)) { ArrayList() } += s
    val sorted = byChunk.entries.sortedBy { it.key.id }
    val canvas = drawContext.canvas.nativeCanvas
    for ((chunk, list) in sorted) {
      val key = ArrayList<Any?>(list.size * 2)
      for (s in list) { key += System.identityHashCode(s); key += version(s) }
      if (key != chunk.key || !chunk.node.hasDisplayList()) {
        record(chunk, list, draw)
        chunk.key = key
      }
      canvas.drawRenderNode(chunk.node)
    }
  }

  private fun DrawScope.record(chunk: Chunk, list: List<InkStroke>, draw: DrawScope.(InkStroke) -> Unit) {
    val b = LassoTools.bounds(list) ?: return
    val pad = (list.maxOf { it.width } * 1.6f) + 8f
    val l = floor(b.left - pad).toInt(); val t = floor(b.top - pad).toInt()
    val r = ceil(b.right + pad).toInt(); val btm = ceil(b.bottom + pad).toInt()
    val node = chunk.node
    node.setPosition(l, t, r.coerceAtLeast(l + 1), btm.coerceAtLeast(t + 1))
    val rc = node.beginRecording()
    try {
      rc.translate(-l.toFloat(), -t.toFloat())
      CanvasDrawScope().draw(
        Density(density, fontScale), LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(rc), Size((r - l).toFloat(), (btm - t).toFloat())
      ) { for (s in list) draw(s) }
    } finally {
      node.endRecording()
    }
  }

  /** Forgets strokes that are no longer on the page (after edits, undo history keeps old ones alive). */
  fun prune(current: List<InkStroke>) {
    if (chunkOf.size < current.size * 2 + 200) return
    val live = IdentityHashMap<InkStroke, Boolean>(current.size).apply { for (s in current) put(s, true) }
    val it = chunkOf.entries.iterator()
    while (it.hasNext()) {
      val e = it.next()
      if (!live.containsKey(e.key)) { it.remove(); count[e.value] = (count[e.value] ?: 1) - 1 }
    }
    val empty = chunks.filter { (count[it] ?: 0) <= 0 }.toSet()
    if (empty.isNotEmpty()) {
      chunks.removeAll(empty)
      empty.forEach { c -> count.remove(c); c.node.discardDisplayList() }
      fill.entries.removeAll { it.value in empty }
    }
  }

  fun clear() {
    chunks.forEach { it.node.discardDisplayList() }
    chunkOf.clear(); fill.clear(); count.clear(); chunks.clear()
  }
}
