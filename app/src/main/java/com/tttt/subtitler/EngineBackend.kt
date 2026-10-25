package com.tttt.subtitler

/**
 * (v541) م0-أ — الواجهة اللي المشغّل (PlayerActivity) بيكلّم بيها «المحرك».
 *
 * النهارده المنفّذ الوحيد هو LocalEngine (بيفوّض كل شيء لـ Engine الحقيقي من غير أي منطق).
 * في مرحلة تانية هيتضاف منفّذ بعيد (RemoteEngine) بيقرا من نسخة محلية ويبعت الأوامر للتلفزيون.
 *
 * القائمة اتبنت من الاستخدام الفعلي لـ engine. في Player.kt (grep)، مش من التخمين.
 * دوال التشغيل الداخلية (run / load / persistBlocked) مش هنا: دي بتتنادى على Engine الحقيقي
 * (realEngine) جوه initEngine / startEngine / retireEngine بس.
 */
interface EngineBackend {
    val subs: List<Sub>
    var paused: Boolean
    var userPaused: Boolean
    var keepGoing: Boolean
    var convDialect: String
    var transOnly: Boolean
    var toneStyle: String
    var toneStrength: String
    var titleHint: String
    var skipIfBaseLang: Boolean
    var baseSkipped: Boolean
    var baseLangCode: String
    val refiningSegs: List<DoubleArray>
    val versions: List<Engine.Version>

    fun coveredSec(): Double
    fun pendingCount(): Int
    fun coverageSegs(): List<DoubleArray>
    fun pendingSegs(): List<DoubleArray>
    fun batches(): List<Engine.BatchInfo>
    fun batchRows(): List<Pair<String, Boolean>>
    fun problems(): List<Engine.BatchInfo>
    fun failedCount(): Int
    fun fullyDone(): Boolean
    fun chunkOfSec(sec: Double): Int
    fun chunkStartSec(i: Int): Double
    fun offlineBrief(): String?
    fun offlineDetail(): String?
    fun charactersNow(): List<Chr>
    fun analyzeCharsNow(report: (String) -> Unit, done: (List<Chr>) -> Unit)

    fun translateFrom(sec: Double)
    fun translateFromStart()
    fun redoAll(keep: Boolean = true)
    fun redoFrom(i: Int, keep: Boolean = true)
    fun redoOnly(i: Int, keep: Boolean = true)
    fun langChanged(newLang: String, posSec: Double)
    fun setConvDialect(d: String, existing: Boolean)
    fun adoptDialect(d: String)
    fun applyConf(c: Conf)
    fun saveNow()
    fun stop()
    fun awaitStopped(ms: Long)
    fun reopen(): Boolean
    fun importSubs(l: List<Sub>)
    fun retryProblems(): Int
    fun retryFailed()
    fun removeDuplicates(): Int

    /**
     * dialect فاضي = نفس الافتراضي القديم (اللهجة الحالية أو «فصحى»).
     */
    fun saveVersion(name: String, dialect: String = "")
    fun applyVersion(i: Int): Int

    fun recap(upToSec: Double, report: (String) -> Unit, done: (String) -> Unit)
    fun rewriteAll(label: String, instruction: String, fromSec: Double, report: (String) -> Unit, done: (Int) -> Unit)
    fun refineWholeNow(report: (String) -> Unit, done: (Int) -> Unit)
    fun correctPronounsNow(report: (String) -> Unit, done: (Int) -> Unit)
    fun googleTranslateAll(report: (String) -> Unit, done: (Int) -> Unit)
}

/**
 * التنفيذ المحلي: تفويض كامل لـ Engine من غير أي منطق إضافي (السلوك مطابق للقديم 100%).
 */
class LocalEngine(val e: Engine) : EngineBackend {
    override val subs: List<Sub> get() = e.subs
    override var paused: Boolean
        get() = e.paused
        set(v) { e.paused = v }
    override var userPaused: Boolean
        get() = e.userPaused
        set(v) { e.userPaused = v }
    override var keepGoing: Boolean
        get() = e.keepGoing
        set(v) { e.keepGoing = v }
    override var convDialect: String
        get() = e.convDialect
        set(v) { e.convDialect = v }
    override var transOnly: Boolean
        get() = e.transOnly
        set(v) { e.transOnly = v }
    override var toneStyle: String
        get() = e.toneStyle
        set(v) { e.toneStyle = v }
    override var toneStrength: String
        get() = e.toneStrength
        set(v) { e.toneStrength = v }
    override var titleHint: String
        get() = e.titleHint
        set(v) { e.titleHint = v }
    override var skipIfBaseLang: Boolean
        get() = e.skipIfBaseLang
        set(v) { e.skipIfBaseLang = v }
    override var baseSkipped: Boolean
        get() = e.baseSkipped
        set(v) { e.baseSkipped = v }
    override var baseLangCode: String
        get() = e.baseLangCode
        set(v) { e.baseLangCode = v }
    override val refiningSegs: List<DoubleArray> get() = e.refiningSegs
    override val versions: List<Engine.Version> get() = e.versions

    override fun coveredSec(): Double = e.coveredSec()
    override fun pendingCount(): Int = e.pendingCount()
    override fun coverageSegs(): List<DoubleArray> = e.coverageSegs()
    override fun pendingSegs(): List<DoubleArray> = e.pendingSegs()
    override fun batches(): List<Engine.BatchInfo> = e.batches()
    override fun batchRows(): List<Pair<String, Boolean>> = e.batchRows()
    override fun problems(): List<Engine.BatchInfo> = e.problems()
    override fun failedCount(): Int = e.failedCount()
    override fun fullyDone(): Boolean = e.fullyDone()
    override fun chunkOfSec(sec: Double): Int = e.chunkOfSec(sec)
    override fun chunkStartSec(i: Int): Double = e.chunkStartSec(i)
    override fun offlineBrief(): String? = e.offlineBrief()
    override fun offlineDetail(): String? = e.offlineDetail()
    override fun charactersNow(): List<Chr> = e.charactersNow()
    override fun analyzeCharsNow(report: (String) -> Unit, done: (List<Chr>) -> Unit) { e.analyzeCharsNow(report, done) }

    override fun translateFrom(sec: Double) { e.translateFrom(sec) }
    override fun translateFromStart() { e.translateFromStart() }
    override fun redoAll(keep: Boolean) { e.redoAll(keep) }
    override fun redoFrom(i: Int, keep: Boolean) { e.redoFrom(i, keep) }
    override fun redoOnly(i: Int, keep: Boolean) { e.redoOnly(i, keep) }
    override fun langChanged(newLang: String, posSec: Double) { e.langChanged(newLang, posSec) }
    override fun setConvDialect(d: String, existing: Boolean) { e.setConvDialect(d, existing) }
    override fun adoptDialect(d: String) { e.adoptDialect(d) }
    override fun applyConf(c: Conf) { e.applyConf(c) }
    override fun saveNow() { e.saveNow() }
    override fun stop() { e.stop() }
    override fun awaitStopped(ms: Long) { e.awaitStopped(ms) }
    override fun reopen(): Boolean = e.reopen()
    override fun importSubs(l: List<Sub>) { e.importSubs(l) }
    override fun retryProblems(): Int = e.retryProblems()
    override fun retryFailed() { e.retryFailed() }
    override fun removeDuplicates(): Int = e.removeDuplicates()

    override fun saveVersion(name: String, dialect: String) {
        if (dialect.isEmpty()) e.saveVersion(name) else e.saveVersion(name, dialect)
    }
    override fun applyVersion(i: Int): Int = e.applyVersion(i)

    override fun recap(upToSec: Double, report: (String) -> Unit, done: (String) -> Unit) { e.recap(upToSec, report, done) }
    override fun rewriteAll(label: String, instruction: String, fromSec: Double, report: (String) -> Unit, done: (Int) -> Unit) { e.rewriteAll(label, instruction, fromSec, report, done) }
    override fun refineWholeNow(report: (String) -> Unit, done: (Int) -> Unit) { e.refineWholeNow(report, done) }
    override fun correctPronounsNow(report: (String) -> Unit, done: (Int) -> Unit) { e.correctPronounsNow(report, done) }
    override fun googleTranslateAll(report: (String) -> Unit, done: (Int) -> Unit) { e.googleTranslateAll(report, done) }
}
