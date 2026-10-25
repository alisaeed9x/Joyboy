package com.tttt.subtitler

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * (v545 م2-ب) EngineBackend بعيد: القراءة كلها من نسخة محلية (LinkSession.mirror)، والأوامر async للتلفزيون.
 * التلفزيون هو المالك الوحيد للمحرك والترجمة؛ هنا مفيش Engine ولا Store.
 * `subs` بتتبدّل بقائمة جديدة عند كل delta (LinkMirror) عشان مقارنة المرجع في الـtick تشتغل.
 * الأعلام اللي بتتغيّر من الموبايل ليها قيمة تفاؤلية 3 ثواني لحد ما اللقطة الجاية من التلفزيون توصل.
 */
class RemoteEngine : EngineBackend {
    private val m get() = LinkSession.mirror
    private val snap get() = m.eng
    private val ov = ConcurrentHashMap<String, Pair<Any, Long>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> ovGet(k: String, real: T): T {
        val o = ov[k] ?: return real
        return if (System.currentTimeMillis() - o.second < 3000L) o.first as T else { ov.remove(k); real }
    }

    private fun put(op: String, vararg kv: Pair<String, Any>) {
        val a = JSONObject(); for ((k, v) in kv) a.put(k, v)
        LinkSession.send(op, a)
    }

    private fun setFlag(name: String, v: Any) {
        ov[name] = Pair(v, System.currentTimeMillis())
        LinkSession.send("engine.set", JSONObject().put("name", name).put("value", v))
    }

    override val subs: List<Sub> get() = m.subs

    override var paused: Boolean
        get() = ovGet("paused", snap.paused)
        set(v) { setFlag("paused", v) }
    override var userPaused: Boolean
        get() = ovGet("userPaused", snap.userPaused)
        set(v) { ov["userPaused"] = Pair(v, System.currentTimeMillis()); LinkSession.send(if (v) "engine.pause" else "engine.resume") }
    override var keepGoing: Boolean
        get() = ovGet("keepGoing", snap.keepGoing)
        set(v) { setFlag("keepGoing", v) }
    override var convDialect: String
        get() = ovGet("convDialect", snap.convDialect)
        set(v) { setFlag("convDialect", v) }
    override var transOnly: Boolean
        get() = ovGet("transOnly", snap.transOnly)
        set(v) { setFlag("transOnly", v) }
    override var toneStyle: String
        get() = ovGet("toneStyle", snap.toneStyle)
        set(v) { setFlag("toneStyle", v) }
    override var toneStrength: String
        get() = ovGet("toneStrength", snap.toneStrength)
        set(v) { setFlag("toneStrength", v) }
    override var titleHint: String
        get() = ovGet("titleHint", snap.titleHint)
        set(v) { setFlag("titleHint", v) }
    override var skipIfBaseLang: Boolean
        get() = ovGet("skipIfBaseLang", snap.skipIfBaseLang)
        set(v) { setFlag("skipIfBaseLang", v) }
    override var baseSkipped: Boolean
        get() = ovGet("baseSkipped", snap.baseSkipped)
        set(v) { setFlag("baseSkipped", v) }
    override var baseLangCode: String
        get() = ovGet("baseLangCode", snap.baseLangCode)
        set(v) { setFlag("baseLangCode", v) }

    override val refiningSegs: List<DoubleArray> get() = snap.refining

    private val dummy = Sub(0.0, 0.0, "", "", "unknown", "unknown", "none", emptyList(), emptyList(), false, false)

    /** الجمل نفسها فاضية (التلفزيون هو اللي معاه النسخ)؛ الحجم بس صح عشان العرض */
    override val versions: List<Engine.Version>
        get() = snap.versions.map { Engine.Version(it.name, java.util.Collections.nCopies(it.count, dummy), it.dialect) }

    override fun coveredSec(): Double = snap.covered
    override fun pendingCount(): Int = snap.pendingCount
    override fun coverageSegs(): List<DoubleArray> = snap.coverage
    override fun pendingSegs(): List<DoubleArray> = snap.pending
    override fun batches(): List<Engine.BatchInfo> = snap.batches
    override fun batchRows(): List<Pair<String, Boolean>> = snap.rows
    override fun problems(): List<Engine.BatchInfo> = snap.problems
    override fun failedCount(): Int = snap.failed
    override fun fullyDone(): Boolean = snap.fullyDone

    override fun chunkOfSec(sec: Double): Int {
        val b = snap.batches.lastOrNull { it.start <= sec } ?: return 0
        return b.idx
    }

    override fun chunkStartSec(i: Int): Double = snap.batches.firstOrNull { it.idx == i }?.start ?: 0.0

    override fun offlineBrief(): String? = snap.offBrief
    override fun offlineDetail(): String? = snap.offDetail
    override fun charactersNow(): List<Chr> = snap.chars

    override fun analyzeCharsNow(report: (String) -> Unit, done: (List<Chr>) -> Unit) {
        LinkSession.onOp("engine.analyzeChars", report) { js ->
            done(try { BrainChars.decode(org.json.JSONArray(js)) } catch (_: Exception) { emptyList() })
        }
        LinkSession.send("engine.analyzeChars")
    }

    override fun translateFrom(sec: Double) = put("engine.translateFrom", "sec" to sec)
    override fun translateFromStart() = LinkSession.send("engine.translateFromStart")
    override fun redoAll(keep: Boolean) = put("engine.redoAll", "keep" to keep)
    override fun redoFrom(i: Int, keep: Boolean) = put("engine.redoFrom", "i" to i, "keep" to keep)
    override fun redoOnly(i: Int, keep: Boolean) = put("engine.redoOnly", "i" to i, "keep" to keep)
    override fun langChanged(newLang: String, posSec: Double) = put("engine.langChanged", "lang" to newLang, "pos" to posSec)
    override fun setConvDialect(d: String, existing: Boolean) = put("engine.setConvDialect", "d" to d, "existing" to existing)
    override fun adoptDialect(d: String) = put("engine.adoptDialect", "d" to d)

    /** الإعدادات بتتبعت قبلها بـLinkSession.setCfg؛ ده بس بيخلّي التلفزيون يعيد قراءتها */
    override fun applyConf(c: Conf) = LinkSession.send("conf.reload")

    override fun saveNow() = LinkSession.send("engine.saveNow")
    override fun stop() = LinkSession.send("engine.pause")
    override fun awaitStopped(ms: Long) {}

    override fun reopen(): Boolean { LinkSession.send("engine.resume"); return true }

    override fun importSubs(l: List<Sub>) = put("engine.importSubs", "subs" to SubCodec.arrayToJson(l))

    /** العدد الحقيقي بيوصل في اللقطة الجاية (problems/failed)؛ هنا 0 لأن الأمر async */
    override fun retryProblems(): Int { LinkSession.send("engine.retryProblems"); return 0 }
    override fun retryFailed() = LinkSession.send("engine.retryFailed")
    override fun removeDuplicates(): Int { LinkSession.send("engine.removeDuplicates"); return 0 }

    override fun saveVersion(name: String, dialect: String) = put("engine.saveVersion", "name" to name, "dialect" to dialect)
    override fun applyVersion(i: Int): Int { put("engine.applyVersion", "i" to i); return 0 }

    private fun longOp(op: String, args: JSONObject, report: (String) -> Unit, done: (String) -> Unit) {
        LinkSession.onOp(op, report, done)
        LinkSession.send(op, args)
    }

    override fun recap(upToSec: Double, report: (String) -> Unit, done: (String) -> Unit) =
        longOp("engine.recap", JSONObject().put("upToSec", upToSec), report, done)

    override fun rewriteAll(label: String, instruction: String, fromSec: Double, report: (String) -> Unit, done: (Int) -> Unit) =
        longOp("engine.rewriteAll", JSONObject().put("label", label).put("instruction", instruction).put("fromSec", fromSec), report) { done(it.toIntOrNull() ?: 0) }

    override fun refineWholeNow(report: (String) -> Unit, done: (Int) -> Unit) =
        longOp("engine.refineWholeNow", JSONObject(), report) { done(it.toIntOrNull() ?: 0) }

    override fun correctPronounsNow(report: (String) -> Unit, done: (Int) -> Unit) =
        longOp("engine.correctPronounsNow", JSONObject(), report) { done(it.toIntOrNull() ?: 0) }

    override fun googleTranslateAll(report: (String) -> Unit, done: (Int) -> Unit) =
        longOp("engine.googleTranslateAll", JSONObject(), report) { done(it.toIntOrNull() ?: 0) }
}
