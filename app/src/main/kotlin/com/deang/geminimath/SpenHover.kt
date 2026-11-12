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


// S Pen hover and side-button state, shared between the activity and the editor.

object SpenHoverBus {
  var lastHoverOffset by mutableStateOf(Offset(520f, 520f))
  var radialMenuVisible by mutableStateOf(false)
  var radialMenuCenter by mutableStateOf(Offset(520f, 520f))
  var hoverBtnWasPressed = false
  /** Mirrors the "S Pen button shortcuts" setting. */
  @Volatile var buttonShortcutsEnabled = true

  /** True while the side button is held and the pen hovers: the canvas shows an eraser cursor. */
  var eraserHover by mutableStateOf(false)
  /** Where the pen hovers over the page (page-canvas coordinates), or null when it's away. */
  var hover by mutableStateOf<Offset?>(null)
  /** Where the note canvas sits in the window; null when no editor is on screen. */
  var canvasOrigin: Offset? = null

  /** A press shorter than this, with no ink touched, opens or closes the quick menu. */
  private const val TAP_MS = 350L
  private var pressStart = 0L
  private var pressUsed = false
  private var pressAt = Offset.Zero

  /**
   * Side-button state while hovering, in window coordinates. A short tap toggles the quick menu;
   * holding the button turns the pen into an eraser instead.
   */
  fun onHoverButton(windowX: Float, windowY: Float, buttonDown: Boolean, time: Long) {
    val origin = canvasOrigin
    if (!buttonShortcutsEnabled || origin == null) {
      hoverBtnWasPressed = false
      eraserHover = false
      return
    }
    val local = Offset(windowX - origin.x, windowY - origin.y)
    if (buttonDown && !hoverBtnWasPressed) {
      hoverBtnWasPressed = true
      pressStart = time
      pressUsed = false
      pressAt = local
    } else if (!buttonDown && hoverBtnWasPressed) {
      hoverBtnWasPressed = false
      if (!pressUsed && time - pressStart <= TAP_MS) {
        radialMenuCenter = pressAt
        radialMenuVisible = !radialMenuVisible
      }
    }
    // Show the eraser cursor once the press is long enough not to be a tap
    eraserHover = buttonDown && time - pressStart > TAP_MS
  }

  /** The pen touched the page with the button held: this press was an erase, not a tap. */
  fun markPressUsed() {
    pressUsed = true
  }
}
