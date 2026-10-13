package com.tttt.subtitler

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.IOException

/**
 * (v255) محرك sherpa-onnx + موديل SenseVoice (int8): إنجليزي / ياباني / كوري / صيني. من غير فرنساوي.
 * الصوت بيتقطّع لجمل بكاشف طاقة بسيط (EnergyVad) وكل جملة بتتفرّغ لوحدها بوقت بدايتها ونهايتها،
 * والباقي (فلتر الهلوسة، JSON، فولباك جيميناي) مشترك مع Whisper من Offline.
 * الموديل بيتنزّل مرة واحدة من HuggingFace (ملفين: tokens.txt + model.int8.onnx).
 */
object SherpaStt : SttEngine {
    override val id = "sherpa"
    const val SUB = "sherpa/sensevoice"
    const val MODEL = "model.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val MODEL_MB = 239
    const val MIN_MODEL = 200_000_000L
    const val MIN_TOKENS = 100_000L
    const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"
    /** اللغات اللي SenseVoice بيفهمها هنا ("" = تلقائي) */
    private val langs = setOf("", "en", "ja", "zh", "ko")
    /** اللغات اللي الشاشة بترشّح لها sherpa */
    val preferred = setOf("ja", "ko", "zh")

    @Volatile private var rec: OfflineRecognizer? = null
    @Volatile private var recLang = "?"

    private val libOk: Boolean by lazy { try { System.loadLibrary("sherpa-onnx-jni"); true } catch (_: Throwable) { false } }

    fun dir(): File? = Offline.dir?.let { File(it, SUB) }
    fun modelFile(): File? = Offline.pick("$SUB/$MODEL", MIN_MODEL)     // (v271) الجديد الأول وبعده القديم (filesDir)
    fun tokensFile(): File? = Offline.pick("$SUB/$TOKENS", MIN_TOKENS)
    fun downloaded(): Boolean =
        (modelFile()?.let { it.isFile && it.length() > MIN_MODEL } ?: false) && (tokensFile()?.let { it.isFile && it.length() > MIN_TOKENS } ?: false)

    override fun supported() = libOk
    override fun ready() = libOk && downloaded()
    override fun handles(lang: String) = lang in langs

    /** حالة للعرض في شاشة الإعداد */
    fun status(): String = when {
        !libOk -> "⛔ مكتبة sherpa-onnx مش شغالة على جهازك (محتاج معالج 64-bit)"
        downloaded() -> "✅ الموديل نازل وجاهز"
        else -> "⬇ لازم تنزّل الموديل مرة واحدة (~$MODEL_MB ميجا)"
    }

    private fun senseLang(l: String) = if (l.isEmpty()) "auto" else l

    override fun prepare(log: (String) -> Unit) {
        val l = senseLang(Offline.lang())
        if (rec != null && recLang == l) return
        val m = modelFile(); val t = tokensFile()
        if (m == null || t == null || !downloaded()) throw SttInitFail("موديل sherpa مش نازل")
        release()
        val t0 = System.currentTimeMillis()
        val cfg = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = m.path, language = l),
                tokens = t.path,
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            )
        )
        rec = try { OfflineRecognizer(config = cfg) } catch (e: Throwable) { throw SttInitFail("موديل sherpa ما اتحمّلش (" + (e.message ?: e.javaClass.simpleName).take(60) + ")") }
        recLang = l
        log("📴 sherpa: الموديل اتحمّل في ${System.currentTimeMillis() - t0}ms (لغة=$l)")
    }

    override fun transcribe(pcm: FloatArray, lang: String, terms: String, threads: Int): SttResult {
        val r = rec ?: throw IOException("sherpa مش متحمّل")
        val out = ArrayList<SttSeg>()
        val silero = sileroSpans(pcm)
        lastVad = if (silero != null) "Silero" else "طاقة (Silero مش موجود)"
        for (sp in (silero ?: EnergyVad.spans(pcm))) {
            val a = (sp[0] * EnergyVad.RATE).toInt().coerceIn(0, pcm.size)
            val b = (sp[1] * EnergyVad.RATE).toInt().coerceIn(0, pcm.size)
            if (b - a < EnergyVad.RATE / 3) continue
            val st = r.createStream()
            try {
                st.acceptWaveform(pcm.copyOfRange(a, b), EnergyVad.RATE)
                r.decode(st)
                val t = clean(r.getResult(st).text)
                if (t.isNotEmpty()) out.add(SttSeg(sp[0], sp[1], t))
            } finally { st.release() }
        }
        return SttResult(out, "")
    }

    /** اسم كاشف الكلام اللي اشتغل في آخر باتش (للوج) */
    @Volatile var lastVad = "—"

    /** (v257) Silero VAD (موديل صغير بيتحط في assets وقت البناء). أي فشل (مش موجود/مش اتحمّل) ← null والكاشف البسيط EnergyVad هو اللي بيشتغل */
    private fun sileroSpans(pcm: FloatArray): List<DoubleArray>? {
        val ctx = TrWizard.appCtx ?: return null
        return try {
            val v = Vad(ctx.assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = "silero_vad.onnx", threshold = 0.5f, minSilenceDuration = 0.4f, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 12f),
                sampleRate = EnergyVad.RATE, numThreads = 1, provider = "cpu"))
            try {
                val out = ArrayList<DoubleArray>()
                val total = pcm.size.toDouble() / EnergyVad.RATE
                fun drain() {
                    while (!v.empty()) {
                        val sg = v.front()
                        val a = sg.start.toDouble() / EnergyVad.RATE
                        out.add(doubleArrayOf(a, a + sg.samples.size.toDouble() / EnergyVad.RATE))
                        v.pop()
                    }
                }
                var i = 0
                val step = 512 * 16
                while (i < pcm.size) {
                    val e = minOf(pcm.size, i + step)
                    v.acceptWaveform(pcm.copyOfRange(i, e)); drain(); i = e
                }
                v.flush(); drain()
                out.map { doubleArrayOf(maxOf(0.0, it[0] - 0.15), minOf(total, it[1] + 0.15)) }.filter { it[1] - it[0] >= 0.3 }
            } finally { try { v.release() } catch (_: Throwable) {} }
        } catch (_: Throwable) { null }
    }

    private val tagRe = Regex("<\\|[^|>]*\\|>")
    /** بيشيل علامات SenseVoice (لو فلتتت) والنص اللي كله علامات ترقيم */
    private fun clean(s: String): String {
        val t = s.replace(tagRe, "").trim()
        return if (t.none { it.isLetterOrDigit() }) "" else t
    }

    override fun release() {
        val r = rec; rec = null; recLang = "?"
        try { r?.release() } catch (_: Throwable) {}
    }
}

/** تقطيع بسيط للصوت لمجالات كلام (طاقة نافذة 0.1ث). مجال أطول من 12ث بيتقسم عند أهدى نافذة بين 6ث و12ث */
object EnergyVad {
    const val RATE = 16000
    private const val WIN = RATE / 10

    /** مجالات [بداية، نهاية] بالثواني نسبةً لأول الصوت */
    fun spans(pcm: FloatArray): List<DoubleArray> {
        val nw = pcm.size / WIN
        if (nw < 1) return emptyList()
        val lv = DoubleArray(nw)
        for (w in 0 until nw) {
            var s = 0.0; val o = w * WIN
            for (k in 0 until WIN) { val v = pcm[o + k].toDouble(); s += v * v }
            lv[w] = Math.sqrt(s / WIN)
        }
        val p90 = lv.sortedArray()[(nw * 0.90).toInt().coerceIn(0, nw - 1)]
        val thr = maxOf(0.006, 0.12 * p90)
        // مجالات بالنوافذ: فجوة أقل من 0.6ث بتتوصّل
        val raw = ArrayList<IntArray>()
        var cs = -1; var last = -1
        for (w in 0 until nw) {
            if (lv[w] > thr) {
                if (cs < 0) cs = w else if (w - last > 6) { raw.add(intArrayOf(cs, last)); cs = w }
                last = w
            }
        }
        if (cs >= 0) raw.add(intArrayOf(cs, last))
        val out = ArrayList<DoubleArray>()
        for (r in raw) {
            var a = r[0]; val b = r[1] + 1
            while (b - a > 120) {
                var cut = a + 60; var best = Double.MAX_VALUE
                for (w in a + 60..a + 119) if (lv[w] < best) { best = lv[w]; cut = w }
                out.add(doubleArrayOf(a * 0.1, cut * 0.1)); a = cut
            }
            out.add(doubleArrayOf(a * 0.1, b * 0.1))
        }
        val total = pcm.size.toDouble() / RATE
        return out.filter { it[1] - it[0] >= 0.4 }.map { doubleArrayOf(maxOf(0.0, it[0] - 0.2), minOf(total, it[1] + 0.2)) }
    }
}
