package ru.fsociety.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fsociety.vpn.ui.theme.*

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
                App()
            }
        }
    }
}

@Composable
fun App() {
    // Состояние авторизации — false = не вошёл, показываем LoginScreen
    var isLoggedIn by remember { mutableStateOf(false) }

    if (isLoggedIn) {
        AppNavigation(onLogout = { isLoggedIn = false })
    } else {
        LoginScreen(onLogin = { isLoggedIn = true })
    }
}

@Composable
fun LoginScreen(onLogin: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

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
            // Логотип
            Row {
                Text(
                    text = "[f]",
                    color = Accent,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "society",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Подзаголовок
            Text(
                text = "// ВХОД В АККАУНТ",
                color = TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.15.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Поле email
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = {
                    Text(
                        "EMAIL",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedLabelColor = Accent,
                    unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Поле пароля
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = {
                    Text(
                        "ПАРОЛЬ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedLabelColor = Accent,
                    unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Забыл пароль
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Забыл пароль",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { /* открыть браузер */ }
                )
            }

            // Ошибка
            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMsg,
                    color = ErrorRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Кнопка входа
            Button(
                onClick = {
                    if (email.isEmpty() || password.isEmpty()) {
                        errorMsg = "Заполните все поля"
                    } else {
                        // TODO: реальный API запрос
                        onLogin()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = BgDark
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                Text(
                    text = "ВОЙТИ →",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Ссылка на поддержку в Telegram
            Text(
                text = "Поддержка в Telegram",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { /* открыть tg */ }
            )
        }
    }
}

@Composable
fun AppNavigation(onLogout: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(
                containerColor = Bg2,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {},
                    label = {
                        Text("ГЛАВНАЯ", fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent,
                        unselectedTextColor = TextMuted,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {},
                    label = {
                        Text("ИСКЛЮЧЕНИЯ", fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent,
                        unselectedTextColor = TextMuted,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {},
                    label = {
                        Text("НАСТРОЙКИ", fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent,
                        unselectedTextColor = TextMuted,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> HomeScreen()
                1 -> ExclusionsScreen()
                2 -> SettingsScreen(onLogout = onLogout)
            }
        }
    }
}

@Composable
fun HomeScreen() {
    var isConnected by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf("Финляндия — Хельсинки") }

    val servers = listOf(
        Server("Финляндия — Хельсинки", "Finland", "🇫🇮", true),
        Server("Швейцария — Цуг", "Switzerland", "🇨🇭", true),
        Server("Москва — Россия", "Russia", "🇷🇺", true),
        Server("Германия — Франкфурт", "Germany", "🇩🇪", false),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // Верхняя часть
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Статус
            Text(
                text = if (isConnected) "● ПОДКЛЮЧЁН" else "○ ОТКЛЮЧЁН",
                color = if (isConnected) Accent else TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Текущий сервер
            Text(
                text = selectedServer,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Кнопка
            Button(
                onClick = { isConnected = !isConnected },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) ErrorRed else Accent,
                    contentColor = BgDark
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                Text(
                    text = if (isConnected) "ОТКЛЮЧИТЬСЯ" else "ПОДКЛЮЧИТЬСЯ",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Divider(color = Border, thickness = 1.dp)

        // Заголовок серверов
        Text(
            text = "// СЕРВЕРЫ",
            color = Accent,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.15.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )

        // Список серверов
        LazyColumn {
            items(servers) { server ->
                ServerRow(
                    server = server,
                    isSelected = selectedServer == server.name,
                    onClick = {
                        if (server.isActive) {
                            selectedServer = server.name
                            isConnected = false
                        }
                    }
                )
                Divider(color = Border, thickness = 1.dp)
            }
        }
    }
}

@Composable
fun ServerRow(server: Server, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = server.isActive) { onClick() }
            .background(if (isSelected) Surface else BgDark)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = server.flag, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = server.name,
            color = if (server.isActive) TextPrimary else TextMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (server.isActive) "● ONLINE" else "СКОРО",
            color = if (server.isActive) Accent else TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ExclusionsScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(BgDark),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "// ИСКЛЮЧЕНИЯ",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Раздельное туннелирование\nбудет доступно скоро",
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(24.dp)
            .padding(top = 48.dp)
    ) {
        Text(
            text = "// НАСТРОЙКИ",
            color = Accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Аккаунт.",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        SettingsRow(label = "Email", value = "user@example.com")
        SettingsRow(label = "Подписка", value = "Нет активной подписки")
        SettingsRow(label = "Версия", value = "1.0.0")

        Spacer(modifier = Modifier.height(32.dp))

        // Кнопка выхода
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = ErrorRed
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed)
        ) {
            Text(
                text = "ВЫЙТИ →",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
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
        Text(
            text = label.uppercase(),
            color = TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}