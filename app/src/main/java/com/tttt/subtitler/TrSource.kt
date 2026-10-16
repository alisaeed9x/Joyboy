package com.tttt.subtitler

/**
 * (v368) مصدر الترجمة: 🏠 المفاتيح الداخلية (جيميناي + مزوّديك عن طريق الراوتر الداخلي) ⇄ 🛰 OmniRoute.
 *  - mode(): اللي انت مختاره (Cfg tr_src = local | omni) — بيتبدّل بدوسة على سطر المحرك تحت كبسولة العدّاد في المشغّل.
 *  - report(): كل طلب بيسجّل نجح/فشل ومين رد (من Api.generate · الراوتر الداخلي · OmniRoute) — ده اللي بيحدد لون النقطة والسطر.
 * نقطة الحالة: 🔵 المفاتيح الداخلية شغّالة · 🟢 OmniRoute شغّال · 🔴 فشل (ومفيش حد تاني نجح) · مخفية لو مفيش استخدام آخر دقيقتين.
 * من غير أي اعتماد على أندرويد (بيتجرّب في اختبارات JVM).
 */
object TrSource {
    const val LOCAL = "local"
    const val OMNI = "omni"
    enum class Dot { HIDDEN, BLUE, GREEN, RED }
    class Rec(val kind: String, val ok: Boolean, val label: String, val at: Long)

    @Volatile private var lastLocal: Rec? = null
    @Volatile private var lastOmni: Rec? = null
    private const val FRESH = 120_000L
    private const val OTHER_OK = 30_000L

    fun mode(): String = if (Cfg.str("tr_src", LOCAL) == OMNI) OMNI else LOCAL
    fun setMode(m: String) { Cfg.put("tr_src", if (m == OMNI) OMNI else LOCAL) }
    fun modeName(m: String = mode()): String = if (m == OMNI) "🛰 OmniRoute" else "🏠 المفاتيح الداخلية"

    fun report(kind: String, ok: Boolean, label: String) {
        val r = Rec(kind, ok, label, System.currentTimeMillis())
        if (kind == OMNI) lastOmni = r else lastLocal = r
    }

    private fun fresh(r: Rec?, now: Long): Rec? = if (r != null && now - r.at < FRESH) r else null

    fun state(now: Long = System.currentTimeMillis()): Dot {
        val recs = listOfNotNull(fresh(lastLocal, now), fresh(lastOmni, now))
        val newest = recs.maxByOrNull { it.at } ?: return Dot.HIDDEN
        if (newest.ok) return if (newest.kind == OMNI) Dot.GREEN else Dot.BLUE
        // آخر محاولة فشلت: لو التاني نجح من أقل من 30 ث يبقى هو اللي شغّال، غير كده أحمر
        val other = recs.filter { it.ok && now - it.at < OTHER_OK }.maxByOrNull { it.at }
        return if (other != null) (if (other.kind == OMNI) Dot.GREEN else Dot.BLUE) else Dot.RED
    }

    /** سطر المحرك: مين شغّال دلوقتي + (للـ OmniRoute) المزوّد والموديل اللي رد فعلًا */
    fun line(now: Long = System.currentTimeMillis()): String {
        val newest = listOfNotNull(fresh(lastLocal, now), fresh(lastOmni, now)).maxByOrNull { it.at }
        val sel = mode()
        if (newest == null) return modeName(sel) + " · مستني"
        val base = if (newest.ok) modeName(newest.kind) + " · " + newest.label else "⚠ " + modeName(newest.kind) + " فشل · " + newest.label
        return if (newest.kind != sel) "$base  (المختار: ${modeName(sel)})" else base
    }
}
