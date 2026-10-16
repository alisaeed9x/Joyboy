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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** (v190) محرك الموسيقى: مشغّل واحد للعملية كلها (مش مربوط بالشاشة) — بيكمّل شغل لما تخرج من التطبيق لأن MusicService بتخلّي العملية شغّالة */
object MusicEngine {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var mp: MediaPlayer? = null
    var queue: List<Track> = emptyList(); private set
    var idx = -1; private set
    @Volatile var ready = false; private set
    private var wantPlay = false
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnFocus = false
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    // (v195) التكرار: 0 بدون تكرار · 1 تكرار الكل · 2 تكرار أغنية واحدة — والعشوائي (shuffle) زرار لوحده
    @Volatile var mode = 0; private set
    @Volatile var shuffle = false; private set
    private var pendingNext = -1          // الأغنية العشوائية الجاية (متحددة قبل الضغط عشان الشاشة تعرض غلافها)
    private val history = ArrayList<Int>()
    private val rnd = java.util.Random()
    fun cycleMode(): Int { setMode((mode + 1) % 3); return mode }
    fun setMode(m: Int) { mode = m.coerceIn(0, 2); try { Cfg.put("music_mode", mode.toString()) } catch (_: Throwable) {}; changed() }
    fun toggleShuffle(): Boolean { shuffle = !shuffle; pendingNext = -1; try { Cfg.put("music_shuffle", if (shuffle) "1" else "0") } catch (_: Throwable) {}; changed(); return shuffle }

    // (v200) سرعة التشغيل (الطبقة ثابتة) + مؤقت النوم (بعد X دقيقة بفيد تدريجي، أو لما الأغنية الحالية تخلص)
    @Volatile var speed = 1f; private set
    @Volatile var sleepAtMs = 0L; private set          // 0 = مفيش مؤقت زمني
    @Volatile var sleepEndOfTrack = false; private set
    fun sleepLeftMs(): Long = if (sleepAtMs > 0L) (sleepAtMs - System.currentTimeMillis()).coerceAtLeast(0L) else 0L
    private val sleepTick = object : Runnable {
        override fun run() {
            if (sleepAtMs <= 0L) return
            if (System.currentTimeMillis() >= sleepAtMs) { sleepFire(); return }
            main.postDelayed(this, 1000L)
        }
    }
    /** m > 0 دقايق · -1 لما الأغنية تخلص · 0 إلغاء */
    fun setSleep(m: Int) {
        main.removeCallbacks(sleepTick)
        sleepEndOfTrack = m < 0
        sleepAtMs = if (m > 0) System.currentTimeMillis() + m * 60_000L else 0L
        if (m > 0) main.postDelayed(sleepTick, 1000L)
        changed()
    }
    private fun sleepFire() {
        sleepAtMs = 0L; sleepEndOfTrack = false
        val steps = 6; var n = 0
        val fade = object : Runnable {
            override fun run() {
                n++
                val f = (1f - n / steps.toFloat()).coerceAtLeast(0f)
                try { mp?.setVolume(f, f) } catch (_: Throwable) {}   // (v270) الصوت العادي = صوت النظام، مفيش تخفيض تاني من التطبيق
                if (n < steps) main.postDelayed(this, 700L) else { pause(); applyVol() }
            }
        }
        changed(); main.post(fade)
    }
    fun setSpeed(sp: Float) {
        speed = sp.coerceIn(0.5f, 2f)
        try { Cfg.put("music_speed", speed.toString()) } catch (_: Throwable) {}
        if (isPlaying) applySpeed()
        changed()
    }
    /** بنطبّقها بعد start() بس — على بعض الإصدارات ضبط السرعة وهو واقف بيشغّل الأغنية لوحده */
    private fun applySpeed() {
        val m = mp ?: return
        if (!ready) return
        try { val pp = m.playbackParams; pp.setSpeed(speed); pp.setPitch(1f); m.playbackParams = pp } catch (e: Throwable) { LogStore.err("music-speed", e) }
    }

    private fun openPlayer(ctx: Context, t: Track, startMs: Int) {
        val uri = Uri.parse(t.uri)
        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setWakeMode(ctx, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                val yh = if (YtAudio.isRemote(uri.toString())) YtAudio.headersFor(uri.toString()) else null   // (v337) ستريم يوتيوب: بهيدرز
                if (yh != null && yh.isNotEmpty()) setDataSource(ctx, uri, yh) else setDataSource(ctx, uri)
                setOnPreparedListener { ready = true; applyVol(); if (startMs > 0) { try { it.seekTo(startMs) } catch (_: Throwable) {} }; if (wantPlay) { try { it.start() } catch (_: Throwable) {}; applySpeed() }; changed() }
                setOnCompletionListener { onEnded() }
                setOnErrorListener { _, _, _ -> ready = false; main.post { if (YtAudio.isRemote(t.uri)) ytFailed(t) else if (queue.size > 1) step(1) }; true }
                prepareAsync()
            }
        } catch (e: Exception) { LogStore.err("music-play", e) }
    }

    // (v193) الصوت: 0..100 صوت عادي · 100..200 تضخيم (LoudnessEnhancer لحد +15dB) — نفس فكرة مشغّل الفيديو
    @Volatile var volPct = 100; private set
    private var loud: android.media.audiofx.LoudnessEnhancer? = null
    fun loadPrefs() {
        try {
            val raw = Cfg.str("music_mode", "0").toIntOrNull() ?: 0
            mode = if (raw >= 3) 0 else raw.coerceIn(0, 2)                    // (v193 القديم: 3 = عشوائي)
            shuffle = raw == 3 || Cfg.str("music_shuffle", "0") == "1"
            volPct = (Cfg.str("music_vol", "100").toIntOrNull() ?: 100).coerceIn(0, 200)
            speed = (Cfg.str("music_speed", "1").toFloatOrNull() ?: 1f).coerceIn(0.5f, 2f)
        } catch (_: Throwable) {}
    }
    /** (v302) محدش بيستخدمها من الواجهة (السلايدر اتشال) — لو اتنادت بتحرّك صوت النظام على نفس المقياس (0..200 ← 0..100) */
    fun setVol(p: Int) {
        app?.let { SysVol.set(it, p.coerceIn(0, 200) / 2) }
        syncSys()
    }
    /**
     * (v302) الصوت = زراري الفوليوم بس: صوت النظام 0..100% بيتحوّل لـ 0..200% (كل خطوة فوليوم = خطوتين في المقياس).
     * لحد 100% صوت عادي، وفوق 100% (يعني النظام فوق النص) بيتضاف تضخيم LoudnessEnhancer لحد +15dB لما النظام على الآخر (200%).
     */
    fun syncSys() {
        val a = app ?: return
        val np = (SysVol.get(a) * 2).coerceIn(0, 200)
        if (np != volPct) { volPct = np; try { Cfg.put("music_vol", volPct.toString()) } catch (_: Throwable) {}; applyVol() }
    }
    private var volWatch: SysVol.Watch? = null
    fun boostDbText(): String = if (volPct <= 100) "" else "+" + "%.1f".format((volPct - 100) * 0.15) + " dB"
    private fun applyVol() {
        val m = mp ?: return
        try { m.setVolume(1f, 1f) } catch (_: Throwable) {}
        try {
            val b = (volPct - 100).coerceAtLeast(0)
            if (b > 0) {
                if (loud == null) loud = android.media.audiofx.LoudnessEnhancer(m.audioSessionId)
                loud?.setTargetGain(b * 15)   // مللي-ديسيبل: 100 → +15dB
                loud?.enabled = true
            } else { loud?.enabled = false }
        } catch (e: Throwable) { LogStore.err("music-boost", e) }
    }

    val current: Track? get() = queue.getOrNull(idx)
    val sessionId: Int get() = try { mp?.audioSessionId ?: 0 } catch (_: Throwable) { 0 }
    val isPlaying: Boolean get() = try { mp?.isPlaying == true } catch (_: Throwable) { false }
    val posMs: Int get() = try { if (ready) mp?.currentPosition ?: 0 else 0 } catch (_: Throwable) { 0 }
    val durMs: Int get() = try { if (ready) mp?.duration ?: 0 else (current?.durMs ?: 0L).toInt() } catch (_: Throwable) { 0 }

    /** (v351) مكان الوقوف الفعلي لصوت يوتيوب: بيتحفظ بنفس مفتاح مشغّل الفيديو (lastpos) عشان لما تفتح الفيديو تاني يكمّل من هنا مش من قبل ما تخرج */
    private var lastYtPosSave = 0L
    private fun saveYtPos(force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            if (!force && now - lastYtPosSave < 3000L) return
            val id = YtAudio.itemOf(current)?.id ?: return
            val p = posMs; if (p <= 1000) return
            lastYtPosSave = now
            Cfg.p.edit().putString("lastpos", "yt:" + id + "\t" + p).apply()
        } catch (_: Throwable) {}
    }
    private fun changed() { saveYtPos(); main.post { for (l in listeners) { try { l() } catch (_: Throwable) {} }; MusicService.refresh() } }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { c ->
        when (c) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocus = isPlaying; pause() }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) { resumeOnFocus = false; resume() }
        }
    }
    private fun gainFocus() {
        val a = app ?: return
        try {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            focusReq = r; am.requestAudioFocus(r)
        } catch (_: Throwable) {}
    }
    private fun dropFocus() {
        val a = app ?: return
        try { focusReq?.let { (a.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) } } catch (_: Throwable) {}
        focusReq = null
    }

    fun play(ctx: Context, q: List<Track>, i: Int, fromHistory: Boolean = false, startMs: Int = 0) {
        if (i !in q.indices) return
        app = ctx.applicationContext
        if (volWatch == null) volWatch = SysVol.watch(ctx.applicationContext) { syncSys() }   // (v302) زراري الفوليوم بيغيّروا التضخيم فورًا حتى والشاشة مقفولة/المشغّل مقفول
        syncSys()
        if (!fromHistory && queue === q && idx in q.indices && idx != i) { history.add(idx); if (history.size > 50) history.removeAt(0) }
        if (queue !== q) history.clear()
        queue = q; idx = i; ready = false; wantPlay = true; pendingNext = -1; ytBroken = false
        release()
        MusicService.start(ctx.applicationContext)
        gainFocus()
        val t = q[i]
        try { if (!YtAudio.isRemote(t.uri)) { RecentSongs.add(t.uri); PubHistory.add("a", "music", t.title, t.uri) } } catch (_: Throwable) {}   // (v337) روابط يوتيوب مؤقتة: مش بتتسجّل في «آخر المشغّل»
        openPlayer(ctx.applicationContext, t, startMs)   // (v343) startMs: تحويل فيديو→صوت يكمّل من نفس المكان
        changed()
    }
    private fun peekRandom(): Int {
        if (queue.size <= 1) return 0
        if (pendingNext in queue.indices && pendingNext != idx) return pendingNext
        var n = rnd.nextInt(queue.size)
        if (n == idx) n = (n + 1) % queue.size
        pendingNext = n
        return n
    }
    private fun randomOther(): Int { val n = peekRandom(); pendingNext = -1; return n }
    /** (v195) الأغنية اللي هتشتغل لو داس التالي (d=1) أو السابق (d=-1) — للعرض بس (الغلاف على جنب الأسطوانة) */
    fun neighbor(d: Int): Track? {
        if (queue.size < 2 || idx !in queue.indices) return null
        if (shuffle) return if (d > 0) queue.getOrNull(peekRandom()) else history.lastOrNull()?.let { queue.getOrNull(it) }
        val n = idx + d
        return queue.getOrNull(if (n in queue.indices) n else if (d > 0) 0 else queue.size - 1)
    }
    /**
     * (v341) صوت يوتيوب: الـqueue فيها فيديو واحد، فالتالي بييجي من قايمة التشغيل/المقترحات عن طريق Main.
     * ytStep(1) = التالي · ytStep(0) = إعادة جلب رابط نفس الفيديو (الرابط بيخلص). بترجّع true لو اتعامل معاها.
     * الأغنية الحالية بتفضل شغّالة لحد ما اللي بعدها يجهز (مش بنوقفها).
     */
    @Volatile var ytStep: ((Int) -> Boolean)? = null
    @Volatile private var ytRetryId = Long.MIN_VALUE
    private fun ytFailed(t: Track) {
        // أول فشل لنفس الفيديو: نجيب رابط جديد تلقائي. تاني فشل: نقف ونسيب الزرار ▶ يعيد المحاولة
        if (ytRetryId != t.id && ytStep?.invoke(0) == true) { ytRetryId = t.id; return }
        wantPlay = false; ytBroken = true; changed()
    }
    @Volatile private var ytBroken = false

    /** التالي/السابق بالزرار — بيلف دايمًا (حتى لو التكرار مقفول) */
    fun step(d: Int) {
        if (queue.isEmpty()) return
        val a = app ?: return
        val cu = current
        if (cu != null && YtAudio.isRemote(cu.uri)) {   // (v341) صوت يوتيوب: مفيش «سابق» — بيرجّع لأول الفيديو بس
            if (d < 0) { seekTo(0); return }
            if (ytStep?.invoke(1) == true) return
            return
        }
        if (d < 0 && posMs > 3000) { seekTo(0); return }   // السابق بعد 3 ثواني = من أول الأغنية
        if (shuffle) {
            if (d < 0 && history.isNotEmpty()) { play(a, queue, history.removeAt(history.size - 1), true); return }
            play(a, queue, randomOther()); return
        }
        val n = idx + d
        if (n in queue.indices) play(a, queue, n) else play(a, queue, if (d > 0) 0 else queue.size - 1)
    }
    /** الأغنية خلصت لوحدها */
    private fun onEnded() {
        val a = app ?: return
        if (queue.isEmpty()) return
        if (sleepEndOfTrack) { sleepEndOfTrack = false; wantPlay = false; try { mp?.seekTo(0) } catch (_: Throwable) {}; changed(); return }   // (v200) مؤقت النوم: آخر الأغنية
        val cu = current
        if (mode != 2 && cu != null && YtAudio.isRemote(cu.uri) && ytStep?.invoke(1) == true) return   // (v341) صوت يوتيوب: كمّل على الفيديو التالي
        when {
            mode == 2 -> { try { mp?.seekTo(0); mp?.start() } catch (_: Throwable) {}; changed() }
            shuffle -> play(a, queue, randomOther())
            mode == 1 -> play(a, queue, if (idx + 1 in queue.indices) idx + 1 else 0)
            else -> if (idx + 1 in queue.indices) play(a, queue, idx + 1) else { wantPlay = false; try { mp?.pause(); mp?.seekTo(0) } catch (_: Throwable) {}; changed() }
        }
    }
    fun toggle() { if (isPlaying) pause() else resume() }
    fun resume() {
        val cu = current
        if (cu != null && YtAudio.isRemote(cu.uri) && ytBroken) { ytBroken = false; wantPlay = true; if (ytStep?.invoke(0) == true) { changed(); return } }   // (v341) الرابط خلص/فشل: ▶ بيجيب رابط جديد
        val m = mp
        if (m == null) { app?.let { if (queue.isNotEmpty()) play(it, queue, idx.coerceAtLeast(0)) }; return }
        wantPlay = true; gainFocus()
        if (ready) { try { m.start() } catch (_: Throwable) {}; applySpeed() }
        changed()
    }
    fun pause() { saveYtPos(true); wantPlay = false; try { if (mp?.isPlaying == true) mp?.pause() } catch (_: Throwable) {}; changed() }
    fun seekTo(ms: Int) { if (ready) try { mp?.seekTo(ms) } catch (_: Throwable) {}; changed() }
    private fun release() { try { loud?.release() } catch (_: Throwable) {}; loud = null; try { mp?.release() } catch (_: Throwable) {}; mp = null; ready = false }
    /** إيقاف نهائي (زرار ✕ في الإشعار) */
    fun stopAll() {
        sleepAtMs = 0L; sleepEndOfTrack = false; main.removeCallbacks(sleepTick)
        saveYtPos(true)
        wantPlay = false; release(); dropFocus(); queue = emptyList(); idx = -1
        app?.let { MusicService.stop(it) }
        changed()
    }
}

/** (v190) خدمة foreground لتشغيل الموسيقى في الخلفية: إشعار تحكم (⏮ ⏯ ⏭ ✕) + MediaSession (أزرار السماعة والشاشة المقفولة) */
class MusicService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var recv: BroadcastReceiver? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        try {
            val s = MediaSession(this, "subtitler-music")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { MusicEngine.resume() }
                override fun onPause() { MusicEngine.pause() }
                override fun onSkipToNext() { MusicEngine.step(1) }
                override fun onSkipToPrevious() { MusicEngine.step(-1) }
                override fun onSeekTo(pos: Long) { MusicEngine.seekTo(pos.toInt()) }
                override fun onStop() { MusicEngine.stopAll() }
            })
            s.isActive = true
            session = s
        } catch (e: Throwable) { LogStore.err("music-session", e) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { MusicEngine.pause(); return }
                when (i?.getStringExtra("cmd")) {
                    "prev" -> MusicEngine.step(-1); "next" -> MusicEngine.step(1); "toggle" -> MusicEngine.toggle()
                    "close" -> MusicEngine.stopAll()
                    "noisy" -> MusicEngine.pause()
                }
            }
        }
        recv = r
        try {
            val f = IntentFilter(ACT); f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        } catch (e: Throwable) { LogStore.err("music-recv", e) }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        return START_NOT_STICKY
    }

    private fun pi(cmd: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(this, 100 + code, Intent(ACT).setPackage(packageName).putExtra("cmd", cmd), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(res: Int, label: String, cmd: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, res), label, pi(cmd, code)).build()

    private fun clock(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "تشغيل الموسيقى", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val t = MusicEngine.current
        val playing = MusicEngine.isPlaying
        val dur = MusicEngine.durMs; val pos = MusicEngine.posMs
        val title = t?.title ?: "الموسيقى"
        val sub = (if (!t?.artist.isNullOrBlank()) t!!.artist + " · " else "") + (if (dur > 0) "${clock(pos)} / ${clock(dur)}" else "")
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "music"), PendingIntent.FLAG_IMMUTABLE)
        try {
            session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist ?: "").putLong(MediaMetadata.METADATA_KEY_DURATION, dur.toLong().coerceAtLeast(0)).build())
            session?.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos.toLong(), if (playing) 1f else 0f).build())
        } catch (_: Throwable) {}
        val b = Notification.Builder(this, CH)
            .setContentTitle(title).setContentText(sub)
            .setSmallIcon(R.drawable.ic_stat_sub)
            .setContentIntent(open).setOngoing(playing).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "السابق", "prev", 1))
            .addAction(act(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "إيقاف" else "تشغيل", "toggle", 2))
            .addAction(act(android.R.drawable.ic_media_next, "التالي", "next", 3))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "إغلاق", "close", 4))
        val ms = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        try { session?.sessionToken?.let { ms.setMediaSession(it) } } catch (_: Throwable) {}
        b.setStyle(ms)
        return b.build()
    }

    private fun refreshNow() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build()) } catch (e: Throwable) { LogStore.err("music-notif", e) }
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        try { recv?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        try { session?.isActive = false; session?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val CH = "music_ctl"
        private const val ID = 78
        const val ACT = "com.tttt.subtitler.MUSIC_CMD"
        @Volatile private var inst: MusicService? = null
        fun start(c: Context) { try { c.startForegroundService(Intent(c, MusicService::class.java)) } catch (_: Exception) {} }
        fun stop(c: Context) { try { c.stopService(Intent(c, MusicService::class.java)) } catch (_: Exception) {} }
        fun refresh() { val s = inst ?: return; s.main.post { s.refreshNow() } }
    }
}
