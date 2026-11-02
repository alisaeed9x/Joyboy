package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

/**
 * (v547 م3) مصدر المكتبة اللي LibraryUi بتقرا منه: الموبايل (LocalLibrary فوق VideoScan/Thumbs)
 * أو التلفزيون (TvLibrary فوق RemoteLibrary و /lib/…). الواجهة بتشتغل بـVideoItem في الحالتين،
 * وفيديو التلفزيون uri بتاعه tv://id (التلفزيون هو اللي بيحلّه لملفه).
 * كل الـcallbacks بترجع على الـUI thread.
 */
interface LibraryBackend {
    /** true = الفيديوهات على التلفزيون (مفيش عمليات ملفات عليها من الموبايل) */
    val remote: Boolean

    /** آخر نتيجة محفوظة من غير شبكة/فحص، أو null */
    fun cached(): List<VideoItem>?

    /** يحمّل القايمة كلها. cb(list, null) أو cb(null, رسالة الخطأ) */
    fun load(ctx: Context, refresh: Boolean, cb: (List<VideoItem>?, String?) -> Unit)

    /** من الذاكرة بس */
    fun peekThumb(v: VideoItem): Bitmap?

    /** cb(bitmap أو null) */
    fun thumb(ctx: Context, v: VideoItem, audio: Boolean, cb: (Bitmap?) -> Unit)

    fun clearThumbs()

    /** يمسح الكاش عشان التحميل الجاي يبقى من الأول */
    fun invalidate()
}

/** المكتبة المحلية: نفس سلوك الفحص والصور المصغّرة القديم بالظبط (VideoScan + Thumbs) */
object LocalLibrary : LibraryBackend {
    private val main = Handler(Looper.getMainLooper())

    override val remote: Boolean = false

    override fun cached(): List<VideoItem>? = VideoScan.cache

    override fun load(ctx: Context, refresh: Boolean, cb: (List<VideoItem>?, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val res = try { VideoScan.scan(app) } catch (_: Exception) { emptyList<VideoItem>() }
            main.post { cb(res, null) }
        }.apply { isDaemon = true }.start()
    }

    override fun peekThumb(v: VideoItem): Bitmap? = Thumbs.peek(v)

    override fun thumb(ctx: Context, v: VideoItem, audio: Boolean, cb: (Bitmap?) -> Unit) = Thumbs.load(ctx, v, audio, cb)

    override fun clearThumbs() = Thumbs.clear()

    override fun invalidate() { VideoScan.cache = null }
}

/** مكتبة التلفزيون: فولدرات التلفزيون وفيديوهاته متحوّلة لـVideoItem (uri = tv://id) */
object TvLibrary : LibraryBackend {
    @Volatile private var cache: List<VideoItem>? = null

    override val remote: Boolean = true

    override fun cached(): List<VideoItem>? = cache

    override fun load(ctx: Context, refresh: Boolean, cb: (List<VideoItem>?, String?) -> Unit) {
        RemoteLibrary.folders(ctx, refresh) { folders, err ->
            if (folders == null) { cb(null, err ?: "حصلت مشكلة"); return@folders }
            if (folders.isEmpty()) { cache = emptyList(); cb(emptyList(), null); return@folders }
            val out = ArrayList<VideoItem>()
            var left = folders.size
            var firstErr: String? = null
            for (f in folders) {
                RemoteLibrary.videos(ctx, f.key) { vids, e ->
                    if (vids != null) {
                        for (v in vids) out.add(toItem(f, v))
                    } else if (firstErr == null) {
                        firstErr = e
                    }
                    left--
                    if (left == 0) {
                        if (out.isEmpty() && firstErr != null) cb(null, firstErr)
                        else { val res = out.toList(); cache = res; cb(res, null) }
                    }
                }
            }
        }
    }

    private fun toItem(f: RFolder, v: RVideo): VideoItem =
        VideoItem(v.id, LibraryCore.tvUri(v.id), v.name, "tv:" + f.key, f.name, f.path, v.size, v.durMs, v.added, v.modified, v.w, v.h)

    private fun toR(v: VideoItem): RVideo = RVideo(v.id, v.name, v.title, v.size, v.durMs, v.added, v.modified, v.w, v.h, "", v.ext)

    override fun peekThumb(v: VideoItem): Bitmap? = RemoteLibrary.peekThumb(toR(v))

    override fun thumb(ctx: Context, v: VideoItem, audio: Boolean, cb: (Bitmap?) -> Unit) = RemoteLibrary.thumb(ctx, toR(v), cb)

    override fun clearThumbs() = RemoteLibrary.clear()

    override fun invalidate() { cache = null; RemoteLibrary.clear() }
}
