package me.prince.launcher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TvLink"

/**
 * TV side of the Subtitler phone remote. Same wire protocol as Subtitler's TvLink (HTTP :18770, UDP discovery :18771),
 * so the existing phone app finds and controls this launcher unchanged.
 * Pairing: the first phone pairs with no code; any later phone needs a 5-digit code that is shown on the TV when it asks.
 */
object TvLink {
    const val PORT = 18770
    const val UDP_PORT = 18771
    private const val ALIVE_MS = 12000L

    class Remote(val id: String, var name: String, var token: String) {
        @Volatile var lastSeen = 0L
        @Volatile var kicked = false
        @Volatile var announced = false
        fun alive() = lastSeen > 0L && System.currentTimeMillis() - lastSeen < ALIVE_MS
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val remotes = ConcurrentHashMap<String, Remote>()
    @Volatile private var started = false
    private var http: MiniHttp? = null
    @Volatile private var pairCode = ""
    @Volatile private var pairCodeUntil = 0L
    @Volatile private var badTries = 0
    @Volatile private var lockUntil = 0L
    private var top: WeakReference<Activity>? = null
    private val seenCmd = LinkedHashSet<String>()

    fun topActivity(): Activity? = top?.get()
    private fun prefs() = app.getSharedPreferences("tvlink", Context.MODE_PRIVATE)

    @Synchronized fun start(ctx: Context) {
        if (started) return
        app = ctx.applicationContext
        loadRemotes()
        val srv = MiniHttp(PORT) { r -> route(r) }
        if (!srv.start()) return
        http = srv
        started = true
        (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = WeakReference(a); TvUpdate.resumePending(a) }
            override fun onActivityPaused(a: Activity) { if (top?.get() === a) top = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        startUdp()
        startWatcher()
    }

    private fun startUdp() {
        Thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true; s.broadcast = true
                s.bind(InetSocketAddress(UDP_PORT))
                val buf = ByteArray(512)
                while (started) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (String(p.data, 0, p.length, Charsets.UTF_8).startsWith("SUBTV?")) {
                        val reply = hello().toString().toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "udp", e) }
        }.also { it.isDaemon = true }.start()
    }

    private fun startWatcher() {
        Thread {
            while (started) {
                try { for (rm in remotes.values) if (rm.announced && !rm.alive()) { rm.announced = false; notice("📱 الريموت «${rm.name}» انقطع") } } catch (_: Throwable) {}
                try { Thread.sleep(2500) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true }.start()
    }

    // ===== identity =====
    fun tvId(): String {
        var id = prefs().getString("id", "") ?: ""
        if (id.isBlank()) { id = randHex(8); prefs().edit().putString("id", id).apply() }
        return id
    }
    fun tvName(): String {
        val n = prefs().getString("name", "") ?: ""
        if (n.isNotBlank()) return n
        val m = try { Build.MODEL ?: "" } catch (_: Throwable) { "" }
        return if (m.isBlank()) "التلفزيون" else "تلفزيون $m"
    }
    private fun randHex(bytes: Int): String { val b = ByteArray(bytes); SecureRandom().nextBytes(b); return b.joinToString("") { String.format("%02x", it) } }
    private fun ver() = TvUpdate.ownVersionCode(app)
    private fun hello() = JSONObject().put("app", "subtitler-tv").put("id", tvId()).put("name", tvName()).put("port", PORT)
        .put("ver", ver()).put("vname", TvUpdate.ownVersionName(app)).put("needCode", remotes.isNotEmpty())

    private fun loadRemotes() {
        try {
            val a = JSONArray(prefs().getString("remotes", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id"); val tk = o.optString("token")
                if (id.isNotBlank() && tk.isNotBlank()) remotes[id] = Remote(id, o.optString("name", "موبايل"), tk)
            }
        } catch (_: Throwable) {}
    }
    private fun saveRemotes() {
        try {
            val a = JSONArray()
            for (r in remotes.values) a.put(JSONObject().put("id", r.id).put("name", r.name).put("token", r.token))
            prefs().edit().putString("remotes", a.toString()).apply()
        } catch (_: Throwable) {}
    }

    fun notice(msg: String, long: Boolean = false) {
        h.post { try { Toast.makeText(app, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
    }

    // ===== routing =====
    private fun err(code: Int, e: String) = HttpRes.json(JSONObject().put("ok", false).put("err", e).toString(), code)
    private fun ok(extra: JSONObject = JSONObject()) = HttpRes.json(extra.put("ok", true).toString())
    private fun body(r: HttpReq): JSONObject? = try { JSONObject(r.bodyText()) } catch (_: Exception) { null }

    private fun route(r: HttpReq): HttpRes {
        if (r.path == "/hello") return HttpRes.json(hello().toString())
        if (r.path == "/pair") return pair(r)
        val rm = remotes.values.firstOrNull { r.header("x-token").isNotEmpty() && it.token == r.header("x-token") } ?: return err(401, "auth")
        if (rm.kicked) { if (r.header("x-manual") == "1") rm.kicked = false else return err(409, "kicked") }
        touch(rm, r)
        return when (r.path) {
            "/ping" -> ok(JSONObject().put("ver", ver()).put("vname", TvUpdate.ownVersionName(app)).put("name", tvName()).put("tv", tvId())
                .put("ctx", "").put("acc", false))
            "/cmd" -> cmd(r)
            "/key" -> keyCmd(r)
            "/ptr" -> ok(JSONObject().put("ctx", "").put("acc", false))   // touchpad: needs the accessibility service, not in this launcher
            "/player" -> playerCmd(r)
            "/music" -> ok(JSONObject().put("none", true))
            "/open" -> open(r)
            "/type" -> typeText(r)
            "/keys" -> err(403, "unsupported")
            "/offer" -> offer(r)
            "/bye" -> { rm.announced = false; rm.lastSeen = 0L; notice("📱 الريموت «${rm.name}» فصل"); ok() }
            else -> err(404, "nf")
        }
    }

    private fun touch(rm: Remote, r: HttpReq) {
        rm.lastSeen = System.currentTimeMillis()
        val nm = r.header("x-name")
        if (nm.isNotEmpty()) try { val d = URLDecoder.decode(nm, "UTF-8"); if (d.isNotBlank() && d != rm.name) { rm.name = d; saveRemotes() } } catch (_: Exception) {}
        if (!rm.announced) { rm.announced = true; notice("📱 الريموت «${rm.name}» اتصل") }
    }

    private fun pair(r: HttpReq): HttpRes {
        val now = System.currentTimeMillis()
        if (now < lockUntil) return err(403, "locked")
        val j = body(r) ?: return err(403, "bad")
        val id = j.optString("id"); val nm = j.optString("name").ifBlank { "موبايل" }; val code = j.optString("code")
        if (id.isBlank()) return err(403, "bad")
        val codeOk = code.isNotEmpty() && code == pairCode && now < pairCodeUntil
        if (remotes.isNotEmpty() && !codeOk) {
            if (code.isNotEmpty()) { badTries++; if (badTries >= 8) { badTries = 0; lockUntil = now + 60000L } }
            else {   // phone asked without a code: show a fresh one on the TV screen
                pairCode = (SecureRandom().nextInt(90000) + 10000).toString(); pairCodeUntil = now + 10 * 60 * 1000L
                notice("🔐 كود إقران الموبايل: $pairCode", true)
            }
            return err(403, "need_code")
        }
        badTries = 0
        val tok = randHex(24)
        val ex = remotes[id]
        if (ex != null) { ex.token = tok; ex.name = nm; ex.kicked = false } else remotes[id] = Remote(id, nm, tok)
        saveRemotes()
        if (codeOk) pairCode = ""
        notice("📱 اتقرن ريموت جديد: «$nm»")
        return ok(JSONObject().put("token", tok).put("tv", tvId()).put("name", tvName()))
    }

    private fun dup(cid: String): Boolean {
        if (cid.isEmpty()) return false
        synchronized(seenCmd) {
            if (!seenCmd.add(cid)) return true
            if (seenCmd.size > 60) { val it = seenCmd.iterator(); it.next(); it.remove() }
        }
        return false
    }

    // ===== commands =====
    private fun cmd(r: HttpReq): HttpRes {
        val j = body(r) ?: return err(403, "bad")
        if (dup(j.optString("cid"))) return ok(JSONObject().put("dup", true))
        exec(j.optString("c"))
        return ok()
    }

    fun exec(c: String) {
        h.post {
            try {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                when (c) {
                    "vol_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "vol_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                    "playpause" -> PlayerActivity.remoteAct("toggle", 0L)
                    "open_settings" -> app.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "open_yt" -> app.startActivity(Intent(app, YouTubeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    "open_sd", "open_int" -> app.startActivity(Intent(app, VideosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "open_browser" -> goHome()
                }
            } catch (e: Throwable) { Log.w(TAG, "exec", e) }
        }
    }

    fun goHome() {
        try { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        catch (e: Throwable) { Log.w(TAG, "home", e) }
    }

    /** up/down/left/right/ok/back/home/menu/ff/rew/next/prev — delivered to our own foreground screen (other apps need an accessibility service) */
    private fun keyCmd(r: HttpReq): HttpRes {
        val j = body(r) ?: return err(403, "bad")
        val ctx = JSONObject().put("ctx", "").put("acc", false)
        if (dup(j.optString("cid"))) return ok(ctx.put("dup", true))
        val k = j.optString("k")
        if (k.isEmpty()) return err(403, "empty")
        h.post {
            try {
                if (k == "home") { goHome(); return@post }
                val a = top?.get() ?: return@post
                if (k == "back") {
                    if (a is PlayerActivity) a.finish()
                    else (a as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
                    return@post
                }
                val code = when (k) {
                    "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                    "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                    "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "menu" -> KeyEvent.KEYCODE_MENU
                    "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> return@post
                }
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            } catch (e: Throwable) { Log.w(TAG, "key", e) }
        }
        return ok(ctx)
    }

    private fun playerCmd(r: HttpReq): HttpRes {
        val j = body(r) ?: return err(403, "bad")
        val a = j.optString("a")
        if (a != "state" && a.isNotEmpty()) {
            if (dup(j.optString("cid"))) return ok(JSONObject().put("dup", true))
            if (!PlayerActivity.remoteAct(a, j.optLong("v"))) return ok(JSONObject().put("none", true))
        }
        val st = PlayerActivity.remoteState() ?: return ok(JSONObject().put("none", true))
        return ok(JSONObject().put("st", st))
    }

    private fun open(r: HttpReq): HttpRes {
        val j = body(r) ?: return err(403, "bad")
        val u = j.optString("url").trim()
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return err(403, "badurl")
        val lo = u.lowercase()
        if (lo.contains("youtube.com") || lo.contains("youtu.be")) {
            val id = Yt.idOf(u)
            val yi = if (id.isNotEmpty()) Intent(app, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("yt", id).putExtra("title", j.optString("title"))
            else Intent(app, YouTubeActivity::class.java)   // channel / playlist / search link: open YouTube
            yi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            h.post { try { app.startActivity(yi) } catch (e: Throwable) { Log.w(TAG, "open yt", e) } }
            return ok(JSONObject().put("visible", top?.get() != null))
        }
        val i = Intent(app, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(u))
            .putExtra("title", j.optString("title")).putExtra("ref", j.optString("ref")).putExtra("ua", j.optString("ua"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        h.post { try { app.startActivity(i) } catch (e: Throwable) { Log.w(TAG, "open", e) } }
        return ok(JSONObject().put("visible", top?.get() != null))
    }

    /** Text typed on the phone: becomes a YouTube search (opens YouTube if it isn't in front) */
    private fun typeText(r: HttpReq): HttpRes {
        val j = body(r) ?: return err(403, "bad")
        val t = j.optString("text").trim()
        if (t.isEmpty()) return err(403, "empty")
        h.post {
            try { app.startActivity(Intent(app, YouTubeActivity::class.java).putExtra("q", t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            catch (e: Throwable) { Log.w(TAG, "type", e) }
        }
        return ok()
    }

    private fun offer(r: HttpReq): HttpRes {
        val j = body(r) ?: return err(403, "bad")
        val url = j.optString("url"); val pv = j.optLong("ver"); val force = j.optBoolean("force")
        if (!url.startsWith("http://", true)) return err(403, "badurl")
        // the phone must say which package the APK is: pushing the phone's own Subtitler APK must never install over this launcher
        if (j.optString("pkg") != app.packageName) return err(403, "wrongapp")
        if (force) { if (pv < ver()) return err(403, "older") } else if (pv <= ver()) return err(403, "not_newer")
        TvUpdate.start(app, url, pv)
        return ok(JSONObject().put("shown", true))
    }
}
