package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v793) طبقة مكتبات اليوتيوب القابلة للتبديل.
 * - كل مكتبة بتنفّذ YtBackend. أي دالة بترجّع null (أو بترمي خطأ) = YtNp بيكمّل بـ NewPipe تلقائي.
 * - المكتبة الحالية من الإعدادات (Cfg "yt_backend")، الافتراضي innertube (v794).
 * - لإضافة مكتبة جديدة: object يطبّق YtBackend + إضافته في YtBackends.all.
 */
interface YtBackend {
    val id: String
    val label: String
    fun channel(url: String): NpChan? = null
    fun channelFull(url: String): NpChanFull? = null
    fun searchFirst(q: String, filter: String): YtRes? = null
    fun searchMore(q: String, filter: String, p: Page): YtRes? = null
}

object YtBackends {
    const val KEY = "yt_backend"
    val all: List<YtBackend> = listOf(NewPipeBackend, InnertubeBackend)
    val names: List<String> get() = all.map { it.label }
    val ids: List<String> get() = all.map { it.id }

    fun cur(): YtBackend = all.firstOrNull { it.id == Cfg.str(KEY, "innertube") } ?: NewPipeBackend
    /** المكتبة البديلة (غير NewPipe) أو null لو NewPipe هو المختار */
    fun alt(): YtBackend? = cur().takeIf { it !== NewPipeBackend }
    fun shortsAsVideos(): Boolean = Cfg.str("yt_shorts_normal", "1") != "0"
}

/** NewPipe = الافتراضي؛ مفيش دوال هنا، YtNp بيشتغل بيه مباشرة */
object NewPipeBackend : YtBackend {
    override val id = "newpipe"
    override val label = "NewPipe"
}

/** Innertube مباشر (نداءات youtubei/v1 بدون مكتبة خارجية): القنوات بالشورتس والبوستات + البحث */
object InnertubeBackend : YtBackend {
    override val id = "innertube"
    override val label = "Innertube مباشر (الافتراضي: أسرع + شورتس وبوستات)"

    private const val BASE = "https://www.youtube.com/youtubei/v1/"
    private const val VER = "2.20250101.00.00"

    private fun ctx(): JSONObject = JSONObject().put("client", JSONObject()
        .put("clientName", "WEB").put("clientVersion", VER)
        .put("hl", YtLocale.hl()).put("gl", YtLocale.gl()))

    private fun post(ep: String, body: JSONObject): JSONObject {
        body.put("context", ctx())
        val c = URL(BASE + ep + "?prettyPrint=false").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 12000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        c.setRequestProperty("Accept-Language", YtLocale.acceptLanguage())
        c.setRequestProperty("Origin", "https://www.youtube.com")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) throw RuntimeException("Innertube " + c.responseCode)
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { c.disconnect() }
    }

    // ---------- أدوات JSON ----------
    private fun txt(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        o.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
        val r = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder(); for (i in 0 until r.length()) sb.append(r.getJSONObject(i).optString("text"))
        return sb.toString()
    }
    private fun thumbOf(o: JSONObject?): String {
        val a = o?.optJSONArray("thumbnails") ?: o?.optJSONArray("sources") ?: return ""
        if (a.length() == 0) return ""
        val u = a.getJSONObject(a.length() / 2).optString("url")
        return if (u.startsWith("//")) "https:$u" else u
    }
    private fun count(s: String): Long {
        val m = Regex("([\\d.,]+)\\s*([KkMmBb]|ألف|مليون|مليار)?").find(s.replace("\u066b", ".").replace("\u066c", ",")) ?: return -1L
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1L
        val mul = when (m.groupValues[2].lowercase()) { "k", "ألف" -> 1e3; "m", "مليون" -> 1e6; "b", "مليار" -> 1e9; else -> 1.0 }
        return (n * mul).toLong()
    }
    private fun durSec(s: String): Long {
        if (s.isBlank() || !s.contains(':')) return 0L
        var t = 0L; for (p in s.trim().split(':')) t = t * 60 + (p.filter { it.isDigit() }.toLongOrNull() ?: 0L); return t
    }

    /** يمشي على كل الـJSON ويجمع العناصر المعروفة بغض النظر عن العمق */
    private class Acc { val items = ArrayList<NpItem>(); var token: String? = null; val tabs = ArrayList<Triple<String, String, String>>() }

    private fun walk(n: Any?, f: Acc, chName: String, chUrl: String) {
        when (n) {
            is JSONObject -> {
                for (k in n.keys()) {
                    val v = n.opt(k)
                    when (k) {
                        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" -> if (v is JSONObject) video(v, f, chName, chUrl)
                        "reelItemRenderer" -> if (v is JSONObject) reel(v, f, chName, chUrl)
                        "shortsLockupViewModel" -> if (v is JSONObject) shortLock(v, f, chName, chUrl)
                        "backstagePostRenderer" -> if (v is JSONObject) postR(v, f, chUrl)
                        "channelRenderer" -> if (v is JSONObject) chanR(v, f)
                        "playlistRenderer", "gridPlaylistRenderer" -> if (v is JSONObject) plR(v, f)
                        "lockupViewModel" -> if (v is JSONObject) lockup(v, f, chName, chUrl)
                        "continuationCommand" -> if (v is JSONObject && f.token == null) f.token = v.optString("token").takeIf { it.isNotEmpty() }
                        else -> walk(v, f, chName, chUrl)
                    }
                }
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), f, chName, chUrl)
        }
    }

    private fun vid(id: String, title: String, ch: String, chUrl: String, th: String, dur: Long, views: Long, age: String) =
        NpItem(id, title, ch, chUrl, th, dur, views, age, 0, "https://www.youtube.com/watch?v=$id")

    private fun video(v: JSONObject, f: Acc, chName: String, chUrl: String) {
        val id = v.optString("videoId"); if (id.length != 11) return
        val by = v.optJSONObject("ownerText") ?: v.optJSONObject("shortBylineText") ?: v.optJSONObject("longBylineText")
        val cid = by?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
        val dur = durSec(txt(v.optJSONObject("lengthText")))
        val live = v.toString().contains("\"BADGE_STYLE_TYPE_LIVE_NOW\"")
        f.items.add(vid(id, txt(v.optJSONObject("title")), txt(by).ifEmpty { chName }, if (cid.startsWith("UC")) "https://www.youtube.com/channel/$cid" else chUrl,
            thumbOf(v.optJSONObject("thumbnail")), if (live) -1L else dur, count(txt(v.optJSONObject("viewCountText"))), txt(v.optJSONObject("publishedTimeText"))))
    }
    private fun reel(v: JSONObject, f: Acc, chName: String, chUrl: String) {
        val id = v.optString("videoId"); if (id.length != 11) return
        f.items.add(vid(id, txt(v.optJSONObject("headline")), chName, chUrl, thumbOf(v.optJSONObject("thumbnail")), 0L, count(txt(v.optJSONObject("viewCountText"))), ""))
    }
    private fun shortLock(v: JSONObject, f: Acc, chName: String, chUrl: String) {
        val id = v.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("reelWatchEndpoint")?.optString("videoId")
            ?: v.optString("entityId").removePrefix("shorts-shelf-item-")
        if (id.length != 11) return
        val om = v.optJSONObject("overlayMetadata")
        f.items.add(vid(id, txt(om?.optJSONObject("primaryText")), chName, chUrl, thumbOf(v.optJSONObject("thumbnail")), 0L, count(txt(om?.optJSONObject("secondaryText"))), ""))
    }
    /** lockupViewModel = شكل YouTube الجديد للفيديو/البلاي ليست */
    private fun lockup(v: JSONObject, f: Acc, chName: String, chUrl: String) {
        val id = v.optString("contentId"); val type = v.optString("contentType")
        val md = v.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = txt(md?.optJSONObject("title"))
        val th = thumbOf(v.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")?.optJSONObject("image"))
        if (type == "LOCKUP_CONTENT_TYPE_VIDEO" && id.length == 11) f.items.add(vid(id, title, chName, chUrl, th, 0L, -1L, ""))
        else if (type == "LOCKUP_CONTENT_TYPE_PLAYLIST" && id.isNotEmpty()) f.items.add(NpItem("", title, chName, chUrl, th, -1L, -1L, "", 2, "https://www.youtube.com/playlist?list=$id"))
    }
    private fun plR(v: JSONObject, f: Acc) {
        val id = v.optString("playlistId"); if (id.isEmpty()) return
        f.items.add(NpItem("", txt(v.optJSONObject("title")), txt(v.optJSONObject("shortBylineText")), "", thumbOf(v.optJSONObject("thumbnail") ?: v.optJSONArray("thumbnails")?.optJSONObject(0)),
            -1L, count(txt(v.optJSONObject("videoCountText"))), "", 2, "https://www.youtube.com/playlist?list=$id"))
    }
    private fun chanR(v: JSONObject, f: Acc) {
        val cid = v.optString("channelId"); if (cid.isEmpty()) return
        f.items.add(NpItem("", txt(v.optJSONObject("title")), "", "https://www.youtube.com/channel/$cid", thumbOf(v.optJSONObject("thumbnail")), -1L,
            count(txt(v.optJSONObject("videoCountText") ?: v.optJSONObject("subscriberCountText"))), "", 1, "https://www.youtube.com/channel/$cid"))
    }
    /** بوست: kind=3 (النص في title، الصورة في thumb، الإعجابات في count) */
    private fun postR(v: JSONObject, f: Acc, chUrl: String) {
        val pid = v.optString("postId"); if (pid.isEmpty()) return
        val att = v.optJSONObject("backstageAttachment")
        val img = thumbOf(att?.optJSONObject("backstageImageRenderer")?.optJSONObject("image")
            ?: att?.optJSONObject("postMultiImageRenderer")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("backstageImageRenderer")?.optJSONObject("image"))
        f.items.add(NpItem("", txt(v.optJSONObject("contentText")), txt(v.optJSONObject("authorText")), chUrl, img, -1L,
            count(txt(v.optJSONObject("voteCount"))), txt(v.optJSONObject("publishedTimeText")), 3, "https://www.youtube.com/post/$pid"))
    }

    // ---------- معرّف القناة ----------
    private fun browseId(url: String): String {
        Regex("/channel/(UC[A-Za-z0-9_-]{22})").find(url)?.let { return it.groupValues[1] }
        YtChanId.cachedOnly(url)?.takeIf { it.startsWith("UC") }?.let { return it }
        val r = post("navigation/resolve_url", JSONObject().put("url", url))
        return r.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.startsWith("UC") }
            ?: throw RuntimeException("مش قادر أعرف القناة")
    }

    // ---------- صفحة نتائج + continuation ----------
    private fun page(j: JSONObject, chName: String, chUrl: String): YtRes {
        val f = Acc(); walk(j, f, chName, chUrl)
        val seen = HashSet<String>()
        val items = f.items.filter { seen.add(if (it.id.isNotEmpty()) it.id else it.url) }
        return YtRes(items, f.token?.let { Page(null, it) })
    }
    private fun cont(p: Page, chName: String, chUrl: String): YtRes =
        page(post("browse", JSONObject().put("continuation", p.id ?: "")), chName, chUrl)

    private fun tabsOf(j: JSONObject): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val ts = j.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")?.optJSONArray("tabs") ?: return out
        for (i in 0 until ts.length()) {
            val t = ts.getJSONObject(i).optJSONObject("tabRenderer") ?: continue
            val ep = t.optJSONObject("endpoint")?.optJSONObject("browseEndpoint") ?: continue
            val params = ep.optString("params"); val url = t.optJSONObject("endpoint")?.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url") ?: ""
            val key = when {
                url.endsWith("/videos") -> "videos"; url.endsWith("/shorts") -> "shorts"; url.endsWith("/streams") -> "livestreams"
                url.endsWith("/playlists") -> "playlists"; url.endsWith("/community") || url.endsWith("/posts") -> "posts"; else -> continue
            }
            out.add(Triple(key, t.optString("title").ifEmpty { key }, params))
        }
        return out
    }
    private fun labelOf(key: String) = when (key) { "videos" -> "فيديوهات"; "shorts" -> "شورتس"; "livestreams" -> "بث مباشر"; "playlists" -> "قوائم تشغيل"; "posts" -> "بوستات"; else -> key }

    private fun loadTab(cid: String, params: String, name: String, url: String): YtRes =
        page(post("browse", JSONObject().put("browseId", cid).put("params", params)), name, url)

    override fun channel(url: String): NpChan {
        val full = channelFull(url)
        val first = full.loadTab(full.tabs.firstOrNull { it.key == "videos" }?.key ?: full.tabs.first().key)
        return NpChan(full.name, first.first, first.second)
    }

    override fun channelFull(url: String): NpChanFull {
        val cid = browseId(url)
        val j = post("browse", JSONObject().put("browseId", cid))
        val md = j.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")
        val name = md?.optString("title") ?: ""
        val chUrl = "https://www.youtube.com/channel/$cid"
        val tabs = tabsOf(j)
        val byKey = LinkedHashMap<String, String>(); for (t in tabs) byKey[t.first] = t.third
        if (byKey.isEmpty()) throw RuntimeException("مفيش تابات")
        val subs = Regex("\"subscriberCountText\":\\{[^}]*?\"(?:simpleText|content)\":\"([^\"]+)\"").find(j.toString())?.groupValues?.get(1)?.let { count(it) } ?: -1L
        val banner = Regex("\"banner\":\\{\"thumbnails\":\\[\\{\"url\":\"([^\"]+)\"").find(j.toString())?.groupValues?.get(1)
            ?: Regex("\"bannerViewModel\".*?\"url\":\"([^\"]+)\"").find(j.toString())?.groupValues?.get(1) ?: ""
        val load = { key: String ->
            val p = byKey[key] ?: byKey.values.first()
            val first = loadTab(cid, p, name, chUrl)
            Pair(first, { pg: Page -> cont(pg, name, chUrl) })
        }
        return NpChanFull(chUrl, name, thumbOf(md?.optJSONObject("avatar")), banner, subs, md?.optString("description") ?: "",
            j.toString().contains("BADGE_STYLE_TYPE_VERIFIED"), byKey.keys.map { NpChanTab(it, labelOf(it)) }, load)
    }

    override fun searchFirst(q: String, filter: String): YtRes {
        val b = JSONObject().put("query", q)
        when (filter) { "videos" -> b.put("params", "EgIQAQ%3D%3D"); "channels" -> b.put("params", "EgIQAg%3D%3D"); "playlists" -> b.put("params", "EgIQAw%3D%3D") }
        return page(post("search", b), "", "")
    }
    override fun searchMore(q: String, filter: String, p: Page): YtRes =
        page(post("search", JSONObject().put("continuation", p.id ?: "")), "", "")
}
