package com.deang.geminimath

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val sliderColors @Composable get() = SliderDefaults.colors(
  thumbColor = OneUi9Tokens.SamsungBlue,
  activeTrackColor = OneUi9Tokens.SamsungBlue,
  inactiveTrackColor = if (EditorChrome.dark) Color(0xFF3A3A3E) else Color(0xFFDCDCE0)
)

/**
 * The note's layers, top layer first. Tap a layer to draw on it; the eye shows or hides it; the
 * slider sets its opacity; arrows move it up or down.
 */
@Composable
fun LayersPanel(
  layers: List<NoteLayer>,
  active: Int,
  onSelect: (Int) -> Unit,
  onChange: (NoteLayer) -> Unit,
  onAdd: () -> Unit,
  onDelete: (Int) -> Unit,
  onMove: (Int, Boolean) -> Unit
) {
  Column(Modifier.width(380.dp).heightIn(max = 520.dp).padding(vertical = 14.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Layers", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = EditorChrome.text, modifier = Modifier.weight(1f))
      Box(
        Modifier.clip(RoundedCornerShape(18.dp)).clickable { onAdd() }.padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "Add layer" },
        contentAlignment = Alignment.Center
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          MyScriptIcons.Plus(OneUi9Tokens.SamsungBlue, 16.dp)
          Spacer(Modifier.width(4.dp))
          Text("Add", fontSize = 15.sp, color = OneUi9Tokens.SamsungBlue, fontWeight = FontWeight.SemiBold)
        }
      }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
      layers.asReversed().forEachIndexed { revIndex, layer ->
        val index = layers.lastIndex - revIndex
        val isActive = layer.id == active
        Column(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isActive) EditorChrome.selected else Color.Transparent)
            .clickable { onSelect(layer.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = layer.name; selected = isActive }
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              Modifier.size(36.dp).clip(CircleShape).clickable { onChange(layer.copy(visible = !layer.visible)) }
                .semantics { contentDescription = if (layer.visible) "Hide ${layer.name}" else "Show ${layer.name}" },
              contentAlignment = Alignment.Center
            ) {
              Icon(
                painterResource(if (layer.visible) R.drawable.snotes_visible else R.drawable.snotes_hidden),
                null,
                tint = if (layer.visible) EditorChrome.icon else EditorChrome.disabledIcon,
                modifier = Modifier.size(20.dp)
              )
            }
            Spacer(Modifier.width(6.dp))
            Text(
              layer.name + if (isActive) "  ·  drawing here" else "",
              fontSize = 15.sp,
              color = if (layer.visible) EditorChrome.text else EditorChrome.secondaryText,
              fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
              modifier = Modifier.weight(1f)
            )
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = index < layers.lastIndex) { onMove(layer.id, true) }.semantics { contentDescription = "Move up" }, contentAlignment = Alignment.Center) {
              MyScriptIcons.ExpandChevron(expanded = true, color = if (index < layers.lastIndex) EditorChrome.icon else EditorChrome.disabledIcon, size = 14.dp)
            }
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = index > 0) { onMove(layer.id, false) }.semantics { contentDescription = "Move down" }, contentAlignment = Alignment.Center) {
              MyScriptIcons.ExpandChevron(expanded = false, color = if (index > 0) EditorChrome.icon else EditorChrome.disabledIcon, size = 14.dp)
            }
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = layers.size > 1) { onDelete(layer.id) }.semantics { contentDescription = "Delete ${layer.name}" }, contentAlignment = Alignment.Center) {
              MyScriptIcons.TrashDeleteIcon(if (layers.size > 1) Color(0xFFE5484D) else EditorChrome.disabledIcon, 15.dp)
            }
          }
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 42.dp)) {
            Slider(
              value = layer.opacity,
              onValueChange = { onChange(layer.copy(opacity = (it * 20).roundToInt() / 20f)) },
              valueRange = 0.05f..1f,
              colors = sliderColors,
              modifier = Modifier.weight(1f).height(28.dp).semantics { contentDescription = "${layer.name} opacity" }
            )
            Text("${(layer.opacity * 100).roundToInt()}%", fontSize = 13.sp, color = EditorChrome.secondaryText, modifier = Modifier.width(44.dp).padding(start = 6.dp))
          }
        }
      }
    }
  }
}

/** Bucket fill options: how see-through fills are, with a preview in the current colour. */
@Composable
fun FillSettingsPanel(color: Long, opacity: Float, onOpacity: (Float) -> Unit) {
  Column(Modifier.width(340.dp).padding(horizontal = 20.dp, vertical = 16.dp)) {
    Text("Fill", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = EditorChrome.text)
    Text("Tap inside a closed shape to fill it with the pen colour.", fontSize = 13.sp, color = EditorChrome.secondaryText)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
          .background(Color(0xFFFFFFFF)).border(1.dp, EditorChrome.divider, RoundedCornerShape(10.dp))
      ) { Box(Modifier.matchParentSize().background(Color(color).copy(alpha = opacity))) }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text("Opacity  ${(opacity * 100).roundToInt()}%", fontSize = 14.sp, color = EditorChrome.text)
        Slider(
          value = opacity,
          onValueChange = { onOpacity((it * 20).roundToInt() / 20f) },
          valueRange = 0.1f..1f,
          colors = sliderColors,
          modifier = Modifier.semantics { contentDescription = "Fill opacity" }
        )
      }
    }
  }
}
