package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast

/**
 * (v776) اختيار: Joyboy «بلانشر» أو «من غير لانشر» — من نفس التطبيق (من غير نسختين).
 *  - من غير لانشر: لانشر الموبايل (PhoneHomeActivity) بيتعطّل خالص ← مفيش HOME ولا أيقونة «Joyboy Launcher»،
 *    وJoyboy بيتفتح كتطبيق فيديوهات عادي بأيقوناته التلاتة (الرئيسي · المتصفح · يوتيوب).
 *  - بلانشر: بيتفعّل ويبقى متاح كشاشة رئيسية، وأيقونات Joyboy التلاتة موجودة جواه كمان.
 * التبديل من: ضغطة طويلة على اللانشر ← «قفل اللانشر»، أو من إعدادات التطبيق ← «تفعيل اللانشر».
 */
object LauncherMode {
    private const val CLS = "com.tttt.subtitler.phonehome.PhoneHomeActivity"

    private fun cn(c: Context) = ComponentName(c.packageName, CLS)

    /** لانشر الموبايل متاح بس على الموبايل (التلفزيون/التابلت رئيسيتهم غير كده) */
    fun available(c: Context): Boolean = try { c.resources.getBoolean(R.bool.phone_home_enabled) } catch (_: Throwable) { false }

    fun enabled(c: Context): Boolean = try {
        when (c.packageManager.getComponentEnabledSetting(cn(c))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> available(c)
        }
    } catch (_: Throwable) { false }

    fun set(c: Context, on: Boolean) {
        try {
            c.packageManager.setComponentEnabledSetting(
                cn(c),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Throwable) { LogStore.err("LauncherMode:set", e) }
    }

    /** من إعدادات التطبيق: تفعيل اللانشر + عرض فتح إعدادات «الشاشة الرئيسية» عشان يختاره */
    fun enableWithPrompt(a: Activity) {
        set(a, true)
        try {
            AlertDialog.Builder(a)
                .setTitle("اللانشر اتفعّل ✅")
                .setMessage("دلوقتي أيقونة «Joyboy Launcher» ظهرت. عايز تخليه الشاشة الرئيسية الافتراضية؟")
                .setPositiveButton("افتح الإعدادات") { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Throwable) {
                        Toast.makeText(a, "افتح إعدادات الأندرويد ← التطبيقات الافتراضية ← الشاشة الرئيسية", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("لاحقًا", null)
                .show()
        } catch (_: Throwable) {}
    }

    /** قفل اللانشر وفتح Joyboy كتطبيق عادي. [from] لو كان هو شاشة اللانشر بيتقفل */
    fun disableAndOpenApp(a: Activity, finishIt: Boolean) {
        set(a, false)
        try {
            a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Throwable) {}
        Toast.makeText(a, "اللانشر اتقفل — Joyboy بقى تطبيق عادي. تفعّله تاني من الإعدادات ← تفعيل اللانشر", Toast.LENGTH_LONG).show()
        if (finishIt) a.finish()
    }
}
