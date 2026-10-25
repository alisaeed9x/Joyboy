package me.prince.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** kind: 0 video · 1 channel · 2 playlist */
class YItem(val id: String, val title: String, val channel: String, val channelUrl: String, val thumb: String, val dur: Long, val kind: Int, val url: String, val avatar: String = "") {
    fun toJson() = JSONObject().put("id", id).put("t", title).put("c", channel).put("cu", channelUrl).put("th", thumb).put("d", dur).put("k", kind).put("u", url).put("a", avatar)
    companion object {
        fun from(o: JSONObject) = YItem(o.optString("id"), o.optString("t"), o.optString("c"), o.optString("cu"), o.optString("th"), o.optLong("d"), o.optInt("k"), o.optString("u"), o.optString("a"))
    }
}

/** A page of results; [more] loads the next one (null = last page). */
class YRes(val items: List<YItem>, val more: (() -> YRes)?)

/** One playable quality: video url + separate audio url when the stream is video-only. */
class YQ(val label: String, val url: String, val audio: String?)
class YPlay(val title: String, val quals: List<YQ>, val start: Int)

object YtDownloader : Downloader() {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    override fun execute(request: Request): Response {
        val c = URL(request.url()).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        c.requestMethod = request.httpMethod()
        c.setRequestProperty("User-Agent", UA)
        for ((k, vs) in request.headers()) {
            if (vs.isEmpty()) continue
            c.setRequestProperty(k, vs[0])
            for (i in 1 until vs.size) c.addRequestProperty(k, vs[i])
        }
        c.setRequestProperty("Accept-Encoding", "gzip")
        val data = request.dataToSend()
        if (data != null) { c.doOutput = true; c.outputStream.use { it.write(data) } }
        val code = c.responseCode
        if (code == 429) { c.disconnect(); throw ReCaptchaException("reCaptcha Challenge requested", request.url()) }
        var ins = if (code >= 400) c.errorStream else c.inputStream
        if (ins != null && "gzip".equals(c.contentEncoding, true)) ins = GZIPInputStream(ins)
        val body = ins?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        val hs = HashMap<String, List<String>>()
        for ((k, v) in c.headerFields) if (k != null) hs[k] = v
        val last = c.url.toString(); val msg = c.responseMessage ?: ""
        c.disconnect()
        return Response(code, msg, hs, body, last)
    }
}

/** YouTube through NewPipeExtractor (ported from Subtitler's YtNp, trimmed): home, search, channels, playlists, stream picking. */
object Yt {
    private val YT get() = ServiceList.YouTube
    @Volatile private var ready = false
    const val PLAY_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    @Synchronized fun init() {
        if (ready) return
        NewPipe.init(YtDownloader, Localization("ar", "EG"), ContentCountry("EG"))
        ready = true
    }

    fun idOf(url: String): String = try { init(); YT.streamLHFactory.getId(url) } catch (_: Throwable) { "" }

    private fun thumb(i: InfoItem): String { val t = i.thumbnails; return if (t == null || t.isEmpty()) "" else t[t.size / 2].url ?: "" }
    private fun call(o: Any?, name: String): Any? = try { o?.javaClass?.getMethod(name)?.invoke(o) } catch (_: Throwable) { null }
    private fun imgUrl(l: Any?): String { for (x in (l as? List<*> ?: return "").reversed()) { val u = call(x, "getUrl") as? String; if (!u.isNullOrBlank()) return u }; return "" }

    private fun conv(i: InfoItem): YItem? = when (i) {
        is StreamInfoItem -> {
            val id = idOf(i.url)
            if (id.isEmpty() || i.url.contains("/shorts/") || i.duration in 1L..60L) null   // no Shorts
            else YItem(id, i.name ?: "", i.uploaderName ?: "", i.uploaderUrl ?: "", thumb(i), i.duration * 1000L, 0, i.url)
        }
        is ChannelInfoItem -> YItem("", i.name ?: "", "", i.url, thumb(i), -1L, 1, i.url, thumb(i))
        is PlaylistInfoItem -> YItem("", i.name ?: "", i.uploaderName ?: "", i.uploaderUrl ?: "", thumb(i), -1L, 2, i.url)
        else -> null
    }

    private fun wrap(items: List<InfoItem>, next: Page?, more: (Page) -> ListExtractor.InfoItemsPage<out InfoItem>): YRes {
        val l = items.mapNotNull { conv(it) }
        return YRes(l, if (next == null) null else ({ val p = more(next); wrap(p.items, if (p.hasNextPage()) p.nextPage else null, more) }))
    }

    fun search(q: String): YRes {
        init()
        val qh = YT.searchQHFactory.fromQuery(q, emptyList(), "")
        val si = SearchInfo.getInfo(YT, qh)
        return wrap(si.relatedItems, if (si.hasNextPage()) si.nextPage else null) { p -> SearchInfo.getMoreItems(YT, qh, p) }
    }

    fun suggestions(q: String): List<String> = try { init(); YT.suggestionExtractor.suggestionList(q) } catch (_: Throwable) { emptyList() }

    class Chan(val name: String, val avatar: String, val first: YRes)
    fun channel(url: String): Chan {
        init()
        val ci = ChannelInfo.getInfo(YT, url)
        val tab = ci.tabs.firstOrNull { it.contentFilters.contains("videos") } ?: ci.tabs.first()
        val ti = ChannelTabInfo.getInfo(YT, tab)
        val first = wrap(ti.relatedItems, if (ti.hasNextPage()) ti.nextPage else null) { p -> ChannelTabInfo.getMoreItems(YT, tab, p) }
        return Chan(ci.name ?: "", imgUrl(call(ci, "getAvatars")), first)
    }

    fun playlist(url: String): YRes {
        init()
        val pi = PlaylistInfo.getInfo(YT, url)
        return wrap(pi.relatedItems, if (pi.hasNextPage()) pi.nextPage else null) { p -> PlaylistInfo.getMoreItems(YT, url, p) }
    }

    fun related(id: String): List<YItem> = try { init(); StreamInfo.getInfo(YT, "https://www.youtube.com/watch?v=$id").relatedItems.mapNotNull { conv(it) }.filter { it.kind == 0 } } catch (_: Throwable) { emptyList() }

    /** Home: videos related to what you watched + latest uploads of your channels; falls back to trending/search when empty. */
    fun home(ctx: Context): List<YItem> {
        init()
        val seen = hist(ctx).map { it.id }.toSet()
        val rec = ArrayList<List<YItem>>(); val ths = ArrayList<Thread>()
        val hs = hist(ctx).take(3); val subs = subs(ctx).shuffled().take(6)
        val recR = arrayOfNulls<List<YItem>>(hs.size); val subR = arrayOfNulls<List<YItem>>(subs.size)
        hs.forEachIndexed { n, h -> ths.add(Thread { recR[n] = related(h.id) }) }
        subs.forEachIndexed { n, c -> ths.add(Thread { subR[n] = try { channel(c.channelUrl.ifBlank { c.url }).first.items.filter { it.kind == 0 }.take(6) } catch (_: Throwable) { emptyList() } }) }
        ths.forEach { it.isDaemon = true; it.start() }
        ths.forEach { try { it.join(12000L) } catch (_: Throwable) {} }
        val out = LinkedHashMap<String, YItem>()
        val a = subR.map { it ?: emptyList() }; val b = recR.map { it ?: emptyList() }
        var k = 0
        while (a.any { k < it.size } || b.any { k < it.size }) {
            for (l in a) if (k < l.size) out.putIfAbsent(l[k].id, l[k])
            for (l in b) if (k < l.size && l[k].id !in seen) out.putIfAbsent(l[k].id, l[k])
            k++
        }
        if (out.isEmpty()) {
            try {
                val ke = YT.kioskList.defaultKioskExtractor; ke.fetchPage()
                ke.initialPage.items.mapNotNull { conv(it) }.forEach { out.putIfAbsent(it.id, it) }
            } catch (_: Throwable) {}
        }
        if (out.isEmpty()) try { search("الأكثر مشاهدة").items.filter { it.kind == 0 }.forEach { out.putIfAbsent(it.id, it) } } catch (_: Throwable) {}
        return out.values.toList()
    }

    /**
     * Stream choice: video-only mp4 up to 1080p + the best audio (Arabic track first, else the original), plus muxed streams for the
     * heights video-only doesn't cover. Qualities are sorted high → low; default = highest ≤ 1080.
     */
    fun pick(id: String): YPlay? {
        init()
        val i = StreamInfo.getInfo(YT, "https://www.youtube.com/watch?v=$id")
        val vo0 = i.videoOnlyStreams.filter { !it.content.isNullOrBlank() }
        val vo = vo0.filter { it.format?.suffix == "mp4" }.ifEmpty { vo0 }
        val mux = i.videoStreams.filter { !it.content.isNullOrBlank() }
        val aud = i.audioStreams.filter { !it.content.isNullOrBlank() && it.audioTrackType?.name != "DESCRIPTIVE" }.sortedByDescending { it.averageBitrate }
        fun best(l: List<org.schabi.newpipe.extractor.stream.AudioStream>) = l.firstOrNull { it.format?.suffix == "m4a" } ?: l.firstOrNull()
        val ar = aud.filter { (it.audioLocale?.language ?: "").lowercase() == "ar" }
        val orig = aud.filter { it.audioTrackType?.name == "ORIGINAL" }
        val audUrl: String? = (best(ar) ?: best(orig) ?: best(aud))?.content
        val byH = java.util.TreeMap<Int, YQ>(compareByDescending<Int> { it })
        if (audUrl != null) for (v in vo.sortedByDescending { it.bitrate }) if (v.height > 0 && !byH.containsKey(v.height)) byH[v.height] = YQ(v.height.toString() + "p", v.content, audUrl)
        for (m in mux) if (m.height > 0 && !byH.containsKey(m.height)) byH[m.height] = YQ(m.height.toString() + "p", m.content, null)
        val title = i.name ?: ""
        if (byH.isNotEmpty()) {
            val q = byH.values.toList()
            val start = q.indexOfFirst { it.label.removeSuffix("p").toInt() <= 1080 }.coerceAtLeast(0)
            return YPlay(title, q, start)
        }
        val hls = i.hlsUrl
        return if (!hls.isNullOrBlank()) YPlay(title, listOf(YQ("HLS", hls, null)), 0) else null
    }

    // ===== history / channels (SharedPreferences) =====
    private fun sp(c: Context) = c.getSharedPreferences("yt", Context.MODE_PRIVATE)
    private fun read(c: Context, k: String): MutableList<YItem> = try {
        val a = JSONArray(sp(c).getString(k, "[]")); (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> YItem.from(o) } }.toMutableList()
    } catch (_: Throwable) { mutableListOf() }
    private fun write(c: Context, k: String, l: List<YItem>) { sp(c).edit().putString(k, JSONArray(l.map { it.toJson() }).toString()).apply() }

    fun hist(c: Context): List<YItem> = read(c, "hist")
    fun addHist(c: Context, v: YItem) { val l = read(c, "hist"); l.removeAll { it.id == v.id }; l.add(0, v); write(c, "hist", l.take(200)) }
    fun subs(c: Context): List<YItem> = read(c, "subs")
    fun isSub(c: Context, url: String) = read(c, "subs").any { it.url == url || it.channelUrl == url }
    fun toggleSub(c: Context, ch: YItem): Boolean {
        val l = read(c, "subs"); val had = l.removeAll { it.url == ch.url || it.channelUrl == ch.url }
        if (!had) l.add(0, ch)
        write(c, "subs", l); return !had
    }
}
