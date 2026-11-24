package com.tttt.subtitler

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** (v1272) سجل التغييرات بالتصفح: بيفتح على آخر إصدار، زرار يمين للأقدم وزرار شمال للأحدث. بيتجاهل ريليز البلجن. */
object Changelog {
    class Rel(val title: String, val text: String)

    fun isAppRelease(o: JSONObject): Boolean {
        if (o.optBoolean("draft")) return false
        val tag = o.optString("tag_name"); val nm = o.optString("name")
        if (tag.contains("plugin", true) || nm.contains("plugin", true) || nm.contains("aniyomi", true)) return false
        return UpdateSheet.verParts(nm).size >= 2
    }

    /** أحدث ريليز تطبيق (من القايمة، من غير ريليز البلجن) */
    fun pickLatest(text: String): JSONObject {
        val arr = JSONArray(text)
        var best: JSONObject? = null
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (isAppRelease(o) && !o.optBoolean("prerelease")) { if (best == null || UpdateSheet.isNewer(UpdateSheet.verParts(o.optString("name")), UpdateSheet.verParts(best.optString("name")))) best = o } }
        return best ?: throw java.io.IOException("no app release")
    }

    fun fmt(body: String): String {
        var t = body
        val cut = t.indexOf("## 🗂"); if (cut >= 0) t = t.substring(0, cut)
        val bk = t.indexOf("🔙"); if (bk >= 0) t = t.substring(0, bk)
        t = t.replace(Regex("<[^>]+>"), "").replace("`", "")
        val order = ArrayList<String>(); val items = HashMap<String, ArrayList<String>>()
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
            val lst = items[sec]!!
            if (m == null && lst.isNotEmpty()) lst[lst.size - 1] = lst.last() + " " + txt else lst.add(txt)
        }
        val sb = StringBuilder()
        for (k in order) { sb.append("▌ ").append(k).append('\n'); var n = 0; for (x in items[k]!!) { n++; sb.append(n).append(". ").append(x).append('\n') }; sb.append('\n') }
        return sb.toString().trim()
    }

    fun show(a: Activity) {
        val th = Themes.current(); val ui = Ui(a, th)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
        val nav = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun chip(t: String) = ui.text(t, 13f, if (th.isLight) android.graphics.Color.WHITE else android.graphics.Color.BLACK, true).apply {
            background = ui.box(th.primary, th.primary, 14); setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); gravity = Gravity.CENTER
        }
        val older = chip("أقدم ▶"); val newer = chip("◀ أحدث")
        val head = ui.text("⏳ بنجيب السجل...", 15f, th.text, true).apply { gravity = Gravity.CENTER }
        nav.addView(older, LinearLayout.LayoutParams(-2, -2))
        nav.addView(head, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(newer, LinearLayout.LayoutParams(-2, -2))
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(nav, LinearLayout.LayoutParams(-1, -2))
        col.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val rels = ArrayList<Rel>()
        var idx = 0
        fun paint() {
            if (rels.isEmpty()) return
            val r = rels[idx]
            head.text = r.title + "  (" + (idx + 1) + "/" + rels.size + ")"
            older.alpha = if (idx < rels.size - 1) 1f else 0.3f
            newer.alpha = if (idx > 0) 1f else 0.3f
            box.removeAllViews()
            box.addView(Notes.render(a, ui, th, r.text.ifBlank { "مفيش ملاحظات للنسخة دي." }))
        }
        older.setOnClickListener { if (idx < rels.size - 1) { idx++; paint() } }
        newer.setOnClickListener { if (idx > 0) { idx--; paint() } }
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(col) }
        GAlert(a).setTitle("📜 سجل التغييرات").setView(sv).setNegativeButton("إغلاق", null).show()
        Thread {
            try {
                val c = URL("https://api.github.com/repos/alisaeed9x/Joyboy/releases?per_page=30").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 12000
                c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "Joyboy")
                val arr = JSONArray(c.inputStream.bufferedReader().readText())
                val list = ArrayList<JSONObject>()
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (isAppRelease(o)) list.add(o) }
                list.sortWith { x, y -> if (UpdateSheet.isNewer(UpdateSheet.verParts(x.optString("name")), UpdateSheet.verParts(y.optString("name")))) -1 else 1 }
                for (o in list) rels.add(Rel(Regex("v\\d+(?:\\.\\d+)+").find(o.optString("name"))?.value ?: o.optString("name"), fmt(o.optString("body"))))
            } catch (e: Throwable) { LogStore.err("Changelog:load", e) }
            a.runOnUiThread { try { if (rels.isEmpty()) head.text = "ما قدرتش أجيب السجل" else paint() } catch (_: Throwable) {} }
        }.apply { isDaemon = true; start() }
    }
}
