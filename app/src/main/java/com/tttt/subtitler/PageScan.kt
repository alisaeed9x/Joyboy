package com.tttt.subtitler

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** (v837) نتيجة فحص الصفحة. cat: 0 فيديو مباشر · 1 ملف/برنامج · 2 لينك موقع رفع (بيتحوّل لرابط مباشر) · 3 صفحة فيديو/مشغّل مدمج */
class ScanItem(val cat: Int, val url: String, val title: String, val name: String, val ext: String = "", val size: Long = -1L, val viaLink: Boolean = false) {
    /** (v1064) رابط صورة مصغّرة للعنصر (poster / صورة الكارت اللي حوالين اللينك) — فاضي لو ملقيناش */
    var img: String = ""
}

/** (v837) رابط مباشر اتجاب من لينك موقع رفع */
class ScanResolved(val url: String, val name: String)

/**
 * (v837) زرار 🔎 فحص الصفحة — منطق من غير UI:
 *  - DOM_JS بيلم كل اللينكات والفيديوهات والـiframes من الصفحة، وclassify بيصنّفهم (فيديو · ملف/برنامج · موقع رفع · صفحة فيديو)
 *  - PLAY_JS بيشغّل فيديوهات الصفحة مكتومة عشان طلباتها تتلقط من غير ما المستخدم يدوس على الفيديو
 *  - resolve بيحوّل لينك MediaFire / Dropbox / Google Drive لرابط تحميل مباشر
 */
object PageScan {
    const val C_VIDEO = 0
    const val C_FILE = 1
    const val C_HOST = 2
    const val C_PAGE = 3

    const val SRV_TAG = "سيرفر: "

    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** بيشغّل <video> مكتوم + بيدوس على أزرار تشغيل المشغّلات المعروفة بس (مش أي زرار اسمه play) */
    val PLAY_JS = """(function(){try{var n=0;
document.querySelectorAll('video').forEach(function(v){try{v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});n++;}catch(e){}});
['.vjs-big-play-button','.jw-icon-display','.plyr__control--overlaid','.ytp-large-play-button','.mejs__overlay-button','.fp-play','[aria-label="Play"]','[aria-label="تشغيل"]'].forEach(function(s){
document.querySelectorAll(s).forEach(function(b){try{if(b.offsetWidth>0&&n<6){b.click();n++;}}catch(e){}});});
return n;}catch(e){return -1}})()"""

    private val FILE_EXT = setOf("apk", "xapk", "apks", "aab", "exe", "msi", "zip", "rar", "7z", "tar", "gz", "tgz", "iso", "dmg", "pkg", "deb", "rpm", "jar",
        "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "torrent", "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "srt", "ass", "vtt", "sub")
    private val PAGE_RX = Regex("""(^|/)(watch|video|videos|embed|play|player|episode|ep|movie|movies|film|films|series|clip|مشاهدة|فيلم|مسلسل|حلقة)([/?._-]|$)""", RegexOption.IGNORE_CASE)
    private val PAGE_HOSTS = listOf("youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "streamable.com", "ok.ru", "vk.com", "fb.watch", "tiktok.com")
    private val PLAYER_HINT = Regex("(embed|player|video|stream|iframe|vidoza|streamtape|dood|mixdrop|upstream|voe|filemoon|vidmoly)", RegexOption.IGNORE_CASE)

    fun extOf(u: String): String {
        val p = u.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return if (p.contains('.')) p.substringAfterLast('.').lowercase() else ""
    }
    fun hostOf(u: String): String = try { (URL(u).host ?: "").lowercase().removePrefix("www.") } catch (_: Throwable) { "" }
    private fun pathOf(u: String): String = try { URL(u).path ?: "" } catch (_: Throwable) { "" }
    fun niceName(u: String): String {
        val n = u.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val d = try { URLDecoder.decode(n, "UTF-8") } catch (_: Throwable) { n }
        return if (d.isNotBlank()) d else hostOf(u)
    }
    private fun hostIs(h: String, d: String) = h == d || h.endsWith(".$d")

    fun isMediafire(u: String): Boolean {
        if (!hostIs(hostOf(u), "mediafire.com")) return false
        val p = pathOf(u)
        return p.startsWith("/file/") || p.startsWith("/file_premium/") || p.startsWith("/view/") || p.startsWith("/download/")
    }
    fun isDropbox(u: String): Boolean { val p = pathOf(u); return hostIs(hostOf(u), "dropbox.com") && (p.startsWith("/s/") || p.startsWith("/scl/") || p.startsWith("/sh/")) }
    fun driveId(u: String): String? {
        if (hostOf(u) != "drive.google.com") return null
        return Regex("""/file/d/([\w-]+)""").find(u)?.groupValues?.get(1) ?: Regex("""[?&]id=([\w-]+)""").find(u)?.groupValues?.get(1)
    }
    fun isHostLink(u: String) = isMediafire(u) || isDropbox(u) || driveId(u) != null
    /** يوتيوب وما شابه: مش بيتسحب بصفحة خلفية، بيروح للموزّع الموحّد (Links.sheet) */
    fun needsDispatcher(u: String): Boolean = YtExtract.videoId(u) != null || hostIs(hostOf(u), "tiktok.com")

    /** عنصر واحد من الصفحة → تصنيف أو null (مش مهم) */
    fun classify(u: String, t: String, k: String, page: String): ScanItem? {
        if (!u.startsWith("http", true) || u.substringBefore('#') == page.substringBefore('#')) return null
        if (AdBlock.blocked(u)) return null
        val ext = extOf(u)
        val nm = niceName(u)
        if (k == "m") return ScanItem(C_VIDEO, u, t, nm, ext)
        if (Sniff.isDirect(u)) return ScanItem(C_VIDEO, u, t, nm, ext, -1L, true)   // (v841) لينك <a> لفيديو (مش عنصر video في الصفحة): الفحص اليدوي بس بيضيفه للاصطياد
        if (isHostLink(u)) return ScanItem(C_HOST, u, t, if (isMediafire(u)) mediafireName(u) else nm, ext)
        if (ext in FILE_EXT) return ScanItem(C_FILE, u, t, nm, ext)
        val h = hostOf(u)
        val dp = try { URLDecoder.decode(pathOf(u), "UTF-8") } catch (_: Throwable) { pathOf(u) }
        if (k == "s") return ScanItem(C_PAGE, u, if (t.startsWith(SRV_TAG)) t else SRV_TAG + t, nm, "")   // (v850) سيرفر كسول (data-*) مش iframe متحمّل
        if (k == "f") return if (PAGE_HOSTS.any { hostIs(h, it) } || PLAYER_HINT.containsMatchIn(h + dp)) ScanItem(C_PAGE, u, t.ifBlank { "مشغّل مدمج" }, nm, "") else null
        val yt = hostIs(h, "youtube.com") || h == "youtu.be"
        if (yt) return if (YtExtract.videoId(u) != null) ScanItem(C_PAGE, u, t, t.ifBlank { nm }, "") else null
        if (PAGE_HOSTS.any { hostIs(h, it) } || PAGE_RX.containsMatchIn(dp)) return ScanItem(C_PAGE, u, t, t.ifBlank { nm }, "")
        return null
    }
    /** mediafire.com/file/<id>/<name>/file ← الاسم من اللينك */
    private fun mediafireName(u: String): String {
        val seg = pathOf(u).split('/').filter { it.isNotBlank() }
        val n = seg.getOrNull(2) ?: return niceName(u)
        return try { URLDecoder.decode(n, "UTF-8") } catch (_: Throwable) { n }
    }

    /** JSON من DOM_JS → عناصر مصنّفة من غير تكرار، مترتبة (فيديو · موقع رفع · ملف · صفحة) وبحد أقصى لكل نوع */
    fun parse(json: String, page: String): List<ScanItem> {
        val out = ArrayList<ScanItem>()
        val seen = HashSet<String>()
        val arr = try { JSONArray(json) } catch (_: Throwable) { return out }
        for (i in 0 until arr.length()) {
            val a = try { arr.getJSONArray(i) } catch (_: Throwable) { continue }
            val u = a.optString(0); val t = a.optString(1); val k = a.optString(2); val g = a.optString(3)
            if (u.isEmpty() || !seen.add(Sniff.key(u))) continue
            classify(u, t, k, page)?.let { it.img = g; if (g.isNotEmpty() && it.cat == C_VIDEO) posters[Sniff.key(u)] = g; out.add(it) }
        }
        val cap = mapOf(C_VIDEO to 30, C_HOST to 15, C_FILE to 40, C_PAGE to 25)
        return out.groupBy { it.cat }.toSortedMap().flatMap { (c, l) -> l.take(cap[c] ?: 20) }
    }

    // ===== (v1064) صور مصغّرة لعناصر الفحص =====
    /** (v1064) poster اللي الفحص لقاه لكل لينك فيديو في الصفحة (مفتاح Sniff.key) — بتستخدمه قايمة الفيديوهات الملقوطة */
    val posters = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val thumbCache = object : android.util.LruCache<String, android.graphics.Bitmap>(24) {}
    /** يجيب صورة مصغّرة (فك مصغّر ~320px) — على thread خلفي. null لو فشل. بيبعت Referer الصفحة عشان المواقع اللي بتمنع الصور من برّا */
    fun fetchThumb(u: String, ua: String, ref: String): android.graphics.Bitmap? {
        if (!u.startsWith("http", true)) return null
        thumbCache.get(u)?.let { return it }
        var c: HttpURLConnection? = null
        return try {
            c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 7000; c.readTimeout = 9000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua.ifBlank { UA }); if (ref.startsWith("http", true)) c.setRequestProperty("Referer", ref)
            c.setRequestProperty("Accept", "image/*,*/*;q=0.5")
            if (c.responseCode !in 200..299) return null
            val b = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var tot = 0
            c.inputStream.use { st -> while (true) { val n = st.read(buf); if (n <= 0) break; tot += n; if (tot > 3_000_000) return null; b.write(buf, 0, n) } }
            val data = b.toByteArray()
            val bo = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bo)
            var ss = 1; while (bo.outWidth / (ss * 2) >= 320) ss *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
            if (bmp != null) thumbCache.put(u, bmp)
            bmp
        } catch (_: Throwable) { null } finally { try { c?.disconnect() } catch (_: Throwable) {} }
    }

    // ===== تحويل لينكات مواقع الرفع لرابط مباشر (على thread خلفي) =====
    private class Page(val html: String?, val finalUrl: String)
    private fun get(u: String, ua: String): Page? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 9000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua); c.setRequestProperty("Accept-Language", "en,ar;q=0.8")
        c.connect()
        val ct = (c.contentType ?: "").lowercase(); val fin = c.url.toString()
        if (!ct.contains("html") && !ct.startsWith("text")) { c.disconnect(); Page(null, fin) }   // اللينك نفسه ملف
        else {
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
            c.inputStream.use { ins -> while (out.size() < 1_500_000) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
            c.disconnect(); Page(String(out.toByteArray(), Charsets.UTF_8), fin)
        }
    } catch (_: Throwable) { null }

    private fun decodeEnt(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
    private fun attr(tag: String, name: String): String? {
        val m = Regex("""\b""" + Regex.escape(name) + """\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return decodeEnt(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }
    private val MF_DIRECT = Regex("""^https?://download\d*\.mediafire\.com/""", RegexOption.IGNORE_CASE)

    /** MediaFire: زرار التحميل إما href مباشر أو data-scrambled-url (base64)؛ وأخيرًا أي رابط downloadNNN.mediafire.com في الصفحة */
    fun mediafire(html: String): String? {
        for (m in Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            if (!tag.contains("downloadButton", true) && !tag.contains("Download file", true) && !tag.contains("popsok", true) && !tag.contains("data-scrambled-url", true)) continue
            attr(tag, "data-scrambled-url")?.let { s ->
                val d = try { String(java.util.Base64.getDecoder().decode(s.trim())) } catch (_: Throwable) { "" }
                if (MF_DIRECT.containsMatchIn(d)) return d
            }
            attr(tag, "href")?.let { if (MF_DIRECT.containsMatchIn(it)) return it }
        }
        return Regex("""https?://download\d*\.mediafire\.com/[^"'\s<>\\]+""").find(html)?.value?.let { decodeEnt(it) }
    }

    fun resolve(u: String, ua: String = UA): ScanResolved? {
        if (isMediafire(u)) {
            val pg = get(u, ua) ?: return null
            if (pg.html == null) return ScanResolved(pg.finalUrl, niceName(pg.finalUrl))
            val d = mediafire(pg.html) ?: return null
            return ScanResolved(d, niceName(d))
        }
        if (isDropbox(u)) {
            val x = if (Regex("""[?&]dl=\d""").containsMatchIn(u)) u.replace(Regex("""([?&])dl=\d"""), "\$1dl=1") else u + (if (u.contains('?')) "&" else "?") + "dl=1"
            return ScanResolved(x, niceName(u))
        }
        driveId(u)?.let { id -> return ScanResolved("https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t", "drive_$id") }
        return null
    }

    // ===== (v838) التأكد إن اللينك ملف حقيقي (مش صفحة ولا إعلان ولا دوّامة تحويلات) =====
    /** ملف اتأكدنا منه: الرابط النهائي بعد التحويلات · الاسم · الحجم (-1 = مش معروف) · النوع */
    class RealFile(val url: String, val name: String, val size: Long, val mime: String, val ext: String)

    private val HTMLISH = Regex("^(text/html|application/xhtml|text/xml|application/xml|application/json|text/javascript|application/javascript)", RegexOption.IGNORE_CASE)
    private val SUBS = setOf("srt", "ass", "vtt", "sub")

    /** الاسم من هيدر Content-Disposition (filename* ثم filename="…" ثم filename=…) أو null */
    fun nameFromDisposition(d: String?): String? {
        if (d.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*[\\w-]*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(d)?.let { m ->
            val raw = m.groupValues[1].trim().trim('"')
            return try { URLDecoder.decode(raw, "UTF-8") } catch (_: Throwable) { raw }
        }
        Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(d)?.let { m -> return m.groupValues[1].ifBlank { null } }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(d)?.let { m -> return m.groupValues[1].trim().ifBlank { null } }
        return null
    }

    /**
     * رد السيرفر ده ملف حقيقي ولا لأ؟ (من غير تحميل: كود الرد + النوع + Content-Disposition + الحجم)
     *  - صفحة HTML/JSON من غير attachment = لأ (صفحة تحميل / فحص فيروسات / خطأ / إعلان)
     *  - حجم صغير جدًا (أقل من 1KB، وللترجمة أقل من 20 بايت) من غير attachment = لأ (صفحة خطأ)
     *  - attachment = آه · صوت/فيديو/application غير HTML = آه · غير كده بس لو الامتداد معروف
     */
    fun realVerdict(code: Int, mime: String, disp: String, len: Long, ext: String): Boolean {
        if (code !in 200..299) return false
        val m = mime.lowercase().substringBefore(';').trim()
        val att = disp.contains("attachment", true)
        val tiny = if (ext in SUBS) 20L else 1024L
        if (len in 1 until tiny && !att) return false
        if (HTMLISH.containsMatchIn(m)) return att
        if (att) return true
        if (m.startsWith("audio/") || m.startsWith("video/") || m.startsWith("application/")) return true
        if (m.startsWith("image/")) return false   // لينك ملف بيرجّع صورة = صورة بديلة/إعلان
        return ext in FILE_EXT
    }

    /**
     * بيتأكد إن اللينك ملف حقيقي: GET بنطاق 0-0 (مفيش تحميل)، بيتبع التحويلات بنفسه لحد 6 (أكتر من كده = دوّامة = null)
     * ويرجّع الرابط النهائي والاسم والحجم. على thread خلفي بس.
     */
    fun probeReal(u: String, ua: String, ref: String, cookieFor: (String) -> String): RealFile? {
        var cur = u
        for (hop in 0 until 6) {
            val c = try { URL(cur).openConnection() as HttpURLConnection } catch (_: Throwable) { return null }
            try {
                c.connectTimeout = 7000; c.readTimeout = 7000; c.instanceFollowRedirects = false; c.requestMethod = "GET"
                c.setRequestProperty("Range", "bytes=0-0")
                c.setRequestProperty("User-Agent", ua.ifBlank { UA })
                if (ref.isNotEmpty()) c.setRequestProperty("Referer", ref)
                val ck = try { cookieFor(cur) } catch (_: Throwable) { "" }
                if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = c.getHeaderField("Location")
                    if (loc.isNullOrBlank()) return null
                    cur = URL(URL(cur), loc).toString()
                    continue
                }
                val mime = c.contentType ?: ""
                val disp = c.getHeaderField("Content-Disposition") ?: ""
                var len = c.contentLengthLong
                val cr = c.getHeaderField("Content-Range")
                val tot = cr?.substringAfter('/', "")?.trim()?.toLongOrNull()
                if (tot != null && tot > 0) len = tot else if (code == 206) len = -1L
                val name = nameFromDisposition(disp) ?: niceName(cur)
                val ext = extOf(name).ifEmpty { extOf(cur) }
                if (!realVerdict(code, mime, disp, len, ext)) return null
                return RealFile(cur, name, len, mime.substringBefore(';').trim(), ext)
            } catch (_: Throwable) {
                return null
            } finally {
                try { c.disconnect() } catch (_: Throwable) {}
            }
        }
        return null
    }
}
