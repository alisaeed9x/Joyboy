package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashMap

/** سطر كلام متزامن: من start لـ end بالثواني */
class LyricLine(val start: Double, val end: Double, val text: String, val translation: String = "")

/** إصدار بديل كامل من مصدر آخر، يظهر في قائمة النسخ دون خلط كلماته بالإصدار الحالي. */
class LyricVersion(
    val title: String, val artist: String, val source: String, val sourceUrl: String,
    val lines: List<LyricLine>, val synced: Boolean = false
)

/** نتيجة كلمات أغنية. note = ملاحظة تتعرض فوق الكلمات (مثلًا «التوقيت تقريبي»). raw = سطور الكلمات من غير توقيت */
class LyricsResult(
    val title: String, val artist: String, val lines: List<LyricLine>,
    val complete: Boolean = true, val done: Int = 0, val total: Int = 0,
    val ok: Set<Int> = emptySet(), val note: String = "", val raw: List<String> = emptyList(),
    val synced: Boolean = true, val source: String = "",
    val alternatives: List<LyricVersion> = emptyList(), val sourceUrl: String = "", val aghaniChecked: Boolean = false
)

/**
 * محرك الكلمات: LRCLIB + Lyrics.ovh + AghaniLyrics، مع تخزين محلي وإصدارات بديلة وترجمة السطور الأجنبية إلى العربية عبر Gemini.
 * Gemini لا يستخدم Google Search grounding لجلب الكلمات أو الأغلفة؛ النصوص تُقرأ من مصادرها المباشرة وتُرسل للترجمة فقط عند الحاجة.
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
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); l.add(LyricLine(x.getDouble("s"), x.getDouble("e"), x.getString("t"), x.optString("tr"))) }
        val ok = HashSet<Int>(); val oa = o.optJSONArray("ok"); if (oa != null) for (i in 0 until oa.length()) ok.add(oa.getInt(i))
        val ra = o.optJSONArray("raw"); val raw = ArrayList<String>(); if (ra != null) for (i in 0 until ra.length()) { val x = ra.optString(i).trim(); if (x.isNotEmpty()) raw.add(x) }
        val alternatives = ArrayList<LyricVersion>()
        val av = o.optJSONArray("alternatives") ?: JSONArray()
        for (i in 0 until av.length()) try {
            val v = av.getJSONObject(i); val va = v.optJSONArray("lines") ?: JSONArray(); val vl = ArrayList<LyricLine>()
            for (j in 0 until va.length()) { val x = va.getJSONObject(j); vl.add(LyricLine(x.optDouble("s", 0.0), x.optDouble("e", 0.0), x.optString("t"), x.optString("tr"))) }
            if (vl.isNotEmpty()) alternatives.add(LyricVersion(v.optString("title"), v.optString("artist"), v.optString("source"), v.optString("url"), vl, v.optBoolean("synced", false)))
        } catch (_: Throwable) {}
        return LyricsResult(o.optString("title"), o.optString("artist"), l, o.optBoolean("complete", true), o.optInt("done", 0), o.optInt("total", 0), ok, o.optString("note"), raw, o.optBoolean("synced", true), o.optString("source"), alternatives, o.optString("sourceUrl"), o.optBoolean("aghaniChecked", false))
    }
    private fun save(ctx: Context, t: Track, m: String, r: LyricsResult) {
        fun lineArray(lines: List<LyricLine>): JSONArray {
            val a = JSONArray()
            for (x in lines) a.put(JSONObject().put("s", x.start).put("e", x.end).put("t", x.text).put("tr", x.translation))
            return a
        }
        val a = lineArray(r.lines)
        val oa = JSONArray(); for (i in r.ok.sorted()) oa.put(i)
        val ra = JSONArray(); for (x in r.raw) ra.put(x)
        val alt = JSONArray()
        for (v in r.alternatives) alt.put(JSONObject().put("title", v.title).put("artist", v.artist).put("source", v.source)
            .put("url", v.sourceUrl).put("synced", v.synced).put("lines", lineArray(v.lines)))
        try {
            File(dir(ctx), key(t, m)).writeText(JSONObject().put("title", r.title).put("artist", r.artist).put("lines", a)
                .put("complete", r.complete).put("done", r.done).put("total", r.total).put("ok", oa).put("note", r.note).put("raw", ra)
                .put("synced", r.synced).put("source", r.source).put("sourceUrl", r.sourceUrl).put("alternatives", alt)
                .put("aghaniChecked", r.aghaniChecked).put("v", 8).toString())
        } catch (_: Throwable) {}
        version++
    }

    /** توقيت تقريبي (موزّع بالتساوي) — بنستخدمه بس لو تحديد لحظات الغنا محليًا فشل */
    private fun estimate(t: Track, lines: List<String>): List<LyricLine> {
        val tot = (t.durMs / 1000.0).coerceAtLeast(30.0)
        val step = (tot * 0.85) / lines.size.coerceAtLeast(1); val off = tot * 0.07
        return lines.mapIndexed { i, x -> LyricLine(off + i * step, off + (i + 1) * step, x) }
    }

    /** يميّز السطر العربي حتى لا يُرسل إلى Gemini للترجمة مرة أخرى. */
    private fun hasArabicScript(s: String): Boolean = s.any { it in '\u0600'..'\u06FF' }

    /** ترجمة السطور الأجنبية إلى العربية بمفتاح Gemini الموجود في إعدادات المستخدم، من غير Google Search grounding. */
    private fun translateLines(ctx: Context, input: List<LyricLine>, onStage: ((String) -> Unit)? = null): List<LyricLine> {
        if (input.isEmpty()) return input
        // السطور العربية تظل كما هي؛ السطور الأجنبية فقط تُترجم، حتى لو كانت الأغنية مختلطة اللغات.
        val pending = input.indices.filter { i ->
            input[i].translation.isBlank() && input[i].text.any { it.isLetter() } && !hasArabicScript(input[i].text)
        }
        if (pending.isEmpty()) return input
        val keys = (Cfg.keys("keys") + Cfg.keys("extra") + Cfg.keys("backup")).distinct()
        val apiKey = keys.firstOrNull() ?: return input
        val model = Cfg.str("model", Models.DEFAULT).trim().ifBlank { Models.DEFAULT }
        val translated = HashMap<Int, String>()
        for (batch in pending.chunked(35)) {
            try {
                onStage?.invoke("🌐 جيميناي بيترجم الكلمات للعربية (${translated.size}/${pending.size})…")
                val src = JSONArray()
                for (idx in batch) src.put(input[idx].text)
                val prompt = "Translate each supplied song-lyric line into natural Arabic. The input is user-requested translation text from a lyrics source. Keep the same order and one output per input line; preserve meaning, tone, names, and repetition; do not add explanations, omit lines, combine lines, or invent lyrics. Return ONLY JSON exactly shaped as {\"translations\":[\"Arabic line 1\",\"Arabic line 2\"]}. Input lines JSON: " + src.toString()
                // search=false صراحةً: لا يتم تشغيل Google Search أو grounding.
                val reply = Api.generate(model, apiKey, prompt, maxTokens = 4096, temp = 0.1, json = true, search = false).text
                val clean = reply.substring(reply.indexOf('{').coerceAtLeast(0)).trim().removeSuffix("```").trim()
                val arr = JSONObject(clean).optJSONArray("translations") ?: continue
                for (j in batch.indices) {
                    val tr = arr.optString(j, "").trim()
                    if (tr.isNotBlank() && tr != "null") translated[batch[j]] = tr
                }
            } catch (e: Throwable) {
                LogStore.err("lyrics-translation", e)
            }
        }
        return input.mapIndexed { i, line ->
            val tr = translated[i].orEmpty()
            if (tr.isBlank()) line else LyricLine(line.start, line.end, line.text, tr)
        }
    }

    private fun toVersion(s: AghaniLyrics.Song) = LyricVersion(
        s.title, s.artist, "aghanilyrics", s.url, s.lines.map { LyricLine(0.0, 0.0, it) }, false
    )

    /** يستخدم Gemini لتوليد صيغ بحث بديلة فقط؛ الكلمات نفسها لا تُولَّد بل تُقرأ من صفحات المصدر مباشرة. */
    private fun searchAghaniVersions(title: String, artist: String, onStage: (String) -> Unit): List<AghaniLyrics.Song> {
        val found = LinkedHashMap<String, AghaniLyrics.Song>()
        fun add(songs: List<AghaniLyrics.Song>) {
            for (song in songs) {
                val sig = ArNorm.fold(song.lines.joinToString(" ") { it }).take(6000)
                if (sig.isNotBlank()) found.putIfAbsent(sig, song)
                if (found.size >= 6) break
            }
        }
        add(AghaniLyrics.search(title, artist, 6))
        if (found.size < 6) {
            val apiKey = (Cfg.keys("keys") + Cfg.keys("extra") + Cfg.keys("backup")).firstOrNull()
            if (!apiKey.isNullOrBlank()) try {
                val model = Cfg.str("model", Models.DEFAULT).trim().ifBlank { Models.DEFAULT }
                val prompt = "Return ONLY JSON: {\"title_variants\":[\"...\"]}. Find up to 4 plausible search-title variants for the exact same song, based only on spelling variants, alternate Arabic spelling, transliteration, official title aliases, and known version labels (live/acoustic/remix only when plausible). Do not invent different songs or song lyrics. Keep the artist unchanged and do not include explanations. Song title: " + title.replace("\"", "'").replace('\n', ' ') + "; artist: " + artist.replace("\"", "'").replace('\n', ' ')
                // search=false: Gemini يوسّع صيغة البحث فقط دون Google Search/Grounding.
                val reply = Api.generate(model, apiKey, prompt, maxTokens = 512, temp = 0.1, json = true, search = false).text
                val clean = reply.substring(reply.indexOf('{').coerceAtLeast(0)).trim().removeSuffix("```").trim()
                val array = JSONObject(clean).optJSONArray("title_variants")
                if (array != null) for (i in 0 until minOf(array.length(), 4)) {
                    val variant = array.optString(i).trim().trim('"', '\'')
                    if (variant.length < 2 || ArNorm.fold(variant) == ArNorm.fold(title)) continue
                    if (found.size >= 6) break
                    onStage("🔎 بجرّب صيغة اسم بديلة عبر AghaniLyrics…")
                    add(AghaniLyrics.search(variant, artist, 3))
                }
            } catch (e: Throwable) { LogStore.err("aghanilyrics-variants", e) }
        }
        return found.values.take(6)
    }

    private fun signature(lines: List<LyricLine>): String = ArNorm.fold(lines.joinToString(" ") { it.text }).take(6000)

    /** تبديل إصدار الكلمات من غير تغيير أي إعداد أو واجهة أخرى؛ تُحفظ النسخة المختارة في الكاش. */
    fun chooseVersion(ctx: Context, t: Track, chosen: LyricVersion, available: List<LyricVersion>,
                      onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val app = ctx.applicationContext
        val k = key(t, M_FULL)
        if (running.putIfAbsent(k, true) != null) { onDone(null, "الكلمات بتتحدث دلوقتي"); return }
        Thread {
            try {
                onStage("🎼 بجهّز النسخة المختارة…")
                val lines = translateLines(app, chosen.lines, onStage)
                val chosenKey = chosen.sourceUrl.ifBlank { chosen.source + "|" + chosen.title + "|" + chosen.artist }
                val alt = available.filter { v ->
                    val vk = v.sourceUrl.ifBlank { v.source + "|" + v.title + "|" + v.artist }
                    vk != chosenKey && v.lines.isNotEmpty()
                }.distinctBy { it.sourceUrl.ifBlank { it.source + "|" + signature(it.lines) } }.take(8)
                val res = LyricsResult(chosen.title, chosen.artist, lines, true, 1, 1, emptySet(),
                    if (chosen.synced) "" else "من غير توقيت", lines.map { it.text }, chosen.synced, chosen.source, alt, chosen.sourceUrl, true)
                save(app, t, M_FULL, res)
                onDone(res, "")
            } catch (e: Throwable) {
                LogStore.err("lyrics-version", e)
                onDone(null, "فشل تغيير النسخة: " + (e.message ?: e.javaClass.simpleName).take(90))
            } finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    // ===== (v243) LRCLIB =====
    private const val API = "https://lrclib.net/api/"
    private const val UA = "Subtitler/2.50 (Android; lyrics client)"
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
            if (c != null && c.lines.isNotEmpty()) {
                val translated = translateLines(app, c.lines, onStage)
                var alternatives = c.alternatives
                var checked = c.aghaniChecked
                // الإصدارات المحفوظة من النسخ القديمة تُثرى مرة واحدة بـ AghaniLyrics بدل إعادة بحث LRCLIB كاملًا.
                if (!checked) {
                    try {
                        onStage("🔎 براجع النسخ الإضافية في AghaniLyrics…")
                        val songs = searchAghaniVersions(c.title.ifBlank { t.title }, c.artist.ifBlank { t.artist }, onStage)
                        val baseSig = signature(translated)
                        alternatives = (alternatives + songs.map { toVersion(it) })
                            .filter { it.lines.isNotEmpty() && signature(it.lines) != baseSig }
                            .distinctBy { it.sourceUrl.ifBlank { signature(it.lines) } }.take(6)
                    } catch (e: Throwable) { LogStore.err("aghanilyrics-cache", e) }
                    checked = true
                }
                if (translated.indices.any { translated[it].translation != c.lines[it].translation } || !c.aghaniChecked || alternatives != c.alternatives) {
                    val updated = LyricsResult(c.title, c.artist, translated, c.complete, c.done, c.total, c.ok, c.note,
                        translated.map { it.text }, c.synced, c.source, alternatives, c.sourceUrl, checked)
                    save(app, t, M_FULL, updated); onDone(updated, ""); return
                }
                onDone(c, ""); return
            }
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

        // نجمع إصدارات الموقع العربي إلى جانب LRCLIB حتى لو كان المصدر الأساسي نجح.
        var aghaniSongs: List<AghaniLyrics.Song> = emptyList()
        try {
            onStage("🔎 بدوّر في AghaniLyrics على كلمات ونسخ إضافية…")
            aghaniSongs = searchAghaniVersions(name, by, onStage)
        } catch (e: Throwable) { LogStore.err("aghanilyrics", e) }

        val b = best
        if (b == null) {
            if (aghaniSongs.isNotEmpty()) {
                val first = aghaniSongs.first()
                val rawLines = first.lines.map { LyricLine(0.0, 0.0, it) }
                val alternatives = aghaniSongs.drop(1).map { toVersion(it) }
                val translated = translateLines(app, rawLines, onStage)
                val res = LyricsResult(first.title, first.artist, translated, true, 1, 1, emptySet(), "من غير توقيت · AghaniLyrics",
                    translated.map { it.text }, false, "aghanilyrics", alternatives, first.url, true)
                save(app, t, M_FULL, res); onDone(res, ""); return
            }
            if (!answered) { onDone(null, "مفيش اتصال بالمصدر (" + lastErr.take(60) + ") — جرّب تاني"); return }
            save(app, t, M_FULL, LyricsResult(name, by, emptyList(), true, 1, 1, emptySet(), "", emptyList(), true, src, emptyList(), "", true))
            onDone(null, if (retry) "ما لقيتش نتيجة تانية — جرّب ✍ كلمات يدوي" else "ما لقيتش الأغنية دي (LRCLIB + Lyrics.ovh + AghaniLyrics) — جرّب ✍ كلمات يدوي")
            return
        }
        lastId[k] = b.id
        val tot = t.durMs / 1000.0
        val lines = if (b.synced.isNotBlank()) parseLrc(b.synced, tot) else emptyList()
        val base = if (lines.size >= 2) {
            LyricsResult(name, by, lines, true, 1, 1, emptySet(), "", lines.map { it.text }, true, src)
        } else {
            val tagRe = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]")
            val pl = (if (b.plain.isNotBlank()) b.plain else b.synced).lines().map { tagRe.replace(it, "").trim() }.filter { it.isNotEmpty() }
            if (pl.isEmpty()) { onDone(null, "النتيجة فاضية"); return }
            LyricsResult(name, by, pl.map { LyricLine(0.0, 0.0, it) }, true, 1, 1, emptySet(), "من غير توقيت", pl, false, src)
        }
        val baseSig = signature(base.lines)
        val alternatives = aghaniSongs.map { toVersion(it) }
            .filter { signature(it.lines) != baseSig }
            .distinctBy { it.sourceUrl }
            .take(6)
        val translated = translateLines(app, base.lines, onStage)
        val res = LyricsResult(base.title, base.artist, translated, base.complete, base.done, base.total, base.ok, base.note,
            translated.map { it.text }, base.synced, base.source, alternatives, base.sourceUrl, true)
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
