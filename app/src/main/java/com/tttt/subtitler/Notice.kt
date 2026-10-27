package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * إشعار صغير فوق الشاشة (بديل التوست بالكامل). مفيش Toast في أي حتة في البرنامج.
 * - show: سطر صغير بيختفي لوحده (أو بلمسة).
 * - ask: إشعار بسؤال (نعم / لا) وشريط رفيع بيعدّ تنازلي، ولو ما حدش ردّ في المهلة (5 ثواني) بيختفي.
 * بيتفتح كنافذة فوق كل حاجة (حتى فوق الدايلوجات) بدون ما ياخد الفوكس.
 */
object Notice {
    private val main = Handler(Looper.getMainLooper())
    private var removeCur: (() -> Unit)? = null
    private var timeout: Runnable? = null

    private fun activityOf(c: Context): Activity? {
        var x: Context? = c
        while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }
        return null
    }

    private fun theme(c: Context): Theme = try { Themes.byId(c.getSharedPreferences("p", 0).getString("theme", "mx")) } catch (_: Throwable) { Themes.all[0] }

    private fun statusBarH(a: Activity): Int {
        val id = a.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) a.resources.getDimensionPixelSize(id) else 0
    }

    /** (v603) خلفية كبسولة التلفزيون: لون الكارت شبه شفاف + حد رفيع + نص دايري كامل (بتتحسب على الحجم الفعلي عن طريق cornerRadius كبير) */
    private fun tvCapsuleBg(th: Theme, radiusPx: Float, strokePx: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor((th.card and 0x00FFFFFF) or 0xF2000000.toInt()); setStroke(strokePx.coerceAtLeast(1), th.border); cornerRadius = radiusPx
        }

    private fun removeNow() {
        timeout?.let { main.removeCallbacks(it) }; timeout = null
        val r = removeCur; removeCur = null
        try { r?.invoke() } catch (_: Throwable) {}
    }

    fun dismiss() { if (Looper.myLooper() == Looper.getMainLooper()) removeNow() else main.post { removeNow() } }

    /** إشعار عادي صغير */
    fun show(c: Context, msg: String, ms: Long = 2400L) {
        NoticeLog.add(msg)
        if (NoticeLog.sinkFor(c)) return   // (v217) المشغّل فاتح: الإشعار بيتسجّل في مثلث ▲ فوق اليمين بدل ما يطلع عايم
        present(c, msg, null, null, ms, null, null)
    }

    /** إشعار بسؤال. onNo بتتنادي لو دوس «لا» أو عدّت المهلة من غير رد */
    fun ask(c: Context, msg: String, yes: String, no: String, ms: Long = 5000L, onYes: () -> Unit, onNo: () -> Unit) {
        present(c, msg, yes, no, ms, onYes, onNo)
    }

    private fun present(c: Context, msg: String, yes: String?, no: String?, ms: Long, onYes: (() -> Unit)?, onNo: (() -> Unit)?) {
        val act = activityOf(c)
        if (act == null) { onNo?.let { main.post { it() } }; return }
        main.post {
            if (act.isFinishing || act.isDestroyed) { onNo?.invoke(); return@post }
            try { if (act.isInPictureInPictureMode) { LogStore.add("🔕 إشعار (PiP): $msg"); onNo?.invoke(); return@post } } catch (_: Throwable) {}
            removeNow()
            val th = theme(act); val ui = Ui(act, th)
            val d = act.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val onPrimary = if (th.isLight) Color.WHITE else Color.BLACK
            // (v603) على التلفزيون: كبسولة كبيرة مقروءة من بعيد (شكل كبسولات One UI) — الموبايل/التابلت زي ما هم بالظبط
            val tv = try { DeviceUi.isTv(act) } catch (_: Throwable) { false }
            val maxW = (act.resources.displayMetrics.widthPixels * (if (tv) 0.66f else 0.82f)).toInt()
            val msgSize = if (tv) 21f else 12.5f
            val padH = if (tv) 30 else 16
            val padV = if (tv) 15 else 8
            val radius = if (tv) 40 else 20

            // كبسولة على قد الكلام بس (زي إشعارات سامسونج): بتنزل من فوق في النص وبتطلع تاني
            val pill = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                background = if (tv) tvCapsuleBg(th, dp(radius).toFloat(), dp(2)) else ui.box(th.card, th.border, 20)
                setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
                if (tv) elevation = dp(8).toFloat()
                // (v224) الحواف كانت مربعة (ظل/خلفية مستطيلة حوالين الكبسولة): قصّ دايري بنفس نصف القطر وشيل الظل المستطيل
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, if (tv) v.height / 2f else dp(20).toFloat()) }
                }
                clipToOutline = true
            }
            pill.addView(IconTextView(act).apply {
                text = msg; textSize = msgSize; setTextColor(th.text); maxLines = if (tv) 2 else 3; ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER; maxWidth = maxW - dp(padH * 2)
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            var done = false
            lateinit var wrapRef: View
            fun finish(cb: (() -> Unit)?) {
                if (done) return; done = true
                timeout?.let { main.removeCallbacks(it) }; timeout = null
                cb?.invoke()
                // خروج بحركة لفوق ثم الإزالة (لو إشعار تاني حل مكانه قبل كده بيتشال فورًا من removeNow)
                val mine = removeCur
                try {
                    wrapRef.animate().alpha(0f).translationY(-(wrapRef.height + dp(12)).toFloat()).setDuration(170)
                        .withEndAction { if (removeCur === mine) removeNow() else try { mine?.invoke() } catch (_: Throwable) {} }.start()
                } catch (_: Throwable) { removeNow() }
            }

            if (yes != null && no != null) {
                fun chip(t: String, primary: Boolean, f: () -> Unit) = IconTextView(act).apply {
                    text = t; textSize = if (tv) 19f else 12.5f; gravity = Gravity.CENTER; minHeight = dp(if (tv) 52 else 40)
                    setPadding(dp(if (tv) 24 else 14), dp(6), dp(if (tv) 24 else 14), dp(6))
                    setTextColor(if (primary) onPrimary else th.text)
                    background = ui.box(if (primary) th.primary else th.surface, if (primary) th.primary else th.border, if (tv) 26 else 16)
                    setOnClickListener { f() }
                }
                val row = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(6)) }
                row.addView(chip(yes, true) { finish(onYes) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                row.addView(chip(no, false) { finish(onNo) }, LinearLayout.LayoutParams(-2, -2))
                pill.addView(row, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
                val bar = View(act).apply { setBackgroundColor(th.primary) }
                pill.addView(bar, LinearLayout.LayoutParams(-1, dp(2)))
                bar.post {
                    bar.pivotX = bar.width.toFloat()
                    bar.animate().scaleX(0f).setDuration(ms).setInterpolator(LinearInterpolator()).start()
                }
            } else {
                pill.setOnClickListener { finish(null) }
            }

            val wrap = FrameLayout(act).apply { setPadding(dp(8), dp(if (tv) 12 else 6), dp(8), dp(6)); addView(pill, FrameLayout.LayoutParams(-2, -2)) }
            wrapRef = wrap
            wrap.alpha = 0f; wrap.translationY = -dp(60).toFloat()

            val tok = try { act.window.decorView.windowToken } catch (_: Throwable) { null }
            var added = false
            if (tok != null) {
                try {
                    val wm = act.windowManager
                    val lp = WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; token = tok; y = statusBarH(act) + (if (tv) (act.resources.displayMetrics.heightPixels * 0.04f).toInt() else 0) }
                    wm.addView(wrap, lp)
                    removeCur = { try { wm.removeViewImmediate(wrap) } catch (_: Throwable) {} }
                    added = true
                } catch (_: Throwable) {}
            }
            if (!added) {
                try {
                    val content = act.findViewById<ViewGroup>(android.R.id.content)
                    content.addView(wrap, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                    removeCur = { try { content.removeView(wrap) } catch (_: Throwable) {} }
                } catch (_: Throwable) { onNo?.invoke(); return@post }
            }
            wrap.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            val t = Runnable { finish(onNo) }
            timeout = t; main.postDelayed(t, if (tv && yes == null) maxOf(ms, 2600L) else ms)
        }
    }
}


/**
 * (v217) سجل الإشعارات: كل Notice.show بيتسجّل هنا (الأحدث الأول). لو شاشة المشغّل فاتحة (attach) الإشعار مابيطلعش عايم،
 * وبيظهر كمثلث ▲ أحمر شفاف جنب عدّاد الجمل، ولمسة عليه بتفتح قايمة منسدلة بالإشعارات. برّه المشغّل (الإعدادات مثلًا) الإشعار العايم زي ما هو.
 */
object NoticeLog {
    class Item(val at: Long, val msg: String)
    private val items = ArrayList<Item>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var owner: Activity? = null
    @Volatile private var onChange: (() -> Unit)? = null

    fun attach(a: Activity, f: () -> Unit) { owner = a; onChange = f }
    fun detach(a: Activity) { if (owner === a) { owner = null; onChange = null } }

    fun sinkFor(c: Context): Boolean {
        val o = owner ?: return false
        var x: Context? = c
        while (x is ContextWrapper) { if (x is Activity) return x === o && !o.isFinishing; x = x.baseContext }
        return false
    }

    fun add(msg: String) {
        val now = System.currentTimeMillis()
        synchronized(items) {
            val top = items.firstOrNull()
            if (top != null && top.msg == msg && now - top.at < 3000L) return
            items.add(0, Item(now, msg))
            while (items.size > 80) items.removeAt(items.size - 1)
        }
        LogStore.add("🔔 $msg")
        val f = onChange
        if (f != null) main.post { try { f() } catch (_: Throwable) {} }
    }

    fun list(): List<Item> = synchronized(items) { ArrayList(items) }
    fun clear() { synchronized(items) { items.clear() }; val f = onChange; if (f != null) main.post { try { f() } catch (_: Throwable) {} } }
}
