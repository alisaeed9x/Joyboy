package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface Host {
    fun log(s: String)
    fun status(s: String)
    /** القائمة اتغيرت (الواجهة تحدّث الترجمة المعروضة) */
    fun changed()
    /** مكان التشغيل الحالي بالثواني */
    fun position(): Double
    /** مدة الفيديو من المشغّل (0 لو لسه مش معروفة) */
    fun playerDuration(): Double
    /** إشعار صغير للمستخدم (فشل/نجاح باتش) — الافتراضي مفيش */
    fun notice(s: String) {}
}

private class BadReply(msg: String) : Exception(msg)
/** (v151) مفتاح الباتش فشل ومفيش مفتاح فاضي دلوقتي: الباتش بيرجع للطابور ويستنى أقرب مفتاح يخلص باتشه (مش بيتحط فوق باتش شغال) */
private class RehomeEx(val avoid: String?) : Exception("rehome")

class Engine(
    @Volatile private var conf: Conf,
    private val openSource: () -> AudioSource,
    private val store: Store?,
    private val host: Host,
    private val pb: PromptBuilder
) {
    companion object {
        const val OVERLAP = 4.0          // ثواني تداخل بين المقاطع (زي الأصل)
        const val MAX_TRIES = 25
        const val MAX_FAILS = 3          // جولات فشل قبل ما المقطع يتحط كـ "فجوة"
        const val GAP_PASSES = 3         // كام مرة نعيد المحاولة في الفجوات تلقائيًا
        const val PRIOR_CAP = 400        // أقصى عدد جمل قديمة نبعتها للمراجعة
        const val CHAR_MIN_LINES = 12
        const val PRON_MIN_LINES = 20
        const val HOLE_MIN = 1.2         // أقل ثغرة صوتية من غير ترجمة نعيد طلبها (ثواني)
        const val HOLE_MAX = 14          // أقصى عدد ثغرات بنسدّها لكل مقطع
        const val TGROUP_MIN = 4         // (v210) الوضعين 3/4: أقل عدد باتشات منسوخة تتجمّع قبل ما تتبعت للترجمة (إلا لو الدنيا مستعجلة)
        const val TGROUP_MAX = 5         // (v210) أقصى عدد باتشات في طلب ترجمة واحد
        const val TR_MAX_WAIT_MS = 40_000L   // (v210) باتش منسوخ مستني الترجمة أكتر من كده يتبعت من غير ما يستنى التجميع
        const val TR_ROUNDS = 4          // (v210) جولات إعادة الجمل الناقصة قبل ما الباتش يرجع كطلب كامل (صوت ← ترجمة)
    }

    private val lock = Any()
    /** المشغّل بيحمّل الترجمة المحفوظة في الخلفية: لحد ما يخلص ممنوع أي حفظ (عشان مانكتبش حالة فاضية فوق الترجمة المحفوظة) */
    @Volatile var persistBlocked = false
    /** (v139) بعد stop() الإنجن بيتقفل نهائيًا: أي رد متأخر من Gemini (طلب كان لسه في الجو) ما يقدرش يغيّر الترجمة ولا يتحفظ */
    @Volatile private var isClosed = false
    @Volatile var subs: List<Sub> = emptyList()
        private set(v) { if (!isClosed) field = v }
    private val chars = ArrayList<Chr>()
    private val gloss = ArrayList<Gloss>()
    private val tplCache = HashMap<String, String>()
    private val tplBusy = HashSet<String>()
    private val tplFails = HashMap<String, Int>()
    @Volatile private var srcLang = ""
    @Volatile private var detDone = false
    private val offSession = Offline.Session()   // (v267) قفل لغة الأوفلاين للفيديو ده
    private val chunkLang = ConcurrentHashMap<Int, String>()   // (v413) اللغة اللي اتفرّغ بيها كل باتش أوفلاين
    init { Offline.detectedLang = "" }
    private val langVote = OffLang.Vote()   // (v411) لغة المصدر للترجمة: Whisper + فحص الحروف (كانا/هانجول/هان)
    private val done = Ranges()
    private val failed = ConcurrentHashMap<Int, Int>()
    private val gapPass = ConcurrentHashMap<Int, Int>()
    @Volatile private var lastGapTry = 0L
    @Volatile private var basePool = Pool(conf)
    // (v210) الوضعين 3 و4: حوضين منفصلين — مفاتيح الترانسكريبشن (pool) ومفاتيح الترجمة (trPool). الاحتياطي مشترك. بيتبنوا من جديد بس لو المفاتيح/الوضع اتغيروا
    // (v220) تبديل وضع المفاتيح أثناء الترجمة: conf.keyMode = اللي اليوزر اختاره، activeMode = اللي الشغل الجاري ماشي بيه. لو اختلفوا: الباتشات الشغالة
    // (والمنسوخة المستنية ترجمة) بتخلص بالوضع القديم، مفيش باتشات جديدة بتتبعت، وبعدها activeMode بيتبدّل والباتشات الجديدة تمشي بالوضع الجديد.
    @Volatile private var activeMode: Int = conf.keyMode
    private val switching: Boolean get() = conf.keyMode != activeMode
    private fun drained(): Boolean = inflight.isEmpty() && tHold.isEmpty() && tTaken.isEmpty() && trBusy.get() == 0 && bkQ.isEmpty() && rehomeQ.isEmpty()
    private var splitSig = ""
    private var txP: Pool? = null
    private var trP: Pool? = null
    @Synchronized private fun splitPools(): Pair<Pool, Pool> {
        val c = conf
        val sig = activeMode.toString() + "|" + c.keys.joinToString(",") + "|" + c.backup.joinToString(",") + "|" + c.refine.joinToString(",")
        val a = txP; val b = trP
        if (a != null && b != null && sig == splitSig) return a to b
        val (nTx, nTr) = KeyMode.txTrCounts(c.keys.size, activeMode)
        val txKeys = c.keys.take(nTx)
        val trKeys = if (c.keys.size <= 1 || activeMode >= KeyMode.TRANSCRIBE_ALL) c.keys else c.keys.takeLast(nTr)   // (v219) الوضع 4: كل المفاتيح بتنسخ وكل المفاتيح بتترجم
        val p1 = Pool(c.withKeys(txKeys)); val p2 = Pool(c.withKeys(trKeys))
        txP = p1; trP = p2; splitSig = sig
        modeMsg = "🔑 وضع $activeMode: ${txKeys.size} مفتاح ترانسكريبشن + ${trKeys.size} مفتاح ترجمة" + (if (c.keys.size <= 1) " (مفتاح واحد بيعمل الاتنين)" else if (activeMode >= KeyMode.TRANSCRIBE_ALL) " (كل المفاتيح بتعمل الاتنين)" else "")   // (v413) مابتتطبعش إلا لما جيميناي يتنده فعلًا
        return p1 to p2
    }
    @Volatile private var modeMsg: String? = null
    private fun flushModeMsg() { val m = modeMsg ?: return; modeMsg = null; host.log(m) }
    private val pool: Pool get() = if (activeMode >= KeyMode.SPLIT_A) splitPools().first else basePool
    private val trPool: Pool get() = splitPools().second
    @Volatile private var running = true
    /** وقفة: المحرك مايبعتش مقاطع جديدة (مستني اختيار المستخدم: كمّل ولا ترجم من جديد) */
    @Volatile var paused = false
    /** إيقاف مؤقت بإيد المستخدم (زرار ⏸ في المشغّل): مفيش مقاطع جديدة بتتبعت لحد ما يتلغي — اللي شغال بيخلص عادي */
    @Volatile var userPaused = false
    /** لما الباتشات حوالين مكان التشغيل تخلص: كمّل ترجمة باقي الفيديو (قدّام ثم من الأول) بدل ما تقف */
    @Volatile var keepGoing = true
    /** ترجمة في الخلفية من غير مشغّل: بيمشي من أول مقطع ناقص وبيخلص لوحده */
    @Volatile var headless = false
    /** ترجمة باتشات محددة بس (0-based) — بعد ما تخلص المحرك بيستنى ومابيكمّلش لقدّام */
    @Volatile var onlyChunks: IntRange? = null
    /** لو اتحدد: المحرك يبدأ من المقطع ده بدل مكان التشغيل لحد ما المشاهدة توصله */
    @Volatile private var forcedCursor = -1
    @Volatile private var forcedSticky = false   // (v158) إعادة من باتش سابق: المؤشر يفضل عليه حتى لو مكان التشغيل بعده
    @Volatile private var fullRefineStarted = false
    /** (v160) مجالات الجمل اللي بتتنقّح دلوقتي (خط أزرق في شريط التقدم) */
    @Volatile var refiningSegs: List<DoubleArray> = emptyList()
    @Volatile private var fullRefineDone = false
    @Volatile private var finished = false
    @Volatile private var gapScanClean = false
    @Volatile var stoppedFlag = false
        private set
    var onFinished: ((Boolean) -> Unit)? = null
    @Volatile var fatal: String? = null
        private set
    @Volatile private var source: AudioSource? = null
    private val rr = AtomicInteger(0)
    private val bounds = ConcurrentHashMap<Int, Double>()      // بداية المقطع بعد تعديلها لأقرب صمت
    private val prepared = ConcurrentHashMap.newKeySet<Int>()
    private val trimLock = Any()
    private val inflight = ConcurrentHashMap.newKeySet<Int>()
    // ===== توزيع الباتشات على المفاتيح + نسخة احتياطية للباتش المتأخر (كل باتش يتحسب مرة واحدة بس) =====
    private val startedAt = ConcurrentHashMap<Int, Long>()          // وقت بداية الباتش الشغال
    private val preps = ConcurrentHashMap<Int, Prep>()              // صوت الباتش الشغال (عشان النسخة الاحتياطية ماتفكوش تاني)
    private val offOf = ConcurrentHashMap<Int, Boolean>()               // (v413) الباتش ده ماشي على Whisper أوفلاين (مش جيميناي)
    private val keyOf = ConcurrentHashMap<Int, String>()            // المفتاح اللي الباتش شغال عليه دلوقتي
    private val hedged = ConcurrentHashMap.newKeySet<Int>()         // باتشات اتبعتلها نسخة احتياطية
    private val claimed = ConcurrentHashMap.newKeySet<Int>()        // باتشات نتيجتها اتطبّقت (أول نسخة تخلص تكسب)
    /** (v151) باتشات فاشلة مستنية أقرب مفتاح فاضي: avoid = المفتاح اللي فشلت عليه، prep = الصوت المفكوك (من غير فك تاني) */
    private class Rehome(val avoid: String?, val at: Long, val prep: Prep?)
    private val rehomeQ = ConcurrentHashMap<Int, Rehome>()
    /** (v420) الأوفلاين: الباتشات المتوازية = خانات الوشبر (Offline.par) حتى لو المفاتيح أقل */
    private val ppk: Int get() = if (Offline.on()) maxOf(conf.parallelPerKey, Offline.par()) else conf.parallelPerKey
    private val perKey get() = ppk.coerceIn(1, 4)
    private val hedgeBusy = AtomicInteger(0)
    private val recentMs = java.util.concurrent.ConcurrentLinkedDeque<Long>()   // أزمنة آخر باتشات خلصت
    // ===== مفتاح/موديل/زمن رد كل باتش =====
    class KeyRun(val keyNo: Int, val model: String, val ms: Long, val viaGemini: Boolean = true)
    private val runs = ConcurrentHashMap<Int, KeyRun>()                // آخر تنفيذ ناجح لكل باتش (مفتاح · موديل · زمن)
    private fun curModel(): String = conf.model
    private fun keyNo(k: String): Int = basePool.all().indexOf(k) + 1
    private fun shortModel(m: String) = m.removePrefix("gemini-")
    private fun fmtSec(ms: Long) = String.format(java.util.Locale.US, "%.1f", ms / 1000.0) + "ث"

    @Volatile private var maxApplied = -1                           // أعلى باتش اتطبّق (لو في باتش قبله لسه شغال يبقى متأخر)
    private val hedgeEx = Executors.newFixedThreadPool(2) { r -> Thread(r).also { it.isDaemon = true } }
    @Volatile private var anyApplied = false
    private val gapTried = Ranges()      // بتتحفظ بين الجلسات
    private val gapSession = Ranges()    // لجلسة التشغيل دي بس (للحد الأقصى)
    private val gapBusy = AtomicInteger(0)
    private val gapKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastGapScan = 0L
    private val gapEx = Executors.newFixedThreadPool(Gaps.MAX_PARALLEL) { r -> Thread(r).also { it.isDaemon = true } }
    private var exec: java.util.concurrent.ExecutorService? = null
    private var autoCharsAttempts = 0
    /** (v107) اسم الفيلم/المسلسل من اسم الملف + الفولدر — بنبحث بيه عن الشخصيات مرة واحدة */
    @Volatile var titleHint: String = ""
    @Volatile private var lookupState = 0   // 0 لسه · 1 شغال · 2 خلص
    /** (v170) العمل اتعرف عليه (من الاسم أو من الحوار) وشخصياته اتضافت */
    @Volatile private var castFound = false
    private var identTried = false
    private var chunksDone = 0; private var lastCharChunk = 0
    private var charsTrySize = 0
    private var charsBusy = false
    private var pronUpTo = 0
    private var pronBusy = false
    /** لهجة التحويل التلقائي: الترجمة بتتعمل بالفصحى الحرفية وكل ~3 باتشات بتتحوّل للهجة دي. "" = من غير تحويل */
    @Volatile var convDialect: String = ""
    /** (v280) «تفريغ بس»: النص بيتفرّغ ويتعرض بلغته الأصلية من غير ترجمة ولا تحويل لهجة ولا تنقيح ولا سدّ ثغرات.
     *  لما يتقفل بعد ما كان شغّال، الجمل الأجنبية بتتعالج بإصلاح اللغة الأجنبية التلقائي (جيميناي) زي ما بيحصل بعد الريستارت */
    @Volatile var transOnly: Boolean = false
        set(v) { val was = field; field = v; if (was && !v) previewChunks.clear() }
    /** أسلوب الترجمة الحي (حرفي/شعبي/جرئ/+18) وشدته (خفيفة/متوسطة/شديدة): بيتحط في أول برومبت كل باتش جديد وكل تحويل لهجة */
    @Volatile var toneStyle: String = ""
    @Volatile var toneStrength: String = "متوسطة"
    fun toneBlock(): String {
        val st = toneStyle
        if (st.isBlank() || st == "حرفي") return ""
        val what = when (st) {
            "شعبي" -> "كلام الشارع الشعبي والتعبيرات اليومية بدل الرسمي والكتابي"
            "جرئ" -> "أجرأ وأكتر حرية في الألفاظ والتعبيرات من غير تلطيف زيادة"
            "+18" -> "صريح بلا تلطيف: الألفاظ الخارجة والإيحاءات تتترجم بصراحة تطابق النص الأصلي بالظبط، من غير حذف ولا تخفيف"
            else -> return ""
        }
        val deg = when (toneStrength) { "خفيفة" -> "خفيفة: لمسة بسيطة بس في الجمل اللي تستاهل"; "شديدة" -> "شديدة: في كل جملة تقريبًا وبأقصى درجة"; else -> "متوسطة: في معظم الجمل بدرجة معتدلة" }
        return "\n═══ أسلوب الترجمة (إلزامي) ═══\n- الأسلوب: $st — $what.\n- الشدة: $deg.\n- الأسلوب ده بيتطبّق على كل translated مع الحفاظ الكامل على المعنى وجنس المتكلم والمخاطَب.\n\n"
    }
    private val convBusy = AtomicBoolean(false)
    @Volatile private var lastConvFail = 0L
    @Volatile private var convFailN = 0
    /** (v150) جمل conv=true لسه شكلها فصحى: بتتعاد مرة واحدة بس (عشان ما نلفّش في حلقة) */
    private val msaTried = ConcurrentHashMap.newKeySet<String>()
    private fun needsConv(s: Sub) = !s.conv || (Msa.fusha(s) && pk(s) !in msaTried)
    /** باتشات رجعت ناقصة (رد اتقطع / من غير جمل رغم وجود صوت) */
    private val incomplete = ConcurrentHashMap.newKeySet<Int>()
    private val bg = Executors.newFixedThreadPool(2) { r -> Thread(r).also { it.isDaemon = true } }
    private val ch get() = conf.chunkSec.toDouble()
    /** عدد المقاطع قدّام = الأكبر بين الإعداد وعدد الطلبات المتوازية، عشان كل المفاتيح تشتغل مع بعض */
    private val window get() = maxOf(conf.ahead.coerceAtLeast(1), pool.capacity(ppk))

    // ===== واجهة للـ UI =====
    fun failedCount() = failed.count { it.value >= MAX_FAILS }
    /** (v146) سطر لكل باتش + هل هو الأسرع في دفعته (بيتلوّن أخضر). الدفعة = الباتشات اللي بتتبعت مع بعض (عددها = عدد الطلبات المتوازية) */
    fun batchRows(): List<Pair<String, Boolean>> {
        val d = host.playerDuration()
        val c = (host.position() / ch).toInt().coerceAtLeast(0)
        val cap = pool.capacity(ppk).coerceAtLeast(1)
        val out = ArrayList<Pair<String, Boolean>>()
        val fastest = HashMap<Int, Int>()   // الدفعة → رقم الباتش الأسرع
        val byWave = runs.entries.groupBy { it.key / cap }
        for ((wv, es) in byWave) if (es.size >= 2) fastest[wv] = es.minByOrNull { it.value.ms }!!.key
        for (i in maxOf(0, c - 1)..c + window + 1) {
            if (d > 0 && cStart(i) >= d) break
            val mark = when {
                i in inflight -> "⏳"
                tHold.containsKey(i) -> "📝"
                (failed[i] ?: 0) >= MAX_FAILS -> "❌"
                i in incomplete -> "⚠"
                isDone(i, d) -> if (subCount(i, d) == 0) "🔇" else "✅"
                (failed[i] ?: 0) > 0 -> "🔁"
                else -> "▫"
            }
            val a = cStart(i).toInt(); val b = cEnd(i, d).toInt()
            val sb = StringBuilder()
            sb.append(if (i == c) "▶ " else "  ").append("باتش ").append(i + 1).append(" ")
                .append("%d:%02d–%d:%02d".format(a / 60, a % 60, b / 60, b % 60)).append(" ").append(mark)
            if (mark == "✅" || mark == "🔁" || mark == "🔇") sb.append(" ").append(subCount(i, d)).append(" جملة")
            val k = keyOf[i]
            val r = runs[i]
            var fast = false
            if (mark == "⏳" && k != null) {
                if (offOf[i] == true) sb.append(" · 🎙 Whisper أوفلاين") else sb.append(" · 🔑").append(keyNo(k)).append(" ").append(shortModel(conf.model))
                startedAt[i]?.let { sb.append(" · ").append(fmtSec(System.currentTimeMillis() - it)) }
            } else if (r != null && (mark == "✅" || mark == "🔇" || mark == "🔁")) {
                fast = fastest[i / cap] == i
                if (r.viaGemini) sb.append(" · 🔑").append(r.keyNo).append(" · ").append(fmtSec(r.ms)).append(" · ").append(shortModel(r.model)).append(if (fast) " ⚡" else "")
                else sb.append(" · 🎙 Whisper · ").append(fmtSec(r.ms)).append(if (fast) " ⚡" else "")
            }
            out.add(Pair(sb.toString(), fast))
        }
        return out
    }
    // ===== أشرطة التقدم (v56) =====
    /** جمل مستنية تتغيّر (تحويل لهجة / إعادة صياغة / تصحيح ضمائر) — مفتاحها start|original */
    private val pend = ConcurrentHashMap.newKeySet<String>()
    private fun pk(s: Sub) = "${s.start}|${s.original}"
    /** مجالات فيها جمل ترجمة فعلًا (الشريط الأخضر): بيتقطع عند الفجوات الحقيقية (> 2ث) مش عند حدود الباتشات */
    fun coverageSegs(): List<DoubleArray> = Coverage.spans(subs, 2.0)
    /** مجالات الجمل اللي لسه هتتغير (الشريط التالت)، فاضية لما كل الجمل تتغير */
    fun pendingSegs(): List<DoubleArray> { if (pend.isEmpty()) return emptyList(); return Coverage.spans(subs.filter { pk(it) in pend }, 3.0) }
    fun pendingCount(): Int = pend.size

    fun coveredSec() = done.total()
    fun durationSec(): Double = currentDur()
    fun chunkCount(): Int { val d = currentDur(); if (d <= 0) return 0; var i = 0; while (cStart(i) < d) i++; return i }
    fun awaitStopped(ms: Long) { val t0 = System.currentTimeMillis(); while (!stoppedFlag && System.currentTimeMillis() - t0 < ms) try { Thread.sleep(50) } catch (_: InterruptedException) { return } }

    class BatchInfo(val idx: Int, val start: Double, val end: Double, val mark: String, val count: Int = 0)
    /** كل الباتشات بحالتها (✅ خلص · ⏳ بيترجم · ❌ فشل · 🔁 بيعيد · ▫ لسه) — لقايمة إعادة الترجمة */
    fun batches(): List<BatchInfo> {
        val d = currentDur(); if (d <= 0) return emptyList()
        val out = ArrayList<BatchInfo>(); var i = 0
        while (cStart(i) < d) {
            val mark = when {
                i in inflight -> "⏳"
                tHold.containsKey(i) -> "📝"
                (failed[i] ?: 0) >= MAX_FAILS -> "❌"
                i in incomplete -> "⚠"
                isDone(i, d) -> if (subCount(i, d) == 0) "🔇" else "✅"
                (failed[i] ?: 0) > 0 -> "🔁"
                else -> "▫"
            }
            out.add(BatchInfo(i, cStart(i), cEnd(i, d), mark, subCount(i, d))); i++
        }
        return out
    }
    /** الباتشات اللي ما اترجمتش (❌) أو رجعت ناقصة (⚠) وملهاش طلب شغال دلوقتي */
    fun problems(): List<BatchInfo> {
        // بتتنادي من الواجهة كل ~0.7ث: ما نلفّش على كل باتشات الفيديو ونعدّ الجمل لكل واحد — بس على الباتشات الفاشلة/الناقصة
        val idx = java.util.TreeSet<Int>()
        for ((k, v) in failed) if (v >= MAX_FAILS) idx.add(k)
        idx.addAll(incomplete)
        if (idx.isEmpty()) return emptyList()
        val d = currentDur(); if (d <= 0) return emptyList()
        return idx.filter { it !in inflight && cStart(it) < d }
            .map { BatchInfo(it, cStart(it), cEnd(it, d), if ((failed[it] ?: 0) >= MAX_FAILS) "❌" else "⚠", 0) }
    }
    // ===== إعادة تلقائية للباتشات الفاشلة/الناقصة (من غير ما المستخدم يدوس حاجة) =====
    private val autoTries = ConcurrentHashMap<Int, Int>()
    private val autoAt = ConcurrentHashMap<Int, Long>()
    private val announced = ConcurrentHashMap.newKeySet<Int>()
    private var lastAutoTick = 0L
    private fun autoRetryTick() {
        val now = System.currentTimeMillis()
        if (now - lastAutoTick < 4000L) return
        lastAutoTick = now
        val d = currentDur(); if (d <= 0) return
        // باتش اتبلّغ إنه فشل واتترجم دلوقتي → إشعار نجاح صغير
        for (i in announced.toList()) {
            if (i in inflight || i in incomplete || (failed[i] ?: 0) >= MAX_FAILS || !isDone(i, d)) continue
            announced.remove(i); autoTries.remove(i); autoAt.remove(i)
            host.notice("✅ تمت ترجمة باتش ${i + 1}")
            host.log("✅ باتش ${i + 1}: اتترجم بعد الإعادة التلقائية")
        }
        for (b in problems()) {
            val i = b.idx
            val zero = b.mark == "⚠" && subCount(i, d) == 0   // رجع من غير جمل: يتعاد مرة واحدة بس للتأكد
            val limit = if (zero) 1 else 3
            val n = autoTries[i] ?: 0
            if (n >= limit) {
                if (zero) { incomplete.remove(i); announced.remove(i); host.notice("🔇 باتش ${i + 1}: اتأكدت إن مفيهوش جمل"); host.log("🔇 باتش ${i + 1}: اتعاد وبرضه من غير جمل — اتأكد إنه فعلاً فاضي") }
                else if (announced.remove(i)) { host.notice("⛔ باتش ${i + 1} لسه فاشل بعد $n محاولات"); host.log("⛔ باتش ${i + 1}: الإعادة التلقائية خلصت ($n) وبرضه فاشل") }
                continue
            }
            if (now < (autoAt[i] ?: 0L)) continue
            autoTries[i] = n + 1; autoAt[i] = now + 12_000L * (n + 1)
            if (announced.add(i)) host.notice(if (zero) "⚠ باتش ${i + 1} رجع من غير جمل — بعيده للتأكد" else if (b.mark == "❌") "❌ باتش ${i + 1} فشل — بعيده تلقائي" else "⚠ باتش ${i + 1} رجع ناقص — بعيده تلقائي")
            host.log("🔁 إعادة تلقائية لباتش ${i + 1} (${n + 1}/$limit)")
            failed.remove(i); incomplete.remove(i); priorityRetry.add(i)
        }
    }

    /** إعادة مجموعة باتشات على المفاتيح الاحتياطية بالتوالي (واحد ورا التاني: الأقدم فالأحدث) في خيط واحد — مش كلهم في نفس الوقت */
    fun retryOnBackupSeq(list: List<Int>) {
        val todo = list.distinct().sorted().filter { inflight.add(it) }
        if (todo.isEmpty()) return
        host.log("🔁 إعادة ${todo.size} باتش على المفاتيح الاحتياطية بالتوالي (الأقدم فالأحدث): " + todo.joinToString("، ") { (it + 1).toString() })
        Thread {
            for (i in todo) {
                try {
                    if (!running) { continue }
                    incomplete.remove(i); failed.remove(i)
                    host.log("🔁 إعادة باتش ${i + 1} على المفاتيح الاحتياطية…")
                    val p = prepare(i, true)
                    if (p != null) { send(i, p, true); host.log("✅ باتش ${i + 1}: خلصت إعادة الترجمة") }
                    else host.log("⚠ باتش ${i + 1}: مفيش صوت اتفك — جرّب تاني بعد شوية")
                } catch (e: Exception) {
                    failed[i] = MAX_FAILS
                    host.log("⚠ إعادة باتش ${i + 1} فشلت: " + (e.message ?: e.toString()).take(160))
                } finally { inflight.remove(i); host.changed(); persist() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** باتشات فاشلة/ناقصة مطلوب إعادتها بالأولوية على المفاتيح الأساسية (لما تكون أكتر من 3) */
    private val priorityRetry = java.util.concurrent.ConcurrentSkipListSet<Int>()
    /** عدد الباتشات اللي مستنية دورها في الإعادة بالأولوية */
    fun priorityPending(): Int = priorityRetry.size

    /**
     * زرار «إعادة» في الشريط الأحمر: بيعيد كل الباتشات المشكلة.
     * - 3 أو أقل: على المفاتيح الاحتياطية بالتوالي (الأقدم فالأحدث).
     * - أكتر من 3: مش هتتزحم على الاحتياطي — الترجمة العادية بتقف عن إرسال باتشات جديدة (اللي شغّال بيخلص)،
     *   والفاشلين بيتترجموا الأول على المفاتيح الأساسية بالترتيب، وبعدها الترجمة بتكمّل باقي الباتشات.
     * بيرجّع عدد الباتشات.
     */
    fun retryProblems(): Int {
        val idx = problems().map { it.idx }.sorted()
        if (idx.isEmpty()) return 0
        if (idx.size <= 3 || !running) retryOnBackupSeq(idx)   // لو حلقة الترجمة مش شغالة مفيش مين يستلم الأولوية، فبالتوالي على الاحتياطي
        else {
            for (i in idx) { failed.remove(i); incomplete.remove(i); priorityRetry.add(i) }
            host.log("🔁 ${idx.size} باتش فاشل: هترجمهم الأول على المفاتيح الأساسية بالترتيب وبعدها أكمّل باقي الباتشات")
            host.changed(); persist()
        }
        return idx.size
    }

    /** اختيار لهجة التحويل. existing=true: بيحوّل كمان اللي اتترجم قبل كده — من مكان المشاهدة وللقدّام الأول، وبعدين اللي قبله. بيحفظ نسخة باللهجة القديمة قبل التحويل (🗂) */
    fun setConvDialect(d: String, existing: Boolean) {
        if (Strict.noGemini()) { convDialect = ""; pend.clear(); return }   // (v410) فصحى بس في نص-أونلاين والأوفلاين
        val prev = convDialect
        if (existing && subs.isNotEmpty() && prev != d) { val lb = if (prev.isBlank()) "فصحى" else prev; saveVersion("ترجمة $lb", lb) }
        convDialect = d
        if (d.isBlank() || d == "فصحى") { pend.clear(); return }
        lastConvFail = 0L; convFailN = 0
        if (existing) synchronized(lock) { subs = subs.map { it.copy(conv = false) }; pend.addAll(subs.map { pk(it) }) }
        maybeConvert(true)
    }
    /** تبديل اللهجة من غير تحويل (لما نرجّع نسخة محفوظة): الجمل الجديدة تتحوّل للهجة دي */
    fun adoptDialect(d: String) {
        convDialect = d
        synchronized(lock) { subs = subs.map { it.copy(conv = true) } }; pend.clear()
    }
    /** بيحوّل الجمل اللي لسه ما اتحوّلتش كل ~3 باتشات (أو الباقي لما الترجمة تهدى) على المفاتيح الاحتياطية */
    fun maybeConvert(force: Boolean = false) {
        if (Strict.noGemini()) return   // (v410) تحويل اللهجة بجيميناي — أونلاين بس
        if (transOnly || (keyless && convLlm() == null)) return   // (v280) تفريغ بس: مفيش تحويل لهجة · (v281) من غير مفتاح جيميناي التحويل بيشتغل لو فيه محرك LLM مختار
        // (v259) تحويل اللهجة محتاج جيميناي — من غير مفتاح الترجمة بتفضل فصحى (الإشعار اتعرض قبل البداية)
        val tgt = convDialect
        if (tgt.isBlank() || tgt == "فصحى" || tgt == conf.lang) return
        if (System.currentTimeMillis() - lastConvFail < minOf(60_000L, 10_000L shl minOf(convFailN, 3))) return
        val todo = synchronized(lock) { subs.filter { needsConv(it) } }
        if (todo.isEmpty()) return
        val span = todo.maxOf { it.end } - todo.minOf { it.start }
        if (!force && span < ch * 0.5) return
        if (!convBusy.compareAndSet(false, true)) return
        try { bg.submit { try { convertSubs(tgt) } finally { convBusy.set(false) } } } catch (_: Exception) { convBusy.set(false) }
    }
    /** (v281) محرك LLM اللي اخترته لتحويل اللهجة (Cfg conv_via = id بريست)، لو جاهز ومش موقوف. null = جيميناي */
    private fun convLlm(): ExtLlm.Preset? = null   // (v395) المحركات الخارجية اتشالت — جيميناي بس
    private fun convViaLlm(p: ExtLlm.Preset, batch: List<Int>, snap: List<Sub>, tgt: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (part in batch.chunked(25)) {
            val orig = part.map { snap[it].original }; val msa = part.map { snap[it].translated }
            val meta = part.map { k -> val s = snap[k]; listOfNotNull(s.gender.takeIf { it.isNotBlank() }?.let { "speaker=$it" }, s.addressee.takeIf { it.isNotBlank() }?.let { "addressee=$it" }).joinToString(", ") }
            val r = ExtLlm.convert(p, orig, msa, meta, tgt, pb.dialectBlock(tgt) + toneBlock())
            for ((q, k) in part.withIndex()) r.getOrNull(q)?.takeIf { it.isNotBlank() }?.let { out.add(k to it) }
        }
        return out
    }
    private fun convertSubs(tgt: String): Int {
        var changed = 0
        val snap = synchronized(lock) { subs }
        val idx0 = snap.indices.filter { needsConv(snap[it]) }
        if (idx0.isEmpty()) return 0
        for (k in idx0) if (snap[k].conv) msaTried.add(pk(snap[k]))
        // من أول الباتش اللي المشاهد فيه ولقدّام الأول، وبعدين اللي قبله
        val focus = try { cStart(maxOf(0, (host.position() / ch).toInt())) } catch (_: Exception) { 0.0 }
        val idx = idx0.filter { snap[it].start >= focus - 0.05 } + idx0.filter { snap[it].start < focus - 0.05 }
        val batches = idx.chunked(40)
        host.log("🌐 تحويل ${idx.size} جملة للهجة $tgt (من ${(focus / 60).toInt()}:%02d وللقدّام الأول)…".format(focus.toInt() % 60))
        batches.forEachIndexed { bi, batch ->
            if (!running) return@forEachIndexed
            val list = JSONArray()
            for (k in batch) { val s = snap[k]; list.put(JSONObject().put("idx", k).put("original", s.original).put("translated", s.translated).put("speaker_gender", s.gender).put("addressee_gender", s.addressee)) }
            val prompt = PromptStore.fill(PromptStore.pick("eng:conv", EnginePrompts.CONV), "TGT" to tgt, "DIALECT" to pb.dialectBlock(tgt), "TONE" to toneBlock(), "LIST" to list.toString())
            try {
                // (v281) محرك LLM مختار (ChatGPT / Claude / …): بيحوّل هو، ولو وقف بيكمّل جيميناي (إلا لو مفيش مفتاح جيميناي)
                var rw: List<Pair<Int, String>>? = null
                val via = convLlm()
                if (via != null) {
                    try { rw = convViaLlm(via, batch, snap, tgt) }
                    catch (e: Exception) {
                        if (e is ExtLlm.LlmErr) ExtTranslate.noteFail("llm_" + via.id, ExtLlm.key(via).hashCode(), e)
                        host.log("⚠ تحويل اللهجة بـ ${via.label} فشل: " + (e.message ?: e.javaClass.simpleName).take(110))
                        if (keyless) throw e
                        warnUser("convvia", "🔄 فشل ${via.label} في تحويل اللهجة — تم التحويل لجيميناي")
                    }
                }
                if (rw == null) {
                    val r = bgCall(prompt, 8192, 0.3, true, bi)
                    val arr = (Parse.json(r.text) ?: throw BadReply("رد التحويل مش JSON")).optJSONArray("rewrites") ?: throw BadReply("رد التحويل من غير rewrites")
                    rw = (0 until arr.length()).mapNotNull { q -> val c = arr.optJSONObject(q) ?: return@mapNotNull null; val ix = c.optInt("idx", -1); if (ix !in batch) null else ix to c.optString("translated").trim() }
                }
                val rws: List<Pair<Int, String>> = rw ?: emptyList()
                synchronized(lock) {
                    val cur = subs.toMutableList(); val got = HashSet<Int>()
                    for ((ix, tr) in rws) {
                        if (tr.isEmpty()) continue
                        val t = snap[ix]
                        val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                        if (at >= 0) { cur[at] = cur[at].copy(translated = tr, conv = true); got.add(ix); if (tr != t.translated) changed++ }
                    }
                    // اللي ما رجعش في الرد يفضل غير محوّل (هيتحاول تاني)، مش بيتعلّم
                    subs = cur
                    for (ix in got) pend.remove(pk(snap[ix]))
                }
                host.changed(); convFailN = 0
            } catch (e: Exception) { lastConvFail = System.currentTimeMillis(); convFailN++; warnUser("conv", "⚠ التحويل للهجة فشل — الترجمة فضلت زي ما هي وهيتحاول تاني"); host.log("⚠ التحويل للهجة فشل: " + (e.message ?: "").take(130)) }
        }
        if (changed > 0) { persist(); host.log("✅ اتحوّلت $changed جملة للهجة $tgt") }
        return changed
    }
    fun chunkOfSec(sec: Double): Int { var i = 0; while (cStart(i + 1) <= sec) i++; return i }
    fun chunkStartSec(i: Int) = cStart(i)

    /** يمسح ترجمة الباتشات من..إلى (شاملة، to < 0 = لحد النهاية) عشان تتترجم من جديد. بيحفظ نسخة قبلها (🗂 ترجمات الفيديو) */
    fun redo(from: Int, to: Int, keep: Boolean = true) {
        val d = currentDur()
        val hi = if (to < 0) Int.MAX_VALUE else to
        val a = cStart(from); val b = if (to < 0) Double.MAX_VALUE else cEnd(to, d)
        if (keep && subs.isNotEmpty()) saveVersion("قبل إعادة الترجمة من باتش ${from + 1}")
        synchronized(lock) { subs = subs.filter { !(it.chunk in from..hi) && !(it.start >= a && it.start < b) } }
        done.remove(a, b); gapTried.remove(a, b)
        val last = if (to < 0) (if (d > 0) chunkCount() else from + 1000) else to
        for (i in from..last) { failed.remove(i); gapPass.remove(i); holeTried.remove(i); claimed.remove(i); rehomeQ.remove(i); tHold.remove(i); fullChunks.remove(i) }
        pool.clear(); lastGapTry = 0L; gapScanClean = false
        fullRefineStarted = false; fullRefineDone = false
        host.changed(); persist()
    }
    /**
     * (v413) اليوزر غيّر لغة الكلام من ⚙: بنفك قفل اللغة، وبنعيد الباتشات اللي اتفرّغت بلغة غير الجديدة (اللي باظت)،
     * وبنكمّل الترجمة من المكان اللي واقف عليه المشغّل.
     */
    fun langChanged(newLang: String, posSec: Double) {
        offSession.lang = ""; offSession.hint = ""; offSession.badRun = 0
        Offline.detectedLang = ""
        if (newLang.isNotEmpty()) {
            val bad = chunkLang.entries.filter { it.value.isNotEmpty() && it.value != newLang }.map { it.key }.sorted()
            for ((n, i) in bad.withIndex()) { redo(i, i, keep = n == 0); chunkLang.remove(i) }
            if (bad.isNotEmpty()) host.log("🌐 اللغة اتغيّرت لـ $newLang: هتتعاد ${bad.size} باتش اتفرّغت بلغة غلط (" + bad.take(8).joinToString("، ") { (it + 1).toString() } + (if (bad.size > 8) "…" else "") + ")")
            else host.log("🌐 اللغة اتغيّرت لـ $newLang — مفيش باتشات قديمة محتاجة إعادة")
        }
        translateFrom(posSec)
    }
    /** باتش ده وبعده كله (بيبدأ منه ويكمل) */
    fun redoFrom(i: Int, keep: Boolean = true) { redo(i, -1, keep); onlyChunks = null; forcedSticky = true; forcedCursor = i; paused = false }
    /** باتش ده لوحده وخلاص */
    fun redoOnly(i: Int, keep: Boolean = true) { redo(i, i, keep); onlyChunks = i..i; paused = false }
    /** من الأول خالص */
    fun redoAll(keep: Boolean = true) { redo(0, -1, keep); onlyChunks = null; forcedSticky = true; forcedCursor = 0; paused = false }
    /** كمّل الترجمة من المكان ده (من غير مسح حاجة): بيقفز بالمؤشر لباتش الوقت ده ويلغي الإيقاف */
    /** (v224) ابدأ من أول الفيديو: أول باتش لسه ناقص من الأول، بغض النظر عن مكان الفيديو دلوقتي (ثابت لحد ما يوصل لمكان التشغيل) */
    fun translateFromStart() { onlyChunks = null; forcedSticky = true; forcedCursor = 0; paused = false; userPaused = false }
    fun translateFrom(sec: Double) { onlyChunks = null; forcedSticky = false; forcedCursor = chunkOfSec(sec.coerceAtLeast(0.0)); paused = false; userPaused = false }
    fun stop() { isClosed = true; running = false; bg.shutdownNow(); gapEx.shutdownNow(); exec?.shutdownNow(); try { trEx.shutdownNow() } catch (_: Exception) {}; try { hedgeEx.shutdownNow() } catch (_: Exception) {}; try { holeEx.shutdownNow() } catch (_: Exception) {}; try { persistEx.shutdownNow() } catch (_: Exception) {} }
    /** (v122) إعدادات اتغيّرت من شاشة الإعدادات (لهجة / أسلوب / موديل / شخصيات…): تتطبّق على الباتشات والتحويلات الجاية من غير إعادة تشغيل المحرك */
    fun applyConf(c: Conf) {
        val keysChanged = c.keys != conf.keys || c.backup != conf.backup || c.refine != conf.refine
        conf = c; dialConf.clear()
        if (keysChanged) basePool = Pool(c)   // (v259) المفاتيح اتغيّرت (مثلًا مفتاح محلي للأوفلاين+جوجل): الحوض لازم يتبني من جديد
    }
    /** (v259) مفيش ولا مفتاح جيميناي حقيقي — بس المفتاح المحلي الوهمي (أوفلاين + جوجل). المهام اللي محتاجة جيميناي بتتخطّى */
    private val keyless: Boolean get() = conf.keys.isNotEmpty() && conf.keys.all { it == LocalKey.ID } && conf.backup.isEmpty() && conf.refine.isEmpty()
    fun saveNow() = doPersist(true)
    /** استيراد ترجمة جاهزة (SRT) لفيديو من غير ترجمة */
    fun importSubs(l: List<Sub>) { subs = l }
    fun retryFailed() {
        pool.clear(); gapPass.clear(); gapTried.clear(); lastGapTry = 0L
        for (k in failed.keys) failed[k] = MAX_FAILS
        host.log("🔁 هعيد محاولة المقاطع الفاشلة (${failedCount()})")
    }
    fun charactersNow(): List<Chr> = effectiveChars()

    /**
     * (v151) زرار 🧑 في المشغّل: بيحلّل الشخصيات من الترجمة الحالية دلوقتي (حتى لو التحليل التلقائي مقفول، أو الفيديو اتترجم قبل كده
     * ومفيش ولا باتش جديد يشغّل التحليل). الشخصيات اليدوية من الإعدادات هي المرجع ومبتتلمسش.
     * done بتتنادى مرة واحدة بالقايمة النهائية (من خيط تاني).
     */
    fun analyzeCharsNow(report: (String) -> Unit, done: (List<Chr>) -> Unit) {
        if (conf.manualChars.isNotEmpty()) { done(conf.manualChars); return }
        if (subs.size < 4) { report("لسه مفيش جمل مترجمة كفاية للتحليل"); done(effectiveChars()); return }
        val go = synchronized(lock) { if (charsBusy) false else { charsBusy = true; true } }
        if (!go) { report("التحليل شغّال دلوقتي — استنى لحظة"); done(effectiveChars()); return }
        Thread {
            try { analyzeChars() } catch (e: Exception) {
                host.log("⚠ تحليل الشخصيات فشل: " + (e.message ?: e.toString()).take(100))
                report("التحليل فشل: " + (e.message ?: "").take(80))
            } finally { synchronized(lock) { charsBusy = false }; persist() }
            done(effectiveChars())
        }.apply { isDaemon = true }.start()
    }

    /** يسترجع التقدم المحفوظ. يرجع آخر مكان تشغيل (بالثواني) أو 0. */
    fun load(): Double {
        val s = store?.load() ?: return 0.0
        synchronized(lock) {
            subs = Subs.unifyOverlaps(Subs.dedup((if (s.chunkSec == conf.chunkSec) s.subs else s.subs.map { it.copy(chunk = -1) }).map { Subs.collapseSelfRepeat(it) }))
            if (s.subs.isNotEmpty()) { for (r in s.done) done.add(r[0], r[1]) }
            else if (s.done.isNotEmpty()) host.log("♻ التقدم المحفوظ فيه دقايق «مخلّصة» من غير ولا جملة — هتتعاد الترجمة من الأول (v263)")
            for (r in s.failed) failed[Math.round(r[0] / ch).toInt()] = MAX_FAILS
            chars.addAll(s.chars); gloss.addAll(s.gloss); tplCache.putAll(s.tpl)
            srcLang = s.srcLang; detDone = s.detDone; autoCharsAttempts = s.charsTried
            if (s.detDone) anyApplied = true
            pronUpTo = subs.size
            if (s.chunkSec == conf.chunkSec) bounds.putAll(s.bounds)
            for (r in s.gapTried) gapTried.add(r[0], r[1])
        }
        host.log("♻ استرجعت ${s.subs.size} جملة و${(done.total() / 60).toInt()} دقيقة مترجمة من الجلسة اللي فاتت")
        host.changed()
        return s.pos
    }

    // ===== الحلقة الرئيسية =====
    // ملحوظة: فك الصوت (والتقصير لأقرب صمت) بيتم بالترتيب في الخيط ده، وإرسال الطلبات لجيميناي بيتم بالتوازي.
    private fun cStart(i: Int): Double = bounds[i] ?: (i * ch)
    private fun cEnd(i: Int, d: Double): Double { val e = cStart(i + 1); return if (d > 0) minOf(e, d) else e }

    fun run() {
        if (conf.keys.isEmpty() && conf.backup.isEmpty()) { host.status("⚠ ادخل مفتاح Gemini في الإعدادات"); host.log("⚠ مفيش مفاتيح Gemini"); host.notice("⚠ مفيش مفتاح جيميناي — ضيف واحد من الإعدادات ← المفاتيح"); return }
        val capMax = basePool.capacity(ppk)
        val ex = Executors.newFixedThreadPool(maxOf(capMax, Offline.MAX_PAR) + 2) { r -> Thread(r).also { it.isDaemon = true } }   // +2: باتش اتنسخ واتحسب من نسخته الاحتياطية ممكن يفضل خيطه الأصلي شغال شوية من غير ما يحجز مكان باتش جديد
        exec = ex
        if (capMax > 1) host.log("⚡ ترجمة متوازية: لحد $capMax طلب في نفس الوقت")
        try { sideNotice() } catch (_: Throwable) {}
        try {
            if (headless) try { src() } catch (e: Exception) { host.log("⚠ معرفتش أفتح الفيديو: " + (e.message ?: e.toString()).take(120)) }
            while (running) {
                val d = currentDur()
                val cap = capNow(pool.capacity(ppk))   // (v227: 429 يقلّلها لـ 2) (v210) بيتغيّر لو الوضع اتبدّل أثناء الشغل (3/4 = مفاتيح الترانسكريبشن بس)
                translateTick(userPaused || paused)            // (v210) الباتشات المنسوخة بتتجمّع وتتترجم حتى وقت الإيقاف المؤقت
                if (headless && d <= 0 && (failed[0] ?: 0) >= MAX_FAILS) { fatal = "معرفتش أفتح الفيديو أو أقرأ مدته"; host.log("⛔ " + fatal); host.status("⛔ " + fatal); running = false; break }
                if (headless && !anyApplied && failedCount() >= 5) { fatal = "المقاطع بتفشل ورا بعض — راجع المفاتيح والنت"; host.log("⛔ " + fatal); host.status("⛔ " + fatal); running = false; break }
                if (userPaused) { host.status("⏸ الترجمة واقفة مؤقتًا — دوس «إلغاء الإيقاف» تكمّل"); nap(300); continue }
                if (paused) { host.status("⏸ مستني اختيارك: كمّل على الترجمة الحالية ولا ترجم من جديد"); nap(300); continue }
                hedgeTick()
                autoRetryTick()
                // (v151) باتش فشل: يتبعت الأول لأقرب مفتاح خلّص باتشه (مفيش باتشين على مفتاح واحد) وبعدين الترجمة تكمّل باقي الباتشات عادي
                if (rehomeQ.isNotEmpty()) {
                    val ri = rehomeQ.keys.minOrNull()
                    val re = ri?.let { rehomeQ[it] }
                    if (ri == null || re == null || ri in inflight || isDone(ri, d)) { if (ri != null) rehomeQ.remove(ri); continue }
                    var rk = pool.pickIdle(ri, re.avoid, perKey)
                    if (rk == null && System.currentTimeMillis() - re.at > 20_000L) rk = pool.pickIdle(ri, null, perKey)
                    if (rk == null) { nap(150); continue }
                    rehomeQ.remove(ri)
                    host.log("🔁 باتش ${ri + 1} (فاشل) → أقرب مفتاح فاضي ${pool.tail(rk)} — قبل باقي الباتشات")
                    dispatch(ri, ex, true, rk, re.prep); continue
                }
                // (v220) تبديل الوضع أثناء الترجمة: خلّص اللي شغّال الأول (من غير باتشات جديدة) وبعدين بدّل
                if (switching) {
                    if (drained()) {
                        val from = activeMode; activeMode = conf.keyMode
                        host.log("🔑 الباتشات الشغالة خلصت — الوضع اتبدّل من $from لـ ${KeyMode.name(activeMode)} والباتشات الجديدة هتمشي بيه")
                        host.notice("🔑 الوضع الجديد شغّال: " + KeyMode.short(activeMode) + "\n" + ExtTranslate.setupLine())
                        if (activeMode < KeyMode.ALL_PARALLEL && detDone) try { maybeTranslateTemplate() } catch (_: Throwable) {}
                    } else {
                        host.status("🔑 بخلّص الباتشات الشغالة قبل ما أبدّل للوضع " + KeyMode.short(conf.keyMode) + "…")
                        nap(200); continue
                    }
                }
                // إعادة الباتشات الفاشلة بالأولوية (أكتر من 3): مفيش باتشات جديدة تتبعت لحد ما الفاشلين يتبعتوا، وبعدين الترجمة تكمّل عادي
                if (priorityRetry.isNotEmpty()) {
                    val nx = priorityRetry.firstOrNull { it !in inflight }
                    if (nx == null) { nap(150); continue }
                    if (activeInflight() >= cap) { nap(150); continue }
                    priorityRetry.remove(nx)
                    host.log("🔁 باتش ${nx + 1} (أولوية) — على المفاتيح الأساسية، باقي ${priorityRetry.size}")
                    dispatch(nx, ex, true); continue
                }
                var c = if (headless) firstUndone(d) else (host.position() / ch).toInt().coerceAtLeast(0)
                val fc = forcedCursor
                if (fc >= 0) {
                    var k = fc
                    while (!(d > 0 && cStart(k) >= d) && (isDone(k, d) || (failed[k] ?: 0) >= MAX_FAILS)) k++
                    forcedCursor = k
                    if (d > 0 && cStart(k) >= d) forcedCursor = -1
                    else if (forcedSticky) { c = k; if (k > (host.position() / ch).toInt()) forcedCursor = -1 }
                    else if (c >= k) forcedCursor = -1 else c = k
                }
                val only = onlyChunks
                val lo = only?.first ?: c
                val hi = only?.last ?: (c + window - 1)
                var next = -1
                for (i in lo..hi) {
                    if (d > 0 && cStart(i) >= d) break
                    if (isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS || i in inflight || tHold.containsKey(i) || rehomeQ.containsKey(i)) continue
                    next = i; break
                }
                if (next < 0 && only == null && keepGoing && d > 0) next = nextUndone(c + window, d)
                if (next < 0 && only == null && keepGoing && d > 0) next = nextUndone(0, d)
                if (next < 0 && translateTick(inflight.isEmpty())) { nap(100); continue }   // (v210) مفيش ترانسكريبشن جاي: ابعت اللي اتجمّع للترجمة مهما كان عدده
                if (next >= 0) {
                    // أول مقطع لوحده (عشان نعرف لغة الفيديو ونختار القالب الصح)، وبعدين بالتوازي — إلا الوضع 2+ (برومبت إنجليزي ثابت: مفيش قالب يتختار)
                    if (activeInflight() >= cap || (activeMode < KeyMode.ALL_PARALLEL && !anyApplied && inflight.isNotEmpty())) { gapTick(); nap(150); continue }   // (v209) الوضع 2+: كل المفاتيح بالتوازي من الأول من غير ما نستنى أول باتش
                    dispatch(next, ex); continue
                }
                if (inflight.isEmpty() && retryGaps()) continue
                if (inflight.isEmpty() && repairForeignTick()) continue
                if (inflight.isEmpty() && noteTick()) continue   // (v408) 📖 نبذة عن المصطلحات
                if (gapTick(headless || keepGoing)) continue
                if (txIdle() && gapBusy.get() == 0 && holeBusy.get() == 0 && finalRefineTick()) { nap(300); continue }   // (v140) تنقيح نهائي قبل ما نقول «خلصت»
                // (v158) كل المقاطع خلصت: تنقيح كامل واحد بالفيديو كله (بعد التنقيح الجزئي مقطع مقطع)
                if (!transOnly && txIdle() && gapBusy.get() == 0 && holeBusy.get() == 0 && conf.partialRefine && d > 0 && cStart(firstUndone(d)) >= d && !fullRefineStarted && subs.size >= 12 && toolBusy == null) {
                    fullRefineStarted = true; host.log("✍ تنقيح كامل نهائي (الفيديو كله في طلب واحد)…")
                    refineWholeNow({ host.status(it) }, { fullRefineDone = true }); nap(300); continue
                }
                if (fullRefineStarted && !fullRefineDone && toolBusy != null) { nap(300); continue }
                if (headless && txIdle() && gapBusy.get() == 0 && holeBusy.get() == 0 && (gapScanClean || !conf.gapFill) && !hasRepairable() && d > 0 && cStart(firstUndone(d)) >= d) {
                    finished = true; host.status("✅ خلصت الترجمة"); break
                }
                if (inflight.isEmpty()) maybeConvert(true)
                idleStatus(d)
                nap(if (inflight.isEmpty()) 700 else 200)
            }
        } finally {
            ex.shutdownNow()
            try { source?.close() } catch (_: Exception) {}
            doPersist()
            stoppedFlag = true
            try { onFinished?.invoke(finished && fatal == null) } catch (_: Exception) {}
        }
    }

    /** (v203) الفيديو كله مترجم فعلًا (كل المقاطع مخلّصة، من غير الفاشل) — بيتستخدم عشان «▶ ترجمة» ما تعيدش شغل مخلّص */
    fun fullyDone(): Boolean {
        val d = currentDur(); if (d <= 0) return false
        var i = 0
        while (cStart(i) < d) { if (!isDone(i, d)) return false; i++ }
        if (!transOnly && hasRepairable()) return false   // (v399) فيه جمل متفرّغة بس (لسه بلغتها الأصلية) — لسه ناقصها ترجمة
        return true
    }

    /** (v399) المحرك خلّص ووقف (مثلًا «تفريغ بس» خلص) وبعدين الوضع اتغيّر: بيجهّزه يشتغل تاني. true = لازم المشغّل يبدأ الحلقة من جديد */
    fun reopen(): Boolean {
        if (!stoppedFlag || isClosed) return false
        stoppedFlag = false; finished = false; fatal = null; source = null; running = true
        return true
    }

    /** أول مقطع ناقص من i وطالع (بيتخطّى المخلّص والفاشل والشغّال دلوقتي) أو -1 */
    private fun nextUndone(from: Int, d: Double): Int {
        var i = maxOf(0, from)
        while (cStart(i) < d) {
            if (!(isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS || i in inflight || tHold.containsKey(i) || rehomeQ.containsKey(i))) return i
            i++
        }
        return -1
    }

    /** أول مقطع لسه ناقص (الفاشلين بيتخطّوا وبتتعالج في retryGaps) — للترجمة في الخلفية */
    private fun firstUndone(d: Double): Int {
        if (d <= 0) return 0
        var i = 0
        while (true) {
            if (d > 0 && cStart(i) >= d) return i
            if (!(isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS)) return i
            i++
        }
    }

    /** عدد الجمل اللي رجعت لباتش (بالوقت مش بالـ tag عشان يشتغل بعد التحميل من الملف) */
    fun subCount(i: Int, d: Double): Int { val a = cStart(i) - 0.001; val b = cEnd(i, d) - 0.001; return subs.count { it.start >= a && it.start < b } }
    private fun isDone(i: Int, d: Double): Boolean = done.covers(cStart(i), cEnd(i, d))

    @Volatile private var durCache = 0.0
    private fun currentDur(): Double {
        if (durCache > 0) return durCache
        val a = try { source?.durationSec() ?: 0.0 } catch (_: Exception) { 0.0 }
        if (a > 0) durCache = a
        return if (a > 0) a else host.playerDuration()
    }

    private fun nap(ms: Long) {
        var t = 0L
        while (running && t < ms) { try { Thread.sleep(minOf(200L, ms - t)) } catch (_: InterruptedException) { return }; t += 200 }
    }

    private var lastIdle = ""
    private fun idleStatus(d: Double) {
        val pct = if (d > 0) (done.total() * 100 / d).toInt().coerceAtMost(100) else 0
        val f = failedCount()
        val s = "✅ مترجم $pct% — ${subs.size} جملة" + (if (f > 0) " — ⚠ $f مقطع فاشل" else "")
        if (s != lastIdle) { lastIdle = s; host.status(s) }
    }

    /** بينفّذ خطوة خاصة بمقطع ويسجّل الفشل. بيرجع true لو نجحت. */
    private fun guard(i: Int, block: () -> Unit): Boolean {
        try {
            block(); return true
        } catch (e: Unsupported) {
            fatal = e.message
            host.log("⛔ ${e.message}"); host.status("⛔ ${e.message}")
            running = false
        } catch (e: RehomeEx) {
            if (!running) return false
            rehomeQ[i] = Rehome(e.avoid, System.currentTimeMillis(), preps[i])
            host.log("↪ باتش ${i + 1}: مفتاح ${pool.tail(e.avoid ?: "")} فشل ومفيش مفتاح فاضي — الباتش مستني أقرب مفتاح يخلص باتشه")
        } catch (e: Exception) {
            if (!running) return false
            val n = (failed[i] ?: 0) + 1
            failed[i] = n
            val failedKey = keyOf[i]
            val where = e.stackTrace.filter { it.className.startsWith("com.tttt") || it.className.startsWith("android.media") }.take(3).joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
            host.log("⚠ مقطع ${i + 1} فشل ($n/$MAX_FAILS): " + (e.message ?: e.javaClass.simpleName).take(160) + (if (where.isNotEmpty()) " [$where]" else ""))
            if (n >= MAX_FAILS) host.log("🕳 المقطع ${i + 1} اتسجل كفجوة — هعيد محاولته تلقائيًا بعد شوية")
            persist()
            // (v151) باتش فشل على مفتاح: يروح لأقرب مفتاح تاني خلّص باتشه، والمفتاح اللي فشل يكمّل باتشاته عادي
            if (n < MAX_FAILS && failedKey != null && pool.usableCount() >= 2) {
                rehomeQ[i] = Rehome(failedKey, System.currentTimeMillis(), preps[i])
                nap(1000)
            } else nap(4000)
        }
        return false
    }

    /** فك صوت المقطع + الإرسال لجيميناي كله في خيط من الحوض: عدة باتشات بتفك صوتها وتتبعت بالتوازي على كل المفاتيح (قبل كده الفك كان بالترتيب في حلقة واحدة فالباتشات كانت بتمشي واحد ورا التاني) */
    private fun dispatch(i: Int, ex: java.util.concurrent.ExecutorService, force: Boolean = false, firstKey: String? = null, prep0: Prep? = null) {
        if (!inflight.add(i)) return
        startedAt[i] = System.currentTimeMillis(); hedged.remove(i)
        // (v151) المفتاح بيتحجز للباتش من لحظة الإرسال (قبل فك الصوت) عشان باتشين ما يتحطوش على نفس المفتاح
        val fk = firstKey ?: pool.pickIdle(i, null, perKey)
        try {
            ex.submit {
                try {
                    var p: Prep? = prep0
                    val ok = if (prep0 != null) { preps[i] = prep0; true } else guard(i) { val got = prepare(i, force); p = got; if (got != null) preps[i] = got }
                    if (ok) {
                        val pp = p
                        if (pp == null) failed.remove(i)
                        else { startedAt[i] = System.currentTimeMillis(); if (guard(i) { sendOrBackup(i, pp, fk) }) failed.remove(i) }
                    }
                } finally { if (i !in bkQueued) inflight.remove(i); preps.remove(i); startedAt.remove(i); keyOf.remove(i); pool.unreserve(i); host.changed() }   // (v216) باتش في طابور الاحتياطي يفضل «شغّال» لحد ما يخلص
            }
        } catch (_: RejectedExecutionException) { inflight.remove(i); preps.remove(i); startedAt.remove(i); pool.unreserve(i) }
    }

    /**
     * باتش اتأخر (شغال أطول من ~2× المعتاد) وفي باتش بعده خلص: يبقى غالبًا واقع على مفتاح بطيء.
     * بنبعت نسخة منه على مفتاح تاني — أول نسخة تخلص هي اللي بتتطبّق والتانية بتتجاهل. نسخة واحدة بس في نفس الوقت.
     */
    private fun hedgeTick() {
        if (inflight.isEmpty() || hedgeBusy.get() >= 1 || pool.usableCount() < 2 || Offline.on()) return   // (v253) الأوفلاين: نسخة احتياطية بتستنى نفس المعالج
        val now = System.currentTimeMillis()
        val ms = recentMs.toList().sorted()
        val med = if (ms.isEmpty()) 0L else ms[ms.size / 2]
        val limit = (if (med > 0) med * 22 / 10 else 40_000L).coerceIn(25_000L, 120_000L)
        val i = inflight.filter { it < maxApplied && it !in hedged && preps.containsKey(it) && (startedAt[it]?.let { s -> now - s > limit } ?: false) }.minOrNull() ?: return
        val p = preps[i] ?: return
        if (!hedged.add(i)) return
        hedgeBusy.incrementAndGet()
        host.log("⚡ باتش ${i + 1} اتأخر عن اللي بعده — بعتّه على مفتاح تاني (أول نسخة تخلص هي اللي تتحسب)")
        try {
            hedgeEx.submit {
                try { send(i, p, avoidKey = keyOf[i], hedge = true) } catch (_: Exception) {} finally { hedgeBusy.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) { hedgeBusy.decrementAndGet() }
    }

    /** إعادة محاولة المقاطع الفاشلة لما نخلص الشغل اللي قدام المشاهد. */
    private fun retryGaps(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGapTry < 30_000) return false
        val cand = failed.filter { it.value >= MAX_FAILS && (gapPass[it.key] ?: 0) < GAP_PASSES }.keys.sorted()
        if (cand.isEmpty()) return false
        lastGapTry = now
        host.log("🕳 إعادة محاولة ${cand.size} مقطع فاشل…")
        for (i in cand) {
            if (!running) break
            gapPass[i] = (gapPass[i] ?: 0) + 1
            try { translateChunk(i); failed.remove(i); host.log("✅ المقطع ${i + 1} اتترجم في إعادة المحاولة") }
            catch (e: Unsupported) { fatal = e.message; host.log("⛔ ${e.message}"); running = false; break }
            catch (e: Exception) { host.log("⚠ المقطع ${i + 1} لسه فاشل: " + (e.message ?: e.toString()).take(120)) }
        }
        return true
    }

    // ===== ترجمة مقطع =====
    @Synchronized private fun src(): AudioSource = source ?: openSource().also { it.setPreRoll(if (conf.hiTiming) 3.0 else 0.0); source = it }

    private class Prep(val w: WavChunk, val rawStart: Double, val rawEnd: Double)

    private fun translateChunk(i: Int) { prepare(i)?.let { send(i, it) } }

    /** يفك الصوت (ويقصّر المقطع لأقرب لحظة صمت). null = مفيش حاجة تتبعت (صامت / مفيش صوت). */
    private fun prepare(i: Int, force: Boolean = false): Prep? {
        claimed.remove(i)   // محاولة جديدة للباتش ده من الأول
        val d = currentDur()
        // بنحجز الباتش ونقرا حدوده في خطوة واحدة: لو باتش قبله بيقصّر حدّه بالتوازي ماينفعش يسيب ثغرة
        val rawStart: Double; var rawEnd: Double
        synchronized(trimLock) { prepared.add(i); rawStart = cStart(i); rawEnd = cEnd(i, d) }
        val start = if (i == 0) 0.0 else maxOf(0.0, rawStart - OVERLAP)
        host.status("⏳ ترجمة المقطع ${i + 1}…")
        val t0 = System.currentTimeMillis()
        val s0 = src()
        var w = s0.wav(start, rawEnd)
        // فك الصوت ساعات بيرجع null مؤقتًا (المصدر لسه بيفتح / شبكة) — نعيد قبل ما نعتبر المقطع من غير صوت
        var retry = 0
        while (w == null && running && retry++ < 2) { host.log("… المقطع ${i + 1}: الصوت لسه ما طلعش — محاولة ${retry + 1}/3"); nap(700); w = s0.wav(start, rawEnd) }
        if (w == null) {
            host.log("… المقطع ${i + 1} مفيهوش صوت")
            // لو ده مش آخر ذيل الفيديو يبقى غالبًا فشل فك مش صمت حقيقي: نعلّمه ⚠ عشان يتعاد بدل ما يبان ✅ فاضي
            if (d <= 0 || (rawEnd - rawStart > 5.0 && rawStart < d - 3.0)) { incomplete.add(i); host.log("⚠ باتش ${i + 1}: مقدرتش أفك صوته — أعده من علامة التحذير") }
            done.add(rawStart, rawEnd); persist(); return null
        }
        host.log("🎧 صوت المقطع ${i + 1}: ${w.bytes.size / 1024}KB في ${System.currentTimeMillis() - t0}ms")
        // (v166) نضغط الصوت هنا (نفس خيط التجهيز) عشان الرفع يبدأ بحجم أصغر ونشوف النسبة في اللوج
        try { val pk = AudioEnc.pack(w.bytes); host.log("📦 المقطع ${i + 1}: ${pk.srcSize / 1024}KB ← ${pk.bytes.size / 1024}KB (${pk.mime.removePrefix("audio/")})") } catch (_: Throwable) {}
        return Prep(w, rawStart, rawEnd)
    }

    private fun send(i: Int, p: Prep, backupFirst: Boolean = false, avoidKey: String? = null, hedge: Boolean = false, firstKey: String? = null) {
        val w = p.w; val rawStart = p.rawStart; val rawEnd = p.rawEnd
        val prior = subs
        // المفتاح الأقل شغلًا ثم الأسرع (مش تبادل بالدور): الباتشات بتمشي ورا بعض بدل ما الزوجية تتحبس على مفتاح بطيء
        fun nextKey(avoid: String?): String? = if (backupFirst) (pool.pickBackup(avoid) ?: pool.pickFree(avoid, rr.getAndIncrement())) else pool.pickFree(avoid, rr.getAndIncrement())
        // (v151) تغيير المفتاح أثناء الباتش: لأقرب مفتاح فاضي بس. لو كلهم مشغولين الباتش بيرجع للطابور (RehomeEx) بدل ما يتحط على مفتاح شغّال
        fun switchKey(cur: String): String? {
            if (hedge || backupFirst) return nextKey(cur)
            pool.pickIdle(i, cur, perKey)?.let { return it }
            if (nextKey(cur) == null) return null
            throw RehomeEx(cur)
        }
        var key = firstKey?.takeIf { pool.ok(it) } ?: nextKey(avoidKey) ?: throw Exception("كل المفاتيح معطلة مؤقتًا — راجع المفاتيح")
        var partial = false
        var trunc = 0; var empties = 0; var bad = 0; var net = 0; var tries = 0; var langBad = 0
        var langRetry = 0; var langSwitchRetry = 0
        var srv = 0; var altModel: String? = null   // (v227) 503/5xx متكرر: الباتش يتجرّب على موديل بديل
        val tx = activeMode >= KeyMode.SPLIT_A && i !in fullChunks   // (v210) ترانسكريبشن بس، والترجمة بعدين مجمّعة على مفاتيح الترجمة
        while (running && tries++ < MAX_TRIES) {
            if (claimed.contains(i)) return   // نسخة تانية من الباتش ده خلصت وطُبّقت قبلنا
            if (!hedge) { keyOf[i] = key; offOf[i] = (tx && Offline.on()) }
            val prompt = buildPrompt(w.durSec, w.startSec, langBad > 0 || (tx && empties > 0), i, tx = tx)
            try {
                val t0 = System.currentTimeMillis()
                val mdl = altModel ?: curModel()
                var gemCalled = false   // (v413) هل جيميناي اتنده فعلًا للباتش ده؟ (false = Whisper لوحده)
                val r = run {
                    pool.begin(key, i)
                    try {
                        val gem = { gemCalled = true; Api.generate(mdl, key, prompt, w.bytes, maxTokens = Api.outTokens(w.durSec), temp = if (tx && empties == 0 && bad == 0 && trunc == 0) 0.0 else 0.1) }   // (v331) التفريغ بحرارة 0 (أدق وثابت)؛ الإعادة بعد فشل بحرارة 0.1 عشان ما يرجعش نفس الرد
                        // (v253) ترانسكريبشن أوفلاين (Whisper على الجهاز) — لو فشل جيميناي (gem) بيكمّل الباتش
                        (if (tx && Offline.on()) { Offline.batchTag.set(i); Offline.transcribe(w.bytes, w.durSec, effectiveGloss().joinToString(", ") { it.term }, { host.log(it) }, gem, { host.notice(it) }, offSession) }
                            else { if (tx) Offline.whyOff()?.let { m -> host.log(m); Offline.softNotice(m) { host.notice(it) } }; gem() }).also { pool.end(key, System.currentTimeMillis() - t0, true) }
                    } catch (e: Throwable) { pool.end(key, 0, false); throw e }
                }
                val took = System.currentTimeMillis() - t0
                if (isClosed) return
                if (gemCalled) { flushModeMsg(); noteWho("🔑 جيميناي شغّال", " — باتش ${i + 1} بمفتاح " + pool.tail(key) + " (" + conf.keys.size + " أساسي)") }   // (v278)   // الإنجن اتقفل أثناء الطلب (خرجت من الفيديو): ارمي الرد
                if (r.finish == "MAX_TOKENS" && trunc >= 2) partial = true
                if (r.finish == "MAX_TOKENS" && trunc < 2) { trunc++; host.log("⚠ الرد اتقطع — إعادة المحاولة"); nap(600); continue }
                if (r.finish.isNotEmpty() && r.finish != "STOP" && r.finish != "MAX_TOKENS") throw Exception("رد Gemini اتوقف: ${r.finish}")
                var j: JSONObject? = null
                if (r.text.isNotBlank()) { j = Parse.json(r.text); if (j == null) throw BadReply("رد Gemini مش JSON صالح") }
                var fresh = if (j == null) emptyList() else if (tx) TParse.transcript(j, w.startSec, w.durSec) else Parse.subs(j, w.startSec, w.durSec)
                // حارس اللغة: لو 30%+ من الجمل translated فيها مش بالعربي → إعادة المحاولة بتشديد، وبعدها إصلاح الباقي نصيًا (الترانسكريبشن مفيهوش translated فمالوش حارس هنا — الحارس بيشتغل بعد الترجمة)
                val off = if (tx) emptyList() else LangGuard.foreignOf(fresh)
                if (off.isNotEmpty() && off.size * 10 >= fresh.size * 3 && langBad < 2) {
                    langBad++; host.log("🌐 المقطع ${i + 1}: ${off.size}/${fresh.size} جملة مش بـ${conf.lang} — إعادة المحاولة")
                    nap(400); continue
                }
                if (off.isNotEmpty()) fresh = fixForeign(fresh)
                // (v267) الباتش اتفرّغ أوفلاين وطلع فاضي: Whisper بيرجّع نفس النتيجة لو أعدناه — بنعيد مرة واحدة بس لو اللغة اتقفلت بعد ما بدأ، وإلا بنقبله (مفيهوش كلام/موسيقى)
                val offRan = tx && Offline.ranOffline.get() == true
                if (offRan) {   // (v413) سجّل لغة التفريغ؛ ولو اليوزر غيّر اللغة والباتش شغّال بالقديمة: أعده بالجديدة
                    val ul = Offline.usedLang.get().orEmpty(); val man = Offline.lang()
                    if (!hedge && man.isNotEmpty() && ul != man && langSwitchRetry++ < 1) { host.log("🌐 المقطع ${i + 1}: اللغة اتغيّرت أثناء التفريغ (${ul.ifEmpty { "تلقائي" }} ← $man) — إعادة بالجديدة"); continue }
                    chunkLang[i] = ul.ifEmpty { Offline.gotLang.get().orEmpty() }
                }
                if (fresh.isEmpty() && offRan) {
                    val lk = offSession.lang
                    if (!w.silent && !hedge && lk.isNotEmpty() && lk != Offline.usedLang.get().orEmpty() && langRetry++ < 1) { host.log("🌐 المقطع ${i + 1}: فاضي — إعادة واحدة باللغة المقفولة ($lk)"); continue }
                    host.log("🔇 المقطع ${i + 1}: Whisper ما لقاش كلام — اتقبل من غير إعادة")
                }
                if (!offRan && fresh.isEmpty() && !w.silent && !hedge && !backupFirst && queueBackup(i, p, "رجع من غير جمل")) return   // (v216) صفر جمل: على المفتاح الاحتياطي بالترتيب في كل الأوضاع
                if (!offRan && fresh.isEmpty() && empties < (if (w.silent) 1 else 3)) {
                    empties++
                    if (!w.silent) host.log("⚠ المقطع ${i + 1} رجع من غير جمل رغم وجود صوت — إعادة محاولة ($empties/3)")
                    switchKey(key)?.let { key = it }
                    nap(400); continue
                }
                if (!offRan && fresh.isEmpty() && !w.silent) { partial = true; host.log("⚠ المقطع ${i + 1} لسه من غير جمل — هيتحاول تاني كفجوة") }
                pool.good(key)
                if (!claimed.add(i)) { host.log("↩ باتش ${i + 1}: نسخة تانية خلصته قبلها — اتجاهلت دي"); return }
                if (i > maxApplied) maxApplied = i
                runs[i] = KeyRun(keyNo(key), mdl, took, gemCalled)
                if (tx && fresh.isNotEmpty()) {
                    // (v210) الباتش ما بيتطبّقش دلوقتي: بيتحط في المجمّع لحد ما يتترجم (applyChunk + سدّ الثغرات بيحصلوا بعد الترجمة)
                    holdTranscript(i, p, j, relistenLow(i, fresh, key), prior, key, partial)   // (v331) الجمل واطية الثقة بتتسمع تاني قبل الترجمة
                    startedAt[i]?.let { recentMs.addLast(System.currentTimeMillis() - it); while (recentMs.size > 8) recentMs.pollFirst() }
                    if (hedge) { inflight.remove(i); host.log("⚡ باتش ${i + 1}: النسخة الاحتياطية خلصت الأول") }
                    return
                }
                applyChunk(i, rawStart, rawEnd, w, j, fresh, prior)
                startedAt[i]?.let { recentMs.addLast(System.currentTimeMillis() - it); while (recentMs.size > 8) recentMs.pollFirst() }
                if (hedge) { inflight.remove(i); host.log("⚡ باتش ${i + 1}: النسخة الاحتياطية خلصت الأول") }
                scheduleHoleFill(i, rawStart, rawEnd, w, key)   // برّه خانة الباتش: الباتش الجاي مايستناش سدّ الثغرات
                if (partial) { incomplete.add(i); host.log("⚠ باتش ${i + 1} رجع ناقص — تقدر تعيده من علامة التحذير") }
                return
            } catch (e: BadReply) {
                if (bad++ < 2) { host.log("⚠ ${e.message} — إعادة المحاولة"); nap(600); continue }
                throw e
            } catch (e: ApiErr) {
                val invalid = e.code == 401 || e.code == 403 || (e.code == 400 && (e.message ?: "").contains("API key", true))
                if (e.code == 429) {
                    noteQuota()
                    pool.block(key, 60_000)
                    val alt = switchKey(key)
                    if (alt != null) { host.log("⏳ ${(e.message ?: "").take(130)} على ${pool.tail(key)} — تحويل لمفتاح ${pool.tail(alt)}"); key = alt; continue }
                    val s = pool.streakUp(); val wait = minOf(60, 10 * s)
                    host.log("⏳ كل المفاتيح في كوتة (${(e.message ?: "").take(130)}) — انتظار ${wait}ث"); nap(wait * 1000L)
                    pool.clear(); continue
                }
                if (invalid) {
                    pool.block(key, 3_600_000)
                    val alt = switchKey(key)
                    if (alt != null) { host.log("🔑 ${(e.message ?: "").take(110)} — مفتاح ${pool.tail(key)} غير صالح — تحويل"); key = alt; continue }
                    throw Exception("مفتاح API غير صالح أو الموديل مش متاح — ${(e.message ?: "").take(120)}")
                }
                if (e.code >= 500) {
                    if (++srv >= 2 && altModel == null) { altModel = Models.alt(curModel()); host.log("🔀 الموديل تحت ضغط (${e.code}) — باتش ${i + 1} هيتجرّب على ${altModel}") }
                    host.log("⚠ خطأ من السيرفر (${e.code}) — إعادة المحاولة"); nap(3000); continue
                }
                throw e
            } catch (e: Unsupported) { throw e
            } catch (e: IOException) {
                if (++net > 4) throw e
                host.log("📶 مشكلة اتصال: ${(e.message ?: "").take(80)} — إعادة المحاولة"); nap(2000L * net)
            }
        }
        if (running) throw Exception("استنفدت المحاولات")
    }

    // ===== (v216) طابور المفتاح الاحتياطي (كل الأوضاع) =====
    // أي باتش يرجع من غير جمل أو يفشل على مفتاح أساسي بيتحط هنا، والمفتاح الاحتياطي بيترجمهم واحد ورا التاني بالترتيب (الأقدم الأول):
    // لو باتش 1 فشل وباتش 2 رجع صفر → باتش 1 الأول، ولما جمله ترجع يبدأ باتش 2. الباتش بيتبعت كطلب كامل (صوت ← ترجمة) بنفس الصوت المفكوك.
    private val bkQ = java.util.concurrent.ConcurrentSkipListMap<Int, Prep>()
    private val bkQueued = ConcurrentHashMap.newKeySet<Int>()
    private val bkRunning = AtomicBoolean(false)
    private fun activeInflight(): Int = inflight.count { it !in bkQueued }

    /** true = الباتش اتسلّم للاحتياطي (الأساسي يسيبه). false = مفيش مفتاح احتياطي → السلوك القديم */
    private fun queueBackup(i: Int, p: Prep, why: String): Boolean {
        if (!running || isClosed) return false
        if (pool.pickBackup(null) == null) return false
        if (claimed.contains(i)) return true
        if (bkQueued.add(i)) {
            bkQ[i] = p; inflight.add(i)
            host.log("🛟 باتش ${i + 1}: $why — اتحط في طابور المفتاح الاحتياطي (بالترتيب، في الطابور ${bkQ.size})")
            host.notice("🛟 باتش ${i + 1} رايح للمفتاح الاحتياطي")
        }
        startBkLane()
        return true
    }

    private fun sendOrBackup(i: Int, p: Prep, fk: String?) {
        try { send(i, p, firstKey = fk) }
        catch (e: Unsupported) { throw e }
        catch (e: RehomeEx) { throw e }
        catch (e: Exception) {
            if (!running || isClosed || !queueBackup(i, p, "فشل: " + (e.message ?: e.javaClass.simpleName).take(80))) throw e
        }
    }

    private fun startBkLane() {
        if (!bkRunning.compareAndSet(false, true)) return
        Thread {
            try {
                nap(1500)   // مهلة صغيرة عشان باتشات تانية فاشلة في نفس اللحظة تدخل الطابور وتترتب
                while (running && !isClosed) {
                    val e = bkQ.pollFirstEntry() ?: break
                    val i = e.key
                    try {
                        if (claimed.contains(i)) continue
                        host.log("🛟 باتش ${i + 1} ← المفتاح الاحتياطي (باقي ${bkQ.size} في الطابور)")
                        fullChunks.add(i)
                        send(i, e.value, true)
                        failed.remove(i)
                        host.log("🛟 باتش ${i + 1}: خلص على الاحتياطي — ${subCount(i, currentDur())} جملة")
                    } catch (x: Unsupported) {
                        fatal = x.message; host.log("⛔ ${x.message}"); host.status("⛔ ${x.message}"); running = false
                    } catch (x: Exception) {
                        failed[i] = MAX_FAILS
                        host.log("⚠ باتش ${i + 1} فشل برضه على الاحتياطي: " + (x.message ?: x.javaClass.simpleName).take(120))
                    } finally {
                        bkQueued.remove(i); inflight.remove(i); host.changed(); persist()
                    }
                }
            } finally {
                bkRunning.set(false)
                if (running && !isClosed && bkQ.isNotEmpty()) startBkLane()
            }
        }.apply { isDaemon = true; name = "backup-lane" }.start()
    }

    /** لهجة الباتش اللي بيتبعت: لو اتختارت لهجة، الباتش بيتترجم بيها مباشرة (من غير فصحى ثم تحويل — توفير على المفاتيح) */
    private val dialOf = ConcurrentHashMap<Int, String>()
    private val dialConf = ConcurrentHashMap<String, Conf>()
    private fun buildPrompt(durSec: Double, start: Double, strict: Boolean = false, chunk: Int = -1, hole: Boolean = false, holeNear: Boolean = false, tx: Boolean = false): String {
        val dl = convDialect.let { if (it.isBlank() || it == "فصحى") "" else it }
        if (chunk >= 0) { if (dl.isEmpty()) dialOf.remove(chunk) else dialOf[chunk] = dl }
        val c = if (dl.isEmpty() || dl == conf.lang) conf else dialConf.getOrPut(dl) { conf.withLang(dl) }
        // (v209) الوضع 2+ (وكمان 3 و4 في طلبات الصوت): برومبت إنجليزي بدل القالب العربي — نفس شكل الرد فـ Parse.subs بيقراه عادي
        if (activeMode >= KeyMode.ALL_PARALLEL || tx) {   // (v239) tx يشتغل في كل الأوضاع (سدّ الثغرات بأسلوب الوضع 4)
            val prev = if (hole) "" else Subs.prevContext(subs, start)
            // (v210) tx = طلب ترانسكريبشن بس (الوضعين 3/4، الباتشات العادية). الثغرات والفجوات وإعادة الباتش الكامل بتفضل صوت ← ترجمة مباشرة
            val sl = if (detDone) srcLang else ""   // (v331) لغة الكلام الأصلية بعد ما تتحدد من أول باتش
            return if (tx) toneBlock() + ModePrompts.transcribe(pb, c, durSec, prev, effectiveChars(), effectiveGloss(), strict, hole, holeNear, sl, notes = !hole)
            else toneBlock() + ModePrompts.english(pb, c, durSec, prev, effectiveChars(), effectiveGloss(), strict, hole, holeNear, sl)
        }
        val case = pb.caseOf(srcLang, detDone)
        val tf = if (case == "other") synchronized(tplCache) { tplCache[tplKey(pb.templateId(c, "other"))] } else null
        val built = toneBlock() + pb.build(c, srcLang, detDone, durSec, if (hole) "" else Subs.prevContext(subs, start), effectiveChars(), effectiveGloss(), tf, strict, hole)
        return if (hole && holeNear) built.replace(PromptBuilder.holeBlock(), PromptBuilder.holeNearBlock()) else built
    }

    /** إصلاح الجمل اللي translated بتاعتها مش عربي: ترجمة نصية سريعة من original (وpivot) للهجة المختارة */
    private fun fixForeign(list: List<Sub>): List<Sub> {
        val bad = list.indices.filter { LangGuard.foreign(list[it]) }
        if (bad.isEmpty()) return list
        return try {
            val arr = JSONArray()
            for (k in bad) arr.put(JSONObject().put("idx", k).put("original", list[k].original).put("english", list[k].pivot).put("speaker_gender", list[k].gender).put("addressee_gender", list[k].addressee))
            val target = if (conf.lang == "فصحى") "العربية الفصحى" else "اللهجة ${conf.lang}"
            val prompt = "ترجم كل جملة من الجمل دي إلى $target بالحروف العربية فقط (الأسلوب: ${conf.style}). ماتسيبش أي جملة بلغتها الأصلية ولا بالإنجليزي. حافظ على المعنى كامل وعلى جنس المتكلم speaker_gender والمخاطَب addressee_gender.\n\nالجمل:\n$arr\n\n" +
                "أرجع JSON فقط: {\"rewrites\":[{\"idx\":0,\"translated\":\"...\"}]}"
            val r = bgCall(prompt, 4096, 0.3, true, 0)
            val rw = (Parse.json(r.text) ?: return list).optJSONArray("rewrites") ?: return list
            val out = list.toMutableList(); var n = 0
            for (q in 0 until rw.length()) {
                val c = rw.optJSONObject(q) ?: continue
                val ix = c.optInt("idx", -1); if (ix !in bad) continue
                val tr = c.optString("translated").trim()
                if (tr.isEmpty()) continue
                val ns = out[ix].copy(translated = tr)
                if (!LangGuard.foreign(ns)) { out[ix] = ns; n++ }
            }
            if (n > 0) host.log("🌐 اتصلّحت $n جملة كانت مش بـ${conf.lang}")
            out
        } catch (e: Exception) { host.log("⚠ إصلاح اللغة فشل: " + (e.message ?: "").take(80)); list }
    }

    private fun tplKey(id: String) = "$srcLang|$id"

    private fun effectiveChars(): List<Chr> = if (conf.manualChars.isNotEmpty()) conf.manualChars else synchronized(lock) { chars.toList() }
    private fun effectiveGloss(): List<Gloss> = synchronized(lock) { gloss.toList() }

    // ===== (v210) الوضعين 3 و4: ترانسكريبشن على مفاتيح + ترجمة جماعية (3–5 باتشات) على مفاتيح تانية =====
    // الباتش بعد الترانسكريبشن بيقعد في tHold (مش done، فالحلقة ماتبعتوش تاني ومحدش يعتبره خلص) لحد ما يتترجم ويتطبّق بتوقيته الأصلي.
    // لو الترجمة فشلت أو رجعت ناقصة بعد TR_ROUNDS جولات: الباتش يتبعت من جديد كطلب كامل (صوت ← ترجمة) — مفيش باتش بيتخطّى.
    private class TItem(val i: Int, val p: Prep, val j: JSONObject?, val fresh: List<Sub>, val prior: List<Sub>, val key: String, val partial: Boolean, val at: Long)
    private val tHold = ConcurrentHashMap<Int, TItem>()
    private val tTaken = ConcurrentHashMap.newKeySet<Int>()
    private val fullChunks = ConcurrentHashMap.newKeySet<Int>()
    private val trBusy = AtomicInteger(0)
    private val trEx = Executors.newFixedThreadPool(4) { r -> Thread(r).also { it.isDaemon = true; it.name = "translate-group" } }

    // (v224) الوضع 4: النص المنسوخ بيظهر فورًا بلغته الأصلية (من غير ترجمة) لحد ما الترجمة السياقية تحلّ محلّه.
    // مابيتحسبش «مخلّص» (done) ومابيتحفظش على الديسك، وكل باتش بيتشال من القايمة دي أول ما يتطبّق بترجمته (applyChunk).
    private val previewChunks = ConcurrentHashMap.newKeySet<Int>()

    // (v227) حماية من أخطاء جوجل: 429 = الطلبات المتوازية تقلّ لـ 2 لمدة 5 دقايق + الاستعلامات الجانبية (اسم العمل/الشخصيات) تقف
    @Volatile private var quotaCapUntil = 0L
    private fun capNow(c: Int): Int = if (System.currentTimeMillis() < quotaCapUntil) minOf(c, 2).coerceAtLeast(1) else c
    private fun noteQuota() {
        val now = System.currentTimeMillis()
        if (now >= quotaCapUntil) { warnUser("quota", "⚠ كوتة جيميناي خلصت (429) — الترجمة هتبطّأ 5 دقايق وبترجع لوحدها"); host.log("⚠ كوتة (429): الطلبات المتوازية اتقلّلت لـ 2 لمدة 5 دقايق، والبحث عن العمل/الشخصيات واقف لحد ما المفاتيح ترجع") }
        quotaCapUntil = now + 5 * 60_000L
    }
    /** الاستعلامات الجانبية (بحث اسم العمل · الشخصيات) بتشتغل بس لو فيه مفتاح شغّال ومفيش 429 جديد — عشان ماتاخدش كوتة من الترجمة نفسها */
    /** (v267) في «أوفلاين + جوجل» مهام جيميناي الجانبية (سدّ الفجوات · الضمائر · الشخصيات/اسم العمل) واقفة — إلا لو side_in_google=1 */
    /** (v283→v285) المهام الجانبية النصية (ضمائر · شخصيات): تشتغل بجيميناي، أو بمحرك LLM اللي اخترته في كارت ③ («يحوّل اللهجة بـ») لو مفيش جيميناي أو في مسار جوجل المجاني. محرك LLM مختار = موافقة منك على استهلاك مفتاحه */
    private fun sideOff(): Boolean {
        if (transOnly) return true
        val optIn = Cfg.bool("side_in_google", false) || convLlm() != null
        if (keyless) return convLlm() == null
        if (TrWizard.googleOn()) return !optIn
        return false
    }
    private fun sideOk(): Boolean = !sideOff() && System.currentTimeMillis() >= quotaCapUntil && (basePool.all().any { basePool.ok(it) } || convLlm() != null)
    @Volatile private var sideNoted = false
    /** (v285) إشعار واحد لكل تشغيل: إيه اللي اتقفل ولسه ليه وإزاي يتفتح — بدل ما يتقفل بصمت */
    private fun sideNotice() {
        if (transOnly || sideNoted) return
        sideNoted = true
        val closed = ArrayList<String>()
        if (sideOff()) { closed.add("تصحيح الضمائر"); closed.add("تحليل الشخصيات") }
        if (keyless) closed.add("التنقيح")
        if (!fillOk()) closed.add("سدّ الفجوات والثغرات")
        if (closed.isEmpty()) return
        val why = if (keyless) "مفيش مفتاح جيميناي" else "مسار جوجل المجاني"
        val hint = ""
        val msg = "ℹ واقف ($why): " + closed.joinToString(" · ") + hint
        host.log(msg); try { host.notice(msg) } catch (_: Throwable) {}
    }
    /** (v283) سدّ الثغرات/الفجوات بالأوفلاين نفسه (Whisper / SenseVoice / السحابي) بدل صوت جيميناي: في «تفريغ بس» وفي مسار جوجل/الخارجي وبدون مفتاح جيميناي. جيميناي مترجم = الطريقة القديمة (جيميناي) */
    private fun offFill(): Boolean = Offline.configured() && (transOnly || keyless || TrWizard.googleOn())
    private fun fillOk(): Boolean = !isClosed && (!sideOff() || offFill())
    private fun showTranscriptPreview(i: Int, p: Prep, fresh: List<Sub>) {
        if (isClosed || fresh.isEmpty()) return
        try {
            var tagged = Subs.splitAll(fresh.map { it.copy(translated = it.original, pivot = "") }).map { Subs.capPace(it).copy(chunk = i) }
            synchronized(lock) {
                tagged = Subs.dropOverlapZone(tagged, subs.filter { it.chunk != i }, p.rawStart)
                val keep = subs.filter { it.chunk != i && !(it.chunk == -1 && it.start >= p.rawStart && it.start < p.rawEnd) && !Subs.inMyZone(it, tagged, i, p.rawEnd) }
                subs = (keep + tagged).sortedBy { it.start }
            }
            previewChunks.add(i)
            // (v227) النص المنسوخ يتحفظ على الديسك قبل الترجمة السياقية ويتحسب «اتنسخ» — لو التطبيق اتقفل أو الترجمة فشلت ما بيتنسخش من الأول
            // (بعد الريستارت جمله بتتترجم بإصلاح اللغة الأجنبية التلقائي لحد ما الترجمة السياقية تحلّ محلها)
            done.add(p.rawStart, p.rawEnd)
            persist()
        } catch (e: Throwable) { host.log("⚠ عرض النص المنسوخ فشل: " + (e.message ?: "").take(80)) }
    }

    /** مفيش طلب ترانسكريبشن شغال ومفيش باتش منسوخ مستني الترجمة */
    private fun txIdle() = inflight.isEmpty() && tHold.isEmpty()

    /**
     * (v331) الجمل اللي جيميناي علّم عليها low_confidence: سماع تاني لنافذة ضيقة حواليها (±1.2ث) بمفتاح تاني، ومعاها قراءة المرة الأولى كمرجع.
     * بنستبدل النص بس لو الرد منطقي (مش فاضي ولا أطول بكتير)؛ التوقيت والباقي زي ما هو. أقصى 4 جمل للباتش. مش بيشتغل مع التفريغ الأوفلاين.
     */
    private fun relistenLow(i: Int, fresh: List<Sub>, key0: String): List<Sub> {
        if (Offline.on() || !running || isClosed) return fresh
        val idx = fresh.indices.filter { fresh[it].lowConf && !fresh[it].isSound && !fresh[it].isSong && fresh[it].original.isNotBlank() }.take(4)
        if (idx.isEmpty()) return fresh
        val out = fresh.toMutableList()
        var key = pool.pick(key0, rr.getAndIncrement()) ?: key0
        var fixed = 0
        for (ix in idx) {
            if (!running || isClosed) break
            val s = fresh[ix]
            try {
                val wv = src().wav(maxOf(0.0, s.start - 1.2), s.end + 1.2) ?: continue
                if (wv.silent) continue
                val lang = if (detDone && srcLang.isNotBlank()) " in $srcLang" else ""
                val gl = effectiveGloss().joinToString(", ") { it.term }.take(400)
                val pr = "You are a meticulous verbatim transcriber. This short audio contains one spoken sentence (with a little context before and after). A first pass heard it as: «${s.original}» but was NOT sure. " +
                    "Listen again very carefully and write exactly the words that are said$lang, using the sound only (never what the story would suggest). Keep it one sentence. " +
                    (if (gl.isNotBlank()) "Names and terms that appear in this video: $gl. " else "") +
                    "If it stays unclear, give your best reading and set low_confidence=true. If you hear no speech at all, return an empty original. " +
                    "Return JSON only: {\"original\":\"\",\"low_confidence\":false}"
                val r = Api.generate(curModel(), key, pr, wv.bytes, 512, 0.0)
                val j = if (r.text.isBlank()) null else Parse.json(r.text)
                val nt = j?.optString("original", "")?.trim().orEmpty()
                if (nt.isEmpty() || nt.length > s.original.length * 2 + 20) continue
                out[ix] = s.copy(original = nt, lowConf = j?.optBoolean("low_confidence", false) ?: s.lowConf)
                if (nt != s.original) fixed++
            } catch (e: ApiErr) {
                if (e.code == 429) { pool.block(key, 60_000); key = pool.pick(key, rr.getAndIncrement()) ?: break } else break
            } catch (_: Throwable) { }
        }
        if (fixed > 0) host.log("🎧 باتش ${i + 1}: سماع تاني لـ ${idx.size} جملة واطية الثقة — اتصحّحت $fixed")
        return out
    }

    private fun holdTranscript(i: Int, p: Prep, j: JSONObject?, fresh: List<Sub>, prior: List<Sub>, key: String, partial: Boolean) {
        if (j != null && !detDone) {
            val dl = j.optString("detected_source_language", "").trim()
            if (dl.isNotEmpty()) { srcLang = dl; detDone = true; host.log("🌐 لغة الفيديو الأصلية: $dl") }
        }
        if (transOnly) {   // (v280) تفريغ بس: النص الأصلي بيتعرض ويتحفظ ويتحسب «اتنسخ» من غير ما يدخل مجمّع الترجمة
            showTranscriptPreview(i, p, fresh)
            host.log("✍ باتش ${i + 1}: ${fresh.size} جملة اتفرّغت — تفريغ بس (من غير ترجمة)")
            host.changed()
            scheduleHoleFill(i, p.rawStart, p.rawEnd, p.w, key)   // (v283) الثغرات بتتسدّ بالتفريغ الأوفلاين نفسه (مفيش ترجمة)
            return
        }
        tHold[i] = TItem(i, p, j, fresh, prior, key, partial, System.currentTimeMillis())
        if (activeMode >= KeyMode.TRANSCRIBE_ALL) showTranscriptPreview(i, p, fresh)   // (v224) الوضع 4: اعرض النص الأصلي فورًا
        host.log("📝 باتش ${i + 1}: ${fresh.size} جملة منسوخة — مستني الترجمة (في المجمّع ${tHold.size - tTaken.size})")
        host.changed()
    }

    private fun releaseToFull(t: TItem, why: String) {
        if (keyless) {   // (v399) من غير مفتاح جيميناي: «طلب كامل» مستحيل (NoKey) — الباتش يفضل في المجمّع ويتعاد ترجمته بجوجل بعد شوية، وبعد 4 محاولات يفضل بنصه الأصلي والإصلاح التلقائي بيكمّله
            val n = trRetryN.merge(t.i, 1, Int::plus) ?: 1
            if (n < 4) {
                trRetryAt[t.i] = System.currentTimeMillis() + 8_000L * n
                host.log("🔁 باتش ${t.i + 1}: $why — هعيد الترجمة بجوجل ($n/3)"); host.changed(); return
            }
            trRetryN.remove(t.i); trRetryAt.remove(t.i); tHold.remove(t.i)
            host.log("⚠ باتش ${t.i + 1}: جوجل ماترجمش بعد 3 محاولات — فضل بنصه الأصلي ومحاولة الإصلاح التلقائي هتكمّله"); host.changed(); return
        }
        fullChunks.add(t.i); claimed.remove(t.i); tHold.remove(t.i)
        if (queueBackup(t.i, t.p, why)) { host.changed(); return }   // (v216) على المفتاح الاحتياطي بالترتيب
        host.log("↩ باتش ${t.i + 1}: $why — هيتبعت تاني كطلب كامل (صوت ← ترجمة) عشان ما يتخطّاش")
        host.changed()
    }

    /**
     * بيبعت للترجمة أقدم 3–5 باتشات منسوخة. بيستنى التجميع (TGROUP_MIN) إلا لو: [flush] (مفيش ترانسكريبشن جاي) أو الباتش مستني أكتر من
     * TR_MAX_WAIT_MS أو المشاهد واصل له تقريبًا أو الوضع اتبدّل لـ 1/2. بيرجع true لو بعت مجموعة.
     */
    private fun translateTick(flush: Boolean): Boolean {
        if (activeMode >= KeyMode.TRANSCRIBE_ALL && !switching) return contextTick()   // (v219) الوضع 4: ترجمة سياقية واحدة بعد ما كل الباتشات تتنسخ
        if (tHold.isEmpty() || !running || isClosed) return false
        if (trBusy.get() >= maxOf(1, trPool.capacity(1))) return false
        val avail = tHold.keys.filter { it !in tTaken }.sorted()
        if (avail.isEmpty()) return false
        val now = System.currentTimeMillis()
        val oldest = avail.minOf { tHold[it]?.at ?: now }
        val viewer = if (headless) -1 else (host.position() / ch).toInt()
        val urgent = activeMode < KeyMode.SPLIT_A || flush || switching || now - oldest > TR_MAX_WAIT_MS || (viewer >= 0 && avail.first() <= viewer + 1)
        if (!urgent && avail.size < TGROUP_MIN) return false
        val take = avail.take(TGROUP_MAX)
        val group = take.mapNotNull { tHold[it] }
        if (group.isEmpty()) return false
        tTaken.addAll(take); trBusy.incrementAndGet()
        try {
            trEx.submit {
                try { translateGroup(group) }
                catch (e: Exception) { host.log("⚠ ترجمة مجمّعة فشلت: " + (e.message ?: e.javaClass.simpleName).take(120)) }
                finally { tTaken.removeAll(take.toSet()); trBusy.decrementAndGet(); host.changed() }
            }
        } catch (_: RejectedExecutionException) { tTaken.removeAll(take.toSet()); trBusy.decrementAndGet(); return false }
        return true
    }

    private fun translateGroup(group: List<TItem>) {
        val dl = convDialect.let { if (it.isBlank() || it == "فصحى") "" else it }
        val c = if (dl.isEmpty() || dl == conf.lang) conf else dialConf.getOrPut(dl) { conf.withLang(dl) }
        val flat = ArrayList<Sub>(); val off = IntArray(group.size)
        group.forEachIndexed { g, t -> off[g] = flat.size; flat.addAll(t.fresh) }
        val tr = arrayOfNulls<String>(flat.size); val pv = arrayOfNulls<String>(flat.size)
        val tp = trPool
        val lastIdx = group.last().i
        host.log("🌍 ترجمة مجمّعة: باتشات " + group.joinToString("، ") { (it.i + 1).toString() } + " — ${flat.size} جملة")
        var key: String? = null
        var round = 0; var langBad = 0; var swapped = false
        try {
            while (running && !isClosed && round < TR_ROUNDS) {
                val missing = flat.indices.filter { tr[it].isNullOrBlank() }
                if (missing.isEmpty()) break
                round++
                val items = missing.map { flat[it] }
                val first = items.first().start
                val before = synchronized(lock) { subs.filter { it.start < first && it.translated.isNotBlank() && !(it.isSound && (it.original.startsWith("📖") || it.original.startsWith("💡"))) }.takeLast(20) }   // (v425) الترانسكريبشن القديم + ترجمته (من غير سطور الشرح)
                val after = tHold.values.filter { it.i > lastIdx }.sortedBy { it.i }.flatMap { it.fresh }.take(6)
                val prompt = toneBlock() + ModePrompts.translate(pb, c, items, before, after, effectiveChars(), effectiveGloss(), langBad > 0)
                var reply: JSONObject? = null
                var attempts = 0; var net = 0; var bad = 0
                while (running && !isClosed && reply == null && attempts++ < 6) {
                    val k = key?.takeIf { tp.ok(it) } ?: tp.pickFree(null, rr.getAndIncrement()) ?: pool.pickFree(null, rr.getAndIncrement())
                    if (k == null) { nap(1500); continue }
                    key = k
                    val t0 = System.currentTimeMillis()
                    try {
                        tp.begin(k)
                        val r = try { Api.generate(curModel(), k, prompt, null, maxTokens = (items.size * 150).coerceIn(4096, 16384), temp = 0.2).also { tp.end(k, System.currentTimeMillis() - t0, true) } }
                                catch (e: Throwable) { tp.end(k, 0, false); throw e }
                        if (isClosed) return
                        val jj = if (r.text.isBlank()) null else Parse.json(r.text)
                        if (jj == null) { if (++bad > 2) break; host.log("⚠ ترجمة مجمّعة: رد مش JSON صالح — إعادة"); nap(600); continue }
                        tp.good(k); reply = jj; flushModeMsg()
                    } catch (e: ApiErr) {
                        val invalid = e.code == 401 || e.code == 403 || (e.code == 400 && (e.message ?: "").contains("API key", true))
                        if (e.code == 429) { noteQuota(); tp.block(k, 60_000); key = null; host.log("⏳ ترجمة مجمّعة: كوتة على ${tp.tail(k)} — تحويل لمفتاح تاني"); nap(800); continue }
                        if (invalid) { tp.block(k, 3_600_000); key = null; host.log("🔑 ترجمة مجمّعة: مفتاح ${tp.tail(k)} غير صالح — تحويل"); continue }
                        if (e.code >= 500) { host.log("⚠ ترجمة مجمّعة: خطأ سيرفر (${e.code}) — إعادة"); nap(3000); continue }
                        throw e
                    } catch (e: Unsupported) { throw e
                    } catch (e: IOException) {
                        if (++net > 4) throw e
                        host.log("📶 ترجمة مجمّعة: مشكلة اتصال — إعادة"); nap(2000L * net)
                    }
                }
                val rep0 = reply
                if (rep0 == null) { if (swapTranslate(items, missing, tr, pv) > 0) { swapped = true; continue } else break }   // (v286) بديل تلقائي
                val rep = rep0
                val got = TParse.translations(rep, items.size)
                val okMap = LinkedHashMap<Int, Pair<String, String>>()
                var offCount = 0
                for ((ix, pr) in got) { val o = missing[ix]; if (LangGuard.foreign(flat[o].copy(translated = pr.first))) offCount++; okMap[o] = pr }
                if (offCount > 0 && offCount * 10 >= got.size * 3 && langBad < 2) {
                    langBad++; host.log("🌐 ترجمة مجمّعة: $offCount/${got.size} جملة مش بالعربي — إعادة بتشديد"); continue
                }
                for ((o, pr) in okMap) { tr[o] = pr.first; pv[o] = pr.second }
                if (got.size < items.size) host.log("↩ ترجمة مجمّعة: رجع ناقص ${items.size - got.size} جملة — هعيد الناقص بس")
            }
        } catch (e: Unsupported) {
            fatal = e.message; host.log("⛔ ${e.message}"); host.status("⛔ ${e.message}"); running = false; return
        } catch (e: Exception) {
            host.log("⚠ ترجمة مجمّعة: " + (e.message ?: e.javaClass.simpleName).take(120))
        }
        if (!running || isClosed) return
        applyGroupTranslated(group, off, tr, pv, if (swapped) "" else dl)
    }

    /** كل باتش بيتطبّق بجمله كلها بتوقيتها الأصلي بالترتيب (idx)، أو يرجع كطلب كامل (v219: اتفصلت عشان الترجمة السياقية تستخدمها كمان) */
    private fun applyGroupTranslated(group: List<TItem>, off: IntArray, tr: Array<String?>, pv: Array<String?>, dl: String) {
        // كل باتش بيتطبّق بجمله كلها بتوقيتها الأصلي بالترتيب (idx)، أو يرجع كطلب كامل
        var released = 0
        for ((g, t) in group.withIndex()) {
            if (tHold[t.i] !== t) continue   // اتعمله redo أثناء الترجمة
            val a = off[g]
            if ((a until a + t.fresh.size).any { tr[it].isNullOrBlank() }) { releaseToFull(t, "الترجمة رجعت ناقصة"); released++; continue }
            try {
                var list = t.fresh.mapIndexed { q, s -> s.copy(translated = tr[a + q]!!.trim(), pivot = pv[a + q].orEmpty()) }
                if (LangGuard.foreignOf(list).isNotEmpty()) list = fixForeign(list)
                if (dl.isEmpty()) dialOf.remove(t.i) else dialOf[t.i] = dl
                applyChunk(t.i, t.p.rawStart, t.p.rawEnd, t.p.w, t.j, list, t.prior)
                tHold.remove(t.i); trRetryN.remove(t.i); trRetryAt.remove(t.i)
                if (t.partial) { incomplete.add(t.i); host.log("⚠ باتش ${t.i + 1} رجع ناقص — تقدر تعيده من علامة التحذير") }
                scheduleHoleFill(t.i, t.p.rawStart, t.p.rawEnd, t.p.w, t.key)
            } catch (e: Exception) { releaseToFull(t, "فشل تطبيق الترجمة: " + (e.message ?: "").take(80)) }
        }
        if (released > 0 && TrWizard.googleOn()) {   // (v274) المحركات الخارجية وجوجل ماكمّلوش: الباتشات دي بترجع كطلب كامل (احتياطي ثم صوت ← ترجمة) بمفاتيح جيميناي
            // (v389) إشعار واحد «فشل ← اتحوّل» وبس؛ لو مفيش تحويل (مفيش مفتاح جيميناي) مفيش إشعار (اللوج بس)
            if (keyless) host.log("⚠ محركات الترجمة وقفت ومفيش مفتاح جيميناي — $released باتش هيتعاد لما تشتغل")
            else if (!Strict.noGemini()) swapNotice("🔄 فشلت محركات الترجمة (${ExtTranslate.chainLabel().substringBeforeLast(" ← ")}) — تم التحويل لجيميناي ($released باتش)")
        }
    }

    // ===== (v219) الوضع 4: ترانسكريبشن بكل المفاتيح، وبعدها ترجمة سياقية لكل النص (مرة واحدة أو أجزاء) =====
    private val CTX_PART_MAX = 260     // أقصى عدد جمل في الجزء الواحد (الأجزاء بتتقسم على حدود الباتشات)
    private val CTX_FULL_MAX = 1500    // لحد العدد ده بنبعت النص الكامل كسياق مع كل جزء، وفوقه بنبعت نافذة حوالين الجزء
    private val CTX_WINDOW = 300
    /** (v400) وضع «وشبر + جيميناي بالسياق»: عدد الباتشات في كل طلب ترجمة (2 أو 3)، 0 = الوضع مش شغّال */
    private fun ctxBatches(): Int = if (Cfg.str("tr_mode_id", "") == "loc_ctx" && Offline.configured() && !TrWizard.googleOn()) Cfg.int("ctx_batches", 3).coerceIn(2, 3) else 0
    private val GT_CTX_LINES = 30      // (v399) أقصى عدد سطور سياق (من الباتشات اللي قبل) مع كل طلب جوجل
    private val GT_CTX_CHARS = 2500    // (v399) وأقصى عدد حروف — الطلب ما بيكبرش مع طول الفيديو
    private val trRetryAt = ConcurrentHashMap<Int, Long>()   // (v399) باتش جوجل فشل في ترجمته: ميعاد المحاولة الجاية
    private val trRetryN = ConcurrentHashMap<Int, Int>()
    @Volatile private var ctxMsg = ""
    private val warnAtMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** (v258) إشعار للمستخدم بدل ما الخطأ يفضل في اللوج بس. [key] بيحدّد النوع — نفس النوع مرة كل 60 ث بالكتير */
    private fun warnUser(key: String, msg: String) {
        // (v389) إشعارات «فشل من غير تحويل» مابتظهرش (اللوج بس) — الإشعار الوحيد هو «فشل ← تم التحويل»
        if (key in quietFailKeys) { host.log(msg); return }
        if (keyless) return   // (v259) من غير مفتاح: خطوات جيميناي الجانبية بتتخطّى بهدوء (الإشعار الأساسي بيتعرض مرة واحدة)
        val n = System.currentTimeMillis()
        val last = warnAtMap[key] ?: 0L
        if (n - last < 60_000L) return
        warnAtMap[key] = n
        try { host.notice(msg) } catch (_: Throwable) {}
    }
    @Volatile private var whoKind = ""; @Volatile private var whoAt = 0L
    /** (v278) «مين اللي بيترجم فعلًا»: سطر لوج كل مرة، وإشعار لما النوع يتغيّر (أو كل 3 دقايق) — مش كل باتش */
    private fun noteWho(kind: String, detail: String) {
        host.log(kind + detail)
        val n = System.currentTimeMillis()
        if (kind == whoKind && n - whoAt < 180_000L) return
        whoKind = kind; whoAt = n
        try { host.notice(kind + detail) } catch (_: Throwable) {}
    }
    private val quietFailKeys = setOf("conv", "ctx", "part", "refine")
    @Volatile private var swapNoticeAt = 0L   // (v274) آخر إشعار «محرك وقف ← التاني كمّل» (مرة كل 20 ث بالكتير)
    private val skipNoted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()   // (v274) مزوّدين اتقال إنهم موقوفين (بيتشالوا لما يرجعوا)
    /** (v274) إشعار تبديل محرك الترجمة + سطر في اللوج */
    /** (v286) بديل تلقائي: جيميناي وقف في الترجمة النصية (كوتة / مفاتيح / رد غلط) ← الجمل الناقصة بتتترجم بسلسلة المترجمين (المحرك الخارجي/LLM ← جوجل) بدل ما تستنى. بيتقفل من «⋯ أكتر». مش بيشتغل في مسار جوجل (هو أصلًا بالسلسلة) */
    private fun autoSwapOn(): Boolean = Cfg.str("tr_autoswap", "on") != "off" && !transOnly && !TrWizard.googleOn() && TrWizard.online()
    private fun swapTranslate(items: List<Sub>, missing: List<Int>, tr: Array<String?>, pv: Array<String?>): Int {
        if (!autoSwapOn() || missing.isEmpty() || !running || isClosed) return 0
        val g = googleItems(items.map { it.original })
        var n = 0
        for ((q, o) in missing.withIndex()) g.getOrNull(q)?.takeIf { it.isNotBlank() }?.let { tr[o] = it.trim(); pv[o] = ""; n++ }
        if (n > 0) swapNotice("🔄 فشل جيميناي في الترجمة — تم التحويل لـ " + ExtTranslate.chainLabel().substringBeforeLast(" ← ") + " ($n جملة، فصحى؛ اللهجة بتتحوّل بعدين)")
        return n
    }

    private fun swapNotice(msg: String) {
        host.log(msg)
        val n = System.currentTimeMillis()
        if (n - swapNoticeAt < 20_000L) return
        swapNoticeAt = n
        try { host.notice(msg) } catch (_: Throwable) {}
    }
    @Volatile private var gtNoticeAt = 0L   // (v255) آخر إشعار «جوجل فشل» (مرة كل 30 ث بالكتير)

    // (v265) حالة الأوفلاين للعرض (الكبسولة + اللوج الصغير): بيفرّغ إيه، اتفرّغ كام، مستني إيه، بيترجم إيه
    @Volatile private var offAt = 0L
    @Volatile private var offB = ""
    @Volatile private var offD = ""
    private fun offRefresh() {
        val now = System.currentTimeMillis()
        if (now - offAt < 800L) return
        offAt = now
        val d = currentDur(); val tot = if (d > 0) Math.ceil(d / ch).toInt() else 0
        val k = ctxBatches().takeIf { it > 0 } ?: if (Offline.live() || TrWizard.googleOn()) 1 else maxOf(1, (Offline.partMin() * 60.0 / ch).toInt())
        val doneN = if (tot > 0) (0 until tot).count { isDone(it, d) } else 0
        val held = tHold.size
        val txN = minOf(tot, doneN + held)
        val cur = inflight.minOrNull(); val trN = trBusy.get()
        val who = if (TrWizard.googleOn()) (if (ExtTranslate.semiLibre()) "LibreTranslate" else "جوجل") else "جيميناي"
        offB = "📴 $txN/$tot" + (if (trN > 0) " 🌍" else if (cur != null) " ⏳" else "")
        val sb = StringBuilder("📴 أوفلاين: اتفرّغ $txN من $tot باتش (الباتش ${ch.toInt()}ث)")
        val live = Offline.liveLine()
        if (live != null) { sb.append("\n").append(live); if (inflight.size > 1) sb.append(" (+${inflight.size - 1} مستنيين دورهم)") }   // (v415) تقدم Whisper الفعلي
        else if (cur != null) sb.append("\n⏳ بيفرّغ دلوقتي باتش ${cur + 1} على الجهاز")
        if (trN > 0) sb.append("\n🌍 بيترجم دلوقتي بـ$who")
        else if (held > 0) sb.append("\n⏸ جاهز $held باتش — الترجمة بتبدأ أول ما يجهز $k" + (if (Offline.live()) " (لحظي)" else " (≈ ${Offline.partMin()} د)"))
        else if (cur == null && txN < tot) sb.append("\n… مستني الباتش الجاي")
        offD = sb.toString()
    }
    fun offlineBrief(): String? { if (!Offline.configured() || !running) return null; offRefresh(); return offB }
    fun offlineDetail(): String? { if (!Offline.configured() || !running) return null; offRefresh(); return offD }

    /** كل الباتشات المطلوبة اتنسخت (مفيش حاجة شغالة ولا مستنية ولا مستنية إعادة) */
    private fun ctxReady(): Boolean {
        val d = currentDur()
        if (d <= 0 || inflight.isNotEmpty() || rehomeQ.isNotEmpty() || priorityRetry.isNotEmpty() || bkQ.isNotEmpty()) return false
        val only = onlyChunks
        val win = !keepGoing && !headless && only == null
        val cur = if (win) (host.position() / ch).toInt().coerceAtLeast(0) else 0
        var i = only?.first ?: cur
        val end = only?.last ?: if (win) cur + window - 1 else Int.MAX_VALUE
        while (i <= end && cStart(i) < d) {
            if (!(isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS || tHold.containsKey(i))) return false
            i++
        }
        return true
    }

    private fun contextTick(): Boolean {
        if (tHold.isEmpty() || !running || isClosed) return false
        if (trBusy.get() > 0) return false
        val nowT = System.currentTimeMillis()
        val avail = tHold.keys.filter { it !in tTaken && (trRetryAt[it] ?: 0L) <= nowT }.sorted()   // (v399) باتش جوجل فشل: بيستنى شوية قبل المحاولة الجاية
        if (avail.isEmpty()) return false
        if (!ctxReady() && Offline.configured() && partTick(avail)) return true   // (v253) جزء كامل اتنسخ ← يتترجم فورًا
        if (!ctxReady()) {
            val m = if (Offline.configured()) { offRefresh(); offD.replace("\n", " · ") } else "📝 ترانسكريبشن: ${avail.size} باتش جاهز — الترجمة السياقية بتبدأ لما كل الباتشات تتنسخ"
            if (m != ctxMsg) { ctxMsg = m; host.status(m) }
            return false
        }
        val group = avail.mapNotNull { tHold[it] }
        if (group.isEmpty()) return false
        tTaken.addAll(avail); trBusy.incrementAndGet()
        try {
            trEx.submit {
                try { translateContextual(group) }
                catch (e: Exception) { warnUser("ctx", "⚠ الترجمة السياقية فشلت — جمل ممكن تفضل من غير ترجمة لحد ما تتعاد"); host.log("⚠ ترجمة سياقية فشلت: " + (e.message ?: e.javaClass.simpleName).take(120)) }
                finally { tTaken.removeAll(avail.toSet()); trBusy.decrementAndGet(); host.changed() }
            }
        } catch (_: RejectedExecutionException) { tTaken.removeAll(avail.toSet()); trBusy.decrementAndGet(); return false }
        return true
    }

    /**
     * (v253) الأوفلاين بطيء (دقايق للفيلم)، فمش بنستنى الفيلم كله: أول ما k باتش ورا بعض (= 5/10/20 دقيقة) يتنسخوا وماحدش قبلهم لسه شغّال
     * بيتبعتوا لجيميناي يترجمهم. الباقي (اللي أقل من جزء) بيتترجم لما كل الباتشات تخلص زي الوضع 4 العادي.
     */
    private fun partTick(avail: List<Int>): Boolean {
        val k = ctxBatches().takeIf { it > 0 } ?: if (Offline.live() || TrWizard.googleOn()) 1 else maxOf(1, (Offline.partMin() * 60.0 / ch).toInt())   // (v399) جوجل: باتش بباتش · (v400) وضع السياق: 2–3 باتشات
        if (avail.size < k) return false
        val head = avail.take(k)
        if (head.last() - head.first() != k - 1) return false      // فيه باتش ناقص في النص (لسه بيتنسخ أو متأخر)
        if (inflight.any { it < head.last() }) return false
        val group = head.mapNotNull { tHold[it] }
        if (group.size != k) return false
        tTaken.addAll(head); trBusy.incrementAndGet()
        try {
            trEx.submit {
                try { translateContextual(group) }
                catch (e: Exception) { warnUser("part", "⚠ ترجمة جزء من الفيديو فشلت — هتتعاد تلقائي"); host.log("⚠ ترجمة جزء فشلت: " + (e.message ?: e.javaClass.simpleName).take(120)) }
                finally { tTaken.removeAll(head.toSet()); trBusy.decrementAndGet(); host.changed() }
            }
        } catch (_: RejectedExecutionException) { tTaken.removeAll(head.toSet()); trBusy.decrementAndGet(); return false }
        host.log("📴 جزء جاهز (${k} باتش" + (if (k > 1 && ctxBatches() == 0) " ≈ ${Offline.partMin()} دقيقة" else "") + ") — اتبعت " + (if (TrWizard.googleOn()) (if (ExtTranslate.semiLibre()) "لـ LibreTranslate" else "لجوجل") else "لجيميناي") + " يترجمه")
        return true
    }

    /** (v254) ترجمة جوجل غير الرسمية لقايمة نصوص (لغة المصدر من إعداد الأوفلاين). بترجّع نفس الطول، والفاشل null */
    /** (v411) لغة المصدر لجوجل/ML Kit: اللي اخترتها بإيدك، وإلا (في مسار Whisper) لغة Whisper متراجعة بحروف النص، وإلا "auto" */
    private fun resolveSl(src: List<String>): String {
        val manual = Offline.lang()
        if (manual.isNotEmpty()) return if (manual == "zh") "zh-CN" else manual
        if (!OffLang.mergeOn()) return TrWizard.googleLang()
        langVote.add(src)
        val d = langVote.decide(srcLang.ifEmpty { offSession.hint })
        if (d != langVote.lastAnnounced) {
            langVote.lastAnnounced = d
            if (d.isNotEmpty()) {
                val c = langVote.conflict(srcLang.ifEmpty { offSession.hint })
                host.log("🌐 لغة الترجمة: ${OffLang.nameOf(d)}" + (c?.let { " ($it — اخترت بالحروف)" } ?: ""))
                if (c != null) try { host.notice("🌐 اللغة: ${OffLang.nameOf(d)} — $c") } catch (_: Throwable) {}
            }
        }
        return when { d.isEmpty() -> "auto"; d == "zh" -> "zh-CN"; else -> d }
    }

    /**
     * (v411) دمج الجمل المقطّعة: Whisper بيقطّع الجملة نصين، وML Kit/جوجل بيترجموا كل سطر لوحده فبيطلع مكسّر.
     * بنضم الأسطر المتتالية اللي مش واقفة بعلامة نهاية في جملة واحدة، نترجمها، وبعدين نقسم الترجمة على نفس الأسطر (والتوقيتات بتفضل زي ما هي لأن بنرجّع نص كل سطر بس).
     */
    private fun googleItems(src0: List<String>, ctx: List<String> = emptyList()): List<String?> {
        if (OffLang.mergeOn()) MlKitTr.say = { m -> host.log(m); try { host.notice(m) } catch (_: Throwable) {} }   // رسائل تحميل موديل اللغة
        if (!OffLang.mergeOn() || src0.size < 2) return googleItemsRaw(src0, ctx)
        val clean = src0.map { it.replace(Regex("\\s*\n\\s*"), " ").trim() }
        val groups = OffLang.group(clean)
        if (groups.all { it.size == 1 }) return googleItemsRaw(src0, ctx)
        val merged = groups.map { g -> OffLang.join(g.map { clean[it] }) }
        val tr = googleItemsRaw(merged, ctx)
        val out = arrayOfNulls<String>(src0.size)
        val redo = ArrayList<Int>()   // مجموعات الترجمة بتاعتها أقصر من عدد أسطرها — بتتترجم سطر سطر
        for ((gi, g) in groups.withIndex()) {
            val t = tr.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: continue
            if (g.size == 1) { out[g[0]] = t; continue }
            val parts = OffLang.split(t, g.map { clean[it].length.coerceAtLeast(1) })
            if (parts == null) { redo.addAll(g); continue }
            for ((q, k) in g.withIndex()) out[k] = parts[q]
        }
        if (redo.isNotEmpty() && running && !isClosed) {
            val r = googleItemsRaw(redo.map { src0[it] }, ctx)
            for ((q, k) in redo.withIndex()) r.getOrNull(q)?.takeIf { it.isNotBlank() }?.let { out[k] = it }
        }
        val n = groups.count { it.size > 1 }
        if (n > 0) host.log("🔗 دمج الجمل: $n جملة مقطّعة اتترجمت كاملة وانقسمت على أسطرها")
        return out.toList()
    }

    private fun googleItemsRaw(src0: List<String>, ctx: List<String> = emptyList()): List<String?> {
        val src = src0.map { it.replace(Regex("\\s*\n\\s*"), " ").trim() }
        val out = arrayOfNulls<String>(src.size)
        val sl = resolveSl(src)
        /** جوجل الداخلي (gtCall): 25 جملة في الطلب */
        // (v399) سياق جوجل: سطور الباتشات اللي قبل (ctx) + الجمل اللي قبلها في نفس الباتش بتتبعت قدام الطلب عشان جوجل يترجم بالسياق،
        // وبعدين بنشيل رد سطور السياق ونرجّع رد الجمل المطلوبة بس بنفس ترتيبها (يعني بنفس توقيتاتها). لو عدد الأسطر اختلف: نرجع للطلب من غير سياق
        val ctxL = ctx.map { it.replace(Regex("\\s*\n\\s*"), " ").trim() }.filter { it.isNotBlank() }
        fun lead(firstIdx: Int): List<String> {
            val pre = ArrayList<String>(ctxL)
            for (q in 0 until firstIdx) if (src[q].isNotBlank()) pre.add(src[q])
            val keep = ArrayList<String>(); var chars = 0
            for (s in pre.asReversed()) { if (keep.size >= GT_CTX_LINES || chars + s.length > GT_CTX_CHARS) break; keep.add(s); chars += s.length }
            return keep.asReversed()
        }
        fun viaGoogle(idx: List<Int>) {
            var failedB = 0
            for (b in idx.chunked(25)) {
                if (!running || isClosed) break
                try {
                    val ld = lead(b.first())
                    var r: List<String>? = null
                    if (ld.isNotEmpty()) r = gtCall(ld + b.map { src[it] }, "ar", sl)?.drop(ld.size)
                    if (r == null || r.size != b.size) r = gtCall(b.map { src[it] }, "ar", sl)
                    if (r != null) { for ((q, k) in b.withIndex()) out[k] = r[q] }
                    else for (k in b) { gtCall(listOf(src[k]), "ar", sl)?.firstOrNull()?.let { out[k] = it } }   // عدد الأسطر اختلف: جملة جملة
                } catch (e: Exception) { failedB++; host.log("⚠ ترجمة جوجل: " + (e.message ?: e.toString()).take(80)); if (failedB >= 3) break }
            }
        }
        /** (v272) مزوّد خارجي (MyMemory / Lingva / LibreTranslate / LLM…) للجمل اللي لسه ماترجمتش */
        fun viaExt(p: ExtTranslate.Prov, idx: List<Int>) {
            val r = ExtTranslate.run(p, idx.map { src[it] }, sl) { running && !isClosed }
            var n = 0
            for ((q, k) in idx.withIndex()) r.getOrNull(q)?.let { out[k] = it; n++ }
            host.log("🔀 ${p.label}: ترجم $n/${idx.size} جملة")
            if (n < idx.size && ExtTranslate.lastError.isNotEmpty()) host.log("⚠ ${p.label}: ${ExtTranslate.lastError}")
            ExtTranslate.lastNote.takeIf { it.isNotEmpty() }?.let { ExtTranslate.lastNote = ""; host.log(it); try { host.notice(it) } catch (_: Throwable) {} }   // (v274) مثلًا: الموديل اتغيّر لوحده
        }
        // (v274) السلسلة: [المحرك الأساسي لو متحدد] ← باقي الخارجيين ← جوجل (حسب سويتش الترتيب) ← (لو كله فشل) الباتش بيتحوّل لجيميناي بمفاتيحك
        val steps = ExtTranslate.chain()   // null = جوجل الداخلي
        val who = LinkedHashMap<String, Int>()   // (v278) مين ترجم كام جملة في اللفة دي
        fun nm(st: ExtTranslate.Prov?): String = st?.label ?: "جوجل"
        for ((si, st) in steps.withIndex()) {
            if (!running || isClosed) break
            val need = src.indices.filter { out[it].isNullOrBlank() && src[it].isNotBlank() }
            if (need.isEmpty()) break
            val nextName = if (si + 1 < steps.size) nm(steps[si + 1]) else "جيميناي (مفاتيحك)"
            if (st != null) {   // مزوّد واقف (مفتاح مرفوض / كوتة / رصيد): بنعدّيه من غير ما نبعتله حاجة، وبنقول مرة واحدة بس
                val bw = ExtTranslate.blockedWhy(st.id, st.keyHash())
                if (bw != null) { if (skipNoted.add(st.id)) swapNotice("🔄 فشل ${st.label} ($bw) — تم التحويل لـ $nextName"); continue }
                skipNoted.remove(st.id)
            }
            if (st == null) viaGoogle(need) else viaExt(st, need)
            need.count { !out[it].isNullOrBlank() }.let { g -> if (g > 0) who[ExtTranslate.whoLabel(st)] = g }
            val left = src.indices.count { out[it].isNullOrBlank() && src[it].isNotBlank() }
            if (left > 0) {
                val why = if (st == null) "ما ردّش" else ExtTranslate.lastError.ifEmpty { "ما كمّلش" }
                if (st != null) skipNoted.add(st.id)
                swapNotice("🔄 فشل ${nm(st)} ($why) — تم التحويل لـ $nextName ($left جملة)")
            }
        }
        if (who.isNotEmpty()) noteWho("✅ بيترجم بـ " + who.keys.joinToString(" + "), " — " + who.entries.joinToString(" · ") { it.value.toString() + " جملة" })
        return out.toList()
    }

    /** طلب Gemini نصي واحد (من غير صوت) بإعادة المحاولة وتحويل المفتاح عند الكوتة/الخطأ. null = فشل */
    private fun askJson(prompt: String, maxTok: Int, tp: Pool, tag: String): JSONObject? {
        var key: String? = null
        var attempts = 0; var net = 0; var bad = 0
        while (running && !isClosed && attempts++ < 6) {
            val k = key?.takeIf { tp.ok(it) } ?: tp.pickFree(null, rr.getAndIncrement()) ?: pool.pickFree(null, rr.getAndIncrement())
            if (k == null) { nap(1500); continue }
            key = k
            val t0 = System.currentTimeMillis()
            try {
                tp.begin(k)
                val r = try { Api.generate(curModel(), k, prompt, null, maxTokens = maxTok, temp = 0.2).also { tp.end(k, System.currentTimeMillis() - t0, true) } }
                        catch (e: Throwable) { tp.end(k, 0, false); throw e }
                if (isClosed) return null
                val jj = if (r.text.isBlank()) null else Parse.json(r.text)
                if (jj == null) { if (++bad > 2) return null; host.log("⚠ $tag: رد مش JSON صالح — إعادة"); nap(600); continue }
                tp.good(k); flushModeMsg(); return jj
            } catch (e: ApiErr) {
                val invalid = e.code == 401 || e.code == 403 || (e.code == 400 && (e.message ?: "").contains("API key", true))
                if (e.code == 429) { noteQuota(); tp.block(k, 60_000); key = null; host.log("⏳ $tag: كوتة على ${tp.tail(k)} — تحويل لمفتاح تاني"); nap(800); continue }
                if (invalid) { tp.block(k, 3_600_000); key = null; host.log("🔑 $tag: مفتاح ${tp.tail(k)} غير صالح — تحويل"); continue }
                if (e.code >= 500) { host.log("⚠ $tag: خطأ سيرفر (${e.code}) — إعادة"); nap(3000); continue }
                throw e
            } catch (e: Unsupported) { throw e
            } catch (e: IOException) {
                if (++net > 4) throw e
                host.log("📶 $tag: مشكلة اتصال — إعادة"); nap(2000L * net)
            }
        }
        return null
    }

    /**
     * الباتشات المنسوخة كلها بتتترجم بالسياق: النص الكامل بيتبعت كسياق مع كل جزء، والجزء (لحد CTX_PART_MAX جملة) بيتترجم بعد اللي قبله بالترتيب
     * (الأجزاء ورا بعض عشان الأسماء والضمائر تفضل ثابتة)، وكل جزء بيتطبّق بمجرد ما يخلص. باتش رجع ناقص = يرجع كطلب كامل (احتياطي ثم صوت ← ترجمة).
     */
    private fun translateContextual(group0: List<TItem>) {
        val dl = convDialect.let { if (it.isBlank() || it == "فصحى") "" else it }
        val c = if (dl.isEmpty() || dl == conf.lang) conf else dialConf.getOrPut(dl) { conf.withLang(dl) }
        val group = group0.sortedBy { it.i }
        val flatAll = group.flatMap { it.fresh }
        val parts = ArrayList<List<TItem>>(); var cur = ArrayList<TItem>(); var n = 0
        for (t in group) {
            val pm = if (ctxBatches() > 0) 0 else Offline.partMin()   // (v253) 5/10/20 دقيقة للجزء (0 = بعدد الجمل بس) · (v400) وضع السياق: بعدد الباتشات بس
            if (cur.isNotEmpty() && (n + t.fresh.size > CTX_PART_MAX || (pm > 0 && (t.i - cur.first().i + 1) * ch > pm * 60.0))) { parts.add(cur); cur = ArrayList(); n = 0 }
            cur.add(t); n += t.fresh.size
        }
        if (cur.isNotEmpty()) parts.add(cur)
        host.log("🧠 ترجمة سياقية: ${flatAll.size} جملة من ${group.size} باتش — " + (if (parts.size == 1) "طلب واحد" else "${parts.size} أجزاء ورا بعض"))
        host.status("🧠 بترجم بالسياق الكامل (${flatAll.size} جملة)…")
        val tp = trPool
        val doneTr = ArrayList<Sub>()
        if (Offline.configured() && flatAll.isNotEmpty()) {   // (v253) الأجزاء بتتترجم أول بأول: آخر جمل اتترجمت قبل الجزء ده بتتبعت كسياق
            val f0 = flatAll.first().start
            doneTr.addAll(synchronized(lock) { subs.filter { it.start < f0 && it.translated.isNotBlank() && it.translated != it.original }.takeLast(if (ctxBatches() > 0) 40 else 12) })
        }
        var base = 0
        for ((pn, part) in parts.withIndex()) {
            if (!running || isClosed) return
            val flat = ArrayList<Sub>(); val off = IntArray(part.size)
            part.forEachIndexed { g, t -> off[g] = flat.size; flat.addAll(t.fresh) }
            val tr = arrayOfNulls<String>(flat.size); val pv = arrayOfNulls<String>(flat.size)
            var round = 0; var langBad = 0; var swapped = false
            try {
                while (running && !isClosed && round < TR_ROUNDS) {
                    val missing = flat.indices.filter { tr[it].isNullOrBlank() }
                    if (missing.isEmpty()) break
                    round++
                    val items = missing.map { flat[it] }
                    if (TrWizard.googleOn()) {   // (v254) الترجمة بجوجل (من غير API): 25 جملة في الطلب؛ اللي فشل بيتعاد في اللفة الجاية، ولو الكل فشل الباتشات بتتسلّم لجيميناي
                        val gf = flat.first().start
                        val gctx = synchronized(lock) { subs.filter { it.start < gf && it.original.isNotBlank() && !it.isSound }.takeLast(GT_CTX_LINES).map { it.original } }   // (v399) الباتشات اللي قبل كسياق
                        val g = googleItems(items.map { it.original }, gctx)
                        var okN = 0
                        for ((q, o) in missing.withIndex()) g.getOrNull(q)?.takeIf { it.isNotBlank() }?.let { tr[o] = it; pv[o] = ""; okN++ }
                        if (okN == 0) {
                            host.log("⚠ ترجمة جوجل: مفيش جملة اترجمت (النت أو جوجل واقف) — الباتشات هتتسلّم لجيميناي")
                            val now = System.currentTimeMillis()
                            if (now - gtNoticeAt > 30_000L) { gtNoticeAt = now; (if (!TrWizard.online()) TrWizard.NO_NET_MSG else if (keyless) null else "🔄 فشلت محركات الترجمة (${ExtTranslate.chainLabel().substringBeforeLast(" ← ")}) — تم التحويل لجيميناي")?.let { m -> host.notice(m) } }   // (v255)
                            break
                        }
                        continue
                    }
                    val full = flatAll.size <= CTX_FULL_MAX
                    val lo = if (full) 0 else (base - CTX_WINDOW).coerceAtLeast(0)
                    val hi = if (full) flatAll.size else (base + flat.size + CTX_WINDOW).coerceAtMost(flatAll.size)
                    val prompt = toneBlock() + ModePrompts.contextual(pb, c, flatAll.subList(lo, hi), lo, missing.map { base + it }, items, doneTr, effectiveChars(), effectiveGloss(), pn + 1, parts.size, langBad > 0, beforeMax = if (ctxBatches() > 0) 40 else 20)
                    val rep0 = askJson(prompt, (items.size * 90).coerceIn(4096, 32768), tp, "ترجمة سياقية")
                    if (rep0 == null) { if (swapTranslate(items, missing, tr, pv) > 0) { swapped = true; continue } else break }   // (v286) بديل تلقائي
                    val rep = rep0
                    val got = TParse.translations(rep, items.size)
                    val okMap = LinkedHashMap<Int, Pair<String, String>>()
                    var offCount = 0
                    for ((ix, pr) in got) { val o = missing[ix]; if (LangGuard.foreign(flat[o].copy(translated = pr.first))) offCount++; okMap[o] = pr }
                    if (offCount > 0 && offCount * 10 >= got.size * 3 && langBad < 2) {
                        langBad++; host.log("🌐 ترجمة سياقية: $offCount/${got.size} جملة مش بالعربي — إعادة بتشديد"); continue
                    }
                    for ((o, pr) in okMap) { tr[o] = pr.first; pv[o] = pr.second }
                    if (got.size < items.size) host.log("↩ ترجمة سياقية (جزء ${pn + 1}): رجع ناقص ${items.size - got.size} جملة — هعيد الناقص بس")
                }
            } catch (e: Unsupported) {
                fatal = e.message; host.log("⛔ ${e.message}"); host.status("⛔ ${e.message}"); running = false; return
            } catch (e: Exception) {
                warnUser("ctx", "⚠ الترجمة السياقية (جزء ${pn + 1}) فشلت — الجمل دي هتتعاد"); host.log("⚠ ترجمة سياقية (جزء ${pn + 1}): " + (e.message ?: e.javaClass.simpleName).take(120))
            }
            if (!running || isClosed) return
            applyGroupTranslated(part, off, tr, pv, if (swapped) "" else dl)
            for (k in flat.indices) tr[k]?.takeIf { it.isNotBlank() }?.let { doneTr.add(flat[k].copy(translated = it)) }
            base += flat.size
            host.log("🧠 ترجمة سياقية: خلص جزء ${pn + 1}/${parts.size}")
        }
    }

    private fun applyChunk(i: Int, rawStart: Double, rawEnd: Double, w: WavChunk, j: JSONObject?, fresh0: List<Sub>, prior: List<Sub>) {
        if (isClosed) return
        previewChunks.remove(i)   // (v224) الترجمة الحقيقية حلّت محلّ العرض المؤقت للنص الأصلي
        val fresh = fresh0
        if (j != null && !detDone) {
            val d = j.optString("detected_source_language", "").trim()
            if (d.isNotEmpty()) { srcLang = d; detDone = true; host.log("🌐 لغة الفيديو الأصلية: $d"); maybeTranslateTemplate() }
        }
        if (j != null) applyPrevCorrections(j)
        val spansAbs = Speech.activeSpans(w.bytes, w.gain)?.let { Speech.absolute(it, w.startSec) }
        val direct = dialOf[i]?.let { it == convDialect } ?: false
        var tagged = Subs.splitAll(fresh).map { Subs.capPace(it).copy(chunk = i, conv = direct) }
        // (v177) لو الكاشف ماطلّعش ولا مجال صوت والمقطع مش صامت (صوت واطي جدًا) ماينفعش نشيل جمل على أساسه
        if (spansAbs != null && (spansAbs.isNotEmpty() || w.silent)) {
            val n0 = tagged.size
            tagged = tagged.mapNotNull { Speech.fit(it, spansAbs) }
            if (tagged.size < n0) host.log("🔕 المقطع ${i + 1}: اتشالت ${n0 - tagged.size} جملة كانت فوق صمت تام")
        }
        tagged = Subs.harmonize(tagged, subs)   // توحيد ترجمة سطور الكورَس المتكررة في الأغاني
        synchronized(lock) {
            tagged = Subs.dropOverlapZone(tagged, subs.filter { it.chunk != i }, rawStart)
            val keep = subs.filter { it.chunk != i && !(it.chunk == -1 && it.start >= rawStart && it.start < rawEnd) && !Subs.inMyZone(it, tagged, i, rawEnd) }
            subs = Subs.merge(Subs.dedup(keep + tagged)).sortedBy { it.start }
            Subs.refreshMain(subs)
        }
        // (v177) دفتر الفقد: لو جمل رجعت من Gemini واختفت بعد التنضيف (تكرار/تداخل) نسجّل العدد عشان أي رجوع للمشكلة يبان في اللوج
        try {
            val lost = tagged.count { t -> val k = t.original.trim().take(10); k.length >= 4 && subs.none { it.start in (t.start - 3.0)..(t.end + 3.0) && it.original.contains(k) } }
            if (lost > 0 || fresh.size != tagged.size) host.log("📊 المقطع ${i + 1}: Gemini رجّع ${fresh.size} · بعد الفلاتر ${tagged.size} · اختفى بالتنضيف ~$lost")
        } catch (_: Throwable) {}
        done.add(rawStart, rawEnd)
        failed.remove(i); incomplete.remove(i)
        anyApplied = true
        host.changed()
        Stats.addSec(rawEnd - rawStart)
        host.log("✅ المقطع ${i + 1}: ${fresh.size} جملة — الإجمالي ${subs.size}")
        persist()
        afterChunk(w, fresh, prior)
        maybeConvert(false)
    }

    private fun applyPrevCorrections(j: JSONObject) {
        val pc = j.optJSONArray("prev_corrections") ?: return
        var n = 0
        synchronized(lock) {
            val cur = subs.toMutableList()
            for (k in 0 until pc.length()) {
                val c = pc.optJSONObject(k) ?: continue
                val snip = c.optString("original_snippet").trim()
                if (snip.length < 3) continue
                val idx = cur.indexOfLast { it.original.contains(snip) }
                if (idx < 0) continue
                var s = cur[idx]
                c.optString("gender").lowercase().takeIf { it == "male" || it == "female" }?.let { s = s.copy(gender = it) }
                c.optString("addressee").lowercase().takeIf { it in listOf("male", "female", "plural", "unknown") }?.let { s = s.copy(addressee = it) }
                c.optString("topic_gender").lowercase().takeIf { it in listOf("male", "female", "plural", "none") }?.let { s = s.copy(topicGender = it) }
                c.optString("translated").trim().takeIf { it.isNotEmpty() }?.let { s = s.copy(translated = it) }
                cur[idx] = s; n++
            }
            if (n > 0) subs = cur
        }
        if (n > 0) host.log("🩺 تصحيح $n جملة قديمة (جنس/ضمير)")
    }

    // ===== المهام الخلفية بعد كل مقطع =====
    private fun afterChunk(w: WavChunk, fresh: List<Sub>, prior: List<Sub>) {
        if (isClosed) return
        synchronized(lock) { chunksDone++ }
        maybeAnalyze()
        maybePronouns()
        maybeGenre()
        maybeRefine()
    }

    // (v108) نوع العمل (أكشن/كوميدي/رعب…) من أول جمل مترجمة — للفلتر التلقائي
    @Volatile var genreWanted = false
    @Volatile var onGenre: ((String) -> Unit)? = null
    @Volatile private var genreBusy = false
    private fun maybeGenre() {
        if (!genreWanted || genreBusy) return
        val sample: String
        synchronized(lock) {
            if (subs.size < 40) return
            genreBusy = true
            sample = subs.filter { !it.isSound && !it.isSong }.take(70).joinToString("\n") { it.translated.ifEmpty { it.original } }
        }
        try {
            bg.submit {
                try {
                    val prompt = "دي أول جمل من فيلم أو مسلسل مترجم:\n$sample\n\nحدد نوع العمل بدقة من القائمة دي بس: action (أكشن/قتال/مطاردات)، comedy (كوميدي)، horror (رعب/إثارة مظلمة)، romance (رومانسي)، scifi (خيال علمي/فانتازيا)، drama (دراما عادية)، other (مش واضح أو وثائقي).\nلو مش متأكد رجّع other. رد JSON فقط: {\"genre\":\"...\"}"
                    val r = bgCall(prompt, 128, 0.1)
                    val g = Parse.json(r.text)?.optString("genre", "")?.trim()?.lowercase() ?: ""
                    if (g in setOf("action", "comedy", "horror", "romance", "scifi", "drama", "other")) { genreWanted = false; host.log("🎨 نوع العمل: $g"); onGenre?.invoke(g) }
                } catch (e: Exception) { host.log("⚠ تحديد نوع العمل فشل: " + (e.message ?: "").take(80)) }
                finally { genreBusy = false }
            }
        } catch (_: Exception) { genreBusy = false }
    }

    private fun helperKey(i: Int = 0): String? {
        if (keyless) return null
        val pref = (conf.refine + conf.backup).distinct().ifEmpty { conf.keys }
        val ok = pref.filter { pool.ok(it) }
        if (ok.isNotEmpty()) return ok[Math.floorMod(i, ok.size)]
        return pool.helper()
    }

    /** (v285) محرك النص الاحتياطي للمهام الجانبية: محرك LLM المختار في كارت ③ بس (مش أي محرك مضبوط) — null لو مفيش أو الطلب أكبر من حدّ المحركات */
    private fun textViaLlm(prompt: String, maxTokens: Int, json: Boolean): Api.Result? {
        val p = convLlm() ?: return null
        if (maxTokens > 8192) return null
        return try { Api.Result(ExtLlm.chat(p, if (json) "Reply with one valid JSON object only: no markdown, no explanation." else "", prompt, maxTokens), "STOP") }
        catch (e: Exception) {
            if (e is ExtLlm.LlmErr) ExtTranslate.noteFail("llm_" + p.id, ExtLlm.key(p).hashCode(), e)
            host.log("⚠ ${p.label} (مهمة جانبية) فشل: " + (e.message ?: e.javaClass.simpleName).take(100)); null
        }
    }

    /** (v285) طلب نص للمهام الجانبية: جيميناي الأول، ولو مفيش مفتاح أو فشل ← محرك LLM المختار. البحث بجوجل (search) جيميناي بس */
    private fun bgCall(prompt: String, maxTokens: Int, temp: Double, json: Boolean = true, i: Int = 0, search: Boolean = false): Api.Result {
        if (!search && helperKey(i) == null) textViaLlm(prompt, maxTokens, json)?.let { return it }
        try { return bgGemini(prompt, maxTokens, temp, json, i, search) }
        catch (e: Exception) {
            if (!search && !keyless) textViaLlm(prompt, maxTokens, json)?.let { host.log("↪ جيميناي وقف في مهمة جانبية — اتكمّلت بـ ${convLlm()?.label}"); return it }
            throw e
        }
    }

    private fun bgGemini(prompt: String, maxTokens: Int, temp: Double, json: Boolean = true, i: Int = 0, search: Boolean = false): Api.Result {
        var key = helperKey(i) ?: throw Exception("مفيش مفتاح")
        var tries = 0
        while (true) {
            try { return Api.generate(curModel(), key, prompt, null, maxTokens, temp, json, search) }
            catch (e: ApiErr) {
                if (e.code >= 500) { if (++tries > 2) throw e; nap(2000L * tries); helperKey(i + tries)?.let { key = it }; continue }   // (v150) 503 مؤقت: جرّب تاني على مفتاح تاني
                if (e.code == 429) { noteQuota(); pool.block(key, 60_000) } else if (e.code == 403) pool.block(key, 3_600_000) else throw e
                val alt = helperKey(i + 1)
                if (alt == null || alt == key || ++tries > 2) throw e
                key = alt
            }
        }
    }

    // --- تحليل الشخصيات تلقائيًا (_maybeAutoAnalyzeCharacters / analyzeCharacters) ---
    private fun maybeAnalyze() {
        if (sideOff()) return
        if (!conf.autoChars || conf.manualChars.isNotEmpty()) return
        maybeLookup()
        maybeIdentify()
        synchronized(lock) {
            if (charsBusy || lookupState == 1 || subs.size < CHAR_MIN_LINES || !sideOk()) return
            // أول تحليل: لما مفيش شخصيات. بعد كده: تحديث كل 5 باتشات (لو الترجمة زادت 20 جملة على الأقل)
            val first = chars.isEmpty() && autoCharsAttempts == 0
            val refresh = chunksDone - lastCharChunk >= 5 && subs.size >= charsTrySize + 20
            val retry = chars.isEmpty() && autoCharsAttempts in 1..1 && subs.size >= charsTrySize + 30
            if (!first && !refresh && !retry) return
            charsBusy = true; charsTrySize = subs.size; lastCharChunk = chunksDone
        }
        try {
            bg.submit {
                try { analyzeChars() } catch (e: Exception) { host.log("⚠ تحليل الشخصيات فشل: " + (e.message ?: "").take(100)) }
                finally { synchronized(lock) { charsBusy = false; autoCharsAttempts++ }; persist() }
            }
        } catch (_: Exception) { synchronized(lock) { charsBusy = false } }
    }

    /** (v107) بحث تلقائي عن شخصيات الفيلم/المسلسل بالاسم الكامل (بحث جوجل عبر Gemini). لو الاسم مش واضح أو مش متأكد: مفيش تخمين والشخصيات بتتطلع من الترجمة. */
    private fun maybeLookup() {
        val hint = titleHint.trim()
        synchronized(lock) {
            if (lookupState != 0 || hint.length < 3 || chars.isNotEmpty() || !sideOk()) return
            lookupState = 1
        }
        try {
            bg.submit {
                try { lookupCast(hint) } catch (e: Exception) { host.log("⚠ البحث عن الشخصيات فشل: " + (e.message ?: "").take(100)) }
                finally { synchronized(lock) { lookupState = 2 }; persist() }
            }
        } catch (_: Exception) { synchronized(lock) { lookupState = 2 } }
    }

    private fun lookupCast(hint: String) {
        host.log("🔎 بدور على الشخصيات باسم: $hint")
        val prompt = "اسم ملف الفيديو واسم الفولدر بتاعه: «$hint».\n" +
            "مهمتك: تحدد لو ده اسم فيلم أو مسلسل أو أنمي كامل وواضح، وتدوّر عليه في جوجل وتجيب شخصياته الرئيسية.\n" +
            "🔴 ممنوع التخمين: لو الاسم ناقص أو عام أو فيه رقم حلقة بس أو لقيت أكتر من عمل بنفس الاسم ومش قادر تحدد بثقة، أو ملقيتش مصدر موثوق، رجّع found=false وخلاص.\n" +
            "لو لقيته بثقة: رجّع الشخصيات الرئيسية والمهمة (حد أقصى 15) بأسمائها المعروفة مكتوبة بالعربي، وجنس كل شخصية، ودورها في سطر قصير (حد أقصى 12 كلمة) من غير حرق أحداث.\n" +
            "رد JSON فقط بدون أي شرح أو markdown:\n" +
            "{\"found\":true,\"title\":\"الاسم الكامل للعمل\",\"characters\":[{\"name\":\"...\",\"gender\":\"male\",\"role\":\"...\"}]}\n" +
            "أو {\"found\":false}"
        val r = bgCall(prompt, 2048, 0.1, true, 0, true)
        val j = Parse.json(r.text)
        if (j == null || !j.optBoolean("found", false)) { host.log("🔎 الاسم مش واضح أو مش متأكد — هطلّع الشخصيات من الترجمة نفسها"); return }
        val n = applyCast(j)
        host.log("🔎 لقيت العمل «" + j.optString("title") + "» — اتضافت $n شخصية هتتحط في الـ prompt من المقطع الجاي")
    }

    /** يضيف شخصيات العمل اللي اتلقى (من البحث) لجدول الشخصيات. يرجع عدد الجديد. */
    private fun applyCast(j: JSONObject): Int {
        var n = 0
        synchronized(lock) {
            val ca = j.optJSONArray("characters")
            for (k in 0 until (ca?.length() ?: 0)) {
                val o = ca!!.optJSONObject(k) ?: continue
                val name = o.optString("name").trim(); if (name.isEmpty() || chars.any { it.name == name }) continue
                val g = if (o.optString("gender").lowercase().startsWith("f")) "female" else "male"
                chars.add(Chr(name, g, o.optString("role").trim())); n++
            }
            castFound = true
        }
        return n
    }

    /**
     * (v170) مفيش اسم ملف مفيد (أو البحث بالاسم ملقاش حاجة): نتعرّف على العمل من الحوار نفسه (أسماء الشخصيات والأحداث) ونطابقه بجوجل.
     * لو لقينا عمل بثقة عالية: شخصياته الرئيسية بتتضاف جاهزة بجنسها ودورها، ومن المقطع الجاي جيميناي بيربط الأصوات بيها.
     */
    private fun maybeIdentify() {
        synchronized(lock) {
            if (identTried || castFound || subs.size < 30 || !sideOk()) return
            val pending = titleHint.trim().length >= 3 && lookupState != 2   // لسه بنجرّب الاسم الأول
            if (pending) return
            identTried = true
        }
        try {
            bg.submit { try { identifyFromDialogue() } catch (e: Exception) { host.log("⚠ التعرف على العمل من الحوار فشل: " + (e.message ?: "").take(100)) } }
        } catch (_: Exception) {}
    }

    private fun identifyFromDialogue() {
        val snap = subs.filter { !it.isSound && it.original.isNotBlank() }
        if (snap.size < 20) return
        val step = maxOf(1, snap.size / 45)
        val lines = snap.filterIndexed { i, _ -> i % step == 0 }.take(45).joinToString("\n") { s ->
            "[" + (if (s.gender == "female") "أنثى" else "ذكر") + "] " + s.original.take(140) + (if (s.translated.isNotBlank()) "  ←  " + s.translated.take(140) else "")
        }
        val names = snap.flatMap { it.people }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25).joinToString("، ") { it.key }
        val known = synchronized(lock) { chars.map { it.name } }.joinToString("، ")
        val hint = titleHint.trim()
        host.log("🔎 مفيش اسم عمل واضح — بحاول أتعرّف عليه من الحوار نفسه (${snap.size} جملة)…")
        val prompt = "ده حوار من فيديو (فيلم أو مسلسل أو أنمي؟) اتسمع واتترجم. لغة الصوت: ${srcLang.ifEmpty { "غير محددة" }}. اسم الملف/الفولدر (ممكن يكون ناقص أو عام): «${hint.ifEmpty { "مفيش" }}».\n" +
            "أسماء ظهرت في الحوار: ${names.ifEmpty { "مفيش" }}\n" + (if (known.isNotEmpty()) "شخصيات معروفة عندنا: $known\n" else "") +
            "\nمقتطفات من الحوار (متكلم + الأصل + الترجمة):\n$lines\n\n" +
            "مهمتك: تتعرّف على العمل ده، وتدوّر عليه في جوجل وتتأكد إنه عمل حقيقي وإن أسماء الشخصيات والأحداث دي بتاعته فعلًا.\n" +
            "🔴 ممنوع التخمين: لو مش متأكد بثقة عالية (أسماء شخصيات وأحداث متطابقة مع مصدر موثوق)، أو لقيت أكتر من عمل محتمل، أو ده شكله فيديو يوتيوب/مقطع شخصي/برنامج/بودكاست — رجّع found=false وخلاص.\n" +
            "لو لقيته بثقة: رجّع الشخصيات الرئيسية والمهمة (حد أقصى 15) بأسمائها العربية بنفس كتابتها في الترجمة (ولو الشخصية في «شخصيات معروفة عندنا» استخدم نفس الكتابة بالظبط)، وجنس كل شخصية، ودورها في سطر قصير (حد أقصى 12 كلمة) من غير حرق أحداث.\n" +
            "رد JSON فقط بدون أي شرح أو markdown:\n{\"found\":true,\"title\":\"الاسم الكامل للعمل\",\"characters\":[{\"name\":\"...\",\"gender\":\"male\",\"role\":\"...\"}]}\nأو {\"found\":false}"
        val r = bgCall(prompt, 2048, 0.1, true, 0, true)
        val j = Parse.json(r.text)
        if (j == null || !j.optBoolean("found", false)) { host.log("🔎 مقدرتش أتأكد من اسم العمل — هكمّل بالشخصيات اللي بتتطلّع من الترجمة والأصوات"); return }
        val n = applyCast(j)
        host.log("🔎 اتعرّفت على العمل من الحوار: «" + j.optString("title") + "» — اتضافت $n شخصية جاهزة")
        persist()
    }


    private fun analyzeChars() {
        val snap = subs
        val stride = if (snap.size > 400) Math.ceil(snap.size / 400.0).toInt() else 1
        val sample = snap.filterIndexed { i, _ -> i % stride == 0 }
        val dialogue = sample.joinToString("\n") { s ->
            val g = if (s.gender == "female") "أنثى" else "ذكر"
            val names = if (s.people.isNotEmpty()) " [أسماء مذكورة: ${s.people.joinToString("، ")}]" else ""
            "[متكلم:$g]$names ${s.translated.ifEmpty { s.original }}"
        }
        val known = synchronized(lock) { chars.map { it.name } }
        val dialogueK = if (known.isEmpty()) dialogue else "(شخصيات معروفة مسبقًا — لو نفس الشخصية اتذكرت استخدم نفس الاسم بالظبط ومتكررهاش: " + known.joinToString("، ") + ")\n" + dialogue
        host.log("🧑‍🤝‍🧑 بحلل الشخصيات من ${sample.size} جملة…")
        val r = bgCall(pb.read("prompts/analyze_chars.txt").replace("§DIALOGUE§", dialogueK), 3072, 0.2)
        val j = Parse.json(r.text) ?: throw Exception("رد غير صالح")
        var nc = 0; var ng = 0
        synchronized(lock) {
            val ca = j.optJSONArray("characters")
            for (k in 0 until (ca?.length() ?: 0)) {
                val o = ca!!.optJSONObject(k) ?: continue
                val name = o.optString("name").trim(); if (name.isEmpty()) continue
                val g = if (o.optString("gender").lowercase().startsWith("f")) "female" else "male"
                val role = o.optString("role").trim()
                val ix = chars.indexOfFirst { it.name == name }
                if (ix >= 0) chars[ix] = Chr(name, g, role.ifEmpty { chars[ix].role }) else chars.add(Chr(name, g, role))
                nc++
            }
            val ga = j.optJSONArray("glossary")
            for (k in 0 until (ga?.length() ?: 0)) {
                val o = ga!!.optJSONObject(k) ?: continue
                val term = (o.optString("term").ifEmpty { o.optString("name") }).trim(); if (term.isEmpty()) continue
                val note = (o.optString("note").ifEmpty { o.optString("translation") }).trim()
                if (gloss.none { it.term == term }) { gloss.add(Gloss(term, note)); ng++ }
            }
        }
        host.log("🧑‍🤝‍🧑 اتحددت $nc شخصية و$ng مصطلح — هتتحط في الـ prompt من المقطع الجاي")
    }

    // --- تصحيح الضمائر تلقائيًا (_maybeAutoCorrectPronouns) ---
    private fun maybePronouns() {
        if (sideOff() || !conf.autoPronouns) return
        if (effectiveChars().isEmpty()) return
        synchronized(lock) {
            if (pronBusy || subs.size < PRON_MIN_LINES || pronUpTo >= subs.size) return
            pronBusy = true
        }
        try {
            bg.submit {
                try { pronounPass() } catch (e: Exception) { host.log("⚠ تصحيح الضمائر فشل: " + (e.message ?: "").take(100)) }
                finally { synchronized(lock) { pronBusy = false } }
            }
        } catch (_: Exception) { synchronized(lock) { pronBusy = false } }
    }

    private fun pronounPass() {
        val cs = effectiveChars(); if (cs.isEmpty()) return
        val snap = subs
        val cand = (pronUpTo until snap.size).toList()
        var fixed = 0
        for (batch in cand.chunked(40)) {
            if (!running) return
            fixed += runPronounCorrection(snap, batch, cs)
        }
        if (fixed > 0) { host.log("🩺 تصحيح ضمائر: $fixed جملة"); host.changed(); persist() }
        pronUpTo = maxOf(pronUpTo, snap.size)
    }

    private fun runPronounCorrection(snap: List<Sub>, batch: List<Int>, cs: List<Chr>): Int {
        val want = LinkedHashSet<Int>()
        for (i in batch) for (k in i - 2..i + 2) if (k in snap.indices) want.add(k)
        val list = JSONArray()
        for (k in want) {
            val s = snap[k]
            list.put(JSONObject().put("idx", k).put("review", k in batch).put("original", s.original).put("translated", s.translated).put("speaker_gender", s.gender).put("addressee_gender", s.addressee))
        }
        val table = cs.joinToString("\n") { "- \"${it.name}\": ${if (it.gender == "female") "أنثى (مؤنث)" else "ذكر (مذكر)"}" }
        val prompt = pb.read("prompts/pronoun_fix.txt").replace("§TABLE§", table).replace("§LIST§", list.toString())
        val r = bgCall(prompt, 8192, 0.1)
        val j = Parse.json(r.text) ?: return 0
        val arr = j.optJSONArray("corrections") ?: return 0
        var n = 0
        synchronized(lock) {
            val cur = subs.toMutableList()
            for (q in 0 until arr.length()) {
                val c = arr.optJSONObject(q) ?: continue
                if (!c.has("idx")) continue
                val idx = c.optInt("idx", -1)
                if (idx !in batch) continue
                val tr = c.optString("translated").trim(); if (tr.isEmpty()) continue
                val target = snap[idx]
                val at = cur.indexOfFirst { Math.abs(it.start - target.start) < 0.05 && it.original == target.original }
                if (at >= 0 && cur[at].translated != tr) { cur[at] = cur[at].copy(translated = tr); n++ }
            }
            if (n > 0) subs = cur
        }
        return n
    }

    // ===== (v109) تنقيح الترجمة بالسياق — (v140) اتحسّن: بيشتغل كل مقطعين بعدّاد مش بباقي القسمة (كان بيفوّت لما مقطعين يخلصوا مع بعض)،
    // وبيعمل تنقيح نهائي لما كل المقاطع تخلص (كان آخر مقاطع الفيديو عمرها ما بتتراجع)، وبيراجع الجمل الجديدة بس مع 25 جملة قبلها كسياق (كان بيبعت الفيديو كله كل مرة) =====
    private val refineEvery = 1   // (v154) كل مقطع يخلص (التنقيح الجزئي على مفتاح مخصوص مش بيزاحم الترجمة)
    @Volatile private var partialWarned = false
    @Volatile private var partialKeyTail = ""
    @Volatile private var refineBusy = false
    private var refineSaved = false
    private var refinedAt = 0
    private val refinedKeys = HashSet<String>()   // جمل اتراجعت وفي بعدها سياق كفاية (بتتحمي بـ lock)

    /** (v154) مفاتيح التنقيح الجزئي: الاحتياطي (وأي مفتاح إضافي اتحط على «احتياطي») الأول، وبعدين مفاتيح الصور — بس لو الوضع البصري مش شغّال دلوقتي. مفاتيح الترجمة الأساسية ماتتلمسش أبدًا. */
    private fun partialKeys(): List<String> {
        val l = ArrayList<String>()
        l.addAll(conf.refine.filter { it.length > 10 && pool.ok(it) })   // (v158) مفاتيح التنقيح المخصوصة الأول
        // (v238) الاحتياطي بقى بيساعد في التنقيح الجزئي حتى لو فيه مفاتيح تنقيح (كان بيقعد فاضي لحد ما باتش يفشل)
        l.addAll(conf.backup.filter { it.length > 10 && pool.ok(it) })
        if (!VisualMode.anyActive()) l.addAll(conf.visKeys.filter { it.length > 10 && pool.ok(it) })
        return l.distinct()
    }

    /** طلب تنقيح جزئي على المفاتيح المخصوصة بس (429 → يعطّل المفتاح ويجرّب اللي بعده، 5xx → يعيد) */
    private fun partialCall(prompt: String, maxTokens: Int, temp: Double, i: Int): Api.Result {
        var last: Exception? = null
        for (tries in 0 until 3) {
            val ks = partialKeys()
            if (ks.isEmpty()) throw last ?: Exception("مفيش مفتاح احتياطي/صور فاضي للتنقيح الجزئي")
            val key = ks.sortedBy { pool.loadOf(it) * 100 + Math.floorMod(ks.indexOf(it) - (i + tries), ks.size) }.first()
            try {
                partialKeyTail = pool.tail(key)
                pool.refineBegin(key)   // (v160) المفتاح يتحجز: مفيش باتش ترجمة جديد يتبعت عليه، والباتش الشغّال يخلص الأول
                var w = 0; while (pool.loadOf(key) > 0 && w < 90_000 && running) { nap(300); w += 300 }
                return Api.generate(curModel(), key, prompt, null, maxTokens, temp, true, false) }
            catch (e: ApiErr) {
                last = e
                if (e.code >= 500) nap(2000L * (tries + 1))
                else if (e.code == 429) pool.block(key, 60_000)
                else if (e.code == 401 || e.code == 403) pool.block(key, 3_600_000)
                else throw e
            } finally { pool.refineEnd(key) }
        }
        throw last ?: Exception("فشل التنقيح الجزئي")
    }

    private fun maybeRefine() {
        if (keyless) return
        if (!conf.partialRefine) return
        val keysOk = partialKeys().isNotEmpty()
        if (!keysOk) {
            if (!partialWarned && chunksDone >= 2) { partialWarned = true; host.log("✍ التنقيح الجزئي واقف: محتاج 3 مفاتيح على الأقل عشان البرنامج يخصّص مفتاح للتنقيح — هيتنقّح في الآخر بالمفاتيح العادية") }
            return
        }
        synchronized(lock) {
            if (refineBusy || chunksDone - refinedAt < refineEvery || subs.size < 12) return
            refineBusy = true; refinedAt = chunksDone
        }
        runRefine(false)
    }

    private var finalAt = -1   // (v154) آخر chunksDone اتعمل عنده تنقيح نهائي (مرة واحدة لكل قيمة — مايتعادش لو فشل)
    /** من حلقة run() وقت الهدوء (مفيش باتش في الجو): لو في مقاطع خلصت بعد آخر تنقيح، نقّح الباقي. بيرجّع true لو التنقيح شغال — الحلقة تستنى */
    private fun finalRefineTick(): Boolean {
        if (isClosed || transOnly) return false
        synchronized(lock) {
            if (refineBusy) return true
            if (subs.size < 8 || finalAt == chunksDone) return false
            // (v154) التنقيح الجزئي بيعلّم refinedAt مع كل مقطع، فلازم نشوف كمان لو لسه فيه جمل (آخر 8) ماخدتش سياق بعدها
            val pending = subs.any { !it.isSound && it.original.isNotBlank() && it.translated.isNotBlank() && !it.translated.startsWith("«") && pk(it) !in refinedKeys }
            if (chunksDone <= refinedAt && !pending) return false
            refineBusy = true; refinedAt = chunksDone; finalAt = chunksDone
        }
        runRefine(true)
        return true
    }

    private fun runRefine(isFinal: Boolean) {
        try {
            bg.submit {
                try { refinePass(isFinal) } catch (e: Exception) { warnUser("refine", "⚠ تنقيح الترجمة فشل — الترجمة سليمة بس من غير تنقيح"); host.log("⚠ تنقيح الترجمة فشل: " + (e.message ?: "").take(100)) }
                finally { refineBusy = false }
            }
        } catch (_: Exception) { refineBusy = false }
    }

    private fun refinePass(isFinal: Boolean) {
        val snap = subs.filter { !it.isSound && it.original.isNotBlank() && it.translated.isNotBlank() && !it.translated.startsWith("«") }.sortedBy { it.start }
        if (snap.size < 8) return
        val reviewed = synchronized(lock) { HashSet(refinedKeys) }
        val firstNew = snap.indexOfFirst { pk(it) !in reviewed }
        if (firstNew < 0) return
        val roster = pb.rosterText(effectiveChars(), effectiveGloss())
        val WIN = 400; val STEP = 360; val LEAD = 25
        var fixedTotal = 0; var from = maxOf(0, firstNew - LEAD); var win = 0
        var okAll = true
        while (from < snap.size && running) {
            val part = snap.subList(from, minOf(snap.size, from + WIN))
            val arr = JSONArray()
            for ((k, q) in part.withIndex()) {
                val prevEnd = if (k > 0) part[k - 1].end else if (from > 0) snap[from - 1].end else q.start
                val o = JSONObject().put("i", k).put("original", q.original).put("translated", q.translated)
                if (q.pivot.isNotBlank()) o.put("en", q.pivot)
                o.put("speaker", q.gender)
                if (q.addressee != "unknown") o.put("to", q.addressee)
                o.put("gap", Math.round(maxOf(0.0, q.start - prevEnd) * 10) / 10.0)
                arr.put(o)
            }
            val prompt = PromptStore.fill(PromptStore.pick("eng:refine", EnginePrompts.REFINE), "ROSTER" to (if (roster.isNotBlank()) "\nمعلومات الشخصيات والمصطلحات:\n$roster\n" else ""), "ARR" to arr.toString(), "LANG" to conf.lang, "STYLE" to conf.style)
            refiningSegs = listOf(doubleArrayOf(part.first().start, part.last().end))
            val r = try { if (isFinal) bgCall(prompt, 8192, 0.1, true, win) else partialCall(prompt, 8192, 0.1, win) } catch (e: Exception) { okAll = false; refiningSegs = emptyList(); throw e }
            refiningSegs = emptyList()
            val ca = Parse.json(r.text)?.optJSONArray("corrections")
            if (ca != null && ca.length() > 0) {
                var n = 0
                synchronized(lock) {
                    val cur = subs.toMutableList()
                    if (!refineSaved) { refineSaved = true; versions.add(Version("قبل التنقيح", subs, convDialect.ifBlank { "فصحى" })); while (versions.size > 12) versions.removeAt(0) }
                    for (q in 0 until ca.length()) {
                        val c = ca.optJSONObject(q) ?: continue
                        val k = c.optInt("i", -1); if (k < 0 || k >= part.size) continue
                        val nt = c.optString("translated").trim(); if (nt.isEmpty()) continue
                        val t = part[k]
                        if (nt == t.translated || nt.length > t.translated.length * 3 + 20 || nt.length * 3 + 20 < t.translated.length) continue
                        val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                        if (at < 0 || cur[at].translated != t.translated) continue   // اتغيّرت في الأثناء (تعديل تاني): ماندوسش عليها
                        cur[at] = cur[at].copy(translated = nt); n++
                    }
                    if (n > 0) subs = cur
                }
                fixedTotal += n
            }
            win++
            if (from + WIN >= snap.size) break
            from += STEP; nap(2000)
        }
        // علّم الجمل اللي اتراجعت — وآخر 8 جمل مش بتتعلّم إلا في التنقيح النهائي (لسه ماعندهاش سياق بعدها)
        if (okAll && running) synchronized(lock) {
            val upto = if (isFinal) snap.size else maxOf(0, snap.size - 8)
            for (k in 0 until upto) refinedKeys.add(pk(snap[k]))
        }
        host.log((if (isFinal) "✍ تنقيح الترجمة بالسياق" else "✍ تنقيح جزئي (مفتاح $partialKeyTail)") + ": راجعت ${snap.size - maxOf(0, firstNew - LEAD)} جملة وصحّحت $fixedTotal")
        if (fixedTotal > 0) { host.changed(); persist() }
    }

    // --- ترجمة قالب الـ prompt للغات اللي ملهاش قالب جاهز (_translatePromptTemplate) ---
    // ===== أدوات يدوية من المشغّل (أزرار الشاشة الكاملة في نسخة الـ HTML) =====
    class Version(val name: String, val subs: List<Sub>, val dialect: String = "")
    val versions = java.util.concurrent.CopyOnWriteArrayList<Version>()
    fun saveVersion(name: String, dialect: String = convDialect.ifBlank { "فصحى" }) { versions.add(Version(name, subs, dialect)); while (versions.size > 12) versions.removeAt(0) }
    /** يرجّع نص الترجمة (translated) بس من نسخة محفوظة على الجمل المطابقة (نفس البداية والأصل)، والجمل الجديدة بتفضل زي ما هي */
    fun applyVersion(i: Int): Int {
        val v = versions.getOrNull(i) ?: return 0
        var n = 0
        synchronized(lock) {
            val cur = subs.toMutableList()
            for (k in cur.indices) {
                val m = v.subs.firstOrNull { Math.abs(it.start - cur[k].start) < 0.05 && it.original == cur[k].original } ?: continue
                if (m.translated != cur[k].translated) { cur[k] = cur[k].copy(translated = m.translated); n++ }
            }
            subs = cur
        }
        host.changed(); persist(); return n
    }

    @Volatile var toolBusy: String? = null
        private set

    /** 🔧 تصحيح الضمائر لكل الجمل (من جدول الشخصيات + جنس الصوت) */
    fun correctPronounsNow(report: (String) -> Unit, done: (Int) -> Unit) {
        if (toolBusy != null) { report("في عملية شغالة: " + toolBusy); return }
        val cs = effectiveChars()
        if (cs.isEmpty()) { report("مفيش شخصيات متحللة لسه — شغّل الترجمة لحد ما تتحلل الأول"); done(0); return }
        toolBusy = "تصحيح الضمائر"
        Thread {
            var fixed = 0
            val mine = HashSet<String>()
            try {
                saveVersion("قبل تصحيح الضمائر")
                val snap = subs
                val all = snap.indices.toList()
                for (k in all) mine.add(pk(snap[k])); pend.addAll(mine)
                val batches = all.chunked(40)
                batches.forEachIndexed { bi, batch ->
                    if (!running) return@forEachIndexed
                    report("🔧 ضمائر ${bi + 1}/${batches.size}")
                    fixed += try { runPronounCorrection(snap, batch, cs) } catch (e: Exception) { host.log("⚠ ضمائر: " + (e.message ?: "").take(80)); 0 }
                    for (k in batch) pend.remove(pk(snap[k]))
                }
                if (fixed > 0) { host.changed(); persist() }
            } finally { pend.removeAll(mine); toolBusy = null }
            done(fixed)
        }.start()
    }

    /** إعادة صياغة الترجمة الحالية بتعليمة (لهجة أقوى / عائلي / صريح / لهجة لايف) من نقطة معينة لآخر الفيديو */
    fun rewriteAll(label: String, instruction: String, fromSec: Double, report: (String) -> Unit, done: (Int) -> Unit) {
        if (toolBusy != null) { report("في عملية شغالة: " + toolBusy); return }
        toolBusy = label
        Thread {
            var changed = 0
            val mine = HashSet<String>()
            try {
                saveVersion("قبل $label")
                val snap = subs
                val idx = snap.indices.filter { snap[it].end >= fromSec }
                for (k in idx) mine.add(pk(snap[k])); pend.addAll(mine)
                val table = effectiveChars().joinToString("\n") { "- \"${it.name}\": ${if (it.gender == "female") "أنثى" else "ذكر"}" }.ifEmpty { "(مفيش)" }
                val batches = idx.chunked(40)
                batches.forEachIndexed { bi, batch ->
                    if (!running) return@forEachIndexed
                    report("$label ${bi + 1}/${batches.size}")
                    val list = JSONArray()
                    for (k in batch) { val s = snap[k]; list.put(JSONObject().put("idx", k).put("original", s.original).put("translated", s.translated).put("speaker_gender", s.gender).put("addressee_gender", s.addressee)) }
                    val prompt = "أنت محرر ترجمة محترف. اللغة/اللهجة الحالية: ${conf.lang} — الأسلوب: ${conf.style}.\n" +
                        "المطلوب بالظبط: $instruction\n" +
                        "قواعد ثابتة: حافظ على المعنى وعلى عدد الجمل وترتيبها (ماتدمجش ولا تقسّم). ماتقلبش ذكر لأنثى ولا العكس: جنس المتكلم = speaker_gender، وجنس المخاطَب = addressee_gender، وجنس الشخصيات المسماة من الجدول:\n$table\n" +
                        "ماتلمسش الجملة لو مش محتاجة تغيير.\n\nالجمل:\n$list\n\n" +
                        "أرجع JSON فقط بالجمل اللي اتغيّرت: {\"rewrites\":[{\"idx\":0,\"translated\":\"النص الجديد كامل\"}]}"
                    try {
                        val r = bgCall(prompt, 8192, 0.4, true, bi)
                        val arr = (Parse.json(r.text) ?: return@forEachIndexed).optJSONArray("rewrites") ?: return@forEachIndexed
                        synchronized(lock) {
                            val cur = subs.toMutableList()
                            for (q in 0 until arr.length()) {
                                val c = arr.optJSONObject(q) ?: continue
                                val ix = c.optInt("idx", -1); if (ix !in batch) continue
                                val tr = c.optString("translated").trim(); if (tr.isEmpty()) continue
                                val t = snap[ix]
                                val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                                if (at >= 0 && cur[at].translated != tr) { cur[at] = cur[at].copy(translated = tr); changed++ }
                            }
                            subs = cur
                        }
                        host.changed()
                    } catch (e: Exception) { host.log("⚠ $label: " + (e.message ?: "").take(80)) }
                    finally { for (k in batch) pend.remove(pk(snap[k])) }
                }
                if (changed > 0) persist()
            } finally { pend.removeAll(mine); toolBusy = null }
            done(changed)
        }.start()
    }

    /**
     * (v151) ✍ تنقيح كامل بطلب واحد: الأصل المستخرج + الترجمة الحالية لكل الجمل المترجمة بتتبعت مرة واحدة (مش باتش باتش)
     * للمفتاح الاحتياطي، ولو مفيش فلأي مفتاح أساسي فاضي. الموديل بيصلّح الترجمة بالسياق (والكلمات الحرفية اللي معناها
     * بيتغيّر مع المشهد) وبيرجّع الجمل اللي اتغيّرت بس، وإحنا بنطبّقها مكانها. فيه نسخة محفوظة قبل التنقيح للرجوع.
     */
    fun refineWholeNow(report: (String) -> Unit, done: (Int) -> Unit) {
        if (toolBusy != null) { report("في عملية شغالة: " + toolBusy); return }
        toolBusy = "تنقيح بالسياق"
        Thread {
            var fixed = 0
            try {
                val snap = subs.filter { !it.isSound && it.original.isNotBlank() && it.translated.isNotBlank() && !it.translated.startsWith("«") }.sortedBy { it.start }
                if (snap.size < 3) report("مفيش جمل مترجمة كفاية للتنقيح")
                else {
                    saveVersion("قبل التنقيح بالسياق"); refiningSegs = listOf(doubleArrayOf(snap.first().start, snap.last().end))
                    val roster = pb.rosterText(effectiveChars(), effectiveGloss())
                    val parts = snap.chunked(3000)
                    for ((pi, part) in parts.withIndex()) {
                        report("✍ تنقيح ${pi + 1}/${parts.size} — ${part.size} جملة في طلب واحد")
                        val arr = JSONArray()
                        for ((k, q) in part.withIndex()) arr.put(JSONArray().put(k).put(q.original).put(q.translated).put(q.gender))
                        val prompt = "أنت مراجع ترجمة محترف. دي كل جمل فيلم/مسلسل/فيديو بالترتيب. كل عنصر: [رقم، الأصل، الترجمة الحالية، جنس المتكلم]. الترجمة الحالية اتعملت على مقاطع صوتية منفصلة من غير ما كل مقطع يشوف باقي الفيديو، فغالبًا فيها جمل حرفية أو غلط في السياق.\n" +
                            (if (roster.isNotBlank()) "\nمعلومات الشخصيات والمصطلحات:\n$roster\n" else "") +
                            "\nالجمل:\n$arr\n\n" +
                            "اقرا الفيديو كله كأنك بتتفرج عليه، وصحّح الترجمة (النقاط دي بس):\n" +
                            "1) كلمة أو تعبير اتترجم حرفي ومعناه في المشهد مختلف (اصطلاح، سخرية، تلميح، مجاز، رد على جملة قبلها).\n" +
                            "2) ضمير/فاعل/مفعول أو جنس غلط، أو اسم ومصطلح متترجم بأكتر من شكل.\n" +
                            "3) جملة مقطوعة بين مقطعين وترجمتها مش متصلة بجارتها، أو ترجمة بتتناقض مع اللي حواليها.\n" +
                            "القواعد: حافظ على لهجة ${conf.lang} وأسلوب ${conf.style}؛ الجملة قصيرة قد الأصلية تقريبًا؛ ماتغيّرش جملة سليمة ولا تحسّن أسلوب بس؛ ممنوع تضيف أو تحذف أو تدمج جمل.\n" +
                            "رجّع JSON فقط بالجمل اللي اتغيّرت: {\"corrections\":[{\"i\":12,\"translated\":\"النص المصحح الكامل\"}]} — لو كله سليم: {\"corrections\":[]}"
                        var key: String = (conf.refine + conf.backup).firstOrNull { pool.ok(it) } ?: pool.pickIdle(-1000, null, perKey) ?: helperKey() ?: throw Exception("مفيش مفتاح")
                        pool.unreserve(-1000)
                        var r: Api.Result? = null; var tries = 0
                        while (r == null) {
                            try { r = Api.generate(curModel(), key, prompt, null, 30000, 0.1, true, false) }
                            catch (e: Exception) {
                                val code = (e as? ApiErr)?.code ?: 0
                                if (++tries > 3 || code == 400 || e is Unsupported) throw e
                                if (code == 429) pool.block(key, 60_000) else if (code == 401 || code == 403) pool.block(key, 3_600_000)
                                nap(2000L * tries)
                                key = pool.pickIdle(-1000, key, perKey)?.also { pool.unreserve(-1000) } ?: helperKey(tries) ?: key
                            }
                        }
                        val ca = Parse.json(r!!.text)?.optJSONArray("corrections")
                        if (ca == null) { report("رد التنقيح مش صالح (ممكن اتقطع) — جرّب تاني"); continue }
                        var n = 0
                        synchronized(lock) {
                            val cur = subs.toMutableList()
                            for (q in 0 until ca.length()) {
                                val c = ca.optJSONObject(q) ?: continue
                                val k = c.optInt("i", -1); if (k < 0 || k >= part.size) continue
                                val nt = c.optString("translated").trim(); if (nt.isEmpty()) continue
                                val t = part[k]
                                if (nt == t.translated || nt.length > t.translated.length * 3 + 20 || nt.length * 3 + 20 < t.translated.length) continue
                                val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                                if (at < 0 || cur[at].translated != t.translated) continue
                                cur[at] = cur[at].copy(translated = nt); n++
                            }
                            if (n > 0) subs = cur
                            for (q in part) refinedKeys.add(pk(q))
                        }
                        fixed += n
                    }
                    if (fixed > 0) { host.changed(); persist() }
                    host.log("✍ تنقيح بالسياق (طلب واحد): ${snap.size} جملة — اتصحّح $fixed")
                }
            } catch (e: Exception) {
                host.log("⚠ التنقيح بالسياق فشل: " + (e.message ?: e.toString()).take(120)); report("التنقيح فشل: " + (e.message ?: "").take(80))
            } finally { toolBusy = null; refiningSegs = emptyList() }
            done(fixed)
        }.apply { isDaemon = true }.start()
    }

    /**
     * (v153) 🌍 ترجمة جوجل (الرابط غير الرسمي translate.googleapis.com — من غير مفتاح ولا API): بتترجم النص الأصلي لكل الجمل
     * (25 جملة في الطلب) وتحطه مكان الترجمة الحالية، فيتعرض على الفيديو عادي. جوجل بيترجم لـ«العربية» بس (مش لهجة).
     * بيتحفظ نسخة «قبل ترجمة جوجل» للرجوع.
     */
    private fun gtCall(texts: List<String>, tl: String, sl: String = "auto"): List<String>? {
        val body = "client=gtx&sl=" + sl + "&tl=" + tl + "&dt=t&q=" + java.net.URLEncoder.encode(texts.joinToString("\n"), "UTF-8")
        var last: Exception? = null
        for (t in 1..4) {
            try {
                val c = java.net.URL("https://translate.googleapis.com/translate_a/single").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 25000
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code == 429 || code >= 500) { c.disconnect(); nap(3000L * t); continue }
                if (code != 200) { c.disconnect(); throw IOException("HTTP $code") }
                val txt = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }; c.disconnect()
                NetUse.api(body.length.toLong() + 200, txt)
                val segs = JSONArray(txt).optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (k in 0 until segs.length()) sb.append(segs.optJSONArray(k)?.optString(0, "") ?: "")
                val parts = sb.toString().split("\n")
                return if (parts.size == texts.size) parts.map { it.trim() } else null
            } catch (e: Exception) { last = e; nap(1500L * t) }
        }
        if (last != null) throw last
        return null
    }

    fun googleTranslateAll(report: (String) -> Unit, done: (Int) -> Unit) {
        if (toolBusy != null) { report("في عملية شغالة: " + toolBusy); return }
        toolBusy = "ترجمة جوجل"
        Thread {
            var changed = 0
            try {
                val snap = subs.sortedBy { it.start }
                val idx = snap.indices.filter { !snap[it].isSound && snap[it].original.isNotBlank() }
                if (idx.isEmpty()) report("مفيش نص أصلي لسه للترجمة")
                else {
                    saveVersion("قبل ترجمة جوجل")
                    val out = HashMap<Int, String>()
                    val batches = idx.chunked(25)
                    var failedB = 0
                    for ((bi, b) in batches.withIndex()) {
                        if (!running && isClosed) break
                        if (bi % 8 == 0) report("🌍 جوجل ${bi + 1}/${batches.size}")
                        val src = b.map { snap[it].original.replace(Regex("\\s*\n\\s*"), " ").trim() }
                        try {
                            val r = gtCall(src, "ar")
                            if (r != null) { for ((q, k) in b.withIndex()) out[k] = r[q] }
                            else for ((q, k) in b.withIndex()) { gtCall(listOf(src[q]), "ar")?.firstOrNull()?.let { out[k] = it } }   // عدد الأسطر اختلف: جملة جملة
                        } catch (e: Exception) { failedB++; host.log("⚠ ترجمة جوجل: " + (e.message ?: e.toString()).take(80)); if (failedB >= 5) { report("جوجل واقف أو النت فاصل — وقفت"); break } }
                    }
                    synchronized(lock) {
                        val cur = subs.toMutableList()
                        for ((k, tr) in out) {
                            if (tr.isBlank()) continue
                            val t = snap[k]
                            val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                            if (at >= 0 && cur[at].translated != tr) { cur[at] = cur[at].copy(translated = tr); changed++ }
                        }
                        if (changed > 0) subs = cur
                    }
                    if (changed > 0) { host.changed(); persist() }
                    host.log("🌍 ترجمة جوجل: ${idx.size} جملة — اتغيّرت $changed")
                }
            } catch (e: Exception) {
                host.log("⚠ ترجمة جوجل فشلت: " + (e.message ?: e.toString()).take(120)); report("ترجمة جوجل فشلت: " + (e.message ?: "").take(80))
            } finally { toolBusy = null }
            done(changed)
        }.apply { isDaemon = true }.start()
    }

    /** 🧠 دمج الجمل المكررة المتداخلة زمنيًا (محلي بدون Gemini): جملتين متداخلتين ومتشابهتين جدًا = واحدة بأطول وقت */
    fun removeDuplicates(): Int {
        var removed = 0
        synchronized(lock) {
            val cur = subs.sortedBy { it.start }.toMutableList()
            fun toks(x: String) = x.replace(Regex("[\\p{P}\\s]+"), " ").trim().split(" ").filter { it.isNotEmpty() }.toSet()
            var i = 0
            while (i < cur.size - 1) {
                val a = cur[i]; val b = cur[i + 1]
                val ov = minOf(a.end, b.end) - maxOf(a.start, b.start)
                val ta = toks(a.translated); val tb = toks(b.translated)
                val inter = ta.intersect(tb).size; val uni = ta.union(tb).size
                val sim = if (uni == 0) 0.0 else inter.toDouble() / uni
                if (ov > 0.3 && a.faint == b.faint && !(a.overlap && b.overlap && a.gender != b.gender) && (sim >= 0.7 || a.translated.contains(b.translated) || b.translated.contains(a.translated))) {
                    val keep = if (b.translated.length >= a.translated.length) b else a
                    cur[i] = keep.copy(start = minOf(a.start, b.start), end = maxOf(a.end, b.end))
                    cur.removeAt(i + 1); removed++
                } else i++
            }
            val cleaned = Subs.dropEchoes(cur)
            removed += cur.size - cleaned.size
            if (removed > 0) subs = cleaned.map { Subs.collapseSelfRepeat(it) }
        }
        if (removed > 0) { saveVersion("بعد دمج المكرر"); host.changed(); persist() }
        return removed
    }

    /** 📜 ذكّرني: ملخص لكل الأحداث من أول الفيديو لحد المكان الحالي */
    fun recap(upToSec: Double, report: (String) -> Unit, done: (String) -> Unit) {
        Thread {
            try {
                val snap = subs.filter { it.start <= upToSec }
                if (snap.isEmpty()) { done("لسه مفيش ترجمة قبل المكان ده"); return@Thread }
                val stride = if (snap.size > 500) Math.ceil(snap.size / 500.0).toInt() else 1
                val text = snap.filterIndexed { i, _ -> i % stride == 0 }.joinToString("\n") { it.translated.ifEmpty { it.original } }
                report("📜 بلخّص…")
                val r = bgCall("ده حوار فيديو من أوله لحد اللحظة الحالية. لخّص بالعامية المصرية في 6 لـ 10 أسطر قصيرة أهم اللي حصل وإيه الشخصيات وإيه اللي كان بيحصل في آخر مشهد، من غير حرق للي هيجي بعد كده ومن غير مقدمات:\n\n$text", 1500, 0.4, false)
                done(r.text.trim().ifEmpty { "ملقيتش ملخص" })
            } catch (e: Exception) { done("⚠ فشل التلخيص: " + (e.message ?: "").take(100)) }
        }.start()
    }

    private fun maybeTranslateTemplate() {
        if (!conf.autoTemplate || activeMode >= KeyMode.ALL_PARALLEL) return   // (v209) الوضع 2+: البرومبت إنجليزي ثابت، مفيش قالب يتترجم
        if (pb.caseOf(srcLang, detDone) != "other") return
        val lang = srcLang
        if (lang.contains("عرب")) return
        val id = pb.templateId(conf, "other")
        val k = "$lang|$id"
        synchronized(tplCache) { if (tplCache.containsKey(k) || tplBusy.contains(k) || (tplFails[k] ?: 0) >= 2) return; tplBusy.add(k) }
        try {
            bg.submit {
                try { translateTemplate(id, lang, k) }
                catch (e: Exception) { synchronized(tplCache) { tplFails[k] = (tplFails[k] ?: 0) + 1 }; host.log("⚠ ترجمة قالب الـ prompt فشلت: " + (e.message ?: "").take(100)) }
                finally { synchronized(tplCache) { tplBusy.remove(k) } }
            }
        } catch (_: Exception) { synchronized(tplCache) { tplBusy.remove(k) } }
    }

    private fun translateTemplate(id: String, lang: String, k: String) {
        val token = "[[ROSTER_BLOCK]]"
        val text = pb.fixedPart(id).replace("§ROSTER§", token)
        val prompt = pb.read("prompts/tpl_translate.txt").replace("§LANG§", lang).replace("§TEXT§", text)
        host.log("🈯 بترجم قالب الـ prompt للغة $lang…")
        val r = bgCall(prompt, 32768, 0.0, false)
        val t = r.text.trim()
        if (r.finish != "STOP" || t.length < 200) throw Exception("الترجمة ناقصة (${r.finish})")
        val fin = if (t.contains(token)) t.replace(token, "§ROSTER§") else "$t\n§ROSTER§"
        synchronized(tplCache) { tplCache[k] = fin }
        host.log("🈯 قالب الـ prompt بقى بلغة $lang من المقطع الجاي")
        persist()
    }

    // ===== إصلاح الجمل اللي فضلت بلغتها الأصلية (بتتفحص كل الجمل بعد كل مقطع، حتى القديمة المحفوظة) =====
    private val fixTries = ConcurrentHashMap<String, Int>()
    @Volatile private var lastFix = 0L
    private fun fixKey(s: Sub) = "${s.start}|${s.original}"
    /** (v399) إصلاح جمل نصها لسه بلغتها الأصلية بجوجل (من غير مفتاح)، والتوقيت بيفضل زي ما هو لأن بنغيّر النص بس */
    private fun fixViaGoogle(list: List<Sub>): List<Sub> {
        if (list.isEmpty()) return list
        val first = list.minOf { it.start }
        val ctx = synchronized(lock) { subs.filter { it.start < first && it.original.isNotBlank() && !it.isSound }.takeLast(GT_CTX_LINES).map { it.original } }
        val g = googleItems(list.map { it.original }, ctx)
        return list.mapIndexed { k, s ->
            val t = g.getOrNull(k)?.takeIf { it.isNotBlank() }
            if (t == null) s else s.copy(translated = t).takeIf { !LangGuard.foreign(it) } ?: s
        }
    }
    private fun hasRepairable(): Boolean = !transOnly && synchronized(lock) { subs.any { it.chunk !in previewChunks && LangGuard.foreign(it) && (fixTries[fixKey(it)] ?: 0) < 3 } }
    private fun repairForeignTick(): Boolean {
        if (!running || transOnly) return false
        val now = System.currentTimeMillis()
        if (now - lastFix < 12_000) return false
        val viaG = TrWizard.googleOn() || keyless   // (v399) من غير مفتاح جيميناي (أو مسار جوجل): الإصلاح بجوجل بالسياق بدل جيميناي
        val bad = synchronized(lock) { subs.filter { it.chunk !in previewChunks && LangGuard.foreign(it) && (fixTries[fixKey(it)] ?: 0) < 3 } }.take(if (viaG) 100 else 30)
        if (bad.isEmpty()) return false
        lastFix = now
        bad.forEach { fixTries.merge(fixKey(it), 1, Int::plus) }
        host.log("🌐 بصلّح ${bad.size} جملة لسه مش بـ${conf.lang}" + (if (viaG) " (بجوجل)" else "") + "…")
        val fixed = if (viaG) fixViaGoogle(bad) else fixForeign(bad)
        val map = HashMap<String, Sub>()
        bad.indices.forEach {
            if (fixed[it].translated != bad[it].translated) {
                val wrapped = bad[it].translated.startsWith("«") && !fixed[it].translated.startsWith("«")
                map[fixKey(bad[it])] = if (wrapped) fixed[it].copy(translated = "«" + fixed[it].translated + "»") else fixed[it]
            }
        }
        if (map.isNotEmpty()) {
            synchronized(lock) { subs = subs.map { map[fixKey(it)] ?: it } }
            host.changed(); persist()
        }
        return true
    }

    // ===== (v408) 📖 نبذة عن المصطلحات الطبية/الصعبة: سطر شرح فوق الترجمة (بيتعرض زي أوصاف الأصوات isSound) =====
    // بيشتغل في مسار الترجمة من غير جيميناي (جوجل/ML Kit/خارجي) — جيميناي بيعمل الشرح ده بنفسه من البرومبت. القاموس أوفلاين، وويكيبيديا للمصطلح المجهول (بالنت).
    @Volatile private var noteAt = 0L
    private var noteSeeded = false
    private val noteShown = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val noteScanned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun noteTick(): Boolean {
        if (transOnly || !(TrWizard.googleOn() || keyless) || !TermNotes.enabled() || !running || isClosed) return false
        val now = System.currentTimeMillis()
        if (now - noteAt < 2500L) return false
        noteAt = now
        if (!noteBusy.compareAndSet(false, true)) return false
        Thread { try { noteWork() } catch (e: Exception) { host.log("⚠ النبذة: " + (e.message ?: e.javaClass.simpleName).take(80)) } finally { noteBusy.set(false) } }.apply { isDaemon = true }.start()
        return false   // في خيط لوحده (ويكيبيديا/جوجل ممكن يبطّأوا) — عشان لفة المحرك ماتستناش
    }
    private val noteBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun noteWork() {
        if (!noteSeeded) {
            noteSeeded = true
            synchronized(lock) { subs.filter { it.isSound && it.original.startsWith("📖 ") }.forEach { s -> s.original.removePrefix("📖 ").split(" · ").forEach { k -> noteShown.add(k.trim()) } } }
        }
        val snap = synchronized(lock) { subs.filter { !it.isSound && it.original.isNotBlank() && it.translated.isNotBlank() && it.chunk !in previewChunks && fixKey(it) !in noteScanned } }.take(60)
        if (snap.isEmpty()) return
        val add = ArrayList<Sub>()
        var wikiLeft = 2
        for (s in snap) {
            noteScanned.add(fixKey(s))
            val lines = ArrayList<Pair<String, String>>()   // (مفتاح المصطلح، سطر العرض)
            for (en in TermNotes.match(s.original + "\n" + s.translated).take(2)) if (noteShown.add(en.ar)) lines.add(en.ar to ("📖 " + en.ar + ": " + en.note))
            if (lines.isEmpty() && wikiLeft > 0 && TrWizard.online()) {
                for (w in TermNotes.candidates(s.original)) {
                    if (wikiLeft <= 0) break
                    if (!noteShown.add("w:" + w.lowercase())) continue
                    wikiLeft--
                    val wk = TermNotes.wiki(w) ?: continue
                    val tr = (try { gtCall(listOf(wk.first, wk.second), "ar", "en") } catch (_: Exception) { null }) ?: continue
                    if (tr.size < 2 || tr[0].isBlank() || tr[1].isBlank()) continue
                    if (!noteShown.add(wk.first)) continue
                    lines.add(wk.first to ("📖 " + tr[0] + " (" + wk.first + "): " + tr[1]))
                    break
                }
            }
            if (lines.isEmpty()) continue
            add.add(Sub(s.start, maxOf(s.end, s.start + 3.5), "📖 " + lines.joinToString(" · ") { it.first }, lines.joinToString("\n") { it.second },
                "unknown", "unknown", "none", emptyList(), emptyList(), false, false, s.chunk, isSound = true))
        }
        if (add.isEmpty()) return
        synchronized(lock) { subs = (subs + add).sortedBy { it.start } }
        host.log("📖 نبذة: اتضاف شرح لـ ${add.size} جملة فيها مصطلحات")
        host.changed(); persist()
    }

    // ===== سدّ الفجوات تلقائيًا أثناء المشاهدة (autoGapFill) =====
    /** مناطق اتترجمت بس مفيهاش جمل لمدة طويلة، قريبة من مكان التشغيل: بتتبعت بمفاتيح المراقبين/الاحتياطي وبتتعلّم بـ «» */
    private fun gapTick(all: Boolean = false): Boolean {
        if (!fillOk()) return false
        if (!conf.gapFill || !running) return false
        val off = offFill()
        val maxPar = if (off) 1 else Gaps.MAX_PARALLEL   // (v283) الأوفلاين بينسخ واحد في المرة
        val now = System.currentTimeMillis()
        if (now - lastGapScan < Gaps.COOLDOWN_MS) return false
        lastGapScan = now
        if (gapBusy.get() >= maxPar) return false
        val d = currentDur(); val pos = if (headless) d else host.position()
        val atEnd = d > 0 && pos >= d - 2
        val elig = Gaps.find(subs, done.list(), Gaps.MIN_SEC).filter {
            !gapTried.covers(it[0], it[1]) && (atEnd || all || (pos >= it[0] - Gaps.LOOKAHEAD && pos < it[1]))
        }
        if (elig.isEmpty()) { if (all || atEnd) gapScanClean = true; return false }
        var started = false
        for (g in elig) {
            if (gapBusy.get() >= maxPar || gapSession.total() >= Gaps.MAX_TOTAL * ch) break
            val key = if (off) "off" else (pool.gapKey(gapKeys, rr.getAndIncrement()) ?: break)
            gapTried.add(g[0], g[1]); gapSession.add(g[0], g[1]); gapKeys.add(key); gapBusy.incrementAndGet()
            host.log("🔁 سدّ فجوة تلقائي: ${"%.0f".format(java.util.Locale.US, g[0])}ث → ${"%.0f".format(java.util.Locale.US, g[1])}ث")
            try {
                gapEx.submit {
                    try { for (pt in Gaps.parts(g[0], g[1], minOf(ch, Coverage.PIECE_SEC))) { if (!running) break; fillGap(pt[0], pt[1], key) } }
                    catch (e: Exception) { host.log("⚠ سدّ الفجوة فشل: " + (e.message ?: "").take(100)) }
                    finally { gapKeys.remove(key); gapBusy.decrementAndGet() }
                }
                started = true
            } catch (_: RejectedExecutionException) { gapKeys.remove(key); gapBusy.decrementAndGet() }
        }
        if (all || atEnd) gapScanClean = !started
        return started
    }

    private val holeTried = ConcurrentHashMap.newKeySet<Int>()
    private val holeBusy = AtomicInteger(0)
    private val holeEx = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true; it.name = "holefill" } }
    /** سدّ الثغرات كان شغال جوه خانة الباتش (inflight) فكل باتش بيستنى طلبات الثغرات قبل ما الباتش اللي بعده يتبعت = تأخير. دلوقتي على خيط لوحده */
    private fun scheduleHoleFill(i: Int, rawStart: Double, rawEnd: Double, w: WavChunk, key: String) {
        if (!fillOk()) return   // (v283) من غير أوفلاين ومن غير جيميناي مفيش حاجة تسدّ الثغرة
        if (holeTried.contains(i)) return
        holeBusy.incrementAndGet()
        try {
            holeEx.submit {
                try { holeFill(i, rawStart, rawEnd, w, key) }
                catch (e: Exception) { host.log("⚠ سدّ الثغرات في المقطع ${i + 1} فشل: " + (e.message ?: "").take(80)) }
                finally { holeBusy.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) { holeBusy.decrementAndGet() }
    }

    /**
     * بعد ما المقطع يتطبّق: نقيس الصوت الفعلي ونشوف فيه كلام/غنا مسموع (>= HOLE_MIN ثانية) من غير ولا جملة فوقه.
     * الموديل بيرجّع جزء من المقطع وبيفوّت الباقي (خصوصًا الكورس المتكرر) — فبنعيد طلب الجزء الناقص بس
     * على أجزاء صغيرة (<= 20ث) وبسياق الجمل اللي قبله. مرة واحدة لكل مقطع.
     */
    private fun holeFill(i: Int, rawStart: Double, rawEnd: Double, w: WavChunk, key0: String) {
        val spans = Speech.activeSpans(w.bytes, w.gain)?.let { Speech.absolute(it, w.startSec) } ?: return
        if (!holeTried.add(i)) return
        val holes = Speech.holes(spans, subs, maxOf(rawStart, w.startSec), minOf(rawEnd, w.startSec + w.durSec), HOLE_MIN).take(HOLE_MAX)
        if (holes.isEmpty()) return
        host.log("🧩 المقطع ${i + 1}: ${holes.size} ثغرة فيها صوت من غير ترجمة — بعيد طلبها")
        var key = key0
        val off = offFill()
        for (h in holes) {
            // (v177) ثغرة ملزوقة في جملة اتترجمت (≤1.5ث) = غالبًا كلام ناقص مش موسيقى: بنطلبها بتعليمات «كمّل الناقص» وبنقبل الخافت
            val near = nearSpeech(h[0], h[1])
            for (pt in Gaps.parts(maxOf(0.0, h[0] - 0.5), h[1] + 0.5, 20.0)) {
                if (!running) return
                val w2 = src().wav(pt[0], pt[1]) ?: continue
                if (w2.silent) continue
                var tries = 0; var got = false
                while (running && !got && tries++ < 3) {
                    try {
                        // (v239) أسلوب الوضع 4 في كل الأوضاع: ترانسكريبشن بس من الصوت (بيسمع أكتر لأنه مش مشغول بالترجمة) ثم ترجمة نصية منفصلة. لو فشلت الترجمة: الطلب المباشر القديم
                        // (v143) الثغرة غالبًا موسيقى/مؤثرات: الرد الفاضي إجابة صحيحة، وماينفعش نعيد الطلب لحد ما الموديل "يلاقي" كلام
                        var base: List<Sub> = if (off) txFillOffline(w2, near) else txFillSubs(w2, near, key) ?: run {
                            val r = Api.generate(curModel(), key, buildPrompt(w2.durSec, w2.startSec, hole = true, holeNear = near), w2.bytes)
                            val j = if (r.text.isBlank()) null else Parse.json(r.text)
                            if (j == null) emptyList() else Subs.splitAll(Parse.subs(j, w2.startSec, w2.durSec)).filter { !it.isSound && (near || (!it.faint && !it.lowConf)) }.map { Subs.capPace(it) }
                        }
                        if (!off && base.any { LangGuard.foreign(it) }) base = fixForeign(base)
                        val sp2 = Speech.activeSpans(w2.bytes, w2.gain)?.let { Speech.absolute(it, w2.startSec) }
                        if (sp2 != null) base = base.mapNotNull { Speech.fit(it, sp2) }
                        base = base.filter { val m = (it.start + it.end) / 2; m >= h[0] - 0.5 && m <= h[1] + 0.5 }.map { it.copy(chunk = i) }
                        if (base.isEmpty()) { got = true; host.log("… ثغرة ${"%.0f".format(java.util.Locale.US, pt[0])}ث: مفيهاش كلام — اتساب زي ما هي"); continue }
                        synchronized(lock) { subs = Subs.merge(Subs.dedup(subs + base)).sortedBy { it.start } }
                        if (!off) pool.good(key); got = true
                        host.changed(); persist()
                        host.log("✅ ثغرة ${"%.0f".format(java.util.Locale.US, pt[0])}ث→${"%.0f".format(java.util.Locale.US, pt[1])}ث: اتضاف ${base.size} جملة")
                    } catch (e: ApiErr) {
                        if (e.code == 429) pool.block(key, 60_000) else if (e.code == 403) pool.block(key, 3_600_000) else { host.log("⚠ ثغرة: " + (e.message ?: "").take(80)); return }
                        key = pool.pick(key, rr.getAndIncrement()) ?: return
                    } catch (e: IOException) { host.log("📶 ثغرة: مشكلة اتصال"); return }
                }
            }
        }
    }

    /**
     * (v283) نفس مرحلتين txFillSubs بس من غير جيميناي: (1) الصوت ← نص أصلي بالأوفلاين (Whisper/SenseVoice/السحابي)،
     * (2) النص ← ترجمة بنفس سلسلة مترجم الباتشات (جوجل / خارجي)، أو من غير ترجمة في «تفريغ بس». أي فشل ← قائمة فاضية (مفيش رجوع لجيميناي).
     * Whisper على مقاطع قصيرة بيهلوس أكتر: فلتر الأوفلاين (noSpeech/logProb) + هنا بنشيل التكرار الحرفي والنصوص القصيرة جدًا.
     */
    private fun txFillOffline(w: WavChunk, near: Boolean): List<Sub> {
        val none = Api.Result("{\"subtitles\":[]}", "STOP")
        val r = Offline.transcribe(w.bytes, w.durSec, effectiveGloss().joinToString(", ") { it.term }, { host.log(it) }, { none }, { host.notice(it) }, offSession)
        val j = if (r.text.isBlank()) null else Parse.json(r.text)
        if (j == null) return emptyList()
        val seen = HashMap<String, Int>()
        val raw = Subs.splitAll(TParse.transcript(j, w.startSec, w.durSec)).filter { !it.isSound && it.original.isNotBlank() && (near || (!it.faint && !it.lowConf)) }
            .filter { s -> val k = s.original.lowercase().filter { it.isLetterOrDigit() }; k.length >= 2 && (seen.merge(k, 1, Int::plus) ?: 1) <= 1 }
        if (raw.isEmpty()) return emptyList()
        if (transOnly) return raw.map { Subs.capPace(it.copy(translated = it.original, pivot = "")) }
        val tr = googleItems(raw.map { it.original })
        return raw.mapIndexed { q, s -> Subs.capPace(s.copy(translated = tr.getOrNull(q)?.trim().orEmpty().ifBlank { s.original }, pivot = "")) }
    }

    /**
     * (v239) سدّ ثغرة/فجوة بأسلوب الوضع 4 في **كل** الأوضاع: (1) الصوت ← نص أصلي بس (من غير عبء الترجمة، فالموديل بيسمع أكتر ومابيسيبش جمل)
     * (2) النص الأصلي ← ترجمة بالسياق اللي حواليه. بيرجّع null لو فيه كلام اتنسخ والترجمة فشلت (الكولر بيرجع للطلب المباشر القديم)، وفاضي لو مفيش كلام.
     * بيرمي ApiErr/IOException زي أي طلب عادي (الكولر بيتصرف في 429 وغيره).
     */
    private fun txFillSubs(w: WavChunk, near: Boolean, key: String): List<Sub>? {
        val r = Api.generate(curModel(), key, buildPrompt(w.durSec, w.startSec, hole = true, holeNear = near, tx = true), w.bytes)
        val j = if (r.text.isBlank()) null else Parse.json(r.text)
        if (j == null) return emptyList()
        val raw = Subs.splitAll(TParse.transcript(j, w.startSec, w.durSec)).filter { !it.isSound && it.original.isNotBlank() && (near || (!it.faint && !it.lowConf)) }
        if (raw.isEmpty()) return emptyList()
        val dl = convDialect.let { if (it.isBlank() || it == "فصحى") "" else it }
        val c = if (dl.isEmpty() || dl == conf.lang) conf else dialConf.getOrPut(dl) { conf.withLang(dl) }
        val first = raw.first().start; val last = raw.last().end
        val before = synchronized(lock) { subs.filter { it.start < first && it.translated.isNotBlank() }.takeLast(12) }
        val after = synchronized(lock) { subs.filter { it.start > last && it.original.isNotBlank() }.take(6) }
        val out = arrayOfNulls<Pair<String, String>>(raw.size)
        for (round in 0 until 2) {
            val missing = raw.indices.filter { out[it] == null }
            if (missing.isEmpty()) break
            val prompt = toneBlock() + ModePrompts.translate(pb, c, missing.map { raw[it] }, before, after, effectiveChars(), effectiveGloss(), round > 0)
            val rr2 = Api.generate(curModel(), key, prompt, null, maxTokens = (missing.size * 150).coerceIn(2048, 8192), temp = 0.2)
            val jj = if (rr2.text.isBlank()) null else Parse.json(rr2.text)
            if (jj != null) for ((ix, pr) in TParse.translations(jj, missing.size)) out[missing[ix]] = pr
        }
        var list = raw.mapIndexedNotNull { q, sb -> out[q]?.let { sb.copy(translated = it.first.trim(), pivot = it.second) } }
        if (list.isEmpty()) return null
        if (LangGuard.foreignOf(list).isNotEmpty()) list = fixForeign(list)
        return list.map { Subs.capPace(it) }
    }

    /** (v177) في جملة مترجمة (مش وصف صوت) بتنتهي قبل بداية المجال أو بتبدأ بعد نهايته بـ ≤1.5ث؟ */
    private fun nearSpeech(a: Double, b: Double): Boolean = synchronized(lock) { subs.any { !it.isSound && (Math.abs(it.end - a) <= 1.5 || Math.abs(it.start - b) <= 1.5) } }

    private fun fillGap(a: Double, b: Double, key0: String) {
        val w = src().wav(a, b) ?: return
        if (w.silent) { host.log("🔇 الفجوة ${a.toInt()}ث صامتة — اتخطّيت"); return }
        var key = key0; var tries = 0
        val near = nearSpeech(a, b)
        val off = offFill()
        while (running && tries++ < 3) {
            try {
                var base: List<Sub> = if (off) txFillOffline(w, near) else txFillSubs(w, near, key) ?: run {   // (v239) ترانسكريبشن ثم ترجمة منفصلة (أسلوب الوضع 4)، والقديم احتياطي
                    val r = Api.generate(curModel(), key, buildPrompt(w.durSec, w.startSec, hole = true, holeNear = near), w.bytes)
                    val j = if (r.text.isBlank()) null else Parse.json(r.text)
                    if (j == null) emptyList() else Subs.splitAll(Parse.subs(j, w.startSec, w.durSec)).filter { !it.isSound && (near || (!it.faint && !it.lowConf)) }.map { Subs.capPace(it) }
                }
                if (!off && base.any { LangGuard.foreign(it) }) base = fixForeign(base)
                val sp = Speech.activeSpans(w.bytes, w.gain)?.let { Speech.absolute(it, w.startSec) }
                if (sp != null) base = base.mapNotNull { Speech.fit(it, sp) }
                val marked = base.map { it.copy(translated = "«" + it.translated + "»", chunk = -2) }
                if (marked.isEmpty()) { host.log("… فجوة ${a.toInt()}ث: مفيهاش كلام — اتساب زي ما هي"); return }
                synchronized(lock) { subs = Subs.merge(Subs.dedup(subs + marked)).sortedBy { it.start } }
                if (!off) pool.good(key); host.changed(); persist()
                host.log("✅ فجوة ${a.toInt()}ث: اتسدّ ${marked.size} جملة")
                return
            } catch (e: ApiErr) {
                if (e.code == 429) { noteQuota(); pool.block(key, 60_000) } else if (e.code == 403) pool.block(key, 3_600_000) else throw e
                key = pool.gapKey(gapKeys - key, rr.getAndIncrement()) ?: return
            }
        }
    }

    // ===== الحفظ =====
    /** حفظ مؤجّل: بيجمّع كل طلبات الحفظ في 3 ثواني في كتابة واحدة على خيط لوحده (بدل ما كل باتش يكتب الملف كله). الحفظ الإجباري (إغلاق/نهاية) بيروح لـ doPersist مباشرة */
    private val persistPending = AtomicBoolean(false)
    private val persistEx = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r).also { it.isDaemon = true; it.name = "persist" } }
    private fun persist() {
        if (store == null || isClosed) return
        if (!persistPending.compareAndSet(false, true)) return
        try { persistEx.schedule({ persistPending.set(false); try { doPersist() } catch (_: Exception) {} }, 3, java.util.concurrent.TimeUnit.SECONDS) }
        catch (_: RejectedExecutionException) { persistPending.set(false); doPersist() }
    }
    private fun doPersist(force: Boolean = false) {
        val st = store ?: return
        if (persistBlocked) return
        if (isClosed && !force) return   // خيط متأخر من إنجن اتقفل: ممنوع يكتب
        val saved = synchronized(lock) {
            val fr = failed.filter { it.value >= MAX_FAILS }.keys.sorted().map { doubleArrayOf(cStart(it), cStart(it + 1)) }
            Saved(conf.chunkSec, srcLang, detDone, subs, done.list(), fr, chars.toList(), gloss.toList(), synchronized(tplCache) { HashMap(tplCache) }, host.position(), autoCharsAttempts, HashMap(bounds), gapTried.list())
        }
        try { st.save(saved) } catch (e: Exception) { warnUser("save", "⚠ مقدرتش أحفظ تقدّم الترجمة — راجع مساحة التخزين، ممكن تضيع لو التطبيق اتقفل"); host.log("⚠ تعذر حفظ التقدم: " + (e.message ?: "").take(80)) }
    }
}
