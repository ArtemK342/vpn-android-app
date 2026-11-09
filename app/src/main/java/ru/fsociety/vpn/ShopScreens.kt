package ru.fsociety.vpn

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.fsociety.vpn.ui.theme.*

/*
 * Подписка и история оплат (юзер 2026-11-09, вместо заглушек «в разработке»).
 * Купить «Месяц» за звёзды: заказ на этот аккаунт (POST /shop/stars-order) → бот @fsocietynet_agent с готовым
 * счётом → подписка включается сама. Код FS-XXXX-XXXX (оплата в боте или в Tribute) — POST /promo/redeem.
 */

private val STAFF = setOf("tester", "developer", "admin", "superadmin")

private fun shortDate(iso: String?): String =
    iso?.take(10)?.split("-")?.let { if (it.size == 3) "${it[2]}.${it[1]}.${it[0]}" else iso } ?: ""

@Composable
private fun ShopHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { onBack() })
        Spacer(modifier = Modifier.width(16.dp))
        Text(title, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = Border)
}

@Composable
fun SubscriptionTab(token: String, user: UserResponse?, subscription: SubscriptionResponse?,
                    onBack: () -> Unit, onChanged: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val staff = user?.role in STAFF
    val active = subscription?.is_active == true
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }   // текст, ошибка?
    val account = if (user?.account_type == "numeric") stringResource(R.string.shop_anon_account) else (user?.email ?: "")
    val strDone = stringResource(R.string.shop_redeem_ok)
    val strSold = stringResource(R.string.shop_sold_out)

    Column(modifier = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
        ShopHeader(stringResource(R.string.settings_subscription), onBack)
        Column(modifier = Modifier.padding(12.dp)) {
            // Статус
            Column(modifier = Modifier.fillMaxWidth().card().padding(18.dp)) {
                SectionLabel(stringResource(R.string.shop_status_label))
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        staff -> stringResource(R.string.shop_status_staff)
                        active -> stringResource(R.string.shop_status_active, shortDate(subscription?.expires_at))
                        else -> stringResource(R.string.shop_status_free)
                    },
                    color = if (active || staff) Accent else TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            // Купить за звёзды
            if (!staff) {
                Column(modifier = Modifier.fillMaxWidth().card().padding(18.dp)) {
                    SectionLabel(stringResource(R.string.shop_buy_label))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.shop_buy_text), color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            busy = true; message = null
                            scope.launch {
                                try {
                                    val r = ApiClient.service.starsOrder("Bearer $token")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                                } catch (e: retrofit2.HttpException) {
                                    message = (if (e.code() == 409) strSold else AppErrors.text(context, e)) to true
                                } catch (e: Exception) {
                                    message = AppErrors.text(context, e) to true
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentFill, contentColor = OnAccent),
                        shape = BtnShape
                    ) { Text("⭐ " + stringResource(R.string.shop_buy_stars), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
                Spacer(Modifier.height(12.dp))
            }
            // Активировать код
            Column(modifier = Modifier.fillMaxWidth().card().padding(18.dp)) {
                SectionLabel(stringResource(R.string.shop_code_label))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.shop_code_text, account), color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase().take(16) },
                    placeholder = { Text("FS-XXXX-XXXX", fontFamily = FontFamily.Monospace, color = TextDim) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent)
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (code.isBlank()) return@Button
                        busy = true; message = null
                        scope.launch {
                            try {
                                val r = ApiClient.service.redeemCode("Bearer $token", RedeemRequest(code.trim()))
                                message = String.format(strDone, shortDate(r.expires_at)) to false
                                code = ""
                                onChanged()
                            } catch (e: retrofit2.HttpException) {
                                message = AppErrors.text(context, e) to true
                            } catch (e: Exception) {
                                message = AppErrors.text(context, e) to true
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy && code.isNotBlank() && !staff,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = TextPrimary),
                    shape = BtnShape
                ) { Text(stringResource(R.string.shop_code_btn), fontWeight = FontWeight.SemiBold) }
            }
            message?.let { (text, err) ->
                Spacer(Modifier.height(12.dp))
                Text(text, color = if (err) ErrorRed else Accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

@Composable
fun PaymentsTab(token: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<PaymentItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        try { items = ApiClient.service.paymentsHistory("Bearer $token") }
        catch (e: Exception) { AppErrors.log("payments history", e); failed = true }
    }
    Column(modifier = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
        ShopHeader(stringResource(R.string.settings_payments), onBack)
        Column(modifier = Modifier.padding(12.dp)) {
            when {
                items == null && !failed -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                }
                items.isNullOrEmpty() -> Text(
                    stringResource(if (failed) R.string.shop_payments_failed else R.string.shop_payments_empty),
                    color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(12.dp)
                )
                else -> Column(modifier = Modifier.fillMaxWidth().card().padding(vertical = 6.dp)) {
                    items!!.forEach { p ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.shop_plan_month), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("${shortDate(p.created_at)} · ${when (p.method) {
                                    "telegram_stars" -> stringResource(R.string.shop_method_stars)
                                    "tribute" -> "Tribute"
                                    else -> p.method ?: ""
                                }}${if (p.refunded) " · " + stringResource(R.string.shop_refunded) else ""}",
                                    color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text(formatAmount(p.amount, p.currency), color = TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

private fun formatAmount(amount: Double?, currency: String?): String {
    if (amount == null) return ""
    val n = if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format(java.util.Locale.US, "%.2f", amount)
    return when (currency) {
        "XTR" -> "$n ⭐"
        "RUB" -> "$n ₽"
        "EUR" -> "€$n"
        "USD" -> "$$n"
        else -> "$n ${currency ?: ""}"
    }
}
