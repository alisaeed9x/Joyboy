package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * (v727) تعديل الإعدادات المشتركة (ترجمة · شكل الترجمة · صوت…) على نسخة التلفزيون بس.
 * - enter: بيجيب قيم التلفزيون الحالية ويحطها في Cfg.tvOv (في الذاكرة بس) ← كل شاشات الإعدادات بتعرض قيم التلفزيون،
 *   وأي تغيير بيتبعت للتلفزيون (عن طريق TvSync) من غير ما إعدادات الموبايل المحفوظة تتغيّر.
 * - exit: بيمسح الوضع ويرجّع إعدادات الموبايل.
 * لو البرنامج اتقفل وهو في الوضع ده: الوضع في الذاكرة بس فبيرجع لإعدادات الموبايل لوحده.
 */
object TvEdit {
    private fun reopenSettings(act: Activity) {
        try {
            act.startActivity(Intent(act, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("tab", "settings"))
        } catch (e: Throwable) { LogStore.err("TvEdit:reopen", e) }
    }

    fun enter(act: Activity) {
        Notice.show(act, "📺 بجيب إعدادات التلفزيون…", 2500L)
        Thread {
            var err = ""
            val ov = ConcurrentHashMap<String, String>()
            try {
                Cfg.ensure(act)
                if (!RemoteClient.ensureConnected(act, 6000L)) err = "ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else {
                    val keys = Cfg.p.all.keys.filter { BrainRpc.allowed(it) }
                    var i = 0
                    while (i < keys.size && err.isEmpty()) {
                        val part = keys.subList(i, minOf(i + 40, keys.size))
                        val (c, t) = RemoteClient.getText("/cfg?keys=" + part.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") })
                        if (c != 200) err = "التلفزيون ما ردّش على الإعدادات (" + c + ") — حدّث البرنامج على التلفزيون لآخر إصدار"
                        else {
                            val cfg = JSONObject(t).optJSONObject("cfg")
                            if (cfg != null) for (k in part) { val v = cfg.optString(k, ""); if (v.isNotEmpty()) ov[k] = v }
                        }
                        i += 40
                    }
                }
            } catch (e: Throwable) { LogStore.err("TvEdit:enter", e); err = "حصلت مشكلة وأنا بجيب إعدادات التلفزيون" }
            act.runOnUiThread {
                if (err.isNotEmpty()) { Notice.show(act, "⚠ " + err, 3800L); return@runOnUiThread }
                Cfg.tvOv = ov
                Notice.show(act, "✏️ بتعدّل إعدادات التلفزيون دلوقتي — الموبايل مش هيتأثر", 3200L)
                reopenSettings(act)
            }
        }.also { it.isDaemon = true }.start()
    }

    fun exit(act: Activity) {
        Cfg.tvOv = null
        Notice.show(act, "↩ رجعت لإعدادات الموبايل", 2200L)
        reopenSettings(act)
    }
}
