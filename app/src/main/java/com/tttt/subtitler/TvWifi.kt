package com.tttt.subtitler

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

/**
 * (v525) واي فاي سريع على الرئيسية: الحالة · تشغيل/إيقاف · بحث عن الشبكات · اتصال/فصل.
 * ملحوظة مهمة: من أندرويد 10 والتطبيقات العادية ممنوعة تشغّل/تقفل الواي فاي أو تفصل شبكة بنفسها،
 * فكل زرار بيجرّب الطريقة المباشرة الأول، ولو النظام رفض بيفتح شاشة الواي فاي بتاعة التلفزيون على طول.
 */
object TvWifi {
    private val h = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null
    const val RC_LOC = 93

    private fun wm(a: Activity): WifiManager = a.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private fun hasLoc(a: Activity): Boolean = try {
        a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun ipStr(i: Int): String = (i and 0xff).toString() + "." + ((i shr 8) and 0xff) + "." + ((i shr 16) and 0xff) + "." + ((i shr 24) and 0xff)

    /** (اسم الشبكة المتصل بيها، عنوان الـIP) أو null لو مش متصل */
    @Suppress("DEPRECATION")
    private fun current(a: Activity): Pair<String, String>? = try {
        val w = wm(a)
        val info = w.connectionInfo
        if (!w.isWifiEnabled || info == null || info.networkId == -1) null
        else {
            var s = (info.ssid ?: "").trim('"')
            if (s.isBlank() || s == "<unknown ssid>") s = "شبكة متصلة"
            s to ipStr(info.ipAddress)
        }
    } catch (_: Throwable) { null }

    private fun isOpen(cap: String): Boolean = !(cap.contains("WPA") || cap.contains("WEP") || cap.contains("SAE") || cap.contains("PSK") || cap.contains("EAP"))

    @Suppress("DEPRECATION")
    private fun bars(level: Int): String {
        val l = try { WifiManager.calculateSignalLevel(level, 5) } catch (_: Throwable) { 2 }
        return "▂▃▄▅▆".take((l + 1).coerceIn(1, 5))
    }

    @Volatile private var busy = false

    /**
     * (v536) الزرار الأساسي: دوسة واحدة = تشغيل/إيقاف الواي فاي على طول، من غير حوار ولا شاشة نظام ظاهرة.
     *  1) WifiManager مباشرة (بيشتغل على أندرويد 9 وأقدم).
     *  2) أندرويد 10+: بإمكانية الوصول (لازم تكون متفعّلة من «🔐 الأذونات») — بتضغط مفتاح الواي فاي في شاشة النظام وترجع للانشر.
     *  3) من غير إمكانية الوصول: بيفتح شاشة الواي فاي ويقولك تفعّلها.
     * قايمة الشبكات/الاتصال بشبكة جديدة اتنقلت لدوسة طويلة على الزرار (show).
     */
    @Suppress("DEPRECATION")
    fun quick(a: Activity) {
        if (busy) return
        val w = wm(a)
        val on = try { w.isWifiEnabled } catch (_: Throwable) { false }
        val want = !on
        val ok = try { w.setWifiEnabled(want) } catch (_: Throwable) { false }
        if (ok) { Notice.show(a, if (want) "📶 الواي فاي اشتغل" else "📴 الواي فاي اتقفل", 2200L); return }
        val svc = TvAccess.svc
        if (svc == null) {
            Notice.show(a, "عشان الزرار يشتغل بدوسة واحدة فعّل «إمكانية الوصول» من 🔐 الأذونات", 4500L)
            TvSetup.open(a)
            return
        }
        busy = true
        Notice.show(a, if (want) "📶 بشغّل الواي فاي…" else "📴 بقفل الواي فاي…", 2500L)
        try { a.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Throwable) { busy = false; openSettings(a, "ما قدرتش أفتح شاشة الواي فاي"); return }
        val back = Intent(a, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        svc.wifiToggle(want, back) { good ->
            busy = false
            h.postDelayed({
                if (!a.isDestroyed && !a.isFinishing) Notice.show(a, if (good) (if (want) "📶 الواي فاي اشتغل" else "📴 الواي فاي اتقفل") else "⚠ ما لقيتش مفتاح الواي فاي في شاشة النظام — دوسة طويلة على الزرار تفتحلك الإعدادات", 3500L)
            }, 900L)
        }
    }

    @Suppress("DEPRECATION")
    fun show(a: Activity) {
        try { dlg?.dismiss() } catch (_: Throwable) {}
        try {
            val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(a, th)
            val col = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8))
            }
            val w = wm(a)
            val on = try { w.isWifiEnabled } catch (_: Throwable) { false }
            val cur = if (on) current(a) else null
            col.addView(ui.text(
                if (!on) "📴 الواي فاي مقفول" else if (cur != null) "✅ متصل بـ «" + cur.first + "»   ·   " + cur.second else "📶 الواي فاي شغّال — مش متصل بشبكة",
                15f, if (on) th.text else th.muted, true).apply { setPadding(0, 0, 0, ui.dp(6)) })
            col.addView(ui.button(if (on) "⏻ إيقاف الواي فاي" else "⏻ تشغيل الواي فاي") { close(); toggle(a, !on) })
            if (on && cur != null) col.addView(ui.button("⛔ فصل «" + cur.first + "»") { close(); disconnect(a) })
            if (on) {
                col.addView(ui.button("🔄 بحث عن شبكات") { scan(a) })
                if (!hasLoc(a)) {
                    col.addView(ui.text("عشان تظهر قايمة الشبكات أندرويد محتاج إذن «الموقع» (مش بنستخدمه غير للبحث عن الواي فاي).", 13f, th.muted).apply { setPadding(0, ui.dp(6), 0, ui.dp(4)) })
                    col.addView(ui.button("📍 اسمح بالموقع", true) {
                        close()
                        try { a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), RC_LOC) } catch (e: Throwable) { LogStore.err("TvWifi:perm", e) }
                    })
                } else {
                    val list: List<ScanResult> = try {
                        w.scanResults.filter { !it.SSID.isNullOrBlank() }.groupBy { it.SSID }.map { g -> g.value.maxByOrNull { it.level }!! }.sortedByDescending { it.level }
                    } catch (_: Throwable) { emptyList() }
                    col.addView(ui.text(if (list.isEmpty()) "مفيش شبكات ظاهرة — دوس «بحث» أو اتأكد إن «الموقع» شغّال في إعدادات التلفزيون" else "الشبكات (دوس على شبكة):", 14f, th.text, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
                    for (r in list.take(25)) {
                        val isCur = cur != null && cur.first == r.SSID
                        val lock = if (isOpen(r.capabilities ?: "")) "" else " 🔒"
                        col.addView(ui.button((if (isCur) "✅ " else "") + r.SSID + "   " + bars(r.level) + lock) { close(); if (isCur) disconnect(a) else connect(a, r) })
                    }
                }
            }
            col.addView(ui.button("⚙ إعدادات الواي فاي بتاعة النظام") { close(); openSettings(a, "") })
            dlg = GAlert(a).setTitle("📶 الواي فاي").setView(col).setNegativeButton("إغلاق", null).create().also { it.show() }
        } catch (e: Throwable) { LogStore.err("TvWifi:show", e); openSettings(a, "") }
    }

    private fun close() { try { dlg?.dismiss() } catch (_: Throwable) {}; dlg = null }

    @Suppress("DEPRECATION")
    private fun toggle(a: Activity, want: Boolean) {
        val ok = try { wm(a).setWifiEnabled(want) } catch (_: Throwable) { false }
        if (ok) {
            Notice.show(a, if (want) "📶 بشغّل الواي فاي…" else "📴 اتقفل الواي فاي", 2200L)
            h.postDelayed({ if (!a.isDestroyed && !a.isFinishing) show(a) }, 1800L)
        } else openSettings(a, "أندرويد مبيسمحش للتطبيقات تشغّل/تقفل الواي فاي — فتحتلك شاشة الواي فاي")
    }

    @Suppress("DEPRECATION")
    private fun disconnect(a: Activity) {
        val ok = try { wm(a).disconnect() } catch (_: Throwable) { false }
        if (ok) {
            Notice.show(a, "⛔ اتفصلت الشبكة", 2200L)
            h.postDelayed({ if (!a.isDestroyed && !a.isFinishing) show(a) }, 1500L)
        } else openSettings(a, "أندرويد مبيسمحش للتطبيقات تفصل الشبكة — فتحتلك شاشة الواي فاي")
    }

    @Suppress("DEPRECATION")
    private fun scan(a: Activity) {
        val ok = try { wm(a).startScan() } catch (_: Throwable) { false }
        Notice.show(a, if (ok) "🔄 بدوّر على الشبكات…" else "🔄 النظام بيحدّ مرات البحث — هعرضلك آخر نتيجة", 2200L)
        close()
        h.postDelayed({ if (!a.isDestroyed && !a.isFinishing) show(a) }, 2500L)
    }

    private fun connect(a: Activity, r: ScanResult) {
        val ssid = r.SSID ?: return
        val cap = r.capabilities ?: ""
        if (isOpen(cap)) { doConnect(a, ssid, "", cap); return }
        val et = EditText(a).apply {
            hint = "باسورد «$ssid»"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            val th = Themes.byId(Cfg.str("theme", "mx"))
            setTextColor(th.text); setHintTextColor(th.muted)
        }
        GAlert(a).setTitle("🔒 اتصل بـ «$ssid»").setView(et)
            .setPositiveButton("اتصل") { _, _ -> doConnect(a, ssid, et.text.toString(), cap) }
            .setNegativeButton("إلغاء", null).show()
    }

    @Suppress("DEPRECATION")
    private fun doConnect(a: Activity, ssid: String, pw: String, cap: String) {
        try {
            if (Build.VERSION.SDK_INT < 29) {
                val w = wm(a)
                val c = WifiConfiguration().apply {
                    SSID = "\"" + ssid + "\""
                    if (pw.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) else preSharedKey = "\"" + pw + "\""
                }
                val id = w.addNetwork(c)
                if (id != -1 && w.enableNetwork(id, true)) {
                    w.reconnect()
                    Notice.show(a, "📶 بتصل بـ $ssid…", 2500L)
                    h.postDelayed({ if (!a.isDestroyed && !a.isFinishing) show(a) }, 4000L)
                    return
                }
            } else if (Build.VERSION.SDK_INT >= 30) {
                // أندرويد 11+: شاشة النظام «احفظ الشبكة؟» — بتحفظها وبتتصل بيها (من غير ما التطبيق يلمس الواي فاي بنفسه)
                val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
                if (pw.isNotEmpty()) { if (cap.contains("SAE") && !cap.contains("PSK")) b.setWpa3Passphrase(pw) else b.setWpa2Passphrase(pw) }
                val list = arrayListOf(b.build())
                a.startActivityForResult(Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, list), 94)
                return
            }
        } catch (e: Throwable) { LogStore.err("TvWifi:connect", e) }
        openSettings(a, "الاتصال بشبكة جديدة بيتم من شاشة الواي فاي بتاعة النظام — فتحتهالك")
    }

    fun openSettings(a: Activity, why: String) {
        if (why.isNotBlank()) Notice.show(a, why, 3500L)
        val tries = listOf(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) { try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) {} }
        Notice.show(a, "⚠ ما قدرتش أفتح إعدادات الواي فاي", 3000L)
    }
}
