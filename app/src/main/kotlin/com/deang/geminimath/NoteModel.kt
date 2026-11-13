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


// A note's ink: points, strokes, fills, layers, and the app's tools and screens.

data class TimedPoint(val x: Float, val y: Float, val t: Long)

data class InkStroke(
  val points: List<TimedPoint>,
  val width: Float = 8.5f,
  val colorHex: Long = 0xFF1A1A1AL,
  val isHighlighter: Boolean = false,
  /** Samsung Notes "Tape": an opaque strip that hides what's under it until tapped. */
  val isTape: Boolean = false,
  val penType: PenType = PenType.PEN,
  val lineStyle: LineStyle = LineStyle.SOLID,
  /** Which layer of the note this belongs to (see [NoteLayer]). */
  val layer: Int = 0,
  /**
   * A bucket fill instead of a pen stroke. Its two points are the region's top-left and
   * bottom-right corners, so moving it with the lasso works like any stroke.
   */
  val fill: FillMask? = null,
  /** A picture (from the gallery, or a generated graph). Its two points are its corners. */
  val picture: NotePicture? = null,
  /** Highlighter with a chisel tip (flat ends) rather than a round one. */
  val chisel: Boolean = false,
  /** Tape's pattern (plain, stripes, dots, grid). */
  val tapePattern: TapePattern = TapePattern.PLAIN
) {
  /** Handwriting or a drawn line: not a highlighter, tape, fill or picture. */
  val isInk: Boolean get() = !isHighlighter && !isTape && fill == null && picture == null
}

/**
 * A filled region: a w × h coverage mask (1 byte per pixel, 0 or 255) stretched over its bounds.
 * Supports lazy decoding from its compressed Base64 string and caches the encoded string so
 * saving a note doesn't re-compress unchanged fills on every stroke.
 */
class FillMask private constructor(
  val w: Int,
  val h: Int,
  private var decodedMask: ByteArray?,
  private var encodedMask: String?
) {
  constructor(w: Int, h: Int, mask: ByteArray) : this(w, h, mask, null)
  constructor(w: Int, h: Int, encoded: String) : this(w, h, null, encoded)

  val mask: ByteArray
    @Synchronized get() = decodedMask ?: FillCodec.decode(encodedMask ?: "").also { decodedMask = it }

  val encoded: String
    @Synchronized get() = encodedMask ?: FillCodec.encode(mask).also { encodedMask = it }

  /** Decoded image for drawing, made on first use. */
  @Volatile var image: androidx.compose.ui.graphics.ImageBitmap? = null

  companion object {
    /** One pixel of [argb] at this mask byte's coverage (for exports). */
    fun tint(argb: Long, coverage: Byte): Int {
      val a = ((argb ushr 24) and 0xFF) * (coverage.toInt() and 0xFF) / 255
      return ((a shl 24) or (argb and 0xFFFFFF)).toInt()
    }
  }
}

/** One layer of a note. Layers are drawn bottom (first) to top (last). */
data class NoteLayer(val id: Int, val name: String, val visible: Boolean = true, val opacity: Float = 1f)

/** Samsung Notes-style masking tape. */
const val TAPE_WIDTH = 34f
const val TAPE_COLOR = 0xFFF2C894L

/** Set from the launch intent (debug builds' self-test); read once the OCR engine is ready. */
object OcrSelfTest { @Volatile var requested = false }

enum class MyScriptTool {
  PEN,
  HIGHLIGHTER,
  TAPE,
  /** Bucket fill: tap inside a closed shape. */
  FILL,
  LASSO,
  ERASER,
  LASER,
  IMAGE_GRAPH
}

enum class AppScreen {
  EDITOR,
  HOME_NOTES,
  QUICK_LATEX,
  SETTINGS
}
