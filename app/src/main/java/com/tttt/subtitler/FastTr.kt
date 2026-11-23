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
        // (v1194) التفريغ بيدّي رقم المتكلم (محلي للباتش ده بس) والترجمة بتقرر جنس كل شخص مرة واحدة من كلامه
        const val SPK_TX = "Also give each sentence spk: a whole number for the person speaking, counted 1, 2, 3 in order of first appearance in THIS audio only (the same voice keeps the same number inside this audio, a new voice gets the next number, 0 if you cannot tell). Do not write gender anywhere. "
        const val GENDER_TR = "🔴 Gender and addressing (Arabic verbs, pronouns, adjectives and address words depend on it, and the source language may not mark gender at all). " +
            "A line may start with [N]: N is a speaker number valid only inside this request, given by the transcription step from the audio. The same N is probably the same person, but numbers can be wrong, so the words of a line always beat the number. " +
            "Decide the gender of each person ONCE, using every line of that person: first-person words (Japanese 俺/僕/私/あたし/わし and endings like わよ/かしら/だぜ/ぞ, Korean 오빠/형/누나/언니 said by the speaker, Chinese 老公/老婆), how others address or mention the person (names, titles, vocatives, husband/wife/sir/madam/mom/dad/boss/secretary) and the relationship between the speakers. One clear clue fixes the gender of all that person's lines. " +
            "NEVER decide from voice pitch or tone, and never default to masculine. A reply is spoken by whoever was addressed in the previous line. If the clues for one person conflict, treat that person as unknown. " +
            "Relationship words must match the person they describe: a secretary talking to her male boss says his wife, never \"your husband\"; a woman speaking to a man uses masculine forms for him and feminine forms for herself, and the reverse. " +
            "If s or a is unknown, use neutral phrasing (nouns instead of gendered adjectives, avoid gendered address words) instead of guessing. " +
            "Names: when you decided the gender of a person who is called by name in the lines, put it in n as name=m or name=f (original spelling, comma separated); leave n out otherwise. "
    }

    @Volatile var running = false; private set
    @Volatile var paused = false
    // (v1119) تفريغ بس: الجمل المفرّغة هي الناتج — من غير خطوة ترجمة
    @Volatile var transOnly: Boolean = false
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
    // (v1119) إشعار فصل النت مرة واحدة بس لكل فصل (مش من كل خيط)
    private val downNoteLock = Any()
    private var downNoted = false
    private val mergeLock = Any()
    private val srcLock = Any()
    // (v1194) أسماء → جنس: أي باتش بيتعلّمها والباتشات اللي بتبدأ بعده بتستفيد منها (من غير ما حد يستنى حد)
    private val nameG = ConcurrentHashMap<String, String>()
    private fun learnNames(t: String) {
        if (t.isBlank()) return
        for (p in t.split(',', '،', ';')) {
            val kv = p.split('=')
            if (kv.size != 2) continue
            val k = kv[0].trim(); val g = kv[1].trim().lowercase()
            if (k.isEmpty() || k.length > 24 || (g != "m" && g != "f")) continue
            if (nameG.size >= 80 && !nameG.containsKey(k)) continue
            nameG.putIfAbsent(k, g)
        }
    }
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

    // (v1141) حالة كل باتش للعرض في «📋 الباتشات»: ⏳ اتبعت لجيميناي (مفتاح + خطوة + وقت) · ✅ خلص · ❌ فشل
    private val stage = ConcurrentHashMap<Int, String>()
    private val keyNoOf = ConcurrentHashMap<Int, Int>()
    private val startedAt = ConcurrentHashMap<Int, Long>()
    private val msOf = ConcurrentHashMap<Int, Long>()
    private val subN = ConcurrentHashMap<Int, Int>()

    private fun fmtS(ms: Long): String { val t = (ms / 1000).toInt(); return if (t >= 60) "${t / 60}د${t % 60}ث" else "${t}ث" }

    /** نفس شكل Engine.batchRows (نص + هل هو الأسرع) — بيتعرض بدل صفوف المحرك القديم لما المتوازن شغّال */
    fun batchRows(cur: Int, ahead: Int = 8): List<Pair<String, Boolean>> {
        val d = durSec
        if (total <= 0 || d <= 0) return emptyList()
        val out = ArrayList<Pair<String, Boolean>>()
        val c = cur.coerceIn(0, total - 1)
        for (i in maxOf(0, c - 1)..minOf(total - 1, c + ahead)) {
            val a = try { eng.chunkStartSec(i) } catch (_: Throwable) { break }
            if (a >= d) break
            val b = minOf(try { eng.chunkStartSec(i + 1) } catch (_: Throwable) { d }, d)
            val mark = when {
                i in active -> "⏳"
                i in failedSet && i !in doneSet -> "❌"
                i in failedSet -> "🔁"
                i in doneSet -> if ((subN[i] ?: 1) == 0) "🔇" else "✅"
                else -> "▫"
            }
            val sb = StringBuilder()
            sb.append(if (i == c) "▶ " else "  ").append("باتش ").append(i + 1).append(" ")
                .append("%d:%02d–%d:%02d".format(a.toInt() / 60, a.toInt() % 60, b.toInt() / 60, b.toInt() % 60)).append(" ").append(mark)
            if (mark == "✅" || mark == "🔁") subN[i]?.let { sb.append(" ").append(it).append(" جملة") }
            val kn = keyNoOf[i]
            if (mark == "⏳") {
                sb.append(" · ").append(if (stage[i] == "ترجمة") "📤 بيترجم" else "📤 اتبعت لجيميناي (تفريغ)")
                if (kn != null) sb.append(" · 🔑").append(kn).append(" ").append(model().removePrefix("gemini-"))
                startedAt[i]?.let { sb.append(" · ").append(fmtS(System.currentTimeMillis() - it)) }
            } else if (mark == "✅" || mark == "🔁") {
                if (kn != null) sb.append(" · 🔑").append(kn)
                msOf[i]?.let { sb.append(" · ").append(fmtS(it)) }
            }
            out.add(Pair(sb.toString(), false))
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

    // (v1119) إشعار فصل النت مرة واحدة بس لكل فصل — كبسولة على التلفزيون + بيوصل للموبايل (نفس host.notice بتاع المحرك القديم)
    private fun noteDownOnce() {
        synchronized(downNoteLock) {
            if (downNoted) return
            downNoted = true
        }
        try { host.notice(NetWait.statusText()) } catch (_: Throwable) {}
    }

    // (v1119) إشعار رجوع النت مرة واحدة بس — للي أعلن الفصل
    private fun noteBackOnce() {
        synchronized(downNoteLock) {
            if (!downNoted) return
            downNoted = false
        }
        try { host.notice("✅ النت رجع — بكمّل الترجمة") } catch (_: Throwable) {}
    }

    // (v1119) انتظار رجوع النت مع الإشعارين — بيرجّع الوقت اللي اتمددت بيه مهلة الباتش
    private fun waitNetDown(): Long {
        val t0n = System.currentTimeMillis()
        noteDownOnce()
        NetWait.waitUp({ !stopped }) { try { host.status(NetWait.statusText()) } catch (_: Throwable) {} }
        val spent = System.currentTimeMillis() - t0n
        if (!stopped) noteBackOnce()
        return spent
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
        // (v1119) تفريغ بس: الجملة المفرّغة أصلها هو الناتج — فالموجود يُحسب من الأصل مش من الترجمة
        val haveTr = eng.subs.filter {
            if (transOnly) it.original.isNotBlank() else (it.translated.isNotBlank() && it.translated != it.original)
        }.map { it.chunk }.toSet()
        val order = ((start until n) + (0 until start)).filter { it !in doneSet && it !in haveTr }
        if (oneKey) {   // (v1197) 🔑 وضع المفتاح الواحد
            runOneKey(s, dur, order)
            try { s.close() } catch (_: Throwable) {}
            try { eng.saveNow() } catch (_: Throwable) {}
            if (!stopped) host.log("🔑 [مفتاح واحد] خلصت — ${doneSet.size}/$total باتش" + (if (failedSet.isNotEmpty()) " · ⚠ ${failedSet.size} فاشل" else ""))
            return
        }
        val workers = pool.all().size.coerceIn(1, MAX_WORKERS)
        host.log("⚡ [محرك ٢] $n باتش · $workers خيط (كل مفتاح باتش) · الموديل ${model().removePrefix("gemini-")}")
        val next = AtomicInteger(0)
        val rampFirst = if (try { Cfg.bool("ramp_all", true) } catch (_: Throwable) { true }) order.firstOrNull() ?: -1 else -1   // (v1141) أول باتش (مكان التشغيل) بيتقسّم لخطوات بتكبر
        val ths = ArrayList<Thread>()
        for (w in 0 until workers) {
            val t = Thread {
                while (!stopped) {
                    waitIfPaused()
                    if (stopped) break
                    waitForSlot()
                    if (stopped) break
                    val idx = next.getAndIncrement()
                    if (idx >= order.size) break
                    val i = order[idx]
                    active.add(i); startedAt[i] = System.currentTimeMillis(); stage[i] = "تفريغ"
                    try { if (i == rampFirst) processRamp(i, s, dur) else process(i, s, dur) }
                    catch (e: Throwable) { failedSet.add(i); host.log("⚠ [محرك ٢] باتش ${i + 1}: " + (e.message ?: e.toString()).take(120)) }
                    finally { active.remove(i); msOf[i] = System.currentTimeMillis() - (startedAt[i] ?: System.currentTimeMillis()) }
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
    /** (v1141) أول باتش: خطوات 10←20←30←45←60ث (كل خطوة تفريغ+ترجمة وتظهر على طول) بدل الباتش كله مرة واحدة */
    private val RAMP = doubleArrayOf(10.0, 20.0, 30.0, 45.0, 60.0)
    private fun processRamp(i: Int, s: AudioSource, dur: Double) {
        val cs = eng.chunkStartSec(i); val ce = minOf(eng.chunkStartSec(i + 1), dur)
        val pos = try { host.position() } catch (_: Throwable) { cs }
        val lead = maxOf(cs, minOf(pos - 1.0, ce - 3.0))
        if (ce - lead < 12.0 && lead <= cs + 0.05) { process(i, s, dur); return }
        var a = lead; var step = 0
        host.log("⚡ [محرك ٢] باتش ${i + 1}: خطوات تصاعدية من ${a.toInt()}ث")
        while (a < ce - 0.05 && !stopped) {
            val size = RAMP.getOrNull(step) ?: Double.MAX_VALUE
            var b = minOf(ce, a + size); if (ce - b < 8.0) b = ce
            val isLast = b >= ce - 0.05 && lead <= cs + 0.05
            process(i, s, dur, a, b, isLast)
            if (failedSet.contains(i)) return   // خطوة فشلت: الباتش يفضل فاشل (زي الأول)
            a = b; step++
            waitIfPaused()
        }
        if (lead > cs + 0.05 && !stopped) process(i, s, dur, cs, lead, true)   // اللي قبل مكان التشغيل في الباتش ده
    }

    private fun process(i: Int, s: AudioSource, dur: Double, wbs: Double = Double.NaN, wbe: Double = Double.NaN, last: Boolean = true) {
        val bs = if (wbs.isNaN()) eng.chunkStartSec(i) else wbs
        val be = if (wbe.isNaN()) minOf(eng.chunkStartSec(i + 1), dur) else wbe
        if (be - bs < 0.3) { if (last) doneSet.add(i); return }
        val a0 = maxOf(0.0, bs - PAD); val a1 = minOf(dur, be + PAD)
        var w: WavChunk? = s.wav(a0, a1)
        var r = 0
        while (w == null && r++ < 2 && !stopped) { nap(600); w = s.wav(a0, a1) }
        if (w == null || w.silent) { merge(bs, be, emptyList()); if (last) doneSet.add(i); return }
        val wav = w
        val t0 = System.currentTimeMillis()

        // 1) تفريغ
        val rawP = withKey(i, "تفريغ") { k, _ ->
            val rep = Api.generate(model(), k, transcribePrompt(), wav.bytes, Api.outTokens(wav.durSec), 0.0, true)
            val a = arr(rep.text) ?: throw BadReply("رد التفريغ مش JSON")
            val out = ArrayList<Sub>()
            val hs = ArrayList<String>()
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
                hs.add(spkTag(o.optInt("spk", 0)))
            }
            Pair(out, hs)
        }
        val raw = rawP?.first
        val hints = rawP?.second ?: emptyList()
        if (raw == null) { failedSet.add(i); host.log("⚠ [محرك ٢] باتش ${i + 1}: التفريغ فشل بعد $TRIES محاولات"); return }
        merge(bs, be, raw)            // الجمل المفرّغة تظهر دلوقتي
        subN[i] = raw.size
        val tTx = System.currentTimeMillis() - t0
        if (raw.isEmpty()) { if (last) doneSet.add(i); failedSet.remove(i); host.log("⚡ [محرك ٢] باتش ${i + 1}: مفيش كلام (${tTx}ms)"); state(); return }

        // (v1119) تفريغ بس: مفيش خطوة ترجمة — الجمل المفرّغة هي الناتج النهائي
        if (transOnly) {
            if (last) doneSet.add(i)
            failedSet.remove(i)
            host.log("⚡ [محرك ٢] باتش ${i + 1}: ${raw.size} جملة · تفريغ بس ${tTx}ms")
            if (persistN.incrementAndGet() % 3 == 0) try { eng.saveNow() } catch (_: Throwable) {}
            state()
            return
        }

        // 2) ترجمة نفس الباتش
        val lines = raw.map { it.original }
        val tr = withKey(i, "ترجمة") { k, attempt ->
            val rep = Api.generate(model(), k, translatePrompt(lines, hints), null, 8192, 0.2, true)
            val a = arr(rep.text) ?: throw BadReply("رد الترجمة مش JSON")
            if (a.length() != lines.size && attempt < TRIES - 1) throw BadReply("عدد الجمل ${a.length()} بدل ${lines.size}")
            val out = ArrayList<String>()
            for (x in 0 until lines.size) {
                val v = a.opt(x)
                if (v is JSONObject) learnNames(v.optString("n", ""))
                val t = when (v) { is JSONObject -> v.optString("t", v.optString("text", "")); is String -> v; null -> ""; else -> v.toString() }
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
        if (last) doneSet.add(i)
        host.log("⚡ [محرك ٢] باتش ${i + 1}: ${raw.size} جملة · تفريغ ${tTx}ms · إجمالي ${System.currentTimeMillis() - t0}ms")
        if (persistN.incrementAndGet() % 3 == 0) try { eng.saveNow() } catch (_: Throwable) {}
        state()
    }

    /** جملة عادية من غير أي علامات (لون/إيموشن/متكلم…) عشان تتعرض بنفس شكل الترجمة العادي */
    private fun plain(st: Double, en: Double, orig: String, tr: String, chunk: Int) =
        Sub(st, en, orig, tr, "", "", "", emptyList(), emptyList(), false, false, chunk)

    /** بيبدّل جمل الباتش (بدايتها جوه [bs,be)) بالجديدة ويبلّغ المشغّل */
    private fun merge(bs: Double, be: Double, fresh: List<Sub>) {
        // (v1119) نتيجة طلب خلص بعد الإلغاء ماتوصلش — الباتشات اللي اتلغت ماترجعش تظهر
        if (stopped) return
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
        while (!stopped && attempt < TRIES && System.currentTimeMillis() < deadline) {
            waitIfPaused()
            // (v1101) بوابة النت: النت واقع → استنى هنا (مش بنحرق محاولات ولا مفاتيح) والمهلة بتتمدّد بقد وقت الفصل
            if (NetWait.gateDown()) {
                deadline += waitNetDown()
                continue
            }
            val k = if (oneKey) oneKeyPick(avoid) else (pool.pickIdle(i, avoid, 1) ?: pool.pickFree(avoid, i) ?: pool.pickFree(null, i))   // (v1197) مفتاح واحد: المترجم الأول، والباتش الواقع لمفتاح فاضي ثم المؤكّد
            if (k == null) { nap(500); continue }
            pool.begin(k, i); stage[i] = label; keyNoOf[i] = pool.all().indexOf(k) + 1
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
                // (v1101) فصل نت مش فشل مفتاح
                if (NetWait.stillDown(e)) {
                    deadline += waitNetDown()
                    continue
                }
                pool.block(k, 5_000L); avoid = k; attempt++
            } finally {
                pool.end(k, System.currentTimeMillis() - t0, ok)
            }
        }
        return null
    }

    // ===== (v1197) 🔑 وضع المفتاح الواحد =====
    // مفتاح «مترجم» بياخد الباتشات واحد ورا التاني (مش بالتوازي) وبيرتاح بين كل باتش عشان ما يجيش 429.
    // ومفتاح «تأكيدي» بياخد كل التفريغ الأصلي للباتشات اللي خلصت (القديم والجديد) ويبعته لجيميناي يترجمه بالسياق
    // ويبدّل بيه الترجمات القديمة. باتش وقع → مفتاح فاضي تاني، ولو مفيش بيستنى المفتاح التأكيدي يفضى.
    @Volatile var oneKey: Boolean = false
    @Volatile private var oneT: String? = null
    @Volatile private var oneC: String? = null
    @Volatile private var oneFinished = false
    private val trDone = ConcurrentHashMap.newKeySet<Int>()
    private val confirmed = ConcurrentHashMap.newKeySet<Int>()

    /** الراحة بين بداية باتش وبداية اللي بعده (ms) — تتظبط من onekey_rest_ms (افتراضي 8000 = طلبين لكل باتش على ~15 طلب/دقيقة) */
    private fun oneRestMs(): Long = try { Cfg.int("onekey_rest_ms", 8000).toLong().coerceIn(0L, 60_000L) } catch (_: Throwable) { 8000L }

    private fun oneKeyPick(avoid: String?): String? {
        val t = oneT; val c = oneC
        if (avoid == null && t != null && pool.ok(t) && pool.loadOf(t) == 0) return t
        val others = pool.all().filter { it != avoid && it != c && pool.ok(it) && pool.loadOf(it) == 0 }
        others.firstOrNull()?.let { return it }
        if (c != null && c != avoid && pool.ok(c) && pool.loadOf(c) == 0) return c
        if (pool.all().size <= 1 && t != null && pool.ok(t) && pool.loadOf(t) == 0) return t
        return null
    }

    private fun runOneKey(s: AudioSource, dur: Double, order: List<Int>) {
        val keys = conf.keys.filter { it.length > 10 }
        val tKey = keys.firstOrNull() ?: pool.all().firstOrNull() ?: return
        val cKey = (conf.refine + conf.backup + keys.drop(1)).firstOrNull { it != tKey && it.length > 10 }
        oneT = tKey; oneC = cKey; oneFinished = false
        host.log("🔑 [مفتاح واحد] $total باتش بالتوالي · مترجم ${pool.tail(tKey)} · " +
            (if (cKey != null) "تأكيد ${pool.tail(cKey)}" else "من غير مفتاح تأكيد (تأكيد واحد في الآخر)") +
            " · راحة ${oneRestMs() / 1000}ث بين الباتشات · الموديل ${model().removePrefix("gemini-")}")
        val confirmTh: Thread? = if (cKey != null && !transOnly) Thread {
            try { confirmLoop(cKey) } catch (e: Throwable) { try { host.log("⚠ [مفتاح واحد] المؤكّد وقف: " + (e.message ?: e.toString()).take(120)) } catch (_: Throwable) {} }
        }.apply { isDaemon = true; name = "fast-tr-confirm"; start() } else null

        fun one(i: Int): Long {
            val t0 = System.currentTimeMillis()
            active.add(i); startedAt[i] = t0; stage[i] = "تفريغ"
            try {
                process(i, s, dur)
                if (i in doneSet && i !in failedSet) trDone.add(i)
            } catch (e: Throwable) {
                failedSet.add(i); host.log("⚠ [مفتاح واحد] باتش ${i + 1}: " + (e.message ?: e.toString()).take(120))
            } finally {
                active.remove(i); msOf[i] = System.currentTimeMillis() - t0
            }
            state()
            return t0
        }
        fun rest(t0: Long) {
            var left = oneRestMs() - (System.currentTimeMillis() - t0)
            while (left > 0 && !stopped) { val n = minOf(left, 500L); nap(n); left -= n }
        }
        for (i in order) {
            if (stopped) break
            waitIfPaused()
            if (stopped) break
            rest(one(i))
        }
        // مرحلة تانية: الباتشات اللي وقعت تتجرّب تاني (مفتاح فاضي، ولو مفيش المؤكّد لما يفضى)
        val retry = order.filter { it in failedSet }
        if (retry.isNotEmpty() && !stopped) host.log("🔑 [مفتاح واحد] إعادة ${retry.size} باتش وقعوا")
        for (i in retry) {
            if (stopped) break
            waitIfPaused()
            if (stopped) break
            rest(one(i))
        }
        oneFinished = true
        try { confirmTh?.join() } catch (_: InterruptedException) {}
        // مفيش مفتاح تاني: تأكيد واحد في الآخر على نفس المفتاح بعد راحة
        if (cKey == null && !transOnly && !stopped && trDone.size > 1) {
            var left = oneRestMs(); while (left > 0 && !stopped) { nap(500); left -= 500 }
            if (!stopped) confirmBatches(tKey, trDone.toList().sorted())
        }
    }

    private fun confirmLoop(key: String) {
        var fails = 0
        var endBy = 0L
        while (!stopped) {
            waitIfPaused()
            val fresh = trDone.filter { it !in confirmed }
            if (oneFinished) { if (endBy == 0L) endBy = System.currentTimeMillis() + 3 * 60_000L; if (fresh.isEmpty() || System.currentTimeMillis() > endBy) break }
            if (fresh.isEmpty()) { nap(500); continue }
            if (!pool.ok(key) || pool.loadOf(key) > 0) { nap(500); continue }   // المفتاح التأكيدي مشغول بباتش واقع أو واخد 429
            val upto = trDone.toList().sorted()
            if (confirmBatches(key, upto)) { confirmed.addAll(upto); fails = 0 }
            else if (++fails >= 3) { confirmed.addAll(upto); fails = 0; try { host.log("⚠ [مفتاح واحد] التأكيد فشل 3 مرات — بكمّل من غيره للباتشات دي") } catch (_: Throwable) {} }
            else nap(3000)
        }
    }

    private fun confirmPrompt(lines: List<String>): String =
        "These numbered lines are consecutive subtitle lines of ONE video, in order (earlier lines first). They were transcribed from the audio in their original language. " +
        "Translate each line using the context of ALL the lines (who is speaking, who is addressed, names, the running topic) so the translation is consistent and accurate. " +
        translatePrompt(lines)

    /** بيبعت أصل كل الباتشات المذكورة (آخر 300 جملة بحد أقصى) للترجمة بالسياق ويبدّل بيه الترجمات الموجودة */
    private fun confirmBatches(k: String, batches: List<Int>): Boolean {
        val bs = batches.toSet()
        var src = eng.subs.filter { it.chunk in bs && it.original.isNotBlank() }.sortedBy { it.start }
        if (src.size > 300) src = src.takeLast(300)
        if (src.isEmpty()) return true
        val lines = src.map { it.original }
        val t0 = System.currentTimeMillis()
        var ok = false
        pool.begin(k)
        try {
            val rep = Api.generate(model(), k, confirmPrompt(lines), null, 8192, 0.2, true)
            val a = arr(rep.text) ?: throw BadReply("رد التأكيد مش JSON")
            if (a.length() != lines.size) throw BadReply("عدد جمل التأكيد ${a.length()} بدل ${lines.size}")
            val map = HashMap<String, String>()
            for (x in lines.indices) {
                val v = a.opt(x)
                if (v is JSONObject) learnNames(v.optString("n", ""))
                val tr = (when (v) { is JSONObject -> v.optString("t", v.optString("text", "")); is String -> v; else -> "" }).trim()
                if (tr.isNotEmpty()) map[src[x].start.toString() + "|" + src[x].original] = tr
            }
            if (stopped) return false
            synchronized(mergeLock) {
                val out = eng.subs.map { sb -> val tr = map[sb.start.toString() + "|" + sb.original]; if (tr != null) sb.copy(translated = tr) else sb }
                eng.importSubs(out.sortedBy { it.start })
            }
            try { host.changed() } catch (_: Throwable) {}
            ok = true; pool.good(k)
            host.log("🔎 [مفتاح واحد] تأكيد بالسياق: ${lines.size} جملة (${batches.size} باتش) · ${System.currentTimeMillis() - t0}ms")
            if (persistN.incrementAndGet() % 3 == 0) try { eng.saveNow() } catch (_: Throwable) {}
            return true
        } catch (e: NoKey) {
            pool.block(k, 10 * 60_000L); return false
        } catch (e: ApiErr) {
            host.log("⚠ [مفتاح واحد] التأكيد: HTTP ${e.code}")
            if (e.code == 503) record503()
            pool.block(k, if (e.code == 429) 60_000L else if (e.code == 403 || e.code == 401 || e.code == 400) 5 * 60_000L else 8_000L)
            return false
        } catch (e: BadReply) {
            host.log("⚠ [مفتاح واحد] التأكيد: ${e.message}"); return false
        } catch (e: java.io.IOException) {
            if (NetWait.stillDown(e)) waitNetDown() else pool.block(k, 5_000L)
            return false
        } finally {
            pool.end(k, System.currentTimeMillis() - t0, ok)
        }
    }

    private class BadReply(msg: String) : Exception(msg)

    private fun arr(t: String): JSONArray? {
        val s = t.replace(Regex("```json\\n?"), "").replace(Regex("```\\n?"), "").trim()
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        if (a < 0 || b <= a) return null
        return try { JSONArray(s.substring(a, b + 1)) } catch (_: Exception) { null }
    }

    @Volatile private var songLogged = ""
    /** (v1171) 🎵 وضع الأغاني في المحرك البسيط: بلوك آخر البرومبت + كلمات LRCLIB — واللوج بيقول مرة واحدة */
    private fun songBlock(): String {
        if (!SongHint.on()) return ""
        val b = SongHint.block(transOnly, durSec, compact = true)
        val st = SongHint.state()
        if (st != songLogged) { songLogged = st; try { host.log("🎵 [محرك ٢] وضع الأغاني — $st · قاعدة «تجاهل الموسيقى» متلغية") } catch (_: Throwable) {} }
        return b
    }

    private fun transcribePrompt(): String =
        "Transcribe all the speech in this audio exactly as spoken, in its ORIGINAL language (do NOT translate). " +
        "Split it into natural subtitle sentences (one speaker turn or phrase each, short enough to read on screen). " +
        "The audio has a little extra overlap at both ends: transcribe everything you hear. Ignore music and noise that has no speech. " +
        "Return ONLY a JSON array, no markdown, no comments. Each item: {\"start\": seconds from the beginning of THIS audio (number), \"end\": seconds (number), \"text\": \"the sentence\"" + (if (transOnly) "" else ", \"spk\": 1") + "}. " +
        (if (transOnly) "" else SPK_TX) +
        "If there is no speech return []." + songBlock()

    /** (v1194) رقم المتكلم من التفريغ (محلي للباتش ده بس) → «[N]» قدام السطر، أو فاضي لو 0 */
    private fun spkTag(n: Int): String = if (n > 0) "[$n]" else ""

    private fun translatePrompt(lines: List<String>, hints: List<String> = emptyList()): String {
        val lang = conf.lang.ifBlank { "Arabic" }
        val d = dialect.trim().takeIf { it.isNotEmpty() && it != "فصحى" }?.let { " Use the $it dialect." } ?: ""
        val sb = StringBuilder()
        sb.append("Translate each numbered subtitle line into $lang.$d Keep the meaning and a natural spoken subtitle style; do not merge or split lines. ")
        if (SongHint.on()) sb.append("These lines are song lyrics: translate them as natural poetic lines (the meaning, not word by word). ")
        sb.append(GENDER_TR)
        val known = nameG.entries.take(20).joinToString(", ") { "${it.key}=${it.value}" }
        if (known.isNotEmpty()) sb.append("Already known from other parts of this video (name=gender): $known. ")
        sb.append("Return ONLY a JSON array of exactly ${lines.size} objects, in the same order, no markdown. Each object: {\"s\":\"m|f|p|?\",\"a\":\"m|f|p|?\",\"t\":\"the translation\",\"n\":\"optional names\"} — decide s and a FIRST, then write t.\n\n")
        for ((j, l) in lines.withIndex()) {
            val h = hints.getOrNull(j).orEmpty()
            sb.append(j + 1).append(". ").append(if (h.isEmpty()) "" else "$h ").append(l.replace('\n', ' ')).append('\n')
        }
        return sb.toString()
    }
}
