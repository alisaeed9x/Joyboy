package com.tttt.subtitler

import android.app.Activity

/**
 * (v313) انتقال أفقي بين الشاشات الأربعة (الفيديوهات/الموسيقى · المتصفح · الملفات · المهام).
 * الشريط السفلي RTL: رقم أكبر = ناحية الشمال، فالشاشة الجديدة بتدخل من الشمال والقديمة بتروح يمين (والعكس).
 */
object NavAnim {
    private var enter = 0
    private var exit = 0
    private var has = false

    /** (v336) مكان البوابة في الشريط (يوتيوب بقى قبل الملفات): الأرقام الداخلية 3=ملفات و4=يوتيوب */
    fun pos(i: Int): Int = when (i) { 4 -> 3; 3 -> 4; else -> i }

    fun set(from: Int, to: Int) {
        if (from == to) return
        has = true
        if (pos(to) > pos(from)) { enter = R.anim.nav_in_left; exit = R.anim.nav_out_right }
        else { enter = R.anim.nav_in_right; exit = R.anim.nav_out_left }
    }

    /** (v321) بتطبّق الانتقال فورًا من غير ما تمسحه — بتتنده بعد startActivity مباشرة (قبل finish) عشان الشاشة الجديدة هي اللي تاخده */
    fun peek(act: Activity) {
        if (!has) return
        try { act.overridePendingTransition(enter, exit) } catch (_: Throwable) {}
    }

    /** بتتنده بعد startActivity/finish؛ true لو طبّقت الانتقال الاتجاهي */
    fun take(act: Activity): Boolean {
        if (!has) return false
        has = false
        try { act.overridePendingTransition(enter, exit) } catch (_: Throwable) {}
        return true
    }
}
