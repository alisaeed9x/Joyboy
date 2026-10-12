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
    class Plan(val mode: String, val onlineId: String, val lang: String, val model: String, val method: String, val engine: String = "whisper", val dialect: String = "") {
        val offline: Boolean get() = mode == "offline"
    }

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

    const val NO_NET_MSG = "📵 مفيش نت — ترجمة جوجل محتاجة اتصال بالنت عشان تترجم، اتصل بالنت"
    const val NO_NET_GEMINI = "📵 مفيش نت — جيميناي محتاج اتصال بالنت عشان يترجم، اتصل بالنت"

    /**
     * إشعارات قبل بدء الترجمة بالخطة دي (قايمة فاضية = مفيش حاجة تتنبّه عليها).
     * جوجل + فصحى: إشعار إنها ترجمة جوجل. جوجل + لهجة (مصري…): مفيش إشعار — جيميناي بيحوّل ترجمة جوجل تلقائي؛ إلا لو مفيش مفتاح (هتفضل فصحى).
     * مفيش نت: بيقولك تتصل (جوجل وجيميناي الاتنين محتاجين نت للترجمة).
     */
    fun startNotices(pl: Plan): List<String> {
        if (!pl.offline) return emptyList()
        val out = ArrayList<String>()
        val d = pl.dialect.trim().let { if (it == "فصحى") "" else it }
        if (pl.method == "google") {
            if (d.isEmpty()) out.add("🌍 الترجمة بجوجل (فصحى، من غير API)")
            else if (!hasGeminiKey()) out.add("⚠ لهجة «$d» محتاجة مفتاح جيميناي يحوّل ترجمة جوجل — من غير مفتاح هتفضل فصحى")
            if (!online()) out.add(NO_NET_MSG)
        } else if (!online()) out.add(NO_NET_GEMINI)
        return out
    }

    fun saved(): Boolean = Cfg.bool("tr_saved", false)
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
            "offline" -> Plan("offline", "", Cfg.str("tr_s_lang", ""), Cfg.str("tr_s_model", "small").ifBlank { "small" }, if (Cfg.str("tr_s_method", "gemini") == "google") "google" else "gemini", if (Cfg.str("tr_s_engine", "whisper") == "sherpa") "sherpa" else "whisper", Cfg.str("tr_s_dialect", ""))
            "online" -> Plan("online", Cfg.str("tr_online", CURRENT).ifBlank { CURRENT }, "", "", "gemini")
            else -> null
        }
    }

    fun describe(pl: Plan): String =
        if (pl.offline) "📴 أوفلاين · " + langLabel(pl.lang) + "\nالتفريغ: " + (if (pl.engine == "sherpa") "SenseVoice (sherpa-onnx)" else "Whisper " + pl.model) + " على الجهاز\nالترجمة: " + (if (pl.method == "google") "جوجل (من غير API)" else "جيميناي بالسياق") + "\nاللهجة: " + pl.dialect.ifBlank { "فصحى" } + (if (pl.method == "google" && pl.dialect.isNotBlank() && pl.dialect != "فصحى") " (جيميناي بيحوّل ترجمة جوجل)" else "")
        else "☁ أونلاين (جيميناي)\n" + (TrPresets.byId(pl.onlineId)?.let { it.icon + " " + it.name } ?: "⚙ الإعدادات الحالية")

    /** بيكتب الإعدادات اللي المحرك بيقراها (من غير ما يحفظ الخطة) */
    fun apply(pl: Plan) {
        Cfg.put("stt_offline", if (pl.offline) "1" else "0")
        if (pl.offline) { Cfg.put("stt_lang", pl.lang); Cfg.put("off_model", pl.model); Cfg.put("tr_method", pl.method); Cfg.put("stt_engine", pl.engine); Cfg.put("conv_dialect", if (pl.dialect == "فصحى") "" else pl.dialect) }
        else Cfg.put("tr_method", "gemini")
    }

    fun save(pl: Plan) {
        Cfg.put("tr_mode", pl.mode)
        if (pl.offline) { Cfg.put("tr_s_lang", pl.lang); Cfg.put("tr_s_model", pl.model); Cfg.put("tr_s_method", pl.method); Cfg.put("tr_s_engine", pl.engine); Cfg.put("tr_s_dialect", pl.dialect) }
        else Cfg.put("tr_online", pl.onlineId)
        Cfg.put("tr_saved", "1")
    }

    fun clearSaved() { Cfg.put("tr_saved", "0") }

    /** (v261) تغيير من الإعدادات (لغة التفريغ / الموديل / المحرك / طريقة الترجمة) بيتكتب كمان في الخطة المحفوظة، وإلا «▶ ترجمة» كان بيكتب القديم فوقه */
    fun syncSaved(key: String, v: String) {
        if (!saved() || Cfg.str("tr_mode", "") != "offline") return
        when (key) {
            "stt_lang" -> { Cfg.put("tr_s_lang", v); if (!SherpaStt.handles(v)) Cfg.put("tr_s_engine", "whisper") }
            "off_model" -> Cfg.put("tr_s_model", v)
            "stt_engine" -> Cfg.put("tr_s_engine", v)
            "tr_method" -> Cfg.put("tr_s_method", v)
        }
    }

    /** [onDone] (الخطة، احفظ؟، ابدأ ترجمة؟) — بتتنده مرة واحدة لما تخلّص الخطوات */
    fun show(act: Activity, edit: Boolean, onDone: (Plan, Boolean, Boolean) -> Unit) { Wiz(act, edit, onDone).start() }

    private class Wiz(val act: Activity, val edit: Boolean, val onDone: (Plan, Boolean, Boolean) -> Unit) {
        val th = Glass.theme()
        val ui = Ui(act, th)
        val dlg = GDialog(act)
        val title = ui.text("", 17f, th.primary, true)
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val foot = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val hist = ArrayList<() -> Unit>()

        // المسودة: بتبدأ من اللي شغّال دلوقتي (أو المحفوظ لو فيه)
        val sp = TrWizard.savedPlan()
        var mode = sp?.mode ?: if (Cfg.bool("stt_offline", false)) "offline" else "online"
        var onlineId = Cfg.str("tr_online", CURRENT).ifBlank { CURRENT }
        var lang = sp?.takeIf { it.offline }?.lang ?: Offline.lang()
        var model = Offline.model().id
        var method = TrWizard.method()
        var engine = sp?.takeIf { it.offline }?.engine ?: (if (Cfg.str("stt_engine", "whisper") == "sherpa") "sherpa" else "whisper")

        var dialect = sp?.takeIf { it.offline }?.dialect ?: TrWizard.dialectWanted()
        fun plan() = Plan(mode, onlineId, lang, model, method, engine, dialect)

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
        fun stepSummary() {
            reset("⚙ طريقة الترجمة")
            val s = TrWizard.savedPlan()
            note(if (s != null) "الإعداد المحفوظ دلوقتي:\n" + TrWizard.describe(s) + "\n\nزرار «▶ ترجمة» بيبدأ بيه على طول." else "مفيش إعداد محفوظ — زرار «▶ ترجمة» هيسألك كل مرة.")
            opt("✏ غيّر الإعداد", "", false) { go { stepMode() } }
            if (s != null) opt("🗑 امسح المحفوظ", "زرار «▶ ترجمة» هيسألك كل مرة تاني", false) { TrWizard.clearSaved(); Notice.show(act, "اتمسح الإعداد المحفوظ", 2200L); dlg.dismiss() }
            addBack("إغلاق")
        }

        fun stepMode() {
            reset(if (edit) "✏ اختار طريقة الترجمة" else "▶ هتترجم إزاي؟")
            opt("📴 أوفلاين", if (WhisperLib.loaded) "الكلام بيتفرّغ على جهازك من غير نت ولا مفتاح، وبعدها الترجمة بجوجل (من غير API) أو جيميناي" else Offline.status(), mode == "offline") {
                if (!WhisperLib.loaded) Notice.show(act, "الأوفلاين مش مدعوم على جهازك", 2500L) else { mode = "offline"; go { stepLang() } }
            }
            opt("☁ أونلاين (جيميناي)", "الصوت بيتبعت لجيميناي — بتختار نوع الترجمة (سريعة / دقيقة / …)", mode == "online") { mode = "online"; go { stepOnline() } }
            addBack(if (hist.size > 1) "← رجوع" else "إغلاق")
        }

        fun stepOnline() {
            reset("☁ نوع الترجمة")
            for (pr in TrPresets.all) opt(pr.icon + " " + pr.name, pr.desc, onlineId == pr.id) { onlineId = pr.id; go { stepSave() } }
            opt("⚙ الإعدادات الحالية", "من غير ما أغيّر أي إعداد", onlineId == CURRENT) { onlineId = CURRENT; go { stepSave() } }
            addBack()
        }

        fun stepLang() {
            reset("🌍 لغة الفيديو")
            note("اختار لغة الكلام في الفيديو — بيخلّي التفريغ أدق. لو الفيديو بيخلط لغات اختار «تلقائي»، بس التفريغ بيبقى أدق لما المقطع كله بلغة واحدة.")
            for ((id, label) in TrWizard.sttLangs) opt(label, "", lang == id) {
                lang = id
                if (SherpaStt.handles(id)) go { stepEngine() }   // (v263) كل لغة SenseVoice بيفهمها (إنجليزي/ياباني/كوري/صيني/تلقائي) — كانت ياباني/كوري/صيني بس فمحرك SenseVoice مكنش بيظهر للإنجليزي else { engine = "whisper"; go { stepStt() } }
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
                else go { stepMethod() }
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
                else go { stepMethod() }
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
                opt("💾 أيوه، احفظ", "زرار «▶ ترجمة» هيبدأ بيه على طول", false) { onDone(pl, true, false); dlg.dismiss() }
                opt("✖ لأ، سيبه زي ما كان", "", false) { dlg.dismiss() }
            } else {
                opt("💾 أيوه، احفظ وابدأ الترجمة", "الضغطة الجاية على «▶ ترجمة» هتبدأ بالإعداد ده على طول — وتغيّره من زرار ⚙ اللي جنبه", false) { onDone(pl, true, true); dlg.dismiss() }
                opt("▶ لأ، ابدأ المرة دي بس", "", false) { onDone(pl, false, true); dlg.dismiss() }
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
            if (edit) go { stepSummary() } else go { stepMode() }
            dlg.show()
        }
    }
}
