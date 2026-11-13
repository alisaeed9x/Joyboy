package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.View
import com.newtube.mobile.ui.playback.SeekFilmstrip
import java.util.concurrent.ConcurrentHashMap

/**
 * (v956) إطارات شريط التقدّم لفيديو الميديا/المتصفح (مصدر غير يوتيوب) جوه مشغّل MeeTube:
 * MeeTube بيجيب الإطارات من storyboard يوتيوب بس؛ هنا بنقطعها من الملف/الرابط نفسه بـ MediaMetadataRetriever
 * (نفس فكرة SeekFilmstrip بتاع مشغّل Joyboy القديم) على خيط واحد، الأقرب للصباع الأول، والإطارات متخزّنة (LruCache).
 * بيتوصّل بـ SeekFilmstrip.setFrameSource، وبيبدأ/يقف من OnScrubListener بتاع الشريط (MtBar.hookSeekBar).
 */
class MtFrames(
    private val ctx: Context,
    private val view: View,
    private val src: String,
    private val hdr: Map<String, String>,
    private val durProvider: () -> Long
) : SeekFilmstrip.FrameSource {
    private val d = ctx.resources.displayMetrics.density
    private val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: Int, v: Bitmap): Int = v.byteCount }
    private val bad = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var durMs = 0L
    @Volatile private var each = 0L
    @Volatile private var centreMs = 0L
    @Volatile private var live = false
    @Volatile private var failed = false
    @Volatile private var aspectV = 16f / 9f
    private var worker: Thread? = null

    override fun eachMs(): Long = if (failed) 0L else each
    override fun aspect(): Float = aspectV
    override fun count(): Int = if (each > 0) (((durMs - 1L).coerceAtLeast(0L)) / each).toInt() + 1 else 0
    override fun frame(index: Int): Bitmap? = cache.get(index)

    /** المصدر ينفع للإطارات؟ (مش بث ولا صفحة يوتيوب) */
    companion object {
        fun usable(s: String?): Boolean {
            val l = s?.lowercase() ?: return false
            if (l.isEmpty()) return false
            if (!l.startsWith("http")) return true
            return !(l.contains(".m3u8") || l.contains(".mpd") || l.contains("youtube.com/") || l.contains("youtu.be/"))
        }
    }

    /** بداية السحب */
    fun begin(positionMs: Long) {
        durMs = try { durProvider() } catch (_: Throwable) { 0L }
        each = if (durMs > 0) ((durMs / 150L) / 1000L * 1000L).coerceIn(2000L, 30000L) else 0L
        centreMs = positionMs
        if (each <= 0L || failed) return
        if (worker?.isAlive == true && live) return
        live = true
        val s = src; val hd = HashMap(hdr)
        val app = ctx.applicationContext
        val t = Thread {
            var m: MediaMetadataRetriever? = null
            try {
                m = MediaMetadataRetriever()
                val l = s.lowercase()
                when {
                    l.startsWith("http") -> m.setDataSource(s, hd)
                    l.startsWith("content:") || l.startsWith("file:") -> m.setDataSource(app, Uri.parse(s))
                    else -> m.setDataSource(s)
                }
                val fh = (64f * 1.3f * d).toInt().coerceAtLeast(48)
                while (live) {
                    val i = nextMissing()
                    if (i < 0) { Thread.sleep(40L); continue }
                    val raw = try { m.getFrameAtTime((i * each + each / 2) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (_: Throwable) { null }
                    if (raw == null) { bad.add(i); continue }
                    val ar = (raw.width.toFloat() / raw.height.coerceAtLeast(1)).coerceIn(0.5f, 2.4f)
                    aspectV = ar
                    val sc = Bitmap.createScaledBitmap(raw, (fh * ar).toInt().coerceAtLeast(1), fh, true)
                    if (sc !== raw) raw.recycle()
                    cache.put(i, sc)
                    view.postInvalidate()
                }
            } catch (e: Throwable) {
                failed = true
                LogStore.err("MtFrames", e)
                view.postInvalidate()
            } finally { try { m?.release() } catch (_: Throwable) {} }
        }
        t.isDaemon = true; worker = t; t.start()
    }

    fun center(positionMs: Long) { centreMs = positionMs }

    /** رفع الصباع: يوقف الخيط (الإطارات تفضل متخزّنة) */
    fun end() { live = false }

    private fun nextMissing(): Int {
        val e = each; if (e <= 0L) return -1
        val c = (centreMs / e).toInt()
        val maxI = ((durMs - 1L).coerceAtLeast(0L) / e).toInt()
        for (k in 0..8) {
            val offs = if (k == 0) intArrayOf(0) else intArrayOf(k, -k)
            for (o in offs) {
                val i = c + o
                if (i < 0 || i > maxI) continue
                if (cache.get(i) == null && !bad.contains(i)) return i
            }
        }
        return -1
    }
}
