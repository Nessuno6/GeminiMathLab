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


// The app's screens: notes list, editor (page, toolbars, panels, menus) and settings.

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun MyScriptExactReplicaApp() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // Notes & Folders state
  val notesList = remember {
    mutableStateListOf<MathNote>().apply { addAll(NotesRepository.loadAllNotes(context)) }
  }
  val foldersList = remember {
    mutableStateListOf<NoteFolder>().apply { addAll(NotesRepository.loadFolders(context)) }
  }
  var currentNoteId by remember { mutableStateOf(notesList.firstOrNull()?.id ?: "note_blank") }
  var currentScreen by remember { mutableStateOf(AppScreen.EDITOR) }
  var navRailExpanded by remember { mutableStateOf(false) }

  // App-wide settings (saved as they change)
  val settings = remember { AppSettings(context) }
  val uiStyleMode = settings.uiStyle
  val disableFingerWriting = settings.penOnly
  SideEffect {
    SpenHoverBus.buttonShortcutsEnabled = settings.penButtonShortcuts
    CurrentUiStyle.mode = uiStyleMode
  }

  // Settings navigation: which section is open and where Back returns to
  var settingsSection by remember { mutableStateOf<SettingsSection?>(null) }
  var settingsReturnScreen by remember { mutableStateOf(AppScreen.HOME_NOTES) }
  fun openSettings(section: SettingsSection? = null) {
    if (currentScreen != AppScreen.SETTINGS) settingsReturnScreen = currentScreen
    settingsSection = section
    currentScreen = AppScreen.SETTINGS
  }

  // Active note properties
  val activeNote = notesList.find { it.id == currentNoteId } ?: notesList.first()
  var noteTitle by remember(currentNoteId) { mutableStateOf(activeNote.title) }
  var paperPattern by remember(currentNoteId) { mutableStateOf(activeNote.paperPattern) }
  var graphColor by remember(currentNoteId) { mutableStateOf(activeNote.graphColor) }
  var showInlinePlotter by remember(currentNoteId) { mutableStateOf(false) }
  var currentFolderId by remember(currentNoteId) { mutableStateOf(activeNote.folderId) }
  var isNoteFavourite by remember(currentNoteId) { mutableStateOf(activeNote.isFavourite) }

  // Strokes & Undo/Redo stacks
  val strokes = remember { mutableStateListOf<InkStroke>().apply { addAll(activeNote.strokes) } }
  // Undo/redo by snapshots: covers new strokes, erasing, moving, clean up, shapes and clearing
  val history = remember { InkHistory() }
  // Laser pointer: its own trail and layer, redrawn every frame only while it's showing
  val laserTrail = remember { LaserTrail() }
  var laserFrame by remember { mutableStateOf(0L) }
  var showLaserSettings by remember { mutableStateOf(false) }
  // AI assistant (robot button): the conversation about the open note
  var showAssistant by remember { mutableStateOf(false) }
  val aiTurns = remember(currentNoteId) { mutableStateListOf<AiTurn>() }
  var aiBusy by remember { mutableStateOf(false) }
  LaunchedEffect(laserTrail) {
    snapshotFlow { laserTrail.tick }.collect {
      while (laserTrail.prune(android.os.SystemClock.uptimeMillis())) {
        withFrameMillis { laserFrame = it }
      }
      laserFrame++
    }
  }
  var currentPoints by remember { mutableStateOf<List<TimedPoint>>(emptyList()) }

  // Left vertical pill toolbar state
  var selectedTool by remember { mutableStateOf(MyScriptTool.PEN) }
  var selectedThickness by remember { mutableStateOf(8.5f) }
  var selectedPenColor by remember { mutableStateOf(0xFF1A1A1AL) }
  var selectedPenType by remember { mutableStateOf(PenType.PEN) }
  var selectedLineStyle by remember { mutableStateOf(LineStyle.SOLID) }
  // Samsung pen settings panel (tap the pen tool while it's selected)
  var showPenSettings by remember { mutableStateOf(false) }
  // Highlighter, eraser and lasso panels (tap the selected tool again, as in Samsung Notes)
  var showHighlighterSettings by remember { mutableStateOf(false) }
  var showEraserSettings by remember { mutableStateOf(false) }
  var showLassoSettings by remember { mutableStateOf(false) }
  var showTapeSettings by remember { mutableStateOf(false) }
  /** Where the pen is while the stabiliser makes the ink trail it (the string is drawn between them). */
  var stabilizerPen by remember { mutableStateOf<Offset?>(null) }
  // Samsung colour menu for a toolbar colour slot, and the eyedropper waiting for a tap on the page
  var editingColorSlot by remember { mutableStateOf<Int?>(null) }
  var eyedropperSlot by remember { mutableStateOf<Int?>(null) }
  // Tape strips peeled back in this session (by identity; tape is covered again on reopening)
  val revealedTape = remember(currentNoteId) { mutableStateListOf<InkStroke>() }
  /** Highlighter ink: the pen colour, see-through; yellow when the pen is black (Samsung's default). */
  // The highlighter's own colour and size (its panel), see-through
  fun highlighterColor(): Long = (settings.highlighterColor and 0x00FFFFFFL) or 0x66000000L
  fun highlighterWidth(): Float = highlighterSliderToWidth(settings.highlighterSize)
  var showColorPickerPopup by remember { mutableStateOf(false) }

  // Lasso selection: selected strokes are tracked by identity, so edits elsewhere can't shift them
  val lassoSelection = remember { mutableStateListOf<InkStroke>() }
  var lassoDragLast by remember { mutableStateOf<Offset?>(null) }
  /** A selection handle (resize or rotate) is being dragged: the menu hides meanwhile. */
  var lassoTransforming by remember { mutableStateOf(false) }
  /** How far the selection has been turned: its box turns with it, as in Samsung Notes. */
  var lassoAngle by remember { mutableStateOf(0f) }
  /** Change style is open: the selection as it was before, to put back on Cancel. */
  var styleOriginal by remember { mutableStateOf<List<InkStroke>?>(null) }
  var styleStep by remember { mutableStateOf(30) }
  var styleColor by remember { mutableStateOf<Long?>(null) }
  var pasteAnchor by remember { mutableStateOf<Offset?>(null) }
  var inkClipboard by remember { mutableStateOf<List<InkStroke>>(emptyList()) }
  // Strokes the last solve was run on (highlighted while the steps card is open)
  var solvedStrokes by remember { mutableStateOf<List<InkStroke>>(emptyList()) }
  var autoSolveJob by remember { mutableStateOf<Job?>(null) }
  fun clearLasso() {
    lassoSelection.clear()
    lassoDragLast = null
    pasteAnchor = null
    lassoAngle = 0f
  }
  fun liveSelection(): List<InkStroke> = strokes.filter { s -> lassoSelection.any { it === s } }
  LaunchedEffect(selectedTool) { if (selectedTool != MyScriptTool.LASSO) clearLasso() }

  var showStepByStepCard by remember { mutableStateOf(false) }
  var areAllStepsExpanded by remember { mutableStateOf(false) }
  var isFinalAnswerExpanded by remember { mutableStateOf(true) }

  // Dialogs & Menus
  var showPageStyleDialog by remember { mutableStateOf(false) }
  var showRenameDialog by remember { mutableStateOf(false) }
  var showMoreMenu by remember { mutableStateOf(false) }

  // Engines: Pix2Text-MFR 1.5 (ONNX) + SymbolicStepEngine + Optional Gemini Nano
  val onnxOcrEngine = remember { OnnxMathOcrEngine(context) }
  var onnxReady by remember { mutableStateOf(false) }
  var generativeModel by remember { mutableStateOf<GenerativeModel?>(null) }
  var nanoStatus by remember { mutableStateOf("Checking…") }

  var recognizedLatex by remember(currentNoteId) { mutableStateOf(activeNote.recognizedLatex) }
  var isRunningOcr by remember { mutableStateOf(false) }
  var symbolicSolution by remember {
    mutableStateOf(SymbolicStepEngine.solveFromLatex(activeNote.recognizedLatex))
  }

  // Optional Gemini Nano Tutor state
  var isRunningNano by remember { mutableStateOf(false) }
  var nanoExplanation by remember { mutableStateOf<String?>(null) }
  var activeNanoJob by remember { mutableStateOf<Job?>(null) }

  // Google account & Drive backup
  fun currentAccount(): AccountInfo? = GoogleDriveSyncManager.getSignedInAccount(context)?.let { acct ->
    acct.email?.let { AccountInfo(acct.displayName, it) }
  }
  var account by remember { mutableStateOf(currentAccount()) }
  var isSyncingDrive by remember { mutableStateOf(false) }

  // The bin (deleted notes, 30 days)
  val binEntries = remember { mutableStateListOf<NoteBin.Entry>().apply { addAll(NoteBin.list(context)) } }
  fun refreshBin() { binEntries.clear(); binEntries.addAll(NoteBin.list(context)) }

  fun syncNow() {
    if (isSyncingDrive) return
    scope.launch {
      isSyncingDrive = true
      val res = GoogleDriveSyncManager.syncNotesWithDrive(context)
      settings.lastSyncStatus = res.statusMessage
      if (res.success) {
        notesList.clear()
        notesList.addAll(NotesRepository.loadAllNotes(context))
        refreshBin()
      }
      isSyncingDrive = false
    }
  }

  // With backup on: sync when the app opens or comes back, and after leaving a note (at most once a minute)
  val lastAutoSync = remember { longArrayOf(0L) }
  fun autoSync() {
    if (!settings.driveBackup || account == null) return
    val now = System.currentTimeMillis()
    if (now - lastAutoSync[0] < 60_000) return
    lastAutoSync[0] = now
    syncNow()
  }
  androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
    autoSync()
    onPauseOrDispose { }
  }
  LaunchedEffect(currentScreen) {
    // Give the note's background save a moment to reach the disk first
    if (currentScreen == AppScreen.HOME_NOTES) { delay(1500); autoSync() }
  }

  val googleSignInLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    val signedIn = runCatching { GoogleSignIn.getSignedInAccountFromIntent(result.data).result }.getOrNull()
    account = currentAccount()
    if (signedIn != null && settings.driveBackup) syncNow()
    if (signedIn == null && account == null) {
      settings.driveBackup = false
      Toast.makeText(context, "Google sign-in was cancelled", Toast.LENGTH_SHORT).show()
    }
  }
  fun signIn() = googleSignInLauncher.launch(GoogleDriveSyncManager.getSignInClient(context).signInIntent)

  // ===================== Long-press selection mode + Import / Export =====================
  val selectedNoteIds = remember { mutableStateListOf<String>() }
  var selectionMode by remember { mutableStateOf(false) }
  var visibleNoteIds by remember { mutableStateOf<List<String>>(emptyList()) }
  var showExportDialog by remember { mutableStateOf(false) }
  var showMoveDialog by remember { mutableStateOf(false) }
  var showRenameSelectedDialog by remember { mutableStateOf(false) }
  var showDeleteSelectedDialog by remember { mutableStateOf(false) }
  var pendingExport by remember { mutableStateOf<Pair<List<MathNote>, NoteExportFormat>?>(null) }
  var activeHomeSection by remember { mutableStateOf("notes") }
  var noteBackgroundPath by remember(currentNoteId) { mutableStateOf(activeNote.backgroundImagePath) }
  var paperColor by remember(currentNoteId) { mutableStateOf(activeNote.paperColor) }
  // Layers: drawn bottom to top; you draw, erase and lasso on the active one
  var noteLayers by remember(currentNoteId) { mutableStateOf(activeNote.layers) }
  var activeLayer by remember(currentNoteId) { mutableStateOf(activeNote.layers.last().id) }
  fun layerVisible(id: Int) = noteLayers.firstOrNull { it.id == id }?.visible ?: true
  var showLayers by remember { mutableStateOf(false) }
  // Moving the toolbar: the finger's window position while its handle is dragged
  var toolbarDragging by remember { mutableStateOf(false) }
  var toolbarFinger by remember { mutableStateOf(Offset.Zero) }
  var showFillSettings by remember { mutableStateOf(false) }
  val canvasSizePx = remember { floatArrayOf(1f, 1f) }
  // Infinite canvas: where the page is on screen. Each note opens at 100%, top-left.
  var canvasView by remember(currentNoteId) { mutableStateOf(CanvasView()) }
  // "Lock canvas": no panning or zooming
  var canvasLocked by remember { mutableStateOf(false) }
  /** Bumped when a note picture finishes loading, so the canvas redraws. */
  var pictureTick by remember { mutableStateOf(0) }
  var showCalculator by remember { mutableStateOf(false) }
  /** The picture picked with a long press (move, resize, crop, copy, cut, delete, share). */
  var selectedPicture by remember(currentNoteId) { mutableStateOf<InkStroke?>(null) }

  fun exitSelection() {
    selectionMode = false
    selectedNoteIds.clear()
  }

  fun toggleSelect(note: MathNote) {
    selectionMode = true
    if (note.id in selectedNoteIds) selectedNoteIds.remove(note.id) else selectedNoteIds.add(note.id)
  }

  fun selectedNotes(): List<MathNote> = notesList.filter { it.id in selectedNoteIds }

  val exportLauncher = rememberLauncherForActivityResult(CreateDocumentWithMime()) { uri ->
    val job = pendingExport
    pendingExport = null
    if (uri != null && job != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) {
          runCatching { NoteImportExport.exportToUri(context, job.first, job.second, uri) }
        }
        Toast.makeText(
          context,
          ok.fold({ "Exported ${job.first.size} note(s) as ${job.second.extension.uppercase()}" }, { "Export failed: ${it.message}" }),
          Toast.LENGTH_LONG
        ).show()
        if (ok.isSuccess) exitSelection()
      }
    }
  }

  val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      scope.launch {
        val targetFolder = activeHomeSection.takeIf { it.startsWith("folder_") }
        val res = withContext(Dispatchers.IO) {
          runCatching {
            NoteImportExport.importFromUri(context, uri, targetFolder).onEach { NotesRepository.saveNote(context, it) }
          }
        }
        res.onSuccess { imported ->
          notesList.addAll(0, imported)
          Toast.makeText(context, "Imported ${imported.size} note(s)", Toast.LENGTH_SHORT).show()
        }.onFailure {
          Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  fun launchImport() {
    importLauncher.launch(
      arrayOf("application/pdf", "image/png", "image/jpeg", "image/webp", "application/zip", "application/octet-stream", "application/json", "*/*")
    )
  }

  fun persistCurrentNote() {
    // With all ink gone (undo, erase, cut), any recognised text is stale
    if (strokes.isEmpty() && recognizedLatex.isNotEmpty()) recognizedLatex = ""
    val updated = MathNote(
      id = currentNoteId,
      title = noteTitle,
      updatedAt = System.currentTimeMillis(),
      folderId = currentFolderId,
      isFavourite = isNoteFavourite,
      backgroundImagePath = noteBackgroundPath,
      paperColor = paperColor,
      layers = noteLayers,
      paperPattern = paperPattern,
      graphColor = graphColor,
      showInlinePlotter = showInlinePlotter,
      recognizedLatex = recognizedLatex,
      strokes = strokes.toList()
    )
    // In the background: saving after every stroke must not make drawing stutter
    NotesRepository.saveNoteAsync(context, updated)
    val idx = notesList.indexOfFirst { it.id == currentNoteId }
    if (idx >= 0) notesList[idx] = updated else notesList.add(0, updated)
    NoteHandoff.openNote = updated
  }

  fun switchToNote(note: MathNote, persistPrevious: Boolean = true, openEditor: Boolean = true) {
    if (persistPrevious) persistCurrentNote()
    currentNoteId = note.id
    noteTitle = note.title
    currentFolderId = note.folderId
    isNoteFavourite = note.isFavourite
    noteBackgroundPath = note.backgroundImagePath
    paperColor = note.paperColor
    noteLayers = note.layers
    activeLayer = note.layers.last().id
    paperPattern = note.paperPattern
    graphColor = note.graphColor
    showInlinePlotter = note.showInlinePlotter
    recognizedLatex = note.recognizedLatex
    strokes.clear()
    strokes.addAll(note.strokes)
    history.clear()
    symbolicSolution = SymbolicStepEngine.solveFromLatex(note.recognizedLatex)
    nanoExplanation = null
    showStepByStepCard = false
    clearLasso()
    SpenHoverBus.radialMenuVisible = false
    if (openEditor) currentScreen = AppScreen.EDITOR
  }

  // Page style › Background: a picture or the first page of a PDF under this note's ink
  val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      scope.launch {
        val res = withContext(Dispatchers.IO) { runCatching { NoteImportExport.backgroundFromUri(context, uri) } }
        res.onSuccess { path ->
          noteBackgroundPath = path
          persistCurrentNote()
        }.onFailure { Toast.makeText(context, "Couldn't use that file: ${it.message}", Toast.LENGTH_LONG).show() }
      }
    }
  }

  // System Back: note → notes list, Quick LaTeX → notes list, selection → normal list.
  // (Settings handles its own Back.) On the notes list, Back leaves the app as usual.
  androidx.activity.compose.BackHandler(
    enabled = currentScreen == AppScreen.EDITOR || currentScreen == AppScreen.QUICK_LATEX ||
      (currentScreen == AppScreen.HOME_NOTES && selectionMode)
  ) {
    when {
      currentScreen == AppScreen.EDITOR -> {
        persistCurrentNote()
        clearLasso()
        currentScreen = AppScreen.HOME_NOTES
      }
      currentScreen == AppScreen.QUICK_LATEX -> currentScreen = AppScreen.HOME_NOTES
      selectionMode -> exitSelection()
    }
  }

  // Android 17 Handoff: while a note is open in the editor, offer it to the user's other devices
  val hostActivity = context as? MainActivity
  LaunchedEffect(currentScreen, currentNoteId) {
    val editing = currentScreen == AppScreen.EDITOR
    NoteHandoff.openNote = if (editing) notesList.find { it.id == currentNoteId } else null
    hostActivity?.setNoteHandoffEnabled(editing)
  }
  SideEffect {
    NoteHandoff.onHandoffStarted = { if (currentScreen == AppScreen.EDITOR) persistCurrentNote() }
  }
  // A note handed off from another device (already saved by NoteHandoff.receive): open it
  val handedOffNoteId = NoteHandoff.pendingNoteId
  LaunchedEffect(handedOffNoteId) {
    val id = handedOffNoteId ?: return@LaunchedEffect
    NoteHandoff.pendingNoteId = null
    notesList.clear()
    notesList.addAll(NotesRepository.loadAllNotes(context))
    // Ink is saved after every stroke, so the note open here is already on disk
    notesList.find { it.id == id }?.let { switchToNote(it, persistPrevious = false) }
  }
  val handoffMessage = NoteHandoff.pendingMessage
  LaunchedEffect(handoffMessage) {
    handoffMessage ?: return@LaunchedEffect
    android.widget.Toast.makeText(context, handoffMessage, android.widget.Toast.LENGTH_LONG).show()
    NoteHandoff.pendingMessage = null
  }

  val selectionActions = NoteSelectionActions(
    onClose = { exitSelection() },
    onDelete = { if (selectedNoteIds.isNotEmpty()) showDeleteSelectedDialog = true },
    onDuplicate = {
      val copies = selectedNotes().map { n ->
        n.copy(
          id = "note_${UUID.randomUUID().toString().take(8)}",
          title = "${n.title} (copy)",
          updatedAt = System.currentTimeMillis()
        )
      }
      copies.forEach { NotesRepository.saveNote(context, it) }
      notesList.addAll(0, copies)
      exitSelection()
      Toast.makeText(context, "Duplicated ${copies.size} note(s)", Toast.LENGTH_SHORT).show()
    },
    onExport = { if (selectedNoteIds.isNotEmpty()) showExportDialog = true },
    onSelectAll = {
      selectionMode = true
      val ids = visibleNoteIds.ifEmpty { notesList.map { it.id } }
      ids.forEach { if (it !in selectedNoteIds) selectedNoteIds.add(it) }
    },
    onDeselectAll = { selectedNoteIds.clear() },
    onRename = { if (selectedNoteIds.size == 1) showRenameSelectedDialog = true },
    onMoveToFolder = { if (selectedNoteIds.isNotEmpty()) showMoveDialog = true },
    onToggleFavourite = {
      val sel = selectedNotes()
      val makeFav = sel.any { !it.isFavourite }
      sel.forEach { n ->
        val upd = n.copy(isFavourite = makeFav)
        NotesRepository.saveNote(context, upd)
        val i = notesList.indexOfFirst { it.id == n.id }
        if (i >= 0) notesList[i] = upd
        if (n.id == currentNoteId) isNoteFavourite = makeFav
      }
      exitSelection()
    }
  )

  fun updateNotes(ids: Collection<String>, transform: (MathNote) -> MathNote) {
    ids.toList().forEach { id ->
      val i = notesList.indexOfFirst { it.id == id }
      if (i >= 0) {
        val upd = transform(notesList[i])
        notesList[i] = upd
        NotesRepository.saveNote(context, upd)
        if (id == currentNoteId) {
          noteTitle = upd.title
          currentFolderId = upd.folderId
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    launch {
      runCatching {
        withContext(Dispatchers.IO) { onnxOcrEngine.init() }
        onnxReady = true
      }
    }
    launch {
      runCatching {
        val model = Generation.getClient()
        when (model.checkStatus()) {
          FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
            nanoStatus = "Downloading…"
            model.download().collect { status ->
              if (status == DownloadStatus.DownloadCompleted) {
                generativeModel = model
                nanoStatus = "Ready"
              }
            }
          }
          FeatureStatus.AVAILABLE -> {
            generativeModel = model
            nanoStatus = "Ready"
          }
          else -> nanoStatus = "Not available on this device"
        }
      }.onFailure { nanoStatus = "Not available on this device" }
    }
  }

  // Debug self-test (adb: am start … --ez ocr_selftest true): OCR every note's ink with both image
  // preprocessings and log the results, to compare recognition quality on real handwriting
  // Debug solver self-test (adb: am start … --ez solver_selftest true), see SolverSelfTest
  LaunchedEffect(onnxReady) {
    if (!onnxReady || !SolverSelfTest.requested) return@LaunchedEffect
    SolverSelfTest.requested = false
    withContext(Dispatchers.Default) {
      SolverSelfTest.run(
        ocr = { bmp -> onnxOcrEngine.recognizeFormula(bmp).latex },
        notes = notesList.toList().map { it.title to it.strokes }
      )
    }
  }
  LaunchedEffect(onnxReady) {
    if (!onnxReady || !OcrSelfTest.requested) return@LaunchedEffect
    OcrSelfTest.requested = false
    withContext(Dispatchers.Default) {
      val expected = mapOf(
        1 to "x^2-5x+6=0", 2 to "\\int_0^1 \\frac{\\ln(x)}{\\sqrt{x}} dx", 3 to "\\frac{2x+3}{x-1}=5",
        4 to "\\int_0^\\pi \\sin(x) dx", 5 to "\\int e^x \\cdot x dx"
      )
      val cases = expected.map { (id, want) -> "sample $id (want $want)" to HandwritingSimulator.generateSampleStrokes(id) } +
        notesList.toList().map { it.title to it.strokes.filter { s -> !s.isHighlighter && !s.isTape } }
      for ((title, ink) in cases) {
        if (ink.isEmpty()) continue
        val note = object { val title = title }
        val bmp = renderStrokesToBitmap(ink)
        for (stretch in listOf(false, true)) {
          val r = runCatching { onnxOcrEngine.recognizeFormula(bmp, stretch = stretch) }.getOrNull()
          android.util.Log.i("OcrSelfTest", "${note.title} | ${if (stretch) "stretch" else "letterbox"} | ${r?.latex} | ${r?.encoderMs}+${r?.decoderMs} ms")
        }
      }
      android.util.Log.i("OcrSelfTest", "done")
    }
  }

  /**
   * Recognises and solves the ink. Uses the lasso selection when there is one (or [only] when
   * given), otherwise the whole page. With [openOnlyIfEquals] the steps card opens only when
   * the recognised expression ends in "=" (auto-solve).
   */
  fun runOcrAndSolve(
    openStepsCard: Boolean = false,
    only: List<InkStroke>? = null,
    openOnlyIfEquals: Boolean = false
  ) {
    activeNanoJob?.cancel()
    isRunningNano = false
    nanoExplanation = null
    // Highlighter and tape aren't writing: leave them out of recognition
    val chosen = only ?: liveSelection()
    val target = chosen.ifEmpty { strokes.toList() }.filter { it.isInk }
      .let { if (chosen.isEmpty()) SolveTarget.pick(it) else it }
    solvedStrokes = target
    scope.launch {
      var expr = recognizedLatex.trim()
      if (target.isNotEmpty() && onnxReady) {
        isRunningOcr = true
        try {
          val bmp = withContext(Dispatchers.Default) { renderStrokesToBitmap(target) }
          val ocrRes = withContext(Dispatchers.Default) { onnxOcrEngine.recognizeFormula(bmp) }
          android.util.Log.i("Solve", "OCR ${target.size} strokes -> '${ocrRes.latex}'")
          if (ocrRes.latex.isNotBlank()) {
            recognizedLatex = ocrRes.latex
            expr = ocrRes.latex
          }
        } catch (t: Throwable) {
          android.util.Log.w("Solve", "OCR failed", t)
        } finally {
          isRunningOcr = false
        }
      } else {
        android.util.Log.i("Solve", "no OCR: ${target.size} strokes, onnxReady=$onnxReady")
      }
      if (openOnlyIfEquals && !Regex("""=\s*\??\s*$""").containsMatchIn(expr)) return@launch
      val solved = withContext(Dispatchers.Default) { runCatching { SymbolicStepEngine.solveFromLatex(expr) } }
      solved.exceptionOrNull()?.let { android.util.Log.w("Solve", "solver threw on '$expr'", it) }
      symbolicSolution = solved.getOrNull() ?: SymbolicStepEngine.solveFromLatex("")
      android.util.Log.i("Solve", "'$expr' -> ${symbolicSolution?.finalAnswer}")
      if (openStepsCard || openOnlyIfEquals) showStepByStepCard = true
      persistCurrentNote()
    }
  }

  // Quick LaTeX pad helpers (the pad keeps its own ink, separate from the open note)
  suspend fun recognizeInk(list: List<InkStroke>): String? {
    if (!onnxReady || list.isEmpty()) return null
    return withContext(Dispatchers.Default) {
      runCatching { onnxOcrEngine.recognizeFormula(renderStrokesToBitmap(list)).latex }.getOrNull()
    }
  }
  fun copyLatex(latex: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("LaTeX", latex))
    Toast.makeText(context, "LaTeX copied", Toast.LENGTH_SHORT).show()
  }
  fun openInkAsNote(ink: List<InkStroke>, latex: String) {
    val note = NotesRepository.createBlankNote(null, settings.defaultPaper).copy(strokes = ink, recognizedLatex = latex)
    notesList.add(0, note)
    NotesRepository.saveNote(context, note)
    switchToNote(note)
  }

  // ===================== Pictures =====================
  /**
   * Places a picture in the note, [widthPage] wide (page units). With [topLeft] it goes there;
   * otherwise it's centred on what's on screen. Returns the new stroke.
   */
  fun insertPicture(file: java.io.File, bmp: Bitmap, widthPage: Float? = null, topLeft: Offset? = null): InkStroke {
    val vis = canvasView.visiblePage(canvasSizePx[0], canvasSizePx[1])
    val w = widthPage ?: (vis.width * 0.5f)
    val h = w * bmp.height / bmp.width.coerceAtLeast(1)
    // Without a place: below everything on the page (so it never covers writing), lined up with it
    val content = LassoTools.bounds(strokes.filter { layerVisible(it.layer) })
    val tl = topLeft ?: content?.let { Offset(maxOf(it.left, vis.left + 24f), it.bottom + 60f) } ?: Offset(vis.center.x - w / 2, vis.center.y - h / 2)
    // Scroll so the new picture is on screen
    val picScreen = canvasView.toScreen(androidx.compose.ui.geometry.Rect(tl, androidx.compose.ui.geometry.Size(w, h)))
    if (picScreen.bottom > canvasSizePx[1] || picScreen.top < 0f) {
      canvasView = canvasView.panBy(0f, canvasSizePx[1] * 0.15f - picScreen.top)
    }
    val pic = NotePicture(file.absolutePath).also { it.image = bmp.asImageBitmap() }
    val now = System.currentTimeMillis()
    // Settings → "Insert pictures on layer 1": the note's second layer, added if there's only one
    val layer = if (!settings.picturesOnLayer2) activeLayer else noteLayers.getOrNull(1)?.id ?: run {
      val id = (noteLayers.maxOfOrNull { it.id } ?: 0) + 1
      noteLayers = noteLayers + NoteLayer(id, "Layer 1")
      id
    }
    val s = InkStroke(listOf(TimedPoint(tl.x, tl.y, now), TimedPoint(tl.x + w, tl.y + h, now)), width = 0f, layer = layer, picture = pic)
    history.checkpoint(strokes)
    strokes.add(s)
    persistCurrentNote()
    return s
  }

  /**
   * Lasso → Graph: reads the selected function and adds a picture of its graph beside it.
   * The JPG is also saved to the Gallery (Pictures/Gemini Math).
   */
  fun graphSelection(selection: List<InkStroke>) {
    val ink = selection.filter { it.isInk }
    if (ink.isEmpty() || !onnxReady) {
      Toast.makeText(context, if (ink.isEmpty()) "Select a handwritten function to graph" else "Handwriting recognition is still loading", Toast.LENGTH_SHORT).show()
      return
    }
    scope.launch {
      isRunningOcr = true
      val result = withContext(Dispatchers.Default) {
        runCatching {
          val latex = onnxOcrEngine.recognizeFormula(renderStrokesToBitmap(ink)).latex
          val sol = SymbolicStepEngine.solveFromLatex(latex)
          val expr = sol.plottableExpr ?: return@runCatching null
          val win = GraphRender.autoWindow(expr)
          val bmp = GraphRender.bitmap(listOf(GraphCurve(expr, GraphRender.CurveColors[1])), win, dark = paperColor.isDark)
          Triple(sol.normalizedMath, bmp, NotePictures.save(context, bmp))
        }.getOrNull()
      }
      isRunningOcr = false
      if (result == null) {
        Toast.makeText(context, "That isn't a function of x I can graph. Try writing it as y = …", Toast.LENGTH_LONG).show()
        return@launch
      }
      val (label, bmp, file) = result
      val box = LassoTools.bounds(ink)
      // As wide as the formula (at least 640), below it
      val width = (box?.width ?: 640f).coerceIn(640f, 1000f)
      val height = width * bmp.height / bmp.width.coerceAtLeast(1)
      val at = box?.let { Offset(it.left, it.bottom + 40f) }?.takeIf { p ->
        // Only if that spot is empty; otherwise insertPicture puts it below everything
        val spot = androidx.compose.ui.geometry.Rect(p, androidx.compose.ui.geometry.Size(width, height))
        strokes.none { s -> selection.none { it === s } && LassoTools.bounds(listOf(s))?.overlaps(spot) == true }
      }
      insertPicture(file, bmp, width, at)
      clearLasso()
      val saved = withContext(Dispatchers.IO) { NotePictures.saveToGallery(context, bmp, "graph_" + System.currentTimeMillis()) }
      Toast.makeText(context, "Graph of $label added" + if (saved) " and saved to Gallery" else "", Toast.LENGTH_SHORT).show()
    }
  }

  // Gallery icon: insert a picture into the note
  val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) scope.launch {
      val res = withContext(Dispatchers.IO) { NotePictures.import(context, uri) }
      if (res == null) Toast.makeText(context, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
      else insertPicture(res.first, res.second)
    }
  }

  // ===================== Phone as toolbar =====================
  var showPhoneToolbar by remember { mutableStateOf(false) }
  var pendingLinkRole by remember { mutableStateOf(RemoteToolbarLink.Role.NONE) }
  val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    if (result.values.all { it }) {
      when (pendingLinkRole) {
        RemoteToolbarLink.Role.CANVAS -> RemoteToolbarLink.startCanvas(context)
        RemoteToolbarLink.Role.TOOLBAR -> { RemoteToolbarLink.startToolbar(context); showPhoneToolbar = true }
        RemoteToolbarLink.Role.NONE, RemoteToolbarLink.Role.LISTENING -> {}
      }
    } else {
      Toast.makeText(context, "Nearby devices permission is needed to link your phone and tablet", Toast.LENGTH_LONG).show()
    }
    pendingLinkRole = RemoteToolbarLink.Role.NONE
  }
  // On a phone, while the app is open: listen for a tablet that starts "Use phone as toolbar" and
  // switch to the toolbar by itself (tablets never listen, so they can't be taken over)
  val isPhoneSized = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp < 600
  androidx.lifecycle.compose.LifecycleResumeEffect(isPhoneSized) {
    ToolbarWake.appVisible = true
    if (isPhoneSized) {
      RemoteToolbarLink.listen(context)
      // Keep watching for a tablet while the app is closed too, so it can open by itself
      ToolbarWake.registerWatch(context)
      ToolbarWake.clearNotification(context)
    }
    onPauseOrDispose { ToolbarWake.appVisible = false; RemoteToolbarLink.stopListening() }
  }
  // Phone: once, ask to show notifications (how the closed app says "your tablet wants a toolbar")
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  LaunchedEffect(isPhoneSized) {
    val prefs = context.getSharedPreferences("toolbar_wake", Context.MODE_PRIVATE)
    if (isPhoneSized && !prefs.getBoolean("asked_notifications", false) &&
      androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      prefs.edit().putBoolean("asked_notifications", true).apply()
      notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
  }
  val linkRole = RemoteToolbarLink.role
  val linkConnected = RemoteToolbarLink.connected
  LaunchedEffect(linkRole, linkConnected) {
    if (linkRole == RemoteToolbarLink.Role.TOOLBAR && linkConnected) showPhoneToolbar = true
    if (linkRole == RemoteToolbarLink.Role.LISTENING) showPhoneToolbar = false
  }

  /** Asks for Nearby permissions, then starts this device as the canvas or as the phone toolbar. */
  fun startLink(role: RemoteToolbarLink.Role) {
    pendingLinkRole = role
    nearbyPermissionLauncher.launch(RemoteToolbarLink.requiredPermissions())
  }

  // ===================== Samsung Notes toolbar tools =====================
  var showTypeMaths by remember { mutableStateOf(false) }
  /** Result of Handwriting help / S Pen to text: (title, LaTeX). */
  var inkTextResult by remember { mutableStateOf<Pair<String, String>?>(null) }
  var showCustomiseToolbar by remember { mutableStateOf(false) }

  /** What a tool acts on: the lasso selection, or the whole page. Highlighter and tape are skipped. */
  fun toolTarget(): List<InkStroke> = liveSelection().ifEmpty { strokes.toList() }.filter { it.isInk }

  /** Replaces strokes in place (keeping order and the lasso selection) as one undo step. */
  /**
   * The layer a selection goes to with Move forwards ([dir] +1, the layer above) or Move
   * backwards (-1, below), or null when there's none that way (so the item is greyed out).
   * A selection across layers moves from its top layer (forwards) or bottom one (backwards).
   */
  fun layerStepFor(selection: List<InkStroke>, dir: Int): NoteLayer? {
    if (selection.isEmpty()) return null
    val ids = noteLayers.map { it.id }
    val indices = selection.map { s -> ids.indexOf(s.layer).coerceAtLeast(0) }
    val from = if (dir > 0) indices.max() else indices.min()
    return noteLayers.getOrNull(from + dir)
  }

  fun moveSelectionToLayer(selection: List<InkStroke>, target: NoteLayer) {
    history.checkpoint(strokes)
    val moved = ArrayList<InkStroke>()
    for (i in strokes.indices) {
      val s = strokes[i]
      if (selection.any { it === s }) { val m = s.copy(layer = target.id); strokes[i] = m; moved += m }
    }
    lassoSelection.clear(); lassoSelection.addAll(moved)
    persistCurrentNote()
    Toast.makeText(context, "Moved to ${target.name}" + if (!target.visible) " (hidden)" else "", Toast.LENGTH_SHORT).show()
  }

  fun replaceStrokes(target: List<InkStroke>, transform: (InkStroke) -> InkStroke) {
    if (target.isEmpty()) return
    history.checkpoint(strokes)
    val replaced = ArrayList<InkStroke>()
    for (i in strokes.indices) {
      val s = strokes[i]
      if (target.any { it === s }) {
        val n = transform(s)
        strokes[i] = n
        if (lassoSelection.any { it === s }) replaced += n
      }
    }
    if (replaced.isNotEmpty()) { lassoSelection.clear(); lassoSelection.addAll(replaced) }
    persistCurrentNote()
  }

  fun recogniseForDialog(title: String) {
    val target = toolTarget()
    if (target.isEmpty()) { Toast.makeText(context, "Write something first", Toast.LENGTH_SHORT).show(); return }
    scope.launch {
      isRunningOcr = true
      val latex = try { recognizeInk(target) } finally { isRunningOcr = false }
      if (latex.isNullOrBlank()) Toast.makeText(context, "Couldn't read that handwriting", Toast.LENGTH_SHORT).show()
      else inkTextResult = title to latex
    }
  }

  fun onToolbarTool(tool: NoteTool) {
    when (tool) {
      NoteTool.KEYBOARD -> showTypeMaths = true
      NoteTool.PEN -> if (selectedTool == MyScriptTool.PEN) showPenSettings = !showPenSettings else selectedTool = MyScriptTool.PEN
      NoteTool.HIGHLIGHTER -> if (selectedTool == MyScriptTool.HIGHLIGHTER) showHighlighterSettings = !showHighlighterSettings else selectedTool = MyScriptTool.HIGHLIGHTER
      NoteTool.ERASER -> if (selectedTool == MyScriptTool.ERASER) showEraserSettings = !showEraserSettings else selectedTool = MyScriptTool.ERASER
      NoteTool.LASSO -> if (selectedTool == MyScriptTool.LASSO) showLassoSettings = !showLassoSettings else selectedTool = MyScriptTool.LASSO
      NoteTool.TAPE -> if (selectedTool == MyScriptTool.TAPE) showTapeSettings = !showTapeSettings else selectedTool = MyScriptTool.TAPE
      NoteTool.FILL -> if (selectedTool == MyScriptTool.FILL) showFillSettings = !showFillSettings else selectedTool = MyScriptTool.FILL
      NoteTool.LAYERS -> showLayers = !showLayers
      NoteTool.LASER -> if (selectedTool == MyScriptTool.LASER) showLaserSettings = !showLaserSettings else selectedTool = MyScriptTool.LASER
      NoteTool.UNDO -> if (history.undo(strokes)) persistCurrentNote()
      NoteTool.REDO -> if (history.redo(strokes)) persistCurrentNote()
      NoteTool.CLEAN_UP -> {
        val target = toolTarget()
        if (target.isEmpty()) Toast.makeText(context, "Nothing to clean up", Toast.LENGTH_SHORT).show()
        else {
          replaceStrokes(target) { InkTools.cleanUp(it) }
          Toast.makeText(context, "Handwriting cleaned up", Toast.LENGTH_SHORT).show()
        }
      }
      NoteTool.HANDWRITING_HELP -> recogniseForDialog("Handwriting help")
      NoteTool.PEN_TO_TEXT -> recogniseForDialog("S Pen to text")
      NoteTool.CHANGE_STYLE -> {
        // Applies the current pen colour and size to the selection (or the whole page)
        val target = toolTarget()
        if (target.isEmpty()) Toast.makeText(context, "Select ink with the lasso first", Toast.LENGTH_SHORT).show()
        else replaceStrokes(target) { it.copy(colorHex = selectedPenColor, width = selectedThickness) }
      }
      NoteTool.EASY_WRITING_PAD -> {
        persistCurrentNote()
        currentScreen = AppScreen.QUICK_LATEX
      }
      NoteTool.AUTO_SHAPES -> {
        settings.autoShapes = !settings.autoShapes
        Toast.makeText(context, if (settings.autoShapes) "Auto-fix shapes on" else "Auto-fix shapes off", Toast.LENGTH_SHORT).show()
      }
      NoteTool.LOCK_CANVAS -> {
        canvasLocked = !canvasLocked
        Toast.makeText(context, if (canvasLocked) "Canvas locked: it won't move or zoom" else "Canvas unlocked", Toast.LENGTH_SHORT).show()
      }
      NoteTool.COLOURS, NoteTool.THICKNESS -> {}
    }
  }

  fun askGeminiNanoTutor() {
    val model = generativeModel
    val sol = symbolicSolution
    if (model == null) {
      nanoExplanation = "Gemini Nano isn't available on this device ($nanoStatus)."
      return
    }
    if (sol.isUnreadable) {
      nanoExplanation = "The handwriting couldn't be read as math, so there's nothing to explain yet. Try rewriting it or selecting just the expression with the lasso."
      return
    }
    activeNanoJob?.cancel()
    activeNanoJob = scope.launch {
      isRunningNano = true
      nanoExplanation = "Generating explanation with Gemini Nano..."
      val sb = StringBuilder()
      try {
        val prompt = buildString {
          append("Explain this math solution concisely in 3 short sentences. ")
          append("If a step looks wrong, say so instead of justifying it. ")
          append("Write plain text only: no Markdown, no LaTeX, no dollar signs. ")
          append("Write math with Unicode symbols, for example x², eˣ, ∫, √x, π, ≤.\n")
          append("Problem: ${sol.normalizedMath}\n")
          sol.steps.forEach { append("- $it\n") }
          append("Final answer: ${sol.finalAnswer}\n")
        }
        model.generateContentStream(prompt).collect { chunk ->
          val piece = chunk.candidates.firstOrNull()?.text.orEmpty()
          if (piece.isNotEmpty()) {
            sb.append(piece)
            nanoExplanation = NanoText.clean(sb.toString())
          }
        }
      } catch (e: Throwable) {
        nanoExplanation = "Gemini Nano: ${e.message}"
      } finally {
        isRunningNano = false
      }
    }
  }

  when (currentScreen) {
    AppScreen.EDITOR -> {
      // =========================================================================
      // SCREEN 1, 2, 3: EXACT MYSCRIPT MATH EDITOR + CONTEXT MENU + SOLUTION STEPS
      // =========================================================================
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            when {
              paperColor.isDark -> EditorChrome.bar // dark page: dark status bar strip too
              uiStyleMode == UiStyleMode.ONEUI_9 -> Color(0xFFE4E4E6)
              else -> Color(0xFFEDEDED)
            }
          )
          .statusBarsPadding()
          .navigationBarsPadding()
      ) {
        val bgImage = remember(noteBackgroundPath) {
          noteBackgroundPath?.let { p ->
            runCatching { android.graphics.BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull()
          }
        }
        DisposableEffect(Unit) {
          // Phone toolbar: taps on the phone act on this canvas
          RemoteToolbarLink.onCommand = { cmd ->
            when (cmd) {
              is RemoteCommand.Tool -> onToolbarTool(cmd.tool)
              is RemoteCommand.PenColor -> {
                if (selectedTool == MyScriptTool.HIGHLIGHTER) settings.highlighterColor = cmd.argb
                else { selectedPenColor = cmd.argb; selectedTool = MyScriptTool.PEN }
              }
              is RemoteCommand.PenSize -> selectedThickness = cmd.width
              is RemoteCommand.Pen -> { selectedPenType = cmd.type; selectedTool = MyScriptTool.PEN }
              is RemoteCommand.Line -> selectedLineStyle = cmd.style
              is RemoteCommand.Straighten -> settings.autoShapes = cmd.on
              RemoteCommand.Solve -> runOcrAndSolve(openStepsCard = true)
            }
          }
          onDispose {
            EditorChrome.dark = false
            RemoteToolbarLink.onCommand = null
            SpenHoverBus.canvasOrigin = null
            SpenHoverBus.eraserHover = false
          }
        }
        // Dark page → dark editor: bars, toolbar, menus, dialogs and the status bar icons
        val hostView = androidx.compose.ui.platform.LocalView.current
        val laserPredictor = remember(hostView) { androidx.input.motionprediction.MotionEventPredictor.newInstance(hostView) }
        SideEffect {
          EditorChrome.dark = paperColor.isDark
          (hostView.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, hostView).isAppearanceLightStatusBars = !paperColor.isDark
          }
        }
        // ...and the phone shows what's selected here
        val linkState = ToolbarState(selectedTool, if (selectedTool == MyScriptTool.HIGHLIGHTER) settings.highlighterColor else selectedPenColor, selectedThickness, history.canUndo, history.canRedo, settings.autoShapes, canvasLocked, selectedPenType, selectedLineStyle, settings.toolbarColors, paperColor.isDark, settings.highlighterColor)
        LaunchedEffect(linkState, settings.toolbar, RemoteToolbarLink.connected) {
          if (RemoteToolbarLink.role == RemoteToolbarLink.Role.CANVAS) RemoteToolbarLink.sendState(linkState, settings.toolbar)
        }
        // Finger gestures in progress (not Compose state: they change on every touch event)
        val pinch = remember { arrayOfNulls<PinchGesture>(1) }
        val fingerPanLast = remember { arrayOfNulls<Offset>(1) }
        // After a pinch, the finger left on the screen mustn't start drawing or erasing
        val ignoreUntilUp = remember { booleanArrayOf(false) }
        // One undo step per eraser gesture, however many strokes it rubs out
        val erasedInGesture = remember { booleanArrayOf(false) }
        // The eraser: where it was at the last update (page), and each stroke's box (looked up by identity)
        val eraseLast = remember { arrayOfNulls<Offset>(1) }
        // This touch began as a side-button erase (it keeps erasing until the pen lifts)
        val buttonErase = remember { booleanArrayOf(false) }
        val strokeBounds = remember { java.util.IdentityHashMap<InkStroke, androidx.compose.ui.geometry.Rect>() }
        // Long press on a picture selects it: the pending timer and where the press started
        val holdHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
        val hold = remember { arrayOfNulls<Any>(2) } // [0] Runnable, [1] Offset
        fun cancelHold() { (hold[0] as? Runnable)?.let { holdHandler.removeCallbacks(it) }; hold[0] = null; hold[1] = null }
        // Where the pen is predicted to be by the next frame: drawn ahead of the ink so writing keeps up
        var inkTip by remember { mutableStateOf<Offset?>(null) }
        // The eraser's circle, on screen: follows the pen while hovering and rubbing out
        var eraserCursor by remember { mutableStateOf<Offset?>(null) }
        // Hold to straighten: keep the pen still at the end of a line and it snaps straight.
        // [0] the line is straight (its end follows the pen), [1] it became a neat shape (frozen)
        val straight = remember { BooleanArray(2) }
        val holdAnchor = remember { arrayOfNulls<Offset>(1) }
        val straightenTimer = remember { arrayOfNulls<Runnable>(1) }
        fun cancelStraighten() { straightenTimer[0]?.let { holdHandler.removeCallbacks(it) }; straightenTimer[0] = null }
        EditorPart {

        // A stroke (page coordinates) in the look of its pen: used by the ink layer and the live stroke
        fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePath(s: InkStroke) {
          val pts = s.points
          if (pts.isEmpty()) return
          val base = Color(PaperDraw.displayInk(s.colorHex, paperColor))
          // Peeled-back tape stays faintly visible so it can be tapped to cover again
          val col = if (s.isTape && revealedTape.any { it === s }) base.copy(alpha = 0.18f) else base
          // Bucket fill (or converted text): the mask image, tinted, stretched over its bounds
          s.fill?.let { m ->
            val a = pts[0]; val b = pts.getOrElse(1) { pts[0] }
            // Placed at its exact page position: whole-unit rounding put it up to a page unit
            // off, a visible gap along the outline when zoomed in
            withTransform({
              translate(a.x, a.y)
              scale((b.x - a.x).coerceAtLeast(0.01f) / m.w, (b.y - a.y).coerceAtLeast(0.01f) / m.h, pivot = Offset.Zero)
            }) {
            drawImage(
              image = BucketFill.image(m),
              srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
              srcSize = androidx.compose.ui.unit.IntSize(m.w, m.h),
              dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
              dstSize = androidx.compose.ui.unit.IntSize(m.w, m.h),
              alpha = col.alpha,
              colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(col.copy(alpha = 1f)),
              filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
            )
            }
            return
          }
          // Picture: drawn over its bounds once loaded (loading triggers a redraw)
          s.picture?.let { pic ->
            val a = pts[0]; val b = pts.getOrElse(1) { pts[0] }
            val img = pic.image
            if (img == null) {
              NotePictures.load(pic) { pictureTick++ }
              drawRect(Color(0x11000000), topLeft = Offset(a.x, a.y), size = androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y))
            } else {
              drawImage(
                image = img,
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(a.x.toInt(), a.y.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize((b.x - a.x).toInt().coerceAtLeast(1), (b.y - a.y).toInt().coerceAtLeast(1)),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
              )
            }
            return
          }
          // Pen type and line style (fountain, calligraphy, pencil, brush; dashed, dotted, tapered)
          drawInkStroke(s, col)
          if (s.isTape) drawTapePattern(s, col)
        }
        // The finished ink, recorded in small groups (a new set for another note or page colour,
        // since the colour of ink on dark paper changes)
        val strokeCache = remember(currentNoteId, paperColor) { StrokeCache() }
        // The zoom alone (not the scroll position): outline widths on the ink layer depend on it
        val zoom by remember { derivedStateOf { canvasView.scale } }

        // 0. PAPER: the pattern over the visible part of the page
        Canvas(Modifier.fillMaxSize()) { with(PaperDraw) { drawPaper(paperPattern, paperColor, canvasView) } }

        // 0b. INK LAYER: every finished stroke, recorded once in page coordinates on its own GPU
        // layer. Scrolling and zooming only move or scale the layer, and writing only redraws the
        // live stroke above it, so the page is no longer redrawn stroke by stroke on the main thread
        // every frame (what made moving the page lag and drained the battery while writing).
        Box(Modifier.fillMaxSize().clipToBounds()) {
          Canvas(
            Modifier.fillMaxSize().graphicsLayer {
              val v = canvasView
              translationX = v.offset.x; translationY = v.offset.y
              scaleX = v.scale; scaleY = v.scale
              transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
          ) {
            // Imported PDF page / image underneath the ink
            bgImage?.let { img ->
              drawImage(
                image = img,
                dstOffset = androidx.compose.ui.unit.IntOffset(NoteImportExport.BG_X.toInt(), NoteImportExport.BG_Y.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(img.width, img.height)
              )
            }
            // A glow behind strokes: the solved ones while the steps card is open, the selection's outline
            fun drawGlow(list: List<InkStroke>, color: Color, extra: Float = 18f) {
              for (s in list) {
                val pts = s.points
                if (s.picture != null || s.fill != null) continue
                if (pts.size == 1) drawCircle(color, (s.width + extra) / 2f, Offset(pts[0].x, pts[0].y))
                if (pts.size > 1) {
                  val glowPath = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                  }
                  drawPath(glowPath, color, style = Stroke(width = s.width + extra, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
              }
            }
            @Suppress("UNUSED_EXPRESSION") pictureTick // redraw when a picture has loaded
            val selectedNow = strokes.filter { s -> lassoSelection.any { it === s } }
            if (showStepByStepCard && selectedNow.isEmpty()) {
              drawGlow(strokes.filter { s -> solvedStrokes.any { it === s } }.ifEmpty { strokes }, Color(0x5526A69A))
            }
            // Samsung Notes: selected ink gets a thin blue outline (the box and handles are drawn on top, on screen)
            if (selectedNow.isNotEmpty()) drawGlow(selectedNow, SelectionBlue, 4.5f * density / zoom)

            // Layers bottom to top, each at its own opacity; in each: pictures, fills, highlighter, ink, tape
            val knownLayers = noteLayers.map { it.id }.toSet()
            val firstLayer = noteLayers.first().id
            for (layer in noteLayers) {
              if (!layer.visible) continue
              val onLayer = strokes.filter { (if (it.layer in knownLayers) it.layer else firstLayer) == layer.id }
              if (onLayer.isEmpty()) continue
              val translucent = layer.opacity < 0.999f
              if (translucent) {
                // Only the part on screen goes through the see-through buffer (a whole long page wouldn't fit)
                val vis = canvasView.visiblePage(size.width, size.height)
                val r = LassoTools.bounds(onLayer)?.inflate(onLayer.maxOf { it.width } + 4f)?.intersect(vis) ?: vis
                drawContext.canvas.saveLayer(if (r.isEmpty) vis else r, androidx.compose.ui.graphics.Paint().apply { alpha = layer.opacity })
              }
              // Through the cache: only the small group of strokes that changed is drawn again
              with(strokeCache) {
                val id = layer.id
                drawCached("$id/pic", onLayer.filter { it.picture != null }, { it.picture?.image != null }) { drawStrokePath(it) }
                drawCached("$id/fill", onLayer.filter { it.fill != null }, { null }) { drawStrokePath(it) }
                drawCached("$id/hl", onLayer.filter { it.isHighlighter }, { null }) { drawStrokePath(it) }
                drawCached("$id/ink", onLayer.filter { it.isInk }, { null }) { drawStrokePath(it) }
                drawCached("$id/tape", onLayer.filter { it.isTape }, { s -> revealedTape.any { it === s } }) { drawStrokePath(it) }
              }
              if (translucent) drawContext.canvas.restore()
            }
            strokeCache.prune(strokes)
          }
        }
        // The eraser's circle as the pen's hover pointer: Android draws its pointer itself, at the
        // pen's position with no delay (what the app draws always arrives a frame or two late)
        val eraserRadiusPx = if (settings.eraserArea) 6f + settings.eraserSize * 6f else 36f
        val eraserIcon = remember(eraserRadiusPx) { eraserPointerIcon(eraserRadiusPx) }
        val eraserPointer = selectedTool == MyScriptTool.ERASER || SpenHoverBus.eraserHover
        SideEffect { hostView.pointerIcon = if (eraserPointer) eraserIcon else null }
        // 1. INFINITE PAPER & S-PEN CANVAS
        Canvas(
          modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerHoverIcon(if (eraserPointer) androidx.compose.ui.input.pointer.PointerIcon(eraserIcon) else androidx.compose.ui.input.pointer.PointerIcon.Default)
            .onGloballyPositioned {
              SpenHoverBus.canvasOrigin = it.positionInWindow()
              canvasSizePx[0] = it.size.width.toFloat(); canvasSizePx[1] = it.size.height.toFloat()
            }
            .pointerInteropFilter { event ->
              val toolType = event.getToolType(0)
              // Deliver the pen's movements as they come, not batched once per frame: less lag for ink and laser
              // (Not for the eraser: it works once per screen update, so batched movements suit it)
              if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val erasing = selectedTool == MyScriptTool.ERASER || toolType == MotionEvent.TOOL_TYPE_ERASER ||
                  (settings.penButtonShortcuts && (event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0)
                if (!erasing) hostView.requestUnbufferedDispatch(event)
              }

              // --- Pan and zoom: two fingers always; one finger when "S Pen only" is on ---
              val fingers = (0 until event.pointerCount).count { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
              if (fingers >= 2 && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                // A second finger: whatever the first finger started drawing was the start of a pinch
                currentPoints = emptyList()
                lassoDragLast = null
                inkTip = null
                cancelStraighten()
                if (laserTrail.penDown) laserTrail.lift()
                fingerPanLast[0] = null
                ignoreUntilUp[0] = true
                pinch[0] =if (canvasLocked) null else PinchGesture(
                  Offset(event.getX(0), event.getY(0)), Offset(event.getX(1), event.getY(1)), canvasView, zoomLocked = false
                )
                return@pointerInteropFilter true
              }
              val activePinch = pinch[0]
              if (activePinch != null || (canvasLocked && fingers >= 2)) {
                when (event.actionMasked) {
                  MotionEvent.ACTION_MOVE -> if (activePinch != null && event.pointerCount >= 2) {
                    canvasView = activePinch.update(Offset(event.getX(0), event.getY(0)), Offset(event.getX(1), event.getY(1)))
                  }
                  MotionEvent.ACTION_POINTER_UP -> pinch[0] = null
                  MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pinch[0] = null; ignoreUntilUp[0] = false }
                }
                return@pointerInteropFilter true
              }
              if (ignoreUntilUp[0]) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignoreUntilUp[0] = false
                return@pointerInteropFilter true
              }
              // --- Pictures: a tap elsewhere drops the selection; a long press on one selects it ---
              when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                  if (selectedPicture != null) {
                    selectedPicture = null
                    ignoreUntilUp[0] = true
                    return@pointerInteropFilter true
                  }
                  val hit = PictureOps.hit(strokes.filter { layerVisible(it.layer) }, canvasView.toPage(Offset(event.x, event.y)))
                  if (hit != null && toolType != MotionEvent.TOOL_TYPE_ERASER) {
                    val r = Runnable {
                      hold[0] = null
                      selectedPicture = hit
                      clearLasso()
                      currentPoints = emptyList()
                      lassoDragLast = null
                      fingerPanLast[0] = null
                      ignoreUntilUp[0] = true
                      hostView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                    hold[0] = r; hold[1] = Offset(event.x, event.y)
                    holdHandler.postDelayed(r, 450)
                  }
                }
                MotionEvent.ACTION_MOVE -> (hold[1] as? Offset)?.let { d ->
                  if (hypot(event.x - d.x, event.y - d.y) > 18f) cancelHold()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
              }
              if (disableFingerWriting && toolType == MotionEvent.TOOL_TYPE_FINGER) {
                // "Write with S Pen only": one finger moves the page
                when (event.actionMasked) {
                  MotionEvent.ACTION_DOWN -> fingerPanLast[0] = Offset(event.x, event.y)
                  MotionEvent.ACTION_MOVE -> fingerPanLast[0]?.let { last ->
                    if (!canvasLocked) canvasView = canvasView.panBy(event.x - last.x, event.y - last.y)
                    fingerPanLast[0] = Offset(event.x, event.y)
                  }
                  MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerPanLast[0] = null
                }
                return@pointerInteropFilter true
              }

              // Ink lives in page coordinates
              val view = canvasView
              val pagePoint = view.toPage(Offset(event.x, event.y))
              val x = pagePoint.x
              val y = pagePoint.y
              val t = event.eventTime
              val isStylusButtonHeld = settings.penButtonShortcuts && (
                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                  (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
                ) || toolType == MotionEvent.TOOL_TYPE_ERASER

              // S Pen hover. The side button itself is handled in MainActivity.dispatchGenericMotionEvent.
              if (
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                  event.actionMasked == MotionEvent.ACTION_HOVER_ENTER
              ) {
                // Screen position: the quick menu and eraser cursor are drawn in screen space
                SpenHoverBus.lastHoverOffset = Offset(event.x, event.y)
                return@pointerInteropFilter true
              }
              if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                SpenHoverBus.eraserHover = false
                return@pointerInteropFilter true
              }
              // The predictor learns from every pen movement (pen, highlighter and laser use it)
              if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) laserPredictor.record(event)
              if (event.actionMasked == MotionEvent.ACTION_DOWN && isStylusButtonHeld) {
                SpenHoverBus.markPressUsed()
              }

              /**
               * Rubs out along [path] (page points: where the pen was at the last screen update,
               * then everywhere it went since). One pass per screen update: only strokes whose
               * box is near the path are looked at closely.
               */
              fun eraseAlong(path: List<Offset>) {
                if (path.isEmpty()) return
                // What the eraser may touch: this layer, no pictures; only highlighter with "Erase highlighter only"
                fun erasable(s: InkStroke) = s.layer == activeLayer && layerVisible(s.layer) && s.picture == null &&
                  (!settings.eraseHighlighterOnly || s.isHighlighter)
                val area = settings.eraserArea
                // Area eraser: its size; stroke eraser: 36 px on screen whatever the zoom
                val r = if (area) (6f + settings.eraserSize * 6f) / view.scale else 36f / view.scale
                val pathBox = androidx.compose.ui.geometry.Rect(
                  path.minOf { it.x } - r, path.minOf { it.y } - r, path.maxOf { it.x } + r, path.maxOf { it.y } + r
                )
                if (strokeBounds.size > strokes.size * 3 + 500) strokeBounds.clear()
                fun boxOf(s: InkStroke) = strokeBounds.getOrPut(s) { LassoTools.bounds(listOf(s)) ?: androidx.compose.ui.geometry.Rect.Zero }
                val near = strokes.filter { s -> erasable(s) && boxOf(s).inflate(s.width / 2f + 1f).overlaps(pathBox) }
                if (near.isEmpty()) return
                fun distToPath(px: Float, py: Float): Float {
                  if (path.size == 1) return hypot(px - path[0].x, py - path[0].y)
                  var best = Float.MAX_VALUE
                  for (i in 1 until path.size) {
                    val a = path[i - 1]; val b = path[i]
                    val dx = b.x - a.x; val dy = b.y - a.y
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
                    val d = hypot(px - (a.x + t * dx), py - (a.y + t * dy))
                    if (d < best) best = d
                  }
                  return best
                }
                // Whether the stroke's line (its points and the straight bits between them) passes
                // within [reach] of the eraser's path: shapes have long sides with no points along them
                fun touches(s: InkStroke, reach: Float): Boolean {
                  val pts = s.points
                  if (pts.size == 1) return distToPath(pts[0].x, pts[0].y) < reach
                  for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    if (distToPath(b.x, b.y) < reach) return true
                    val dx = b.x - a.x; val dy = b.y - a.y
                    val len2 = dx * dx + dy * dy
                    if (len2 < reach * reach) continue
                    for (q in path) {
                      val t = (((q.x - a.x) * dx + (q.y - a.y) * dy) / len2).coerceIn(0f, 1f)
                      if (hypot(q.x - (a.x + t * dx), q.y - (a.y + t * dy)) < reach) return true
                    }
                    // The eraser crossed the side between two path points
                    for (j in 1 until path.size) if (LassoTools.segmentsCross(a.x, a.y, b.x, b.y, path[j - 1].x, path[j - 1].y, path[j].x, path[j].y)) return true
                  }
                  return distToPath(pts[0].x, pts[0].y) < reach
                }
                if (!area) {
                  val hit = near.filter { s ->
                    if (s.fill != null) path.any { BucketFill.hits(s, it) } else touches(s, r)
                  }
                  if (hit.isEmpty()) return
                  if (!erasedInGesture[0]) { history.checkpoint(strokes); erasedInGesture[0] = true }
                  strokes.removeAll { s -> hit.any { it === s } }
                  lassoSelection.retainAll { sel -> strokes.any { it === sel } }
                  return
                }
                // Area eraser: rub out only the part under the eraser (strokes are split), at points
                // along the path close enough together to leave no gaps
                val samples = ArrayList<Offset>()
                samples += path[0]
                for (i in 1 until path.size) {
                  val a = path[i - 1]; val b = path[i]
                  val n = (hypot(b.x - a.x, b.y - a.y) / (r * 0.5f)).toInt().coerceIn(1, 200)
                  for (k in 1..n) samples += Offset(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n)
                }
                val replaced = java.util.IdentityHashMap<InkStroke, List<InkStroke>>()
                for (s in near) {
                  if (s.fill != null) { if (samples.any { BucketFill.hits(s, it) }) replaced[s] = emptyList(); continue }
                  if (!touches(s, r + s.width / 2f)) continue
                  // Long straight sides (shapes, straight tape) get points along them first, so
                  // the eraser can cut them anywhere, not only at their corners
                  val base = InkTools.densify(s, r * 0.5f)
                  var pieces = listOf(base)
                  for (p in samples) pieces = pieces.flatMap { LassoTools.eraseArea(it, p.x, p.y, r) }
                  if (!(pieces.size == 1 && pieces[0] === base)) replaced[s] = pieces
                }
                if (replaced.isEmpty()) return
                if (!erasedInGesture[0]) { history.checkpoint(strokes); erasedInGesture[0] = true }
                val out = ArrayList<InkStroke>(strokes.size + 8)
                for (s in strokes) { val rep = replaced[s]; if (rep == null) out += s else out += rep }
                strokes.clear(); strokes.addAll(out)
                lassoSelection.retainAll { sel -> strokes.any { it === sel } }
                // Saved once when the pen lifts, not after every bit rubbed out
              }

              when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                  // Eyedropper: the colour of the ink under the tap (or the page) goes into the toolbar slot
                  // Bucket fill: fill the closed shape under the tap (on the visible part of the page)
                  if (selectedTool == MyScriptTool.FILL) {
                    val area = view.visiblePage(canvasSizePx[0], canvasSizePx[1])
                    val argb = (selectedPenColor and 0xFFFFFFL) or ((settings.fillOpacity * 255).toLong() shl 24)
                    val fill = BucketFill.build(strokes.filter { layerVisible(it.layer) }, Offset(x, y), area, argb, activeLayer)
                    if (fill == null) {
                      Toast.makeText(context, "Tap inside a closed shape to fill it", Toast.LENGTH_SHORT).show()
                    } else {
                      history.checkpoint(strokes)
                      strokes.add(fill)
                      persistCurrentNote()
                    }
                    return@pointerInteropFilter true
                  }
                  eyedropperSlot?.let { slot ->
                    val r = 24f / view.scale
                    val hit = strokes.lastOrNull { s ->
                      when {
                        s.isTape || s.picture != null -> false
                        s.fill != null -> BucketFill.hits(s, Offset(x, y))
                        else -> LassoTools.distanceTo(s, x, y) < r + s.width / 2f
                      }
                    }
                    val picked = (hit?.colorHex ?: paperColor.argb) or 0xFF000000L
                    settings.setToolbarColor(slot, picked)
                    selectedPenColor = picked
                    eyedropperSlot = null
                    return@pointerInteropFilter true
                  }
                  erasedInGesture[0] = false
                  showColorPickerPopup = false
                  showMoreMenu = false
                  autoSolveJob?.cancel()
                  straight[0] = false; straight[1] = false; holdAnchor[0] = null; cancelStraighten()
                  // Started with the side button held: it stays an erase until the pen lifts, even if the button is let go
                  buttonErase[0] = isStylusButtonHeld
                  if (isStylusButtonHeld || selectedTool == MyScriptTool.ERASER) {
                    SpenHoverBus.radialMenuVisible = false
                    eraserCursor = Offset(event.x, event.y)
                    eraseLast[0] = Offset(x, y)
                    eraseAlong(listOf(Offset(x, y)))
                  } else if (SpenHoverBus.radialMenuVisible) {
                    SpenHoverBus.radialMenuVisible = false
                  } else if (selectedTool == MyScriptTool.LASSO) {
                    pasteAnchor = null
                    val box = LassoTools.bounds(liveSelection())
                    if (box != null && box.inflate(28f).contains(Offset(x, y))) {
                      // Drag the selection
                      history.checkpoint(strokes)
                      lassoDragLast = Offset(x, y)
                    } else {
                      lassoSelection.clear()
                      currentPoints = listOf(TimedPoint(x, y, t))
                    }
                  } else if (selectedTool == MyScriptTool.LASER) {
                    clearLasso()
                    laserTrail.start(x, y, t)
                  } else {
                    clearLasso()
                    currentPoints = listOf(TimedPoint(x, y, t))
                  }
                  true
                }
                MotionEvent.ACTION_MOVE -> {
                  val drag = lassoDragLast
                  if (isStylusButtonHeld || buttonErase[0] || selectedTool == MyScriptTool.ERASER) {
                    SpenHoverBus.radialMenuVisible = false
                    currentPoints = emptyList()
                    // Drawn where the pen will be by the time the frame shows, so it stays under the tip
                    eraserCursor = laserPredictor.predict()?.let { p ->
                      val o = Offset(p.x, p.y); p.recycle()
                      if (hypot(o.x - event.x, o.y - event.y) < 60f * hostView.resources.displayMetrics.density) o else null
                    } ?: Offset(event.x, event.y)
                    // Rub out along the whole path since the last update, in one pass
                    val path = ArrayList<Offset>(event.historySize + 2)
                    eraseLast[0]?.let { path += it }
                    for (i in 0 until event.historySize) path += view.toPage(Offset(event.getHistoricalX(i), event.getHistoricalY(i)))
                    path += Offset(x, y)
                    eraseLast[0] = Offset(x, y)
                    eraseAlong(path)
                  } else if (drag != null) {
                    val dx = x - drag.x
                    val dy = y - drag.y
                    val moved = ArrayList<InkStroke>()
                    for (i in strokes.indices) {
                      val s = strokes[i]
                      if (lassoSelection.any { it === s }) {
                        val m = LassoTools.translate(s, dx, dy)
                        strokes[i] = m
                        moved.add(m)
                      }
                    }
                    lassoSelection.clear()
                    lassoSelection.addAll(moved)
                    lassoDragLast = Offset(x, y)
                  } else if (selectedTool == MyScriptTool.LASER && laserTrail.penDown) {
                    // Every reported position (not just one per frame), then where the pen will be
                    // by the time the frame shows, so the laser keeps up with the pen
                    for (i in 0 until event.historySize) {
                      val hp = view.toPage(Offset(event.getHistoricalX(i), event.getHistoricalY(i)))
                      laserTrail.add(hp.x, hp.y, event.getHistoricalEventTime(i))
                    }
                    laserTrail.add(x, y, t)
                    laserTrail.tip = laserPredictor.predict()?.let { p ->
                      val tip = view.toPage(Offset(p.x, p.y)); p.recycle(); tip
                    }
                  } else if (currentPoints.isNotEmpty() && straight[1]) {
                    // Held into a neat shape: it stays as it is until the pen lifts
                  } else if (currentPoints.isNotEmpty() && straight[0]) {
                    // Held into a straight line: its end follows the pen
                    currentPoints = listOf(currentPoints.first(), TimedPoint(x, y, t))
                  } else if (currentPoints.isNotEmpty()) {
                    val pts = currentPoints.toMutableList()
                    // Stroke stabiliser: the ink is pulled behind the pen on a string (see StrokeStabilizer);
                    // its length is the same on screen at any zoom
                    val inking = selectedTool == MyScriptTool.PEN || selectedTool == MyScriptTool.HIGHLIGHTER
                    // Hold to straighten: once the pen stays put (within a few pixels) for half a
                    // second, a line snaps straight, or a rough circle/rectangle/triangle becomes neat
                    if (inking) {
                      val still = 7f * hostView.resources.displayMetrics.density / view.scale
                      val a = holdAnchor[0]
                      if (a == null || hypot(x - a.x, y - a.y) > still) {
                        holdAnchor[0] = Offset(x, y)
                        cancelStraighten()
                        val r = Runnable {
                          straightenTimer[0] = null
                          val pts0 = currentPoints
                          if (pts0.size < 3 || straight[0] || straight[1]) return@Runnable
                          // Only for a real line: long enough on screen, not a dot or a letter
                          if (LassoTools.pathLength(pts0) * canvasView.scale < 48f * hostView.resources.displayMetrics.density) return@Runnable
                          val probe = InkStroke(pts0, selectedThickness, selectedPenColor)
                          // Nothing it recognises (writing, a scribble): the ink stays as drawn
                          val fit = InkTools.fitShape(probe, lenient = true) ?: return@Runnable
                          if (fit.shape == InkTools.Shape.LINE) {
                            currentPoints = listOf(pts0.first(), pts0.last()); straight[0] = true
                          } else {
                            currentPoints = fit.stroke.points; straight[1] = true
                          }
                          inkTip = null
                          stabilizerPen = null
                          hostView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                        straightenTimer[0] = r
                        holdHandler.postDelayed(r, 550)
                      }
                    }
                    val radius = if (inking) StrokeStabilizer.radiusPx(settings.stabilizer, hostView.resources.displayMetrics.density) / view.scale else 0f
                    fun addPoint(px: Float, py: Float, pt: Long) {
                      if (radius <= 0f) { pts.add(TimedPoint(px, py, pt)); return }
                      val last = pts.last()
                      StrokeStabilizer.pull(last.x, last.y, px, py, radius)?.let { (nx, ny) -> pts.add(TimedPoint(nx, ny, pt)) }
                    }
                    if (radius > 0f) stabilizerPen = Offset(x, y)
                    for (i in 0 until event.historySize) {
                      val hp = view.toPage(Offset(event.getHistoricalX(i), event.getHistoricalY(i)))
                      addPoint(hp.x, hp.y, event.getHistoricalEventTime(i))
                    }
                    addPoint(x, y, t)
                    currentPoints = pts
                    // Without the stabiliser, draw a little ahead to where the pen is going
                    inkTip = if (inking && radius <= 0f) laserPredictor.predict()?.let { p ->
                      val tip = view.toPage(Offset(p.x, p.y)); p.recycle()
                      // Never more than a short way ahead, so a wrong guess can't streak
                      val last = pts.last()
                      if (hypot(tip.x - last.x, tip.y - last.y) * view.scale < 40f * hostView.resources.displayMetrics.density) tip else null
                    } else null
                  }
                  true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                  // Stabilised ink trails the pen: finish the line to where the pen lifted, curving into place
                  stabilizerPen = null
                  inkTip = null
                  cancelStraighten()
                  // Rubbed something out in this gesture: save the note once now
                  if (erasedInGesture[0]) { erasedInGesture[0] = false; persistCurrentNote() }
                  val wasButtonErase = buttonErase[0]; buttonErase[0] = false
                  val wasStraightened = straight[0] || straight[1]
                  straight[0] = false; straight[1] = false
                  // Lifted: from here the circle follows the pen's hover (or goes, for a finger)
                  eraserCursor = null
                  if (!wasStraightened && settings.stabilizer > 0 && currentPoints.isNotEmpty() &&
                    (selectedTool == MyScriptTool.PEN || selectedTool == MyScriptTool.HIGHLIGHTER)
                  ) currentPoints = currentPoints + StrokeStabilizer.finish(currentPoints.last(), TimedPoint(x, y, t), currentPoints.getOrNull(currentPoints.size - 2))
                  if (isStylusButtonHeld || wasButtonErase || selectedTool == MyScriptTool.ERASER) {
                    currentPoints = emptyList()
                  } else if (lassoDragLast != null) {
                    lassoDragLast = null
                    persistCurrentNote()
                  } else if (selectedTool == MyScriptTool.LASSO) {
                    val loop = currentPoints
                    currentPoints = emptyList()
                    if (LassoTools.pathLength(loop) < 24f) {
                      // A tap: offer Paste when something was copied
                      if (inkClipboard.isNotEmpty()) pasteAnchor = Offset(x, y)
                    } else {
                      lassoSelection.clear()
                      lassoAngle = 0f
                      val area =if (settings.lassoRectangle) LassoTools.rectangleLoop(loop) else loop
                      lassoSelection.addAll(LassoTools.strokesInside(strokes.filter { it.layer == activeLayer && layerVisible(it.layer) }, area, settings.lassoPartial))
                    }
                  } else if (selectedTool == MyScriptTool.LASER) {
                    laserTrail.lift()
                    currentPoints = emptyList()
                  } else if (currentPoints.isNotEmpty()) {
                    val moveDist = if (currentPoints.size >= 2) {
                      hypot(
                        currentPoints.last().x - currentPoints.first().x,
                        currentPoints.last().y - currentPoints.first().y
                      )
                    } else 0f
                    val tappedTape = if (moveDist <= 4f && currentPoints.size <= 3) {
                      val r = 20f / view.scale
                      strokes.lastOrNull { s -> s.isTape && LassoTools.distanceTo(s, x, y) < s.width / 2f + r }
                    } else null
                    if (tappedTape != null) {
                      // Tape: tap to peel it back and see what's under it, tap again to cover it
                      if (revealedTape.any { it === tappedTape }) revealedTape.removeAll { it === tappedTape }
                      else revealedTape.add(tappedTape)
                    } else if (moveDist > 4f || currentPoints.size > 3) {
                      val drawn = when (selectedTool) {
                        MyScriptTool.HIGHLIGHTER -> InkStroke(currentPoints, highlighterWidth(), highlighterColor(), isHighlighter = true, chisel = settings.highlighterChisel)
                        // Tape: its size, colour and pattern; straight from start to finish with Straighten lines
                        MyScriptTool.TAPE -> InkStroke(
                          if (settings.tapeStraighten) listOf(currentPoints.first(), currentPoints.last()) else currentPoints,
                          tapeSliderToWidth(settings.tapeSize), settings.tapeColor, isTape = true, tapePattern = settings.tapePattern
                        )
                        else -> InkStroke(points = currentPoints, width = selectedThickness, colorHex = selectedPenColor, penType = selectedPenType, lineStyle = selectedLineStyle)
                      }
                      // Auto-fix shapes: a rough line, triangle, rectangle or ellipse becomes a neat one
                      val fitted = if (wasStraightened) drawn
                      else if (settings.autoShapes && selectedTool == MyScriptTool.PEN) InkTools.fitShape(drawn)?.stroke ?: drawn
                      else drawn
                      // A neat shape has only its corners: fill in its sides so the eraser and lasso find all of it
                      val finalStroke = if (fitted !== drawn || wasStraightened) InkTools.densify(fitted, max(3f, fitted.width * 0.75f)) else fitted
                      history.checkpoint(strokes)
                      strokes.add(finalStroke.copy(layer = activeLayer))
                      persistCurrentNote()
                      // Auto-solve: after a short pause, solve if the last two strokes form "="
                      if (settings.autoSolve && LassoTools.endsWithEquals(strokes)) {
                        autoSolveJob?.cancel()
                        autoSolveJob = scope.launch {
                          delay(900L)
                          runOcrAndSolve(only = LassoTools.lineEndingAtEquals(strokes.toList()), openOnlyIfEquals = true)
                        }
                      }
                    }
                    currentPoints = emptyList()
                  }
                  true
                }
                else -> false
              }
            }
        ) {
          // Only what's live is drawn here (the stroke being written, the lasso line, the stabiliser
          // string): the finished ink is on its own layer underneath (see INK LAYER above)
          withTransform({
            translate(canvasView.offset.x, canvasView.offset.y)
            scale(canvasView.scale, canvasView.scale, pivot = Offset.Zero)
          }) {
          // Stabiliser: a faint string from the ink to the pen, so you can see it pulling
          stabilizerPen?.let { pen ->
            currentPoints.lastOrNull()?.let { ink ->
              val w = 1.6f / canvasView.scale
              drawLine(Color(0x66808088), Offset(ink.x, ink.y), Offset(pen.x, pen.y), strokeWidth = w)
              drawCircle(Color(0x99808088), radius = 3.5f / canvasView.scale, center = pen)
            }
          }
          if (currentPoints.isNotEmpty() && selectedTool == MyScriptTool.LASSO) {
            // Samsung Notes' lasso: a dark dashed line over a white one, readable on any paper,
            // closing back to the start; the rectangle from where the drag started to the pen
            val shown = if (settings.lassoRectangle) LassoTools.rectangleLoop(currentPoints) else currentPoints
            if (shown.size > 1) {
              val path = Path().apply {
                moveTo(shown[0].x, shown[0].y)
                for (i in 1 until shown.size) lineTo(shown[i].x, shown[i].y)
              }
              val u = density / canvasView.scale
              drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 3.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
              drawPath(path, Color(0xFF55555C), style = Stroke(width = 1.6f * u, join = StrokeJoin.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f * u, 4f * u))))
              if (!settings.lassoRectangle) {
                val a = shown.last(); val b = shown.first()
                drawLine(Color(0x8855555C), Offset(a.x, a.y), Offset(b.x, b.y), 1.2f * u, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f * u, 4f * u)))
              }
            }
          } else if (currentPoints.isNotEmpty()) {
            // Plus a short way ahead, to where the pen is predicted to be
            val shown = inkTip?.let { tip -> currentPoints + TimedPoint(tip.x, tip.y, currentPoints.last().t) } ?: currentPoints
            drawStrokePath(
              InkStroke(
                points = shown,
                isHighlighter = selectedTool == MyScriptTool.HIGHLIGHTER,
                chisel = selectedTool == MyScriptTool.HIGHLIGHTER && settings.highlighterChisel,
                width = when (selectedTool) {
                  MyScriptTool.LASSO -> 3f / canvasView.scale
                  MyScriptTool.HIGHLIGHTER -> highlighterWidth()
                  MyScriptTool.TAPE -> tapeSliderToWidth(settings.tapeSize)
                  else -> selectedThickness
                },
                isTape = selectedTool == MyScriptTool.TAPE,
                tapePattern = if (selectedTool == MyScriptTool.TAPE) settings.tapePattern else TapePattern.PLAIN,
                colorHex = when (selectedTool) {
                  MyScriptTool.LASER -> 0xFFE53935L
                  MyScriptTool.LASSO -> 0xFF3993A0L
                  MyScriptTool.HIGHLIGHTER -> highlighterColor()
                  MyScriptTool.TAPE -> settings.tapeColor
                  else -> selectedPenColor
                },
                penType = if (selectedTool == MyScriptTool.PEN) selectedPenType else PenType.PEN,
                lineStyle = if (selectedTool == MyScriptTool.PEN) selectedLineStyle else LineStyle.SOLID
              )
            )
          }
          } // end page transform
        }
        // Laser pointer layer: redraws on its own every frame while the laser shows, so the page
        // underneath doesn't have to (that redraw was what made the laser lag)
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
          @Suppress("UNUSED_EXPRESSION") laserFrame
          if (laserTrail.points.isEmpty() && !laserTrail.penDown) return@Canvas
          withTransform({
            translate(canvasView.offset.x, canvasView.offset.y)
            scale(canvasView.scale, canvasView.scale, pivot = Offset.Zero)
          }) { drawLaser(laserTrail, settings.laserColor, canvasView.scale, density) }
        }
        }

        // The handwriting toolbar, used in the top bar or floating on a side. Its handle moves it:
        // drag it and let go, and it snaps to the nearest side of the editor.
        @Composable
        fun EditorToolStrip(vertical: Boolean, modifier: Modifier) {
          val gripPos = remember { arrayOf(Offset.Zero) }
          NoteToolStrip(
            layout = settings.toolbar,
            state = ToolbarState(
              tool = selectedTool,
              // With the highlighter selected, the colours are the highlighter's
              penColor = if (selectedTool == MyScriptTool.HIGHLIGHTER) settings.highlighterColor else selectedPenColor,
              highlighterColor = settings.highlighterColor,
              thickness = selectedThickness,
              canUndo = history.canUndo,
              canRedo = history.canRedo,
              autoShapes = settings.autoShapes,
              canvasLocked = canvasLocked,
              penType = selectedPenType,
              lineStyle = selectedLineStyle,
              quickColors = settings.toolbarColors,
              dark = paperColor.isDark
            ),
            onTool = { onToolbarTool(it) },
            onEditColor = { editingColorSlot = it },
            onPickColor = {
              // The highlighter has its own colour; otherwise picking a colour means "write with it"
              if (selectedTool == MyScriptTool.HIGHLIGHTER) settings.highlighterColor = it
              else {
                selectedPenColor = it
                if (selectedTool != MyScriptTool.FILL) selectedTool = MyScriptTool.PEN
              }
            },
            onPickThickness = { selectedThickness = it },
            vertical = vertical,
            modifier = modifier,
            leading = {
              ToolbarGrip(
                vertical = vertical,
                modifier = Modifier
                  .onGloballyPositioned { gripPos[0] = it.positionInWindow() }
                  .pointerInput(settings.toolbarDock) {
                    var start = Offset.Zero
                    detectDragGestures(
                      onDragStart = { start = it; toolbarFinger = gripPos[0] + it; toolbarDragging = true },
                      onDrag = { change, amount -> change.consume(); toolbarFinger += amount },
                      onDragEnd = {
                        toolbarDragging = false
                        val o = SpenHoverBus.canvasOrigin ?: Offset.Zero
                        val w = canvasSizePx[0]; val h = canvasSizePx[1]
                        val f = toolbarFinger
                        settings.toolbarDock = listOf(
                          ToolbarDock.LEFT to (f.x - o.x), ToolbarDock.RIGHT to (o.x + w - f.x),
                          ToolbarDock.TOP to (f.y - o.y), ToolbarDock.BOTTOM to (o.y + h - f.y)
                        ).minBy { it.second }.first
                      },
                      onDragCancel = { toolbarDragging = false }
                    )
                  }
                  .semantics { contentDescription = "Move toolbar (drag to a side)" }
              )
            }
          ) {
            if (vertical) Box(Modifier.padding(vertical = 8.dp).height(1.dp).width(20.dp).background(EditorChrome.divider))
            else Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(20.dp).background(EditorChrome.divider))
            Surface(
              onClick = { runOcrAndSolve(openStepsCard = true) },
              shape = RoundedCornerShape(16.dp),
              color = OneUi9Tokens.RelatedCardBg,
              modifier = Modifier.semantics { contentDescription = "Solve" }
            ) {
              if (vertical) {
                Box(Modifier.padding(10.dp)) { MyScriptIcons.SparkleAi(color = OneUi9Tokens.SamsungBlue, size = 17.dp) }
              } else {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                  MyScriptIcons.SparkleAi(color = OneUi9Tokens.SamsungBlue, size = 15.dp)
                  Spacer(Modifier.width(5.dp))
                  Text("Solve", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OneUi9Tokens.SamsungDeepBlue)
                }
              }
            }
            // ⁘ Customise toolbar, last on the toolbar as in Samsung Notes
            Box(
              Modifier.size(36.dp).clip(CircleShape).clickable { showCustomiseToolbar = true }
                .semantics { contentDescription = "Customise toolbar" },
              contentAlignment = Alignment.Center
            ) {
              val c = EditorChrome.icon
              Canvas(Modifier.size(17.dp)) {
                val s = size.width; val r = s * 0.2f; val sw = s * 0.09f
                for (cx in listOf(s * 0.27f, s * 0.73f)) for (cy in listOf(s * 0.27f, s * 0.73f)) {
                  drawCircle(c, radius = r, center = Offset(cx, cy), style = Stroke(width = sw))
                }
              }
            }
            // The AI assistant, last of all: ask about this page
            Box(
              Modifier.size(36.dp).clip(CircleShape)
                .background(if (showAssistant) EditorChrome.selected else Color.Transparent)
                .clickable { showAssistant = !showAssistant }
                .semantics { contentDescription = "AI assistant" },
              contentAlignment = Alignment.Center
            ) { RobotIcon(EditorChrome.icon, 19.dp) }
          }
        }
        EditorPart {

        // 2. TOP BAR & TOOLBAR: ONE UI 9.0 DOCKED BAR vs M3E MYSCRIPT CLASSIC
        if (uiStyleMode == UiStyleMode.ONEUI_9) {
          OneUi9EditorTopBar(
            noteTitle = noteTitle,
            selectedTool = selectedTool,
            onSelectTool = { selectedTool = it },
            selectedPenColor = selectedPenColor,
            onSelectPenColor = {
              // Picking a colour means "write with it": switch to the pen from eraser/lasso/laser
              selectedPenColor = it
              selectedTool = MyScriptTool.PEN
            },
            selectedThickness = selectedThickness,
            onCycleThickness = {
              selectedThickness = when {
                selectedThickness < 7f -> 8.5f
                selectedThickness < 11f -> 14.0f
                else -> 5.0f
              }
            },
            onBack = {
              persistCurrentNote()
              currentScreen = AppScreen.HOME_NOTES
            },
            onEditTitle = { showRenameDialog = true },
            onUndo = {
              if (history.undo(strokes)) persistCurrentNote()
            },
            onRedo = {
              if (history.redo(strokes)) persistCurrentNote()
            },
            graphVisible = showInlinePlotter,
            onToggleGraph = { showInlinePlotter = !showInlinePlotter },
            onInsertPicture = {
              galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCalculator = { showCalculator = true },
            onOpenPageStyle = { showPageStyleDialog = true },
            onClearPage = {
              history.checkpoint(strokes)
              strokes.clear()
              recognizedLatex = ""
              clearLasso()
              showStepByStepCard = false
              persistCurrentNote()
            },
            onOpenSettings = {
              persistCurrentNote()
              openSettings()
            },
            onSolve = { runOcrAndSolve(openStepsCard = true) },
            onCustomiseToolbar = { showCustomiseToolbar = true },
            onPhoneToolbar = {
              if (RemoteToolbarLink.role == RemoteToolbarLink.Role.CANVAS) RemoteToolbarLink.stop()
              else startLink(RemoteToolbarLink.Role.CANVAS)
            },
            phoneToolbarOn = RemoteToolbarLink.role == RemoteToolbarLink.Role.CANVAS,
            toolStrip = if (settings.toolbarDock == ToolbarDock.TOP) ({ EditorToolStrip(false, Modifier.fillMaxWidth()) }) else null,
            modifier = Modifier.align(Alignment.TopCenter)
          )
          // The toolbar docked on another side: a floating rounded bar
          when (settings.toolbarDock) {
            ToolbarDock.LEFT, ToolbarDock.RIGHT -> Surface(
              color = EditorChrome.bar,
              shape = RoundedCornerShape(28.dp),
              shadowElevation = 6.dp,
              modifier = Modifier
                .align(if (settings.toolbarDock == ToolbarDock.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(start = 12.dp, end = 12.dp, top = 64.dp, bottom = 80.dp)
                .width(56.dp)
                .fillMaxHeight()
            ) { EditorToolStrip(true, Modifier.fillMaxSize()) }
            ToolbarDock.BOTTOM -> Surface(
              color = EditorChrome.bar,
              shape = RoundedCornerShape(28.dp),
              shadowElevation = 6.dp,
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 150.dp, end = 24.dp, bottom = 14.dp)
                .fillMaxWidth()
                .height(56.dp)
            ) { EditorToolStrip(false, Modifier.fillMaxSize()) }
            ToolbarDock.TOP -> {}
          }
          // While dragging the toolbar's handle: a small pill follows the finger
          if (toolbarDragging) {
            val o = SpenHoverBus.canvasOrigin ?: Offset.Zero
            val p = toolbarFinger - o
            Surface(
              color = EditorChrome.bar,
              shape = RoundedCornerShape(22.dp),
              shadowElevation = 10.dp,
              modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(p.x.toInt() - 70, p.y.toInt() - 24) }
            ) {
              Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolbarGrip(vertical = false, modifier = Modifier.size(16.dp, 22.dp))
                Spacer(Modifier.width(6.dp))
                Text("Move toolbar", fontSize = 13.sp, color = EditorChrome.text)
              }
            }
          }
        } else EditorPart {
        // 2. TRANSPARENT TOP APP BAR (Matches real_myscript_screen1.png)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .padding(horizontal = 14.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Left: Back arrow + Title ("Untitled") + Edit Pencil
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Back to notes" }
                .clickable {
                  persistCurrentNote()
                  currentScreen = AppScreen.HOME_NOTES
                }
            ) {
              MyScriptIcons.BackArrow(Color(0xFF1F1F1F), 20.dp)
            }

            Text(
              text = noteTitle,
              fontSize = 19.sp,
              fontWeight = FontWeight.Normal,
              color = Color(0xFF1F1F1F)
            )

            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Rename note" }
                .clickable { showRenameDialog = true }
            ) {
              MyScriptIcons.EditPencil(Color(0xFF1F1F1F), 17.dp)
            }
          }

          // Right: Undo, Redo, Preferences Sliders, More 3-dots
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Undo" }
                .clickable(enabled = history.canUndo) {
                  if (history.undo(strokes)) persistCurrentNote()
                }
            ) {
              MyScriptIcons.UndoArrow(
                color = if (history.canUndo) Color(0xFF1F1F1F) else Color(0xFFA8A8A8),
                size = 20.dp
              )
            }

            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Redo" }
                .clickable(enabled = history.canRedo) {
                  if (history.redo(strokes)) persistCurrentNote()
                }
            ) {
              MyScriptIcons.RedoArrow(
                color = if (history.canRedo) Color(0xFF1F1F1F) else Color(0xFFA8A8A8),
                size = 20.dp
              )
            }

            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Page style" }
                .clickable { showPageStyleDialog = true }
            ) {
              MyScriptIcons.PreferencesSliders(Color(0xFF1F1F1F), 20.dp)
            }

            Box {
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(40.dp)
                  .clip(CircleShape)
                  .semantics { contentDescription = "More options" }
                  .clickable { showMoreMenu = true }
              ) {
                MyScriptIcons.MoreVerticalDots(Color(0xFF1F1F1F), 20.dp)
              }

              UiMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false }
              ) {
                UiMenuItem(
                  text = { Text("Solve step by step") },
                  onClick = {
                    showMoreMenu = false
                    runOcrAndSolve(openStepsCard = true)
                  }
                )
                UiMenuItem(
                  text = { Text(if (showInlinePlotter) "Hide graph" else "Show graph") },
                  onClick = {
                    showMoreMenu = false
                    showInlinePlotter = !showInlinePlotter
                  }
                )
                UiMenuItem(
                  text = { Text("Page style") },
                  onClick = {
                    showMoreMenu = false
                    showPageStyleDialog = true
                  }
                )
                UiMenuItem(
                  text = { Text("Rename") },
                  onClick = {
                    showMoreMenu = false
                    showRenameDialog = true
                  }
                )
                UiMenuItem(
                  text = { Text("Show S Pen quick menu") },
                  onClick = {
                    showMoreMenu = false
                    SpenHoverBus.radialMenuVisible = !SpenHoverBus.radialMenuVisible
                  }
                )
                HorizontalDivider()
                UiMenuItem(
                  text = { Text("Clear page") },
                  onClick = {
                    showMoreMenu = false
                    history.checkpoint(strokes)
                    strokes.clear()
                    recognizedLatex = ""
                    clearLasso()
                    showStepByStepCard = false
                    persistCurrentNote()
                  }
                )
                HorizontalDivider()
                UiMenuItem(
                  text = { Text("Settings") },
                  onClick = {
                    showMoreMenu = false
                    persistCurrentNote()
                    openSettings()
                  }
                )
              }
            }
          }
        }

        // 3. LEFT VERTICAL FLOATING PILL TOOLBAR (Exact match to real_myscript_screen1.png)
        Row(
          modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            shadowElevation = 5.dp,
            modifier = Modifier.width(44.dp)
          ) {
            Column(
              modifier = Modifier.padding(vertical = 8.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              // 1. Pen
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(if (selectedTool == MyScriptTool.PEN) Color(0xFFDCDCDC) else Color.Transparent)
                  .semantics { contentDescription = "Pen" }
                  .clickable { selectedTool = MyScriptTool.PEN }
              ) {
                MyScriptIcons.EditPencil(Color(0xFF1F1F1F), 18.dp)
              }

              // 2. Lasso: circle ink to select it
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(if (selectedTool == MyScriptTool.LASSO) Color(0xFFDCDCDC) else Color.Transparent)
                  .semantics { contentDescription = "Lasso select" }
                  .clickable(onClickLabel = "Lasso select") { selectedTool = MyScriptTool.LASSO }
              ) {
                MyScriptIcons.LassoLoop(Color(0xFF1F1F1F), 19.dp)
              }

              // 3. Eraser
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(if (selectedTool == MyScriptTool.ERASER) Color(0xFFDCDCDC) else Color.Transparent)
                  .semantics { contentDescription = "Eraser" }
                  .clickable { selectedTool = MyScriptTool.ERASER }
              ) {
                MyScriptIcons.EraserBlock(Color(0xFF1F1F1F), 19.dp)
              }

              // 4. Laser Pointer
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(if (selectedTool == MyScriptTool.LASER) Color(0xFFDCDCDC) else Color.Transparent)
                  .semantics { contentDescription = "Laser pointer" }
                  .clickable { selectedTool = MyScriptTool.LASER }
              ) {
                MyScriptIcons.LaserWand(Color(0xFF1F1F1F), 19.dp)
              }

              // 5. Image / Plotter Frame
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(if (showInlinePlotter) Color(0xFFDCDCDC) else Color.Transparent)
                  .semantics { contentDescription = "Show graph" }
                  .clickable { showInlinePlotter = !showInlinePlotter }
              ) {
                MyScriptIcons.ImageFrame(Color(0xFF1F1F1F), 18.dp)
              }

              HorizontalDivider(
                modifier = Modifier.width(26.dp).padding(vertical = 2.dp),
                color = Color(0xFFE0E0E0)
              )

              // 6. Active Color Circle
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .clickable { showColorPickerPopup = !showColorPickerPopup }
              ) {
                Box(
                  modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(selectedPenColor))
                )
              }

              HorizontalDivider(
                modifier = Modifier.width(26.dp).padding(vertical = 2.dp),
                color = Color(0xFFE0E0E0)
              )

              // 7, 8, 9: 3 Stroke Thickness Dots (Small, Medium, Large)
              val sizes = listOf(5.5f to 5.dp, 8.5f to 8.dp, 13.0f to 12.dp)
              sizes.forEach { (strokeW, dotDp) ->
                val isSelected = abs(selectedThickness - strokeW) < 0.5f
                Box(
                  contentAlignment = Alignment.Center,
                  modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFFDCDCDC) else Color.Transparent)
                    .clickable { selectedThickness = strokeW }
                ) {
                  Box(
                    modifier = Modifier
                      .size(dotDp)
                      .clip(CircleShape)
                      .background(Color(0xFF1A1A1A))
                  )
                }
              }
            }
          }

          // Optional Color Picker Popover next to the Left Pill Toolbar
          if (showColorPickerPopup) {
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = Color.White,
              shadowElevation = 6.dp
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                listOf(
                  0xFF1A1A1AL,
                  0xFF1E88E5L,
                  0xFFD81B60L,
                  0xFF00897BL,
                  0xFF8E24AAL,
                  0xFFF4511EL
                ).forEach { colHex ->
                  Box(
                    modifier = Modifier
                      .size(24.dp)
                      .clip(CircleShape)
                      .background(Color(colHex))
                      .border(
                        width = if (selectedPenColor == colHex) 2.dp else 0.dp,
                        color = Color(0xFF1A73E8),
                        shape = CircleShape
                      )
                      .clickable {
                        selectedPenColor = colHex
                        selectedTool = MyScriptTool.PEN
                        showColorPickerPopup = false
                      }
                  )
                }
              }
            }
          }
        }
        } // end else (M3E top bar + left pill)
        }
        EditorPart {

        // 4. BOTTOM-LEFT FLOATING "100% + Unlock" PILL (Matches real_myscript_screen1.png)
        Surface(
          shape = RoundedCornerShape(24.dp),
          color = EditorChrome.bar,
          shadowElevation = 4.dp,
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 14.dp, bottom = 16.dp)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            // Tap the percentage to go back to 100%; the padlock locks panning and zooming
            Text(
              text = "${canvasView.percent}%",
              fontSize = 13.sp,
              fontWeight = FontWeight.Medium,
              color = EditorChrome.text,
              modifier = Modifier
                .clickable(onClickLabel = "Reset zoom to 100%") { canvasView = CanvasView() }
            )
            Box(
              modifier = Modifier
                .clip(CircleShape)
                .clickable(onClickLabel = if (canvasLocked) "Unlock canvas" else "Lock canvas") { canvasLocked = !canvasLocked }
            ) {
              MyScriptIcons.PadlockIcon(if (canvasLocked) OneUi9Tokens.SamsungBlue else Color(0xFF9A9AA0), 16.dp)
            }
          }
        }

        // Graphing calculator (⋮ menu), full screen over the editor. Starts with the note's function, if any.
        if (showCalculator) androidx.compose.ui.window.Dialog(
          onDismissRequest = { showCalculator = false },
          properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
          // Status bar icons readable over the calculator
          val dialogView = androidx.compose.ui.platform.LocalView.current
          androidx.compose.runtime.SideEffect {
            (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
              androidx.core.view.WindowCompat.getInsetsController(w, dialogView).isAppearanceLightStatusBars = !paperColor.isDark
            }
          }
          val start = remember(showCalculator) {
            symbolicSolution.plottableExpr?.takeIf { !symbolicSolution.isUnreadable }?.let {
              listOf(
                if (it.startsWith(Piecewise.PREFIX)) "y=" + Piecewise.toTyped(it)
                else "y=" + it.replace("*", "×").replace("sqrt", "√").replace(Regex("""\bpi\b"""), "π")
              )
            } ?: emptyList()
          }
          GraphingCalculatorScreen(
            dark = paperColor.isDark,
            initial = start,
            onClose = { showCalculator = false },
            onInsert = { bmp ->
              showCalculator = false
              scope.launch {
                val file = withContext(Dispatchers.IO) { NotePictures.save(context, bmp) }
                insertPicture(file, bmp)
                val saved = withContext(Dispatchers.IO) { NotePictures.saveToGallery(context, bmp, "graph_" + System.currentTimeMillis()) }
                Toast.makeText(context, if (saved) "Graph added to the note and saved to Gallery" else "Graph added to the note", Toast.LENGTH_SHORT).show()
              }
            }
          )
        }

        // Layers and Fill popups (under the toolbar, at the right / centre)
        val popupTopPx = with(androidx.compose.ui.platform.LocalDensity.current) { 104.dp.roundToPx() }
        if (showLayers) {
          androidx.compose.ui.window.Popup(
            alignment = Alignment.TopEnd,
            offset = androidx.compose.ui.unit.IntOffset(-popupTopPx / 4, popupTopPx),
            onDismissRequest = { showLayers = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
          ) {
            OneUiPopupSurface {
              LayersPanel(
                layers = noteLayers,
                active = activeLayer,
                onSelect = { activeLayer = it; clearLasso() },
                onChange = { l -> noteLayers = noteLayers.map { if (it.id == l.id) l else it }; persistCurrentNote() },
                onAdd = {
                  val id = (noteLayers.maxOfOrNull { it.id } ?: 0) + 1
                  noteLayers = noteLayers + NoteLayer(id, "Layer ${noteLayers.size}")
                  activeLayer = id
                  persistCurrentNote()
                },
                onDelete = { id ->
                  if (noteLayers.size > 1) {
                    history.checkpoint(strokes)
                    strokes.removeAll { it.layer == id }
                    noteLayers = noteLayers.filter { it.id != id }
                    if (activeLayer == id) activeLayer = noteLayers.last().id
                    clearLasso()
                    persistCurrentNote()
                  }
                },
                onMove = { id, up ->
                  val i = noteLayers.indexOfFirst { it.id == id }
                  val j = if (up) i + 1 else i - 1
                  if (i >= 0 && j in noteLayers.indices) {
                    noteLayers = noteLayers.toMutableList().also { l -> val tmp = l[i]; l[i] = l[j]; l[j] = tmp }
                    persistCurrentNote()
                  }
                }
              )
            }
          }
        }
        if (showFillSettings) {
          androidx.compose.ui.window.Popup(
            alignment = Alignment.TopCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, popupTopPx),
            onDismissRequest = { showFillSettings = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
          ) {
            OneUiPopupSurface {
              FillSettingsPanel(selectedPenColor, settings.fillOpacity) { settings.fillOpacity = it }
            }
          }
        }

        // Samsung colour menu for a toolbar colour (tap the selected colour)
        editingColorSlot?.let { slot ->
          val topPx = with(androidx.compose.ui.platform.LocalDensity.current) { 104.dp.roundToPx() }
          androidx.compose.ui.window.Popup(
            alignment = Alignment.TopCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, topPx),
            onDismissRequest = { editingColorSlot = null },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
          ) {
            OneUiPopupSurface {
              SamsungColorPicker(
                initial = settings.toolbarColors[slot],
                recent = settings.customColors,
                onDone = { c ->
                  settings.setToolbarColor(slot, c)
                  settings.addCustomColor(c)
                  selectedPenColor = c
                  editingColorSlot = null
                },
                onCancel = { editingColorSlot = null },
                onEyedropper = {
                  eyedropperSlot = slot
                  editingColorSlot = null
                  Toast.makeText(context, "Tap ink on the page to use its colour", Toast.LENGTH_SHORT).show()
                }
              )
            }
          }
        }

        // Samsung pen settings (tap the selected pen): pen type, size, line style, straighten, colours
        if (showPenSettings) {
          val topPx = with(androidx.compose.ui.platform.LocalDensity.current) { 104.dp.roundToPx() }
          androidx.compose.ui.window.Popup(
            alignment = Alignment.TopCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, topPx),
            onDismissRequest = { showPenSettings = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
          ) {
            OneUiPopupSurface {
              PenSettingsPanel(
                penType = selectedPenType,
                lineStyle = selectedLineStyle,
                width = selectedThickness,
                color = selectedPenColor,
                straighten = settings.autoShapes,
                customColors = settings.customColors,
                onPenType = { selectedPenType = it },
                onLineStyle = { selectedLineStyle = it },
                onWidth = { selectedThickness = it },
                onColor = { selectedPenColor = it },
                onStraighten = { settings.autoShapes = it },
                onAddCustomColor = { settings.addCustomColor(it) },
                modifier = Modifier.width(440.dp),
                stabilizer = settings.stabilizer,
                onStabilizer = { settings.stabilizer = it }
              )
            }
          }
        }

        // Highlighter, eraser and lasso panels, opened by tapping the selected tool again
        val toolPanelTop = with(androidx.compose.ui.platform.LocalDensity.current) { 104.dp.roundToPx() }
        @Composable
        fun ToolPanelPopup(onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp, content: @Composable (Modifier) -> Unit) {
          androidx.compose.ui.window.Popup(
            alignment = Alignment.TopCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, toolPanelTop),
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
          ) { OneUiPopupSurface { content(Modifier.width(width)) } }
        }
        if (showHighlighterSettings) ToolPanelPopup({ showHighlighterSettings = false }, 420.dp) { m ->
          HighlighterSettingsPanel(
            chisel = settings.highlighterChisel, size = settings.highlighterSize, color = settings.highlighterColor,
            customColors = settings.customColors,
            onChisel = { settings.highlighterChisel = it }, onSize = { settings.highlighterSize = it },
            onColor = { settings.highlighterColor = it }, onClose = { showHighlighterSettings = false }, modifier = m
          )
        }
        if (showEraserSettings) ToolPanelPopup({ showEraserSettings = false }, 400.dp) { m ->
          EraserSettingsPanel(
            area = settings.eraserArea, size = settings.eraserSize, highlighterOnly = settings.eraseHighlighterOnly,
            onArea = { settings.eraserArea = it }, onSize = { settings.eraserSize = it },
            onHighlighterOnly = { settings.eraseHighlighterOnly = it }, onClose = { showEraserSettings = false }, modifier = m
          )
        }
        if (showTapeSettings) ToolPanelPopup({ showTapeSettings = false }, 420.dp) { m ->
          val tapes = strokes.filter { it.isTape }
          TapeSettingsPanel(
            size = settings.tapeSize, color = settings.tapeColor, pattern = settings.tapePattern,
            straighten = settings.tapeStraighten,
            allPeeled = tapes.isNotEmpty() && tapes.all { t -> revealedTape.any { it === t } },
            onSize = { settings.tapeSize = it }, onColor = { settings.tapeColor = it },
            onPattern = { settings.tapePattern = it }, onStraighten = { settings.tapeStraighten = it },
            onTogglePeelAll = {
              if (tapes.isNotEmpty() && tapes.all { t -> revealedTape.any { it === t } }) revealedTape.clear()
              else { revealedTape.clear(); revealedTape.addAll(tapes) }
            },
            onRemoveAll = {
              if (tapes.isEmpty()) Toast.makeText(context, "There's no tape on this page", Toast.LENGTH_SHORT).show()
              else {
                history.checkpoint(strokes)
                strokes.removeAll { it.isTape }
                revealedTape.clear()
                persistCurrentNote()
              }
              showTapeSettings = false
            },
            onClose = { showTapeSettings = false }, modifier = m
          )
        }
        // AI assistant: a chat about the page, at the side on a tablet, filling the width on a phone
        if (showAssistant) {
          val cfg = androidx.compose.ui.platform.LocalConfiguration.current
          val wide = cfg.screenWidthDp >= 600
          val dens = androidx.compose.ui.platform.LocalDensity.current
          androidx.compose.ui.window.Popup(
            alignment = if (wide) Alignment.CenterEnd else Alignment.BottomCenter,
            offset = androidx.compose.ui.unit.IntOffset(if (wide) -with(dens) { 16.dp.roundToPx() } else 0, if (wide) with(dens) { 30.dp.roundToPx() } else -with(dens) { 12.dp.roundToPx() }),
            onDismissRequest = { showAssistant = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true, dismissOnClickOutside = false)
          ) {
            AssistantPanel(
              provider = settings.aiProvider,
              hasKey = remember(settings.aiProvider, showAssistant, currentScreen) { KeyVault.has(context, settings.aiProvider) },
              turns = aiTurns,
              busy = aiBusy,
              onAsk = { q ->
                val history = aiTurns.toList()
                aiTurns.add(AiTurn(true, q))
                aiBusy = true
                // The whole page as it is now (every visible layer), and what the app read on it
                val page = strokes.filter { layerVisible(it.layer) }
                scope.launch {
                  val answer = runCatching {
                    val img = if (page.isEmpty()) null else withContext(Dispatchers.Default) {
                      AiAssistant.pageImage(selectionPicture(page, paperColor.argb))
                    }
                    AiAssistant.ask(
                      context, settings.aiProvider,
                      if (settings.aiProvider == AiProvider.CLAUDE) settings.claudeModel else settings.geminiModel,
                      history, q, img, noteTitle, recognizedLatex
                    )
                  }.getOrElse { e -> "⚠ " + (e.message ?: "Something went wrong") }
                  aiTurns.add(AiTurn(false, answer))
                  aiBusy = false
                }
              },
              onSwitchProvider = { settings.aiProvider = it },
              onClear = { aiTurns.clear() },
              onOpenSettings = { showAssistant = false; persistCurrentNote(); openSettings(SettingsSection.AI) },
              onClose = { showAssistant = false },
              modifier = if (wide) Modifier.width(420.dp).height((cfg.screenHeightDp * 0.78f).dp)
                else Modifier.fillMaxWidth().padding(horizontal = 10.dp).height((cfg.screenHeightDp * 0.62f).dp)
            )
          }
        }
        if (showLaserSettings) ToolPanelPopup({ showLaserSettings = false }, 420.dp) { m ->
          LaserSettingsPanel(settings.laserColor, onColor = { settings.laserColor = it }, onClose = { showLaserSettings = false }, modifier = m)
        }
        if (showLassoSettings) ToolPanelPopup({ showLassoSettings = false }, 420.dp) { m ->
          LassoSettingsPanel(
            rectangle = settings.lassoRectangle, partial = settings.lassoPartial,
            onRectangle = { settings.lassoRectangle = it }, onPartial = { settings.lassoPartial = it },
            onClose = { showLassoSettings = false }, modifier = m
          )
        }

        // Phone toolbar status (this device is the canvas)
        if (RemoteToolbarLink.role == RemoteToolbarLink.Role.CANVAS) {
          Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (RemoteToolbarLink.connected) OneUi9Tokens.RelatedCardBg else EditorChrome.bar,
            shadowElevation = 4.dp,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .padding(start = 150.dp, bottom = 16.dp)
          ) {
            Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(
                if (RemoteToolbarLink.connected) "Phone toolbar: ${RemoteToolbarLink.peerName}" else "Phone toolbar: waiting for your phone…",
                fontSize = 13.sp,
                color = if (RemoteToolbarLink.connected) OneUi9Tokens.SamsungDeepBlue else EditorChrome.secondaryText
              )
              Box(
                Modifier.padding(start = 6.dp).size(30.dp).clip(CircleShape).clickable(onClickLabel = "Stop phone toolbar") { RemoteToolbarLink.stop() },
                contentAlignment = Alignment.Center
              ) { MyScriptIcons.CloseX(OneUi9Tokens.SecondaryText, 14.dp) }
            }
          }
        }
        }
        EditorPart {

        // 5. FLOATING INTERACTIVE GRAPH PLOTTER FRAME ON CANVAS (When toggled)
        if (showInlinePlotter && symbolicSolution.plottableExpr != null) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 6.dp,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(bottom = 20.dp)
              .width(420.dp)
              .height(230.dp)
              .border(1.5.dp, Color(0xFF3993A0), RoundedCornerShape(16.dp))
          ) {
            Box(modifier = Modifier.fillMaxSize()) {
              FunctionPlotCanvas(
                expr = symbolicSolution.plottableExpr!!,
                curveColor = Color(graphColor.colorHex),
                modifier = Modifier.fillMaxSize()
              )
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .align(Alignment.TopCenter)
                  .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = symbolicSolution.normalizedMath,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Serif,
                  fontStyle = FontStyle.Italic,
                  color = Color(0xFF424242),
                  modifier = Modifier
                    .background(Color(0xDDFFFFFF), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Box(Modifier.clip(CircleShape).clickable { showInlinePlotter = false }.padding(4.dp)) {
                  MyScriptIcons.CloseX(color = Color(0xFF424242), size = 14.dp)
                }
              }
            }
          }
        }

        // Long-pressed picture: drag to move, corners to resize, menu for crop / copy / cut / delete / share
        LaunchedEffect(currentScreen) { if (currentScreen != AppScreen.EDITOR) selectedPicture = null }
        selectedPicture?.takeIf { sel -> strokes.any { it === sel } }?.let { sel -> Box(Modifier.fillMaxSize().clipToBounds()) {
          fun replace(new: InkStroke) {
            val i = strokes.indexOfFirst { it === selectedPicture }
            if (i >= 0) { strokes[i] = new; selectedPicture = new }
          }
          PictureSelectionOverlay(
            rect = canvasView.toScreen(PictureOps.bounds(sel)),
            dark = paperColor.isDark,
            onEditStart = { history.checkpoint(strokes) },
            onMove = { dx, dy ->
              val s = selectedPicture ?: return@PictureSelectionOverlay
              replace(PictureOps.withBounds(s, PictureOps.bounds(s).translate(dx / canvasView.scale, dy / canvasView.scale)))
            },
            onResize = { r ->
              val s = selectedPicture ?: return@PictureSelectionOverlay
              replace(PictureOps.withBounds(s, androidx.compose.ui.geometry.Rect(canvasView.toPage(r.topLeft), canvasView.toPage(r.bottomRight))))
            },
            onEditEnd = { persistCurrentNote() },
            onCrop = { frac ->
              val s = selectedPicture ?: return@PictureSelectionOverlay
              scope.launch {
                val cropped = withContext(Dispatchers.IO) { PictureOps.crop(context, s, frac) }
                if (cropped == null) Toast.makeText(context, "Couldn't crop this picture", Toast.LENGTH_SHORT).show()
                else { history.checkpoint(strokes); replace(cropped); persistCurrentNote() }
              }
            },
            onCopy = {
              selectedPicture?.let { s ->
                inkClipboard = listOf(s)
                PictureOps.copyToClipboard(context, s)
                Toast.makeText(context, "Copied. Tap empty space with the lasso to paste.", Toast.LENGTH_SHORT).show()
              }
              selectedPicture = null
            },
            onCut = {
              selectedPicture?.let { s ->
                inkClipboard = listOf(s)
                PictureOps.copyToClipboard(context, s)
                history.checkpoint(strokes)
                strokes.removeAll { it === s }
                persistCurrentNote()
              }
              selectedPicture = null
            },
            onDelete = {
              selectedPicture?.let { s ->
                history.checkpoint(strokes)
                strokes.removeAll { it === s }
                persistCurrentNote()
              }
              selectedPicture = null
            },
            onShare = { selectedPicture?.let { PictureOps.share(context, it) } }
          )
        } }
        }
        EditorPart {

        // 6. LASSO SELECTION, as in Samsung Notes: a blue box with resize and rotate handles, and
        // a white menu above it (✦ Solve, Cut, Copy, Delete, Change style, ⋮). Paste after tapping empty space.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val menuGapPx = with(density) { 72.dp.toPx() }
        val edgePx = with(density) { 12.dp.toPx() }
        val selectedForMenu = strokes.filter { s -> lassoSelection.any { it === s } }
        // Selection bounds on screen (ink is in page coordinates)
        val selScreen = LassoTools.bounds(selectedForMenu)?.let { canvasView.toScreen(it) }
        if (selScreen != null && selectedTool == MyScriptTool.LASSO) {
          // Applies a change to every selected stroke and keeps them selected
          fun transformSelection(f: (InkStroke) -> InkStroke) {
            val moved = ArrayList<InkStroke>()
            for (i in strokes.indices) {
              val s = strokes[i]
              if (lassoSelection.any { it === s }) { val m = f(s); strokes[i] = m; moved += m }
            }
            lassoSelection.clear(); lassoSelection.addAll(moved)
          }
          // The box turns with the selection (its angle so far); on screen, corners in order
          val corners = LassoTools.orientedBox(selectedForMenu, lassoAngle)?.map { canvasView.toScreen(it) }
          if (corners != null) LassoSelectionOverlay(
            corners = corners,
            angle = lassoAngle,
            onStart = { history.checkpoint(strokes); lassoTransforming = true },
            onScale = { k, pivot ->
              val p = canvasView.toPage(pivot)
              transformSelection { LassoTools.scale(it, k, p.x, p.y) }
            },
            onStretch = { k, axis, pivot ->
              val p = canvasView.toPage(pivot)
              transformSelection { LassoTools.stretch(it, k, axis.x, axis.y, p.x, p.y) }
            },
            onRotate = { a, centre ->
              val c = canvasView.toPage(centre)
              transformSelection { LassoTools.rotate(it, a, c.x, c.y) }
              lassoAngle += a
            },
            onEnd = { lassoTransforming = false; persistCurrentNote() }
          )
        }
        // Replaces the selected handwriting with typed text (Convert to text / maths)
        fun convertSelection(maths: Boolean) {
          val ink = selectedForMenu.filter { it.isInk }
          val area = LassoTools.bounds(ink)
          if (ink.isEmpty() || area == null) { Toast.makeText(context, "Select some handwriting first", Toast.LENGTH_SHORT).show(); return }
          if (maths && !onnxReady) { Toast.makeText(context, "Maths recognition is still loading", Toast.LENGTH_SHORT).show(); return }
          scope.launch {
            isRunningOcr = true
            val text = try {
              if (maths) recognizeInk(ink)?.let { NanoText.latexToText(it) }
              else runCatching { InkTextConvert.recognizeText(ink) }.onFailure {
                Toast.makeText(context, "Couldn't get the handwriting model (it downloads once, needs internet)", Toast.LENGTH_LONG).show()
              }.getOrNull()
            } finally { isRunningOcr = false }
            if (text.isNullOrBlank()) { Toast.makeText(context, "Couldn't read that handwriting", Toast.LENGTH_SHORT).show(); return@launch }
            val colour = ink.groupingBy { it.colorHex }.eachCount().maxBy { it.value }.key
            val obj = InkTextConvert.textObject(text, area, colour, ink.first().layer, maths) ?: return@launch
            history.checkpoint(strokes)
            val at = strokes.indexOfFirst { s -> ink.any { it === s } }.coerceAtLeast(0)
            strokes.removeAll { s -> ink.any { it === s } }
            strokes.add(at.coerceAtMost(strokes.size), obj)
            lassoSelection.clear(); lassoSelection.add(obj); lassoAngle = 0f
            persistCurrentNote()
          }
        }
        if (selScreen != null && selectedTool == MyScriptTool.LASSO && lassoDragLast == null && !lassoTransforming && styleOriginal == null) {
          LassoActionMenu(selScreen, LassoActions(
            solve = { runOcrAndSolve(openStepsCard = true, only = selectedForMenu) },
            copy = {
              inkClipboard = selectedForMenu.toList()
              Toast.makeText(context, "Copied. Tap empty space with the lasso to paste.", Toast.LENGTH_SHORT).show()
              clearLasso()
            },
            cut = {
              inkClipboard = selectedForMenu.toList()
              history.checkpoint(strokes)
              strokes.removeAll { s -> selectedForMenu.any { it === s } }
              clearLasso()
              persistCurrentNote()
            },
            delete = {
              history.checkpoint(strokes)
              strokes.removeAll { s -> selectedForMenu.any { it === s } }
              clearLasso()
              showStepByStepCard = false
              persistCurrentNote()
            },
            changeStyle = {
              // Opens the panel; changes show on the selection straight away (Cancel puts it back)
              val ink = selectedForMenu.filter { it.isInk }
              history.checkpoint(strokes)
              styleOriginal = selectedForMenu
              styleStep = if (ink.isEmpty()) 30 else widthToSlider(ink.map { it.width }.sorted()[ink.size / 2])
              styleColor = selectedForMenu.filter { it.picture == null }.map { it.colorHex or 0xFF000000L }.distinct().singleOrNull()
            },
            convertToText = { convertSelection(maths = false) },
            convertToMaths = { convertSelection(maths = true) },
            alignHandwriting = {
              val pairs = LassoTools.alignHandwriting(selectedForMenu)
              if (pairs.isEmpty()) Toast.makeText(context, "Select some handwriting first", Toast.LENGTH_SHORT).show()
              else {
                history.checkpoint(strokes)
                for ((old, new) in pairs) {
                  val i = strokes.indexOfFirst { it === old }
                  if (i >= 0) strokes[i] = new
                  val j = lassoSelection.indexOfFirst { it === old }
                  if (j >= 0) lassoSelection[j] = new
                }
                lassoAngle = 0f
                persistCurrentNote()
              }
            },
            // Move forwards / backwards: to the layer above or below (layers go bottom to top from Layer 0)
            moveForwards = layerStepFor(selectedForMenu, +1)?.let { target -> { moveSelectionToLayer(selectedForMenu, target) } },
            moveBackwards = layerStepFor(selectedForMenu, -1)?.let { target -> { moveSelectionToLayer(selectedForMenu, target) } },
            clipboard = {
              // As a picture on the phone's clipboard (to paste in other apps), and as ink here
              inkClipboard = selectedForMenu.toList()
              val sel = selectedForMenu.toList()
              scope.launch {
                val ok = withContext(Dispatchers.IO) {
                  runCatching {
                    val file = NotePictures.save(context, selectionPicture(sel, paperColor.argb))
                    PictureOps.copyToClipboard(context, InkStroke(listOf(TimedPoint(0f, 0f, 0), TimedPoint(1f, 1f, 0)), picture = NotePicture(file.absolutePath)))
                  }.isSuccess
                }
                Toast.makeText(context, if (ok) "Copied to the clipboard as a picture" else "Couldn't copy to the clipboard", Toast.LENGTH_SHORT).show()
              }
            },
            duplicate = {
              // A copy just below and to the right, selected so it can be moved straight away
              val off = 24f / canvasView.scale
              val copies = selectedForMenu.map { LassoTools.translate(it, off, off) }
              history.checkpoint(strokes)
              strokes.addAll(copies)
              lassoSelection.clear(); lassoSelection.addAll(copies)
              persistCurrentNote()
            },
            cleanUp = {
              val ink = selectedForMenu.filter { it.isInk }
              if (ink.isNotEmpty()) replaceStrokes(ink) { InkTools.cleanUp(it) }
            },
            graph = if (selectedForMenu.any { it.isInk }) ({ graphSelection(selectedForMenu) }) else null
          ))
        }
        // Change style: size and colour, shown on the selection as you change them
        styleOriginal?.let { original ->
          fun restyle() {
            var k = 0
            val now = ArrayList<InkStroke>()
            for (i in strokes.indices) {
              if (k >= original.size) break
              val s = strokes[i]
              if (lassoSelection.any { it === s }) {
                val o = original[k++]
                val n = o.copy(
                  colorHex = styleColor?.let { c -> if (o.isHighlighter) (o.colorHex and 0xFF000000L) or (c and 0xFFFFFFL) else c } ?: o.colorHex,
                  width = if (o.isInk) sliderToWidth(styleStep) else o.width
                )
                strokes[i] = n; now += n
              }
            }
            lassoSelection.clear(); lassoSelection.addAll(now)
          }
          androidx.compose.ui.window.Popup(
            alignment = Alignment.BottomCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 24.dp.roundToPx() }),
            onDismissRequest = { styleOriginal = null; persistCurrentNote() },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
          ) {
            OneUiPopupSurface {
              ChangeStylePanel(
                step = styleStep, color = styleColor, customColors = settings.customColors,
                onStep = { styleStep = it; restyle() },
                onColor = { styleColor = it; restyle() },
                onAddCustomColor = { settings.addCustomColor(it) },
                onCancel = {
                  // The checkpoint taken on opening holds the selection as it was
                  history.undo(strokes)
                  lassoSelection.clear(); lassoSelection.addAll(original)
                  styleOriginal = null
                },
                onDone = { styleOriginal = null; persistCurrentNote() },
                modifier = Modifier.widthIn(max = 460.dp)
              )
            }
          }
        }
        val anchor = pasteAnchor
        val anchorOnScreen = anchor?.let { canvasView.toScreen(it) }
        if (anchor != null && anchorOnScreen != null && inkClipboard.isNotEmpty() && selectedTool == MyScriptTool.LASSO) {
          LassoPasteMenu(
            onPaste = {
              val b = LassoTools.bounds(inkClipboard)
              if (b != null) {
                val pasted = inkClipboard.map { LassoTools.translate(it, anchor.x - b.left, anchor.y - b.top) }
                history.checkpoint(strokes)
                strokes.addAll(pasted)
                lassoSelection.clear()
                lassoSelection.addAll(pasted)
                persistCurrentNote()
              }
              pasteAnchor = null
            },
            modifier = Modifier.offset {
              androidx.compose.ui.unit.IntOffset(anchorOnScreen.x.toInt(), (anchorOnScreen.y - menuGapPx).coerceAtLeast(edgePx).toInt())
            }
          )
        }

        // The eraser's circle: follows the pen while it hovers and rubs out, the size it erases.
        // Read only while drawing, so moving it redraws just this little layer.
        Canvas(Modifier.fillMaxSize()) {
          // Only while rubbing out (the pen touching); while it hovers, the pen's own pointer is the circle
          val c = eraserCursor ?: return@Canvas
          val r = if (settings.eraserArea) 6f + settings.eraserSize * 6f else 36f
          drawCircle(Color(0x1A000000), radius = r, center = c)
          drawCircle(Color.White.copy(alpha = 0.85f), radius = r, center = c, style = Stroke(width = 3.5f))
          drawCircle(Color(0xFF6E6E76), radius = r, center = c, style = Stroke(width = 1.6f))
        }
        // Eraser cursor while the side button is held over the page (same radius as eraseAt)
        // (Holding the side button over the page: the pen's pointer turns into the eraser circle, see eraserPointerIcon)

        // 6B. S Pen quick menu (short press of the side button while hovering)
        run {
          SpenHoverRadialMenu(
            visible = SpenHoverBus.radialMenuVisible,
            centerOffset = SpenHoverBus.radialMenuCenter,
            selectedTool = selectedTool,
            selectedPenColor = selectedPenColor,
            selectedThickness = selectedThickness,
            onUndo = {
              if (history.undo(strokes)) persistCurrentNote()
            },
            onRedo = {
              if (history.redo(strokes)) persistCurrentNote()
            },
            onCycleThickness = {
              selectedThickness = when {
                selectedThickness < 7f -> 8.5f
                selectedThickness < 11f -> 14.0f
                else -> 5.0f
              }
            },
            onCycleColor = {
              val palette = listOf(
                0xFF0A0A0CL,
                0xFF88C8BAL,
                0xFFFFEB3BL,
                0xFFA4BDFCL,
                0xFFB388FFL
              )
              val curIdx = palette.indexOf(selectedPenColor)
              selectedPenColor = palette[(curIdx + 1) % palette.size]
              selectedTool = MyScriptTool.PEN
            },
            onSelectEraser = {
              selectedTool = MyScriptTool.ERASER
              SpenHoverBus.radialMenuVisible = false
            },
            onSelectPen = {
              selectedTool = MyScriptTool.PEN
              SpenHoverBus.radialMenuVisible = false
            },
            onTriggerSparkleSolve = {
              SpenHoverBus.radialMenuVisible = false
              runOcrAndSolve(openStepsCard = true)
            },
            onDismiss = { SpenHoverBus.radialMenuVisible = false }
          )
        }
        }
        EditorPart {

        // 7. BOTTOM-RIGHT FLOATING "Solution steps" CARD (One UI 9.0 vs M3E MyScript Classic)
        if (showStepByStepCard) {
          if (uiStyleMode == UiStyleMode.ONEUI_9) {
            OneUi9SolutionStepsCard(
              solution = symbolicSolution,
              ocrStatsText = "Pix2Text-MFR 1.5 ONNX + CAS (${symbolicSolution.elapsedMs} ms)",
              geminiExplanation = nanoExplanation,
              isGeminiRunning = isRunningNano,
              onAskGeminiNano = { askGeminiNanoTutor() },
              onClose = { showStepByStepCard = false },
              modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
            )
          } else {
            AuthenticMyScriptSolutionStepsCard(
              symbolicSolution = symbolicSolution,
              areAllExpanded = areAllStepsExpanded,
              isFinalAnswerExpanded = isFinalAnswerExpanded,
              nanoExplanation = nanoExplanation,
              isRunningNano = isRunningNano,
              onToggleExpandAll = { areAllStepsExpanded = !areAllStepsExpanded },
              onToggleFinalAnswer = { isFinalAnswerExpanded = !isFinalAnswerExpanded },
              onAskGeminiNano = { askGeminiNanoTutor() },
              onClose = { showStepByStepCard = false },
              modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .width(265.dp)
                .heightIn(max = 540.dp)
            )
          }
        }
        }
      }
    }

    AppScreen.HOME_NOTES, AppScreen.QUICK_LATEX -> {
      AppPart {
      if (uiStyleMode == UiStyleMode.ONEUI_9) {
        OneUi9NotesHomeScreen(
          notes = notesList,
          folders = foldersList,
          activeNavSection = if (currentScreen == AppScreen.QUICK_LATEX) "quick_latex" else activeHomeSection,
          onSelectNavSection = { sec ->
            if (sec == "quick_latex") {
              currentScreen = AppScreen.QUICK_LATEX
            } else {
              activeHomeSection = sec
              currentScreen = AppScreen.HOME_NOTES
            }
          },
          onOpenNote = { note -> switchToNote(note) },
          selectedNoteIds = selectedNoteIds.toSet(),
          selectionMode = selectionMode,
          onToggleSelectNote = { note -> toggleSelect(note) },
          onEnterSelectionMode = { selectionMode = true },
          onVisibleNotesChanged = { visibleNoteIds = it },
          selectionActions = selectionActions,
          onImport = { launchImport() },
          onCreateNewNote = { folderId ->
            val newNote = NotesRepository.createBlankNote(folderId, settings.defaultPaper)
            notesList.add(0, newNote)
            NotesRepository.saveNote(context, newNote)
            switchToNote(newNote)
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
            updateNotes(notesList.filter { it.folderId == folder.id }.map { it.id }) { it.copy(folderId = null) }
          },
          onOpenSettings = { section -> openSettings(section) },
          onSwitchToM3E = { settings.uiStyle = UiStyleMode.M3E },
          onUseAsToolbar = { startLink(RemoteToolbarLink.Role.TOOLBAR) },
          binCount = binEntries.size,
          binContent = { m ->
            BinPane(
              entries = binEntries,
              driveBackupOn = settings.driveBackup && account != null,
              onRestore = { e ->
                val local = NoteBin.restoreLocal(context, e.id)
                if (local != null) {
                  notesList.add(0, local)
                  refreshBin()
                  Toast.makeText(context, "Restored \"${local.title}\"", Toast.LENGTH_SHORT).show()
                  if (settings.driveBackup && account != null) syncNow()
                } else scope.launch {
                  // Only on Drive: download it
                  val note = GoogleDriveSyncManager.restoreFromCloud(context, e.id)
                  if (note != null) {
                    notesList.add(0, note)
                    refreshBin()
                    Toast.makeText(context, "Restored \"${note.title}\"", Toast.LENGTH_SHORT).show()
                  } else Toast.makeText(context, "Couldn't get it from Google Drive. Check you're online and signed in.", Toast.LENGTH_LONG).show()
                }
              },
              onDeleteForever = { e ->
                NoteBin.deleteForever(context, e.id)
                refreshBin()
                scope.launch { GoogleDriveSyncManager.deletePurgedNow(context) }
              },
              onEmptyBin = {
                binEntries.toList().forEach { NoteBin.deleteForever(context, it.id) }
                refreshBin()
                scope.launch { GoogleDriveSyncManager.deletePurgedNow(context) }
              },
              modifier = m
            )
          },
          quickLatexContent = {
            QuickLatexPad(
              penOnly = disableFingerWriting,
              recognize = { recognizeInk(it) },
              onCopyLatex = { copyLatex(it) },
              onOpenAsNote = { ink, latex -> openInkAsNote(ink, latex) },
              modifier = Modifier.fillMaxSize()
            )
          }
        )
      } else {
      // =========================================================================
      // SCREEN 4 & 5: EXACT MYSCRIPT HOME ("My notes" & "Quick LaTeX" + Left Nav Rail)
      // =========================================================================
      Row(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White)
          .statusBarsPadding()
          .navigationBarsPadding()
      ) {
        // Left Navigation Rail (Exact match to real_myscript_notes_home.png & real_myscript_nav_drawer.png)
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
            // Hamburger Toggle
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

            // Item 1: My notes
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.semantics { contentDescription = "My notes" }.clickable { currentScreen = AppScreen.HOME_NOTES }
            ) {
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(width = 52.dp, height = 42.dp)
                  .clip(RoundedCornerShape(21.dp))
                  .background(
                    if (currentScreen == AppScreen.HOME_NOTES) Color(0xFFFCE8E6) else Color.Transparent
                  )
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

            // Item 2: Quick LaTeX "{}"
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.clickable { currentScreen = AppScreen.QUICK_LATEX }
            ) {
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(width = 52.dp, height = 42.dp)
                  .clip(RoundedCornerShape(21.dp))
                  .background(
                    if (currentScreen == AppScreen.QUICK_LATEX) Color(0xFFFCE8E6) else Color.Transparent
                  )
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

          // Bottom Settings Gear
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics { contentDescription = "Settings" }.clickable(onClickLabel = "Settings") { openSettings() }
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

        // Right Main Content Area
        if (currentScreen == AppScreen.HOME_NOTES) {
          LaunchedEffect(notesList.size) { visibleNoteIds = notesList.map { it.id } }
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
                // Top-right 3 dots: Select / Import
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
                        onClick = { m3eHomeMenu = false; selectionMode = true }
                      )
                      UiMenuItem(
                        text = { Text("Import (Samsung Notes, .iink, PDF, PNG)") },
                        onClick = { m3eHomeMenu = false; launchImport() }
                      )
                      HorizontalDivider()
                      UiMenuItem(
                        text = { Text("Switch to One UI 9 design") },
                        onClick = { m3eHomeMenu = false; settings.uiStyle = UiStyleMode.ONEUI_9 }
                      )
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(16.dp))

              // Grid of Square Note Thumbnails (Exact match to real_myscript_notes_home.png)
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
                      onClick = { if (selectionMode) toggleSelect(note) else switchToNote(note) },
                      onLongClick = { toggleSelect(note) }
                    )
                  ) {
                    // Square 108dp x 108dp white thumbnail card with mini rendered strokes
                    Surface(
                      shape = RoundedCornerShape(18.dp),
                      color = if (isSelected) Color(0xFFFCE8E6) else Color.White,
                      shadowElevation = 2.dp,
                      modifier = Modifier.size(108.dp)
                    ) {
                      Box(Modifier.fillMaxSize()) {
                      note.backgroundImagePath?.let { NoteBackgroundThumb(it, Modifier.fillMaxSize().padding(8.dp)) }
                      Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        if (note.strokes.isNotEmpty()) {
                          val minX = note.strokes.flatMap { it.points }.minOfOrNull { it.x } ?: 0f
                          val maxX = note.strokes.flatMap { it.points }.maxOfOrNull { it.x } ?: 1f
                          val minY = note.strokes.flatMap { it.points }.minOfOrNull { it.y } ?: 0f
                          val maxY = note.strokes.flatMap { it.points }.maxOfOrNull { it.y } ?: 1f
                          val spanX = (maxX - minX).coerceAtLeast(60f)
                          val spanY = (maxY - minY).coerceAtLeast(60f)
                          val sc = min(size.width / spanX, size.height / spanY) * 0.85f
                          val offX = (size.width - spanX * sc) / 2f
                          val offY = (size.height - spanY * sc) / 2f
                          for (s in note.strokes) {
                            val pts = s.points
                            if (pts.size > 1) {
                              val p = Path().apply {
                                moveTo(offX + (pts[0].x - minX) * sc, offY + (pts[0].y - minY) * sc)
                                for (i in 1 until pts.size) {
                                  lineTo(offX + (pts[i].x - minX) * sc, offY + (pts[i].y - minY) * sc)
                                }
                              }
                              drawPath(p, Color(0xFF1A1A1A), style = Stroke(2.2f, cap = StrokeCap.Round))
                            }
                          }
                        }
                      }
                      if (isSelected) {
                        SelectedCheckBadge(
                          color = Color(0xFFEA5244),
                          modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        )
                      }
                      } // end thumbnail Box
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

            // Bottom-Right Coral-Red Rounded Square "+" FAB (Exact match to real_myscript_notes_home.png)
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
                  val newNote = NotesRepository.createBlankNote(currentFolderId, settings.defaultPaper)
                  notesList.add(0, newNote)
                  NotesRepository.saveNote(context, newNote)
                  switchToNote(newNote)
                }
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                MyScriptIcons.Plus(color = Color.White, size = 24.dp)
              }
            }
          }
        } else {
          // =========================================================================
          // SCREEN 5: EXACT MYSCRIPT "Quick LaTeX" SCREEN (Matches real_myscript_quick_latex.png)
          // =========================================================================
          QuickLatexPad(
            penOnly = disableFingerWriting,
            recognize = { recognizeInk(it) },
            onCopyLatex = { copyLatex(it) },
            onOpenAsNote = { ink, latex -> openInkAsNote(ink, latex) },
            modifier = Modifier.weight(1f).fillMaxHeight()
          )
        }
      }
      } // end else (M3E Home Notes & Quick LaTeX)
      }
    }

    AppScreen.SETTINGS -> AppPart {
      val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
      }
      SettingsScreen(
        settings = settings,
        section = settingsSection,
        onSectionChange = { settingsSection = it },
        account = account,
        isSyncing = isSyncingDrive,
        onSignIn = { signIn() },
        onSignOut = {
          scope.launch {
            GoogleDriveSyncManager.signOut(context)
            account = null
            settings.driveBackup = false
            settings.lastSyncStatus = null
          }
        },
        onSyncNow = { syncNow() },
        onBackupChange = { enabled ->
          settings.driveBackup = enabled
          if (enabled) {
            if (account == null) signIn() else syncNow()
          }
        },
        modelStatus = if (onnxReady) "Ready" else "Loading…",
        nanoStatus = nanoStatus,
        appVersion = appVersion,
        onClose = { currentScreen = settingsReturnScreen }
      )
    }
  }

  // Keyboard tool: type the maths instead of writing it
  if (showTypeMaths) {
    var typed by remember { mutableStateOf(recognizedLatex) }
    fun solveTyped() {
      showTypeMaths = false
      if (typed.isNotBlank()) {
        recognizedLatex = typed.trim()
        symbolicSolution = SymbolicStepEngine.solveFromLatex(recognizedLatex)
        showStepByStepCard = true
        persistCurrentNote()
      }
    }
    OneUiDialog(
      title = "Type maths",
      onDismiss = { showTypeMaths = false },
      actions = listOf("Cancel" to { showTypeMaths = false }, "Solve" to { solveTyped() })
    ) {
      Text("Type an expression or equation in LaTeX or plain text, for example x^2-4=0 or \\int x e^x dx.", fontSize = 14.sp, color = EditorChrome.secondaryText)
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, fontFamily = FontFamily.Monospace),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { solveTyped() }),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
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
      onDismiss = { inkTextResult = null },
      actions = listOf(
        "Copy" to { copyLatex(if (isHelp) latex else readable); inkTextResult = null },
        "Solve" to { inkTextResult = null; recognizedLatex = latex; symbolicSolution = SymbolicStepEngine.solveFromLatex(latex); showStepByStepCard = true; persistCurrentNote() },
        "Close" to { inkTextResult = null }
      )
    ) {
      Text(if (isHelp) "Your handwriting reads as:" else "Converted to text:", fontSize = 14.sp, color = EditorChrome.secondaryText)
      Spacer(Modifier.height(10.dp))
      Text(readable, fontSize = 24.sp, fontFamily = FontFamily.Serif, color = EditorChrome.text)
      Spacer(Modifier.height(8.dp))
      Text(latex, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = EditorChrome.secondaryText)
      if (isHelp) {
        Spacer(Modifier.height(10.dp))
        Text("If a symbol is wrong, write it again more clearly, or use the Keyboard tool to type it.", fontSize = 13.sp, color = EditorChrome.secondaryText)
      }
    }
  }

  // This phone as the toolbar of a tablet / Googlebook
  if (showPhoneToolbar) {
    androidx.compose.ui.window.Dialog(
      onDismissRequest = { RemoteToolbarLink.stop(); showPhoneToolbar = false },
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
      // Status bar icons readable over the toolbar (dark icons on the light toolbar, light on dark)
      val toolbarDark = RemoteToolbarLink.remoteState?.dark == true
      val dialogView = androidx.compose.ui.platform.LocalView.current
      SideEffect {
        (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
          androidx.core.view.WindowCompat.getInsetsController(w, dialogView).isAppearanceLightStatusBars = !toolbarDark
        }
      }
      PhoneToolbarScreen(
        // Closing ends the link (the tablet sees it), instead of hiding the toolbar while still connected.
        // The phone listens again the next time the app is opened.
        onClose = { RemoteToolbarLink.closeFromToolbar(); showPhoneToolbar = false },
        customColors = settings.customColors,
        onAddCustomColor = { settings.addCustomColor(it) }
      )
    }
  }

  // Customise toolbar (full screen, like Samsung Notes)
  if (showCustomiseToolbar) {
    androidx.compose.ui.window.Dialog(
      onDismissRequest = { showCustomiseToolbar = false },
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
      CustomiseToolbarScreen(
        layout = settings.toolbar,
        state = ToolbarState(selectedTool, selectedPenColor, selectedThickness, history.canUndo, history.canRedo, settings.autoShapes, canvasLocked),
        onChange = { settings.toolbar = it },
        onBack = { showCustomiseToolbar = false },
        dock = settings.toolbarDock,
        onDock = { settings.toolbarDock = it }
      )
    }
  }

  if (showPageStyleDialog) {
    PageStyleDialog(
      uiStyle = uiStyleMode,
      paper = paperPattern,
      isDefault = paperPattern == settings.defaultPaper,
      onSelectPaper = {
        paperPattern = it
        persistCurrentNote()
      },
      onMakeDefault = { settings.defaultPaper = paperPattern },
      onOpenSettings = {
        showPageStyleDialog = false
        persistCurrentNote()
        openSettings(SettingsSection.APPEARANCE)
      },
      onClose = { showPageStyleDialog = false },
      paperColor = paperColor,
      onSelectColor = {
        paperColor = it
        persistCurrentNote()
      },
      hasBackground = noteBackgroundPath != null,
      onPickBackground = { backgroundLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp", "application/pdf")) },
      onRemoveBackground = {
        noteBackgroundPath = null
        persistCurrentNote()
      }
    )
  }

  // ===================== Selection-mode dialogs =====================
  if (showExportDialog) {
    ExportFormatDialog(
      count = selectedNoteIds.size,
      onPick = { format ->
        showExportDialog = false
        if (selectedNoteIds.contains(currentNoteId)) persistCurrentNote()
        val notes = selectedNotes()
        if (notes.isNotEmpty()) {
          pendingExport = notes to format
          exportLauncher.launch(
            NoteImportExport.suggestedFileName(notes, format) to NoteImportExport.mimeFor(notes, format)
          )
        }
      },
      onDismiss = { showExportDialog = false }
    )
  }

  if (showMoveDialog) {
    MoveToFolderDialog(
      folders = foldersList,
      onPick = { folderId ->
        showMoveDialog = false
        updateNotes(selectedNoteIds) { it.copy(folderId = folderId) }
        exitSelection()
      },
      onDismiss = { showMoveDialog = false }
    )
  }

  if (showRenameSelectedDialog) {
    val target = selectedNotes().firstOrNull()
    if (target == null) {
      showRenameSelectedDialog = false
    } else {
      RenameNoteDialog(
        initial = target.title,
        onSave = { newTitle ->
          showRenameSelectedDialog = false
          updateNotes(listOf(target.id)) { it.copy(title = newTitle, updatedAt = System.currentTimeMillis()) }
          exitSelection()
        },
        onDismiss = { showRenameSelectedDialog = false }
      )
    }
  }

  if (showDeleteSelectedDialog) {
    ConfirmDeleteNotesDialog(
      count = selectedNoteIds.size,
      onConfirm = {
        showDeleteSelectedDialog = false
        val ids = selectedNoteIds.toSet()
        // To the bin (30 days), not gone: the open note's latest ink first
        if (currentNoteId in ids) persistCurrentNote()
        notesList.filter { it.id in ids }.forEach { NoteBin.moveToBin(context, it) }
        notesList.removeAll { it.id in ids }
        refreshBin()
        // With backup on, tell Drive now (so other devices bin it too and it leaves this device)
        if (settings.driveBackup && account != null) syncNow()
        if (notesList.isEmpty()) {
          val blank = NotesRepository.createBlankNote()
          NotesRepository.saveNote(context, blank)
          notesList.add(blank)
        }
        if (currentNoteId in ids) {
          // Load another note WITHOUT persisting the deleted one back to disk
          switchToNote(notesList.first(), persistPrevious = false, openEditor = false)
        }
        exitSelection()
      },
      onDismiss = { showDeleteSelectedDialog = false }
    )
  }

  // Rename Note Dialog
  if (showRenameDialog) {
    var tempTitle by remember { mutableStateOf(noteTitle) }
    UiAlertDialog(
      onDismissRequest = { showRenameDialog = false },
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
          noteTitle = tempTitle.ifBlank { "Untitled" }
          persistCurrentNote()
          showRenameDialog = false
        }) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = { showRenameDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}

/**
 * Runs [content] as a function of its own. The app's main composable is too large for Android
 * to compile (ART skips methods past its size limit, so they run slowly in the interpreter);
 * Box/Column/Row are inlined into their caller, so wrapping big parts here splits them out.
 * It emits straight into the parent: layout is unchanged.
 */
@Composable
private fun BoxScope.EditorPart(content: @Composable BoxScope.() -> Unit) = content()

/** Same as [EditorPart], for parts that aren't inside a Box. */
@Composable
private fun AppPart(content: @Composable () -> Unit) = content()
