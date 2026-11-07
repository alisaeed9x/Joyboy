package com.tttt.subtitler

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView

/**
 * (v814) هوت سبوت للابتوب — ضغطة طويلة على 📶 في الريموت:
 *  • «فتح إعدادات الهوت سبوت»: هوت سبوت الموبايل العادي (أندرويد ما بيسمحش للتطبيقات تشغّله بنفسها). اللابتوب بيتوصل بيه تلقائي لو حفظت بياناته.
 *  • «هوت سبوت محلي سريع»: بيشغّل هوت سبوت محلي من التطبيق (من غير إنترنت) ويعرض الاسم والباسورد + نسخ بصيغة WIFI: للصقها في Joyboy Desk.
 */
object PhoneHotspot {
    private var res: WifiManager.LocalOnlyHotspotReservation? = null
    private val main = Handler(Looper.getMainLooper())
    private const val RC = 94

    fun show(act: Activity) {
        val b = AlertDialog.Builder(act).setTitle("📶 هوت سبوت للابتوب")
            .setMessage("لو الموبايل واللابتوب مش على نفس الواي فاي.")
            .setPositiveButton("فتح إعدادات الهوت سبوت") { _, _ -> openTether(act) }
            .setNeutralButton(if (res == null) "هوت سبوت محلي سريع" else "إيقاف الهوت سبوت المحلي") { _, _ -> if (res == null) startLocal(act) else stopLocal(act) }
            .setNegativeButton("إغلاق", null)
        b.show()
    }

    private fun openTether(act: Activity) {
        try { act.startActivity(Intent("android.settings.TETHER_SETTINGS")) }
        catch (_: Throwable) { try { act.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (e: Throwable) { Notice.show(act, "⚠ ما قدرتش أفتح الإعدادات", 2500L) } }
    }

    private fun need(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun startLocal(act: Activity) {
        val miss = need().filter { act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) { act.requestPermissions(miss.toTypedArray(), RC); Notice.show(act, "ادّي الإذن وبعدين اضغط تاني", 3000L); return }
        val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    res = r
                    val ssid: String; val pass: String
                    if (Build.VERSION.SDK_INT >= 30) { val c = r.softApConfiguration; ssid = c.ssid ?: ""; pass = c.passphrase ?: "" }
                    else { @Suppress("DEPRECATION") val c = r.wifiConfiguration; ssid = (c?.SSID ?: "").trim('"'); pass = c?.preSharedKey?.trim('"') ?: "" }
                    val wifi = "WIFI:T:WPA;S:" + esc(ssid) + ";P:" + esc(pass) + ";;"
                    try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("wifi", wifi)) } catch (_: Throwable) {}
                    main.post {
                        val tv = TextView(act).apply { text = "الاسم: $ssid\nالباسورد: $pass\n\n(اتنسخت تلقائي — في Joyboy Desk: تاب هوت سبوت ← لصق ← حفظ وانضم)"; textSize = 16f; setPadding(48, 24, 48, 0); setTextIsSelectable(true) }
                        AlertDialog.Builder(act).setTitle("📶 الهوت سبوت المحلي شغّال").setView(tv).setPositiveButton("تمام", null)
                            .setNegativeButton("إيقاف") { _, _ -> stopLocal(act) }.show()
                    }
                }
                override fun onStopped() { res = null }
                override fun onFailed(reason: Int) { res = null; main.post { Notice.show(act, "⚠ الهوت سبوت المحلي فشل ($reason) — جرّب إعدادات الهوت سبوت العادية", 3500L) } }
            }, main)
        } catch (e: Throwable) { LogStore.err("PhoneHotspot", e); Notice.show(act, "⚠ الجهاز ما سمحش بالهوت سبوت المحلي", 3000L) }
    }

    private fun stopLocal(act: Activity) { try { res?.close() } catch (_: Throwable) {}; res = null; Notice.show(act, "اتقفل الهوت سبوت المحلي", 2000L) }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
}
