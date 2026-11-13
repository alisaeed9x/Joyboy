package com.tttt.joyboy.desk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** مهام النقل/التحميل الظاهرة في الواجهة */
class Task(val name: String) {
    @Volatile var pct = 0
    @Volatile var msg = "…"
    @Volatile var done = false
    @Volatile var failed = false
    @Volatile var cancelled = false
    @Volatile var file: File? = null
}

object Tasks {
    val list = CopyOnWriteArrayList<Task>()
    var onChange: () -> Unit = {}
    fun add(t: Task) { list.add(0, t); onChange() }
    fun changed() = onChange()
}

object Xfer {
    fun fmt(n: Long): String = when { n >= 1L shl 30 -> "%.2f GB".format(n / 1073741824.0); n >= 1L shl 20 -> "%.1f MB".format(n / 1048576.0); n >= 1024 -> "%d KB".format(n / 1024); else -> "$n B" }

    // ---------- الموبايل بيبعت ملف: اللابتوب بيسحبه (/pull) ----------
    fun pull(r: Link.Req) {
        val j = r.json()
        val url = j.optString("url"); val name = Cat.safeName(j.optString("name")); val size = j.optLong("size")
        val cat = j.optString("cat").ifEmpty { Cat.of(name) }
        val host = try { URL(url).host } catch (_: Throwable) { "" }
        if (!url.startsWith("http://") || host.isEmpty() || host != r.peer) return Link.send(r.ex, 403, JSONObject().put("ok", false).put("err", "src"))
        val t = Task("📥 $name"); Tasks.add(t)
        Thread { download(t, url, emptyMap(), name, size, Cat.dirFor(cat)) }.also { it.isDaemon = true }.start()
        Link.onNotice("📥 بيوصل ملف من الموبايل: $name")
        Link.send(r.ex, 200, JSONObject().put("ok", true))
    }

    /** تحميل بـRange واستكمال لو الاتصال قطع (بيشتغل لملفات الموبايل ولينكات النت) */
    fun download(t: Task, url: String, headers: Map<String, String>, name: String, size0: Long, dir: File): File? {
        dir.mkdirs()
        val out = Cat.unique(dir, name)
        val part = File(dir, out.name + ".part"); part.delete()
        var total = size0; var tries = 0
        while (true) {
            if (t.cancelled) { part.delete(); t.failed = true; t.msg = "اتلغى"; Tasks.changed(); return null }
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000; c.useCaches = false
                for ((k, v) in headers) c.setRequestProperty(k, v)
                val have = part.length()
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code != 200 && code != 206) throw IOException("السيرفر رد بـ $code")
                val append = code == 206 && have > 0
                if (!append && have > 0) part.delete()
                if (total <= 0L) total = (if (append) have else 0L) + c.contentLengthLong
                FileOutputStream(part, append).use { fo ->
                    c.inputStream.use { ins ->
                        val buf = ByteArray(131072); var got = if (append) have else 0L; var last = 0L; val t0 = System.currentTimeMillis()
                        while (true) {
                            if (t.cancelled) throw IOException("cancelled")
                            val n = ins.read(buf); if (n < 0) break
                            fo.write(buf, 0, n); got += n
                            val now = System.currentTimeMillis()
                            if (now - last > 400) {
                                last = now
                                t.pct = if (total > 0) ((got * 100) / total).toInt().coerceIn(0, 99) else 0
                                val sp = got * 1000 / maxOf(1L, now - t0)
                                t.msg = fmt(got) + (if (total > 0) " / " + fmt(total) else "") + "  •  " + fmt(sp) + "/s"
                                Tasks.changed()
                            }
                        }
                    }
                }
                if (total > 0 && part.length() < total) throw IOException("الملف ناقص")
                break
            } catch (e: IOException) {
                if (t.cancelled || e.message == "cancelled") { part.delete(); t.failed = true; t.msg = "اتلغى"; Tasks.changed(); return null }
                if (++tries > 12) { t.failed = true; t.msg = "فشل: " + (e.message ?: ""); Tasks.changed(); return null }
                t.msg = "الاتصال قطع — بحاول تاني ($tries)"; Tasks.changed()
                try { Thread.sleep(1500) } catch (_: Exception) {}
            }
        }
        if (!part.renameTo(out)) { part.copyTo(out, true); part.delete() }
        t.pct = 100; t.done = true; t.file = out; t.msg = "تم ✓ " + fmt(out.length()); Tasks.changed()
        return out
    }

    // ---------- اللابتوب بيقدّم ملفاته للموبايل (/xfer/list + /file/any) ----------
    private val served = java.util.concurrent.ConcurrentHashMap<String, File>()
    private fun key(f: File) = "f:" + Integer.toHexString((f.absolutePath + f.length()).hashCode())
    /** تسجيل ملف عشان الموبايل يسحبه بـ/file/any?k=… */
    fun register(f: File): String { val k = key(f); served[k] = f; return k }

    private fun scan(cat: String): List<File> {
        val out = ArrayList<File>(); val seen = HashSet<String>()
        fun walk(d: File, depth: Int) {
            if (depth < 0 || out.size >= 4000) return
            val ls = try { d.listFiles() } catch (_: Throwable) { null } ?: return
            for (f in ls) {
                if (f.isDirectory) { if (!f.name.startsWith(".")) walk(f, depth - 1) }
                else if (f.length() > 0 && !f.name.endsWith(".part") && Cat.of(f.name) == cat && seen.add(f.absolutePath)) out.add(f)
            }
        }
        for (d in Store.shared()) walk(d, 3)
        return out.sortedByDescending { it.lastModified() }
    }

    fun list(r: Link.Req) {
        val cat = r.query["cat"] ?: "video"
        val arr = JSONArray()
        if (cat != "app" && cat != "outbox") for (f in scan(cat)) {
            val k = key(f); served[k] = f
            arr.put(JSONObject().put("k", k).put("n", f.name).put("s", f.length()).put("g", f.parentFile?.name ?: "").put("c", cat))
        }
        Link.send(r.ex, 200, JSONObject().put("ok", true).put("items", arr))
    }

    fun file(r: Link.Req) {
        val f = served[r.query["k"] ?: ""]
        if (f == null || !f.isFile) return Link.send(r.ex, 404, JSONObject().put("ok", false).put("err", "nf"))
        Link.serveFile(r.ex, f, Cat.mime(f.name))
    }

    // ---------- مدير تحميل: لينك من النت ----------
    fun downloadUrl(url: String, dirOverride: File? = null) {
        val u = url.trim()
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return
        val guess = try { java.net.URLDecoder.decode(URL(u).path.substringAfterLast('/'), "UTF-8") } catch (_: Throwable) { "" }
        val direct = guess.contains('.') && Cat.of(guess) != "other"
        val t = Task("⬇ " + (guess.ifBlank { u.take(60) })); Tasks.add(t)
        Thread {
            if (direct) { download(t, u, emptyMap(), Cat.safeName(guess), 0L, dirOverride ?: Cat.dirFor(Cat.of(guess))) }
            else ytdlp(t, u, dirOverride ?: Cat.dirFor("video"))
        }.also { it.isDaemon = true }.start()
    }

    /** صفحات فيديو (يوتيوب وغيره): بيستخدم yt-dlp لو موجود جنب البرنامج أو في الـPATH */
    private fun ytdlp(t: Task, url: String, dir: File) {
        try {
            val exe = listOf(File("yt-dlp.exe"), File(Store.home, "yt-dlp.exe")).firstOrNull { it.isFile }?.absolutePath ?: "yt-dlp"
            val pb = ProcessBuilder(exe, "-o", File(dir, "%(title).120s.%(ext)s").absolutePath, "--newline", "--no-playlist", url).redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine { l ->
                if (t.cancelled) { p.destroy(); return@forEachLine }
                val m = Regex("""\[download]\s+([\d.]+)%""").find(l)
                if (m != null) { t.pct = m.groupValues[1].toDouble().toInt().coerceIn(0, 99); t.msg = l.substringAfter("] ").take(70); Tasks.changed() }
            }
            val rc = p.waitFor()
            if (rc == 0) { t.pct = 100; t.done = true; t.msg = "تم ✓"; } else { t.failed = true; t.msg = "فشل yt-dlp (كود $rc) — لازم yt-dlp.exe يكون جنب البرنامج" }
        } catch (e: Throwable) { t.failed = true; t.msg = "yt-dlp مش موجود — حطّ yt-dlp.exe في ${Store.home}" }
        Tasks.changed()
    }

    // ---------- اللابتوب بيبعت ملف للموبايل: بيعرضه للموبايل في «مركز النقل» (يظهر تلقائي من الكتالوج) ----------
    fun share(f: File) { Store.addShared(f.parentFile ?: return) }
}
