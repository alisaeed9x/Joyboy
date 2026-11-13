package com.tttt.subtitler

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * (v819) تحميل بأكتر من مسار (زي IDM / ADM): الملف بيتقسّم لقطع، وكل مسار بيسحب قطعة بـ Range في نفس الوقت.
 *  - بيكمّل من اللي اتحمّل: حالة كل قطعة بتتحفظ جنب الملف (.meta) فلو التحميل فشل/اتوقف/التطبيق اتقفل بيرجع من نفس المكان
 *  - الإلغاء بيقطع الاتصالات الجارية فورًا (مفيش استنى 30 ثانية للـ readTimeout)
 *  - لو السيرفر مش بيدعم Range أو الحجم مش معروف: الطلب بيرجع للتحميل العادي (Downloader.copyTo / ResumableDl)
 */
object SegDl {
    class Probe(val total: Long, val ranges: Boolean)

    /** عدد المسارات (إعداد «dl_conns»، الافتراضي 4، من 1 لـ 8) */
    fun conns(): Int = try { Cfg.int("dl_conns", 4).coerceIn(1, 8) } catch (_: Throwable) { 4 }

    /** أقل حجم يستاهل التقسيم */
    const val MIN_SIZE = 2_000_000L

    fun dir(ctx: Context): File { val d = File(ctx.filesDir, "dlpart"); d.mkdirs(); return d }

    fun key(vararg parts: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(parts.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /** الحجم + هل السيرفر بيدعم Range (طلب bytes=0-0 لازم يرد 206 بـ Content-Range) */
    fun probe(url: String, headers: Map<String, String>): Probe {
        var c: HttpURLConnection? = null
        try {
            c = req(url, headers, "bytes=0-0")
            val code = c.responseCode
            if (code == 206) {
                val t = (c.getHeaderField("Content-Range") ?: "").substringAfter('/', "").trim().toLongOrNull() ?: -1L
                return Probe(t, t > 0)
            }
            val n = c.contentLengthLong
            return Probe(if (n > 0) n else -1L, false)
        } catch (_: Throwable) { return Probe(-1L, false) } finally { try { c?.disconnect() } catch (_: Throwable) {} }
    }

    private fun req(url: String, headers: Map<String, String>, range: String): HttpURLConnection {
        val c = Downloader.open(url, headers, "GET", range)
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    /** بيمسح ملفات التحميل الناقصة اللي عدّى عليها أكتر من 3 أيام + مخلفات الكاش (بيتنادى مرة عند فتح التطبيق) */
    fun sweep(ctx: Context) {
        try {
            val lim = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            dir(ctx).listFiles()?.forEach { if (it.lastModified() < lim) it.delete() }
            for (n in listOf("dl", "tasks")) File(ctx.cacheDir, n).listFiles()?.forEach { try { if (it.isDirectory) it.deleteRecursively() else it.delete() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    /**
     * بينزّل الملف كله في part (بيتكمّل لو موجود وسليم).
     * wait: بيتنادى في أول كل جولة وبيستنى لو متوقف مؤقتًا (وبيرمي TaskCancelled لو اتلغى).
     * progress: عدد البايتات المتحمّلة (كل ~250ms من خيط المهمة نفسه).
     */
    fun fetch(url: String, headers: Map<String, String>, part: File, total: Long, n: Int, ctl: Downloader.Ctl,
              wait: () -> Unit, progress: (Long) -> Unit) {
        val meta = File(part.path + ".meta")
        val csz = (total / (n * 6L)).coerceIn(512L * 1024, 8L * 1024 * 1024)
        val cnt = ((total + csz - 1) / csz).toInt()
        val done = AtomicLongArray(cnt)
        fun clen(i: Int): Long = minOf(csz, total - i * csz)

        var ok = false
        if (part.exists() && part.length() == total && meta.exists()) {
            try {
                val o = JSONObject(meta.readText())
                if (o.getString("u") == url.substringBefore('#') && o.getLong("t") == total && o.getLong("c") == csz) {
                    val a = o.getJSONArray("d")
                    if (a.length() == cnt) { for (i in 0 until cnt) done.set(i, a.getLong(i).coerceIn(0L, clen(i))); ok = true }
                }
            } catch (_: Throwable) {}
        }
        if (!ok) {
            try { part.delete() } catch (_: Throwable) {}
            RandomAccessFile(part, "rw").use { it.setLength(total) }
            for (i in 0 until cnt) done.set(i, 0L)
        }
        fun sum(): Long { var s = 0L; for (i in 0 until cnt) s += done.get(i); return s }
        fun complete(): Boolean { for (i in 0 until cnt) if (done.get(i) < clen(i)) return false; return true }
        fun saveMeta() {
            try {
                val a = JSONArray(); for (i in 0 until cnt) a.put(done.get(i))
                meta.writeText(JSONObject().put("u", url.substringBefore('#')).put("t", total).put("c", csz).put("d", a).toString())
            } catch (_: Throwable) {}
        }

        var rounds = 0
        while (true) {
            wait()
            if (ctl.stop()) throw TaskCancelled()
            if (complete()) break
            val fatal = AtomicReference<Throwable?>(null)
            val next = AtomicInteger(0)
            val workers = ArrayList<Thread>()
            val left = (0 until cnt).count { done.get(it) < clen(it) }
            for (w in 0 until minOf(n, left)) {
                val th = Thread {
                    while (true) {
                        if (ctl.stop() || ctl.paused || fatal.get() != null) return@Thread
                        val i = next.getAndIncrement(); if (i >= cnt) return@Thread
                        var tries = 0
                        while (done.get(i) < clen(i)) {
                            if (ctl.stop() || ctl.paused || fatal.get() != null) return@Thread
                            val from = i * csz + done.get(i); val to = i * csz + clen(i) - 1
                            var c: HttpURLConnection? = null
                            try {
                                c = req(url, headers, "bytes=$from-$to"); ctl.live.add(c)
                                val code = c.responseCode
                                if (code != 206) throw IOException(if (code == 200) "السيرفر وقف التحميل بمسارات" else "HTTP $code")
                                c.inputStream.use { ins ->
                                    RandomAccessFile(part, "rw").use { raf ->
                                        raf.seek(from)
                                        val buf = ByteArray(64 * 1024)
                                        while (done.get(i) < clen(i)) {
                                            if (ctl.stop() || ctl.paused) break
                                            val r = ins.read(buf, 0, minOf(buf.size.toLong(), clen(i) - done.get(i)).toInt())
                                            if (r < 0) break
                                            raf.write(buf, 0, r); done.addAndGet(i, r.toLong()); NetUse.media(r.toLong())
                                        }
                                    }
                                }
                                if (done.get(i) < clen(i) && !ctl.stop() && !ctl.paused) throw IOException("الاتصال اتقطع")
                                tries = 0
                            } catch (e: Throwable) {
                                if (ctl.stop() || ctl.paused) return@Thread
                                if (++tries > 5) { fatal.compareAndSet(null, e); return@Thread }
                                try { Thread.sleep(900L * tries) } catch (_: InterruptedException) { return@Thread }
                            } finally { if (c != null) { ctl.live.remove(c); try { c.disconnect() } catch (_: Throwable) {} } }
                        }
                    }
                }
                th.isDaemon = true; th.start(); workers.add(th)
            }
            var lastSave = System.currentTimeMillis()
            while (workers.any { it.isAlive }) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { ctl.cancelled = true }
                progress(sum())
                val now = System.currentTimeMillis()
                if (now - lastSave > 2000L) { lastSave = now; saveMeta() }
            }
            saveMeta(); progress(sum())
            if (ctl.stop()) throw TaskCancelled()
            fatal.get()?.let { if (!ctl.paused) throw (if (it is IOException) it else IOException(it.message ?: it.javaClass.simpleName)) }
            if (!ctl.paused && !complete()) { if (++rounds > 6) throw IOException("الاتصال اتقطع — اللي اتحمّل محفوظ، دوس ▶ استكمال"); Thread.sleep(1500) }
        }
        if (sum() != total) throw IOException("الحجم النهائي مش مطابق")
        try { meta.delete() } catch (_: Throwable) {}
    }
}
