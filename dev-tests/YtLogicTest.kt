import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }
fun yi(id: String, title: String = "t", ch: String = "", views: Long = -1, age: String = "", dur: Long = 100) = YtItem(id, title, ch, "", dur, views, age)

class FakeReCaptchaEx : Exception()

fun main() {
    check("uri: تحويل ذهاب وإياب", YtLogic.uriOf("dQw4w9WgXcQ") == "yt:dQw4w9WgXcQ" && YtLogic.idOf("yt:dQw4w9WgXcQ") == "dQw4w9WgXcQ")
    check("uri: isYt", YtLogic.isYt("yt:abc") && !YtLogic.isYt("content://media/1") && !YtLogic.isYt("https://x"))
    check("uri: idOf لغير يوتيوب = فاضي", YtLogic.idOf("content://media/1") == "")
    check("id: صالح", YtLogic.isValidId("dQw4w9WgXcQ") && YtLogic.isValidId("a_b-c1D2e3F"))
    check("id: غير صالح", !YtLogic.isValidId("short") && !YtLogic.isValidId("dQw4w9WgXc!") && !YtLogic.isValidId("dQw4w9WgXcQQ") && !YtLogic.isValidId(""))
    check("keep: شورتس بالمدة", !YtLogic.keep("https://www.youtube.com/watch?v=x", 45) && YtLogic.keep("https://www.youtube.com/watch?v=x", 61))
    check("keep: شورتس بالرابط", !YtLogic.keep("https://www.youtube.com/shorts/abc", 300))
    check("keep: مدة مجهولة (0) أو بث مباشر (-1) تفضل", YtLogic.keep("https://www.youtube.com/watch?v=x", 0) && YtLogic.keep("https://www.youtube.com/watch?v=x", -1))
    val v = YtLogic.toVideoItem(yi("dQw4w9WgXcQ", "أغنية", dur = 212))
    check("toVideoItem: uri/اسم/مدة", v.uri == "yt:dQw4w9WgXcQ" && v.name == "أغنية" && v.durMs == 212_000L && v.folderKey == "yt")
    check("toVideoItem: id ثابت لنفس العنصر", YtLogic.toVideoItem(yi("dQw4w9WgXcQ")).id == v.id)
    check("formatViews", YtLogic.formatViews(-1) == "" && YtLogic.formatViews(950) == "950" && YtLogic.formatViews(12_345) == "12K" && YtLogic.formatViews(3_400_000) == "3.4M")
    check("subtitle: كل الأجزاء", YtLogic.subtitle(yi("a", ch = "قناة", views = 12_345, age = "منذ يوم")) == "قناة · 12K مشاهدة · منذ يوم")
    check("subtitle: بدون أجزاء فاضية", YtLogic.subtitle(yi("a", ch = "قناة")) == "قناة" && YtLogic.subtitle(yi("a")) == "")
    val m = YtLogic.mergeById(listOf(yi("a"), yi("b")), listOf(yi("b"), yi("c"), yi("a"), yi("d")))
    check("mergeById: بدون تكرار + الترتيب", m.map { it.id } == listOf("a", "b", "c", "d"))
    check("mergeById: قايمة قديمة فاضية", YtLogic.mergeById(emptyList(), listOf(yi("x"))).size == 1)
    val hs = listOf(144, 360, 720, 1080, 480, 720, 0)
    check("chooseHeight: سقف 720", YtLogic.chooseHeight(hs, 720) == 720)
    check("chooseHeight: سقف بين قيمتين (900 → 720)", YtLogic.chooseHeight(hs, 900) == 720)
    check("chooseHeight: سقف أقل من الكل → الأقل", YtLogic.chooseHeight(listOf(360, 720), 144) == 360)
    check("chooseHeight: 0 = الأعلى", YtLogic.chooseHeight(hs, 0) == 1080)
    check("chooseHeight: فاضي/أصفار = null", YtLogic.chooseHeight(emptyList(), 720) == null && YtLogic.chooseHeight(listOf(0, 0), 0) == null)
    check("qualityLabel", YtLogic.qualityLabel(0) == "الأعلى المتاح" && YtLogic.qualityLabel(1080) == "1080p")
    check("friendlyError: مفيش نت", YtLogic.friendlyError(java.net.UnknownHostException("x")).contains("النت"))
    check("friendlyError: سبب متداخل (timeout)", YtLogic.friendlyError(RuntimeException("w", java.net.SocketTimeoutException())).contains("بطيء"))
    check("friendlyError: reCaptcha", YtLogic.friendlyError(FakeReCaptchaEx()).contains("تحقق"))
    check("friendlyError: غير معروف = اسم الكلاس", YtLogic.friendlyError(IllegalStateException("x")).contains("IllegalStateException"))
    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails != 0) System.exit(1)
}
