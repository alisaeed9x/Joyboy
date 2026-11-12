package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.lang.ref.WeakReference

/** حالة المشغّل اللي بتظهر في الإشعار (المشغّل بيحدّثها، والخدمة بتعرضها) */
object NotifState {
    @Volatile var title = ""
    @Volatile var posMs = 0L
    @Volatile var durMs = 0L
    @Volatile var playing = false
    @Volatile var trPct = 0
    /** 0 = الترجمة ماابتدتش · 1 = شغالة · 2 = موقوفة */
    @Volatile var trState = 0
}

/**
 * (v624) صوت التلفزيون من الموبايل: زراري الفوليوم (حتى والشاشة مقفولة) وسلايدر الصوت بتاع النظام بيتحوّلوا لصوت التلفزيون.
 * الزرار = خطوة 5% وبحد أقصى 100 (التضخيم فوق 100 من سلايدر المشغّل بس) عشان خطوة واحدة ما توصّلش للآخر.
 */
object TvVolume {
    private const val STEP = 5
    @Volatile private var pend = -1
    @Volatile private var pendAt = 0L

    /** الصوت الحالي 0..200: القيمة اللي لسه مبعوتة (لحد ما ترجع من التلفزيون) وإلا آخر قيمة جاية منه */
    fun current(): Int {
        if (pend >= 0 && System.currentTimeMillis() - pendAt < 1500L) return pend
        val v = try { LinkSession.mirror.st.optInt("vol", -1) } catch (_: Throwable) { -1 }
        return if (v >= 0) v else 50
    }

    /** الصوت بعد ضغطة واحدة (dir = +1 / -1) */
    fun next(cur: Int, dir: Int): Int =
        if (dir > 0) (if (cur >= 100) cur else minOf(cur + STEP, 100))
        else (if (cur > 100) 100 else maxOf(cur - STEP, 0))

    fun step(dir: Int): Int { val cur = current(); val n = next(cur, dir); setTo(n, cur); return n }

    fun setTo(p0: Int, from: Int = current()) {
        val p = p0.coerceIn(0, 200)
        pend = p; pendAt = System.currentTimeMillis()
        LinkSession.send("volume.set", org.json.JSONObject().put("p", p)) { ok, _ ->
            if (!ok && p != from) RemoteClient.cmd(if (p > from) "vol_up" else "vol_down")   // مفيش مشغّل بيرد على التلفزيون: خطوة نظام عادية
        }
    }
}

/** جسر بين الإشعار والمشغّل: الأوامر (السابق/التالي/تشغيل/سيك/ترجمة/خروج) بتروح للمشغّل الحي */
object PlayerRemote {
    @Volatile var act: WeakReference<PlayerActivity>? = null
    fun send(cmd: String, arg: Long = 0L) {
        val a = act?.get()
        if (a != null) a.remote(cmd, arg) else LinkSession.notifCmd(cmd, arg)
    }
}

/** خدمة foreground: بتخلّي العملية شغالة والترجمة تكمّل لما الشاشة تتقفل، وبتعرض إشعار تحكم (اسم الفيديو · الوقت · شريط تقدم · أزرار) */
class KeepAliveService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var recv: BroadcastReceiver? = null
    private var volProv: android.media.VolumeProvider? = null   // (v624) بيخلّي زراري الفوليوم/سلايدر الصوت يتحكّموا في التلفزيون

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        try {
            val s = MediaSession(this, "subtitler")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { PlayerRemote.send("toggle") }
                override fun onPause() { PlayerRemote.send("toggle") }
                override fun onSkipToNext() { PlayerRemote.send("next") }
                override fun onSkipToPrevious() { PlayerRemote.send("prev") }
                override fun onSeekTo(pos: Long) { PlayerRemote.send("seek", pos) }
            })
            s.isActive = true
            session = s
        } catch (e: Throwable) { LogStore.err("notif-session", e) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { PlayerRemote.send(i?.getStringExtra("cmd") ?: return) }
        }
        recv = r
        try {
            val f = IntentFilter(ACT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        } catch (e: Throwable) { LogStore.err("notif-recv", e) }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        return START_NOT_STICKY
    }

    private fun pi(cmd: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(this, code, Intent(ACT).setPackage(packageName).putExtra("cmd", cmd), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(res: Int, label: String, cmd: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, res), label, pi(cmd, code)).build()

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%02d:%02d", s / 60, s % 60)
    }

    /** (v624) الموبايل بيتحكم في فيديو على التلفزيون ← الجلسة بقت «صوت بعيد» (الفوليوم بيروح للتلفزيون حتى والشاشة مقفولة) */
    private fun syncVolume() {
        val s = session ?: return
        try {
            val pa = PlayerRemote.act?.get()
            val toTv = RemoteClient.state == RemoteClient.St.CONNECTED && (if (pa != null) pa.remote else LinkSession.mirror.live)
            if (toTv) {
                val v = TvVolume.current().coerceIn(0, 100)
                val cur = volProv
                if (cur == null) {
                    val p = object : android.media.VolumeProvider(android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, v) {
                        override fun onSetVolumeTo(vol: Int) { TvVolume.setTo(vol); currentVolume = vol.coerceIn(0, 100) }
                        override fun onAdjustVolume(direction: Int) { if (direction != 0) currentVolume = TvVolume.step(direction).coerceIn(0, 100) }
                    }
                    volProv = p; s.setPlaybackToRemote(p)
                } else if (cur.currentVolume != v) cur.currentVolume = v
            } else if (volProv != null) {
                volProv = null
                s.setPlaybackToLocal(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            }
        } catch (e: Throwable) { LogStore.err("notif-vol", e) }
    }

    private fun build(): Notification {
        syncVolume()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "تحكم المشغّل والترجمة", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val st = NotifState
        val title = st.title.ifBlank { "Subtitler" }
        val trTxt = when (st.trState) { 1 -> "الترجمة ${st.trPct}%"; 2 -> "الترجمة موقوفة ${st.trPct}%"; else -> "الترجمة ماابتدتش" }
        val timeTxt = if (st.durMs > 0) "${clock(st.posMs)} / ${clock(st.durMs)}" else clock(st.posMs)
        val open = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java).addFlags(if (!DeviceUi.isTv(this)) Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        try {
            session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putLong(MediaMetadata.METADATA_KEY_DURATION, st.durMs.coerceAtLeast(0)).build())
            session?.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, st.posMs, if (st.playing) 1f else 0f).build())
        } catch (_: Throwable) {}
        val b = Notification.Builder(this, CH)
            .setContentTitle(title)
            .setContentText("$timeTxt · $trTxt")
            .setSmallIcon(R.drawable.ic_stat_sub)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "السابق", "prev", 1))
            .addAction(act(if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (st.playing) "إيقاف" else "تشغيل", "toggle", 2))
            .addAction(act(android.R.drawable.ic_media_next, "التالي", "next", 3))
            .addAction(act(android.R.drawable.ic_popup_sync, if (st.trState == 1) "وقّف الترجمة" else "ترجم من هنا", "tr", 4))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "خروج", "exit", 5))
        val ms = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        try { session?.sessionToken?.let { ms.setMediaSession(it) } } catch (_: Throwable) {}
        b.setStyle(ms)
        if (Build.VERSION.SDK_INT < 33 && st.durMs > 0) b.setProgress(st.durMs.toInt().coerceAtLeast(1), st.posMs.toInt().coerceIn(0, st.durMs.toInt()), false)
        return b.build()
    }

    private fun refreshNow() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build()) } catch (e: Throwable) { LogStore.err("notif-upd", e) }
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        try { recv?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        try { session?.isActive = false; session?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val CH = "keep_alive_ctl"
        private const val ID = 77
        const val ACT = "com.tttt.subtitler.NOTIF_CMD"
        @Volatile private var inst: KeepAliveService? = null
        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
        fun stop(c: Context) {
            try { c.stopService(Intent(c, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
        /** المشغّل بيناديها (كل ثانية تقريبًا) بعد ما يحدّث NotifState */
        fun refresh() { val s = inst ?: return; s.main.post { s.refreshNow() } }
    }
}
