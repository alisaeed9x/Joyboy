package devtests.trslidertest

import com.tttt.subtitler.*

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean) { if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name") } }

fun main() {
    // الطرف السريع: كل بلوكات السرعة شغّالة وكل بلوكات الدقة مطفية (ما عدا اللي عتبته 0..)
    for (b in TrSlider.speedBlocks()) check("سريع: ${b.id} شغّال عند 0", TrSlider.onAt(b.id, 0))
    for (b in TrSlider.accuracyBlocks()) check("سريع: ${b.id} مطفي عند 0", !TrSlider.onAt(b.id, 0))
    // الطرف الدقيق: العكس
    for (b in TrSlider.accuracyBlocks()) check("دقيق: ${b.id} شغّال عند 10", TrSlider.onAt(b.id, 10))
    for (b in TrSlider.speedBlocks()) check("دقيق: ${b.id} مطفي عند 10", !TrSlider.onAt(b.id, 10))
    // تدريج: بلوك الدقة مابيرجعش يتطفّي كل ما الموضع يزيد، وبلوك السرعة مابيرجعش يشتغل
    var mono = true
    for (b in TrSlider.blocks) {
        var prev = TrSlider.onAt(b.id, 0)
        for (p in 1..TrSlider.MAX) {
            val cur = TrSlider.onAt(b.id, p)
            if (b.speed && !prev && cur) mono = false
            if (!b.speed && prev && !cur) mono = false
            prev = cur
        }
    }
    check("التدريج أحادي الاتجاه لكل البلوكات", mono)
    // المسار المباشر = 0..2 بس، والتفريغ ثم الترجمة عكسه بالظبط
    check("direct عند 0..2", (0..2).all { TrSlider.direct(it) })
    check("direct مطفي عند 3..10", (3..10).none { TrSlider.direct(it) })
    check("twostep عكس direct", (0..TrSlider.MAX).all { TrSlider.onAt("twostep", it) == !TrSlider.direct(it) })
    // الوسط متوازن: تفريغ ثم ترجمة + سماع + تنقيح، من غير تسميع مزدوج ولا شخصيات
    check("الوسط: twostep+listen+refine", TrSlider.onAt("twostep", 5) && TrSlider.onAt("listen", 5) && TrSlider.onAt("refine", 5))
    check("الوسط: مفيش cross/chars", !TrSlider.onAt("cross", 5) && !TrSlider.onAt("chars", 5))
    // قيم برّه المدى بتتقصّ، وid مجهول شغّال (ما نكسرش حاجة)
    check("clamp", TrSlider.onAt("cross", 99) && !TrSlider.onAt("cross", -5))
    check("id مجهول شغّال", TrSlider.onAt("nope", 0) && TrSlider.onAt("nope", 10))
    check("ids فريدة", TrSlider.blocks.map { it.id }.toSet().size == TrSlider.blocks.size)
    println("pass=$pass fail=$fail")
    System.exit(if (fail == 0) 0 else 1)
}
