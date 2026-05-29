package ru.fsociety.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ru.fsociety.vpn.ui.theme.*

private fun policyAssetUrl(name: String, lang: String): String {
    val suffix = if (lang == "en") "_en" else ""
    return "file:///android_asset/${name}${suffix}.html"
}

fun formatNumericCode(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(16)
    return digits.chunked(4).joinToString(" ")
}

private data class AnonResult(val code: String, val accessToken: String, val refreshToken: String)

private fun shareText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
        null
    ))
}

@Composable
fun PolicyViewerScreen(url: String, title: String, onBack: () -> Unit) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val progress = remember { mutableStateOf(0) }

    BackHandler {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark).statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.privacy_screen_back),
                color = Accent, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title, color = TextPrimary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
            )
        }
        if (progress.value < 100) {
            LinearProgressIndicator(
                progress = { progress.value / 100f },
                modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Border
            )
        }
        AndroidView(
            factory = { ctx ->
                val progressCapture = progress
                val webViewCapture = webViewRef
                WebView(ctx).apply {
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progressCapture.value = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: android.webkit.WebResourceRequest?
                        ) = false
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    loadUrl(url)
                    webViewCapture.value = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PrivacyCheckboxRow(
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = accepted, onCheckedChange = onAcceptedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Accent, uncheckedColor = TextMuted, checkmarkColor = BgDark
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.privacy_accept_prefix), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    stringResource(R.string.privacy_policy_link), color = Accent, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onOpenPrivacy() }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.privacy_and_prefix), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    stringResource(R.string.terms_link), color = Accent, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onOpenTerms() }
                )
            }
        }
    }
}

// ─── Экран: код создан ────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnonCodeResultScreen(result: AnonResult, onLogin: (String, String) -> Unit) {
    val context = LocalContext.current
    val formatted = result.code.chunked(4).joinToString(" ")
    val shareText = stringResource(R.string.anon_share_text, formatted)
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun copyCode() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("fsociety code", formatted))
        copied = true
        scope.launch { delay(2000); copied = false }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BgDark).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            Row {
                Text("[f]", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("society", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.anon_code_subtitle), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp)

            Spacer(modifier = Modifier.height(40.dp))

            Text(stringResource(R.string.anon_code_label), color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.2.sp)
            Spacer(modifier = Modifier.height(16.dp))

            // Код — нажать/долго нажать = скопировать
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (copied) Accent else Border)
                    .background(Surface)
                    .combinedClickable(onClick = { copyCode() }, onLongClick = { copyCode() })
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatted,
                        color = TextPrimary,
                        fontSize = 26.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (copied) stringResource(R.string.anon_code_copied) else stringResource(R.string.anon_code_tap_to_copy),
                        color = if (copied) Accent else TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.anon_code_warning), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(28.dp))

            // Поделиться
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Border)
                    .clickable { shareText(context, shareText) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.anon_share_label), color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onLogin(result.accessToken, result.refreshToken) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                Text(stringResource(R.string.anon_code_login_btn), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

// ─── Экран: регистрация анонимного аккаунта ───────────────────────────────────

@Composable
private fun AnonRegisterScreen(
    onBack: () -> Unit,
    onSuccess: (AnonResult) -> Unit,
    onOpenPolicy: (url: String, title: String) -> Unit
) {
    var privacyAccepted by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lang = remember { context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru" }
    val strPrivacyTitle   = stringResource(R.string.privacy_screen_title)
    val strTermsTitle     = stringResource(R.string.terms_screen_title)
    val strErrorNetwork   = stringResource(R.string.login_error_network)
    val strErrorServer    = stringResource(R.string.anon_register_error)
    val strErrorRateLimit = stringResource(R.string.anon_register_error_rate_limit)

    Box(
        modifier = Modifier.fillMaxSize().background(BgDark).imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 80.dp, bottom = 24.dp)
        ) {
            Row {
                Text("[f]", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("society", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.anon_register_subtitle), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp)

            Spacer(modifier = Modifier.height(40.dp))

            Text(stringResource(R.string.anon_register_desc), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(24.dp))

            PrivacyCheckboxRow(
                accepted = privacyAccepted,
                onAcceptedChange = { privacyAccepted = it },
                onOpenPrivacy = { onOpenPolicy(policyAssetUrl("privacy", lang), strPrivacyTitle) },
                onOpenTerms   = { onOpenPolicy(policyAssetUrl("terms",   lang), strTermsTitle) }
            )

            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(errorMsg, color = ErrorRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true; errorMsg = ""
                        try {
                            val resp = ApiClient.service.generateNumericAccount()
                            onSuccess(AnonResult(resp.code, resp.access_token, resp.refresh_token))
                        } catch (e: java.net.SocketTimeoutException) {
                            errorMsg = strErrorNetwork
                        } catch (e: retrofit2.HttpException) {
                            errorMsg = if (e.code() == 429) strErrorRateLimit else strErrorServer
                        } catch (e: Exception) {
                            errorMsg = strErrorServer
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading && privacyAccepted,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                if (isLoading) CircularProgressIndicator(color = BgDark, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.anon_register_btn), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.register_back),
                color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onBack() }
            )
        }
    }
}

// ─── Экран входа ──────────────────────────────────────────────────────────────

@Composable
fun LoginScreen(onLogin: (String, String) -> Unit, onRegister: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isAnonymousMode by remember { mutableStateOf(false) }
    var numericCode by remember { mutableStateOf("") }
    var numericFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var showAnonRegister by remember { mutableStateOf(false) }
    var anonResult by remember { mutableStateOf<AnonResult?>(null) }
    var showPolicyUrl by remember { mutableStateOf<String?>(null) }
    var showPolicyTitle by remember { mutableStateOf("") }
    var showLangMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fsociety", Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(prefs.getString("language", "ru") ?: "ru") }
    val scope = rememberCoroutineScope()

    val strErrorCodeLength   = stringResource(R.string.login_error_code_length)
    val strErrorEmpty        = stringResource(R.string.login_error_empty)
    val strErrorNetwork      = stringResource(R.string.login_error_network)
    val strErrorCodeNotFound = stringResource(R.string.login_error_code_not_found)
    val strErrorCredentials  = stringResource(R.string.login_error_credentials)

    // Оверлей политики конфиденциальности
    if (showPolicyUrl != null) {
        PolicyViewerScreen(url = showPolicyUrl!!, title = showPolicyTitle, onBack = { showPolicyUrl = null })
        return
    }

    // Экран с кодом
    if (anonResult != null) {
        AnonCodeResultScreen(result = anonResult!!, onLogin = onLogin)
        return
    }

    // Экран регистрации анонимного аккаунта
    if (showAnonRegister) {
        AnonRegisterScreen(
            onBack = { showAnonRegister = false },
            onSuccess = { result -> anonResult = result },
            onOpenPolicy = { url, title -> showPolicyUrl = url; showPolicyTitle = title }
        )
        return
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BgDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 72.dp, bottom = 24.dp)
        ) {
            Row {
                Text("[f]", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("society", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(if (isAnonymousMode) R.string.login_subtitle_anon else R.string.login_subtitle_email),
                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            if (!isAnonymousMode) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("EMAIL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true, enabled = !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.login_field_password), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.login_forgot_password), color = TextMuted, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.fillMaxWidth().clickable { }
                )
            } else {
                OutlinedTextField(
                    value = numericFieldValue,
                    onValueChange = { newVal ->
                        val digits = newVal.text.filter { it.isDigit() }.take(16)
                        val formatted = digits.chunked(4).joinToString(" ")
                        numericCode = digits
                        numericFieldValue = TextFieldValue(text = formatted, selection = TextRange(formatted.length))
                    },
                    label = { Text(stringResource(R.string.login_field_code), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    placeholder = { Text("XXXX XXXX XXXX XXXX", fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, enabled = !isLoading,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                        textAlign = TextAlign.Center, color = TextPrimary, letterSpacing = 2.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedLabelColor = Accent, unfocusedLabelColor = TextMuted, cursorColor = Accent)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.login_code_hint), color = TextMuted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }

            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(errorMsg, color = ErrorRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true; errorMsg = ""
                        try {
                            val response = if (isAnonymousMode) {
                                val digits = numericCode.filter { it.isDigit() }
                                if (digits.length != 16) { errorMsg = strErrorCodeLength; return@launch }
                                ApiClient.service.loginNumeric(NumericLoginRequest(digits))
                            } else {
                                if (email.isEmpty() || password.isEmpty()) { errorMsg = strErrorEmpty; return@launch }
                                ApiClient.service.login(email, password)
                            }
                            onLogin(response.access_token, response.refresh_token)
                        } catch (e: java.net.SocketTimeoutException) {
                            errorMsg = strErrorNetwork
                        } catch (e: Exception) {
                            errorMsg = if (isAnonymousMode) strErrorCodeNotFound else strErrorCredentials
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                if (isLoading) CircularProgressIndicator(color = BgDark, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.login_btn), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Ссылка «Зарегистрироваться» — в обоих режимах, но ведёт в разные места
            Text(
                text = stringResource(R.string.login_btn_register),
                color = Accent, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    if (isAnonymousMode) showAnonRegister = true else onRegister()
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = Border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { isAnonymousMode = !isAnonymousMode; errorMsg = "" },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = TextMuted),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Text(
                    stringResource(if (isAnonymousMode) R.string.login_btn_email else R.string.login_btn_anon),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp
                )
            }
        }

        // ── Language picker (top-right) ──
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 16.dp)
        ) {
            Text(
                text = language.uppercase(),
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .border(1.dp, Border)
                    .clickable { showLangMenu = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
            DropdownMenu(
                expanded = showLangMenu,
                onDismissRequest = { showLangMenu = false },
                modifier = Modifier.background(Surface)
            ) {
                listOf("ru" to "RU", "en" to "EN").forEach { (code, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (language == code) Accent else TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        },
                        onClick = {
                            showLangMenu = false
                            if (language != code) {
                                language = code
                                prefs.edit().putString("language", code).apply()
                                (context as? android.app.Activity)?.recreate()
                            }
                        }
                    )
                }
            }
        }
    }
}

// ─── Экран регистрации email-аккаунта ─────────────────────────────────────────

@Composable
fun RegisterScreen(onBack: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var successMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var privacyAccepted by remember { mutableStateOf(false) }
    var showPolicyUrl by remember { mutableStateOf<String?>(null) }
    var showPolicyTitle by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val context2 = LocalContext.current
    val lang2 = remember { context2.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru" }
    val strErrorEmpty    = stringResource(R.string.login_error_empty)
    val strErrorMismatch = stringResource(R.string.register_error_passwords_mismatch)
    val strErrorLength   = stringResource(R.string.register_error_password_length)
    val strErrorServer   = stringResource(R.string.register_error_server)
    val strSuccess       = stringResource(R.string.register_success, email)
    val strPrivacyTitle  = stringResource(R.string.privacy_screen_title)
    val strTermsTitle    = stringResource(R.string.terms_screen_title)

    if (showPolicyUrl != null) {
        PolicyViewerScreen(url = showPolicyUrl!!, title = showPolicyTitle, onBack = { showPolicyUrl = null })
        return
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BgDark).imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 80.dp, bottom = 24.dp)
        ) {
            Row {
                Text("[f]", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("society", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.register_subtitle), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp)

            Spacer(modifier = Modifier.height(40.dp))

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("EMAIL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true, enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.login_field_password), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password2, onValueChange = { password2 = it },
                label = { Text(stringResource(R.string.register_field_password_repeat), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
            )

            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(errorMsg, color = ErrorRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (successMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(successMsg, color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(16.dp))

            PrivacyCheckboxRow(
                accepted = privacyAccepted,
                onAcceptedChange = { privacyAccepted = it },
                onOpenPrivacy = { showPolicyTitle = strPrivacyTitle; showPolicyUrl = policyAssetUrl("privacy", lang2) },
                onOpenTerms   = { showPolicyTitle = strTermsTitle;   showPolicyUrl = policyAssetUrl("terms",   lang2) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (email.isEmpty() || password.isEmpty() || password2.isEmpty()) { errorMsg = strErrorEmpty; return@Button }
                    if (password != password2) { errorMsg = strErrorMismatch; return@Button }
                    if (password.length < 8) { errorMsg = strErrorLength; return@Button }
                    scope.launch {
                        isLoading = true; errorMsg = ""
                        try {
                            ApiClient.service.register(RegisterRequest(email, password))
                            successMsg = strSuccess
                        } catch (e: Exception) {
                            errorMsg = strErrorServer
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading && privacyAccepted,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                if (isLoading) CircularProgressIndicator(color = BgDark, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.login_btn_register), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.register_back),
                color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onBack() }
            )
        }
    }
}
