package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * (v998 — وارد) محرك التفريغ بطريقة Warid، مكتوب هنا من الصفر من الوصف بس (كود Warid الأصلي AGPL — متنقلش منه سطر).
 * الفكرة: فك الصوت → WAV خام 16kHz mono → قطع دقايق → ستريم لجيميناي بـ thinkingBudget 0.
 * بيتنده من Lecture.doChunk للدوكيومنتات اللي d.warid == true بس — الوضع العادي مبيتلمسش.
 * حدود الحالة لكل موديل (أرقام وارد المنشورة — ممكن تختلف على مفاتيح المستخدم):
 *  Lite 500 طلب/يوم · Flash 20 طلب/يوم · 429 → retryDelay أو 60ث · حمل زايد/5xx → 3 ساعات · يوم خلص → لحد نص الليل · أقصى كول داون 6 ساعات.
 * الطلب بيتحسب بس لما أول توكن يوصل (onFirstToken) — محاولة ماتجابتش توكن مبتستهلكش من الكوتة.
 */
class WaridStall(val partial: String, msg: String = "warid stream stalled") : Exception(msg)

object WaridTx {
    const val CHUNK_SEC_WARID = 300.0
    const val CHUNK_MIN_KEY = "warid_chunk_min"
    const val STATE_KEY = "warid_state"
    const val READ_TIMEOUT_MS = 300000
    const val FIRST_TOKEN_BASE_MS = 45000L
    const val STALL_GAP_MS = 25000L
    const val UPLOAD_FLOOR_BPS = 50000L
    const val MIN_ACTIVE_SEC = 3.0
    const val LITE_DAILY_CAP = 500
    const val FLASH_DAILY_CAP = 20
    const val MAX_COOLDOWN_MS = 6L * 3600L * 1000L
    const val OVERLOAD_COOLDOWN_MS = 3L * 3600L * 1000L
    const val STALL_COOLDOWN_MS = 60000L

    val CHAIN = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3-flash-preview",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    )

    private class ModelState(var daily: Int = 0, var minBucket: Long = 0L, var minCount: Int = 0, var coolUntil: Long = 0L)

    private val states = ConcurrentHashMap<String, ModelState>()
    @Volatile private var loadedDay = ""

    private fun today(): String = Quota.dayKey(System.currentTimeMillis())

    private fun loadLocked() {
        val day = today()
        if (loadedDay == day) return
        states.clear()
        loadedDay = day
        try {
            val raw = Cfg.str(STATE_KEY, "")
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            if (root.optString("d", "") != day) return
            val m = root.optJSONObject("m") ?: return
            val keys = m.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = m.optJSONObject(id) ?: continue
                states[id] = ModelState(o.optInt("d", 0), o.optLong("mb", 0L), o.optInt("mc", 0), o.optLong("cool", 0L))
            }
        } catch (_: Throwable) {
            states.clear()
        }
    }

    private fun saveLocked() {
        try {
            val m = JSONObject()
            for ((id, s) in states) {
                m.put(id, JSONObject().put("d", s.daily).put("mb", s.minBucket).put("mc", s.minCount).put("cool", s.coolUntil))
            }
            Cfg.put(STATE_KEY, JSONObject().put("d", today()).put("m", m).toString())
        } catch (_: Throwable) {
        }
    }

    @Synchronized private fun stateOf(model: String): ModelState {
        loadLocked()
        return states.getOrPut(model) { ModelState() }
    }

    private fun dailyCap(model: String): Int = if (model.contains("lite")) LITE_DAILY_CAP else FLASH_DAILY_CAP

    private fun midnightPt(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * أنسب موديل للمحاولة الجاية: الجاهز الأول (أقل استهلاك يومي، ثم أقل استهلاك في الدقيقة)،
     * بعدين المهدّى (أقرب كول داون يخلص)، وآخر حاجة المستنفد يوميًا (بيتجرب برضه كملاذ أخير).
     */
    @Synchronized fun pickModel(tried: Set<String>): String? {
        loadLocked()
        val now = System.currentTimeMillis()
        val ready = ArrayList<String>()
        val throttled = ArrayList<String>()
        val exhausted = ArrayList<String>()
        for (model in CHAIN) {
            if (model in tried) continue
            val s = stateOf(model)
            if (s.daily >= dailyCap(model)) {
                exhausted.add(model)
            } else if (s.coolUntil > now) {
                throttled.add(model)
            } else {
                ready.add(model)
            }
        }
        ready.sortWith(compareBy({ stateOf(it).daily }, { stateOf(it).minCount }))
        throttled.sortBy { stateOf(it).coolUntil }
        exhausted.sortBy { stateOf(it).daily }
        return (ready + throttled + exhausted).firstOrNull()
    }

    /** طلب بيتحسب بس لما أول توكن يوصل. */
    @Synchronized fun noteFirstToken(model: String) {
        val s = stateOf(model)
        val bucket = System.currentTimeMillis() / 60000L
        if (s.minBucket != bucket) {
            s.minBucket = bucket
            s.minCount = 0
        }
        s.daily++
        s.minCount++
        saveLocked()
    }

    /** كول داون حسب سبب الفشل — 429: retryDelay لو موجود وإلا 60ث (أو لحد نص الليل لو الكوتة اليومية خلصت) · 5xx/حمل: 3 ساعات · أقصى حد 6 ساعات. */
    @Synchronized fun noteFailure(model: String, e: ApiErr) {
        val now = System.currentTimeMillis()
        val raw = e.message ?: ""
        val dur: Long = when {
            e.code == 429 && (raw.contains("quota", true) || raw.contains("daily", true) || raw.contains("exhaust", true)) -> midnightPt() - now
            e.code == 429 -> {
                val m = Regex("retryDelay[^0-9]*([0-9]+(?:\\.[0-9]+)?)").find(raw)
                val sec = m?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (sec != null) (sec * 1000).toLong() else 60000L
            }
            e.code >= 500 || raw.contains("overload", true) -> OVERLOAD_COOLDOWN_MS
            else -> return
        }
        val s = stateOf(model)
        s.coolUntil = now + minOf(dur.coerceAtLeast(1000L), MAX_COOLDOWN_MS)
        saveLocked()
    }

    /** توقف الستريم (stall) بيهدّي الموديل 60 ثانية وبيتجرّب اللي بعده. */
    @Synchronized fun noteStall(model: String) {
        val s = stateOf(model)
        s.coolUntil = System.currentTimeMillis() + STALL_COOLDOWN_MS
        saveLocked()
    }

    /** طول الجزء من الإعدادات: "300" أو "600" — أي قيمة تانية بترجع للافتراضي 300. */
    fun chunkSec(): Double = when (Cfg.str(CHUNK_MIN_KEY, "300").trim()) {
        "600" -> 600.0
        else -> CHUNK_SEC_WARID
    }

    /**
     * حجم الجزء الفعّال للدوكيومنت ده + حراسة الاستكمال:
     * دوكيومنت جديد (مفيش تقدّم) بياخد قيمة الإعدادات؛ دوكيومنت فيه تقدّم بحجم وارد (300/600) بيكمّل بنفس الحجم.
     * دوكيومنت فيه تقدّم بحجم تاني (60 عادي، أو إعدادات اتغيّرت) — التفريغ القديم بيتمسح الأول،
     * لأن done/failed/Seg.chunk متخزنين على حجم الجزء القديم واستكمالهم على حجم جديد يبوّظ التوقيت.
     */
    fun ensureChunkSec(d: Lecture.Doc): Double {
        val want = chunkSec()
        val hasProgress = synchronized(d) { d.done.isNotEmpty() || d.failed.isNotEmpty() || d.segs.isNotEmpty() }
        if (!hasProgress) {
            synchronized(d) { d.chunkSec = want }
            return want
        }
        val cur = synchronized(d) { d.chunkSec }
        if (cur == 300.0 || cur == 600.0) return cur
        synchronized(d) {
            d.done.clear()
            d.failed.clear()
            d.segs.clear()
            d.chunkSec = want
        }
        LogStore.add("🌐 [وارد] حجم الجزء اتغيّر — اتمسح التفريغ القديم وهنبدأ من الأول")
        return want
    }

    /** شغّل وضع وارد على الدوكيومنت واحفظه (الواجهة بتناديها قبل Lecture.start — أو startWarid بتعمل الاتنين). */
    fun enableWarid(ctx: Context, d: Lecture.Doc): Lecture.Doc {
        synchronized(d) { d.warid = true }
        Lecture.save(ctx, d)
        return d
    }

    /** بداية تفريغ وارد: فعّل الوضع وابدا Lecture.start (نفس المسار العادي — الفرق جوه doChunk). */
    fun startWarid(ctx: Context, d: Lecture.Doc): Lecture.Run? {
        enableWarid(ctx, d)
        return Lecture.start(ctx, d)
    }

    /**
     * تفريغ وارد بعد التفريغ ← ترجمة SRT بمحرك الترجمة الحالي.
     * لغة المصدر للترجمة = d.langCode اللي وارد حددها (لو فاضية، المحرك بيكتشفها لوحده زي الأول).
     * لازم تتنده من خيط خلفي.
     */
    fun translateWaridDoc(ctx: Context, d: Lecture.Doc, report: (String) -> Unit, cancel: () -> Boolean = { false }, eng: String? = null): Studio.SrtResult {
        val src = synchronized(d) { d.langCode.ifBlank { null } }
        return Studio.translateToSrt(ctx, d, report, cancel, eng, src)
    }

    fun uploadAllowanceMs(audioBytes: Int): Long {
        val b64 = WaridFiles.base64Len(audioBytes)
        return b64 * 1000L / UPLOAD_FLOOR_BPS
    }

    /**
     * حارس الستريم: أول توكن خلال (وقت الرفع + 45ث)، وبعدها فجوة أكتر من 25ث بين توكن والتاني = stall.
     * الإجهاض بيقطع الاتصال نفسه (disconnect) عشان readLine المحظور يصحى فورًا — مش بيستنى مهلة القراءة.
     */
    private class Watchdog(audioBytes: Int) {
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var cancelled = false
        @Volatile private var firstAt = 0L
        @Volatile private var lastAt = 0L
        private val startAt = System.currentTimeMillis()
        private val firstDeadline = startAt + uploadAllowanceMs(audioBytes) + FIRST_TOKEN_BASE_MS
        @Volatile private var thread: Thread? = null

        fun onToken() {
            val n = System.currentTimeMillis()
            if (firstAt == 0L) firstAt = n
            lastAt = n
        }

        fun start() {
            thread = Thread {
                try {
                    while (!cancelled) {
                        val now = System.currentTimeMillis()
                        if (firstAt == 0L && now > firstDeadline) {
                            abort()
                        } else if (firstAt > 0L && now - lastAt > STALL_GAP_MS) {
                            abort()
                        }
                        if (cancelled) break
                        try {
                            Thread.sleep(500L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; start() }
        }

        fun stop() {
            cancelled = true
            try {
                thread?.interrupt()
            } catch (_: Throwable) {
            }
        }

        private fun abort() {
            cancelled = true
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * تفريغ جزء واحد بطريقة وارد. بيرجّع true لو نجح (حتى لو الجزء فاضي)، false لو فشل بعد المحاولات.
     * المحاولات: لحد 4 — كل محاولة موديل مختلف من السلسلة (pickModel)، ومفتاح مختلف بالتناوب زي الوضع العادي.
     * القطع ≥ 5MB base64 بتترفع Files API الأول وبتتمسح في finally بعد الـ generateContent.
     */
    // (v1001) offset: إزاحة المراجع الملزوقة قبل المقطع (CallKit.mix) — أي عنصر بيبدأ قبلها هو المرجع نفسه وبيتخطى، والباقي بتتطرح منه.
    // (G1) accurate: إعادة تفريغ جملة — الـlite بيتستبعد من المحاولات، فالسلسلة بتبدأ من أول موديل Flash مش lite.
    fun transcribeChunk(run: Lecture.Run, keys: List<String>, wav: WavChunk, idx: Int, worker: Int, chunkSec: Double, chunkDur: Double, prompt: String, offset: Double = 0.0, accurate: Boolean = false, forceModel: String = ""): Boolean {
        val d = run.doc
        val st = idx * chunkSec
        val prevThink = Api.thinkBudget0.get()
        val prevTimeout = Api.readTimeoutOverride.get()
        val prevLossless = AudioEnc.lossless.get()
        Api.thinkBudget0.set(true)
        Api.readTimeoutOverride.set(READ_TIMEOUT_MS)
        AudioEnc.lossless.set(true)
        try {
            val tried = HashSet<String>()
            // (G1) إعادة التفريغ بموديل أقوى: الـlite مش في المحاولات أصلًا — أول pickModel هيبقى Flash.
            if (accurate && forceModel.isBlank()) tried.addAll(CHAIN.filter { it.contains("lite") })
            for (a in 0 until 4) {
                if (run.stop) return false
                // (v1011) forceModel: نفس الموديل في كل المحاولات (بمفاتيح بتتبدّل) — مفيش اختيار من السلسلة ومفيش بديل صامت
                val model = if (forceModel.isNotBlank()) forceModel else (pickModel(tried) ?: break)
                tried.add(model)
                val key = keys[(idx + worker + a) % keys.size]
                var uploaded: WaridFiles.Uploaded? = null
                var fileUri: String? = null
                try {
                    if (WaridFiles.needsFiles(wav.bytes.size)) {
                        uploaded = WaridFiles.upload(key, wav.bytes, "audio/wav", "warid-chunk-$idx.wav")
                        fileUri = uploaded.uri
                    }
                    val wd = Watchdog(wav.bytes.size)
                    val cb = Api.StreamCb(
                        onText = { wd.onToken() },
                        onFirstToken = { wd.onToken(); noteFirstToken(model) },
                        cancel = { wd.cancelled },
                        onConn = { c -> wd.conn = c }
                    )
                    wd.start()
                    val r = try {
                        Api.generateStream(model, key, prompt, wav.bytes, fileUri, Api.outTokens(chunkDur), 0.1, true, cb)
                    } finally {
                        wd.stop()
                    }
                    val j = Lecture.parseJson(r.text)
                    if (j == null) {
                        if (r.text.isBlank() && r.finish.startsWith("PROMPT_")) return true
                        continue
                    }
                    val arr = j.optJSONArray("segments") ?: JSONArray()
                    val got = ArrayList<Lecture.Seg>()
                    for (i in 0 until arr.length()) {
                        val x = arr.optJSONObject(i) ?: continue
                        val t = x.optString("text").trim()
                        if (t.isEmpty()) continue
                        val s0 = (x.optDouble("start", 0.0)).coerceIn(0.0, chunkDur)
                        val e0 = (x.optDouble("end", s0)).coerceIn(s0, chunkDur)
                        if (s0 < offset) continue
                        got.add(Lecture.Seg(st + s0 - offset, st + e0 - offset, t, idx, Lecture.spkOf(x.optString("spk")), Lecture.normLangCode(x.optString("lang")), Lecture.toneOf(x.optString("tone")), Lecture.confOf(x.optDouble("conf", -1.0))))
                    }
                    synchronized(d) {
                        d.segs.removeAll { it.chunk == idx }
                        d.segs.addAll(got)
                        // (v1000) لغة كل جملة من رد وارد زي المسار العادي بالظبط، وعدّاد اللغات بيحدّث
                        // langCode/lang من الغالبية الجارية — مش أول جزء ناجح بس.
                        Lecture.recordLang(d, Lecture.normLangCode(j.optString("langCode")), j.optString("lang"))
                    }
                    return true
                } catch (e: NoKey) {
                    run.error = e.message ?: "مفيش مفتاح"
                    run.stop = true
                    return false
                } catch (e: WaridStall) {
                    noteStall(model)
                    LogStore.add("🌐 [وارد] $model وقف الستريم (stall) — نص جزئي ${e.partial.length} حرف، بنجرّب اللي بعده")
                    continue
                } catch (e: ApiErr) {
                    if (e.code == 502 && e.raw.startsWith("جوجل رجّع صفحة HTML")) {
                        run.error = e.raw.take(200)
                        run.stop = true
                        return false
                    }
                    noteFailure(model, e)
                    if (e.code == 429 || e.code >= 500) {
                        try {
                            Thread.sleep(1500L * (a + 1))
                        } catch (_: InterruptedException) {
                        }
                    } else if (e.code != 400 && e.code != 403) {
                        run.error = (e.message ?: "").take(120)
                        return false
                    }
                } catch (e: Throwable) {
                    LogStore.err("WaridTx:chunk", e)
                    try {
                        Thread.sleep(800)
                    } catch (_: InterruptedException) {
                    }
                } finally {
                    if (uploaded != null) WaridFiles.delete(key, uploaded.uri)
                }
            }
            // (v1000) كل الموديلات الأربعة فشلت للجزء ده — مفيش رجوع تلقائي للطريقة العادية أبدًا؛
            // الجزء بيتعاد من «أعد الناقص» بس.
            LogStore.add("🌐 [وارد] الجزء " + (idx + 1) + " فشل بعد كل الموديلات — هيتعاد من «أعد الناقص»، ومفيش رجوع تلقائي للطريقة العادية")
            return false
        } finally {
            Api.thinkBudget0.set(prevThink)
            Api.readTimeoutOverride.set(prevTimeout)
            AudioEnc.lossless.set(prevLossless)
        }
    }
}
