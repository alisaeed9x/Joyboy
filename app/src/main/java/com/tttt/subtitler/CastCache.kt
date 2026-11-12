package com.tttt.subtitler

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import java.io.File

/**
 * (v564) كاش مؤقت على التلفزيون لفيديو جاي من الموبايل (لينك فيه tvcast=1).
 * بيتحفظ اللي اتبعت بس، وأول ما تخرج من الفيديو الكاش كله بيتمسح (وبيتنضّف كمان لو التطبيق اتقفل فجأة).
 */
object CastCache {
    private const val DIR = "cast_cache"
    private const val MAX_BYTES = 1536L * 1024 * 1024   // سقف أمان: لو الفيلم أكبر القديم بيتشال
    @Volatile private var cache: SimpleCache? = null

    fun isCast(url: String?): Boolean = url != null && url.contains("tvcast=1")

    @Synchronized fun factory(ctx: Context, upstream: DataSource.Factory): DataSource.Factory {
        val c = cache ?: run {
            val d = File(ctx.cacheDir, DIR); d.mkdirs()
            SimpleCache(d, LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(ctx.applicationContext)).also { cache = it }
        }
        return CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** تحميل أعمق من العادي (الشبكة محلية): الخط الأزرق بيمتد قدّام مكان التشغيل */
    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(40_000, 240_000, 2_500, 5_000)
        .setTargetBufferBytes(96 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    /** يقفل الكاش ويمسح كل ملفاته (بنناديه بعد release للمشغّل) */
    @Synchronized fun purge(ctx: Context) {
        try { cache?.release() } catch (_: Throwable) {}
        cache = null
        try { File(ctx.cacheDir, DIR).deleteRecursively() } catch (_: Throwable) {}
    }

    /** تنضيف بقايا لو التطبيق اتقفل وهو بيشغّل فيديو من الموبايل (مفيش كاش مفتوح دلوقتي) */
    @Synchronized fun purgeStale(ctx: Context) { if (cache == null) try { File(ctx.cacheDir, DIR).deleteRecursively() } catch (_: Throwable) {} }
}
