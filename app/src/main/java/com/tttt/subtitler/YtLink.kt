package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * (v488) تسجيل دخول يوتيوب على التلفزيون بـ QR: التلفزيون بيعرض QR، الموبايل (عليه Subtitler) يمسحه ويسجّل بجوجل
 * بكيبورده ويبعت الجلسة للتلفزيون على الواي فاي. (مش «كود الجهاز» الرسمي بتاع جوجل — الجلسة عندنا كوكيز مش توكن.)
 *
 * التلفزيون: dialog فيه QR → http://IP:18765/ytlink?pin=XXXXXX (TypeServer بيخدمها).
 * الصفحة على الموبايل فيها زرار «افتح في Subtitler» → subtitler://ytlink?h=IP&p=PORT&pin=XXXXXX.
 * الموبايل: لو مسجّل دخول يبعت الكوكيز بـ POST /ytlink (PIN مرة واحدة وصالح 10 دقايق)، ولو لأ يفتح تسجيل الدخول الأول.
 */
object YtLink {
    private const val VALID_MS = 10 * 60 * 1000L
    @Volatile private var pin = ""
    @Volatile private var pinAt = 0L
    @Volatile private var onReceived: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private fun newPin(): String { val p = (100000 + java.security.SecureRandom().nextInt(900000)).toString(); pin = p; pinAt = System.currentTimeMillis(); return p }

    /** من TypeServer: طلب POST /ytlink (body: pin=…&c=كوكيز). بيرجّع true لو اتقبل */
    fun receive(body: String): Boolean {
        fun f(k: String): String = try { java.net.URLDecoder.decode(body.split("&").firstOrNull { it.startsWith("$k=") }?.substringAfter("=") ?: "", "UTF-8") } catch (_: Exception) { "" }
        val p = f("pin"); val c = f("c")
        if (pin.isEmpty() || p != pin || System.currentTimeMillis() - pinAt > VALID_MS) return false
        val ok = YtAccount.importCookie(c)
        if (ok) { pin = ""; main.post { onReceived?.invoke() } }
        return ok
    }

    /** صفحة الموبايل اللي الـQR بيفتحها */
    fun page(pinQ: String): String {
        val ip = TypeServer.ip()
        val deep = "subtitler://ytlink?h=$ip&p=${TypeServer.PORT}&pin=$pinQ"
        val intentUri = "intent://ytlink?h=$ip&p=${TypeServer.PORT}&pin=$pinQ#Intent;scheme=subtitler;package=com.tttt.joyboy;end"
        return """<!doctype html><html dir="rtl" lang="ar"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Subtitler — دخول يوتيوب</title><body style="font-family:sans-serif;background:#171512;color:#f3ead8;padding:22px;line-height:1.8"><h3>📺 تسجيل دخول يوتيوب على التلفزيون</h3><p>بيفتح Subtitler لوحده… لو ما اتفتحش دوس الزرار.</p><script>setTimeout(function(){location.href="$intentUri"},250)</script><a href="$intentUri" style="display:block;text-align:center;background:#ffb340;color:#000;padding:14px;border-radius:12px;font-size:19px;text-decoration:none;margin:14px 0">📱 افتح في Subtitler</a><p style="font-size:13px;opacity:.8">لو ما اتفتحش: <a style="color:#ffb340" href="$deep">جرّب الرابط ده</a></p></body></html>"""
    }

    // ───────── التلفزيون ─────────
    fun showTvQr(act: Activity, ui: Ui, th: Theme, onDone: (Boolean) -> Unit) {
        TypeServer.start()
        val p = newPin()
        val base = TypeServer.url()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        if (base.isEmpty()) {
            col.addView(ui.text("⚠ التلفزيون مش متصل بواي فاي — وصّله بنفس شبكة الموبايل الأول.", 15f, th.text))
        } else {
            val qr = QrCode.bitmap("$base/ytlink?pin=$p", ui.dp(230))
            if (qr != null) col.addView(ImageView(act).apply { setImageBitmap(qr); setBackgroundColor(Color.WHITE); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }, LinearLayout.LayoutParams(ui.dp(230), ui.dp(230)).apply { bottomMargin = ui.dp(10) })
            col.addView(ui.text("1) امسح الكود بكاميرا الموبايل (نفس الواي فاي)\n2) Subtitler هيفتح على الموبايل لوحده\n3) هيتبعت لوحده لو إنت مسجّل دخول؛ لو لأ سجّل بجوجل على الموبايل", 14f, th.text))
            col.addView(ui.text("$base/ytlink?pin=$p", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        val status = ui.text("⏳ مستني الموبايل…", 13.5f, th.primary, true).apply { setPadding(0, ui.dp(8), 0, 0) }
        col.addView(status)
        var finished = false
        val dlg = GAlert(act).setTitle("📱 دخول يوتيوب بالـQR").setView(col).setNegativeButton("إلغاء", null).create()
        onReceived = {
            if (!finished) { finished = true; onReceived = null; try { dlg.dismiss() } catch (_: Throwable) { }; onDone(true) }
        }
        dlg.setOnDismissListener { if (!finished) { finished = true; onReceived = null; pin = ""; onDone(false) } }
        dlg.show()
    }

    // ───────── الموبايل ─────────
    /** (v598) نص QR اتمسح بكاميرا التطبيق: http://IP:PORT/ytlink?pin=… ← نفس مسار الدخول بالـdeep link. بيرجّع true لو كان QR يوتيوب */
    fun handleScanned(act: Activity, text: String): Boolean {
        val u = try { Uri.parse(text.trim()) } catch (_: Throwable) { return false }
        if (u.path?.startsWith("/ytlink") != true) return false
        val host = u.host ?: return false
        val pin = u.getQueryParameter("pin") ?: return false
        val port = if (u.port > 0) u.port else TypeServer.PORT
        val deep = Uri.parse("subtitler://ytlink?h=" + host + "&p=" + port + "&pin=" + Uri.encode(pin))
        maybeHandle(act, Intent().setData(deep))
        return true
    }

    /** ينادى من Main لو الـIntent فيه subtitler://ytlink */
    fun maybeHandle(act: Activity, i: Intent?) {
        val u: Uri = i?.data ?: return
        if (u.scheme != "subtitler" || u.host != "ytlink") return
        i.data = null
        val h = u.getQueryParameter("h") ?: return; val port = u.getQueryParameter("p") ?: return; val p = u.getQueryParameter("pin") ?: return
        val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(act, th)
        YtAccount.init(act)
        fun send() {
            Notice.show(act, "📤 بابعت الحساب للتلفزيون…", 1800L)
            Thread {
                val ok = try {
                    val body = "pin=" + URLEncoder.encode(p, "UTF-8") + "&c=" + URLEncoder.encode(YtAccount.exportCookie(), "UTF-8")
                    val c = URL("http://$h:$port/ytlink").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 6000; c.readTimeout = 8000
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    c.responseCode == 200
                } catch (e: Throwable) { LogStore.err("YtLink:send", e); false }
                act.runOnUiThread { if (!act.isFinishing) Notice.show(act, if (ok) "✅ التلفزيون اتسجّل دخوله" else "⚠ ما وصلش للتلفزيون — اتأكد إنهم على نفس الواي فاي وإن الكود لسه مفتوح على التلفزيون", 4000L) }
            }.start()
        }
        // (v489) من غير أسئلة: لو مسجّل دخول يوتيوب بيبعت على طول؛ لو لأ بيفتح تسجيل الدخول وبعد ما يخلص يبعت لوحده
        if (YtAccount.loggedIn()) send()
        else YtAccount.login(act, ui, th) { ok -> if (ok) act.runOnUiThread { send() } }
    }
}
