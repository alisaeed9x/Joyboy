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
            if (isFinishing || isDestroyed || RemoteClient.tvCtx != "player" || RemoteClient.state != RemoteClient.St.CONNECTED) { polling = false; return }
            Thread { val st = RemoteClient.player("state"); h.post { if (st != null) paintPlayer(st) } }.also { it.isDaemon = true }.start()
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

        ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(10), 0, 0) }
        fun rb(t: String, c: String) = ui.button(t) { RemoteClient.cmd(c) }.apply { textSize = 20f }
        for ((t, c) in listOf("🔉" to "vol_down", "🔇" to "mute", "🔊" to "vol_up", "⏯" to "playpause"))
            ctrl.addView(rb(t, c), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) })
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

        setContentView(ScrollView(this).apply { addView(col) })

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

    override fun onPause() { stopRepeat(); polling = false; retryOn = false; h.removeCallbacks(retryRun); h.removeCallbacks(pollRun); super.onPause() }

    override fun onResume() {
        super.onResume(); smartSig = ""; retryOn = true
        if (::smart.isInitialized && RemoteClient.state == RemoteClient.St.CONNECTED) updateSmart()
        else if (RemoteClient.state == RemoteClient.St.IDLE && !searching) h.postDelayed(retryRun, 1500L)   // (v521) رجعت للشاشة ومش متصل ← دوّر تاني
    }

    // ===== (v520) كارت المشغّل: كل أزرار مشغّل الفيديو على الموبايل =====
    private fun startPoll() { if (polling) return; polling = true; h.post(pollRun) }

    private fun pAct(a: String, v: Long = 0L) {
        Thread { val st = RemoteClient.player(a, v); h.post { if (st != null) paintPlayer(st) } }.also { it.isDaemon = true }.start()
    }

    private fun pBtn(key: String, t: String, a: String, v: Long = 0L, repeat: Boolean = false): View {
        val b = if (repeat) keyLikeRepeat(t, a, v) else ui.button(t) { pAct(a, v) }.apply { textSize = 17f }
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
        playerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE; setPadding(0, ui.dp(8), 0, 0) }
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

        pPlay = ui.button("⏯", true) { pAct("toggle") }.apply { textSize = 20f }
        pBtns["toggle"] = pPlay
        playerBox.addView(row(pBtn("prev", "⏮", "prev"), pBtn("rew", "⏪ 10", "seek", -10000L, true), pPlay, pBtn("ff", "10 ⏩", "seek", 10000L, true), pBtn("next", "⏭", "next")))
        playerBox.addView(row(pBtn("cc", "💬 ترجمة", "cc"), pBtn("subs", "🌐 مسار", "subs"), pBtn("tr", "▶ ترجم", "translate"), pBtn("more", "⋯", "more")))   // (v521) 🌐 مسار/لغة الترجمة
        playerBox.addView(row(pBtn("sc-", "🔤 −", "scale", -10L, true), pBtn("sc+", "🔤 +", "scale", 10L, true), pBtn("sh-", "⏱ −", "shift", -250L, true), pBtn("sh+", "⏱ +", "shift", 250L, true)))
        playerBox.addView(row(pBtn("speed", "⏩ 1x", "speed"), pBtn("fit", "📐", "fit"), pBtn("audio", "🔊 صوت", "audio"), pBtn("quality", "HD", "quality")))
    }

    private fun setTxt(key: String, t: String) { (pBtns[key] as? android.widget.TextView)?.text = t }

    private fun paintPlayer(st: org.json.JSONObject) {
        if (isFinishing || isDestroyed || !::pTitle.isInitialized) return
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
        sw.addView(ui.button("🎮 أزرار", !pad) { Cfg.put("remote_mode", "keys"); buildRemote(); updateSmart() }, lp(0, -2, 1f))
        sw.addView(ui.button("🖐 تاتش باد", pad) { Cfg.put("remote_mode", "pad"); buildRemote(); updateSmart() }, lp(0, -2, 1f))
        remote.addView(sw)
        smart = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        remote.addView(smart)
        buildPlayerCard()
        remote.addView(playerBox)
        if (pad) {
            remote.addView(TouchPad(this, th), LinearLayout.LayoutParams(-1, ui.dp(300)).apply { setMargins(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6)) })
        } else {
            remote.addView(row(spacer(), keyBtn("▲", "up", true), spacer()))
            remote.addView(row(keyBtn("◀", "left", true), keyBtn("OK", "ok", false, true), keyBtn("▶", "right", true)))
            remote.addView(row(spacer(), keyBtn("▼", "down", true), spacer()))
        }
        remote.addView(row(keyBtn("↩", "back", false), keyBtn("🏠", "home", false), keyBtn("☰", "menu", false), keyBtn("🕘", "recents", false)))
    }

    private fun spacer(): View = View(this)

    private fun row(vararg vs: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        for (v in vs) r.addView(v, lp(0, ui.dp(58), 1f))
        return r
    }

    private fun stopRepeat() { repKey = ""; h.removeCallbacks(repRun) }

    /** زرار ريموت: لمسة = أمر، والضغط المستمر على الأسهم بيكرر الحركة، واهتزاز خفيف */
    private fun keyBtn(t: String, k: String, repeat: Boolean, primary: Boolean = false): View = ui.button(t, primary) { }.apply {
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

    private fun cmdBtn(t: String, c: String): View = ui.button(t) { RemoteClient.cmd(c) }.apply { textSize = 20f }

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
        playerBox.visibility = if (ctx == "player") View.VISIBLE else View.GONE
        if (ctx == "player") startPoll()
        if (ctx == "other") {
            smart.addView(row(keyBtn("⏮", "prev", false), keyBtn("⏪", "rew", true), cmdBtn("⏯", "playpause"), keyBtn("⏩", "ff", true), keyBtn("⏭", "next", false)))
        }
        if (ctx == "browser" && !padMode()) {
            smart.addView(ui.button("🖐 افتح التاتش باد (أنسب للمتصفح)") { Cfg.put("remote_mode", "pad"); buildRemote(); updateSmart() })
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
