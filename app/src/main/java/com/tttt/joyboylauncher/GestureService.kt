package com.tttt.joyboylauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Full-screen gestures via accessibility overlays:
 *  - swipe up from the bottom edge  → Home
 *  - swipe up and hold              → Recents
 *  - swipe in from left/right edge  → Back (with arrow bubble)
 */
class GestureService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private val views = mutableListOf<View>()
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == "gestures") refresh() }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    private var currentPkg: String? = null
    private val launchable = HashMap<String, Boolean>()

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) {
            if (e.className?.toString() == MainActivity::class.java.name) currentPkg = null
            return
        }
        val ok = launchable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }
        if (!ok) return
        currentPkg = pkg
        RecentsStore.touch(prefs, pkg)
    }

    /** Snapshot the foreground app (Android 11+), then go Home; optionally open our Recents. */
    fun leaveApp(openRecents: Boolean) {
        val pkg = currentPkg
        val finish = {
            if (openRecents) RecentsStore.openRequested = true
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        if (Build.VERSION.SDK_INT >= 30 && pkg != null) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        try {
                            val hw = r.hardwareBuffer
                            val hwBmp = Bitmap.wrapHardwareBuffer(hw, r.colorSpace)
                            if (hwBmp != null) RecentsStore.saveThumb(this@GestureService, pkg, hwBmp.copy(Bitmap.Config.ARGB_8888, false))
                            hw.close()
                        } catch (_: Exception) {}
                        finish()
                    }
                    override fun onFailure(code: Int) { finish() }
                })
            } catch (_: Exception) { finish() }
        } else finish()
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        removeAll()
        super.onDestroy()
    }

    private fun refresh() {
        removeAll()
        if (prefs.getBoolean("gestures", true)) addEdges()
    }

    private fun removeAll() {
        if (!::wm.isInitialized) return
        views.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        views.clear()
    }

    private fun addEdges() {
        val h = (resources.displayMetrics.heightPixels * .6f).toInt()
        add(EdgeView(this, EdgeView.BOTTOM), WindowManager.LayoutParams.MATCH_PARENT, dp(24), Gravity.BOTTOM)
        add(EdgeView(this, EdgeView.LEFT), dp(14), h, Gravity.LEFT or Gravity.CENTER_VERTICAL)
        add(EdgeView(this, EdgeView.RIGHT), dp(14), h, Gravity.RIGHT or Gravity.CENTER_VERTICAL)
    }

    private fun add(v: View, w: Int, h: Int, g: Int) {
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = g }
        try { wm.addView(v, lp); views.add(v) } catch (_: Exception) {}
    }
}

class EdgeView(private val svc: GestureService, private val side: Int) : View(svc) {
    companion object { const val BOTTOM = 0; const val LEFT = 1; const val RIGHT = 2 }

    private var x0 = 0f
    private var y0 = 0f
    private var anchorY = 0f
    private var holdTriggered = false
    private var vt: VelocityTracker? = null
    private var bubble: BackBubble? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val screenW = svc.resources.displayMetrics.widthPixels

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }

    private val holdRun = Runnable {
        holdTriggered = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        svc.leaveApp(true)
    }

    override fun onDraw(c: Canvas) {
        if (side != BOTTOM) return
        val pw = svc.dp(110).toFloat(); val ph = svc.dp(4).toFloat()
        val l = (width - pw) / 2f; val t = height - svc.dp(8) - ph
        c.drawRoundRect(l - 1, t - 1, l + pw + 1, t + ph + 1, ph, ph, pillEdge)
        c.drawRoundRect(l, t, l + pw, t + ph, ph, ph, pill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; anchorY = e.rawY; holdTriggered = false
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
                if (side == BOTTOM) animate().alpha(.4f).setDuration(80).start() else showBubble(e.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (side == BOTTOM) {
                    val dy = y0 - e.rawY
                    if (abs(e.rawY - anchorY) > svc.dp(10)) {
                        anchorY = e.rawY
                        handler.removeCallbacks(holdRun)
                        if (dy > svc.dp(90) && !holdTriggered) handler.postDelayed(holdRun, 300)
                    }
                } else {
                    val dx = if (side == LEFT) e.rawX - x0 else x0 - e.rawX
                    updateBubble(dx, e.rawY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRun)
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (side == BOTTOM) {
                    animate().alpha(1f).setDuration(120).start()
                    val dy = y0 - e.rawY
                    if (up && !holdTriggered && (dy > svc.dp(60) || vy < -svc.dp(500)))
                        svc.leaveApp(false)
                } else {
                    val dx = if (side == LEFT) e.rawX - x0 else x0 - e.rawX
                    hideBubble()
                    if (up && dx > svc.dp(70)) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                }
            }
        }
        return true
    }

    private fun showBubble(rawY: Float) {
        val b = BackBubble(svc)
        val lp = WindowManager.LayoutParams(
            svc.dp(44), svc.dp(44), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (side == LEFT) -svc.dp(44) else screenW
            y = (rawY - svc.dp(22)).toInt()
        }
        try { wm.addView(b, lp); bubble = b; bubbleLp = lp } catch (_: Exception) {}
    }

    private fun updateBubble(dx: Float, rawY: Float) {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val p = (dx / svc.dp(70)).coerceIn(0f, 1f)
        lp.x = if (side == LEFT) (-svc.dp(44) + p * svc.dp(64)).toInt() else (screenW - p * svc.dp(64)).toInt()
        lp.y = (rawY - svc.dp(22)).toInt()
        b.setProgress(p)
        try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
    }

    private fun hideBubble() {
        val b = bubble ?: return
        bubble = null
        b.animate().alpha(0f).setDuration(120).withEndAction { try { wm.removeView(b) } catch (_: Exception) {} }.start()
    }
}

class BackBubble(c: Context) : View(c) {
    private var p = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = c.dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE
    }

    fun setProgress(v: Float) { p = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val armed = p >= 1f
        bg.color = if (armed) 0xF0333333.toInt() else 0xCC222222.toInt()
        c.drawCircle(cx, cy, width / 2f * (if (armed) 1f else .88f), bg)
        arrow.alpha = (80 + 175 * p).toInt()
        val s = width * .12f
        val path = Path().apply { moveTo(cx + s * .6f, cy - s * 1.5f); lineTo(cx - s * .8f, cy); lineTo(cx + s * .6f, cy + s * 1.5f) }
        c.drawPath(path, arrow)
    }
}
