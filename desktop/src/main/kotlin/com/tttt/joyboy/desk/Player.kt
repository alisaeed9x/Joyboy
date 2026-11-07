package com.tttt.joyboy.desk

import org.json.JSONObject
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*

/** مشغّل الفيديو (VLC عن طريق vlcj) — بيشغّل لينكات الموبايل مباشرة (بث من غير نسخ) + ملفات اللابتوب */
object Player {
    @Volatile private var comp: EmbeddedMediaPlayerComponent? = null
    private var frame: JFrame? = null
    private var title = ""
    @Volatile private var media = false
    @Volatile var vlcOk = false
    private var ccOn = true
    private var shiftMs = 0L
    private var lastSub: File? = null
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIdx = 2
    private lateinit var seek: JSlider
    private lateinit var timeLbl: JLabel
    private lateinit var playBtn: JButton
    private var dragging = false

    fun vlcProbe(): Boolean = try { NativeDiscovery().discover().also { vlcOk = it } } catch (_: Throwable) { false }
    fun hasMedia() = media && comp != null
    fun volume(): Int = try { comp?.mediaPlayer()?.audio()?.volume() ?: -1 } catch (_: Throwable) { -1 }

    private fun ensure(): Boolean {
        if (comp != null) return true
        vlcOk = try { NativeDiscovery().discover() } catch (_: Throwable) { false }
        if (!vlcOk) return false
        SwingUtilities.invokeAndWait {
            val c = EmbeddedMediaPlayerComponent()
            comp = c
            val f = JFrame("Joyboy — مشغّل")
            f.defaultCloseOperation = JFrame.HIDE_ON_CLOSE
            f.layout = BorderLayout()
            f.add(c, BorderLayout.CENTER)
            seek = JSlider(0, 1000, 0)
            timeLbl = JLabel(" 00:00 / 00:00 ")
            playBtn = JButton("⏸")
            playBtn.addActionListener { c.mediaPlayer().controls().pause() }
            val stop = JButton("⏹"); stop.addActionListener { stopAll() }
            val fs = JButton("⛶"); fs.addActionListener { c.mediaPlayer().fullScreen().toggle() }
            seek.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) { dragging = true }
                override fun mouseReleased(e: java.awt.event.MouseEvent) { dragging = false; c.mediaPlayer().controls().setPosition(seek.value / 1000f) }
            })
            val bar = JPanel(BorderLayout(6, 0)); bar.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            val left = JPanel(); left.add(playBtn); left.add(stop)
            val right = JPanel(); right.add(timeLbl); right.add(fs)
            bar.add(left, BorderLayout.WEST); bar.add(seek, BorderLayout.CENTER); bar.add(right, BorderLayout.EAST)
            f.add(bar, BorderLayout.SOUTH)
            f.size = Dimension(960, 560)
            f.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_SPACE -> c.mediaPlayer().controls().pause()
                        KeyEvent.VK_LEFT -> c.mediaPlayer().controls().skipTime(-10000)
                        KeyEvent.VK_RIGHT -> c.mediaPlayer().controls().skipTime(10000)
                        KeyEvent.VK_F -> c.mediaPlayer().fullScreen().toggle()
                    }
                }
            })
            frame = f
            Timer(500) { tick() }.start()
        }
        return true
    }

    private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%02d:%02d".format(s / 60, s % 60).let { if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else it } }

    private fun tick() {
        val c = comp ?: return
        try {
            val len = c.mediaPlayer().status().length(); val t = c.mediaPlayer().status().time()
            timeLbl.text = " ${fmt(t)} / ${fmt(len)} "
            if (!dragging && len > 0) seek.value = (t * 1000 / len).toInt()
            playBtn.text = if (c.mediaPlayer().status().isPlaying()) "⏸" else "▶"
        } catch (_: Throwable) {}
    }

    fun play(url: String, ttl: String, startMs: Long, ref: String, ua: String, cookie: String) {
        if (!ensure()) {
            // مفيش VLC: افتح اللينك بالمشغّل الافتراضي للويندوز
            try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Throwable) {}
            Link.onNotice("⚠ VLC مش متسطّب — اتفتح اللينك بالمشغّل الافتراضي. سطّب VLC من videolan.org"); return
        }
        title = ttl.ifBlank { try { java.net.URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8") } catch (_: Throwable) { url } }
        val opts = ArrayList<String>()
        if (ref.isNotEmpty()) opts.add(":http-referrer=$ref")
        if (ua.isNotEmpty()) opts.add(":http-user-agent=$ua")
        if (startMs > 0) opts.add(":start-time=${startMs / 1000.0}")
        media = true; shiftMs = 0; ccOn = true; speedIdx = 2
        SwingUtilities.invokeLater {
            frame?.title = "Joyboy — $title"; frame?.isVisible = true; frame?.toFront()
            comp!!.mediaPlayer().media().play(url, *opts.toTypedArray())
        }
    }

    fun stopAll() { try { comp?.mediaPlayer()?.controls()?.stop() } catch (_: Throwable) {}; media = false; SwingUtilities.invokeLater { frame?.isVisible = false } }

    fun loadSub(f: File): Boolean {
        val c = comp ?: return false
        lastSub = f
        return try { c.mediaPlayer().subpictures().setSubTitleFile(f); true } catch (_: Throwable) { false }
    }

    fun act(a: String, v: Long) {
        val c = comp ?: return
        val mp = c.mediaPlayer()
        try {
            when (a) {
                "toggle" -> mp.controls().pause()
                "seek" -> mp.controls().skipTime(if (Math.abs(v) <= 1L) v * 10000L else v)
                "seekto" -> mp.controls().setTime(v.coerceAtLeast(0))
                "speed" -> { speedIdx = (speedIdx + 1) % speeds.size; mp.controls().setRate(speeds[speedIdx]) }
                "cc" -> { ccOn = !ccOn; if (!ccOn) mp.subpictures().setTrack(-1) else lastSub?.let { mp.subpictures().setSubTitleFile(it) } }
                "shift" -> { shiftMs += v; mp.subpictures().setDelay(shiftMs * 1000L) }
                "loop" -> {}
            }
        } catch (_: Throwable) {}
    }

    fun cmd(c: String) {
        val mp = comp?.mediaPlayer() ?: return
        try {
            when {
                c == "vol_up" -> mp.audio().setVolume((mp.audio().volume() + 10).coerceAtMost(200))
                c == "vol_down" -> mp.audio().setVolume((mp.audio().volume() - 10).coerceAtLeast(0))
                c == "mute" -> mp.audio().mute()
                c == "playpause" -> mp.controls().pause()
                c.startsWith("vol_set:") -> mp.audio().setVolume((c.substring(8).toIntOrNull() ?: 100).coerceIn(0, 100))
            }
        } catch (_: Throwable) {}
    }

    fun state(): JSONObject {
        val mp = comp!!.mediaPlayer()
        val j = JSONObject().put("kind", "player").put("title", title)
        try {
            j.put("pos", mp.status().time()).put("dur", maxOf(0L, mp.status().length())).put("playing", mp.status().isPlaying())
            j.put("speed", speeds[speedIdx].toString().removeSuffix("0").removeSuffix(".") + "x").put("cc", ccOn).put("vol", mp.audio().volume()).put("shift", shiftMs)
        } catch (_: Throwable) {}
        j.put("wtr", "free").put("fit", "fit").put("scale", 100).put("audio", false).put("quality", false).put("tr", "")
        return j
    }
}
