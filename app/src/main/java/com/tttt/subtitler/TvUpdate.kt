package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v518) تحديث التلفزيون من الموبايل على الواي فاي:
 * الموبايل بيعرض نسخته (ملف الـAPK بتاعه بيوزّعه هو) ← التلفزيون بيسألك «تحديث؟» ← ينزّل ← شاشة تثبيت أندرويد.
 * أندرويد بيطلّع شاشة تأكيد التثبيت على التلفزيون (مفيش تثبيت صامت) ومحتاج صلاحية «تثبيت تطبيقات غير معروفة» مرة واحدة.
 * الـAPK لازم يكون متوقّع بنفس مفتاح التوقيع (ده بيحصل أصلًا في بناء GitHub).
 */
object TvUpdate {
    private val h = Handler(Looper.getMainLooper())
    @Volatile var pendingApk: File? = null

    fun ask(a: Activity, o: TvLink.Offer, done: (Boolean) -> Unit) {
        try {
            GAlert(a).setTitle("⬆ تحديث من الموبايل")
                .setMessage(if (o.force) "«" + o.from + "» بيدفع تحديث إجباري: Subtitler " + o.vname + " (على التلفزيون دلوقتي: " + LinkVer.name(a) + ").\nتنزّله وتثبّته دلوقتي من الموبايل على الواي فاي؟" else "«" + o.from + "» عليه Subtitler " + o.vname + " (أحدث من اللي على التلفزيون: " + LinkVer.name(a) + ").\nتنزّله دلوقتي من الموبايل على الواي فاي؟")
                .setPositiveButton("تحديث") { _, _ -> done(true); download(a, o) }
                .setNegativeButton("لاحقًا") { _, _ -> done(false) }
                .setOnCancelListener { done(false) }
                .show()
        } catch (e: Throwable) { LogStore.err("TvUpdate:ask", e); done(false) }
    }

    private var overlayRoot: View? = null

    /**
     * (v525) عرض التحديث فوق أي تطبيق تاني (مثلًا فيديو شغّال في تطبيق غير Subtitler):
     * بنستخدم نافذة إمكانية الوصول (لو مفعّلة) أو صلاحية «العرض فوق التطبيقات». لو الاتنين مش متاحين بنرجّع false.
     * «تحديث» = بنفتح Subtitler وهو بيكمّل التنزيل (الأندرويد مبيسمحش بتنزيل/تثبيت من نافذة عايمة).
     */
    fun askOverlay(ctx: Context, o: TvLink.Offer, done: (Boolean) -> Unit): Boolean {
        val svc = TvAccess.svc
        val type: Int
        val c: Context
        if (svc != null) { type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY; c = svc }
        else if (Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(ctx)) { type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; c = ctx }
        else return false
        if (overlayRoot != null) return true
        try {
            val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val th = Themes.byId(Cfg.str("theme", "mx"))
            val d = c.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            var root: FrameLayout? = null
            var finished = false
            fun close(accepted: Boolean) {
                if (finished) return
                finished = true
                try { root?.let { wm.removeView(it) } } catch (_: Throwable) {}
                overlayRoot = null
                done(accepted)
                if (accepted) {
                    try {
                        ctx.startActivity(Intent(ctx, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    } catch (e: Throwable) { LogStore.err("TvUpdate:overlayOpen", e) }
                }
            }
            val fr = object : FrameLayout(c) {
                override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                    if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) close(false); return true }
                    return super.dispatchKeyEvent(e)
                }
            }
            root = fr
            fr.setBackgroundColor(0x99000000.toInt())
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(24), dp(20), dp(24), dp(16))
                background = GradientDrawable().apply { setColor(th.card); setStroke(dp(1), th.border); cornerRadius = dp(22).toFloat() }
            }
            card.addView(TextView(c).apply { text = "⬆ تحديث من الموبايل"; textSize = 22f; setTextColor(th.primary); typeface = android.graphics.Typeface.DEFAULT_BOLD })
            val msg = (if (o.force) "«" + o.from + "» بيدفع تحديث إجباري: Subtitler " + o.vname else "«" + o.from + "» عليه Subtitler " + o.vname + " (أحدث من اللي على التلفزيون: " + LinkVer.name(c) + ")") +
                "\nتفتح Subtitler وتنزّل التحديث دلوقتي؟"
            card.addView(TextView(c).apply { text = msg; textSize = 17f; setTextColor(th.text); setPadding(0, dp(10), 0, dp(14)); setLineSpacing(0f, 1.2f) })
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
            fun btn(t: String, main: Boolean, act: () -> Unit): TextView = TextView(c).apply {
                text = t; textSize = 17f; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                setPadding(dp(26), dp(11), dp(26), dp(11))
                fun paint(f: Boolean) {
                    background = GradientDrawable().apply { setColor(if (f) Color.WHITE else if (main) th.primary else 0x33FFFFFF); cornerRadius = dp(24).toFloat() }
                    setTextColor(if (f) Color.BLACK else if (main) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
                }
                paint(false)
                setOnFocusChangeListener { _, f -> paint(f) }
                setOnClickListener { act() }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, dp(8), 0) }
            }
            val yes = btn("تحديث", true) { close(true) }
            val no = btn("لاحقًا", false) { close(false) }
            row.addView(yes); row.addView(no)
            card.addView(row)
            fr.addView(card, FrameLayout.LayoutParams((c.resources.displayMetrics.widthPixels * 0.6f).toInt().coerceAtLeast(dp(300)), -2, Gravity.CENTER))
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            wm.addView(fr, lp)
            overlayRoot = fr
            fr.post { try { yes.requestFocus() } catch (_: Throwable) {} }
            return true
        } catch (e: Throwable) { LogStore.err("TvUpdate:overlay", e); overlayRoot = null; return false }
    }

    fun download(a: Activity, o: TvLink.Offer) {
        val tv = IconTextView(a).apply {
            text = "⏳ بتواصل مع الموبايل…"; textSize = 16f
            setTextColor(Themes.byId(Cfg.str("theme", "mx")).text)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = (16 * a.resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        // (v531) شريط تقدّم حقيقي تحت النسبة والميجات (قبل كده كان نص بس)
        val dd = a.resources.displayMetrics.density
        val bar = android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = true
            try { progressTintList = android.content.res.ColorStateList.valueOf(Themes.byId(Cfg.str("theme", "mx")).primary) } catch (_: Throwable) {}
        }
        val boxV = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(tv, LinearLayout.LayoutParams(-1, -2))
            addView(bar, LinearLayout.LayoutParams(-1, (14 * dd).toInt()).apply { setMargins((16 * dd).toInt(), 0, (16 * dd).toInt(), (16 * dd).toInt()) })
        }
        var cancelled = false
        val dlg = try {
            GAlert(a).setTitle("⬇ تنزيل التحديث").setView(boxV).setCancelable(false)
                .setNegativeButton("إلغاء") { _, _ -> cancelled = true }.create().also { it.show() }
        } catch (e: Throwable) { LogStore.err("TvUpdate:dlg", e); null }
        Thread {
            val f = File(a.cacheDir, "tv_update.apk")
            var ok = false; var msg = ""
            try {
                try { f.delete() } catch (_: Exception) {}
                val c = URL(o.url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 15000
                if (c.responseCode != 200) throw IOException("رد الموبايل " + c.responseCode)
                val total = c.contentLengthLong
                c.inputStream.use { ins ->
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(65536)
                        var got = 0L; var lastUi = 0L
                        while (true) {
                            if (cancelled) throw IOException("اتلغى")
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); got += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 400L) {
                                lastUi = now
                                val g = got
                                h.post {
                                    tv.text = "⬇ " + (if (total > 0) (g * 100 / total).toString() + "%   " else "") + (g / 1048576L) + " / " + (total / 1048576L) + " MB"
                                    if (total > 0) { bar.isIndeterminate = false; bar.progress = (g * 1000L / total).toInt().coerceIn(0, 1000) }
                                }
                            }
                        }
                    }
                }
                if (total > 0 && f.length() != total) throw IOException("الملف ناقص")
                ok = true
            } catch (e: Exception) { msg = e.message ?: "فشل"; try { f.delete() } catch (_: Exception) {} }
            h.post {
                try { dlg?.dismiss() } catch (_: Throwable) {}
                if (ok) install(a, f) else if (!cancelled) Notice.show(a, "⚠ التحديث فشل: $msg", 3500L)
            }
        }.also { it.isDaemon = true }.start()
    }

    fun install(a: Activity, f: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
                pendingApk = f
                GAlert(a).setTitle("🔐 صلاحية التثبيت (مرة واحدة)")
                    .setMessage("أندرويد محتاج تسمح لـ Subtitler بتثبيت التطبيقات. هتتفتح شاشة الإعدادات — فعّل الخيار وارجع، والتثبيت هيكمل لوحده.")
                    .setPositiveButton("افتح الإعدادات") { _, _ ->
                        try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
                        catch (e: Throwable) { LogStore.err("TvUpdate:perm", e); Notice.show(a, "فعّل «مصادر غير معروفة» لـ Subtitler من إعدادات التلفزيون ← التطبيقات", 5000L) }
                    }
                    .setNegativeButton("إلغاء") { _, _ -> pendingApk = null }
                    .show()
                return
            }
            pendingApk = null
            val uri = FileProvider.getUriForFile(a, a.packageName + ".fmprov", f)
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) { LogStore.err("TvUpdate:install", e); Notice.show(a, "⚠ ما قدرتش أبدأ التثبيت", 3000L) }
    }

    /** بيتنادى لما أي شاشة ترجع قدّام: لو كنا مستنيين صلاحية التثبيت وبقت متاحة نكمل */
    fun resumePending(a: Activity) {
        val f = pendingApk ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && a.packageManager.canRequestPackageInstalls() && f.isFile) install(a, f)
        } catch (_: Throwable) {}
    }
}

/** (v518) شاشة «ريموت الموبايل» على التلفزيون: العنوان + كود الإقران + الريموتات المتصلة (فصل / مسح) */
object TvLinkUi {
    fun show(a: Activity) {
        try {
            TvLink.start(a)
            val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(a, th)
            val code = TvLink.newCode()
            val col = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8))
            }
            val addr = TvLink.address()
            col.addView(ui.text(if (addr.isEmpty()) "⚠ مش متصل بواي فاي" else "عنوان التلفزيون: $addr", 14f, th.muted))
            col.addView(ui.text("افتح Subtitler على الموبايل (أيقونة «ريموت التلفزيون») — هيلاقي التلفزيون لوحده. أول موبايل بيتقرن من غير كود.", 13f, th.text).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
            col.addView(ui.text("كود إقران موبايل جديد (10 دقايق):", 13f, th.muted))
            col.addView(ui.text(code, 30f, th.primary, true).apply { setPadding(0, ui.dp(4), 0, ui.dp(10)) })
            // (v519) التحكم في التطبيقات التانية (نتفليكس…) محتاج «إمكانية الوصول» مرة واحدة
            val accOn = TvAccess.connected
            col.addView(ui.text(if (accOn) "✅ التحكم في التطبيقات التانية شغّال (إمكانية الوصول)" else "⚠ التحكم بالأسهم والتاتش باد في التطبيقات التانية متوقّف — الصوت والتشغيل والرئيسية شغّالين", 13f, if (accOn) th.success else th.danger).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
            if (!accOn) col.addView(ui.button("♿ فعّل «إمكانية الوصول» لـ Subtitler") { TvAccess.openSettings(a) })
            val list = TvLink.remoteList()
            col.addView(ui.text(if (list.isEmpty()) "لسه مفيش ريموتات مقترنة." else "الريموتات المقترنة (دوس على واحد للفصل أو المسح):", 14f, th.text, true))
            lateinit var dlg: android.app.Dialog
            for (r in list) {
                val on = r.alive()
                col.addView(ui.button((if (on) "🟢 " else "⚪ ") + r.name + (if (on) " — متصل" else " — مش متصل")) {
                    try { dlg.dismiss() } catch (_: Throwable) {}
                    manage(a, r.id, r.name)
                })
            }
            dlg = GAlert(a).setTitle("📱 ريموت الموبايل").setView(col).setNegativeButton("إغلاق", null).create()
            dlg.show()
        } catch (e: Throwable) { LogStore.err("TvLinkUi:show", e) }
    }

    private fun manage(a: Activity, id: String, name: String) {
        GAlert(a).setTitle(name).setItems(arrayOf("⛔ فصل دلوقتي (يرجع لو دوس اتصال)", "🗑 مسح من المقترنين (يحتاج كود تاني)")) { _, i ->
            if (i == 0) TvLink.kick(id) else TvLink.forget(id)
        }.setNegativeButton("إلغاء", null).show()
    }
}
