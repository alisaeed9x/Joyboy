package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * (v272) مزوّدين ترجمة مجانيين من غير مفتاح (من النوع اللي بيتذكر في قايمة public-apis) بيشتغلوا مع جوجل الداخلي في سلسلة واحدة:
 *  - «الداخلي أولًا» (الافتراضي): جوجل (gtCall) ← المزوّدين الخارجيين لو جوجل فشل في جمل.
 *  - «الريبو أولًا»: المزوّدين الخارجيين ← جوجل للي فضل.
 * بيتفعّل بس في مسار «تفريغ أوفلاين + ترجمة جوجل» (نفس مكان googleItems). كل مزوّد: جملة جملة، 4 طلبات بالتوازي،
 * وليه حد يومي للحروف (MyMemory ≈ 5000 حرف/يوم من غير إيميل) ولو الكوتة خلصت بيقف لباقي اليوم.
 */
object ExtTranslate {
    class Prov(val id: String, val label: String, val keys: List<String>, val dailyChars: Int, val call: (String, String) -> String?) {
        /** (v273) لو متحدد: المزوّد بيترجم دفعة (25 جملة) في طلب واحد بدل جملة جملة (محركات LLM) */
        var batch: ((List<String>, String) -> List<String?>)? = null
        /** (v274) بصمة المفتاح الحالي — لو المفتاح اتغيّر الإيقاف بسبب «مفتاح مرفوض» بيتفك لوحده */
        var keyHash: () -> Int = { 0 }
        /** (v416) حجم الدفعة لمحركات LLM (الافتراضي 25) + راحة بين الدفعات بالمللي ثانية (لتجنّب حد الطلبات في الثانية) */
        var chunk: Int = 25
        var gapMs: Long = 0L
    }
    /** (v274) ملاحظة غير خطأ من مزوّد (مثلًا: الموديل اتغيّر لوحده) — المحرك بيعرضها كإشعار وبيصفّرها */
    @Volatile var lastNote = ""
    /** آخر خطأ لمزوّد دفعات (للّوج والإشعار) — بيتصفّر مع كل run */
    @Volatile var lastError = ""
    private class QuotaOut : Exception("quota")

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    val builtin: List<Prov> = listOf(
        Prov("mymemory", "MyMemory", listOf("mymemory"), 4800) { t, sl -> myMemory(t, sl) },
        Prov("lingva", "Lingva (جوجل عبر وسيط)", listOf("lingva"), 0) { t, sl -> lingva(t, sl) },
        Prov("mlkit", "ML Kit (أوفلاين · فصحى)", listOf("mlkit"), 0) { _, _ -> null }.also { pv -> pv.batch = { texts, sl -> MlKitTr.translate(texts, sl) } }   // (v408)
    )

    /** المزوّدين المدمجين + الراوتر (لو فيه مفاتيح) + محركات LLM اللي مفتاحها مضبوط */
    fun all(): List<Prov> = builtin + listOfNotNull(libreProv(), FreeLlm.prov())

    private fun day(): String = (System.currentTimeMillis() / 86_400_000L).toString()

    // ===== إعدادات =====
    /** internal = جوجل الداخلي أولًا (افتراضي) · external = المزوّدين الخارجيين أولًا */
    fun externalFirst(): Boolean = Cfg.str("tx_order", "internal") == "external"
    fun strict(): Boolean = Cfg.str("ext_strict", "off") == "on"
    /** (v274) المحرك الأساسي اللي اخترته من «▶ ترجمة ← أونلاين» (id مزوّد) — بيتجرّب الأول مهما كان الترتيب. فاضي = مفيش */
    fun primaryId(): String = Cfg.str("ext_primary").trim()
    /** (v274) سلسلة الترجمة بالترتيب الفعلي (null = جوجل الداخلي). بعدها لو كلها فشلت الباتش بيتحوّل لجيميناي بمفاتيحك */
    fun chain(): List<Prov?> {
        val ext = active()
        // (v277) id المحفوظ لمحركات LLM هو id البريست بينما id المزوّد llm_<id> — قبل كده المحرك الأساسي ماكانش بيتلقى أبدًا؛ وكمان وضع «الريبو بس» ماكانش لازم يستبعد اللي اخترته بإيدك
        val pid = primaryId()
        val prim = if (pid.isEmpty()) null else all().firstOrNull { enabled(it) && (it.id == pid || it.id == "llm_$pid") }
        val rest = ext.filter { it.id != prim?.id }
        val steps = ArrayList<Prov?>()
        prim?.let { steps.add(it) }
        if (semiLibre()) {   // (v423) 🌗 وشبر + LibreTranslate بدل جوجل: ليبرا أول واحد بعد محركات الـLLM، وبعده MyMemory/Lingva/ML Kit — جوجل الداخلي مش في السلسلة خالص
            val lb = rest.firstOrNull { it.id == "libre" }
            lb?.let { steps.add(it) }
            steps.addAll(rest.filter { it.id != "libre" })
            return steps
        }
        if (externalFirst()) { steps.addAll(rest); steps.add(null) } else { steps.add(null); steps.addAll(rest) }
        return steps
    }
    /** (v278) اسم المحرك بوضوح: جوجل الداخلي / ChatGPT… + الموديل / مجاني من الريبو / سيرفرك */
    fun whoLabel(p: Prov?): String = when {
        p == null -> "🏠 جوجل الداخلي"
        p.id == "libre" -> if (Cfg.str("libre_url").isBlank()) "🌐 LibreTranslate" else "🌐 LibreTranslate (سيرفرك)"
        p.id == "free" -> "🆓 FreeLLM · " + FreeLlm.liveName()
        else -> "🌐 " + p.label + " (مجاني من الريبو)"
    }
    /** (v278) سطر «مين بيترجم دلوقتي» للإشعارات: السلسلة بالترتيب، أو جيميناي بعدد مفاتيحه */
    fun setupLine(): String = if (TrWizard.googleOn()) "المترجم: " + chain().joinToString(" ← ") { whoLabel(it) } + (if (Strict.noGemKeys()) "" else " ← 🔑 جيميناي (مفاتيحك)")
        else "المترجم: 🔑 جيميناي — " + (Cfg.keys("keys").size + Cfg.keys("extra").size) + " مفتاح"
    fun chainLabel(): String = chain().joinToString(" ← ") { it?.label ?: "جوجل" } + (if (Strict.noGemKeys()) "" else " ← جيميناي (مفاتيحك)")
    fun enabled(p: Prov): Boolean = Cfg.str("ext_on_" + p.id, "on") != "off"
    fun setEnabled(p: Prov, on: Boolean) { Cfg.put("ext_on_" + p.id, if (on) "on" else "off") }
    /** مزوّد موجود في القايمة المسحوبة من الريبو؟ */
    fun inRepo(p: Prov): Boolean = PublicApis.listed(p.keys) != null

    /** المزوّدين اللي هيتستخدموا فعلًا: مفعّلين، ولو «الريبو بس» شغّال لازم يكونوا في القايمة المسحوبة */
    fun active(): List<Prov> = all().filter { enabled(it) && (!strict() || inRepo(it) || it.id == "mlkit" || it.id == "free") }

    // ===== (v274) إيقاف مؤقت لمزوّد فشل: مفتاح مرفوض (لحد ما المفتاح يتغيّر) / كوتة أو رصيد (دقايق) / رابط أو موديل غلط / سيرفر واقع =====
    private val coolUntil = ConcurrentHashMap<String, Long>()
    private val coolWhy = ConcurrentHashMap<String, String>()
    private val badKey = ConcurrentHashMap<String, Int>()

    /** سبب إن المزوّد [id] واقف دلوقتي، أو null لو شغّال. [keyHash] لتمييز «المفتاح اتغيّر» */
    fun blockedWhy(id: String, keyHash: Int = 0): String? {
        val bk = badKey[id]
        if (bk != null) { if (bk == keyHash) return "المفتاح مرفوض — غيّره عشان يتجرّب تاني" else badKey.remove(id) }
        val u = coolUntil[id] ?: return null
        val left = u - System.currentTimeMillis()
        if (left <= 0L) { coolUntil.remove(id); coolWhy.remove(id); return null }
        return (coolWhy[id] ?: "واقف") + " — بيتجرّب تاني بعد " + ((left + 59_999L) / 60_000L) + " د"
    }
    /** (v369) إيقاف مؤقت لمفتاح [id] (مثلًا موديل أومني روت) لمدة [ms] */
    fun coolFor(id: String, ms: Long, why: String) { coolUntil[id] = System.currentTimeMillis() + ms; coolWhy[id] = why }
    fun clearBlock(id: String) { badKey.remove(id); coolUntil.remove(id); coolWhy.remove(id) }
    fun noteFail(id: String, keyHash: Int, e: ExtLlm.LlmErr) {
        val now = System.currentTimeMillis()
        fun cool(ms: Long, why: String) { coolUntil[id] = now + ms; coolWhy[id] = why }
        when (e.code) {
            401, 403 -> badKey[id] = keyHash
            402 -> cool(1_800_000L, "الرصيد خلص")
            429 -> cool((if (e.retryAfterSec > 0) e.retryAfterSec.coerceIn(30, 3600) else 600) * 1000L, "الكوتة/الحد خلص")
            404 -> cool(1_800_000L, "الرابط أو الموديل غلط")
            in 500..599 -> cool(60_000L, "الخدمة واقعة")
        }
    }

    // ===== LibreTranslate (سيرفرك أو سيرفر بمفتاح): POST {url}/translate بدفعة جمل =====
    /** (v423) 🌗 النص-أونلاين: «libre» (الافتراضي) = وشبر + LibreTranslate بدل جوجل · «google» = زي الأول */
    fun semiLibre(): Boolean = Strict.semi()   // (v425) ليبرا بس — جوجل اتشال من الوضع ده
    /** السيرفر الافتراضي لو ما حطيتش سيرفرك. السيرفرات العامة غالبًا بتطلب مفتاح أو بتحط حد — الجزء ده ما اتأكدتش منه، زرار 🧪 جرّب في الإعدادات هو الاختبار */
    const val LIBRE_DEFAULT = "https://libretranslate.com"
    fun libreUrl(): String = Cfg.str("libre_url").trim().trimEnd('/').ifEmpty { if (semiLibre()) LIBRE_DEFAULT else "" }
    fun libreProv(): Prov? {
        val u = libreUrl()
        if (u.isEmpty()) return null
        return Prov("libre", "LibreTranslate", listOf("libretranslate"), 0) { _, _ -> null }.also { pv ->
            pv.keyHash = { Cfg.str("libre_key").hashCode() }
            pv.chunk = 20; pv.gapMs = 400L   // (v423) سيرفر عام: دفعات أصغر وراحة بينها
            pv.batch = { texts, sl -> libre(u, texts, sl) }
        }
    }
    private fun libre(url: String, texts: List<String>, sl0: String): List<String?> {
        val sl = if (sl0 == "zh-CN") "zh" else sl0.ifEmpty { "auto" }
        val j = JSONObject().put("q", JSONArray(texts)).put("source", sl).put("target", "ar").put("format", "text")
        Cfg.str("libre_key").trim().takeIf { it.isNotEmpty() }?.let { j.put("api_key", it) }
        val o = JSONObject(ExtLlm.post("$url/translate", emptyMap(), j.toString()))
        val out = arrayOfNulls<String>(texts.size)
        val arr = o.optJSONArray("translatedText")
        if (arr != null) { for (i in 0 until minOf(arr.length(), texts.size)) out[i] = arr.optString(i, "").trim().takeIf { it.isNotEmpty() } }
        else if (texts.size == 1) out[0] = o.optString("translatedText", "").trim().takeIf { it.isNotEmpty() }
        return out.toList()
    }


    // ===== طلبات OpenAI-compatible (بيستخدمها FreeLLM): chat بصوت اختياري + ترجمة دفعة جمل =====
    /** طلب chat واحد لموديل واحد، بصوت اختياري (input_audio / aac دايمًا). لو رفض max_tokens الكبير بيجرّب 4096 */
    internal fun llmChat(url: String, key: String, model: String, prompt: String, wav: ByteArray?, maxTokens: Int, temp: Double, json: Boolean): Api.Result {
        val msgs = JSONArray()
        if (json) msgs.put(JSONObject().put("role", "system").put("content", "Reply with ONLY valid JSON. No markdown fences, no text before or after the JSON."))
        val user = JSONObject().put("role", "user")
        if (wav != null) {
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", java.util.Base64.getEncoder().encodeToString(wav)).put("format", "wav")))
            parts.put(JSONObject().put("type", "text").put("text", prompt))
            user.put("content", parts)
        } else user.put("content", prompt)
        msgs.put(user)
        fun go(mt: Int): String {
            val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", temp).put("max_tokens", mt).put("stream", false)
            return ExtLlm.post(url, mapOf("Authorization" to "Bearer $key"), body.toString(), 180000)
        }
        val raw = try { go(maxTokens) } catch (e: ExtLlm.LlmErr) {
            if (e.code == 400 && maxTokens > 4096 && (e.message ?: "").contains("token", true)) go(4096) else throw e
        }
        val ch = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0) ?: throw java.io.IOException("رد " + model + " من غير choices")
        val cm = ch.optJSONObject("message")?.opt("content")
        var text = when (cm) {
            is String -> cm
            is JSONArray -> { val sb = StringBuilder(); for (i in 0 until cm.length()) sb.append(cm.optJSONObject(i)?.optString("text", "") ?: ""); sb.toString() }
            else -> ""
        }
        text = text.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val fin = when (ch.optString("finish_reason", "")) { "length" -> "MAX_TOKENS"; "stop", "" -> "STOP"; else -> ch.optString("finish_reason") }
        return Api.Result(text, fin)
    }

    /** تعليمات اللهجة والأسلوب والشدة من الإعدادات للمترجمين اللي بيترجموا بالـ LLM مباشرة */
    private fun llmTarget(): String {
        val d = try { TrWizard.dialectWanted() } catch (_: Throwable) { "" }
        val style = Cfg.str("tone_style", "").ifBlank { "حرفي" }
        val deg = Cfg.str("tone_strength", "متوسطة")
        val sb = StringBuilder()
        if (d.isBlank()) sb.append("Translate into Modern Standard Arabic (fusha). ")
        else {
            sb.append("Translate into the Arabic dialect: ").append(d).append(" — real colloquial speech as native speakers talk, NOT Modern Standard Arabic. ")
            if (d == "مصري") sb.append("Use pure Egyptian Arabic: ده/دي/دول, إيه, ليه, إزاي, فين, إمتى, مين, اللي, مش, مفيش, عايز, دلوقتي, كده, عشان, أوي; present tense with ب (بيقول), future with ه (هعمل), negation ما…ش; never use لقد/سوف/لماذا/ماذا/هذا/الآن/أريد/كيف/أين. ")
        }
        if (style != "حرفي") sb.append("Style: ").append(style).append(", intensity: ").append(deg).append(" — use informal street-level wording that fits this style, without changing the meaning or gender. ")
        else sb.append("Keep the translation faithful to the source. ")
        return sb.toString()
    }

    /** طلب واحد لموديل واحد. بيبعت الجمل بأرقام ({i,t}) ويطابق الرد بالرقم، فمحدش يتزحزح لو الموديل دمج أو فوّت سطر */
    internal fun llmCall(url: String, key: String, model: String, texts: List<String>, sl0: String): List<String?> {
        val sys = "You are a professional subtitle translator. " + llmTarget() +
            "Keep the meaning, tone, names and numbers; never merge or split items; do not explain. " +
            "The input is a JSON array of objects {\"i\": index, \"t\": text}. Reply with ONLY a JSON array of objects {\"i\": index, \"t\": translation} using the same i values."
        val arrIn = JSONArray()
        for ((i, t) in texts.withIndex()) arrIn.put(JSONObject().put("i", i).put("t", t))
        val user = (if (sl0.isNotEmpty() && sl0 != "auto") "Source language: $sl0\n" else "") + arrIn.toString()
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.2).put("stream", false)
        val raw = ExtLlm.post(url, mapOf("Authorization" to "Bearer $key"), body.toString(), 120000)
        val content = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
        val a = content.indexOf('['); val b = content.lastIndexOf(']')
        if (a < 0 || b <= a) throw java.io.IOException("رد " + model + " مش JSON array")
        val arr = JSONArray(content.substring(a, b + 1))
        val out = arrayOfNulls<String>(texts.size)
        for (k in 0 until arr.length()) {
            val o = arr.opt(k)
            if (o is JSONObject) {
                val i = o.optInt("i", -1); val t = o.optString("t", "").trim()
                if (i in out.indices && t.isNotEmpty()) out[i] = t
            } else if (o is String && arr.length() == texts.size) {
                val t = o.trim(); if (t.isNotEmpty()) out[k] = t
            }
        }
        if (out.all { it == null }) throw java.io.IOException("رد " + model + " مفيهوش ترجمة")
        return out.toList()
    }


    /** (v274) تجربة مزوّد بجملة واحدة (من الإعدادات): سطر نتيجة، وبيفك الإيقاف لو نجح */
    private fun <T> strictTest(p: Prov, f: () -> T): T = f()

    fun testProv(p: Prov): String = try {
        val r = strictTest(p) { p.batch?.invoke(listOf("Hello, how are you today?"), "en") ?: listOf(p.call("Hello, how are you today?", "en")) }
        val tr = r.firstOrNull()
        if (tr.isNullOrBlank()) "❌ ${p.label}: ما رجّعش ترجمة" else { clearBlock(p.id); "✅ ${p.label} شغّال: «$tr»" }
    } catch (e: Exception) { "❌ ${p.label}: " + (e.message ?: e.javaClass.simpleName).take(140) }

    /** (v276) اختبار لحظي بقياس الزمن (من غير حفظ): (نجح؟، ms، الترجمة أو سبب الفشل). بيفك الإيقاف لو نجح */
    fun testTimed(p: Prov): Triple<Boolean, Long, String> {
        val t0 = System.currentTimeMillis()
        return try {
            val r = strictTest(p) { p.batch?.invoke(listOf("Hello, how are you today?"), "en") ?: listOf(p.call("Hello, how are you today?", "en")) }
            val tr = r.firstOrNull(); val ms = System.currentTimeMillis() - t0
            if (tr.isNullOrBlank()) Triple(false, ms, "ما رجّعش ترجمة") else { clearBlock(p.id); Triple(true, ms, tr) }
        } catch (e: Exception) { Triple(false, System.currentTimeMillis() - t0, (e.message ?: e.javaClass.simpleName).take(90)) }
    }

    // ===== عدّادات =====
    private fun usedToday(id: String): Int {
        val p = Cfg.str("ext_use_$id").split(":")
        return if (p.size == 2 && p[0] == day()) (p[1].toIntOrNull() ?: 0) else 0
    }
    private fun addUsed(id: String, n: Int) { if (n > 0) Cfg.put("ext_use_$id", day() + ":" + (usedToday(id) + n)) }
    private fun dead(id: String): Boolean = Cfg.str("ext_dead_$id") == day()
    private fun markDead(id: String) { Cfg.put("ext_dead_$id", day()) }
    private fun addCount(id: String, n: Int) { if (n > 0) Cfg.put("ext_n_$id", ((Cfg.str("ext_n_$id").toIntOrNull() ?: 0) + n).toString()) }

    /** سطر حالة للعرض في الإعدادات */
    fun statLine(p: Prov): String {
        val n = Cfg.str("ext_n_${p.id}").toIntOrNull() ?: 0
        val q = if (p.dailyChars > 0) " · النهارده ${usedToday(p.id)}/${p.dailyChars} حرف" else ""
        val d = if (dead(p.id)) " · ⛔ الكوتة خلصت لباقي اليوم" else (blockedWhy(p.id, p.keyHash())?.let { " · ⛔ $it" } ?: "")
        return "ترجم $n جملة إجمالًا$q$d"
    }

    // ===== شبكة =====
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) return null
            val t = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            NetUse.api(url.length.toLong() + 200L, t)
            return t
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }

    /** تخمين لغة المصدر من الحروف (MyMemory مابيقبلش «auto») */
    fun guessLang(t: String): String {
        for (ch in t) {
            val c = ch.code
            if (c in 0xAC00..0xD7AF || c in 0x1100..0x11FF) return "ko"
            if (c in 0x3040..0x30FF) return "ja"
        }
        for (ch in t) if (ch.code in 0x4E00..0x9FFF) return "zh-CN"
        for (ch in t) if (ch.code in 0x0400..0x04FF) return "ru"
        return "en"
    }

    private fun myMemory(text: String, sl0: String): String? {
        val sl = if (sl0 == "auto") guessLang(text) else sl0
        if (sl == "ar") return null
        val q = text.take(450)
        val t = get("https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(q, "UTF-8") + "&langpair=" + URLEncoder.encode("$sl|ar", "UTF-8")) ?: return null
        val j = JSONObject(t)
        val st = j.opt("responseStatus")?.toString()?.toIntOrNull() ?: 200
        val tr = j.optJSONObject("responseData")?.optString("translatedText", "") ?: ""
        if (st == 429) throw QuotaOut()
        val up = tr.uppercase()
        if (up.startsWith("MYMEMORY WARNING")) throw QuotaOut()
        if (st != 200 || tr.isBlank() || up.contains("PLEASE SELECT TWO DISTINCT") || up.contains("QUERY LENGTH LIMIT")) return null
        if (tr.trim() == q.trim()) return null   // رجّع نفس النص = ما ترجمش
        return tr.trim()
    }

    private val lingvaHosts = listOf("https://lingva.ml", "https://lingva.thedaviddelta.com")
    private fun lingva(text: String, sl0: String): String? {
        val sl = if (sl0 == "zh-CN") "zh" else sl0
        if (sl == "ar") return null
        val path = URLEncoder.encode(text.take(400), "UTF-8").replace("+", "%20")
        for (h in lingvaHosts) {
            try {
                val t = get("$h/api/v1/$sl/ar/$path") ?: continue
                val tr = JSONObject(t).optString("translation", "")
                if (tr.isNotBlank()) return tr.trim()
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * يترجم [texts] بمزوّد واحد (جملة جملة، 4 بالتوازي). بيرجّع نفس الطول، والفاشل null.
     * [alive] لو رجّعت false بيوقف. بيرجّع كمان سطر لوج.
     */
    fun run(p: Prov, texts: List<String>, sl: String, alive: () -> Boolean): List<String?> {
        val out = arrayOfNulls<String>(texts.size)
        lastError = ""
        if (texts.isEmpty()) return out.toList()
        if (dead(p.id)) { lastError = "الكوتة اليومية خلصت"; return out.toList() }
        blockedWhy(p.id, p.keyHash())?.let { lastError = it; return out.toList() }
        val bt = p.batch
        if (bt != null) {   // (v273) محرك LLM: 25 جملة في الطلب، ولو فشل طلبين ورا بعض بنقف
            var okN = 0; var failed = 0
            val chunks = texts.indices.chunked(p.chunk.coerceAtLeast(1))
            for ((ci, chunk) in chunks.withIndex()) {
                if (!alive()) break
                if (ci > 0 && p.gapMs > 0L) { try { Thread.sleep(p.gapMs) } catch (_: InterruptedException) { break } }   // (v416) راحة بين الدفعات (حد الطلبات)
                try {
                    val r = bt(chunk.map { texts[it] }, sl)
                    for ((q, i) in chunk.withIndex()) r.getOrNull(q)?.takeIf { it.isNotBlank() }?.let { out[i] = it; okN++ }
                } catch (e: Exception) {
                    lastError = (e.message ?: e.javaClass.simpleName).take(120)
                    if (e is ExtLlm.LlmErr) { noteFail(p.id, p.keyHash(), e); if (e.code in intArrayOf(401, 402, 403, 404, 429)) break }   // (v274) مفتاح/رصيد/كوتة/موديل: مفيش فايدة من إعادة المحاولة
                    if (++failed >= 2) break
                }
            }
            addCount(p.id, okN)
            return out.toList()
        }
        val budget = if (p.dailyChars > 0) (p.dailyChars - usedToday(p.id)).coerceAtLeast(0) else Int.MAX_VALUE
        var spent = 0
        val allow = BooleanArray(texts.size)
        for (i in texts.indices) { val len = texts[i].length; if (len in 1..450 && spent + len <= budget) { allow[i] = true; spent += len } }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = texts.indices.filter { allow[it] }.map { i ->
                Callable<Unit> {
                    if (!alive() || dead(p.id)) return@Callable
                    try { out[i] = p.call(texts[i], sl)?.takeIf { it.isNotBlank() } }
                    catch (_: QuotaOut) { markDead(p.id) }
                    catch (_: Exception) {}
                    Unit
                }
            }
            pool.invokeAll(jobs)
        } finally { pool.shutdown() }
        var chars = 0; var n = 0
        for (i in texts.indices) if (out[i] != null) { chars += texts[i].length; n++ }
        addUsed(p.id, chars); addCount(p.id, n)
        return out.toList()
    }
}
