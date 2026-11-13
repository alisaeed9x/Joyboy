package com.tttt.subtitler

import android.app.Application
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import com.newtube.mobile.MobileMainApplication
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * (v889) جلب روابط فيديو/صوت يوتيوب من محرك MeeTube نفسه (MediaServiceCore) — بدل طبقة الاستخراج القديمة بتاعت Joyboy
 * (YtNp/YtFast/YtExtract-clients اتمسحوا). بيتنده من خيط خلفي بس (الطلب متزامن).
 * بيطلّع نفس الأشكال اللي المشغّل والموسيقى والتحميل متعودين عليها: Got (url + صوت منفصل + قايمة الجودات JSON) و YtPick.
 */
object MtYt {
    /** بيتحط من JoyApp.onCreate */
    @Volatile var app: Application? = null

    /** نتيجة جاهزة للمشغّل: url (+ صوت منفصل لو فيه) + قايمة الجودات JSON */
    class Got(val url: String, val audio: String?, val ua: String, val title: String, val qlist: String, val by: String)

    private const val TTL = 90L * 60 * 1000L
    private val cache = ConcurrentHashMap<String, Pair<Long, MediaItemFormatInfo>>()
    private val warming = ConcurrentHashMap<String, Long>()
    @Volatile var lastWhy: String = ""

    fun clearCache() { cache.clear() }

    private fun boot() { app?.let { MobileMainApplication.boot(it) } }

    private fun service() = run { boot(); YouTubeServiceManager.instance().mediaItemService }

    /** معلومات الفورمات للفيديو (كاش 90 دقيقة). null لو فشل — والسبب في lastWhy. */
    fun info(id: String, fresh: Boolean = false): MediaItemFormatInfo? {
        if (!fresh) cache[id]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.let { return it.second }
        val t0 = System.currentTimeMillis()
        return try {
            val fi = service().getFormatInfo(id)
            if (fi == null) { lastWhy = "MeeTube مرجّعش معلومات للفيديو"; return null }
            if (fi.isUnplayable) { lastWhy = fi.playabilityReason ?: "الفيديو مش متاح"; return null }
            cache[id] = Pair(System.currentTimeMillis(), fi)
            lastWhy = ""
            LogStore.add("⏱ MeeTube $id: " + (System.currentTimeMillis() - t0) + "ms")
            fi
        } catch (e: Throwable) {
            lastWhy = e.javaClass.simpleName + ": " + (e.message ?: "")
            LogStore.err("MtYt:info", e); null
        }
    }

    /** تسخين: أول ما صباعك يلمس فيديو نبدأ نجيب معلوماته في الخلفية */
    fun warm(id: String) {
        if (id.length != 11 || cache.containsKey(id)) return
        val now = System.currentTimeMillis()
        val prev = warming[id]
        if (prev != null && now - prev < 60_000L) return
        warming[id] = now
        if (warming.size > 40) warming.entries.removeAll { now - it.value > 120_000L }
        Thread { try { info(id) } catch (_: Throwable) {} }.apply { isDaemon = true; start() }
    }

    fun warmOnTouch(v: android.view.View, id: () -> String) {
        v.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) warm(id()); false }
    }

    private val CHROME = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun uaFor(fi: MediaItemFormatInfo): String {
        val ci = try { fi.clientInfo } catch (_: Throwable) { null } ?: return CHROME
        val n = ci.clientName ?: ""; val v = ci.clientVersion ?: ""
        val os = ci.osVersion ?: ""
        return when {
            n.startsWith("ANDROID_VR") -> "com.google.android.apps.youtube.vr.oculus/$v (Linux; U; Android ${os.ifBlank { "12L" }}; eureka-user Build/SQ3A.220605.009.A1) gzip"
            n.startsWith("ANDROID") -> "com.google.android.youtube/$v (Linux; U; Android ${os.ifBlank { "14" }}) gzip"
            n.startsWith("IOS") -> "com.google.ios.youtube/$v (iPhone16,2; U; CPU iOS ${os.replace('.', '_').ifBlank { "17_5" }} like Mac OS X;)"
            else -> CHROME
        }
    }

    private fun hasUrl(f: MediaFormat) = !f.url.isNullOrEmpty()
    private fun isMp4(f: MediaFormat) = (f.mimeType ?: "").contains("mp4", true)
    private fun bitrate(f: MediaFormat) = f.bitrate?.toLongOrNull() ?: 0L

    /** أحسن صوت: m4a أولًا (يشتغل في كل مكان) وأعلى بتريت، ومن مسار الصوت الأصلي لو الفيديو متعدد اللغات */
    private fun bestAudio(fs: List<MediaFormat>): MediaFormat? {
        val au = fs.filter { (it.mimeType ?: "").startsWith("audio/") && hasUrl(it) && !it.isDrc }
        if (au.isEmpty()) return null
        val orig = au.filter { val t = it.audioTrackId ?: ""; t.isEmpty() || t.contains("original", true) || t.endsWith(".4") }
        val pool = if (orig.isNotEmpty()) orig else au
        val pm = pool.filter { isMp4(it) }.ifEmpty { pool }
        return pm.maxByOrNull { bitrate(it) }
    }

    private class Q(val label: String, val url: String, val audio: String?, val h: Int)

    private fun build(fi: MediaItemFormatInfo, maxH: Int): Pair<List<Q>, String?> {
        val adaptive = try { fi.adaptiveFormats ?: emptyList() } catch (_: Throwable) { emptyList<MediaFormat>() }
        val muxed = try { fi.urlFormats ?: emptyList() } catch (_: Throwable) { emptyList<MediaFormat>() }
        val audio = bestAudio(adaptive)
        val out = ArrayList<Q>()
        // فيديو منفصل (+ الصوت المنفصل): أحسن ملف لكل ارتفاع، mp4 أولًا
        val vids = adaptive.filter { (it.mimeType ?: "").startsWith("video/") && hasUrl(it) && it.height > 0 }
        if (audio != null) {
            for ((h, g) in vids.groupBy { it.height }) {
                val pick = g.filter { isMp4(it) }.ifEmpty { g }.maxByOrNull { bitrate(it) } ?: continue
                out.add(Q(h.toString() + "p", pick.url, audio.url, h))
            }
        }
        // ملفات متدمجة (فيديو+صوت في ملف واحد، غالبًا 360p/720p)
        for (m in muxed) {
            if (!hasUrl(m) || m.height <= 0) continue
            if (out.any { it.h == m.height }) continue
            out.add(Q(m.height.toString() + "p", m.url, null, m.height))
        }
        out.sortByDescending { it.h }
        val capped = if (maxH > 0) out.filter { it.h <= maxH }.ifEmpty { out.takeLast(1) } else out
        return Pair(capped, audio?.url)
    }

    private fun qlistJson(qs: List<Q>): String {
        val a = JSONArray()
        for (q in qs) a.put(JSONObject().put("l", q.label).put("u", q.url).put("a", q.audio ?: "").put("h", q.h))
        return a.toString()
    }

    /** فيديو للمشغّل بأعلى جودة ≤ maxH (0 = الأعلى) — null لو فشل (السبب في lastWhy) */
    fun video(id: String, maxH: Int, fresh: Boolean = false): Got? {
        val fi = info(id, fresh) ?: return null
        val ua = uaFor(fi)
        val title = fi.title ?: ""
        val (qs, _) = build(fi, maxH)
        val top = qs.firstOrNull()
        if (top != null) return Got(top.url, top.audio, ua, title, qlistJson(qs), "MeeTube")
        val hls = try { fi.hlsManifestUrl } catch (_: Throwable) { null }
        if (!hls.isNullOrEmpty()) return Got(hls, null, ua, title, "", "MeeTube·HLS")
        lastWhy = if (fi.containsSabrFormats()) "الفيديو ده SABR بس (من غير روابط مباشرة)" else "مفيش روابط مباشرة للفيديو"
        return null
    }

    /** رابط الصوت بس (m4a) */
    fun audio(id: String, @Suppress("UNUSED_PARAMETER") maxH: Int = 0): YtPick? {
        val fi = info(id) ?: return null
        val adaptive = try { fi.adaptiveFormats ?: emptyList() } catch (_: Throwable) { emptyList<MediaFormat>() }
        val au = bestAudio(adaptive)
        val dur = (fi.lengthSeconds?.toLongOrNull() ?: 0L) * 1000L
        val ua = uaFor(fi)
        if (au != null) return YtPick(au.url, null, ua, fi.title ?: "", 0, "", dur)
        // مفيش صوت منفصل: نستخدم أصغر ملف متدمج (الصوت بيتفك منه)
        val muxed = try { fi.urlFormats ?: emptyList() } catch (_: Throwable) { emptyList<MediaFormat>() }
        val m = muxed.filter { hasUrl(it) }.minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE } ?: run { lastWhy = "مفيش رابط صوت"; return null }
        return YtPick(m.url, null, ua, fi.title ?: "", 0, "", dur)
    }

    /** نفس الدالة بشكل YtPick للتحميل/الهاند-أوف: أحسن جودة ≤ maxH */
    fun pick(id: String, maxH: Int): YtPick? {
        val g = video(id, maxH) ?: return null
        val h = try { JSONArray(g.qlist).optJSONObject(0)?.optInt("h") ?: 0 } catch (_: Throwable) { 0 }
        val dur = (info(id)?.lengthSeconds?.toLongOrNull() ?: 0L) * 1000L
        return YtPick(g.url, g.audio, g.ua, g.title, h, g.qlist, dur)
    }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun toItem(m: com.liskovsoft.mediaserviceinterfaces.data.MediaItem): NpItem? {
        val vid = m.videoId ?: return null
        if (vid.length != 11) return null
        val sub = m.secondTitle?.toString() ?: ""
        val parts = sub.split(" • ", "·", "•").map { it.trim() }.filter { it.isNotEmpty() }
        val thumb = m.cardImageUrl?.takeIf { it.isNotBlank() } ?: "https://i.ytimg.com/vi/$vid/mqdefault.jpg"
        return NpItem(vid, m.title ?: "", parts.firstOrNull() ?: "", "", thumb, (m.durationMs / 1000L).coerceAtLeast(0L), -1L,
            m.productionDate ?: "", 0, watchUrl(vid), m.publishedDate.coerceAtLeast(0L))
    }

    private fun flatten(groups: List<com.liskovsoft.mediaserviceinterfaces.data.MediaGroup>?): List<NpItem> {
        val out = ArrayList<NpItem>()
        for (g in groups ?: emptyList()) for (m in g.mediaItems ?: emptyList()) toItem(m)?.let { out.add(it) }
        return out.distinctBy { it.id }
    }

    /** بحث فيديوهات (من محرك MeeTube) — من خيط خلفي */
    fun search(q: String): List<NpItem> {
        if (q.isBlank()) return emptyList()
        return try { boot(); flatten(YouTubeServiceManager.instance().contentService.getSearch(q)) }
        catch (e: Throwable) { LogStore.err("MtYt:search", e); emptyList() }
    }

    /** (v929) حساب MeeTube مسجّل دخول؟ */
    fun signedIn(): Boolean = try { boot(); YouTubeServiceManager.instance().signInService.isSigned() } catch (e: Throwable) { LogStore.err("MtYt:signed", e); false }

    /** (v929) أحدث فيديوهات الاشتراكات (صفحة الاشتراكات في MeeTube) — من خيط خلفي */
    fun subscriptions(): List<NpItem> =
        try { boot(); flatten(listOfNotNull(YouTubeServiceManager.instance().contentService.getSubscriptions())) }
        catch (e: Throwable) { LogStore.err("MtYt:subs", e); emptyList() }

    /** (v929) فيديوهات الصفحة الرئيسية في MeeTube — من خيط خلفي */
    fun homeFeed(): List<NpItem> =
        try { boot(); flatten(YouTubeServiceManager.instance().contentService.getHome()) }
        catch (e: Throwable) { LogStore.err("MtYt:home", e); emptyList() }

    /** المقترحات بعد الفيديو (من محرك MeeTube) — من خيط خلفي */
    fun related(id: String): List<NpItem> {
        return try { flatten(service().getMetadata(id)?.suggestions).filter { it.id != id } }
        catch (e: Throwable) { LogStore.err("MtYt:related", e); emptyList() }
    }
}
