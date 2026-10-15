package com.tttt.subtitler

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/** (v325) عنصر في قوايم يوتيوب الداخلي: kind 0 فيديو · 1 قناة · 2 قايمة تشغيل (ts = وقت الرفع بالمللي لو معروف) */
class YtItem(val id: String, val title: String, val channel: String, val channelUrl: String, val thumb: String,
             val dur: Long, val count: Long, val age: String, val kind: Int, val url: String, val ts: Long = 0L)

class YtRes(val items: List<YtItem>, val next: Page?)
class YtDetail(val id: String, val title: String, val channel: String, val channelUrl: String, val desc: String, val views: Long,
               val likes: Long, val age: String, val thumb: String, val dur: Long, val related: List<YtItem>)
class YtPick(val url: String, val audio: String?, val ua: String, val title: String, val h: Int, val qlist: String = "")
class YtChan(val name: String, val first: YtRes, val more: (Page) -> YtRes)

/** (v325) Downloader بتاع NewPipeExtractor على HttpURLConnection (من غير مكتبة إضافية) */
object YtDownloader : Downloader() {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    override fun execute(request: Request): Response {
        val c = URL(request.url()).openConnection() as HttpURLConnection
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
        if (code == 429) { c.disconnect(); throw ReCaptchaException("reCaptcha Challenge requested", request.url()) }
        var ins = if (code >= 400) c.errorStream else c.inputStream
        if (ins != null && "gzip".equals(c.contentEncoding, true)) ins = GZIPInputStream(ins)
        val body = ins?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        val hs = HashMap<String, List<String>>()
        for ((k, v) in c.headerFields) if (k != null) hs[k] = v
        val last = c.url.toString()
        val msg = c.responseMessage ?: ""
        c.disconnect()
        return Response(code, msg, hs, body, last)
    }
}

/** (v325) طبقة NewPipeExtractor: بحث · اقتراحات · قنوات · قوايم تشغيل · مقترحات · تريند · فيد الاشتراكات */
object YtNp {
    private val YT get() = ServiceList.YouTube
    @Volatile private var ready = false

    @Synchronized fun init() {
        if (ready) return
        NewPipe.init(YtDownloader, Localization("ar", "EG"), ContentCountry("EG"))
        ready = true
    }

    fun idOf(url: String): String = try { YT.streamLHFactory.getId(url) } catch (_: Throwable) { "" }
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun thumb(i: InfoItem): String {
        val t = i.thumbnails
        return if (t == null || t.isEmpty()) "" else t[t.size / 2].url ?: ""
    }

    fun conv(i: InfoItem): YtItem? = when (i) {
        is StreamInfoItem -> {
            val id = idOf(i.url)
            if (id.isEmpty()) null else {
                val ts = try { i.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L } catch (_: Throwable) { 0L }
                YtItem(id, i.name ?: "", i.uploaderName ?: "", i.uploaderUrl ?: "", thumb(i), i.duration, i.viewCount,
                    i.textualUploadDate ?: "", 0, i.url, ts)
            }
        }
        is ChannelInfoItem -> YtItem("", i.name ?: "", "", i.url, thumb(i), -1L, i.subscriberCount, "", 1, i.url)
        is PlaylistInfoItem -> YtItem("", i.name ?: "", i.uploaderName ?: "", i.uploaderUrl ?: "", thumb(i), -1L, i.streamCount, "", 2, i.url)
        else -> null
    }

    private fun res(p: ListExtractor.InfoItemsPage<out InfoItem>): YtRes =
        YtRes(p.items.mapNotNull { conv(it) }, if (p.hasNextPage()) p.nextPage else null)

    fun suggestions(q: String): List<String> = try { init(); YT.suggestionExtractor.suggestionList(q) } catch (_: Throwable) { emptyList() }

    /** filter: videos / channels / playlists / "" (الكل) */
    fun searchFirst(q: String, filter: String): YtRes {
        init()
        val qh = YT.searchQHFactory.fromQuery(q, if (filter.isEmpty()) emptyList() else listOf(filter), "")
        val si = SearchInfo.getInfo(YT, qh)
        return YtRes(si.relatedItems.mapNotNull { conv(it) }, if (si.hasNextPage()) si.nextPage else null)
    }
    fun searchMore(q: String, filter: String, p: Page): YtRes {
        val qh = YT.searchQHFactory.fromQuery(q, if (filter.isEmpty()) emptyList() else listOf(filter), "")
        return res(SearchInfo.getMoreItems(YT, qh, p))
    }

    fun channel(url: String): YtChan {
        init()
        val ci = ChannelInfo.getInfo(YT, url)
        val tab = ci.tabs.firstOrNull { it.contentFilters.contains("videos") } ?: ci.tabs.first()
        val ti = ChannelTabInfo.getInfo(YT, tab)
        val first = YtRes(ti.relatedItems.mapNotNull { conv(it) }, if (ti.hasNextPage()) ti.nextPage else null)
        return YtChan(ci.name ?: "", first) { p -> res(ChannelTabInfo.getMoreItems(YT, tab, p)) }
    }

    fun playlist(url: String): Pair<YtRes, (Page) -> YtRes> {
        init()
        val pi = PlaylistInfo.getInfo(YT, url)
        val first = YtRes(pi.relatedItems.mapNotNull { conv(it) }, if (pi.hasNextPage()) pi.nextPage else null)
        return Pair(first, { p: Page -> res(PlaylistInfo.getMoreItems(YT, url, p)) })
    }

    // كاش لآخر StreamInfo (5 دقايق): صفحة التفاصيل والتشغيل بيتشاركوا نفس الطلب
    @Volatile private var infoId = ""
    @Volatile private var infoAt = 0L
    @Volatile private var infoV: StreamInfo? = null
    private fun info(id: String): StreamInfo {
        val c = infoV
        if (c != null && infoId == id && System.currentTimeMillis() - infoAt < 300000L) return c
        init()
        val i = StreamInfo.getInfo(YT, watchUrl(id))
        infoV = i; infoId = id; infoAt = System.currentTimeMillis()
        return i
    }

    fun related(id: String): List<YtItem> = info(id).relatedItems.mapNotNull { conv(it) }.filter { it.kind == 0 }

    /** (v326) تفاصيل فيديو: وصف · مشاهدات · لايكات · قناة · مقترحات */
    fun details(id: String): YtDetail {
        val i = info(id)
        val t = i.thumbnails
        return YtDetail(id, i.name ?: "", i.uploaderName ?: "", i.uploaderUrl ?: "", i.description?.content ?: "", i.viewCount, i.likeCount,
            i.textualUploadDate ?: "", if (t == null || t.isEmpty()) "" else t[t.size / 2].url ?: "", i.duration,
            i.relatedItems.mapNotNull { conv(it) }.filter { it.kind == 0 })
    }

    /** (v326) استخراج رابط التشغيل بـ NewPipe (بديل لـ YtExtract): فيديو بس mp4 + صوت m4a لو أعلى جودة من المدمج، وإلا المدمج، وإلا HLS */
    fun pick(id: String, maxH: Int): YtPick? {
        val i = info(id)
        val vo0 = i.videoOnlyStreams.filter { !it.content.isNullOrBlank() }
        val vo = vo0.filter { it.format?.suffix == "mp4" }.ifEmpty { vo0 }
        val mux = i.videoStreams.filter { !it.content.isNullOrBlank() }
        val aud = i.audioStreams.filter { !it.content.isNullOrBlank() }.sortedByDescending { it.averageBitrate }
        val bestAud = aud.firstOrNull { it.format?.suffix == "m4a" } ?: aud.firstOrNull()
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val ttl = i.name ?: ""
        // (v327) كل الجودات المتاحة (نفس شكل YtExtract.Pick.optsJson: l/u/a/h) عشان زرار الجودة في المشغّل يشتغل
        val byH = HashMap<Int, Array<String?>>()   // h -> [label, url, audio]
        for (m in mux) if (m.height > 0 && !byH.containsKey(m.height)) byH[m.height] = arrayOf(m.height.toString() + "p", m.content, null)
        if (bestAud != null) for (v in vo.sortedByDescending { it.bitrate }) {
            if (v.height <= 0 || byH.containsKey(v.height)) continue
            byH[v.height] = arrayOf(v.height.toString() + "p", v.content, bestAud.content)
        }
        if (byH.isNotEmpty()) {
            val hs = byH.keys.sortedDescending()
            val h = (if (maxH > 0) hs.firstOrNull { it <= maxH } ?: hs.last() else hs.first())
            val arr = org.json.JSONArray()
            for (k in hs) { val o = byH[k]!!; arr.put(org.json.JSONObject().put("l", o[0]).put("u", o[1]).put("a", o[2] ?: "").put("h", k)) }
            val c = byH[h]!!
            return YtPick(c[1]!!, c[2], ua, ttl, h, arr.toString())
        }
        val hls = i.hlsUrl
        if (!hls.isNullOrBlank()) return YtPick(hls, null, ua, ttl, 0)
        return null
    }

    /** (v326) تعليقات الفيديو (kind 3: title = النص · channel = الكاتب · count = لايكات · thumb = صورته) */
    fun comments(id: String): Pair<YtRes, (Page) -> YtRes> {
        init()
        val ci = CommentsInfo.getInfo(YT, watchUrl(id))
        fun cv(c: CommentsInfoItem): YtItem {
            val av = c.uploaderAvatars
            return YtItem("", c.commentText?.content ?: "", c.uploaderName ?: "", "", if (av == null || av.isEmpty()) "" else av[0].url ?: "",
                -1L, c.likeCount.toLong(), c.textualUploadDate ?: "", 3, "")
        }
        val first = YtRes(ci.relatedItems.map { cv(it) }, if (ci.hasNextPage()) ci.nextPage else null)
        return Pair(first, { p: Page ->
            val pg = CommentsInfo.getMoreItems(YT, ci, p)
            YtRes(pg.items.map { cv(it) }, if (pg.hasNextPage()) pg.nextPage else null)
        })
    }

    /** الرئيسية: مقترحات من آخر اللي اتفرّجت عليه، ولو مفيش سجل → تريند، ولو فشل → بحث عام */
    fun home(hist: List<YtItem>): YtRes {
        init()
        val out = LinkedHashMap<String, YtItem>()
        val seen = hist.map { it.id }.toSet()
        val seeds = hist.filter { it.kind == 0 && it.id.isNotEmpty() }.take(3)
        val results = arrayOfNulls<List<YtItem>>(seeds.size)
        val ths = seeds.mapIndexed { n, s -> Thread { results[n] = try { related(s.id) } catch (_: Throwable) { emptyList() } }.also { it.start() } }
        ths.forEach { try { it.join(25000) } catch (_: Throwable) {} }
        // تقليب بين نتايج كل فيديو عشان التنوّع
        val lists = results.map { it ?: emptyList() }
        var k = 0
        while (lists.any { k < it.size }) { for (l in lists) if (k < l.size) { val v = l[k]; if (v.id !in seen) out.putIfAbsent(v.id, v) }; k++ }
        if (out.size < 12) {
            try {
                val ex = YT.kioskList.defaultKioskExtractor
                ex.fetchPage()
                ex.initialPage.items.mapNotNull { conv(it) }.forEach { if (it.id !in seen) out.putIfAbsent(it.id, it) }
            } catch (_: Throwable) {}
        }
        if (out.isEmpty()) {
            try { searchFirst("الأكثر مشاهدة اليوم", "videos").items.forEach { out.putIfAbsent(it.id, it) } } catch (_: Throwable) {}
        }
        return YtRes(out.values.toList(), null)
    }

    /** فيد الاشتراكات: آخر فيديوهات كل قناة مدموجة بالأحدث أولًا */
    /** (v327) الفيد بقى RSS (YtRss.feed) — ده للتوافق بس */
    fun subsFeed(subs: List<YtItem>): YtRes = YtRss.feed(subs)
}

/** (v325) تخزين محلي: سجل · محفوظات · اشتراكات (في Cfg، سطر لكل عنصر) */
object YtStore {
    private const val SEP = "\u0001"
    const val HIST = "ytp_hist"; const val SAVED = "ytp_saved"; const val SUBS = "ytp_subs"
    private fun clean(s: String) = s.replace('\n', ' ').replace(SEP, " ")

    fun read(key: String): MutableList<YtItem> = Cfg.str(key, "").lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
        val p = l.split(SEP)
        if (p.size < 7) null else YtItem(p[0], p[1], p[2], p[3], p[4], p[5].toLongOrNull() ?: -1L, 0L, "", p[6].toIntOrNull() ?: 0, p.getOrElse(7) { "" })
    }.toMutableList()

    fun write(key: String, l: List<YtItem>) {
        Cfg.put(key, l.joinToString("\n") { listOf(it.id, clean(it.title), clean(it.channel), it.channelUrl, it.thumb, it.dur.toString(), it.kind.toString(), it.url).joinToString(SEP) })
    }

    private fun same(a: YtItem, b: YtItem) = if (a.kind == 1 || b.kind == 1) a.channelUrl == b.channelUrl else a.id == b.id && a.url == b.url

    fun contains(key: String, x: YtItem) = read(key).any { same(it, x) }

    fun addFront(key: String, x: YtItem, cap: Int) {
        val l = read(key); l.removeAll { same(it, x) }; l.add(0, x)
        write(key, if (l.size > cap) l.subList(0, cap) else l)
    }
    /** بيرجّع true لو بقى موجود بعد التبديل */
    fun toggle(key: String, x: YtItem): Boolean {
        val l = read(key)
        return if (l.removeAll { same(it, x) }) { write(key, l); false } else { l.add(0, x); write(key, l); true }
    }
    fun remove(key: String, x: YtItem) { val l = read(key); if (l.removeAll { same(it, x) }) write(key, l) }
}
