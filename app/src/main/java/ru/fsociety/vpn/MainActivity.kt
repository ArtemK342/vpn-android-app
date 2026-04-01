package ru.fsociety.vpn

import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fsociety.vpn.ui.theme.*

// Модель сервера
data class Server(
    val name: String,
    val country: String,
    val flag: String,
    val isActive: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VpnappTheme {
                App(context = this)
            }
        }
    }
}

@Composable
fun App(context: android.content.Context) {
    // Загружаем сохранённый токен
    val prefs = context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }

    if (token.isNotEmpty()) {
        AppNavigation(
            token = token,
            onLogout = {
                // Удаляем токен при выходе
                prefs.edit().remove("token").apply()
                token = ""
            }
        )
    } else {
        LoginScreen(onLogin = {
            // Сохраняем токен
            prefs.edit().putString("token", it).apply()
            token = it
        })
    }
}


@Composable
fun LoginScreen(onLogin: (String) -> Unit) {
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
            modifier = Modifier.fillMaxWidth()
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
                    // Реальный API запрос
                    scope.launch {
                        isLoading = true
                        errorMsg = ""
                        try {
                            val response = ApiClient.service.login(email, password)
                            onLogin(response.access_token)
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
                onClick = { },
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
fun AppNavigation(token: String, onLogout: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(containerColor = Bg2, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = {},
                    label = { Text("ГЛАВНАЯ", fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = {},
                    label = { Text("ПРАВИЛА", fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
                NavigationBarItem(
                    selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    icon = {},
                    label = { Text("НАСТРОЙКИ", fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> HomeScreen(token = token)
                1 -> RulesScreen()
                2 -> SettingsScreen(token = token, onLogout = onLogout)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(token: String) {
    var isConnected by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var favoriteServers by remember { mutableStateOf(setOf<String>()) }
    var showFavoriteDialog by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf<List<ServerResponse>>(emptyList()) }
    var isLoadingServers by remember { mutableStateOf(true) }

    // Загружаем серверы из API при входе
    LaunchedEffect(token) {
        try {
            val result = ApiClient.service.getServers("Bearer $token")
            servers = result
            selectedServer = result.firstOrNull { it.is_active }?.name ?: ""
        } catch (e: Exception) {
            // ошибка загрузки
        } finally {
            isLoadingServers = false
        }
    }

    val displayedServers = if (selectedTab == 0) servers
    else servers.filter { favoriteServers.contains(it.name) }

    // Диалог избранного
    showFavoriteDialog?.let { serverName ->
        AlertDialog(
            onDismissRequest = { showFavoriteDialog = null },
            containerColor = Bg2,
            title = {
                Text(serverName, color = TextPrimary, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    if (favoriteServers.contains(serverName)) "Убрать из избранного?"
                    else "Добавить в избранное?",
                    color = TextMuted, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    favoriteServers = if (favoriteServers.contains(serverName))
                        favoriteServers - serverName
                    else favoriteServers + serverName
                    showFavoriteDialog = null
                }) {
                    Text(
                        if (favoriteServers.contains(serverName)) "УБРАТЬ" else "ДОБАВИТЬ",
                        color = Accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFavoriteDialog = null }) {
                    Text("ОТМЕНА", color = TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(BgDark)
    ) {
        // Картинка — заглушка
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Bg2),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("[f]", color = Accent, fontSize = 48.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("// TODO: маска Гая Фокса", color = TextDim,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        HorizontalDivider(color = Border, thickness = 1.dp)

        // Вкладки + статус
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ВСЕ",
                color = if (selectedTab == 0) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { selectedTab = 0 })
            Text("ИЗБРАННЫЕ",
                color = if (selectedTab == 1) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { selectedTab = 1 })
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (isConnected) "● $selectedServer" else "○ Отключён",
                color = if (isConnected) Accent else TextMuted,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }

        HorizontalDivider(color = Border, thickness = 1.dp)

        // Загрузка или список серверов
        if (isLoadingServers) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
            }
        } else if (displayedServers.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center) {
                Text(
                    text = if (selectedTab == 1) "Удерживайте сервер\nчтобы добавить в избранное"
                    else "Нет доступных серверов",
                    color = TextMuted, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(displayedServers) { server ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (server.is_active) {
                                        selectedServer = server.name
                                        isConnected = false
                                    }
                                },
                                onLongClick = { showFavoriteDialog = server.name }
                            )
                            .background(if (selectedServer == server.name) Surface else BgDark)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Флаг по стране
                        Text(
                            text = when (server.country) {
                                "Finland" -> "🇫🇮"
                                "Switzerland" -> "🇨🇭"
                                "Russia" -> "🇷🇺"
                                "Germany" -> "🇩🇪"
                                "Netherlands" -> "🇳🇱"
                                else -> "🌍"
                            },
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = server.name,
                                color = if (server.is_active) TextPrimary else TextMuted,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold
                            )
                            if (favoriteServers.contains(server.name)) {
                                Text("★ избранное", color = Accent,
                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text(
                            text = if (server.is_active) "● ONLINE" else "СКОРО",
                            color = if (server.is_active) Accent else TextMuted,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider(color = Border, thickness = 1.dp)
                }
            }
        }

        // Кнопка подключения
        HorizontalDivider(color = Border, thickness = 1.dp)
        Button(
            onClick = { isConnected = !isConnected },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) ErrorRed else Accent,
                contentColor = BgDark
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
        ) {
            Text(
                text = if (isConnected) "● ОТКЛЮЧИТЬСЯ" else "○ ПОДКЛЮЧИТЬСЯ",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
        }
    }
}



@Composable
fun RulesScreen() {
    var selectedTab by remember { mutableStateOf(0) } // 0=Приложения, 1=Сайты

    Column(
        modifier = Modifier.fillMaxSize().background(BgDark)
    ) {
        // Заголовок
        Column(modifier = Modifier.padding(24.dp).padding(top = 48.dp)) {
            Text("// ПРАВИЛА", color = Accent, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Раздельный туннель.", color = TextPrimary,
                fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        // Вкладки Приложения / Сайты
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "ПРИЛОЖЕНИЯ",
                color = if (selectedTab == 0) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { selectedTab = 0 }
            )
            Text(
                text = "САЙТЫ",
                color = if (selectedTab == 1) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { selectedTab = 1 }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = Border, thickness = 1.dp)

        // Заглушка
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selectedTab == 0)
                    "Раздельное туннелирование\nдля приложений будет скоро"
                else "Исключения для сайтов\nбудут скоро",
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
fun SettingsScreen(token: String, onLogout: () -> Unit) {
    var user by remember { mutableStateOf<UserResponse?>(null) }
    var subscription by remember { mutableStateOf<SubscriptionResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Загружаем данные пользователя и подписки
    LaunchedEffect(token) {
        try {
            user = ApiClient.service.getMe("Bearer $token")
            subscription = ApiClient.service.getSubscription("Bearer $token")
        } catch (e: Exception) {
            // ошибка загрузки
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(24.dp)
            .padding(top = 48.dp)
    ) {
        Text("// НАСТРОЙКИ", color = Accent, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Аккаунт.", color = TextPrimary, fontSize = 28.sp,
            fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(32.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
            }
        } else {
            // Email
            SettingsRow(
                label = "Email",
                value = user?.email ?: "—"
            )

            // Подписка
            SettingsRow(
                label = "Подписка",
                value = if (subscription?.is_active == true)
                    "${subscription?.plan} · до ${subscription?.expires_at?.take(10)}"
                else "Нет активной подписки"
            )

            // Роль (показываем только если не user)
            if (user?.role != "user" && user?.role != null) {
                SettingsRow(label = "Роль", value = user?.role ?: "—")
            }

            // Версия
            SettingsRow(label = "Версия", value = "1.0.0")

            Spacer(modifier = Modifier.height(32.dp))

            // Кнопка выхода
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent, contentColor = ErrorRed),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed)
            ) {
                Text("ВЫЙТИ →", fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}



@Composable
fun SettingsRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border)
            .padding(16.dp)
    ) {
        Text(label.uppercase(), color = TextMuted, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, color = TextPrimary, fontSize = 14.sp,
            fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(8.dp))
}