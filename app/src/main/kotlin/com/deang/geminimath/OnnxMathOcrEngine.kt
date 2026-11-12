package com.deang.geminimath

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min

class OnnxMathOcrEngine(private val context: Context) {
  data class OcrResult(
    val latex: String,
    val encoderMs: Long,
    val decoderMs: Long,
    val tokenCount: Int
  )

  private var ortEnv: OrtEnvironment? = null
  private var encoderSession: OrtSession? = null
  private var decoderSession: OrtSession? = null
  private val idToToken = HashMap<Int, String>(2048)

  @Volatile
  var isReady: Boolean = false
    private set

  fun init(): String {
    val t0 = SystemClock.elapsedRealtime()
    val env = OrtEnvironment.getEnvironment()
    ortEnv = env

    // 1. Load vocabulary from assets/mfr/tokenizer.json
    val tokJsonStr = context.assets.open("mfr/tokenizer.json").bufferedReader().use { it.readText() }
    val root = JSONObject(tokJsonStr)
    val vocabObj = root.getJSONObject("model").getJSONObject("vocab")
    val keys = vocabObj.keys()
    while (keys.hasNext()) {
      val tokenStr = keys.next()
      val id = vocabObj.getInt(tokenStr)
      idToToken[id] = tokenStr
    }
    val addedArr = root.optJSONArray("added_tokens")
    if (addedArr != null) {
      for (i in 0 until addedArr.length()) {
        val obj = addedArr.getJSONObject(i)
        idToToken[obj.getInt("id")] = obj.getString("content")
      }
    }

    // 2. Copy ONNX models to filesDir if not present so ONNX Runtime can memory-map them without heap bloat
    val encFile = copyAssetIfNeeded("mfr/encoder_model.onnx", "pix2text_encoder_1_5.onnx")
    val decFile = copyAssetIfNeeded("mfr/decoder_model.onnx", "pix2text_decoder_1_5.onnx")

    val opts = OrtSession.SessionOptions().apply {
      setIntraOpNumThreads(4)
      setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    encoderSession = env.createSession(encFile.absolutePath, opts)
    decoderSession = env.createSession(decFile.absolutePath, opts)
    isReady = true
    val dt = SystemClock.elapsedRealtime() - t0
    return "Pix2Text-MFR 1.5 ONNX PRONTO (${dt}ms, ${idToToken.size} token)"
  }

  private fun copyAssetIfNeeded(assetPath: String, outName: String): File {
    val outFile = File(context.filesDir, outName)
    if (outFile.exists() && outFile.length() > 1_000_000L) {
      return outFile
    }
    context.assets.open(assetPath).use { input ->
      outFile.outputStream().use { output ->
        input.copyTo(output, bufferSize = 256 * 1024)
      }
    }
    return outFile
  }

  /**
   * Runs Pix2Text-MFR 1.5 VisionEncoderDecoder (DeiT-384x384 + TrOCR-Decoder) on a cropped formula Bitmap.
   */
  fun recognizeFormula(formulaBitmap: Bitmap, maxTokens: Int = 96, stretch: Boolean = false): OcrResult {
    val env = ortEnv ?: error("ORT Environment non inizializzato")
    val enc = encoderSession ?: error("Encoder ONNX non inizializzato")
    val dec = decoderSession ?: error("Decoder ONNX non inizializzato")

    // 1. Preprocess image to 384x384 preserving aspect ratio on white background
    val targetSize = 384
    val paddedBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(paddedBmp)
    canvas.drawColor(Color.WHITE)

    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    if (stretch) {
      // TrOCR / Pix2Text training preprocessing: resize straight to 384×384, ignoring aspect ratio
      canvas.drawBitmap(formulaBitmap, null, RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat()), paint)
    } else {
      val margin = 24f
      val avail = targetSize - margin * 2f
      val scale = min(avail / formulaBitmap.width.toFloat(), avail / formulaBitmap.height.toFloat())
      val drawW = formulaBitmap.width * scale
      val drawH = formulaBitmap.height * scale
      val left = (targetSize - drawW) / 2f
      val top = (targetSize - drawH) / 2f
      canvas.drawBitmap(formulaBitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
    }

    val pixels = IntArray(targetSize * targetSize)
    paddedBmp.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

    // DeiT normalization: mean=0.5, std=0.5 -> (c / 255.0 - 0.5) / 0.5 = c / 127.5 - 1.0
    val hw = targetSize * targetSize
    val floatBuf = FloatBuffer.allocate(3 * hw)
    for (i in 0 until hw) {
      val p = pixels[i]
      val r = ((p shr 16) and 0xFF) / 127.5f - 1.0f
      val g = ((p shr 8) and 0xFF) / 127.5f - 1.0f
      val b = (p and 0xFF) / 127.5f - 1.0f
      floatBuf.put(i, r)
      floatBuf.put(hw + i, g)
      floatBuf.put(2 * hw + i, b)
    }
    floatBuf.rewind()

    val tEnc0 = SystemClock.elapsedRealtime()
    val pixelTensor = OnnxTensor.createTensor(env, floatBuf, longArrayOf(1L, 3L, targetSize.toLong(), targetSize.toLong()))

    pixelTensor.use { px ->
      val encOut = enc.run(mapOf("pixel_values" to px))
      encOut.use { encoderResults ->
        val hiddenStatesTensor = encoderResults.get(0) as OnnxTensor
        val encoderMs = SystemClock.elapsedRealtime() - tEnc0

        // 2. Autoregressive Greedy Decoding (BOS = 1, EOS = 2, PAD = 0)
        val tDec0 = SystemClock.elapsedRealtime()
        val tokenIds = ArrayList<Long>(maxTokens)
        tokenIds.add(1L) // decoder_start_token_id = 1 (<s>)

        for (step in 0 until maxTokens) {
          val seqLen = tokenIds.size
          val idsArray = LongArray(seqLen) { tokenIds[it] }
          val idsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(idsArray),
            longArrayOf(1L, seqLen.toLong())
          )
          var nextTokenId = 2L
          idsTensor.use { idT ->
            val decInputs = mapOf(
              "input_ids" to idT,
              "encoder_hidden_states" to hiddenStatesTensor
            )
            val decOut = dec.run(decInputs)
            decOut.use { decoderResults ->
              @Suppress("UNCHECKED_CAST")
              val logits3d = decoderResults.get(0).value as Array<Array<FloatArray>>
              val lastLogits = logits3d[0][seqLen - 1]
              var bestIdx = 0
              var bestVal = Float.NEGATIVE_INFINITY
              for (v in lastLogits.indices) {
                if (lastLogits[v] > bestVal) {
                  bestVal = lastLogits[v]
                  bestIdx = v
                }
              }
              nextTokenId = bestIdx.toLong()
            }
          }

          if (nextTokenId == 2L || nextTokenId == 0L) {
            break
          }
          tokenIds.add(nextTokenId)
        }

        val decoderMs = SystemClock.elapsedRealtime() - tDec0
        val decodedLatex = decodeTokensToLatex(tokenIds)
        return OcrResult(
          latex = decodedLatex,
          encoderMs = encoderMs,
          decoderMs = decoderMs,
          tokenCount = tokenIds.size - 1
        )
      }
    }
  }

  private fun decodeTokensToLatex(tokenIds: List<Long>): String {
    val sb = StringBuilder()
    for (idLong in tokenIds) {
      val id = idLong.toInt()
      if (id in 0..4) continue // Skip <pad>, <s>, </s>, <unk>, <mask>
      val raw = idToToken[id] ?: continue
      sb.append(raw)
    }
    return sb.toString()
      .replace('\u0120', ' ') // ByteLevel BPE space 'Ġ'
      .replace('\u010A', '\n') // ByteLevel BPE newline 'Ċ'
      .trim()
  }

  fun close() {
    try {
      encoderSession?.close()
      decoderSession?.close()
    } catch (_: Throwable) {}
  }
}
