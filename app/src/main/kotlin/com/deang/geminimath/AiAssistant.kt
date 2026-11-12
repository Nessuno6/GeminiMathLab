package com.deang.geminimath

import android.content.Context
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Which AI answers in the assistant. Both use your own API key (bring your own key). */
enum class AiProvider(val label: String) { CLAUDE("Claude"), GEMINI("Gemini") }

/** Claude models offered in Settings (the first is the default). */
val ClaudeModels = listOf(
  "claude-opus-5-5" to "Claude Opus 5.5 (best)",
  "claude-sonnet-5-5" to "Claude Sonnet 5.5 (faster)",
  "claude-haiku-5-5" to "Claude Haiku 5.5 (fastest, cheapest)"
)

/**
 * API keys, kept encrypted with a key that lives in the phone's secure hardware (Android
 * Keystore): even a copy of the app's files can't be read for them.
 */
object KeyVault {
  private const val ALIAS = "gemini_math_ai_keys"
  private const val PREFS = "ai_keys"

  private fun secretKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build()
    )
    return gen.generateKey()
  }

  fun save(ctx: Context, provider: AiProvider, key: String) {
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (key.isBlank()) { prefs.edit().remove(provider.name).apply(); return }
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
    val sealed = c.iv + c.doFinal(key.trim().toByteArray(Charsets.UTF_8))
    prefs.edit().putString(provider.name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
  }

  fun load(ctx: Context, provider: AiProvider): String? = runCatching {
    val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(provider.name, null) ?: return null
    val bytes = Base64.decode(raw, Base64.NO_WRAP)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
    String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
  }.getOrNull()

  fun has(ctx: Context, provider: AiProvider) = load(ctx, provider) != null
}

/** One message in the assistant's conversation. */
data class AiTurn(val fromUser: Boolean, val text: String)

/**
 * Asks Claude or Gemini about the open page. Each question goes with a picture of the whole page
 * (all the ink, pictures and converted text) and what the app has recognised on it, so the
 * assistant always sees the page as it is now. Earlier messages go along as text.
 */
object AiAssistant {
  private val SYSTEM = """
    You are the maths tutor inside a handwriting notes app. With each question you get a picture
    of the student's whole page of handwritten notes, and sometimes the app's own reading of the
    maths on it (which can contain recognition mistakes). Answer the student's question about
    their page: explain clearly, check their working, point out mistakes and why, and show steps
    when asked. Be concise and friendly. The app shows plain text only: write maths with Unicode
    symbols (x², √, ∫, π, ≤, →, ½), not LaTeX. If part of the handwriting is unreadable, say so.
  """.trimIndent()

  /** The page as a JPEG, base64 (small enough for both APIs). */
  fun pageImage(page: Bitmap): String {
    val out = ByteArrayOutputStream()
    page.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  private fun contextText(title: String, recognised: String) = buildString {
    append("Note title: ").append(title.ifBlank { "(untitled)" }).append('\n')
    if (recognised.isNotBlank()) append("The app read this maths on the page (may contain mistakes): ").append(recognised).append('\n')
  }

  /** Runs off the main thread. Returns the answer, or throws with a message to show. */
  suspend fun ask(
    ctx: Context, provider: AiProvider, model: String,
    history: List<AiTurn>, question: String, pageJpeg: String?, title: String, recognised: String
  ): String = withContext(Dispatchers.IO) {
    val key = KeyVault.load(ctx, provider) ?: error("Add your ${provider.label} API key in Settings → AI assistant")
    val q = contextText(title, recognised) + "\nQuestion: " + question
    when (provider) {
      AiProvider.CLAUDE -> askClaude(key, model.ifBlank { ClaudeModels.first().first }, history, q, pageJpeg)
      AiProvider.GEMINI -> askGemini(key, model, history, q, pageJpeg)
    }
  }

  private fun askClaude(key: String, model: String, history: List<AiTurn>, q: String, page: String?): String {
    val client = AnthropicOkHttpClient.builder().apiKey(key).build()
    try {
      val b = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16000L)
        .system(SYSTEM)
        // Tutoring answers: medium effort (stated, since defaults differ between models)
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
      // If a safety check declines the request, the API retries it on a suitable model by itself
      if (model == "claude-opus-5-5" || model == "claude-sonnet-5-5") {
        b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
      }
      for (t in history) if (t.fromUser) b.addUserMessage(t.text) else b.addAssistantMessage(t.text)
      val blocks = ArrayList<ContentBlockParam>()
      if (page != null) blocks += ContentBlockParam.ofImage(
        ImageBlockParam.builder().source(
          Base64ImageSource.builder().data(page).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
        ).build()
      )
      blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(q).build())
      b.addUserMessageOfBlockParams(blocks)
      val res = client.messages().create(b.build())
      val text = res.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("").trim()
      if (res.stopReason().map { it.toString() }.orElse("") == "refusal" && text.isBlank()) error("Claude declined to answer that.")
      return text.ifBlank { error("Claude sent an empty answer.") }
    } finally {
      client.close()
    }
  }

  // ---------- Gemini (Google AI Studio key, REST API) ----------
  private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta"
  @Volatile private var geminiAuto: String? = null

  private fun http(url: String, key: String, body: String?): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 20_000; c.readTimeout = 120_000
    c.setRequestProperty("x-goog-api-key", key)
    if (body != null) {
      c.requestMethod = "POST"; c.doOutput = true
      c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }
    val code = c.responseCode
    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
    if (code !in 200..299) {
      val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
      error("Gemini: ${msg ?: "error $code"}")
    }
    return JSONObject(text)
  }

  /** With no model chosen: the newest Gemini "flash" model that can answer (looked up once). */
  private fun pickGemini(key: String): String {
    geminiAuto?.let { return it }
    val list = http("$GEMINI/models?pageSize=200", key, null).optJSONArray("models") ?: JSONArray()
    val names = (0 until list.length()).map { list.getJSONObject(it) }
      .filter { m ->
        val methods = m.optJSONArray("supportedGenerationMethods")
        (0 until (methods?.length() ?: 0)).any { methods!!.getString(it) == "generateContent" }
      }
      .map { it.getString("name").removePrefix("models/") }
      .filter { it.startsWith("gemini-") && "flash" in it && listOf("lite", "image", "tts", "live", "audio", "exp", "preview").none { w -> w in it } }
    fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    val best = names.maxByOrNull { version(it) } ?: "gemini-2.5-flash"
    geminiAuto = best
    return best
  }

  private fun askGemini(key: String, model: String, history: List<AiTurn>, q: String, page: String?): String {
    val m = model.ifBlank { pickGemini(key) }
    val contents = JSONArray()
    for (t in history) contents.put(JSONObject().put("role", if (t.fromUser) "user" else "model").put("parts", JSONArray().put(JSONObject().put("text", t.text))))
    val parts = JSONArray()
    if (page != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", page)))
    parts.put(JSONObject().put("text", q))
    contents.put(JSONObject().put("role", "user").put("parts", parts))
    val body = JSONObject()
      .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
      .put("contents", contents)
    val res = http("$GEMINI/models/$m:generateContent", key, body.toString())
    val cand = res.optJSONArray("candidates")?.optJSONObject(0)
    val out = cand?.optJSONObject("content")?.optJSONArray("parts")
    val text = (0 until (out?.length() ?: 0)).mapNotNull { out!!.getJSONObject(it).optString("text").takeIf { s -> s.isNotEmpty() } }.joinToString("").trim()
    if (text.isBlank()) error(if (cand?.optString("finishReason") == "SAFETY") "Gemini declined to answer that." else "Gemini sent an empty answer.")
    return text
  }
}

/** A small robot (the assistant's button). */
@Composable
fun RobotIcon(color: Color, size: Dp = 20.dp) {
  Canvas(Modifier.size(size)) {
    val s = this.size.width
    val sw = s * 0.08f
    // Antenna
    drawLine(color, Offset(s / 2, s * 0.08f), Offset(s / 2, s * 0.24f), sw, StrokeCap.Round)
    drawCircle(color, s * 0.06f, Offset(s / 2, s * 0.08f))
    // Head
    drawRoundRect(color, Offset(s * 0.16f, s * 0.26f), Size(s * 0.68f, s * 0.56f), CornerRadius(s * 0.16f), style = Stroke(width = sw))
    // Ears
    drawLine(color, Offset(s * 0.06f, s * 0.48f), Offset(s * 0.06f, s * 0.62f), sw, StrokeCap.Round)
    drawLine(color, Offset(s * 0.94f, s * 0.48f), Offset(s * 0.94f, s * 0.62f), sw, StrokeCap.Round)
    // Eyes and mouth
    drawCircle(color, s * 0.065f, Offset(s * 0.37f, s * 0.48f))
    drawCircle(color, s * 0.065f, Offset(s * 0.63f, s * 0.48f))
    drawLine(color, Offset(s * 0.38f, s * 0.66f), Offset(s * 0.62f, s * 0.66f), sw, StrokeCap.Round)
  }
}

/**
 * The assistant panel: a chat about the open page. [ask] sends a question (the page goes with
 * it); [turns] is the conversation so far.
 */
@Composable
fun AssistantPanel(
  provider: AiProvider,
  hasKey: Boolean,
  turns: List<AiTurn>,
  busy: Boolean,
  onAsk: (String) -> Unit,
  onSwitchProvider: (AiProvider) -> Unit,
  onClear: () -> Unit,
  onOpenSettings: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  var draft by remember { mutableStateOf("") }
  val list = rememberLazyListState()
  LaunchedEffect(turns.size, busy) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1 + if (busy) 1 else 0) }
  fun send() { val q = draft.trim(); if (q.isNotEmpty() && !busy) { onAsk(q); draft = "" } }
  Surface(shape = RoundedCornerShape(26.dp), color = EditorChrome.menu, shadowElevation = 12.dp, modifier = modifier) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        RobotIcon(OneUi9Tokens.SamsungBlue, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text("Assistant", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = EditorChrome.text, modifier = Modifier.weight(1f))
        // Which AI answers
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(if (EditorChrome.dark) Color(0xFF2A2A2D) else Color(0xFFF0F0F3)).padding(2.dp)) {
          AiProvider.entries.forEach { p ->
            val sel = p == provider
            Box(
              Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) EditorChrome.menu else Color.Transparent)
                .clickable { onSwitchProvider(p) }.padding(horizontal = 10.dp, vertical = 5.dp)
            ) { Text(p.label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = EditorChrome.text) }
          }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClose).semantics { contentDescription = "Close assistant" }, contentAlignment = Alignment.Center) {
          Text("✕", fontSize = 15.sp, color = EditorChrome.secondaryText)
        }
      }
      Spacer(Modifier.height(8.dp))
      if (!hasKey) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
          Text("Add your ${provider.label} API key to ask questions about this page.", fontSize = 15.sp, color = EditorChrome.text)
          Spacer(Modifier.height(12.dp))
          Box(Modifier.clip(RoundedCornerShape(20.dp)).background(OneUi9Tokens.SamsungBlue).clickable(onClick = onOpenSettings).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text("Open Settings", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
          }
        }
        return@Column
      }
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (turns.isEmpty()) item {
          Text(
            "Ask anything about this page: \"Is my working right?\", \"Explain step 3\", \"What's the next step?\". The assistant sees the whole page each time you ask.",
            fontSize = 14.sp, color = EditorChrome.secondaryText, modifier = Modifier.padding(vertical = 8.dp)
          )
        }
        items(turns) { t ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = if (t.fromUser) Arrangement.End else Arrangement.Start) {
            SelectionContainer {
              Text(
                t.text, fontSize = 15.sp, color = if (t.fromUser) Color.White else EditorChrome.text,
                modifier = Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(18.dp))
                  .background(if (t.fromUser) OneUi9Tokens.SamsungBlue else if (EditorChrome.dark) Color(0xFF2A2A2D) else Color(0xFFF1F2F5))
                  .padding(horizontal = 14.dp, vertical = 10.dp)
              )
            }
          }
        }
        if (busy) item {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OneUi9Tokens.SamsungBlue)
            Spacer(Modifier.width(8.dp))
            Text("Looking at your page…", fontSize = 14.sp, color = EditorChrome.secondaryText)
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = draft, onValueChange = { draft = it },
          placeholder = { Text("Ask about this page", fontSize = 15.sp) },
          maxLines = 4,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { send() }),
          shape = RoundedCornerShape(22.dp),
          colors = OutlinedTextFieldDefaults.colors(focusedTextColor = EditorChrome.text, unfocusedTextColor = EditorChrome.text),
          modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Box(
          Modifier.size(46.dp).clip(CircleShape).background(if (draft.isBlank() || busy) Color(0xFFB8C6E8) else OneUi9Tokens.SamsungBlue)
            .clickable(enabled = draft.isNotBlank() && !busy) { send() }.semantics { contentDescription = "Send" },
          contentAlignment = Alignment.Center
        ) { Text("↑", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
      }
      if (turns.isNotEmpty()) {
        Text(
          "New conversation", fontSize = 13.sp, color = OneUi9Tokens.SamsungBlue,
          modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy, onClick = onClear).padding(4.dp)
        )
      }
    }
  }
}
