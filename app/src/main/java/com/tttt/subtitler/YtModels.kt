package com.tttt.subtitler

/** (v889) نماذج بيانات مشتركة (كانت في YtNp.kt المحذوف). مفيش منطق يوتيوب هنا — بس أشكال بيانات بتستخدمها شاشات Joyboy. */
class NpItem(val id: String, val title: String, val channel: String, val channelUrl: String, val thumb: String,
             val dur: Long, val count: Long, val age: String, val kind: Int, val url: String, val ts: Long = 0L, var avatar: String = "", val channelId: String = "",
             val chanVerified: Boolean = false)   // (v1303) القناة موثّقة ✔

class YtPick(val url: String, val audio: String?, val ua: String, val title: String, val h: Int, val qlist: String = "", val durMs: Long = 0L)

// (v1103 Y2) YtHistory الحقيقي رجع في YoutubeUi.kt

object YtAge {
    fun ageOf(ts: Long): String {
        if (ts <= 0L) return ""
        val s = (System.currentTimeMillis() - ts) / 1000L
        if (s < 0) return ""
        fun u(n: Long, one: String, two: String, few: String, many: String): String =
            "منذ " + when { n == 1L -> one; n == 2L -> two; n in 3L..10L -> "$n $few"; else -> "$n $many" }
        return when {
            s < 60 -> "الآن"
            s < 3600 -> u(s / 60, "دقيقة", "دقيقتين", "دقائق", "دقيقة")
            s < 86400 -> u(s / 3600, "ساعة", "ساعتين", "ساعات", "ساعة")
            s < 86400 * 30 -> u(s / 86400, "يوم", "يومين", "أيام", "يومًا")
            s < 86400 * 365 -> u(s / (86400 * 30), "شهر", "شهرين", "شهور", "شهرًا")
            else -> u(s / (86400 * 365), "سنة", "سنتين", "سنين", "سنة")
        }
    }
}
