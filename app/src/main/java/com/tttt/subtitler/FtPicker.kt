package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.bumptech.glide.Glide
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v653) شاشة اختيار الملفات للإرسال: الأحدث · صور · فيديو · موسيقى · مستندات · التنزيلات · الذاكرة · مجلدات
 * مع مسار تنقل (Breadcrumb) وبحث وتحديد متعدد وتحديد مجلد كامل وإجمالي الحجم. بيقرا الملفات الحقيقية من الجهاز (MediaStore + File).
 * ما بتبدأش أي نقل — بس بتسلّم القايمة لما المستخدم يدوس «إرسال».
 */
class FtPicker(
    private val act: Activity, private val ui: Ui, private val th: Theme, private val tv: Boolean,
    private val onSend: (List<FtPick>) -> Unit, private val onBack: () -> Unit
) {
    class PItem(val name: String, val size: Long, val date: Long, val dir: Boolean, val file: File?, val uri: Uri?) {
        val key: String get() = file?.absolutePath ?: uri.toString()
    }

    private val h = Handler(Looper.getMainLooper())
    private val sel = LinkedHashMap<String, PItem>()
    private val dirSize = HashMap<String, Long>()
    private var items: List<PItem> = emptyList()
    private var shown: List<PItem> = emptyList()
    private var section = "recent"
    private var dir: File? = null
    private var gen = 0
    private var query = ""
    private var loading = false
    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    private val sections = listOf("recent" to "🕘 الأحدث", "images" to "🖼 صور", "videos" to "🎬 فيديو", "music" to "🎵 موسيقى", "docs" to "📄 مستندات", "downloads" to "⬇ التنزيلات", "internal" to "📱 الذاكرة الداخلية", "folders" to "📁 المجلدات")
    private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "zip", "rar", "7z", "epub", "srt", "json", "apk")
    private val chipsBox = LinearLayout(act)
    private val crumbBox = LinearLayout(act)
    private val crumbScroll = HorizontalScrollView(act)
    private lateinit var search: EditText
    private lateinit var info: TextView
    private lateinit var access: TextView
    private lateinit var barInfo: TextView
    private lateinit var list: ListView
    private val adapter = Adapter()
    val view: View

    private fun lp(w: Int, hh: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, hh, wt)

    private fun pill(label: String, on: Boolean, f: () -> Unit): TextView = IconTextView(act).apply {
        text = label; textSize = if (tv) 18f else 13f; gravity = Gravity.CENTER; isFocusable = true; isClickable = true; maxLines = 1
        setPadding(ui.dp(if (tv) 18 else 12), ui.dp(if (tv) 12 else 9), ui.dp(if (tv) 18 else 12), ui.dp(if (tv) 12 else 9))
        fun paint(foc: Boolean) {
            setTextColor(if (on) onPrimary else th.text)
            background = ui.box(if (on) th.primary else th.surface, if (foc) th.accent else if (on) th.primary else th.border, 20, if (foc) 3 else 1)
        }
        paint(false)
        setOnFocusChangeListener { _, foc -> paint(foc) }
        setOnClickListener { f() }
    }

    init {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(pill("→", false) { onBack() }, lp(-2, -2).apply { marginEnd = ui.dp(8) })
        head.addView(ui.text("اختار الملفات", if (tv) 24f else 19f, th.primary, true), lp(0, -2, 1f))
        root.addView(head, lp(-1, -2))

        val hs = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chipsBox, FrameLayout.LayoutParams(-2, -2)) }
        chipsBox.orientation = LinearLayout.HORIZONTAL
        root.addView(hs, lp(-1, -2).apply { topMargin = ui.dp(6) })

        crumbBox.orientation = LinearLayout.HORIZONTAL
        crumbScroll.apply { isHorizontalScrollBarEnabled = false; addView(crumbBox, FrameLayout.LayoutParams(-2, -2)) }
        root.addView(crumbScroll, lp(-1, -2).apply { topMargin = ui.dp(4) })

        search = ui.input("🔍 بحث بالاسم", "").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { query = s?.toString()?.trim() ?: ""; applyFilter() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(search)

        access = pill("🔓 اسمح بالوصول للملفات", true) { askAccess() }.apply { visibility = View.GONE }
        root.addView(access, lp(-1, -2).apply { bottomMargin = ui.dp(4) })
        info = ui.text("", if (tv) 15f else 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(2)) }
        root.addView(info, lp(-1, -2))

        list = ListView(act).apply {
            adapter = this@FtPicker.adapter
            divider = null; dividerHeight = ui.dp(2); isFocusable = true; cacheColorHint = Color.TRANSPARENT
            val hi = ui.box(th.surface, th.accent, 12, 2)
            selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), hi)
                addState(intArrayOf(android.R.attr.state_focused), hi)
                addState(intArrayOf(android.R.attr.state_selected), hi)
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnItemClickListener { _, _, p, _ -> onRow(p) }
            setOnItemLongClickListener { _, _, p, _ -> val it = shown.getOrNull(p); if (it != null && it.dir) { toggle(it); true } else false }
        }
        root.addView(list, lp(-1, 0, 1f))

        barInfo = ui.text("", if (tv) 16f else 13f, th.text, true).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(2)) }
        root.addView(barInfo, lp(-1, -2))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        bar.addView(pill("📤 إرسال", true) { send() }, lp(0, -2, 1.3f).apply { marginEnd = ui.dp(4) })
        bar.addView(pill("☑ الكل", false) { selectAllShown() }, lp(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        bar.addView(pill("✕ إلغاء التحديد", false) { sel.clear(); adapter.notifyDataSetChanged(); updateBar() }, lp(0, -2, 1.2f))
        root.addView(bar, lp(-1, -2).apply { topMargin = ui.dp(4) })
        view = root
        renderChips()
        openSec("recent", null)
    }

    // ───────── الصلاحيات ─────────
    fun hasAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return if (Build.VERSION.SDK_INT >= 33)
            listOf(android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO, android.Manifest.permission.READ_MEDIA_IMAGES)
                .all { act.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        else act.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun askAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + act.packageName)))
            else act.requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 31)
        } catch (e: Throwable) { LogStore.err("FtPicker:perm", e); Notice.show(act, "⚠ مش قادر أفتح إعدادات الصلاحية", 2600L) }
    }

    /** بيتنادى لما الشاشة ترجع (ممكن المستخدم يكون سمح بالصلاحية) */
    fun refreshIfNeeded() { if (items.isEmpty() && !loading) openSec(section, dir) else access.visibility = if (hasAccess()) View.GONE else View.VISIBLE }

    // ───────── الأقسام ─────────

    private fun renderChips() {
        chipsBox.removeAllViews()
        for ((k, label) in sections) chipsBox.addView(pill(label, section == k) { openSec(k, null) }, lp(-2, -2).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) })
    }

    private fun browsing(): Boolean = section == "downloads" || section == "internal" || section == "folders"

    private fun rootOf(sec: String): File? = try {
        when (sec) {
            "downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            "internal" -> Environment.getExternalStorageDirectory()
            else -> null
        }
    } catch (_: Throwable) { null }

    private fun fromEntry(e: XEntry) = PItem(e.name, e.size, e.date * 1000L, false, e.file, e.uri)

    private fun browse(d: File): List<PItem> {
        val ls = try { d.listFiles() } catch (_: Throwable) { null } ?: return emptyList()
        return ls.filter { !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { PItem(it.name, if (it.isFile) it.length() else 0L, it.lastModified(), it.isDirectory, it, null) }
    }


    private fun docs(): List<PItem> {
        val out = ArrayList<PItem>()
        fun walk(d: File?, depth: Int) {
            if (d == null || depth < 0 || out.size >= 800) return
            val ls = try { d.listFiles() } catch (_: Throwable) { null } ?: return
            for (f in ls) {
                if (out.size >= 800) return
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) { if (f.name != "Android") walk(f, depth - 1) }
                else if (f.extension.lowercase() in DOC_EXT && f.length() > 0L) out.add(PItem(f.name, f.length(), f.lastModified(), false, f, null))
            }
        }
        try { walk(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), 3) } catch (_: Throwable) {}
        try { walk(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 3) } catch (_: Throwable) {}
        return out.sortedByDescending { it.date }
    }

    fun openSec(sec: String, d: File?) {
        section = sec; dir = d; query = ""
        try { search.setText("") } catch (_: Throwable) {}
        renderChips()
        val my = ++gen
        loading = true
        info.text = "⏳ بحمّل…"
        items = emptyList(); shown = emptyList(); adapter.notifyDataSetChanged()
        val app = act.applicationContext
        Thread {
            val res: List<PItem> = try {
                when (sec) {
                    "recent" -> (FileCatalog.list(app, XCat.IMAGE) + FileCatalog.list(app, XCat.VIDEO) + FileCatalog.list(app, XCat.AUDIO)).sortedByDescending { it.date }.take(80).map { fromEntry(it) }
                    "images" -> FileCatalog.list(app, XCat.IMAGE).map { fromEntry(it) }
                    "videos" -> FileCatalog.list(app, XCat.VIDEO).map { fromEntry(it) }
                    "music" -> FileCatalog.list(app, XCat.AUDIO).map { fromEntry(it) }
                    "docs" -> docs()
                    "downloads", "internal" -> { val r = d ?: rootOf(sec); if (r == null) emptyList() else browse(r) }
                    else -> if (d == null) knownFolders() else browse(d)
                }
            } catch (e: Throwable) { LogStore.err("FtPicker:load", e); emptyList() }
            h.post {
                if (my != gen) return@post
                loading = false
                items = res
                access.visibility = if (hasAccess()) View.GONE else View.VISIBLE
                applyFilter(); renderCrumbs()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun knownFolders(): List<PItem> {
        val out = ArrayList<PItem>()
        for (n in listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_DOWNLOADS)) {
            try { val f = Environment.getExternalStoragePublicDirectory(n); if (f.isDirectory) out.add(PItem(f.name, 0L, f.lastModified(), true, f, null)) } catch (_: Throwable) {}
        }
        try { for (f in File("/storage").listFiles() ?: emptyArray<File>()) if (f.isDirectory && f.name != "emulated" && f.name != "self" && f.canRead()) out.add(PItem("💾 " + f.name, 0L, f.lastModified(), true, f, null)) } catch (_: Throwable) {}
        return out
    }

    private fun renderCrumbs() {
        crumbBox.removeAllViews()
        if (!browsing()) { crumbScroll.visibility = View.GONE; return }
        crumbScroll.visibility = View.VISIBLE
        val base: File? = if (section == "folders") null else rootOf(section)
        val cur = dir
        fun add(label: String, f: File?) {
            crumbBox.addView(pill(label, false) { openSec(section, f) }, lp(-2, -2).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) })
        }
        if (section == "folders") add("📁 المجلدات", null) else add(if (section == "downloads") "⬇ التنزيلات" else "📱 الذاكرة", null)
        if (cur != null) {
            val chain = ArrayList<File>()
            var x: File? = cur
            val stop = base?.absolutePath
            while (x != null && x.absolutePath != stop && chain.size < 12) { chain.add(0, x); x = x.parentFile }
            chain.removeAll { it.absolutePath == "/storage" || it.absolutePath == "/storage/emulated" || it.absolutePath == "/" }
            for (f in chain) add("‹ " + f.name, f)
        }
    }

    /** زرار الرجوع: بيطلع مجلد لو بنتصفح، وإلا false (الشاشة تقفل) */
    fun back(): Boolean {
        if (!browsing()) return false
        val cur = dir ?: return false
        val base = if (section == "folders") null else rootOf(section)
        if (base != null && cur.absolutePath == base.absolutePath) return false
        val par = cur.parentFile
        val pp = par?.absolutePath
        if (section == "folders" && (par == null || pp == "/storage" || pp == "/storage/emulated" || pp == "/")) openSec(section, null) else if (par != null) openSec(section, if (base != null && par.absolutePath == base.absolutePath) null else par)
        return true
    }

    private fun applyFilter() {
        val q = query.lowercase()
        shown = if (q.isEmpty()) items else items.filter { it.name.lowercase().contains(q) }
        info.text = if (loading) "⏳ بحمّل…" else if (shown.isEmpty()) (if (!hasAccess()) "محتاج صلاحية الوصول للملفات" else "مفيش ملفات هنا") else shown.size.toString() + " عنصر"
        adapter.notifyDataSetChanged()
        updateBar()
    }

    // ───────── التحديد ─────────
    private fun toggle(it: PItem) {
        if (sel.remove(it.key) == null) {
            sel[it.key] = it
            val f = it.file
            if (it.dir && f != null && !dirSize.containsKey(it.key)) {
                Thread { val n = try { XExpand.files(listOf(f), 20000).sumOf { x -> x.length() } } catch (_: Throwable) { 0L }; dirSize[it.key] = n; h.post { updateBar() } }.also { t -> t.isDaemon = true }.start()
            }
        }
        adapter.notifyDataSetChanged()
        updateBar()
    }

    private fun onRow(p: Int) {
        val it = shown.getOrNull(p) ?: return
        if (it.dir) { val f = it.file ?: return; openSec(section, f) } else toggle(it)
    }

    private fun selectAllShown() {
        val files = shown.filter { !it.dir }
        val allOn = files.isNotEmpty() && files.all { sel.containsKey(it.key) }
        if (allOn) for (x in files) sel.remove(x.key) else for (x in files) sel[x.key] = x
        adapter.notifyDataSetChanged(); updateBar()
    }

    private fun updateBar() {
        var total = 0L
        var pending = false
        for (x in sel.values) { if (x.dir) { val s = dirSize[x.key]; if (s == null) pending = true else total += s } else total += x.size }
        barInfo.text = if (sel.isEmpty()) "مفيش ملفات متحدّدة" else "اتحدّد " + sel.size + " · " + Sniff.fmtSize(total) + (if (pending) " …" else "")
    }

    private fun send() {
        if (sel.isEmpty()) { Notice.show(act, "اختار ملف واحد على الأقل", 2200L); return }
        val chosen = sel.values.toList()
        Notice.show(act, "⏳ بجهّز القايمة…", 1500L)
        Thread {
            val out = ArrayList<FtPick>()
            for (x in chosen) {
                if (out.size > FtOut.MAX_FILES) break
                val f = x.file
                if (x.dir) {
                    if (f == null) continue
                    val base = f.absolutePath
                    for (y in XExpand.files(listOf(f), FtOut.MAX_FILES + 1)) {
                        if (out.size > FtOut.MAX_FILES) break
                        out.add(FtPick(f.name + "/" + y.absolutePath.removePrefix(base).trimStart('/'), y.length(), Uri.fromFile(y), y.absolutePath))
                    }
                } else {
                    val u = if (f != null) Uri.fromFile(f) else x.uri
                    if (u == null || x.size < 0L) continue
                    out.add(FtPick(x.name, x.size, u, f?.absolutePath ?: u.toString()))
                }
            }
            h.post {
                if (out.isEmpty()) Notice.show(act, "⚠ مفيش ملفات صالحة للإرسال", 2500L)
                else if (out.size > FtOut.MAX_FILES) Notice.show(act, "⚠ لحد " + FtOut.MAX_FILES + " ملف في المرة — قسّمهم على دفعات", 3500L)
                else onSend(out)
            }
        }.also { it.isDaemon = true }.start()
    }

    // ───────── الصفوف ─────────
    private inner class Row : LinearLayout(act) {
        val box = FrameLayout(act)
        val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val ico = TextView(act).apply { gravity = Gravity.CENTER; textSize = 22f }
        val title = ui.text("", if (tv) 17f else 14f, th.text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        val sub = ui.text("", if (tv) 13f else 11.5f, th.muted).apply { maxLines = 1 }
        val chk = TextView(act).apply { gravity = Gravity.CENTER; textSize = 22f }
        var cur: PItem? = null

        init {
            orientation = HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            val sz = ui.dp(if (tv) 60 else 50)
            box.background = ui.box(th.surface, th.border, 8)
            box.addView(img, FrameLayout.LayoutParams(-1, -1)); box.addView(ico, FrameLayout.LayoutParams(-1, -1))
            addView(box, LinearLayout.LayoutParams(sz, sz))
            val col = LinearLayout(act).apply { orientation = VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            col.addView(title); col.addView(sub)
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10); marginEnd = ui.dp(6) })
            addView(chk, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            chk.setOnClickListener { cur?.let { toggle(it) } }   // لمس فقط — الريموت بيستخدم دوسة الصف (وطويلة للمجلد)
        }

        fun bind(it: PItem) {
            cur = it
            title.text = it.name
            val on = sel.containsKey(it.key)
            chk.text = if (on) "☑" else "☐"
            chk.setTextColor(if (on) th.primary else th.muted)
            chk.visibility = View.VISIBLE
            val cat = if (it.dir) "dir" else XCat.catOf(it.name)
            ico.text = if (it.dir) "📁" else XCat.emoji(cat)
            sub.text = if (it.dir) "مجلد" else Sniff.fmtSize(it.size) + (if (it.date > 0L) " · " + fmt.format(Date(it.date)) else "")
            setBackgroundColor(if (on) (th.primary and 0x00FFFFFF) or 0x26000000 else Color.TRANSPARENT)
            val thumb = !it.dir && (cat == XCat.IMAGE || cat == XCat.VIDEO)
            if (thumb) {
                ico.visibility = View.GONE
                try { Glide.with(act).load(it.file ?: it.uri).centerCrop().override(ui.dp(96), ui.dp(96)).into(img) } catch (_: Throwable) { ico.visibility = View.VISIBLE }
            } else {
                try { Glide.with(act).clear(img) } catch (_: Throwable) {}
                img.setImageDrawable(null); ico.visibility = View.VISIBLE
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = (cv as? Row) ?: Row()
            v.bind(shown[p])
            return v
        }
    }
}
