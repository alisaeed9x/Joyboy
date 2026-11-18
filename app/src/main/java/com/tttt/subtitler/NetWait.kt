package com.tttt.subtitler

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/**
 * (v1101) بوابة انتظار النت — الترجمة ما بتبوظش ولا بتلف في تكرار لما النت يقع.
 *
 * الفكرة: لما طلب يفشل بخطأ شكله «نت» (مفيش DNS / مفيش اتصال / قطع)، بنتأكد بفحص حقيقي (probe) إن النت واقع فعلًا.
 *  • لو واقع: الطلب بيستنى في مكانه (مش بيتحسب فاشل ولا بيروح للمفتاح الاحتياطي ولا للفجوات)، والحلقة الرئيسية بتوقّف إرسال باتشات جديدة.
 *  • أول ما الفحص ينجح (السيرفر رد فعلًا) كل حاجة بتكمّل لوحدها من نفس النقطة.
 *  • لو النت شغّال (السيرفر بيرد) والطلب لسه بيفشل: مش «فصل نت» — بتتعامل معاه المعالجة العادية القديمة (إعادة محاولة محدودة ← فشل).
 *
 * الفحص خفيف على الداتا: HEAD بدون جسم لسيرفر جيميناي، ومخزَّن 20ث لما النت شغّال (بيتفحص بس لو فيه اشتباه)، وكل 3ث وهو واقع.
 */
object NetWait {
    private const val PROBE_TIMEOUT_MS = 4_000
    private const val DOWN_POLL_MS = 3_000L      // النت واقع: فحص كل 3ث
    private const val SUSPECT_POLL_MS = 5_000L   // الشبكة متوصلة بس مش «متحقق منها» (داتا خلصت/كابتيف): فحص كل 5ث
    private const val STATUS_EVERY_MS = 1_500L

    /** مستمع واحد (الإنجن) بياخد إشعار واحد لما النت يقع وإشعار واحد لما يرجع */
    interface Sink { fun netDown(); fun netUp(downMs: Long) }
    @Volatile var sink: Sink? = null

    @Volatile private var down = false
    @Volatile private var downSince = 0L
    @Volatile private var lastProbeAt = 0L
    @Volatile private var lastProbeOk = true
    @Volatile private var suspectAt = 0L
    private val probing = AtomicBoolean(false)
    private val statusAt = AtomicLong(0L)

    /** الخطأ ده شكله فصل نت؟ (بيتفحص في السلسلة كلها: cause) */
    fun looksNet(e: Throwable?): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 6) {
            if (t is UnknownHostException || t is ConnectException || t is NoRouteToHostException ||
                t is SocketTimeoutException || t is SSLException || t is EOFException) return true
            if (t is SocketException) return true
            val m = (t.message ?: "").lowercase()
            if (t is IOException && (m.contains("unable to resolve host") || m.contains("network is unreachable") ||
                    m.contains("failed to connect") || m.contains("connection abort") || m.contains("connection reset") ||
                    m.contains("unexpected end of stream") || m.contains("software caused") || m.contains("timeout") ||
                    m.contains("timed out") || m.contains("no address associated") || m.contains("connection closed"))) return true
            t = t.cause
        }
        return false
    }

    /** 0 = مفيش شبكة خالص · 1 = شبكة متوصلة بس مش متحقق منها (غالبًا الداتا واقفة) · 2 = تمام */
    private fun sysState(): Int {
        val c = TrWizard.appCtx ?: return 2
        return try {
            val cm = c.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return 0
            if (!nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) 0
            else if (!nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1
            else 2
        } catch (_: Throwable) { 2 }
    }

    private fun hostRoot(): String = try {
        val u = URL(Api.base); u.protocol + "://" + u.host + "/"
    } catch (_: Throwable) { "https://generativelanguage.googleapis.com/" }

    /** أي رد HTTP (حتى 404/403) = السيرفر وصلناله = النت شغّال فعلًا */
    private fun head(url: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS; readTimeout = PROBE_TIMEOUT_MS
                requestMethod = "HEAD"; instanceFollowRedirects = false; useCaches = false
            }
            c.responseCode; true
        } catch (_: Throwable) { false } finally { try { c?.disconnect() } catch (_: Throwable) {} }
    }

    /**
     * فحص حقيقي: سيرفر جيميناي أولًا. لو ما ردّش بس النت العام شغّال (connectivitycheck) يبقى النت مش هو المشكلة
     * (السيرفر محجوب/DNS) → بنعتبره «شغّال» عشان المعالجة العادية تتصرف ومانقعدش نستنى للأبد.
     */
    private fun probe(): Boolean = head(hostRoot()) || head("https://connectivitycheck.gstatic.com/generate_204")

    private fun flipDown() {
        var fire = false
        synchronized(this) { if (!down) { down = true; downSince = System.currentTimeMillis(); fire = true } }
        if (fire) try { sink?.netDown() } catch (_: Throwable) {}
    }

    private fun flipUp() {
        var ms = -1L
        synchronized(this) { if (down) { ms = System.currentTimeMillis() - downSince; down = false; downSince = 0L } }
        if (ms >= 0) try { sink?.netUp(ms) } catch (_: Throwable) {}
    }

    /**
     * النت شغّال دلوقتي؟ (true = كمّل). رخيص: لو النت تمام ومفيش اشتباه بيرجع من غير أي طلب شبكة.
     * @param force يفحص فورًا حتى لو الفحص الأخير جديد (بنستخدمه لما طلب فعلي فشل بخطأ نت)
     */
    fun check(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val s = sysState()
        if (s == 0) { lastProbeOk = false; lastProbeAt = now; flipDown(); return false }   // مفيش شبكة: من غير فحص ولا داتا
        val suspect = suspectAt > lastProbeAt
        val due = force || suspect ||
            (down && now - lastProbeAt >= DOWN_POLL_MS) ||
            (s == 1 && now - lastProbeAt >= SUSPECT_POLL_MS)
        if (!due) return !down
        if (!probing.compareAndSet(false, true)) return !down && lastProbeOk   // خيط تاني بيفحص: خد آخر نتيجة
        try {
            val ok = probe()
            lastProbeOk = ok; lastProbeAt = System.currentTimeMillis()
            if (ok) { suspectAt = 0L; flipUp() } else flipDown()
            return ok
        } finally { probing.set(false) }
    }

    /** طلب فعلي فشل بخطأ شكله نت: true = النت واقع فعلًا (استنى). false = النت شغّال (المشكلة مش فصل) */
    fun stillDown(e: Throwable?): Boolean {
        if (!looksNet(e)) return false
        suspectAt = System.currentTimeMillis()
        return !check(force = true)
    }

    /** للحلقة الرئيسية: true = النت واقع (ماتبعتش باتشات جديدة) */
    fun gateDown(): Boolean = !check()

    /** بيستنى لحد ما النت يرجع (أو alive تبقى false). onTick بيتنده تقريبًا كل ثانية ونص بعدد ملّي ثواني الفصل. بيرجّع alive() */
    fun waitUp(alive: () -> Boolean, onTick: (Long) -> Unit = {}): Boolean {
        while (alive()) {
            if (check()) return true
            val now = System.currentTimeMillis()
            val last = statusAt.get()
            if (now - last >= STATUS_EVERY_MS && statusAt.compareAndSet(last, now)) try { onTick(downMs()) } catch (_: Throwable) {}
            var t = 0
            while (t < 1000 && alive()) { try { Thread.sleep(200) } catch (_: InterruptedException) { return alive() }; t += 200 }
        }
        return false
    }

    fun downMs(): Long = if (down && downSince > 0L) System.currentTimeMillis() - downSince else 0L

    fun statusText(): String {
        val s = (downMs() / 1000).toInt()
        val t = if (s >= 60) "${s / 60}د ${s % 60}ث" else "${s}ث"
        return "📵 مفيش نت (من $t) — الترجمة واقفة ومش هتفشل، هتكمّل لوحدها أول ما النت يرجع"
    }

    /** للاختبارات: تصفير الحالة */
    fun reset() { down = false; downSince = 0L; lastProbeAt = 0L; lastProbeOk = true; suspectAt = 0L }
}
