package com.tttt.subtitler

import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * (v832) فحوصات إضافية لـ«فحص الجهاز» في العناية (كان بيفحص 4 حاجات بس). كلها قراءة بس من APIs رسمية — مفيش تعديل ولا حذف.
 * حد أندرويد: مفيش تطبيق يقدر يقرا أو يمسح كاش تطبيق تاني (sandbox + Android/data مقفول من 11). اللي بنقدر عليه:
 * نقيس حجم كاش كل تطبيق (StorageStats بإذن «الوصول لبيانات الاستخدام») ونوديك لشاشة النظام عشان تمسحه.
 */
object CareScanExtra {
    class Item(val emoji: String, val color: Int, val title: String, val sub: String, val target: String)

    private const val MB = 1024L * 1024
    private fun sz(b: Long): String = if (b >= 1024 * MB) String.format(java.util.Locale.US, "%.1f GB", b / (1024.0 * MB)) else "${b / MB} MB"
    private fun names(labels: List<String>, max: Int = 4): String = labels.take(max).joinToString(" · ") + (if (labels.size > max) " …" else "")

    /** المرحلة 4: كاش التطبيقات · تطبيقات كبيرة · تطبيقات مهملة (كلها محتاجة إذن بيانات الاستخدام) */
    fun apps(ctx: Context, apps: List<AppRec>, cancel: () -> Boolean): List<Item> {
        val out = ArrayList<Item>()
        if (!CareAppsRead.usageGranted(ctx)) {
            out.add(Item("📊", CareColors.SKY, "اسمح بـ«الوصول لبيانات الاستخدام»", "عشان أقيس كاش كل تطبيق وأعرف التطبيقات الكبيرة والمهملة", "usage"))
            return out
        }
        val now = System.currentTimeMillis()
        var cacheTotal = 0L
        val caches = ArrayList<Pair<AppRec, Long>>(); val totals = ArrayList<Pair<AppRec, Long>>()
        for (a in apps) {
            if (cancel()) return out
            val st = CareAppsRead.statsOf(ctx, a.pkg) ?: continue
            cacheTotal += st[2]
            if (st[2] > 0) caches.add(a to st[2])
            if (!a.system) totals.add(a to (st[0] + st[1] + st[2]))
        }
        if (cacheTotal >= 100 * MB) {
            val top = caches.sortedByDescending { it.second }.take(3).map { it.first.label + " (" + sz(it.second) + ")" }
            out.add(Item("🧹", CareColors.TERRA, "كاش التطبيقات حوالي ${sz(cacheTotal)}", "الأكبر: " + top.joinToString(" · ") + ". أندرويد مش بيسمح لتطبيق يمسح كاش غيره — اضغط وامسحه من شاشة النظام", "cache"))
        }
        val big = totals.filter { it.second >= 1024 * MB }.sortedByDescending { it.second }
        if (big.isNotEmpty()) out.add(Item("📦", CareColors.PLUM, "${big.size} تطبيق حجمه أكتر من 1 GB", names(big.map { it.first.label + " (" + sz(it.second) + ")" }, 3), "apps"))
        val unused = CareAppLogic.unusedApps(apps, CareAppsRead.lastUsed(ctx, now), now)
        if (unused.isNotEmpty()) {
            val tot = unused.sumOf { u -> totals.firstOrNull { it.first.pkg == u.pkg }?.second ?: 0L }
            out.add(Item("💤", CareColors.SAGE, "${unused.size} تطبيق ما اتفتحش من ${CareAppLogic.UNUSED_DAYS} يوم" + (if (tot > 0) " (حوالي ${sz(tot)})" else ""), names(unused.map { it.label }), "apps"))
        }
        return out
    }

    private fun parsePkgs(raw: String?): List<String> =
        (raw ?: "").split(':').mapNotNull { it.substringBefore('/').trim().takeIf { p -> p.isNotEmpty() } }.distinct()

    private fun opAllowed(ao: AppOpsManager?, op: String, a: AppRec, uid: Int): Boolean = try {
        if (ao == null) false
        else {
            @Suppress("DEPRECATION")
            val m = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(op, uid, a.pkg) else ao.checkOpNoThrow(op, uid, a.pkg)
            m == AppOpsManager.MODE_ALLOWED
        }
    } catch (_: Throwable) { false }

    /** المرحلة 5: إعدادات أمان إضافية (وصول · مدير جهاز · إشعارات · فوق التطبيقات · كل الملفات · تثبيت · VPN · المطور · صحة البطارية) */
    fun settings(ctx: Context, apps: List<AppRec>): List<Item> {
        val out = ArrayList<Item>()
        val byPkg = apps.associateBy { it.pkg }
        fun lab(p: String) = byPkg[p]?.label ?: p
        fun userPkgs(l: List<String>) = l.filter { p -> p != ctx.packageName && byPkg[p]?.system == false }

        try {
            val acc = userPkgs(parsePkgs(Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")))
            if (acc.isNotEmpty()) out.add(Item("♿", CareColors.ROSE, "${acc.size} تطبيق مفعّل له «إمكانية الوصول»", names(acc.map { lab(it) }) + ". الخدمة دي تقدر تقرا الشاشة وتتحكم فيها — سيب اللي تعرفه بس", "security"))
        } catch (_: Throwable) {}
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adm = userPkgs((dpm.activeAdmins ?: emptyList()).map { it.packageName }.distinct())
            if (adm.isNotEmpty()) out.add(Item("🛂", CareColors.ROSE, "${adm.size} تطبيق «مدير جهاز»", names(adm.map { lab(it) }) + ". بيقدر يقفل الجهاز ويمنع الحذف — اتأكد إنك تعرفه", "security"))
        } catch (_: Throwable) {}
        try {
            val nl = userPkgs(parsePkgs(Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")))
            if (nl.isNotEmpty()) out.add(Item("🔔", CareColors.CARAMEL, "${nl.size} تطبيق بيقرا إشعاراتك", names(nl.map { lab(it) }) + ". الإشعارات فيها أكواد تحقق ورسائل", "security"))
        } catch (_: Throwable) {}

        val ao = try { ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager } catch (_: Throwable) { null }
        val ovl = ArrayList<String>(); val all = ArrayList<String>(); val inst = ArrayList<String>()
        for (a in apps) {
            if (a.system || a.pkg == ctx.packageName) continue
            val uid = try { ctx.packageManager.getApplicationInfo(a.pkg, 0).uid } catch (_: Throwable) { continue }
            if ("android.permission.SYSTEM_ALERT_WINDOW" in a.requested && opAllowed(ao, "android:system_alert_window", a, uid)) ovl.add(a.label)
            if (Build.VERSION.SDK_INT >= 30 && "android.permission.MANAGE_EXTERNAL_STORAGE" in a.requested && opAllowed(ao, "android:manage_external_storage", a, uid)) all.add(a.label)
            if ("android.permission.REQUEST_INSTALL_PACKAGES" in a.requested && opAllowed(ao, "android:request_install_packages", a, uid)) inst.add(a.label)
        }
        if (ovl.isNotEmpty()) out.add(Item("🪟", CareColors.CARAMEL, "${ovl.size} تطبيق يرسم فوق التطبيقات التانية", names(ovl) + ". بيتستخدم في الإعلانات المنبثقة وتزييف الشاشات", "security"))
        if (all.isNotEmpty()) out.add(Item("🗂", CareColors.CARAMEL, "${all.size} تطبيق له وصول لكل الملفات", names(all), "security"))
        if (inst.isNotEmpty()) out.add(Item("📥", CareColors.CARAMEL, "${inst.size} تطبيق مسموح له يثبّت تطبيقات", names(inst), "security"))

        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out.add(Item("🛰", CareColors.SKY, "VPN شغّال دلوقتي", "كل الاتصال بيعدّي على خدمة VPN — طبيعي لو إنت شغّلته", "security"))
        } catch (_: Throwable) {}
        try {
            if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
                out.add(Item("🛠", CareColors.CARAMEL, "خيارات المطور مفعّلة", "اقفلها لو مش بتستخدمها (من إعدادات النظام ← خيارات المطور)", "devopts"))
        } catch (_: Throwable) {}
        try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val msg = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "البطارية سخنة زيادة"
                BatteryManager.BATTERY_HEALTH_DEAD -> "النظام مبلّغ إن البطارية تالفة"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "فولت البطارية عالي زيادة"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "النظام مبلّغ عن عطل في البطارية"
                BatteryManager.BATTERY_HEALTH_COLD -> "البطارية باردة جدًا"
                else -> null
            }
            if (msg != null) out.add(Item("🔋", CareColors.ROSE, "صحة البطارية: $msg", "الحالة جاية من نظام أندرويد نفسه", "battery"))
        } catch (_: Throwable) {}
        return out
    }

    /** المرحلة 6: التخزين العام (مؤقت/مصغّرات · APK · ملفات كبيرة) — محتاجة «الوصول لكل الملفات» */
    fun storage(ctx: Context, cancel: () -> Boolean): List<Item> {
        val allowed = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        if (!allowed) return listOf(Item("🗂", CareColors.SKY, "اسمح بـ«الوصول لكل الملفات»", "عشان أفحص التخزين: ملفات مؤقتة وAPK وملفات كبيرة", "clean"))
        val q = CareClean.quick(listOfNotNull(Environment.getExternalStorageDirectory()), cancel)
        if (q.cancelled) return emptyList()
        val out = ArrayList<Item>()
        if (q.junkCount > 0 && q.junkBytes >= 5 * MB) out.add(Item("🗑", CareColors.TERRA, "${q.junkCount} ملف مؤقت وصور مصغّرة حوالي ${sz(q.junkBytes)}", ".tmp · .crdownload · .part · .log · .thumbnails — قابلة للمراجعة والحذف من المنظّف", "clean"))
        if (q.apkCount > 0) out.add(Item("📲", CareColors.PLUM, "${q.apkCount} ملف APK حوالي ${sz(q.apkBytes)}", "ملفات تثبيت قديمة غالبًا مش محتاجها بعد التثبيت", "clean"))
        if (q.largeCount > 0) out.add(Item("📦", CareColors.CARAMEL, "${q.largeCount} ملف كبير (100MB+) حوالي ${sz(q.largeBytes)}", "راجعهم من المنظّف", "clean"))
        return out
    }

    /** نفتح شاشة تنضيف الكاش بتاعة النظام (مفيش API يمسح كاش تطبيقات تانية). بنجرّب أكتر من طريق. */
    fun openCacheScreen(act: Activity) {
        val tries = listOf(Intent("android.os.storage.action.CLEAR_APP_CACHE"), Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_MEMORY_CARD_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) { try { act.startActivity(i); return } catch (_: Throwable) {} }
    }

    fun openUsageAccess(act: Activity) {
        try { act.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Throwable) {}
    }

    fun openDevOptions(act: Activity) {
        try { act.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Throwable) { try { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) {} }
    }
}
