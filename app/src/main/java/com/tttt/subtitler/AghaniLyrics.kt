package com.tttt.subtitler

import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap

/** مصدر إضافي للكلمات من AghaniLyrics. لا يستخدم محرك بحث خارجي أو Gemini لجلب النص. */
object AghaniLyrics {
    data class Song(val title: String, val artist: String, val url: String, val lines: List<String>)

    private const val ROOT = "https://aghanilyrics.com/"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, maxBytes: Int = 1_000_000): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 5000
            c.readTimeout = 6500
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36 Subtitler/2.50")
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", "ar,en-US;q=0.8,en;q=0.6")
            if (c.responseCode !in 200..299) null else c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192); var total = 0
                while (true) {
                    val n = input.read(buf); if (n <= 0) break
                    total += n; if (total > maxBytes) return@use null
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        } catch (_: Throwable) { null } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }

    private fun text(html: String): String {
        val flat = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>|</h[1-6]\\s*>|</tr\\s*>|</section\\s*>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
        return Html.fromHtml(flat, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\u00a0', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private data class Link(val title: String, val url: String, val score: Double)

    private fun searchLinks(html: String, wantedTitle: String): List<Link> {
        val result = LinkedHashMap<String, Link>()
        val aRe = Regex("(?is)<a\\b[^>]*href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a\\s*>")
        for (m in aRe.findAll(html)) {
            var href = m.groupValues[2].replace("&amp;", "&").trim()
            if (!href.contains("songlyrics.php", true)) continue
            if (href.startsWith("//")) href = "https:$href"
            else if (href.startsWith("/")) href = "https://aghanilyrics.com$href"
            else if (!href.startsWith("http", true)) href = ROOT + href
            if (!href.startsWith(ROOT) || href.contains("#")) continue
            val label = text(m.groupValues[3]).replace(Regex("\\s+"), " ").trim()
            val slug = try {
                val q = URL(href).query.orEmpty().split('&').firstOrNull { it.startsWith("ar=") }?.substringAfter('=')
                java.net.URLDecoder.decode(q.orEmpty(), "UTF-8").replace('-', ' ')
            } catch (_: Throwable) { "" }
            val candidateTitle = label.ifBlank { slug }.replace(Regex("(?i)^\\s*كلمات\\s+اغنية\\s*"), "").trim()
            if (candidateTitle.isBlank() || candidateTitle.length < 2) continue
            val score = maxOf(ArNorm.sim(wantedTitle, candidateTitle), if (ArNorm.fold(candidateTitle).contains(ArNorm.fold(wantedTitle)) && ArNorm.fold(wantedTitle).length >= 3) 0.9 else 0.0)
            // نتسامح مع اختلافات كتابة العنوان، لكن نستبعد النتائج التي لا تشبه عنوان الأغنية إطلاقًا.
            if (score < 0.22) continue
            val old = result[href]
            if (old == null || score > old.score) result[href] = Link(candidateTitle, href, score)
        }
        return result.values.sortedByDescending { it.score }.take(7)
    }

    private fun pageMeta(pageHtml: String, fallbackTitle: String, fallbackArtist: String): Pair<String, String> {
        val plain = text(pageHtml)
        fun field(pattern: String): String = try {
            Regex("(?im)^.*(?:$pattern)\\s*[:：]?\\s*(?:\\|\\s*)?([^\\n|]{2,100})$").find(plain)?.groupValues?.get(1)?.trim()?.trim('|', ' ', ':', '：').orEmpty()
        } catch (_: Throwable) { "" }
        val title = field("اسم\\s+الاغنية|اسم\\s+الأغنية")
        val artist = field("مطرب\\s+الاغنية|مطرب\\s+الأغنية|اسم\\s+المطرب")
        if (title.isNotBlank() && !title.contains("كلمات")) return title to artist.ifBlank { fallbackArtist }
        val h = Regex("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]>").findAll(pageHtml)
            .map { text(it.groupValues[1]) }.firstOrNull { it.contains("كلمات") && !it.contains("معلومات") }.orEmpty()
        val clean = h.replace(Regex("(?i)كلمات\\s+اغنية|كلمات\\s+أغنية|كلمات"), " ").replace(Regex("\\s+"), " ").trim()
        return (if (clean.length >= 2) clean else fallbackTitle) to artist.ifBlank { fallbackArtist }
    }

    private fun extractLyrics(html: String, title: String): List<String> {
        val cleaned = html.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        val headings = Regex("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]>").findAll(cleaned).toList()
        val foldedTitle = ArNorm.fold(title).trim()
        // نفضّل عنوان قسم الكلمات مثل "اسم الأغنية كلمات" على عنوان الصفحة العام،
        // حتى لا تدخل روابط التصفح واسم الفنان ضمن نص الأغنية.
        val lyricHeading = headings.firstOrNull {
            val h = ArNorm.fold(text(it.groupValues[1])).trim()
            h.contains("كلمات") && !h.contains("معلومات") && !h.contains("التصنيف") &&
                foldedTitle.length >= 2 && h.contains(foldedTitle) && h.endsWith("كلمات")
        } ?: headings.firstOrNull {
            val h = text(it.groupValues[1])
            h.contains("كلمات") && !h.contains("معلومات") && !h.contains("التصنيف")
        }
        var start = lyricHeading?.range?.last?.plus(1) ?: -1
        if (start < 0) {
            val titleNeedle = ArNorm.fold(title)
            if (titleNeedle.isNotBlank()) {
                val at = ArNorm.fold(text(cleaned)).indexOf(titleNeedle)
                if (at >= 0) start = 0
            }
        }
        val tail = if (start >= 0) cleaned.substring(start) else cleaned
        val tailPlain = text(tail)
        val infoRe = Regex("(?i)(معلومات\\s+الاغنية|معلومات\\s+الأغنية|تفاصيل\\s+الاغنية|تفاصيل\\s+الأغنية|اخر\\s+تعديل|آخر\\s+تعديل|تم\\s+النشر)")
        val info = infoRe.find(tailPlain)
        val regionText = if (info != null && info.range.first > 40) tailPlain.substring(0, info.range.first) else tailPlain
        val raw = regionText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val out = ArrayList<String>()
        for (line0 in raw) {
            val line = line0.trim()
            if (line.length < 2) continue
            if (line.matches(Regex("(?i)(\\+\\s*حجم الخط|-\\s*حجم الخط|حجم الخط|كلمات اغنية|كلمات أغنية|كلمات|معلومات الاغنية|معلومات الأغنية|اسم الاغنية|مطرب الاغنية)"))) continue
            if (line.startsWith("كلمات اغنية ") || line.startsWith("كلمات أغنية ")) continue
            if (line == title || ArNorm.fold(line) == ArNorm.fold(title)) continue
            if (line.contains("تابعنا على مواقع التواصل") || line.contains("حقوق النشر محفوظة")) continue
            out.add(line)
        }
        // أحيانًا تُعرض الكلمات في سطر واحد متصل بعد إزالة الوسوم؛ نحتفظ به بدل ضياع النص.
        return out.takeIf { it.size >= 3 } ?: emptyList()
    }

    /** يبحث بمحددات الموقع نفسه ثم يقرأ أكثر من صفحة متطابقة ليتيح اختلافات النسخ/الأداء. */
    fun search(title0: String, artist0: String, limit: Int = 6): List<Song> {
        val title = ArNorm.clean(title0).ifBlank { title0.trim() }
        val artist = ArNorm.clean(artist0)
        if (title.length < 2) return emptyList()
        val q = (title + " " + artist).trim()
        val queries = listOf(
            ROOT + "site-search.php?search=" + enc(q),
            ROOT + "site-search.php?q=" + enc(q),
            ROOT + "site-search.php?query=" + enc(q),
            ROOT + "search.php?query=" + enc(q),
            ROOT + "search-lyrics.php?search=" + enc(q),
            ROOT + "site-search.php?search=" + enc(title)
        ).distinct()
        val links = LinkedHashMap<String, Link>()
        for (u in queries) {
            val html = get(u) ?: continue
            for (l in searchLinks(html, title)) links[l.url] = l
            if (links.size >= limit) break
        }
        if (links.isEmpty()) return emptyList()
        val out = ArrayList<Song>()
        val seenLyrics = HashSet<String>()
        for (link in links.values.sortedByDescending { it.score }.take(limit.coerceIn(1, 8))) {
            val html = get(link.url, 1_500_000) ?: continue
            val meta = pageMeta(html, link.title, artist)
            val pageTitle = meta.first.ifBlank { link.title }
            val similarity = maxOf(ArNorm.sim(title, pageTitle), if (ArNorm.fold(pageTitle).contains(ArNorm.fold(title)) && ArNorm.fold(title).length >= 3) 0.9 else 0.0)
            if (similarity < 0.28) continue
            val words = extractLyrics(html, pageTitle)
            if (words.size < 3) continue
            val sig = ArNorm.fold(words.joinToString(" ")).take(6000)
            if (!seenLyrics.add(sig)) continue // لا نكرر النسخ التي تحمل النص نفسه حرفيًا
            out.add(Song(pageTitle, meta.second.ifBlank { artist }, link.url, words))
        }
        return out.sortedByDescending { maxOf(ArNorm.sim(title, it.title), if (ArNorm.fold(it.title).contains(ArNorm.fold(title)) && ArNorm.fold(title).length >= 3) 0.9 else 0.0) }
    }
}
