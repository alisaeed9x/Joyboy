package com.tttt.subtitler

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** أدوات الشكل الزجاجي (Glass UI) + أنيميشن الضغط — موحّدة لكل الشاشات */
object Glass {
    fun theme(): Theme = Themes.current()   // (v907) المصدر الوحيد = ثيم MeeTube

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    fun blend(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(m(Color.alpha(a), Color.alpha(b)), m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    /** كارت زجاجي: تدرّج خفيف من فوق لتحت + حد. الفاتح بيميل لون الثيم، الغامق بيلمّع من فوق */
    fun drawable(th: Theme, radiusPx: Float, strokePx: Int, stroke: Int, a: Int = 255): GradientDrawable {
        if (MtGlass.on() && MtSkin.on() && a >= 0xE0) return MtGlass.glassCard(th, radiusPx, Resources.getSystem().displayMetrics.density)   // (v897) زجاج شفاف فوق الخلفية المتحركة
        if (MtGlass.on()) return MtGlass.shapePx(alpha(th.card, a), if (stroke == th.border) MtGlass.stroke(th) else stroke, radiusPx, if (stroke == th.border) MtGlass.hair(Resources.getSystem().displayMetrics.density) else maxOf(1, strokePx))   // (v893) زجاج MeeTube
        val cols = if (th.isLight) intArrayOf(alpha(th.card, a), alpha(blend(th.card, th.primary, 0.05f), a))
        else intArrayOf(alpha(blend(th.card, Color.WHITE, 0.08f), a), alpha(th.card, a))
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols).apply { cornerRadius = radiusPx; setStroke(strokePx, stroke) }
    }

    /** زرار أساسي بتدرّج لون الثيم */
    fun primaryFill(th: Theme, radiusPx: Float): GradientDrawable =
        if (MtGlass.on()) MtGlass.primary(th, radiusPx * 2f)   // (v893) كبسولة بلمعة
        else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(th.primary, blend(th.primary, Color.BLACK, 0.22f))).apply { cornerRadius = radiusPx }

    /** أي نافذة جذرها له خلفية مدوّرة (box) بيتحوّل لزجاجي — بيتنده تلقائي من GDialog */
    fun restyle(v: View) {
        val bg = v.background as? GradientDrawable ?: return
        val r0 = try { bg.cornerRadius } catch (_: Throwable) { 0f }
        if (r0 < 1f) return
        val th = theme(); val d = v.resources.displayMetrics.density
        if (MtGlass.on()) { v.background = MtGlass.sheetBg(th, MtGlass.radPx(r0, d), d); return }   // (v893) شيت زجاجي: سطح شبه شفاف + لمعة + حافة مضيئة
        v.background = drawable(th, r0, maxOf(1, d.toInt()), if (th.isLight) th.border else alpha(Color.WHITE, 0x2E), 0xF2)
    }

    /** تعتيم خفيف + بلور للخلفية (أندرويد 12+). على الأقدم: تعتيم بس */
    fun styleWindow(d: Dialog, windowBlur: Boolean = true) {   // (v910) windowBlur=false: الشيت بيبلّر نفسه بـSheetGlass فمش محتاجين بلور النافذة كلها
        val w = d.window ?: return
        try {
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (MtGlass.on()) {   // (v893) MeeTube: تعتيم خفيف + بلور تقيل ورا الشيت
                w.setDimAmount(0.32f)
                if (windowBlur && (d !is GDialog || d.frostTarget == null)) { if (Build.VERSION.SDK_INT >= 31) { w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); val lp0 = w.attributes; lp0.blurBehindRadius = 30; w.attributes = lp0 } }   // (v905) الشيت المثلّج بيبلّر نفسه والصفحة تفضل حادّة؛ بلور النافذة كلها للدايلوجات التانية
                return
            }
            w.setDimAmount(0.40f)
            if (Build.VERSION.SDK_INT >= 31) { w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); val lp = w.attributes; lp.blurBehindRadius = 26; w.attributes = lp }
        } catch (_: Throwable) {}
    }

    private fun content(d: Dialog): View? = d.window?.decorView?.findViewById(android.R.id.content)

    /** (v910) نافذة الأكتيفيتي اللي الشيت فوقها — الصفحة اللي SheetGlass (MeeTube) بيبلّرها */
    fun hostWindowOf(ctx: Context?): Window? {
        var x: Context? = ctx
        while (x is android.content.ContextWrapper) { if (x is android.app.Activity) return x.window; x = x.baseContext }
        return null
    }

    /** (v910) بلور MeeTube الحقيقي (SheetGlass.frost: نسخة مبلّرة من الصفحة اللي تحت + tint + حافة) لأي شيت Dialog عادي (مش GDialog). target = اللوح اللي بيتبلّر */
    fun frostSheet(d: Dialog, target: View, cornerPx: Float, tint: Int = 0) {   // (v915) tint: 0 = لون الشيت الافتراضي · غير كده = لون زجاج مخصوص (زجاج الشريط)
        if (!MtGlass.on()) return
        try {
            val host = hostWindowOf(d.context) ?: return
            if (tint != 0) com.newtube.mobile.ui.common.SheetGlass.frost(target, host, cornerPx, tint)
            else com.newtube.mobile.ui.common.SheetGlass.frost(target, host, cornerPx)
        } catch (_: Throwable) {}
    }

    fun enter(d: Dialog) {
        val c = content(d) ?: return
        val g = d.window?.attributes?.gravity ?: 0
        val bottom = ((g and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM)
        val dens = c.resources.displayMetrics.density
        if (MtGlass.on()) {   // (v893) MeeTube: الشيت بينزلق لفوق/النافذة بتكبر بمنحنى emphasized-decelerate
            c.animate().cancel()
            c.alpha = 0f; c.scaleX = if (bottom) 1f else 0.92f; c.scaleY = if (bottom) 1f else 0.92f; c.translationY = (if (bottom) 72f else 16f) * dens
            c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(Skins.dur(MtGlass.ENTER_MS)).setInterpolator(MtGlass.EMPHASIZED_DECELERATE).start()
            return
        }
        c.animate().cancel()
        c.alpha = 0f; c.scaleX = 0.93f; c.scaleY = 0.93f; c.translationY = (if (bottom) 56f else 14f) * dens
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(240L).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    fun exit(d: Dialog, done: () -> Unit) {
        val c = content(d)
        if (c == null) { done(); return }
        c.animate().cancel()
        if (MtGlass.on()) {   // (v893) خروج: بيتسارع ويتلاشى
            c.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(12f * c.resources.displayMetrics.density).setDuration(Skins.dur(MtGlass.EXIT_MS)).setInterpolator(MtGlass.EMPHASIZED_ACCELERATE).withEndAction { done() }.start()
            return
        }
        c.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(140L).withEndAction { done() }.start()
    }

    /** تأثير ضغطة: بيصغّر العنصر وهو مضغوط ويرجّعه بنطّة خفيفة. مابيعترضش الـ click */
    fun pressable(v: View) {
        if (MtGlass.on()) { MtGlass.press(v); return }   // (v893) press_3d
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().setStartDelay(0L).scaleX(0.95f).scaleY(0.95f).alpha(0.88f).setDuration(Skins.dur(90)).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().setStartDelay(0L).scaleX(1f).scaleY(1f).alpha(1f).setDuration(Skins.dur(200)).setInterpolator(Skins.interp(2.4f)).start()
            }
            false
        }
    }

    fun hasEdit(v: View?): Boolean {
        if (v is EditText) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasEdit(v.getChildAt(i))) return true
        return false
    }
}

/**
 * Dialog بتصميم التطبيق: خلفية زجاجية + بلور + أنيميشن دخول/خروج،
 * ورجوع (زرار/سويب) بيتعالج في onBackPressed — ده اللي بيشتغل مع إيماءة الرجوع (القديم setOnKeyListener ماكانش بيوصله السويب).
 * onBack بترجّع true لو استهلكت الرجوع (مثلاً رجوع خطوة جوه الإعدادات) وإلا بتتقفل النافذة.
 */
open class GDialog(ctx: Context, theme: Int = 0) : Dialog(ctx, theme) {
    var onBack: (() -> Boolean)? = null
    var plain = false
    private var closing = false
    private var seq = 0
    /** (v905) شيت زجاج مثلّج (SheetFrost): الـview اللي خلفيته بتتغلّف بنسخة مبلّرة من الصفحة اللي وراها + نصف قطر الركن بالـpx */
    var frostTarget: View? = null
    var frostCorner = 0f

    init { requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun setContentView(view: View) {
        super.setContentView(view)
        if (!plain) Glass.restyle(view)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (onBack?.invoke() == true) return
        super.onBackPressed()
    }

    /** (v953) PlayerTrace: لمس جوه الشيت بيتسجّل مباشرة من هنا (من غير reflection) — بيتجاهل التكرار لو الـcallback hook سجّله */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        try { com.newtube.mobile.ui.common.PlayerTrace.touchWindow(window?.decorView, ev) } catch (_: Throwable) {}
        return super.dispatchTouchEvent(ev)
    }

    override fun show() {
        try { com.newtube.mobile.ui.common.PlayerTrace.mark("sheet show (" + javaClass.simpleName + ")") } catch (_: Throwable) {}   // (v953)
        seq++; closing = false
        if (!plain) Glass.styleWindow(this)
        try { TvFocus.attachWindow(context, window, Glass.theme().primary) } catch (_: Throwable) {}   // (v477 م3)
        super.show()
        try { Glass.enter(this) } catch (_: Throwable) {}
        frostTarget?.let { t -> try { com.newtube.mobile.ui.common.SheetGlass.frost(t, hostWindow(), frostCorner) } catch (_: Throwable) {} }   // (v909) SheetGlass.frost الأصلي من MeeTube (بدل SheetFrost)
    }

    /** (v909) نافذة الأكتيفيتي اللي الدايلوج فوقها — الصفحة اللي SheetGlass بيبلّرها */
    private fun hostWindow(): Window? {
        var x: Context? = context
        while (x is android.content.ContextWrapper) { if (x is android.app.Activity) return x.window; x = x.baseContext }
        return null
    }

    private fun realDismiss() { try { super.dismiss() } catch (_: Throwable) {} }

    override fun dismiss() {
        val v = window?.decorView
        if (closing) return
        if (!isShowing || v == null || Looper.myLooper() != Looper.getMainLooper()) { realDismiss(); return }
        closing = true
        try { com.newtube.mobile.ui.common.PlayerTrace.sheetDismiss() } catch (_: Throwable) {}   // (v953) بداية أنيميشن الخروج
        val my = seq
        try { Glass.exit(this) { if (closing && my == seq) { closing = false; realDismiss() } } } catch (_: Throwable) { closing = false; realDismiss(); return }
        // احتياطي لو الأنيميشن اتلغى
        v.postDelayed({ if (closing && my == seq) { closing = false; realDismiss() } }, 400)
    }
}

/** بديل AlertDialog.Builder بنفس الـ API لكن بشكل زجاجي بلون الثيم (مش شكل النظام) */
class GAlert(private val ctx: Context) {
    private var title: CharSequence? = null
    private var msg: CharSequence? = null
    private var view: View? = null
    private var items: Array<out CharSequence>? = null
    private var itemsCb: DialogInterface.OnClickListener? = null
    private var pos: CharSequence? = null; private var posCb: DialogInterface.OnClickListener? = null
    private var neg: CharSequence? = null; private var negCb: DialogInterface.OnClickListener? = null
    private var neu: CharSequence? = null; private var neuCb: DialogInterface.OnClickListener? = null
    private var multiItems: Array<out CharSequence>? = null
    private var multiChecked: BooleanArray? = null
    private var multiCb: DialogInterface.OnMultiChoiceClickListener? = null
    private var cancelable = true
    private var cancelCb: DialogInterface.OnCancelListener? = null

    fun setTitle(t: CharSequence?): GAlert { title = t; return this }
    fun setMessage(m: CharSequence?): GAlert { msg = m; return this }
    fun setView(v: View?): GAlert { view = v; return this }
    fun setItems(a: Array<out CharSequence>, l: DialogInterface.OnClickListener?): GAlert { items = a; itemsCb = l; return this }
    fun setMultiChoiceItems(a: Array<out CharSequence>, c: BooleanArray?, l: DialogInterface.OnMultiChoiceClickListener?): GAlert { multiItems = a; multiChecked = c; multiCb = l; return this }
    fun setPositiveButton(t: CharSequence, l: DialogInterface.OnClickListener?): GAlert { pos = t; posCb = l; return this }
    fun setNegativeButton(t: CharSequence, l: DialogInterface.OnClickListener?): GAlert { neg = t; negCb = l; return this }
    fun setNeutralButton(t: CharSequence, l: DialogInterface.OnClickListener?): GAlert { neu = t; neuCb = l; return this }
    fun setCancelable(b: Boolean): GAlert { cancelable = b; return this }
    fun setOnCancelListener(l: DialogInterface.OnCancelListener?): GAlert { cancelCb = l; return this }

    private fun build(): Pair<Dialog, LinearLayout?> {
        val th = Glass.theme(); val ui = Ui(ctx, th); val dm = ctx.resources.displayMetrics
        val tvk = TvFocus.onCtx(ctx)   // (v477 م3) تلفزيون: خطوط أكبر ونافذة أعرض
        val d = GDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14)); background = if (MtGlass.on() && !tvk) MtGlass.sheetBg(th, ui.dp(MtGlass.rad(22)).toFloat(), dm.density) else ui.box(th.card, th.border, 22)   // (v901) دايلوج زجاجي
        }
        title?.let { root.addView(ui.text(it.toString(), if (tvk) 22f else 18f, th.primary, true), LinearLayout.LayoutParams(-1, -2)) }
        msg?.let { m ->
            val tv = ui.text(m.toString(), if (tvk) 17f else 14f, th.text).apply { setLineSpacing(0f, 1.2f); setPadding(0, ui.dp(10), 0, ui.dp(4)); setTextIsSelectable(!tvk) }
            root.addView(MaxHeightScroll(ctx, (dm.heightPixels * 0.5f).toInt()).apply { addView(tv) }, LinearLayout.LayoutParams(-1, -2))
        }
        view?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            // (v125) أي محتوى طويل جوه الدايلوج لازم يتسكرول (كان بيتقص من غير ما يتحرك)
            val scrollable = v is android.widget.ScrollView || v is android.widget.AbsListView || v is android.widget.HorizontalScrollView
            val wrapped: View = if (scrollable) v else MaxHeightScroll(ctx, (dm.heightPixels * 0.6f).toInt()).apply { addView(v) }
            root.addView(wrapped, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        var itemsCol: LinearLayout? = null
        items?.let { arr ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            arr.forEachIndexed { i, s ->
                val tv = ui.text(s.toString(), if (tvk) 18f else 15f, th.text).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); background = ui.box(th.surface, th.border, 12) }
                Glass.pressable(tv)
                tv.setOnClickListener { d.dismiss(); itemsCb?.onClick(d, i) }
                col.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
            }
            itemsCol = col
            if (tvk) col.post { try { for (k in 0 until col.childCount) { val c = col.getChildAt(k); if ((c as? TextView)?.text?.startsWith("✓") == true) { c.requestFocus(); break } } } catch (_: Throwable) {} }   // (v803) تلفزيون: التركيز يبدأ على الاختيار الحالي (✓)
            root.addView(MaxHeightScroll(ctx, (dm.heightPixels * 0.6f).toInt()).apply { addView(col) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        multiItems?.let { arr ->
            val st = multiChecked ?: BooleanArray(arr.size)
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            arr.forEachIndexed { i, s ->
                val tv = ui.text((if (st[i]) "☑ " else "☐ ") + s.toString(), if (tvk) 18f else 15f, th.text).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); background = ui.box(th.surface, th.border, 12) }
                Glass.pressable(tv)
                tv.setOnClickListener {
                    st[i] = !st[i]
                    tv.text = (if (st[i]) "☑ " else "☐ ") + s.toString()
                    multiCb?.onClick(d, i, st[i])
                }
                col.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
            }
            itemsCol = col
            root.addView(MaxHeightScroll(ctx, (dm.heightPixels * 0.6f).toInt()).apply { addView(col) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        val btns = ArrayList<Triple<CharSequence, DialogInterface.OnClickListener?, Int>>()
        pos?.let { btns.add(Triple(it, posCb, DialogInterface.BUTTON_POSITIVE)) }
        neu?.let { btns.add(Triple(it, neuCb, DialogInterface.BUTTON_NEUTRAL)) }
        neg?.let { btns.add(Triple(it, negCb, DialogInterface.BUTTON_NEGATIVE)) }
        if (btns.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
            for ((t, cb, which) in btns) {
                val main = which == DialogInterface.BUTTON_POSITIVE
                val b = IconTextView(ctx).apply {
                    text = t; textSize = if (tvk) 17f else 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                    setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
                    setTextColor(if (main) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
                    background = if (main) Glass.primaryFill(th, ui.dp(Skins.rad(12)).toFloat()) else ui.box(th.surface, th.border, 12)
                }
                Glass.pressable(b)
                b.setOnClickListener { d.dismiss(); cb?.onClick(d, which) }
                row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            }
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) })
        }
        if (MtGlass.on() && !tvk) { d.frostTarget = root; d.frostCorner = ui.dp(MtGlass.rad(22)).toFloat() }   // (v905) SheetFrost
        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf((dm.widthPixels * 0.9f).toInt(), ui.dp(if (tvk) 560 else 420)), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or (if (Glass.hasEdit(view)) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0))
        }
        d.setCancelable(cancelable); d.setCanceledOnTouchOutside(cancelable)
        cancelCb?.let { d.setOnCancelListener(it) }
        return Pair(d, itemsCol)
    }

    /** بيبني الدايلوج من غير ما يعرضه (زي AlertDialog.Builder.create) */
    fun create(): Dialog = build().first

    fun show(): Dialog {
        val (d, col) = build()
        d.show()
        col?.let { Motion.stagger(it, 8, 35L) }
        return d
    }
}
