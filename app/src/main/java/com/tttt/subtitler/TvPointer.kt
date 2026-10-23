package com.tttt.subtitler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * (v480 م6) مؤشر شاشة للمتصفح على التلفزيون — شغّال بس لما المتصفح يكون في وضع التلفزيون (Browser.kt بيتحقق).
 * TvCursor = الرسمة (سهم بحدّ بلون السمة). TvPointer = الحركة والدوسة والسكرول:
 *  - الأسهم بتحرّك المؤشر، والسرعة بتزيد مع الضغط المستمر.
 *  - OK = دوسة حقيقية (لمسة بتتبعت لمنطقة الصفحة كلها، فبتشتغل على الصفحة والأزرار العايمة).
 *  - لو المؤشر عند حافة فوق/تحت/جنب والاتجاه ده → سكرول بدل ما يخرج.
 */
class TvCursor(ctx: Context, accent: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var cx = 0f
    var cy = 0f
    private var placed = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = accent; strokeJoin = Paint.Join.ROUND }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x66000000 }
    private val arrow = Path()
    private val idle = Runnable { try { animate().alpha(0.4f).setDuration(350L).start() } catch (_: Throwable) {} }

    init {
        isFocusable = false; isClickable = false; isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        arrow.moveTo(0f, 0f); arrow.lineTo(0f, 19f * d); arrow.lineTo(4.8f * d, 14.6f * d); arrow.lineTo(8.2f * d, 22f * d)
        arrow.lineTo(11f * d, 20.8f * d); arrow.lineTo(7.6f * d, 13.6f * d); arrow.lineTo(13.6f * d, 13.6f * d); arrow.close()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!placed && w > 0 && h > 0) { cx = w / 2f; cy = h / 2f; placed = true }
        cx = cx.coerceIn(0f, (w - 1).coerceAtLeast(0).toFloat()); cy = cy.coerceIn(0f, (h - 1).coerceAtLeast(0).toFloat())
    }

    override fun onDraw(c: Canvas) {
        val s = c.save()
        c.translate(cx, cy)
        c.save(); c.translate(1.6f * d, 2.2f * d); c.drawPath(arrow, shade); c.restore()
        c.drawPath(arrow, fill); c.drawPath(arrow, edge)
        c.restoreToCount(s)
    }

    fun moveBy(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cy = (cy + dy).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate(); poke()
    }

    /** المؤشر يبان بالكامل، وبعد 3 ثواني من غير حركة يبهت */
    fun poke() {
        removeCallbacks(idle); animate().cancel(); alpha = 1f
        postDelayed(idle, 3000L)
    }

    fun pulse() {
        try { animate().cancel(); scaleX = 1f; scaleY = 1f
            animate().scaleX(0.8f).scaleY(0.8f).setDuration(Skins.dur(70L)).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(Skins.dur(90L)).start() }.start()
        } catch (_: Throwable) {}
        poke()
    }
}

class TvPointer(ctx: Context, private val host: ViewGroup, accent: Int) {
    val view = TvCursor(ctx, accent)
    private val d = ctx.resources.displayMetrics.density
    /** المؤشر ضغط «فوق» على حافة الصفحة وهي مش بتتسكرول (ضغطتين ورا بعض) */
    var onTopEdge: (() -> Unit)? = null
    /** نفس الكلام لتحت؛ يرجّع true لو فيه حاجة تحت خدت التركيز (زرار «لقيت فيديو» مثلًا) */
    var onBottomEdge: (() -> Boolean)? = null
    /** كل دوسة بتتبعت (المتصفح بيحدّث lastTouchAt منها عشان حارس البوب-أب) */
    var onTap: (() -> Unit)? = null
    private var lastTopHit = 0L
    private var lastBotHit = 0L

    var enabled: Boolean = true
        set(v) { field = v; view.visibility = if (v) View.VISIBLE else View.GONE; if (v) view.poke() }

    private fun step(rep: Int): Float = d * (6f + minOf(rep, 36) * 1.2f)

    /** حركة بالأسهم. يرجّع true لو الزرار اتستهلك */
    fun move(keyCode: Int, rep: Int, wv: WebView?): Boolean {
        val s = step(rep)
        val edge = 26f * d
        val w = view.width.toFloat(); val h = view.height.toFloat()
        view.poke()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (view.cy <= edge) {
                    if (scrollMain(wv, 0, -1, s * 2.4f)) return true
                    val now = SystemClock.uptimeMillis()
                    if (rep == 0 && now - lastTopHit < 700L) { lastTopHit = 0L; onTopEdge?.invoke(); return true }
                    if (rep == 0) lastTopHit = now
                    wheel(0, -1)
                } else view.moveBy(0f, -s)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (view.cy >= h - edge) {
                    if (scrollMain(wv, 0, 1, s * 2.4f)) return true
                    val now = SystemClock.uptimeMillis()
                    if (rep == 0 && now - lastBotHit < 700L) { lastBotHit = 0L; if (onBottomEdge?.invoke() == true) return true }
                    if (rep == 0) lastBotHit = now
                    wheel(0, 1)
                } else view.moveBy(0f, s)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (view.cx <= edge) { if (!scrollMain(wv, -1, 0, s * 2.4f)) wheel(-1, 0) } else view.moveBy(-s, 0f)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (view.cx >= w - edge) { if (!scrollMain(wv, 1, 0, s * 2.4f)) wheel(1, 0) } else view.moveBy(s, 0f)
            }
            else -> return false
        }
        return true
    }

    /** سكرول الصفحة الأساسية. false = مش بتتسكرول في الاتجاه ده */
    private fun scrollMain(wv: WebView?, sx: Int, sy: Int, amount: Float): Boolean {
        wv ?: return false
        val px = amount.toInt().coerceAtLeast(1)
        return try {
            if (sy != 0 && wv.canScrollVertically(sy)) { wv.scrollBy(0, sy * px); true }
            else if (sx != 0 && wv.canScrollHorizontally(sx)) { wv.scrollBy(sx * px, 0); true }
            else false
        } catch (_: Throwable) { false }
    }

    /** عجلة ماوس وهمية عند المؤشر — بتسكرول القوايم الداخلية (div بيتسكرول) اللي scrollBy مبيلمسهاش */
    private fun wheel(sx: Int, sy: Int): Boolean = try {
        val t = SystemClock.uptimeMillis()
        val pp = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val pc = arrayOf(MotionEvent.PointerCoords().apply {
            x = view.cx; y = view.cy
            setAxisValue(MotionEvent.AXIS_VSCROLL, -sy * 1.5f); setAxisValue(MotionEvent.AXIS_HSCROLL, sx * 1.5f)
        })
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        val r = host.dispatchGenericMotionEvent(ev); ev.recycle(); r
    } catch (_: Throwable) { false }

    /** صفحة كاملة لفوق/تحت (زراير الصفحة/القناة) */
    fun page(wv: WebView?, down: Boolean) { try { if (down) wv?.pageDown(false) else wv?.pageUp(false) } catch (_: Throwable) {} }

    /** دوسة عند المؤشر: DOWN دلوقتي وUP بعد 60ms (عشان صفحات كتير بتتجاهل دوسة لحظية) */
    fun click() {
        view.pulse()
        val x = view.cx; val y = view.cy
        val t = SystemClock.uptimeMillis()
        try { onTap?.invoke() } catch (_: Throwable) {}
        try {
            val dn = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            dn.source = InputDevice.SOURCE_TOUCHSCREEN
            host.dispatchTouchEvent(dn); dn.recycle()
        } catch (_: Throwable) { return }
        view.postDelayed({
            try {
                val t2 = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, x, y, 0)
                up.source = InputDevice.SOURCE_TOUCHSCREEN
                try { onTap?.invoke() } catch (_: Throwable) {}
                host.dispatchTouchEvent(up); up.recycle()
            } catch (_: Throwable) {}
        }, 60L)
    }

    companion object {
        private const val FIND_VIDEO = "var vs=[].slice.call(document.querySelectorAll('video'));var v=vs.filter(function(x){return !x.paused})[0]||vs.sort(function(a,b){return b.videoWidth*b.videoHeight-a.videoWidth*a.videoHeight})[0];if(!v)return;"

        /** تشغيل/إيقاف أول فيديو شغّال (أو الأكبر) في الصفحة */
        fun jsToggle(): String = "(function(){" + FIND_VIDEO + "if(v.paused){v.play()}else{v.pause()}})()"

        /** تقديم/تأخير بالثواني */
        fun jsSeek(sec: Int): String = "(function(){" + FIND_VIDEO + "v.currentTime=Math.max(0,Math.min(v.duration||1e9,v.currentTime+(" + sec + ")))})()"
    }
}
