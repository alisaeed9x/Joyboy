package com.tttt.subtitler

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.util.Collections
import java.util.WeakHashMap

/**
 * (v166) ضغط الصوت قبل الإرسال: WAV 16kHz mono (≈32KB/ث) → AAC-LC ADTS (≈4KB/ث) = أصغر بحوالي 8 مرات.
 * بيتعمل بـ MediaCodec الموجود في أي أندرويد (من غير مكتبات). لو أي حاجة فشلت بنرجع للـ WAV زي ما كان (مفيش خسارة).
 * الـ WAV الأصلي فاضل في الذاكرة للمعالجات المحلية (قص الصمت / كشف الثغرات) — الضغط بس عند الرفع.
 */
object AudioEnc {
    class Packed(val bytes: ByteArray, val mime: String, val srcSize: Int)

    /**
     * (v167) بريسيت الصوت (من الإعدادات ← الترجمة والمحرك ← الصوت والتوقيت):
     *  • سريع (الافتراضي): AAC بمعدل 64kbps (v742: كان 32 — الكلام الضعيف في اللغات غير الإنجليزي كان بيتسمع غلط) — أصغر ~4 مرات من WAV. لو الجهاز رفض 64 بيجرّب 48 ثم 32.
     *  • نورمال: WAV خام 16kHz من غير ضغط (≈32KB/ث) — أعلى جودة، والرفع أبطأ.
     */
    const val PRESET_KEY = "audio_preset"
    const val FAST = "fast"
    const val NORMAL = "normal"
    const val BITRATE = 64000
    private val LADDER = intArrayOf(64000, 48000, 32000)
    @Volatile private var goodBr = 0
    /** (v742) على الخيط ده بس: ابعت WAV خام مهما كان البريسيت (مقاطع التدقيق القصيرة — مفيش سبب نضغطها) */
    val lossless = ThreadLocal<Boolean>()
    fun preset(): String = if (Cfg.str(PRESET_KEY, FAST) == NORMAL) NORMAL else FAST

    private val cache: MutableMap<ByteArray, Packed> = Collections.synchronizedMap(WeakHashMap<ByteArray, Packed>())
    @Volatile private var fails = 0

    fun pack(wav: ByteArray): Packed {
        if (preset() == NORMAL || lossless.get() == true) return Packed(wav, "audio/wav", wav.size)   // نورمال: WAV خام
        cache[wav]?.let { return it }
        var p: Packed? = null
        if (fails < 3 && isWav(wav)) {
            val tries = if (goodBr > 0) intArrayOf(goodBr) else LADDER
            var threw = 0
            for (br in tries) {
                try { p = aac(wav, br)?.takeIf { it.bytes.size < wav.size * 0.8 }; if (p != null) { goodBr = br; break } }
                catch (_: Throwable) { threw++ }
            }
            if (p == null && threw == tries.size) fails++
        }
        val r = p ?: Packed(wav, "audio/wav", wav.size)
        cache[wav] = r
        return r
    }


    private fun isWav(b: ByteArray) = b.size > 46 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    private fun freqIdx(rate: Int): Int = when (rate) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5; 24000 -> 6
        22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; else -> -1
    }

    private fun adts(out: java.io.ByteArrayOutputStream, size: Int, fi: Int) {
        val fl = size + 7
        val h = ByteArray(7)
        h[0] = 0xFF.toByte(); h[1] = 0xF1.toByte()
        h[2] = ((1 shl 6) or (fi shl 2) or 0).toByte()          // LC (aot 2 → 1) ، قناة واحدة
        h[3] = ((1 shl 6) or (fl shr 11)).toByte()
        h[4] = ((fl shr 3) and 0xFF).toByte()
        h[5] = (((fl and 7) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
        out.write(h)
    }

    private fun aac(wav: ByteArray, bitrate: Int): Packed? {
        val rate = (wav[24].toInt() and 255) or ((wav[25].toInt() and 255) shl 8) or ((wav[26].toInt() and 255) shl 16) or ((wav[27].toInt() and 255) shl 24)
        val fi = freqIdx(rate); if (fi < 0) return null
        val total = wav.size - 44
        if (total < rate / 5) return null    // أقل من 0.1ث مش مستاهل
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = java.io.ByteArrayOutputStream(total / 6 + 1024)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0; var fed = 0L; var eosSent = false; var done = false
            val deadline = System.currentTimeMillis() + 30_000L
            while (!done) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("aac timeout")
                if (!eosSent) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        val n = minOf(ib.capacity() and 1.inv(), total - pos)
                        val pts = fed * 1_000_000L / rate
                        if (n <= 0) { codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true }
                        else { ib.put(wav, 44 + pos, n); codec.queueInputBuffer(ii, 0, n, pts, 0); pos += n; fed += n / 2 }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size); ob.get(chunk)
                        adts(out, info.size, fi); out.write(chunk)
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    codec.releaseOutputBuffer(oi, false)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
        }
        val b = out.toByteArray()
        return if (b.size < 64) null else Packed(b, "audio/aac", wav.size)
    }
}


/**
 * (v1067) أدوات صوت صغيرة للوضع السريع (WAV 16kHz mono PCM16): تسريع من غير تغيير النغمة (WSOLA)، قص صمت الأطراف، وقص جزء.
 * كله منطق نقي من غير Android (متختبر في FastModeTest).
 */
object AudioTools {
    class Trim(val bytes: ByteArray, val leadSec: Double, val durSec: Double)

    private fun rateOf(w: ByteArray): Int = (w[24].toInt() and 255) or ((w[25].toInt() and 255) shl 8) or ((w[26].toInt() and 255) shl 16) or ((w[27].toInt() and 255) shl 24)
    private fun isWav(w: ByteArray) = w.size > 44 && w[0] == 'R'.code.toByte() && w[1] == 'I'.code.toByte() && w[2] == 'F'.code.toByte() && w[3] == 'F'.code.toByte()
    private fun pcm(w: ByteArray, from: Int, count: Int): ByteArray {
        val out = ByteArray(44 + count * 2)
        System.arraycopy(Wav.header(count * 2, rateOf(w)), 0, out, 0, 44)
        System.arraycopy(w, 44 + from * 2, out, 44, count * 2)
        return out
    }

    /** جزء من الـ WAV من from لـ to (ثواني نسبة لأول الملف) */
    fun cut(wav: ByteArray, fromSec: Double, toSec: Double): ByteArray {
        if (!isWav(wav)) return wav
        val rate = rateOf(wav); if (rate <= 0) return wav
        val n = (wav.size - 44) / 2
        val a = (fromSec * rate).toInt().coerceIn(0, n)
        val b = (toSec * rate).toInt().coerceIn(a, n)
        return pcm(wav, a, b - a)
    }

    /** قص الصمت من أول وآخر المقطع (بهامش 0.4ث). null = مفيش حاجة تستاهل القص (أقل من 1.5ث) أو المقطع كله هادي */
    fun trimSilence(wav: ByteArray): Trim? {
        if (!isWav(wav)) return null
        val rate = rateOf(wav); if (rate <= 0) return null
        val n = (wav.size - 44) / 2
        val fr = rate / 10
        val nf = n / fr
        if (nf < 20) return null
        val lv = DoubleArray(nf)
        for (f in 0 until nf) {
            var sq = 0.0
            for (k in f * fr until (f + 1) * fr) { val o = 44 + 2 * k; val v = ((wav[o + 1].toInt() shl 8) or (wav[o].toInt() and 0xFF)).toShort() / 32768.0; sq += v * v }
            lv[f] = Math.sqrt(sq / fr)
        }
        val speech = lv.sortedArray()[(nf * 0.90).toInt().coerceIn(0, nf - 1)]
        if (speech < 0.01) return null
        val thr = maxOf(0.005, speech * 0.12)
        var first = 0; while (first < nf && lv[first] <= thr) first++
        var last = nf - 1; while (last > first && lv[last] <= thr) last--
        if (first >= nf) return null
        val total = n.toDouble() / rate
        val lead = maxOf(0.0, first * 0.1 - 0.4)
        val end = minOf(total, (last + 1) * 0.1 + 0.4)
        if (end - lead < 1.5 || lead + (total - end) < 1.5) return null
        return Trim(cut(wav, lead, end), lead, end - lead)
    }

    /** تسريع k× (k>1) بالحفاظ على النغمة (WSOLA: إطار 40ms، نص تداخل، بحث ±10ms عن أحسن تطابق). الناتج أقصر بحوالي k مرة */
    fun speedUp(wav: ByteArray, k: Double): ByteArray {
        if (k <= 1.01 || !isWav(wav)) return wav
        val rate = rateOf(wav); if (rate < 8000) return wav
        val n = (wav.size - 44) / 2
        val fl = rate / 25
        if (n < fl * 4) return wav
        val hs = fl / 2
        val ha = (hs * k).toInt().coerceAtLeast(hs + 1)
        val delta = rate / 100
        val x = ShortArray(n) { val o = 44 + 2 * it; ((wav[o + 1].toInt() shl 8) or (wav[o].toInt() and 0xFF)).toShort() }
        val cap = (n / k).toInt() + fl * 3
        val y = FloatArray(cap); val ws = FloatArray(cap)
        val win = FloatArray(fl) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (fl - 1))).toFloat() }
        var m = 0; var prev = 0; var outPos = 0
        while (true) {
            val target = m * ha
            var pos = 0
            if (m > 0) {
                val nat = prev + hs
                val lo = maxOf(0, target - delta); val hi = minOf(n - fl, target + delta)
                if (hi < lo) break
                var best = Double.NEGATIVE_INFINITY; pos = lo
                var cand = lo
                while (cand <= hi) {
                    var c = 0.0; var j = 0
                    while (j < hs) { c += x[cand + j].toDouble() * x[nat + j]; j += 2 }
                    if (c > best) { best = c; pos = cand }
                    cand++
                }
            }
            if (pos + fl > n || outPos + fl > cap) break
            for (j in 0 until fl) { y[outPos + j] += x[pos + j] * win[j]; ws[outPos + j] += win[j] }
            prev = pos; m++; outPos += hs
        }
        val len = minOf(outPos + hs, cap)
        if (len < rate / 2) return wav
        val out = ByteArray(44 + len * 2)
        System.arraycopy(Wav.header(len * 2, rate), 0, out, 0, 44)
        for (i in 0 until len) {
            val v = if (ws[i] > 0.05f) Math.round(y[i] / ws[i]).coerceIn(-32768, 32767) else 0
            out[44 + 2 * i] = (v and 0xFF).toByte(); out[45 + 2 * i] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
