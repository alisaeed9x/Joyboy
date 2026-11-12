package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.exoplayer.ExoPlayer

/**
 * (v938) قفل فجوات الفيديوهات اللي جاية من النت (لينك مباشر / مصطادة من المتصفح) في مسار مشغّل MeeTube:
 *  1) هوية الفيديو: نفس هوية مشغّل Joyboy (`u:…` + WebIdent) بدل `f:<لينك>` — فالترجمة/التقدم/«آخر المشغّلة» القديمة تتلاقي (نسخة من Player.webId/videoId).
 *  2) التسجيل في «فيديوهات الإنترنت المحفوظة» (WebVideos.register) + لقطة مصغّرة (PixelCopy) — زي Player.webRegister/webSnap.
 *  3) extra `ret`: الخروج من المشغّل يرجّعك للمتصفح/يوتيوب اللي فتحت منه (زي Player.returnToOrigin).
 * بيتنده من MtPlay.openMt (قبل openVideo) ومن MtMedia.attach/detach. كله بـtry/catch ومالوش أثر على يوتيوب أو الملفات المحلية.
 */
object MtWeb {
    private val ui = Handler(Looper.getMainLooper())

    private class Meta(val id: String, val ret: String, val track: Boolean)
    /** مفتاح = لينك المصدر */
    private val metas: MutableMap<String, Meta> = java.util.Collections.synchronizedMap(HashMap<String, Meta>())

    private fun isWeb(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)

    /** (نسخة من Player.webId v138) هوية فيديو النت: الاسم في اللينك لوحده مش كفاية للينكات العامة */
    private fun webId(u: String, ref: String, title: String): String {
        val noFrag = u.substringBefore('#')
        val base = noFrag.substringBefore('?')
        val q = noFrag.substringAfter('?', "")
        fun qp(k: String): String? = q.split('&').firstNotNullOfOrNull { kv -> if (kv.substringBefore('=').equals(k, true)) kv.substringAfter('=', "").takeIf { it.isNotBlank() } else null }
        val file = base.substringAfterLast('/').lowercase()
        val generic = u.contains("videoplayback") || file.isBlank() ||
            Regex("(master|index|playlist|chunklist|manifest|stream|video|play|media|file|main|source|hls|dash)(\\.(m3u8|mpd|mp4|webm|ts|php|m4v))?").matches(file)
        val stable = listOf("id", "docid", "vid", "video", "video_id", "videoid", "v", "fid", "file", "name", "mid").firstNotNullOfOrNull { k -> qp(k)?.let { "$k=$it" } }
        if (!generic) return "u:$base"
        if (stable != null) return "u:" + (if (u.contains("videoplayback")) "gv" else base) + "?" + stable
        val page = ref.substringBefore('#').substringBefore('?').trim()
        val extra = listOf(title.trim(), if (page.length > 12 && page.substringAfter("://").contains('/')) page else "").filter { it.isNotBlank() }.joinToString("|")
        return if (extra.isNotBlank()) "u:$base|$extra" else "u:$base?" + Integer.toHexString(q.hashCode())
    }

    /**
     * بيتنده من MtPlay.openMt لأي src بروتوكوله http(s). بيحسب الهوية ← MtTranslate.idOverride، وبيسجّل في المصطادة، وبيفتكر `ret`.
     * incognito = extra "incognito" (مش «الموقع المحجوب»)، hidden = موقع محجوب (بيتسجّل مخفي).
     */
    fun onOpen(app: Context, i: Intent, src: String, hdr: Map<String, String>, forceId: String?, incognito: Boolean, hidden: Boolean) {
        try {
            if (!isWeb(src)) return
            val ref = hdr["Referer"] ?: ""; val ua = hdr["User-Agent"] ?: ""; val ck = hdr["Cookie"] ?: ""
            val siteTitle = i.getStringExtra("title")?.takeIf { it.isNotBlank() }
            val title0 = i.getStringExtra("title") ?: ""
            val id: String = if (forceId != null) {
                if (!incognito) Thread { try { WebIdent.bindLink(forceId, src, ref, title0) } catch (_: Throwable) {} }.apply { isDaemon = true }.start()
                forceId
            } else {
                val legacy = webId(src, ref, title0)
                if (incognito) legacy else try { WebIdent.resolve(legacy, src, ref, title0).id } catch (_: Throwable) { legacy }
            }
            MtTranslate.idOverride["L:$src"] = id   // الترجمة والتقدم و«آخر المشغّلة» على الهوية القديمة
            metas[src] = Meta(id, i.getStringExtra("ret") ?: "", !incognito)
            if (!incognito) {
                val t0 = siteTitle ?: Sniff.nameOf(src)
                val kind = if (src.contains("videoplayback")) "YT" else Sniff.kindOf(src)
                val pageX = i.getStringExtra("page") ?: ""
                val page = if (WebIdent.specific(pageX)) pageX else if (WebIdent.specific(ref)) ref else ""
                Thread { try { WebVideos.register(app, id, src, ref, ua, ck, t0, kind, siteTitle != null, page, hidden) } catch (_: Throwable) {} }.apply { isDaemon = true }.start()
            }
        } catch (e: Throwable) { LogStore.err("MtWeb:onOpen", e) }
    }

    // ===================== اللقطة المصغّرة =====================

    private var snapKey: String? = null
    private var snapTries = 0

    /** من MtMedia.attach: لو الفيديو مصطاد ومسجّل ومالوش لقطة، نلقطها بعد 9ث (وبنعيد لحد 4 مرات كل 8ث) */
    fun attach(a: Activity, p: ExoPlayer, src: String?) {
        try {
            val s = src ?: return
            val m = metas[s] ?: return
            if (!m.track) return
            if (snapKey == s) return   // attach بيتنده كتير (تغيير سطح) — جدولة واحدة بس للفيديو
            if (WebVideos.thumbFile(a.applicationContext, m.id).exists()) return
            snapKey = s; snapTries = 0
            ui.postDelayed({ snap(a, p, s) }, 9000L)
        } catch (e: Throwable) { LogStore.err("MtWeb:attach", e) }
    }

    private fun findVideoView(v: View): View? {
        if (v is SurfaceView || v is TextureView) return v
        if (v is ViewGroup) for (k in 0 until v.childCount) findVideoView(v.getChildAt(k))?.let { return it }
        return null
    }

    private fun snap(a: Activity, p: ExoPlayer, s: String) {
        try {
            if (snapKey != s || a.isFinishing || a.isDestroyed) return
            val m = metas[s] ?: return
            val ok = try { p.playbackState == androidx.media3.common.Player.STATE_READY && p.videoSize.width > 0 } catch (_: Throwable) { false }
            val vv = findVideoView(a.window.decorView)
            if (!ok || vv == null || vv.width <= 0 || vv.height <= 0) {
                if (++snapTries < 4) ui.postDelayed({ snap(a, p, s) }, 8000L)
                return
            }
            val app = a.applicationContext; val id = m.id
            if (vv is TextureView) {
                val bmp = vv.bitmap ?: return
                Thread { try { WebVideos.saveThumb(app, id, bmp) } catch (e: Throwable) { LogStore.err("MtWeb:thumb", e) } }.apply { isDaemon = true }.start()
            } else if (vv is SurfaceView) {
                val bmp = Bitmap.createBitmap(vv.width, vv.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(vv, bmp, { res ->
                    if (res != PixelCopy.SUCCESS) return@request
                    Thread { try { WebVideos.saveThumb(app, id, bmp) } catch (e: Throwable) { LogStore.err("MtWeb:thumb", e) } }.apply { isDaemon = true }.start()
                }, Handler(Looper.getMainLooper()))
            }
        } catch (e: Throwable) { LogStore.err("MtWeb:snap", e) }
    }

    // ===================== ret =====================

    /**
     * من MtMedia.detach: لو الأكتفيتي بتتقفل فعلاً (مش تغيير سطح/تدوير) ومفيش PiP، نرجّع للصفحة اللي فتحنا منها.
     * لو المستخدم انتقل لحلقة تانية جوه نفس المشغّل (openItem) الأكتفيتي مش بتتقفل فمفيش رجوع.
     */
    fun onDetach(a: Activity, src: String?) {
        try {
            val s = src ?: return
            snapKey = null
            val m = metas[s] ?: return
            if (!a.isFinishing || a.isChangingConfigurations) return
            metas.remove(s)
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            when (m.ret) {
                "yt" -> a.startActivity(Intent(a, MainActivity::class.java).addFlags(flags).putExtra("tab", "youtube"))
                "browser" -> a.startActivity(Intent(a, BrowserActivity::class.java).addFlags(flags))
            }
        } catch (e: Throwable) { LogStore.err("MtWeb:ret", e) }
    }
}
