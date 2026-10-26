package com.tttt.subtitler

import android.app.Activity
import android.content.Intent

/**
 * (v543 م3/م4) وصف فيديو للتشغيل. نفس حقول المواصفة (م0-ب) + how (video / video_tr) عشان يوتيوب يترجم على التلفزيون.
 * headers مفاتيحها: ref · ua · cookie (نفس /open).
 */
data class MediaSpec(
    val uri: String? = null,
    val url: String? = null,
    val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val title: String = "",
    val ytid: String? = null,
    val startMs: Long = 0L,
    val how: String = ""
)

/**
 * (v543) «وضع التلفزيون» على الموبايل: لو مفعّل، أي فيديو بيتبعت للتلفزيون بدل ما يشتغل على الموبايل.
 * قرار علي: التلفزيون مش متصل = مفيش تشغيل على الموبايل (إعادة المحاولة بس).
 * (v554) بعد الإرسال بس إشعار — مفيش شاشة تحكم على الموبايل (الريموت اتلغى).
 */
object PlayTarget {
    const val KEY = "tv_mode"

    fun enabled(): Boolean = Cfg.bool(KEY, false)

    /**
     * @return true = PlayTarget مسك الطلب (اتبعت للتلفزيون أو اتعرض خطأ) وإنت ما تكمّلش محلي؛
     *         false = وضع التلفزيون مقفول (أو الجهاز نفسه تلفزيون/مفيش حاجة تتبعت) — كمّل السلوك المحلي زي ما كان.
     */
    fun open(act: Activity, spec: MediaSpec): Boolean {
        if (!enabled()) return false
        return force(act, spec)
    }

    /**
     * نفس open بس من غير فحص «وضع التلفزيون»: لما الشخص نفسه اختار التلفزيون (مثلًا من «مكتبة التلفزيون»).
     * false = مفيش حاجة تتبعت (الجهاز نفسه تلفزيون، أو مفيش رابط صالح).
     */
    fun force(act: Activity, spec: MediaSpec): Boolean {
        if (DeviceUi.isTv(act)) return false
        val target = targetUrl(spec) ?: return false
        try { RemoteClient.init(act) } catch (_: Throwable) {}
        if (RemoteClient.lastTv() == null) {
            Notice.show(act, "📺 مفيش تلفزيون متصل — دوس الزرار العايم 📺 أو اخرج من وضع التلفزيون", 4200L)
            return true
        }
        send(act, spec, target)
        return true
    }

    /** الرابط اللي هيتبعت للتلفزيون، أو null لو مفيش حاجة تتبعت (مثلًا ملف محلي على الموبايل) */
    internal fun targetUrl(spec: MediaSpec): String? {
        val id = spec.ytid
        if (!id.isNullOrEmpty()) return YtHistory.watchUrl(id)
        val u = spec.uri
        if (!u.isNullOrEmpty() && LibraryCore.idOf(u) != null) return u
        val w = spec.url
        if (!w.isNullOrEmpty() && (w.startsWith("http://", true) || w.startsWith("https://", true))) return w
        return null
    }

    private fun send(act: Activity, spec: MediaSpec, target: String) {
        Notice.show(act, "📺 بوصّل بالتلفزيون…", 2500L)
        Thread {
            val er: String? = try {
                if (!RemoteClient.ensureConnected(act, 8000L)) "ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else RemoteClient.openLink(
                    target, spec.title, spec.how,
                    spec.headers["ref"] ?: "", spec.headers["ua"] ?: "", spec.headers["cookie"] ?: "", spec.startMs
                )
            } catch (e: Throwable) { LogStore.err("PlayTarget:send", e); "حصلت مشكلة" }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (er == null) {
                    // (v554) مفيش شاشة ريموت/متحكم على الموبايل: الفيديو اشتغل على التلفزيون وبتتحكم من ريموت التلفزيون
                    Notice.show(act, "📺 اتفتح على التلفزيون" + (if (spec.title.isNotBlank()) " — " + spec.title else ""), 3000L)
                } else {
                    GAlert(act).setTitle("⚠ ما اتبعتش للتلفزيون").setMessage(er)
                        .setPositiveButton("🔄 إعادة المحاولة") { _, _ -> send(act, spec, target) }
                        .setNegativeButton("إغلاق", null).show()
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}
