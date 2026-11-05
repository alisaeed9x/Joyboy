package com.tttt.subtitler

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** (v776) صورة خلفية من أي مصدر: [thumbs] بالترتيب (الأولى المفضّلة) و[fulls] النسخ الكبيرة (بنجرّبهم بالترتيب لحد ما واحدة تنزل) */
data class WallItem(val thumbs: List<String>, val fulls: List<String>, val title: String, val source: String)

/**
 * (v776) مصادر الخلفيات — مش Bing بس:
 *  • Bing اليومية (أحدث صور الأرشيف) · • Wallhaven (بحث + الأحدث + عشوائي — بدون مفتاح، محتوى آمن بس)
 *  • بحث صور Bing (لأي كلمة: ذئب، ثعلب…) · • Picsum (عشوائي) في AppWall
 * كل الدوال بتتنادى من Thread خلفي (بتعمل شبكة)، وبترجّع قايمة فاضية لو فشلت (مبترميش استثناء).
 */
object WallSources {
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"


    // Wallhaven بيفهم إنجليزي بس — قاموس صغير للكلمات الشائعة (الباقي بيتبعت زي ما هو، وبحث Bing بيفهم عربي)
    private val ar2en = mapOf(
        "ذئب" to "wolf", "ثعلب" to "fox", "اسد" to "lion", "أسد" to "lion", "نمر" to "tiger", "قطة" to "cat", "قط" to "cat", "كلب" to "dog",
        "حصان" to "horse", "خيل" to "horse", "نسر" to "eagle", "صقر" to "falcon", "دب" to "bear", "فيل" to "elephant", "تنين" to "dragon",
        "فراشة" to "butterfly", "سمكة" to "fish", "حوت" to "whale", "دلفين" to "dolphin", "غراب" to "crow", "بومة" to "owl",
        "طبيعة" to "nature", "غابة" to "forest", "جبل" to "mountain", "جبال" to "mountains", "بحر" to "sea", "محيط" to "ocean", "شاطئ" to "beach",
        "صحرا" to "desert", "صحراء" to "desert", "سماء" to "sky", "غروب" to "sunset", "شروق" to "sunrise", "نجوم" to "stars", "قمر" to "moon",
        "شمس" to "sun", "فضاء" to "space", "مجرة" to "galaxy", "مطر" to "rain", "ثلج" to "snow", "شتاء" to "winter", "خريف" to "autumn",
        "ربيع" to "spring", "زهور" to "flowers", "ورد" to "roses", "شلال" to "waterfall", "نهر" to "river", "بحيرة" to "lake", "ليل" to "night",
        "مدينة" to "city", "طوكيو" to "tokyo", "سيارة" to "car", "سيارات" to "cars", "دراجة" to "motorcycle", "طيارة" to "airplane", "سفينة" to "ship",
        "انمي" to "anime", "أنمي" to "anime", "العاب" to "games", "ألعاب" to "games", "سيبر" to "cyberpunk", "روبوت" to "robot", "كعبة" to "kaaba",
        "مسجد" to "mosque", "قهوة" to "coffee", "سيارة رياضية" to "sports car", "ظلام" to "dark", "ابيض" to "white", "أسود" to "black", "ازرق" to "blue",
        "أزرق" to "blue", "احمر" to "red", "أحمر" to "red", "اخضر" to "green", "أخضر" to "green", "ذهبي" to "gold", "بسيط" to "minimal", "تجريدي" to "abstract"
    )

    /** كلمة البحث بالإنجليزي لـWallhaven (لو كل كلمة مفهومة بنترجمها، وإلا بنسيبها) */
    fun toEnglish(q: String): String {
        val t = q.trim()
        if (t.isEmpty()) return t
        ar2en[t]?.let { return it }
        return t.split(Regex("\\s+")).joinToString(" ") { ar2en[it] ?: it }
    }

    fun http(url: String, max: Int = 4_000_000): ByteArray? {
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (_: Throwable) { return null }
        return try {
            c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
            if (c.responseCode !in 200..299) null
            else {
                val bo = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var tot = 0
                var tooBig = false
                c.inputStream.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        tot += n
                        if (tot > max) { tooBig = true; break }
                        bo.write(buf, 0, n)
                    }
                }
                if (tooBig || tot == 0) null else bo.toByteArray()
            }
        } catch (_: Throwable) { null } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }

    private fun text(url: String, max: Int = 3_000_000): String? = http(url, max)?.toString(Charsets.UTF_8)

    // ───────── Bing اليومية ─────────
    fun bingDaily(portrait: Boolean): List<WallItem> {
        val out = ArrayList<WallItem>()
        for (idx in listOf(0, 8)) {
            try {
                val js = JSONObject(text("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=en-US") ?: continue)
                val arr = js.getJSONArray("images")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val base = "https://www.bing.com" + o.optString("urlbase")
                    val land = "https://www.bing.com" + o.optString("url")
                    if (o.optString("urlbase").isBlank()) continue
                    val title = o.optString("title").ifBlank { o.optString("copyright").substringBefore("(").trim() }
                    val thumbs = if (portrait) listOf(base + "_480x800.jpg", base + "_320x240.jpg") else listOf(base + "_800x480.jpg", base + "_400x240.jpg")
                    val fulls = if (portrait) listOf(base + "_1080x1920.jpg", base + "_768x1280.jpg", land) else listOf(land, base + "_1920x1080.jpg")
                    out.add(WallItem(thumbs, fulls, title, "Bing"))
                }
            } catch (_: Throwable) {}
        }
        return out
    }

    // ───────── Wallhaven ─────────
    private fun wallhavenUrl(q: String, page: Int, sorting: String, ratio: String?): String {
        val sb = StringBuilder("https://wallhaven.cc/api/v1/search?categories=111&purity=100&order=desc&page=").append(page.coerceAtLeast(1))
        sb.append("&sorting=").append(sorting)
        if (q.isNotBlank()) sb.append("&q=").append(URLEncoder.encode(q.trim(), "UTF-8"))
        if (ratio != null) sb.append("&ratios=").append(ratio).append("&atleast=").append(if (ratio == "portrait") "720x1280" else "1280x720")
        return sb.toString()
    }

    private fun parseWallhaven(body: String?): List<WallItem> {
        if (body == null) return emptyList()
        return try {
            val arr = JSONObject(body).getJSONArray("data")
            val out = ArrayList<WallItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val path = o.optString("path")
                if (!path.startsWith("http")) continue
                val th = o.optJSONObject("thumbs")
                val thumbs = ArrayList<String>()
                th?.optString("large")?.takeIf { it.startsWith("http") }?.let { thumbs.add(it) }
                th?.optString("small")?.takeIf { it.startsWith("http") }?.let { thumbs.add(it) }
                if (thumbs.isEmpty()) thumbs.add(path)
                out.add(WallItem(thumbs, listOf(path), o.optString("resolution"), "Wallhaven"))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    /** [q] فاضي = الأحدث؛ وإلا بحث بالكلمة. لو الاتجاه المطلوب مفيش فيه نتايج بنجرّب من غير تحديد اتجاه */
    fun wallhaven(q: String, page: Int, portrait: Boolean): List<WallItem> {
        val sorting = if (q.isBlank()) "date_added" else "relevance"
        val r1 = parseWallhaven(text(wallhavenUrl(q, page, sorting, if (portrait) "portrait" else "landscape")))
        if (r1.size >= 6) return r1
        val r2 = parseWallhaven(text(wallhavenUrl(q, page, sorting, null)))
        return if (r2.size > r1.size) r2 else r1
    }

    /** رابط صورة عشوائية من Wallhaven للتغيير التلقائي (AppWall) */
    fun wallhavenRandom(portrait: Boolean): String? {
        val seed = java.lang.Long.toHexString(System.nanoTime()).takeLast(6)
        val u = wallhavenUrl("", 1, "random", if (portrait) "portrait" else "landscape") + "&seed=" + seed
        return parseWallhaven(text(u)).randomOrNull()?.fulls?.firstOrNull()
    }

    // ───────── بحث صور Bing ─────────
    fun bingImages(q: String, portrait: Boolean): List<WallItem> {
        if (q.isBlank()) return emptyList()
        val query = if (q.contains("wallpaper", true) || q.contains("خلفية")) q else "$q wallpaper"
        val u = "https://www.bing.com/images/search?q=" + URLEncoder.encode(query, "UTF-8") +
            "&form=HDRSC2&first=1&qft=+filterui:imagesize-large" + (if (portrait) "+filterui:aspect-tall" else "+filterui:aspect-wide") + "&adlt=strict"
        val html = text(u) ?: return emptyList()
        val fulls = Regex("murl&quot;:&quot;(.*?)&quot;").findAll(html).map { it.groupValues[1].replace("\\/", "/").replace("&amp;", "&") }.toList()
        val thumbs = Regex("turl&quot;:&quot;(.*?)&quot;").findAll(html).map { it.groupValues[1].replace("\\/", "/").replace("&amp;", "&") }.toList()
        val bad = listOf("wikipedia.org", "wikimedia.org", "pinterest", "alamy", "shutterstock", "istockphoto", "dreamstime", "gettyimages", ".svg", ".gif")
        val out = ArrayList<WallItem>()
        val seen = HashSet<String>()
        for ((i, f) in fulls.withIndex()) {
            if (!f.startsWith("http") || bad.any { f.contains(it, true) } || !seen.add(f)) continue
            val t = thumbs.getOrNull(i)?.takeIf { it.startsWith("http") }
            out.add(WallItem(if (t != null) listOf(t, f) else listOf(f), listOf(f), q.trim(), "Bing"))
            if (out.size >= 24) break
        }
        return out
    }

    // ───────── التجميع ─────────
    private fun interleave(a: List<WallItem>, b: List<WallItem>): List<WallItem> {
        val out = ArrayList<WallItem>()
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) { a.getOrNull(i)?.let { out.add(it) }; b.getOrNull(i)?.let { out.add(it) } }
        return out
    }

    /** أحدث الخلفيات: الصفحة الأولى = Bing اليومية + أحدث Wallhaven؛ الصفحات اللي بعدها Wallhaven */
    fun latest(page: Int, portrait: Boolean): List<WallItem> {
        val w = wallhaven("", page, portrait)
        return if (page <= 1) interleave(bingDaily(portrait), w) else w
    }

    /** بحث بكلمة (ذئب، ثعلب، فضاء…): Wallhaven + صور Bing (الصفحة الأولى بس) */
    fun search(q: String, page: Int, portrait: Boolean): List<WallItem> {
        val w = wallhaven(toEnglish(q), page, portrait)
        return if (page <= 1) interleave(w, bingImages(q, portrait)) else w
    }
}
