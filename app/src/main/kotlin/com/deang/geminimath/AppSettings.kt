package com.deang.geminimath

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-wide preferences. Each property is Compose state (screens update when it changes) and is
 * written to SharedPreferences straight away.
 */
class AppSettings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("gemini_math_ui_prefs", Context.MODE_PRIVATE)

  private var _uiStyle by mutableStateOf(UiStyleMode.fromId(prefs.getString("ui_style_mode", UiStyleMode.ONEUI_9.id)))
  var uiStyle: UiStyleMode
    get() = _uiStyle
    set(v) { _uiStyle = v; prefs.edit().putString("ui_style_mode", v.id).apply() }

  private var _penOnly by mutableStateOf(prefs.getBoolean("disable_finger_writing", true))
  /** Only the S Pen draws; fingers scroll and tap. */
  var penOnly: Boolean
    get() = _penOnly
    set(v) { _penOnly = v; prefs.edit().putBoolean("disable_finger_writing", v).apply() }

  private var _penButtonShortcuts by mutableStateOf(prefs.getBoolean("spen_button_shortcuts", true))
  /** Hold the S Pen button to erase; press it while hovering to open the quick menu. */
  var penButtonShortcuts: Boolean
    get() = _penButtonShortcuts
    set(v) { _penButtonShortcuts = v; prefs.edit().putBoolean("spen_button_shortcuts", v).apply() }

  private var _autoSolve by mutableStateOf(prefs.getBoolean("auto_solve_equals", true))
  /** Solve automatically after writing "=". */
  var autoSolve: Boolean
    get() = _autoSolve
    set(v) { _autoSolve = v; prefs.edit().putBoolean("auto_solve_equals", v).apply() }

  private var _angleDegrees by mutableStateOf(prefs.getBoolean("angle_degrees", false))
  var angleDegrees: Boolean
    get() = _angleDegrees
    set(v) { _angleDegrees = v; prefs.edit().putBoolean("angle_degrees", v).apply(); applyToSolver() }

  private var _exactFractions by mutableStateOf(prefs.getBoolean("exact_fractions", true))
  /** Show results as fractions and roots (1/3, √2) instead of decimals. */
  var exactFractions: Boolean
    get() = _exactFractions
    set(v) { _exactFractions = v; prefs.edit().putBoolean("exact_fractions", v).apply(); applyToSolver() }

  private var _decimals by mutableStateOf(prefs.getInt("decimal_places", 4).coerceIn(0, 10))
  var decimals: Int
    get() = _decimals
    set(v) { _decimals = v.coerceIn(0, 10); prefs.edit().putInt("decimal_places", _decimals).apply(); applyToSolver() }

  private var _defaultPaper by mutableStateOf(
    runCatching { PaperPattern.valueOf(prefs.getString("default_paper", PaperPattern.GRID.name)!!) }.getOrDefault(PaperPattern.GRID)
  )
  /** Paper used for new notes. */
  var defaultPaper: PaperPattern
    get() = _defaultPaper
    set(v) { _defaultPaper = v; prefs.edit().putString("default_paper", v.name).apply() }

  private var _driveBackup by mutableStateOf(prefs.getBoolean("drive_backup", false))
  var driveBackup: Boolean
    get() = _driveBackup
    set(v) { _driveBackup = v; prefs.edit().putBoolean("drive_backup", v).apply() }

  private var _lastSyncStatus by mutableStateOf(prefs.getString("last_sync_status", null))
  var lastSyncStatus: String?
    get() = _lastSyncStatus
    set(v) { _lastSyncStatus = v; prefs.edit().putString("last_sync_status", v).apply() }

  private var _toolbar by mutableStateOf(ToolbarLayout.decode(prefs.getString("toolbar_tools", null)))
  /** Order of the optional tools on the handwriting toolbar, and which were removed. */
  var toolbar: ToolbarLayout
    get() = _toolbar
    set(v) { _toolbar = v; prefs.edit().putString("toolbar_tools", v.encode()).apply() }

  private var _autoShapes by mutableStateOf(prefs.getBoolean("auto_fix_shapes", false))
  /** "Auto-fix shapes": hand-drawn lines, triangles, rectangles and ellipses are tidied up. */
  var autoShapes: Boolean
    get() = _autoShapes
    set(v) { _autoShapes = v; prefs.edit().putBoolean("auto_fix_shapes", v).apply() }

  private var _toolbarDock by mutableStateOf(
    runCatching { ToolbarDock.valueOf(prefs.getString("toolbar_dock", "TOP")!!) }.getOrDefault(ToolbarDock.TOP)
  )
  /** Which side of the editor the handwriting toolbar is on. */
  var toolbarDock: ToolbarDock
    get() = _toolbarDock
    set(v) { _toolbarDock = v; prefs.edit().putString("toolbar_dock", v.name).apply() }

  private var _fillOpacity by mutableStateOf(prefs.getFloat("fill_opacity", 0.6f).coerceIn(0.1f, 1f))
  /** How opaque bucket fills are (0.1–1). */
  var fillOpacity: Float
    get() = _fillOpacity
    set(v) { _fillOpacity = v.coerceIn(0.1f, 1f); prefs.edit().putFloat("fill_opacity", _fillOpacity).apply() }

  private var _picturesOnLayer2 by mutableStateOf(prefs.getBoolean("pictures_on_layer_2", false))
  /** Pictures (gallery, graphs) go on the note's second layer, made if needed, instead of the current one. */
  var picturesOnLayer2: Boolean
    get() = _picturesOnLayer2
    set(v) { _picturesOnLayer2 = v; prefs.edit().putBoolean("pictures_on_layer_2", v).apply() }

  // --- Highlighter, eraser and lasso settings (their panels open when you tap the selected tool) ---
  private var _hlColor by mutableStateOf(prefs.getLong("highlighter_color", 0xFFFFE600L))
  /** The highlighter's own colour (opaque; it's drawn see-through). */
  var highlighterColor: Long
    get() = _hlColor
    set(v) { _hlColor = v or 0xFF000000L; prefs.edit().putLong("highlighter_color", _hlColor).apply() }

  private var _hlSize by mutableStateOf(prefs.getInt("highlighter_size", 5).coerceIn(1, 10))
  var highlighterSize: Int
    get() = _hlSize
    set(v) { _hlSize = v.coerceIn(1, 10); prefs.edit().putInt("highlighter_size", _hlSize).apply() }

  private var _hlChisel by mutableStateOf(prefs.getBoolean("highlighter_chisel", true))
  /** Chisel tip (flat ends) or round tip. */
  var highlighterChisel: Boolean
    get() = _hlChisel
    set(v) { _hlChisel = v; prefs.edit().putBoolean("highlighter_chisel", v).apply() }

  private var _eraserArea by mutableStateOf(prefs.getBoolean("eraser_area", false))
  /** Area eraser (rubs out only the part you touch) instead of the stroke eraser (whole strokes). */
  var eraserArea: Boolean
    get() = _eraserArea
    set(v) { _eraserArea = v; prefs.edit().putBoolean("eraser_area", v).apply() }

  private var _eraserSize by mutableStateOf(prefs.getInt("eraser_size", 4).coerceIn(1, 10))
  var eraserSize: Int
    get() = _eraserSize
    set(v) { _eraserSize = v.coerceIn(1, 10); prefs.edit().putInt("eraser_size", _eraserSize).apply() }

  private var _eraseHlOnly by mutableStateOf(prefs.getBoolean("erase_highlighter_only", false))
  var eraseHighlighterOnly: Boolean
    get() = _eraseHlOnly
    set(v) { _eraseHlOnly = v; prefs.edit().putBoolean("erase_highlighter_only", v).apply() }

  private var _lassoRect by mutableStateOf(prefs.getBoolean("lasso_rectangle", false))
  /** Select with a rectangle instead of a free lasso loop. */
  var lassoRectangle: Boolean
    get() = _lassoRect
    set(v) { _lassoRect = v; prefs.edit().putBoolean("lasso_rectangle", v).apply() }

  private var _lassoPartial by mutableStateOf(prefs.getBoolean("lasso_partial", false))
  /** Select strokes that are only partly inside the selection too. */
  var lassoPartial: Boolean
    get() = _lassoPartial
    set(v) { _lassoPartial = v; prefs.edit().putBoolean("lasso_partial", v).apply() }

  // --- Tape (its panel opens when you tap the selected tape tool) ---
  private var _tapeColor by mutableStateOf(prefs.getLong("tape_color", TAPE_COLOR))
  var tapeColor: Long
    get() = _tapeColor
    set(v) { _tapeColor = v or 0xFF000000L; prefs.edit().putLong("tape_color", _tapeColor).apply() }

  private var _tapeSize by mutableStateOf(prefs.getInt("tape_size", 3).coerceIn(1, 10))
  var tapeSize: Int
    get() = _tapeSize
    set(v) { _tapeSize = v.coerceIn(1, 10); prefs.edit().putInt("tape_size", _tapeSize).apply() }

  private var _tapePattern by mutableStateOf(prefs.getInt("tape_pattern", 0).coerceIn(0, TapePattern.entries.size - 1))
  var tapePattern: TapePattern
    get() = TapePattern.entries[_tapePattern]
    set(v) { _tapePattern = v.ordinal; prefs.edit().putInt("tape_pattern", v.ordinal).apply() }

  private var _tapeStraight by mutableStateOf(prefs.getBoolean("tape_straighten", false))
  /** Tape goes on in a straight line from where you start to where you lift the pen. */
  var tapeStraighten: Boolean
    get() = _tapeStraight
    set(v) { _tapeStraight = v; prefs.edit().putBoolean("tape_straighten", v).apply() }

  // --- AI assistant (your own API keys, stored encrypted in KeyVault) ---
  private var _aiProvider by mutableStateOf(prefs.getInt("ai_provider", 0).coerceIn(0, AiProvider.entries.size - 1))
  var aiProvider: AiProvider
    get() = AiProvider.entries[_aiProvider]
    set(v) { _aiProvider = v.ordinal; prefs.edit().putInt("ai_provider", v.ordinal).apply() }

  private var _claudeModel by mutableStateOf(prefs.getString("claude_model", ClaudeModels.first().first) ?: ClaudeModels.first().first)
  var claudeModel: String
    get() = _claudeModel
    set(v) { _claudeModel = v; prefs.edit().putString("claude_model", v).apply() }

  private var _geminiModel by mutableStateOf(prefs.getString("gemini_model", "") ?: "")
  /** Empty: the newest Gemini Flash model, looked up when first used. */
  var geminiModel: String
    get() = _geminiModel
    set(v) { _geminiModel = v.trim(); prefs.edit().putString("gemini_model", v.trim()).apply() }

  // --- Laser pointer (its panel opens when you tap the selected laser) ---
  private var _laserColor by mutableStateOf(prefs.getLong("laser_color", 0xFFFF3B30L))
  /** The laser's colour; [LASER_RAINBOW] draws the trail in shifting rainbow colours. */
  var laserColor: Long
    get() = _laserColor
    set(v) { _laserColor = v; prefs.edit().putLong("laser_color", v).apply() }

  private var _stabilizer by mutableStateOf(prefs.getInt("stroke_stabilizer", 0).coerceIn(0, 10))
  /** Stroke stabiliser, 0 (off) to 10 (strongest): smooths the pen's path while you write. */
  var stabilizer: Int
    get() = _stabilizer
    set(v) { _stabilizer = v.coerceIn(0, 10); prefs.edit().putInt("stroke_stabilizer", _stabilizer).apply() }

  private var _toolbarColors by mutableStateOf(
    prefs.getString("toolbar_colors", null)?.split(',')?.mapNotNull { it.toLongOrNull(16) }?.takeIf { it.size == 5 }
      ?: ToolbarPenColours.map { it.first }
  )
  /** The five colours on the toolbar. Tap the selected one to change it. */
  val toolbarColors: List<Long> get() = _toolbarColors

  fun setToolbarColor(index: Int, argb: Long) {
    if (index !in 0..4) return
    _toolbarColors = _toolbarColors.toMutableList().also { it[index] = argb or 0xFF000000L }
    prefs.edit().putString("toolbar_colors", _toolbarColors.joinToString(",") { java.lang.Long.toHexString(it) }).apply()
  }

  private var _customColors by mutableStateOf(
    prefs.getString("custom_colors", "").orEmpty().split(',').mapNotNull { it.toLongOrNull(16) }
  )
  /** Colours you added with the colour picker (newest first, up to 12), shown after Samsung's palette. */
  val customColors: List<Long> get() = _customColors

  fun addCustomColor(argb: Long) {
    val c = argb or 0xFF000000L
    if (c in SamsungPalette) return
    _customColors = (listOf(c) + _customColors.filter { it != c }).take(12)
    prefs.edit().putString("custom_colors", _customColors.joinToString(",") { java.lang.Long.toHexString(it) }).apply()
  }

  init {
    applyToSolver()
  }

  fun applyToSolver() {
    MathSettings.angleDegrees = _angleDegrees
    MathSettings.exactFractions = _exactFractions
    MathSettings.decimals = _decimals
  }
}
