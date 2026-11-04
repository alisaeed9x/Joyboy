package com.tttt.subtitler

import org.json.JSONObject

/**
 * (v696) ضبط توقيت الترجمة تلقائي: بنسمّع جيميناي عينات صوت قصيرة من الفيديو (٢–٥ عينات في أماكن مختلفة) ومعاها نص الترجمة
 * (أي لغة — بيطابق بالمعنى)، فبيرجّع لكل جملة سمعها رقم سطر الترجمة اللي بيقولها. من الأزواج (وقت الفيديو، وقت الترجمة) بنحسب:
 *  • إزاحة ثابتة (الترجمة متأخرة/مبكرة)، أو
 *  • إزاحة + ميل (اختلاف فريمات 23.976 / 25 …)، بخوارزمية تتجاهل الجمل الغلط.
 * الحسابات (fit / shift / describe) صافية وبتتفحص من غير Android.
 */
object SubAlign {
    /** زوج: وقت في الفيديو (v) ووقت نفس الجملة في ملف الترجمة (s) */
    class P(val v: Double, val s: Double)
    /** تحويل: وقت الفيديو = a × وقت الترجمة + b */
    class Fit(val kind: String, val a: Double, val b: Double, val inliers: Int, val total: Int)
    class Outcome(val fit: Fit?, val msg: String, val windows: Int, val lang: String)

    private const val TOL = 1.2

    /** أحسن تحويل لأزواج فيها جمل غلط. null = أقل من ٣ أزواج */
    fun fit(p: List<P>, tol: Double = TOL): Fit? {
        val n = p.size
        if (n < 3) return null
        val d = DoubleArray(n) { p[it].v - p[it].s }
        var bestIn: List<Int> = emptyList()
        for (i in 0 until n) {
            val inl = (0 until n).filter { Math.abs(d[it] - d[i]) <= tol }
            if (inl.size > bestIn.size) bestIn = inl
        }
        val sortedD = bestIn.map { d[it] }.sorted()
        val med = sortedD[sortedD.size / 2]
        val off = Fit("offset", 1.0, med, bestIn.size, n)

        var bestA = 1.0; var bestB = 0.0; var linIn: List<Int> = emptyList()
        for (i in 0 until n) for (j in i + 1 until n) {
            val ds = p[j].s - p[i].s
            if (Math.abs(ds) < 300.0) continue
            val a = (p[j].v - p[i].v) / ds
            if (a < 0.9 || a > 1.12) continue
            val b = p[i].v - a * p[i].s
            val inl = (0 until n).filter { Math.abs(a * p[it].s + b - p[it].v) <= tol * 0.8 }
            if (inl.size > linIn.size) { bestA = a; bestB = b; linIn = inl }
        }
        if (linIn.size >= 4) {
            val m = linIn.size.toDouble()
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (k in linIn) { sx += p[k].s; sy += p[k].v; sxx += p[k].s * p[k].s; sxy += p[k].s * p[k].v }
            val den = m * sxx - sx * sx
            if (Math.abs(den) > 1e-9) { bestA = (m * sxy - sx * sy) / den; bestB = (sy - bestA * sx) / m }
            if (Math.abs(bestA - 1.0) > 0.0004 && linIn.size >= off.inliers * 1.3 + 1) return Fit("linear", bestA, bestB, linIn.size, n)
        }
        return off
    }

    fun apply(f: Fit, t: Double): Double = f.a * t + f.b

    /** مفيش داعي للتعديل: إزاحة أقل من ٠٫٣٥ ث ومن غير ميل */
    fun isNoop(f: Fit): Boolean = Math.abs(f.a - 1.0) < 0.0004 && Math.abs(f.b) < 0.35

    fun describe(f: Fit): String =
        if (f.kind == "linear") String.format(java.util.Locale.US, "إزاحة %+.1f ث وسرعة ×%.4f (فرق فريمات)", f.b, f.a)
        else String.format(java.util.Locale.US, "إزاحة %+.1f ث", f.b)

    fun shiftCues(cues: List<SubFileTr.Cue>, f: Fit): List<SubFileTr.Cue> = cues.map { c ->
        val st = Math.max(0.0, apply(f, c.start)); val en = Math.max(st + 0.1, apply(f, c.end))
        SubFileTr.Cue(st, en, c.text)
    }

    fun shiftSubs(subs: List<Sub>, f: Fit): List<Sub> = subs.map { s ->
        val st = Math.max(0.0, apply(f, s.start)); val en = Math.max(st + 0.1, apply(f, s.end))
        s.copy(start = st, end = en)
    }

    // ===== أماكن العينات =====
    private fun winLen(dur: Double): Double = if (dur in 1.0..480.0) 50.0 else 75.0

    /** بداية كل عينة بالثواني، مترتبة بالأولوية (الوسط · الآخر · الأول · …) */
    fun positions(dur: Double): List<Double> {
        val w = winLen(dur)
        if (dur <= 0.0) return listOf(300.0, 900.0, 1500.0)
        val fr = when {
            dur < 480.0 -> listOf(0.5, 0.25, 0.75)
            dur < 1500.0 -> listOf(0.5, 0.82, 0.2)
            dur < 3600.0 -> listOf(0.5, 0.85, 0.15, 0.68)
            else -> listOf(0.45, 0.88, 0.12, 0.66, 0.28)
        }
        val hi = Math.max(20.0, dur - w - 20.0)
        return fr.map { Math.min(hi, Math.max(20.0, dur * it - w / 2)) }
    }

    // ===== جيميناي =====
    private fun keys(): List<String> = (Cfg.allMainKeys() + Cfg.keys("viskeys")).distinct().filter { it.length > 10 }
    private fun model(): String = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }

    private fun prompt(win: Int, lines: String): String =
        "You align a subtitle file to a video's audio. The audio is a short excerpt (about $win seconds) taken from the video.\n" +
        "Listen carefully and list up to 14 distinct spoken lines you can clearly hear, in order. For each line give:\n" +
        "- \"s\": start time in seconds from the START OF THIS AUDIO (decimal number)\n" +
        "- \"e\": end time in seconds from the start of this audio\n" +
        "- \"id\": the number of the subtitle line below that says the same thing (the subtitle may be in a different language, a translation or a paraphrase). Use -1 when no line clearly corresponds.\n" +
        "Rules: match by meaning only, never guess; each id at most once; ids must come from the list; ignore music and sound effects; if there is no clear speech return an empty list.\n" +
        "Also return \"lang\": the ISO 639-1 code of the spoken language.\n" +
        "Return JSON only, exactly like: {\"lang\":\"en\",\"lines\":[{\"s\":3.2,\"e\":5.8,\"id\":214}]}\n" +
        "SUBTITLE LINES (id|text):\n" + lines

    private fun ask(prompt: String, wav: ByteArray, ks: List<String>, first: Int): String {
        var last: Exception? = null
        for (attempt in 0 until Math.min(4, Math.max(ks.size, 2))) {
            val key = ks[(first + attempt) % ks.size]
            try { return Api.generate(model(), key, prompt, wav, 8192, 0.1, true, false).text }
            catch (e: Exception) {
                last = e
                val code = (e as? ApiErr)?.code ?: 0
                try { Thread.sleep(if (code == 429) 4000L * (attempt + 1) else 1500L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        throw last ?: Exception("فشل جيميناي")
    }

    /** نص الترجمة ← «id|text» لأسطر الكلام بس (من غير [music] و♪). لو أكتر من ١٦٠٠ سطر بنختار بالتساوي */
    fun linesFor(cues: List<SubFileTr.Cue>): Pair<String, Set<Int>> {
        val idx = ArrayList<Int>()
        for ((i, c) in cues.withIndex()) {
            val t = c.text.replace(Regex("\\s*\\n\\s*"), " ").trim()
            if (t.count { it.isLetter() } < 2) continue
            if (t.startsWith("[") && t.endsWith("]")) continue
            if (t.startsWith("♪") || t.startsWith("(") && t.endsWith(")") && t.length < 25) continue
            idx.add(i)
        }
        val step = if (idx.size > 1600) (idx.size + 1599) / 1600 else 1
        val sb = StringBuilder(); val used = HashSet<Int>()
        var k = 0
        while (k < idx.size) {
            val i = idx[k]
            sb.append(i).append('|').append(cues[i].text.replace(Regex("\\s*\\n\\s*"), " ").trim().take(90)).append('\n')
            used.add(i); k += step
        }
        return Pair(sb.toString(), used)
    }

    /** رد جيميناي لعينة ← أزواج (وقت الفيديو، وقت الترجمة) + لغة الكلام */
    fun pairsOf(reply: String, winOrigin: Double, winSec: Double, cues: List<SubFileTr.Cue>, valid: Set<Int>): Pair<List<P>, String> {
        val js: JSONObject = Parse.json(reply) ?: return Pair(emptyList(), "")
        val arr = js.optJSONArray("lines") ?: return Pair(emptyList(), js.optString("lang"))
        val out = ArrayList<P>(); val seen = HashSet<Int>()
        for (q in 0 until arr.length()) {
            val o = arr.optJSONObject(q) ?: continue
            val id = o.optInt("id", -1)
            if (id < 0 || id !in valid || !seen.add(id)) continue
            val s = o.optDouble("s", Double.NaN); if (s.isNaN()) continue
            var e = o.optDouble("e", Double.NaN); if (e.isNaN() || e < s) e = s + 1.0
            if (s < -1.0 || s > winSec + 5.0) continue
            val c = cues[id]
            out.add(P(winOrigin + (s + e) / 2.0, (c.start + c.end) / 2.0))
        }
        return Pair(out, js.optString("lang"))
    }

    /**
     * العملية كلها (من خيط خلفي). audio = صوت الفيديو، durSec مدته (0 لو مجهولة)، cues = الترجمة الحالية.
     * report بيتنادى بنص التقدم، cancel() لو true بيوقف.
     */
    fun run(audio: AudioSource, durSec: Double, cues: List<SubFileTr.Cue>, report: (String) -> Unit, cancel: () -> Boolean): Outcome {
        val ks = keys()
        if (ks.isEmpty()) return Outcome(null, "مفيش مفتاح جيميناي — ضيفه من الإعدادات (ضبط التوقيت بيسمع الصوت بجيميناي)", 0, "")
        val (lines, valid) = linesFor(cues)
        if (valid.size < 8) return Outcome(null, "الترجمة قصيرة أو أغلبها وصف أصوات — مفيش كلام كفاية للمطابقة", 0, "")
        val all = ArrayList<P>()
        var used = 0; var lang = ""
        val pos = positions(durSec)
        val first = (System.currentTimeMillis() / 1000L % ks.size).toInt()
        for ((wi, p0) in pos.withIndex()) {
            if (cancel()) return Outcome(null, "اتلغى", used, lang)
            val len = winLen(durSec)
            report("🎯 بسمع عينة " + (wi + 1) + "/" + pos.size + " (من الدقيقة " + (p0 / 60.0).toInt() + ")…")
            var chunk: WavChunk? = try { audio.wav(p0, p0 + len) } catch (e: Throwable) { LogStore.err("SubAlign:audio", e); null }
            if (chunk == null || chunk.silent) chunk = try { audio.wav(p0 + 45.0, p0 + 45.0 + len) } catch (_: Throwable) { null }
            if (chunk == null || chunk.silent) { LogStore.add("🎯 ضبط التوقيت: عينة $wi صامتة/مش متاحة"); continue }
            val reply = try { ask(prompt(len.toInt(), lines), chunk.bytes, ks, first + wi) }
            catch (e: Throwable) {
                LogStore.err("SubAlign:ask", e)
                if (all.isEmpty() && wi == pos.size - 1) return Outcome(null, "جيميناي ما ردّش: " + (e.message ?: "").take(70), used, lang)
                continue
            }
            val (ps, lg) = pairsOf(reply, chunk.startSec, chunk.durSec.takeIf { it > 1.0 } ?: len, cues, valid)
            if (lg.isNotBlank() && lang.isBlank()) lang = lg
            used++
            all.addAll(ps)
            LogStore.add("🎯 ضبط التوقيت: عينة ${wi + 1} ← ${ps.size} جملة متطابقة")
            if (used >= 2) {
                val f = fit(all)
                if (f != null && f.kind == "offset" && f.inliers >= 5 && f.inliers * 10 >= f.total * 7) {
                    val agree = all.count { Math.abs((it.v - it.s) - f.b) <= TOL }
                    if (agree >= 5) return finish(f, used, lang)
                }
            }
        }
        val f = fit(all)
        if (f == null) return Outcome(null, "مقدرتش ألاقي جمل متطابقة بين الصوت والترجمة — ممكن الترجمة لفيديو تاني", used, lang)
        return finish(f, used, lang)
    }

    private fun finish(f: Fit, windows: Int, lang: String): Outcome {
        if (f.inliers < 4) return Outcome(null, "تطابق ضعيف (" + f.inliers + " جمل بس) — مش واثق أغيّر التوقيت", windows, lang)
        if (f.kind == "linear" && windows < 2) return Outcome(null, "الفرق شكله اختلاف فريمات بس العينات مش كفاية", windows, lang)
        if (Math.abs(f.b) > 120.0 && f.inliers < 6) return Outcome(null, "الفرق كبير (" + describe(f) + ") والتطابق ضعيف — مش هغيّر", windows, lang)
        return Outcome(f, if (isNoop(f)) "التوقيت سليم ✓" else describe(f), windows, lang)
    }
}
