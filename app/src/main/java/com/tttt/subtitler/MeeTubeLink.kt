package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import com.newtube.mobile.MobileMainApplication
import com.newtube.mobile.ui.browse.JoyboyBarHost

/**
 * (v878) MeeTube (GPL-3.0، فورك SmartTube للموبايل) مدموج كامل جوه Joyboy — مودولات meetube/ (:mobile · :common · :youtubeapi · …).
 *  • (v888) بوابة يوتيوب بتاعة Joyboy اتمسحت: أي «يوتيوب» (شريط سفلي · لانشر · إعدادات · لينك) بيفتح MeeTube (موبايل/تابلت/تلفزيون). الـApplication بتاعه بيتشغّل Lazy أول فتحة (MobileMainApplication.boot).
 *  • مشغّله وحسابه وإعداداته مستقلة (مشغّل media3 + SABR بتاعه). Back من أول شاشة بيرجّعك لبوابة يوتيوب.
 *  • mt_replace (v880: افتراضي شغّال): لو اتفعّل، دوسة «يوتيوب» من لوحة البداية/الشريط السفلي بتفتح MeeTube بدل بوابة Joyboy (مش على التلفزيون).
 *  • تحديث MeeTube الذاتي (GitHub بتاعه) مقفول؛ التحديث مع Joyboy. روابط يوتيوب/المشاركة مش بتتحوّل له (Joyboy بيتولّاها).
 *  • (v907) الاتجاه اتعكس: ثيم MeeTube (ThemeMode + موارد الألوان) هو المصدر الوحيد، وJoyboy بيقراه (Theme.kt · MeeRes) — مفيش ثيمات ولا سكنات في Joyboy.
 *  • (v884) شريط Joyboy السفلي جوه MeeTube: JoyboyBarHost (في مودول :mobile) بينده MeeTubeBar.attach لما شاشة تصفّح MeeTube تتفتح.
 */
object MeeTubeLink {
    /** (v884) بيربط شريط Joyboy السفلي بشاشة تصفّح MeeTube — بيتنده من JoyApp.onCreate ومن open() (نفس الربط، آمن يتكرر). */
    fun bindBar() {
        try {
            JoyboyBarHost.setProvider { act, container -> MeeTubeBar.attach(act, container) }
            com.newtube.mobile.ui.playback.JoyboyPlayerHost.setProvider(MtTranslate)   // (v890) ترجمة Joyboy فوق مشغّل MeeTube
        } catch (e: Throwable) {
            LogStore.err("MeeTubeLink:bar", e)
        }
    }

    /** (v887) فيديو يوتيوب عادي (من غير ترجمة) على الموبايل/التابلت ← مشغّل MeeTube نفسه (أسرع، والتكبير من غير إعادة تحميل). false = كمّل بمشغّل Joyboy. */
    fun playYt(act: Activity, id: String, startMs: Long, autoTranslate: Boolean = false): Boolean {
        if (DeviceUi.isTv(act) || id.length != 11) return false
        return try {
            if (autoTranslate) MtTranslate.autoFor = id   // (v890) الترجمة تبدأ لوحدها أول ما الفيديو يشتغل في مشغّل MeeTube
            bindBar()
            MobileMainApplication.boot(act.application)
            MtTvRoute.bypassed { com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(act.applicationContext).openVideo(id, false, if (startMs > 0L) startMs else -1L, false) }   // (v928) السؤال «تشغّله فين؟» اتسأل قبل كده في Main.playYt
            true
        } catch (e: Throwable) {
            LogStore.err("MeeTubeLink:playYt", e)
            false
        }
    }

    /** (v928) على التلفزيون: فيديو يوتيوب في مشغّل MeeTube (بييجي من الموبايل عبر TvDirect). false = كمّل بمشغّل Joyboy. */
    fun playYtOnTv(ctx: android.content.Context, id: String, startMs: Long): Boolean {
        if (id.length != 11) return false
        return try {
            bindBar()
            MobileMainApplication.boot(ctx.applicationContext as android.app.Application)
            com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(ctx.applicationContext).openVideo(id, false, if (startMs > 0L) startMs else -1L, false)
            true
        } catch (e: Throwable) {
            LogStore.err("MeeTubeLink:playYtOnTv", e)
            false
        }
    }

    /** (v929) شاشة تسجيل الدخول بتاعة MeeTube (كود الجهاز: بتدوس «كمّل» ← صفحة جوجل/تطبيق يوتيوب ← «السماح» ← ترجع). */
    fun signIn(act: Activity) {
        try {
            bindBar()
            MobileMainApplication.boot(act.application)
            com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter.instance(act).start()
        } catch (e: Throwable) {
            LogStore.err("MeeTubeLink:signIn", e)
            Notice.show(act, "ما قدرتش أفتح تسجيل الدخول: " + (e.message ?: e.javaClass.simpleName).take(60), 3000L)
        }
    }

    fun open(act: Activity) {
        if (DeviceUi.isTv(act)) { try { act.startActivity(Intent(act, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tv_show", "youtube")) } catch (e: Throwable) { LogStore.err("MeeTubeLink:tvYt", e) }; return }   // (v929) على التلفزيون «يوتيوب» = صفحة يوتيوب بتاعة اللانشر (قوايم MeeTube جاية من الموبايل)
        try {
            bindBar()
            MobileMainApplication.boot(act.application)
            if (act is MainActivity) NavAnim.set(0, 4)   // (v911) نفس أنيميشن انتقال التبويبات (mt_tab_enter/exit) بدل act_enter القديم
            act.startActivity(Intent(act, com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity::class.java))
        } catch (e: Throwable) {
            LogStore.err("MeeTubeLink:open", e)
            Notice.show(act, "ما قدرتش أفتح MeeTube: " + (e.message ?: e.javaClass.simpleName).take(60), 3000L)
        }
    }

    /** (v888) لينك يوتيوب (قناة / قايمة / بحث / فيديو) ← MeeTube — بيمرّ على نفس موجّه الروابط بتاعه (SplashActivity). */
    fun openUrl(act: Activity, url: String) {
        try {
            bindBar()
            MobileMainApplication.boot(act.application)
            act.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setClass(act, com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity::class.java))
        } catch (e: Throwable) {
            LogStore.err("MeeTubeLink:openUrl", e)
            Notice.show(act, "ما قدرتش أفتح اللينك في MeeTube: " + (e.message ?: e.javaClass.simpleName).take(60), 3000L)
        }
    }

    /** (v888) إعدادات MeeTube الكاملة (من إعدادات البرنامج العامة) — pageId = null يعني أول قايمة. */
    fun openSettings(act: Activity, pageId: String? = null) {
        try {
            bindBar()
            MobileMainApplication.boot(act.application)
            act.startActivity(com.newtube.mobile.ui.settings.MobileSettingsActivity.intent(act, pageId))
        } catch (e: Throwable) {
            LogStore.err("MeeTubeLink:settings", e)
            Notice.show(act, "ما قدرتش أفتح إعدادات MeeTube: " + (e.message ?: e.javaClass.simpleName).take(60), 3000L)
        }
    }

    /** موبايل/تابلت + mt_replace شغّال ← يفتح MeeTube ويرجّع true (الكود المنادي ما يفتحش بوابة Joyboy). */
    fun openIfReplace(act: Activity): Boolean {
        if (DeviceUi.isTv(act)) return false
        Cfg.ensure(act.applicationContext)
        open(act); return true   // (v886) على الموبايل/التابلت «يوتيوب» = MeeTube دايمًا (بوابة Joyboy القديمة للتلفزيون بس)
    }

    fun menu(act: Activity) {
        open(act)   // (v886) مفيش تبديل: يوتيوب = MeeTube
    }

    fun onTrim(level: Int) { try { MobileMainApplication.booted()?.onTrimMemory(level) } catch (_: Throwable) {} }
    fun onLow() { try { MobileMainApplication.booted()?.onLowMemory() } catch (_: Throwable) {} }
}
