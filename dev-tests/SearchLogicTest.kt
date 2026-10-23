import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun v(id: Long, name: String, fk: String = "a", fn: String = "A") = VideoItem(id, "content://v/$id", name, fk, fn, id * 1000, 60_000, 1000)
fun w(name: String) = WatchEntry("k$name", "u", name, "a", 1000, 60_000, 5, false)

fun main() {
    // ----- التطبيع -----
    check("تشكيل وتطويل بيتشالوا", SearchLogic.normalize("مُحَمَّـد") == "محمد")
    check("أإآ → ا", SearchLogic.normalize("أحمد إبراهيم آدم") == "احمد ابراهيم ادم")
    check("ى→ي و ة→ه", SearchLogic.normalize("مصطفى مدرسة") == "مصطفي مدرسه")
    check("أرقام هندية → عادية", SearchLogic.normalize("حلقة ٣٢") == "حلقه 32")
    check("رموز الملفات → مسافة", SearchLogic.normalize("Breaking.Bad_S01-E02 [1080p]") == "breaking bad s01 e02 1080p")
    check("مسافات متكررة بتتضغط", SearchLogic.normalize("  a   b ") == "a b")
    // ----- المطابقة -----
    val items = listOf(
        v(1, "Breaking.Bad.S01E01.mkv", "bb", "Breaking Bad"), v(2, "Breaking.Bad.S01E02.mkv", "bb", "Breaking Bad"),
        v(3, "المسلسل - الحلقة ٣.mp4", "ar", "مسلسلات"), v(4, "فيلم أكشن.mp4", "ar", "مسلسلات"),
        v(5, "Better Call Saul S01E10.mkv", "bcs", "Saul"), v(6, "bad boys.mp4", "mov", "Movies"))
    val none = emptySet<String>()
    var r = SearchLogic.search(items, none, emptyList(), "breaking bad")
    check("كلمتين بأي ترتيب", r.videos.map { it.id } == listOf(1L, 2L))
    r = SearchLogic.search(items, none, emptyList(), "bad breaking")
    check("ترتيب الكلمات مش مهم", r.videos.map { it.id }.toSet() == setOf(1L, 2L))
    r = SearchLogic.search(items, none, emptyList(), "bad")
    check("«bad»: اللي بيبدأ بيها الأول (bad boys) بعدين الباقي", r.videos.map { it.id } == listOf(6L, 1L, 2L))
    r = SearchLogic.search(items, none, emptyList(), "الحلقه 3")
    check("عربي: ة/ه وأرقام هندية/عادية", r.videos.map { it.id } == listOf(3L))
    r = SearchLogic.search(items, none, emptyList(), "اكشن")
    check("عربي: أ/ا", r.videos.map { it.id } == listOf(4L))
    r = SearchLogic.search(items, none, emptyList(), "s01e10")
    check("S01E10 بيتلاقي", r.videos.map { it.id } == listOf(5L))
    r = SearchLogic.search(items, none, emptyList(), "   ")
    check("استعلام فاضي = مفيش نتيجة", r.isEmpty)
    r = SearchLogic.search(items, none, emptyList(), "zzzz")
    check("مفيش تطابق = isEmpty", r.isEmpty)
    r = SearchLogic.search(items, setOf("bb"), emptyList(), "breaking")
    check("المجلد المستبعد مش بيظهر في النتايج", r.videos.isEmpty() && r.folders.isEmpty())
    r = SearchLogic.search(items, none, emptyList(), "breaking")
    check("البحث في المجلدات", r.folders.map { it.key } == listOf("bb") && r.folders[0].count == 2)
    r = SearchLogic.search(items, none, listOf(w("Old Movie.mkv"), w("breaking bad pilot.mkv")), "breaking")
    check("البحث في السجل (حتى لو الملف مش في المكتبة)", r.history.map { it.name } == listOf("breaking bad pilot.mkv"))
    r = SearchLogic.search(emptyList(), none, listOf(w("x y.mkv")), "x")
    check("مكتبة فاضية: السجل لسه بيتبحث", r.videos.isEmpty() && r.history.size == 1)
    // ترتيب طبيعي
    val eps = listOf(v(10, "ep 10.mkv"), v(2, "ep 2.mkv"), v(1, "ep 1.mkv"))
    check("ترتيب طبيعي للنتايج المتساوية (2 قبل 10)", SearchLogic.search(eps, none, emptyList(), "ep").videos.map { it.id } == listOf(1L, 2L, 10L))

    // ----- الأخيرة -----
    var rec = emptyList<String>()
    rec = SearchLogic.addRecent(rec, "  breaking   bad ")
    check("addRecent بيضغط المسافات", rec == listOf("breaking bad"))
    rec = SearchLogic.addRecent(rec, "فيلم")
    rec = SearchLogic.addRecent(rec, "Breaking Bad")
    check("منع التكرار (بعد التطبيع) والأحدث أولًا", rec == listOf("Breaking Bad", "فيلم"))
    check("أقل من حرفين مايتسجّلش", SearchLogic.addRecent(rec, "a") == rec && SearchLogic.addRecent(rec, "  ") == rec)
    var many = emptyList<String>(); for (i in 1..15) many = SearchLogic.addRecent(many, "query $i")
    check("حد أقصى ${SearchLogic.MAX_RECENT}", many.size == SearchLogic.MAX_RECENT && many[0] == "query 15")
    check("encode/decode ذهاب وعودة", SearchLogic.decodeRecents(SearchLogic.encodeRecents(rec)) == rec)
    check("decode يتجاهل الأسطر الفاضية", SearchLogic.decodeRecents("a b\n\n  \nc d") == listOf("a b", "c d"))
    check("removeRecent", SearchLogic.removeRecent(rec, "فيلم") == listOf("Breaking Bad"))
    // ----- Back -----
    check("Back مع نص = مسح", SearchLogic.backStep("abc") == SearchLogic.BACK_CLEAR)
    check("Back من غير نص = خروج", SearchLogic.backStep("") == SearchLogic.BACK_LEAVE && SearchLogic.backStep("  ") == SearchLogic.BACK_LEAVE)
    // ----- رابط يوتيوب -----
    check("رابط يوتيوب مُرمَّز", SearchLogic.youtubeSearchUrl(" breaking bad ") == "https://www.youtube.com/results?search_query=breaking+bad")
    check("رابط يوتيوب بعربي", SearchLogic.youtubeSearchUrl("فيلم").startsWith("https://www.youtube.com/results?search_query=%D9%81"))

    println(if (fails == 0) "ALL PASS" else "FAILS: $fails")
    if (fails > 0) System.exit(1)
}
