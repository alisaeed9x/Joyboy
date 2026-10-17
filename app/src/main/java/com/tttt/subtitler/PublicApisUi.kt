package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * (v272/v273) قسم الإعدادات «🌐 APIs خارجية»: حالة سحب قايمة public-apis (بتتحدّث لوحدها)، زرار سحب يدوي،
 * ترتيب الترجمة (الداخلي أولًا / الخارجي أولًا)، المزوّدين المجانيين، محركات LLM بمفتاحك (ChatGPT/Claude/…)،
 * والتفريغ السحابي. كل مزوّد بيتعلّم لو موجود في القايمة المسحوبة من الريبو.
 */
object PublicApisUi {
    @Volatile private var omniListCache: List<String> = emptyList()
    @Volatile private var omniListAt = 0L

    /** (v373) قايمة صغيرة منسدلة (مش شاشة كاملة): [autoLabel] فوق (بترجّع "") وبعدها [items]. اللي عليه ✓ هو الحالي */
    fun dropdown(act: Activity, ui: Ui, th: Theme, anchor: View, items: List<String>, current: String, autoLabel: String, footer: Pair<String, () -> Unit>? = null, onPick: (String) -> Unit) {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(th.card, th.border, 14)
        }
        lateinit var pw: android.widget.PopupWindow
        fun row(label: String, on: Boolean, v: String) {
            col.addView(IconTextView(act).apply {
                text = (if (on) "✓ " else "") + label; textSize = 14f; setTextColor(if (on) th.primary else th.text)
                minHeight = ui.dp(40); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
                setOnClickListener { pw.dismiss(); onPick(v) }
            })
        }
        row(autoLabel, current.isBlank(), "")
        for (m in items) row(m, m == current, m)
        footer?.let { f -> col.addView(IconTextView(act).apply { text = f.first; textSize = 13f; setTextColor(th.primary); minHeight = ui.dp(40); gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START; setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); setOnClickListener { pw.dismiss(); f.second() } }) }
        val scroll = MaxHeightScroll(act, (act.resources.displayMetrics.heightPixels * 0.45f).toInt()).apply { addView(col) }
        pw = android.widget.PopupWindow(scroll, anchor.width.coerceAtLeast(ui.dp(240)), -2, true)
        pw.setBackgroundDrawable(ColorDrawable(0)); pw.elevation = ui.dp(8).toFloat()
        try { pw.showAsDropDown(anchor, 0, ui.dp(2)) } catch (_: Throwable) {}
    }

    /** (v373) موديلات OmniRoute (شات بس) — مخزّنة 10 دقايق؛ [onDone] على الـ UI: (القايمة، رسالة خطأ أو null) */
    private fun omniModels(act: Activity, force: Boolean, onDone: (List<String>, String?) -> Unit) {
        val p = ExtLlm.byId("omniroute")!!
        if (!force && omniListCache.isNotEmpty() && System.currentTimeMillis() - omniListAt < 600_000L) { onDone(omniListCache, null); return }
        Thread {
            val r = runCatching { OmniPick.list() }   // (v377) موديلات حقيقية بس (من غير auto/*)، ومن الراوتر الداخلي لو السيرفر مش شغّال
            act.runOnUiThread {
                val l = r.getOrNull()
                if (l == null) onDone(emptyList(), (r.exceptionOrNull()?.message ?: "فشل").take(80))
                else { omniListCache = l; omniListAt = System.currentTimeMillis(); onDone(l, if (l.isEmpty()) "القايمة فاضية" else null) }
            }
        }.start()
    }

    /** (v379) 🆓 قسم أوبن كود في المفاتيح: مفتاح (زي جيميناي) + اختيار الموديل + تجربة */
    fun ocSection(act: Activity, ui: Ui, th: Theme): LinearLayout {
        val p = ExtLlm.byId(OpenCodeFree.ID)!!
        val st = ui.text("", 12.5f, th.text)
        val key = ui.input("مفتاح أوبن كود (من opencode.ai)", Cfg.str("llm_opencode_key"), 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        lateinit var modelBtn: IconButton
        fun curModel(): String = Cfg.str("llm_opencode_model").trim().let { if (it.equals("auto", true)) "" else it }
        fun label(note: String = "") { modelBtn.text = "🎯 موديل أوبن كود: " + curModel().ifBlank { "تلقائي" } + " ▾" + note }
        fun hasKey() = Cfg.str("llm_opencode_key").trim().isNotEmpty() || OpenCodeFree.BUILTIN_KEY.isNotEmpty()
        fun refresh() { st.text = (if (hasKey()) "✅ المفتاح محفوظ" else "🔓 من غير مفتاح — أوبن كود ممكن يرفض الطلب المجهول") + " · الموديل: " + curModel().ifBlank { "تلقائي (أول موديل مجاني شغّال)" } }
        fun saveKey() { Cfg.put("llm_opencode_key", key.text.toString().trim()); OpenCodeFree.resetCool(); refresh() }
        modelBtn = ui.button("") {
            modelBtn.text = "⏳ بجيب الموديلات المجانية…"
            Thread {
                val r = runCatching { OpenCodeFree.list(true) }
                act.runOnUiThread {
                    label()
                    val l = r.getOrNull().orEmpty()
                    if (l.isEmpty()) { label("  ⚠ القايمة فاضية"); return@runOnUiThread }
                    dropdown(act, ui, th, modelBtn, l, curModel(), "تلقائي (أول موديل مجاني شغّال + بدايل)") { v ->
                        Cfg.put("llm_opencode_model", v.ifBlank { "auto" }); OpenCodeFree.resetCool(); label(); refresh()
                    }
                }
            }.start()
        }
        label(); refresh()
        return ui.section("🆓 أوبن كود (موديلات مجانية)", false,
            st, key,
            ui.button("💾 حفظ المفتاح", true) { saveKey(); Notice.show(act, "✅ اتحفظ مفتاح أوبن كود", 2200L) },
            modelBtn,
            ui.button("🧪 جرّب (يبعت جملة واحدة)") {
                saveKey(); Notice.show(act, "⏳ بجرّب أوبن كود…", 1500L)
                Thread { val r = ExtLlm.test(p); act.runOnUiThread { Notice.show(act, r, 6500L) } }.start()
            },
            ui.button("🗑 مسح المفتاح") { key.setText(""); saveKey() },
            ui.text("الموديلات المجانية بتتجاب لوحدها وبتتحدّث. لو الموديل المختار وقع بيتحوّل لموديل مجاني تاني. الوضع بتاعه (تفريغ محلي + ترجمة أوبن كود) في ▶ ترجمة ← 🆓 أوبن كود.", 11.5f, th.muted))
    }

    /** (v372) قسم الإعدادات الوحيد المتبقي لمحركات الـ LLM: OmniRoute (باقي المزوّدين من شاشة 🤖 الذكاء). (v373) + اختيار موديل الترجمة من قايمة منسدلة */
    fun omniSection(act: Activity, ui: Ui, th: Theme): LinearLayout {
        val p = ExtLlm.byId("omniroute")!!
        val st = ui.text("", 12.5f, th.text)
        lateinit var modelBtn: IconButton
        fun curModel(): String = Cfg.str("llm_omniroute_model").trim().let { if (it.equals("auto", true)) "" else it }
        fun label(note: String = "") { modelBtn.text = "🎯 موديل الترجمة: " + curModel().ifBlank { "تلقائي" } + " ▾" + note }
        fun refresh() { st.text = if (ExtLlm.configured(p)) "✅ جاهز · " + ExtLlm.model(p) else "🔌 مش مضبوط — بيشتغل من غير مفتاح لو OmniRoute شغّال على الموبايل" }
        modelBtn = ui.button("") {
            modelBtn.text = "⏳ بجيب الموديلات…"
            omniModels(act, false) { list, err ->
                label()
                if (err != null) { label("  ⚠ $err"); return@omniModels }
                dropdown(act, ui, th, modelBtn, list, curModel(), "تلقائي (OmniRoute يختار + بدايل)") { v ->
                    Cfg.put("llm_omniroute_model", v.ifBlank { "auto" }); ExtTranslate.clearBlock("llm_omniroute"); label(); refresh()
                }
            }
        }
        // (v379) زرار صريح: اجلب القايمة من السيرفر (أو من مزوّدات الراوتر الداخلي لو السيرفر مش شغّال) واختار منها اللي يترجم
        val fetchBtn = ui.button("🔄 اجلب القايمة واختار اللي يترجم") {
            Notice.show(act, "⏳ بجيب القايمة من OmniRoute…", 1800L)
            omniModels(act, true) { list, err ->
                if (err != null) { Notice.show(act, "❌ $err", 3500L); return@omniModels }
                Thread {
                    val stt = try { OmniAuto.state() } catch (_: Throwable) { OmniAuto.St.DOWN }
                    val where = when (stt) {
                        OmniAuto.St.UP -> "🛰 السيرفر شغّال على " + OmniAuto.candidates().first()
                        OmniAuto.St.NEED_KEY -> "🔑 السيرفر شغّال بس طالب مفتاح"
                        OmniAuto.St.DOWN -> "🧭 السيرفر مش شغّال — القايمة من المزوّدين المكتوبين في الراوتر الداخلي"
                    }
                    act.runOnUiThread { Notice.show(act, "✅ اتجابت ${list.size} موديل/مفتاح\n$where", 4200L) }
                }.start()
                dropdown(act, ui, th, modelBtn, list, curModel(), "تلقائي (OmniRoute يختار + بدايل)") { v ->
                    Cfg.put("llm_omniroute_model", v.ifBlank { "auto" }); ExtTranslate.clearBlock("llm_omniroute"); label(); refresh()
                }
            }
        }
        label(); refresh()
        return ui.section("🛰 OmniRoute", false,
            st, fetchBtn, modelBtn,
            ui.text("اللي تختاره بيترجم بيه الأول، ولو وقف بيكمّل على موديلات تانية من OmniRoute. «تلقائي» = OmniRoute يختار.", 11.5f, th.muted),
            ui.button("⚙ ضبط OmniRoute (مفتاح · موديل التفريغ · بدايل)") { editDialog(act, ui, th, p) { refresh(); label() } },
            ui.text("باقي مزوّدين الذكاء الاصطناعي (ChatGPT · Claude · Groq …) من شاشة 🤖 الذكاء.", 11.5f, th.muted))
    }

    /** نافذة ضبط مزوّد LLM: مفتاح · موديل · رابط · موديل تفريغ · تفعيل */
    internal fun editDialog(act: Activity, ui: Ui, th: Theme, p: ExtLlm.Preset, done: () -> Unit) {
        val d = GDialog(act)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.box(th.card, th.border, 18)
        }
        val key = ui.input("مفتاح API", Cfg.str("llm_${p.id}_key"), 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val model = ui.input("اسم الموديل" + (if (p.model.isNotEmpty()) " (فاضي = ${p.model})" else ""), Cfg.str("llm_${p.id}_model"), 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val url = ui.input("الرابط الأساسي" + (if (p.url.isNotEmpty()) " (فاضي = ${p.url})" else " (مثال: https://…/v1)"), Cfg.str("llm_${p.id}_url"), 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val stt = ui.input("موديل التفريغ الصوتي" + (if (p.sttModel.isNotEmpty()) " (فاضي = ${p.sttModel})" else ""), Cfg.str("llm_${p.id}_stt"), 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val alt = ui.input("موديلات بديلة لو الأساسي فشل (فاصلة بينهم)", Cfg.str("omni_models"), 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }   // (v369) أومني روت بس
        var enabled = Cfg.str("ext_on_llm_" + p.id, "on") != "off"
        box.addView(ui.text("🔑 ${p.label}", 17f, th.primary, true))
        box.addView(ui.text("المفتاح بيتخزن على جهازك بس، وبيتبعت لخدمة ${p.label} وحدها. أسماء الموديلات بتتغيّر — لو الاسم الافتراضي مابقاش شغّال اكتب اسم موديل حالي من موقع الخدمة.", 11.5f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
        fun save() {
            Cfg.put("llm_${p.id}_key", key.text.toString().trim()); Cfg.put("llm_${p.id}_model", model.text.toString().trim())
            Cfg.put("llm_${p.id}_url", url.text.toString().trim()); Cfg.put("llm_${p.id}_stt", stt.text.toString().trim())
            if (p.id == "omniroute") Cfg.put("omni_models", alt.text.toString().trim())
            Cfg.put("ext_on_llm_" + p.id, if (enabled) "on" else "off")
            ExtTranslate.clearBlock("llm_" + p.id)   // (v274) حفظ جديد = نبدأ نجرّب من تاني
        }
        ExtLlm.blockedWhy(p)?.let { bw -> box.addView(ui.text("⛔ واقف دلوقتي: $bw\nبيتجرّب لوحده لما المدة تخلص، أو دوس «🧪 جرّب».", 12f, th.primary).apply { setPadding(0, 0, 0, ui.dp(6)) }) }
        box.addView(key); box.addView(model)
        box.addView(ui.button("📋 اجلب الموديلات المتاحة من الخدمة (عشان الاسم يبقى صح)") {
            val k = key.text.toString().trim(); val u = url.text.toString().trim().ifEmpty { p.url }
            Notice.show(act, "⏳ بجيب قايمة الموديلات…", 1800L)
            Thread {
                val res = runCatching { ExtLlm.listModels(p, k, u) }
                act.runOnUiThread {
                    val l = res.getOrNull()
                    if (l == null) Notice.show(act, "❌ " + (res.exceptionOrNull()?.message ?: "فشل").take(120), 3500L)
                    else if (l.isEmpty()) Notice.show(act, "القايمة فاضية", 2500L)
                    else pickModel(act, ui, th, l, ExtLlm.pickClosest(model.text.toString().trim().ifBlank { p.model }, l)) { model.setText(it) }
                }
            }.start()
        })
        if (p.kind == "openai") box.addView(url)
        if (p.id == "omniroute") box.addView(alt)   // (v369) فاضي = بنجيب موديلات شات من أومني روت لوحدنا
        if (ExtLlm.supportsStt(p)) box.addView(stt)
        box.addView(ui.switchRow("مفعّل في سلسلة الترجمة", enabled) { enabled = it })
        box.addView(ui.button("💾 حفظ", true) {
            save()
            d.dismiss(); done()
            Notice.show(act, if (ExtLlm.configured(p)) "✅ اتحفظ — ${p.label} جاهز" else "اتحفظ، بس لسه ناقص (مفتاح/موديل/رابط)", 2600L)
        })
        box.addView(ui.button("🧪 جرّب (يحفظ ويبعت جملة واحدة)") {
            save(); Notice.show(act, "⏳ بجرّب ${p.label}…", 1500L)
            Thread { val r = ExtLlm.test(p); act.runOnUiThread { Notice.show(act, r, 6500L); done() } }.start()
        })
        box.addView(ui.button("🗑 مسح المفتاح") { Cfg.put("llm_${p.id}_key", ""); d.dismiss(); done() })
        box.addView(ui.button("إغلاق") { d.dismiss() })
        d.setContentView(ScrollView(act).apply { addView(box) })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** قايمة موديلات الخدمة: الأقرب لاسمك الحالي متعلّم ⭐ وفي الأول */
    private fun pickModel(act: Activity, ui: Ui, th: Theme, list: List<String>, suggested: String?, onPick: (String) -> Unit) {
        val d = GDialog(act)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.box(th.card, th.border, 18)
        }
        box.addView(ui.text("📋 الموديلات المتاحة (${list.size})", 16f, th.primary, true))
        box.addView(ui.text("اختار واحد — اللي عليه ⭐ هو الأقرب لاسم موديلك الحالي. موديلات التفريغ والصور والـ embedding مش بتترجم.", 11.5f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
        val ordered = (listOfNotNull(suggested) + list.filter { it != suggested }).take(80)
        for (m in ordered) box.addView(ui.button((if (m == suggested) "⭐ " else "") + m) { onPick(m); d.dismiss() })
        box.addView(ui.button("إغلاق") { d.dismiss() })
        d.setContentView(ScrollView(act).apply { addView(box) })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** LibreTranslate: رابط السيرفر + مفتاح اختياري + تفعيل + تجربة */
    internal fun libreDialog(act: Activity, ui: Ui, th: Theme, done: () -> Unit) {
        val d = GDialog(act)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.box(th.card, th.border, 18)
        }
        val url = ui.input("رابط السيرفر (مثال: https://libretranslate.com أو سيرفرك)", Cfg.str("libre_url"), 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val key = ui.input("مفتاح API (اختياري — حسب السيرفر)", Cfg.str("libre_key"), 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        var enabled = Cfg.str("ext_on_libre", "on") != "off"
        box.addView(ui.text("🔑 LibreTranslate", 17f, th.primary, true))
        box.addView(ui.text("السيرفر العام بتاعهم محتاج مفتاح، أو شغّل سيرفرك بنفسك من غير مفتاح. بيترجم 25 جملة في الطلب (فصحى).", 11.5f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
        box.addView(url); box.addView(key)
        box.addView(ui.switchRow("مفعّل في سلسلة الترجمة", enabled) { enabled = it })
        fun save() {
            Cfg.put("libre_url", url.text.toString().trim()); Cfg.put("libre_key", key.text.toString().trim()); Cfg.put("ext_on_libre", if (enabled) "on" else "off")
            ExtTranslate.clearBlock("libre")
        }
        box.addView(ui.button("💾 حفظ", true) { save(); d.dismiss(); done() })
        box.addView(ui.button("🧪 جرّب (يحفظ ويبعت جملة واحدة)") {
            save(); Notice.show(act, "⏳ بجرّب LibreTranslate…", 1500L)
            Thread { val r = ExtTranslate.libreProv()?.let { ExtTranslate.testProv(it) } ?: "🔑 اكتب رابط السيرفر الأول"; act.runOnUiThread { Notice.show(act, r, 6500L); done() } }.start()
        })
        box.addView(ui.button("🗑 مسح الإعدادات") { Cfg.put("libre_url", ""); Cfg.put("libre_key", ""); d.dismiss(); done() })
        box.addView(ui.button("إغلاق") { d.dismiss() })
        d.setContentView(ScrollView(act).apply { addView(box) })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
