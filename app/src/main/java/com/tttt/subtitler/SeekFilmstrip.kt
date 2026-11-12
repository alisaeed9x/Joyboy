package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.View
import java.util.concurrent.ConcurrentHashMap

/**
 * (v904) «شريط الإطارات» وإنت بتسحب شريط التقدّم — منقول من MeeTube (`SeekFilmstrip.java`).
 * فوق الصورة: الإطارات اللي حوالين صباعك بتتحرك كفيلم، اللي تحت الصباع أكبر بحدّ أبيض، والوقت كبير في النص.
 *
 * الفرق عن الأصل: MeeTube بيجيب الإطارات من storyboard يوتيوب. Joyboy بيجيبها من الملف/الرابط نفسه
 * بـ MediaMetadataRetriever (نفس مصدر ScrubThumbs بتاع الريموت) على خيط واحد، الأقرب للصباع الأول، والإطارات بتتخزّن
 * (LruCache) لنفس الفيديو. لو المصدر بث (m3u8/mpd) أو صفحة يوتيوب أو فشل الفتح: بيظهر الوقت بس (زي الأصل من غير storyboard).
 * مش بيلمس التلفزيون: بيتبني بس لما MtGlass.on().
 */
class SeekFilmstrip(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val txO = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeWidth = 4f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint().apply { color = 0x66000000 }
    private val dst = RectF()

    private val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: Int, v: Bitmap): Int = v.byteCount }
    private val bad = ConcurrentHashMap.newKeySet<Int>()

    private var key: String? = null
    private var uri: Uri? = null
    private var url: String? = null
    private var hdr: Map<String, String> = emptyMap()
    @Volatile private var durMs = 0L
    @Volatile private var each = 0L
    @Volatile private var centreMs = 0L
    @Volatile private var reachN = 4
    @Volatile private var failed = false
    @Volatile private var live = false
    @Volatile private var aspect = 16f / 9f
    @Volatile private var canStrip = false
    private var worker: Thread? = null
    private var time = ""
    private var sub = ""
    var active = false
        private set

    init { isClickable = false; isFocusable = false; visibility = GONE }

    private fun usable(u: Uri?, s: String?): Boolean {
        if (u != null) return true
        val l = s?.lowercase() ?: return false
        if (l.isEmpty()) return false
        if (!l.startsWith("http")) return true
        return !(l.contains(".m3u8") || l.contains(".mpd") || l.contains("youtube.com/") || l.contains("youtu.be/"))
    }

    /** بداية السحب: يجهّز المصدر (مرة لكل فيديو) ويفتح الشريط بتلاشي */
    fun begin(u: Uri?, s: String?, h: Map<String, String>, dur: Long) {
        val k = (u?.toString() ?: s) ?: ""
        if (k != key) { key = k; cache.evictAll(); bad.clear(); failed = false; aspect = 16f / 9f }
        uri = u; url = s; hdr = h; durMs = dur
        each = if (dur > 0) ((dur / 150L) / 1000L * 1000L).coerceIn(2000L, 30000L) else 0L
        canStrip = each > 0 && !failed && usable(u, s)
        active = true
        animate().cancel(); alpha = 0f; visibility = VISIBLE; animate().alpha(1f).setDuration(120L).start()
        if (canStrip) startWorker()
        invalidate()
    }

    fun update(positionMs: Long, timeText: String, subText: String) {
        centreMs = positionMs; time = timeText; sub = subText
        invalidate()
    }

    /** رفع الصباع: يقفل الشريط بتلاشي ويوقف الخيط (الإطارات تفضل متخزّنة) */
    fun end() {
        active = false; live = false
        animate().cancel()
        animate().alpha(0f).setDuration(160L).withEndAction { if (!active) visibility = GONE }.start()
    }

    private fun startWorker() {
        if (live && worker?.isAlive == true) return
        live = true
        val u = uri; val s = url; val hd = HashMap(hdr)
        val app = context.applicationContext
        val t = Thread {
            var m: MediaMetadataRetriever? = null
            try {
                m = MediaMetadataRetriever()
                if (u != null) m.setDataSource(app, u) else m.setDataSource(s, hd)
                val fh = (64f * 1.3f * d).toInt().coerceAtLeast(48)
                while (live) {
                    val i = nextMissing()
                    if (i < 0) { Thread.sleep(40L); continue }
                    val raw = try { m.getFrameAtTime((i * each + each / 2) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (_: Throwable) { null }
                    if (raw == null) { bad.add(i); continue }
                    val ar = (raw.width.toFloat() / raw.height.coerceAtLeast(1)).coerceIn(0.5f, 2.4f)
                    aspect = ar
                    val sc = Bitmap.createScaledBitmap(raw, (fh * ar).toInt().coerceAtLeast(1), fh, true)
                    if (sc !== raw) raw.recycle()
                    cache.put(i, sc)
                    postInvalidate()
                }
            } catch (e: Throwable) {
                failed = true; canStrip = false
                LogStore.err("SeekFilmstrip", e)
                postInvalidate()
            } finally { try { m?.release() } catch (_: Throwable) {} }
        }
        t.isDaemon = true; worker = t; t.start()
    }

    /** أقرب إطار ناقص للصباع (0, +1, −1, +2, −2 …) جوه مدى الشاشة */
    private fun nextMissing(): Int {
        val e = each; if (e <= 0L) return -1
        val c = (centreMs / e).toInt()
        val maxI = ((durMs - 1L).coerceAtLeast(0L) / e).toInt()
        for (k in 0..reachN) {
            val offs = if (k == 0) intArrayOf(0) else intArrayOf(k, -k)
            for (o in offs) {
                val i = c + o
                if (i < 0 || i > maxI) continue
                if (cache.get(i) == null && !bad.contains(i)) return i
            }
        }
        return -1
    }

    override fun onDetachedFromWindow() { live = false; super.onDetachedFromWindow() }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float) {
        tx.textSize = size; txO.textSize = size
        c.drawText(s, x, y, txO); c.drawText(s, x, y, tx)
    }

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val cx = width / 2f; val cy = height / 2f
        val sc = (height / (250f * d)).coerceIn(0.55f, 1f)
        val strip = canStrip && !failed && each > 0
        // بدون إطارات: الوقت في النص بالظبط
        val base = if (strip) cy else cy + 30f * d * sc
        if (strip) drawStrip(c, cx, cy + 56f * d * sc, sc)
        text(c, time, cx, base - 30f * d * sc, 40f * d * sc)
        if (sub.isNotEmpty()) text(c, sub, cx, base - 4f * d * sc, 15f * d * sc)
    }

    private fun drawStrip(c: Canvas, cx: Float, cy: Float, sc: Float) {
        val h = 64f * d * sc
        val w = h * aspect
        val e = each
        val centre = (centreMs / e).toInt()
        val reach = (cx / w).toInt() + 2
        reachN = reach
        val maxI = ((durMs - 1L).coerceAtLeast(0L) / e).toInt()
        for (k in -reach..reach) {
            val i = centre + k
            if (i < 0 || i > maxI || k == 0) continue
            val x = cx + ((i + 0.5f) * e - centreMs) / e * w
            frame(c, i, x - w / 2f, cy - h / 2f, w - 2f * d, h)
        }
        val big = 1.3f
        val bx = cx + ((centre + 0.5f) * e - centreMs) / e * w
        frame(c, centre, bx - w * big / 2f, cy - h * big / 2f, w * big, h * big)
        c.drawRect(dst, edge)
    }

    private fun frame(c: Canvas, index: Int, l: Float, t: Float, w: Float, h: Float) {
        dst.set(l, t, l + w, t + h)
        if (dst.right < 0f || dst.left > width) return
        val b = cache.get(index)
        if (b == null) c.drawRect(dst, fill) else c.drawBitmap(b, null, dst, bmpP)
    }
}
