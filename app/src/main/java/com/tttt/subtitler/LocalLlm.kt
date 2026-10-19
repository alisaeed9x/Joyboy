package com.tttt.subtitler

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/**
 * (v420) موديل ترجمة محلي على الموبايل (أوفلاين كامل): Gemma 4 E2B (~2.6 جيجا، Apache-2.0) على LiteRT-LM.
 * بيتنزّل مرة واحدة (بيكمّل من حيث وقف) في نفس مكان موديلات Whisper، والمحرك بيتحمّل أول طلب وبيتحرّر لوحده بعد دقيقتين خمول (عشان الرام).
 * الإعدادات: vl_local_gpu = on|off (الافتراضي off — المعالج أأمن) · vl_local_reflect = on|off (الافتراضي off) · vl_local_summary = on|off (الافتراضي off)
 */
object LocalLlm {
    const val FILE = "gemma-4-E2B-it.litertlm"
    private const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$FILE"
    private const val MIN_BYTES = 2_300_000_000L
    const val APPROX_MB = 2600

    private val lock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var busy = false
    @Volatile private var lastUse = 0L
    @Volatile private var watcher: Thread? = null
    @Volatile private var dlThread: Thread? = null

    /** الجهاز arm64؟ (LiteRT-LM ملوش نسخة 32-bit مفيدة هنا) */
    fun supported(): Boolean = try { android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } } catch (_: Throwable) { false }

    fun file(): File? = Offline.pick("llm/$FILE", MIN_BYTES)
    fun ready(): Boolean = file()?.let { it.isFile && it.length() > MIN_BYTES } ?: false
    fun downloading(): Boolean = dlThread?.isAlive == true

    /** تنزيل الموديل. [progress] (نزّل، الكلّي) بالبايت · [done] null = نجح */
    fun download(progress: (Long, Long) -> Unit, done: (String?) -> Unit) {
        if (downloading()) return
        val dst = file() ?: run { done("مفيش مساحة تخزين"); return }
        dlThread = Thread {
            var err: String? = null
            try {
                val dir = dst.parentFile
                dir?.mkdirs()
                if (dir != null && dir.usableSpace < 3_000_000_000L) throw java.io.IOException("المساحة الفاضية أقل من 3 جيجا")
                Offline.fetch(URL, dst, MIN_BYTES, progress)
            } catch (e: Throwable) { err = (e.message ?: e.javaClass.simpleName).take(120) }
            try { done(err) } catch (_: Throwable) {}
        }.also { it.isDaemon = true; it.start() }
    }

    fun delete() {
        release()
        for (r in Offline.roots()) File(File(r, "llm"), FILE).let { it.delete(); File(it.path + ".part").delete() }
    }

    private fun startWatcher() {
        if (watcher?.isAlive == true) return
        watcher = Thread {
            while (true) {
                try { Thread.sleep(20_000L) } catch (_: InterruptedException) { return@Thread }
                if (engine == null) { watcher = null; return@Thread }
                if (!busy && System.currentTimeMillis() - lastUse > 120_000L) { release(); watcher = null; return@Thread }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun release() {
        synchronized(lock) {
            try { engine?.close() } catch (_: Throwable) {}
            engine = null
        }
    }

    private fun engineNow(): Engine {
        engine?.let { return it }
        val f = file()?.takeIf { it.isFile && it.length() > MIN_BYTES } ?: throw ExtLlm.LlmErr(404, "موديل Gemma المحلي مش نازل")
        if (!supported()) throw ExtLlm.LlmErr(400, "الموديل المحلي محتاج جهاز arm64")
        val cache = try { TrWizard.appCtx?.cacheDir?.path } catch (_: Throwable) { null }
        val be = if (Cfg.str("vl_local_gpu", "off") == "on") Backend.GPU() else Backend.CPU()
        val e = Engine(EngineConfig(modelPath = f.path, backend = be, cacheDir = cache))
        e.initialize()
        engine = e
        return e
    }

    /** طلب واحد للموديل المحلي: (system, user, أقصى توكنز) → النص. بيشتغل على خيط خلفي (بيتحمّل الموديل أول مرة ~10 ثواني) */
    fun chat(system: String, user: String, maxTok: Int): String = synchronized(lock) {
        busy = true
        try {
            val e = engineNow()
            val conf = ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3),
                maxOutputToken = maxTok
            )
            val out = e.createConversation(conf).use { c -> c.sendMessage(user).toString() }
            out.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        } finally {
            lastUse = System.currentTimeMillis(); busy = false
            startWatcher()
        }
    }
}
