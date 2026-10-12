package com.tttt.subtitler

import android.net.TrafficStats
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * عدّاد استهلاك النت — لجلسة التشغيل الحالية بس (من أول ما البرنامج يتفتح؛ بيتصفّر لما العملية تتقفل).
 * - «فيديوهات عادية» (من الجهاز): اللي اترفع لـ Gemini/المترجم + اللي نزل منهم (الباتشات والردود).
 * - «فيديوهات الإنترنت»: نفس الحاجات دي + تحميل الصوت/الفيديو نفسه (قراءة اللينك والتحميل).
 * `online` بيتظبط لما فيديو يتفتح (أو مهمة خلفية تبدأ): true لو الفيديو من لينك.
 */
object NetUse {
    @Volatile var online = false
    private val locUp = AtomicLong(); private val locDown = AtomicLong()
    private val onUp = AtomicLong(); private val onDown = AtomicLong()
    private val mediaDl = AtomicLong()
    @Volatile private var baseRx = -1L
    @Volatile private var baseTx = -1L

    /** نقطة بداية عدّاد النظام الكلي للتطبيق (بيتنده من أول شاشة) */
    @Synchronized fun init() {
        if (baseRx >= 0) return
        val uid = android.os.Process.myUid()
        baseRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        baseTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
    }

    /** طلب API: up = اللي اترفع، down = الرد */
    fun api(up: Long, down: Long) {
        if (online) { onUp.addAndGet(up); onDown.addAndGet(down) } else { locUp.addAndGet(up); locDown.addAndGet(down) }
    }
    fun api(up: Long, reply: String) = api(up, try { reply.toByteArray(Charsets.UTF_8).size.toLong() } catch (_: Throwable) { reply.length.toLong() })
    /** تحميل صوت/فيديو من لينك (دايمًا بيتحسب على فيديوهات الإنترنت) */
    fun media(n: Long) { if (n > 0) mediaDl.addAndGet(n) }

    fun localTotal() = locUp.get() + locDown.get()
    fun onlineTotal() = onUp.get() + onDown.get() + mediaDl.get()
    fun grand() = localTotal() + onlineTotal()
    fun localUp() = locUp.get(); fun localDown() = locDown.get()
    fun onlineUp() = onUp.get(); fun onlineDown() = onDown.get(); fun mediaDown() = mediaDl.get()

    /** إجمالي التطبيق كله من النظام (بيشمل تشغيل الفيديو الأونلاين نفسه) من وقت فتح البرنامج */
    fun appTotal(): Long {
        if (baseRx < 0) return -1L
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) return -1L
        return (rx - baseRx).coerceAtLeast(0L) + (tx - baseTx).coerceAtLeast(0L)
    }

    fun fmt(b: Long): String = when {
        b < 1024L -> "$b ب"
        b < 1024L * 1024 -> String.format(Locale.US, "%.0f ك.ب", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f م.ب", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f ج.ب", b / 1073741824.0)
    }
    /** سطر صغير للعدّاد جنب عدّاد الجمل */
    fun badgeLine() = "🌐 " + fmt(localTotal()) + " · 🔗 " + fmt(onlineTotal())
    fun report(): String {
        val sb = StringBuilder("📶 استهلاك النت (من ساعة ما فتحت البرنامج)\n\n")
        sb.append("📁 فيديوهات عادية (من الجهاز): ").append(fmt(localTotal())).append('\n')
        sb.append("   رفع: ").append(fmt(localUp())).append("  ·  نزول: ").append(fmt(localDown())).append("\n\n")
        sb.append("🔗 فيديوهات الإنترنت: ").append(fmt(onlineTotal())).append('\n')
        sb.append("   رفع: ").append(fmt(onlineUp())).append("  ·  نزول: ").append(fmt(onlineDown())).append('\n')
        sb.append("   تحميل الفيديو/الصوت: ").append(fmt(mediaDown())).append("\n\n")
        sb.append("المجموع المحسوب: ").append(fmt(grand())).append('\n')
        val app = appTotal()
        if (app >= 0) sb.append("إجمالي التطبيق كله (من النظام، بيشمل مشاهدة الأونلاين): ").append(fmt(app))
        return sb.toString()
    }
}
