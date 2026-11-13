package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * (v884) شريط Joyboy السفلي جوه شاشة تصفّح MeeTube — نفس BottomNav بتاع البرنامج (مش نسخة تانية):
 * بيتبني بثيم Joyboy الحالي (فبيتبع أي ثيم/سكن يتغيّر) وبيتحط في الحاوية joyboy_bottom_bar تحت شريط MeeTube.
 * الدوسة على بوابة بتخرج من MeeTube وترجّع لـ MainActivity على نفس البوابة (CLEAR_TOP + SINGLE_TOP
 * فمفيش نسخة تانية من Main)؛ الدوسة على «يوتيوب» نفسه بتتجاهل (إحنا جواه أصلًا).
 * الريموت/العناية/النت/«المزيد» بيتعاملوا جوه BottomNav نفسه زي البرنامج بالظبط.
 */
object MeeTubeBar {
    fun attach(act: Activity, container: ViewGroup): Boolean {
        try {
            if (container.childCount > 0) return true
            Cfg.ensure(act.applicationContext)
            val th = Glass.theme()
            val ui = Ui(act, th)
            val nav = BottomNav(act, ui, th, 4, false) { i -> go(act, i) }
            if (MtGlass.on() && !DeviceUi.isTablet(act)) {   // (v906) نفس GlassView بتاع MeeTube الأصلي + بيبلّر نفس صفحة MeeTube (mobile_blur_target) — مش طبقة معتمة
                val d = act.resources.displayMetrics.density
                nav.floatGlass()
                val bar = MeeGlass.bar(act, th)
                val srcV = act.findViewById<View>(com.liskovsoft.smartyoutubetv2.tv.R.id.mobile_blur_target)
                if (srcV is com.newtube.mobile.ui.common.GlassSource) bar.setSource(srcV)
                bar.addView(nav.view, FrameLayout.LayoutParams(-1, -2))
                val lensOn = Cfg.bool("glass_lens", false) && com.newtube.mobile.ui.common.NavLens.enabled(act) && container is FrameLayout && com.newtube.mobile.ui.common.NavLens.supported(act)
                val lens = if (lensOn) MeeGlass.lens(act, th) else null   // (v911) نفس عدسة باقي الشاشات
                nav.lensHook = { from, to, settled ->
                    val fx = com.newtube.mobile.ui.common.NavLens.centreInBar(from, bar); val tx = com.newtube.mobile.ui.common.NavLens.centreInBar(to, bar)
                    if (Cfg.bool("glass_jiggle", false) && com.newtube.mobile.ui.common.NavLens.enabled(act)) com.newtube.mobile.ui.common.NavLens.jiggle(bar, if (tx > fx) 1 else -1)   // (v922) الاهتزاز والعدسة اتقفلوا افتراضيًا (لاج)
                    if (lens != null) { lens.play(bar, from, to, Runnable { settled() }); true } else false
                }
                container.addView(bar, FrameLayout.LayoutParams(-1, -2).apply { setMargins((16 * d).toInt(), 0, (16 * d).toInt(), (12 * d).toInt()) })
                if (lens != null) (container as FrameLayout).addView(lens, FrameLayout.LayoutParams((1 * d).toInt().coerceAtLeast(1), (1 * d).toInt().coerceAtLeast(1), android.view.Gravity.TOP or android.view.Gravity.START))
                return true
            }
            container.addView(nav.view, FrameLayout.LayoutParams(-1, -2))
            return true
        } catch (e: Throwable) {
            LogStore.err("MeeTubeBar:attach", e)
            return false
        }
    }

    private fun go(act: Activity, i: Int) {
        try {
            if (i == 0 || i == 1 || i == 2 || i == 3 || i == 5 || i == 7) NavAnim.set(4, i)   // (v911) نفس أنيميشن انتقال التبويبات بدل بدون حركة
            when (i) {
                0 -> toMain(act, "videos")
                1 -> toMain(act, "music")
                2 -> openActivity(act, TabNav.front(Intent(act, BrowserActivity::class.java)))
                3 -> openActivity(act, TabNav.front(FilesHub.intent(act)))
                5 -> toMain(act, "tasks")
                7 -> toMain(act, "settings")
                else -> return
            }
        } catch (e: Throwable) {
            LogStore.err("MeeTubeBar:go", e)
        }
    }

    private fun toMain(act: Activity, tab: String) {
        // (v941) قبل ما نرجّع لـMain: لو فيه مصغّر MeeTube شغّال نوقّفه الأول، ونعلّم إن الرجوع بدأ
        // عشان onDetach بتاع المشغّل ما يضربش تنقّل تاني لـMain في نص تفكيك الـCLEAR_TOP (كان بيعمل force close)
        try {
            MtOrigin.markReturnStarted()
            if (com.newtube.mobile.ui.playback.MiniPlayerBridge.isActive()) {
                com.newtube.mobile.ui.playback.MiniPlayerBridge.close()
            }
        } catch (_: Throwable) {}
        openActivity(act, TabNav.main(act, tab))   // (v973) REORDER بدل CLEAR_TOP: يوتيوب بيفضل شغّال تحت (فيدّه ومكانه زي ما هم)
    }

    private fun openActivity(act: Activity, intent: Intent) {
        act.startActivity(intent)   // (v973) من غير finish: شاشة يوتيوب بتفضل حيّة
        if (!NavAnim.take(act)) act.overridePendingTransition(0, 0)
    }
}
