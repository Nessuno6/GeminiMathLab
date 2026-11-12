package com.deang.geminimath

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * UI Design System mode selectable in Settings:
 * - [M3E]: Material 3 Expressive / MyScript Math 1:1 UI
 * - [ONEUI_9]: Samsung One UI 9.0 (SESL 9 / GitHub `tribalfs/oneui-design` + Galaxy Tab S12 Ultra)
 */
enum class UiStyleMode(val id: String, val label: String, val subtitle: String) {
  M3E(
    id = "M3E",
    label = "M3E (MyScript Classic)",
    subtitle = "Material 3 Expressive / MyScript Math 1:1 Layout"
  ),
  ONEUI_9(
    id = "ONEUI_9",
    label = "One UI 9.0 (Galaxy Tab S12)",
    subtitle = "Samsung One UI 9.0 Design Kit (SESL 9 / tribalfs/oneui-design)"
  );

  companion object {
    fun fromId(id: String?): UiStyleMode =
      entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: M3E
  }
}

/**
 * Official Samsung One UI 9.0 (ro.build.version.oneui = 90000) & GitHub `tribalfs/oneui-design`
 * color tokens and dimensions.
 */
object OneUi9Tokens {
  val RoundAndBgColor = Color(0xFFF4F4F6)         // @color/oui_round_and_bgcolor
  val CardBackground = Color(0xFFFFFFFF)          // @color/oui_background_color
  val DrawerBackground = Color(0xFFF9F9FB)        // @color/drawer_background_color
  val DrawerSelectedBg = Color(0x0D010102)        // @color/oui_drawer_item_selected_background_color
  val DottedLineColor = Color(0xFFC6C6C6)         // @color/oui_drawer_category_dotted_line_color
  val ListDividerColor = Color(0xFFEFEFF2)        // @color/oui_list_divider_color
  val SwitchVerticalBar = Color(0xFFD8D8DC)

  val PrimaryText = Color(0xFF010102)
  val SecondaryText = Color(0xFF67676C)
  val CountText = Color(0xFF8C8C8C)

  val SamsungBlue = Color(0xFF387AFF)
  val SamsungDeepBlue = Color(0xFF1F57C3)
  val RelatedCardBg = Color(0xFFE2E7F4)
  val FunctionalGreen = Color(0xFF72C147)
  val FunctionalOrange = Color(0xFFDF622C)
  val FunctionalRed = Color(0xFFDB333C)
  val WallpaperPink = Color(0xFFDB4777)
  val GalaxyAiBlue = Color(0xFF4280FF)
  val GalaxyAiPurple = Color(0xFF635EE2)
  val SNotesFabCoral = Color(0xFFE65142)

  val CardRadius = 26.dp
  val InnerItemRadius = 18.dp
  val DrawerItemRadius = 14.dp
}

/** Readable math for a note card ("∫ x·eˣ dx"), or null when there is nothing readable to show. */
fun noteMathLabel(note: MathNote): String? {
  if (note.strokes.isEmpty() || note.recognizedLatex.isBlank()) return null
  return NoteLabelCache.get(note.recognizedLatex)
}

/**
 * Card labels need the solver's normalised form, which can take tens of milliseconds per note.
 * Worked out once per LaTeX string, off the main thread (see [rememberNoteMathLabel]).
 */
object NoteLabelCache {
  private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>()
  private object NONE

  fun peek(latex: String): String? = cache[latex] as? String
  fun known(latex: String): Boolean = cache.containsKey(latex)

  fun get(latex: String): String? {
    cache[latex]?.let { return it as? String }
    val sol = SymbolicStepEngine.solveFromLatex(latex)
    val label = sol.normalizedMath.takeIf { !sol.isUnreadable && it.isNotBlank() }
    cache[latex] = label ?: NONE
    return label
  }
}

/** The card label, computed in the background so opening the notes list never stutters. */
@Composable
fun rememberNoteMathLabel(note: MathNote): String? {
  val latex = note.recognizedLatex
  val hasInk = note.strokes.isNotEmpty()
  return produceState(initialValue = if (hasInk) NoteLabelCache.peek(latex) else null, latex, hasInk) {
    value = if (!hasInk || latex.isBlank()) null
    else if (NoteLabelCache.known(latex)) NoteLabelCache.peek(latex)
    else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { NoteLabelCache.get(latex) }
  }.value
}

@Composable
fun OneUi9DottedDivider(
  modifier: Modifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp, vertical = 10.dp)
) {
  Canvas(modifier = modifier.height(2.dp)) {
    val dotSpacing = 6.dp.toPx()
    val dotRadius = 1.dp.toPx()
    var x = 0f
    while (x < size.width) {
      drawCircle(
        color = OneUi9Tokens.DottedLineColor,
        radius = dotRadius,
        center = Offset(x, size.height / 2f)
      )
      x += dotSpacing
    }
  }
}

@Composable
fun OneUi9Switch(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  showLeftDivider: Boolean = true
) {
  val trackColor by animateColorAsState(
    targetValue = if (checked) OneUi9Tokens.SamsungBlue else Color(0xFF99999E),
    animationSpec = tween(180),
    label = "oneui_switch_track"
  )
  val thumbOffset by animateDpAsState(
    targetValue = if (checked) 22.dp else 3.dp,
    animationSpec = tween(180),
    label = "oneui_switch_thumb"
  )

  Row(verticalAlignment = Alignment.CenterVertically) {
    if (showLeftDivider) {
      Box(
        modifier = Modifier
          .padding(end = 16.dp)
          .width(1.dp)
          .height(22.dp)
          .background(OneUi9Tokens.SwitchVerticalBar)
      )
    }
    Box(
      modifier = Modifier
        .width(44.dp)
        .height(24.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(trackColor)
        .clickable { onCheckedChange(!checked) },
      contentAlignment = Alignment.CenterStart
    ) {
      Box(
        modifier = Modifier
          .offset(x = thumbOffset)
          .size(19.dp)
          .shadow(2.dp, CircleShape)
          .clip(CircleShape)
          .background(Color.White)
      )
    }
  }
}

@Composable
private fun OneUiRadioPreviewItem(
  title: String,
  subtitle: String,
  selected: Boolean,
  isOneUiPreview: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val borderColor = if (selected) OneUi9Tokens.SamsungBlue else Color(0xFFE0E0E5)
  val borderWidth = if (selected) 2.dp else 1.dp

  Column(
    modifier = modifier
      .clip(RoundedCornerShape(20.dp))
      .background(if (selected) Color(0xFFF5F8FF) else Color(0xFFFAFAFC))
      .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
      .clickable(onClick = onClick)
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(92.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(if (isOneUiPreview) OneUi9Tokens.RoundAndBgColor else Color(0xFFEDEDED))
    ) {
      if (!isOneUiPreview) {
        val step = 12.dp.toPx()
        var x = 0f
        while (x < size.width) {
          drawLine(Color(0xFFE0E0E0), Offset(x, 0f), Offset(x, size.height), 1f)
          x += step
        }
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(10.dp.toPx(), 16.dp.toPx()),
          size = Size(14.dp.toPx(), 58.dp.toPx()),
          cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
        )
        drawRoundRect(
          color = Color(0xFF3D3D3D),
          topLeft = Offset(size.width - 78.dp.toPx(), size.height - 46.dp.toPx()),
          size = Size(68.dp.toPx(), 36.dp.toPx()),
          cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        drawRoundRect(
          color = Color(0xFF3993A0),
          topLeft = Offset(size.width - 78.dp.toPx(), size.height - 22.dp.toPx()),
          size = Size(68.dp.toPx(), 12.dp.toPx()),
          cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
      } else {
        val rightX = size.width * 0.42f
        val rightW = size.width * 0.52f
        drawRoundRect(
          color = Color(0xFFE8E8EE),
          topLeft = Offset(10.dp.toPx(), 14.dp.toPx()),
          size = Size(size.width * 0.32f, 16.dp.toPx()),
          cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        drawCircle(
          color = OneUi9Tokens.SamsungBlue,
          radius = 4.dp.toPx(),
          center = Offset(18.dp.toPx(), 22.dp.toPx())
        )
        drawCircle(
          color = OneUi9Tokens.WallpaperPink,
          radius = 4.dp.toPx(),
          center = Offset(18.dp.toPx(), 42.dp.toPx())
        )
        drawCircle(
          color = OneUi9Tokens.FunctionalGreen,
          radius = 4.dp.toPx(),
          center = Offset(18.dp.toPx(), 60.dp.toPx())
        )
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(rightX, 14.dp.toPx()),
          size = Size(rightW, 30.dp.toPx()),
          cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
        )
        drawRoundRect(
          color = OneUi9Tokens.SamsungBlue,
          topLeft = Offset(rightX + rightW - 22.dp.toPx(), 24.dp.toPx()),
          size = Size(16.dp.toPx(), 9.dp.toPx()),
          cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        )
        drawRoundRect(
          color = OneUi9Tokens.RelatedCardBg,
          topLeft = Offset(rightX, 50.dp.toPx()),
          size = Size(rightW, 28.dp.toPx()),
          cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
        )
      }
    }

    Spacer(Modifier.height(12.dp))
    Text(
      text = title,
      fontSize = 15.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      color = if (selected) OneUi9Tokens.SamsungBlue else OneUi9Tokens.PrimaryText
    )
    Text(
      text = subtitle,
      fontSize = 12.sp,
      color = OneUi9Tokens.SecondaryText,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Box(
      modifier = Modifier
        .size(20.dp)
        .clip(CircleShape)
        .border(
          width = if (selected) 6.dp else 1.8.dp,
          color = if (selected) OneUi9Tokens.SamsungBlue else Color(0xFF9E9EA4),
          shape = CircleShape
        )
    )
  }
}

/**
 * Full-Screen **One UI 9.0 Home Screen (`All notes` & `Quick LaTeX`)** with
 * **Editable & Deletable Folders** and zero sample notes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OneUi9NotesHomeScreen(
  notes: List<MathNote>,
  folders: List<NoteFolder>,
  activeNavSection: String,
  onSelectNavSection: (String) -> Unit,
  onOpenNote: (MathNote) -> Unit,
  onCreateNewNote: (String?) -> Unit,
  selectedNoteIds: Set<String>,
  selectionMode: Boolean,
  onToggleSelectNote: (MathNote) -> Unit,
  onEnterSelectionMode: () -> Unit,
  onVisibleNotesChanged: (List<String>) -> Unit,
  selectionActions: NoteSelectionActions,
  onImport: () -> Unit,
  onCreateFolder: (String, Long) -> Unit,
  onEditFolder: (NoteFolder, String, Long) -> Unit,
  onDeleteFolder: (NoteFolder) -> Unit,
  onOpenSettings: (SettingsSection?) -> Unit,
  onSwitchToM3E: () -> Unit,
  onUseAsToolbar: () -> Unit = {},
  /** Notes in the bin, and the bin's own screen (shown when "Bin" is picked in the drawer). */
  binCount: Int = 0,
  binContent: @Composable (Modifier) -> Unit = {},
  quickLatexContent: @Composable () -> Unit
) {
  var selectedFilter by remember { mutableStateOf("all") } // "all", "fav", "bin", or folder.id
  val df = remember { SimpleDateFormat("dd MMM", Locale.ENGLISH) }

  // Folder Create / Edit Dialog state
  var showFolderDialog by remember { mutableStateOf(false) }
  var editingFolder by remember { mutableStateOf<NoteFolder?>(null) }
  var folderNameInput by remember { mutableStateOf("") }
  var folderColorInput by remember { mutableStateOf(0xFFF5A623L) }

  // Wide screens (tablet, Googlebook): drawer and notes side by side.
  // Narrow screens (phone, small windows): notes full width, drawer slides in from the menu button.
  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
      .statusBarsPadding()
      .navigationBarsPadding()
  ) {
  val compact = maxWidth < 600.dp
  val screenWidth = maxWidth
  var drawerOpen by remember { mutableStateOf(false) }
  // Picking a section or folder in the drawer closes it
  LaunchedEffect(activeNavSection, selectedFilter) { drawerOpen = false }

  val drawerPane: @Composable (Modifier) -> Unit = { paneModifier ->
    // ==================== LEFT DRAWER (Samsung Notes One UI 9.0 Style) ====================
    Column(
      modifier = paneModifier
        .fillMaxHeight()
        .background(OneUi9Tokens.DrawerBackground)
        .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Notes", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OneUi9Tokens.PrimaryText)
        IconButton(
          onClick = { onOpenSettings(null) },
          modifier = Modifier.size(36.dp).semantics { contentDescription = "Settings" }
        ) {
          MyScriptIcons.SettingsGear(color = Color(0xFF45454A), size = 20.dp)
        }
      }

      Spacer(Modifier.height(8.dp))

      OneUi9NotesDrawerRow(
        icon = "all_notes",
        title = "All notes",
        count = notes.size.toString(),
        selected = activeNavSection == "notes" && selectedFilter == "all",
        onClick = {
          selectedFilter = "all"
          onSelectNavSection("notes")
        }
      )
      OneUi9NotesDrawerRow(
        icon = "favourites",
        title = "Favourites",
        count = notes.count { it.isFavourite }.toString(),
        selected = activeNavSection == "notes" && selectedFilter == "fav",
        onClick = {
          selectedFilter = "fav"
          onSelectNavSection("notes")
        }
      )
      OneUi9NotesDrawerRow(
        icon = "latex",
        title = "Quick LaTeX Pad",
        count = "LIVE",
        isBetaBadge = true,
        selected = activeNavSection == "quick_latex",
        onClick = { onSelectNavSection("quick_latex") }
      )
      OneUi9NotesDrawerRow(
        icon = "delete",
        title = "Bin",
        count = binCount.toString(),
        selected = activeNavSection == "notes" && selectedFilter == "bin",
        onClick = {
          selectedFilter = "bin"
          onSelectNavSection("notes")
        }
      )
      OneUi9NotesDrawerRow(
        icon = "cloud",
        title = "Google Drive backup",
        count = "",
        selected = false,
        onClick = { onOpenSettings(SettingsSection.ACCOUNT) }
      )

      OneUi9DottedDivider()

      // Folders section header with "+" button to add a new folder
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          MyScriptIcons.Folder(color = OneUi9Tokens.PrimaryText, size = 17.dp)
          Spacer(Modifier.width(12.dp))
          Text(
            text = "Folders",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = OneUi9Tokens.PrimaryText
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(folders.size.toString(), fontSize = 13.sp, color = OneUi9Tokens.CountText)
          Surface(
            onClick = {
              editingFolder = null
              folderNameInput = ""
              folderColorInput = 0xFF387AFFL
              showFolderDialog = true
            },
            shape = CircleShape,
            color = Color(0xFFE6E6EC),
            modifier = Modifier.size(26.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              MyScriptIcons.Plus(color = OneUi9Tokens.PrimaryText, size = 15.dp)
            }
          }
        }
      }

      // Dynamic Editable & Deletable Folders list
      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState())
      ) {
        folders.forEach { folder ->
          val countInFolder = notes.count { it.folderId == folder.id }
          OneUi9EditableFolderRow(
            folder = folder,
            count = countInFolder,
            selected = activeNavSection == "notes" && selectedFilter == folder.id,
            onSelect = {
              selectedFilter = folder.id
              onSelectNavSection("notes")
            },
            onEdit = {
              editingFolder = folder
              folderNameInput = folder.name
              folderColorInput = folder.colorHex
              showFolderDialog = true
            },
            onDelete = {
              if (selectedFilter == folder.id) selectedFilter = "all"
              onDeleteFolder(folder)
            }
          )
        }
      }

      // Bottom UI Style Switcher Chip
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 6.dp)
          .clickable { onOpenSettings(SettingsSection.APPEARANCE) },
        shape = RoundedCornerShape(16.dp),
        color = OneUi9Tokens.RelatedCardBg
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column {
            Text(
              text = "Design: One UI 9",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = OneUi9Tokens.SamsungDeepBlue
            )
            Text(
              text = "Change in Appearance settings",
              fontSize = 11.sp,
              color = OneUi9Tokens.SecondaryText
            )
          }
          MyScriptIcons.Palette(color = OneUi9Tokens.SamsungDeepBlue, size = 18.dp)
        }
      }
    }
  }

  val mainPane: @Composable (Modifier) -> Unit = { paneModifier ->
    // ==================== RIGHT MAIN AREA ====================
    if (activeNavSection == "quick_latex") {
      Box(modifier = paneModifier.fillMaxHeight()) {
        quickLatexContent()
      }
    } else if (selectedFilter == "bin") {
      Box(
        modifier = paneModifier.fillMaxHeight().background(Color(0xFFFDFDFE))
          .padding(horizontal = if (compact) 16.dp else 28.dp, vertical = if (compact) 12.dp else 20.dp)
      ) {
        Column {
          // On a phone the drawer is behind the menu button: keep a way back to it
          if (compact) IconButton(onClick = { drawerOpen = true }, modifier = Modifier.semantics { contentDescription = "Open folders" }) {
            MyScriptIcons.HamburgerMenu(color = OneUi9Tokens.PrimaryText)
          }
          binContent(Modifier)
        }
      }
    } else {
      val currentFolderObj = folders.find { it.id == selectedFilter }
      val headerTitle = when {
        selectedFilter == "all" -> "All notes"
        selectedFilter == "fav" -> "Favourites"
        currentFolderObj != null -> currentFolderObj.name
        else -> "All notes"
      }
      val displayedNotes = when {
        selectedFilter == "all" -> notes
        selectedFilter == "fav" -> notes.filter { it.isFavourite }
        currentFolderObj != null -> notes.filter { it.folderId == currentFolderObj.id }
        else -> notes
      }

      val displayedIds = displayedNotes.map { it.id }
      LaunchedEffect(displayedIds) { onVisibleNotesChanged(displayedIds) }

      Box(
        modifier = paneModifier
          .fillMaxHeight()
          .background(Color(0xFFFDFDFE))
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 16.dp else 28.dp, vertical = if (compact) 12.dp else 20.dp)
        ) {
          if (selectionMode) {
            OneUi9SelectionHeader(
              count = selectedNoteIds.size,
              allSelected = displayedIds.isNotEmpty() && selectedNoteIds.containsAll(displayedIds),
              actions = selectionActions
            )
          } else Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              if (compact) {
                IconButton(
                  onClick = { drawerOpen = true },
                  modifier = Modifier.semantics { contentDescription = "Open folders" }
                ) {
                  MyScriptIcons.HamburgerMenu(color = OneUi9Tokens.PrimaryText)
                }
                Spacer(Modifier.width(4.dp))
              }
              Text(
                text = headerTitle,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = OneUi9Tokens.PrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)
            ) {
              // One-tap design switch (the full option lives in Settings › Appearance);
              // on a phone it moves into the ⋮ menu to leave room for the title
              if (!compact) Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFF0F2F8),
                modifier = Modifier.clickable(onClickLabel = "Switch to M3E design") { onSwitchToM3E() }
              ) {
                Text(
                  text = "UI: One UI 9 ⇄ M3E",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = OneUi9Tokens.SamsungDeepBlue,
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
              }
              IconButton(onClick = onImport, modifier = Modifier.semantics { contentDescription = "Import notes" }) {
                MyScriptIcons.Import(color = OneUi9Tokens.PrimaryText)
              }
              var homeMenuOpen by remember { mutableStateOf(false) }
              Box {
                IconButton(onClick = { homeMenuOpen = true }, modifier = Modifier.semantics { contentDescription = "More options" }) {
                  MyScriptIcons.MoreVerticalDots(color = OneUi9Tokens.PrimaryText)
                }
                UiMenu(expanded = homeMenuOpen, onDismissRequest = { homeMenuOpen = false }) {
                  UiMenuItem(
                    text = { Text("Edit (select notes)") },
                    onClick = { homeMenuOpen = false; onEnterSelectionMode() }
                  )
                  UiMenuItem(
                    text = { Text("Import (Samsung Notes, .iink, PDF, PNG)") },
                    onClick = { homeMenuOpen = false; onImport() }
                  )
                  UiMenuItem(
                    text = { Text("Use as toolbar for another device") },
                    onClick = { homeMenuOpen = false; onUseAsToolbar() }
                  )
                  if (compact) {
                    UiMenuItem(
                      text = { Text("Switch to M3E design") },
                      onClick = { homeMenuOpen = false; onSwitchToM3E() }
                    )
                  }
                }
              }
            }
          }

          Spacer(Modifier.height(20.dp))

          if (displayedNotes.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                  text = "No notes",
                  fontSize = 18.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = OneUi9Tokens.SecondaryText
                )
                Spacer(Modifier.height(6.dp))
                Text(
                  text = "Tap the compose button at the bottom right to create a new math note.",
                  fontSize = 13.sp,
                  color = OneUi9Tokens.CountText
                )
              }
            }
          } else {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = if (compact) 150.dp else 185.dp),
              horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
              verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 24.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              items(displayedNotes, key = { it.id }) { note ->
                val isSelected = note.id in selectedNoteIds
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  modifier = Modifier
                    .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(190.dp))
                    .combinedClickable(
                      onClick = { if (selectionMode) onToggleSelectNote(note) else onOpenNote(note) },
                      onLongClick = { onToggleSelectNote(note) }
                    )
                ) {
                  Surface(
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(235.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) Color(0xFFE8F0FF) else Color.White,
                    shadowElevation = 3.dp,
                    border = BorderStroke(
                      if (isSelected) 2.dp else 1.dp,
                      if (isSelected) OneUi9Tokens.SamsungDeepBlue else Color(0xFFE6E6EC)
                    )
                  ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                      note.backgroundImagePath?.let { bgPath ->
                        NoteBackgroundThumb(bgPath, Modifier.fillMaxSize().padding(10.dp))
                      }
                      Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        val step = 12.dp.toPx()
                        var gx = 0f
                        while (gx <= size.width) {
                          drawLine(Color(0xFFF0F0F3), Offset(gx, 0f), Offset(gx, size.height), 1f)
                          gx += step
                        }
                        var gy = 0f
                        while (gy <= size.height) {
                          drawLine(Color(0xFFF0F0F3), Offset(0f, gy), Offset(size.width, gy), 1f)
                          gy += step
                        }

                      }
                      // The ink preview: rendered once in the background, cached until the note changes
                      NoteInkThumb(note, Modifier.fillMaxSize().padding(12.dp))

                      val label = rememberNoteMathLabel(note)
                      if (label != null) {
                        Text(
                          text = label,
                          fontSize = 10.sp,
                          color = Color(0xFF686870),
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                          modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                      }

                      // Selection checkbox (Samsung Notes style) while in selection mode
                      if (selectionMode) {
                        if (isSelected) {
                          SelectedCheckBadge(
                            color = OneUi9Tokens.SamsungDeepBlue,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                          )
                        } else {
                          Box(
                            modifier = Modifier
                              .align(Alignment.TopEnd)
                              .padding(8.dp)
                              .size(26.dp)
                              .clip(CircleShape)
                              .border(2.dp, Color(0xFFB0B0B8), CircleShape)
                              .background(Color(0xCCFFFFFF))
                          )
                        }
                      } else if (note.isFavourite) {
                        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                          MyScriptIcons.Star(filled = true, color = Color(0xFFF5A623), size = 16.dp)
                        }
                      }
                    }
                  }

                  Spacer(Modifier.height(10.dp))
                  Text(
                    text = note.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUi9Tokens.PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                  )
                  Spacer(Modifier.height(2.dp))
                  Text(
                    text = df.format(Date(note.updatedAt)),
                    fontSize = 12.sp,
                    color = OneUi9Tokens.CountText
                  )
                }
              }
            }
          }
        }

        // Bottom-Right White Squircle Compose FAB with Coral-Red S-Pen Icon
        if (selectionMode) {
          OneUi9SelectionBottomBar(
            count = selectedNoteIds.size,
            actions = selectionActions,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(bottom = 24.dp)
          )
        } else
        Surface(
          onClick = {
            val targetFolderId = currentFolderObj?.id
            onCreateNewNote(targetFolderId)
          },
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 28.dp, bottom = 28.dp)
            .size(56.dp),
          shape = CircleShape,
          color = Color.White,
          shadowElevation = 8.dp
        ) {
          Box(contentAlignment = Alignment.Center) {
            MyScriptIcons.EditPencil(
              color = OneUi9Tokens.SNotesFabCoral,
              size = 24.dp
            )
          }
        }
      }
    }
  }

  if (!compact) {
    Row(modifier = Modifier.fillMaxSize()) {
      drawerPane(Modifier.width(284.dp))
      Box(
        modifier = Modifier
          .width(1.dp)
          .fillMaxHeight()
          .background(Color(0xFFE6E6EA))
      )
      mainPane(Modifier.weight(1f))
    }
  } else {
    mainPane(Modifier.fillMaxSize())
    AnimatedVisibility(visible = drawerOpen, enter = fadeIn(), exit = fadeOut()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0x66000000))
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { drawerOpen = false }
      )
    }
    AnimatedVisibility(
      visible = drawerOpen,
      enter = slideInHorizontally { -it },
      exit = slideOutHorizontally { -it }
    ) {
      drawerPane(Modifier.width(minOf(300.dp, screenWidth * 0.86f)).shadow(16.dp))
    }
  }
  }

  // Create / Edit Folder Dialog
  if (showFolderDialog) {
    val folderPalette = listOf(
      0xFFF5A623L,
      0xFF387AFFL,
      0xFF72C147L,
      0xFFDB4777L,
      0xFF635EE2L,
      0xFF00897BL
    )
    UiAlertDialog(
      onDismissRequest = { showFolderDialog = false },
      title = {
        Text(if (editingFolder == null) "Create folder" else "Edit folder")
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
          OutlinedTextField(
            value = folderNameInput,
            onValueChange = { folderNameInput = it },
            label = { Text("Folder name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            folderPalette.forEach { hex ->
              Box(
                modifier = Modifier
                  .size(30.dp)
                  .clip(CircleShape)
                  .background(Color(hex))
                  .border(
                    width = if (folderColorInput == hex) 3.dp else 0.dp,
                    color = if (folderColorInput == hex) OneUi9Tokens.PrimaryText else Color.Transparent,
                    shape = CircleShape
                  )
                  .clickable { folderColorInput = hex }
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val cleanName = folderNameInput.trim().ifBlank { "New folder" }
            val existing = editingFolder
            if (existing == null) {
              onCreateFolder(cleanName, folderColorInput)
            } else {
              onEditFolder(existing, cleanName, folderColorInput)
            }
            showFolderDialog = false
          }
        ) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = { showFolderDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun OneUi9NotesDrawerRow(
  icon: String,
  title: String,
  count: String,
  isBetaBadge: Boolean = false,
  selected: Boolean,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(46.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(if (selected) Color(0xFFE6E6EC) else Color.Transparent)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      MyScriptIcons.ByName(icon, color = OneUi9Tokens.PrimaryText, size = 17.dp)
      Spacer(Modifier.width(14.dp))
      Text(
        text = title,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = OneUi9Tokens.PrimaryText
      )
    }
    if (isBetaBadge) {
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF9E9EA6)
      ) {
        Text(
          text = count,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
      }
    } else {
      Text(
        text = count,
        fontSize = 13.sp,
        color = OneUi9Tokens.CountText
      )
    }
  }
}

@Composable
private fun OneUi9EditableFolderRow(
  folder: NoteFolder,
  count: Int,
  selected: Boolean,
  onSelect: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(42.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(if (selected) Color(0xFFE6E6EC) else Color.Transparent)
      .clickable(onClick = onSelect)
      .padding(start = 18.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      Box(
        modifier = Modifier
          .size(14.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(Color(folder.colorHex))
      )
      Spacer(Modifier.width(10.dp))
      Text(
        text = folder.name,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = OneUi9Tokens.PrimaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(count.toString(), fontSize = 12.sp, color = OneUi9Tokens.CountText)
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(26.dp)
          .clip(CircleShape)
          .clickable(onClick = onEdit)
      ) {
        MyScriptIcons.EditPencil(color = OneUi9Tokens.SecondaryText, size = 13.dp)
      }
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(26.dp)
          .clip(CircleShape)
          .clickable(onClick = onDelete)
      ) {
        MyScriptIcons.TrashDeleteIcon(color = OneUi9Tokens.FunctionalRed, size = 13.dp)
      }
    }
  }
}

/**
 * One UI editor top bar: title row (page style, graph, overflow menu) and the pen toolbar row.
 * Every icon has a content description so TalkBack and UI automation can find it.
 */
@Composable
fun OneUi9EditorTopBar(
  noteTitle: String,
  selectedTool: MyScriptTool,
  onSelectTool: (MyScriptTool) -> Unit,
  selectedPenColor: Long,
  onSelectPenColor: (Long) -> Unit,
  selectedThickness: Float,
  onCycleThickness: () -> Unit,
  onBack: () -> Unit,
  onEditTitle: () -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  graphVisible: Boolean,
  onToggleGraph: () -> Unit,
  onOpenPageStyle: () -> Unit,
  onClearPage: () -> Unit,
  onOpenSettings: () -> Unit,
  onSolve: () -> Unit,
  onCustomiseToolbar: () -> Unit,
  onPhoneToolbar: () -> Unit,
  phoneToolbarOn: Boolean,
  /** The handwriting toolbar; null when it's docked on another side of the editor. */
  toolStrip: (@Composable () -> Unit)?,
  modifier: Modifier = Modifier,
  onInsertPicture: () -> Unit = {},
  onCalculator: () -> Unit = {}
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = EditorChrome.bar,
    shadowElevation = 2.dp
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onBack, modifier = Modifier.size(34.dp).semantics { contentDescription = "Back to notes" }) {
            MyScriptIcons.BackArrow(color = EditorChrome.icon, size = 18.dp)
          }
          Spacer(Modifier.width(4.dp))
          Text(
            text = if (noteTitle.isBlank() || noteTitle == "Untitled") "Title" else noteTitle,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (noteTitle == "Untitled") EditorChrome.secondaryText else EditorChrome.text,
            modifier = Modifier
              .clickable(onClickLabel = "Rename note") { onEditTitle() }
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          IconButton(
            onClick = onInsertPicture,
            modifier = Modifier
              .size(34.dp)
              .semantics { contentDescription = "Insert picture" }
          ) {
            MyScriptIcons.ImageFrame(color = EditorChrome.icon, size = 18.dp)
          }
          IconButton(onClick = onOpenPageStyle, modifier = Modifier.size(34.dp).semantics { contentDescription = "Page style" }) {
            MyScriptIcons.PreferencesSliders(color = EditorChrome.icon, size = 18.dp)
          }
          var menuOpen by remember { mutableStateOf(false) }
          Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp).semantics { contentDescription = "More options" }) {
              MyScriptIcons.MoreVerticalDots(color = EditorChrome.icon, size = 18.dp)
            }
            UiMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
              UiMenuItem(text = { Text("Solve step by step") }, onClick = { menuOpen = false; onSolve() })
              UiMenuItem(text = { Text("Graphing calculator") }, onClick = { menuOpen = false; onCalculator() })
              UiMenuItem(text = { Text("Insert picture") }, onClick = { menuOpen = false; onInsertPicture() })
              UiMenuItem(text = { Text(if (graphVisible) "Hide graph" else "Show graph") }, onClick = { menuOpen = false; onToggleGraph() })
              UiMenuItem(text = { Text("Page style") }, onClick = { menuOpen = false; onOpenPageStyle() })
              UiMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onEditTitle() })
              UiMenuItem(text = { Text("Customise toolbar") }, onClick = { menuOpen = false; onCustomiseToolbar() })
              UiMenuItem(text = { Text(if (phoneToolbarOn) "Stop using phone as toolbar" else "Use phone as toolbar") }, onClick = { menuOpen = false; onPhoneToolbar() })
              UiMenuItem(text = { Text("Clear page") }, onClick = { menuOpen = false; onClearPage() })
              HorizontalDivider()
              UiMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onOpenSettings() })
            }
          }
        }
      }

      HorizontalDivider(color = EditorChrome.divider, thickness = 1.dp)

      // Samsung Notes handwriting toolbar (configurable in Customise toolbar)
      if (toolStrip != null) {
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) { toolStrip() }
      }

      HorizontalDivider(color = EditorChrome.divider, thickness = 1.dp)
    }
  }
}

@Composable
private fun OneUiSubBarIcon(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  content: @Composable () -> Unit
) {
  Box(
    modifier = Modifier
      .size(32.dp)
      .clip(CircleShape)
      .background(if (selected) Color(0xFFE4E4E9) else Color.Transparent)
      .clickable(onClick = onClick)
      .semantics {
        contentDescription = label
        this.selected = selected
      },
    contentAlignment = Alignment.Center
  ) {
    content()
  }
}

@Composable
private fun OneUiSubBarDivider() {
  Box(
    modifier = Modifier
      .padding(horizontal = 10.dp)
      .width(1.dp)
      .height(18.dp)
      .background(Color(0xFFDCDCE2))
  )
}

/** How far item [index] (0 = first to appear) has fanned out, given the menu's overall progress. */
private fun radialItemProgress(menu: Float, index: Int): Float {
  // Each item animates over its own 70% slice of the open animation, starting 5% after the one before;
  // the last (index 6) ends exactly at 1. The spring's overshoot past 1 is added on top.
  val base = menu.coerceAtMost(1f)
  val overshoot = menu - base
  return ((base - 0.05f * index) / 0.7f).coerceIn(0f, 1f) + overshoot
}

/**
 * Places a quick-menu button at ([dx], [dy]) from the centre. While the menu opens, it slides out
 * from the centre and grows, slightly after the button before it.
 */
private fun Modifier.radialItem(dx: Dp, dy: Dp, index: Int, menu: () -> Float): Modifier =
  this
    .offset {
      val p = radialItemProgress(menu(), index)
      IntOffset((dx.toPx() * p).roundToInt(), (dy.toPx() * p).roundToInt())
    }
    .graphicsLayer {
      val p = radialItemProgress(menu(), index)
      val s = 0.4f + 0.6f * p
      scaleX = s
      scaleY = s
      alpha = p.coerceIn(0f, 1f)
    }

/**
 * S Pen quick menu, opened by a short press of the side button while hovering. It pops out of the
 * pen tip like the Samsung Notes one: the ring scales up with a slight overshoot, then the buttons
 * fan out from the centre one after another. Closing plays it in reverse, faster.
 */
@Composable
fun SpenHoverRadialMenu(
  visible: Boolean,
  centerOffset: Offset,
  selectedTool: MyScriptTool,
  selectedPenColor: Long,
  selectedThickness: Float,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onCycleThickness: () -> Unit,
  onCycleColor: () -> Unit,
  onSelectEraser: () -> Unit,
  onSelectPen: () -> Unit,
  onTriggerSparkleSolve: () -> Unit,
  onDismiss: () -> Unit
) {
  val progress = remember { Animatable(0f) }
  LaunchedEffect(visible) {
    if (visible) {
      progress.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 420f))
    } else {
      progress.animateTo(0f, tween(durationMillis = 150, easing = FastOutLinearInEasing))
    }
  }
  if (!visible && progress.value == 0f) return
  val menu = { progress.value }

  val menuSizeDp = 176.dp
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
  val half = menuSizeDp / 2
  Box(
    modifier = Modifier
      .offset {
        // Centred on the pen tip, kept fully on screen
        val h = half.roundToPx()
        IntOffset(
          x = (centerOffset.x.roundToInt() - h).coerceIn(0, (constraints.maxWidth - 2 * h).coerceAtLeast(0)),
          y = (centerOffset.y.roundToInt() - h).coerceIn(0, (constraints.maxHeight - 2 * h).coerceAtLeast(0))
        )
      }
      .size(menuSizeDp),
    contentAlignment = Alignment.Center
  ) {
    // Outer white circular ring (#FAFAFC) with soft drop shadow; pops out of the pen tip
    Surface(
      modifier = Modifier
        .size(168.dp)
        .graphicsLayer {
          val p = menu()
          val s = 0.35f + 0.65f * p
          scaleX = s
          scaleY = s
          alpha = (p * 2.5f).coerceIn(0f, 1f)
        },
      shape = CircleShape,
      color = Color(0xFFFAFAFC),
      shadowElevation = 12.dp,
      border = BorderStroke(0.8.dp, Color(0xFFE6E6EC))
    ) {}

    // Inner recessed circle (#EFEFF2) with the pen illustration. Surface(onClick) keeps the
    // hover/press highlight inside the circle (a plain .clickable drew it as a square).
    Surface(
      onClick = onDismiss,
      modifier = Modifier
        .size(88.dp)
        .graphicsLayer {
          val p = menu()
          val s = 0.6f + 0.4f * p
          scaleX = s
          scaleY = s
          alpha = (p * 2f).coerceIn(0f, 1f)
        },
      shape = CircleShape,
      color = Color(0xFFEFEFF2)
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val h = size.height

        // Soft shadow to the right of the pen barrel
        drawRoundRect(
          color = Color(0x18000000),
          topLeft = Offset(cx - 10.dp.toPx(), h * 0.24f),
          size = Size(24.dp.toPx(), h * 0.76f),
          cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )

        // Sharp pen tip at top-center
        val tipPath = Path().apply {
          moveTo(cx, h * 0.13f)
          lineTo(cx - 2.6.dp.toPx(), h * 0.22f)
          lineTo(cx + 2.6.dp.toPx(), h * 0.22f)
          close()
        }
        drawPath(tipPath, Color(selectedPenColor))

        // White conical upper cone (from y=0.22 to y=0.52)
        val conePath = Path().apply {
          moveTo(cx - 2.6.dp.toPx(), h * 0.22f)
          lineTo(cx + 2.6.dp.toPx(), h * 0.22f)
          lineTo(cx + 10.dp.toPx(), h * 0.52f)
          lineTo(cx - 10.dp.toPx(), h * 0.52f)
          close()
        }
        drawPath(
          path = conePath,
          brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFFFDFDFD), Color(0xFFEAEAEF), Color(0xFFD8D8DF)),
            startX = cx - 10.dp.toPx(),
            endX = cx + 10.dp.toPx()
          )
        )

        // Black / Colored middle band (from y=0.52 to y=0.66)
        drawRoundRect(
          color = Color(selectedPenColor),
          topLeft = Offset(cx - 11.dp.toPx(), h * 0.52f),
          size = Size(22.dp.toPx(), h * 0.14f),
          cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )

        // White lower barrel (from y=0.66 to bottom)
        drawRect(
          brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFE8E8EE), Color(0xFFD5D5DC)),
            startX = cx - 10.dp.toPx(),
            endX = cx + 10.dp.toPx()
          ),
          topLeft = Offset(cx - 10.dp.toPx(), h * 0.66f),
          size = Size(20.dp.toPx(), h * 0.34f)
        )
      }
    }

    // 1. Top-Left (~10 o'clock): Undo "↶"
    Box(
      modifier = Modifier
        .radialItem((-42).dp, (-44).dp, 1, menu)
        .size(32.dp)
        .clip(CircleShape)
        .clickable { onUndo() },
      contentAlignment = Alignment.Center
    ) {
      MyScriptIcons.UndoArrow(color = Color(0xFFB0B0B8), size = 16.dp)
    }

    // 2. Top-Right (~2 o'clock): Redo "↷"
    Box(
      modifier = Modifier
        .radialItem(42.dp, (-44).dp, 2, menu)
        .size(32.dp)
        .clip(CircleShape)
        .clickable { onRedo() },
      contentAlignment = Alignment.Center
    ) {
      MyScriptIcons.RedoArrow(color = Color(0xFFB0B0B8), size = 16.dp)
    }

    // 3. Middle-Left (9 o'clock): Stroke Thickness Dot Button
    Surface(
      onClick = onCycleThickness,
      modifier = Modifier
        .radialItem((-62).dp, (-2).dp, 0, menu)
        .size(30.dp),
      shape = CircleShape,
      color = Color(0xFFEFEFF2)
    ) {
      Box(contentAlignment = Alignment.Center) {
        val dotSize = if (selectedThickness > 11f) 9.dp else if (selectedThickness > 7f) 6.dp else 4.dp
        Box(
          modifier = Modifier
            .size(dotSize)
            .clip(CircleShape)
            .background(Color(0xFF1A1A1A))
        )
      }
    }

    // 4. Middle-Right (3 o'clock): Active Pen Color Circle
    Surface(
      onClick = onCycleColor,
      modifier = Modifier
        .radialItem(62.dp, (-2).dp, 3, menu)
        .size(26.dp),
      shape = CircleShape,
      color = Color(selectedPenColor),
      border = BorderStroke(1.dp, Color(0x22000000))
    ) {}

    // 5. Bottom-Left (~7 o'clock): Eraser (Rubber) Icon
    Surface(
      onClick = onSelectEraser,
      modifier = Modifier
        .radialItem((-34).dp, 52.dp, 6, menu)
        .size(30.dp),
      shape = CircleShape,
      color = if (selectedTool == MyScriptTool.ERASER) Color(0xFFDCDCE4) else Color(0xFFEFEFF2)
    ) {
      Box(contentAlignment = Alignment.Center) {
        MyScriptIcons.EraserBlock(color = Color(0xFF75757C), size = 15.dp)
      }
    }

    // 6. Bottom-Center (6 o'clock, slightly protruding elevated white circle): Pen Icon
    Surface(
      onClick = onSelectPen,
      modifier = Modifier
        .radialItem(0.dp, 66.dp, 5, menu)
        .size(36.dp),
      shape = CircleShape,
      color = Color.White,
      shadowElevation = 5.dp,
      border = BorderStroke(0.8.dp, Color(0xFFE2E2E8))
    ) {
      Box(contentAlignment = Alignment.Center) {
        MyScriptIcons.EditPencil(color = Color(0xFF45454A), size = 16.dp)
      }
    }

    // 7. Bottom-Right (~5 o'clock): Sparkle Math Assist Icon ("✦+")
    Surface(
      onClick = onTriggerSparkleSolve,
      modifier = Modifier
        .radialItem(34.dp, 52.dp, 4, menu)
        .size(30.dp),
      shape = CircleShape,
      color = Color(0xFFEFEFF2)
    ) {
      Box(contentAlignment = Alignment.Center) {
        MyScriptIcons.SparkleAi(color = Color(0xFF75757C), size = 15.dp)
      }
    }
  }
  }
}

/**
 * One UI 9.0 Floating Solution Steps Sheet (Squircle `26.dp` card with grouped white step cards
 * and One UI 9.0 Lavender-Blue `RelatedCard` for the Final Answer).
 */
@Composable
fun OneUi9SolutionStepsCard(
  solution: SymbolicStepEngine.SymbolicSolution,
  ocrStatsText: String,
  geminiExplanation: String?,
  isGeminiRunning: Boolean,
  onAskGeminiNano: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier
      .width(420.dp)
      .heightIn(max = 580.dp)
      .shadow(16.dp, RoundedCornerShape(OneUi9Tokens.CardRadius)),
    shape = RoundedCornerShape(OneUi9Tokens.CardRadius),
    color = if (EditorChrome.dark) Color(0xFF161618) else OneUi9Tokens.RoundAndBgColor,
    border = BorderStroke(1.dp, if (EditorChrome.dark) Color(0xFF2E2E32) else Color(0xFFE0E0E8))
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(28.dp)
              .clip(CircleShape)
              .background(OneUi9Tokens.GalaxyAiBlue),
            contentAlignment = Alignment.Center
          ) {
            MyScriptIcons.SparkleAi(color = Color.White, size = 16.dp)
          }
          Spacer(Modifier.width(10.dp))
          Column {
            Text(
              text = "Solution steps",
              fontSize = 17.sp,
              fontWeight = FontWeight.Bold,
              color = EditorChrome.text
            )
            Text(
              text = ocrStatsText,
              fontSize = 11.sp,
              color = OneUi9Tokens.SamsungBlue
            )
          }
        }
        Box(Modifier.clip(CircleShape).clickable { onClose() }.padding(6.dp)) {
          MyScriptIcons.CloseX(color = EditorChrome.text, size = 18.dp)
        }
      }

      Column(
        modifier = Modifier
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp)
      ) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(22.dp),
          color = if (EditorChrome.dark) Color(0xFF242427) else OneUi9Tokens.CardBackground
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            solution.steps.forEachIndexed { idx, stepText ->
              var expanded by remember(idx) { mutableStateOf(true) }
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { expanded = !expanded }
                  .padding(horizontal = 18.dp, vertical = 12.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "STEP ${idx + 1}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUi9Tokens.SamsungBlue
                  )
                  MyScriptIcons.ExpandChevron(expanded, color = EditorChrome.secondaryText, size = 14.dp)
                }
                if (expanded) {
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = stepText,
                    fontSize = 13.5.sp,
                    color = EditorChrome.text,
                    lineHeight = 19.sp
                  )
                }
              }
              if (idx < solution.steps.lastIndex) {
                HorizontalDivider(
                  modifier = Modifier.padding(horizontal = 18.dp),
                  thickness = 1.dp,
                  color = OneUi9Tokens.ListDividerColor
                )
              }
            }
          }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(22.dp),
          color = if (EditorChrome.dark) Color(0xFF1E2840) else OneUi9Tokens.RelatedCardBg
        ) {
          Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
              text = "Final answer",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = OneUi9Tokens.SamsungDeepBlue
            )
            Spacer(Modifier.height(6.dp))
            Text(
              text = solution.finalAnswer,
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Serif,
              fontStyle = FontStyle.Italic,
              color = EditorChrome.text
            )
          }
        }

        if (!geminiExplanation.isNullOrBlank()) {
          Spacer(Modifier.height(12.dp))
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (EditorChrome.dark) Color(0xFF242427) else Color.White
          ) {
            Column(modifier = Modifier.padding(16.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                MyScriptIcons.SparkleAi(color = OneUi9Tokens.GalaxyAiBlue, size = 13.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                  text = "Galaxy AI / Gemini Nano On-Device",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = OneUi9Tokens.GalaxyAiBlue
                )
              }
              Spacer(Modifier.height(4.dp))
              Text(
                text = geminiExplanation,
                fontSize = 12.5.sp,
                color = EditorChrome.text,
                lineHeight = 18.sp
              )
            }
          }
        }

        Spacer(Modifier.height(14.dp))
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Surface(
          onClick = onAskGeminiNano,
          shape = RoundedCornerShape(18.dp),
          color = OneUi9Tokens.SamsungBlue
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
          ) {
            MyScriptIcons.SparkleAi(color = Color.White, size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(
              text = if (isGeminiRunning) "Thinking..." else "Explain with Gemini Nano",
              fontSize = 12.5.sp,
              fontWeight = FontWeight.SemiBold,
              color = Color.White
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          MyScriptIcons.ThumbUp(color = EditorChrome.secondaryText, size = 16.dp)
          MyScriptIcons.ThumbDown(color = EditorChrome.secondaryText, size = 16.dp)
        }
      }
    }
  }
}
