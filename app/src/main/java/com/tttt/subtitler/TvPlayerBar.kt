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
        fun scrubPos(): Long = -1L    // (v569) مكان التقديم المعلّق (لسه ما اتنفّذش) أو -1
        // (v640) شريط التلفزيون الذكي: كل زرار يظهر بس لما يكون ليه لازمة
        fun hasPrevNext(): Boolean = true            // ⏮ ⏭ بس لو الفيديو من مكتبة فيها حلقة قبل/بعد
        fun pickSpeed(anchor: View) { cycleSpeed() } // ⏩ قايمة السرعات بدوسة واحدة
        fun subsAvailable(): Boolean = true          // 💬 و🔤⏱ بس لو في ترجمة (برنامج / جوه الفيديو / نسخ محفوظة)
        fun subSourcesMulti(): Boolean = false       // 🌐 بس لو في أكتر من مصدر ترجمة
        fun subSources(anchor: View) { subTracks(anchor) }
        fun hasSentences(): Boolean = false          // 📝 بس لو في جمل مترجمة
        fun sentences() {}
        fun sleepLabel(): String = ""                // 😴 بس لو المؤقت شغّال
        fun sleepPick(anchor: View) {}
        fun subPage() { subDialog() }                // (v676) صفحة واحدة لكل أدوات الترجمة (مصادر · شكل وتوقيت · جمل · باتشات · إعدادات…)
    }

    private val h = Handler(Looper.getMainLooper())
    val view = LinearLayout(act)
    var visible = false; private set
    var onVis: ((Boolean) -> Unit)? = null   // (v509) Player يظهر/يخفي العدّادات مع الشريط
    fun focusMain() { try { playB.requestFocus() } catch (_: Throwable) {} }
    /** (v569) الريموت يوصل لشريط التقدم (فوق من الأزرار) ويسحب عليه يمين/شمال */
    fun focusTrack() { try { track.requestFocus() } catch (_: Throwable) {} }
    fun onTrack(v: View?): Boolean = v === track
    private val hideRun = Runnable { hide() }
    private val tickRun = object : Runnable { override fun run() { if (visible) { refresh(); h.postDelayed(this, 400L) } } }

    private val cur = TextView(act); private val total = TextView(act)
    private val track = SeekTrack(act, th.primary)
    private lateinit var playB: TextView; private lateinit var speedB: TextView; private lateinit var fitB: TextView
    private lateinit var ccB: TextView; private lateinit var audB: TextView
    private lateinit var trB: TextView; private lateinit var qB: TextView
    private lateinit var prevB: TextView; private lateinit var nextB: TextView   // (v640)
    private lateinit var aaB: TextView
    private lateinit var sleepB: TextView
    private val slim: Boolean = try { a.slim() } catch (_: Throwable) { false }

    private fun btn(label: String, onClick: (View) -> Unit): TextView = IconTextView(act).apply {   // (v527) IconTextView: ⏸ ▶ ⏮ ⏭ بتترسم أيقونة (خطين متوازيين) بدل رمز الخط اللي على TCL كان بيطلع شكل «i»
        text = label; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = TvBlur.frostDrawable(12f, resources.displayMetrics.density)
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
            // (v640) تلفزيون: ⏮ ▶ ⏭ · ⏩ سرعة (قايمة) · ⬛ حجم الشاشة · ترجم (بنسبتها) · 💬 · 🌐 مصادر الترجمة · 🔤⏱ شكل/ذكاء · 📝 · HD · 🔊 · 😴 (لو شغّال) · ⋯
            // كل زرار بيظهر/بيختفي في refresh() حسب حالة الفيديو (ترجمة؟ مسارات؟ نسخ؟) — الباقي كله في ⋯
            prevB = btn("⏮") { a.prev() }; add(prevB)
            playB = btn("⏸") { a.toggle() }; add(playB)
            nextB = btn("⏭") { a.next() }; add(nextB)
            speedB = btn("⏩ 1x") { v -> a.pickSpeed(v) }; add(speedB)
            fitB = btn("⛶") { a.cycleFit() }; add(fitB)   // (v662) أيقونة ⛶ (نفس أيقونة زرار التكبير القديم) حجم الشاشة (احتواء / ملء / تمديد / 100%) على الشريط نفسه بدل «⋯»
            trB = btn("▶ ترجم") { v -> a.translate(v) }; add(trB)
            ccB = btn("💬") { a.toggleCc() }
            ccB.setOnLongClickListener { a.subSources(ccB); true }
            add(ccB)
            aaB = btn("🔤 الترجمة") { a.subPage() }; add(aaB)   // (v676) 🌐 و🔤⏱ و📝 اتجمّعوا في زرار واحد بيفتح صفحة الترجمة
            qB = btn("HD") { v -> a.quality(v) }; add(qB)
            audB = btn("🔊") { v -> a.audio(v) }; add(audB)
            sleepB = btn("😴") { v -> a.sleepPick(v) }; add(sleepB)
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
        add(btn("🌐") { v -> a.subTracks(v) })
        add(btn("🔤 ⏱") { a.subDialog() })
        audB = btn("🔊") { v -> a.audio(v) }; add(audB)
        val sleepMins = intArrayOf(0, 15, 30, 60)
        val sleepPos = intArrayOf(0)
        add(btn("😴") { v ->
            sleepPos[0] = (sleepPos[0] + 1) % sleepMins.size
            val mins = sleepMins[sleepPos[0]]
            TvLink.setSleep(mins)
            (v as TextView).text = if (mins == 0) "😴" else "😴 $mins"
        })
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
            val d = a.dur(); val sp = a.scrubPos(); val p = if (sp >= 0) sp else a.pos()
            cur.text = PlayerLogic.clock(p); total.text = if (d > 0) PlayerLogic.clock(d) else "--:--"
            track.frac = if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f; track.invalidate()
            playB.text = if (a.playing()) "⏸" else "▶"
            if (slim) {
                trB.text = a.trLabel(); trB.background = GradientDrawable().apply { setColor(a.trColor()); cornerRadius = ui.dp(12).toFloat() }
                speedB.text = "⏩ " + a.speedLabel()
                fitB.text = "⛶ " + a.fitLabel()
                fun vis(v: View, on: Boolean) { val n = if (on) View.VISIBLE else View.GONE; if (v.visibility != n) v.visibility = n }
                val pn = a.hasPrevNext(); vis(prevB, pn); vis(nextB, pn)
                val subs = a.subsAvailable(); vis(ccB, subs); vis(aaB, subs || a.hasSentences())
                vis(qB, a.hasQuality())
                val sl = a.sleepLabel(); vis(sleepB, sl.isNotEmpty()); if (sl.isNotEmpty()) sleepB.text = sl
                // لو الزرار اللي عليه التركيز اختفى → التركيز يرجع لـ ▶
                try { val f = view.findFocus(); if (f != null && !f.isShown) playB.requestFocus() } catch (_: Throwable) {}
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
