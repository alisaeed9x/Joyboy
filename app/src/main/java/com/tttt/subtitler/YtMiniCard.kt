package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import com.newtube.mobile.ui.playback.MiniPlayerBridge

/**
 * (v1037) كارت يوتيوب المصغّر («PiP داخلي») بيمشي مع المستخدم في باقي صفحات Joyboy (ميديا · متصفح · ملفات).
 * قبل كده الكارت كان مرسوم جوه شاشات MeeTube بس (Browse/Search/Channel)، فأول ما تروح لصفحة الميديا كان بيختفي.
 * هنا بنستعمل نفس الـSurfaceTexture بتاع جلسة التشغيل (MiniPlayerBridge.getSessionTexture) في TextureView
 * بتاع الصفحة دي طول ما هي قدّام (onResume)، ونرجّعها قبل ما الصفحة تتغطّى (onPause) — زي ما MobileMiniPlayerController بيعمل بالظبط.
 * دوسة على الكارت = يكبّر لمشغّل MeeTube الكامل · ⏯ تشغيل/إيقاف · ✕ يقفل · بيتسحب لأي مكان.
 * parent لازم FrameLayout بيغطي الشاشة. بتتنده detach() من onDestroy (أو بتنضّف نفسها لوحدها).
 */
class YtMiniCard(private val act: Activity, private val parent: FrameLayout, private val bottomMarginDp: Int = 96) {
    private val main = Handler(Looper.getMainLooper())
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val frame = AspectRatioFrameLayout(act).apply {
        setBackgroundColor(Color.BLACK)
        setAspectRatio(16f / 9f)
    }
    private val title = TextView(act).apply {
        setTextColor(Color.WHITE); textSize = 11f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(6), dp(2), dp(6), dp(2)); setBackgroundColor(0x99000000.toInt())
    }
    private val btnPlay = btn("⏸") { togglePlay() }
    private val btnClose = btn("✕") { closeAll() }
    private val card = object : FrameLayout(act) {
        private var dx = 0f; private var dy = 0f; private var sx = 0f; private var sy = 0f; private var moved = false
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; dx = x - e.rawX; dy = y - e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> if (!moved && (Math.abs(e.rawX - sx) > dp(8) || Math.abs(e.rawY - sy) > dp(8))) moved = true
            }
            return moved
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_MOVE -> {
                    moved = true
                    val pw = parent.width - width; val ph = parent.height - height
                    x = (e.rawX + dx).coerceIn(0f, pw.toFloat().coerceAtLeast(0f))
                    y = (e.rawY + dy).coerceIn(0f, ph.toFloat().coerceAtLeast(0f))
                    return true
                }
                MotionEvent.ACTION_UP -> { if (!moved) expand(); return true }
            }
            return false
        }
    }.apply {
        visibility = View.GONE; elevation = dp(12).toFloat()
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x55FFFFFF) }
        clipToOutline = true
    }

    private var tex: TextureView? = null
    private var shown = false
    private var placed = false
    private val tick = object : Runnable {
        override fun run() {
            if (!shown) return
            if (!MiniPlayerBridge.isActive()) { hide(); return }
            MiniPlayerBridge.fitToVideo(frame)
            MiniPlayerBridge.getPlayer()?.let { btnPlay.text = if (it.playWhenReady) "⏸" else "▶" }
            try { title.text = MiniPlayerBridge.getVideo()?.title ?: "" } catch (_: Throwable) {}
            main.postDelayed(this, 700L)
        }
    }

    private val life = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if (a === act) show() }
        override fun onActivityPaused(a: Activity) { if (a === act) hide() }
        override fun onActivityDestroyed(a: Activity) { if (a === act) detach() }
        override fun onActivityCreated(a: Activity, s: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
    }

    init {
        val w = dp(190)
        frame.addView(View(act), FrameLayout.LayoutParams(-1, -1))   // مكان الفيديو (الـTextureView بيتضاف فوقه)
        card.addView(frame, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(title, FrameLayout.LayoutParams(w - dp(40), -2, Gravity.TOP or Gravity.START))
        card.addView(btnClose, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END))
        card.addView(btnPlay, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
        parent.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(bottomMarginDp); marginEnd = dp(10)
        })
        act.application.registerActivityLifecycleCallbacks(life)
        if (!act.isFinishing) main.post { show() }   // الصفحة اتبنت وهي قدّام (onResume عدّى قبل ما نتسجّل)
    }

    private fun btn(sym: String, f: () -> Unit) = TextView(act).apply {
        text = sym; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; includeFontPadding = false
        setOnClickListener { f() }
    }

    private fun togglePlay() {
        val p = MiniPlayerBridge.getPlayer() ?: return
        p.playWhenReady = !p.playWhenReady
        btnPlay.text = if (p.playWhenReady) "⏸" else "▶"
    }

    private fun closeAll() {
        hide()
        try { MiniPlayerBridge.close() } catch (_: Throwable) {}
    }

    private fun expand() {
        hide()   // لازم نرجّع الـtexture الأول (المشغّل الكامل بياخده تاني)
        try { MiniPlayerBridge.expand(act) } catch (_: Throwable) {}
    }

    fun show() {
        if (act.isFinishing || act.isDestroyed) return
        if (!MiniPlayerBridge.isActive()) { hide(); return }
        if (shown) return
        shown = true
        attachTexture()
        card.visibility = View.VISIBLE
        card.bringToFront()
        main.removeCallbacks(tick); main.post(tick)
    }

    fun hide() {
        shown = false
        main.removeCallbacks(tick)
        val t = tex
        if (t != null) { frame.removeView(t); tex = null }   // من غير release — الـSurfaceTexture بتاع جلسة المشغّل
        card.visibility = View.GONE
    }

    fun detach() {
        hide()
        try { act.application.unregisterActivityLifecycleCallbacks(life) } catch (_: Throwable) {}
        try { parent.removeView(card) } catch (_: Throwable) {}
    }

    private fun attachTexture() {
        if (tex != null) return
        val t = TextureView(act)
        t.isOpaque = false
        t.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private var adopted: SurfaceTexture? = null
            override fun onSurfaceTextureAvailable(created: SurfaceTexture, width: Int, height: Int) {
                val session = MiniPlayerBridge.getSessionTexture()
                if (session != null && created !== session) {
                    t.setSurfaceTexture(session)
                    adopted = session
                    created.release()
                }
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = s !== adopted && s !== MiniPlayerBridge.getSessionTexture()
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        frame.addView(t, 0, FrameLayout.LayoutParams(-1, -1))
        tex = t
    }
}
