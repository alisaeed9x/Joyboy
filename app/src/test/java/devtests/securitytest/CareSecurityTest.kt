package devtests.securitytest

import com.tttt.subtitler.AppRec
import com.tttt.subtitler.CareAppLogic
import com.tttt.subtitler.CareSecurity

var okN = 0; var bad = 0
fun chk(n: String, c: Boolean) { if (c) { okN++; println("PASS $n") } else { bad++; println("FAIL $n") } }
const val DAY = 86_400_000L
private fun p(n: String) = "android.permission.$n"
fun app(pkg: String, sys: Boolean = false, inst: String? = null, perms: List<String> = emptyList(), apk: Long = 0L) =
    AppRec(pkg, pkg.substringAfterLast('.'), "1.0", 1L, sys, true, 0L, 0L, inst, perms, perms.toSet(), apk)

fun main() {
    val sensitive = listOf(p("CAMERA"), p("RECORD_AUDIO"), p("ACCESS_FINE_LOCATION"))
    val risky = app("com.x.risky", inst = null, perms = sensitive)
    val store = app("com.x.store", inst = "com.android.vending", perms = sensitive)
    val sys = app("com.x.sys", sys = true, perms = sensitive)
    val calm = app("com.x.calm", inst = "com.google.android.packageinstaller", perms = listOf(p("CAMERA")))

    chk("مثبّت يدويًا = مالوش متجر", CareAppLogic.isSideloaded(risky) && CareAppLogic.isSideloaded(calm))
    chk("تطبيق المتجر مش يدوي", !CareAppLogic.isSideloaded(store))
    chk("تطبيق النظام مش يدوي", !CareAppLogic.isSideloaded(sys))

    val clean = CareSecurity.review(listOf(store, sys), CareSecurity.Env(true, 30, false))
    chk("جهاز سليم = 100", clean.score == 100 && clean.findings.isEmpty())

    val noLock = CareSecurity.review(emptyList(), CareSecurity.Env(false, null, null))
    chk("مفيش قفل = −15", noLock.score == 85 && noLock.findings.first().id == "lock")

    val old = CareSecurity.review(emptyList(), CareSecurity.Env(true, 400, true))
    chk("تحديث قديم −10 وUSB −5", old.score == 85)
    chk("تحديث 200 يوم = −4", CareSecurity.review(emptyList(), CareSecurity.Env(true, 200, false)).score == 96)

    val unknown = CareSecurity.review(emptyList(), CareSecurity.Env(null, null, null))
    chk("قراءة فاشلة (null) مش بتتخصم", unknown.score == 100 && unknown.findings.isEmpty())

    val r = CareSecurity.review(listOf(risky, calm), CareSecurity.Env(true, 10, false))
    chk("تطبيق يدوي بـ3 مجموعات حساسة = −5", r.score == 95 && r.findings.any { it.id == "risky" && it.pkgs == listOf("com.x.risky") })

    val many = (1..10).map { app("com.x.r$it", perms = sensitive) }
    chk("خصم التطبيقات بحد أقصى −20", CareSecurity.review(many, CareSecurity.Env(true, 10, false)).score == 80)
    chk("النتيجة مرتبة: الأخطر الأول", noLock.findings.first().sev == CareSecurity.SEV_ATTENTION)

    val big = app("com.x.big", apk = 500L); val small = app("com.x.small", apk = 5L)
    chk("ترتيب بالحجم (APK)", CareAppLogic.sorted(listOf(small, big), 1, emptyMap()).first().pkg == "com.x.big")
    chk("الحجم الحقيقي يغلب الـAPK", CareAppLogic.sorted(listOf(small, big), 1, mapOf("com.x.small" to 9_999L)).first().pkg == "com.x.small")
    chk("أمر ADB للتعطيل", CareAppLogic.adbDisable("com.a.b") == "adb shell pm disable-user --user 0 com.a.b")
    chk("أمر ADB بيشيل أي رموز غريبة", !CareAppLogic.adbEnable("com.a; rm -rf /").contains(";"))
    println("الاختبارات: $okN نجح، $bad فشل")
    if (bad > 0) System.exit(1)
}
