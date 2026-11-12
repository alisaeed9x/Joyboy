package devtests.pagescantest

import com.tttt.subtitler.*

var fails = 0
fun check(n: String, ok: Boolean, d: String = "") { if (ok) println("PASS $n") else { fails++; println("FAIL $n $d") } }
fun main() {
    val pg = "https://site.example/movies/page1"
    fun c(u: String, k: String = "a", t: String = "") = PageScan.classify(u, t, k, pg)
    check("فحص: mp4 في لينك = فيديو", c("https://cdn.example/v/a.mp4?x=1")?.cat == PageScan.C_VIDEO)
    check("فحص: عنصر video من غير امتداد = فيديو", c("https://cdn.example/stream/123", "m")?.cat == PageScan.C_VIDEO)
    check("فحص: apk = ملف", c("https://x.example/app.apk")?.let { it.cat == PageScan.C_FILE && it.ext == "apk" } == true)
    check("فحص: mediafire = موقع رفع + الاسم من اللينك", c("https://www.mediafire.com/file/abc123/My%20App.apk/file")?.let { it.cat == PageScan.C_HOST && it.name == "My App.apk" } == true)
    check("فحص: لينك mediafire مش ملف (الرئيسية) = مش مهم", c("https://www.mediafire.com/") == null)
    check("فحص: صفحة فيديو بالكلمة في المسار", c("https://site.example/watch/55", "a", "حلقة 5")?.cat == PageScan.C_PAGE)
    check("فحص: لينك عادي = مش مهم", c("https://site.example/about") == null)
    check("فحص: نفس الصفحة = مش مهم", c(pg) == null)
    check("فحص: javascript/mailto = مش مهم", c("mailto:a@b.c") == null)
    val html1 = """<a id="downloadButton" class="input popsok" href="#" data-scrambled-url="${java.util.Base64.getEncoder().encodeToString("https://download1234.mediafire.com/xyz/abc/My App.apk".toByteArray())}">Download</a>"""
    check("mediafire: data-scrambled-url", PageScan.mediafire(html1) == "https://download1234.mediafire.com/xyz/abc/My App.apk")
    val html2 = """<a aria-label="Download file" id="downloadButton" href="https://download9.mediafire.com/q/w/file.zip">Download</a>"""
    check("mediafire: href مباشر", PageScan.mediafire(html2) == "https://download9.mediafire.com/q/w/file.zip")
    val html3 = """<script>var u="https://download55.mediafire.com/k/j/f.rar";</script>"""
    check("mediafire: لينك في سكريبت", PageScan.mediafire(html3) == "https://download55.mediafire.com/k/j/f.rar")
    check("mediafire: مفيش لينك = null", PageScan.mediafire("<html>nope</html>") == null)
    check("dropbox: dl=0 ← dl=1", PageScan.resolve("https://www.dropbox.com/s/abc/f.zip?dl=0")?.url == "https://www.dropbox.com/s/abc/f.zip?dl=1")
    check("dropbox: من غير dl بتتضاف", PageScan.resolve("https://www.dropbox.com/s/abc/f.zip")?.url == "https://www.dropbox.com/s/abc/f.zip?dl=1")
    check("drive: رابط تحميل من الـid", PageScan.resolve("https://drive.google.com/file/d/AbC-123_x/view")?.url?.contains("id=AbC-123_x") == true)
    val json = """[["https://cdn.example/a.mp4","t","m"],["https://cdn.example/a.mp4","t","a"],["https://site.example/watch/1","ep","ai"],["https://x.example/a.zip","z","a"],["https://x.example/b.apk","b","a"]]"""
    val l = PageScan.parse(json, pg)
    check("parse: بدون تكرار + الترتيب فيديو ثم ملفات ثم صفحات", l.map { it.cat } == listOf(PageScan.C_VIDEO, PageScan.C_FILE, PageScan.C_FILE, PageScan.C_PAGE), l.map { it.cat }.toString())
    check("parse: JSON بايظ = فاضي", PageScan.parse("{{{", pg).isEmpty())
    check("v841: mp4 في لينك <a> = viaLink (الفحص اليدوي بس)", c("https://cdn.example/v/a.mp4")?.viaLink == true)
    check("v841: عنصر video = مش viaLink (بيتضاف أوتوماتيك)", c("https://cdn.example/v/a.mp4", "m")?.viaLink == false)
    // (v838) التأكد إن اللينك ملف حقيقي
    check("حقيقي: apk octet-stream بحجم", PageScan.realVerdict(206, "application/octet-stream", "", 5_000_000L, "apk"))
    check("حقيقي: zip", PageScan.realVerdict(200, "application/zip", "", 80_000L, "zip"))
    check("حقيقي: صفحة HTML = لأ", !PageScan.realVerdict(200, "text/html; charset=utf-8", "", -1L, "apk"))
    check("حقيقي: HTML + attachment = آه", PageScan.realVerdict(200, "text/html", "attachment; filename=\"a.html\"", 5000L, "html"))
    check("حقيقي: 404 = لأ", !PageScan.realVerdict(404, "application/octet-stream", "", 9000L, "apk"))
    check("حقيقي: حجم 300 بايت من غير attachment = لأ (صفحة خطأ)", !PageScan.realVerdict(200, "application/octet-stream", "", 300L, "apk"))
    check("حقيقي: ترجمة srt صغيرة text/plain = آه", PageScan.realVerdict(200, "text/plain", "", 200L, "srt"))
    check("حقيقي: json = لأ", !PageScan.realVerdict(200, "application/json", "", 5000L, "zip"))
    check("حقيقي: mp3 audio/mpeg", PageScan.realVerdict(206, "audio/mpeg", "", 3_000_000L, "mp3"))
    check("حقيقي: من غير نوع + امتداد معروف = آه", PageScan.realVerdict(200, "", "", -1L, "pdf"))
    check("حقيقي: من غير نوع + امتداد مجهول = لأ", !PageScan.realVerdict(200, "", "", -1L, "php"))
    check("حقيقي: صورة = لأ", !PageScan.realVerdict(200, "image/png", "", 50_000L, "apk"))
    check("اسم: filename بين علامتين", PageScan.nameFromDisposition("attachment; filename=\"My App.apk\"") == "My App.apk")
    check("اسم: filename* مشفّر", PageScan.nameFromDisposition("attachment; filename*=UTF-8''%D9%85%D9%84%D9%81.zip") == "ملف.zip")
    check("اسم: filename من غير علامات", PageScan.nameFromDisposition("attachment; filename=app.apk; size=3") == "app.apk")
    check("اسم: مفيش = null", PageScan.nameFromDisposition("inline") == null && PageScan.nameFromDisposition(null) == null)
    check("ScanItem: الحجم اختياري", ScanItem(PageScan.C_FILE, "https://x/a.zip", "", "a.zip", "zip").size == -1L && ScanItem(PageScan.C_FILE, "https://x/a.zip", "", "a.zip", "zip", 99L).size == 99L)
    check("JS: التشغيل الأوتوماتيك مش بيدوس على أزرار", !PageScan.AUTO_PLAY_JS.contains(".click(") && PageScan.AUTO_PLAY_JS.contains("v.muted=true"))
    check("JS: الإيقاف بيرجّع الصوت", PageScan.AUTO_PAUSE_JS.contains("__jbm") && PageScan.AUTO_PAUSE_JS.contains("v.pause()"))
    if (fails > 0) { println("FAILED: $fails"); System.exit(1) }
    println("ALL OK"); System.exit(0)
}
