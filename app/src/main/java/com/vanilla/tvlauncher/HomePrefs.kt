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

    /** ترتيب الصفوف: المحفوظ (بدون المجهول/المكرر) ثم أي صف جديد في الآخر */
    fun order(csv: String, all: List<String>): List<String> {
        val saved = csv.split(",").map { it.trim() }.filter { it in all }.distinct()
        return saved + all.filter { it !in saved }
    }

    /** نقل id خطوة (dir = -1 لفوق، +1 لتحت)؛ على الحافة أو id مجهول = بدون تغيير */
    fun move(order: List<String>, id: String, dir: Int): List<String> {
        val i = order.indexOf(id); val j = i + dir
        if (i < 0 || j < 0 || j >= order.size) return order
        val m = order.toMutableList(); val t = m[i]; m[i] = m[j]; m[j] = t
        return m
    }

    /** القيمة اللي بعد cur في order (دايري). لو cur مش موجودة → أول قيمة. */
    fun cycle(order: List<Int>, cur: Int): Int {
        val i = order.indexOf(cur)
        return if (i < 0) order.first() else order[(i + 1) % order.size]
    }
}
