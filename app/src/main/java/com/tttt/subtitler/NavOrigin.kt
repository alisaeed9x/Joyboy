package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * (v1006) «آخر صفحة كنت فيها» — عشان الخروج من أي فيديو يرجّعك للصفحة اللي فتحته منها بالظبط
 * (فيديوهات · موسيقى · مهام · إعدادات · يوتيوب · متصفح · ملفات)، مش لأي صفحة صادف إنها فوق في الستاك.
 * MtOrigin.record() بياخد اللقطة دي لحظة فتح الفيديو (الصفحة لسه هي اللي قدّام).
 */
object NavOrigin : Application.ActivityLifecycleCallbacks {
    @Volatile private var page = ""
    @Volatile private var tab = "videos"

    fun last(): String = page

    /** MainActivity بتنده ده كل ما التبويب يتغيّر: 0 فيديوهات · 1 موسيقى · 5 مهام · 7 إعدادات */
    fun mainTab(i: Int) {
        tab = when (i) { 1 -> "music"; 5 -> "tasks"; 7 -> "settings"; else -> "videos" }
        page = tab
    }

    fun install(app: Application) { try { app.registerActivityLifecycleCallbacks(this) } catch (_: Throwable) {} }

    override fun onActivityResumed(a: Activity) {
        try {
            when (a.javaClass.simpleName) {
                "MobileBrowseActivity" -> page = "yt"
                "BrowserActivity" -> page = "browser"
                "FileManagerActivity", "FileTransferActivity" -> page = "files"
                "MainActivity" -> page = tab
            }
        } catch (_: Throwable) {}
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
