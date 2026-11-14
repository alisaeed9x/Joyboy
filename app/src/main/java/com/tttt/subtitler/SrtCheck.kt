package com.tttt.subtitler

/**
 * (v1003 — المرحلة 5) فحص جودة SRT — منطق نقي، من غير I/O:
 *  • check: فحص صارم على النص الخام (مش بس round-trip عبر parseSrt): BOM، ترقيم الجمل، صيغة
 *    التوقيت، نهاية ≤ بداية، تداخل، ترتيب البدايات، جمل فاضية، سطور طويلة (>42 حرف/سطر أو
 *    >سطرين — تنبيه بس)، وآخر نهاية بتعدّي مدة الفيديو لو durSec متبعتة.
 *  • fix: بيطبّق الإصلاحات الآمنة بس — ترتيب حسب البداية، إعادة ترقيم 1..n، حذف الجمل الفاضية،
 *    وتقليم نهاية ≤ بداية لـ end = start + 0.5s — وبيرجع النص عبر PlayerLogic.toSrt
 *    (مفيش كاتب SRT جديد). التداخل والسطور الطويلة مبيتصلحوش هنا — تنبيه بس.
 */
object SrtCheck {

    class Issue(val line: Int, val msg: String, val fixable: Boolean)

    private val TS_RX = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")
    private val NUM_RX = Regex("^\\d+$")

    private class Cue(val num: Int?, val numLine: Int, val start: Double?, val end: Double?, val tsLine: Int, val text: String, val textLines: Int, val blockLine: Int)

    private fun sec(h: String, m: String, s: String, ms: String): Double {
        return h.toInt() * 3600.0 + m.toInt() * 60 + s.toInt() + ms.padEnd(3, '0').take(3).toInt() / 1000.0
    }

    private fun parseBlocks(text: String): List<Cue> {
        val lines = text.replace("\r", "").lines()
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            while (i < lines.size && lines[i].isBlank()) i++
            if (i >= lines.size) break
            val blockLine = i + 1
            val block = ArrayList<Pair<Int, String>>()
            while (i < lines.size && lines[i].isNotBlank()) {
                block.add(Pair(i + 1, lines[i]))
                i++
            }
            if (block.isEmpty()) continue
            var num: Int? = null
            var numLine = block[0].first
            var at = 0
            if (NUM_RX.matches(block[0].second.trim())) {
                num = block[0].second.trim().toIntOrNull()
                at = 1
            }
            var tsIdx = -1
            for (j in at until block.size) {
                if (block[j].second.contains("-->")) {
                    tsIdx = j
                    break
                }
            }
            if (tsIdx < 0) {
                cues.add(Cue(num, numLine, null, null, block[0].first, "", 0, blockLine))
                continue
            }
            val tsLine = block[tsIdx].first
            val g = TS_RX.find(block[tsIdx].second)?.groupValues
            if (g == null) {
                cues.add(Cue(num, numLine, null, null, tsLine, "", 0, blockLine))
                continue
            }
            val st = sec(g[1], g[2], g[3], g[4])
            val en = sec(g[5], g[6], g[7], g[8])
            val bodyLines = block.drop(tsIdx + 1).map { it.second }
            val body = bodyLines.joinToString("\n").trim()
            cues.add(Cue(num, numLine, st, en, tsLine, body, bodyLines.count { it.isNotBlank() }, blockLine))
        }
        return cues
    }

    fun check(text: String, durSec: Double = 0.0): List<Issue> {
        val out = ArrayList<Issue>()
        if (text.startsWith("\uFEFF")) out.add(Issue(1, "الملف بيبدأ بـ BOM — بيتشال في الإصلاح", true))
        val clean = text.removePrefix("\uFEFF")
        if (clean.isBlank()) {
            out.add(Issue(1, "ملف SRT فاضي", false))
            return out
        }
        val cues = parseBlocks(clean)
        if (cues.isEmpty()) {
            out.add(Issue(1, "مفيش ولا جملة بتوقيت صالح", false))
            return out
        }
        var expected = 1
        for (c in cues) {
            if (c.num == null) {
                out.add(Issue(c.blockLine, "رقم الجملة ناقص (المتوقع $expected)", true))
            } else if (c.num != expected) {
                out.add(Issue(c.numLine, "ترقيم مش متسلسل (لقيت ${c.num} والمتوقع $expected)", true))
            }
            expected++
            if (c.start == null || c.end == null) {
                out.add(Issue(c.tsLine, "سطر التوقيت مش صالح أو ناقص", false))
                continue
            }
            if (c.end <= c.start) out.add(Issue(c.tsLine, "نهاية الجملة قبل بدايتها أو مساوية ليها", true))
            if (c.text.isBlank()) {
                out.add(Issue(c.tsLine, "جملة فاضية من غير نص", true))
            } else {
                if (c.textLines > 2) out.add(Issue(c.tsLine, "الجملة أكتر من سطرين — صعبة القراءة", false))
                for (line in c.text.lines()) {
                    if (line.length > 42) {
                        out.add(Issue(c.tsLine, "سطر أطول من 42 حرف (${line.length}) — صعب القراءة", false))
                        break
                    }
                }
            }
        }
        var prevStart = -1.0
        var prevEnd = -1.0
        var lastTsLine = 1
        var lastEnd = -1.0
        for (c in cues) {
            val st = c.start ?: continue
            val en = c.end ?: continue
            lastTsLine = c.tsLine
            lastEnd = en
            if (prevStart >= 0 && st < prevStart) out.add(Issue(c.tsLine, "البدايات مش مرتبة (بتبدأ قبل الجملة اللي قبلها)", true))
            if (prevEnd >= 0 && st < prevEnd) out.add(Issue(c.tsLine, "تداخل مع الجملة اللي قبلها", false))
            if (st > prevStart) prevStart = st
            if (en > prevEnd) prevEnd = en
        }
        if (durSec > 0 && lastEnd > durSec) out.add(Issue(lastTsLine, "آخر جملة بتعدّي مدة الفيديو", false))
        return out
    }

    // إصلاحات آمنة بس: ترتيب، إعادة ترقيم، حذف الفاضي، وتقليم نهاية ≤ بداية. التداخل والسطور
    // الطويلة مبيتصلحوش — بيفضلوا تنبيهات في check(). لو مفيش ولا جملة صالحة بيرجّع النص من غير BOM.
    fun fix(text: String): String {
        val clean = text.removePrefix("\uFEFF")
        val triples = PlayerLogic.parseSrt(clean)
        if (triples.isEmpty()) return clean
        val subs = ArrayList<Sub>()
        for ((s, e, body) in triples.sortedBy { it.first }) {
            val st = s.coerceAtLeast(0.0)
            var en = e
            if (en <= st) en = st + 0.5
            subs.add(Sub(st, en, body.trim(), "", "", "", "", emptyList(), emptyList(), false, false))
        }
        return PlayerLogic.toSrt(subs)
    }
}
