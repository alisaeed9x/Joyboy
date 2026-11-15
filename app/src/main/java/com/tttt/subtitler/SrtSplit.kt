package com.tttt.subtitler

/**
 * (v1031 — المرحلة 1) «تقطيع لسطور ترجمة»: التفريغ بيرجّع جمل طويلة (٣–١٥ ثانية للوحدة)،
 * وده شكل «صفحات» مش سطور ترجمة. الكلاس ده بيقسّم الجملة الطويلة لسطور ترجمة قصيرة:
 *  • حد أقصى للمدة ~٦.٥ ثانية للسطر.
 *  • حد أقصى ~٤٢ حرف للسطر الواحد × سطرين = ٨٤ حرف للـcue.
 *  • القصّ بيتم على علامات الترقيم الأول، وبعدها على الكلمات (وعلى الحروف لو كلمة واحدة طويلة).
 *  • التوقيت بيتوزّع بنسبة عدد الحروف، والسطور متتالية من غير فجوات ولا تداخل (آخر سطر بينتهي عند نهاية الجملة الأصلية بالظبط).
 * مهم: الدالة حتمية (نفس الجملة ← نفس القطع دايمًا) عشان مفاتيح الترجمة المحفوظة (بداية×١٠|نص) تفضل متطابقة
 * بين التشغيلات. والجملة القصيرة (اللي مش محتاجة تقطيع) بترجع زي ما هي حرفيًا — نفس النص ونفس التوقيت.
 * لا بتغيّر Lecture.Doc.segs الخام أبدًا؛ ده طبقة عرض/ترجمة بس.
 */
object SrtSplit {
    const val MAX_DUR = 6.5
    const val MAX_LINE = 42
    const val MAX_CHARS = MAX_LINE * 2
    private const val MIN_PIECE_SEC = 0.7
    private const val MIN_TAIL_CHARS = 12

    private val PUNCT = setOf('.', '!', '?', '؟', '…', ',', '،', ';', '؛', ':', '。', '！', '？', '、')
    private val STRONG = setOf('.', '!', '?', '؟', '…', '。', '！', '？')

    private fun endsPunct(w: String): Boolean = w.isNotEmpty() && w.last() in PUNCT
    private fun endsStrong(w: String): Boolean = w.isNotEmpty() && w.last() in STRONG

    /** كام سطر محتاجين للجملة دي */
    fun piecesNeeded(start: Double, end: Double, text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return 1
        val dur = (end - start).coerceAtLeast(0.1)
        val byDur = Math.ceil(dur / MAX_DUR).toInt()
        val byLen = Math.ceil(t.length / MAX_CHARS.toDouble()).toInt()
        return maxOf(1, byDur, byLen)
    }

    /** يقسّم جملة واحدة لسطور. لو مش محتاجة تقطيع بيرجّع cue واحد بنفس النص والتوقيت بالظبط. */
    fun split(start: Double, end: Double, text: String): List<SubFileTr.Cue> {
        val n = piecesNeeded(start, end, text)
        if (n <= 1) return listOf(SubFileTr.Cue(start, end, text))
        val t = text.trim().replace(Regex("\\s+"), " ")
        // كلمة طويلة جدًا (مفيش مسافات — صيني/ياباني أو لزق): قصّ على الحروف
        val words = ArrayList<String>()
        for (w in t.split(' ')) {
            if (w.isEmpty()) continue
            if (w.length <= MAX_LINE) words.add(w)
            else w.chunked(MAX_LINE).forEach { words.add(it) }
        }
        if (words.size < 2) return listOf(SubFileTr.Cue(start, end, text))
        val target = t.length / n.toDouble()
        val pieces = ArrayList<String>()
        var cur = StringBuilder()
        var prev = ""
        for (w in words) {
            val candLen = if (cur.isEmpty()) w.length else cur.length + 1 + w.length
            val canBreakMore = pieces.size < n - 1
            val overHard = cur.isNotEmpty() && candLen > MAX_CHARS
            val softBreak = cur.isNotEmpty() && canBreakMore && (
                (cur.length >= target * 0.8 && endsPunct(prev)) ||
                (cur.length >= target * 0.55 && endsStrong(prev)) ||
                cur.length >= target * 1.2
            )
            if (overHard || softBreak) {
                pieces.add(cur.toString())
                cur = StringBuilder()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
            prev = w
        }
        if (cur.isNotEmpty()) pieces.add(cur.toString())
        // ذيل قصير جدًا ← يتدمج في اللي قبله (لو مفيش تخطّي للحد الأقصى)
        if (pieces.size >= 2 && pieces.last().length < MIN_TAIL_CHARS &&
            pieces[pieces.size - 2].length + 1 + pieces.last().length <= MAX_CHARS + 8) {
            val last = pieces.removeAt(pieces.size - 1)
            pieces[pieces.size - 1] = pieces[pieces.size - 1] + " " + last
        }
        if (pieces.size < 2) return listOf(SubFileTr.Cue(start, end, text))
        // توزيع التوقيت بنسبة الحروف (متتالي، آخر سطر بينتهي عند end بالظبط)
        val dur = (end - start).coerceAtLeast(0.1)
        val total = pieces.sumOf { it.length }.coerceAtLeast(1)
        val out = ArrayList<SubFileTr.Cue>(pieces.size)
        var acc = 0
        var s = start
        for ((i, p) in pieces.withIndex()) {
            acc += p.length
            var e = if (i == pieces.size - 1) end else start + dur * acc / total
            e = Math.round(e * 1000.0) / 1000.0
            if (i < pieces.size - 1 && e - s < MIN_PIECE_SEC && dur / pieces.size >= MIN_PIECE_SEC) e = s + MIN_PIECE_SEC
            if (i == pieces.size - 1) e = end
            if (e <= s) e = s + 0.1
            out.add(SubFileTr.Cue(s, e, p))
            s = e
        }
        return out
    }

    /** تقطيع قايمة جمل (Seg) لسطور ترجمة بالترتيب الزمني. */
    fun cues(segs: List<Lecture.Seg>): List<SubFileTr.Cue> {
        val out = ArrayList<SubFileTr.Cue>(segs.size + segs.size / 2)
        for (s in segs.sortedBy { it.start }) out.addAll(split(s.start, s.end, s.text))
        return out
    }
}
