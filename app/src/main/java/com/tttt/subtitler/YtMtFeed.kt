package com.tttt.subtitler

import android.content.Context
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.youtubeapi.browse.v2.BrowseServiceGates
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.extractor.Page
import java.util.concurrent.ConcurrentHashMap

/**
 * (v1104) Y3 — صفحتا «الرئيسية» و«الاشتراكات» في يوتيوب Joyboy القديم (YtProUi) مصدرهم دلوقتي محرك MeeTube (MediaServiceCore)
 * بدل NewPipe/RSS/الكوكيز: بيلقط كل الفيديوهات (القديم كان بيجيب ناقص وقديم)، وبتحميل مفتوح لتحت (continueGroup)، والاشتراكات مرتّبة بالأحدث.
 *
 *  • بيشتغل بس لو: المفتاح yt_mt_feed شغّال (افتراضي: شغّال) + حساب الكود مسجّل دخول (YtCodeLogin / Y1).
 *    غير كده YtProUi بيكمّل على المسار القديم بالظبط (NewPipe/RSS) — مفيش أي تغيير في السلوك.
 *  • لو الجلب رجع فاضي (خطأ شبكة/حساب) بنرجّع null ← YtProUi بيستخدم المسار القديم كاحتياطي.
 *  • «Page» هنا مفتاح مرجعي لمجموعة MediaGroup محفوظة في الذاكرة (ytm:<رقم>) — YtProUi بيتعامل معاه زي أي Page.
 *
 * كل الدوال بتتنده من خيط خلفي. لم يتم compile.
 */
object YtMtFeed {
    private const val KEY = "yt_mt_feed"
    private const val PFX = "ytm:"
    private const val MAX_GROUPS = 24

    @Volatile var onHome: ((YtRes) -> Unit)? = null
    @Volatile private var homeAt = 0L
    @Volatile private var homeBusy = false
    @Volatile private var signedAt = 0L
    @Volatile private var signedVal = false
    @Volatile private var signedInAt = 0L        // (v1106) وقت آخر دخول ناجح — بنعيد المحاولة لو الرئيسية رجعت فاضية بعده بشوية (المحرك لسه بيجهّز الحساب)
    @Volatile private var lastShown: Set<String> = emptySet()   // (v1107) اللي اتشاف فعلًا (ظهر على الشاشة وإنت بتسكرول) — مش كل اللي اتحمّل. الجديد/اللي لسه ماوصلتلوش بيطلع فوق في التحديث. «اتفرّجت عليه» (HIST) حاجة تانية: بيتشال من الرئيسية
    private const val VIEWED_KEY = "ytp_home_viewed"
    /** (v1107) فيديوهات عدّت على الشاشة وإنت بتسكرول في الرئيسية — بتتحفظ (آخر 400) عشان التحديث الجاي يفرّق بين اللي شفته واللي لسه */
    fun markViewed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val s = LinkedHashSet<String>()
        for (x in ids) { if (s.size >= 400) break; s.add(x) }
        for (x in lastShown) { if (s.size >= 400) break; s.add(x) }
        lastShown = s
        try { Cfg.put(VIEWED_KEY, s.joinToString("\n")) } catch (_: Throwable) {}
    }
    private val groups = ConcurrentHashMap<String, MediaGroup>()
    private val order = ArrayList<String>()
    private var seq = 0

    fun enabled() = Cfg.bool(KEY, true)

    /** مسجّل دخول بالكود؟ (كاش 20 ثانية — الفحص بيشغّل المحرك) */
    fun usable(): Boolean {
        if (!enabled()) return false
        val now = System.currentTimeMillis()
        if (now - signedAt < 20_000L) return signedVal
        val v = try { MtYt.signedIn() } catch (_: Throwable) { false }
        signedVal = v; signedAt = now
        return v
    }

    /** نفس usable() من غير ما نشغّل المحرك (آمنة على خيط الواجهة) — آخر قيمة معروفة */
    fun usableCached(): Boolean = enabled() && signedVal

    /** (v1106) الدخول تم دلوقتي: القيمة المعروفة تتحدّث فورًا (usableCached كانت بتفضل false لحد أول فحص فصفحة «سجّل دخولك» كانت بتفضل ظاهرة) */
    fun markSigned(v: Boolean) { signedVal = v; signedAt = System.currentTimeMillis(); if (v) signedInAt = signedAt }

    /** بعد دخول/خروج: نصفّر الكاش (القيمة المعروفة signedVal بتفضل لحد ما يتحدّد غيرها) */
    fun reset() { signedAt = 0L; homeAt = 0L; stopWalk(); walks.clear(); lastShown = emptySet(); groups.clear(); synchronized(order) { order.clear() } }

    private fun justSignedIn() = signedInAt > 0L && System.currentTimeMillis() - signedInAt < 90_000L

    fun homeAgeMs(): Long = if (homeAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - homeAt

    private fun keep(g: MediaGroup?): Page? {
        if (g == null) return null
        val k = PFX + (++seq)
        groups[k] = g
        synchronized(order) {
            order.add(k)
            while (order.size > MAX_GROUPS) groups.remove(order.removeAt(0))
        }
        return Page(k)
    }

    private fun res(f: MtYt.Feed, seen: Set<String>): YtRes {
        val items = f.items.filter { it.id !in seen && it.kind == 0 }.distinctBy { it.id }
        fillAvatars(items)
        return YtRes(items, keep(f.next))
    }

    private val avCache = ConcurrentHashMap<String, String>()
    private val avBusy = ConcurrentHashMap<String, Boolean>()

    /** (v1144) لوجو قناة من أي مفتاح (رابط/@handle/UC…): كاش ← خريطة اشتراكاتك ← جلب من الخدمة في خيط خلفي. cb بيتنده على خيط خلفي (الـUI يعمل runOnUiThread) */
    private val avWait = ConcurrentHashMap<String, MutableList<(String) -> Unit>>()
    fun avatarAsync(c: android.content.Context, key: String, cb: (String) -> Unit) {
        if (key.isBlank()) return
        val cid0 = if (key.startsWith("UC") && key.length == 24) key else YtRss.chanId(key)
        if (cid0 != null) { val hit = avCache[cid0]; if (!hit.isNullOrBlank()) { cb(hit); return } }
        val wk = cid0 ?: key
        // (v1147) كل الكروت اللي من نفس القناة بتستنى نفس الجلب وكلها بتاخد اللوجو (قبل كده أول كارت بس كان بياخده والباقي بيتجاهل)
        synchronized(avWait) {
            val l = avWait[wk]
            if (l != null) { l.add(cb); return }
            avWait[wk] = mutableListOf(cb)
        }
        Thread {
            var got: String? = null
            try {
                try { attach(c) } catch (_: Throwable) {}
                var cid: String? = cid0
                if (cid == null) {   // (v1147) @handle من غير معرّف محفوظ: نحلّه الأول (قبل كده كان بيقف هنا من غير لوجو)
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var r: String? = null
                    try { YtChanId.resolve(key) { x -> r = x; latch.countDown() } } catch (_: Throwable) { latch.countDown() }
                    try { latch.await(12, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
                    cid = r
                }
                if (cid != null) {
                    val sm = YouTubeServiceManager.instance().contentService
                    var u: String? = avCache[cid]
                    if (u.isNullOrBlank()) u = try { sm.subscribedChannelAvatars?.get(cid) } catch (_: Throwable) { null }
                    if (u.isNullOrBlank()) u = try { sm.getChannelAvatarUrl(cid) } catch (_: Throwable) { null }
                    if (!u.isNullOrBlank()) { avCache[cid] = u; got = u }
                }
            } catch (_: Throwable) {}
            val ws = synchronized(avWait) { avWait.remove(wk) }
            val g = got
            if (!g.isNullOrBlank() && ws != null) for (w in ws) { try { w(g) } catch (_: Throwable) {} }
        }.apply { isDaemon = true; start() }
    }

    private fun fillAvatars(items: List<NpItem>) {
        if (items.isEmpty()) return
        val subsMap = try { YouTubeServiceManager.instance().contentService.subscribedChannelAvatars } catch (_: Throwable) { null }
        var resolved = 0
        for (x in items) {
            if (x.avatar.isNotBlank()) continue
            val cid = x.channelId
            if (cid.isBlank()) continue
            val sub = subsMap?.get(cid)
            if (!sub.isNullOrBlank()) {
                x.avatar = sub
                avCache[cid] = sub
                continue
            }
            val cached = avCache[cid]
            if (!cached.isNullOrBlank()) {
                x.avatar = cached
                continue
            }
            if (resolved >= 20) continue
            resolved++
            val url = try { YouTubeServiceManager.instance().contentService.getChannelAvatarUrl(cid) } catch (_: Throwable) { null }
            if (!url.isNullOrBlank()) {
                x.avatar = url
                avCache[cid] = url
            }
        }
    }

    // ───────── الرئيسية ─────────
    // (v1106) كانت بتاخد أول صفحة صفوف بس (getHome) وتكمّل من أول صف واحد فقط = جزء صغير من اللي ميتيوب بيجيبه. دلوقتي بنمشي قايمة الأقسام
    // صفحة صفحة (getHomeObserve) بنفس طريقة ميتيوب: صفحتين الأول وكل ما تنزل صفحة كمان (SectionListPacer)، والجديد (اللي ماظهرش في آخر تحديث) فوق.
    private const val WPFX = "ytmh:"
    private const val WALK_FIRST_PAGES = 2
    private val walks = ConcurrentHashMap<String, Walk>()
    @Volatile private var walk: Walk? = null

    private class Walk(val key: String) : BrowseServiceGates.SectionListPacer {
        val lock = Object()
        val items = ArrayList<NpItem>()
        val ids = HashSet<String>()
        var pages = 0
        var allowed = WALK_FIRST_PAGES
        var delivered = 0
        @Volatile var done = false
        @Volatile var stopped = false
        @Volatile var err: Throwable? = null
        @Volatile var disp: Disposable? = null
        override fun awaitNextPage(groupType: Int, nextPage: Int, disposal: BrowseServiceGates.Disposal): Boolean {
            if (groupType != MediaGroup.TYPE_HOME) return true
            synchronized(lock) {
                while (nextPage > allowed && !stopped && !disposal.isDisposed()) { try { lock.wait(1000L) } catch (_: InterruptedException) { return false } }
            }
            return !stopped && !disposal.isDisposed()
        }
        fun demand() { synchronized(lock) { allowed++; lock.notifyAll() } }
        fun stop() { stopped = true; done = true; try { disp?.dispose() } catch (_: Throwable) {}; synchronized(lock) { lock.notifyAll() } }
    }

    private fun stopWalk() { val w = walk; walk = null; w?.stop(); if (w != null && BrowseServiceGates.sectionListPacer === w) BrowseServiceGates.sectionListPacer = null }

    private fun startWalk(): Walk {
        stopWalk()
        val w = Walk(WPFX + (++seq))
        walks[w.key] = w
        synchronized(order) { order.add(w.key); while (order.size > MAX_GROUPS) { val k = order.removeAt(0); groups.remove(k); walks.remove(k)?.stop() } }
        walk = w
        BrowseServiceGates.sectionListPacer = w
        try {
            MtYt.ensureBoot()
            w.disp = YouTubeServiceManager.instance().contentService.getHomeObserve().subscribe(
                { gs ->
                    val got = try { MtYt.flattenGroups(gs) } catch (_: Throwable) { emptyList() }
                    synchronized(w.lock) { for (x in got) if (w.ids.add(x.id)) w.items.add(x); w.pages++; w.lock.notifyAll() }
                },
                { e -> w.err = e; w.done = true; synchronized(w.lock) { w.lock.notifyAll() } },
                { w.done = true; synchronized(w.lock) { w.lock.notifyAll() } })
        } catch (e: Throwable) { w.err = e; w.done = true }
        return w
    }

    /** أول رئيسية (من غير اللي اتشاف) بتحميل مفتوح. null = فاضية/فشلت ← المسار القديم. من خيط خلفي. */
    fun home(hist: List<NpItem>): YtRes? {
        val seen = hist.map { it.id }.toHashSet()
        var attempt = 0
        while (true) {
            val w = startWalk()
            val deadline = System.currentTimeMillis() + 12_000L
            synchronized(w.lock) {
                while (!w.done && w.pages < WALK_FIRST_PAGES && System.currentTimeMillis() < deadline) { try { w.lock.wait(300L) } catch (_: InterruptedException) { break } }
            }
            val first: List<NpItem>
            synchronized(w.lock) { first = w.items.filter { it.id !in seen && it.kind == 0 }; w.delivered = w.items.size }
            if (first.isNotEmpty()) {
                val prev = lastShown
                val ordered = if (prev.isEmpty()) first else first.filter { it.id !in prev } + first.filter { it.id in prev }   // الجديد فوق
                homeAt = System.currentTimeMillis()
                fillAvatars(ordered)
                val out = YtRes(ordered, if (w.done && w.delivered >= w.items.size) null else Page(w.key))
                saveHomeSnap(out)
                return out
            }
            w.err?.let { LogStore.err("YtMtFeed:home", it) }
            stopWalk()
            // الدخول لسه جديد: المحرك أوقات بيحتاج ثواني يجهّز الحساب — نعيد مرة أو اتنين قبل ما نسلّم للمسار القديم
            if (justSignedIn() && attempt < 2) { attempt++; try { Thread.sleep(1500L) } catch (_: InterruptedException) { return null }; continue }
            return null
        }
    }

    /** الدفعة اللي بعد كده من مشي الرئيسية */
    private fun moreWalk(w: Walk, seen: Set<String>): YtRes {
        val deadline = System.currentTimeMillis() + 12_000L
        val chunk: List<NpItem>
        synchronized(w.lock) {
            if (w.delivered >= w.items.size && !w.done) {
                w.demand()
                while (w.delivered >= w.items.size && !w.done && System.currentTimeMillis() < deadline) { try { w.lock.wait(300L) } catch (_: InterruptedException) { break } }
            }
            chunk = w.items.subList(w.delivered.coerceAtMost(w.items.size), w.items.size).filter { it.id !in seen && it.kind == 0 }
            w.delivered = w.items.size
        }
        if (!w.done) w.demand()   // صفحة قدّام القارئ دايمًا
        val end = w.done && w.delivered >= w.items.size
        return YtRes(chunk, if (end) null else Page(w.key))
    }

    /** الصفحة اللي بعد Page ماركة ytm: */
    fun more(p: Page, hist: List<NpItem> = emptyList()): YtRes {
        val k = p.url ?: ""
        if (k.startsWith(WPFX)) { val w = walks[k] ?: return YtRes(emptyList(), null); return moreWalk(w, hist.map { it.id }.toHashSet()) }
        val g = groups[k] ?: return YtRes(emptyList(), null)
        val seen = hist.map { it.id }.toHashSet()
        return res(MtYt.more(g), seen)
    }

    /** تحديث خلفي للرئيسية (بنفس فكرة YtNp.refreshHomeAsync): بيطلّع كبسولة «فيديوهات جديدة» لو فيه جديد */
    fun refreshHomeAsync(hist: List<NpItem>): Boolean {
        if (homeBusy) return false
        homeBusy = true
        Thread {
            try { val r = peekHome(hist); onHome?.invoke(r ?: YtRes(emptyList(), null)) }
            catch (_: Throwable) { try { onHome?.invoke(YtRes(emptyList(), null)) } catch (_: Throwable) {} }
            finally { homeBusy = false }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    /** (v1106) فحص خلفي: أول صفحة بس (من غير مشي) — عشان التحديث الخلفي ما يوقّفش تحميل الصفحة اللي إنت نازل فيها */
    private fun peekHome(hist: List<NpItem>): YtRes? {
        val seen = hist.map { it.id }.toHashSet()
        val f = MtYt.homeFirst()
        val items = f.items.filter { it.id !in seen && it.kind == 0 }.distinctBy { it.id }
        if (items.isEmpty()) return null
        fillAvatars(items)
        homeAt = System.currentTimeMillis()
        return YtRes(items, null)
    }

    // ───────── الاشتراكات ─────────
    /** أول صفحة اشتراكات (الأحدث أولًا). null = فاضية/فشلت ← المسار القديم. */
    fun subs(): YtRes? {
        var f = MtYt.subsFirst()
        var r = res(f, emptySet())
        if (r.items.isEmpty() && justSignedIn()) { try { Thread.sleep(1500L) } catch (_: InterruptedException) {}; f = MtYt.subsFirst(); r = res(f, emptySet()) }   // (v1106) دخول لسه جديد
        if (r.items.isEmpty()) return null
        return r
    }

    /** (v1107) معرّفات أول صفحة اشتراكات (للعدّاد الأحمر على «الاشتراكات»). null = فاضية/فشلت */
    fun subsIds(): List<String>? {
        val l = MtYt.subsFirst().items.filter { it.kind == 0 }.map { it.id }.distinct()
        return if (l.isEmpty()) null else l
    }

    // ───────── كاش الرئيسية على القرص (عشان تفتح فورًا) ─────────
    @Volatile private var dir: java.io.File? = null
    fun attach(c: Context) { if (dir == null) { dir = c.applicationContext.filesDir; if (lastShown.isEmpty()) lastShown = try { Cfg.str(VIEWED_KEY, "").split('\n').filter { it.isNotBlank() }.toHashSet() } catch (_: Throwable) { emptySet() } } }
    private fun saveHomeSnap(r: YtRes) { try { dir?.let { d -> YtSnapHome.save(d, r.items) } } catch (_: Throwable) {} }
    /** آخر رئيسية محفوظة من المحرك (حتى لو قديمة) — null لو مفيش */
    fun homeStale(): List<NpItem>? = try { dir?.let { YtSnapHome.load(it) } } catch (_: Throwable) { null }
    fun dropHomeSnap() { try { dir?.let { YtSnapHome.drop(it) } } catch (_: Throwable) {} ; homeAt = 0L }
}

/** ملف yt_mt_home.json — نفس شكل YtSnap بس للرئيسية بتاعة المحرك (منفصل عن yt_home.json بتاع NewPipe عشان ما يتخلطوش) */
private object YtSnapHome {
    private const val MAX = 90
    private fun f(d: java.io.File) = java.io.File(d, "yt_mt_home.json")
    @Synchronized fun save(d: java.io.File, items: List<NpItem>) {
        val vids = items.filter { it.kind == 0 && it.id.isNotBlank() }.take(MAX)
        if (vids.isEmpty()) return
        val a = org.json.JSONArray()
        for (x in vids) a.put(org.json.JSONObject().put("i", x.id).put("t", x.title).put("c", x.channel).put("cu", x.channelUrl).put("th", x.thumb)
            .put("d", x.dur).put("n", x.count).put("a", x.age).put("k", x.kind).put("u", x.url).put("ts", x.ts).put("av", x.avatar).put("cid", x.channelId))
        val tmp = java.io.File(d, "yt_mt_home.json.tmp")
        tmp.writeText(org.json.JSONObject().put("at", System.currentTimeMillis()).put("items", a).toString()); tmp.renameTo(f(d))
    }
    @Synchronized fun load(d: java.io.File): List<NpItem>? {
        val file = f(d); if (!file.isFile) return null
        val o = org.json.JSONObject(file.readText())
        if (System.currentTimeMillis() - o.optLong("at") > 7L * 24 * 3600 * 1000L) return null
        val a = o.getJSONArray("items"); val l = ArrayList<NpItem>()
        for (n in 0 until a.length()) {
            val x = a.getJSONObject(n); val ts = x.optLong("ts", 0L)
            l.add(NpItem(x.optString("i"), x.optString("t"), x.optString("c"), x.optString("cu"), x.optString("th"), x.optLong("d", -1L), x.optLong("n", -1L),
                if (ts > 0L) YtRss.ageOf(ts) else x.optString("a"), x.optInt("k", 0), x.optString("u"), ts, x.optString("av"), x.optString("cid")))
        }
        return if (l.isEmpty()) null else l
    }
    @Synchronized fun drop(d: java.io.File) { try { f(d).delete() } catch (_: Throwable) {} }
}
