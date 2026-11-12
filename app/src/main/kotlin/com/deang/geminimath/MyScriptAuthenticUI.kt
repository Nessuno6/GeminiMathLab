package com.deang.geminimath

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vector icons replicating the exact iconography of com.myscript.math.
 */
object MyScriptIcons {

  /** Draws a One UI kit icon (res/drawable/oneui_*.xml) tinted with [color]. */
  @Composable
  private fun KitIcon(@DrawableRes id: Int, color: Color, size: Dp) {
    Icon(painter = painterResource(id), contentDescription = null, tint = color, modifier = Modifier.size(size))
  }

  // One UI kit icons are drawn on a 24-unit grid with ~3 units of padding, so they're shown a
  // little larger than the old hand-drawn ones to keep the same visual weight.
  private fun kit(size: Dp): Dp = size * 1.15f

  @Composable
  fun BackArrow(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_back, color, kit(size))

  @Composable
  fun EditPencil(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) =
    KitIcon(R.drawable.oneui_pen, color, kit(size))

  @Composable
  fun UndoArrow(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_undo, color, kit(size))

  @Composable
  fun RedoArrow(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_redo, color, kit(size))

  /** Page style / page settings. */
  @Composable
  fun PreferencesSliders(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_page_settings, color, kit(size))

  @Composable
  fun MoreVerticalDots(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_more, color, kit(size))

  @Composable
  fun LassoLoop(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_selection_lasso, color, kit(size))

  @Composable
  fun EraserBlock(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_pen_eraser, color, kit(size))

  /** Galaxy AI sparkle, used for Solve. */
  @Composable
  fun SparkleAi(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_ai, color, kit(size))

  /** Samsung Notes laser pen toolbar icon. */
  @Composable
  fun LaserWand(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.snotes_laser_pen, color, kit(size))


  @Composable
  fun ImageFrame(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.oneui_image_outline, color, kit(size))

  @Composable
  fun PadlockIcon(color: Color = Color(0xFF1F1F1F), size: Dp = 17.dp) =
    KitIcon(R.drawable.oneui_lock_outline, color, kit(size))

  @Composable
  fun CopyIcon(color: Color = Color.White, size: Dp = 18.dp) =
    KitIcon(R.drawable.snotes_copy, color, kit(size))

  @Composable
  fun CutScissorsIcon(color: Color = Color.White, size: Dp = 18.dp) =
    KitIcon(R.drawable.snotes_cut, color, kit(size))

  @Composable
  fun DashedBoxIcon(color: Color = Color.White, size: Dp = 18.dp) =
    KitIcon(R.drawable.oneui_selection_rectangle, color, kit(size))

  @Composable
  fun TrashDeleteIcon(color: Color = Color(0xFFFF6E6E), size: Dp = 18.dp) =
    KitIcon(R.drawable.snotes_delete, color, kit(size))

  @Composable
  fun HamburgerMenu(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    KitIcon(R.drawable.snotes_drawer, color, kit(size))

  @Composable
  fun NoteDocIcon(color: Color = Color(0xFFD93025), size: Dp = 20.dp) =
    KitIcon(R.drawable.snotes_notes, color, kit(size))

  @Composable
  fun CloudSyncIcon(color: Color = Color(0xFF4285F4), size: Dp = 22.dp) =
    KitIcon(R.drawable.snotes_sync, color, size)

  // ---- Icons that used to be text symbols (✕ ⚙ + − ⌄ ⌃ ✓ ☆ ★ 📁 📓 ☁ 🎨 👍 👎 ⇪ ⇩ ∑ i) ----
  // From Samsung Notes (snotes_*) and Samsung Settings (ssettings_*), extracted on the user's device.

  @Composable fun CloseX(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) = KitIcon(R.drawable.snotes_close, color, kit(size))
  @Composable fun SettingsGear(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) = KitIcon(R.drawable.snotes_settings, color, kit(size))
  @Composable fun Plus(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) = KitIcon(R.drawable.snotes_add, color, kit(size))
  /** A minus sign drawn to match Samsung's plus icon (Samsung's "remove" icon is a bin). */
  @Composable fun Minus(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) =
    Canvas(Modifier.size(kit(size))) {
      val y = this.size.height / 2f
      drawLine(color, Offset(this.size.width * 0.22f, y), Offset(this.size.width * 0.78f, y), this.size.width * 0.075f, StrokeCap.Round)
    }
  /** ⌄ when [expanded] is false (tap to expand), ⌃ when true (tap to collapse). */
  @Composable fun ExpandChevron(expanded: Boolean, color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) =
    KitIcon(if (expanded) R.drawable.snotes_collapse else R.drawable.snotes_expand, color, kit(size))
  @Composable fun Check(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.snotes_check, color, kit(size))
  @Composable fun Star(filled: Boolean, color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) =
    KitIcon(if (filled) R.drawable.snotes_favorite_solid else R.drawable.snotes_favorite_line, color, kit(size))
  @Composable fun Folder(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.snotes_folder, color, kit(size))
  @Composable fun AllNotes(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.snotes_notes, color, kit(size))
  @Composable fun Cloud(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.ssettings_cloud, color, kit(size))
  @Composable fun Palette(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.snotes_palette, color, kit(size))
  @Composable fun ThumbUp(color: Color = Color(0xFF1F1F1F), size: Dp = 16.dp) = KitIcon(R.drawable.ssettings_thumb_up, color, kit(size))
  @Composable fun ThumbDown(color: Color = Color(0xFF1F1F1F), size: Dp = 16.dp) = KitIcon(R.drawable.ssettings_thumb_down, color, kit(size))
  @Composable fun Share(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) = KitIcon(R.drawable.snotes_share, color, kit(size))
  @Composable fun Import(color: Color = Color(0xFF1F1F1F), size: Dp = 20.dp) = KitIcon(R.drawable.snotes_import, color, kit(size))
  @Composable fun MathFormula(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.snotes_math, color, kit(size))
  @Composable fun Info(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.ssettings_info, color, kit(size))
  @Composable fun AccountsBackup(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.ssettings_accounts_backup, color, kit(size))
  @Composable fun Theme(color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) = KitIcon(R.drawable.ssettings_theme, color, kit(size))

  /** Draws the icon for a key; used by places that store an icon id instead of a composable. */
  @Composable
  fun ByName(name: String, color: Color = Color(0xFF1F1F1F), size: Dp = 18.dp) {
    when (name) {
      "all_notes" -> AllNotes(color, size)
      "favourites" -> Star(false, color, size)
      "latex" -> MathFormula(color, size)
      "cloud" -> Cloud(color, size)
      "account" -> Cloud(color, size)
      "pen" -> EditPencil(color, size)
      "math" -> MathFormula(color, size)
      "assistant" -> RobotIcon(color, size)
      "appearance" -> Theme(color, size)
      "about" -> Info(color, size)
      "folder" -> Folder(color, size)
      "export" -> Share(color, size)
      "move" -> Folder(color, size)
      "duplicate" -> CopyIcon(color, size)
      "delete" -> TrashDeleteIcon(color, size)
      "more" -> MoreVerticalDots(color, size)
      "settings" -> SettingsGear(color, size)
      else -> Text(name, color = color)
    }
  }
}
