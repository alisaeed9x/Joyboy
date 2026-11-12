package com.tttt.subtitler

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * (v607) مركز النقل — التصنيفات: فيديوهات · صوتيات · صور · APK · تطبيقات مثبتة.
 * الملفات الواصلة بتتحفظ في فولدر لكل نوع (Xfer.inboxDir)، والإرسال/الاستقبال بين الموبايل والتلفزيون وبين التطبيق وأي تطبيق تاني.
 */
object XCat {
    const val VIDEO = "video"; const val AUDIO = "audio"; const val IMAGE = "image"; const val APK = "apk"; const val APP = "app"; const val OTHER = "other"
    /** تصنيفات الإرسال/الاستقبال (التطبيقات المثبتة نوع افتراضي — الوصول لـ APK بتاعها) */
    val SEND: List<String> = listOf(VIDEO, AUDIO, IMAGE, APK, APP)
    /** تصنيفات الفولدرات الواصلة (التطبيقات بتتحفظ جوه APK) */
    val INBOX: List<String> = listOf(VIDEO, AUDIO, IMAGE, APK, OTHER)

    fun label(c: String): String = when (c) {
        VIDEO -> "🎬 فيديوهات"; AUDIO -> "🎵 صوتيات"; IMAGE -> "🖼 صور"; APK -> "📦 APK"; APP -> "📱 تطبيقات مثبتة"; else -> "📄 ملفات تانية"
    }
    fun emoji(c: String): String = when (c) { VIDEO -> "🎬"; AUDIO -> "🎵"; IMAGE -> "🖼"; APK -> "📦"; APP -> "📱"; else -> "📄" }

    private val VID = setOf("mp4", "mkv", "webm", "avi", "3gp", "ts", "mov", "m4v", "flv", "wmv", "mpg", "mpeg")
    private val AUD = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "amr", "mka")
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val APKS = setOf("apk", "apks", "xapk", "apkm")

    fun catOf(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return when { e in VID -> VIDEO; e in AUD -> AUDIO; e in IMG -> IMAGE; e in APKS -> APK; else -> OTHER }
    }
    fun isApk(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in APKS
}

/** عنصر في الكتالوج (ملف أو تطبيق) */
class XEntry(
    val cat: String, val key: String, val name: String, val size: Long,
    val uri: Uri? = null, val file: File? = null, val pkg: String = "", val sub: String = "", val date: Long = 0L, val splits: Boolean = false,
    val path: String = ""   // (v672) المسار الفعلي لو معروف (MediaStore DATA) — لفتح الفولدر الأب في اختيار الملفات
)

/** ملف جاهز للإرسال (التطبيقات اللي ليها splits بتتحوّل لـ .apks في الكاش) */
class SendFile(val name: String, val uri: Uri, val size: Long, val cat: String)

object FileCatalog {
    /** اللي اتعرض عن بُعد — السيرفر مابيقدّمش غير مفاتيح اتسجّلت هنا (مفيش وصول لمسارات عشوائية) */
    private val served = ConcurrentHashMap<String, XEntry>()
    fun served(key: String): XEntry? = served[key]
    fun register(e: XEntry) { served[e.key] = e }

    private fun hkey(s: String): String = try {
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(14)
    } catch (_: Throwable) { Integer.toHexString(s.hashCode()) }

    /** عنصر كتالوج من ملف عادي (مثلًا من الواصل) */
    fun fileEntry(f: File, cat: String): XEntry = XEntry(cat, "f:" + hkey(f.absolutePath + f.length()), f.name, f.length(), Uri.fromFile(f), f, "", f.parentFile?.name ?: "", f.lastModified() / 1000L)

    fun list(ctx: Context, cat: String): List<XEntry> = try {
        when (cat) {
            XCat.VIDEO, XCat.AUDIO, XCat.IMAGE -> media(ctx, cat)
            XCat.APK -> apkFiles(ctx)
            XCat.APP -> apps(ctx)
            else -> emptyList()
        }
    } catch (e: Throwable) { LogStore.err("Catalog:$cat", e); emptyList() }

    private fun media(ctx: Context, cat: String): List<XEntry> {
        val coll = when (cat) {
            XCat.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            XCat.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        @Suppress("DEPRECATION") val dataCol = MediaStore.MediaColumns.DATA
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, dataCol, MediaStore.MediaColumns.DATE_ADDED)
        val out = ArrayList<XEntry>()
        ctx.contentResolver.query(coll, proj, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext() && out.size < 4000) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val size = c.getLong(2)
                if (size <= 0L) continue
                val data = c.getString(4) ?: ""
                val folder = data.substringBeforeLast('/', "").substringAfterLast('/')
                // (v672) التاريخ = تاريخ الإضافة للجهاز (DATE_ADDED) لو موجود وإلا تاريخ التعديل
                val added = c.getLong(5)
                out.add(XEntry(cat, "m:$cat:$id", name, size, ContentUris.withAppendedId(coll, id), null, "", folder, if (added > 0L) added else c.getLong(3), false, data))
            }
        }
        return out
    }

    private fun apkFiles(ctx: Context): List<XEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<XEntry>()
        fun add(f: File) {
            val len = f.length()
            if (len <= 0L || !seen.add(f.absolutePath)) return
            out.add(XEntry(XCat.APK, "f:" + hkey(f.absolutePath + len), f.name, len, Uri.fromFile(f), f, "", f.parentFile?.name ?: "", f.lastModified() / 1000L))
        }
        fun walk(d: File?, depth: Int) {
            if (d == null || depth < 0 || out.size >= 800) return
            val ls = try { d.listFiles() } catch (_: Throwable) { null } ?: return
            for (f in ls) {
                if (out.size >= 800) return
                if (f.isDirectory) { if (!f.name.startsWith(".") && f.name != "Android") walk(f, depth - 1) }
                else if (XCat.isApk(f.name)) add(f)
            }
        }
        try { walk(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 3) } catch (_: Throwable) {}
        try { walk(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), 3) } catch (_: Throwable) {}
        try { walk(Xfer.inboxDir(ctx, XCat.APK), 2) } catch (_: Throwable) {}
        try { walk(Environment.getExternalStorageDirectory(), 0) } catch (_: Throwable) {}
        return out.sortedByDescending { it.date }
    }

    private fun apps(ctx: Context): List<XEntry> {
        val pm = ctx.packageManager
        val out = ArrayList<XEntry>()
        for (ai in pm.getInstalledApplications(0)) {
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (system) continue
            val src = ai.sourceDir ?: continue
            val base = File(src)
            if (!base.exists()) continue
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Throwable) { ai.packageName }
            val ver = try { pm.getPackageInfo(ai.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
            val splits = (ai.splitSourceDirs ?: emptyArray<String>()).map { File(it) }.filter { it.exists() }
            val total = base.length() + splits.sumOf { it.length() }
            val nm = Xfer.safeName(label + (if (ver.isNotEmpty()) "_$ver" else "")).let { it + (if (splits.isNotEmpty()) ".apks" else ".apk") }
            out.add(XEntry(XCat.APP, "p:" + ai.packageName, nm, total, null, base, ai.packageName, ver, 0L, splits.isNotEmpty()))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** يجهّز العنصر للإرسال: ملف عادي ← Uri · تطبيق بدون splits ← APK · تطبيق بـsplits ← ملف .apks (zip) في الكاش */
    fun materialize(ctx: Context, e: XEntry): SendFile? {
        try {
            if (e.cat == XCat.APP) {
                val ai = ctx.packageManager.getApplicationInfo(e.pkg, 0)
                val base = File(ai.sourceDir)
                val splits = (ai.splitSourceDirs ?: emptyArray<String>()).map { File(it) }.filter { it.exists() }
                if (splits.isEmpty()) return SendFile(e.name, Uri.fromFile(base), base.length(), XCat.APK)
                val dir = File(ctx.cacheDir, "xfer_tmp"); dir.mkdirs()
                val z = File(dir, e.name)
                java.util.zip.ZipOutputStream(FileOutputStream(z)).use { zo ->
                    zo.setLevel(0)
                    for (f in listOf(base) + splits) {
                        zo.putNextEntry(java.util.zip.ZipEntry(if (f == base) "base.apk" else f.name))
                        FileInputStream(f).use { it.copyTo(zo) }
                        zo.closeEntry()
                    }
                }
                return SendFile(e.name, Uri.fromFile(z), z.length(), XCat.APK)
            }
            val u = e.uri ?: return null
            val sz = if (e.size > 0L) e.size else Xfer.sizeOf(ctx, u)
            return SendFile(e.name, u, sz, e.cat)
        } catch (t: Throwable) { LogStore.err("Catalog:mat", t); return null }
    }

    fun cleanTmp(ctx: Context) { try { File(ctx.cacheDir, "xfer_tmp").listFiles()?.forEach { it.delete() } } catch (_: Throwable) {} }
}

/** (التلفزيون) الملفات اللي المستخدم «عرضها» للموبايل — الموبايل بيشوفها في استقبال ← عروض */
object TvOutbox {
    private val l = CopyOnWriteArrayList<XEntry>()
    fun add(items: List<XEntry>) { for (e in items) { FileCatalog.register(e); if (l.none { it.key == e.key }) l.add(0, e) } }
    fun all(): List<XEntry> = l.toList()
    fun clear() = l.clear()
}

/** (التلفزيون) واجهة الكتالوج اللي الموبايل بيتصفحها: /xfer/list?cat=… و /file/any?k=… */
object TvCatalogApi {
    private fun err(code: Int) = HttpRes.json(JSONObject().put("ok", false).toString(), code)

    fun list(app: Context, r: HttpReq): HttpRes {
        val cat = r.query["cat"] ?: XCat.VIDEO
        val items = if (cat == "outbox") TvOutbox.all() else FileCatalog.list(app, cat)
        val arr = JSONArray()
        for (e in items) {
            FileCatalog.register(e)
            arr.put(JSONObject().put("k", e.key).put("n", e.name).put("s", if (e.splits) 0L else e.size).put("g", e.sub).put("c", e.cat))
        }
        return HttpRes.json(JSONObject().put("ok", true).put("items", arr).toString())
    }

    fun file(app: Context, r: HttpReq): HttpRes {
        val e = FileCatalog.served(r.query["k"] ?: "") ?: return err(404)
        val sf = FileCatalog.materialize(app, e) ?: return err(404)
        if (sf.size <= 0L) return err(404)
        return HttpRes.stream(UriSrc(app, sf.uri, sf.size, sf.name), Xfer.mimeOf(sf.name))
    }
}

/** تنزيل ملف من رابط (Range + استكمال لو الاتصال قطع) على فولدر نوعه — بيتنادى من مهمة في TaskCenter */
object XNet {
    fun download(app: Context, t: TaskItem, url: String, headers: Map<String, String>, name: String, size0: Long, cat: String, subdir: String = "", quiet: Boolean = false): File {
        val dir = if (subdir.isEmpty()) Xfer.inboxDir(app, cat) else File(Xfer.inboxDir(app, XCat.OTHER), subdir).also { it.mkdirs() }
        val out = Xfer.uniqueFile(dir, name)
        val part = File(dir, out.name + ".part")
        part.delete()
        var total = size0
        var tries = 0
        while (true) {
            if (t.cancelled) { part.delete(); throw TaskCancelled() }
            var c: HttpURLConnection? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                c = conn
                conn.connectTimeout = 8000; conn.readTimeout = 15000; conn.useCaches = false
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                val have = part.length()
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("الجهاز التاني رد بـ $code")
                val append = code == 206 && have > 0
                if (!append && have > 0) part.delete()
                if (total <= 0L) total = (if (append) have else 0L) + conn.contentLengthLong
                FileOutputStream(part, append).use { fo ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(131072)
                        var got = if (append) have else 0L
                        var last = 0L
                        while (true) {
                            if (t.cancelled) throw TaskCancelled()
                            val n = ins.read(buf)
                            if (n < 0) break
                            fo.write(buf, 0, n); got += n
                            val now = System.currentTimeMillis()
                            if (!quiet && now - last > 400L) {
                                last = now
                                t.pct = if (total > 0L) ((got * 100) / total).toInt().coerceIn(0, 99) else 0
                                t.msg = Sniff.fmtSize(got) + (if (total > 0L) " / " + Sniff.fmtSize(total) else "")
                                TaskCenter.changed()
                            }
                        }
                    }
                }
                if (total > 0L && part.length() < total) throw IOException("الملف ناقص")
                break
            } catch (e: TaskCancelled) { part.delete(); throw e
            } catch (e: IOException) {
                tries++
                if (tries >= 6) throw e
                try { Thread.sleep(1200) } catch (_: InterruptedException) {}
            } finally { try { c?.disconnect() } catch (_: Throwable) {} }
        }
        if (!part.renameTo(out)) throw IOException("ما قدرتش أحفظ الملف")
        if (!quiet) { t.outPath = out.absolutePath; t.outSize = out.length(); t.msg = "" }
        try { android.media.MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), null, null) } catch (_: Throwable) {}
        try { VideoScan.cache = null } catch (_: Throwable) {}
        return out
    }
}

/** (الموبايل) تصفح كتالوج التلفزيون وتنزيل منه */
object XRemote {
    class Item(val key: String, val name: String, val size: Long, val sub: String, val cat: String)

    /** blocking — من خيط خلفي. الاتنين: القايمة، ورسالة خطأ (null = تمام) */
    fun list(ctx: Context, cat: String): Pair<List<Item>, String?> {
        if (!RemoteClient.ensureConnected(ctx, 8000L)) return Pair(emptyList(), "ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي وإنه مقترن")
        val (c, t) = RemoteClient.getText("/xfer/list?cat=" + URLEncoder.encode(cat, "UTF-8"), 25000)
        if (c == 404) return Pair(emptyList(), "التلفزيون محتاج تحديث للنسخة دي")
        if (c != 200) return Pair(emptyList(), "ما قدرتش أجيب القايمة ($c)")
        val out = ArrayList<Item>()
        try {
            val a = JSONObject(t).optJSONArray("items") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Item(o.optString("k"), o.optString("n"), o.optLong("s"), o.optString("g"), o.optString("c")))
            }
        } catch (e: Throwable) { LogStore.err("XRemote:list", e); return Pair(emptyList(), "رد التلفزيون مش مفهوم") }
        return Pair(out, null)
    }

    class PathJob(val path: String, val name: String, val size: Long, val subdir: String)

    /** تنزيل ملفات بمساراتها من تصفح ملفات التلفزيون: مهمة واحدة بشريط تقدم إجمالي (مجلد كامل = مهمة واحدة) */
    fun downloadPaths(ctx: Context, title: String, jobs: List<PathJob>): Boolean {
        val tv = RemoteClient.current ?: return false
        if (jobs.isEmpty()) return false
        val ver = LinkVer.code(ctx).toString()
        val hdr = mapOf("X-Token" to tv.token, "X-Ver" to ver)
        TaskCenter.add(ctx, "dm", "📥 $title") { t ->
            var done = 0
            var fails = 0
            for (j in jobs) {
                if (t.cancelled) throw TaskCancelled()
                val url = "http://" + tv.ip + ":" + tv.port + "/fs/file?path=" + URLEncoder.encode(j.path, "UTF-8")
                t.msg = "ملف " + (done + 1) + "/" + jobs.size + " · " + j.name
                t.pct = (done * 100 / jobs.size).coerceIn(0, 99)
                TaskCenter.changed()
                try { XNet.download(ctx, t, url, hdr, Xfer.safeName(j.name), j.size, XCat.catOf(j.name), j.subdir, true) }
                catch (e: TaskCancelled) { throw e }
                catch (e: Throwable) { fails++; LogStore.err("XRemote:path", e) }
                done++
            }
            t.pct = 100; t.msg = if (fails > 0) "خلص — $fails ملف فشل" else "خلص (" + jobs.size + " ملف)"
            TaskCenter.changed()
        }
        return true
    }

    /** تنزيل مجموعة عناصر من التلفزيون — كل واحد مهمة في مركز المهام (شريط تقدم + إلغاء) */
    fun download(ctx: Context, items: List<Item>): Boolean {
        val tv = RemoteClient.current ?: return false
        val ver = LinkVer.code(ctx).toString()
        for (x in items) {
            val url = "http://" + tv.ip + ":" + tv.port + "/file/any?k=" + URLEncoder.encode(x.key, "UTF-8")
            val name = Xfer.safeName(x.name)
            val cat = if (x.cat == XCat.APP) XCat.APK else x.cat.ifEmpty { XCat.catOf(name) }
            TaskCenter.add(ctx, "dm", "📥 $name") { t ->
                XNet.download(ctx, t, url, mapOf("X-Token" to tv.token, "X-Ver" to ver), name, x.size, cat)
            }
        }
        return true
    }
}


/** (التلفزيون) تصفح ملفات التلفزيون: /fs/list?path=… و /fs/file?path=… — محصور في الذاكرة الداخلية والفلاشات (الموبايل المقترن بس) */
object TvFsApi {
    private fun roots(): List<File> {
        val out = ArrayList<File>()
        try { out.add(Environment.getExternalStorageDirectory()) } catch (_: Throwable) {}
        try { File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }?.forEach { out.add(it) } } catch (_: Throwable) {}
        return out
    }
    private fun canon(f: File): String? = try { f.canonicalPath } catch (_: Throwable) { null }
    private fun allowed(f: File): Boolean {
        val c = canon(f) ?: return false
        return roots().any { r -> val rc = canon(r); rc != null && (c == rc || c.startsWith("$rc/")) }
    }
    private fun err(code: Int) = HttpRes.json(JSONObject().put("ok", false).toString(), code)

    fun list(app: Context, r: HttpReq): HttpRes {
        val path = r.query["path"] ?: ""
        val arr = JSONArray()
        val access = if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        if (path.isEmpty()) {
            for (rt in roots()) {
                val internal = rt.absolutePath == (try { Environment.getExternalStorageDirectory().absolutePath } catch (_: Throwable) { "" })
                arr.put(JSONObject().put("n", if (internal) "الذاكرة الداخلية" else "💾 " + rt.name).put("d", true).put("s", 0L).put("m", 0L).put("p", rt.absolutePath))
            }
            return HttpRes.json(JSONObject().put("ok", true).put("path", "").put("parent", "").put("access", access).put("items", arr).toString())
        }
        val d = File(path)
        if (!allowed(d)) return err(403)
        if (!d.isDirectory) return err(404)
        val hidden = r.query["hidden"] == "1"
        val ls = (d.listFiles() ?: emptyArray<File>()).filter { hidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).take(3000)
        for (f in ls) arr.put(JSONObject().put("n", f.name).put("d", f.isDirectory).put("s", if (f.isFile) f.length() else 0L).put("m", f.lastModified() / 1000L).put("p", f.absolutePath))
        val isRoot = roots().any { canon(it) == canon(d) }
        val parent = if (isRoot) "" else (d.parentFile?.absolutePath ?: "")
        return HttpRes.json(JSONObject().put("ok", true).put("path", d.absolutePath).put("parent", parent).put("access", access).put("items", arr).toString())
    }

    fun file(app: Context, r: HttpReq): HttpRes {
        val f = File(r.query["path"] ?: "")
        if (!allowed(f)) return err(403)
        if (!f.isFile || f.length() <= 0L) return err(404)
        return HttpRes.stream(UriSrc(app, Uri.fromFile(f), f.length(), f.name), Xfer.mimeOf(f.name))
    }
}

/** تجميع ملفات من مجلدات (بالتكرار) بحد أقصى */
object XExpand {
    fun files(roots: List<File>, cap: Int = 300): List<File> {
        val out = ArrayList<File>()
        fun walk(f: File, depth: Int) {
            if (out.size >= cap || depth > 8) return
            if (f.isFile) { if (f.length() > 0L) out.add(f); return }
            val ls = try { f.listFiles() } catch (_: Throwable) { null } ?: return
            for (c in ls.sortedBy { it.name.lowercase() }) { if (out.size >= cap) return; if (!c.name.startsWith(".")) walk(c, depth + 1) }
        }
        for (r in roots) walk(r, 0)
        return out
    }
}

/** استقبال ملفات من تطبيق تاني (مشاركة) أو من منتقي الملفات: نسخ لفولدر النوع بشريط تقدم */
object XIn {
    fun save(app: Context, uris: List<Uri>) {
        for (u in uris) {
            val (n0, size) = XferUi.meta(app, u)
            val name = Xfer.safeName(n0)
            TaskCenter.add(app, "dm", "💾 $name") { t ->
                val cat = XCat.catOf(name)
                val out = Xfer.uniqueFile(Xfer.inboxDir(app, cat), name)
                val ins = (if (u.scheme == "file") FileInputStream(File(u.path ?: "")) else app.contentResolver.openInputStream(u)) ?: throw IOException("مش قادر أفتح الملف")
                try {
                    ins.use { src ->
                        FileOutputStream(out).use { fo ->
                            val buf = ByteArray(131072)
                            var got = 0L; var last = 0L
                            while (true) {
                                if (t.cancelled) { out.delete(); throw TaskCancelled() }
                                val n = src.read(buf)
                                if (n < 0) break
                                fo.write(buf, 0, n); got += n
                                val now = System.currentTimeMillis()
                                if (now - last > 400L) {
                                    last = now
                                    t.pct = if (size > 0L) ((got * 100) / size).toInt().coerceIn(0, 99) else 0
                                    t.msg = Sniff.fmtSize(got) + (if (size > 0L) " / " + Sniff.fmtSize(size) else "")
                                    TaskCenter.changed()
                                }
                            }
                        }
                    }
                } catch (e: TaskCancelled) { out.delete(); throw e }
                t.outPath = out.absolutePath; t.outSize = out.length(); t.msg = ""
                try { android.media.MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), null, null) } catch (_: Throwable) {}
                try { VideoScan.cache = null } catch (_: Throwable) {}
                Notice.show(app, "✅ اتحفظ في " + out.parentFile?.name + ": " + out.name, 3000L)
            }
        }
    }
}
