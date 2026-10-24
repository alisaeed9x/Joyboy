package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
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

    fun download(a: Activity, o: TvLink.Offer) {
        val tv = IconTextView(a).apply {
            text = "⏳ بتواصل مع الموبايل…"; textSize = 16f
            setTextColor(Themes.byId(Cfg.str("theme", "mx")).text)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = (16 * a.resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        var cancelled = false
        val dlg = try {
            GAlert(a).setTitle("⬇ تنزيل التحديث").setView(tv).setCancelable(false)
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
                                h.post { tv.text = "⬇ " + (if (total > 0) (g * 100 / total).toString() + "%   " else "") + (g / 1048576L) + " / " + (total / 1048576L) + " MB" }
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
