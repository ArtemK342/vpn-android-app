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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fsociety.vpn.ui.theme.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Лента новостей (вкладка «Новости»): только опубликованные, в порядке из админки, по [PAGE] штук —
 * следующая страница подгружается, когда до конца списка остаётся пара карточек. Лента живёт в
 * процессе ([NewsFeed]): при возврате на вкладку видно то, что было, и тихо обновляется.
 *
 * Эмодзи: текст показывается как есть (Compose рисует их системным шрифтом). Ссылки http(s) —
 * кликабельные, открываются в браузере.
 */
private const val PAGE = 10

object NewsFeed {
    val items = mutableStateListOf<NewsItem>()
    val hasMore = mutableStateOf(true)
    val loading = mutableStateOf(false)
    val error = mutableStateOf(false)
    @Volatile private var loadedAt = 0L

    /** Первая страница заново; старая лента видна, пока грузится новая. */
    suspend fun refresh(force: Boolean = false) {
        if (loading.value) return
        if (!force && items.isNotEmpty() && System.currentTimeMillis() - loadedAt < 60_000) return
        load(reset = true)
    }

    suspend fun loadMore() {
        if (loading.value || !hasMore.value || error.value) return
        load(reset = false)
    }

    private suspend fun load(reset: Boolean) {
        loading.value = true
        error.value = false
        try {
            val page = ApiClient.service.getNews(PAGE, if (reset) 0 else items.size)
            if (reset) items.clear()
            // Порядок мог поменяться между страницами — без дублей
            val known = items.map { it.id }.toHashSet()
            items.addAll(page.items.filter { known.add(it.id) })
            hasMore.value = page.has_more
            loadedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            AppErrors.log("load news", e)
            error.value = true
        } finally {
            loading.value = false
        }
    }
}

/** Картинки новостей: через общий клиент (маршрут как у API), уменьшенные под ширину экрана, кэш в памяти. */
private object NewsImages {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    suspend fun load(imageId: String, maxWidth: Int): ImageBitmap? {
        cache.get(imageId)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url(ApiClient.newsImageUrl(imageId)).build()
                val bytes = ApiClient.http.newCall(req).execute().use { resp ->
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

private val URL_RE = Regex("https?://[^\\s<>\"]+")

/** Текст с кликабельными ссылками http(s); хвостовая пунктуация — не часть ссылки. */
private fun linkified(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in URL_RE.findAll(text)) {
        val url = m.value.trimEnd('.', ',', '!', '?', ':', ';', ')', ']')
        append(text, last, m.range.first)
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline)))) {
            append(url)
        }
        last = m.range.first + url.length
    }
    append(text, last, text.length)
}

@Composable
fun NewsScreen() {
    val context = LocalContext.current
    val lang = remember { context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru" }
    val items = NewsFeed.items
    val hasMore by NewsFeed.hasMore
    val loading by NewsFeed.loading
    val error by NewsFeed.error
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { NewsFeed.refresh() }
    // Подгрузка: до конца списка осталось 2 карточки
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd && items.isNotEmpty()) NewsFeed.loadMore() }
    }

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
                    loading -> CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.news_error), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = {
                            // Пустая лента — с первой страницы, иначе — следующую
                            scope.launch {
                                NewsFeed.error.value = false
                                if (items.isEmpty()) NewsFeed.refresh(force = true) else NewsFeed.loadMore()
                            }
                        }) {
                            Text(stringResource(R.string.news_retry), color = Accent, fontFamily = FontFamily.Monospace)
                        }
                    }
                    items.isEmpty() -> Text(stringResource(R.string.news_empty), color = TextMuted, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                    !hasMore -> Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun NewsCard(news: NewsItem, lang: String) {
    val maxWidth = LocalContext.current.resources.displayMetrics.widthPixels
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        news.image_id?.let { id ->
            val image by produceState<ImageBitmap?>(initialValue = null, id) { value = NewsImages.load(id, maxWidth) }
            val bmp = image
            if (bmp != null) {
                Image(bmp, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio((bmp.width.toFloat() / bmp.height).coerceIn(1f, 2.4f)))
            } else {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface))
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        val date = formatNewsDate(news.published_at, lang)
        if (date.isNotEmpty()) Text(date, color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(news.title(lang), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(linkified(news.body(lang)), color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
    HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 24.dp))
}
