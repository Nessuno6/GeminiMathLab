package com.deang.geminimath

import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.graphicsLayer
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


// Ink drawn to bitmaps: the eraser pointer, clipboard pictures, and the image read by recognition.

/** The eraser's circle (radius [r] px) as a pointer icon, centred on the pen tip. */
fun eraserPointerIcon(r: Float): android.view.PointerIcon {
  val size = (r * 2 + 8).toInt()
  val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  val c = android.graphics.Canvas(bmp)
  val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
  val cx = size / 2f
  p.style = android.graphics.Paint.Style.FILL; p.color = 0x1A000000
  c.drawCircle(cx, cx, r, p)
  p.style = android.graphics.Paint.Style.STROKE
  p.strokeWidth = 3.5f; p.color = 0xD9FFFFFF.toInt(); c.drawCircle(cx, cx, r, p)
  p.strokeWidth = 1.6f; p.color = 0xFF6E6E76.toInt(); c.drawCircle(cx, cx, r, p)
  return android.view.PointerIcon.create(bmp, cx, cx)
}

/** A selection drawn as a picture, in its colours on the page colour (for the clipboard). */
fun selectionPicture(strokes: List<InkStroke>, paperArgb: Long): Bitmap {
  val b = LassoTools.bounds(strokes) ?: androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f)
  val margin = 16f + (strokes.maxOfOrNull { it.width } ?: 0f)
  val scale = (2f).coerceAtMost(2400f / maxOf(b.width + margin * 2, b.height + margin * 2))
  val w = ((b.width + margin * 2) * scale).toInt().coerceIn(1, 4000)
  val h = ((b.height + margin * 2) * scale).toInt().coerceIn(1, 4000)
  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val c = android.graphics.Canvas(bmp)
  c.drawColor(paperArgb.toInt())
  c.scale(scale, scale)
  c.translate(margin - b.left, margin - b.top)
  val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
  }
  for (s in strokes) {
    val p0 = s.points.firstOrNull() ?: continue
    val p1 = s.points.getOrElse(1) { p0 }
    val box = android.graphics.RectF(p0.x, p0.y, p1.x, p1.y)
    val pic = s.picture
    val fill = s.fill
    when {
      pic != null -> android.graphics.BitmapFactory.decodeFile(pic.path)?.let { c.drawBitmap(it, null, box, null) }
      fill != null -> {
        val px = IntArray(fill.w * fill.h) { i -> FillMask.tint(s.colorHex, fill.mask[i]) }
        c.drawBitmap(Bitmap.createBitmap(px, fill.w, fill.h, Bitmap.Config.ARGB_8888), null, box, null)
      }
      else -> {
        paint.color = s.colorHex.toInt()
        paint.strokeWidth = s.width
        if (s.points.size == 1) {
          paint.style = android.graphics.Paint.Style.FILL
          c.drawCircle(p0.x, p0.y, s.width / 2f, paint)
          paint.style = android.graphics.Paint.Style.STROKE
        } else {
          val path = android.graphics.Path().apply { moveTo(p0.x, p0.y); for (i in 1 until s.points.size) lineTo(s.points[i].x, s.points[i].y) }
          c.drawPath(path, paint)
        }
      }
    }
  }
  return bmp
}

/**
 * Writing done while zoomed in (or very large) is resized to the usual handwriting size first, so
 * the dot filter, padding and pen width below (all in page units) suit it. Small writing used to
 * come out as fat blobs with tiny strokes taken for dots, and the reader returned nonsense.
 */
internal fun normalizeInkSize(strokes: List<InkStroke>): List<InkStroke> {
  val sizes = strokes.filter { it.isInk && it.points.size > 1 }.mapNotNull { s ->
    val b = LassoTools.bounds(listOf(s)) ?: return@mapNotNull null
    max(b.width, b.height).takeIf { it > 2f }
  }.sorted()
  if (sizes.isEmpty()) return strokes
  val median = sizes[sizes.size / 2]
  // A typical stroke of handwriting at 100% is about 65 page units across
  val k = (65f / median).coerceIn(0.5f, 12f)
  if (k in 0.8f..1.25f) return strokes
  return strokes.map { s -> s.copy(points = s.points.map { it.copy(x = it.x * k, y = it.y * k) }) }
}

fun renderStrokesToBitmap(input: List<InkStroke>): Bitmap {
  val strokes = normalizeInkSize(input)
  data class StrokeBox(
    val stroke: InkStroke,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
  ) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val isTinyDot get() = width < 14f && height < 14f
    val cx get() = 0.5f * (minX + maxX)
    val cy get() = 0.5f * (minY + maxY)
  }

  val boxes = strokes.filter { it.isInk }.mapNotNull { s ->
    if (s.points.isEmpty()) return@mapNotNull null
    var x0 = Float.MAX_VALUE
    var y0 = Float.MAX_VALUE
    var x1 = Float.MIN_VALUE
    var y1 = Float.MIN_VALUE
    for (p in s.points) {
      if (p.x < x0) x0 = p.x
      if (p.y < y0) y0 = p.y
      if (p.x > x1) x1 = p.x
      if (p.y > y1) y1 = p.y
    }
    StrokeBox(s, x0, y0, x1, y1)
  }

  val nonDotBoxes = boxes.filter { !it.isTinyDot }
  val filteredStrokes = if (nonDotBoxes.isNotEmpty()) {
    val mainMinX = nonDotBoxes.minOf { it.minX }
    val mainMaxX = nonDotBoxes.maxOf { it.maxX }
    val mainMinY = nonDotBoxes.minOf { it.minY }
    val mainMaxY = nonDotBoxes.maxOf { it.maxY }
    boxes.filter { b ->
      !b.isTinyDot || (
        b.cx in (mainMinX - 70f)..(mainMaxX + 70f) &&
          b.cy in (mainMinY - 70f)..(mainMaxY + 70f)
        )
    }
  } else {
    boxes
  }

  if (filteredStrokes.isEmpty()) {
    return Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
  }

  val minX = filteredStrokes.minOf { it.minX }
  val minY = filteredStrokes.minOf { it.minY }
  val maxX = filteredStrokes.maxOf { it.maxX }
  val maxY = filteredStrokes.maxOf { it.maxY }

  val pad = 32f
  val contentW = (maxX - minX).coerceAtLeast(64f) + pad * 2
  val contentH = (maxY - minY).coerceAtLeast(64f) + pad * 2

  val scale = (640f / max(contentW, contentH)).coerceIn(0.5f, 2.0f)
  val bmpW = (contentW * scale).toInt().coerceAtLeast(128)
  val bmpH = (contentH * scale).toInt().coerceAtLeast(96)

  val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
  val canvas = AndroidCanvas(bmp)
  canvas.drawColor(AndroidColor.WHITE)

  val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = AndroidColor.BLACK
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    strokeWidth = (8.5f * scale).coerceAtLeast(5f)
  }

  for (b in filteredStrokes) {
    val pts = b.stroke.points
    if (pts.size == 1) {
      val cx = (pts[0].x - minX + pad) * scale
      val cy = (pts[0].y - minY + pad) * scale
      canvas.drawPoint(cx, cy, paint)
      continue
    }
    val path = AndroidPath()
    path.moveTo((pts[0].x - minX + pad) * scale, (pts[0].y - minY + pad) * scale)
    for (i in 1 until pts.size) {
      val px = (pts[i - 1].x - minX + pad) * scale
      val py = (pts[i - 1].y - minY + pad) * scale
      val cx = (pts[i].x - minX + pad) * scale
      val cy = (pts[i].y - minY + pad) * scale
      path.quadTo(px, py, (px + cx) / 2f, (py + cy) / 2f)
    }
    path.lineTo((pts.last().x - minX + pad) * scale, (pts.last().y - minY + pad) * scale)
    canvas.drawPath(path, paint)
  }
  return bmp
}
