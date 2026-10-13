package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock

/** (v253) جسر JNI لـ whisper.cpp (cpp/jni.cpp). المكتبة arm64 بس؛ لو مش موجودة loaded = false والأوفلاين بيتقفل لوحده */
object WhisperLib {
    @Volatile var loaded = false; private set
    init { try { System.loadLibrary("subwhisper"); loaded = true } catch (_: Throwable) {} }
    @JvmStatic external fun nInit(path: String): Long
    @JvmStatic external fun nFree(h: Long)
    @JvmStatic external fun nRun(h: Long, pcm: FloatArray, threads: Int, lang: String, prompt: String): ByteArray
}

/**
 * (v253) الترانسكريبشن الأوفلاين: Whisper شغّال على الموبايل نفسه — من غير مفتاح ولا نت (النت بس لتنزيل الموديل مرة واحدة).
 * بيستبدل نسخ الصوت بجيميناي؛ الترجمة لسه جيميناي (نص بس). أي فشل ← الباتش بيتنسخ بجيميناي (الاحتياطي).
 * الرد بيتحوّل لنفس JSON بتاع جيميناي في الترانسكريبشن فباقي المحرك (TParse ← الترجمة السياقية) ما بيتغيّرش.
 */
object Offline {
    class Model(val id: String, val label: String, val file: String, val approxMb: Int, val note: String)
    val models = listOf(
        Model("base", "⚡ base", "ggml-base-q5_1.bin", 60, "الأسرع — دقة أقل، مناسب لكلام واضح"),
        Model("small", "⚖ small", "ggml-small-q5_1.bin", 185, "متوازن (الافتراضي)"),
        Model("medium", "🎯 medium", "ggml-medium-q5_0.bin", 520, "الأدق — بطيء وبياخد رام كتير (~1.5GB)، مش لأي موبايل")
    )
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    val langIds = listOf("", "en", "ar", "fr", "es", "tr", "ko", "ja", "zh", "ru", "de", "it", "hi", "fa", "ur")
    val langLabels = listOf("تلقائي", "إنجليزي", "عربي", "فرنساوي", "إسباني", "تركي", "كوري", "ياباني", "صيني", "روسي", "ألماني", "إيطالي", "هندي", "فارسي", "أوردو")   // (v255) بالعربي بس — الاسم بحروفه الأصلية كان بيلخبط

    /** (v271) المكان الدائم للموديلات: Android/media/<الحزمة>/models — بيفضل بعد مسح/إعادة تثبيت التطبيق (زي نسخة المفاتيح). لو مش متاح بيرجع لـ filesDir */
    @Volatile var dir: File? = null   // بيتظبط من Cfg.init
    /** (v271) مكان الموديلات القديم (filesDir) — بنقرا منه لو الموديل لسه مانتقلش، وبينتقل لوحده في الخلفية */
    @Volatile var legacy: File? = null
    fun model(): Model = models.firstOrNull { it.id == Cfg.str("off_model", "small") } ?: models[1]
    /** (v271) الملف الكامل لو موجود في أي مكان (الجديد الأول)، وإلا مساره في المكان الجديد (للتنزيل) */
    fun pick(rel: String, minBytes: Long): File? {
        val n = dir?.let { File(it, rel) }
        if (n != null && n.isFile && n.length() > minBytes) return n
        val o = legacy?.let { File(it, rel) }
        if (o != null && o.isFile && o.length() > minBytes) return o
        return n ?: o
    }
    /** (v271) كل الأماكن اللي ممكن الموديل يكون فيها (للمسح) */
    fun roots(): List<File> = listOfNotNull(dir, legacy).distinctBy { it.path }
    fun file(m: Model = model()): File? = pick("whisper/" + m.file, m.approxMb * 600_000L)
    fun downloaded(m: Model = model()): Boolean = file(m)?.let { it.isFile && it.length() > m.approxMb * 600_000L } ?: false
    fun lang(): String = Cfg.str("stt_lang", "").trim().let { if (it in langIds) it else "" }
    /** (v255) المحركات المتاحة: Whisper (لكل اللغات) و sherpa-onnx SenseVoice (إنجليزي/ياباني/كوري/صيني) */
    val engines: List<SttEngine> = listOf(WhisperStt, SherpaStt)
    /** المحرك المختار (مفتاح stt_engine: whisper | sherpa، الافتراضي whisper). لو المختار مش مدعوم على الجهاز أو مش بيفهم اللغة ← whisper */
    fun engine(): SttEngine = (if (RemoteStt.active()) RemoteStt else null) ?: engines.firstOrNull { it.id == Cfg.str("stt_engine", "whisper") && it.supported() && it.handles(lang()) } ?: WhisperStt
    /** (v273) التفريغ السحابي (RemoteStt) شغّال وجاهز — الموديل المحلي مش لازم يكون نازل */
    fun remoteOn(): Boolean = RemoteStt.active()
    /** true = الترانسكريبشن بيتعمل أوفلاين (مفعّل + الموديل نازل + المكتبة موجودة) والوضع بيبقى «نسخ ثم ترجمة سياقية» */
    fun on(): Boolean = configured() && System.currentTimeMillis() >= coolUntil
    /** (v262) الأوفلاين متظبّط (مفعّل + الموديل نازل) بغض النظر عن فترة الراحة بعد الأعطال. بيحدد وضع الترجمة (سياقية / جوجل)، أما on() فبيحدد مين بينسخ دلوقتي — قبل كده فترة الراحة كانت بتحوّل الترجمة من جوجل لجيميناي وسط الشغل */
    fun configured(): Boolean = Cfg.bool("stt_offline", false) && engine().ready()
    /** حالة الأوفلاين للعرض في الإعدادات */
    fun status(): String = when {
        !WhisperLib.loaded -> "⛔ الأوفلاين مش مدعوم على جهازك (محتاج معالج 64-bit)."
        !downloaded() -> "⬇ الموديل لسه منزّلش (~${model().approxMb} ميجا، مرة واحدة بس)."
        !Cfg.bool("stt_offline", false) -> "الموديل نازل ✅ — الأوفلاين مقفول، جيميناي هو اللي بينسخ."
        else -> "✅ الأوفلاين شغّال: Whisper ${model().id} بينسخ على الجهاز، وجيميناي بيترجم ويكمّل لو الأوفلاين فشل."
    }
    /** طول جزء الترجمة السياقية بالدقايق: 5/10/20، أو 0 لو الأوفلاين مقفول (السلوك القديم) */
    fun partMin(): Int = if (!configured()) 0 else Cfg.int("ctx_min", 3).let { if (it == 1 || it == 3 || it == 5 || it == 10 || it == 20) it else 3 }
    /** (v266) «لحظي»: كل باتش يتفرّغ يتترجم فورًا (ctx_min = 1) */
    fun live(): Boolean = configured() && partMin() == 1

    // ===== (v267) قفل اللغة: أول باتش «مضمون» يحدد لغة الفيديو، وباقي الباتشات بتتفرّغ بيها (Whisper الصغير بيغلط في الاكتشاف التلقائي) =====
    /** حالة قفل اللغة لفيديو واحد (كل Engine عنده واحدة) */
    class Session { @Volatile var lang = ""; @Volatile var badRun = 0 }
    /** true = آخر نداء transcribe على الخيط ده اتنفّذ فعلًا على الجهاز (مش فولباك جيميناي) */
    val ranOffline = ThreadLocal<Boolean>()
    /** اللغة اللي اتفرّغ بيها آخر باتش على الخيط ده ("" = اكتشاف تلقائي) */
    val usedLang = ThreadLocal<String>()

    private fun lettersOf(t: String) = t.filter { it.isLetter() }
    private fun ratio(t: String, f: (Char) -> Boolean): Double { val l = lettersOf(t); return if (l.isEmpty()) 0.0 else l.count(f).toDouble() / l.length }
    /** النص اللي طلع شكله بحروف اللغة المكتشفة؟ (لو Whisper قال «روسي» والنص لاتيني يبقى الاكتشاف غلط) */
    private fun scriptFits(lang: String, text: String): Boolean = when (lang) {
        "ar", "fa", "ur" -> ratio(text) { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFEFF' } > 0.6
        "ru", "uk", "bg" -> ratio(text) { it in '\u0400'..'\u04FF' } > 0.6
        "ko" -> ratio(text) { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' } > 0.5
        "ja" -> ratio(text) { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' } > 0.5
        "zh" -> ratio(text) { it in '\u4E00'..'\u9FFF' } > 0.6
        "hi" -> ratio(text) { it in '\u0900'..'\u097F' } > 0.6
        "en", "fr", "es", "de", "it", "tr", "pt", "nl" -> ratio(text) { it.code < 0x250 } > 0.85
        else -> true
    }

    /** بيقفل اللغة بعد أول نتيجة مضمونة (3 جمل+ · 40 حرف+ · ثقة كويسة · الحروف مطابقة للغة)، وبيفكّها لو النتيجة بقت زبالة مرتين ورا بعض */
    private fun noteLang(sess: Session, res: SttResult, forced: String, log: (String) -> Unit) {
        val good = res.segs.filter { it.text.isNotBlank() && it.logProb > -1.0 && it.noSpeech < 0.5 }
        if (forced.isNotEmpty()) {   // اللغة مقفولة: لو النتيجة وحشة مرتين ورا بعض نرجع للاكتشاف التلقائي
            val avg = if (res.segs.size >= 4) res.segs.map { it.logProb }.average() else 0.0
            if (avg < -1.4) { if (++sess.badRun >= 2) { sess.lang = ""; sess.badRun = 0; log("🌐 الأوفلاين: نتيجة اللغة المقفولة ($forced) وحشة مرتين — رجعت للاكتشاف التلقائي") } } else sess.badRun = 0
            return
        }
        val txt = good.joinToString(" ") { it.text }
        if (res.lang.isEmpty() || good.size < 3 || txt.length < 40 || !scriptFits(res.lang, txt)) return
        sess.lang = res.lang; sess.badRun = 0
        log("🔒 الأوفلاين: لغة الفيديو اتقفلت على (${res.lang}) — باقي الباتشات هتتفرّغ بيها")
    }

    // ===== التفريغ =====
    private val lock = ReentrantLock(true)   // fair: الباتشات بتتفرّغ بترتيب وصولها (واحد في المرة — المعالج مش هيستحمل اتنين)
    @Volatile private var coolUntil = 0L
    @Volatile private var fails = 0

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** ينسخ [wav] (PCM16 mono 16kHz بهيدر 44 بايت) على الجهاز بالمحرك المختار. أي فشل ← [fallback] (طلب جيميناي الأصلي) */
    fun transcribe(wav: ByteArray, durSec: Double, terms: String, log: (String) -> Unit, fallback: () -> Api.Result, warn: (String) -> Unit = {}, sess: Session? = null): Api.Result {
        ranOffline.set(false)
        val eng = engine()
        if (Cfg.str("stt_engine", "whisper") == "sherpa" && eng.id == "whisper") softNotice("🎙 SenseVoice مش بيفهم لغة الكلام المختارة أو مش شغّال على جهازك — Whisper هو اللي بينسخ", warn)   // (v257)
        val tq = System.currentTimeMillis()
        lock.lock()
        try {
            val waited = System.currentTimeMillis() - tq
            if (waited > 5000) log("⏳ الأوفلاين: الباتش استنى دوره ${waited / 1000}ث (التفريغ واحد في المرة)")
            eng.prepare(log)
            val pcm = toFloats(wav) ?: run { log("⚠ الأوفلاين: صيغة الصوت مش مدعومة — جيميناي هو اللي نسخ الباتش"); softNotice("⚠ الأوفلاين: صيغة الصوت مش مدعومة — " + who(), warn); return fallback() }
            val t0 = System.currentTimeMillis()
            log("📴 بدأ التفريغ (${eng.id}): ${"%.0f".format(durSec)}ث صوت · ${threads()} خيوط")   // (v263)
            val manual = lang()
            val locked = if (manual.isEmpty() && eng.id == "whisper") sess?.lang.orEmpty() else ""
            val useLang = manual.ifEmpty { locked }
            usedLang.set(useLang)
            val res = eng.transcribe(pcm, useLang, terms.trim().take(200), threads())
            if (sess != null && eng.id == "whisper") noteLang(sess, res, locked, log)
            val outLang = manual.ifEmpty { if (eng.id == "whisper") sess?.lang.orEmpty() else res.lang }   // (v267) لغة غير مضمونة ما بتتبلّغش للمحرك (كانت بتبوّظ srcLang بـ «ru» لفيديو إنجليزي)
            val took = System.currentTimeMillis() - t0
            log("📴 أوفلاين (${eng.id}${if (eng is SherpaStt) " · VAD=" + SherpaStt.lastVad else ""}): ${"%.0f".format(durSec)}ث صوت ← ${took / 1000}ث (${"%.1f".format(durSec * 1000.0 / took.coerceAtLeast(1))}× سرعة)")
            fails = 0
            ranOffline.set(true)
            return Api.Result(toJson(res, outLang), "STOP")
        } catch (e: SttInitFail) {
            log("⚠ الأوفلاين: ${e.message} — جيميناي هو اللي بينسخ")
            softNotice("⚠ الأوفلاين مش شغّال: ${e.message} — " + who(), warn)
            cool()
            return fallback()
        } catch (e: Throwable) {
            log("⚠ الأوفلاين فشل: ${(e.message ?: e.javaClass.simpleName).take(100)} — جيميناي هو اللي بينسخ الباتش ده")
            if (++fails >= 3) { cool(); fails = 0; log("🛟 الأوفلاين فشل 3 مرات ورا بعض — جيميناي بينسخ 5 دقايق"); softNotice("🛟 الأوفلاين فشل 3 مرات ورا بعض — " + (if (noFallback()) "مفيش مفتاح جيميناي، هكمّل أحاول" else "جيميناي بينسخ 5 دقايق"), warn) }
            else softNotice("⚠ الأوفلاين فشل في باتش — " + (if (noFallback()) "مفيش مفتاح جيميناي، هيتعاد" else "جيميناي نسخه"), warn)
            return fallback()
        } finally { lock.unlock() }
    }

    /** (v259) جيميناي مش موجود يكمّل بدل الأوفلاين لو فشل (مفيش مفتاح حقيقي) */
    private fun noFallback(): Boolean = !TrWizard.hasGeminiKey()
    private fun who(): String = if (noFallback()) "مفيش مفتاح جيميناي يكمّل بدله — الباتش هيتعاد" else "جيميناي بينسخ"
    /** راحة الأوفلاين 5 دقايق بعد الأعطال — إلا لو مفيش مفتاح (مفيش بديل، فنكمّل نحاول) */
    private fun cool() { if (!noFallback()) coolUntil = System.currentTimeMillis() + 300_000L }

    @Volatile private var warnAt = 0L
    /** (v257) إشعار للمستخدم بحد أقصى مرة كل 20 ث — اللوج بيسجّل كل مرة، لكن الإشعار مايفضلش يطلع على كل باتش */
    fun softNotice(msg: String, f: (String) -> Unit) {
        val n = System.currentTimeMillis()
        if (n - warnAt < 20_000L) return
        warnAt = n
        try { f(msg) } catch (_: Throwable) {}
    }

    /** (v257) الأوفلاين مفعّل بس مش شغّال دلوقتي؟ بيرجّع السبب (للإشعار)، أو null لو شغّال أو مقفول أصلًا */
    fun whyOff(): String? = when {
        !Cfg.bool("stt_offline", false) -> null
        System.currentTimeMillis() < coolUntil -> "📴 الأوفلاين مرتاح لحد ما تعدّي 5 دقايق بعد الأعطال — جيميناي هو اللي بينسخ دلوقتي"
        !engine().ready() -> "📴 الأوفلاين مفعّل بس موديل التفريغ مش نازل — جيميناي هو اللي بينسخ. نزّله من الإعدادات ← 🎙 التفريغ والصوت"
        else -> null
    }

    private fun toFloats(b: ByteArray): FloatArray? {
        if (b.size < 46 || b[0] != 'R'.code.toByte()) return null
        val rate = (b[24].toInt() and 255) or ((b[25].toInt() and 255) shl 8) or ((b[26].toInt() and 255) shl 16)
        val ch = (b[22].toInt() and 255)
        if (rate != 16000 || ch != 1) return null
        val n = (b.size - 44) / 2
        if (n < 1600) return null
        val out = FloatArray(n)
        var o = 44
        for (i in 0 until n) { out[i] = ((b[o].toInt() and 255) or (b[o + 1].toInt() shl 8)).toShort() / 32768f; o += 2 }
        return out
    }

    /** نتيجة أي محرك ← نفس JSON جيميناي. بنشيل الأجزاء اللي شكلها هلوسة على صمت/موسيقى/تكرار (مشترك بين المحركات) */
    private fun toJson(res: SttResult, langOut: String): String {
        val out = JSONArray()
        for (g in res.segs) {
            val t = g.text.trim()
            if (t.isEmpty() || t.startsWith("[") && t.endsWith("]") || t.startsWith("(") && t.endsWith(")")) continue   // [موسيقى] (تصفيق) — مش كلام
            if ((g.noSpeech > 0.6 && g.logProb < -0.8) || g.logProb < -1.8) continue
            out.put(JSONObject().put("start", g.start).put("end", g.end).put("original", t)
                .put("gender", "unknown").put("addressee", "unknown").put("topic_gender", "none")
                .put("people", JSONArray()).put("places", JSONArray()).put("is_song", false).put("emotion", "neutral")
                .put("low_confidence", g.logProb < -1.0).put("overlap", false).put("speaker_tag", "").put("is_continuation", false).put("faint", false))
        }
        return JSONObject().put("subtitles", out).apply { if (langOut.isNotEmpty()) put("detected_source_language", langOut) }.toString()
    }

    // ===== تنزيل الموديل (بيكمّل من حيث وقف) =====
    @Volatile private var dlThread: Thread? = null
    @Volatile private var dlCancel = false
    fun downloading(): Boolean = dlThread?.isAlive == true
    fun cancelDownload() { dlCancel = true }

    /** (v255) تنزيل ملف واحد بالاستكمال. [progress] (نزّل، الكلّي) بالبايت. بيرمي IOException عند أي مشكلة */
    private fun fetch(url: String, dst: File, minBytes: Long, progress: (Long, Long) -> Unit) {
        dst.parentFile?.mkdirs()
        val part = File(dst.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000; c.readTimeout = 30000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 416) { have = 0L; part.delete(); throw IOException("اتعاد من الأول — جرّب تاني") }
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            if (code == 200 && have > 0) { have = 0L; part.delete() }   // السيرفر ما دعمش الاستكمال
            val total = have + c.contentLengthLong.coerceAtLeast(0L)
            java.io.FileOutputStream(part, have > 0).use { os ->
                c.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024); var last = 0L
                    while (true) {
                        if (dlCancel) throw IOException("اتلغى")
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); have += n
                        val now = System.currentTimeMillis()
                        if (now - last > 400) { last = now; progress(have, total) }
                    }
                }
            }
            progress(have, have)
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
        if (have < minBytes) throw IOException("التنزيل ناقص — جرّب تاني")
        if (dst.exists()) dst.delete()
        if (!part.renameTo(dst)) throw IOException("مقدرتش أحفظ الملف")
    }

    /** بيشغّل [work] في خيط التنزيل. [done] بتتنده مرة واحدة: null = نجح، غير كده سبب الفشل */
    private fun startDl(done: (String?) -> Unit, work: () -> Unit) {
        if (downloading()) return
        dlCancel = false
        dlThread = Thread {
            var err: String? = null
            try { work() } catch (e: Throwable) { err = (e.message ?: e.javaClass.simpleName).take(120) }
            try { done(err) } catch (_: Throwable) {}
        }.also { it.isDaemon = true; it.start() }
    }

    /** تنزيل موديل Whisper. [progress] (نزّل، الكلّي) بالبايت من خيط التنزيل */
    fun download(m: Model, progress: (Long, Long) -> Unit, done: (String?) -> Unit) {
        if (downloading()) return
        val dst = file(m) ?: run { done("مفيش مساحة تخزين"); return }
        startDl(done) {
            fetch(BASE_URL + m.file, dst, m.approxMb * 600_000L, progress)
            releaseSafe(WhisperStt)
        }
    }

    /** (v255) تنزيل موديل SenseVoice (sherpa-onnx): tokens.txt الصغير الأول وبعده الموديل (~239 ميجا) */
    fun downloadSherpa(progress: (Long, Long) -> Unit, done: (String?) -> Unit) {
        if (downloading()) return
        val tk = SherpaStt.tokensFile(); val md = SherpaStt.modelFile()
        if (tk == null || md == null) { done("مفيش مساحة تخزين"); return }
        startDl(done) {
            if (!(tk.isFile && tk.length() > SherpaStt.MIN_TOKENS)) fetch(SherpaStt.BASE + SherpaStt.TOKENS, tk, SherpaStt.MIN_TOKENS, { _, _ -> })
            if (!(md.isFile && md.length() > SherpaStt.MIN_MODEL)) fetch(SherpaStt.BASE + SherpaStt.MODEL, md, SherpaStt.MIN_MODEL, progress)
            releaseSafe(SherpaStt)
        }
    }

    /** (v261) تحرير الموديل بنفس قفل التفريغ: لو باتش بيتفرّغ دلوقتي بيستنى يخلص، وإلا release() كان ممكن يحرّر الموديل وهو شغّال ← كراش native */
    private fun releaseSafe(e: SttEngine) { lock.lock(); try { e.release() } finally { lock.unlock() } }

    fun deleteSherpa() {
        for (r in roots()) File(r, SherpaStt.SUB).deleteRecursively()   // (v271) الجديد والقديم
        Thread { try { releaseSafe(SherpaStt) } catch (_: Throwable) {} }.also { it.isDaemon = true; it.start() }   // (v261) في خيط: مايعلّقش الشاشة لو باتش بيتفرّغ
    }

    fun delete(m: Model = model()) {
        for (r in roots()) File(File(r, "whisper"), m.file).let { it.delete(); File(it.path + ".part").delete() }   // (v271) الجديد والقديم
        Thread { try { releaseSafe(WhisperStt) } catch (_: Throwable) {} }.also { it.isDaemon = true; it.start() }
    }

    // ===== (v271) نقل الموديلات من filesDir لمكان دائم (بيتعمل مرة واحدة في الخلفية) =====
    @Volatile private var migrating = false
    /** بيدوّر في المكان القديم على موديلات كاملة ويكوّشها للمكان الدائم (نسخ ← تحقق من الحجم ← مسح القديم). أي فشل = بنسيب القديم زي ما هو */
    fun migrateLegacy() {
        val nw = dir ?: return; val old = legacy ?: return
        if (nw.path == old.path || migrating) return
        migrating = true
        Thread {
            try {
                val items = ArrayList<Triple<String, Long, SttEngine>>()
                for (m in models) items.add(Triple("whisper/" + m.file, m.approxMb * 600_000L, WhisperStt))
                items.add(Triple(SherpaStt.SUB + "/" + SherpaStt.MODEL, SherpaStt.MIN_MODEL, SherpaStt))
                items.add(Triple(SherpaStt.SUB + "/" + SherpaStt.TOKENS, SherpaStt.MIN_TOKENS, SherpaStt))
                for ((rel, min, eng) in items) {
                    val src = File(old, rel); val dst = File(nw, rel)
                    if (!src.isFile || src.length() <= min) continue
                    if (dst.isFile && dst.length() == src.length()) { releaseSafe(eng); src.delete(); continue }
                    if (downloading()) continue
                    if ((nw.parentFile ?: nw).usableSpace < src.length() + 100_000_000L) continue
                    dst.parentFile?.mkdirs()
                    val part = File(dst.path + ".mig")
                    try {
                        src.inputStream().use { i -> part.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                        if (part.length() != src.length()) { part.delete(); continue }
                        if (dst.exists()) dst.delete()
                        if (!part.renameTo(dst)) { part.delete(); continue }
                        releaseSafe(eng)   // لو الموديل متحمّل من القديم: اتحرر وهيتحمّل من الجديد
                        src.delete()
                    } catch (_: Throwable) { try { part.delete() } catch (_: Throwable) {} }
                }
            } finally { migrating = false }
        }.also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY; it.start() }
    }
}
