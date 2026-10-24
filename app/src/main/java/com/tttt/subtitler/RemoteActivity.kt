package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/**
 * (v518) شاشة «ريموت التلفزيون» على الموبايل: بحث تلقائي · اقتران · حالة الاتصال ·
 * 🔉🔇🔊⏯ · إرسال لينك/نص للتلفزيون · نقل مفاتيح Gemini · تحديث التلفزيون من نسخة الموبايل.
 * (v519) + ريموت كامل: وضع أزرار (أسهم/OK/رجوع/رئيسية/قائمة/أخيرة) ووضع تاتش باد (سحب/دوسة/دوسة طويلة/إصبعين للتمرير)
 *   ولوحة ذكية بتتغيّر على حسب اللي ظاهر على التلفزيون (فيديو شغّال ← تقديم/تأخير/تشغيل، متصفح ← تاتش باد…).
 */
class RemoteActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var th: Theme
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: IconTextView
    private lateinit var sub: IconTextView
    private lateinit var ctrl: LinearLayout
    private lateinit var tools: LinearLayout
    private lateinit var devices: LinearLayout
    private lateinit var connBtnBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var qBox: EditText
    private lateinit var mainScroll: ScrollView
    private lateinit var pageScroll: ScrollView
    private lateinit var pageTitle: IconTextView
    private lateinit var musicBox: LinearLayout
    private var pageKind = ""
    private lateinit var mTitle: IconTextView
    private lateinit var mSub: IconTextView
    private lateinit var mLine: IconTextView
    private lateinit var mTime: IconTextView
    private lateinit var mSeek: SeekBar
    private val mBtns = HashMap<String, View>()
    private var mDur = 0L
    private var mDragging = false
    private lateinit var remote: LinearLayout
    private lateinit var smart: LinearLayout
    private var smartSig = ""
    private lateinit var playerBox: LinearLayout
    private lateinit var pTitle: IconTextView
    private lateinit var pTime: IconTextView
    private lateinit var pSeek: SeekBar
    private lateinit var pPlay: View
    private val pBtns = HashMap<String, View>()
    private var pDur = 0L
    private var pDragging = false
    private var polling = false
    private val pollRun = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || pageKind.isEmpty() || RemoteClient.state != RemoteClient.St.CONNECTED) { polling = false; return }
            val k = pageKind
            Thread {
                if (k == "video") { val st = RemoteClient.player("state"); h.post { paintPlayer(st) } }
                else { val st = RemoteClient.music("state"); h.post { paintMusic(st) } }
            }.also { it.isDaemon = true }.start()
            h.postDelayed(this, 1000L)
        }
    }
    private var repKey = ""
    private val repRun = object : Runnable {
        override fun run() { if (repKey.isNotEmpty()) { RemoteClient.key(repKey); h.postDelayed(this, 110L) } }
    }
    private var pendingLink: String? = null
    private var searching = false
    private var retryOn = true   // (v521) إعادة البحث التلقائي طول ما الشاشة قدّامك ومش متصل
    private val retryRun = Runnable { if (retryOn && !isFinishing && !isDestroyed && !searching && RemoteClient.state != RemoteClient.St.CONNECTED && RemoteClient.state != RemoteClient.St.CONNECTING) search() }
    private val lis: () -> Unit = { refresh() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this); RemoteClient.init(this)
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(th.bg))
        pendingLink = intent?.getStringExtra("pending_link")

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(30))
        }
        col.addView(ui.text("📺 ريموت التلفزيون", 22f, th.text, true))
        status = ui.text("", 17f, th.text, true).apply { setPadding(0, ui.dp(12), 0, 0) }
        sub = ui.text("", 13f, th.muted)
        col.addView(status); col.addView(sub)

        connBtnBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(8), 0, 0) }
        col.addView(connBtnBox)

        ctrl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(10), 0, 0) }
        ctrl.addView(panel(vrow(cmdBtn("⏻", "power"), sleepBtn(), cmdBtn("🔇", "mute"))))
        ctrl.addView(panel(vrow(wordCmd("▶ يوتيوب", "open_yt"), wordCmd("💾 فلاشة", "open_sd")), vrow(wordCmd("🌐 متصفح", "open_browser"), wordCmd("📱 ذاكرة داخلية", "open_int"))), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        col.addView(ctrl)

        remote = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(remote)
        buildRemote()

        col.addView(ui.text("🔍 التلفزيونات", 15f, th.text, true).apply { setPadding(0, ui.dp(18), 0, ui.dp(4)) })
        col.addView(ui.button("🔍 دوّر على التلفزيون") { search() })
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(devices)

        tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tools.addView(ui.text("📤 ابعت للتلفزيون", 15f, th.text, true).apply { setPadding(0, ui.dp(18), 0, ui.dp(4)) })
        input = EditText(this).apply {
            hint = "الصق لينك (فيديو / يوتيوب / موقع) أو اكتب نص"
            setTextColor(th.text); setHintTextColor(th.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = ui.box(th.card, th.border, 12)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START
        }
        tools.addView(input, LinearLayout.LayoutParams(-1, -2))
        tools.addView(ui.button("📋 الصق من الكليبورد") { pasteClip() })
        tools.addView(ui.button("▶ افتحه على التلفزيون", true) { sendLink() })
        tools.addView(ui.button("⌨ ابعته كنص (لخانة البحث)") { sendText() })
        tools.addView(ui.text("🧰 أدوات", 15f, th.text, true).apply { setPadding(0, ui.dp(18), 0, ui.dp(4)) })
        tools.addView(ui.button("🔑 انقل مفاتيح Gemini للتلفزيون") { sendKeys() })
        tools.addView(ui.button("⬆ حدّث التلفزيون من نسخة الموبايل (" + LinkVer.name(this) + ")") { offerUpdate() })
        tools.addView(ui.button("📤 ادفع التحديث إجباري (حتى لو نفس النسخة)") { offerUpdate(true) })   // (v521)
        col.addView(tools)

        buildPlayerCard(); buildMusicCard()
        val pageCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(30))
        }
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        hdr.addView(ui.button("↩ رجوع") { closePage() }.skin().apply { textSize = 15f }, LinearLayout.LayoutParams(ui.dp(110), -2))
        pageTitle = ui.text("", 20f, th.text, true).apply { setPadding(ui.dp(12), 0, 0, 0) }
        hdr.addView(pageTitle)
        pageCol.addView(hdr); pageCol.addView(playerBox); pageCol.addView(musicBox)
        pageScroll = ScrollView(this).apply { addView(pageCol); visibility = View.GONE; setBackgroundColor(th.bg) }
        mainScroll = ScrollView(this).apply { addView(col) }
        setContentView(FrameLayout(this).apply {
            addView(mainScroll, FrameLayout.LayoutParams(-1, -1)); addView(pageScroll, FrameLayout.LayoutParams(-1, -1))
        })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71) } catch (_: Throwable) {}

        RemoteClient.addListener(lis)
        refresh()
        if (RemoteClient.state == RemoteClient.St.IDLE && RemoteClient.lastTv() != null) autoConnect()
        else if (RemoteClient.state == RemoteClient.St.IDLE) search()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        i?.getStringExtra("pending_link")?.let { pendingLink = it; refresh() }
    }

    override fun onDestroy() { stopRepeat(); RemoteClient.removeListener(lis); super.onDestroy() }

    override fun onPause() { stopRepeat(); polling = false; h.removeCallbacks(pollRun); retryOn = false; h.removeCallbacks(retryRun); h.removeCallbacks(pollRun); super.onPause() }

    override fun onResume() {
        super.onResume(); smartSig = ""; retryOn = true
        if (pageKind.isNotEmpty()) startPoll()
        if (::smart.isInitialized && RemoteClient.state == RemoteClient.St.CONNECTED) updateSmart()
        else if (RemoteClient.state == RemoteClient.St.IDLE && !searching) h.postDelayed(retryRun, 1500L)   // (v521) رجعت للشاشة ومش متصل ← دوّر تاني
    }

    // ===== (v520) كارت المشغّل: كل أزرار مشغّل الفيديو على الموبايل =====
    private fun startPoll() { if (polling) return; polling = true; h.post(pollRun) }

    private fun pAct(a: String, v: Long = 0L) {
        Thread { val st = RemoteClient.player(a, v); h.post { if (st != null) paintPlayer(st) } }.also { it.isDaemon = true }.start()
    }

    private fun pBtn(key: String, t: String, a: String, v: Long = 0L, repeat: Boolean = false): View {
        val b = (if (repeat) keyLikeRepeat(t, a, v) else ui.button(t) { pAct(a, v) }.apply { textSize = 17f }).skin()
        pBtns[key] = b
        return b
    }

    /** زرار بيكرر الأمر مع الضغط المستمر (تقديم/تأخير/حجم/إزاحة الترجمة) */
    private fun keyLikeRepeat(t: String, a: String, v: Long): View = ui.button(t) { }.apply {
        textSize = 17f
        var run: Runnable? = null
        setOnTouchListener { vw, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    vw.isPressed = true; vw.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); pAct(a, v)
                    val r = object : Runnable { override fun run() { pAct(a, v); h.postDelayed(this, 220L) } }
                    run = r; h.postDelayed(r, 450L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vw.isPressed = false; run?.let { h.removeCallbacks(it) }; run = null }
            }
            true
        }
    }

    private fun buildPlayerCard() {
        pBtns.clear()
        playerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(8), 0, 0) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = ui.box(th.card, th.border, 16); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(8))
        }
        pTitle = ui.text("🎞 —", 15f, th.text, true).apply { maxLines = 2 }
        pTime = ui.text("0:00 / 0:00", 13f, th.muted)
        pSeek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && pDur > 0) pTime.text = PlayerLogic.clock(pDur * p / 1000L) + " / " + PlayerLogic.clock(pDur)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { pDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    pDragging = false
                    val p = sb?.progress ?: 0
                    if (pDur > 0) pAct("seekto", pDur * p / 1000L)
                }
            })
        }
        card.addView(pTitle); card.addView(pSeek, LinearLayout.LayoutParams(-1, -2)); card.addView(pTime)
        playerBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), ui.dp(4)) })

        pPlay = ui.button("⏯", true) { pAct("toggle") }.skin(true).apply { textSize = 20f }
        pBtns["toggle"] = pPlay
        playerBox.addView(row(pBtn("prev", "⏮", "prev"), pBtn("rew", "⏪ 10", "seek", -10000L, true), pPlay, pBtn("ff", "10 ⏩", "seek", 10000L, true), pBtn("next", "⏭", "next")))
        playerBox.addView(row(pBtn("cc", "💬 ترجمة", "cc"), pBtn("subs", "🌐 مسار", "subs"), pBtn("tr", "▶ ترجم", "translate"), pBtn("more", "⋯", "more")))   // (v521) 🌐 مسار/لغة الترجمة
        playerBox.addView(row(ui.button("📄 ترجمة ملف الترجمة (سوفت / هارد سب)") { sfDialog() }.skin().apply { textSize = 15f }.also { pBtns["sf"] = it }))   // (v529)
        playerBox.addView(row(pBtn("sc-", "🔤 −", "scale", -10L, true), pBtn("sc+", "🔤 +", "scale", 10L, true), pBtn("sh-", "⏱ −", "shift", -250L, true), pBtn("sh+", "⏱ +", "shift", 250L, true)))
        playerBox.addView(row(pBtn("speed", "⏩ 1x", "speed"), pBtn("fit", "📐", "fit"), pBtn("audio", "🔊 صوت", "audio"), pBtn("quality", "HD", "quality")))
    }

    // ===== (v529) 📄 ترجمة ملف الترجمة من الموبايل: سوفت سب / ملف على التلفزيون / هارد سب — بجوجل أو جيميناي (بمفاتيح التلفزيون) =====
    private var lastPSt: org.json.JSONObject? = null

    private fun sfEngine(what: String, then: (String) -> Unit) {
        AlertDialog.Builder(this).setTitle("ترجم $what بإيه؟")
            .setItems(arrayOf("🌐 جوجل ترجمة — من غير مفتاح، سريع (فصحى)", "✨ جيميناي — بمفاتيح التلفزيون وباللهجة المختارة هناك")) { _, w -> then(if (w == 0) "google" else "gemini") }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun sfDialog() {
        val st = lastPSt
        if (st == null) { Notice.show(this, "مفيش فيديو شغّال على التلفزيون — شغّل فيديو الأول", 2800L); return }
        val side = st.optString("sf_side"); val nat = st.optInt("sf_nat"); val live = st.optString("sf_live")
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        add("💬 ترجمة السوفت سب" + (if (side.isNotEmpty()) " — ملف «$side»" else if (nat > 0) " — $nat مسار جوه الفيديو" else " (لو موجودة)")) {
            sfEngine("السوفت سب") { e -> pAct("subfile:soft:$e"); Notice.show(this, "📄 بدأت الترجمة على التلفزيون — التقدّم على الزرار", 3000L) }
        }
        add("📂 اختار ملف ترجمة على التلفزيون (SRT / ASS / VTT)") {
            sfEngine("الملف") { e -> pAct("subfile:pick:$e"); Notice.show(this, "اختار ملف الترجمة من شاشة التلفزيون بالريموت", 3500L) }
        }
        add("🔤 ترجمة الهارد سب (المحروقة على الصورة) — بجيميناي") { pAct("subfile:hard"); Notice.show(this, "🔤 بدأ الوضع البصري على التلفزيون (محتاج مفتاح الوضع البصري هناك)", 3500L) }
        if (live.isNotEmpty()) add("⏹ وقّف الترجمة اللحظية") { pAct("subfile:off") }
        AlertDialog.Builder(this).setTitle("📄 ترجمة ملف الترجمة")
            .setItems(labels.toTypedArray()) { _, w -> if (w in acts.indices) acts[w]() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun setTxt(key: String, t: String) { (pBtns[key] as? android.widget.TextView)?.text = t }

    private fun paintPlayer(st: org.json.JSONObject?) {
        if (isFinishing || isDestroyed || !::pTitle.isInitialized) return
        lastPSt = st
        if (st == null) { pTitle.text = "🎞 مفيش فيديو شغّال على التلفزيون دلوقتي"; return }
        pTitle.text = "🎞 " + st.optString("title")
        pDur = st.optLong("dur"); val pos = st.optLong("pos")
        if (!pDragging) {
            pSeek.progress = if (pDur > 0) (pos * 1000L / pDur).toInt().coerceIn(0, 1000) else 0
            pTime.text = PlayerLogic.clock(pos) + " / " + PlayerLogic.clock(pDur)
        }
        setTxt("toggle", if (st.optBoolean("playing")) "⏸" else "▶")
        setTxt("speed", "⏩ " + st.optString("speed"))
        setTxt("fit", "📐 " + st.optString("fit"))
        setTxt("tr", st.optString("tr"))
        setTxt("cc", if (st.optBoolean("cc")) "💬 ترجمة ✓" else "💬 ترجمة ✗")
        setTxt("sc-", "🔤 −"); setTxt("sc+", "🔤 + (" + st.optInt("scale") + "%)")
        setTxt("sh-", "⏱ −"); setTxt("sh+", "⏱ + (" + String.format("%+.2f", st.optLong("shift") / 1000.0) + ")")
        run {   // (v529) حالة «ترجمة ملف الترجمة»
            val busy = st.optString("sf_busy"); val live = st.optString("sf_live")
            setTxt("sf", if (busy.isNotEmpty()) "⏳ $busy" else if (live.isNotEmpty()) "💬 ترجمة لحظية بـ " + (if (live == "gemini") "جيميناي" else "جوجل") + " — دوس للتحكم" else "📄 ترجمة ملف الترجمة (سوفت / هارد سب)")
        }
        pBtns["audio"]?.visibility = if (st.optBoolean("audio")) View.VISIBLE else View.INVISIBLE
        pBtns["quality"]?.visibility = if (st.optBoolean("quality")) View.VISIBLE else View.INVISIBLE
    }

    /** أزرار الصوت الفعلية في الموبايل بتتحكم في صوت التلفزيون وانت في الشاشة دي */
    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean {
        if (RemoteClient.state == RemoteClient.St.CONNECTED) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP) { RemoteClient.cmd("vol_up"); return true }
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { RemoteClient.cmd("vol_down"); return true }
        }
        return super.onKeyDown(code, e)
    }

    // ===== (v519) الريموت: أزرار / تاتش باد =====
    private fun lp(w: Int, hh: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, hh, wt).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }

    private fun padMode(): Boolean = Cfg.str("remote_mode", "keys") == "pad"

    private fun buildRemote() {
        stopRepeat(); smartSig = ""
        remote.removeAllViews()
        val pad = padMode()
        val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(14), 0, ui.dp(2)) }
        sw.addView(ui.button("🎮 أزرار", !pad) { Cfg.put("remote_mode", "keys"); buildRemote(); updateSmart() }.skin(!pad), lp(0, -2, 1f))
        sw.addView(ui.button("🖐 تاتش باد", pad) { Cfg.put("remote_mode", "pad"); buildRemote(); updateSmart() }.skin(pad), lp(0, -2, 1f))
        remote.addView(sw)
        smart = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        remote.addView(smart)
        val gap = { LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) } }
        if (pad) {
            remote.addView(panel(TouchPad(this, th).also { it.layoutParams = LinearLayout.LayoutParams(-1, ui.dp(300)).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) } }, row(keyBtn("↩", "back", false), keyBtn("🏠", "home", false), cmdBtn("🎤", "mic"), menuBtn())), gap())
        } else {
            remote.addView(panel(
                row(keyBtn("↩", "back", false), keyBtn("▲", "up", true), keyBtn("🏠", "home", false)),   // رجوع شمال فوق · رئيسية اللانشر يمين فوق
                row(keyBtn("◀", "left", true), keyBtn("OK", "ok", false, true), keyBtn("▶", "right", true)),
                row(menuBtn(), keyBtn("▼", "down", true), cmdBtn("🎤", "mic"))   // قايمة إعدادات البرنامج شمال تحت · ميك يمين تحت
            ), gap())
        }
        remote.addView(panel(searchRow()), gap())
        remote.addView(panel(vrow(bigBtn("🎞 مشغّل الفيديو") { openPage("video") }, bigBtn("🎵 مشغّل الموسيقى") { openPage("music") }, h0 = 64)), gap())
    }

    // ===== (v522) شكل الريموت: أزرار مصمتة بألوان السمة + لوحات بخطوط فاصلة =====
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((a shr 16) and 255, (b shr 16) and 255) shl 16) or (ch((a shr 8) and 255, (b shr 8) and 255) shl 8) or ch(a and 255, b and 255)
    }
    private fun btnFill(): Int = if (th.isLight) blend(th.bg, 0xFF000000.toInt(), 0.09f) else blend(th.bg, 0xFFFFFFFF.toInt(), 0.16f)
    private fun btnPressed(): Int = if (th.isLight) blend(th.bg, 0xFF000000.toInt(), 0.20f) else blend(th.bg, 0xFFFFFFFF.toInt(), 0.30f)
    private fun lineCol(): Int = blend(th.bg, th.text, 0.30f)

    private fun <T : View> T.skin(primary: Boolean = false): T {
        val f = if (primary) th.primary else btnFill()
        val p = if (primary) blend(th.primary, if (th.isLight) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.22f) else btnPressed()
        val sd = android.graphics.drawable.StateListDrawable()
        sd.addState(intArrayOf(android.R.attr.state_pressed), ui.box(p, lineCol(), 14, 2))
        sd.addState(intArrayOf(), ui.box(f, lineCol(), 14, 2))
        background = sd
        if (this is android.widget.TextView) setTextColor(if (primary) (if (th.isLight) android.graphics.Color.WHITE else android.graphics.Color.BLACK) else th.text)
        return this
    }

    private fun hline(): View = View(this).apply { setBackgroundColor(lineCol()); layoutParams = LinearLayout.LayoutParams(-1, ui.dp(1)).apply { setMargins(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) } }
    private fun vline(): View = View(this).apply { setBackgroundColor(lineCol()); layoutParams = LinearLayout.LayoutParams(ui.dp(1), ui.dp(34)) }

    /** لوحة بحدود مستديرة وخطوط عرضية بين الصفوف */
    private fun panel(vararg vs: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = ui.box(th.card, lineCol(), 22, 2); setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        for ((i, v) in vs.withIndex()) { if (i > 0 && v !is TouchPad && vs[i - 1] !is TouchPad) addView(hline()); addView(v) }
    }

    /** صف بخطوط طولية بين الأزرار */
    private fun vrow(vararg vs: View, h0: Int = 58): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
        for ((i, v) in vs.withIndex()) { if (i > 0) addView(vline()); addView(v, lp(0, ui.dp(h0), 1f)) }
    }

    private fun bigBtn(t: String, f: () -> Unit): View = ui.button(t) { f() }.skin(true).apply { textSize = 15f }

    // ===== (v522) صفحات المشغّل (فيديو / موسيقى) =====
    private fun openPage(kind: String) {
        pageKind = kind
        pageTitle.text = if (kind == "video") "🎞 مشغّل الفيديو" else "🎵 مشغّل الموسيقى"
        playerBox.visibility = if (kind == "video") View.VISIBLE else View.GONE
        musicBox.visibility = if (kind == "music") View.VISIBLE else View.GONE
        mainScroll.visibility = View.GONE; pageScroll.visibility = View.VISIBLE; pageScroll.scrollTo(0, 0)
        polling = false; startPoll()
    }

    private fun closePage() {
        pageKind = ""; polling = false; h.removeCallbacks(pollRun)
        pageScroll.visibility = View.GONE; mainScroll.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (pageKind.isNotEmpty()) closePage() else super.onBackPressed() }

    private fun mAct(a: String, v: Long = 0L) {
        Thread { val st = RemoteClient.music(a, v); h.post { paintMusic(st) } }.also { it.isDaemon = true }.start()
    }

    private fun mBtn(key: String, t: String, a: String, v: Long = 0L, primary: Boolean = false): View {
        val b = ui.button(t, primary) { mAct(a, v) }.skin(primary).apply { textSize = 17f }
        mBtns[key] = b
        return b
    }

    private fun buildMusicCard() {
        mBtns.clear()
        musicBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE; setPadding(0, ui.dp(8), 0, 0) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = ui.box(th.card, lineCol(), 16, 2); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(8))
        }
        mTitle = ui.text("🎵 —", 17f, th.text, true).apply { maxLines = 2 }
        mSub = ui.text("", 13f, th.muted)
        mLine = ui.text("", 15f, th.primary, true).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)); maxLines = 3 }
        mTime = ui.text("0:00 / 0:00", 13f, th.muted)
        mSeek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser && mDur > 0) mTime.text = PlayerLogic.clock(mDur * p / 1000L) + " / " + PlayerLogic.clock(mDur) }
                override fun onStartTrackingTouch(sb: SeekBar?) { mDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { mDragging = false; if (mDur > 0) mAct("seekto", mDur * (sb?.progress ?: 0) / 1000L) }
            })
        }
        card.addView(mTitle); card.addView(mSub); card.addView(mLine); card.addView(mSeek, LinearLayout.LayoutParams(-1, -2)); card.addView(mTime)
        musicBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), ui.dp(4)) })
        musicBox.addView(panel(
            vrow(mBtn("prev", "⏮", "prev"), mBtn("rew", "⏪ 10", "seek", -10000L), mBtn("toggle", "⏯", "toggle", 0L, true), mBtn("ff", "10 ⏩", "seek", 10000L), mBtn("next", "⏭", "next")),
            vrow(mBtn("shuffle", "🔀", "shuffle"), mBtn("mode", "🔁", "mode"), ui.button("📜 كلمات") { showLyrics() }.skin().apply { textSize = 16f }.also { mBtns["lyr"] = it })
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
    }

    private fun paintMusic(st: org.json.JSONObject?) {
        if (isFinishing || isDestroyed || !::mTitle.isInitialized) return
        if (st == null) { mTitle.text = "🎵 مفيش موسيقى شغّالة على التلفزيون دلوقتي"; mSub.text = ""; mLine.text = ""; return }
        mTitle.text = "🎵 " + st.optString("title")
        mSub.text = st.optString("artist")
        mLine.text = st.optString("line")
        mDur = st.optLong("dur"); val pos = st.optLong("pos")
        if (!mDragging) {
            mSeek.progress = if (mDur > 0) (pos * 1000L / mDur).toInt().coerceIn(0, 1000) else 0
            mTime.text = PlayerLogic.clock(pos) + " / " + PlayerLogic.clock(mDur)
        }
        (mBtns["toggle"] as? android.widget.TextView)?.text = if (st.optBoolean("playing")) "⏸" else "▶"
        (mBtns["shuffle"] as? android.widget.TextView)?.text = if (st.optBoolean("shuffle")) "🔀 ✓" else "🔀"
        (mBtns["mode"] as? android.widget.TextView)?.text = when (st.optInt("mode")) { 1 -> "🔁 الكل"; 2 -> "🔂 واحدة"; else -> "🔁" }
        (mBtns["lyr"] as? android.widget.TextView)?.text = if (st.optBoolean("haslyr")) "📜 كلمات" else "📜 (مفيش كلمات محفوظة)"
    }

    private fun showLyrics() {
        Thread {
            val t = RemoteClient.musicLyrics()
            h.post {
                if (isFinishing || isDestroyed) return@post
                if (t.isNullOrBlank()) Notice.show(this, "مفيش كلمات محفوظة للأغنية دي على التلفزيون — اجيبها من مشغّل الموسيقى هناك الأول", 3600L)
                else AlertDialog.Builder(this).setTitle("📜 الكلمات").setMessage(t).setPositiveButton("تمام", null).show()
            }
        }.also { it.isDaemon = true }.start()
    }

    /** (v522) بحث: اللي تكتبه يتبعت لخانة البحث على التلفزيون */
    private fun searchRow(): View {
        if (!::qBox.isInitialized) {
            qBox = EditText(this).apply {
                hint = "🔍 ابحث على التلفزيون"
                setTextColor(th.text); setHintTextColor(th.muted); setSingleLine(true)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                background = ui.box(btnFill(), lineCol(), 14, 2)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                setOnEditorActionListener { _, a, _ -> if (a == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { sendQuery(); true } else false }
            }
        }
        (qBox.parent as? android.view.ViewGroup)?.removeView(qBox)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(8), 0, 0) }
        r.addView(qBox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        r.addView(ui.button("🔍", true) { sendQuery() }.skin(true).apply { textSize = 18f }, LinearLayout.LayoutParams(ui.dp(64), -2))
        return r
    }

    private fun sendQuery() {
        val t = qBox.text.toString().trim()
        if (t.isEmpty()) return
        Thread {
            val er = RemoteClient.sendText(t)
            h.post { Notice.show(this, if (er == null) "✅ اتبعت لبحث التلفزيون" else "⚠ $er", 2200L) }
        }.also { it.isDaemon = true }.start()
    }

    /** ☰: في المتصفح بتفتح قايمة المتصفح، وغير كده إعدادات برنامج التلفزيون */
    private fun menuBtn(): View = ui.button("☰") { if (RemoteClient.tvCtx == "browser") RemoteClient.key("menu") else RemoteClient.cmd("open_settings") }.skin().apply { textSize = 20f }

    /** كيبورد الموبايل ← أي خانة على التلفزيون */
    private fun askTvText() {
        val et = EditText(this).apply { hint = "اكتب هنا وهيتبعت لخانة الكتابة على التلفزيون"; setSingleLine(false) }
        AlertDialog.Builder(this).setTitle("⌨ كيبورد").setView(et)
            .setPositiveButton("ابعت") { _, _ ->
                val t = et.text.toString()
                if (t.isNotEmpty()) Thread {
                    val er = RemoteClient.sendText(t)
                    h.post { Notice.show(this, if (er == null) "✅ النص وصل للتلفزيون" else "⚠ $er", 2200L) }
                }.also { it.isDaemon = true }.start()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun wordCmd(t: String, c: String): View = ui.button(t) { RemoteClient.cmd(c) }.skin().apply { textSize = 15f }

    /** سليب: مؤقت نوم على التلفزيون */
    private fun sleepBtn(): View = ui.button("😴") {
        val opts = arrayOf("15 دقيقة", "30 دقيقة", "60 دقيقة", "90 دقيقة", "إلغاء المؤقت")
        val mins = intArrayOf(15, 30, 60, 90, 0)
        AlertDialog.Builder(this).setTitle("😴 مؤقت نوم التلفزيون")
            .setItems(opts) { _, i ->
                RemoteClient.cmd("sleep:" + mins[i])
                Notice.show(this, if (mins[i] == 0) "اتلغى مؤقت النوم" else "😴 التلفزيون هيقفل بعد " + opts[i], 2600L)
            }.show()
    }.skin().apply { textSize = 20f }

    private fun spacer(): View = View(this)

    private fun row(vararg vs: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        for (v in vs) r.addView(v, lp(0, ui.dp(58), 1f))
        return r
    }

    private fun stopRepeat() { repKey = ""; h.removeCallbacks(repRun) }

    /** زرار ريموت: لمسة = أمر، والضغط المستمر على الأسهم بيكرر الحركة، واهتزاز خفيف */
    private fun keyBtn(t: String, k: String, repeat: Boolean, primary: Boolean = false): View = ui.button(t, primary) { }.skin(primary).apply {
        textSize = 20f
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    RemoteClient.key(k)
                    if (repeat) { repKey = k; h.removeCallbacks(repRun); h.postDelayed(repRun, 380L) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; stopRepeat() }
            }
            true
        }
    }

    private fun cmdBtn(t: String, c: String): View = ui.button(t) { RemoteClient.cmd(c) }.skin().apply { textSize = 20f }

    /** اللوحة الذكية: بتتغيّر على حسب اللي ظاهر على التلفزيون */
    private fun updateSmart() {
        if (!::smart.isInitialized) return
        val ctx = RemoteClient.tvCtx
        val sig = ctx + "|" + RemoteClient.tvAcc + "|" + padMode()
        if (sig == smartSig) return
        smartSig = sig
        smart.removeAllViews()
        val label = when (ctx) {
            "player" -> "🎞 التلفزيون بيشغّل فيديو"
            "browser" -> "🌐 المتصفح مفتوح"
            "home" -> "🏠 الشاشة الرئيسية"
            "app" -> "📂 شاشة جوه Subtitler"
            "other" -> "📺 تطبيق تاني على التلفزيون"
            else -> ""
        }
        if (label.isNotEmpty()) smart.addView(ui.text(label, 13f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(2)) })
        if (ctx == "other") {
            smart.addView(row(keyBtn("⏮", "prev", false), keyBtn("⏪", "rew", true), cmdBtn("⏯", "playpause"), keyBtn("⏩", "ff", true), keyBtn("⏭", "next", false)))
        }
        if (ctx == "browser") {   // (v524) المتصفح: كليك شمال/يمين + كيبورد الموبايل
            smart.addView(panel(vrow(
                ui.button("🖱 كليك شمال") { RemoteClient.ptrClick("tap") }.skin().apply { textSize = 14f },
                ui.button("⌨") { askTvText() }.skin().apply { textSize = 20f },
                ui.button("🖱 كليك يمين") { RemoteClient.ptrClick("long") }.skin().apply { textSize = 14f })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
            if (!padMode()) smart.addView(ui.button("🖐 افتح التاتش باد (أنسب للمتصفح)") { Cfg.put("remote_mode", "pad"); buildRemote(); updateSmart() }.skin())
        }
        if (ctx == "other" && !RemoteClient.tvAcc) {
            smart.addView(ui.text("⚠ الأسهم والتاتش باد في التطبيقات التانية محتاجين تفعّل «إمكانية الوصول» لـ Subtitler على التلفزيون مرة واحدة: ⋯ المزيد ← 📱 ريموت الموبايل ← زرار التفعيل. الصوت والتشغيل والرئيسية شغّالين من غيرها.", 12f, th.danger).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(4)) })
        }
    }

    private fun autoConnect() {
        Thread {
            val ok = RemoteClient.ensureConnected(this, 7000L)
            if (!ok) h.post { if (!isFinishing) { refresh(); search() } }
        }.also { it.isDaemon = true }.start()
    }

    private fun refresh() {
        if (isFinishing || isDestroyed) return
        val st = RemoteClient.state
        val tv = RemoteClient.current ?: RemoteClient.lastTv()
        val name = tv?.name ?: "التلفزيون"
        status.text = when (st) {
            RemoteClient.St.CONNECTED -> "🟢 متصل بـ $name"
            RemoteClient.St.CONNECTING -> "🟡 بتصل بـ $name…"
            RemoteClient.St.LOST -> "🔴 الاتصال انقطع — بحاول أرجّع تلقائي"
            else -> "⚪ مش متصل"
        }
        sub.text = when (st) {
            RemoteClient.St.CONNECTED -> (tv?.ip ?: "") + (if (RemoteClient.tvVName.isNotEmpty()) "  ·  نسخة التلفزيون " + RemoteClient.tvVName else "") + "  ·  نسخة الموبايل " + LinkVer.name(this)
            else -> RemoteClient.lastError
        }
        val on = st == RemoteClient.St.CONNECTED
        ctrl.visibility = if (on) View.VISIBLE else View.GONE
        remote.visibility = if (on) View.VISIBLE else View.GONE
        if (on) updateSmart() else stopRepeat()
        tools.visibility = if (on) View.VISIBLE else View.GONE
        connBtnBox.removeAllViews()
        if ((st == RemoteClient.St.IDLE || st == RemoteClient.St.LOST) && RemoteClient.lastTv() != null) connBtnBox.addView(ui.button("🔌 اتصل بـ " + RemoteClient.lastTv()!!.name, true) { autoConnect() })
        else if (st == RemoteClient.St.IDLE) connBtnBox.addView(ui.button("🔌 اتصل (دوّر على التلفزيون)", true) { search() })   // (v521)
        if (st != RemoteClient.St.IDLE) connBtnBox.addView(ui.button("⏻ فصل") { RemoteClient.disconnect(this) })
        val pl = pendingLink
        if (on && pl != null) { pendingLink = null; input.setText(pl); sendLink() }
    }

    // ===== البحث والاقتران =====
    private fun search() {
        if (searching) return
        searching = true
        devices.removeAllViews()
        devices.addView(ui.text("⏳ بدوّر على التلفزيون في الواي فاي…", 13f, th.muted))
        Thread {
            val res = try { RemoteClient.discover(4500L) } catch (e: Throwable) { LogStore.err("Remote:discover", e); emptyList() }
            h.post {
                searching = false
                if (isFinishing || isDestroyed) return@post
                devices.removeAllViews()
                if (res.isEmpty()) {
                    devices.addView(ui.text("ما لقيتش تلفزيون — هعيد البحث تلقائي كل كام ثانية. اتأكد إن الموبايل والتلفزيون على نفس الواي فاي، وإن Subtitler شغّال على التلفزيون (افتح اللانشر).", 13f, th.muted))
                    if (retryOn && RemoteClient.state != RemoteClient.St.CONNECTED) h.postDelayed(retryRun, 4000L)   // (v521) فشل ← جرّب تاني
                } else if (res.size == 1 && RemoteClient.state != RemoteClient.St.CONNECTED && RemoteClient.state != RemoteClient.St.CONNECTING) {
                    choose(res[0])   // (v521) تلفزيون واحد بس ← اتصل بيه على طول (مقترن قبل كده أو أول مرة من غير كود)
                } else for (f in res) {
                    val paired = RemoteClient.pairedList().any { it.id == f.id }
                    devices.addView(ui.button((if (paired) "✓ " else "") + f.name + "  (" + f.ip + ")") { choose(f) })
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun choose(f: RemoteClient.Found) {
        Thread {
            val saved = RemoteClient.pairedList().firstOrNull { it.id == f.id }
            if (saved != null) {
                saved.ip = f.ip; saved.port = f.port; RemoteClient.saveTv(saved)
                RemoteClient.connect(this, saved, true); return@Thread
            }
            val (tv, why) = RemoteClient.pair(f, "")
            h.post {
                if (isFinishing) return@post
                if (tv != null) RemoteClient.connect(this, tv, true)
                else if (why == "need_code") askCode(f)
                else if (why == "locked") Notice.show(this, "⛔ محاولات كتير غلط — استنى دقيقة", 3000L)
                else Notice.show(this, "⚠ $why", 3000L)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun askCode(f: RemoteClient.Found) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "الكود اللي على شاشة التلفزيون"
            filters = arrayOf(android.text.InputFilter.LengthFilter(5)); gravity = Gravity.CENTER; textSize = 24f
        }
        AlertDialog.Builder(this).setTitle("🔐 كود الإقران")
            .setMessage("على التلفزيون: ⋯ المزيد ← 📱 ريموت الموبايل — هتلاقي كود من 5 أرقام.")
            .setView(et)
            .setPositiveButton("اقترن") { _, _ ->
                val code = et.text.toString().trim()
                Thread {
                    val (tv, why) = RemoteClient.pair(f, code)
                    h.post {
                        if (isFinishing) return@post
                        if (tv != null) RemoteClient.connect(this, tv, true)
                        else Notice.show(this, if (why == "need_code") "⚠ الكود غلط أو انتهى" else "⚠ $why", 3000L)
                    }
                }.also { it.isDaemon = true }.start()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    // ===== الإرسال =====
    private fun pasteClip() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (t.isNotBlank()) input.setText(t) else Notice.show(this, "الكليبورد فاضي", 1800L)
        } catch (_: Throwable) {}
    }

    private fun sendLink() {
        val raw = input.text.toString()
        val u = Regex("https?://\\S+").find(raw)?.value
        if (u == null) { Notice.show(this, "مفيش لينك في الكلام ده — استخدم «ابعته كنص»", 2800L); return }
        Thread {
            val er = RemoteClient.openLink(u)
            h.post { Notice.show(this, if (er == null) "✅ اتبعت للتلفزيون — لو على تطبيق تاني ارجع لشاشة اللانشر" else "⚠ $er", 3200L) }
        }.also { it.isDaemon = true }.start()
    }

    private fun sendText() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        Thread {
            val er = RemoteClient.sendText(t)
            h.post { Notice.show(this, if (er == null) "✅ النص وصل للتلفزيون" else "⚠ $er", 2600L) }
        }.also { it.isDaemon = true }.start()
    }

    private fun sendKeys() {
        if (!RemoteClient.hasKeys()) { Notice.show(this, "مفيش مفاتيح Gemini على الموبايل", 2600L); return }
        AlertDialog.Builder(this).setTitle("🔑 نقل المفاتيح")
            .setMessage("هنبعت مفاتيح Gemini (الأساسية والاحتياطية والإضافية وأوضاعها) للتلفزيون، وهتحل محل اللي هناك. تكمل؟")
            .setPositiveButton("ابعت") { _, _ ->
                Thread {
                    val er = RemoteClient.sendKeys()
                    h.post { Notice.show(this, if (er == null) "✅ المفاتيح وصلت للتلفزيون" else "⚠ $er", 3000L) }
                }.also { it.isDaemon = true }.start()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun offerUpdate(force: Boolean = false) {
        Thread {
            val r = RemoteClient.offerUpdate(false, force)
            h.post {
                Notice.show(this, when (r) {
                    "ok" -> "✅ ظهرت رسالة التحديث على التلفزيون (افتح اللانشر لو مش ظاهرة)"
                    else -> "⚠ $r"
                }, 4000L)
            }
        }.also { it.isDaemon = true }.start()
    }
}

/**
 * (v519) تاتش باد: إصبع واحد = تحريك المؤشر · دوسة سريعة = نقر · دوسة طويلة = ضغط مطوّل · إصبعين = تمرير.
 * الحركة بتتبعت كنسبة من حجم اللوحة، فالتلفزيون يحوّلها لبكسلات شاشته (حساسية TvInput.GAIN).
 */
class TouchPad(ctx: Context, private val th: Theme) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = th.card }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = th.border }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.muted; textSize = 13f * d; textAlign = Paint.Align.CENTER }
    private var lastX = 0f; private var lastY = 0f; private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var moved = false; private var two = false; private var longFired = false
    private val longRun = Runnable {
        if (!moved && !two) { longFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); RemoteClient.ptrClick("long") }
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(2f * d, 2f * d, width - 2f * d, height - 2f * d)
        c.drawRoundRect(r, 20f * d, 20f * d, fill); c.drawRoundRect(r, 20f * d, 20f * d, line)
        val cx = width / 2f; var y = height / 2f - 26f * d
        for (l in listOf("اسحب بإصبع = مؤشر", "دوسة = نقر", "دوسة طويلة = ضغط مطوّل", "إصبعين = تمرير")) { c.drawText(l, cx, y, txt); y += 24f * d }
    }

    private fun fx(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun fy(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f); val h = height.toFloat().coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downT = e.eventTime
                moved = false; two = false; longFired = false
                removeCallbacks(longRun); postDelayed(longRun, 550L)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { two = true; removeCallbacks(longRun); lastX = fx(e); lastY = fy(e) }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val x = fx(e); val y = fy(e)
                    RemoteClient.ptrScroll((x - lastX) / w, (y - lastY) / h)
                    lastX = x; lastY = y; moved = true
                } else if (!two) {
                    if (!moved && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < slop) return true
                    moved = true; removeCallbacks(longRun)
                    RemoteClient.ptrMove((e.x - lastX) / w, (e.y - lastY) / h)
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                if (!moved && !two && !longFired && e.eventTime - downT < 400L) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); RemoteClient.ptrClick("tap")
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longRun)
        }
        return true
    }
}

/**
 * (v518) هدف مشاركة «📺 ابعت للتلفزيون»: من أي تطبيق (كروم / يوتيوب …) دوس مشاركة ← اختار الأيقونة دي ←
 * اللينك يتبعت للتلفزيون ويشتغل هناك على طول. من غير شاشة (شفّافة) وبتقفل لوحدها.
 */
class SendToTvActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this); RemoteClient.init(this)
        val raw = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val url = Regex("https?://\\S+").find(raw)?.value
        if (url == null) { Notice.show(this, "⚠ مفيش لينك في اللي اتشارك", 2600L); h.postDelayed({ finish() }, 2800L); return }
        if (RemoteClient.lastTv() == null) {
            // مفيش تلفزيون مقترن: افتح شاشة الريموت واللينك يتبعت أول ما يتصل
            startActivity(Intent(this, RemoteActivity::class.java).putExtra("pending_link", url))
            finish(); return
        }
        Notice.show(this, "📺 بوصّل بالتلفزيون…", 6000L)
        Thread {
            var msg = ""
            try {
                if (!RemoteClient.ensureConnected(this, 8000L)) msg = "⚠ ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else {
                    val er = RemoteClient.openLink(url)
                    msg = if (er == null) "✅ اتبعت للتلفزيون" else "⚠ $er"
                }
            } catch (e: Throwable) { LogStore.err("SendToTv", e); msg = "⚠ حصلت مشكلة" }
            h.post { Notice.show(this, msg, 2600L); h.postDelayed({ if (!isFinishing) finish() }, 2800L) }
        }.also { it.isDaemon = true }.start()
    }
}
