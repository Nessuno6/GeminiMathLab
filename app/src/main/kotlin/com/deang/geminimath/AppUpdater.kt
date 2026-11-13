package com.deang.geminimath

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from this project's GitHub Releases: finds a newer release, downloads its APK and hands it
 * to Android's installer, which asks you to confirm. Android only installs it over this app when it's
 * signed with the same key, so a changed or tampered APK can't replace the app.
 *
 * Release builds look once a day when the app starts (Settings › About › Check automatically);
 * Settings › About › Check for updates looks straight away in any build.
 */
object AppUpdater {
  const val REPO = "Nessuno6/GeminiMathLab"
  private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

  data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

  sealed interface State {
    data object Idle : State
    data object Checking : State
    data object UpToDate : State
    data class Available(val release: Release) : State
    data class Downloading(val release: Release, val progress: Float) : State
    data class Installing(val release: Release) : State
    data class Failed(val message: String) : State
  }

  var state by mutableStateOf<State>(State.Idle)
    private set
  /** The update dialog is showing (it can be closed while an update stays available). */
  var dialogOpen by mutableStateOf(false)

  private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

  fun autoCheck(ctx: Context) = prefs(ctx).getBoolean("auto", true)
  fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

  fun currentVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

  private fun isDebugBuild(ctx: Context) = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Whether [remote] ("v1.2.0" or "1.2") is a later version than [local]. */
  fun isNewer(remote: String, local: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-')
      .split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(remote); val b = parts(local)
    for (i in 0 until maxOf(a.size, b.size)) {
      val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
      if (x != y) return x > y
    }
    return false
  }

  /** Reads a GitHub "latest release" reply; null when it has no APK attached. */
  fun parseRelease(json: String): Release? {
    val o = JSONObject(json)
    val assets = o.optJSONArray("assets") ?: return null
    for (i in 0 until assets.length()) {
      val a = assets.getJSONObject(i)
      if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
        return Release(
          version = o.optString("tag_name").removePrefix("v"),
          notes = o.optString("body").trim(),
          apkUrl = a.optString("browser_download_url"),
          apkSize = a.optLong("size"),
          pageUrl = o.optString("html_url")
        )
      }
    }
    return null
  }

  private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 15_000; readTimeout = 30_000
    instanceFollowRedirects = true
    setRequestProperty("User-Agent", "GeminiMathLab-updater")
  }

  /** Looks for a newer release. [quiet]: from the automatic check, so "up to date" and errors show nothing. */
  suspend fun check(ctx: Context, quiet: Boolean = false) {
    if (state is State.Checking || state is State.Downloading || state is State.Installing) return
    state = State.Checking
    val result = withContext(Dispatchers.IO) {
      runCatching {
        val c = open("https://api.github.com/repos/$REPO/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
          when (c.responseCode) {
            200 -> parseRelease(c.inputStream.bufferedReader().readText())
            404 -> null // no release yet
            else -> error("GitHub answered ${c.responseCode}")
          }
        } finally { c.disconnect() }
      }
    }
    prefs(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
    val release = result.getOrElse {
      state = if (quiet) State.Idle else State.Failed("Couldn't check for updates: ${it.message ?: "no connection"}")
      return
    }
    if (release != null && isNewer(release.version, currentVersion(ctx))) {
      state = State.Available(release)
      // The automatic check doesn't bring back a version you chose to skip
      if (!quiet || prefs(ctx).getString("skipped", null) != release.version) dialogOpen = true
    } else {
      state = if (quiet) State.Idle else State.UpToDate
    }
  }

  /** The once-a-day check when the app starts (release builds, if it's on). */
  suspend fun checkOnStart(ctx: Context) {
    if (isDebugBuild(ctx) || !autoCheck(ctx)) return
    if (System.currentTimeMillis() - prefs(ctx).getLong("last", 0L) < CHECK_EVERY_MS) return
    check(ctx, quiet = true)
  }

  fun skip(ctx: Context, release: Release) {
    prefs(ctx).edit().putString("skipped", release.version).apply()
    dialogOpen = false
  }

  /** Downloads the APK, then installs it (asking for "Install unknown apps" the first time). */
  suspend fun downloadAndInstall(ctx: Context, release: Release) {
    val app = ctx.applicationContext
    if (!app.packageManager.canRequestPackageInstalls()) {
      // Android asks once per app: send you to the switch, then you tap Update again
      state = State.Available(release)
      runCatching {
        ctx.startActivity(
          Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
      }
      return
    }
    state = State.Downloading(release, 0f)
    val file = withContext(Dispatchers.IO) {
      runCatching {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // older downloads
        val out = File(dir, "GeminiMathLab-${release.version}.apk")
        val c = open(release.apkUrl)
        try {
          if (c.responseCode !in 200..299) error("download answered ${c.responseCode}")
          val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
          c.inputStream.use { input ->
            out.outputStream().use { output ->
              val buf = ByteArray(256 * 1024)
              var done = 0L; var lastShown = 0L
              while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                done += n
                if (total > 0 && done - lastShown > total / 200) {
                  lastShown = done
                  state = State.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                }
              }
            }
          }
          if (release.apkSize > 0 && out.length() != release.apkSize) error("the download was incomplete")
          out
        } finally { c.disconnect() }
      }
    }.getOrElse {
      state = State.Failed("Couldn't download the update: ${it.message ?: "no connection"}")
      return
    }
    state = State.Installing(release)
    withContext(Dispatchers.IO) {
      runCatching { install(app, file) }.onFailure {
        state = State.Failed("Couldn't start the install: ${it.message}")
      }
    }
  }

  /** Writes the APK into an install session; Android then asks you to confirm (see [InstallResultReceiver]). */
  private fun install(ctx: Context, apk: File) {
    val installer = ctx.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(ctx.packageName)
      setSize(apk.length())
    }
    val id = installer.createSession(params)
    installer.openSession(id).use { session ->
      session.openWrite("base.apk", 0, apk.length()).use { out ->
        apk.inputStream().use { it.copyTo(out, 256 * 1024) }
        session.fsync(out)
      }
      val result = PendingIntent.getBroadcast(
        ctx, id, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      )
      session.commit(result.intentSender)
    }
  }

  internal fun onInstallResult(ctx: Context, intent: Intent) {
    when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
      PackageInstaller.STATUS_SUCCESS -> { state = State.Idle; dialogOpen = false }
      else -> {
        val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        state = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
          (state as? State.Installing)?.let { State.Available(it.release) } ?: State.Idle
        } else State.Failed("The update wasn't installed" + (why?.let { ": $it" } ?: "."))
      }
    }
  }
}

/** Android's answer to an install session: ask to confirm, done, or failed. */
class InstallResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) = AppUpdater.onInstallResult(context, intent)
}

private fun megabytes(bytes: Long) = if (bytes <= 0) "" else " · ${(bytes + 524_288) / 1_048_576} MB"

/** The update dialog over the whole app; also runs the once-a-day check when the app starts. */
@Composable
fun UpdatePrompt() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) { AppUpdater.checkOnStart(ctx) }
  if (!AppUpdater.dialogOpen) return
  val s = AppUpdater.state
  val release = when (s) {
    is AppUpdater.State.Available -> s.release
    is AppUpdater.State.Downloading -> s.release
    is AppUpdater.State.Installing -> s.release
    else -> null
  }
  if (release == null && s !is AppUpdater.State.Failed) return
  val busy = s is AppUpdater.State.Downloading || s is AppUpdater.State.Installing
  AlertDialog(
    onDismissRequest = { if (!busy) AppUpdater.dialogOpen = false },
    title = { Text(if (release != null) "Update to ${release.version}" else "Update") },
    text = {
      Column {
        when (s) {
          is AppUpdater.State.Failed -> Text(s.message, fontSize = 15.sp)
          is AppUpdater.State.Downloading -> {
            Text("Downloading${megabytes(s.release.apkSize)}…", fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
          }
          is AppUpdater.State.Installing -> Text("Confirm the install when Android asks. The app restarts with the new version.", fontSize = 15.sp)
          else -> {
            Text("You have ${AppUpdater.currentVersion(ctx)}. The new version${megabytes(release!!.apkSize)} installs over this one and keeps your notes.", fontSize = 15.sp)
            if (release.notes.isNotBlank()) {
              Spacer(Modifier.height(12.dp))
              Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Text(release.notes, fontSize = 14.sp)
              }
            }
            if (!ctx.packageManager.canRequestPackageInstalls()) {
              Spacer(Modifier.height(12.dp))
              Text("The first time, Android asks you to allow this app to install updates: turn it on, come back and tap Update again.", fontSize = 13.sp)
            }
          }
        }
      }
    },
    confirmButton = {
      when {
        s is AppUpdater.State.Failed -> TextButton(onClick = { AppUpdater.dialogOpen = false }) { Text("OK") }
        !busy && release != null -> TextButton(onClick = { scope.launch { AppUpdater.downloadAndInstall(ctx, release) } }) { Text("Update") }
      }
    },
    dismissButton = {
      if (!busy && release != null) {
        Row {
          TextButton(onClick = { AppUpdater.skip(ctx, release) }) { Text("Skip") }
          TextButton(onClick = { AppUpdater.dialogOpen = false }) { Text("Later") }
        }
      }
    }
  )
}
