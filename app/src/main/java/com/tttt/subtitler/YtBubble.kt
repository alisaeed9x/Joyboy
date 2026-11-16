package com.tttt.subtitler

import android.app.Activity
import android.graphics.Outline
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import com.decio.skipii.SkipiiHost

/**
 * (v1080 Phase 3D) «دايرة الفيديو» العايمة: لما تسيب تبويب يوتيوب لتبويب تاني (التنقّل، مش
 * الرجوع — الرجوع بيقفّل الفيديو خالص زي ما هو) وفيديو شغّال، الفيديو بيكمّل صوت في فقاعة
 * دايرة 46dp فوق كل التبويبات، بنفس نمط MiniDock: صورة الفيديو في دايرة، بتتسحب لأي مكان
 * وبتلزق في حافة الشمال أو اليمين، ومكانها بيتحفظ. دوسة عليها = بترجّع لمشغّل Skipii؛ وهي
 * بتتسحب بيظهر ✕ تحت (DragTrash المشتركة): سيبها عليه = الفيديو بيتقفل. التشغيل نفسه
 * ملك PlaybackService (الخدمة بتكمّل لوحدها لما التبويب يختفي)؛ الدايرة دي واجهة بس،
 * وبتقرا الحالة من SkipiiHost.playerState اللي SkipiiApp بتنشره وهي مركّبة.
 * النطاق داخل التطبيق بس (نمط MiniDock)، مش نافذة نظام: Skipii شغّال جوه نفس الأكتيفيتي
 * أصلًا، فمفيش حاجة تستدعي TYPE_APPLICATION_OVERLAY ولا إذن العرض فوق التطبيقات.
 * parent لازم يكون FrameLayout بيغطي الشاشة. بتتنده detach() من onDestroy.
 */
class YtBubble(
    private val act: Activity,
    private val parent: FrameLayout,
    private val ui: Ui,
    private val hideIf: () -> Boolean = { false },
    private val onReturn: () -> Unit
) {
    private val prefs = act.getSharedPreferences("p", 0)
    private val circle = ui.dp(46)
    private var artKey = ""

    private val art = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF2A2A36.toInt())
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setOval(0, 0, v.width, v.height)
            }
        }
        clipToOutline = true
    }
    private val trash = DragTrash(act, parent, ui)

    private inner class BubbleLayout : LinearLayout(act) {
        private var dx = 0f
        private var dy = 0f
        private var sx = 0f
        private var sy = 0f
        private var drag = false
        private fun down(e: MotionEvent) {
            dx = e.rawX
            dy = e.rawY
            sx = translationX
            sy = translationY
            drag = false
        }
        private fun move(e: MotionEvent) {
            if (!drag && Math.abs(e.rawX - dx) + Math.abs(e.rawY - dy) > ui.dp(10)) {
                drag = true
                this@YtBubble.parent.requestDisallowInterceptTouchEvent(true)
                trash.show()
            }
            if (drag) {
                translationX = (sx + e.rawX - dx).coerceIn(0f, (this@YtBubble.parent.width - width).coerceAtLeast(0).toFloat())
                translationY = (sy + e.rawY - dy).coerceIn(0f, (this@YtBubble.parent.height - height).coerceAtLeast(0).toFloat())
                trash.update(this)
            }
        }
        private fun up() {
            if (drag) {
                val hit = trash.near(this)
                trash.hide()
                if (hit) {
                    drag = false
                    SkipiiHost.playerState?.close()
                    refresh()
                    return
                }
                val right = translationX + width / 2f > this@YtBubble.parent.width / 2f
                val room = (this@YtBubble.parent.height - height).coerceAtLeast(1)
                prefs.edit().putBoolean("ytbubble_right", right).putFloat("ytbubble_fy", (translationY / room).coerceIn(0f, 1f)).apply()
                place(true)
            } else {
                onReturn()
            }
            drag = false
        }
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> down(e)
                MotionEvent.ACTION_MOVE -> {
                    move(e)
                    if (drag) return true
                }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down(e)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    move(e)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    up()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (drag) up()
                    return true
                }
            }
            return false
        }
    }

    private val bubble = BubbleLayout().apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        visibility = View.GONE
        elevation = ui.dp(10).toFloat()
        setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
    }

    init {
        bubble.addView(art, LinearLayout.LayoutParams(circle, circle))
        bubble.background = ui.box(0xEE1B1E27.toInt(), 0xFF8FB4FF.toInt(), 26, 2)
        parent.addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        parent.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) place(false)
        }
        parent.post { refresh() }
    }

    fun detach() {
        bubble.visibility = View.GONE
    }

    /** مكان الدايرة: لازقة في الحافة (شمال/يمين) بالارتفاع المحفوظ — من غير ما تطلع بره الشاشة */
    private fun place(animate: Boolean) {
        val w = parent.width
        val h = parent.height
        if (w <= 0 || h <= 0 || bubble.visibility != View.VISIBLE) return
        val dw = if (bubble.width > 0) bubble.width else circle + ui.dp(6)
        val dh = if (bubble.height > 0) bubble.height else circle + ui.dp(6)
        val m = ui.dp(6)
        val right = prefs.getBoolean("ytbubble_right", true)
        val fy = prefs.getFloat("ytbubble_fy", 0.45f)
        val tx = (if (right) w - dw - m else m).coerceAtLeast(0).toFloat()
        val ty = (fy * (h - dh)).coerceIn(0f, (h - dh).coerceAtLeast(0).toFloat())
        if (animate) {
            bubble.animate().translationX(tx).translationY(ty).setDuration(180L).start()
        } else {
            bubble.animate().cancel()
            bubble.translationX = tx
            bubble.translationY = ty
        }
    }

    private fun loadArt(thumb: String?) {
        if (thumb.isNullOrBlank()) {
            art.setImageResource(R.drawable.no_poster)
            return
        }
        try {
            Glide.with(act).load(thumb).centerCrop().into(art)
        } catch (_: Throwable) {
        }
    }

    /** بتظهر بس وفيديو شغّال (أو بيحمّل) وإحنا بره تبويب يوتيوب؛ غير كده بتتخبّى */
    fun refresh() {
        val st = SkipiiHost.playerState
        val item = st?.current
        val playing = st != null && (st.isPlaying || st.isBuffering)
        if (item == null || !playing || hideIf() || act.isFinishing || act.isDestroyed) {
            bubble.visibility = View.GONE
            return
        }
        val wasGone = bubble.visibility != View.VISIBLE
        bubble.visibility = View.VISIBLE
        if (artKey != item.id) {
            artKey = item.id
            loadArt(item.thumbnail)
        }
        if (wasGone) {
            bubble.alpha = 0f
            bubble.post {
                place(false)
                bubble.animate().alpha(1f).setDuration(200L).start()
            }
        } else {
            bubble.post { place(false) }
        }
    }
}
