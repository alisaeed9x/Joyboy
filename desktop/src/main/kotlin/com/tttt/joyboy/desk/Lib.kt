package com.tttt.joyboy.desk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/** مكتبة فيديوهات اللابتوب بنفس شكل مكتبة التلفزيون (/lib/folders · /lib/videos · /lib/thumb · /file/lib) — الموبايل يتصفّحها من نفس الشاشة */
object Lib {
    class V(val id: Long, val f: File) { val folderKey: String get() = crc(f.parentFile?.absolutePath ?: "").toString() }
    @Volatile private var cache: List<V> = emptyList()
    @Volatile private var cacheAt = 0L

    private fun crc(s: String): Long { val c = CRC32(); c.update(s.toByteArray(Charsets.UTF_8)); return c.value }

    private fun scan(force: Boolean): List<V> {
        if (!force && System.currentTimeMillis() - cacheAt < 20_000L && cache.isNotEmpty()) return cache
        val out = ArrayList<V>(); val seen = HashSet<String>()
        fun walk(d: File, depth: Int) {
            if (depth < 0 || out.size >= 6000) return
            val ls = try { d.listFiles() } catch (_: Throwable) { null } ?: return
            for (f in ls) {
                if (f.isDirectory) { if (!f.name.startsWith(".") && !f.name.equals("AppData", true)) walk(f, depth - 1) }
                else if (f.length() > 0 && Cat.of(f.name) == "video" && seen.add(f.absolutePath)) out.add(V(crc(f.absolutePath + f.length()), f))
            }
        }
        for (d in Store.shared()) walk(d, 4)
        cache = out; cacheAt = System.currentTimeMillis()
        return out
    }

    fun find(idOrKey: String): V? { val id = idOrKey.toLongOrNull() ?: return null; return scan(false).firstOrNull { it.id == id } }

    fun folders(r: Link.Req) {
        val l = scan(r.query["refresh"] == "1")
        val a = JSONArray()
        for ((k, vs) in l.groupBy { it.folderKey }) {
            val p = vs.first().f.parentFile
            a.put(JSONObject().put("key", k).put("name", p?.name ?: "—").put("path", p?.absolutePath ?: "").put("count", vs.size)
                .put("size", vs.sumOf { it.f.length() }).put("newest", vs.maxOf { it.f.lastModified() }))
        }
        Link.send(r.ex, 200, JSONObject().put("ok", true).put("folders", a))
    }

    fun videos(r: Link.Req) {
        val key = r.query["folder"] ?: ""
        val a = JSONArray()
        for (v in scan(false)) if (key.isEmpty() || v.folderKey == key) {
            val n = v.f.name
            a.put(JSONObject().put("id", v.id).put("name", n).put("title", n.substringBeforeLast('.', n)).put("size", v.f.length()).put("durMs", 0)
                .put("added", v.f.lastModified() / 1000).put("modified", v.f.lastModified() / 1000).put("w", 0).put("h", 0)
                .put("vid", "f:$n:${v.f.length()}").put("ext", n.substringAfterLast('.', "").lowercase()))
        }
        Link.send(r.ex, 200, JSONObject().put("ok", true).put("videos", a))
    }

    /** الصورة المصغّرة: لقطة بـffmpeg لو موجود (جنب البرنامج أو في الـPATH)، وإلا 204 والموبايل يعرض أيقونة */
    fun thumb(r: Link.Req) {
        val v = find(r.query["id"] ?: "")
        if (v == null) { r.ex.sendResponseHeaders(404, -1); return }
        val out = File(File(Store.home, "thumbs").also { it.mkdirs() }, "${v.id}.jpg")
        if (!out.exists()) {
            try {
                val exe = listOf(File("ffmpeg.exe"), File(Store.home, "ffmpeg.exe")).firstOrNull { it.isFile }?.absolutePath ?: "ffmpeg"
                val p = ProcessBuilder(exe, "-y", "-ss", "8", "-i", v.f.absolutePath, "-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "5", out.absolutePath)
                    .redirectErrorStream(true).start()
                p.inputStream.readBytes()
                if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
            } catch (_: Throwable) {}
        }
        if (out.exists() && out.length() > 0) {
            r.ex.responseHeaders.add("Content-Type", "image/jpeg")
            val b = out.readBytes(); r.ex.sendResponseHeaders(200, b.size.toLong()); r.ex.responseBody.use { it.write(b) }
        } else r.ex.sendResponseHeaders(204, -1)
    }

    /** تحميل فيديو من مكتبة اللابتوب على الموبايل (Range) */
    fun file(r: Link.Req) {
        val v = find(r.query["id"] ?: "")
        if (v == null || !v.f.isFile) return Link.send(r.ex, 404, JSONObject().put("ok", false).put("err", "nf"))
        Link.serveFile(r.ex, v.f, Cat.mime(v.f.name))
    }
}

/** طابور «ابعت للموبايل»: الموبايل بيشوفه في رد /ping (كل كام ثانية) وبيسحب الملف لوحده */
object Push {
    class Item(val id: String, val key: String, val name: String, val size: Long, val cat: String, val at: Long)
    private val items = java.util.concurrent.CopyOnWriteArrayList<Item>()
    fun send(f: File) {
        if (!f.isFile || f.length() <= 0) return
        val k = Xfer.register(f)
        items.add(Item(Store.hex(6), k, f.name, f.length(), Cat.of(f.name), System.currentTimeMillis()))
    }
    /** العناصر الحديثة (آخر 40 ثانية) — الموبايل بيشيل التكرار بالـid */
    fun pending(): JSONArray {
        val now = System.currentTimeMillis(); items.removeIf { now - it.at > 40_000L }
        val a = JSONArray()
        for (i in items) a.put(JSONObject().put("id", i.id).put("k", i.key).put("n", i.name).put("s", i.size).put("c", i.cat))
        return a
    }
}
