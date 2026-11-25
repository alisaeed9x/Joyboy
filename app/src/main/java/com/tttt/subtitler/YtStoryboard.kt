package com.tttt.subtitler

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * (v1306) معاينة الصور على شريط التقدم (storyboard) + رسم «الأكثر إعادة تشغيل» (heatmap):
 * طلب Innertube player واحد لكل فيديو بيجيب:
 *  - storyboards.playerStoryboardSpecRenderer.spec: لوحات صور مجمّعة (كل لوحة فيها شبكة كادرات)
 *  - markersMap.heatMarkers: نقاط «اتشافت أكتر» بدرجة 0..100 (لو يوتيوب رجّعها — مش كل الفيديوهات)
 * النتايج متخزّنة في الذاكرة لكل فيديو، واللوحات المحمّلة في LruCache.
 */
object YtStoryboard {
    class Board(val url: String, val w: Int, val h: Int, val count: Int, val cols: Int, val rows: Int, val stepMs: Long)
    class Data(val boards: List<Board>, val heat: FloatArray?)

    private const val ORIGIN = "https://www.youtube.com"
    private const val API_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val cache = HashMap<String, Data?>()
    private val bmCache = LruCache<String, Bitmap>(10)
    @Volatile private var inflight: String? = null

    fun data(vid: String): Data? = synchronized(cache) { if (cache.containsKey(vid)) cache[vid] else null }

    /** جلب في الخلفية لو مش متخزّن — بينادي onDone على خيط الخلفية لما يخلص (بنجاح أو لا) */
    fun prefetch(vid: String, onDone: (() -> Unit)? = null) {
        if (vid.isBlank()) return
        synchronized(cache) { if (cache.containsKey(vid)) return }
        if (inflight == vid) return
        inflight = vid
        Thread {
            val d = try { fetch(vid) } catch (_: Throwable) { null }
            synchronized(cache) { cache[vid] = d }
            inflight = null
            try { onDone?.invoke() } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }

    /** كادر المعاينة للحظة ms (أقرب لوح، مقصوص من الشبكة) — null لو لسه بيتحمّل أو مش متاح */
    fun frame(vid: String, ms: Long): Bitmap? {
        val d = data(vid) ?: return null
        if (d.boards.isEmpty()) return null
        // أدنى لوح بيغطي اللحظة دي (وإلا أكبر لوح)
        var b = d.boards.firstOrNull { ms < it.count * it.stepMs } ?: d.boards.last()
        val idx = (ms / b.stepMs).toInt().coerceIn(0, b.count - 1)
        val perBoard = b.cols * b.rows
        if (perBoard <= 0) return null
        val boardNo = idx / perBoard
        val tile = idx % perBoard
        val url = b.url.replace("\$L", boardNo.toString()).replace("\$N", "M$boardNo")
        val full = boardBitmap(url) ?: return null
        val tx = (tile % b.cols) * b.w; val ty = (tile / b.cols) * b.h
        if (tx + b.w > full.width || ty + b.h > full.height) return null
        return try { Bitmap.createBitmap(full, tx, ty, b.w, b.h) } catch (_: Throwable) { null }
    }

    private fun boardBitmap(url: String): Bitmap? {
        bmCache.get(url)?.let { return it }
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", API_UA)
            val bm = BitmapFactory.decodeStream(c.inputStream)
            if (bm != null) bmCache.put(url, bm)
            bm
        } catch (_: Throwable) { null }
    }

    private fun fetch(vid: String): Data? {
        val ctx = JSONObject().put("client", JSONObject()
            .put("clientName", "ANDROID").put("clientVersion", "19.09.37")
            .put("androidSdkVersion", 30).put("hl", "ar").put("gl", "EG"))
        val body = JSONObject().put("context", ctx).put("videoId", vid)
            .put("contentCheckOk", true).put("racyCheckOk", true)
        val c = URL("$ORIGIN/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 12000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", API_UA)
            c.setRequestProperty("Origin", ORIGIN)
            c.setRequestProperty("X-Youtube-Client-Name", "3")
            c.setRequestProperty("X-Youtube-Client-Version", "19.09.37")
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            var ins = if (c.responseCode >= 400) c.errorStream else c.inputStream
            if (ins != null && "gzip".equals(c.contentEncoding, true)) ins = GZIPInputStream(ins)
            val txt = ins?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
            val j = JSONObject(txt)

            // ── storyboards ──
            val boards = ArrayList<Board>()
            val spec = j.optJSONObject("storyboards")?.optJSONObject("playerStoryboardSpecRenderer")?.optString("spec", "") ?: ""
            if (spec.isNotBlank()) {
                for (part in spec.split("\\|".toRegex())) {
                    val seg = part.split("#")
                    if (seg.size < 6) continue
                    val url = seg[0]
                    val w = seg.getOrNull(1)?.toIntOrNull() ?: continue
                    val h = seg.getOrNull(2)?.toIntOrNull() ?: continue
                    val count = seg.getOrNull(3)?.toIntOrNull() ?: continue
                    val cols = seg.getOrNull(4)?.toIntOrNull() ?: continue
                    val rows = seg.getOrNull(5)?.toIntOrNull() ?: continue
                    // الفاصل الزمني بين الكادرات بالمللي ثانية — 0 معناه «متساوي على مدة الفيديو»، بنستبعده
                    val step = seg.getOrNull(6)?.toLongOrNull() ?: 0L
                    if (w > 0 && h > 0 && count > 0 && cols > 0 && rows > 0 && step > 0) boards.add(Board(url, w, h, count, cols, rows, step))
                }
                boards.sortBy { it.w }
            }

            // ── heatmap «الأكثر إعادة» ──
            var heat: FloatArray? = null
            val hm = j.optJSONObject("markersMap")?.optJSONArray("heatMarkers")
            if (hm != null && hm.length() > 3) {
                val pts = ArrayList<Pair<Long, Float>>()
                for (i in 0 until hm.length()) {
                    val r = hm.optJSONObject(i)?.optJSONObject("heatMarkerRenderer") ?: continue
                    val t = r.optLong("timeRangeStartMillis", -1L)
                    var score = r.optDouble("heatScore", -1.0)
                    if (score < 0) score = r.optDouble("maxHeatScore", -1.0)
                    if (t >= 0 && score >= 0) pts.add(t to score.toFloat())
                }
                if (pts.size > 3) {
                    val maxT = pts.last().first.coerceAtLeast(1L)
                    val maxS = pts.maxOf { it.second }.coerceAtLeast(0.01f)
                    val arr = FloatArray(48)
                    for ((t, s) in pts) {
                        val slot = ((t * 48L) / maxT).toInt().coerceIn(0, 47)
                        arr[slot] = maxOf(arr[slot], s / maxS)
                    }
                    heat = arr
                }
            }
            return Data(boards, heat)
        } finally {
            try { c.disconnect() } catch (_: Throwable) {}
        }
    }
}
