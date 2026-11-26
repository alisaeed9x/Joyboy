package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** (v1260) عرض ملاحظات الإصدار: كل بند مرقّم في سطره وعليه كبسولة ملوّنة (ميزة جديدة/ميزة أخضر · تحسين/تعديل أزرق · حذف أحمر · إصلاح برتقالي)، وقسم «ملاحظات» في الآخر. صيغة البند: «1. [إصلاح] النص». (v1328) «ميزة» و«تحسين» بقوا بادجات ملونة زي «إصلاح» بالظبط (بطلب علي) */
object Notes {
    private val chips = linkedMapOf("ميزة جديدة" to 0xFF2E9E5B.toInt(), "ميزة" to 0xFF2E9E5B.toInt(), "تعديل" to 0xFF2F7FE0.toInt(), "تحسين" to 0xFF2F7FE0.toInt(), "حذف" to 0xFFD9453D.toInt(), "إصلاح" to 0xFFF08A24.toInt())   // (v1328) كل أنواع البنود بتطلع بادج ملوّن مش «إصلاح» بس
    private val itemRe = Regex("^(\\d+)[.)]\\s+(.*)$")
    private val chipRe = Regex("^\\[([^\\]]+)]\\s*(.*)$")

    private class Sec(val name: String) { val items = ArrayList<Pair<String, String>>() }

    /** (v1318) استنتاج لون الكبسولة من اسم القسم لما البند نفسه مفيهوش [تسمية] */
    private fun chipOf(sec: String): String = when {
        sec.contains("إصلاح") -> "إصلاح"
        sec.contains("جديد") || sec.contains("ميز") || sec.contains("إضاف") -> "ميزة جديدة"
        sec.contains("حذف") || sec.contains("إزال") || sec.contains("إلغاء") -> "حذف"
        sec.contains("تعديل") -> "تعديل"
        sec.contains("تحسين") -> "تحسين"
        else -> ""
    }

    /** (v1318) توحيد صيغة الملاحظات قبل العرض: بيفهم البنود المسطرة (- / • / *) والبولد ** وعناوين الأقسام من غير ## — رجّع الباتش نوتس الملونة بعد ما صيغة ملفات CHANGES اتغيّرت لـmarkdown عادي */
    private fun norm(text: String): List<String> {
        val out = ArrayList<String>()
        var sec = ""
        var n = 0
        for (raw0 in text.lines()) {
            val ln = raw0.trim().replace("**", "")
            if (ln.isEmpty() || ln.startsWith(">")) continue
            if (ln.startsWith("▌") || ln.startsWith("#")) { sec = ln.trimStart('▌', '#', ' '); out.add("▌" + sec); continue }
            val m = Regex("^[-*•]\\s+|^\\d+[.)]\\s+").find(ln)
            if (m != null) {
                var body = ln.substring(m.range.last + 1).trim()
                body = Regex("^[\\p{So}\\s]+").replace(body, "")   // شيل الإيموجي من أول البند
                if (body.isEmpty()) continue
                n += 1
                val chip = if (Regex("^\\[[^]]+]").containsMatchIn(body)) "" else chipOf(sec)
                out.add(n.toString() + ". " + (if (chip.isNotEmpty()) "[$chip] " else "") + body)
                continue
            }
            if (ln.length <= 42 && chipOf(ln).isNotEmpty()) { sec = ln; out.add("▌" + ln); continue }   // عنوان قسم من غير ##
            out.add(ln)
        }
        return out
    }

    fun render(a: Activity, ui: Ui, th: Theme, text: String): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val secs = ArrayList<Sec>()
        var cur: Sec? = null
        for (raw in norm(text)) {   // (v1318)
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
