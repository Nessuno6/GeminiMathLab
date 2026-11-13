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


// The interactive inline graph of one or more functions drawn under a solution.
// Supports multiple curves (; separated) and S Pen / touch crosshair tracing that snaps to
// roots, local extrema, y-intercepts, and curve intersections.

private val SecondaryCurvePalette = listOf(
  Color(0xFFD81B60),
  Color(0xFF1E88E5),
  Color(0xFF8E24AA),
  Color(0xFFF4511E)
)

@Composable
fun FunctionPlotCanvas(
  expr: String,
  curveColor: Color = Color(0xFF00897B),
  modifier: Modifier = Modifier
) {
  val win = remember(expr) { GraphWindow(-6.0, 6.0, -8.0, 8.0) }
  val exprs = remember(expr) { GraphRender.splitExpressions(expr) }
  val specials = remember(expr, win) { GraphRender.specialPoints(expr, win) }
  var traceX by remember(expr) { mutableStateOf<Double?>(null) }
  val tracedPoint = remember(expr, traceX, win) {
    traceX?.let { tx -> GraphRender.snapTrace(expr, tx, win) }
  }

  Box(modifier = modifier) {
    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(expr) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull() ?: continue
              if (change.pressed) {
                val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                traceX = win.xMin + (win.xMax - win.xMin) * frac
                change.consume()
              }
            }
          }
        }
    ) {
      val w = size.width
      val h = size.height
      val xMin = win.xMin
      val xMax = win.xMax
      val yMin = win.yMin
      val yMax = win.yMax

      fun toScreenX(x: Double): Float = ((x - xMin) / (xMax - xMin) * w).toFloat()
      fun toScreenY(y: Double): Float = (h - (y - yMin) / (yMax - yMin) * h).toFloat()

      for (gx in -5..5) {
        val sx = toScreenX(gx.toDouble())
        drawLine(Color(0xFFEDF2F7), Offset(sx, 0f), Offset(sx, h), strokeWidth = 1f)
      }
      for (gy in -6..6 step 2) {
        val sy = toScreenY(gy.toDouble())
        drawLine(Color(0xFFEDF2F7), Offset(0f, sy), Offset(w, sy), strokeWidth = 1f)
      }

      val ox = toScreenX(0.0)
      val oy = toScreenY(0.0)
      drawLine(Color(0xFF94A3B8), Offset(0f, oy), Offset(w, oy), strokeWidth = 2f)
      drawLine(Color(0xFF94A3B8), Offset(ox, 0f), Offset(ox, h), strokeWidth = 2f)

      val steps = 280
      exprs.forEachIndexed { idx, singleExpr ->
        val col = if (idx == 0) curveColor else SecondaryCurvePalette[(idx - 1) % SecondaryCurvePalette.size]
        val path = Path()
        var started = false
        for (i in 0..steps) {
          val x = xMin + (xMax - xMin) * i / steps
          val y = LocalMathEngine.evalAt(singleExpr, x)
          if (y.isFinite() && y in -40.0..40.0) {
            val sx = toScreenX(x)
            val sy = toScreenY(y)
            if (!started) {
              path.moveTo(sx, sy)
              started = true
            } else {
              path.lineTo(sx, sy)
            }
          } else {
            started = false
          }
        }
        drawPath(path, col, style = Stroke(width = 3.8f, cap = StrokeCap.Round))
      }

      // Draw subtle markers on notable points (roots, extrema, intersections)
      for (sp in specials) {
        val sx = toScreenX(sp.x)
        val sy = toScreenY(sp.y)
        val dotColor = if (sp.kind == "Intersection") Color(0xFFD81B60) else curveColor
        drawCircle(Color.White, radius = 5.5f, center = Offset(sx, sy))
        drawCircle(dotColor, radius = 3.8f, center = Offset(sx, sy))
      }

      // Interactive trace crosshair & highlighted point
      tracedPoint?.let { tp ->
        val sx = toScreenX(tp.x)
        val sy = toScreenY(tp.y)
        drawLine(Color(0x883993A0), Offset(sx, 0f), Offset(sx, h), strokeWidth = 1.5f)
        drawLine(Color(0x883993A0), Offset(0f, sy), Offset(w, sy), strokeWidth = 1.5f)
        drawCircle(Color.White, radius = 8f, center = Offset(sx, sy))
        drawCircle(Color(0xFF00897B), radius = 5.5f, center = Offset(sx, sy))
      }
    }

    // Floating trace coordinate badge at bottom-left when tracing (or hint when idle)
    val badgeText = tracedPoint?.let { tp ->
      val prefix = if (tp.kind != "Point") "${tp.kind}: " else ""
      "${prefix}(${MathFormat.num(tp.x)}, ${MathFormat.num(tp.y)})"
    } ?: if (specials.isNotEmpty()) "Drag to trace roots & extrema" else null

    if (badgeText != null) {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (tracedPoint != null) Color(0xEE263238) else Color(0xCCFFFFFF),
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(horizontal = 10.dp, vertical = 6.dp)
          .clickable(enabled = tracedPoint != null) { traceX = null }
      ) {
        Text(
          text = badgeText,
          fontSize = 11.sp,
          fontWeight = if (tracedPoint != null) FontWeight.SemiBold else FontWeight.Normal,
          color = if (tracedPoint != null) Color.White else Color(0xFF64748B),
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
      }
    }
  }
}
