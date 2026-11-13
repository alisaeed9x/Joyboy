package com.tttt.subtitler

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * (v828 · Joyboy Care) «فحص الحماية» = مراجعة مخاطر بقواعد معلنة، مش مضاد فيروسات: مفيش محرك فحص ولا قاعدة تواقيع،
 * ومفيش رفع بيانات لأي طرف. بتدوّر على إعدادات أمان الجهاز + تطبيقات مثبّتة يدويًا بأذونات حساسة كتير — ده «يستاهل مراجعة» مش «فيروس».
 * منطق صرف (من غير أندرويد) عشان يتختبر على JVM.
 */
object CareSecurity {
    const val SEV_INFO = 0      // معلومة
    const val SEV_REVIEW = 1    // يستاهل مراجعة
    const val SEV_ATTENTION = 2 // يحتاج انتباه (إعداد أمان ناقص)

    class Finding(val id: String, val sev: Int, val title: String, val detail: String, val pkgs: List<String> = emptyList(), val points: Int = 0)
    class Result(val score: Int, val findings: List<Finding>)

    /** حالة الجهاز: null = مقدرناش نقرا (مش بتتخصم). */
    class Env(val screenLock: Boolean?, val patchAgeDays: Int?, val usbDebugging: Boolean?)

    const val FORMULA = "نبدأ من 100 ونخصم بس من اللي اتقاس: مفيش قفل شاشة (−15) · تحديث الأمان أقدم من سنة (−10) أو من 6 شهور (−4) · " +
        "تصحيح USB مفعّل (−5) · كل تطبيق مثبّت يدويًا (مش من متجر) وممنوح له 3 مجموعات أذونات حساسة أو أكتر (−5، بحد أقصى −20). " +
        "ده مراجعة مخاطر بقواعد معلنة مش فحص فيروسات: مفيش محرك ولا قاعدة تواقيع، فمفيش «آمن 100%» ولا «لقينا فيروس»."

    private const val MAX_APP_PENALTY = 20
    /** الأذونات اللي بتتعد في الحساسية للمراجعة (الإشعارات والملفات بره: شائعة جدًا). */
    private val SENSITIVE_KEYS = setOf("camera", "mic", "loc", "contacts", "phone", "sms")

    fun sensitiveGranted(r: AppRec): List<CarePerm.Group> =
        CarePerm.GROUPS.filter { it.key in SENSITIVE_KEYS && CarePerm.granted(r, it).isNotEmpty() }

    fun review(apps: List<AppRec>, env: Env): Result {
        val out = ArrayList<Finding>()
        if (env.screenLock == false) out.add(Finding("lock", SEV_ATTENTION, "مفيش قفل للشاشة", "أي حد يمسك الجهاز يفتحه. فعّل بصمة/نمط/PIN من إعدادات الأمان.", points = -15))
        env.patchAgeDays?.let { d ->
            if (d > 365) out.add(Finding("patch", SEV_ATTENTION, "تحديث الأمان قديم (أكتر من سنة)", "آخر تحديث أمان للنظام عمره حوالي $d يوم. شوف لو في تحديث متاح للجهاز.", points = -10))
            else if (d > 180) out.add(Finding("patch", SEV_REVIEW, "تحديث الأمان أقدم من 6 شهور", "آخر تحديث أمان للنظام عمره حوالي $d يوم.", points = -4))
        }
        if (env.usbDebugging == true) out.add(Finding("adb", SEV_REVIEW, "تصحيح USB مفعّل", "مفيد للمطورين، لكن سيبه مقفول لو مش بتستخدمه (أي كمبيوتر مصرّح له يتحكم في الجهاز).", points = -5))

        val side = apps.filter { CareAppLogic.isSideloaded(it) }
        if (side.isNotEmpty()) out.add(Finding("side", SEV_INFO,
            "${side.size} تطبيق مثبّت يدويًا (مش من متجر)", "مفيش مصدر متجر مسجّل ليهم. ده طبيعي لو إنت اللي ثبّتهم؛ راجع إن كل واحد تعرفه.", side.map { it.pkg }))

        val risky = side.filter { sensitiveGranted(it).size >= 3 }.sortedByDescending { sensitiveGranted(it).size }
        if (risky.isNotEmpty()) {
            val pts = -minOf(MAX_APP_PENALTY, 5 * risky.size)
            out.add(Finding("risky", SEV_REVIEW, "${risky.size} تطبيق يدوي بأذونات حساسة كتير",
                "ممنوح لكل واحد 3 مجموعات أذونات حساسة أو أكتر (كاميرا/مايك/موقع/جهات/هاتف/رسائل). ده مش دليل إنه ضار — بس يستاهل تتأكد إنك واثق فيه.", risky.map { it.pkg }, pts))
        }

        val sms = apps.filter { !it.system && CarePerm.granted(it, CarePerm.GROUPS.first { g -> g.key == "sms" }).isNotEmpty() }
        if (sms.isNotEmpty()) out.add(Finding("sms", SEV_INFO, "${sms.size} تطبيق ممنوح له صلاحيات الرسائل SMS", "الرسائل بتحتوي أكواد تحقق. اتأكد إن التطبيقات دي محتاجاها فعلًا.", sms.map { it.pkg }))

        val score = (100 + out.sumOf { it.points }).coerceIn(0, 100)
        return Result(score, out.sortedWith(compareByDescending<Finding> { it.sev }.thenBy { it.points }))
    }
}

/** قراءة حالة الجهاز (من APIs رسمية). أي قراءة بتفشل بترجّع null. */
object CareSecurityRead {
    fun env(ctx: Context, nowMs: Long = System.currentTimeMillis()): CareSecurity.Env {
        val lock: Boolean? = try {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= 23) km.isDeviceSecure else null
        } catch (_: Throwable) { null }
        val age: Int? = try {
            val p = Build.VERSION.SECURITY_PATCH
            val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(p)
            if (d == null) null else ((nowMs - d.time) / 86_400_000L).toInt().coerceAtLeast(0)
        } catch (_: Throwable) { null }
        val adb: Boolean? = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 } catch (_: Throwable) { null }
        return CareSecurity.Env(lock, age, adb)
    }
}
