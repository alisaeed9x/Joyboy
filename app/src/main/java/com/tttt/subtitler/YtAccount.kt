package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * (v1104) Y4 — حساب يوتيوب بتوكن كود التفعيل (OAuth2 device flow، من محرك MeeTube/MediaServiceCore) بدل كوكيز WebView + QR.
 *  • الدخول: YtCodeLogin (كود + youtube.com/activate). مفيش WebView جوجل، ومفيش كوكيز، ومفيش QR/نقل جلسة للتلفزيون
 *    (التلفزيون بيدخل بالكود مباشرة — ده بالظبط اللي الـdevice flow معمول له).
 *  • القوايم (قنواتي · السجل · الإعجاب · شاهد لاحقًا · قوايمي · الاشتراكات): contentService + continueGroup.
 *  • الأفعال (اشتراك · لايك · تعليق): mediaItemService / commentsService.
 *  • الجلسات القديمة (كوكيز) بتتمسح أول ما البرنامج يفتح — لازم تدخل بالكود مرة واحدة.
 *  • اللي فاضل من InnerTube الخام: relatedLite (مقترحات من غير حساب) + محلل الردود (للشورتس وللمسارات الاحتياطية القديمة) — من غير أي توقيع حساب.
 * كل دوال الشبكة بتتنده من خيط خلفي. لم يتم compile.
 */
object YtAccount {
    private const val ORIGIN = "https://www.youtube.com"
    private const val API_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val SYNC_EVERY_MS = 6 * 60 * 60 * 1000L

    @Volatile private var sp: SharedPreferences? = null
    @Volatile private var dir: File? = null

    fun init(c: Context) {
        if (sp == null) {
            val a = c.applicationContext; sp = a.getSharedPreferences("yt_acc", 0); dir = a.filesDir
            // (v1104 Y4) كوكيز الدخول القديم (WebView) مبقاش ليها استخدام — بنمسحها
            try { if (sp?.contains("cookie") == true) { sp?.edit()?.remove("cookie")?.remove("login_at")?.apply(); LogStore.add("🍪 اتمسحت جلسة الكوكيز القديمة — ادخل بالكود (🔐)") } } catch (_: Throwable) {}
        }
    }

    // ───────── الحالة ─────────
    @Volatile private var sAt = 0L
    @Volatile private var sVal = false
    /** مسجّل دخول بالكود؟ (كاش 20 ثانية؛ أول مرة بتشغّل المحرك) */
    fun loggedIn(): Boolean {
        val now = System.currentTimeMillis()
        if (now - sAt < 20_000L) return sVal
        val v = try { MtYt.signedIn() } catch (_: Throwable) { false }
        sVal = v; sAt = now
        return v
    }
    private fun refreshState() { sAt = 0L }

    /** (v574) callback بيتنادى (من thread خلفي) لما مزامنة الاشتراكات تخلص — لانشر التلفزيون بيحدّث صف يوتيوب بيه */
    @Volatile var onSynced: (() -> Unit)? = null
    fun syncDue(every: Long = SYNC_EVERY_MS): Boolean = System.currentTimeMillis() - (sp?.getLong("sync_at", 0L) ?: 0L) > every
    @Volatile private var syncing = false
    /** (v389) مزامنة في الخلفية (نفس زرار «🔄 مزامنة الاشتراكات») — مرة واحدة في نفس الوقت */
    fun syncAsync(force: Boolean = false, onDone: (Int) -> Unit = {}) {
        if (syncing || !loggedIn()) return
        if (!force && System.currentTimeMillis() - lastSyncAt() < 60_000L) return   // مش أكتر من مرة في الدقيقة
        syncing = true
        Thread {
            try { val n = syncSubs(); LogStore.add("🔄 مزامنة تلقائية للاشتراكات: +$n قناة"); onDone(n); try { onSynced?.invoke() } catch (_: Throwable) { } }
            catch (e: Throwable) { LogStore.add("⚠ المزامنة التلقائية فشلت: " + (e.message ?: e.javaClass.simpleName).take(120)) }
            finally { syncing = false }
        }.also { it.isDaemon = true; it.start() }
    }
    fun lastSyncAt(): Long = sp?.getLong("sync_at", 0L) ?: 0L
    fun chanCount(): Int = sp?.getInt("chan_n", 0) ?: 0

    // ───────── تسجيل الدخول / الخروج (بالكود) ─────────
    fun login(act: Activity, ui: Ui, th: Theme, onDone: (Boolean) -> Unit) {
        init(act)
        YtCodeLogin.show(act, ui, th) { ok ->
            refreshState(); YtMtFeed.reset()
            if (ok) { try { sp?.edit()?.putLong("sync_at", 0L)?.apply() } catch (_: Throwable) {} }
            onDone(ok)
        }
    }

    fun logout() {
        try { YtCodeLogin.logout() } catch (_: Throwable) {}
        try { sp?.edit()?.clear()?.apply() } catch (_: Throwable) {}
        refreshState(); YtMtFeed.reset(); YtMtFeed.dropHomeSnap(); plMap.clear()
        try { dir?.let { d -> File(d, "yt_snap_1.json").delete(); File(d, "yt_feed.json").delete() } } catch (_: Throwable) {}
    }

    /** (v471) كان بيرجّع هيدرز الكوكيز لـNewPipe — دلوقتي مفيش كوكيز: NewPipe بيشتغل من غير حساب (الحساب كله على المحرك) */
    fun authHeaders(): Map<String, String>? = null

    private fun ver(): String = Cfg.str("yt_client_ver", "2.20260601.01.00")

    private fun ctx(): JSONObject = JSONObject().put("client",
        JSONObject().put("clientName", "WEB").put("clientVersion", ver()).put("hl", YtLocale.hl()).put("gl", YtLocale.gl()))   // (v471) اللغة من YtLocale


    /** (v471) طلب InnerTube خفيف: بيشتغل من غير تسجيل دخول، ولو مسجّل بيتبعت بحسابك (لغة وترتيب حسابك). مهلة قصيرة عشان الرئيسية ما تتعطّلش */
    private fun postLite(path: String, body: JSONObject): JSONObject {
        val c = URL("$ORIGIN/youtubei/v1/$path?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 12000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", API_UA)
            c.setRequestProperty("Origin", ORIGIN)
            c.setRequestProperty("X-Youtube-Client-Name", "1"); c.setRequestProperty("X-Youtube-Client-Version", ver())
            c.setRequestProperty("Accept-Language", YtLocale.acceptLanguage())
            c.setRequestProperty("Accept-Encoding", "gzip")
            val ah = authHeaders()
            if (ah != null) for ((k, v) in ah) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            var ins = if (code >= 400) c.errorStream else c.inputStream
            if (ins != null && "gzip".equals(c.contentEncoding, true)) ins = GZIPInputStream(ins)
            val txt = ins?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code >= 400) throw RuntimeException("يوتيوب رد بخطأ $code")
            return JSONObject(txt)
        } finally { c.disconnect() }
    }


    /** (v471) فيديوهات مقترحة لفيديو من ردّ next الخفيف (بدل StreamInfo الكامل) */
    fun relatedLite(videoId: String): List<NpItem> {
        val j = postLite("next", JSONObject().put("context", ctx()).put("videoId", videoId))
        return parse(j).items.filter { it.kind == 0 && it.id != videoId && it.dur !in 1L..60L }
    }



    // ───────── قوايم الحساب (من المحرك) ─────────
    private val kept = ConcurrentHashMap<String, MediaGroup>()
    private val keptOrder = ArrayList<String>()
    private var kseq = 0
    private val plMap = ConcurrentHashMap<String, MediaItem>()

    private fun keep(g: MediaGroup?): Page? {
        if (g == null || g.nextPageKey == null) return null
        val k = "yta:m" + (++kseq)
        kept[k] = g
        synchronized(keptOrder) { keptOrder.add(k); while (keptOrder.size > 24) kept.remove(keptOrder.removeAt(0)) }
        return Page(k)
    }

    /** MediaItem → NpItem: فيديو (0) · قناة (1) · قايمة تشغيل (2) */
    private fun np(m: MediaItem): NpItem? {
        val vid = m.videoId
        if (!vid.isNullOrEmpty() && vid.length == 11) return MtYt.toItem(m)
        val pid = m.playlistId
        if (!pid.isNullOrEmpty()) {
            plMap[pid] = m
            return NpItem("", m.title ?: "", m.secondTitle?.toString() ?: "", "", m.cardImageUrl ?: "", -1L, -1L, "", 2, "yta:VL$pid")
        }
        val cid = m.channelId
        if (!cid.isNullOrEmpty() && cid.startsWith("UC")) {
            val u = "https://www.youtube.com/channel/$cid"
            return NpItem("", m.title ?: "", "", u, m.cardImageUrl ?: "", -1L, -1L, "", 1, u)
        }
        return null
    }

    private fun resOf(g: MediaGroup?): YtRes {
        if (g == null) return YtRes(emptyList(), null)
        val out = ArrayList<NpItem>(); val seen = HashSet<String>()
        for (m in g.mediaItems ?: emptyList()) { val x = np(m) ?: continue; if (seen.add(x.kind.toString() + x.id + x.url)) out.add(x) }
        return YtRes(out, keep(g))
    }

    /** MediaItem بسيط (Proxy) من معرّف قايمة — عشان getGroup(item) تفتح قايمة (إعجاب LL / شاهد لاحقًا WL / أي قايمة) */
    private fun playlistItem(pid: String, title: String): MediaItem =
        java.lang.reflect.Proxy.newProxyInstance(MediaItem::class.java.classLoader, arrayOf<Class<*>>(MediaItem::class.java)) { _, mt, _ ->
            when (mt.name) {
                "getPlaylistId" -> pid
                "getTitle" -> title
                else -> when (mt.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as MediaItem

    private fun videoItem(id: String): MediaItem =
        java.lang.reflect.Proxy.newProxyInstance(MediaItem::class.java.classLoader, arrayOf<Class<*>>(MediaItem::class.java)) { _, mt, _ ->
            when (mt.name) {
                "getVideoId" -> id
                else -> when (mt.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as MediaItem

    private fun cs() = run { MtYt.ensureBoot(); YouTubeServiceManager.instance().contentService }

    // ───────── واجهة للشاشة (نفس الأسماء القديمة) ─────────
    /** المفاتيح: FEchannels · FEhistory · FEsubscriptions · FEwhat_to_watch · FEplaylist_aggregation · VLLL (إعجاب) · VLWL (شاهد لاحقًا) · VL<id> (قايمة) */
    fun listFirst(browseId: String): YtRes {
        if (!loggedIn()) throw RuntimeException("مش مسجّل الدخول — سجّل بالكود الأول (🔐)")
        val g: MediaGroup? = when {
            browseId == "FEchannels" -> cs().getSubscribedChannels()
            browseId == "FEhistory" -> cs().getHistory()
            browseId == "FEsubscriptions" -> cs().getSubscriptions()
            browseId == "FEwhat_to_watch" -> return YtMtFeed.home(emptyList()) ?: YtRes(emptyList(), null)
            browseId == "FEplaylist_aggregation" -> {
                MtYt.ensureBoot()
                YouTubeServiceManager.instance().contentService.getPlaylistsObserve().blockingFirst()
            }
            browseId == "VLLL" -> cs().getGroup(plMap["LL"] ?: playlistItem("LL", "فيديوهات الإعجاب"))
            browseId == "VLWL" -> cs().getGroup(plMap["WL"] ?: playlistItem("WL", "شاهد لاحقًا"))
            browseId.startsWith("VL") -> { val pid = browseId.substring(2); cs().getGroup(plMap[pid] ?: playlistItem(pid, "")) }
            else -> null
        }
        return resOf(g)
    }
    fun listMore(p: Page): YtRes {
        val g = kept[p.url ?: ""] ?: return YtRes(emptyList(), null)
        return resOf(cs().continueGroup(g))
    }

    private fun row(title: String, sub: String, key: String) = NpItem("act", title, sub, "", "", -1L, -1L, "", 2, "yta:$key")
    fun menuItems(): List<NpItem> = listOf(
        row("📺 قنواتي", "كل القنوات اللي مشترك فيها (${chanCount()})", "FEchannels"),
        row("🕘 سجل مشاهدتي في يوتيوب", "اللي شفته على حسابك", "FEhistory"),
        row("👍 فيديوهات الإعجاب", "اللي عملتلها لايك", "VLLL"),
        row("⏰ شاهد لاحقًا", "قايمة شاهد لاحقًا بتاعتك", "VLWL"),
        row("📃 قوايم التشغيل بتاعتي", "القوايم اللي عملتها أو حفظتها", "FEplaylist_aggregation"),
        row("🔄 مزامنة الاشتراكات دلوقتي", syncLabel(), "!sync"),
        row("🚪 تسجيل الخروج", "بيشيل حساب الدخول من التطبيق", "!out"))

    private fun syncLabel(): String {
        val t = lastSyncAt(); if (t <= 0L) return "لسه ما اتزامنتش"
        val m = (System.currentTimeMillis() - t) / 60000
        return "آخر مزامنة: " + (if (m < 1) "دلوقتي" else if (m < 60) "من $m دقيقة" else if (m < 1440) "من ${m / 60} ساعة" else "من ${m / 1440} يوم")
    }

    private fun sameChan(a: String, b: String): Boolean { val x = YtRss.chanId(a); val y = YtRss.chanId(b); return if (x != null && y != null) x == y else a == b }

    /** بيجيب كل قنوات حسابك ويضيف الجديد منها على اشتراكاتك المحلية (مابيمسحش أي حاجة). بيرجّع عدد القنوات الجديدة */
    fun syncSubs(): Int {
        if (!loggedIn()) throw RuntimeException("مش مسجّل الدخول — سجّل بالكود الأول (🔐)")
        val chans = ArrayList<NpItem>()
        var r = resOf(cs().getSubscribedChannels())
        chans.addAll(r.items.filter { it.kind == 1 })
        var nx = r.next; var pages = 0
        while (nx != null && pages++ < 40) { r = listMore(nx); chans.addAll(r.items.filter { it.kind == 1 }); nx = r.next }
        if (chans.isEmpty()) throw RuntimeException("ما لقيتش قنوات في حسابك — لو متأكد إن عندك اشتراكات: اخرج وادخل بالكود تاني")
        val subs = YtStore.read(YtStore.SUBS); var added = 0
        for (c in chans) if (subs.none { sameChan(it.channelUrl, c.channelUrl) }) { subs.add(c); added++ }
        YtStore.write(YtStore.SUBS, subs)
        sp?.edit()?.putLong("sync_at", System.currentTimeMillis())?.putInt("chan_n", chans.size)?.apply()
        return added
    }

    // ───────── أفعال على حسابك: اشتراك · لايك · تعليق ─────────
    private fun needLogin() { if (!loggedIn()) throw RuntimeException("مش مسجّل الدخول — سجّل بالكود الأول (🔐)") }

    fun setSubscribed(chanUrl: String, on: Boolean) {
        needLogin()
        val id = YtRss.chanId(chanUrl) ?: throw RuntimeException("مش قادر أعرف معرّف القناة")
        val s = MtYt.itemSvc()
        if (on) s.subscribe(id) else s.unsubscribe(id)
    }
    fun setLiked(videoId: String, on: Boolean) {
        needLogin()
        val s = MtYt.itemSvc(); val it = videoItem(videoId)
        if (on) s.setLike(it) else s.removeLike(it)
    }
    /** بينشر تعليق على الفيديو */
    fun postComment(videoId: String, text: String) {
        needLogin()
        MtYt.ensureBoot()
        YouTubeServiceManager.instance().commentsService.createCommentObserve(videoId, text).blockingFirst()
    }

    // ───────── (v1298) تعليقات MeeTube: تحميل + ترتيب + لايك + رد ─────────
    private fun commentSvc() = run { MtYt.ensureBoot(); YouTubeServiceManager.instance().commentsService }

    /** مفاتيح التعليقات (الأعلى/الأحدث) + العدد المختصر من ميتا الفيديو */
    fun commentKeys(videoId: String): Triple<String?, String?, String?> {
        val md = try { MtYt.itemSvc().getMetadata(videoId) } catch (_: Throwable) { null } ?: return Triple(null, null, null)
        return Triple(md.commentsKey, try { md.newestCommentsKey } catch (_: Throwable) { null }, try { md.commentsCount } catch (_: Throwable) { null })
    }

    class MtComments(val items: List<NpComment>, val nextKey: String?)

    private fun mtNp(c: CommentItem): NpComment {
        val likes = (c.likeCount ?: "").filter { it.isDigit() }.toLongOrNull() ?: 0L
        val rc = (c.replyCount ?: "").filter { it.isDigit() }.toIntOrNull() ?: 0
        return NpComment(c.message ?: "", c.authorName ?: "", c.authorPhoto ?: "", likes, c.publishedDate ?: "", c.pinnedLabel != null, false, false, rc, null, c.isLiked, c.nestedCommentsKey, c.id)
    }

    /** صفحة تعليقات (أو ردود لو المفتاح مفتاح ردود) — blocking، اتنادى من خيط خلفية */
    fun mtComments(key: String): MtComments {
        val g = commentSvc().getCommentsObserve(key).blockingFirst()
        val list = (g.comments ?: emptyList()).filter { !it.message.isNullOrBlank() }.map { mtNp(it) }
        return MtComments(list, g.nextCommentsKey)
    }

    /** لايك/شيل لايك على تعليق (المفتاح هو مفتاح الردود بتاعه زي ما MeeTube بيعمل) */
    fun likeComment(nestedKey: String) { needLogin(); commentSvc().toggleLikeObserve(nestedKey).blockingFirst() }

    /** رد على تعليق */
    fun postReply(videoId: String, parentId: String, text: String) {
        needLogin(); MtYt.ensureBoot()
        YouTubeServiceManager.instance().commentsService.createReplyObserve(videoId, parentId, text).blockingFirst()
    }

    // ───────── قراءة الرد (ماشي عام على الـJSON) ─────────
    private fun walk(n: Any?, f: (String, JSONObject) -> Unit) {
        when (n) {
            is JSONObject -> { val ks = n.keys(); while (ks.hasNext()) { val k = ks.next(); val v = n.opt(k); if (v is JSONObject) f(k, v); walk(v, f) } }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), f)
        }
    }
    private fun dig(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '\u0660'..'\u0669') ('0' + (ch - '\u0660')) else if (ch in '\u06F0'..'\u06F9') ('0' + (ch - '\u06F0')) else ch)
        return sb.toString()
    }
    private fun txt(o: JSONObject?): String {
        if (o == null) return ""
        val s = o.optString("simpleText"); if (s.isNotEmpty()) return s
        val c = o.optString("content"); if (c.isNotEmpty()) return c
        val r = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder(); for (i in 0 until r.length()) sb.append(r.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString()
    }
    private fun secs(s0: String): Long {
        val s = dig(s0).trim(); if (s.isEmpty() || !s.contains(':')) return -1L
        var t = 0L
        for (p in s.split(":")) { val n = p.filter { it in '0'..'9' }.toLongOrNull() ?: return -1L; t = t * 60 + n }
        return t
    }
    private fun fixUrl(u: String): String = if (u.startsWith("//")) "https:$u" else u
    private fun lastThumb(o: JSONObject?): String {
        val a = o?.optJSONArray("thumbnails") ?: return ""
        return if (a.length() == 0) "" else fixUrl(a.optJSONObject(a.length() - 1)?.optString("url") ?: "")
    }
    private fun chanUrlOf(o: JSONObject?): String {
        val id = o?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
        return if (id.startsWith("UC")) "https://www.youtube.com/channel/$id" else ""
    }
    private fun vid(id: String, title: String, ch: String, chUrl: String, dur: Long, age: String) =
        NpItem(id, title, ch, chUrl, "https://i.ytimg.com/vi/$id/mqdefault.jpg", dur, -1L, age, 0, YtNp.watchUrl(id))

    /** (v863) معرّفات الشورتس اللي اتشافت في آخر تحليل (الفيد الرسمي بيطلّعها كفيديو عادي لو مدتها فوق 60 ثانية) */
    val shorts: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun isShortRenderer(o: JSONObject): Boolean {
        if (o.optJSONObject("navigationEndpoint")?.has("reelWatchEndpoint") == true) return true
        val ov = o.optJSONArray("thumbnailOverlays") ?: return false
        for (i in 0 until ov.length()) if (ov.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.optString("style") == "SHORTS") return true
        return false
    }
    private fun video(o: JSONObject): NpItem? {
        val id = o.optString("videoId"); if (id.length != 11) return null
        if (isShortRenderer(o)) shorts.add(id)
        val by = o.optJSONObject("ownerText") ?: o.optJSONObject("longBylineText") ?: o.optJSONObject("shortBylineText")
        var dur = secs(txt(o.optJSONObject("lengthText")))
        if (dur < 0) dur = o.optString("lengthSeconds").toLongOrNull() ?: -1L
        return vid(id, txt(o.optJSONObject("title")), txt(by), chanUrlOf(by), dur, txt(o.optJSONObject("publishedTimeText")))
    }
    private fun chan(o: JSONObject): NpItem? {
        val id = o.optString("channelId"); if (!id.startsWith("UC")) return null
        val u = "https://www.youtube.com/channel/$id"
        return NpItem("", txt(o.optJSONObject("title")), "", u, lastThumb(o.optJSONObject("thumbnail")), -1L, -1L, "", 1, u)
    }
    private fun plist(o: JSONObject): NpItem? {
        val pid = o.optString("playlistId"); if (pid.isEmpty()) return null
        val th0 = lastThumb(o.optJSONArray("thumbnails")?.optJSONObject(0)).ifEmpty { lastThumb(o.optJSONObject("thumbnail")) }
        val cnt = dig(o.optString("videoCount")).filter { it in '0'..'9' }.toLongOrNull() ?: -1L
        return NpItem("", txt(o.optJSONObject("title")), txt(o.optJSONObject("longBylineText")), "", th0, -1L, cnt, "", 2, "yta:VL$pid")
    }
    /** الشكل الجديد (lockupViewModel) بتاع يوتيوب لقوايم السجل والتشغيل */
    private fun lockup(o: JSONObject): NpItem? {
        val id = o.optString("contentId"); if (id.isEmpty()) return null
        val md = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = md?.optJSONObject("title")?.optString("content") ?: ""
        val parts = ArrayList<String>()
        walk(md?.optJSONObject("metadata")) { k, p -> if (k == "text") { val t = p.optString("content"); if (t.isNotEmpty()) parts.add(t) } }
        val type = o.optString("contentType")
        if (type.contains("CHANNEL")) {   // (v350) القنوات بالشكل الجديد
            if (!id.startsWith("UC")) return null
            val u = "https://www.youtube.com/channel/$id"
            return NpItem("", title, "", u, "", -1L, -1L, "", 1, u)
        }
        if (type.contains("PLAYLIST")) return NpItem("", title, parts.firstOrNull() ?: "", "", "", -1L, -1L, "", 2, "yta:VL$id")
        if (type.contains("SHORT") && id.length == 11) { shorts.add(id); return vid(id, title, parts.firstOrNull() ?: "", "", -1L, "") }
        if (!type.contains("VIDEO") || id.length != 11) return null
        var dur = -1L
        walk(o.optJSONObject("contentImage")) { k, p -> if (k == "thumbnailBadgeViewModel" && dur < 0) dur = secs(p.optString("text")) }
        return vid(id, title, parts.firstOrNull() ?: "", "", dur, parts.drop(1).joinToString(" · "))
    }

    /** (v864) شورتس من الفيد: reelItemRenderer (القديم) / shortsLockupViewModel (الجديد) */
    private fun reel(o: JSONObject): NpItem? {
        val id = o.optString("videoId"); if (id.length != 11) return null
        shorts.add(id)
        return vid(id, txt(o.optJSONObject("headline")), "", "", -1L, "")
    }
    private fun shortLockup(o: JSONObject): NpItem? {
        var id = o.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("reelWatchEndpoint")?.optString("videoId") ?: ""
        if (id.length != 11) id = o.optString("entityId").substringAfterLast('-')
        if (id.length != 11) return null
        shorts.add(id)
        return vid(id, txt(o.optJSONObject("overlayMetadata")?.optJSONObject("primaryText")), "", "", -1L, "")
    }
    /** (v864) بوست (community post) من الفيد: kind = 4، والدوسة بتفتحه في المتصفح */
    private fun post(o: JSONObject): NpItem? {
        val pid = o.optString("postId"); if (pid.isEmpty()) return null
        val text = txt(o.optJSONObject("contentText")).trim()
        val by = o.optJSONObject("authorText")
        val chId = o.optJSONObject("authorEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
        val chUrl = if (chId.startsWith("UC")) "https://www.youtube.com/channel/$chId" else ""
        val thumb = lastThumb(o.optJSONObject("backstageAttachment")?.optJSONObject("backstageImageRenderer")?.optJSONObject("image"))
        return NpItem(pid, (if (text.isEmpty()) "📝 بوست" else text).take(400), txt(by), chUrl, thumb, -1L, -1L,
            txt(o.optJSONObject("publishedTimeText")), 4, "https://www.youtube.com/post/$pid", 0L, lastThumb(o.optJSONObject("authorThumbnail")))
    }

    class Parsed(val items: List<NpItem>, val next: String?)
    private fun parse(j: JSONObject): Parsed {
        val out = ArrayList<NpItem>(); val seen = HashSet<String>()
        var tok: String? = null
        walk(j) { k, o ->
            val x: NpItem? = when (k) {
                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" -> video(o)
                "channelRenderer", "gridChannelRenderer" -> chan(o)
                "playlistRenderer", "gridPlaylistRenderer" -> plist(o)
                "lockupViewModel" -> lockup(o)
                "reelItemRenderer" -> reel(o)
                "shortsLockupViewModel" -> shortLockup(o)
                "backstagePostRenderer" -> post(o)
                else -> null
            }
            if (x != null && seen.add(x.kind.toString() + x.id + x.url)) out.add(x)
            if (k == "continuationItemRenderer" && tok == null) {
                val t = o.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token") ?: ""
                if (t.isNotEmpty()) tok = t
            }
        }
        return Parsed(out, tok)
    }
}
