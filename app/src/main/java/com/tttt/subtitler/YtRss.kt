package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * (v327) فيد الاشتراكات بـ RSS: https://www.youtube.com/feeds/videos.xml?channel_id=UC…
 * خفيف (طلب واحد صغير لكل قناة) ووقت الرفع دقيق. كاش لكل قناة في الذاكرة + ملف (filesDir/yt_rss.json).
 * القناة اللي مالهاش معرّف UC… أو فشل الـ RSS بتاعها ومفيش كاش → بنرجع للطريقة البطيئة (NewPipe channel()).
 */
object YtRss {
    const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_CH = 120
    private const val PER_CH = 8
    private val RX = Regex("/channel/(UC[A-Za-z0-9_-]{22})")

    private class Entry(val t: Long, val items: List<NpItem>)
    private val mem = HashMap<String, Entry>()
    @Volatile private var dir: File? = null
    @Volatile private var loaded = false

    fun attach(c: Context) { if (dir == null) dir = c.applicationContext.filesDir }

    fun chanId(url: String): String? = RX.find(url)?.groupValues?.get(1)

    // ───────── كاش الملف ─────────
    @Synchronized private fun loadDisk() {
        if (loaded) return
        loaded = true
        try {
            val f = File(dir ?: return, "yt_rss.json"); if (!f.isFile) return
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val e = o.getJSONObject(k); val a = e.getJSONArray("i"); val l = ArrayList<NpItem>()
                for (n in 0 until a.length()) {
                    val x = a.getJSONObject(n)
                    l.add(mk(x.optString("i"), x.optString("t"), x.optString("c"), x.optString("u"), x.optLong("p"), x.optLong("v", -1L)))
                }
                mem[k] = Entry(e.optLong("t"), l)
            }
        } catch (_: Throwable) {}
    }

    @Synchronized private fun saveDisk(keep: Set<String>) {
        try {
            val d = dir ?: return
            val o = JSONObject()
            for ((k, e) in mem) {
                if (k !in keep) continue
                val a = JSONArray()
                for (it in e.items) a.put(JSONObject().put("i", it.id).put("t", it.title).put("c", it.channel).put("u", it.channelUrl).put("p", it.ts).put("v", it.count))
                o.put(k, JSONObject().put("t", e.t).put("i", a))
            }
            val tmp = File(d, "yt_rss.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(File(d, "yt_rss.json"))
        } catch (_: Throwable) {}
    }

    private fun mk(id: String, title: String, ch: String, chUrl: String, ts: Long, views: Long) =
        NpItem(id, title, ch, chUrl, "https://i.ytimg.com/vi/$id/mqdefault.jpg", -1L, views, ageOf(ts), 0, "https://www.youtube.com/watch?v=$id", ts)

    /** «منذ 3 أيام» من وقت دقيق */
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

    // ───────── تحميل وتحليل RSS ─────────
    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 9000   // (v354) أقصر: القناة البطيئة ما تعطّلش الفيد كله
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        try {
            if (c.responseCode != 200) throw RuntimeException("RSS " + c.responseCode)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    fun parse(xml: String, chUrl: String): List<NpItem> {
        val p = Xml.newPullParser(); p.setInput(xml.reader())
        val out = ArrayList<NpItem>()
        var inEntry = false; var inAuthor = false
        var id = ""; var title = ""; var pub = ""; var views = -1L; var author = ""; var feedAuthor = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "entry" -> { inEntry = true; id = ""; title = ""; pub = ""; views = -1L; author = "" }
                    "author" -> inAuthor = true
                    "yt:videoId" -> if (inEntry) id = p.nextText().trim()
                    "title" -> if (inEntry) title = p.nextText().trim()
                    "published" -> if (inEntry) pub = p.nextText().trim()
                    "name" -> if (inAuthor) { val n = p.nextText().trim(); if (inEntry) author = n else feedAuthor = n }
                    "media:statistics" -> if (inEntry) views = p.getAttributeValue(null, "views")?.toLongOrNull() ?: -1L
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        if (id.isNotEmpty()) {
                            val ts = try { OffsetDateTime.parse(pub).toInstant().toEpochMilli() } catch (_: Throwable) { 0L }
                            out.add(mk(id, title, author.ifEmpty { feedAuthor }, chUrl, ts, views))
                        }
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun fetchOne(cid: String, chUrl: String, force: Boolean, staleOk: Boolean = false): List<NpItem> {
        val now = System.currentTimeMillis()
        val e = synchronized(this) { mem[cid] }
        if (!force && e != null && (staleOk || now - e.t < TTL_MS)) return e.items   // (v354) staleOk: اعرض الكاش فورًا حتى لو قديم
        return try {
            val l = parse(http("https://www.youtube.com/feeds/videos.xml?channel_id=$cid"), chUrl).take(PER_CH)
            synchronized(this) { mem[cid] = Entry(now, l) }
            l
        } catch (t: Throwable) { e?.items ?: throw t }   // فشل → آخر كاش لو موجود
    }

    /** فيد مدموج من كل الاشتراكات، الأحدث أولًا. force = تجاهل الكاش */
    private val feedLock = Any()
    private val fbBudget = java.util.concurrent.atomic.AtomicInteger(0)
    /** (v354) يحدّث الكاش في الخلفية (القنوات المنتهية بس) من غير ما يعطّل الشاشة — الزيارة الجاية تلاقي فيد جديد */
    fun refreshAsync(subs: List<NpItem>) {
        Thread { try { feed(subs, false, false) } catch (_: Throwable) {} }.apply { isDaemon = true; start() }
    }
    /** staleOk: لو للقناة كاش (حتى لو قديم) بيتعرض فورًا من غير شبكة */
    fun feed(subs: List<NpItem>, force: Boolean = false, staleOk: Boolean = false): YtRes = synchronized(feedLock) {   // (v354) طلب واحد في المرة: فحص العدّاد ما بيتخانقش مع فتح الاشتراكات (كان بيعمل ضغط ويوتيوب بيرفض)
        loadDisk()
        fbBudget.set(5)   // أقصى عدد قنوات نرجع فيها للطريقة البطيئة (NewPipe) في الطلب الواحد
        val list = subs.take(MAX_CH)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val fs = list.map { s ->
                pool.submit(Callable<List<NpItem>> {
                    val cid = chanId(s.channelUrl)
                    try {
                        if (cid != null) fetchOne(cid, s.channelUrl, force, staleOk)
                        else if (fbBudget.getAndDecrement() > 0) { YtNp.init(); YtNp.channel(s.channelUrl).first.items.filter { it.kind == 0 }.take(6) } else emptyList()
                    } catch (_: Throwable) {
                        if (cid != null && fbBudget.getAndDecrement() > 0) try { YtNp.init(); YtNp.channel(s.channelUrl).first.items.filter { it.kind == 0 }.take(6) } catch (_: Throwable) { emptyList() } else emptyList()
                    }
                })
            }
            val all = ArrayList<NpItem>()
            val dl = System.currentTimeMillis() + 40000L
            for (f in fs) try { all.addAll(f.get(maxOf(1L, dl - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS)) } catch (_: Throwable) {}
            // العمر بيتحسب من جديد (الكاش ممكن يكون قديم)
            val fresh = all.distinctBy { it.id }.map { if (it.ts > 0L) NpItem(it.id, it.title, it.channel, it.channelUrl, it.thumb, it.dur, it.count, ageOf(it.ts), it.kind, it.url, it.ts) else it }
            saveDisk(list.mapNotNull { chanId(it.channelUrl) }.toSet())
            YtRes(fresh.sortedByDescending { it.ts }.take(200), null)
        } finally { pool.shutdown() }
    }
}

/** (v327) إشعارات الفيديوهات الجديدة من الاشتراكات (فحص كل ساعة بـ JobScheduler، من غير مكتبات زيادة) */
object YtNotify {
    const val KEY = "yt_notif"          // الإعداد: شغّال افتراضيًا
    private const val SEEN = "ytp_seen" // سطر لكل قناة: معرّف=آخر وقت رفع شافه
    private const val CH = "yt_new"
    private const val JOB = 32700
    private const val MAX_SINGLE = 4

    fun enabled() = Cfg.bool(KEY, true)

    // (v403) إشعار لكل قناة: القنوات اللي قفلت إشعاراتها (الباقي شغّال لو الإشعارات العامة شغّالة)
    private const val OFF = "ytp_notif_off"
    private fun offSet(): HashSet<String> = HashSet(Cfg.str(OFF, "").split("\n").filter { it.isNotBlank() })
    fun chanOn(url: String): Boolean { val id = YtRss.chanId(url) ?: return true; return !offSet().contains(id) }
    fun setChanOn(url: String, on: Boolean) {
        val id = YtRss.chanId(url) ?: return
        val s = offSet(); if (on) s.remove(id) else s.add(id)
        Cfg.put(OFF, s.joinToString("\n"))
    }

    // ───────── (v350) عدّاد الفيديوهات الجديدة على أيقونة يوتيوب ─────────
    private const val OPENED = "ytp_opened"      // آخر مرة فتحت فيها الاشتراكات
    private const val BADGE = "ytp_badge"
    private const val BADGE_AT = "ytp_badge_at"
    @Volatile var badge = -1
    fun badgeCount(): Int { if (badge < 0) badge = Cfg.str(BADGE, "0").toIntOrNull() ?: 0; return badge }
    private fun storeBadge(n: Int) { badge = n; Cfg.put(BADGE, n.toString()) }
    /** فتحت الاشتراكات: العدّاد يتصفّر وبنبدأ العدّ من دلوقتي */
    fun markOpened(c: Context) { Cfg.put(OPENED, System.currentTimeMillis().toString()); storeBadge(0); try { (c as? MainActivity)?.let { m -> m.runOnUiThread { m.updateYtBadge() } } } catch (_: Throwable) {} }
    private fun countNew(items: List<NpItem>): Int {
        val t = Cfg.str(OPENED, "").toLongOrNull() ?: 0L
        if (t == 0L) { Cfg.put(OPENED, System.currentTimeMillis().toString()); return 0 }   // أول مرة: خط أساس
        return items.count { it.ts > t }
    }
    /** بيحدّث العدّاد من الفيد (كاش 15 دقيقة) في خيط خلفي؛ بيستدعي done على نفس الخيط */
    fun refreshBadge(c: Context, force: Boolean = false, done: (Int) -> Unit) {
        val now = System.currentTimeMillis()
        if (!force && now - (Cfg.str(BADGE_AT, "0").toLongOrNull() ?: 0L) < 5 * 60 * 1000L) return
        Cfg.put(BADGE_AT, now.toString())
        Thread {
            try {
                Cfg.ensure(c); YtRss.attach(c)
                val subs = YtStore.read(YtStore.SUBS); if (subs.isEmpty()) return@Thread
                val n = countNew(YtRss.feed(subs, false).items)
                storeBadge(n); done(n)
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
    }

    fun schedule(c: Context) {
        try {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (!enabled() || YtStore.read(YtStore.SUBS).isEmpty()) { js.cancel(JOB); return }
            if (js.getPendingJob(JOB) != null) return
            val ji = JobInfo.Builder(JOB, ComponentName(c, YtFeedJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(60L * 60L * 1000L)
                .build()
            js.schedule(ji)
        } catch (e: Throwable) { try { LogStore.err("YtNotify:schedule", e) } catch (_: Throwable) {} }
    }

    fun setEnabled(c: Context, on: Boolean) {
        Cfg.put(KEY, if (on) "1" else "0")
        try { (c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB) } catch (_: Throwable) {}
        if (on) schedule(c)
    }

    private fun readSeen(): HashMap<String, Long> {
        val m = HashMap<String, Long>()
        for (l in Cfg.str(SEEN, "").lineSequence()) { val i = l.indexOf('='); if (i > 0) m[l.substring(0, i)] = l.substring(i + 1).toLongOrNull() ?: 0L }
        return m
    }
    private fun writeSeen(m: Map<String, Long>) = Cfg.put(SEEN, m.entries.joinToString("\n") { it.key + "=" + it.value })

    /** بيحدّث «آخر ما اتشاف» لكل القنوات للوقت الحالي من الفيد (بعد ما اليوزر يفتح الاشتراكات) عشان ما يجيش إشعار على حاجة شافها */
    fun markSeen(items: List<NpItem>) {
        try {
            val m = readSeen()
            for (it in items) { val cid = YtRss.chanId(it.channelUrl) ?: continue; if (it.ts > (m[cid] ?: 0L)) m[cid] = it.ts }
            writeSeen(m)
        } catch (_: Throwable) {}
    }

    /** الفحص نفسه. بيرجّع عدد الإشعارات اللي اتبعتت */
    fun check(c: Context): Int {
        Cfg.ensure(c); YtRss.attach(c)
        if (!enabled()) return 0
        val subs = YtStore.read(YtStore.SUBS); if (subs.isEmpty()) return 0
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val feed = YtRss.feed(subs, true).items
        try { storeBadge(countNew(feed)) } catch (_: Throwable) {}   // (v350) العدّاد بيتحدّث مع الفحص الدوري كمان
        val seen = readSeen(); val fresh = ArrayList<NpItem>()
        val firstRun = seen.isEmpty()
        val newest = HashMap<String, Long>()
        val off = offSet()
        for (it in feed) {
            val cid = YtRss.chanId(it.channelUrl) ?: continue
            if (it.ts > (newest[cid] ?: 0L)) newest[cid] = it.ts
            val last = seen[cid]
            if (last != null && it.ts > last && !off.contains(cid)) fresh.add(it)   // قناة جديدة على الإشعارات (من غير سجل) = خط أساس من غير إشعار
        }
        for ((k, v) in newest) if (v > (seen[k] ?: 0L)) seen[k] = v
        writeSeen(seen)
        if (firstRun || fresh.isEmpty()) return 0
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return 0
        if (!nm.areNotificationsEnabled()) return 0
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "فيديوهات جديدة من اشتراكاتي", NotificationManager.IMPORTANCE_DEFAULT))
        val list = fresh.sortedByDescending { it.ts }
        val pf = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        fun open(vid: String?, rc: Int): PendingIntent {
            val i = Intent(c, MainActivity::class.java).putExtra("tab", "youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (vid != null) i.putExtra("yt_open", vid)
            return PendingIntent.getActivity(c, rc, i, pf)
        }
        var n = 0
        if (list.size <= MAX_SINGLE) {
            for (v in list) {
                val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH) else @Suppress("DEPRECATION") Notification.Builder(c)
                nb.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(v.channel.ifBlank { "فيديو جديد" }).setContentText(v.title)
                    .setStyle(Notification.BigTextStyle().bigText(v.title)).setContentIntent(open(v.id, v.id.hashCode())).setAutoCancel(true)
                nm.notify(("yt:" + v.id).hashCode(), nb.build()); n++
            }
        } else {
            val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH) else @Suppress("DEPRECATION") Notification.Builder(c)
            val chs = list.map { it.channel }.filter { it.isNotBlank() }.distinct().take(3).joinToString("، ")
            nb.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("📺 " + list.size + " فيديو جديد من اشتراكاتك").setContentText(chs)
                .setStyle(Notification.BigTextStyle().bigText(list.take(6).joinToString("\n") { "• " + it.channel + ": " + it.title }))
                .setContentIntent(open(null, 7)).setAutoCancel(true)
            nm.notify(32701, nb.build()); n = 1
        }
        return n
    }
}

class YtFeedJob : JobService() {
    @Volatile private var t: Thread? = null
    override fun onStartJob(p: JobParameters): Boolean {
        t = Thread {
            try { YtNotify.check(applicationContext) } catch (e: Throwable) { try { LogStore.err("YtFeedJob", e) } catch (_: Throwable) {} }
            try { jobFinished(p, false) } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        return true
    }
    override fun onStopJob(p: JobParameters): Boolean { t?.interrupt(); return true }
}
