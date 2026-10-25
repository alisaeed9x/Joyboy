package me.prince.launcher

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/** A list of YouTube results: a tab's content or a pushed channel / playlist page. */
class YList(val title: String) {
    var items by mutableStateOf<List<YItem>>(emptyList())
    var more by mutableStateOf<(() -> YRes)?>(null)
    var busy by mutableStateOf(true)
    var error by mutableStateOf("")
    var chan by mutableStateOf<YItem?>(null)   // set on a channel page (for subscribe)
    fun set(r: YRes) { items = r.items; more = r.more; busy = false; error = "" }
    fun fail(e: Throwable) { busy = false; error = e.message ?: "فشل التحميل" }
}

/** YouTube for the TV: Home / Search / History / Channels. Results open in PlayerActivity (controlled from the phone). */
class YouTubeActivity : ComponentActivity() {
    private val pages = mutableStateListOf<YList>()   // pushed channel/playlist pages (top = visible)
    private var tab by mutableStateOf(0)
    private var query by mutableStateOf("")
    private val home = YList("الرئيسية")
    private val search = YList("بحث").also { it.busy = false }
    private var homeLoaded = false

    companion object {
        private var ref: WeakReference<YouTubeActivity>? = null
        fun live(): Boolean = ref?.get()?.let { !it.isFinishing && !it.isDestroyed } == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ref = WeakReference(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
        intent.getStringExtra("q")?.let { runSearch(it) } ?: loadHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("q")?.let { runSearch(it) }
    }

    override fun onDestroy() { if (ref?.get() === this) ref = null; super.onDestroy() }

    private fun bg(block: () -> Unit) { Thread { block() }.also { it.isDaemon = true }.start() }
    private fun ui(f: () -> Unit) { Handler(Looper.getMainLooper()).post(f) }

    private fun loadHome() {
        home.busy = true; homeLoaded = true
        bg { try { val l = Yt.home(this); ui { home.set(YRes(l, null)) } } catch (e: Throwable) { ui { home.fail(e) } } }
    }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        query = q; tab = 1; pages.clear(); search.busy = true
        bg { try { val r = Yt.search(q); ui { search.set(r) } } catch (e: Throwable) { ui { search.fail(e) } } }
    }

    private fun openChannel(it: YItem) {
        val p = YList(it.title.ifBlank { it.channel }); p.chan = it; pages.add(p)
        bg {
            try { val c = Yt.channel(it.url.ifBlank { it.channelUrl }); ui { p.set(c.first) } } catch (e: Throwable) { ui { p.fail(e) } }
        }
    }

    private fun openPlaylist(it: YItem) {
        val p = YList(it.title); pages.add(p)
        bg { try { val r = Yt.playlist(it.url); ui { p.set(r) } } catch (e: Throwable) { ui { p.fail(e) } } }
    }

    private fun click(it: YItem, list: List<YItem>) {
        when (it.kind) {
            1 -> openChannel(it)
            2 -> openPlaylist(it)
            else -> {
                val vids = list.filter { x -> x.kind == 0 }
                Yt.addHist(this, it)
                startActivity(Intent(this, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .putExtra("yt", it.id).putExtra("title", it.title)
                    .putStringArrayListExtra("list", ArrayList(vids.map { v -> "yt:" + v.id }))
                    .putStringArrayListExtra("names", ArrayList(vids.map { v -> v.title })))
            }
        }
    }

    private fun channelOf(v: YItem) = YItem("", v.channel, "", v.channelUrl, v.avatar, -1L, 1, v.channelUrl, v.avatar)

    @Composable
    private fun Screen() {
        BackHandler(enabled = pages.isNotEmpty()) { pages.removeAt(pages.size - 1) }
        LaunchedEffect(tab) { if (tab == 0 && !homeLoaded) loadHome() }
        val ctx = LocalContext.current
        Box(Modifier.fillMaxSize().background(ScreenBg)) {
            val page = pages.lastOrNull()
            if (page != null) {
                ResultGrid(page, header = {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(page.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 6.dp))
                        page.chan?.let { ch ->
                            var sub by remember(ch.url) { mutableStateOf(Yt.isSub(ctx, ch.url.ifBlank { ch.channelUrl })) }
                            TvPill(if (sub) "✓ مشترك" else "＋ اشترك", sub) { sub = Yt.toggleSub(ctx, ch) }
                        }
                    }
                })
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("الرئيسية", "بحث", "السجل", "القنوات").forEachIndexed { i, n -> TvPill(n, tab == i) { tab = i } }
                }
                Box(Modifier.fillMaxSize().padding(top = 84.dp)) {
                    when (tab) {
                        0 -> ResultGrid(home)
                        1 -> ResultGrid(search, header = { SearchBox() })
                        2 -> { val l = remember(tab) { Yt.hist(ctx) }; Static(l, "لسه مفيش سجل مشاهدة") }
                        else -> { val l = remember(tab) { Yt.subs(ctx) }; Static(l, "لسه مفيش قنوات — اضغط مطوّل على أي فيديو للدخول لقناته واشترك") }
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchBox() {
        var text by remember { mutableStateOf(query) }
        LaunchedEffect(query) { text = query }
        BasicTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch(text) }),
            modifier = Modifier.fillMaxWidth(0.6f).background(CardBg, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
            decorationBox = { inner -> if (text.isEmpty()) Text("اكتب للبحث… أو ابعت النص من موبايلك (ريموت ← كتابة)", color = Dim, fontSize = 18.sp); inner() },
        )
    }

    @Composable
    private fun Static(l: List<YItem>, empty: String) {
        val y = remember(l) { YList("").also { it.set(YRes(l, null)) } }
        if (l.isEmpty()) Text(empty, color = Dim, fontSize = 20.sp, modifier = Modifier.padding(48.dp)) else ResultGrid(y)
    }

    @Composable
    private fun ResultGrid(l: YList, header: (@Composable () -> Unit)? = null) {
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            if (l.busy) item(span = { GridItemSpan(maxLineSpan) }) { Text("⏳ بحمّل…", color = Dim, fontSize = 20.sp) }
            if (l.error.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("⚠ " + l.error, color = Color(0xFFFF8A80), fontSize = 18.sp) }
            items(l.items) { v ->
                TvCard(
                    title = v.title,
                    sub = when (v.kind) { 1 -> "قناة"; 2 -> "قايمة تشغيل · " + v.channel; else -> v.channel },
                    badge = if (v.kind == 0) fmtDur(v.dur) else null,
                    onLongClick = if (v.kind == 0 && v.channelUrl.isNotBlank()) ({ openChannel(channelOf(v)) }) else null,
                    onClick = { click(v, l.items) },
                ) { RemoteImage(v.thumb, Modifier.fillMaxSize()) }
            }
            if (l.more != null) item(span = { GridItemSpan(maxLineSpan) }) {
                var loading by remember { mutableStateOf(false) }
                TvPill(if (loading) "⏳" else "المزيد") {
                    val m = l.more ?: return@TvPill
                    if (loading) return@TvPill
                    loading = true
                    bg { try { val r = m(); ui { l.items = l.items + r.items; l.more = r.more; loading = false } } catch (e: Throwable) { ui { loading = false; l.error = e.message ?: "فشل" } } }
                }
            }
        }
    }
}
