package com.deang.geminimath

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Bin: deleted notes, kept for 30 days. Each can be restored or deleted forever (from this
 * device and from Google Drive); "Empty bin" deletes them all forever.
 */
@Composable
fun BinPane(
  entries: List<NoteBin.Entry>,
  driveBackupOn: Boolean,
  onRestore: (NoteBin.Entry) -> Unit,
  onDeleteForever: (NoteBin.Entry) -> Unit,
  onEmptyBin: () -> Unit,
  modifier: Modifier = Modifier
) {
  val df = remember { SimpleDateFormat("d MMM", Locale.ENGLISH) }
  var confirm by remember { mutableStateOf<NoteBin.Entry?>(null) }
  var confirmEmpty by remember { mutableStateOf(false) }

  Column(modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Bin", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = OneUi9Tokens.PrimaryText, modifier = Modifier.weight(1f))
      if (entries.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) {
        Text("Empty bin", color = Color(0xFFD93025), fontSize = 15.sp)
      }
    }
    Text(
      if (driveBackupOn) "Deleted notes stay here for ${NoteBin.KEEP_DAYS} days, then they're removed. Their copy on Google Drive is kept until then, so you can restore them on any of your devices."
      else "Deleted notes stay here for ${NoteBin.KEEP_DAYS} days, then they're removed.",
      fontSize = 14.sp, color = OneUi9Tokens.SecondaryText, lineHeight = 19.sp,
      modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
    )
    if (entries.isEmpty()) {
      Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          MyScriptIcons.ByName("delete", OneUi9Tokens.SecondaryText, 40.dp)
          Spacer(Modifier.height(12.dp))
          Text("The bin is empty", fontSize = 16.sp, color = OneUi9Tokens.SecondaryText)
        }
      }
    } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(entries, key = { it.id }) { e ->
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFF3F3F6), modifier = Modifier.fillMaxWidth()) {
          Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(e.title.ifBlank { "Untitled" }, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = OneUi9Tokens.PrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
              val days = e.daysLeft()
              Text(
                "Deleted ${df.format(Date(e.deletedAt))} · " + (if (days <= 1) "removed tomorrow" else "$days days left") +
                  if (!e.onDevice) " · on Google Drive" else "",
                fontSize = 13.sp, color = OneUi9Tokens.SecondaryText
              )
            }
            TextButton(onClick = { onRestore(e) }) { Text("Restore", color = OneUi9Tokens.SamsungBlue, fontSize = 14.sp) }
            Box(
              Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { confirm = e }
                .semantics { contentDescription = "Delete forever" },
              contentAlignment = Alignment.Center
            ) { MyScriptIcons.ByName("delete", Color(0xFFD93025), 18.dp) }
          }
        }
      }
    }
  }

  confirm?.let { e ->
    UiAlertDialog(
      onDismissRequest = { confirm = null },
      title = { Text("Delete forever?") },
      text = { Text("\"${e.title.ifBlank { "Untitled" }}\" will be deleted from this device and from Google Drive. This can't be undone.") },
      confirmButton = { TextButton(onClick = { confirm = null; onDeleteForever(e) }) { Text("Delete forever", color = Color(0xFFD93025)) } },
      dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
    )
  }
  if (confirmEmpty) {
    UiAlertDialog(
      onDismissRequest = { confirmEmpty = false },
      title = { Text("Empty the bin?") },
      text = { Text("${entries.size} ${if (entries.size == 1) "note" else "notes"} will be deleted from this device and from Google Drive. This can't be undone.") },
      confirmButton = { TextButton(onClick = { confirmEmpty = false; onEmptyBin() }) { Text("Empty bin", color = Color(0xFFD93025)) } },
      dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
    )
  }
}
