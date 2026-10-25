package com.tttt.subtitler

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * (v536) شاشة الأذونات بتاعة التلفزيون: أول مرة تفتح اللانشر بتطلع لوحدها وبتعدّي على كل الأذونات اللي النسخة محتاجاها.
 * أهم واحدة: «إمكانية الوصول» — هي اللي بتخلّي ريموت الموبايل يتحكم في أي تطبيق تاني على التلفزيون (مش في Subtitler بس)،
 * وهي كمان اللي بتخلّي زرار الواي فاي يشتغل بدوسة واحدة على أندرويد 10+.
 * تقدر تفتحها تاني في أي وقت من «⋯ المزيد ← 🔐 الأذونات».
 */
object TvSetup {
    private val h = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null
    /** true = المستخدم في نص الدورة (راح لشاشة إعدادات ورجع) فنفتح الشاشة تاني أول ما يرجع */
    private var active = false

    const val RC_NOTIF = 95
    const val RC_MIC = 96
    const val RC_LOC = 97

    private class Step(val title: String, val why: String, val key: Boolean, val granted: (Activity) -> Boolean, val ask: (Activity) -> Unit)

    private fun perm(a: Activity, p: String): Boolean = try { a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    private fun go(a: Activity, i: Intent, fail: String) {
        try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { Notice.show(a, fail, 3500L) }
    }

    private fun steps(): List<Step> = listOf(
        Step("♿ إمكانية الوصول (ريموت الموبايل في كل التطبيقات)", "من غيرها ريموت الموبايل بيشتغل جوه Subtitler بس، وزرار الواي فاي مش هيشتغل بدوسة واحدة", true,
            { a -> TvAccess.connected || TvAccess.enabled(a) },
            { a ->
                Notice.show(a, "دوّر على «Subtitler — تحكم الريموت في التطبيقات» وشغّله. لو الخيار رمادي: «🔓 معلومات التطبيق» ← ⋮ ← السماح بالإعدادات المقيّدة", 6000L)
                TvAccess.openSettings(a)
            }),
        Step("🪟 الظهور فوق التطبيقات", "مؤشر التاتش باد بتاع الموبايل وبعض الرسايل بتظهر فوق أي تطبيق", true,
            { a -> Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(a) },
            { a -> go(a, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.packageName)), "ما قدرتش أفتح شاشة الظهور فوق التطبيقات — افتحها من إعدادات التلفزيون ← التطبيقات") }),
        Step("🎬 قراءة الفيديوهات", "عشان تظهر فيديوهات الفلاشة والذاكرة", true,
            { a -> VideoScan.hasPermission(a) },
            { a -> try { a.requestPermissions(arrayOf(VideoScan.permission()), 11) } catch (e: Throwable) { LogStore.err("TvSetup:video", e) } }),
        Step("🔔 الإشعارات", "إشعارات المهام والتحديث", false,
            { a -> Build.VERSION.SDK_INT < 33 || perm(a, Manifest.permission.POST_NOTIFICATIONS) },
            { a -> try { a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIF) } catch (e: Throwable) { LogStore.err("TvSetup:notif", e) } }),
        Step("🎤 الميكروفون", "البحث الصوتي", false,
            { a -> perm(a, Manifest.permission.RECORD_AUDIO) },
            { a -> try { a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC) } catch (e: Throwable) { LogStore.err("TvSetup:mic", e) } }),
        Step("📦 تثبيت التحديث اللي بييجي من الموبايل", "عشان تحديث التلفزيون من نسخة الموبايل يتثبّت", false,
            { a -> Build.VERSION.SDK_INT < 26 || a.packageManager.canRequestPackageInstalls() },
            { a -> go(a, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)), "ما قدرتش أفتح شاشة تثبيت التطبيقات") }),
        Step("🔋 عدم تقييد البطارية", "عشان خدمة الريموت ما تتقفلش في الخلفية", false,
            { a -> try { (a.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(a.packageName) } catch (_: Throwable) { true } },
            { a -> go(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName)), "التلفزيون ده مبيدعمش الشاشة دي — مش لازم") }),
        Step("📍 الموقع (اختياري)", "قايمة شبكات الواي فاي ومواقيت الصلاة", false,
            { a -> perm(a, Manifest.permission.ACCESS_FINE_LOCATION) },
            { a -> try { a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), RC_LOC) } catch (e: Throwable) { LogStore.err("TvSetup:loc", e) } })
    )

    private fun missing(a: Activity): List<Step> = steps().filter { s -> !(try { s.granted(a) } catch (_: Throwable) { true }) }

    /** بتتندهي من TvActivity.onResume */
    fun onResume(a: Activity) {
        try {
            if (!DeviceUi.isTv(a)) return
            if (active) { h.postDelayed({ if (!a.isDestroyed && !a.isFinishing) show(a) }, 500L); return }
            if (Cfg.str("tv_setup_v536", "0") != "1") {
                Cfg.put("tv_setup_v536", "1")
                if (missing(a).isNotEmpty()) { active = true; h.postDelayed({ if (!a.isDestroyed && !a.isFinishing) show(a) }, 1200L) }
            }
        } catch (e: Throwable) { LogStore.err("TvSetup:resume", e) }
    }

    /** بتتندهي بعد نتيجة أي طلب إذن */
    fun refresh(a: Activity) { if (active || dlg?.isShowing == true) h.postDelayed({ if (!a.isDestroyed && !a.isFinishing) show(a) }, 300L) }

    fun open(a: Activity) { active = true; show(a) }

    private fun close() { try { dlg?.dismiss() } catch (_: Throwable) {}; dlg = null }

    fun show(a: Activity) {
        close()
        try {
            val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(a, th)
            val all = steps()
            val miss = missing(a)
            if (miss.isEmpty()) { active = false; Notice.show(a, "✅ كل الأذونات تمام", 2500L); return }
            val col = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8))
            }
            col.addView(ui.text("دوس على أي خطوة ناقصة وسمح، وارجع هنا تكمّل اللي بعدها. الأهم: «إمكانية الوصول».", 14f, th.muted).apply { setPadding(0, 0, 0, ui.dp(8)) })
            var first: Step? = null
            for (s in all) {
                val ok = try { s.granted(a) } catch (_: Throwable) { true }
                if (!ok && first == null && s.key) first = s
                col.addView(ui.button((if (ok) "✅ " else if (s.key) "⚠ " else "⬜ ") + s.title, !ok && s === first) {
                    if (ok) Notice.show(a, "✅ مسموح خلاص", 1500L) else { Notice.show(a, s.why, 3500L); s.ask(a) }
                })
            }
            if (!(try { steps()[0].granted(a) } catch (_: Throwable) { true })) {
                col.addView(ui.button("🔓 معلومات التطبيق (لو إمكانية الوصول رمادي)") {
                    go(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)), "ما قدرتش أفتح معلومات التطبيق")
                })
            }
            val sv = ScrollView(a).apply { addView(col) }
            dlg = GAlert(a).setTitle("🔐 أذونات نسخة التلفزيون (" + miss.size + " ناقصة)").setView(sv)
                .setPositiveButton("▶ الخطوة الجاية") { _, _ -> val n = miss.firstOrNull { it.key } ?: miss.first(); Notice.show(a, n.why, 3500L); n.ask(a) }
                .setNegativeButton("إغلاق") { _, _ -> active = false }
                .setOnCancelListener { active = false }
                .create().also { it.show() }
        } catch (e: Throwable) { LogStore.err("TvSetup:show", e); active = false }
    }
}
