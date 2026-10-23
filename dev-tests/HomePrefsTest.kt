import com.vanilla.tvlauncher.HomePrefs

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    check("toggle يضيف", HomePrefs.toggle("", "cont") == "cont")
    check("toggle يشيل", HomePrefs.toggle("cont,lib", "cont") == "lib")
    check("toggle مرتين = الأصل", HomePrefs.toggle(HomePrefs.toggle("lib", "more"), "more") == "lib")
    check("hiddenSet بيتجاهل الفراغات", HomePrefs.hiddenSet(" a , ,b,") == setOf("a", "b"))
    check("cycle دايري", HomePrefs.cycle(listOf(0, 1, 5), 5) == 0 && HomePrefs.cycle(listOf(0, 1, 5), 0) == 1)
    check("cycle قيمة مجهولة = الأولى", HomePrefs.cycle(listOf(2, 1, 0), 99) == 2)
    val all3 = listOf("a", "b", "c")
    check("order: فاضي = الافتراضي", HomePrefs.order("", all3) == all3)
    check("order: محفوظ + الجديد في الآخر", HomePrefs.order("c,a", all3) == listOf("c", "a", "b"))
    check("order: مجهول/مكرر يتشال", HomePrefs.order("x,b,b", all3) == listOf("b", "a", "c"))
    check("move: لفوق", HomePrefs.move(all3, "b", -1) == listOf("b", "a", "c"))
    check("move: لتحت", HomePrefs.move(all3, "a", 1) == listOf("b", "a", "c"))
    check("move: على الحافة/مجهول = بدون تغيير", HomePrefs.move(all3, "a", -1) == all3 && HomePrefs.move(all3, "c", 1) == all3 && HomePrefs.move(all3, "zz", 1) == all3)
    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails != 0) System.exit(1)
}
