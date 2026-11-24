package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** (v1260) عرض ملاحظات الإصدار: كل بند مرقّم في سطره وعليه كبسولة ملوّنة (ميزة جديدة أخضر · تعديل أزرق · حذف أحمر · إصلاح برتقالي)، وقسم «ملاحظات» في الآخر. صيغة البند: «1. [إصلاح] النص». */
object Notes {
    private val chips = linkedMapOf("ميزة جديدة" to 0xFF2E9E5B.toInt(), "تعديل" to 0xFF2F7FE0.toInt(), "حذف" to 0xFFD9453D.toInt(), "إصلاح" to 0xFFF08A24.toInt())
    private val itemRe = Regex("^(\\d+)[.)]\\s+(.*)$")
    private val chipRe = Regex("^\\[([^\\]]+)]\\s*(.*)$")

    private class Sec(val name: String) { val items = ArrayList<Pair<String, String>>() }

    fun render(a: Activity, ui: Ui, th: Theme, text: String): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val secs = ArrayList<Sec>()
        var cur: Sec? = null
        for (raw in text.lines()) {
            val ln = raw.trim()
            if (ln.isEmpty()) continue
            if (ln.startsWith("▌") || ln.startsWith("#")) { cur = Sec(ln.trimStart('▌', '#', ' ')); secs.add(cur); continue }
            if (cur == null) { cur = Sec(""); secs.add(cur) }
            val m = itemRe.find(ln)
            val body = m?.groupValues?.get(2) ?: ln
            val cm = chipRe.find(body)
            if (cm != null && chips.containsKey(cm.groupValues[1].trim())) cur.items.add(cm.groupValues[1].trim() to cm.groupValues[2])
            else if (m == null && cur.items.isNotEmpty()) { val l = cur.items.removeAt(cur.items.size - 1); cur.items.add(l.first to (l.second + " " + body)) }
            else cur.items.add("" to body)
        }
        val ordered = secs.filter { !it.name.contains("ملاحظات") } + secs.filter { it.name.contains("ملاحظات") }
        for (s in ordered) {
            if (s.name.isNotEmpty()) col.addView(ui.text(s.name, 15f, th.primary, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14); bottomMargin = ui.dp(2) })
            var n = 0
            for ((lab, body) in s.items) {
                n++
                val item = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(ui.text("$n.", 14f, th.muted, true).apply { setPadding(0, 0, ui.dp(8), 0) })
                if (lab.isNotEmpty()) top.addView(TextView(a).apply {
                    this.text = lab; textSize = 11.5f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); includeFontPadding = false
                    setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(4))
                    background = GradientDrawable().apply { setColor(chips[lab] ?: 0xFF888888.toInt()); cornerRadius = ui.dp(12).toFloat() }
                })
                item.addView(top)
                item.addView(ui.text(body, 14f, th.text).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
                col.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            }
        }
        return col
    }
}
