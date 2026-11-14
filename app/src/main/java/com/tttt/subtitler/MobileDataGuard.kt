package com.tttt.subtitler

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * (v1010) تحذير داتا الموبايل — في كل شاشات البرنامج (Joyboy + يوتيوب/MeeTube + المتصفح + المشغّل).
 * - لو الشبكة داتا موبايل (مش واي فاي) وإجمالي استهلاك التطبيق من لحظة بداية الداتا عدّى 5 ميجا:
 *   بيوقّف مؤقتًا (Pause) كل المشغّلات والتحميلات والترجمة بالخلفية، وبيطلّع إشعار كبير بحجم الشاشة.
 * - الأزرار: إعدادات الواي فاي (بتفتح إعدادات النظام، وأول ما الواي فاي يتوصّل البرنامج بيرجع لوحده ويكمّل) ·
 *   إكمال بالداتا (بيكمّل اللي كان شغّال) · خروج من البرنامج.
 * - «إكمال» بيسمح بالداتا لحد ما الداتا تنقطع أو الواي فاي يتوصّل؛ بعد كده الحماية بترجع تشتغل من الصفر.
 * - بيشتغل من غير أي إذن إضافي (الإشعار بيتحط جوه نافذة الشاشة المفتوحة، مش فوق التطبيقات).
 */
object MobileDataGuard {
    private const val LIMIT = 5L * 1024L * 1024L
    private val main = Handler(Looper.getMainLooper())
    private var app: Application? = null
    private var started = false
    private var resumed: WeakReference<Activity>? = null
    private var lastAct: WeakReference<Activity>? = null
    private var tv: Boolean? = null

    private var wasCell = false
    private var allowed = false
    private var tripped = false
    private var awaitWifi = false
    private var base = -1L

    private var overlay: View? = null
    private var overlayHost: Activity? = null
    private var pulse: ObjectAnimator? = null

    // اللي إحنا وقّفناه (عشان نكمّل اللي وقّفناه بس — مش اللي المستخدم واقفه بإيده)
    private var pausedMt = false
    private var pausedJoy = false
    private var pausedWeb = false
    private var pausedBg = false
    private var pausedTasks: List<TaskItem> = emptyList()

    fun install(a: Application) {
        if (started) return
        started = true
        app = a
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(x: Activity, b: Bundle?) {}
            override fun onActivityStarted(x: Activity) {}
            override fun onActivityResumed(x: Activity) { try { onResumed(x) } catch (_: Throwable) {} }
            override fun onActivityPaused(x: Activity) {
                try {
                    if (resumed?.get() === x) resumed = null
                    if (overlayHost === x) removeOverlayView()
                } catch (_: Throwable) {}
            }
            override fun onActivityStopped(x: Activity) {}
            override fun onActivitySaveInstanceState(x: Activity, b: Bundle) {}
            override fun onActivityDestroyed(x: Activity) { try { if (overlayHost === x) removeOverlayView() } catch (_: Throwable) {} }
        })
        main.postDelayed(object : Runnable {
            override fun run() {
                try { tick() } catch (e: Throwable) { try { LogStore.err("MobileDataGuard:tick", e) } catch (_: Throwable) {} }
                main.postDelayed(this, 1000L)
            }
        }, 2000L)
    }

    private fun onResumed(x: Activity) {
        resumed = WeakReference(x); lastAct = WeakReference(x)
        if (tv == null) tv = try { DeviceUi.isTv(x) } catch (_: Throwable) { false }
        if (tv == true) return
        if (tripped && !allowed) {
            if (overlay != null && overlayHost !== x) removeOverlayView()
            if (overlay == null && state(x) == 2) show(x)
        }
    }

    // ===================== حالة الشبكة =====================
    /** 0 = مفيش نت · 1 = واي فاي/إيثرنت · 2 = داتا موبايل (VPN: بنعتمد على «محدودة» من النظام) */
    private fun state(c: Context): Int = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val cap = if (n != null) cm.getNetworkCapabilities(n) else null
        if (cap == null || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 0
        else if (DataMeter.isCell(c)) 2 else 1
    } catch (_: Throwable) { 0 }

    private fun uidBytes(): Long {
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
        return if (rx < 0L || tx < 0L) -1L else rx + tx
    }

    private fun resetSpell() {
        wasCell = false; allowed = false; tripped = false; awaitWifi = false; base = -1L
        try { app?.let { NetGate.allowMobile(it, false) } } catch (_: Throwable) {}
    }

    private fun tick() {
        val a = app ?: return
        if (tv == true) return
        when (state(a)) {
            2 -> {
                if (!wasCell) { wasCell = true; allowed = false; tripped = false; base = uidBytes() }
                if (allowed) return
                if (!tripped) {
                    if (base < 0L) { base = uidBytes(); return }
                    val cur = uidBytes()
                    if (cur >= 0L && cur - base >= LIMIT) { tripped = true; pauseEverything() }
                }
                if (tripped) {
                    val act = resumed?.get()
                    if (act != null && overlay == null) show(act)
                }
            }
            1 -> {
                // واي فاي رجع: اقفل التحذير، كمّل اللي وقّفناه، وارجع للبرنامج لو كان المستخدم في إعدادات الواي فاي
                if (wasCell || tripped || overlay != null) {
                    val front = awaitWifi
                    resetSpell()
                    removeOverlayView()
                    resumeEverything()
                    if (front) bringToFront(a)
                }
            }
            else -> {}
        }
    }

    // ===================== الإيقاف المؤقت والاستكمال =====================
    private fun pauseEverything() {
        try { pausedMt = MtBar.pauseForGuard() } catch (_: Throwable) {}
        try { pausedJoy = PlayerActivity.pauseForGuard() } catch (_: Throwable) {}
        try { pausedWeb = WebMute.pauseMarked() } catch (_: Throwable) {}
        try {
            val run = TaskCenter.items.toList().filter { (it.state == 1 && !it.paused) || it.state == 0 }
            pausedTasks = run
            run.forEach { try { TaskCenter.pause(it, true) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        try { if (BgJobs.jobs.any { it.active && !it.paused }) { BgJobs.pauseAll(); pausedBg = true } } catch (_: Throwable) {}
    }

    private fun resumeEverything() {
        val a = app
        try { if (pausedMt) MtBar.resumeForGuard() } catch (_: Throwable) {}
        try { if (pausedJoy) PlayerActivity.resumeForGuard() } catch (_: Throwable) {}
        try { if (pausedWeb) WebMute.resumeMarked() } catch (_: Throwable) {}
        try {
            pausedTasks.forEach { t -> try { if (t.state == 6 || (t.state == 1 && t.paused) || t.state == 3 || t.state == 5) TaskCenter.pause(t, false) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        try { if (pausedBg && a != null) BgJobs.resumeAll(a) } catch (_: Throwable) {}
        pausedMt = false; pausedJoy = false; pausedWeb = false; pausedBg = false; pausedTasks = emptyList()
    }

    // ===================== الرجوع للبرنامج =====================
    private fun bringToFront(a: Application) {
        try {
            val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.firstOrNull()?.moveToFront()
        } catch (_: Throwable) {}
        try {
            val act = lastAct?.get() ?: return
            if (act.isFinishing || act.isDestroyed) return
            act.startActivity(Intent(a, act.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Throwable) {}
    }

    // ===================== الإشعار الكبير =====================
    private fun removeOverlayView() {
        try { pulse?.cancel() } catch (_: Throwable) {}
        pulse = null
        val v = overlay; overlay = null; overlayHost = null
        try { (v?.parent as? ViewGroup)?.removeView(v) } catch (_: Throwable) {}
    }

    private fun fmt(b: Long): String = try { DataMeter.fmt(b) } catch (_: Throwable) { (b / 1048576L).toString() + " MB" }

    private fun rounded(color: Int, r: Float, stroke: Int = 0, sw: Int = 0): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = r; if (sw > 0) setStroke(sw, stroke) }

    @Suppress("DEPRECATION")
    private fun show(act: Activity) {
        if (overlay != null && overlayHost === act) return
        removeOverlayView()
        if (act.isFinishing || act.isDestroyed) return
        try { if (act.isInPictureInPictureMode) return } catch (_: Throwable) {}
        val decor = act.window?.decorView as? ViewGroup ?: return
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val used = try { (uidBytes() - base).coerceAtLeast(LIMIT) } catch (_: Throwable) { LIMIT }

        val root = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(244, 20, 8, 10))
            isClickable = true; isFocusable = true; isFocusableInTouchMode = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            elevation = dp(200).toFloat()
            setOnApplyWindowInsetsListener { v, i -> v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i }
            setOnKeyListener { _, k, _ -> k == KeyEvent.KEYCODE_BACK }   // الرجوع ما يقفلش التحذير
        }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(20), dp(26), dp(20))
        }
        val icon = TextView(act).apply {
            text = "⚠"; textSize = 64f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = rounded(Color.rgb(214, 40, 40), dp(60).toFloat(), Color.argb(90, 255, 255, 255), dp(3))
        }
        col.addView(icon, LinearLayout.LayoutParams(dp(120), dp(120)))
        pulse = ObjectAnimator.ofPropertyValuesHolder(icon,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)).apply {
            duration = 700L; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE; start()
        }
        col.addView(TextView(act).apply {
            text = "إنت على داتا الموبايل"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        col.addView(TextView(act).apply {
            text = "البرنامج استهلك " + fmt(used) + " من الداتا، ووقّفت كل التشغيل والتحميل مؤقتًا (Pause) لحد ما تختار."
            textSize = 15f; setTextColor(Color.argb(220, 255, 255, 255)); gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        col.addView(TextView(act).apply {
            text = "تكمّل بالداتا ولا تحوّل للواي فاي؟"
            textSize = 17f; setTextColor(Color.rgb(255, 196, 0)); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(22) })

        fun btn(label: String, bg: Int, fg: Int, stroke: Int, onClick: () -> Unit): TextView = TextView(act).apply {
            text = label; textSize = 17f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = rounded(bg, dp(16).toFloat(), stroke, if (stroke != 0) dp(2) else 0)
            setPadding(dp(16), dp(15), dp(16), dp(15)); isClickable = true
            setOnClickListener { try { onClick() } catch (e: Throwable) { try { LogStore.err("MobileDataGuard:btn", e) } catch (_: Throwable) {} } }
        }
        val lp = { LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } }
        col.addView(btn("📶  إعدادات الواي فاي", Color.rgb(33, 150, 243), Color.WHITE, 0) {
            awaitWifi = true
            try { act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            catch (_: Throwable) { try { act.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Throwable) {} }
        }, lp())
        col.addView(btn("▶  إكمال بالداتا", Color.argb(40, 255, 196, 0), Color.rgb(255, 196, 0), Color.rgb(255, 196, 0)) {
            allowed = true; tripped = false; awaitWifi = false
            try { app?.let { NetGate.allowMobile(it, true) } } catch (_: Throwable) {}
            removeOverlayView()
            resumeEverything()
        }, lp())
        col.addView(btn("⛔  خروج من البرنامج", Color.argb(40, 255, 82, 82), Color.rgb(255, 120, 120), Color.rgb(255, 82, 82)) {
            removeOverlayView()
            try { act.finishAndRemoveTask() } catch (_: Throwable) { try { act.finishAffinity() } catch (_: Throwable) {} }
            main.postDelayed({ try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {} }, 350L)
        }, lp())

        root.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        decor.addView(root, ViewGroup.LayoutParams(-1, -1))
        overlay = root; overlayHost = act
        root.requestFocus()
        root.requestApplyInsets()
    }
}
