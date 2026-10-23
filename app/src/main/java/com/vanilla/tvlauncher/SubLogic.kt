package com.vanilla.tvlauncher

/**
 * ترجمة خارجية (.srt/.vtt/.ass) للفيديو المحلي + رسم الترجمة المصوّرة (P15، D-018). نقي (بدون Android) → بيتختبر على JVM.
 * المصدر: (1) ملف اختاره المستخدم بمُنتقي النظام (SAF) ومربوط بالفيديو (key من D-007) (2) ملف في مجلد التطبيق الخاص `files/subs` باسم الفيديو.
 */
object SubLogic {
    private val MIMES = mapOf("srt" to "application/x-subrip", "vtt" to "text/vtt", "ass" to "text/x-ssa", "ssa" to "text/x-ssa", "ttml" to "application/ttml+xml")
    const val MAP_CAP = 400   // كل فيديو = مدخلين (الرابط + الاسم الظاهر) → 200 فيديو
    const val UNSET = -3.4028235E38f   // = Cue.DIMEN_UNSET في media3

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun mimeFor(name: String): String? = MIMES[ext(name)]
    fun isSubName(name: String): Boolean = mimeFor(name) != null
    fun baseName(name: String): String = if (name.contains('.')) name.substringBeforeLast('.') else name

    /** لغة من اسم زي movie.ar.srt أو movie.en.forced.srt (جزء 2-3 حروف قبل الامتداد). null = مش معروفة */
    fun langOf(name: String): String? {
        val parts = baseName(name).split('.')
        if (parts.size < 2) return null
        return parts.drop(1).lastOrNull { it.length in 2..3 && it.all { c -> c.isLetter() } && it.lowercase() !in setOf("srt", "sub", "txt") }?.lowercase()
    }

    /** ملفات ترجمة في مجلد names تخص الفيديو: نفس الاسم الأساسي أو الاسم + ".<لغة>". الأقرب اسمًا الأول، والعربي قبل غيره */
    fun matchSidecars(videoName: String, names: List<String>): List<String> {
        val vb = baseName(videoName).lowercase()
        return names.filter { isSubName(it) }.filter { val b = baseName(it).lowercase(); b == vb || b.startsWith("$vb.") }
            .sortedWith(compareBy({ baseName(it).length }, { if (langOf(it) == "ar") 0 else 1 }, { it.lowercase() }))
    }

    fun label(name: String): String = "📄 " + (langOf(name)?.let { "[$it] " } ?: "") + name

    // ----- ربط فيديو ← ترجمة (نص: سطر لكل ربط، key<TAB>uri، الأحدث في الآخر) -----
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    fun decodeMap(csv: String): List<Pair<String, String>> =
        csv.split("\n").mapNotNull { l -> val p = l.split("\t", limit = 2); if (p.size == 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) p[0] to p[1] else null }
    fun encodeMap(m: List<Pair<String, String>>): String = m.joinToString("\n") { it.first + "\t" + it.second }
    fun mapPut(m: List<Pair<String, String>>, key: String, uri: String): List<Pair<String, String>> {
        val k = clean(key); val u = clean(uri)
        val out = m.filter { it.first != k } + (k to u)
        return if (out.size > MAP_CAP) out.takeLast(MAP_CAP) else out
    }
    fun mapGet(m: List<Pair<String, String>>, key: String): String? = m.lastOrNull { it.first == clean(key) }?.second
    fun mapRemove(m: List<Pair<String, String>>, key: String): List<Pair<String, String>> = m.filter { it.first != clean(key) }

    /** اسم قابل للعرض لرابط content:// (آخر جزء بعد / أو %2F) */
    fun shortName(uri: String): String = uri.substringAfterLast("%2F").substringAfterLast('/').let { try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Throwable) { it } }

    /**
     * مستطيل رسم ترجمة مصوّرة جوه حاوية boxW×boxH (مقاس صورة الفيديو). كل القيم نسب 0..1 زي Cue في media3.
     * anchor: 0 بداية · 1 منتصف · 2 نهاية. بدون position/line = منتصف أسفل. بيرجّع [left, top, right, bottom] وبيتقصّ جوه الحاوية.
     */
    fun bitmapRect(boxW: Float, boxH: Float, bmpW: Int, bmpH: Int, position: Float, posAnchor: Int, line: Float, lineAnchor: Int, size: Float, bitmapHeight: Float): FloatArray {
        if (boxW <= 0f || boxH <= 0f || bmpW <= 0 || bmpH <= 0) return floatArrayOf(0f, 0f, 0f, 0f)
        val w = if (size > 0f && size != UNSET) size * boxW else bmpW.toFloat().coerceAtMost(boxW)
        val h = if (bitmapHeight > 0f && bitmapHeight != UNSET) bitmapHeight * boxH else w * bmpH / bmpW
        val posSet = position != UNSET && position >= 0f
        val lineSet = line != UNSET && line >= 0f
        var left = if (posSet) position * boxW else (boxW - w) / 2f
        if (posSet) left -= when (posAnchor) { 1 -> w / 2f; 2 -> w; else -> 0f }
        var top = if (lineSet) line * boxH else boxH - h - boxH * 0.05f
        if (lineSet) top -= when (lineAnchor) { 1 -> h / 2f; 2 -> h; else -> 0f }
        left = left.coerceIn(0f, (boxW - w).coerceAtLeast(0f)); top = top.coerceIn(0f, (boxH - h).coerceAtLeast(0f))
        return floatArrayOf(left, top, left + w, top + h)
    }
}
