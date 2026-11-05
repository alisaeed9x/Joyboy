package com.tttt.subtitler.phonehome

import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

// (v772) من غير Material/AppCompat: ألوان السطح ثابتة حسب وضع النظام (فاتح/داكن)
fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
// (v773) لو فيه ثيم بالذكاء شغّال، الدرج بياخد ألوانه (والنص بيتحدد بالتباين مع لون الدرج)
fun Context.surfaceColor(): Int = HomeThemeStore.cur?.drawer ?: if (isNight()) 0xFF1F1F24.toInt() else 0xFFFBF8FD.toInt()
fun Context.onSurfaceColor(): Int {
    val t = HomeThemeStore.cur
    if (t != null) return if (ColorUtils.calculateLuminance(t.drawer) > .5) 0xFF1B1B1F.toInt() else 0xFFE6E1E6.toInt()
    return if (isNight()) 0xFFE6E1E6.toInt() else 0xFF1B1B1F.toInt()
}
fun Context.surfaceVariantColor(): Int {
    val t = HomeThemeStore.cur
    if (t != null) return ColorUtils.blendARGB(t.drawer, onSurfaceColor(), .12f)
    return if (isNight()) 0xFF3A3A42.toInt() else 0xFFE8E0EB.toInt()
}

fun View.springTo(
    prop: DynamicAnimation.ViewProperty, target: Float,
    stiffness: Float = SpringForce.STIFFNESS_MEDIUM, damping: Float = 0.7f
) {
    SpringAnimation(this, prop).setSpring(
        SpringForce(target).setStiffness(stiffness).setDampingRatio(damping)
    ).start()
}

/** Xiaomi-style press: icon shrinks with spring, bounces back on release. */
fun View.pressEffect() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.springTo(DynamicAnimation.SCALE_X, .86f, SpringForce.STIFFNESS_HIGH, .6f)
                v.springTo(DynamicAnimation.SCALE_Y, .86f, SpringForce.STIFFNESS_HIGH, .6f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.springTo(DynamicAnimation.SCALE_X, 1f, SpringForce.STIFFNESS_MEDIUM, .45f)
                v.springTo(DynamicAnimation.SCALE_Y, 1f, SpringForce.STIFFNESS_MEDIUM, .45f)
            }
        }
        false
    }
}

/** Container that steals vertical swipe-up gestures (to open the app drawer). */
class SwipeFrame(ctx: Context) : FrameLayout(ctx) {
    var onDrag: ((Float) -> Unit)? = null
    var onEnd: ((Float, Float) -> Unit)? = null
    var swipeEnabled = true
    private var sx = 0f
    private var sy = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt!!.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - sx; val dy = e.y - sy
                if (dy < -slop && abs(dy) > abs(dx) * 1.5f) { dragging = true; sy = e.y }
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return false
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDrag?.invoke(e.y - sy)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                onEnd?.invoke(e.y - sy, vt?.yVelocity ?: 0f)
                vt?.recycle(); vt = null; dragging = false
            }
        }
        return true
    }
}
