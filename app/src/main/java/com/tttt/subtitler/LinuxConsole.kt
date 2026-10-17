package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** (v394) خدمة foreground: بتخلّي التجهيز والسيرفر شغّالين لما تخرج من الشاشة، وبتعرض التقدم في الإشعار */
class LinuxService : Service() {
    @Volatile private var alive = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACTION_STOP) {
            Linux.stopServer()
            try { if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) } catch (_: Throwable) {}
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val n = build(this)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        } catch (_: Throwable) { stopSelf(startId); return START_NOT_STICKY }
        if (!alive) {
            alive = true
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            Thread {
                while (alive) {
                    try { Thread.sleep(3000) } catch (_: Throwable) { break }
                    if (!Linux.busy && !Linux.serverRunning()) { alive = false; stopSelf(); break }
                    try { nm.notify(ID, build(this)) } catch (_: Throwable) {}
                }
            }.apply { isDaemon = true }.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { alive = false; super.onDestroy() }

    companion object {
        const val ID = 3941
        const val CH = "linux_env"
        const val ACTION_STOP = "com.tttt.subtitler.LINUX_STOP"

        fun build(c: Context): Notification {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "البيئة الداخلية (Linux)", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(c, 0, Intent(c, LinuxConsoleActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(c, 1, Intent(c, LinuxService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val txt = when {
                Linux.busy -> Linux.step.ifBlank { "بجهّز…" }
                Linux.isUpCached() -> "OmniRoute شغّال على 127.0.0.1:${Linux.PORT}"
                else -> "السيرفر بيشتغل…"
            }
            val b = Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("🐧 البيئة الداخلية")
                .setContentText(txt)
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel), "إيقاف", stop).build())
            if (Linux.busy && Linux.pct in 0..100) b.setProgress(100, Linux.pct, false)
            return b.build()
        }

        fun start(c: Context) { try { c.startForegroundService(Intent(c, LinuxService::class.java)) } catch (_: Throwable) {} }

        fun refresh(c: Context) {
            try {
                if (!Linux.busy && !Linux.serverRunning()) { c.stopService(Intent(c, LinuxService::class.java)); return }
                (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(c))
            } catch (_: Throwable) {}
        }
    }
}

/**
 * (v394) 🐧 كونسول Linux داخل التطبيق — طرفية كاملة (TerminalView بتاع Termux) على بيئة Ubuntu الداخلية.
 * فوق: حالة + تقدم التجهيز + أزرار (جهّز تلقائي / شغّل / أوقف / مفتاح / اختبر / لوج). تحت: الطرفية + صف مفاتيح إضافية (ESC TAB CTRL ALT أسهم).
 */
class LinuxConsoleActivity : Activity() {
    private lateinit var th: Theme
    private lateinit var ui: Ui
    private lateinit var term: TerminalView
    private lateinit var status: TextView
    private lateinit var logTv: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var bar: ProgressBar
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var alive = false
    private var ctrl = false
    private var alt = false
    private var ctrlBtn: TextView? = null
    private var altBtn: TextView? = null
    private var mode = "setup"          // setup | server | acts
    private val acts = StringBuilder()
    private var attachedOnce = false

    private val sessClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { term.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) { runOnUiThread { if (!isFinishing) { toast("الجلسة خلصت — بفتح واحدة جديدة"); Linux.killShell(); attachedOnce = false; attachShell() } } }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) { copy(text ?: "") }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@LinuxConsoleActivity)?.toString()
            if (!t.isNullOrEmpty()) (session ?: term.currentSession)?.emulator?.paste(t)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "", e) }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f
        override fun onSingleTapUp(e: MotionEvent?) { term.requestFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(term, 0) }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean { val v = ctrl; if (v) { ctrl = false; paintMods() }; return v }
        override fun readAltKey(): Boolean { val v = alt; if (v) { alt = false; paintMods() }; return v }
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "", e) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.ensure(this)
        th = Themes.byId(try { Cfg.str("theme", "mx") } catch (_: Exception) { "mx" })
        ui = Ui(this, th); applyBars(th)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg); setPadding(ui.dp(8), ui.dp(30), ui.dp(8), ui.dp(6)) }

        val top = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.text("🐧 كونسول Linux الداخلي", 17f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.button("✕") { finish() }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(48), -2) })
        root.addView(top)

        status = ui.text("…", 11.5f, th.muted).apply { setPadding(0, 0, 0, ui.dp(2)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(status)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(6)))

        val chips = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun chip(t: String, f: () -> Unit) = chips.addView(ui.button(t) { f() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(4) })
        chip("🚀 جهّز تلقائي") { setup() }
        chip("▶ شغّل أومني روت") { setup() }
        chip("⏹ أوقف") { Linux.stopServer(); LinuxService.refresh(applicationContext); act("⏹ السيرفر اتوقف") }
        chip("🔑 مفتاح") { bg { act("🔑 " + (Linux.ensureKey() ?: "المفتاح جاهز ومحفوظ في التطبيق")) } }
        chip("🧪 اختبر") { bg { act("— اختبار —"); Linux.selfCheck { act(it) } } }
        chip("⬇ OpenCode CLI") { Linux.optIn(); bg { act("⬇ بثبّت OpenCode CLI (دقايق)…"); Linux.installOpenCode(applicationContext) { act(it) } } }
        chip("📜 تجهيز") { mode = "setup"; refreshLog() }
        chip("📜 سيرفر") { mode = "server"; refreshLog() }
        chip("📋 نتايج") { mode = "acts"; refreshLog() }
        chip("📎 انسخ") { copy(currentLog()) }
        chip("🗑 احذف البيئة") { confirmWipe() }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        logTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 10.5f; setTextColor(th.text); setTextIsSelectable(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)); background = ui.box(th.surface, th.border, 8)
        }
        logScroll = ScrollView(this).apply { addView(logTv) }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, ui.dp(110)).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(4) })

        term = TerminalView(this, null).apply {
            setBackgroundColor(0xFF000000.toInt()); isFocusable = true; isFocusableInTouchMode = true; keepScreenOn = true
            setTerminalViewClient(viewClient)
        }
        root.addView(term, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(extraKeys(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        setContentView(root)
    }

    private fun extraKeys(): View {
        val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER }
        fun key(t: String, onTap: (TextView) -> Unit): TextView {
            val v = TextView(this).apply {
                text = t; textSize = 13f; setTextColor(th.text); gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE
                setPadding(ui.dp(2), ui.dp(9), ui.dp(2), ui.dp(9)); background = ui.box(th.surface, th.border, 8)
                setOnClickListener { onTap(this) }
            }
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(2); marginEnd = ui.dp(2) })
            return v
        }
        key("ESC") { send("\u001b") }
        key("TAB") { send("\t") }
        ctrlBtn = key("CTRL") { ctrl = !ctrl; paintMods() }
        altBtn = key("ALT") { alt = !alt; paintMods() }
        key("←") { arrow('D') }
        key("↓") { arrow('B') }
        key("↑") { arrow('A') }
        key("→") { arrow('C') }
        key("-") { send("-") }
        key("/") { send("/") }
        key("|") { send("|") }
        return row
    }

    private fun paintMods() {
        ctrlBtn?.setBackgroundColor(if (ctrl) th.accent else th.surface)
        altBtn?.setBackgroundColor(if (alt) th.accent else th.surface)
    }

    private fun send(s: String) {
        val ses = term.currentSession ?: return
        val b = s.toByteArray()
        ses.write(b, 0, b.size)
    }

    private fun arrow(ch: Char) {
        val app = term.currentSession?.emulator?.isCursorKeysApplicationMode == true
        send(if (app) "\u001bO$ch" else "\u001b[$ch")
    }

    // ===== أوامر =====
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun copy(s: String) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("linux", s)); toast("اتنسخ") }
    private fun bg(f: () -> Unit) { Thread { try { f() } catch (e: Throwable) { act("❌ " + (e.message ?: e.javaClass.simpleName)) } }.apply { isDaemon = true }.start() }
    private fun act(s: String) { synchronized(acts) { acts.append(s).append('\n'); if (acts.length > 12000) acts.delete(0, acts.length - 9000) }; mode = "acts"; h.post { refreshLog() } }

    private fun currentLog(): String = when (mode) { "server" -> Linux.serverLog(); "acts" -> synchronized(acts) { acts.toString() }.ifBlank { "مفيش نتايج لسه" }; else -> Linux.logText() }

    private fun refreshLog() {
        val t = currentLog()
        if (t != logTv.text.toString()) { logTv.text = t; logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun setup() {
        if (!Linux.supported(this)) { act("❌ الجهاز مش مدعوم (مفيش proot لمعماريته)"); return }
        if (Linux.ready(this)) { Linux.setupAsync(applicationContext); mode = "setup"; toast("بشغّل السيرفر…"); return }
        AlertDialog.Builder(this).setTitle("🚀 تجهيز البيئة الداخلية")
            .setMessage("هينزّل Ubuntu + Node.js + OmniRoute (حوالي 400MB تحميل و2–3GB مساحة) ويثبّتهم جوه التطبيق — بياخد 15–30 دقيقة أول مرة على الواي فاي. بعدها كل حاجة بتشتغل لوحدها. أبدأ؟")
            .setPositiveButton("ابدأ") { _, _ -> Linux.setupAsync(applicationContext); mode = "setup"; toast("بدأ التجهيز في الخلفية") }
            .setNegativeButton("لأ", null).show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this).setTitle("🗑 حذف البيئة الداخلية").setMessage("هيمسح Ubuntu وNode وOmniRoute وكل بياناتهم من التطبيق. متأكد؟")
            .setPositiveButton("احذف") { _, _ -> bg { Linux.wipe(applicationContext); attachedOnce = false; act("🗑 اتمسحت") } }
            .setNegativeButton("لأ", null).show()
    }

    private fun attachShell() {
        if (!Linux.supported(this) || !Linux.rootReady(this)) return
        if (attachedOnce && term.currentSession?.isRunning == true) return
        val s = Linux.shellSession(this, sessClient)
        term.setTextSize(ui.dp(13))
        term.attachSession(s)
        attachedOnce = true
        term.onScreenUpdated()
    }

    override fun onResume() {
        super.onResume()
        alive = true
        attachShell()
        Thread {
            while (alive) {
                val sm = try { Linux.summary(applicationContext) } catch (_: Throwable) { "…" }
                h.post {
                    if (!isFinishing) {
                        status.text = sm
                        if (Linux.busy) { bar.visibility = View.VISIBLE; bar.isIndeterminate = Linux.pct !in 0..100; if (Linux.pct in 0..100) bar.progress = Linux.pct } else bar.visibility = View.GONE
                        refreshLog()
                        if (!attachedOnce) attachShell()
                    }
                }
                try { Thread.sleep(1500) } catch (_: Throwable) { break }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onPause() { alive = false; super.onPause() }
    override fun onDestroy() { alive = false; h.removeCallbacksAndMessages(null); super.onDestroy() }
}
