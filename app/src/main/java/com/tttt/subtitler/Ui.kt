package com.tttt.subtitler

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** مكوّنات واجهة بنفس شكل الأصل (radius 14/8، حدود، أمبر كلون أساسي) */
class Ui(val ctx: Context, var th: Theme) {
    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun box(fill: Int, stroke: Int, r0: Int, sw: Int = 1): GradientDrawable {
        val r = Skins.rad(r0)   // (v352) استدارة حسب وضع الواجهة
        if (Skins.cur().ios) {   // (v816) iOS: كروت مسطّحة من غير حدود · الباقي بحد شعري خفيف
            return GradientDrawable().apply {
                setColor(fill); cornerRadius = dp(r).toFloat()
                if (fill != th.card) { if (stroke == th.border) setStroke(1, Glass.alpha(th.border, 0x80)) else if (stroke != Color.TRANSPARENT) setStroke(dp(sw), stroke) }
            }
        }
        // الكروت (لون card) بتتحوّل لزجاج بتدرّج خفيف؛ الباقي زي ما هو
        if (fill == th.card && r0 >= 12) return Glass.drawable(th, dp(r).toFloat(), dp(sw), stroke)
        return GradientDrawable().apply { setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(dp(sw), stroke) }
    }
    private val plex: android.graphics.Typeface? by lazy { try { android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/ibm_plex.ttf") } catch (_: Exception) { null } }
    fun text(t: String, size: Float = 14f, color: Int = th.text, bold: Boolean = false) = IconTextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) {
            val sk = Skins.cur()
            if (sk.ios || sk.look == "w11" || sk.look == "amoled") typeface = IosSkin.bold   // (v817) ويندوز 11/أموليد: خط medium نضيف (Segoe مش متاح على أندرويد)
            else if (sk.look == "xp") setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)   // (v817) XP: Tahoma-like
            else plex?.let { typeface = it } ?: setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }
    fun slider(label: String, init: Int, lo: Int, hi: Int, unit: String, onChange: (Int) -> Unit): LinearLayout {
        val tv = text("$label: ${init.coerceIn(lo, hi)}$unit", 13f, th.text)
        val sb = SeekBar(ctx).apply {
            max = hi - lo; progress = init.coerceIn(lo, hi) - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tv.text = "$label: ${p + lo}$unit"; if (u) onChange(p + lo) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(sb) }
    }
    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; if (Skins.cur().ios) setPadding(dp(16), dp(14), dp(16), dp(16)) else if (Skins.cur().look == "xp") setPadding(dp(11), dp(9), dp(11), dp(11)) else setPadding(dp(14), dp(12), dp(14), dp(14))
        background = box(th.card, th.border, 14)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
    }
    fun input(hint: String, value: String, lines: Int = 1) = EditText(ctx).apply {
        this.hint = Icons.plain(hint); setText(value); setTextColor(th.text); setHintTextColor(th.muted); textSize = 14f
        background = box(th.surface, th.border, 10); setPadding(dp(10), dp(11), dp(10), dp(11))
        setOnFocusChangeListener { v, f -> v.background = box(th.surface, if (f) th.primary else th.border, 10) }
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START } else setSingleLine()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
    }
    fun button(t: String, primary: Boolean = false, f: () -> Unit) = IconButton(ctx).apply {
        text = t; isAllCaps = false; stateListAnimator = null; minHeight = dp(48)   // (v458) هدف لمس قياسي
        setTextColor(if (primary) (if (Skins.cur().ios || Skins.cur().look == "xp") Color.WHITE else if (th.isLight) Color.WHITE else Color.BLACK) else if (Skins.cur().ios) th.primary else th.text)   // (v475) كان مبلوع جوه التعليق اللي فوق فالأزرار كلها كانت بلون الخط الافتراضي الباهت
        background = if (primary) Glass.primaryFill(th, dp(Skins.rad(12)).toFloat()) else if (Skins.cur().ios) GradientDrawable().apply { setColor(Glass.alpha(th.primary, 0x24)); cornerRadius = dp(Skins.rad(12)).toFloat() }   // (v816) iOS: tinted
            else if (Skins.cur().look == "xp") GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,   // (v817) XP: زرار Luna مجسّم (أبيض ← بيج) بحد أزرق غامق
                if (th.isLight) intArrayOf(0xFFFFFFFF.toInt(), 0xFFF1F0EA.toInt(), 0xFFD6D0C5.toInt()) else intArrayOf(Glass.blend(th.card, Color.WHITE, 0.14f), th.card, Glass.blend(th.card, Color.BLACK, 0.25f))).apply {
                cornerRadius = dp(Skins.rad(12)).toFloat(); setStroke(dp(1), if (th.isLight) 0xFF003C74.toInt() else th.border) }
            else if (Skins.cur().look == "amoled") GradientDrawable().apply { setColor(th.card); cornerRadius = dp(Skins.rad(12)).toFloat(); setStroke(dp(1), Glass.alpha(th.primary, 0x66)) }   // (v817) أموليد: أسود بحد لون التمييز
            else box(th.surface, th.border, 12)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
        Glass.pressable(this)
        setOnClickListener { f() }
    }
    fun switchRow(t: String, on: Boolean, onChange: (Boolean) -> Unit): Switch = IconSwitch(ctx).apply {
        text = t; isChecked = on; setTextColor(th.text); textSize = 14f; setPadding(0, dp(6), 0, dp(6)); minHeight = dp(48)   // (v458)
        if (Skins.cur().ios) try {   // (v816) سويتش iOS: مقبض أبيض ومسار أخضر
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF34C759.toInt(), Glass.alpha(th.muted, 0x55)))
        } catch (_: Throwable) {}
        when (Skins.cur().look) {   // (v817) سويتشات السكنات الكاملة
            "w11" -> try {   // ويندوز 11: مفعّل = مسار لون التمييز ومقبض أبيض · مقفول = مسار خافت ومقبض رمادي
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(if (th.isLight) Color.WHITE else Color.BLACK, th.muted))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.primary, Glass.alpha(th.muted, 0x40)))
            } catch (_: Throwable) {}
            "xp" -> try {   // XP: مسار أخضر شريط التقدم لما يشتغل
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFFF5F4EC.toInt())
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF2E9E3F.toInt(), 0xFF8FA6C4.toInt()))
            } catch (_: Throwable) {}
            "amoled" -> try {   // أموليد: مقبض لون التمييز على مسار غامق
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.primary, 0xFF6E6E6E.toInt()))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Glass.alpha(th.primary, 0x55), 0xFF1C1C1C.toInt()))
            } catch (_: Throwable) {}
        }
        setOnCheckedChangeListener { _, v -> onChange(v); if (isShown) { Motion.pop(this, 0.94f); SkinSound.event(ctx, 1); if (Skins.cur().ios) try { Haptic.tap(this) } catch (_: Throwable) {} } }
    }
    /** صف chips بيلف على أكتر من سطر؛ بيرجّع الـ container، والاختيار بيتبلّغ بـ onPick */
    fun chips(items: List<String>, selected: () -> String, onPick: (String) -> Unit): LinearLayout {
        val all = ArrayList<Pair<String, TextView>>()
        var built = false
        fun paint() = all.forEach { (k, v) ->
            val on = k == selected()
            val amo = Skins.cur().look == "amoled"
            v.setTextColor(if (amo && on) th.primary else if (on) (if (th.isLight || Skins.cur().look == "xp") Color.WHITE else Color.BLACK) else th.text)
            v.background = if (Skins.cur().ios) { val fc = if (on) th.primary else Glass.alpha(th.muted, 0x26); box(fc, fc, 20) }
                else if (amo) GradientDrawable().apply { setColor(if (on) Glass.alpha(th.primary, 0x26) else th.card); cornerRadius = dp(Skins.rad(20)).toFloat(); setStroke(dp(1), if (on) th.primary else th.border) }   // (v817) أموليد: المختار = تعبئة خفيفة + حد التمييز
                else box(if (on) th.primary else th.surface, if (on) th.primary else th.border, 20)
        }
        fun build(col: LinearLayout) {
        var row: LinearLayout? = null; var w = 0
        val max = ctx.resources.displayMetrics.widthPixels - dp(70)
        for (label in items) {
            val tv = IconTextView(ctx).apply { text = label; textSize = 13f; setPadding(dp(12), dp(11), dp(12), dp(11)) }   // (v458) chips ~48dp لمس
            tv.measure(0, 0); val tw = tv.measuredWidth + dp(8)
            if (row == null || w + tw > max) { row = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }; col.addView(row); w = 0 }
            w += tw
            row.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            all.add(label to tv)
            Glass.pressable(tv)
            tv.setOnClickListener { onPick(label); paint(); Motion.pop(tv, 0.86f) }
        }
        paint()
        }
        // (v313) بتتبني أول ما تتقاس بس — chips الإعدادات المقفولة مابتكلّفش حاجة وقت فتح البرنامج
        val col = object : LinearLayout(ctx) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                if (!built) { built = true; build(this) }
                super.onMeasure(wSpec, hSpec)
            }
        }
        col.orientation = LinearLayout.VERTICAL
        return col
    }
    /** قسم قابل للطي (الموبايل صغير) */
    fun section(title: String, open: Boolean, vararg views: View): LinearLayout {
        // (v487) تلفزيون: الأقسام مفتوحة دايمًا من غير طي (الريموت مش بيعمل «تكبير/تصغير») — عنوان بس والعناصر تحته
        if (try { DeviceUi.isTv(ctx) } catch (_: Throwable) { false }) {
            val c = card(); val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            views.forEach { body.addView(it) }
            c.addView(text(title, 17f, th.primary, true).apply { setPadding(0, dp(6), 0, dp(8)) }); c.addView(body); return c
        }
        val c = card(); val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = if (open) View.VISIBLE else View.GONE }
        views.forEach { body.addView(it) }
        val lkS = Skins.cur().look
        fun arr(o: Boolean) = if (lkS == "w11") (if (o) "⌄ " else "› ") else if (o) "▾ " else "▸ "   // (v817) ويندوز 11: شيفرونات
        val head = text(arr(open) + title, 16f, th.primary, true).apply {
            setPadding(0, dp(6), 0, dp(6))
            if (lkS == "xp") {   // (v817) XP: رأس قسم زي Task Pane — تدرّج أبيض ← أزرق فاتح ونص أزرق Luna
                setPadding(dp(8), dp(6), dp(8), dp(6)); setTextColor(if (th.isLight) 0xFF215DC6.toInt() else th.primary)
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (th.isLight) intArrayOf(0xFFFFFFFF.toInt(), 0xFFC6D3F7.toInt()) else intArrayOf(th.card, Glass.blend(th.card, th.primary, 0.35f))).apply { cornerRadius = dp(3).toFloat() }
            }
        }
        Glass.pressable(head)
        head.setOnClickListener {
            val o = body.visibility != View.VISIBLE
            try { android.transition.TransitionManager.beginDelayedTransition((c.parent as? android.view.ViewGroup) ?: c, android.transition.AutoTransition().setDuration(220)) } catch (_: Exception) {}
            body.visibility = if (o) View.VISIBLE else View.GONE
            if (o) Motion.stagger(body, 6, 30L)
            head.text = arr(o) + title
        }
        c.addView(head); c.addView(body); return c
    }
}

/** شريط الحالة والتنقل بلون الثيم، وأيقونات غامقة لو الثيم فاتح (عشان تبان على الأبيض) */
@Suppress("DEPRECATION")
fun android.app.Activity.applyBars(th: Theme) {
    window.statusBarColor = th.bg; window.navigationBarColor = th.bg
    if (android.os.Build.VERSION.SDK_INT >= 23 && th.isLight) {
        var f = window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (android.os.Build.VERSION.SDK_INT >= 26) f = f or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }
}
