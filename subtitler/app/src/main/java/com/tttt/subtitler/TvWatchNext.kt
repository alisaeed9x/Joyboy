package com.tttt.subtitler

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import java.io.File

/**
 * (v462) صف «تابع المشاهدة» في لانشر Android TV نفسه (بره البرنامج):
 * بننشر آخر فيديوهات المستخدم في Watch Next بتاع النظام، والدوسة عليها بتفتح المشغّل على طول.
 * بيشتغل على أجهزة التلفزيون الحقيقية بس، وأي جهاز مش بيدعمه بيتجاهل بهدوء.
 */
object TvWatchNext {
    fun sync(ctx: Context) {
        if (!DeviceUi.isRealTv(ctx)) return
        Thread {
            try {
                val recents = Recents.parse(try { File(ctx.filesDir, "recent.json").readText() } catch (_: Exception) { "" })
                    .filter { it.percent < 95 && it.uri.isNotEmpty() }.take(10)
                val uri = TvContract.WatchNextPrograms.CONTENT_URI
                val cr = ctx.contentResolver
                try { cr.delete(uri, TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME + "=?", arrayOf(ctx.packageName)) } catch (_: Exception) {}
                for (r in recents) {
                    val play = Intent(ctx, PlayerActivity::class.java).apply {
                        data = Uri.parse(r.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra("landscape", true); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
                    }
                    val v = ContentValues().apply {
                        put(TvContract.WatchNextPrograms.COLUMN_TITLE, r.title)
                        put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                        put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (r.posSec * 1000).toLong())
                        put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (r.durSec * 1000).toLong())
                        put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, play.toUri(Intent.URI_INTENT_SCHEME))
                    }
                    try { cr.insert(uri, v) } catch (_: Exception) {}
                }
            } catch (e: Exception) { LogStore.err("TvWatchNext", e) }
        }.start()
    }
}
