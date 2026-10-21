package com.tttt.subtitler

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * (v270) صوت النظام (STREAM_MUSIC) هو نفسه سلايدر الصوت في المشغّلين: 0..100% = زرار الفوليوم بتاع الموبايل،
 * وفوق 100% تضخيم (LoudnessEnhancer) والنظام على الآخر. زرار الفوليوم أب/داون بيحرّك السلايدر والعكس.
 */
object SysVol {
    private fun am(ctx: Context) = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** صوت النظام 0..100 */
    fun get(ctx: Context): Int = try {
        val a = am(ctx); val mx = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        Math.round(a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / mx)
    } catch (_: Throwable) { 100 }

    fun set(ctx: Context, pct: Int) {
        try {
            val a = am(ctx); val mx = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val idx = Math.round(pct.coerceIn(0, 100) / 100f * mx)
            if (a.getStreamVolume(AudioManager.STREAM_MUSIC) != idx) a.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        } catch (_: Throwable) {}
    }

    class Watch(private val ctx: Context, private val obs: ContentObserver) {
        fun stop() { try { ctx.applicationContext.contentResolver.unregisterContentObserver(obs) } catch (_: Throwable) {} }
    }

    /** بيتنادى [onChange] (على خيط الواجهة) كل ما صوت النظام يتغيّر (زراري الفوليوم أو لوحة الإعدادات) */
    fun watch(ctx: Context, onChange: () -> Unit): Watch {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { try { onChange() } catch (_: Throwable) {} }
        }
        try { ctx.applicationContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs) } catch (_: Throwable) {}
        return Watch(ctx, obs)
    }
}
