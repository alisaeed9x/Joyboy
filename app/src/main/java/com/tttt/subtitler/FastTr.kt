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
    // (v1117) توازي تكيفي لموجة 503: أوقات آخر أخطاء 503 — النافذة 60ث وبتتقصّ عند القراءة
    private val err503Times = ArrayDeque<Long>()
    @Volatile private var last503At = 0L
    @Volatile private var lastCapLogged = -1
    private val mergeLock = Any()
    // (v1118) نسخة التأمين للباتش البطيء: وقت بدء كل باتش شغّال، وتفريغه الأول، ومين كسب الترجمة
    private val startedAt = ConcurrentHashMap<Int, Long>()
    private val rawOf = ConcurrentHashMap<Int, List<Sub>>()
    private val trWon = ConcurrentHashMap.newKeySet<Int>()
    private val hedged = ConcurrentHashMap.newKeySet<Int>()
    private val hedgeRunning = AtomicInteger(0)
    private val finishedMs = ArrayList<Long>()
    private val srcLock = Any()
    private val persistN = AtomicInteger(0)
    private val active = ConcurrentHashMap.newKeySet<Int>()   // (دمج v1081) باتشات اتبعتت (تفريغ/ترجمة) ولسه ما خلصتش
    @Volatile private var durSec = 0.0

    /** مجالات الباتشات اللي اتبعتت ولسه ما خلصتش — أزرق في شريط التقدم (نفس معنى Engine.inflightSegs) */
    fun inflightSegs(): List<DoubleArray> {
        if (active.isEmpty() || durSec <= 0) return emptyList()
        val out = ArrayList<DoubleArray>()
        for (i in active.toList().sorted()) {
            val a = try { eng.chunkStartSec(i) } catch (_: Throwable) { continue }
            val b = minOf(try { eng.chunkStartSec(i + 1) } catch (_: Throwable) { durSec }, durSec)
            if (b > a) out.add(doubleArrayOf(a, b))
        }
        return out
    }

    /** (v1116) FIX 1: نقاط حالة المفاتيح من حوض المحرك البسيط نفسه — نفس معنى وألوان Engine.keyDots */
    fun keyDots(): String {
        val dots = arrayOf("🔵", "🟢", "🔴", "🟠")
        val parts = pool.all().mapIndexed { idx, k -> dots[pool.dotState(k)] + (idx + 1) }
        return parts.joinToString(" ")
    }

    // (v1117) سقف التوازي العادي = عدد الخيوط اللي بتتفتح أصلًا
    private fun normalCap(): Int {
        return pool.all().size.coerceIn(1, MAX_WORKERS)
    }

    // (v1117) سقف التوازي الحالي حسب موجة 503 — من غير أي انتظار: 0-1 خطأ في آخر 60ث = عادي، 2 = 2، 3+ = 1
    // (v1117) ولو مفيش 503 من 30ث بيرجع للعادي لوحده حتى لو نافذة الـ60ث لسه فيها أخطاء قديمة
    private fun capNow(): Int {
        val now = System.currentTimeMillis()
        if (now - last503At > 30_000L) {
            return normalCap()
        }
        val n = synchronized(err503Times) {
            while (err503Times.isNotEmpty() && now - err503Times.first() > 60_000L) {
                err503Times.removeFirst()
            }
            err503Times.size
        }
        if (n >= 3) {
            return minOf(1, normalCap())
        }
        if (n == 2) {
            return minOf(2, normalCap())
        }
        return normalCap()
    }

    // (v1117) تسجيل خطأ 503 لحساب الموجة — بيتنده من مسار إعادة المحاولة بس، من غير ما يغيّر فيه حاجة
    private fun record503() {
        val now = System.currentTimeMillis()
        last503At = now
        synchronized(err503Times) {
            err503Times.addLast(now)
            while (err503Times.isNotEmpty() && now - err503Times.first() > 60_000L) {
                err503Times.removeFirst()
            }
        }
    }

    // (v1117) سطر لوج واحد مع كل تغيير في السقف بس — مش مع كل باتش
    @Synchronized private fun logCapIfChanged(cap: Int) {
        if (lastCapLogged < 0) {
            lastCapLogged = cap
            return
        }
        if (cap == lastCapLogged) {
            return
        }
        lastCapLogged = cap
        if (cap < normalCap()) {
            try {
                host.log("⚡ التوازي نزل لـ$cap — موجة 503")
            } catch (_: Throwable) {
            }
        } else {
            try {
                host.log("⚡ التوازي رجع $cap")
            } catch (_: Throwable) {
            }
        }
    }

    // (v1118) وسيط زمن آخر باتشات خلصت — null لو لسه أقل من 3
    private fun noteFinish(ms: Long) {
        synchronized(finishedMs) {
            finishedMs.add(ms)
            if (finishedMs.size > 12) {
                finishedMs.removeAt(0)
            }
        }
    }

    private fun medianMs(): Long? {
        val copy = synchronized(finishedMs) { finishedMs.toList() }
        if (copy.size < 3) {
            return null
        }
        return copy.sorted()[copy.size / 2]
    }

    // (v1118) بعد قد إيه الباتش يتعتبر بطيء: 1.6 × الوسيط (بين 25 و90ث)، ونص كده للباتش اللي المشغّل واقف عنده أو اللي بعده
    private fun hedgeAfterMs(i: Int): Long {
        val med = medianMs()
        val base = if (med == null) 45_000L else (med * 16 / 10).coerceIn(25_000L, 90_000L)
        val cur = try { eng.chunkOfSec(host.position()) } catch (_: Throwable) { -1 }
        if (i == cur || i == cur + 1) {
            return maxOf(12_000L, base / 2)
        }
        return base
    }

    private fun isStraggler(i: Int, now: Long): Boolean {
        if (hedged.contains(i)) {
            return false
        }
        if (trWon.contains(i)) {
            return false
        }
        val t = startedAt[i] ?: return false
        return now - t > hedgeAfterMs(i)
    }

    // (v1118) الخيط اللي خلّص باتشه بيدوّر الأول على باتش بطيء يعمله نسخة تأمين على مفتاحه الفاضي — مش بيستنى ولا بيحجب أي باتش تاني
    // (v1118) مفيش تأمين وقت موجة 503 (هيضاعف الحمل)، ونسخة تأمين واحدة بس في نفس الوقت
    @Synchronized private fun claimStraggler(): Int {
        if (capNow() < normalCap()) {
            return -1
        }
        if (hedgeRunning.get() >= 1) {
            return -1
        }
        val now = System.currentTimeMillis()
        for (i in active.toList().sorted()) {
            if (isStraggler(i, now) && hedged.add(i)) {
                hedgeRunning.incrementAndGet()
                host.log("⚡ [محرك ٢] باتش ${i + 1}: بطيء — نسخة تأمين على مفتاح تاني (أول واحدة تخلص تكسب)")
                return i
            }
        }
        return -1
    }

    private fun runHedge(i: Int, s: AudioSource, dur: Double) {
        try {
            process(i, s, dur, true)
        } catch (e: Throwable) {
            host.log("⚠ [محرك ٢] باتش ${i + 1} نسخة التأمين: " + (e.message ?: e.toString()).take(100))
        } finally {
            hedgeRunning.decrementAndGet()
        }
    }

    // (v1117) بوابة بدء الباتشات: الخيط بيستنى خانة فاضية تحت السقف الحالي — نفس نمط waitIfPaused اللي في الملف
    // (v1117) دي مش انتظار قبل إعادة 503 ولا وقفة ثابتة بين الباتشات — الباتش اللي ليه خانة بيبدأ فورًا، والانتظار هنا بس لما السقف مليان
    private fun waitForSlot() {
        while (!stopped) {
            val cap = capNow()
            logCapIfChanged(cap)
            if (active.size < cap) {
                return
            }
            nap(200)
        }
    }

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
        durSec = dur
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
                    waitForSlot()
                    if (stopped) break
                    val h = claimStraggler()
                    if (h >= 0) {
                        runHedge(h, s, dur)
                        state()
                        continue
                    }
                    val idx = next.getAndIncrement()
                    if (idx >= order.size) {
                        // (v1118) الباتشات خلصت توزيع: لو لسه فيه باتش شغّال الخيط الفاضي يفضل يراقب عشان يعمل نسخة تأمين
                        if (active.isEmpty()) break
                        nap(1000)
                        continue
                    }
                    val i = order[idx]
                    active.add(i)
                    try { process(i, s, dur) }
                    catch (e: Throwable) { failedSet.add(i); host.log("⚠ [محرك ٢] باتش ${i + 1}: " + (e.message ?: e.toString()).take(120)) }
                    finally { active.remove(i) }
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
    private fun process(i: Int, s: AudioSource, dur: Double, hedge: Boolean = false) {
        val bs = eng.chunkStartSec(i)
        val be = minOf(eng.chunkStartSec(i + 1), dur)
        if (be - bs < 0.3) { doneSet.add(i); return }
        val t0 = System.currentTimeMillis()
        if (!hedge) {
            startedAt[i] = t0
        }
        // (v1118) نسخة التأمين لو التفريغ خلص عند الأصلي بتترجم بس من غير ما ترفع الصوت تاني
        val cached = if (hedge) rawOf[i] else null

        // 1) تفريغ
        val raw = cached ?: transcribeStep(i, s, dur, bs, be)
        if (raw == null) {
            if (trWon.contains(i)) return
            if (hedge && active.contains(i)) return
            failedSet.add(i)
            host.log("⚠ [محرك ٢] باتش ${i + 1}: التفريغ فشل بعد $TRIES محاولات")
            return
        }
        if (cached == null) {
            // (v1118) أول تفريغ يخلص هو اللي يظهر ويكمل؛ التاني يقف (والتأمين يرجع متاح لو الترجمة اتأخرت)
            val first = rawOf.putIfAbsent(i, raw) == null
            if (!first) {
                if (hedge) hedged.remove(i)
                return
            }
            merge(bs, be, raw)            // الجمل المفرّغة تظهر دلوقتي
        }
        val tTx = System.currentTimeMillis() - t0
        if (raw.isEmpty()) {
            if (!trWon.add(i)) return
            doneSet.add(i); failedSet.remove(i)
            noteFinish(System.currentTimeMillis() - (startedAt[i] ?: t0))
            host.log("⚡ [محرك ٢] باتش ${i + 1}: مفيش كلام (${tTx}ms)")
            state()
            return
        }

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
            // (v1118) أول ترجمة تخلص (الأصلي أو التأمين) هي اللي بتتدمج
            if (!trWon.add(i)) return
            merge(bs, be, done)
            failedSet.remove(i)
        } else {
            if (trWon.contains(i)) return
            if (hedge && active.contains(i)) return
            failedSet.add(i)   // التفريغ فضل ظاهر
            host.log("⚠ [محرك ٢] باتش ${i + 1}: الترجمة فشلت — التفريغ فاضل معروض")
        }
        doneSet.add(i)
        noteFinish(System.currentTimeMillis() - (startedAt[i] ?: t0))
        host.log("⚡ [محرك ٢] باتش ${i + 1}: ${raw.size} جملة · تفريغ ${tTx}ms · إجمالي ${System.currentTimeMillis() - t0}ms" + (if (hedge) " · 🏁 نسخة التأمين كسبت" else ""))
        if (persistN.incrementAndGet() % 3 == 0) try { eng.saveNow() } catch (_: Throwable) {}
        state()
    }

    // (v1118) خطوة التفريغ اتفصلت عن process بدون تغيير في المنطق — الصوت الفاضي بيرجع قايمة فاضية (بيتعامل معاه زي «مفيش كلام»)
    private fun transcribeStep(i: Int, s: AudioSource, dur: Double, bs: Double, be: Double): List<Sub>? {
        val a0 = maxOf(0.0, bs - PAD); val a1 = minOf(dur, be + PAD)
        var w: WavChunk? = synchronized(srcLock) { s.wav(a0, a1) }
        var r = 0
        while (w == null && r++ < 2 && !stopped) { nap(600); w = synchronized(srcLock) { s.wav(a0, a1) } }
        if (w == null || w.silent) return emptyList()
        val wav = w
        return withKey(i, "تفريغ") { k, _ ->
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
        var deadline = System.currentTimeMillis() + 5 * 60_000L
        while (!stopped && attempt < TRIES && System.currentTimeMillis() < deadline && !trWon.contains(i)) {
            waitIfPaused()
            // (v1101) بوابة النت: النت واقع → استنى هنا (مش بنحرق محاولات ولا مفاتيح) والمهلة بتتمدّد بقد وقت الفصل
            if (NetWait.gateDown()) { val t0n = System.currentTimeMillis(); NetWait.waitUp({ !stopped }) { try { host.status(NetWait.statusText()) } catch (_: Throwable) {} }; deadline += System.currentTimeMillis() - t0n; continue }
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
                if (e.code == 503) {
                    record503()
                }
                pool.block(k, if (e.code == 429) 60_000L else if (e.code == 403 || e.code == 401 || e.code == 400) 5 * 60_000L else 8_000L)
                avoid = k; attempt++
            } catch (e: BadReply) {
                host.log("⚠ [محرك ٢] باتش ${i + 1} $label: ${e.message}")
                avoid = k; attempt++
            } catch (e: java.io.IOException) {
                if (NetWait.stillDown(e)) { val t0n = System.currentTimeMillis(); NetWait.waitUp({ !stopped }) { try { host.status(NetWait.statusText()) } catch (_: Throwable) {} }; deadline += System.currentTimeMillis() - t0n; continue }   // (v1101) فصل نت مش فشل مفتاح
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
