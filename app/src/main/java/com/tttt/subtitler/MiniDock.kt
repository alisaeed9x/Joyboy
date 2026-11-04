package com.tttt.subtitler

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide

/**
 * (v344) «المشغّل المصغّر» العايم: دايرة بغلاف الأغنية بتظهر في كل الصفحات (الرئيسية · يوتيوب · المهام · المتصفح · الملفات) طول ما فيه أغنية/صوت شغّال،
 * ومابتظهرش في صفحة الموسيقى نفسها. بتتسحب لأي مكان وبتلزق في حافة الشمال أو اليمين (عشان ما تغطيش المحتوى)، ومكانها بيتحفظ.
 * (v730) دوسة على الدايرة = بتتفتح لوحة: ⏮ ⏯ ⏭ ✕ + سلايدر التقدّم + سلايدر الصوت + قايمة الأغاني اللي جاية (اختار منها). دوسة على الصورة وهي مفتوحة = المشغّل الكامل.
 * وهي بتتسحب بيظهر ✕ تحت: سيبها عليه = الموسيقى تتقفل. اللوحة بتتلم لوحدها بعد 8 ثواني من غير لمس.
 * parent لازم يكون FrameLayout بيغطي الشاشة. بتتنده detach() من onDestroy.
 */
class MiniDock(
    private val act: Activity, private val parent: FrameLayout, private val ui: Ui, private val th: Theme,
    private val onOpenFull: () -> Unit,
    private val hideIf: () -> Boolean = { false },
    private val artOf: (Track) -> Bitmap? = { null }
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences("p", 0)
    private val circle = ui.dp(46)
    private var expanded = false
    private var artKey = ""

    private val art = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF2A2A36.toInt())
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) } }
        clipToOutline = true
    }
    private val title = ui.text("", 13f, Color.WHITE, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; isSelected = true; maxWidth = ui.dp(120) }
    private val sub = ui.text("", 11f, 0xFFB8BCC8.toInt()).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = ui.dp(120) }
    private val btnPrev = btn("⏮") { MusicEngine.step(-1) }
    private val btnPlay = btn("▮▮") { MusicEngine.toggle() }
    private val btnNext = btn("⏭") { MusicEngine.step(1) }
    private val btnStop = btn("✕") { MusicEngine.stopAll() }
    // (v736) تصغير (يرجّع الدايرة) وتكبير (المشغّل الكامل) — زراير صريحة بدل الاعتماد على دوسة الصورة
    private val btnMin = btn("—") { collapse() }
    private val btnMax = btn("⤢") { collapse(); onOpenFull() }
    private val capsule = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_LTR }

    private fun btn(sym: String, f: () -> Unit): TextView = ui.text(sym, 18f, Color.WHITE).apply {
        gravity = Gravity.CENTER; includeFontPadding = false
        setOnClickListener { bump(); f() }
    }

    private val collapseTick = Runnable { if (seeking) bump() else collapse() }
    private fun bump() { main.removeCallbacks(collapseTick); if (expanded) main.postDelayed(collapseTick, 8000L) }

    // ===== (v730) لوحة التحكم الموسّعة: تقدّم + صوت + قايمة التالي =====
    private var seeking = false
    private var queueKey = ""
    private val muted = 0xFFB8BCC8.toInt()
    private val seek = SeekBar(act).apply { max = 1000 }
    private val volBar = SeekBar(act).apply { max = 100 }
    private val timeL = ui.text("0:00", 11f, muted)
    private val timeR = ui.text("0:00", 11f, muted)
    private val queueBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    // (v736) القايمة على قد عدد الأغاني (لحد 4 صفوف تقريبًا وبعدها سكرول) — مش طول ثابت حتى لو فاضية
    private val queueScroll = object : ScrollView(act) {
        override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(ui.dp(140), View.MeasureSpec.AT_MOST)) }
    }.apply { addView(queueBox, ViewGroup.LayoutParams(-1, -2)) }
    private val panel = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(6)) }
    private val topRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
    private val trash = ui.text("✕", 24f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; visibility = View.GONE; elevation = ui.dp(12).toFloat()
        background = ui.box(0xCCE53935.toInt(), 0x88FFFFFF.toInt(), 32, 2)
    }
    private fun clock(ms: Int): String { val s0 = (ms.coerceAtLeast(0) / 1000); return "%d:%02d".format(s0 / 60, s0 % 60) }
    private val posTick = object : Runnable {
        override fun run() {
            if (!expanded) return
            if (!seeking) {
                val d = MusicEngine.durMs; val pos = MusicEngine.posMs
                if (d > 0) seek.progress = (pos.toLong() * 1000 / d).toInt().coerceIn(0, 1000)
                timeL.text = clock(pos); timeR.text = clock(d)
            }
            main.postDelayed(this, 500L)
        }
    }
    private fun noIntercept(v: View) { v.setOnTouchListener { x, _ -> x.parent?.requestDisallowInterceptTouchEvent(true); false } }
    private fun renderQueue() {
        val q = MusicEngine.queue; val i = MusicEngine.idx
        val key = System.identityHashCode(q).toString() + ":" + i + ":" + q.size
        if (key == queueKey) return
        queueKey = key
        queueBox.removeAllViews()
        if (i < 0 || i + 1 >= q.size) { queueBox.addView(ui.text("مفيش أغاني بعد دي", 12f, muted).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }); dock.post { place(false) }; return }
        for (k in i + 1 until minOf(q.size, i + 1 + 40)) {
            val t = q[k]
            val r = ui.text((k - i).toString() + ".  " + t.title + (if (t.artist.isBlank()) "" else "  —  " + t.artist), 12.5f, Color.WHITE).apply {
                setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                setOnClickListener { bump(); MusicEngine.play(act, MusicEngine.queue, k) }
            }
            queueBox.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        dock.post { place(false) }
    }
    private fun showTrash() {
        trash.visibility = View.VISIBLE; trash.alpha = 0f; trash.scaleX = 0.6f; trash.scaleY = 0.6f
        trash.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
    }
    private fun hideTrash() { trash.animate().alpha(0f).setDuration(120L).withEndAction { trash.visibility = View.GONE }.start() }
    private fun nearTrash(): Boolean {
        if (trash.visibility != View.VISIBLE) return false
        val dxc = dock.translationX + dock.width / 2f; val dyc = dock.translationY + dock.height / 2f
        val tx = trash.left + trash.width / 2f; val ty = trash.top + trash.height / 2f
        return Math.hypot((dxc - tx).toDouble(), (dyc - ty).toDouble()) < ui.dp(70)
    }
    private fun updateTrash() { val on = nearTrash(); val sc = if (on) 1.3f else 1f; if (trash.scaleX != sc) { trash.scaleX = sc; trash.scaleY = sc } }
    private fun collapseQuiet() { expanded = false; main.removeCallbacks(collapseTick); capsule.visibility = View.GONE; panel.visibility = View.GONE; paintBg() }

    private inner class DockLayout : LinearLayout(act) {
        private var dx = 0f; private var dy = 0f; private var sx = 0f; private var sy = 0f; private var drag = false
        private fun down(e: MotionEvent) { dx = e.rawX; dy = e.rawY; sx = translationX; sy = translationY; drag = false; bump() }
        private fun move(e: MotionEvent) {
            if (!drag && Math.abs(e.rawX - dx) + Math.abs(e.rawY - dy) > ui.dp(10)) { drag = true; main.removeCallbacks(collapseTick); this@MiniDock.parent.requestDisallowInterceptTouchEvent(true); if (expanded) collapseQuiet(); showTrash() }
            if (drag) {
                translationX = (sx + e.rawX - dx).coerceIn(0f, (this@MiniDock.parent.width - width).coerceAtLeast(0).toFloat())
                translationY = (sy + e.rawY - dy).coerceIn(0f, (this@MiniDock.parent.height - height).coerceAtLeast(0).toFloat())
                updateTrash()
            }
        }
        private fun up() {
            if (drag) {
                val hit = nearTrash(); hideTrash()
                if (hit) { drag = false; MusicEngine.stopAll(); refresh(); return }   // (v730) سيبها على ✕ = قفل الموسيقى
                val right = translationX + width / 2f > this@MiniDock.parent.width / 2f
                val room = (this@MiniDock.parent.height - height).coerceAtLeast(1)
                prefs.edit().putBoolean("dock_right", right).putFloat("dock_fy", (translationY / room).coerceIn(0f, 1f)).apply()
                place(true); bump()
            } else if (!expanded) expand() else collapse()   // دوسة على الغلاف وهي مفتوحة = تتلم
            drag = false
        }
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> down(e)
                MotionEvent.ACTION_MOVE -> { move(e); if (drag) return true }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down(e); return true }
                MotionEvent.ACTION_MOVE -> { move(e); return true }
                MotionEvent.ACTION_UP -> { up(); return true }
                MotionEvent.ACTION_CANCEL -> { if (drag) up(); return true }
            }
            return false
        }
    }
    private val dock = DockLayout().apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.START; layoutDirection = View.LAYOUT_DIRECTION_LTR
        visibility = View.GONE; elevation = ui.dp(10).toFloat(); setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
    }

    private val listener: () -> Unit = { if (!act.isDestroyed && !act.isFinishing) refresh() }

    init {
        topRow.addView(art, LinearLayout.LayoutParams(circle, circle))
        // (v467) بطلب صاحب التطبيق: دوسة على صورة الأغنية أو اسمها/مغنيها ترجّع لمشغّل الموسيقى الكامل (زي ⤢ بالظبط)
        // (v730) أول دوسة على الدايرة = تفتح اللوحة؛ دوسة على الصورة وهي مفتوحة = المشغّل الكامل
        art.setOnClickListener { if (!expanded) expand() else { bump(); collapse(); onOpenFull() } }
        title.setOnClickListener { bump(); collapse(); onOpenFull() }
        sub.setOnClickListener { bump(); collapse(); onOpenFull() }
        for (b in listOf(btnPrev, btnPlay, btnNext, btnStop, btnMin, btnMax)) capsule.addView(b, LinearLayout.LayoutParams(ui.dp(38), ui.dp(40)))
        // (v736) اسم الأغنية (والمغني) مكتوب تحت الصورة والأزرار
        title.gravity = Gravity.CENTER_HORIZONTAL; sub.gravity = Gravity.CENTER_HORIZONTAL
        title.maxWidth = ui.dp(290); sub.maxWidth = ui.dp(290)
        panel.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        panel.addView(sub, LinearLayout.LayoutParams(-1, -2))
        topRow.addView(capsule, LinearLayout.LayoutParams(-2, -2))
        dock.addView(topRow, LinearLayout.LayoutParams(-2, -2))
        // لوحة التقدّم والصوت والقايمة
        val seekRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        seekRow.addView(timeL, LinearLayout.LayoutParams(ui.dp(34), -2))
        seekRow.addView(seek, LinearLayout.LayoutParams(0, ui.dp(34), 1f))
        seekRow.addView(timeR, LinearLayout.LayoutParams(ui.dp(34), -2))
        val volRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        volRow.addView(ui.text("🔊", 14f, Color.WHITE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(34), -2))
        volRow.addView(volBar, LinearLayout.LayoutParams(0, ui.dp(34), 1f))
        volRow.addView(View(act), LinearLayout.LayoutParams(ui.dp(34), 1))
        panel.addView(seekRow, LinearLayout.LayoutParams(-1, -2))
        panel.addView(volRow, LinearLayout.LayoutParams(-1, -2))
        panel.addView(ui.text("الأغاني اللي جاية", 11f, muted, true).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(2)); layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.START }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(queueScroll, LinearLayout.LayoutParams(-1, -2))
        dock.addView(panel, LinearLayout.LayoutParams(ui.dp(304), -2))
        noIntercept(seek); noIntercept(volBar); noIntercept(queueScroll)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { if (user) { timeL.text = clock((MusicEngine.durMs.toLong() * p / 1000).toInt()); bump() } }
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true; bump() }
            override fun onStopTrackingTouch(sb: SeekBar) { seeking = false; val d = MusicEngine.durMs; if (d > 0) MusicEngine.seekTo((d.toLong() * sb.progress / 1000).toInt()); bump() }
        })
        volBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { if (user) { SysVol.set(act, p); bump() } }
            override fun onStartTrackingTouch(sb: SeekBar) { bump() }
            override fun onStopTrackingTouch(sb: SeekBar) { bump() }
        })
        parent.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        parent.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (r - l != or2 - ol || b - t != ob - ot) place(false) }
        parent.addView(trash, FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(90) })
        MusicEngine.listeners.add(listener)
        parent.post { refresh() }
    }

    fun detach() { MusicEngine.listeners.remove(listener); main.removeCallbacks(collapseTick); main.removeCallbacks(posTick) }

    private fun paintBg() {
        val on = MusicEngine.isPlaying
        dock.background = ui.box(0xEE1B1E27.toInt(), if (on) 0xFF8FB4FF.toInt() else 0x55FFFFFF, if (expanded) 28 else 26, 2)
    }

    /** مكان الدايرة/الكبسولة: لازقة في الحافة (شمال/يمين) بالارتفاع المحفوظ — من غير ما تطلع بره الشاشة */
    private fun place(animate: Boolean) {
        val w = parent.width; val h = parent.height
        if (w <= 0 || h <= 0 || dock.visibility != View.VISIBLE) return
        val dw = if (dock.width > 0) dock.width else circle + ui.dp(6)
        val dh = if (dock.height > 0) dock.height else circle + ui.dp(6)
        val m = ui.dp(6)
        val right = prefs.getBoolean("dock_right", true)
        val fy = prefs.getFloat("dock_fy", 0.62f)
        val tx = (if (right) w - dw - m else m).coerceAtLeast(0).toFloat()
        val ty = (fy * (h - dh)).coerceIn(0f, (h - dh).coerceAtLeast(0).toFloat())
        if (animate) dock.animate().translationX(tx).translationY(ty).setDuration(180L).start()
        else { dock.animate().cancel(); dock.translationX = tx; dock.translationY = ty }
    }

    private fun expand() {
        expanded = true; capsule.visibility = View.VISIBLE; panel.visibility = View.VISIBLE
        (panel.layoutParams as LinearLayout.LayoutParams).width = minOf(ui.dp(304), (parent.width - ui.dp(12)).coerceAtLeast(ui.dp(200)))
        volBar.progress = SysVol.get(act)
        queueKey = ""; renderQueue()
        paintBg(); refresh()
        main.removeCallbacks(posTick); main.post(posTick)
        dock.post { place(true) }; bump()
    }
    fun collapse() {
        if (!expanded) return
        expanded = false; main.removeCallbacks(collapseTick); main.removeCallbacks(posTick); capsule.visibility = View.GONE; panel.visibility = View.GONE; paintBg()
        dock.post { place(true) }
    }

    private fun loadArt(t: Track) {
        if (artKey == t.uri) return
        artKey = t.uri
        val key = t.uri
        val have = artOf(t)
        if (have != null) { art.setImageBitmap(have); return }
        art.setImageResource(R.drawable.no_poster)
        if (YtAudio.isRemote(t.uri)) {
            val u = YtAudio.thumbUrl(t)
            if (u != null) try { Glide.with(act).load(u).centerCrop().into(art) } catch (_: Throwable) {}
            return
        }
        Thread {
            val b = try {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(act, Uri.parse(t.uri)); r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { try { r.release() } catch (_: Throwable) {} }
            } catch (_: Throwable) { null }
            if (b != null) act.runOnUiThread { if (artKey == key && !act.isDestroyed) art.setImageBitmap(b) }
        }.apply { isDaemon = true }.start()
    }

    fun refresh() {
        val t = MusicEngine.current
        val show = t != null && !hideIf() && !act.isFinishing && !act.isDestroyed
        if (!show) { if (expanded) collapse(); dock.visibility = View.GONE; return }
        val wasGone = dock.visibility != View.VISIBLE
        dock.visibility = View.VISIBLE
        val tr = t!!
        loadArt(tr)
        if (artOf(tr) != null && artKey == tr.uri) art.setImageBitmap(artOf(tr))   // الغلاف المتعرّف عليه (من مشغّل الموسيقى) أحسن من المدمج
        title.text = tr.title; sub.text = tr.artist; sub.visibility = if (tr.artist.isBlank()) View.GONE else View.VISIBLE
        btnPrev.visibility = if (YtAudio.isRemote(tr.uri)) View.GONE else View.VISIBLE   // صوت يوتيوب: مفيش «سابق»
        btnPlay.text = if (MusicEngine.isPlaying) "▮▮" else "▶"
        paintBg()
        if (expanded) renderQueue()
        if (wasGone) { dock.alpha = 0f; dock.post { place(false); dock.animate().alpha(1f).setDuration(200L).start() } }
        else dock.post { place(false) }
    }
}
