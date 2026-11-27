package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * (v1377 — بطلب علي) شاشة أول تشغيل: حقل مفتاح Groq + حقل مفتاح Gemini. لو الاتنين اتسابوا فاضيين
 * بيظهر خيار تنزيل تفريغ الجهاز (ويسبر) باختيار tiny / small / large مع تقدّم التنزيل.
 * بتظهر مرة واحدة بس، ومش بتظهر أصلًا لو المستخدم عنده مفاتيح محفوظة أو موديل ويسبر متنزّل.
 */
object Onboarding {
    private const val FLAG = "onboarded_v1"
    private val h = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null

    /** بتتنده من Main بعد رسم الواجهة */
    fun maybeShow(act: Activity) {
        try {
            if (act.isFinishing || act.isDestroyed) return
            if (Cfg.str(FLAG, "") == "1") return
            // مستخدم قديم عنده مفاتيح أو موديل متنزّل: نعلّم من غير ما نزعجه
            if (Cfg.allMainKeys().isNotEmpty() || Cfg.str("groq_key", "").isNotBlank() || Offline.models.any { Offline.downloaded(it) }) { Cfg.put(FLAG, "1"); return }
            val th = Themes.current()
            val ui = Ui(act, th)
            gate(act, ui, th)
        } catch (t: Throwable) { LogStore.err("Onboarding:show", t) }
    }

    private fun close() { try { dlg?.dismiss() } catch (_: Throwable) {} ; dlg = null }

    private fun gate(act: Activity, ui: Ui, th: Theme) {
        val groq = ui.input("gsk_… مفتاح Groq (مجاني من console.groq.com/keys)", "")
        val gem = ui.input("AIza… مفتاح Gemini (مجاني من aistudio.google.com)", "")
        lateinit var d: Dialog
        fun save(): Boolean {
            val g = groq.text.toString().trim(); val m = gem.text.toString().trim()
            if (g.isEmpty() && m.isEmpty()) return false
            if (g.isNotEmpty()) { Cfg.put("groq_key", g); Cfg.put("groq_key_state", "") }
            if (m.isNotEmpty()) Cfg.p.edit().putString("keys", m).apply()
            return true
        }
        val views = listOf<View>(
            ui.text("👋 أهلاً بيك في Joyboy", 18f, th.text, true),
            ui.text("عشان الترجمة والتفريغ يشتغلوا محتاج مفتاح واحد على الأقل، أو نزّل تفريغ الجهاز (ويسبر) واشتغل من غير مفاتيح خالص.", 13.5f, th.text),
            groq, gem,
            ui.button("💾 حفظ وابدأ", true) {
                if (!save()) { whisperPick(act, ui, th); return@button }
                Cfg.put(FLAG, "1"); close(); Notice.show(act, "✅ المفاتيح اتجهزت — ابدأ الترجمة", 3000L)
            },
            ui.button("🎙 من غير مفاتيح: تفريغ على الجهاز (ويسبر)") { whisperPick(act, ui, th) },
            ui.text("المفاتيح بتتحفظ على الموبايل بس ومبتتبعتش لأي حد غير جوجل/Groq.", 12f, th.muted)
        )
        d = ui.sheet(act, "الإعداد الأول", views, false); dlg = d; d.show()
    }

    private fun whisperPick(act: Activity, ui: Ui, th: Theme) {
        close()   // اقفل شيت البوابة قبل ما شيت الويسبر يظهر مكانها
        val picks = listOf("tiny", "small", "turbo")
        val prog = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val pct = ui.text("", 13f, th.text)
        val state = ui.text("اختار الحجم المناسب لموبايلك:", 13.5f, th.text)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val views = ArrayList<View>()
        views.add(state)
        for (id in picks) {
            val m = Offline.models.first { it.id == id }
            views.add(ui.button(m.label + " · ~" + m.approxMb + " ميجا — " + m.note) {
                if (Offline.downloading()) { Notice.show(act, "فيه تنزيل شغال بالفعل…", 2200L); return@button }
                box.visibility = View.VISIBLE
                state.text = "⬇ بيتنزّل " + m.label + "…"
                Offline.download(m, { got, total ->
                    h.post { try { if (total > 0) prog.progress = (got * 1000L / total).toInt(); pct.text = (got / 1_048_576L).toString() + " / " + (total / 1_048_576L) + " ميجا" } catch (_: Throwable) {} }
                }, { err ->
                    h.post { try {
                        if (err != null) { state.text = "⚠ التنزيل فشل: $err — جرّب تاني" }
                        else {
                            Cfg.put("off_model", m.id); Cfg.put("stt_offline", "1"); Cfg.put("mt_tr_mode", "whisper"); Cfg.put("tr_engine", "off")   // نفس مزامنة اختيار ويسبر اليدوي (v1375)
                            Cfg.put(FLAG, "1"); close(); Notice.show(act, "✅ ويسبر اتجهز — التفريغ بيشتغل على الجهاز من غير مفاتيح", 3600L)
                        }
                    } catch (_: Throwable) {} }
                })
            })
        }
        prog.visibility = View.GONE
        box.addView(prog, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(8), 0, 0) })
        box.addView(pct)
        box.visibility = View.GONE
        views.add(box)
        val d = ui.sheet(act, "🎙 تفريغ الجهاز (ويسبر)", views, false); dlg = d
        prog.visibility = View.VISIBLE
        d.show()
    }
}
