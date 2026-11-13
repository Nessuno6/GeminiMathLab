package com.deang.geminimath

import android.accounts.Account
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class PaperPattern(val label: String) {
  GRID("Grid"),
  DOTS("Dots"),
  LINES("Lines"),
  BLANK("Blank"),
  /** Fine 5 mm-style graph paper with a bolder line every 5 squares. */
  GRAPH("Graph paper"),
  /** Grid with x and y axes through the page origin, for sketching functions. */
  AXES("Axes")
}

/** Page colour. On dark pages, black ink is shown as white (as Samsung Notes does), and stored unchanged. */
enum class PaperColor(val label: String, val argb: Long, val isDark: Boolean) {
  WHITE("White", 0xFFFFFFFFL, false),
  CREAM("Cream", 0xFFFBF6E9L, false),
  GREY("Grey", 0xFFEDEDEFL, false),
  BLACK("Black", 0xFF121214L, true)
}

enum class GraphColorTheme(val label: String, val colorHex: Long) {
  EMERALD("Emerald", 0xFF00897BL),
  COBALT("Cobalt", 0xFF1E88E5L),
  RASPBERRY("Raspberry", 0xFFD81B60L),
  PURPLE("Purple", 0xFF8E24AAL),
  ORANGE("Orange", 0xFFF4511EL)
}

data class NoteFolder(
  val id: String,
  val name: String,
  val colorHex: Long
)

data class MathNote(
  val id: String,
  val title: String,
  val updatedAt: Long,
  val paperPattern: PaperPattern,
  val graphColor: GraphColorTheme,
  val showInlinePlotter: Boolean,
  val recognizedLatex: String,
  val strokes: List<InkStroke>,
  val folderId: String? = null,
  val isFavourite: Boolean = false,
  /** Absolute path(s) of imported PDF page(s) / PNG image rendered underneath the ink (`;`-separated for multi-page). */
  val backgroundImagePath: String? = null,
  val paperColor: PaperColor = PaperColor.WHITE,
  val layers: List<NoteLayer> = listOf(NoteLayer(0, "Layer 0")),
  /** False when only the lightweight metadata sidecar is in memory and strokes are loaded on demand. */
  val strokesLoaded: Boolean = true,
  val strokeCountHint: Int = strokes.size
) {
  val strokeCount: Int get() = if (strokesLoaded) strokes.size else strokeCountHint
}

/**
 * Local JSON repository for math notes and user folders, compatible with Google Drive appDataFolder sync.
 */
object NotesRepository {
  private val legacySampleIds = setOf(
    "note_analisi_integrale",
    "note_algebra_fratta",
    "note_secondo_grado"
  )

  private fun notesDir(context: Context): File {
    val dir = File(context.filesDir, "myscript_style_notes")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  private fun metaFile(dir: File, noteId: String): File = File(dir, "$noteId.meta")

  private fun foldersFile(context: Context): File {
    return File(context.filesDir, "myscript_style_folders.json")
  }

  fun loadFolders(context: Context): List<NoteFolder> {
    val file = foldersFile(context)
    if (!file.exists()) {
      val defaults = listOf(
        NoteFolder("folder_calculus", "Calculus", 0xFFF5A623L),
        NoteFolder("folder_algebra", "Algebra", 0xFF387AFFL)
      )
      saveFolders(context, defaults)
      return defaults
    }
    return try {
      val arr = JSONArray(file.readText())
      val list = mutableListOf<NoteFolder>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        list.add(
          NoteFolder(
            id = obj.getString("id"),
            name = obj.getString("name"),
            colorHex = obj.optLong("colorHex", 0xFF387AFFL)
          )
        )
      }
      list
    } catch (_: Throwable) {
      emptyList()
    }
  }

  fun saveFolders(context: Context, folders: List<NoteFolder>) {
    val arr = JSONArray()
    for (f in folders) {
      val obj = JSONObject()
      obj.put("id", f.id)
      obj.put("name", f.name)
      obj.put("colorHex", f.colorHex)
      arr.put(obj)
    }
    foldersFile(context).writeText(arr.toString(2))
  }

  fun loadAllNotes(context: Context): List<MathNote> = loadNotesInternal(context, lazyStrokes = false)

  /**
   * Fast startup loader: reads lightweight `.meta` sidecars for the notes library and only parses
   * full stroke arrays for the most recently edited note (the one opened first in the editor).
   */
  fun loadAllNoteSummaries(context: Context): List<MathNote> = loadNotesInternal(context, lazyStrokes = true)

  private fun loadNotesInternal(context: Context, lazyStrokes: Boolean): List<MathNote> {
    val dir = notesDir(context)
    // Remove legacy pre-populated sample files so no fake sample equations appear
    legacySampleIds.forEach { sampleId ->
      val f = File(dir, "$sampleId.json")
      if (f.exists()) f.delete()
      val m = metaFile(dir, sampleId)
      if (m.exists()) m.delete()
    }

    val files = dir.listFiles { f -> f.extension == "json" }
    val loaded = files?.mapNotNull { file ->
      try {
        val idFromName = file.nameWithoutExtension
        pendingSaves[idFromName]?.let { return@mapNotNull it }
        val mf = metaFile(dir, idFromName)
        val note = if (lazyStrokes && mf.exists() && mf.lastModified() >= file.lastModified()) {
          parseNoteMetaJson(mf.readText())
        } else {
          parseNoteJson(file.readText()).also { parsed ->
            runCatching { mf.writeText(serializeNoteMetaJson(parsed).toString()) }
          }
        }
        // Only the old built-in sample notes are removed, matched by ID.
        if (note.id in legacySampleIds) {
          file.delete()
          mf.delete()
          null
        } else {
          note
        }
      } catch (_: Throwable) {
        null
      }
    }?.sortedByDescending { it.updatedAt } ?: emptyList()

    if (loaded.isEmpty()) {
      val blankNote = createBlankNote()
      saveNote(context, blankNote)
      return listOf(blankNote)
    }
    if (lazyStrokes && !loaded.first().strokesLoaded) {
      val firstHydrated = ensureStrokesLoaded(context, loaded.first())
      return listOf(firstHydrated) + loaded.drop(1)
    }
    return loaded
  }

  /** Loads the full strokes of [note] from disk (or pending memory save) if not yet hydrated. */
  fun ensureStrokesLoaded(context: Context, note: MathNote): MathNote {
    pendingSaves[note.id]?.let { if (it.strokesLoaded) return it }
    if (note.strokesLoaded) return note
    val file = File(notesDir(context), "${note.id}.json")
    if (!file.exists()) return note.copy(strokesLoaded = true)
    return runCatching { parseNoteJson(file.readText()) }.getOrDefault(note.copy(strokesLoaded = true))
  }

  fun createBlankNote(folderId: String? = null, paper: PaperPattern = PaperPattern.GRID): MathNote {
    return MathNote(
      id = "note_${System.currentTimeMillis()}",
      title = "Untitled",
      updatedAt = System.currentTimeMillis(),
      paperPattern = paper,
      graphColor = GraphColorTheme.EMERALD,
      showInlinePlotter = false,
      recognizedLatex = "",
      strokes = emptyList(),
      folderId = folderId,
      isFavourite = false
    )
  }

  fun saveNote(context: Context, note: MathNote) {
    val dir = notesDir(context)
    val hydrated = if (note.strokesLoaded) note else ensureStrokesLoaded(context, note).copy(
      title = note.title,
      updatedAt = note.updatedAt,
      paperPattern = note.paperPattern,
      graphColor = note.graphColor,
      showInlinePlotter = note.showInlinePlotter,
      recognizedLatex = note.recognizedLatex,
      folderId = note.folderId,
      isFavourite = note.isFavourite,
      backgroundImagePath = note.backgroundImagePath,
      paperColor = note.paperColor,
      layers = note.layers
    )
    val file = File(dir, "${hydrated.id}.json")
    // Written to a temp file first so a note is never left half-written; compact JSON is much
    // faster to write than indented JSON for notes with a lot of ink
    val tmp = File(file.parentFile, "${hydrated.id}.json.tmp")
    tmp.writeText(serializeNoteJson(hydrated).toString())
    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    runCatching { metaFile(dir, hydrated.id).writeText(serializeNoteMetaJson(hydrated).toString()) }
  }

  private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "note-saver").apply { priority = Thread.NORM_PRIORITY - 1 }
  }
  private val pendingSaves = java.util.concurrent.ConcurrentHashMap<String, MathNote>()

  /**
   * Saves [note] on a background thread, so writing never makes drawing or closing stutter.
   * Saves of the same note that pile up are merged: only the latest is written.
   */
  fun saveNoteAsync(context: Context, note: MathNote) {
    val app = context.applicationContext
    val alreadyQueued = pendingSaves.put(note.id, note) != null
    if (alreadyQueued) return // a save for this note is already queued and will pick up the latest
    saveExecutor.execute {
      // Wait a moment so a burst of changes (rubbing out with the eraser, dragging) is written once
      Thread.sleep(350)
      val latest = pendingSaves.remove(note.id) ?: return@execute
      runCatching { saveNote(app, latest) }
    }
  }

  fun deleteNote(context: Context, noteId: String) {
    val dir = notesDir(context)
    val file = File(dir, "$noteId.json")
    val mf = metaFile(dir, noteId)
    // A queued background save mustn't write the note back after it's deleted
    pendingSaves.remove(noteId)
    if (file.exists()) file.delete()
    if (mf.exists()) mf.delete()
    saveExecutor.execute {
      if (file.exists()) file.delete()
      if (mf.exists()) mf.delete()
    }
  }

  private fun serializeNoteMetaJson(note: MathNote): JSONObject {
    val root = JSONObject()
    root.put("id", note.id)
    root.put("title", note.title)
    root.put("updatedAt", note.updatedAt)
    root.put("paperPattern", note.paperPattern.name)
    root.put("graphColor", note.graphColor.name)
    root.put("showInlinePlotter", note.showInlinePlotter)
    root.put("recognizedLatex", note.recognizedLatex)
    if (note.folderId != null) root.put("folderId", note.folderId)
    root.put("isFavourite", note.isFavourite)
    if (note.backgroundImagePath != null) root.put("backgroundImagePath", note.backgroundImagePath)
    root.put("paperColor", note.paperColor.name)
    root.put("layers", JSONArray().apply {
      note.layers.forEach { l -> put(JSONObject().put("id", l.id).put("name", l.name).put("visible", l.visible).put("opacity", l.opacity.toDouble())) }
    })
    root.put("strokeCount", note.strokeCount)
    return root
  }

  private fun parseNoteMetaJson(jsonStr: String): MathNote {
    val obj = JSONObject(jsonStr)
    val id = obj.getString("id")
    val title = obj.optString("title", "Untitled")
    val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
    val paperPattern = runCatching { PaperPattern.valueOf(obj.optString("paperPattern", "GRID")) }.getOrDefault(PaperPattern.GRID)
    val graphColor = runCatching { GraphColorTheme.valueOf(obj.optString("graphColor", "EMERALD")) }.getOrDefault(GraphColorTheme.EMERALD)
    val showInlinePlotter = obj.optBoolean("showInlinePlotter", false)
    val recognizedLatex = obj.optString("recognizedLatex", "")
    val folderId = obj.optString("folderId", "").takeIf { it.isNotBlank() }
    val isFavourite = obj.optBoolean("isFavourite", false)
    val strokeCount = obj.optInt("strokeCount", 0)
    return MathNote(
      id = id,
      title = title,
      updatedAt = updatedAt,
      paperPattern = paperPattern,
      graphColor = graphColor,
      showInlinePlotter = showInlinePlotter,
      recognizedLatex = recognizedLatex,
      strokes = emptyList(),
      folderId = folderId,
      isFavourite = isFavourite,
      backgroundImagePath = obj.optString("backgroundImagePath", "").takeIf { it.isNotBlank() }?.let { localizeBackgroundPaths(it) },
      paperColor = runCatching { PaperColor.valueOf(obj.optString("paperColor", "WHITE")) }.getOrDefault(PaperColor.WHITE),
      layers = obj.optJSONArray("layers")?.let { arr ->
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { l -> NoteLayer(l.getInt("id"), l.optString("name", "Layer"), l.optBoolean("visible", true), l.optDouble("opacity", 1.0).toFloat()) } }
      }?.takeIf { it.isNotEmpty() }?.let { LayerNames.renumber(it) } ?: listOf(NoteLayer(0, "Layer 0")),
      strokesLoaded = strokeCount == 0,
      strokeCountHint = strokeCount
    )
  }

  private fun localizeBackgroundPaths(raw: String): String =
    raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(";") { NotePictures.localPath(it) }

  fun serializeNoteJson(note: MathNote): JSONObject {
    val root = JSONObject()
    root.put("id", note.id)
    root.put("title", note.title)
    root.put("updatedAt", note.updatedAt)
    root.put("paperPattern", note.paperPattern.name)
    root.put("graphColor", note.graphColor.name)
    root.put("showInlinePlotter", note.showInlinePlotter)
    root.put("recognizedLatex", note.recognizedLatex)
    if (note.folderId != null) root.put("folderId", note.folderId)
    root.put("isFavourite", note.isFavourite)
    if (note.backgroundImagePath != null) root.put("backgroundImagePath", note.backgroundImagePath)
    root.put("paperColor", note.paperColor.name)
    root.put("layers", JSONArray().apply {
      note.layers.forEach { l -> put(JSONObject().put("id", l.id).put("name", l.name).put("visible", l.visible).put("opacity", l.opacity.toDouble())) }
    })

    val strokesArr = JSONArray()
    for (s in note.strokes) {
      val sObj = JSONObject()
      sObj.put("width", s.width.toDouble())
      sObj.put("colorHex", s.colorHex)
      sObj.put("isHighlighter", s.isHighlighter)
      if (s.isTape) sObj.put("isTape", true)
      if (s.penType != PenType.PEN) sObj.put("pen", s.penType.name)
      if (s.lineStyle != LineStyle.SOLID) sObj.put("line", s.lineStyle.name)
      if (s.layer != 0) sObj.put("layer", s.layer)
      s.fill?.let { m -> sObj.put("fill", JSONObject().put("w", m.w).put("h", m.h).put("mask", m.encoded)) }
      s.picture?.let { p -> sObj.put("picture", p.path) }
      if (s.chisel) sObj.put("chisel", true)
      if (s.tapePattern != TapePattern.PLAIN) sObj.put("tapePattern", s.tapePattern.name)
      val ptsArr = JSONArray()
      for (p in s.points) {
        val pObj = JSONObject()
        pObj.put("x", p.x.toDouble())
        pObj.put("y", p.y.toDouble())
        pObj.put("t", p.t)
        ptsArr.put(pObj)
      }
      sObj.put("points", ptsArr)
      strokesArr.put(sObj)
    }
    root.put("strokes", strokesArr)
    return root
  }

  fun parseNoteJson(jsonStr: String): MathNote {
    val obj = JSONObject(jsonStr)
    val id = obj.getString("id")
    val title = obj.optString("title", "Untitled")
    val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
    val paperPattern = runCatching {
      PaperPattern.valueOf(obj.optString("paperPattern", "GRID"))
    }.getOrDefault(PaperPattern.GRID)
    val graphColor = runCatching {
      GraphColorTheme.valueOf(obj.optString("graphColor", "EMERALD"))
    }.getOrDefault(GraphColorTheme.EMERALD)
    val showInlinePlotter = obj.optBoolean("showInlinePlotter", false)
    val recognizedLatex = obj.optString("recognizedLatex", "")
    val folderId = obj.optString("folderId", "").takeIf { it.isNotBlank() }
    val isFavourite = obj.optBoolean("isFavourite", false)

    val strokes = mutableListOf<InkStroke>()
    val strokesArr = obj.optJSONArray("strokes") ?: JSONArray()
    for (i in 0 until strokesArr.length()) {
      val sObj = strokesArr.getJSONObject(i)
      val width = sObj.optDouble("width", 8.5).toFloat()
      val colorHex = sObj.optLong("colorHex", 0xFF1A1A1AL)
      val isHighlighter = sObj.optBoolean("isHighlighter", false)
      val ptsArr = sObj.optJSONArray("points") ?: JSONArray()
      val pts = mutableListOf<TimedPoint>()
      for (j in 0 until ptsArr.length()) {
        val pObj = ptsArr.getJSONObject(j)
        pts.add(
          TimedPoint(
            x = pObj.optDouble("x", 0.0).toFloat(),
            y = pObj.optDouble("y", 0.0).toFloat(),
            t = pObj.optLong("t", 0L)
          )
        )
      }
      if (pts.isNotEmpty()) {
        strokes.add(InkStroke(pts, width, colorHex, isHighlighter, isTape = sObj.optBoolean("isTape", false), penType = runCatching { PenType.valueOf(sObj.optString("pen", "PEN")) }.getOrDefault(PenType.PEN), lineStyle = runCatching { LineStyle.valueOf(sObj.optString("line", "SOLID")) }.getOrDefault(LineStyle.SOLID), layer = sObj.optInt("layer", 0), fill = sObj.optJSONObject("fill")?.let { fo -> runCatching { FillMask(fo.getInt("w"), fo.getInt("h"), fo.getString("mask")) }.getOrNull() }, picture = sObj.optString("picture", "").takeIf { it.isNotEmpty() }?.let { NotePicture(NotePictures.localPath(it)) }, chisel = sObj.optBoolean("chisel", false), tapePattern = runCatching { TapePattern.valueOf(sObj.optString("tapePattern", "PLAIN")) }.getOrDefault(TapePattern.PLAIN)))
      }
    }
    return MathNote(
      id = id,
      title = title,
      updatedAt = updatedAt,
      paperPattern = paperPattern,
      graphColor = graphColor,
      showInlinePlotter = showInlinePlotter,
      recognizedLatex = recognizedLatex,
      strokes = strokes,
      folderId = folderId,
      isFavourite = isFavourite,
      backgroundImagePath = obj.optString("backgroundImagePath", "").takeIf { it.isNotBlank() }?.let { localizeBackgroundPaths(it) },
      paperColor = runCatching { PaperColor.valueOf(obj.optString("paperColor", "WHITE")) }.getOrDefault(PaperColor.WHITE),
      layers = obj.optJSONArray("layers")?.let { arr ->
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { l -> NoteLayer(l.getInt("id"), l.optString("name", "Layer"), l.optBoolean("visible", true), l.optDouble("opacity", 1.0).toFloat()) } }
      }?.takeIf { it.isNotEmpty() }?.let { LayerNames.renumber(it) } ?: listOf(NoteLayer(0, "Layer 0")),
      strokesLoaded = true,
      strokeCountHint = strokes.size
    )
  }
}

/**
 * Google Drive synchronization manager using Google Play Services Auth + Drive REST API v3 (appDataFolder).
 */
object GoogleDriveSyncManager {
  private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
  private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
  private const val FIREBASE_WEB_CLIENT_ID =
    "1043901052666-76aih9r0e4u6p9cbn91vbu7mhv4oke89.apps.googleusercontent.com"

  data class SyncResult(
    val success: Boolean,
    val statusMessage: String,
    val syncedNotesCount: Int = 0
  )

  fun getSignInClient(context: Context): GoogleSignInClient {
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
      .requestEmail()
      .requestIdToken(FIREBASE_WEB_CLIENT_ID)
      .requestScopes(Scope(DRIVE_APPDATA_SCOPE), Scope(DRIVE_FILE_SCOPE))
      .build()
    return GoogleSignIn.getClient(context, gso)
  }

  fun getSignedInAccount(context: Context): GoogleSignInAccount? {
    return GoogleSignIn.getLastSignedInAccount(context)
  }

  suspend fun signOut(context: Context) {
    runCatching { getSignInClient(context).signOut().await() }
  }

  suspend fun syncNotesWithDrive(context: Context): SyncResult = withContext(Dispatchers.IO) {
    val acct = GoogleSignIn.getLastSignedInAccount(context)
      ?: return@withContext SyncResult(
        success = false,
        statusMessage = "Sign in with your Google account to sync notes with Google Drive."
      )

    val androidAccount: Account = acct.account
      ?: return@withContext SyncResult(
        success = false,
        statusMessage = "Invalid Google account. Please reconnect your account."
      )

    val token = try {
      val scopeStr = "oauth2:$DRIVE_APPDATA_SCOPE $DRIVE_FILE_SCOPE"
      GoogleAuthUtil.getToken(context, androidAccount, scopeStr)
    } catch (e: Exception) {
      return@withContext SyncResult(
        success = false,
        statusMessage = "Drive OAuth token unavailable (${e.javaClass.simpleName})."
      )
    }

    try {
      val remote = listAppDataFiles(token).values.filter { it.name.endsWith(".json") }.map {
        BinSync.Remote(it.name.removeSuffix(".json"), it.fileId, it.deletedAt, it.title)
      }
      val notes = NotesRepository.loadAllNotes(context).associateBy { it.id }
      val bin = NoteBin.list(context).associateBy { it.id }
      val purged = NoteBin.purged(context).keys
      val now = System.currentTimeMillis()
      var uploaded = 0
      var downloaded = 0
      var binned = 0

      fun noteJson(n: MathNote, deletedAt: Long? = null) =
        NotesRepository.serializeNoteJson(n).apply { if (deletedAt != null) put("deletedAt", deletedAt) }.toString()

      for (a in BinSync.plan(notes.mapValues { it.value.updatedAt }, bin, purged, remote, now)) {
        runCatching {
          when (a) {
            is BinSync.Action.Upload -> {
              val n = notes[a.id] ?: return@runCatching
              if (a.fileId == null) createAppDataFile(token, "${a.id}.json", noteJson(n)) else updateFileContent(token, a.fileId, noteJson(n))
              uploaded++
            }
            is BinSync.Action.Undelete -> {
              val n = notes[a.id] ?: return@runCatching
              updateFileContent(token, a.fileId, noteJson(n))
              setAppProperties(token, a.fileId, JSONObject().put("deletedAt", JSONObject.NULL))
              uploaded++
            }
            is BinSync.Action.MarkDeleted -> {
              val e = bin[a.id] ?: return@runCatching
              val n = NoteBin.loadNote(context, a.id) ?: return@runCatching
              val props = JSONObject().put("deletedAt", e.deletedAt.toString()).put("title", e.title.take(100))
              if (a.fileId == null) createAppDataFile(token, "${a.id}.json", noteJson(n, e.deletedAt), props)
              else { updateFileContent(token, a.fileId, noteJson(n, e.deletedAt)); setAppProperties(token, a.fileId, props) }
              // Drive keeps it now: it leaves this device
              NoteBin.makeStub(context, a.id)
              binned++
            }
            is BinSync.Action.BinHere -> {
              val n = notes[a.id] ?: return@runCatching
              NotesRepository.deleteNote(context, a.id)
              NoteBin.writeStub(context, a.id, n.title, a.deletedAt, n.recognizedLatex)
              binned++
            }
            is BinSync.Action.Stub -> NoteBin.makeStub(context, a.id)
            is BinSync.Action.AddToBin -> NoteBin.writeStub(context, a.id, a.title, a.deletedAt)
            is BinSync.Action.Restore, is BinSync.Action.Download -> {
              val fileId = if (a is BinSync.Action.Restore) a.fileId else (a as BinSync.Action.Download).fileId
              val content = downloadFileContent(token, fileId) ?: return@runCatching
              NotesRepository.saveNote(context, NotesRepository.parseNoteJson(content))
              NoteBin.remove(context, a.id)
              downloaded++
            }
            is BinSync.Action.DeleteRemote -> { deleteFile(token, a.fileId); NoteBin.forgetPurged(context, a.id) }
            is BinSync.Action.DropFromBin -> NoteBin.remove(context, a.id)
          }
        }
      }

      // Pictures and page backgrounds: the notes only hold file names, the files travel separately
      val pictures = runCatching { syncPictures(context, token) }.getOrDefault(0)

      SyncResult(
        success = true,
        statusMessage = "Synced with Google Drive (${acct.email}): $uploaded uploaded, $downloaded downloaded" +
          (if (binned > 0) ", $binned moved to the bin" else "") +
          (if (pictures > 0) ", $pictures pictures" else ""),
        syncedNotesCount = uploaded + downloaded
      )
    } catch (e: Exception) {
      SyncResult(
        success = false,
        statusMessage = "Google Drive error: ${e.message ?: e.javaClass.simpleName}"
      )
    }
  }

  /** A file on Drive; [deletedAt] and [title] come from its app properties (set while it's in the bin). */
  private data class RemoteFileMeta(val fileId: String, val name: String, val deletedAt: Long?, val title: String?)

  private fun listAppDataFiles(token: String): Map<String, RemoteFileMeta> {
    val q = URLEncoder.encode("trashed = false", "UTF-8")
    val map = mutableMapOf<String, RemoteFileMeta>()
    var pageToken: String? = null
    do {
      val page = pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: ""
      val url = URL("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=200&fields=nextPageToken,files(id,name,appProperties)&q=$q$page")
      val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $token")
        connectTimeout = 10_000
        readTimeout = 10_000
      }
      val code = conn.responseCode
      if (code !in 200..299) throw IllegalStateException("Drive list HTTP $code")
      val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
      val files = json.optJSONArray("files") ?: JSONArray()
      for (i in 0 until files.length()) {
        val f = files.getJSONObject(i)
        val props = f.optJSONObject("appProperties")
        val name = f.getString("name")
        map[name] = RemoteFileMeta(
          f.getString("id"), name,
          props?.optString("deletedAt")?.toLongOrNull(),
          props?.optString("title")?.takeIf { it.isNotEmpty() }
        )
      }
      pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
    } while (pageToken != null)
    return map
  }

  /** Sets (or, with JSONObject.NULL values, clears) a Drive file's app properties. */
  private fun setAppProperties(token: String, fileId: String, props: JSONObject) {
    val conn = (URL("https://www.googleapis.com/drive/v3/files/$fileId").openConnection() as HttpURLConnection).apply {
      // HttpURLConnection has no PATCH; Drive accepts it through this header
      requestMethod = "POST"
      setRequestProperty("X-HTTP-Method-Override", "PATCH")
      doOutput = true
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Content-Type", "application/json; charset=UTF-8")
    }
    conn.outputStream.use { it.write(JSONObject().put("appProperties", props).toString().toByteArray(Charsets.UTF_8)) }
    if (conn.responseCode !in 200..299) throw IllegalStateException("Drive update HTTP ${conn.responseCode}")
  }

  private fun deleteFile(token: String, fileId: String) {
    val conn = (URL("https://www.googleapis.com/drive/v3/files/$fileId").openConnection() as HttpURLConnection).apply {
      requestMethod = "DELETE"
      setRequestProperty("Authorization", "Bearer $token")
    }
    val code = conn.responseCode
    if (code !in 200..299 && code != 404) throw IllegalStateException("Drive delete HTTP $code")
  }

  private const val PICTURE_PREFIX = "pic_"

  /**
   * Makes sure every picture the notes use is on Drive and on this device: uploads the ones Drive
   * doesn't have, downloads the ones this device is missing. Returns how many files moved.
   */
  private fun syncPictures(context: Context, token: String): Int {
    val dir = NotePictures.dir(context)
    val notes = NotesRepository.loadAllNotes(context) + NoteBin.list(context).mapNotNull { NoteBin.loadNote(context, it.id) }
    val names = notes.flatMap { NotePictures.namesIn(it) }.toSet()
    if (names.isEmpty()) return 0
    val remote = listAppDataFiles(token)
    var moved = 0
    for (name in names) {
      val local = File(dir, name)
      val onDrive = remote[PICTURE_PREFIX + name]
      when {
        local.exists() && onDrive == null -> {
          val mime = if (name.endsWith(".png", true)) "image/png" else "image/jpeg"
          createAppDataBinary(token, PICTURE_PREFIX + name, local.readBytes(), mime)
          moved++
        }
        !local.exists() && onDrive != null -> {
          val bytes = downloadBytes(token, onDrive.fileId) ?: continue
          val tmp = File(dir, "$name.part")
          tmp.writeBytes(bytes)
          if (!tmp.renameTo(local)) { local.writeBytes(bytes); tmp.delete() }
          moved++
        }
      }
    }
    return moved
  }

  private fun createAppDataBinary(token: String, name: String, bytes: ByteArray, mime: String) {
    val boundary = "===GeminiMathBoundary${System.currentTimeMillis()}==="
    val conn = (URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart").openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
    }
    val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString()
    conn.outputStream.use { o ->
      o.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray(Charsets.UTF_8))
      o.write(bytes)
      o.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
    }
    if (conn.responseCode !in 200..299) throw IllegalStateException("Drive picture upload HTTP ${conn.responseCode}")
  }

  private fun downloadBytes(token: String, fileId: String): ByteArray? {
    val conn = (URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media").openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      setRequestProperty("Authorization", "Bearer $token")
      connectTimeout = 15_000
      readTimeout = 30_000
    }
    if (conn.responseCode !in 200..299) return null
    return conn.inputStream.use { it.readBytes() }
  }

  private fun driveToken(context: Context): String? {
    val acct = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return null
    return runCatching { GoogleAuthUtil.getToken(context, acct, "oauth2:$DRIVE_APPDATA_SCOPE $DRIVE_FILE_SCOPE") }.getOrNull()
  }

  /**
   * Restores a note whose content is only on Drive: downloads it, puts it back with the notes and
   * takes it out of the bin on Drive. Null if it couldn't (not signed in, offline, or gone).
   */
  suspend fun restoreFromCloud(context: Context, id: String): MathNote? = withContext(Dispatchers.IO) {
    runCatching {
      val token = driveToken(context) ?: return@runCatching null
      val meta = listAppDataFiles(token)["$id.json"] ?: return@runCatching null
      val content = downloadFileContent(token, meta.fileId) ?: return@runCatching null
      val note = NotesRepository.parseNoteJson(content).copy(updatedAt = System.currentTimeMillis())
      NotesRepository.saveNote(context, note)
      NoteBin.remove(context, id)
      updateFileContent(token, meta.fileId, NotesRepository.serializeNoteJson(note).toString())
      setAppProperties(token, meta.fileId, JSONObject().put("deletedAt", JSONObject.NULL))
      note
    }.getOrNull()
  }

  /** Deletes the Drive copies of notes deleted forever, straight away (otherwise the next sync does). */
  suspend fun deletePurgedNow(context: Context) = withContext(Dispatchers.IO) {
    runCatching {
      val token = driveToken(context) ?: return@runCatching
      val files = listAppDataFiles(token)
      for (id in NoteBin.purged(context).keys) {
        files["$id.json"]?.let { deleteFile(token, it.fileId) }
        NoteBin.forgetPurged(context, id)
      }
    }
  }

  private fun downloadFileContent(token: String, fileId: String): String? {
    val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      setRequestProperty("Authorization", "Bearer $token")
      connectTimeout = 10_000
      readTimeout = 10_000
    }
    if (conn.responseCode !in 200..299) return null
    return conn.inputStream.bufferedReader().use { it.readText() }
  }

  private fun createAppDataFile(token: String, name: String, jsonContent: String, appProperties: JSONObject? = null) {
    val boundary = "===GeminiMathBoundary${System.currentTimeMillis()}==="
    val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
    }
    val meta = JSONObject().apply {
      put("name", name)
      put("parents", JSONArray().put("appDataFolder"))
      if (appProperties != null) put("appProperties", appProperties)
    }.toString()

    val payload = buildString {
      append("--$boundary\r\n")
      append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
      append(meta)
      append("\r\n--$boundary\r\n")
      append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
      append(jsonContent)
      append("\r\n--$boundary--\r\n")
    }
    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    if (conn.responseCode !in 200..299) throw IllegalStateException("Drive create HTTP ${conn.responseCode}")
  }

  private fun updateFileContent(token: String, fileId: String, jsonContent: String) {
    val url = URL("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      // HttpURLConnection refuses "PATCH" itself; Drive accepts it through this header
      requestMethod = "POST"
      setRequestProperty("X-HTTP-Method-Override", "PATCH")
      doOutput = true
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Content-Type", "application/json; charset=UTF-8")
    }
    conn.outputStream.use { it.write(jsonContent.toByteArray(Charsets.UTF_8)) }
    if (conn.responseCode !in 200..299) throw IllegalStateException("Drive upload HTTP ${conn.responseCode}")
  }

  fun getAppSignatureFingerprints(context: Context): Pair<String, String> {
    return try {
      val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(
          context.packageName,
          PackageManager.GET_SIGNING_CERTIFICATES
        )
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
      }
      val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
      } else {
        @Suppress("DEPRECATION")
        pkgInfo.signatures?.firstOrNull()?.toByteArray()
      } ?: return "40:21:15:E2:91:05:4F:60:16:A7:CC:28:33:40:60:6F:8C:3F:E2:0C" to
        "96:31:84:A2:2E:72:D2:CA:14:20:AC:BE:9F:F9:D2:95:A4:66:3D:E4:57:D7:70:10:D0:7C:2E:CD:E2:29:91:82"

      val sha1 = MessageDigest.getInstance("SHA-1").digest(certBytes)
        .joinToString(":") { "%02X".format(it) }
      val sha256 = MessageDigest.getInstance("SHA-256").digest(certBytes)
        .joinToString(":") { "%02X".format(it) }
      sha1 to sha256
    } catch (_: Throwable) {
      "40:21:15:E2:91:05:4F:60:16:A7:CC:28:33:40:60:6F:8C:3F:E2:0C" to
        "96:31:84:A2:2E:72:D2:CA:14:20:AC:BE:9F:F9:D2:95:A4:66:3D:E4:57:D7:70:10:D0:7C:2E:CD:E2:29:91:82"
    }
  }
}

/** Fill masks are stored compressed (deflate) and Base64-encoded. */
object FillCodec {
  fun encode(mask: ByteArray): String {
    val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
    d.setInput(mask); d.finish()
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf))
    d.end()
    return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
  }

  fun decode(s: String): ByteArray {
    val inf = java.util.zip.Inflater()
    inf.setInput(java.util.Base64.getDecoder().decode(s))
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
    inf.end()
    return out.toByteArray()
  }
}

/** Layers are numbered from 0 (the bottom one is "Layer 0"). */
object LayerNames {
  /**
   * Notes made before layers were numbered from 0 have "Layer 1", "Layer 2"…: those default
   * names are shifted down by one. Names you gave a layer yourself are kept.
   */
  fun renumber(layers: List<NoteLayer>): List<NoteLayer> {
    val old = layers.withIndex().all { (i, l) -> l.name == "Layer ${i + 1}" || !Regex("Layer \\d+").matches(l.name) }
    val anyOld = layers.withIndex().any { (i, l) -> l.name == "Layer ${i + 1}" }
    if (!old || !anyOld) return layers
    return layers.mapIndexed { i, l -> if (l.name == "Layer ${i + 1}") l.copy(name = "Layer $i") else l }
  }
}