package com.vanilla.tvlauncher

/** نموذج فيديو واحد في المكتبة. key = معرّف مستقر للسجل (D-007): الاسم+الحجم+المدة، مش رقم MediaStore (بيتغيّر). */
data class VideoItem(
    val id: Long, val uri: String, val name: String,
    val folderKey: String, val folderName: String,
    val dateMs: Long, val durMs: Long, val size: Long
) {
    val key: String get() = "$name|$size|$durMs"
}

data class FolderInfo(val key: String, val name: String, val count: Int, val newestMs: Long)

/** منطق المكتبة النقي (تجميع/فرز/استبعاد/ترميز) — بدون Android عشان يتاختبر على JVM. */
object LibraryLogic {
    const val ALL = "*"
    const val SORT_NAME = "name"; const val SORT_DATE = "date"; const val SORT_PLACE = "place"
    val SORTS = listOf(SORT_NAME, SORT_DATE, SORT_PLACE)

    /** «تجنب فهرسة نفس الملف مرتين»: نفس id مرة واحدة */
    fun dedupeById(items: List<VideoItem>): List<VideoItem> {
        val seen = HashSet<Long>(); val out = ArrayList<VideoItem>(items.size)
        for (v in items) if (seen.add(v.id)) out.add(v)
        return out
    }

    fun visible(items: List<VideoItem>, excluded: Set<String>): List<VideoItem> =
        if (excluded.isEmpty()) items else items.filter { it.folderKey !in excluded }

    fun folders(items: List<VideoItem>, excluded: Set<String>): List<FolderInfo> =
        visible(items, excluded).groupBy { it.folderKey }.map { (k, v) ->
            FolderInfo(k, v.first().folderName, v.size, v.maxOf { it.dateMs })
        }

    /** المجلدات المستبعدة فقط (لشاشة الإعدادات): اللي مش موجودة في المكتبة دلوقتي بترجع باسم = المفتاح وعدّ 0 */
    fun excludedFolders(items: List<VideoItem>, excluded: Set<String>): List<FolderInfo> {
        val known = folders(items, emptySet()).associateBy { it.key }
        return excluded.map { k -> known[k] ?: FolderInfo(k, k, 0, 0L) }
    }

    /** ترتيب طبيعي: «حلقة 2» قبل «حلقة 10»، وبدون حساسية لحالة الحروف */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun sortItems(items: List<VideoItem>, sort: String): List<VideoItem> = when (sort) {
        SORT_DATE -> items.sortedByDescending { it.dateMs }
        SORT_PLACE -> items.sortedWith(Comparator { x, y ->
            val c = naturalCompare(x.folderName, y.folderName); if (c != 0) c else naturalCompare(x.name, y.name) })
        else -> items.sortedWith(Comparator { x, y -> naturalCompare(x.name, y.name) })
    }

    fun sortFolders(f: List<FolderInfo>, sort: String): List<FolderInfo> =
        if (sort == SORT_DATE) f.sortedByDescending { it.newestMs } else f.sortedWith(Comparator { x, y -> naturalCompare(x.name, y.name) })

    fun inFolder(items: List<VideoItem>, excluded: Set<String>, key: String): List<VideoItem> =
        if (key == ALL) visible(items, excluded) else visible(items, excluded).filter { it.folderKey == key }

    fun formatDur(ms: Long): String {
        if (ms <= 0) return "--:--"
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ===== ترميز الكاش المحلي (سطر لكل فيديو، حقول مفصولة بـ TAB، هروب \\ \t \n) =====
    private const val HEADER = "VTL-LIB-1"
    internal fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    internal fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); else -> sb.append(s[i + 1]) }; i += 2 }
            else { sb.append(c); i++ }
        }
        return sb.toString()
    }
    fun encode(items: List<VideoItem>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (v in items) sb.append(v.id).append('\t').append(esc(v.uri)).append('\t').append(esc(v.name)).append('\t')
            .append(esc(v.folderKey)).append('\t').append(esc(v.folderName)).append('\t')
            .append(v.dateMs).append('\t').append(v.durMs).append('\t').append(v.size).append('\n')
        return sb.toString()
    }
    /** كاش تالف/نسخة تانية → قايمة فاضية (مش كراش) */
    fun decode(text: String): List<VideoItem> {
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0] != HEADER) return emptyList()
        val out = ArrayList<VideoItem>()
        for (ln in lines.drop(1)) {
            if (ln.isEmpty()) continue
            val p = ln.split('\t'); if (p.size != 8) continue
            try { out.add(VideoItem(p[0].toLong(), unesc(p[1]), unesc(p[2]), unesc(p[3]), unesc(p[4]), p[5].toLong(), p[6].toLong(), p[7].toLong())) }
            catch (_: NumberFormatException) { }
        }
        return out
    }
}
