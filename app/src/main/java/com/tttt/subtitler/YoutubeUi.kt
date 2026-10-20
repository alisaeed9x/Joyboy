package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** فيديو يوتيوب اتفتح قبل كده (v117): المعرّف (11 حرف) + الاسم + آخر مرة اتفتح (v407: + القناة + اتفتح كصوت بس؟) */
class YtItem(val id: String, val title: String, val ts: Long, val chan: String = "", val audio: Boolean = false)

/** سجل فيديوهات يوتيوب (yt_history.json): بيتسجل من المتصفح ومن بوابة يوتيوب، والمعرّف بيتحوّل للينك قابل للنسخ */
object YtHistory {
    private val lock = Any()
    private const val MAX = 600   // (v407) سجل يوتيوب المنفصل: 600 بدل 300
    private fun file(ctx: Context) = File(ctx.filesDir, "yt_history.json")
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun read(ctx: Context): List<YtItem> = try {
        val a = JSONArray(file(ctx).readText().ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { YtItem(it.optString("id"), it.optString("t"), it.optLong("ts", 0), it.optString("c"), it.optBoolean("a", false)) }
            .filter { it.id.length == 11 }
    } catch (_: Exception) { emptyList() }

    private fun write(ctx: Context, l: List<YtItem>) {
        try {
            val a = JSONArray()
            for (x in l.take(MAX)) a.put(JSONObject().put("id", x.id).put("t", x.title).put("ts", x.ts).put("c", x.chan).put("a", x.audio))
            file(ctx).writeText(a.toString())
        } catch (e: Exception) { LogStore.err("YtHistory", e) }
    }

    fun load(ctx: Context): List<YtItem> = synchronized(lock) { read(ctx).sortedByDescending { it.ts } }

    /** نفس الفيديو بيطلع فوق، وبيحتفظ باسمه/قناته القديمة لو الجديدة فاضية. (v407) بيحترم مفتاح «حفظ سجل المشاهدة» */
    fun add(ctx: Context, id: String, title: String, chan: String = "", audio: Boolean = false) {
        if (id.length != 11) return
        try { if (!YtStore.histOn()) return } catch (_: Throwable) {}
        synchronized(lock) {
            val l = read(ctx)
            val old = l.firstOrNull { it.id == id }
            val t = title.trim().ifEmpty { old?.title ?: "" }
            val c = chan.trim().ifEmpty { old?.chan ?: "" }
            write(ctx, listOf(YtItem(id, t, System.currentTimeMillis(), c, audio)) + l.filter { it.id != id })
        }
    }

    fun remove(ctx: Context, id: String) { synchronized(lock) { write(ctx, read(ctx).filter { it.id != id }) } }
    fun clear(ctx: Context) { synchronized(lock) { try { file(ctx).delete() } catch (_: Exception) {} } }
}

/** صور مصغّرة ليوتيوب (بتتحمّل مرة وتتخزّن في الجهاز) */
object YtThumbs {
    private val cache = android.util.LruCache<String, Bitmap>(60)
    private val main = Handler(Looper.getMainLooper())
    private fun f(ctx: Context, id: String) = File(File(ctx.filesDir, "ytthumbs"), "$id.jpg")
    fun peek(id: String): Bitmap? = cache.get(id)
    fun load(ctx: Context, id: String, cb: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var b: Bitmap? = null
            try {
                val file = f(app, id)
                if (file.exists()) b = BitmapFactory.decodeFile(file.path)
                if (b == null) {
                    val c = java.net.URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    val bytes = c.inputStream.use { it.readBytes() }
                    c.disconnect()
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (b != null) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
                }
            } catch (e: Throwable) { LogStore.err("YtThumbs", e) }
            val res = b
            if (res != null) cache.put(id, res)
            main.post { cb(res) }
        }.apply { isDaemon = true }.start()
    }
}
