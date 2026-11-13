package com.deang.geminimath

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Callbacks for the long-press multi-selection mode on the notes home screens. */
class NoteSelectionActions(
  val onClose: () -> Unit,
  val onDelete: () -> Unit,
  val onDuplicate: () -> Unit,
  val onExport: () -> Unit,
  val onSelectAll: () -> Unit,
  val onDeselectAll: () -> Unit,
  val onRename: () -> Unit,
  val onMoveToFolder: () -> Unit,
  val onToggleFavourite: () -> Unit
)

private fun countLabel(count: Int) = if (count == 1) "1 note" else "$count notes"

/** Red selection bar replicating MyScript Math (X, "1 note", ⋮ → Delete/Duplicate/Export/Select all/Deselect all). */
@Composable
fun MyScriptSelectionTopBar(count: Int, actions: NoteSelectionActions, modifier: Modifier = Modifier) {
  var menuOpen by remember { mutableStateOf(false) }
  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(52.dp)
      .background(Color(0xFFEA5244))
      .padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(40.dp).clip(CircleShape).clickable { actions.onClose() }
    ) { MyScriptIcons.CloseX(Color.White, 20.dp) }
    Spacer(Modifier.width(8.dp))
    Text(countLabel(count), color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    Box {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { menuOpen = true }
      ) { MyScriptIcons.MoreVerticalDots(Color.White, 20.dp) }
      UiMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        @Composable
        fun item(label: String, enabled: Boolean = true, action: () -> Unit) = UiMenuItem(
          text = { Text(label) },
          enabled = enabled,
          onClick = { menuOpen = false; action() }
        )
        item("Delete", count > 0, actions.onDelete)
        item("Duplicate", count > 0, actions.onDuplicate)
        item("Export", count > 0, actions.onExport)
        item("Rename", count == 1, actions.onRename)
        item("Move to folder", count > 0, actions.onMoveToFolder)
        item("Add to / remove from favourites", count > 0, actions.onToggleFavourite)
        HorizontalDivider()
        item("Select all", action = actions.onSelectAll)
        item("Deselect all", count > 0, actions.onDeselectAll)
      }
    }
  }
}

/** Samsung Notes One UI 9.0 style selection header ("All" checkbox + "N selected"). */
@Composable
fun OneUi9SelectionHeader(
  count: Int,
  allSelected: Boolean,
  actions: NoteSelectionActions,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.clickable { if (allSelected) actions.onDeselectAll() else actions.onSelectAll() }
    ) {
      Checkbox(
        checked = allSelected,
        onCheckedChange = { if (it) actions.onSelectAll() else actions.onDeselectAll() },
        colors = CheckboxDefaults.colors(checkedColor = OneUi9Tokens.SamsungDeepBlue)
      )
      Text("All", fontSize = 11.sp, color = OneUi9Tokens.SecondaryText)
    }
    Spacer(Modifier.width(10.dp))
    Text(
      text = if (count == 0) "Select notes" else "$count selected",
      fontSize = 22.sp,
      fontWeight = FontWeight.Bold,
      color = OneUi9Tokens.PrimaryText,
      modifier = Modifier.weight(1f)
    )
    TextButton(onClick = actions.onClose) { Text("Cancel", color = OneUi9Tokens.SamsungDeepBlue) }
  }
}

/** Samsung Notes style floating bottom action bar shown while notes are selected. */
@Composable
fun OneUi9SelectionBottomBar(count: Int, actions: NoteSelectionActions, modifier: Modifier = Modifier) {
  var moreOpen by remember { mutableStateOf(false) }
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(28.dp),
    color = Color.White,
    shadowElevation = 10.dp
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      @Composable
      fun action(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
          val c = if (enabled) OneUi9Tokens.PrimaryText else Color(0xFFBDBDC2)
          MyScriptIcons.ByName(icon, color = c, size = 20.dp)
          Text(label, fontSize = 11.sp, color = c)
        }
      }
      action("export", "Export", count > 0, actions.onExport)
      action("move", "Move", count > 0, actions.onMoveToFolder)
      action("duplicate", "Duplicate", count > 0, actions.onDuplicate)
      action("delete", "Delete", count > 0, actions.onDelete)
      Box {
        action("more", "More", count > 0) { moreOpen = true }
        UiMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
          UiMenuItem(
            text = { Text("Rename") },
            enabled = count == 1,
            onClick = { moreOpen = false; actions.onRename() }
          )
          UiMenuItem(
            text = { Text("Add to / remove from favourites") },
            onClick = { moreOpen = false; actions.onToggleFavourite() }
          )
        }
      }
    }
  }
}

/** Small, down-sampled preview of an imported PDF page / image used on note thumbnails. */
@Composable
fun NoteBackgroundThumb(path: String, modifier: Modifier = Modifier) {
  // Multi-page PDF notes show their first page
  val first = BackgroundPages.firstPath(path) ?: return
  // Decoded in the background: decoding on the main thread made the notes list stutter
  val bmp = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, first) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        android.graphics.BitmapFactory.decodeFile(first, opts)?.asImageBitmap()
      }.getOrNull()
    }
  }.value
  if (bmp != null) {
    Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
  }
}

/** Selected-state overlay badge (red check circle) drawn on a note thumbnail. */
@Composable
fun SelectedCheckBadge(color: Color, modifier: Modifier = Modifier) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.size(26.dp).clip(CircleShape).background(color)
  ) { MyScriptIcons.Check(Color.White, 16.dp) }
}

@Composable
fun ExportFormatDialog(count: Int, onPick: (NoteExportFormat) -> Unit, onDismiss: () -> Unit) {
  UiAlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (count == 1) "Export note" else "Export $count notes") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NoteExportFormat.entries.forEach { f ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .clickable { onPick(f) }
              .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(f.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
          }
        }
        Text(
          ".gmnote keeps everything (the whole canvas, layers, pictures) and opens again in this app. SVG stays sharp at any size. PDF splits a big canvas over several pages.",
          fontSize = 12.sp,
          color = Color(0xFF757575)
        )
        if (count > 1) {
          Text(
            "Multiple notes: PDF creates pages for each note, the other formats are bundled in a .zip file.",
            fontSize = 12.sp,
            color = Color(0xFF757575)
          )
        }
        Text(
          "Samsung Notes (.sdocx) is not available: the format is proprietary.",
          fontSize = 12.sp,
          color = Color(0xFF9E9E9E)
        )
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

@Composable
fun MoveToFolderDialog(folders: List<NoteFolder>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
  UiAlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Move to folder") },
    text = {
      Column {
        Row(
          modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) { Text("No folder", fontSize = 15.sp) }
        folders.forEach { f ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(f.colorHex)))
            Spacer(Modifier.width(12.dp))
            Text(f.name, fontSize = 15.sp)
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

@Composable
fun RenameNoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
  var value by remember { mutableStateOf(initial) }
  UiAlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Rename note") },
    text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
    confirmButton = { TextButton(onClick = { onSave(value.trim().ifBlank { "Untitled" }) }) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

@Composable
fun ConfirmDeleteNotesDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  UiAlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (count == 1) "Move note to the bin?" else "Move $count notes to the bin?") },
    text = { Text("You can restore ${if (count == 1) "it" else "them"} from the Bin for ${NoteBin.KEEP_DAYS} days.") },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text("Move to bin", color = Color(0xFFD93025)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}
