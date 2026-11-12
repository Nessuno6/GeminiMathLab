package com.deang.geminimath

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The phone side of "Use phone as toolbar", laid out like Samsung Notes': the main tools on a
 * raised bar, the pen settings (pen types, size, line style, Straighten lines, colours), quick
 * colours and size, then round buttons for the other tools. Every tap acts on the tablet or
 * Googlebook; what's selected mirrors it, and it's dark when the page there is dark.
 */
@Composable
fun PhoneToolbarScreen(
  onClose: () -> Unit,
  customColors: List<Long>,
  onAddCustomColor: (Long) -> Unit
) {
  val link = RemoteToolbarLink
  val state = link.remoteState ?: ToolbarState(MyScriptTool.PEN, 0xFF1A1A1AL, 8.5f, false, false, false, false)
  val layout = link.remoteLayout ?: ToolbarLayout.DEFAULT
  val on = link.connected
  fun send(cmd: RemoteCommand) { if (on) link.send(cmd) }

  // Follow the tablet: dark page there, dark toolbar here
  SideEffect { EditorChrome.dark = state.dark }
  DisposableEffect(Unit) { onDispose { EditorChrome.dark = false } }
  val bg = if (state.dark) Color(0xFF000000) else Color(0xFFF4F4F6)
  val card = EditorChrome.menu

  Column(
    Modifier
      .fillMaxSize()
      .background(bg)
      .statusBarsPadding()
      .navigationBarsPadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp)
  ) {
    // Stop (top right, like Samsung's)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
      Surface(
        onClick = { link.stop(); onClose() },
        shape = CircleShape,
        color = card,
        shadowElevation = 2.dp,
        modifier = Modifier.size(44.dp).semantics { contentDescription = "Stop using phone as toolbar" }
      ) { Box(contentAlignment = Alignment.Center) { MyScriptIcons.CloseX(EditorChrome.text, 18.dp) } }
    }

    // Main tools on a raised bar, on top of the pen settings card
    Surface(color = card, shape = RoundedCornerShape(30.dp), modifier = Modifier.fillMaxWidth()) {
      Column {
        Row(
          Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(30.dp))
            .background(card, RoundedCornerShape(30.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically
        ) {
          NoteTool.mainTools.forEach { t -> RoundTool(t, state, 48.dp, filled = false) { send(RemoteCommand.Tool(t)) } }
        }
        PenSettingsPanel(
          penType = state.penType,
          lineStyle = state.lineStyle,
          width = state.thickness,
          color = state.penColor,
          straighten = state.autoShapes,
          customColors = customColors,
          onPenType = { send(RemoteCommand.Pen(it)) },
          onLineStyle = { send(RemoteCommand.Line(it)) },
          onWidth = { send(RemoteCommand.PenSize(it)) },
          onColor = { send(RemoteCommand.PenColor(it)) },
          onStraighten = { send(RemoteCommand.Straighten(it)) },
          onAddCustomColor = onAddCustomColor,
          compact = true
        )
      }
    }

    Spacer(Modifier.height(14.dp))

    // Quick colours | size
    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Surface(color = card, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1.35f).fillMaxHeight()) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
          state.quickColors.forEach { hex ->
            val sel = state.penColor == hex
            Box(
              Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(hex))
                .border(if (sel) 2.dp else 1.dp, if (sel) EditorChrome.text else EditorChrome.swatchRing(hex), CircleShape)
                .clickable(enabled = on) { send(RemoteCommand.PenColor(hex)) }
                .semantics { contentDescription = "Colour #${Srgb.hex(hex)}"; selected = sel }
            )
          }
        }
      }
      Surface(
        onClick = {
          val next = ToolbarPenSizes.firstOrNull { it > state.thickness + 0.5f } ?: ToolbarPenSizes.first()
          send(RemoteCommand.PenSize(next))
        },
        enabled = on,
        color = card,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "Pen size" }
      ) {
        Box(contentAlignment = Alignment.Center) {
          Box(Modifier.width(26.dp).height((state.thickness / 3.5f).coerceIn(2.5f, 6f).dp).clip(RoundedCornerShape(3.dp)).background(EditorChrome.icon))
        }
      }
    }

    Spacer(Modifier.height(14.dp))

    // Undo/redo, Solve, then the rest of your toolbar as round buttons
    val rest = layout.shown.filter { it !in setOf(NoteTool.COLOURS, NoteTool.THICKNESS, NoteTool.UNDO, NoteTool.REDO) }
    val round = 54.dp
    Row(Modifier.fillMaxWidth().height(round), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(color = card, shape = RoundedCornerShape(27.dp), modifier = Modifier.weight(2f).fillMaxHeight()) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
          RoundTool(NoteTool.UNDO, state, 44.dp, filled = false) { send(RemoteCommand.Tool(NoteTool.UNDO)) }
          RoundTool(NoteTool.REDO, state, 44.dp, filled = false) { send(RemoteCommand.Tool(NoteTool.REDO)) }
        }
      }
      Surface(
        onClick = { send(RemoteCommand.Solve) },
        enabled = on,
        shape = CircleShape,
        color = card,
        modifier = Modifier.size(round).semantics { contentDescription = "Solve on tablet" }
      ) { Box(contentAlignment = Alignment.Center) { MyScriptIcons.SparkleAi(EditorChrome.text, 22.dp) } }
      rest.take(2).forEach { t -> RoundTool(t, state, round) { send(RemoteCommand.Tool(t)) } }
    }
    rest.drop(2).chunked(5).forEach { row ->
      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        row.forEach { t -> RoundTool(t, state, round) { send(RemoteCommand.Tool(t)) } }
        repeat(5 - row.size) { Spacer(Modifier.size(round)) }
      }
    }
    Spacer(Modifier.height(20.dp))
  }
}

/** A round Samsung-style tool button; grey disc when that tool is in use on the tablet. */
@Composable
private fun RoundTool(tool: NoteTool, state: ToolbarState, size: Dp, filled: Boolean = true, onClick: () -> Unit) {
  val active = state.isActive(tool)
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(size)
      .clip(CircleShape)
      .background(if (active) EditorChrome.selected else if (filled) EditorChrome.menu else Color.Transparent)
      .clickable(onClick = onClick)
      .semantics { contentDescription = tool.label; selected = active }
  ) { NoteToolIcon(tool, state, (size.value * 0.44f).dp) }
}
