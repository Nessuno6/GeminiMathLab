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


class MainActivity : ComponentActivity() {
  override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
    val action = ev.actionMasked
    // Where the pen hovers over the page (the eraser circle follows it). Hover moves arrive here,
    // not at the page's touch handler.
    if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER) {
      SpenHoverBus.canvasOrigin?.let { o -> SpenHoverBus.hover = Offset(ev.x - o.x, ev.y - o.y) }
    } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
      SpenHoverBus.hover = null
    }
    if (
      action == MotionEvent.ACTION_HOVER_MOVE ||
      action == MotionEvent.ACTION_HOVER_ENTER ||
      action == MotionEvent.ACTION_HOVER_EXIT ||
      action == MotionEvent.ACTION_BUTTON_PRESS ||
      action == MotionEvent.ACTION_BUTTON_RELEASE
    ) {
      val stylusBtnDown = when (action) {
        MotionEvent.ACTION_BUTTON_PRESS -> true
        MotionEvent.ACTION_BUTTON_RELEASE -> false
        else -> (ev.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
      }
      // HOVER_EXIT also fires when the pen touches down; don't treat it as a release then
      if (action != MotionEvent.ACTION_HOVER_EXIT) {
        SpenHoverBus.onHoverButton(ev.x, ev.y, stylusBtnDown, ev.eventTime)
      }
      // The press and release themselves are ours; no view needs them
      if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) return true
    }
    return super.dispatchGenericMotionEvent(ev)
  }

  /** Turns Android 17 Handoff on while a note is open in the editor, off elsewhere. */
  fun setNoteHandoffEnabled(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < 37) return
    runCatching { setHandoffEnabled(enabled, null) }
  }

  @RequiresApi(37)
  override fun onHandoffActivityDataRequested(
    handoffRequestInfo: android.app.HandoffActivityDataRequestInfo
  ): android.app.HandoffActivityData {
    if (handoffRequestInfo.isActiveRequest) {
      // Save the latest ink without holding up the handoff
      window.decorView.post { NoteHandoff.onHandoffStarted?.invoke() }
    }
    val builder = android.app.HandoffActivityData.Builder(componentName)
    NoteHandoff.openNote?.let { builder.setExtras(NoteHandoff.buildExtras(it)) }
    return builder.build()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    NoteHandoff.receive(this, intent)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Before any note is read: pictures are found in this device's folder
    NotePictures.init(this)
    // A note handed off from another device: store it before the UI loads the notes
    if (savedInstanceState == null) NoteHandoff.receive(this, intent)
    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      OcrSelfTest.requested = intent?.getBooleanExtra("ocr_selftest", false) == true
      SolverSelfTest.requested = intent?.getBooleanExtra("solver_selftest", false) == true
    }
    enableEdgeToEdge()
    setContent {
      MaterialTheme(
        colorScheme = lightColorScheme(
          primary = Color(0xFF1A73E8),
          secondary = Color(0xFF3993A0),
          surface = Color.White,
          background = Color(0xFFEDEDED)
        )
      ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFEDEDED)) {
          MyScriptExactReplicaApp()
        }
      }
    }
  }
}
