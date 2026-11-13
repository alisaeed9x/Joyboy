package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import com.newtube.mobile.ui.playback.JoyboyPlayerHost
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MtBar — the old player's full button bar, rebuilt as a Joyboy overlay inside
 * MeeTube's player (v942). attachBar() is called from MtTranslate.extraControls()
 * with mControlsRoot; attachCircles() from MtTranslate.attach() with mVideoArea.
 * Every button wires to MtTranslate / MtMedia / Engine / Store / Sub* where the
 * function exists; the rest (visualSnap, snapFrame, export, speed, sleep, dialect,
 * voice, log, chars, batches) is copy-adapted from Player.kt here.
 * The top-row pills in mOptionsRow (MtTranslate.attach) are kept — they are
 * working code and are not removed by this bar.
 */
object MtBar {

    private const val TAG_BAR = "mtbar_bar"
    private const val TAG_TOOLS = "mtbar_tools"
    private const val TAG_CIRCLES = "mtbar_circles"
    private const val TAG_VIS = "mtbar_vis"
    private const val TAG_SYNC = "mtbar_sync"
    private const val TAG_LOCKOV = "mtbar_lockov"
    private const val REST_A = 0.25f
    private const val BAR_BOTTOM_DP = 84

    private val ui = Handler(Looper.getMainLooper())
    private var act: Activity? = null
    private var player: ExoPlayer? = null
    private var info: JoyboyPlayerHost.VideoInfo? = null
    private var uiHelper: Ui? = null
    private var barRoot: ViewGroup? = null
    private var toolsRoot: ViewGroup? = null
    private var trChipLast = ""
    private var circlesRoot: ViewGroup? = null
    private var visOv: VisualOverlay? = null
    private var visual: VisualMode? = null
    private var visConf: Conf? = null
    private var visConfAt = 0L
    private var visBusy = false
    private var visTok = 0
    private var locked = false
    private var lockOv: TextView? = null
    private var lockB: TextView? = null
    private var lockCircle: TextView? = null
    private var trChip: TextView? = null
    private var pauseB: TextView? = null
    private var stopB: TextView? = null
    private var playB: TextView? = null
    private var fitB: TextView? = null
    private var audModePill: TextView? = null
    private var batchWarn: TextView? = null
    private var volBar: SeekBar? = null
    private var volLab: TextView? = null
    private var syncStrip: ViewGroup? = null
    private var syncValV: TextView? = null
    private var tvMenuDlg: android.app.Dialog? = null
    private var batchDlg: android.app.Dialog? = null
    private var voicePb: VoiceClean.Playback? = null
    private var ticking = false
    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try {
                refreshPlay()
                refreshTrChip()
                tickCount++
                if (tickCount % 8 == 0) refreshBatchWarn()
            } catch (_: Throwable) {}
            ui.postDelayed(this, 700L)
        }
    }

    private fun dp(a: Activity, v: Int): Int {
        return (v * a.resources.displayMetrics.density).toInt()
    }

    private fun fitName(i: Int): String {
        return when (i) {
            0 -> "احتواء"
            1 -> "ملء (قص)"
            2 -> "تمديد"
            3 -> "100%"
            else -> "احتواء"
        }
    }

    private fun removeByTag(parent: ViewGroup?, tag: String) {
        if (parent == null) return
        for (i in parent.childCount - 1 downTo 0) {
            val c = parent.getChildAt(i)
            if (c != null && c.tag == tag) parent.removeViewAt(i)
        }
    }

    private fun fadeCircle(v: View) {
        v.alpha = REST_A
        v.setOnTouchListener { vv, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                vv.alpha = 1f
                ui.removeCallbacks(fadeRun(vv))
                ui.postDelayed(fadeRun(vv), 1200L)
            }
            false
        }
    }

    private val fadeRunnables = HashMap<View, Runnable>()

    private fun fadeRun(v: View): Runnable {
        var r = fadeRunnables[v]
        if (r == null) {
            r = Runnable {
                try {
                    v.alpha = REST_A
                } catch (_: Throwable) {}
            }
            fadeRunnables[v] = r
        }
        return r
    }

    private fun tvFocus(v: View) {
        if (act != null && DeviceUi.isTv(act!!)) v.isFocusable = true
    }

    fun attachBar(activity: Activity, p: ExoPlayer, bar: ViewGroup, i: JoyboyPlayerHost.VideoInfo) {
        act = activity
        player = p
        info = i
        uiHelper = Ui(activity, Themes.current())
        removeByTag(bar, TAG_BAR)
        removeByTag(bar, TAG_TOOLS)
        val root = LinearLayout(activity).apply {
            tag = TAG_BAR
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x66000000, 0xCC000000.toInt())
            )
        }
        // (v944) نفس ترتيب المشغّل القديم: شريط أيقونات واحد فوق (بيتسحب) + شريحة الترجمة (ترجمة · ⚙ · إعادة) + الصف الرئيسي
        root.addView(trRow(activity))
        root.addView(mainRow(activity))
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            root.addView(capsuleRow(activity))
        }
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = dp(activity, BAR_BOTTOM_DP)
        }
        bar.addView(root, lp)
        barRoot = root
        val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val tools = toolbar(activity)
        val tlp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(activity, if (portrait) 72 else 58)
            leftMargin = dp(activity, 14)
            rightMargin = dp(activity, 14)
        }
        bar.addView(tools, tlp)
        toolsRoot = tools
        if (!ticking) {
            ticking = true
            tickCount = 0
            ui.post(tick)
        }
    }

    fun attachCircles(activity: Activity, p: ExoPlayer, overlay: ViewGroup, i: JoyboyPlayerHost.VideoInfo) {
        act = activity
        player = p
        info = i
        if (uiHelper == null) uiHelper = Ui(activity, Themes.current())
        val u = uiHelper!!
        removeByTag(overlay, TAG_VIS)
        removeByTag(overlay, TAG_CIRCLES)
        removeByTag(overlay, TAG_LOCKOV)
        val vis = VisualOverlay(activity).apply {
            tag = TAG_VIS
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        overlay.addView(vis)
        visOv = vis
        val col = LinearLayout(activity).apply {
            tag = TAG_CIRCLES
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(activity, 6)
            }
        }
        col.addView(circle(u, activity, "👁") { visualSnap(activity) })
        col.addView(circle(u, activity, "🎧") { audioHandoff(activity) })
        col.addView(circle(u, activity, "📺") { MtTranslate.sendToTv() })
        audModePill = u.fsBtn("🎧 وضع الصوت") {
            toggleAudioOnly(activity)
        }.apply {
            setOnTouchListener(null)
            layoutParams = LinearLayout.LayoutParams(-2, dp(activity, 34)).apply {
                setMargins(dp(activity, 3), dp(activity, 3), dp(activity, 3), dp(activity, 3))
            }
        }
        col.addView(audModePill)
        batchWarn = u.fsCircle("⚠") { batchPanel(activity) }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)).apply {
                setMargins(0, dp(activity, 6), 0, 0)
            }
        }
        col.addView(batchWarn)
        lockCircle = u.fsCircle("🔓") { toggleLock(activity) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)).apply {
                setMargins(0, dp(activity, 6), 0, 0)
            }
        }
        col.addView(lockCircle)
        overlay.addView(col)
        circlesRoot = col
        val lock = u.fsCircle("🔒") { toggleLock(activity) }.apply {
            tag = TAG_LOCKOV
            textSize = 34f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(activity, 64), dp(activity, 64), Gravity.CENTER)
        }
        overlay.addView(lock)
        lockOv = lock
        if (!ticking) {
            ticking = true
            tickCount = 0
            ui.post(tick)
        }
    }

    private fun circle(u: Ui, a: Activity, t: String, f: () -> Unit): TextView {
        return u.fsCircle(t) { f() }.apply {
            fadeCircle(this)
            layoutParams = LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)).apply {
                setMargins(0, dp(a, 3), 0, dp(a, 3))
            }
        }
    }

    fun detach(activity: Activity) {
        ticking = false
        ui.removeCallbacks(tick)
        try {
            voicePb?.stop()
        } catch (_: Throwable) {}
        voicePb = null
        try {
            barRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Throwable) {}
        try {
            toolsRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Throwable) {}
        try {
            circlesRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Throwable) {}
        try {
            visOv?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Throwable) {}
        try {
            lockOv?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Throwable) {}
        try {
            syncStrip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Throwable) {}
        try {
            tvMenuDlg?.dismiss()
        } catch (_: Throwable) {}
        try {
            batchDlg?.dismiss()
        } catch (_: Throwable) {}
        barRoot = null
        toolsRoot = null
        trChipLast = ""
        circlesRoot = null
        visOv = null
        visual = null
        lockOv = null
        lockB = null
        lockCircle = null
        trChip = null
        pauseB = null
        stopB = null
        playB = null
        fitB = null
        audModePill = null
        batchWarn = null
        volBar = null
        volLab = null
        syncStrip = null
        syncValV = null
        tvMenuDlg = null
        batchDlg = null
        uiHelper = null
        player = null
        info = null
        act = null
        locked = false
        visBusy = false
    }

    private fun refreshPlay() {
        val b = playB ?: return
        val p = player ?: return
        val playing = try {
            p.isPlaying
        } catch (_: Throwable) {
            false
        }
        val t = if (playing) "▮▮" else "▶"
        if (b.text != t) b.text = t
    }

    private fun refreshTrChip() {
        val chip = trChip ?: return
        val u = uiHelper ?: return
        val translating = try {
            MtTranslate.isTranslating()
        } catch (_: Throwable) {
            false
        }
        pauseB?.visibility = if (translating) View.VISIBLE else View.GONE
        stopB?.visibility = if (translating) View.VISIBLE else View.GONE
        val pz = pauseB
        if (pz != null) {
            val t = if (MtTranslate.isPaused()) "▶" else "▮▮"
            if (pz.text != t) pz.text = t
        }
        if (!translating) {
            if (trChipLast != "▶ ترجمة") {
                trChipLast = "▶ ترجمة"
                chip.text = trChipLast
            }
            chip.background = u.box(0xFFE53935.toInt(), 0x33FFFFFF, 12)
            return
        }
        val st = try {
            MtTranslate.coverStats()
        } catch (_: Throwable) {
            null
        }
        val p = player ?: return
        val dur = try {
            p.duration / 1000.0
        } catch (_: Throwable) {
            0.0
        }
        val pct = if (st != null && dur > 0) PlayerLogic.percent(st.second, dur) else 0
        val t = "⏳ " + pct + "%"
        if (trChipLast != t) {
            trChipLast = t
            chip.text = t
        }
        val fill = if (pct >= 100) 0xFF2E7D32.toInt() else 0xFF1F5FBF.toInt()
        chip.background = u.box(fill, 0x33FFFFFF, 12)
    }

    private fun refreshBatchWarn() {
        val bw = batchWarn ?: return
        val n = failedBatchCount()
        if (n > 0) {
            bw.visibility = View.VISIBLE
            bw.text = "⚠" + n
        } else {
            bw.visibility = View.GONE
        }
    }

    private fun failedBatchCount(): Int {
        return try {
            val o = MtTranslate.rpc("engine.batches", JSONObject())
            val arr = o.optJSONArray("batches") ?: return 0
            var n = 0
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val m = b.optString("m")
                if (m.contains("✗") || m.contains("فشل") || m.contains("!")) n++
            }
            n
        } catch (_: Throwable) {
            0
        }
    }

    /** (v944) الشريط العلوي زي القديم: صف واحد أيقونات بس (38dp)، بيتسحب أفقيًا، الأدوات التانية في القوايم */
    private fun toolbar(a: Activity): ViewGroup {
        val u = uiHelper!!
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun lpC(): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(dp(a, 38), dp(a, 38)).apply {
                setMargins(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
            }
        }
        fun circ(t: String, ts: Float, f: () -> Unit): TextView {
            return u.fsCircle(t) { f() }.apply {
                textSize = ts
                layoutParams = lpC()
            }
        }
        fun chev(t: String, f: () -> Unit): TextView {
            return u.fsBtn(t) { f() }.apply {
                textSize = 12f
                minimumWidth = dp(a, 38)
                setPadding(dp(a, 8), 0, dp(a, 8), 0)
                layoutParams = LinearLayout.LayoutParams(-2, dp(a, 38)).apply {
                    setMargins(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
                }
            }
        }
        val sq = { u.box(0xE0141418.toInt(), 0x1FFFFFFF, 12) }
        // من اليمين: ☰ 🧰 📃 HD 🎙 CC 💬 ⏱▾ Aa▾ 🧑 📝 📋 🔂
        row.addView(circ("☰", 14f) { MtTranslate.showMenu() }.apply { background = sq() })
        row.addView(circ("🧰", 14f) { toolboxMenu(a) }.apply { background = sq() })
        row.addView(circ("📃", 12f) { PlayQueueUi.show(a) })
        row.addView(circ("HD", 11f) { bridge(a, "quality") }.apply { typeface = Typeface.DEFAULT_BOLD })
        row.addView(circ("🎙", 12f) { voiceMenu(a) })
        row.addView(circ("CC", 11f) { bridge(a, "cc") }.apply { typeface = Typeface.DEFAULT_BOLD })
        row.addView(circ("💬", 12f) { MtTranslate.cycleDual() })
        row.addView(chev("⏱ ▾") { toggleSyncStrip(a) })
        row.addView(chev("🔤 ▾") { textMenu(a) })
        row.addView(circ("🧑", 12f) { showChars(a) })
        row.addView(circ("📝", 12f) { MtTranslate.searchSentences() })
        row.addView(circ("📜", 12f) { showLog(a) })
        row.addView(circ("🔂", 12f) { loopCycle(a) })
        return HorizontalScrollView(a).apply {
            tag = TAG_TOOLS
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(row, FrameLayout.LayoutParams(-2, -2))
        }
    }

    /** (v944) شريحة الترجمة المدمجة زي القديم: ▶ ترجمة · ⚙ · 🔁 إعادة (+ ▮▮ ✕ لما الترجمة شغّالة) */
    private fun trRow(a: Activity): ViewGroup {
        val u = uiHelper!!
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun lp(w: Int): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(w, dp(a, 38)).apply {
                setMargins(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
            }
        }
        trChipLast = ""
        trChip = u.fsBtn("▶ ترجمة") {
            if (MtTranslate.isTranslating()) MtTranslate.translateMenu() else MtTranslate.toggle()
        }.apply {
            background = u.box(0xFFE53935.toInt(), 0x33FFFFFF, 12)
            layoutParams = lp(-2)
        }
        row.addView(trChip)
        row.addView(u.fsCircle("⚙") { MtTranslate.translateMenu() }.apply {
            textSize = 14f
            background = u.box(0xE0374151.toInt(), 0x33FFFFFF, 12)
            layoutParams = lp(dp(a, 38))
        })
        row.addView(u.fsBtn("🔁 إعادة") {
            if (!MtTranslate.isTranslating()) {
                Notice.show(a, "ابدأ الترجمة الأول", 1800L)
            } else {
                try {
                    MtTranslate.rpc("engine.retranslate", JSONObject())
                    Notice.show(a, "🔁 بيعيد ترجمة الفيديو كله", 2000L)
                } catch (t: Throwable) {
                    LogStore.err("MtBar:redo", t)
                }
            }
        }.apply {
            background = u.box(0xFF6D28A8.toInt(), 0x33FFFFFF, 12)
            layoutParams = lp(-2)
        })
        pauseB = u.fsCircle("▮▮") {
            if (MtTranslate.isPaused()) MtTranslate.resumeTranslate() else MtTranslate.pauseTranslate()
        }.apply {
            textSize = 12f
            visibility = View.GONE
            layoutParams = lp(dp(a, 38))
        }
        row.addView(pauseB)
        stopB = u.fsCircle("✕") { MtTranslate.stopTranslate() }.apply {
            textSize = 12f
            visibility = View.GONE
            layoutParams = lp(dp(a, 38))
        }
        row.addView(stopB)
        return row
    }

    /** (v944) الصف الرئيسي بنفس أحجام القديم: شمال 🔓 🗣▾ 🎧 | نص ⏮ ▶ ⏭ (60×44 / 56) | يمين تدوير ⧉ ⛶ (40dp) */
    private fun mainRow(a: Activity): LinearLayout {
        val u = uiHelper!!
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        lockB = u.fsCircle("🔓") { toggleLock(a) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)).apply { marginEnd = dp(a, 4) }
        }
        left.addView(lockB)
        left.addView(u.fsBtn("🗣 ▾") { dialectMenu(a) }.apply {
            textSize = 12f
            minimumWidth = dp(a, 38)
            setPadding(dp(a, 8), 0, dp(a, 8), 0)
            layoutParams = LinearLayout.LayoutParams(-2, dp(a, 38)).apply { marginStart = dp(a, 3) }
        })
        left.addView(u.fsCircle("🎧") { AudioBridge.showMenu(a) }.apply {
            textSize = 15f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(a, 34), dp(a, 34)).apply { marginStart = dp(a, 3) }
        })
        val mid = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        mid.addView(u.fsCircle("⏮") { jumpSentence(a, -1) }.apply {
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(a, 60), dp(a, 44)).apply { marginEnd = dp(a, 8) }
        })
        playB = u.fsCircle("▶") { togglePlay(a) }.apply {
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(dp(a, 56), dp(a, 56))
        }
        mid.addView(playB)
        mid.addView(u.fsCircle("⏭") { jumpSentence(a, 1) }.apply {
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(a, 60), dp(a, 44)).apply { marginStart = dp(a, 8) }
        })
        val right = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        right.addView(u.fsCircle("🔄") { bridge(a, "rotate") }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)).apply { marginEnd = dp(a, 8) }
        })
        right.addView(u.fsCircle("⧉") { bridge(a, "pip") }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(a, 40), dp(a, 40))
        })
        fitB = u.fsCircle("⛶") { cycleFit(a) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)).apply { marginStart = dp(a, 8) }
        }
        right.addView(fitB)
        row.addView(left)
        row.addView(mid, LinearLayout.LayoutParams(-2, -2))
        row.addView(right)
        return row
    }

    private fun capsuleRow(a: Activity): LinearLayout {
        val u = uiHelper!!
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(u.fsBtn("📄 تصدير") { exportSub(a) })
        volLab = u.text("🔊 100%", 12f, Color.WHITE, false).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(dp(a, 8), 0, dp(a, 4), 0)
            }
        }
        row.addView(volLab)
        volBar = SeekBar(a).apply {
            max = 200
            progress = MtMedia.volumePct()
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    MtMedia.setVolumePct(p)
                    refreshVolLab()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(volBar)
        return row
    }

    private fun refreshVolLab() {
        val p = MtMedia.volumePct()
        val lab = volLab ?: return
        val boost = try {
            MtMedia.boostPct()
        } catch (_: Throwable) {
            0
        }
        lab.text = if (boost > 0) {
            "🔊 " + p + "% +" + String.format("%.1f", boost * 0.15) + " dB"
        } else {
            "🔊 " + p + "%"
        }
    }

    private fun bridge(a: Activity, action: String) {
        try {
            JoyboyPlayerHost.onJoyboyAction(a, action)
        } catch (_: Throwable) {}
    }

    private fun togglePlay(a: Activity) {
        val p = player ?: return
        try {
            if (p.isPlaying) p.pause() else p.play()
        } catch (_: Throwable) {}
        refreshPlay()
    }

    private fun jumpSentence(a: Activity, dir: Int) {
        val p = player ?: return
        val moved = try {
            MtTranslate.jumpSentence(dir)
        } catch (_: Throwable) {
            false
        }
        if (moved) return
        try {
            val cur = p.currentPosition
            val dur = p.duration
            val t = (cur + dir * 10000L).coerceIn(0L, if (dur > 0) dur else cur)
            p.seekTo(t)
            Notice.show(a, if (dir > 0) "⏭ +10 ثواني" else "⏮ −10 ثواني", 1200L)
        } catch (_: Throwable) {}
    }

    private fun cycleFit(a: Activity) {
        val nModes = if (DeviceUi.isTv(a)) 3 else 4
        val cur = try {
            Cfg.int("fit", 0)
        } catch (_: Throwable) {
            0
        }
        val n = (cur + 1) % nModes
        try {
            Cfg.put("fit", n.toString())
        } catch (_: Throwable) {}
        bridge(a, "fit:" + n)
        fitB?.text = "⛶"
        Notice.show(a, "⬛ " + fitName(n), 1400L)
    }

    private fun loopCycle(a: Activity) {
        try {
            MtMedia.loopCycle()
            Notice.show(a, "🔂 " + MtMedia.loopLabel(), 1500L)
        } catch (_: Throwable) {}
    }

    private fun toggleLock(a: Activity) {
        locked = !locked
        bridge(a, "lock")
        lockB?.text = if (locked) "🔒" else "🔓"
        lockCircle?.text = if (locked) "🔒" else "🔓"
        lockOv?.visibility = if (locked) View.VISIBLE else View.GONE
        Notice.show(a, if (locked) "🔒 اتقفل" else "🔓 اتفتح", 1400L)
    }

    private fun toggleAudioOnly(a: Activity) {
        val i = info
        val vid = i?.videoId()
        val isYt = vid != null && vid.length == 11
        if (isYt) {
            audioHandoff(a)
            return
        }
        bridge(a, "audio_only")
        val pill = audModePill
        if (pill != null) {
            val nowAudio = pill.text.toString().contains("الصوت")
            pill.text = if (nowAudio) "🎬 وضع الفيديو" else "🎧 وضع الصوت"
        }
    }

    private fun audioHandoff(a: Activity) {
        val i = info
        val vid = i?.videoId()
        val p = player
        if (vid == null || vid.length != 11 || p == null) {
            bridge(a, "audio_only")
            Notice.show(a, "🎧 وضع الصوت", 1400L)
            return
        }
        try {
            val intent = Intent(a, Class.forName("com.tttt.subtitler.MainActivity"))
            intent.putExtra("ytaudio", vid)
            intent.putExtra("ytaudpos", p.currentPosition / 1000)
            intent.putExtra("ytaudtitle", i?.title() ?: "")
            val au = try {
                i?.audioUrl()
            } catch (_: Throwable) {
                null
            }
            if (!au.isNullOrBlank()) intent.putExtra("ytaudurl", au)
            a.startActivity(intent)
        } catch (t: Throwable) {
            LogStore.err("MtBar:audioHandoff", t)
            bridge(a, "audio_only")
        }
    }

    private fun exportSub(a: Activity) {
        try {
            val ok = MtTranslate.exportSrt()
            if (!ok) {
                Notice.show(a, "📄 مفيش ترجمة تتصدّر لسه", 2200L)
            }
        } catch (t: Throwable) {
            LogStore.err("MtBar:exportSub", t)
            Notice.show(a, "📄 التصدير فشل", 2200L)
        }
    }

    private fun sheetRows(a: Activity, title: String, items: List<Pair<String, () -> Unit>>): android.app.Dialog {
        val u = uiHelper!!
        val views = ArrayList<View>()
        for ((t, f) in items) {
            val b = u.button(t) { f() }
            tvFocus(b)
            views.add(b)
        }
        return u.sheet(a, title, views)
    }

    private fun toolboxMenu(a: Activity) {
        sheetRows(a, "🧰 أدوات", listOf(
            "📄 تصدير الترجمة (.srt)" to { exportSub(a) },
            "😴 مؤقت النوم" to { sleepMenu(a) },
            "⏩ سرعة التشغيل" to { speedPanel(a) },
            "👁 لقطة بصرية (ترجمة الفريم)" to { visualSnap(a) },
            "📸 لقطة فريم (PNG)" to { snapFrame(a) },
            "📋 اللوج" to { showLog(a) },
            "⬇ حمّل الفيديو" to { MtTranslate.downloadCur() },
            "🔊 مسارات الصوت" to { MtTranslate.showAudioTracks() },
            "🎧 مصدر الصوت (تلفزيون / موبايل)" to { AudioBridge.showMenu(a) },
            "📺 ابعت للتلفزيون" to { MtTranslate.sendToTv() },
            "⚙ إعدادات محرك الترجمة" to { MeeTubeLink.openSettings(a, "tr_engine") }
        ))
    }

    private fun textMenu(a: Activity) {
        sheetRows(a, "🔤 النص", listOf(
            "🎨 ستايل السبتايتل" to { MtTranslate.showStyle() },
            "⏩ سرعة التشغيل" to { speedPanel(a) },
            "🔠 خط أكبر" to { bumpSubScale(a, 10) },
            "🔡 خط أصغر" to { bumpSubScale(a, -10) },
            "💬 مزدوج / أحادي" to { MtTranslate.cycleDual() }
        ))
    }

    private fun bumpSubScale(a: Activity, d: Int) {
        try {
            val cur = try {
                Cfg.int("sub_scale", 100)
            } catch (_: Throwable) {
                100
            }
            val n = (cur + d).coerceIn(60, 200)
            Cfg.put("sub_scale", n.toString())
            MtTranslate.restyle()
            Notice.show(a, "🔠 حجم الخط " + n + "%", 1400L)
        } catch (_: Throwable) {}
    }

    private fun speedPanel(a: Activity) {
        val u = uiHelper!!
        val p = player ?: return
        val cur = try {
            p.playbackParameters.speed
        } catch (_: Throwable) {
            1f
        }
        val lab = u.text("⏩ السرعة: " + PlayerLogic.speedLabel(cur), 14f, Color.WHITE, true)
        val bar = SeekBar(a).apply {
            max = 275
            progress = ((cur - 0.25f) * 100f).toInt().coerceIn(0, 275)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8))
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val sp = 0.25f + pr / 100f
                    try {
                        p.setPlaybackSpeed(sp)
                    } catch (_: Throwable) {}
                    lab.text = "⏩ السرعة: " + PlayerLogic.speedLabel(sp)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val chips = FlowRow(a).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        for (sp in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
            chips.addView(u.fsBtn(PlayerLogic.speedLabel(sp)) {
                try {
                    p.setPlaybackSpeed(sp)
                } catch (_: Throwable) {}
                lab.text = "⏩ السرعة: " + PlayerLogic.speedLabel(sp)
                bar.progress = ((sp - 0.25f) * 100f).toInt().coerceIn(0, 275)
            })
        }
        u.sheet(a, "⏩ سرعة التشغيل", listOf(lab, bar, chips))
    }

    private fun sleepMenu(a: Activity) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val cur = try {
            MtMedia.sleepLabel()
        } catch (_: Throwable) {
            ""
        }
        if (cur.isNotBlank()) {
            items.add("⏹ إلغاء المؤقت (الحالي: " + cur + ")" to { MtMedia.cancelSleep() })
        }
        for (m in listOf(5, 10, 15, 30, 45, 60)) {
            items.add("😴 بعد " + m + " دقيقة" to { MtMedia.setSleep(m) })
        }
        items.add("😴 آخر الفيديو" to { MtMedia.setSleep(-1) })
        sheetRows(a, "😴 مؤقت النوم", items)
    }

    private fun dialectMenu(a: Activity) {
        val u = uiHelper!!
        val curD = try {
            Cfg.str("conv_dialect", "فصحى")
        } catch (_: Throwable) {
            "فصحى"
        }
        val lab = u.text("🗣 اللهجة: " + curD, 14f, Color.WHITE, true)
        val chips = FlowRow(a).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        for (d in listOf("فصحى", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني")) {
            chips.addView(u.fsBtn(d) {
                try {
                    Cfg.put("conv_dialect", d)
                } catch (_: Throwable) {}
                try {
                    MtTranslate.rpc("engine.dialect", JSONObject().apply { put("d", d) })
                } catch (_: Throwable) {}
                lab.text = "🗣 اللهجة: " + d
                Notice.show(a, "🗣 اللهجة: " + d, 1400L)
            })
        }
        val styles = listOf("حرفي", "شعبي", "جرئ", "+18")
        val degrees = listOf("خفيفة", "متوسطة", "شديدة")
        val curS = try {
            Cfg.str("tone_style", "شعبي")
        } catch (_: Throwable) {
            "شعبي"
        }
        val curG = try {
            Cfg.str("tone_strength", "متوسطة")
        } catch (_: Throwable) {
            "متوسطة"
        }
        val toneLab = u.text("🎨 الأسلوب: " + curS + " / " + curG, 13f, Color.WHITE, false)
        val styleBar = SeekBar(a).apply {
            max = 3
            progress = styles.indexOf(curS).coerceIn(0, 3)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 4))
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val st = styles[s?.progress ?: 0]
                    try {
                        Cfg.put("tone_style", st)
                    } catch (_: Throwable) {}
                    applyTone(a, st, null)
                    toneLab.text = "🎨 الأسلوب: " + st + " / " + Cfg.str("tone_strength", "متوسطة")
                }
            })
        }
        val degBar = SeekBar(a).apply {
            max = 2
            progress = degrees.indexOf(curG).coerceIn(0, 2)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 8))
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val dg = degrees[s?.progress ?: 1]
                    try {
                        Cfg.put("tone_strength", dg)
                    } catch (_: Throwable) {}
                    applyTone(a, null, dg)
                    toneLab.text = "🎨 الأسلوب: " + Cfg.str("tone_style", "شعبي") + " / " + dg
                }
            })
        }
        u.sheet(a, "🌐 اللغة والأسلوب", listOf(lab, chips, toneLab, styleBar, degBar))
    }

    private fun applyTone(a: Activity, style: String?, strength: String?) {
        try {
            val o = JSONObject()
            if (style != null) o.put("style", style)
            if (strength != null) o.put("strength", strength)
            MtTranslate.rpc("engine.tone", o)
        } catch (_: Throwable) {}
    }

    private fun voiceMenu(a: Activity) {
        val uriStr = info?.localUri()
        val uri = try {
            if (uriStr == null) null else Uri.parse(uriStr)
        } catch (_: Throwable) {
            null
        }
        if (uri == null || (uri.scheme != "file" && uri.scheme != "content")) {
            Notice.show(a, "🎙 فصل الصوت شغّال على الفيديو المحلي بس — نزّل الفيديو الأول", 2600L)
            return
        }
        val dur = try {
            player?.duration ?: 0L
        } catch (_: Throwable) {
            0L
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (VoiceClean.busy) {
            items.add("⏹ وقّف الفصل اللي شغّال" to { VoiceClean.cancel() })
        }
        if (VoiceClean.downloading()) {
            items.add("⏹ وقّف تنزيل الموديل" to { VoiceClean.cancelDownload() })
        }
        if (voicePb != null) {
            items.add("🔊 رجّع الصوت الأصلي" to { stopVoice(a) })
        }
        val ready = try {
            VoiceClean.ready(a, uri, dur)
        } catch (_: Throwable) {
            null
        }
        if (ready != null) {
            items.add("🎙 شغّل صوت الكلام النضيف" to { startVoice(a, ready) })
        } else {
            items.add("🎙 ابدأ فصل صوت الكلام" to { startVoiceJob(a, uri, dur) })
        }
        val curM = try {
            VoiceClean.model()
        } catch (_: Throwable) {
            VoiceClean.models[0]
        }
        items.add("🔄 الموديل: " + curM.label + " (بدّل)" to {
            val next = if (curM === VoiceClean.models[0] && VoiceClean.models.size > 1) VoiceClean.models[1] else VoiceClean.models[0]
            VoiceClean.setModel(next.file)
            Notice.show(a, "🎙 الموديل: " + next.label, 1800L)
        })
        items.add("🗑 امسح ملفات فصل الصوت" to {
            try {
                for (m in VoiceClean.models) {
                    val f = VoiceClean.modelFile(a, m)
                    if (f.isFile) f.delete()
                }
                Notice.show(a, "🗑 اتمسحت ملفات الموديل", 1800L)
            } catch (_: Throwable) {}
        })
        sheetRows(a, "🎙 فصل صوت الكلام", items)
    }

    private fun startVoiceJob(a: Activity, uri: Uri, durMs: Long) {
        val m = try {
            VoiceClean.model()
        } catch (_: Throwable) {
            VoiceClean.models[0]
        }
        val p = player
        if (p == null) return
        if (!VoiceClean.has(a, m)) {
            Notice.show(a, "🎙 بنزّل موديل " + m.label + "…", 2200L)
            VoiceClean.download(a, m, { _, _ -> }, { err ->
                ui.post {
                    if (err != null) {
                        Notice.show(a, "🎙 فشل تنزيل الموديل: " + err, 2600L)
                    } else {
                        startVoiceJob(a, uri, durMs)
                    }
                }
            })
            return
        }
        Notice.show(a, "🎙 بفصل صوت الكلام… (ده بياخد وقت)", 2600L)
        VoiceClean.run(a, uri, durMs, { pct ->
            ui.post { Notice.show(a, "🎙 فصل الصوت… " + pct + "%", 1400L) }
        }, { f, err ->
            ui.post {
                if (f != null) {
                    startVoice(a, f)
                } else {
                    Notice.show(a, "🎙 فشل الفصل: " + (err ?: "؟"), 2600L)
                }
            }
        })
    }

    private fun startVoice(a: Activity, f: java.io.File) {
        val p = player ?: return
        try {
            voicePb?.stop()
        } catch (_: Throwable) {}
        try {
            voicePb = VoiceClean.Playback(a, p, f).also { it.start() }
            Notice.show(a, "🎙 صوت الكلام النضيف شغّال", 2200L)
        } catch (t: Throwable) {
            LogStore.err("MtBar:startVoice", t)
            Notice.show(a, "🎙 فشل تشغيل الصوت النضيف", 2200L)
        }
    }

    private fun stopVoice(a: Activity) {
        try {
            voicePb?.stop()
        } catch (_: Throwable) {}
        voicePb = null
        Notice.show(a, "🔊 رجّعنا الصوت الأصلي", 1800L)
    }

    fun showLog(a: Activity) {
        val u = uiHelper ?: Ui(a, Themes.current())
        val txt = try {
            LogStore.lastLines(LogStore.currentText(), 120)
        } catch (_: Throwable) {
            "مفيش لوج"
        }
        val tv = u.text(txt, 11f, Color.WHITE, false).apply {
            typeface = Typeface.MONOSPACE
        }
        u.sheet(a, "📋 اللوج", listOf(tv))
    }

    fun showChars(a: Activity) {
        val u = uiHelper ?: Ui(a, Themes.current())
        val rows = ArrayList<View>()
        try {
            val o = MtTranslate.rpc("engine.chars", JSONObject())
            val arr = o.optJSONArray("chars")
            if (arr == null || arr.length() == 0) {
                Notice.show(a, "🧑 مفيش شخصيات مكتشفة في الترجمة الحالية", 2200L)
                return
            }
            val list = BrainChars.decode(arr)
            if (list.isEmpty()) {
                Notice.show(a, "🧑 مفيش شخصيات مكتشفة في الترجمة الحالية", 2200L)
                return
            }
            for (c in list) {
                val g = if (c.gender == "female") "👩" else "👨"
                rows.add(u.text(g + " " + c.name + " — " + c.role, 14f, Color.WHITE, false))
            }
        } catch (t: Throwable) {
            LogStore.err("MtBar:showChars", t)
            Notice.show(a, "🧑 تعذّر قراءة الشخصيات", 2200L)
            return
        }
        u.sheet(a, "🧑 الشخصيات", rows)
    }

    private fun batchPanel(a: Activity) {
        val u = uiHelper ?: Ui(a, Themes.current())
        val rows = ArrayList<View>()
        try {
            val o = MtTranslate.rpc("engine.batches", JSONObject())
            val arr = o.optJSONArray("batches")
            if (arr == null || arr.length() == 0) {
                Notice.show(a, "📄 مفيش دفعات ترجمة لسه", 2200L)
                return
            }
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val s = b.optLong("s")
                val e = b.optLong("e")
                val c = b.optInt("c")
                val m = b.optString("m")
                val t = "#" + i + "  " + PlayerLogic.clock(s) + "–" + PlayerLogic.clock(e) + "  ·  " + c + " جملة  ·  " + m
                rows.add(u.button(t) {
                    try {
                        player?.seekTo(s * 1000L)
                    } catch (_: Throwable) {}
                    try {
                        batchDlg?.dismiss()
                    } catch (_: Throwable) {}
                })
            }
        } catch (t: Throwable) {
            LogStore.err("MtBar:batchPanel", t)
            Notice.show(a, "📄 تعذّر قراءة الدفعات", 2200L)
            return
        }
        rows.add(u.button("🔁 ترجم من الأول (كل الدفعات)") {
            try {
                MtTranslate.rpc("engine.retranslate", JSONObject())
            } catch (_: Throwable) {}
            try {
                batchDlg?.dismiss()
            } catch (_: Throwable) {}
        })
        batchDlg = u.sheet(a, "📄 دفعات الترجمة", rows)
    }

    fun toggleSyncStrip(a: Activity) {
        val ov = circlesRoot?.parent as? ViewGroup ?: return
        val existing = syncStrip
        if (existing != null) {
            val vis = existing.visibility != View.VISIBLE
            existing.visibility = if (vis) View.VISIBLE else View.GONE
            if (vis) refreshSyncVal()
            return
        }
        val u = uiHelper ?: Ui(a, Themes.current())
        val col = LinearLayout(a).apply {
            tag = TAG_SYNC
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(a, 58)
            }
        }
        syncValV = u.text("0ms", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }
        val plus = u.fsCircle("＋") { shiftSync(a, 250) }
        val minus = u.fsCircle("－") { shiftSync(a, -250) }
        col.addView(plus)
        col.addView(syncValV)
        col.addView(minus)
        ov.addView(col)
        syncStrip = col
        refreshSyncVal()
    }

    private fun shiftSync(a: Activity, d: Int) {
        try {
            MtTranslate.shiftOffset(d.toLong())
        } catch (_: Throwable) {}
        refreshSyncVal()
    }

    private fun refreshSyncVal() {
        val v = syncValV ?: return
        val ms = try {
            MtTranslate.offsetMs()
        } catch (_: Throwable) {
            0L
        }
        v.text = (if (ms > 0) "+" else "") + ms + "ms"
    }

    private fun visConfNow(): Conf {
        val n = SystemClock.uptimeMillis()
        val c = visConf
        if (c == null || n - visConfAt > 1500L) {
            try {
                visConf = Cfg.snapshot()
                visConfAt = n
            } catch (_: Throwable) {}
        }
        return visConf ?: Cfg.snapshot()
    }

    private fun makeRetriever(a: Activity): android.media.MediaMetadataRetriever? {
        return try {
            val r = android.media.MediaMetadataRetriever()
            val u = info?.localUri() ?: return null
            val uri = Uri.parse(u)
            if (uri.scheme == "content" || uri.scheme == "file") {
                r.setDataSource(a, uri)
            } else {
                r.setDataSource(u, HashMap<String, String>())
            }
            r
        } catch (_: Throwable) {
            null
        }
    }

    private fun findVideoView(root: View): View? {
        if (root is TextureView || root is SurfaceView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val f = findVideoView(root.getChildAt(i))
                if (f != null) return f
            }
        }
        return null
    }

    private fun grabFrame(a: Activity): Bitmap? {
        val vv = try {
            findVideoView(a.window.decorView)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (vv is TextureView) {
            return try {
                vv.bitmap
            } catch (_: Throwable) {
                null
            }
        }
        if (vv is SurfaceView) {
            if (vv.width <= 0 || vv.height <= 0) return null
            if (Looper.myLooper() == Looper.getMainLooper()) return null
            val bmp = Bitmap.createBitmap(vv.width, vv.height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            val okBox = booleanArrayOf(false)
            PixelCopy.request(vv, bmp, { res ->
                okBox[0] = res == PixelCopy.SUCCESS
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
            if (!latch.await(1500L, TimeUnit.MILLISECONDS)) return null
            if (!okBox[0]) return null
            return bmp
        }
        return null
    }

    private fun visualMode(a: Activity): VisualMode {
        val vm = visual
        if (vm != null) return vm
        val nv = VisualMode(
            { visConfNow() },
            { try { (player?.currentPosition ?: 0L) / 1000.0 } catch (_: Throwable) { 0.0 } },
            { makeRetriever(a) },
            { m -> ui.post { try { Notice.show(a, m, 2200L) } catch (_: Throwable) {} } },
            { ui.post { refreshVisOv() } },
            { grabFrame(a) },
            log = { m -> LogStore.add(m) }
        )
        visual = nv
        return nv
    }

    private fun refreshVisOv() {
        val ov = visOv ?: return
        val vm = visual ?: return
        val t = try {
            (player?.currentPosition ?: 0L) / 1000.0
        } catch (_: Throwable) {
            0.0
        }
        try {
            ov.showBoxes(vm.boxesAt(t))
        } catch (_: Throwable) {}
    }

    fun visualSnap(a: Activity) {
        val p = player ?: return
        if (act !== a) return
        if (visBusy) {
            Notice.show(a, "👁 لسه بترجم اللقطة اللي فاتت…", 2300L)
            return
        }
        visBusy = true
        val wasPlaying = try {
            p.isPlaying
        } catch (_: Throwable) {
            false
        }
        try {
            p.pause()
        } catch (_: Throwable) {}
        try {
            p.seekTo((p.currentPosition - 1500L).coerceAtLeast(0L))
        } catch (_: Throwable) {}
        val tok = ++visTok
        ui.postDelayed({
            if (!visBusy || visTok != tok) return@postDelayed
            val t0 = try {
                p.currentPosition / 1000.0
            } catch (_: Throwable) {
                0.0
            }
            val bmp = grabFrame(a)
            val done: () -> Unit = {
                ui.post {
                    visBusy = false
                    if (wasPlaying && act === a) {
                        try {
                            p.play()
                        } catch (_: Throwable) {}
                    }
                }
            }
            if (bmp != null) {
                try {
                    visualMode(a).snap(bmp, { t0 }, done)
                } catch (t: Throwable) {
                    LogStore.err("MtBar:visSnap", t)
                    done()
                }
            } else {
                done()
                Notice.show(a, "👁 مفيش فريم جاهز لسه", 2200L)
            }
        }, 650L)
        ui.postDelayed({
            if (visBusy && visTok == tok) visBusy = false
        }, 60000L)
    }

    private fun snapFrame(a: Activity) {
        val bmp = grabFrame(a)
        if (bmp == null) {
            Notice.show(a, "📸 مفيش فريم جاهز لسه", 2200L)
            return
        }
        try {
            val name = "Joyboy_" + System.currentTimeMillis() + ".png"
            val rel = "Pictures/Joyboy"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val vals = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                }
                val cr = a.contentResolver
                val uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)
                if (uri == null) {
                    Notice.show(a, "📸 فشل حفظ اللقطة", 2200L)
                    return
                }
                cr.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                a.startActivity(Intent.createChooser(share, "📸 شارك اللقطة"))
            } else {
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Joyboy")
                dir.mkdirs()
                val f = java.io.File(dir, name)
                java.io.FileOutputStream(f).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                a.startActivity(Intent.createChooser(share, "📸 شارك اللقطة"))
            }
            Notice.show(a, "📸 اتحفظت اللقطة", 2200L)
        } catch (t: Throwable) {
            LogStore.err("MtBar:snapFrame", t)
            Notice.show(a, "📸 فشل حفظ اللقطة", 2200L)
        }
    }

    fun showTvMenu(a: Activity) {
        val dlg = tvMenuDlg
        if (dlg != null && dlg.isShowing) {
            closeTopLayer(a)
            return
        }
        tvMenuDlg = sheetRows(a, "☰ قائمة المشغّل", listOf(
            "▶ / ▮▮ تشغيل / إيقاف" to { togglePlay(a) },
            "⏮ جملة سابقة" to { jumpSentence(a, -1) },
            "⏭ جملة تالية" to { jumpSentence(a, 1) },
            "🌐 ترجم" to { MtTranslate.translateMenu() },
            "📝 بحث في الجمل" to { MtTranslate.searchSentences() },
            "🎨 ستايل السبتايتل" to { MtTranslate.showStyle() },
            "😴 مؤقت النوم" to { sleepMenu(a) },
            "⛶ ملاءمة الفيديو" to { cycleFit(a) },
            "⧉ صورة داخل صورة" to { bridge(a, "pip") },
            "🔓 قفل / فتح" to { toggleLock(a) },
            "✕ إغلاق القائمة" to { closeTopLayer(a) }
        ))
    }

    fun closeTopLayer(a: Activity): Boolean {
        val tv = tvMenuDlg
        if (tv != null && tv.isShowing) {
            try {
                tv.dismiss()
            } catch (_: Throwable) {}
            tvMenuDlg = null
            return true
        }
        val ss = syncStrip
        if (ss != null && ss.visibility == View.VISIBLE) {
            ss.visibility = View.GONE
            return true
        }
        val bd = batchDlg
        if (bd != null && bd.isShowing) {
            try {
                bd.dismiss()
            } catch (_: Throwable) {}
            batchDlg = null
            return true
        }
        return false
    }
}
