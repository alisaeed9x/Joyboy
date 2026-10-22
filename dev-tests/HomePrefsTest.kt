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
    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails != 0) System.exit(1)
}
