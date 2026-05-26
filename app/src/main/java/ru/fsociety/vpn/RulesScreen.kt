package ru.fsociety.vpn

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fsociety.vpn.ui.theme.*

data class AppInfo(
    val packageName: String,
    val label: String
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RulesScreen(
    settings: SplitTunnelingSettings,
    onSettingsChange: (SplitTunnelingSettings) -> Unit
) {
    var currentScreen by remember { mutableStateOf("main") }

    BackHandler(enabled = currentScreen != "main") { currentScreen = "main" }

    when (currentScreen) {
        "apps" -> AppsRulesScreen(
            settings = settings,
            onSettingsChange = onSettingsChange,
            onBack = { currentScreen = "main" }
        )
        "sites" -> SitesRulesScreen(
            settings = settings,
            onSettingsChange = onSettingsChange,
            onBack = { currentScreen = "main" }
        )
        else -> RulesMainScreen(
            settings = settings,
            onNavigate = { currentScreen = it }
        )
    }
}

@Composable
fun RulesMainScreen(settings: SplitTunnelingSettings, onNavigate: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Column(modifier = Modifier.padding(24.dp).padding(top = 48.dp)) {
            Text(stringResource(R.string.rules_section), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.rules_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().clickable { /* в разработке */ }
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp)
                    .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) { Text("📱", fontSize = 18.sp) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.rules_apps), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.rules_in_progress), color = TextMuted, fontSize = 12.sp)
            }
            Text("›", color = TextMuted, fontSize = 20.sp)
        }
        HorizontalDivider(color = Border)

        Row(
            modifier = Modifier.fillMaxWidth().clickable { onNavigate("sites") }
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp)
                    .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) { Text("🌐", fontSize = 18.sp) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.rules_sites), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    color = TextMuted, fontSize = 12.sp,
                    text = when {
                        !settings.sitesEnabled -> stringResource(R.string.rules_sites_off)
                        settings.selectedDomains.isEmpty() -> stringResource(R.string.rules_sites_none)
                        settings.sitesMode == SplitMode.EXCLUDE ->
                            stringResource(R.string.rules_sites_exclude, settings.selectedDomains.size)
                        else -> stringResource(R.string.rules_sites_include, settings.selectedDomains.size)
                    }
                )
            }
            Text("›", color = TextMuted, fontSize = 20.sp)
        }
        HorizontalDivider(color = Border)

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.rules_footer),
            color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
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
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { AppInfo(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
        isLoading = false
    }

    val displayed = if (searchQuery.isBlank()) allApps
    else allApps.filter { it.label.contains(searchQuery, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.rules_apps_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { onSettingsChange(settings.copy(appsEnabled = !settings.appsEnabled)) }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.rules_apps_toggle), color = TextPrimary, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            Switch(
                checked = settings.appsEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(appsEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BgDark, checkedTrackColor = Accent,
                    uncheckedThumbColor = TextMuted, uncheckedTrackColor = Surface)
            )
        }
        HorizontalDivider(color = Border)

        if (settings.appsEnabled) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeChip(stringResource(R.string.rules_mode_exclude), settings.appsMode == SplitMode.EXCLUDE) {
                    onSettingsChange(settings.copy(appsMode = SplitMode.EXCLUDE))
                }
                ModeChip(stringResource(R.string.rules_mode_include), settings.appsMode == SplitMode.INCLUDE) {
                    onSettingsChange(settings.copy(appsMode = SplitMode.INCLUDE))
                }
            }
            HorizontalDivider(color = Border)

            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.rules_search_hint), fontSize = 13.sp, color = TextMuted,
                    fontFamily = FontFamily.Monospace) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(displayed) { app ->
                        val selected = settings.selectedApps.contains(app.packageName)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    val newSet = if (selected)
                                        settings.selectedApps - app.packageName
                                    else settings.selectedApps + app.packageName
                                    onSettingsChange(settings.copy(selectedApps = newSet))
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, color = TextPrimary, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, color = TextMuted, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(
                                checked = selected,
                                onCheckedChange = {
                                    val newSet = if (selected)
                                        settings.selectedApps - app.packageName
                                    else settings.selectedApps + app.packageName
                                    onSettingsChange(settings.copy(selectedApps = newSet))
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Accent, uncheckedColor = TextMuted, checkmarkColor = BgDark)
                            )
                        }
                        HorizontalDivider(color = Border)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.rules_enable_hint),
                    color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun SitesRulesScreen(
    settings: SplitTunnelingSettings,
    onSettingsChange: (SplitTunnelingSettings) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var inputUrl by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    val strErrorInvalid   = stringResource(R.string.rules_sites_error_invalid)
    val strErrorDuplicate = stringResource(R.string.rules_sites_error_duplicate)

    fun addDomain() {
        val domain = SplitTunnelingManager.extractDomain(inputUrl)
        if (domain.isBlank() || !domain.contains(".")) {
            inputError = strErrorInvalid; return
        }
        if (settings.selectedDomains.contains(domain)) {
            inputError = strErrorDuplicate; return
        }
        onSettingsChange(settings.copy(selectedDomains = settings.selectedDomains + domain))
        inputUrl = ""; inputError = ""
        keyboardController?.hide()
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.rules_sites_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { onSettingsChange(settings.copy(sitesEnabled = !settings.sitesEnabled)) }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.rules_sites_toggle), color = TextPrimary, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            Switch(
                checked = settings.sitesEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(sitesEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BgDark, checkedTrackColor = Accent,
                    uncheckedThumbColor = TextMuted, uncheckedTrackColor = Surface)
            )
        }
        HorizontalDivider(color = Border)

        if (settings.sitesEnabled) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeChip(stringResource(R.string.rules_mode_exclude), settings.sitesMode == SplitMode.EXCLUDE) {
                    onSettingsChange(settings.copy(sitesMode = SplitMode.EXCLUDE))
                }
                ModeChip(stringResource(R.string.rules_mode_include), settings.sitesMode == SplitMode.INCLUDE) {
                    onSettingsChange(settings.copy(sitesMode = SplitMode.INCLUDE))
                }
            }

            Text(
                stringResource(if (settings.sitesMode == SplitMode.EXCLUDE) R.string.rules_vpn_except else R.string.rules_vpn_only),
                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Border)

            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.rules_presets), color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ruEssentialActive = settings.selectedDomains.containsAll(Presets.ruEssential)
                    Box(
                        modifier = Modifier
                            .clickable {
                                val newDomains = if (ruEssentialActive)
                                    settings.selectedDomains - Presets.ruEssential
                                else
                                    settings.selectedDomains + Presets.ruEssential
                                onSettingsChange(settings.copy(selectedDomains = newDomains))
                            }
                            .background(
                                if (ruEssentialActive) Accent else Surface,
                                androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (ruEssentialActive) "✓ RU Essential" else "+ RU Essential",
                            color = if (ruEssentialActive) BgDark else Accent,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.rules_zone_ru_soon), color = TextMuted, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
            HorizontalDivider(color = Border)

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it; inputError = "" },
                    placeholder = { Text(stringResource(R.string.rules_sites_placeholder),
                        fontSize = 12.sp, color = TextMuted, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addDomain() }),
                    isError = inputError.isNotEmpty(),
                    supportingText = if (inputError.isNotEmpty()) {
                        { Text(inputError, color = ErrorRed, fontSize = 10.sp) }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { addDomain() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) { Text("+", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }

            HorizontalDivider(color = Border)

            if (settings.selectedDomains.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.rules_sites_empty), color = TextMuted,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(settings.selectedDomains.sorted()) { domain ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🌐", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(domain, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.rules_delete_cd),
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp).clickable {
                                    onSettingsChange(settings.copy(selectedDomains = settings.selectedDomains - domain))
                                }
                            )
                        }
                        HorizontalDivider(color = Border)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.rules_enable_hint),
                    color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .background(
                if (selected) Accent else Surface,
                androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) BgDark else TextMuted,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
