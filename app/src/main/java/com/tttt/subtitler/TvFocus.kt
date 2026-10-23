package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.util.DisplayMetrics
import android.view.ViewTreeObserver
import android.view.Window
import android.webkit.WebView
import android.widget.PopupWindow
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap

/**
 * (v477) محرك تركيز الريموت — مرحلة 1 من تحويل Subtitler لتلفزيون.
 * شغّال على التلفزيون بس (DeviceUi.isTv): الموبايل والتابلت ما بيتأثروش بأي سطر هنا.
 * بيرسم حلقة تركيز واضحة + تكبير خفيف على أي عنصر ياخد التركيز بالأسهم، في أي شاشة بتستدعي attach().
 * ملحوظة: الدايلوجات (GAlert) نافذة تانية، فمش مشمولة هنا (مرحلة لاحقة).
 */
object TvFocus {
    private val saved = WeakHashMap<View, Drawable?>()

    fun on(a: Activity): Boolean = try { DeviceUi.isTv(a) } catch (_: Throwable) { false }

    fun onCtx(c: Context): Boolean = try { DeviceUi.isTv(c) } catch (_: Throwable) { false }

    fun attach(act: Activity, accent: Int) {
        if (!on(act)) return
        listen(act.window.decorView.viewTreeObserver, accent, act.resources.displayMetrics)
    }

    private fun listen(vto: ViewTreeObserver, accent: Int, dm: DisplayMetrics) {
        vto.addOnGlobalFocusChangeListener { old, new ->
            if (old != null) unmark(old)
            if (new != null) mark(new, accent, dm)
        }
    }

    /** (v477 م3) نافذة تانية (دايلوج): نفس حلقة التركيز + أول عنصر ياخد التركيز */
    fun attachWindow(ctx: Context, w: Window?, accent: Int) {
        if (w == null || !onCtx(ctx)) return
        listen(w.decorView.viewTreeObserver, accent, ctx.resources.displayMetrics)
        firstFocus(w.decorView)
    }

    /** (v480 م7) أول تركيز: أول عنصر قابل للتركيز مش «✕/→/إغلاق» (عشان OK على أول فتح ما يقفلش الدايلوج) */
    fun firstFocus(v: View) {
        v.post {
            try {
                if (v.findFocus() != null) return@post
                val list = ArrayList<View>()
                v.addFocusables(list, View.FOCUS_DOWN, View.FOCUSABLES_ALL)
                val pick = list.firstOrNull { it.isShown && !closeLike(it) } ?: list.firstOrNull { it.isShown }
                (pick ?: v).requestFocus()
            } catch (_: Throwable) {}
        }
    }

    private fun closeLike(x: View): Boolean {
        val t = (x as? TextView)?.text?.toString()?.trim() ?: return false
        return t.startsWith("✕") || t.startsWith("✖") || t == "→" || t == "×"
    }

    /** (v480 م7) تركيز على أول عنصر جوه حاوية (قسم إعدادات اتفتح مثلًا) */
    fun focusIn(v: View) { v.post { try { v.requestFocus() } catch (_: Throwable) {} } }

    /** (v480 م7) دايلوج عادي (Dialog مش GDialog): حلقة التركيز + أول تركيز. بيتنده قبل show() */
    fun attachDialog(ctx: Context, d: android.app.Dialog, accent: Int) {
        try { attachWindow(ctx, d.window, accent) } catch (_: Throwable) {}
    }

    /** (v477 م3) بديل PopupWindow(...) على التلفزيون: التركيز بالريموت جوه القايمة المنبثقة */
    fun pop(ctx: Context, v: View, w: Int, h: Int, focusable: Boolean): PopupWindow {
        val pw = PopupWindow(v, w, h, focusable)
        if (focusable && onCtx(ctx)) { listen(v.viewTreeObserver, Glass.theme().primary, ctx.resources.displayMetrics); firstFocus(v) }
        return pw
    }

    private fun isContainer(v: View) = v is AbsListView || v is ScrollView || v is HorizontalScrollView || v is WebView

    private fun mark(v: View, accent: Int, dm: DisplayMetrics) {
        if (isContainer(v)) return   // الشبكات/القوايم بترسم تحديدها بنفسها (selector)
        try {
            val d = dm.density
            if (!saved.containsKey(v)) saved[v] = v.foreground
            v.foreground = GradientDrawable().apply { setColor(0x1AFFFFFF); setStroke((3 * d).toInt(), accent); cornerRadius = 10 * d }
            val big = v.width > dm.widthPixels * 0.55f || v.height > dm.heightPixels * 0.45f
            if (!big && v.width > 0) {
                (v.parent as? ViewGroup)?.clipChildren = false
                v.animate().scaleX(1.05f).scaleY(1.05f).translationZ(8 * d).setDuration(Skins.dur(120L)).start()
            }
        } catch (_: Throwable) {}
    }

    private fun unmark(v: View) {
        try {
            if (saved.containsKey(v)) { v.foreground = saved.remove(v) }
            v.animate().cancel()
            v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(Skins.dur(100L)).start()
        } catch (_: Throwable) {}
    }

    /** زر Menu على الريموت = نفس الدوسة الطويلة (قوايم العنصر). الشبكات بتتعامل معاه بنفسها. */
    fun handleKey(act: Activity, ev: KeyEvent): Boolean {
        if (!on(act) || ev.action != KeyEvent.ACTION_DOWN || ev.keyCode != KeyEvent.KEYCODE_MENU) return false
        val f = act.currentFocus ?: return false
        if (f is AbsListView || !f.isLongClickable) return false
        return f.performLongClick()
    }

    /** رجوع للشاشة الرئيسية للتلفزيون (TvActivity) وقفل الشاشة الحالية */
    fun goHome(act: Activity): Boolean = try {
        act.startActivity(Intent(act, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        act.finish(); true
    } catch (_: Throwable) { false }
}
