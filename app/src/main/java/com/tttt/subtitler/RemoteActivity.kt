package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * (v518) شاشة «ريموت التلفزيون» على الموبايل: بحث تلقائي · اقتران · حالة الاتصال ·
 * 🔉🔇🔊⏯ · إرسال لينك/نص للتلفزيون · نقل مفاتيح Gemini · تحديث التلفزيون من نسخة الموبايل.
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
    private var pendingLink: String? = null
    private var searching = false
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

    override fun onDestroy() { RemoteClient.removeListener(lis); super.onDestroy() }

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
        tools.visibility = if (on) View.VISIBLE else View.GONE
        connBtnBox.removeAllViews()
        if (st == RemoteClient.St.IDLE && RemoteClient.lastTv() != null) connBtnBox.addView(ui.button("🔌 اتصل بـ " + RemoteClient.lastTv()!!.name, true) { autoConnect() })
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
                    devices.addView(ui.text("ما لقيتش تلفزيون. اتأكد إن الموبايل والتلفزيون على نفس الواي فاي، وإن Subtitler شغّال على التلفزيون (افتح اللانشر).", 13f, th.muted))
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

    private fun offerUpdate() {
        Thread {
            val r = RemoteClient.offerUpdate(false)
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
