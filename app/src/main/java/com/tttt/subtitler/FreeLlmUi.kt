package com.tttt.subtitler

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * (v416) واجهة إعدادات Mistral و Cerebras (جوه الإعدادات ← 🔑 المفاتيح، تحت جيميناي):
 * مفتاح (أو أكتر) + إزاي أجيبه + الموديل + تجربة + تشغيل/إيقاف، وقسم «مين يترجم الأول؟ واللهجة».
 */
object FreeLlmUi {
    private val TOKEN_RE = Regex("[A-Za-z0-9_\\-]{20,120}")

    private fun clipToken(act: Activity): String? = try {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = (cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString() ?: "").trim()
        if (TOKEN_RE.matches(t) && !t.startsWith("AIza")) t else null
    } catch (_: Exception) { null }

    /** الأقسام اللي بتتضاف في تاب 🔑 المفاتيح */
    fun sections(act: Activity, ui: Ui, th: Theme): List<View> =
        FreeLlm.engines.map { engineSection(act, ui, th, it) } + listOf(chooser(ui, th))

    private fun watch(f: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
        override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
    }

    private fun engineSection(act: Activity, ui: Ui, th: Theme, e: FreeLlm.Eng): View {
        val keysEt = ui.input("مفتاح ${e.label} (مفتاح في كل سطر)", Cfg.str(e.keyCfg), 3)
        keysEt.layoutDirection = View.LAYOUT_DIRECTION_LTR
        keysEt.addTextChangedListener(watch { Cfg.put(e.keyCfg, it) })
        val modelEt = ui.input("الموديل (سيبه فاضي = البرنامج يختار لوحده)", Cfg.str(e.modelCfg), 1)
        modelEt.layoutDirection = View.LAYOUT_DIRECTION_LTR
        modelEt.addTextChangedListener(watch { Cfg.put(e.modelCfg, it.trim()) })
        val out = ui.text("", 12f, th.muted)
        return ui.section(e.icon + " " + e.label + " (مجاني · بمفتاحك)", false,
            ui.text("ترجمة بالذكاء الصناعي (جملها أحسن بكتير من جوجل/ML Kit). بتجيب المفتاح من موقع ${e.keyPageLabel} من الخطة المجانية بس — من غير كارت. الحد المجاني بيتغيّر عندهم من غير إشعار، ولو خلص البرنامج بيكمّل بالمحرك اللي بعده لوحده.", 12f, th.muted),
            ui.button("❓ إزاي أجيب المفتاح المجاني؟ (وألصقه)", true) {
                guide(act, ui, th, e) { k ->
                    val cur = keysEt.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (k in cur) Notice.show(act, "المفتاح ده موجود قبل كده", 2400L)
                    else { keysEt.setText((cur + k).joinToString("\n")); Notice.show(act, "✓ اتحفظ مفتاح ${e.label}", 2400L) }
                }
            },
            keysEt,
            ui.text("الموديل:", 12f, th.muted),
            modelEt,
            ui.switchRow("استخدام ${e.label} في الترجمة", Cfg.str("ext_on_" + e.id, "on") != "off") { Cfg.put("ext_on_" + e.id, if (it) "on" else "off") },
            ui.button("🧪 جرّب ${e.label} دلوقتي") {
                if (!FreeLlm.hasKey(e)) Notice.show(act, "حط مفتاح ${e.label} الأول", 2600L)
                else {
                    out.text = "⏳ بيجرّب…"; out.setTextColor(th.muted)
                    Thread {
                        val r = FreeLlm.test(e)
                        act.runOnUiThread { out.text = r.second; out.setTextColor(if (r.first) th.primary else th.danger) }
                    }.also { it.isDaemon = true }.start()
                }
            },
            ui.button("📋 شوف الموديلات المتاحة على مفتاحك") {
                if (!FreeLlm.hasKey(e)) Notice.show(act, "حط مفتاح ${e.label} الأول", 2600L)
                else {
                    out.text = "⏳ بيجيب القايمة…"; out.setTextColor(th.muted)
                    Thread {
                        val msg = try { FreeLlm.listModels(e, FreeLlm.keys(e).first()).take(20).joinToString("\n").ifBlank { "القايمة فاضية" } }
                                  catch (x: Exception) { "❌ " + (x.message ?: x.javaClass.simpleName).take(120) }
                        act.runOnUiThread { out.text = msg; out.setTextColor(th.text) }
                    }.also { it.isDaemon = true }.start()
                }
            },
            out)
    }

    private fun chooser(ui: Ui, th: Theme): View {
        fun toLabel(id: String) = when (id) { "mistral" -> "Mistral"; "cerebras" -> "Cerebras"; "google" -> "جوجل الأول"; else -> "تلقائي" }
        fun toId(l: String) = when (l) { "Mistral" -> "mistral"; "Cerebras" -> "cerebras"; "جوجل الأول" -> "google"; else -> "auto" }
        val first = ui.chips(listOf("تلقائي", "Mistral", "Cerebras", "جوجل الأول"), { toLabel(Cfg.str("llm_first", "auto")) }) { Cfg.put("llm_first", toId(it)) }
        val dia = ui.chips(listOf("فصحى", "مصري"), { if (Cfg.str("llm_dialect", "msa") == "egy") "مصري" else "فصحى" }) { Cfg.put("llm_dialect", if (it == "مصري") "egy" else "msa") }
        return ui.section("🎯 مين يترجم الأول؟ واللهجة", true,
            ui.text("المحرك اللي يترجم الأول في وضع «🌗 نص-أونلاين» وكبديل لما جيميناي يقف. «تلقائي» = أول محرك عنده مفتاح (Mistral ثم Cerebras)، ولو فشل بيكمّل بالتاني ثم جوجل.", 12f, th.muted),
            first,
            ui.text("لهجة ترجمة Mistral/Cerebras:", 12f, th.muted),
            dia,
            ui.text("«مصري» = بيترجموا مصري على طول من غير جيميناي. «فصحى» = فصحى بسيطة. الوضع «📴 أوفلاين» بيفضل ML Kit بس (من غير نت).", 12f, th.muted))
    }

    /** شاشة «إزاي أجيب المفتاح؟»: خطوات + زرار يفتح الموقع + زرار يلصق من الكليبورد */
    fun guide(act: Activity, ui: Ui, th: Theme, e: FreeLlm.Eng, onKey: (String) -> Unit) {
        val d = GDialog(act); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var auto = false
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.box(th.card, th.border, 18)
        }
        box.addView(ui.text("❓ إزاي أجيب مفتاح ${e.label} المجاني؟", 17f, th.primary, true))
        box.addView(ui.text("المفتاح من موقع ${e.keyPageLabel} نفسه، وبياخد دقيقتين:", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
        val steps = if (e.id == "mistral") listOf(
            "١) دوس «🌐 افتح الموقع» وسجّل بإيميلك.",
            "٢) اختار الخطة المجانية (Experiment / Free) — ماتحطش كارت. لو طلب تأكيد رقم موبايل أو موافقة على شروط الخطة المجانية، وافق (ده شرطهم للمجاني).",
            "٣) ادخل على API Keys ← Create new key وانسخ المفتاح.",
            "٤) ارجع هنا ودوس «📋 الصق من الكليبورد»."
        ) else listOf(
            "١) دوس «🌐 افتح الموقع» وسجّل بإيميلك.",
            "٢) ادخل على API Keys ← Create key (الطبقة المجانية، من غير كارت) وانسخ المفتاح (غالبًا بيبدأ بـ csk-).",
            "٣) ارجع هنا ودوس «📋 الصق من الكليبورد»."
        )
        for (s in steps) box.addView(ui.text(s, 13f, th.text).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) })
        box.addView(ui.text("• اتأكد إن الخطة اللي على حسابك مكتوب عليها مجانية (Free/Experiment) مش مدفوعة (Scale/Paid) — البرنامج مابيشتريش حاجة بس الفاتورة لو فعّلت خطة مدفوعة هتبقى على حسابك.\n• الأسماء ممكن تتغيّر في الموقع؛ دوّر على «API Keys».\n• ماتشاركش مفتاحك مع حد.", 11f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        fun pill(t: String, bg: Int, fg: Int, f: () -> Unit) = IconTextView(act).apply {
            text = t; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.box(bg, th.border, 12); setOnClickListener { f() }
        }
        fun paste() {
            val k = clipToken(act)
            if (k == null) Notice.show(act, "مفيش مفتاح في الكليبورد — انسخه الأول من موقع ${e.keyPageLabel}", 3600L)
            else { onKey(k); d.dismiss() }
        }
        box.addView(pill("🌐 افتح موقع ${e.keyPageLabel}", th.primary, Color.BLACK) {
            auto = true
            try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.keyPage))) }
            catch (_: Exception) { Notice.show(act, "مفيش متصفح — افتح ${e.keyPageLabel} يدويًا", 3600L) }
        }, LinearLayout.LayoutParams(-1, ui.dp(46)).apply { topMargin = ui.dp(8) })
        box.addView(pill("📋 الصق من الكليبورد", th.surface, th.text) { paste() }, LinearLayout.LayoutParams(-1, ui.dp(44)).apply { topMargin = ui.dp(6) })
        box.addView(pill("إغلاق", th.surface, th.muted) { d.dismiss() }, LinearLayout.LayoutParams(-1, ui.dp(40)).apply { topMargin = ui.dp(6) })
        // أول ما ترجع من المتصفح والمفتاح منسوخ: يتحط لوحده
        d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { f -> if (f && auto && clipToken(act) != null) { auto = false; paste() } }
        d.setContentView(ScrollView(act).apply { addView(box) })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
