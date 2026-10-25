package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * (v543 م3) مكتبة فيديوهات التلفزيون على الموبايل، فوق /lib/* (قرار علي: الفيديوهات على التلفزيون، الموبايل يعرضها بس).
 * كل الشبكة على خيوط خلفية، والـcallbacks بترجع على الـUI. مفيش أي شبكة على الـUI thread.
 * الصور المصغّرة: كاش ذاكرة + كاش ملفات في cacheDir/tvthumbs.
 */
object RemoteLibrary {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbPool = Executors.newFixedThreadPool(2)

    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(2 * 1024 * 1024)) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }
    private val noThumb = HashSet<String>()

    @Volatile private var folderCache: List<RFolder>? = null
    private val videoCache = HashMap<String, List<RVideo>>()

    fun cachedFolders(): List<RFolder>? = folderCache
    fun cachedVideos(key: String): List<RVideo>? = synchronized(videoCache) { videoCache[key] }

    /** فصل الكاش (تلفزيون تاني / تحديث) */
    fun clear() {
        folderCache = null
        synchronized(videoCache) { videoCache.clear() }
        synchronized(noThumb) { noThumb.clear() }
        mem.evictAll()
    }

    private fun failText(code: Int, body: String): String = when {
        code < 0 -> "ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
        code == 403 && LibraryCore.errOf(body) == "perm" -> "التلفزيون محتاج صلاحية «الفيديوهات» — اديها له من إعدادات التلفزيون"
        else -> "فشل ($code)"
    }

    /** cb(list, null) أو cb(null, رسالة الخطأ) على الـUI */
    fun folders(ctx: Context, refresh: Boolean, cb: (List<RFolder>?, String?) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            var res: List<RFolder>? = null; var err: String? = null
            try {
                if (!RemoteClient.ensureConnected(app, 8000L)) err = failText(-1, "")
                else {
                    val (c, b) = RemoteClient.getText(if (refresh) "/lib/folders?refresh=1" else "/lib/folders", 20000)
                    if (c == 200) {
                        val l = LibraryCore.parseFolders(b)
                        if (l == null) err = "رد غير مفهوم من التلفزيون" else { res = LibraryCore.foldersNewestFirst(l); folderCache = res }
                    } else err = failText(c, b)
                }
            } catch (e: Throwable) { LogStore.err("RemoteLibrary:folders", e); err = "حصلت مشكلة" }
            main.post { cb(res, err) }
        }
    }

    fun videos(ctx: Context, folderKey: String, cb: (List<RVideo>?, String?) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            var res: List<RVideo>? = null; var err: String? = null
            try {
                if (!RemoteClient.ensureConnected(app, 8000L)) err = failText(-1, "")
                else {
                    val q = "/lib/videos?folder=" + java.net.URLEncoder.encode(folderKey, "UTF-8")
                    val (c, b) = RemoteClient.getText(q, 20000)
                    if (c == 200) {
                        val l = LibraryCore.parseVideos(b)
                        if (l == null) err = "رد غير مفهوم من التلفزيون" else { res = LibraryCore.videosNewestFirst(l); synchronized(videoCache) { videoCache[folderKey] = res } }
                    } else err = failText(c, b)
                }
            } catch (e: Throwable) { LogStore.err("RemoteLibrary:videos", e); err = "حصلت مشكلة" }
            main.post { cb(res, err) }
        }
    }

    private fun tvId(): String = try { RemoteClient.current?.id ?: RemoteClient.lastTv()?.id ?: "x" } catch (_: Throwable) { "x" }
    private fun key(v: RVideo) = tvId() + ":" + v.id + ":" + v.size

    /** من الذاكرة بس، من غير شبكة */
    fun peekThumb(v: RVideo): Bitmap? = mem.get(key(v))

    /** cb(bitmap أو null) على الـUI. null = مفيش صورة للفيديو ده (أو التلفزيون مش متصل) */
    fun thumb(ctx: Context, v: RVideo, cb: (Bitmap?) -> Unit) {
        val k = key(v)
        mem.get(k)?.let { cb(it); return }
        if (synchronized(noThumb) { k in noThumb }) { cb(null); return }
        val app = ctx.applicationContext
        val id = tvId()
        thumbPool.execute {
            var bmp: Bitmap? = null
            try {
                val dir = File(app.cacheDir, "tvthumbs").also { it.mkdirs() }
                val f = File(dir, LibraryCore.thumbFile(id, v))
                if (f.exists() && f.length() > 0L) bmp = BitmapFactory.decodeFile(f.absolutePath)
                if (bmp == null) {
                    val (c, b) = RemoteClient.getBytes("/lib/thumb?id=" + v.id, 10000)
                    if (c == 200 && b.isNotEmpty()) {
                        bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                        if (bmp != null) try { f.writeBytes(b) } catch (_: Throwable) {}
                    } else if (c == 204 || c == 404) {
                        synchronized(noThumb) { noThumb.add(k) }
                    }
                }
                if (bmp != null) mem.put(k, bmp)
            } catch (e: Throwable) { LogStore.err("RemoteLibrary:thumb", e) }
            val out = bmp
            main.post { cb(out) }
        }
    }
}
