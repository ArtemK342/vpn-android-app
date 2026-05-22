package ru.fsociety.vpn

import kotlinx.coroutines.launch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fsociety.vpn.ui.theme.*

@Composable
fun LoginScreen(onLogin: (String, String) -> Unit, onRegister: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(24.dp),
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

            Text("// ВХОД В АККАУНТ", color = TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp)

            Spacer(modifier = Modifier.height(40.dp))

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("EMAIL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("ПАРОЛЬ", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Забыл пароль?",
                color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.fillMaxWidth().clickable { }
            )

            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(errorMsg, color = ErrorRed, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (email.isEmpty() || password.isEmpty()) {
                        errorMsg = "Заполните все поля"
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        errorMsg = ""
                        try {
                            val response = ApiClient.service.login(email, password)
                            onLogin(response.access_token, response.refresh_token)
                        } catch (e: java.net.SocketTimeoutException) {
                            errorMsg = "Ошибка сети, попробуйте ещё раз"
                        } catch (e: Exception) {
                            errorMsg = "Неверный email или пароль"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = BgDark,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("ВОЙТИ →", fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Поддержка в Telegram",
                color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { }
            )

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = Border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(24.dp))

            Text("Нет аккаунта?", color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRegister,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent, contentColor = TextPrimary),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Text("ЗАРЕГИСТРИРОВАТЬСЯ →", fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding(),
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

            Text("// РЕГИСТРАЦИЯ", color = TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp)

            Spacer(modifier = Modifier.height(40.dp))

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("EMAIL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("ПАРОЛЬ", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password2, onValueChange = { password2 = it },
                label = { Text("ПОВТОРИТЕ ПАРОЛЬ", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(errorMsg, color = ErrorRed, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }

            if (successMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(successMsg, color = Accent, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (email.isEmpty() || password.isEmpty() || password2.isEmpty()) {
                        errorMsg = "Заполните все поля"
                        return@Button
                    }
                    if (password != password2) {
                        errorMsg = "Пароли не совпадают"
                        return@Button
                    }
                    if (password.length < 8) {
                        errorMsg = "Пароль минимум 8 символов"
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        errorMsg = ""
                        try {
                            ApiClient.service.register(RegisterRequest(email, password))
                            successMsg = "Письмо отправлено на $email — подтвердите аккаунт"
                        } catch (e: Exception) {
                            errorMsg = "Email уже зарегистрирован или ошибка сервера"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = BgDark,
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("ЗАРЕГИСТРИРОВАТЬСЯ →", fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "← Уже есть аккаунт? Войти",
                color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onBack() }
            )
        }
    }
}
