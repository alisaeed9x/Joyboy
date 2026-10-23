package com.vanilla.tvlauncher

/** منطق المشغّل النقي (بدون Android/media3) عشان يتاختبر على JVM — مأخوذ في الأساس من PlayerLogic بتاع Subtitler v475 ومتكيّف للتلفزيون. */
object PlayerLogic {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    val fitNames = listOf("احتواء", "ملء (قص)", "تمديد")
    /** مؤقت النوم: 0 معطّل · دقايق · -1 = لحد آخر الفيديو */
    val sleepOptions = listOf(0, 15, 30, 60, -1)
    const val SUB_MIN = 50; const val SUB_MAX = 250; const val SUB_STEP = 10

    fun speedLabel(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"
    fun sleepLabel(m: Int): String = when (m) { 0 -> "معطّل"; -1 -> "آخر الفيديو"; else -> "$m دقيقة" }

    /** تدوير في قايمة بالاتجاه (+1/-1) مع لفّ. القيمة مش موجودة = أول عنصر. */
    fun <T> cycle(list: List<T>, cur: T, dir: Int): T {
        if (list.isEmpty()) return cur
        val i = list.indexOf(cur).let { if (it < 0) 0 else it }
        return list[((i + dir) % list.size + list.size) % list.size]
    }

    /** مقاس الفيديو جوه الحاوية: 0 = احتواء، 1 = ملء بالقص، 2 = تمديد */
    fun fitSize(boxW: Int, boxH: Int, vw: Int, vh: Int, mode: Int): Pair<Int, Int> {
        if (vw <= 0 || vh <= 0 || boxW <= 0 || boxH <= 0) return boxW to boxH
        if (mode == 2) return boxW to boxH
        val sc = if (mode == 1) maxOf(boxW.toFloat() / vw, boxH.toFloat() / vh) else minOf(boxW.toFloat() / vw, boxH.toFloat() / vh)
        return Math.round(vw * sc) to Math.round(vh * sc)
    }

    /** نط بالأرقام (زي Subtitler): رقمين أو أقل = ثواني، أكتر = (اللي قبل آخر رقمين) دقايق + آخر رقمين ثواني */
    fun digitsToSec(buf: String): Int {
        if (buf.isEmpty() || buf.any { !it.isDigit() }) return 0
        return if (buf.length <= 2) buf.toInt() else buf.dropLast(2).toInt() * 60 + buf.takeLast(2).toInt()
    }

    /** خطوة التقديم/التأخير: 10ث، ولو الضغط متتابع (5+ ضغطات) 30ث */
    fun seekStep(streak: Int): Long = seekStep(streak, 10)
    /** P15: الخطوة الأساسية قابلة للتغيير من اللوحة (SEEK_BASES) — ضغط متتابع (5+) = ×3 */
    val SEEK_BASES = listOf(5, 10, 20, 30)
    fun seekStep(streak: Int, baseSec: Int): Long = (if (streak >= 5) baseSec * 3 else baseSec) * 1000L

    // ===== P15: إعدادات مشغّل أوسع (كلها بتتحفظ) =====
    val SUB_COLORS = listOf(0xFFFFFFFF.toInt() to "أبيض", 0xFFFFE066.toInt() to "أصفر", 0xFF7FE8F0.toInt() to "سماوي", 0xFF9BF59B.toInt() to "أخضر")
    val SUB_BGS = listOf(0x00000000 to "بدون خلفية", 0x77000000 to "شفافة", 0xCC000000.toInt() to "معتمة")
    val HIDE_SECS = listOf(3, 4, 8, 15)
    /** لغة مفضّلة للصوت/الترجمة: "" = تلقائي (من الملف) */
    val LANGS = listOf("" to "تلقائي", "ar" to "العربية", "en" to "الإنجليزية")
    fun langLabel(code: String): String = LANGS.firstOrNull { it.first == code }?.second ?: LANGS[0].second
    fun idxOf(list: List<Pair<Int, String>>, v: Int): Int = list.indexOfFirst { it.first == v }.let { if (it < 0) 0 else it }

    /** اللوحة بقت أطول من الشاشة → بنعرض نافذة صفوف حوالين الصف المختار. بيرجّع أول صف ظاهر */
    fun panelStart(total: Int, selected: Int, visible: Int): Int {
        if (total <= visible || visible <= 0) return 0
        return (selected - visible / 2).coerceIn(0, total - visible)
    }

    fun clampSub(v: Int): Int = v.coerceIn(SUB_MIN, SUB_MAX)

    // ===== حلقة A-B =====
    data class AB(val a: Long = -1L, val b: Long = -1L) { val active: Boolean get() = a >= 0 && b > a }
    /** ضغطة OK على الصف: (فاضي) → نقطة A → نقطة B → (فاضي). لو B قبل A بيبدأ من جديد بنقطة A. */
    fun abPress(ab: AB, pos: Long): AB = when {
        ab.a < 0 -> AB(pos, -1L)
        ab.b < 0 -> if (pos > ab.a + 500L) AB(ab.a, pos) else AB(pos, -1L)
        else -> AB()
    }
    /** لو الحلقة شغّالة ووصلنا B → ارجع لـ A (غير كده -1) */
    fun abJumpTarget(ab: AB, pos: Long): Long = if (ab.active && pos >= ab.b) ab.a else -1L
    fun abLabel(ab: AB): String = when {
        ab.a < 0 -> "معطّلة (OK لتحديد A)"
        ab.b < 0 -> "A=${LibraryLogic.formatDur(ab.a)} — OK لتحديد B"
        else -> "${LibraryLogic.formatDur(ab.a)} ⇄ ${LibraryLogic.formatDur(ab.b)} (OK للإلغاء)"
    }

    /** ثواني متبقية لبطاقة «التالي» (بتتقرّب لفوق، ومابتنزلش عن 0) */
    fun nextUpSecs(deadlineMs: Long, nowMs: Long): Int = ((deadlineMs - nowMs + 999L) / 1000L).toInt().coerceAtLeast(0)

    /** الترجمة المصوّرة (PGS/DVB/VobSub) مش بنقدر نرسمها في TextView → بتتشال من قايمة الترجمات */
    private val bitmapSubMimes = setOf("application/pgs", "application/dvbsubs", "application/vobsub")
    fun isBitmapSub(mime: String?): Boolean = mime != null && mime.lowercase() in bitmapSubMimes
}
