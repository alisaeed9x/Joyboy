package com.tttt.subtitler

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v909) هيدر صفحة بنفس تركيبة هيدر صفحات MeeTube (fragment_mobile_settings_page.xml):
 * زرار رجوع 40dp بخلفية `bg_glass_circle` + `ic_mobile_back_arrow` + `press_3d`، والعنوان 20sp medium بلون on_surface.
 * كله من موارد MeeTube نفسها (MeeRes)؛ لو مورد ناقص بيرجع لزرار نص. مش بيعمل بلور حي فوق القايمة (ده محتاج GlassSource حوالين المحتوى).
 */
object MtHeader {
    fun bar(act: Activity, ui: Ui, th: Theme, title: String, sub: String = "", onBack: (() -> Unit)? = null, right: View? = null): LinearLayout {
        val hd = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
        }
        if (onBack != null) {
            val arrow = MeeRes.drawable("ic_mobile_back_arrow")
            val circle = MeeRes.drawable("bg_glass_circle")
            val back: View = if (arrow != null && circle != null) ImageButton(act).apply {
                background = circle; setImageDrawable(arrow); setColorFilter(th.text)
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); contentDescription = "رجوع"
                rotation = 180f   // السهم في MeeTube بيشاور شمال (LTR)؛ الصفحات عندنا RTL فبنلفّه (rotation مش scale عشان press_3d بيتحكم في scaleX)
            } else ui.text("→", 20f, th.text)
            MtGlass.press(back)
            back.setOnClickListener { onBack() }
            hd.addView(back, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(8) })
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text(title, 20f, th.text).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) col.addView(ui.text(sub, 12f, th.muted))
        hd.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) hd.addView(right, LinearLayout.LayoutParams(-2, -2))
        return hd
    }
}
