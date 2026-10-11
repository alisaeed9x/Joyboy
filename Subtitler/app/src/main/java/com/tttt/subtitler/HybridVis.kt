package com.tttt.subtitler

import java.util.TreeMap

/**
 * (v206) خوارزمية الوضع البصري «الهجين» منقولة من نسخة الـ HTML (hybridMode):
 * فريم ← بصمة 24×24 (تخطّي لو مفيش تغيير) ← درجة نص 48×48 (تخطّي لو مفيش نص) ← طابور 30 فريم ←
 * مجموعات من 6 فريمات في صورة شبكة واحدة ← Gemini ← خريطة زمنية بمفتاح ربع ثانية (دمج + حد أدنى للعرض + فلتر الشعارات + إزالة التكرار) ← لوج وأحداث ظهور/اختفاء.
 * الملف ده منطق صرف (من غير Android) عشان يتختبر على الـ JVM.
 */
class HBox(
    val x: Float, val y: Float, val w: Float, val h: Float, val angle: Float,
    val original: String, val translated: String, val lang: String,
    val bgHex: String, val fgHex: String, val opacity: Int, val hasBox: Boolean,
    /** لون خلفية مسحوب من الفريم نفسه حوالين النص (بيُستخدم لو Gemini ماذكرش لون) */
    val sampledBg: Int? = null
) {
    fun withBg(c: Int?): HBox = HBox(x, y, w, h, angle, original, translated, lang, bgHex, fgHex, opacity, hasBox, c)
}

/** مقطع زمني (مفتاحه startQ بالربع ثانية) فيه نصوصه */
class HSeg(val sq: Int, var eq: Int, var fw: Int, var fh: Int) {
    var boxes: MutableList<HBox> = ArrayList()
    var ver: Int = 0
}

/** صورة للقراءة من الواجهة (نسخة من المقطع النشط) */
class HView(val sq: Int, val eq: Int, val ver: Int, val boxes: List<HBox>)

/** فريم اتبعت للتحليل: مفتاحه + آخر ربع ثانية اتأكدنا إن الشاشة فيها لسه زي ما هي (holdQ) + أبعاده */
class HFrame(val qk: Int, val holdQ: Int, val w: Int, val h: Int)

/** نص رجع من Gemini متحط على فريم معين */
class HPlace(val qk: Int, val box: HBox)

class HEvent(
    val type: String, val sec: Double, val endSec: Double, val key: String,
    val original: String, val translated: String, val detail: String, val at: Long
)

object HybridVis {
    const val SCAN_EVERY_MS = 500L       // فريم كل نص ثانية (الالتقاط الحي)
    const val BATCH_SIZE = 30            // الطابور لحد 30 فريم قبل الإرسال
    const val SUBBATCH_SIZE = 6          // كل باتش بيتقسم لمجموعات 6 فريمات (شبكة واحدة لكل مجموعة)
    const val DIFF_THRESHOLD = 0.008     // 0.8% فرق بين فريمين = «فيه تغيير»
    const val TEXT_THRESHOLD = 0.008     // أقل درجة نص = «مفيش نص»
    const val DIFF_GRID = 24
    const val TEXT_GRID = 48
    const val LEAD_Q = 2                 // النص يبدأ يظهر قبل توقيته بنص ثانية
    const val LAG_Q = 2                  // ويفضل بعد نهايته بنص ثانية
    const val MAX_SEGMENT_Q = 32         // أقصى مدة لنص واحد مستمر: 8 ثواني
    const val RENDER_SANITY_Q = 40       // مقطع أطول من 10ث = شاذ، مايتعرضش
    const val MERGE_SIM = 0.72           // تشابه نصين عشان يتعبروا نفس الجملة
    const val MIN_DISPLAY_Q = 6          // أقل مدة عرض: 1.5 ثانية
    const val EVENT_LOG_MAX = 2000

    fun lum(argb: Int): Float =
        ((argb shr 16) and 255) * 0.299f + ((argb shr 8) and 255) * 0.587f + (argb and 255) * 0.114f

    fun lumGrid(px: IntArray): FloatArray {
        val o = FloatArray(px.size)
        for (i in px.indices) o[i] = lum(px[i])
        return o
    }

    /** متوسط الفرق المطلق بين بصمتين (0..1) */
    fun sigDiff(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.size != b.size) return 1.0
        var s = 0.0
        for (i in a.indices) s += Math.abs(a[i] - b[i]).toDouble()
        return s / (a.size * 255.0)
    }

    /** درجة وجود نص: أعلى قيمة بين كثافة الحواف وتباين البلوكات المحلي (للكانجي/الكانا) */
    fun textScore(lum: FloatArray, n: Int): Double {
        val edgeTh = 25f
        var edges = 0
        val total = (n * (n - 1) + n * (n - 1)).toDouble()
        for (y in 0 until n) for (x in 0 until n - 1) if (Math.abs(lum[y * n + x] - lum[y * n + x + 1]) > edgeTh) edges++
        for (x in 0 until n) for (y in 0 until n - 1) if (Math.abs(lum[y * n + x] - lum[(y + 1) * n + x]) > edgeTh) edges++
        val edgeScore = edges / total
        var varScore = 0
        var blocks = 0
        var by = 0
        while (by < n - 4) {
            var bx = 0
            while (bx < n - 4) {
                var sum = 0.0
                var sum2 = 0.0
                for (dy in 0 until 4) for (dx in 0 until 4) {
                    val v = lum[(by + dy) * n + (bx + dx)].toDouble()
                    sum += v; sum2 += v * v
                }
                val mean = sum / 16.0
                val variance = sum2 / 16.0 - mean * mean
                if (variance > 150.0) varScore++
                blocks++
                bx += 4
            }
            by += 4
        }
        val lv = varScore.toDouble() / (if (blocks == 0) 1 else blocks)
        return Math.max(edgeScore, lv * 0.15)
    }

    fun normCmp(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** تشابه تقريبي (Sørensen-Dice على أزواج الحروف) — بيسامح في اختلاف الـ OCR بين باتش وباتش */
    fun sim(a: String, b: String): Double {
        val na = normCmp(a)
        val nb = normCmp(b)
        if (na.isEmpty() && nb.isEmpty()) return 1.0
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (na.length < 2 || nb.length < 2) return 0.0
        val counts = HashMap<String, Int>()
        for (i in 0 until na.length - 1) {
            val g = na.substring(i, i + 2)
            counts[g] = (counts[g] ?: 0) + 1
        }
        var matches = 0
        for (i in 0 until nb.length - 1) {
            val g = nb.substring(i, i + 2)
            val c = counts[g] ?: 0
            if (c > 0) { matches++; counts[g] = c - 1 }
        }
        return 2.0 * matches / ((na.length - 1) + (nb.length - 1))
    }

    /** مفتاح ثابت لكل «نص» (المحتوى + المكان) لتتبّع ظهوره واختفاءه ورجوعه */
    fun textKey(b: HBox): String =
        b.original.trim() + "__" + Math.round(b.x * 100f) + "_" + Math.round(b.y * 100f)

    fun f2(d: Double): String = String.format(java.util.Locale.US, "%.2f", d)
}

/** الخريطة الزمنية للترجمات البصرية: مفتاحها startQ (ربع ثانية). كل العمليات synchronized لأن المسح والإرسال والعرض في خيوط مختلفة. */
class HMap {
    private val map = TreeMap<Int, HSeg>()
    private val events = ArrayList<HEvent>()
    private class TS(var firstSeenQ: Int, var lastSeenQ: Int, var lastEndQ: Int, var visible: Boolean, var appearances: Int)
    private val state = HashMap<String, TS>()

    @Synchronized fun clear() { map.clear(); events.clear(); state.clear() }
    @Synchronized fun isEmpty(): Boolean = map.isEmpty()
    @Synchronized fun boxCount(): Int {
        var n = 0
        for (s in map.values) n += s.boxes.size
        return n
    }

    /** مسح اللي نهايته عند الثانية دي أو بعدها (إعادة تحليل من مكان معيّن) */
    @Synchronized fun removeFrom(sec: Double) {
        val it = map.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.value.eq / 4.0 >= sec) it.remove() }
        state.clear()
    }

    /** نسخة للحفظ على الديسك */
    @Synchronized fun copySegs(): List<HSeg> {
        val out = ArrayList<HSeg>()
        for (s in map.values) {
            val c = HSeg(s.sq, s.eq, s.fw, s.fh)
            c.boxes = ArrayList(s.boxes)
            out.add(c)
        }
        return out
    }

    @Synchronized fun load(list: List<HSeg>) {
        map.clear(); events.clear(); state.clear()
        for (s in list) map[s.sq] = s
    }

    /**
     * يخزّن نتيجة مجموعة فريمات:
     * 1) مدخل فاضي لكل فريم اتبعت  2) النصوص على فريماتها  3) مدّ نهاية المقطع لآخر لحظة الشاشة فيها لسه زي ما هي (holdQ)
     * 4) حد أدنى للعرض (1.5ث) من غير ما يغطي نص جديد جاي بعده  5) دمج المتتاليات المتطابقة  6) فلتر الشعارات المتكررة
     */
    @Synchronized fun apply(frames: List<HFrame>, places: List<HPlace>, noiseOut: MutableList<String>) {
        for (f in frames) if (!map.containsKey(f.qk)) map[f.qk] = HSeg(f.qk, f.qk, f.w, f.h)
        for (p in places) {
            val s = map[p.qk] ?: continue
            var dup = false
            for (e in s.boxes) if (e.original == p.box.original && e.x == p.box.x && e.y == p.box.y) { dup = true; break }
            if (!dup) { s.boxes.add(p.box); s.ver++ }
        }
        for (f in frames) {
            val s = map[f.qk] ?: continue
            if (s.boxes.isNotEmpty() && f.holdQ > s.eq) {
                s.eq = Math.min(f.holdQ, s.sq + HybridVis.MAX_SEGMENT_Q)
                s.ver++
            }
        }
        minDisplay()
        mergeSegments()
        noiseFilter(noiseOut)
    }

    private fun minDisplay() {
        val filled = ArrayList<HSeg>()
        for (s in map.values) if (s.boxes.isNotEmpty()) filled.add(s)
        for (i in filled.indices) {
            val s = filled[i]
            val desired = s.sq + HybridVis.MIN_DISPLAY_Q
            val next = if (i + 1 < filled.size) filled[i + 1].sq else Int.MAX_VALUE
            val capped = Math.max(s.sq, Math.min(desired, next - 1))
            if (capped > s.eq) { s.eq = capped; s.ver++ }
        }
    }

    /** مقطعين متتاليين (فجوة ≤ ثانية، مجموعهم ≤ 8ث) بنفس النصوص تقريبًا وفي نفس المكان = ظهور واحد */
    private fun mergeSegments() {
        val sorted = ArrayList<HSeg>(map.values)
        var i = 0
        while (i < sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (b.sq - a.eq > 4) { i++; continue }
            if (b.eq - a.sq > HybridVis.MAX_SEGMENT_Q) { i++; continue }
            if (a.boxes.isEmpty() || a.boxes.size != b.boxes.size) { i++; continue }
            val used = HashSet<Int>()
            var all = true
            for (ba in a.boxes) {
                var bestJ = -1
                var bestScore = -1.0
                for (j in b.boxes.indices) {
                    if (used.contains(j)) continue
                    val bb = b.boxes[j]
                    val s = HybridVis.sim(ba.original, bb.original)
                    val dx = Math.abs(ba.x - bb.x)
                    val dy = Math.abs(ba.y - bb.y)
                    if (s >= HybridVis.MERGE_SIM && dx <= 0.12f && dy <= 0.12f && s > bestScore) { bestScore = s; bestJ = j }
                }
                if (bestJ == -1) { all = false; break }
                used.add(bestJ)
            }
            if (!all) { i++; continue }
            a.eq = b.eq
            a.ver++
            map.remove(b.sq)
            sorted.removeAt(i + 1)
        }
    }

    /** نص بيظهر في 3 مقاطع منفصلة أو أكتر على مدى 10ث+ = شعار/واترمارك: بيتشال */
    private fun noiseFilter(out: MutableList<String>) {
        val appear = HashMap<String, MutableList<Int>>()
        for (s in map.values) for (b in s.boxes) {
            val o = b.original.trim().lowercase()
            if (o.length < 2) continue
            appear.getOrPut(o) { ArrayList() }.add(s.sq)
        }
        val noise = HashSet<String>()
        for ((o, l) in appear) {
            val u = l.distinct().sorted()
            if (u.size >= 3 && u[u.size - 1] - u[0] >= 40) {
                noise.add(o)
                out.add("🚫 Noise filter: \"" + o.take(30) + "\" ظهر " + u.size + "x — محذوف")
            }
        }
        if (noise.isEmpty()) return
        for (s in map.values) {
            val before = s.boxes.size
            val kept = ArrayList<HBox>()
            for (b in s.boxes) if (!noise.contains(b.original.trim().lowercase())) kept.add(b)
            if (kept.size != before) { s.boxes = kept; s.ver++ }
        }
    }

    /**
     * بعد ما كل مجموعات الباتش تخلص: نفس النص (تشابه + قرب مكان) اتكشف في مجموعتين متجاورتين = تكرار على الحدود.
     * بنشيله من الأحدث وبنمدّد الأقدم عشان يغطي المدى كله.
     */
    @Synchronized fun dedupe() {
        val keys = ArrayList<Int>(map.keys)
        val win = HybridVis.LEAD_Q + HybridVis.LAG_Q + 1
        for (i in keys.indices) {
            val a = map[keys[i]] ?: continue
            if (a.boxes.isEmpty()) continue
            val aEnd = a.eq
            for (j in (i + 1) until keys.size) {
                val b = map[keys[j]] ?: continue
                if (b.boxes.isEmpty()) continue
                if (b.sq - aEnd > win) break
                val bEnd = b.eq
                var removed = false
                val keep = ArrayList<HBox>()
                for (bb in b.boxes) {
                    var dup = false
                    for (ba in a.boxes) {
                        if (HybridVis.sim(ba.original, bb.original) >= HybridVis.MERGE_SIM &&
                            Math.abs(ba.x - bb.x) <= 0.18f && Math.abs(ba.y - bb.y) <= 0.18f) { dup = true; break }
                    }
                    if (dup) removed = true else keep.add(bb)
                }
                if (removed) {
                    b.boxes = keep; b.ver++
                    a.eq = Math.max(aEnd, bEnd); a.ver++
                }
            }
        }
    }

    /** المقطع اللي يتعرض عند الربع ده (مع سبق/تأخير نص ثانية): الأضيق مدى لو فيه أكتر من واحد */
    @Synchronized fun activeAt(q: Int): HView? {
        var best: HSeg? = null
        var bestRange = Int.MAX_VALUE
        for (s in map.values) {
            if (s.eq - s.sq > HybridVis.RENDER_SANITY_Q) continue
            if (s.boxes.isEmpty()) continue
            if (q >= s.sq - HybridVis.LEAD_Q && q <= s.eq + HybridVis.LAG_Q) {
                val r = s.eq - s.sq
                if (r < bestRange) { bestRange = r; best = s }
            }
        }
        val b = best ?: return null
        return HView(b.sq, b.eq, b.ver, ArrayList(b.boxes))
    }

    // ───────────── السجل: أحداث الظهور/التمديد/الرجوع/الاختفاء + جدول الترجمات ─────────────

    private fun push(ev: HEvent, label: String, emit: (String) -> Unit) {
        events.add(ev)
        if (events.size > HybridVis.EVENT_LOG_MAX) events.removeAt(0)
        emit("🧬 " + label + " «" + ev.original.take(40) + "» — " + ev.detail)
    }

    /** بيقارن حالة كل نص بالخريطة الحالية ويسجّل الأحداث الجديدة بس (بيتنادى بعد كل باتش) */
    @Synchronized fun rebuildEvents(nowQ: Int, emit: (String) -> Unit) {
        val seen = HashSet<String>()
        for (s in map.values) {
            if (s.boxes.isEmpty()) continue
            val startQ = s.sq
            val endQ = s.eq
            val sec = startQ / 4.0
            val endSec = endQ / 4.0
            for (nb in s.boxes) {
                val tk = HybridVis.textKey(nb)
                if (!seen.add(tk + "@" + startQ)) continue
                val prev = state[tk]
                if (prev == null) {
                    push(HEvent("appear", sec, endSec, tk, nb.original, nb.translated,
                        "ظهر لأول مرة عند " + HybridVis.f2(sec) + "s — متوقع يفضل لحد " + HybridVis.f2(endSec) + "s", System.currentTimeMillis()), "ظهر", emit)
                    state[tk] = TS(startQ, startQ, endQ, true, 1)
                } else {
                    val gapQ = startQ - prev.lastEndQ
                    if (gapQ > 4) {
                        prev.appearances++
                        push(HEvent("reappear", sec, endSec, tk, nb.original, nb.translated,
                            "رجع تاني عند " + HybridVis.f2(sec) + "s بعد ما اختفى عند " + HybridVis.f2(prev.lastEndQ / 4.0) + "s (فجوة " +
                                HybridVis.f2(gapQ / 4.0) + "s) — ده ظهوره رقم " + prev.appearances, System.currentTimeMillis()), "رجع", emit)
                    } else if (endQ > prev.lastEndQ) {
                        push(HEvent("extend", sec, endSec, tk, nb.original, nb.translated,
                            "امتد توقيته من " + HybridVis.f2(prev.lastEndQ / 4.0) + "s إلى " + HybridVis.f2(endSec) + "s", System.currentTimeMillis()), "امتد", emit)
                    }
                    prev.lastSeenQ = startQ
                    prev.lastEndQ = Math.max(prev.lastEndQ, endQ)
                    prev.visible = true
                }
            }
        }
        for ((tk, st) in state) {
            if (st.visible && nowQ - st.lastEndQ > 8) {
                st.visible = false
                push(HEvent("disappear", st.lastEndQ / 4.0, st.lastEndQ / 4.0, tk, tk.split("__")[0], "",
                    "اختفى نهائيًا (لحد الآن) عند " + HybridVis.f2(st.lastEndQ / 4.0) + "s", System.currentTimeMillis()), "اختفى", emit)
            }
        }
    }

    @Synchronized fun eventsText(): String {
        if (events.isEmpty()) return "لا توجد أحداث بعد"
        val sb = StringBuilder()
        for (e in events) {
            val lbl = when (e.type) { "appear" -> "ظهر"; "reappear" -> "رجع"; "extend" -> "امتد"; else -> "اختفى" }
            sb.append(HybridVis.f2(e.sec)).append("s  ").append(lbl).append("  «").append(e.original.take(40)).append("» — ").append(e.detail).append('\n')
        }
        return sb.toString().trimEnd()
    }

    @Synchronized fun tableText(): String {
        val sb = StringBuilder()
        for (s in map.values) {
            if (s.boxes.isEmpty()) continue
            for (b in s.boxes) {
                sb.append(HybridVis.f2(s.sq / 4.0)).append("s → ").append(HybridVis.f2(s.eq / 4.0)).append("s   ")
                    .append(b.original.replace('\n', ' ')).append("  ←  ").append(b.translated.replace('\n', ' ')).append('\n')
            }
        }
        return if (sb.isEmpty()) "لا توجد ترجمات بصرية بعد" else sb.toString().trimEnd()
    }
}
