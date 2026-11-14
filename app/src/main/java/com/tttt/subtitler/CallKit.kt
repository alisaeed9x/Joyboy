package com.tttt.subtitler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * (v994) أدوات التفريغ الذكي للمكالمات والحوارات:
 *  • «بصمة الصوت»: مقطع مرجعي قصير (~7 ثواني) لكل شخص بيتحفظ على الجهاز، وبيتلزق قبل الصوت وقت التفريغ
 *    عشان Gemini يطابق الصوت بالمرجع. (مش بصمة بيومترية حقيقية — مطابقة سمعية بالمقارنة؛ بتتأثر بجودة التسجيل.)
 *  • أسماء من جهات الاتصال: بتتقري من اسم ملف تسجيل المكالمة (رقم أو اسم) وبتتحوّل لاسم الشخص من الجهات.
 *  • لزق WAV: مقاطع مرجعية + المقطع الأصلي في ملف واحد، مع حساب إزاحة التوقيت.
 */
object CallKit {
    // ===================== WAV =====================
    private const val GAP_SEC = 0.6

    fun rateOf(wav: ByteArray): Int =
        if (wav.size >= 28) (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24) else 16000

    fun pcmOf(wav: ByteArray): ByteArray = if (wav.size > 44) wav.copyOfRange(44, wav.size) else ByteArray(0)

    fun wavOf(pcm: ByteArray, rate: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        System.arraycopy(Wav.header(pcm.size, rate), 0, out, 0, 44)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    fun silencePcm(sec: Double, rate: Int): ByteArray = ByteArray(((sec * rate).toInt().coerceAtLeast(0)) * 2)

    fun durOf(wav: ByteArray): Double { val r = rateOf(wav).coerceAtLeast(1); return pcmOf(wav).size / 2.0 / r }

    /** wav = المقاطع المرجعية + فاصل + الأصلي · offset = ثانية بداية الأصلي جوه الملف المدموج · ranges = مكان كل مرجع */
    class Mixed(val wav: ByteArray, val offset: Double, val ranges: List<Pair<Double, Double>>)

    fun mix(main: ByteArray, refs: List<ByteArray>): Mixed {
        if (refs.isEmpty()) return Mixed(main, 0.0, emptyList())
        val rate = rateOf(main).coerceAtLeast(8000)
        val out = ByteArrayOutputStream()
        val ranges = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        for (r in refs) {
            val p = pcmOf(r)
            val d = p.size / 2.0 / rate
            out.write(p)
            ranges.add(t to (t + d))
            t += d
            val gap = silencePcm(GAP_SEC, rate); out.write(gap); t += GAP_SEC
        }
        out.write(pcmOf(main))
        return Mixed(wavOf(out.toByteArray(), rate), t, ranges)
    }

    // ===================== بصمات الصوت (مقاطع مرجعية) =====================
    private fun voiceDir(ctx: Context) = File(ctx.filesDir, "voices").also { it.mkdirs() }
    private fun idxFile(ctx: Context) = File(voiceDir(ctx), "index.json")

    fun contactKey(name: String): String = "c_" + java.lang.Long.toHexString(name.trim().lowercase().hashCode().toLong() and 0xffffffffL)

    @Synchronized private fun readIdx(ctx: Context): JSONObject = try { val f = idxFile(ctx); if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject() } catch (_: Throwable) { JSONObject() }

    @Synchronized fun saveVoice(ctx: Context, key: String, name: String, wav: ByteArray) {
        try {
            File(voiceDir(ctx), "$key.wav").writeBytes(wav)
            val j = readIdx(ctx); j.put(key, JSONObject().put("name", name).put("ts", System.currentTimeMillis()))
            idxFile(ctx).writeText(j.toString(), Charsets.UTF_8)
        } catch (e: Throwable) { LogStore.err("CallKit:saveVoice", e) }
    }

    fun voice(ctx: Context, key: String): ByteArray? = try { val f = File(voiceDir(ctx), "$key.wav"); if (f.exists() && f.length() > 44) f.readBytes() else null } catch (_: Throwable) { null }

    fun hasVoice(ctx: Context, key: String): Boolean = File(voiceDir(ctx), "$key.wav").exists()

    // ===================== جهات الاتصال =====================
    class Who(val name: String, val number: String)

    fun hasContactsPerm(ctx: Context): Boolean = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private val PREFIX = Regex("(?i)(call\\s*recording|recorded\\s*call|voice\\s*call|call\\s*with|incoming|outgoing|call|تسجيل\\s*المكالمة|تسجيل\\s*مكالمة|مكالمة\\s*مع|مكالمة|تسجيل)")
    private val DATE_START = Regex("^(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{0,8}$")
    private val IO_WORDS = Regex("(?i)\\b(in|out|inc|outg)\\b")

    /** يطلّع رقم/اسم من اسم ملف تسجيل مكالمة (سامسونج · جوجل · شاومي · ACR…) — من غير جهات اتصال */
    fun parseFileName(fileName: String): Who {
        val base = fileName.replace(Regex("(?i)\\.(m4a|mp3|wav|aac|amr|ogg|opus|3gp|mp4|mkv|flac|awb)$"), "")
        var number = ""
        for (m in Regex("\\+?\\d[\\d\\s\\-()]{5,}\\d").findAll(base)) {
            val digits = m.value.filter { it.isDigit() || it == '+' }
            val n = digits.count { it.isDigit() }
            if (n !in 7..15) continue
            if (DATE_START.matches(digits.removePrefix("+"))) continue
            number = digits; break
        }
        var nm = base
        nm = nm.replace(Regex("\\+?\\d[\\d\\s\\-()]{5,}\\d"), " ")
        nm = nm.replace(Regex("\\d+"), " ")
        nm = nm.replace(PREFIX, " ").replace(IO_WORDS, " ")
        nm = nm.replace(Regex("[_\\-()\\[\\]{}.,]"), " ").replace(Regex("\\s+"), " ").trim()
        if (nm.count { it.isLetter() } < 2) nm = ""
        return Who(nm, number)
    }

    /** لو فيه إذن جهات الاتصال: الرقم ← اسم الشخص المسجّل. وإلا بيرجّع اللي اتقرا من الاسم */
    fun resolve(ctx: Context, fileName: String): Who {
        val w = parseFileName(fileName)
        if (w.number.isBlank() || !hasContactsPerm(ctx)) return w
        return try {
            val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(w.number))
            val found = ctx.contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (!found.isNullOrBlank()) Who(found.trim(), w.number) else w
        } catch (e: Throwable) { LogStore.err("CallKit:lookup", e); w }
    }
}
