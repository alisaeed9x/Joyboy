package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * (v598 م3) سجل باكات الأيقونات — الجزء اللي بيعتمد على أندرويد (التخزين · التبديل الحي · الاستيراد/التصدير).
 * المحرك نفسه (قراءة SVG والتحقق) في IconPackCore.kt، والرسم في Icons.kt.
 *
 *  - الباك «الافتراضي» (default) = الأيقونات المرسومة بالكود (الشكل الحالي) — مفيش ملف.
 *  - 4 باكات مدمجة (ملفات zip في assets/iconpacks): outline · filled · duotone · rounded.
 *  - باكات المستخدم: filesDir/iconpacks (ملف لكل id) (بتتحفظ زي ما اتستوردت بالظبط).
 *  - الباك مستقل عن سكِن الواجهة: Icons.draw بيسأل IconPacks.active() أيًا كان السكِن، وأي أيقونة ناقصة في الباك بترجع للافتراضي.
 *  - الاختيار محفوظ في Cfg «icon_pack»؛ التبديل حي (المستمعين بيتندهوا على UI thread فالشاشة تتعاد رسمها من غير ريستارت).
 */
object IconPacks {
    const val DEFAULT = "default"
    private const val KEY = "icon_pack"
    const val DEFAULT_NAME = "الافتراضي (الحالي)"

    /** الباكات المدمجة بالترتيب + اسمها (مطابق لـ pack.json جوه كل zip — بيتأكد منه الاختبار) */
    val BUILTIN: List<Pair<String, String>> get() = IconPackReader.BUILTIN

    class Info(val id: String, val name: String, val builtin: Boolean, val author: String)
    class ImportResult(val report: PackReport, val id: String?, val name: String?) { val ok: Boolean get() = report.ok && id != null }

    @Volatile private var app: Context? = null
    @Volatile private var act: IconPackData? = null
    @Volatile private var actId: String = DEFAULT
    private val loaded = ConcurrentHashMap<String, IconPackData>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** الباك النشط (null = الافتراضي أو لسه بيتحمّل) — بيتنده من Icons.draw فمفيش تقل هنا */
    fun active(): IconPackData? = act
    fun activeId(): String = actId

    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun removeOnChange(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() {
        val run = Runnable { for (l in listeners) try { l() } catch (_: Throwable) {} }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    /** بيتنده مرة من Cfg.init: يقرا الاختيار المحفوظ ويحمّل الباك في الخلفية (لحد ما يجهز بيترسم الافتراضي) */
    fun attach(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val id = try { Cfg.str(KEY, DEFAULT) } catch (_: Throwable) { DEFAULT }
        actId = id
        if (id != DEFAULT) Thread {
            val p = loadPack(id)
            if (p != null) { act = p; notifyChanged() }
            else { actId = DEFAULT; try { Cfg.put(KEY, DEFAULT) } catch (_: Throwable) {} }   // الباك اتمسح/باظ ← رجوع للافتراضي
        }.also { it.isDaemon = true }.start()
    }

    // ===== القايمة =====
    fun list(): List<Info> {
        val out = ArrayList<Info>()
        out.add(Info(DEFAULT, DEFAULT_NAME, true, ""))
        for ((id, nm) in BUILTIN) out.add(Info(id, nm, true, "Joyboy"))
        val dir = userDir()
        dir?.listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.sortedBy { it.name }?.forEach { f ->
            val id = f.name.removeSuffix(".zip")
            if (id in BUILTIN.map { it.first } || id == DEFAULT) return@forEach
            val pk = IconPackReader.peek(try { f.readBytes() } catch (_: Throwable) { return@forEach })
            out.add(Info(id, pk?.first ?: id, false, pk?.second ?: ""))
        }
        return out
    }

    fun nameOf(id: String): String = list().firstOrNull { it.id == id }?.name ?: id

    private fun userDir(): File? = app?.let { File(it.filesDir, "iconpacks").also { d -> d.mkdirs() } }

    private fun bytesOf(id: String): ByteArray? {
        val c = app ?: return null
        return try {
            if (BUILTIN.any { it.first == id }) c.assets.open("iconpacks/$id.zip").use { it.readBytes() }
            else File(userDir(), "$id.zip").takeIf { it.isFile }?.readBytes()
        } catch (e: Throwable) { LogStore.err("IconPacks:read:$id", e); null }
    }

    private fun loadPack(id: String): IconPackData? {
        loaded[id]?.let { return it }
        val b = bytesOf(id) ?: return null
        val r = IconPackReader.read(b, builtin = BUILTIN.any { it.first == id }, expectId = id)
        if (r.pack == null) { LogStore.err("IconPacks:load:$id", RuntimeException(r.report.toText(6))); return null }
        loaded[id] = r.pack
        return r.pack
    }

    /** تبديل حي: تحميل (لو لازم) في الخلفية ← تطبيق + حفظ + إعادة رسم. done(true/false) على UI thread */
    fun select(id: String, done: (Boolean) -> Unit = {}) {
        if (id == DEFAULT) {
            act = null; actId = DEFAULT; try { Cfg.put(KEY, DEFAULT) } catch (_: Throwable) {}
            notifyChanged(); main.post { done(true) }; return
        }
        Thread {
            val p = loadPack(id)
            main.post {
                if (p == null) done(false)
                else { act = p; actId = id; try { Cfg.put(KEY, id) } catch (_: Throwable) {}; notifyChanged(); done(true) }
            }
        }.also { it.isDaemon = true }.start()
    }

    // ===== الاستيراد =====
    /** فحص + حفظ. بيشتغل على الـthread اللي بيناديه (نادّيه من خلفية). التقرير دايمًا بيتعرض للمستخدم. */
    fun importBytes(bytes: ByteArray): ImportResult {
        val r = IconPackReader.read(bytes)
        val pk = r.pack
        if (pk == null || !r.report.ok) return ImportResult(r.report, null, null)
        val dir = userDir() ?: run { r.report.err(null, "مفيش مكان تخزين متاح"); return ImportResult(r.report, null, null) }
        try {
            val tmp = File(dir, pk.id + ".zip.tmp"); tmp.writeBytes(bytes)
            val dst = File(dir, pk.id + ".zip")
            if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw RuntimeException("rename") }
        } catch (e: Throwable) { r.report.err(null, "ماقدرتش أحفظ الباك (" + (e.message ?: "") + ")"); return ImportResult(r.report, null, null) }
        loaded[pk.id] = pk
        if (actId == pk.id) { act = pk; notifyChanged() }   // استبدال باك شغّال
        return ImportResult(r.report, pk.id, pk.name)
    }

    /** قراءة zip من Uri (اختيار من نظام الملفات) بحد حجم */
    fun readUri(ctx: Context, uri: Uri): ByteArray? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val bos = ByteArrayOutputStream(); val buf = ByteArray(16384); var tot = 0
                while (true) { val n = ins.read(buf); if (n < 0) break; tot += n; if (tot > IconPackReader.MAX_ZIP_BYTES + 1) return null; bos.write(buf, 0, n) }
                bos.toByteArray()
            }
        } catch (e: Throwable) { LogStore.err("IconPacks:readUri", e); null }
    }

    /** ملفات zip جاهزة للاستيراد في الأماكن المعتادة (من غير ما نعمل فولدرات) — الأحدث أولًا */
    fun inboxZips(ctx: Context): List<File> {
        val dirs = ArrayList<File?>()
        try { dirs.add(ctx.getExternalFilesDir("iconpacks")) } catch (_: Throwable) {}
        try { dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Joyboy")) } catch (_: Throwable) {}
        try { dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) } catch (_: Throwable) {}
        try { dirs.add(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)) } catch (_: Throwable) {}
        val seen = HashSet<String>(); val out = ArrayList<File>()
        for (d in dirs) {
            val fs = try { d?.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".zip") && f.length() in 100..IconPackReader.MAX_ZIP_BYTES.toLong() } } catch (_: Throwable) { null } ?: continue
            for (f in fs) if (seen.add(f.absolutePath)) out.add(f)
        }
        return out.sortedByDescending { it.lastModified() }.take(30)
    }

    // ===== الحذف والتصدير =====
    fun delete(id: String): Boolean {
        if (id == DEFAULT || BUILTIN.any { it.first == id }) return false
        val ok = try { File(userDir(), "$id.zip").delete() } catch (_: Throwable) { false }
        loaded.remove(id)
        if (actId == id) select(DEFAULT)
        return ok
    }

    private fun outDir(ctx: Context): File = (ctx.getExternalFilesDir("iconpacks") ?: File(ctx.filesDir, "iconpacks_out")).also { it.mkdirs() }

    /** قالب جاهز للتعديل (الأيقونات الافتراضية كـ SVG + pack.json) → فولدر iconpacks بتاع التطبيق. بيرجّع الملف */
    fun exportTemplate(ctx: Context): File? = try {
        File(outDir(ctx), "joyboy-icon-pack-template.zip").also { it.writeBytes(IconPackWriter.template()) }
    } catch (e: Throwable) { LogStore.err("IconPacks:template", e); null }

    /** نسخة من باك موجود (مدمج أو مستورد) كملف zip قابل للتعديل/المشاركة */
    fun exportPack(ctx: Context, id: String): File? {
        if (id == DEFAULT) return exportTemplate(ctx)
        val b = bytesOf(id) ?: return null
        return try { File(outDir(ctx), "$id.zip").also { it.writeBytes(b) } } catch (e: Throwable) { LogStore.err("IconPacks:export", e); null }
    }
}
