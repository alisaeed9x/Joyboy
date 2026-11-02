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
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide

/**
 * (v344) «المشغّل المصغّر» العايم: دايرة بغلاف الأغنية بتظهر في كل الصفحات (الرئيسية · يوتيوب · المهام · المتصفح · الملفات) طول ما فيه أغنية/صوت شغّال،
 * ومابتظهرش في صفحة الموسيقى نفسها. بتتسحب لأي مكان وبتلزق في حافة الشمال أو اليمين (عشان ما تغطيش المحتوى)، ومكانها بيتحفظ.
 * دوسة على الدايرة = بتتفتح كبسولة عريضة (⏮ ⏯ ⏭ ⤢ ✕) وبتتلم لوحدها بعد 5 ثواني من غير لمس. ⤢ = المشغّل الكامل.
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
    private val btnOpen = btn("⤢") { collapse(); onOpenFull() }
    private val btnStop = btn("✕") { MusicEngine.stopAll() }
    private val capsule = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_LTR }

    private fun btn(sym: String, f: () -> Unit): TextView = ui.text(sym, 18f, Color.WHITE).apply {
        gravity = Gravity.CENTER; includeFontPadding = false
        setOnClickListener { bump(); f() }
    }

    private val collapseTick = Runnable { collapse() }
    private fun bump() { main.removeCallbacks(collapseTick); if (expanded) main.postDelayed(collapseTick, 5000L) }

    private inner class DockLayout : LinearLayout(act) {
        private var dx = 0f; private var dy = 0f; private var sx = 0f; private var sy = 0f; private var drag = false
        private fun down(e: MotionEvent) { dx = e.rawX; dy = e.rawY; sx = translationX; sy = translationY; drag = false; bump() }
        private fun move(e: MotionEvent) {
            if (!drag && Math.abs(e.rawX - dx) + Math.abs(e.rawY - dy) > ui.dp(10)) { drag = true; main.removeCallbacks(collapseTick); this@MiniDock.parent.requestDisallowInterceptTouchEvent(true) }
            if (drag) {
                translationX = (sx + e.rawX - dx).coerceIn(0f, (this@MiniDock.parent.width - width).coerceAtLeast(0).toFloat())
                translationY = (sy + e.rawY - dy).coerceIn(0f, (this@MiniDock.parent.height - height).coerceAtLeast(0).toFloat())
            }
        }
        private fun up() {
            if (drag) {
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
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
        visibility = View.GONE; elevation = ui.dp(10).toFloat(); setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
    }

    private val listener: () -> Unit = { if (!act.isDestroyed && !act.isFinishing) refresh() }

    init {
        dock.addView(art, LinearLayout.LayoutParams(circle, circle))
        // (v467) بطلب صاحب التطبيق: دوسة على صورة الأغنية أو اسمها/مغنيها ترجّع لمشغّل الموسيقى الكامل (زي ⤢ بالظبط)
        art.setOnClickListener { bump(); collapse(); onOpenFull() }
        title.setOnClickListener { bump(); collapse(); onOpenFull() }
        sub.setOnClickListener { bump(); collapse(); onOpenFull() }
        val tc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), 0, ui.dp(4), 0) }
        tc.addView(title); tc.addView(sub)
        capsule.addView(tc, LinearLayout.LayoutParams(-2, -2))
        for (b in listOf(btnPrev, btnPlay, btnNext, btnOpen, btnStop)) capsule.addView(b, LinearLayout.LayoutParams(ui.dp(38), ui.dp(40)))
        dock.addView(capsule, LinearLayout.LayoutParams(-2, -2))
        parent.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        parent.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (r - l != or2 - ol || b - t != ob - ot) place(false) }
        MusicEngine.listeners.add(listener)
        parent.post { refresh() }
    }

    fun detach() { MusicEngine.listeners.remove(listener); main.removeCallbacks(collapseTick) }

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
        expanded = true; capsule.visibility = View.VISIBLE; paintBg(); refresh()
        dock.post { place(true) }; bump()
    }
    fun collapse() {
        if (!expanded) return
        expanded = false; main.removeCallbacks(collapseTick); capsule.visibility = View.GONE; paintBg()
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
        if (wasGone) { dock.alpha = 0f; dock.post { place(false); dock.animate().alpha(1f).setDuration(200L).start() } }
        else dock.post { place(false) }
    }
}
