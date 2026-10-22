package com.vanilla.tvlauncher

/** منطق إعدادات صغير بدون Android (قابل للاختبار على JVM). */
object HomePrefs {
    fun hiddenSet(csv: String): Set<String> = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** يبدّل وجود id في القايمة (مخفي ↔ ظاهر) ويرجّع النص الجديد */
    fun toggle(csv: String, id: String): String {
        val s = LinkedHashSet(hiddenSet(csv).toList())
        if (!s.remove(id)) s.add(id)
        return s.joinToString(",")
    }

    /** القيمة اللي بعد cur في order (دايري). لو cur مش موجودة → أول قيمة. */
    fun cycle(order: List<Int>, cur: Int): Int {
        val i = order.indexOf(cur)
        return if (i < 0) order.first() else order[(i + 1) % order.size]
    }
}
