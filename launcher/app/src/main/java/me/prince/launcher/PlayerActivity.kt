package me.prince.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Player"

/**
 * Simple full-screen player on libVLC (plays nearly any container/codec; YouTube = video-only stream + separate audio slave).
 * The screen shows only video + subtitles; every control and menu lives on the phone (Subtitler's remote talks to
 * remoteState()/remoteAct() through TvLink /player). Entries are file/content/http URIs or "yt:<videoId>".
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var lib: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var layout: VLCVideoLayout
    private lateinit var spinner: TextView
    private var entries: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var idx = -1
    private var curKey = ""
    private var title = ""
    private var attached = false
    private var speedIdx = 2
    private var fitIdx = 0
    private var shiftMs = 0L
    private var yq: List<YQ> = emptyList()
    private var yqi = 0
    private var gen = 0   // bumps on every open so a late YouTube resolve of an old entry is dropped
    private val handler = Handler(Looper.getMainLooper())

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private val fits = arrayOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT to "ملاءمة",
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "ملء بالقص",
        MediaPlayer.ScaleType.SURFACE_FILL to "تمديد",
        MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
        MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
    )

    companion object {
        private var ref: WeakReference<PlayerActivity>? = null
        private fun live(): PlayerActivity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "wmv", "flv", "mpg", "mpeg", "3gp")

        private fun <T> onUi(f: () -> T): T? {
            if (Looper.myLooper() == Looper.getMainLooper()) return try { f() } catch (_: Throwable) { null }
            val out = AtomicReference<T?>(); val l = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { try { out.set(f()) } catch (e: Throwable) { Log.w(TAG, "ui", e) }; l.countDown() }
            l.await(1500, TimeUnit.MILLISECONDS)
            return out.get()
        }
        fun remoteState(): JSONObject? { val p = live() ?: return null; return onUi { p.state() } }
        fun remoteAct(a: String, v: Long): Boolean { val p = live() ?: return false; return onUi { p.act(a, v) } == true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        layout = VLCVideoLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        spinner = TextView(this).apply { text = "⏳"; textSize = 40f; setTextColor(0xCCFFFFFF.toInt()) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(layout, FrameLayout.LayoutParams(-1, -1))
            addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
        lib = LibVLC(this, arrayListOf("--audio-time-stretch", "--sub-autodetect-file", "--http-user-agent=" + Yt.PLAY_UA))
        mp = MediaPlayer(lib)
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> handler.post { spinner.visibility = View.GONE }
                MediaPlayer.Event.Buffering -> handler.post { spinner.visibility = if (e.buffering < 100f) View.VISIBLE else View.GONE }
                MediaPlayer.Event.EndReached -> handler.post { onEnded() }
                MediaPlayer.Event.EncounteredError -> handler.post { Toast.makeText(this, "⚠ ما قدرتش أشغّل الفيديو", Toast.LENGTH_LONG).show(); finish() }
                else -> {}
            }
        }
        ref = WeakReference(this)
        load(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); load(intent) }

    private fun load(i: Intent) {
        val cur = i.data?.toString() ?: i.getStringExtra("yt")?.let { "yt:$it" } ?: i.getStringExtra("url") ?: run { finish(); return }
        val list = i.getStringArrayListExtra("list")
        if (!list.isNullOrEmpty()) { entries = list; names = i.getStringArrayListExtra("names") ?: emptyList() }
        else { entries = siblingsOf(cur); names = emptyList() }
        if (cur !in entries) { entries = listOf(cur); names = emptyList() }
        openAt(entries.indexOf(cur), i.getStringExtra("title"), i.getStringExtra("ref"), i.getStringExtra("ua"))
    }

    private fun siblingsOf(key: String): List<String> {
        val u = Uri.parse(key)
        if (u.scheme != "file") return emptyList()
        val dir = File(u.path ?: return emptyList()).parentFile ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() in VIDEO_EXT }?.sortedBy { it.name.lowercase() }?.map { Uri.fromFile(it).toString() } ?: emptyList()
    }

    private fun openAt(n: Int, forcedTitle: String? = null, ref: String? = null, ua: String? = null) {
        if (n !in entries.indices) return
        saveResume()
        idx = n; curKey = entries[n]; yq = emptyList(); yqi = 0; shiftMs = 0L
        title = forcedTitle?.takeIf { it.isNotBlank() } ?: names.getOrNull(n) ?: Uri.parse(curKey).lastPathSegment.orEmpty().substringAfterLast('/')
        spinner.visibility = View.VISIBLE
        val g = ++gen
        val resume = getSharedPreferences("resume", Context.MODE_PRIVATE).getLong(curKey, 0L)
        if (curKey.startsWith("yt:")) {
            Thread {
                val p = try { Yt.pick(curKey.removePrefix("yt:")) } catch (e: Throwable) { Log.w(TAG, "yt", e); null }
                handler.post {
                    if (g != gen || isFinishing) return@post
                    if (p == null) { Toast.makeText(this, "⚠ ما قدرتش أجيب الفيديو من يوتيوب", Toast.LENGTH_LONG).show(); return@post }
                    if (title.isBlank()) title = p.title
                    yq = p.quals; yqi = p.start
                    startMedia(p.quals[p.start].url, p.quals[p.start].audio, resume, null, null)
                }
            }.also { it.isDaemon = true }.start()
        } else startMedia(curKey, null, resume, ref, ua)
    }

    private fun startMedia(url: String, audio: String?, startMs: Long, ref: String?, ua: String?) {
        val m = Media(lib, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        if (audio != null) m.addSlave(Media.Slave(Media.Slave.Type.Audio, 4, audio))
        ref?.takeIf { it.isNotBlank() }?.let { m.addOption(":http-referrer=$it") }
        ua?.takeIf { it.isNotBlank() }?.let { m.addOption(":http-user-agent=$it") }
        if (startMs > 5000) m.addOption(":start-time=${startMs / 1000}")
        mp.media = m
        m.release()
        mp.play()
        mp.rate = speeds[speedIdx]
        mp.videoScale = fits[fitIdx].first
    }

    private fun step(d: Int): Boolean { val n = idx + d; if (n !in entries.indices) return false; openAt(n); return true }

    private fun onEnded() {
        clearResume()
        if (getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("loop", false)) { mp.time = 0; mp.play(); return }
        if (!step(1)) finish()
    }

    private fun saveResume() {
        if (curKey.isEmpty()) return
        try {
            val p = mp.time; val d = mp.length
            val sp = getSharedPreferences("resume", Context.MODE_PRIVATE).edit()
            if (d > 0 && p > 5000 && p < d - 15000) sp.putLong(curKey, p) else sp.remove(curKey)
            sp.apply()
        } catch (_: Throwable) {}
    }
    private fun clearResume() { if (curKey.isNotEmpty()) getSharedPreferences("resume", Context.MODE_PRIVATE).edit().remove(curKey).apply() }

    override fun onStart() { super.onStart(); if (!attached) { mp.attachViews(layout, null, false, false); attached = true; mp.videoScale = fits[fitIdx].first } }
    override fun onStop() { saveResume(); mp.pause(); if (attached) { mp.detachViews(); attached = false }; super.onStop() }
    override fun onDestroy() {
        saveResume(); gen++
        try { mp.stop(); mp.release(); lib.release() } catch (_: Throwable) {}
        if (ref?.get() === this) ref = null
        super.onDestroy()
    }

    // keys from a physical TV remote (the phone remote is the main control)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> act("toggle", 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> act("seek", -10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> act("seek", 10_000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> act("seek", -30_000)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> act("seek", 30_000)
            KeyEvent.KEYCODE_MEDIA_NEXT -> act("next", 0)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> act("prev", 0)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ===== phone remote =====
    private fun audioList(): List<MediaPlayer.TrackDescription> = (mp.audioTracks?.toList() ?: emptyList()).filter { it.id >= 0 }
    private fun subList(): List<MediaPlayer.TrackDescription> = (mp.spuTracks?.toList() ?: emptyList()).filter { it.id >= 0 }

    private fun state(): JSONObject {
        val j = JSONObject()
        j.put("title", title).put("pos", mp.time.coerceAtLeast(0L)).put("dur", mp.length.coerceAtLeast(0L)).put("playing", mp.isPlaying)
        j.put("speed", speeds[speedIdx].toString().removeSuffix(".0") + "x").put("fit", fits[fitIdx].second)
        j.put("cc", mp.spuTrack >= 0).put("scale", 100).put("shift", shiftMs)
        val al = audioList()
        j.put("audio", al.size > 1).put("quality", yq.size > 1).put("tr", "")
        j.put("kind", "player").put("loop", getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("loop", false))
        j.put("dual", 0).put("bgon", false).put("nots", 0).put("locked", false).put("font", "").put("eng", "").put("cnt", "")
        j.put("trcolor", 0xFFE53935.toInt())
        j.put("al", JSONArray(al.map { it.name })).put("ac", al.indexOfFirst { it.id == mp.audioTrack })
        j.put("ql", JSONArray(yq.map { it.label })).put("qc", yqi)
        return j
    }

    private fun act(a: String, v: Long): Boolean {
        when (a) {
            "toggle" -> if (mp.isPlaying) mp.pause() else mp.play()
            "seek" -> mp.time = (mp.time + v).coerceIn(0L, mp.length.takeIf { it > 0 } ?: Long.MAX_VALUE)
            "seekto" -> mp.time = v.coerceIn(0L, mp.length.takeIf { it > 0 } ?: Long.MAX_VALUE)
            "prev" -> step(-1)
            "next" -> step(1)
            "speed" -> { speedIdx = (speedIdx + 1) % speeds.size; mp.rate = speeds[speedIdx] }
            "fit" -> { fitIdx = (fitIdx + 1) % fits.size; mp.videoScale = fits[fitIdx].first }
            "cc" -> { if (mp.spuTrack >= 0) mp.spuTrack = -1 else subList().firstOrNull()?.let { mp.spuTrack = it.id } }
            "subs" -> { val l = subList(); if (l.isNotEmpty()) { val i = l.indexOfFirst { it.id == mp.spuTrack }; mp.spuTrack = if (i + 1 < l.size) l[i + 1].id else -1 } }
            "shift" -> { shiftMs += v; mp.spuDelay = shiftMs * 1000L }
            "aset" -> audioList().getOrNull(v.toInt())?.let { mp.audioTrack = it.id }
            "audio" -> { val l = audioList(); if (l.size > 1) { val i = l.indexOfFirst { it.id == mp.audioTrack }; mp.audioTrack = l[(i + 1) % l.size].id } }
            "qset" -> yq.getOrNull(v.toInt())?.let { q -> val at = mp.time; yqi = v.toInt(); spinner.visibility = View.VISIBLE; startMedia(q.url, q.audio, at, null, null) }
            "loop" -> { val sp = getSharedPreferences("player", Context.MODE_PRIVATE); sp.edit().putBoolean("loop", !sp.getBoolean("loop", false)).apply() }
            "scale", "quality", "translate", "more", "log", "sent", "chars", "dual", "bgon", "ai", "lock", "notice", "visual", "menu", "tools", "font" -> {}   // not in the launcher player yet
            else -> return false
        }
        return true
    }
}
