package com.deang.geminimath

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Opens the app on a phone where it's closed, when a tablet starts "Use phone as toolbar".
 *
 * The tablet sends a small Bluetooth beacon with this app's ID. The phone has asked Android to
 * watch for that beacon in the background (a scan that keeps running while the app is closed);
 * when Android sees it, it starts [ToolbarWakeReceiver], which brings the app to the front. Once
 * open, the app connects by itself (see [RemoteToolbarLink.listen]).
 *
 * Android only lets an app open its own screen from the background with "Display over other
 * apps" allowed; without it, a notification appears instead and one tap opens the toolbar.
 */
object ToolbarWake {
  private const val TAG = "Link"
  val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("7a1c0f2e-5b9d-4e1a-9c3f-6d2b8e4a1f70")
  const val EXTRA_WOKEN = "toolbar_woken"
  private const val CHANNEL = "phone_toolbar"

  /** True while the app's screen is showing (then the open app connects by itself). */
  @Volatile var appVisible = false

  private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

  private fun adapter(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter

  // ---------- Tablet: the beacon ----------

  private var advertising: AdvertiseCallback? = null

  @SuppressLint("MissingPermission")
  fun startBeacon(ctx: Context) {
    if (advertising != null || !granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE)) return
    val adv = adapter(ctx)?.bluetoothLeAdvertiser ?: return
    val cb = object : AdvertiseCallback() {
      override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { Log.i(TAG, "wake beacon on") }
      override fun onStartFailure(errorCode: Int) { Log.w(TAG, "wake beacon failed: $errorCode"); advertising = null }
    }
    advertising = cb
    runCatching {
      adv.startAdvertising(
        AdvertiseSettings.Builder()
          // Balanced (about 4 a second) instead of low latency (10 a second): still found within
          // a second or two, at well under half the radio time
          .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
          .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
          .setConnectable(false)
          .build(),
        AdvertiseData.Builder().addServiceUuid(SERVICE_UUID).setIncludeDeviceName(false).build(),
        cb
      )
    }.onFailure { advertising = null; Log.w(TAG, "wake beacon", it) }
  }

  @SuppressLint("MissingPermission")
  fun stopBeacon(ctx: Context) {
    val cb = advertising ?: return
    advertising = null
    if (granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE)) runCatching { adapter(ctx)?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    Log.i(TAG, "wake beacon off")
  }

  // ---------- Phone: the background watch ----------

  private fun scanIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
    ctx, 0, Intent(ctx, ToolbarWakeReceiver::class.java),
    // Mutable: Android adds the scan results to it
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
  )

  /** Phone: ask Android to watch for a tablet's beacon, even after the app is closed. */
  @SuppressLint("MissingPermission")
  fun registerWatch(ctx: Context) {
    if (!granted(ctx, Manifest.permission.BLUETOOTH_SCAN)) return
    val scanner = adapter(ctx)?.bluetoothLeScanner ?: return
    runCatching {
      val r = scanner.startScan(
        listOf(ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()),
        ScanSettings.Builder()
          .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
          .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
          .build(),
        scanIntent(ctx)
      )
      Log.i(TAG, "wake watch registered ($r)")
    }.onFailure { Log.w(TAG, "wake watch", it) }
  }

  /** Allowed to open the screen from the background ("Display over other apps")? */
  fun canOpenDirectly(ctx: Context) = Settings.canDrawOverlays(ctx)

  /** Called when the beacon is seen: bring the app up (directly, or through a notification). */
  fun wake(ctx: Context) {
    // App already open: start the (short) Nearby search now that a tablet is actually there
    if (appVisible) { RemoteToolbarLink.listen(ctx, 60_000); return }
    // Don't keep waking: the beacon is seen again and again while the tablet waits
    val prefs = ctx.getSharedPreferences("toolbar_wake", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    if (now - prefs.getLong("last", 0) < 20_000) return
    prefs.edit().putLong("last", now).apply()

    val open = Intent(ctx, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra(EXTRA_WOKEN, true)
    if (canOpenDirectly(ctx) && runCatching { ctx.startActivity(open) }.isSuccess) {
      Log.i(TAG, "woke: opened the app")
      return
    }
    // Otherwise: a notification that opens it
    if (!granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) { Log.i(TAG, "woke: no way to tell the user"); return }
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone as toolbar", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "When your tablet asks to use this phone as its toolbar"
    })
    val tap = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    nm.notify(
      7, NotificationCompat.Builder(ctx, CHANNEL)
        .setSmallIcon(R.drawable.launcher_math_monochrome)
        .setContentTitle("Use this phone as a toolbar?")
        .setContentText("Your tablet wants this phone as its handwriting toolbar. Tap to start.")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setAutoCancel(true)
        .setTimeoutAfter(60_000)
        .setContentIntent(tap)
        .build()
    )
    Log.i(TAG, "woke: notification")
  }

  fun clearNotification(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(7)
}

/** Started by Android when the tablet's beacon is seen (or after a restart, to watch again). */
class ToolbarWakeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ToolbarWake.registerWatch(context)
      else -> ToolbarWake.wake(context)
    }
  }
}
