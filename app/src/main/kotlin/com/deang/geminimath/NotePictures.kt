package com.deang.geminimath

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.UUID

/** A picture placed in a note. The stroke's two points are its top-left and bottom-right corners. */
class NotePicture(val path: String) {
  /** Decoded image for drawing, loaded in the background on first use. */
  @Volatile var image: ImageBitmap? = null
  @Volatile var loading = false
}

object NotePictures {
  private const val MAX_SIDE = 2048

  fun dir(context: Context): File = File(context.filesDir, "note_images").apply { mkdirs() }

  /** This device's pictures folder, set when the app starts (see [init]). */
  @Volatile private var baseDir: File? = null

  fun init(context: Context) { baseDir = dir(context) }

  /**
   * Where a picture saved in a note is on *this* device. Notes store the full path of the device
   * that added the picture; synced to another device that path doesn't exist, but the file (same
   * name, downloaded from Drive) is in this device's pictures folder.
   */
  fun localPath(saved: String): String {
    if (File(saved).exists()) return saved
    val base = baseDir ?: return saved
    return File(base, File(saved).name).absolutePath
  }

  /** File names of the pictures and page background a note uses. */
  fun namesIn(note: MathNote): List<String> =
    (note.strokes.mapNotNull { it.picture?.path } + listOfNotNull(note.backgroundImagePath)).map { File(it).name }.distinct()

  /** Saves [bmp] as a JPG in the app's pictures folder. */
  fun save(context: Context, bmp: Bitmap): File {
    val f = File(dir(context), UUID.randomUUID().toString() + ".jpg")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return f
  }

  /** Copies a picture from the gallery into the app (scaled to at most 2048 px). Null if it can't be read. */
  fun import(context: Context, uri: Uri): Pair<File, Bitmap>? = runCatching {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
      val s = info.size
      val k = MAX_SIDE.toFloat() / maxOf(s.width, s.height)
      if (k < 1f) dec.setTargetSize((s.width * k).toInt().coerceAtLeast(1), (s.height * k).toInt().coerceAtLeast(1))
      dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    save(context, bmp) to bmp
  }.getOrNull()

  /** Starts loading [p]'s image off the main thread; [onLoaded] runs when it's ready. */
  fun load(p: NotePicture, onLoaded: () -> Unit) {
    if (p.image != null || p.loading) return
    p.loading = true
    Thread {
      val b = runCatching { BitmapFactory.decodeFile(p.path) }.getOrNull()
      p.image = b?.asImageBitmap()
      p.loading = false
      if (b != null) android.os.Handler(android.os.Looper.getMainLooper()).post(onLoaded)
    }.start()
  }

  /** Adds a copy to the phone's Pictures/Gemini Math album, so it shows in Gallery. */
  fun saveToGallery(context: Context, bmp: Bitmap, name: String): Boolean = runCatching {
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gemini Math")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val r = context.contentResolver
    val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return false
    values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
    r.update(uri, values, null, null)
    true
  }.getOrDefault(false)
}
