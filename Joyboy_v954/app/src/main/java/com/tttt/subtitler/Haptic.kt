package com.tttt.subtitler

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * (v810) اهتزاز لكل زرار في الريموت. بيستخدم الـVibrator مباشرة (مش بيتأثر بإعداد «اهتزاز اللمس» في النظام اللي كان بيخلّي performHapticFeedback ما يهتزّش)،
 * وبيرجع لـperformHapticFeedback لو الجهاز مفيهوش اهتزاز. فيه حماية من الاهتزاز المزدوج (نفس الدوسة).
 */
object Haptic {
    private var last = 0L
    @Volatile private var vib: Vibrator? = null

    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): Vibrator? {
        vib?.let { return it }
        val v = try {
            if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            else c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Throwable) { null }
        vib = v
        return v
    }

    @Suppress("DEPRECATION")
    fun tap(v: View, long: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (now - last < 35L) return
        last = now
        try {
            val vb = vibrator(v.context.applicationContext)
            if (vb != null && vb.hasVibrator()) {
                val ms = if (long) 45L else 20L
                if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else vb.vibrate(ms)
                return
            }
        } catch (_: Throwable) { }
        try { v.performHapticFeedback(if (long) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) { }
    }
}
