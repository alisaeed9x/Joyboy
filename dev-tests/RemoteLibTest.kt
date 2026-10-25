import com.tttt.subtitler.*

var fails = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) println("PASS $name") else { fails++; println("FAIL $name $extra") }
}

fun main() {
    // tv:// رابط ذهاب وعودة
    check("tvUri/idOf round-trip", LibraryCore.idOf(LibraryCore.tvUri(12345L)) == 12345L)
    check("idOf: مش tv:// ← null", LibraryCore.idOf("https://x.com/a.mp4") == null && LibraryCore.idOf("tv://abc") == null)

    // /lib/folders
    val fb = """{"ok":true,"folders":[{"key":"a","name":"أفلام","path":"/sd/a","count":3,"size":1048576,"newest":100},{"key":"b","name":"مسلسلات","path":"/sd/b","count":1,"size":0,"newest":900}]}"""
    val fl = LibraryCore.parseFolders(fb)
    check("parseFolders: عددين", fl != null && fl.size == 2)
    check("parseFolders: الحقول", fl != null && fl[0].key == "a" && fl[0].name == "أفلام" && fl[0].count == 3 && fl[0].size == 1048576L && fl[0].newest == 100L)
    check("parseFolders: ok=false ← null", LibraryCore.parseFolders("""{"ok":false,"err":"perm"}""") == null)
    check("parseFolders: مش JSON ← null", LibraryCore.parseFolders("<html>") == null)
    check("parseFolders: من غير folders ← فاضية", LibraryCore.parseFolders("""{"ok":true}""") == emptyList<RFolder>())
    check("الأحدث أولًا للفولدرات", fl != null && LibraryCore.foldersNewestFirst(fl).map { it.key } == listOf("b", "a"))

    // /lib/videos
    val vb = """{"ok":true,"videos":[
        {"id":7,"name":"old.mp4","title":"old","size":2048,"durMs":65000,"added":10,"modified":0,"w":1920,"h":1080,"vid":"f:old.mp4:2048","ext":"mp4"},
        {"id":8,"name":"new.mkv","title":"new","size":1610612736,"durMs":3661000,"added":10,"modified":500,"w":0,"h":0,"vid":"f:new.mkv:1610612736","ext":"mkv"}]}"""
    val vl = LibraryCore.parseVideos(vb)
    check("parseVideos: عددين", vl != null && vl.size == 2)
    check("parseVideos: الحقول", vl != null && vl[1].id == 8L && vl[1].vid == "f:new.mkv:1610612736" && vl[1].ext == "mkv" && vl[1].durMs == 3661000L)
    check("dateMs: modified لو موجود وإلا added", vl != null && vl[0].dateMs == 10_000L && vl[1].dateMs == 500_000L)
    check("الأحدث أولًا للفيديوهات", vl != null && LibraryCore.videosNewestFirst(vl).map { it.id } == listOf(8L, 7L))

    // أخطاء
    check("errOf", LibraryCore.errOf("""{"ok":false,"err":"perm"}""") == "perm" && LibraryCore.errOf("x") == "")

    // تنسيق
    check("fmtDur ثواني/دقايق", LibraryCore.fmtDur(65000) == "1:05", LibraryCore.fmtDur(65000))
    check("fmtDur ساعات", LibraryCore.fmtDur(3661000) == "1:01:01", LibraryCore.fmtDur(3661000))
    check("fmtDur صفر ← فاضي", LibraryCore.fmtDur(0) == "")
    check("fmtSize", LibraryCore.fmtSize(1610612736L) == "1.5 GB" && LibraryCore.fmtSize(5L * 1024 * 1024) == "5 MB" && LibraryCore.fmtSize(2048) == "2 KB" && LibraryCore.fmtSize(0) == "")

    // اسم ملف الصورة
    val f1 = LibraryCore.thumbFile("tv:1/2", vl!![0])
    check("thumbFile: من غير رموز خطرة", !f1.contains('/') && !f1.contains(':') && f1.endsWith(".jpg"), f1)
    check("thumbFile: الحجم بيغيّر الاسم", LibraryCore.thumbFile("t", vl[0]) != LibraryCore.thumbFile("t", vl[0].copy(size = 1L)))

    println(if (fails == 0) "\nكل الاختبارات نجحت" else "\nفشل $fails اختبار")
    System.exit(if (fails == 0) 0 else 1)
}
