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
import android.widget.TextView
import com.bumptech.glide.Glide
import java.util.Locale

/**
 * (v381) لوحة «التالي» جوه مشغّل يوتيوب:
 *  - مقفولة = شريط رفيع جدًا (~3% من مساحة المشغّل) فيه مقبض صغير بس — مفيش فيديوهات ظاهرة.
 *  - سحب لفوق (أو دوسة على المقبض) = تتفتح بنسبة 40% من مساحة المشغّل: صف أفقي من كروت الفيديوهات كاملة (صورة + عنوان + قناة + مدة) تسحبها يمين/شمال.
 *  - سحب لتحت / دوسة على المقبض / زرار الرجوع = ترجع الشريط الرفيع. مفيش قايمة طولية خالص.
 *  - دوسة على أي فيديو = يشتغل. لو الفيديو جاي من قايمة تشغيل → فيديوهات القايمة، وإلا المقترحات (YtNext).
 */
class NextPanel(
    private val act: Activity, private val ui: Ui, private val th: Theme, private val host: FrameLayout,
    private val curId: String, private val onPick: (NpItem) -> Unit,
    private val lift: (Int) -> Unit,            // يرفع شريط التحكم عشان اللوحة تتحط تحته
    private val hold: (Boolean) -> Unit,        // true = ماتخفّيش الشريط (اللوحة مفتوحة)
    @Suppress("UNUSED_PARAMETER") progBottom: () -> Int = { 0 }
) {
    private enum class S { HIDDEN, PEEK, OPEN }
    private var st = S.HIDDEN
    private var items: List<NpItem> = emptyList()
    private var loading = false
    private var loaded = false
    private var anim: ValueAnimator? = null

    /** بيلقط السحب الرأسي (لفوق = فتح، لتحت = قفل) ويسيب السحب الأفقي للصف */
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
        override fun onTouchEvent(e: MotionEvent): Boolean {
            // السحب الرأسي اللي بدأ على المقبض نفسه (من غير ما يعدّي على الصف) برضه يفتح/يقفل
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; fired = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - y0; val dx = e.x - x0
                    if (!fired && Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx) * 1.2f) { fired = true; if (dy < 0) onUp() else onDown() }
                }
            }
            return true
        }
    }

    private val handleTv = ui.text("⏭ التالي  ▾", 11.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4)) }
    private val stripScroll = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(strip) }
    private val root = VSwipe(act, { expand() }, { collapse() }).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE; isClickable = true
        background = GradientDrawable().apply { setColor(0xF2101014.toInt()); cornerRadii = floatArrayOf(ui.dp(12).toFloat(), ui.dp(12).toFloat(), ui.dp(12).toFloat(), ui.dp(12).toFloat(), 0f, 0f, 0f, 0f); setStroke(ui.dp(1), 0x29FFFFFF) }
    }
    private val handleRow = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setOnClickListener { if (st == S.PEEK) expand() else if (st == S.OPEN) collapse() }
    }

    init {
        val grab = View(act).apply { background = GradientDrawable().apply { setColor(0xAAFFFFFF.toInt()); cornerRadius = ui.dp(2).toFloat() } }
        handleRow.addView(grab, LinearLayout.LayoutParams(ui.dp(40), ui.dp(4)))
        handleRow.addView(handleTv, LinearLayout.LayoutParams(-1, ui.dp(18)))
        root.addView(handleRow, LinearLayout.LayoutParams(-1, peekH()))
        root.addView(stripScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        host.addView(root, FrameLayout.LayoutParams(-1, peekH(), Gravity.BOTTOM))
    }

    /** مقفولة: ~3% من ارتفاع المشغّل (بحد أدنى صغير عشان المقبض يتلمس) */
    private fun peekH(): Int = maxOf(ui.dp(16), (host.height * 0.03f).toInt())
    /** مفتوحة: 40% من ارتفاع المشغّل */
    private fun openH(): Int = maxOf(ui.dp(120), (host.height * 0.40f).toInt())
    private fun setH(h: Int) { val lp = root.layoutParams; lp.height = h; root.layoutParams = lp }
    private fun handleH(h: Int) { val lp = handleRow.layoutParams; lp.height = h; handleRow.layoutParams = lp }
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
                loaded = true; items = r.first.take(60)
                if (chromeOn && st == S.HIDDEN) show()
                if (wantOpen) { wantOpen = false; if (st == S.HIDDEN) show(); expand() }   // (v531) الفيديو خلص: افتح اللوحة أول ما تتحمّل
            }
        }.apply { isDaemon = true }.start()
    }

    /** كروت كاملة: صورة 16:9 (ومدة) + عنوان سطرين + القناة — بحجم يملا الـ40% */
    private fun buildStrip() {
        strip.removeAllViews()
        val cardH = openH() - ui.dp(22) - ui.dp(6)
        val ph = (cardH - ui.dp(54)).coerceAtLeast(ui.dp(48))
        val pw = minOf((ph * 16f / 9f).toInt(), (host.width * 0.62f).toInt().coerceAtLeast(ui.dp(120)))
        for (v in items.filter { it.id != curId }.take(40)) {
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setOnClickListener { onPick(v) }
                if (DeviceUi.isTv(act)) {   // (v531) الريموت: الكارت يتفوكس ويتظلّل
                    isFocusable = true; isClickable = true
                    setOnFocusChangeListener { vw, f -> vw.setBackgroundColor(if (f) 0x55FFFFFF else 0) }
                }
            }
            val imgBox = FrameLayout(act)
            imgBox.addView(ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK)
                if (v.thumb.isNotBlank()) try { Glide.with(act).load(v.thumb).into(this) } catch (_: Throwable) {}
            }, FrameLayout.LayoutParams(pw, ph))
            fmtDur(v.dur).takeIf { it.isNotEmpty() }?.let { d ->
                imgBox.addView(TextView(act).apply {
                    text = d; textSize = 10f; setTextColor(Color.WHITE); setPadding(ui.dp(4), ui.dp(1), ui.dp(4), ui.dp(1))
                    background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() }
                }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(4), ui.dp(4)) })
            }
            card.addView(imgBox, LinearLayout.LayoutParams(pw, ph))
            card.addView(TextView(act).apply {
                text = v.title; textSize = 11.5f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutDirection = View.LAYOUT_DIRECTION_RTL; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPadding(ui.dp(2), ui.dp(3), ui.dp(2), 0)
            }, LinearLayout.LayoutParams(pw, -2))
            if (v.channel.isNotBlank()) card.addView(TextView(act).apply {
                text = v.channel; textSize = 10f; setTextColor(0xFFB0B0B8.toInt()); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
                layoutDirection = View.LAYOUT_DIRECTION_RTL; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPadding(ui.dp(2), 0, ui.dp(2), 0)
            }, LinearLayout.LayoutParams(pw, -2))
            strip.addView(card, LinearLayout.LayoutParams(pw, -2).apply { setMargins(ui.dp(5), ui.dp(2), ui.dp(5), 0) })
        }
    }

    private var chromeOn = false
    private var wantOpen = false

    /** (v531) لما الفيديو يخلص: اظهر لوحة الفيديوهات القريبة مفتوحة (وحمّلها لو لسه) من غير ما تنتظر لمسة */
    fun reveal() {
        chromeOn = true
        if (!loaded) { wantOpen = true; load(); return }
        if (st == S.HIDDEN) show()
        if (st == S.PEEK) expand()
    }

    /** بيتنادى من applyChromeFn: الشريط ظهر/اختفى */
    fun onChrome(on: Boolean) {
        chromeOn = on
        if (on) { if (st == S.HIDDEN) { if (loaded) show() else load() } }
        else if (st != S.HIDDEN) hide()
    }

    private fun show() {
        if (items.none { it.id != curId }) return
        anim?.cancel(); st = S.PEEK
        stripScroll.visibility = View.GONE; handleTv.visibility = View.GONE; handleH(peekH())
        setH(peekH()); root.visibility = View.VISIBLE
        root.alpha = 0f; root.animate().alpha(1f).setDuration(200).start()
        lift(peekH())
    }

    private fun hide() {
        anim?.cancel(); val was = st; st = S.HIDDEN
        root.animate().cancel(); root.visibility = View.GONE
        if (was == S.OPEN) { stripScroll.visibility = View.GONE; handleTv.visibility = View.GONE; handleH(peekH()) }
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
        st = S.OPEN; hold(true)
        buildStrip()
        handleTv.visibility = View.VISIBLE; handleH(ui.dp(22)); stripScroll.scrollTo(0, 0); stripScroll.visibility = View.VISIBLE
        lift(openH())
        animH(openH())
        if (DeviceUi.isTv(act)) strip.post { try { strip.getChildAt(0)?.requestFocus() } catch (_: Throwable) {} }   // (v531) تلفزيون: الفوكس على أول كارت
    }

    fun collapse() {
        if (st != S.OPEN) return
        st = S.PEEK
        lift(peekH())
        animH(peekH()) { if (st == S.PEEK) { stripScroll.visibility = View.GONE; handleTv.visibility = View.GONE; handleH(peekH()) } }
        hold(false)
    }

    /** زرار الرجوع: لو اللوحة مفتوحة تتقفل بدل ما المشغّل يتقفل */
    fun consumeBack(): Boolean { if (st == S.OPEN) { collapse(); return true }; return false }
}
