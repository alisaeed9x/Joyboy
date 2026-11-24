package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** (v1270) شاشة اختيار لغة مرتبة: قايمة صفوف بأسماء اللغات بلغتها الأصلية، المختار عليه ✓، وخيار تلقائي فوق. */
object LangPicker {
    /** current: كود اللغة أو "auto". onPick بيستلم الكود أو "auto". */
    fun show(a: Activity, title: String, current: String, cancelable: Boolean, onPick: (String) -> Unit) {
        val th = Themes.current(); val ui = Ui(a, th)
        val dlg = Dialog(a)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = ui.box(th.surface, th.border, 18)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(10))
        }
        root.addView(ui.text("🌐 " + title, 18f, th.text, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun row(code: String, label: String, sub: String) {
            val on = code == current
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                background = ui.box(if (on) th.primary else th.bg, if (on) th.primary else th.border, 12)
            }
            val fg = if (on) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text
            val nm = ui.text(label, 16f, fg, on).apply { gravity = Gravity.START }
            r.addView(nm, LinearLayout.LayoutParams(0, -2, 1f))
            if (sub.isNotEmpty()) r.addView(ui.text(sub, 12f, if (on) fg else th.muted), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
            r.addView(ui.text(if (on) "✓" else "", 17f, fg, true), LinearLayout.LayoutParams(ui.dp(22), -2))
            r.setOnClickListener { try { dlg.dismiss() } catch (_: Throwable) {}; onPick(code) }
            list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        row("auto", "🌐 Auto", I18n.nameOf(I18n.systemCode()))
        for ((c, n) in I18n.LANGS) row(c, n, c)
        val sv = ScrollView(a).apply { addView(list, ViewGroup.LayoutParams(-1, -2)) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        if (cancelable) root.addView(ui.button("✕") { try { dlg.dismiss() } catch (_: Throwable) {} }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        dlg.setContentView(root)
        dlg.setCancelable(cancelable)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = a.resources.displayMetrics
            setLayout((dm.widthPixels * 0.9f).toInt().coerceAtMost(ui.dp(420)), (dm.heightPixels * 0.82f).toInt())
        }
        dlg.show()
    }

    /** صف واحد للإعدادات: بيوري اللغة الحالية ويفتح الشاشة عند الضغط. */
    fun settingRow(a: Activity, ui: Ui, title: String, current: () -> String, onPick: (String) -> Unit): View {
        fun label(): String { val c = current(); return if (c == "auto") "🌐 Auto · " + I18n.nameOf(I18n.systemCode()) else I18n.nameOf(c) }
        val b = ui.button(label() + "  ▾") { }
        b.setOnClickListener { show(a, title, current(), true) { c -> onPick(c); try { b.text = label() + "  ▾" } catch (_: Throwable) {} } }
        return b
    }
}
