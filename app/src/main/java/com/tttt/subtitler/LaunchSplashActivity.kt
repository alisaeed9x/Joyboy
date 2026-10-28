package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer

/**
 * (v619) بوابة الفتح الخفيفة: بتتفتح فورًا (من غير أي تحميل) وخلفية نافذتها صورة السبلاش، وبعد أول رسمة بتفتح MainActivity الثقيلة وتقفل نفسها.
 * كده أندرويد 12+ مابيفضلش على الشاشة البنية طول تحميل Main — الصورة ظاهرة والتحميل بيحصل وراها. (أيقونة اللانشر بتشاور هنا.)
 */
class LaunchSplashActivity : Activity() {
    private var started = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val h = Handler(Looper.getMainLooper())
        val ch = Choreographer.getInstance()
        // فريمين: نضمن إن الصورة اترسمت قبل ما نبدأ تحميل Main
        ch.postFrameCallback { ch.postFrameCallback { h.post { go() } } }
        h.postDelayed({ go() }, 500L)   // أمان لو الـChoreographer اتأخر
    }

    private fun go() {
        if (started) return
        started = true
        try {
            startActivity(Intent(this, MainActivity::class.java).apply { intent?.extras?.let { putExtras(it) } })
            overridePendingTransition(0, 0)
        } catch (_: Throwable) { }
        finish()
        overridePendingTransition(0, 0)
    }
}
