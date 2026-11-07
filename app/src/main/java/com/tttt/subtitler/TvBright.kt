package com.tttt.subtitler

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * (v817) سطوع التلفزيون من سلايدر الريموت (بيشتغل على التلفزيون نفسه، بيتنفّذ على الـUI thread):
 *  1) سطوع النظام الحقيقي: Settings.System.SCREEN_BRIGHTNESS (يد manual) — محتاج إذن «تعديل إعدادات النظام».
 *  2) طبقة تعتيم برمجية فوق الشاشة (احتياطي/إضافي): بتغمّق بس، مش إضاءة خلفية حقيقية. بتشتغل لو الإذن ناقص
 *     (وإذن العرض فوق التطبيقات موجود) أو لو اتفعّلت يدوي من زرار 🌙 في الريموت (Cfg tv_bri_soft).
 * الحالة بترجع للموبايل في /ping: bri (0..100) · brim = sys / soft / sys+soft / none
 */
object TvBright {
    private var layer: View? = null
    @Volatile var mode = "sys"; private set

    private fun canWrite(ctx: Context) = Build.VERSION.SDK_INT < 23 || try { Settings.System.canWrite(ctx) } catch (_: Throwable) { false }
    private fun canOverlay(ctx: Context) = Build.VERSION.SDK_INT < 23 || try { Settings.canDrawOverlays(ctx) } catch (_: Throwable) { false }

    fun softOn(): Boolean = Cfg.bool("tv_bri_soft", false)

    /** السطوع الحالي 0..100 (سطوع النظام، ولو التعتيم البرمجي شغّال بنطرح منه) */
    fun get(ctx: Context): Int = try {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255)
        val sys = Math.round(raw * 100f / 255f)
        val soft = Cfg.int("tv_bri_soft_pct", 100).coerceIn(0, 100)
        if (layer != null) minOf(sys, soft) else sys
    } catch (_: Throwable) { -1 }

    /** @return الوضع اللي اتطبّق: sys / soft / sys+soft / none (مفيش إذن خالص) */
    fun set(ctx: Context, pct: Int): String {
        val p = pct.coerceIn(0, 100)
        var sys = false
        if (canWrite(ctx)) {
            try {
                val cr = ctx.contentResolver
                if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                val raw = Math.round(p * 2.55f).coerceIn(1, 255)   // 0 على بعض الأجهزة = شاشة مطفية: نبدأ من 1
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, raw)
                sys = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1) == raw
            } catch (e: Throwable) { LogStore.err("TvBright:sys", e) }
        }
        val wantSoft = softOn() || (!sys && canOverlay(ctx))
        var soft = false
        if (wantSoft) { Cfg.put("tv_bri_soft_pct", p.toString()); soft = applyLayer(ctx, p) } else dropLayer(ctx)
        mode = when { sys && soft -> "sys+soft"; sys -> "sys"; soft -> "soft"; else -> "none" }
        return mode
    }

    /** زرار 🌙: تشغيل/إيقاف طبقة التعتيم البرمجية الإضافية */
    fun setSoft(ctx: Context, on: Boolean) {
        Cfg.put("tv_bri_soft", if (on) "1" else "0")
        if (on) set(ctx, Cfg.int("tv_bri_soft_pct", get(ctx).coerceAtLeast(0).let { if (it < 0) 100 else it }))
        else { dropLayer(ctx); mode = if (canWrite(ctx)) "sys" else "none" }
    }

    private fun applyLayer(ctx: Context, pct: Int): Boolean {
        if (!canOverlay(ctx)) return false
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val a = ((100 - pct) / 100f * 0.88f).coerceIn(0f, 0.88f)   // أقصى تعتيم 88% عشان الشاشة ما تبقاش سودا خالص
            if (a <= 0.005f) { dropLayer(ctx); return true }
            val v = layer ?: View(ctx.applicationContext).also { nv ->
                nv.setBackgroundColor(Color.BLACK)
                val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
                val lp = WindowManager.LayoutParams(-1, -1, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
                wm.addView(nv, lp); layer = nv
            }
            v.alpha = a
            true
        } catch (e: Throwable) { LogStore.err("TvBright:layer", e); layer = null; false }
    }

    private fun dropLayer(ctx: Context) {
        val v = layer ?: return
        layer = null
        try { (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
    }
}
