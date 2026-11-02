package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.EditText

/**
 * (v486) اختصار طوارئ في كل شاشات البرنامج (اللانشر · المشغّل · المتصفح · الملفات · يوتيوب/الإعدادات):
 * دوسة مستمرة 5 ثواني على زرار 0 في الريموت = شاشة إلغاء تثبيت Subtitler (النظام بيطلب تأكيد).
 * الزرار 0 بيفضل يشتغل عادي (مش بنبلع الضغطة)، والعدّاد بيتلغي أول ما تسيب الزرار. مش بيشتغل وانت بتكتب في خانة كتابة.
 */
object TvKill {
    private const val HOLD_MS = 5000L
    private val h = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    private fun cancel() { pending?.let { h.removeCallbacks(it) }; pending = null }

    /** ينادى أول سطر في dispatchKeyEvent. مش بيبلع المفتاح أبدًا */
    fun watch(a: Activity, ev: KeyEvent) {
        if (ev.keyCode != KeyEvent.KEYCODE_0 && ev.keyCode != KeyEvent.KEYCODE_NUMPAD_0) return
        if (ev.action == KeyEvent.ACTION_UP) { cancel(); return }
        if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount != 0) return
        if (a.currentFocus is EditText) return
        cancel()
        val r = Runnable {
            pending = null
            try {
                if (a.isFinishing || a.isDestroyed) return@Runnable
                Notice.show(a, "🗑 إلغاء تثبيت Subtitler…", 1500L)
                uninstall(a)
            } catch (e: Throwable) { try { LogStore.err("TvKill", e) } catch (_: Throwable) { } }
        }
        pending = r
        h.postDelayed(r, HOLD_MS)
    }

    fun uninstall(a: Activity) {
        val pkg = "package:" + a.packageName
        for (i in listOf(Intent(Intent.ACTION_DELETE, Uri.parse(pkg)), Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse(pkg)))) {
            try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) { }
        }
        Notice.show(a, "⚠ ما قدرتش أفتح شاشة إلغاء التثبيت", 2400L)
    }
}
