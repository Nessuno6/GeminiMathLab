package com.deang.geminimath

import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Android 17 Handoff ("Continue on"): continue the open note on another of the user's devices,
 * such as phone → tablet or Googlebook.
 *
 * Handoff only relaunches our activity on the other device with a small bundle of extras
 * (the system limit is 50 KB). So the whole note travels in the bundle, gzipped, when it fits.
 * When it doesn't, only the note's id is sent, and the receiver opens its own copy if it has
 * one (for example from Drive backup).
 */
object NoteHandoff {
  const val EXTRA_NOTE_ID = "handoff_note_id"
  const val EXTRA_NOTE_TITLE = "handoff_note_title"
  /** The note as gzipped JSON, Base64-encoded. Absent when the note was too large. */
  const val EXTRA_NOTE_GZ = "handoff_note_gz"

  /** Stay well under the 50 KB system limit, leaving room for the other extras. */
  private const val MAX_PAYLOAD_CHARS = 44_000

  /** The note open in the editor as last saved; null when no note is open. Written on the main thread. */
  @Volatile var openNote: MathNote? = null

  /** Called on the main thread when the user starts a handoff, to save any unsaved ink first. */
  @Volatile var onHandoffStarted: (() -> Unit)? = null

  /** Id of a note received from another device, waiting for the UI to open it. */
  var pendingNoteId by mutableStateOf<String?>(null)

  /** What a received handoff could not bring across, shown to the user once. */
  var pendingMessage by mutableStateOf<String?>(null)

  fun encode(note: MathNote): String {
    val json = NotesRepository.serializeNoteJson(note).toString().toByteArray(Charsets.UTF_8)
    val bytes = ByteArrayOutputStream()
    GZIPOutputStream(bytes).use { it.write(json) }
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
  }

  fun decode(payload: String): MathNote {
    val gz = Base64.getDecoder().decode(payload)
    val json = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }.toString(Charsets.UTF_8)
    return NotesRepository.parseNoteJson(json)
  }

  /** The note in a form that fits the handoff bundle, or null when it's too large. */
  fun payloadOrNull(note: MathNote): String? = encode(note).takeIf { it.length <= MAX_PAYLOAD_CHARS }

  fun buildExtras(note: MathNote): PersistableBundle = PersistableBundle().apply {
    putString(EXTRA_NOTE_ID, note.id)
    putString(EXTRA_NOTE_TITLE, note.title)
    payloadOrNull(note)?.let { putString(EXTRA_NOTE_GZ, it) }
  }

  /**
   * Reads a handoff launch intent: stores the incoming note (unless the copy here is newer) and
   * queues it to be opened. Returns true when the intent was a handoff.
   */
  fun receive(context: Context, intent: Intent?): Boolean {
    val id = intent?.getStringExtra(EXTRA_NOTE_ID) ?: return false
    val payload = intent.getStringExtra(EXTRA_NOTE_GZ)
    val local = NotesRepository.loadAllNotes(context).find { it.id == id }
    if (payload != null) {
      val incoming = runCatching { decode(payload) }.getOrNull()
      if (incoming != null && incoming.id == id) {
        // Page images are files on the other device; only ones already here are kept
        val allPages = BackgroundPages.paths(incoming.backgroundImagePath)
        val kept = allPages.filter { File(it).exists() }
        val bg = BackgroundPages.join(kept)
        if (kept.size < allPages.size) {
          pendingMessage = "The page image stayed on the other device."
        }
        if (local == null || incoming.updatedAt >= local.updatedAt) {
          NotesRepository.saveNote(context, incoming.copy(backgroundImagePath = bg))
        }
      }
    } else if (local == null) {
      val title = intent.getStringExtra(EXTRA_NOTE_TITLE) ?: "this note"
      pendingMessage = "\"$title\" is too large to hand off directly. Back it up to Google Drive on the other device, then try again."
      return true
    }
    // Consume the extras so a later recreation doesn't import the note again
    intent.removeExtra(EXTRA_NOTE_ID)
    intent.removeExtra(EXTRA_NOTE_GZ)
    intent.removeExtra(EXTRA_NOTE_TITLE)
    pendingNoteId = id
    return true
  }
}
