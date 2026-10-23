package com.vanilla.tvlauncher

/** سطر في سجل المشاهدة. key = VideoItem.key (معرّف مستقر D-007). completed لا يتحدد إلا بالموضع/نهاية التشغيل، مش بإغلاق التطبيق. */
data class WatchEntry(
    val key: String, val uri: String, val name: String, val folderKey: String,
    val posMs: Long, val durMs: Long, val lastAt: Long, val completed: Boolean
)

/** منطق السجل النقي (بدون Android). السابق/التالي بتاع قايمة التشغيل منفصل تمامًا (PlayQueue) — السجل ما بيتحركش بيه. */
object HistoryLogic {
    const val MIN_RESUME_MS = 5_000L      // أقل من كده = ما يتسجّلش ولا يتستكمل
    const val REWIND_MS = 3_000L          // الاستكمال بيرجع 3 ثواني ورا
    const val DONE_RATIO = 0.95
    const val DONE_TAIL_MS = 15_000L      // للفيديوهات الطويلة (> دقيقتين): آخر 15 ثانية = خلص
    const val MAX_ENTRIES = 500

    fun isDone(pos: Long, dur: Long): Boolean =
        dur > 0 && (pos >= (dur * DONE_RATIO).toLong() || (dur > 120_000L && dur - pos <= DONE_TAIL_MS))

    /**
     * تسجيل موضع. ended=true فقط لما المشغّل يبلّغ فعلًا بانتهاء الفيديو.
     * - أقل من 5 ثواني وما خلصش: ما يتسجّلش، ولا يمسح تقدّم موجود (فتح بالغلط ما يضيّعش 40 دقيقة).
     * - خلص: posMs = 0 و completed = true.
     * - نفس key = سطر واحد يتنقل لأول القايمة.
     */
    fun record(list: List<WatchEntry>, v: VideoItem, pos: Long, dur: Long, now: Long, ended: Boolean = false): List<WatchEntry> {
        val d = if (dur > 0) dur else v.durMs
        val done = ended || isDone(pos, d)
        if (!done && pos < MIN_RESUME_MS) return list
        val e = WatchEntry(v.key, v.uri, v.name, v.folderKey, if (done) 0L else pos, d, now, done)
        val out = ArrayList<WatchEntry>(list.size + 1)
        out.add(e)
        for (x in list) if (x.key != v.key) out.add(x)
        return if (out.size > MAX_ENTRIES) out.subList(0, MAX_ENTRIES).toList() else out
    }

    fun resumePos(e: WatchEntry?): Long =
        if (e == null || e.completed || e.posMs < MIN_RESUME_MS) 0L else maxOf(0L, e.posMs - REWIND_MS)

    /** صف «متابعة المشاهدة»: غير مكتمل، الأحدث أولًا */
    fun continueList(list: List<WatchEntry>, limit: Int): List<WatchEntry> =
        list.filter { !it.completed && it.posMs >= MIN_RESUME_MS }.sortedByDescending { it.lastAt }.take(limit)

    fun remove(list: List<WatchEntry>, key: String): List<WatchEntry> = list.filter { it.key != key }

    fun search(list: List<WatchEntry>, q: String): List<WatchEntry> {
        val t = q.trim(); if (t.isEmpty()) return list
        return list.filter { it.name.contains(t, ignoreCase = true) }
    }

    /** الفيديو الحالي في المكتبة المقابل للسطر (بالـ key، وبعدين بالـ uri) — null = غير موجود (اتنقل/اتمسح/اتغيّر اسمه) */
    fun find(e: WatchEntry, items: List<VideoItem>): VideoItem? =
        items.firstOrNull { it.key == e.key } ?: items.firstOrNull { it.uri == e.uri }

    // ===== ترميز الملف المحلي =====
    private const val HEADER = "VTL-HIST-1"
    fun encode(list: List<WatchEntry>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (e in list) sb.append(LibraryLogic.esc(e.key)).append('\t').append(LibraryLogic.esc(e.uri)).append('\t')
            .append(LibraryLogic.esc(e.name)).append('\t').append(LibraryLogic.esc(e.folderKey)).append('\t')
            .append(e.posMs).append('\t').append(e.durMs).append('\t').append(e.lastAt).append('\t')
            .append(if (e.completed) 1 else 0).append('\n')
        return sb.toString()
    }
    fun decode(text: String): List<WatchEntry> {
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0] != HEADER) return emptyList()
        val out = ArrayList<WatchEntry>()
        for (ln in lines.drop(1)) {
            if (ln.isEmpty()) continue
            val p = ln.split('\t'); if (p.size != 8) continue
            try {
                out.add(WatchEntry(LibraryLogic.unesc(p[0]), LibraryLogic.unesc(p[1]), LibraryLogic.unesc(p[2]), LibraryLogic.unesc(p[3]),
                    p[4].toLong(), p[5].toLong(), p[6].toLong(), p[7] == "1"))
            } catch (_: NumberFormatException) { }
        }
        return out
    }
}

/** قايمة التشغيل الحالية (مجلد أو نتيجة). التالي/السابق هنا فقط، وبدون لفّ دائري. */
class PlayQueue(val items: List<VideoItem>, start: Int) {
    var index: Int = start.coerceIn(0, maxOf(0, items.size - 1)); private set
    val current: VideoItem? get() = items.getOrNull(index)
    fun hasNext() = index < items.size - 1
    fun hasPrev() = index > 0
    fun next(): VideoItem? { if (!hasNext()) return null; index++; return current }
    fun prev(): VideoItem? { if (!hasPrev()) return null; index--; return current }
}
