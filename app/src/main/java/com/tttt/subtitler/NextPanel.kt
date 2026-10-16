package com.tttt.subtitler

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bumptech.glide.Glide
import java.util.Locale

/**
 * (v378) لوحة «التالي» جوه مشغّل يوتيوب (بدل زرار 📃 + الشيت):
 *  - بتظهر مع أزرار المشغّل تحت صف ⏮ ⏯ ⏭ بنسبة ~20% من الفيديو: صف أفقي من الفيديوهات اللي جاية.
 *  - سحب لفوق (أو دوسة على المقبض) = تتفتح 100% (قايمة طولية) لحد شريط التقدم؛ سحب لتحت/دوسة على المقبض/رجوع = ترجع صغيرة.
 *  - دوسة على أي فيديو = يشتغل. لو الفيديو جاي من قايمة تشغيل → فيديوهات القايمة، وإلا المقترحات.
 */
class NextPanel(
    private val act: Activity, private val ui: Ui, private val th: Theme, private val host: FrameLayout,
    private val curId: String, private val onPick: (NpItem) -> Unit,
    private val lift: (Int) -> Unit,            // يرفع شريط التحكم عشان اللوحة الصغيرة تتحط تحته
    private val hold: (Boolean) -> Unit,        // true = ماتخفّيش الشريط (اللوحة مفتوحة)
    private val progBottom: () -> Int           // مكان آخر شريط التقدم (من فوق الفيديو) — اللوحة الكبيرة بتوصل لحد هنا
) {
    private enum class S { HIDDEN, PEEK, FULL }
    private var st = S.HIDDEN
    private var items: List<NpItem> = emptyList()
    private var title = ""
    private var loading = false
    private var loaded = false
    private var anim: ValueAnimator? = null

    /** مقبض + صف أفقي: بيلقط السحب الرأسي (لفوق = فتح، لتحت = قفل) ويسيب السحب الأفقي للقايمة */
    private class VSwipe(c: Context, val onUp: () -> Unit, val onDown: () -> Unit) : LinearLayout(c) {
        private var x0 = 0f; private var y0 = 0f; private var fired = false
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; fired = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - y0; val dx = e.x - x0
                    if (!fired && Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx) * 1.2f) { fired = true; if (dy < 0) onUp() else onDown(); return true }
                }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean = true
    }

    private val handleTv = ui.text("⏭ التالي  ▴", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
    private val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
    private val stripScroll = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
    private val fullCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(8)) }
    private val fullScroll = ScrollView(act).apply { visibility = View.GONE; addView(fullCol) }
    private val top = VSwipe(act, { expand() }, { collapse() }).apply { orientation = LinearLayout.VERTICAL }
    private val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE; isClickable = true
        background = GradientDrawable().apply { setColor(0xF2101014.toInt()); cornerRadii = floatArrayOf(ui.dp(16).toFloat(), ui.dp(16).toFloat(), ui.dp(16).toFloat(), ui.dp(16).toFloat(), 0f, 0f, 0f, 0f); setStroke(ui.dp(1), 0x29FFFFFF) }
    }

    init {
        val grab = View(act).apply { background = GradientDrawable().apply { setColor(0x88FFFFFF.toInt()); cornerRadius = ui.dp(2).toFloat() } }
        val handleRow = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ui.dp(5), 0, ui.dp(2)); setOnClickListener { if (st == S.PEEK) expand() else if (st == S.FULL) collapse() } }
        handleRow.addView(grab, LinearLayout.LayoutParams(ui.dp(40), ui.dp(4)))
        handleRow.addView(handleTv, LinearLayout.LayoutParams(-1, ui.dp(22)))
        top.addView(handleRow, LinearLayout.LayoutParams(-1, -2))
        top.addView(stripScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(top, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fullScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        host.addView(root, FrameLayout.LayoutParams(-1, peekH(), Gravity.BOTTOM))
    }

    /** صغيرة: الصف الأفقي ياخد المساحة كلها · كبيرة: المقبض بس فوق والقايمة الطولية تحته */
    private fun topMode(peek: Boolean) {
        val lp = top.layoutParams as LinearLayout.LayoutParams
        lp.height = if (peek) 0 else -2; lp.weight = if (peek) 1f else 0f; top.layoutParams = lp
    }

    private fun peekH(): Int = maxOf(ui.dp(104), (host.height * 0.2f).toInt() + ui.dp(26))
    private fun fullH(): Int = (host.height - progBottom() - ui.dp(6)).coerceAtLeast(peekH() + ui.dp(60))
    private fun setH(h: Int) { val lp = root.layoutParams; lp.height = h; root.layoutParams = lp }
    private fun fmtDur(s: Long): String = if (s <= 0) "" else if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)

    private fun load() {
        if (loading || loaded) return
        loading = true
        Thread {
            val r = try { YtNext.fetch(curId) } catch (_: Throwable) { null }
            act.runOnUiThread {
                loading = false
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (r == null || r.first.isEmpty()) return@runOnUiThread   // هيتجرّب تاني في الظهور الجاي
                loaded = true; items = r.first.take(60); title = r.second
                build()
                if (chromeOn && st == S.HIDDEN) show()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun thumb(v: NpItem, w: Int, h: Int): ImageView = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(w, h)
        if (v.thumb.isNotBlank()) try { Glide.with(act).load(v.thumb).into(this) } catch (_: Throwable) {}
    }

    private fun build() {
        strip.removeAllViews(); fullCol.removeAllViews()
        val ph = peekH() - ui.dp(36)                       // ارتفاع كارت الصف الصغير
        val pw = (ph * 16f / 9f).toInt()
        for (v in items.filter { it.id != curId }.take(30)) {
            val card = FrameLayout(act).apply { setOnClickListener { onPick(v) } }
            card.addView(thumb(v, pw, ph))
            card.addView(TextView(act).apply {
                text = v.title; textSize = 10.5f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(5), ui.dp(10), ui.dp(5), ui.dp(3)); setShadowLayer(4f, 0f, 1f, Color.BLACK)
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xCC000000.toInt()))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            strip.addView(card, LinearLayout.LayoutParams(pw, ph).apply { setMargins(ui.dp(4), ui.dp(2), ui.dp(4), 0) })
        }
        fullCol.addView(ui.text(title, 12.5f, th.muted, true).apply { setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(6)) })
        for (v in items) {
            val on = v.id == curId
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                background = ui.box(if (on) 0x445B9DFF else 0x14808080, if (on) 0xFF5B9DFF.toInt() else th.border, 12)
                setOnClickListener { if (!on) onPick(v) }
            }
            row.addView(thumb(v, ui.dp(112), ui.dp(63)))
            val tc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, 0, 0) }
            tc.addView(ui.text((if (on) "▶ " else "") + v.title, 13.5f, Color.WHITE, on).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            tc.addView(ui.text(listOf(v.channel, fmtDur(v.dur)).filter { it.isNotBlank() }.joinToString(" · "), 11.5f, 0xFFB0B0B8.toInt()).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
            fullCol.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
        }
    }

    private var chromeOn = false

    /** بيتنادى من applyChromeFn: الشريط ظهر/اختفى */
    fun onChrome(on: Boolean) {
        chromeOn = on
        if (on) { if (st == S.HIDDEN) { if (loaded) show() else load() } }
        else if (st != S.HIDDEN) hide()
    }

    private fun show() {
        if (items.none { it.id != curId }) return
        anim?.cancel(); st = S.PEEK
        stripScroll.visibility = View.VISIBLE; fullScroll.visibility = View.GONE; handleTv.text = "⏭ التالي  ▴"; topMode(true)
        setH(peekH()); root.visibility = View.VISIBLE
        root.alpha = 0f; root.translationY = ui.dp(14).toFloat(); root.animate().alpha(1f).translationY(0f).setDuration(200).start()
        lift(peekH())
    }

    private fun hide() {
        anim?.cancel(); val was = st; st = S.HIDDEN
        root.animate().cancel(); root.visibility = View.GONE
        if (was == S.FULL) { stripScroll.visibility = View.VISIBLE; fullScroll.visibility = View.GONE; topMode(true) }
        lift(0)
    }

    private fun animH(to: Int, end: () -> Unit = {}) {
        anim?.cancel()
        val from = root.height.takeIf { it > 0 } ?: peekH()
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = 220
            addUpdateListener { setH(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { end() } })
            start()
        }
    }

    fun expand() {
        if (st != S.PEEK) return
        st = S.FULL; hold(true)
        handleTv.text = "⏭ التالي  ▾"
        stripScroll.visibility = View.GONE; fullScroll.visibility = View.VISIBLE; topMode(false)
        animH(fullH())
    }

    fun collapse() {
        if (st != S.FULL) return
        st = S.PEEK
        handleTv.text = "⏭ التالي  ▴"
        animH(peekH()) { if (st == S.PEEK) { fullScroll.visibility = View.GONE; stripScroll.visibility = View.VISIBLE; topMode(true) } }
        hold(false)
    }

    /** زرار الرجوع: لو اللوحة الكبيرة مفتوحة تتقفل بدل ما المشغّل يتقفل */
    fun consumeBack(): Boolean { if (st == S.FULL) { collapse(); return true }; return false }
}
