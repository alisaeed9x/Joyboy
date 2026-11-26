package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout

/**
 * (v1303) حالة مفاتيح Gemini في الإعدادات (منقولة من تبويب شات الذكاء المحذوف v1303):
 * سطر في صفحة المفاتيح بيوري الحالة (🟢 كلهم شغّالين · 🟡 فيه مشكلة · 🔴 مفيش/واقفين · ⚪ لسه ما اتجرّبتش)،
 * ودوسة بتفتح شيت فيه حالة كل مفتاح بالتفصيل + زرار «اختبار كل المفاتيح».
 */
object KeyStatus {
    /** حالة كل مفتاح: -1 لسه ما اتجرّبش · 200 شغّال · غير كده كود الخطأ (0 = نت/مهلة) */
    val kcode = IntArray(32) { -1 }
    @Volatile private var autoTested = false
    private val h = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null

    private fun keys(): List<String> = try { Cfg.allMainKeys().filter { it != LocalKey.ID && it.isNotBlank() } } catch (_: Throwable) { emptyList() }
    private fun model(): String = try { Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT } } catch (_: Throwable) { Models.DEFAULT }

    /** النقطة + سطر الحالة المختصر */
    fun summary(): Pair<String, String> {
        val ks = keys(); val n = ks.size
        var ok = 0; var bad = 0
        for (i in 0 until n) { val c = kcode[i.coerceAtMost(31)]; if (c == 200) ok++ else if (c != -1) bad++ }
        return when {
            n == 0 -> "🔴" to "مفيش مفاتيح"
            ok == 0 && bad == 0 -> "⚪" to "دوس للاختبار"
            ok == 0 -> "🔴" to "المفاتيح واقفة"
            bad > 0 -> "🟡" to "$ok من $n شغّالة"
            else -> "🟢" to "$n مفاتيح شغّالة"
        }
    }

    private fun codeLabel(c: Int): String = when (c) {
        -1 -> "⚪ لسه ما اتجرّبش"
        200 -> "🟢 شغّال"
        429 -> "🟡 وصل للحد دلوقتي (429)"
        503, 500, 502, 504 -> "🟡 الموديل مضغوط (" + c + ")"
        404 -> "🔴 الموديل مش متاح على المفتاح (404)"
        400 -> "🔴 طلب مرفوض (400)"
        401, 403 -> "🔴 المفتاح مرفوض أو مقفول (" + c + ")"
        0 -> "🟡 مشكلة نت أو مهلة"
        else -> "🟡 خطأ " + c
    }

    /** اختبار المفاتيح في الخلفية. onTick بتتنده على خيط الواجهة بعد كل مفتاح وفي الآخر. */
    fun test(act: Activity, ui: Ui, th: Theme, reopen: Boolean = false, onTick: (() -> Unit)? = null) {
        val ks = keys(); val mdl = model()
        Thread {
            for (i in ks.indices) {
                val k = ks[i]
                val code = try { Api.generate(mdl, k, "رد بكلمة ok فقط", null, 16, 0.0, false, false); 200 } catch (e: ApiErr) { e.code } catch (_: NoKey) { 401 } catch (_: Throwable) { 0 }
                kcode[i.coerceAtMost(31)] = code
                h.post { onTick?.invoke() }
            }
            h.post { onTick?.invoke(); if (reopen && !act.isFinishing && !act.isDestroyed) show(act, ui, th) }
        }.start()
    }

    /** سطر الإعدادات: الحالة المختصرة + دوسة بتفتح التفاصيل. بيجرّب المفاتيح تلقائيًا أول مرة. */
    fun row(act: Activity, ui: Ui, th: Theme): View {
        val t = ui.text("", 14f, th.text, true).apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        fun paint() { val s = summary(); t.text = "📶 حالة المفاتيح: " + s.first + " " + s.second }
        paint()
        if (!autoTested && keys().isNotEmpty()) {
            autoTested = true
            test(act, ui, th) { try { paint() } catch (_: Throwable) {} }
        }
        t.setOnClickListener { show(act, ui, th) { try { paint() } catch (_: Throwable) {} } }
        return t
    }

    /** شيت التفاصيل: حالة كل مفتاح + اختبار الكل */
    fun show(act: Activity, ui: Ui, th: Theme, onChange: (() -> Unit)? = null) {
        try { dlg?.dismiss() } catch (_: Throwable) {}
        val ks = keys()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.text("الموديل: " + model(), 12.5f, th.muted).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)) })
        if (ks.isEmpty()) box.addView(ui.text("🔴 مفيش مفاتيح. ضيفها من خانات المفاتيح فوق.", 13.5f, th.text))
        for (i in ks.indices) {
            val k = ks[i]
            val tail = if (k.length > 8) "…" + k.takeLast(4) else ""
            val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); background = ui.box(th.card, th.border, 12) }
            row.addView(ui.text("مفتاح " + (i + 1) + " " + tail, 13.5f, th.text, true))
            row.addView(ui.text(codeLabel(kcode[i.coerceAtMost(31)]), 12f, th.muted))
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        }
        lateinit var d2: Dialog
        if (ks.isNotEmpty()) box.addView(ui.button("🔍 اختبار كل المفاتيح", true) {
            for (i in ks.indices) kcode[i.coerceAtMost(31)] = -1
            onChange?.invoke()
            d2.dismiss()
            test(act, ui, th, reopen = true, onTick = onChange)
            Notice.show(act, "بيتجرّب المفاتيح…", 1800L)
        })
        d2 = ui.sheet(act, "🔑 حالة المفاتيح", listOf<View>(box), false)
        dlg = d2
        d2.show()
    }
}
