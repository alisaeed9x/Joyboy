package devtests.appstest

import com.tttt.subtitler.AppRec
import com.tttt.subtitler.CareAppLogic
import com.tttt.subtitler.CarePerm

var okN = 0; var bad = 0
fun chk(n: String, c: Boolean) { if (c) { okN++; println("PASS $n") } else { bad++; println("FAIL $n") } }
const val DAY = 86_400_000L
fun app(pkg: String, sys: Boolean = false, en: Boolean = true, ageDays: Int = 200, req: List<String> = emptyList(), gr: Set<String> = emptySet(), inst: String? = null, now: Long) =
    AppRec(pkg, pkg.substringAfterLast('.'), "1.0", 1L, sys, en, now - ageDays * DAY, now - ageDays * DAY, inst, req, gr)
fun main() {
    val now = 1_800_000_000_000L
    val cam = "android.permission.CAMERA"; val mic = "android.permission.RECORD_AUDIO"
    val fine = "android.permission.ACCESS_FINE_LOCATION"; val inet = "android.permission.INTERNET"
    val a = app("com.x.chat", req = listOf(cam, mic, inet), gr = setOf(cam, inet), now = now)
    val b = app("com.x.sys", sys = true, now = now)
    val c = app("com.x.off", en = false, now = now)
    val d = app("com.x.old", ageDays = 400, now = now)
    val e = app("com.x.new", ageDays = 5, now = now)
    val f = app("com.x.used", ageDays = 400, now = now)
    val all = listOf(a, b, c, d, e, f)

    chk("المستخدم = غير النظام", CareAppLogic.userApps(all).none { it.system } && CareAppLogic.userApps(all).size == 5)
    chk("النظام تطبيق واحد", CareAppLogic.systemApps(all).map { it.pkg } == listOf("com.x.sys"))
    chk("المعطّل", CareAppLogic.disabledApps(all).map { it.pkg } == listOf("com.x.off"))

    val used = mapOf("com.x.used" to now - 10 * DAY, "com.x.old" to now - 120 * DAY)
    val unused = CareAppLogic.unusedApps(all, used, now).map { it.pkg }.toSet()
    chk("غير المستخدم: القديم من غير استخدام + اللي استخدامه أقدم من 90 يوم", "com.x.old" in unused && "com.x.chat" in unused && "com.x.off" in unused)
    chk("المستخدم من 10 أيام مش غير مستخدم", "com.x.used" !in unused)
    chk("التطبيق الجديد (5 أيام) مش بيتحسب", "com.x.new" !in unused)
    chk("تطبيق النظام مش بيتحسب", "com.x.sys" !in unused)

    val camG = CarePerm.GROUPS.first { it.key == "camera" }; val micG = CarePerm.GROUPS.first { it.key == "mic" }; val locG = CarePerm.GROUPS.first { it.key == "loc" }
    chk("الكاميرا: معلن وممنوح", CarePerm.declared(a, camG).size == 1 && CarePerm.granted(a, camG).size == 1)
    chk("المايك: معلن مش ممنوح", CarePerm.declared(a, micG).size == 1 && CarePerm.granted(a, micG).isEmpty())
    chk("الموقع: مش معلن", CarePerm.declared(a, locG).isEmpty())
    chk("INTERNET مش حساس", CarePerm.GROUPS.none { inet in it.perms })
    chk("عدد المجموعات الحساسة الممنوحة = 1", CareAppLogic.grantedSensitiveCount(a) == 1)
    chk("fine location في مجموعة الموقع", fine in locG.perms)

    chk("البحث بالاسم", CareAppLogic.matches(a, "CHAT") && !CareAppLogic.matches(a, "zzz"))
    chk("البحث الفاضي بيرجّع الكل", CareAppLogic.matches(a, "  "))
    chk("مصدر Google Play", CareAppLogic.installerName("com.android.vending") == "Google Play")
    chk("مصدر مجهول", CareAppLogic.installerName(null).startsWith("غير معروف"))
    chk("مثبّت الحزم = APK يدوي غالبًا", CareAppLogic.installerName("com.google.android.packageinstaller").contains("APK"))
    println("الاختبارات: $okN نجح، $bad فشل")
}
