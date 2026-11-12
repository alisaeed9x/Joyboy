package com.tttt.subtitler

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * (v811) «جسر الصوت»: يبعت صوت جهاز لجهاز تاني على الواي فاي (UDP، PCM خام 48kHz ستيريو، فريمات 5ms).
 *  - 🎧 صوت التلفزيون ← سماعة الموبايل (والسماعات البلوتوث المتوصلة بالموبايل بتشتغل تلقائي)
 *  - 📱 صوت الموبايل ← سماعة التلفزيون (لما بتتفرج على الموبايل)
 * المرسل = AbSendService (AudioPlaybackCapture عن طريق MediaProjection)، المستقبل = AbRecvService (AudioTrack منخفض التأخير).
 * البروتوكول: [J][B][flags][0][seq:int32 big-endian] + 960 بايت PCM16.
 * الجهاز المرسل بيكتم صوته المحلي (STREAM_MUSIC) وهو بيبعت، ويرجّعه لما يوقف.
 */
object AbProto {
    const val SR = 48000
    const val CH = 2
    const val FRAME_SAMPLES = 240                         // 5ms
    const val FRAME_BYTES = FRAME_SAMPLES * CH * 2        // 960
    const val HDR = 8
    const val M0: Byte = 0x4A
    const val M1: Byte = 0x42
}

object AudioBridge {
    enum class Route { OFF, TV_TO_PHONE, PHONE_TO_TV }

    @Volatile var route = Route.OFF
        private set
    @Volatile var err = ""

    private val main = Handler(Looper.getMainLooper())

    /** زمن التأخير المستهدف بالمللي ثانية (حجم مخزن الاستقبال = ضعفه) */
    fun latMs(): Int = try { Cfg.int("ab_lat_ms", 60).coerceIn(20, 300) } catch (_: Throwable) { 60 }

    fun say(ctx: Context, m: String) { main.post { try { Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} } }

    // ===== كتم/رجوع الصوت المحلي على الجهاز المرسل =====
    fun muteLocal(ctx: Context) {
        try {
            Cfg.ensure(ctx.applicationContext)
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Cfg.str("ab_saved_vol", "").isEmpty()) Cfg.put("ab_saved_vol", am.getStreamVolume(AudioManager.STREAM_MUSIC).toString())
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: Throwable) {}
    }

    fun restoreVolume(ctx: Context) {
        try {
            Cfg.ensure(ctx.applicationContext)
            val s = Cfg.str("ab_saved_vol", "")
            if (s.isEmpty()) return
            Cfg.put("ab_saved_vol", "")
            val v = s.toIntOrNull() ?: return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: Throwable) {}
    }

    // ===== تشغيل/إيقاف الأجزاء المحلية =====
    private fun stopRecv(ctx: Context) { try { ctx.stopService(Intent(ctx, AbRecvService::class.java)) } catch (_: Throwable) {} }
    private fun stopSendFull(ctx: Context) { try { ctx.stopService(Intent(ctx, AbSendService::class.java)) } catch (_: Throwable) {} }

    /** إيقاف الإرسال مع إبقاء إذن التقاط الصوت (15 دقيقة) عشان ما نطلبش موافقة تاني على التلفزيون */
    fun pauseSend(ctx: Context) {
        if (!AbSendService.hasProj) return
        try { ctx.startService(Intent(ctx, AbSendService::class.java).setAction("pause")) } catch (_: Throwable) {}
    }

    /** بداية إرسال صوت الجهاز ده لـ ip:port. لو مفيش إذن التقاط بيطلع شاشة الموافقة. */
    fun startSend(ctx: Context, ip: String, port: Int, mute: Boolean) {
        err = ""
        val i = Intent(ctx, AbSendService::class.java).setAction("start").putExtra("ip", ip).putExtra("port", port).putExtra("mute", mute)
        if (AbSendService.hasProj) {
            try { ctx.startService(i) } catch (e: Throwable) { err = "تعذّر بدء الإرسال: " + (e.message ?: "") }
        } else {
            try {
                ctx.startActivity(Intent(ctx, AbAskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("ip", ip).putExtra("port", port).putExtra("mute", mute))
            } catch (e: Throwable) { err = "تعذّر فتح شاشة الموافقة: " + (e.message ?: "") }
        }
    }

    // ===== جهة التلفزيون: أوامر /abridge من الموبايل =====
    fun rpc(app: Context, r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText().ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        fun res(o: JSONObject, ok: Boolean = true, code: Int = 200) = HttpRes.json(o.put("ok", ok).toString(), code)
        return when (j.optString("op")) {
            "send" -> {
                val port = j.optInt("port")
                if (port <= 0) return res(JSONObject().put("err", "port"), false, 400)
                startSend(app, r.peer, port, true)
                res(JSONObject().put("pending", !AbSendService.hasProj))
            }
            "recv" -> {
                try {
                    if (!AbRecvService.running) {
                        app.startForegroundService(Intent(app, AbRecvService::class.java).putExtra("lat", j.optInt("lat", latMs())))
                        var w = 0
                        while (AbRecvService.port == 0 && w < 40) { Thread.sleep(100); w++ }
                    }
                    val p = AbRecvService.port
                    if (p == 0) res(JSONObject().put("err", err.ifEmpty { "recv" }), false, 500) else res(JSONObject().put("port", p))
                } catch (e: Throwable) { res(JSONObject().put("err", e.message ?: "recv"), false, 500) }
            }
            "stop" -> { stopRecv(app); pauseSend(app); res(JSONObject()) }
            "status" -> res(JSONObject().put("sending", AbSendService.streaming).put("recving", AbRecvService.running)
                .put("proj", AbSendService.hasProj).put("err", err))
            else -> res(JSONObject().put("err", "op"), false, 400)
        }
    }

    // ===== جهة الموبايل =====
    private fun setRoute(r: Route) { route = r }

    private fun stopLocal(ctx: Context) { stopRecv(ctx); stopSendFull(ctx) }

    /** 🎧 صوت التلفزيون ← الموبايل */
    fun toPhone(act: Activity) {
        val ctx = act.applicationContext
        if (RemoteClient.current == null) { say(ctx, "اربط بالتلفزيون الأول"); return }
        Thread {
            try {
                stopLocal(ctx); err = ""
                ctx.startForegroundService(Intent(ctx, AbRecvService::class.java).putExtra("lat", latMs()))
                var w = 0
                while (AbRecvService.port == 0 && w < 40) { Thread.sleep(100); w++ }
                val port = AbRecvService.port
                if (port == 0) { say(ctx, "تعذّر تشغيل مستقبل الصوت: $err"); return@Thread }
                val (c, t) = RemoteClient.postJson("/abridge", JSONObject().put("op", "send").put("port", port))
                if (c != 200) {
                    stopLocal(ctx)
                    say(ctx, if (c < 0) "مفيش اتصال بالتلفزيون" else "التلفزيون مش بيدعم الميزة دي — حدّث نسخته ($c)")
                    return@Thread
                }
                setRoute(Route.TV_TO_PHONE)
                val pending = try { JSONObject(t).optBoolean("pending") } catch (_: Throwable) { false }
                if (pending) say(ctx, "على التلفزيون: وافق على طلب تسجيل الصوت بالريموت (OK)")
                waitTv(ctx)
            } catch (e: Throwable) { say(ctx, "خطأ: " + (e.message ?: "")); offLocal(ctx) }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun waitTv(ctx: Context) {
        var i = 0
        while (i < 60 && route == Route.TV_TO_PHONE) {
            Thread.sleep(1000); i++
            val (c, t) = RemoteClient.postJson("/abridge", JSONObject().put("op", "status"))
            if (c != 200) continue
            val j = try { JSONObject(t) } catch (_: Throwable) { continue }
            if (j.optBoolean("sending")) return
            val e = j.optString("err")
            if (e.isNotEmpty()) { say(ctx, "التلفزيون: $e"); offLocal(ctx); return }
        }
        if (route == Route.TV_TO_PHONE) { say(ctx, "التلفزيون ما بدأش يبعت الصوت"); offLocal(ctx) }
    }

    /** 📱 صوت الموبايل ← التلفزيون */
    fun toTv(act: Activity) {
        val ctx = act.applicationContext
        val tv = RemoteClient.current
        if (tv == null) { say(ctx, "اربط بالتلفزيون الأول"); return }
        Thread {
            try {
                stopLocal(ctx); err = ""
                val (c, t) = RemoteClient.postJson("/abridge", JSONObject().put("op", "recv").put("lat", latMs()))
                if (c != 200) { say(ctx, if (c < 0) "مفيش اتصال بالتلفزيون" else "التلفزيون مش بيدعم الميزة دي — حدّث نسخته ($c)"); return@Thread }
                val port = try { JSONObject(t).optInt("port") } catch (_: Throwable) { 0 }
                if (port <= 0) { say(ctx, "التلفزيون ما فتحش المستقبل"); return@Thread }
                setRoute(Route.PHONE_TO_TV)
                startSend(ctx, tv.ip, port, true)
                var i = 0
                while (i < 90 && route == Route.PHONE_TO_TV && !AbSendService.streaming && err.isEmpty()) { Thread.sleep(500); i++ }
                if (route == Route.PHONE_TO_TV && !AbSendService.streaming) { say(ctx, err.ifEmpty { "الصوت ما بدأش يتبعت" }); off(ctx) }
            } catch (e: Throwable) { say(ctx, "خطأ: " + (e.message ?: "")); off(ctx) }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun offLocal(ctx: Context) { setRoute(Route.OFF); stopLocal(ctx) }

    /** 📺 رجوع الصوت لسماعة التلفزيون (عادي) */
    fun off(ctx0: Context) {
        val ctx = ctx0.applicationContext
        offLocal(ctx)
        Thread { try { RemoteClient.postJson("/abridge", JSONObject().put("op", "stop")) } catch (_: Throwable) {} }.also { it.isDaemon = true; it.start() }
    }

    // ===== القايمة (زرار الريموت + شريط المشغّل + مشغّل الموسيقى) =====
    fun showMenu(act: Activity) {
        val on = "✔ "
        val items = arrayOf(
            (if (route == Route.OFF) on else "") + "📺 الصوت من سماعة التلفزيون (عادي)",
            (if (route == Route.TV_TO_PHONE) on else "") + "🎧 صوت التلفزيون ← سماعة الموبايل",
            (if (route == Route.PHONE_TO_TV) on else "") + "📱 صوت الموبايل ← سماعة التلفزيون",
            "⏱ زمن التأخير: ${latMs()}ms (اضغط للتغيير)"
        )
        val d = GAlert(act).setTitle("🔊 مصدر الصوت").setItems(items) { _, w ->
            when (w) {
                0 -> off(act)
                1 -> toPhone(act)
                2 -> toTv(act)
                3 -> showLatency(act)
            }
        }.create()
        d.show()
        try { d.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL } catch (_: Throwable) {}
    }

    private fun showLatency(act: Activity) {
        val dp = act.resources.displayMetrics.density
        val lab = TextView(act).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        val sb = SeekBar(act).apply { max = 280; progress = latMs() - 20 }
        fun upd() { lab.text = "${sb.progress + 20} ms" }
        upd()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { upd() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val note = TextView(act).apply {
            textSize = 12.5f
            text = "قليل = أسرع بس ممكن تقطيع على الواي فاي الضعيف. كتير = ثابت بس الصوت متأخر عن الصورة. السماعات البلوتوث بتزوّد تأخير لوحدها. التغيير بيسري من التشغيل الجاي."
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(lab); addView(sb); addView(note)
        }
        val d = GAlert(act).setTitle("⏱ زمن التأخير").setView(box)
            .setPositiveButton("حفظ") { _, _ -> try { Cfg.put("ab_lat_ms", (sb.progress + 20).toString()) } catch (_: Throwable) {} }
            .setNegativeButton("إلغاء", null).create()
        d.show()
        try { d.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL } catch (_: Throwable) {}
    }
}

/** المستقبل: UDP ← AudioTrack. مخزن بحجم 2×زمن التأخير، والفائض بيتشال (التأخير ما بيزيدش). */
class AbRecvService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var port = 0
        private const val CH_ID = "ab_recv"
        private const val NID = 4411
    }

    private var sock: DatagramSocket? = null
    private var track: AudioTrack? = null
    private var th: Thread? = null
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        startFg()
        try { begin(intent.getIntExtra("lat", 60)) } catch (e: Throwable) {
            AudioBridge.err = "المستقبل: " + (e.message ?: "")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_ID, "جسر الصوت", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 4, Intent(this, AbRecvService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_ID).setContentTitle("🎧 بيشغّل صوت الجهاز التاني").setContentText("اضغط إيقاف للرجوع للصوت العادي")
            .setSmallIcon(R.drawable.ic_stat_sub).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NID, n)
    }

    private fun begin(latMs: Int) {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        s.soTimeout = 400
        try { s.receiveBufferSize = 262144 } catch (_: Throwable) {}
        sock = s
        val bytesPerSec = AbProto.SR * AbProto.CH * 2
        val want = (bytesPerSec.toLong() * (latMs.coerceIn(20, 300) * 2) / 1000L).toInt()
        val minB = AudioTrack.getMinBufferSize(AbProto.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(want, minB, AbProto.FRAME_BYTES * 4)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AbProto.SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        alive = true
        running = true
        port = s.localPort
        th = Thread { loop(s, t, size) }.also { it.isDaemon = true; it.start() }
    }

    private fun loop(s: DatagramSocket, t: AudioTrack, size: Int) {
        val buf = ByteArray(2048)
        val pk = DatagramPacket(buf, buf.size)
        val silence = ByteArray(AbProto.FRAME_BYTES)
        var last = -1
        var primed = false
        var lastRx = 0L
        try {
            while (alive) {
                try { pk.length = buf.size; s.receive(pk) } catch (_: SocketTimeoutException) {
                    if (primed && SystemClock.elapsedRealtime() - lastRx > 1200L) {
                        try { t.pause(); t.flush() } catch (_: Throwable) {}
                        primed = false; last = -1
                    }
                    continue
                }
                val n = pk.length
                if (n <= AbProto.HDR || buf[0] != AbProto.M0 || buf[1] != AbProto.M1) continue
                val seq = ((buf[4].toInt() and 0xFF) shl 24) or ((buf[5].toInt() and 0xFF) shl 16) or ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                lastRx = SystemClock.elapsedRealtime()
                if (!primed) {
                    // نص المخزن سكوت = هامش ضد تقطيع الشبكة، وبعدها التشغيل
                    var left = size / 2
                    while (left > 0) { val w = minOf(left, silence.size); t.write(silence, 0, w, AudioTrack.WRITE_NON_BLOCKING); left -= w }
                    try { t.play() } catch (_: Throwable) {}
                    primed = true
                } else if (last != -1) {
                    val gap = seq - last - 1
                    if (gap < 0) continue                       // باكت قديم أو مكرر
                    if (gap in 1..6) repeat(gap) { t.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING) }
                }
                last = seq
                t.write(buf, AbProto.HDR, n - AbProto.HDR, AudioTrack.WRITE_NON_BLOCKING)   // لو المخزن مليان بيتشال الفريم (التأخير ثابت)
            }
        } catch (_: Throwable) {
        } finally {
            try { t.stop() } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        alive = false
        try { sock?.close() } catch (_: Throwable) {}
        try { th?.join(500) } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        sock = null; track = null
        running = false; port = 0
        super.onDestroy()
    }
}

/** المرسل: يلتقط صوت الجهاز (AudioPlaybackCapture) ويبعته UDP. الإذن (MediaProjection) بيفضل 15 دقيقة بعد الإيقاف. */
class AbSendService : Service() {
    companion object {
        @Volatile var hasProj = false
        @Volatile var streaming = false
        private const val CH_ID = "ab_send"
        private const val NID = 4412
        private const val IDLE_MS = 15 * 60 * 1000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var th: Thread? = null
    @Volatile private var alive = false
    private var muted = false
    private val idleStop = Runnable { shutdown(); stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action ?: ""
        if (intent == null || act == "stop") { shutdown(); stopSelf(); return START_NOT_STICKY }
        main.removeCallbacks(idleStop)
        when (act) {
            "proj" -> {
                startFg()
                val data = intent.getParcelableExtra<Intent>("data")
                if (data == null) { AudioBridge.err = "الموافقة ما وصلتش"; stopSelf(); return START_NOT_STICKY }
                try {
                    val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val p = m.getMediaProjection(intent.getIntExtra("rc", 0), data) ?: throw IllegalStateException("no projection")
                    p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { main.post { shutdown(); stopSelf() } } }, main)
                    mp = p; hasProj = true
                } catch (e: Throwable) {
                    AudioBridge.err = "فشل التقاط الصوت: " + (e.message ?: "")
                    stopSelf(); return START_NOT_STICKY
                }
                begin(intent)
            }
            "start" -> {
                if (!hasProj) { AudioBridge.err = "مفيش إذن التقاط"; stopSelf() }
                else { startFg(); begin(intent) }
            }
            "pause" -> { endStream(); main.postDelayed(idleStop, IDLE_MS) }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_ID, "جسر الصوت", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 3, Intent(this, AbSendService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_ID).setContentTitle("🎧 جسر الصوت شغّال").setContentText("بيبعت صوت الجهاز لجهاز تاني — اضغط إيقاف لقفله")
            .setSmallIcon(R.drawable.ic_stat_sub).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NID, n)
    }

    private fun begin(i: Intent) {
        val ip = i.getStringExtra("ip") ?: ""
        val port = i.getIntExtra("port", 0)
        if (ip.isEmpty() || port <= 0) return
        endStream()
        try {
            startStream(ip, port)
            if (i.getBooleanExtra("mute", true)) { AudioBridge.muteLocal(this); muted = true }
        } catch (e: Throwable) {
            AudioBridge.err = "بدء الالتقاط فشل: " + (e.message ?: "")
            endStream()
        }
    }

    private fun startStream(ip: String, port: Int) {
        val p = mp ?: throw IllegalStateException("no projection")
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AbProto.SR).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(AbProto.SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, AbProto.FRAME_BYTES * 8)).setAudioPlaybackCaptureConfig(cfg).build()
        r.startRecording()
        rec = r
        alive = true
        streaming = true
        th = Thread { sendLoop(r, ip, port) }.also { it.isDaemon = true; it.start() }
    }

    private fun sendLoop(r: AudioRecord, ip: String, port: Int) {
        val buf = ByteArray(AbProto.HDR + AbProto.FRAME_BYTES)
        buf[0] = AbProto.M0; buf[1] = AbProto.M1; buf[2] = 1
        var seq = 0
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            val pk = DatagramPacket(buf, buf.size, InetAddress.getByName(ip), port)
            while (alive) {
                var off = 0
                while (off < AbProto.FRAME_BYTES && alive) {
                    val n = r.read(buf, AbProto.HDR + off, AbProto.FRAME_BYTES - off)
                    if (n < 0) { alive = false; break }
                    if (n == 0) { Thread.sleep(2); continue }
                    off += n
                }
                if (off < AbProto.FRAME_BYTES) continue
                buf[4] = (seq ushr 24).toByte(); buf[5] = (seq ushr 16).toByte(); buf[6] = (seq ushr 8).toByte(); buf[7] = seq.toByte()
                seq++
                sock.send(pk)
            }
        } catch (e: Throwable) {
            AudioBridge.err = "الإرسال وقف: " + (e.message ?: "")
        } finally {
            try { sock?.close() } catch (_: Throwable) {}
            streaming = false
        }
    }

    private fun endStream() {
        alive = false
        try { th?.join(400) } catch (_: Throwable) {}
        th = null
        try { rec?.stop() } catch (_: Throwable) {}
        try { rec?.release() } catch (_: Throwable) {}
        rec = null
        streaming = false
        if (muted) { AudioBridge.restoreVolume(this); muted = false }
    }

    private fun shutdown() {
        main.removeCallbacks(idleStop)
        endStream()
        val p = mp
        mp = null
        hasProj = false
        try { p?.stop() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}

/** شاشة شفافة: إذن الميكروفون + موافقة MediaProjection، وبعدها تشغّل AbSendService. */
class AbAskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 91)
            return
        }
        ask()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 91 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) ask()
        else { AudioBridge.err = "لازم إذن الميكروفون عشان يلتقط صوت الجهاز"; finish() }
    }

    private fun ask() {
        try {
            val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION") startActivityForResult(m.createScreenCaptureIntent(), 92)
        } catch (e: Throwable) {
            AudioBridge.err = "الجهاز ده مش بيدعم التقاط الصوت"
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 92 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, AbSendService::class.java).setAction("proj")
                .putExtra("rc", resultCode).putExtra("data", data)
                .putExtra("ip", intent.getStringExtra("ip") ?: "").putExtra("port", intent.getIntExtra("port", 0))
                .putExtra("mute", intent.getBooleanExtra("mute", true))
            try { startForegroundService(i) } catch (e: Throwable) { AudioBridge.err = "تعذّر تشغيل الخدمة: " + (e.message ?: "") }
        } else if (requestCode == 92) {
            AudioBridge.err = "الموافقة اتلغت"
        }
        finish()
    }
}
