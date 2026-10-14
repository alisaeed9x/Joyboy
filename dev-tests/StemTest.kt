import com.tttt.subtitler.*
import java.io.File

// (v307) اختبارات فصل الصوت (StemCore.kt) على الـ JVM.
//  1) FFT المختلط مقابل DFT مباشر  2) STFT→iSTFT رجوع تام لإشارة محدودة النطاق  3) إعادة التشكيل 48k→44.1k
//  4) الخط كله (قطع + تداخل + نافذة + كتابة) بموديل «هوية» — لازم الصوت = الإشارة بالظبط على حدود القطع
//  5) (اختياري) الموديل الحقيقي: بيتشغّل لو SUBTITLER_MDX_MODEL و SUBTITLER_MDX_REF موجودين (بيقارن بمرجع numpy)
var pass = 0; var fail = 0
fun check(name: String, ok: Boolean, extra: String = "") { if (ok) { pass++; println("PASS $name $extra") } else { fail++; println("FAIL $name $extra") } }

class IdentityRunner : StemRunner { override fun run(input: FloatArray, output: FloatArray): String? { System.arraycopy(input, 0, output, 0, input.size); return null }; override fun close() {} }
class MemSink : StemSink {
    var l = FloatArray(1 shl 16); var r = FloatArray(1 shl 16); var n = 0
    override fun write(l: FloatArray, r: FloatArray, n: Int) {
        if (this.n + n > this.l.size) { this.l = this.l.copyOf(Math.max(this.l.size * 2, this.n + n)); this.r = this.r.copyOf(this.l.size) }
        System.arraycopy(l, 0, this.l, this.n, n); System.arraycopy(r, 0, this.r, this.n, n); this.n += n
    }
}
fun writePcm(f: File, l: FloatArray, r: FloatArray) {
    val b = java.nio.ByteBuffer.allocate(l.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (i in l.indices) { b.putShort((l[i] * 32767f).toInt().toShort()); b.putShort((r[i] * 32767f).toInt().toShort()) }
    f.writeBytes(b.array())
}

fun main() {
    // ---- 1) FFT
    for (n in intArrayOf(12, 7, 64, 360, 6144, 7680)) {
        val rnd = java.util.Random(n.toLong())
        val re = DoubleArray(n) { rnd.nextGaussian() }; val im = DoubleArray(n) { rnd.nextGaussian() }
        val er = DoubleArray(n); val ei = DoubleArray(n)
        val step = if (n > 1000) 37 else 1
        var maxErr = 0.0
        val f = StemFft(n); val xr = re.copyOf(); val xi = im.copyOf(); f.transform(xr, xi, false)
        for (k in 0 until n step step) {
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) { val a = -2 * Math.PI * ((k.toLong() * t) % n) / n; sr += re[t] * Math.cos(a) - im[t] * Math.sin(a); si += re[t] * Math.sin(a) + im[t] * Math.cos(a) }
            maxErr = Math.max(maxErr, Math.hypot(sr - xr[k], si - xi[k]))
        }
        check("fft n=$n vs DFT", maxErr < 1e-7 * n, "maxErr=%.2e".format(maxErr))
        f.transform(xr, xi, true)
        var rt = 0.0; for (k in 0 until n) rt = Math.max(rt, Math.hypot(xr[k] / n - re[k], xi[k] / n - im[k]))
        check("fft n=$n roundtrip", rt < 1e-9, "err=%.2e".format(rt))
    }

    // ---- 2) STFT → iSTFT (إشارة محدودة النطاق: أقل بكتير من 17kHz)
    run {
        val nF = 7680; val dimF = 3072; val chunk = 1024 * 255
        val l = FloatArray(chunk) { (0.4 * Math.sin(2 * Math.PI * 440 * it / 44100.0) + 0.2 * Math.sin(2 * Math.PI * 5000 * it / 44100.0 + 1)).toFloat() }
        val r = FloatArray(chunk) { (0.3 * Math.sin(2 * Math.PI * 1234 * it / 44100.0) + 0.1 * Math.sin(2 * Math.PI * 9000 * it / 44100.0)).toFloat() }
        val st = MdxStft(nF, 1024, dimF); val t = st.frames(chunk)
        check("stft frames=256", t == 256, "t=$t")
        val spec = FloatArray(4 * dimF * t); st.forward(l, r, chunk, spec)
        val oL = FloatArray(chunk); val oR = FloatArray(chunk); st.inverse(spec, t, oL, oR)
        var e = 0.0; for (i in 0 until chunk) e = Math.max(e, Math.max(Math.abs(oL[i] - l[i]), Math.abs(oR[i] - r[i])).toDouble())
        check("stft->istft perfect reconstruction (n_fft 7680)", e < 2e-4, "maxErr=%.2e".format(e))
        val st2 = MdxStft(6144, 1024, 3072); val spec2 = FloatArray(4 * 3072 * t); st2.forward(l, r, chunk, spec2); st2.inverse(spec2, t, oL, oR)
        e = 0.0; for (i in 0 until chunk) e = Math.max(e, Math.max(Math.abs(oL[i] - l[i]), Math.abs(oR[i] - r[i])).toDouble())
        check("stft->istft perfect reconstruction (n_fft 6144)", e < 2e-4, "maxErr=%.2e".format(e))
    }

    // ---- 3) إعادة التشكيل
    run {
        val inR = 48000; val outR = 44100; val secs = 3
        val n = inR * secs
        val l = FloatArray(n) { (0.5 * Math.sin(2 * Math.PI * 1000.0 * it / inR)).toFloat() }
        val r = FloatArray(n) { (0.5 * Math.sin(2 * Math.PI * 7000.0 * it / inR)).toFloat() }
        val rs = StemResampler(inR, outR); val ol = ArrayList<Float>(); val orr = ArrayList<Float>()
        var p = 0
        while (p < n) { val c = Math.min(10000, n - p); rs.push(l.copyOfRange(p, p + c), r.copyOfRange(p, p + c), c) { a, b, k -> for (i in 0 until k) { ol.add(a[i]); orr.add(b[i]) } }; p += c }
        rs.finish { a, b, k -> for (i in 0 until k) { ol.add(a[i]); orr.add(b[i]) } }
        val expect = n.toLong() * outR / inR
        check("resample length", Math.abs(ol.size - expect) <= 2, "got=${ol.size} expect=$expect")
        var e = 0.0
        for (i in 200 until ol.size - 200) {
            val tt = i.toDouble() / outR
            e = Math.max(e, Math.abs(ol[i] - 0.5 * Math.sin(2 * Math.PI * 1000.0 * tt)))
            e = Math.max(e, Math.abs(orr[i] - 0.5 * Math.sin(2 * Math.PI * 7000.0 * tt)))
        }
        check("resample accuracy 1k/7k sines", e < 3e-3, "maxErr=%.2e".format(e))
    }

    // ---- 4) الخط كله بموديل هوية (بيجرّب القطع والنافذة والحدود)
    for ((ov, n) in listOf(Pair(0.25, 700_000), Pair(0.0, 400_000), Pair(0.5, 123_457), Pair(0.1, 300_001))) {
        val l = FloatArray(n) { (0.35 * Math.sin(2 * Math.PI * 523.0 * it / 44100.0) + 0.2 * Math.sin(2 * Math.PI * 3100.0 * it / 44100.0)).toFloat() }
        val r = FloatArray(n) { (0.3 * Math.sin(2 * Math.PI * 330.0 * it / 44100.0) + 0.2 * Math.sin(2 * Math.PI * 8000.0 * it / 44100.0)).toFloat() }
        val f = File.createTempFile("stem", ".pcm"); writePcm(f, l, r)
        val src = StemPcmFile(f); val v = MemSink(); val m = MemSink()
        var lastPct = -1; var mono = true
        val err = StemSeparator.run(src, listOf(Pair(MdxSpec(7680, 3072, 256, 1.0f, true), IdentityRunner())), ov, v, m, { p -> if (p < lastPct) mono = false; lastPct = p }, { false })
        check("pipeline overlap=$ov n=$n no-error", err == null, err ?: "")
        check("pipeline overlap=$ov length", v.n == n && m.n == n, "v=${v.n} m=${m.n} n=$n")
        var e = 0.0; var em = 0.0
        for (i in 0 until Math.min(v.n, n)) {
            e = Math.max(e, Math.abs(v.l[i] - l[i]).toDouble()); e = Math.max(e, Math.abs(v.r[i] - r[i]).toDouble())
            em = Math.max(em, Math.abs(v.l[i] + m.l[i] - l[i]).toDouble())
        }
        check("pipeline overlap=$ov voice==mix (identity)", e < 1.5e-3, "maxErr=%.2e".format(e))
        check("pipeline overlap=$ov voice+music==mix", em < 1e-5, "maxErr=%.2e".format(em))
        check("pipeline progress monotonic", mono && lastPct in 50..99, "last=$lastPct")
        src.close(); f.delete()
    }
    // إلغاء
    run {
        val n = 300_000; val l = FloatArray(n); val f = File.createTempFile("stem", ".pcm"); writePcm(f, l, l)
        val src = StemPcmFile(f)
        val err = StemSeparator.run(src, listOf(Pair(MdxSpec(7680, 3072, 256, 1.0f, true), IdentityRunner())), 0.25, MemSink(), MemSink(), { }, { true })
        check("cancel returns message", err != null)
        src.close(); f.delete()
    }

    // ---- 5) الموديل الحقيقي (اختياري)
    val mp = System.getenv("SUBTITLER_MDX_MODEL"); val rp = System.getenv("SUBTITLER_MDX_REF")
    if (mp != null && rp != null && File(mp).isFile && File(rp + "_mix.f32").isFile) {
        val mixRaw = java.nio.ByteBuffer.wrap(File(rp + "_mix.f32").readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val estRaw = java.nio.ByteBuffer.wrap(File(rp + "_est.f32").readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = mixRaw.remaining() / 2
        val l = FloatArray(n); val r = FloatArray(n); for (i in 0 until n) { l[i] = mixRaw.get(i); r[i] = mixRaw.get(n + i) }
        val f = File.createTempFile("stem", ".pcm"); writePcm(f, l, r)
        // المرجع اتحسب على نفس الـ int16 → نقرا نفس القيم
        val src = StemPcmFile(f)
        val rn = OrtRunner(mp, 1, 3072, 256)
        check("ORT open model", rn.error == null, rn.error ?: ("ORT " + StemOrt.nVersion()))
        if (rn.error == null) {
            val v = MemSink(); val m = MemSink(); val t0 = System.currentTimeMillis()
            val err = StemSeparator.run(src, listOf(Pair(MdxSpec(7680, 3072, 256, 1.021f, true), rn)), 0.25, v, m, { p -> print("\r  $p%") }, { false })
            println()
            check("real model run", err == null, (err ?: "") + " " + (System.currentTimeMillis() - t0) / 1000 + "s")
            var num = 0.0; var den = 0.0
            for (i in 0 until n) {
                val dl = v.l[i] - estRaw.get(i); val dr = v.r[i] - estRaw.get(n + i)
                num += dl * dl + dr * dr; den += estRaw.get(i) * estRaw.get(i) + estRaw.get(n + i) * estRaw.get(n + i)
            }
            val snr = 10 * Math.log10(den / Math.max(num, 1e-20))
            check("real model matches numpy reference", snr > 40, "SNR=%.1f dB".format(snr))
            File(rp + "_kt_voice.f32").writeBytes(java.nio.ByteBuffer.allocate(n * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> for (i in 0 until n) b.putFloat(v.l[i]); for (i in 0 until n) b.putFloat(v.r[i]) }.array())
        }
        rn.close(); src.close(); f.delete()
    } else println("SKIP real-model test (SUBTITLER_MDX_MODEL / SUBTITLER_MDX_REF مش متحددين)")

    println("الاختبارات: $pass نجحت، $fail فشلت")
    if (fail > 0) System.exit(1)
}
