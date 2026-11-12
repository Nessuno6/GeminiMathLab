package com.deang.geminimath

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.PopupProperties

/** The design the app is showing, for components shared by both designs. Set by the app root. */
object CurrentUiStyle {
  var mode by mutableStateOf(UiStyleMode.ONEUI_9)
  val isOneUi: Boolean get() = mode == UiStyleMode.ONEUI_9
}

/**
 * The editor's bars, toolbar, menus and dialogs. They go dark automatically while the open note's
 * page is dark (black paper), like Samsung Notes in dark mode, and are light everywhere else.
 */
object EditorChrome {
  var dark by mutableStateOf(false)
  val bar: Color get() = if (dark) Color(0xFF1C1C1E) else Color.White
  val icon: Color get() = if (dark) Color(0xFFE3E3E8) else Color(0xFF45454A)
  val text: Color get() = if (dark) Color(0xFFF2F2F5) else OneUi9Tokens.PrimaryText
  val secondaryText: Color get() = if (dark) Color(0xFFA1A1A8) else OneUi9Tokens.SecondaryText
  val divider: Color get() = if (dark) Color(0xFF38383C) else Color(0xFFECECEF)
  val selected: Color get() = if (dark) Color(0xFF3A3A3E) else Color(0xFFE4E4E9)
  val menu: Color get() = if (dark) Color(0xFF2C2C2E) else Color.White
  val disabledIcon: Color get() = if (dark) Color(0xFF5E5E64) else Color(0xFFBDBDC2)

  /** Outline for an unselected colour dot: very dark colours get a grey ring on dark bars, very light ones on light bars. */
  fun swatchRing(argb: Long): Color {
    val lum = 0.2126f * ((argb shr 16) and 0xFF) / 255f + 0.7152f * ((argb shr 8) and 0xFF) / 255f + 0.0722f * (argb and 0xFF) / 255f
    return when {
      dark && lum < 0.2f -> Color(0xFF6E6E74)
      !dark && lum > 0.9f -> Color(0xFFD0D0D6)
      else -> Color.Transparent
    }
  }
}

/** One UI blue for actions in menus and dialogs. */
private val OneUiAction = Color(0xFF3E91FF)

/**
 * One UI colours for Material components (text fields, buttons, switches…) inside menus and
 * dialogs, dark while the editor is dark.
 */
@Composable
internal fun OneUiTheme(content: @Composable () -> Unit) {
  if (!CurrentUiStyle.isOneUi && !EditorChrome.dark) return content()
  val base = if (EditorChrome.dark) androidx.compose.material3.darkColorScheme() else MaterialTheme.colorScheme
  MaterialTheme(
    colorScheme = base.copy(
      primary = OneUiAction,
      surface = EditorChrome.menu,
      onSurface = EditorChrome.text,
      onSurfaceVariant = EditorChrome.secondaryText,
      background = EditorChrome.menu,
      onBackground = EditorChrome.text,
      outline = EditorChrome.secondaryText,
      outlineVariant = EditorChrome.divider
    ),
    content = content
  )
}
/**
 * A dropdown menu: One UI's (white, 26 dp corners, soft shadow, roomier rows) in the One UI design,
 * Material's in M3E. Same parameters as Material's DropdownMenu.
 */
@Composable
fun UiMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  offset: DpOffset = DpOffset(0.dp, 0.dp),
  scrollState: ScrollState = rememberScrollState(),
  properties: PopupProperties = PopupProperties(focusable = true),
  content: @Composable ColumnScope.() -> Unit
) {
  if (!CurrentUiStyle.isOneUi) {
    DropdownMenu(expanded, onDismissRequest, modifier, offset, scrollState, properties, content = content)
    return
  }
  OneUiTheme {
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = onDismissRequest,
      modifier = modifier.padding(vertical = 4.dp),
      offset = offset,
      scrollState = scrollState,
      properties = properties,
      shape = RoundedCornerShape(26.dp),
      containerColor = EditorChrome.menu,
      tonalElevation = 0.dp,
      shadowElevation = 10.dp,
      border = BorderStroke(0.5.dp, if (EditorChrome.dark) Color(0xFF3A3A3E) else Color(0xFFE6E6EA)),
      content = content
    )
  }
}

/** A menu row, One UI-styled (16 sp, wider padding, taller) in the One UI design. */
@Composable
fun UiMenuItem(
  text: @Composable () -> Unit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: (@Composable () -> Unit)? = null,
  enabled: Boolean = true
) {
  if (!CurrentUiStyle.isOneUi) {
    DropdownMenuItem(text = text, onClick = onClick, modifier = modifier, leadingIcon = leadingIcon, enabled = enabled)
    return
  }
  DropdownMenuItem(
    text = {
      CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 16.sp, color = if (enabled) EditorChrome.text else EditorChrome.disabledIcon)) { text() }
    },
    onClick = onClick,
    modifier = modifier.heightIn(min = 52.dp).widthIn(min = 200.dp),
    leadingIcon = leadingIcon,
    enabled = enabled,
    contentPadding = PaddingValues(horizontal = 24.dp)
  )
}

/**
 * An alert dialog. One UI: white card with 26 dp corners, bold title, buttons side by side at the
 * bottom in One UI blue. M3E: Material's AlertDialog. Same parameters as Material's AlertDialog.
 */
@Composable
fun UiAlertDialog(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: (@Composable () -> Unit)? = null,
  title: (@Composable () -> Unit)? = null,
  text: (@Composable () -> Unit)? = null
) {
  if (!CurrentUiStyle.isOneUi) {
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier, dismissButton = dismissButton, title = title, text = text)
    return
  }
  Dialog(onDismissRequest = onDismissRequest) {
    OneUiTheme {
      Surface(shape = RoundedCornerShape(26.dp), color = EditorChrome.menu, modifier = modifier.widthIn(max = 420.dp)) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp)) {
          if (title != null) {
            CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = EditorChrome.text)) { title() }
            Spacer(Modifier.height(12.dp))
          }
          if (text != null) {
            CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 15.sp, color = EditorChrome.text, lineHeight = 21.sp)) { text() }
            Spacer(Modifier.height(14.dp))
          }
          CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              if (dismissButton != null) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { dismissButton() }
                Box(Modifier.width(1.dp).height(18.dp).background(Color(0xFFDCDCE0)))
              }
              Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { confirmButton() }
            }
          }
        }
      }
    }
  }
}

/** A One UI dialog with a title, a body and up to three text actions (shown in either design). */
@Composable
fun OneUiDialog(
  title: String,
  onDismiss: () -> Unit,
  actions: List<Pair<String, () -> Unit>>,
  body: @Composable ColumnScope.() -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    OneUiTheme {
    Surface(shape = RoundedCornerShape(26.dp), color = EditorChrome.menu, modifier = Modifier.widthIn(max = 460.dp)) {
      Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = EditorChrome.text)
        Spacer(Modifier.height(12.dp))
        body()
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          actions.forEachIndexed { i, (label, action) ->
            if (i > 0) Box(Modifier.width(1.dp).height(18.dp).background(Color(0xFFDCDCE0)))
            androidx.compose.material3.TextButton(onClick = action, modifier = Modifier.weight(1f)) {
              Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OneUiAction)
            }
          }
        }
      }
    }
    }
  }
}
