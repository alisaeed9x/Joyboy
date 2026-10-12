package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** سطر كلام متزامن: من start لـ end بالثواني */
class LyricLine(val start: Double, val end: Double, val text: String)

/** نتيجة كلمات أغنية. note = ملاحظة تتعرض فوق الكلمات (مثلًا «التوقيت تقريبي»). raw = سطور الكلمات من غير توقيت */
class LyricsResult(
    val title: String, val artist: String, val lines: List<LyricLine>,
    val complete: Boolean = true, val done: Int = 0, val total: Int = 0,
    val ok: Set<Int> = emptySet(), val note: String = "", val raw: List<String> = emptyList(),
    val synced: Boolean = true, val source: String = ""
)

/**
 * (v242 · مرحلة 1) محرك الكلمات اتفضّى من الطريقتين القديمتين:
 *  ✘ ترانسكريبت الصوت (تفريغ الأغنية بجيميناي على مقاطع)
 *  ✘ جلب الكلمات من جيميناي بالاسم (والتعرّف بمقطع، وتوقيت جيميناي)
 * اللي فاضل هنا: الكاش المحلي، عرض الكلمات المحفوظة، ✍ كلمات يدوي (توقيت محلي من غير AI)، والبحث عن السطر الشغّال.
 * (v243 · مرحلة 2) المصدر الجديد: LRCLIB (من غير مفاتيح) بعد تطبيع الاسم العربي (LyricsNorm.kt) — كلمات متزامنة LRC أو عادية من غير توقيت.
 * المصادر الاحتياطية + تحسينات التخزين جاية في المراحل الجاية.
 */
object LyricsEngine {
    const val M_FULL = "full"
    /** مفيش أوضاع تانية دلوقتي — بنسيبها دالة عشان باقي الكود مايتغيّرش */
    fun mode(): String = M_FULL

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun key(t: Track, m: String) = "t${t.id}_${t.durMs}" + (if (m == M_FULL) "_full" else "") + ".json"
    private val running = ConcurrentHashMap<String, Boolean>()
    @Volatile var version = 0; private set      // بيزيد مع كل حفظ — الشاشة بتقارنه عشان تعرف تحدّث

    fun isRunning(t: Track, m: String) = running.containsKey(key(t, m))
    fun anyRunning() = running.isNotEmpty()

    fun cached(ctx: Context, t: Track, m: String): LyricsResult? = try {
        val f = File(dir(ctx), key(t, m))
        val r = parse(JSONObject(f.readText()))
        // «ما لقيتش» بتتحفظ 12 ساعة بس عشان ما نضغطش على المصدر — بعدها بنجرّب تاني
        if (r.lines.isEmpty() && r.raw.isEmpty() && System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) null else r
    } catch (_: Throwable) { null }
    fun clear(ctx: Context, t: Track, m: String) { try { File(dir(ctx), key(t, m)).delete() } catch (_: Throwable) {}; version++ }
    fun clearAll(ctx: Context, t: Track) { clear(ctx, t, M_FULL) }

    private fun parse(o: JSONObject): LyricsResult {
        val a = o.optJSONArray("lines") ?: JSONArray()
        val l = ArrayList<LyricLine>()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); l.add(LyricLine(x.getDouble("s"), x.getDouble("e"), x.getString("t"))) }
        val ok = HashSet<Int>(); val oa = o.optJSONArray("ok"); if (oa != null) for (i in 0 until oa.length()) ok.add(oa.getInt(i))
        val ra = o.optJSONArray("raw"); val raw = ArrayList<String>(); if (ra != null) for (i in 0 until ra.length()) { val x = ra.optString(i).trim(); if (x.isNotEmpty()) raw.add(x) }
        return LyricsResult(o.optString("title"), o.optString("artist"), l, o.optBoolean("complete", true), o.optInt("done", 0), o.optInt("total", 0), ok, o.optString("note"), raw, o.optBoolean("synced", true), o.optString("source"))
    }
    private fun save(ctx: Context, t: Track, m: String, r: LyricsResult) {
        val a = JSONArray(); for (x in r.lines) a.put(JSONObject().put("s", x.start).put("e", x.end).put("t", x.text))
        val oa = JSONArray(); for (i in r.ok.sorted()) oa.put(i)
        val ra = JSONArray(); for (x in r.raw) ra.put(x)
        try {
            File(dir(ctx), key(t, m)).writeText(JSONObject().put("title", r.title).put("artist", r.artist).put("lines", a)
                .put("complete", r.complete).put("done", r.done).put("total", r.total).put("ok", oa).put("note", r.note).put("raw", ra).put("synced", r.synced).put("source", r.source).put("v", 6).toString())
        } catch (_: Throwable) {}
        version++
    }

    /** توقيت تقريبي (موزّع بالتساوي) — بنستخدمه بس لو تحديد لحظات الغنا محليًا فشل */
    private fun estimate(t: Track, lines: List<String>): List<LyricLine> {
        val tot = (t.durMs / 1000.0).coerceAtLeast(30.0)
        val step = (tot * 0.85) / lines.size.coerceAtLeast(1); val off = tot * 0.07
        return lines.mapIndexed { i, x -> LyricLine(off + i * step, off + (i + 1) * step, x) }
    }

    // ===== (v243) LRCLIB =====
    private const val API = "https://lrclib.net/api/"
    private const val UA = "Subtitler/2.46 (Android; lyrics client)"
    private val lastId = ConcurrentHashMap<String, Long>()   // آخر نتيجة اتعرضت لكل أغنية — «الكلمات غلط» بتتخطاها

    private class Cand(val id: Long, val title: String, val artist: String, val synced: String, val plain: String, val score: Double)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** GET → نص الرد، أو null لو 404 (مفيش نتيجة). أي خطأ شبكة/سيرفر بيطلع استثناء */
    private fun httpGet(u: String): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun str(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

    /** بيقيّم نتيجة LRCLIB: الاسم + الفنان + المدة. بيرجّع null لو مش نفس الأغنية (المدة بتفرق أكتر من 10ث، أو الاسم مش قريب) */
    private fun candOf(o: JSONObject, t: Track, wantT: String, wantA: String): Cand? {
        if (o.optBoolean("instrumental", false)) return null
        val syn = str(o, "syncedLyrics"); val plain = str(o, "plainLyrics")
        if (syn.isBlank() && plain.isBlank()) return null
        val title = str(o, "trackName").ifBlank { str(o, "name") }; val artist = str(o, "artistName")
        val dur = o.optDouble("duration", 0.0)
        val have = t.durMs > 0 && dur > 0
        val diff = if (have) Math.abs(dur - t.durMs / 1000.0) else 0.0
        if (have && diff > 10.0) return null
        val durOk = have && diff <= 4.0
        val tsim = ArNorm.sim(wantT, title)
        val asim = if (wantA.isBlank() || artist.isBlank()) 0.0 else ArNorm.sim(wantA, artist)
        if (tsim < 0.5 && !(durOk && asim >= 0.5)) return null
        val score = tsim * 2 + asim + (if (durOk) 1.5 else 0.0) + (if (syn.isNotBlank()) 0.3 else 0.0)
        if (score < 2.0) return null
        return Cand(o.optLong("id", 0L), title, artist, syn, plain, score)
    }

    /** LRC → سطور بتوقيت. السطر الفاضي بيقفل السطر اللي قبله (فجوات الموسيقى) */
    fun parseLrc(text: String, totalSec: Double): List<LyricLine> {
        val re = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
        val pts = ArrayList<Pair<Double, String>>()
        for (raw in text.lines()) {
            val ms = re.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val tx = re.replace(raw, "").replace("\u200e", "").replace("\u200f", "").trim()
            for (m in ms) {
                val fr = m.groupValues[3]
                val f = if (fr.isEmpty()) 0.0 else ("0." + fr).toDouble()
                pts.add((m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() + f) to tx)
            }
        }
        pts.sortBy { it.first }
        val res = ArrayList<LyricLine>()
        for (i in pts.indices) {
            val (st, tx) = pts[i]
            if (tx.isEmpty()) continue
            val nx = if (i + 1 < pts.size) pts[i + 1].first else (if (totalSec > st) totalSec else st + 6.0)
            res.add(LyricLine(st, maxOf(nx, st + 0.5), tx))
        }
        return res
    }

    /**
     * جلب الكلمات من LRCLIB (من غير مفاتيح). بيجرّب صيغ الاسم المطبّعة بالترتيب (ArNorm.variants):
     * get بالاسم+الفنان+المدة ← search ← وأخيرًا q حر. بيرفض أي نتيجة مدتها بعيدة أو اسمها مش قريب.
     * متزامنة → LRC بتوقيت فعلي · غير متزامنة → سطور من غير توقيت (من غير توقيت مختلق).
     * فشل الشبكة ما بيتحفظش كـ«مفيش كلمات»؛ «مفيش نتيجة» بتتحفظ 12 ساعة.
     */
    fun start(ctx: Context, t: Track, mode: String, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val app = ctx.applicationContext
        val k = key(t, M_FULL)
        if (running.putIfAbsent(k, true) != null) { onDone(null, "بتتجاب دلوقتي"); return }
        Thread {
            try { fetch(app, t, hint, retry, fresh, onStage, onDone) }
            catch (e: Throwable) { LogStore.err("lyrics-fetch", e); onDone(null, "فشل: " + (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(110)) }
            finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    private fun fetch(app: Context, t: Track, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val k = key(t, M_FULL)
        if (!fresh && !retry) {
            val c = cached(app, t, M_FULL)
            if (c != null && c.lines.isNotEmpty()) { onDone(c, ""); return }
        }
        val name = (hint?.first ?: "").ifBlank { t.title }
        val by = (hint?.second ?: "").ifBlank { t.artist }
        val vars = ArNorm.variants(name, by)
        val skip: Long? = if (retry) lastId[k] else null
        var best: Cand? = null
        var answered = false
        var lastErr = ""

        fun consider(o: JSONObject, wt: String, wa: String) {
            val c = candOf(o, t, wt, wa) ?: return
            if (skip != null && c.id == skip) return
            val b0 = best
            if (b0 == null || c.score > b0.score) best = c
        }

        var n = 0
        for ((tt, aa) in vars) {
            n++
            onStage("🔎 بدوّر في LRCLIB ($n/${vars.size})…")
            try {
                if (aa.isNotBlank() && t.durMs > 0) {
                    val g = httpGet(API + "get?artist_name=" + enc(aa) + "&track_name=" + enc(tt) + "&duration=" + Math.round(t.durMs / 1000.0))
                    answered = true
                    if (g != null) consider(JSONObject(g), name, by)
                }
                val q = "track_name=" + enc(tt) + (if (aa.isNotBlank()) "&artist_name=" + enc(aa) else "")
                val s = httpGet(API + "search?" + q)
                answered = true
                if (s != null) { val a = JSONArray(s); for (i in 0 until minOf(a.length(), 20)) consider(a.getJSONObject(i), name, by) }
            } catch (e: Throwable) { lastErr = e.message ?: e.javaClass.simpleName }
            if (best != null) break
        }
        if (best == null && answered) {
            try {
                onStage("🔎 بحث حر في LRCLIB…")
                val s = httpGet(API + "search?q=" + enc((ArNorm.clean(name) + " " + ArNorm.clean(by)).trim()))
                if (s != null) { val a = JSONArray(s); for (i in 0 until minOf(a.length(), 20)) consider(a.getJSONObject(i), name, by) }
            } catch (e: Throwable) { lastErr = e.message ?: e.javaClass.simpleName }
        }

        var src = "lrclib"
        // (v243 · مرحلة 3) مصدر احتياطي: Lyrics.ovh — كلمات عادية من غير توقيت، ومحتاج اسم فنان. من غير مقارنة مدة، فبنقبل بس لو الفنان معروف
        if (best == null && !(retry && skip == 0L)) {
            var n2 = 0
            for ((tt, aa) in vars) {
                if (aa.isBlank()) continue
                if (++n2 > 3) break
                onStage("🔎 بدوّر في مصدر احتياطي ($n2)…")
                try {
                    val r = httpGet("https://api.lyrics.ovh/v1/" + enc(aa).replace("+", "%20") + "/" + enc(tt).replace("+", "%20"))
                    answered = true
                    if (r != null) {
                        var ly = str(JSONObject(r), "lyrics").replace("\r", "").trim()
                        if (ly.startsWith("Paroles de la chanson")) ly = ly.lines().drop(1).joinToString("\n").trim()   // سطر دعائي بيبدأ بيه Lyrics.ovh أحيانًا
                        if (ly.lines().count { it.isNotBlank() } >= 4) { best = Cand(0L, tt, aa, "", ly, 2.0); src = "lyrics.ovh"; break }
                    }
                } catch (e: Throwable) { lastErr = e.message ?: e.javaClass.simpleName }
            }
        }

        val b = best
        if (b == null) {
            if (!answered) { onDone(null, "مفيش اتصال بالمصدر (" + lastErr.take(60) + ") — جرّب تاني"); return }
            save(app, t, M_FULL, LyricsResult(name, by, emptyList(), true, 1, 1, emptySet(), "", emptyList(), true, src))
            onDone(null, if (retry) "ما لقيتش نتيجة تانية — جرّب ✍ كلمات يدوي" else "ما لقيتش الأغنية دي (LRCLIB + مصدر احتياطي) — جرّب ✍ كلمات يدوي")
            return
        }
        lastId[k] = b.id
        val tot = t.durMs / 1000.0
        val lines = if (b.synced.isNotBlank()) parseLrc(b.synced, tot) else emptyList()
        val res = if (lines.size >= 2) {
            LyricsResult(name, by, lines, true, 1, 1, emptySet(), "", lines.map { it.text }, true, src)
        } else {
            val tagRe = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]")   // (v246) لو بنرجع للنص المتزامن كنص عادي نشيل علامات الوقت
            val pl = (if (b.plain.isNotBlank()) b.plain else b.synced).lines().map { tagRe.replace(it, "").trim() }.filter { it.isNotEmpty() }
            if (pl.isEmpty()) { onDone(null, "النتيجة فاضية"); return }
            LyricsResult(name, by, pl.map { LyricLine(0.0, 0.0, it) }, true, 1, 1, emptySet(), "من غير توقيت", pl, false, src)
        }
        save(app, t, M_FULL, res); onDone(res, "")
    }

    /** (v223) كلمات من المستخدم (لصق/تعديل): بتتحفظ زي ما هي، والتوقيت بيتحسب محليًا من لحظات الغنا في الصوت (من غير جيميناي) */
    fun startManual(ctx: Context, t: Track, hint: Pair<String, String>?, lines: List<String>, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val app = ctx.applicationContext
        val k = key(t, M_FULL)
        if (running.putIfAbsent(k, true) != null) { onDone(null, "بتتكتب دلوقتي"); return }
        Thread {
            try { finishApprox(app, t, (hint?.first ?: "").ifBlank { t.title }, (hint?.second ?: "").ifBlank { t.artist }, lines, onStage, onDone) }
            catch (e: Throwable) { LogStore.err("lyrics-manual", e); onDone(null, "فشل: " + (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(110)) }
            finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    /** توقيت تقريبي محلي: الكلمات بتتوزّع على لحظات الغنا الفعلية في الصوت (Karaoke.segments) — من غير أي طلب شبكة */
    private fun finishApprox(app: Context, t: Track, name: String, by: String, lines: List<String>, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        onStage("⏱ بوزّع الكلمات على لحظات الغنا في الأغنية…")
        val segs = try { Karaoke.segments(app, t) } catch (e: Throwable) { LogStore.err("lyrics-vad", e); emptyList() }
        val placed = if (segs.isNotEmpty()) Karaoke.place(lines, segs) else emptyList()
        val ok = placed.isNotEmpty()
        val res = LyricsResult(name, by, if (ok) placed else estimate(t, lines), true, 1, 1, emptySet(), "التوقيت تقريبي", lines)
        save(app, t, M_FULL, res); onDone(res, "")
    }

    /** السطر الشغّال دلوقتي عند الوقت ده (آخر سطر بدأ)، أو -1 قبل أول سطر */
    fun indexAt(lines: List<LyricLine>, sec: Double): Int {
        var lo = 0; var hi = lines.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (lines[m].start <= sec) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }
}
