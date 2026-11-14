package com.tttt.subtitler

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v991) تفريغ المحاضرات — المنطق (من غير واجهة):
 *  • تفريغ نصي دقيق بنفس لغة الكلام (Gemini) على أجزاء 60 ثانية — الحد اللي جيميناي بيسمح بيه — بالتوازي على أكتر من مفتاح.
 *  • كل جزء بيتحفظ أول ما يخلص: لو البرنامج اتقفل أو جزء فشل، «استكمال» بيكمّل الناقص بس (من غير ما يعيد اللي خلص).
 *  • «تنظيم»: عناوين رئيسية وتحت كل عنوان نقط قليلة · «تلخيص»: ملخص قصير + أهم النقط · ودايمًا اختيار «استعن بالنت» (بحث جوجل).
 *  • مصادر: فيديو/ملف صوت من الجهاز · تسجيلات الجهاز (MediaStore) · لينك (يوتيوب/فيسبوك/أي لينك فيه صوت أو فيديو).
 * التخزين: filesDir/lectures/<id>.json — مفيش تعديل على أي حاجة موجودة.
 */
object Lecture {
    const val CHUNK_SEC = 60.0   // جيميناي ما بيقبلش أكتر من كده في الطلب الواحد بشكل موثوق

    // ===================== الموديل =====================
    // (v999 ملاحظة 1) نموذج التفريغ المستقل عن نموذج الترجمة: الأدق افتراضيًا، والسريع = flash-lite.
    // مفتاح Cfg مستقل «lec_model» — مفتاح «model» بتاع الترجمة مبيتلمسش.
    const val LEC_MODEL_ACCURATE = "gemini-flash-latest"
    const val LEC_MODEL_FAST = "gemini-flash-lite-latest"
    const val LEC_MODEL_KEY = "lec_model"
    fun lecModel(): String = Cfg.str(LEC_MODEL_KEY, LEC_MODEL_ACCURATE).trim().ifEmpty { LEC_MODEL_ACCURATE }

    class Seg(val start: Double, val end: Double, val text: String, val chunk: Int, val spk: String = "", val lang: String = "", val tone: String = "", val conf: Double = -1.0)   // spk: D محاضر · S طالب/متحدث تاني · "" غير معروف — lang: كود ISO للغة الجملة دي (v999) — tone: نبرة الجملة بكلمة عربية واحدة (v1001، للمكالمات) — conf: ثقة الموديل في دقة الجملة 0–1، و-1 = مارجّعش ثقة (v1002)

    class Doc(val id: String) {
        var title = ""
        var kind = "video"          // video · audio · rec · link
        var uri = ""                // content:// أو file:// للمصادر المحلية
        var link = ""               // اللينك الأصلي (بيتحل من جديد عند الاستكمال لأن روابط الستريم بتنتهي)
        var durSec = 0.0
        var nChunks = 0
        var created = System.currentTimeMillis()
        var lang = ""
        var clean = true            // تجاهل الكلام الإداري/الجانبي
        val done = HashSet<Int>()
        val failed = HashSet<Int>()
        val segs = ArrayList<Seg>()
        var organized = ""          // نص «تنظيم» الجاهز
        var organizedWeb = false
        var summary = ""
        var summaryWeb = false
        var summaryLen = "mid"      // short · mid
        var ua = ""                 // (v993) هيدرز اللينك المتحل — بتتستعمل للقطات الفيديو
        var ref = ""
        var analysis = ""           // (v993) تحليل المذكرة (JSON) — بيتعاد لو التفريغ اتغيّر
        var analysisN = 0
        var analysisHash = ""
        var type = ""               // (v995) نوع المحتوى: lecture · call · subs · show · meeting · audio — "" = لسه ما اتحددش (بيتعامل كمحاضرة)
        var typeAuto = false        // (v995) النوع اتحدد تلقائي ولا يدوي
        var typeConf = 0.0          // (v995) ثقة التحديد التلقائي 0.0–1.0
        var autoTitle = ""          // (v995) عنوان مولّد تلقائي من أول أجزاء التفريغ — منفصل عن title اللي المستخدم يعدّله
        var warid = false           // (v998) تفريغ بطريقة وارد: سلسلة موديلات + ستريم + Files API (مش الوضع العادي)
        var chunkSec = 60.0         // (v998) حجم الجزء بالثواني للتفريغ ده — 60 عادي، 300/600 وارد (بيتحفظ عشان الاستكمال ميتكسرش)
        var langCode = ""           // (v998) كود ISO للغة المنطوقة (ar/en/tr/...) من رد وارد — بيتبعت لمحرك الترجمة كلغة مصدر
        var langTally = ""          // (v999) عدّاد لغات الأجزاء «ar:3#العربية;en:1#English» — الغالبة فيه هي langCode الحالية
        val parties = ArrayList<String>()   // (v1001) أطراف المكالمة بأسماء العرض بس، بنفس ترتيب المراجع — "أنا" أولًا لو فيه بصمة صوت لصاحب الجهاز — مفتاح الصوت بيتجاب بـ CallKit.contactKey(name) والرقم بـ CallKit.resolve وقت الدخول
        val complete: Boolean get() = nChunks > 0 && done.size >= nChunks && failed.isEmpty()
        val pct: Int get() = if (nChunks <= 0) 0 else (done.size * 100 / nChunks).coerceIn(0, 100)
        val words: Int get() = synchronized(this) { segs.sumOf { s -> s.text.split(' ').count { it.isNotBlank() } } }
    }

    /** حالة تشغيل حيّة (في الذاكرة) — الواجهة بتقراها كل ثانية */
    class Run(val doc: Doc) {
        @Volatile var running = true
        @Volatile var stop = false
        @Volatile var status = "بجهّز الصوت…"
        @Volatile var error = ""
        @Volatile var titleTried = false   // (v995) اتحاول توليد عنوان تلقائي للدوكيومنت ده ولا لسه — مرة واحدة بس
        @Volatile var callRefs: List<ByteArray> = emptyList()   // (v1001) المقاطع المرجعية للمكالمة، محمّلة مرة واحدة في start() — بنفس ترتيب callNames
        @Volatile var callNames: List<String> = emptyList()   // (v1001) أسماء الأطراف بنفس ترتيب callRefs — callNames[0] = "أنا" لو فيه بصمة لصاحب الجهاز
        @Volatile var callMixLogged: Boolean = false   // (v1001) اتسجّل خلط المراجع في اللوج مرة واحدة ولا لسه
        @Volatile var showGlossary: String = ""   // (v1002) أسماء قاموس شخصيات المسلسل (للحلقة دي بس) — بتتضاف لـ terms في doChunk
    }
    val runs = ConcurrentHashMap<String, Run>()
    fun runOf(id: String): Run? = runs[id]
    fun isRunning(id: String) = runs[id]?.running == true

    // ===================== التخزين =====================
    private fun dir(ctx: Context) = File(ctx.filesDir, "lectures").also { it.mkdirs() }

    fun idFor(key: String): String =
        "l" + java.lang.Long.toHexString(key.hashCode().toLong() and 0xffffffffL) + java.lang.Long.toHexString(key.length.toLong())

    private fun toJson(d: Doc): JSONObject = synchronized(d) {
        val segs = JSONArray()
        for (s in d.segs) segs.put(JSONObject().put("s", s.start).put("e", s.end).put("t", s.text).put("c", s.chunk).put("k", s.spk).put("g", s.lang).put("n", s.tone).put("cf", s.conf))
        JSONObject().put("id", d.id).put("title", d.title).put("kind", d.kind).put("uri", d.uri).put("link", d.link)
            .put("dur", d.durSec).put("n", d.nChunks).put("created", d.created).put("lang", d.lang).put("clean", d.clean)
            .put("done", JSONArray(d.done.toList())).put("failed", JSONArray(d.failed.toList())).put("segs", segs)
            .put("org", d.organized).put("orgWeb", d.organizedWeb).put("sum", d.summary).put("sumWeb", d.summaryWeb).put("sumLen", d.summaryLen)
            .put("ua", d.ua).put("ref", d.ref).put("an", d.analysis).put("anN", d.analysisN).put("anH", d.analysisHash)
            .put("ty", d.type).put("tyA", d.typeAuto).put("tyC", d.typeConf).put("aTi", d.autoTitle)
            .put("wrd", d.warid).put("cSec", d.chunkSec).put("lCd", d.langCode).put("lTl", d.langTally).put("pty", JSONArray(d.parties))
    }

    private fun fromJson(o: JSONObject): Doc {
        val d = Doc(o.getString("id"))
        d.title = o.optString("title"); d.kind = o.optString("kind", "video"); d.uri = o.optString("uri"); d.link = o.optString("link")
        d.durSec = o.optDouble("dur", 0.0); d.nChunks = o.optInt("n", 0); d.created = o.optLong("created", 0L)
        d.lang = o.optString("lang"); d.clean = o.optBoolean("clean", true)
        o.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) d.done.add(a.optInt(i)) }
        o.optJSONArray("failed")?.let { a -> for (i in 0 until a.length()) d.failed.add(a.optInt(i)) }
        o.optJSONArray("segs")?.let { a ->
            for (i in 0 until a.length()) { val x = a.optJSONObject(i) ?: continue; d.segs.add(Seg(x.optDouble("s"), x.optDouble("e"), x.optString("t"), x.optInt("c"), x.optString("k"), x.optString("g"), toneOf(x.optString("n")), confOf(x.optDouble("cf", -1.0)))) }
        }
        d.organized = o.optString("org"); d.organizedWeb = o.optBoolean("orgWeb"); d.summary = o.optString("sum")
        d.summaryWeb = o.optBoolean("sumWeb"); d.summaryLen = o.optString("sumLen", "mid")
        d.ua = o.optString("ua"); d.ref = o.optString("ref"); d.analysis = o.optString("an"); d.analysisN = o.optInt("anN", 0); d.analysisHash = o.optString("anH")
        d.type = o.optString("ty"); d.typeAuto = o.optBoolean("tyA", false); d.typeConf = o.optDouble("tyC", 0.0); d.autoTitle = o.optString("aTi")
        d.warid = o.optBoolean("wrd", false); d.chunkSec = o.optDouble("cSec", 60.0); d.langCode = o.optString("lCd"); d.langTally = o.optString("lTl")
        o.optJSONArray("pty")?.let { a -> for (i in 0 until a.length()) d.parties.add(a.optString(i)) }
        return d
    }

    @Synchronized fun save(ctx: Context, d: Doc) {
        try {
            val f = File(dir(ctx), d.id + ".json"); val t = File(dir(ctx), d.id + ".tmp")
            t.writeText(toJson(d).toString(), Charsets.UTF_8)
            if (!t.renameTo(f)) { f.writeText(t.readText(Charsets.UTF_8), Charsets.UTF_8); t.delete() }
        } catch (e: Throwable) { LogStore.err("Lecture:save", e) }
    }

    fun load(ctx: Context, id: String): Doc? = try {
        val f = File(dir(ctx), "$id.json"); if (f.exists()) fromJson(JSONObject(f.readText(Charsets.UTF_8))) else null
    } catch (e: Throwable) { LogStore.err("Lecture:load", e); null }

    /** كل المحاضرات المحفوظة، الأحدث أولًا (يتنده من خيط خلفي — بيقرا الملفات) */
    fun list(ctx: Context): List<Doc> {
        val out = ArrayList<Doc>()
        for (f in dir(ctx).listFiles().orEmpty()) {
            if (!f.name.endsWith(".json")) continue
            val live = runs[f.name.removeSuffix(".json")]?.doc
            if (live != null) { out.add(live); continue }
            try { out.add(fromJson(JSONObject(f.readText(Charsets.UTF_8)))) } catch (_: Throwable) {}
        }
        return out.sortedByDescending { it.created }
    }

    fun delete(ctx: Context, id: String) {
        runs[id]?.stop = true
        try { File(dir(ctx), "$id.json").delete() } catch (_: Throwable) {}
        runs.remove(id)
    }

    // (v1003 — المرحلة 5) حذف شامل للدوكيومنت وكل مشتقاته — الخيار اللي اتاخد: علامة studio_for
    // بتتمسح دايمًا (هي علامة الإحياء)، وملف تقدم الفيديو + نسخة المحرك المخبّاة (.eng.json) بيتمسحوا،
    // وsub_choice/sub_offset_ms بيتسابوا زي ما هما (تفضيلات باقية للفيديو، مابتحييش الدوكيومنت).
    // مبيتمسحش: ملفات studio_series.json / studio_courses.json كلها (بيتشال الدوكيومنت من الكورس بس)،
    // ولا مجلد voices (البصمات مشتركة)، ولا كاش صور الويكي (lectures/media)، ولا نسخة studio_inbox
    // من الصوت الأصلي (بتفضل ملف يتيم — لو اتمسحت بعدين مفيش حاجة بتعتمد عليها).
    // ملفات كاش التصدير (cacheDir/<title>.srt/.vtt) بتتمسح على قد ما العنوان الحالي يعرّفها — لو العنوان
    // اتغيّر بعد التصدير ممكن يفضل ملف يتيم في الكاش (الكاش بيتمسح لوحده من النظام).
    fun deleteFull(ctx: Context, id: String) {
        runs[id]?.stop = true
        val lecId = "lec:$id"
        try { BgJobs.stopAndWait(lecId, 7000L) } catch (_: Throwable) {}
        val d = load(ctx, id)
        val vid = try { if (d != null) StudioLink.vidForDoc(ctx, d) else null } catch (_: Throwable) { null }
        try { File(dir(ctx), "$id.json").delete() } catch (_: Throwable) {}
        try { File(dir(ctx), "$id.tmp").delete() } catch (_: Throwable) {}
        try { File(dir(ctx), "$id.intel.json").delete() } catch (_: Throwable) {}
        val item = TrLib.Item(lecId, d?.title ?: "", d?.uri ?: "", d?.link ?: "", d?.durSec ?: 0.0, 0, 0.0, 0L, null, null)
        try { TrLib.wipe(ctx, item) } catch (_: Throwable) {}
        try { TrLib.forget(ctx, lecId) } catch (_: Throwable) {}
        try { Recents.drop(ctx, lecId) } catch (_: Throwable) {}
        if (vid != null) {
            try { File(File(ctx.filesDir, "progress"), Store.keyFor(vid) + ".json").delete() } catch (_: Throwable) {}
            try { File(File(ctx.filesDir, "progress"), Store.keyFor(vid) + ".eng.json").delete() } catch (_: Throwable) {}
            try { StudioLink.clearStudioMark(ctx, vid) } catch (_: Throwable) {}
        }
        if (d != null) {
            try {
                for (f in TrLib.writtenSrt(ctx, item)) try { f.delete() } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            try {
                val safe = TrLib.safeName(Studio.displayTitle(d).ifBlank { d.title })
                File(ctx.cacheDir, safe + ".srt").delete()
                File(ctx.cacheDir, safe + ".vtt").delete()
            } catch (_: Throwable) {}
        }
        try {
            val course = StudioIntel.courseOf(ctx, id)
            if (course.isNotBlank()) StudioIntel.removeFromCourse(ctx, course, id)
        } catch (_: Throwable) {}
        runs.remove(id)
    }

    // (v1003 — المرحلة 5) قص مقطع صوت من مصدر الدوكيومنت — بيفتح المصدر زي openSource بالظبط
    // (محلي: الملف نفسه؛ لينك: الستريم بيتحل من جديد) وبيفك المدى المطلوب بس، من غير ما يفك الملف كله.
    // بيرجّع null لو المصدر مش متاح (الملف اتمسح/اتنقل، أو اللينك مبيتحلش دلوقتي) — والمتصل بيقع على
    // مقطع نصي بس. لازم يتنده من خيط خلفي (ممكن يعمل شبكة للينكات).
    internal fun cutAudioClip(ctx: Context, d: Doc, startSec: Double, endSec: Double): WavChunk? {
        var src: AudioSource? = null
        try {
            val s = startSec.coerceAtLeast(0.0)
            val dur = if (d.durSec > 0) d.durSec else Double.MAX_VALUE
            val e = endSec.coerceIn(0.0, dur)
            if (e - s < 0.5) return null
            src = openSource(ctx, d)
            return src.wav(s, e)
        } catch (e: Throwable) {
            LogStore.err("Lecture:cutClip", e)
            return null
        } finally {
            try { src?.close() } catch (_: Throwable) {}
        }
    }

    fun newDoc(ctx: Context, kind: String, title: String, uri: String, link: String): Doc {
        val id = idFor(if (link.isNotBlank()) link else uri)
        val old = runs[id]?.doc ?: load(ctx, id)
        if (old != null) return old   // نفس المصدر ← نكمّل على اللي اتفرّغ قبل كده
        val d = Doc(id); d.kind = kind; d.title = title.ifBlank { "محاضرة" }; d.uri = uri; d.link = link
        save(ctx, d)
        return d
    }

    // ===================== المفاتيح والموديل =====================
    private fun keys(): List<String> = (Cfg.keys("keys") + Cfg.keys("backup") + Cfg.keys("extra")).distinct()
    private fun model(): String = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }

    // ===================== مصادر الصوت =====================
    class Resolved(val url: String, val hdr: Map<String, String>, val title: String)

    /** لينك → رابط ستريم صوت/فيديو (بيتنده من خيط خلفي). يوتيوب: صوت m4a · روابط الملفات · صفحات فيها فيديو (فيسبوك وغيره) */
    fun resolveLink(ctx: Context, link0: String): Resolved {
        val link = link0.trim()
        Links.ytId(link)?.let { id ->
            val p = MtYt.audio(id) ?: throw IOException("يوتيوب: " + MtYt.lastWhy.ifBlank { "مقدرتش أجيب الصوت" })
            return Resolved(p.url, mapOf("User-Agent" to p.ua, "Referer" to "https://www.youtube.com/"), p.title)
        }
        PageScan.resolve(link)?.let { return Resolved(it.url, emptyMap(), it.name) }
        val cv = ClipWatch.resolve(ctx, link) ?: throw IOException("مفيش صوت أو فيديو في اللينك ده")
        val play = cv.playUrl.ifBlank { if (Sniff.isDirect(cv.url)) cv.url else "" }
        if (play.isBlank()) throw IOException("لقيت صفحة بس مقدرتش أطلّع منها ستريم (ممكن تحتاج تسجيل دخول)")
        val hdr = HashMap<String, String>()
        if (cv.ua.isNotBlank()) hdr["User-Agent"] = cv.ua
        if (cv.ref.isNotBlank()) hdr["Referer"] = cv.ref
        return Resolved(play, hdr, cv.title)
    }

    private fun openSource(ctx: Context, d: Doc): AudioSource {
        val log: (String) -> Unit = { LogStore.add("🎓 $it") }
        if (d.link.isNotBlank()) {
            val r = resolveLink(ctx, d.link)
            if (d.title.isBlank() || d.title == "محاضرة" || d.title == d.link) d.title = r.title.ifBlank { d.title }
            d.ua = r.hdr["User-Agent"] ?: ""; d.ref = r.hdr["Referer"] ?: ""
            return AudioSources.make(ctx, null, r.url, r.hdr, 1, log)
        }
        return AudioSources.make(ctx, Uri.parse(d.uri), null, emptyMap(), 1, log)
    }

    // ===================== تسجيلات الجهاز =====================
    class Rec(val uri: Uri, val name: String, val durMs: Long, val size: Long, val dateSec: Long, val path: String, val isRec: Boolean)

    fun audioPermission(): String = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
    fun hasAudioPermission(ctx: Context): Boolean = ctx.checkSelfPermission(audioPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    internal val REC_HINT = Regex("record|rec[_\\- ]|voice|memo|dictaphone|call|ptt|تسجيل|ريكورد|مكالم|صوتيات", RegexOption.IGNORE_CASE)

    /** كل الصوتيات على الجهاز؛ isRec = تسجيل (علامة النظام IS_RECORDING أو اسم/مسار شبه «Recordings/Voice Recorder/Call»). يتنده من خيط خلفي */
    fun scanAudio(ctx: Context): List<Rec> {
        val out = ArrayList<Rec>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        fun query(withFlag: Boolean) {
            val proj = ArrayList<String>().apply {
                add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.DISPLAY_NAME); add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.SIZE); add(MediaStore.Audio.Media.DATE_MODIFIED)
                add(if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
                if (withFlag) add("is_recording")
            }
            ctx.contentResolver.query(base, proj.toTypedArray(), null, null, MediaStore.Audio.Media.DATE_MODIFIED + " DESC")?.use { c ->
                while (c.moveToNext()) {
                    val dur = c.getLong(2)
                    if (dur in 1..2500L) continue
                    val name = c.getString(1) ?: continue
                    val path = c.getString(5) ?: ""
                    val flag = withFlag && c.getInt(6) == 1
                    out.add(Rec(ContentUris.withAppendedId(base, c.getLong(0)), name, dur, c.getLong(3), c.getLong(4), path, flag || REC_HINT.containsMatchIn(path) || REC_HINT.containsMatchIn(name)))
                }
            }
        }
        try { query(Build.VERSION.SDK_INT >= 31) } catch (_: Throwable) { out.clear(); try { query(false) } catch (e: Throwable) { LogStore.err("Lecture:scan", e) } }
        return out
    }

    // ===================== التفريغ =====================
    // type = نوع المحتوى ("" أو "lecture" = المحاضرة — السلوك القديم حرفيًا).
    // أي نوع تاني بياخد برومبته من سجل Studio.TxType. internal عشان Studio بيناديها لبرومبت المحاضرة.
    // (v999 ملاحظة 1) اكتشاف اللغة: كل برومبت (محاضرة + الأنواع الخمسة في Studio) بيطلب lang بالعربي
    // وlangCode بكود ISO، ولغة لكل عنصر لو الجملة بلغة تانية عن باقي المقطع.
    // المثال في شكل الـJSON وصف محايد («كود ISO») مش قيمة «ar» — عشان ميميلش الموديل للعربي.
    // terms: أسماء/مصطلحات متراكمة من الأجزاء اللي قبل كده — بتتثبّت كتابتها.
    internal fun transcribePrompt(durSec: Double, clean: Boolean, prev: String, type: String = "", warid: Boolean = false, terms: String = "", parties: List<String> = emptyList(), refsMixed: Boolean = false): String {
        val t = type.ifBlank { "lecture" }
        val base = if (t != "lecture") Studio.promptFor(t, durSec, clean, prev, terms, parties, refsMixed)
        else buildString {
        append("أنت مفرّغ صوتي أكاديمي محترف بدقة عالية جدًا، متخصص في المحاضرات. اسمع المقطع الصوتي (مدته حوالي ${durSec.toInt()} ثانية) بتركيز كامل وفرّغ الكلام المنطوق نصًا حرفيًا بنفس اللغة المنطوقة فعلًا — من غير أي ترجمة ولا تلخيص ولا إضافة من عندك.\n\n")
        append("قواعد صارمة:\n")
        append("- 🔴 اكتشف لغة الكلام المنطوق في المقطع ده أولًا (عربي/إنجليزي/تركي/كوري/أي لغة تانية) واكتبها في lang بالعربي وlangCode بكود ISO من حرفين (ar/en/tr/ko/...)، ولو فيه جملة بلغة تانية عن باقي المقطع حط كودها في lang بتاع العنصر نفسه. متفترضش إن اللغة عربي.\n")
        append("- 🔴 المتحدثين: المحاضر = الصوت الأساسي اللي بيشرح (spk=\"D\"). أي صوت تاني مختلف (طالب بيسأل أو بيقاطع أو بيرد، أو شخص تاني) = spk=\"S\". ميّز بين الأصوات من نبرتها وطبقتها وطريقة كلامها مش من المحتوى بس. لو الجملة الواحدة اتقالت من صوتين، افصلها لعنصرين.\n")
        append("- 🔴 لو طالب قاطع المحاضر أو سأل، سجّل سؤاله كعنصر منفصل spk=\"S\" بتوقيته، ورد المحاضر بعده كعنصر spk=\"D\". متدمجوش مع بعض أبدًا.\n")
        append("- 🔴 لو المتحدث خلط عربي وإنجليزي في نفس الجملة، اكتب كل جزء بلغته بالظبط زي ما اتقال. المصطلحات العلمية والإنجليزي القصير (حتى كلمتين وسط الجملة) تتكتب بحروفها الإنجليزية الصحيحة ومتتشالش ولا تتترجم ولا تتكتب بحروف عربية.\n")
        append("- كل جملة أو وحدة معنى (٣–١٥ ثانية) عنصر منفصل بـ start/end بالثواني نسبةً لبداية المقطع ده، والتوقيت لازم يبقى دقيق.\n")
        if (clean) append("- استبعد الكلام الإداري والجانبي اللي مالوش علاقة بالمادة (طلب سكوت، تنظيم الحضور، الغياب، الوقت، دردشة طلبة مع بعض). لكن أسئلة الطلبة للمحاضر وردوده عليها تتفرّغ دايمًا. ملاحظة قصيرة مرتبطة بالشرح (افتحوا صفحة كذا) سيبها.\n")
        else append("- فرّغ كل كلام مسموع حتى الجانبي.\n")
        append("- تجاهل السكوت والمؤثرات غير الكلامية (خطوات، ضجيج، تنحنح).\n")
        append("- لو الصوت مش واضح اكتب أقرب تفسير علمي منطقي من السياق، ومتخترعش كلام لو مفيش كلام مسموع.\n")
        append("- لو المقطع مفيهوش كلام خالص رجّع segments فاضية.\n")
        if (prev.isNotBlank()) append("\nسياق آخر كلام في المقطع اللي قبله (لتكملة جملة مقطوعة على الحدود وتوحيد كتابة المصطلحات بس، متكررهوش): \"$prev\"\n")
        if (terms.isNotBlank()) append("\nأسماء ومصطلحات ظهرت قبل كده في نفس التفريغ — اكتبها بنفس الطريقة دي بالظبط كل مرة تتقال: \"$terms\"\n")
        append("\nرجّع JSON فقط بدون شرح ولا markdown:\n")
        append("{\"lang\":\"اسم اللغة بالعربي\",\"langCode\":\"كود ISO\",\"segments\":[{\"start\":0.0,\"end\":5.2,\"spk\":\"D\",\"lang\":\"كود ISO\",\"text\":\"...\",\"conf\":0.9}]}")
        }
        if (!warid) return base
        return waridPrompt(base)
    }

    // (v998 — وارد) قواعد إضافية فوق برومبت النوع (محاضرة أو غيرها — أكواد spk بتفضل زي ما النوع حددها):
    // حرفي تمامًا، لهجة مصرية زي ما اتنطقت، إنجليزي بحروف لاتينية، بادئات عربية على الكلمات الإنجليزية،
    // كل utterance عنصر مستقل بتوقيت دقيق + spk، وlangCode (كود ISO) جنب lang في الـ JSON.
    // (v999) الشكل الأساسي بقى فيه langCode أصلًا — وارد مبيعدّلش الشكل، بيضيف قواعده بس.
    private fun waridPrompt(base: String): String {
        val rules = buildString {
            append("\n═══ قواعد وارد (إضافية — بتكمّل اللي فوق، مش بتلغيه) ═══\n")
            append("- 🔴 فرّغ حرفيًا تمامًا: من غير تلخيص ولا تصحيح قواعد ولا إضافة ولا حذف. اللي اتقال يتكتب زي ما اتقال بالظبط.\n")
            append("- 🔴 اللهجة المصرية زي ما اتنطقت حرفيًا — متحوّلهاش لفصحى. المصطلحات الإنجليزية والتقنية تتكتب بحروف لاتينية صحيحة، وأبدًا بحروف عربية صوتية.\n")
            append("- 🔴 البادئات العربية (الـ، بـ، نـ، هـ، وـ) تفضل عربية لما تتلزق في كلمة إنجليزية (مثال: الـAPI، بـEnglish).\n")
            append("- 🔴 كل مقطع كلام (utterance) عنصر مستقل: start/end دقيقين بالثواني نسبةً لبداية المقطع ده، وspk حسب الصوت (نفس أكواد spk المذكورة فوق حسب نوع المحتوى). الجملة الواحدة من صوتين تتفصل لعنصرين.\n")
            append("- 🔴 التوقيت لازم يبقى دقيق — كل عنصر بمكانه وبدايته ونهايته الفعلية في المقطع، مش توزيع بالتساوي.\n")
            append("- 🔴 ثقة كل جملة: حط في \"conf\" رقم 0–1 يعبر عن ثقتك في دقة نص الجملة دي (1 = متأكد تمامًا) — ولو مش متأكد حط رقم قليل.\n")
        }
        val marker = "\nرجّع JSON فقط"
        val at = base.indexOf(marker)
        if (at < 0) return base + rules
        return base.substring(0, at) + rules + base.substring(at)
    }

    // (v1001) تطبيع النبرة من رد الموديل: كلمة واحدة قصيرة (بحد أقصى 12 حرف)، وإلا فاضية.
    internal fun toneOf(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val w = t.split(Regex("\\s+")).firstOrNull() ?: ""
        return w.take(12)
    }

    // (v1002) تطبيع ثقة الجملة من رد الموديل: رقم 0–1، و-1 لو مفيش ثقة مرجّعة (قديم أو موديل مابيدعمهاش).
    internal fun confOf(raw: Double): Double {
        if (raw < 0.0 || raw.isNaN()) return -1.0
        return raw.coerceIn(0.0, 1.0)
    }

    internal fun spkOf(raw: String): String {
        val r = raw.trim().lowercase()
        return when {
            r.isEmpty() -> ""
            r.startsWith("s") || r.startsWith("ط") || r.startsWith("st") -> "S"
            r.startsWith("d") || r.startsWith("م") || r.startsWith("l") -> "D"
            r.length == 1 && r[0] in 'a'..'z' -> r.uppercase()
            else -> ""
        }
    }

    internal fun parseJson(t: String): JSONObject? {
        val c = t.replace("```json", "").replace("```", "").trim()
        return try { JSONObject(c) } catch (_: Throwable) {
            val a = c.indexOf('{'); val b = c.lastIndexOf('}')
            if (a >= 0 && b > a) try { JSONObject(c.substring(a, b + 1)) } catch (_: Throwable) { null } else null
        }
    }

    // (v999 ملاحظة 1) تطبيع كود اللغة لحرفين لاتينيين صغار، وإلا فاضي.
    internal fun normLangCode(raw: String): String {
        val c = raw.trim().lowercase().split('-', '_').firstOrNull() ?: ""
        return if (c.length == 2 && c.all { it in 'a'..'z' }) c else ""
    }

    // (v999 ملاحظة 1) عدّاد لغات الأجزاء: «ar:3#العربية;en:1#English» — الغالبة بتحدد langCode/lang.
    // لازم يتنده جوّا synchronized(d) — لو الغالبية اتقلبت في نص الفيديو، langCode بيتحدّث معاها.
    internal fun recordLang(d: Doc, code: String, name: String) {
        if (code.isBlank()) return
        val map = LinkedHashMap<String, Pair<Int, String>>()
        for (part in d.langTally.split(';')) {
            val kv = part.split('#', limit = 2)
            if (kv.isEmpty()) continue
            val cc = kv[0].substringBefore(':')
            val n = kv[0].substringAfter(':', "0").toIntOrNull() ?: 0
            if (cc.isNotBlank() && n > 0) map[cc] = Pair(n, kv.getOrNull(1) ?: "")
        }
        val cur = map[code]
        val nm = if (name.isNotBlank()) name else cur?.second ?: ""
        map[code] = Pair((cur?.first ?: 0) + 1, nm)
        val top = map.maxByOrNull { it.value.first }
        d.langTally = map.entries.joinToString(";") { (k, v) -> k + ":" + v.first + "#" + v.second }
        if (top != null) {
            d.langCode = top.key
            val topName = top.value.second
            if (topName.isNotBlank()) d.lang = topName
        }
    }

    // (v999 ملاحظة 1) أسماء/مصطلحات متراكمة من الأجزاء اللي قبل كده: كلمات لاتينية مبدوءة بكبير أو طويلة،
    // وكلمات عربية طويلة، الأكثر تكرارًا أولًا — بحد أقصى 12 مصطلح و300 حرف (رخيص، من غير طلب شبكة).
    internal fun termsFromPrev(d: Doc, idx: Int): String {
        val counts = HashMap<String, Int>()
        val segs = synchronized(d) { d.segs.filter { it.chunk < idx } }
        for (s in segs) {
            for (w in s.text.split(Regex("[^\\p{L}0-9_]+"))) {
                val t = w.trim()
                if (t.length < 4) continue
                val latin = t[0] in 'A'..'Z' || t[0] in 'a'..'z'
                val keep = if (latin) t[0] in 'A'..'Z' || t.length >= 5 else t.length >= 5
                if (!keep) continue
                counts[t] = (counts[t] ?: 0) + 1
            }
        }
        val top = counts.entries.sortedByDescending { it.value }.take(12).map { it.key }
        return top.joinToString("، ").take(300)
    }

    // (v999) تحويل مصفوفة segments في رد JSON لقايمة Seg — بتستعمل في المحاولة العادية وفي إعادة المحاولة بالموديل الأدق.
    // (v1001) offset: إزاحة المراجع الملزوقة قبل المقطع (CallKit.mix) — أي عنصر بيبدأ قبلها هو المرجع نفسه وبيتخطى، والباقي بتتطرح منه.
    private fun parseSegs(j: JSONObject, st: Double, en: Double, idx: Int, offset: Double = 0.0): List<Seg> {
        val arr = j.optJSONArray("segments") ?: JSONArray()
        val got = ArrayList<Seg>()
        val dur = en - st
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val t = x.optString("text").trim()
            if (t.isEmpty()) continue
            val s0 = (x.optDouble("start", 0.0)).coerceIn(0.0, dur)
            val e0 = (x.optDouble("end", s0)).coerceIn(s0, dur)
            if (s0 < offset) continue
            got.add(Seg(st + s0 - offset, st + e0 - offset, t, idx, spkOf(x.optString("spk")), normLangCode(x.optString("lang")), toneOf(x.optString("tone")), confOf(x.optDouble("conf", -1.0))))
        }
        return got
    }

    /** جزء واحد: بيرجّع true لو نجح (حتى لو الجزء صامت)، false لو فشل نهائيًا بعد المحاولات */
    private fun doChunk(run: Run, keys: List<String>, mdl: String, src: AudioSource, idx: Int, worker: Int, dur: Double, chunkSec: Double = CHUNK_SEC): Boolean {
        val d = run.doc
        // (G1) علم «الموديل الأقوى» لمرة واحدة للجزء ده — بيتسحب هنا وبيتمسح سواء نجح الجزء أو فشل.
        val accurate = StudioIntel.takeAccurate(d, idx)
        val useMdl = if (accurate) LEC_MODEL_ACCURATE else mdl
        val st = idx * chunkSec; val en = minOf(dur, st + chunkSec)
        if (en - st < 1.0) return true
        val wav = try { src.wav(st, en) } catch (e: Throwable) { LogStore.err("Lecture:wav", e); null } ?: return false
        val hasSpeech = d.warid && wav.activeSec >= WaridTx.MIN_ACTIVE_SEC
        if (wav.silent && !hasSpeech) { synchronized(d) { d.segs.removeAll { it.chunk == idx } }; return true }
        // (v1001) مكالمة + مراجع محمّلة: لزق المراجع قبل المقطع — البوابة اللي فوق (silent/activeSec)
        // لازم تفضل على المقطع الأصلي، لأن المقطع المخلوط فيه كلام مرجعي دايمًا فكان هيعدّيها على طول.
        // المقطع المخلوط بياخد نفس activeSec/silent بتوع الأصلي، والإزاحة بتتطرح من التوقيتات بعدين.
        val isCall = Studio.typeOf(d).code == "call"
        var sendWav = wav
        var mixOffset = 0.0
        var refsMixed = isCall && run.callRefs.isNotEmpty()
        if (refsMixed) {
            try {
                val mixed = CallKit.mix(wav.bytes, run.callRefs)
                sendWav = WavChunk(mixed.wav, wav.startSec, wav.durSec, wav.silent, wav.gain, wav.activeSec)
                mixOffset = mixed.offset
                synchronized(run) {
                    if (!run.callMixLogged) {
                        run.callMixLogged = true
                        LogStore.add("📞 [مكالمة] خلط " + run.callRefs.size + " مرجع صوت قبل كل جزء — الأطراف: " + run.callNames.joinToString("، "))
                    }
                }
            } catch (e: Throwable) {
                LogStore.err("Lecture:callMix", e)
                refsMixed = false
            }
        }
        val prev = synchronized(d) { d.segs.filter { it.chunk == idx - 1 }.sortedBy { it.start }.takeLast(2).joinToString(" ") { it.text } }.takeLast(300)
        var terms = termsFromPrev(d, idx)
        if (run.showGlossary.isNotBlank()) {
            terms = if (terms.isBlank()) run.showGlossary else terms + "، " + run.showGlossary
        }
        val promptParties = if (refsMixed) run.callNames else synchronized(d) { d.parties.toList() }
        val prompt = transcribePrompt(en - st, d.clean, prev, d.type, d.warid, terms, promptParties, refsMixed)
        if (d.warid) return WaridTx.transcribeChunk(run, keys, sendWav, idx, worker, chunkSec, en - st, prompt, mixOffset, accurate)
        for (a in 0 until 4) {
            if (run.stop) return false
            val key = keys[(idx + worker + a) % keys.size]
            try {
                val r = Api.generate(useMdl, key, prompt, sendWav.bytes, Api.outTokens(en - st), 0.1, true, false)
                val j = parseJson(r.text)
                if (j == null) { if (r.text.isBlank() && r.finish.startsWith("PROMPT_")) { return true }; continue }
                var finalGot = parseSegs(j, st, en, idx, mixOffset)
                var finalJ = j
                // (v999 ملاحظة 1) فحص الجزء المشبوه — قاعدة محافظة: بيتعاد مرة واحدة بس، وبالموديل الأدق،
                // وبيتاخد الرد الأطول بس. الشرط: الموديل الحالي مش الأدق أصلًا، وثواني الكلام الفعلي
                // (activeSec) ≥ نصف مدة الجزء، وحروف النص المرجّع < حرفين لكل ثانية كلام.
                // الجزء الهادي أو الصامت (activeSec قليل) مبيتعادش أبدًا.
                val gotChars = finalGot.sumOf { it.text.length }
                val suspicious = useMdl != LEC_MODEL_ACCURATE && wav.activeSec >= (en - st) * 0.5 && gotChars < wav.activeSec * 2
                if (suspicious) {
                    try {
                        val key2 = keys[(idx + worker + 1) % keys.size]
                        val r2 = Api.generate(LEC_MODEL_ACCURATE, key2, prompt, sendWav.bytes, Api.outTokens(en - st), 0.1, true, false)
                        val j2 = parseJson(r2.text)
                        if (j2 != null) {
                            val got2 = parseSegs(j2, st, en, idx, mixOffset)
                            if (got2.sumOf { it.text.length } > gotChars) {
                                finalGot = got2
                                finalJ = j2
                            }
                        }
                    } catch (e: Throwable) {
                        LogStore.err("Lecture:retryAccurate", e)
                    }
                }
                synchronized(d) {
                    d.segs.removeAll { it.chunk == idx }; d.segs.addAll(finalGot)
                    recordLang(d, normLangCode(finalJ.optString("langCode")), finalJ.optString("lang"))
                }
                return true
            } catch (e: NoKey) { run.error = e.message ?: "مفيش مفتاح"; run.stop = true; return false }
            catch (e: ApiErr) {
                if (e.code == 429 || e.code >= 500) { try { Thread.sleep(1500L * (a + 1)) } catch (_: InterruptedException) {} }
                else if (e.code != 400 && e.code != 403) { run.error = (e.message ?: "").take(120); return false }
            } catch (e: Throwable) { LogStore.err("Lecture:chunk", e); try { Thread.sleep(800) } catch (_: InterruptedException) {} }
        }
        return false
    }

    // ===================== العنوان التلقائي (v995) =====================
    // عنوان قصير واحد من أول أجزاء التفريغ — طلب نص رخيص (JSON فقط، حرارة 0.1) على أول 12 جملة.
    // بيتنده مرة واحدة بس لكل تشغيل تفريغ (titleTried)، وبيتخطى خالص لو العنوان اتولّد قبل كده.
    // فشله صامت (مبيوقفش التفريغ ومبيتسجّلش إلا في LogStore).
    // مبيكتبش في d.title أبدًا — بيتحفظ في d.autoTitle بس، والعرض بيقرر يوريه إمتى (Studio.displayTitle).
    private fun titlePrompt(text: String): String = buildString {
        append("اقترح عنوانًا قصيرًا واحدًا (3–7 كلمات) للمحتوى التالي من نص مفرّغ صوتيًا. العنوان بالعربية، وسيب المصطلحات العلمية بلغتها الأصلية.\n\n")
        append("رجّع JSON فقط بدون شرح ولا markdown:\n")
        append("{\"title\":\"...\"}\n\n")
        append(text)
    }

    private fun titleFromText(text: String): String? {
        val ks = keys()
        if (ks.isEmpty()) return null
        val mdl = model()
        val prompt = titlePrompt(text)
        for (a in 0 until minOf(ks.size, 2)) {
            val key = ks[a % ks.size]
            try {
                val r = Api.generate(mdl, key, prompt, null, 512, 0.1, true, false)
                val j = parseJson(r.text) ?: continue
                val t = j.optString("title").trim()
                if (t.isNotBlank()) return t.take(120)
            } catch (e: NoKey) {
                return null
            } catch (e: Throwable) {
                LogStore.err("Lecture:autoTitle", e)
            }
        }
        return null
    }

    // بيحاول توليد العنوان لما يبقى فيه كفاية نص (أول جزئين، أو الجزء الوحيد لو المحاضرة قصيرة).
    // بيشتغل في خيط خلفي منفصل عشان ميعطّلش خيوط التفريغ.
    private fun maybeAutoTitle(ctx: Context, run: Run) {
        val d = run.doc
        val need = synchronized(d) {
            if (run.titleTried) false
            else if (d.autoTitle.isNotBlank()) {
                run.titleTried = true
                false
            } else if (d.done.size < minOf(2, d.nChunks).coerceAtLeast(1)) false
            else {
                run.titleTried = true
                true
            }
        }
        if (!need) return
        Thread {
            try {
                val text = synchronized(d) { d.segs.sortedBy { it.start }.take(12).joinToString(" ") { it.text } }.take(3000)
                if (text.length < 40) return@Thread
                val t = titleFromText(text)
                if (!t.isNullOrBlank()) {
                    synchronized(d) { if (d.autoTitle.isBlank()) d.autoTitle = t }
                    save(ctx, d)
                }
            } catch (e: Throwable) {
                LogStore.err("Lecture:autoTitle", e)
            }
        }.apply { isDaemon = true; start() }
    }

    // (v1000 — المرحلة 2) اكتشاف نوع المحتوى قبل التفريغ: إشارات مجانية أولًا (من غير طلب شبكة)،
    // ولو مفيش إشارة قوية بيجرّب طلب خفيف على أول 90 ثانية. بيتنده من start() بس، قبل خيوط التفريغ.
    // مبيتندهش أبدًا لو النوع متحدد (يدوي أو تلقائي قديم) — ومبيرميش استثناء أبدًا.
    private fun detectType(ctx: Context, d: Doc, src: AudioSource, dur: Double) {
        if (d.type.isNotBlank() || d.typeAuto) return
        val path = d.uri.ifBlank { d.link }
        val free = TxDetect.freeSignals(d.kind, d.title, path, dur)
        if (free != null) {
            Studio.setType(ctx, d, free.type, true, free.conf)
            return
        }
        val wav = try {
            src.wav(0.0, minOf(90.0, dur))
        } catch (_: Throwable) {
            null
        } ?: return
        if (wav.silent) return
        val res = TxDetect.detectLight(ctx, wav.bytes)
        if (res == null) return
        if (res.conf < 0.7) return
        Studio.setType(ctx, d, res.type, true, res.conf)
    }

    /** يبدأ/يكمّل التفريغ في الخلفية. الأجزاء اللي خلصت بتتخطى؛ اللي فشلت بتتعاد. */
    fun start(ctx0: Context, d: Doc): Run? {
        val ctx = ctx0.applicationContext
        if (isRunning(d.id)) return runs[d.id]
        val run = Run(d); runs[d.id] = run
        Thread {
            var src: AudioSource? = null
            try {
                val ks = keys()
                if (ks.isEmpty()) throw IOException("مفيش مفتاح Gemini — ضيفه من الإعدادات الأول")
                // (v999) التفريغ بموديل التفريغ المستقل (lec_model) — مش موديل الترجمة.
                val mdl = lecModel()
                run.status = "بجهّز الصوت…"
                val s = openSource(ctx, d); src = s
                val dur = s.durationSec()
                if (dur <= 0.0) throw IOException("مقدرتش أعرف مدة الصوت")
                // (v1000) وارد هو الافتراضي للاستوديو: دوكيومنت جديد (مفيش تقدّم) بيتفرّغ بوارد.
                // الأولوية: سلسلة وارد التلقائية أولًا للتفريغات الجديدة؛ lec_model هو محرك المسار القديم (غير وارد) الثابت بس.
                synchronized(d) {
                    if (!d.warid && d.done.isEmpty() && d.failed.isEmpty() && d.segs.isEmpty()) {
                        d.warid = true
                    }
                }
                // (v1000 — المرحلة 2) اكتشاف نوع المحتوى قبل ما خيوط التفريغ تبدأ: بيتنده مرة واحدة هنا بس.
                // القاعدة: بيتحدد تلقائي بس لو النوع لسه فاضي (d.type == "") ومتحددش تلقائي قبل كده (typeAuto == false) —
                // لو المستخدم اختار نوع يدوي في بداية الجلسة (pickType) الاكتشاف مبيعدّلش عليه.
                // أي فشل هنا مبيوقفش التفريغ أبدًا: بيتسجّل وبيتكمّل بالنوع زي ما هو.
                try {
                    detectType(ctx, d, s, dur)
                } catch (e: Throwable) {
                    LogStore.err("Lecture:detect", e)
                }
                // (v1001 — المرحلة 3) مكالمة: حمّل المقاطع المرجعية مرة واحدة قبل خيوط التفريغ —
                // بصمة صاحب الجهاز ("me") أولًا، وبعدها بصمة كل طرف في d.parties ليه صوت محفوظ،
                // وبنفس الترتيب ده بتتبني قايمة الأسماء (run.callNames) عشان البرومبت والخلط يتفقوا.
                // مفيش global: المراجع عايشة على Run اللي بيتسلم لـ doChunk.
                // (v1002) مسلسل/فيلم: حمّل قاموس أسماء الشخصيات للسلسلة دي مرة واحدة — بيتضاف لـ terms في doChunk.
                if (Studio.typeOf(d).code == "show") {
                    try {
                        run.showGlossary = StudioIntel.glossaryTerms(ctx, d)
                    } catch (e: Throwable) {
                        LogStore.err("Lecture:glossary", e)
                    }
                }
                if (Studio.typeOf(d).code == "call") {
                    try {
                        val refs = ArrayList<ByteArray>()
                        val names = ArrayList<String>()
                        if (CallKit.hasVoice(ctx, "me")) {
                            CallKit.voice(ctx, "me")?.let { refs.add(it); names.add("أنا") }
                        }
                        val partyNames = synchronized(d) { d.parties.toList() }
                        for (nm in partyNames) {
                            if (nm == "أنا") continue
                            val key = CallKit.contactKey(nm)
                            if (!CallKit.hasVoice(ctx, key)) continue
                            CallKit.voice(ctx, key)?.let { refs.add(it); names.add(nm) }
                        }
                        run.callRefs = refs
                        run.callNames = names
                    } catch (e: Throwable) {
                        LogStore.err("Lecture:callRefs", e)
                    }
                }
                val cs = if (d.warid) {
                    WaridTx.ensureChunkSec(d)
                } else {
                    synchronized(d) {
                        if ((d.done.isNotEmpty() || d.segs.isNotEmpty()) && d.chunkSec != CHUNK_SEC) {
                            d.done.clear(); d.failed.clear(); d.segs.clear()
                        }
                        d.chunkSec = CHUNK_SEC
                    }
                    CHUNK_SEC
                }
                synchronized(d) { d.durSec = dur; d.nChunks = Math.ceil(dur / cs).toInt() }
                save(ctx, d)
                val todo = (0 until d.nChunks).filter { it !in d.done }
                if (todo.isEmpty()) { maybeAutoTitle(ctx, run); run.status = "التفريغ مكتمل ✅"; return@Thread }
                val next = AtomicInteger(0); val finished = AtomicInteger(0)
                val par = minOf(ks.size, 3, todo.size).coerceAtLeast(1)
                run.status = "بفرّغ…"
                val ths = (0 until par).map { w ->
                    Thread {
                        while (!run.stop) {
                            val k = next.getAndIncrement(); if (k >= todo.size) break
                            val idx = todo[k]
                            val ok = try { doChunk(run, ks, mdl, s, idx, w, dur, cs) } catch (e: Throwable) { LogStore.err("Lecture:worker", e); false }
                            synchronized(d) { if (ok) { d.done.add(idx); d.failed.remove(idx) } else if (!run.stop) d.failed.add(idx) }
                            val fin = finished.incrementAndGet()
                            if (d.warid || fin % 4 == 0) save(ctx, d)
                            if (ok) maybeAutoTitle(ctx, run)
                            run.status = "بفرّغ… ${d.done.size}/${d.nChunks}"
                        }
                    }.apply { isDaemon = true; start() }
                }
                ths.forEach { try { it.join() } catch (_: InterruptedException) {} }
                maybeAutoTitle(ctx, run)
                run.status = when {
                    run.error.isNotEmpty() -> "اتوقف: " + run.error
                    run.stop -> "اتوقف — تقدر تكمّل"
                    d.failed.isNotEmpty() -> "اكتمل مع ${d.failed.size} جزء ناقص — اضغط «أعد الناقص»"
                    else -> "التفريغ مكتمل ✅"
                }
            } catch (e: Throwable) {
                LogStore.err("Lecture:start", e); run.error = (e.message ?: e.javaClass.simpleName).take(140); run.status = "فشل: " + run.error
            } finally {
                try { src?.close() } catch (_: Throwable) {}
                save(ctx, d); run.running = false
            }
        }.apply { isDaemon = true; start() }
        return run
    }

    fun stop(id: String) { runs[id]?.stop = true }

    // ===================== نص التفريغ =====================
    fun fmtTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
    }

    // (v999 ملاحظة 2) جمل العرض بعد تطبيع التوقيتات: تقليم داخل مدة الفيديو، وبداية < نهاية،
    // وكل جملة بتبدأ من نهاية اللي قبلها على الأقل — فمفيش جملتين معروضتين بتوقيتات متداخلة أبدًا.
    // التطبيع للعرض بس: الخام في d.segs مبيتغيّرش، ومفيش جملة بتتحذف.
    fun displaySegs(d: Doc): List<Seg> = synchronized(d) {
        val dur = if (d.durSec > 0) d.durSec else d.segs.maxOfOrNull { it.end } ?: 0.0
        val sorted = d.segs.sortedBy { it.start }
        val out = ArrayList<Seg>()
        var prevEnd = 0.0
        for (s in sorted) {
            var st = s.start.coerceIn(0.0, dur)
            var en = s.end.coerceIn(0.0, dur)
            if (en <= st) en = minOf(dur, st + 0.5)
            if (en <= st) st = maxOf(0.0, en - 0.5)
            if (st < prevEnd) st = prevEnd
            if (en <= st) en = minOf(dur, st + 0.5)
            if (en <= st) st = maxOf(0.0, en - 0.5)
            out.add(Seg(st, en, s.text, s.chunk, s.spk, s.lang, s.tone, s.conf))
            prevEnd = en
        }
        out
    }

    // (v999 ملاحظة 2) تعديل جملة (نص + توقيت) من الواجهة — بيحفظ عبر save وبيعرض من غير إعادة تفريغ.
    // التوقيتات بتتقلم داخل مدة الفيديو وstart < end. ملحوظة سياسة: ده تعديل في الطبقة الخام
    // (Studio.kt بيقول الخام «متتعدّلش») — اتنفذ بطلب صريح في ملاحظات الاستوديو.
    fun editSeg(ctx: Context, d: Doc, chunk: Int, oldStart: Double, newText: String, newStart: Double, newEnd: Double): Boolean {
        val dur = if (d.durSec > 0) d.durSec else Double.MAX_VALUE
        var st = newStart.coerceIn(0.0, dur)
        var en = newEnd.coerceIn(0.0, dur)
        if (en <= st) en = minOf(dur, st + 0.5)
        if (en <= st) st = maxOf(0.0, en - 0.5)
        val t = newText.trim()
        if (t.isEmpty()) return false
        val ok = synchronized(d) {
            // أقرب جملة في نفس الجزء لوقت البداية المعروض — التطبيع للعرض ممكن يزحزح البداية شوية عن الخام.
            val cand = d.segs.withIndex().filter { it.value.chunk == chunk }
            val pick = cand.minByOrNull { Math.abs(it.value.start - oldStart) }
            if (pick == null || Math.abs(pick.value.start - oldStart) > 30.0) false
            else {
                val old = pick.value
                d.segs[pick.index] = Seg(st, en, t, old.chunk, old.spk, old.lang, old.tone, old.conf)
                true
            }
        }
        if (ok) save(ctx, d)
        return ok
    }

    // (v1000 استثناء ثانٍ بطلب صريح من صاحب التطبيق: إعادة تسمية/دمج المتحدثين مسموح عبر renameSpeaker
    // وmergeSpeakers — بيغيّروا spk في كل الجمل دفعة واحدة، وبيمسحوا المشتقات النصية (تنظيم/تلخيص/تحليل)
    // لأنها اتبنت على أسماء المتحدثين القديمة، وبيتحفظوا، من غير إعادة تفريغ.)
    // ملحوظة سياسة: ده تعديل في الطبقة الخام زي editSeg — مسموح هنا بس، وبنفس شروطه.
    // الحارس: التسمية/الدمج بيرفضوا (false) والتشغيل شغال على الدوكيومنت ده — اكتمال جزء بيمسح جمل
    // الجزء ويعيد إضافتها، فكان هيمسح التسمية من غير ما يبان. editSeg فيه نفس الخطر ومعروف من v999.
    private fun wipeDerived(d: Doc) {
        d.organized = ""
        d.organizedWeb = false
        d.summary = ""
        d.summaryWeb = false
        d.summaryLen = "mid"
        d.analysis = ""
        d.analysisN = 0
        d.analysisHash = ""
    }

    /** إعادة تسمية متحدث في كل الجمل: كل seg بـ spk == oldSpk بيبقى newSpk. بيرجّع false لو الاسمين فاضيين أو مفيش جملة مطابقة أو التشغيل شغال. */
    fun renameSpeaker(ctx: Context, d: Doc, oldSpk: String, newSpk: String): Boolean {
        if (oldSpk.isBlank() || newSpk.isBlank()) return false
        if (isRunning(d.id)) return false
        val old = oldSpk.trim()
        val new = newSpk.trim()
        if (old == new) return false
        var matched = false
        synchronized(d) {
            for (i in d.segs.indices) {
                val s = d.segs[i]
                if (s.spk == old) {
                    d.segs[i] = Seg(s.start, s.end, s.text, s.chunk, new, s.lang, s.tone, s.conf)
                    matched = true
                }
            }
            if (matched) wipeDerived(d)
        }
        if (!matched) return false
        save(ctx, d)
        return true
    }

    /** دمج متحدث في متحدث تاني: كل seg بـ spk == fromSpk بيبقى toSpk. بيرجّع false لو from == to أو مفيش جملة مطابقة أو التشغيل شغال. */
    fun mergeSpeakers(ctx: Context, d: Doc, fromSpk: String, toSpk: String): Boolean {
        if (fromSpk.isBlank() || toSpk.isBlank()) return false
        if (isRunning(d.id)) return false
        val from = fromSpk.trim()
        val to = toSpk.trim()
        if (from == to) return false
        var matched = false
        synchronized(d) {
            for (i in d.segs.indices) {
                val s = d.segs[i]
                if (s.spk == from) {
                    d.segs[i] = Seg(s.start, s.end, s.text, s.chunk, to, s.lang, s.tone, s.conf)
                    matched = true
                }
            }
            if (matched) wipeDerived(d)
        }
        if (!matched) return false
        save(ctx, d)
        return true
    }

    /** عدد الجمل اللي فيها نص البحث (من غير حساسية لحالة الحروف) + بداية أول جملة مطابقة أو null. دالة نقية، من غير I/O. */
    fun countMatches(d: Doc, q: String): Pair<Int, Double?> {
        if (q.isBlank()) return Pair(0, null)
        var n = 0
        var first: Double? = null
        synchronized(d) {
            for (s in d.segs.sortedBy { it.start }) {
                if (!s.text.contains(q, true)) continue
                n++
                if (first == null) first = s.start
            }
        }
        return Pair(n, first)
    }

    // (v999 ملاحظة 2) دقايق اتفرّغت = الأجزاء المكتملة × حجم الجزء، مقابل مدة الفيديو الكاملة.
    fun doneMinutes(d: Doc): Double = synchronized(d) {
        val cs = if (d.chunkSec > 0) d.chunkSec else CHUNK_SEC
        d.done.size * cs / 60.0
    }

    fun fmtMinutes(m: Double): String {
        return if (m < 10) String.format(java.util.Locale.US, "%.1f", m) else m.toInt().toString()
    }

    /** نص التفريغ: stamps=true سطر لكل جملة بتوقيتها · false فقرات متصلة. القطع الفاشلة بتظهر كعلامة ⚠ */
    // (v1001) names: أسماء الأطراف للمكالمات — لو مش فاضية وspk حرف A–E بيتكتب «الاسم: » قبل الجملة.
    // الافتراضي فاضي فالمحاضرات والنسخ/المشاركة زي ما هي بالظبط من غير أي تغيير.
    fun transcriptText(d: Doc, stamps: Boolean, names: List<String> = emptyList()): String = synchronized(d) {
        class Item(val start: Double, val text: String, val gap: Boolean, val spk: String)
        val items = ArrayList<Item>()
        val cs = if (d.chunkSec > 0) d.chunkSec else CHUNK_SEC
        for (s in displaySegs(d)) items.add(Item(s.start, s.text, false, s.spk))
        for (c in d.failed) items.add(Item(c * cs, "⚠ [جزء ناقص من ${fmtTime(c * cs)} إلى ${fmtTime(minOf(d.durSec, (c + 1) * cs))}]", true, ""))
        items.sortBy { it.start }
        fun nameOf(spk: String): String {
            if (names.isEmpty()) return ""
            if (spk.length != 1) return ""
            val c = spk[0]
            if (c !in 'A'..'E') return ""
            return names.getOrNull(c - 'A') ?: ""
        }
        val sb = StringBuilder()
        if (stamps) {
            for (i in items) {
                if (i.gap) sb.append(i.text).append('\n')
                else {
                    sb.append('[').append(fmtTime(i.start)).append("] ")
                    val nm = nameOf(i.spk)
                    if (nm.isNotEmpty()) sb.append(nm).append(": ")
                    sb.append(if (i.spk == "S") "🙋 " else "").append(i.text).append('\n')
                }
            }
        } else {
            var para = 0; var last = ""
            for (i in items) {
                if (i.gap) { if (sb.isNotEmpty()) sb.append("\n\n"); sb.append(i.text).append("\n\n"); para = 0; last = ""; continue }
                val nm = nameOf(i.spk)
                val changed = i.spk != last && (i.spk == "S" || last == "S" || nm.isNotEmpty())
                if (changed || para > 650) { if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n"); para = 0 }
                else if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append(' ')
                if (para == 0) {
                    if (i.spk == "S") sb.append("🙋 ")
                    if (nm.isNotEmpty()) sb.append(nm).append(": ")
                }
                sb.append(i.text); para += i.text.length + 1; last = i.spk
            }
        }
        sb.toString().trim()
    }

    // ===================== تنظيم / تلخيص (نصوص) =====================
    class TextResult(val text: String, val usedWeb: Boolean)

    private fun gen(prompt: String, web: Boolean, maxTok: Int): TextResult {
        val ks = keys(); if (ks.isEmpty()) throw IOException("مفيش مفتاح Gemini — ضيفه من الإعدادات الأول")
        val mdl = model()
        var last: Throwable? = null
        var useWeb = web
        for (a in 0 until minOf(ks.size, 3) + 2) {
            val key = ks[a % ks.size]
            try {
                val r = Api.generate(mdl, key, prompt, null, maxTok, 0.2, false, useWeb)
                if (r.text.isNotBlank()) return TextResult(r.text.trim(), useWeb)
                last = IOException("رد فاضي (${r.finish})")
            } catch (e: NoKey) { throw e }
            catch (e: ApiErr) {
                last = e
                if (e.code == 400 && useWeb) useWeb = false   // الموديل/المفتاح مش بيدعم البحث ← نكمّل من غيره
                else if (e.code == 429 || e.code >= 500) { try { Thread.sleep(1500L * (a + 1)) } catch (_: InterruptedException) {} }
            } catch (e: Throwable) { last = e }
        }
        throw IOException("فشل الطلب: " + (last?.message ?: "").take(100))
    }

    private const val MAX_CHARS = 300_000

    private fun outLangLine(): String = if (Cfg.str("lec_out", "ar") == "same")
        "اكتب بنفس لغة المحاضرة." else "اكتب بالعربية المصرية الواضحة (سهلة المذاكرة)، وسيب المصطلحات العلمية بلغتها الأصلية بين قوسين أو كما هي."

    private fun webLine(web: Boolean): String = if (web)
        "\n- مسموح تستعين ببحث جوجل لتصحيح مصطلح أو إكمال تعريف ناقص فقط. كل نقطة مصدرها النت لازم تبدأ بالرمز 🌐 وتبقى قصيرة، ومتعدّلش ولا تناقض كلام المحاضر. باقي النقط من كلام المحاضر بس."
    else "\n- كل المحتوى لازم يكون من كلام المحاضر في التفريغ؛ متضيفش معلومات من برّه."

    // (v1000 — المرحلة 2) بلوك شكل المخرجات حسب نوع المحتوى: بيتحط في برومبت التنظيم والتلخيص.
    // الشكل نفسه من سجل Studio.TxType.summaryShape — فأي تعديل في السجل بيسمع هنا من غير ما نعدّل البرومبت.
    private fun typeBlock(t: Studio.TxType): String = when (t) {
        Studio.TxType.CALL -> "شكل المخرجات المطلوب: " + t.summaryShape + ".\n- أي مبلغ أو موعد اتقال لازم يظهر زي ما اتقال بالظبط، وكل وعد يتسجل باسم اللي وعد بيه."
        Studio.TxType.SUBS -> "شكل المخرجات المطلوب: " + t.summaryShape + ".\n- لخّص الأحداث والحوار من غير توقيتات في الملخص، واذكر المتحدثين باختصار."
        Studio.TxType.SHOW -> "شكل المخرجات المطلوب: " + t.summaryShape + ".\n- لخّص أحداث الحلقة/الفيلم، واذكر الشخصيات الرئيسية وتقلبات الأحداث."
        Studio.TxType.MEETING -> "شكل المخرجات المطلوب: " + t.summaryShape + ".\n- كل مهمة سطر فيه: المهمة + المسؤول عنها + الموعد النهائي لو اتقال."
        Studio.TxType.AUDIO -> "شكل المخرجات المطلوب: " + t.summaryShape + ".\n- ملخص عام مختصر للنص من غير افتراض نوع محتوى معين."
        else -> "شكل المخرجات المطلوب: " + t.summaryShape + "."
    }

    private fun organizePrompt(text: String, web: Boolean, type: String = ""): String {
        val t = Studio.TxType.fromCode(type)
        val termsLine = if (t == Studio.TxType.LECTURE) "\n- بعد آخر عنوان ضيف قسم «## أهم المصطلحات» — كل مصطلح سطر بالشكل «المصطلح — تعريف من سطر واحد» ومصدره نص التفريغ بس، من غير إضافة من برّه." else ""
        return "أنت أستاذ جامعي بتنظّم تفريغ محاضرة لطالب بيذاكر منها.\n" +
        "المطلوب: عناوين رئيسية، وتحت كل عنوان نقط قليلة ومركّزة.\n" +
        "القواعد:\n- عدد العناوين الرئيسية من 5 لـ 12 حسب طول المحاضرة — مش أكتر.\n" +
        "- تحت كل عنوان من 3 لـ 6 نقط بس. كل نقطة جملة واحدة واضحة. ممنوع تعمل عشرات النقط.\n" +
        "- رتّب العناوين بتسلسل منطقي، مش بالضرورة بترتيب الكلام العشوائي.\n" +
        "- ${outLangLine()}" + webLine(web) + termsLine + "\n" +
        typeBlock(t) + "\n\n" +
        "الشكل بالظبط (من غير أي كلام تاني قبل أو بعد):\n# عنوان المحاضرة\n## العنوان الرئيسي الأول\n- نقطة\n- نقطة\n## العنوان الرئيسي التاني\n- نقطة\n\n" +
        "نص التفريغ:\n" + text
    }

    private fun summaryPrompt(text: String, short: Boolean, web: Boolean, type: String = ""): String {
        val t = Studio.TxType.fromCode(type)
        return "لخّص تفريغ المحاضرة ده لطالب.\n" +
        "القواعد:\n- " + (if (short) "ملخص من 3 لـ 4 جمل بس، وبعده 5 نقط أهم حاجات." else "ملخص فقرة واحدة (6 لـ 8 جمل) بيغطي الفكرة العامة، وبعده من 6 لـ 8 نقط لأهم الأفكار.") + "\n" +
        "- ${outLangLine()}" + webLine(web) + "\n" +
        typeBlock(t) + "\n\n" +
        "الشكل بالظبط (من غير أي كلام تاني):\n## الملخص\n<الفقرة>\n## أهم النقط\n- نقطة\n- نقطة\n\n" +
        "نص التفريغ:\n" + text
    }

    // (v1001) أسماء الأطراف لمدخل التنظيم/التلخيص — للمكالمات بس (d.parties)، غير كده فاضي.
    private fun callNames(d: Doc): List<String> =
        if (Studio.typeOf(d).code == "call") synchronized(d) { d.parties.toList() } else emptyList()

    private fun fullText(d: Doc, names: List<String> = emptyList()): String {
        val t = transcriptText(d, false, names).replace(Regex("⚠ \\[جزء ناقص[^\\]]*\\]"), "").trim()
        if (t.isBlank()) throw IOException("مفيش تفريغ لسه — فرّغ المحاضرة الأول")
        return t
    }

    private fun parts(t: String): List<String> {
        if (t.length <= MAX_CHARS) return listOf(t)
        val n = Math.ceil(t.length / MAX_CHARS.toDouble()).toInt(); val size = t.length / n + 1
        return (0 until n).map { t.substring(it * size, minOf(t.length, (it + 1) * size)) }
    }

    /** «تنظيم»: عناوين + نقط. يتنده من خيط خلفي ويحفظ النتيجة في الدوكيومنت */
    fun organize(ctx: Context, d: Doc, web: Boolean, type: String = d.type): String {
        val t = fullText(d, callNames(d))
        var usedWeb = false
        val outs = parts(t).mapIndexed { i, p ->
            val r = gen(organizePrompt(p, web, type), web, 16000); usedWeb = usedWeb || r.usedWeb
            if (i == 0) r.text else r.text.lines().filterNot { it.startsWith("# ") && !it.startsWith("## ") }.joinToString("\n")
        }
        val res = outs.joinToString("\n")
        synchronized(d) { d.organized = res; d.organizedWeb = usedWeb }
        save(ctx, d); return res
    }

    /** «تلخيص»: فقرة + أهم النقط */
    fun summarize(ctx: Context, d: Doc, short: Boolean, web: Boolean, type: String = d.type): String {
        val t = fullText(d, callNames(d))
        var usedWeb = false
        val ps = parts(t)
        var res: String
        if (ps.size == 1) { val r = gen(summaryPrompt(ps[0], short, web, type), web, 6000); usedWeb = r.usedWeb; res = r.text }
        else {
            val mid = ps.joinToString("\n\n") { p -> gen(summaryPrompt(p, false, false, type), false, 6000).text }
            val r = gen(summaryPrompt(mid, short, web, type), web, 6000); usedWeb = r.usedWeb; res = r.text
        }
        synchronized(d) { d.summary = res; d.summaryWeb = usedWeb; d.summaryLen = if (short) "short" else "mid" }
        save(ctx, d); return res
    }

    // ===================== تحليل المذكرة (v993) — نفس منطق وضع المذاكرة في الـHTML =====================
    private const val AN_MAX_CHARS = 200_000

    private fun segsHash(d: Doc): String = synchronized(d) { d.segs.size.toString() + "_" + d.segs.sumOf { it.text.length } + "_" + d.segs.count { it.spk == "S" } }

    private fun analysisPrompt(text: String): String = buildString {
        append("أنت أستاذ جامعي خبير ومحرر أكاديمي محترف، متخصص في تحويل تفريغ المحاضرات الخام إلى مادة مذاكرة استثنائية الجودة. المطلوب مش تلخيص سطحي، المطلوب \"شغل فاخر\": مذكرة أكاديمية شاملة، عميقة، منظمة بعناية، تخلّي الطالب يذاكر منها من غير ما يرجع للمحاضرة الأصلية.\n\n")
        append("⚠️⚠️ تحذير حرج: أغلب المحاضرات طبية. أي معلومة طبية غلط ممكن تضر مريض حقيقي. الدقة شرط أساسي غير قابل للتفاوض.\n\n")
        append("═══ المصدر الوحيد للحقيقة ═══\nكل المحتوى الأساسي (bullets, detail, qna, keyPoints, definitions) لازم يتبني على كلام المحاضر الفعلي في النص. النص ده هو مصدر الحقيقة الوحيد.\n")
        append("علامة (طالب) قبل جملة معناها إنها اتقالت من متحدث تاني غير المحاضر (سؤال أو مقاطعة أو رد). الجمل من غير علامة هي كلام المحاضر.\n\n")
        append("═══ نظام تمييز الإضافات (حرج جدًا) ═══\nلو حبيت تضيف معلومة موثوقة من برّه كلام المحاضر (تعريف أوسع، سياق علمي معروف، تصنيف يفيد الفهم، إكمال خطوة ناقصة جوه تسلسل ذكره المحاضر):\n")
        append("1. لازم تبقى دقيقة وموثوقة 100% علميًا — لو في أي شك ولو 1% متضيفهاش خالص.\n2. متتعارضش مع أي حاجة قالها المحاضر ولو ضمنيًا.\n3. الإضافة تسد فجوة صغيرة جوه قسم موجود؛ ممنوع تخترع قسم كامل من عندك.\n")
        append("4. 🔴 حيط النص المضاف بـ [[ADD]] و [[/ADD]] بالظبط: \"...كلام المحاضر [[ADD]]المعلومة المضافة[[/ADD]] وباقي الكلام...\". العلامات إلزامية لأي جزء مضاف مهما صغر، وممنوعة على أي جزء منقول من كلام المحاضر.\n\n")
        append("═══ 🚨 إضافات طبية حساسة ═══\nلو الإضافة بتخص جرعة دواء أو معيار تشخيص محدد أو خطوة علاج مباشر، حيطها كمان بـ [[MEDWARN]]...[[/MEDWARN]] برّه الـ[[ADD]]: [[MEDWARN]][[ADD]]نص الإضافة[[/ADD]][[/MEDWARN]]. ولو مش متأكد 100% اتجاهلها خالص.\n\n")
        append("═══ 🏷️ تمييز المصطلحات العلمية (في bullets و detail) ═══\nكل مصطلح علمي أو تقني مهم يظهر لأول مرة في القسم (مرض، إجراء، مادة، خلية، مفهوم تشريحي/فسيولوجي دقيق) حيطه بـ [[TERM:تعريفه المختصر]]المصطلح[[/TERM]]. التعريف سطر واحد (8–15 كلمة) ودقيق 100%. علّم المصطلح مرة واحدة بس في القسم، من 2 لـ 5 مصطلحات لكل قسم، ومش كل كلمة.\n\n")
        append("═══ معايير الجودة ═══\n")
        append("- اشرح كل مفهوم بعمق يخلّي الطالب يفهم \"ليه\" مش بس \"إيه\"؛ اربط الأفكار، وضّح العلاقات السببية والاستثناءات اللي ذكرها المحاضر.\n")
        append("- رتّب منطقيًا بداية ونهاية لكل قسم، مش نقل حرفي للترتيب العشوائي للكلام.\n")
        append("- أمثلة والحالات السريرية/العملية اللي ذكرها المحاضر اشرحها بالتفصيل جوه القسم المرتبط.\n")
        append("- أي مقارنة ذكرها المحاضر (الفرق بين تقنيتين، مين أفضل وليه) نظّمها في جدول جوه comparisons.\n")
        append("- ابحث في النص كله عن أي سؤال من طالب ورد المحاضر عليه (الجمل اللي عليها علامة (طالب) وكمان أي سؤال متقال في الكلام) وسجّله في qna بالسؤال والإجابة كاملين ومفصّلين.\n")
        append("- لكل قسم رئيسي اقترح مصطلح علمي إنجليزي دقيق (زي اسم مقالة Wikipedia) يصلح للبحث عن صورة توضيحية حقيقية في imageSearchTerm. لو القسم مفيهوش مفهوم بصري (مقدمة/خاتمة) سيبه فاضي.\n")
        append("- 🔴🔴 الإشارات البصرية (visuals): المحاضر كتير بيشاور على حاجة قدامه من غير ما يسميها: \"عندنا هنا كذا\"، \"زي ما إنتو شايفين\"، \"شوفوا الشكل ده\"، \"ده اللي على الشاشة\"، \"الرسمة دي\"، \"هنا في الصورة\"، \"لاحظوا الجزء ده\"، \"as you can see\"، \"this one here\". لكل إشارة زي دي: (1) حدّد من سياق الجمل اللي قبلها وبعدها هو بيشاور على إيه بالظبط (شكل تشريحي؟ جدول؟ مخطط؟ أشعة؟ سلايد؟ جهاز؟) واكتبه في what بجملة واضحة. (2) سجّل ts = ثانية الجملة اللي فيها الإشارة (من الرقم بعد | في علامة الوقت). (3) اختار source: \"frame\" لو الأنسب لقطة من الفيديو نفسه (سلايد/رسمة/حاجة مخصوصة بالمحاضرة)، أو \"wiki\" لو الأنسب صورة مرجعية معروفة (شكل تشريحي، تصنيف، جهاز). (4) wikiTerm دايمًا: مصطلح Wikipedia إنجليزي دقيق للحاجة دي (بيتستعمل كبديل لو اللقطة مش متاحة). (5) callouts: أسماء الأجزاء اللي المحاضر نطقها وبيشاور عليها (تتحط كأسهم على اللقطة)، [] لو مفيش. حط الإشارة في القسم اللي بتخدمه. متخترعش إشارة لو مفيش مؤشر واضح، ومتكررش نفس الإشارة (حد أقصى 3 لكل قسم).\n")
        append("- خريطة ذهنية في mindMap: عقدة مركزية (موضوع المحاضرة) + فروع (فرع لكل قسم) وتحت كل فرع نقط قصيرة جدًا (كلمة أو اتنين).\n\n")
        append("النص الكامل للمحاضرة (كل سطر: [الوقت | الثواني] الكلام):\n").append(text).append("\n\n")
        append("""أرجع JSON فقط بدون أي شرح أو markdown، بالهيكل ده بالظبط:
{
  "title": "عنوان المحاضرة المستنتج",
  "subtitle": "وصف قصير جدًا (سطر واحد)",
  "mindMap": {"center": "الموضوع المركزي (كلمتين-تلاتة)", "branches": [{"label": "اسم الفرع", "points": ["نقطة قصيرة 1", "نقطة قصيرة 2"]}]},
  "sections": [{"heading": "عنوان القسم", "bullets": ["نقطة شرح (ممكن تحتوي [[ADD]]..[[/ADD]] أو [[TERM:..]]..[[/TERM]])"], "detail": "فقرة شرح موسّعة (4-7 جمل) تربط الأفكار وتوضّح العلاقات", "imageSearchTerm": "English Wikipedia term e.g. Pulpotomy", "visuals": [{"ts": 123.4, "phrase": "عندنا هنا الشكل ده", "what": "رسم تشريحي يوضّح الطبقات", "source": "frame", "wikiTerm": "Tooth anatomy", "callouts": ["Enamel", "Dentin"]}]}],
  "comparisons": [{"title": "الفرق بين X وY", "columns": ["المعيار", "الأول", "التاني"], "rows": [["معيار", "قيمة أ", "قيمة ب"]]}],
  "qna": [{"question": "سؤال الطالب كامل", "answer": "إجابة المحاضر كاملة ومفصّلة"}],
  "review": {"keyPoints": ["نقطة مراجعة"], "definitions": [{"term": "مصطلح", "meaning": "تعريف مختصر"}], "quickQuiz": [{"question": "سؤال مراجعة يختبر الفهم", "answer": "إجابة مختصرة"}]}
}

الأعداد: sections من 5 لـ 12 حسب طول المحتوى وعمقه، comparisons [] لو مفيش مقارنة صريحة، qna [] لو مفيش سؤال في النص، review.keyPoints من 8 لـ 15، quickQuiz من 5 لـ 10، mindMap.branches بعدد sections تقريبًا، و visuals [] لو القسم مفيهوش إشارة بصرية. اكتب بالعربية المصرية الواضحة وسيب المصطلحات العلمية بلغتها الأصلية.""")
    }

    /** يحلل التفريغ لهيكل مذكرة (JSON). بيتخزّن في الدوكيومنت ويتعاد بس لو التفريغ اتغيّر. يتنده من خيط خلفي. */
    fun analyze(ctx: Context, d: Doc): JSONObject {
        val h = segsHash(d)
        if (d.analysis.isNotBlank() && d.analysisHash == h) try { return JSONObject(d.analysis) } catch (_: Throwable) {}
        val ks = keys(); if (ks.isEmpty()) throw IOException("مفيش مفتاح Gemini — ضيفه من الإعدادات الأول")
        val lines = synchronized(d) {
            d.segs.sortedBy { it.start }.joinToString("\n") { "[" + fmtTime(it.start) + " | " + it.start.toInt() + "] " + (if (it.spk == "S") "(طالب) " else "") + it.text }
        }
        if (lines.isBlank()) throw IOException("مفيش تفريغ لسه — فرّغ المحاضرة الأول")
        val text = if (lines.length > AN_MAX_CHARS) lines.take(AN_MAX_CHARS) + "\n...[اتقطع باقي النص لطول المحاضرة]" else lines
        val prompt = analysisPrompt(text)
        val mdl = model()
        var last: Throwable? = null
        for (a in 0 until minOf(ks.size, 3) + 2) {
            val key = ks[a % ks.size]
            try {
                val r = Api.generate(mdl, key, prompt, null, 24000, 0.3, true, false)
                val j = parseJson(r.text)
                if (j != null && (j.optJSONArray("sections")?.length() ?: 0) > 0) {
                    synchronized(d) { d.analysis = j.toString(); d.analysisN++; d.analysisHash = h }
                    save(ctx, d); return j
                }
                last = IOException("رد التحليل مش مكتمل (${r.finish})")
            } catch (e: NoKey) { throw e }
            catch (e: ApiErr) {
                last = e
                if (e.code == 429 || e.code >= 500) { try { Thread.sleep(1500L * (a + 1)) } catch (_: InterruptedException) {} }
            } catch (e: Throwable) { last = e }
        }
        throw IOException("فشل التحليل: " + (last?.message ?: "").take(100))
    }

    // ===================== تحليل الناتج للعرض =====================
    class Pt(val text: String, val bullet: Boolean, val web: Boolean)
    class Sec(val head: String, val pts: List<Pt>)
    class Outline(val title: String, val secs: List<Sec>)

    fun parseOutline(raw: String): Outline {
        var title = ""; val secs = ArrayList<Sec>(); var head: String? = null; var pts = ArrayList<Pt>()
        fun flush() { val h = head; if (h != null || pts.isNotEmpty()) secs.add(Sec(h ?: "", pts)); pts = ArrayList(); head = null }
        for (l0 in raw.lines()) {
            val l = l0.trim(); if (l.isEmpty()) continue
            when {
                l.startsWith("## ") -> { flush(); head = l.removePrefix("## ").trim() }
                l.startsWith("# ") -> title = l.removePrefix("# ").trim()
                l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ") -> { val t = l.substring(2).trim(); pts.add(Pt(t.removePrefix("🌐").trim(), true, t.startsWith("🌐"))) }
                else -> pts.add(Pt(l.removePrefix("🌐").trim(), false, l.startsWith("🌐")))
            }
        }
        flush()
        return Outline(title, secs)
    }

    fun outlineText(title: String, raw: String): String {
        val o = parseOutline(raw); val sb = StringBuilder()
        val t = o.title.ifBlank { title }; if (t.isNotBlank()) sb.append(t).append("\n\n")
        for (s in o.secs) {
            if (s.head.isNotBlank()) sb.append(s.head).append('\n')
            for (p in s.pts) sb.append(if (p.bullet) "• " else "").append(if (p.web) "🌐 " else "").append(p.text).append('\n')
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}
