package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/*
 * YOUTUBE_SLOT (v1086) ─────────────────────────────────────────────────────────────
 * كود يوتيوب/MeeTube اتشال بالكامل (واجهة + محرك روابط + حساب + فيد + NewPipe + Skipii).
 * الملف ده بدائل فاضية بنفس التوقيعات عشان باقي الكود يفضل يتبني من غير ما يتغيّر؛ كلها بترجّع null/فاضي/false.
 * عند تركيب Skipii (أو أي يوتيوب جديد): ابحث عن  YOUTUBE_SLOT  — نقطة التركيب الأساسية:
 *   1) YtProUi (تحت) = محتوى تبويب يوتيوب (تبويب 4) — Main.ensureYt().
 *   2) MtYt / YtExtract (Sniffer.kt) = جلب روابط الفيديو/الصوت اللي بيستخدمها المشغّل والموسيقى.
 *   3) YtFeedStore / MtFeed = فيد الاشتراكات/الرئيسية للتلفزيون.  4) YtHistory / YtNext / YtFallback / YtAudio = سجل/التالي/بحث/صوت.
 */

/** YOUTUBE_SLOT: سجل يوتيوب (فاضي). */
class YtItem(val id: String, val title: String, val ts: Long, val chan: String = "", val audio: Boolean = false)

object YtHistory {
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun load(ctx: Context): List<YtItem> = emptyList()   // YOUTUBE_SLOT
    fun add(ctx: Context, id: String, title: String, chan: String = "", audio: Boolean = false) {}   // YOUTUBE_SLOT
    fun remove(ctx: Context, id: String) {}   // YOUTUBE_SLOT
    fun clear(ctx: Context) {}   // YOUTUBE_SLOT
}

/** YOUTUBE_SLOT: محرك جلب الروابط (كان MediaServiceCore). */
object MtYt {
    @Volatile var app: Application? = null
    @Volatile var lastWhy: String = ""
    class Got(val url: String, val audio: String?, val ua: String, val title: String, val qlist: String, val by: String)
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun video(id: String, maxH: Int, fresh: Boolean = false): Got? = null   // YOUTUBE_SLOT
    fun audio(id: String, maxH: Int = 0): YtPick? = null   // YOUTUBE_SLOT
    fun pick(id: String, maxH: Int): YtPick? = null   // YOUTUBE_SLOT
    fun signedIn(): Boolean = false   // YOUTUBE_SLOT
    fun search(q: String): List<NpItem> = emptyList()   // YOUTUBE_SLOT
    fun subscriptions(): List<NpItem> = emptyList()   // YOUTUBE_SLOT
    fun homeFeed(): List<NpItem> = emptyList()   // YOUTUBE_SLOT
    fun clearCache() {}   // YOUTUBE_SLOT
}

object YtMtSignIn {
    fun signIn(a: Activity, after: ((Boolean) -> Unit)? = null) { after?.invoke(false) }   // YOUTUBE_SLOT
}

object YtFast {
    fun startup() {}   // YOUTUBE_SLOT
}

object YtSkin {
    var translucent = false   // YOUTUBE_SLOT
}

/** YOUTUBE_SLOT: فيد الاشتراكات/الرئيسية (للتلفزيون). */
object YtFeedStore {
    @Volatile var subs: List<NpItem> = emptyList(); private set
    @Volatile var home: List<NpItem> = emptyList(); private set
    @Volatile var signed: Boolean? = null; private set
    @Volatile var at = 0L; private set
    @Volatile var onChange: (() -> Unit)? = null
    fun load(ctx: Context) {}   // YOUTUBE_SLOT
    fun put(ctx: Context, j: JSONObject) {}   // YOUTUBE_SLOT
    fun requestRefresh() {}   // YOUTUBE_SLOT
    fun requestLogin() {}   // YOUTUBE_SLOT
    fun refreshRequested(): Boolean = false   // YOUTUBE_SLOT
    fun loginRequested(): Boolean = false   // YOUTUBE_SLOT
}

object YtNext {
    fun markPlayed(id: String) {}   // YOUTUBE_SLOT
    fun nextAfter(id: String): NpItem? = null   // YOUTUBE_SLOT
    fun fetch(id: String): Pair<List<NpItem>, String> = emptyList<NpItem>() to ""   // YOUTUBE_SLOT
    fun show(act: Activity, ui: Ui, th: Theme, curId: String, onPick: (NpItem) -> Unit) {}   // YOUTUBE_SLOT
}

object YtFallback {
    fun pending(ui: Ui, th: Theme, box: LinearLayout, q: String) {}   // YOUTUBE_SLOT
    fun fill(act: Activity, ui: Ui, th: Theme, box: LinearLayout, q: String, stale: () -> Boolean, onPick: (NpItem) -> Unit, onBrowser: (String) -> Unit) {}   // YOUTUBE_SLOT
}

object YtHistoryUi {
    fun show(act: Activity, ui: Ui, th: Theme, onVideo: (String, String) -> Unit, onAudio: (String, String, String) -> Unit) {}   // YOUTUBE_SLOT
}

/** YOUTUBE_SLOT: صوت يوتيوب في مشغّل الموسيقى. */
object YtAudio {
    fun isRemote(uri: String) = uri.startsWith("http", true)
    fun itemOf(t: Track?): NpItem? = null   // YOUTUBE_SLOT
    fun headersFor(uri: String): Map<String, String> = emptyMap()   // YOUTUBE_SLOT
    fun thumbUrl(t: Track): String? = null   // YOUTUBE_SLOT
    fun thumb(t: Track): Bitmap? = null   // YOUTUBE_SLOT
    fun track(vid: String, title: String, channel: String, thumb: String, a: YtPick): Track =   // YOUTUBE_SLOT
        Track("yt:" + vid, a.url, title.ifBlank { a.title }, channel, a.durMs, "yt", "يوتيوب")
    fun src(app: Context, t: Track): AudioSource =
        if (isRemote(t.uri)) AudioSources.make(app, null, t.uri, headersFor(t.uri), 1) { }
        else AudioSources.make(app, android.net.Uri.parse(t.uri), null, emptyMap(), 1) { }
}

/**
 * YOUTUBE_SLOT: ← نقطة تركيب Skipii الأساسية. بدل ما Main يبني YtProUi، ركّب هنا View Skipii.
 * دلوقتي: شاشة فاضية بتقول إن اليوتيوب مش متركّب. نفس الواجهة اللي Main بيستخدمها (root · busyView · onTab · refresh · onHide · back · openUrl · tapTab …).
 */
class YtProUi(private val act: Activity, private val ui: Ui, private val th: Theme,
              private val recents: () -> Map<String, *>,
              private val onPlay: (String, String, Boolean) -> Unit,
              private val onBrowser: (String) -> Unit,
              private val onImport: () -> Unit,
              private val onAudio: (NpItem) -> Unit = {},
              private val onDownload: (NpItem, Boolean) -> Unit = { _, _ -> },
              private val onPubHistory: () -> Unit = {},
              private val onPlayAt: ((String, String, Boolean, Long, YtPick?) -> Unit)? = null) {
    var onTab: (() -> Unit)? = null
    val root: View = FrameLayout(act).also { fl ->
        fl.addView(TextView(act).apply { text = "يوتيوب — مش متركّب دلوقتي"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(th.muted) },
            FrameLayout.LayoutParams(-1, -1))
    }
    val busyView: View = View(act).also { it.visibility = View.GONE }
    fun refresh() {}
    fun tvFocusList() {}
    fun onHide() {}
    fun tapTab(i: Int, was: Boolean) {}
    fun leaveForPip(): Boolean = false
    fun openUrl(u: String) {}
    fun back(): Boolean = false
}
