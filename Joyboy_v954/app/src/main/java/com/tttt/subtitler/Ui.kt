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
        if (MtGlass.on()) {
            val rpx = dp(MtGlass.rad(r0)).toFloat(); val dn = ctx.resources.displayMetrics.density
            if (MtSkin.on()) {   // (v897) زجاج حقيقي شفاف: الكارت/السطح مابقوش لون صلب
                if (fill == th.card || fill == th.surface) {
                    val g = if (fill == th.card) MtGlass.glassCard(th, rpx, dn) else MtGlass.glassWell(th, rpx, dn)
                    if (stroke != th.border && Color.alpha(stroke) != 0) g.setStroke(maxOf(1, dp(sw)), stroke)   // خط التمييز (مختار · فوكس) يفضل ظاهر
                    return g
                }
            }
            return MtGlass.shape(th, fill, stroke, rpx, dn, dp(sw))   // (v893) زجاج MeeTube: استدارة أكبر + خط شعري
        }
        val r = Skins.rad(r0)   // (v352) استدارة حسب وضع الواجهة
        // الكروت (لون card) بتتحوّل لزجاج بتدرّج خفيف؛ الباقي زي ما هو
        if (fill == th.card && r0 >= 12) return Glass.drawable(th, dp(r).toFloat(), dp(sw), stroke)
        return GradientDrawable().apply { setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(dp(sw), stroke) }
    }
    private val plex: android.graphics.Typeface? by lazy { try { android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/ibm_plex.ttf") } catch (_: Exception) { null } }
    fun text(t: String, size: Float = 14f, color: Int = th.text, bold: Boolean = false) = IconTextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) {
            plex?.let { typeface = it } ?: setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }
    /** (v927) نص شرح اتشال: مكانه View مخفي (عشان نداءات القوايم تفضل تتكومبايل) */
    fun noHint(): IconTextView = IconTextView(ctx).apply { visibility = View.GONE }
    fun slider(label: String, init: Int, lo: Int, hi: Int, unit: String, onChange: (Int) -> Unit): LinearLayout {
        val tv = text("$label: ${init.coerceIn(lo, hi)}$unit", 13f, th.text)
        val sb = (if (MtGlass.on()) com.newtube.mobile.ui.common.GlassSlider(ctx) else SeekBar(ctx)).apply { layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR;   // (v909) GlassSlider الأصلي من MeeTube
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
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14))
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
        setTextColor(if (primary) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)   // (v475) كان مبلوع جوه التعليق اللي فوق فالأزرار كلها كانت بلون الخط الافتراضي الباهت
        background = if (primary) Glass.primaryFill(th, dp(Skins.rad(12)).toFloat())
            else if (MtGlass.on()) MtGlass.pill(th, MtGlass.fill(th), dp(24).toFloat(), ctx.resources.displayMetrics.density)   // (v893) كبسولة زجاجية
            else box(th.surface, th.border, 12)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
        Glass.pressable(this)
        setOnClickListener { f() }
    }
    /**
     * (v909) سويتش MeeTube الأصلي (`com.newtube.mobile.ui.common.GlassSwitch`): نفس صف إعدادات MeeTube —
     * النص جنب السويتش والصف كله clickable هو اللي بيقلب (السويتش الأصلي بيعمل click على صاحبه). تلفزيون/فشل بناء ← الشكل القديم.
     */
    fun switchRow(t: String, on: Boolean, onChange: (Boolean) -> Unit): SwitchRow {
        val row = SwitchRow(ctx)
        val glass: CompoundButton? = if (MtGlass.on()) try { com.newtube.mobile.ui.common.GlassSwitch(ctx) } catch (_: Throwable) { null } else null
        if (glass == null) {
            val sw = plainSwitchRow(t, on, onChange)
            row.inner = sw; row.addView(sw, LinearLayout.LayoutParams(-1, -2))
            return row
        }
        row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(2), 0, dp(2)); row.isClickable = true; row.isFocusable = true
        val label = text(t, 14f, th.text).apply { setPadding(0, dp(6), dp(10), dp(6)) }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(glass, LinearLayout.LayoutParams(-2, -2))
        glass.isChecked = on
        glass.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.setOnClickListener { glass.isChecked = !glass.isChecked }   // الصف هو اللي بيقلب (clickOwner في GlassSwitch)
        row.inner = glass
        return row
    }
    private fun plainSwitchRow(t: String, on: Boolean, onChange: (Boolean) -> Unit): Switch = IconSwitch(ctx).apply {   // (v909) الشكل القديم: تلفزيون + احتياطي لو سويتش MeeTube مااتبناش
        text = t; isChecked = on; setTextColor(th.text); textSize = 14f; setPadding(0, dp(6), 0, dp(6)); minHeight = dp(48)   // (v458)
        if (MtGlass.on()) try {   // (v893) سويتش MeeTube: مقبض أبيض · مسار لون التمييز مفعّل / رمادي غامق مقفول (v905: الزجاجي بيرسم نفسه)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.primary, 0xFF3A3A3F.toInt()))
        } catch (_: Throwable) {}
        setOnCheckedChangeListener { _, v -> onChange(v); if (isShown) Motion.pop(this, 0.94f) }
    }
    /** صف chips بيلف على أكتر من سطر؛ بيرجّع الـ container، والاختيار بيتبلّغ بـ onPick */
    fun chips(items: List<String>, selected: () -> String, onPick: (String) -> Unit): LinearLayout {
        val all = ArrayList<Pair<String, TextView>>()
        var built = false
        fun paint() = all.forEach { (k, v) ->
            val on = k == selected()
            v.setTextColor(if (MtGlass.on()) (if (on) th.text else th.muted) else if (on) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
            v.background = if (MtGlass.on()) MtGlass.chip(th, on, dp(23).toFloat(), ctx.resources.displayMetrics.density)   // (v893) زجاج مضيء للمختار
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
        fun arr(o: Boolean) = if (o) "▾ " else "▸ "
        val head = text(arr(open) + title, 16f, th.primary, true).apply {
            setPadding(0, dp(6), 0, dp(6))
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

/** (v909) صف سويتش: بيلفّ سويتش MeeTube الأصلي (أو IconSwitch القديم) ويكشف isChecked زي Switch عشان باقي الكود */
class SwitchRow(ctx: Context) : LinearLayout(ctx) {
    var inner: CompoundButton? = null
    var isChecked: Boolean
        get() = inner?.isChecked ?: false
        set(v) { inner?.isChecked = v }
}
