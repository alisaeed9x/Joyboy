package com.tttt.subtitler

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (v928) على التلفزيون: لما فيديو يوتيوب يتفتح في مشغّل MeeTube (TvDirect.playYt)، ريموت الموبايل لازم يفضل يتحكم فيه.
 * MtTranslate.attach/detach بيسجّلوا الـExoPlayer هنا، وTvLink.playerCmd بيحوّل أوامر/حالة الريموت له لما مفيش مشغّل Joyboy شغّال.
 * الأوامر المدعومة: toggle · seek (±ms) · seekto · speedset · close. أي أمر تاني = مايتعملش حاجة (من غير ما الريموت يفتكر إن مفيش مشغّل).
 */
object MtTvPlayer {
    /** JOYBOY(v939): experimental TV player routing remains opt-in until physical TCL validation. */
    const val KEY_TV_PLAYER = "mt_tv_player"
    fun enabled(context: android.content.Context): Boolean = try { Cfg.bool(KEY_TV_PLAYER, false) } catch (_: Throwable) { false }

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

    fun act(a: String, v: Long): Boolean {
        if (!live()) return false
        onUi { p, ac ->
            when (a) {
                "toggle", "playpause" -> if (p.isPlaying) p.pause() else p.play()
                "seek" -> p.seekTo((p.currentPosition + v).coerceIn(0L, if (p.duration > 0L) p.duration else Long.MAX_VALUE))
                "seekto" -> p.seekTo(v.coerceIn(0L, if (p.duration > 0L) p.duration else Long.MAX_VALUE))
                "speedset" -> p.setPlaybackSpeed((v / 100f).coerceIn(0.25f, 4f))
                "close" -> { try { p.pause() } catch (_: Throwable) {}; ac.finish() }
            }
            true
        }
        return true
    }

    fun state(): JSONObject? {
        if (!live()) return null
        return onUi { p, _ ->
            val t = try { p.mediaMetadata.title?.toString() } catch (_: Throwable) { null }
            JSONObject()
                .put("kind", "player")
                .put("title", if (!t.isNullOrBlank()) t else title)
                .put("pos", p.currentPosition)
                .put("dur", if (p.duration > 0L) p.duration else 0L)
                .put("playing", p.isPlaying)
                .put("speed", p.playbackParameters.speed.toString() + "x")
                .put("vol", -1)
        }
    }
}
