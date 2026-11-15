package com.tttt.subtitler

/**
 * (v1001) 📞 صفحة الدردشة للمكالمات — المنطق الخالص (من غير واجهة):
 *  • تجميع الجمل المتتالية لنفس المتحدث في فقاعة واحدة (للعرض بس — الخام في d.segs مبيتغيّرش).
 *  • اسم المتحدث من parties (A→parties[0] وهكذا) أو لابل احتياطي بنفس أسلوب LectureUi.spkLabel.
 *  • «أنا» = parties[0] == "أنا"؛ ولو مفيش أطراف خالص، A هو أنا.
 *  • نبرة الفقاعة = أول نبرة مش فاضية في جملها (tone جاي من التفريغ نفسه، كلمة واحدة متطبّعة عبر Lecture.toneOf).
 */
object CallChat {

    class Bubble(val spk: String, val name: String, val text: String, val start: Double, val end: Double, val tone: String, val firstSegIdx: Int, val gender: String = "", val failedChunk: Int = -1)   // (v1036) gender: male/female/"" · failedChunk >= 0 = فقاعة «جزء ناقص» مكانه الزمني (مش جملة)

    /** حروف المتحدثين A–E بالترتيب — نفس ترتيب parties والمراجع في Studio.callPrompt. */
    private val LETTERS = listOf("A", "B", "C", "D", "E")

    fun letterIdx(spk: String): Int = if (spk.length == 1) LETTERS.indexOf(spk) else -1

    fun isMe(d: Lecture.Doc, spk: String): Boolean {
        val idx = letterIdx(spk)
        if (idx >= 0) {
            val parties = synchronized(d) { d.parties.toList() }
            if (parties.isNotEmpty()) return parties.getOrNull(idx) == "أنا"
            return idx == 0
        }
        return spk == "أنا"
    }

    /** اسم العرض للمتحدث: من parties لو الحرف متطابق، وإلا لابل احتياطي. */
    fun speakerName(d: Lecture.Doc, spk: String): String {
        val idx = letterIdx(spk)
        if (idx >= 0) {
            val parties = synchronized(d) { d.parties.toList() }
            val nm = parties.getOrNull(idx)
            if (!nm.isNullOrBlank()) return nm
            return when (spk) {
                "A" -> "🗣 أ"
                "B" -> "🗣 ب"
                "C" -> "🗣 ج"
                "D" -> "🗣 د"
                else -> "🗣 هـ"
            }
        }
        return when (spk) {
            "D" -> "🎓 المحاضر"
            "S" -> "🙋 طالب"
            "" -> ""
            else -> "🗣 " + spk
        }
    }

    /** إيموجي النبرة — الكلمات دي هي اللي البرومبت بيطلبها (هادي/بيضحك/…)، والباقي 😐 محايد. */
    fun toneEmoji(tone: String): String = when (tone.trim()) {
        "بيضحك", "مبسوط", "فرحان", "بيبتسم" -> "😄"
        "متضايق", "مستاء" -> "😠"
        "عصبي", "غاضب" -> "😡"
        "حزين", "زعلان", "مكتئب" -> "😢"
        "خايف", "قلقان", "متوتر" -> "😨"
        else -> "😐"
    }

    /** فقاعات العرض من جمل العرض المطبّعة — دمج متتالي لنفس المتحدث بس (من غير حد زمني زي SUBS).
     *  (v1036) الجمل من Lecture.orderedSegs (عتبة الترتيب)، والجزء الفاشل بيظهر فقاعة «⚠ ناقص» مكانه الزمني. */
    fun bubbles(d: Lecture.Doc): List<Bubble> {
        val segs = Lecture.orderedSegs(d)
        val fails = Lecture.failedRanges(d)
        val out = ArrayList<Bubble>()
        var curSpk = ""
        var curText = StringBuilder()
        var curStart = 0.0
        var curEnd = 0.0
        var curTone = ""
        var curFirst = 0
        fun flush() {
            if (curText.isNotEmpty()) {
                out.add(Bubble(curSpk, speakerName(d, curSpk), curText.toString().trim(), curStart, curEnd, curTone, curFirst, Lecture.genderOf(d, curSpk)))
            }
            curText = StringBuilder()
            curSpk = ""
            curTone = ""
        }
        var fi = 0
        fun flushFails(upTo: Double, idx: Int) {
            while (fi < fails.size && fails[fi].second <= upTo) {
                flush()
                val f = fails[fi]
                out.add(Bubble("⚠", "⚠ الجزء " + (f.first + 1) + " ناقص", "(" + Lecture.fmtTime(f.second) + " ← " + Lecture.fmtTime(f.third) + ") — دوس لإعادة المحاولة", f.second, f.third, "", idx, "", f.first))
                fi++
            }
        }
        for ((idx, s) in segs.withIndex()) {
            flushFails(s.start, idx)
            if (curText.isEmpty() || s.spk != curSpk) {
                flush()
                curSpk = s.spk
                curText = StringBuilder(s.text)
                curStart = s.start
                curEnd = s.end
                curTone = s.tone
                curFirst = idx
            } else {
                curText.append(' ').append(s.text)
                curEnd = s.end
                if (curTone.isBlank() && s.tone.isNotBlank()) curTone = s.tone
            }
        }
        flush()
        flushFails(Double.MAX_VALUE, segs.size)
        return out
    }
}
