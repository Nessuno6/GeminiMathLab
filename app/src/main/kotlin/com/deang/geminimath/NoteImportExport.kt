package com.deang.geminimath

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export formats supported by the app.
 *
 * Samsung Notes (.sdocx) is intentionally NOT supported: it is a proprietary, undocumented format.
 *
 * `.iink` is MyScript's package format: a ZIP archive. Its internal layout is not publicly specified,
 * but MyScript's ink exchange format (JIIX) is documented. Our `.iink` export is therefore a ZIP
 * containing a JIIX document (`content.jiix`) plus a lossless copy of the note (`geminimath/note.json`).
 * Import reads our own packages losslessly and does a best-effort scan of any JIIX/JSON stroke data
 * (objects with `X`/`Y` arrays) found inside foreign `.iink` / `.jiix` files.
 */
enum class NoteExportFormat(val label: String, val extension: String, val mime: String) {
  /** The note exactly as the app keeps it (infinite canvas, layers, pens, fills, pictures); imports back. */
  GMNOTE("Gemini Math note (.gmnote)", "gmnote", "application/octet-stream"),
  /** Vector drawing: sharp at any size, however big the canvas. */
  SVG("SVG drawing (.svg)", "svg", "image/svg+xml"),
  IINK("MyScript iink (.iink)", "iink", "application/octet-stream"),
  PDF("PDF document (.pdf)", "pdf", "application/pdf"),
  PNG("PNG image (.png)", "png", "image/png")
}

/** CREATE_DOCUMENT contract whose MIME type is chosen per launch: input = (fileName, mime). */
class CreateDocumentWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
  override fun createIntent(context: Context, input: Pair<String, String>): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType(input.second)
      .putExtra(Intent.EXTRA_TITLE, input.first)

  override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

object NoteImportExport {
  private const val MM_PER_PX = 0.2645833f // 96 dpi reference used for JIIX millimetre coordinates

  /** Top-left position (note coordinates, px) where an imported background image is placed. */
  const val BG_X = 440f
  const val BG_Y = 320f

  // ---------------------------------------------------------------------------------------------
  // EXPORT
  // ---------------------------------------------------------------------------------------------

  private val fileDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

  /** "Title_2026-11-02": the note's name and the day it was last changed, safe as a file name. */
  fun fileBaseName(note: MathNote): String {
    val title = note.title.ifBlank { "Untitled" }.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(80)
    return title + "_" + fileDate.format(java.util.Date(note.updatedAt))
  }

  fun suggestedFileName(notes: List<MathNote>, format: NoteExportFormat): String {
    val base = if (notes.size == 1) fileBaseName(notes.first())
    else "GeminiMath_${notes.size}_notes_" + fileDate.format(java.util.Date(notes.maxOf { it.updatedAt }))
    val ext = if (notes.size > 1 && format != NoteExportFormat.PDF) "zip" else format.extension
    return "$base.$ext"
  }

  fun mimeFor(notes: List<MathNote>, format: NoteExportFormat): String =
    if (notes.size > 1 && format != NoteExportFormat.PDF) "application/zip" else format.mime

  /** Writes [notes] to [uri]. Multiple notes: PDF = multi-page document, PNG/iink = ZIP bundle. */
  fun exportToUri(context: Context, notes: List<MathNote>, format: NoteExportFormat, uri: Uri) {
    val out = context.contentResolver.openOutputStream(uri, "w")
      ?: error("Cannot open destination")
    out.use { stream ->
      when {
        format == NoteExportFormat.PDF -> writePdf(notes, stream)
        notes.size == 1 -> stream.write(singleNoteBytes(notes.first(), format))
        else -> ZipOutputStream(stream).use { zip ->
          val usedNames = mutableSetOf<String>()
          notes.forEach { note ->
            var name = fileBaseName(note)
            var n = 2
            val base = name
            while (!usedNames.add(name)) name = "$base ($n)".also { n++ }
            zip.putNextEntry(ZipEntry("$name.${format.extension}"))
            zip.write(singleNoteBytes(note, format))
            zip.closeEntry()
          }
        }
      }
    }
  }

  private fun singleNoteBytes(note: MathNote, format: NoteExportFormat): ByteArray = when (format) {
    NoteExportFormat.GMNOTE -> buildGmnote(note)
    NoteExportFormat.SVG -> buildSvg(note).toByteArray(Charsets.UTF_8)
    NoteExportFormat.IINK -> buildIinkPackage(note)
    NoteExportFormat.PNG -> {
      val bmp = renderNoteBitmap(note)
      ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
    NoteExportFormat.PDF -> ByteArrayOutputStream().also { writePdf(listOf(note), it) }.toByteArray()
  }

  // ---------------------------------------------------------------------------------------------
  // .gmnote: the app's own format. A ZIP with
  //   meta.json            {"format": "gmnote", "version": 1, …}
  //   note.json            the note as the app saves it, picture paths pointing into pictures/
  //   pictures/<name>      each picture and the page background
  // Everything is kept: the whole infinite canvas, layers, pen types, line styles, fills, tape.
  // ---------------------------------------------------------------------------------------------

  fun buildGmnote(note: MathNote): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zip ->
      fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
      // Paths inside the package instead of this device's paths
      val files = mutableMapOf<String, File>()
      fun pack(path: String): String {
        val f = File(path); files[f.name] = f
        return "pictures/" + f.name
      }
      val portable = note.copy(
        backgroundImagePath = note.backgroundImagePath?.let { pack(it) },
        strokes = note.strokes.map { s -> s.picture?.let { s.copy(picture = NotePicture(pack(it.path))) } ?: s }
      )
      put("meta.json", JSONObject().put("format", "gmnote").put("version", 1).put("generator", "Gemini Math Lab")
        .put("title", note.title).put("modified", note.updatedAt).toString(2).toByteArray())
      put("note.json", NotesRepository.serializeNoteJson(portable).toString().toByteArray())
      files.forEach { (name, f) -> if (f.exists()) put("pictures/$name", f.readBytes()) }
    }
    return bos.toByteArray()
  }

  /** Reads a .gmnote back: its pictures go in this device's folder, and it becomes a new note. */
  fun importGmnote(context: Context, bytes: ByteArray, folderId: String?): MathNote {
    var noteJson: String? = null
    val pictures = mutableMapOf<String, ByteArray>()
    java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
      while (true) {
        val e = zip.nextEntry ?: break
        when {
          e.name == "note.json" -> noteJson = zip.readBytes().toString(Charsets.UTF_8)
          e.name.startsWith("pictures/") && !e.isDirectory -> pictures[e.name] = zip.readBytes()
        }
      }
    }
    val json = JSONObject(noteJson ?: error("Not a Gemini Math note"))
    // Each picture gets a new file name here, so it never replaces one already on this device
    val renamed = pictures.mapValues { (name, data) ->
      val f = File(imagesDir(context), UUID.randomUUID().toString() + "." + name.substringAfterLast('.', "jpg"))
      f.writeBytes(data); f.absolutePath
    }
    json.optString("backgroundImagePath").takeIf { it.isNotEmpty() }?.let { json.put("backgroundImagePath", renamed[it] ?: JSONObject.NULL) }
    json.optJSONArray("strokes")?.let { arr ->
      for (i in 0 until arr.length()) {
        val s = arr.getJSONObject(i)
        s.optString("picture").takeIf { it.isNotEmpty() }?.let { p -> renamed[p]?.let { s.put("picture", it) } ?: s.remove("picture") }
      }
    }
    // A new id: importing a note twice (or on the device it came from) makes a copy, not a clash
    json.put("id", "note_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(4))
    val note = NotesRepository.parseNoteJson(json.toString())
    return note.copy(folderId = folderId ?: note.folderId, updatedAt = System.currentTimeMillis())
  }

  // ---------------------------------------------------------------------------------------------
  // SVG: a vector drawing of the whole canvas (no pixel limit), pictures and fills embedded
  // ---------------------------------------------------------------------------------------------

  private fun hex(argb: Long) = String.format(java.util.Locale.US, "#%06X", argb and 0xFFFFFF)
  private fun alpha(argb: Long) = ((argb ushr 24) and 0xFF) / 255.0
  private fun n(v: Float) = String.format(java.util.Locale.US, "%.1f", v)

  fun buildSvg(note: MathNote): String {
    val ink = inkBounds(note)
    val bg = note.backgroundImagePath?.let { p -> File(p).takeIf { it.exists() } }
    val bgBmp = bg?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    val region = when {
      bgBmp != null -> RectF(BG_X, BG_Y, BG_X + bgBmp.width, BG_Y + bgBmp.height).also { r -> ink?.let { r.union(it) } }
      ink != null -> RectF(ink)
      else -> RectF(0f, 0f, 800f, 600f)
    }.apply { inset(-40f, -40f) }
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="${n(region.left)} ${n(region.top)} ${n(region.width())} ${n(region.height())}" width="${n(region.width())}" height="${n(region.height())}">""").append('\n')
    sb.append("<title>").append(note.title.replace("&", "&amp;").replace("<", "&lt;")).append("</title>\n")
    sb.append("""<rect x="${n(region.left)}" y="${n(region.top)}" width="${n(region.width())}" height="${n(region.height())}" fill="${hex(note.paperColor.argb)}"/>""").append('\n')
    fun dataUri(f: File) = "data:image/" + (if (f.name.endsWith(".png", true)) "png" else "jpeg") + ";base64," +
      android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    if (bg != null && bgBmp != null) {
      sb.append("""<image x="${n(BG_X)}" y="${n(BG_Y)}" width="${bgBmp.width}" height="${bgBmp.height}" xlink:href="${dataUri(bg)}"/>""").append('\n')
    }
    val layerIds = note.layers.map { it.id }.toSet()
    for (layer in note.layers) {
      if (!layer.visible) continue
      sb.append("""<g id="layer-${layer.id}" opacity="${String.format(java.util.Locale.US, "%.2f", layer.opacity)}">""").append('\n')
      val onLayer = note.strokes.filter { (if (it.layer in layerIds) it.layer else note.layers.first().id) == layer.id && it.points.isNotEmpty() }
      // Same order as on screen: pictures, fills, highlighter, ink, tape
      val ordered = onLayer.filter { it.picture != null } + onLayer.filter { it.fill != null } +
        onLayer.filter { it.isHighlighter } + onLayer.filter { it.isInk } + onLayer.filter { it.isTape }
      for (s in ordered) {
        val a = s.points[0]; val b = s.points.getOrElse(1) { a }
        when {
          s.picture != null -> File(s.picture.path).takeIf { it.exists() }?.let { f ->
            sb.append("""<image x="${n(a.x)}" y="${n(a.y)}" width="${n(b.x - a.x)}" height="${n(b.y - a.y)}" preserveAspectRatio="none" xlink:href="${dataUri(f)}"/>""").append('\n')
          }
          s.fill != null -> {
            val m = s.fill
            val px = IntArray(m.w * m.h) { i -> FillMask.tint(s.colorHex, m.mask[i]) }
            val img = Bitmap.createBitmap(px, m.w, m.h, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().also { img.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            sb.append("""<image x="${n(a.x)}" y="${n(a.y)}" width="${n(b.x - a.x)}" height="${n(b.y - a.y)}" preserveAspectRatio="none" xlink:href="data:image/png;base64,${android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)}"/>""").append('\n')
          }
          else -> {
            val color = PaperDraw.displayInk(s.colorHex, note.paperColor)
            val w = s.width
            val dash = when {
              s.lineStyle.name.contains("DOT") -> """ stroke-dasharray="0.1 ${n(w * 2)}""""
              s.lineStyle.name.contains("DASH") -> """ stroke-dasharray="${n(w * 3)} ${n(w * 2)}""""
              else -> ""
            }
            val d = StringBuilder("M${n(a.x)} ${n(a.y)}")
            if (s.points.size == 1) d.append(" l0.01 0") else for (i in 1 until s.points.size) d.append(" L${n(s.points[i].x)} ${n(s.points[i].y)}")
            sb.append("""<path d="$d" fill="none" stroke="${hex(color)}" stroke-opacity="${String.format(java.util.Locale.US, "%.2f", alpha(color))}" stroke-width="${n(w)}" stroke-linecap="round" stroke-linejoin="round"$dash/>""").append('\n')
          }
        }
      }
      sb.append("</g>\n")
    }
    sb.append("</svg>\n")
    return sb.toString()
  }

  /** Builds a JIIX-style document (MyScript Interactive Ink eXchange, documented JSON format). */
  fun buildJiix(note: MathNote): JSONObject {
    val items = JSONArray()
    note.strokes.forEachIndexed { idx, s ->
      val xs = JSONArray()
      val ys = JSONArray()
      val ts = JSONArray()
      s.points.forEach { p ->
        xs.put((p.x * MM_PER_PX).toDouble())
        ys.put((p.y * MM_PER_PX).toDouble())
        ts.put(p.t)
      }
      items.put(
        JSONObject()
          .put("type", "stroke")
          .put("id", "s$idx")
          .put("X", xs)
          .put("Y", ys)
          .put("T", ts)
      )
    }
    val element = JSONObject()
      .put("type", "Math")
      .put("label", note.recognizedLatex)
      .put("items", items)
    return JSONObject()
      .put("type", "Raw Content")
      .put("version", "3")
      .put("id", note.id)
      .put("elements", JSONArray().put(element))
  }

  fun buildIinkPackage(note: MathNote): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zip ->
      fun put(name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
      val meta = JSONObject()
        .put("generator", "GeminiMathLab")
        .put("format", "iink-package")
        .put("contentType", "Math")
        .put("title", note.title)
        .put("latex", note.recognizedLatex)
        .put("unit", "mm")
      put("meta.json", meta.toString(2).toByteArray())
      put("content.jiix", buildJiix(note).toString(2).toByteArray())
      // Strip local-only image path from the portable copy, embed the image instead
      val portable = note.copy(backgroundImagePath = null)
      put("geminimath/note.json", NotesRepository.serializeNoteJson(portable).toString().toByteArray())
      note.backgroundImagePath?.let { path ->
        val f = File(path)
        if (f.exists()) put("geminimath/background.png", f.readBytes())
      }
    }
    return bos.toByteArray()
  }

  private fun inkBounds(note: MathNote): RectF? {
    val pts = note.strokes.flatMap { it.points }
    if (pts.isEmpty()) return null
    return RectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
  }

  private fun drawInk(canvas: AndroidCanvas, note: MathNote, scale: Float, dx: Float, dy: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }
    for (s in note.strokes) {
      if (s.points.isEmpty()) continue
      // Bucket fills: the mask, tinted, under its bounds
      s.fill?.let { m ->
        val a = s.points[0]; val b = s.points.getOrElse(1) { a }
        val px = IntArray(m.w * m.h) { i -> FillMask.tint(s.colorHex, m.mask[i]) }
        val img = Bitmap.createBitmap(px, m.w, m.h, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(img, null, RectF(dx + a.x * scale, dy + a.y * scale, dx + b.x * scale, dy + b.y * scale), null)
        return@let
      }
      if (s.fill != null) continue
      s.picture?.let { pic ->
        val a = s.points[0]; val b = s.points.getOrElse(1) { a }
        android.graphics.BitmapFactory.decodeFile(pic.path)?.let { img ->
          canvas.drawBitmap(img, null, RectF(dx + a.x * scale, dy + a.y * scale, dx + b.x * scale, dy + b.y * scale), null)
        }
        continue
      }
      paint.color = s.colorHex.toInt()
      paint.strokeWidth = (s.width * scale).coerceAtLeast(1f)
      if (s.points.size == 1) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(dx + s.points[0].x * scale, dy + s.points[0].y * scale, paint.strokeWidth / 2f, paint)
        paint.style = Paint.Style.STROKE
        continue
      }
      val path = AndroidPath()
      path.moveTo(dx + s.points[0].x * scale, dy + s.points[0].y * scale)
      for (i in 1 until s.points.size) path.lineTo(dx + s.points[i].x * scale, dy + s.points[i].y * scale)
      canvas.drawPath(path, paint)
    }
  }

  /** Renders the note (background image + ink) to a white bitmap. */
  fun renderNoteBitmap(note: MathNote, maxSide: Int = 2400): Bitmap {
    val bg = note.backgroundImagePath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    val ink = inkBounds(note)
    // Canvas region in note coordinates
    val region = when {
      bg != null -> {
        val r = RectF(BG_X, BG_Y, BG_X + bg.width, BG_Y + bg.height)
        ink?.let { r.union(it) }
        r
      }
      ink != null -> RectF(ink.left - 40f, ink.top - 40f, ink.right + 40f, ink.bottom + 40f)
      else -> RectF(0f, 0f, 800f, 600f)
    }
    // Fits in maxSide × maxSide whatever the size of the page. (An infinite canvas used to be
    // drawn at no less than 25%, so writing spread far apart made an image too big for memory.)
    val scale = minOf(maxSide / maxOf(region.width(), region.height()), 2f)
    val w = (region.width() * scale).toInt().coerceIn(64, maxSide)
    val h = (region.height() * scale).toInt().coerceIn(64, maxSide)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawColor(AndroidColor.WHITE)
    val dx = -region.left * scale
    val dy = -region.top * scale
    bg?.let {
      canvas.drawBitmap(it, null, RectF(dx + BG_X * scale, dy + BG_Y * scale, dx + (BG_X + it.width) * scale, dy + (BG_Y + it.height) * scale), null)
    }
    drawInk(canvas, note, scale, dx, dy)
    return bmp
  }

  /**
   * A4 pages. A note that fits is drawn on one page. A bigger one (the canvas is infinite) is
   * scaled to the page width, but not below [MIN_PDF_SCALE] so writing stays readable, and
   * continues over as many pages as it needs, top to bottom and left to right.
   */
  fun writePdf(notes: List<MathNote>, out: OutputStream) {
    val doc = PdfDocument()
    val pageW = 595
    val pageH = 842
    val margin = 36f
    var pageNo = 1
    notes.forEach { note ->
      val bg = note.backgroundImagePath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
      val ink = inkBounds(note)
      val region = when {
        bg != null -> RectF(BG_X, BG_Y, BG_X + bg.width, BG_Y + bg.height).also { r -> ink?.let { r.union(it) } }
        ink != null -> RectF(ink.left - 20f, ink.top - 20f, ink.right + 20f, ink.bottom + 20f)
        else -> null
      }
      val titleH = 28f
      val latexH = if (note.recognizedLatex.isNotBlank()) 24f else 0f
      val availW = pageW - 2 * margin
      val availH = pageH - 2 * margin - titleH - latexH
      // One page if it fits at a readable size; otherwise tiles at a readable scale
      val fitScale = region?.let { minOf(availW / it.width(), availH / it.height(), 1f) } ?: 1f
      val scale = if (region == null || fitScale >= MIN_PDF_SCALE) fitScale else maxOf(MIN_PDF_SCALE, minOf(availW / region.width(), 1f))
      val cols = region?.let { maxOf(1, kotlin.math.ceil(it.width() * scale / availW).toInt()) } ?: 1
      val rows = region?.let { maxOf(1, kotlin.math.ceil(it.height() * scale / availH).toInt()) } ?: 1
      for (col in 0 until cols) for (row in 0 until rows) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo++).create())
        val c = page.canvas
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; isFakeBoldText = true }
        val title = note.title.ifBlank { "Untitled" } + if (cols * rows > 1) "  (${col * rows + row + 1}/${cols * rows})" else ""
        c.drawText(title, margin, margin + 10f, titlePaint)
        if (region != null) {
          val avail = RectF(margin, margin + titleH, margin + availW, margin + titleH + availH)
          c.save()
          c.clipRect(avail)
          // This page shows the part of the note starting at (col × page width, row × page height)
          val dx = avail.left - region.left * scale - col * availW
          val dy = avail.top - region.top * scale - row * availH
          bg?.let { c.drawBitmap(it, null, RectF(dx + BG_X * scale, dy + BG_Y * scale, dx + (BG_X + it.width) * scale, dy + (BG_Y + it.height) * scale), null) }
          drawInk(c, note, scale, dx, dy)
          c.restore()
        }
        if (note.recognizedLatex.isNotBlank()) {
          val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = AndroidColor.DKGRAY }
          c.drawText("LaTeX: ${note.recognizedLatex}", margin, pageH - margin, p)
        }
        doc.finishPage(page)
      }
    }
    doc.writeTo(out)
    doc.close()
  }

  /** Smallest scale used in a PDF: below this, handwriting is too small to read on paper. */
  private const val MIN_PDF_SCALE = 0.35f

  // ---------------------------------------------------------------------------------------------
  // IMPORT
  // ---------------------------------------------------------------------------------------------

  fun displayName(context: Context, uri: Uri): String {
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0) ?: "Imported"
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported"
  }

  private fun imagesDir(context: Context): File =
    File(context.filesDir, "note_images").also { if (!it.exists()) it.mkdirs() }

  private fun saveBitmap(context: Context, bmp: Bitmap): String {
    val f = File(imagesDir(context), "img_${UUID.randomUUID().toString().take(10)}.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f.absolutePath
  }

  private fun newNote(title: String, folderId: String?) =
    NotesRepository.createBlankNote(folderId).copy(
      id = "note_${UUID.randomUUID().toString().take(8)}",
      title = title.ifBlank { "Imported" }
    )

  /** Imports a file and returns the created notes (not yet saved). Throws on unsupported content. */
  fun importFromUri(context: Context, uri: Uri, folderId: String?): List<MathNote> {
    val name = displayName(context, uri)
    val baseTitle = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = context.contentResolver.getType(uri).orEmpty()

    return when {
      ext == "pdf" || mime == "application/pdf" -> importPdf(context, uri, baseTitle, folderId)
      ext in setOf("png", "jpg", "jpeg", "webp") || mime.startsWith("image/") -> {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
          ?: error("Unreadable image")
        listOf(newNote(baseTitle, folderId).copy(backgroundImagePath = saveBitmap(context, bmp)))
      }
      ext == "sdocx" -> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: error("Unreadable file")
        importSdocx(bytes, baseTitle, folderId)
      }
      ext == "gmnote" -> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unreadable file")
        listOf(importGmnote(context, bytes, folderId))
      }
      ext == "spd" -> error("Old Samsung Notes .spd files aren't supported. Open the note in Samsung Notes and save it as a Samsung Notes file (.sdocx).")
      else -> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: error("Unreadable file")
        listOf(importIinkOrJiix(context, bytes, baseTitle, folderId))
      }
    }
  }

  private fun importPdf(context: Context, uri: Uri, baseTitle: String, folderId: String?): List<MathNote> {
    val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
      ?: error("Cannot open PDF")
    val result = mutableListOf<MathNote>()
    pfd.use { fd ->
      PdfRenderer(fd).use { renderer ->
        val count = minOf(renderer.pageCount, 60)
        for (i in 0 until count) {
          renderer.openPage(i).use { page ->
            val targetW = 1400
            val targetH = (targetW.toFloat() * page.height / page.width).toInt()
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AndroidColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val title = if (count > 1) "$baseTitle – p.${i + 1}" else baseTitle
            result += newNote(title, folderId).copy(backgroundImagePath = saveBitmap(context, bmp))
          }
        }
      }
    }
    if (result.isEmpty()) error("PDF has no pages")
    return result
  }

  // Samsung page units → editor pixels. Samsung Notes shows a 1848-unit page about 1.2× on a
  // tablet; our One UI sheet is ~2130 px wide, so 1.15 keeps handwriting the same size.
  private const val SDOCX_SCALE = 1.15f
  private const val SDOCX_LEFT = 420f
  private const val SDOCX_TOP = 200f

  /**
   * Imports the handwriting from a Samsung Notes `.sdocx` file as editable strokes, one note per
   * page. The canvas is infinite, so tall pages stay whole: scroll down to see the rest.
   */
  fun importSdocx(bytes: ByteArray, baseTitle: String, folderId: String?): List<MathNote> {
    val doc = SdocxParser.parse(bytes)
    val result = ArrayList<MathNote>()
    doc.pages.forEachIndexed { pageIndex, page ->
      if (page.strokes.isEmpty()) return@forEachIndexed
      val pagePart = if (doc.pages.size > 1) " – p.${pageIndex + 1}" else ""
      result += newNote("$baseTitle$pagePart", folderId).copy(strokes = page.strokes.map { s -> convertSdocxStroke(s) })
    }
    if (result.isEmpty()) error("This Samsung Notes file has no handwriting to import")
    return result
  }

  private fun convertSdocxStroke(s: SdocxParser.Stroke): InkStroke {
    val pts = s.xs.indices.map { i ->
      TimedPoint(SDOCX_LEFT + (s.xs[i] * SDOCX_SCALE).toFloat(), SDOCX_TOP + (s.ys[i] * SDOCX_SCALE).toFloat(), i * 8L)
    }
    val width = (s.penSize * SDOCX_SCALE * 0.9f).coerceIn(1.5f, 40f)
    val alpha = (s.colorArgb ushr 24) and 0xff
    return InkStroke(points = pts, width = width, colorHex = s.colorArgb, isHighlighter = alpha in 1..200)
  }

  /**
   * A page background from a picture (PNG, JPG, WebP) or the first page of a PDF, saved in app
   * storage. Returns its path, for [MathNote.backgroundImagePath].
   */
  fun backgroundFromUri(context: Context, uri: Uri): String {
    val name = displayName(context, uri)
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (ext == "pdf" || mime == "application/pdf") {
      val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
      pfd.use { fd ->
        PdfRenderer(fd).use { renderer ->
          if (renderer.pageCount == 0) error("PDF has no pages")
          renderer.openPage(0).use { page ->
            val targetW = 1400
            val bmp = Bitmap.createBitmap(targetW, (targetW.toFloat() * page.height / page.width).toInt(), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AndroidColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return saveBitmap(context, bmp)
          }
        }
      }
    }
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
      ?: error("Unsupported file: choose a PNG, JPG, WebP or PDF")
    return saveBitmap(context, bmp)
  }

  private fun isZip(bytes: ByteArray) =
    bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

  fun importIinkOrJiix(context: Context, bytes: ByteArray, baseTitle: String, folderId: String?): MathNote {
    if (!isZip(bytes)) {
      // Plain JIIX / JSON file
      val strokes = extractJiixStrokes(String(bytes))
      if (strokes.isEmpty()) error("No ink strokes found in file")
      return newNote(baseTitle, folderId).copy(strokes = strokes)
    }

    var nativeJson: String? = null
    var bgBytes: ByteArray? = null
    var metaLatex = ""
    val foreignStrokes = mutableListOf<InkStroke>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          val data = zip.readBytes()
          val n = entry.name.lowercase()
          when {
            n == "geminimath/note.json" -> nativeJson = String(data)
            n == "geminimath/background.png" -> bgBytes = data
            n == "meta.json" -> metaLatex = runCatching { JSONObject(String(data)).optString("latex") }.getOrDefault("")
            n.endsWith(".jiix") || n.endsWith(".json") ->
              foreignStrokes += runCatching { extractJiixStrokes(String(data)) }.getOrDefault(emptyList())
          }
        }
        entry = zip.nextEntry
      }
    }

    nativeJson?.let { json ->
      val parsed = NotesRepository.parseNoteJson(json)
      val bgPath = bgBytes?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let { saveBitmap(context, it) } }
      return parsed.copy(
        id = "note_${UUID.randomUUID().toString().take(8)}",
        updatedAt = System.currentTimeMillis(),
        folderId = folderId,
        backgroundImagePath = bgPath
      )
    }
    if (foreignStrokes.isEmpty()) {
      error("This .iink package has no readable ink (MyScript's internal layout is not public)")
    }
    return newNote(baseTitle, folderId).copy(strokes = foreignStrokes, recognizedLatex = metaLatex)
  }

  /** Recursively collects every JSON object having numeric `X` and `Y` arrays (JIIX stroke items). */
  fun extractJiixStrokes(jsonText: String): List<InkStroke> {
    val root: Any = runCatching { JSONObject(jsonText) }.getOrElse { JSONArray(jsonText) }
    val out = mutableListOf<InkStroke>()
    val pxPerMm = 1f / MM_PER_PX
    fun visit(node: Any?) {
      when (node) {
        is JSONObject -> {
          val xs = node.optJSONArray("X")
          val ys = node.optJSONArray("Y")
          if (xs != null && ys != null && xs.length() > 0 && xs.length() == ys.length()) {
            val ts = node.optJSONArray("T")
            val pts = (0 until xs.length()).map { i ->
              TimedPoint(
                (xs.optDouble(i) * pxPerMm).toFloat(),
                (ys.optDouble(i) * pxPerMm).toFloat(),
                ts?.optLong(i) ?: 0L
              )
            }
            out += InkStroke(pts)
          }
          node.keys().forEach { k -> visit(node.opt(k)) }
        }
        is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
      }
    }
    visit(root)
    // Shift so the ink starts near the top-left of the page
    if (out.isEmpty()) return out
    val minX = out.minOf { s -> s.points.minOf { it.x } }
    val minY = out.minOf { s -> s.points.minOf { it.y } }
    val dx = 120f - minX
    val dy = 180f - minY
    return out.map { s -> s.copy(points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) }) }
  }
}
