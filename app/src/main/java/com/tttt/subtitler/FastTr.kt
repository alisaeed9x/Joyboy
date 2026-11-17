package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v1089→v1090) المحرك البسيط = «المتوازن» بزرار «ترجم» القديم: نفس الموديل ونفس تقسيم الباتشات، بس أبسط بكتير.
 *  - كل مفتاح بياخد باتش: يفرّغه (تفريغ بلغته الأصلية) ← الجمل تظهر على طول كنص أبيض عادي (نفس خط ولون وحجم الترجمة الحالية، من غير أي تأثيرات)
 *    كل جملة من بدايتها لنهايتها ← بعدين نفس الباتش يترجم والجمل المفرّغة بتتبدّل بالترجمة.
 *  - مفيش تنقيح ولا سد ثغرات ولا شخصيات ولا لهجات معقّدة: تفريغ + ترجمة بس.
 *  - الترجمة بتتحط في eng.subs (نفس قايمة المحرك الأساسي) فالمشغّل بيعرضها زي أي ترجمة.
 */
class FastTr(
    private val conf: Conf,
    private val openSrc: () -> AudioSource,
    private val eng: EngineBackend,
    private val host: Host,
    private val dialect: String
) {
    companion object {
        const val PAD = 2.0        // ثواني زيادة من الصوت على كل طرف عشان الجملة اللي على الحافة ماتتقصش
        const val TRIES = 6        // أقصى محاولات لكل خطوة (تفريغ / ترجمة) على مفاتيح مختلفة
        const val MAX_WORKERS = 6
    }

    @Volatile var running = false; private set
    @Volatile var paused = false
    @Volatile private var stopped = false
    @Volatile var total = 0; private set
    @Volatile var onState: (() -> Unit)? = null
    private val doneSet = ConcurrentHashMap.newKeySet<Int>()
    private val failedSet = ConcurrentHashMap.newKeySet<Int>()
    private val pool = Pool(conf)
    private val mergeLock = Any()
    private val srcLock = Any()
    private val persistN = AtomicInteger(0)

    val pct: Int get() = if (total <= 0) 0 else (doneSet.size * 100 / total).coerceIn(0, 100)
    val finished: Boolean get() = total > 0 && doneSet.size >= total
    val failedCount: Int get() = failedSet.size

    private fun model(): String = conf.model.ifBlank { "gemini-flash-lite-latest" }
    private fun state() { try { onState?.invoke() } catch (_: Throwable) {} }

    fun start() {
        if (running) { paused = false; state(); return }
        stopped = false; paused = false; running = true; busy = true
        Thread {
            try { runAll() }
            catch (e: Throwable) { try { host.log("⚠ [محرك ٢] وقف بخطأ: " + (e.message ?: e.toString()).take(140)) } catch (_: Throwable) {} }
            finally { running = false; busy = false; state() }
        }.apply { isDaemon = true; name = "fast-tr" }.start()
        state()
    }

    /** (v1090) true طول ما فيه خيوط شغّالة (حتى بعد stop لحد ما آخر طلب يخلص) */
    @Volatile var busy = false; private set
    fun awaitDone(ms: Long) { val end = System.currentTimeMillis() + ms; while (busy && System.currentTimeMillis() < end) nap(50) }

    fun stop() { stopped = true; paused = false; running = false; try { eng.saveNow() } catch (_: Throwable) {}; state() }

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
    private fun waitIfPaused() { while (paused && !stopped) nap(200) }

    private fun runAll() {
        if (conf.keys.isEmpty() && conf.backup.isEmpty()) { host.log("⚠ [محرك ٢] مفيش مفاتيح Gemini"); host.notice("⚠ مفيش مفتاح جيميناي — ضيف واحد من الإعدادات ← المفاتيح"); return }
        val s: AudioSource = synchronized(srcLock) { openSrc() }
        var dur = try { s.durationSec() } catch (_: Throwable) { 0.0 }
        var waited = 0
        while (dur <= 0.0 && waited < 20 && !stopped) { dur = host.playerDuration(); if (dur <= 0.0) nap(300); waited++ }
        if (dur <= 0.0) { host.log("⚠ [محرك ٢] مدة الفيديو لسه مش معروفة"); host.notice("⚠ مدة الفيديو لسه مش معروفة — جرّب تاني بعد ثانية"); return }
        var n = 0
        while (eng.chunkStartSec(n) < dur) n++
        total = n
        // ترتيب الباتشات: من مكان التشغيل لآخر الفيديو، وبعدها اللي قبله
        val start = eng.chunkOfSec(host.position()).coerceIn(0, maxOf(0, n - 1))
        // الباتشات اللي فيها ترجمة جاهزة فعلًا (من أي محرك) بتتخطّى — عشان ماندوّرش على ترجمة موجودة ونحرق الكوتة
        val haveTr = eng.subs.filter { it.translated.isNotBlank() && it.translated != it.original }.map { it.chunk }.toSet()
        val order = ((start until n) + (0 until start)).filter { it !in doneSet && it !in haveTr }
        val workers = pool.all().size.coerceIn(1, MAX_WORKERS)
        host.log("⚡ [محرك ٢] $n باتش · $workers خيط (كل مفتاح باتش) · الموديل ${model().removePrefix("gemini-")}")
        val next = AtomicInteger(0)
        val ths = ArrayList<Thread>()
        for (w in 0 until workers) {
            val t = Thread {
                while (!stopped) {
                    waitIfPaused()
                    if (stopped) break
                    val idx = next.getAndIncrement()
                    if (idx >= order.size) break
                    val i = order[idx]
                    try { process(i, s, dur) }
                    catch (e: Throwable) { failedSet.add(i); host.log("⚠ [محرك ٢] باتش ${i + 1}: " + (e.message ?: e.toString()).take(120)) }
                    state()
                }
            }.apply { isDaemon = true; name = "fast-tr-$w" }
            t.start(); ths.add(t)
        }
        for (t in ths) { try { t.join() } catch (_: InterruptedException) {} }
        try { s.close() } catch (_: Throwable) {}
        try { eng.saveNow() } catch (_: Throwable) {}
        if (!stopped) host.log("⚡ [محرك ٢] خلصت — ${doneSet.size}/$total باتش" + (if (failedSet.isNotEmpty()) " · ⚠ ${failedSet.size} فاشل" else ""))
    }

    // ===== باتش واحد =====
    private fun process(i: Int, s: AudioSource, dur: Double) {
        val bs = eng.chunkStartSec(i)
        val be = minOf(eng.chunkStartSec(i + 1), dur)
        if (be - bs < 0.3) { doneSet.add(i); return }
        val a0 = maxOf(0.0, bs - PAD); val a1 = minOf(dur, be + PAD)
        var w: WavChunk? = synchronized(srcLock) { s.wav(a0, a1) }
        var r = 0
        while (w == null && r++ < 2 && !stopped) { nap(600); w = synchronized(srcLock) { s.wav(a0, a1) } }
        if (w == null || w.silent) { merge(bs, be, emptyList()); doneSet.add(i); return }
        val wav = w
        val t0 = System.currentTimeMillis()

        // 1) تفريغ
        val raw = withKey(i, "تفريغ") { k, _ ->
            val rep = Api.generate(model(), k, transcribePrompt(), wav.bytes, Api.outTokens(wav.durSec), 0.0, true)
            val a = arr(rep.text) ?: throw BadReply("رد التفريغ مش JSON")
            val out = ArrayList<Sub>()
            for (x in 0 until a.length()) {
                val o = a.optJSONObject(x) ?: continue
                val txt = o.optString("text", "").trim()
                if (txt.isEmpty()) continue
                val st = wav.startSec + Parse.sec(o, "start", 0.0)
                var en = wav.startSec + Parse.sec(o, "end", 0.0)
                if (st < bs - 0.25 || st >= be) continue   // الجملة بتتبع الباتش اللي بتبدأ فيه
                if (en <= st + 0.2) en = st + 1.5
                en = minOf(en, dur)
                out.add(plain(st, en, txt, txt, i))
            }
            out
        }
        if (raw == null) { failedSet.add(i); host.log("⚠ [محرك ٢] باتش ${i + 1}: التفريغ فشل بعد $TRIES محاولات"); return }
        merge(bs, be, raw)            // الجمل المفرّغة تظهر دلوقتي
        val tTx = System.currentTimeMillis() - t0
        if (raw.isEmpty()) { doneSet.add(i); failedSet.remove(i); host.log("⚡ [محرك ٢] باتش ${i + 1}: مفيش كلام (${tTx}ms)"); state(); return }

        // 2) ترجمة نفس الباتش
        val lines = raw.map { it.original }
        val tr = withKey(i, "ترجمة") { k, attempt ->
            val rep = Api.generate(model(), k, translatePrompt(lines), null, 8192, 0.2, true)
            val a = arr(rep.text) ?: throw BadReply("رد الترجمة مش JSON")
            if (a.length() != lines.size && attempt < TRIES - 1) throw BadReply("عدد الجمل ${a.length()} بدل ${lines.size}")
            val out = ArrayList<String>()
            for (x in 0 until lines.size) {
                val v = a.opt(x)
                val t = when (v) { is JSONObject -> v.optString("text", v.optString("t", "")); is String -> v; null -> ""; else -> v.toString() }
                out.add(t.trim())
            }
            out
        }
        if (tr != null) {
            val done = raw.mapIndexed { j, sb -> val t = tr.getOrNull(j).orEmpty(); plain(sb.start, sb.end, sb.original, t.ifBlank { sb.original }, i) }
            merge(bs, be, done)
            failedSet.remove(i)
        } else {
            failedSet.add(i)   // التفريغ فضل ظاهر
            host.log("⚠ [محرك ٢] باتش ${i + 1}: الترجمة فشلت — التفريغ فاضل معروض")
        }
        doneSet.add(i)
        host.log("⚡ [محرك ٢] باتش ${i + 1}: ${raw.size} جملة · تفريغ ${tTx}ms · إجمالي ${System.currentTimeMillis() - t0}ms")
        if (persistN.incrementAndGet() % 3 == 0) try { eng.saveNow() } catch (_: Throwable) {}
        state()
    }

    /** جملة عادية من غير أي علامات (لون/إيموشن/متكلم…) عشان تتعرض بنفس شكل الترجمة العادي */
    private fun plain(st: Double, en: Double, orig: String, tr: String, chunk: Int) =
        Sub(st, en, orig, tr, "", "", "", emptyList(), emptyList(), false, false, chunk)

    /** بيبدّل جمل الباتش (بدايتها جوه [bs,be)) بالجديدة ويبلّغ المشغّل */
    private fun merge(bs: Double, be: Double, fresh: List<Sub>) {
        synchronized(mergeLock) {
            val keep = eng.subs.filter { it.start < bs || it.start >= be }
            eng.importSubs((keep + fresh).sortedBy { it.start })
        }
        try { host.changed() } catch (_: Throwable) {}
    }

    // ===== طلب على مفتاح فاضي مع إعادة على مفاتيح تانية =====
    private fun <T> withKey(i: Int, label: String, call: (String, Int) -> T): T? {
        var avoid: String? = null
        var attempt = 0
        val deadline = System.currentTimeMillis() + 5 * 60_000L
        while (!stopped && attempt < TRIES && System.currentTimeMillis() < deadline) {
            waitIfPaused()
            val k = pool.pickIdle(i, avoid, 1) ?: pool.pickFree(avoid, i) ?: pool.pickFree(null, i)
            if (k == null) { nap(500); continue }
            pool.begin(k, i)
            val t0 = System.currentTimeMillis()
            var ok = false
            try {
                val res = call(k, attempt)
                ok = true; pool.good(k)
                return res
            } catch (e: NoKey) {
                pool.block(k, 10 * 60_000L); avoid = k; attempt++
            } catch (e: ApiErr) {
                host.log("⚠ [محرك ٢] باتش ${i + 1} $label: HTTP ${e.code} — بجرّب مفتاح تاني")
                pool.block(k, if (e.code == 429) 60_000L else if (e.code == 403 || e.code == 401 || e.code == 400) 5 * 60_000L else 8_000L)
                avoid = k; attempt++
            } catch (e: BadReply) {
                host.log("⚠ [محرك ٢] باتش ${i + 1} $label: ${e.message}")
                avoid = k; attempt++
            } catch (e: java.io.IOException) {
                pool.block(k, 5_000L); avoid = k; attempt++
            } finally {
                pool.end(k, System.currentTimeMillis() - t0, ok)
            }
        }
        return null
    }

    private class BadReply(msg: String) : Exception(msg)

    private fun arr(t: String): JSONArray? {
        val s = t.replace(Regex("```json\\n?"), "").replace(Regex("```\\n?"), "").trim()
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        if (a < 0 || b <= a) return null
        return try { JSONArray(s.substring(a, b + 1)) } catch (_: Exception) { null }
    }

    private fun transcribePrompt(): String =
        "Transcribe all the speech in this audio exactly as spoken, in its ORIGINAL language (do NOT translate). " +
        "Split it into natural subtitle sentences (one speaker turn or phrase each, short enough to read on screen). " +
        "The audio has a little extra overlap at both ends: transcribe everything you hear. Ignore music and noise that has no speech. " +
        "Return ONLY a JSON array, no markdown, no comments. Each item: {\"start\": seconds from the beginning of THIS audio (number), \"end\": seconds (number), \"text\": \"the sentence\"}. " +
        "If there is no speech return []."

    private fun translatePrompt(lines: List<String>): String {
        val lang = conf.lang.ifBlank { "Arabic" }
        val d = dialect.trim().takeIf { it.isNotEmpty() && it != "فصحى" }?.let { " Use the $it dialect." } ?: ""
        val sb = StringBuilder()
        sb.append("Translate each numbered subtitle line into $lang.$d Keep the meaning and a natural spoken subtitle style; do not merge or split lines. ")
        sb.append("Return ONLY a JSON array of exactly ${lines.size} strings (the translations, in the same order), no markdown.\n\n")
        for ((j, l) in lines.withIndex()) sb.append(j + 1).append(". ").append(l.replace('\n', ' ')).append('\n')
        return sb.toString()
    }
}
