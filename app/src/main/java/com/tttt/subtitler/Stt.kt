package com.tttt.subtitler

import java.io.IOException

/**
 * (v255) واجهة محرك التفريغ. Offline.transcribe بينده على المحرك المختار (مفتاح stt_engine: whisper | sherpa، الافتراضي whisper)
 * والباقي (فلتر الهلوسة، toJson، فولباك جيميناي، الراحة بعد 3 فشلات) مشترك بين كل المحركات.
 */

/** جملة مفرّغة. noSpeech/logProb للفلتر بتاع الهلوسة — محرك مالوش القيم دي يسيبهم 0 */
class SttSeg(val start: Double, val end: Double, val text: String, val noSpeech: Double = 0.0, val logProb: Double = 0.0)

/** [lang] لغة الكلام اللي المحرك اكتشفها ("" لو ما اكتشفش) */
class SttResult(val segs: List<SttSeg>, val lang: String = "")

/** المحرك ما قدرش يتحمّل (الموديل/المكتبة) ← الأوفلاين بيرتاح 5 دقايق وجيميناي بينسخ */
class SttInitFail(msg: String) : IOException(msg)

interface SttEngine {
    val id: String
    /** المكتبة موجودة وبتشتغل على الجهاز ده */
    fun supported(): Boolean
    /** مدعوم + الموديل نازل */
    fun ready(): Boolean
    /** المحرك بيفهم لغة الكلام دي؟ ("" = تلقائي) */
    fun handles(lang: String): Boolean
    /** بيحمّل الموديل لو لسه (بيتنده تحت قفل Offline). فشل التحميل ← SttInitFail */
    fun prepare(log: (String) -> Unit)
    /** [pcm] 16kHz mono float. أي خطأ تاني ← Exception عادي (بيتحسب فشل) */
    fun transcribe(pcm: FloatArray, lang: String, terms: String, threads: Int): SttResult
    /** بيحرّر الموديل من الرام (بعد تنزيل جديد أو مسح) */
    fun release()
}

/** Whisper (whisper.cpp عن طريق WhisperLib) — نفس سلوك v254 بالظبط، بس ورا الواجهة */
object WhisperStt : SttEngine {
    override val id = "whisper"
    // (v420) كل خانة تفريغ ليها context مستقل من نفس الملف (الأوزان بتتكرر في الرام: base ~60MB · small ~185MB؛ medium خانة واحدة بس)
    private val ctxs = LongArray(Offline.MAX_PAR)
    @Volatile private var ctxFile = ""
    private fun slot(): Int = (Offline.slotTl.get() ?: 0).coerceIn(0, Offline.MAX_PAR - 1)

    override fun supported() = WhisperLib.loaded
    override fun ready() = WhisperLib.loaded && Offline.downloaded()
    override fun handles(lang: String) = true

    @Synchronized
    override fun prepare(log: (String) -> Unit) {
        val f = Offline.file() ?: throw SttInitFail("مفيش مساحة تخزين")
        if (ctxFile != f.path) { releaseAll() }
        val s = slot()
        if (ctxs[s] == 0L) {
            val t0 = System.currentTimeMillis()
            ctxs[s] = WhisperLib.nInit(f.path); ctxFile = f.path
            if (ctxs[s] == 0L) throw SttInitFail("الموديل ما اتحمّلش")
            log("📴 الأوفلاين: الموديل اتحمّل (خانة ${s + 1}) في ${System.currentTimeMillis() - t0}ms")
        }
    }

    override fun transcribe(pcm: FloatArray, lang: String, terms: String, threads: Int): SttResult {
        val s = slot()
        val raw = WhisperLib.nRun(ctxs[s], pcm, threads, lang, terms, s)
        val txt = String(raw, Charsets.UTF_8)
        if (txt.startsWith("ERR")) throw IOException(txt.replace('\t', ' '))
        return parse(txt)
    }

    @Synchronized
    private fun releaseAll() {
        for (i in ctxs.indices) if (ctxs[i] != 0L) { WhisperLib.nFree(ctxs[i]); ctxs[i] = 0L }
        ctxFile = ""
    }

    /** بيتنده من Offline.releaseSafe تحت قفل الكتابة (مفيش تفريغ شغّال) */
    override fun release() { releaseAll() }

    /** رد nRun: سطر «L<tab>لغة» + سطور «بداية<tab>نهاية<tab>noSpeech<tab>logProb<tab>نص» (الوقت بالملي) */
    private fun parse(txt: String): SttResult {
        val segs = ArrayList<SttSeg>(); var lang = ""
        for (line in txt.split('\n')) {
            if (line.isBlank()) continue
            val p = line.split('\t', limit = 5)
            if (p[0] == "L") { lang = p.getOrElse(1) { "" }; continue }
            if (p.size < 5) continue
            val t0 = p[0].toDoubleOrNull() ?: continue; val t1 = p[1].toDoubleOrNull() ?: continue
            segs.add(SttSeg(t0 / 1000.0, t1 / 1000.0, p[4].trim(), p[2].toDoubleOrNull() ?: 0.0, p[3].toDoubleOrNull() ?: 0.0))
        }
        return SttResult(segs, lang)
    }
}
