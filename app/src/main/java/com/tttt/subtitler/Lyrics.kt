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
 * (v250) نسخة كلمات مرشّحة لأغنية (من أي مصدر). synced = نص LRC أو فاضي · plain = كلمات عادية أو فاضي · dur بالثواني (0 = مجهولة).
 * القايمة بتتحفظ على الجهاز مع الأغنية، واليوزر بيختار منها من «🎼 نسخ بديلة للكلمات».
 */
class LyrCand(val src: String, val id: String, val title: String, val artist: String,
              val synced: String, val plain: String, val dur: Double, val score: Double) {
    fun key() = "$src:$id"
}

/**
 * (v242 · مرحلة 1) محرك الكلمات اتفضّى من الطريقتين القديمتين (ترانسكريبت الصوت · جلب جيميناي بالاسم).
 * (v243) المصدر الأساسي: LRCLIB (من غير مفاتيح) بعد تطبيع الاسم العربي (LyricsNorm.kt) — LRC متزامن أو كلمات من غير توقيت.
 * (v250) إضافات:
 *   • مصادر جديدة من أداة lyrics-api (LyricsApi.kt): Musixmatch (متزامن) ← YouTube Music (عادي) قبل Lyrics.ovh
 *   • جيميناي (من غير Google Search) بيقترح صيغ بديلة للاسم، وبنبحث بيها عن نسخ إضافية (تلقائي لو مفيش نتيجة، ويدوي من «🎼 نسخ بديلة»)
 *   • كل النسخ اللي اتلقت بتتحفظ محليًا والمستخدم يختار منها
 *   • ترجمة عربية للسطور الأجنبية بجيميناي (بتتحفظ محليًا، والكلمات الأصلية والسطور العربي بتفضل زي ما هي)
 */
object LyricsEngine {
    const val M_FULL = "full"
    /** مفيش أوضاع تانية دلوقتي — بنسيبها دالة عشان باقي الكود مايتغيّرش */
    fun mode(): String = M_FULL

    /**
     * (v342) بوابة جيميناي: أي استخدام لجيميناي في الكلمات (اقتراح أسماء · كتابة الكلمات · ضبط التوقيت) بيسأل الأول —
     * الواجهة بتعرض إشعار «أيوه/لا» وبتعتبر عدم الرد خلال 5 ثواني = لا. بتتنادى من خيط خلفي وبترجّع true لو اتوافق عليها.
     * زرار «🤖 جيميناي» الصريح بيعدّي من غير سؤال (gemini = true).
     */
    @Volatile var geminiGate: ((String) -> Boolean)? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun key(t: Track, m: String) = "t${t.id}_${t.durMs}" + (if (m == M_FULL) "_full" else "") + ".json"
    private fun candFile(ctx: Context, t: Track) = File(dir(ctx), "t${t.id}_${t.durMs}_cands.json")
    private fun trFile(ctx: Context, t: Track) = File(dir(ctx), "t${t.id}_${t.durMs}_tr.json")
    private val running = ConcurrentHashMap<String, Boolean>()
    private val trRun = ConcurrentHashMap<String, Boolean>()
    @Volatile var version = 0; private set      // بيزيد مع كل حفظ — الشاشة بتقارنه عشان تعرف تحدّث

    fun isRunning(t: Track, m: String) = running.containsKey(key(t, m))
    fun anyRunning() = running.isNotEmpty()
    fun trRunning(t: Track) = trRun.containsKey("tr${t.id}_${t.durMs}")

    fun cached(ctx: Context, t: Track, m: String): LyricsResult? = try {
        val f = File(dir(ctx), key(t, m))
        val r = parse(JSONObject(f.readText()))
        // «ما لقيتش» بتتحفظ 12 ساعة بس عشان ما نضغطش على المصدر — بعدها بنجرّب تاني
        if (r.lines.isEmpty() && r.raw.isEmpty() && System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) null else r
    } catch (_: Throwable) { null }
    fun clear(ctx: Context, t: Track, m: String) { try { File(dir(ctx), key(t, m)).delete() } catch (_: Throwable) {}; version++ }
    /** مسح كل حاجة للأغنية (كلمات + النسخ المرشّحة + الترجمة) — لما الاسم نفسه بيتغيّر أو ريفريش كامل */
    fun clearAll(ctx: Context, t: Track) {
        clear(ctx, t, M_FULL)
        try { candFile(ctx, t).delete(); trFile(ctx, t).delete() } catch (_: Throwable) {}
        lastId.remove(key(t, M_FULL))
    }

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

    // ===================== النسخ المرشّحة (محفوظة محليًا) =====================
    fun srcLabel(src: String): String = if (src.endsWith("+ai")) srcLabel(src.removeSuffix("+ai")) + " + توقيت ذكاء اصطناعي" else if (src.endsWith("+local")) srcLabel(src.removeSuffix("+local")) + " + توقيت محلي" else when (src) {
        "lrclib" -> "LRCLIB"; "musixmatch" -> "Musixmatch"; "youtube" -> "YouTube Music"; "lyrics.ovh" -> "Lyrics.ovh"; "gemini" -> "Gemini"; "gemini-audio" -> "Gemini (من الصوت)"; else -> src
    }

    fun candidates(ctx: Context, t: Track): List<LyrCand> = try {
        val a = JSONObject(candFile(ctx, t).readText()).optJSONArray("list") ?: JSONArray()
        val out = ArrayList<LyrCand>()
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i) ?: continue
            out.add(LyrCand(x.optString("s"), x.optString("i"), x.optString("t"), x.optString("a"), x.optString("sy"), x.optString("pl"), x.optDouble("d", 0.0), x.optDouble("sc", 0.0)))
        }
        out
    } catch (_: Throwable) { emptyList() }

    /** مفتاح النسخة المختارة حاليًا («المصدر:الرقم») أو "" */
    fun pickedId(ctx: Context, t: Track): String = try { JSONObject(candFile(ctx, t).readText()).optString("picked") } catch (_: Throwable) { "" }

    private fun saveCands(ctx: Context, t: Track, list: List<LyrCand>, picked: String) {
        try {
            val a = JSONArray()
            for (c in list.sortedByDescending { it.score }.take(14)) a.put(JSONObject().put("s", c.src).put("i", c.id).put("t", c.title).put("a", c.artist)
                .put("sy", c.synced).put("pl", c.plain).put("d", c.dur).put("sc", c.score))
            candFile(ctx, t).writeText(JSONObject().put("picked", picked).put("list", a).toString())
        } catch (_: Throwable) {}
    }

    /** عدد سطور النسخة (للعرض في قايمة النسخ) */
    fun lineCount(t: Track, c: LyrCand): Int =
        if (c.synced.isNotBlank()) parseLrc(c.synced, t.durMs / 1000.0).size
        else c.plain.lines().count { it.isNotBlank() }

    /** يخلّي نسخة معيّنة هي الكلمات الحالية للأغنية (وبتتحفظ محليًا مع علامة «المختارة») */
    fun applyCand(ctx: Context, t: Track, hint: Pair<String, String>?, c: LyrCand): LyricsResult? {
        val app = ctx.applicationContext
        val name = (hint?.first ?: "").ifBlank { t.title }
        val by = (hint?.second ?: "").ifBlank { t.artist }
        val res = build(t, name, by, c) ?: return null
        save(app, t, M_FULL, res)
        lastId[key(t, M_FULL)] = c.key()
        saveCands(app, t, candidates(app, t), c.key())
        return res
    }

    // ===================== LRCLIB + باقي المصادر =====================
    private const val API = "https://lrclib.net/api/"
    private const val UA = "Subtitler/2.50 (Android; lyrics client)"
    private val lastId = ConcurrentHashMap<String, String>()   // آخر نسخة اتعرضت لكل أغنية — «الكلمات غلط» بتتخطاها

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

    /**
     * درجة تطابق نتيجة: الاسم (أي واحد من الأسماء المطلوبة) + الفنان + المدة. null لو مش نفس الأغنية.
     * maxDiff = أقصى فرق مدة بالثواني (10 في الجلب العادي، أوسع لما بندوّر على نسخ تانية).
     */
    private fun rate(title: String, artist: String, dur: Double, hasSyn: Boolean, t: Track, wantT: List<String>, wantA: String, maxDiff: Double): Double? {
        val have = t.durMs > 0 && dur > 0
        val diff = if (have) Math.abs(dur - t.durMs / 1000.0) else 0.0
        if (have && diff > maxDiff) return null
        val durOk = have && diff <= 4.0
        val tsim = wantT.maxOfOrNull { ArNorm.sim(it, title) } ?: 0.0
        val asim = if (wantA.isBlank() || artist.isBlank()) 0.0 else ArNorm.sim(wantA, artist)
        if (tsim < 0.5 && !(durOk && asim >= 0.5)) return null
        val score = tsim * 2 + asim + (if (durOk) 1.5 else 0.0) + (if (hasSyn) 0.3 else 0.0)
        return if (score < 2.0) null else score
    }

    private fun lrcCand(o: JSONObject, x: Ctx): LyrCand? {
        if (o.optBoolean("instrumental", false)) return null
        val syn = str(o, "syncedLyrics"); val plain = str(o, "plainLyrics")
        if (syn.isBlank() && plain.isBlank()) return null
        val title = str(o, "trackName").ifBlank { str(o, "name") }; val artist = str(o, "artistName")
        val dur = o.optDouble("duration", 0.0)
        val sc = rate(title, artist, dur, syn.isNotBlank(), x.t, x.wantT, x.wantA, x.maxDiff) ?: return null
        return LyrCand("lrclib", o.optLong("id", 0L).toString(), title, artist, syn, plain, dur, sc)
    }

    /** سياق بحث واحد: النتايج المتجمّعة، وهل أي مصدر رد فعلًا (عشان نفرّق «مفيش نتيجة» عن «مفيش نت») */
    private class Ctx(val t: Track, val wantA: String, val maxDiff: Double, first: List<String>) {
        val wantT = ArrayList<String>(first)
        val found = ArrayList<LyrCand>()
        var answered = false
        var lastErr = ""
        fun add(c: LyrCand?) { if (c != null && found.none { it.key() == c.key() }) found.add(c) }
        fun addWant(s: String) { if (s.isNotBlank() && wantT.none { it == s }) wantT.add(s) }
    }

    private fun lrclibRound(x: Ctx, tt: String, aa: String) {
        try {
            if (aa.isNotBlank() && x.t.durMs > 0) {
                val g = httpGet(API + "get?artist_name=" + enc(aa) + "&track_name=" + enc(tt) + "&duration=" + Math.round(x.t.durMs / 1000.0))
                x.answered = true
                if (g != null) x.add(lrcCand(JSONObject(g), x))
            }
            val q = "track_name=" + enc(tt) + (if (aa.isNotBlank()) "&artist_name=" + enc(aa) else "")
            val s = httpGet(API + "search?" + q)
            x.answered = true
            if (s != null) { val a = JSONArray(s); for (i in 0 until minOf(a.length(), 20)) x.add(lrcCand(a.getJSONObject(i), x)) }
        } catch (e: Throwable) { x.lastErr = e.message ?: e.javaClass.simpleName }
    }

    private fun mxmRound(x: Ctx, tt: String, aa: String) {
        try {
            val hits = LyricsApi.mxmFind(tt, aa) { ti, ar, d -> rate(ti, ar, d, true, x.t, x.wantT, x.wantA, x.maxDiff) != null }
            x.answered = true
            for (h in hits) {
                val sc = rate(h.title, h.artist, h.dur, h.synced.isNotBlank(), x.t, x.wantT, x.wantA, x.maxDiff) ?: continue
                x.add(LyrCand(h.src, h.id, h.title, h.artist, h.synced, h.plain, h.dur, sc))
            }
        } catch (e: Throwable) { x.lastErr = e.message ?: e.javaClass.simpleName }
    }

    private fun ytRound(x: Ctx, tt: String, aa: String) {
        try {
            val h = LyricsApi.ytFind(tt, aa) { text -> x.wantT.any { ArNorm.sim(it, text) >= 0.5 } }
            x.answered = true
            if (h != null) x.add(LyrCand(h.src, h.id, h.title, h.artist.ifBlank { aa }, "", h.plain, 0.0, 2.1))
        } catch (e: Throwable) { x.lastErr = e.message ?: e.javaClass.simpleName }
    }

    private fun ovhRound(x: Ctx, tt: String, aa: String) {
        try {
            val r = httpGet("https://api.lyrics.ovh/v1/" + enc(aa).replace("+", "%20") + "/" + enc(tt).replace("+", "%20"))
            x.answered = true
            if (r != null) {
                var ly = str(JSONObject(r), "lyrics").replace("\r", "").trim()
                if (ly.startsWith("Paroles de la chanson")) ly = ly.lines().drop(1).joinToString("\n").trim()   // سطر دعائي بيبدأ بيه Lyrics.ovh أحيانًا
                if (ly.lines().count { it.isNotBlank() } >= 4) x.add(LyrCand("lyrics.ovh", "0", tt, aa, "", ly, 0.0, 2.0))
            }
        } catch (e: Throwable) { x.lastErr = e.message ?: e.javaClass.simpleName }
    }

    // ===================== جيميناي: صيغ بديلة للاسم (من غير Google Search) =====================
    private fun cleanJson(s: String) = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    /**
     * (v250) بيسأل جيميناي من معرفته هو بس (من غير أداة البحث/Grounding) عن صيغ تانية للاسم والفنان بتتسجّل بيها الأغنية في قواعد الكلمات:
     * الاسم الأصلي، الكتابة بالحروف اللاتينية/العربية، تهجئات الفنان… مفيش مفاتيح = قايمة فاضية.
     */
    fun altNames(name: String, by: String, skip: Collection<String>): List<Pair<String, String>> {
        val keys = Cfg.allMainKeys()
        if (keys.isEmpty()) return emptyList()
        val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
        val pr = buildString {
            append("You help find song lyrics in lyrics databases (LRCLIB, Musixmatch). Song title: \"$name\"")
            if (by.isNotBlank()) append(", artist: \"$by\"")
            append(". The title/artist may be misspelled, transliterated (Arabic <-> Latin), shortened, or contain junk like (Official Video). ")
            append("Using ONLY your own knowledge (no web browsing), suggest up to 6 alternative title/artist forms under which this same song is likely listed: ")
            append("the original-language title, the Latin-script and Arabic-script spellings, the official title, common alternate spellings of the artist, and the original song if this looks like a cover or remix. ")
            append("Do not invent songs. If you do not recognize it, return only a cleaned-up title. ")
            append("Return JSON only: {\"alts\":[{\"title\":\"\",\"artist\":\"\"}]}")
        }
        val seen = HashSet<String>(); skip.forEach { seen.add(ArNorm.fold(it)) }
        for (k in 0 until keys.size.coerceAtMost(3)) {
            try {
                val r = Api.generate(model, keys[k % keys.size], pr, null, 1024, 0.3, true, false)
                val a = JSONObject(cleanJson(r.text)).optJSONArray("alts") ?: continue
                val out = ArrayList<Pair<String, String>>()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val tt = o.optString("title").trim(); val aa = o.optString("artist").trim()
                    if (tt.isEmpty() || !seen.add(ArNorm.fold(tt + " " + aa))) continue
                    out.add(tt to aa)
                    if (out.size >= 6) break
                }
                return out
            } catch (e: Throwable) { LogStore.err("lyrics-altnames", e) }
        }
        return emptyList()
    }

    // ===================== LRC → سطور =====================
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
     * (v342) توقيت محلي من غير شبكة ولا جيميناي: بنحلّل طاقة الصوت (Karaoke.envelope)، نحدد مساحة الغنا (أول/آخر صوت متواصل)،
     * ونوزّع السطور عليها بنسبة طول كل سطر بس على الفريمات «النشطة» (يعني الصمت/الفواصل ما بتاخدش وقت)، وبعدين نشدّ كل بداية لأقرب بداية صوت.
     * النتيجة تقريبية (مش بتسمع الكلام) — ومتعلّمة كده في الملاحظة. null لو الصوت ما اتحلّلش.
     */
    /** (v357) بيقرا مصفوفة أوقات البداية من رد جيميناي: لازم العدد يطابق عدد السطور، ومن 0 لأقل من مدة الأغنية؛ المتساوي بيتزاح شوية عشان الترتيب يفضل تصاعدي */
    private fun parseStarts(txt: String, n: Int, tot: Double): DoubleArray? {
        val a = txt.indexOf('['); val b = txt.lastIndexOf(']'); if (a < 0 || b <= a) return null
        val js = try { JSONArray(txt.substring(a, b + 1)) } catch (_: Throwable) { return null }
        if (js.length() != n) return null
        val st = DoubleArray(n)
        for (k in 0 until n) { val v = js.optDouble(k, Double.NaN); if (v.isNaN() || v < 0.0) return null; st[k] = v }
        for (k in 1 until n) { if (st[k] < st[k - 1]) return null; if (st[k] - st[k - 1] < 0.4) st[k] = st[k - 1] + 0.4 }
        if (st[n - 1] >= tot) return null
        return st
    }
    /**
     * (v357) توقيت بالذكاء الاصطناعي من غير ما الأغنية تتبعت: جيميناي بياخد النص واسم الأغنية والمغني والمدة بس (نص من غير أي صوت)،
     * ويقدّر وقت بداية كل سطر من معرفته بالتسجيل الحقيقي (طول المقدمة، المذهب المتكرر، الفواصل الموسيقية). النتيجة تقريبية وممكن تتزحلق.
     * null لو مفيش مفتاح أو الرد مش صالح — وساعتها بنكمّل بالتوقيت المحلي/الموزّع زي الأول.
     */
    private fun aiTextSync(name: String, by: String, raw: List<String>, src: String, tot: Double): LyricsResult? {
        if (tot < 30.0 || raw.size < 4 || raw.size > 220) return null
        val keys = SongId.textKeys(); if (keys.isEmpty()) return null
        val p = buildString {
            append("You are a lyrics timing expert. Estimate when each line of this song's lyrics starts, WITHOUT hearing the audio: use your knowledge of the real recording ")
            append("(length of the intro, verse/chorus structure, instrumental breaks, tempo, repeated choruses) together with the lyric text itself.\n\n")
            append("Song: \"").append(name).append("\"\nArtist: \"").append(by).append("\"\nTotal duration: ").append(Math.round(tot)).append(" seconds\n\nLINES (numbered):\n")
            raw.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
            append("\nRULES:\n- Return the START time in seconds (one decimal) of EVERY line, in order: exactly ").append(raw.size).append(" numbers.\n")
            append("- Increasing order; the first line starts after the real intro; the last start must be below ").append(Math.round(tot)).append(".\n")
            append("- A sung line of normal tempo lasts roughly 2-6 seconds; leave a longer gap only for an instrumental break, a long intro/outro or a held note. A repeated chorus is sung at the same pace each time.\n")
            append("- If unsure, distribute the lines using the song structure and the length of each line instead of refusing.\n")
            append("OUTPUT: one JSON array of numbers only, no markdown, e.g. [12.5,16.0,19.8]")
        }
        var st: DoubleArray? = null
        loop@ for (k in keys.take(3)) {
            for (useSearch in booleanArrayOf(true, false)) {
                try {
                    val r = Api.generate(Models.DEFAULT, k, p, null, 4000, 0.1, true, useSearch)
                    st = parseStarts(r.text, raw.size, tot); if (st != null) break@loop
                } catch (e: ApiErr) { if (e.code == 429) continue@loop } catch (_: Throwable) {}
            }
        }
        val s = st ?: return null
        val lines = raw.mapIndexed { i, tx ->
            val en = if (i + 1 < raw.size) minOf(s[i + 1], s[i] + 8.0) else minOf(tot, s[i] + 6.0)
            LyricLine(s[i], maxOf(en, s[i] + 0.5), tx)
        }
        return LyricsResult(name, by, lines, true, 1, 1, emptySet(), "توقيت تقريبي بالذكاء الاصطناعي (من غير سماع الصوت — ممكن يتزحلق)", raw, true, src + "+ai")
    }

    /** (v345) توقيت تقريبي من غير أي سماع: السطور بتتوزّع على مدة الأغنية بنسبة طول كل سطر (بعد مقدمة ~6% وقبل نهاية ~4%) */
    private fun evenSync(name: String, by: String, raw: List<String>, src: String, tot: Double): LyricsResult? {
        if (tot < 20.0 || raw.size < 2) return null
        val a0 = tot * 0.06; val span = tot * 0.90
        val w = raw.map { maxOf(6, it.replace(" ", "").length).toDouble() }; val sw = w.sum()
        var acc = 0.0; val starts = ArrayList<Double>()
        for (i in raw.indices) { starts.add(a0 + acc / sw * span); acc += w[i] }
        val lines = raw.mapIndexed { i, tx ->
            val st = starts[i].coerceAtMost(tot - 1.0)
            val en = if (i + 1 < raw.size) maxOf(starts[i + 1], st + 0.5) else minOf(tot, st + 4.0)
            LyricLine(st, en, tx)
        }
        return LyricsResult(name, by, lines, true, 1, 1, emptySet(), "توقيت تقريبي (موزّع على مدة الأغنية)", raw, true, src + "+local")
    }

    private fun localSync(app: Context, t: Track, name: String, by: String, raw: List<String>, src: String): LyricsResult? {
        val tot = t.durMs / 1000.0
        if (tot < 20.0 || raw.size < 4) return null
        val env0 = try { Karaoke.envelope(app, t) } catch (_: Throwable) { null }
        if (env0 == null || env0.size < 60) return evenSync(name, by, raw, src, tot)   // (v345) صوت من النت/مفيش تحليل محلي: توزيع على المدة من غير سماع
        val env = env0
        val hop = 0.05   // نفس HOP بتاع Karaoke (إطار كل 50ms)
        val sorted = env.sortedArray()
        val thr = sorted[(sorted.size * 0.90).toInt().coerceAtMost(sorted.size - 1)] * 0.12f
        val act = BooleanArray(env.size) { env[it] > thr }
        // مساحة الغنا: أول وآخر 1.2 ثانية متواصلة نشطة
        val need = (1.2 / hop).toInt().coerceAtLeast(3)
        fun runAt(i: Int): Boolean { if (i + need > act.size) return false; for (k in i until i + need) if (!act[k]) return false; return true }
        var a = 0; while (a < act.size && !runAt(a)) a++
        var b = act.size - 1; while (b > a && !runAt(maxOf(0, b - need + 1))) b--
        if (b - a < need * 4) return evenSync(name, by, raw, src, tot)
        val cum = IntArray(b - a + 2)   // cum[i] = عدد الفريمات النشطة قبل a+i
        for (i in 0..(b - a)) cum[i + 1] = cum[i] + (if (act[a + i]) 1 else 0)
        val totalAct = cum[b - a + 1]
        if (totalAct < need * 4) return evenSync(name, by, raw, src, tot)
        val w = raw.map { maxOf(6, it.replace(" ", "").length).toDouble() }
        val sw = w.sum()
        var acc = 0.0
        val starts = ArrayList<Double>()
        for (i in raw.indices) {
            val target = (acc / sw * totalAct).toInt()
            var lo = 0; var hi = b - a   // أول فريم cum[f] >= target
            while (lo < hi) { val m = (lo + hi) / 2; if (cum[m + 1] > target) hi = m else lo = m + 1 }
            var st = (a + lo) * hop
            st = Karaoke.snapOnset(env, st, 0.8)
            if (starts.isNotEmpty() && st < starts.last() + 1.0) st = starts.last() + 1.0
            starts.add(st); acc += w[i]
        }
        val lines = raw.mapIndexed { i, tx ->
            val st = starts[i].coerceAtMost(tot - 1.0)
            val en = if (i + 1 < raw.size) maxOf(starts[i + 1], st + 0.5) else minOf(tot, st + 4.0)
            LyricLine(st, en, tx)
        }
        return LyricsResult(name, by, lines, true, 1, 1, emptySet(), "توقيت تقريبي من الصوت على الجهاز", raw, true, src + "+local")
    }

    /** نسخة مرشّحة → نتيجة كلمات (متزامنة لو فيها LRC سليم، وإلا سطور من غير توقيت). null لو فاضية */
    private fun build(t: Track, name: String, by: String, b: LyrCand): LyricsResult? {
        val tot = t.durMs / 1000.0
        val lines = if (b.synced.isNotBlank()) parseLrc(b.synced, tot) else emptyList()
        if (lines.size >= 2) return LyricsResult(name, by, lines, true, 1, 1, emptySet(), "", lines.map { it.text }, true, b.src)
        val tagRe = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]")   // (v246) لو بنرجع للنص المتزامن كنص عادي نشيل علامات الوقت
        val pl = (if (b.plain.isNotBlank()) b.plain else b.synced).lines().map { tagRe.replace(it, "").trim() }.filter { it.isNotEmpty() }
        if (pl.isEmpty()) return null
        return LyricsResult(name, by, pl.map { LyricLine(0.0, 0.0, it) }, true, 1, 1, emptySet(), "من غير توقيت", pl, false, b.src)
    }

    /**
     * جلب الكلمات. الترتيب: LRCLIB (صيغ الاسم المطبّعة بالترتيب) ← Musixmatch (لو مفيش نسخة متزامنة) ← YouTube Music ← Lyrics.ovh
     * ← وأخيرًا جيميناي يقترح صيغ بديلة للاسم ونعيد البحث بيها. كل النسخ اللي اتلقت بتتحفظ محليًا، وأحسن واحدة هي المعروضة.
     * بيرفض أي نتيجة مدتها بعيدة أو اسمها مش قريب. فشل الشبكة ما بيتحفظش كـ«مفيش كلمات»؛ «مفيش نتيجة» بتتحفظ 12 ساعة.
     */
    fun start(ctx: Context, t: Track, mode: String, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, allowAi: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit, gemini: Boolean = false) {
        val app = ctx.applicationContext
        val k = key(t, M_FULL)
        if (running.putIfAbsent(k, true) != null) { onDone(null, "بتتجاب دلوقتي"); return }
        Thread {
            try { fetch(app, t, hint, retry, fresh, onStage, onDone, allowAi, gemini) }
            catch (e: Throwable) { LogStore.err("lyrics-fetch", e); onDone(null, "فشل: " + (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(110)) }
            finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    /**
     * (v331) بحث LRCLIB بالألبوم والمغني كل واحد لوحده (بعد ما بحث الاسم وحده فشل): اسم+ألبوم · مغني+اسم · ألبوم+اسم · المغني بس (والنتايج بتتقيّم بالاسم/المدة).
     * [done] = لقينا نتيجة كويسة خلاص؛ بنوقف أول ما تتحقق. أقصى 12 طلب.
     */
    private fun lrclibExtra(x: Ctx, names: List<String>, artists: List<String>, albums: List<String>, onStage: (String) -> Unit, done: () -> Boolean) {
        var calls = 0
        fun get(q: String, lbl: String) {
            if (calls >= 12 || done()) return
            calls++
            onStage("🔎 [$lbl] بدوّر في LRCLIB…")
            try {
                val s = httpGet(API + "search?" + q)
                x.answered = true
                if (s != null) { val a = JSONArray(s); for (i in 0 until minOf(a.length(), 20)) x.add(lrcCand(a.getJSONObject(i), x)) }
            } catch (e: Throwable) { x.lastErr = e.message ?: e.javaClass.simpleName }
        }
        val ns = names.filter { it.isNotBlank() }.distinct().take(4)
        val ars = artists.filter { it.isNotBlank() }.distinct().take(2)
        val als = albums.filter { it.isNotBlank() }.distinct().take(2)
        for (al in als) for (tt in ns) get("track_name=" + enc(tt) + "&album_name=" + enc(al), "الألبوم")
        for (ar in ars) for (tt in ns) get("q=" + enc(ar + " " + tt), "المغني")
        for (al in als) for (tt in ns) get("q=" + enc(al + " " + tt), "الألبوم")
        for (ar in ars) get("artist_name=" + enc(ar), "المغني لوحده")
    }

    private fun fetch(app: Context, t: Track, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit, allowAi: Boolean = true, gem: Boolean = false) {
        val k = key(t, M_FULL)
        val hasKeys = Cfg.allMainKeys().isNotEmpty()
        // (v342) جيميناي بالطلب بس: السؤال بيتسأل مرة لكل خطوة، والرفض/عدم الرد = نكمّل من غير جيميناي
        var askedWrite: Boolean? = null
        fun mayWrite(): Boolean {
            if (!hasKeys) return false
            if (gem) return true
            askedWrite?.let { return it }
            val a = try { geminiGate?.invoke("🤖 مالقيتش الكلمات في المصادر — جيميناي يكتبها ويوقّتها؟ (بيسحب كوتا)") ?: false } catch (_: Throwable) { false }
            askedWrite = a; return a
        }
        if (!fresh && !retry) {
            val c = cached(app, t, M_FULL)
            if (c != null && c.lines.isNotEmpty() && c.complete) { onDone(c, ""); return }   // (v331) نتيجة جزئية قديمة (التطبيق اتقفل في النص) بتتكمّل من الأول
        }
        val name = (hint?.first ?: "").ifBlank { t.title }
        val by = (hint?.second ?: "").ifBlank { t.artist }
        // (v298) مجموعات الأسماء: العربي لوحده والإنجليزي لوحده (لو التعرّف جاب الصيغتين) — كل مجموعة بتعدّي على كل المصادر قبل اللي بعدها
        val hasAr = { s: String -> Regex("[\\u0600-\\u06FF]").containsMatchIn(s) }
        val groups = ArrayList<Pair<String, List<Pair<String, String>>>>()
        groups.add((if (hasAr(name + by)) "عربي" else "إنجليزي") to ArNorm.variants(name, by))
        SongId.alt(t.id)?.let { (an, aa) ->
            val n2 = an.ifBlank { name }; val b2 = aa.ifBlank { by }
            if (ArNorm.fold(n2 + " " + b2) != ArNorm.fold(name + " " + by)) groups.add((if (hasAr(n2 + b2)) "عربي" else "إنجليزي") to ArNorm.variants(n2, b2))
        }
        val vars = groups.flatMap { it.second }.distinct()
        val skip: String? = if (retry) (lastId[k] ?: pickedId(app, t).ifBlank { null }) else null
        val x = Ctx(t, by, 10.0, listOf(name))
        for (p in vars) x.addWant(p.first)   // (v298) أسماء المجموعتين كلها مقبولة في مطابقة النتايج (عربي/إنجليزي)
        if (retry) for (c in candidates(app, t)) x.add(c)   // «الكلمات غلط»: النسخ المحفوظة قبل كده (من أي مصدر) بتدخل في الاختيار من غير ما نستنى الشبكة
        fun best(): LyrCand? = x.found.filter { it.key() != skip }.maxByOrNull { it.score }
        fun runChain(vars: List<Pair<String, String>>, lbl: String) {

            // 1) LRCLIB
            var n = 0
            for ((tt, aa) in vars) {
                n++
                onStage("🔎 [$lbl] بدوّر في LRCLIB ($n/${vars.size})…")
                lrclibRound(x, tt, aa)
                if (best() != null) break
            }
            if (best() == null && x.answered) {
                onStage("🔎 [$lbl] بحث حر في LRCLIB…")
                try {
                    val s = httpGet(API + "search?q=" + enc((ArNorm.clean(vars.firstOrNull()?.first ?: name) + " " + ArNorm.clean(vars.firstOrNull()?.second ?: by)).trim()))
                    if (s != null) { val a = JSONArray(s); for (i in 0 until minOf(a.length(), 20)) x.add(lrcCand(a.getJSONObject(i), x)) }
                } catch (e: Throwable) { x.lastErr = e.message ?: e.javaClass.simpleName }
            }

            // 2) Musixmatch (من أداة lyrics-api): لو مفيش نسخة متزامنة لسه
            fun hasSynced() = x.found.any { it.synced.isNotBlank() && it.key() != skip }
            if (!hasSynced()) {
                var n2 = 0
                for ((tt, aa) in vars) {
                    if (++n2 > 3) break
                    onStage("🔎 بدوّر في Musixmatch ($n2)…")
                    mxmRound(x, tt, aa)
                    if (hasSynced()) break
                }
            }

            // 3) YouTube Music (كلمات عادية) — لو لسه مفيش حاجة
            if (best() == null) {
                var n3 = 0
                for ((tt, aa) in vars) {
                    if (++n3 > 2) break
                    onStage("🔎 بدوّر في YouTube Music ($n3)…")
                    ytRound(x, tt, aa)
                    if (best() != null) break
                }
            }

            // 4) Lyrics.ovh — كلمات عادية، ومحتاج فنان معروف
            if (best() == null && !(retry && skip != null && skip.startsWith("lyrics.ovh"))) {
                var n4 = 0
                for ((tt, aa) in vars) {
                    if (aa.isBlank()) continue
                    if (++n4 > 3) break
                    onStage("🔎 بدوّر في مصدر احتياطي ($n4)…")
                    ovhRound(x, tt, aa)
                    if (best() != null) break
                }
            }

        }
        for ((lbl, gv) in groups) { if (best() != null) break; runChain(gv, lbl) }

        // (v331) بحث بالمغني والألبوم كل واحد لوحده (عربي وإنجليزي)
        if (best() == null && x.answered) {
            val alt = SongId.alt(t.id)
            lrclibExtra(x, listOf(name, alt?.first ?: "", ArNorm.clean(name)), listOf(by, alt?.second ?: ""),
                listOf(SongId.albumOf(t.id), SongId.albumLat(t.id)), onStage) { best() != null }
        }

        if (best() == null && !allowAi) {   // (v331) لسه بيتعرّف على الأغنية: ما نحفظش «مفيش كلمات» ولا نشغّل جيميناي — الكلمات هتتجاب تاني أول ما الاسم يتحدّد
            onDone(null, "بستنى التعرّف على الأغنية (الاسم/المغني/الألبوم) قبل ما أكمّل الكلمات"); return
        }

        // 5) جيميناي يقترح صيغ بديلة للاسم (من غير Google Search) ونعيد البحث بيها
        if (best() == null && x.answered && mayWrite()) {
            onStage("🤖 جيميناي بيقترح صيغ تانية للاسم…")
            val alts = altNames(name, by, vars.map { it.first + " " + it.second })
            var n5 = 0
            for ((tt, aa) in alts) {
                if (++n5 > 4) break
                x.addWant(tt)
                onStage("🔎 بجرّب «$tt»…")
                lrclibRound(x, tt, aa)
                if (best() == null) mxmRound(x, tt, aa)
                if (best() != null) break
            }
        }

        // (v345) اتشال: تفريغ/كتابة الكلمات بجيميناي من سماع الصوت — الكلمات من المصادر بس، ولو من غير توقيت بتتوقّت محليًا
        var aiMsg = ""

        var b = best()
        // (v304) النسخة اللي اتلقت من غير توقيت: ندوّر في LRCLIB وMusixmatch بالاسم/المغني اللي المصدر نفسه رجّعهم، لو فيه نسخة متزامنة
        if (b != null && b.synced.isBlank() && !retry) {
            val tried = HashSet<String>(vars.map { ArNorm.fold(it.first + " " + it.second) })
            val pairs = ArrayList<Pair<String, String>>()
            pairs.add(b.title to b.artist)
            for (p in ArNorm.variants(b.title, b.artist)) pairs.add(p)
            var n6 = 0
            for ((tt, aa) in pairs) {
                if (tt.isBlank() || !tried.add(ArNorm.fold(tt + " " + aa))) continue
                if (++n6 > 3) break
                x.addWant(tt)
                onStage("🔎 بدوّر على نسخة متزامنة بالاسم «$tt»…")
                lrclibRound(x, tt, aa)
                mxmRound(x, tt, aa)
                val sync = x.found.filter { it.key() != skip && it.synced.isNotBlank() }.maxByOrNull { it.score }
                if (sync != null) { b = sync; break }
            }
        }
        if (b == null) {
            if (!x.answered) { onDone(null, "مفيش اتصال بالمصدر (" + x.lastErr.take(60) + ") — جرّب تاني"); return }
            save(app, t, M_FULL, LyricsResult(name, by, emptyList(), true, 1, 1, emptySet(), "", emptyList(), true, "lrclib"))
            val tail = if (aiMsg.isNotBlank()) " · جيميناي: $aiMsg" else ""
            onDone(null, (if (retry) "ما لقيتش نتيجة تانية — جرّب 🎼 نسخ بديلة أو ✍ كلمات يدوي" else "ما لقيتش الأغنية دي (LRCLIB + Musixmatch + YouTube Music + مصدر احتياطي) — جرّب 🎼 نسخ بديلة أو ✍ كلمات يدوي") + tail)
            return
        }
        val bf: LyrCand = b
        var res = build(t, name, by, bf)
        if (res == null) { onDone(null, "النتيجة فاضية"); return }
        // (v294) النسخة اللي اتلقت من غير توقيت: جيميناي يسمع الأغنية ويوقّت كل سطر (لو التطابق مع الصوت كويس؛ غير كده بنسيب النص زي ما هو)
        if (!res.synced && res.raw.size >= 4 && !retry) {
            // (v357) كلمات YouTube Music من غير توقيت: توقيت بالذكاء الاصطناعي من النص بس (من غير ما الصوت يتبعت لجيميناي)، ولو فشل بنكمّل بالطريقة المحلية
            if (bf.src == "youtube") {
                onStage("🤖 بوقّت الكلمات بالذكاء الاصطناعي (من غير رفع الصوت)…")
                val ai = try { aiTextSync(name, by, res.raw, bf.src, t.durMs / 1000.0) } catch (e: Throwable) { LogStore.err("lyrics-aisync", e); null }
                if (ai != null) { res = ai; save(app, t, M_FULL, ai) }
            }
            // (v342) توقيت محلي مجاني الأول (من طاقة الصوت على الجهاز — من غير جيميناي ولا مقاطع صوت)، وتظهر الكلمات بيه فورًا
            if (!res.synced) {
                onStage("⏱ بضبط التوقيت من الصوت على الجهاز…")
                val loc = try { localSync(app, t, name, by, res.raw, bf.src) } catch (e: Throwable) { LogStore.err("lyrics-localsync", e); null }
                if (loc != null) { res = loc; save(app, t, M_FULL, loc) }
                else save(app, t, M_FULL, LyricsResult(name, by, res.lines, true, 1, 1, emptySet(), "من غير توقيت", res.raw, false, bf.src))
            }
        }
        lastId[k] = bf.key()
        val merged = ArrayList<LyrCand>(x.found)
        for (c in candidates(app, t)) if (merged.none { it.key() == c.key() }) merged.add(c)   // النسخ اللي اتلقت قبل كده (نسخ بديلة) ما بتضيعش
        saveCands(app, t, merged, bf.key())
        save(app, t, M_FULL, res); onDone(res, "")
    }

    /**
     * (v250) «🔎 دوّر على نسخ تانية»: جيميناي يقترح صيغ بديلة للاسم (من غير Google Search) وندوّر بيها كلها في LRCLIB وMusixmatch
     * (بفرق مدة أوسع لحد 45ث عشان نلقط النسخ المختلفة: راديو/لايف/ريمكس). النسخ الجديدة بتتضاف للقايمة المحفوظة.
     * onDone(عدد النسخ الجديدة، رسالة).
     */
    fun moreVersions(ctx: Context, t: Track, hint: Pair<String, String>?, onStage: (String) -> Unit, onDone: (Int, String) -> Unit) {
        val app = ctx.applicationContext
        val k = key(t, M_FULL)
        if (running.putIfAbsent(k, true) != null) { onDone(0, "لسه شغّال… استنى شوية"); return }
        Thread {
            try {
                val name = (hint?.first ?: "").ifBlank { t.title }
                val by = (hint?.second ?: "").ifBlank { t.artist }
                val vars = ArNorm.variants(name, by)
                onStage("🤖 جيميناي بيقترح صيغ تانية للاسم…")
                val alts = altNames(name, by, vars.map { it.first + " " + it.second })
                val qs = LinkedHashMap<String, Pair<String, String>>()
                for (p in vars + alts) qs.putIfAbsent(ArNorm.fold(p.first + " " + p.second), p)
                val x = Ctx(t, by, 45.0, listOf(name))
                for (p in qs.values) x.addWant(p.first)
                var n = 0
                for (p in qs.values.take(8)) {
                    n++
                    onStage("🔎 بدوّر على نسخ تانية ($n/${minOf(qs.size, 8)})…")
                    lrclibRound(x, p.first, p.second)
                    if (n <= 5) mxmRound(x, p.first, p.second)
                }
                if (!x.answered) { onDone(0, "مفيش اتصال بالمصادر (" + x.lastErr.take(60) + ") — جرّب تاني"); return@Thread }
                val old = candidates(app, t)
                val merged = ArrayList<LyrCand>(old)
                var added = 0
                for (c in x.found) if (merged.none { it.key() == c.key() }) { merged.add(c); added++ }
                saveCands(app, t, merged, pickedId(app, t))
                onDone(added, if (added > 0) "لقيت $added نسخة جديدة" else "ما لقيتش نسخ جديدة")
            } catch (e: Throwable) {
                LogStore.err("lyrics-more", e); onDone(0, "فشل: " + (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(110))
            } finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }



    /** السطر الشغّال دلوقتي عند الوقت ده (آخر سطر بدأ)، أو -1 قبل أول سطر */
    fun indexAt(lines: List<LyricLine>, sec: Double): Int {
        var lo = 0; var hi = lines.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (lines[m].start <= sec) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }

    // ===================== (v250) ترجمة عربية للسطور الأجنبية =====================
    private fun sigOf(lines: List<LyricLine>): String = lines.size.toString() + ":" + lines.joinToString("\n") { it.text }.hashCode()

    private fun isArabicCh(ch: Char) = ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF'

    /** السطر أجنبي = حروفه غير العربية أكتر من العربية (السطور العربي والمخلوطة العربي-الغالب ما بتتترجمش) */
    fun isForeign(s: String): Boolean {
        var ar = 0; var ot = 0
        for (ch in s) { if (!Character.isLetter(ch)) continue; if (isArabicCh(ch)) ar++ else ot++ }
        return ot >= 2 && ot > ar
    }

    /** الترجمة المحفوظة لنفس الكلمات: قايمة بنفس ترتيب السطور، "" = السطر ما اتترجمش (عربي أصلًا). null = مفيش ترجمة محفوظة للكلمات دي */
    fun translation(ctx: Context, t: Track, r: LyricsResult): List<String>? = try {
        val o = JSONObject(trFile(ctx, t).readText())
        val a = o.getJSONArray("tr")
        if (o.optString("sig") != sigOf(r.lines) || a.length() != r.lines.size) null
        else List(a.length()) { a.optString(it, "") }
    } catch (_: Throwable) { null }

    fun clearTranslation(ctx: Context, t: Track) { try { trFile(ctx, t).delete() } catch (_: Throwable) {}; version++ }

    /** بيترجم السطور الأجنبية بس (دفعات 40 سطر) بجيميناي من غير Google Search. onDone(نجح؟، رسالة). بيتحفظ كله أو ولا حاجة */
    fun translate(ctx: Context, t: Track, r: LyricsResult, onStage: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        val k = "tr${t.id}_${t.durMs}"
        if (trRun.putIfAbsent(k, true) != null) { onDone(false, "الترجمة شغّالة دلوقتي"); return }
        Thread {
            try { doTranslate(app, t, r, onStage, onDone) }
            catch (e: Throwable) { LogStore.err("lyrics-tr", e); onDone(false, "فشل: " + (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(110)) }
            finally { trRun.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    private fun doTranslate(app: Context, t: Track, r: LyricsResult, onStage: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        val keys = Cfg.allMainKeys()
        if (keys.isEmpty()) { onDone(false, "الترجمة محتاجة مفتاح Gemini"); return }
        val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
        val idx = r.lines.indices.filter { isForeign(r.lines[it].text) }
        val out = Array(r.lines.size) { "" }
        val batches = idx.chunked(40)
        var rot = 0
        var lastErr = ""
        for ((bi, b) in batches.withIndex()) {
            onStage("🌐 بترجم السطور الأجنبية (${bi + 1}/${batches.size})…")
            val pr = buildString {
                append("ترجم سطور كلمات الأغنية دي للعربية. الأغنية: \"${r.title}\"" + (if (r.artist.isNotBlank()) " — ${r.artist}" else "") + ".\n")
                append("القواعد: ترجمة سلسة ومفهومة بتحافظ على المعنى والإحساس، من غير شرح ولا تعليقات. ")
                append("ماتغيّرش الأرقام، ماتدمجش سطرين في سطر، وماتسيبش سطر من غير ترجمة. ")
                append("لو السطر أصوات من غير معنى (زي la la la أو oh oh) رجّعه زي ما هو. لو السطر عربي أصلًا رجّع \"\".\n")
                append("السطور (الرقم: النص):\n")
                for (i in b) append(i).append(": ").append(r.lines[i].text.replace('\n', ' ')).append('\n')
                append("رجّع JSON بس بالشكل ده: {\"t\":{\"<الرقم>\":\"<الترجمة>\"}}")
            }
            var ok = false
            for (a in 0 until keys.size.coerceAtMost(4)) {
                try {
                    val res = Api.generate(model, keys[(rot + a) % keys.size], pr, null, 8192, 0.2, true, false)
                    val m = JSONObject(cleanJson(res.text)).optJSONObject("t") ?: throw java.io.IOException("رد غير مفهوم")
                    var got = 0
                    for (i in b) { val v = m.optString(i.toString(), "").trim(); if (v.isNotEmpty()) { out[i] = v; got++ } }
                    if (got == 0) throw java.io.IOException("الرد فاضي")
                    rot += a + 1; ok = true; break
                } catch (e: Throwable) { lastErr = e.message ?: e.javaClass.simpleName }
            }
            if (!ok) { onDone(false, "الترجمة ما كملتش (" + lastErr.replace('\n', ' ').take(70) + ") — جرّب تاني"); return }
        }
        try {
            val a = JSONArray(); for (s in out) a.put(s)
            trFile(app, t).writeText(JSONObject().put("sig", sigOf(r.lines)).put("tr", a).toString())
        } catch (e: Throwable) { onDone(false, "ما قدرتش أحفظ الترجمة"); return }
        version++
        onDone(true, "")
    }
}
