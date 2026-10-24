package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout

/**
 * (v417) موزّع الروابط الواحد: أي لينك أو ملف من أي شاشة (المتصفح · الملفات · الكليبورد · مشاركة من برنامج تاني)
 * بيعدّي هنا ويتقرر مكانه: يوتيوب ← بوابة يوتيوب · فيديو مباشر ← مشغّل الفيديو · صوت ← مشغّل الموسيقى · غير كده ← المتصفح.
 * Links.open(...) من MainActivity بتنفّذ على طول؛ من أي شاشة تانية بتبعت Intent لـ MainActivity وهي اللي بتنفّذ (openLinkAs).
 * Links.sheet(...) = نافذة «افتح بـ…» الموحّدة (بتتعرض بضغطة طويلة على لينك).
 */
object Links {
    const val EX_URL = "link_url"
    const val EX_HOW = "link_how"
    const val EX_TITLE = "link_title"
    const val EX_RET = "link_ret"

    const val AUTO = "auto"
    const val VIDEO = "video"
    const val VIDEO_TR = "video_tr"
    const val AUDIO = "audio"
    const val WEB = "web"
    const val DL = "dl"

    enum class Kind { YT, VIDEO, AUDIO, WEB }

    private val YT_RE = Regex("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^\\s#]*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})")
    private val AUD_RE = Regex("\\.(mp3|m4a|aac|wav|flac|ogg|opus|wma|amr)(?=[?#]|$)", RegexOption.IGNORE_CASE)
    private val VID_FILE_RE = Regex("\\.(mp4|mkv|webm|avi|mov|3gp|ts|flv|m4v|wmv|mpg|mpeg)(?=[?#]|$)", RegexOption.IGNORE_CASE)

    fun ytId(u: String): String? = YT_RE.find(u)?.groupValues?.get(1)

    fun kind(u0: String): Kind {
        val u = u0.trim()
        if (ytId(u) != null) return Kind.YT
        val local = u.startsWith("file:", true) || u.startsWith("content:", true) || u.startsWith("/")
        if (local) {
            val p = u.substringBefore('?')
            return when {
                AUD_RE.containsMatchIn(p) -> Kind.AUDIO
                VID_FILE_RE.containsMatchIn(p) -> Kind.VIDEO
                else -> Kind.WEB
            }
        }
        if (Sniff.isDirect(u)) return Kind.VIDEO
        if (u.startsWith("http", true) && AUD_RE.containsMatchIn(u)) return Kind.AUDIO
        return Kind.WEB
    }

    fun isHttp(u: String): Boolean = u.startsWith("http", true)

    /** من أي شاشة: MainActivity بتنفّذ على طول، وغيرها بيبعت Intent لـ MainActivity (CLEAR_TOP) وهي بتنفّذ في onCreate/onNewIntent */
    fun open(act: Activity, url: String, how: String = AUTO, title: String = "", ret: String = "") {
        val u = url.trim()
        if (u.isEmpty()) return
        if (act is MainActivity) { act.openLinkAs(u, how, title, ret); return }
        try {
            act.startActivity(Intent(act, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EX_URL, u).putExtra(EX_HOW, how).putExtra(EX_TITLE, title).putExtra(EX_RET, ret))
        } catch (e: Throwable) { LogStore.err("Links:open", e) }
    }

    /** Track لأي لينك/ملف صوت (مش من الميديا ستور) */
    fun trackOf(u: String, title: String): Track {
        val last = try { Uri.parse(u).lastPathSegment } catch (_: Throwable) { null } ?: ""
        val nm = title.ifBlank { last.substringBeforeLast('.').ifBlank { "صوت" } }
        return Track(("lnk:" + u).hashCode().toLong() or (1L shl 41), u, nm, "", 0L, "lnk", "لينكات")
    }

    /** يشغّل الصوت في مشغّل الموسيقى من غير ما تسيب الشاشة الحالية (المشغّل المصغّر العايم بيظهر) */
    fun playAudioHere(ctx: Context, u: String, title: String = "") {
        try { MusicEngine.play(ctx, listOf(trackOf(u, title)), 0) } catch (e: Throwable) { LogStore.err("Links:audio", e) }
    }

    fun download(act: Activity, u: String, title: String = "") {
        try {
            val name = android.webkit.URLUtil.guessFileName(u, null, null)
            val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            val ck = try { android.webkit.CookieManager.getInstance().getCookie(u) } catch (_: Throwable) { null }
            DmTasks.start(act.applicationContext, u, name, null, ua, ck, "")
            Notice.show(act, "⬇ اتضاف للتحميل — تابعه من المهام: " + name, 2600L)
        } catch (e: Throwable) { LogStore.err("Links:dl", e); Notice.show(act, "التحميل فشل", 2600L) }
    }

    /** (v518) يبعت اللينك للتلفزيون المقترن (يوصّل لو مش متصل) ويشتغل هناك */
    fun sendToTv(act: Activity, u: String) {
        Notice.show(act, "📺 بوصّل بالتلفزيون…", 2500L)
        Thread {
            var msg = ""
            try {
                msg = if (!RemoteClient.ensureConnected(act, 8000L)) "⚠ ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else (RemoteClient.openLink(u)?.let { "⚠ $it" } ?: "✅ اتبعت للتلفزيون")
            } catch (e: Throwable) { LogStore.err("Links:sendToTv", e); msg = "⚠ حصلت مشكلة" }
            try { act.runOnUiThread { Notice.show(act, msg, 2600L) } } catch (_: Throwable) {}
        }.also { it.isDaemon = true }.start()
    }

    fun copy(act: Activity, u: String) {
        try {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", u))
            Notice.show(act, "📋 اتنسخ", 1400L)
        } catch (e: Throwable) { LogStore.err("Links:copy", e) }
    }

    /**
     * نافذة «افتح بـ…» الموحّدة. showWeb=false في المتصفح نفسه (عنده «افتح في تبويب»).
     * before بتتنفّذ قبل ما نسيب الشاشة (المتصفح بيحفظ تبويباته).
     */
    fun sheet(act: Activity, ui: Ui, url: String, title: String = "", ret: String = "", showWeb: Boolean = true, before: () -> Unit = {}) {
        val u = url.trim()
        if (u.isEmpty()) return
        val k = kind(u)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.text(u, 11f, ui.th.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR })
        lateinit var d: Dialog
        fun go(how: String) { d.dismiss(); before(); open(act, u, how, title, ret) }
        when (k) {
            Kind.YT -> {
                box.addView(ui.button("🎬 شغّله فيديو") { go(VIDEO) })
                box.addView(ui.button("🌐 شغّله وترجمه") { go(VIDEO_TR) })
                box.addView(ui.button("🎧 صوت بس (داتا أقل)") { go(AUDIO) })
            }
            Kind.VIDEO -> {
                box.addView(ui.button("▶ شغّل في مشغّل الفيديو") { go(VIDEO) })
                if (isHttp(u)) box.addView(ui.button("⬇ حمّل") { d.dismiss(); download(act, u, title) })
            }
            Kind.AUDIO -> {
                box.addView(ui.button("🎵 شغّل في مشغّل الموسيقى") { go(AUDIO) })
                box.addView(ui.button("▶ شغّل في مشغّل الفيديو") { go(VIDEO) })
                if (isHttp(u)) box.addView(ui.button("⬇ حمّل") { d.dismiss(); download(act, u, title) })
            }
            Kind.WEB -> {
                box.addView(ui.button("▶ جرّب شغّله كفيديو") { go(VIDEO) })
            }
        }
        if (showWeb && isHttp(u)) box.addView(ui.button(if (k == Kind.WEB) "🌐 افتح في المتصفح (يصطاد الفيديوهات)" else "🌐 افتحه في المتصفح") { go(WEB) })
        box.addView(ui.button("📋 انسخ اللينك") { d.dismiss(); copy(act, u) })
        if (isHttp(u) && !DeviceUi.isTv(act)) {   // (v518) ابعت للتلفزيون المقترن (لو فيه واحد)
            try { RemoteClient.init(act); if (RemoteClient.lastTv() != null) box.addView(ui.button("📺 ابعت للتلفزيون") { d.dismiss(); sendToTv(act, u) }) } catch (_: Throwable) {}
        }
        d = ui.sheet(act, "افتح بـ…", listOf<View>(box))
        d.show()
    }
}
