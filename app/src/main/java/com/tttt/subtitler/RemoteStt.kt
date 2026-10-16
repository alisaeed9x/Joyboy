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
 *
 * (v369) أومني روت (localhost:20128/v1/audio/transcriptions): من غير مفتاح لو شغّال كده، والصوت بيترفع AAC (أصغر ~8 مرات) ولو رفضه بيرجع WAV لوحده،
 * ولو الرد نص بس من غير توقيتات (Deepgram / AssemblyAI) النص بيتقسّم لجمل والتوقيت بيتوزّع بالتناسب على مدة المقطع (تقريبي).
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
        return ExtLlm.hasAccess(p) && ExtLlm.url(p).isNotEmpty() && ExtLlm.sttModel(p).isNotEmpty()
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

    /** خطأ HTTP من خدمة التفريغ (الكود محتاجينه عشان نقرر نجرّب صيغة تانية ولا لأ) */
    private class HttpErr(val code: Int, msg: String) : java.io.IOException(msg)

    /** طريقة رفع لأومني روت: الصوت (AAC أو WAV) + هل نطلب verbose_json */
    private class Variant(val name: String, val bytes: ByteArray, val fname: String, val mime: String, val verbose: Boolean)
    @Volatile private var omniOk = ""   // آخر طريقة نجحت — بتتجرّب الأول في الباتش اللي بعده

    override fun transcribe(pcm: FloatArray, lang: String, terms: String, threads: Int): SttResult {
        val p = provider() ?: throw SttInitFail("التفريغ السحابي: مفيش مزوّد مختار")
        val wav = wavOf(pcm)
        val dur = pcm.size / 16000.0
        if (p.id != "omniroute") return parse(upload(p, "audio.wav", "audio/wav", wav, lang, terms, true), dur)

        val vs = ArrayList<Variant>()
        if (AudioEnc.preset() == AudioEnc.FAST) {
            val pk = try { AudioEnc.pack(wav) } catch (_: Throwable) { null }
            if (pk != null && pk.mime == "audio/aac") vs.add(Variant("aac", pk.bytes, "audio.aac", "audio/aac", true))
        }
        vs.add(Variant("wav", wav, "audio.wav", "audio/wav", true))
        vs.add(Variant("wavmin", wav, "audio.wav", "audio/wav", false))
        val ordered = vs.sortedBy { if (it.name == omniOk) 0 else 1 }
        var last: java.io.IOException? = null
        for (v in ordered) {
            try {
                val txt = upload(p, v.fname, v.mime, v.bytes, lang, terms, v.verbose)
                omniOk = v.name
                return parse(txt, dur)
            } catch (e: HttpErr) {
                last = e
                if (e.code !in 400..499) throw e   // 5xx: مش مشكلة صيغة
            }
        }
        throw last ?: java.io.IOException(p.label + ": فشل التفريغ")
    }

    private fun upload(p: ExtLlm.Preset, fname: String, mime: String, audio: ByteArray, lang: String, terms: String, verbose: Boolean): String {
        val boundary = "----subtitler" + System.currentTimeMillis()
        val bo = ByteArrayOutputStream()
        fun fld(name: String, value: String) { bo.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8)) }
        fld("model", ExtLlm.sttModel(p))
        if (verbose) { fld("response_format", "verbose_json"); fld("temperature", "0") }
        if (lang.isNotBlank()) fld("language", lang.substringBefore('-'))
        if (terms.isNotBlank()) fld("prompt", terms.take(200))
        bo.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fname\"\r\nContent-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8))
        bo.write(audio); bo.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        val body = bo.toByteArray()

        val c = URL(ExtLlm.url(p) + "/audio/transcriptions").openConnection() as HttpURLConnection
        var txt = ""
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 120000
            ExtLlm.key(p).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            NetUse.api(body.size.toLong(), txt)
            if (code in 401..403) throw SttInitFail(p.label + ": " + ExtLlm.errMsg(code, txt))
            if (code !in 200..299) throw HttpErr(code, p.label + ": " + ExtLlm.errMsg(code, txt))
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
        return txt
    }

    private fun parse(txt: String, dur: Double): SttResult {
        val j = JSONObject(txt)
        val l = j.optString("language", "").lowercase().trim().let { langCodes[it] ?: if (it.length in 2..3) it else "" }
        val segs = ArrayList<SttSeg>()
        val arr = j.optJSONArray("segments")
        if (arr != null) for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val t = s.optString("text", "").trim(); if (t.isEmpty()) continue
            segs.add(SttSeg(s.optDouble("start", 0.0), s.optDouble("end", 0.0), t, s.optDouble("no_speech_prob", 0.0), s.optDouble("avg_logprob", 0.0)))
        }
        if (segs.isEmpty()) { val t = j.optString("text", "").trim(); if (t.isNotEmpty()) segs.addAll(evenSplit(t, dur)) }
        return SttResult(segs, l)
    }

    /** (v369) نص من غير توقيتات: جمل بعلامات الوقف (قصير بيتدمج، طويل بيتقطع) والتوقيت بالتناسب مع عدد الحروف على مدة المقطع */
    private fun evenSplit(text: String, dur: Double): List<SttSeg> {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return emptyList()
        val raw = Regex("[^.!?؟。！？…]+[.!?؟。！？…]*").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val parts = ArrayList<String>()
        var carry = ""
        for (r in raw) {
            val c = if (carry.isEmpty()) r else "$carry $r"
            if (c.length < 14) { carry = c; continue }
            carry = ""
            var rest = c
            while (rest.length > 110) {
                var cut = rest.lastIndexOf(' ', 100)
                val comma = maxOf(rest.lastIndexOf(',', 100), rest.lastIndexOf('،', 100))
                if (comma > 40) cut = comma + 1
                if (cut < 30) cut = 100
                parts.add(rest.substring(0, cut).trim()); rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) parts.add(rest)
        }
        if (carry.isNotEmpty()) { if (parts.isEmpty()) parts.add(carry) else parts[parts.size - 1] = parts[parts.size - 1] + " " + carry }
        val total = parts.sumOf { it.length }.coerceAtLeast(1)
        val out = ArrayList<SttSeg>(); var acc = 0.0
        for (s in parts) { val d = dur * s.length / total; out.add(SttSeg(acc, acc + d, s)); acc += d }
        return out
    }
}
