package com.tttt.subtitler

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.decio.skipii.Account
import com.decio.skipii.AppLanguage
import com.decio.skipii.AudioOnly
import com.decio.skipii.AutoPip
import com.decio.skipii.Bells
import com.decio.skipii.CaptionSettings
import com.decio.skipii.CaptionTranslator
import com.decio.skipii.ChaptersStayOpen
import com.decio.skipii.FullscreenSwipes
import com.decio.skipii.FullscreenZoom
import com.decio.skipii.LiveControls
import com.decio.skipii.NewPipeDownloader
import com.decio.skipii.NewVideos
import com.decio.skipii.PlaybackSpeed
import com.decio.skipii.PlayerControls
import com.decio.skipii.QualityPreference
import com.decio.skipii.ResumeStore
import com.decio.skipii.SavedSession
import com.decio.skipii.SearchHistory
import com.decio.skipii.SeekStep
import com.decio.skipii.ShortsBank
import com.decio.skipii.ShortsSetting
import com.decio.skipii.SkipiiShorts
import com.decio.skipii.SponsorBlock
import com.decio.skipii.StreamCache
import com.decio.skipii.ThemeSettings
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

/**
 * (v878) الـApplication بتاع Joyboy — عادي من غير Hilt.
 * (v1072) MeeTube اتشال خالص: مفيش تهيئة MeeTube ولا لانشر بتاعه ولا متابعة ثيمه ولا استعادة PiP بتاعته. اللي فاضل تهيئة مكتبات بيانات يوتيوب (GlobalPreferences) عشان الصفحة الرئيسية والاشتراكات وتسجيل الدخول.
 */
class JoyApp : Application(), ImageLoaderFactory {
    companion object {
        @Volatile var app: JoyApp? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        // (v1080) Skipii's language holder: vendored code reads strings through it (str()/plural()).
        // It only reads the pick (LocaleManager on API 33+, else its own "language" prefs file); Joyboy never sets either, so the pick stays the phone's language and Joyboy's behavior is unchanged.
        AppLanguage.init(this)
        // (v1072) تهيئة مكتبات بيانات يوتيوب (الحساب + التوكن): أول استدعاء بيشغّل الـcallbacks المسجلة (تهيئة مدير الحساب + الهيدرات) في خيوط خلفية
        try { GlobalPreferences.instance(this) } catch (_: Throwable) {}
        MtYt.app = this
        // (v1080) Skipii singletons, re-homed from SkipiiApplication.onCreate (that class
        // is never instantiated: Joyboy's application is JoyApp). AppUpdate is deliberately
        // NOT wired: the Skipii self-update check is disabled (Updater.kt), Joyboy updates
        // stay on its own GhCi flow. NewPipe.init used to come from YtNp.init; YtNp is
        // deleted in v1080, so it moves here.
        try { com.decio.skipii.CrashLog.install(this) } catch (_: Throwable) {}
        SkipiiShorts.install(this)
        try { NewPipe.init(NewPipeDownloader.instance) } catch (_: Throwable) {}
        try { YoutubeStreamExtractor.setFetchIosClient(false) } catch (_: Throwable) {}
        try { ThemeSettings.init(this) } catch (_: Throwable) {}
        try { QualityPreference.init(this) } catch (_: Throwable) {}
        try { PlaybackSpeed.init(this) } catch (_: Throwable) {}
        try { AudioOnly.init(this) } catch (_: Throwable) {}
        try { PlayerControls.init(this) } catch (_: Throwable) {}
        try { FullscreenZoom.init(this) } catch (_: Throwable) {}
        try { FullscreenSwipes.init(this) } catch (_: Throwable) {}
        try { SeekStep.init(this) } catch (_: Throwable) {}
        try { ChaptersStayOpen.init(this) } catch (_: Throwable) {}
        try { AutoPip.init(this) } catch (_: Throwable) {}
        try { LiveControls.init(this) } catch (_: Throwable) {}
        try { CaptionSettings.init(this) } catch (_: Throwable) {}
        try { CaptionTranslator.refresh() } catch (_: Throwable) {}
        try { SearchHistory.init(this) } catch (_: Throwable) {}
        try { ResumeStore.init(this) } catch (_: Throwable) {}
        try { SavedSession.init(this) } catch (_: Throwable) {}
        try { Account.init(this) } catch (_: Throwable) {}
        try { Bells.init(this) } catch (_: Throwable) {}
        try { NewVideos.schedule(this) } catch (_: Throwable) {}
        try { SponsorBlock.init(this) } catch (_: Throwable) {}
        try { ShortsSetting.init(this) } catch (_: Throwable) {}
        try { ShortsBank.init(this) } catch (_: Throwable) {}
        try {
            getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    private var last: android.net.Network? = null
                    override fun onAvailable(network: android.net.Network) {
                        if (last != null && last != network) StreamCache.invalidateAll()
                        last = network
                    }
                },
            )
        } catch (_: Throwable) {}
        NavOrigin.install(this)   // (v1006) آخر صفحة — للرجوع من الفيديو
        NavSnap.install(this)   // (v923) انتقال موحّد بين الشاشات (لقطة + فيد)
        MtFeed.install(this)   // (v1072) تتبّع الشاشة الظاهرة + طلب تسجيل الدخول المعلق (كان في MtTvRoute)
        try { MtSkin.install(this) } catch (_: Throwable) {}   // (v897) الجلاس في البرنامج كله: خلفية متحركة + مسح الخلفيات الصلبة
        try { MtSmooth.install(this) } catch (_: Throwable) {}   // (v988) فتح الفريمات لأعلى معدل تحديث
        try { MobileDataGuard.install(this) } catch (_: Throwable) {}   // (v1010) تحذير داتا الموبايل في كل الشاشات
        try { PermAsk.install(this) } catch (_: Throwable) {}   // (v1010) صلاحيات بتتطلب وقت الحاجة بس
    }

    // (v1080) Skipii thumbnails are in every list and the player: a short crossfade replaces pop-in; 25% of memory for the image cache.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .build()
}
