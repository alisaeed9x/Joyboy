package com.tttt.subtitler

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * (v990) مكتبة الترجمات — طبقة البيانات والمنطق (من غير واجهة).
 *  • المصدر: recent.json (اللي فيه نسبة التقدم وعدد الأسطر لكل فيديو اترجم ولو جزئيًا) + مهام الخلفية الشغّالة (BgJobs).
 *  • الميتا (اللهجة · الأسلوب · الشدة · المحرك) في trlib.json — بتتسجّل وقت تشغيل الترجمة (خلفية / مشغّل MeeTube / مشغّل Joyboy).
 *  • «إعدادات متقدمة» لكل ترجمة (ov*): بتتطبّق على الاستكمال والإعادة في الخلفية بس؛ فاضي = زي الإعدادات العامة.
 *  • التقدم الفعلي (الأسطر) هو ملف progress/<hash>.json بتاع Store — نفسه اللي المشغّل بيستخدمه، فالاستكمال بيكمّل من اللي اترجم.
 */
object TrLib {
    class Meta(val id: String) {
        @Volatile var title = ""
        @Volatile var dialect = ""
        @Volatile var style = ""
        @Volatile var strength = ""
        @Volatile var model = ""
        @Volatile var engine = ""
        @Volatile var ts = 0L
        @Volatile var ovDialect = ""
        @Volatile var ovStyle = ""
        @Volatile var ovStrength = ""
        @Volatile var ovModel = ""
        val hasOverride: Boolean get() = ovDialect.isNotBlank() || ovStyle.isNotBlank() || ovStrength.isNotBlank() || ovModel.isNotBlank()
    }

    val DIALECTS = listOf("فصحى", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني")
    val STYLES = listOf("تلقائي", "حرفي", "شعبي", "جرئ")
    val STRENGTHS = listOf("خفيفة", "متوسطة", "شديدة")

    /** (v999) عرض أسلوب ترجمة قديمة محفوظ: "+18" اتشال من القوايم بس القيمة المحفوظة بتتعرض «+18 (قديم)» من غير ما تتغيّر */
    fun displayStyle(s: String): String = if (s == "+18") "+18 (قديم)" else s

    private val lock = Any()
    private var cache: HashMap<String, Meta>? = null
    private fun file(c: Context) = File(c.applicationContext.filesDir, "trlib.json")

    private fun load(c: Context): HashMap<String, Meta> {
        cache?.let { return it }
        val m = HashMap<String, Meta>()
        try {
            val f = file(c)
            if (f.exists()) {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                for (k in o.keys()) {
                    val j = o.optJSONObject(k) ?: continue
                    val x = Meta(k)
                    x.title = j.optString("t"); x.dialect = j.optString("d"); x.style = j.optString("s"); x.strength = j.optString("g")
                    x.model = j.optString("m"); x.engine = j.optString("e"); x.ts = j.optLong("ts", 0L)
                    x.ovDialect = j.optString("od"); x.ovStyle = j.optString("os"); x.ovStrength = j.optString("og"); x.ovModel = j.optString("om")
                    m[k] = x
                }
            }
        } catch (e: Throwable) { LogStore.err("TrLib:load", e) }
        cache = m
        return m
    }

    private fun flush(c: Context) {
        try {
            val m = cache ?: return
            val o = JSONObject()
            for ((k, x) in m) o.put(k, JSONObject().put("t", x.title).put("d", x.dialect).put("s", x.style).put("g", x.strength)
                .put("m", x.model).put("e", x.engine).put("ts", x.ts)
                .put("od", x.ovDialect).put("os", x.ovStyle).put("og", x.ovStrength).put("om", x.ovModel))
            val f = file(c)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.writeText(o.toString(), Charsets.UTF_8); tmp.delete() }
        } catch (e: Throwable) { LogStore.err("TrLib:flush", e) }
    }

    fun meta(c: Context, id: String): Meta? = synchronized(lock) { load(c)[id] }

    /** اسم المحرك زي ما بيظهر في الكارت: «جيميناي flash-lite · دقيقة · ٧٥٪ / ٢٥٪» أو «تفريغ محلي (whisper) · ترجمة جوجل» */
    fun engineLabel(conf: Conf): String {
        val m = conf.model.removePrefix("gemini-").removeSuffix("-latest")
        val off = try { Offline.configured() } catch (_: Throwable) { false }
        if (off) {
            val eng = Cfg.str("stt_engine", "whisper").ifBlank { "whisper" }
            val tr = if (TrWizard.method() == "google") "ترجمة جوجل" else "ترجمة جيميناي $m"
            return "تفريغ محلي ($eng) · $tr"
        }
        val pr = try { TrPresets.byId(Cfg.str("preset_last", ""))?.name } catch (_: Throwable) { null }
        return "جيميناي $m" + (if (pr != null) " · $pr" else "") + " · " + KeyMode.short(conf.keyMode)
    }

    /** بيسجّل القيم اللي الترجمة اشتغلت بيها فعلًا (من غير ما يلمس الإعدادات المتقدمة). */
    fun stamp(c: Context, id: String, title: String, dialect: String, style: String, strength: String, model: String, engine: String) {
        try {
            synchronized(lock) {
                val m = load(c)
                val x = m.getOrPut(id) { Meta(id) }
                if (title.isNotBlank()) x.title = title
                x.dialect = dialect.ifBlank { "فصحى" }
                x.style = style.ifBlank { "تلقائي" }
                x.strength = strength.ifBlank { "متوسطة" }
                x.model = model; x.engine = engine; x.ts = System.currentTimeMillis()
                flush(c)
            }
        } catch (e: Throwable) { LogStore.err("TrLib:stamp", e) }
    }

    fun setOverrides(c: Context, id: String, dialect: String, style: String, strength: String, model: String) {
        try {
            synchronized(lock) {
                val x = load(c).getOrPut(id) { Meta(id) }
                x.ovDialect = dialect; x.ovStyle = style; x.ovStrength = strength; x.ovModel = model
                flush(c)
            }
        } catch (e: Throwable) { LogStore.err("TrLib:ov", e) }
    }

    fun forget(c: Context, id: String) {
        try { synchronized(lock) { if (load(c).remove(id) != null) flush(c) } } catch (_: Throwable) {}
    }

    // ===================== القايمة =====================
    class Item(val id: String, val title: String, val uri: String, val url: String, val durSec: Double, val subs: Int,
               val coverSec: Double, val ts: Long, val meta: Meta?, val job: BgJob?) {
        val active: Boolean get() = job?.active == true
        val pct: Int get() {
            val j = job
            return if (j != null && j.active && j.dur > 0.0) j.pct else PlayerLogic.percent(coverSec, durSec)
        }
        /** running · paused · queued · failed · done · partial */
        val state: String get() {
            val j = job
            if (j != null && j.active) return if (j.paused) "paused" else if (j.state == "running") "running" else "queued"
            if (j != null && j.state == "failed") return "failed"
            if (j != null && j.state == "done") return "done"
            return if (pct >= 99 || (durSec > 0.0 && coverSec >= durSec - 3.0)) "done" else "partial"
        }
    }

    fun items(c: Context): List<Item> {
        val rec = try { Recents.parse(File(c.filesDir, "recent.json").readText()) } catch (_: Exception) { emptyList() }
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        for (r in rec) {
            val j = BgJobs.find(r.id)
            if (r.subs <= 0 && r.coverSec <= 0.0 && j == null) continue
            seen.add(r.id)
            out.add(Item(r.id, r.title.ifBlank { Recents.titleOf(r.id) }, r.uri, r.url, r.durSec, r.subs, r.coverSec, r.ts, meta(c, r.id), j))
        }
        for (j in BgJobs.jobs) {
            if (j.vid in seen) continue
            seen.add(j.vid)
            out.add(Item(j.vid, j.title, j.uri ?: "", j.url ?: "", j.dur, 0, j.covered, System.currentTimeMillis(), meta(c, j.vid), j))
        }
        return out
    }

    // ===================== الإجراءات =====================
    /** null = ينفع يكمّل في الخلفية، غير كده سبب المنع */
    fun bgBlock(i: Item): String? = when {
        i.id.startsWith("yt:") -> "يوتيوب: لينك الفيديو بينتهي، افتحه من المشغّل عشان تكمّل"
        i.uri.isBlank() && !i.url.startsWith("http") -> "مفيش مصدر محفوظ للفيديو، افتحه من المشغّل مرة"
        else -> null
    }

    fun startBg(c: Context, i: Item, why: String): Boolean {
        val uri = i.uri.ifBlank { null }
        val url = if (uri == null) i.url.ifBlank { null } else null
        return BgJobs.enqueue(c.applicationContext, BgJob(i.id, i.title, uri, url, HashMap()), why)
    }

    private fun progressFile(c: Context, id: String) = File(File(c.filesDir, "progress"), Store.keyFor(id) + ".json")

    /** Store.load() بيغيّر Extras.mainNames (عام) — بنرجّعها عشان فتح ترجمة فيديو تاني ما يلخبطش المشغّل الشغّال */
    fun loadSaved(c: Context, id: String): Saved? {
        val keep = Extras.mainNames
        return try { Store(File(c.filesDir, "progress"), Store.keyFor(id)).load() } catch (_: Throwable) { null } finally { Extras.mainNames = keep }
    }

    /** مسح تقدم الترجمة (الأسطر) بس — ملف الـSRT اللي جنب الفيديو بيفضل زي ما هو */
    fun wipe(c: Context, i: Item) {
        try { progressFile(c, i.id).delete() } catch (_: Throwable) {}
        try { File(File(c.filesDir, "progress"), Store.keyFor(i.id) + ".json.bad").delete() } catch (_: Throwable) {}
        Recents.saveProgress(c.applicationContext, i.id, i.title, i.url, i.uri, i.durSec, 0, 0.0)
    }

    /** ملف SRT اللي البرنامج كتبه جنب الفيديو (من سجل SrtWriter بس — مابنمسحش حاجة مش بتاعتنا) */
    fun writtenSrt(c: Context, i: Item): List<File> {
        if (i.uri.isBlank()) return emptyList()
        return try {
            val video = SrtWriter.pathOf(c, i.uri) ?: return emptyList()
            val dir = video.parentFile ?: return emptyList()
            val base = video.nameWithoutExtension
            val written = try { File(c.filesDir, "srt_written.txt").readLines().toHashSet() } catch (_: Exception) { hashSetOf<String>() }
            listOf(File(dir, "$base.srt"), File(dir, "$base.ar.srt")).filter { it.exists() && it.path in written }
        } catch (_: Throwable) { emptyList() }
    }

    fun srtText(c: Context, id: String): String? {
        val sv = loadSaved(c, id) ?: return null
        if (sv.subs.isEmpty()) return null
        val off = Cfg.str("sub_offset_ms:$id", "0").toLongOrNull() ?: 0L
        return PlayerLogic.toSrt(sv.subs, off)
    }

    fun safeName(t: String): String = t.substringBeforeLast('.', t).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "subtitle" }.take(80)

    /** بيكتب SRT في الكاش وبيرجّع الملف (للمشاركة) */
    fun exportFile(c: Context, i: Item): File? {
        val txt = srtText(c, i.id) ?: return null
        return try {
            val f = File(c.cacheDir, safeName(i.title) + ".srt")
            f.writeText(txt, Charsets.UTF_8)
            f
        } catch (_: Throwable) { null }
    }

    /** تعديل نص سطر واحد في الترجمة المحفوظة. بيرجّع false لو الترجمة شغّالة دلوقتي أو التخزين فشل */
    fun editLine(c: Context, id: String, index: Int, newText: String): Boolean {
        if (BgJobs.isActive(id)) return false
        return try {
            val sv = loadSaved(c, id) ?: return false
            if (index !in sv.subs.indices) return false
            val subs = sv.subs.toMutableList()
            subs[index] = subs[index].copy(translated = newText)
            Store(File(c.filesDir, "progress"), Store.keyFor(id)).save(Saved(sv.chunkSec, sv.srcLang, sv.detDone, subs, sv.done, sv.failed,
                sv.chars, sv.gloss, sv.tpl, sv.pos, sv.charsTried, sv.bounds, sv.gapTried))
            true
        } catch (e: Throwable) { LogStore.err("TrLib:edit", e); false }
    }

    /** عدد الأسطر المطابقة لكلمة بحث (أصل أو ترجمة) — للبحث في كل المكتبة */
    fun countMatches(c: Context, id: String, q: String): Int {
        val sv = loadSaved(c, id) ?: return 0
        var n = 0
        for (s in sv.subs) if (s.translated.contains(q, true) || s.original.contains(q, true)) n++
        return n
    }

    fun fmtPct(i: Item): String {
        val unknown = i.durSec <= 0.0 && (i.job?.dur ?: 0.0) <= 0.0
        return if (unknown && i.state != "done") "…" else i.pct.toString() + "%"
    }
}
