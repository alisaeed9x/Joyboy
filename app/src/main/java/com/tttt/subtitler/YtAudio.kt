package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * (v337) تشغيل صوت فيديو يوتيوب في مشغّل الموسيقى (من غير ما نسحب أي داتا فيديو):
 * بنعمل Track رابطه رابط الصوت نفسه (ستريم http) — فمشغّل الموسيقى بيتعامل معاه زي أي أغنية:
 * الاسم والمغني من عنوان الفيديو، والكلمات والغلاف والتعرّف بنفس الطريقة.
 * الـid سالب عشان ما يتعارضش مع أرقام ملفات الجهاز (MediaStore).
 */
object YtAudio {
    private val hdr = ConcurrentHashMap<String, Map<String, String>>()
    private val thumbs = ConcurrentHashMap<Long, String>()
    private val items = ConcurrentHashMap<Long, NpItem>()   // (v339) معلومات الفيديو الأصلي لكل Track يوتيوب — عشان زرار ⬇ في مشغّل الموسيقى يعرف يحمّله

    fun isRemote(uri: String) = uri.startsWith("http", true)
    fun headersFor(uri: String): Map<String, String> = hdr[uri] ?: emptyMap()

    fun idOf(vid: String): Long {
        val h = (("a" + vid).hashCode().toLong() shl 31) xor ("b" + vid).hashCode().toLong()
        return -(h and Long.MAX_VALUE) - 1L
    }

    private val NOISE = Regex("""\s*[\(\[\{（【][^\)\]\}）】]*(official|video|audio|lyric|lyrics|clip|visuali[sz]er|remaster|hd|4k|كلمات|فيديو|كليب|رسمي|حصري|مترجم)[^\)\]\}）】]*[\)\]\}）】]""", RegexOption.IGNORE_CASE)
    private val NOISE2 = Regex("""(?i)\b(official\s+(music\s+)?video|official\s+audio|lyric\s+video|lyrics)\b""")
    private val SEPR = Regex("""\s[-–—]\s""")

    /** (الاسم، المغني) من عنوان الفيديو: «مغني - اسم» → المغني قبل الشرطة. لو مفيش شرطة والقناة «… - Topic» نستخدمها، غير كده المغني فاضي (التعرّف التلقائي يكمّل) */
    fun parse(raw: String, channel: String): Pair<String, String> {
        var t = NOISE.replace(raw, " ")
        t = NOISE2.replace(t, " ").replace(Regex("\\s+"), " ").trim().trim('-', '–', '—', '|', ' ')
        val parts = t.split(SEPR, 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) return parts[1].trim() to parts[0].trim()
        val ch = channel.trim()
        if (ch.endsWith("- Topic", true)) return t.ifBlank { raw } to ch.dropLast(7).trim().trim('-', ' ')
        return t.ifBlank { raw } to ""
    }

    fun track(vid: String, title: String, channel: String, thumb: String, a: YtPick): Track {
        val (nm, ar) = parse(title.ifBlank { a.title }, channel)
        hdr[a.url] = mapOf("User-Agent" to a.ua, "Referer" to "https://www.youtube.com/")
        val id = idOf(vid)
        thumbs[id] = thumb.ifBlank { "https://i.ytimg.com/vi/$vid/mqdefault.jpg" }
        items[id] = NpItem(vid, title.ifBlank { a.title }, channel, "", thumbs[id] ?: "", a.durMs / 1000L, -1L, "", 0, YtNp.watchUrl(vid))
        return Track(id, a.url, nm, ar, a.durMs, "yt", "يوتيوب")
    }

    /** (v339) فيديو يوتيوب الأصلي لـ Track بيشتغل من يوتيوب (null لو أغنية من الجهاز أو الجلسة اتقفلت) */
    fun itemOf(t: Track?): NpItem? = if (t != null && isRemote(t.uri)) items[t.id] else null

    /** مصدر صوت للتعرّف/الكلمات: ستريم يوتيوب بالهيدرز بتاعته، أو ملف الجهاز */
    fun src(app: Context, t: Track): AudioSource =
        if (isRemote(t.uri)) AudioSources.make(app, null, t.uri, headersFor(t.uri), 1) { }
        else AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }

    /** غلاف احتياطي: صورة الفيديو متقصوصة مربع (لو مفيش بوستر لقيناه بالاسم) — من خيط خلفي */
    fun thumb(t: Track): Bitmap? {
        val u = thumbs[t.id] ?: return null
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val b = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            val s = (if (b.width.toFloat() / b.height < 1.5f) b.height * 3 / 4 else b.height).coerceAtMost(b.width).coerceAtLeast(1)
            Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        } catch (_: Throwable) { null }
    }
}
