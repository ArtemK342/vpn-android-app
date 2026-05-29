package ru.fsociety.vpn

import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

fun formatNumericCode(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(16)
    return digits.chunked(4).joinToString(" ")
}

@Composable
fun PolicyViewerScreen(url: String, title: String, onBack: () -> Unit) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val progress = remember { mutableStateOf(0) }

    BackHandler {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.privacy_screen_back),
                color = Accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
        if (progress.value < 100) {
            LinearProgressIndicator(
                progress = { progress.value / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = Border
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
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = accepted,
            onCheckedChange = onAcceptedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Accent,
                uncheckedColor = TextMuted,
                checkmarkColor = BgDark
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.privacy_accept_prefix),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.privacy_policy_link),
                    color = Accent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onOpenPrivacy() }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.privacy_and_prefix),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.terms_link),
                    color = Accent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onOpenTerms() }
                )
            }
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String, String) -> Unit, onRegister: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isAnonymousMode by remember { mutableStateOf(false) }
    var numericCode by remember { mutableStateOf("") }
    var numericFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()

    val strErrorCodeLength   = stringResource(R.string.login_error_code_length)
    val strErrorEmpty        = stringResource(R.string.login_error_empty)
    val strErrorNetwork      = stringResource(R.string.login_error_network)
    val strErrorCodeNotFound = stringResource(R.string.login_error_code_not_found)
    val strErrorCredentials  = stringResource(R.string.login_error_credentials)

    Box(
        modifier = Modifier.fillMaxSize().background(BgDark).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(top = 48.dp)
        ) {
            Row {
                Text("[f]", color = Accent, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("society", color = TextPrimary, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                stringResource(if (isAnonymousMode) R.string.login_subtitle_anon else R.string.login_subtitle_email),
                color = TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp
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
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Accent)
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
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Accent)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.login_forgot_password), color = TextMuted, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.fillMaxWidth().clickable { })
            } else {
                OutlinedTextField(
                    value = numericFieldValue,
                    onValueChange = { newVal ->
                        val digits = newVal.text.filter { it.isDigit() }.take(16)
                        val formatted = digits.chunked(4).joinToString(" ")
                        numericCode = digits
                        numericFieldValue = TextFieldValue(
                            text = formatted,
                            selection = TextRange(formatted.length)
                        )
                    },
                    label = { Text(stringResource(R.string.login_field_code), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    placeholder = { Text("XXXX XXXX XXXX XXXX",
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, enabled = !isLoading,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        color = TextPrimary,
                        letterSpacing = 2.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                        cursorColor = Accent)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.login_code_hint),
                    color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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

            Text(
                text = stringResource(R.string.login_telegram_support),
                color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { }
            )

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = Border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    isAnonymousMode = !isAnonymousMode
                    errorMsg = ""
                },
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

            if (!isAnonymousMode) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.login_no_account), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRegister,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = TextPrimary),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Text(stringResource(R.string.login_btn_register), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

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

    val strErrorEmpty    = stringResource(R.string.login_error_empty)
    val strErrorMismatch = stringResource(R.string.register_error_passwords_mismatch)
    val strErrorLength   = stringResource(R.string.register_error_password_length)
    val strErrorServer   = stringResource(R.string.register_error_server)
    val strSuccess       = stringResource(R.string.register_success, email)
    val strPrivacyTitle  = stringResource(R.string.privacy_screen_title)
    val strTermsTitle    = stringResource(R.string.terms_screen_title)

    if (showPolicyUrl != null) {
        PolicyViewerScreen(
            url = showPolicyUrl!!,
            title = showPolicyTitle,
            onBack = { showPolicyUrl = null }
        )
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
                Text("[f]", color = Accent, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("society", color = TextPrimary, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(stringResource(R.string.register_subtitle), color = TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp)

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
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent)
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
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent)
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
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent)
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
                onOpenPrivacy = { showPolicyTitle = strPrivacyTitle; showPolicyUrl = "file:///android_asset/privacy.html" },
                onOpenTerms   = { showPolicyTitle = strTermsTitle;   showPolicyUrl = "file:///android_asset/terms.html" }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (email.isEmpty() || password.isEmpty() || password2.isEmpty()) {
                        errorMsg = strErrorEmpty; return@Button
                    }
                    if (password != password2) {
                        errorMsg = strErrorMismatch; return@Button
                    }
                    if (password.length < 8) {
                        errorMsg = strErrorLength; return@Button
                    }
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
                if (isLoading) {
                    CircularProgressIndicator(color = BgDark, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.login_btn_register), fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
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
