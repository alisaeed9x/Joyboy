package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

/**
 * (v289) شاشة البرومبتات (الإعدادات ← 🌐 الترجمة ← 📝 البرومبتات).
 * قايمة بكل البرومبتات (PromptCatalog) ← دوسة على واحد تفتحه: تشوف النص الحالي، تنسخه، تلصق بدله نص تاني، تحفظ.
 * المحفوظ بيتستخدم فورًا بدل الأصلي (PromptStore) ومن «↩ رجّع الأصلي» بيرجع زي ما كان.
 */
object PromptsUi {
    private val openSecs = HashSet<String>()

    /** قراءة ملفات assets الأصلية (من غير أي تعديل) عشان «الأصلي» يفضل متاح دايمًا */
    private fun rawReader(act: Activity): (String) -> String =
        { p -> act.assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }

    fun show(act: Activity, ui: Ui) {
        val th = ui.th
        val read = rawReader(act)
        var lang = Cfg.str("lang", "فصحى")
        var style = Cfg.str("style", "حرفي")
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val dlg = ui.sheet(act, "📝 البرومبتات", listOf(box), false)

        fun collapsible(title: String, note: String, body: View): View {
            val c = ui.card()
            val open = openSecs.contains(title)
            val head = ui.text((if (open) "▾ " else "▸ ") + title, 15f, th.primary, true).apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
            val inner = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = if (open) View.VISIBLE else View.GONE }
            if (note.isNotBlank()) inner.addView(ui.text(note, 12f, th.muted))
            inner.addView(body)
            Glass.pressable(head)
            head.setOnClickListener {
                val o = inner.visibility != View.VISIBLE
                if (o) openSecs.add(title) else openSecs.remove(title)
                inner.visibility = if (o) View.VISIBLE else View.GONE
                head.text = (if (o) "▾ " else "▸ ") + title
            }
            c.addView(head); c.addView(inner)
            return c
        }

        lateinit var render: () -> Unit

        fun row(e: PEntry): View {
            val mod = PromptStore.has(e.key)
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                background = ui.box(th.surface, if (mod) th.primary else th.border, 12)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) }
            }
            Glass.pressable(r)
            r.addView(ui.text((if (mod) "✏ " else "") + e.title, 14f, th.text, true))
            if (mod) r.addView(ui.text("معدّل — بيتستخدم النص بتاعك", 11f, th.primary))
            if (e.note.isNotBlank()) r.addView(ui.text(e.note, 11f, th.muted))
            r.setOnClickListener { edit(act, ui, e) { render() } }
            return r
        }

        render = {
            box.removeAllViews()
            box.addView(ui.text("دوس على أي برومبت: تشوفه · تنسخه · تلصق بدله نص تاني · تحفظ. اللي تحفظه بيشتغل من الطلب الجاي من غير ريستارت. اللي علامته ✏ معدّل.", 12f, th.muted))
            val n = PromptStore.count()
            if (n > 0) {
                box.addView(ui.button("↩ رجّع كل البرومبتات للأصلي ($n معدّل)") {
                    AlertDialog.Builder(act).setMessage("ترجّع $n برومبت معدّل للأصلي؟")
                        .setPositiveButton("رجّع") { _, _ -> PromptStore.resetAll(); render() }
                        .setNegativeButton("لأ", null).show()
                })
            }
            // قوالب الوضع 1: بتتحدد باللهجة والأسلوب
            val tplBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            lateinit var fillTpl: () -> Unit
            fillTpl = {
                tplBox.removeAllViews()
                try { PromptCatalog.templates(read, lang, style).forEach { tplBox.addView(row(it)) } }
                catch (e: Throwable) { LogStore.err("PromptsUi:tpl", e); tplBox.addView(ui.text("⚠ مش قادر أقرا القوالب: " + (e.message ?: ""), 12f, th.muted)) }
            }
            val pick = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            pick.addView(ui.text("اللهجة", 12f, th.muted))
            pick.addView(ui.chips(PromptCatalog.langs, { lang }) { lang = it; fillTpl() })
            pick.addView(ui.text("الأسلوب", 12f, th.muted))
            pick.addView(ui.chips(PromptCatalog.styles, { style }) { style = it; fillTpl() })
            pick.addView(tplBox)
            fillTpl()
            box.addView(collapsible("📄 قوالب الوضع 1 (حسب لغة الصوت)", "القالب الأساسي في وضع المفاتيح 1. اختار اللهجة والأسلوب وبعدين القالب. التعديل بيتطبّق على القالب ده بس.", pick))
            for (g in PromptCatalog.groups(read)) {
                val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                g.entries.forEach { body.addView(row(it)) }
                box.addView(collapsible(g.title, g.note, body))
            }
        }
        render()
        dlg.show()
    }

    private fun edit(act: Activity, ui: Ui, e: PEntry, onDone: () -> Unit) {
        val th = ui.th
        val orig = e.default()
        val cur = PromptStore.pick(e.key, orig)
        val et: EditText = ui.input("نص البرومبت", PromptStore.toEditor(cur), 14)
        et.textSize = 13f
        val views = ArrayList<View>()
        if (e.note.isNotBlank()) views.add(ui.text(e.note, 12f, th.muted))
        val shownOrig = PromptStore.toEditor(orig)
        val need = e.required.filter { shownOrig.contains(it) }
        if (need.isNotEmpty()) views.add(ui.text("⚠ لازم تفضل موجودة في النص: " + need.joinToString("  "), 12f, th.muted))
        views.add(et)
        val d = ui.sheet(act, e.title, views, false, { onDone() })

        fun doSave(text: String) {
            val t = PromptStore.fromEditor(text)
            if (t.isBlank() || t.trim() == orig.trim()) { PromptStore.reset(e.key); Notice.show(act, "↩ ماحصلش تعديل — الأصلي شغّال", 2200L); d.dismiss(); return }
            if (PromptStore.set(e.key, t)) { Notice.show(act, "✅ اتحفظ — بيشتغل من الطلب الجاي", 2400L); d.dismiss() }
            else Notice.show(act, "⚠ ماقدرتش أحفظ الملف", 2800L)
        }

        val body = et.parent as? LinearLayout
        fun add(v: View) { body?.addView(v) ?: views.add(v) }
        add(ui.button("💾 حفظ", true) {
            val text = et.text.toString()
            val missing = need.filter { !text.contains(it) }
            if (missing.isEmpty()) doSave(text)
            else AlertDialog.Builder(act).setMessage("ناقص من النص: " + missing.joinToString("  ") + "\nالبرومبت ممكن يبوظ من غيرهم. تحفظ برضو؟")
                .setPositiveButton("احفظ برضو") { _, _ -> doSave(text) }
                .setNegativeButton("لأ", null).show()
        })
        add(ui.button("📋 انسخ النص ده") {
            try {
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("prompt", et.text.toString()))
                Notice.show(act, "📋 اتنسخ", 1600L)
            } catch (x: Throwable) { LogStore.err("PromptsUi:copy", x); Notice.show(act, "⚠ النسخ فشل", 2200L) }
        })
        add(ui.button("📥 الصق بدله اللي في الكليبورد") {
            try {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()
                if (t.isNullOrBlank()) Notice.show(act, "الكليبورد فاضي", 2000L)
                else { et.setText(t); Notice.show(act, "📥 اتلصق في الخانة — دوس 💾 حفظ عشان يشتغل", 2800L) }
            } catch (x: Throwable) { LogStore.err("PromptsUi:paste", x); Notice.show(act, "⚠ اللصق فشل", 2200L) }
        })
        add(ui.button("📄 حط النص الأصلي في الخانة") { et.setText(shownOrig); Notice.show(act, "اتحط الأصلي في الخانة — دوس 💾 لو عايز تثبّته", 2600L) })
        if (PromptStore.has(e.key)) add(ui.button("↩ امسح التعديل (رجّع الأصلي)") {
            PromptStore.reset(e.key); Notice.show(act, "↩ رجع الأصلي", 1800L); d.dismiss()
        })
        d.show()
    }
}
