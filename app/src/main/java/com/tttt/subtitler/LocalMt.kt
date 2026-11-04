package com.tttt.subtitler

import java.io.File

/** (v696) جسر JNI لـ llama.cpp (cpp/llm_jni.cpp) — جوه نفس المكتبة subwhisper (ggml واحدة) */
object LlmLib {
    @Volatile var loaded = false; private set
    init { try { System.loadLibrary("subwhisper"); loaded = true } catch (_: Throwable) {} }
    @JvmStatic external fun nInit(path: String, nCtx: Int): Long
    @JvmStatic external fun nFree(h: Long)
    @JvmStatic external fun nTranslate(h: Long, user: String, threads: Int, maxNew: Int): ByteArray
    /** (v744) system + user + فك greedy (طريقة SubMaster) */
    @JvmStatic external fun nChat(h: Long, system: String, user: String, threads: Int, maxNew: Int): ByteArray
    /** (v747) البيلد فيه kernel STQ1_0 (موديل 1.25-bit)؟ */
    @JvmStatic external fun nStq(): Boolean
    val stq: Boolean by lazy { try { loaded && nStq() } catch (_: Throwable) { false } }
}

/**
 * (v744) 📴 ترجمة أوفلاين كاملة بـ HY-MT1.5 (Tencent) بطريقة SubMaster — (v696 كان Hy-MT2) على الجهاز — من غير نت ولا مفاتيح ولا سيرفر. الناتج فصحى.
 * الملف برّه الـAPK: بيتنزّل مرة (من الرابط في المتصفح أو من التطبيق) ويتختار بـ ModelStore أو يتحط في Android/media.
 * البرومبت والإعدادات من كارت Tencent الرسمي: من غير system prompt · temperature 0.7 · top_p 0.6 · top_k 20 · repeat 1.05.
 * ما اتجرّبش على جهاز: السرعة الحقيقية (توكن/ث) والجودة على العربي لازم تتقاس.
 */
object LocalMt {
    /** needStq = محتاج بيلد فيه kernel STQ1_0 (JOY_STQ=1) */
    class Model(val id: String, val label: String, val file: String, val approxMb: Int, val note: String, val repo: String, val owner: String = "tencent", val needStq: Boolean = false)
    val models = listOf(
        Model("small", "📴 HY-MT1.5 · 1.8B", "HY-MT1.5-1.8B-Q4_K_M.gguf", 1130, "للعربي بس — Q4_K_M ~1.1GB", "HY-MT1.5-1.8B-GGUF"),   // (v745) الأساسي
        Model("q125", "🪶 HY-MT1.5 · 1.25-bit (~440 ميجا)", "Hy-MT1.5-1.8B-1.25bit.gguf", 440, "الأصغر — نفس الموديل مضغوط 1.25-bit (QAT). محتاج بيلد خاص (JOY_STQ=1) وما اتجرّبش على جهاز؛ الجودة على العربي لازم تتقاس", "Hy-MT1.5-1.8B-1.25bit-GGUF", "AngelSlim", true)   // (v747)
    )
    /** رابط المتصفح (للتنزيل اليدوي) */
    fun url(m: Model = model()): String = "https://huggingface.co/" + m.owner + "/" + m.repo + "/resolve/main/" + m.file

    fun model(): Model = models.firstOrNull { it.id == Cfg.str("mt_model", "small") } ?: models[0]
    fun file(m: Model = model()): File? = Offline.pick("mt/" + m.file, m.approxMb * 600_000L)
    fun downloaded(m: Model = model()): Boolean = file(m)?.let { it.isFile && it.length() > m.approxMb * 600_000L } ?: false
    fun supported(): Boolean = LlmLib.loaded
    /** (v747) الموديل المختار يشتغل على البيلد ده؟ (الصغير محتاج kernel خاص) */
    fun usable(m: Model = model()): Boolean = supported() && (!m.needStq || LlmLib.stq)
    fun ready(): Boolean = usable() && downloaded()

    // لغات المصدر ← الاسم الإنجليزي الكامل (Tencent بتطلب الاسم الكامل)
    private val names = mapOf("ja" to "Japanese", "en" to "English", "ko" to "Korean", "zh" to "Chinese", "fr" to "French", "es" to "Spanish",
        "de" to "German", "it" to "Italian", "ru" to "Russian", "tr" to "Turkish", "hi" to "Hindi", "fa" to "Persian", "ur" to "Urdu", "ar" to "Arabic")

    @Volatile private var h = 0L
    @Volatile private var loadedFile = ""
    private val lock = Any()

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(3, 4)

    /** بيحمّل الموديل لو لسه. بيرمي SttInitFail لو فشل */
    fun prepare(log: (String) -> Unit) = synchronized(lock) {
        val f = file() ?: throw SttInitFail("مفيش مساحة تخزين")
        if (!usable()) throw SttInitFail("الموديل الصغير (1.25-bit) محتاج بيلد خاص (JOY_STQ=1) — اختار النسخة 1.8B العادية")
        if (!ready()) throw SttInitFail("موديل الترجمة مش موجود — نزّله أو اختاره من الإعدادات")
        if (h != 0L && loadedFile == f.path) return@synchronized
        releaseLocked()
        val t0 = System.currentTimeMillis()
        Offline.loadBegin(model().id, model().approxMb, "HY-MT1.5")   // (v700) مؤشر تحميل موديل الترجمة
        try { h = LlmLib.nInit(f.path, 4096) } finally { Offline.loadEnd(h != 0L) }
        if (h == 0L) throw SttInitFail("موديل الترجمة ما اتحمّلش")
        loadedFile = f.path
        log("📴 موديل الترجمة اتحمّل في ${System.currentTimeMillis() - t0}ms")
    }

    private fun releaseLocked() { if (h != 0L) { LlmLib.nFree(h); h = 0L; loadedFile = "" } }
    /** تحرير الرام (بعد التفريغ/الترجمة أو قبل تحميل Whisper الكبير) */
    fun release() = synchronized(lock) { releaseLocked() }
    fun isLoaded(): Boolean = h != 0L

    // ===== (v744) طريقة SubMaster: باتشات جمل بعلامات [[[cue:N]]] · حدود باتش واعية بنهاية الجملة · system prompt · greedy · سطر سطر لو الرد اتبوّظ =====
    private const val MAX_CUES = 4
    private const val MAX_CUES_SMALL = 2      // 1.8B أضعف في الحفاظ على العلامات
    private const val MAX_CHARS = 1600
    private val SENT_END = Regex("""[.!?…。！？]+["')\]»”’]*$""")
    private val CLAUSE_END = Regex("""[,;:，؛；、]+["')\]»”’]*$""")
    private val CONT_START = Regex("""^[,;:，；、.!?。！？)\]»”’]""")
    private val LOWER_START = Regex("""^[a-zà-öø-ÿ]""")
    private val CUE_RE = Regex("""\[\[\[cue:(\d+)\]\]\]\s*(.*?)\s*\[\[\[/cue\]\]\]""", RegexOption.DOT_MATCHES_ALL)
    private val CUE_START = Regex("""\[\[\[cue:(\d+)\]\]\]\s*""")

    private fun maxCues(): Int = if (model().id == "small" || model().id == "q125") MAX_CUES_SMALL else MAX_CUES

    private fun score(t: List<String>, a: Int, e: Int, chars: Int): Double {
        var s = chars.toDouble()
        val n = e - a + 1
        val cur = t[e]; val next = if (e + 1 < t.size) t[e + 1] else ""
        val soft = maxOf(1, (MAX_CHARS * 0.7).toInt())
        s += n * 120
        if (chars >= soft) s += 200
        if (SENT_END.containsMatchIn(cur)) s += 3000
        else if (CLAUSE_END.containsMatchIn(cur)) s += 1200
        else if (e + 1 < t.size) s -= 900
        if (next.isNotEmpty()) {
            val cont = CONT_START.containsMatchIn(next) || LOWER_START.containsMatchIn(next)
            if (cont) s -= 2500 else if (next[0].isUpperCase() || next[0].isDigit()) s += 500
        }
        if (n == 1 && e + 1 < t.size && chars < soft) s -= 700
        return s
    }

    /** بيقسّم الجمل لباتشات (فهارس) بنفس منطق SubMaster: يفضّل يقفل على نهاية جملة/عبارة ويتجنّب يقطع جملة في النص */
    private fun batches(t: List<String>): List<IntRange> {
        val out = ArrayList<IntRange>(); var start = 0
        val cap = maxCues()
        while (start < t.size) {
            var best = start; var bestScore = Double.NEGATIVE_INFINITY; var chars = 0
            for (e in start until minOf(t.size, start + cap)) {
                val c = t[e].length
                if (e > start && chars + c > MAX_CHARS) break
                chars += c
                val sc = score(t, start, e, chars)
                if (sc > bestScore) { bestScore = sc; best = e }
            }
            out.add(start..best); start = best + 1
        }
        return out
    }

    private fun systemPrompt(src: String): String {
        val srcName = names[src]
        val zh = src == "zh"
        val sb = StringBuilder()
        if (zh) sb.append("请将用户提供的字幕翻译成阿拉伯语（现代标准阿拉伯语）。严格保留每个[[[cue:N]]]与[[[/cue]]]标记，不要新增说明。不要合并、删除或重排任何字幕块。只输出翻译后的字幕块，并保持原有顺序。")
        else sb.append("Translate the user's subtitle cues into Arabic (Modern Standard Arabic). Preserve every [[[cue:N]]] and [[[/cue]]] marker exactly. Do not add explanations. Do not merge, remove, or reorder any cue. Output only the translated cues in the same order.")
        if (srcName != null) sb.append(" Source language: ").append(srcName).append('.')
        return sb.toString()
    }

    private fun chat(sys: String, user: String, maxNew: Int): String {
        val raw = synchronized(lock) {
            if (h == 0L) throw SttInitFail("موديل الترجمة مش متحمّل")
            String(LlmLib.nChat(h, sys, user, threads(), maxNew), Charsets.UTF_8)
        }
        if (raw.startsWith("ERR")) throw java.io.IOException(raw.replace('\t', ' '))
        return raw
    }

    private fun payload(lines: List<String>): String = lines.withIndex().joinToString("\n\n", postfix = "\n") { (i, l) -> "[[[cue:" + (i + 1) + "]]]\n" + l + "\n[[[/cue]]]" }

    /** رد الباتش ← نص كل جملة بترتيبها، أو null لو العلامات اتبوّظت (ناقص/مكرر/ids غلط) */
    private fun parseBatch(raw: String, n: Int): List<String>? {
        val m = CUE_RE.findAll(raw).toList()
        if (m.size != n) return null
        val got = HashMap<Int, String>()
        for (x in m) { val id = x.groupValues[1].toIntOrNull() ?: return null; if (got.containsKey(id)) return null; got[id] = x.groupValues[2].trim() }
        if ((1..n).any { !got.containsKey(it) }) return null
        return (1..n).map { got[it]!! }
    }

    /** جملة واحدة لما الباتش يفشل: نفس العلامات، وبعدها قراءة متسامحة لو علامة القفل ناقصة */
    private fun single(text: String, sys: String): String {
        val raw = chat(sys, payload(listOf(text)), (text.length * 3 + 64).coerceIn(64, 600))
        parseBatch(raw, 1)?.let { return it[0] }
        var t = raw.trim()
        val st = CUE_START.find(t)
        if (st != null) { if (st.groupValues[1] != "1") return text; t = t.substring(st.range.last + 1).trim() }
        if (t.contains("[[[cue:") || t.contains("[[[/cue]]]")) return text
        return t.ifBlank { text }
    }

    /** جملة واحدة ← عربي (فصحى) */
    fun translate(text: String, src: String = ""): String {
        val t = text.trim(); if (t.isEmpty()) return ""
        prepare { }
        return single(t, systemPrompt(src))
    }

    /** قايمة جمل ← قايمة ترجمات بنفس الترتيب. جملة/باتش فشل = الجملة بأصلها (مش بتوقّف الباقي). [onProgress] (خلص، الكلّي) */
    fun translateAll(lines: List<String>, src: String, log: (String) -> Unit, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<String> {
        prepare(log)
        val sys = systemPrompt(src)
        val clean = lines.map { it.replace(Regex("\\s*\n\\s*"), " ").trim() }
        val out = ArrayList<String>(lines)
        val bs = batches(clean)
        var bad = 0; var fallbackWarned = false; var done = 0
        for (r in bs) {
            val items = r.map { clean[it] }
            if (items.all { it.isEmpty() }) { done += items.size; onProgress(done, lines.size); continue }
            val tr: List<String>? = try {
                val raw = chat(sys, payload(items), (items.sumOf { it.length } * 3 + items.size * 40 + 64).coerceIn(96, 1536))
                parseBatch(raw, items.size)
            } catch (e: Throwable) { bad++; if (bad <= 3) log("⚠ باتش ترجمة فشل: " + (e.message ?: "").take(80)); null }
            if (tr != null) { for ((q, k) in r.withIndex()) if (tr[q].isNotBlank()) out[k] = tr[q] }
            else {
                if (!fallbackWarned) { fallbackWarned = true; log("↪ ردود الباتشات مش سليمة أحيانًا — بنعيد الجمل سطر سطر") }
                for (k in r) {
                    if (clean[k].isEmpty()) continue
                    out[k] = try { single(clean[k], sys) } catch (e: Throwable) { bad++; if (bad <= 3) log("⚠ ترجمة جملة فشلت: " + (e.message ?: "").take(80)); lines[k] }
                }
            }
            done += items.size; onProgress(done, lines.size)
        }
        return out
    }

    fun status(): String = when {
        !LlmLib.loaded -> "⛔ الترجمة الأوفلاين مش مدعومة على جهازك (محتاجة معالج 64-bit)."
        !usable() -> "⛔ الموديل الصغير (1.25-bit) محتاج بيلد خاص من التطبيق (JOY_STQ=1) — النسخة دي من غيره. اختار 1.8B العادية."
        !downloaded() -> "⬇ موديل الترجمة لسه مش موجود (~${model().approxMb} ميجا، مرة واحدة)."
        else -> "✅ ترجمة أوفلاين: HY-MT1.5 ${model().id} على الجهاز."
    }
}
