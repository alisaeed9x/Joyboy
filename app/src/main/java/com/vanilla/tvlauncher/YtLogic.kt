package com.vanilla.tvlauncher

import java.util.Locale

/** نتيجة بحث يوتيوب (P8). durSec = 0 لو مجهولة. */
data class YtItem(val id: String, val title: String, val channel: String, val thumb: String, val durSec: Long, val views: Long, val age: String)

/** رابط تشغيل جاهز للمشغّل: url (+ صوت منفصل لو الفيديو بدون صوت). */
class YtStream(val url: String, val audio: String?, val ua: String, val title: String, val height: Int)

/**
 * منطق يوتيوب النقي (بدون Android ولا NewPipe) — قابل للاختبار على JVM (D-013).
 * عنصر يوتيوب بيتحوّل لـ VideoItem بـ uri = «yt:<id>» عشان يمشي في PlayQueue/PlayerLayer؛ المشغّل بيعرف إنه يوتيوب من الـ uri
 * وما بيسجّلوش في سجل المشاهدة المحلي (D-014).
 */
object YtLogic {
    const val SCHEME = "yt:"
    const val FOLDER_KEY = "yt"

    fun uriOf(id: String) = SCHEME + id
    fun isYt(uri: String) = uri.startsWith(SCHEME)
    fun idOf(uri: String) = if (isYt(uri)) uri.substring(SCHEME.length) else ""

    /** معرّف فيديو يوتيوب: 11 حرف من [A-Za-z0-9_-] */
    fun isValidId(id: String): Boolean = id.length == 11 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }

    /** الشورتس بتتخفى (نفس قاعدة Subtitler): رابط /shorts/ أو مدة 1..60 ثانية */
    fun keep(url: String, durSec: Long): Boolean = !(url.contains("/shorts/") || durSec in 1L..60L)

    fun toVideoItem(y: YtItem): VideoItem =
        VideoItem(y.id.hashCode().toLong(), uriOf(y.id), y.title, FOLDER_KEY, "YouTube", 0L, y.durSec * 1000L, 0L)

    fun formatViews(n: Long): String = when {
        n < 0L -> ""
        n >= 1_000_000L -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000L -> (n / 1_000L).toString() + "K"
        else -> n.toString()
    }

    /** «القناة · مشاهدات · منذ…» بدون أجزاء فاضية */
    fun subtitle(y: YtItem): String {
        val parts = ArrayList<String>()
        if (y.channel.isNotBlank()) parts.add(y.channel)
        val v = formatViews(y.views); if (v.isNotEmpty() && y.views > 0) parts.add("$v مشاهدة")
        if (y.age.isNotBlank()) parts.add(y.age)
        return parts.joinToString(" · ")
    }

    /** ضم صفحة جديدة للقديمة بدون تكرار الـ id (الترتيب القديم أولًا) */
    fun mergeById(old: List<YtItem>, add: List<YtItem>): List<YtItem> {
        val seen = HashSet<String>(); val out = ArrayList<YtItem>(old.size + add.size)
        for (x in old) if (seen.add(x.id)) out.add(x)
        for (x in add) if (seen.add(x.id)) out.add(x)
        return out
    }

    /** اختيار الارتفاع: maxH>0 → أعلى ارتفاع ≤ maxH (وإلا أقل واحد متاح)؛ maxH=0 → الأعلى. null لو مفيش. */
    fun chooseHeight(heights: Collection<Int>, maxH: Int): Int? {
        val hs = heights.filter { it > 0 }.distinct().sortedDescending()
        if (hs.isEmpty()) return null
        return if (maxH > 0) (hs.firstOrNull { it <= maxH } ?: hs.last()) else hs.first()
    }

    /** نصوص إعداد الجودة القصوى (يوتيوب) */
    fun qualityLabel(maxH: Int): String = if (maxH <= 0) "الأعلى المتاح" else "${maxH}p"

    /** رسالة عربي مفهومة لخطأ شبكة/يوتيوب (P11). بنفحص أسماء الكلاسات في سلسلة الأسباب عشان الملف ده يفضل بدون NewPipe. */
    fun friendlyError(e: Throwable): String {
        var t: Throwable? = e; var n = 0
        while (t != null && n < 6) {
            val c = t.javaClass.name
            if (c.endsWith("UnknownHostException") || c.endsWith("ConnectException") || c.endsWith("NoRouteToHostException")) return "مفيش اتصال بالنت — اتأكد من الشبكة"
            if (c.endsWith("SocketTimeoutException")) return "النت بطيء أو الاتصال انقطع — جرّب تاني"
            if (c.contains("ReCaptcha")) return "يوتيوب طلب تحقق مؤقت (ضغط طلبات) — جرّب بعد شوية"
            t = t.cause; n++
        }
        return "حصل خطأ (" + e.javaClass.simpleName + ")"
    }
}
