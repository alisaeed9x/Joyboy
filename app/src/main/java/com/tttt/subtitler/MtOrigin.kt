package com.tttt.subtitler

import android.app.Activity
import android.content.Intent

/**
 * (v939) مصدر التشغيل الواعي (source-aware playback origin) + المتحكم المركزي في التصغير/الرجوع.
 * المشكلة اللي بيحلها: فيديو جاي من المتصفح أو من واتساب كان بيتفتح في مشغّل MeeTube،
 * ولما يتصغّر/يتقفل كان بيرجع لتصفّح يوتيوب لأن مفيش حد فاكر هو جه منين.
 * دلوقتي كل فتحة في MeeTube بتتسجّل هنا (من MtPlay.openMt) قبل ما الفيديو يتشغّل،
 * والرجوع/التصغير بيتحسم من المصدر الأصلي مش من الأكتفيتي اللي صادف إنها شغّالة.
 * جدول الوجهات (من خطة الدمج):
 * يوتيوب ← مصغّر يوتيوب · المتصفح ← مصغّر المتصفح · واتساب/خارجي ← واجهة ميديا Joyboy
 * محلي ← واجهة ميديا Joyboy · لينك مباشر ← الواجهة اللي فتحته · تلفزيون ← مشغّل التلفزيون.
 * مفيش وجهة متثبّتة على يوتيوب: أي مصدر مش يوتيوب عمره ما يرجّع ليوتيوب.
 * ملاحظة أمانة: التصغير نفسه (سحب لتحت/قرص/زرار) لسه إيماءة MeeTube الأصلية؛
 * اللي هنا هو قرار الوجهة + الرجوع عند الإغلاق الفعلي، وخطاف onMinimize في JoyboyPlayerHost
 * عشان إيماءة MeeTube تستشير المصدر قبل ما تختار الوجهة (بيتوصّل من MobilePlaybackActivity).
 */
object MtOrigin {
    enum class Kind { YOUTUBE, BROWSER, LOCAL, EXTERNAL, DIRECT_URL, PLAYLIST, REMOTE_TV }
    enum class ReturnTo { NONE, BROWSER, YOUTUBE, VIDEOS, FILES, TV_HOME }

    class Origin(
        val kind: Kind,
        val src: String,
        val videoId: String,
        val title: String,
        val ret: String,
        val page: String,
        val sessionId: String,
        val startedMini: Boolean,
        val headers: Map<String, String> = emptyMap(),
        val from: String = ""   // (v1006) الصفحة اللي اتفتح منها الفيديو (NavOrigin)
    )

    @Volatile private var current: Origin? = null

    // (v941) الرجوع لـMain بدأ فعلاً من الشريط (MeeTubeBar.toMain): onDetach ما يضربش تنقّل تاني في نص التفكيك
    @Volatile private var returnStarted = false

    fun markReturnStarted() {
        returnStarted = true
    }

    fun current(): Origin? = current

    /**
     * (v979) هل المصدر المسجّل ده بتاع الفيديو اللي شغّال دلوقتي فعلاً؟
     * السبب: record() بيتنده بس لما الفيديو يتفتح من Joyboy (MtPlay.openMt / MeeTubeLink). فيديو يوتيوب اتفتح من فيد MeeTube نفسه
     * عمره ما اتسجّل، فكان بيفضل شايل مصدر قديم (ملف/متصفح) من فيديو قبله — والتصغير كان يروح PiP والقفل يرجّع لميديا/المتصفح بدل يوتيوب.
     * ملف/لينك: نفس الـsrc بتاع Video.localUri · يوتيوب: نفس الـvideoId.
     */
    fun isFor(videoId: String?, localUri: String?): Boolean {
        val o = current ?: return false
        return if (!localUri.isNullOrEmpty()) o.src == localUri
        else !videoId.isNullOrEmpty() && o.videoId == videoId
    }

    fun clear(src: String?) {
        try {
            val c = current
            if (c != null && (src == null || c.src == src)) current = null
        } catch (_: Throwable) {}
    }

    /**
     * تصنيف المصدر من الـintent بتاع PlayerActivity + قرار MtPlay.
     * الترتيب مهم: ret الصريح الأول، بعدين ytid، بعدين شكل المصدر.
     */
    fun classify(i: Intent, src: String?, yt: String?): Kind {
        try {
            val ret = i.getStringExtra("ret") ?: ""
            if (ret == "browser") return Kind.BROWSER
            if (yt != null) return Kind.YOUTUBE
            if (ret == "yt") return Kind.YOUTUBE
            if (i.getBooleanExtra("remote", false)) return Kind.REMOTE_TV
            if (i.hasExtra("mirror_uri")) return Kind.REMOTE_TV
            val s = src ?: ""
            val low = s.lowercase()
            if (low.startsWith("http://") || low.startsWith("https://")) {
                if (i.getStringExtra("page")?.isNotBlank() == true) return Kind.BROWSER
                return Kind.DIRECT_URL
            }
            val data = i.data
            if (data != null && data.scheme == "content") {
                val a = i.action ?: ""
                if (a == Intent.ACTION_VIEW || a == Intent.ACTION_SEND) return Kind.EXTERNAL
                return Kind.LOCAL
            }
            if (low.startsWith("file://") || low.startsWith("/storage/") || low.startsWith("/sdcard/")) return Kind.LOCAL
            if (i.hasExtra("qlist") || i.getStringExtra("url")?.isNotBlank() == true) return Kind.DIRECT_URL
            return Kind.LOCAL
        } catch (_: Throwable) {
            return Kind.LOCAL
        }
    }

    /** (v1006) الصفحة المسجّلة ← وجهة الرجوع */
    private fun fromDest(from: String): ReturnTo? = when (from) {
        "yt" -> ReturnTo.YOUTUBE
        "browser" -> ReturnTo.BROWSER
        "files" -> ReturnTo.FILES
        "videos", "music", "tasks", "settings" -> ReturnTo.VIDEOS
        else -> null
    }

    private fun tabOf(from: String): String = if (from == "music" || from == "tasks" || from == "settings") from else "videos"

    /** وجهة الرجوع: الصفحة اللي فتحت منها الفيديو (ret الصريح الأول، وبعدين الصفحة المسجّلة). */
    fun destination(o: Origin): ReturnTo {
        return when (o.ret) {
            "browser" -> ReturnTo.BROWSER
            "yt" -> ReturnTo.YOUTUBE
            else -> when (o.kind) {
                Kind.BROWSER -> ReturnTo.BROWSER
                Kind.YOUTUBE -> fromDest(o.from) ?: ReturnTo.YOUTUBE
                Kind.REMOTE_TV -> ReturnTo.TV_HOME
                Kind.EXTERNAL -> ReturnTo.VIDEOS
                Kind.LOCAL, Kind.DIRECT_URL, Kind.PLAYLIST -> fromDest(o.from) ?: ReturnTo.VIDEOS
            }
        }
    }

    /** بيتنده من MtPlay.openMt قبل openVideo: يسجّل مصدر الجلسة دي. */
    fun record(i: Intent, src: String?, yt: String?, title: String) {
        try {
            returnStarted = false
            val kind = classify(i, src, yt)
            var ret = i.getStringExtra("ret") ?: ""
            val from = NavOrigin.last()
            // (v1006) فيديو يوتيوب اتفتح من صفحة تانية (استوديو/ميديا/ملفات…): الرجوع لصفحته مش ليوتيوب
            if (ret == "yt" && src == null && yt != null && from.isNotEmpty() && from != "yt") ret = ""
            val page = i.getStringExtra("page") ?: ""
            val vid = yt ?: ""
            val mini = i.getBooleanExtra("mini", false) || i.getBooleanExtra("pip", false)
            val hdr = LinkedHashMap<String, String>()
            i.getStringExtra("ua")?.takeIf { it.isNotEmpty() }?.let { hdr["User-Agent"] = it }
            i.getStringExtra("ref")?.takeIf { it.isNotEmpty() }?.let { hdr["Referer"] = it }
            // (v976) المشغّلات الخارجية (hls.js) بتبعت Origin مع كل طلب — نفس أصل الـReferer، من غير يوتيوب
            hdr["Referer"]?.let { r0 -> if (!r0.contains("youtube.com", true) && !r0.contains("youtu.be", true)) Sniff.originOf(r0)?.let { o0 -> hdr["Origin"] = o0 } }
            i.getStringExtra("cookie")?.takeIf { it.isNotEmpty() }?.let { hdr["Cookie"] = it }
            val prev = current
            val sid = if (prev != null && prev.src == (src ?: "") && prev.kind == kind) prev.sessionId else java.util.UUID.randomUUID().toString().take(12)
            current = Origin(kind, src ?: "", vid, title, ret, page, sid, mini, hdr, from)
        } catch (e: Throwable) {
            LogStore.err("MtOrigin:record", e)
        }
    }

    /**
     * الرجوع عند الإغلاق الفعلي للمشغّل. true = اتنفّذ تنقّل.
     * (v1006) إصلاح: الشرط القديم كان بيفحص `a.isDestroyed` وده دايمًا true جوه onDestroy (اللي منه بيتنده detach)،
     * فالرجوع للمصدر ماكانش بيشتغل أبدًا والنظام كان بيختار صفحة عشوائية. دلوقتي: الإغلاق لازم يبقى والمشغّل ظاهر قدّامك
     * (مش قفل PiP من برّه) والوجهة هي الصفحة اللي فتحت منها الفيديو، بـREORDER_TO_FRONT (من غير ما نقفل صفحات تانية).
     */
    fun onDetach(a: Activity, src: String?, videoId: String? = null): Boolean {
        try {
            if (!a.isFinishing || a.isChangingConfigurations) return false
            val o = current
            if (returnStarted) {
                returnStarted = false
                current = null
                return false
            }
            if (!com.newtube.mobile.ui.playback.MobilePlaybackActivity.closedFromForeground(a)) {
                if (o != null && (src == null || o.src == src)) current = null
                return false
            }
            val dest: ReturnTo
            var tab = "videos"
            if (o != null && ((src != null && (o.src == src || o.src.isEmpty())) || (src == null && isFor(videoId, null)))) {
                dest = destination(o)
                tab = tabOf(o.from)
            } else if (src == null) {
                dest = ReturnTo.YOUTUBE   // فيديو يوتيوب من فيد MeeTube نفسه — مالوش تسجيل: يرجع ليوتيوب
                current = null
            } else {
                current = null            // مصدر قديم مش بتاع الفيديو ده
                return false
            }
            if (dest == ReturnTo.NONE) {
                current = null
                return false
            }
            current = null
            val app = a.applicationContext
            val nt = Intent.FLAG_ACTIVITY_NEW_TASK
            when (dest) {
                ReturnTo.BROWSER -> app.startActivity(TabNav.front(Intent(app, BrowserActivity::class.java)).addFlags(nt))
                ReturnTo.YOUTUBE -> {
                    if (!com.newtube.mobile.ui.browse.MobileBrowseActivity.bringToFront(a)) {
                        a.startActivity(Intent(a, MainActivity::class.java).addFlags(nt or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "youtube"))
                    }
                }
                ReturnTo.VIDEOS -> app.startActivity(TabNav.main(app, tab).addFlags(nt))
                ReturnTo.FILES -> app.startActivity(TabNav.front(FilesHub.intent(app)).addFlags(nt))
                ReturnTo.TV_HOME -> app.startActivity(Intent(app, TvActivity::class.java).addFlags(nt or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                ReturnTo.NONE -> return false
            }
            return true
        } catch (e: Throwable) {
            LogStore.err("MtOrigin:ret", e)
            return false
        }
    }

    /**
     * قرار التصغير المركزي: كل الإيماءات والزراير لازم تسأل هنا الأول.
     * بيرجّع وجهة التصغير من غير ما ينفّذها؛ التنفيذ (مصغّر MeeTube نفسه) لسه عند MeeTube،
     * والوجهة دي هي اللي ترجع لها واجهة الاستعادة/الإغلاق. false = مفيش مصدر مسجّل.
     */
    fun minimizeTarget(): ReturnTo? {
        return try {
            current()?.let { destination(it) }
        } catch (_: Throwable) {
            null
        }
    }
}
