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

    /** (v930) قرار التوجيه: ok=true ← يتفتح في MeeTube بالمصدر src · ok=false ← reason = سبب البقاء على مشغّل Joyboy (للّوج). مفيش آثار جانبية. */
    class Decision(val ok: Boolean, val reason: String, val src: String? = null) {
        companion object { fun skip(reason: String) = Decision(false, reason) }
    }

    /** (v930) نفس شروط reroute القديمة بالظبط، بس بترجّع السبب. بتتنادى جوه try (Cfg.ensure ممكن ترمي). */
    fun decide(act: Activity, i: Intent): Decision {
        Cfg.ensure(act.applicationContext)
        if (!enabled()) return Decision.skip(R_OFF)
        if (DeviceUi.isTv(act)) return Decision.skip(R_TV)
        if (i.getBooleanExtra("remote", false)) return Decision.skip("extra remote (ريموت — م4)")
        for (k in listOf("mirror_uri", "ytid", "ytpend", "aurl", "qlist", "forceid")) {
            if (i.hasExtra(k)) return Decision.skip("extra $k")
        }
        if (!i.getStringExtra("ref").isNullOrEmpty()) return Decision.skip("هيدر ref (م2)")
        if (!i.getStringExtra("cookie").isNullOrEmpty()) return Decision.skip("هيدر cookie (م2)")
        if (i.getBooleanExtra("incognito", false)) return Decision.skip("incognito (م2)")
        if (i.getBooleanExtra("hsite", false)) return Decision.skip("hsite (م2)")
        val data = i.data
        val url = i.getStringExtra("url")
        val src: String = when {
            data != null -> {
                val sc = data.scheme ?: return Decision.skip("data بدون scheme")
                if (sc != "content" && sc != "file") return Decision.skip("data scheme=$sc")
                if (sc == "file" && (data.path ?: "").let { it.endsWith(".part") || it.contains(".part") }) return Decision.skip("ملف .part (تحميل شغّال)")   // مشغّل Joyboy بيعرض الجزء المحمّل
                data.toString()
            }
            !url.isNullOrBlank() -> {
                if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return Decision.skip("url مش http(s)")
                if (!MEDIA_EXT.containsMatchIn(url)) return Decision.skip("url مش امتداد ميديا (محتاج استخراج)")
                url
            }
            else -> return Decision.skip("مفيش data ولا url")
        }
        return Decision(true, "ok", src)
    }

    private const val R_OFF = "mt_main_player off"
    private const val R_TV = "tv"

    /** true = اتفتح في مشغّل MeeTube (والـPlayerActivity لازم تقفل نفسها) */
    fun reroute(act: Activity, i: Intent): Boolean {
        try {
            val d = decide(act, i)
            if (!d.ok) {
                if (d.reason != R_OFF && d.reason != R_TV) LogStore.add("MtPlay: skip — ${d.reason}")   // (v930) السبب في اللوج (الحالتين الأوليين عادية فمش بنسجّلها)
                return false
            }
            val src = d.src ?: return false
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
