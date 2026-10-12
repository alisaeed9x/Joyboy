package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** سطر كلام متزامن: من start لـ end بالثواني */
class LyricLine(val start: Double, val end: Double, val text: String)

/** نتيجة كلمات أغنية. complete=false لو لسه ناقصة (ok = أرقام المقاطع اللي اتكتبت) وهتتكمّل من اللي فاضل. note = ملاحظة تتعرض فوق الكلمات (مثلًا «التوقيت تقريبي») */
class LyricsResult(
    val title: String, val artist: String, val lines: List<LyricLine>,
    val complete: Boolean = true, val done: Int = 0, val total: Int = 0,
    val ok: Set<Int> = emptySet(), val note: String = "", val raw: List<String> = emptyList()
)

/**
 * (v194) الكلمات بوضعين (v230: الاختيار بيتحفظ، والأساسي «جلب الكلمات»):
 *  • M_FULL  📝 جلب الكلمات: جيميناي بيجيب كلمات الأغنية كاملة بالاسم (JSON ← نص عادي لو الرد اتقطع). لو الاسم مش مفيد أو جيميناي ماعرفهاش،
 *            بنبعتله مقطع واحد بس من نص الأغنية يقول اسمها وبعدين نجيب الكلمات بالاسم ده. التوقيت بيتوزّع محليًا على لحظات الغنا (من غير ما جيميناي يفرّغ الصوت).
 *  • M_AUDIO 🎧 تفريغ من الصوت: جيميناي بيسمع الأغنية على مقاطع 60ث (زي ترجمة الفيديو) — بالتوازي على كذا مفتاح، وبتتحفظ بعد كل مقطع
 *            فلو حصل فشل في النص بتتكمّل من اللي فاضل بس.
 * كل فشل بيرجّع سببه الحقيقي (كود جيميناي/قراءة الصوت…) بدل رسالة عامة.
 */
object LyricsEngine {
    const val M_AUDIO = "audio"; const val M_FULL = "full"
    private const val CHUNK = 60.0
    // (v230) الخيارين رجعوا: «جلب الكلمات» (الأساسي) و«ترانسكريبت الصوت» — والاختيار بيتحفظ
    fun mode(): String = if (Cfg.str("lyr_src", M_FULL) == M_AUDIO) M_AUDIO else M_FULL
    fun setMode(m: String) { Cfg.put("lyr_src", if (m == M_AUDIO) M_AUDIO else M_FULL) }

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun key(t: Track, m: String) = "t${t.id}_${t.durMs}" + (if (m == M_FULL) "_full" else "") + ".json"
    private val running = ConcurrentHashMap<String, Boolean>()
    @Volatile var version = 0; private set      // بيزيد مع كل حفظ — الشاشة بتقارنه عشان تعرف تحدّث

    fun isRunning(t: Track, m: String) = running.containsKey(key(t, m))
    fun anyRunning() = running.isNotEmpty()

    fun cached(ctx: Context, t: Track, m: String): LyricsResult? = try {
        val o = JSONObject(File(dir(ctx), key(t, m)).readText())
        val r = parse(o)
        // (v198) نسخ «كلمات كاملة» القديمة (توقيت موزّع بالتساوي) بتتعامل كناقصة عشان تتظبط على الصوت
        if (m == M_FULL && o.optInt("v", 0) < 4 && r.complete) LyricsResult(r.title, r.artist, r.lines, false, 0, 0, emptySet(), r.note, r.raw.ifEmpty { r.lines.map { it.text } }) else r
    } catch (_: Throwable) { null }
    fun clear(ctx: Context, t: Track, m: String) { try { File(dir(ctx), key(t, m)).delete() } catch (_: Throwable) {}; version++ }
    fun clearAll(ctx: Context, t: Track) { clear(ctx, t, M_AUDIO); clear(ctx, t, M_FULL) }

    private fun parse(o: JSONObject): LyricsResult {
        val a = o.optJSONArray("lines") ?: JSONArray()
        val l = ArrayList<LyricLine>()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); l.add(LyricLine(x.getDouble("s"), x.getDouble("e"), x.getString("t"))) }
        val ok = HashSet<Int>(); val oa = o.optJSONArray("ok"); if (oa != null) for (i in 0 until oa.length()) ok.add(oa.getInt(i))
        val ra = o.optJSONArray("raw"); val raw = ArrayList<String>(); if (ra != null) for (i in 0 until ra.length()) { val x = ra.optString(i).trim(); if (x.isNotEmpty()) raw.add(x) }
        return LyricsResult(o.optString("title"), o.optString("artist"), l, o.optBoolean("complete", true), o.optInt("done", 0), o.optInt("total", 0), ok, o.optString("note"), raw)
    }
    private fun save(ctx: Context, t: Track, m: String, r: LyricsResult) {
        val a = JSONArray(); for (x in r.lines) a.put(JSONObject().put("s", x.start).put("e", x.end).put("t", x.text))
        val oa = JSONArray(); for (i in r.ok.sorted()) oa.put(i)
        val ra = JSONArray(); for (x in r.raw) ra.put(x)
        try {
            File(dir(ctx), key(t, m)).writeText(JSONObject().put("title", r.title).put("artist", r.artist).put("lines", a)
                .put("complete", r.complete).put("done", r.done).put("total", r.total).put("ok", oa).put("note", r.note).put("raw", ra).put("v", 4).toString())
        } catch (_: Throwable) {}
        version++
    }
    /** ترتيب + شيل التكرار/التداخل عند حدود المقاطع */
    private fun tidy(src: List<LyricLine>): ArrayList<LyricLine> {
        val out = src.sortedBy { it.start }
        val clean = ArrayList<LyricLine>()
        for (l in out) {
            val p = clean.lastOrNull()
            if (p != null && l.start < p.end) {
                if (l.text == p.text) continue
                clean[clean.size - 1] = LyricLine(p.start, minOf(p.end, l.start), p.text)
            }
            clean.add(l)
        }
        return clean
    }
    private fun shortErr(e: Throwable): String {
        if (e is ApiErr) {
            val why = when (e.code) { 429 -> "المفتاح وصل لحد الطلبات (429)"; 401, 403 -> "المفتاح مرفوض (${e.code})"; 400 -> "جيميناي رفض الطلب (400)"; 503, 500 -> "سيرفر جيميناي مشغول (${e.code})"; else -> "رد جيميناي ${e.code}" }
            return why + " — " + e.raw.replace('\n', ' ').take(70)
        }
        return (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(110)
    }
    private fun jsonOf(txt: String): JSONObject = JSONObject(txt.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

    private fun audioPrompt(first: Boolean, hint: Pair<String, String>?, retry: Boolean) = buildString {
        append("You are given a short audio clip from a song. Transcribe ONLY the words that are actually sung or spoken in this clip, exactly as you hear them, in the original language (do NOT translate, do NOT correct, do NOT complete or add lines from memory, skip instrumental parts). ")
        append("Split into natural lines (one sung phrase per line) and give each line its start and end time in seconds from the start of THIS clip. ")
        append("If there are no clearly audible words, return an empty list. ")
        if (hint != null && hint.first.isNotBlank()) {
            append("The song is believed to be \"${hint.first}\"" + (if (hint.second.isNotBlank()) " by ${hint.second}" else "") + ". ")
            append("If you know its official lyrics, use them ONLY to fix mis-heard words and spelling of lines that are actually sung in this clip (timings must still come from the audio; never add lines that are not in the clip). ")
        }
        if (retry) append("A previous transcription of this song was reported as INACCURATE — listen again very carefully and be precise. ")
        if (first && (hint == null || hint.first.isBlank())) append("Also, only if you genuinely recognise the song, give its title and artist; otherwise leave them empty strings. ")
        append("Return JSON only: {\"title\":\"\",\"artist\":\"\",\"lines\":[{\"s\":0.0,\"e\":0.0,\"t\":\"\"}]}")
    }

    /** (v198) مواءمة الكلمات الرسمية مع الصوت: جيميناي بيسمع المقطع ويقول أنهي سطور (من القايمة) اتغنّت فيه ومتى بالظبط */
    private fun alignPrompt(raw: List<String>, hint: Pair<String, String>?) = buildString {
        append("You are given a short audio clip (about one minute) from a song")
        if (hint != null && hint.first.isNotBlank()) append(" (\"${hint.first}\"" + (if (hint.second.isNotBlank()) " by ${hint.second}" else "") + ")")
        append(" and the song's official lyrics as numbered lines. Listen to the clip and return ONLY the lines that are actually sung in THIS clip, in the order they are sung, ")
        append("each with its start and end time in seconds measured from the start of THIS clip. Timings must come from what you hear, not from the position of the line in the list. ")
        append("Copy the text of each line EXACTLY as written in the list (do not change, translate or merge lines). If the same line is sung more than once (a chorus), return it once per time it is sung. ")
        append("Do not return lines that are not sung in this clip. If you hear no words from the list, return an empty list. ")
        append("Return JSON only: {\"lines\":[{\"s\":0.0,\"e\":0.0,\"t\":\"\"}]}\n\nOFFICIAL LYRICS:\n")
        raw.forEachIndexed { i, x -> append(i + 1).append(". ").append(x).append('\n') }
    }
    /** توقيت تقريبي (موزّع بالتساوي) — بنستخدمه بس لو المواءمة مع الصوت فشلت */
    private fun estimate(t: Track, lines: List<String>): List<LyricLine> {
        val tot = (t.durMs / 1000.0).coerceAtLeast(30.0)
        val step = (tot * 0.85) / lines.size.coerceAtLeast(1); val off = tot * 0.07
        return lines.mapIndexed { i, x -> LyricLine(off + i * step, off + (i + 1) * step, x) }
    }

    /**
     * بيشتغل على خيط خلفية. onStage = نص الحالة الحالية (مرحلة/تقدم) · onDone(نتيجة أو null، رسالة).
     * لو فيه نسخة ناقصة من وضع الصوت بتكمّل من اللي فاضل (إلا لو fresh).
     */
    fun start(ctx: Context, t: Track, mode: String, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val app = ctx.applicationContext
        val m = if (mode == M_AUDIO) M_AUDIO else M_FULL
        val k = key(t, m)
        if (running.putIfAbsent(k, true) != null) { onDone(null, "بتتكتب دلوقتي"); return }
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش مفتاح Gemini — ضيفه من الإعدادات"); return@Thread }
                val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
                if (m == M_FULL) runFull(app, t, keys, model, hint, retry, fresh, onStage, onDone) else runAudio(app, t, keys, model, hint, retry, fresh, onStage, onDone)
            } catch (e: Throwable) { LogStore.err("lyrics", e); onDone(null, "فشل: " + shortErr(e)) }
            finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    /** (v223) كلمات من المستخدم (لصق/تعديل): بتتحفظ زي ما هي وجيميناي بيسمع الأغنية ويظبط توقيت كل سطر */
    fun startManual(ctx: Context, t: Track, hint: Pair<String, String>?, lines: List<String>, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val app = ctx.applicationContext
        val k = key(t, M_FULL)
        if (running.putIfAbsent(k, true) != null) { onDone(null, "بتتكتب دلوقتي"); return }
        Thread {
            try { finishLocal(app, t, (hint?.first ?: "").ifBlank { t.title }, (hint?.second ?: "").ifBlank { t.artist }, lines, onStage, onDone) }
            catch (e: Throwable) { LogStore.err("lyrics-manual", e); onDone(null, "فشل: " + shortErr(e)) }
            finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    // ===================== 🎧 تفريغ من الصوت (متوازي + بيتحفظ بعد كل مقطع) =====================
    private fun runAudio(app: Context, t: Track, keys: List<String>, model: String, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit,
                         mode: String = M_AUDIO, raw: List<String>? = null) {
        onStage("🔌 بجهّز الملف الصوتي…")
        val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
        val total0 = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
        val total = Math.ceil(total0 / CHUNK).toInt().coerceAtLeast(1)
        val prev = if (fresh) null else cached(app, t, mode)?.takeIf { !it.complete && it.total == total }
        val okSet = HashSet<Int>()
        if (prev != null) { if (prev.ok.isNotEmpty()) okSet.addAll(prev.ok) else okSet.addAll(0 until prev.done) }
        val out = ArrayList<LyricLine>()
        if (prev != null) out.addAll(prev.lines)
        var title = prev?.title ?: ""; var artist = prev?.artist ?: ""
        if (raw != null && prev == null) save(app, t, mode, LyricsResult(hint?.first ?: t.title, hint?.second ?: t.artist, emptyList(), false, 0, total, emptySet(), "", raw))
        val pending = ConcurrentLinkedQueue<Int>((0 until total).filter { it !in okSet })
        val errs = ConcurrentLinkedQueue<String>()
        val lock = Any(); val srcLock = Any()
        val nThreads = keys.size.coerceIn(1, 3).coerceAtMost(pending.size.coerceAtLeast(1))
        onStage(if (okSet.isNotEmpty()) "🎧 بكمّل من مقطع ${okSet.size + 1} من $total…" else if (raw != null) "⏱ بظبط توقيت الكلمات على الصوت… ($total مقطع، $nThreads مفتاح بالتوازي)" else "🎧 بسمع الأغنية… ($total مقطع، $nThreads مفتاح بالتوازي)")
        val workers = (0 until nThreads).map { wi ->
            Thread {
                while (true) {
                    val c = pending.poll() ?: break
                    val a = c * CHUNK; val b = minOf(a + CHUNK, total0.coerceAtLeast(a + 1))
                    val w = try { synchronized(srcLock) { src.wav(a, b) } } catch (e: Throwable) { errs.add("قراءة الصوت: " + shortErr(e)); null }
                    if (w == null) { if (errs.isEmpty()) errs.add("ماقدرتش أقرا المقطع ${c + 1} من الملف"); continue }
                    if (w.silent) { synchronized(lock) { okSet.add(c); save(app, t, mode, LyricsResult(title, artist, tidy(out), false, okSet.size, total, HashSet(okSet), "", raw ?: emptyList())) }; continue }
                    var got: JSONObject? = null
                    for (kk in 0 until keys.size.coerceAtMost(4)) {
                        val key = keys[(c + wi + kk) % keys.size]
                        try {
                            val r = Api.generate(model, key, if (raw != null) alignPrompt(raw, hint) else audioPrompt(c == 0, hint, retry), w.bytes, 8192, if (retry) 0.3 else 0.0, true)
                            got = jsonOf(r.text); break
                        } catch (e: Throwable) { errs.add(shortErr(e)) }
                    }
                    val g = got ?: continue
                    synchronized(lock) {
                        if (raw == null && c == 0 && (hint == null || hint.first.isBlank())) { title = g.optString("title").trim(); artist = g.optString("artist").trim() }
                        val arr = g.optJSONArray("lines")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val x = arr.optJSONObject(i) ?: continue
                            val txt = x.optString("t").trim(); if (txt.isEmpty()) continue
                            var s0 = x.optDouble("s", 0.0); var e0 = x.optDouble("e", s0 + 3.0)
                            s0 = s0.coerceIn(0.0, CHUNK); e0 = e0.coerceIn(s0 + 0.5, CHUNK + 1.0)
                            out.add(LyricLine(a + s0, a + e0, txt))
                        }
                        okSet.add(c)
                        if (hint != null && hint.first.isNotBlank()) { title = hint.first; artist = hint.second }
                        save(app, t, mode, LyricsResult(title, artist, tidy(out), false, okSet.size, total, HashSet(okSet), "", raw ?: emptyList()))
                    }
                    onStage((if (raw != null) "⏱ اتظبط " else "🎧 اتكتب ") + "${synchronized(lock) { okSet.size }} من $total مقطع (بتتحفظ لوحدها)")
                }
            }.apply { isDaemon = true }
        }
        workers.forEach { it.start() }; workers.forEach { it.join() }
        try { src.close() } catch (_: Throwable) {}
        if (hint != null && hint.first.isNotBlank()) { title = hint.first; artist = hint.second }
        val complete = okSet.size >= total
        val clean = tidy(out)
        val why = errs.firstOrNull()
        // (v198) مواءمة الكلمات الرسمية: لو الصوت ماطابقش تقريبًا ولا سطر، نرجع للتوقيت التقريبي بدل ما نسيب الشاشة فاضية
        if (raw != null && complete && clean.size < (raw.size * 0.35).toInt().coerceAtLeast(3)) {
            val est = LyricsResult(title, artist, estimate(t, raw), true, total, total, HashSet(okSet), "التوقيت تقريبي", raw)
            save(app, t, mode, est); onDone(est, "ماقدرتش أظبط التوقيت على الصوت — التوقيت تقريبي (جرّب وضع 🎧 تفريغ من الصوت للدقة)"); return
        }
        val res = LyricsResult(title, artist, clean, complete, okSet.size, total, HashSet(okSet), "", raw ?: emptyList())
        if (clean.isEmpty()) {
            save(app, t, mode, res)
            if (complete) onDone(res, "ما سمعتش كلام واضح في الأغنية دي — جرّب وضع 📝 كلمات كاملة")
            else onDone(res, "فشل تفريغ ${total - okSet.size} من $total مقطع" + (if (why != null) " — السبب: $why" else ""))
        } else {
            save(app, t, mode, res)
            onDone(res, if (!complete) "اتكتب الكلام بس ${total - okSet.size} مقطع فشل" + (if (why != null) " ($why)" else "") + " — دوس 🔁 يكمّل" else "")
        }
    }

    // ===================== 📝 كلمات كاملة من جيميناي (بالاسم) =====================
    /** اسم الملف مش مفيد (فاضي / أرقام / تسجيل / تاريخ…) فمالوش لازمة نسأل بيه جيميناي */
    private fun looksJunk(s: String): Boolean {
        val x = s.trim(); val l = x.lowercase()
        if (x.length < 3 || x.count { it.isLetter() } < 2) return true
        if (Regex("^(track|audio|voice|rec|recording|new recording|video|vid|aud|ptt|whatsapp|unknown|untitled)[\\s_\\-.]*[0-9]*$").containsMatchIn(l)) return true
        if (Regex("[0-9]{6,}").containsMatchIn(x)) return true
        return false
    }

    /** بيبعت مقطع واحد (~25ث) من نص الأغنية لجيميناي (أو AudD لو فيه توكن) عشان يقول اسمها — متزامن (بيستنى الرد) */
    private fun identifyByClip(app: Context, t: Track, startSec: Double): SongMatch? {
        var res: SongMatch? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        SongId.auto(app, t, startSec, emptyList()) { m, _ -> res = m; latch.countDown() }
        try { latch.await(90, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return res
    }

    // ===================== 📝 جلب الكلمات من جيميناي (بالاسم، أو بمقطع من نص الأغنية لو الاسم مش معروف) =====================
    private fun runFull(app: Context, t: Track, keys: List<String>, model: String, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val trusted = hint != null && hint.first.isNotBlank()          // اسم اتحفظ يدويًا أو من التعرف
        var name = if (trusted) hint!!.first else t.title
        var by = if (trusted) hint!!.second else t.artist
        var lines: List<String> = emptyList(); var notFound = false
        val errs = ArrayList<String>()
        // لو الكلمات الرسمية اتجابت قبل كده (ومحفوظة) بنكمّل توزيع التوقيت من غير ما نطلبها تاني
        val saved = if (fresh) null else cached(app, t, M_FULL)?.raw?.takeIf { it.size >= 4 }
        if (saved != null) { finishApprox(app, t, name, by, saved, onStage, onDone); return }
        val totalSec = (t.durMs / 1000.0).coerceAtLeast(30.0)
        var identified = false

        // مقطع واحد من نص الأغنية يتبعت لجيميناي يقول اسمها
        fun identify(posFrac: Double): Boolean {
            onStage("🔎 بسمّع جيميناي مقطع من نص الأغنية عشان يعرف اسمها…")
            val m = identifyByClip(app, t, maxOf(10.0, totalSec * posFrac)) ?: return false
            name = m.title; by = m.artist; identified = true
            return true
        }
        // طلب الكلمات بالاسم الحالي: مرحلة 1 JSON ثم مرحلة 2 نص عادي لو اتقطع
        fun fetch() {
            lines = emptyList(); notFound = false
            val label = "\"$name\"" + (if (by.isNotBlank()) " by $by" else "")
            val temp = if (retry) 0.4 else 0.0
            val again = if (retry) " A previous answer for this song was reported INACCURATE — be careful and precise, and do not mix in lines of other songs." else ""
            onStage("📝 بطلب كلمات $name من جيميناي…")
            val pr1 = "Give the complete lyrics of the song $label in its original language (Arabic songs must be written in Arabic script — never transliterated, never translated to English): every sung line in order, no section labels, no translation, nothing invented. " +
                "If you do not reliably know the full lyrics of this exact song, return found=false and an empty list.$again Return JSON only: {\"found\":true,\"lines\":[\"\"]}"
            for (kk in 0 until keys.size.coerceAtMost(3)) {
                try {
                    val r = Api.generate(model, keys[kk % keys.size], pr1, null, 8192, temp, true)
                    val o = jsonOf(r.text)
                    if (!o.optBoolean("found", true)) { notFound = true; break }
                    val arr = o.optJSONArray("lines")
                    lines = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it).trim() }.filter { it.isNotEmpty() }
                    if (lines.size >= 4) break
                } catch (e: Throwable) { errs.add(shortErr(e)) }
            }
            if (lines.size < 4 && !notFound) {
                onStage("📝 بطلبها كنص عادي…")
                val pr2 = "Write the complete lyrics of the song $label in its original language (Arabic songs must be written in Arabic script — never transliterated, never translated to English), one sung line per row, no section labels, no translation, no commentary.$again " +
                    "If you do not reliably know this exact song's lyrics, reply with exactly: NOT_FOUND"
                for (kk in 0 until keys.size.coerceAtMost(3)) {
                    try {
                        val r = Api.generate(model, keys[(kk + 1) % keys.size], pr2, null, 8192, temp, false)
                        val txt = r.text.trim()
                        if (txt.contains("NOT_FOUND")) { notFound = true; break }
                        val l2 = txt.lines().map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
                        if (l2.size > lines.size) lines = l2
                        if (lines.size >= 4) break
                    } catch (e: Throwable) { errs.add(shortErr(e)) }
                }
            }
        }

        // 1) اسم الملف مش مفيد (ومحدش حفظ اسم) ← نعرّف الأغنية من مقطع من نصها الأول
        if (!trusted && looksJunk(name)) identify(0.5)
        // 2) نجيب الكلمات بالاسم
        fetch()
        // 3) الاسم ماجابش كلمات (جيميناي مش متأكد) ← نعرّف الأغنية من مقطع من نصها ونجرّب بالاسم الجديد
        if (lines.size < 4 && !trusted && !identified) {
            val before = name.trim().lowercase()
            if (identify(0.5) && name.trim().lowercase() != before) fetch()
        }
        if (lines.size < 4) {
            val msg = if (notFound) "جيميناي مش متأكد من كلمات «$name» — جرّب ✏️ اسم الأغنية (اكتبه بإيدك) أو وضع 🎧 ترانسكريبت الصوت"
                      else "ماجاتش كلمات" + (if (errs.isNotEmpty()) " — السبب: " + errs.first() else "") + " — دوس 🔁 تاني"
            onDone(null, msg); return
        }
        // توزيع التوقيت محليًا على لحظات الغنا (من غير طلبات تفريغ صوت)
        finishApprox(app, t, name, by, lines, onStage, onDone)
    }

    /** (v230) توقيت تقريبي محلي: الكلمات بتتوزّع على لحظات الغنا الفعلية في الصوت (Karaoke.segments) — من غير ما جيميناي يسمع الأغنية. للتوقيت الدقيق: وضع 🎧 ترانسكريبت الصوت. */
    private fun finishApprox(app: Context, t: Track, name: String, by: String, lines: List<String>, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        onStage("⏱ بوزّع الكلمات على لحظات الغنا في الأغنية…")
        val segs = try { Karaoke.segments(app, t) } catch (e: Throwable) { LogStore.err("lyrics-vad", e); emptyList() }
        val placed = if (segs.isNotEmpty()) Karaoke.place(lines, segs) else emptyList()
        val ok = placed.isNotEmpty()
        val res = LyricsResult(name, by, if (ok) placed else estimate(t, lines), true, 1, 1, emptySet(), "التوقيت تقريبي", lines)
        save(app, t, M_FULL, res); onDone(res, "")
    }

    // ===================== (v222) مواءمة التوقيت على الصوت الحقيقي =====================
    // 1) جيميناي بيسمع الأغنية على نوافذ قصيرة (24ث بتداخل 4ث) ويفرّغ اللي اتغنّى فعلًا بوقت بداية/نهاية كل سطر.
    // 2) المفرّغ بيتطابق كلمة بكلمة (Needleman–Wunsch) مع الكلمات الرسمية — فمفيش مشكلة لو التقسيم أو الإملاء اختلف أو سطر اتكرر.
    // 3) بداية كل سطر بتتشدّ لأقرب بداية صوت حقيقية في منحنى الطاقة (±0.8ث، بدقة 50ms).
    // 4) أي سطر جيميناي ماسمعوش بيتوزّع بين أقرب سطرين متثبّتين. لو الطريقة كلها فشلت: الطريقة القديمة (توزيع على لحظات الغنا).
    private const val WWIN = 24.0
    private const val WSTEP = 20.0

    private class Det(val s: Double, val e: Double, val text: String, val edge: Double)
    private class OW(val n: String, val line: Int, val cb: Int, val ca: Int)
    private class DW(val n: String, val s: Double, val e: Double, val spc: Double)

    private fun timedPrompt() =
        "You are given a short audio clip cut from a song (about 24 seconds; it may start or end in the middle of a sung line). " +
        "Transcribe ONLY the words that are actually sung or spoken, exactly as heard, in the original language and script (Arabic stays in Arabic script; do NOT translate, transliterate or correct). " +
        "Return one entry per sung phrase/line. For each entry give s = the exact second (one decimal, 0.0 = the very first sample of THIS clip) at which the FIRST word starts being sung, and e = the exact second the LAST word ends. " +
        "Listen for the real onset of the voice: do not count instrumental intro or breaks, do not spread lines evenly, do not round times to look regular. " +
        "If a line is cut at the start or end of the clip, still include the audible part. If no words are clearly audible return an empty list. " +
        "Return JSON only: {\"lines\":[{\"s\":0.0,\"e\":0.0,\"t\":\"\"}]}"

    private fun normWord(x: String): String {
        val sb = StringBuilder()
        for (ch in x.lowercase()) {
            if (!ch.isLetterOrDigit()) continue
            sb.append(when (ch) { 'أ', 'إ', 'آ', 'ٱ' -> 'ا'; 'ى' -> 'ي'; 'ة' -> 'ه'; 'ؤ' -> 'و'; 'ئ' -> 'ي'; else -> ch })
        }
        return sb.toString()
    }
    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }; var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) { val c = if (a[i - 1] == b[j - 1]) 0 else 1; cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c) }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
    private fun wsim(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** تفريغ بتوقيت على نوافذ متداخلة (بالتوازي على لحد 3 مفاتيح). بيرجّع (السطور المسموعة بتوقيت مطلق، أول خطأ لو فيه) */
    private fun transcribeTimed(app: Context, t: Track, keys: List<String>, model: String, onStage: (String) -> Unit): Pair<List<Det>, String?> {
        val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
        try {
            val total = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
            val starts = ArrayList<Double>(); var p = 0.0
            while (p < total - 3.0 || starts.isEmpty()) { starts.add(p); p += WSTEP }
            val pending = ConcurrentLinkedQueue<Int>((0 until starts.size).toList())
            val out = ConcurrentLinkedQueue<Det>(); val errs = ConcurrentLinkedQueue<String>()
            val srcLock = Any(); val done = java.util.concurrent.atomic.AtomicInteger(0)
            val nThreads = keys.size.coerceIn(1, 3).coerceAtMost(starts.size)
            val workers = (0 until nThreads).map { wi ->
                Thread {
                    while (true) {
                        val c = pending.poll() ?: break
                        val a = starts[c]; val b = minOf(a + WWIN, total.coerceAtLeast(a + 1))
                        val w = try { synchronized(srcLock) { src.wav(a, b) } } catch (e: Throwable) { errs.add("قراءة الصوت: " + shortErr(e)); null }
                        if (w != null && !w.silent) {
                            var got: JSONObject? = null
                            for (kk in 0 until keys.size.coerceAtMost(4)) {
                                val key = keys[(c + wi + kk) % keys.size]
                                try { val r = Api.generate(model, key, timedPrompt(), w.bytes, 8192, 0.0, true); got = jsonOf(r.text); break } catch (e: Throwable) { errs.add(shortErr(e)) }
                            }
                            val g = got
                            if (g != null) {
                                val base = if (Math.abs(w.startSec - a) <= 5.0) w.startSec else a
                                val dur = w.durSec.coerceAtLeast(1.0)
                                val arr = g.optJSONArray("lines")
                                if (arr != null) for (i in 0 until arr.length()) {
                                    val x = arr.optJSONObject(i) ?: continue
                                    val txt = x.optString("t").trim(); if (txt.isEmpty()) continue
                                    var s0 = x.optDouble("s", 0.0); var e0 = x.optDouble("e", s0 + 2.0)
                                    s0 = s0.coerceIn(0.0, dur); e0 = e0.coerceIn(s0 + 0.3, dur + 1.0)
                                    val mid = (s0 + e0) / 2
                                    out.add(Det(base + s0, base + e0, txt, minOf(mid, dur - mid)))
                                }
                            }
                        }
                        onStage("⏱ بسمع الأغنية وبظبط التوقيت… ${done.incrementAndGet()} من ${starts.size} جزء")
                    }
                }.apply { isDaemon = true }
            }
            workers.forEach { it.start() }; workers.forEach { it.join() }
            val merged = ArrayList<Det>()
            for (d in out.sortedBy { it.s }) {
                val last = merged.lastOrNull()
                if (last != null && Math.abs(d.s - last.s) < 1.8 && wsim(normWord(d.text), normWord(last.text)) >= 0.6) { if (d.edge > last.edge) merged[merged.size - 1] = d; continue }
                merged.add(d)
            }
            return Pair(merged, errs.firstOrNull())
        } finally { try { src.close() } catch (_: Throwable) {} }
    }

    /** مطابقة كلمات الأغنية الرسمية مع المفرّغ المتوقّت، وبيرجّع سطور بتوقيت الصوت (أو null لو المطابقة ضعيفة) */
    private fun alignLines(lines: List<String>, dets: List<Det>, env: FloatArray?): List<LyricLine>? {
        val ow = ArrayList<OW>()
        for ((li, ln) in lines.withIndex()) {
            val toks = ln.split(' ', '\t').map { normWord(it) }.filter { it.isNotEmpty() }
            val tot = toks.sumOf { it.length }; var cb = 0
            for (n in toks) { ow.add(OW(n, li, cb, tot - cb - n.length)); cb += n.length }
        }
        val dw = ArrayList<DW>()
        for (d in dets) {
            val toks = d.text.split(' ', '\t').map { normWord(it) }.filter { it.isNotEmpty() }
            val tot = toks.sumOf { it.length }.coerceAtLeast(1)
            val spc = (d.e - d.s).coerceAtLeast(0.3) / tot; var c = 0
            for (n in toks) { dw.add(DW(n, d.s + c * spc, d.s + (c + n.length) * spc, spc)); c += n.length }
        }
        val n = ow.size; val m = dw.size
        if (n < 4 || m < 4 || n.toLong() * m > 4_000_000L) return null
        val M = m + 1; val GAP = -0.4f
        val sc = FloatArray((n + 1) * M); val tr = ByteArray((n + 1) * M)   // 0 قطري · 1 تخطّي كلمة رسمية · 2 تخطّي كلمة مفرّغة
        for (i in 1..n) { sc[i * M] = i * GAP; tr[i * M] = 1 }
        for (j in 1..m) { sc[j] = j * GAP; tr[j] = 2 }
        for (i in 1..n) for (j in 1..m) {
            val sm = wsim(ow[i - 1].n, dw[j - 1].n)
            val diag = sc[(i - 1) * M + j - 1] + (if (sm >= 0.55) (sm * 2 - 0.5).toFloat() else -1.0f)
            val up = sc[(i - 1) * M + j] + GAP; val left = sc[i * M + j - 1] + GAP
            if (diag >= up && diag >= left) { sc[i * M + j] = diag; tr[i * M + j] = 0 }
            else if (up >= left) { sc[i * M + j] = up; tr[i * M + j] = 1 } else { sc[i * M + j] = left; tr[i * M + j] = 2 }
        }
        val L = lines.size
        val es = Array(L) { ArrayList<Double>() }; val ee = Array(L) { ArrayList<Double>() }
        var i = n; var j = m
        while (i > 0 || j > 0) {
            val d = if (i == 0) 2 else if (j == 0) 1 else tr[i * M + j].toInt()
            if (d == 0) {
                if (wsim(ow[i - 1].n, dw[j - 1].n) >= 0.55) {
                    val o = ow[i - 1]; val w = dw[j - 1]
                    es[o.line].add(w.s - o.cb * w.spc); ee[o.line].add(w.e + o.ca * w.spc)
                }
                i--; j--
            } else if (d == 1) i-- else j--
        }
        fun med(l: List<Double>): Double { val q = l.sorted(); return q[q.size / 2] }
        val st = DoubleArray(L); val en = DoubleArray(L); val anch = BooleanArray(L)
        var na = 0
        for (k in 0 until L) if (es[k].isNotEmpty()) { anch[k] = true; st[k] = med(es[k]).coerceAtLeast(0.0); en[k] = maxOf(med(ee[k]), st[k] + 0.5); na++ }
        if (na < maxOf(3, (L * 0.35).toInt())) return null
        // السطور اللي جيميناي ماسمعهاش: توزيع بين أقرب سطرين متثبّتين بنسبة الحروف
        var k = 0
        while (k < L) {
            if (anch[k]) { k++; continue }
            var e2 = k; while (e2 < L && !anch[e2]) e2++
            val wts = (k until e2).map { lines[it].count { c -> c.isLetterOrDigit() }.coerceAtLeast(6).toDouble() }; val sw = wts.sum()
            val hasP = k > 0; val hasN = e2 < L
            val a: Double; val b: Double
            if (hasP && hasN) { a = en[k - 1]; b = maxOf(st[e2], a + 0.5 * (e2 - k)) }
            else if (hasP) { a = en[k - 1] + 0.3; b = a + sw * 0.12 }
            else { b = st[e2] - 0.3; a = maxOf(0.0, b - sw * 0.12) }
            var acc = 0.0
            for (q in k until e2) { val x0 = a + acc / sw * (b - a); acc += wts[q - k]; val x1 = a + acc / sw * (b - a); st[q] = x0; en[q] = maxOf(x1, x0 + 0.5) }
            k = e2
        }
        // تثبيت بدايات السطور المسموعة على أقرب بداية صوت حقيقية
        if (env != null) for (q in 0 until L) if (anch[q]) st[q] = Karaoke.snapOnset(env, st[q], 0.8)
        var prevS = -1.0
        for (q in 0 until L) { if (st[q] < prevS + 0.2) st[q] = prevS + 0.2; prevS = st[q] }
        val res = ArrayList<LyricLine>()
        for (q in 0 until L) {
            var e = en[q]
            if (q + 1 < L) e = minOf(e, st[q + 1])
            e = e.coerceAtLeast(st[q] + 0.5).coerceAtMost(st[q] + 12.0)
            res.add(LyricLine(st[q], e, lines[q]))
        }
        return res
    }

    private fun finishLocal(app: Context, t: Track, name: String, by: String, lines: List<String>, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        onStage("⏱ مرحلة 3 من 3: جيميناي بيسمع الأغنية ويظبط توقيت كل سطر…")
        var aligned: List<LyricLine>? = null; var why = ""
        try {
            val keys = Cfg.allMainKeys()
            val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
            if (keys.isNotEmpty()) {
                val (dets, err) = transcribeTimed(app, t, keys, model, onStage)
                if (dets.size >= 3) {
                    onStage("⏱ بطابق الكلمات وبثبّت البدايات على الصوت…")
                    val env = try { Karaoke.envelope(app, t) } catch (e: Throwable) { LogStore.err("lyrics-env", e); null }
                    aligned = alignLines(lines, dets, env)
                    if (aligned == null) why = "التطابق مع الصوت ضعيف"
                } else why = err ?: "جيميناي ماسمعش كلام واضح"
            }
        } catch (e: Throwable) { LogStore.err("lyrics-align", e); why = shortErr(e) }
        if (aligned != null) {
            val res = LyricsResult(name, by, aligned, true, 1, 1, emptySet(), "", lines)
            save(app, t, M_FULL, res); onDone(res, ""); return
        }
        // احتياطي: التوزيع القديم على لحظات الغنا
        onStage("⏱ بحدّد لحظات الغنا في الصوت…")
        val segs = try { Karaoke.segments(app, t) } catch (e: Throwable) { LogStore.err("lyrics-vad", e); emptyList() }
        val placed = if (segs.isNotEmpty()) Karaoke.place(lines, segs) else emptyList()
        val ok = placed.isNotEmpty()
        val res = LyricsResult(name, by, if (ok) placed else estimate(t, lines), true, 1, 1, emptySet(), "التوقيت تقريبي", lines)
        save(app, t, M_FULL, res)
        onDone(res, "ماقدرتش أظبط التوقيت على الصوت" + (if (why.isNotEmpty()) " ($why)" else "") + " — التوقيت تقريبي، دوس 🔁 يعيد")
    }

    /** السطر الشغّال دلوقتي عند الوقت ده (آخر سطر بدأ)، أو -1 قبل أول سطر */
    fun indexAt(lines: List<LyricLine>, sec: Double): Int {
        var lo = 0; var hi = lines.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (lines[m].start <= sec) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }
}
