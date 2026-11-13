package com.deang.geminimath

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors

/**
 * A note's page background: one picture, or the pages of an imported PDF stacked top to bottom
 * (with a gap between pages) starting at ([NoteImportExport.BG_X], [NoteImportExport.BG_Y]).
 *
 * Several pages are kept in [MathNote.backgroundImagePath] as paths joined by `;`, so notes saved
 * with a single background (and older app versions) read the same as before.
 */
object BackgroundPages {
  const val SEPARATOR = ";"
  /** Gap between stacked pages, in page units. */
  const val PAGE_GAP = 48f

  data class Page(val path: String, val rect: RectF)

  fun paths(raw: String?): List<String> =
    raw?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

  fun join(paths: List<String>): String? = paths.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

  fun firstPath(raw: String?): String? = paths(raw).firstOrNull()

  /** Where each page sits, from the image sizes only (nothing is decoded). Missing files are skipped. */
  fun layout(raw: String?): List<Page> {
    val out = ArrayList<Page>()
    var top = NoteImportExport.BG_Y
    for (p in paths(raw)) {
      val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      runCatching { BitmapFactory.decodeFile(p, opts) }
      if (opts.outWidth <= 0 || opts.outHeight <= 0) continue
      val r = RectF(NoteImportExport.BG_X, top, NoteImportExport.BG_X + opts.outWidth, top + opts.outHeight)
      out += Page(p, r)
      top = r.bottom + PAGE_GAP
    }
    return out
  }

  /** The box around every page, or null when there's no background. */
  fun bounds(pages: List<Page>): RectF? {
    if (pages.isEmpty()) return null
    val r = RectF(pages[0].rect)
    pages.forEach { r.union(it.rect) }
    return r
  }

  /**
   * Draws each page onto [canvas] at page→canvas transform (scale, dx, dy), decoding one page at a
   * time so a long PDF never needs every page in memory. Pages outside [clip] (canvas coords) are skipped.
   */
  fun drawAll(canvas: Canvas, pages: List<Page>, scale: Float, dx: Float, dy: Float, clip: RectF? = null) {
    for (pg in pages) {
      val dst = RectF(dx + pg.rect.left * scale, dy + pg.rect.top * scale, dx + pg.rect.right * scale, dy + pg.rect.bottom * scale)
      if (clip != null && !RectF.intersects(dst, clip)) continue
      // Decode smaller when the page is drawn much smaller than its pixels (exports of long PDFs)
      val sample = generateSequence(1) { it * 2 }.takeWhile { it * 2 <= (1f / scale.coerceAtLeast(1e-3f)) }.lastOrNull() ?: 1
      val bmp = runCatching { BitmapFactory.decodeFile(pg.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull() ?: continue
      canvas.drawBitmap(bmp, null, dst, null)
      bmp.recycle()
    }
  }

  /** Index of the page whose area is most on screen ([visible] in page coordinates), or -1. */
  fun currentPage(pages: List<Page>, visible: Rect): Int {
    var best = -1
    var bestArea = 0f
    pages.forEachIndexed { i, pg ->
      val w = minOf(pg.rect.right, visible.right) - maxOf(pg.rect.left, visible.left)
      val h = minOf(pg.rect.bottom, visible.bottom) - maxOf(pg.rect.top, visible.top)
      if (w > 0 && h > 0 && w * h > bestArea) { bestArea = w * h; best = i }
    }
    if (best >= 0) return best
    // Nothing on screen: the page nearest the top of the view
    return pages.indices.minByOrNull { kotlin.math.abs(pages[it].rect.top - visible.top) } ?: -1
  }
}

/**
 * The editor's view of a note's background pages: page positions are known straight away, and each
 * page's picture is decoded off the main thread the first time it comes on screen. Only a few pages
 * are kept decoded, so a 60-page PDF scrolls without running out of memory.
 */
class BackgroundPageCache(raw: String?, private val keep: Int = 4) {
  val pages: List<BackgroundPages.Page> = BackgroundPages.layout(raw)

  /** Bumped when a page finishes decoding: read it while drawing so the canvas redraws. */
  var tick by mutableIntStateOf(0)
    private set

  private val decoded = object : LinkedHashMap<Int, ImageBitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>?) = size > keep
  }
  private val loading = HashSet<Int>()

  /** The page's picture if it's ready; otherwise starts decoding it and returns null. */
  fun imageOrLoad(index: Int): ImageBitmap? {
    synchronized(decoded) {
      decoded[index]?.let { return it }
      if (!loading.add(index)) return null
    }
    val path = pages.getOrNull(index)?.path ?: return null
    decoder.execute {
      val img = runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
      synchronized(decoded) {
        loading.remove(index)
        if (img != null) decoded[index] = img
      }
      if (img != null) android.os.Handler(android.os.Looper.getMainLooper()).post { tick++ }
    }
    return null
  }

  companion object {
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "bg-pages").apply { priority = Thread.NORM_PRIORITY - 1 } }
  }
}

/** Renders a PDF page to a white bitmap [targetW] px wide. */
internal fun renderPdfPage(page: android.graphics.pdf.PdfRenderer.Page, targetW: Int = 1400): Bitmap {
  val bmp = Bitmap.createBitmap(targetW, (targetW.toFloat() * page.height / page.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
  bmp.eraseColor(android.graphics.Color.WHITE)
  page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
  return bmp
}
