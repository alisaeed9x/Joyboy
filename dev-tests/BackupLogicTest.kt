import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    val prefs = mapOf<String, Any?>("theme" to "mx", "motion" to 1, "nav_sound" to true, "disp_name" to "بيت\tعلي\nسطر2 \\ آخر", "apps_on" to false, "bad key!" to "x", "x" to 3.5)
    val hist = "line1\nline2|with|pipes\t tab\r\nend"
    val txt = BackupLogic.encode(prefs, hist)
    check("الترويسة أول سطر", txt.startsWith("VTLBACKUP 1\n"))
    check("المفتاح الغلط والنوع غير المدعوم بيتتخطّوا", !txt.contains("bad key") && !txt.contains("\tx\t"))
    val d = BackupLogic.decode(txt)
    check("فك بدون خطأ", d.error == null && d.data != null)
    val p = d.data!!.prefs
    check("نص/رقم/منطقي رجعوا بنفس النوع", p["theme"] == "mx" && p["motion"] == 1 && p["nav_sound"] == true && p["apps_on"] == false)
    check("نص فيه tab وسطر جديد و\\ رجع زي ما هو", p["disp_name"] == "بيت\tعلي\nسطر2 \\ آخر")
    check("سجل المشاهدة رجع حرفيًا (سطور/tab)", d.data!!.history == hist)
    check("عدد الإعدادات = 5 (من غير المتخطّى)", p.size == 5)
    check("encode/decode/encode ثابت", BackupLogic.encode(p, d.data!!.history) == txt)
    // أخطاء
    check("نص عشوائي = خطأ", BackupLogic.decode("hello").error != null)
    check("فاضي = خطأ", BackupLogic.decode("").error != null)
    check("ترويسة بدون سجل = ناقص", BackupLogic.decode("VTLBACKUP 1\ns\ta\tb\n").error != null)
    check("نوع مجهول = خطأ", BackupLogic.decode("VTLBACKUP 1\nz\ta\tb\nH\t\n").error != null)
    check("رقم غلط = خطأ", BackupLogic.decode("VTLBACKUP 1\ni\ta\tabc\nH\t\n").error != null)
    check("منطقي غلط = خطأ", BackupLogic.decode("VTLBACKUP 1\nb\ta\tmaybe\nH\t\n").error != null)
    check("مفتاح بمسافة = خطأ", BackupLogic.decode("VTLBACKUP 1\ns\ta b\tv\nH\t\n").error != null)
    check("سجل مكرر = خطأ", BackupLogic.decode("VTLBACKUP 1\nH\tx\nH\ty\n").error != null)
    check("حجم ضخم = خطأ", BackupLogic.decode("VTLBACKUP 1\n" + "a".repeat(BackupLogic.MAX_BYTES)).error != null)
    check("CRLF مقبول", BackupLogic.decode("VTLBACKUP 1\r\ns\tk\tv\r\nH\t\r\n").error == null)
    check("سجل فاضي مقبول", BackupLogic.decode("VTLBACKUP 1\nH\t\n").data!!.history == "")
    check("قيمة نصية فاضية", BackupLogic.decode(BackupLogic.encode(mapOf("k" to ""), "")).data!!.prefs["k"] == "")
    check("unesc على backslash في الآخر ما بيكسرش", BackupLogic.unesc("abc\\") == "abc\\")
    check("validKey", BackupLogic.validKey("sub_map") && BackupLogic.validKey("a.b:c-d") && !BackupLogic.validKey("") && !BackupLogic.validKey("a b") && !BackupLogic.validKey("x".repeat(65)))
    check("summary", BackupLogic.summary(d.data!!, 3).contains("5 إعداد") && BackupLogic.summary(d.data!!, 3).contains("3"))
    println(if (fails == 0) "ALL PASS" else "FAILS=$fails"); if (fails > 0) System.exit(1)
}
