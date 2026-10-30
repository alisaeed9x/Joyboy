package com.tttt.subtitler

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** حالات الملف جوه جلسة النقل */
object FtState { const val WAIT = 0; const val RUN = 1; const val DONE = 2; const val FAIL = 3; const val CANCEL = 4 }

/**
 * جهاز ظاهر في الرادار. ft = شغّال عليه بروتوكول النقل الذكي (تشفير + موافقة قبل الاستقبال).
 * link = تلفزيون بيدعم ربط الريموت القديم (v518+) — بيتستخدم كبديل للتلفزيونات اللي لسه ما اتحدّثتش.
 * rtt = زمن الاستجابة بالمللي ثانية (مؤشر جودة الاتصال، مش قوة إشارة الواي فاي).
 */
class FtPeer(
    val id: String, val name: String, val type: String, val ip: String, val port: Int,
    val fp: String, val pub: String, val ver: Int, val rtt: Int,
    val ft: Boolean, val link: RemoteClient.Found? = null
) {
    val isTv: Boolean get() = type == "tv"
}

/** ملف مختار للإرسال: rel = المسار النسبي (بيحفظ شكل الفولدر عند المستلم) · src = مصدر للسجل (مسار أو Uri) */
class FtPick(val rel: String, val size: Long, val uri: Uri, val src: String)

class FtFile(val idx: Int, val rel: String, val size: Long, val cat: String, val srcObj: RangeSource?) {
    @Volatile var state = FtState.WAIT
    @Volatile var done = 0L
    @Volatile var err = ""
    @Volatile var outPath = ""
    @Volatile var accepted = true
    @Volatile var logged = false
    @Volatile var srcRef = ""
    @Volatile var sha = ""
    val name: String get() = rel.substringAfterLast('/')
}

class FtSession(
    val sid: String, val outgoing: Boolean,
    val peerId: String, val peerName: String, val peerType: String, val peerIp: String, val peerPort: Int, val peerFp: String,
    val files: List<FtFile>, val legacy: Boolean = false
) {
    companion object { const val NEG = 0; const val WAITING = 1; const val RUNNING = 2; const val DONE = 3; const val FAILED = 4; const val CANCELLED = 5; const val REJECTED = 6 }

    @Volatile var status = NEG
    @Volatile var msg = ""
    @Volatile var key: ByteArray? = null
    @Volatile var sas = ""
    @Volatile var cancelled = false
    /** (مستقبِل) 0 مستني · 1 قبول · 2 رفض */
    @Volatile var decision = 0
    @Volatile var trustAfter = false
    /** (مستقبِل) 0 فولدرات حسب النوع · 1 Download/Joyboy */
    @Volatile var saveMode = 0
    @Volatile var autoAccepted = false
    @Volatile var retry = false
    @Volatile var startedAt = 0L
    @Volatile var endedAt = 0L
    @Volatile var lastTouch = System.currentTimeMillis()
    @Volatile var speedBps = 0.0
    private var sT = 0L
    private var sB = 0L

    val acceptedFiles: List<FtFile> get() = files.filter { it.accepted }
    val active: Boolean get() = status == NEG || status == WAITING || status == RUNNING
    val ended: Boolean get() = !active

    fun totalBytes(): Long = acceptedFiles.sumOf { it.size }
    fun doneBytes(): Long = acceptedFiles.sumOf { if (it.state == FtState.DONE) it.size else it.done.coerceAtMost(it.size) }
    fun percent(): Int { val t = totalBytes(); return if (t <= 0L) (if (acceptedFiles.all { it.state == FtState.DONE }) 100 else 0) else ((doneBytes() * 100L) / t).toInt().coerceIn(0, 100) }
    fun etaSec(): Long { val left = totalBytes() - doneBytes(); return if (speedBps > 1.0 && left > 0L) (left / speedBps).toLong() else -1L }
    fun doneFiles(): Int = acceptedFiles.count { it.state == FtState.DONE }
    fun failedFiles(): List<FtFile> = acceptedFiles.filter { it.state == FtState.FAIL || it.state == FtState.CANCEL }

    /** بيتنادى كل ~نص ثانية: بيحسب السرعة الفعلية (متوسط متحرك) من البايتات اللي اتنقلت فعلًا */
    fun sample() {
        val now = System.currentTimeMillis()
        val b = doneBytes()
        if (sT == 0L) { sT = now; sB = b; return }
        val dt = now - sT
        if (dt >= 800L) {
            val inst = (b - sB) * 1000.0 / dt
            speedBps = if (speedBps <= 0.0) inst else speedBps * 0.6 + inst * 0.4
            sT = now; sB = b
        }
    }

    fun finish(st: Int, m: String) {
        status = st; msg = m; endedAt = System.currentTimeMillis(); speedBps = 0.0
    }
}

/** مركز الجلسات: بيحفظ الجلسات الحية ويبلّغ الواجهة بأي تغيير (على الخيط الرئيسي) */
object FtHub {
    val sessions = CopyOnWriteArrayList<FtSession>()
    private val ls = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    /** شاشة النقل ظاهرة دلوقتي؟ (لو آه هي بتعرض طلبات الاستقبال بنفسها من غير إشعار) */
    @Volatile var uiVisible = false
    @Volatile var topAct: WeakReference<Activity>? = null

    fun listen(l: () -> Unit) { ls.add(l) }
    fun unlisten(l: () -> Unit) { ls.remove(l) }
    fun changed() { main.post { for (l in ls) { try { l() } catch (_: Throwable) {} } } }
    fun find(sid: String): FtSession? = sessions.firstOrNull { it.sid == sid }

    fun add(s: FtSession) {
        sessions.add(0, s)
        // نشيل القديم المنتهي (نسيب آخر 30)
        val old = sessions.filter { it.ended }.drop(30)
        for (o in old) sessions.remove(o)
        changed()
    }

    fun pendingIncoming(): List<FtSession> = sessions.filter { !it.outgoing && it.status == FtSession.WAITING && it.decision == 0 && !it.cancelled }
}

/** تسجيل نتيجة كل ملف في السجل (مرة واحدة بس لكل ملف) */
object FtLog {
    fun file(s: FtSession, f: FtFile) {
        if (f.logged) return
        if (f.state != FtState.DONE && f.state != FtState.FAIL && f.state != FtState.CANCEL) return
        f.logged = true
        val st = when (f.state) { FtState.DONE -> "ok"; FtState.FAIL -> "fail"; else -> "cancel" }
        try {
            FtStore.add(FtHist(System.currentTimeMillis(), if (s.outgoing) "out" else "in", s.peerName, s.peerId, f.name, f.size, st, if (s.outgoing) f.srcRef else f.outPath, f.err))
        } catch (_: Throwable) {}
    }
    fun all(s: FtSession) { for (f in s.files) file(s, f) }
}

object FtPath {
    /** مسار نسبي آمن: من غير .. ولا / في الأول ولا رموز ممنوعة، وبحد أقصى 8 مستويات */
    fun cleanRel(raw: String): String {
        val segs = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.takeLast(8)
        if (segs.isEmpty()) return ""
        return segs.joinToString("/") { Xfer.safeName(it) }
    }
}

/** HTTP صغير للنقل (blocking — من خيط خلفي) */
object FtNet {
    fun call(ip: String, port: Int, path: String, body: String?, ms: Int, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val (c, b) = callBytes(ip, port, path, body, ms, headers)
        return Pair(c, String(b, Charsets.UTF_8))
    }

    fun callBytes(ip: String, port: Int, path: String, body: String?, ms: Int, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
            c.connectTimeout = minOf(ms, 6000); c.readTimeout = ms; c.useCaches = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val ins = if (code in 200..299) c.inputStream else c.errorStream
            val bo = ByteArrayOutputStream()
            if (ins != null) ins.use { it.copyTo(bo) }
            Pair(code, bo.toByteArray())
        } catch (e: Exception) { Pair(-1, (e.message ?: "").toByteArray(Charsets.UTF_8)) } finally { try { c?.disconnect() } catch (_: Exception) {} }
    }
}
