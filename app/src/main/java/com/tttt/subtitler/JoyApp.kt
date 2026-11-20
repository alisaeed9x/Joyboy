package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.WeakHashMap

/**
 * (v878) الـApplication بتاع Joyboy — عادي من غير Hilt. (v869-v877 كان بيمتد من PlayTubeBaseApp؛ اتشال مع PlayTube.)
 * (v920) MeeTube هو البرنامج الأساسي: بيتشغّل هنا فورًا في onCreate (قبل كده كان Lazy أول ما تفتحه من بوابة يوتيوب)
 * عشان شاشة الدخول (لانشر) هي SplashActivity بتاعته فلازم يكون جاهز قبل أي شاشة.
 */
class JoyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { com.tttt.subtitler.mtui.ThemeMode.init(this) } catch (_: Throwable) {}   // (v1109 Y6b) الثيم بقى في app (كان بيتعمله init جوه MobileMainApplication.boot)
        // (v920) MeeTube هو الأساس: تهيئة فورية — اللانشر هو SplashActivity بتاعته
        try {
            // (v1148) تهيئة محرك MediaServiceCore (MtEngine) — الاحتياطي القديم MobileMainApplication اتشال مع :mobile
            com.tttt.subtitler.mtui.MtEngine.boot(this)
        } catch (e: Throwable) {
            try { LogStore.err("JoyApp:mtengine", e) } catch (_: Throwable) {}
        }
        try { com.tttt.subtitler.mtui.PlayerTrace.sink = com.tttt.subtitler.mtui.PlayerTrace.Sink { l -> try { LogStore.add(l) } catch (_: Throwable) {} } } catch (_: Throwable) {}   // (v952)
        try { com.tttt.subtitler.mtui.PlayerTrace.install(this) } catch (_: Throwable) {}   // (v1110 Y6c) كان بيتعمل جوه MobileMainApplication.onCreate (النسخة القديمة)
        MtYt.app = this
        NavOrigin.install(this)   // (v1006) آخر صفحة — للرجوع من الفيديو
        NavSnap.install(this)   // (v923) انتقال موحّد بين الشاشات (لقطة + فيد)
        MtFeed.install(this)   // (v1105 Y5) كان جوه MtTvRoute: متابعة الشاشة الظاهرة لطلب دخول يوتيوب من التلفزيون
        try { MtSkin.install(this) } catch (_: Throwable) {}   // (v897) الجلاس في البرنامج كله: خلفية متحركة + مسح الخلفيات الصلبة
        try { MtSmooth.install(this) } catch (_: Throwable) {}   // (v988) فتح الفريمات لأعلى معدل تحديث
        try { MobileDataGuard.install(this) } catch (_: Throwable) {}   // (v1010) تحذير داتا الموبايل في كل الشاشات
        try { PermAsk.install(this) } catch (_: Throwable) {}   // (v1010) صلاحيات بتتطلب وقت الحاجة بس
        followMeeTubeTheme()
    }

    /**
     * (v907) الثيم مصدره MeeTube. لو اتغيّر (من إعدادات MeeTube وانت جواه) وانت رجعت لأي شاشة Joyboy، الشاشة بتتبني من جديد بالألوان الجديدة
     * — من غير ما كل شاشة تراقب الثيم لوحدها. (المشغّل ليه فحصه الخاص.)
     */
    private fun followMeeTubeTheme() {
        val seen = WeakHashMap<Activity, String>()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                try {
                    if (!a.javaClass.name.startsWith("com.tttt.subtitler")) return
                    val cur = Themes.current().id
                    val old = seen[a]
                    seen[a] = cur
                    if (old != null && old != cur) a.recreate()
                } catch (_: Throwable) {}
            }
            // (v908) جانب فاتح/داكن من إعداد MeeTube (ThemeMode) قبل ما أي شاشة Joyboy تتبني — نفس اللي MeeTube بيعمله لشاشاته (أندرويد 10+)
            override fun onActivityPreCreated(a: Activity, b: Bundle?) {
                try { if (a.javaClass.name.startsWith("com.tttt.subtitler")) com.tttt.subtitler.mtui.ThemeMode.syncResources(a, com.tttt.subtitler.mtui.ThemeMode.desiredNight(a)) } catch (_: Throwable) {}
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

}
