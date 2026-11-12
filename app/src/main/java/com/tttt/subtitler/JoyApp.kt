package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.newtube.mobile.MobileMainApplication
import java.util.WeakHashMap

/**
 * (v878) الـApplication بتاع Joyboy — عادي من غير Hilt. (v869-v877 كان بيمتد من PlayTubeBaseApp؛ اتشال مع PlayTube.)
 * (v920) MeeTube هو البرنامج الأساسي: بيتشغّل هنا فورًا في onCreate (قبل كده كان Lazy أول ما تفتحه من بوابة يوتيوب)
 * عشان شاشة الدخول (لانشر) هي SplashActivity بتاعته فلازم يكون جاهز قبل أي شاشة.
 */
class JoyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // (v920) MeeTube هو الأساس: تهيئة فورية — اللانشر هو SplashActivity بتاعته
        try {
            MobileMainApplication.boot(this)
        } catch (e: Throwable) {
            try {
                LogStore.err("JoyApp:mtboot", e)
            } catch (_: Throwable) {
            }
        }
        MtYt.app = this
        NavSnap.install(this)   // (v923) انتقال موحّد بين الشاشات (لقطة + فيد)
        MeeTubeLink.bindBar()
        MtTvRoute.install(this)   // (v928) «تشغّله فين؟» لفيديوهات MeeTube لما وضع التلفزيون شغّال
        try { MtSkin.install(this) } catch (_: Throwable) {}   // (v897) الجلاس في البرنامج كله: خلفية متحركة + مسح الخلفيات الصلبة
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
                try { if (a.javaClass.name.startsWith("com.tttt.subtitler")) com.newtube.mobile.ui.common.ThemeMode.syncResources(a, com.newtube.mobile.ui.common.ThemeMode.desiredNight(a)) } catch (_: Throwable) {}
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); MeeTubeLink.onTrim(level) }
    override fun onLowMemory() { super.onLowMemory(); MeeTubeLink.onLow() }
}
