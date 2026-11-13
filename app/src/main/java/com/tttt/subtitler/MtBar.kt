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
    private const val TAG_CAP = "mtbar_cap"
    private const val TAG_MODE = "mtbar_mode"
    private const val BLUE_ON = 0xFF1F5FBF.toInt()
    private const val DARK_OFF = 0xE00F0F12.toInt()
    private const val TAG_LOCKOV = "mtbar_lockov"
    private const val REST_A = 0.25f
    private const val BAR_BOTTOM_DP = 6   // (v954) كان 84 — الشريط (ترجمة … تصدير) كان مرفوع لنص الشاشة؛ دلوقتي لازق تحت

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
    private var capRoot: ViewGroup? = null
    private var modePill: TextView? = null         // (v949) «أونلاين · مصري» جنب كبسولة العنوان
    private var noticeB: TextView? = null          // (v949) ▲ الإشعارات الحمرا (زي noticeBtn في القديم)
    private var titleBaseR = -1                    // (v949) هامش العنوان الأصلي من جهة الكبسولات
    private var lastTopSig = ""
    private var capPct: TextView? = null
    private var capNet: TextView? = null
    private var capDot: View? = null
    private var capLast = ""
    private var loopBtn: TextView? = null
    private var exitDlg: android.app.Dialog? = null
    private var askBtn: TextView? = null
    private var slotV: View? = null
    private var timeBar: View? = null
    private var scrubL: androidx.media3.ui.TimeBar.OnScrubListener? = null   // (v956) فيلمستريب الميديا/المتصفح
    private var frames: MtFrames? = null
    private var bottomRowV: View? = null          // (v947) صف الوقت بتاع MeeTube — بيمشي مع شريط التقدم
    private val nativeInv = ArrayList<View>()      // (v970) أزرار أصلية بتتخبّى INVISIBLE (مقاسها محفوظ)
    private val nativeDup = ArrayList<View>()      // (v947) أزرار MeeTube الأصلية المكرّرة (⏮ ⏯ ⏭ وسط الشاشة + ⛶) — بتتخبّى طول ما شريط Joyboy موجود
    private var seekHook: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var dualBtn: TextView? = null
    private var pauseB: TextView? = null
    private var stopB: TextView? = null
    private var playB: TextView? = null
    private var fitB: TextView? = null
    private var fullB: TextView? = null            // (v960) ⛶ دخول/خروج الشاشة الكاملة — جنب زرار التمديد
    private var qualB: TextView? = null            // (v960) زرار الجودة (رقم الجودة إجباري) جنب 🗣▾ — لاند سكيب
    private var qualLast = ""
    private var durCapWired = false
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
                refreshBadge()
                refreshModes()
                refreshNotice()
                refreshQuality()   // (v960)
                refreshTrMap()   // (v956)
                tickCount++
                if (tickCount % 8 == 0) refreshBatchWarn()
            } catch (_: Throwable) {}
            ui.postDelayed(this, 700L)
        }
    }

    private var titleForcedLtr = false   // (v960) اتجاه العنوان اتفرض LTR في اللاند سكيب (اسم الفيديو أقصى اليسار)

    private fun dp(a: Activity, v: Int): Int {
        return (v * a.resources.displayMetrics.density).toInt()
    }

    // (v961) ٣ وضعيات بس: Original (الافتراضي، من غير قص) · Fit (على عرض الشاشة) · Fill (ملء الشاشة كلها). القيم المخزّنة هي أكواد fit: القديمة (0 / 3 / 1)
    private val fitCodes = intArrayOf(0, 3, 1)

    private fun fitName(i: Int): String {
        return when (i) {
            3 -> "Fit"
            1, 2 -> "Fill"
            else -> "Original"
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
        removeByTag(bar, TAG_CAP)
        removeByTag(bar, TAG_MODE)
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
        // (v946) شريط التقدم بين شريحة الترجمة والصف الرئيسي زي القديم: مكان فاضي (slot) وشريط MeeTube بيتحط عليه
        val slot = View(activity).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 34)) }   // (v949) 34dp: الوقت والشريط والمدة على نفس السطر زي القديم (timeRow 30dp)
        root.addView(slot)
        slotV = slot
        val mainV = mainRow(activity)
        root.addView(mainV)
        mtStyle(activity, mainV)   // (v954) نفس ستايل سماعة/إعدادات MeeTube
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            val capV = capsuleRow(activity)
            root.addView(capV)
            mtStyle(activity, capV)
        }
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = dp(activity, BAR_BOTTOM_DP)
        }
        bar.addView(root, lp)
        barRoot = root
        hookSeekBar(activity, root)
        val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (!portrait) {   // (v968) أي دخول فل سكرين (ملف/متصفح/يوتيوب) يبدأ Fill = الفيديو متمدّد على قد الشاشة بالظبط؛ بعدها زرار ⤢ يغيّر
            try { Cfg.put("fit", "1") } catch (_: Throwable) {}
            bridge(activity, "fit:1")
        }
        val tools = toolbar(activity)
        mtStyle(activity, tools)
        val tlp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(activity, if (portrait) 72 else 58)
            leftMargin = dp(activity, 14)
            rightMargin = dp(activity, 14)
        }
        // (v949) كبسولة العدّاد فوق اليمين زي القديم (اتحطّت مكان الشمال) + «أونلاين · مصري» جنب العنوان؛ المكان بيتظبط في placeTop() حسب أماكن أزرار MeeTube
        val cap = badgeCapsule(activity)
        val clp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = dp(activity, 12)
            rightMargin = dp(activity, 100)
        }
        tlp.leftMargin = dp(activity, 14)
        tlp.gravity = Gravity.TOP or Gravity.RIGHT
        bar.addView(cap, clp)
        capRoot = cap
        val mode = TextView(activity).apply {
            tag = TAG_MODE
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setSingleLine()
            gravity = Gravity.CENTER
            setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6))
            background = uiHelper!!.box(0xE0141418.toInt(), 0x1FFFFFFF, 12)
            text = modeLabel()
        }
        bar.addView(mode, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = dp(activity, 12)
            rightMargin = dp(activity, 230)
        })
        modePill = mode
        lastTopSig = ""
        bar.addView(tools, tlp)
        toolsRoot = tools
        refreshBadge()
        refreshModes()
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
        // (v949) أزرار العمود الجانبي اتشالت لأنها مكرّرة: 🔓 (موجود في الصف الرئيسي) · 👁 و📺 (في قايمة 🧰) · 🎧 وضع الصوت (زرار سماعة MeeTube الأصلي فوق الشمال بيعمل نفس الشغلة). فاضل ⚠ بس.
        audModePill = null
        batchWarn = u.fsCircle("⚠") { batchPanel(activity) }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)).apply {
                setMargins(0, dp(activity, 6), 0, 0)
            }
        }
        col.addView(batchWarn)
        lockCircle = null
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
        dismissPopup()
        try { modePill?.let { (it.parent as? ViewGroup)?.removeView(it) } } catch (_: Throwable) {}
        modePill = null
        noticeB = null
        titleBaseR = -1
        try {
            if (titleForcedLtr) {
                val tid = activity.resources.getIdentifier("mobile_player_title", "id", activity.packageName)
                if (tid != 0) activity.findViewById<View>(tid)?.layoutDirection = View.LAYOUT_DIRECTION_INHERIT
            }
        } catch (_: Throwable) {}
        titleForcedLtr = false
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
        try {
            capRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Throwable) {}
        try {
            val l = seekHook
            val r = barRoot
            if (l != null && r != null && r.viewTreeObserver.isAlive) r.viewTreeObserver.removeOnPreDrawListener(l)
        } catch (_: Throwable) {}
        dismissPopup()
        try { timeBar?.translationY = 0f } catch (_: Throwable) {}
        try { scrubL?.let { (timeBar as? com.newtube.mobile.ui.playback.PlayerTimeBar)?.removeListener(it) } } catch (_: Throwable) {}
        try { (timeBar as? com.newtube.mobile.ui.playback.PlayerTimeBar)?.setTranslation(0.0, null, null, null, null) } catch (_: Throwable) {}
        scrubL = null
        frames?.end(); frames = null
        try { bottomRowV?.translationY = 0f } catch (_: Throwable) {}
        nativeDup.clear(); nativeInv.clear(); bottomRowV = null
        try { exitDlg?.dismiss() } catch (_: Throwable) {}
        seekHook = null
        timeBar = null
        slotV = null
        askBtn = null
        exitDlg = null
        capRoot = null
        capPct = null
        capNet = null
        capDot = null
        capLast = ""
        loopBtn = null
        dualBtn = null
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
        fullB = null
        qualB = null
        qualLast = ""
        durCapWired = false
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

    private fun askOn(): Boolean = try { Cfg.bool("ask_bg_exit", true) } catch (_: Throwable) { true }

    fun exitAskShowing(): Boolean = exitDlg?.isShowing == true

    /** (v946) «🌙 الترجمة لسه ما خلصتش» — أكمّل في الخلفية / قفّل خالص / رجوع للفيديو */
    fun exitAsk(a: Activity, onBg: () -> Unit, onClose: () -> Unit) {
        val u = uiHelper ?: Ui(a, Themes.current()).also { uiHelper = it }
        if (exitAskShowing()) return
        var dlg: android.app.Dialog? = null
        val views = ArrayList<View>()
        views.add(u.text("أكمّل الترجمة في الخلفية؟ (هيستهلك نت)\nلو اخترت «لا» الترجمة بتتقفل خالص ومفيش نت بيتصرف.", 12f, Color.WHITE, false).apply {
            setPadding(dp(a, 12), dp(a, 6), dp(a, 12), dp(a, 6))
        })
        views.add(u.button("🌙 أيوه، كمّل في الخلفية") { dlg?.dismiss(); onBg() })
        views.add(u.button("⛔ لا، قفّل خالص") { dlg?.dismiss(); onClose() })
        views.add(u.button("↩ رجوع للفيديو") { dlg?.dismiss() })
        dlg = u.sheet(a, "🌙 الترجمة لسه ما خلصتش", views)
        exitDlg = dlg
    }

    /** (v946) شريط تقدّم MeeTube (PlayerTimeBar) برّه طبقة الأزرار؛ بنلاقيه بالاسم ونرفعه بـtranslationY لحد مكان الـslot (مفيش تغيير في هامشه ولا في لمسه) */
    private fun hookSeekBar(a: Activity, root: ViewGroup) {
        try {
            val id = a.resources.getIdentifier("mobile_player_time_bar", "id", a.packageName)
            timeBar = if (id != 0) a.findViewById(id) else null
        } catch (_: Throwable) { timeBar = null }
        hookFilmstrip(a)
        // (v947) أزرار MeeTube الأصلية اللي Joyboy عنده بدايلها في الصف الرئيسي: ⏮ ⏯ ⏭ (النص) + ⛶ + سهم الأكشنز — بتتخبّى (كانت بتظهر مرتين)
        nativeDup.clear()
        for (n in arrayOf("mobile_player_transport", "mobile_player_fullscreen", "mobile_player_actions_toggle", "mobile_player_cast", "mobile_watch_fullscreen_fab")) {   // (v960) + FAB الشاشة الكاملة اللي كان في آخر صفحة اليوتيوب (بورتريه): الزرار بقى على الشريط جنب التمديد
   // (v954) + قفل MeeTube الكبير (مكرّر مع 🔓 اللي في الصف الرئيسي) + الكاست   // (v949) + زرار CC بتاع MeeTube (Joyboy عنده CC في صف الدواير بيعمل نفس الشغلة)
            try {
                val i = a.resources.getIdentifier(n, "id", a.packageName)
                if (i != 0) a.findViewById<View>(i)?.let { nativeDup.add(it) }
            } catch (_: Throwable) {}
        }
        nativeInv.clear()
        try {   // (v970) زرار القفل الأصلي العايم: مخفي (INVISIBLE عشان مقاسه يفضل لـ MeeTube) لأن القفل بقى في الصف الرئيسي
            val i = a.resources.getIdentifier("mobile_player_lock", "id", a.packageName)
            if (i != 0) a.findViewById<View>(i)?.let { nativeInv.add(it) }
        } catch (_: Throwable) {}
        try {
            val i = a.resources.getIdentifier("mobile_player_bottom_row", "id", a.packageName)
            bottomRowV = if (i != 0) a.findViewById(i) else null
        } catch (_: Throwable) { bottomRowV = null }
        val l = android.view.ViewTreeObserver.OnPreDrawListener { hideNativeDup(); alignSeek(); placeTop(); true }
        seekHook = l
        try { root.viewTreeObserver.addOnPreDrawListener(l) } catch (_: Throwable) {}
    }

    /**
     * (v956) الفيلمستريب (الصور المصغرة وقت سحب الشريط) كان بيشتغل ليوتيوب بس (storyboard). للميديا والمتصفح مفيش storyboard،
     * فبنوصّل مصدر إطارات بيقطّع من الملف/الرابط نفسه (MtFrames). ودوسة على الشريط بتنقل مباشرة (PlayerTimeBar.setTapToSeek).
     */
    private fun hookFilmstrip(a: Activity) {
        try {
            val tb = timeBar as? com.newtube.mobile.ui.playback.PlayerTimeBar ?: return
            scrubL?.let { try { tb.removeListener(it) } catch (_: Throwable) {} }
            scrubL = null
            frames?.end(); frames = null
            tb.setTapToSeek(true)
            val fid = a.resources.getIdentifier("mobile_player_seek_filmstrip", "id", a.packageName)
            val fs = if (fid != 0) a.findViewById<com.newtube.mobile.ui.playback.SeekFilmstrip>(fid) else null
            val s = MtTranslate.srcOrNull()
            if (fs == null || !MtFrames.usable(s)) { fs?.setFrameSource(null); return }
            val fr = MtFrames(a, fs, s!!, MtTranslate.srcHeaders()) { player?.duration?.takeIf { it > 0 } ?: 0L }
            frames = fr
            fs.setFrameSource(fr)
            val l = object : androidx.media3.ui.TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) { fr.begin(position) }
                override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) { fr.center(position) }
                override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) { fr.end() }
            }
            scrubL = l
            tb.addListener(l)
        } catch (t: Throwable) { LogStore.err("MtBar:filmstrip", t) }
    }

    /** (v956) نقط/مجالات الأجزاء المترجمة على شريط MeeTube (أخضر = مترجم · كهرماني = هيتغيّر · أزرق = بيتنقّح) */
    private fun refreshTrMap() {
        val tb = timeBar as? com.newtube.mobile.ui.playback.PlayerTimeBar ?: return
        val sg = MtTranslate.trSegs()
        if (sg == null) { tb.setTranslation(0.0, null, null, null, null); return }
        val dur = MtTranslate.trDurSec().takeIf { it > 0 } ?: ((player?.duration ?: 0L) / 1000.0)
        tb.setTranslation(dur, sg[0].map { it }, sg[1].map { it }, sg[2].map { it }, null)
    }

    /** (v947) اخفي الأزرار الأصلية المكرّرة (GONE عشان ما تاخدش لمس ولا مساحة)؛ بتتنده كل فريم بس بتغيّر لو MeeTube رجّعها */
    private fun hideNativeDup() {
        for (v in nativeDup) { if (v.visibility != View.GONE) v.visibility = View.GONE }
        for (v in nativeInv) { if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE }
    }

    /** (v949) الوقت الحالي في طرف والمدة في الطرف التاني، على نفس سطر شريط التقدم (زي timeRow في القديم) */
    private fun alignSeek() {
        val tb = timeBar ?: return
        val slot = slotV ?: return
        val root = barRoot ?: return
        val a = act ?: return
        try {
            if (!root.isShown || slot.height <= 0 || tb.height <= 0) {
                if (tb.translationY != 0f) tb.translationY = 0f
                bottomRowV?.let { if (it.translationY != 0f) it.translationY = 0f }
                return
            }
            val posV = a.findViewById<TextView>(a.resources.getIdentifier("mobile_player_position", "id", a.packageName))
            val durV = a.findViewById<TextView>(a.resources.getIdentifier("mobile_player_duration", "id", a.packageName))
            val timeV = a.findViewById<View>(a.resources.getIdentifier("mobile_player_time", "id", a.packageName))
            val chapV = a.findViewById<View>(a.resources.getIdentifier("mobile_player_chapter", "id", a.packageName))
            val row = bottomRowV as? LinearLayout
            // المدة تطلع من كبسولة الوقت لآخر الصف (الطرف التاني) ويتشال منها « / »
            if (row != null && posV != null && durV != null && durV.parent !== row) {
                (durV.parent as? ViewGroup)?.removeView(durV)
                row.addView(durV, row.childCount, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(a, 8) })
            }
            if (durV != null) {
                val t = durV.text?.toString() ?: ""
                val fixed = t.trimStart(' ', '/')
                if (fixed != t) durV.text = fixed
                // (v960) كبسولة توقيت النهاية: زجاج زي كبسولة الوقت الحالي بالظبط (نفس المصدر ونفس الحواشي) + دوسة عليها بتبدّل المتبقّي/المدة
                if (!durCapWired) {
                    durCapWired = true
                    try {
                        val sid = a.resources.getIdentifier("mobile_player_glass", "id", a.packageName)
                        val src = if (sid != 0 && com.newtube.mobile.ui.common.Glass.isPlayerFrost(a)) a.findViewById<View>(sid) as? com.newtube.mobile.ui.common.GlassSource else null
                        durV.setPadding(dp(a, 9), dp(a, 4), dp(a, 9), dp(a, 4))
                        com.newtube.mobile.ui.common.GlassDrawable.apply(durV, src, 0)
                        durV.setOnClickListener { timeV?.performClick() }
                    } catch (e: Throwable) { LogStore.err("MtBar:durCap", e) }
                }
            }
            // الصف كله على سطر الشريط (مركز الشريط = مركز الـslot)
            val s = IntArray(2)
            val t = IntArray(2)
            slot.getLocationInWindow(s)
            tb.getLocationInWindow(t)
            val want = s[1] + slot.height / 2f
            val d = want - (t[1] + tb.height / 2f)
            if (Math.abs(d) >= 1f) {
                tb.translationY = tb.translationY + d
                bottomRowV?.let { it.translationY = it.translationY + d }
            }
            // الشريط بين الوقت والمدة: هامش يمين/شمال بعرض النصين
            val lp = tb.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && posV != null && durV != null && timeV != null) {
                val l = (timeV.width + dp(a, 10)).coerceAtLeast(0)
                val r = (durV.width + dp(a, 18)).coerceAtLeast(0)
                if (Math.abs(lp.leftMargin - l) > 2 || Math.abs(lp.rightMargin - r) > 2) {
                    lp.leftMargin = l
                    lp.rightMargin = r
                    tb.layoutParams = lp
                }
            }
            // شريحة الفصل (لو فيه فصول) فوق الشريط مش عليه
            chapV?.let { if (it.translationY != -dp(a, 30).toFloat()) it.translationY = -dp(a, 30).toFloat() }
        } catch (_: Throwable) {}
    }

    private fun modeLabel(): String {
        val m = try { MtTranslate.curEngineMode() } catch (_: Throwable) { "gemini" }
        val mn = when (m) {
            "whisper" -> "أوفلاين"
            "google" -> "جوجل"
            "transcribe" -> "تفريغ"
            else -> "أونلاين"
        }
        val d = try { Cfg.str("conv_dialect", "").ifBlank { "فصحى" } } catch (_: Throwable) { "فصحى" }
        return mn + " · " + d
    }

    /** (v954) شريط فوق: يمين ← [كبسولة العدّاد] [أونلاين · لهجة] [اسم الفيديو] ← شمال [⚙ 🎧 ⌄]. السماعة والسهم بقوا جوه صف الإعدادات (شمال)، فاليمين بقى فيه الكبسولة بس، والعنوان بيضيّق هامشه من الجهتين حسب العرض الفعلي */
    private fun placeTop() {
        val a = act ?: return
        val cap = capRoot ?: return
        val mode = modePill
        try {
            val land = a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val capW = cap.width
            val modeW = mode?.width ?: 0
            val gap = dp(a, 8)
            val oid = a.resources.getIdentifier("mobile_player_options", "id", a.packageName)
            val opt = if (oid != 0) a.findViewById<View>(oid) else null
            val optW = if (opt != null && opt.visibility == View.VISIBLE) opt.width else 0
            // (v960) لاند سكيب: صف الأزرار العلوي (🌐 ترجم · ⌄ · 🎧 · كاست · CC · ⚙) ينتقل جنب كبسولة اللوج/الجمل (يمين)،
            // وبعده «أونلاين · لهجة»، واسم الفيديو أعلى أقصى اليسار. الرأسي زي ما هو (الأزرار في الطرف التاني).
            val olp = opt?.layoutParams as? FrameLayout.LayoutParams
            if (opt != null && olp != null) {
                val wantG = if (land) (Gravity.TOP or Gravity.RIGHT) else (Gravity.TOP or Gravity.END)
                val wantR = if (land) gap + capW + gap else 0
                if (olp.gravity != wantG || olp.rightMargin != wantR) {
                    olp.gravity = wantG
                    olp.rightMargin = wantR
                    opt.layoutParams = olp
                }
            }
            // (v963) لاند سكيب: «أونلاين · لهجة» تحت اسم الفيديو (مش جنبه) — نفس الحافة اليسار، تحت سطر النص مباشرة
            val tid0 = a.resources.getIdentifier("mobile_player_title", "id", a.packageName)
            val title0 = if (tid0 != 0) a.findViewById<View>(tid0) else null
            val tloc = IntArray(2)
            val ploc = IntArray(2)
            var capTop = 0
            var capLeft = 0
            if (land && title0 != null && mode != null && title0.height > 0) {
                try {
                    title0.getLocationInWindow(tloc)
                    (mode.parent as? View)?.getLocationInWindow(ploc)
                    val lh = (title0 as? TextView)?.lineHeight ?: dp(a, 18)
                    capTop = tloc[1] - ploc[1] + (title0.height + lh) / 2 + dp(a, 2)
                    capLeft = tloc[0] - ploc[0] + title0.paddingLeft
                } catch (_: Throwable) {}
            }
            val sig = capW.toString() + "|" + modeW + "|" + optW + "|" + land + "|" + capTop + "|" + capLeft
            if (sig != lastTopSig) {
                lastTopSig = sig
                (cap.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.rightMargin = gap
                    cap.layoutParams = it
                }
                if (mode != null) {
                    (mode.layoutParams as? FrameLayout.LayoutParams)?.let {
                        if (land && capTop > 0) {
                            it.gravity = Gravity.TOP or Gravity.LEFT
                            it.leftMargin = capLeft
                            it.topMargin = capTop
                            it.rightMargin = 0
                        } else {
                            it.gravity = Gravity.TOP or Gravity.RIGHT
                            it.leftMargin = 0
                            it.topMargin = dp(a, 12)
                            it.rightMargin = if (land) gap + capW + gap + optW + gap else gap + capW + dp(a, 6)
                        }
                        mode.layoutParams = it
                    }
                }
            }
            val tid = a.resources.getIdentifier("mobile_player_title", "id", a.packageName)
            val title = if (tid != 0) a.findViewById<View>(tid) else null
            val tlp = title?.layoutParams as? ViewGroup.MarginLayoutParams
            if (!land && title != null && titleForcedLtr) { titleForcedLtr = false; title.layoutDirection = View.LAYOUT_DIRECTION_INHERIT }   // (v960) رجوع اتجاه العنوان لوضعه الطبيعي برّه اللاند سكيب
            // العنوان في صف الأزرار العلوي بس (في الفل-سكرين الرأسي العنوان تحت الصف فمش بيتلمس)
            if (title != null && tlp != null && capW > 0 && tlp.topMargin <= dp(a, 30)) {
                if (land) {
                    // (v960) اسم الفيديو أعلى أقصى يسار الشاشة (يسار فيزيائي): اتجاه LTR + gravity يسار، وعرضه ينتهي قبل «أونلاين · لهجة»
                    if (title.layoutDirection != View.LAYOUT_DIRECTION_LTR || !titleForcedLtr) { titleForcedLtr = true; title.layoutDirection = View.LAYOUT_DIRECTION_LTR }
                    (title as? TextView)?.let {
                        val g = Gravity.CENTER_VERTICAL or Gravity.LEFT
                        if (it.gravity != g) it.gravity = g
                    }
                    val wantLeft = dp(a, 12)
                    val wantRight = gap + capW + gap + optW + gap + dp(a, 10)   // (v963) «أونلاين · لهجة» بقت تحت الاسم، مش جنبه
                    if (tlp.marginStart != wantLeft || tlp.marginEnd != wantRight) {
                        tlp.marginStart = wantLeft
                        tlp.marginEnd = wantRight
                        title.layoutParams = tlp
                    }
                } else {
                    val rtl = title.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val wantRight = gap + capW + modeW + dp(a, 14)   // اليمين: الكبسولة (+ «أونلاين · لهجة») وبعدها العنوان
                    val wantLeft = optW + dp(a, 8)                   // الشمال: صف ⚙ 🎧 ⌄ (+ 🌐 ترجم)
                    val wantStart = if (rtl) wantRight else wantLeft
                    val wantEnd = if (rtl) wantLeft else wantRight
                    if (tlp.marginStart != wantStart || tlp.marginEnd != wantEnd) {
                        tlp.marginStart = wantStart
                        tlp.marginEnd = wantEnd
                        title.layoutParams = tlp
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /** (v949) ▲ الإشعارات: أحمر شفاف، بيظهر دايمًا (خافت لو مفيش) ودوسة = قايمة من الأحدث للأقدم (زي noticeBtn) */
    private fun refreshNotice() {
        val b = noticeB ?: return
        val n = try { NoticeLog.list().size } catch (_: Throwable) { 0 }
        b.alpha = if (n == 0) 0.35f else 0.7f
        val t = if (n == 0) "▲" else "▲ " + n
        if (b.text.toString() != t) b.text = t
    }

    private fun noticePopup(a: Activity, anchor: View) {
        val u = uiHelper ?: return
        val items = try { NoticeLog.list() } catch (_: Throwable) { emptyList<NoticeLog.Item>() }
        val views = ArrayList<View>()
        for (it in items) {
            views.add(u.text(it.msg, 11.5f, 0xFFF1F3F4.toInt()).apply { setPadding(dp(a, 10), 0, dp(a, 10), dp(a, 6)) })
        }
        if (items.isEmpty()) views.add(u.text("مفيش إشعارات", 11.5f, 0xFFC9CED6.toInt()).apply { setPadding(dp(a, 10), 0, dp(a, 10), dp(a, 6)) })
        popupViews(a, anchor, true, views, 220)
    }

    private fun ovalBg(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(1, 0x1FFFFFFF)
        }
    }

    /** (v954) حالة التفعيل بحلقة زرقا فوق نفس خلفية الزجاج (مش خلفية سودا/زرقا بتغيّر الستايل) */
    private fun ring(v: View, on: Boolean) {
        try {
            v.foreground = if (on) android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke(2, 0xFF4C8DFF.toInt())
            } else null
        } catch (_: Throwable) {}
    }

    /** (v954) نفس ستايل أزرار MeeTube (سماعة/إعدادات): GlassDrawable.applyButton بنفس مصدر الزجاج والإعداد (شفاف/مثلّج/مينيمال) */
    private fun mtStyle(a: Activity, root: View?) {
        if (root == null) return
        try {
            val sid = a.resources.getIdentifier("mobile_player_glass", "id", a.packageName)
            val src = if (sid != 0 && com.newtube.mobile.ui.common.Glass.isPlayerFrost(a)) a.findViewById<View>(sid) as? com.newtube.mobile.ui.common.GlassSource else null
            styleTree(root, src)
        } catch (t: Throwable) {
            LogStore.err("MtBar:mtStyle", t)
        }
    }

    private fun styleTree(v: View, src: com.newtube.mobile.ui.common.GlassSource?) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) styleTree(v.getChildAt(i), src)
            return
        }
        if (v !is TextView || v === noticeB || v === trChip || v.tag == TAG_CAP) return
        if (v.background == null) return
        try { com.newtube.mobile.ui.common.GlassDrawable.applyButton(v, src, 0, true) } catch (_: Throwable) {}
    }

    /** (v945) 🔂 و💬 أزرق لما يكونوا شغّالين، غامق لما يكونوا مقفولين — زي القديم */
    private fun refreshModes() {
        try {
            val lo = MtMedia.loopMode() != 0
            loopBtn?.let { ring(it, lo) }
            val du = Cfg.int("sub_dual", 0) != 0
            dualBtn?.let { ring(it, du) }
        } catch (_: Throwable) {}
    }

    /** (v945) كبسولة العدّاد: نقطة الحالة + «🔊 N% · N جملة ▾» + سطر النت (🌐 · 🔗) */
    private fun badgeCapsule(a: Activity): ViewGroup {
        val u = uiHelper!!
        val dot = View(a).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFF3B30.toInt())
            }
        }
        val pct = TextView(a).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setSingleLine()
            text = "🔊 0% · 0 جملة ▾"
        }
        val net = TextView(a).apply {
            textSize = 9f
            setTextColor(0xB3FFFFFF.toInt())
            includeFontPadding = false
            setSingleLine()
        }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pct, LinearLayout.LayoutParams(-2, -2))
            addView(net, LinearLayout.LayoutParams(-2, -2))
        }
        capPct = pct
        capNet = net
        capDot = dot
        capLast = ""
        return LinearLayout(a).apply {
            tag = TAG_CAP
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(a, 8), dp(a, 4), dp(a, 8), dp(a, 4))
            background = u.box(0xE0141418.toInt(), 0x1FFFFFFF, 12)
            addView(col, LinearLayout.LayoutParams(-2, -2))
            addView(dot, LinearLayout.LayoutParams(dp(a, 6), dp(a, 6)).apply { marginStart = dp(a, 5) })
            setOnClickListener { showLog(a) }
        }
    }

    private fun refreshBadge() {
        val pv = capPct ?: return
        val nv = capNet ?: return
        val dot = capDot
        val p = player ?: return
        val st = try { MtTranslate.coverStats() } catch (_: Throwable) { null }
        val dur = try { p.duration / 1000.0 } catch (_: Throwable) { 0.0 }
        val pc = if (st != null && dur > 0) PlayerLogic.percent(st.second, dur) else 0
        val n = st?.first ?: 0
        var line1 = "🔊 " + pc + "% · " + n + " جملة ▾"
        try {
            val v = visual
            if (v != null && v.running) line1 += "  👁 " + v.count
        } catch (_: Throwable) {}
        val line2 = try { NetUse.badgeLine() } catch (_: Throwable) { "" }
        val key = line1 + "|" + line2
        if (key != capLast) {
            capLast = key
            pv.text = line1
            nv.text = line2
        }
        val live = try { MtTranslate.isTranslating() && !MtTranslate.isPaused() } catch (_: Throwable) { false }
        (dot?.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (live) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
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
        fun circ(t: String, ts: Float, f: (View) -> Unit): TextView {
            val self = arrayOfNulls<TextView>(1)   // (v953) fsCircle بياخد () -> Unit، فبنمسك الـView يدويًا
            val c = u.fsCircle(t) { self[0]?.let { f(it) } }
            self[0] = c
            return c.apply {
                textSize = ts
                layoutParams = lpC()
            }
        }
        fun chev(t: String, f: (View) -> Unit): TextView {
            return u.fsBtn(t) { v -> f(v) }.apply {
                textSize = 12f
                minimumWidth = dp(a, 38)
                setPadding(dp(a, 8), 0, dp(a, 8), 0)
                layoutParams = LinearLayout.LayoutParams(-2, dp(a, 38)).apply {
                    setMargins(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
                }
            }
        }
        // (v956) من اليمين: 🧰 ☑ 💬 ⏱▾ 🔤▾ 📝 📜
        // ☰ اتشال (قايمته كلها موجودة في 🧰/⏱/🔤/زرار الترجمة)، 📃 الطابور و🔂 التكرار اتشالوا (عند MeeTube: ⚙ ← المزيد)
        row.addView(circ("🧰", 14f) { v -> toolboxMenu(a, v) })
        // (v946) ☑/⬜ اسألني عند الخروج: أكمّل الترجمة في الخلفية؟ (نفس مفتاح ask_bg_exit في الإعدادات)
        val askV = circ(if (askOn()) "☑" else "⬜", 14f) {
            val on = !askOn()
            try { Cfg.put("ask_bg_exit", if (on) "1" else "0") } catch (_: Throwable) {}
            askBtn?.text = if (on) "☑" else "⬜"
            Notice.show(a, if (on) "هسألك لما تخرج: كمّل في الخلفية ولا لا" else "لما تخرج: الترجمة بتتقفل من غير سؤال ومن غير خلفية", 2600L)
        }
        askBtn = askV
        row.addView(askV)
        // (v954) HD · 🎙 · 🧑 اتشالوا (مش محتاجينهم) — الجودة والصوت جوه 🧰 والإعدادات
        // (v955) CC اتشال — زرار MeeTube الأصلي بيعمل نفس الشغلة
        val dualV = circ("💬", 12f) { MtTranslate.cycleDual(); refreshModes() }
        dualBtn = dualV
        row.addView(dualV)
        row.addView(chev("⏱ ▾") { v -> syncMenu(a, v, false) })   // (v949) قايمة ⏱ منبثقة (والشريط السريع جواها)
        row.addView(chev("🔤 ▾") { v -> textMenu(a, v) })
        row.addView(circ("📝", 12f) { MtTranslate.searchSentences() })
        row.addView(circ("📜", 12f) { showLog(a) })
        loopBtn = null
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
            if (MtTranslate.isTranslating()) MtTranslate.translateMenu(it) else MtTranslate.toggle()
        }.apply {
            background = u.box(0xFFE53935.toInt(), 0x33FFFFFF, 12)
            layoutParams = lp(-2)
        }
        row.addView(trChip)
        row.addView(u.fsCircle("⚙") { MtTranslate.translateMenu(trChip) }.apply {
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
    /** (v960) ⤢ التمديد (احتواء ← ملء ← تمديد ← 100%) */
    private fun fitButton(a: Activity): TextView {
        val u = uiHelper!!
        return u.fsCircle("⤢") { cycleFit(a) }.apply {
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)).apply { marginStart = dp(a, 4) }
        }.also { fitB = it }
    }

    /** (v960) ⛶ دخول/خروج الشاشة الكاملة (نفس toggleFullscreen بتاع MeeTube) — جنب زرار التمديد في كل الحالات (ملف/متصفح/يوتيوب، أفقي ورأسي) */
    private fun fullButton(a: Activity): TextView {
        val u = uiHelper!!
        return u.fsCircle("⛶") { bridge(a, "rotate") }.apply {
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)).apply { marginStart = dp(a, 4) }
        }.also { fullB = it }
    }

    /** (v960) أقل بُعد في الفيديو الشغّال (540 / 360 / 1080 …) — الفيديو الرأسي بنفس عرف يوتيوب */
    private fun curQuality(): Int {
        return try {
            val f = player?.videoFormat ?: return 0
            val w = f.width
            val h = f.height
            if (w <= 0 || h <= 0) 0 else Math.min(w, h)
        } catch (_: Throwable) { 0 }
    }

    private fun refreshQuality() {
        val b = qualB ?: return
        val q = curQuality()
        val t = if (q > 0) q.toString() + "p" else "HD"
        if (t != qualLast) { qualLast = t; b.text = t }
    }

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
        // (v970) 🔓 قفل اللمس على شمال 🗣 (أقصى الشمال): بيمنع اللمس على الفيديو لحد ما أدوس عليه تاني (زرار فك القفل بتاع MeeTube)؛ زرار القفل الأصلي العايم بقى مخفي
        left.addView(u.fsCircle("🔓") { bridge(a, "lock") }.apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(dp(a, 38), dp(a, 38)).apply { marginStart = dp(a, 3) }
        })
        left.addView(u.fsBtn("🗣 ▾") { v -> dialectMenu(a, v, true) }.apply {
            textSize = 12f
            minimumWidth = dp(a, 38)
            setPadding(dp(a, 8), 0, dp(a, 8), 0)
            layoutParams = LinearLayout.LayoutParams(-2, dp(a, 38)).apply { marginStart = dp(a, 3) }
        })
        // (v960) زرار الجودة جنب 🗣▾ — لاند سكيب بس — رقم الجودة إجباري (540p / 360p …) والدوسة = قايمة الجودة
        if (a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            qualLast = ""
            qualB = u.fsBtn("HD") { bridge(a, "quality") }.apply {
                textSize = 12f
                minimumWidth = dp(a, 46)
                setPadding(dp(a, 8), 0, dp(a, 8), 0)
                layoutParams = LinearLayout.LayoutParams(-2, dp(a, 38)).apply { marginStart = dp(a, 4) }
            }
            left.addView(qualB)
            refreshQuality()
        }
        // (v954) 🌐 و🎧 اتشالوا من الشريط السفلي (مكرّرين: 🌐 = ⚙ شريحة الترجمة، 🎧 = سماعة MeeTube اللي فوق الشمال)
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
        // (v966) ▲ الإشعارات الحمرا اتشال خالص
        noticeB = null
        // (v960) ⤢ التمديد + ⛶ الشاشة الكاملة جنب بعض (الأفقي هنا؛ الرأسي في صف الحجم تحت)
        if (a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            right.addView(fitButton(a))
            right.addView(fullButton(a))
        }
        // (v955) 🔄 ⧉ ⛶ اتشالوا — التدوير والـPiP في ⚙ MeeTube
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
        // (v963) «📄 تصدير» وسلايدر الصوت اتشالوا (التصدير لسه في 🧰 الأدوات)
        // (v960) الرأسي: ⤢ التمديد + ⛶ الشاشة الكاملة في آخر الصف (كان زرار الشاشة الكاملة في آخر صفحة اليوتيوب تحت)
        row.addView(fitButton(a))
        row.addView(fullButton(a))
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
        // (v969) الوضع الحالي من المشغّل نفسه (الافتراضي Fill، أو الدخول بورتريه/لاندسكيب) مش من Cfg
        val cur = try {
            when ((a as? com.newtube.mobile.ui.playback.MobilePlaybackActivity)?.resizeMode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> 1
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> 3
                null -> Cfg.int("fit", 0)
                else -> 0
            }
        } catch (_: Throwable) { 0 }
        val idx = fitCodes.indexOf(if (cur == 2) 1 else cur)
        val n = fitCodes[(idx + 1) % fitCodes.size]
        try { Cfg.put("fit", n.toString()) } catch (_: Throwable) {}
        bridge(a, "fit:" + n)
        fitB?.text = "⤢"
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

    /** (v947) قايمة منبثقة جنب الزرار (PopupWindow زي togglePop في المشغّل القديم): عرض أدنى 150dp، لوح زجاجي، بتتسكرول لو أطول من المتاح؛ دوسة تانية على نفس الزرار بتقفلها */
    private var popup: android.widget.PopupWindow? = null
    private var popupOwner: View? = null

    /** (v958) قوايم/لوحات أزرار Joyboy بتفتح في نافذة MeeTube الأصلية (BottomSheetDialog بتاعتها) لو المشغّل MeeTube؛ null = مش MeeTube (تلفزيون/ريموت) فنرجع للقديم */
    internal fun meeRows(a: Activity, title: String, items: List<Pair<String, (() -> Unit)?>>): android.app.Dialog? {
        val h = a as? com.newtube.mobile.ui.playback.MobilePlaybackActivity ?: return null
        return try {
            val acts = ArrayList<Runnable?>()
            for (it in items) { val f = it.second; acts.add(if (f == null) null else Runnable { f() }) }
            h.joyboyRows(title, items.map { it.first }, acts)
        } catch (t: Throwable) { LogStore.err("MtBar:meeRows", t); null }
    }

    internal fun meeViews(a: Activity, title: String, views: List<View>): android.app.Dialog? {
        val h = a as? com.newtube.mobile.ui.playback.MobilePlaybackActivity ?: return null
        return try { h.joyboySheet(title, views) } catch (t: Throwable) { LogStore.err("MtBar:meeViews", t); null }
    }

    private fun dismissPopup() {
        try { popup?.dismiss() } catch (_: Throwable) {}
        popup = null
        popupOwner = null
    }

    /** (v949) نسخة عامة من popupRows: بتاخد Views جاهزة (سلايدر/شيبس/نصوص) وتحطها جوه نفس اللوح المنبثق جنب الزرار */
    private fun popupViews(a: Activity, anchor: View, above: Boolean, views: List<View>, minWdp: Int = 150) {
        val had = popupOwner === anchor
        dismissPopup()
        if (had) return
        if (meeViews(a, "", views) != null) return   // (v958) نافذة MeeTube الأصلية
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(a, 6), dp(a, 6), dp(a, 6), dp(a, 6))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF2242432.toInt(), 0xF5101017.toInt())
            ).apply {
                cornerRadius = dp(a, 24).toFloat()
                setStroke(1, 0x3DFFFFFF)
            }
        }
        for (v in views) {
            (v.parent as? ViewGroup)?.removeView(v)
            val lp = v.layoutParams
            if (lp is LinearLayout.LayoutParams) {
                if (lp.width == -1) lp.width = dp(a, maxOf(minWdp, 240))
                col.addView(v, lp)
            } else if (v is FlowRow) {
                col.addView(v, LinearLayout.LayoutParams(dp(a, maxOf(minWdp, 240)), -2))
            } else {
                col.addView(v, LinearLayout.LayoutParams(-2, -2))
            }
        }
        val dm = a.resources.displayMetrics
        col.measure(
            View.MeasureSpec.makeMeasureSpec((dm.widthPixels * 0.9f).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val avail = (if (above) loc[1] - dp(a, 8) else dm.heightPixels - loc[1] - anchor.height - dp(a, 8)).coerceAtLeast(dp(a, 120))
        val popH = minOf(col.measuredHeight, avail)
        val sv = android.widget.ScrollView(a).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(col)
        }
        val pw = android.widget.PopupWindow(sv, maxOf(col.measuredWidth, dp(a, minWdp)), popH, false)
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.setOnDismissListener { if (popup === pw) { popup = null; popupOwner = null } }
        try {
            if (above) pw.showAsDropDown(anchor, 0, -(anchor.height + popH + dp(a, 2)))
            else pw.showAsDropDown(anchor, 0, dp(a, 2))
            popup = pw
            popupOwner = anchor
            try { com.newtube.mobile.ui.common.PlayerTrace.popup((anchor as? TextView)?.text?.toString()?.take(6) ?: anchor.javaClass.simpleName, anchor, pw) } catch (_: Throwable) {}   // (v952) لوج مكان القايمة وحجمها
        } catch (_: Throwable) {}
    }

    private fun popupRows(a: Activity, anchor: View, above: Boolean, items: List<Pair<String, () -> Unit>>, title: String = "") {
        if (meeRows(a, title, items.map { Pair<String, (() -> Unit)?>(it.first, it.second) }) != null) return   // (v958) نافذة MeeTube الأصلية
        val u = uiHelper ?: return
        val views = ArrayList<View>()
        for ((t, f) in items) {
            val b = u.fsBtn(t) { dismissPopup(); f() }.apply { minimumWidth = dp(a, 150) }
            tvFocus(b)
            views.add(b)
        }
        popupViews(a, anchor, above, views)
    }

    /** (v949) لـMtTranslate: قايمة ☰ بقت منبثقة جنب الزرار بدل شيت */
    fun popupMenu(a: Activity, anchor: View, above: Boolean, items: List<Pair<String, () -> Unit>>) {
        if (uiHelper == null) uiHelper = Ui(a, Themes.current())
        popupRows(a, anchor, above, items)
    }

    /** (v949) قايمة صفوف: منبثقة لو معاها زرار، وإلا شيت زي الأول (للتلفزيون/الريموت) */
    private fun rowsMenu(a: Activity, anchor: View?, above: Boolean, title: String, items: List<Pair<String, () -> Unit>>) {
        if (anchor != null) popupRows(a, anchor, above, items, title) else sheetRows(a, title, items)
    }

    /** (v949) لوحة فيها عناصر (سلايدر/شيبس): منبثقة جنب الزرار لو فيه زرار، وإلا شيت */
    private fun panel(a: Activity, anchor: View?, above: Boolean, title: String, views: List<View>) {
        val u = uiHelper ?: return
        if (anchor != null) {
            val all = ArrayList<View>()
            all.add(u.text(title, 13f, 0xFF9AA0A6.toInt(), true).apply { setPadding(dp(a, 10), dp(a, 6), dp(a, 10), dp(a, 2)) })
            all.addAll(views)
            popupViews(a, anchor, above, all, 240)
        } else {
            u.sheet(a, title, views)
        }
    }

    /** (v949) ⏱ تزامن الترجمة: تقديم/تأخير ربع ثانية + القيمة (دوسة = صفر) + حلقة A-B + الشريط السريع — منبثقة زي gSub القديمة */
    private fun syncMenu(a: Activity, anchor: View?, above: Boolean) {
        val ms = try { MtTranslate.offsetMs() } catch (_: Throwable) { 0L }
        val items: List<Pair<String, () -> Unit>> = listOf(
            "⏱ تقديم −0.25" to { shiftSync(a, -250) },
            ("⏱ " + (if (ms > 0) "+" else "") + ms + "ms  (دوسة = صفر)") to { try { MtTranslate.shiftOffset(-MtTranslate.offsetMs()) } catch (_: Throwable) {}; refreshSyncVal() },
            "⏱ تأخير +0.25" to { shiftSync(a, 250) },
            ("🅰🅱 " + (try { MtMedia.abLabel() } catch (_: Throwable) { "حلقة A-B" })) to { try { MtMedia.abNext() } catch (_: Throwable) {} },
            "✖ إلغاء حلقة A-B" to { try { MtMedia.abClear() } catch (_: Throwable) {} },
            "⏱ شريط التزامن السريع" to { toggleSyncStrip(a) }
        )
        rowsMenu(a, anchor, above, "⏱ تزامن الترجمة", items)
    }

    /** (v949) ▲ → toolSrc لأدوات الفيديو (قص/صوت/GIF/ضغط/ترجمة ثابتة) زي toolSrc() القديمة */
    private fun toolSrc(): ToolSrc? {
        val i = info ?: return null
        val p = player ?: return null
        val lu = try { i.localUri() } catch (_: Throwable) { null } ?: return null
        val hdr = HashMap<String, String>()
        try { i.headers()?.let { hdr.putAll(it) } } catch (_: Throwable) {}
        val au = try { i.audioUrl()?.takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }
        val name = (try { i.title() } catch (_: Throwable) { null } ?: "").ifBlank { "video" }
        val dur = try { p.duration.coerceAtLeast(0L) } catch (_: Throwable) { 0L }
        val vw = try { p.videoSize.width } catch (_: Throwable) { 0 }
        val vh = try { p.videoSize.height } catch (_: Throwable) { 0 }
        val cur = try { p.currentPosition } catch (_: Throwable) { 0L }
        return ToolSrc(lu, au, name, dur, vw, vh, cur, MtTranslate.subsNow(), hdr)
    }

    private fun withSrc(a: Activity, f: (ToolSrc) -> Unit) {
        val ts = toolSrc()
        if (ts == null) Notice.show(a, "مفيش فيديو شغّال", 2300L) else f(ts)
    }

    /** (v952) تسجيل حركة المشغّل (PlayerTrace): فريم بفريم + مكان كل زرار وقايمة، في ملف PlayerTrace/trace_*.log */
    private fun toggleTrace(a: Activity) {
        com.newtube.mobile.ui.common.PlayerTrace.sink = com.newtube.mobile.ui.common.PlayerTrace.Sink { l -> try { LogStore.add(l) } catch (_: Throwable) {} }
        val p = com.newtube.mobile.ui.common.PlayerTrace.setEnabled(a, !com.newtube.mobile.ui.common.PlayerTrace.isEnabled())
        Notice.show(a, if (p != null) "📹 التسجيل شغّال — اللوج: $p" else "📹 التسجيل اتقفل — شارك اللوج من 🧰", 5000L)
    }

    private fun toolboxMenu(a: Activity, anchor: View? = null) {
        val items: List<Pair<String, () -> Unit>> = listOf(
            "👁 لقطة بصرية (ترجمة الفريم)" to { visualSnap(a) },
            "📸 لقطة فريم (PNG)" to { snapFrame(a) },
            "⬇ حمّل الفيديو" to { MtTranslate.downloadCur() },
            "🔊 مسارات الصوت" to { MtTranslate.showAudioTracks() },
            "🎧 مصدر الصوت (تلفزيون / موبايل)" to { AudioBridge.showMenu(a) },
            "📺 ابعت للتلفزيون" to { MtTranslate.sendToTv() },
            "✂ قص جزء من الفيديو" to { withSrc(a) { ToolsUi(a, uiHelper!!, Themes.current()).trim(it) } },
            "🎞 عمل GIF" to { withSrc(a) { ToolsUi(a, uiHelper!!, Themes.current()).gif(it) } },
            "📦 ضغط / تغيير الدقة" to { withSrc(a) { ToolsUi(a, uiHelper!!, Themes.current()).compress(it) } },
            "🎬 ترجمة ثابتة (هارد ساب)" to { withSrc(a) { ToolsUi(a, uiHelper!!, Themes.current()).hardsub(it) } },
            "🎧 تحويل لصوت" to { withSrc(a) { ToolsUi(a, uiHelper!!, Themes.current()).audio(it) } }
        )
        if (anchor != null) popupRows(a, anchor, false, items) else sheetRows(a, "🧰 أدوات", items)
    }

    private fun textMenu(a: Activity, anchor: View? = null) {
        val items: List<Pair<String, () -> Unit>> = listOf(
            "🎨 ستايل السبتايتل" to { MtTranslate.showStyle() },
            "🔠 خط أكبر" to { bumpSubScale(a, 10) },
            "🔡 خط أصغر" to { bumpSubScale(a, -10) }
        )
        if (anchor != null) popupRows(a, anchor, false, items) else sheetRows(a, "🔤 النص", items)
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

    private fun speedPanel(a: Activity, anchor: View? = null, above: Boolean = false) {
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
        panel(a, anchor, above, "⏩ سرعة التشغيل", listOf(lab, bar, chips))
    }

    private fun sleepMenu(a: Activity, anchor: View? = null, above: Boolean = false) {
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
        rowsMenu(a, anchor, above, "😴 مؤقت النوم", items)
    }

    private fun dialectMenu(a: Activity, anchor: View? = null, above: Boolean = false) {
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
        panel(a, anchor, above, "🌐 اللغة والأسلوب", listOf(lab, chips, toneLab, styleBar, degBar))
    }

    private fun applyTone(a: Activity, style: String?, strength: String?) {
        try {
            val o = JSONObject()
            if (style != null) o.put("style", style)
            if (strength != null) o.put("strength", strength)
            MtTranslate.rpc("engine.tone", o)
        } catch (_: Throwable) {}
    }

    private fun voiceMenu(a: Activity, anchor: View? = null, above: Boolean = false) {
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
        rowsMenu(a, anchor, above, "🎙 فصل صوت الكلام", items)
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
        if (meeViews(a, "📋 اللوج", listOf(tv)) == null) u.sheet(a, "📋 اللوج", listOf(tv), false, {}, 0.6f).show()   // (v956) كان بيبني الشيت من غير show() فالزرار مكانش بيفتح حاجة
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
