package ru.fsociety.vpn

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.fsociety.vpn.ui.theme.*

/**
 * «Белый список №3»: вход в Mail.ru во встроенном браузере (юзер 2026-11-16). Mail.ru открывает публичный
 * документ на редактирование только залогиненным (401), поэтому клиенту OpenFlux нужны cookies входа.
 * Как только открылось Облако с сессией — cookies cloud.mail.ru сохраняются (WhitelistLinks) и окно закрывается.
 */
private const val CLOUD = "https://cloud.mail.ru/"

private fun grabSession(): String? {
    val raw = CookieManager.getInstance().getCookie(CLOUD) ?: return null
    return raw.takeIf { WhitelistLinks.isMailruSession(WhitelistLinks.parseCookies(it)) }
}

/** Выход: забыть сохранённые cookies и стереть cookies mail.ru из WebView. */
fun mailruLogout(context: android.content.Context) {
    WhitelistLinks.saveMailruCookies(context, null)
    val cm = CookieManager.getInstance()
    for (u in listOf(CLOUD, "https://mail.ru/", "https://auth.mail.ru/", "https://account.mail.ru/")) {
        val raw = cm.getCookie(u) ?: continue
        WhitelistLinks.parseCookies(raw).keys.forEach { name ->
            cm.setCookie(u, "$name=; Max-Age=0; Path=/")
            cm.setCookie(u, "$name=; Max-Age=0; Path=/; Domain=.mail.ru")
        }
    }
    cm.flush()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MailruLoginDialog(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    val done = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun tryFinish(ctx: android.content.Context): Boolean {
        if (done.get()) return true
        val s = grabSession() ?: return false
        if (!done.compareAndSet(false, true)) return true
        WhitelistLinks.saveMailruCookies(ctx, s)
        CookieManager.getInstance().flush()
        onLoggedIn()
        return true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
            Row(modifier = Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("✕", color = Accent, fontSize = 18.sp, modifier = Modifier.clickable { onDismiss() }.padding(end = 16.dp))
                Text(stringResource(R.string.wl_mailru_login_wait), color = TextMuted, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.padding(start = 8.dp))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Text(stringResource(R.string.wl_mailru_login_done), color = Accent, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { tryFinish(ctx) }.padding(6.dp))
            }
            HorizontalDivider(color = Border)
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null && url.startsWith(CLOUD)) tryFinish(ctx)
                        }
                    }
                    loadUrl(CLOUD + "home/")
                }
            })
        }
    }
}
