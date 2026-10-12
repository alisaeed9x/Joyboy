package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * (v193) غلاف الأغنية (البوستر) من قواعد بيانات مجانية من غير مفتاح.
 * (v250) ويكيبيديا اتشالت خالص. الترتيب دلوقتي:
 *   1) غلاف الأغنية/الألبوم: Apple iTunes ← Deezer ← MusicBrainz + Cover Art Archive
 *   2) لو الأغنية ملهاش غلاف في أي مصدر: صورة المغني نفسه من Deezer (بدل صورة ويكيبيديا)
 * بيدوّر باسم الأغنية + الفنان، ولازم اسم النتيجة يطابق اسمنا (عشان ما نجيبش غلاف غلط). الغلاف بيتخزن على الجهاز؛
 * ولو ما لقيناش حاجة بنفتكر ده 7 أيام عشان ما نكرّرش الطلب.
 */
object Poster {
    private const val UA = "Subtitler/2.50 (Android; Arabic subtitle app)"   // MusicBrainz بيطلب User-Agent معرّف بالتطبيق

    private fun dir(ctx: Context) = File(ctx.filesDir, "posters").apply { mkdirs() }
    private fun key(title: String, artist: String) = Integer.toHexString((clean(title).lowercase() + "|" + artist.trim().lowercase()).hashCode())

    /** تنضيف اسم ملف/عنوان: شيل الأقواس والـ _ والأرقام اللي في الأول */
    fun clean(s: String): String = s.replace('_', ' ')
        .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
        .replace(Regex("^\\s*\\d{1,3}\\s*[-.)]\\s*"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun matches(found: String, want: String): Boolean {
        val a = norm(found); val b = norm(want)
        if (a.length < 2 || b.length < 2) return false
        return a == b || (minOf(a.length, b.length) >= 3 && (a.contains(b) || b.contains(a)))
    }

    fun forget(ctx: Context, title: String, artist: String) {
        val k = key(title, artist)
        try { File(dir(ctx), "$k.jpg").delete(); File(dir(ctx), "$k.none").delete() } catch (_: Throwable) {}
    }

    private fun http(url: String, max: Int = 2_000_000): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 10000; c.setRequestProperty("User-Agent", UA)
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }.takeIf { it.size in 1..max }
        } catch (_: Throwable) { null } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ===== (1) Apple iTunes =====
    private fun itunesUrls(title: String, artist: String): List<String> {
        val out = ArrayList<String>()
        try {
            val q = (artist + " " + title).trim()
            val txt = http("https://itunes.apple.com/search?media=music&entity=song&limit=15&term=${enc(q)}", 500_000)?.toString(Charsets.UTF_8)
            val arr = txt?.let { JSONObject(it).optJSONArray("results") }
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("trackName"), title)) continue
                if (artist.isNotBlank() && !matches(o.optString("artistName"), artist)) continue
                val u = o.optString("artworkUrl100").replace("100x100bb", "600x600bb")
                if (u.isNotBlank() && u !in out) out.add(u)
            }
        } catch (_: Throwable) {}
        return out
    }

    // ===== (2) Deezer =====
    private fun deezerUrls(title: String, artist: String): List<String> {
        val out = ArrayList<String>()
        try {
            val q = (artist + " " + title).trim()
            val txt = http("https://api.deezer.com/search?limit=15&q=${enc(q)}", 500_000)?.toString(Charsets.UTF_8)
            val arr = txt?.let { JSONObject(it).optJSONArray("data") }
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("title"), title)) continue
                if (artist.isNotBlank() && !matches(o.optJSONObject("artist")?.optString("name").orEmpty(), artist)) continue
                val u = o.optJSONObject("album")?.optString("cover_big").orEmpty()
                if (u.isNotBlank() && u !in out) out.add(u)
            }
        } catch (_: Throwable) {}
        return out
    }

    // ===== (3) MusicBrainz + Cover Art Archive =====
    /** بيدوّر عن التسجيل في MusicBrainz (الاسم + الفنان لازم يطابقوا) وبيرجّع أغلفة إصداراته من Cover Art Archive (بعضها ممكن ما يبقاش ليه غلاف — التحميل بيتخطّاه) */
    private fun musicBrainzUrls(title: String, artist: String): List<String> {
        val out = ArrayList<String>()
        try {
            fun lq(s: String) = s.replace("\\", " ").replace("\"", " ").trim()
            val qs = "recording:\"${lq(title)}\"" + (if (artist.isNotBlank()) " AND artist:\"${lq(artist)}\"" else "")
            val txt = http("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=6&query=${enc(qs)}", 800_000)?.toString(Charsets.UTF_8) ?: return out
            val arr = JSONObject(txt).optJSONArray("recordings") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("title"), title)) continue
                if (artist.isNotBlank()) {
                    val ac = o.optJSONArray("artist-credit"); var ok = false
                    if (ac != null) for (j in 0 until ac.length()) {
                        val n = ac.optJSONObject(j)?.optString("name").orEmpty()
                        if (n.isNotBlank() && matches(n, artist)) ok = true
                    }
                    if (!ok) continue
                }
                val rel = o.optJSONArray("releases") ?: continue
                for (j in 0 until minOf(rel.length(), 3)) {
                    val id = rel.optJSONObject(j)?.optString("id").orEmpty()
                    if (id.isNotBlank()) { val u = "https://coverartarchive.org/release/$id/front-500"; if (u !in out) out.add(u) }
                }
                if (out.size >= 5) break
            }
        } catch (_: Throwable) {}
        return out
    }

    // ===== (4) صورة المغني (لو الأغنية ملهاش غلاف) — من Deezer =====
    private fun artistPhotoUrls(artist: String): List<String> {
        val out = ArrayList<String>()
        if (artist.length < 2) return out
        try {
            val txt = http("https://api.deezer.com/search/artist?limit=5&q=${enc(artist)}", 300_000)?.toString(Charsets.UTF_8)
            val arr = txt?.let { JSONObject(it).optJSONArray("data") }
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("name"), artist)) continue
                val u = o.optString("picture_xl").ifBlank { o.optString("picture_big") }
                if (u.isNotBlank() && !u.contains("/artist//") && u !in out) out.add(u)   // «/artist//» = صورة افتراضية فاضية
            }
        } catch (_: Throwable) {}
        return out
    }

    /** كل الأغلفة المطابقة بالترتيب (iTunes ← Deezer ← MusicBrainz ← صورة المغني). بتتجمّع واحد واحد (lazy): المصدر اللي بعده مابيتطلبش إلا لو اللي قبله خلّص */
    private fun urlSeq(title: String, artist: String): Sequence<String> = sequence<String> {
        yieldAll(itunesUrls(title, artist))
        yieldAll(deezerUrls(title, artist))
        yieldAll(musicBrainzUrls(title, artist))
        yieldAll(artistPhotoUrls(artist))
    }.distinct()

    private fun skipKey(title: String, artist: String) = "pskip_" + key(title, artist)
    /** «البوستر غلط»: ننسى الغلاف الحالي وننزل للي بعده. بيرجّع false لو مفيش تاني */
    fun reject(ctx: Context, title0: String, artist0: String): Boolean {
        val title = clean(title0); val artist = artist0.trim()
        forget(ctx, title, artist)
        val n = (Cfg.str(skipKey(title, artist), "0").toIntOrNull() ?: 0) + 1
        Cfg.put(skipKey(title, artist), n.toString())
        return true
    }
    fun resetSkip(title0: String, artist0: String) { Cfg.put(skipKey(clean(title0), artist0.trim()), "0") }

    /**
     * (v241 · v250) زرار «🖼↻» في المشغّل: بيدوّر من الأول في المصادر بالترتيب (iTunes ← Deezer ← MusicBrainz ← صورة المغني).
     * لو ملقاش حاجة بيسيب الغلاف الحالي زي ما هو. على خيط خلفية.
     */
    fun refetchSync(ctx: Context, title0: String, artist0: String): Bitmap? {
        val title = clean(title0); val artist = artist0.trim()
        if (title.length < 2) return null
        resetSkip(title, artist)
        val k = key(title, artist)
        try {
            File(dir(ctx), "$k.none").delete()
            for (u in urlSeq(title, artist)) {
                val bytes = http(u) ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                File(dir(ctx), "$k.jpg").writeBytes(bytes)
                return bmp
            }
        } catch (e: Throwable) { LogStore.err("poster", e) }
        return null
    }

    /** على خيط خلفية. بيرجّع الغلاف أو null */
    fun fetchSync(ctx: Context, title0: String, artist0: String): Bitmap? {
        val title = clean(title0); val artist = artist0.trim()
        if (title.length < 2) return null
        val k = key(title, artist); val jpg = File(dir(ctx), "$k.jpg"); val none = File(dir(ctx), "$k.none")
        try {
            if (jpg.exists()) BitmapFactory.decodeFile(jpg.path)?.let { return it }
            if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7L * 86400_000L) return null
            val skip = Cfg.str(skipKey(title, artist), "0").toIntOrNull() ?: 0
            // الرابط الميّت (مثلًا إصدار ملوش غلاف في Cover Art Archive) بيتخطّى من غير ما يتعدّ، و«البوستر غلط» بيعدّي على اللي بعده
            var n = 0
            for (u in urlSeq(title, artist)) {
                val bytes = http(u) ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (n++ < skip) continue
                jpg.writeBytes(bytes); return bmp
            }
            none.writeText("x")
        } catch (e: Throwable) { LogStore.err("poster", e) }
        return null
    }
}
