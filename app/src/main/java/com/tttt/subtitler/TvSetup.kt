package com.tttt.subtitler

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/**
 * (v549) أذونات نسخة التلفزيون: من غير نافذة كبيرة. أول ما اللانشر يفتح بتعدّي على الأذونات الناقصة واحدة واحدة
 * بالترتيب: الأول اللي بتطلع نافذة صغيرة من السيستم (الفيديوهات · الإشعارات · الميكروفون · الموقع)،
 * وبعدها اللي بتتفتح لها شاشة إعدادات (إمكانية الوصول · تعديل إعدادات النظام · تثبيت التحديث · البطارية).
 * كل إذن بيتطلب مرة واحدة بس (حتى لو رفضته ما بيتكررش كل فتحة). «⋯ المزيد ← 🔐 الأذونات» بيبدأ الدورة من الأول.
 * «الظهور فوق التطبيقات» اتشال من التلفزيون (مش محتاجه).
 */
object TvSetup {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var cur: Activity? = null
    private var total = 0
    private var doneN = 0

    const val RC_NOTIF = 95
    const val RC_MIC = 96
    const val RC_LOC = 97

    /** ask بترجّع true لو فتحت نافذة/شاشة هنستنى الرجوع منها، false = مفيش حاجة اتفتحت فنكمّل على طول */
    private class Step(val id: String, val title: String, val why: String, val granted: (Activity) -> Boolean, val ask: (Activity) -> Boolean)

    private fun perm(a: Activity, p: String): Boolean = try { a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    private fun go(a: Activity, i: Intent): Boolean = try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Throwable) { false }

    private fun req(a: Activity, p: String, rc: Int, tag: String): Boolean =
        try { a.requestPermissions(arrayOf(p), rc); true } catch (e: Throwable) { LogStore.err("TvSetup:$tag", e); false }

    private fun steps(): List<Step> = listOf(
        // ===== الأول: نوافذ السيستم الصغيرة =====
        Step("video", "🎬 قراءة الفيديوهات", "عشان تظهر فيديوهات الفلاشة والذاكرة",
            { a -> VideoScan.hasPermission(a) },
            { a -> req(a, VideoScan.permission(), 11, "video") }),
        Step("notif", "🔔 الإشعارات", "إشعارات المهام والتحديث",
            { a -> Build.VERSION.SDK_INT < 33 || perm(a, Manifest.permission.POST_NOTIFICATIONS) },
            { a -> req(a, Manifest.permission.POST_NOTIFICATIONS, RC_NOTIF, "notif") }),
        Step("mic", "🎤 الميكروفون", "البحث الصوتي",
            { a -> perm(a, Manifest.permission.RECORD_AUDIO) },
            { a -> req(a, Manifest.permission.RECORD_AUDIO, RC_MIC, "mic") }),
        Step("loc", "📍 الموقع", "قايمة شبكات الواي فاي ومواقيت الصلاة",
            { a -> perm(a, Manifest.permission.ACCESS_FINE_LOCATION) },
            { a -> req(a, Manifest.permission.ACCESS_FINE_LOCATION, RC_LOC, "loc") }),
        // ===== بعدين: شاشات إعدادات (تدوس وترجع) =====
        Step("access", "♿ إمكانية الوصول (ريموت الموبايل في كل التطبيقات)", "من غيرها ريموت الموبايل بيشتغل جوه Subtitler بس، وزرار الواي فاي مش هيشتغل بدوسة واحدة",
            { a -> TvAccess.connected || TvAccess.enabled(a) },
            { a ->
                Notice.show(a, "دوّر على «Subtitler — تحكم الريموت في التطبيقات» وشغّله. لو الخيار رمادي: معلومات التطبيق ← ⋮ ← السماح بالإعدادات المقيّدة", 7000L)
                try { TvAccess.openSettings(a); true } catch (_: Throwable) { false }
            }),
        Step("write", "⚙ تعديل إعدادات النظام", "عشان التطبيق يضبط إعدادات زي السطوع والصوت",
            { a -> Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(a) },
            { a -> go(a, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + a.packageName))) }),
        Step("install", "📦 تثبيت التحديث اللي بييجي من الموبايل", "عشان تحديث التلفزيون من نسخة الموبايل يتثبّت",
            { a -> Build.VERSION.SDK_INT < 26 || a.packageManager.canRequestPackageInstalls() },
            { a -> go(a, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }),
        Step("battery", "🔋 عدم تقييد البطارية", "عشان خدمة الريموت ما تتقفلش في الخلفية",
            { a -> try { (a.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(a.packageName) } catch (_: Throwable) { true } },
            { a ->
                val pkg = Uri.parse("package:" + a.packageName)
                // كتير من التلفزيونات مابتدعمش الشاشة دي: نجرّب 3 طرق، ولو كلهم فشلوا نعدّي (مش لازم)
                go(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) ||
                    go(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
                    go(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            })
    )

    private fun tried(): MutableSet<String> = Cfg.str("tv_setup_tried", "").split(',').filter { it.isNotEmpty() }.toMutableSet()

    private fun markTried(id: String) { val t = tried(); t.add(id); Cfg.put("tv_setup_tried", t.joinToString(",")) }

    private fun granted(s: Step, a: Activity): Boolean = try { s.granted(a) } catch (_: Throwable) { true }

    /** الناقص واللي لسه ماتطلبش (بالترتيب) */
    private fun pending(a: Activity): List<Step> { val t = tried(); return steps().filter { it.id !in t && !granted(it, a) } }

    private fun schedule(a: Activity, ms: Long) {
        cur = a
        h.removeCallbacks(nextRun)
        h.postDelayed(nextRun, ms)
    }

    private val nextRun = Runnable {
        val a = cur
        if (a != null && !a.isDestroyed && !a.isFinishing) next(a) else { running = false; cur = null }
    }

    private fun next(a: Activity) {
        val s = pending(a).firstOrNull()
        if (s == null) { finish(a); return }
        markTried(s.id)
        doneN++
        Notice.show(a, "🔐 (" + doneN + "/" + total + ") " + s.title + " — " + s.why, 5000L)
        val launched = try { s.ask(a) } catch (e: Throwable) { LogStore.err("TvSetup:" + s.id, e); false }
        if (!launched) schedule(a, 600L)
    }

    private fun finish(a: Activity) {
        running = false; cur = null
        val miss = steps().filter { !granted(it, a) }
        if (miss.isEmpty()) Notice.show(a, "✅ كل الأذونات تمام", 2500L)
        else Notice.show(a, "⚠ لسه ناقص: " + miss.joinToString("، ") { it.title.substringBefore(" (") } + " — كمّلها من ⋯ المزيد ← 🔐 الأذونات", 6000L)
    }

    private fun start(a: Activity, fresh: Boolean) {
        if (fresh) Cfg.put("tv_setup_tried", "")
        val n = pending(a).size
        if (n == 0) { finish(a); return }
        total = n; doneN = 0; running = true
        schedule(a, 1200L)
    }

    /** بتتندهي من TvHome.onResume: فتحة التطبيق (أو الرجوع من شاشة إعدادات) */
    fun onResume(a: Activity) {
        try {
            if (!DeviceUi.isTv(a)) return
            if (running) { schedule(a, 500L); return }
            if (pending(a).isNotEmpty()) start(a, false)
        } catch (e: Throwable) { LogStore.err("TvSetup:resume", e) }
    }

    /** بتتندهي بعد نتيجة أي طلب إذن */
    fun refresh(a: Activity) { if (running) schedule(a, 300L) }

    /** «⋯ المزيد ← 🔐 الأذونات»: يبدأ الدورة من الأول */
    fun open(a: Activity) { start(a, true) }
}
