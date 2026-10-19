package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v435) شاشة Beat API الواحدة: مفتاح + رابط + موديلات بتتبدّل + تجربة كل موديل.
 * بتتفتح من ⚙ (إعدادات الوضع)، ومن قايمة «ترجم»، ومن «⋯ أكتر» في شاشة طريقة الترجمة.
 */
object BeatUi {
    fun show(act: Activity, onDone: () -> Unit = {}) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()
        val models = ArrayList<String>(ExtTranslate.beatModels())
        var sel = ExtTranslate.beatModel()
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.layoutDirection = View.LAYOUT_DIRECTION_RTL
        box.setPadding(dp(18), dp(8), dp(18), dp(4))

        val live = TextView(act); live.textSize = 13f
        val ek = EditText(act); ek.hint = "مفتاح Beat API (sk-…)"; ek.setText(Cfg.str("beat_key")); ek.setSingleLine(true)
        val eu = EditText(act); eu.hint = "الرابط (فاضي = " + ExtTranslate.BEAT_URL + ")"; eu.setText(Cfg.str("beat_url")); eu.setSingleLine(true)
        val rg = RadioGroup(act); rg.orientation = RadioGroup.VERTICAL
        val res = TextView(act); res.textSize = 12f; res.setPadding(0, dp(4), 0, dp(4))
        val em = EditText(act); em.hint = "اسم موديل تضيفه (مثلًا glm-5.3-free)"; em.setSingleLine(true)
        val cbRot = CheckBox(act); cbRot.text = "بدّل للموديل التاني تلقائي لو الموديل وقف"; cbRot.isChecked = ExtTranslate.beatRotate()
        val cbPrim = CheckBox(act); cbPrim.text = "خليه المحرك الأساسي (يترجم قبل جوجل)"; cbPrim.isChecked = ExtTranslate.primaryId() == "beat"

        fun paint() {
            rg.removeAllViews()
            for (m in models) {
                val st = ExtTranslate.beatModelStatus(m)
                val rb = RadioButton(act)
                rb.id = View.generateViewId()
                rb.text = m + (if (m == ExtTranslate.beatLive && st.isEmpty()) "  ● شغّال" else "") + (if (st.isNotEmpty()) "  ⛔ " + st else "")
                rb.isChecked = (m == sel)
                rb.setOnClickListener { sel = m }
                rg.addView(rb)
            }
            val now = ExtTranslate.beatNow()
            live.text = "الموديل الشغّال دلوقتي: " + now + (if (now != sel) "  (المختار: " + sel + ")" else "")
        }

        val bTest = Button(act)
        bTest.text = "🧪 جرّب الموديل المختار"
        bTest.setOnClickListener {
            val m = sel; val k = ek.text.toString(); val u = eu.text.toString()
            if (k.isBlank()) { res.text = "⚠ اكتب المفتاح الأول" } else {
                res.text = "⏳ بيجرّب " + m + "…"
                Thread {
                    val r = ExtTranslate.beatTest(u, k, m)
                    act.runOnUiThread { res.text = (if (r.first) "✅ " else "❌ ") + m + " · " + r.second + "ms — " + r.third; paint() }
                }.also { it.isDaemon = true }.start()
            }
        }
        val bAud = Button(act)
        bAud.text = "🎙 جرّب الصوت على الموديل المختار"
        bAud.setOnClickListener {
            val m = sel; val k = ek.text.toString(); val b = eu.text.toString()
            if (k.isBlank()) { res.text = "⚠ اكتب المفتاح الأول" } else {
                res.text = "⏳ بيبعت صوت قصير لـ " + m + "…"
                Thread {
                    val r = ExtTranslate.beatAudioTest(b, k, m)
                    act.runOnUiThread { res.text = (if (r.first) "✅ " else "❌ ") + m + " · " + r.second + "ms — " + r.third + (if (r.first) "\n(ده يثبت إنه بيقبل الصوت بس، مش إنه بيفهم الكلام — جرّب فيديو قصير)" else "\nلو الموديل بيرفض الصوت، «ترجمة ببيت» مش هتشتغل بيه — استخدم «وشبر + Beat»."); paint() }
                }.also { it.isDaemon = true }.start()
            }
        }
        val bAdd = Button(act)
        bAdd.text = "➕ ضيف الموديل"
        bAdd.setOnClickListener {
            val m = em.text.toString().trim()
            if (m.isEmpty() || m.contains(' ')) { res.text = "⚠ اكتب اسم موديل صحيح (من غير مسافات)" } else {
                if (m !in models) models.add(m)
                sel = m; em.setText(""); paint()
            }
        }
        val bDel = Button(act)
        bDel.text = "🗑 احذف المختار من القايمة"
        bDel.setOnClickListener {
            if (models.size <= 1) { res.text = "⚠ لازم يفضل موديل واحد على الأقل" } else {
                models.remove(sel); sel = models.first(); paint()
            }
        }

        box.addView(live)
        box.addView(ek)
        box.addView(eu)
        box.addView(TextView(act).apply { text = "الموديلات (اختار اللي يترجم):"; textSize = 13f; setPadding(0, dp(10), 0, dp(2)) })
        box.addView(rg)
        box.addView(bTest)
        box.addView(bAud)
        box.addView(res)
        box.addView(em)
        box.addView(bAdd)
        box.addView(bDel)
        box.addView(cbRot)
        box.addView(cbPrim)
        box.addView(TextView(act).apply {
            text = "ملاحظة: الموديلات المجانية ليها حد طلبات في الدقيقة (طلب واحد قبل أول شحن، وحتى ١٠ بعده). لو موديل وقف بالحد، «بدّل تلقائي» بيكمّل بالتاني."
            textSize = 11f; setPadding(0, dp(8), 0, dp(4))
        })
        paint()

        AlertDialog.Builder(act).setTitle("🔑 Beat API")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("حفظ") { _, _ ->
                val u = eu.text.toString().trim().trimEnd('/')
                Cfg.put("beat_url", if (u == ExtTranslate.BEAT_URL) "" else u)
                Cfg.put("beat_key", ek.text.toString().trim())
                Cfg.put("beat_models", models.joinToString("\n"))
                Cfg.put("beat_model", sel)
                Cfg.put("beat_rotate", if (cbRot.isChecked) "on" else "off")
                ExtTranslate.clearBlock("beat"); ExtTranslate.beatResetCool(); ExtTranslate.beatLive = sel
                if (cbPrim.isChecked) { Cfg.put("ext_primary", "beat"); Cfg.put("ext_on_beat", "on") }
                else if (ExtTranslate.primaryId() == "beat") Cfg.put("ext_primary", "")
                onDone()
            }
            .setNegativeButton("إلغاء", null).show()
    }
}
