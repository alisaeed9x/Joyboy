package devtests.trslidertest

import com.tttt.subtitler.*
import java.io.File

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean) { if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name") } }

fun main() {
    // (v1100) T2b — متكيّف على v1095: مفيش direct()/twostep (مش متوصّلين)، والاختبار بيراجع كمان إن كل بلوك wired ليه وصلة فعلًا في Engine.kt
    for (b in TrSlider.speedBlocks()) check("سريع: ${b.id} شغّال عند 0", TrSlider.onAt(b.id, 0))
    for (b in TrSlider.accuracyBlocks()) check("سريع: ${b.id} مطفي عند 0", !TrSlider.onAt(b.id, 0))
    for (b in TrSlider.accuracyBlocks()) check("دقيق: ${b.id} شغّال عند 10", TrSlider.onAt(b.id, 10))
    for (b in TrSlider.speedBlocks()) check("دقيق: ${b.id} مطفي عند 10", !TrSlider.onAt(b.id, 10))
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
    check("الوسط: listen+refine شغّالين", TrSlider.onAt("listen", 5) && TrSlider.onAt("refine", 5))
    check("الوسط: مفيش cross/chars", !TrSlider.onAt("cross", 5) && !TrSlider.onAt("chars", 5))
    check("clamp", TrSlider.onAt("cross", 99) && !TrSlider.onAt("cross", -5))
    check("id مجهول شغّال", TrSlider.onAt("nope", 0) && TrSlider.onAt("nope", 10))
    check("ids فريدة", TrSlider.blocks.map { it.id }.toSet().size == TrSlider.blocks.size)
    check("المناطق", TrSlider.zone(0) == TrSlider.SPEED && TrSlider.zone(3) == TrSlider.SPEED && TrSlider.zone(4) == TrSlider.BAL && TrSlider.zone(6) == TrSlider.BAL && TrSlider.zone(7) == TrSlider.ACC && TrSlider.zone(10) == TrSlider.ACC)
    // (T2b) تطابق wired مع الوصلات الفعلية في Engine.kt
    val eng = listOf("src/main/java/com/tttt/subtitler/Engine.kt", "app/src/main/java/com/tttt/subtitler/Engine.kt").map { File(it) }.firstOrNull { it.exists() }
    if (eng == null) { println("SKIP Engine.kt مش لاقيه من هنا") } else {
        val src = eng.readText(Charsets.UTF_8)
        for (b in TrSlider.blocks) {
            val hooked = src.contains("TrSlider.on(\"${b.id}\")")
            check("${b.id}: wired=${b.wired} ⇔ وصلة في Engine", hooked == b.wired)
        }
    }
    println("pass=$pass fail=$fail")
    System.exit(if (fail == 0) 0 else 1)
}
