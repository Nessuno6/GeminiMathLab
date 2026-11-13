package com.deang.geminimath

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Note-card ink previews. Drawing every stroke of every note while the notes list appears made
 * that first frame slow, so each preview is rendered once into a bitmap on a background thread
 * (and persisted to `cacheDir/note_thumbs` so lazy-loaded notes don't even need their strokes
 * parsed on subsequent launches).
 */
object NoteThumbCache {
  private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 80
  }

  fun key(note: MathNote, w: Int, h: Int) = "${note.id}:${note.updatedAt}:${note.strokeCount}:${w}x$h"

  private fun diskFile(context: Context, key: String): File {
    val dir = File(context.cacheDir, "note_thumbs").apply { if (!exists()) mkdirs() }
    val safe = key.replace(':', '_')
    return File(dir, "$safe.png")
  }

  @Synchronized fun peek(key: String): ImageBitmap? = cache[key]
  @Synchronized private fun put(key: String, bmp: ImageBitmap) { cache[key] = bmp }

  /** Renders or loads from disk cache the note's ink fitted into w × h pixels. */
  fun render(context: Context, note: MathNote, w: Int, h: Int, strokePx: Float): ImageBitmap {
    val k = key(note, w, h)
    peek(k)?.let { return it }
    val file = diskFile(context, k)
    if (file.exists()) {
      runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()?.let {
        put(k, it)
        return it
      }
    }
    val hydrated = NotesRepository.ensureStrokesLoaded(context, note)
    return render(hydrated, w, h, strokePx, saveToFile = file)
  }

  /** Renders the note's ink fitted into w × h pixels (same framing as before: centred, 16/40 px margins). */
  fun render(note: MathNote, w: Int, h: Int, strokePx: Float, saveToFile: File? = null): ImageBitmap {
    val k = key(note, w, h)
    peek(k)?.let { return it }
    val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val strokes = note.strokes.filter { it.points.isNotEmpty() && it.fill == null && it.picture == null }
    if (strokes.isNotEmpty()) {
      var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
      var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
      for (s in strokes) for (p in s.points) {
        if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
      }
      val bw = (maxX - minX).coerceAtLeast(1f)
      val bh = (maxY - minY).coerceAtLeast(1f)
      val scale = minOf((w - 16f) / bw, (h - 40f) / bh)
      val ox = (w - bw * scale) / 2f
      val oy = (h - bh * scale) / 2f
      val canvas = android.graphics.Canvas(bmp)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = strokePx
      }
      for (s in strokes) {
        val pts = s.points
        if (pts.size < 2) continue
        paint.color = s.colorHex.toInt()
        val path = android.graphics.Path()
        path.moveTo(ox + (pts[0].x - minX) * scale, oy + (pts[0].y - minY) * scale)
        for (i in 1 until pts.size) path.lineTo(ox + (pts[i].x - minX) * scale, oy + (pts[i].y - minY) * scale)
        canvas.drawPath(path, paint)
      }
    }
    if (saveToFile != null) {
      runCatching { saveToFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    }
    return bmp.asImageBitmap().also { put(k, it) }
  }
}

@Composable
fun NoteInkThumb(note: MathNote, modifier: Modifier = Modifier) {
  if (note.strokeCount == 0) return
  val context = LocalContext.current
  BoxWithConstraints(modifier) {
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val strokePx = with(LocalDensity.current) { androidx.compose.ui.unit.Dp(2.4f).toPx() }
    val key = NoteThumbCache.key(note, w, h)
    val bmp = produceState(initialValue = NoteThumbCache.peek(key), key) {
      if (value == null) value = withContext(Dispatchers.IO) { NoteThumbCache.render(context, note, w, h, strokePx) }
    }.value
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
  }
}
