package com.tttt.subtitler

import android.app.Activity
import android.view.View
import android.widget.LinearLayout

/**
 * (v1032 — المرحلة 2) شيتين إعدادات للاستوديو، بنفس مفاتيح Cfg اللي المحرك بيقراها فعلًا:
 *  • ⚙ إعدادات الترجمة: لهجة/أسلوب/شدة ترجمة الاستوديو (conv_dialect + lang · tone_style · tone_strength) — نفس مفاتيح الإعدادات العامة
 *    (SubFileTr.effLang/effStyle بيقراهم)، فالتغيير هنا = التغيير في الإعدادات والعكس.
 *  • ⚙ إعدادات التفريغ: القفل على Flash Lite Latest + التفريغ الأوفلاين (كانوا سويتشين جوّا شيت الدوكيومنت — اتنقلوا هنا).
 * مفيش منطق جديد: واجهة بس.
 */
class StudioSettingsUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private fun col() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private fun note(t: String) = ui.text(t, 11.5f, th.muted).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) }
    private fun head(t: String) = ui.text(t, 12.5f, th.primary, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(2)) }

    /** لهجة/أسلوب/شدة ترجمة الاستوديو (عامة — بتأثر على أي ترجمة جاية، مش المترجم قبل كده). */
    fun translation() {
        val c = col()
        c.addView(ui.text("ترجمة الاستوديو (💬 ترجمة · 📝 ترجمة لـSRT · 🧩 إكمال الناقص)", 13.5f, th.text, true))
        val engine = if (TrWizard.method() == "google") "جوجل (من غير API)" else "Gemini " + (StudioTx.pinned().ifBlank { "حسب موديل الإعدادات" })
        c.addView(ui.text("المحرك دلوقتي: $engine — بيتغيّر من الإعدادات ← خط الترجمة.", 11.5f, th.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        if (Strict.hideTrOpts()) {
            c.addView(note("خيارات اللهجة والأسلوب مقفولة في الوضع الحالي."))
        } else {
            c.addView(head("🗣 اللهجة (فصحى = من غير تحويل)"))
            c.addView(ui.chips(TrLib.DIALECTS, { Cfg.str("conv_dialect", "").ifBlank { "فصحى" } }) {
                val dv = if (it == "فصحى") "" else it
                Cfg.put("lang", it); Cfg.put("conv_dialect", dv); TrWizard.syncSaved("conv_dialect", dv)
            })
            c.addView(head("🎭 الأسلوب"))
            c.addView(ui.chips(TrLib.STYLES, { Cfg.str("tone_style", "").ifBlank { "تلقائي" } }) {
                Cfg.put("tone_style", if (it == "تلقائي") "" else it)
            })
            c.addView(head("💪 الشدة (بتشتغل مع شعبي / جرئ / تلقائي بس)"))
            c.addView(ui.chips(TrLib.STRENGTHS, { Cfg.str("tone_strength", "متوسطة").ifBlank { "متوسطة" } }) { Cfg.put("tone_strength", it) })
        }
        c.addView(note("الإعدادات دي عامة (نفس إعدادات البرنامج). اللي اترجم قبل كده مابيتغيّرش غير لو ترجمته من الأول."))
        ui.sheet(act, "⚙ إعدادات الترجمة", listOf<View>(c)).show()
    }

    /** مصدر التفريغ: القفل + الأوفلاين (اتنقلوا من openDocSheet). */
    fun transcription() {
        val c = col()
        c.addView(ui.text("تفريغ الاستوديو (محاضرة · تسجيل · مكالمة · فيديو)", 13.5f, th.text, true))
        c.addView(ui.switchRow("🔒 مصدر التفريغ ثابت: Gemini Flash Lite Latest (من غير تبديل موديلات)", StudioTx.fixedOn()) { v ->
            Cfg.put(StudioTx.FIXED_KEY, if (v) "1" else "0")
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val offNote = ui.text(StudioTx.offlineNote(), 11.5f, th.muted)
        c.addView(ui.switchRow("📴 تفريغ أوفلاين للكلام غير العربي (إنجليزي وغيره) — العربي أونلاين", StudioTx.offlineOn()) { v ->
            Cfg.put(StudioTx.OFFLINE_KEY, if (v) "1" else "0")
            offNote.text = StudioTx.offlineNote()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        c.addView(offNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        c.addView(note("بيتطبق على أي تفريغ جديد أو «إكمال الناقص». اللي اتفرّغ قبل كده مابيتغيّرش."))
        ui.sheet(act, "⚙ إعدادات التفريغ", listOf<View>(c)).show()
    }
}
