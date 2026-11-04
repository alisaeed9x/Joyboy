package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.net.URLEncoder

/**
 * (v746) وضع «صيد الترجمة» في متصفح البرنامج: بيفتح المتصفح على بحث/موقع ترجمة باسم الفيديو (أو الفولدر) + «sub file»،
 * وأول ما تضغط تحميل على ملف ترجمة (SRT/ASS/VTT) أو ZIP (حلقة واحدة أو باك كل الحلقات) بيتحمّل ويتفك ويتضاف للفيديو علطول
 * (الباك بيتخزّن والحلقة بتاعتك بتتختار). كل ده من غير ما تدخل تحميلات المتصفح العادية.
 */
object SubHunt {
    @Volatile var active = false
    @Volatile var info: SubName.Info? = null
    @Volatile var langs: List<String> = listOf("ar", "en")
    @Volatile private var deliver: ((SubOnline.Got) -> Boolean)? = null

    val siteNames = listOf(
        "🔎 جوجل — اسم الفيديو + sub file",
        "🔎 جوجل — اسم الفولدر/المسلسل + sub file",
        "🐱 SubtitleCat",
        "📘 Podnapisi",
        "🎞 OpenSubtitles"
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** نص البحث: العنوان (+ SxxEyy للمسلسل) */
    fun queryOf(i: SubName.Info, seriesOnly: Boolean = false): String {
        val t = (if (i.weakEp && i.titleAlt.isNotBlank()) i.titleAlt else i.title).ifBlank { i.raw }
        if (seriesOnly || i.episode < 0 || i.weakEp || i.season < 0) return t
        return t + String.format(java.util.Locale.US, " S%02dE%02d", i.season, i.episode)
    }

    fun searchUrl(site: Int, i: SubName.Info): String = when (site) {
        0 -> "https://www.google.com/search?q=" + enc(queryOf(i) + " sub file")
        1 -> "https://www.google.com/search?q=" + enc(queryOf(i, true) + " sub file")
        2 -> "https://www.subtitlecat.com/index.php?search=" + enc(queryOf(i))
        3 -> "https://www.podnapisi.net/subtitles/search/advanced?keywords=" + enc(queryOf(i, true)) + "&language=ar&language=en"
        else -> "https://www.opensubtitles.org/en/search/sublanguageid-ara,eng/moviename-" + enc(queryOf(i, true))
    }

    private val EXT = Regex("(?i)\\.(srt|ass|ssa|vtt|zip|gz)(?=$|[?#&/])")
    private val SUB_MIME = Regex("(?i)(subrip|x-subtitle|text/vtt|/zip|x-zip|x-gzip|/gzip)")

    /** لينك ينزّل ملف ترجمة (بالامتداد) — بنحوّله لتحميل عن طريقنا بدل ما يفتح كصفحة نص */
    fun urlLooksSub(u: String): Boolean = EXT.containsMatchIn(u.substringBefore('?')) || Regex("(?i)\\.(srt|ass|vtt|zip)(\\?|$)").containsMatchIn(u)

    /** تحميل المتصفح (url / content-disposition / mime) شكله ترجمة أو ZIP؟ */
    fun looksLikeSub(url: String, cd: String?, mime: String?): Boolean {
        if (urlLooksSub(url)) return true
        if (cd != null && Regex("(?i)filename\\*?=[^;]*\\.(srt|ass|ssa|vtt|zip|gz|rar|7z)").containsMatchIn(cd)) return true
        return mime != null && SUB_MIME.containsMatchIn(mime)
    }

    private fun nameFrom(url: String, cd: String?): String {
        val m = cd?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it) }
        val n = m?.groupValues?.get(1)?.trim()
        if (!n.isNullOrBlank()) return try { java.net.URLDecoder.decode(n, "UTF-8") } catch (_: Throwable) { n }
        return url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "sub.srt" }
    }

    /** فتح متصفح البرنامج على الموقع المختار. deliver بيطبّق الترجمة على الفيديو (true = اتضافت) */
    fun start(act: Activity, i: SubName.Info, site: Int, deliverFn: (SubOnline.Got) -> Boolean) {
        info = i; langs = SubOnline.prefLangs(); deliver = deliverFn; active = true
        act.startActivity(Intent(act, BrowserActivity::class.java).putExtra("start", searchUrl(site, i)).putExtra("noauto", true))
    }

    fun stop() { active = false; deliver = null }

    /** تحميل الملف (بكوكيز المتصفح) وفكه واختيار الحلقة وتسليمه للمشغّل. done(ok, رسالة) على خيط خلفي */
    fun capture(ctx: Context, url: String, ua: String, cookie: String?, referer: String?, cd: String?, done: (Boolean, String) -> Unit) {
        val i = info ?: SubName.build("", "")
        val lg = langs
        Thread {
            try {
                val hd = HashMap<String, String>()
                hd["User-Agent"] = ua
                if (!cookie.isNullOrBlank()) hd["Cookie"] = cookie
                if (!referer.isNullOrBlank()) hd["Referer"] = referer
                val r = SubOnline.http(url, hd)
                if (r.code != 200 || r.body.isEmpty()) { done(false, "⚠ التحميل فشل (كود ${r.code}) — جرّب لينك تاني"); return@Thread }
                var why = ""
                val g = SubOnline.ingest(ctx, r.body, nameFrom(url, cd), i, lg, "", "المتصفح", { why = it })
                if (g == null) { done(false, why.ifBlank { "⚠ الملف ده مش ترجمة صالحة" }); return@Thread }
                val d = deliver
                if (d == null) { done(true, "⬇ اتحمّلت (${g.cues.size} جملة) — افتح الفيديو وادوس «جيب ترجمة أونلاين» وهتتضاف من الكاش"); return@Thread }
                val ok = d(g)
                done(ok, if (ok) "✅ اتضافت ترجمة «${g.fileName.take(40)}»" + (if (g.packSize > 1) " (من باك ${g.packSize} ملف — حلقتك اتختارت)" else "") else "⚠ ما قدرتش أضيف الترجمة على الفيديو")
            } catch (e: Throwable) {
                LogStore.err("SubHunt:capture", e)
                done(false, "⚠ فشل: " + (e.message ?: e.javaClass.simpleName).take(60))
            }
        }.also { it.isDaemon = true }.start()
    }
}
