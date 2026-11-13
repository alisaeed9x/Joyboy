package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * (v823) زرار «لفّ الشاشة» زي اللي أندرويد بيطلّعه: لو التطبيق على لاندسكيب وإنت ماسك التليفون بورتريه (أو العكس) وفضل كده حوالي ثانية،
 * بيظهر زرار 🔄 صغير على الحافة اليمين؛ دوسة عليه = الشاشة تتحوّل لاتجاه التليفون الفعلي. بيختفي لوحده بعد 7 ثواني أو لما الاتجاهين يتطابقوا.
 * بيشتغل بحساس الاتجاه نفسه، فمش بيحتاج إن «التدوير التلقائي» يكون شغّال. التلفزيون: مش بيتركّب.
 * الاستخدام: RotateHint.attach(activity) مرة واحدة في onCreate (بيمسك دورة حياة الأكتيفيتي لوحده).
 */
object RotateHint {
    private val live = java.util.WeakHashMap<Activity, Hint>()

    fun attach(act: Activity) {
        try { if (DeviceUi.isTv(act)) return } catch (_: Throwable) {}
        if (live.containsKey(act)) return
        act.window.decorView.post {
            if (act.isFinishing || act.isDestroyed || live.containsKey(act)) return@post
            val h = Hint(act); live[act] = h
            h.install()
        }
    }

    private class Hint(val act: Activity) : Application.ActivityLifecycleCallbacks {
        private val h = Handler(Looper.getMainLooper())
        private val den = act.resources.displayMetrics.density
        private fun dp(v: Int) = (v * den).toInt()
        private var btn: TextView? = null
        private var targetLand = false
        private var pendingShow: Runnable? = null
        private val autoHide = Runnable { hide() }
        private var phys: Boolean? = null   // true = التليفون ماسكه لاندسكيب · false = بورتريه · null = مش واضح/مسطّح

        private val listener = object : OrientationEventListener(act) {
            override fun onOrientationChanged(deg: Int) { try { onDeg(deg) } catch (_: Throwable) {} }
        }

        fun install() {
            act.application.registerActivityLifecycleCallbacks(this)
            if (act.hasWindowFocus() || !act.isFinishing) enable()
        }
        private fun enable() { try { if (listener.canDetectOrientation()) listener.enable() } catch (_: Throwable) {} }
        private fun disable() { try { listener.disable() } catch (_: Throwable) {}; cancelPending(); hide() }

        /** الجهاز الطبيعي (صفر درجة) لاندسكيب؟ (بعض التابلتات) */
        private fun naturalLandscape(): Boolean {
            val dm = act.resources.displayMetrics
            val rot = try { act.windowManager.defaultDisplay.rotation } catch (_: Throwable) { 0 }
            val wide = dm.widthPixels > dm.heightPixels
            return if (rot == 0 || rot == 2) wide else !wide
        }

        private fun onDeg(deg: Int) {
            if (deg == OrientationEventListener.ORIENTATION_UNKNOWN) { phys = null; cancelPending(); return }
            val p = when {
                deg <= 30 || deg >= 330 || deg in 150..210 -> false
                deg in 60..120 || deg in 240..300 -> true
                else -> null
            }
            phys = p?.let { if (naturalLandscape()) !it else it }
            val cur = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val pp = phys
            if (pp == null || pp == cur) { cancelPending(); hide(); return }
            if (btn?.visibility == View.VISIBLE) { targetLand = pp; return }
            if (pendingShow != null) return
            val r = Runnable {
                pendingShow = null
                val now = phys ?: return@Runnable
                val c = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (now != c && !act.isFinishing && !act.isDestroyed) { targetLand = now; show() }
            }
            pendingShow = r; h.postDelayed(r, 900L)
        }
        private fun cancelPending() { pendingShow?.let { h.removeCallbacks(it) }; pendingShow = null }

        private fun ensureBtn(): TextView {
            btn?.let { return it }
            val b = TextView(act).apply {
                text = "🔄"; textSize = 20f; gravity = Gravity.CENTER; includeFontPadding = false; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE61E2A3A.toInt()); setStroke(dp(1), 0x66FFFFFF) }
                elevation = dp(12).toFloat(); visibility = View.GONE
                contentDescription = "لفّ الشاشة"
                setOnClickListener {
                    val land = targetLand
                    hide()
                    act.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
            act.addContentView(b, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })
            btn = b
            return b
        }
        private fun show() {
            val b = ensureBtn()
            b.rotation = if (targetLand) 90f else 0f   // تلميح بصري بسيط: الاتجاه اللي هتروحله
            b.alpha = 0f; b.visibility = View.VISIBLE; b.bringToFront()
            b.animate().alpha(1f).setDuration(160L).start()
            h.removeCallbacks(autoHide); h.postDelayed(autoHide, 7000L)
        }
        private fun hide() {
            h.removeCallbacks(autoHide)
            val b = btn ?: return
            if (b.visibility == View.VISIBLE) { b.animate().cancel(); b.visibility = View.GONE }
        }

        override fun onActivityResumed(a: Activity) { if (a === act) enable() }
        override fun onActivityPaused(a: Activity) { if (a === act) disable() }
        override fun onActivityDestroyed(a: Activity) {
            if (a !== act) return
            disable(); try { act.application.unregisterActivityLifecycleCallbacks(this) } catch (_: Throwable) {}
            live.remove(act)
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    }
}
