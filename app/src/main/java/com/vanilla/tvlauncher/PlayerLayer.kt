@file:OptIn(UnstableApi::class)

package com.vanilla.tvlauncher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * مشغّل داخلي (media3 ExoPlayer — D-008 محدَّث) كطبقة فوق الشاشة، مش Activity منفصلة → Back بيعدّي على BackRouter (D-002).
 * الصيغ: mkv/mp4/webm/ts/mov وغيرها بتتفتح بمستخرجات media3؛ الفيديو (H.264/HEVC/VP9/AV1) والصوت (AAC/AC3/EAC3/DTS…) بيتفكّوا
 * بمفكّكات الجهاز نفسه (decoder fallback مفعّل) — يعني الدعم الفعلي بيعتمد على شريحة التلفزيون. AVI/WMV مش مدعومين من media3.
 * لو الكودك مش مدعوم: رسالة بنوع الكودك، ولو فشل التشغيل كله بيتفتح بمشغّل خارجي لو موجود.
 * التحكم: ◀ ▶ ±10ث (تسارع) · OK إيقاف/تشغيل · ▲ (والشريط ظاهر) مسار الصوت التالي · ▼ (والشريط ظاهر) السرعة · ⏮ ⏭ قايمة التشغيل فقط.
 */
class PlayerLayer(private val act: Activity, private val ui: Ui, private val th: Theme, private val onClosed: () -> Unit) {
    val root = FrameLayout(act).apply { setBackgroundColor(0xFF000000.toInt()); visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_LTR }
    private val surface = SurfaceView(act)
    private val titleTv: TextView = ui.text("", 20f, 0xFFFFFFFF.toInt(), true)
    private val timeTv: TextView = ui.text("", 18f, 0xFFFFFFFF.toInt(), true)
    private val stateTv: TextView = ui.text("", 40f, 0xFFFFFFFF.toInt(), true)
    private val hintTv: TextView = ui.text("◀ ▶ تقديم/تأخير · OK إيقاف · ▲ مسار الصوت · ▼ السرعة · ⏮ ⏭ السابق/التالي", 13f, 0xFFCCCCCC.toInt())
    private val barFill = View(act).apply { setBackgroundColor(th.primary) }
    private val barRest = View(act).apply { setBackgroundColor(0x55FFFFFF) }
    private val controls = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(32), ui.dp(16), ui.dp(32), ui.dp(20)); setBackgroundColor(0xAA000000.toInt()) }
    private val h = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var queue: PlayQueue? = null
    private var cur: VideoItem? = null
    private var prepared = false
    private var endedHandled = false
    private var lastSaveAt = 0L
    private var hideAt = 0L
    private var lastSeekKeyAt = 0L
    private var seekStreak = 0
    private val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    private var speedIdx = 0

    val active: Boolean get() = root.visibility == View.VISIBLE
    val playing: Boolean get() = active && prepared && (player?.isPlaying == true)

    init {
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        stateTv.gravity = Gravity.CENTER
        root.addView(stateTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        controls.addView(titleTv)
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(barFill, LinearLayout.LayoutParams(0, ui.dp(6), 0f)); bar.addView(barRest, LinearLayout.LayoutParams(0, ui.dp(6), 1f))
        controls.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(10), 0, ui.dp(8)) })
        controls.addView(timeTv); controls.addView(hintTv)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> stateTv.text = "…"
                Player.STATE_READY -> { if (!prepared) { prepared = true; showControls() }; stateTv.text = "" }
                Player.STATE_ENDED -> onEnded()
            }
        }
        override fun onPlayerError(error: PlaybackException) { onFailed(error.errorCodeName) }
        override fun onTracksChanged(tracks: Tracks) {
            // رسالة صريحة لما كودك مش مدعوم على الجهاز (بدل شاشة سودة/صمت من غير تفسير)
            for (g in tracks.groups) {
                if (g.length == 0 || g.isSupported) continue
                val mime = g.getTrackFormat(0).sampleMimeType ?: "?"
                if (g.type == C.TRACK_TYPE_VIDEO) Toast.makeText(act, "كودك الفيديو ($mime) مش مدعوم على التلفزيون ده", Toast.LENGTH_LONG).show()
                else if (g.type == C.TRACK_TYPE_AUDIO && tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO && it.isSupported })
                    Toast.makeText(act, "كودك الصوت ($mime) مش مدعوم — الفيديو هيشتغل بدون صوت", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val rf = DefaultRenderersFactory(act).setEnableDecoderFallback(true)   // نفس إعداد Subtitler: لو مفكّك فشل جرّب اللي بعده
        val p = ExoPlayer.Builder(act, rf).build()
        p.setVideoSurfaceView(surface); p.addListener(listener); p.setPlaybackSpeed(speeds[speedIdx])
        player = p; return p
    }

    fun open(q: PlayQueue) {
        val first = q.current ?: return
        queue = q; root.visibility = View.VISIBLE
        start(first)
        h.removeCallbacks(ticker); h.post(ticker)
    }

    private fun start(v: VideoItem) {
        saveNow()
        cur = v; prepared = false; endedHandled = false; lastSaveAt = 0L
        titleTv.text = v.name; timeTv.text = ""; stateTv.text = "…"
        val resume = HistoryLogic.resumePos(HistoryStore.entryFor(v))
        try {
            val p = ensurePlayer()
            p.setMediaItem(MediaItem.fromUri(Uri.parse(v.uri)), resume)
            p.prepare(); p.playWhenReady = true
        } catch (e: Throwable) { onFailed(e.javaClass.simpleName) }
        showControls()
    }

    private fun posMs(): Long = try { player?.currentPosition ?: 0L } catch (_: Throwable) { 0L }
    private fun durMs(): Long = try { (player?.duration ?: 0L).let { if (it < 0) 0L else it } } catch (_: Throwable) { 0L }

    /** حفظ دوري (10ث) + عند الإيقاف/pause/الخروج. «مكتمل» بقاعدة HistoryLogic أو انتهاء حقيقي فقط. */
    private fun saveNow() {
        val v = cur ?: return; if (!prepared) return
        HistoryStore.record(act, v, posMs(), durMs()); lastSaveAt = System.currentTimeMillis()
    }

    private fun onEnded() {
        if (endedHandled) return; endedHandled = true
        val v = cur
        if (v != null) HistoryStore.record(act, v, 0, durMs(), ended = true)
        val n = queue?.next()
        if (n != null) start(n) else close(save = false)
    }

    /** فشل التشغيل الداخلي: رسالة بالكود + محاولة أخيرة بمشغّل خارجي (لو موجود) ثم نقفل */
    private fun onFailed(code: String) {
        val v = cur
        Toast.makeText(act, "تعذّر التشغيل الداخلي ($code)", Toast.LENGTH_LONG).show()
        close(save = true)
        if (v != null && openExternal(v)) Toast.makeText(act, "اتفتح بمشغّل تاني", Toast.LENGTH_SHORT).show()
    }

    private fun openExternal(v: VideoItem): Boolean = try {
        act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(v.uri), "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); true
    } catch (_: Throwable) { false }

    fun close(save: Boolean = true) {
        if (!active) return
        if (save) saveNow()
        h.removeCallbacks(ticker)
        try { player?.release() } catch (_: Throwable) { }
        player = null; prepared = false; cur = null; queue = null
        root.visibility = View.GONE
        onClosed()
    }

    fun onPause() { if (active) { saveNow(); try { player?.pause() } catch (_: Throwable) { }; showControls() } }

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            if (prepared) {
                val pos = posMs(); val dur = durMs()
                val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                (barFill.layoutParams as LinearLayout.LayoutParams).weight = frac; (barRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - frac
                barFill.requestLayout()
                val q = queue
                timeTv.text = LibraryLogic.formatDur(pos) + " / " + LibraryLogic.formatDur(dur) +
                    (if (speeds[speedIdx] != 1f) "     ${speeds[speedIdx]}x" else "") +
                    (if (q != null && q.items.size > 1) "     ${q.index + 1}/${q.items.size}" else "")
                if (System.currentTimeMillis() - lastSaveAt >= 10_000L && playing) saveNow()
            }
            if (controls.visibility == View.VISIBLE && playing && System.currentTimeMillis() > hideAt) controls.visibility = View.GONE
            h.postDelayed(this, 500)
        }
    }

    private fun showControls() { controls.visibility = View.VISIBLE; hideAt = System.currentTimeMillis() + 4000L }
    private fun flash(s: String, ms: Long = 700L) { stateTv.text = s; h.postDelayed({ if (stateTv.text == s) stateTv.text = "" }, ms) }

    private fun seekBy(dir: Int) {
        val p = player ?: return; if (!prepared) return
        val now = System.currentTimeMillis()
        seekStreak = if (now - lastSeekKeyAt < 700L) seekStreak + 1 else 0
        lastSeekKeyAt = now
        val step = if (seekStreak >= 5) 30_000L else 10_000L
        val dur = durMs()
        p.seekTo((posMs() + dir * step).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE))
        flash(if (dir > 0) "▶▶" else "◀◀", 500L); showControls()
    }

    private fun toggle() {
        val p = player ?: return; if (!prepared) return
        if (p.isPlaying) { p.pause(); saveNow(); stateTv.text = "⏸" } else { p.play(); stateTv.text = "" }
        showControls()
    }

    private fun cycleAudio() {
        val p = player ?: return
        val gs = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (gs.size < 2) { flash("مفيش مسار صوت تاني", 1200L); return }
        val n = gs[(gs.indexOfFirst { it.isSelected } + 1) % gs.size]
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(n.mediaTrackGroup, 0)).build()
        val f = n.getTrackFormat(0)
        flash("🔊 " + (f.language ?: f.label ?: "?") + (if (f.channelCount > 0) " · ${f.channelCount}ch" else ""), 1500L)
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        try { player?.setPlaybackSpeed(speeds[speedIdx]) } catch (_: Throwable) { }
        flash("${speeds[speedIdx]}x", 900L)
    }

    private fun jump(n: VideoItem?) { if (n != null) start(n) else flash("—", 600L) }

    /** يستهلك مفاتيح التحكم فقط. BACK مش هنا إطلاقًا — بيمر على BackRouter. */
    fun handleKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { if (down && e.repeatCount == 0) toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down && prepared && player?.isPlaying == false) toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down && prepared && player?.isPlaying == true) toggle(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seekBy(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seekBy(-1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down && e.repeatCount == 0) { if (controls.visibility == View.VISIBLE) cycleAudio(); showControls() }; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down && e.repeatCount == 0) { if (controls.visibility == View.VISIBLE) cycleSpeed(); showControls() }; return true }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { if (down && e.repeatCount == 0) cycleAudio(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && e.repeatCount == 0) jump(queue?.next()); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (down && e.repeatCount == 0) jump(queue?.prev()); return true }
        }
        return false
    }
}
