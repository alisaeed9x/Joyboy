package com.tttt.subtitler

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (v928) على التلفزيون: لما فيديو يوتيوب يتفتح في مشغّل MeeTube (TvDirect.playYt)، ريموت الموبايل لازم يفضل يتحكم فيه.
 * MtTranslate.attach/detach بيسجّلوا الـExoPlayer هنا، وTvLink.playerCmd بيحوّل أوامر/حالة الريموت له لما مفيش مشغّل Joyboy شغّال.
 * (v939) المرحلة 4 — الجزء الأول: توسعة على نفس النمط (من غير محرك ترجمة جديد ومن غير لمس Player.kt):
 * الأوامر المدعومة: toggle · seek (±ms) · seekto · speed (تدوير) · speedset · prev · next · loop · loopset · ab_a · ab_b · ab_x · vol · close.
 * أي أمر تاني = false بصراحة (مش true كذب — v928 كانت بترجّع true لأي أمر حتى اللي مش متنفّذ، فالموبايل كان بيفتكر إنه اتنفّذ).
 * الحالة بقت فيها حقول حقيقية (loop · vol · ab · src · أبعاد الفيديو)؛ cc/audio/quality = false بصراحة لأنها محتاجة قوايم MeeTube (لسه).
 * لقطة حية (frameJpeg) + handoff + queueCur من MtOrigin (نفس عقد PlayerActivity).
 * الفلاج: mt_tv_player (افتراضي false — م4). بيتحكم في توجيه ملفات/لينكات التلفزيون لمشغّل MeeTube من MtPlay؛
 * مسار يوتيوب-على-التلفزيون بتاع v928 زي ما هو من غير الفلاج، والـlegacy هو الـfallback دايمًا.
 * اللي لسه على legacy (مش متنفّذ هنا): محرك الترجمة (engine.*) · استيراد ترجمة من الموبايل (SfJob) · أدوات الميديا (dl/task) · fit/rotate · الوضع البصري.
 */
object MtTvPlayer {
    const val KEY = "mt_tv_player"

    fun enabled(): Boolean = try { Cfg.bool(KEY, false) } catch (_: Throwable) { false }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var act: Activity? = null
    @Volatile private var player: ExoPlayer? = null
    @Volatile private var title = ""

    fun set(a: Activity, p: ExoPlayer, t: String) {
        if (!DeviceUi.isTv(a)) return
        act = a; player = p; title = t
    }

    fun clear(a: Activity) {
        if (act === a) { act = null; player = null; title = "" }
    }

    fun live(): Boolean {
        val a = act
        return player != null && a != null && !a.isFinishing && !a.isDestroyed
    }

    private fun <T> onUi(f: (ExoPlayer, Activity) -> T): T? {
        val p = player ?: return null
        val a = act ?: return null
        val box = arrayOfNulls<Any>(1)
        val latch = CountDownLatch(1)
        ui.post { try { box[0] = f(p, a) } catch (e: Throwable) { LogStore.err("MtTvPlayer", e) }; latch.countDown() }
        @Suppress("UNCHECKED_CAST")
        return if (latch.await(900L, TimeUnit.MILLISECONDS)) box[0] as T? else null
    }

    /** تشغيل/إيقاف من غير أمر (TvLink.exec "playpause"). */
    fun remoteToggle(): Boolean {
        if (!live()) return false
        onUi { p, _ ->
            if (p.isPlaying) p.pause() else p.play()
            true
        }
        return true
    }

    fun act(a: String, v: Long): Boolean {
        if (!live()) return false
        val handled = onUi { p, ac ->
            when (a) {
                "toggle", "playpause" -> {
                    if (p.isPlaying) p.pause() else p.play()
                    true
                }
                "seek" -> {
                    p.seekTo((p.currentPosition + v).coerceIn(0L, if (p.duration > 0L) p.duration else Long.MAX_VALUE))
                    true
                }
                "seekto" -> {
                    p.seekTo(v.coerceIn(0L, if (p.duration > 0L) p.duration else Long.MAX_VALUE))
                    true
                }
                "speedset" -> {
                    p.setPlaybackSpeed((v / 100f).coerceIn(0.25f, 4f))
                    true
                }
                "speed" -> {
                    val cur = try { p.playbackParameters.speed } catch (_: Throwable) { 1f }
                    p.setPlaybackSpeed(PlayerLogic.nextSpeed(cur))
                    true
                }
                "prev" -> {
                    val stepped = try { MtMedia.onStep(ac, -1) } catch (_: Throwable) { false }
                    if (!stepped) {
                        try { p.seekTo(0L) } catch (_: Throwable) {}
                    }
                    true
                }
                "next" -> {
                    val stepped = try { MtMedia.onStep(ac, 1) } catch (_: Throwable) { false }
                    if (!stepped) {
                        val d = try { p.duration } catch (_: Throwable) { 0L }
                        if (d > 0L) {
                            try { p.seekTo(d) } catch (_: Throwable) {}
                        } else {
                            return@onUi false
                        }
                    }
                    true
                }
                "loop" -> {
                    try { MtMedia.loopCycle() } catch (_: Throwable) {}
                    true
                }
                "loopset" -> {
                    val cur = try { MtMedia.loopMode() } catch (_: Throwable) { 0 }
                    val n = v.toInt().coerceIn(0, 2)
                    val target = if (n == cur) 0 else n
                    try {
                        Cfg.put("loop", if (target == 1) "1" else "0")
                        Cfg.put("autoplay_wrap", if (target == 2) "1" else "0")
                        p.repeatMode = if (target == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    } catch (_: Throwable) {}
                    true
                }
                "ab_a", "ab_b" -> {
                    try { MtMedia.abNext() } catch (_: Throwable) {}
                    true
                }
                "ab_x" -> {
                    try { MtMedia.abClear() } catch (_: Throwable) {}
                    true
                }
                "vol", "volume" -> {
                    val f = (v.toInt().coerceIn(0, 100) / 100f)
                    try { p.volume = f } catch (_: Throwable) {}
                    true
                }
                "close" -> {
                    try { p.pause() } catch (_: Throwable) {}
                    try { ac.finish() } catch (_: Throwable) {}
                    true
                }
                else -> false
            }
        } ?: false
        return handled
    }

    fun state(): JSONObject? {
        if (!live()) return null
        return onUi { p, _ ->
            val t = try { p.mediaMetadata.title?.toString() } catch (_: Throwable) { null }
            val vs = try { p.videoSize } catch (_: Throwable) { null }
            val lm = try { MtMedia.loopMode() } catch (_: Throwable) { 0 }
            val vol = try { (p.volume * 100).toInt().coerceIn(0, 100) } catch (_: Throwable) { -1 }
            val ab = try { MtMedia.abState() } catch (_: Throwable) { "" }
            val src = try { MtOrigin.current()?.src ?: "" } catch (_: Throwable) { "" }
            val spd = try { p.playbackParameters.speed } catch (_: Throwable) { 1f }
            JSONObject()
                .put("kind", "player")
                .put("title", if (!t.isNullOrBlank()) t else title)
                .put("pos", p.currentPosition)
                .put("dur", if (p.duration > 0L) p.duration else 0L)
                .put("playing", p.isPlaying)
                .put("speed", PlayerLogic.speedLabel(spd))
                .put("spd", spd.toDouble())
                .put("vol", vol)
                .put("loop", lm != 0)
                .put("lm", lm)
                .put("ab", ab)
                .put("cc", false)
                .put("quality", false)
                .put("audio", false)
                .put("vw", vs?.width ?: 0)
                .put("vh", vs?.height ?: 0)
                .put("src", src)
        }
    }

    private fun refOf(o: MtOrigin.Origin?): String {
        if (o == null) return ""
        if (o.videoId.length == 11) return "https://www.youtube.com/watch?v=" + o.videoId
        val s = o.src
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
        return ""
    }

    /** (v939) نفس عقد PlayerActivity.remoteHandoff: الوصف + المكان + الهيدرز — والتلفزيون بيوقّف مؤقتًا لو فيه مرجع. */
    fun remoteHandoff(): JSONObject? {
        if (!live()) return null
        return onUi { p, _ ->
            val o = try { MtOrigin.current() } catch (_: Throwable) { null }
            val ref = refOf(o)
            if (ref.isNotEmpty()) {
                try { p.pause() } catch (_: Throwable) {}
            }
            JSONObject()
                .put("ref", ref)
                .put("title", o?.title ?: title)
                .put("pos", p.currentPosition)
                .put("hdr_ref", o?.headers?.get("Referer") ?: "")
                .put("hdr_ua", o?.headers?.get("User-Agent") ?: "")
                .put("hdr_cookie", o?.headers?.get("Cookie") ?: "")
        }
    }

    /** (v939) نفس عقد PlayerActivity.remoteQueueCur: الوصف بس، من غير إيقاف. */
    fun remoteQueueCur(): JSONObject? {
        if (!live()) return null
        return onUi { _, _ ->
            val o = try { MtOrigin.current() } catch (_: Throwable) { null }
            JSONObject()
                .put("ref", refOf(o))
                .put("title", o?.title ?: title)
        }
    }

    private fun findVideoView(v: View): View? {
        if (v is SurfaceView || v is TextureView) return v
        if (v is ViewGroup) {
            for (k in 0 until v.childCount) {
                findVideoView(v.getChildAt(k))?.let { return it }
            }
        }
        return null
    }

    /** (v939) لقطة حية JPEG زي PlayerActivity.frameJpeg (نفس حدود الأبعاد/الجودة). null = مفيش سطح/DRM — الموبايل يعرض البوستر. */
    fun frameJpeg(maxDim: Int, q: Int): ByteArray? {
        val a = act ?: return null
        if (!live()) return null
        return try {
            val vv = findVideoView(a.window.decorView) ?: return null
            val bmp0: Bitmap = when (vv) {
                is TextureView -> try { vv.bitmap } catch (_: Throwable) { null }
                is SurfaceView -> {
                    if (vv.width <= 0 || vv.height <= 0) return null
                    val bmp = Bitmap.createBitmap(vv.width, vv.height, Bitmap.Config.ARGB_8888)
                    val latch = CountDownLatch(1)
                    val okBox = booleanArrayOf(false)
                    PixelCopy.request(vv, bmp, { res ->
                        okBox[0] = res == PixelCopy.SUCCESS
                        latch.countDown()
                    }, Handler(Looper.getMainLooper()))
                    if (!latch.await(1500L, TimeUnit.MILLISECONDS)) return null
                    if (!okBox[0]) return null
                    bmp
                }
                else -> null
            } ?: return null
            val md = maxDim.coerceIn(160, 960)
            val sc = minOf(1f, md.toFloat() / maxOf(bmp0.width, bmp0.height).toFloat())
            val bmp = if (sc < 1f) {
                Bitmap.createScaledBitmap(bmp0, (bmp0.width * sc).toInt().coerceAtLeast(1), (bmp0.height * sc).toInt().coerceAtLeast(1), true)
            } else {
                bmp0
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q.coerceIn(30, 90), out)
            if (bmp !== bmp0) {
                try { bmp0.recycle() } catch (_: Throwable) {}
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }
}
