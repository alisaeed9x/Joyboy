package com.tttt.subtitler

import java.io.File
import java.io.IOException

/**
 * (v569) sherpa-onnx / SenseVoice اتشالوا من التطبيق (المكتبة كانت بتزوّد الـ APK ~100 ميجا).
 * الكائن ده فاضل كواجهة فاضية عشان باقي الكود (Offline / TrWizard / الإعدادات) يفضل يتبني من غير تعديل،
 * وكل الدوال بترجّع «مش مدعوم» ← Offline.engine() بيرجع Whisper دايمًا.
 */
object SherpaStt : SttEngine {
    override val id = "sherpa"
    const val SUB = "sherpa/sensevoice"
    const val MODEL = "model.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val MODEL_MB = 239
    const val MIN_MODEL = 200_000_000L
    const val MIN_TOKENS = 100_000L
    const val BASE = ""
    val preferred: Set<String> = emptySet()

    @Volatile var lastVad = "—"

    fun dir(): File? = null
    fun modelFile(): File? = null
    fun tokensFile(): File? = null
    fun downloaded(): Boolean = false
    fun status(): String = "SenseVoice اتشال من التطبيق (استخدم Whisper)"

    override fun supported() = false
    override fun ready() = false
    override fun handles(lang: String) = false
    override fun prepare(log: (String) -> Unit) { throw SttInitFail("SenseVoice اتشال من التطبيق") }
    override fun transcribe(pcm: FloatArray, lang: String, terms: String, threads: Int): SttResult = throw IOException("SenseVoice اتشال من التطبيق")
    override fun release() {}
}
