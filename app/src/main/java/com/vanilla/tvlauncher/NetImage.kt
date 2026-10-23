package com.vanilla.tvlauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** صور مصغّرة من الشبكة (يوتيوب) بتتحمّل في الخلفية (3 threads) مع كاش LRU. https بس. cb على الـ Main (null = فشل). */
object NetImage {
    private val cache = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r).apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, cb: (Bitmap?) -> Unit) {
        if (!url.startsWith("https://")) { cb(null); return }
        cache.get(url)?.let { cb(it); return }
        pool.execute {
            var c: HttpURLConnection? = null
            val bmp = try {
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Throwable) { null } finally { try { c?.disconnect() } catch (_: Throwable) { } }
            if (bmp != null) cache.put(url, bmp)
            main.post { cb(bmp) }
        }
    }
}
