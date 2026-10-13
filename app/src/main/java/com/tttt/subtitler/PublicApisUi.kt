package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.lang.ref.WeakReference

/**
 * (v272/v273) قسم الإعدادات «🌐 APIs خارجية»: حالة سحب قايمة public-apis (بتتحدّث لوحدها)، زرار سحب يدوي،
 * ترتيب الترجمة (الداخلي أولًا / الخارجي أولًا)، المزوّدين المجانيين، محركات LLM بمفتاحك (ChatGPT/Claude/…)،
 * والتفريغ السحابي. كل مزوّد بيتعلّم لو موجود في القايمة المسحوبة من الريبو.
 */
object PublicApisUi {
    private const val OPT_INTERNAL = "🏠 جوجل الداخلي أولًا"
    private const val OPT_EXTERNAL = "🌐 APIs الخارجية أولًا"

    private fun repoTag(keys: List<String>): String =
        if (keys.isEmpty()) "" else if (PublicApis.entries.isEmpty()) "" else if (PublicApis.listed(keys) != null) " · ✅ في الريبو" else " · ➕ مش في قايمة الريبو"

    fun section(act: Activity, ui: Ui, th: Theme): LinearLayout {
        val ref = WeakReference(act)
        val status = ui.text(PublicApis.statusLine(), 12.5f, th.text)
        val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        lateinit var refreshAll: () -> Unit

        fun rebuild() {
            listBox.removeAllViews()
            listBox.addView(ui.text("🔗 سلسلة الترجمة دلوقتي: " + ExtTranslate.chainLabel(), 12f, th.text, true).apply { setPadding(0, ui.dp(2), 0, ui.dp(6)) })
            // --- مزوّدين مجانيين من غير مفتاح ---
            for (p in ExtTranslate.builtin) {
                val origin = if (ExtTranslate.inRepo(p)) "✅ موجود في الريبو" else if (PublicApis.entries.isEmpty()) "⏳ لسه القايمة ما اتسحبتش" else "➕ مش في قايمة الريبو (شغّال بالمحوّل الجاهز)"
                listBox.addView(ui.switchRow(p.label, ExtTranslate.enabled(p)) { ExtTranslate.setEnabled(p, it) })
                listBox.addView(ui.text(origin + "\n" + ExtTranslate.statLine(p), 11.5f, th.muted))
            }
            // --- محركات LLM بمفتاحك ---
            listBox.addView(ui.text("🤖 محركات LLM بمفتاحك (ChatGPT · Claude · …) — بتترجم 25 جملة في الطلب:", 12.5f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
            for (pr in ExtLlm.presets) {
                val bw = if (ExtLlm.configured(pr)) ExtLlm.blockedWhy(pr) else null
                val st = if (!ExtLlm.configured(pr)) "🔑 محتاج مفتاح" else if (!ExtLlm.enabled(pr)) "⏸ موقوف" else if (bw != null) "⛔ $bw" else "✅ جاهز"
                val n = Cfg.str("ext_n_llm_" + pr.id).toIntOrNull() ?: 0
                listBox.addView(ui.button("${pr.label} — $st" + (if (n > 0) " · ترجم $n جملة" else "") + repoTag(pr.keys)) { editDialog(act, ui, th, pr) { refreshAll() } })
            }
            // --- LibreTranslate (سيرفرك أو سيرفر بمفتاح) ---
            val lp = ExtTranslate.libreProv()
            val lbw = lp?.let { ExtTranslate.blockedWhy(it.id, it.keyHash()) }
            val ln = Cfg.str("ext_n_libre").toIntOrNull() ?: 0
            listBox.addView(ui.button("LibreTranslate — " + (if (lp == null) "🔑 محتاج رابط السيرفر" else if (!ExtTranslate.enabled(lp)) "⏸ موقوف" else if (lbw != null) "⛔ $lbw" else "✅ جاهز") + (if (ln > 0) " · ترجم $ln جملة" else "") + repoTag(listOf("libretranslate"))) { libreDialog(act, ui, th) { refreshAll() } })
            // --- تفريغ سحابي ---
            listBox.addView(ui.text("🎙 التفريغ السحابي (Whisper API):", 12.5f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
            listBox.addView(ui.switchRow("فرّغ الصوت عن طريق API سحابي بدل الموديل اللي على الجهاز", RemoteStt.on()) {
                Cfg.put("rstt_on", if (it) "on" else "off")
                if (it) {
                    if (RemoteStt.ready()) Cfg.put("stt_offline", "1")
                    else ref.get()?.let { a -> Notice.show(a, "🔑 ضبط مفتاح مزوّد التفريغ الأول (ChatGPT أو Groq من اللي فوق)", 3200L) }
                }
                refreshAll()
            })
            val stts = ExtLlm.presets.filter { ExtLlm.supportsStt(it) }
            listBox.addView(ui.chips(stts.map { it.label }, { RemoteStt.provider()?.label ?: stts.first { s -> s.id == "groq" }.label }) { l ->
                stts.firstOrNull { it.label == l }?.let { Cfg.put("rstt_prov", it.id) }; refreshAll()
            })
            val rp = RemoteStt.provider()
            listBox.addView(ui.text(
                (if (!RemoteStt.on()) "مقفول." else if (RemoteStt.ready()) "✅ جاهز: ${rp?.label} · ${rp?.let { ExtLlm.sttModel(it) }}" else "🔑 المزوّد المختار ناقصه مفتاح أو موديل تفريغ — اضبطه من الزرار فوق.") +
                "\n⚠ الصوت بيتبعت لخدمة خارجية. لو فشلت (نت/مفتاح/كوتة) جيميناي هو اللي بينسخ الباتش.", 11.5f, th.muted))
            // --- ذات صلة في الريبو ---
            val rel = PublicApis.related()
            if (rel.isNotEmpty()) {
                val names = rel.take(30).joinToString("، ") { it.name + (if (it.auth.isNotBlank() && !it.auth.equals("No", true)) " (مفتاح)" else "") }
                listBox.addView(ui.text("ليها علاقة بالترجمة/الكلام في قايمة الريبو (${rel.size}): $names" + if (rel.size > 30) " …" else "", 11.5f, th.muted).apply { setPadding(0, ui.dp(10), 0, 0) })
            }
        }
        refreshAll = { status.text = PublicApis.statusLine(); rebuild() }
        refreshAll()
        PublicApis.onChange = { ref.get()?.runOnUiThread { try { refreshAll() } catch (_: Throwable) {} } }

        return ui.section("🌐 APIs خارجية (public-apis · ChatGPT · Claude · LibreTranslate · تفريغ سحابي) — v275", false,
            ui.text("الترجمة بجوجل (مع الأوفلاين) ممكن تستعين بمزوّدين تانيين، والتفريغ ممكن يبقى سحابي. القايمة بتتسحب من ريبو public-apis لوحدها، والمزوّد بيشتغل بس لو ليه محوّل جاهز في التطبيق. الترتيب بيأثر على مسار «تفريغ أوفلاين + ترجمة جوجل» بس.", 12f, th.muted),
            status,
            ui.button("🔄 اسحب القايمة من الريبو دلوقتي") { PublicApis.pull(ref.get(), true) },
            ui.switchRow("اسحب القايمة لوحدها كل 24 ساعة", Cfg.str("papi_auto", "on") != "off") { Cfg.put("papi_auto", if (it) "on" else "off") },
            ui.text("ترتيب الترجمة (لو الأول فشل في جمل، التاني بيكمّل — ولو كلهم فشلوا الباتش بيتحوّل لجيميناي بمفاتيحك، وبيطلعلك إشعار بكل تبديل):", 12f, th.muted),
            ui.chips(listOf(OPT_INTERNAL, OPT_EXTERNAL), { if (ExtTranslate.externalFirst()) OPT_EXTERNAL else OPT_INTERNAL }) { Cfg.put("tx_order", if (it == OPT_EXTERNAL) "external" else "internal") },
            ui.switchRow("استخدم المزوّدين اللي في قايمة الريبو المسحوبة بس", ExtTranslate.strict()) { Cfg.put("ext_strict", if (it) "on" else "off"); try { refreshAll() } catch (_: Throwable) {} },
            listBox,
            ui.text("جوجل الداخلي أدق في العادة من المزوّدين المجانيين، وMyMemory ليه حد يومي (~5000 حرف) — فالافتراضي «الداخلي أولًا». محركات LLM بتستهلك من رصيد مفتاحك.", 11.5f, th.muted))
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
        var enabled = Cfg.str("ext_on_llm_" + p.id, "on") != "off"
        box.addView(ui.text("🔑 ${p.label}", 17f, th.primary, true))
        box.addView(ui.text("المفتاح بيتخزن على جهازك بس، وبيتبعت لخدمة ${p.label} وحدها. أسماء الموديلات بتتغيّر — لو الاسم الافتراضي مابقاش شغّال اكتب اسم موديل حالي من موقع الخدمة.", 11.5f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
        fun save() {
            Cfg.put("llm_${p.id}_key", key.text.toString().trim()); Cfg.put("llm_${p.id}_model", model.text.toString().trim())
            Cfg.put("llm_${p.id}_url", url.text.toString().trim()); Cfg.put("llm_${p.id}_stt", stt.text.toString().trim())
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
