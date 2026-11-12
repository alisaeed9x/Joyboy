package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v821) أدوات واجهة مشتركة لشاشات ريموت IR (الكتالوج · البروفايلات · الإعدادات) بنفس هوية Joyboy الداكنة،
 * + بناء «ريموت واقعي» من أزرار الريموت الفعلية (بدون أي زرار مخترع).
 */
class IrUiKit(val act: Activity) {
    companion object {
        const val BG = 0xFF0F1830.toInt(); const val CARD = 0xFF14203A.toInt(); const val CARD2 = 0xFF1E2A44.toInt()
        const val STROKE = 0xFF2B3C60.toInt(); const val BLUE = 0xFF2563EB.toInt(); const val GREEN = 0xFF0F7B4A.toInt()
        const val RED = 0xFFC62828.toInt(); const val MUTED = 0xFFB8BDC9.toInt(); const val SOFT = 0xFF8FA3C7.toInt(); const val AMBER = 0xFFFFB74D.toInt()
    }
    val dm = act.resources.displayMetrics
    fun dp(v: Int) = (v * dm.density).toInt()
    val widthDp: Int get() = (dm.widthPixels / dm.density).toInt()

    fun shape(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radiusDp).toFloat(); if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

    fun text(t: String, sp: Float, col: Int = Color.WHITE, bold: Boolean = false, grav: Int = Gravity.START or Gravity.CENTER_VERTICAL) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(col); gravity = grav; if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** زرار/بلاطة قابلة للضغط والتركيز بالريموت (D-pad): بيكبر شوية وبيتحدد بإطار أبيض لما ياخد الفوكس */
    fun clickable(v: View, base: GradientDrawable, onTap: () -> Unit) {
        v.background = base; v.isFocusable = true
        v.setOnClickListener { Haptic.tap(it); onTap() }
        v.setOnFocusChangeListener { x, f -> x.scaleX = if (f) 1.05f else 1f; x.scaleY = if (f) 1.05f else 1f; base.setStroke(dp(if (f) 2 else 1), if (f) Color.WHITE else STROKE) }
    }

    fun btn(t: String, fill: Int = CARD2, sp: Float = 14f, onTap: () -> Unit) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
        setPadding(dp(10), dp(8), dp(10), dp(8)); clickable(this, shape(fill, 14, 0x44FFFFFF), onTap)
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    fun lpm(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    fun vbox() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(grav: Int = Gravity.CENTER) = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = grav }

    /** سطر معلومات/تحذير */
    fun banner(t: String, col: Int = AMBER) = text(t, 12f, col).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = shape(0x33FFFFFF and 0x22FFFFFF or 0x22000000, 12, col, 1) }
}

object IrRemoteView {
    /** رمز/اسم الزرار كما يظهر على الريموت */
    fun labelOf(key: String): String = IrTv.LABELS[key]?.takeIf { it.isNotEmpty() } ?: when (key) {
        "RED", "GREEN", "YELLOW", "BLUE" -> "●"
        else -> key.replace('_', ' ')
    }

    private fun fillOf(k: String): Int = when (k) {
        "POWER" -> 0xFFC62828.toInt(); "OK", "SEL", "ENTER" -> 0xFF1D4ED8.toInt(); "RED" -> 0xFFD32F2F.toInt(); "GREEN" -> 0xFF2E7D32.toInt()
        "YELLOW" -> 0xFFF9A825.toInt(); "BLUE" -> 0xFF1565C0.toInt(); else -> 0xFF26324F.toInt()
    }

    /**
     * ريموت واقعي من الأزرار الفعلية: باور/مصدر/كتم ← أرقام ← دايرة التنقل (▲▼◀▶ + OK) ← رجوع/رئيسية/قايمة ← صوت وقنوات ← ألوان ← ميديا ← باقي الأزرار.
     * [onKey] بيتنده بمفتاح الزرار (للتجربة).
     * (v855) [full] = true (الافتراضي): الشكل ثابت لكل الريموتات — كل الأماكن بتتعرض بنفس الترتيب، والزرار اللي مش في الريموت ده بيظهر باهت ومش بيتضغط
     * (مفيش زرار مخترع ومفيش مكان بيختفي فالريموتات متناسقة). [full] = false: الشكل القديم (الناقص بيختفي).
     */
    fun build(kit: IrUiKit, keys: Set<String>, full: Boolean = true, onKey: (View, String) -> Unit): View {
        val used = HashSet<String>()
        val body = kit.vbox().apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1B2540.toInt(), 0xFF0B1226.toInt())).apply { cornerRadius = kit.dp(30).toFloat(); setStroke(kit.dp(2), 0xFF34507A.toInt()) }
            setPadding(kit.dp(12), kit.dp(16), kit.dp(12), kit.dp(18))
        }
        val bw = minOf(kit.widthDp - 24, 380)
        fun key(k: String?, hDp: Int = 50, textSp: Float = 16f): View {
            if (k == null) return View(kit.act).apply { layoutParams = kit.lpm(0, kit.dp(hDp), 3, 3, 3, 3, 1f) }
            if (k !in keys) {
                if (!full) return View(kit.act).apply { layoutParams = kit.lpm(0, kit.dp(hDp), 3, 3, 3, 3, 1f) }
                val lb0 = labelOf(k)
                return TextView(kit.act).apply {   // مكان ثابت باهت: الزرار ده مش في الريموت
                    text = lb0; textSize = if (lb0.length > 6) 11f else textSp; setTextColor(0x44FFFFFF); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
                    contentDescription = "$k (مش موجود)"; background = kit.shape(0x14FFFFFF, 16, 0x14FFFFFF)
                    layoutParams = kit.lpm(0, kit.dp(hDp), 3, 3, 3, 3, 1f)
                }
            }
            used.add(k)
            val lb = labelOf(k)
            return TextView(kit.act).apply {
                text = lb; textSize = if (lb.length > 6) 11f else textSp; setTextColor(if (k == "YELLOW") Color.BLACK else Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
                contentDescription = k
                kit.clickable(this, kit.shape(fillOf(k), 16, 0x33FFFFFF)) { onKey(this, k) }
                layoutParams = kit.lpm(0, kit.dp(hDp), 3, 3, 3, 3, 1f)
            }
        }
        fun row(vararg ks: String?, h: Int = 50) {
            if (!full && ks.none { it != null && it in keys }) return
            val r = kit.hbox().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
            for (k in ks) r.addView(key(k, h))
            body.addView(r, kit.lp(-1, -2))
        }
        fun section(t: String) = body.addView(kit.text(t, 10.5f, IrUiKit.SOFT, false, Gravity.CENTER), kit.lpm(-1, -2, 0, 8, 0, 2))

        row("POWER", "SRC", "MUTE", "INFO")
        val digits = (1..9).map { it.toString() }
        if (full || digits.any { it in keys } || "0" in keys) {
            section("الأرقام")
            for (i in 0 until 3) row(*digits.subList(i * 3, i * 3 + 3).toTypedArray(), h = 44)
            row(null, "0", null, h = 44)
        }
        // دايرة التنقل
        if (full || listOf("UP", "DOWN", "LEFT", "RIGHT", "OK", "SEL", "ENTER").any { it in keys }) {
            section("التنقل")
            val size = kit.dp(minOf(bw - 24, 250))
            val ring = FrameLayout(kit.act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0F3441.toInt()); setStroke(kit.dp(2), 0xFF2CC4D2.toInt()) } }
            val b = size / 3
            fun nav(k: String, gx: Int, gy: Int, grav: Int) {
                val has = k in keys
                if (!has && !full) return
                if (has) used.add(k)
                val v = TextView(kit.act).apply {
                    text = labelOf(k); textSize = 22f; setTextColor(if (has) Color.WHITE else 0x44FFFFFF); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false; contentDescription = if (has) k else "$k (مش موجود)"
                    if (has) kit.clickable(this, kit.shape(if (k == "OK" || k == "SEL" || k == "ENTER") 0xFF1D4ED8.toInt() else 0xFF0B2338.toInt(), 28, 0xFF2F6A8C.toInt()))
                    { onKey(this, k) } else background = kit.shape(0x14FFFFFF, 28, 0x14FFFFFF)
                }
                ring.addView(v, FrameLayout.LayoutParams(b, b, grav).apply { setMargins(gx * kit.dp(2), gy * kit.dp(2), 0, 0) })
            }
            nav("UP", 0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL); nav("DOWN", 0, 0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            nav("LEFT", 0, 0, Gravity.START or Gravity.CENTER_VERTICAL); nav("RIGHT", 0, 0, Gravity.END or Gravity.CENTER_VERTICAL)
            nav(listOf("OK", "SEL", "ENTER").firstOrNull { it in keys } ?: "OK", 0, 0, Gravity.CENTER)
            body.addView(ring, LinearLayout.LayoutParams(size, size).apply { topMargin = kit.dp(4) })
        }
        row("BACK", "HOME", "MENU", "EXIT")
        row("GUIDE", "FAV", "AUDIO", "LAST")   // (v855) INFO كان متكرر هنا وفي أول صف
        // صوت وقنوات: عمودين زي الريموت الحقيقي
        val volK = listOf("VOLUP", "VOLDN").filter { it in keys }; val chK = listOf("CHUP", "CHDN").filter { it in keys }
        if (full || volK.isNotEmpty() || chK.isNotEmpty()) {
            section("الصوت والقنوات")
            val r = kit.hbox().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
            fun pill(a: String, bK: String, title: String): View {
                val col = kit.vbox().apply { gravity = Gravity.CENTER; background = kit.shape(0xFF14203A.toInt(), 26, 0xFF2B3C60.toInt()); setPadding(kit.dp(4), kit.dp(6), kit.dp(4), kit.dp(6)) }
                col.addView(key(a, 54, 18f)); col.addView(kit.text(title, 10f, IrUiKit.SOFT, false, Gravity.CENTER), kit.lp(-2, -2)); col.addView(key(bK, 54, 18f))
                return col
            }
            if (full || volK.isNotEmpty()) r.addView(pill("VOLUP", "VOLDN", "VOL"), kit.lpm(0, -2, 8, 0, 8, 0, 1f))
            if (full || chK.isNotEmpty()) r.addView(pill("CHUP", "CHDN", "CH"), kit.lpm(0, -2, 8, 0, 8, 0, 1f))
            body.addView(r, kit.lp(-1, -2))
        }
        if (full || listOf("RED", "GREEN", "YELLOW", "BLUE").any { it in keys }) { section("الألوان"); row("RED", "GREEN", "YELLOW", "BLUE", h = 38) }
        if (full || listOf("REW", "PLAY", "PAUSE", "STOP", "FF", "REC", "PREVIOUS", "NEXT").any { it in keys }) {
            section("الميديا"); row("REW", "PLAY", "PAUSE", "FF", h = 44); row("PREVIOUS", "STOP", "REC", "NEXT", h = 44)
        }
        val rest = keys.filter { it !in used }.sorted()
        if (rest.isNotEmpty()) {
            section("باقي الأزرار (${rest.size})")
            for (ch in rest.chunked(4)) row(*(ch + List(4 - ch.size) { null }).toTypedArray(), h = 44)
        }
        if (full) body.addView(kit.text("الأزرار الباهتة مش موجودة في الريموت ده", 10.5f, IrUiKit.SOFT, false, Gravity.CENTER), kit.lpm(-1, -2, 0, 10, 0, 0))
        return FrameLayout(kit.act).apply { addView(body, FrameLayout.LayoutParams(kit.dp(bw), -2, Gravity.CENTER_HORIZONTAL)) }
    }
}
