package com.tttt.subtitler

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** نص ظاهر على الشاشة (لافتة/عنوان/هاردسب) بموضعه ونسبه (0..1) وستايله */
class VisBox(
    val x: Float, val y: Float, val w: Float, val h: Float, val angle: Float,
    val original: String, val translated: String, val bg: Int, val fg: Int, val opacity: Int, val hasBox: Boolean,
    /** (v185) شفافية الظهور/الاختفاء (fade) — 1 = كامل */
    val alpha: Float = 1f
)
class VisFrame(val t: Double, val boxes: List<VisBox>, val dur: Double = VisualMode.SNAP_DUR)

/**
 * الوضع البصري (v206): خوارزمية «الهجين» منقولة من نسخة الـ HTML.
 * فريم كل ثانية (أو نص ثانية في الالتقاط الحي) ← تخطّي لو مفيش تغيير بصري ← تخطّي لو مفيش نص ← طابور 30 فريم ←
 * مجموعات 6 فريمات في صورة شبكة واحدة (أرقام + مسطرة مواضع) ← Gemini بالتوازي ← خريطة زمنية بمفتاح ربع ثانية
 * (دمج + حد أدنى للعرض + فلتر شعارات + إزالة تكرار الحدود) ← لوج وأحداث ظهور/اختفاء. المنطق الصرف في HybridVis.kt.
 */
class VisualMode(
    private val conf: Conf,
    private val position: () -> Double,
    private val retriever: () -> MediaMetadataRetriever?,
    private val say: (String) -> Unit,
    private val changed: () -> Unit,
    /** (v182) لقط فريم من شاشة المشغّل نفسها (PixelCopy) — للمصادر اللي MediaMetadataRetriever مابيقراهاش (m3u8) */
    private val grab: (() -> Bitmap?)? = null,
    /** (v206) لوج تفصيلي (كل SKIP/queue/دفعة/حدث) — من غير إشعارات على الشاشة. لو null بيروح لـ say */
    private val log: ((String) -> Unit)? = null
) {
    companion object {
        private val active = java.util.concurrent.atomic.AtomicInteger(0)
        /** (v154) true لو الوضع البصري شغّال دلوقتي — التنقيح الجزئي مابيستعملش مفاتيح الصور وقتها */
        fun anyActive(): Boolean = active.get() > 0
        const val STEP = 1.0            // خطوة مسح الفريمات من الملف (زي مسح الـ HTML)
        const val LIVE_STEP = 0.5       // خطوة الالتقاط الحي من الشاشة (m3u8)
        const val CELL_MAX = 480        // أكبر ضلع لخلية الفريم جوه الشبكة (480×270 للأفقي)
        const val DEADLINE = 15.0       // لو مكان التشغيل قرّب من أقدم فريم في الطابور بأقل من كده نبعت من غير ما نستنى 30
        const val SNAP_DUR = 6.0        // ثواني عرض نتيجة اللقطة
        const val FADE = 0.2            // ثواني الظهور/الاختفاء التدريجي
        const val REST_MIN = 2_500L     // أقل راحة لنفس المفتاح بين طلبين
        const val REST_MAX = 20_000L
        /** (v181) تشغيل تلقائي مع فتح أي فيديو (لو فيه مفتاح للوضع البصري) — الافتراضي شغّال */
        fun auto(): Boolean = try { Cfg.bool("vis_auto", true) } catch (_: Throwable) { true }
        /** أقصى مسافة مسح قدّام مكان التشغيل (ثواني) — بتوفّر الكوتة لو الفيديو طويل */
        fun ahead(): Double = try { Cfg.int("vis_ahead", 180).coerceIn(30, 3600).toDouble() } catch (_: Throwable) { 180.0 }

        const val HYBRID_SYSTEM = "أنت نظام OCR وترجمة بصري. أمامك شبكة فريمات. كبّر (zoom in) على كل خلية بمفردها بأقصى دقة ممكنة قبل قراءتها — النصوص صغيرة ويسهل تفويتها. افحص كل خلية بالترتيب من #1 حتى #§N§ واحدة واحدة. ممنوع رصد أي نص عربي هو أصلاً ترجمة حرقة (hardsub) أسفل/أعلى الفيديو — تجاهله تمامًا. أخرج JSON فقط."

        const val HYBRID_PROMPT = """أنت نظام OCR وترجمة بصري متخصص للفيديو. أمامك صورة واحدة كبيرة فيها §N§ فريماً مرتبة في شبكة §ROWS§×§COLS§. كل خلية لها رقم (#1-#§N§) ووقتها بالثانية/مللي ثانية في الشريط السفلي (مثال: "4.350s" = 4.35 ثانية).

الفريمات ومواضعها:
§LIST§

🔍 كبّر (zoom in) ذهنياً على كل خلية بمفردها وافحصها بأقصى دقة كأنها صورة منفصلة — النصوص صغيرة ويسهل تفويتها.
⚠️ افحص كل خلية (#1-#§N§) بالترتيب، خلية خلية — لا تتجاوز أي خلية. فيها نص؟ أضفه. فارغة؟ تجاوزها بصمت.
⏱️ لاحظ أول وآخر فريم ظهرت/اختفت فيه كل جملة بدقة (مش افتراض إنها ظاهرة طول الدفعة).

🚫🚫 ممنوع منعاً باتاً: أي نص عربي مكتوب كترجمة أسفل أو أعلى الفيديو (الترجمة الجاهزة/الحرقة/hardsub) — سواء كانت الترجمة بالعربي الفصحى أو العامية. هذا النص موجود بالفعل كترجمة ولا يجب اكتشافه أو "ترجمته" مرة أخرى. لو شفت سطر عربي في نفس مكان الترجمة المعتاد (عادة أسفل الشاشة) — تجاهله فورًا ولا تضعه في الإخراج نهائيًا، حتى لو كان واضحًا وسهل القراءة.

✅ ابحث عن أي نص مكتوب في الصورة غير عربي أياً كان نوعه ولغته (إنجليزي، ياباني، كوري، صيني، فرنسي... إلخ): لافتات، محلات، أسماء شوارع/مبانٍ، نصوص شاشات/تلفزيونات داخل المشهد، كتابات على ملابس/أغراض/سيارات/أبواب، لافتات إعلانية/مطاعم/فنادق، وكذلك أي عناوين/كروت نصية/ترجمة أو كابشن مكتوب على الفيديو نفسه (حتى لو مش جزء من "مشهد" — زي نص عنوان حلقة، كارت معلومات، ترجمة إنجليزية/يابانية أسفل الشاشة). القاعدة الوحيدة: أي نص مش عربي = افحصه ورصده، مهما كان شكله أو مكانه.
❌ تجاهل فقط: النص العربي المحجوب/الحرقة (هارد سب) كما ذُكر أعلاه، شعار القناة/الواترمارك، واجهة المشغّل، أرقام شريط الفريم السفلي، خطوط وأرقام النسبة الصفراء/السماوية على حواف الخلية (دي علامات قياس موضع بس — استخدمها للمرجع، لا تدرجها كنص).

═ قواعد الموضع ═
كل خلية فيها علامات قياس: خطوط صفراء كل 10% (وسماوي عند 50%) مع أرقام (0.1, 0.5, 0.9) — استخدمها كمسطرة بدل التخمين:
x=مركز النص أفقياً (0=يسار→1=يمين) | y=مركز النص رأسياً (0=أعلى→1=أسفل) | w=عرض النص÷عرض الخلية | h=ارتفاع النص÷ارتفاع الخلية | angle=زاوية الميل (موجب=عكس عقارب الساعة)
مثال: بين علامتي 0.8-0.9 أفقيًا → x≈0.85. العلامات نسبية، تشتغل مهما كانت دقة الفيديو الأصلي.

═ قواعد أرقام الفريمات (مهم جداً) ═
frames: مصفوفة أرقام صحيحة بسيطة لكل الفريمات اللي ظهر فيها نفس النص بالظبط — استخدم رقم الفريم زي ما هو مكتوب بالضبط (مثال: [1,2,3])، بدون أي رموز أو نصوص إضافية. نفس النص في خلايا متتالية → entry واحدة بكل أرقامها. اختفى وعاد لاحقاً → entry منفصلة.

🚫🚫 ممنوع منعاً باتاً دمج أكتر من نص منفصل (له مكانه الخاص على الشاشة) في entry واحدة. لو في نفس الفريم فيه كذا نص/فقاعة/تعليق/كومنت متفرقين في أماكن مختلفة (حتى لو نفس النوع أو الستايل، زي حائط تعليقات/دردشة فوق فيديو فيه كذا فقاعة كل واحدة جنب شخص مختلف) — كل واحد منهم entry منفصلة تماماً بإحداثيات x/y الخاصة بيه (مكان الفقاعة نفسها، مش مكان تجميعي واحد). ممنوع تجميع نصوص متعددة بفواصل أسطر (\n) جوه original/translated واحدة إلا لو كانت فعلياً سطور متتالية داخل نفس اللافتة/البوكس الواحد بعينه (زي كريدت فيه كذا سطر تحت بعض في نفس المكان).

═ قواعد الستايل ═
bg_hex: لون خلفية النص hex (لو بوكس/لافتة اكتب لونها، لو نص مباشر على المشهد اكتب "#000000" مع opacity_pct=0)
text_hex: لون النص hex | opacity_pct: شفافية الخلفية 0-100 | has_box: true لو جوه إطار/بوكس

═ الترجمة ═
عربية فصحى مبسطة. العلامات التجارية: كما هي + الترجمة بين قوسين (مثال: "SALE 50% OFF"→"تخفيض ٥٠٪"). الأرقام والتواريخ كما هي.
🌉 لو اللغة الأصلية للنص (original) مش إنجليزي ولا عربي (زي ياباني/كوري/صيني/فرنسي/إسباني... إلخ): سجّل ترجمة إنجليزية حرفية-صريحة تماماً للنص الأصلي في حقل en_pivot (نقل مباشر للمعنى زي ما هو مكتوب بالضبط، من غير تجميل أو حذف). لو original أصلاً إنجليزي أو عربي، سيب en_pivot فارغة "".

═ الإخراج JSON فقط ═
{"texts":[
  {"frames":[1,2],"x":0.75,"y":0.35,"w":0.22,"h":0.06,"angle":0,"original":"OPEN 24H","translated":"مفتوح ٢٤ ساعة","en_pivot":"","lang":"en","bg_hex":"#CC0000","text_hex":"#FFFFFF","opacity_pct":90,"has_box":true},
  {"frames":[3],"x":0.4,"y":0.78,"w":0.18,"h":0.04,"angle":-8,"original":"TAXI","translated":"تاكسي","en_pivot":"","lang":"en","bg_hex":"#000000","text_hex":"#FFFF00","opacity_pct":0,"has_box":false}
]}
لو مفيش نصوص: {"texts":[]}. لا شرح ولا مقدمة — JSON فقط."""

        const val PROMPT_SCENE = "أنت نظام OCR وترجمة بصري متخصص للفيديو. أمامك فريم واحد من فيديو.\n" +
            "🔍 افحص الصورة بأقصى دقة ممكنة — النصوص أحياناً صغيرة ويسهل تفويتها.\n" +
            "🚫🚫 ممنوع منعاً باتاً: أي نص عربي مكتوب كترجمة أسفل أو أعلى الفيديو (الترجمة الجاهزة/الحرقة/hardsub) — تجاهله تمامًا.\n" +
            "✅ ابحث عن أي نص غير عربي أياً كان نوعه ولغته (إنجليزي، ياباني، كوري، صيني، فرنسي...): لافتات، محلات، أسماء شوارع/مبانٍ، نصوص شاشات/تلفزيونات، كتابات على ملابس/أغراض/سيارات، عناوين/كروت نصية/كابشن على الفيديو نفسه.\n" +
            "❌ تجاهل: النص العربي الحرقة، شعار القناة/الواترمارك، واجهة المشغّل.\n" +
            "═ قواعد الموضع (نسبة من 0 إلى 1) ═\n" +
            "x=مركز النص أفقياً (0=يسار→1=يمين) | y=مركز النص رأسياً (0=أعلى→1=أسفل) | w=عرض النص÷عرض الصورة | h=ارتفاع النص÷ارتفاع الصورة | angle=زاوية الميل (موجب=عكس عقارب الساعة)\n" +
            "═ قواعد الستايل ═\n" +
            "bg_hex: لون خلفية النص hex (لو بوكس/لافتة اكتب لونها، لو نص مباشر على المشهد اكتب \"#000000\" مع opacity_pct=0)\n" +
            "text_hex: لون النص hex | opacity_pct: شفافية الخلفية 0-100 | has_box: true لو جوه إطار/بوكس\n" +
            "═ الترجمة ═\nعربية فصحى مبسطة. العلامات التجارية: كما هي + الترجمة بين قوسين. الأرقام والتواريخ كما هي.\n" +
            "═ الإخراج JSON فقط ═\n" +
            "{\"texts\":[{\"x\":0.75,\"y\":0.35,\"w\":0.22,\"h\":0.06,\"angle\":0,\"original\":\"OPEN 24H\",\"translated\":\"مفتوح ٢٤ ساعة\",\"lang\":\"en\",\"bg_hex\":\"#CC0000\",\"text_hex\":\"#FFFFFF\",\"opacity_pct\":90,\"has_box\":true}]}\n" +
            "لو مفيش نصوص: {\"texts\":[]}. لا شرح ولا مقدمة — JSON فقط."

        const val PROMPT_HARDSUB = "أنت نظام OCR متخصص في قراءة الترجمة المحروقة (hardsub) الموجودة بالفعل على فيديو. أمامك فريم واحد.\n" +
            "اقرأ نص الترجمة الهاردسب الظاهر فعليًا (سطر أو سطرين غالبًا أسفل أو أعلى الفيديو) وترجمه: §DIALECT§\n" +
            "🚫 ممنوع قراءة أو ترجمة أي نص تاني غير الهاردسب نفسه (لافتات، شعارات، عناوين، كتابة على الملابس…) إلا لو كان بنفس لغة الهاردسب بالظبط.\n" +
            "🚫 لو مفيش هاردسب ظاهر في الفريم، رجّع قائمة فاضية.\n" +
            "§LANG§\n" +
            "أعطني كمان موضع سطر الهاردسب: y=مركز السطر رأسياً (0=أعلى→1=أسفل)، h=ارتفاع الكتلة÷ارتفاع الصورة.\n" +
            "═ الإخراج JSON فقط ═\n{\"lines\":[{\"original\":\"النص كما ظهر\",\"translated\":\"الترجمة العربية\",\"y\":0.88,\"h\":0.09}]}\nلا شرح ولا مقدمة — JSON فقط."

    }

    @Volatile var running = false
        private set
    @Volatile var mode = "scene"          // scene | hardsub (للقطة اليدوية)
    @Volatile var hardLang = ""
    @Volatile var status = ""
        private set
    private val frames = java.util.concurrent.CopyOnWriteArrayList<VisFrame>()   // نتايج اللقطات اليدوية
    private var th: Thread? = null
    @Volatile private var gen = 0
    var sent = 0; private set
    /** عدد جمل الوضع البصري (نصوص الخريطة + نتايج اللقطات اليدوية) — للعدّاد فوق الفيديو */
    val count: Int get() = map.boxCount() + frames.sumOf { it.boxes.size }

    private val map = HMap()
    /** المدد اللي اتفحصت فعلًا (a,b) — بتتحفظ مع الفيديو عشان مانعيدش فحصها لما نرجع للفيديو */
    private val done = ArrayList<DoubleArray>()
    private var file: File? = null
    private var cacheKey = ""
    private var cacheList: List<VisBox> = emptyList()

    // ── حالة المسح (خيط المسح بس) ──
    private class QF(val t: Double, val span: Double, val qk: Int, val bmp: Bitmap, val vw: Int, val vh: Int) {
        @Volatile var hold: Double = t
        var tries = 0
        @Volatile var requeued = false
    }
    private class Cell(val bmp: Bitmap, val vw: Int, val vh: Int)
    private class Grid(val jpeg: ByteArray, val cols: Int, val rows: Int)
    private class HParsed(val frames: List<Int>, val box: HBox)

    private val queue = ArrayList<QF>()
    private val inflight = java.util.concurrent.atomic.AtomicInteger(0)
    private var flushEx: java.util.concurrent.ExecutorService? = null
    private var lastSig: FloatArray? = null
    private var lastQ: QF? = null
    private var grabFails = 0
    @Volatile private var restMs = REST_MIN
    @Volatile private var lastAddAt = 0L
    private var pool = KeyPool(emptyList())

    /** مفاتيح الوضع البصري: كل طلب بياخد مفتاح فاضي مش في راحة (429) — التوازي = عدد المفاتيح */
    private class KeyPool(val keys: List<String>) {
        private val cool = HashMap<String, Long>()
        private val busy = HashSet<String>()
        @Volatile private var rr = 0
        fun acquire(alive: () -> Boolean): String? {
            while (alive()) {
                val now = System.currentTimeMillis()
                synchronized(this) {
                    for (i in keys.indices) {
                        val k = keys[(rr + i) % keys.size]
                        if (!busy.contains(k) && (cool[k] ?: 0L) <= now) { busy.add(k); rr = rr + i + 1; return k }
                    }
                }
                Thread.sleep(250)
            }
            return null
        }
        fun release(k: String, coolMs: Long) {
            synchronized(this) { busy.remove(k); cool[k] = System.currentTimeMillis() + coolMs }
        }
    }

    private fun lg(m: String) { try { (log ?: say)(m) } catch (_: Throwable) {} }
    private fun fx(d: Double, n: Int): String = String.format(Locale.US, "%." + n + "f", d)
    private fun alive(g: Int): Boolean = running && gen == g

    // ───────────────────────── الحفظ والتحميل ─────────────────────────

    /** مسح كل النتائج (في الذاكرة وعلى الديسك) */
    fun clear() {
        map.clear(); frames.clear(); synchronized(done) { done.clear() }; sent = 0; cacheKey = ""; cacheList = emptyList()
        try { file?.delete() } catch (_: Throwable) {}
    }

    /** (v185) مسح من الثانية دي وقدّام بس (إعادة تحليل من مكان التشغيل) — اللي قبلها يفضل زي ما هو */
    fun clearFrom(sec: Double) {
        map.removeFrom(sec)
        frames.removeIf { it.t >= sec }
        synchronized(done) {
            val it2 = done.iterator()
            while (it2.hasNext()) { val r = it2.next(); if (r[0] >= sec) it2.remove() else if (r[1] > sec) r[1] = sec }
        }
        cacheKey = ""; cacheList = emptyList()
        save()
    }

    /** (v185) ربط الوضع البصري بملف الفيديو: بيحمّل النتايج المحفوظة (لو فيه) وكل نتيجة جديدة بتتحفظ فيه */
    fun bind(f: File) {
        file = f
        try { load() } catch (_: Throwable) {}
    }

    private fun boxJson(b: VisBox): JSONObject = JSONObject().put("x", b.x.toDouble()).put("y", b.y.toDouble()).put("w", b.w.toDouble()).put("h", b.h.toDouble())
        .put("a", b.angle.toDouble()).put("o", b.original).put("t", b.translated).put("bg", b.bg).put("fg", b.fg).put("op", b.opacity).put("hb", b.hasBox)
    private fun boxFrom(o: JSONObject): VisBox = VisBox(o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(), o.optDouble("w", 0.15).toFloat(), o.optDouble("h", 0.04).toFloat(),
        o.optDouble("a", 0.0).toFloat(), o.optString("o"), o.optString("t"), o.optInt("bg", Color.BLACK), o.optInt("fg", Color.WHITE), o.optInt("op", 80), o.optBoolean("hb"))

    private fun hboxJson(b: HBox): JSONObject {
        val o = JSONObject().put("x", b.x.toDouble()).put("y", b.y.toDouble()).put("w", b.w.toDouble()).put("h", b.h.toDouble()).put("a", b.angle.toDouble())
            .put("o", b.original).put("t", b.translated).put("l", b.lang).put("bg", b.bgHex).put("fg", b.fgHex).put("op", b.opacity).put("hb", b.hasBox)
        val sb = b.sampledBg
        if (sb != null) o.put("sb", sb)
        return o
    }
    private fun hboxFrom(o: JSONObject): HBox = HBox(o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(), o.optDouble("w", 0.15).toFloat(), o.optDouble("h", 0.04).toFloat(),
        o.optDouble("a", 0.0).toFloat(), o.optString("o"), o.optString("t"), o.optString("l"), o.optString("bg"), o.optString("fg"), o.optInt("op", 80), o.optBoolean("hb"),
        if (o.has("sb")) o.optInt("sb") else null)

    @Synchronized fun save() {
        val f = file ?: return
        try {
            val root = JSONObject().put("v", 2)
            val da = org.json.JSONArray(); synchronized(done) { for (r in done) da.put(org.json.JSONArray().put(r[0]).put(r[1])) }
            root.put("done", da)
            val sa = org.json.JSONArray()
            for (s in map.copySegs()) {
                val bs = org.json.JSONArray(); for (b in s.boxes) bs.put(hboxJson(b))
                sa.put(JSONObject().put("s", s.sq).put("e", s.eq).put("fw", s.fw).put("fh", s.fh).put("b", bs))
            }
            root.put("segs", sa)
            val fa = org.json.JSONArray()
            for (fr in frames) { val bs = org.json.JSONArray(); for (b in fr.boxes) bs.put(boxJson(b)); fa.put(JSONObject().put("t", fr.t).put("d", fr.dur).put("b", bs)) }
            root.put("snaps", fa)
            if (map.isEmpty() && frames.isEmpty() && done.isEmpty()) { f.delete(); return }
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp"); tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.writeText(root.toString(), Charsets.UTF_8); tmp.delete() }
        } catch (_: Throwable) {}
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        val root = JSONObject(f.readText(Charsets.UTF_8))
        frames.clear(); synchronized(done) { done.clear() }
        // ملفات الإصدارات القديمة (v<2) كان فيها نوافذ بنظام تاني: بنحتفظ بس باللقطات اليدوية ونعيد المسح
        if (root.optInt("v", 1) >= 2) {
            root.optJSONArray("done")?.let { a -> synchronized(done) { for (i in 0 until a.length()) { val r = a.optJSONArray(i) ?: continue; done.add(doubleArrayOf(r.optDouble(0), r.optDouble(1))) } } }
            val segs = ArrayList<HSeg>()
            root.optJSONArray("segs")?.let { a -> for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val s = HSeg(o.optInt("s"), o.optInt("e"), o.optInt("fw", 0), o.optInt("fh", 0))
                val bs = o.optJSONArray("b")
                if (bs != null) for (j in 0 until bs.length()) { val bo = bs.optJSONObject(j) ?: continue; s.boxes.add(hboxFrom(bo)) }
                segs.add(s)
            } }
            map.load(segs)
        } else map.clear()
        root.optJSONArray("snaps")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val bs = o.optJSONArray("b") ?: continue
            frames.add(VisFrame(o.optDouble("t"), (0 until bs.length()).mapNotNull { j -> bs.optJSONObject(j)?.let { boxFrom(it) } }, o.optDouble("d", SNAP_DUR)))
        } }
    }

    /** ضم مدة جديدة للمدد اللي اتفحصت (بيدمج المتلامس) */
    private fun markDone(a: Double, b: Double) {
        if (b <= a) return
        synchronized(done) {
            var na = a; var nb = b
            val it2 = done.iterator()
            while (it2.hasNext()) { val r = it2.next(); if (r[1] >= na - 0.05 && r[0] <= nb + 0.05) { na = minOf(na, r[0]); nb = maxOf(nb, r[1]); it2.remove() } }
            done.add(doubleArrayOf(na, nb))
        }
    }
    /** لو w داخل مدة اتفحصت نقفز لآخرها (من غير ما نعيد فحصها) */
    private fun skipDone(w: Double): Double {
        var x = w
        synchronized(done) { var moved = true; var n = 0; while (moved && n++ < 50) { moved = false; for (r in done) if (r[0] <= x + 0.05 && r[1] > x + 0.5) { x = r[1]; moved = true } } }
        return x
    }
    private fun isDone(t: Double): Boolean {
        synchronized(done) { for (r in done) if (r[0] <= t && t < r[1]) return true }
        return false
    }

    // ───────────────────────── لقطة واحدة (يدوي / هاردسب) ─────────────────────────

    private fun keyList(): List<String> = conf.visKeys.filter { it.length > 10 }.distinct()

    /** لقطة واحدة: بتتبعت لـ Gemini والنصوص المترجمة بتتعرض على الفيديو في مكانها */
    fun snap(bmp: Bitmap, nowSec: () -> Double, onDone: () -> Unit = {}) {
        Thread {
            try { snapWork(bmp, nowSec) } catch (e: Exception) { say("⚠ " + (e.message ?: "").take(80)) }
            finally { try { onDone() } catch (_: Exception) {} }
        }.also { it.isDaemon = true; it.start() }
    }
    private fun snapWork(bmp: Bitmap, nowSec: () -> Double) {
        val keys = keyList()
        if (keys.isEmpty()) { say("ضيف مفتاح للوضع البصري (الإعدادات ← مفتاح الوضع البصري فقط)"); return }
        val jpeg = toJpeg(bmp); bmp.recycle()
        val prompt = if (mode == "hardsub") PROMPT_HARDSUB.replace("§DIALECT§", "بلهجة ${conf.lang} وأسلوب ${conf.style}")
            .replace("§LANG§", if (hardLang.isBlank()) "لغة الهاردسب: اكتشفها تلقائيًا من السطر المحروق." else "لغة الهاردسب في هذا الفيديو هي: \"$hardLang\" — اقرأ فقط الأسطر المكتوبة بيها.") else PROMPT_SCENE
        var last = ""
        var attempts = 0; var emptyTries = 0
        loop@ for (round in 0 until 3) for (key in keys) {
            if (attempts++ >= 6) break@loop
            try {
                val res = Api.generateImage(conf.model, key, prompt, jpeg)
                val boxes = parseOrNull(res.text)
                if (boxes == null) { last = "رد Gemini مش مفهوم"; say("👁 الرد مش واضح — بحاول تاني"); Thread.sleep(400); continue }
                // رد فاضي مرة واحدة ممكن يكون عشوائية من الموديل — نعيد مرة قبل ما نقول «مفيش نصوص»
                if (boxes.isEmpty() && emptyTries++ < 1) { say("👁 مفيش نصوص — محاولة تانية للتأكد"); Thread.sleep(300); continue }
                val t = nowSec()   // الفيديو واقف: النتيجة بتتعرض من لحظة اللقطة
                frames.removeIf { Math.abs(it.t - t) < 0.5 }   // (v185) اللقطات بتتحفظ مع الفيديو: بنشيل بس اللي في نفس اللحظة
                frames.add(VisFrame(t, boxes, SNAP_DUR)); sent++; save()
                say(if (boxes.isEmpty()) "👁 مفيش نصوص واضحة في اللقطة" else "👁 اتترجم ${boxes.size} نص — اتعرض على الفيديو")
                changed(); return
            } catch (e: ApiErr) { last = e.message ?: ""; say("👁 خطأ ${e.code} على …${key.takeLast(4)}"); if (e.code == 429) Thread.sleep(1500) }
            catch (e: java.io.IOException) { last = e.message ?: "مشكلة اتصال"; say("👁 مشكلة اتصال — بحاول تاني"); Thread.sleep(800) }
        }
        say("👁 فشل: " + last.take(110))
    }

    // ───────────────────────── التشغيل والإيقاف ─────────────────────────

    fun start() {
        stop(); running = true
        val myGen = ++gen
        flushEx = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }
        val t = Thread {
            active.incrementAndGet()
            try { loop(myGen); if (alive(myGen)) lg("👁 الحلقة خلصت") }
            catch (e: Throwable) { status = "⚠ " + e.javaClass.simpleName + ": " + (e.message ?: "").take(80); lg(status) }   // (v182) السبب بيتسجّل في اللوج دايمًا
            finally { if (th === Thread.currentThread()) running = false; active.decrementAndGet() }
        }
        t.isDaemon = true; th = t; t.start()
    }
    fun stop() {
        running = false; th?.interrupt(); th = null
        try { flushEx?.shutdownNow() } catch (_: Throwable) {}
        flushEx = null
        synchronized(queue) { for (q in queue) try { q.bmp.recycle() } catch (_: Throwable) {}; queue.clear() }
        save()
    }

    // ───────────────────────── حلقة المسح ─────────────────────────

    private fun loop(g: Int) {
        val r = retriever()
        val live = r == null   // (v182) m3u8 / مصدر مش مقروء: بنلقط الفريمات من الشاشة وقت التشغيل
        if (live && grab == null) { status = "المصدر ده مش مدعوم للوضع البصري"; say(status); return }
        val keys = keyList()
        if (keys.isEmpty()) { say("ضيف مفتاح للوضع البصري (الإعدادات ← مفتاح الوضع البصري فقط)"); try { r?.release() } catch (_: Exception) {}; return }
        pool = KeyPool(keys)
        val durSec = if (r == null) 0.0 else try { (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0 } catch (_: Exception) { 0.0 }
        lastSig = null; lastQ = null; grabFails = 0
        lg("الوضع البصري بدأ (probe + skip + batch) — " + (if (live) "التقاط حي من الشاشة (m3u8) كل نص ثانية" else "مسح فريم كل ثانية من الفيديو"))
        if (live) lg("👁 المصدر m3u8 — بلقط الفريمات من الشاشة وقت التشغيل (النتيجة بتتأخر شوية عن التشغيل)")
        var cursor = skipDone(Math.floor(position()))
        var runStart = cursor
        var phase = 0            // 0 = لسه ما بدأش، 1 = مسح أول دقيقة، 2 = مسح الخلفية
        var firstMinEnd = cursor + 60.0
        var finished = false
        var liveLast = -1.0
        try {
            while (alive(g)) {
                val pos = position()
                deadlineFlush(pos, live)
                if (live) {
                    // قفز/رجوع: الفريمات القديمة مالهاش لازمة
                    if (liveLast >= 0 && (pos < liveLast - 0.5 || pos > liveLast + 5.0)) { flushNow(); lastSig = null; lastQ = null }
                    if (liveLast >= 0 && pos < liveLast + LIVE_STEP - 0.05) { Thread.sleep(100); continue }
                    while (alive(g) && pendingFrames() > HybridVis.BATCH_SIZE * 3) Thread.sleep(250)
                    val bmp = try { grab?.invoke() } catch (_: Throwable) { null }
                    if (bmp == null) {
                        if (++grabFails >= 8) { status = "⚠ مش قادر ألقط فريمات من الشاشة (الفيديو محمي أو السطح مش جاهز)"; lg(status); return }
                        Thread.sleep(300); continue
                    }
                    grabFails = 0
                    liveLast = pos
                    if (isDone(pos)) { try { bmp.recycle() } catch (_: Throwable) {}; continue }
                    val cell = makeCell(bmp)
                    if (cell == null) { lg("SKIP @" + fx(pos, 2) + "s — فيديو مش جاهز بعد"); continue }
                    handleFrame(cell, pos, LIVE_STEP)
                    continue
                }
                // ── مسح من الملف ──
                // اليوزر قفز (قدّام أو ورا) أو إحنا متأخرين عن التشغيل: نبدأ من مكان التشغيل
                if (pos > cursor + 2.0 || pos < runStart - 1.0) {
                    flushNow()
                    cursor = skipDone(Math.floor(pos)); runStart = cursor
                    lastSig = null; lastQ = null; phase = 0; firstMinEnd = cursor + 60.0; finished = false
                    lg("▶ مكان التشغيل اتغيّر — مسح من " + fx(cursor, 0) + "ث")
                }
                if (durSec > 0 && cursor >= durSec - 0.3) {
                    flushNow()
                    if (!finished) { finished = true; lg("✅ مسح الخلفية اكتمل"); status = "👁 المسح خلص | " + map.boxCount() + " نص" }
                    Thread.sleep(600); continue
                }
                if (cursor > pos + ahead()) { flushNow(); Thread.sleep(500); continue }
                val c0 = skipDone(cursor)
                if (c0 != cursor) { cursor = c0; continue }
                while (alive(g) && pendingFrames() > HybridVis.BATCH_SIZE * 3) Thread.sleep(250)
                if (!alive(g)) break
                if (phase == 0) {
                    phase = 1
                    lg("بدأ مسح أول دقيقة (" + fx(cursor, 0) + "s → " + fx(if (durSec > 0) Math.min(firstMinEnd, durSec) else firstMinEnd, 0) + "s)")
                } else if (phase == 1 && cursor >= firstMinEnd) {
                    phase = 2; flushNow()
                    lg("✅ مسح أول دقيقة اكتمل")
                    if (durSec > firstMinEnd) lg("▶ مسح الخلفية: " + fx(firstMinEnd, 0) + "s → " + fx(durSec, 0) + "s")
                }
                val t = cursor
                cursor += STEP
                var raw: Bitmap? = null
                try { raw = r!!.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { raw = null }
                val cell = if (raw != null) makeCell(raw) else null
                if (cell == null) { lg("SKIP @" + fx(t, 2) + "s — فيديو مش جاهز بعد"); continue }
                handleFrame(cell, t, STEP)
            }
        } catch (_: InterruptedException) {
        } finally { try { r?.release() } catch (_: Exception) {} }
    }

    private fun queueSize(): Int { synchronized(queue) { return queue.size } }
    private fun pendingFrames(): Int = queueSize() + inflight.get()

    /** طابور مستني ومكان التشغيل قرّب منه (أو الطابور قديم في الالتقاط الحي): نبعته من غير ما نستنى 30 فريم */
    private fun deadlineFlush(pos: Double, live: Boolean) {
        var due = false
        synchronized(queue) {
            if (queue.isNotEmpty()) {
                val first = queue[0].t
                due = if (live) pos - first >= 10.0 else first <= pos + DEADLINE
                if (!due && System.currentTimeMillis() - lastAddAt > 6000L) due = true   // مفيش فريمات جديدة بقالها فترة (إيقاف مؤقت)
            }
        }
        if (due) flushNow()
    }

    /** تحليل فريم واحد: تخطّي «لا تغيير» ← تخطّي «لا نص» ← طابور */
    private fun handleFrame(c: Cell, t: Double, span: Double) {
        val qk = Math.round(t * 4).toInt()
        val sig = HybridVis.lumGrid(pixelsOf(c.bmp, HybridVis.DIFF_GRID))
        val diff = HybridVis.sigDiff(lastSig, sig)
        if (lastSig != null && diff < HybridVis.DIFF_THRESHOLD) {
            lg("SKIP @" + fx(t, 2) + "s — لا تغيير (" + fx(diff * 100, 1) + "%)")
            // الشاشة لسه زي ما هي: أي نص اتكشف في آخر فريم اتبعت لسه ظاهر لحد دلوقتي
            val lq = lastQ
            if (lq != null && t > lq.hold) lq.hold = t
            markDone(t, t + span)
            try { c.bmp.recycle() } catch (_: Throwable) {}
            return
        }
        lastSig = sig
        val score = HybridVis.textScore(HybridVis.lumGrid(pixelsOf(c.bmp, HybridVis.TEXT_GRID)), HybridVis.TEXT_GRID)
        if (score < HybridVis.TEXT_THRESHOLD) {
            lg("SKIP @" + fx(t, 2) + "s — لا نص (score " + fx(score * 100, 1) + "%)")
            lastQ = null
            markDone(t, t + span)
            try { c.bmp.recycle() } catch (_: Throwable) {}
            return
        }
        val q = QF(t, span, qk, c.bmp, c.vw, c.vh)
        lastQ = q
        synchronized(queue) { queue.add(q) }
        lastAddAt = System.currentTimeMillis()
        val n = queueSize()
        lg("قيد @" + fx(t, 2) + "s (q" + qk + ") — queue: " + n)
        status = "👁 مسح @" + fx(t, 0) + "ث | queue " + n + " | " + map.boxCount() + " نص"
        if (n >= HybridVis.BATCH_SIZE) flush()
    }

    private fun pixelsOf(b: Bitmap, n: Int): IntArray {
        val s = Bitmap.createScaledBitmap(b, n, n, true)
        val px = IntArray(n * n)
        s.getPixels(px, 0, n, 0, 0, n, n)
        if (s !== b) s.recycle()
        return px
    }

    // ───────────────────────── الالتقاط ─────────────────────────

    /**
     * فريم خام ← خلية بحجم أقصاه 480 (زي خلية الشبكة): حجب شريط الواترمارك العلوي (6%) وتفتيح الفريمات الغامقة.
     * الفريم الخام بيتعمله recycle هنا.
     */
    private fun makeCell(raw: Bitmap): Cell? {
        try {
            val vw = raw.width; val vh = raw.height
            if (vw < 2 || vh < 2) { raw.recycle(); return null }
            val m = Math.max(vw, vh)
            val sc = if (m > CELL_MAX) CELL_MAX.toFloat() / m else 1f
            val cw = Math.max(2, Math.round(vw * sc)); val ch = Math.max(2, Math.round(vh * sc))
            var dst = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            val cv = Canvas(dst)
            cv.drawBitmap(raw, Rect(0, 0, vw, vh), Rect(0, 0, cw, ch), Paint(Paint.FILTER_BITMAP_FLAG))
            raw.recycle()
            val blk = Paint(); blk.color = Color.BLACK
            cv.drawRect(0f, 0f, cw.toFloat(), Math.round(ch * 0.06f).toFloat(), blk)
            // تحسين تلقائي للفريمات الغامقة (مش بيأثر على الفيديو المعروض)
            val s16 = Bitmap.createScaledBitmap(dst, 16, 16, true)
            val px = IntArray(256); s16.getPixels(px, 0, 16, 0, 0, 16, 16)
            if (s16 !== dst) s16.recycle()
            var sum = 0.0; for (p in px) sum += HybridVis.lum(p)
            val avg = sum / 256.0
            if (avg < 60.0) {
                val strength = Math.min(1.0, (60.0 - avg) / 60.0)
                val bright = 1.0 + strength * 0.35
                val contr = 1.0 + strength * 0.25
                val scale = (bright * contr).toFloat()
                val trans = (128.0 - 128.0 * contr).toFloat()
                val cm = ColorMatrix(floatArrayOf(scale, 0f, 0f, 0f, trans, 0f, scale, 0f, 0f, trans, 0f, 0f, scale, 0f, trans, 0f, 0f, 0f, 1f, 0f))
                val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                val p2 = Paint(); p2.colorFilter = ColorMatrixColorFilter(cm)
                Canvas(out).drawBitmap(dst, 0f, 0f, p2)
                dst.recycle(); dst = out
            }
            return Cell(dst, vw, vh)
        } catch (_: Throwable) { return null }
    }

    /** لون الخلفية الفعلي حوالين النص: متوسط شريحة رفيعة من أعلى وأسفل منطقة النص (احتمال أكبر إنها خلفية مش حروف) */
    private fun sampleBg(b: Bitmap, nx: Float, ny: Float, nw: Float, nh: Float): Int? {
        try {
            val cw = b.width; val ch = b.height
            val bx = Math.max(0, Math.round((nx - nw / 2f) * cw))
            val by = Math.max(0, Math.round((ny - nh / 2f) * ch))
            if (bx >= cw || by >= ch) return null
            val bw = Math.max(1, Math.min(cw - bx, Math.round(nw * cw)))
            val bh = Math.max(1, Math.min(ch - by, Math.round(nh * ch)))
            val strip = Math.max(1, Math.round(bh * 0.18f))
            var r = 0L; var gg = 0L; var bl = 0L; var n = 0L
            val h1 = Math.min(strip, ch - by)
            val p1 = IntArray(bw * h1); b.getPixels(p1, 0, bw, bx, by, bw, h1)
            for (p in p1) { r += (p shr 16) and 255; gg += (p shr 8) and 255; bl += p and 255; n++ }
            val botY = Math.max(by, by + bh - strip)
            val h2 = Math.min(strip, ch - botY)
            if (h2 > 0) {
                val p2 = IntArray(bw * h2); b.getPixels(p2, 0, bw, bx, botY, bw, h2)
                for (p in p2) { r += (p shr 16) and 255; gg += (p shr 8) and 255; bl += p and 255; n++ }
            }
            if (n == 0L) return null
            return Color.rgb((r / n).toInt(), (gg / n).toInt(), (bl / n).toInt())
        } catch (_: Throwable) { return null }
    }

    // ───────────────────────── الإرسال (باتش ← مجموعات ← Gemini) ─────────────────────────

    private fun flushNow() { while (queueSize() > 0) flush() }

    /** ياخد لحد 30 فريم من الطابور ويبعتهم في سلسلة متسلسلة (الباتش التالي بيستنى اللي قبله) */
    private fun flush() {
        val ex = flushEx ?: return
        val batch = ArrayList<QF>()
        synchronized(queue) {
            val n = Math.min(HybridVis.BATCH_SIZE, queue.size)
            for (i in 0 until n) batch.add(queue.removeAt(0))
        }
        if (batch.isEmpty()) return
        val g = gen
        inflight.addAndGet(batch.size)
        try {
            ex.execute {
                try { doFlush(batch, g) }
                catch (_: InterruptedException) {}
                catch (e: Throwable) { lg("خطأ: " + e.javaClass.simpleName + " " + (e.message ?: "").take(80)) }
                finally { inflight.addAndGet(-batch.size) }
            }
        } catch (_: Throwable) {
            inflight.addAndGet(-batch.size)
            for (f in batch) try { f.bmp.recycle() } catch (_: Throwable) {}
        }
    }

    private fun doFlush(batch: List<QF>, g: Int) {
        val subs = ArrayList<List<QF>>()
        var i = 0
        while (i < batch.size) { subs.add(batch.subList(i, Math.min(i + HybridVis.SUBBATCH_SIZE, batch.size))); i += HybridVis.SUBBATCH_SIZE }
        status = "👁 تحليل " + batch.size + " فريم (" + subs.size + " مجموعات)…"
        lg("🧠 تحليل " + batch.size + " فريم (" + subs.size + " مجموعات متوازية)…")
        val ths = ArrayList<Thread>()
        for (sb in subs) {
            val t = Thread { try { doSub(sb, g) } catch (_: Throwable) {} }
            t.isDaemon = true; ths.add(t); t.start()
        }
        try { for (t in ths) t.join() } catch (e: InterruptedException) { for (x in ths) x.interrupt(); throw e }
        if (alive(g)) {
            map.dedupe()
            map.rebuildEvents(Math.round(position() * 4).toInt()) { lg(it) }
            status = "👁 " + sent + " طلب | " + map.boxCount() + " نص"
            save(); changed()
        }
        for (f in batch) if (!f.requeued) try { f.bmp.recycle() } catch (_: Throwable) {}
    }

    /** مجموعة فريمات (≤6) ← شبكة واحدة ← Gemini ← الخريطة. فشل نهائي: الفريمات بترجع للطابور (3 محاولات بس) */
    private fun doSub(sb: List<QF>, g: Int) {
        val n = sb.size
        val grid = buildGrid(sb)
        val prompt = buildPrompt(sb, grid.cols, grid.rows)
        val system = HYBRID_SYSTEM.replace("§N§", n.toString())
        var last = ""
        var attempt = 0
        while (attempt < 4 && alive(g)) {
            val key = pool.acquire { alive(g) } ?: break
            var coolMs = restMs
            var ok = false
            try {
                val res = Api.generateImage(conf.model, key, prompt, grid.jpeg, 3000, 0.0, system)
                val parsed = parseHybrid(res.text, sb)
                if (parsed == null) { last = "رد Gemini مش مفهوم"; lg("⚠ الرد مش واضح — بحاول تاني") }
                else { applyResult(sb, parsed); ok = true; restMs = Math.max(REST_MIN, restMs * 8 / 10); coolMs = restMs }
            } catch (e: ApiErr) {
                last = e.message ?: ""
                if (e.code == 429) {
                    val sec = Regex("(\\d+(?:\\.\\d+)?)\\s*s").find(e.message ?: "")?.groupValues?.get(1)?.toDoubleOrNull()
                    coolMs = ((sec ?: 60.0) * 1000).toLong().coerceIn(15_000L, 120_000L)
                    restMs = Math.min(REST_MAX, restMs * 2)
                    lg("⏸ 429 على …" + key.takeLast(4) + " — راحة " + (coolMs / 1000) + "ث")
                } else if (e.code == 400 || e.code == 401 || e.code == 403) {
                    coolMs = 600_000L
                    lg("⚠ المفتاح …" + key.takeLast(4) + " مرفوض (" + e.code + "): " + (e.message ?: "").take(90))
                } else { coolMs = 3_000L; lg("⚠ خطأ " + e.code + ": " + (e.message ?: "").take(90)) }
            } catch (e: java.io.IOException) {
                last = e.message ?: "مشكلة اتصال"; coolMs = 1_500L; lg("⚠ مشكلة اتصال — بحاول تاني")
            } catch (e: InterruptedException) {
                pool.release(key, 0L); throw e
            } catch (e: Exception) {
                last = e.javaClass.simpleName + " " + (e.message ?: ""); lg("⚠ خطأ " + e.javaClass.simpleName + ": " + (e.message ?: "").take(80))
            }
            pool.release(key, coolMs)
            if (ok) { sent++; return }
            attempt++
        }
        if (!alive(g)) return
        lg("❌ فشل تحليل " + n + " فريم: " + last.take(100))
        for (f in sb) {
            f.tries++
            if (f.tries < 3) { f.requeued = true; synchronized(queue) { queue.add(0, f) } }
        }
    }

    /** بيحط نتيجة مجموعة في الخريطة ويسجّل في اللوج */
    private fun applyResult(sb: List<QF>, parsed: List<HParsed>) {
        val frs = ArrayList<HFrame>()
        for (f in sb) frs.add(HFrame(f.qk, Math.round(f.hold * 4).toInt(), f.vw, f.vh))
        val places = ArrayList<HPlace>()
        for (p in parsed) for (fi in p.frames) {
            val f = sb[fi - 1]
            places.add(HPlace(f.qk, p.box.withBg(sampleBg(f.bmp, p.box.x, p.box.y, p.box.w, p.box.h))))
        }
        val noise = ArrayList<String>()
        map.apply(frs, places, noise)
        for (m in noise) lg(m)
        for (f in sb) markDone(f.t, f.t + f.span)
        if (parsed.isEmpty()) lg("لا نصوص — دفعة " + sb.size + " فريم")
        else {
            lg(parsed.size.toString() + " نص — دفعة " + sb.size + " فريم")
            for (p in parsed) {
                val f0 = sb[p.frames[0] - 1]
                lg("  [ف" + p.frames[0] + "@" + fx(f0.t, 2) + "s] ← \"" + p.box.original + "\" → \"" + p.box.translated + "\"")
            }
        }
        changed()
    }

    private fun buildPrompt(sb: List<QF>, cols: Int, rows: Int): String {
        val list = StringBuilder()
        for ((i, f) in sb.withIndex()) {
            if (i > 0) list.append('\n')
            list.append("فريم #").append(i + 1).append(" عند ").append(fx(f.t, 3)).append("s — موضعه في الصورة: العمود ").append((i % cols) + 1).append(" الصف ").append((i / cols) + 1)
        }
        return HYBRID_PROMPT.replace("§N§", sb.size.toString()).replace("§ROWS§", rows.toString()).replace("§COLS§", cols.toString()).replace("§LIST§", list.toString())
    }

    /** شبكة صورة واحدة من الفريمات: إطار أبيض + علامات مواضع كل 10% (سماوي عند 50%) + شريط سفلي «#رقم  وقت بالمللي» */
    private fun buildGrid(fr: List<QF>): Grid {
        val n = fr.size
        val cols = Math.max(1, Math.ceil(Math.sqrt(n * (16.0 / 9.0))).toInt())
        val rows = Math.ceil(n.toDouble() / cols).toInt()
        val cw = fr[0].bmp.width; val ch = fr[0].bmp.height
        val label = 28; val border = 3; val pad = 6
        val totalW = cols * (cw + border * 2 + pad) + pad
        val totalH = rows * (ch + label + border * 2 + pad) + pad
        val bmp = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(17, 17, 17))
        val stroke = Paint(); stroke.style = Paint.Style.STROKE; stroke.color = Color.WHITE; stroke.strokeWidth = border.toFloat()
        val tick = Paint(Paint.ANTI_ALIAS_FLAG)
        val small = Paint(Paint.ANTI_ALIAS_FLAG); small.textSize = 9f; small.typeface = Typeface.MONOSPACE
        val txt = Paint(Paint.ANTI_ALIAS_FLAG); txt.color = Color.WHITE; txt.textSize = 14f; txt.textAlign = Paint.Align.CENTER; txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val bar = Paint(); bar.color = Color.argb(209, 0, 0, 0)
        for ((i, f) in fr.withIndex()) {
            val col = i % cols; val row = i / cols
            val x = (pad + col * (cw + border * 2 + pad) + border).toFloat()
            val y = (pad + row * (ch + label + border * 2 + pad) + border).toFloat()
            val hb = border / 2f
            c.drawRect(x - hb, y - hb, x - hb + cw + border, y - hb + ch + label + border, stroke)
            c.drawBitmap(f.bmp, x, y, null)
            for (p in 1..9) {
                val pf = p / 10f
                val mid = p == 5
                tick.color = if (mid) Color.argb(217, 0, 255, 255) else Color.argb(140, 255, 255, 0)
                tick.strokeWidth = if (mid) 1.4f else 0.8f
                val lx = x + pf * cw
                c.drawLine(lx, y, lx, y + 7f, tick); c.drawLine(lx, y + ch - 7f, lx, y + ch, tick)
                val ly = y + pf * ch
                c.drawLine(x, ly, x + 7f, ly, tick); c.drawLine(x + cw - 7f, ly, x + cw, ly, tick)
                if (p == 1 || p == 5 || p == 9) {
                    small.color = if (mid) Color.argb(230, 0, 255, 255) else Color.argb(180, 255, 255, 0)
                    c.drawText(String.format(Locale.US, "%.1f", pf), lx + 1f, y + 10f, small)
                }
            }
            c.drawRect(x, y + ch, x + cw, y + ch + label, bar)
            c.drawText("#" + (i + 1) + "  " + fx(f.t, 3) + "s", x + cw / 2f, y + ch + label / 2f + 5f, txt)
        }
        val o = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, o)
        bmp.recycle()
        return Grid(o.toByteArray(), cols, rows)
    }

    /** رد Gemini: كل نص معاه frames:[أرقام] — بنتجاهل أي نص من غير أرقام صالحة. null = الرد مش JSON مفهوم (يتعاد) */
    private fun parseHybrid(txt: String, sb: List<QF>): List<HParsed>? {
        val j = Parse.json(txt) ?: return null
        val a = j.optJSONArray("texts") ?: return if (j.has("texts")) emptyList() else null
        val out = ArrayList<HParsed>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val og = o.optString("original").trim(); val tr = o.optString("translated").trim()
            val idx = java.util.TreeSet<Int>()
            val fa = o.optJSONArray("frames")
            if (fa != null) {
                for (k in 0 until fa.length()) {
                    val d = fa.optDouble(k, Double.NaN)
                    if (d.isNaN()) continue
                    val nn = Math.round(d).toInt()
                    if (nn >= 1 && nn <= sb.size) idx.add(nn)
                }
            } else {
                val d = o.optDouble("frame", Double.NaN)
                if (!d.isNaN()) { val nn = Math.round(d).toInt(); if (nn >= 1 && nn <= sb.size) idx.add(nn) }
            }
            if (idx.isEmpty()) { lg("⚠️ تجاهل نص \"" + og + "\" — مفيش frames صالحة في الرد"); continue }
            if (og.isEmpty() || tr.isEmpty()) continue
            val box = HBox(frac(o.optDouble("x", 0.5), 0.5), frac(o.optDouble("y", 0.5), 0.5),
                frac(o.optDouble("w", 0.15), 0.15).coerceIn(0.03f, 1f), frac(o.optDouble("h", 0.04), 0.04).coerceIn(0.02f, 0.5f),
                o.optDouble("angle", 0.0).toFloat().coerceIn(-45f, 45f), og, tr, o.optString("lang"),
                o.optString("bg_hex"), o.optString("text_hex"), o.optInt("opacity_pct", 80).coerceIn(0, 100), o.optBoolean("has_box"))
            out.add(HParsed(idx.toList(), box))
        }
        return out
    }

    // ───────────────────────── السجل (للعرض) ─────────────────────────

    /** جدول الترجمات البصرية (من → إلى + الأصل والترجمة) */
    fun tableText(): String = map.tableText()
    /** أحداث الظهور/التمديد/الرجوع/الاختفاء */
    fun eventsText(): String = map.eventsText()

    // ───────────────────────── أدوات ─────────────────────────

    private fun toJpeg(b: Bitmap, maxSide: Int = 1024, q: Int = 82): ByteArray {
        val m = maxOf(b.width, b.height); val sc = if (m > maxSide) maxSide.toFloat() / m else 1f
        val s = if (sc < 1f) Bitmap.createScaledBitmap(b, (b.width * sc).toInt().coerceAtLeast(1), (b.height * sc).toInt().coerceAtLeast(1), true) else b
        val o = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, q, o)
        if (s !== b) s.recycle()
        return o.toByteArray()
    }

    private fun col(h: String?, def: Int): Int = try { Color.parseColor((h ?: "").trim()) } catch (_: Exception) { def }

    /** Gemini ساعات بيرجّع الإحداثيات 0-100 أو 0-1000 بدل 0-1 — بنرجّعها لنسبة صحيحة عشان النص مايطلعش بره الفيديو */
    private fun frac(v: Double, def: Double): Float {
        if (v.isNaN()) return def.toFloat()
        val n = when { v > 1.5 && v <= 100.0 -> v / 100.0; v > 100.0 -> v / 1000.0; else -> v }
        return n.coerceIn(0.0, 1.0).toFloat()
    }

    private fun boxOf(o: JSONObject): VisBox? {
        val tr = o.optString("translated").trim(); val og = o.optString("original").trim()
        if (tr.isEmpty() || og.isEmpty()) return null
        return VisBox(frac(o.optDouble("x", 0.5), 0.5), frac(o.optDouble("y", 0.5), 0.5),
            frac(o.optDouble("w", 0.15), 0.15).coerceIn(0.03f, 1f), frac(o.optDouble("h", 0.04), 0.04).coerceIn(0.02f, 0.5f),
            o.optDouble("angle", 0.0).toFloat().coerceIn(-45f, 45f), og, tr, col(o.optString("bg_hex"), Color.BLACK), col(o.optString("text_hex"), Color.WHITE), o.optInt("opacity_pct", 80).coerceIn(0, 100), o.optBoolean("has_box"))
    }

    /** null = الرد مش JSON مفهوم (يتعاد)، قائمة فاضية = JSON سليم من غير نصوص */
    private fun parseOrNull(txt: String): List<VisBox>? {
        val j = Parse.json(txt) ?: return null
        val out = ArrayList<VisBox>()
        j.optJSONArray("texts")?.let { a -> for (i in 0 until a.length()) { a.optJSONObject(i)?.let { o -> boxOf(o)?.let { out.add(it) } } } }
        j.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val tr = o.optString("translated").trim(); val og = o.optString("original").trim()
            if (tr.isEmpty() || og.isEmpty()) continue
            val y = frac(o.optDouble("y", 0.88), 0.88).coerceIn(0.05f, 0.97f); val h = frac(o.optDouble("h", 0.09), 0.09).coerceIn(0.05f, 0.3f)
            out.add(VisBox(0.5f, y, 0.9f, h, 0f, og, tr, Color.BLACK, Color.WHITE, 90, true))
        } }
        return out
    }

    /** نتايج اللقطات اليدوية (فريم + مدة) */
    private fun frameBoxes(sec: Double): List<VisBox> {
        var f: VisFrame? = null
        for (x in frames) if (x.t <= sec + 0.3 && (f == null || x.t > f.t)) f = x
        return if (f != null && sec <= f.t + f.dur) f.boxes else emptyList()
    }

    /** HBox ← VisBox: لون الخلفية = bg_hex من Gemini، وإلا المسحوب من الفريم، وإلا أسود */
    private fun toVis(b: HBox, alpha: Float): VisBox =
        VisBox(b.x, b.y, b.w, b.h, b.angle, b.original, b.translated, col(b.bgHex, b.sampledBg ?: Color.BLACK), col(b.fgHex, Color.WHITE), b.opacity, b.hasBox, alpha)

    /**
     * اللي يتعرض عند الثانية دي. sec = وقت اللقطات اليدوية (بعد تزامن الترجمة)، media = وقت الفيديو الفعلي:
     * المقطع النشط (الأضيق مدى) بيظهر قبل توقيته بنص ثانية وبيفضل بعده نص ثانية بظهور/اختفاء تدريجي.
     */
    fun boxesAt(sec: Double, media: Double = sec): List<VisBox> {
        val base = frameBoxes(sec)
        val v = map.activeAt(Math.round(media * 4).toInt()) ?: return base
        val s = v.sq / 4.0 - HybridVis.LEAD_Q / 4.0
        val e = v.eq / 4.0 + HybridVis.LAG_Q / 4.0
        val fin = ((media - s) / FADE).coerceIn(0.0, 1.0)
        val fout = ((e - media) / FADE).coerceIn(0.0, 1.0)
        val a = Math.min(fin, fout)
        if (a >= 0.999) {
            val key = v.sq.toString() + ":" + v.ver
            if (key != cacheKey) { cacheKey = key; cacheList = v.boxes.map { toVis(it, 1f) } }
            return if (base.isEmpty()) cacheList else base + cacheList
        }
        val al = (0.1 + 0.9 * a).toFloat()
        val out = ArrayList<VisBox>(v.boxes.size + base.size)
        out.addAll(base)
        for (b in v.boxes) out.add(toVis(b, al))
        return out
    }
}

/** طبقة رسم فوق الفيديو: بترسم نصوص الوضع البصري في مواضعها (نسب من حدود الفيديو الفعلية) */
class VisualOverlay(ctx: Context) : View(ctx) {
    var boxes: List<VisBox> = emptyList()
    var area: () -> RectF = { RectF(0f, 0f, width.toFloat(), height.toFloat()) }
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    fun showBoxes(b: List<VisBox>) { if (b !== boxes && !(b.isEmpty() && boxes.isEmpty())) { boxes = b; invalidate() } }
    private fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    override fun onDraw(c: Canvas) {
        if (boxes.isEmpty()) return
        val r = area(); val d = resources.displayMetrics.density
        if (r.width() < 20f || r.height() < 20f) return
        for (b in boxes) drawBox(c, r, d, b)
    }

    /** بيرسم النص جوه حدود الفيديو دايمًا: الخط بيصغر والمربع بيتزحزح لحد ما المستطيل (بعد الميل) يبقى كله جوه الفيديو */
    private fun drawBox(c: Canvas, r: RectF, d: Float, b: VisBox) {
        val rad = Math.toRadians(b.angle.toDouble())
        val ca = Math.abs(Math.cos(rad)).toFloat(); val sa = Math.abs(Math.sin(rad)).toFloat()
        val margin = 4f * d; val pad = 4f * d
        val maxW = r.width() - 2 * margin; val maxH = r.height() - 2 * margin
        var bw = minOf(maxOf(b.w * r.width() * 1.1f, 70f * d), maxW * 0.95f)
        val bhT = maxOf(b.h * r.height(), 14f * d)
        var ts = minOf(bhT * 0.8f, r.height() * 0.12f).coerceAtLeast(9f * d)
        tp.color = b.fg
        var lay: StaticLayout
        var boxH: Float; var bbW: Float; var bbH: Float
        while (true) {
            tp.textSize = ts
            lay = StaticLayout.Builder.obtain(b.translated, 0, b.translated.length, tp, bw.toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            boxH = maxOf(bhT, lay.height.toFloat())
            val rw = bw + 2 * pad; val rh = boxH + 2 * pad
            bbW = rw * ca + rh * sa; bbH = rw * sa + rh * ca
            val fits = bbW <= maxW && bbH <= maxH && lay.height <= bhT * 1.25f
            if (fits || ts <= 9f * d) break
            ts *= 0.9f
        }
        // آخر حارس: لو لسه أكبر من الفيديو بعد أصغر خط، نصغّر الرسمة كلها
        val sc = minOf(1f, maxW / bbW, maxH / bbH)
        val sW = bbW * sc; val sH = bbH * sc
        val cx = (r.left + b.x * r.width()).coerceIn(r.left + margin + sW / 2, r.right - margin - sW / 2)
        val cy = (r.top + b.y * r.height()).coerceIn(r.top + margin + sH / 2, r.bottom - margin - sH / 2)
        // ألوان: خلفية تغطي الأصلي + تباين كافي للنص
        val hasFill = b.opacity > 0
        val bg = if (hasFill) b.bg else Color.BLACK
        val alpha = if (hasFill) maxOf(b.opacity * 255 / 100, 225) else 175
        var fg = b.fg
        if (Math.abs(lum(fg) - lum(bg)) < 0.4f) fg = if (lum(bg) > 0.5f) Color.BLACK else Color.WHITE
        tp.color = fg; bgP.color = bg; bgP.alpha = (alpha * b.alpha).toInt().coerceIn(0, 255); tp.alpha = (255 * b.alpha).toInt().coerceIn(0, 255)
        // لازم نعيد بناء الـ layout بعد تغيير اللون مش مطلوب (اللون بيتقري وقت draw)
        c.save(); c.translate(cx, cy); c.rotate(-b.angle); c.scale(sc, sc)
        val rect = RectF(-bw / 2 - pad, -boxH / 2 - pad, bw / 2 + pad, boxH / 2 + pad)
        c.drawRoundRect(rect, 6f * d, 6f * d, bgP)
        c.translate(-bw / 2, -lay.height / 2f); lay.draw(c)
        c.restore()
    }
}

