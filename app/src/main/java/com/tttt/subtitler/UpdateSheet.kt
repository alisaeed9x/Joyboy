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
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(col) }
        var apkUrl = ""
        var page = "https://github.com/alisaeed9x/Joyboy/releases/latest"
        val g = GAlert(a).setView(sv)
        g.setNegativeButton("لاحقًا", null)
        g.setPositiveButton("تحميل") { _, _ ->
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (apkUrl.isNotBlank()) apkUrl else page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
            val clean = body.replace(Regex("<[^>]+>"), "").replace(Regex("(?m)^#+\\s*"), "").replace("`", "").take(4000)
            a.runOnUiThread {
                try {
                    if (tName.isNotBlank()) title.text = "⬆ $tName"
                    notes.text = clean.ifBlank { "مفيش ملاحظات للنسخة دي." }
                } catch (_: Throwable) {}
            }
        }.start()
    }
}
