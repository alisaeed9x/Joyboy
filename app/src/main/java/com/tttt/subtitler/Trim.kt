package com.tttt.subtitler


/** كشف الفجوات: مناطق اتترجمت (done) بس مفيهاش جمل لمدة طويلة */
object Gaps {
    const val MIN_SEC = 5.0
    const val LOOKAHEAD = 8.0
    const val COOLDOWN_MS = 3000L
    const val MAX_PARALLEL = 3
    const val MAX_TOTAL = 120

    /** كل فجوة [start,end] أطول من minSec جوه المناطق اللي اتترجمت */
    fun find(subs: List<Sub>, done: List<DoubleArray>, minSec: Double = MIN_SEC): List<DoubleArray> {
        val sorted = subs.sortedBy { it.start }
        val out = ArrayList<DoubleArray>()
        for (r in done) {
            var cursor = r[0]
            for (s in sorted) {
                if (s.end <= r[0] || s.start >= r[1]) continue
                if (s.start - cursor >= minSec) out.add(doubleArrayOf(cursor, s.start))
                if (s.end > cursor) cursor = s.end
            }
            if (r[1] - cursor >= minSec) out.add(doubleArrayOf(cursor, r[1]))
        }
        return out
    }

    /** يقسّم مجال لأجزاء مش أطول من maxSec */
    fun parts(a: Double, b: Double, maxSec: Double): List<DoubleArray> {
        val l = ArrayList<DoubleArray>(); var t = a
        while (t < b - 0.5) { l.add(doubleArrayOf(t, minOf(t + maxSec, b))); t += maxSec }
        return l
    }
}
