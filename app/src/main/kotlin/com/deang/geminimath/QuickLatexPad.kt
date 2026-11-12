package com.deang.geminimath

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.hypot

/**
 * Scratch pad that turns handwriting into LaTeX. It keeps its own ink, separate from any note,
 * and recognises automatically shortly after you stop writing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun QuickLatexPad(
  penOnly: Boolean,
  recognize: suspend (List<InkStroke>) -> String?,
  onCopyLatex: (String) -> Unit,
  onOpenAsNote: (List<InkStroke>, String) -> Unit,
  modifier: Modifier = Modifier
) {
  val strokes = remember { mutableStateListOf<InkStroke>() }
  var current by remember { mutableStateOf<List<TimedPoint>>(emptyList()) }
  var erasing by remember { mutableStateOf(false) }
  var latex by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var version by remember { mutableIntStateOf(0) }

  // Recognise 700 ms after the last change
  LaunchedEffect(version) {
    if (strokes.isEmpty()) { latex = ""; return@LaunchedEffect }
    delay(700)
    busy = true
    latex = recognize(strokes.toList()).orEmpty()
    busy = false
  }
  val preview = remember(latex) {
    if (latex.isBlank()) null
    else SymbolicStepEngine.solveFromLatex(latex).let { if (it.isUnreadable) null else it.normalizedMath }
  }

  Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(
      modifier = Modifier
        .weight(0.6f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(Color(0xFFF5F5F5))
    ) {
      Canvas(
        modifier = Modifier
          .fillMaxSize()
          .pointerInteropFilter { e ->
            if (penOnly && e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) return@pointerInteropFilter true
            val p = TimedPoint(e.x, e.y, e.eventTime)
            val eraseNow = erasing || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
            when (e.actionMasked) {
              MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (eraseNow) {
                  if (strokes.removeAll { s -> s.points.any { q -> hypot(q.x - p.x, q.y - p.y) < 30f } }) version++
                } else {
                  current = if (e.actionMasked == MotionEvent.ACTION_DOWN) listOf(p) else current + p
                }
                true
              }
              MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!eraseNow && current.size > 1) {
                  strokes.add(InkStroke(current + p))
                  version++
                }
                current = emptyList()
                true
              }
              else -> false
            }
          }
      ) {
        var gy = 48f
        while (gy < size.height) {
          drawLine(Color(0xFFE0E0E0), Offset(0f, gy), Offset(size.width, gy), 1.2f)
          gy += 48f
        }
        fun draw(pts: List<TimedPoint>, w: Float) {
          if (pts.size < 2) return
          val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
          }
          drawPath(path, Color(0xFF1A1A1A), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        for (s in strokes) draw(s.points, s.width)
        draw(current, 8.5f)
      }

      if (strokes.isEmpty() && current.isEmpty()) {
        Text(
          "Write a math expression here",
          fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp, color = Color(0xFF9E9E9E),
          modifier = Modifier.padding(start = 20.dp, top = 16.dp)
        )
      }

      PadButton("Clear", enabled = strokes.isNotEmpty(), modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)) {
        strokes.clear()
        version++
      }

      Surface(
        shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 4.dp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
      ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ToolDot(selected = !erasing, label = "Pen", onClick = { erasing = false }) { MyScriptIcons.EditPencil(Color(0xFF1F1F1F), 16.dp) }
          ToolDot(selected = erasing, label = "Eraser", onClick = { erasing = true }) { MyScriptIcons.EraserBlock(Color(0xFF1F1F1F), 17.dp) }
        }
      }
    }

    Box(
      modifier = Modifier
        .weight(0.4f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(Color(0xFFF5F5F5))
        .padding(18.dp)
    ) {
      Column(Modifier.align(Alignment.TopStart)) {
        Text("LaTeX", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF757575))
        Spacer(Modifier.height(6.dp))
        Text(
          text = when {
            busy && latex.isBlank() -> "Recognising…"
            latex.isBlank() -> "Your LaTeX will appear here"
            else -> latex
          },
          fontFamily = FontFamily.Monospace, fontSize = 16.sp,
          color = if (latex.isBlank()) Color(0xFF9E9E9E) else Color(0xFF1F1F1F)
        )
        if (preview != null) {
          Spacer(Modifier.height(12.dp))
          Text("Reads as", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF757575))
          Spacer(Modifier.height(4.dp))
          Text(preview, fontSize = 18.sp, fontFamily = FontFamily.Serif, color = Color(0xFF1F1F1F))
        }
      }
      Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PadButton("Open as note", enabled = strokes.isNotEmpty()) { onOpenAsNote(strokes.toList(), latex) }
        Surface(
          shape = RoundedCornerShape(20.dp), color = Color(0xFFEA5244), shadowElevation = 3.dp,
          modifier = Modifier
            .alpha(if (latex.isNotBlank()) 1f else 0.4f)
            .clickable(enabled = latex.isNotBlank()) { onCopyLatex(latex) }
        ) {
          Text("{ }  Copy LaTeX", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
        }
      }
    }
  }
}

@Composable
private fun PadButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 2.dp,
    modifier = modifier.alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled, onClick = onClick)
  ) {
    Text(label, fontSize = 13.sp, color = Color(0xFF1F1F1F), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
  }
}

@Composable
private fun ToolDot(selected: Boolean, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(34.dp)
      .clip(CircleShape)
      .background(if (selected) Color(0xFFDCDCDC) else Color.Transparent)
      .clickable(onClick = onClick)
      .semantics { contentDescription = label; this.selected = selected }
  ) { icon() }
}
