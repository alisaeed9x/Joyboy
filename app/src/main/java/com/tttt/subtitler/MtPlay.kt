package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
import com.newtube.mobile.MobileMainApplication

/**
 * (v923) مشغّل يوتيوب (MeeTube) هو المشغّل الأساسي لكل الميديا — البداية: الملفات المحلية واللينكات المباشرة.
 * أي فتح لـPlayerActivity (من أي مكان: مكتبة · ملفات · متصفح · نقل · مهام) بيعدّي هنا الأول (PlayerActivity.onCreate):
 * لو الحالة مدعومة بيفتح الفيديو في مشغّل MeeTube (Video.localUri) ومحرك الترجمة + أزرار Joyboy (MtTranslate) فوقه، ومشغّل Joyboy القديم بيتقفل.
 * غير كده (تلفزيون · ريموت · يوتيوب · لينك محتاج Referer/Cookie · وضع متخفي · ملف تحميل لسه شغّال) = مشغّل Joyboy زي ما هو.
 * مفتاح الإيقاف: Cfg mt_main_player (الافتراضي true من v934 — م2 من خطة دمج المشغّل).
 */
object MtPlay {
    const val KEY = "mt_main_player"
    private val MEDIA_EXT = Regex("\\.(mp4|m4v|mkv|webm|mov|m3u8|mpd|ts|3gp|avi|flv)(\\?|#|$)", RegexOption.IGNORE_CASE)

    fun enabled(): Boolean = try { Cfg.bool(KEY, true) } catch (_: Throwable) { true }   // (v934 م2) افتراضي: شغّال — MeeTube هو مشغّل الملفات واللينكات؛ مفتاح الرجوع لمشغّل Joyboy في إعدادات المشغّل

    /** (v930) قرار التوجيه: ok=true ← يتفتح في MeeTube بالمصدر src · ok=false ← reason = سبب البقاء على مشغّل Joyboy (للّوج). مفيش آثار جانبية. */
    class Decision(val ok: Boolean, val reason: String, val src: String? = null, val headers: Map<String, String> = emptyMap(), val audio: String? = null,
                   val yt: String? = null, val priv: Boolean = false, val forceId: String? = null, val probe: Boolean = false) {   // (v936) yt = يوتيوب · priv = متخفي/موقع محجوب · forceId = هوية قديمة · probe = لينك من غير امتداد محتاج فحص
        companion object { fun skip(reason: String) = Decision(false, reason) }
    }

    /** (v930) نفس شروط reroute القديمة بالظبط، بس بترجّع السبب. بتتنادى جوه try (Cfg.ensure ممكن ترمي). */
    fun decide(act: Activity, i: Intent): Decision {
        Cfg.ensure(act.applicationContext)
        if (!enabled()) return Decision.skip(R_OFF)
        if (DeviceUi.isTv(act)) return Decision.skip(R_TV)
        if (i.getBooleanExtra("remote", false)) return Decision.skip("extra remote (ريموت — م4)")
        // (v936) لسه على مشغّل Joyboy: mirror_uri (م4 — تلفزيون) بس. يوتيوب/المتخفي/hsite/forceid/لينك من غير امتداد اتحلّوا.
        if (i.hasExtra("mirror_uri")) return Decision.skip("extra mirror_uri (م4 — تلفزيون)")
        // (v936) المتخفي + الموقع المحجوب (hsite): معناهم «ما تسجّلش» مش «محتاج استخراج» — Video.incognito بيقفل تاريخ MeeTube، والترجمة المحفوظة بتتمسح، والسجل العام بيتخطّى
        val priv = i.getBooleanExtra("incognito", false) || hiddenSite(act, i)
        // (v936) يوتيوب: مشغّل MeeTube بيستخرج بنفسه (من غير url/qlist/aurl بتاعة Joyboy) والترجمة بتشتغل على معرّف الفيديو
        val ytid = i.getStringExtra("ytid")
        if (ytid != null && i.getBooleanExtra("handoff", false)) return Decision.skip("handoff (تسليم من المصغّر الشغّال — لسه على Joyboy)")
        if (ytid != null) {
            if (ytid.length != 11) return Decision.skip("ytid مش 11 حرف")
            return Decision(true, "ok", yt = ytid, priv = priv)
        }
        if (i.hasExtra("ytpend")) return Decision.skip("ytpend من غير ytid")
        val forceId = i.getStringExtra("forceid")?.takeIf { it.isNotEmpty() }   // (v936) رجعنا من «إصلاح اللينك»: نفس هوية الفيديو القديم (ترجمة/تقدم) على اللينك الجديد
        // (v934 م2) هيدرز اللينك (Referer/UA/Cookie) + الصوت المنفصل بقوا مدعومين في مشغّل MeeTube
        val hdr = LinkedHashMap<String, String>()
        i.getStringExtra("ua")?.takeIf { it.isNotEmpty() }?.let { hdr["User-Agent"] = it }
        i.getStringExtra("ref")?.takeIf { it.isNotEmpty() }?.let { hdr["Referer"] = it }
        i.getStringExtra("cookie")?.takeIf { it.isNotEmpty() }?.let { hdr["Cookie"] = it }
        val aurlRaw = i.getStringExtra("aurl")?.takeIf { it.isNotEmpty() }
        val aurl = aurlRaw?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (aurlRaw != null && aurl == null) return Decision.skip("aurl مش http(s)")
        val data = i.data
        val url = i.getStringExtra("url")
        val src: String = when {
            data != null -> {
                val sc = data.scheme ?: return Decision.skip("data بدون scheme")
                if (sc != "content" && sc != "file") return Decision.skip("data scheme=$sc")
                if (sc == "file" && (data.path ?: "").let { it.endsWith(".part") || it.contains(".part") }) return Decision.skip("ملف .part (تحميل شغّال)")   // مشغّل Joyboy بيعرض الجزء المحمّل
                data.toString()
            }
            !url.isNullOrBlank() -> {
                if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return Decision.skip("url مش http(s)")
                if (!MEDIA_EXT.containsMatchIn(url) && i.getBooleanExtra(EX_PROBED, false)) return Decision.skip("url مش ميديا (الفحص رفضه)")   // (v936)
                url
            }
            else -> return Decision.skip("مفيش data ولا url")
        }
        if ((hdr.isNotEmpty() || aurl != null) && !(src.startsWith("http://", true) || src.startsWith("https://", true))) return Decision.skip("هيدرز/صوت منفصل على ملف محلي")
        val probe = data == null && !MEDIA_EXT.containsMatchIn(src) && !i.getBooleanExtra(EX_PROBED, false)   // (v936) لينك من غير امتداد: فحص Content-Type في الخلفية قبل ما نقرر
        return Decision(true, "ok", src, hdr, aurl, null, priv, forceId, probe)
    }

    private const val EX_PROBED = "mt_probed"

    /** (v936) الموقع محجوب بزرار 🚨؟ (extra hsite أو دومين اللينك/الـref في قايمة SiteBlock) — نفس شرط Player.hiddenSite */
    private fun hiddenSite(act: Activity, i: Intent): Boolean = try {
        i.getBooleanExtra("hsite", false) ||
            listOf(i.getStringExtra("url"), i.getStringExtra("ref")).any { u -> SiteBlock.keyOf(u)?.let { k -> SiteBlock.has(act, k) } == true }
    } catch (_: Throwable) { false }

    /** (v936) أول بايتات الملف: MP4/MOV (ftyp) · MKV/WebM (EBML) · TS (0x47) · HLS (#EXTM3U) · AVI (RIFF) · FLV */
    private fun sniffMedia(b: ByteArray, n: Int): Boolean {
        if (n >= 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()) return true
        if (n >= 4 && (b[0].toInt() and 255) == 0x1A && (b[1].toInt() and 255) == 0x45 && (b[2].toInt() and 255) == 0xDF && (b[3].toInt() and 255) == 0xA3) return true
        if (n >= 1 && (b[0].toInt() and 255) == 0x47) return true
        if (n >= 7 && String(b, 0, 7, Charsets.ISO_8859_1) == "#EXTM3U") return true
        if (n >= 4 && String(b, 0, 4, Charsets.ISO_8859_1).let { it == "RIFF" || it == "FLV\u0001" }) return true
        return false
    }

    /** (v936) هل اللينك ده ميديا فعلاً؟ (Range 0-15 + Content-Type + sniff). بيتنده من خيط خلفي بس. */
    private fun probeMedia(url: String, hdr: Map<String, String>): Boolean {
        var c: java.net.HttpURLConnection? = null
        return try {
            c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 7000; readTimeout = 7000; instanceFollowRedirects = true; requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-15")
                for ((k, v) in hdr) setRequestProperty(k, v)
            }
            if (c.responseCode !in 200..299) return false
            val ct = (c.contentType ?: "").lowercase().substringBefore(';').trim()
            if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("mpegurl") || ct.contains("dash+xml") || ct == "application/mp4" || ct == "application/ogg") return true
            if (ct.startsWith("text/html") || ct.contains("json") || ct.startsWith("image/")) return false
            val buf = ByteArray(16); var n = 0
            c.inputStream.use { ins -> while (n < 16) { val r = ins.read(buf, n, 16 - n); if (r <= 0) break; n += r } }
            sniffMedia(buf, n)
        } catch (_: Throwable) { false } finally { try { c?.disconnect() } catch (_: Throwable) {} }
    }

    private const val R_OFF = "mt_main_player off"
    private const val R_TV = "tv"

    /** true = اتفتح في مشغّل MeeTube (أو هيتفتح بعد الفحص) والـPlayerActivity لازم تقفل نفسها */
    fun reroute(act: Activity, i: Intent): Boolean {
        try {
            val d = decide(act, i)
            if (!d.ok) {
                if (d.reason != R_OFF && d.reason != R_TV) LogStore.add("MtPlay: skip — ${d.reason}")   // (v930) السبب في اللوج (الحالتين الأوليين عادية فمش بنسجّلها)
                return false
            }
            if (d.probe) {   // (v936) لينك من غير امتداد: نفحصه في الخلفية؛ ميديا ← MeeTube، غير كده ← نرجّعه لمشغّل Joyboy بنفس الـintent
                val src = d.src ?: return false
                val app = act.applicationContext
                val copy = Intent(i).putExtra(EX_PROBED, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Thread {
                    val ok = probeMedia(src, d.headers)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            if (ok) { LogStore.add("MtPlay: probe ok — MeeTube"); openMt(act, app, i, d) }
                            else { LogStore.add("MtPlay: probe — مش ميديا، مشغّل Joyboy"); app.startActivity(copy) }
                        } catch (e: Throwable) { LogStore.err("MtPlay:probe", e); try { app.startActivity(copy) } catch (_: Throwable) {} }
                    }
                }.apply { isDaemon = true }.start()
                return true
            }
            return openMt(act, act.applicationContext, i, d)
        } catch (e: Throwable) {
            LogStore.err("MtPlay:reroute", e)
            return false
        }
    }

    /** (v936) فتح فعلي في MeeTube (بعد القرار/الفحص). false = كمّل بمشغّل Joyboy. */
    private fun openMt(act: Activity, app: android.content.Context, i: Intent, d: Decision): Boolean {
        val startMs = i.getIntExtra("startms", 0).toLong().takeIf { it > 0L } ?: i.getLongExtra("startms", 0L)
        d.yt?.let { yid ->   // (v936) يوتيوب
            val tr = i.getBooleanExtra("autotr", false) && !i.getBooleanExtra("nosub", false)
            if (!d.priv) try { PubHistory.addVideoFrom(i) } catch (_: Throwable) {}
            return MeeTubeLink.playYt(act, yid, startMs, tr, d.priv)
        }
        val src = d.src ?: return false
        val title = i.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: nameOf(act, src)
        if (i.getBooleanExtra("autotr", false) && !i.getBooleanExtra("nosub", false)) MtTranslate.autoFor = "L:$src"
        if (d.priv) MtTranslate.privIds.add("L:$src")
        d.forceId?.let { MtTranslate.idOverride["L:$src"] = it }   // (v936) forceid
        // (v938) لينك من النت: هوية مشغّل Joyboy (u:…) + تسجيل المصطادة + extra ret (بيكتب فوق forceid بنفس القيمة لو موجود)
        try { MtWeb.onOpen(app, i, src, d.headers, d.forceId, i.getBooleanExtra("incognito", false), d.priv && !i.getBooleanExtra("incognito", false)) } catch (_: Throwable) {}
        if (!d.priv) try { PubHistory.addVideoFrom(i) } catch (_: Throwable) {}   // (v936) السجل العام (كان بيتخطّى مع التوجيه لـMeeTube)
        MeeTubeLink.bindBar()
        MobileMainApplication.boot(act.application)
        val v = Video()
        v.videoId = "joy_local_" + Integer.toHexString(src.hashCode())
        v.title = title
        v.localUri = src
        v.incognito = d.priv   // (v936) MeeTube ما يسجّلش تاريخ/استكمال للجلسة دي
        if (startMs > 0L) { v.pendingPosMs = startMs; MtMedia.noResume.add("L:$src") }   // (v937) startms صريح ← مفيش استكمال من عندنا
        if (d.headers.isNotEmpty()) v.headers = HashMap(d.headers)   // (v934)
        v.audioUrl = d.audio
        PlaybackPresenter.instance(app).openVideo(v)
        return true
    }

    private fun nameOf(act: Activity, s: String): String {
        try {
            val u = Uri.parse(s)
            if (u.scheme == "content") act.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.') }
            return (u.lastPathSegment ?: s).substringBeforeLast('.')
        } catch (_: Throwable) { return s }
    }
}
