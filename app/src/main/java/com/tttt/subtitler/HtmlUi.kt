package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/** مكوّنات الواجهة بنفس شكل نسخة الـ HTML (دوائر علوية، كروت سودا، كبسولة تحكم، شبكة أزرار، شريط الشاشة الكاملة) */

/** شريط التقدم: أزرق = موضع التشغيل، كهرماني = المترجم (زي fs-progress-played / fs-progress-translated) */
class DualProgress(ctx: Context, val th: Theme) : View(ctx) {
    var played = 0f
    /** (قديم) نسبة المترجم المتصل — الشريط الأخضر بقى بيتحسب من الجمل الفعلية (cov) */
    var translated = 0f
    var dragging = false
    /** mini = شكل شريط التقدم الرئيسي في الأصل (.progress-bar-bg: خط 4dp بتدرج كهرماني→سماوي + خريطة التغطية تحته) */
    var mini = false
    /** (v104) شريط رفيع جدًا دايم تحت الفيديو: 3 خطوط (برتقالي/تقدم/أخضر) من غير لمس */
    var thin = false
    var onSeek: ((Float) -> Unit)? = null
    /** أثناء السحب (قبل رفع الصباع): الفيديو بيتحرك معاك */
    var onScrub: ((Float) -> Unit)? = null
    var onDrag: ((Boolean) -> Unit)? = null
    /** مدة الفيديو بالثواني (لتحويل أزمنة الجمل لنسب على الشريط) */
    var durSec = 0.0
    /** مجالات الترجمة الفعلية [بداية,نهاية] بالثواني — الشريط الأخضر بيتقطع بين الجمل مش بين الباتشات */
    var cov: List<DoubleArray> = emptyList()
    /** مجالات الجمل اللي لسه هتتغير (لهجة / إعادة صياغة / ضمائر) — شريط تالت فوق، بيختفي لما كلها تتغير */
    var pend: List<DoubleArray> = emptyList()
    /** (v160) الجمل اللي بتتنقّح دلوقتي — أزرق على نفس الخط العلوي */
    var refine: List<DoubleArray> = emptyList()
    /** (v160) الأجزاء اللي اتحمّلت من الفيديو الأونلاين — على نفس الخط العلوي (سماوي) */
    var buf: List<DoubleArray> = emptyList()
    /** (v226) أماكن جمل الوضع البصري — علامات زرقاء تحت الأخضر */
    var vis: List<DoubleArray> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    /** (v445) 0..1: الشريط بيتخن والمقبض بيكبر وبيتوهّج وانت ماسكه (أنيميشن ناعم) */
    private var grow = 0f
    private var growAnim: android.animation.ValueAnimator? = null
    private fun animGrow(to: Float) {
        growAnim?.cancel()
        growAnim = android.animation.ValueAnimator.ofFloat(grow, to).apply {
            duration = 170L; interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), if (thin) MeasureSpec.getSize(h).coerceAtLeast(3) else ((if (mini) 30 else 34) * d).toInt())
    }

    private fun strip(c: Canvas, w: Float, y0: Float, hh: Float, segs: List<DoubleArray>, color: Int, trackColor: Int?) {
        val rad = hh / 2f
        if (trackColor != null) { p.shader = null; p.color = trackColor; c.drawRoundRect(RectF(0f, y0, w, y0 + hh), rad, rad, p) }
        if (durSec <= 0.0 || segs.isEmpty()) return
        p.shader = null; p.color = color
        for (s in segs) {
            val a = (w * (s[0] / durSec)).toFloat().coerceIn(0f, w)
            val b = (w * (s[1] / durSec)).toFloat().coerceIn(0f, w)
            if (b <= a && a >= w) continue
            c.drawRoundRect(RectF(a, y0, maxOf(b, a + 1.5f * d).coerceAtMost(w), y0 + hh), rad, rad, p)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        p.style = Paint.Style.FILL; p.shader = null
        if (thin) {   // (v107) الارتفاع نسبة من الفيديو (2.5%): 3 خطوط متساوية
            val hh = height / 3f
            if (buf.isNotEmpty()) strip(c, w, 0f, hh, buf, 0xFF22D3EE.toInt(), null)
            if (pend.isNotEmpty()) strip(c, w, 0f, hh, pend, 0xFFF59E0B.toInt(), null)
            if (refine.isNotEmpty()) strip(c, w, 0f, hh, refine, 0xFF3B82F6.toInt(), null)
            p.color = 0x44FFFFFF; c.drawRect(0f, hh, w, 2f * hh, p)
            p.color = 0xFF3B82F6.toInt(); c.drawRect(0f, hh, w * played.coerceIn(0f, 1f), 2f * hh, p)
            strip(c, w, 2f * hh, hh, cov, 0xFF22C55E.toInt(), 0x33FFFFFF)
            return
        }
        if (mini) {
            val cy = height / 2f - 3f * d
            val tr = 2f * d
            // الشريط التالت (فوق): الجمل اللي لسه هتتغير — بيظهر بس لما فيه تغيير شغال
            if (buf.isNotEmpty()) strip(c, w, 3f * d, 3f * d, buf, 0xFF22D3EE.toInt(), 0x3322D3EE)
            if (pend.isNotEmpty()) strip(c, w, 3f * d, 3f * d, pend, 0xFFF59E0B.toInt(), if (buf.isEmpty()) 0x33F59E0B else null)
            if (refine.isNotEmpty()) strip(c, w, 3f * d, 3f * d, refine, 0xFF3B82F6.toInt(), null)
            p.color = th.border
            c.drawRoundRect(RectF(0f, cy - tr, w, cy + tr), 3f * d, 3f * d, p)
            val px = w * played.coerceIn(0f, 1f)
            if (px > 0f) {
                p.shader = LinearGradient(0f, 0f, px.coerceAtLeast(1f), 0f, th.primary, th.accent, Shader.TileMode.CLAMP)
                c.drawRoundRect(RectF(0f, cy - tr, px, cy + tr), 3f * d, 3f * d, p)
                p.shader = null
            }
            // الشريط التاني (تحت): أخضر = فيه جملة ترجمة فعلًا (بيتقطع عند الفجوات)
            strip(c, w, cy + tr + 3f * d, 4f * d, cov, 0xFF22C55E.toInt(), th.border)
            if (vis.isNotEmpty()) strip(c, w, cy + tr + 8.5f * d, 3f * d, vis, 0xFF3B82F6.toInt(), null)
            val r = (if (dragging) 5.5f else 3.5f) * d
            val tcx = px.coerceIn(r, w - r)
            p.style = Paint.Style.FILL; p.shader = null; p.color = Color.WHITE
            c.drawCircle(tcx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = th.accent
            c.drawCircle(tcx, cy, r, p)
            return
        }
        val cy = height / 2f; val th6 = (2f + 1.4f * grow) * d
        if (buf.isNotEmpty()) strip(c, w, 2f * d, 3f * d, buf, 0xFF22D3EE.toInt(), 0x3322D3EE)
        if (pend.isNotEmpty()) strip(c, w, 2f * d, 3f * d, pend, 0xFFF59E0B.toInt(), if (buf.isEmpty()) 0x33F59E0B else null)
        if (refine.isNotEmpty()) strip(c, w, 2f * d, 3f * d, refine, 0xFF3B82F6.toInt(), null)
        p.style = Paint.Style.FILL; p.shader = null
        p.color = 0x38FFFFFF
        c.drawRoundRect(RectF(0f, cy - th6, w, cy + th6), th6, th6, p)
        val px = w * played.coerceIn(0f, 1f)
        if (px > 0f) {
            p.shader = LinearGradient(0f, 0f, px.coerceAtLeast(1f), 0f, 0xFF60A5FA.toInt(), 0xFF3B82F6.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, cy - th6, px, cy + th6), th6, th6, p)
            p.shader = null
        }
        strip(c, w, cy + th6 + 4f * d, 3f * d, cov, 0xFF22C55E.toInt(), 0x2EFFFFFF)
        if (vis.isNotEmpty()) strip(c, w, cy + th6 + 8.5f * d, 3f * d, vis, 0xFF3B82F6.toInt(), null)
        val r = (5.5f + 3.5f * grow) * d
        val tx = px.coerceIn(r, w - r)
        p.style = Paint.Style.FILL; p.shader = null
        p.color = 0x303B82F6; c.drawCircle(tx, cy, r + (4f + 5f * grow) * d, p)   // هالة
        p.color = 0x55000000; c.drawCircle(tx, cy + 1.2f * d, r + 0.6f * d, p)    // ظل خفيف
        p.color = Color.WHITE; c.drawCircle(tx, cy, r, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (thin) return false
        if (width <= 0) return true
        val f = (e.x / width).coerceIn(0f, 1f)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { dragging = true; animGrow(1f); parent?.requestDisallowInterceptTouchEvent(true); played = f; invalidate(); onDrag?.invoke(true); onScrub?.invoke(f) }
            MotionEvent.ACTION_MOVE -> { played = f; invalidate(); onScrub?.invoke(f) }
            MotionEvent.ACTION_UP -> { dragging = false; animGrow(0f); played = f; invalidate(); onDrag?.invoke(false); onSeek?.invoke(f) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; animGrow(0f); invalidate(); onDrag?.invoke(false) }
        }
        return true
    }
}

/** (v249) كبسولة صغيرة (صوتي / بصري): نقطة حالة + نسبة + عدد جمل. بتتحط جنب بعض شمال فوق الفيديو. الشفافية من الإعدادات (badge_alpha) */
/** (v322) plain=true: من غير خلفية خاصة بيه (جوه كبسولة موحّدة). السطر الأول (النسبة + عدد الجمل) أكبر ومابيتقصّش، والتاني بيقصّ من آخره بنقط */
class TransBadge(ctx: Context, th: Theme, plain: Boolean = false) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val pct = IconTextView(ctx).apply { textSize = 11f; setTextColor(th.primary); typeface = android.graphics.Typeface.DEFAULT_BOLD; includeFontPadding = false; setSingleLine() }
    private val cnt = IconTextView(ctx).apply { textSize = 9f; setTextColor(0xB3FFFFFF.toInt()); includeFontPadding = false; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = (190 * ctx.resources.displayMetrics.density).toInt() }
    private val dot = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) }; visibility = View.GONE }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding((7 * d).toInt(), (3 * d).toInt(), (7 * d).toInt(), (3 * d).toInt())
        if (!plain) background = GradientDrawable().apply { setColor(0xE0141418.toInt()); cornerRadius = 9 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0x1FFFFFFF) }
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; addView(pct, LayoutParams(-2, -2)); addView(cnt, LayoutParams(-2, -2)) }   // (v330) wrap_content: match_parent كان بيخلّي TextView ما يطلبش قياس جديد لما الرقم يكبر
        addView(col, LayoutParams(-2, -2))
        addView(dot, LayoutParams((6 * d).toInt(), (6 * d).toInt()).apply { marginStart = (5 * d).toInt() })
    }
    /** نقطة الحالة: خضراء = الوضع شغّال، صفرا = شغّال بس مستني (كل موديلات FreeLLM في استراحة)، حمراء = واقف */
    fun setOn(on: Boolean, waiting: Boolean = false) { dot.visibility = View.VISIBLE; (dot.background as GradientDrawable).setColor(if (!on) 0xFFFF3B30.toInt() else if (waiting) 0xFFFFCC00.toInt() else 0xFF34C759.toInt()) }
    fun set(p: String, c: String) { var ch = false; if (pct.text.toString() != p) { pct.text = p; ch = true }; if (cnt.text.toString() != c) { cnt.text = c; ch = true }; val v = if (c.isBlank()) View.GONE else View.VISIBLE; if (cnt.visibility != v) { cnt.visibility = v; ch = true }; if (ch) requestLayout() }
}

/** (v249) كبسولة سحب النت — منفصلة عن الكبسولتين، نفس مكانهم (شمال فوق الفيديو). سطرين: 🌐 فيديوهات عادية · 🔗 فيديوهات النت */
class NetBadge(ctx: Context) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val a = IconTextView(ctx).apply { textSize = 8f; setTextColor(0xCCFFFFFF.toInt()); includeFontPadding = false; setSingleLine() }
    private val b = IconTextView(ctx).apply { textSize = 8f; setTextColor(0xCCFFFFFF.toInt()); includeFontPadding = false; setSingleLine() }
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding((7 * d).toInt(), (3 * d).toInt(), (7 * d).toInt(), (3 * d).toInt())
        background = GradientDrawable().apply { setColor(0xE0141418.toInt()); cornerRadius = 9 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0x1FFFFFFF) }
        addView(a, LayoutParams(-2, -2)); addView(b, LayoutParams(-2, -2))   // (v330) wrap_content (كان match_parent فالكبسولة ما بتكبرش والأرقام الكبيرة بتتقصّ)
    }
    fun set(local: String, online: String) { val x = "🌐 $local"; val y = "🔗 $online"; var ch = false; if (a.text.toString() != x) { a.text = x; ch = true }; if (b.text.toString() != y) { b.text = y; ch = true }; if (ch) requestLayout() }
}

class MaxHeightScroll(ctx: Context, var maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        // (v377) لو الأب حدّد ارتفاع (weight / match_parent) نمشي عليه — قبل كده كنا بنتجاهله فالصفحة بتطلع أطول من الشاشة المتاحة وآخرها بيتقص ومابتسكرولش لحد الآخر
        val mode = MeasureSpec.getMode(h); val sz = MeasureSpec.getSize(h)
        if (mode == MeasureSpec.EXACTLY) { super.onMeasure(w, h); return }
        val cap = if (mode == MeasureSpec.AT_MOST && sz > 0) minOf(maxH, sz) else maxH
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

class Ctl(
    val root: LinearLayout, val prog: DualProgress, val tEl: TextView, val tDur: TextView,
    val prev: TextView, val next: TextView, val play: TextView, val speed: TextView,
    val vol: SeekBar, val cc: TextView, val exp: TextView, val rot: TextView
)

class MemRow(val root: LinearLayout, val ramTv: TextView, val ramBar: ProgressBar, val cacheTv: TextView, val cacheBar: ProgressBar) {
    fun update(ctx: Context) {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory(); val mx = rt.maxMemory()
        val pct = if (mx > 0) (used * 100 / mx).toInt() else 0
        ramTv.text = "🧠 رام: $pct% (${used / 1048576} م.ب)"; ramBar.progress = pct
        val dir = ctx.cacheDir
        Thread {
            var sz = 0L
            try { dir.walkTopDown().forEach { f -> if (f.isFile) sz += f.length() } } catch (_: Exception) {}
            val cap = 512L * 1048576L
            val cp = (sz * 100 / cap).toInt().coerceIn(0, 100)
            root.post { cacheTv.text = "💾 كاش الفيديو: $cp% (${sz / 1048576} م.ب)"; cacheBar.progress = cp }
        }.apply { isDaemon = true }.start()
    }
}

fun Ui.onPrimary(): Int = if (th.isLight) Color.WHITE else Color.BLACK

/** .icon-tool-btn: دايرة 32dp، المفتاح بحدود متقطعة (accent) */
fun Ui.circleBtn(t: String, dashed: Boolean = false, f: () -> Unit): TextView = IconTextView(ctx).apply {
    text = t; textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (dashed) th.primary else th.muted)
    includeFontPadding = false; Glass.pressable(this)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(th.card)
        if (dashed) setStroke(dp(1), th.success, dp(3).toFloat(), dp(2).toFloat()) else setStroke(dp(1), th.border)
    }
    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(3), 0, dp(3), 0) }
    setOnClickListener { f() }
}

/** .model-badge */
fun Ui.pillChip(t: String, f: () -> Unit): TextView = IconTextView(ctx).apply {
    text = t; textSize = 11f; gravity = Gravity.CENTER; setTextColor(th.accent); setSingleLine()
    setPadding(dp(10), dp(4), dp(10), dp(4))
    background = box(th.card, th.border, 20)
    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), 0, dp(3), 0) }
    setOnClickListener { f() }
}

private fun Ui.gap25(): Int = (ctx.resources.displayMetrics.density * 2.5f).toInt()

/** .tools-row-btn: أيقونة + عنوان 8sp، radius 8، padding 5/4 */
fun Ui.gridBtn(icon: String, label: String, f: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
    setPadding(dp(4), dp(5), dp(4), dp(5))
    background = box(th.card, th.border, 8)
    addView(IconTextView(ctx).apply { text = icon; textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false })
    addView(text(label, 8f, th.muted, true).apply { gravity = Gravity.CENTER; maxLines = 2; includeFontPadding = false },
        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
    val m = gap25()
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(m, m, m, m) }
    setOnClickListener { f() }
}

/** شبكة أزرار: 6 أعمدة زي .tools-row (الخانات الفاضية بتتملى بفراغ عشان العرض يفضل ثابت) */
fun Ui.grid(items: List<View>, cols: Int = 6): LinearLayout {
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    val m = gap25()
    items.chunked(cols).forEach { rowItems ->
        val row = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        rowItems.forEach { row.addView(it) }
        for (i in rowItems.size until cols) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(m, 0, m, 0) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    return col
}



/** نافذة سفلية (bottom sheet) بنفس كروت الأصل. fixedH = ارتفاع ثابت للقوائم الطويلة */
fun Ui.sheet(act: Activity, title: String, views: List<View>, fixedH: Boolean = false, onClose: () -> Unit = {}, frac: Float = 0f): Dialog {
    val d = GDialog(act)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    views.forEach { body.addView(it) }
    val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, 0, 0, dp(8)) }
    head.addView(text(title, 17f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(IconTextView(ctx).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { d.dismiss() } })
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(14), dp(12), dp(14), dp(14)); background = box(th.card, th.border, 18)
    }
    root.addView(head, LinearLayout.LayoutParams(-1, -2))
    val maxH = (ctx.resources.displayMetrics.heightPixels * 0.78f).toInt()
    val holder: View
    if (fixedH) {
        holder = body
        root.addView(body, LinearLayout.LayoutParams(-1, maxH))
    } else {
        val sv = MaxHeightScroll(ctx, maxH).apply { addView(body) }
        holder = sv
        root.addView(sv, LinearLayout.LayoutParams(-1, -2))
    }
    d.setContentView(root)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    // الأفقي: النافذة بتتفتح كصفحة كاملة؛ الرأسي: bottom sheet زي الأول (بيتحدد وقت الفتح مش وقت الإنشاء)
    d.setOnShowListener {
        val land = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = d.window
        if (frac > 0f) {   // (v97) نافذة بنسبة من ارتفاع الشاشة (مثلًا 45%) بدل الصفحة الكاملة
            root.background = box(th.card, th.border, 18)
            w?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            if (fixedH) holder.layoutParams = LinearLayout.LayoutParams(-1, (ctx.resources.displayMetrics.heightPixels * frac - dp(64)).toInt().coerceAtLeast(dp(120)))
        } else if (land) {
            root.background = box(th.card, th.card, 0)
            w?.setBackgroundDrawable(ColorDrawable(th.bg))
            w?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (fixedH) holder.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        } else {
            root.background = box(th.card, th.border, 18)
            w?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            if (fixedH) holder.layoutParams = LinearLayout.LayoutParams(-1, (ctx.resources.displayMetrics.heightPixels * 0.78f).toInt())
        }
    }
    d.setOnDismissListener { onClose() }
    return d
}

/** الأفقي: أي نافذة (Dialog) بتتفتح كصفحة كاملة بدل نافذة صغيرة. بتتنده قبل d.show() */
fun Ui.fullPage(d: Dialog) {
    if (ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) return
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(th.bg))
        setGravity(Gravity.FILL)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
}


/** .fs-subsize-btn: مربع 32dp داكن شفاف بحدود خفيفة (radius 10، 12sp bold) */
fun Ui.fsBtn(t: String, f: (TextView) -> Unit): TextView = IconTextView(ctx).apply {
    text = t; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setSingleLine()
    typeface = android.graphics.Typeface.DEFAULT_BOLD
    minimumWidth = dp(40); setPadding(dp(10), 0, dp(10), 0)
    background = glassBtnBg(ctx.resources.displayMetrics.density, 14)   // (v445) زجاج بدل المستطيل الغامق
    layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    setOnClickListener { f(this) }
    Glass.pressable(this)   // (v445) ضغطة بتصغّر الزرار وترجّعه بارتداد
}

/** (v445) خلفية زجاجية للأزرار والقوايم الصغيرة: تدرّج شفاف خفيف + حد فاتح رفيع */
fun glassBtnBg(d: Float, rDp: Int): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xA62C2C38.toInt(), 0xB3121219.toInt())).apply {
    cornerRadius = rDp * d; setStroke(maxOf(1, d.toInt()), 0x2EFFFFFF)
}

/** .fullscreen-btn: دايرة 34dp داكنة (أسفل يسار الفيديو) */
/** (v161) خلفية زجاجية للأزرار الدايرية: تدرّج شفاف من فوق لتحت + حد أبيض خفيف */
fun glassCircleBg(d: Float): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x9E383846.toInt(), 0xB30E0E15.toInt())).apply {
    shape = GradientDrawable.OVAL; setStroke(maxOf(1, d.toInt()), 0x4DFFFFFF)
}

/** (v161) أيقونة بتظهر بأنيميشن (تكبير + فيد) وتختفي فورًا — لزرار التشغيل الأوسط */
class FadePop(ctx: Context) : IconTextView(ctx) {
    override fun setVisibility(visibility: Int) {
        val was = getVisibility()
        super.setVisibility(visibility)
        if (visibility == View.VISIBLE && was != View.VISIBLE) {
            alpha = 0f; scaleX = 0.6f; scaleY = 0.6f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(android.view.animation.OvershootInterpolator(1.7f)).start()
        } else if (visibility != View.VISIBLE) { animate().cancel(); alpha = 1f; scaleX = 1f; scaleY = 1f }
    }
}

fun Ui.fsCircle(t: String, f: () -> Unit): TextView = IconTextView(ctx).apply {
    text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); includeFontPadding = false
    background = glassCircleBg(ctx.resources.displayMetrics.density)
    setOnClickListener { f() }
    Glass.pressable(this)
}

/** شريط التقدم + الوقت + سابق/تالي + الكبسولة (.controls): [تشغيل] [سرعة] [🔊 سلايدر] [CC] [📤] [▯] */
fun Ui.controls(): Ctl {
    val prog = DualProgress(ctx, th).apply { mini = true }
    fun mono(t: String) = text(t, 11f, th.muted).apply { typeface = android.graphics.Typeface.MONOSPACE }
    val tEl = mono("0:00"); val tDur = mono("0:00")
    fun small(t: String) = IconTextView(ctx).apply { text = t; textSize = 11f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(dp(8), dp(2), dp(8), dp(2)) }
    val prev = small("⏮"); val next = small("⏭")
    val timeRow = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
    timeRow.addView(tEl)
    timeRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
    timeRow.addView(prev); timeRow.addView(next)
    timeRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
    timeRow.addView(tDur)

    fun ic(t: String) = IconTextView(ctx).apply {
        text = t; textSize = 16f; setTextColor(th.text); gravity = Gravity.CENTER; includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(38)).apply { setMargins(dp(2), 0, dp(2), 0) }
    }
    val play = IconTextView(ctx).apply {
        text = "▶"; textSize = 19f; gravity = Gravity.CENTER; setTextColor(onPrimary()); includeFontPadding = false
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(th.primary, th.accent)).apply { shape = GradientDrawable.OVAL }
        elevation = dp(4).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(2), 0, dp(4), 0) }
    }
    val speed = ic("⏩")
    val vol = DualVolBar(ctx, th.accent, th.border).apply {
        max = 200; progress = 100; layoutDirection = View.LAYOUT_DIRECTION_LTR   // (v167) 0..100 عادي، 100..200 تضخيم أحمر
        setPadding(dp(8), 0, dp(8), 0)
    }
    val cc = ic("CC").apply { textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
    val exp = ic("📤"); val rot = ic("▯")
    val pill = LinearLayout(ctx).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(th.card, th.surface)).apply { cornerRadius = dp(40).toFloat(); setStroke(dp(1), th.border) }
        elevation = dp(2).toFloat()
    }
    pill.addView(play); pill.addView(speed); pill.addView(ic("🔊"))
    pill.addView(vol, LinearLayout.LayoutParams(0, -2, 1f))
    pill.addView(cc); pill.addView(exp); pill.addView(rot)

    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(16), 0, dp(16), 0) }
    root.addView(prog, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    root.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    root.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    return Ctl(root, prog, tEl, tDur, prev, next, play, speed, vol, cc, exp, rot)
}

/** .mem-monitor: شريط عرضه كامل بخلفية card وحد سفلي؛ رام يمين وكاش شمال */
fun Ui.memRow(): MemRow {
    fun bar() = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; isIndeterminate = false; progress = 0
        progressTintList = ColorStateList.valueOf(th.success); progressBackgroundTintList = ColorStateList.valueOf(th.surface)
    }
    val ramTv = text("🧠 رام: —", 10f, th.muted); val cacheTv = text("💾 كاش الفيديو: —", 10f, th.muted)
    val ramBar = bar(); val cacheBar = bar()
    fun item(tv: TextView, b: ProgressBar) = LinearLayout(ctx).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
        addView(tv); addView(b, LinearLayout.LayoutParams(dp(46), dp(5)).apply { marginStart = dp(4) })
    }
    val row = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(5), dp(12), dp(5)) }
    row.addView(item(ramTv, ramBar), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
    row.addView(item(cacheTv, cacheBar), LinearLayout.LayoutParams(-2, -2))
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.card) }
    root.addView(row, LinearLayout.LayoutParams(-1, -2))
    root.addView(View(ctx).apply { setBackgroundColor(th.border) }, LinearLayout.LayoutParams(-1, 1))
    return MemRow(root, ramTv, ramBar, cacheTv, cacheBar)
}

/** مؤشر رأسي للصوت/السطوع (أيقونة + شريط + نسبة) زي fs-gesture-indicator */
class VertInd(ctx: Context, icon: String, private val range: Float = 1f) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val trackH = (90 * d).toInt()
    private val fill = View(ctx)
    private val fillRed = View(ctx)
    private val lb = IconTextView(ctx)
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
        setPadding((10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt())
        background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = 14 * d }
        addView(IconTextView(ctx).apply { text = icon; textSize = 18f; gravity = Gravity.CENTER })
        val track = android.widget.FrameLayout(ctx).apply { background = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = 4 * d } }
        fill.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 4 * d }
        track.addView(fill, android.widget.FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
        fillRed.background = GradientDrawable().apply { setColor(0xFFFF3B30.toInt()); cornerRadius = 4 * d }
        track.addView(fillRed, android.widget.FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
        addView(track, LayoutParams((5 * d).toInt(), trackH).apply { setMargins(0, (6 * d).toInt(), 0, (6 * d).toInt()) })
        lb.textSize = 11f; lb.setTextColor(Color.WHITE); lb.typeface = android.graphics.Typeface.DEFAULT_BOLD; addView(lb)
    }
    fun set(f: Float) {
        // (v167) range=1: عادي (السطوع)؛ range=2 (الصوت): 0..1 أبيض، 1..2 تضخيم أحمر — الشريط كله = 200%
        val v = f.coerceIn(0f, range)
        val white = minOf(v, 1f); val red = (v - 1f).coerceAtLeast(0f)
        val lp = fill.layoutParams; lp.height = (trackH * white / range).toInt(); fill.layoutParams = lp
        val lr = fillRed.layoutParams as android.widget.FrameLayout.LayoutParams
        lr.height = (trackH * red / range).toInt(); lr.bottomMargin = (trackH / range).toInt(); fillRed.layoutParams = lr
        lb.setTextColor(if (red > 0f) 0xFFFF5252.toInt() else Color.WHITE)
        lb.text = Math.round(v * 100).toString() + "%"; visibility = View.VISIBLE
    }
}


/** (v167) سلايدر صوت بقسمين: 0..100 بلون الثيم، 100..200 أحمر (تضخيم). علامة صغيرة عند 100%. */
class DualVolBar(ctx: Context, private val normal: Int, private val trackBg: Int) : SeekBar(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val red = 0xFFFF3B30.toInt()
    // بنرسم كل حاجة (المسار + الإبهام) بنفسنا؛ الـ super بيتحسب منه اللمس بس
    override fun onDraw(c: android.graphics.Canvas) {
        val l = paddingLeft.toFloat(); val r = (width - paddingRight).toFloat(); val cy = height / 2f; val th = 4 * d
        val w = r - l; val mx = max.coerceAtLeast(1).toFloat(); val x = l + w * progress / mx; val mid = l + w * 100f / mx
        pt.style = android.graphics.Paint.Style.FILL
        pt.color = trackBg; c.drawRoundRect(l, cy - th / 2, r, cy + th / 2, th, th, pt)
        pt.color = normal; c.drawRoundRect(l, cy - th / 2, minOf(x, mid), cy + th / 2, th, th, pt)
        if (progress > 100) { pt.color = red; c.drawRoundRect(mid, cy - th / 2, x, cy + th / 2, th, th, pt) }
        pt.color = 0xCCFFFFFF.toInt(); c.drawRect(mid - 0.5f * d, cy - th, mid + 0.5f * d, cy + th, pt)   // علامة 100%
        pt.color = if (progress > 100) red else Color.WHITE; c.drawCircle(x, cy, 8 * d, pt)
    }
}
