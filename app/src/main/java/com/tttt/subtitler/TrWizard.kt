package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * (v254) شاشة «طريقة الترجمة»: زرار ▶ ترجمة قبل البداية بيسأل خطوة خطوة (أونلاين / أوفلاين ← لغة الفيديو ← طريقة التفريغ ← طريقة الترجمة ← «تحفظ الإعداد؟»).
 * لو اتحفظ، الضغطة الجاية على ▶ ترجمة بتبدأ بيه على طول، وزرار ⚙ الصغير جنب ▶ ترجمة بيفتح نفس الشاشة لتغييره.
 * المفاتيح: tr_saved / tr_mode (online|offline) / tr_online (id بريست أو current) / tr_s_lang / tr_s_model / tr_s_method (المحفوظ)،
 * و stt_offline / stt_lang / off_model / tr_method (اللي المحرك بيقراه فعلًا — بتتكتب وقت التشغيل).
 */
object TrWizard {
    /** (v291) ⓪ المصدر: القيم المؤقتة من الشاشة بتتكتب في Cfg لما الخطوات تخلّص (أو فورًا في «بصري بس») */
    var pSrc: String? = null
    var pKind: String? = null
    var sourceHook: (Boolean) -> Unit = {}   // (edit) — Player بيسجّله: «بصري بس» مالوش خطة صوت
    fun commitSource() { pSrc?.let { Cfg.put("tr_src", it) }; pKind?.let { Cfg.put("tr_vkind", it) }; pSrc = null; pKind = null }
    class Plan(val mode: String, val onlineId: String, val lang: String, val model: String, val method: String, val engine: String = "whisper", val dialect: String = "", val live: Boolean = false, val ext: String = "", val free: String = "", val dia: String? = null, val only: Boolean = false, val conv: String = "") {
        // (v281) conv: id محرك LLM بيحوّل اللهجة بدل جيميناي ("" = جيميناي)
        // (v280) only: «تفريغ بس» — النص الأصلي من غير ترجمة (Engine.transOnly)
        // (v279) dia: لهجة اتحدّدت من كروت «خط الترجمة» للمسارات الأونلاين (null = ماتلمسش الإعدادات). أونلاين جيميناي ← Cfg lang · أونلاين بمحرك خارجي ← conv_dialect. الأوفلاين بيستخدم dialect زي الأول
        val offline: Boolean get() = mode == "offline"
        /** (v274) أونلاين بمحرك خارجي: ext = id مزوّد LLM (تفريغ سحابي + ترجمته)، فاضي = جيميناي */
        val cloud: Boolean get() = false   // (v395) المحركات الخارجية اتشالت — جيميناي وجوجل بس
    }

    /** (v275) المشغّل بيحطه عشان تغيير وضع المفاتيح من الشاشة دي يوصل للمحرك الشغّال فورًا */
    @Volatile var keyModeHook: ((Int) -> Unit)? = null

    const val CURRENT = "current"
    val sttLangs = listOf(
        "en" to "إنجليزي", "ja" to "ياباني", "zh" to "صيني",
        "ko" to "كوري", "fr" to "فرنساوي", "ar" to "عربي", "es" to "إسباني", "tr" to "تركي", "ru" to "روسي",
        "de" to "ألماني", "it" to "إيطالي", "hi" to "هندي", "fa" to "فارسي", "ur" to "أوردو", "" to "تلقائي (يكتشف اللغة)"
    )   // (v262) كل لغات Whisper (Offline.langIds) — كانت 6 بس والباقي من الإعدادات والخطة كانت بتكتب فوقه

    /** (v255) لازم يتظبط من Cfg.init — لفحص النت */
    @Volatile var appCtx: android.content.Context? = null

    /** فيه اتصال بالنت دلوقتي؟ (لو مش عارف يفحص يرجّع true عشان ما نقولش «مفيش نت» غلط) */
    fun online(): Boolean {
        val c = appCtx ?: return true
        return try {
            val cm = c.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) { true }
    }

    /** اللهجة المطلوبة (غير الفصحى) من الإعدادات: «لهجة التحويل» لو متحددة، وإلا لغة الترجمة. "" = فصحى */
    fun dialectWanted(): String {
        val d = Cfg.str("conv_dialect", "").ifBlank { Cfg.str("lang", "فصحى") }.trim()
        return if (d.isEmpty() || d == "فصحى") "" else d
    }

    /** اللهجات اللي ممكن تختارها للترجمة ("فصحى" = من غير تحويل) */
    val dialects = listOf("فصحى", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني")

    /** فيه مفتاح جيميناي واحد على الأقل (أساسي أو احتياطي)؟ لو مش عارف يفحص يرجّع true */
    fun hasGeminiKey(): Boolean = try { Cfg.snapshot().let { c -> (c.keys + c.backup + c.refine).any { it != LocalKey.ID && it.length > 10 } } } catch (_: Throwable) { true }

    const val NO_NET_CLOUD = "📵 مفيش نت — التفريغ والترجمة السحابيين محتاجين اتصال بالنت، اتصل بالنت"

    /** (v395) التفريغ السحابي الخارجي اتشال — مفيش مزوّد */
    fun sttCandidate(ext: String): ExtLlm.Preset? = null
    fun sttFallback(): ExtLlm.Preset? = null

    const val NO_NET_MSG = "📵 مفيش نت — ترجمة جوجل محتاجة اتصال بالنت عشان تترجم، اتصل بالنت"
    const val NO_NET_GEMINI = "📵 مفيش نت — جيميناي محتاج اتصال بالنت عشان يترجم، اتصل بالنت"

    /**
     * إشعارات قبل بدء الترجمة بالخطة دي (قايمة فاضية = مفيش حاجة تتنبّه عليها).
     * جوجل + فصحى: إشعار إنها ترجمة جوجل. جوجل + لهجة (مصري…): مفيش إشعار — جيميناي بيحوّل ترجمة جوجل تلقائي؛ إلا لو مفيش مفتاح (هتفضل فصحى).
     * مفيش نت: بيقولك تتصل (جوجل وجيميناي الاتنين محتاجين نت للترجمة).
     */
    fun startNotices(pl: Plan): List<String> {
        if (pl.only) {   // (v280)
            val o = ArrayList<String>()
            o.add("✍ تفريغ بس — النص هيظهر بلغته الأصلية من غير ترجمة. لو عايز تترجمه بعدين اختار خطة ترجمة والجمل الأجنبية هتتصلّح بجيميناي")

            if (!pl.offline && !online()) o.add(NO_NET_GEMINI)
            return o
        }
        if (pl.offline && pl.free == "mlkit") return listOf("📴 أوفلاين كامل: Whisper + ML Kit على جهازك — فصحى، من غير نت ولا جيميناي (موديل لغة الترجمة بيتحمّل مرة واحدة لو مش نازل)")   // (v410)
        if (!pl.offline) return emptyList()
        val out = ArrayList<String>()
        if (pl.free == "free" && pl.method == "google") {   // (v440) FreeLLM: بيترجم باللهجة بنفسه (Gemini/Groq المجانيين بمفاتيحك)
            out.add("🆓 المترجم FreeLLM · " + FreeLlm.liveName() + " — بيوزّع على الموديلات المجانية حسب الحد، ولو كلهم وقفوا بيستنى (من غير جيميناي)")
            val fd = pl.dialect.trim().let { if (it == "فصحى") "" else it }
            out.add(if (fd.isEmpty()) "🌍 الترجمة فصحى" else "🗣 لهجة «$fd» — FreeLLM بيترجمها مباشرة (مش جيميناي)")
            if (!online()) out.add(NO_NET_MSG)
            return out
        }
        if (pl.free.isNotBlank() && pl.method == "google") out.add((if (pl.free.startsWith("llm_")) "🤖 المترجم " else "🌐 المترجم المجاني ") + (ExtTranslate.all().firstOrNull { it.id == pl.free }?.label ?: pl.free) + " بيترجم الأول — لو وقف أو الحد اليومي خلص بيكمّل الباقي ثم جوجل ثم جيميناي")
        val d = pl.dialect.trim().let { if (it == "فصحى") "" else it }
        if (pl.method == "google") {
            if (d.isEmpty()) out.add("🌍 الترجمة بجوجل (فصحى، من غير API)")
            else if (pl.conv.isNotBlank()) out.add("🗣 لهجة «$d» بتتحوّل بـ " + convLabel(pl.conv) + " بعد الترجمة")
            else if (!hasGeminiKey()) out.add("⚠ لهجة «$d» محتاجة مفتاح جيميناي يحوّل ترجمة جوجل — من غير مفتاح هتفضل فصحى")
            if (!online()) out.add(NO_NET_MSG)
        } else if (!online()) out.add(NO_NET_GEMINI)
        return out
    }

    // ===== (v282) تجربة لحظية: طلب صغير حقيقي لكل كارت =====
    /** طلب واحد صغير لجيميناي بأول مفتاح (بياخد طلب واحد من الكوتا). (نجح؟، سطر نتيجة) */
    fun probeGemini(): Pair<Boolean, String> {
        val k = try { Cfg.allMainKeys().firstOrNull() } catch (_: Throwable) { null } ?: return false to "مفيش مفتاح جيميناي"
        val t0 = System.currentTimeMillis()
        return try {
            val mdl = Cfg.str("model", Models.DEFAULT).ifBlank { Models.DEFAULT }
            val r = Api.generate(mdl, k, "Reply with one word: OK", null, 64, 0.0, false, false)
            true to ((System.currentTimeMillis() - t0).toString() + "ms · المفتاح شغّال" + (if (r.text.isBlank()) "" else " «" + r.text.trim().take(20) + "»"))
        } catch (e: Throwable) { false to (e.message ?: e.javaClass.simpleName).take(90) }
    }

    /** ترجمة جوجل الداخلي (من غير مفتاح): جملة واحدة */
    fun probeGoogle(): Pair<Boolean, String> {
        val t0 = System.currentTimeMillis()
        return try {
            val body = "client=gtx&sl=en&tl=ar&dt=t&q=" + java.net.URLEncoder.encode("Hello, how are you today?", "UTF-8")
            val c = java.net.URL("https://translate.googleapis.com/translate_a/single").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val txt = (if (code == 200) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            c.disconnect()
            if (code != 200) false to ("HTTP $code" + (if (code == 429) " (جوجل رافض مؤقتًا)" else ""))
            else {
                val segs = org.json.JSONArray(txt).optJSONArray(0)
                val sb = StringBuilder()
                if (segs != null) for (k in 0 until segs.length()) sb.append(segs.optJSONArray(k)?.optString(0, "") ?: "")
                if (sb.isBlank()) false to "ما رجّعش ترجمة" else true to ((System.currentTimeMillis() - t0).toString() + "ms «" + sb.toString().trim().take(40) + "»")
            }
        } catch (e: Throwable) { false to (e.message ?: e.javaClass.simpleName).take(90) }
    }

    fun saved(): Boolean = Cfg.bool("tr_saved", false)
    /** (v281) اسم محوّل اللهجة: فاضي = جيميناي */
    fun convLabel(id: String): String = if (id.isBlank()) "جيميناي" else (ExtLlm.byId(id)?.label ?: id)
    /** «google» = بعد التفريغ الأوفلاين النص بيتترجم بجوجل (من غير API)، غير كده جيميناي بالسياق */
    fun method(): String = if (Cfg.str("tr_method", "gemini") == "google") "google" else "gemini"
    /** المحرك بيسأل: الترجمة بجوجل دلوقتي؟ (لازم الأوفلاين شغّال فعلًا، لأن جوجل بيترجم النص المفرّغ بس) */
    fun googleOn(): Boolean = method() == "google" && Offline.configured()
    /** كود لغة المصدر لجوجل من لغة الأوفلاين */
    fun googleLang(): String = when (val l = Offline.lang()) { "" -> "auto"; "zh" -> "zh-CN"; else -> l }

    fun langLabel(id: String): String = sttLangs.firstOrNull { it.first == id }?.second ?: "تلقائي"

    fun savedPlan(): Plan? {
        if (!saved()) return null
        return when (Cfg.str("tr_mode", "")) {
            "offline" -> Plan("offline", "", Cfg.str("tr_s_lang", ""), Cfg.str("tr_s_model", "small").ifBlank { "small" }, if (Cfg.str("tr_s_method", "gemini") == "google") "google" else "gemini", if (Cfg.str("tr_s_engine", "whisper") == "sherpa") "sherpa" else "whisper", Cfg.str("tr_s_dialect", ""), Cfg.bool("tr_s_live", false), free = Cfg.str("tr_s_free", ""), only = Cfg.bool("tr_s_only", false), conv = Cfg.str("tr_s_conv", ""))
            "online" -> Plan("online", Cfg.str("tr_online", CURRENT).ifBlank { CURRENT }, "", "", "gemini", ext = Cfg.str("tr_online_ext", ""), dia = Cfg.str("tr_online_dia", "-").let { if (it == "-") null else it }, only = Cfg.bool("tr_s_only", false), conv = Cfg.str("tr_s_conv", ""))
            else -> null
        }
    }

    /** (v360) ملخص قصير للإعداد المحفوظ — بيظهر تحت اسم الفيديو في المشغّل */
    /** (v413) سطر «كشف اللغة بـ Whisper» تحت اسم الفيديو: اللغة المكتشفة، أو اللي اخترتها بإيدك */
    fun langLine(): String = try {
        val pl = savedPlan()
        if (pl == null || !pl.offline || pl.engine == "sherpa" || SourceMode.get() == SourceMode.VISUAL) "" else {
            val man = Offline.lang(); val det = Offline.detectedLang
            "🔎 " + if (man.isNotEmpty()) "اللغة: " + langLabel(man) + " (إنت حددتها)" else if (det.isNotEmpty()) "كشف وشبر اللغة: " + OffLang.nameOf(det) + " — غلط؟ غيّرها من ⚙" else "كشف وشبر اللغة: لسه بيكتشف…"
        }
    } catch (_: Throwable) { "" }

    fun summaryLine(): String = try {
        if (SourceMode.get() == SourceMode.VISUAL) "👁 بصري بس · " + SourceMode.kindLabel()
        else {
            val pl = savedPlan()
            if (pl == null) "" else {
                val id = Cfg.str("tr_mode_id", "")
                val flName: String = com.tttt.subtitler.FreeLlm.liveName()   // (v447) برّه الـ when عشان السطر الطويل مابيحلّش المرجع
                val base = when (id) {
                    "g_wav" -> (if (pl.only) "✍ تفريغ ذكاء صناعي" else "☁ أونلاين · WAV"); "g_aac" -> (if (pl.only) "✍ تفريغ ذكاء صناعي" else "☁ أونلاين · AAC")
                    "loc_google" -> (if (pl.only) "✍ تفريغ Whisper" else "🌗 وشبر + 🌐 LibreTranslate"); "loc_free" -> "🌗 وشبر + 🆓 FreeLLM · " + flName; "f_gem" -> "🆓 FreeLLM · " + flName
                    "ai_only" -> "✍ تفريغ ذكاء صناعي"; "w_only" -> "✍ تفريغ Whisper"; "s_only" -> "✍ تفريغ SenseVoice"
                    else -> if (pl.only) "✍ تفريغ بس" else if (pl.offline) "📴 أوفلاين" else if (pl.cloud) "☁ محرك خارجي" else "☁ أونلاين"
                }
                val parts = ArrayList<String>(); parts.add(base)
                if (!pl.only) {
                    val d = (if (pl.offline) pl.dialect else (pl.dia ?: "")).ifBlank { "فصحى" }
                    parts.add("🗣 $d")
                    val st = Cfg.str("tone_style", "")
                    if (st.isNotBlank() && st != "حرفي" && id in setOf("g_wav", "g_aac")) parts.add("🎚 $st/" + Cfg.str("tone_strength", "متوسطة"))
                }
                if (SourceMode.get() == SourceMode.HYBRID) parts.add("👁 " + SourceMode.kindLabel())
                parts.joinToString(" · ")
            }
        }
    } catch (_: Throwable) { "" }

    fun describe(pl: Plan): String = SourceMode.prefix() + describeCore(pl)
    private fun describeCore(pl: Plan): String =
        if (pl.only) "✍ تفريغ بس (من غير ترجمة)\nالتفريغ: " + (if (pl.offline) (if (pl.engine == "sherpa") "SenseVoice" else "Whisper " + pl.model) + " على الجهاز · " + langLabel(pl.lang) else "جيميناي") + "\nالنص بيظهر بلغته الأصلية"
        else if (pl.offline) "📴 أوفلاين" + (if (pl.live) " ⚡ لحظي" else "") + " · " + langLabel(pl.lang) + "\nالتفريغ: " + (if (pl.engine == "sherpa") "SenseVoice (sherpa-onnx)" else "Whisper " + pl.model) + " على الجهاز\nالترجمة: " + (if (pl.free == "mlkit") "ML Kit (أوفلاين · من غير نت)" else if (pl.method == "google") "جوجل (من غير API)" else "جيميناي بالسياق") + "\nاللهجة: " + pl.dialect.ifBlank { "فصحى" } + (if (pl.method == "google" && pl.dialect.isNotBlank() && pl.dialect != "فصحى") (if (pl.free == "free") " (FreeLLM بيترجمها مباشرة)" else " (" + convLabel(pl.conv) + " بيحوّل الترجمة)") else "") + (if (pl.free.isNotBlank() && pl.method == "google") "\nالمترجم: " + (if (pl.free.startsWith("llm_")) "🤖 " + (ExtLlm.byId(pl.free.removePrefix("llm_"))?.label ?: pl.free) + " الأول (بمفتاحك)" else "🌐 " + (ExtTranslate.all().firstOrNull { it.id == pl.free }?.label ?: pl.free) + " الأول (مجاني من الريبو)") else "")
        else "☁ أونلاين (جيميناي)\n" + (TrPresets.byId(pl.onlineId)?.let { it.icon + " " + it.name } ?: "⚙ الإعدادات الحالية") + (pl.dia?.let { "\nاللهجة: " + it.ifBlank { "فصحى" } } ?: "")

    /** (v274) لو التفريغ السحابي كان شغّال بسبب خطة «أونلاين بمحرك خارجي» بنقفله لما نرجع لخطة تانية (إلا لو إنت شغّلته من الإعدادات) */
    private fun leaveCloud() {
        Cfg.put("ext_primary", "")
        if (Cfg.str("rstt_by_plan") == "1") { Cfg.put("rstt_on", "off"); Cfg.put("rstt_by_plan", "0") }
    }

    /** بيكتب الإعدادات اللي المحرك بيقراها (من غير ما يحفظ الخطة) */
    fun apply(pl: Plan) {
        Cfg.put("omni_mode", "0"); Cfg.put("conv_via", pl.conv)   // (v395) المحركات الخارجية اتشالت — محوّل اللهجة جيميناي
        Cfg.put("stt_offline", if (pl.offline || pl.cloud) "1" else "0")
        if (pl.offline) { Cfg.put("stt_lang", pl.lang); Cfg.put("off_model", pl.model); Cfg.put("tr_method", pl.method); Cfg.put("stt_engine", pl.engine); Cfg.put("conv_dialect", if (pl.dialect == "فصحى") "" else pl.dialect)
            if (pl.live) Cfg.put("ctx_min", "1") else if (Cfg.int("ctx_min", 3) == 1) Cfg.put("ctx_min", "3")   // (v266)
            leaveCloud()
            if (pl.free.isNotBlank()) { Cfg.put("ext_primary", pl.free); Cfg.put("ext_on_" + pl.free, "on") } }   // (v275) مترجم مجاني من الريبو يتجرّب الأول
        else { Cfg.put("tr_method", "gemini"); leaveCloud(); pl.dia?.let { val d = it.ifBlank { "فصحى" }; Cfg.put("lang", d); Cfg.put("conv_dialect", if (d == "فصحى") "" else d) } }   // (v279) لهجة ترجمة جيميناي من كارت ③ · (v297) conv_dialect بنفس القيمة عشان قيمة قديمة ماتغلبش
    }

    fun save(pl: Plan) {
        Cfg.put("tr_mode", pl.mode)
        Cfg.put("tr_s_only", if (pl.only) "1" else "0")   // (v280)
        Cfg.put("tr_s_conv", pl.conv)   // (v281)
        if (pl.offline) { Cfg.put("tr_s_lang", pl.lang); Cfg.put("tr_s_model", pl.model); Cfg.put("tr_s_method", pl.method); Cfg.put("tr_s_engine", pl.engine); Cfg.put("tr_s_dialect", pl.dialect); Cfg.put("tr_s_live", if (pl.live) "1" else "0"); Cfg.put("tr_s_free", pl.free) }
        else { Cfg.put("tr_online", pl.onlineId); Cfg.put("tr_online_ext", pl.ext); Cfg.put("tr_online_dia", pl.dia ?: "-") }
        Cfg.put("tr_saved", "1")
    }

    fun clearSaved() { Cfg.put("tr_saved", "0") }

    /** (v261) تغيير من الإعدادات (لغة التفريغ / الموديل / المحرك / طريقة الترجمة) بيتكتب كمان في الخطة المحفوظة، وإلا «▶ ترجمة» كان بيكتب القديم فوقه */
    fun syncSaved(key: String, v: String) {
        if (!saved()) return
        // (v297) اللهجة اتغيّرت من أي مكان (إعدادات / زرار اللغة في المشغّل): تتكتب في الخطة المحفوظة كمان، وإلا «▶ ترجمة» كان بيرجّع القديمة (فصحى)
        if (key == "conv_dialect" || key == "lang") {
            val d = v.ifBlank { "فصحى" }
            when (Cfg.str("tr_mode", "")) { "offline" -> Cfg.put("tr_s_dialect", d); "online" -> Cfg.put("tr_online_dia", d) }
            return
        }
        if (Cfg.str("tr_mode", "") != "offline") return
        when (key) {
            "stt_lang" -> { Cfg.put("tr_s_lang", v); if (!SherpaStt.handles(v)) Cfg.put("tr_s_engine", "whisper") }
            "off_model" -> Cfg.put("tr_s_model", v)
            "stt_engine" -> Cfg.put("tr_s_engine", v)
            "tr_method" -> Cfg.put("tr_s_method", v)
        }
    }

    /** [onDone] (الخطة، احفظ؟، ابدأ ترجمة؟) — بتتنده مرة واحدة لما تخلّص الخطوات */
    fun show(act: Activity, edit: Boolean, entry: String = "", onDone: (Plan, Boolean, Boolean) -> Unit) { pSrc = null; pKind = null; Wiz(act, edit, entry) { pl, save, start -> commitSource(); onDone(pl, save, start) }.start() }   // (v427) entry="gem" = يفتح خيارات جيميناي على طول

    private class Wiz(val act: Activity, val edit: Boolean, val entry: String, val onDone: (Plan, Boolean, Boolean) -> Unit) {
        val th = Glass.theme()
        val ui = Ui(act, th)
        val dlg = GDialog(act)
        val title = ui.text("", 17f, th.primary, true)
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val foot = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val hist = ArrayList<() -> Unit>()
        var viaModes = false   // (v360) الإعداد جه من الأوضاع المبسّطة (وإلا tr_mode_id بيتفضّى عشان الملخص ما يكدبش)
        fun done(pl: Plan, save: Boolean, start: Boolean) { if (!viaModes) Cfg.put("tr_mode_id", ""); onDone(pl, save, start) }

        // المسودة: بتبدأ من اللي شغّال دلوقتي (أو المحفوظ لو فيه)
        val sp = TrWizard.savedPlan()
        var mode = sp?.mode ?: if (Cfg.bool("stt_offline", false)) "offline" else "online"
        var onlineId = Cfg.str("tr_online", CURRENT).ifBlank { CURRENT }
        var lang = sp?.takeIf { it.offline }?.lang ?: Offline.lang()
        var model = Offline.model().id
        var method = TrWizard.method()
        var engine = sp?.takeIf { it.offline }?.engine ?: (if (Cfg.str("stt_engine", "whisper") == "sherpa") "sherpa" else "whisper")

        var dialect = sp?.takeIf { it.offline }?.dialect ?: TrWizard.dialectWanted()
        var live = false
        var presetFlow = false
        var free = sp?.takeIf { it.offline }?.free ?: ""   // (v275) id مترجم مجاني (من الريبو) أو فاضي
        var ext = sp?.takeIf { !it.offline }?.ext ?: ""   // (v274) محرك أونلاين خارجي (id مزوّد) أو فاضي = جيميناي
        val tested = HashMap<String, Triple<Boolean, Long, String>>()
        var testing = false
        val ftested = HashMap<String, Triple<Boolean, Long, String>>()
        var ftesting = false

        // ===== (v279) حالة كروت «خط الترجمة»: ① تفريغ ← ② ترجمة ← ③ لهجة =====
        var src = ""          // gemini | whisper | sherpa | cloud
        var tr = ""           // gemini | google | free | llm
        var trId = ""         // free: id مزوّد الريبو · llm: id بريست LLM
        var gp = CURRENT      // نوع ترجمة جيميناي (id بريست أو current) لما src=gemini
        var dia = ""          // "" = فصحى
        var diaSet = false    // اللهجة اتحدّدت صراحةً (وإلا الأونلاين مابيلمسش Cfg lang)
        var hubFlow = false   // شاشات التنزيل بترجع للكروت
        var convId = ""       // (v281) محوّل اللهجة: id محرك LLM أو فاضي = جيميناي
        var hubActive = false // (v280) plan() بيقرا tr=="none" بس لما الكروت هي اللي شغّالة (الويزارد القديم ماله دعوة)
        var repoToHub = false // فحص الريبو بيرجع للكروت
        var lastQp = ""

        init {
            val s0 = sp
            if (s0 != null) {
                if (s0.offline) {
                    src = if (s0.engine == "sherpa") "sherpa" else "whisper"
                    when {
                        s0.only -> tr = "none"
                        s0.free.startsWith("llm_") -> { tr = "llm"; trId = s0.free.removePrefix("llm_") }
                        s0.free.isNotBlank() -> { tr = "free"; trId = if (s0.free == "mymemory" || s0.free == "lingva") "msedge" else s0.free }
                        else -> tr = if (s0.method == "google") "google" else "gemini"
                    }
                    live = s0.live; dia = s0.dialect; diaSet = true; convId = s0.conv
                } else if (s0.cloud) {
                    src = "cloud"; tr = if (s0.only) "none" else "llm"; trId = s0.ext; convId = s0.conv
                    s0.dia?.let { dia = it; diaSet = true }
                } else {
                    src = "gemini"; tr = if (s0.only) "none" else "gemini"; gp = if (s0.only) CURRENT else s0.onlineId
                    s0.dia?.let { dia = it; diaSet = true }
                }
            } else {
                if (mode == "offline") { src = if (engine == "sherpa") "sherpa" else "whisper"; tr = if (method == "google") "google" else "gemini" }
                else { src = "gemini"; tr = "gemini" }
                dia = TrWizard.dialectWanted()
            }
            if (dia == "فصحى") dia = ""
        }

        fun plan() = Plan(mode, onlineId, lang, model, method, engine, dialect, live, if (mode == "online") ext else "", if (mode == "offline") free else "", if (mode == "online" && diaSet && tr != "none") dia else null, hubActive && tr == "none", if (hubActive && src != "gemini" && tr != "none" && tr != "gemini" && dia.isNotBlank()) convId else "")

        fun go(step: () -> Unit) { hist.add(step); step() }
        fun back() { if (hist.size > 1) { hist.removeAt(hist.size - 1); hist[hist.size - 1]() } else dlg.dismiss() }
        fun reset(t: String) { title.text = t; body.removeAllViews(); foot.removeAllViews() }
        fun note(t: String) {
            body.addView(ui.text(t, 12.5f, th.muted).apply { setLineSpacing(0f, 1.15f); setPadding(0, ui.dp(2), 0, ui.dp(8)) })
        }

        fun opt(t: String, d: String, on: Boolean, f: () -> Unit) {
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(14), ui.dp(11), ui.dp(14), ui.dp(11))
                background = ui.box(if (on) ((th.primary and 0x00FFFFFF) or 0x33000000) else th.surface, if (on) th.primary else th.border, 14)
            }
            item.addView(ui.text((if (on) "✓ " else "") + t, 15f, th.text, true))
            if (d.isNotBlank()) item.addView(ui.text(d, 12f, th.muted).apply { setLineSpacing(0f, 1.1f); setPadding(0, ui.dp(3), 0, 0) })
            Glass.pressable(item)
            item.setOnClickListener { f() }
            body.addView(item, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
        }

        fun footBtn(t: String, main: Boolean, f: () -> Unit) {
            val b = IconTextView(act).apply {
                text = t; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
                setTextColor(if (main) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
                background = if (main) Glass.primaryFill(th, ui.dp(12).toFloat()) else ui.box(th.surface, th.border, 12)
            }
            Glass.pressable(b)
            b.setOnClickListener { f() }
            foot.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        fun addBack(label: String = "← رجوع") = footBtn(label, false) { back() }

        // ===== الخطوات =====
        // ===== (v291) ⓪ المصدر: 🎙 صوتي بس · 👁 بصري بس · 🎙+👁 هجين — بعده خط الترجمة (للصوتي والهجين) =====
        var tsrc = SourceMode.get()
        var tkind = SourceMode.kind()
        fun stepVisKind() {
            reset("👁 البصري يترجم إيه؟")
            note(if (tsrc == "visual") "بصري بس: الترجمة الصوتية هتتقفل." else "هجين: الصوت بيترجم زي ما هتختار بعد كده، والبصري شغّال معاه.")
            opt("👁 نصوص الشاشة", "لافتات وكلام مكتوب في الصورة. الهارد سب (الترجمة المحروقة) بيتتجاهل.", tkind == "scene") { tkind = "scene"; pickKind() }
            opt("🔤 هارد سب", "الترجمة المحروقة في الفيديو (وأي نصوص في الصورة).", tkind == "hard") { tkind = "hard"; pickKind() }
            addBack()
        }
        fun pickKind() {
            TrWizard.pSrc = tsrc; TrWizard.pKind = tkind
            if (tsrc == "visual") { dlg.dismiss(); TrWizard.commitSource(); TrWizard.sourceHook(edit) }
            else go(hubL)
        }

        // ===================== (v360) الأوضاع المبسّطة =====================
        // شاشة واحدة بكل الأوضاع ← (للتلاتة الأولانية: لهجة ← أسلوب ← شدة) ← «بصري معاه؟» ← يطبّق ويحفظ ويقفل.
        val hubL: () -> Unit = { stepHub() }
        var mId = Cfg.str("tr_mode_id", "")
        var onlySw = false   // (v427) «تفريغ بس» بقى وضع لوحده (ai_only / w_only) مش سويتش
        var mStyle = Cfg.str("tone_style", "").ifBlank { "حرفي" }
        var mDeg = Cfg.str("tone_strength", "متوسطة").ifBlank { "متوسطة" }
        var afterDl: (() -> Unit)? = null
        val dialectModes = setOf("g_wav", "g_aac")

        fun sec(t: String) { body.addView(ui.text(t, 13f, th.primary, true).apply { setPadding(ui.dp(2), ui.dp(10), 0, ui.dp(2)) }) }

        fun localReadyNow(): Boolean = Offline.remoteOn() || (if (src == "sherpa") SherpaStt.ready() else (WhisperLib.loaded && Offline.downloaded(Offline.models.firstOrNull { it.id == model } ?: Offline.model())))

        fun finishModes(s: String, k: String?) {
            TrWizard.pSrc = s; if (k != null) TrWizard.pKind = k
            if (mId in dialectModes) { Cfg.put("tone_style", if (mStyle == "حرفي") "" else mStyle); Cfg.put("tone_strength", mDeg) }
            if (mId == "g_wav") Cfg.put(AudioEnc.PRESET_KEY, AudioEnc.NORMAL)
            if (mId == "g_aac") Cfg.put(AudioEnc.PRESET_KEY, AudioEnc.FAST)
            Cfg.put("tr_mode_id", mId); viaModes = true
            sync()
            done(plan(), true, !edit)
            dlg.dismiss()
        }

        fun stepMDeg() {
            reset("🎚 شدة الأسلوب")
            note("أسلوب «" + mStyle + "» — بأي درجة؟")
            for ((d, ds) in listOf("خفيفة" to "لمسة بسيطة في الجمل اللي تستاهل", "متوسطة" to "في معظم الجمل بدرجة معتدلة", "شديدة" to "في كل جملة تقريبًا وبأقصى درجة")) opt(d, ds, mDeg == d) { mDeg = d; finishModes("audio", null) }
            addBack()
        }

        fun stepMTone() {
            reset("🎚 الأسلوب")
            note("حرفي = ترجمة عادية من غير أسلوب. أي أسلوب تاني بتختار شدته في الخطوة اللي بعدها.")
            for ((st, sd) in listOf("حرفي" to "ترجمة عادية", "شعبي" to "كلام الشارع والتعبيرات اليومية", "جرئ" to "ألفاظ أجرأ من غير تلطيف زيادة", "+18" to "صريح بلا تلطيف — يطابق الأصل بالظبط")) opt(st, sd, mStyle == st) {
                mStyle = st
                if (st == "حرفي") finishModes("audio", null) else go { stepMDeg() }
            }
            addBack()
        }


        fun afterModePick() {
            // (v410) تفريغ بس: مفيش لهجة ولا أسلوب · نص-أونلاين وأوفلاين: فصحى + صوتي بس (مفيش لهجة ولا بصري)
            if (onlySw || mId == Strict.SEMI || mId == "ai_only" || mId == "w_only") { finishModes("audio", null); return }   // (v427) وشبر+Libre وتفريغ بس: من غير أسئلة لهجة
            // (v435) جيميناي: اللهجة والأسلوب والشدة من الإعدادات زي ما هي — مفيش أسئلة تاني (تتغيّر من ⚙ ← 🌐 اللهجة والأسلوب)
            dia = TrWizard.dialectWanted(); diaSet = true
            finishModes("audio", null)
        }


        fun pickMode(id: String) {
            mId = id; afterDl = null; hubActive = true; hubFlow = false; presetFlow = false; live = false; repoToHub = false
            when (id) {
                "g_wav", "g_aac" -> { src = "gemini"; tr = "gemini"; gp = CURRENT }
                "loc_google" -> { src = "whisper"; tr = "google"; trId = ""; dia = ""; diaSet = true; convId = ""; lang = "" }   // (v410) 🌗 Whisper فقط + لغة تلقائية
                "ai_only" -> { src = "gemini"; tr = "none"; trId = "" }
                "w_only" -> { src = "whisper"; tr = "none"; trId = "" }
            }
            if (onlySw && id in setOf("g_wav", "g_aac", Strict.SEMI)) tr = "none"   // (v410)
            if (src == "whisper") engine = "whisper"
            if (src == "sherpa") { engine = "sherpa"; if (!SherpaStt.handles(lang)) lang = "" }
            if (src == "whisper" && !WhisperLib.loaded) { Notice.show(act, "الأوفلاين مش مدعوم على جهازك", 2500L); return }
            if (src == "sherpa" && !SherpaStt.supported()) { Notice.show(act, "جهازك مش بيدعم SenseVoice", 2500L); return }
            normalize(); sync()
            if (isLocal() && !localReadyNow()) {
                afterDl = { afterDl = null; afterModePick() }
                go { if (src == "sherpa") stepSherpa() else stepStt() }
            } else afterModePick()
        }

        fun stepOnlineQ() {
            reset("☁ أونلاين — جودة الصوت")
            note("جيميناي بيسمع ويترجم بمفاتيحك. اللهجات والأسلوب والشدة والضمائر والشخصيات متاحة هنا بس.")
            opt("🧠 عادي (WAV)", "صوت خام أعلى جودة · رفع أبطأ وداتا أكتر", mId == "g_wav") { pickMode("g_wav") }
            opt("🧠 توفير بيانات (AAC)", "نفس الشيء بصوت مضغوط (أصغر ~8 مرات)", mId == "g_aac") { pickMode("g_aac") }
            addBack()
        }

        /** (v427) الأوضاع التلاتة بس: ☁ ترجمة بجيميناي · 🌗 أوفلاين وشبر + LibreTranslate · ✍ تفريغ بس (جيميناي أو وشبر) */
        fun stepModes() {
            reset(if (edit) "⚙ طريقة الترجمة" else "▶ هتترجم إزاي؟")
            val cur = TrWizard.summaryLine()
            note(if (cur.isBlank()) "اختار وضع من التلاتة." else "الحالي: $cur")
            opt("☁ ترجمة بجيميناي", "٧٥٪ من مفاتيحك بتنسخ الكلام (مع مصدر الصوت وشرح المصطلحات والتلميحات فوق) و٢٥٪ بتترجم بالسياق · لهجات + أسلوب + شدة + ضمائر + شخصيات · محتاج مفتاح ونت", mId == "g_wav" || mId == "g_aac") { go { stepOnlineQ() } }
            opt("🌗 أوفلاين — وشبر + LibreTranslate  🔒 من غير مفتاح", "التفريغ بوشبر على جهازك والترجمة بـ LibreTranslate (لو وقف بيكمّل Microsoft Edge) · فصحى بس · جيميناي ممنوع · الترجمة محتاجة نت · اللغة بتتكشف لوحدها", mId == Strict.SEMI) { pickMode(Strict.SEMI) }
            sec("✍ تفريغ بس (من غير ترجمة)")
            opt("☁ تفريغ بجيميناي", "النص بيظهر بلغته الأصلية · محتاج مفتاح ونت", mId == "ai_only") { pickMode("ai_only") }
            opt("📴 تفريغ أوفلاين بوشبر", "النص بيظهر بلغته الأصلية · من غير مفتاح ولا نت (النت بس لتنزيل الموديل مرة واحدة)", mId == "w_only") { pickMode("w_only") }
            if (hist.size > 1) addBack() else footBtn("✖ إلغاء", false) { dlg.dismiss() }
        }

        fun stepSummary() {
            reset("⚙ طريقة الترجمة")
            val s = TrWizard.savedPlan()
            note(if (s != null) "الإعداد المحفوظ دلوقتي:\n" + TrWizard.describe(s) + "\n\nزرار «▶ ترجمة» بيبدأ بيه على طول." else "مفيش إعداد محفوظ — زرار «▶ ترجمة» هيسألك كل مرة.")
            opt("🔀 ترتيب الترجمة: " + (if (ExtTranslate.externalFirst()) "🌐 الخارجية أولًا" else "🏠 جوجل الداخلي أولًا"), "دوس عشان تبدّل. السلسلة دلوقتي: " + ExtTranslate.chainLabel() + "\n(بيأثر على مسار «تفريغ + ترجمة نص» — الأوفلاين والأونلاين بمحرك خارجي)", false) {
                Cfg.put("tx_order", if (ExtTranslate.externalFirst()) "internal" else "external"); stepSummary()
            }
            opt("🌐 سيرفر LibreTranslate: " + (Cfg.str("libre_url").ifBlank { "الافتراضي (" + ExtTranslate.LIBRE_DEFAULT + ")" }), "سيرفرك أو سيرفر عام، ومفتاح لو محتاج. السيرفرات العامة ممكن تطلب مفتاح أو تحط حد. دوس عشان تغيّر.", false) {
                val box = LinearLayout(act); box.orientation = LinearLayout.VERTICAL; box.setPadding(40, 20, 40, 0)
                val eu = android.widget.EditText(act); eu.hint = "https://libretranslate.com"; eu.setText(Cfg.str("libre_url")); eu.setSingleLine(true)
                val ek = android.widget.EditText(act); ek.hint = "مفتاح API (لو محتاج)"; ek.setText(Cfg.str("libre_key")); ek.setSingleLine(true)
                box.addView(eu); box.addView(ek)
                android.app.AlertDialog.Builder(act).setTitle("🌐 LibreTranslate").setView(box)
                    .setPositiveButton("حفظ") { _, _ ->
                        Cfg.put("libre_url", eu.text.toString().trim().trimEnd('/')); Cfg.put("libre_key", ek.text.toString().trim())
                        ExtTranslate.libreProv()?.let { ExtTranslate.clearBlock(it.id) }; stepSummary()
                    }
                    .setNegativeButton("إلغاء", null).show()
            }
            val swapOn = Cfg.str("tr_autoswap", "on") != "off"
            opt("🔄 بديل تلقائي لو جيميناي وقف: " + (if (swapOn) "✅ شغّال" else "⏸ مقفول"), "لو كوتا/مفاتيح جيميناي خلصت أثناء الترجمة، الجمل الناقصة بتتترجم بالمترجم الخارجي أو جوجل (فصحى، واللهجة بتتحوّل بعدين) بدل ما تستنى. دوس عشان تبدّل.", false) {
                Cfg.put("tr_autoswap", if (swapOn) "off" else "on"); stepSummary()
            }
            val tnOn = TermNotes.enabled()
            opt("📖 نبذة عن المصطلحات الطبية: " + (if (tnOn) "✅ شغّال" else "⏸ مقفول"), "لما جملة فيها مصطلح طبي (زي «القوباء» أو «psoriasis») بيظهر سطر شرح قصير فوق الترجمة. قاموس مدمج أوفلاين + ويكيبيديا للمصطلحات اللي مش فيه (بالنت). الشرح تعريفي عام مش تشخيص. دوس عشان تبدّل.", false) {
                Cfg.put("term_notes", if (tnOn) "off" else "on"); stepSummary()
            }
            if (s != null) opt("🗑 امسح المحفوظ", "زرار «▶ ترجمة» هيسألك كل مرة تاني", false) { TrWizard.clearSaved(); Notice.show(act, "اتمسح الإعداد المحفوظ", 2200L); dlg.dismiss() }
            addBack(if (hist.size > 1) "← رجوع" else "إغلاق")
        }


        /** (v266) بريستات الأوفلاين: لغة تلقائية، من غير أسئلة كتير. «✏ مخصص» بيفتح الخطوات التفصيلية */
        fun stepOffPreset() {
            reset("📴 نوع الأوفلاين")
            note("اختار بريست جاهز (اللغة تلقائية). لو الموديل مش نازل هتنزّله مرة واحدة.")
            fun pick(eng: String, mdl: String, meth: String, lv: Boolean, dia: String) {
                presetFlow = true; lang = ""; engine = eng; model = mdl; method = meth; live = lv; dialect = dia
                val ready = Offline.remoteOn() || (if (eng == "sherpa") SherpaStt.ready() else (WhisperLib.loaded && Offline.downloaded(Offline.models.firstOrNull { it.id == mdl } ?: Offline.model())))
                if (ready) go { stepSave() } else go { if (eng == "sherpa") stepSherpa() else stepStt() }
            }
            val fastEng = if (SherpaStt.ready()) "sherpa" else "whisper"
            opt("⚡ لحظي", "الأسرع: باتشات قصيرة (20-30ث) وكل باتش يتفرّغ يتترجم فورًا بجوجل. " + (if (fastEng == "sherpa") "SenseVoice." else "Whisper base (~60 ميجا)."), live && presetFlow && method == "google") { pick(fastEng, "base", "google", true, "") }
            opt("⚖ متوازن", "Whisper small + ترجمة جوجل كل 3 دقايق.", false) { pick("whisper", "small", "google", false, "") }
            if (free.isBlank()) opt("🎯 دقيق", "Whisper small + ترجمة جيميناي بالسياق (ضمائر وأجناس أدق).", false) { pick("whisper", "small", "gemini", false, TrWizard.dialectWanted()) }
            opt("✏ مخصص", "اختار اللغة والمحرك والموديل والترجمة واللهجة بنفسك.", false) { presetFlow = false; live = false; go { stepLang() } }
            addBack()
        }


        /** (v276) فحص المترجمين المجانيين (اللي ليهم محوّل جاهز) وترتيبهم بالسرعة؛ الأخضر الأسرع يتختار */
        fun stepRepo() {
            reset("🌐 مترجمين مجانيين")
            val provs = ExtTranslate.builtin + listOfNotNull(ExtTranslate.libreProv())
            note("قايمة الريبو مفيهاش مفاتيح، فبنجرّب المترجمين اللي ليهم محوّل جاهز في التطبيق (بدون مفتاح). الأسرع الشغّال بيتختار الأول، والباقي بيكمّل لو وقف. التفريغ أوفلاين على جهازك.")
            if (ftested.isEmpty() && !ftesting) {
                ftesting = true
                val left = java.util.concurrent.atomic.AtomicInteger(provs.size)
                for (pv in provs) Thread {
                    val r = ExtTranslate.testTimed(pv)
                    act.runOnUiThread {
                        ftested[pv.id] = r
                        if (left.decrementAndGet() == 0) ftesting = false
                        if (dlg.isShowing && title.text.toString().startsWith("🌐 مترجمين")) stepRepo()
                    }
                }.also { it.isDaemon = true }.start()
            }
            val ordered = provs.sortedBy { pv -> (ftested[pv.id]?.let { if (it.first) it.second else Long.MAX_VALUE - 1 } ?: Long.MAX_VALUE) }
            val okList = ordered.filter { ftested[it.id]?.first == true }
            val best = okList.firstOrNull { it.dailyChars == 0 } ?: okList.firstOrNull()   // (v277) اللي ليه حد يومي (MyMemory ~4800 حرف) مايبقاش «الأسرع» طول ما فيه بديل من غير حد
            fun choose(pv: ExtTranslate.Prov) {
                if (repoToHub) { tr = "free"; trId = pv.id; sync(); toHub(); return }   // (v279) جاي من كارت ②
                if (!WhisperLib.loaded) { Notice.show(act, "الأوفلاين مش مدعوم على جهازك — التفريغ هنا بيبقى على الجهاز", 3500L); return }
                mode = "offline"; free = pv.id; method = "google"; go { stepOffPreset() }
            }
            for (pv in ordered) {
                val t = ftested[pv.id]
                val tag = if (ExtTranslate.inRepo(pv)) " · ✅ في الريبو" else ""
                val st = if (t == null) "⏳ بيتجرّب…" else if (t.first) "🟢 شغّال · " + t.second + "ms" + (if (pv === best) " ⚡ الأسرع" else "") + (if (pv.dailyChars > 0) "\n⚠ حد يومي ~" + pv.dailyChars + " حرف (فيديو واحد ممكن يخلّصه)" else "") + "\n«" + t.third.take(50) + "»" else "🔴 " + t.third
                opt((if (t?.first == true) "🟢 " else if (t == null) "⏳ " else "🔴 ") + pv.label, st + tag, free == pv.id) {
                    if (t?.first == true) choose(pv) else Notice.show(act, if (t == null) "استنى الفحص يخلص" else "المترجم ده مش شغّال دلوقتي", 2500L)
                }
            }
            footBtn("🔄 افحص تاني", true) { if (!ftesting) { ftested.clear(); stepRepo() } }
            if (best != null) footBtn("⚡ اختار الأسرع", true) { choose(best) }
            addBack()
        }

        /** (v275) مفاتيح جيميناي + أوضاعها (كانت زرار 🔑 لوحده في المشغّل) */
        fun stepGemKeys() {
            reset("🔑 مفاتيح جيميناي ووضعها")
            note("أساسي: " + Cfg.keys("keys").size + " · إضافي: " + Cfg.keys("extra").size + " · احتياطي: " + Cfg.keys("backup").size + "\nتعديل/حذف المفاتيح بالتفصيل من الإعدادات ← 🔑 المفاتيح.")
            opt("➕ ضيف مفتاح جيميناي", "إزاي تجيبه + لصقه من الكليبورد", false) {
                act.showKeyGuide { k ->
                    val cur = Cfg.keys("keys")
                    if (k in cur || k in Cfg.keys("extra") || k in Cfg.keys("backup")) Notice.show(act, "⚠ المفتاح ده موجود قبل كده", 2600L)
                    else { Cfg.p.edit().putString("keys", (cur + k).joinToString("\n")).apply(); Notice.show(act, "✓ اتحفظ المفتاح", 2200L) }
                    stepGemKeys()
                }
            }
            note("وضع المفاتيح ثابت: ٧٥٪ من المفاتيح بتنسخ الكلام و٢٥٪ بتترجم بالسياق (لو معاك مفتاح واحد بيعمل الاتنين).")
            addBack()
        }


        fun stepLang() {
            reset("🌍 لغة الفيديو")
            note("اختار لغة الكلام في الفيديو — بيخلّي التفريغ أدق. لو الفيديو بيخلط لغات اختار «تلقائي»، بس التفريغ بيبقى أدق لما المقطع كله بلغة واحدة.")
            for ((id, label) in TrWizard.sttLangs) opt(label, "", lang == id) {
                lang = id
                // (v264) كل لغة SenseVoice بيفهمها (إنجليزي/ياباني/كوري/صيني/تلقائي) بتروح لاختيار المحرك، والباقي على Whisper على طول
                if (SherpaStt.handles(id)) go { stepEngine() }
                else { engine = "whisper"; go { stepStt() } }
            }
            addBack()
        }

        fun stepEngine() {
            reset("🎙 محرك التفريغ")
            note("للغة " + TrWizard.langLabel(lang) + " فيه محركين على الجهاز. التفريغ كله من غير نت (النت بس لتنزيل الموديل مرة واحدة).")
            opt("⭐ SenseVoice (sherpa-onnx)", "بيفهم إنجليزي وياباني وكوري وصيني بس (مش فرنساوي ولا عربي ولا إسباني)، وغالبًا أسرع بكتير من Whisper. موديل ~" + SherpaStt.MODEL_MB + " ميجا.", engine == "sherpa") { engine = "sherpa"; go { stepSherpa() } }
            opt("🎧 Whisper", "الأقدم والمجرّب: بتختار حجم الموديل (base / small / medium).", engine != "sherpa") { engine = "whisper"; go { stepStt() } }
            addBack()
        }

        fun stepSherpa() {
            reset("📴 التفريغ بـ SenseVoice")
            note("بيشتغل على موبايلك (sherpa-onnx). النت بس لتنزيل الموديل مرة واحدة.")
            val st = ui.text("", 13f, th.text).apply { setLineSpacing(0f, 1.15f); setPadding(0, ui.dp(8), 0, ui.dp(2)) }
            val pr = ui.text("", 12f, th.muted)
            val dl = ui.button("", false) { }
            fun refresh() {
                val have = SherpaStt.downloaded()
                st.text = if (!SherpaStt.supported()) SherpaStt.status() else if (Offline.downloading()) "⏳ بينزّل…" else SherpaStt.status()
                dl.text = if (Offline.downloading()) "⏹ إلغاء التنزيل" else if (have) "✓ الموديل نازل" else "⬇ تنزيل (~" + SherpaStt.MODEL_MB + " ميجا)"
                dl.isEnabled = SherpaStt.supported() && (Offline.downloading() || !have)
            }
            dl.setOnClickListener {
                if (Offline.downloading()) Offline.cancelDownload()
                else if (!SherpaStt.downloaded()) {
                    pr.text = "⏳ بيبدأ التنزيل…"
                    Offline.downloadSherpa(
                        { a, t -> act.runOnUiThread { pr.text = "⬇ " + (a / 1048576) + " / " + (t / 1048576) + " ميجا" } },
                        { err -> act.runOnUiThread { pr.text = if (err == null) "✅ الموديل نزل" else "❌ $err"; refresh() } })
                    refresh()
                }
            }
            body.addView(st); body.addView(dl); body.addView(pr)
            refresh()
            footBtn("التالي ←", true) {
                if (!SherpaStt.supported()) Notice.show(act, "جهازك مش بيدعم sherpa-onnx", 2500L)
                else if (!SherpaStt.downloaded()) Notice.show(act, "نزّل الموديل الأول", 2500L)
                else { val a = afterDl; if (a != null) { afterDl = null; a() } else go { if (hubFlow) toHub() else if (presetFlow) stepSave() else stepMethod() } }
            }
            addBack()
        }

        fun stepStt() {
            reset("📴 التفريغ على الجهاز")
            note("Whisper بيشتغل على موبايلك. النت بس لتنزيل الموديل مرة واحدة.")
            val st = ui.text("", 13f, th.text).apply { setLineSpacing(0f, 1.15f); setPadding(0, ui.dp(8), 0, ui.dp(2)) }
            val pr = ui.text("", 12f, th.muted)
            val dl = ui.button("", false) { }
            fun cur() = Offline.models.firstOrNull { it.id == model } ?: Offline.model()
            fun refresh() {
                val m = cur(); val have = Offline.downloaded(m)
                st.text = when {
                    !WhisperLib.loaded -> "⛔ جهازك مش بيدعم الأوفلاين (محتاج معالج 64-bit)"
                    have -> "✅ الموديل نازل وجاهز — " + m.note
                    Offline.downloading() -> "⏳ بينزّل…"
                    else -> "⬇ لازم تنزّل الموديل مرة واحدة (~" + m.approxMb + " ميجا) — " + m.note
                }
                dl.text = if (Offline.downloading()) "⏹ إلغاء التنزيل" else if (have) "✓ الموديل نازل" else "⬇ تنزيل (~" + m.approxMb + " ميجا)"
                dl.isEnabled = WhisperLib.loaded && (Offline.downloading() || !have)
            }
            dl.setOnClickListener {
                val m = cur()
                if (Offline.downloading()) Offline.cancelDownload()
                else if (!Offline.downloaded(m)) {
                    pr.text = "⏳ بيبدأ التنزيل…"
                    Offline.download(m,
                        { a, t -> act.runOnUiThread { pr.text = "⬇ " + (a / 1048576) + " / " + (t / 1048576) + " ميجا" } },
                        { err -> act.runOnUiThread { pr.text = if (err == null) "✅ الموديل نزل" else "❌ $err"; refresh() } })
                    refresh()
                }
            }
            body.addView(ui.chips(Offline.models.map { it.label }, { cur().label }) { l ->
                Offline.models.firstOrNull { m -> m.label == l }?.let { m -> model = m.id }   // (v261) ماتكتبش off_model هنا: الاختيار بيتطبّق في TrWizard.apply بس. قبل كده القفل من غير حفظ كان بيغيّر الموديل الشغّال وسط الترجمة
                pr.text = ""; refresh()
            })
            body.addView(st); body.addView(dl); body.addView(pr)
            refresh()
            footBtn("التالي ←", true) {
                if (!WhisperLib.loaded) Notice.show(act, "جهازك مش بيدعم الأوفلاين", 2500L)
                else if (!Offline.downloaded(cur())) Notice.show(act, "نزّل الموديل الأول", 2500L)
                else { val a = afterDl; if (a != null) { afterDl = null; a() } else go { if (hubFlow) toHub() else if (presetFlow) stepSave() else stepMethod() } }
            }
            addBack()
        }

        fun stepMethod() {
            reset("🔤 الترجمة بعد التفريغ")
            note("النص اتفرّغ على الجهاز — دلوقتي مين يترجمه للعربي؟ (الاتنين محتاجين نت وقت الترجمة)")
            opt("🌍 جوجل (من غير API)", "من غير مفتاح جيميناي خالص (التفريغ والترجمة الاتنين من غير مفتاح) ولا حصة. بيترجم فصحى وكل جملة لوحدها (المذكر والمؤنث والضمائر أضعف). لو اخترت لهجة بعد كده جيميناي بيحوّل ترجمة جوجل ليها.", method == "google") { method = "google"; go { stepDialect() } }
            opt("✨ جيميناي بالسياق", "أدق في الضمائر والأسماء والجنس. بيستهلك من حصة مفاتيح جيميناي.", method == "gemini") { method = "gemini"; go { stepDialect() } }
            addBack()
        }

        fun stepDialect() {
            reset("🗣 لهجة الترجمة")
            note(if (method == "google") "جوجل بيترجم فصحى. لو اخترت لهجة (مصري مثلًا) جيميناي بيحوّل ترجمة جوجل ليها تلقائي — محتاج مفتاح جيميناي. فصحى = من غير تحويل." else "جيميناي بيترجم باللهجة اللي تختارها على طول.")
            for (d in TrWizard.dialects) {
                val v = if (d == "فصحى") "" else d
                opt(d, "", dialect == v || (v.isEmpty() && dialect == "فصحى")) { dialect = v; go { stepSave() } }
            }
            addBack()
        }

        fun stepSave() {
            reset("💾 تحفظ الإعداد ده؟")
            val pl = plan()
            body.addView(ui.text(TrWizard.describe(pl), 14f, th.text, true).apply { setLineSpacing(0f, 1.2f); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(10)) })
            if (edit) {
                opt("💾 أيوه، احفظ", "زرار «▶ ترجمة» هيبدأ بيه على طول", false) { done(pl, true, false); dlg.dismiss() }
                opt("✖ لأ، سيبه زي ما كان", "", false) { dlg.dismiss() }
            } else {
                opt("💾 أيوه، احفظ وابدأ الترجمة", "الضغطة الجاية على «▶ ترجمة» هتبدأ بالإعداد ده على طول — وتغيّره من زرار ⚙ اللي جنبه", false) { done(pl, true, true); dlg.dismiss() }
                opt("▶ لأ، ابدأ المرة دي بس", "", false) { done(pl, false, true); dlg.dismiss() }
            }
            addBack()
        }

        // ===================== (v279) كروت خط الترجمة + بريستات هاي/لو كوتا =====================
        // ===== (v282) 🧪 تجربة كل كارت =====
        val probe = HashMap<String, Pair<Boolean, String>>()
        val probing = HashSet<String>()
        fun onHub(): Boolean = dlg.isShowing && title.text.toString().contains("خط الترجمة")

        /** k: "1" تفريغ · "2" ترجمة · "3" لهجة. بيشتغل في خيط ويحدّث الكروت لما يخلص */
        fun runProbe(k: String) {
            if (!probing.add(k)) return
            probe.remove(k); sync()
            val s = src; val t = tr; val tid = trId; val d = dia; val cv = convId; val mdl = model
            Thread {
                val r: Pair<Boolean, String> = try {
                    when (k) {
                        "1" -> when (s) {
                            "whisper" -> if (!WhisperLib.loaded) false to "جهازك مش بيدعم Whisper" else if (!Offline.downloaded(Offline.models.firstOrNull { it.id == mdl } ?: Offline.model())) false to "الموديل مش نازل — دوس على الكارت وانزّله" else true to "الموديل نازل وجاهز (التفريغ بيشتغل على الجهاز)"
                            "sherpa" -> if (!SherpaStt.supported()) false to "جهازك مش بيدعم SenseVoice" else if (!SherpaStt.downloaded()) false to "الموديل مش نازل — دوس على الكارت وانزّله" else true to "الموديل نازل وجاهز (التفريغ بيشتغل على الجهاز)"
                            "cloud" -> {
                                val p = TrWizard.sttCandidate(if (t == "llm") tid else "")
                                if (p == null) false to "مفيش محرك تفريغ"
                                else { val x = ExtLlm.testTimed(p); x.first to (p.label + " · " + (if (x.first) x.second.toString() + "ms — المفتاح شغّال (التفريغ نفسه ماجرّبتوش)" else x.third)) }
                            }
                            else -> TrWizard.probeGemini()
                        }
                        "2" -> when {
                            t == "none" -> true to "مفيش ترجمة"
                            s == "gemini" -> true to "مدمجة مع ① (اتجرّبت هناك)"
                            t == "google" -> TrWizard.probeGoogle()
                            t == "free" -> { val pv = ExtTranslate.all().firstOrNull { it.id == tid }; if (pv == null) false to "المترجم مش موجود" else { val x = ExtTranslate.testTimed(pv); x.first to (if (x.first) x.second.toString() + "ms «" + x.third.take(40) + "»" else x.third) } }
                            t == "llm" -> { val p = ExtLlm.byId(tid); if (p == null || !ExtLlm.configured(p)) false to "المفتاح مش مضبوط" else { val x = ExtLlm.testTimed(p); x.first to (if (x.first) x.second.toString() + "ms «" + x.third.take(40) + "»" else x.third) } }
                            else -> TrWizard.probeGemini()
                        }
                        else -> when {
                            t == "none" -> true to "مفيش لهجة من غير ترجمة"
                            s == "gemini" || t == "gemini" -> true to "جيميناي بيترجم باللهجة على طول — مفيش محوّل"
                            d.isBlank() -> true to "فصحى — مفيش تحويل"
                            cv.isBlank() -> TrWizard.probeGemini()
                            else -> {
                                val p = ExtLlm.byId(cv)
                                if (p == null || !ExtLlm.configured(p)) false to "مفتاح المحوّل مش مضبوط"
                                else { val t0 = System.currentTimeMillis(); val o = ExtLlm.convert(p, listOf("How are you?"), listOf("كيف حالك؟"), listOf(""), d, "").firstOrNull(); if (o.isNullOrBlank()) false to "ما رجّعش تحويل" else true to ((System.currentTimeMillis() - t0).toString() + "ms «" + o.take(40) + "»") }
                            }
                        }
                    }
                } catch (e: Throwable) { false to (e.message ?: e.javaClass.simpleName).take(90) }
                act.runOnUiThread { probe[k] = r; probing.remove(k); if (onHub()) stepHub() }
            }.also { it.isDaemon = true }.start()
            if (onHub()) stepHub()
        }

        /** لو ② وقف: السلسلة الفعلية اللي بتكمّل (نفس ترتيب ExtTranslate.chain) */
        fun fallbackLine(): String {
            if (src == "gemini" || tr == "none" || tr == "gemini") return ""
            val prim = when (tr) { "free" -> trId; "llm" -> "llm_$trId"; else -> "" }
            val pv = if (prim.isEmpty()) null else ExtTranslate.all().firstOrNull { it.id == prim }
            val rest = ExtTranslate.active().filter { it.id != pv?.id }
            val steps = ArrayList<ExtTranslate.Prov?>()
            pv?.let { steps.add(it) }
            if (ExtTranslate.externalFirst()) { steps.addAll(rest); steps.add(null) } else { steps.add(null); steps.addAll(rest) }
            return "لو ② وقف بيكمّل تلقائي: " + steps.joinToString(" ← ") { ExtTranslate.whoLabel(it) } + (if (TrWizard.hasGeminiKey()) " ← 🔑 جيميناي" else "") + "\nوبيطلعلك إشعار باسم اللي كمّل."
        }

        class QP(val id: String, val label: String, val desc: String, val go: () -> Boolean)

        /** هاي كوتا = مفاتيح جيميناي كتير (الدقة أهم) · لو كوتا = مفاتيح قليلة (وفّر). تلقائي: 3 مفاتيح فأكتر = هاي */
        fun quotaHigh(): Boolean = when (Cfg.str("tr_quota", "auto")) { "high" -> true; "low" -> false; else -> Cfg.allMainKeys().size >= 3 }

        fun normalize() {
            when (src) {
                "gemini" -> if (tr != "none") tr = "gemini"
                else -> if (tr == "") tr = "google"
            }
        }

        /** بيترجم حالة الكروت للمتغيرات اللي plan() بيقراها (نفس الخطط القديمة — من غير تغيير في المحرك) */
        fun sync() {
            when (src) {
                "gemini" -> { mode = "online"; ext = ""; free = ""; onlineId = if (tr == "none") "transcript" else gp; method = "gemini"; live = false }
                "cloud" -> { mode = "online"; ext = if (tr == "none") (if (trId.isNotBlank() && TrWizard.sttCandidate(trId) != null) trId else (TrWizard.sttCandidate("")?.id ?: "")) else trId; free = ""; onlineId = CURRENT; method = "gemini"; live = false }
                else -> {
                    mode = "offline"; ext = ""; engine = src; method = if (tr == "gemini") "gemini" else "google"
                    free = when (tr) { "free" -> trId; "llm" -> "llm_$trId"; else -> "" }
                    dialect = if (tr == "none") "" else dia
                }
            }
        }

        fun toHub() { probe.clear(); val i = hist.indexOf(hubL); if (i >= 0) { while (hist.size > i + 1) hist.removeAt(hist.size - 1); hubL() } else { while (hist.size > 1) hist.removeAt(hist.size - 1); hist[0]() } }   // (v360) كانت بترجع لأول خطوة في السلسلة مش للكروت

        fun srcName(): String = when (src) {
            "gemini" -> "✨ جيميناي"
            "whisper" -> "📴 Whisper " + (Offline.models.firstOrNull { it.id == model }?.label ?: model)
            "sherpa" -> "📴 SenseVoice"
            "cloud" -> "☁ " + (TrWizard.sttCandidate(trId)?.label ?: "Whisper سحابي")
            else -> "؟"
        }
        fun trName(): String = when (tr) {
            "gemini" -> if (src == "gemini") "✨ جيميناي (مدمج)" else "✨ جيميناي بالسياق"
            "google" -> "🏠 جوجل الداخلي"
            "free" -> "🌐 " + (ExtTranslate.all().firstOrNull { it.id == trId }?.label ?: trId)
            "none" -> "✍ بدون ترجمة (تفريغ بس)"
            "llm" -> "🤖 " + (ExtLlm.byId(trId)?.label ?: (if (trId.isBlank()) "اختار محرك" else trId))
            else -> "؟"
        }
        fun diaName(): String = if (tr == "none") "—" else if (dia.isBlank()) "فصحى" else dia
        fun isLocal() = src == "whisper" || src == "sherpa"

        fun localStatus(): String =
            if (src == "sherpa") (if (SherpaStt.downloaded()) "✅ الموديل نازل" else "⬇ لازم تنزّل الموديل")
            else (if (Offline.downloaded(Offline.models.firstOrNull { it.id == model } ?: Offline.model())) "✅ الموديل نازل" else "⬇ لازم تنزّل الموديل")

        /** null = تمام. forStart: بيفحص كمان إن موديل الأوفلاين نازل */
        fun blocker(forStart: Boolean): String? {
            sync()
            when (src) {
                "whisper" -> {
                    if (!WhisperLib.loaded) return "الأوفلاين مش مدعوم على جهازك"
                    if (forStart && !Offline.remoteOn() && !Offline.downloaded(Offline.models.firstOrNull { it.id == model } ?: Offline.model())) return "① نزّل موديل Whisper الأول — دوس على كارت ①"
                }
                "sherpa" -> {
                    if (!SherpaStt.supported()) return "جهازك مش بيدعم SenseVoice"
                    if (forStart && !Offline.remoteOn() && !SherpaStt.ready()) return "① نزّل موديل SenseVoice الأول — دوس على كارت ①"
                }
                "cloud" -> {
                    if (tr == "none") { if (TrWizard.sttCandidate(ext) == null) return "① اختار تفريغ تاني" }
                    else {
                        val p = ExtLlm.byId(trId)
                        if (p == null || !ExtLlm.configured(p) || !ExtLlm.enabled(p)) return "② اختار محرك جاهز (مفتاحه مضبوط)"
                        if (TrWizard.sttCandidate(trId) == null) return "① اختار تفريغ تاني"
                    }
                }
            }
            if (convId.isNotBlank() && dia.isNotBlank() && src != "gemini" && tr != "none" && tr != "gemini") {
                val cp = ExtLlm.byId(convId)
                if (cp == null || !ExtLlm.configured(cp)) return "③ مفتاح محوّل اللهجة (" + TrWizard.convLabel(convId) + ") مش مضبوط"
            }
            if (tr == "llm" && src != "cloud") {
                val p = ExtLlm.byId(trId)
                if (p == null || !ExtLlm.configured(p)) return "② مفتاح المحرك ده مش مضبوط"
            }
            return null
        }

        fun useLocal(mdl: String, sherpaOk: Boolean) {
            live = false; lang = ""; model = mdl
            src = if (sherpaOk && SherpaStt.ready()) "sherpa" else "whisper"
        }

        fun qpresets(hi: Boolean): List<QP> = if (hi) listOf(
            QP("acc", "🎯 دقيق", "جيميناي يسمع ويترجم: مقاطع 30ث + تنقيح + أصوات الخلفية. أدق نتيجة وبياكل كوتا عالية.") { src = "gemini"; gp = "accurate"; true },
            QP("fast", "⚡ سريع", "جيميناي بكل المفاتيح بالتوازي، مقاطع 90ث من غير تنقيح. بياكل كوتا عالية.") { src = "gemini"; gp = "fast"; true },
            QP("full", "👁 شاملة", "صوت بدقة + تنقيح + الوضع البصري للنص اللي في الصورة (بمفتاح الوضع البصري).") { src = "gemini"; gp = "full"; true },
            QP("ctx", "📝 محلي + سياق", "Whisper small على جهازك يفرّغ، وجيميناي يترجم بالسياق (ضمائر وأجناس أدق). كوتا نص بس — أقل بكتير من الصوت.") { useLocal("small", false); tr = "gemini"; true },
            QP("only", "✍ تفريغ بس", "النص الأصلي يظهر على طول من غير أي ترجمة. تفريغ على جهازك — من غير كوتا خالص.") { useLocal("small", false); tr = "none"; true }
        ) else listOf(
            QP("free", "💰 مجاني بالكامل", "تفريغ على الجهاز + ترجمة جوجل من غير مفتاح ولا كوتة. فصحى.") { useLocal("small", false); tr = "google"; dia = ""; diaSet = true; true },
            QP("freeDia", "🗣 مجاني + لهجة", "نفس المجاني، وبعدها اللهجة بتتحوّل بجيميناي (نص بس، كوتا قليلة جدًا).") { useLocal("small", false); tr = "google"; dia = TrWizard.dialectWanted().ifBlank { "مصري" }; diaSet = true; convId = ""; true },
            QP("live", "⚡ لحظي", "باتشات قصيرة، وكل باتش يتفرّغ ويتترجم فورًا بجوجل. من غير كوتا.") { useLocal("base", true); tr = "google"; live = true; dia = ""; diaSet = true; true },
            QP("repo", "🌐 مترجم الريبو", "تفريغ على الجهاز + Microsoft Edge Translate (من غير مفتاح ولا حد يومي معلن).") { useLocal("small", false); tr = "free"; trId = "msedge"; dia = ""; diaSet = true; true },
            QP("only", "✍ تفريغ بس", "النص الأصلي يظهر على طول من غير أي ترجمة. تفريغ على جهازك — من غير كوتا.") { useLocal("small", false); tr = "none"; true }
        )

        /** (v287) ترتيب البريستات بالنتيجة: الجاهز على جهازك الأول (موديل نازل / مفتاح موجود)، والناقص آخر الصف بسبب مكتوب. الترتيب الأصلي بيكسر التعادل */
        fun qScore(id: String): Pair<Int, String> {
            val localOk = try { WhisperLib.loaded && Offline.downloaded(Offline.models.firstOrNull { it.id == "small" } ?: Offline.model()) } catch (_: Throwable) { false }
            val baseOk = try { WhisperLib.loaded && Offline.downloaded(Offline.models.firstOrNull { it.id == "base" } ?: Offline.model()) || (SherpaStt.supported() && SherpaStt.ready()) } catch (_: Throwable) { false }
            val gem = TrWizard.hasGeminiKey()
            return when (id) {
                "acc", "fast", "full" -> if (gem) 100 to "" else 20 to "محتاج مفتاح جيميناي"
                "ctx" -> if (!gem) 20 to "محتاج مفتاح جيميناي" else if (!localOk) 60 to "هتنزّل Whisper small" else 100 to ""
                "free" -> if (localOk) 100 to "" else 60 to "هتنزّل Whisper small"
                "freeDia" -> if (!localOk) 60 to "هتنزّل Whisper small" else if (gem) 90 to "" else 70 to "اللهجة محتاجة مفتاح جيميناي"
                "live" -> if (baseOk) 100 to "" else 60 to "هتنزّل موديل التفريغ"
                "repo" -> if (localOk) 95 to "" else 60 to "هتنزّل Whisper small"
                "only" -> if (localOk) 90 to "" else 60 to "هتنزّل Whisper small"
                else -> 50 to ""
            }
        }

        fun rankQp(list: List<QP>): List<QP> {
            val scored = list.mapIndexed { i, q -> Triple(q, qScore(q.id), i) }
            val sorted = scored.sortedWith(compareByDescending<Triple<QP, Pair<Int, String>, Int>> { it.second.first }.thenBy { it.third })
            val best = sorted.firstOrNull()?.takeIf { it.second.first >= 90 }?.first?.id
            return sorted.map { (q, sc, _) ->
                val why = sc.second
                QP(q.id, (if (q.id == best) "⭐ " else "") + q.label + (if (why.isNotEmpty() && sc.first <= 70) " ⚠" else ""),
                    q.desc + (if (why.isNotEmpty()) "\n⚠ " + why else if (q.id == best) "\n⭐ الأنسب لإعدادك دلوقتي" else ""), q.go)
            }
        }

        fun applyQp(q: QP) {
            if (!q.go()) return
            lastQp = q.id; probe.clear(); normalize(); sync()
            val b = blocker(true)
            if (b != null && isLocal()) {
                Notice.show(act, "⬇ $b", 3200L); hubFlow = true
                go { if (src == "sherpa") stepSherpa() else stepStt() }
                return
            }
            stepHub()
        }

        fun pill(t: String, on: Boolean, f: () -> Unit): View {
            val b = IconTextView(act).apply {
                text = t; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setTextColor(th.text)
                background = ui.box(if (on) ((th.primary and 0x00FFFFFF) or 0x33000000) else th.surface, if (on) th.primary else th.border, 18)
            }
            Glass.pressable(b); b.setOnClickListener { f() }
            return b
        }

        fun card(num: String, title: String, value: String, sub0: String, lock: String, pk: String, f: () -> Unit) {
            val locked = lock.isNotEmpty()
            val pr = probe[pk]
            val sub = sub0 + (if (probing.contains(pk)) "\n🧪 ⏳ بيجرّب…" else if (pr != null) "\n🧪 " + (if (pr.first) "✅ " else "❌ ") + pr.second else "")
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.box(th.surface, th.border, 14)
                alpha = if (locked) 0.6f else 1f
            }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ui.text("$num $title", 12f, th.muted, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(ui.text("🧪", 14f, th.text).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); setOnClickListener { runProbe(pk) } })
            head.addView(ui.text(if (locked) "🔒 $lock" else "تغيير ›", 12f, th.primary, true))
            item.addView(head)
            item.addView(ui.text(value, 15.5f, th.text, true).apply { setPadding(0, ui.dp(4), 0, 0) })
            if (sub.isNotBlank()) item.addView(ui.text(sub, 11.5f, th.muted).apply { setLineSpacing(0f, 1.1f); setPadding(0, ui.dp(3), 0, 0) })
            Glass.pressable(item); item.setOnClickListener { f() }
            body.addView(item, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(2), 0, ui.dp(2)) })
        }


        /** (v297) الكروت ① ② ③ جنب بعض في صف واحد (بالترتيب من اليمين للشمال) بدل ما كانت تحت بعض */
        var cardsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun miniCard(num: String, title: String, value: String, sub0: String, lock: String, pk: String, f: () -> Unit) {
            val locked = lock.isNotEmpty()
            val pr = probe[pk]
            val sub = sub0 + (if (probing.contains(pk)) "\n🧪 ⏳" else if (pr != null) "\n🧪 " + (if (pr.first) "✅ " else "❌ ") + pr.second else "")
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); background = ui.box(th.surface, th.border, 14)
                alpha = if (locked) 0.6f else 1f
            }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ui.text("$num $title", 12f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(ui.text("🧪", 13f, th.text).apply { setPadding(ui.dp(6), ui.dp(2), ui.dp(2), ui.dp(2)); setOnClickListener { runProbe(pk) } })
            item.addView(head)
            item.addView(ui.text(value, 13.5f, th.text, true).apply { setPadding(0, ui.dp(5), 0, 0) })
            if (locked) item.addView(ui.text("🔒 $lock", 10.5f, th.primary, true).apply { setPadding(0, ui.dp(3), 0, 0) })
            if (sub.isNotBlank()) item.addView(ui.text(sub, 10.5f, th.muted).apply { setLineSpacing(0f, 1.1f); setPadding(0, ui.dp(4), 0, 0) })
            Glass.pressable(item); item.setOnClickListener { f() }
            if (cardsRow.childCount > 0) cardsRow.addView(ui.text("‹", 18f, th.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(14), -1))
            cardsRow.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }

        fun footSm(t: String, main: Boolean, f: () -> Unit) {
            val b = IconTextView(act).apply {
                text = t; textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
                setTextColor(if (main) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
                background = if (main) Glass.primaryFill(th, ui.dp(12).toFloat()) else ui.box(th.surface, th.border, 12)
            }
            Glass.pressable(b); b.setOnClickListener { f() }
            foot.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }

        fun stepHub() {
            hubFlow = false; repoToHub = false; hubActive = true
            sync()
            reset(if (edit) "⚙ خط الترجمة" else "▶ خط الترجمة")
            val hi = quotaHigh()
            val auto = Cfg.str("tr_quota", "auto") == "auto"
            note("اختار بريست جاهز على حسب كوتا جيميناي عندك، أو اظبط الكروت بنفسك. عندك " + Cfg.allMainKeys().size + " مفتاح جيميناي.")
            // تقسيمة الكوتا
            val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            seg.addView(pill("🔥 هاي كوتا", hi && !auto) { Cfg.put("tr_quota", "high"); lastQp = ""; stepHub() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) })
            seg.addView(pill("🪫 لو كوتا", !hi && !auto) { Cfg.put("tr_quota", "low"); lastQp = ""; stepHub() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) })
            seg.addView(pill("🔄 تلقائي" + if (auto) (if (hi) " (هاي)" else " (لو)") else "", auto) { Cfg.put("tr_quota", "auto"); lastQp = ""; stepHub() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) })
            body.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
            // البريستات
            val list = rankQp(qpresets(hi))   // (v287) مترتبة بالجاهزية
            val hs = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            for (q in list) row.addView(pill(q.label, q.id == lastQp) { applyQp(q) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            hs.addView(row)
            body.addView(hs, LinearLayout.LayoutParams(-1, -2))
            body.addView(ui.text(list.firstOrNull { it.id == lastQp }?.desc ?: (if (hi) "هاي كوتا: الدقة والسرعة أهم من توفير الكوتا — دوس على بريست." else "لو كوتا: توفير الكوتا أهم — التفريغ والترجمة على الجهاز أو بمفاتيح خارجية. دوس على بريست."), 12f, th.muted).apply { setLineSpacing(0f, 1.15f); setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(8)) })
            // شريط الملخص
            body.addView(ui.text("① " + srcName() + "  ←  ② " + trName() + "  ←  ③ " + diaName(), 12.5f, th.text, true).apply {
                setLineSpacing(0f, 1.2f); gravity = Gravity.CENTER
                setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.box((th.primary and 0x00FFFFFF) or 0x1A000000, th.border, 12)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            // الكروت
            cardsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            miniCard("①", "تفريغ", srcName(),
                (when (src) { "gemini" -> (if (tr == "none") "💸 كوتا جيميناي (تفريغ بس — أقل من الترجمة)" else "💸💸 كوتا جيميناي عالية (الصوت كله بيتبعت له)"); "cloud" -> "🔑 كوتا مفتاحك الخارجي · الصوت بيتبعت لخدمة خارجية"; else -> "🆓 على جهازك من غير كوتا · " + localStatus() + " · لغة: " + TrWizard.langLabel(lang) }), "", "1") { go { stepCardStt() } }
            miniCard("②", "ترجمة", trName(), if (tr == "none") "النص الأصلي بيظهر من غير ترجمة" else if (src == "gemini") "مدمج مع ① — جيميناي بيسمع ويترجم في طلب واحد" else (when (tr) { "gemini" -> "💸 كوتا جيميناي (نص بس)"; "llm" -> "🔑 كوتا مفتاحك"; else -> "🆓 مجاني" }), "", "2") { go { stepCardTr() } }
            miniCard("③", "لهجة", diaName(),
                if (tr == "none") "مفيش ترجمة فمفيش لهجة" else if (src == "gemini") "جيميناي بيترجم باللهجة دي على طول" else if (dia.isBlank()) "من غير تحويل — الترجمة فصحى" else if (tr == "gemini") "جيميناي بيترجم باللهجة دي على طول" else "التحويل بـ " + TrWizard.convLabel(convId) + " (نص بس)" + (if (convId.isBlank() && !TrWizard.hasGeminiKey()) " · ⚠ محتاج مفتاح جيميناي" else ""), if (tr == "none") "من غير ترجمة" else "", "3") { if (tr == "none") Notice.show(act, "③ مفيش لهجة من غير ترجمة — اختار مترجم في ②", 2800L) else go { stepCardDia() } }
            body.addView(cardsRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(2), 0, ui.dp(4)) })
            fallbackLine().takeIf { it.isNotEmpty() }?.let { body.addView(ui.text(it, 11.5f, th.muted).apply { setLineSpacing(0f, 1.15f); setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(4)) }) }
            opt("🧪 جرّب الخط كله", "بيجرّب كل كارت بطلب صغير حقيقي ويعرض الزمن أو سبب الفشل — جيميناي بياخد طلب واحد بس. أو دوس 🧪 على كارت لوحده.", false) { runProbe("1"); runProbe("2"); runProbe("3") }
            opt("⋯ أكتر", "ترتيب الترجمة · الإعداد المحفوظ · الخطوات القديمة (خطوة خطوة)", false) { go { stepSummary() } }
            // الأزرار
            footSm("إغلاق", false) { dlg.dismiss() }
            if (edit) footSm("💾 احفظ", true) {
                val b = blocker(false)
                if (b != null) Notice.show(act, "⚠ $b", 3200L) else { done(plan(), true, false); dlg.dismiss() }
            } else {
                footSm("▶ المرة دي", false) {
                    val b = blocker(true)
                    if (b != null) Notice.show(act, "⚠ $b", 3200L) else { done(plan(), false, true); dlg.dismiss() }
                }
                footSm("💾 احفظ وابدأ", true) {
                    val b = blocker(true)
                    if (b != null) Notice.show(act, "⚠ $b", 3200L) else { done(plan(), true, true); dlg.dismiss() }
                }
            }
        }

        /** ① التفريغ: الاختيارات بتترتّب حسب الكوتا (هاي: جيميناي الأول · لو: اللي على الجهاز الأول) */
        fun stepCardStt() {
            reset("① التفريغ — مين يسمع الكلام؟")
            val hi = quotaHigh()
            note("اختيارك هنا بيحدد اختيارات ② الترجمة. 🆓 من غير كوتا · 💸 بياكل كوتا جيميناي · 🔑 كوتا مفتاحك. الترتيب حسب " + (if (hi) "هاي كوتا" else "لو كوتا") + ".")
            fun gemOpt() = opt("✨ جيميناي (يسمع ويترجم في طلب واحد)", "💸💸 كوتا عالية · أدق في الضمائر والأصوات · ② و③ بيتدمجوا فيه", src == "gemini") {
                src = "gemini"; live = false; normalize(); sync(); go { stepCardGem() }
            }
            fun whisperOpt() = opt("📴 Whisper على الجهاز", "🆓 " + (if (WhisperLib.loaded) "بتختار الحجم وبتنزّله مرة واحدة · كل اللغات" else "⛔ جهازك مش بيدعمه"), src == "whisper") {
                if (!WhisperLib.loaded) Notice.show(act, "الأوفلاين مش مدعوم على جهازك", 2500L)
                else { src = "whisper"; engine = "whisper"; live = false; normalize(); sync(); hubFlow = true; go { stepStt() } }
            }
            fun sherpaOpt() = opt("📴 SenseVoice على الجهاز", "🆓 أسرع غالبًا · إنجليزي وياباني وكوري وصيني بس · موديل ~" + SherpaStt.MODEL_MB + " ميجا", src == "sherpa") {
                if (!SherpaStt.supported()) Notice.show(act, "جهازك مش بيدعم sherpa-onnx", 2500L)
                else { src = "sherpa"; engine = "sherpa"; live = false; if (!SherpaStt.handles(lang)) lang = ""; normalize(); sync(); hubFlow = true; go { stepSherpa() } }
            }
            if (hi) { gemOpt(); whisperOpt(); sherpaOpt() }
            else { whisperOpt(); sherpaOpt(); note("⬇ اختيارات بتاكل كوتا:"); gemOpt() }
            if (isLocal()) opt("🌍 لغة الفيديو: " + TrWizard.langLabel(lang), "بيخلّي التفريغ أدق", false) { go { stepCardLang() } }
            footBtn("تمام ✓", true) { toHub() }
            addBack()
        }

        fun stepCardLang() {
            reset("🌍 لغة الفيديو")
            note("«تلقائي» بيكتشف اللغة، بس التفريغ أدق لما المقطع كله بلغة واحدة.\nلو غيّرت اللغة والفيديو شغّال: بيكمّل من مكانك وبيعيد الباتشات اللي اتفرّغت بلغة غلط.")
            TrWizard.langLine().takeIf { it.isNotBlank() }?.let { note(it) }
            opt("⬇ تنزيل موديل الترجمة للغة المختارة", "ML Kit ~30 ميجا، مرة واحدة — بيشتغل بعدها من غير نت", false) { MlKitTr.downloadAsync(act, lang) }
            for ((id, label) in TrWizard.sttLangs) {
                val sherpaNo = src == "sherpa" && !SherpaStt.handles(id)
                opt(label, if (sherpaNo) "⚠ SenseVoice مابيفهمهاش — هيتحوّل لـ Whisper" else "", lang == id) {
                    lang = id
                    if (sherpaNo) { src = "whisper"; engine = "whisper"; normalize(); sync() }
                    back()
                }
            }
            addBack()
        }

        fun stepCardGem() {
            reset("✨ نوع ترجمة جيميناي")
            note("جيميناي بيسمع ويترجم. اختار النمط — بيظبط المقاطع والتنقيح وتوزيع المفاتيح.")
            opt("🔑 مفاتيح جيميناي ووضعها", Cfg.keys("keys").size.toString() + " مفتاح", false) { go { stepGemKeys() } }
            for (pr in TrPresets.all) opt(pr.icon + " " + pr.name, pr.desc, gp == pr.id) { gp = pr.id; src = "gemini"; normalize(); sync(); toHub() }
            opt("⚙ الإعدادات الحالية", "من غير ما أغيّر أي إعداد", gp == CURRENT) { gp = CURRENT; src = "gemini"; normalize(); sync(); toHub() }
            addBack()
        }

        /** ② الترجمة: الاختيارات بتتحدد بنوع التفريغ ①، وبتترتّب حسب الكوتا */
        fun stepCardTr() {
            reset("② الترجمة — مين يترجم؟")
            val hi = quotaHigh()
            fun noneOpt() = opt("✍ بدون ترجمة (تفريغ بس)", "النص الأصلي يظهر بلغته من غير أي ترجمة · " + (if (src == "gemini") "💸 كوتا جيميناي أقل من الترجمة" else if (src == "cloud") "🔑 كوتا التفريغ بس" else "🆓 من غير كوتا") + "\nلو عايز تترجمه بعدين اختار خطة ترجمة — الجمل الأجنبية بتتصلّح بجيميناي", tr == "none") { tr = "none"; trId = ""; sync(); toHub() }
            if (src == "gemini") {
                note("جيميناي بيسمع ويترجم في طلب واحد. لو عايز مترجم تاني غيّر ① لتفريغ على الجهاز أو سحابي.")
                opt("✨ مدمج في جيميناي (يسمع ويترجم)", "💸💸 كوتا عالية · أدق في الضمائر والأصوات", tr == "gemini") { tr = "gemini"; sync(); toHub() }
                noneOpt()
                opt("↩ غيّر ① التفريغ", "", false) { toHub(); go { stepCardStt() } }
                addBack(); return
            }
            note("الاختيارات الـ🆓 من غير كوتا. الترتيب حسب " + (if (hi) "هاي كوتا" else "لو كوتا") + ".")
            fun gemOpt() = opt("✨ جيميناي بالسياق", "💸 كوتا جيميناي (نص بس) · أدق في الضمائر والأسماء والجنس", tr == "gemini") { tr = "gemini"; trId = ""; sync(); toHub() }
            fun googleOpt() = opt("🏠 جوجل الداخلي", "🆓 من غير مفتاح · فصحى جملة جملة · ③ لهجة بتتحوّل بجيميناي", tr == "google") { tr = "google"; trId = ""; sync(); toHub() }
            fun repoOpt() = opt("🌐 مجاني من الريبو", "🆓 Microsoft Edge · LibreTranslate — بتفحصهم وتختار الأسرع" + (if (tr == "free") "\nالحالي: " + trName() else ""), tr == "free") { repoToHub = true; go { stepRepo() } }
            if (hi) { gemOpt(); googleOpt(); repoOpt() }
            else { googleOpt(); repoOpt(); note("⬇ اختيار بياكل كوتا:"); gemOpt() }
            noneOpt()
            addBack()
        }

        fun stepCardDia() {
            reset("③ لهجة الترجمة")
            note(when {
                src == "gemini" -> "جيميناي بيترجم باللهجة اللي تختارها على طول."
                tr == "gemini" -> "جيميناي بيترجم باللهجة اللي تختارها على طول."
                else -> "المترجم ده بيطلّع فصحى. لو اخترت لهجة بيحوّل جيميناي النص ليها بعد الترجمة (نص بس — كوتا قليلة). فصحى = من غير تحويل."
            } + (if (convId.isBlank() && src != "gemini" && tr != "gemini" && !TrWizard.hasGeminiKey()) "\n⚠ مفيش مفتاح جيميناي — ضيف مفتاح." else ""))
            for (d in TrWizard.dialects) {
                val v = if (d == "فصحى") "" else d
                opt(d, "", dia == v) { dia = v; diaSet = true; sync(); toHub() }
            }
            addBack()
        }

        fun start() {
            val dm = act.resources.displayMetrics
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(12)); background = ui.box(th.card, th.border, 22)
            }
            root.addView(title, LinearLayout.LayoutParams(-1, -2))
            root.addView(MaxHeightScroll(act, (dm.heightPixels * 0.62f).toInt()).apply { addView(body) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            root.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            dlg.setContentView(root)
            dlg.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(minOf((dm.widthPixels * 0.92f).toInt(), ui.dp(430)), WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
            }
            dlg.onBack = { if (hist.size > 1) { back(); true } else false }
            go { if (entry == "gem") stepOnlineQ() else stepModes() }   // (v427) (v360) الأوضاع المبسّطة؛ الخط الكامل (v291/v279) من «⋯ متقدّم»  ·  (v291) ⓪ المصدر ← (v279) شاشة الكروت (الخطوات القديمة من «⋯ أكتر»)
            dlg.show()
        }
    }
}
