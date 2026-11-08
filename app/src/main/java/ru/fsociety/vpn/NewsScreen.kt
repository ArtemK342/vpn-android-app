package ru.fsociety.vpn

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fsociety.vpn.ui.theme.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Лента новостей (вкладка «Новости»): [COUNT] последних опубликованных, в порядке из админки.
 * Лента хранится на телефоне ([NewsFeed]): вкладка сразу показывает сохранённое и тихо обновляется
 * (раньше каждый раз ждала сервер, и подгружала по 10 штук при прокрутке).
 *
 * Эмодзи: текст показывается как есть (Compose рисует их системным шрифтом). Ссылки http(s) —
 * кликабельные, открываются в браузере.
 */
private const val COUNT = 5

object NewsFeed {
    private const val FILE = "news.json"
    val items = mutableStateListOf<NewsItem>()
    val loading = mutableStateOf(false)
    val error = mutableStateOf(false)
    @Volatile private var loadedAt = 0L
    @Volatile private var restored = false
    private val gson = com.google.gson.Gson()

    /** Сохранённая лента — один раз за процесс, до первого запроса. */
    fun restore(context: Context) {
        if (restored) return
        restored = true
        try {
            val f = java.io.File(context.filesDir, FILE)
            if (!f.exists()) return
            val saved = gson.fromJson(f.readText(), Array<NewsItem>::class.java)?.toList().orEmpty()
            if (items.isEmpty()) items.addAll(saved.take(COUNT))
        } catch (e: Exception) { AppErrors.log("restore news", e) }
    }

    /** Последние [COUNT] заново; сохранённая лента видна, пока грузится свежая. */
    suspend fun refresh(context: Context, force: Boolean = false) {
        restore(context)
        if (loading.value) return
        if (!force && loadedAt > 0 && System.currentTimeMillis() - loadedAt < 60_000) return
        loading.value = true
        error.value = false
        try {
            val page = ApiClient.service.getNews(COUNT, 0)
            val fresh = page.items.distinctBy { it.id }.take(COUNT)
            items.clear()
            items.addAll(fresh)
            loadedAt = System.currentTimeMillis()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val f = java.io.File(context.filesDir, FILE)
                    val tmp = java.io.File(context.filesDir, "$FILE.tmp")
                    tmp.writeText(gson.toJson(fresh))
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                } catch (e: Exception) { AppErrors.log("save news", e) }
            }
        } catch (e: Exception) {
            AppErrors.log("load news", e)
            error.value = true
        } finally {
            loading.value = false
        }
    }

}

/**
 * Картинки новостей: через общий клиент (маршрут как у API), уменьшенные под ширину экрана. Кэш в
 * памяти и на диске (30 МБ, Cache-Control immutable): каждую картинку телефон скачивает один раз.
 */
private object NewsImages {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    @Volatile private var client: okhttp3.OkHttpClient? = null

    private fun client(context: Context): okhttp3.OkHttpClient = client ?: synchronized(this) {
        client ?: ApiClient.http.newBuilder()
            .cache(okhttp3.Cache(java.io.File(context.cacheDir, "news-images"), 30L * 1024 * 1024))
            .build().also { client = it }
    }

    suspend fun load(context: Context, imageId: String, ext: String?, maxWidth: Int): ImageBitmap? {
        cache.get(imageId)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url(ApiClient.newsImageUrl(imageId, ext)).build()
                val bytes = client(context).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.bytes() ?: return@withContext null
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
                bmp.asImageBitmap().also { cache.put(imageId, it) }
            } catch (e: Exception) {
                AppErrors.log("news image", e)
                null
            }
        }
    }
}

private fun formatNewsDate(iso: String?, lang: String): String {
    if (iso.isNullOrBlank()) return ""
    return try {
        val locale = if (lang == "en") Locale.ENGLISH else Locale.forLanguageTag("ru")
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
    } catch (_: Exception) { "" }
}

/** Текст новости с разметкой (жирный, курсив, подчёркнутый, зачёркнутый — [NewsFormat]) и кликабельными ссылками. */
private fun formatted(text: String): AnnotatedString = buildAnnotatedString {
    for (r in NewsFormat.parse(text)) {
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { r.underline || r.url != null },
            TextDecoration.LineThrough.takeIf { r.strike })
        val style = SpanStyle(
            fontWeight = if (r.bold) FontWeight.Bold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            color = if (r.url != null) Accent else androidx.compose.ui.graphics.Color.Unspecified,
        )
        if (r.url != null) {
            withLink(LinkAnnotation.Url(r.url, TextLinkStyles(style))) { append(r.text) }
        } else {
            withStyle(style) { append(r.text) }
        }
    }
}

@Composable
fun NewsScreen() {
    val context = LocalContext.current
    val lang = remember { context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru" }
    remember { NewsFeed.restore(context) }   // сохранённое — уже в первом кадре
    val items = NewsFeed.items
    val loading by NewsFeed.loading
    val error by NewsFeed.error
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { NewsFeed.refresh(context) }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(BgDark)) {
        item {
            Column(modifier = Modifier.statusBarsPadding().padding(24.dp)) {
                Text(stringResource(R.string.news_section), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.news_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Border)
        }

        items(items, key = { it.id }) { news -> NewsCard(news, lang) }

        item {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                when {
                    // Есть сохранённые — без крутилки и без ошибки внизу: они уже на экране
                    loading && items.isEmpty() -> CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    error && items.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.news_error), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = {
                            scope.launch { NewsFeed.refresh(context, force = true) }
                        }) {
                            Text(stringResource(R.string.news_retry), color = Accent, fontFamily = FontFamily.Monospace)
                        }
                    }
                    items.isEmpty() && !loading -> Text(stringResource(R.string.news_empty), color = TextMuted, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                    else -> Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun NewsCard(news: NewsItem, lang: String) {
    val context = LocalContext.current
    val maxWidth = context.resources.displayMetrics.widthPixels
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val date = formatNewsDate(news.published_at, lang)
        if (date.isNotEmpty()) Text(date, color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(news.title(lang), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(formatted(news.body(lang)), color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
        }
        // Картинка — под текстом
        news.image_id?.let { id ->
            Spacer(modifier = Modifier.height(4.dp))
            val image by produceState<ImageBitmap?>(initialValue = null, id) {
                value = NewsImages.load(context.applicationContext, id, news.image_ext, maxWidth)
            }
            val bmp = image
            if (bmp != null) {
                Image(bmp, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio((bmp.width.toFloat() / bmp.height).coerceIn(1f, 2.4f)))
            } else {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface))
            }
        }
    }
    HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 24.dp))
}
