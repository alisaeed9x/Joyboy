package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v610) تصفح ملفات التلفزيون من الموبايل: الذاكرة الداخلية والفلاشات، فولدر فولدر.
 * أي ملف أو فولدر: ضغطة مطوّلة (أو ⋮) ← 📥 نزّل للموبايل (الفولدر بيتنزّل كله) · ☑ تحديد · ℹ تفاصيل.
 * الفيديوهات/الصوتيات/الصور بتتحفظ في فولدر نوعها (Joyboy)، والفولدر الكامل في Download/Joyboy/اسم-الفولدر.
 */
class TvFilesActivity : Activity() {
    private lateinit var th: Theme
    private lateinit var ui: Ui
    private val h = Handler(Looper.getMainLooper())

    private class Item(val name: String, val dir: Boolean, val size: Long, val mtime: Long, val path: String)

    private var path = ""
    private var parent = ""
    private var items: List<Item> = emptyList()
    private val sel = LinkedHashSet<String>()
    private var gen = 0
    private var msg = ""
    private var noAccess = false

    private lateinit var crumb: TextView
    private lateinit var info: TextView
    private lateinit var list: ListView
    private lateinit var bar: LinearLayout
    private val adapter = ItemAdapter()
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK
    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        try { RemoteClient.init(this) } catch (_: Throwable) {}
        try { DeviceUi.applyOrientation(this) } catch (_: Throwable) {}
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        try { window.setBackgroundDrawable(ColorDrawable(th.bg)); window.statusBarColor = th.bg; window.navigationBarColor = th.bg } catch (_: Throwable) {}
        build()
        load("")
    }

    override fun onDestroy() { gen++; h.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun lp(w: Int, hh: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, hh, wt)

    private fun pill(label: String, primary: Boolean, f: () -> Unit): TextView = IconTextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; isFocusable = true; isClickable = true; maxLines = 1
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        fun paint(foc: Boolean) {
            setTextColor(if (primary) onPrimary else th.text)
            background = ui.box(if (primary) th.primary else th.surface, if (foc) th.accent else if (primary) th.primary else th.border, 20, if (foc) 3 else 1)
        }
        paint(false)
        setOnFocusChangeListener { _, foc -> paint(foc) }
        setOnClickListener { f() }
    }

    private fun build() {
        val pad = ui.dp(12)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg); setPadding(pad, pad, pad, ui.dp(8)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tc.addView(ui.text("📺 ملفات التلفزيون", 20f, th.primary, true))
        crumb = ui.text("", 12f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
        tc.addView(crumb)
        head.addView(tc, lp(0, -2, 1f))
        head.addView(pill("↑", false) { up() }, lp(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        head.addView(pill("🔄", false) { load(path) }, lp(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        head.addView(pill("✕", false) { finish() })
        root.addView(head, lp(-1, -2))
        info = ui.text("", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(4)) }
        root.addView(info, lp(-1, -2))
        list = ListView(this).apply {
            adapter = this@TvFilesActivity.adapter
            divider = null; dividerHeight = ui.dp(2); isFocusable = true; cacheColorHint = Color.TRANSPARENT
            val hi = ui.box(th.surface, th.accent, 12, 2)
            selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), hi)
                addState(intArrayOf(android.R.attr.state_focused), hi)
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnItemClickListener { _, _, p, _ -> onClick(p) }
            setOnItemLongClickListener { _, _, p, _ -> menu(p); true }
        }
        root.addView(list, lp(-1, 0, 1f))
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE; setPadding(0, ui.dp(6), 0, 0) }
        root.addView(bar, lp(-1, -2))
        setContentView(root)
    }

    private fun renderBar() {
        bar.removeAllViews()
        if (sel.isEmpty()) { bar.visibility = View.GONE; return }
        bar.visibility = View.VISIBLE
        bar.addView(pill("📥 نزّل المحدد (" + sel.size + ")", true) { downloadItems(items.filter { it.path in sel }) }, lp(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        bar.addView(pill("☑ الكل", false) { toggleAll() }, lp(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        bar.addView(pill("✖", false) { sel.clear(); adapter.notifyDataSetChanged(); renderBar() }, lp(-2, -2))
    }

    private fun toggleAll() {
        val all = items.map { it.path }
        if (all.isNotEmpty() && all.all { it in sel }) sel.clear() else { sel.clear(); sel.addAll(all) }
        adapter.notifyDataSetChanged(); renderBar()
    }

    // ───────── التحميل ─────────
    private fun load(p: String) {
        val my = ++gen
        sel.clear(); renderBar()
        msg = "⏳ بجيب الملفات…"; updateInfo()
        val app = applicationContext
        Thread {
            var err = ""
            var parsed: List<Item> = emptyList(); var par = ""; var cur = p; var acc = true
            try {
                if (!RemoteClient.ensureConnected(app, 8000L)) err = "ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي، وإنك مقترن (مركز النقل ← الرادار)."
                else {
                    val (c, t) = RemoteClient.getText("/fs/list?path=" + URLEncoder.encode(p, "UTF-8"), 25000)
                    when {
                        c == 404 -> err = "التلفزيون محتاج تحديث للنسخة دي."
                        c == 403 -> err = "المسار ده مش مسموح."
                        c != 200 -> err = "ما قدرتش أجيب القايمة ($c)"
                        else -> {
                            val j = JSONObject(t)
                            cur = j.optString("path"); par = j.optString("parent"); acc = j.optBoolean("access", true)
                            val a = j.optJSONArray("items")
                            val out = ArrayList<Item>()
                            if (a != null) for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Item(o.optString("n"), o.optBoolean("d"), o.optLong("s"), o.optLong("m"), o.optString("p"))) }
                            parsed = out
                        }
                    }
                }
            } catch (e: Throwable) { LogStore.err("TvFiles:load", e); err = "حصلت مشكلة" }
            runOnUiThread {
                if (my != gen || isFinishing || isDestroyed) return@runOnUiThread
                if (err.isNotEmpty()) { msg = "⚠ $err"; items = emptyList() }
                else { path = cur; parent = par; items = parsed; noAccess = !acc; msg = "" }
                crumb.text = if (path.isEmpty()) "التخزين" else path
                adapter.notifyDataSetChanged(); updateInfo()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun updateInfo() {
        info.text = when {
            msg.isNotEmpty() -> msg
            noAccess -> "⚠ التلفزيون مش مدّي التطبيق صلاحية «كل الملفات» — افتح الملفات على التلفزيون مرة واسمح بالوصول."
            items.isEmpty() -> "المجلد فاضي"
            else -> items.size.toString() + " عنصر" + (if (sel.isNotEmpty()) " · اتحدّد " + sel.size else "") + " — ضغطة مطوّلة أو ⋮ للنقل"
        }
    }

    private fun up() { if (path.isNotEmpty()) load(parent) else finish() }
    override fun onBackPressed() { if (sel.isNotEmpty()) { sel.clear(); adapter.notifyDataSetChanged(); renderBar() } else up() }

    private fun onClick(p: Int) {
        val it = items.getOrNull(p) ?: return
        if (sel.isNotEmpty()) { toggle(it); return }
        if (it.dir) load(it.path) else menu(p)
    }

    private fun toggle(it: Item) {
        if (!sel.remove(it.path)) sel.add(it.path)
        adapter.notifyDataSetChanged(); renderBar(); updateInfo()
    }

    // ───────── قايمة العنصر (⋮ / ضغطة مطوّلة) ─────────
    private fun menu(p: Int) {
        val it = items.getOrNull(p) ?: return
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        if (it.dir) add("📂 فتح المجلد") { load(it.path) }
        add(if (it.dir) "📥 نزّل المجلد كله للموبايل" else "📥 نزّل للموبايل") { downloadItems(listOf(it)) }
        add(if (it.path in sel) "☐ شيل التحديد" else "☑ تحديد (لنقل أكتر من واحد)") { toggle(it) }
        add("📋 نسخ المسار") { try { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("path", it.path)); Notice.show(this, "اتنسخ", 1500L) } catch (_: Throwable) {} }
        add("ℹ تفاصيل") {
            GAlert(this).setTitle(it.name).setMessage((if (it.dir) "مجلد" else Sniff.fmtSize(it.size)) + (if (it.mtime > 0L) "\n" + fmt.format(Date(it.mtime * 1000L)) else "") + "\n" + it.path).setPositiveButton("تمام", null).show()
        }
        GAlert(this).setTitle(it.name).setItems(labels.toTypedArray()) { _, w -> if (w in acts.indices) acts[w]() }.setNegativeButton("إغلاق", null).show()
    }

    // ───────── التنزيل ─────────
    private fun downloadItems(targets: List<Item>) {
        if (targets.isEmpty()) return
        val app = applicationContext
        Notice.show(this, "⏳ بجهّز التنزيل…", 1800L)
        Thread {
            val jobs = ArrayList<XRemote.PathJob>()
            var capped = false
            fun collect(dirPath: String, rel: String, depth: Int) {
                if (jobs.size >= 400 || depth > 8) { capped = true; return }
                val (c, t) = RemoteClient.getText("/fs/list?path=" + URLEncoder.encode(dirPath, "UTF-8"), 25000)
                if (c != 200) return
                val a = try { JSONObject(t).optJSONArray("items") } catch (_: Throwable) { null } ?: return
                for (i in 0 until a.length()) {
                    if (jobs.size >= 400) { capped = true; return }
                    val o = a.getJSONObject(i)
                    val n = o.optString("n"); val pp = o.optString("p")
                    if (o.optBoolean("d")) collect(pp, rel + "/" + Xfer.safeName(n), depth + 1)
                    else if (o.optLong("s") > 0L) jobs.add(XRemote.PathJob(pp, n, o.optLong("s"), rel))
                }
            }
            try {
                for (it in targets) {
                    if (it.dir) collect(it.path, Xfer.safeName(it.name), 0)
                    else if (it.size > 0L) jobs.add(XRemote.PathJob(it.path, it.name, it.size, ""))
                }
            } catch (e: Throwable) { LogStore.err("TvFiles:collect", e) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (jobs.isEmpty()) { Notice.show(this, "⚠ مفيش ملفات تتنزّل", 2600L); return@runOnUiThread }
                val title = if (targets.size == 1) targets[0].name else targets.size.toString() + " عنصر من التلفزيون"
                if (XRemote.downloadPaths(app, title, jobs)) {
                    Notice.show(this, "⬇ بدأ تنزيل " + jobs.size + " ملف" + (if (capped) " (أول 400 بس)" else "") + " — التقدم في زرار المهام، والملفات في فولدراتها (Joyboy)", 4500L)
                    sel.clear(); adapter.notifyDataSetChanged(); renderBar(); updateInfo()
                } else Notice.show(this, "⚠ مش متصل بالتلفزيون", 2600L)
            }
        }.also { it.isDaemon = true }.start()
    }

    // ───────── العرض ─────────
    private fun emojiOf(it: Item): String = if (it.dir) "📁" else XCat.emoji(XCat.catOf(it.name))

    private inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val it = items[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@TvFilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(8), ui.dp(9), ui.dp(4), ui.dp(9))
                val chk = ui.text("", 20f, th.primary); chk.tag = "chk"
                val ic = ui.text("", 24f, th.text); ic.tag = "ic"
                val col = LinearLayout(this@TvFilesActivity).apply { orientation = LinearLayout.VERTICAL; tag = "col" }
                col.addView(ui.text("", 14f, th.text, true).apply { tag = "t1"; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                col.addView(ui.text("", 11.5f, th.muted).apply { tag = "t2"; maxLines = 1 })
                val dots = ui.text("⋮", 22f, th.muted, true).apply { tag = "dots"; gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)); isClickable = true }
                addView(chk, LinearLayout.LayoutParams(ui.dp(30), -2))
                addView(ic, LinearLayout.LayoutParams(ui.dp(38), -2).apply { marginEnd = ui.dp(6) })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                addView(dots, LinearLayout.LayoutParams(-2, -2))
            }
            val on = it.path in sel
            row.findViewWithTag<TextView>("chk").text = if (on) "☑" else if (sel.isNotEmpty()) "☐" else ""
            row.findViewWithTag<TextView>("ic").text = emojiOf(it)
            row.findViewWithTag<TextView>("t1").text = it.name
            row.findViewWithTag<TextView>("t2").text = if (it.dir) "مجلد" else Sniff.fmtSize(it.size) + (if (it.mtime > 0L) " · " + fmt.format(Date(it.mtime * 1000L)) else "")
            row.findViewWithTag<TextView>("dots").setOnClickListener { menu(position) }
            row.background = if (on) ui.box(th.surface, th.primary, 12, 2) else null
            return row
        }
    }
}
