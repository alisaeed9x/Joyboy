package com.tttt.subtitler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * (v362) جلب رابط فيديو/صوت يوتيوب أسرع:
 *  • YtExtract (عملاء يوتيوب بالتوازي) و NewPipe بيشتغلوا مع بعض — أول نتيجة ناجحة تكسب. قبل كده NewPipe ماكانش بيبدأ
 *    غير بعد ما YtExtract يفشل خالص (لحد 40 ثانية) — وده كان سبب «نص دقيقة».
 *  • لو YtExtract فشل مرتين ورا بعض في آخر 10 دقايق: NewPipe بيبدأ فورًا بدل ما يستنى 3 ثواني.
 *  • نتايج NewPipe بتتخزّن 3 ساعات (فيديو وصوت) فتحويل صوت⇄فيديو والفتح التاني للفيديو فوري.
 *  • الزمن والمصدر بيتسجّلوا في اللوج («⏱ يوتيوب …») عشان نعرف بالظبط فين الوقت بيروح.
 */
object YtFast {
    private const val TTL = 3L * 60 * 60 * 1000L
    private val npVid = ConcurrentHashMap<String, Pair<Long, YtPick>>()   // id/maxH
    private val npAud = ConcurrentHashMap<String, Pair<Long, YtPick>>()   // id
    @Volatile private var exFails = 0
    @Volatile private var exFailAt = 0L

    private fun exLikelyDead(): Boolean = exFails >= 2 && System.currentTimeMillis() - exFailAt < 10L * 60 * 1000L
    private fun exOk() { exFails = 0 }
    fun markDead() { exFails = 2; exFailAt = System.currentTimeMillis() }   // الفحص قال: ولا عميل شغّال ← NewPipe يبدأ فورًا
    fun markAlive() { exFails = 0 }
    @Volatile private var started = false
    /** (v363) أول ما التطبيق يفتح (من الخلفية): فحص وتثبيت عميل يوتيوب الشغّال، ولو ولا واحد شغّال نسخّن NewPipe (تحميل الجافاسكربت التقيل) عشان أول فيديو ما يستناش */
    fun startup() {
        if (started) return; started = true
        Thread {
            try { Thread.sleep(2500L) } catch (_: Throwable) {}
            try {
                YtExtract.loadPin()
                val ok = YtExtract.reprobe(true)
                if (!ok) {
                    val t0 = System.currentTimeMillis()
                    YtNp.init(); YtNp.pickAudio("jNQXAC9IVRw")
                    LogStore.add("🔥 NewPipe اتسخّن في " + (System.currentTimeMillis() - t0) + "ms")
                }
            } catch (e: Throwable) { LogStore.add("⚠ تسخين يوتيوب فشل: " + (e.message ?: e.javaClass.simpleName)) }
        }.apply { isDaemon = true; start() }
    }
    private fun exBad() { exFails++; exFailAt = System.currentTimeMillis() }
    private fun <T> live(p: Pair<Long, T>?): T? = if (p != null && System.currentTimeMillis() - p.first < TTL) p.second else null

    /** نتيجة جاهزة للمشغّل: url (+ صوت منفصل لو فيه) + قايمة الجودات JSON */
    class Got(val url: String, val audio: String?, val ua: String, val title: String, val qlist: String, val by: String)

    private fun fromPick(p: YtExtract.Pick, by: String) = Got(p.url, p.audio, p.ua, p.title, p.optsJson(), by)
    private fun fromNp(p: YtPick, by: String) = Got(p.url, p.audio, p.ua, p.title, p.qlist, by)

    private class R(val src: Int, val pick: YtExtract.Pick?, val np: YtPick?)   // src: 0 = YtExtract · 1 = NewPipe

    fun video(id: String, maxH: Int, fresh: Boolean = false): Got? {
        val t0 = System.currentTimeMillis()
        if (!fresh) {
            YtExtract.cachedPick(id, maxH)?.let { LogStore.add("⏱ يوتيوب $id: فيديو من الكاش"); return fromPick(it, "كاش") }
            live(npVid["$id/$maxH"])?.let { LogStore.add("⏱ يوتيوب $id: فيديو من كاش NewPipe"); return fromNp(it, "كاش NewPipe") }
        }
        val q = LinkedBlockingQueue<R>()
        val won = AtomicBoolean(false)
        Thread { q.put(try { R(0, YtExtract.fetchPick(id, maxH, fresh), null) } catch (_: Throwable) { R(0, null, null) }) }.apply { isDaemon = true; start() }
        val delay = if (exLikelyDead()) 0L else 3000L
        Thread {
            try { if (delay > 0) Thread.sleep(delay) } catch (_: Throwable) {}
            if (won.get()) { q.put(R(1, null, null)); return@Thread }
            q.put(try { YtNp.init(); R(1, null, YtNp.pick(id, maxH)?.also { npVid["$id/$maxH"] = Pair(System.currentTimeMillis(), it) }) } catch (_: Throwable) { R(1, null, null) })
        }.apply { isDaemon = true; start() }
        var hls: YtExtract.Pick? = null
        var done = 0
        val deadline = t0 + 50_000L
        while (done < 2) {
            val r = q.poll(maxOf(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS) ?: break
            done++
            if (r.src == 0) {
                val p = r.pick
                if (p == null) exBad()
                else if (p.label == "HLS") { hls = p; exBad() }
                else { exOk(); won.set(true); LogStore.add("⏱ يوتيوب $id: فيديو من YtExtract في " + (System.currentTimeMillis() - t0) + "ms"); return fromPick(p, "YtExtract") }
            } else {
                val n = r.np
                if (n != null) { won.set(true); LogStore.add("⏱ يوتيوب $id: فيديو من NewPipe في " + (System.currentTimeMillis() - t0) + "ms"); return fromNp(n, "NewPipe") }
            }
        }
        won.set(true)
        hls?.let { LogStore.add("⏱ يوتيوب $id: HLS بعد " + (System.currentTimeMillis() - t0) + "ms"); return fromPick(it, "HLS") }
        LogStore.add("⏱ يوتيوب $id: فشل بعد " + (System.currentTimeMillis() - t0) + "ms")
        return null
    }

    /** رابط الصوت بس (m4a) — من الكاش لو فيه، وإلا نفس السباق بين YtExtract و NewPipe */
    fun audio(id: String, maxH: Int): YtPick? {
        val t0 = System.currentTimeMillis()
        YtExtract.cachedPick(id, maxH)?.let { p -> p.opts.firstNotNullOfOrNull { it.audio }?.let { LogStore.add("⏱ يوتيوب $id: صوت من الكاش"); return YtPick(it, null, p.ua, p.title, 0, "", 0L) } }
        live(npAud[id])?.let { LogStore.add("⏱ يوتيوب $id: صوت من كاش NewPipe"); return it }
        live(npVid["$id/$maxH"])?.takeIf { !it.audio.isNullOrEmpty() }?.let { LogStore.add("⏱ يوتيوب $id: صوت من كاش الفيديو"); return YtPick(it.audio!!, null, it.ua, it.title, 0, "", 0L) }
        val q = LinkedBlockingQueue<R>()
        val won = AtomicBoolean(false)
        Thread {
            q.put(try {
                val pk = YtExtract.fetchPick(id, maxH)
                val au = pk?.opts?.firstNotNullOfOrNull { it.audio }
                if (pk == null) exBad() else exOk()
                R(0, pk, if (pk != null && au != null) YtPick(au, null, pk.ua, pk.title, 0, "", 0L) else null)
            } catch (_: Throwable) { R(0, null, null) })
        }.apply { isDaemon = true; start() }
        val delay = if (exLikelyDead()) 0L else 3000L
        Thread {
            try { if (delay > 0) Thread.sleep(delay) } catch (_: Throwable) {}
            if (won.get()) { q.put(R(1, null, null)); return@Thread }
            q.put(try { YtNp.init(); R(1, null, YtNp.pickAudio(id)?.also { npAud[id] = Pair(System.currentTimeMillis(), it) }) } catch (_: Throwable) { R(1, null, null) })
        }.apply { isDaemon = true; start() }
        var done = 0
        val deadline = t0 + 50_000L
        while (done < 2) {
            val r = q.poll(maxOf(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS) ?: break
            done++
            val a = r.np
            if (a != null) { won.set(true); LogStore.add("⏱ يوتيوب $id: صوت من " + (if (r.src == 0) "YtExtract" else "NewPipe") + " في " + (System.currentTimeMillis() - t0) + "ms"); return a }
        }
        won.set(true)
        LogStore.add("⏱ يوتيوب $id: الصوت فشل بعد " + (System.currentTimeMillis() - t0) + "ms")
        return null
    }
}
