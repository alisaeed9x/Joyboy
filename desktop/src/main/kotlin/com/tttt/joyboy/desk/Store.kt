package com.tttt.joyboy.desk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** إعدادات وحالة التطبيق على اللابتوب (~/.joyboy-desk) */
object Store {
    val home = File(System.getProperty("user.home"), ".joyboy-desk").also { it.mkdirs() }
    private val cfgFile = File(home, "config.json")
    private val remFile = File(home, "remotes.json")
    private var cfg = try { JSONObject(cfgFile.readText()) } catch (_: Throwable) { JSONObject() }

    @Synchronized private fun save() { try { cfgFile.writeText(cfg.toString()) } catch (_: Throwable) {} }

    val id: String get() = synchronized(this) { if (!cfg.has("id")) { cfg.put("id", UUID.randomUUID().toString().take(8)); save() }; cfg.getString("id") }
    var name: String
        get() = synchronized(this) { cfg.optString("name", "").ifBlank { try { java.net.InetAddress.getLocalHost().hostName } catch (_: Throwable) { "Windows" } } }
        set(v) { synchronized(this) { cfg.put("name", v); save() } }

    /** فولدر الملفات الواصلة (فيديوهات/صوتيات/صور/غيره بحسب النوع) */
    val inbox: File get() = File(System.getProperty("user.home"), "Joyboy").also { it.mkdirs() }

    fun shared(): List<File> {
        val l = ArrayList<File>()
        l.add(inbox)
        val vids = File(System.getProperty("user.home"), "Videos"); if (vids.isDirectory) l.add(vids)
        val dl = File(System.getProperty("user.home"), "Downloads"); if (dl.isDirectory) l.add(dl)
        val a = cfg.optJSONArray("shared") ?: JSONArray()
        for (i in 0 until a.length()) { val f = File(a.optString(i)); if (f.isDirectory && l.none { it.absolutePath == f.absolutePath }) l.add(f) }
        return l
    }
    fun addShared(f: File) { synchronized(this) { val a = cfg.optJSONArray("shared") ?: JSONArray().also { cfg.put("shared", it) }; a.put(f.absolutePath); save() } }

    class Remote(val id: String, var name: String, var token: String) {
        @Volatile var lastSeen = 0L
        @Volatile var ip = ""
        @Volatile var announced = false
        fun alive() = lastSeen > 0 && System.currentTimeMillis() - lastSeen < 12000L
    }
    val remotes = java.util.concurrent.ConcurrentHashMap<String, Remote>()
    init {
        try {
            val a = JSONArray(remFile.readText())
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); remotes[o.getString("id")] = Remote(o.getString("id"), o.optString("name", "موبايل"), o.getString("token")) }
        } catch (_: Throwable) {}
    }
    @Synchronized fun saveRemotes() {
        try { val a = JSONArray(); for (r in remotes.values) a.put(JSONObject().put("id", r.id).put("name", r.name).put("token", r.token)); remFile.writeText(a.toString()) } catch (_: Throwable) {}
    }
    fun hex(n: Int): String { val b = ByteArray(n); SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) } }

    fun localIps(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().map { it.hostAddress }
    } catch (_: Throwable) { emptyList() }
}

object Cat {
    private val VID = setOf("mp4", "mkv", "webm", "avi", "3gp", "ts", "mov", "m4v", "flv", "wmv", "mpg", "mpeg")
    private val AUD = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "amr", "mka")
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    fun of(name: String): String { val e = name.substringAfterLast('.', "").lowercase(); return when { e in VID -> "video"; e in AUD -> "audio"; e in IMG -> "image"; e == "apk" -> "apk"; else -> "other" } }
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
    fun safeName(n: String): String {
        val b = n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trim('.')
        return if (b.isEmpty()) "file" else b.take(120)
    }
    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists() && !File(dir, "$name.part").exists()) return f
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
        var i = 1
        while (i < 1000) { f = File(dir, "$base ($i)" + (if (ext.isEmpty() || ext == name) "" else ".$ext")); if (!f.exists()) return f; i++ }
        return File(dir, System.currentTimeMillis().toString() + "_" + name)
    }
    fun dirFor(cat: String): File = File(Store.inbox, when (cat) { "video" -> "Videos"; "audio" -> "Music"; "image" -> "Pictures"; "apk" -> "APK"; else -> "Files" }).also { it.mkdirs() }
}
