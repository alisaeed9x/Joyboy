package com.tttt.joyboylauncher

import android.content.Context
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.themeColor(attr: Int, def: Int): Int {
    val tv = TypedValue()
    return if (theme.resolveAttribute(attr, tv, true)) tv.data else def
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
