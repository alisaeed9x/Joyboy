package com.vanilla.tvlauncher

import java.net.URLEncoder

/** نتيجة البحث الموحّد (FR-SRCH-*): فيديوهات + مجلدات + سجل المشاهدة. */
data class SearchResult(val videos: List<VideoItem>, val folders: List<FolderInfo>, val history: List<WatchEntry>) {
    val isEmpty: Boolean get() = videos.isEmpty() && folders.isEmpty() && history.isEmpty()
}

/**
 * منطق البحث النقي (بدون Android) عشان يتاختبر على JVM.
 * التطبيع عربي-واعي: بيشيل التشكيل والتطويل، بيوحّد الألف (أإآ→ا) والياء (ى→ي) والتاء المربوطة (ة→ه)،
 * بيحوّل الأرقام الهندية (٣) لعادية (3)، وأي رمز مش حرف/رقم (نقطة، شرطة، underscore، أقواس) بيبقى مسافة
 * عشان «Breaking.Bad.S01E02» تتلاقي بـ«breaking bad».
 * المطابقة: كل كلمة في الاستعلام لازم تكون موجودة في الاسم (بأي ترتيب).
 */
object SearchLogic {
    const val MAX_RECENT = 10
    const val BACK_LEAVE = 0
    const val BACK_CLEAR = 1

    /** Back في شاشة البحث: لو فيه نص مكتوب يتمسح الأول (خطوة واحدة)، وإلا يخرج من الشاشة. */
    fun backStep(query: String): Int = if (query.isNotBlank()) BACK_CLEAR else BACK_LEAVE

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c0 in s) {
            if (c0 == '\u0640') continue                                              // تطويل
            if (Character.getType(c0) == Character.NON_SPACING_MARK.toInt()) continue // تشكيل
            var c = when (c0) {
                '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627'  // أ إ آ ٱ → ا
                '\u0649' -> '\u064A'                                 // ى → ي
                '\u0629' -> '\u0647'                                 // ة → ه
                else -> c0
            }
            if (c in '\u0660'..'\u0669') c = '0' + (c - '\u0660')    // ٠-٩
            else if (c in '\u06F0'..'\u06F9') c = '0' + (c - '\u06F0') // ۰-۹
            sb.append(if (c.isLetterOrDigit()) c.lowercaseChar() else ' ')
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }

    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    private fun matches(nameNorm: String, toks: List<String>): Boolean = toks.isNotEmpty() && toks.all { nameNorm.contains(it) }

    /** 0 = الاسم بيبدأ بالاستعلام · 1 = كلمة في الاسم بتبدأ بأول كلمة · 2 = باقي المطابقات */
    private fun rank(nameNorm: String, qNorm: String, first: String): Int =
        if (nameNorm.startsWith(qNorm)) 0 else if (" $nameNorm".contains(" $first")) 1 else 2

    fun search(items: List<VideoItem>, excluded: Set<String>, history: List<WatchEntry>, query: String): SearchResult {
        val toks = tokens(query)
        if (toks.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList())
        val qNorm = toks.joinToString(" "); val first = toks[0]
        val vis = LibraryLogic.visible(items, excluded)
        val vids = vis.map { it to normalize(it.name) }.filter { matches(it.second, toks) }
            .sortedWith(compareBy<Pair<VideoItem, String>> { rank(it.second, qNorm, first) }.thenComparator { a, b -> LibraryLogic.naturalCompare(a.first.name, b.first.name) })
            .map { it.first }
        val folders = LibraryLogic.folders(items, excluded).filter { matches(normalize(it.name), toks) }
            .sortedWith { a, b -> LibraryLogic.naturalCompare(a.name, b.name) }
        val hist = history.filter { matches(normalize(it.name), toks) }   // بترتيب السجل (الأحدث أولًا)
        return SearchResult(vids, folders, hist)
    }

    // ===== عمليات البحث الأخيرة =====
    fun encodeRecents(l: List<String>): String = l.joinToString("\n") { it.replace('\n', ' ').replace('\r', ' ') }
    fun decodeRecents(s: String): List<String> = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_RECENT)

    /** الأحدث أولًا، بدون تكرار (بعد التطبيع)، بحد أقصى MAX_RECENT. أقل من حرفين = مايتسجّلش. */
    fun addRecent(list: List<String>, q: String): List<String> {
        val t = q.trim().replace(Regex("\\s+"), " ")
        if (t.length < 2) return list
        val key = normalize(t)
        return (listOf(t) + list.filter { normalize(it) != key }).take(MAX_RECENT)
    }
    fun removeRecent(list: List<String>, q: String): List<String> = list.filter { it != q }

    /** رابط البحث على يوتيوب — بيتفتح بس بعد اختيار صريح من المستخدم (مش تلقائي أبدًا). */
    fun youtubeSearchUrl(q: String): String = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q.trim(), "UTF-8")
}
