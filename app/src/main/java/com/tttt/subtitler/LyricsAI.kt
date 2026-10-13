package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * (v294) مصدر الكلمات الاحتياطي بجيميناي — بيشتغل بس لما LRCLIB / Musixmatch / YouTube Music / Lyrics.ovh ما لقوش حاجة
 * (وكمان بيدّي توقيت للكلمات اللي اتلقت من غير توقيت).
 *
 *  ① النص: جيميناي (بحث جوجل شغّال) بيكتب الكلمات كاملة بالاسم والفنان والألبوم، كل سطر، وبيكمّل لو الرد اتقطع.
 *  ② التوقيت: جيميناي بيسمع الأغنية على نوافذ قصيرة (بتداخل) ويكتب اللي اتغنّى بوقت بداية/نهاية كل سطر (مش من النص، من الصوت).
 *  ③ مواءمة كلمة بكلمة (Needleman–Wunsch) بين النص وبين المسموع: كل سطر في النص ياخد وقته من الصوت الحقيقي،
 *     وبعدين بنشدّ بداية السطر لأقرب بداية صوت في منحنى الطاقة.
 *  ④ حماية من التأليف: لو أقل من نص المسموع لقى له مقابل في النص ← النص غلط (جيميناي اخترعه) ← بنعرض المسموع نفسه (تفريغ من الصوت).
 */
object LyricsAI {
    private const val WIN = 30.0          // طول نافذة السماع بالثواني
    private const val OVERLAP = 4.0       // التداخل بين نافذتين
    private const val MIN_HEARD_MATCH = 0.45   // أقل نسبة من المسموع لازم يتطابق مع النص عشان نثق في النص

    class Heard(val s: Double, val e: Double, val text: String)

    private fun clean(s: String) = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    private fun model() = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }

    // ===================== ① النص الكامل =====================
    /** كلمات الأغنية كاملة من جيميناي. null لو مش عارفها أو مفيش مفتاح */
    fun fetchText(name: String, by: String, album: String, durSec: Double, wrong: Boolean = false): List<String>? {
        val keys = Cfg.allMainKeys()
        if (keys.isEmpty()) return null
        val pr0 = buildString {
            append("Write the COMPLETE lyrics of this song, exactly as officially sung.\n")
            append("Song: \"$name\"")
            if (by.isNotBlank()) append("\nArtist: \"$by\"")
            if (album.isNotBlank()) append("\nAlbum: \"$album\"")
            if (durSec > 0) append("\nDuration: about ${(durSec / 60.0).let { "%.1f".format(java.util.Locale.US, it) }} minutes")
            append("\n\nUse Google Search to find the real lyrics; do not rely on memory alone.\n")
            append("RULES:\n")
            append("- Output EVERY line from the first sung line to the last. Never summarize, never skip, never write \"(repeat)\" or \"x2\": write a repeated chorus/verse out in full every time it is sung.\n")
            append("- Keep the original language and script (Arabic stays Arabic with the exact dialect spelling, English stays English). No translation, no transliteration.\n")
            append("- One sung line per row. No section headers like [Chorus], no numbering, no commentary, no markdown.\n")
            append("- If you do not really know this song, or you are not sure the lyrics are the real ones, output exactly: NOT_FOUND\n")
            if (wrong) append("- WARNING: the lyrics given before for this song were reported WRONG by the user. Search again carefully and verify against at least one real lyrics page before answering.\n")
        }
        val all = ArrayList<String>()
        var lastErr = ""
        Api.readTimeoutOverride.set(180000)
        try {
            var prompt = pr0
            var round = 0
            var ki = 0
            while (round < 4) {
                var res: Api.Result? = null
                for (a in 0 until keys.size.coerceAtMost(3)) {
                    try { res = Api.generate(model(), keys[(ki + a) % keys.size], prompt, null, 16000, if (wrong) 0.4 else 0.1, false, true); ki += a + 1; break }
                    catch (e: Throwable) { lastErr = e.message ?: e.javaClass.simpleName }
                }
                if (res == null) break
                val txt = res.text.replace("\r", "").trim()
                if (round == 0 && (txt.isBlank() || txt.lines().first().trim().equals("NOT_FOUND", true))) return null
                val lines = txt.lines().map { it.trim().removePrefix("*").removePrefix("-").trim() }
                    .filter { it.isNotEmpty() && !it.equals("NOT_FOUND", true) && !(it.startsWith("[") && it.endsWith("]")) }
                all.addAll(lines)
                val cut = res.finish.equals("MAX_TOKENS", true) && lines.isNotEmpty()
                if (!cut) break
                round++
                prompt = pr0 + "\nYou already wrote these lines and the answer was cut. Continue from the line AFTER this one, without repeating anything before it:\n\"" + lines.last() + "\"\n"
            }
        } finally { Api.readTimeoutOverride.remove() }
        if (all.isEmpty() && lastErr.isNotEmpty()) LogStore.err("lyricsai-text", RuntimeException(lastErr))
        return if (all.size >= 4) all else null
    }

    // ===================== ② السماع بتوقيت =====================
    private fun listenPrompt(hintName: String, hintBy: String): String = buildString {
        append("You are a precise lyrics transcriber and timer. The audio is a SHORT excerpt of a song")
        if (hintName.isNotBlank()) append(" (\"$hintName\"" + (if (hintBy.isNotBlank()) " by $hintBy" else "") + ")")
        append(".\nTranscribe ONLY the words that are actually sung or spoken in this excerpt, in the original language and script, one sung phrase per row, with its start and end time in seconds measured from the START of this excerpt (1 decimal).\n")
        append("RULES:\n- Never invent or complete lines you cannot hear. Skip instrumental parts. If a phrase is unintelligible, leave it out.\n")
        append("- A phrase is one natural sung line (usually 2-8 seconds). Do not merge two lines in one row. Write repeated lines every time they are sung.\n")
        append("- Times must follow the real audio: s = the moment the first word of the phrase starts, e = the moment its last word ends.\n")
        append("OUTPUT: JSON only: {\"lines\":[{\"s\":0.0,\"e\":0.0,\"t\":\"\"}]}")
    }

    /** بيسمع الأغنية كلها على نوافذ بتداخل ويرجّع السطور المسموعة بوقتها المطلق. فاضية = ما سمعش حاجة. onProg(تم، الكل) */
    fun listen(app: Context, t: Track, name: String, by: String, onProg: (Int, Int) -> Unit): List<Heard> {
        val keys = Cfg.allMainKeys()
        if (keys.isEmpty()) return emptyList()
        val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
        val wins = ArrayList<Triple<Int, Double, WavChunk>>()
        val total: Double
        try {
            total = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
            var a = 0.0; var i = 0
            while (a < total - 2.0) {
                val b = minOf(a + WIN, total)
                val w = try { src.wav(a, b) } catch (_: Throwable) { null }
                if (w != null && !w.silent) wins.add(Triple(i, a, w))
                i++
                if (b >= total) break
                a += WIN - OVERLAP
            }
        } finally { try { src.close() } catch (_: Throwable) {} }
        if (wins.isEmpty()) return emptyList()
        val lastIdx = wins.maxOf { it.first }
        val queue = ConcurrentLinkedQueue(wins)
        val out = java.util.Collections.synchronizedList(ArrayList<Heard>())
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val nThreads = keys.size.coerceIn(1, 3)
        val latch = CountDownLatch(nThreads)
        val prompt = listenPrompt(name, by)
        for (w in 0 until nThreads) {
            Thread {
                try {
                    Api.readTimeoutOverride.set(120000)
                    while (true) {
                        val (idx, a0, chunk) = queue.poll() ?: break
                        var got: List<Heard>? = null
                        for (att in 0 until keys.size.coerceAtMost(4)) {
                            val key = keys[(w + idx + att) % keys.size]
                            try {
                                val r = Api.generate(model(), key, prompt, chunk.bytes, 4096, 0.0, true, false)
                                val arr = JSONObject(clean(r.text)).optJSONArray("lines")
                                val l = ArrayList<Heard>()
                                if (arr != null) for (k in 0 until arr.length()) {
                                    val o = arr.optJSONObject(k) ?: continue
                                    val tx = o.optString("t").trim()
                                    val s = o.optDouble("s", -1.0); var e = o.optDouble("e", -1.0)
                                    if (tx.isEmpty() || s < 0 || s > chunk.durSec + 1.0) continue
                                    if (e <= s) e = s + 2.0
                                    l.add(Heard(a0 + s, a0 + minOf(e, chunk.durSec + 1.0), tx))
                                }
                                got = l; break
                            } catch (e: ApiErr) { if (e.code == 429) continue }
                            catch (e: Throwable) { LogStore.err("lyricsai-listen", e) }
                        }
                        if (got != null) {
                            // كل نافذة «بتملك» نصها الأوسط بس: التداخل بيتاخد من النافذة اللي السطر مش على طرفها
                            val own0 = if (idx == 0) -1.0 else a0 + OVERLAP / 2
                            val own1 = if (idx == lastIdx) Double.MAX_VALUE else a0 + chunk.durSec - OVERLAP / 2
                            for (h in got) if (h.s >= own0 && h.s < own1) out.add(h)
                        }
                        onProg(done.incrementAndGet(), wins.size)
                    }
                } finally { Api.readTimeoutOverride.remove(); latch.countDown() }
            }.apply { isDaemon = true }.start()
        }
        latch.await(15, java.util.concurrent.TimeUnit.MINUTES)
        return out.sortedBy { it.s }
    }

    // ===================== ③ المواءمة =====================
    private class W(val raw: String, val key: String, val line: Int)

    private fun words(lines: List<String>): List<W> {
        val res = ArrayList<W>()
        for ((li, l) in lines.withIndex()) for (w in ArNorm.fold(l).split(' ')) if (w.isNotEmpty()) res.add(W(w, w.let { if (it.length > 3 && it.startsWith("ال")) it.substring(2) else it }, li))
        return res
    }

    private fun wordSim(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 3 || b.length < 3) return false
        val d = lev(a, b)
        return d.toDouble() / maxOf(a.length, b.length) <= 0.25
    }
    private fun lev(a: String, b: String): Int {
        val p = IntArray(b.length + 1) { it }; val c = IntArray(b.length + 1)
        for (i in 1..a.length) {
            c[0] = i
            for (j in 1..b.length) c[j] = minOf(c[j - 1] + 1, p[j] + 1, p[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            System.arraycopy(c, 0, p, 0, c.size)
        }
        return p[b.length]
    }

    class Aligned(val lines: List<LyricLine>, val heardCoverage: Double, val textCoverage: Double)

    /**
     * يواءم [text] (الكلمات الرسمية) على [heard] (المسموع بتوقيته). بيرجّع سطور النص بوقتها من الصوت، ونسبتين:
     * heardCoverage = كام % من المسموع لقى مقابل في النص · textCoverage = كام % من كلمات النص اتسمعت.
     * null لو حجم المواءمة كبير جدًا.
     */
    fun align(text: List<String>, heard: List<Heard>, total: Double, env: FloatArray?): Aligned? {
        val o = words(text)
        // كلمات المسموع بوقتها: كل سطر مسموع بيوزّع وقته على كلماته بنسبة الحروف
        class HW(val key: String, val t0: Double, val t1: Double)
        val h = ArrayList<HW>()
        for (ln in heard) {
            val ws = ArNorm.fold(ln.text).split(' ').filter { it.isNotEmpty() }
            if (ws.isEmpty()) continue
            val tot = ws.sumOf { it.length }.toDouble().coerceAtLeast(1.0)
            var acc = 0.0
            for (w in ws) {
                val a = ln.s + (ln.e - ln.s) * acc / tot; acc += w.length
                val b = ln.s + (ln.e - ln.s) * acc / tot
                h.add(HW(if (w.length > 3 && w.startsWith("ال")) w.substring(2) else w, a, b))
            }
        }
        val m = o.size; val n = h.size
        if (m == 0 || n == 0) return null
        if (m.toLong() * n > 4_000_000L) return null
        // Needleman–Wunsch: تطابق +2 · عدم تطابق -1 · فجوة -1
        val sc = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) sc[i][0] = -i
        for (j in 1..n) sc[0][j] = -j
        for (i in 1..m) for (j in 1..n) {
            val mt = if (wordSim(o[i - 1].key, h[j - 1].key)) 2 else -1
            sc[i][j] = maxOf(sc[i - 1][j - 1] + mt, sc[i - 1][j] - 1, sc[i][j - 1] - 1)
        }
        val oStart = DoubleArray(m) { -1.0 }; val oEnd = DoubleArray(m) { -1.0 }
        var i = m; var j = n; var matched = 0
        while (i > 0 && j > 0) {
            val mt = if (wordSim(o[i - 1].key, h[j - 1].key)) 2 else -1
            when {
                sc[i][j] == sc[i - 1][j - 1] + mt -> { if (mt > 0) { oStart[i - 1] = h[j - 1].t0; oEnd[i - 1] = h[j - 1].t1; matched++ }; i--; j-- }
                sc[i][j] == sc[i - 1][j] - 1 -> i--
                else -> j--
            }
        }
        val heardCov = matched.toDouble() / n
        val textCov = matched.toDouble() / m
        // وقت كل سطر: أول كلمة متطابقة فيه (ولو مفيش: نملّاه بين أقرب سطرين متثبّتين بنسبة الحروف)
        val L = text.size
        val ls = DoubleArray(L) { -1.0 }; val le = DoubleArray(L) { -1.0 }
        for (k in 0 until m) { val li = o[k].line; if (oStart[k] >= 0) { if (ls[li] < 0 || oStart[k] < ls[li]) ls[li] = oStart[k]; if (oEnd[k] > le[li]) le[li] = oEnd[k] } }
        val chars = IntArray(L) { text[it].count { c -> c.isLetterOrDigit() }.coerceAtLeast(3) }
        val floor = heard.minOf { it.s }; val ceil = heard.maxOf { it.e }.coerceAtMost(total.coerceAtLeast(1.0))
        var k = 0
        while (k < L) {
            if (ls[k] >= 0) { k++; continue }
            var a = k; while (a < L && ls[a] < 0) a++            // [k, a) من غير وقت
            val prevEnd = if (k > 0) maxOf(le[k - 1], ls[k - 1]) else floor
            val nextStart = if (a < L) ls[a] else maxOf(ceil, prevEnd + 1.0)
            val span = (nextStart - prevEnd).coerceAtLeast(0.5 * (a - k))
            val sum = (k until a).sumOf { chars[it] }.toDouble()
            var acc = 0.0
            for (q in k until a) { ls[q] = prevEnd + span * acc / sum; acc += chars[q]; le[q] = prevEnd + span * acc / sum }
            k = a
        }
        // ترتيب تصاعدي + شدّ البداية لأقرب بداية صوت
        val res = ArrayList<LyricLine>()
        var prev = -1.0
        for (q in 0 until L) {
            var s = ls[q]
            if (env != null) s = Karaoke.snapOnset(env, s, 0.8)
            if (s <= prev) s = prev + 0.3
            ls[q] = s; prev = s
        }
        for (q in 0 until L) {
            val nextS = if (q + 1 < L) ls[q + 1] else Double.MAX_VALUE
            var e = if (le[q] > ls[q]) le[q] else ls[q] + 2.5
            e = minOf(e + 0.3, nextS, ls[q] + 12.0)
            res.add(LyricLine(ls[q], maxOf(e, ls[q] + 0.5), text[q]))
        }
        return Aligned(res, heardCov, textCov)
    }

    // ===================== الواجهة العامة =====================
    /** @return نتيجة جاهزة (متزامنة) أو null + رسالة السبب */
    class Out(val res: LyricsResult?, val msg: String)

    /**
     * [givenText] = كلمات من غير توقيت لقاها مصدر تاني (بنوقّتها بس). null = ما لقيناش كلمات: جيميناي يكتبها كاملة.
     */
    fun run(app: Context, t: Track, name: String, by: String, album: String, givenText: List<String>?, source: String, wrong: Boolean, onStage: (String) -> Unit): Out {
        if (Cfg.allMainKeys().isEmpty()) return Out(null, "محتاج مفتاح جيميناي عشان يكتب الكلمات ويوقّتها")
        val total = t.durMs / 1000.0
        var text = givenText
        var textMsg = ""
        val fetchThread: Thread? = if (text == null) Thread {
            onStage("🤖 جيميناي بيكتب الكلمات كاملة…")
            text = try { fetchText(name, by, album, total, wrong) } catch (e: Throwable) { LogStore.err("lyricsai-fetch", e); null }
        }.apply { isDaemon = true; start() } else null
        val heard = try { listen(app, t, name, by) { d, n -> onStage("🎧 جيميناي بيسمع الأغنية ويوقّت الكلمات ($d/$n)…") } }
                    catch (e: Throwable) { LogStore.err("lyricsai-listen", e); emptyList() }
        fetchThread?.join(5 * 60_000L)
        val tx = text
        val env = try { Karaoke.envelope(app, t) } catch (_: Throwable) { null }
        fun fromHeard(note: String): Out {
            if (heard.size < 3) return Out(null, textMsg.ifBlank { "جيميناي ما سمعش كلام واضح في الأغنية" })
            val ls = heard.mapIndexed { i, h -> LyricLine(h.s, maxOf(h.e, h.s + 0.8), h.text) }
            val fixed = ls.mapIndexed { i, l -> val nx = if (i + 1 < ls.size) ls[i + 1].start else Double.MAX_VALUE; LyricLine(l.start, minOf(l.end, nx), l.text) }
            return Out(LyricsResult(name, by, fixed, true, 1, 1, emptySet(), note, fixed.map { it.text }, true, "gemini-audio"), "")
        }
        if (tx == null || tx.size < 4) {
            return if (heard.isEmpty()) Out(null, "جيميناي ما لقيش الكلمات وما سمعش كلام واضح") else fromHeard("تفريغ من الصوت (الكلمات الرسمية مالقيناهاش) — التوقيت من الصوت")
        }
        if (heard.size < 3) {
            // ما قدرناش نسمع (كوتة/صمت): النص كامل بس من غير توقيت دقيق
            val raw = tx
            return Out(LyricsResult(name, by, raw.map { LyricLine(0.0, 0.0, it) }, true, 1, 1, emptySet(), "من غير توقيت (جيميناي ما قدرش يسمع الأغنية)", raw, false, source.ifBlank { "gemini" }), "")
        }
        onStage("🧩 بوفّق الكلمات على الصوت…")
        val al = align(tx, heard, total, env)
        if (al == null || al.heardCoverage < MIN_HEARD_MATCH) {
            return fromHeard("تفريغ من الصوت — النص اللي اتكتب ما طابقش اللي اتسمع، فعرضنا المسموع نفسه")
        }
        val note = if (al.textCoverage >= 0.7) "" else "التوقيت دقيق للمقاطع اللي اتسمعت (${(al.textCoverage * 100).toInt()}% من الكلمات) والباقي متوزّع بينها"
        return Out(LyricsResult(name, by, al.lines, true, 1, 1, emptySet(), note, al.lines.map { it.text }, true, (if (givenText == null) "gemini" else source.ifBlank { "نص" } + "+gemini")), "")
    }
}
