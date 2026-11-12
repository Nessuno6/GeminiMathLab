package com.deang.geminimath

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** The tools of the Samsung Notes handwriting toolbar. [main] tools are always shown first. */
enum class NoteTool(val label: String, val main: Boolean = false) {
  KEYBOARD("Keyboard", main = true),
  PEN("Pen", main = true),
  HIGHLIGHTER("Highlighter", main = true),
  ERASER("Eraser", main = true),
  LASSO("Lasso", main = true),
  COLOURS("Pen colour"),
  THICKNESS("Pen thickness"),
  UNDO("Undo"),
  REDO("Redo"),
  CLEAN_UP("Clean up handwriting"),
  HANDWRITING_HELP("Handwriting help"),
  PEN_TO_TEXT("S Pen to text"),
  TAPE("Tape"),
  LASER("Laser pointer"),
  CHANGE_STYLE("Change style"),
  EASY_WRITING_PAD("Easy writing pad"),
  AUTO_SHAPES("Auto-fix shapes"),
  LOCK_CANVAS("Lock canvas"),
  FILL("Fill"),
  LAYERS("Layers");

  companion object {
    val mainTools = entries.filter { it.main }
    val optional = entries.filter { !it.main }
  }
}

/** Which optional tools the toolbar shows, in order, and which were dragged off it. */
data class ToolbarLayout(val shown: List<NoteTool>, val removed: List<NoteTool>) {
  fun encode(): String = shown.joinToString(",") { it.name } + "|" + removed.joinToString(",") { it.name }

  companion object {
    val DEFAULT = ToolbarLayout(NoteTool.optional, emptyList())

    fun decode(s: String?): ToolbarLayout {
      if (s.isNullOrBlank()) return DEFAULT
      fun parse(part: String) = part.split(',').mapNotNull { n -> NoteTool.entries.find { it.name == n && !it.main } }.distinct()
      val halves = s.split('|')
      val shown = parse(halves.getOrElse(0) { "" })
      val removed = parse(halves.getOrElse(1) { "" }).filter { it !in shown }
      // Tools added in a later version appear on the toolbar
      val missing = NoteTool.optional.filter { it !in shown && it !in removed }
      return ToolbarLayout(shown + missing, removed)
    }
  }
}

/** Pen colours offered on the toolbar (Samsung Notes' defaults). */
val ToolbarPenColours = listOf(
  0xFF1A1A1AL to "Black",
  0xFF80CBC4L to "Mint",
  0xFFFFEE58L to "Yellow",
  0xFF90CAF9L to "Light blue",
  0xFF9F7AEAL to "Purple"
)

/** Pen sizes offered by the thickness popup. */
val ToolbarPenSizes = listOf(3f, 5f, 8.5f, 14f, 22f)

/** What the toolbar needs to know to draw selected/disabled states. */
data class ToolbarState(
  val tool: MyScriptTool,
  val penColor: Long,
  val thickness: Float,
  val canUndo: Boolean,
  val canRedo: Boolean,
  val autoShapes: Boolean,
  val canvasLocked: Boolean,
  val penType: PenType = PenType.PEN,
  val lineStyle: LineStyle = LineStyle.SOLID,
  val quickColors: List<Long> = ToolbarPenColours.map { it.first },
  /** The canvas page is dark: toolbars (including the phone's) are dark too. */
  val dark: Boolean = false,
  /** The highlighter's own colour (shown on its icon). */
  val highlighterColor: Long = 0xFFFFE600L
)

private val IconGrey: Color get() = EditorChrome.icon

@Composable
private fun TbIcon(@DrawableRes id: Int, size: Dp, tint: Color? = IconGrey) {
  if (tint == null) Icon(painterResource(id), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(size))
  else Icon(painterResource(id), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/** A Samsung pen illustration with its tip tinted in the current colour. */
@Composable
private fun TintedPen(@DrawableRes body: Int, @DrawableRes tip: Int, tipColor: Color, size: Dp) {
  Box(Modifier.size(size)) {
    TbIcon(body, size, tint = null)
    TbIcon(tip, size, tint = tipColor)
  }
}

/** The (opaque) colour the highlighter writes with for pen colour [penColor]: yellow instead of black. */
fun highlighterInk(penColor: Long): Long =
  if ((penColor and 0xFFFFFFL) == 0x1A1A1AL) 0xFFFFE600L else (penColor and 0xFFFFFFL) or 0xFF000000L

/** The icon for [tool], as Samsung Notes draws it. */
@Composable
fun NoteToolIcon(tool: NoteTool, state: ToolbarState, size: Dp = 22.dp) {
  when (tool) {
    NoteTool.KEYBOARD -> TbIcon(R.drawable.tb_keyboard, size)
    // Samsung's illustrations have grey outlines: light versions are used on dark bars
    NoteTool.PEN -> TintedPen(if (EditorChrome.dark) R.drawable.tb_pen_dark else R.drawable.tb_pen, R.drawable.tb_pen_tip, Color(state.penColor), size)
    // The highlighter's tip shows the colour it writes in (its own, as in Samsung Notes)
    NoteTool.HIGHLIGHTER -> TintedPen(
      if (EditorChrome.dark) R.drawable.tb_highlighter_dark else R.drawable.tb_highlighter, R.drawable.tb_highlighter_tip,
      Color(state.highlighterColor or 0xFF000000L), size
    )
    NoteTool.ERASER -> TbIcon(R.drawable.tb_eraser, size)
    NoteTool.LASSO -> TbIcon(if (EditorChrome.dark) R.drawable.tb_lasso_dark else R.drawable.tb_lasso, size, tint = null)
    NoteTool.COLOURS -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
      ToolbarPenColours.forEach { (hex, _) -> Box(Modifier.size(4.dp).clip(CircleShape).background(Color(hex))) }
    }
    NoteTool.THICKNESS -> Box(Modifier.width(size).height((state.thickness / 5f).coerceIn(1.5f, 4.5f).dp).clip(RoundedCornerShape(2.dp)).background(EditorChrome.text))
    NoteTool.UNDO -> TbIcon(R.drawable.tb_undo, size, if (state.canUndo) IconGrey else EditorChrome.disabledIcon)
    NoteTool.REDO -> TbIcon(R.drawable.tb_redo, size, if (state.canRedo) IconGrey else EditorChrome.disabledIcon)
    NoteTool.CLEAN_UP -> TbIcon(R.drawable.tb_clean_up, size)
    NoteTool.HANDWRITING_HELP -> TbIcon(R.drawable.tb_handwriting_help, size)
    NoteTool.PEN_TO_TEXT -> TbIcon(if (EditorChrome.dark) R.drawable.tb_pen_to_text_dark else R.drawable.tb_pen_to_text, size, tint = null)
    NoteTool.TAPE -> TbIcon(R.drawable.tb_tape, size)
    // Samsung's coloured laser icon has a white body: draw the laser pen outline under it
    NoteTool.LASER -> Box(Modifier.size(size)) {
      TbIcon(R.drawable.snotes_laser_pen, size)
      TbIcon(R.drawable.tb_laser, size, tint = null)
    }
    NoteTool.CHANGE_STYLE -> TbIcon(R.drawable.tb_change_style, size, tint = null)
    NoteTool.EASY_WRITING_PAD -> TbIcon(R.drawable.tb_easy_writing, size)
    NoteTool.AUTO_SHAPES -> TbIcon(R.drawable.tb_shapes, size, if (state.autoShapes) OneUi9Tokens.SamsungBlue else IconGrey)
    NoteTool.LOCK_CANVAS -> TbIcon(if (state.canvasLocked) R.drawable.tb_lock else R.drawable.tb_unlock, size, if (state.canvasLocked) OneUi9Tokens.SamsungBlue else IconGrey)
    NoteTool.FILL -> TbIcon(R.drawable.tb_fill, size)
    NoteTool.LAYERS -> TbIcon(R.drawable.tb_layers, size)
  }
}

/** Whether [tool] shows as selected (a drawing tool in use, or a mode that's on). */
fun ToolbarState.isActive(tool: NoteTool): Boolean = when (tool) {
  NoteTool.PEN -> this.tool == MyScriptTool.PEN
  NoteTool.HIGHLIGHTER -> this.tool == MyScriptTool.HIGHLIGHTER
  NoteTool.ERASER -> this.tool == MyScriptTool.ERASER
  NoteTool.LASSO -> this.tool == MyScriptTool.LASSO
  NoteTool.TAPE -> this.tool == MyScriptTool.TAPE
  NoteTool.FILL -> this.tool == MyScriptTool.FILL
  NoteTool.LASER -> this.tool == MyScriptTool.LASER
  NoteTool.AUTO_SHAPES -> autoShapes
  NoteTool.LOCK_CANVAS -> canvasLocked
  else -> false
}

/**
 * The Samsung Notes handwriting toolbar: main tools, a divider, then the user's tools in their
 * order. Scrolls sideways when it doesn't fit (on phones) and is centred when it does.
 */
@Composable
fun NoteToolStrip(
  layout: ToolbarLayout,
  state: ToolbarState,
  onTool: (NoteTool) -> Unit,
  onPickColor: (Long) -> Unit,
  onPickThickness: (Float) -> Unit,
  modifier: Modifier = Modifier,
  /** Tap on the colour that's already selected: edit that toolbar slot. */
  onEditColor: (Int) -> Unit = {},
  /** A column instead of a row (toolbar docked on the left or right). */
  vertical: Boolean = false,
  /** Before the tools, e.g. the drag handle that moves the toolbar. */
  leading: @Composable () -> Unit = {},
  trailing: @Composable () -> Unit = {}
) {
  @Composable
  fun Divider() {
    if (vertical) Box(Modifier.padding(vertical = 8.dp).height(1.dp).width(20.dp).background(EditorChrome.divider))
    else Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(20.dp).background(EditorChrome.divider))
  }

  @Composable
  fun Items() {
    leading()
    NoteTool.mainTools.forEach { t -> ToolButton(t, state, onTool) }
    Divider()
    layout.shown.forEach { t ->
      when (t) {
        NoteTool.COLOURS -> state.quickColors.forEachIndexed { index, hex ->
          val name = ToolbarPenColours.find { it.first == hex }?.second ?: "#" + Srgb.hex(hex)
          // A colour only counts as "in use" while a tool that writes in it is selected. With the
          // eraser, lasso or laser, tapping any colour (even the current one) goes back to the pen.
          val writing = state.tool == MyScriptTool.PEN || state.tool == MyScriptTool.HIGHLIGHTER || state.tool == MyScriptTool.FILL
          val inUse = writing && state.penColor == hex
          Box(
            modifier = Modifier
              .padding(horizontal = if (vertical) 0.dp else 4.dp, vertical = if (vertical) 4.dp else 0.dp)
              .size(22.dp)
              .clip(CircleShape)
              .background(Color(hex))
              .border(if (inUse) 2.dp else 1.dp, if (inUse) EditorChrome.text else EditorChrome.swatchRing(hex), CircleShape)
              .clickable { if (inUse) onEditColor(index) else onPickColor(hex) }
              .semantics { contentDescription = "$name pen"; selected = inUse }
          )
        }
        NoteTool.THICKNESS -> ThicknessButton(state, onPickThickness)
        else -> ToolButton(t, state, onTool)
      }
    }
    trailing()
  }

  BoxWithConstraints(modifier) {
    if (vertical) {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .heightIn(min = maxHeight)
          .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
      ) { Items() }
    } else {
      Row(
        modifier = Modifier
          .horizontalScroll(rememberScrollState())
          .widthIn(min = maxWidth)
          .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
      ) { Items() }
    }
  }
}

/** Where the handwriting toolbar sits. Drag its handle to move it; it snaps to the nearest side. */
enum class ToolbarDock { TOP, LEFT, RIGHT, BOTTOM }

/** Six-dot drag handle for moving the toolbar. */
@Composable
fun ToolbarGrip(vertical: Boolean, modifier: Modifier = Modifier) {
  Box(modifier.size(if (vertical) 40.dp else 28.dp, if (vertical) 28.dp else 40.dp), contentAlignment = Alignment.Center) {
    androidx.compose.foundation.Canvas(Modifier.size(if (vertical) 16.dp else 10.dp, if (vertical) 10.dp else 16.dp)) {
      val cols = if (vertical) 3 else 2
      val rows = if (vertical) 2 else 3
      for (r in 0 until rows) for (c in 0 until cols) {
        drawCircle(EditorChrome.secondaryText, 1.6.dp.toPx(), androidx.compose.ui.geometry.Offset((c + 0.5f) * size.width / cols, (r + 0.5f) * size.height / rows))
      }
    }
  }
}

@Composable
private fun ToolButton(tool: NoteTool, state: ToolbarState, onTool: (NoteTool) -> Unit) {
  val active = state.isActive(tool)
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .padding(horizontal = 2.dp)
      .size(40.dp)
      .clip(CircleShape)
      .background(if (active) EditorChrome.selected else Color.Transparent)
      .clickable { onTool(tool) }
      .semantics { contentDescription = tool.label; selected = active }
  ) { NoteToolIcon(tool, state) }
}

@Composable
private fun ThicknessButton(state: ToolbarState, onPick: (Float) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .padding(horizontal = 2.dp)
        .size(40.dp)
        .clip(CircleShape)
        .clickable { open = true }
        .semantics { contentDescription = NoteTool.THICKNESS.label }
    ) { NoteToolIcon(NoteTool.THICKNESS, state) }
    if (open) {
      Popup(onDismissRequest = { open = false }, properties = PopupProperties(focusable = true), alignment = Alignment.TopCenter, offset = androidx.compose.ui.unit.IntOffset(0, 120)) {
        OneUiPopupSurface {
          Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolbarPenSizes.forEach { w ->
              val sel = kotlin.math.abs(state.thickness - w) < 0.5f
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(40.dp)
                  .clip(CircleShape)
                  .background(if (sel) Color(0xFFE4E4E9) else Color.Transparent)
                  .clickable { onPick(w); open = false }
                  .semantics { contentDescription = "Pen size ${w}"; selected = sel }
              ) {
                Box(Modifier.size((w * 0.9f).coerceIn(4f, 22f).dp).clip(CircleShape).background(Color(state.penColor)))
              }
            }
          }
        }
      }
    }
  }
}

/** The One UI popup look: white, 26 dp corners, soft shadow. */
@Composable
fun OneUiPopupSurface(content: @Composable () -> Unit) {
  Surface(
    shape = RoundedCornerShape(26.dp),
    color = EditorChrome.menu,
    shadowElevation = 12.dp,
    border = BorderStroke(0.5.dp, Color(0xFFE6E6EA))
  ) { content() }
}

// ------------------------------------------------------------------------------------------------
// Customise toolbar (Samsung Notes › handwriting toolbar › Customise)

private sealed interface ToolRow {
  data class Tool(val tool: NoteTool) : ToolRow
  data object Divider : ToolRow
}

/**
 * Reorder tools by dragging their handle; drag a tool below the line to remove it, or a removed
 * tool above it to add it back. TalkBack users get Move up / Move down / Add / Remove actions.
 */
@Composable
fun CustomiseToolbarScreen(
  layout: ToolbarLayout,
  state: ToolbarState,
  onChange: (ToolbarLayout) -> Unit,
  onBack: () -> Unit,
  dock: ToolbarDock = ToolbarDock.TOP,
  onDock: (ToolbarDock) -> Unit = {}
) {
  val rows = remember(layout) {
    mutableStateListOf<ToolRow>().apply {
      addAll(layout.shown.map { ToolRow.Tool(it) })
      add(ToolRow.Divider)
      addAll(layout.removed.map { ToolRow.Tool(it) })
    }
  }
  fun commit() {
    val d = rows.indexOf(ToolRow.Divider)
    onChange(
      ToolbarLayout(
        rows.take(d).filterIsInstance<ToolRow.Tool>().map { it.tool },
        rows.drop(d + 1).filterIsInstance<ToolRow.Tool>().map { it.tool }
      )
    )
  }
  fun move(from: Int, to: Int) {
    val t = to.coerceIn(0, rows.lastIndex)
    if (from == t) return
    rows.add(t, rows.removeAt(from))
    commit()
  }

  val rowHeight = 64.dp
  val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
  var dragIndex by remember { mutableStateOf(-1) }
  var dragDy by remember { mutableStateOf(0f) }

  // Centred at a readable width on tablets, as in Samsung Notes
  Box(Modifier.fillMaxSize().background(Color(0xFFF2F2F4)), contentAlignment = Alignment.TopCenter) {
  Column(
    Modifier
      .widthIn(max = 760.dp)
      .fillMaxSize()
      .statusBarsPadding()
      .navigationBarsPadding()
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Surface(onClick = onBack, shape = CircleShape, color = Color.White, shadowElevation = 2.dp, modifier = Modifier.size(44.dp).semantics { contentDescription = "Back" }) {
        Box(contentAlignment = Alignment.Center) { MyScriptIcons.BackArrow(OneUi9Tokens.PrimaryText, 18.dp) }
      }
      Spacer(Modifier.width(12.dp))
      Text("Customise toolbar", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OneUi9Tokens.PrimaryText, modifier = Modifier.weight(1f))
      Surface(onClick = { onChange(ToolbarLayout.DEFAULT) }, shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 2.dp) {
        Text("Reset", fontSize = 15.sp, color = OneUi9Tokens.SecondaryText, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
      }
    }

    // Live preview of the toolbar
    Surface(color = Color.White, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(14.dp)) {
      NoteToolStrip(layout, state, onTool = {}, onPickColor = {}, onPickThickness = {}, modifier = Modifier.height(52.dp))
    }

    Column(
      Modifier
        .fillMaxWidth()
        .weight(1f)
        .verticalScroll(rememberScrollState(), enabled = dragIndex < 0)
        .padding(horizontal = 16.dp)
    ) {
      Spacer(Modifier.height(18.dp))
      Text("Position", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OneUi9Tokens.SecondaryText, modifier = Modifier.padding(start = 6.dp))
      Text("Or drag the toolbar's handle to any side.", fontSize = 13.sp, color = OneUi9Tokens.CountText, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
      Spacer(Modifier.height(10.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolbarDock.entries.forEach { d ->
          val sel = d == dock
          Box(
            Modifier
              .weight(1f)
              .height(44.dp)
              .clip(RoundedCornerShape(22.dp))
              .background(if (sel) OneUi9Tokens.SamsungBlue else Color.White)
              .clickable { onDock(d) }
              .semantics { selected = sel; contentDescription = "Toolbar on the " + d.name.lowercase() },
            contentAlignment = Alignment.Center
          ) { Text(d.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 15.sp, color = if (sel) Color.White else OneUi9Tokens.PrimaryText) }
        }
      }
      Spacer(Modifier.height(18.dp))
      Text("Available tools", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OneUi9Tokens.SecondaryText, modifier = Modifier.padding(start = 6.dp))
      Text("Drag tools to remove or reorder them.", fontSize = 13.sp, color = OneUi9Tokens.CountText, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
      Spacer(Modifier.height(10.dp))
      Surface(color = Color.White, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
          rows.forEachIndexed { index, row ->
            val dragging = index == dragIndex
            val shift = when {
              dragIndex < 0 || dragging -> 0f
              else -> {
                val target = (dragIndex + (dragDy / rowPx).roundToInt()).coerceIn(0, rows.lastIndex)
                when {
                  dragIndex < index && index <= target -> -rowPx
                  target <= index && index < dragIndex -> rowPx
                  else -> 0f
                }
              }
            }
            val rowModifier = Modifier
              .fillMaxWidth()
              .height(rowHeight)
              .graphicsLayer {
                translationY = if (dragging) dragDy else shift
                shadowElevation = if (dragging) 12f else 0f
              }
              .background(if (dragging) Color(0xFFF7F7F9) else Color.Transparent)
            when (row) {
              ToolRow.Divider -> Box(rowModifier, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(Modifier.width(40.dp).height(1.dp).background(Color(0xFFCFCFD4)))
                  Text("  Drag tools below this line to remove them.  ", fontSize = 13.sp, color = OneUi9Tokens.SecondaryText, fontWeight = FontWeight.Medium)
                  Box(Modifier.width(40.dp).height(1.dp).background(Color(0xFFCFCFD4)))
                }
              }
              is ToolRow.Tool -> {
                val removed = index > rows.indexOf(ToolRow.Divider)
                Row(
                  rowModifier
                    .padding(horizontal = 18.dp)
                    .semantics {
                      contentDescription = row.tool.label + if (removed) ", removed" else ""
                      customActions = listOfNotNull(
                        CustomAccessibilityAction("Move up") { move(index, index - 1); true },
                        CustomAccessibilityAction("Move down") { move(index, index + 1); true },
                        if (removed) CustomAccessibilityAction("Add to toolbar") { move(index, rows.indexOf(ToolRow.Divider)); true }
                        else CustomAccessibilityAction("Remove from toolbar") { move(index, rows.lastIndex); true }
                      )
                    },
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) { NoteToolIcon(row.tool, state, 22.dp) }
                  Spacer(Modifier.width(12.dp))
                  Text(row.tool.label, fontSize = 17.sp, color = if (removed) OneUi9Tokens.SecondaryText else OneUi9Tokens.PrimaryText, modifier = Modifier.weight(1f))
                  // Drag handle
                  Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                      .size(48.dp)
                      .pointerInput(index, rows.size) {
                        detectDragGestures(
                          onDragStart = { dragIndex = index; dragDy = 0f },
                          onDrag = { change, amount -> change.consume(); dragDy += amount.y },
                          onDragEnd = {
                            val from = dragIndex
                            val to = from + (dragDy / rowPx).roundToInt()
                            dragIndex = -1; dragDy = 0f
                            move(from, to)
                          },
                          onDragCancel = { dragIndex = -1; dragDy = 0f }
                        )
                      }
                  ) {
                    TbIcon(R.drawable.tb_reorder_handle, 22.dp, Color(0xFF8E8E93))
                  }
                }
              }
            }
          }
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
  }
}
