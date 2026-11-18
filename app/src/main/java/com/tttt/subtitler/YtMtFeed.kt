package com.tttt.subtitler

import android.content.Context
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
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

    /** بعد دخول/خروج: نصفّر الكاش */
    fun reset() { signedAt = 0L; homeAt = 0L; groups.clear(); synchronized(order) { order.clear() } }

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
        return YtRes(items, keep(f.next))
    }

    // ───────── الرئيسية ─────────
    /** أول صفحة رئيسية (من غير الفيديوهات اللي اتشافت). null = فاضية/فشلت ← المسار القديم. */
    fun home(hist: List<NpItem>): YtRes? {
        val seen = hist.map { it.id }.toHashSet()
        var f = MtYt.homeFirst()
        var r = res(f, seen)
        // الرئيسية ممكن تيجي أغلبها مشاهَد قبل كده: كمّل صفحتين لحد ما يبقى فيه كفاية
        var guard = 0
        val acc = ArrayList<NpItem>(r.items)
        var nxt = f.next
        while (acc.size < 12 && nxt != null && guard < 3) {
            guard++
            f = MtYt.more(nxt); nxt = f.next
            val x = res(f, seen); val have = acc.map { it.id }.toHashSet()
            acc.addAll(x.items.filter { it.id !in have })
            r = YtRes(acc, x.next)
        }
        if (acc.isEmpty()) return null
        homeAt = System.currentTimeMillis()
        val out = YtRes(acc, r.next)
        saveHomeSnap(out)
        return out
    }

    /** الصفحة اللي بعد Page ماركة ytm: */
    fun more(p: Page, hist: List<NpItem> = emptyList()): YtRes {
        val g = groups[p.url ?: ""] ?: return YtRes(emptyList(), null)
        val seen = hist.map { it.id }.toHashSet()
        return res(MtYt.more(g), seen)
    }

    /** تحديث خلفي للرئيسية (بنفس فكرة YtNp.refreshHomeAsync): بيطلّع كبسولة «فيديوهات جديدة» لو فيه جديد */
    fun refreshHomeAsync(hist: List<NpItem>): Boolean {
        if (homeBusy) return false
        homeBusy = true
        Thread {
            try { val r = home(hist); onHome?.invoke(r ?: YtRes(emptyList(), null)) }
            catch (_: Throwable) { try { onHome?.invoke(YtRes(emptyList(), null)) } catch (_: Throwable) {} }
            finally { homeBusy = false }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    // ───────── الاشتراكات ─────────
    /** أول صفحة اشتراكات (الأحدث أولًا). null = فاضية/فشلت ← المسار القديم. */
    fun subs(): YtRes? {
        val f = MtYt.subsFirst()
        val r = res(f, emptySet())
        if (r.items.isEmpty()) return null
        return r
    }

    // ───────── كاش الرئيسية على القرص (عشان تفتح فورًا) ─────────
    @Volatile private var dir: java.io.File? = null
    fun attach(c: Context) { if (dir == null) dir = c.applicationContext.filesDir }
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
            .put("d", x.dur).put("n", x.count).put("a", x.age).put("k", x.kind).put("u", x.url).put("ts", x.ts).put("av", x.avatar))
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
                if (ts > 0L) YtRss.ageOf(ts) else x.optString("a"), x.optInt("k", 0), x.optString("u"), ts, x.optString("av")))
        }
        return if (l.isEmpty()) null else l
    }
    @Synchronized fun drop(d: java.io.File) { try { f(d).delete() } catch (_: Throwable) {} }
}
