package com.tttt.subtitler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * (v463) مولّد QR محلي بالكامل من غير أي مكتبة: وضع Byte، مستوى تصحيح M، إصدارات 1–6
 * (السعة لحد ~106 بايت — بتكفي روابط «اكتب من الموبايل» القصيرة).
 * Reed-Solomon على GF(256) بجداول مولّدة، تشابك بلوكات، واختيار أفضل قناع بقواعد الجزاء القياسية.
 */
object QrCode {
    private class Spec(val data: Int, val ec: Int, val blocks: Int, val rem: Int, val align: IntArray)
    private val SPECS = arrayOf(
        Spec(16, 10, 1, 0, intArrayOf()),          // v1-M
        Spec(28, 16, 1, 7, intArrayOf(6, 18)),    // v2-M
        Spec(44, 26, 1, 7, intArrayOf(6, 22)),    // v3-M
        Spec(64, 18, 2, 7, intArrayOf(6, 26)),    // v4-M
        Spec(86, 24, 2, 7, intArrayOf(6, 30)),    // v5-M
        Spec(108, 16, 4, 7, intArrayOf(6, 34))    // v6-M
    )
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun rsGen(ec: Int): IntArray {
        var p = intArrayOf(1)
        for (i in 0 until ec) {
            val c = EXP[i]
            val np = IntArray(p.size + 1)
            for (j in np.indices) {
                var v = 0
                if (j < p.size) v = v xor p[j]
                if (j > 0) v = v xor mul(c, p[j - 1])
                np[j] = v
            }
            p = np
        }
        return p.copyOfRange(1, p.size)   // من غير المعامل القائد (1)
    }

    private fun rsEc(data: IntArray, gen: IntArray, ec: Int): IntArray {
        val res = IntArray(ec)
        for (b in data) {
            val factor = b xor res[0]
            res.copyInto(res, 0, 1, ec); res[ec - 1] = 0
            if (factor != 0) for (i in 0 until ec) res[i] = res[i] xor mul(gen[i], factor)
        }
        return res
    }

    /** بيرجّع مصفوفة الوحدات (true = غامق) من غير منطقة الهدوء، أو null لو النص أطول من السعة */
    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val vi = SPECS.indices.firstOrNull { bytes.size <= SPECS[it].data - 2 } ?: return null
        val ver = vi + 1
        val spec = SPECS[vi]
        // تيار البتات: وضع + طول + بيانات + إنهاء + حشو
        val bits = ArrayList<Int>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((v shr i) and 1) }
        put(0b0100, 4); put(bytes.size, 8)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        val capBits = spec.data * 8
        repeat(minOf(4, capBits - bits.size)) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        val pads = intArrayOf(0xEC, 0x11); var pi = 0
        while (bits.size < capBits) { put(pads[pi % 2], 8); pi++ }
        val dataCw = IntArray(spec.data) { i -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or bits[i * 8 + k] } }
        // بلوكات وتصحيح وتشابك
        val per = spec.data / spec.blocks
        val gen = rsGen(spec.ec)
        val blocksData = Array(spec.blocks) { b -> dataCw.copyOfRange(b * per, (b + 1) * per) }
        val blocksEc = Array(spec.blocks) { b -> rsEc(blocksData[b], gen, spec.ec) }
        val fin = ArrayList<Int>()
        for (i in 0 until per) for (b in 0 until spec.blocks) fin.add(blocksData[b][i])
        for (i in 0 until spec.ec) for (b in 0 until spec.blocks) fin.add(blocksEc[b][i])
        val finBits = ArrayList<Int>()
        for (cw in fin) for (i in 7 downTo 0) finBits.add((cw shr i) and 1)
        repeat(spec.rem) { finBits.add(0) }
        // المصفوفة
        val size = ver * 4 + 17
        val mod = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }   // وحدات وظيفية (مش بيانات)
        fun setF(r: Int, c: Int, v: Boolean) { if (r in 0 until size && c in 0 until size) { mod[r][c] = v; fn[r][c] = true } }
        fun finder(r0: Int, c0: Int) {
            for (dr in -1..7) for (dc in -1..7) {
                val dark = dr in 0..6 && dc in 0..6 && (dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4))
                setF(r0 + dr, c0 + dc, dark)
            }
        }
        finder(0, 0); finder(0, size - 7); finder(size - 7, 0)
        for (i in 8 until size - 8) { setF(6, i, i % 2 == 0); setF(i, 6, i % 2 == 0) }
        for (a in spec.align) for (b in spec.align) {
            if (fn[a][b]) continue   // مكان باينر (زاوية) — بيتخطى
            for (dr in -2..2) for (dc in -2..2) setF(a + dr, b + dc, maxOf(Math.abs(dr), Math.abs(dc)) != 1)
        }
        setF(size - 8, 8, true)   // الوحدة الغامقة
        // حجز أماكن معلومات الشكل
        val fmtPos1 = listOf(8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8, 7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8)
        val fmtPos2 = (0 until 7).map { (size - 1 - it) to 8 } + (0 until 8).map { 8 to (size - 8 + it) }
        (fmtPos1 + fmtPos2).forEach { (r, c) -> fn[r][c] = true }
        // حط البيانات (زيجزاج من تحت يمين)
        var bit = 0; var c = size - 1; var up = true
        while (c > 0) {
            if (c == 6) c--
            for (rr in (if (up) (size - 1) downTo 0 else 0 until size)) {
                for (cc in intArrayOf(c, c - 1)) {
                    if (cc >= 0 && !fn[rr][cc]) { mod[rr][cc] = if (bit < finBits.size) finBits[bit] == 1 else false; bit++ }
                }
            }
            c -= 2; up = !up
        }
        // جرّب الأقنعة واختار أقل جزاء
        fun maskBit(m: Int, r: Int, c: Int): Boolean = when (m) {
            0 -> (r + c) % 2 == 0
            1 -> r % 2 == 0
            2 -> c % 3 == 0
            3 -> (r + c) % 3 == 0
            4 -> (r / 2 + c / 3) % 2 == 0
            5 -> (r * c) % 2 + (r * c) % 3 == 0
            6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
            else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
        }
        fun formatBits(m: Int): Int {
            val data5 = m   // مستوى M = 00 في البتين اللي فوق
            var rem = data5
            for (i in 0 until 10) rem = (rem shl 1) xor (if (rem shr 9 != 0) 0x537 else 0)
            return ((data5 shl 10) or (rem and 0x3FF)) xor 0x5412
        }
        fun drawFormat(m: Array<BooleanArray>, msk: Int) {
            val f = formatBits(msk)
            fmtPos1.forEachIndexed { i, (rr, cc) -> m[rr][cc] = ((f shr (14 - i)) and 1) == 1 }
            fmtPos2.forEachIndexed { i, (rr, cc) -> m[rr][cc] = ((f shr (14 - i)) and 1) == 1 }
        }
        fun penalty(m: Array<BooleanArray>): Int {
            var pen = 0
            fun linePen(line: BooleanArray): Int {
                var p = 0; var run = 1
                for (i in 1 until line.size) { if (line[i] == line[i - 1]) run++ else { if (run >= 5) p += run - 2; run = 1 } }
                if (run >= 5) p += run - 2
                for (i in 0..line.size - 11) {
                    val s = (0 until 11).map { if (line[i + it]) 1 else 0 }
                    if (s == listOf(1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0) || s == listOf(0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1)) p += 40
                }
                return p
            }
            for (i in 0 until size) {
                pen += linePen(m[i])
                pen += linePen(BooleanArray(size) { m[it][i] })
            }
            for (rr in 0 until size - 1) for (cc in 0 until size - 1) {
                val v = m[rr][cc]; if (m[rr][cc + 1] == v && m[rr + 1][cc] == v && m[rr + 1][cc + 1] == v) pen += 3
            }
            var dark = 0; for (rr in 0 until size) for (cc in 0 until size) if (m[rr][cc]) dark++
            val pct = dark * 100.0 / (size * size)
            val lo = Math.floor(pct / 5).toInt() * 5; val hi = Math.ceil(pct / 5).toInt() * 5
            pen += minOf(Math.abs(lo - 50), Math.abs(hi - 50)) / 5 * 10
            return pen
        }
        var best: Array<BooleanArray>? = null; var bestPen = Int.MAX_VALUE
        for (m in 0..7) {
            val cand = Array(size) { rr -> BooleanArray(size) { cc ->
                if (fn[rr][cc]) mod[rr][cc] else mod[rr][cc] xor maskBit(m, rr, cc)
            } }
            drawFormat(cand, m)
            val p = penalty(cand)
            if (p < bestPen) { bestPen = p; best = cand }
        }
        return best
    }

    /** رسم المصفوفة كصورة بمنطقة هدوء 4 وحدات حوالين الكود */
    fun bitmap(text: String, px: Int): Bitmap? {
        val m = encode(text) ?: return null
        val size = m.size
        val cells = size + 8
        val scale = (px / cells).coerceAtLeast(1)
        val dim = scale * cells
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK }
        for (r in 0 until size) for (c in 0 until size) if (m[r][c]) {
            val x = ((c + 4) * scale).toFloat(); val y = ((r + 4) * scale).toFloat()
            cv.drawRect(x, y, x + scale, y + scale, paint)
        }
        return bmp
    }
}
