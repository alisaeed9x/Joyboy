package com.tttt.subtitler

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * (v1011) «🧩 إكمال الناقص» في الاستوديو: بيكتشف إيه الناقص فعلًا (تفريغ / ترجمة / الاتنين) ويكمّله، وبس.
 *
 * مفيش خط أنابيب AI جديد — بيعيد استخدام اللي موجود:
 *  • التفريغ: نفس تفريغ وارد (Lecture.start ← doChunk ← WaridTx.transcribeChunk): نفس البرومبت/الأجزاء/الستريم/Files API/
 *    اكتشاف اللغة/حفظ كل جزء أول ما يخلص. الدوكيومنت الجديد بيتحوّل وارد زي ما Lecture.start بيعمل؛ الدوكيومنت اللي فيه تقدّم
 *    بالطريقة العادية بيكمّل على نفس طريقته (مبنطّش نمسح اللي اتفرّغ).
 *  • الترجمة: نفس خط وارد (Studio.translateToSrt ← SubFileTr.translate: لهجة/أسلوب/لغة المصدر d.langCode/تحليل الرد) —
 *    بس على السطور اللي من غير ترجمة حقيقية، والسطور المترجمة قبل كده مبتتلمسش.
 *
 * الموديل: دايمًا MODEL (Gemini Flash Lite Latest) — مش موديل وارد ولا موديل الإعدادات. مفيش سلسلة موديلات ولا «موديل أدق»
 * ولا FreeLLM ولا جوجل كبديل: لو الموديل فشل، المحاولات بتتعاد عليه هو بس، ولو فضل فاشل النتيجة بتقول كده بصراحة.
 *
 * مفيش «نجاح» إلا بعد التحقق: التفريغ complete فعلًا (done == nChunks ومفيش failed) والترجمة مفيهاش سطر من غير ترجمة حقيقية.
 */
object StudioComplete {
    /** gemini-flash-lite-latest — نفس المعرّف الموجود في Models.DEFAULT / Lecture.LEC_MODEL_FAST */
    const val MODEL = "gemini-flash-lite-latest"

    private const val TX_ROUNDS = 3   // جولات إعادة تفريغ الأجزاء الفاشلة قبل ما نعلن إنها ناقصة

    /** تحليل الناقص: tx = أجزاء تفريغ · tr = سطور ترجمة */
    class Plan(
        val needTx: Boolean, val txMissing: Int, val txTotal: Int,
        val needTr: Boolean, val trMissing: Int, val trTotal: Int,
        val noSource: Boolean
    ) {
        val nothing: Boolean get() = !needTx && !needTr
        fun describe(): String {
            val parts = ArrayList<String>()
            if (needTx) parts.add(if (txTotal <= 0) "تفريغ (لسه ما بدأش)" else "تفريغ: $txMissing من $txTotal جزء")
            if (needTr) parts.add(if (trTotal <= 0) "ترجمة (بعد التفريغ)" else "ترجمة: $trMissing من $trTotal سطر")
            return if (parts.isEmpty()) "مفيش حاجة ناقصة" else parts.joinToString(" + ")
        }
    }

    class Result(val ok: Boolean, val message: String, val txRemaining: Int, val trRemaining: Int, val trDone: Int, val stopped: Boolean)

    /** تشغيلة حيّة لدوكيومنت — واحدة بس في نفس الوقت لكل دوكيومنت */
    class Job(val docId: String) {
        @Volatile var running = true
        @Volatile var stop = false
        @Volatile var status = "بحلّل الناقص…"
        @Volatile var result: Result? = null
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    fun jobOf(id: String): Job? = jobs[id]
    fun isRunning(id: String): Boolean = jobs[id]?.running == true
    fun stop(id: String) {
        jobs[id]?.let { it.stop = true }
        if (Lecture.isRunning(id)) Lecture.stop(id)
    }

    /** إيه الناقص دلوقتي؟ (بدون أي شبكة — قراءة محلية بس) */
    fun analyze(ctx: Context, d: Lecture.Doc): Plan {
        val noSource = d.uri.isBlank() && d.link.isBlank()
        val needTx: Boolean
        val txMissing: Int
        val txTotal: Int
        synchronized(d) {
            txTotal = d.nChunks
            txMissing = if (d.nChunks > 0) (0 until d.nChunks).count { it !in d.done } else 0
            // لسه ما بدأش (nChunks == 0 ومفيش جمل) أو فيه أجزاء ناقصة/فاشلة
            needTx = if (d.nChunks == 0) d.segs.isEmpty() else !d.complete
        }
        val (trMissing, trTotal) = Studio.countUntranslated(ctx, d)
        // لو التفريغ لسه هيتعمل، الترجمة ناقصة بالتبعية (مش عارفين عدد السطور لسه)
        val needTr = if (needTx && trTotal == 0) true else trMissing > 0
        return Plan(needTx, txMissing, txTotal, needTr, trMissing, trTotal, noSource)
    }

    /** يبدأ إكمال الناقص في الخلفية. onStatus/onDone بيتنادوا من خيط خلفي (الواجهة هي اللي بتعمل post). */
    fun start(ctx0: Context, d: Lecture.Doc, onStatus: (String) -> Unit, onDone: (Result) -> Unit): Job? {
        val ctx = ctx0.applicationContext
        jobs[d.id]?.let { if (it.running) return it }
        if (Lecture.isRunning(d.id)) return null   // تفريغ عادي شغّال على نفس الدوكيومنت — الواجهة بتقول للمستخدم يستنى
        val job = Job(d.id)
        jobs[d.id] = job
        Thread {
            var res: Result
            try {
                res = run(ctx, d, job) { m -> job.status = m; try { onStatus(m) } catch (_: Throwable) {} }
            } catch (e: Throwable) {
                LogStore.err("StudioComplete:run", e)
                res = Result(false, "⚠ " + (e.message ?: e.javaClass.simpleName).take(100), -1, -1, 0, false)
            } finally {
                try { Lecture.save(ctx, d) } catch (_: Throwable) {}
            }
            job.result = res
            job.status = res.message
            job.running = false
            try { onDone(res) } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
        return job
    }

    private fun sleepMs(ms: Long): Boolean = try { Thread.sleep(ms); true } catch (_: InterruptedException) { false }

    private fun run(ctx: Context, d: Lecture.Doc, job: Job, report: (String) -> Unit): Result {
        val plan = analyze(ctx, d)
        LogStore.add("🧩 [إكمال الناقص] " + plan.describe() + " · الموديل: " + MODEL)
        if (plan.nothing) return Result(true, "✅ مفيش حاجة ناقصة — التفريغ والترجمة كاملين", 0, 0, 0, false)
        if (SubFileTr.realGemKeys().isEmpty()) return Result(false, "⚠ مفيش مفتاح Gemini حقيقي — ضيفه من الإعدادات (المفاتيح الوسيطة مبتثبّتش الموديل)", -1, -1, 0, false)

        // ===== ١) التفريغ الناقص =====
        var txRemaining = 0
        if (plan.needTx) {
            if (plan.noSource) return Result(false, "⚠ مصدر الصوت مش متاح (مفيش ملف ولا لينك محفوظ) — مقدرش أفرّغ الأجزاء الناقصة", plan.txMissing, -1, 0, false)
            var lastErr = ""
            for (round in 1..TX_ROUNDS) {
                if (job.stop) break
                val label = if (TX_ROUNDS > 1) "تفريغ (محاولة $round/$TX_ROUNDS)" else "تفريغ"
                report("⏳ $label — بجهّز…")
                val run = Lecture.start(ctx, d, MODEL)
                if (run == null) return Result(false, "⚠ مقدرتش أبدأ التفريغ", -1, -1, 0, false)
                // انتظار انتهاء التفريغ (Lecture.start شغّال على خيط تاني) مع نقل حالته وإيقافه لو المستخدم وقّف
                while (Lecture.isRunning(d.id)) {
                    if (job.stop) Lecture.stop(d.id)
                    val st = Lecture.runOf(d.id)?.status ?: ""
                    if (st.isNotBlank()) report("⏳ $label — $st")
                    if (!sleepMs(1000L)) { Lecture.stop(d.id); break }
                }
                lastErr = Lecture.runOf(d.id)?.error ?: ""
                if (job.stop) break
                val complete = synchronized(d) { d.complete }
                if (complete) break
                // لو الخطأ مفتاح/شبكة/مصدر (Run.error) متعاد-ش بلا فايدة — بنعيد بس لو فيه أجزاء فاشلة هتتحاول تاني
                if (lastErr.isNotBlank() && synchronized(d) { d.done.isEmpty() }) break
                if (round < TX_ROUNDS) {
                    report("↻ في أجزاء لسه ناقصة — هعيد المحاولة على نفس الموديل")
                    if (!sleepMs(3000L * round)) break
                }
            }
            txRemaining = synchronized(d) { if (d.nChunks > 0) (0 until d.nChunks).count { it !in d.done } else (if (d.segs.isEmpty()) 1 else 0) }
            if (job.stop) return Result(false, "⏸ اتوقف — اللي اتفرّغ اتحفظ، دوس «إكمال الناقص» تاني تكمّل", txRemaining, -1, 0, true)
            val segs = synchronized(d) { d.segs.size }
            if (segs == 0) {
                val why = Lecture.runOf(d.id)?.error?.takeIf { it.isNotBlank() } ?: "فشل التفريغ — مفيش جمل اتفرّغت"
                return Result(false, "⚠ " + why.take(110), txRemaining, -1, 0, false)
            }
        }

        // ===== ٢) الترجمة الناقصة (بعد ما التفريغ بقى جاهز) =====
        report("⏳ ترجمة الناقص…")
        val srcLang = synchronized(d) { d.langCode.ifBlank { null } }
        val mt = Studio.translateMissing(ctx, d, { m -> report("⏳ $m") }, { job.stop }, MODEL, srcLang)
        if (job.stop) return Result(false, "⏸ اتوقف — الترجمة اللي خلصت اتحفظت، دوس «إكمال الناقص» تاني تكمّل", txRemaining, mt.remaining, mt.now, true)
        if (mt.error.isNotBlank() && mt.srt == null) return Result(false, "⚠ " + mt.error.take(110), txRemaining, mt.remaining, mt.now, false)

        // ===== ٣) التحقق النهائي من الحالة الفعلية المحفوظة (مش من عدّادات الشغل) =====
        val after = analyze(ctx, d)
        val txLeft = if (after.needTx) maxOf(1, after.txMissing) else 0
        val trLeft = after.trMissing
        LogStore.add("🧩 [إكمال الناقص] بعد التحقق — تفريغ ناقص: $txLeft · ترجمة ناقصة: $trLeft · اترجم في التشغيلة: ${mt.now}")
        return if (txLeft == 0 && trLeft == 0) {
            Result(true, "✅ اكتمل — التفريغ كامل وكل السطور مترجمة (اترجم ${mt.now} سطر ناقص)", 0, 0, mt.now, false)
        } else {
            val parts = ArrayList<String>()
            if (txLeft > 0) parts.add("$txLeft جزء تفريغ")
            if (trLeft > 0) parts.add("$trLeft سطر ترجمة")
            val extra = if (mt.error.isNotBlank()) " (" + mt.error.take(60) + ")" else ""
            Result(false, "⚠ لسه ناقص: " + parts.joinToString(" + ") + extra + " — دوس «إكمال الناقص» تاني", txLeft, trLeft, mt.now, false)
        }
    }
}
