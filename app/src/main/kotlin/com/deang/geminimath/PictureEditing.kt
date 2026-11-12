package com.deang.geminimath

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.roundToInt

/** Picture actions: hit-testing, crop, share, clipboard. Bounds are a picture stroke's two points. */
object PictureOps {
  fun bounds(s: InkStroke): Rect {
    val a = s.points[0]; val b = s.points.getOrElse(1) { a }
    return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
  }

  /** The top-most picture under the page point [p]. */
  fun hit(strokes: List<InkStroke>, p: Offset): InkStroke? =
    strokes.lastOrNull { it.picture != null && it.points.isNotEmpty() && bounds(it).contains(p) }

  fun withBounds(s: InkStroke, r: Rect): InkStroke {
    val t = s.points[0].t
    return s.copy(points = listOf(TimedPoint(r.left, r.top, t), TimedPoint(r.right, r.bottom, t)))
  }

  /** Keeps the part [frac] (0–1 of width and height) of the picture, as a new file. */
  fun crop(context: Context, s: InkStroke, frac: Rect): InkStroke? = runCatching {
    val pic = s.picture ?: return null
    val src = BitmapFactory.decodeFile(pic.path) ?: return null
    val x0 = (frac.left * src.width).roundToInt().coerceIn(0, src.width - 1)
    val y0 = (frac.top * src.height).roundToInt().coerceIn(0, src.height - 1)
    val x1 = (frac.right * src.width).roundToInt().coerceIn(x0 + 1, src.width)
    val y1 = (frac.bottom * src.height).roundToInt().coerceIn(y0 + 1, src.height)
    val out = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
    val file = NotePictures.save(context, out)
    val r = bounds(s)
    val nr = Rect(r.left + frac.left * r.width, r.top + frac.top * r.height, r.left + frac.right * r.width, r.top + frac.bottom * r.height)
    withBounds(s, nr).copy(picture = NotePicture(file.absolutePath).also { it.image = null })
  }.getOrNull()

  private fun uri(context: Context, s: InkStroke) =
    FileProvider.getUriForFile(context, context.packageName + ".files", File(s.picture!!.path))

  fun share(context: Context, s: InkStroke) {
    runCatching {
      val u = uri(context, s)
      val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, u)
        clipData = ClipData.newRawUri("", u)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(send, "Share picture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  /** Puts the picture on the system clipboard, so it can be pasted in other apps too. */
  fun copyToClipboard(context: Context, s: InkStroke) {
    runCatching {
      val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Picture", uri(context, s)))
    }
  }
}

/**
 * Frame around a long-pressed picture. Drag inside to move it, drag a corner to resize it
 * (keeping its shape). The menu above has Crop, Copy, Cut, Delete and Share. In crop mode,
 * drag the corners to choose what to keep, then Done.
 *
 * [rect] is the picture on screen. Moves and resizes are reported in screen pixels.
 */
@Composable
fun PictureSelectionOverlay(
  rect: Rect,
  dark: Boolean,
  onEditStart: () -> Unit,
  onMove: (dx: Float, dy: Float) -> Unit,
  onResize: (newRect: Rect) -> Unit,
  onEditEnd: () -> Unit,
  onCrop: (frac: Rect) -> Unit,
  onCopy: () -> Unit,
  onCut: () -> Unit,
  onDelete: () -> Unit,
  onShare: () -> Unit
) {
  val density = LocalDensity.current
  val handle = with(density) { 28.dp.toPx() }
  val blue = OneUi9Tokens.SamsungBlue
  var cropping by remember { mutableStateOf(false) }
  var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
  val current by rememberUpdatedState(rect)

  // The frame (sits exactly over the picture)
  Box(
    Modifier
      .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
      .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
      .pointerInput(cropping) {
        if (!cropping) detectDragGestures(
          onDragStart = { onEditStart() },
          onDragEnd = { onEditEnd() },
          onDragCancel = { onEditEnd() }
        ) { change, drag -> change.consume(); onMove(drag.x, drag.y) }
      }
      .semantics { contentDescription = "Selected picture. Drag to move it." }
  ) {
    Canvas(Modifier.fillMaxSize()) {
      if (cropping) {
        // Dim what will be cut away
        val c = Rect(crop.left * size.width, crop.top * size.height, crop.right * size.width, crop.bottom * size.height)
        val shade = Color(0x88000000)
        drawRect(shade, Offset.Zero, Size(size.width, c.top))
        drawRect(shade, Offset(0f, c.bottom), Size(size.width, size.height - c.bottom))
        drawRect(shade, Offset(0f, c.top), Size(c.left, c.height))
        drawRect(shade, Offset(c.right, c.top), Size(size.width - c.right, c.height))
        drawRect(Color.White, c.topLeft, c.size, style = Stroke(2.5f))
      } else {
        drawRect(blue, Offset.Zero, size, style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
      }
    }
  }

  // Corner handles: resize (keeping the shape), or move the crop corners
  val corners = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
  for ((cx, cy) in corners) {
    val px = if (cropping) rect.left + (if (cx == 0) crop.left else crop.right) * rect.width else if (cx == 0) rect.left else rect.right
    val py = if (cropping) rect.top + (if (cy == 0) crop.top else crop.bottom) * rect.height else if (cy == 0) rect.top else rect.bottom
    Box(
      Modifier
        .offset { IntOffset((px - handle / 2).roundToInt(), (py - handle / 2).roundToInt()) }
        .size(28.dp)
        .pointerInput(cropping, cx, cy) {
          detectDragGestures(
            onDragStart = { if (!cropping) onEditStart() },
            onDragEnd = { if (!cropping) onEditEnd() },
            onDragCancel = { if (!cropping) onEditEnd() }
          ) { change, drag ->
            change.consume()
            val r = current
            if (cropping) {
              val fx = drag.x / r.width; val fy = drag.y / r.height
              val min = 0.08f
              crop = crop.let { c ->
                Rect(
                  if (cx == 0) (c.left + fx).coerceIn(0f, c.right - min) else c.left,
                  if (cy == 0) (c.top + fy).coerceIn(0f, c.bottom - min) else c.top,
                  if (cx == 1) (c.right + fx).coerceIn(c.left + min, 1f) else c.right,
                  if (cy == 1) (c.bottom + fy).coerceIn(c.top + min, 1f) else c.bottom
                )
              }
            } else {
              // Grow from the opposite corner; the width decides, the height follows
              val aspect = r.height / r.width.coerceAtLeast(1f)
              val newW = (r.width + if (cx == 1) drag.x else -drag.x).coerceAtLeast(60f)
              val newH = newW * aspect
              val left = if (cx == 1) r.left else r.right - newW
              val top = if (cy == 1) r.top else r.bottom - newH
              onResize(Rect(left, top, left + newW, top + newH))
            }
          }
        }
        .clip(CircleShape)
        .background(Color.White)
        .border(2.5.dp, blue, CircleShape)
        .semantics { contentDescription = if (cropping) "Crop corner" else "Resize" }
    )
  }

  // Menu above the picture (below it when there's no room)
  val menuH = with(density) { 44.dp.toPx() }
  val gap = with(density) { 16.dp.toPx() }
  val menuY = if (rect.top - menuH - gap > gap) rect.top - menuH - gap else rect.bottom + gap
  Surface(
    shape = RoundedCornerShape(22.dp),
    color = if (dark) Color(0xFF2C2C2E) else Color(0xFF303030),
    shadowElevation = 8.dp,
    modifier = Modifier.offset { IntOffset(rect.left.roundToInt().coerceAtLeast(12), menuY.roundToInt().coerceAtLeast(12)) }
  ) {
    Row(Modifier.height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      @Composable fun item(label: String, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
          Text(label, color = Color.White, fontSize = 14.sp)
        }
      }
      @Composable fun sep() = Box(Modifier.width(1.dp).height(16.dp).background(Color(0x55FFFFFF)))
      if (cropping) {
        item("Cancel") { cropping = false; crop = Rect(0f, 0f, 1f, 1f) }
        sep()
        Box(Modifier.clip(RoundedCornerShape(18.dp)).background(blue).clickable {
          val c = crop
          cropping = false; crop = Rect(0f, 0f, 1f, 1f)
          if (c.width < 0.999f || c.height < 0.999f) onCrop(c)
        }.padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Done", color = Color.White, fontSize = 14.sp) }
      } else {
        item("Crop") { cropping = true }
        sep(); item("Copy", onCopy)
        sep(); item("Cut", onCut)
        sep(); item("Delete", onDelete)
        sep(); item("Share", onShare)
      }
    }
  }
}
