package com.tttt.subtitler

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/** (v827 · Joyboy Care م5) سجل تطبيق واحد: بيانات PackageManager بس، من غير تخمين. */
class AppRec(
    val pkg: String, val label: String, val versionName: String, val versionCode: Long,
    val system: Boolean, val enabled: Boolean, val firstInstall: Long, val lastUpdate: Long,
    val installer: String?, val requested: List<String>, val granted: Set<String>,
    val apkBytes: Long = 0L   // (v828) حجم ملف الـAPK (من غير إذن). الحجم الكامل بييجي من StorageStats بإذن الاستخدام
)

/** مجموعات الأذونات الحساسة: «مُعلن» = في المانيفست · «ممنوح» = المستخدم/النظام وافق. مفيش ادّعاء عن الاستخدام الفعلي. */
object CarePerm {
    class Group(val key: String, val title: String, val perms: Set<String>)
    private fun p(vararg n: String): Set<String> = n.map { "android.permission.$it" }.toSet()
    val GROUPS: List<Group> = listOf(
        Group("camera", "📷 الكاميرا", p("CAMERA")),
        Group("mic", "🎤 المايك", p("RECORD_AUDIO")),
        Group("loc", "📍 الموقع", p("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION")),
        Group("contacts", "👤 جهات الاتصال", p("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS")),
        Group("phone", "📞 الهاتف", p("READ_PHONE_STATE", "CALL_PHONE", "READ_CALL_LOG", "WRITE_CALL_LOG", "ANSWER_PHONE_CALLS", "READ_PHONE_NUMBERS")),
        Group("sms", "💬 الرسائل SMS", p("SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH")),
        Group("notif", "🔔 الإشعارات", p("POST_NOTIFICATIONS")),
        Group("files", "🗂 الملفات والوسائط", p("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED", "MANAGE_EXTERNAL_STORAGE"))
    )

    fun declared(r: AppRec, g: Group): List<String> = r.requested.filter { it in g.perms }
    fun granted(r: AppRec, g: Group): List<String> = r.requested.filter { it in g.perms && it in r.granted }
    fun short(perm: String): String = perm.removePrefix("android.permission.")
}

/** منطق صرف (من غير أندرويد) — متختبر على JVM. */
object CareAppLogic {
    const val UNUSED_DAYS = 90
    const val MIN_AGE_DAYS = 30
    private const val DAY = 86_400_000L

    /** مثبّت بواسطة المستخدم = مش تطبيق نظام (تحديث تطبيق نظام بيفضل «نظام»). */
    fun userApps(l: List<AppRec>) = l.filter { !it.system }
    fun systemApps(l: List<AppRec>) = l.filter { it.system }
    fun disabledApps(l: List<AppRec>) = l.filter { !it.enabled }

    /** غير مستخدم: تطبيق مستخدم، مثبّت من أكتر من 30 يوم، ومفيش استخدام مسجّل في آخر 90 يوم. محتاج إشارة استخدام حقيقية (lastUsed map). */
    fun unusedApps(l: List<AppRec>, lastUsed: Map<String, Long>, now: Long): List<AppRec> =
        userApps(l).filter { r ->
            val used = lastUsed[r.pkg] ?: 0L
            (now - r.firstInstall) >= MIN_AGE_DAYS * DAY && (used <= 0L || now - used >= UNUSED_DAYS * DAY)
        }

    fun matches(r: AppRec, q: String): Boolean {
        val s = q.trim(); if (s.isEmpty()) return true
        return r.label.contains(s, ignoreCase = true) || r.pkg.contains(s, ignoreCase = true)
    }

    fun installerName(pkg: String?): String = when (pkg) {
        null, "" -> "غير معروف (مفيش مصدر مسجّل)"
        "com.android.vending" -> "Google Play"
        "com.sec.android.app.samsungapps" -> "Galaxy Store"
        "com.huawei.appmarket" -> "AppGallery"
        "com.xiaomi.mipicks", "com.xiaomi.market" -> "Xiaomi GetApps"
        "com.amazon.venezia" -> "Amazon Appstore"
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.shell" -> "مثبّت الحزم / ADB (غالبًا APK يدوي)"
        else -> pkg
    }

    /** مؤشرات للمراجعة فقط (مش حكم): تطبيق مستخدم مثبّت يدويًا + أذونات حساسة ممنوحة. */
    fun grantedSensitiveCount(r: AppRec): Int = CarePerm.GROUPS.count { CarePerm.granted(r, it).isNotEmpty() }

    /** (v828) ترتيب القايمة: 0 الاسم · 1 الحجم (الأكبر الأول؛ اللي حجمه مجهول في الآخر) · 2 الأحدث تثبيتًا. */
    fun sorted(l: List<AppRec>, mode: Int, sizes: Map<String, Long>): List<AppRec> = when (mode) {
        1 -> l.sortedWith(compareByDescending<AppRec> { sizes[it.pkg] ?: it.apkBytes }.thenBy { it.label.lowercase() })
        2 -> l.sortedByDescending { it.firstInstall }
        else -> l.sortedBy { it.label.lowercase() }
    }

    /** (v828) مثبّت يدويًا = تطبيق مستخدم مالوش متجر معروف (مثبّت الحزم/ADB/مفيش مصدر). */
    private val STORES = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.mipicks", "com.xiaomi.market", "com.amazon.venezia")
    fun isSideloaded(r: AppRec): Boolean = !r.system && (r.installer.isNullOrEmpty() || r.installer !in STORES)

    /** (v828) أوامر ADB (للنسخ فقط — Joyboy مابينفذهاش). pkg بييجي من PackageManager فمفيش حقن. */
    private fun safePkg(p: String): String = p.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
    fun adbDisable(pkg: String) = "adb shell pm disable-user --user 0 ${safePkg(pkg)}"
    fun adbEnable(pkg: String) = "adb shell pm enable ${safePkg(pkg)}"
}

/** قراءة من PackageManager (محتاجة Thread خلفي: مئات التطبيقات). QUERY_ALL_PACKAGES معلنة أصلًا في المانيفست. */
object CareAppsRead {
    @Suppress("DEPRECATION")
    fun load(ctx: Context): List<AppRec> {
        val pm = ctx.packageManager
        val out = ArrayList<AppRec>()
        val apps = try { pm.getInstalledApplications(0) } catch (_: Throwable) { emptyList<ApplicationInfo>() }
        for (ai in apps) {
            try {
                val pi: PackageInfo = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS)
                val req = pi.requestedPermissions?.toList() ?: emptyList()
                val flags = pi.requestedPermissionsFlags
                val granted = HashSet<String>()
                if (flags != null) for (i in req.indices) if (i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted.add(req[i])
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                val inst: String? = try {
                    if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ai.packageName).installingPackageName else pm.getInstallerPackageName(ai.packageName)
                } catch (_: Throwable) { null }
                val apk = try { java.io.File(ai.sourceDir).length() } catch (_: Throwable) { 0L }
                out.add(AppRec(ai.packageName, pm.getApplicationLabel(ai).toString(), pi.versionName ?: "?", vc, sys, ai.enabled, pi.firstInstallTime, pi.lastUpdateTime, inst, req, granted, apk))
            } catch (_: Throwable) { /* تطبيق اختفى أو مش مسموح — نتخطّاه */ }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /** إذن «الوصول لبيانات الاستخدام» — بيمنحه المستخدم من إعدادات النظام (opt-in). من غيره «غير مستخدم» مش متاح. */
    fun usageGranted(ctx: Context): Boolean = try {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val m = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
                else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        m == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    /** آخر استخدام لكل تطبيق في آخر 90 يوم (لو مفيش سجل مش هيبقى في الخريطة). */
    fun lastUsed(ctx: Context, now: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val list = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - CareAppLogic.UNUSED_DAYS * 86_400_000L, now)
            if (list != null) for (u in list) { val t = u.lastTimeUsed; if (t > (out[u.packageName] ?: 0L)) out[u.packageName] = t }
        } catch (_: Throwable) {}
        return out
    }

    /** (v828) سجل تطبيق واحد من جديد (بعد الرجوع من إعدادات النظام). null = اتحذف/مش ظاهر. */
    @Suppress("DEPRECATION")
    fun one(ctx: Context, pkg: String): AppRec? {
        val pm = ctx.packageManager
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            val req = pi.requestedPermissions?.toList() ?: emptyList()
            val flags = pi.requestedPermissionsFlags
            val granted = HashSet<String>()
            if (flags != null) for (i in req.indices) if (i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted.add(req[i])
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            val inst: String? = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else pm.getInstallerPackageName(pkg)
            } catch (_: Throwable) { null }
            val apk = try { java.io.File(ai.sourceDir).length() } catch (_: Throwable) { 0L }
            AppRec(pkg, pm.getApplicationLabel(ai).toString(), pi.versionName ?: "?", vc, sys, ai.enabled, pi.firstInstallTime, pi.lastUpdateTime, inst, req, granted, apk)
        } catch (_: Throwable) { null }
    }

    /** (v828) [التطبيق, البيانات, الكاش] بالبايت من StorageStatsManager — محتاجة إذن «الوصول لبيانات الاستخدام». null لو مش متاحة. */
    fun statsOf(ctx: Context, pkg: String): LongArray? {
        if (Build.VERSION.SDK_INT < 26) return null
        return try {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as android.app.usage.StorageStatsManager
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            val st = ssm.queryStatsForPackage(ai.storageUuid, pkg, Process.myUserHandle())
            longArrayOf(st.appBytes, st.dataBytes, st.cacheBytes)
        } catch (_: Throwable) { null }
    }

    /** الحجم الكلي (تطبيق + بيانات + كاش) أو null. */
    fun sizeOf(ctx: Context, pkg: String): Long? = statsOf(ctx, pkg)?.let { it[0] + it[1] + it[2] }
}
