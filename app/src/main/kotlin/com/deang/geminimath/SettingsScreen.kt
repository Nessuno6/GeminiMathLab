package com.deang.geminimath

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

enum class SettingsSection(val title: String, val summary: String, val symbol: String, val color: Color) {
  // symbol = icon key for MyScriptIcons.ByName
  ACCOUNT("Account & backup", "Google account and Drive backup", "account", OneUi9Tokens.SamsungBlue),
  WRITING("Pen & writing", "S Pen, finger input, auto-solve", "pen", OneUi9Tokens.GalaxyAiPurple),
  MATH("Math", "Angles and number format", "math", OneUi9Tokens.FunctionalOrange),
  APPEARANCE("Appearance", "App style and default paper", "appearance", OneUi9Tokens.WallpaperPink),
  AI("AI assistant", "Claude or Gemini, with your own API key", "assistant", OneUi9Tokens.SamsungBlue),
  ABOUT("About", "What the app does, version, privacy", "about", Color(0xFF6E6E73))
}

data class AccountInfo(val name: String?, val email: String)

/** Look of the settings screen, so it matches the active app design (One UI 9 or M3E). */
data class SettingsStyle(
  val isM3e: Boolean,
  val screenBg: Color,
  val sidebarBg: Color,
  val listCardBg: Color,
  val cardBg: Color,
  val cardRadius: androidx.compose.ui.unit.Dp,
  val itemRadius: androidx.compose.ui.unit.Dp,
  val selectedItemBg: Color,
  val controlBg: Color,
  val primaryText: Color,
  val secondaryText: Color,
  val groupTitle: Color,
  val accent: Color,
  val divider: Color,
  val danger: Color
)

val OneUiSettingsStyle = SettingsStyle(
  isM3e = false,
  screenBg = OneUi9Tokens.RoundAndBgColor,
  sidebarBg = Color.Transparent,
  listCardBg = OneUi9Tokens.CardBackground,
  cardBg = OneUi9Tokens.CardBackground,
  cardRadius = OneUi9Tokens.CardRadius,
  itemRadius = OneUi9Tokens.DrawerItemRadius,
  selectedItemBg = Color(0xFFE9ECF5),
  controlBg = Color(0xFFEFEFF3),
  primaryText = OneUi9Tokens.PrimaryText,
  secondaryText = OneUi9Tokens.SecondaryText,
  groupTitle = OneUi9Tokens.SamsungDeepBlue,
  accent = OneUi9Tokens.SamsungBlue,
  divider = OneUi9Tokens.ListDividerColor,
  danger = OneUi9Tokens.FunctionalRed
)

/** Material 3 Expressive look used by the M3E (MyScript-style) design. */
val M3eSettingsStyle = SettingsStyle(
  isM3e = true,
  screenBg = Color.White,
  sidebarBg = Color(0xFFF7F7F7),
  listCardBg = Color.Transparent,
  cardBg = Color(0xFFF5F6F8),
  cardRadius = 20.dp,
  itemRadius = 24.dp,
  selectedItemBg = Color.White,
  controlBg = Color(0xFFE9EAEE),
  primaryText = Color(0xFF1F1F1F),
  secondaryText = Color(0xFF757575),
  groupTitle = Color(0xFF757575),
  accent = Color(0xFF1A73E8),
  divider = Color(0xFFE6E6E9),
  danger = Color(0xFFD93025)
)

val LocalSettingsStyle = androidx.compose.runtime.staticCompositionLocalOf { OneUiSettingsStyle }
private val S: SettingsStyle
  @Composable get() = LocalSettingsStyle.current

fun settingsStyleFor(mode: UiStyleMode) = if (mode == UiStyleMode.M3E) M3eSettingsStyle else OneUiSettingsStyle

/**
 * The single settings screen. On wide screens the section list and its content sit side by side;
 * on phones the list opens each section as its own page.
 */
@Composable
fun SettingsScreen(
  settings: AppSettings,
  section: SettingsSection?,
  onSectionChange: (SettingsSection?) -> Unit,
  account: AccountInfo?,
  isSyncing: Boolean,
  onSignIn: () -> Unit,
  onSignOut: () -> Unit,
  onSyncNow: () -> Unit,
  onBackupChange: (Boolean) -> Unit,
  modelStatus: String,
  nanoStatus: String,
  appVersion: String,
  onClose: () -> Unit
) {
  androidx.compose.runtime.CompositionLocalProvider(LocalSettingsStyle provides settingsStyleFor(settings.uiStyle)) {
    SettingsScreenContent(settings, section, onSectionChange, account, isSyncing, onSignIn, onSignOut, onSyncNow, onBackupChange, modelStatus, nanoStatus, appVersion, onClose)
  }
}

@Composable
private fun SettingsScreenContent(
  settings: AppSettings,
  section: SettingsSection?,
  onSectionChange: (SettingsSection?) -> Unit,
  account: AccountInfo?,
  isSyncing: Boolean,
  onSignIn: () -> Unit,
  onSignOut: () -> Unit,
  onSyncNow: () -> Unit,
  onBackupChange: (Boolean) -> Unit,
  modelStatus: String,
  nanoStatus: String,
  appVersion: String,
  onClose: () -> Unit
) {
  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(S.screenBg)
      .statusBarsPadding()
      .navigationBarsPadding()
  ) {
    val wide = maxWidth >= 720.dp
    val shown = section ?: if (wide) SettingsSection.ACCOUNT else null

    BackHandler {
      if (!wide && section != null) onSectionChange(null) else onClose()
    }

    val content: @Composable () -> Unit = {
      when (shown) {
        SettingsSection.ACCOUNT -> AccountSection(settings, account, isSyncing, onSignIn, onSignOut, onSyncNow, onBackupChange)
        SettingsSection.WRITING -> WritingSection(settings)
        SettingsSection.MATH -> MathSection(settings)
        SettingsSection.AI -> AiSection(settings)
        SettingsSection.APPEARANCE -> AppearanceSection(settings)
        SettingsSection.ABOUT -> AboutSection(appVersion, modelStatus, nanoStatus)
        null -> {}
      }
    }

    if (wide) {
      Row(Modifier.fillMaxSize()) {
        SectionList(
          selected = shown,
          account = account,
          onSelect = { onSectionChange(it) },
          onClose = onClose,
          modifier = Modifier.width(if (S.isM3e) 280.dp else 340.dp).fillMaxHeight().background(S.sidebarBg)
        )
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 48.dp, top = 28.dp, bottom = 36.dp)
        ) {
          Text(shown!!.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = S.primaryText)
          Spacer(Modifier.height(20.dp))
          content()
        }
      }
    } else if (shown == null) {
      SectionList(selected = null, account = account, onSelect = { onSectionChange(it) }, onClose = onClose, modifier = Modifier.fillMaxSize())
    } else {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 12.dp)
      ) {
        Header(title = shown.title, backLabel = "Back to settings", onBack = { onSectionChange(null) })
        Spacer(Modifier.height(16.dp))
        content()
      }
    }
  }
}

@Composable
private fun Header(title: String, backLabel: String, onBack: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
    IconButton(onClick = onBack, modifier = Modifier.size(40.dp).semantics { contentDescription = backLabel }) {
      // M3E closes settings with ✕ like the MyScript layout; One UI uses a back arrow
      if (S.isM3e && backLabel == "Close settings") MyScriptIcons.CloseX(color = S.primaryText)
      else MyScriptIcons.BackArrow(color = S.primaryText)
    }
    Spacer(Modifier.width(6.dp))
    Text(title, fontSize = if (S.isM3e) 20.sp else 24.sp, fontWeight = if (S.isM3e) FontWeight.Normal else FontWeight.Bold, color = S.primaryText)
  }
}

@Composable
private fun SectionList(
  selected: SettingsSection?,
  account: AccountInfo?,
  onSelect: (SettingsSection) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp)
  ) {
    Header(title = "Settings", backLabel = "Close settings", onBack = onClose)
    Spacer(Modifier.height(16.dp))

    // Account summary at the top, like system settings
    Surface(
      shape = RoundedCornerShape(S.cardRadius),
      color = S.listCardBg,
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(S.cardRadius))
        .clickable { onSelect(SettingsSection.ACCOUNT) }
    ) {
      Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(account)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
          Text(
            account?.name ?: account?.email ?: "Not signed in",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = S.primaryText,
            maxLines = 1, overflow = TextOverflow.Ellipsis
          )
          Text(
            if (account != null) account.email else "Sign in to back up notes to Google Drive",
            fontSize = 13.sp, color = S.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis
          )
        }
      }
    }

    Spacer(Modifier.height(14.dp))

    Surface(shape = RoundedCornerShape(S.cardRadius), color = S.listCardBg) {
      Column(Modifier.padding(vertical = 6.dp)) {
        SettingsSection.entries.forEach { s ->
          val isSel = s == selected
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 6.dp, vertical = 2.dp)
              .clip(RoundedCornerShape(S.itemRadius))
              .background(if (isSel) S.selectedItemBg else Color.Transparent)
              .clickable { onSelect(s) }
              .semantics { this.selected = isSel; role = Role.Tab }
              .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              Modifier.size(30.dp).clip(CircleShape).background(s.color),
              contentAlignment = Alignment.Center
            ) { MyScriptIcons.ByName(s.symbol, color = Color.White, size = 16.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
              Text(s.title, fontSize = 16.sp, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal, color = S.primaryText)
              Text(s.summary, fontSize = 12.sp, color = S.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Avatar(account: AccountInfo?) {
  val initial = (account?.name ?: account?.email)?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
  Box(
    Modifier.size(44.dp).clip(CircleShape).background(if (account != null) S.accent else Color(0xFFC7C7CC)),
    contentAlignment = Alignment.Center
  ) { Text(initial, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

// ----------------------------------------------------------------------------------------------
// Building blocks

@Composable
private fun Group(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
  if (title != null) {
    Text(
      title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = S.groupTitle,
      modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
    )
  }
  Surface(shape = RoundedCornerShape(S.cardRadius), color = S.cardBg, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(vertical = 4.dp), content = content)
  }
  if (footer != null) {
    Text(footer, fontSize = 12.sp, color = S.secondaryText, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
  }
  Spacer(Modifier.height(20.dp))
}

/** One UI 9 switch in One UI, Material 3 switch in M3E. */
@Composable
private fun StyledSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
  if (S.isM3e) {
    Switch(
      checked = checked,
      onCheckedChange = { if (enabled) onChange(it) },
      enabled = enabled,
      colors = SwitchDefaults.colors(checkedTrackColor = S.accent)
    )
  } else {
    OneUi9Switch(checked = checked, onCheckedChange = { if (enabled) onChange(it) }, showLeftDivider = true)
  }
}

@Composable
private fun RowDivider() {
  HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), thickness = 1.dp, color = S.divider)
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .alpha(if (enabled) 1f else 0.45f)
      .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
      .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, fontSize = 16.sp, color = S.primaryText)
      if (summary != null) Text(summary, fontSize = 13.sp, color = S.secondaryText)
    }
    StyledSwitch(checked = checked, enabled = enabled, onChange = onChange)
  }
}

@Composable
private fun ActionRow(title: String, summary: String?, action: String? = null, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .alpha(if (enabled) 1f else 0.45f)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, fontSize = 16.sp, color = if (danger) S.danger else S.primaryText)
      if (summary != null) Text(summary, fontSize = 13.sp, color = S.secondaryText)
    }
    if (action != null) Text(action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = S.accent)
  }
}

@Composable
private fun InfoRow(title: String, value: String) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, fontSize = 16.sp, color = S.primaryText, modifier = Modifier.weight(1f))
    Text(value, fontSize = 14.sp, color = S.secondaryText)
  }
}

/** Title with a row of mutually exclusive options. */
@Composable
private fun ChoiceRow(title: String, summary: String?, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
    Text(title, fontSize = 16.sp, color = S.primaryText)
    if (summary != null) Text(summary, fontSize = 13.sp, color = S.secondaryText)
    Spacer(Modifier.height(10.dp))
    Row(
      Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(S.controlBg).padding(3.dp)
    ) {
      options.forEachIndexed { i, label ->
        val sel = i == selectedIndex
        Box(
          modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(11.dp))
            .background(if (sel) Color.White else Color.Transparent)
            .clickable(role = Role.RadioButton) { onSelect(i) }
            .semantics { selected = sel }
            .padding(vertical = 9.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(label, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
            color = if (sel) S.primaryText else S.secondaryText)
        }
      }
    }
  }
}

@Composable
private fun StepperRow(title: String, summary: String?, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
  Row(
    Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, fontSize = 16.sp, color = S.primaryText)
      if (summary != null) Text(summary, fontSize = 13.sp, color = S.secondaryText)
    }
    StepButton("−", "Fewer decimal places", enabled && value > range.first) { onChange(value - 1) }
    Text(value.toString(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    StepButton("+", "More decimal places", enabled && value < range.last) { onChange(value + 1) }
  }
}

@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .size(36.dp)
      .clip(CircleShape)
      .background(S.controlBg)
      .alpha(if (enabled) 1f else 0.4f)
      .clickable(enabled = enabled, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center
  ) {
    if (symbol == "+") MyScriptIcons.Plus(color = S.primaryText, size = 18.dp)
    else MyScriptIcons.Minus(color = S.primaryText, size = 18.dp)
  }
}

// ----------------------------------------------------------------------------------------------
// Sections

@Composable
private fun AccountSection(
  settings: AppSettings,
  account: AccountInfo?,
  isSyncing: Boolean,
  onSignIn: () -> Unit,
  onSignOut: () -> Unit,
  onSyncNow: () -> Unit,
  onBackupChange: (Boolean) -> Unit
) {
  Group(title = "Google account") {
    if (account == null) {
      ActionRow("Sign in with Google", "Needed to back up notes to Google Drive", action = "Sign in", onClick = onSignIn)
    } else {
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(account)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
          if (account.name != null) Text(account.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = S.primaryText)
          Text(account.email, fontSize = 13.sp, color = S.secondaryText)
        }
      }
      RowDivider()
      ActionRow("Sign out", null, danger = true, onClick = onSignOut)
    }
  }

  Group(
    title = "Google Drive backup",
    footer = "Notes are stored in the app's private Drive folder. They are not visible in your Drive file list."
  ) {
    SwitchRow(
      "Back up notes to Google Drive",
      if (account == null) "Turning this on asks you to sign in" else "Keeps notes in sync across your Android devices",
      checked = settings.driveBackup,
      onChange = onBackupChange
    )
    RowDivider()
    ActionRow(
      "Back up now",
      when {
        isSyncing -> "Syncing…"
        settings.lastSyncStatus != null -> settings.lastSyncStatus
        else -> "Not backed up yet"
      },
      action = if (isSyncing) null else "Sync",
      enabled = account != null && settings.driveBackup && !isSyncing,
      onClick = onSyncNow
    )
  }
}

@Composable
private fun WritingSection(settings: AppSettings) {
  Group(title = "Input") {
    SwitchRow(
      "Write with S Pen only",
      if (settings.penOnly) "Fingers don't draw on the page" else "Both S Pen and fingers draw on the page",
      checked = settings.penOnly,
      onChange = { settings.penOnly = it }
    )
    RowDivider()
    SwitchRow(
      "S Pen button shortcuts",
      "Hold the button while writing to erase. Press it while hovering to open the quick menu.",
      checked = settings.penButtonShortcuts,
      onChange = { settings.penButtonShortcuts = it }
    )
    RowDivider()
    // Phone as toolbar: open by itself when a tablet asks. Android needs "Display over other apps" for that.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var canOpen by remember { mutableStateOf(ToolbarWake.canOpenDirectly(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
      canOpen = ToolbarWake.canOpenDirectly(ctx)
      onPauseOrDispose { }
    }
    SwitchRow(
      "Open phone toolbar automatically",
      if (canOpen) "When your tablet asks for the phone toolbar, it opens on this phone even if the app is closed"
      else "Off: you'll get a notification to tap instead. Turning this on opens Android's \"Display over other apps\" setting",
      checked = canOpen,
      onChange = {
        ctx.startActivity(
          android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
      }
    )
    RowDivider()
    SwitchRow(
      "Insert pictures on layer 1",
      "Pictures and graphs go on their own layer (made if the note has only one), so you can hide them or change their opacity without touching your writing",
      checked = settings.picturesOnLayer2,
      onChange = { settings.picturesOnLayer2 = it }
    )
  }
  Group(title = "Solving", footer = "To solve part of the page, circle it with the lasso and tap ✦ Solve.") {
    SwitchRow(
      "Solve when you write =",
      "Shows the answer after you finish writing an equals sign",
      checked = settings.autoSolve,
      onChange = { settings.autoSolve = it }
    )
  }
}

/**
 * The assistant (robot button at the end of the toolbar): which AI answers, and your API keys.
 * Keys are stored encrypted on this device and only ever sent to that AI's own servers.
 */
@Composable
private fun AiSection(settings: AppSettings) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  var keysChanged by remember { mutableStateOf(0) }
  Group(title = "Assistant", footer = "Ask questions about the open page with the AI assistant button at the end of the toolbar. Each question sends a picture of the page to the AI you choose.") {
    ChoiceRow("Answers from", null, AiProvider.entries.map { it.label }, settings.aiProvider.ordinal) { settings.aiProvider = AiProvider.entries[it] }
  }
  @Composable
  fun KeyField(provider: AiProvider, hint: String) {
    @Suppress("UNUSED_EXPRESSION") keysChanged
    val saved = remember(keysChanged) { KeyVault.has(ctx, provider) }
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
      Text("${provider.label} API key", fontSize = 16.sp, color = S.primaryText)
      Text(if (saved) "Saved on this device (encrypted)" else hint, fontSize = 13.sp, color = S.secondaryText)
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = typed, onValueChange = { typed = it }, singleLine = true,
          placeholder = { Text(if (saved) "••••••••  (type to replace)" else "Paste your key", fontSize = 14.sp) },
          visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
          modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = typed.isNotBlank(), onClick = { KeyVault.save(ctx, provider, typed); typed = ""; keysChanged++ }) { Text("Save") }
        if (saved) TextButton(onClick = { KeyVault.save(ctx, provider, ""); keysChanged++ }) { Text("Remove", color = S.danger) }
      }
    }
  }
  Group(title = "Claude", footer = "Get a key at console.anthropic.com (API keys). Use is billed to your Anthropic account.") {
    KeyField(AiProvider.CLAUDE, "Not set")
    RowDivider()
    ChoiceRow("Model", null, ClaudeModels.map { it.second.substringBefore(" (") }, ClaudeModels.indexOfFirst { it.first == settings.claudeModel }.coerceAtLeast(0)) {
      settings.claudeModel = ClaudeModels[it].first
    }
  }
  Group(title = "Gemini", footer = "Get a key at aistudio.google.com (Get API key). Leave the model empty to use the newest Gemini Flash.") {
    KeyField(AiProvider.GEMINI, "Not set")
    RowDivider()
    var model by remember { mutableStateOf(settings.geminiModel) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
      Text("Model", fontSize = 16.sp, color = S.primaryText)
      OutlinedTextField(
        value = model, onValueChange = { model = it; settings.geminiModel = it }, singleLine = true,
        placeholder = { Text("Newest Gemini Flash", fontSize = 14.sp) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
      )
    }
  }
}

@Composable
private fun MathSection(settings: AppSettings) {
  Group(title = "Angles", footer = "Used when evaluating sin, cos and tan of numbers. Calculus always uses radians.") {
    ChoiceRow("Angle unit", null, listOf("Radians", "Degrees"), if (settings.angleDegrees) 1 else 0) {
      settings.angleDegrees = it == 1
    }
  }
  Group(title = "Results") {
    ChoiceRow(
      "Number format",
      if (settings.exactFractions) "Exact, e.g. 1/3 and √2" else "Decimal, e.g. 0.3333",
      listOf("Exact", "Decimal"),
      if (settings.exactFractions) 0 else 1
    ) { settings.exactFractions = it == 0 }
    RowDivider()
    StepperRow(
      "Decimal places",
      if (settings.exactFractions) "Used when a result has no exact form" else null,
      value = settings.decimals,
      range = 0..10,
      enabled = true,
      onChange = { settings.decimals = it }
    )
  }
}

@Composable
private fun AppearanceSection(settings: AppSettings) {
  Group(title = "App style") {
    ChoiceRow(
      "Design",
      if (settings.uiStyle == UiStyleMode.ONEUI_9) "One UI 9: Samsung Notes-style toolbars, folders and settings"
      else "M3E: Material 3 Expressive, floating pen toolbar and side rail",
      listOf("One UI 9", "M3E"),
      if (settings.uiStyle == UiStyleMode.ONEUI_9) 0 else 1
    ) { settings.uiStyle = if (it == 0) UiStyleMode.ONEUI_9 else UiStyleMode.M3E }
  }
  Group(title = "Default paper for new notes", footer = "To change the paper of an open note, use Page style in the editor.") {
    Box(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      PaperPatternPicker(selected = settings.defaultPaper, onSelect = { settings.defaultPaper = it })
    }
  }
}

@Composable
private fun AboutSection(appVersion: String, modelStatus: String, nanoStatus: String) {
  Group(title = "Gemini Math Lab") {
    Text(
      "A handwriting notebook for maths, in the style of Samsung Notes. Write with the S Pen or your finger, and the app reads your handwriting and solves it step by step, right on this device.",
      fontSize = 15.sp, color = S.primaryText,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
    )
    RowDivider()
    InfoRow("Version", appVersion)
  }
  UpdatesGroup()
  Group(title = "Solving") {
    FeatureRow("Solve", "Tap ✦ Solve to read the line you wrote last, or circle any part with the lasso first. It handles equations, inequalities, integrals, limits, sums, functions and piecewise functions, with each step explained.")
    RowDivider()
    FeatureRow("Solve when you write =", "Finish a line with = and the answer appears by itself (Settings › Pen & writing).")
    RowDivider()
    FeatureRow("Explain with Gemini Nano", "On the steps card, Gemini Nano explains the solution in words, offline.")
    RowDivider()
    FeatureRow("Graphs", "Circle a function with the lasso and tap Graph: a picture of its graph goes next to it and is saved to the Gallery.")
    RowDivider()
    FeatureRow("Graphing calculator", "⋮ › Graphing calculator. Type functions with the maths keyboard, as many as you like; drag, pinch or double-tap the graph, tap a curve to read a point, and add the view to your note. Piecewise functions are written like {x<0: −x, x≥0: x}.")
    RowDivider()
    FeatureRow("Quick LaTeX Pad", "From the notes list: write a formula and copy it as LaTeX, or open it as a note.")
  }
  Group(title = "Writing") {
    FeatureRow("Infinite page", "Write anywhere. Pinch to zoom, move with two fingers (or one, with S Pen only on). The lock keeps the page still.")
    RowDivider()
    FeatureRow("Toolbar", "Samsung Notes' tools: pens (fountain, calligraphy, pen, pencil, brush), highlighter, eraser, lasso, tape, laser, shapes, fill and layers. Tap a selected tool again for its settings: pen types and line styles; the highlighter's tip, size and own colour; stroke or area eraser; lasso or rectangle selection; the tape's size, colour and pattern (tap tape on the page to peel it back, and remove or peel all tape from its panel). Customise it with ⁘ at its end, and drag its handle to put it on any side.")
    RowDivider()
    FeatureRow("Colours", "Tap the selected colour to choose any colour, from swatches, a spectrum or a hex code. The pen and highlighter icons show the colour they write in.")
    RowDivider()
    FeatureRow("Stroke stabiliser", "In the pen settings: smooths shaky lines; higher is smoother.")
    RowDivider()
    FeatureRow("Fill and layers", "The bucket fills a closed shape (tap it again for opacity). Layers can be added, hidden, reordered and made see-through; you write on the selected one.")
    RowDivider()
    FeatureRow("Pictures", "Insert one with the picture icon. Long-press it to move, resize, crop, copy, cut, delete or share it.")
    RowDivider()
    FeatureRow("Paper", "Plain, lined, grid, dotted, graph or axes, on white, cream, grey or black (the app goes dark with a black page). Use any picture or PDF page as the background.")
    RowDivider()
    FeatureRow("Import", "Gemini Math notes (.gmnote), Samsung Notes (.sdocx), .iink, PDF and pictures, with the import button in the notes list.")
    RowDivider()
    FeatureRow("Export", "Select notes and tap Export. Files are named after the note and the day it was last changed (Title_2026-11-02.pdf). .gmnote keeps everything, the whole infinite canvas, layers and pictures, and opens again in this app on any device. SVG is a drawing that stays sharp at any size. PDF spreads a big canvas over several pages; PNG is one picture.")
    RowDivider()
    FeatureRow("Bin", "Deleted notes go to the Bin (in the notes list's menu) for ${NoteBin.KEEP_DAYS} days, then they're removed. Restore them from there, or delete them forever. With Drive backup on, a deleted note leaves this device but stays on Google Drive until then, and the other devices move it to their Bin too.")
  }
  Group(title = "Your devices") {
    FeatureRow("S Pen", "Hold the button while writing to erase; press it while hovering for the quick menu.")
    RowDivider()
    FeatureRow("Phone as toolbar", "On the tablet: ⋮ › Use phone as toolbar. The phone opens as its toolbar by itself, even if the app is closed (or shows a notification to tap). Close it with ✕ on the phone.")
    RowDivider()
    FeatureRow("Continue on another device", "With Android's Handoff, a note open here can be picked up on your other devices.")
    RowDivider()
    FeatureRow("Google Drive backup", "Keeps your notes in sync across your Android devices, deletions included (Settings › Account & backup).")
  }
  Group(title = "On-device models") {
    InfoRow("Handwriting recognition", modelStatus)
    RowDivider()
    InfoRow("Gemini Nano explanations", nanoStatus)
  }
  Group(title = "Privacy") {
    Text(
      "Handwriting is recognised and solved on this device. Notes leave the device only when Google Drive backup is on.",
      fontSize = 14.sp, color = S.secondaryText,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
    )
  }
}

/** About › Updates: check GitHub for a new version now, and whether to check by itself. */
@Composable
private fun UpdatesGroup() {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val scope = rememberCoroutineScope()
  var auto by remember { mutableStateOf(AppUpdater.autoCheck(ctx)) }
  val status = when (val s = AppUpdater.state) {
    AppUpdater.State.Checking -> "Checking…"
    AppUpdater.State.UpToDate -> "You have the latest version"
    is AppUpdater.State.Available -> "Version ${s.release.version} is available"
    is AppUpdater.State.Downloading -> "Downloading ${s.release.version}… ${(s.progress * 100).toInt()}%"
    is AppUpdater.State.Installing -> "Installing ${s.release.version}…"
    is AppUpdater.State.Failed -> s.message
    AppUpdater.State.Idle -> "New versions come from github.com/${AppUpdater.REPO}"
  }
  Group(title = "Updates") {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f)) {
        Text("Check for updates", fontSize = 16.sp, color = S.primaryText)
        Text(status, fontSize = 13.sp, color = S.secondaryText)
      }
      val available = AppUpdater.state as? AppUpdater.State.Available
      TextButton(
        enabled = AppUpdater.state !is AppUpdater.State.Checking && AppUpdater.state !is AppUpdater.State.Downloading,
        onClick = { if (available != null) AppUpdater.dialogOpen = true else scope.launch { AppUpdater.check(ctx) } }
      ) { Text(if (available != null) "Update" else "Check") }
    }
    RowDivider()
    SwitchRow("Check automatically", "Once a day when the app opens", checked = auto, onChange = {
      auto = it; AppUpdater.setAutoCheck(ctx, it)
    })
  }
}

/** A feature in About: its name, and a line on how to use it. */
@Composable
private fun FeatureRow(title: String, description: String) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = S.primaryText)
    Spacer(Modifier.height(2.dp))
    Text(description, fontSize = 14.sp, color = S.secondaryText, lineHeight = 19.sp)
  }
}

// ----------------------------------------------------------------------------------------------
// Paper picker and the editor's Page style dialog

@Composable
fun PaperPatternPicker(selected: PaperPattern, onSelect: (PaperPattern) -> Unit, paperColor: PaperColor = PaperColor.WHITE) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    modifier = Modifier.horizontalScroll(rememberScrollState())
  ) {
    PaperPattern.entries.forEach { pat ->
      val isSel = pat == selected
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .clickable(role = Role.RadioButton) { onSelect(pat) }
          .semantics { this.selected = isSel; contentDescription = "${pat.label} paper" }
          .padding(4.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color(paperColor.argb),
          modifier = Modifier
            .size(width = 64.dp, height = 84.dp)
            .border(if (isSel) 2.dp else 1.dp, if (isSel) S.accent else Color(0xFFDADADF), RoundedCornerShape(10.dp))
        ) {
          // Same renderer as the page, zoomed out, with Axes centred in the preview
          Canvas(Modifier.fillMaxSize()) {
            val s = 0.42f
            val origin = if (pat == PaperPattern.AXES)
              Offset(size.width / 2f - PaperDraw.AXES_ORIGIN.x * s, size.height / 2f - PaperDraw.AXES_ORIGIN.y * s)
            else Offset.Zero
            with(PaperDraw) { drawPaper(pat, paperColor, CanvasView(origin, s)) }
          }
        }
        Spacer(Modifier.height(6.dp))
        Text(pat.label, fontSize = 12.sp, color = if (EditorChrome.dark) EditorChrome.text else S.primaryText, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
      }
    }
  }
}

@Composable
private fun PaperColorPicker(selected: PaperColor, onSelect: (PaperColor) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    PaperColor.entries.forEach { c ->
      val isSel = c == selected
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(c.argb))
            .border(if (isSel) 2.5.dp else 1.dp, if (isSel) S.accent else Color(0xFFD0D0D6), CircleShape)
            .clickable(role = Role.RadioButton) { onSelect(c) }
            .semantics { this.selected = isSel; contentDescription = "${c.label} page" },
          contentAlignment = Alignment.Center
        ) {
          if (isSel) MyScriptIcons.Check(if (c.isDark) Color.White else S.accent, 16.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text(c.label, fontSize = 12.sp, color = if (EditorChrome.dark) EditorChrome.secondaryText else S.secondaryText)
      }
    }
  }
}

/** Per-note page options, opened from the editor toolbar. App-wide options live in [SettingsScreen]. */
@Composable
fun PageStyleDialog(
  uiStyle: UiStyleMode,
  paper: PaperPattern,
  isDefault: Boolean,
  onSelectPaper: (PaperPattern) -> Unit,
  onMakeDefault: () -> Unit,
  onOpenSettings: () -> Unit,
  onClose: () -> Unit,
  paperColor: PaperColor = PaperColor.WHITE,
  onSelectColor: (PaperColor) -> Unit = {},
  hasBackground: Boolean = false,
  onPickBackground: () -> Unit = {},
  onRemoveBackground: () -> Unit = {}
) {
  Dialog(onDismissRequest = onClose) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsStyle provides settingsStyleFor(uiStyle)) {
      Surface(shape = RoundedCornerShape(S.cardRadius), color = if (EditorChrome.dark) EditorChrome.menu else Color.White) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
          Text("Page style", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (EditorChrome.dark) EditorChrome.text else S.primaryText)
          Spacer(Modifier.height(4.dp))
          Text("Applies to this note", fontSize = 13.sp, color = if (EditorChrome.dark) EditorChrome.secondaryText else S.secondaryText)
          Spacer(Modifier.height(18.dp))
          Text("Template", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (EditorChrome.dark) EditorChrome.text else S.primaryText)
          Spacer(Modifier.height(8.dp))
          PaperPatternPicker(selected = paper, onSelect = onSelectPaper, paperColor = paperColor)
          Spacer(Modifier.height(18.dp))
          Text("Page colour", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (EditorChrome.dark) EditorChrome.text else S.primaryText)
          Spacer(Modifier.height(8.dp))
          PaperColorPicker(paperColor, onSelectColor)
          Spacer(Modifier.height(18.dp))
          Text("Background", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (EditorChrome.dark) EditorChrome.text else S.primaryText)
          Text("A picture (PNG, JPG, WebP) or a PDF page under your ink", fontSize = 12.sp, color = if (EditorChrome.dark) EditorChrome.secondaryText else S.secondaryText)
          Spacer(Modifier.height(6.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            val btn = ButtonDefaults.textButtonColors(contentColor = S.accent)
            TextButton(onClick = onPickBackground, colors = btn) {
              MyScriptIcons.ImageFrame(S.accent, 16.dp)
              Spacer(Modifier.width(6.dp))
              Text(if (hasBackground) "Change" else "Choose…")
            }
            if (hasBackground) TextButton(onClick = onRemoveBackground, colors = btn) { Text("Remove") }
          }
          Spacer(Modifier.height(12.dp))
          // Two lines, so the buttons fit on a phone too
          val btn = ButtonDefaults.textButtonColors(contentColor = S.accent)
          TextButton(onClick = onMakeDefault, enabled = !isDefault, colors = btn) {
            Text(if (isDefault) "Default for new notes" else "Use for new notes")
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenSettings, colors = btn) { Text("All settings") }
            TextButton(onClick = onClose, colors = btn) { Text("Done") }
          }
        }
      }
    }
  }
}