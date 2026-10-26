package com.tttt.subtitler

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * (v545 م2-أ) جلسة الموبايل مع «مخ» التلفزيون: long-poll على /sync فوق RemoteClient، النتيجة بتتطبّق على LinkMirror (ذاكرة).
 * قاعدة ب-1: الـUI ما يعملش شبكة أبدًا. كل getter بيقرا من mirror، والأوامر async بنفس الـcid عند إعادة المحاولة.
 */
object LinkSession {
    val mirror = LinkMirror()

    @Volatile var connected = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var running = false
    @Volatile private var gen = 0
    @Volatile private var appRef: Context? = null
    @Volatile private var lastEv = 0L
    private val logBuf = ArrayList<String>()

    private class OpH(val report: (String) -> Unit, val done: (String) -> Unit)
    private val ops = ConcurrentHashMap<String, OpH>()

    @Volatile private var notifOn = false
    @Volatile private var notifMuted = false
    @Volatile private var notifAt = 0L
    @Volatile private var notifTitle = ""

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { main.post { for (l in listeners) try { l() } catch (_: Throwable) {} } }

    @Synchronized
    fun start(ctx: Context) {
        appRef = ctx.applicationContext
        if (running) return
        running = true
        gen += 1
        val g = gen
        Thread { loop(ctx.applicationContext, g) }.also { it.isDaemon = true; it.name = "LinkSession" }.start()
    }

    @Synchronized
    fun stop() {
        running = false
        gen += 1
        if (connected) { connected = false; fire() }
        val a = appRef
        if (notifOn && a != null) { try { KeepAliveService.stop(a) } catch (_: Throwable) {}; notifOn = false }
    }

    private fun setConn(v: Boolean) { if (connected != v) { connected = v; fire() } }

    private fun pause(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun loop(app: Context, g: Int) {
        var fails = 0
        while (running && gen == g) {
            if (!RemoteClient.ensureConnected(app, 4000L)) {
                setConn(false); fails += 1
                pause(minOf(1000L * fails, 8000L)); continue
            }
            val (c, t) = RemoteClient.getText("/sync?since=" + mirror.since + "&wait=20000", 30000)
            if (!running || gen != g) break
            val j = if (c == 200) try { JSONObject(t) } catch (_: Exception) { null } else null
            if (j == null || !j.optBoolean("ok", true)) {
                setConn(false); fails += 1
                pause(minOf(1000L * fails, 8000L)); continue
            }
            fails = 0
            val ch = mirror.apply(j, System.currentTimeMillis())
            setConn(true)
            if (ch.reset) continue
            if (ch.events.isNotEmpty()) dispatch(ch.events)
            mirrorNotif(app)
            if (ch.any()) fire()
        }
    }

    private fun dispatch(evs: List<JSONObject>) {
        for (e in evs) {
            val v = e.optLong("v", 0L)
            if (v <= lastEv) continue
            lastEv = v
            val t = e.optString("t"); val s = e.optString("s")
            when (t) {
                "log" -> synchronized(logBuf) { logBuf.add(s); while (logBuf.size > 300) logBuf.removeAt(0) }
                "opreport" -> { val (op, m) = PosExtrap.splitOp(s); val h = ops[op]; if (h != null) main.post { h.report(m) } }
                "opdone" -> { val (op, m) = PosExtrap.splitOp(s); val h = ops.remove(op); if (h != null) main.post { h.done(m) } }
            }
        }
    }

    /** تسجيل معالج لأمر طويل (recap / rewriteAll / …) قبل ما يتبعت */
    internal fun onOp(op: String, report: (String) -> Unit, done: (String) -> Unit) { ops[op] = OpH(report, done) }

    /**
     * أمر للتلفزيون (async). نفس الـcid في كل المحاولات (التلفزيون بيتجاهل المكرر).
     * cb على الـUI: (نجح؟، الرد). لو متحكّم تاني ماسك التحكم الرد err = readonly.
     */
    fun send(op: String, args: JSONObject = JSONObject(), cb: ((Boolean, JSONObject) -> Unit)? = null) {
        val cid = UUID.randomUUID().toString()
        Thread {
            var res = JSONObject().put("ok", false).put("err", "net")
            for (i in 0 until 3) {
                val (c, t) = try { RemoteClient.postJson("/rpc", JSONObject().put("cid", cid).put("op", op).put("args", args)) } catch (_: Throwable) { Pair(-1, "") }
                if (c == 200 || c == 409 || c == 403) {
                    res = try { JSONObject(t) } catch (_: Exception) { JSONObject().put("ok", false).put("err", "bad") }
                    if (c == 409) res.put("ok", false).put("err", "readonly")
                    break
                }
                pause(400L)
            }
            if (cb != null) { val r = res; main.post { cb(r.optBoolean("ok", false), r) } }
        }.also { it.isDaemon = true }.start()
    }

    fun takeControl() { send("control.take") { _, _ -> } }

    /** إعدادات من الموبايل للتلفزيون (قايمة بيضاء على التلفزيون؛ الحساس عبر /keys بس) ثم conf.reload */
    fun setCfg(kv: Map<String, String>, cb: ((Boolean) -> Unit)? = null) {
        Thread {
            var ok = false
            try {
                val set = JSONObject(); for ((k, v) in kv) set.put(k, v)
                val (c, _) = RemoteClient.postJson("/cfg", JSONObject().put("set", set))
                ok = c == 200
            } catch (e: Throwable) { LogStore.err("LinkSession:cfg", e) }
            if (cb != null) { val r = ok; main.post { cb(r) } }
        }.also { it.isDaemon = true }.start()
    }

    // ===== إشعار الموبايل بيعكس حالة التلفزيون =====

    private fun mirrorNotif(app: Context) {
        try {
            if (!PlayTarget.enabled() || PlayerActivity.hasLive()) return
            val st = mirror.st
            val title = st.optString("title", "")
            if (title != notifTitle) { notifTitle = title; notifMuted = false }
            val show = mirror.live && title.isNotEmpty() && !notifMuted
            if (!show) {
                if (notifOn) { KeepAliveService.stop(app); notifOn = false }
                return
            }
            val now = System.currentTimeMillis()
            if (now - notifAt < 1000L) return
            notifAt = now
            val dur = st.optLong("dur", 0L)
            NotifState.title = title
            NotifState.posMs = mirror.position(now)
            NotifState.durMs = dur
            NotifState.playing = st.optBoolean("playing", false)
            val e = mirror.eng
            NotifState.trState = if (!st.optBoolean("started", false)) 0 else if (e.userPaused) 2 else 1
            NotifState.trPct = if (dur > 0L) (e.covered * 1000.0 / dur * 100.0).toInt().coerceIn(0, 100) else 0
            if (!notifOn) { KeepAliveService.start(app); notifOn = true }
            KeepAliveService.refresh()
        } catch (e: Throwable) { LogStore.err("LinkSession:notif", e) }
    }

    /** أوامر الإشعار لما مفيش مشغّل محلي (PlayerRemote بتناديها) */
    fun notifCmd(cmd: String, arg: Long) {
        if (!running) return
        when (cmd) {
            "toggle", "prev", "next" -> send("act", JSONObject().put("a", cmd))
            "seek" -> send("seekto", JSONObject().put("ms", arg))
            "exit" -> { notifMuted = true; val a = appRef; if (a != null) { try { KeepAliveService.stop(a) } catch (_: Throwable) {}; notifOn = false } }
        }
    }
}
