package com.tttt.subtitler

import android.content.Intent

/**
 * (v973) تبديل التبويبات الرئيسية (ميديا · يوتيوب · متصفح · ملفات) من غير ما الشاشة القديمة تتقفل ولا الجديدة تتبني من الصفر:
 * كل تبويب Activity فاضلة شغّالة في الخلفية، والدوسة على الشريط بتجيب الموجودة قدّام (REORDER_TO_FRONT) بدل ما تبني نسخة جديدة
 * (قبل كده: startActivity + finish ← بناء كامل لكل دوسة = قطع/تهنيج). صفحة المتصفح تفضل محمّلة، وفيد يوتيوب يفضل زي ما هو.
 */
object TabNav {
    const val F = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP

    /** تبويب Main (videos/music/tasks/settings) المطلوب — لو onNewIntent ما اتناداش (REORDER على بعض الأجهزة) Main.onResume بينفّذه */
    @Volatile var pendingMain: String? = null

    fun front(i: Intent): Intent = i.addFlags(F)

    fun main(ctx: android.content.Context, tab: String): Intent {
        pendingMain = tab
        return Intent(ctx, MainActivity::class.java).addFlags(F).putExtra("tab", tab)
    }
}
