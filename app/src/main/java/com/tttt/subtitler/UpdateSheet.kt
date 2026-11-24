package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v1220) شيت التحديث (الشكل الجديد): من أسفل الشاشة، بيعرض إيه اللي اتغيّر في آخر نسخة (من ملاحظات الـRelease)،
 * وفيه «لاحقًا» و«تحميل». «تحميل» بيفتح لينك الـAPK في المتصفح مباشرة (مش المنزّل بتاع البرنامج).
 */
object UpdateSheet {
    private const val API = "https://api.github.com/repos/alisaeed9x/Joyboy/releases/latest"

    fun show(a: Activity) {
        val th = Themes.current()
        val ui = Ui(a, th)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        val title = ui.text("⬆ آخر نسخة", 17f, th.text, true)
        val notes = ui.text("بنجيب التغييرات...", 13f, th.text)
        col.addView(title)
        col.addView(notes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val sv: View = col   // (v1247) GAlert بيلف المحتوى في سكرول بحد أقصى ٦٠٪ من الشاشة، والأزرار ثابتة تحته
        var apkUrl = ""
        var upToDateFlag = false
        var page = "https://github.com/alisaeed9x/Joyboy/releases/latest"
        val g = GAlert(a).setView(sv)
        g.setNegativeButton("لاحقًا", null)
        g.setPositiveButton("تحميل") { _, _ ->
            if (!upToDateFlag) try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (apkUrl.isNotBlank()) apkUrl else page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Throwable) { LogStore.err("UpdateSheet:open", e) }
        }
        val d: Dialog = g.show()
        try { d.window?.setGravity(Gravity.BOTTOM) } catch (_: Throwable) {}
        Thread {
            var tName = ""; var body = ""
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                tName = j.optString("name"); body = j.optString("body")
                page = j.optString("html_url", page)
                val assets = j.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    if (o.optString("name").endsWith(".apk")) { apkUrl = o.optString("browser_download_url"); break }
                }
            } catch (e: Throwable) { body = "ما قدرتش أجيب التغييرات. دوس «تحميل» وهيفتح صفحة آخر نسخة في المتصفح." }
            val verNow = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
            val newer = tName.isNotBlank() && verNow.isNotBlank() && isNewer(verParts(tName), verParts(verNow))
            val cleanTxt = (if (newer) cumulative(verNow) else cumulative("0", true)).ifBlank { clean(body) }
            val ver = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
            val upToDate = tName.isNotBlank() && ver.isNotBlank() && !isNewer(verParts(tName), verParts(ver))
            a.runOnUiThread {
                try {
                    if (upToDate) {
                        title.text = "✅ أنت على آخر تحديث"
                        try { (d as? android.app.AlertDialog)?.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.text = "تمام" } catch (_: Throwable) {}
                        upToDateFlag = true
                    } else if (tName.isNotBlank()) title.text = "⬆ $tName"
                    notes.text = cleanTxt.ifBlank { "مفيش ملاحظات للنسخة دي." }
                } catch (_: Throwable) {}
            }
        }.start()
    }


    @Volatile private var ucChecked = false
    private fun verParts(s: String): List<Int> = Regex("(\\d+(?:\\.\\d+)*)").find(s)?.value?.split(".")?.map { it.toIntOrNull() ?: 0 } ?: emptyList()
    private fun isNewer(remote: List<Int>, local: List<Int>): Boolean {
        for (i in 0 until maxOf(remote.size, local.size)) { val r = remote.getOrElse(i) { 0 }; val l = local.getOrElse(i) { 0 }; if (r != l) return r > l }
        return false
    }

    /** (v1240) فحص التحديث أول ما البرنامج يفتح: لو فيه نسخة أحدث بيظهر شعار تحديث + اللي اتغيّر (من ملاحظات الـRelease) من غير ما تحمّل حاجة، وتختار «تحديث» (يفتح المتصفح) أو «مش دلوقتي» */
    fun checkOnLaunch(a: Activity) {
        if (ucChecked) return
        ucChecked = true
        val ver = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
        if (ver.isBlank()) return
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 12000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val name = j.optString("name"); val tag = j.optString("tag_name"); val body = j.optString("body")
                val remote = verParts(name)
                if (remote.isEmpty() || !isNewer(remote, verParts(ver))) return@Thread
                if (Cfg.str("upd_skip") == tag) return@Thread
                val page = j.optString("html_url", "https://github.com/alisaeed9x/Joyboy/releases/latest")
                var apk = ""
                val assets = j.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) { val o = assets.getJSONObject(i); if (o.optString("name").endsWith(".apk")) { apk = o.optString("browser_download_url"); break } }
                val txt = cumulative(ver).ifBlank { clean(body) }.ifBlank { "مفيش ملاحظات للنسخة دي." }
                a.runOnUiThread {
                    try {
                        if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                        val th = Themes.current()
                        val ui = Ui(a, th)
                        val col = LinearLayout(a).apply {
                            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                        }
                        col.addView(ui.text("🔔 فيه تحديث جديد", 19f, th.primary, true))
                        col.addView(ui.text(name + "  (عندك v" + ver + ")", 13f, th.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
                        col.addView(ui.text("إيه اللي اتغيّر:", 15f, th.text, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
                        col.addView(ui.text(txt, 15f, th.text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
                        val sv: View = col   // (v1247) GAlert بيلف المحتوى في سكرول بحد أقصى ٦٠٪ من الشاشة، والأزرار ثابتة تحته
                        val g = GAlert(a).setView(sv)
                        g.setNegativeButton("مش دلوقتي") { _, _ -> Cfg.put("upd_skip", tag) }
                        g.setPositiveButton("تحديث") { _, _ ->
                            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (apk.isNotBlank()) apk else page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (e: Throwable) { LogStore.err("UpdateSheet:launchOpen", e) }
                        }
                        val d: Dialog = g.show()
                        try { d.window?.setGravity(Gravity.CENTER) } catch (_: Throwable) {}
                    } catch (e: Throwable) { LogStore.err("UpdateSheet:launch-ui", e) }
                }
            } catch (e: Throwable) { LogStore.err("UpdateSheet:launch", e) }
        }.start()
    }

    @Volatile private var wnChecked = false
    /** (v1245) ملاحظات التحديث مجمّعة بالقسم (الميديا / الإعدادات / ...): كل نقطة عليها رقم النسخة اللي نزلت فيها، من الأحدث للأقدم لحد النسخة المثبّتة. latestOnly = آخر نسخة بس */
    private fun cumulative(ver: String, latestOnly: Boolean = false): String {
        return try {
            val c = URL("https://api.github.com/repos/alisaeed9x/Joyboy/releases?per_page=30").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val arr = org.json.JSONArray(c.inputStream.bufferedReader().readText())
            val local = verParts(ver)
            val order = ArrayList<String>(); val items = HashMap<String, ArrayList<String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("draft")) continue
                val nm = o.optString("name"); val rv = verParts(nm)
                if (rv.isEmpty() || !isNewer(rv, local)) continue
                val tag = Regex("v\\d+\\.\\d+").find(nm)?.value ?: nm
                var t = o.optString("body")
                val cut = t.indexOf("## 🗂"); if (cut >= 0) t = t.substring(0, cut)
                val bk = t.indexOf("🔙"); if (bk >= 0) t = t.substring(0, bk)
                t = t.replace(Regex("<[^>]+>"), "").replace("`", "")
                var sec = "عام"
                for (ln in t.lines()) {
                    val s = ln.trim()
                    if (s.isEmpty() || s.startsWith("⚠") || Regex("^-{3,}$").matches(s)) continue
                    val hm = Regex("^#{2,}\\s*(.+)$").find(s)
                    if (hm != null) { sec = hm.groupValues[1].trim(); continue }
                    if (s.startsWith("#")) continue
                    val m = Regex("^(?:[-*•]|\\d+[.)])\\s+(.*)$").find(s)
                    val txt = m?.groupValues?.get(1) ?: s
                    if (!items.containsKey(sec)) { items[sec] = ArrayList(); order.add(sec) }
                    items[sec]!!.add(txt + "  (" + tag + ")")
                }
                if (latestOnly) break
            }
            val sb = StringBuilder()
            for (sec in order) {
                sb.append("▌ ").append(sec).append('\n')
                var n = 0
                for (it in items[sec]!!) { n++; sb.append(n).append(". ").append(it).append('\n') }
                sb.append('\n')
            }
            sb.toString().trim()
        } catch (e: Throwable) { LogStore.err("UpdateSheet:cum", e); "" }
    }

    /** (v1245) ملاحظات التحديث: بس اللي جديد في النسخة. بنقطع قايمة الملفات الأوتوماتيكة والنسخة الاحتياطية وخطوط الفصل اللي CI بيضيفها */
    private fun clean(b: String): String {
        var t = b
        val cut = t.indexOf("## 🗂")
        if (cut >= 0) t = t.substring(0, cut)
        val bk = t.indexOf("🔙")
        if (bk >= 0) t = t.substring(0, bk)
        return t.replace(Regex("<[^>]+>"), "").replace(Regex("(?m)^#+\\s*"), "").replace(Regex("(?m)^-{3,}\\s*$"), "").replace("`", "").replace(Regex("\\n{3,}"), "\n\n").trim().take(4000)
    }

    /** (v1221) أول فتح بعد التحديث: بيعرض «إيه الجديد» في النسخة اللي اتنصّبت (من ملاحظات الـRelease)، ويتغيّر لوحده مع كل نسخة. */
    fun whatsNew(a: Activity) {
        if (wnChecked) return
        wnChecked = true
        val ver = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
        if (ver.isBlank() || Cfg.str("wn_seen") == ver) return
        Thread {
            try {
                val c = URL("https://api.github.com/repos/alisaeed9x/Joyboy/releases?per_page=15").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val arr = org.json.JSONArray(c.inputStream.bufferedReader().readText())
                var name = ""; var body = ""
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("name").contains("v$ver")) { name = o.optString("name"); body = o.optString("body"); break }
                }
                Cfg.put("wn_seen", ver)
                if (body.isBlank()) return@Thread
                val txt = clean(body)
                a.runOnUiThread {
                    try {
                        if (a.isFinishing) return@runOnUiThread
                        val th = Themes.current()
                        val ui = Ui(a, th)
                        val col = LinearLayout(a).apply {
                            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                        }
                        col.addView(ui.text("✨ إيه الجديد في $name", 17f, th.text, true))
                        col.addView(ui.text(txt, 13f, th.text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
                        val sv: View = col   // (v1247) GAlert بيلف المحتوى في سكرول بحد أقصى ٦٠٪ من الشاشة، والأزرار ثابتة تحته
                        GAlert(a).setView(sv).setPositiveButton("تمام", null).show()
                    } catch (e: Throwable) { LogStore.err("UpdateSheet:wn-ui", e) }
                }
            } catch (e: Throwable) { LogStore.err("UpdateSheet:wn", e) }
        }.start()
    }
}
