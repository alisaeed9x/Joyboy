package com.tttt.subtitler

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v365) 💻 كونسول Termux جوه التطبيق — بتكتب أمر، التطبيق يبعته لـ Termux (RUN_COMMAND في الخلفية) ويرجّعلك النتيجة (stdout/stderr/exit).
 * مش طرفية تفاعلية (مفيش nano/vim/top) — لكن كفاية لتثبيت/تشغيل/إيقاف/تحديث OmniRoute وقراءة اللوج من غير ما تسيب التطبيق.
 * (طرفية Termux كاملة جوه التطبيق مش ممكنة: حزم Termux مبنية على مسار com.termux وأندرويد بيمنع تنفيذ ملفات من مجلد التطبيق.)
 */
object TermuxRun {
    private const val PKG = "com.termux"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"
    private val ids = AtomicInteger(1000)
    val waiting = ConcurrentHashMap<Int, (String) -> Unit>()
    @Volatile var lastId = -1

    /** بيشغّل [cmd] في Termux بالخلفية؛ [onDone] بيتنادى على الـ main thread بالنتيجة النصية. بيرجّع OmniLauncher.R */
    fun run(c: Context, cmd: String, onDone: (String) -> Unit): OmniLauncher.R {
        if (!OmniLauncher.installed(c)) return OmniLauncher.R.NO_TERMUX
        if (!OmniLauncher.hasPerm(c)) return OmniLauncher.R.NO_PERM
        val id = ids.incrementAndGet()
        lastId = id
        waiting[id] = onDone
        return try {
            val cb = Intent(c, TermuxResultReceiver::class.java).putExtra("rid", id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, id, cb, flags)
            val i = Intent("com.termux.RUN_COMMAND").setClassName(PKG, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "export PATH=/data/data/com.termux/files/usr/bin:\$PATH; $cmd"))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            OmniLauncher.R.SENT
        } catch (_: SecurityException) { waiting.remove(id); OmniLauncher.R.NO_PERM } catch (_: Throwable) { waiting.remove(id); OmniLauncher.R.ERROR }
    }

    /** (v386) سكربت bash بمعاملات موضعية (\$1 \$2 …) — للنصوص الطويلة من غير مشاكل اقتباس. [onDone] على الـ main thread */
    fun runScript(c: Context, script: String, args: List<String>, onDone: (String) -> Unit): OmniLauncher.R {
        if (!OmniLauncher.installed(c)) return OmniLauncher.R.NO_TERMUX
        if (!OmniLauncher.hasPerm(c)) return OmniLauncher.R.NO_PERM
        val id = ids.incrementAndGet()
        lastId = id
        waiting[id] = onDone
        return try {
            val cb = Intent(c, TermuxResultReceiver::class.java).putExtra("rid", id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, id, cb, flags)
            val argv = arrayOf("-c", "export PATH=/data/data/com.termux/files/usr/bin:\$PATH; " + script, "oc") + args
            val i = Intent("com.termux.RUN_COMMAND").setClassName(PKG, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", argv)
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            OmniLauncher.R.SENT
        } catch (_: SecurityException) { waiting.remove(id); OmniLauncher.R.NO_PERM } catch (_: Throwable) { waiting.remove(id); OmniLauncher.R.ERROR }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("rid", -1)
        val cb = TermuxRun.waiting.remove(id) ?: return
        val b: Bundle? = i.getBundleExtra("result")
        val out = b?.getString("stdout").orEmpty().trimEnd()
        val err = b?.getString("stderr").orEmpty().trimEnd()
        val code = b?.getInt("exitCode", 0) ?: 0
        val errCode = b?.getInt("err", -1) ?: -1
        val errMsg = b?.getString("errmsg").orEmpty()
        val sb = StringBuilder()
        if (out.isNotEmpty()) sb.append(out).append('\n')
        if (err.isNotEmpty()) sb.append(err).append('\n')
        if (errCode != -1 && errMsg.isNotEmpty()) sb.append("⚠ ").append(errMsg).append('\n')
        sb.append("[exit ").append(code).append(']')
        cb(sb.toString())
    }
}

class TermuxConsoleActivity : Activity() {
    private lateinit var th: Theme
    private lateinit var ui: Ui
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val h = Handler(Looper.getMainLooper())
    private val pending = HashSet<Int>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.ensure(this)
        th = Themes.byId(try { Cfg.str("theme", "mx") } catch (_: Exception) { "mx" })
        ui = Ui(this, th); applyBars(th)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg); setPadding(ui.dp(10), ui.dp(30), ui.dp(10), ui.dp(8)) }
        val top = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.text("💻 كونسول Termux", 18f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.button("✕") { finish() }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(48), -2) })
        root.addView(top)
        root.addView(ui.text("بيبعت الأمر لـ Termux بالخلفية ويرجّع النتيجة هنا. مش طرفية تفاعلية (مفيش nano/vim).", 11.5f, th.muted).apply { setPadding(0, 0, 0, ui.dp(4)) })

        val chips = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun chip(t: String, f: () -> Unit) = chips.addView(ui.button(t) { f() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(4) })
        chip("📊 حالة OmniRoute") { exec("echo \"node: \$(node --version 2>&1)\"; echo \"omniroute: \$(command -v omniroute || echo 'مش متثبّت')\"; echo \"port 20128 → HTTP \$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:20128/v1/models)\"") }
        chip("▶ شغّل") { startOmni() }
        chip("⏹ أوقف") { exec("pkill -f 'bin/omnirout[e]' && echo اتوقف || echo 'مكانش شغّال'") }
        chip("📜 اللوج") { exec("tail -n 60 \$HOME/omniroute.log 2>&1") }
        chip("⬆ حدّث") { exec("npm install -g omniroute@latest 2>&1 | tail -n 15") }
        chip("🛠 إعداد مرة واحدة") { OmniLauncher.copySetupAndOpenTermux(this); append("📋 اتنسخ أمر الإعداد — الصقه في Termux واضغط Enter ثم ارجع هنا.") }
        chip("🤖 جهّز أوبن كود تلقائي") { OpenCodeAuto.start(this, true); append("🤖 بدأ التجهيز التلقائي في الخلفية — تابع بزرار «📜 لوج التجهيز».") }
        chip("📜 لوج التجهيز") { append(OpenCodeAuto.logText()) }
        chip("⬇ ثبّت OpenCode") { installOc() }
        chip("🧪 جرّب OpenCode") { testOc() }
        chip("🧹 مسح") { out.text = "" }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11.5f; setTextColor(th.text); setTextIsSelectable(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); background = ui.box(th.surface, th.border, 10)
            text = "جاهز. اكتب أمر تحت أو اضغط زرار فوق."
        }
        scroll = ScrollView(this).apply { addView(out) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(6) })

        input = ui.input("$ اكتب أمر (مثال: ls ~)", "", 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; typeface = Typeface.MONOSPACE; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.button("▶", true) { val c = input.text.toString().trim(); if (c.isNotEmpty()) { input.setText(""); exec(c) } }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(56), -2) })
        root.addView(row)
        setContentView(root)
    }

    private fun append(s: String) {
        val cur = out.text.toString()
        val n = (if (cur.startsWith("جاهز.")) "" else cur) + s + "\n"
        out.text = if (n.length > 20000) n.takeLast(20000) else n
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startOmni() {
        val r = OmniLauncher.start(this)
        if (r == OmniLauncher.R.SENT) {
            append("▶ بعتّ أمر تشغيل OmniRoute لـ Termux (أول مرة بيثبّته — 5-10 دقايق). تابع بزرار 📜 اللوج.")
            OmniLauncher.waitUntilUp { runOnUiThread { if (!isFinishing) append("✅ OmniRoute اشتغل (البورت 20128 بيرد)") } }
        } else problem(r)
    }

    private fun installOc() {
        append("⬇ بثبّت OpenCode في Termux (3-5 دقايق أول مرة، حوالي 105MB). استنى رسالة OC_OK…")
        val r = OpenCodeLocal.install(this) { res -> if (!isFinishing) append(res.takeLast(1500) + if (res.contains("OC_OK")) "\n✅ OpenCode جاهز. لو لسه ماسجلتش دخول: في Termux اكتب opencode auth login" else "") }
        if (r != OmniLauncher.R.SENT) problem(r)
    }

    private fun testOc() {
        append("🧪 بجرّب opencode run …")
        Thread {
            val res = try { OpenCodeLocal.ask("", "You are a precise assistant.", listOf(AiApi.Msg("user", "Say hi in one short sentence."))) } catch (e: Throwable) { "❌ " + (e.message ?: e.javaClass.simpleName) }
            runOnUiThread { if (!isFinishing) append(res) }
        }.start()
    }

    private fun problem(r: OmniLauncher.R) {
        when (r) {
            OmniLauncher.R.NO_TERMUX -> append("❌ Termux مش متسطب — نزّله من F-Droid أو GitHub (مش Play Store).")
            OmniLauncher.R.NO_PERM -> { append("🔐 محتاج إذن RUN_COMMAND — وافق على الطلب اللي ظهر. لو ماظهرش: إعدادات الأندرويد ← التطبيقات ← Subtitler ← الأذونات."); try { OmniLauncher.requestPerm(this) } catch (_: Throwable) {} }
            else -> append("❌ مقدرتش أكلّم Termux. اضغط «🛠 إعداد مرة واحدة» وجرّب تاني.")
        }
    }

    private fun exec(cmd: String) {
        append("$ $cmd")
        val r = TermuxRun.run(this, cmd) { res -> if (!isFinishing) append(res) }
        if (r != OmniLauncher.R.SENT) { problem(r); return }
        val rid = TermuxRun.lastId
        pending.add(rid)
        TermuxRun.waiting[rid]?.let { orig -> TermuxRun.waiting[rid] = { res -> pending.remove(rid); orig(res) } }
        h.postDelayed({ if (!isFinishing && pending.contains(rid)) append("⏳ لسه مفيش رد… لو فضل كده: اضغط «🛠 إعداد مرة واحدة» (allow-external-apps) أو الأمر لسه شغّال.") }, 20000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 365 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) append("✅ الإذن اتمنح — جرّب الأمر تاني.")
    }

    override fun onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null) }
}
