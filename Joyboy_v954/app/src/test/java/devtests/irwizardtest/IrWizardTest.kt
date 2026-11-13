package devtests.irwizardtest

import com.tttt.subtitler.*
import java.io.File

var fails = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) println("PASS $name") else { fails++; println("FAIL $name $extra") }
}

private fun cand(brand: String, name: String, vararg sigs: Pair<String, String>, keyCount: Int = sigs.size) =
    WizCand(brand, name, "tv", mapOf(*sigs), keyCount)

fun main() {
    // ---------- معرّف الإشارة
    check("sigId: نفس الكود = نفس المعرّف، RAW بالبصمة", IrWizardCore.sigId("N", 1, 2, 0, null) == IrWizardCore.sigId("N", 1, 2, 0, null) &&
        IrWizardCore.sigId("RAW", 0, 0, 38000, intArrayOf(1, 2, 3)) != IrWizardCore.sigId("RAW", 0, 0, 38000, intArrayOf(1, 2, 4)))

    // ---------- «ما اشتغلش» بيشيل كل اللي ليهم نفس الكود بس
    val a = cand("A", "a1", "POWER" to "N|1|1", "VOLUP" to "N|1|2")
    val b = cand("A", "a2", "POWER" to "N|1|1", "VOLUP" to "N|1|9")
    val c = cand("B", "b1", "POWER" to "N|2|1", "VOLUP" to "N|2|2")
    run {
        val w = IrWizardCore(listOf(a, b, c))
        val p = w.probe()!!
        check("أول تجربة = باور بأكتر كود مشترك", p.key == "POWER" && p.sig == "N|1|1" && p.holders == 2)
        w.answer(false)
        check("ما اشتغلش: اتشال المشتركين بس", w.aliveCount == 1 && w.alive[0].name == "b1" && w.tries == 1 && w.failStreak == 1)
        check("الكود المجرَّب ما يتجرّبش تاني", w.probe()!!.sig == "N|2|1")
        w.answer(false)
        check("كله اتشال ← dead", w.dead && w.probe() == null && !w.locked())
        check("undo بيرجّع", w.undo() && w.aliveCount == 1 && w.probe()!!.sig == "N|2|1")
    }

    // ---------- «اشتغل» ← نأكّد بزرار تاني ← تثبيت بعد زرارين
    run {
        val w = IrWizardCore(listOf(a, b, c))
        w.answer(true)
        check("اشتغل: فضل المطابقين بس", w.aliveCount == 2 && w.confirmedKeys == listOf("POWER"))
        val p2 = w.probe()!!
        check("التأكيد بزرار مختلف بيفرّق بين الباقيين", p2.key == "VOLUP" && !w.locked())
        w.answer(true)
        check("زرارين اتأكدوا ← مثبّت", w.locked() && w.aliveCount == 1 && w.best()!!.name == (if (p2.sig == "N|1|2") "a1" else "a2"))
    }

    // ---------- فشل زرار التأكيد ما يمسحش كل الباقيين (الجهاز ممكن مالوش الوظيفة)
    run {
        val only = cand("A", "x", "POWER" to "N|9|9", "VOLUP" to "N|9|8")
        val w = IrWizardCore(listOf(only))
        w.answer(true)
        check("زرار تاني للتأكيد", w.probe()!!.key == "VOLUP")
        w.answer(false)
        check("فشل التأكيد مع ريموت واحد: بيفضل وبيتساب الزرار", w.aliveCount == 1 && w.alive[0].name == "x")
        check("ومفيش زرار تاني ← مثبّت بنجاح واحد", w.probe() == null && w.locked())
    }

    // ---------- تخطّي الزرار
    run {
        val w = IrWizardCore(listOf(a, b, c))
        check("skipKey بيغيّر الزرار من غير ما يشيل ريموتات", w.skipKey() && w.probe()!!.key == "VOLUP" && w.aliveCount == 3)
        val z = IrWizardCore(listOf(cand("A", "p", "POWER" to "N|1|1")))
        check("skipKey لو مفيش زرار تاني ← false ومفيش تغيير", !z.skipKey() && z.probe()!!.key == "POWER")
    }

    // ---------- أفضلية الماركة
    run {
        val big = cand("Big", "b", "POWER" to "N|5|5"); val big2 = cand("Big", "b2", "POWER" to "N|5|5"); val big3 = cand("Big", "b3", "POWER" to "N|5|5")
        val mine = cand("Mine", "m", "POWER" to "N|7|7")
        check("من غير أفضلية: الكود الأكتر مشاركة الأول", IrWizardCore(listOf(big, big2, big3, mine)).probe()!!.sig == "N|5|5")
        check("ماركة مختارة (strong) بتتجرّب الأول", IrWizardCore(listOf(big, big2, big3, mine), listOf("Mine")).probe()!!.sig == "N|7|7")
        check("قايمة مترتبة: الأقدم أعلى", IrWizardCore(listOf(big, mine), listOf("Mine", "Big")).probe()!!.sig == "N|7|7")
    }

    // ---------- ترتيب النتيجة: الأكمل أزرارًا
    run {
        val few = cand("A", "few", "POWER" to "N|1|1", keyCount = 5)
        val many = cand("A", "many", "POWER" to "N|1|1", keyCount = 40)
        val w = IrWizardCore(listOf(few, many))
        w.answer(true)
        check("best = الأكمل أزرارًا", w.best()!!.name == "many" && w.ranked().map { it.name } == listOf("many", "few"))
    }

    // ---------- تنبيه لو فشل كتير
    run {
        val list = (1..8).map { cand("A", "r$it", "POWER" to "N|$it|1") }
        val w = IrWizardCore(list)
        repeat(IrWizardCore.TIP_AFTER) { w.answer(false) }
        check("needTip بعد كام فشل متتالي", w.needTip)
    }

    // ---------- عدّاد فشل الواي فاي
    run {
        val f = FailStreak(3, 1000L)
        check("فشلين: لسه", !f.fail(0, 0) && !f.fail(1, 0))
        check("التالت: اسأل", f.fail(2, 0))
        check("بعده فشلين: لسه", !f.fail(3, 2) && !f.fail(4, 2))
        check("التالت تاني خلال فترة الصمت: ما تسألش", !f.fail(5, 2))
        f.ok(); f.fail(2000, 5); f.fail(2001, 5)
        check("بعد فترة الصمت: اسأل تاني", f.fail(2002, 5))
        f.fail(3000, 0); f.ok()
        check("نجاح بيصفّر العدّاد", !f.fail(3001, 0) && !f.fail(3002, 0))
    }

    // ---------- على القاعدة الحقيقية: الريموت الصح بيفضل دايمًا + عدد المحاولات معقول لما الماركة معروفة
    val dir = File(System.getenv("SUBTITLER_ASSETS") ?: "src/main/assets")
    val remotes = ArrayList<IrTv.Remote>()
    for ((fn, k) in listOf("irdb_tv.txt" to "tv", "irdb_tv_mi.txt" to "tv", "irdb_sat.txt" to "sat")) File(dir, fn).bufferedReader(Charsets.UTF_8).useLines { IrTv.parseInto(it, k, remotes) }
    fun candsOf(kind: String) = remotes.filter { it.kind == kind }.mapNotNull { r ->
        val m = HashMap<String, String>()
        for (k in IrWizardCore.KEY_ORDER) { val s = r.keys[k] ?: continue; m[k] = IrWizardCore.sigId(s.proto, s.a, s.b, s.freq, s.raw) }
        if (m.isEmpty()) null else WizCand(r.brand, r.name, r.kind, m, r.keys.size)
    }
    val rnd = java.util.Random(42)
    for (kind in listOf("tv", "sat")) {
        val all = candsOf(kind)
        val devs = (0 until 120).map { all[rnd.nextInt(all.size)] }
        var wrong = 0; var dead = 0; val presses = ArrayList<Int>()
        for (dev in devs) {
            val w = IrWizardCore(all, listOf(dev.brand))
            var n = 0
            while (!w.dead && !w.locked() && n < 3000) {
                val p = w.probe() ?: break
                w.answer(dev.sigs[p.key] == p.sig); n++
            }
            if (w.dead) dead++ else if (w.alive.none { it.brand == dev.brand && it.name == dev.name }) wrong++ else presses.add(n)
        }
        presses.sort()
        val med = if (presses.isEmpty()) 9999 else presses[presses.size / 2]
        check("[$kind] الريموت الصح ما بيتشالش أبدًا (wrong=$wrong dead=$dead)", wrong == 0 && dead == 0)
        check("[$kind] ماركة معروفة: الوسيط ≤ 15 محاولة (فعليًا $med)", med <= 15)
    }

    println(if (fails == 0) "الاختبارات: كلها نجحت" else "الاختبارات: فشل $fails")
    if (fails > 0) System.exit(1)
}
