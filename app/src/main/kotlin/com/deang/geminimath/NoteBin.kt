package com.deang.geminimath

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The bin. Deleting a note moves it here; it's removed for good after [KEEP_DAYS] days, or when
 * you delete it forever.
 *
 * With Drive backup on, the deletion is synced: the note's copy on Drive is marked deleted (and
 * kept there for 30 days), other devices move their copy to the bin too, and the note's content
 * leaves this device: the bin keeps a small placeholder ([Entry.onDevice] = false) and restoring
 * downloads it again. With backup off, the whole note stays in the bin on this device.
 */
object NoteBin {
  const val KEEP_DAYS = 30
  const val KEEP_MS = KEEP_DAYS * 24L * 3600 * 1000

  /** A note in the bin. [onDevice]: its content is still on this device (not only on Drive). */
  data class Entry(val id: String, val title: String, val deletedAt: Long, val latex: String, val onDevice: Boolean) {
    /** Whole days until it's removed, rounded up (deleted just now: 30). */
    fun daysLeft(now: Long = System.currentTimeMillis()): Int {
      val day = 24L * 3600 * 1000
      return (((deletedAt + KEEP_MS) - now + day - 1) / day).toInt().coerceIn(0, KEEP_DAYS)
    }
  }

  private fun dir(ctx: Context) = File(ctx.filesDir, "notes_bin").apply { mkdirs() }
  private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")
  private fun purgedFile(ctx: Context) = File(ctx.filesDir, "notes_purged.json")

  /** Moves [note] from the notes to the bin. */
  fun moveToBin(ctx: Context, note: MathNote, at: Long = System.currentTimeMillis()) {
    val json = NotesRepository.serializeNoteJson(note).put("deletedAt", at).put("stub", false)
    file(ctx, note.id).writeText(json.toString())
    NotesRepository.deleteNote(ctx, note.id)
  }

  /** What's in the bin, newest first. Notes past 30 days are removed on the way. */
  fun list(ctx: Context, now: Long = System.currentTimeMillis()): List<Entry> =
    (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { f ->
      runCatching {
        val o = JSONObject(f.readText())
        val e = Entry(
          id = o.getString("id"),
          title = o.optString("title", "Untitled"),
          deletedAt = o.optLong("deletedAt", now),
          latex = o.optString("recognizedLatex", ""),
          onDevice = !o.optBoolean("stub", false)
        )
        if (now - e.deletedAt > KEEP_MS) { f.delete(); null } else e
      }.getOrNull()
    }.sortedByDescending { it.deletedAt }

  /** The whole note, if its content is on this device. */
  fun loadNote(ctx: Context, id: String): MathNote? =
    file(ctx, id).takeIf { it.exists() }?.let { f ->
      runCatching { JSONObject(f.readText()) }.getOrNull()
        ?.takeIf { !it.optBoolean("stub", false) }
        ?.let { NotesRepository.parseNoteJson(it.toString()) }
    }

  /** Drive has the note: keep only a placeholder here, so its content leaves the device. */
  fun makeStub(ctx: Context, id: String) {
    val e = list(ctx).find { it.id == id } ?: return
    writeStub(ctx, e.id, e.title, e.deletedAt, e.latex)
  }

  fun writeStub(ctx: Context, id: String, title: String, deletedAt: Long, latex: String = "") {
    file(ctx, id).writeText(
      JSONObject().put("id", id).put("title", title).put("deletedAt", deletedAt)
        .put("recognizedLatex", latex).put("stub", true).toString()
    )
  }

  fun remove(ctx: Context, id: String) { file(ctx, id).delete() }

  /** Puts a note that's on the device back with the other notes. */
  fun restoreLocal(ctx: Context, id: String): MathNote? {
    val note = loadNote(ctx, id)?.copy(updatedAt = System.currentTimeMillis()) ?: return null
    NotesRepository.saveNote(ctx, note)
    remove(ctx, id)
    return note
  }

  /** Removes it from the bin for good; the next sync deletes it from Drive too. */
  fun deleteForever(ctx: Context, id: String) {
    remove(ctx, id)
    val p = purged(ctx).toMutableMap()
    p[id] = System.currentTimeMillis()
    savePurged(ctx, p)
  }

  /** Notes deleted forever here (id → when), remembered a while so a sync doesn't bring them back. */
  fun purged(ctx: Context): Map<String, Long> = runCatching {
    val o = JSONObject(purgedFile(ctx).readText())
    val now = System.currentTimeMillis()
    o.keys().asSequence().associateWith { o.getLong(it) }.filterValues { now - it < 2 * KEEP_MS }
  }.getOrDefault(emptyMap())

  fun forgetPurged(ctx: Context, id: String) {
    savePurged(ctx, purged(ctx) - id)
  }

  private fun savePurged(ctx: Context, p: Map<String, Long>) {
    purgedFile(ctx).writeText(JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }.toString())
  }
}

/**
 * What a Drive sync should do with each note, decided from what's on this device and on Drive.
 * Kept separate from the network code so the rules can be tested.
 */
object BinSync {
  /** A note file on Drive. [deletedAt] is set when the note is in the bin. */
  data class Remote(val id: String, val fileId: String, val deletedAt: Long?, val title: String?)

  sealed interface Action {
    val id: String
    /** Upload this device's note ([fileId] null: create the file). */
    data class Upload(override val id: String, val fileId: String?) : Action
    /** The note was restored or edited here after it was deleted: upload it and take it out of the bin on Drive. */
    data class Undelete(override val id: String, val fileId: String) : Action
    /** Deleted here: mark it deleted on Drive (creating it if needed), then keep only a placeholder here. */
    data class MarkDeleted(override val id: String, val fileId: String?) : Action
    /** Deleted on another device: move this device's copy to the bin (as a placeholder; Drive has it). */
    data class BinHere(override val id: String, val deletedAt: Long) : Action
    /** Drive has it in the bin: this device's binned copy can become a placeholder. */
    data class Stub(override val id: String) : Action
    /** In the bin on another device, not known here: show it in this bin too. */
    data class AddToBin(override val id: String, val title: String, val deletedAt: Long) : Action
    /** Restored on another device: bring it back here from Drive. */
    data class Restore(override val id: String, val fileId: String) : Action
    /** A note made on another device. */
    data class Download(override val id: String, val fileId: String) : Action
    /** Gone for good (deleted forever, or 30 days in the bin): delete the Drive file. */
    data class DeleteRemote(override val id: String, val fileId: String) : Action
    /** Deleted forever on another device: remove the placeholder here. */
    data class DropFromBin(override val id: String) : Action
  }

  fun plan(
    local: Map<String, Long>,           // notes on this device: id → updatedAt
    bin: Map<String, NoteBin.Entry>,     // this device's bin
    purged: Set<String>,                 // deleted forever here
    remote: List<Remote>,
    now: Long
  ): List<Action> {
    val out = mutableListOf<Action>()
    val remoteById = remote.associateBy { it.id }
    for (r in remote) {
      val id = r.id
      val rd = r.deletedAt
      val updated = local[id]
      val entry = bin[id]
      when {
        id in purged -> out += Action.DeleteRemote(id, r.fileId)
        updated != null -> out += when {
          rd == null -> Action.Upload(id, r.fileId)
          updated > rd -> Action.Undelete(id, r.fileId)
          else -> Action.BinHere(id, rd)
        }
        rd != null && now - rd > NoteBin.KEEP_MS -> {
          out += Action.DeleteRemote(id, r.fileId)
          if (entry != null) out += Action.DropFromBin(id)
        }
        entry != null -> out += when {
          rd != null -> if (entry.onDevice) Action.Stub(id) else continue
          entry.onDevice -> Action.MarkDeleted(id, r.fileId)
          else -> Action.Restore(id, r.fileId)
        }
        rd != null -> out += Action.AddToBin(id, r.title?.takeIf { it.isNotBlank() } ?: "Untitled", rd)
        else -> out += Action.Download(id, r.fileId)
      }
    }
    for ((id, _) in local) if (id !in remoteById) out += Action.Upload(id, null)
    for ((id, e) in bin) if (id !in remoteById) {
      out += if (e.onDevice) Action.MarkDeleted(id, null) else Action.DropFromBin(id)
    }
    return out
  }
}
