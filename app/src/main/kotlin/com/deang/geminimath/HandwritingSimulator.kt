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


// Sample handwriting used by the recogniser self-test.

object HandwritingSimulator {
  fun generateSampleStrokes(testId: Int): List<InkStroke> {
    val list = mutableListOf<InkStroke>()
    var t = SystemClock.uptimeMillis()

    fun addPolyline(pts: List<Pair<Float, Float>>, width: Float = 8.5f) {
      if (pts.size < 2) return
      val interpolated = mutableListOf<TimedPoint>()
      for (i in 0 until pts.size - 1) {
        val (x0, y0) = pts[i]
        val (x1, y1) = pts[i + 1]
        val dist = hypot(x1 - x0, y1 - y0)
        val steps = (dist / 6f).toInt().coerceAtLeast(2)
        for (s in 0 until steps) {
          val f = s.toFloat() / steps
          interpolated.add(TimedPoint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, t))
          t += 5L
        }
      }
      val last = pts.last()
      interpolated.add(TimedPoint(last.first, last.second, t))
      t += 15L
      list.add(InkStroke(interpolated, width))
    }

    fun drawChar(ch: Char, x: Float, y: Float, h: Float) {
      val w = h * 0.62f
      when (ch) {
        'x' -> {
          addPolyline(listOf(x to y + h * 0.25f, x + w to y + h))
          addPolyline(listOf(x + w to y + h * 0.25f, x to y + h))
        }
        'e' -> {
          addPolyline(
            listOf(
              x + w * 0.15f to y + h * 0.62f,
              x + w * 0.85f to y + h * 0.58f,
              x + w * 0.78f to y + h * 0.34f,
              x + w * 0.35f to y + h * 0.34f,
              x + w * 0.12f to y + h * 0.65f,
              x + w * 0.38f to y + h * 0.94f,
              x + w * 0.82f to y + h * 0.86f
            )
          )
        }
        '0' -> {
          val pts = (0..24).map { i ->
            val a = 2.0 * PI * i / 24.0
            (x + w * 0.5f + (w * 0.45f * cos(a)).toFloat()) to (y + h * 0.5f + (h * 0.48f * sin(a)).toFloat())
          }
          addPolyline(pts)
        }
        '1' -> {
          addPolyline(listOf(x + w * 0.2f to y + h * 0.22f, x + w * 0.5f to y, x + w * 0.5f to y + h))
        }
        '2' -> {
          addPolyline(
            listOf(
              x + w * 0.1f to y + h * 0.25f,
              x + w * 0.45f to y + h * 0.04f,
              x + w * 0.85f to y + h * 0.22f,
              x + w * 0.8f to y + h * 0.48f,
              x + w * 0.1f to y + h * 0.96f,
              x + w * 0.92f to y + h * 0.96f
            )
          )
        }
        '3' -> {
          addPolyline(
            listOf(
              x + w * 0.12f to y + h * 0.15f,
              x + w * 0.55f to y + h * 0.04f,
              x + w * 0.85f to y + h * 0.25f,
              x + w * 0.45f to y + h * 0.48f,
              x + w * 0.88f to y + h * 0.75f,
              x + w * 0.5f to y + h * 0.96f,
              x + w * 0.1f to y + h * 0.85f
            )
          )
        }
        '5' -> {
          addPolyline(
            listOf(
              x + w * 0.85f to y + h * 0.06f,
              x + w * 0.18f to y + h * 0.06f,
              x + w * 0.15f to y + h * 0.46f,
              x + w * 0.65f to y + h * 0.44f,
              x + w * 0.88f to y + h * 0.68f,
              x + w * 0.55f to y + h * 0.96f,
              x + w * 0.12f to y + h * 0.88f
            )
          )
        }
        '6' -> {
          addPolyline(
            listOf(
              x + w * 0.78f to y + h * 0.08f,
              x + w * 0.35f to y + h * 0.25f,
              x + w * 0.12f to y + h * 0.65f,
              x + w * 0.45f to y + h * 0.96f,
              x + w * 0.85f to y + h * 0.75f,
              x + w * 0.55f to y + h * 0.48f,
              x + w * 0.15f to y + h * 0.62f
            )
          )
        }
        '+' -> {
          addPolyline(listOf(x + w * 0.5f to y + h * 0.22f, x + w * 0.5f to y + h * 0.88f))
          addPolyline(listOf(x + w * 0.08f to y + h * 0.55f, x + w * 0.92f to y + h * 0.55f))
        }
        '-' -> {
          addPolyline(listOf(x + w * 0.1f to y + h * 0.55f, x + w * 0.9f to y + h * 0.55f))
        }
        '=' -> {
          addPolyline(listOf(x + w * 0.1f to y + h * 0.42f, x + w * 0.9f to y + h * 0.42f))
          addPolyline(listOf(x + w * 0.1f to y + h * 0.68f, x + w * 0.9f to y + h * 0.68f))
        }
        '(' -> {
          val pts = (0..14).map { i ->
            val a = -PI * 0.38 + (PI * 0.76) * i / 14.0
            (x + w * 0.75f - (w * 0.52f * cos(a)).toFloat()) to (y + h * 0.5f + (h * 0.55f * sin(a)).toFloat())
          }
          addPolyline(pts)
        }
        ')' -> {
          val pts = (0..14).map { i ->
            val a = -PI * 0.38 + (PI * 0.76) * i / 14.0
            (x + w * 0.18f + (w * 0.52f * cos(a)).toFloat()) to (y + h * 0.5f + (h * 0.55f * sin(a)).toFloat())
          }
          addPolyline(pts)
        }
        'l' -> {
          addPolyline(listOf(x + w * 0.45f to y, x + w * 0.45f to y + h))
        }
        'n' -> {
          addPolyline(listOf(x + w * 0.15f to y + h * 0.3f, x + w * 0.15f to y + h))
          addPolyline(
            listOf(
              x + w * 0.15f to y + h * 0.48f,
              x + w * 0.55f to y + h * 0.3f,
              x + w * 0.82f to y + h * 0.48f,
              x + w * 0.82f to y + h
            )
          )
        }
        's' -> {
          addPolyline(
            listOf(
              x + w * 0.8f to y + h * 0.35f,
              x + w * 0.45f to y + h * 0.26f,
              x + w * 0.18f to y + h * 0.44f,
              x + w * 0.78f to y + h * 0.72f,
              x + w * 0.45f to y + h * 0.96f,
              x + w * 0.15f to y + h * 0.86f
            )
          )
        }
        'i' -> {
          addPolyline(listOf(x + w * 0.45f to y + h * 0.35f, x + w * 0.45f to y + h))
          addPolyline(listOf(x + w * 0.45f to y + h * 0.12f, x + w * 0.48f to y + h * 0.16f))
        }
        'd' -> {
          val loop = (0..18).map { i ->
            val a = 2.0 * PI * i / 18.0
            (x + w * 0.42f + (w * 0.3f * cos(a)).toFloat()) to (y + h * 0.68f + (h * 0.28f * sin(a)).toFloat())
          }
          addPolyline(loop)
          addPolyline(listOf(x + w * 0.74f to y, x + w * 0.74f to y + h))
        }
      }
    }

    when (testId) {
      1 -> {
        val y0 = 340f
        val h = 84f
        drawChar('x', 260f, y0, h)
        drawChar('2', 325f, y0 - 40f, h * 0.6f)
        drawChar('-', 400f, y0, h)
        drawChar('5', 485f, y0, h)
        drawChar('x', 555f, y0, h)
        drawChar('+', 645f, y0, h)
        drawChar('6', 735f, y0, h)
        drawChar('=', 825f, y0, h)
        drawChar('0', 915f, y0, h)
      }
      2 -> {
        addPolyline(
          listOf(
            315f to 250f,
            295f to 235f,
            278f to 265f,
            272f to 420f,
            262f to 575f,
            245f to 600f,
            228f to 585f
          )
        )
        drawChar('1', 325f, 205f, 54f)
        drawChar('0', 290f, 565f, 54f)

        drawChar('l', 420f, 290f, 75f)
        drawChar('n', 458f, 290f, 75f)
        drawChar('(', 520f, 290f, 75f)
        drawChar('x', 565f, 290f, 75f)
        drawChar(')', 625f, 290f, 75f)

        addPolyline(listOf(395f to 415f, 685f to 415f))

        addPolyline(
          listOf(
            435f to 495f,
            455f to 485f,
            478f to 545f,
            508f to 445f,
            615f to 445f
          )
        )
        drawChar('x', 530f, 460f, 75f)

        drawChar('d', 735f, 365f, 82f)
        drawChar('x', 805f, 365f, 82f)
      }
      3 -> {
        drawChar('2', 290f, 280f, 76f)
        drawChar('x', 355f, 280f, 76f)
        drawChar('+', 430f, 280f, 76f)
        drawChar('3', 505f, 280f, 76f)

        addPolyline(listOf(270f to 400f, 585f to 400f))

        drawChar('x', 320f, 435f, 76f)
        drawChar('-', 400f, 435f, 76f)
        drawChar('1', 480f, 435f, 76f)

        drawChar('=', 635f, 355f, 82f)
        drawChar('5', 730f, 355f, 82f)
      }
      4 -> {
        addPolyline(
          listOf(
            305f to 260f,
            285f to 245f,
            270f to 275f,
            264f to 410f,
            254f to 545f,
            238f to 565f,
            222f to 550f
          )
        )
        addPolyline(listOf(315f to 225f, 365f to 225f))
        addPolyline(listOf(328f to 225f, 324f to 262f))
        addPolyline(listOf(352f to 225f, 354f to 262f))
        drawChar('0', 280f, 540f, 52f)

        drawChar('s', 425f, 360f, 80f)
        drawChar('i', 472f, 360f, 80f)
        drawChar('n', 508f, 360f, 80f)
        drawChar('(', 578f, 360f, 80f)
        drawChar('x', 625f, 360f, 80f)
        drawChar(')', 685f, 360f, 80f)
        drawChar('d', 765f, 360f, 80f)
        drawChar('x', 830f, 360f, 80f)
      }
      5 -> {
        // Exact replica of \int e^x \cdot x dx from real_myscript_screen1.png!
        addPolyline(
          listOf(
            262f to 365f,
            252f to 355f,
            245f to 372f,
            252f to 425f,
            256f to 468f,
            246f to 480f,
            238f to 472f
          ),
          width = 5.5f
        )
        drawChar('e', 278f, 385f, 65f)
        drawChar('x', 310f, 352f, 44f)
        addPolyline(listOf(348f to 418f, 350f to 420f), width = 6f)
        drawChar('x', 368f, 392f, 56f)
        drawChar('d', 425f, 382f, 64f)
        drawChar('x', 462f, 392f, 56f)
      }
    }
    return list
  }
}
