import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    check("mime srt/vtt/ass/ssa", SubLogic.mimeFor("a.SRT") == "application/x-subrip" && SubLogic.mimeFor("a.vtt") == "text/vtt" && SubLogic.mimeFor("a.ass") == "text/x-ssa" && SubLogic.mimeFor("a.ssa") == "text/x-ssa")
    check("امتداد غير ترجمة = null", SubLogic.mimeFor("a.mp4") == null && SubLogic.mimeFor("noext") == null && !SubLogic.isSubName("a.txt"))
    check("baseName", SubLogic.baseName("Movie.2020.mkv") == "Movie.2020" && SubLogic.baseName("x") == "x")
    check("langOf: movie.ar.srt", SubLogic.langOf("movie.ar.srt") == "ar" && SubLogic.langOf("movie.en.forced.srt") == "en" && SubLogic.langOf("movie.srt") == null)
    check("langOf: ما بتعتبرش رقم لغة", SubLogic.langOf("movie.2020.srt") == null)
    val files = listOf("Movie.srt", "Movie.en.srt", "Movie.ar.srt", "Other.srt", "Movie2.srt", "Movie.mkv", "notes.txt", "movie.AR.vtt")
    val m = SubLogic.matchSidecars("Movie.mkv", files)
    check("مطابقة الاسم (بدون حساسية حروف) وبيستبعد Other/Movie2/غير الترجمات", m.toSet() == setOf("Movie.srt", "Movie.en.srt", "Movie.ar.srt", "movie.AR.vtt"))
    check("الأقصر اسمًا الأول (Movie.srt) والعربي قبل الإنجليزي", m.first() == "Movie.srt" && m.indexOf("Movie.ar.srt") < m.indexOf("Movie.en.srt"))
    check("مفيش مطابقة = فاضي", SubLogic.matchSidecars("Nothing.mp4", files).isEmpty())
    check("اسم الفيديو بنقط كتير", SubLogic.matchSidecars("A.B.C.mkv", listOf("A.B.C.srt", "A.B.srt")) == listOf("A.B.C.srt"))
    // ربط فيديو ← ترجمة
    var map = emptyList<Pair<String, String>>()
    map = SubLogic.mapPut(map, "a.mkv|100|5000", "content://x/1")
    map = SubLogic.mapPut(map, "b.mkv|200|6000", "content://x/2")
    check("get", SubLogic.mapGet(map, "a.mkv|100|5000") == "content://x/1" && SubLogic.mapGet(map, "zzz") == null)
    map = SubLogic.mapPut(map, "a.mkv|100|5000", "content://x/9")
    check("put على نفس المفتاح = استبدال وبينقل للآخر", map.size == 2 && map.last() == ("a.mkv|100|5000" to "content://x/9"))
    check("encode/decode ثابت", SubLogic.decodeMap(SubLogic.encodeMap(map)) == map)
    check("decode بيتخطّى السطور التالفة", SubLogic.decodeMap("x\n\tfoo\nk\tv\n\n").let { it == listOf("k" to "v") })
    check("remove", SubLogic.mapRemove(map, "a.mkv|100|5000").size == 1)
    var big = emptyList<Pair<String, String>>()
    for (i in 1..(SubLogic.MAP_CAP + 30)) big = SubLogic.mapPut(big, "k$i", "u$i")
    check("الحد الأقصى ${SubLogic.MAP_CAP} (الأقدم بيتشال)", big.size == SubLogic.MAP_CAP && SubLogic.mapGet(big, "k1") == null && SubLogic.mapGet(big, "k${SubLogic.MAP_CAP + 30}") != null)
    check("مفتاح فيه tab/سطر جديد ما بيكسرش الترميز", SubLogic.decodeMap(SubLogic.encodeMap(SubLogic.mapPut(emptyList(), "a\tb\nc", "u"))).size == 1)
    check("shortName بيفك %2F والترميز", SubLogic.shortName("content://x/document/primary%3ASubs%2Fفيلم%20واحد.srt").endsWith(".srt"))
    // مستطيل الترجمة المصوّرة (حاوية 1920x1080)
    val U = SubLogic.UNSET
    val r1 = SubLogic.bitmapRect(1920f, 1080f, 800, 100, U, 0, U, 0, U, U)
    check("بدون إحداثيات = منتصف أسفل جوه الحاوية", r1[0] == 560f && r1[2] == 1360f && r1[3] <= 1080f && r1[3] > 1000f)
    val r2 = SubLogic.bitmapRect(1920f, 1080f, 800, 100, 0.25f, 0, 0.8f, 0, 0.4f, 0.1f)
    check("PGS: بداية/بداية: x=480 y=864 w=768 h=108", r2[0] == 480f && r2[1] == 864f && r2[2] == 480f + 768f && r2[3] == 864f + 108f)
    val r3 = SubLogic.bitmapRect(1920f, 1080f, 800, 100, 0.5f, 1, 0.9f, 2, 0.5f, 0.1f)
    check("anchor منتصف/نهاية: x مركزي والحافة السفلية على 0.9", r3[0] == 480f && r3[2] == 1440f && Math.abs(r3[3] - 972f) < 0.01f)
    val r4 = SubLogic.bitmapRect(1920f, 1080f, 800, 100, 0.95f, 0, 0.95f, 0, 0.4f, 0.1f)
    check("بيتقصّ جوه الحاوية", r4[2] <= 1920f && r4[3] <= 1080f && r4[0] >= 0f)
    check("بدون bitmapHeight: بيحافظ على النسبة", SubLogic.bitmapRect(1000f, 1000f, 400, 100, U, 0, U, 0, 0.5f, U).let { Math.abs((it[2] - it[0]) / (it[3] - it[1]) - 4f) < 0.01f })
    check("مقاسات صفر = مستطيل فاضي (مفيش كراش)", SubLogic.bitmapRect(0f, 0f, 0, 0, 0f, 0, 0f, 0, 0f, 0f).all { it == 0f })
    println(if (fails == 0) "ALL PASS" else "FAILS=$fails"); if (fails > 0) System.exit(1)
}
