package com.tttt.subtitler

import android.app.Application

/**
 * (v878) الـApplication بتاع Joyboy — عادي من غير Hilt.
 * (v1086) كود يوتيوب/MeeTube/Skipii اتشال بالكامل — بدايله YoutubeSlot.kt (YOUTUBE_SLOT).
 */
class JoyApp : Application() {
    companion object {
        @Volatile var app: JoyApp? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        MtYt.app = this
        NavOrigin.install(this)   // (v1006) آخر صفحة — للرجوع من الفيديو
        NavSnap.install(this)   // (v923) انتقال موحّد بين الشاشات (لقطة + فيد)
        MtFeed.install(this)   // (v1072) تتبّع الشاشة الظاهرة + طلب تسجيل الدخول المعلق (كان في MtTvRoute)
        try { MtSkin.install(this) } catch (_: Throwable) {}   // (v897) الجلاس في البرنامج كله: خلفية متحركة + مسح الخلفيات الصلبة
        try { MtSmooth.install(this) } catch (_: Throwable) {}   // (v988) فتح الفريمات لأعلى معدل تحديث
        try { MobileDataGuard.install(this) } catch (_: Throwable) {}   // (v1010) تحذير داتا الموبايل في كل الشاشات
        try { PermAsk.install(this) } catch (_: Throwable) {}   // (v1010) صلاحيات بتتطلب وقت الحاجة بس
    }
}

