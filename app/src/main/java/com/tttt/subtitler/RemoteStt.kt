package com.tttt.subtitler

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v273) تفريغ الصوت عن طريق API سحابي بصيغة OpenAI (/audio/transcriptions، response_format=verbose_json):
 * OpenAI (whisper-1) · Groq (whisper-large-v3-turbo) · أي خدمة مخصصة. بيستخدم نفس مفتاح ExtLlm بتاع المزوّد.
 * بيشتغل جوه مسار الأوفلاين نفسه (Offline.transcribe): نفس فلتر الهلوسة وتحويل JSON وفولباك جيميناي لو فشل،
 * وبيتفعّل بس لو «rstt_on» شغّال والمفتاح والموديل مضبوطين. ⚠ الصوت بيتبعت لخدمة خارجية.
 */
object RemoteStt : SttEngine {
    override val id = "remote"

    fun on(): Boolean = Cfg.str("rstt_on", "off") == "on"
    fun provider(): ExtLlm.Preset? = ExtLlm.byId(Cfg.str("rstt_prov", "groq"))?.takeIf { ExtLlm.supportsStt(it) }
    /** مفعّل وجاهز */
    fun active(): Boolean = on() && ready()

    override fun supported() = true
    override fun ready(): Boolean {
        val p = provider() ?: return false
        return ExtLlm.key(p).isNotEmpty() && ExtLlm.url(p).isNotEmpty() && ExtLlm.sttModel(p).isNotEmpty()
    }
    override fun handles(lang: String) = true
    override fun prepare(log: (String) -> Unit) { if (!ready()) throw SttInitFail("التفريغ السحابي: المفتاح أو موديل التفريغ ناقص") }
    override fun release() {}

    private val langCodes = mapOf("english" to "en", "japanese" to "ja", "korean" to "ko", "chinese" to "zh", "arabic" to "ar", "french" to "fr",
        "spanish" to "es", "turkish" to "tr", "russian" to "ru", "german" to "de", "italian" to "it", "hindi" to "hi", "persian" to "fa", "urdu" to "ur")

    private fun wavOf(pcm: FloatArray): ByteArray {
        val n = pcm.size; val dataLen = n * 2
        val b = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataLen); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen)
        for (f in pcm) b.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }

    override fun transcribe(pcm: FloatArray, lang: String, terms: String, threads: Int): SttResult {
        val p = provider() ?: throw SttInitFail("التفريغ السحابي: مفيش مزوّد مختار")
        val boundary = "----subtitler" + System.currentTimeMillis()
        val bo = ByteArrayOutputStream()
        fun fld(name: String, value: String) { bo.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8)) }
        fld("model", ExtLlm.sttModel(p)); fld("response_format", "verbose_json"); fld("temperature", "0")
        if (lang.isNotBlank()) fld("language", lang.substringBefore('-'))
        if (terms.isNotBlank()) fld("prompt", terms.take(200))
        bo.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8))
        bo.write(wavOf(pcm)); bo.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        val body = bo.toByteArray()

        val c = URL(ExtLlm.url(p) + "/audio/transcriptions").openConnection() as HttpURLConnection
        var txt = ""
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 120000
            c.setRequestProperty("Authorization", "Bearer " + ExtLlm.key(p))
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            NetUse.api(body.size.toLong(), txt)
            if (code in 401..403) throw SttInitFail(p.label + ": " + ExtLlm.errMsg(code, txt))
            if (code !in 200..299) throw java.io.IOException(p.label + ": " + ExtLlm.errMsg(code, txt))
        } finally { try { c.disconnect() } catch (_: Throwable) {} }

        val j = JSONObject(txt)
        val l = j.optString("language", "").lowercase().trim().let { langCodes[it] ?: if (it.length in 2..3) it else "" }
        val segs = ArrayList<SttSeg>()
        val arr = j.optJSONArray("segments")
        if (arr != null) for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val t = s.optString("text", "").trim(); if (t.isEmpty()) continue
            segs.add(SttSeg(s.optDouble("start", 0.0), s.optDouble("end", 0.0), t, s.optDouble("no_speech_prob", 0.0), s.optDouble("avg_logprob", 0.0)))
        }
        if (segs.isEmpty()) { val t = j.optString("text", "").trim(); if (t.isNotEmpty()) segs.add(SttSeg(0.0, pcm.size / 16000.0, t)) }
        return SttResult(segs, l)
    }
}
