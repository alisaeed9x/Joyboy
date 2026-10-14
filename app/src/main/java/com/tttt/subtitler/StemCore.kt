package com.tttt.subtitler

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * (v307) قلب فصل الصوت عن الموسيقى بالذكاء الصناعي (MDX-Net من UVR) — كود Kotlin صافي من غير أي حاجة من Android،
 * عشان يتختبر على الـ JVM (dev-tests/StemTest.kt) بنفس المنطق اللي بيشتغل على الموبايل.
 *
 * الخط: PCM ستيريو 44.1kHz ← قطع (chunks) بالتداخل ← STFT (نفس torch.stft: center + reflect + Hann دوري) ← الموديل (ONNX Runtime)
 * ← iSTFT ← دمج القطع بنافذة Hann ← صوت = تقدير الموديل · موسيقى = الأصل − الصوت (فصوت + موسيقى = الأغنية بالظبط).
 */

// ============================================================================================
// FFT مركّب بأساس مختلط (2 · 3 · 5 · أي أولي) — n_fft بتاع الموديلات (7680 و6144) مش قوى 2
// ============================================================================================
class StemFft(val n: Int) {
    private val fac: IntArray
    private val twRe = DoubleArray(n)
    private val twImF = DoubleArray(n)     // اتجاه أمامي: -sin
    private val twImI = DoubleArray(n)     // اتجاه عكسي: +sin
    private val tmpRe = DoubleArray(n)
    private val tmpIm = DoubleArray(n)
    private val sRe: DoubleArray
    private val sIm: DoubleArray

    init {
        require(n >= 1)
        val l = ArrayList<Int>()
        var m = n; var p = 2
        while (m > 1) {
            while (m % p != 0) { p = if (p == 2) 3 else p + 2; if (p.toLong() * p > m) p = m }
            m /= p; l.add(p); l.add(m)
        }
        fac = l.toIntArray()
        var maxP = 2
        for (i in fac.indices step 2) if (fac[i] > maxP) maxP = fac[i]
        sRe = DoubleArray(maxP); sIm = DoubleArray(maxP)
        for (k in 0 until n) {
            val a = 2.0 * Math.PI * k / n
            twRe[k] = Math.cos(a); twImF[k] = -Math.sin(a); twImI[k] = Math.sin(a)
        }
    }

    /** تحويل في نفس المصفوفة. inverse=true من غير قسمة على n (اللي بينادي يقسم) */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        if (n == 1) return
        System.arraycopy(re, 0, tmpRe, 0, n); System.arraycopy(im, 0, tmpIm, 0, n)
        work(re, im, 0, tmpRe, tmpIm, 0, 1, 0, if (inverse) twImI else twImF)
    }

    private fun work(oRe: DoubleArray, oIm: DoubleArray, o0: Int, iRe: DoubleArray, iIm: DoubleArray, i0: Int, fstride: Int, fi: Int, ti: DoubleArray) {
        val p = fac[fi]; val m = fac[fi + 1]
        var o = o0; val end = o0 + p * m
        var f = i0
        if (m == 1) {
            while (o != end) { oRe[o] = iRe[f]; oIm[o] = iIm[f]; f += fstride; o++ }
        } else {
            while (o != end) {
                work(oRe, oIm, o, iRe, iIm, f, fstride * p, fi + 2, ti)
                f += fstride; o += m
            }
        }
        if (p == 2) bfly2(oRe, oIm, o0, fstride, m, ti) else bflyGen(oRe, oIm, o0, fstride, m, p, ti)
    }

    private fun bfly2(re: DoubleArray, im: DoubleArray, o: Int, fstride: Int, m: Int, ti: DoubleArray) {
        for (k in 0 until m) {
            val a = o + k; val b = a + m
            val tr = twRe[k * fstride]; val tq = ti[k * fstride]
            val xr = re[b] * tr - im[b] * tq; val xi = re[b] * tq + im[b] * tr
            re[b] = re[a] - xr; im[b] = im[a] - xi
            re[a] += xr; im[a] += xi
        }
    }

    private fun bflyGen(re: DoubleArray, im: DoubleArray, o: Int, fstride: Int, m: Int, p: Int, ti: DoubleArray) {
        for (u in 0 until m) {
            var k = u
            for (q1 in 0 until p) { sRe[q1] = re[o + k]; sIm[q1] = im[o + k]; k += m }
            k = u
            for (q1 in 0 until p) {
                var tw = 0
                var accR = sRe[0]; var accI = sIm[0]
                for (q in 1 until p) {
                    tw += fstride * k; if (tw >= n) tw -= n
                    val tr = twRe[tw]; val tq = ti[tw]
                    accR += sRe[q] * tr - sIm[q] * tq; accI += sRe[q] * tq + sIm[q] * tr
                }
                re[o + k] = accR; im[o + k] = accI
                k += m
            }
        }
    }
}

// ============================================================================================
// STFT / iSTFT بنفس سلوك torch.stft/istft (center=True · reflect · Hann دوري) — القناتين (شمال+يمين) في FFT واحد
// تخطيط الطيف: [4][dimF][T] مسطّح = (شمال حقيقي، شمال تخيلي، يمين حقيقي، يمين تخيلي) — ده نفس مدخل الموديل [1,4,F,T]
// ============================================================================================
class MdxStft(val nFft: Int, val hop: Int, val dimF: Int) {
    private val fft = StemFft(nFft)
    private val win = DoubleArray(nFft) { 0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / nFft) }
    private val zr = DoubleArray(nFft)
    private val zi = DoubleArray(nFft)
    private var accL = DoubleArray(0); private var accR = DoubleArray(0); private var env = DoubleArray(0); private var envT = -1

    init { require(dimF < nFft / 2) }

    fun frames(len: Int) = 1 + len / hop

    fun forward(l: FloatArray, r: FloatArray, len: Int, spec: FloatArray) {
        val t = frames(len); val half = nFft / 2
        for (fr in 0 until t) {
            val s = fr * hop - half
            for (k in 0 until nFft) {
                var idx = s + k
                if (idx < 0) idx = -idx else if (idx >= len) idx = 2 * (len - 1) - idx
                val w = win[k]
                zr[k] = l[idx] * w; zi[k] = r[idx] * w
            }
            fft.transform(zr, zi, false)
            for (f in 0 until dimF) {
                val j = if (f == 0) 0 else nFft - f
                val lr = 0.5 * (zr[f] + zr[j]); val li = 0.5 * (zi[f] - zi[j])
                val rr = 0.5 * (zi[f] + zi[j]); val ri = -0.5 * (zr[f] - zr[j])
                spec[(0 * dimF + f) * t + fr] = lr.toFloat()
                spec[(1 * dimF + f) * t + fr] = li.toFloat()
                spec[(2 * dimF + f) * t + fr] = rr.toFloat()
                spec[(3 * dimF + f) * t + fr] = ri.toFloat()
            }
        }
    }

    /** spec [4][dimF][t] ← صوت: outL/outR بطول hop*(t-1) */
    fun inverse(spec: FloatArray, t: Int, outL: FloatArray, outR: FloatArray) {
        val total = nFft + hop * (t - 1); val half = nFft / 2
        if (accL.size != total) { accL = DoubleArray(total); accR = DoubleArray(total) } else { java.util.Arrays.fill(accL, 0.0); java.util.Arrays.fill(accR, 0.0) }
        if (envT != t) {
            env = DoubleArray(total)
            for (fr in 0 until t) { val b = fr * hop; for (k in 0 until nFft) env[b + k] += win[k] * win[k] }
            envT = t
        }
        val inv = 1.0 / nFft
        for (fr in 0 until t) {
            java.util.Arrays.fill(zr, 0.0); java.util.Arrays.fill(zi, 0.0)
            for (f in 0 until dimF) {
                val a = spec[(0 * dimF + f) * t + fr].toDouble()
                var b = spec[(1 * dimF + f) * t + fr].toDouble()
                val c = spec[(2 * dimF + f) * t + fr].toDouble()
                var d = spec[(3 * dimF + f) * t + fr].toDouble()
                if (f == 0) { b = 0.0; d = 0.0 }          // DC حقيقي (زي irfft)
                zr[f] = a - d; zi[f] = b + c
                if (f > 0) { val j = nFft - f; zr[j] = a + d; zi[j] = c - b }
            }
            fft.transform(zr, zi, true)
            val base = fr * hop
            for (k in 0 until nFft) { val w = win[k] * inv; accL[base + k] += zr[k] * w; accR[base + k] += zi[k] * w }
        }
        val n = hop * (t - 1)
        for (i in 0 until n) {
            val e = env[half + i]; val s = if (e > 1e-11) 1.0 / e else 1.0
            outL[i] = (accL[half + i] * s).toFloat(); outR[i] = (accR[half + i] * s).toFloat()
        }
    }
}

// ============================================================================================
// إعادة تشكيل التردد (sinc بنافذة Blackman) — الموديلات بتشتغل على 44.1kHz بس
// ============================================================================================
class StemResampler(val inRate: Int, val outRate: Int) {
    private val taps = 16
    private val phases = 512
    private val step = inRate.toDouble() / outRate
    private val table: Array<FloatArray>
    private var bl = FloatArray(1 shl 16); private var br = FloatArray(1 shl 16); private var bn = 0
    private var inBase = 0L          // رقم أول عينة في البافر (مطلق)
    private var pos = 0.0            // مكان العينة الخارجة الجاية (مطلق، بوحدة عينات الدخل)
    private var totalIn = 0L

    init {
        val fc = Math.min(1.0, outRate.toDouble() / inRate) * 0.95
        table = Array(phases + 1) { p ->
            val frac = p.toDouble() / phases
            val row = FloatArray(2 * taps)
            var sum = 0.0
            val tmp = DoubleArray(2 * taps)
            for (j in 0 until 2 * taps) {
                val k = j - taps + 1
                val d = k - frac
                val x = Math.PI * fc * d
                val s = if (Math.abs(x) < 1e-9) 1.0 else Math.sin(x) / x
                val a = Math.PI * d / taps
                val w = if (Math.abs(d) >= taps) 0.0 else 0.42 + 0.5 * Math.cos(a) + 0.08 * Math.cos(2 * a)
                tmp[j] = fc * s * w; sum += tmp[j]
            }
            for (j in 0 until 2 * taps) row[j] = (tmp[j] / sum).toFloat()
            row
        }
    }

    fun push(l: FloatArray, r: FloatArray, n: Int, sink: (FloatArray, FloatArray, Int) -> Unit) {
        if (n <= 0) return
        if (bn + n > bl.size) { val c = Math.max(bl.size * 2, bn + n); bl = bl.copyOf(c); br = br.copyOf(c) }
        System.arraycopy(l, 0, bl, bn, n); System.arraycopy(r, 0, br, bn, n); bn += n; totalIn += n
        drain(false, sink)
    }

    fun finish(sink: (FloatArray, FloatArray, Int) -> Unit) {
        val pad = taps + 2
        if (bn + pad > bl.size) { bl = bl.copyOf(bn + pad); br = br.copyOf(bn + pad) }
        for (i in 0 until pad) { bl[bn + i] = 0f; br[bn + i] = 0f }
        bn += pad
        drain(true, sink)
    }

    private fun drain(final: Boolean, sink: (FloatArray, FloatArray, Int) -> Unit) {
        val cap = 8192
        val ol = FloatArray(cap); val orr = FloatArray(cap)
        var oc = 0
        while (true) {
            val i0 = Math.floor(pos).toLong()
            if (final) { if (pos >= totalIn) break } else if (i0 + taps >= inBase + bn) break
            val frac = pos - i0
            val row = table[Math.round(frac * phases).toInt()]
            val first = i0 - taps + 1 - inBase           // مكان أول عينة في البافر
            var sl = 0f; var sr = 0f
            for (j in 0 until 2 * taps) {
                val idx = (first + j).toInt()
                if (idx < 0 || idx >= bn) continue
                val w = row[j]; sl += bl[idx] * w; sr += br[idx] * w
            }
            ol[oc] = sl; orr[oc] = sr; oc++
            pos += step
            if (oc == cap) { sink(ol, orr, oc); oc = 0 }
        }
        if (oc > 0) sink(ol, orr, oc)
        // شيل العينات القديمة (سيب history بقدر taps)
        val keepFrom = Math.floor(pos).toLong() - taps - inBase
        if (keepFrom > 0 && keepFrom <= bn) {
            val k = keepFrom.toInt(); val rest = bn - k
            System.arraycopy(bl, k, bl, 0, rest); System.arraycopy(br, k, br, 0, rest)
            bn = rest; inBase += k
        }
    }
}

// ============================================================================================
// مصدر PCM (ملف int16 ستيريو متداخل) ووجهات الكتابة (WAV 16-bit)
// ============================================================================================
interface PcmSource { val frames: Long; fun read(start: Long, count: Int, l: FloatArray, r: FloatArray) }
interface StemSink { fun write(l: FloatArray, r: FloatArray, n: Int) }

class StemPcmFile(f: File) : PcmSource {
    private val raf = RandomAccessFile(f, "r")
    override val frames: Long = f.length() / 4
    private var buf = ByteArray(0)
    override fun read(start: Long, count: Int, l: FloatArray, r: FloatArray) {
        java.util.Arrays.fill(l, 0, count, 0f); java.util.Arrays.fill(r, 0, count, 0f)
        val s0 = Math.max(start, 0L); val e0 = Math.min(start + count, frames)
        if (e0 <= s0) return
        val nf = (e0 - s0).toInt()
        if (buf.size < nf * 4) buf = ByteArray(nf * 4)
        raf.seek(s0 * 4); raf.readFully(buf, 0, nf * 4)
        val off = (s0 - start).toInt()
        var p = 0
        for (i in 0 until nf) {
            l[off + i] = ((buf[p + 1].toInt() shl 8) or (buf[p].toInt() and 255)).toShort() / 32768f
            r[off + i] = ((buf[p + 3].toInt() shl 8) or (buf[p + 2].toInt() and 255)).toShort() / 32768f
            p += 4
        }
    }
    fun close() { try { raf.close() } catch (_: Throwable) {} }
}

/** كاتب WAV ستيريو 16-bit — بيكتب لملف .tmp وبيتسمّى للاسم النهائي عند finish(true). (v319) resumeBytes ≥ 0 = كمّل على نفس الـ.tmp من بايت معيّن (استكمال الفصل) */
class StemWav(val file: File, val rate: Int, val monoMix: Boolean = false, resumeBytes: Long = -1L) : StemSink {
    private val tmp = File(file.path + ".tmp")
    private var bytes = 0L
    private val os: BufferedOutputStream
    private var buf = ByteArray(0)
    val written: Long get() = bytes
    init {
        val can = resumeBytes >= 0L && tmp.isFile && tmp.length() >= 44L + resumeBytes
        if (can) {
            RandomAccessFile(tmp, "rw").use { it.setLength(44L + resumeBytes) }
            bytes = resumeBytes
            os = BufferedOutputStream(FileOutputStream(tmp, true), 1 shl 16)
        } else {
            os = BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)
            os.write(ByteArray(44))
        }
    }
    fun flush() { try { os.flush() } catch (_: Throwable) {} }
    /** بيقفل الملف من غير ما يمسح الـ.tmp (للاستكمال بعدين) */
    fun pause() { try { os.flush(); os.close() } catch (_: Throwable) {} }
    override fun write(l: FloatArray, r: FloatArray, n: Int) {
        if (buf.size < n * 4) buf = ByteArray(n * 4)
        var p = 0
        for (i in 0 until n) {
            val a = (l[i].coerceIn(-1f, 1f) * 32767f).toInt(); val b = (r[i].coerceIn(-1f, 1f) * 32767f).toInt()
            buf[p] = (a and 255).toByte(); buf[p + 1] = ((a shr 8) and 255).toByte()
            buf[p + 2] = (b and 255).toByte(); buf[p + 3] = ((b shr 8) and 255).toByte()
            p += 4
        }
        os.write(buf, 0, n * 4); bytes += n * 4L
    }
    fun finish(ok: Boolean): Boolean {
        try { os.flush(); os.close() } catch (_: Throwable) {}
        if (!ok || bytes <= 0L) { tmp.delete(); return false }
        return try {
            RandomAccessFile(tmp, "rw").use { r ->
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()); h.putInt((36 + bytes).toInt()); h.put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(rate); h.putInt(rate * 4); h.putShort(4); h.putShort(16)
                h.put("data".toByteArray()); h.putInt(bytes.toInt())
                r.seek(0); r.write(h.array())
            }
            file.delete(); tmp.renameTo(file)
        } catch (_: Throwable) { tmp.delete(); false }
    }
}

// ============================================================================================
// جسر ONNX Runtime (cpp/stemort.cpp)
// ============================================================================================
object StemOrt {
    @Volatile private var state = 0       // 0 لسه · 1 تمام · -1 فشل
    @Volatile var loadErr = ""; private set
    fun ok(): Boolean {
        if (state == 0) synchronized(this) {
            if (state == 0) {
                try { try { System.loadLibrary("onnxruntime") } catch (_: Throwable) {}; System.loadLibrary("substem"); state = 1 }
                catch (e: Throwable) { loadErr = e.message ?: e.javaClass.simpleName; state = -1 }
            }
        }
        return state == 1
    }
    @JvmStatic external fun nOpen(path: String, threads: Int): Long
    @JvmStatic external fun nShape(h: Long): IntArray?
    @JvmStatic external fun nPrepare(h: Long, c: Int, f: Int, t: Int): Int
    @JvmStatic external fun nRun(h: Long, input: FloatArray, output: FloatArray): Int
    @JvmStatic external fun nClose(h: Long)
    @JvmStatic external fun nErr(): String
    @JvmStatic external fun nVersion(): String
}

interface StemRunner { fun run(input: FloatArray, output: FloatArray): String?; fun close() }

/** موديل MDX-Net واحد على ONNX Runtime. [error] غير null = فشل الفتح */
class OrtRunner(path: String, threads: Int, val dimF: Int, val dimT: Int) : StemRunner {
    private var h = 0L
    var error: String? = null; private set
    init {
        if (!StemOrt.ok()) error = "مكتبة الفصل مش موجودة على الجهاز ده (محتاج arm64): " + StemOrt.loadErr
        else {
            h = StemOrt.nOpen(path, threads)
            if (h == 0L) error = "فتح الموديل فشل: " + StemOrt.nErr()
            else {
                val sh = StemOrt.nShape(h)
                // الأبعاد المتوقعة [batch, 4, F, T] — لو مختلفة يبقى الملف مش الموديل المقصود
                if (sh == null || sh.size != 4 || sh[1] != 4 || sh[2] != dimF || sh[3] != dimT) {
                    error = "الموديل شكله مختلف عن المتوقع (" + (sh?.joinToString("×") ?: "?") + ") — امسحه وحمّله تاني"
                    StemOrt.nClose(h); h = 0L
                } else if (StemOrt.nPrepare(h, 4, dimF, dimT) != 0) {
                    error = "الرام مش كفاية لتشغيل الموديل: " + StemOrt.nErr(); StemOrt.nClose(h); h = 0L
                }
            }
        }
    }
    override fun run(input: FloatArray, output: FloatArray): String? {
        if (h == 0L) return error ?: "الموديل مش مفتوح"
        val rc = StemOrt.nRun(h, input, output)
        return if (rc == 0) null else "تشغيل الموديل فشل (" + rc + "): " + StemOrt.nErr()
    }
    override fun close() { if (h != 0L) { StemOrt.nClose(h); h = 0L } }
}

// ============================================================================================
// الفاصل: قطع بتداخل + دمج بنافذة Hann (نفس منطق UVR / audio-separator)
// ============================================================================================
class MdxSpec(val nFft: Int, val dimF: Int, val dimT: Int, val comp: Float, val vocalsPrimary: Boolean)

/** (v319) نقطة استكمال للفاصل: مكان القطعة الجاية + عدّاد القطع + حالة دمج التداخل */
class StemState(val i: Long, val done: Int, val accL: FloatArray, val accR: FloatArray, val div: FloatArray)

object StemSeparator {
    const val HOP = 1024
    fun chunkLen(dimT: Int) = HOP * (dimT - 1)
    fun stepOf(dimT: Int, overlap: Double) = Math.max(1, ((1.0 - overlap.coerceIn(0.0, 0.75)) * chunkLen(dimT)).toInt())

    /** عدد القطع اللي هتتعالج (للتقدير) */
    fun chunkCount(frames: Long, spec: MdxSpec, overlap: Double): Int {
        val chunk = HOP * (spec.dimT - 1); val step = Math.max(1, ((1.0 - overlap) * chunk).toInt())
        val limit = spec.nFft / 2 + frames
        return ((limit + step - 1) / step).toInt()
    }

    /**
     * بيفصل [src] لصوت [voice] وموسيقى [music] (نفس الطول). موديل واحد أو أكتر (المتوسط = «الأقوى»).
     * overlap: 0..0.75 (أكتر = أبطأ وأنضف). بيرجّع null لو تمام أو رسالة الخطأ.
     */
    fun run(src: PcmSource, models: List<Pair<MdxSpec, StemRunner>>, overlap: Double, voice: StemSink, music: StemSink,
            onPct: (Int) -> Unit, cancelled: () -> Boolean,
            start: StemState? = null, ckptEvery: Int = 6,
            ckpt: ((Long, Int, FloatArray, FloatArray, FloatArray) -> Unit)? = null): String? {
        if (models.isEmpty()) return "مفيش موديل"
        val dimT = models[0].first.dimT
        if (models.any { it.first.dimT != dimT }) return "الموديلات لازم يبقوا بنفس dim_t"
        val frames = src.frames
        if (frames < 2048) return "الملف قصير أوي"
        val chunk = HOP * (dimT - 1)
        val trim = models.maxOf { it.first.nFft } / 2
        val gen = chunk - 2 * trim
        if (gen <= 0) return "إعدادات الموديل غلط"
        val ov = overlap.coerceIn(0.0, 0.75)
        val step = Math.max(1, ((1.0 - ov) * chunk).toInt())
        val limit = trim + frames                 // آخر مكان مفيد (إحداثيات المكس بعد إضافة التريم)
        val total = trim + frames + (gen + trim - (frames % gen).toInt()) // M
        val stfts = models.map { MdxStft(it.first.nFft, HOP, it.first.dimF) }
        val specs = models.map { FloatArray(4 * it.first.dimF * dimT) }
        val preds = models.map { FloatArray(4 * it.first.dimF * dimT) }
        val inL = FloatArray(chunk); val inR = FloatArray(chunk)
        val oL = FloatArray(chunk); val oR = FloatArray(chunk)
        val vL = FloatArray(chunk); val vR = FloatArray(chunk)
        val accL = FloatArray(chunk); val accR = FloatArray(chunk); val div = FloatArray(chunk)
        val outVL = FloatArray(step); val outVR = FloatArray(step); val outML = FloatArray(step); val outMR = FloatArray(step)
        val mixL = FloatArray(step); val mixR = FloatArray(step)
        var fullWin: FloatArray? = null
        fun hann(n: Int): FloatArray {
            if (n == chunk && fullWin != null) return fullWin!!
            val w = FloatArray(n) { if (n <= 1) 1f else (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (n - 1))).toFloat() }
            if (n == chunk) fullWin = w
            return w
        }
        val totalChunks = Math.max(1L, (limit + step - 1) / step).toInt()
        var i = 0L; var done = 0
        if (start != null && start.accL.size == chunk) {            // (v319) استكمال من نقطة محفوظة
            i = start.i; done = start.done
            System.arraycopy(start.accL, 0, accL, 0, chunk); System.arraycopy(start.accR, 0, accR, 0, chunk); System.arraycopy(start.div, 0, div, 0, chunk)
        }
        val wgt = 1f / models.size
        while (i < limit) {
            if (cancelled()) { ckpt?.invoke(i, done, accL, accR, div); return "اتلغى" }
            val n = Math.min(chunk.toLong(), total - i).toInt()
            src.read(i - trim, chunk, inL, inR)
            java.util.Arrays.fill(vL, 0f); java.util.Arrays.fill(vR, 0f)
            var peak = 0f
            for (j in 0 until chunk) { val a = Math.abs(inL[j]); val b = Math.abs(inR[j]); if (a > peak) peak = a; if (b > peak) peak = b }
            val silent = peak < 1e-4f            // (v319) قطعة صامتة: مفيش صوت يتفصل — نوفّر تشغيل الموديل
            for ((k, mp) in models.withIndex()) {
                if (silent) break
                val sp = mp.first; val spec = specs[k]; val pred = preds[k]
                stfts[k].forward(inL, inR, chunk, spec)
                val tt = dimT
                for (c in 0 until 4) for (f in 0 until 3) java.util.Arrays.fill(spec, (c * sp.dimF + f) * tt, (c * sp.dimF + f) * tt + tt, 0f)   // زي UVR: أول 3 bins تتصفّر
                val err = mp.second.run(spec, pred)
                if (err != null) return err
                stfts[k].inverse(pred, tt, oL, oR)
                if (sp.vocalsPrimary) {
                    for (j in 0 until chunk) { vL[j] += wgt * sp.comp * oL[j]; vR[j] += wgt * sp.comp * oR[j] }
                } else {
                    for (j in 0 until chunk) { vL[j] += wgt * (inL[j] - sp.comp * oL[j]); vR[j] += wgt * (inR[j] - sp.comp * oR[j]) }
                }
            }
            if (ov > 0.0) {
                val w = hann(n)
                for (j in 0 until n) { accL[j] += vL[j] * w[j]; accR[j] += vR[j] * w[j]; div[j] += w[j] }
            } else {
                for (j in 0 until n) { accL[j] += vL[j]; accR[j] += vR[j]; div[j] += 1f }
            }
            val last = i + step >= limit
            val emitEnd = if (last) limit else i + step
            val from = Math.max(i, trim.toLong())
            if (emitEnd > from) {
                val cnt = (emitEnd - from).toInt(); val rel = (from - i).toInt()
                src.read(from - trim, cnt, mixL, mixR)
                for (j in 0 until cnt) {
                    val d = div[rel + j]
                    val vl = if (d > 1e-6f) accL[rel + j] / d else 0f
                    val vr = if (d > 1e-6f) accR[rel + j] / d else 0f
                    outVL[j] = vl; outVR[j] = vr; outML[j] = mixL[j] - vl; outMR[j] = mixR[j] - vr
                }
                voice.write(outVL, outVR, cnt); music.write(outML, outMR, cnt)
            }
            if (!last && step < chunk) {
                System.arraycopy(accL, step, accL, 0, chunk - step); System.arraycopy(accR, step, accR, 0, chunk - step); System.arraycopy(div, step, div, 0, chunk - step)
                java.util.Arrays.fill(accL, chunk - step, chunk, 0f); java.util.Arrays.fill(accR, chunk - step, chunk, 0f); java.util.Arrays.fill(div, chunk - step, chunk, 0f)
            } else if (!last) { java.util.Arrays.fill(accL, 0f); java.util.Arrays.fill(accR, 0f); java.util.Arrays.fill(div, 0f) }
            i += step; done++
            if (ckpt != null && done % ckptEvery == 0 && i < limit) ckpt(i, done, accL, accR, div)
            onPct((done * 100 / totalChunks).coerceIn(0, 99))
        }
        return null
    }
}
