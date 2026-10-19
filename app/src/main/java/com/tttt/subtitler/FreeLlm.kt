package com.tttt.subtitler

import java.util.concurrent.ConcurrentHashMap

/**
 * (v440) 🆓 FreeLLM — نفس فكرة مشروع FreeLLM بس جوه التطبيق (من غير سيرفر ولا دوكر):
 * بيوزّع الطلبات على الموديلات المجانية (Gemini / Gemma / Groq) بمفاتيحك، وبيتابع حد الطلبات في الدقيقة (RPM)
 * وفي اليوم (RPD) والتوكنز في الدقيقة (TPM)، وكل طلب بيروح للموديل الأبعد عن حده. لو كلهم وصلوا للحد بيستنى لأقرب واحد.
 * اختياري: لو اتكتب رابط سيرفر FreeLLM بتاعك (مثلًا http://192.168.1.5:3000/v1) الطلب بيروح له والسيرفر هو اللي يوزّع.
 * الاستخدام: وضع «وشبر + FreeLLM» — تفريغ Whisper على الجهاز وترجمة النص بالموديلات دي (مفيش جيميناي خالص).
 */
object FreeLlm {
    const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val GROQ_URL = "https://api.groq.com/openai/v1"

    /** موديل مجاني وحدوده (TPM = 0 يعني مفيش حد توكنز) — الأرقام من defaults (ملفات yml) في المشروع */
    class M(val provider: String, val name: String, val tpm: Int, val rpm: Int, val rpd: Int)

    val DEFAULTS: List<M> = listOf(
        M("gemini", "gemini-3.1-flash-lite", 250000, 15, 500),
        M("groq", "llama-3.1-8b-instant", 6000, 30, 14400),
        M("groq", "llama-3.3-70b-versatile", 12000, 30, 1000),
        M("gemini", "gemini-2.5-flash-lite", 250000, 10, 20),
        M("gemini", "gemini-2.5-flash", 250000, 5, 20),
        M("gemini", "gemini-3.5-flash", 250000, 5, 20)
    )

    // ===== إعدادات =====
    /** (v442) مفتاح Gemini بتاع FreeLLM؛ لو فاضي بياخد أول مفتاح جيميناي مضاف في التطبيق (مفاتيحك) من غير ما تكتبه تاني */
    fun gemKey(): String {
        val k = Cfg.str("fl_gem_key").trim()
        if (k.length > 8) return k
        return try { (Cfg.keys("keys") + Cfg.keys("extra") + Cfg.keys("backup")).firstOrNull() ?: "" } catch (_: Throwable) { "" }
    }
    fun groqKey(): String = Cfg.str("fl_groq_key").trim()
    fun serverUrl(): String = Cfg.str("fl_url").trim().trimEnd('/')
    fun serverKey(): String = Cfg.str("fl_srv_key").trim()
    /** اسم الموديل أو الجروب عند السيرفر (flash / large) — الافتراضي large */
    fun serverModel(): String = Cfg.str("fl_srv_model").trim().ifEmpty { "large" }
    fun ready(): Boolean = serverUrl().isNotEmpty() || gemKey().length > 8 || groqKey().length > 8
    private fun keyOf(m: M): String = if (m.provider == "gemini") gemKey() else groqKey()
    private fun hasKey(m: M): Boolean = keyOf(m).length > 8
    /** (v440) موديلات بتسمع الصوت: جيميناي فلاش/لايت بس (Gemma وllama نص بس) */
    private fun hearsAudio(m: M): Boolean = m.provider == "gemini" && m.name.startsWith("gemini")
    const val KEY = "FREELLM-ROUTER"   // مفتاح وهمي بيدخل مسار جيميناي في المحرك وApi.generate بيحوّله هنا
    fun isKey(k: String): Boolean = k == KEY
    /** الوضع الصوتي محتاج مفتاح Gemini (أو سيرفر) */
    fun audioReady(): Boolean = serverUrl().isNotEmpty() || gemKey().length > 8 || (try { Cfg.allMainKeys().isNotEmpty() } catch (_: Throwable) { false })
    private fun baseOf(m: M): String = if (m.provider == "gemini") GEMINI_URL else GROQ_URL
    private fun endpoint(base0: String): String {
        val base = base0.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    // ===== حالة الموزّع =====
    private val hits = ConcurrentHashMap<String, java.util.ArrayDeque<Pair<Long, Int>>>()   // وقت الطلب + تقدير التوكنز (آخر دقيقة)
    private val cool = ConcurrentHashMap<String, Long>()
    private val why = ConcurrentHashMap<String, String>()
    /** الموديل اللي رد آخر مرة (للعرض) */
    @Volatile var live = ""
    /** true = كل الموديلات وصلت لحدها دلوقتي والموزّع مستني (النقطة بتبقى صفرا) */
    @Volatile var waiting = false
    fun liveName(): String = if (live.isEmpty()) (if (serverUrl().isNotEmpty()) "سيرفر · " + serverModel() else "تلقائي") else live

    private fun day(): String = (System.currentTimeMillis() / 86_400_000L).toString()
    private fun used(m: M, kid: String = ""): Int {
        val p = Cfg.str("fl_use_" + m.name + kid).split(":")
        return if (p.size == 2 && p[0] == day()) (p[1].toIntOrNull() ?: 0) else 0
    }
    private fun addUsed(m: M, kid: String = "") { Cfg.put("fl_use_" + m.name + kid, day() + ":" + (used(m, kid) + 1)) }

    fun resetAll() { hits.clear(); cool.clear(); why.clear(); live = ""; waiting = false }

    /** يختار الموديل الأبعد عن حده (زي سيرفر FreeLLM). لو مفيش: (null، الانتظار بالمللي ثانية لأقرب واحد · -1 = مفيش أمل النهارده) */
    @Synchronized private fun pick(est: Int, audio: Boolean, kid: String = ""): Pair<M?, Long> {
        val now = System.currentTimeMillis()
        var best: M? = null; var bestU = Double.MAX_VALUE; var soonest = Long.MAX_VALUE
        for (m in DEFAULTS) {
            // (v443) kid = مفتاح جيميناي بعينه: يلف على موديلات جيميناي/جيما بس بالمفتاح ده، وكل (مفتاح × موديل) له عدّاده وراحته
            if (kid.isEmpty()) { if (!hasKey(m)) continue } else if (m.provider != "gemini") continue
            if (audio && !hearsAudio(m)) continue
            val id = m.name + kid
            val c = maxOf(cool[id] ?: 0L, cool[m.name + "#all"] ?: 0L)
            if (c > now) { soonest = minOf(soonest, c - now); continue }
            val dq = hits.getOrPut(id) { java.util.ArrayDeque() }
            while (dq.isNotEmpty() && now - dq.peekFirst()!!.first > 60_000L) dq.removeFirst()
            val n = dq.size
            val tk = dq.fold(0) { a, p -> a + p.second }
            if (n >= m.rpm) { soonest = minOf(soonest, 60_000L - (now - dq.peekFirst()!!.first)); continue }
            if (m.tpm > 0 && n > 0 && tk + est > m.tpm) { soonest = minOf(soonest, 60_000L - (now - dq.peekFirst()!!.first)); continue }
            val u = used(m, kid)
            if (m.rpd > 0 && u >= m.rpd) continue
            var util = n.toDouble() / m.rpm
            if (m.rpd > 0) util = maxOf(util, u.toDouble() / m.rpd)
            if (m.tpm > 0) util = maxOf(util, (tk + est).toDouble() / m.tpm)
            if (util < bestU) { bestU = util; best = m }
        }
        val b = best
        if (b != null) {
            hits.getOrPut(b.name + kid) { java.util.ArrayDeque() }.addLast(Pair(now, est))
            addUsed(b, kid)
            return Pair(b, 0L)
        }
        return Pair(null, if (soonest == Long.MAX_VALUE) -1L else soonest)
    }

    private fun coolModel(m: M, ms: Long, reason: String) { cool[m.name] = System.currentTimeMillis() + ms; why[m.name] = reason }
    private fun coolProvider(p: String, ms: Long, reason: String) { for (m in DEFAULTS) if (m.provider == p) coolModel(m, ms, reason) }

    /** ينفّذ طلب على أنسب موديل. 429 → الموديل ده يستريح ويروح للي بعده · كلهم واقفين → يستنى (لحد 10 دقايق) */
    private fun <T> run(est: Int, audio: Boolean, call: (M) -> T): T {
        val t0 = System.currentTimeMillis()
        var lastErr: Exception? = null
        var attempts = 0
        try {
            while (true) {
                val pr = pick(est, audio)
                val m = pr.first
                if (m == null) {
                    val wait = pr.second
                    if (wait < 0L || System.currentTimeMillis() - t0 + wait > 600_000L) {
                        throw lastErr ?: ExtLlm.LlmErr(429, "FreeLLM: كل الموديلات المجانية وصلت لحدها دلوقتي", 0)
                    }
                    waiting = true
                    LogStore.add("🆓 FreeLLM: كل الموديلات في استراحة — بستنى " + ((wait + 300L) / 1000L) + " ث وبكمّل")
                    try { Thread.sleep(wait + 300L) } catch (_: InterruptedException) { throw lastErr ?: ExtLlm.LlmErr(429, "FreeLLM: اتقاطع الانتظار", 0) }
                    continue
                }
                waiting = false
                try {
                    val r = call(m)
                    live = m.name
                    return r
                } catch (e: ExtLlm.LlmErr) {
                    lastErr = e
                    val msg = (e.message ?: "").lowercase()
                    when (e.code) {
                        401, 403 -> coolProvider(m.provider, 1_800_000L, "المفتاح مرفوض")
                        404 -> coolModel(m, 1_800_000L, "الموديل مش موجود")
                        429 -> {
                            val perDay = msg.contains("per day") || msg.contains("daily") || msg.contains("rpd")
                            val ms = if (e.retryAfterSec > 0) e.retryAfterSec.coerceIn(10, 3600) * 1000L else if (perDay) 3_600_000L else 62_000L
                            coolModel(m, ms, "الحد خلص مؤقتًا")
                        }
                        else -> coolModel(m, 30_000L, "خطأ " + e.code)
                    }
                    LogStore.add("🆓 FreeLLM: " + m.name + " ← " + (e.message ?: "").take(100) + " — بجرّب اللي بعده")
                } catch (e: Exception) {
                    lastErr = e
                    coolModel(m, 20_000L, "رد غلط")
                    LogStore.add("🆓 FreeLLM: " + m.name + " ← " + (e.message ?: e.javaClass.simpleName).take(100) + " — بجرّب اللي بعده")
                }
                if (++attempts >= 14) throw lastErr ?: java.io.IOException("FreeLLM: فشل")
            }
        } finally { waiting = false }
    }

    // ===== (v443) 🔁 جيميناي بقى FreeLLM: كل مفتاح من مفاتيحك بيلف على كل موديلات جيميناي =====
    private const val PFX = "FLG~"
    /** لفّة المفتاح: المحرك بيشوفه مفتاح عادي (التوزيع ٧٥/٢٥ والاحتياطي زي ما هو) وApi.generate بيحوّله هنا */
    fun wrap(k: String): String = if (k.length > 10 && k != LocalKey.ID && !k.startsWith(PFX) && !isKey(k)) PFX + k else k
    fun isWrapped(k: String): Boolean = k.startsWith(PFX)
    /** true = اللف شغّال (الافتراضي)؛ fl_direct=1 يرجّع جيميناي المباشر بموديل واحد */
    fun rotating(): Boolean = Cfg.str("fl_direct").trim() != "1"
    private val kseq = java.util.concurrent.atomic.AtomicInteger(0)

    /** طلب بمفتاح واحد بيلف على موديلاته: الأبعد عن حده الأول، 429 → الموديل ده يستريح ويروح للي بعده؛ كلهم وقفوا → 429 للمحرك يحوّل لمفتاح تاني */
    fun generateKey(wrapped: String, prompt: String, wav: ByteArray?, maxTokens: Int, temp: Double, json: Boolean, search: Boolean): Api.Result {
        val key = wrapped.removePrefix(PFX)
        val kid = "@" + Integer.toHexString(key.hashCode())
        val tail = "…" + key.takeLast(4)
        val n = kseq.incrementAndGet()
        val est = (wav?.size ?: 0) / 32 + prompt.length / 3 + 1000
        var lastErr: Exception? = null
        var attempts = 0
        while (true) {
            val pr = pick(est, wav != null || search, kid)
            val m = pr.first
            if (m == null) {
                val w = pr.second
                if (DEFAULTS.any { why[it.name + kid] == "المفتاح مرفوض" }) throw ApiErr(401, "FreeLLM: مفتاح " + tail + " مرفوض (API key not valid)")
                throw lastErr as? ApiErr ?: ApiErr(429, "FreeLLM: كل موديلات المفتاح " + tail + " وصلت لحدها" + (if (w > 0) " — أقرب موديل بعد " + (w / 1000L) + " ث" else " النهارده"))
            }
            val t0 = System.currentTimeMillis()
            LogStore.add("🔁 [FreeLLM #" + n + "] طلب ← " + m.name + " · مفتاح " + tail + " · " + (if (wav != null) "صوت " + (wav.size / 1024) + "KB" else "نص " + prompt.length + "حرف"))
            try {
                val r = if (m.name.startsWith("gemini")) Api.generateModel(m.name, key, prompt, wav, maxTokens, temp, json, search)
                        else ExtTranslate.llmChat(endpoint(GEMINI_URL), key, m.name, prompt, wav, maxTokens, temp, json)
                live = m.name + " " + tail
                LogStore.add("🔁 [FreeLLM #" + n + "] ✅ " + m.name + " · رد " + r.text.length + "حرف في " + (System.currentTimeMillis() - t0) + "ms")
                return r
            } catch (e: ApiErr) {
                lastErr = e; rotateFail(m, kid, e.code, e.raw, null)
                LogStore.add("🔁 [FreeLLM #" + n + "] ❌ " + m.name + " ← HTTP " + e.code + " " + e.raw.take(80) + " — بجرّب موديل تاني")
                if (e.code == 401 || e.code == 403) throw e
            } catch (e: ExtLlm.LlmErr) {
                lastErr = e; rotateFail(m, kid, e.code, e.message ?: "", e.retryAfterSec)
                LogStore.add("🔁 [FreeLLM #" + n + "] ❌ " + m.name + " ← " + (e.message ?: "").take(80) + " — بجرّب موديل تاني")
                if (e.code == 401 || e.code == 403) throw ApiErr(e.code, e.message ?: "")
            } catch (e: java.io.IOException) {
                lastErr = e; cool[m.name + kid] = System.currentTimeMillis() + 15_000L; why[m.name + kid] = "شبكة"
                LogStore.add("🔁 [FreeLLM #" + n + "] ❌ " + m.name + " ← " + (e.message ?: e.javaClass.simpleName).take(80) + " — بجرّب موديل تاني")
            }
            if (++attempts >= 8) throw lastErr ?: java.io.IOException("FreeLLM: فشل")
        }
    }

    private fun rotateFail(m: M, kid: String, code: Int, msg0: String, retry: Int?) {
        val msg = msg0.lowercase()
        val id = m.name + kid
        val now = System.currentTimeMillis()
        when {
            code == 404 && msg.contains("no longer available") -> { cool[id] = now + 1_800_000L; why[id] = "الموديل مقفول على المفتاح ده" }
            code == 404 -> { cool[m.name + "#all"] = now + 1_800_000L; why[m.name + "#all"] = "الموديل مش موجود" }
            code == 401 || code == 403 || msg.contains("api key not valid") -> { for (x in DEFAULTS) { cool[x.name + kid] = now + 1_800_000L; why[x.name + kid] = "المفتاح مرفوض" } }
            code == 429 -> {
                val perDay = msg.contains("per day") || msg.contains("daily") || msg.contains("rpd")
                val ms = if ((retry ?: 0) > 0) retry!!.coerceIn(10, 3600) * 1000L else if (perDay) 3_600_000L else 62_000L
                cool[id] = now + ms; why[id] = "الحد خلص مؤقتًا"
            }
            else -> { cool[id] = now + 30_000L; why[id] = "خطأ " + code }
        }
    }

    /** ترجمة دفعة جمل (باللهجة والأسلوب من الإعدادات) */
    fun translate(texts: List<String>, sl: String): List<String?> {
        val est = texts.sumOf { it.length } / 2 + 700
        val srv = serverUrl()
        if (srv.isNotEmpty()) {   // سيرفر FreeLLM بتاعك هو اللي بيوزّع
            var tries = 0
            while (true) {
                try { return ExtTranslate.llmCall(endpoint(srv), serverKey(), serverModel(), texts, sl) }
                catch (e: ExtLlm.LlmErr) {
                    if (e.code != 429 || ++tries >= 5) throw e
                    waiting = true
                    try { Thread.sleep((if (e.retryAfterSec > 0) e.retryAfterSec.coerceIn(5, 90) else 20) * 1000L) } catch (_: InterruptedException) { throw e }
                    finally { waiting = false }
                }
            }
        }
        return run(est, false) { m -> ExtTranslate.llmCall(endpoint(baseOf(m)), keyOf(m), m.name, texts, sl) }
    }

    private val seq = java.util.concurrent.atomic.AtomicInteger(0)

    /** (v440) 🆓 الوضع الصوتي: نفس طلب جيميناي (صوت + برومبت) بس على موديلات Gemini المجانية بالتوزيع حسب الحد — Api.generate بيحوّل هنا لما المفتاح = KEY */
    fun generate(prompt: String, wav: ByteArray?, maxTokens: Int, temp: Double, json: Boolean): Api.Result {
        val n = seq.incrementAndGet()
        val t0 = System.currentTimeMillis()
        LogStore.add("🆓 [FreeLLM #" + n + "] طلب ← " + liveName() + " · " + (if (wav != null) "صوت " + (wav.size / 1024) + "KB" else "نص " + prompt.length + "حرف"))
        try {
            val r: Api.Result
            val srv = serverUrl()
            if (srv.isNotEmpty()) r = ExtTranslate.llmChat(endpoint(srv), serverKey(), serverModel(), prompt, wav, maxTokens, temp, json)
            else {
                if (wav != null && gemKey().length <= 8) throw ExtLlm.LlmErr(401, "الوضع الصوتي محتاج مفتاح Gemini مجاني (موديلات Groq/Gemma مابتسمعش)", 0)
                val est = (wav?.size ?: 0) / 32 + prompt.length / 3 + 1000   // تقدير توكنز: الصوت ~ 32 بايت للتوكن تقريبًا
                r = run(est, wav != null) { m -> ExtTranslate.llmChat(endpoint(baseOf(m)), keyOf(m), m.name, prompt, wav, maxTokens, temp, json) }
            }
            LogStore.add("🆓 [FreeLLM #" + n + "] ✅ " + liveName() + " · رد " + r.text.length + "حرف في " + (System.currentTimeMillis() - t0) + "ms")
            return r
        } catch (e: ExtLlm.LlmErr) {
            LogStore.add("🆓 [FreeLLM #" + n + "] ❌ " + (e.message ?: "").take(120))
            throw ApiErr(e.code, e.message ?: ("HTTP " + e.code))
        } catch (e: java.io.IOException) {
            LogStore.add("🆓 [FreeLLM #" + n + "] ❌ " + (e.message ?: e.javaClass.simpleName).take(120))
            throw e
        }
    }

    /** المزوّد اللي بيدخل سلسلة الترجمة (id = free) — null لو مفيش مفتاح ولا سيرفر */
    fun prov(): ExtTranslate.Prov? {
        if (!ready()) return null
        return ExtTranslate.Prov("free", "FreeLLM · " + liveName(), listOf("free"), 0) { _, _ -> null }.also { pv ->
            pv.keyHash = { (gemKey() + groqKey() + serverUrl() + serverKey()).hashCode() }
            pv.chunk = 30; pv.gapMs = 0L
            pv.batch = { texts, sl -> translate(texts, sl) }
        }
    }

    /** تجربة بجملة واحدة: (نجح؟، ms، الترجمة أو السبب) */
    fun test(): Triple<Boolean, Long, String> {
        val t0 = System.currentTimeMillis()
        return try {
            val r = translate(listOf("Hello, how are you today?"), "en").firstOrNull()
            val ms = System.currentTimeMillis() - t0
            if (r.isNullOrBlank()) Triple(false, ms, "ما رجّعش ترجمة") else Triple(true, ms, "[" + liveName() + "] " + r)
        } catch (e: Exception) { Triple(false, System.currentTimeMillis() - t0, (e.message ?: e.javaClass.simpleName).take(140)) }
    }

    /** سطر لكل موديل: الاستخدام النهارده وحالته */
    /** (v444) حالة اللف: لكل موديل جيميناي كام مفتاح شغّال منهم دلوقتي وكام طلب اتبعت النهارده */
    fun rotationText(): String {
        val keys = try { Cfg.allMainKeys() } catch (_: Throwable) { emptyList() }
        if (keys.isEmpty()) return "مفيش مفاتيح جيميناي — ضيف مفتاح من الإعدادات ← 🔑 المفاتيح"
        val now = System.currentTimeMillis()
        val sb = StringBuilder().append(keys.size).append(" مفتاح × ").append(DEFAULTS.count { it.provider == "gemini" }).append(" موديل\n")
        for (m in DEFAULTS) {
            if (m.provider != "gemini") continue
            var ok = 0; var tot = 0
            for (k in keys) {
                val kid = "@" + Integer.toHexString(k.hashCode())
                tot += used(m, kid)
                val c = maxOf(cool[m.name + kid] ?: 0L, cool[m.name + "#all"] ?: 0L)
                if (c <= now && !(m.rpd > 0 && used(m, kid) >= m.rpd)) ok++
            }
            sb.append(m.name).append(" · ").append(ok).append('/').append(keys.size).append(" مفتاح شغّال · ").append(tot).append(" طلب النهارده\n")
        }
        return sb.toString().trim()
    }

    fun statusText(): String {
        if (serverUrl().isNotEmpty()) return "السيرفر: " + serverUrl() + " · الجروب/الموديل: " + serverModel()
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        for (m in DEFAULTS) {
            if (!hasKey(m)) continue
            val c = cool[m.name] ?: 0L
            sb.append(m.name).append(" · ").append(used(m)).append('/').append(m.rpd)
            if (c > now) sb.append(" · ").append(why[m.name] ?: "واقف").append(" ").append((c - now) / 1000L).append("ث")
            sb.append('\n')
        }
        return sb.toString().trim().ifEmpty { "مفيش مفاتيح — ضيف مفتاح Gemini أو Groq" }
    }
}
