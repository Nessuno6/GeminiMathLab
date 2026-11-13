package com.deang.geminimath

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Notes Home & Quick LaTeX workspace (both One UI 9 and Material 3 Expressive layouts),
 * extracted from [EditorApp] to keep the editor composable fast and maintainable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeNotesAndQuickLatexScreen(
  uiStyleMode: UiStyleMode,
  onUiStyleChange: (UiStyleMode) -> Unit,
  currentScreen: AppScreen,
  onScreenChange: (AppScreen) -> Unit,
  notesList: SnapshotStateList<MathNote>,
  foldersList: SnapshotStateList<NoteFolder>,
  activeHomeSection: String,
  onActiveHomeSectionChange: (String) -> Unit,
  currentFolderId: String?,
  selectedNoteIds: List<String>,
  selectionMode: Boolean,
  onEnterSelectionMode: () -> Unit,
  onToggleSelect: (MathNote) -> Unit,
  onVisibleNotesChanged: (List<String>) -> Unit,
  selectionActions: NoteSelectionActions,
  onSwitchToNote: (MathNote) -> Unit,
  onLaunchImport: () -> Unit,
  onUpdateNotes: (Collection<String>, (MathNote) -> MathNote) -> Unit,
  onOpenSettings: (SettingsSection?) -> Unit,
  onUseAsToolbar: () -> Unit,
  defaultPaper: PaperPattern,
  driveBackupEnabled: Boolean,
  binEntries: List<NoteBin.Entry>,
  onRefreshBin: () -> Unit,
  onSyncNow: () -> Unit,
  disableFingerWriting: Boolean,
  recognizeInk: suspend (List<InkStroke>) -> String?,
  onCopyLatex: (String) -> Unit,
  onOpenInkAsNote: (List<InkStroke>, String) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var navRailExpanded by remember { mutableStateOf(false) }

  if (uiStyleMode == UiStyleMode.ONEUI_9) {
    OneUi9NotesHomeScreen(
      notes = notesList,
      folders = foldersList,
      activeNavSection = if (currentScreen == AppScreen.QUICK_LATEX) "quick_latex" else activeHomeSection,
      onSelectNavSection = { sec ->
        if (sec == "quick_latex") {
          onScreenChange(AppScreen.QUICK_LATEX)
        } else {
          onActiveHomeSectionChange(sec)
          onScreenChange(AppScreen.HOME_NOTES)
        }
      },
      onOpenNote = { note -> onSwitchToNote(note) },
      selectedNoteIds = selectedNoteIds.toSet(),
      selectionMode = selectionMode,
      onToggleSelectNote = { note -> onToggleSelect(note) },
      onEnterSelectionMode = onEnterSelectionMode,
      onVisibleNotesChanged = onVisibleNotesChanged,
      selectionActions = selectionActions,
      onImport = onLaunchImport,
      onCreateNewNote = { folderId ->
        val newNote = NotesRepository.createBlankNote(folderId, defaultPaper)
        notesList.add(0, newNote)
        NotesRepository.saveNote(context, newNote)
        onSwitchToNote(newNote)
      },
      onCreateFolder = { name, colorHex ->
        val newFolder = NoteFolder(
          id = "folder_${UUID.randomUUID().toString().take(8)}",
          name = name,
          colorHex = colorHex
        )
        foldersList.add(newFolder)
        NotesRepository.saveFolders(context, foldersList.toList())
      },
      onEditFolder = { folder, newName, newColor ->
        val idx = foldersList.indexOfFirst { it.id == folder.id }
        if (idx >= 0) {
          foldersList[idx] = folder.copy(name = newName, colorHex = newColor)
          NotesRepository.saveFolders(context, foldersList.toList())
        }
      },
      onDeleteFolder = { folder ->
        foldersList.removeAll { it.id == folder.id }
        NotesRepository.saveFolders(context, foldersList.toList())
        // Notes inside a deleted folder are kept and moved to "No folder"
        onUpdateNotes(notesList.filter { it.folderId == folder.id }.map { it.id }) { it.copy(folderId = null) }
      },
      onOpenSettings = { section -> onOpenSettings(section) },
      onSwitchToM3E = { onUiStyleChange(UiStyleMode.M3E) },
      onUseAsToolbar = onUseAsToolbar,
      binCount = binEntries.size,
      binContent = { m ->
        BinPane(
          entries = binEntries,
          driveBackupOn = driveBackupEnabled,
          onRestore = { e ->
            val local = NoteBin.restoreLocal(context, e.id)
            if (local != null) {
              notesList.add(0, local)
              onRefreshBin()
              Toast.makeText(context, "Restored \"${local.title}\"", Toast.LENGTH_SHORT).show()
              if (driveBackupEnabled) onSyncNow()
            } else scope.launch {
              val note = GoogleDriveSyncManager.restoreFromCloud(context, e.id)
              if (note != null) {
                notesList.add(0, note)
                onRefreshBin()
                Toast.makeText(context, "Restored \"${note.title}\"", Toast.LENGTH_SHORT).show()
              } else {
                Toast.makeText(context, "Couldn't get it from Google Drive. Check you're online and signed in.", Toast.LENGTH_LONG).show()
              }
            }
          },
          onDeleteForever = { e ->
            NoteBin.deleteForever(context, e.id)
            onRefreshBin()
            scope.launch { GoogleDriveSyncManager.deletePurgedNow(context) }
          },
          onEmptyBin = {
            binEntries.toList().forEach { NoteBin.deleteForever(context, it.id) }
            onRefreshBin()
            scope.launch { GoogleDriveSyncManager.deletePurgedNow(context) }
          },
          modifier = m
        )
      },
      quickLatexContent = {
        QuickLatexPad(
          penOnly = disableFingerWriting,
          recognize = { recognizeInk(it) },
          onCopyLatex = onCopyLatex,
          onOpenAsNote = onOpenInkAsNote,
          modifier = Modifier.fillMaxSize()
        )
      }
    )
  } else {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.White)
        .statusBarsPadding()
        .navigationBarsPadding()
    ) {
      Column(
        modifier = Modifier
          .width(if (navRailExpanded) 92.dp else 72.dp)
          .fillMaxHeight()
          .background(Color.White)
          .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .size(42.dp)
              .clip(CircleShape)
              .semantics { contentDescription = "Show or hide labels" }
              .clickable { navRailExpanded = !navRailExpanded }
          ) {
            MyScriptIcons.HamburgerMenu(Color(0xFF1F1F1F), 20.dp)
          }

          Spacer(modifier = Modifier.height(8.dp))

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .semantics { contentDescription = "My notes" }
              .clickable { onScreenChange(AppScreen.HOME_NOTES) }
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(width = 52.dp, height = 42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(if (currentScreen == AppScreen.HOME_NOTES) Color(0xFFFCE8E6) else Color.Transparent)
            ) {
              MyScriptIcons.NoteDocIcon(
                color = if (currentScreen == AppScreen.HOME_NOTES) Color(0xFFD93025) else Color(0xFF1F1F1F),
                size = 20.dp
              )
            }
            if (navRailExpanded) {
              Spacer(modifier = Modifier.height(4.dp))
              Text("My notes", fontSize = 11.sp, color = Color(0xFF1F1F1F))
            }
          }

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onScreenChange(AppScreen.QUICK_LATEX) }
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(width = 52.dp, height = 42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(if (currentScreen == AppScreen.QUICK_LATEX) Color(0xFFFCE8E6) else Color.Transparent)
            ) {
              Text(
                text = "{ }",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (currentScreen == AppScreen.QUICK_LATEX) Color(0xFFD93025) else Color(0xFF1F1F1F)
              )
            }
            if (navRailExpanded) {
              Spacer(modifier = Modifier.height(4.dp))
              Text("Quick LaTeX", fontSize = 11.sp, color = Color(0xFF1F1F1F))
            }
          }
        }

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .semantics { contentDescription = "Settings" }
            .clickable(onClickLabel = "Settings") { onOpenSettings(null) }
        ) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
          ) {
            MyScriptIcons.SettingsGear(color = Color(0xFF1F1F1F), size = 21.dp)
          }
          if (navRailExpanded) {
            Text("Settings", fontSize = 11.sp, color = Color(0xFF1F1F1F))
          }
        }
      }

      if (currentScreen == AppScreen.HOME_NOTES) {
        LaunchedEffect(notesList.size) { onVisibleNotesChanged(notesList.map { it.id }) }
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .background(Color(0xFFF5F5F5))
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(if (selectionMode) 0.dp else 24.dp)
          ) {
            if (selectionMode) {
              MyScriptSelectionTopBar(count = selectedNoteIds.size, actions = selectionActions)
            } else {
              var m3eHomeMenu by remember { mutableStateOf(false) }
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
              ) {
                Box {
                  Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { m3eHomeMenu = true }
                  ) { MyScriptIcons.MoreVerticalDots(Color(0xFF1F1F1F), 20.dp) }
                  UiMenu(expanded = m3eHomeMenu, onDismissRequest = { m3eHomeMenu = false }) {
                    UiMenuItem(
                      text = { Text("Select") },
                      onClick = { m3eHomeMenu = false; onEnterSelectionMode() }
                    )
                    UiMenuItem(
                      text = { Text("Import (Samsung Notes, .iink, PDF, PNG)") },
                      onClick = { m3eHomeMenu = false; onLaunchImport() }
                    )
                    HorizontalDivider()
                    UiMenuItem(
                      text = { Text("Switch to One UI 9 design") },
                      onClick = { m3eHomeMenu = false; onUiStyleChange(UiStyleMode.ONEUI_9) }
                    )
                  }
                }
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 140.dp),
              horizontalArrangement = Arrangement.spacedBy(24.dp),
              verticalArrangement = Arrangement.spacedBy(24.dp),
              modifier = Modifier
                .weight(1f)
                .padding(horizontal = if (selectionMode) 24.dp else 0.dp)
            ) {
              items(notesList, key = { it.id }) { note ->
                val df = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.ENGLISH) }
                val isSelected = note.id in selectedNoteIds
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  modifier = Modifier.combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect(note) else onSwitchToNote(note) },
                    onLongClick = { onToggleSelect(note) }
                  )
                ) {
                  Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) Color(0xFFFCE8E6) else Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(108.dp)
                  ) {
                    Box(Modifier.fillMaxSize()) {
                      note.backgroundImagePath?.let {
                        NoteBackgroundThumb(it, Modifier.fillMaxSize().padding(8.dp))
                      }
                      NoteInkThumb(note, Modifier.fillMaxSize().padding(12.dp))
                      if (isSelected) {
                        SelectedCheckBadge(
                          color = Color(0xFFEA5244),
                          modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        )
                      }
                    }
                  }

                  Spacer(modifier = Modifier.height(8.dp))

                  Text(
                    text = note.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF1F1F1F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = df.format(Date(note.updatedAt)),
                    fontSize = 11.sp,
                    color = Color(0xFF8E8E8E)
                  )
                }
              }
            }
          }

          if (!selectionMode) Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFEA5244),
            shadowElevation = 6.dp,
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(end = 24.dp, bottom = 24.dp)
              .size(54.dp)
              .semantics { contentDescription = "New note" }
              .clickable {
                val newNote = NotesRepository.createBlankNote(currentFolderId, defaultPaper)
                notesList.add(0, newNote)
                NotesRepository.saveNote(context, newNote)
                onSwitchToNote(newNote)
              }
          ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              MyScriptIcons.Plus(color = Color.White, size = 24.dp)
            }
          }
        }
      } else {
        QuickLatexPad(
          penOnly = disableFingerWriting,
          recognize = { recognizeInk(it) },
          onCopyLatex = onCopyLatex,
          onOpenAsNote = onOpenInkAsNote,
          modifier = Modifier.weight(1f).fillMaxHeight()
        )
      }
    }
  }
}
