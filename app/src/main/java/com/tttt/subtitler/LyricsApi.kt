package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * (v250) أداة lyrics-api (Musixmatch + YouTube Music) متنقّلة لـ Kotlin وشغّالة جوه التطبيق مباشرة — من غير سيرفر وسيط ولا مفاتيح.
 *  • Musixmatch: توكن مؤقت من تطبيق الديسكتوب ← بحث ← كلمات متزامنة (LRC) أو عادية
 *  • YouTube Music: بحث ← تبويب Lyrics ← كلمات عادية (من غير توقيت)
 * أي فشل (توكن مرفوض / شبكة / شكل رد اتغيّر) بيطلع استثناء والمتصل بيتعامل معاه — مفيش حاجة هنا بتكسر باقي المصادر.
 */
object LyricsApi {
    /** نتيجة جاهزة: synced = نص LRC (أو فاضي) · plain = كلمات عادية (أو فاضي) · dur بالثواني (0 = مجهولة) */
    class Hit(val src: String, val id: String, val title: String, val artist: String, val synced: String, val plain: String, val dur: Double)

    private const val MXM = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private const val APP = "app_id=web-desktop-app-v1.0"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    @Volatile private var tok = ""
    @Volatile private var tokExp = 0L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(u: String, cookie: String = "AWSELBCORS=0; AWSELB=0;", lang: String = ""): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Cookie", cookie)
            if (lang.isNotEmpty()) c.setRequestProperty("Accept-Language", lang)
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    // ===================== Musixmatch =====================
    @Synchronized private fun token(force: Boolean = false): String {
        if (!force && tok.isNotEmpty() && System.currentTimeMillis() < tokExp) return tok
        val m = JSONObject(get(MXM + "token.get?" + APP)).optJSONObject("message") ?: throw IOException("Musixmatch: رد غير مفهوم")
        if (m.optJSONObject("header")?.optInt("status_code", 0) != 200) throw IOException("Musixmatch رفض التوكن")
        val t = m.optJSONObject("body")?.optString("user_token").orEmpty()
        if (t.isBlank() || t.startsWith("UpgradeOnly")) throw IOException("Musixmatch رفض التوكن")
        tok = t; tokExp = System.currentTimeMillis() + 25 * 60_000L
        return t
    }

    /** نداء Musixmatch: بيرجّع body أو null لو الحالة مش 200 (مفيش نتيجة) */
    private fun mxm(method: String, params: String): JSONObject? {
        val raw = try { get(MXM + method + "?" + APP + "&usertoken=" + enc(token()) + "&" + params) }
        catch (e: IOException) { tok = ""; throw e }
        val m = JSONObject(raw).optJSONObject("message") ?: return null
        if (m.optJSONObject("header")?.optInt("status_code", 0) != 200) return null
        return m.optJSONObject("body")
    }

    private fun mxmSearch(title: String, artist: String): List<JSONObject> {
        val q = "q_track=" + enc(title) + (if (artist.isNotBlank()) "&q_artist=" + enc(artist) else "")
        val b = mxm("track.search", "page_size=5&page=1&s_track_rating=desc&quorum_factor=1.0&f_has_lyrics=1&$q") ?: return emptyList()
        val a = b.optJSONArray("track_list") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("track")?.let { out.add(it) }
        return out
    }

    private fun mxmSubtitle(id: String): String {
        val b = mxm("track.subtitle.get", "subtitle_format=lrc&track_id=" + enc(id)) ?: return ""
        return b.optJSONObject("subtitle")?.optString("subtitle_body").orEmpty()
    }

    private fun mxmPlain(id: String): String {
        val b = mxm("track.lyrics.get", "track_id=" + enc(id)) ?: return ""
        var t = b.optJSONObject("lyrics")?.optString("lyrics_body").orEmpty()
        val i = t.indexOf("*******")          // ذيل النسخة المجانية («This Lyrics is NOT for Commercial use»)
        if (i >= 0) t = t.substring(0, i)
        return t.trim()
    }

    /**
     * بيدوّر في Musixmatch ويرجّع لحد 2 نتيجة مقبولة (accept بتقرر من الاسم/الفنان/المدة).
     * متزامنة لو الأغنية ليها LRC، وإلا كلمات عادية.
     */
    fun mxmFind(title: String, artist: String, accept: (String, String, Double) -> Boolean): List<Hit> {
        val out = ArrayList<Hit>()
        for (o in mxmSearch(title, artist)) {
            if (out.size >= 2) break
            if (o.optInt("instrumental", 0) == 1) continue
            val tt = o.optString("track_name"); val aa = o.optString("artist_name"); val d = o.optDouble("track_length", 0.0)
            if (!accept(tt, aa, if (d.isNaN()) 0.0 else d)) continue
            val id = o.optString("track_id")
            if (id.isBlank()) continue
            var syn = ""
            if (o.optInt("has_subtitles", 0) == 1) syn = try { mxmSubtitle(id) } catch (_: Throwable) { "" }
            var plain = ""
            if (syn.isBlank() && o.optInt("has_lyrics", 0) == 1) plain = try { mxmPlain(id) } catch (_: Throwable) { "" }
            if (syn.isBlank() && plain.isBlank()) continue
            out.add(Hit("musixmatch", id, tt, aa, syn, plain, if (d.isNaN()) 0.0 else d))
        }
        return out
    }

    // ===================== YouTube Music =====================
    private class YtCtx(val key: String, val name: String, val ver: String)
    @Volatile private var yt: YtCtx? = null

    private fun ytCtx(): YtCtx {
        yt?.let { return it }
        val html = get("https://music.youtube.com/", "CONSENT=YES+1; SOCS=CAI", "en-US,en;q=0.9")
        fun x(p: String) = Regex(p).find(html)?.groupValues?.get(1)
        val k = x("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
        val n = x("\"INNERTUBE_CLIENT_NAME\":\"([^\"]+)\"")
        val v = x("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"")
        if (k == null || n == null || v == null) throw IOException("YouTube Music: مفتاح الواجهة مش لاقيه")
        return YtCtx(k, n, v).also { yt = it }
    }

    private fun ytPost(path: String, body: JSONObject): JSONObject {
        val cx = ytCtx()
        val c = URL("https://music.youtube.com/youtubei/v1/$path?key=" + cx.key).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
            c.setRequestProperty("X-YouTube-Client-Name", if (cx.name == "WEB_REMIX") "67" else cx.name)
            c.setRequestProperty("X-YouTube-Client-Version", cx.ver)
            c.setRequestProperty("X-Origin", "https://music.youtube.com")
            body.put("context", JSONObject().put("client", JSONObject().put("clientName", cx.name).put("clientVersion", cx.ver).put("hl", "en")))
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            if (code !in 200..299) { yt = null; throw IOException("HTTP $code") }
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { c.disconnect() }
    }

    /** كل (نص، videoId) في الرد — الأول عادةً هو أقرب أغنية */
    private fun collectVideos(n: Any?, out: MutableList<Pair<String, String>>) {
        when (n) {
            is JSONObject -> {
                val vid = n.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
                val tx = n.opt("text")
                if (vid.isNotEmpty() && tx is String && tx.isNotBlank() && out.none { it.second == vid }) out.add(tx to vid)
                val ks = n.keys(); while (ks.hasNext()) collectVideos(n.opt(ks.next()), out)
            }
            is JSONArray -> for (i in 0 until n.length()) collectVideos(n.opt(i), out)
        }
    }

    private fun findLyricsBrowse(n: Any?): String? {
        when (n) {
            is JSONObject -> {
                if (n.opt("title") == "Lyrics") {
                    val id = n.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                    if (id.isNotEmpty()) return id
                }
                val ks = n.keys(); while (ks.hasNext()) { val r = findLyricsBrowse(n.opt(ks.next())); if (r != null) return r }
            }
            is JSONArray -> for (i in 0 until n.length()) { val r = findLyricsBrowse(n.opt(i)); if (r != null) return r }
        }
        return null
    }

    private fun findShelf(n: Any?): JSONObject? {
        when (n) {
            is JSONObject -> {
                n.optJSONObject("musicDescriptionShelfRenderer")?.let { return it }
                val ks = n.keys(); while (ks.hasNext()) { val r = findShelf(n.opt(ks.next())); if (r != null) return r }
            }
            is JSONArray -> for (i in 0 until n.length()) { val r = findShelf(n.opt(i)); if (r != null) return r }
        }
        return null
    }

    /** كلمات عادية من YouTube Music. accept(نص النتيجة) بتتأكد إن الأغنية هي المطلوبة (بنجرّب أول 3 نتايج) */
    fun ytFind(title: String, artist: String, accept: (String) -> Boolean): Hit? {
        val s = ytPost("search", JSONObject().put("query", (title + " " + artist).trim()).put("params", "EgWKAQIIAWoKEAMQBRAKEAkQBA=="))
        val vids = ArrayList<Pair<String, String>>(); collectVideos(s, vids)
        for ((text, vid) in vids.take(3)) {
            if (!accept(text)) continue
            val nx = ytPost("next", JSONObject().put("videoId", vid))
            val browse = findLyricsBrowse(nx) ?: continue
            val br = ytPost("browse", JSONObject().put("browseId", browse))
            val runs = findShelf(br)?.optJSONObject("description")?.optJSONArray("runs") ?: continue
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            val ly = sb.toString().replace("\r", "").lines().filterNot { it.trim().startsWith("Source:", true) }.joinToString("\n").trim()
            if (ly.lines().count { it.isNotBlank() } >= 4) return Hit("youtube", vid, text, artist, "", ly, 0.0)
        }
        return null
    }

    /** (v268) غلاف من YouTube Music من غير مفتاح: نتايج «أغاني» فيها صورة الألبوم المربّعة. كل عنصر = (عنوان، سطر التفاصيل «فنان • ألبوم»، رابط الصورة بحجم 600) */
    class YtCover(val title: String, val info: String, val url: String)

    private fun collectCovers(n: Any?, out: MutableList<YtCover>) {
        when (n) {
            is JSONObject -> {
                val r = n.optJSONObject("musicResponsiveListItemRenderer")
                if (r != null) try {
                    val cols = r.optJSONArray("flexColumns")
                    fun runsOf(i: Int): String {
                        val runs = cols?.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
                        val sb = StringBuilder(); for (k in 0 until runs.length()) sb.append(runs.optJSONObject(k)?.optString("text").orEmpty()); return sb.toString()
                    }
                    val th = r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val u = th?.optJSONObject(th.length() - 1)?.optString("url").orEmpty()
                    // صور الأغاني الحقيقية على googleusercontent (مربّعة)؛ الفيديوهات على ytimg (16:9) فبنتجاهلها
                    if (u.contains("googleusercontent.com") || u.contains("ggpht.com")) {
                        val big = u.replace(Regex("=w\\d+-h\\d+[^\\s]*$"), "=w600-h600-l90-rj")
                        out.add(YtCover(runsOf(0), runsOf(1), big))
                    }
                } catch (_: Throwable) {}
                val ks = n.keys(); while (ks.hasNext()) collectCovers(n.opt(ks.next()), out)
            }
            is JSONArray -> for (i in 0 until n.length()) collectCovers(n.opt(i), out)
        }
    }

    fun ytCovers(title: String, artist: String): List<YtCover> {
        val s = ytPost("search", JSONObject().put("query", (title + " " + artist).trim()).put("params", "EgWKAQIIAWoKEAMQBRAKEAkQBA=="))
        val out = ArrayList<YtCover>(); collectCovers(s, out); return out.take(8)
    }
}
