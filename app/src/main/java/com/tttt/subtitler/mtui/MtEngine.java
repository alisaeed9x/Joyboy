package com.tttt.subtitler.mtui;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.app.AppServiceIntCached;
import com.liskovsoft.youtubeapi.browse.v2.BrowseServiceGates;
import com.liskovsoft.youtubeapi.innertube.ytcfg.EmbedIdentityPersistence;
import com.liskovsoft.youtubeapi.next.v2.WatchNextGates;
import com.liskovsoft.youtubeapi.search.v2.SearchServiceGates;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.liskovsoft.youtubeapi.videoinfo.models.VodDelivery;

/**
 * (v1111 Y6d) تهيئة محرك MediaServiceCore (:youtubeapi) من جوه app — بدل MobileMainApplication.boot (اللي بيورّث MainApplication
 * وبيشغّل كل تهيئة واجهة :common/:mobile). ده الجزء اللي المحرك محتاجه فعلًا، منقول حرفيًا من MobileMainApplication.onCreate:
 *  • OkHttp على HTTP/2 (لازم قبل ما أي حاجة تبني العميل — static initializer)  • GlobalPreferences (تخزين التوكن/الحساب)
 *  • أعلام VideoInfoService/Browse/Search/WatchNext  • مخازن هوية التشغيل (BotWall/EmbedIdentity/PlaybackIdentity)
 *  • تسخين توكن PO بعد 4 ثواني  • إخلاء تجمّع الاتصالات لما الشبكة تتبدّل  • preconnect لـyoutube.com
 * اتشال: كل اللي بيخص واجهة :common (Presenters/ViewManager/SidebarService/FeedCache/MediaServiceManager…) وأعلام الـdebug/benchmark.
 */
public final class MtEngine {
    static {
        // HTTP/2 للموبايل (نفس تعليق MobileMainApplication: لازم يتظبط قبل ما العميل المشترك يتبني)
        OkHttpManager.setPreferHttp2(true);
    }

    private static boolean sBooted;

    private MtEngine() {
    }

    public static synchronized boolean booted() {
        return sBooted;
    }

    public static synchronized void boot(Application app) {
        if (sBooted) {
            return;
        }
        sBooted = true;

        GlobalPreferences.instance(app);

        VideoInfoService.setPreferNoPotClient(true);
        VodDelivery.setHlsEnabled(true);
        VideoInfoService.setKidsChannelHintEnabled(true);
        VideoInfoService.setLiveCardHintEnabled(true);
        VideoInfoService.setEmbedRerollEnabled(true);
        VideoInfoService.setFoldHlsChallenge(true);
        VideoInfoService.setKeepSigRuntimeAlive(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        VideoInfoService.setSkipLiveDashInfoWithManifest(true);
        VideoInfoService.setBotWallStore(new BotWallPrefsStore(app));
        EmbedIdentityPersistence.setStore(new EmbedIdentityPrefsStore(app));
        VideoInfoService.setWallMemoryStore(new PlaybackIdentityPrefsStore(app, PlaybackIdentityPrefsStore.KEY_WALLS));
        VideoInfoService.setPlaybackIdentityStore(new PlaybackIdentityPrefsStore(app));
        VideoInfoService.setWallMemoryEnabled(true);
        VideoInfoService.setVodVrLateEnabled(true);
        VideoInfoService.setPlaybackRerollEnabled(true);
        VideoInfoService.setPlaybackKeepEnabled(true);
        VideoInfoService.setSkipStoryboardEnrichment(true);
        BrowseServiceGates.setSkipContinuationPreCombine(true);
        SearchServiceGates.setHistoryMatchesQuery(true);
        DefaultHeaders.setBrotliEnabled(true);
        YouTubeMediaItemService.setSingleFlightEnabled(true);
        YouTubeMediaItemService.setPreconnectMediaHost(true);
        AppServiceIntCached.setPersistedAppInfoEnabled(true);
        WatchNextGates.setSuggestionsSectionContinuation(true);

        // تسخين بوابة PO token بعد 4 ثواني (نفس الـfallback بتاع الأصل من غير انتظار أول فريم/فتح)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                VideoInfoService.warmUpPoTokenGate();
            } catch (Throwable ignored) {
            }
        }, 4_000L);

        startConnectionPoolEviction(app);

        Thread preconnect = new Thread(() -> {
            try {
                RetrofitHelper.warmUpApiConnection("https://www.youtube.com/generate_204");
            } catch (Throwable ignored) {
            }
        }, "TubePreconnect");
        preconnect.setDaemon(true);
        preconnect.start();
    }

    private static void startConnectionPoolEviction(Application app) {
        ConnectivityManager manager = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                private Network mCurrent;
                private boolean mSeenFirst;

                @Override
                public void onAvailable(Network network) {
                    boolean replaced;
                    synchronized (this) {
                        replaced = mSeenFirst && !network.equals(mCurrent);
                        mSeenFirst = true;
                        mCurrent = network;
                    }
                    if (replaced) {
                        OkHttpManager.evictConnections();
                    }
                }

                @Override
                public void onLost(Network network) {
                    synchronized (this) {
                        if (network.equals(mCurrent)) {
                            mCurrent = null;
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            // الشبكة هتتجدد لوحدها؛ بس مفيش إخلاء للتجمّع
        }
    }
}
