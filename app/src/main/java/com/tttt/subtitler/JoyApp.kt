package com.tttt.subtitler

import android.app.Application
import com.liskovsoft.sharedutils.prefs.GlobalPreferences

/**
 * (v878) الـApplication بتاع Joyboy — عادي من غير Hilt.
 * (v1072) MeeTube اتشال خالص: مفيش تهيئة MeeTube ولا لانشر بتاعه ولا متابعة ثيمه ولا استعادة PiP بتاعته. اللي فاضل تهيئة مكتبات بيانات يوتيوب (GlobalPreferences) عشان الصفحة الرئيسية والاشتراكات وتسجيل الدخول.
 */
class JoyApp : Application() {
    companion object {
        @Volatile var app: JoyApp? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        // (v1072) تهيئة مكتبات بيانات يوتيوب (الحساب + التوكن): أول استدعاء بيشغّل الـcallbacks المسجلة (تهيئة مدير الحساب + الهيدرات) في خيوط خلفية
        try { GlobalPreferences.instance(this) } catch (_: Throwable) {}
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
