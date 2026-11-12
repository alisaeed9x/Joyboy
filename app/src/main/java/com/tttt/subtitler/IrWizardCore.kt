package com.tttt.subtitler

/**
 * (v855) منطق «اظبط الريموت بالتجربة» — صافي (من غير Android) عشان يتختبر على JVM.
 *
 * الفكرة: بدل ما المستخدم يدوّر على ماركة وموديل ورقم ريموت، البرنامج بيجرّب زرار على جهازه، والمستخدم يقول «اشتغل» أو «ما اشتغلش»:
 *  • ما اشتغلش ← كل الريموتات اللي كودها للزرار ده نفس الكود المجرَّب بتتشال (ومش هنجرّبه تاني).
 *  • اشتغل ← الريموتات اللي ليها نفس الكود بس هي اللي بتفضل، وبعدين بنجرّب زرار تاني (بيفرّق بين الباقيين) عشان نتأكد.
 *  • لما يتأكد زرارين مختلفين (NEED_CONFIRMS) الريموت بيتثبّت.
 * الإشارات بتتقارن كنص (معرّف الإشارة)، والكود الفعلي بيتبعت من الريموت «العيّنة» (sample) اللي في الـ[WizProbe].
 */
class WizCand(
    val brand: String,
    val name: String,
    val kind: String,
    /** مفتاح الزرار ← معرّف الإشارة (للأزرار اللي الويزارد بيجرّبها بس) */
    val sigs: Map<String, String>,
    /** عدد كل أزرار الريموت في القاعدة (للترتيب: الأكمل الأول) */
    val keyCount: Int = sigs.size
)

class WizProbe(val key: String, val sig: String, val sample: WizCand, val holders: Int)

class IrWizardCore(all: List<WizCand>, priorityBrands: List<String> = emptyList(), strong: Boolean = priorityBrands.size == 1) {
    companion object {
        /** ترتيب الأزرار المجرَّبة: الأعمّ والأوضح أثرًا الأول */
        val KEY_ORDER = listOf("POWER", "VOLUP", "MUTE", "VOLDN", "OK", "UP", "MENU", "HOME", "BACK", "DOWN", "LEFT", "RIGHT", "SRC", "CHUP", "CHDN")
        const val NEED_CONFIRMS = 2
        const val TIP_AFTER = 6
        /** ماركة اختارها المستخدم (أو اتعرفت من التلفزيون): بتغلب أي ترتيب تاني */
        private const val BRAND_STRONG = 1000

        /** معرّف إشارة ثابت من نوع الإشارة (بروتوكول+عنوان+أمر) أو RAW (تردد + بصمة النبضات) */
        fun sigId(proto: String, a: Long, b: Long, freq: Int, raw: IntArray?): String =
            if (raw != null) "RAW|$freq|${raw.contentHashCode()}" else "$proto|$a|$b"

        fun hint(key: String): String = when (key) {
            "POWER" -> "لو الجهاز شغّال المفروض يقفل، ولو مقفول يفتح."
            "VOLUP" -> "الصوت المفروض يعلى (الجهاز لازم يكون شغّال)."
            "VOLDN" -> "الصوت المفروض يوطّى (الجهاز لازم يكون شغّال)."
            "MUTE" -> "علامة الكتم المفروض تظهر أو الصوت يتقطع."
            "OK", "SEL", "ENTER" -> "الجهاز لازم يكون على قايمة أو شاشة فيها اختيار."
            "UP", "DOWN", "LEFT", "RIGHT" -> "الجهاز لازم يكون على قايمة، والاختيار المفروض يتحرك."
            "MENU", "HOME" -> "القايمة أو الشاشة الرئيسية المفروض تظهر."
            "BACK" -> "المفروض يرجع خطوة."
            "SRC" -> "قايمة المصادر (HDMI…) المفروض تظهر."
            "CHUP", "CHDN" -> "القناة المفروض تتغيّر."
            else -> "راقب الجهاز: هل حصل أي رد فعل؟"
        }
    }

    private class St(
        val alive: List<WizCand>,
        val confirmed: List<Pair<String, String>>,
        val rejected: Set<Pair<String, String>>,
        val skipped: Set<String>,
        val tries: Int,
        val failStreak: Int
    )

    /** ماركة ← أفضلية. [strong]: ماركة واحدة مؤكّدة (1000). غير كده قايمة مترتبة (الشهيرة الأول): 100 وتنزل 8 لكل مركز (بحد أدنى 10) */
    private val prio: Map<String, Int> = priorityBrands.withIndex().associate { it.value.lowercase() to (if (strong) BRAND_STRONG else maxOf(10, 100 - 8 * it.index)) }
    private val history = ArrayList<St>()
    private var st = St(all, emptyList(), emptySet(), emptySet(), 0, 0)
    private var cached: WizProbe? = null
    private var cachedFor: St? = null

    val alive: List<WizCand> get() = st.alive
    val aliveCount: Int get() = st.alive.size
    val tries: Int get() = st.tries
    val failStreak: Int get() = st.failStreak
    val confirmedKeys: List<String> get() = st.confirmed.map { it.first }.distinct()
    val needTip: Boolean get() = st.failStreak >= TIP_AFTER
    val canUndo: Boolean get() = history.isNotEmpty()

    /** فيه ريموت متبقّي؟ (لو لأ: مفيش ريموت في القاعدة بيطابق اللي اتجرّب) */
    val dead: Boolean get() = st.alive.isEmpty()

    /** اتأكد كفاية (زرارين مختلفين)، أو اتأكد واحد ومفيش زرار تاني نجرّبه */
    fun locked(): Boolean {
        if (st.alive.isEmpty() || st.confirmed.isEmpty()) return false
        return confirmedKeys.size >= NEED_CONFIRMS || probe() == null
    }

    private fun bonus(c: WizCand): Int = prio[c.brand.lowercase()] ?: 0

    /** الزرار الجاي المطلوب تجربته (null لو مفيش حاجة تتجرّب) */
    fun probe(): WizProbe? {
        if (cachedFor === st) return cached
        val r = computeProbe()
        cached = r; cachedFor = st
        return r
    }

    private fun groupsOf(key: String): Map<String, List<WizCand>> {
        val m = LinkedHashMap<String, ArrayList<WizCand>>()
        for (c in st.alive) { val s = c.sigs[key] ?: continue; if ((key to s) in st.rejected) continue; m.getOrPut(s) { ArrayList() }.add(c) }
        return m
    }

    private fun weight(g: List<WizCand>): Int = g.size + g.sumOf { bonus(it) }

    private fun computeProbe(): WizProbe? {
        if (st.alive.isEmpty()) return null
        val used = confirmedKeys.toSet()
        val keys = KEY_ORDER.filter { it !in used && it !in st.skipped }
        if (st.confirmed.isEmpty()) {
            // أول تجربة: أول زرار (بالترتيب) فيه كود لسه ما اتجرّبش، وأكتر كود مشترك/مفضّل
            for (k in keys) {
                val g = groupsOf(k)
                if (g.isEmpty()) continue
                val best = g.entries.maxByOrNull { weight(it.value) } ?: continue
                return WizProbe(k, best.key, pickSample(best.value), best.value.size)
            }
            return null
        }
        // بعد أول نجاح: زرار بيفرّق بين الباقيين (أكتر من كود مختلف) وإلا أي زرار للتأكيد
        var verify: WizProbe? = null
        for (k in keys) {
            val g = groupsOf(k)
            if (g.isEmpty()) continue
            val best = g.entries.maxByOrNull { weight(it.value) } ?: continue
            val p = WizProbe(k, best.key, pickSample(best.value), best.value.size)
            if (g.size >= 2) return p
            if (verify == null) verify = p
        }
        return verify
    }

    private fun pickSample(g: List<WizCand>): WizCand =
        g.sortedWith(compareByDescending<WizCand> { bonus(it) }.thenByDescending { it.keyCount }.thenBy { it.name }).first()

    private fun push() { history.add(st); cachedFor = null }

    /** إجابة المستخدم على الزرار الحالي */
    fun answer(worked: Boolean) {
        val p = probe() ?: return
        push()
        val k = p.key; val s = p.sig
        val same = st.alive.filter { it.sigs[k] == s }
        val other = st.alive.filter { it.sigs[k] != s }
        val rej = st.rejected + (k to s)
        st = if (worked) {
            St(same, st.confirmed + (k to s), st.rejected, st.skipped, st.tries + 1, 0)
        } else if (st.confirmed.isEmpty()) {
            St(other, st.confirmed, rej, st.skipped, st.tries + 1, st.failStreak + 1)
        } else if (other.isEmpty()) {
            // الجهاز ممكن مالوش الوظيفة دي أصلًا: متشيلش كل الباقيين — بس سيب الزرار ده
            St(st.alive, st.confirmed, rej, st.skipped + k, st.tries + 1, st.failStreak + 1)
        } else {
            St(other, st.confirmed, rej, st.skipped, st.tries + 1, st.failStreak + 1)
        }
    }

    /** «جرّب زرار تاني»: من غير ما نحكم على أي ريموت. false لو مفيش زرار تاني يتجرّب */
    fun skipKey(): Boolean {
        val p = probe() ?: return false
        val before = st
        push()
        st = St(st.alive, st.confirmed, st.rejected, st.skipped + p.key, st.tries, st.failStreak)
        if (probe() == null) { st = before; history.removeAt(history.size - 1); cachedFor = null; return false }
        return true
    }

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        st = history.removeAt(history.size - 1); cachedFor = null
        return true
    }

    /** المتبقّيين مترتبين: الماركة المفضّلة ← الأكمل أزرارًا ← الاسم */
    fun ranked(): List<WizCand> =
        st.alive.sortedWith(compareByDescending<WizCand> { bonus(it) }.thenByDescending { it.keyCount }.thenBy { it.brand.lowercase() }.thenBy { it.name })

    fun best(): WizCand? = ranked().firstOrNull()
}

/**
 * (v855) عدّاد فشل الواي فاي المتتالي: بعد [limit] مرات ورا بعض من غير نجاح بيرجّع true (اقترح ريموت IR) — بشرط ما نكونش سألنا في آخر [quietMs].
 * بعد ما بيوصل للحد بيصفّر نفسه فمفيش سؤال في كل فشل بعده.
 */
class FailStreak(private val limit: Int = 3, private val quietMs: Long = 24L * 3600_000L) {
    private var n = 0
    @Synchronized fun ok() { n = 0 }
    @Synchronized fun fail(now: Long, lastAsk: Long): Boolean {
        n++
        if (n < limit) return false
        n = 0
        return lastAsk == 0L || now - lastAsk >= quietMs
    }
}
