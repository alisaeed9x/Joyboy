package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
import java.lang.ref.WeakReference

/**
 * (v928) توجيه فيديو MeeTube (موبايل/تابلت): لو «وضع التلفزيون» شغّال وفي تلفزيون مقترن، دوسة على أي فيديو في MeeTube
 * بتطلّع نافذة «تشغّله فين؟ 📺 تلفزيون / 📱 موبايل» (نفس PlayTarget.askWhere).
 *  • تلفزيون ← PlayTarget.force (الفيديو بيتفتح على التلفزيون في مشغّل MeeTube، والموبايل بيبقى ريموت).
 *  • موبايل  ← مشغّل Joyboy (v1069: مشغّل MeeTube اتشال خالص — دوسة الفيديو بتفتح Player.kt على طول).
 * مفيش تلفزيون مقترن أو وضع التلفزيون مقفول ← الفيديو بيفتح في مشغّل Joyboy على طول من غير أي سؤال.
 * الربط: PlaybackPresenter.setOpenInterceptor (مودول :common مايعرفش app) — بيتركّب من JoyApp.onCreate.
 */
object MtTvRoute : PlaybackPresenter.OpenInterceptor {
    @Volatile private var bypass = false
    @Volatile private var top: WeakReference<Activity>? = null
    @Volatile private var res: WeakReference<Activity>? = null   // (v929) الشاشة الظاهرة دلوقتي (null = البرنامج في الخلفية)
    private var installed = false

    fun resumed(): Activity? = res?.get()

    fun install(app: Application) {
        try {
            if (installed) return
            installed = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) { top = WeakReference(a); res = WeakReference(a); try { MtFeed.onResumed(a) } catch (_: Throwable) {} }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) { if (res?.get() === a) res = null }
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
            PlaybackPresenter.setOpenInterceptor(this)
        } catch (e: Throwable) { LogStore.err("MtTvRoute:install", e) }
    }

    /** يفتح الفيديو في MeeTube من غير ما يعدّي على نافذة «تشغّله فين؟» (نداء داخلي من Joyboy اللي سأل قبل كده). */
    fun <T> bypassed(f: () -> T): T {
        bypass = true
        try { return f() } finally { bypass = false }
    }

    override fun intercept(context: Context?, video: Video?): Boolean {
        try {
            if (video == null || bypass || context == null) return false
            if (DeviceUi.isTv(context)) return false
            Cfg.ensure(context.applicationContext)
            val id = video.videoId ?: return false
            if (id.length != 11 || video.isLocal()) return false
            try { RemoteClient.init(context) } catch (_: Throwable) {}
            val act = (context as? Activity) ?: top?.get() ?: return false
            if (act.isFinishing || act.isDestroyed) return false
            val title = video.title?.toString() ?: ""
            val start = if (video.pendingPosMs > 0L) video.pendingPosMs else 0L
            // (v931) السؤال «تشغّله فين؟» بيظهر لو «وضع التلفزيون» شغّال أو الموبايل متصل بالتلفزيون فعلًا دلوقتي (قبل كده كان بيحتاج وضع التلفزيون بس، فمع اتصال من غير الوضع مكنش بيسأل)
            if ((PlayTarget.enabled() || RemoteClient.current != null) && RemoteClient.lastTv() != null) {
                PlayTarget.askWhere(act, title,
                    { if (!PlayTarget.force(act, MediaSpec(ytid = id, title = title, startMs = start))) Notice.show(act, "📺 ما اتبعتش للتلفزيون", 2600L) },
                    { try { MeeTubeLink.openJoyboyPlayer(act, id, title, start) } catch (e: Throwable) { LogStore.err("MtTvRoute:phone", e) } })
                return true
            }
            // (v1069) مشغّل MeeTube اتشال خالص: دوسة الفيديو في MeeTube بتفتح مشغّل Joyboy على طول (من غير صفحة التعليقات ولا مشغّل MeeTube)
            return try { MeeTubeLink.openJoyboyPlayer(act, id, title, start, false, video.incognito) } catch (e: Throwable) { LogStore.err("MtTvRoute:joyboy", e); false }
        } catch (e: Throwable) {
            LogStore.err("MtTvRoute:intercept", e)
            return false
        }
    }
}
