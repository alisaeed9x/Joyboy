package com.tttt.subtitler

import java.net.HttpURLConnection
import java.net.URL

/**
 * (v851) صيّاد الروابط الحقيقية: بيفحص كود الجافاسكريبت بتاع الصفحة (inline + ملفات خارجية) ويدوّر على لينكات الفيديو.
 * بيفك: \/ و \u0026 و \x2f · eval(function(p,a,c,k,e,d)) المضغوط · atob/base64.
 * ولو الصفحة بتحمّل المشغّل من موقع خارجي (iframe / سيرفر) بيدخله بـReferer الصفحة ويكرّر الفحص جواه (لحد مستويين).
 * HTTP بس (من غير WebView): اللي مش ظاهر لحد ما الجافا تشتغل بيرجع في `embeds` والمتصفح بيفتحه في الخلفية.
 * لازم يشتغل على thread خلفي.
 */
object StreamHunt {
    class Hit(val url: String, val ref: String)
    class Result(val hits: List<Hit>, val embeds: List<Pair<String, String>>)   // embeds = (رابط المشغّل، الصفحة اللي لقتّه = Referer)

    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val MAX_FETCH = 14
    private const val MAX_MS = 14_000L
    private const val MAX_BYTES = 1_500_000

    private val QUOTED_MEDIA = Regex("""["'`]((?:https?:)?//[^"'`\s<>]+?\.(?:m3u8|mp4|mpd|webm|mkv|m4v)(?:\?[^"'`\s<>]*)?|/[^"'`\s<>]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^"'`\s<>]*)?)["'`]""", RegexOption.IGNORE_CASE)
    private val BARE_MEDIA = Regex("""https?://[^\s"'`<>\\)\]}]+?\.(?:m3u8|mp4|mpd|webm|mkv|m4v)(?:\?[^\s"'`<>\\)\]}]*)?""", RegexOption.IGNORE_CASE)
    private val IFRAME = Regex("""<(?:iframe|embed)\b[^>]*?\b(?:src|data-src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val DATA_EMBED = Regex("""\bdata-(?:embed-url|embed|iframe|frame|player|stream|server)\s*=\s*["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val IFR_JS = Regex("""["'`]((?:https?:)?//[^"'`\s<>]*?(?:embed|player|iframe|stream)[^"'`\s<>]*)["'`]""", RegexOption.IGNORE_CASE)
    private val SCRIPT_SRC = Regex("""<script\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATOB = Regex("""atob\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""")
    private val B64_LIT = Regex("""["']([A-Za-z0-9+/]{40,}={0,2})["']""")
    private val PACK = Regex("""\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\(\s*'\|'\s*\)""", RegexOption.DOT_MATCHES_ALL)
    private val WORD = Regex("""\b\w+\b""")
    private val SKIP_JS = Regex("""jquery|bootstrap|gtag|analytics|adsbygoogle|fingerprint|polyfill|swiper|slick|popper|moment|lodash|fontawesome|recaptcha|googletag|facebook|twitter|disqus|cloudflare-static|histats|cookie""", RegexOption.IGNORE_CASE)
    private val SKIP_EMBED = Regex("""youtube\.com/embed|youtu\.be|facebook\.com|twitter\.com|google\.com/recaptcha|googletagmanager|doubleclick|disqus|about:blank|\.(?:js|css|png|jpe?g|gif|svg|webp|ico|woff2?)(?:\?|$)""", RegexOption.IGNORE_CASE)

    // ====== فك الضغط والتشفير ======
    private fun idx(w: String, radix: Int): Int {
        var n = 0
        for (c in w) {
            val d = when (c) { in '0'..'9' -> c - '0'; in 'a'..'z' -> c - 'a' + 10; in 'A'..'Z' -> c - 'A' + 36; else -> return -1 }
            if (d >= radix) return -1
            n = n * radix + d
            if (n > 1_000_000) return -1
        }
        return n
    }

    /** Dean Edwards packer: بيرجّع النص بعد فك كل eval(function(p,a,c,k,e,d)…) (لحد 3 طبقات) */
    fun unpack(src: String): String {
        var cur = src
        val out = StringBuilder()
        repeat(3) {
            var changed = false
            val sb = StringBuilder()
            for (m in PACK.findAll(cur).take(6)) {
                try {
                    val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
                    val radix = m.groupValues[2].toInt().coerceIn(2, 62)
                    val dict = m.groupValues[4].replace("\\'", "'").split("|")
                    val dec = WORD.replace(payload) { w -> val i = idx(w.value, radix); if (i >= 0 && i < dict.size && dict[i].isNotEmpty()) dict[i] else w.value }
                    sb.append(dec).append('\n'); changed = true
                } catch (_: Throwable) {}
            }
            if (!changed) return@repeat
            out.append(sb); cur = sb.toString()
        }
        return out.toString()
    }

    private fun norm(s: String): String = s
        .replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/").replace("\\x2F", "/").replace("\\x2f", "/")
        .replace("\\u0026", "&").replace("\\x26", "&").replace("&amp;", "&").replace("\\u003D", "=").replace("\\u003d", "=")

    private fun b64(s: String): String? {
        for (fl in intArrayOf(android.util.Base64.DEFAULT, android.util.Base64.URL_SAFE)) {
            try {
                val d = String(android.util.Base64.decode(s, fl), Charsets.UTF_8)
                if (d.length >= 8 && d.all { it.code in 9..126 || it.code > 160 }) return d
            } catch (_: Throwable) {}
        }
        return null
    }

    /** نص واحد (HTML أو JS) ← نص موسّع فيه الجافا المفكوكة وقيم base64 المفكوكة */
    private fun expand(text: String): String {
        val sb = StringBuilder(norm(text))
        val un = unpack(text)
        if (un.isNotEmpty()) sb.append('\n').append(norm(un))
        var n = 0
        for (m in ATOB.findAll(text)) { if (n++ >= 12) break; b64(m.groupValues[1])?.let { sb.append('\n').append(norm(it)) } }
        for (m in B64_LIT.findAll(text)) { if (n++ >= 20) break; b64(m.groupValues[1])?.takeIf { it.contains("http", true) || it.contains(".m3u8", true) || it.contains(".mp4", true) }?.let { sb.append('\n').append(norm(it)) } }
        return sb.toString()
    }

    private fun abs(base: String, x: String): String? = try {
        val y = x.trim()
        if (y.startsWith("//")) "https:$y" else URL(URL(base), y).toString()
    } catch (_: Throwable) { null }

    class Scan(val media: List<String>, val embeds: List<String>, val scripts: List<String>)

    fun scan(text: String, base: String): Scan {
        val t = expand(text)
        val media = LinkedHashSet<String>()
        for (m in QUOTED_MEDIA.findAll(t)) abs(base, m.groupValues[1])?.let { Sniff.accept(it)?.let { a -> media.add(a) } }
        for (m in BARE_MEDIA.findAll(t)) Sniff.accept(m.value)?.let { media.add(it) }
        val emb = LinkedHashSet<String>()
        for (m in IFRAME.findAll(t)) abs(base, m.groupValues[1])?.let { if (it.startsWith("http", true) && !SKIP_EMBED.containsMatchIn(it) && !AdBlock.blocked(it)) emb.add(it) }
        for (m in DATA_EMBED.findAll(t)) abs(base, m.groupValues[1])?.let { if (!SKIP_EMBED.containsMatchIn(it) && !AdBlock.blocked(it)) emb.add(it) }
        for (m in IFR_JS.findAll(t)) abs(base, m.groupValues[1])?.let { if (it.startsWith("http", true) && !SKIP_EMBED.containsMatchIn(it) && !AdBlock.blocked(it) && Sniff.accept(it) == null && PageScan.hostOf(it) != PageScan.hostOf(base)) emb.add(it) }
        val js = LinkedHashSet<String>()
        for (m in SCRIPT_SRC.findAll(text)) abs(base, m.groupValues[1])?.let { if (it.startsWith("http", true) && !SKIP_JS.containsMatchIn(it) && !AdBlock.blocked(it)) js.add(it) }
        return Scan(media.toList(), emb.toList(), js.toList())
    }

    // ====== HTTP ======
    private fun get(u: String, ref: String, ua: String): Pair<String, String>? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 7000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua.ifBlank { UA }); c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
        if (ref.isNotEmpty()) { c.setRequestProperty("Referer", ref); c.setRequestProperty("Origin", ref.substringBefore("://") + "://" + PageScan.hostOf(ref)) }
        c.connect()
        val ct = (c.contentType ?: "").lowercase(); val fin = c.url.toString()
        if (c.responseCode >= 400 || ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("mpegurl") || ct.startsWith("image/")) { c.disconnect(); null }
        else {
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
            c.inputStream.use { ins -> while (out.size() < MAX_BYTES) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
            c.disconnect(); Pair(String(out.toByteArray(), Charsets.UTF_8), fin)
        }
    } catch (_: Throwable) { null }

    /**
     * page = صفحة الموقع · seedText = جافا inline اتجمعت من الصفحة الحيّة (اختياري) · seedScripts / seedEmbeds = من الـDOM الحي (اختياري)
     * بيرجّع الفيديوهات الحقيقية (Hit.ref = الصفحة اللي طلعت منها = Referer التشغيل) + مشغّلات مالقاش فيها حاجة (للـWebView).
     */
    fun hunt(page: String, ua: String = UA, seedText: String = "", seedScripts: List<String> = emptyList(), seedEmbeds: List<String> = emptyList(), startRef: String = ""): Result {
        val t0 = System.currentTimeMillis()
        var fetches = 0
        val hits = ArrayList<Hit>(); val seenMedia = HashSet<String>()
        val left = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        class Job(val url: String, val ref: String, val depth: Int, val text: String?, val scripts: List<String>)
        val q = ArrayDeque<Job>()
        q.add(Job(page, startRef, 0, seedText.ifEmpty { null }, seedScripts))
        for (e in seedEmbeds.take(5)) { val a = abs(page, e) ?: continue; if (a.startsWith("http", true) && !SKIP_EMBED.containsMatchIn(a) && !AdBlock.blocked(a)) q.add(Job(a, page, 1, null, emptyList())) }
        while (q.isNotEmpty() && fetches < MAX_FETCH && System.currentTimeMillis() - t0 < MAX_MS) {
            val j = q.removeFirst()
            if (!seen.add(Sniff.key(j.url))) continue
            var html = j.text; var fin = j.url
            if (j.depth > 0 || html == null) {
                fetches++
                val r = get(j.url, j.ref, ua)
                if (r == null) { if (j.depth > 0) left.add(Pair(j.url, j.ref)); continue }
                html = (html ?: "") + "\n" + r.first; fin = r.second
            }
            val pageScan = scan(html ?: "", fin)
            val found = LinkedHashSet<String>(pageScan.media)
            val scripts = (j.scripts + pageScan.scripts).distinct().take(6)
            for (s in scripts) {
                if (fetches >= MAX_FETCH || System.currentTimeMillis() - t0 > MAX_MS) break
                if (!seen.add("js:" + Sniff.key(s))) continue
                fetches++
                val r = get(s, fin, ua) ?: continue
                found.addAll(scan(r.first, r.second).media)
            }
            for (m in found) if (seenMedia.add(Sniff.key(m))) hits.add(Hit(m, fin))
            if (found.isEmpty()) {
                var added = 0
                for (e in pageScan.embeds) {
                    if (j.depth >= 2 || added >= 4) { left.add(Pair(e, fin)); continue }
                    q.add(Job(e, fin, j.depth + 1, null, emptyList())); added++
                }
            }
            if (hits.size >= 6) break
        }
        return Result(hits, left.distinct().take(6))
    }
}
