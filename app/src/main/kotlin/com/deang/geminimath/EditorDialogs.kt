package com.deang.geminimath

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Modal dialogs hosted by [EditorApp]: keyboard maths input, handwriting-to-text,
 * companion phone toolbar, toolbar customiser, page style picker, and note selection actions.
 */
@Composable
fun EditorDialogs(
  showTypeMaths: Boolean,
  recognizedLatex: String,
  onDismissTypeMaths: () -> Unit,
  onSolveTyped: (String) -> Unit,
  inkTextResult: Pair<String, String>?,
  onDismissInkText: () -> Unit,
  onCopyText: (String) -> Unit,
  onSolveInkLatex: (String) -> Unit,
  showPhoneToolbar: Boolean,
  onDismissPhoneToolbar: () -> Unit,
  customColors: List<Long>,
  onAddCustomColor: (Long) -> Unit,
  showCustomiseToolbar: Boolean,
  onDismissCustomiseToolbar: () -> Unit,
  toolbarLayout: ToolbarLayout,
  toolbarState: ToolbarState,
  onToolbarLayoutChange: (ToolbarLayout) -> Unit,
  toolbarDock: ToolbarDock,
  onToolbarDockChange: (ToolbarDock) -> Unit,
  showPageStyleDialog: Boolean,
  onDismissPageStyle: () -> Unit,
  uiStyleMode: UiStyleMode,
  paperPattern: PaperPattern,
  defaultPaper: PaperPattern,
  onSelectPaper: (PaperPattern) -> Unit,
  onMakeDefaultPaper: () -> Unit,
  onOpenAppearanceSettings: () -> Unit,
  paperColor: PaperColor,
  onSelectPaperColor: (PaperColor) -> Unit,
  hasBackground: Boolean,
  onPickBackground: () -> Unit,
  onRemoveBackground: () -> Unit,
  showExportDialog: Boolean,
  onDismissExport: () -> Unit,
  selectedCount: Int,
  onPickExportFormat: (NoteExportFormat) -> Unit,
  showMoveDialog: Boolean,
  onDismissMove: () -> Unit,
  folders: List<NoteFolder>,
  onPickMoveFolder: (String?) -> Unit,
  showRenameSelectedDialog: Boolean,
  onDismissRenameSelected: () -> Unit,
  renameSelectedTarget: MathNote?,
  onRenameSelected: (MathNote, String) -> Unit,
  showDeleteSelectedDialog: Boolean,
  onDismissDeleteSelected: () -> Unit,
  onConfirmDeleteSelected: () -> Unit,
  showRenameDialog: Boolean,
  onDismissRename: () -> Unit,
  noteTitle: String,
  onSaveNoteTitle: (String) -> Unit
) {
  // Keyboard tool: type the maths instead of writing it
  if (showTypeMaths) {
    var typed by remember { mutableStateOf(recognizedLatex) }
    fun solveTyped() {
      onDismissTypeMaths()
      if (typed.isNotBlank()) onSolveTyped(typed.trim())
    }
    OneUiDialog(
      title = "Type maths",
      onDismiss = onDismissTypeMaths,
      actions = listOf("Cancel" to onDismissTypeMaths, "Solve" to { solveTyped() })
    ) {
      Text(
        "Type an expression or equation in LaTeX or plain text, for example x^2-4=0 or \\int x e^x dx.",
        fontSize = 14.sp,
        color = EditorChrome.secondaryText
      )
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        singleLine = true,
        textStyle = TextStyle(fontSize = 18.sp, fontFamily = FontFamily.Monospace),
        keyboardActions = KeyboardActions(onDone = { solveTyped() }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }

  // Handwriting help / S Pen to text
  inkTextResult?.let { (title, latex) ->
    val readable = remember(latex) { NanoText.latexToText(latex) }
    val isHelp = title == "Handwriting help"
    OneUiDialog(
      title = title,
      onDismiss = onDismissInkText,
      actions = listOf(
        "Copy" to { onCopyText(if (isHelp) latex else readable); onDismissInkText() },
        "Solve" to { onDismissInkText(); onSolveInkLatex(latex) },
        "Close" to onDismissInkText
      )
    ) {
      Text(
        if (isHelp) "Your handwriting reads as:" else "Converted to text:",
        fontSize = 14.sp,
        color = EditorChrome.secondaryText
      )
      Spacer(Modifier.height(10.dp))
      Text(readable, fontSize = 24.sp, fontFamily = FontFamily.Serif, color = EditorChrome.text)
      Spacer(Modifier.height(8.dp))
      Text(latex, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = EditorChrome.secondaryText)
      if (isHelp) {
        Spacer(Modifier.height(10.dp))
        Text(
          "If a symbol is wrong, write it again more clearly, or use the Keyboard tool to type it.",
          fontSize = 13.sp,
          color = EditorChrome.secondaryText
        )
      }
    }
  }

  // This phone as the toolbar of a tablet / Googlebook
  if (showPhoneToolbar) {
    Dialog(
      onDismissRequest = { RemoteToolbarLink.stop(); onDismissPhoneToolbar() },
      properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
      val toolbarDark = RemoteToolbarLink.remoteState?.dark == true
      val dialogView = LocalView.current
      SideEffect {
        (dialogView.parent as? DialogWindowProvider)?.window?.let { w ->
          WindowCompat.getInsetsController(w, dialogView).isAppearanceLightStatusBars = !toolbarDark
        }
      }
      PhoneToolbarScreen(
        onClose = { RemoteToolbarLink.closeFromToolbar(); onDismissPhoneToolbar() },
        customColors = customColors,
        onAddCustomColor = onAddCustomColor
      )
    }
  }

  // Customise toolbar (full screen, like Samsung Notes)
  if (showCustomiseToolbar) {
    Dialog(
      onDismissRequest = onDismissCustomiseToolbar,
      properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
      CustomiseToolbarScreen(
        layout = toolbarLayout,
        state = toolbarState,
        onChange = onToolbarLayoutChange,
        onBack = onDismissCustomiseToolbar,
        dock = toolbarDock,
        onDock = onToolbarDockChange
      )
    }
  }

  if (showPageStyleDialog) {
    PageStyleDialog(
      uiStyle = uiStyleMode,
      paper = paperPattern,
      isDefault = paperPattern == defaultPaper,
      onSelectPaper = onSelectPaper,
      onMakeDefault = onMakeDefaultPaper,
      onOpenSettings = onOpenAppearanceSettings,
      onClose = onDismissPageStyle,
      paperColor = paperColor,
      onSelectColor = onSelectPaperColor,
      hasBackground = hasBackground,
      onPickBackground = onPickBackground,
      onRemoveBackground = onRemoveBackground
    )
  }

  if (showExportDialog) {
    ExportFormatDialog(
      count = selectedCount,
      onPick = onPickExportFormat,
      onDismiss = onDismissExport
    )
  }

  if (showMoveDialog) {
    MoveToFolderDialog(
      folders = folders,
      onPick = onPickMoveFolder,
      onDismiss = onDismissMove
    )
  }

  if (showRenameSelectedDialog) {
    if (renameSelectedTarget == null) {
      onDismissRenameSelected()
    } else {
      RenameNoteDialog(
        initial = renameSelectedTarget.title,
        onSave = { newTitle -> onRenameSelected(renameSelectedTarget, newTitle) },
        onDismiss = onDismissRenameSelected
      )
    }
  }

  if (showDeleteSelectedDialog) {
    ConfirmDeleteNotesDialog(
      count = selectedCount,
      onConfirm = onConfirmDeleteSelected,
      onDismiss = onDismissDeleteSelected
    )
  }

  if (showRenameDialog) {
    var tempTitle by remember { mutableStateOf(noteTitle) }
    UiAlertDialog(
      onDismissRequest = onDismissRename,
      title = { Text("Rename note") },
      text = {
        OutlinedTextField(
          value = tempTitle,
          onValueChange = { tempTitle = it },
          singleLine = true
        )
      },
      confirmButton = {
        TextButton(onClick = {
          onSaveNoteTitle(tempTitle.ifBlank { "Untitled" })
          onDismissRename()
        }) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = onDismissRename) {
          Text("Cancel")
        }
      }
    )
  }
}
