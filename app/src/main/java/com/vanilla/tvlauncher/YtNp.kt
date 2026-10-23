package com.vanilla.tvlauncher

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/** صفحة نتايج: العناصر + صفحة الجاية (null = خلصت). Page نوع من NewPipe فبيتخبّى هنا عشان الواجهة ما تعرفوش. */
class YtRes(val items: List<YtItem>, internal val next: Page?) { val hasMore: Boolean get() = next != null }

/**
 * Downloader بتاع NewPipeExtractor على HttpURLConnection — مكيّف من YtDownloader في Subtitler v475 (D-013)
 * **بدون** أي ترويسات حساب/كوكيز: الطلبات كلها مجهولة (مفيش تسجيل دخول في اللانشر). 429 = ReCaptchaException زي الأصل.
 */
object YtDownloader : Downloader() {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    override fun execute(request: Request): Response {
        val c = URL(request.url()).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000; c.readTimeout = 30000
            c.requestMethod = request.httpMethod()
            c.setRequestProperty("User-Agent", UA)
            for ((k, vs) in request.headers()) {
                if (vs.isEmpty()) continue
                c.setRequestProperty(k, vs[0])
                for (i in 1 until vs.size) c.addRequestProperty(k, vs[i])
            }
            c.setRequestProperty("Accept-Encoding", "gzip")
            val data = request.dataToSend()
            if (data != null) { c.doOutput = true; c.outputStream.use { it.write(data) } }
            val code = c.responseCode
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            var ins = if (code >= 400) c.errorStream else c.inputStream
            if (ins != null && "gzip".equals(c.contentEncoding, true)) ins = GZIPInputStream(ins)
            val body = ins?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val hs = HashMap<String, List<String>>()
            for ((k, v) in c.headerFields) if (k != null) hs[k] = v
            val last = c.url.toString()
            val msg = c.responseMessage ?: ""
            return Response(code, msg, hs, body, last)
        } finally {
            try { c.disconnect() } catch (_: Throwable) { }   // P11: كانت بتتسرّب لو حصل استثناء قبل disconnect
        }
    }
}

/**
 * طبقة NewPipeExtractor المصغّرة للانشر (P8): بحث فيديوهات + استخراج رابط التشغيل. كله بيتنادى من خيط خلفي (شبكة).
 * مكيّفة من YtNp في Subtitler v475 (نفس الإصدار v0.26.5). مش منقول: الحساب/الكوكيز، YtExtract (عملاء بالتوازي)،
 * SponsorBlock، قنوات، تعليقات، رئيسية، فيد اشتراكات — كلها خارج النطاق (D-013).
 * ⚠ ده استخراج غير رسمي: بيتأثر بأي تغيير في يوتيوب (يتصلح بتحديث المكتبة)، وبيخضع لشروط خدمة يوتيوب — شوف D-013.
 */
object YtNp {
    private val YT get() = ServiceList.YouTube
    @Volatile private var ready = false
    private const val PLAY_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun hl(): String {
        val v = Cfg.str("yt_hl", "auto").trim().lowercase()
        if (v.isNotEmpty() && v != "auto") return v
        val d = try { Locale.getDefault().language.lowercase() } catch (_: Throwable) { "" }
        return if (d.isBlank() || d == "und") "ar" else d
    }
    private fun gl(): String {
        val d = try { Locale.getDefault().country.uppercase() } catch (_: Throwable) { "" }
        return if (d.length == 2) d else if (hl() == "ar") "EG" else "US"
    }

    @Synchronized fun init() {
        if (ready) return
        NewPipe.init(YtDownloader, Localization(hl(), gl()), ContentCountry(gl()))
        ready = true
    }

    private fun idOf(url: String): String = try { YT.streamLHFactory.getId(url) } catch (_: Throwable) { "" }
    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun thumb(i: InfoItem): String {
        val t = i.thumbnails
        return if (t == null || t.isEmpty()) "" else t[t.size / 2].url ?: ""
    }

    /** فيديوهات بس (قنوات/قوايم تتجاهل) والشورتس بتتخفى (YtLogic.keep) */
    private fun conv(i: InfoItem): YtItem? {
        if (i !is StreamInfoItem) return null
        val id = idOf(i.url)
        if (!YtLogic.isValidId(id) || !YtLogic.keep(i.url, i.duration)) return null
        return YtItem(id, i.name ?: "", i.uploaderName ?: "", thumb(i), i.duration, i.viewCount, i.textualUploadDate ?: "")
    }

    private fun res(p: ListExtractor.InfoItemsPage<out InfoItem>): YtRes =
        YtRes(p.items.mapNotNull { conv(it) }, if (p.hasNextPage()) p.nextPage else null)

    private fun handler(q: String) = YT.searchQHFactory.fromQuery(q, listOf("videos"), "")

    fun searchFirst(q: String): YtRes {
        init()
        val si = SearchInfo.getInfo(YT, handler(q))
        return YtRes(si.relatedItems.mapNotNull { conv(it) }, if (si.hasNextPage()) si.nextPage else null)
    }

    fun searchMore(q: String, r: YtRes): YtRes {
        val p = r.next ?: return YtRes(emptyList(), null)
        init()
        return res(SearchInfo.getMoreItems(YT, handler(q), p))
    }

    /**
     * رابط التشغيل: أعلى جودة ≤ maxH (0 = الأعلى). مدمج (صوت+فيديو) لو متاح بنفس الارتفاع، وإلا فيديو-بس mp4 + صوت منفصل.
     * الصوت: لغة عربية لو موجودة، وإلا الأصلي، وإلا الأعلى جودة — m4a أولًا (عشان الدبلجات ما تتختارش بالغلط). آخر حل: HLS. null = فشل.
     */
    fun pick(id: String, maxH: Int): YtStream? {
        init()
        val i = StreamInfo.getInfo(YT, watchUrl(id))
        val ttl = i.name ?: ""
        val vo0 = i.videoOnlyStreams.filter { !it.content.isNullOrBlank() }
        val vo = vo0.filter { it.format?.suffix == "mp4" }.ifEmpty { vo0 }
        val mux = i.videoStreams.filter { !it.content.isNullOrBlank() }
        val aud0 = i.audioStreams.filter { !it.content.isNullOrBlank() && it.audioTrackType?.name != "DESCRIPTIVE" }
        val ar = aud0.filter { (it.audioLocale?.language ?: "").lowercase() == "ar" }
        val orig = aud0.filter { it.audioTrackType?.name == "ORIGINAL" }
        val pool = ar.ifEmpty { orig }.ifEmpty { aud0 }.sortedByDescending { it.averageBitrate }
        val bestAud = (pool.firstOrNull { it.format?.suffix == "m4a" } ?: pool.firstOrNull())?.content

        // ارتفاع → (رابط، صوت). المدمج الأول (صوته الافتراضي)، وبعدين فيديو-بس لو في صوت منفصل
        val byH = HashMap<Int, Pair<String, String?>>()
        for (m in mux) if (m.height > 0 && !byH.containsKey(m.height)) byH[m.height] = Pair(m.content, null)
        if (bestAud != null) for (v in vo.sortedByDescending { it.bitrate }) {
            if (v.height <= 0 || byH.containsKey(v.height)) continue
            byH[v.height] = Pair(v.content, bestAud)
        }
        val h = YtLogic.chooseHeight(byH.keys, maxH)
        if (h != null) { val c = byH[h]!!; return YtStream(c.first, c.second, PLAY_UA, ttl, h) }
        val hls = i.hlsUrl
        if (!hls.isNullOrBlank()) return YtStream(hls, null, PLAY_UA, ttl, 0)
        return null
    }
}
