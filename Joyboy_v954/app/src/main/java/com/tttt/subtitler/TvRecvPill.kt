package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * (v718) كبسولة استقبال الملفات على التلفزيون: نفس كبسولة الإرسال اللي على الموبايل (SendPill) — اسم + شريط تقدم + نسبة + سرعة + متبقي —
 * بتظهر فوق أي شاشة ظاهرة على التلفزيون طول ما فيه استقبال شغّال (النقل الذكي أو الربط المباشر)، وبعد ما يخلص بتعرض النتيجة وتختفي.
 * مش بتاخد فوكس (من غير دوسة). بتتنده watch() أول ما يوصل طلب إرسال للتلفزيون.
 */
object TvRecvPill {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var saw = false
    private var t0 = 0L
    private var lastAct: WeakReference<Activity>? = null

    private val tick: Runnable = object : Runnable {
        override fun run() { try { step() } catch (e: Throwable) { running = false; LogStore.err("TvRecvPill", e) } }
    }

    fun watch(app: Context) {
        val tv = try { DeviceUi.isTv(app) } catch (_: Throwable) { false }
        if (!tv) return
        main.post {
            t0 = System.currentTimeMillis()
            if (running) return@post
            running = true; saw = false
            main.post(tick)
        }
    }

    private fun fmt(sec: Long): String { val s = sec.coerceAtLeast(0L); return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60) }

    private fun step() {
        val act = TvLink.topActivity()?.takeIf { !it.isFinishing && !it.isDestroyed }
        val sm = FtHub.sessions.firstOrNull { !it.outgoing && it.active }
        val lg = if (sm == null) TaskCenter.items.firstOrNull { it.kind == "dm" && it.title.startsWith("📥") && (it.state == 0 || it.state == 1) } else null
        if (sm != null || lg != null) {
            saw = true
            if (act != null) {
                lastAct = WeakReference(act)
                if (sm != null) {
                    val going = sm.status == FtSession.RUNNING
                    val pct = sm.percent()
                    val eta = sm.etaSec()
                    val sub = if (!going) "" else sm.doneFiles().toString() + "/" + sm.acceptedFiles.size + " ملف" +
                        (if (sm.speedBps > 1.0) " · ⚡ " + String.format("%.1f MB/s", sm.speedBps / 1048576.0) else "") + (if (eta >= 0L) " · باقي " + fmt(eta) else "")
                    SendPill.update(act, if (going) "📥 بيستقبل من «" + sm.peerName + "» — $pct%" else "📥 " + sm.msg.ifBlank { "جاي ملفات من " + sm.peerName }, if (going) pct else 0, sub, null)
                } else if (lg != null) {
                    SendPill.update(act, "📥 " + lg.title.removePrefix("📥").trim().take(40) + " — " + lg.pct + "%", lg.pct, lg.msg, null)
                }
            }
            main.postDelayed(tick, 500L); return
        }
        if (saw) {
            val done = FtHub.sessions.firstOrNull { !it.outgoing && it.ended }
            val ok: Boolean; val msg: String
            if (done != null && System.currentTimeMillis() - done.endedAt < 20000L) {
                ok = done.status == FtSession.DONE
                msg = if (ok) "✅ وصل " + done.doneFiles() + " ملف من «" + done.peerName + "»" else "⚠ " + done.msg.ifBlank { "الاستقبال وقف" }
            } else {
                val d = TaskCenter.items.firstOrNull { it.kind == "dm" && it.title.startsWith("📥") }
                ok = d?.state == 2
                msg = if (ok) "✅ وصل الملف" else "⚠ الاستقبال وقف"
            }
            for (a in listOfNotNull(act, lastAct?.get()).distinct()) {
                SendPill.update(a, msg, if (ok) 100 else 0)
                SendPill.finish(a, msg, ok, if (ok) 4000L else 6000L)
            }
            running = false; saw = false
            return
        }
        if (System.currentTimeMillis() - t0 < 15000L) { main.postDelayed(tick, 500L); return }   // لسه ما بدأش: نستنى لحد 15 ثانية
        running = false
    }
}
