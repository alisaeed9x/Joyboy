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
class NpItem(val id: String, val title: String, val channel: String, val channelUrl: String, val thumb: String,
             val dur: Long, val count: Long, val age: String, val kind: Int, val url: String, val ts: Long = 0L, val avatar: String = "")

class YtRes(val items: List<NpItem>, val next: Page?)
class YtDetail(val id: String, val title: String, val channel: String, val channelUrl: String, val desc: String, val views: Long,
               val likes: Long, val age: String, val thumb: String, val dur: Long, val related: List<NpItem>)
class YtPick(val url: String, val audio: String?, val ua: String, val title: String, val h: Int, val qlist: String = "", val durMs: Long = 0L)

/** (v388) لغة صوت يوتيوب: التفضيل (yt_alang: ar افتراضي · orig = الأصلي · أو كود لغة) + اختيار المسار + اسمه بالعربي */
object YtLang {
    fun pref(): String = try { Cfg.str("yt_alang", "ar").trim().lowercase().ifBlank { "ar" } } catch (_: Throwable) { "ar" }
    fun code(raw: String): String = raw.substringBefore('.').substringBefore('-').substringBefore('_').trim().lowercase()
    fun name(lang: String, orig: Boolean): String {
        val n = if (lang.isBlank()) "؟" else try { java.util.Locale(lang).getDisplayLanguage(java.util.Locale("ar")).ifBlank { lang } } catch (_: Throwable) { lang }
        return if (orig) "$n (الأصلي)" else n
    }
    class Tr(val lang: String, val orig: Boolean, val dflt: Boolean, val url: String, val bitrate: Int, val alts: List<Pair<String, String>> = emptyList())
    // (v406) لغة لفيديو واحد بس (من علامة «مدبلج» برّه أو من المشغّل المصغّر): بتتستهلك مرة واحدة في pick()
    private val ovr = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun setOverride(id: String, lang: String) { ovr[id] = lang }
    fun takeOverride(id: String): String? = ovr.remove(id)
    /** لغتك المفضّلة لو موجودة، وإلا الأصلي، وإلا الافتراضي، وإلا الأول (الأعلى جودة) */
    fun choose(tr: List<Tr>, pref: String = pref()): Tr? {
        if (tr.isEmpty()) return null
        if (pref != "orig") tr.firstOrNull { it.lang == pref }?.let { return it }
        return tr.firstOrNull { it.orig } ?: tr.firstOrNull { it.dflt } ?: tr.first()
    }
    /** [{n: الاسم, l: اللغة, u: الرابط, c: 1 للمختار}] — بتتحط في أول عنصر من qlist تحت المفتاح "at" */
    fun json(tr: List<Tr>, chosen: Tr?): org.json.JSONArray {
        val a = org.json.JSONArray()
        for (t in tr) {
            val q = org.json.JSONArray()
            for ((n, u) in t.alts) q.put(org.json.JSONObject().put("n", n).put("u", u))
            a.put(org.json.JSONObject().put("n", name(t.lang, t.orig)).put("l", t.lang).put("u", t.url).put("c", if (t === chosen) 1 else 0).put("q", q))
        }
        return a
    }
}
class NpChan(val name: String, val first: YtRes, val more: (Page) -> YtRes)
/** (v403) صفحة قناة كاملة: بانر · أفاتار · مشتركين · وصف · تابات (فيديوهات/بث/قوايم) */
class NpChanTab(val key: String, val label: String)
class NpChanFull(val url: String, val name: String, val avatar: String, val banner: String, val subs: Long, val desc: String, val verified: Boolean,
                 val tabs: List<NpChanTab>, val loadTab: (String) -> Pair<YtRes, (Page) -> YtRes>)
/** (v403) تعليق: مثبّت/قلب من صاحب القناة/صاحب القناة + عدد الردود (صفحة الردود في replies) */
class NpComment(val text: String, val author: String, val avatar: String, val likes: Long, val age: String,
                val pinned: Boolean, val hearted: Boolean, val owner: Boolean, val replyCount: Int, val replies: Page?)
class NpComments(val total: Int, val items: List<NpComment>, val next: Page?)

/** (v325) Downloader بتاع NewPipeExtractor على HttpURLConnection (من غير مكتبة إضافية) */
object YtDownloader : Downloader() {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** (v471) طلبات الويب (next/browse) بس بتتبعت بحسابك لو مسجّل الدخول: ترتيب التعليقات والمقترحات ولغتها بتبقى زي يوتيوب الأصلي على حسابك.
     *  طلبات player (استخراج الروابط) ما بتتلمسش أبدًا. وأي فشل بنعيد الطلب من غير الحساب. الإعداد yt_np_auth */
    private fun accountFor(u: String): Map<String, String>? {
        if (!u.contains("youtube.com/youtubei/v1/")) return null
        if (!(u.contains("/v1/next") || u.contains("/v1/browse"))) return null   // البحث بره عمدًا: بحث الحساب بيتسجّل في «سجل البحث» بتاعك
        return try { if (Cfg.bool("yt_np_auth", true)) YtAccount.authHeaders() else null } catch (_: Throwable) { null }
    }

    override fun execute(request: Request): Response {
        val ah = accountFor(request.url())
        if (ah != null) {
            try { val r = send(request, ah); if (r.responseCode() in 200..399) return r } catch (_: Throwable) {}
        }
        return send(request, null)
    }

    private fun send(request: Request, extra: Map<String, String>?): Response {
        val c = URL(request.url()).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        c.requestMethod = request.httpMethod()
        c.setRequestProperty("User-Agent", UA)
        for ((k, vs) in request.headers()) {
            if (vs.isEmpty()) continue
            c.setRequestProperty(k, vs[0])
            for (i in 1 until vs.size) c.addRequestProperty(k, vs[i])
        }
        if (extra != null) {
            for ((k, v) in extra) {
                if (k.equals("Cookie", true)) { val old = c.getRequestProperty("Cookie"); c.setRequestProperty("Cookie", if (old.isNullOrBlank()) v else "$v; $old") }
                else if (k.equals("Authorization", true)) c.setRequestProperty(k, v)
                else if (c.getRequestProperty(k) == null) c.setRequestProperty(k, v)
            }
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
        NewPipe.init(YtDownloader, Localization(YtLocale.hl(), YtLocale.gl()), ContentCountry(YtLocale.gl()))   // (v471) اللغة من مكان واحد (YtLocale)
        ready = true
    }

    /** (v389) إعادة تهيئة NewPipe ومسح كاش الرئيسية — بتتنادى لما الرئيسية تفضى بعد فترة (علاج ذاتي بدل المزامنة اليدوية) */
    fun clearHome() { homeC = null; dropHomeFile() }   // (v407) + ملف الكاش (سحب للتحديث = رئيسية جديدة فعلًا)
    @Synchronized fun reset() { ready = false; homeC = null; dropHomeFile() }
    private fun dropHomeFile() { try { homeDir?.let { java.io.File(it, "yt_home.json").delete() } } catch (_: Throwable) {} }

    fun idOf(url: String): String = try { YT.streamLHFactory.getId(url) } catch (_: Throwable) { "" }
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun thumb(i: InfoItem): String {
        val t = i.thumbnails
        return if (t == null || t.isEmpty()) "" else t[t.size / 2].url ?: ""
    }

    /** (v397) صورة القناة (أفاتار) لو النسخة بتوفّرها — بالريفلكشن عشان ما يتكسرش البناء لو الدالة مش موجودة */
    private fun avatarOf(i: Any): String {
        return try {
            val l = i.javaClass.getMethod("getUploaderAvatars").invoke(i) as? List<*>
            val f = l?.firstOrNull()
            if (f == null) "" else (f.javaClass.getMethod("getUrl").invoke(f) as? String) ?: ""
        } catch (_: Throwable) { "" }
    }
    fun conv(i: InfoItem): NpItem? = when (i) {
        is StreamInfoItem -> {
            val id = idOf(i.url)
            // (v351) الشورتس مش بتظهر خالص: رابط /shorts/ أو مدة ≤ 60 ثانية
            if (id.isEmpty() || i.url.contains("/shorts/") || i.duration in 1L..60L) null else {
                val ts = try { i.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L } catch (_: Throwable) { 0L }
                NpItem(id, i.name ?: "", i.uploaderName ?: "", i.uploaderUrl ?: "", thumb(i), i.duration, i.viewCount,
                    i.textualUploadDate ?: "", 0, i.url, ts, avatarOf(i))
            }
        }
        is ChannelInfoItem -> NpItem("", i.name ?: "", "", i.url, thumb(i), -1L, i.subscriberCount, "", 1, i.url)
        is PlaylistInfoItem -> NpItem("", i.name ?: "", i.uploaderName ?: "", i.uploaderUrl ?: "", thumb(i), -1L, i.streamCount, "", 2, i.url)
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

    /** (v469) C5 بحث بفلاتر: مدة (0 الكل · 1 قصير <4د · 2 متوسط · 3 طويل >20د) وتاريخ رفع (0 الكل · 1 اليوم · 2 الأسبوع · 3 الشهر).
     *  الفلاتر بتتطبق على النتايج (NewPipe مفيهوش باراميتر sp بتاع الويب) وبتتجب صفحات زيادة لو الفلتر صفّى النتايج */
    // ===== (v521) «آخر حلقة / أحدث / latest»: النتايج بتترتب بتاريخ الرفع (الأحدث الأول) بدل الصلة اللي كانت بتحط الجديد في النص =====
    private val latestW = setOf("اخر", "احدث", "الاخير", "الاخيره", "جديد", "الجديد", "latest", "newest")
    private val epW = setOf("حلقه", "الحلقه", "حلقات", "episode", "ep")
    fun latestIntent(q: String): Boolean = VideoLib.norm(q).split(' ').any { it in latestW }
    private fun stripLatest(q: String): String {
        val keep = VideoLib.norm(q).split(' ').filter { it.isNotBlank() && it !in latestW && it !in epW }
        return keep.joinToString(" ").ifBlank { q }
    }
    private fun ageMs(a: String): Long {
        if (a.isBlank()) return 0L
        val low = a.lowercase()
        val unit = when {
            low.contains("ثاني") || low.contains("second") -> 1000L
            low.contains("دقيق") || low.contains("minute") -> 60_000L
            low.contains("ساع") || low.contains("hour") -> 3_600_000L
            low.contains("يوم") || low.contains("ايام") || low.contains("أيام") || low.contains("day") -> 86_400_000L
            low.contains("اسبوع") || low.contains("أسبوع") || low.contains("اسابيع") || low.contains("أسابيع") || low.contains("week") -> 7 * 86_400_000L
            low.contains("شهر") || low.contains("اشهر") || low.contains("أشهر") || low.contains("month") -> 30 * 86_400_000L
            low.contains("سن") || low.contains("year") -> 365 * 86_400_000L
            else -> return 0L
        }
        val two = low.contains("ساعتين") || low.contains("يومين") || low.contains("اسبوعين") || low.contains("أسبوعين") || low.contains("شهرين") || low.contains("سنتين")
        val n = if (two) 2L else (Regex("\\d+").find(low)?.value?.toLongOrNull() ?: 1L)
        return System.currentTimeMillis() - n * unit
    }
    private fun effTs(x: NpItem): Long = if (x.ts > 0L) x.ts else ageMs(x.age)
    fun searchLatest(q: String, filter: String, durF: Int, dateF: Int): YtRes {
        val core = stripLatest(q)
        var r = searchFirst(core, filter)
        var all = r.items
        var pages = 0
        while (r.next != null && pages < 2) {
            val nx = r.next ?: break
            val m = try { searchMore(core, filter, nx) } catch (_: Throwable) { break }
            all = all + m.items; r = m; pages++
        }
        val toks = VideoLib.sigTokens(core)
        val rel = all.filter { x -> x.kind != 0 || toks.isEmpty() || VideoLib.norm(x.title + " " + x.channel).let { h -> toks.all { h.contains(it) } } }
        val base = if (rel.count { it.kind == 0 } >= 3) rel else all
        val vids = applyF(base.filter { it.kind == 0 }.distinctBy { it.id }, durF, dateF).sortedByDescending { effTs(it) }
        return YtRes(vids + base.filter { it.kind != 0 }, null)
    }

    fun searchFiltered(q: String, filter: String, durF: Int, dateF: Int): YtRes {
        if (filter != "channels" && filter != "playlists" && latestIntent(q)) { try { return searchLatest(q, filter, durF, dateF) } catch (_: Throwable) { } }   // (v521)
        var r = searchFirst(q, filter)
        var pages = 0
        while (countOk(r.items, durF, dateF) < 8 && r.next != null && pages < 2) {
            val nx = r.next ?: break
            val more = try { searchMore(q, filter, nx) } catch (_: Throwable) { break }
            r = YtRes(r.items + more.items, more.next); pages++
        }
        return YtRes(applyF(r.items, durF, dateF), r.next)
    }
    fun applyF(l: List<NpItem>, durF: Int, dateF: Int): List<NpItem> {
        if (durF == 0 && dateF == 0) return l
        val now = System.currentTimeMillis()
        val cut = when (dateF) { 1 -> now - 86400_000L; 2 -> now - 7 * 86400_000L; 3 -> now - 30 * 86400_000L; else -> 0L }
        return l.filter { x ->
            if (x.kind != 0) return@filter true
            val dok = when (durF) { 1 -> x.dur in 61L..239L; 2 -> x.dur in 240L..1200L; 3 -> x.dur > 1200L; else -> true }
            val tok = cut == 0L || x.ts <= 0L || x.ts >= cut
            dok && tok
        }
    }
    private fun countOk(l: List<NpItem>, durF: Int, dateF: Int) = applyF(l, durF, dateF).size

    fun channel(url: String): NpChan {
        init()
        val ci = ChannelInfo.getInfo(YT, url)
        val tab = ci.tabs.firstOrNull { it.contentFilters.contains("videos") } ?: ci.tabs.first()
        val ti = ChannelTabInfo.getInfo(YT, tab)
        val first = YtRes(ti.relatedItems.mapNotNull { conv(it) }, if (ti.hasNextPage()) ti.nextPage else null)
        return NpChan(ci.name ?: "", first) { p -> res(ChannelTabInfo.getMoreItems(YT, tab, p)) }
    }


    // ───────── (v403) صفحة القناة + تعليقات بالردود ─────────
    private fun callAny(o: Any?, name: String): Any? = try { o?.javaClass?.getMethod(name)?.invoke(o) } catch (_: Throwable) { null }
    private fun imgUrl(l: Any?): String {
        val list = l as? List<*> ?: return ""
        // أكبر صورة (آخر واحدة غالبًا)
        for (x in list.reversed()) { val u = callAny(x, "getUrl") as? String; if (!u.isNullOrBlank()) return u }
        return ""
    }

    /** لو لينك القناة ناقص/بايظ: بندوّر على القناة بالاسم ونرجّع أول نتيجة */
    fun findChannelUrl(name: String): String? = try {
        searchFirst(name, "channels").items.firstOrNull { it.kind == 1 && it.channelUrl.isNotBlank() }?.channelUrl
    } catch (_: Throwable) { null }

    fun channelFull(url: String): NpChanFull {
        init()
        val ci = ChannelInfo.getInfo(YT, url)
        val byKey = LinkedHashMap<String, org.schabi.newpipe.extractor.linkhandler.ListLinkHandler>()
        val tabs = ArrayList<NpChanTab>()
        for (t in ci.tabs) {
            val f = t.contentFilters.firstOrNull() ?: continue
            val label = when (f) { "videos" -> "الفيديوهات"; "livestreams" -> "البث المباشر"; "playlists" -> "قوايم التشغيل"; else -> continue }   // الشورتس مخفية (v351)
            if (!byKey.containsKey(f)) { byKey[f] = t; tabs.add(NpChanTab(f, label)) }
        }
        if (tabs.isEmpty()) { val t = ci.tabs.firstOrNull(); if (t != null) { val f = t.contentFilters.firstOrNull() ?: "videos"; byKey[f] = t; tabs.add(NpChanTab(f, "الفيديوهات")) } }
        val subs = (callAny(ci, "getSubscriberCount") as? Long) ?: -1L
        val desc = (callAny(ci, "getDescription") as? String) ?: ""
        val ver = (callAny(ci, "isVerified") as? Boolean) ?: false
        val av = imgUrl(callAny(ci, "getAvatars")); val bn = imgUrl(callAny(ci, "getBanners"))
        val load = { key: String ->
            val h = byKey[key] ?: byKey.values.first()
            val ti = ChannelTabInfo.getInfo(YT, h)
            val first = YtRes(ti.relatedItems.mapNotNull { conv(it) }, if (ti.hasNextPage()) ti.nextPage else null)
            Pair(first, { p: Page -> res(ChannelTabInfo.getMoreItems(YT, h, p)) })
        }
        return NpChanFull(ci.url ?: url, ci.name ?: "", av, bn, subs, desc, ver, tabs, load)
    }

    private fun cmt(c: CommentsInfoItem): NpComment {
        val av = c.uploaderAvatars
        return NpComment(c.commentText?.content ?: "", c.uploaderName ?: "", if (av == null || av.isEmpty()) "" else av[0].url ?: "",
            c.likeCount.toLong(), c.textualUploadDate ?: "",
            (callAny(c, "isPinned") as? Boolean) ?: false, (callAny(c, "isHeartedByUploader") as? Boolean) ?: false, (callAny(c, "isChannelOwner") as? Boolean) ?: false,
            (callAny(c, "getReplyCount") as? Int) ?: 0, callAny(c, "getReplies") as? Page)
    }
    fun commentsX(id: String): Pair<NpComments, (Page) -> NpComments> {
        init()
        val ci = CommentsInfo.getInfo(YT, watchUrl(id))
        val total = (callAny(ci, "getCommentsCount") as? Int) ?: -1
        val first = NpComments(total, ci.relatedItems.map { cmt(it) }, if (ci.hasNextPage()) ci.nextPage else null)
        return Pair(first, { p: Page ->
            val pg = CommentsInfo.getMoreItems(YT, ci, p)
            NpComments(total, pg.items.map { cmt(it) }, if (pg.hasNextPage()) pg.nextPage else null)
        })
    }
    /** ردود تعليق واحد */
    fun repliesOf(id: String, p: Page): NpComments {
        init()
        val pg = CommentsInfo.getMoreItems(YT, watchUrl(id), p)
        return NpComments(-1, pg.items.map { cmt(it) }, if (pg.hasNextPage()) pg.nextPage else null)
    }

    fun playlist(url: String): Pair<YtRes, (Page) -> YtRes> {
        init()
        val pi = PlaylistInfo.getInfo(YT, url)
        val first = YtRes(pi.relatedItems.mapNotNull { conv(it) }, if (pi.hasNextPage()) pi.nextPage else null)
        return Pair(first, { p: Page -> res(PlaylistInfo.getMoreItems(YT, url, p)) })
    }

    // (v362) كاش لآخر 4 StreamInfo (20 دقيقة) بدل واحد بس: الرئيسية بتجيب 4 فيديوهات مع بعض وكانت بتمسح بعض، وتحويل صوت⇄فيديو بيرجع لنفس الفيديو
    private val infoMap = LinkedHashMap<String, Pair<Long, StreamInfo>>()
    private val infoLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun info(id: String): StreamInfo {
        synchronized(infoMap) { infoMap[id]?.takeIf { System.currentTimeMillis() - it.first < 20L * 60 * 1000L }?.let { return it.second } }
        // (v471) طلب واحد بس لنفس الفيديو: التفاصيل والاستخراج كانوا بيعملوا نفس الجلب التقيل مرتين بالتوازي
        synchronized(infoLocks.getOrPut(id) { Any() }) {
            synchronized(infoMap) { infoMap[id]?.takeIf { System.currentTimeMillis() - it.first < 20L * 60 * 1000L }?.let { return it.second } }
            init()
            val t0 = System.currentTimeMillis()
            val i = StreamInfo.getInfo(YT, watchUrl(id))
            LogStore.add("⏱ NewPipe info $id: " + (System.currentTimeMillis() - t0) + "ms")
            synchronized(infoMap) {
                infoMap[id] = Pair(System.currentTimeMillis(), i)
                while (infoMap.size > 4) { val k = infoMap.keys.first(); infoMap.remove(k) }
            }
            return i
        }
    }

    fun related(id: String): List<NpItem> = info(id).relatedItems.mapNotNull { conv(it) }.filter { it.kind == 0 }

    /** (v471) مقترحات خفيفة (طلب next واحد ~1 ثانية بدل StreamInfo الكامل اللي بيعمل فك تشفير جافاسكربت ويستنى 3-8 ثواني على الموبايل) — الرئيسية كانت بتعمل 3 منهم مع بعض.
     *  لو فشل أو رجّع فاضي بنرجع للطريقة القديمة. */
    fun relFast(id: String): List<NpItem> {
        val l = try { YtAccount.relatedLite(id) } catch (_: Throwable) { emptyList() }
        if (l.isNotEmpty()) return l
        return try { related(id) } catch (_: Throwable) { emptyList() }
    }

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
        // (v388) مسارات الصوت (لغات): نختار لغتك المفضّلة (عربي افتراضيًا) بدل أعلى bitrate عشوائي (كان بيجيب الدبلجة الإنجليزي أحيانًا)
        val trs = audioTracks(aud)
        val multi = trs.size > 1
        val chTr = if (multi) YtLang.choose(trs, YtLang.takeOverride(id) ?: YtLang.pref()) else null
        val audUrl: String? = chTr?.url ?: bestAud?.content
        // (v327) كل الجودات المتاحة (نفس شكل YtExtract.Pick.optsJson: l/u/a/h) عشان زرار الجودة في المشغّل يشتغل
        val byH = HashMap<Int, Array<String?>>()   // h -> [label, url, audio]
        fun addMux() { for (m in mux) if (m.height > 0 && !byH.containsKey(m.height)) byH[m.height] = arrayOf(m.height.toString() + "p", m.content, null) }
        fun addVo() { if (audUrl != null) for (v in vo.sortedByDescending { it.bitrate }) {
            if (v.height <= 0 || byH.containsKey(v.height)) continue
            byH[v.height] = arrayOf(v.height.toString() + "p", v.content, audUrl)
        } }
        // أكتر من لغة صوت: الفيديو-بس + الصوت المختار الأول (عشان لغة الصوت تتبدّل)، والمدمج (بصوته الافتراضي) للارتفاعات الناقصة بس
        if (multi) { addVo(); addMux() } else { addMux(); addVo() }
        if (byH.isNotEmpty()) {
            val hs = byH.keys.sortedDescending()
            val h = (if (maxH > 0) hs.firstOrNull { it <= maxH } ?: hs.last() else hs.first())
            val arr = org.json.JSONArray()
            for (k in hs) { val o = byH[k]!!; arr.put(org.json.JSONObject().put("l", o[0]).put("u", o[1]).put("a", o[2] ?: "").put("h", k)) }
            if (multi) arr.optJSONObject(0)?.put("at", YtLang.json(trs, chTr))
            else { val al = altsOf(aud.filter { it.audioTrackType?.name != "DESCRIPTIVE" }); if (al.size > 1) { val q = org.json.JSONArray(); for ((n, u) in al) q.put(org.json.JSONObject().put("n", n).put("u", u)); arr.optJSONObject(0)?.put("aq", q) } }   // (v406) جودات الصوت
            val c = byH[h]!!
            return YtPick(c[1]!!, c[2], ua, ttl, h, arr.toString())
        }
        val hls = i.hlsUrl
        if (!hls.isNullOrBlank()) return YtPick(hls, null, ua, ttl, 0)
        return null
    }

    /** (v388) مسارات الصوت مجمّعة باللغة (بنتخطى الوصف الصوتي)؛ لكل لغة أعلى جودة (m4a أولًا) */
    private fun audioTracks(aud: List<org.schabi.newpipe.extractor.stream.AudioStream>): List<YtLang.Tr> {
        val by = LinkedHashMap<String, MutableList<org.schabi.newpipe.extractor.stream.AudioStream>>()
        for (a in aud) {
            if (a.audioTrackType?.name == "DESCRIPTIVE") continue
            val lg = (a.audioLocale?.language ?: "").lowercase().ifBlank { YtLang.code(a.audioTrackId ?: "") }
            if (lg.isBlank()) continue
            by.getOrPut(lg) { ArrayList() }.add(a)
        }
        val out = ArrayList<YtLang.Tr>()
        for ((lg, l) in by) {
            val b = l.filter { it.format?.suffix == "m4a" }.maxByOrNull { it.averageBitrate } ?: l.maxByOrNull { it.averageBitrate } ?: continue
            out.add(YtLang.Tr(lg, l.any { it.audioTrackType?.name == "ORIGINAL" }, false, b.content, b.averageBitrate, altsOf(l)))
        }
        return out
    }

    /** (v406) جودات الصوت المتاحة (أعلى أولًا): [(اسم, رابط)] — kbps + الصيغة */
    private fun altsOf(l: List<org.schabi.newpipe.extractor.stream.AudioStream>): List<Pair<String, String>> {
        val seen = HashSet<String>(); val out = ArrayList<Pair<String, String>>()
        for (a in l.sortedByDescending { it.averageBitrate }) {
            val c = a.content ?: continue
            if (c.isBlank()) continue
            val b = a.averageBitrate; val kb = if (b > 1000) b / 1000 else b
            val n = (if (kb > 0) "$kb kbps" else "؟") + " · " + (a.format?.suffix ?: "audio")
            if (seen.add(n)) out.add(Pair(n, c))
            if (out.size >= 6) break
        }
        return out
    }

    /** (v406) لغات الصوت المتاحة للفيديو (أكواد) — لعلامة «مدبلج»: أكتر من لغة = مدبلج. بيستخدم كاش info */
    fun audioLangs(id: String): List<String> {
        val i = info(id)
        val aud = i.audioStreams.filter { !it.content.isNullOrBlank() }
        return audioTracks(aud).map { it.lang }
    }

    /** (v337) رابط الصوت بس (m4a لو متاح، وإلا أعلى جودة) من غير أي داتا فيديو — للتشغيل في مشغّل الموسيقى وللتحميل */
    fun pickAudio(id: String): YtPick? {
        val i = info(id)
        val aud = i.audioStreams.filter { !it.content.isNullOrBlank() }.sortedByDescending { it.averageBitrate }
        val b = aud.firstOrNull { it.format?.suffix == "m4a" } ?: aud.firstOrNull() ?: return null
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val trs = audioTracks(aud)   // (v388) أغاني/صوت بس: الأصلي (مش الدبلجة)
        val au = (if (trs.size > 1) YtLang.choose(trs, "orig")?.url else null) ?: b.content
        return YtPick(au, null, ua, i.name ?: "", 0, "", if (i.duration > 0L) i.duration * 1000L else 0L)
    }

    /** (v326) تعليقات الفيديو (kind 3: title = النص · channel = الكاتب · count = لايكات · thumb = صورته) */
    fun comments(id: String): Pair<YtRes, (Page) -> YtRes> {
        init()
        val ci = CommentsInfo.getInfo(YT, watchUrl(id))
        fun cv(c: CommentsInfoItem): NpItem {
            val av = c.uploaderAvatars
            return NpItem("", c.commentText?.content ?: "", c.uploaderName ?: "", "", if (av == null || av.isEmpty()) "" else av[0].url ?: "",
                -1L, c.likeCount.toLong(), c.textualUploadDate ?: "", 3, "")
        }
        val first = YtRes(ci.relatedItems.map { cv(it) }, if (ci.hasNextPage()) ci.nextPage else null)
        return Pair(first, { p: Page ->
            val pg = CommentsInfo.getMoreItems(YT, ci, p)
            YtRes(pg.items.map { cv(it) }, if (pg.hasNextPage()) pg.nextPage else null)
        })
    }

    /** (v378) فيديوهات عشوائية من قنوات اشتراكاتك: بنختار لحد 10 قنوات عشوائي، ومن كل قناة صفحة عشوائية (الأحدث أو أقدم) وناخد منها 4 فيديوهات عشوائي */
    private fun randomSubVideos(subs: List<NpItem>): List<NpItem> {
        val rnd = java.util.Random()
        val chans = subs.filter { it.channelUrl.isNotBlank() }.shuffled(rnd).take(6)   // (v407) 6 بدل 10: الجلب العميق بقى في الخلفية
        val out = java.util.concurrent.ConcurrentLinkedQueue<NpItem>()
        val ths = chans.map { c ->
            Thread {
                try {
                    val ch = channel(c.channelUrl)
                    var page = ch.first
                    var depth = rnd.nextInt(3)   // 0 = الأحدث · 1-2 = أقدم
                    while (depth > 0) { val n = page.next ?: break; page = try { ch.more(n) } catch (_: Throwable) { break }; depth-- }
                    page.items.filter { it.kind == 0 }.shuffled(rnd).take(4).forEach { out.add(it) }
                } catch (_: Throwable) {}
            }.also { it.isDaemon = true; it.start() }
        }
        val end = System.currentTimeMillis() + 20_000L
        ths.forEach { try { it.join(maxOf(1L, end - System.currentTimeMillis())) } catch (_: Throwable) {} }
        return out.toList().shuffled(rnd)
    }

    /** (v362) آخر رئيسية جاهزة (وقتها + النتيجة): بتتعرض فورًا وبتتحدّث في الخلفية للزيارة الجاية */
    @Volatile private var homeC: Pair<Long, YtRes>? = null
    @Volatile private var homeBusy = false
    @Volatile private var homeDir: java.io.File? = null
    /** (v407) بيتنده (من خيط خلفي) لما الرئيسية تتحدّث في الخلفية — الواجهة بتقارن وتظهر كبسولة «فيديوهات جديدة» */
    @Volatile var onHome: ((YtRes) -> Unit)? = null
    fun attach(c: android.content.Context) { if (homeDir == null) homeDir = c.applicationContext.filesDir }

    fun homeAgeMs(): Long = homeC?.let { System.currentTimeMillis() - it.first } ?: Long.MAX_VALUE

    /** (v407) آخر رئيسية محفوظة حتى لو قديمة (من الذاكرة، وإلا من الملف yt_home.json لحد 7 أيام) — بتتعرض فورًا وبعدين تتحدّث في الخلفية */
    fun homeStale(maxAgeMs: Long = 7L * 24 * 3600 * 1000L): YtRes? {
        homeC?.let { if (it.second.items.isNotEmpty()) return it.second }
        val d = loadHomeDisk() ?: return null
        if (System.currentTimeMillis() - d.first > maxAgeMs) return null
        homeC = d
        return d.second
    }

    private fun saveHome(r: YtRes) {
        try {
            val d = homeDir ?: return
            val a = org.json.JSONArray()
            for (x in r.items.take(90)) a.put(org.json.JSONObject().put("i", x.id).put("t", x.title).put("c", x.channel).put("cu", x.channelUrl).put("th", x.thumb)
                .put("d", x.dur).put("n", x.count).put("a", x.age).put("k", x.kind).put("u", x.url).put("ts", x.ts).put("av", x.avatar))
            val o = org.json.JSONObject().put("at", System.currentTimeMillis()).put("items", a)
            val tmp = java.io.File(d, "yt_home.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(java.io.File(d, "yt_home.json"))
        } catch (_: Throwable) {}
    }

    private fun loadHomeDisk(): Pair<Long, YtRes>? {
        return try {
            val d = homeDir ?: return null
            val f = java.io.File(d, "yt_home.json"); if (!f.isFile) return null
            val o = org.json.JSONObject(f.readText())
            val a = o.getJSONArray("items"); val l = ArrayList<NpItem>()
            for (n in 0 until a.length()) {
                val x = a.getJSONObject(n)
                val ts = x.optLong("ts", 0L)
                l.add(NpItem(x.optString("i"), x.optString("t"), x.optString("c"), x.optString("cu"), x.optString("th"), x.optLong("d", -1L), x.optLong("n", -1L),
                    if (ts > 0L) YtRss.ageOf(ts) else x.optString("a"), x.optInt("k", 0), x.optString("u"), ts, x.optString("av")))
            }
            if (l.isEmpty()) null else Pair(o.optLong("at"), YtRes(l, null))
        } catch (_: Throwable) { null }
    }

    private fun setHome(r: YtRes) { homeC = Pair(System.currentTimeMillis(), r); saveHome(r) }

    /** بيرجّع true لو بدأ تحديث فعلًا (false = تحديث تاني شغّال) */
    fun refreshHomeAsync(hist: List<NpItem>, subs: List<NpItem>): Boolean {
        if (homeBusy) return false
        Thread {
            try { val r = home(hist, subs); onHome?.invoke(r) } catch (_: Throwable) { try { onHome?.invoke(YtRes(emptyList(), null)) } catch (_: Throwable) {} }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    /** (v351) الرئيسية: فيديوهات من اشتراكاتك (حتى القديمة) + توصيات شبه اللي بتتفرج عليه — من غير ترند خالص
     *  (v407) أسرع بكتير: أول نتيجة بتطلع من RSS (طلب صغير لكل قناة) + التوصيات، من غير ما تستنى أبطأ قناة — كان بيستنى لحد 12 ثانية (وممكن 50)
     *  لحد ما كل خيوط NewPipe التقيلة تخلص. الجلب العميق (فيديوهات قديمة عشوائية + توصيات من الاشتراكات) بيكمّل في الخلفية وبعدين بيتعرض
     *  من كبسولة «فيديوهات جديدة»؛ والنتيجة بتتحفظ في ملف عشان الفتح الجاي (حتى بعد قفل البرنامج) يبقى فوري. */
    /** (v575) دفعة إضافية للرئيسية (تلفزيون: تحميل من غير توقف): فيديوهات عشوائية من اشتراكاتك + مقترحات من فيديوهات عشوائية من سجلك/اشتراكاتك — من غير ما تلمس كاش الرئيسية */
    fun homeMore(hist: List<NpItem>, subs: List<NpItem>): YtRes {
        init()
        val seen = hist.map { it.id }.toSet()
        val out = java.util.concurrent.ConcurrentLinkedQueue<NpItem>()
        val rnd = java.util.Random()
        val jobs = ArrayList<Thread>()
        if (subs.isNotEmpty()) {
            jobs.add(Thread { try { YtRss.sample(subs, 14).filter { it.kind == 0 }.forEach { out.add(it) } } catch (_: Throwable) {} })
            jobs.add(Thread { try { randomSubVideos(subs).forEach { out.add(it) } } catch (_: Throwable) {} })
        }
        val seedPool = (hist.filter { it.kind == 0 && it.id.length == 11 }.shuffled(rnd).take(2) + subs.filter { it.kind == 0 && it.id.length == 11 }.shuffled(rnd).take(2))
        for (sd in seedPool) jobs.add(Thread { try { relFast(sd.id).forEach { if (it.kind == 0) out.add(it) } } catch (_: Throwable) {} })
        jobs.forEach { it.isDaemon = true; it.start() }
        val end = System.currentTimeMillis() + 15_000L
        for (t in jobs) try { t.join(maxOf(1L, end - System.currentTimeMillis())) } catch (_: Throwable) {}
        val uniq = LinkedHashMap<String, NpItem>()
        for (x in out.toList().shuffled(rnd)) if (x.id !in seen) uniq.putIfAbsent(x.id, x)
        if (uniq.isEmpty()) for (x in out.toList().shuffled(rnd)) uniq.putIfAbsent(x.id, x)
        return YtRes(uniq.values.toList(), null)
    }

    fun home(hist: List<NpItem>, subs: List<NpItem> = emptyList()): YtRes {
        init()
        homeBusy = true
        var tail = false
        try {
            val t0 = System.currentTimeMillis()
            val seen = hist.map { it.id }.toSet()
            val fast = java.util.concurrent.atomic.AtomicReference<List<NpItem>>(emptyList())   // RSS: سريع
            val deep = java.util.concurrent.atomic.AtomicReference<List<NpItem>>(emptyList())   // NewPipe: قديم عشوائي (بطيء)
            val fastTh = Thread {
                try { if (subs.isNotEmpty()) fast.set(YtRss.sample(subs, 14).filter { it.kind == 0 }.shuffled()) } catch (_: Throwable) {}
            }.also { it.isDaemon = true; it.start() }
            val deepTh = Thread {
                try {
                    if (subs.isNotEmpty()) {
                        var d = randomSubVideos(subs)
                        if (d.isEmpty() && fast.get().isEmpty()) d = YtRss.feed(subs, false, true).items.filter { it.kind == 0 }.shuffled()
                        deep.set(d)
                    }
                } catch (_: Throwable) {}
            }.also { it.isDaemon = true; it.start() }
            val histSeeds = hist.filter { it.kind == 0 && it.id.isNotEmpty() }.take(3)
            val results = arrayOfNulls<List<NpItem>>(histSeeds.size)
            val ths = histSeeds.mapIndexed { n, sd -> Thread { results[n] = relFast(sd.id) }.also { it.isDaemon = true; it.start() } }

            // withSeeds = true (في الخلفية بس): لو مفيش سجل نجيب توصيات من أحدث فيديوهات الاشتراكات
            fun assemble(withSeeds: Boolean): YtRes {
                val subsItems = if (deep.get().isNotEmpty()) (fast.get() + deep.get()).distinctBy { it.id } else fast.get()
                var recLists = results.map { it ?: emptyList() }
                if (withSeeds && recLists.all { it.isEmpty() } && subsItems.isNotEmpty()) {
                    val seedSubs = subsItems.take(3)
                    val r2 = arrayOfNulls<List<NpItem>>(seedSubs.size)
                    seedSubs.mapIndexed { n, sd -> Thread { r2[n] = relFast(sd.id) }.also { it.isDaemon = true; it.start() } }
                        .forEach { try { it.join(8000L) } catch (_: Throwable) {} }
                    recLists = r2.map { it ?: emptyList() }
                }
                // توصيات: تقليب بين نتايج كل فيديو للتنوّع (بدون المشاهَد)
                val rec = ArrayList<NpItem>(); var k = 0
                while (recLists.any { k < it.size }) { for (l in recLists) if (k < l.size) { val v = l[k]; if (v.id !in seen) rec.add(v) }; k++ }
                // اشتراكات: غير المشاهَد الأول (عشوائي)، وبعدها المشاهَد في الآخر
                val subsOrdered = subsItems.filter { it.id !in seen }.shuffled() + subsItems.filter { it.id in seen }.shuffled()
                // دمج: اثنين من الاشتراكات ثم اثنين توصيات … من غير تكرار
                val out = LinkedHashMap<String, NpItem>()
                var i = 0; var j = 0
                while ((i < subsOrdered.size || j < rec.size) && out.size < 90) {
                    var c = 0; while (c < 2 && i < subsOrdered.size) { subsOrdered[i].let { out.putIfAbsent(it.id, it) }; i++; c++ }
                    c = 0; while (c < 2 && j < rec.size) { rec[j].let { out.putIfAbsent(it.id, it) }; j++; c++ }
                }
                return YtRes(out.values.toList(), null)
            }

            // (v407) انتظار ذكي: نخرج أول ما الاتنين (RSS + التوصيات) يخلصوا، أو بعد 2.5 ثانية لو فيه 20 فيديو جاهزين وواحد منهم خلص، أو بعد 7 ثواني بالكتير
            val hardEnd = t0 + 7_000L
            while (System.currentTimeMillis() < hardEnd) {
                val subsDone = !fastTh.isAlive
                val recDone = ths.none { it.isAlive }
                if (subsDone && recDone) break
                if (System.currentTimeMillis() - t0 >= 2_500L && (subsDone || recDone) && assemble(false).items.size >= 20) break
                try { Thread.sleep(120L) } catch (_: Throwable) { break }
            }
            var partial = assemble(false)
            // لو لسه فاضية والجلب شغّال: كمّل استنى (لحد 30 ثانية) بدل «مفيش نتايج»
            if (partial.items.isEmpty() && (fastTh.isAlive || deepTh.isAlive || ths.any { it.isAlive })) {
                val end2 = t0 + 30_000L
                while (System.currentTimeMillis() < end2 && partial.items.isEmpty() && (fastTh.isAlive || deepTh.isAlive || ths.any { it.isAlive })) {
                    try { Thread.sleep(150L) } catch (_: Throwable) { break }
                    partial = assemble(false)
                }
            }
            if (partial.items.isEmpty() && YtAccount.loggedIn()) {
                try { val v = YtAccount.listFirst("FEsubscriptions").items.filter { it.kind == 0 }; if (v.isNotEmpty()) partial = YtRes(v, null) } catch (_: Throwable) {}
            }
            if (partial.items.isEmpty() && YtAccount.loggedIn()) {   // (v389) آخر بديل: رئيسية حسابك الرسمية
                try { val v = YtAccount.listFirst("FEwhat_to_watch").items.filter { it.kind == 0 }; if (v.isNotEmpty()) partial = YtRes(v, null) } catch (_: Throwable) {}
            }
            val stillBusy = fastTh.isAlive || deepTh.isAlive || ths.any { it.isAlive }
            LogStore.add("⏱ يوتيوب الرئيسية: " + (System.currentTimeMillis() - t0) + "ms · " + partial.items.size + " فيديو" + (if (stillBusy) " (الباقي بيكمّل في الخلفية)" else ""))
            if (partial.items.isNotEmpty()) setHome(partial)
            if (stillBusy) {
                tail = true
                Thread {
                    try {
                        val end = System.currentTimeMillis() + 60_000L
                        for (t in listOf(fastTh, deepTh) + ths) try { t.join(maxOf(1L, end - System.currentTimeMillis())) } catch (_: Throwable) {}
                        val full = assemble(true)
                        if (full.items.isNotEmpty()) { setHome(full); try { onHome?.invoke(full) } catch (_: Throwable) {} }
                    } catch (_: Throwable) {} finally { homeBusy = false }
                }.also { it.isDaemon = true; it.start() }
            }
            return partial
        } finally { if (!tail) homeBusy = false }
    }

    /** فيد الاشتراكات: آخر فيديوهات كل قناة مدموجة بالأحدث أولًا */
}

/** (v325) تخزين محلي: سجل · محفوظات · اشتراكات (في Cfg، سطر لكل عنصر) */
object YtStore {
    private const val SEP = "\u0001"
    const val HIST = "ytp_hist"; const val SAVED = "ytp_saved"; const val SUBS = "ytp_subs"
    const val DL = "ytp_dl"          // (v337) التحميلات: url = مكان الملف اللي اتحفظ
    private const val APPIDS = "ytp_appids"   // (v337) الفيديوهات اللي اتشغّلت من صفحة يوتيوب (عشان فولدر المصطادة يفصلها عن اللي من المتصفح)
    fun histOn() = Cfg.str("ytp_hist_on", "1") != "0"
    fun markApp(id: String) { if (id.isBlank()) return; val l = Cfg.str(APPIDS, "").split(',').filter { it.isNotBlank() && it != id }.toMutableList(); l.add(0, id); Cfg.put(APPIDS, l.take(600).joinToString(",")) }
    fun appIds(): Set<String> = Cfg.str(APPIDS, "").split(',').filter { it.isNotBlank() }.toHashSet()
    private fun clean(s: String) = s.replace('\n', ' ').replace(SEP, " ")

    fun read(key: String): MutableList<NpItem> = Cfg.str(key, "").lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
        val p = l.split(SEP)
        if (p.size < 7) null else NpItem(p[0], p[1], p[2], p[3], p[4], p[5].toLongOrNull() ?: -1L, 0L, "", p[6].toIntOrNull() ?: 0, p.getOrElse(7) { "" })
    }.toMutableList()

    fun write(key: String, l: List<NpItem>) {
        Cfg.put(key, l.joinToString("\n") { listOf(it.id, clean(it.title), clean(it.channel), it.channelUrl, it.thumb, it.dur.toString(), it.kind.toString(), it.url).joinToString(SEP) })
    }

    private fun same(a: NpItem, b: NpItem) = if (a.kind == 1 || b.kind == 1) a.channelUrl == b.channelUrl else a.id == b.id && a.url == b.url

    fun contains(key: String, x: NpItem) = read(key).any { same(it, x) }

    fun addFront(key: String, x: NpItem, cap: Int) {
        val l = read(key); l.removeAll { same(it, x) }; l.add(0, x)
        write(key, if (l.size > cap) l.subList(0, cap) else l)
    }
    /** بيرجّع true لو بقى موجود بعد التبديل */
    fun toggle(key: String, x: NpItem): Boolean {
        val l = read(key)
        return if (l.removeAll { same(it, x) }) { write(key, l); false } else { l.add(0, x); write(key, l); true }
    }
    fun remove(key: String, x: NpItem) { val l = read(key); if (l.removeAll { same(it, x) }) write(key, l) }
}

// ════════════════════ (v469) فريق C: مميزات يوتيوب الجديدة ════════════════════

/** (v469) C1 تخطي الرعاة (SponsorBlock): مقاطع sponsor/intro/outro/selfpromo من الـ API العام (sponsor.ajay.app) */
object YtSponsor {
    class Seg(val startMs: Long, val endMs: Long, val cat: String)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Seg>>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun on(): Boolean = Cfg.str("yt_sponsor", "1") != "0"
    fun cats(): List<String> = listOf("sponsor", "intro", "outro", "selfpromo")
    fun label(cat: String): String = when (cat) { "sponsor" -> "راعٍ"; "intro" -> "مقدمة"; "outro" -> "خاتمة"; "selfpromo" -> "ترويج ذاتي"; else -> cat }
    fun cached(id: String): List<Seg>? = cache[id]
    /** جلب في الخلفية؛ cb بتتنادى على خيط خلفي */
    fun fetch(id: String, cb: (List<Seg>) -> Unit) {
        cache[id]?.let { cb(it); return }
        if (pending.putIfAbsent(id, true) != null) return
        Thread {
            var out: List<Seg> = emptyList()
            try {
                val catsJson = cats().joinToString(",") { "%22" + it + "%22" }
                val u = "https://sponsor.ajay.app/api/skipSegments?videoID=" + id + "&categories=[" + catsJson + "]"
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "Subtitler")
                if (c.responseCode == 200) {
                    val txt = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val a = org.json.JSONArray(txt); val l = ArrayList<Seg>()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val sg = o.optJSONArray("segment") ?: continue
                        val st = (sg.optDouble(0, 0.0) * 1000).toLong(); val en = (sg.optDouble(1, 0.0) * 1000).toLong()
                        if (en > st) l.add(Seg(st, en, o.optString("category", "sponsor")))
                    }
                    out = l.sortedBy { it.startMs }
                }
                c.disconnect()
            } catch (_: Throwable) {}
            cache[id] = out; pending.remove(id)
            try { cb(out) } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }
    /** (v469) نقطة دمج المشغّل الكامل (Player.kt): نهاية المقطع المطلوب تخطيه لو الموضع جواه، وإلا -1 */
    fun skipTarget(id: String, posMs: Long): Long {
        if (!on()) return -1L
        val l = cache[id] ?: return -1L
        for (sg in l) if (posMs >= sg.startMs && posMs < sg.endMs - 300L) return sg.endMs
        return -1L
    }
}

/** (v469) C8 تقدير الديسلايك من ReturnYouTubeDislike */
object YtDislike {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun fetch(id: String, cb: (Long) -> Unit) {
        cache[id]?.let { cb(it); return }
        Thread {
            var d = -1L
            try {
                val c = URL("https://returnyoutubedislikeapi.com/votes?videoId=" + id).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                if (c.responseCode == 200) {
                    val o = org.json.JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    d = o.optLong("dislikes", -1L)
                }
                c.disconnect()
            } catch (_: Throwable) {}
            if (d >= 0) cache[id] = d
            try { cb(d) } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }
}

/** (v469) C6 تقدم مشاهدة مشغّل يوتيوب المصغّر و«أكمل المشاهدة» (بيندمج مع Recents بتاعة المشغّل الكامل) */
object YtProg {
    private const val KEY = "ytp_prog"
    private const val SEP = "\u0001"
    class E(val id: String, val posMs: Long, val durMs: Long, val title: String, val thumb: String, val ts: Long) {
        val frac: Double get() = if (durMs > 0) posMs.toDouble() / durMs else 0.0
        val done: Boolean get() = frac >= 0.92
    }
    private fun line(e: E) = listOf(e.id, e.posMs.toString(), e.durMs.toString(), e.title, e.thumb, e.ts.toString()).joinToString(SEP)
    @Synchronized fun all(): List<E> = Cfg.str(KEY, "").lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
        val p = l.split(SEP); if (p.size < 6) null else E(p[0], p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0, p[3], p[4], p[5].toLongOrNull() ?: 0)
    }.sortedByDescending { it.ts }.toList()
    @Synchronized fun get(id: String): E? = all().firstOrNull { it.id == id }
    @Synchronized fun save(id: String, posMs: Long, durMs: Long, title: String, thumb: String) {
        if (id.length != 11 || durMs <= 0) return
        val l = all().filter { it.id != id }.toMutableList()
        l.add(0, E(id, posMs, durMs, title.replace(SEP, " ").replace('\n', ' '), thumb, System.currentTimeMillis()))
        Cfg.put(KEY, l.take(200).joinToString("\n") { line(it) })
    }
    @Synchronized fun remove(id: String) { Cfg.put(KEY, all().filter { it.id != id }.joinToString("\n") { line(it) }) }
    /** موضع الاستكمال بالمللي (0 = من الأول) من مخزننا أو من سجل المشغّل الكامل */
    fun resumeMs(id: String, rec: Recent?): Long {
        val e = get(id)
        val eOk = e != null && !e.done && e.posMs > 3000L
        val rOk = rec != null && rec.durSec > 0 && (rec.posSec / rec.durSec) in 0.02..0.92 && rec.posSec > 3.0
        // (v586) الأحدث يكسب: المشغّل المصغّر بيسجّل هنا، والمشغّل الكامل (تكبير الشاشة) بيسجّل في recent.json — قبل كده القديم كان بيغلب الجديد
        if (e != null && rec != null && rec.ts > e.ts) return if (rOk) (rec.posSec * 1000).toLong() else 0L
        if (eOk) return e!!.posMs
        if (rOk) return (rec!!.posSec * 1000).toLong()
        return 0L
    }
    /** (v586) نفس resumeMs بس بتقرا recent.json بنفسها (للمشغّل المصغّر والكامل وقت الفتح من غير ما نعتمد على كاش الشاشة) */
    fun resumeNow(ctx: android.content.Context, id: String): Long {
        val rec = try { Recents.parse(java.io.File(ctx.filesDir, "recent.json").readText()).firstOrNull { it.id == "yt:" + id } } catch (_: Throwable) { null }
        return resumeMs(id, rec)
    }
    fun continueItems(recs: Map<String, *>): List<NpItem> {
        val out = LinkedHashMap<String, NpItem>()
        for (e in all()) if (!e.done && e.frac > 0.02) out[e.id] = NpItem(e.id, e.title, "", "", e.thumb, if (e.durMs > 0) e.durMs / 1000 else -1L, -1L, "", 0, YtNp.watchUrl(e.id))
        for ((k, v) in recs) {
            if (!k.startsWith("yt:")) continue
            val r = v as? Recent ?: continue
            val id = k.removePrefix("yt:"); if (id.length != 11 || out.containsKey(id)) continue
            if (r.durSec > 0 && r.posSec / r.durSec in 0.02..0.92) out[id] = NpItem(id, r.title, "", "", "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", r.durSec.toLong(), -1L, "", 0, YtNp.watchUrl(id))
        }
        return out.values.take(40)
    }
    fun completedIds(recs: Map<String, *>): Set<String> {
        val s = HashSet<String>()
        for (e in all()) if (e.done) s.add(e.id)
        for ((k, v) in recs) { val r = v as? Recent ?: continue; if (r.durSec > 0 && r.posSec / r.durSec >= 0.92) s.add(k.removePrefix("yt:")) }
        return s
    }
}

/** (v469) C2 كاش معرّفات UC لقنوات @handle — عشان فيد RSS يشتغل مع أي رابط قناة مش بس /channel/UC… */
object YtChanId {
    private const val KEY = "ytp_cids"
    @Synchronized fun cachedOnly(url: String): String? {
        for (l in Cfg.str(KEY, "").lineSequence()) { val i = l.indexOf('='); if (i > 0 && l.substring(0, i) == url) return l.substring(i + 1) }
        return null
    }
    @Synchronized fun put(url: String, cid: String) {
        if (url.isBlank() || !cid.startsWith("UC")) return
        val m = LinkedHashMap<String, String>()
        for (l in Cfg.str(KEY, "").lineSequence()) { val i = l.indexOf('='); if (i > 0) m[l.substring(0, i)] = l.substring(i + 1) }
        m[url] = cid
        Cfg.put(KEY, m.entries.toList().takeLast(300).joinToString("\n") { it.key + "=" + it.value })
    }
    /** حلّ معرّف القناة في الخلفية (من صفحة القناة أو NewPipe) وتخزينه */
    fun resolve(url: String, cb: (String?) -> Unit = {}) {
        YtRss.chanId(url)?.let { cb(it); return }
        Thread {
            var cid: String? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                val html = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                c.disconnect()
                cid = Regex("\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.groupValues?.get(1)
                    ?: Regex("/channel/(UC[A-Za-z0-9_-]{22})").find(html)?.groupValues?.get(1)
            } catch (_: Throwable) {}
            if (cid == null) { try { cid = YtRss.chanId(YtNp.channelFull(url).url) } catch (_: Throwable) {} }
            if (cid != null) put(url, cid!!)
            try { cb(cid) } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }
}

/** (v469) C3 تنزيلات DownloadManager: ملفات التطبيق (Downloads/Subtitler) + سجل وحالة حية من النظام */
object YtDl {
    class Rec(val dmId: Long, val vid: String, val title: String, val audio: Boolean, val q: String, val fname: String, var reg: Boolean = false)
    private const val KEY = "ytp_dm"
    private const val SEP = "\u0001"
    private fun line(r: Rec) = listOf(r.dmId.toString(), r.vid, r.title, if (r.audio) "1" else "0", r.q, r.fname, if (r.reg) "1" else "0").joinToString(SEP)
    @Synchronized fun recs(): MutableList<Rec> = Cfg.str(KEY, "").lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
        val p = l.split(SEP); if (p.size < 6) null else Rec(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3] == "1", p[4], p[5], p.getOrElse(6) { "0" } == "1")
    }.toMutableList()
    @Synchronized private fun write(l: List<Rec>) { Cfg.put(KEY, l.take(200).joinToString("\n") { line(it) }) }
    private fun cleanName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "video" }
    /** بيبدأ التنزيل ويرجّع معرّف DownloadManager (أو -1). بتتنادى من أي خيط */
    fun enqueue(ctx: android.content.Context, v: NpItem, url: String, ua: String, audio: Boolean, qLabel: String): Long {
        return try {
            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val fn = cleanName(v.title.ifBlank { v.id }) + if (audio) ".m4a" else ".mp4"
            val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                .setTitle(v.title.ifBlank { v.id })
                .setDescription(if (audio) "صوت يوتيوب" else "فيديو يوتيوب " + qLabel)
                .addRequestHeader("User-Agent", ua)
                .addRequestHeader("Referer", "https://www.youtube.com/")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, android.os.Environment.DIRECTORY_DOWNLOADS, "Subtitler/" + fn)
                .setAllowedOverMetered(true)
            val id = dm.enqueue(req)
            val l = recs(); l.add(0, Rec(id, v.id, v.title.replace(SEP, " ").replace('\n', ' '), audio, qLabel, fn)); write(l)
            id
        } catch (e: Throwable) { LogStore.err("YtDl:enqueue", e); -1L }
    }
    /** (نص الحالة، النسبة) — واللي خلص بيتسجّل في قايمة التحميلات مرة واحدة */
    fun status(ctx: android.content.Context, r: Rec): Pair<String, Int> {
        return try {
            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val cur = dm.query(android.app.DownloadManager.Query().setFilterById(r.dmId))
            var out = Pair("؟ مش موجود في النظام", -1)
            if (cur != null && cur.moveToFirst()) {
                val st = cur.getInt(cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                val done = cur.getLong(cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val tot = cur.getLong(cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val pct = if (tot > 0) (done * 100 / tot).toInt() else -1
                out = when (st) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = cur.getString(cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_LOCAL_URI)) ?: ""
                        if (!r.reg) {
                            val l = recs(); l.firstOrNull { it.dmId == r.dmId }?.let { it.reg = true; r.reg = true; write(l) }
                            try { YtStore.addFront(YtStore.DL, NpItem(r.vid, r.title, "", "", "https://i.ytimg.com/vi/" + r.vid + "/mqdefault.jpg", -1L, -1L, "⬇ " + (if (r.audio) "صوت" else "فيديو " + r.q) + " · " + uri, 0, uri), 300) } catch (_: Throwable) {}
                        }
                        Pair("✅ اتحمّل", 100)
                    }
                    android.app.DownloadManager.STATUS_FAILED -> Pair("⚠ فشل التنزيل", pct)
                    android.app.DownloadManager.STATUS_PAUSED -> Pair("⏸ متوقف · " + pct + "%", pct)
                    android.app.DownloadManager.STATUS_RUNNING -> Pair("⬇ بيتحمّل · " + pct + "%", pct)
                    else -> Pair("⏳ في الانتظار", pct)
                }
            }
            cur?.close()
            out
        } catch (e: Throwable) { Pair("⚠ " + (e.message ?: ""), -1) }
    }
    @Synchronized fun forget(dmId: Long) { write(recs().filter { it.dmId != dmId }) }
}
