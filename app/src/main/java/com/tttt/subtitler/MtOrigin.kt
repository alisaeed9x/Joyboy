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
    enum class ReturnTo { NONE, BROWSER, YOUTUBE, VIDEOS, TV_HOME }

    class Origin(
        val kind: Kind,
        val src: String,
        val videoId: String,
        val title: String,
        val ret: String,
        val page: String,
        val sessionId: String,
        val startedMini: Boolean,
        val headers: Map<String, String> = emptyMap()
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

    /** وجهة الرجوع من المصدر. يوتيوب بس اللي بيرجع ليوتيوب؛ أي مصدر تاني لا. */
    fun destination(o: Origin): ReturnTo {
        return when (o.ret) {
            "browser" -> ReturnTo.BROWSER
            "yt" -> ReturnTo.YOUTUBE
            else -> when (o.kind) {
                Kind.BROWSER -> ReturnTo.BROWSER
                Kind.YOUTUBE -> ReturnTo.NONE
                Kind.REMOTE_TV -> ReturnTo.TV_HOME
                Kind.EXTERNAL -> ReturnTo.VIDEOS
                Kind.LOCAL -> ReturnTo.VIDEOS
                Kind.DIRECT_URL -> ReturnTo.VIDEOS
                Kind.PLAYLIST -> ReturnTo.VIDEOS
            }
        }
    }

    /** بيتنده من MtPlay.openMt قبل openVideo: يسجّل مصدر الجلسة دي. */
    fun record(i: Intent, src: String?, yt: String?, title: String) {
        try {
            returnStarted = false
            val kind = classify(i, src, yt)
            val ret = i.getStringExtra("ret") ?: ""
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
            current = Origin(kind, src ?: "", vid, title, ret, page, sid, mini, hdr)
        } catch (e: Throwable) {
            LogStore.err("MtOrigin:record", e)
        }
    }

    /**
     * الرجوع عند الإغلاق الفعلي للمشغّل. true = اتنفّذ تنقّل.
     * بيتنده بعد MtWeb.onDetach (اللي بيغطّي ret للمصطادة) فلو هو رجّع خلاص مانرجّعش تاني.
     * شرط الإغلاق الفعلي (مش تدوير/تغيير سطح) بيتفحص هنا زي MtWeb.
     */
    fun onDetach(a: Activity, src: String?, videoId: String? = null): Boolean {
        try {
            if (!a.isFinishing || a.isDestroyed || a.isChangingConfigurations) return false
            val o = current ?: return false
            if (returnStarted) {
                returnStarted = false
                current = null
                return false
            }
            // (v983) المصدر المسجّل مش بتاع الفيديو اللي بيتقفل ده: ما نرجّعش بيه، ونمسحه عشان ما يفضلش قديم يتلوّث بيه اللي بعده
            if (src != null && o.src != src && o.src.isNotEmpty()) {
                current = null
                return false
            }
            // (v979) فيديو يوتيوب من فيد MeeTube (من غير src) وملوش تسجيل بتاعه: المصدر اللي موجود قديم — ما نرجّعش بيه (كان بيفتح ميديا/المتصفح عند القفل)
            if (src == null && !isFor(videoId, null)) {
                current = null
                return false
            }
            val dest = destination(o)
            if (dest == ReturnTo.NONE) {
                current = null
                return false
            }
            current = null
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            when (dest) {
                ReturnTo.BROWSER -> a.startActivity(Intent(a, BrowserActivity::class.java).addFlags(flags))
                ReturnTo.YOUTUBE -> a.startActivity(Intent(a, MainActivity::class.java).addFlags(flags).putExtra("tab", "youtube"))
                ReturnTo.VIDEOS -> a.startActivity(Intent(a, MainActivity::class.java).addFlags(flags).putExtra("tab", "videos"))
                ReturnTo.TV_HOME -> a.startActivity(Intent(a, TvActivity::class.java).addFlags(flags))
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
