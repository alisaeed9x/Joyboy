package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v477 م4) شريط تحكم المشغّل على التلفزيون بنمط MX/VLC: شريط تقدّم + صف أزرار بالريموت.
 * ظاهر بـ OK / فوق / تحت / Menu، بيختفي لوحده بعد 5 ثواني من آخر زرار، وBack بيقفله.
 * أي أمر بيتنفّذ عن طريق Actions (المشغّل نفسه) — الشريط مالوش منطق تشغيل خاص.
 */
class TvPlayerBar(private val act: Activity, private val ui: Ui, private val th: Theme, private val a: Actions) {
    interface Actions {
        fun pos(): Long; fun dur(): Long; fun playing(): Boolean
        fun toggle(); fun seekBy(ms: Long); fun seekTo(ms: Long)
        fun prev(); fun next()
        fun speedLabel(): String; fun cycleSpeed()
        fun fitLabel(): String; fun cycleFit()
        fun ccOn(): Boolean; fun toggleCc()
        fun subScale(d: Int); fun subShift(dMs: Long)
        fun hasAudio(): Boolean; fun audio(anchor: View)
        // (v517) الشريط المبسّط للتلفزيون
        fun slim(): Boolean
        fun trLabel(): String; fun trColor(): Int; fun translate(anchor: View)
        fun hasQuality(): Boolean; fun quality(anchor: View)
        fun subDialog(); fun more(anchor: View)
        fun subTracks(anchor: View)   // (v521) 🌐 اختيار مسار/لغة الترجمة (الأصلية اللي في الفيديو + لغة ترجمة البرنامج)
    }

    private val h = Handler(Looper.getMainLooper())
    val view = LinearLayout(act)
    var visible = false; private set
    var onVis: ((Boolean) -> Unit)? = null   // (v509) Player يظهر/يخفي العدّادات مع الشريط
    fun focusMain() { try { playB.requestFocus() } catch (_: Throwable) {} }
    private val hideRun = Runnable { hide() }
    private val tickRun = object : Runnable { override fun run() { if (visible) { refresh(); h.postDelayed(this, 400L) } } }

    private val cur = TextView(act); private val total = TextView(act)
    private val track = SeekTrack(act, th.primary)
    private lateinit var playB: TextView; private lateinit var speedB: TextView; private lateinit var fitB: TextView
    private lateinit var ccB: TextView; private lateinit var audB: TextView
    private lateinit var trB: TextView; private lateinit var qB: TextView
    private val slim: Boolean = try { a.slim() } catch (_: Throwable) { false }

    private fun btn(label: String, onClick: (View) -> Unit): TextView = IconTextView(act).apply {   // (v527) IconTextView: ⏸ ▶ ⏮ ⏭ بتترسم أيقونة (خطين متوازيين) بدل رمز الخط اللي على TCL كان بيطلع شكل «i»
        text = label; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = ui.dp(12).toFloat() }
        isFocusable = true; isClickable = true
        id = View.generateViewId(); nextFocusUpId = id; nextFocusDownId = id
        setOnClickListener { poke(); onClick(it); refresh() }
    }

    init {
        view.apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; visibility = View.GONE
            setPadding(ui.dp(28), ui.dp(40), ui.dp(28), ui.dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xEE000000.toInt(), 0x00000000))
        }
        for (t in listOf(cur, total)) t.apply { textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; text = "0:00" }
        val tr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tr.addView(cur, LinearLayout.LayoutParams(ui.dp(64), -2))
        tr.addView(track, LinearLayout.LayoutParams(0, ui.dp(30), 1f))
        tr.addView(total, LinearLayout.LayoutParams(ui.dp(64), -2).apply { marginStart = ui.dp(8) })
        track.id = View.generateViewId(); track.nextFocusUpId = track.id
        track.onKey = { dir, rep -> a.seekBy(dir * (if (rep > 12) 60_000L else if (rep > 4) 30_000L else 10_000L)); poke(); refresh() }
        view.addView(tr, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fun add(b: View) { row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) }) }
        if (slim) {
            // (v517) تلفزيون: الأساسي بس — ⏮ ▶ ⏭ · ترجم (بنسبتها) · 💬 · 🔤⏱ · HD · 🔊 · ⋯ (الباقي: سرعة/احتواء/شخصيات/جمل/لوج/قوايم)
            // التقديم/التأخير 10ث بالأسهم يمين/شمال، فأزرارهم اتشالت
            speedB = TextView(act); fitB = TextView(act)
            add(btn("⏮") { a.prev() })
            playB = btn("⏸") { a.toggle() }; add(playB)
            add(btn("⏭") { a.next() })
            trB = btn("▶ ترجم") { v -> a.translate(v) }; add(trB)
            ccB = btn("💬") { a.toggleCc() }; add(ccB)
            add(btn("🌐") { v -> a.subTracks(v) })   // (v521) مسار/لغة الترجمة جنب زرار الإخفاء
            add(btn("🔤 ⏱") { a.subDialog() })
            qB = btn("HD") { v -> a.quality(v) }; add(qB)
            audB = btn("🔊") { v -> a.audio(v) }; add(audB)
            add(btn("⋯") { v -> a.more(v) })
        } else {
        add(btn("⏮") { a.prev() })
        add(btn("⏪ 10") { a.seekBy(-10_000L) })
        playB = btn("⏸") { a.toggle() }; add(playB)
        add(btn("10 ⏩") { a.seekBy(10_000L) })
        add(btn("⏭") { a.next() })
        speedB = btn("⏩ 1x") { a.cycleSpeed() }; add(speedB)
        fitB = btn("⬛") { a.cycleFit() }; add(fitB)
        ccB = btn("💬") { a.toggleCc() }; add(ccB)
        add(btn("🌐") { v -> a.subTracks(v) })   // (v521)
        add(btn("🔤−") { a.subScale(-10) })
        add(btn("🔤+") { a.subScale(10) })
        add(btn("⏱−") { a.subShift(-250L) })
        add(btn("⏱+") { a.subShift(250L) })
        audB = btn("🔊") { v -> a.audio(v) }; add(audB)
        }
        val hs = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(row, FrameLayout.LayoutParams(-1, -2)) }
        view.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
    }

    fun poke() { h.removeCallbacks(hideRun); if (visible) h.postDelayed(hideRun, 5000L) }

    fun show() {
        if (visible) { poke(); return }
        visible = true; view.visibility = View.VISIBLE; refresh()
        h.removeCallbacks(tickRun); h.postDelayed(tickRun, 400L); poke()
        playB.requestFocus()
        onVis?.invoke(true)
    }

    fun hide() {
        visible = false; h.removeCallbacks(hideRun); h.removeCallbacks(tickRun)
        view.clearFocus(); view.visibility = View.GONE
        onVis?.invoke(false)
    }

    fun refresh() {
        try {
            val d = a.dur(); val p = a.pos()
            cur.text = PlayerLogic.clock(p); total.text = if (d > 0) PlayerLogic.clock(d) else "--:--"
            track.frac = if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f; track.invalidate()
            playB.text = if (a.playing()) "⏸" else "▶"
            if (slim) {
                trB.text = a.trLabel(); trB.background = GradientDrawable().apply { setColor(a.trColor()); cornerRadius = ui.dp(12).toFloat() }
                qB.visibility = if (a.hasQuality()) View.VISIBLE else View.GONE
            } else { speedB.text = "⏩ " + a.speedLabel(); fitB.text = "⬛ " + a.fitLabel() }
            ccB.alpha = if (a.ccOn()) 1f else 0.4f
            audB.visibility = if (a.hasAudio()) View.VISIBLE else View.GONE
        } catch (_: Throwable) {}
    }

    /** شريط التقدّم: يمين/شمال وهو متركّز = تقديم/تأخير (والضغط المستمر بيزوّد الخطوة) */
    private class SeekTrack(ctx: Context, private val accent: Int) : View(ctx) {
        var frac = 0f
        var onKey: ((Int, Int) -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isFocusable = true; isClickable = true }
        override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) { onKey?.invoke(-1, e.repeatCount); return true }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { onKey?.invoke(1, e.repeatCount); return true }
            return super.onKeyDown(k, e)
        }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val th = (if (isFocused) 10f else 6f) * d; val cy = height / 2f; val w = width.toFloat()
            p.color = 0x55FFFFFF; c.drawRoundRect(RectF(0f, cy - th / 2, w, cy + th / 2), th / 2, th / 2, p)
            p.color = accent; c.drawRoundRect(RectF(0f, cy - th / 2, w * frac, cy + th / 2), th / 2, th / 2, p)
            if (isFocused) { p.color = Color.WHITE; c.drawCircle((w * frac).coerceIn(th, w - th), cy, 9f * d, p) }
        }
    }
}
