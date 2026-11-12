package com.tttt.subtitler

/**
 * (v243 · مرحلة 2) تطبيع أسماء الأغاني والفنانين (عربي/إنجليزي) — للبحث والمطابقة بس.
 * الاسم اللي بيتعرض للمستخدم ما بيتغيّرش أبدًا؛ التطبيع بيتستخدم جوه البحث والمقارنة.
 */
object ArNorm {
    private val TASHKEEL = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val BRACKETS = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val EXT = Regex("(?i)\\.(mp3|m4a|flac|wav|ogg|aac|opus|wma|mp4)$")
    private val TRACKNO = Regex("^\\d{1,3}\\s*[-._)]\\s*")
    private val NOISE = setOf(
        "official", "video", "audio", "lyrics", "lyric", "hd", "hq", "4k", "clip", "remastered", "music",
        "كليب", "فيديو", "اوديو", "أوديو", "كلمات", "رسمي", "الرسمي", "حصريا", "حصريًا"
    )

    /** شكل خفيف: يشيل التشكيل والتطويل ويوحّد الألف والياء المقصورة — من غير ما يمس التاء المربوطة */
    fun lite(s: String): String {
        val x = TASHKEEL.replace(s, "")
        val sb = StringBuilder()
        for (ch in x) {
            when {
                ch == '\u0640' -> {}
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> sb.append('ا')
                ch == 'ى' -> sb.append('ي')
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** شكل المطابقة: lite + ة→ه، ؤ→و، ئ→ي، وكل رمز بقى مسافة، وحروف صغيرة */
    fun fold(s: String): String {
        val sb = StringBuilder()
        for (ch in lite(s).lowercase()) {
            when {
                ch == 'ة' -> sb.append('ه')
                ch == 'ؤ' -> sb.append('و')
                ch == 'ئ' -> sb.append('ي')
                ch.isLetterOrDigit() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun tokens(s: String): Set<String> =
        fold(s).split(' ').filter { it.isNotEmpty() }.map { if (it.length > 3 && it.startsWith("ال")) it.substring(2) else it }.toSet()

    /** تشابه بين اسمين من 0 لـ 1 (كلمات مشتركة؛ والاحتواء الكامل لو الأصغر كلمتين أو أكتر) */
    fun sim(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val jac = inter / ta.union(tb).size.toDouble()
        val small = if (ta.size <= tb.size) ta else tb
        val big = if (ta.size <= tb.size) tb else ta
        return if (small.size >= 2 && big.containsAll(small)) maxOf(jac, 0.85) else jac
    }

    /** تنضيف اسم الملف/العنوان: امتداد، رقم الترتيب، الأقواس، كلمات زي (Official Video) */
    fun clean(s: String): String {
        var x = EXT.replace(s.trim(), "")
        x = x.replace('_', ' ')
        x = BRACKETS.replace(x, " ")
        x = TRACKNO.replace(x.trim(), "")
        val toks = x.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (toks.isNotEmpty() && toks.last().lowercase() in NOISE) toks.removeAt(toks.size - 1)
        return toks.joinToString(" ").trim().trim('-', '–', '—', '.', ' ')
    }

    /** صيغ البحث بالترتيب (من غير تكرار، 5 صيغ بالكتير): الأصلي ← منضّف ← خفيف ← تقسيم «فنان - أغنية» ← العنوان لوحده */
    fun variants(title: String, artist: String): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        val t0 = title.trim(); val a0 = artist.trim().let { if (it.equals("<unknown>", true) || it.equals("unknown", true)) "" else it }
        val t1 = clean(t0); val a1 = clean(a0)
        out.add(t0 to a0)
        if (t1.isNotEmpty()) out.add(t1 to a1)
        if (t1.isNotEmpty()) out.add(lite(t1) to lite(a1))
        val sep = Regex("\\s[-–—]\\s")
        if (sep.containsMatchIn(t1)) {
            val p = t1.split(sep, 2)
            if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                out.add(lite(p[1].trim()) to lite(p[0].trim()))
                out.add(lite(p[0].trim()) to lite(p[1].trim()))
            }
        }
        if (t1.isNotEmpty() && a1.isNotEmpty()) out.add(lite(t1) to "")
        return out.filter { it.first.isNotBlank() }.take(5)
    }
}
