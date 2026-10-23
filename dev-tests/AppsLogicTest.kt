import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    val self = "com.vanilla.tvlauncher"
    val raw = listOf(AppEntry("com.b", "Beta"), AppEntry(self, "Me"), AppEntry("com.a", "alpha"), AppEntry("com.b", "Beta2"), AppEntry("", "x"), AppEntry("com.c", ""))
    val r = AppsLogic.clean(raw, self)
    check("clean: يشيل اللانشر والفاضي والمكرر + ترتيب بالاسم", r.map { it.pkg } == listOf("com.a", "com.b", "com.c"))
    check("clean: يحتفظ بأول مكرر", r.first { it.pkg == "com.b" }.label == "Beta")
    check("clean: اسم فاضي = اسم الحزمة", r.first { it.pkg == "com.c" }.label == "com.c")
    val n = AppsLogic.clean(listOf(AppEntry("p10", "App 10"), AppEntry("p2", "App 2")), self)
    check("clean: ترتيب طبيعي (2 قبل 10)", n.map { it.pkg } == listOf("p2", "p10"))
    check("clean: قايمة فاضية", AppsLogic.clean(emptyList(), self).isEmpty())
    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails != 0) System.exit(1)
}
