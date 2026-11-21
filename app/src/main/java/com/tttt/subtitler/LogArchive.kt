package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v1167) سجل اللوجات — كل «تشغيلة» للفيديو (فتحة جديدة للفيديو، أو ترجمة من الأول وجديد على نفس الفيديو) بتتحفظ كنسخة مستقلة:
 *  • نص اللوج بتاع التشغيلة دي بس (مش لوج الجلسة كلها)
 *  • لقطة (snapshot) من الجمل اللي كانت موجودة/مترجمة وقت الحفظ — مش بتتأثر بالترجمات اللي بعدها
 *  • اسم الفيديو + مصدره + وقت التشغيلة (الصورة بتتجاب وقت العرض من الفيديو نفسه)
 * التخزين: filesDir/logarc/index.json (ميتا بس، للقايمة) + <id>.json (اللوج + الجمل) لكل تشغيلة.
 * ملحوظة: «ما طلعش مع ترجمة» بتتحسب وقت العرض من عدد الجمل المترجمة (مش بتتكتب جوه اللوج، عشان ماتفضلش غلط لو الترجمة جت بعدها).
 */
object LogArchive {
    /** تشغيلة شغّالة في الذاكرة (المشغّل بيضيف عليها أسطر اللوج وبيحفظها كل شوية) */
    class Run(val id: String, val vid: String, val title: String, val uri: String, val url: String, val ts: Long) {
        val log = StringBuilder()
    }

    class Meta(val id: String, val vid: String, val title: String, val uri: String, val url: String, val ts: Long,
               val subs: Int, val translated: Int, val lines: Int) {
        val noTranslation: Boolean get() = translated <= 0
    }

    class Entry(val meta: Meta, val log: String, val subs: List<Sub>)

    private const val MAX_LOG = 120_000
    private const val MAX_RUNS = 300
    private val lock = Any()
    private val seq = AtomicInteger(0)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }

    private fun dir(c: Context): File = File(c.applicationContext.filesDir, "logarc").also { it.mkdirs() }
    private fun idxFile(c: Context) = File(dir(c), "index.json")

    // ===================== التشغيلة الحالية =====================
    fun start(c: Context, vid: String, title: String, uri: String, url: String): Run {
        val now = System.currentTimeMillis()
        return Run(now.toString(36) + "_" + seq.incrementAndGet(), vid, title.ifBlank { Recents.titleOf(vid) }, uri, url, now)
    }

    fun append(run: Run?, line: String) {
        if (run == null) return
        synchronized(run.log) {
            run.log.append(line).append('\n')
            if (run.log.length > MAX_LOG) run.log.delete(0, MAX_LOG / 4)
        }
    }

    fun translatedCount(subs: List<Sub>): Int = subs.count { it.translated.isNotBlank() }

    // ===================== «شغّل بالترجمة دي» =====================
    // الشاشة بتسجّل اللوج المطلوب هنا قبل ما تفتح الفيديو (لينكات الويب بتفتح من طرق مختلفة من غير ما نمرّر extra)؛ المشغّل بياخده مرة واحدة لو الفيديو هو هو.
    @Volatile private var pendingVid = ""
    @Volatile private var pendingId = ""
    @Volatile private var pendingAt = 0L
    fun setPending(vid: String, id: String) { pendingVid = vid; pendingId = id; pendingAt = System.currentTimeMillis() }
    fun takePending(vid: String): String? {
        val id = pendingId
        val ok = id.isNotEmpty() && pendingVid == vid && System.currentTimeMillis() - pendingAt < 120_000L
        if (ok) { pendingId = ""; pendingVid = "" }
        return if (ok) id else null
    }

    /** بيحفظ التشغيلة (على خيط خلفي). مفيش لوج ومفيش جمل = ما بيتحفظش حاجة. */
    fun save(c: Context, run: Run?, subs: List<Sub>) {
        if (run == null) return
        val app = c.applicationContext
        val snap = ArrayList(subs)
        val logText = synchronized(run.log) { run.log.toString() }
        if (logText.isBlank() && snap.isEmpty()) return
        io.execute {
            try { write(app, run, logText, snap) } catch (e: Throwable) { LogStore.err("LogArchive:save", e) }
        }
    }

    private fun subArr(s: Sub) = JSONArray().put(s.start).put(s.end).put(s.original).put(s.translated)
        .put(s.speakerName).put(if (s.isSound) 1 else 0).put(if (s.faint) 1 else 0)

    private fun subOf(a: JSONArray): Sub = Sub(
        a.optDouble(0), a.optDouble(1), a.optString(2), a.optString(3), "male", "unknown", "none", emptyList(), emptyList(), false, false,
        speakerName = a.optString(4), isSound = a.optInt(5) == 1, faint = a.optInt(6) == 1
    )

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.writeText(text, Charsets.UTF_8); tmp.delete() }
    }

    private fun write(c: Context, run: Run, logText: String, subs: List<Sub>) {
        val tr = translatedCount(subs)
        val lines = if (logText.isEmpty()) 0 else logText.count { it == '\n' }
        val sa = JSONArray(); for (s in subs) sa.put(subArr(s))
        val o = JSONObject().put("id", run.id).put("vid", run.vid).put("title", run.title).put("uri", run.uri).put("url", run.url)
            .put("ts", run.ts).put("log", logText).put("subs", sa)
        synchronized(lock) {
            atomicWrite(File(dir(c), run.id + ".json"), o.toString())
            val list = readIndex(c).filter { it.id != run.id }.toMutableList()
            list.add(0, Meta(run.id, run.vid, run.title, run.uri, run.url, run.ts, subs.size, tr, lines))
            list.sortByDescending { it.ts }
            // السجل محدود: الأقدم بيتمسح (ملفه كمان)
            while (list.size > MAX_RUNS) { val x = list.removeAt(list.size - 1); File(dir(c), x.id + ".json").delete() }
            writeIndex(c, list)
        }
    }

    // ===================== القراءة =====================
    private fun readIndex(c: Context): List<Meta> = try {
        val f = idxFile(c)
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText(Charsets.UTF_8))
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Meta(it.optString("id"), it.optString("vid"), it.optString("title"), it.optString("uri"), it.optString("url"), it.optLong("ts"),
                    it.optInt("n"), it.optInt("t"), it.optInt("l"))
            }.filter { it.id.isNotEmpty() }
        }
    } catch (e: Throwable) { LogStore.err("LogArchive:index", e); emptyList() }

    private fun writeIndex(c: Context, l: List<Meta>) {
        val a = JSONArray()
        for (m in l) a.put(JSONObject().put("id", m.id).put("vid", m.vid).put("title", m.title).put("uri", m.uri).put("url", m.url)
            .put("ts", m.ts).put("n", m.subs).put("t", m.translated).put("l", m.lines))
        atomicWrite(idxFile(c), a.toString())
    }

    /** الأحدث الأول */
    fun list(c: Context): List<Meta> = synchronized(lock) { readIndex(c).sortedByDescending { it.ts } }

    fun read(c: Context, id: String): Entry? {
        synchronized(lock) {
            try {
                val m = readIndex(c).firstOrNull { it.id == id } ?: return null
                val f = File(dir(c), "$id.json"); if (!f.exists()) return null
                val o = JSONObject(f.readText(Charsets.UTF_8))
                val sa = o.optJSONArray("subs")
                val subs = (0 until (sa?.length() ?: 0)).mapNotNull { sa!!.optJSONArray(it) }.map { subOf(it) }
                return Entry(m, o.optString("log"), subs)
            } catch (e: Throwable) { LogStore.err("LogArchive:read", e); return null }
        }
    }

    fun readSubs(c: Context, id: String): List<Sub>? = read(c, id)?.subs

    // ===================== المسح =====================
    fun delete(c: Context, id: String) {
        synchronized(lock) {
            try {
                File(dir(c), "$id.json").delete()
                writeIndex(c, readIndex(c).filter { it.id != id })
            } catch (e: Throwable) { LogStore.err("LogArchive:delete", e) }
        }
    }

    fun deleteAll(c: Context): Int = synchronized(lock) {
        var n = 0
        try { dir(c).listFiles()?.forEach { if (it.isFile && it.delete()) n++ } } catch (e: Throwable) { LogStore.err("LogArchive:clear", e) }
        n
    }
}
