package com.vanilla.tvlauncher

import android.content.Context
import android.media.AudioManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

/** مساعدات واجهة برمجية (Views) — مقاسات كبيرة للتلفزيون. */
/** motion: 0 بدون حركة · 1 خفيفة · 2 كاملة (FR: تعطيل المؤثرات) — fontScale: تكبير الخطوط للقراءة من بعيد */
class Ui(private val ctx: Context, val th: Theme, val motion: Int = 2, val fontScale: Float = 1f, val sound: Boolean = false, val fontFamily: String = "") {
    private val base: Typeface = if (fontFamily.isEmpty()) Typeface.DEFAULT else (try { Typeface.create(fontFamily, Typeface.NORMAL) } catch (_: Throwable) { Typeface.DEFAULT })
    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun box(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(3), stroke)
    }

    fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s; textSize = sp * fontScale; setTextColor(color)
        setTypeface(base, if (bold) Typeface.BOLD else Typeface.NORMAL)   // P15: الخط المختار (الافتراضي = خط النظام)
    }

    /** عنصر قابل للتركيز بالريموت: إطار + تكبير 1.05 عند التركيز (FR-NAV-2) */
    fun focusable(v: View, radiusDp: Int = 14, onClick: () -> Unit): View {
        v.isFocusable = true; v.isClickable = true
        v.background = box(th.surface, th.border, radiusDp)
        v.setOnClickListener { onClick() }
        v.setOnFocusChangeListener { x, f ->
            if (f && sound) try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_DOWN) } catch (_: Throwable) { }   // اختياري (nav_sound)
            x.background = box(if (f) th.card else th.surface, if (f) th.primary else th.border, radiusDp)
            val to = if (!f) 1f else when (motion) { 0 -> 1f; 1 -> 1.02f; else -> 1.05f }
            if (motion == 0) { x.scaleX = to; x.scaleY = to } else x.animate().scaleX(to).scaleY(to).setDuration(if (motion == 1) 80L else 120L).start()
        }
        return v
    }
}
