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
 * أغلفة الألبومات من مصادر الموسيقى المباشرة المجانية بدون Google Grounding Search:
 * iTunes Search API ثم Deezer API ثم MusicBrainz/Cover Art Archive.
 * لا يستخدم ويكيبيديا أو صور الفنان العامة؛ يحتفظ بالغلاف محليًا ويمنع تكرار البحث 7 أيام عند الفشل.
 */
object Poster {
    private fun dir(ctx: Context) = File(ctx.filesDir, "posters").apply { mkdirs() }
    private fun key(title: String, artist: String) = Integer.toHexString((clean(title).lowercase() + "|" + artist.trim().lowercase()).hashCode())

    /** تنضيف اسم ملف/عنوان: شيل الأقواس والـ _ والأرقام اللي في الأول */
    fun clean(s: String): String = s.replace('_', ' ')
        .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
        .replace(Regex("^\\s*\\d{1,3}\\s*[-.)]\\s*"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun norm(s: String) = ArNorm.fold(s).filter { it.isLetterOrDigit() }
    private fun matches(found: String, want: String): Boolean {
        val a = norm(found); val b = norm(want)
        if (a.length < 2 || b.length < 2) return false
        return a == b || (minOf(a.length, b.length) >= 3 && (a.contains(b) || b.contains(a))) || ArNorm.sim(found, want) >= 0.80
    }

    fun forget(ctx: Context, title: String, artist: String) {
        val k = key(title, artist)
        try { File(dir(ctx), "$k.jpg").delete(); File(dir(ctx), "$k.none").delete() } catch (_: Throwable) {}
    }

    private fun http(url: String, max: Int = 2_000_000): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 7000; c.readTimeout = 9000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Subtitler/2.50 (Android; music artwork client; contact: app user)")
            c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,application/json,*/*;q=0.8")
            if (c.responseCode !in 200..299) null else c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var nTotal = 0
                while (true) {
                    val n = input.read(buf); if (n <= 0) break
                    nTotal += n; if (nTotal > max) return@use null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Throwable) { null } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun iTunesUrls(title: String, artist: String): List<String> {
        val out = ArrayList<String>()
        val q = (artist + " " + title).trim()
        try {
            val txt = http("https://itunes.apple.com/search?media=music&entity=song&limit=25&term=${enc(q)}", 700_000)?.toString(Charsets.UTF_8)
            val arr = txt?.let { JSONObject(it).optJSONArray("results") }
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("trackName"), title)) continue
                if (artist.isNotBlank() && !matches(o.optString("artistName"), artist)) continue
                val u = o.optString("artworkUrl100").replace("100x100bb", "600x600bb")
                if (u.startsWith("https://") && u !in out) out.add(u)
            }
        } catch (_: Throwable) {}
        // بحث الألبومات مفيد حين يسجل Apple اسم إصدار الألبوم بدل عنوان التراك.
        if (out.isEmpty()) try {
            val txt = http("https://itunes.apple.com/search?media=music&entity=album&limit=20&term=${enc(q)}", 700_000)?.toString(Charsets.UTF_8)
            val arr = txt?.let { JSONObject(it).optJSONArray("results") }
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("collectionName"), title) && !matches(o.optString("collectionName"), q)) continue
                if (artist.isNotBlank() && !matches(o.optString("artistName"), artist)) continue
                val u = o.optString("artworkUrl100").replace("100x100bb", "600x600bb")
                if (u.startsWith("https://") && u !in out) out.add(u)
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun deezerUrls(title: String, artist: String): List<String> {
        val out = ArrayList<String>()
        val q = (if (artist.isBlank()) "track:\"$title\"" else "track:\"$title\" artist:\"$artist\"")
        val queries = listOf(q, (artist + " " + title).trim()).distinct()
        for (query in queries) try {
            val txt = http("https://api.deezer.com/search/track?limit=25&q=${enc(query)}", 700_000)?.toString(Charsets.UTF_8) ?: continue
            val arr = JSONObject(txt).optJSONArray("data") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("title"), title)) continue
                val art = o.optJSONObject("artist")?.optString("name").orEmpty()
                if (artist.isNotBlank() && !matches(art, artist)) continue
                val album = o.optJSONObject("album") ?: continue
                val u = album.optString("cover_xl").ifBlank { album.optString("cover_big") }.ifBlank { album.optString("cover_medium") }
                if (u.startsWith("https://") && u !in out) out.add(u)
            }
            if (out.isNotEmpty()) break
        } catch (_: Throwable) {}
        return out
    }

    /** MusicBrainz يحل اسم التسجيل إلى إصدارات، وCover Art Archive يعيد صورة غلاف الإصدار. */
    private fun coverArtArchiveUrls(title: String, artist: String): List<String> {
        val out = ArrayList<String>()
        val query = if (artist.isBlank()) "recording:\"$title\"" else "recording:\"$title\" AND artist:\"$artist\""
        try {
            val txt = http("https://musicbrainz.org/ws/2/recording/?query=${enc(query)}&fmt=json&limit=10", 1_000_000)?.toString(Charsets.UTF_8) ?: return out
            val arr = JSONObject(txt).optJSONArray("recordings") ?: return out
            val releaseIds = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val rec = arr.optJSONObject(i) ?: continue
                if (!matches(rec.optString("title"), title)) continue
                val credits = rec.optJSONArray("artist-credit")
                var creditedArtist = ""
                if (credits != null) for (j in 0 until credits.length()) {
                    val c = credits.optJSONObject(j)
                    creditedArtist += c?.optJSONObject("artist")?.optString("name").orEmpty()
                }
                if (artist.isNotBlank() && creditedArtist.isNotBlank() && !matches(creditedArtist, artist)) continue
                val releases = rec.optJSONArray("releases") ?: continue
                for (j in 0 until releases.length()) {
                    val rel = releases.optJSONObject(j) ?: continue
                    val id = rel.optString("id")
                    if (id.matches(Regex("[0-9a-fA-F-]{30,40}"))) releaseIds.add(id)
                }
            }
            for (id in releaseIds.take(8)) out.add("https://coverartarchive.org/release/$id/front-500")
        } catch (_: Throwable) {}
        return out
    }

    /** أغلفة ألبومات مطابقة فقط: iTunes ← Deezer ← MusicBrainz/Cover Art Archive. */
    private fun findUrls(title: String, artist: String): List<String> {
        val all = ArrayList<String>()
        iTunesUrls(title, artist).forEach { if (it !in all) all.add(it) }
        deezerUrls(title, artist).forEach { if (it !in all) all.add(it) }
        coverArtArchiveUrls(title, artist).forEach { if (it !in all) all.add(it) }
        return all
    }

    private fun skipKey(title: String, artist: String) = "pskip_" + key(title, artist)
    /** «البوستر غلط»: ننسى الغلاف الحالي وننزل إلى المصدر التالي. */
    fun reject(ctx: Context, title0: String, artist0: String): Boolean {
        val title = clean(title0); val artist = artist0.trim()
        forget(ctx, title, artist)
        val n = (Cfg.str(skipKey(title, artist), "0").toIntOrNull() ?: 0) + 1
        Cfg.put(skipKey(title, artist), n.toString())
        return true
    }
    fun resetSkip(title0: String, artist0: String) { Cfg.put(skipKey(clean(title0), artist0.trim()), "0") }

    /** الاسم قديم للمحافظة على توافق استدعاءات الواجهة؛ يبحث الآن في مصادر الأغلفة المباشرة فقط. */
    fun fetchArtworkSync(ctx: Context, title0: String, artist0: String): Bitmap? {
        val title = clean(title0); val artist = artist0.trim()
        if (title.length < 2) return null
        resetSkip(title, artist)
        val k = key(title, artist)
        try {
            File(dir(ctx), "$k.none").delete()
            val seen = HashSet<String>()
            for (u in findUrls(title, artist)) {
                if (!seen.add(u)) continue
                val bytes = http(u) ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                File(dir(ctx), "$k.jpg").writeBytes(bytes)
                return bmp
            }
        } catch (e: Throwable) { LogStore.err("poster", e) }
        return null
    }

    /** على خيط خلفية. بيرجّع غلاف الألبوم أو null. */
    fun fetchSync(ctx: Context, title0: String, artist0: String): Bitmap? {
        val title = clean(title0); val artist = artist0.trim()
        if (title.length < 2) return null
        val k = key(title, artist); val jpg = File(dir(ctx), "$k.jpg"); val none = File(dir(ctx), "$k.none")
        try {
            if (jpg.exists()) BitmapFactory.decodeFile(jpg.path)?.let { return it }
            if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7L * 86400_000L) return null
            val skip = (Cfg.str(skipKey(title, artist), "0").toIntOrNull() ?: 0).coerceAtLeast(0)
            val urls = findUrls(title, artist)
            for (u in urls.drop(skip)) {
                val bytes = http(u) ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                jpg.writeBytes(bytes)
                return bmp
            }
            none.writeText("x")
        } catch (e: Throwable) { LogStore.err("poster", e) }
        return null
    }
}
