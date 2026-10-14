package ru.fsociety.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fsociety.vpn.ui.theme.*

// Значки разделов — одним цветом, темнее основного (раньше эмодзи выбивались по цвету и стилю)
private val RulesIconGreen: Color get() = themed(0xFF8DB51E, 0xFF5BA300)
private val RulesIconBg: Color get() = themed(0xFF161C0A, 0xFFE3EDCC)

data class AppInfo(
    val packageName: String,
    val label: String
)

/** Иконки приложений — по мере прокрутки и с кэшем: грузить сотню иконок разом долго. */
private object AppIcons {
    private val cache = android.util.LruCache<String, ImageBitmap>(256)
    fun get(pm: PackageManager, pkg: String, sizePx: Int): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        return try {
            pm.getApplicationIcon(pkg).toBitmap(sizePx, sizePx).asImageBitmap().also { cache.put(pkg, it) }
        } catch (_: Exception) { null }
    }
}

/**
 * Приложения с иконкой на рабочем столе (включая предустановленные — Chrome, YouTube). Без
 * разрешения «видеть все приложения»: в манифесте объявлен только такой запрос (<queries>).
 * Раньше брались все установленные, но без системных, — и на Android 11+ список был почти пуст.
 */
private fun loadLauncherApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { AppInfo(it.packageName, it.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun RulesScreen(
    settings: SplitTunnelingSettings,
    onSettingsChange: (SplitTunnelingSettings) -> Unit
) {
    var currentScreen by remember { mutableStateOf("main") }

    BackHandler(enabled = currentScreen != "main") { currentScreen = "main" }

    when (currentScreen) {
        "apps" -> AppsRulesScreen(settings, onSettingsChange, onBack = { currentScreen = "main" })
        "sites" -> SitesRulesScreen(settings, onSettingsChange, onBack = { currentScreen = "main" })
        else -> RulesMainScreen(settings, onNavigate = { currentScreen = it })
    }
}

@Composable
fun RulesMainScreen(settings: SplitTunnelingSettings, onNavigate: (String) -> Unit) {
    val connected by ConnectionManager.connectedServer
    val applied by ConnectionManager.appliedRules
    val isConnecting by ConnectionManager.isConnecting
    // Подключены протоколом с правилами, а правила с тех пор поменялись — применяются переподключением
    val needsApply = connected != null && applied != null && applied != settings.fingerprint()

    Column(modifier = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.statusBarsPadding().padding(24.dp)) {
            Text(stringResource(R.string.rules_section), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.rules_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        if (needsApply) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Surface).padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.rules_apply_text), color = TextPrimary, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                TextButton(
                    enabled = !isConnecting,
                    onClick = { connected?.let { ConnectionManager.requestConnect(it) } }
                ) {
                    Text(stringResource(R.string.rules_apply_btn), color = Accent, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = Border)
        }

        RulesMenuItem(
            icon = { Text(".com", color = RulesIconGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
            title = stringResource(R.string.rules_sites),
            subtitle = when {
                !settings.sitesEnabled -> stringResource(R.string.rules_off)
                settings.sites.isEmpty() -> stringResource(R.string.rules_list_empty)
                settings.sitesMode == SplitMode.EXCLUDE -> stringResource(R.string.rules_sites_exclude_n, settings.sites.size)
                else -> stringResource(R.string.rules_sites_include_n, settings.sites.size)
            },
            onClick = { onNavigate("sites") }
        )
        RulesMenuItem(
            icon = { Icon(Icons.Filled.Apps, contentDescription = null, tint = RulesIconGreen, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.rules_apps),
            subtitle = when {
                !settings.appsEnabled -> stringResource(R.string.rules_off)
                settings.apps.isEmpty() -> stringResource(R.string.rules_list_empty)
                settings.appsMode == SplitMode.EXCLUDE -> stringResource(R.string.rules_apps_exclude_n, settings.apps.size)
                else -> stringResource(R.string.rules_apps_include_n, settings.apps.size)
            },
            onClick = { onNavigate("apps") }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.rules_support),
            color = Color(0xFFF59E0B), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(R.string.rules_footer),
            color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RulesMenuItem(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(RulesIconBg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Text("›", color = TextMuted, fontSize = 20.sp)
    }
    HorizontalDivider(color = Border)
}

@Composable
private fun RulesHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { onBack() })
        Spacer(modifier = Modifier.width(16.dp))
        Text(title, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = Border)
}

@Composable
private fun RuleToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BgDark, checkedTrackColor = Accent,
                uncheckedThumbColor = TextMuted, uncheckedTrackColor = Surface)
        )
    }
    HorizontalDivider(color = Border)
}

/** Режим как в AdGuard VPN: «через VPN всё, кроме…» / «через VPN только…». */
@Composable
private fun ModeOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 12.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected, onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextMuted)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = if (selected) TextPrimary else TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EmptyListWarning() {
    Text(
        stringResource(R.string.rules_list_empty),
        color = Color(0xFFF59E0B), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
    )
}

@Composable
fun SitesRulesScreen(
    settings: SplitTunnelingSettings,
    onSettingsChange: (SplitTunnelingSettings) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    val strErrorInvalid = stringResource(R.string.rules_sites_error_invalid)
    val strErrorDuplicate = stringResource(R.string.rules_sites_error_duplicate)
    val list = settings.sites

    fun addEntry() {
        val entry = SplitTunnelingManager.normalizeSite(input)
        if (entry == null) { inputError = strErrorInvalid; return }
        if (entry in list) { inputError = strErrorDuplicate; return }
        onSettingsChange(settings.withSites(list + entry))
        input = ""; inputError = ""
        keyboardController?.hide()
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Bg2,
            title = { Text(stringResource(R.string.rules_clear_confirm_title), color = TextPrimary,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onSettingsChange(settings.withSites(emptySet())) }) {
                    Text(stringResource(R.string.rules_clear_confirm_btn), color = ErrorRed, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.home_btn_cancel), color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        RulesHeader(stringResource(R.string.rules_sites_title), onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                RuleToggleRow(stringResource(R.string.rules_sites_toggle), settings.sitesEnabled) {
                    onSettingsChange(settings.copy(sitesEnabled = it))
                }
            }
            if (!settings.sitesEnabled) return@LazyColumn

            item {
                Spacer(modifier = Modifier.height(6.dp))
                ModeOption(
                    stringResource(R.string.rules_mode_exclude_title), stringResource(R.string.rules_mode_exclude_sites_desc),
                    settings.sitesMode == SplitMode.EXCLUDE
                ) { onSettingsChange(settings.copy(sitesMode = SplitMode.EXCLUDE)); inputError = "" }
                ModeOption(
                    stringResource(R.string.rules_mode_include_title), stringResource(R.string.rules_mode_include_sites_desc),
                    settings.sitesMode == SplitMode.INCLUDE
                ) { onSettingsChange(settings.copy(sitesMode = SplitMode.INCLUDE)); inputError = "" }
                if (list.isEmpty()) EmptyListWarning()
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = Border)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; inputError = "" },
                        placeholder = { Text(stringResource(R.string.rules_sites_placeholder),
                            fontSize = 12.sp, color = TextMuted, fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addEntry() }),
                        isError = inputError.isNotEmpty(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { addEntry() },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) { Text("+", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
                Text(
                    if (inputError.isNotEmpty()) inputError else stringResource(R.string.rules_sites_hint),
                    color = if (inputError.isNotEmpty()) ErrorRed else TextMuted,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )
            }

            item {
                Text(stringResource(R.string.rules_presets), color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp))
                // Ряды по 2 кнопки (FlowRow из библиотеки Compose в сборке падал с NoSuchMethodError)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Presets.all.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { preset ->
                                val active = list.containsAll(preset.entries)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            onSettingsChange(settings.withSites(
                                                if (active) list - preset.entries else list + preset.entries))
                                        }
                                        .background(if (active) Accent else Surface, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        (if (active) "✓ " else "+ ") + stringResource(preset.titleRes),
                                        color = if (active) BgDark else Accent,
                                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.rules_awg_proxy_note), color = TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Border)
            }

            if (list.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.rules_list_title, list.size), color = Accent, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.rules_clear), color = TextMuted, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                    HorizontalDivider(color = Border)
                }
                // Сначала сайты, потом IP и подсети; по алфавиту
                val sorted = list.sortedWith(compareBy({ SplitTunnelingManager.isAppRule(it) }, { SplitTunnelingManager.isIpRule(it) }, { SplitTunnelingManager.displaySite(it) }))
                items(sorted, key = { it }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when {
                                SplitTunnelingManager.isAppRule(entry) -> Icons.Outlined.Apps
                                SplitTunnelingManager.isIpRule(entry) -> Icons.Outlined.Lan
                                else -> Icons.Outlined.Language
                            },
                            contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(SplitTunnelingManager.displaySite(entry), color = TextPrimary, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.rules_delete_cd),
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp).clickable { onSettingsChange(settings.withSites(list - entry)) }
                        )
                    }
                    HorizontalDivider(color = Border)
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun AppsRulesScreen(
    settings: SplitTunnelingSettings,
    onSettingsChange: (SplitTunnelingSettings) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var allApps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) { loadLauncherApps(context) }
    }

    val list = settings.apps
    // Выбранные — сверху. Порядок фиксируется при открытии и смене режима, чтобы строка не «убегала»
    // из-под пальца при нажатии
    val ordered = remember(allApps, settings.appsMode) {
        allApps?.sortedBy { if (it.packageName in list) 0 else 1 }
    }
    val displayed = ordered?.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }

    fun toggle(pkg: String) {
        onSettingsChange(settings.withApps(if (pkg in list) list - pkg else list + pkg))
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        RulesHeader(stringResource(R.string.rules_apps_title), onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                RuleToggleRow(stringResource(R.string.rules_apps_toggle), settings.appsEnabled) {
                    onSettingsChange(settings.copy(appsEnabled = it))
                }
            }
            if (!settings.appsEnabled) return@LazyColumn

            item {
                Spacer(modifier = Modifier.height(6.dp))
                ModeOption(
                    stringResource(R.string.rules_mode_exclude_title), stringResource(R.string.rules_mode_exclude_apps_desc),
                    settings.appsMode == SplitMode.EXCLUDE
                ) { onSettingsChange(settings.copy(appsMode = SplitMode.EXCLUDE)) }
                ModeOption(
                    stringResource(R.string.rules_mode_include_title), stringResource(R.string.rules_mode_include_apps_desc),
                    settings.appsMode == SplitMode.INCLUDE
                ) { onSettingsChange(settings.copy(appsMode = SplitMode.INCLUDE)) }
                if (list.isEmpty()) EmptyListWarning()
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = Border)
            }

            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.rules_search_hint), fontSize = 13.sp, color = TextMuted,
                        fontFamily = FontFamily.Monospace) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                )
                Text(stringResource(R.string.rules_apps_note), color = TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                HorizontalDivider(color = Border)
            }

            if (displayed == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                    }
                }
            } else {
                items(displayed, key = { it.packageName }) { app ->
                    AppRow(app, selected = app.packageName in list) { toggle(app.packageName) }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, selected: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 36.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) { AppIcons.get(context.packageManager, app.packageName, sizePx) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(36.dp)) {
            icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextMuted, checkmarkColor = BgDark)
        )
    }
    HorizontalDivider(color = Border)
}
