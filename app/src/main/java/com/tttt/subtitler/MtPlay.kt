package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
import com.newtube.mobile.MobileMainApplication

/**
 * (v923) مشغّل يوتيوب (MeeTube) هو المشغّل الأساسي لكل الميديا — البداية: الملفات المحلية واللينكات المباشرة.
 * أي فتح لـPlayerActivity (من أي مكان: مكتبة · ملفات · متصفح · نقل · مهام) بيعدّي هنا الأول (PlayerActivity.onCreate):
 * لو الحالة مدعومة بيفتح الفيديو في مشغّل MeeTube (Video.localUri) ومحرك الترجمة + أزرار Joyboy (MtTranslate) فوقه، ومشغّل Joyboy القديم بيتقفل.
 * غير كده (تلفزيون · ريموت · يوتيوب · لينك محتاج Referer/Cookie · وضع متخفي · ملف تحميل لسه شغّال) = مشغّل Joyboy زي ما هو.
 * مفتاح الإيقاف: Cfg mt_main_player (الافتراضي false من v924).
 */
object MtPlay {
    const val KEY = "mt_main_player"
    private val MEDIA_EXT = Regex("\\.(mp4|m4v|mkv|webm|mov|m3u8|mpd|ts|3gp|avi|flv)(\\?|#|$)", RegexOption.IGNORE_CASE)

    fun enabled(): Boolean = try { Cfg.bool(KEY, false) } catch (_: Throwable) { false }   // (v924) افتراضي: مقفول — الملفات المحلية بتفتح على مشغّل Joyboy الكامل (الشاشة الكاملة)

    /** true = اتفتح في مشغّل MeeTube (والـPlayerActivity لازم تقفل نفسها) */
    fun reroute(act: Activity, i: Intent): Boolean {
        try {
            Cfg.ensure(act.applicationContext)
            if (!enabled() || DeviceUi.isTv(act)) return false
            if (i.getBooleanExtra("remote", false) || i.hasExtra("mirror_uri") || i.hasExtra("ytid") || i.hasExtra("ytpend") ||
                i.hasExtra("aurl") || i.hasExtra("qlist") || i.hasExtra("forceid")) return false
            if (!i.getStringExtra("ref").isNullOrEmpty() || !i.getStringExtra("cookie").isNullOrEmpty()) return false
            if (i.getBooleanExtra("incognito", false) || i.getBooleanExtra("hsite", false)) return false
            val data = i.data
            val url = i.getStringExtra("url")
            val src: String = when {
                data != null -> {
                    val sc = data.scheme ?: return false
                    if (sc != "content" && sc != "file") return false
                    if (sc == "file" && (data.path ?: "").let { it.endsWith(".part") || it.contains(".part") }) return false   // تحميل لسه شغّال: مشغّل Joyboy بيعرض الجزء المحمّل
                    data.toString()
                }
                !url.isNullOrBlank() -> {
                    if (!(url.startsWith("http://", true) || url.startsWith("https://", true)) || !MEDIA_EXT.containsMatchIn(url)) return false   // صفحات/ستريمات محتاجة استخراج = مشغّل Joyboy
                    url
                }
                else -> return false
            }
            val title = i.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: nameOf(act, src)
            if (i.getBooleanExtra("autotr", false) && !i.getBooleanExtra("nosub", false)) MtTranslate.autoFor = "L:$src"
            MeeTubeLink.bindBar()
            MobileMainApplication.boot(act.application)
            val v = Video()
            v.videoId = "joy_local_" + Integer.toHexString(src.hashCode())
            v.title = title
            v.localUri = src
            PlaybackPresenter.instance(act.applicationContext).openVideo(v)
            return true
        } catch (e: Throwable) {
            LogStore.err("MtPlay:reroute", e)
            return false
        }
    }

    private fun nameOf(act: Activity, s: String): String {
        try {
            val u = Uri.parse(s)
            if (u.scheme == "content") act.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.') }
            return (u.lastPathSegment ?: s).substringBeforeLast('.')
        } catch (_: Throwable) { return s }
    }
}
