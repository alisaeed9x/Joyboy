package com.vanilla.tvlauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.concurrent.Executors

/** حالة المكتبة + الفحص التدريجي بـ MediaStore (FR-LIB-1..3). الفحص كله في Thread، والنتائج بترجع على الـ Main. */
object LibraryStore {
    @Volatile var items: List<VideoItem> = emptyList()
    @Volatile var scanning = false
    @Volatile var scanned = 0
    @Volatile var lastScanAt = 0L
    @Volatile var error: String? = null
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())

    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "library.v1.txt")
    fun loadCache(ctx: Context) {
        if (items.isNotEmpty()) return
        try { val f = cacheFile(ctx); if (f.isFile) items = LibraryLogic.decode(f.readText()) } catch (_: Throwable) { }
    }
    private fun saveCache(ctx: Context, l: List<VideoItem>) {
        try { val f = cacheFile(ctx); val tmp = File(f.path + ".tmp"); val txt = LibraryLogic.encode(l); tmp.writeText(txt); if (!tmp.renameTo(f)) { f.writeText(txt); tmp.delete() } } catch (_: Throwable) { }
    }

    /** onUpdate بيتنادى على الـ Main (تقدّم جزئي كل ~300 فيديو ثم نتيجة نهائية) */
    fun scan(ctx: Context, onUpdate: () -> Unit) {
        if (!hasPermission(ctx)) { error = "no_permission"; scanning = false; onUpdate(); return }
        val my = ++gen; scanning = true; scanned = 0; error = null
        val app = ctx.applicationContext
        Thread {
            val out = ArrayList<VideoItem>()
            try {
                val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_ID,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE)
                val cur = app.contentResolver.query(base, proj, null, null, MediaStore.Video.Media.DATE_MODIFIED + " DESC")
                if (cur != null) cur.use { c ->
                    while (c.moveToNext()) {
                        if (gen != my) return@Thread   // فحص أحدث بدأ → ده قديم
                        val id = c.getLong(0)
                        out.add(VideoItem(id, ContentUris.withAppendedId(base, id).toString(), c.getString(1) ?: "video_$id",
                            c.getString(2) ?: "?", c.getString(3) ?: "غير معروف", c.getLong(4) * 1000L, c.getLong(5), c.getLong(6)))
                        if (out.size % 300 == 0) { val snap = ArrayList(out); main.post { if (gen == my) { items = snap; scanned = snap.size; onUpdate() } } }
                    }
                }
                val fin = LibraryLogic.dedupeById(out)
                saveCache(app, fin)
                main.post { if (gen == my) { items = fin; scanned = fin.size; scanning = false; lastScanAt = System.currentTimeMillis(); onUpdate() } }
            } catch (e: Throwable) {
                main.post { if (gen == my) { scanning = false; error = e.message ?: e.javaClass.simpleName; onUpdate() } }
            }
        }.apply { isDaemon = true }.start()
    }

    // تحديث تلقائي لما MediaStore يتغيّر (debounce 3 ثواني)
    private var obs: ContentObserver? = null
    private var pending: Runnable? = null
    fun watch(ctx: Context, onChange: () -> Unit) {
        unwatch(ctx)
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                pending?.let { main.removeCallbacks(it) }
                val r = Runnable { onChange() }; pending = r; main.postDelayed(r, 3000L)
            }
        }
        try { ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, o); obs = o } catch (_: Throwable) { }
    }
    fun unwatch(ctx: Context) {
        pending?.let { main.removeCallbacks(it) }; pending = null
        obs?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (_: Throwable) { } }; obs = null
    }
}

/** صور مصغّرة بتتحمّل بالتدريج في خلفية (2 threads) مع كاش LRU */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(60)
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun load(ctx: Context, v: VideoItem, w: Int, h: Int, cb: (Bitmap?) -> Unit) {
        cache.get(v.uri)?.let { cb(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 29) app.contentResolver.loadThumbnail(Uri.parse(v.uri), Size(w, h), null)
                else MediaStore.Video.Thumbnails.getThumbnail(app.contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } catch (_: Throwable) { null }
            if (bmp != null) cache.put(v.uri, bmp)
            main.post { cb(bmp) }
        }
    }
}
