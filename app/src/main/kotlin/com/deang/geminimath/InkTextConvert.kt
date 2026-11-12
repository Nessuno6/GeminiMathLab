package com.deang.geminimath

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * "Convert to text" and "Convert to maths" for the lasso, as in Samsung Notes: the selected
 * handwriting is read and replaced by typed text in the same place, size and colour.
 *
 * The typed text is stored as a see-through, colour-tinted mask (the same kind of object as a
 * bucket fill), so it can be moved, resized, recoloured and erased like the rest of the page.
 */
object InkTextConvert {
  /**
   * Reads handwriting as text with Google's on-device handwriting recogniser, in the phone's
   * language (English if that language isn't available). The first time, the language's model
   * (a few MB) is downloaded. Lines are read top to bottom.
   */
  suspend fun recognizeText(strokes: List<InkStroke>): String? {
    val lines = LassoTools.splitLines(strokes.filter { it.isInk && it.points.isNotEmpty() })
    if (lines.isEmpty()) return null
    val id = listOf(Locale.getDefault().toLanguageTag(), Locale.getDefault().language, "en-US")
      .firstNotNullOfOrNull { runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(it) }.getOrNull() }
      ?: return null
    val model = DigitalInkRecognitionModel.builder(id).build()
    val manager = RemoteModelManager.getInstance()
    if (!manager.isModelDownloaded(model).await()) manager.download(model, DownloadConditions.Builder().build()).await()
    val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    try {
      val out = ArrayList<String>()
      for (line in lines) {
        val ink = Ink.builder()
        for (s in line) {
          val sb = Ink.Stroke.builder()
          for (p in s.points) sb.addPoint(Ink.Point.create(p.x, p.y, p.t))
          ink.addStroke(sb.build())
        }
        val text = recognizer.recognize(ink.build()).await().candidates.firstOrNull()?.text
        if (!text.isNullOrBlank()) out += text
      }
      return out.joinToString("\n").ifBlank { null }
    } finally {
      recognizer.close()
    }
  }

  /**
   * Typed text as a fill object covering [area] on the page (keeping the text's own shape,
   * centred vertically), in [color]. [maths] uses a serif italic look for maths.
   */
  fun textObject(text: String, area: Rect, color: Long, layer: Int, maths: Boolean): InkStroke? {
    val rows = text.split('\n').filter { it.isNotBlank() }
    if (rows.isEmpty() || area.width <= 0f || area.height <= 0f) return null
    // Pixel size: about 4 mask pixels per page unit, capped so the mask stays small
    val rowHeightPage = area.height / rows.size
    val scale = (4f).coerceAtMost(3000f / max(area.width, area.height))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = if (maths) Typeface.create(Typeface.SERIF, Typeface.ITALIC) else Typeface.create("sans-serif", Typeface.NORMAL)
      textSize = rowHeightPage * scale * 0.78f
      this.color = android.graphics.Color.WHITE
    }
    val fm = paint.fontMetrics
    val lineH = (fm.descent - fm.ascent) * 1.08f
    val widest = rows.maxOf { paint.measureText(it) }
    val w = ceil(widest + paint.textSize * 0.2f).toInt().coerceIn(1, 4000)
    val h = ceil(lineH * rows.size).toInt().coerceIn(1, 4000)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val c = Canvas(bmp)
    rows.forEachIndexed { i, row -> c.drawText(row, paint.textSize * 0.1f, lineH * i - fm.ascent, paint) }
    val mask = ByteArray(w * h)
    val buf = java.nio.ByteBuffer.allocate(bmp.rowBytes * h)
    bmp.copyPixelsToBuffer(buf)
    val raw = buf.array()
    for (y in 0 until h) for (x in 0 until w) mask[y * w + x] = raw[y * bmp.rowBytes + x]
    bmp.recycle()
    // Placed at the handwriting's left edge, centred on it vertically
    val pw = w / scale; val ph = h / scale
    val left = area.left
    val top = area.center.y - ph / 2f
    val now = System.currentTimeMillis()
    return InkStroke(
      listOf(TimedPoint(left, top, now), TimedPoint(left + pw, top + ph, now)),
      width = 0f, colorHex = color or 0xFF000000L, isHighlighter = false,
      layer = layer, fill = FillMask(w, h, mask)
    )
  }
}
