package com.tttt.subtitler

import android.app.Activity
import android.content.Intent

/**
 * (v1069) مشغّل MeeTube اتشال خالص: أي فتح لـPlayerActivity (من أي مكان: مكتبة · ملفات · متصفح · نقل · مهام) بيكمّل في مشغّل Joyboy (Player.kt).
 * دوسة الفيديو في MeeTube نفسها بتتوجّه من MtTvRoute (OpenInterceptor) لمشغّل Joyboy — فمشغّل MeeTube ما بيتفتحش من أي طريق.
 * decide() بترجّع skip دايمًا (للّوج بس)، وreroute()/fastOpen() بيرجّعوا false (كمّل بالمسار القديم).
 * (v1068) مفيش مفتاح تبديل: MeeTube كان لفيديوهات يوتيوب بس · كل ما عداها (وكل التلفزيون) = مشغّل Joyboy (Player.kt).
 */
object MtPlay {

    /** (v1069) قرار التوجيه: ok=false دايمًا (مشغّل MeeTube اتشال خالص) · reason = سبب البقاء على مشغّل Joyboy (للّوج). مفيش آثار جانبية. */
    class Decision(val ok: Boolean, val reason: String, val src: String? = null, val headers: Map<String, String> = emptyMap(), val audio: String? = null,
                   val yt: String? = null, val priv: Boolean = false, val forceId: String? = null, val probe: Boolean = false) {   // (v936) yt = يوتيوب · priv = متخفي/موقع محجوب · forceId = هوية قديمة · probe = لينك من غير امتداد محتاج فحص
        companion object { fun skip(reason: String) = Decision(false, reason) }
    }

    /** (v1069) قرار التوجيه: skip دايمًا — مشغّل MeeTube اتشال خالص فكل حاجة بتشتغل في مشغّل Joyboy (Player.kt). بتتنادى جوه try (Cfg.ensure ممكن ترمي). */
    fun decide(act: Activity, i: Intent): Decision {
        Cfg.ensure(act.applicationContext)
        return Decision.skip(R_OFF)
    }

    // (v950) PlayerActivity بتفضل ورا مشغّل MeeTube (شاشة سودا من غير أنيميشن) لحد ما المشغّل الجديد يظهر — قبل كده كانت بتقفل فوراً
    // (v1069) مشغّل MeeTube اتشال فالحاجز مابيتفعّلش دلوقتي (holdFor مابتتظبطش) — الكود فاضل عشان Player.kt بينادي holdForPlayer
    private var holdFor: java.lang.ref.WeakReference<Activity>? = null
    private val hold = android.os.Handler(android.os.Looper.getMainLooper())
    private val closeHeldRun = Runnable { closeHeld() }

    /** true = الـactivity دي (PlayerActivity) متشالة لحد ما المشغّل يظهر، ومتقفلش دلوقتي. */
    fun holdForPlayer(act: Activity): Boolean {
        if (holdFor?.get() !== act) return false
        try { act.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK)) } catch (_: Throwable) {}
        com.newtube.mobile.ui.playback.MobileLocalPlaybackActivity.sOnShown = Runnable { closeHeld() }
        hold.removeCallbacks(closeHeldRun); hold.postDelayed(closeHeldRun, 6000L)   // أمان: لو المشغّل ماظهرش
        return true
    }

    /** بتتنده لما مشغّل MeeTube يظهر (أو بعد مهلة الأمان) — بتقفل PlayerActivity المتشالة من غير أنيميشن. */
    fun closeHeld() {
        hold.removeCallbacks(closeHeldRun)
        val a = holdFor?.get(); holdFor = null
        com.newtube.mobile.ui.playback.MobileLocalPlaybackActivity.sOnShown = null
        if (a != null && !a.isFinishing) try { a.finish(); a.overridePendingTransition(0, 0) } catch (_: Throwable) {}
    }

    private const val R_OFF = "mt_main_player off"

    /** (v1069) مشغّل MeeTube اتشال خالص: مفيش فتح مباشر — المسار القديم (startActivity(PlayerActivity)) بيكمّل دايمًا. */
    fun fastOpen(act: Activity, i: Intent): Boolean {
        return false
    }

    /** (v1069) مشغّل MeeTube اتشال خالص: مفيش إعادة توجيه — مشغّل Joyboy بيكمّل دايمًا. */
    fun reroute(act: Activity, i: Intent): Boolean {
        return false
    }

}
