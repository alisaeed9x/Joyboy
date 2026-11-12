package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** (v730) سجل الكليبورد الدائم: كل نص بتنسخه (من أي تطبيق) بيتحفظ هنا جوه البرنامج لحد ما انت تمسحه، مش في كليبورد النظام. */
object ClipHistory {
    class Item(val text: String, val ts: Long, val pkg: String = "", val label: String = "", val url: String = "")
    /** نص اتنسخ من شاشة السجل نفسها: ما يتسجّلش تاني */
    @Volatile var ignoreText = ""
    @Volatile var ignoreUntil = 0L
    private const val FILE = "clip_history.json"
    private const val MAX = 5000
    private val lock = Any()
    private var cache: ArrayList<Item>? = null

    private fun load(ctx: Context): ArrayList<Item> {
        cache?.let { return it }
        val out = ArrayList<Item>()
        try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Item(o.optString("t"), o.optLong("ts"), o.optString("p"), o.optString("l"), o.optString("u"))) }
            }
        } catch (_: Throwable) {}
        cache = out
        return out
    }

    private fun save(ctx: Context, l: List<Item>) {
        try {
            val a = JSONArray()
            for (it in l) a.put(JSONObject().put("t", it.text).put("ts", it.ts).put("p", it.pkg).put("l", it.label).put("u", it.url))
            val f = File(ctx.applicationContext.filesDir, FILE)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(a.toString()); tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    /** كل نسخة بتتحفظ لوحدها (بتاريخها ووقتها ومصدرها)؛ بس نفس النص خلال 3 ثواني = نفس النسخة (أندرويد بيبلّغ مرتين أحيانًا) */
    fun add(ctx: Context, text: String, pkg: String = "", label: String = "", url: String = "") {
        val t = text.trim()
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        if (t == ignoreText && now < ignoreUntil) return
        synchronized(lock) {
            val l = load(ctx)
            val top = l.firstOrNull()
            if (top != null && top.text == t && now - top.ts < 3000L) return
            l.add(0, Item(t, now, pkg, label, url))
            while (l.size > MAX) l.removeAt(l.size - 1)
            save(ctx, l)
        }
    }

    /** يفتح المصدر: صفحة المتصفح نفسها لو معروفة، وإلا التطبيق اللي اتنسخ منه */
    fun openSource(act: Activity, it: Item) {
        try {
            if (it.url.isNotBlank()) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(it.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (it.pkg.isNotBlank()) i.setPackage(it.pkg)
                try { act.startActivity(i); return } catch (_: Throwable) { i.setPackage(null); act.startActivity(i); return }
            }
            val li = if (it.pkg.isNotBlank()) act.packageManager.getLaunchIntentForPackage(it.pkg) else null
            if (li != null) act.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else Notice.show(act, "المصدر مش متاح", 1500L)
        } catch (_: Throwable) { Notice.show(act, "ما قدرتش أفتح المصدر", 1500L) }
    }

    fun list(ctx: Context): List<Item> = synchronized(lock) { ArrayList(load(ctx)) }
    fun remove(ctx: Context, it: Item) { synchronized(lock) { val l = load(ctx); l.removeAll { x -> x.text == it.text && x.ts == it.ts }; save(ctx, l) } }
    fun clear(ctx: Context) { synchronized(lock) { val l = load(ctx); l.clear(); save(ctx, l) } }

    /** شاشة السجل: بحث · لمسة = انسخ تاني · ضغطة طويلة = احذف العنصر */
    fun show(act: Activity, ui: Ui, th: Theme) {
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(24)) }
        val fmt = SimpleDateFormat("d/M/yyyy  hh:mm:ss a", Locale.US)
        var q = ""
        lateinit var render: () -> Unit
        render = {
            body.removeAllViews()
            val all = list(act)
            val items = if (q.isBlank()) all else all.filter { it.text.contains(q, true) }
            body.addView(ui.text("${items.size} عنصر" + (if (q.isNotBlank()) " (من ${all.size})" else ""), 12f, th.muted))
            if (items.isEmpty()) body.addView(ui.text("فاضي", 13f, th.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
            for (it in items.take(300)) {
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    background = GradientDrawable().apply { setColor(th.card); cornerRadius = ui.dp(10).toFloat() }
                }
                row.addView(ui.text(it.text, 14f, th.text, true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
                row.addView(ui.text("🕒 " + fmt.format(Date(it.ts)) + (if (it.label.isNotBlank()) "   ·   📱 من: " + it.label else ""), 11f, th.muted))
                if (it.url.isNotBlank()) row.addView(ui.text("🔗 " + it.url, 11f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val x = it
                if (it.pkg.isNotBlank() || it.url.isNotBlank()) row.addView(ui.text("↗ افتح المصدر" + (if (it.label.isNotBlank()) " (" + it.label + ")" else ""), 12f, th.primary, true).apply {
                    setPadding(0, ui.dp(6), 0, ui.dp(2)); setOnClickListener { dlg.dismiss(); openSource(act, x) }
                })
                row.setOnClickListener {
                    try {
                        ClipWatch.markSeen(act, x.text); ignoreText = x.text.trim(); ignoreUntil = System.currentTimeMillis() + 4000L   // ما تظهرش دايرة اللينك ومايتسجّلش تاني
                        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("clip", x.text))
                        Notice.show(act, "اتنسخ ✅", 1200L)
                    } catch (_: Throwable) {}
                }
                row.setOnLongClickListener { remove(act, x); render(); true }
                body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(5) })
            }
            if (items.size > 300) body.addView(ui.text("بيظهر أحدث 300 نتيجة — استخدم البحث للباقي", 11f, th.muted).apply { setPadding(0, ui.dp(10), 0, 0) })
            else if (items.isNotEmpty()) body.addView(ui.noHint().apply { setPadding(0, ui.dp(12), 0, 0) })
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(14), ui.dp(8), ui.dp(6)) }
        head.addView(ui.text("📋 سجل الكليبورد", 19f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.text("🗑 مسح الكل", 12f, th.muted, true).apply {
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            setOnClickListener { GAlert(act).setTitle("🗑 مسح السجل").setMessage("هيتمسح سجل الكليبورد كله.").setPositiveButton("مسح") { _, _ -> clear(act); render() }.setNegativeButton("إلغاء", null).show() }
        })
        head.addView(ui.text("✕", 18f, th.text, true).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setOnClickListener { dlg.dismiss() } })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        val search = EditText(act).apply {
            hint = "🔍 بحث في السجل"; setTextColor(th.text); setHintTextColor(th.muted); textSize = 14f; isSingleLine = true
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            background = GradientDrawable().apply { setColor(th.surface); cornerRadius = ui.dp(16).toFloat() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { q = s?.toString() ?: ""; render() }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(4)) })
        root.addView(ScrollView(act).apply { addView(body, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(root)
        render()
        TvFocus.attachDialog(act, dlg, th.primary)
        dlg.show()
    }
}
