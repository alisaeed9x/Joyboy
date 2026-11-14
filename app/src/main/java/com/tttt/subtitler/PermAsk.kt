package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

/**
 * (v1010) الصلاحيات الخاصة (اللي بتفتح صفحة إعدادات النظام) ما بتتطلبش أول تثبيت خالص.
 * كل واحدة بتتطلب لما الميزة اللي محتاجاها تتستخدم فعلًا، بنافذة بتشرح:
 *   ١) الميزة دي إيه ومحتاجة الإذن ليه  ٢) فين تلاقي الإذن في إعدادات النظام وتعمله إزاي.
 * - لو المستخدم رفض: الميزة بتتخطّى والبرنامج يكمّل عادي (onSkip).
 * - لو وافق ورجع للبرنامج والإذن اتفعّل: onGranted بتتنفّذ لوحدها.
 */
object PermAsk {
    private var pendingCheck: (() -> Boolean)? = null
    private var pendingGo: (() -> Unit)? = null

    fun install(a: Application) {
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(x: Activity, b: Bundle?) {}
            override fun onActivityStarted(x: Activity) {}
            override fun onActivityResumed(x: Activity) {
                val chk = pendingCheck; val go = pendingGo
                if (chk == null || go == null) return
                pendingCheck = null; pendingGo = null
                try { if (chk()) x.window.decorView.postDelayed({ try { go() } catch (_: Throwable) {} }, 350L) } catch (_: Throwable) {}
            }
            override fun onActivityPaused(x: Activity) {}
            override fun onActivityStopped(x: Activity) {}
            override fun onActivitySaveInstanceState(x: Activity, b: Bundle) {}
            override fun onActivityDestroyed(x: Activity) {}
        })
    }

    private fun pkg(c: Context) = Uri.parse("package:" + c.packageName)

    private fun ask(
        act: Activity, title: String, what: String, steps: String, yes: String, no: String,
        granted: () -> Boolean, launch: () -> Unit, onGranted: () -> Unit, onSkip: () -> Unit
    ) {
        if (granted()) { onGranted(); return }
        if (act.isFinishing || act.isDestroyed) { onSkip(); return }
        GAlert(act).setTitle(title)
            .setMessage(what + "\n\n" + steps)
            .setCancelable(true)
            .setPositiveButton(yes) { _, _ ->
                pendingCheck = granted; pendingGo = onGranted
                try { launch() } catch (_: Throwable) {
                    pendingCheck = null; pendingGo = null
                    try { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(act))) } catch (_: Throwable) {}
                }
            }
            .setNegativeButton(no) { _, _ -> onSkip() }
            .setOnCancelListener { onSkip() }
            .show()
    }

    private const val HOW = "هتفتحلك صفحة إعدادات النظام ← دوّر على اسم البرنامج «Joyboy» في القايمة واضغط عليه ← شغّل المفتاح ← ارجع للبرنامج."

    /** «العرض فوق التطبيقات» — الصورة المصغّرة (PiP) وترجمتها */
    fun overlay(act: Activity, onGranted: () -> Unit, onSkip: () -> Unit) = ask(
        act, "🖼 الصورة المصغّرة (PiP)",
        "الصورة المصغّرة هي نافذة فيديو صغيرة بتفضل فوق التطبيقات التانية وانت بتستخدم الموبايل، ومعاها الترجمة.\nعشان تشتغل لازم إذن «العرض فوق التطبيقات الأخرى».",
        HOW + "\nلو مش عايزها دوس «لا» وهتخرج من الفيديو عادي من غير صورة مصغّرة.",
        "فعّل الإذن", "لا، اخرج عادي",
        { Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(act) },
        { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg(act))) }, onGranted, onSkip
    )

    /** «تعديل إعدادات النظام» — لأي ميزة بتغيّر إعداد في النظام (سطوع/رنّات...) */
    fun systemSettings(act: Activity, feature: String, onGranted: () -> Unit, onSkip: () -> Unit) = ask(
        act, "⚙ تعديل إعدادات النظام",
        "التعديل ده ($feature) محتاج إذن «تعديل إعدادات النظام».",
        HOW, "افتح الإعدادات", "لا، مش دلوقتي",
        { Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(act) },
        { act.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg(act))) }, onGranted, onSkip
    )

    /** «إدارة كل الملفات» — إعادة تسمية/نقل/حذف الفيديوهات من غير ما النظام يسألك كل مرة */
    fun allFiles(act: Activity, onGranted: () -> Unit, onSkip: () -> Unit) = ask(
        act, "📁 إدارة كل الملفات",
        "عشان تغيّر اسم الفيديو أو تنقله أو تحذفه من غير ما النظام يسألك في كل مرة، البرنامج محتاج إذن «إدارة كل الملفات».",
        HOW + "\nلو دوست «لا» العملية هتكمّل بس النظام هيسألك تأكيد كل مرة.",
        "فعّل الإذن", "لا، كمّل عادي",
        { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() },
        { act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg(act))) }, onGranted, onSkip
    )

    /** «تجاهل توفير البطارية» — عشان الترجمة في الخلفية ماتتقفلش. بيسأل مرة كل 24 ساعة بالكتير (مش كل عملية). */
    fun battery(act: Activity, onDone: () -> Unit) {
        val ok = try { (act.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(act.packageName) } catch (_: Throwable) { true }
        if (ok) { onDone(); return }
        val now = System.currentTimeMillis()
        val last = try { Cfg.str("battery_ask_ts", "0").toLongOrNull() ?: 0L } catch (_: Throwable) { 0L }
        if (now - last < 24L * 3600_000L) { onDone(); return }
        try { Cfg.put("battery_ask_ts", now.toString()) } catch (_: Throwable) {}
        ask(
            act, "🔋 تجاهل توفير البطارية",
            "الترجمة في الخلفية ممكن تتقفل لما الشاشة تتقفل لو النظام بيوفّر البطارية. الإذن ده بيخلّيها تكمّل.",
            "هتظهرلك نافذة من النظام: اضغط «سماح».\nلو دوست «لا» الترجمة هتبدأ عادي بس ممكن النظام يقفلها وهي في الخلفية.",
            "فعّل", "لا، ابدأ عادي",
            { try { (act.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(act.packageName) } catch (_: Throwable) { true } },
            { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(act))) },
            { }, { }
        )
        // الترجمة بتبدأ فورًا من غير ما تستنى رد المستخدم على النافذة
        onDone()
    }
}
