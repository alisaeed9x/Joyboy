package com.tttt.subtitler

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
