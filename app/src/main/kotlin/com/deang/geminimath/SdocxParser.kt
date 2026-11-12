package com.deang.geminimath

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Reads handwriting from Samsung Notes `.sdocx` files.
 *
 * Port of the stroke-reading parts of sdocx2pdf by squ1dd13 (MIT licence),
 * https://github.com/squ1dd13/sdocx2pdf. Only pen strokes are read; text boxes, images and shapes
 * are skipped. All values are little-endian.
 *
 * File layout used here:
 * - The `.sdocx` is a ZIP. `pageIdInfo.dat` lists page UUIDs in order; each page is `<uuid>.page`.
 * - A page has a header (u32 offset of the layer table, …, u32 width, u32 height, …), then the layer
 *   table: u16 layer count, u16 current layer, layers.
 * - A layer is an inclusive-size header frame, u32 object count, then objects:
 *   u8 type, u16 child count, u32 size, `size` bytes (object frames + 32-byte hash).
 * - A stroke (type 1) is an ObjectBase frame followed by a stroke frame with the point data.
 */
object SdocxParser {
  data class Stroke(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val pressures: FloatArray,
    /** ARGB, converted from the file's BGRA bytes. */
    val colorArgb: Long,
    val penSize: Float
  )

  data class Page(val width: Int, val height: Int, val strokes: List<Stroke>)

  data class Document(val pages: List<Page>)

  class SdocxFormatException(message: String) : Exception(message)

  fun parse(bytes: ByteArray): Document {
    val entries = HashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val e = zip.nextEntry ?: break
        // Only the small binary entries are needed; skip media
        if (!e.isDirectory && !e.name.startsWith("media/")) entries[e.name] = zip.readBytes()
      }
    }
    if (entries.isEmpty()) throw SdocxFormatException("Not a Samsung Notes file")

    val order = entries["pageIdInfo.dat"]?.let { readPageOrder(it) }
      ?: entries.keys.filter { it.endsWith(".page") }.map { it.removeSuffix(".page") }.sorted()
    val pages = order.mapNotNull { uuid -> entries["$uuid.page"]?.let { parsePage(it) } }
    if (pages.isEmpty()) throw SdocxFormatException("No pages found in this Samsung Notes file")
    return Document(pages)
  }

  private fun buf(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

  private fun ByteBuffer.u8() = get().toInt() and 0xff
  private fun ByteBuffer.u16() = short.toInt() and 0xffff
  private fun ByteBuffer.u32() = int.toLong() and 0xffffffffL

  private fun ByteBuffer.shortU16String(): String {
    val n = u16()
    val chars = CharArray(n) { short.toInt().toChar() }
    return String(chars)
  }

  /** Variable-length bitfield: u8 byte count (0–4), then that many little-endian bytes. */
  private fun ByteBuffer.bitfield(): Long {
    return when (val n = u8()) {
      0 -> 0L
      1 -> u8().toLong()
      2 -> u16().toLong()
      3 -> (u16().toLong()) or (u8().toLong() shl 16)
      4 -> u32()
      else -> throw SdocxFormatException("Bad bitfield size $n")
    }
  }

  private fun Long.bit(i: Int) = (this shr i) and 1L == 1L

  /** pageIdInfo.dat: 32-byte note hash, u16 count, then (UUID string, 32-byte page hash) pairs. */
  private fun readPageOrder(bytes: ByteArray): List<String> {
    val b = buf(bytes)
    b.position(32)
    val n = b.u16()
    return List(n) {
      val uuid = b.shortU16String()
      b.position(b.position() + 32)
      uuid
    }
  }

  private fun parsePage(bytes: ByteArray): Page {
    val b = buf(bytes)
    val start = 0
    val pageEnd = start + b.u32().toInt()
    b.u32() // flex offset
    b.bitfield() // property flags
    b.bitfield() // field flags
    b.u32() // orientation
    val width = b.u32().toInt()
    val height = b.u32().toInt()

    b.position(pageEnd)
    val layerCount = b.u16()
    b.u16() // current layer
    val strokes = ArrayList<Stroke>()
    repeat(layerCount) { parseLayer(b, strokes) }
    return Page(width, height, strokes)
  }

  private fun parseLayer(b: ByteBuffer, out: MutableList<Stroke>) {
    // Header frame: inclusive u32 size, so skip it whole
    val frameStart = b.position()
    val frameSize = b.u32().toInt()
    b.position(frameStart + frameSize)

    val objectCount = b.u32()
    for (i in 0 until objectCount) {
      val type = b.u8()
      val children = b.u16()
      if (children != 0) throw SdocxFormatException("Grouped objects aren't supported")
      val size = b.u32().toInt()
      val objStart = b.position()
      if (type == 1) {
        runCatching { parseStroke(b.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(objStart) as ByteBuffer) }
          .getOrNull()?.let { out += it }
      }
      b.position(objStart + size)
    }
    b.position(b.position() + 32) // layer hash
  }

  private fun parseStroke(b: ByteBuffer): Stroke? {
    // ObjectBase frame (inclusive size): skip
    val baseStart = b.position()
    b.position(baseStart + b.u32().toInt())

    // Stroke frame
    val frameStart = b.position()
    b.u32() // frame size
    if (b.u16() != 1) return null // data type must be "stroke"
    val flexOffset = b.u32().toInt()
    val props = b.bitfield()
    val fields = b.bitfield()

    val isCurve = props.bit(0)
    val hasTilt = props.bit(2)
    val isEraser = props.bit(3)

    val n = b.u16()
    if (n == 0) return null
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    val ps = FloatArray(n)

    if (isCurve) {
      // Compressed: f64 origin, then u16 fixed-point deltas (columnar)
      xs[0] = b.double
      ys[0] = b.double
      val d = n - 1
      val deltas = IntArray(2 * d) { b.u16() }
      ps[0] = b.float
      val pDeltas = IntArray(d) { b.u16() }
      b.u32() // origin timestamp
      repeat(d) { b.u16() } // timestamp deltas
      if (hasTilt) {
        b.float; repeat(d) { b.u16() }
        b.float; repeat(d) { b.u16() }
      }
      for (i in 1 until n) {
        xs[i] = xs[i - 1] + pointDelta(deltas[2 * (i - 1)])
        ys[i] = ys[i - 1] + pointDelta(deltas[2 * (i - 1) + 1])
        ps[i] = ps[i - 1] + smallDelta(pDeltas[i - 1])
      }
    } else {
      // Uncompressed: f64 x,y pairs, then f32 pressures, then u32 timestamps
      for (i in 0 until n) {
        xs[i] = b.double
        ys[i] = b.double
      }
      for (i in 0 until n) ps[i] = b.float
      repeat(n) { b.u32() }
      if (hasTilt) repeat(2 * n) { b.float }
    }
    val toolType = b.u16()
    if (isEraser || toolType == 4) return null

    var colour = byteArrayOf(0, 0, 0, 0xff.toByte())
    var penSize = 3f
    if (flexOffset != 0) {
      b.position(frameStart + flexOffset)
      if (fields.bit(1)) b.u32() // advanced pen settings (string id)
      if (fields.bit(2)) colour = ByteArray(4).also { b.get(it) }
      if (fields.bit(3)) penSize = b.float
    }
    // BGRA → ARGB
    val bl = colour[0].toLong() and 0xff
    val g = colour[1].toLong() and 0xff
    val r = colour[2].toLong() and 0xff
    val a = colour[3].toLong() and 0xff
    return Stroke(xs, ys, ps, (a shl 24) or (r shl 16) or (g shl 8) or bl, penSize)
  }

  /** sign | 10-bit integer | 5-bit fraction */
  private fun pointDelta(fixed: Int): Double {
    val v = ((fixed and 0x7fff) shr 5) + (fixed and 0x1f) / 32.0
    return if (fixed and 0x8000 != 0) -v else v
  }

  /** sign | 3-bit integer | 12-bit fraction */
  private fun smallDelta(fixed: Int): Float {
    val v = ((fixed and 0x7fff) shr 12) + (fixed and 0xfff) / 4096f
    return if (fixed and 0x8000 != 0) -v else v
  }
}
