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


// The solution steps card shown after solving, and maths text formatting.

/**
 * Exact 1:1 visual replica of MyScript Math's bottom-right floating "Solution steps" card
 * (`real_myscript_stepbystep.png` & `real_myscript_final_answer.png`).
 */
@Composable
fun AuthenticMyScriptSolutionStepsCard(
  symbolicSolution: SymbolicStepEngine.SymbolicSolution,
  areAllExpanded: Boolean,
  isFinalAnswerExpanded: Boolean,
  nanoExplanation: String?,
  isRunningNano: Boolean,
  onToggleExpandAll: () -> Unit,
  onToggleFinalAnswer: () -> Unit,
  onAskGeminiNano: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = Color(0xFF3D3D3D),
    shadowElevation = 10.dp,
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
    ) {
      // 1. Header: "Solution steps" + Expand-all icon + Close 'X'
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Solution steps",
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          Box(Modifier.clickable { onToggleExpandAll() }) {
            MyScriptIcons.ExpandChevron(areAllExpanded, color = Color.White, size = 16.dp)
          }
          Box(Modifier.clickable { onClose() }) {
            MyScriptIcons.CloseX(color = Color.White, size = 16.dp)
          }
        }
      }

      HorizontalDivider(color = Color(0xFF565656), thickness = 1.dp)

      // 2. Step Rows (STEP 1, STEP 2, ... in teal #4DB6AC)
      symbolicSolution.steps.forEachIndexed { idx, rawStep ->
        var expanded by remember(symbolicSolution, areAllExpanded) {
          mutableStateOf(idx == 0 || areAllExpanded)
        }
        val cleanStep = rawStep.replace(Regex("""^Passo\s+\d+\s*"""), "")
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "STEP ${idx + 1}",
              color = Color(0xFF4DB6AC),
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold
            )
            MyScriptIcons.ExpandChevron(expanded, color = Color.White, size = 14.dp)
          }

          if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            TypesetMathText(
              text = cleanStep,
              color = Color.White,
              fontSize = 13.sp
            )
          }
        }
        HorizontalDivider(color = Color(0xFF565656), thickness = 1.dp)
      }

      // Optional Gemini Nano Explanation inside the card when requested
      if (nanoExplanation != null) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF333333))
            .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
          Text(
            text = "GEMINI NANO TUTOR",
            color = Color(0xFF80CBC4),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(4.dp))
          TypesetMathText(
            text = nanoExplanation,
            color = Color.White,
            fontSize = 12.sp
          )
        }
        HorizontalDivider(color = Color(0xFF565656), thickness = 1.dp)
      }

      // 3. Teal "Final answer" Section (#3993A0 — exact match to real_myscript_final_answer.png)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF3993A0))
          .clickable { onToggleFinalAnswer() }
          .padding(horizontal = 16.dp, vertical = 12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Final answer",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
          )
          MyScriptIcons.ExpandChevron(isFinalAnswerExpanded, color = Color.White, size = 14.dp)
        }

        if (isFinalAnswerExpanded) {
          Spacer(modifier = Modifier.height(8.dp))
          TypesetMathText(
            text = symbolicSolution.finalAnswer,
            color = Color.White,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 16.sp
          )
        }
      }

      // 4. Darker Teal Footer (#2E6B75 — "Was this helpful?" + Ask Gemini Nano button)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF2E6B75))
          .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = if (isRunningNano) "Thinking..." else "Was this helpful? • ✨ AI Explain",
          color = Color(0xFFE0F2F1),
          fontSize = 11.sp,
          modifier = Modifier.clickable { onAskGeminiNano() }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          MyScriptIcons.ThumbUp(color = Color.White, size = 14.dp)
          MyScriptIcons.ThumbDown(color = Color.White, size = 14.dp)
        }
      }
    }
  }
}

fun formatPrettyMath(latex: String): String {
  if (latex.isBlank()) return "Write an expression"
  return latex
    .replace("\\int_{0}^{1} \\frac{\\ln(x)}{\\sqrt{x}} dx", "∫₀¹ ln(x)/√x dx")
    .replace("\\int", "∫")
    .replace("\\cdot", "·")
    .replace("^{x}", "ˣ")
    .replace("^{2}", "²")
    .replace("\\sqrt{x}", "√x")
    .replace("\\sqrt x", "√x")
    .replace("\\ln", "ln")
    .replace("\\sin", "sin")
    .replace("\\cos", "cos")
    .replace("\\pi", "π")
    .replace("\\left(", "(")
    .replace("\\right)", ")")
    .replace(Regex("""\\operatorname\s*\{\s*l\s*n\s*\}"""), "ln")
    .replace(Regex("""\\frac\s*\{\s*([^{}]+)\s*\}\s*\{\s*([^{}]+)\s*\}"""), "($1)/($2)")
    .replace("{", "")
    .replace("}", "")
    .replace("\\", "")
    .replace(Regex("""\s+"""), " ")
    .trim()
}
