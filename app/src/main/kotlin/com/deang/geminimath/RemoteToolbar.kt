package com.deang.geminimath

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject

/** What the phone toolbar asks the canvas to do. */
sealed interface RemoteCommand {
  data class Tool(val tool: NoteTool) : RemoteCommand
  data class PenColor(val argb: Long) : RemoteCommand
  data class PenSize(val width: Float) : RemoteCommand
  data class Pen(val type: PenType) : RemoteCommand
  data class Line(val style: LineStyle) : RemoteCommand
  data class Straighten(val on: Boolean) : RemoteCommand
  data object Solve : RemoteCommand
}

/**
 * "Use phone as toolbar": the tablet or Googlebook showing a note is the *canvas*; a phone running
 * the app is the *toolbar*. They find each other with Nearby Connections (Bluetooth + Wi-Fi, no
 * internet), the phone sends tool taps, and the canvas sends back its state so the phone shows
 * what's selected.
 */
object RemoteToolbarLink {
  private const val SERVICE_ID = "com.deang.geminimath.toolbar"
  private val STRATEGY = Strategy.P2P_POINT_TO_POINT

  /** LISTENING: a phone with the app open, waiting for a tablet to ask for a toolbar. */
  enum class Role { NONE, LISTENING, CANVAS, TOOLBAR }

  var role by mutableStateOf(Role.NONE)
    private set
  private val statusState = mutableStateOf("")
  /** What the link is doing, shown in the UI (and logged under the "Link" tag). */
  var status: String
    get() = statusState.value
    private set(v) { statusState.value = v; if (v.isNotEmpty()) android.util.Log.i("Link", "[$role] $v") }
  /** Name of the connected device, or null. */
  var peerName by mutableStateOf<String?>(null)
    private set

  /** Canvas side: handles commands from the phone. Set while the editor is on screen. */
  @Volatile var onCommand: ((RemoteCommand) -> Unit)? = null

  /** Toolbar side: the canvas's current tool state and toolbar layout. */
  var remoteState by mutableStateOf<ToolbarState?>(null)
    private set
  var remoteLayout by mutableStateOf<ToolbarLayout?>(null)
    private set

  private var client: ConnectionsClient? = null
  // UI state: the canvas chip and the state sync react when the connection completes
  private var endpointId by mutableStateOf<String?>(null)
  private var lastSentState: String? = null

  /** Runtime permissions Nearby needs on this Android version. */
  fun requiredPermissions(): Array<String> = when {
    // Android 17 adds a runtime permission for the local network, which Nearby's Wi-Fi needs
    Build.VERSION.SDK_INT >= 37 -> arrayOf(
      Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
      Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
      "android.permission.ACCESS_LOCAL_NETWORK"
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
      Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
      Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES
    )
    else -> arrayOf(
      Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
      Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION
    )
  }

  private fun deviceName() = Build.MODEL ?: "Android device"

  private val payloads = object : PayloadCallback() {
    override fun onPayloadReceived(id: String, payload: Payload) {
      val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
      runCatching { handle(JSONObject(text)) }
    }
    override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
  }

  private val lifecycle = object : ConnectionLifecycleCallback() {
    override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
      // Both ends are this app, started on purpose by the user on both devices: accept
      status = "Connecting to ${info.endpointName}…"
      peerName = info.endpointName
      client?.acceptConnection(id, payloads)
    }
    override fun onConnectionResult(id: String, result: ConnectionResolution) {
      if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
        endpointId = id
        // A listening phone becomes the toolbar as soon as a tablet asks for one
        if (role == Role.LISTENING) role = Role.TOOLBAR
        status = "Connected to ${peerName ?: "device"}"
        lastSentState = null
        main.removeCallbacks(retry)
        client?.stopAdvertising()
        client?.stopDiscovery()
        if (role == Role.CANVAS) appContext?.let { ToolbarWake.stopBeacon(it) }
      } else {
        status = "Couldn't connect. Trying again…"
        peerName = null
        retrySearch()
      }
    }
    override fun onDisconnected(id: String) {
      endpointId = null
      peerName = null
      remoteState = null
      status = if (role == Role.NONE) "" else "Disconnected"
      // The tablet stopped: a phone that switched by itself goes back to listening
      if (role == Role.TOOLBAR && autoStarted) role = Role.LISTENING
      // Keep looking, so the link comes back by itself
      client?.let { c ->
        if (role == Role.CANVAS) { c.stopAdvertising(); advertise(c); appContext?.let { ToolbarWake.startBeacon(it) } }
        else retrySearch()
      }
    }
  }

  private val main = android.os.Handler(android.os.Looper.getMainLooper())
  private val retry = Runnable {
    val c = client ?: return@Runnable
    if (endpointId != null || (role != Role.TOOLBAR && role != Role.LISTENING)) return@Runnable
    android.util.Log.i("Link", "retrying the search")
    // Drop the stuck attempt and search again, so the tablet is "found" again and asked again
    c.stopAllEndpoints()
    c.stopDiscovery()
    discover(c)
  }

  /** Phone side: try again shortly (a half-finished attempt can't simply be repeated). */
  private fun retrySearch() {
    main.removeCallbacks(retry)
    main.postDelayed(retry, 1500)
  }

  private val discovery = object : EndpointDiscoveryCallback() {
    override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
      if (endpointId != null) return
      status = "Found ${info.endpointName}"
      client?.requestConnection(deviceName(), id, lifecycle)
        ?.addOnFailureListener { status = "Couldn't connect to ${info.endpointName}"; retrySearch() }
      // Watchdog: an attempt can hang (e.g. the tablet restarted); start over if not connected soon
      main.removeCallbacks(retry)
      main.postDelayed(retry, 12_000)
    }
    override fun onEndpointLost(id: String) {}
  }

  private var appContext: Context? = null

  private fun advertise(c: ConnectionsClient) {
    c.startAdvertising(deviceName(), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
      .addOnSuccessListener { if (endpointId == null) status = "Waiting for your phone. Open Gemini Math Lab on it and choose Use as toolbar." }
      .addOnFailureListener { status = "Couldn't start: ${it.message}" }
  }

  private fun discover(c: ConnectionsClient) {
    c.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
      .addOnSuccessListener { if (endpointId == null) status = "Looking for your tablet. Open a note on it and choose Use phone as toolbar." }
      .addOnFailureListener { status = "Couldn't search: ${it.message}" }
  }

  /** Tablet/Googlebook: offer the open note's canvas to a phone. */
  fun startCanvas(context: Context) {
    // Already offering the canvas: restarting now would break a phone that's halfway through connecting
    if (role == Role.CANVAS && client != null) return
    stop()
    role = Role.CANVAS
    appContext = context.applicationContext
    client = Nearby.getConnectionsClient(context.applicationContext).also { advertise(it) }
    // Also a small Bluetooth beacon, which wakes the app on a phone where it's closed
    ToolbarWake.startBeacon(context.applicationContext)
  }

  private var autoStarted = false

  /**
   * Phone with the app open: quietly look for a tablet that starts "Use phone as toolbar" and
   * switch to toolbar mode when one does. Needs the Nearby permissions already granted.
   */
  fun listen(context: Context, forMs: Long = 20_000) {
    // A Nearby search (Bluetooth and Wi-Fi scanning) costs a lot of battery, so it only runs for a
    // short while: when the app opens, and again whenever the low-power Bluetooth watch
    // (ToolbarWake) sees a tablet's beacon
    main.removeCallbacks(listenTimeout)
    main.postDelayed(listenTimeout, forMs)
    if (role != Role.NONE) return
    val granted = requiredPermissions().all {
      androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    if (!granted) return
    role = Role.LISTENING
    autoStarted = true
    client = Nearby.getConnectionsClient(context.applicationContext).also { discover(it) }
  }

  private val listenTimeout = Runnable { if (role == Role.LISTENING && endpointId == null) stop() }

  /** Stops listening when the app goes to the background (it doesn't search while closed). */
  fun stopListening() { main.removeCallbacks(listenTimeout); if (role == Role.LISTENING) stop() }

  /** Phone: find a canvas and become its toolbar. */
  fun startToolbar(context: Context) {
    stop()
    autoStarted = false
    role = Role.TOOLBAR
    client = Nearby.getConnectionsClient(context.applicationContext).also { discover(it) }
  }

  fun stop() {
    main.removeCallbacks(retry)
    if (role == Role.CANVAS) appContext?.let { ToolbarWake.stopBeacon(it) }
    client?.run { stopAdvertising(); stopDiscovery(); stopAllEndpoints() }
    client = null
    endpointId = null
    peerName = null
    remoteState = null
    role = Role.NONE
    status = ""
  }

  val connected: Boolean get() = peerName != null && endpointId != null

  private fun sendJson(o: JSONObject) {
    val id = endpointId ?: return
    client?.sendPayload(id, Payload.fromBytes(o.toString().toByteArray(Charsets.UTF_8)))
  }

  /** Toolbar → canvas. */
  fun send(cmd: RemoteCommand) = sendJson(encode(cmd))

  /** Canvas → toolbar; only sent when something changed. */
  fun sendState(state: ToolbarState, layout: ToolbarLayout) {
    val o = JSONObject()
      .put("t", "state")
      .put("tool", state.tool.name)
      .put("color", state.penColor)
      .put("size", state.thickness.toDouble())
      .put("undo", state.canUndo)
      .put("redo", state.canRedo)
      .put("shapes", state.autoShapes)
      .put("locked", state.canvasLocked)
      .put("pen", state.penType.name)
      .put("line", state.lineStyle.name)
      .put("colors", state.quickColors.joinToString(",") { java.lang.Long.toHexString(it) })
      .put("dark", state.dark)
      .put("hl", state.highlighterColor)
      .put("layout", layout.encode())
    val s = o.toString()
    if (s == lastSentState) return
    lastSentState = s
    sendJson(o)
  }

  fun encode(cmd: RemoteCommand): JSONObject = when (cmd) {
    is RemoteCommand.Tool -> JSONObject().put("t", "tool").put("v", cmd.tool.name)
    is RemoteCommand.PenColor -> JSONObject().put("t", "color").put("v", cmd.argb)
    is RemoteCommand.PenSize -> JSONObject().put("t", "size").put("v", cmd.width.toDouble())
    RemoteCommand.Solve -> JSONObject().put("t", "solve")
    is RemoteCommand.Pen -> JSONObject().put("t", "pen").put("v", cmd.type.name)
    is RemoteCommand.Line -> JSONObject().put("t", "line").put("v", cmd.style.name)
    is RemoteCommand.Straighten -> JSONObject().put("t", "straighten").put("v", cmd.on)
  }

  fun decode(o: JSONObject): RemoteCommand? = when (o.optString("t")) {
    "tool" -> NoteTool.entries.find { it.name == o.optString("v") }?.let { RemoteCommand.Tool(it) }
    "color" -> RemoteCommand.PenColor(o.optLong("v"))
    "size" -> RemoteCommand.PenSize(o.optDouble("v").toFloat())
    "solve" -> RemoteCommand.Solve
    "pen" -> PenType.entries.find { it.name == o.optString("v") }?.let { RemoteCommand.Pen(it) }
    "line" -> LineStyle.entries.find { it.name == o.optString("v") }?.let { RemoteCommand.Line(it) }
    "straighten" -> RemoteCommand.Straighten(o.optBoolean("v"))
    else -> null
  }

  /** Phone: the toolbar was closed. Tell the tablet (so it stops waiting and stops waking this phone), then stop. */
  fun closeFromToolbar() {
    if (endpointId == null) { stop(); return }
    sendJson(JSONObject().put("t", "bye"))
    main.postDelayed({ stop() }, 400)
  }

  private fun handle(o: JSONObject) {
    // The phone closed its toolbar: the tablet stops offering the canvas
    if (o.optString("t") == "bye") {
      if (role == Role.CANVAS) { stop(); status = "" }
      return
    }
    if (o.optString("t") == "state") {
      remoteState = ToolbarState(
        tool = runCatching { MyScriptTool.valueOf(o.optString("tool")) }.getOrDefault(MyScriptTool.PEN),
        penColor = o.optLong("color"),
        thickness = o.optDouble("size", 8.5).toFloat(),
        canUndo = o.optBoolean("undo"),
        canRedo = o.optBoolean("redo"),
        autoShapes = o.optBoolean("shapes"),
        canvasLocked = o.optBoolean("locked"),
        penType = PenType.entries.find { it.name == o.optString("pen") } ?: PenType.PEN,
        lineStyle = LineStyle.entries.find { it.name == o.optString("line") } ?: LineStyle.SOLID,
        quickColors = o.optString("colors").split(',').mapNotNull { it.toLongOrNull(16) }.takeIf { it.size == 5 } ?: ToolbarPenColours.map { it.first },
        dark = o.optBoolean("dark"),
        highlighterColor = o.optLong("hl", 0xFFFFE600L)
      )
      remoteLayout = ToolbarLayout.decode(o.optString("layout"))
      return
    }
    decode(o)?.let { cmd -> onCommand?.invoke(cmd) }
  }
}

