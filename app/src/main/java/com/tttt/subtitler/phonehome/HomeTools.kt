package com.tttt.subtitler.phonehome

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * (v772) تنضيف الرام: أندرويد ما بيدّيش أي تطبيق عادي يمسح الرام بالقوة، فاللي بنعمله هو اللي النظام نفسه بيعمله
 * لما الرام تضيق: killBackgroundProcesses لكل تطبيق في الخلفية (من غير ما نلمس اللي شغّال قدّامك أو عنده خدمة أمامية
 * زي مشغل موسيقى أو تحميل) + تفريغ كاش الصور بتاع Joyboy + GC. الرقم اللي بيظهر = الفرق في الرام الفاضية قبل وبعد.
 */
object RamCleaner {
    class Result(val killed: Int, val freedMb: Long, val availMb: Long, val totalMb: Long)

    /** (الفاضي، الإجمالي) بالميجا */
    fun info(ctx: Context): Pair<Long, Long> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.availMem / 1048576L) to (mi.totalMem / 1048576L)
    }

    /** لازم يتنادى من خيط خلفي (فيه انتظار قصير عشان القياس بعد القتل). */
    fun clean(ctx: Context): Result {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = info(ctx).first
        val ime: String? = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        } catch (_: Throwable) { null }
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = ctx.packageManager.queryIntentActivities(q, 0)
            .map { it.activityInfo.packageName }
            .toSet()
            .filter { it != ctx.packageName && it != ime }
        var n = 0
        for (p in pkgs) {
            try { am.killBackgroundProcesses(p); n++ } catch (_: Throwable) {}
        }
        try { System.gc() } catch (_: Throwable) {}
        try { Thread.sleep(900L) } catch (_: Throwable) {}
        val now = info(ctx)
        return Result(n, (now.first - before).coerceAtLeast(0L), now.first, now.second)
    }
}
