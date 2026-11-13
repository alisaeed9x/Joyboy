package com.tttt.subtitler

import android.app.Activity
import android.content.Intent

/**
 * (v954) فصل المشغّلين — كل واحد شغّال لوحده:
 *   • مشغّل MeeTube (يوتيوب) = فيديوهات يوتيوب العادية بس (ytid من غير ترجمة).
 *   • مشغّل Joyboy القديم (Player.kt = بتاع v863) = الملفات المحلية + لينكات المتصفح/المستطيل + أي لينك مباشر + يوتيوب بترجمة + التلفزيون/الريموت.
 * كل فتح لـPlayerActivity بيعدّي هنا الأول (PlayerActivity.onCreate): لو القرار ok ← يوتيوب في MeeTube والـPlayerActivity بتقفل نفسها،
 * غير كده القرار skip ← PlayerActivity بتكمّل كمشغّل Joyboy القديم زي ما هي (مفيش أي تغيير في سلوكها).
 * (اتشال من هنا: توجيه الملفات/اللينكات/المتخفي/hsite/forceid/probe لـMeeTube + MtWeb.onOpen + التوجيه الرأسي المسبق — كانوا سبب الأزرار المبعترة.)
 * مفتاح الإيقاف: Cfg mt_main_player (الافتراضي true) — false = يوتيوب كمان على مشغّل Joyboy.
 */
object MtPlay {
    const val KEY = "mt_main_player"

    fun enabled(): Boolean = try { Cfg.bool(KEY, true) } catch (_: Throwable) { true }

    /** قرار التوجيه: ok=true ← يوتيوب في MeeTube (yt = معرّف الفيديو) · ok=false ← reason = سبب البقاء على مشغّل Joyboy القديم (للّوج). مفيش آثار جانبية. */
    class Decision(val ok: Boolean, val reason: String, val yt: String? = null, val priv: Boolean = false) {
        companion object { fun skip(reason: String) = Decision(false, reason) }
    }

    /** extra بيحطّه زرار «ترجم» جوه مشغّل يوتيوب: افتح الفيديو في مشغّل Joyboy القديم (بيتجاوز التوجيه لـMeeTube). */
    const val EX_OLD = "oldplayer"

    /** بتتنادى جوه try (Cfg.ensure ممكن ترمي). */
    fun decide(act: Activity, i: Intent): Decision {
        Cfg.ensure(act.applicationContext)
        if (!enabled()) return Decision.skip(R_OFF)
        if (i.getBooleanExtra("remote", false)) return Decision.skip(R_QUIET)             // ريموت (تلفزيون) ← مشغّل Joyboy
        if (i.hasExtra("mirror_uri")) return Decision.skip(R_QUIET)                      // مرآة التلفزيون ← مشغّل Joyboy
        if (i.getBooleanExtra(EX_OLD, false)) return Decision.skip(R_QUIET)              // زرار «ترجم» جوه يوتيوب ← مشغّل Joyboy القديم
        val ytid = i.getStringExtra("ytid") ?: return Decision.skip(R_QUIET)             // مش يوتيوب (ملف/متصفح/لينك) ← مشغّل Joyboy القديم
        if (i.getBooleanExtra("handoff", false)) return Decision.skip("handoff (تسليم من المصغّر الشغّال — لسه على Joyboy)")
        if (DeviceUi.isTv(act)) return Decision.skip(R_QUIET)                            // التلفزيون زي ما هو (مشغّل Joyboy)
        if (ytid.length != 11) return Decision.skip("ytid مش 11 حرف")
        // يوتيوب بترجمة (autotr من غير nosub) ← مشغّل Joyboy القديم بكل أزراره
        if (i.getBooleanExtra("autotr", false) && !i.getBooleanExtra("nosub", false)) return Decision.skip(R_QUIET)
        val priv = i.getBooleanExtra("incognito", false) || hiddenSite(act, i)
        return Decision(true, "ok", yt = ytid, priv = priv)
    }

    /** الموقع محجوب بزرار 🚨؟ (extra hsite أو دومين اللينك/الـref في قايمة SiteBlock) — نفس شرط Player.hiddenSite */
    private fun hiddenSite(act: Activity, i: Intent): Boolean = try {
        i.getBooleanExtra("hsite", false) ||
            listOf(i.getStringExtra("url"), i.getStringExtra("ref")).any { u -> SiteBlock.keyOf(u)?.let { k -> SiteBlock.has(act, k) } == true }
    } catch (_: Throwable) { false }

    private const val R_OFF = "mt_main_player off"
    private const val R_QUIET = "quiet"

    /** true = اتفتح في مشغّل MeeTube والـPlayerActivity لازم تقفل نفسها */
    fun reroute(act: Activity, i: Intent): Boolean {
        try {
            val d = decide(act, i)
            if (!d.ok) {
                if (d.reason != R_OFF && d.reason != R_QUIET) LogStore.add("MtPlay: skip — ${d.reason}")
                return false
            }
            val yid = d.yt ?: return false
            val startMs = i.getIntExtra("startms", 0).toLong().takeIf { it > 0L } ?: i.getLongExtra("startms", 0L)
            val title0 = i.getStringExtra("title")?.takeIf { it.isNotBlank() }
            try { MtOrigin.record(i, null, yid, title0 ?: "") } catch (_: Throwable) {}
            if (!d.priv) try { PubHistory.addVideoFrom(i) } catch (_: Throwable) {}
            return MeeTubeLink.playYt(act, yid, startMs, false, d.priv)
        } catch (e: Throwable) {
            LogStore.err("MtPlay:reroute", e)
            return false
        }
    }
}
