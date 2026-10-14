package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** (v310) عنصر في قايمة الفايل مانجر */
class FmItem(val f: File, val dir: Boolean, val size: Long, val mod: Long, val count: Int) {
    val name: String get() = f.name
}

/** (v310) أدوات صغيرة للفايل مانجر: أيقونات · أحجام · نسخ · أسماء مش متكررة */
object FmUtil {
    val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "flv", "m4v", "wmv", "mpg", "mpeg")
    val AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr")
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    val SUB = setOf("srt", "vtt", "ass", "ssa", "sub")
    val ARCH = setOf("zip", "rar", "7z", "tar", "gz")

    fun icon(i: FmItem): String {
        if (i.dir) return "📁"
        val e = i.f.extension.lowercase()
        return when {
            e in VIDEO -> "🎞"
            e in AUDIO -> "🎵"
            e in IMAGE -> "🖼"
            e in SUB -> "💬"
            e in ARCH -> "🗜"
            e == "apk" -> "📦"
            e == "pdf" -> "📕"
            else -> "📄"
        }
    }

    fun size(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun uniq(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        var f = File(dir, "$base ($n)$ext")
        while (f.exists()) { n++; f = File(dir, "$base ($n)$ext") }
        return f
    }

    fun copy(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            val kids = src.listFiles()
            if (kids != null) for (k in kids) copy(k, File(dst, k.name))
        } else src.copyTo(dst, false)
    }

    fun uriFor(ctx: Context, f: File): Uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fmprov", f)

    fun mime(f: File): String {
        val e = f.extension.lowercase()
        val m = try { MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) } catch (_: Exception) { null }
        if (m != null) return m
        return when {
            e in SUB -> "text/plain"
            else -> "*/*"
        }
    }

    fun scan(ctx: Context, files: List<File>) {
        val paths = files.filter { !it.isDirectory }.map { it.absolutePath }
        if (paths.isEmpty()) return
        try { android.media.MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null) } catch (_: Exception) {}
    }
}

/** (v310) فايل مانجر شغّال على الملفات مباشرة (صلاحية «كل الملفات»): تصفّح · بحث · تحديد · نسخ/نقل/لصق · إعادة تسمية · حذف · مشاركة · تفاصيل */
class FileManagerActivity : Activity() {
    override fun finish() { super.finish(); try { overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Fm:finish", e) } }

    private lateinit var th: Theme
    private lateinit var ui: Ui
    private var cur: File = Environment.getExternalStorageDirectory()
    private var all: List<FmItem> = emptyList()
    private var shown: List<FmItem> = emptyList()
    private val sel = LinkedHashSet<String>()
    private var clip: List<File> = emptyList()
    private var clipCut = false
    private var sortMode = 0
    private var showHidden = false
    private var query = ""
    private var loadErr = false
    private var loaded = false
    private var loadToken = 0

    private lateinit var titleTv: TextView
    private lateinit var pathTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var list: ListView
    private lateinit var emptyTv: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteTv: TextView
    private lateinit var adapter: Adapter
    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

    private fun prefs() = getSharedPreferences("p", 0)
    private fun toast(m: String) = Notice.show(this, m, 2600L)

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun isTop(f: File): Boolean {
        val p = f.parentFile ?: return true
        return p.absolutePath in setOf("/", "/storage", "/storage/emulated", "/mnt")
    }

    private fun chip(t: String, f: () -> Unit): TextView = IconTextView(this).apply {
        text = t; textSize = 13f; setTextColor(th.text); gravity = Gravity.CENTER
        setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
        background = ui.box(th.surface, th.border, 20)
        Glass.pressable(this)
        setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        th = Themes.byId(try { prefs().getString("theme", "mx") } catch (_: Exception) { "mx" })
        ui = Ui(this, th)
        applyBars(th)
        sortMode = prefs().getInt("fm_sort", 0)
        showHidden = prefs().getBoolean("fm_hidden", false)
        val startPath = intent?.getStringExtra("path") ?: prefs().getString("fm_last", null)
        if (startPath != null) { val d = File(startPath); if (d.isDirectory) cur = d }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(th.bg); setPadding(0, ui.dp(28), 0, 0)
        }

        // الهيدر: ✕ · العنوان/المسار · ↑ · ⋮
        titleTv = ui.text("", 16f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        pathTv = ui.text("", 11f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(titleTv); addView(pathTv) }
        fun tbtn(t: String, f: () -> Unit): TextView = IconTextView(this).apply {
            text = t; textSize = 20f; gravity = Gravity.CENTER; setTextColor(th.text)
            layoutParams = LinearLayout.LayoutParams(ui.dp(42), ui.dp(44)); Glass.pressable(this); setOnClickListener { f() }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(6), 0, ui.dp(6), 0)
            addView(tbtn("✕") { finish() })
            addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
            addView(tbtn("⬆") { up() })
            addView(tbtn("⋮") { menu() })
        }
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        // بحث داخل المجلد
        searchEt = ui.input("🔍 بحث في المجلد", "").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; refresh() }
            })
        }
        root.addView(searchEt, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), 0, ui.dp(12), 0) })

        // اختصارات الأماكن
        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)) }
        for ((label, f) in roots()) chipsRow.addView(chip(label) { load(f) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        val chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) }
        root.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2))

        // رسالة الصلاحية
        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(24), ui.dp(18), ui.dp(12)); visibility = View.GONE
            addView(ui.text("محتاج صلاحية «الوصول لكل الملفات» عشان يعرض ملفات الموبايل ويعدّل عليها.", 14f, th.text).apply { gravity = Gravity.CENTER })
            addView(ui.button("🔓 افتح صلاحية الملفات", true) { askAccess() })
        }
        root.addView(permBox, LinearLayout.LayoutParams(-1, -2))

        // القايمة
        adapter = Adapter()
        list = ListView(this).apply {
            adapter = this@FileManagerActivity.adapter
            divider = android.graphics.drawable.ColorDrawable(th.border); dividerHeight = 1
            setOnItemClickListener { _, _, pos, _ -> if (pos in shown.indices) onTap(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> if (pos in shown.indices) toggleSel(shown[pos]); true }
        }
        emptyTv = ui.text("", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(40), 0, 0); visibility = View.GONE }
        val body = android.widget.FrameLayout(this)
        body.addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyTv, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        // شريط اللصق
        pasteTv = ui.text("", 13f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        pasteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setBackgroundColor(th.card); setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            addView(pasteTv, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chip("📥 الصق هنا") { paste() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
            addView(chip("✖") { clip = emptyList(); refresh() })
        }
        root.addView(pasteBar, LinearLayout.LayoutParams(-1, -2))

        // شريط التحديد
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE
            setBackgroundColor(th.card); setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        fun selBtn(t: String, f: () -> Unit) = selBar.addView(chip(t, f), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) })
        selBtn("📋 نسخ") { setClip(false) }
        selBtn("✂ نقل") { setClip(true) }
        selBtn("↗ مشاركة") { shareSel() }
        selBtn("🗑 حذف") { deleteSel() }
        selBtn("⋯") { moreSel() }
        root.addView(selBar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            permBox.visibility = View.GONE
            if (!loaded) { loaded = true; load(cur) }
        } else {
            permBox.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT < 30) requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 31)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasAccess()) { permBox.visibility = View.GONE; loaded = true; load(cur) }
    }

    private fun askAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            else requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 31)
        } catch (e: Exception) { LogStore.err("Fm:perm", e); toast("⚠ مش قادر أفتح إعدادات الصلاحية") }
    }

    @Suppress("DEPRECATION")
    private fun roots(): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        val ext = Environment.getExternalStorageDirectory()
        out.add("📱 الداخلية" to ext)
        for ((l, n) in listOf("⬇ التنزيلات" to Environment.DIRECTORY_DOWNLOADS, "📷 الكاميرا" to Environment.DIRECTORY_DCIM,
            "🎞 أفلام" to Environment.DIRECTORY_MOVIES, "🎵 موسيقى" to Environment.DIRECTORY_MUSIC, "📄 مستندات" to Environment.DIRECTORY_DOCUMENTS)) {
            val d = try { Environment.getExternalStoragePublicDirectory(n) } catch (_: Exception) { null }
            if (d != null && d.isDirectory) out.add(l to d)
        }
        try {
            for (d in getExternalFilesDirs(null)) {
                val p = d?.absolutePath ?: continue
                val i = p.indexOf("/Android/data")
                if (i > 0) { val r = File(p.substring(0, i)); if (r.absolutePath != ext.absolutePath && r.isDirectory) out.add("💾 ${r.name}" to r) }
            }
        } catch (_: Exception) {}
        return out
    }

    // ===== تحميل وعرض =====
    private fun load(dir: File) {
        cur = dir; sel.clear(); query = ""
        if (searchEt.text.isNotEmpty()) searchEt.setText("")
        try { prefs().edit().putString("fm_last", dir.absolutePath).apply() } catch (_: Exception) {}
        val token = ++loadToken
        Thread {
            val arr = try { dir.listFiles() } catch (_: Exception) { null }
            val res = ArrayList<FmItem>()
            if (arr != null) for (f in arr) {
                try {
                    val d = f.isDirectory
                    res.add(FmItem(f, d, if (d) 0L else f.length(), f.lastModified(), if (d) (f.list()?.size ?: 0) else 0))
                } catch (_: Exception) {}
            }
            runOnUiThread {
                if (!isDestroyed && token == loadToken) { all = res; loadErr = arr == null; refresh(); list.setSelection(0) }
            }
        }.start()
    }

    private fun refresh() {
        val q = query.trim().lowercase()
        val filtered = all.filter { (showHidden || !it.name.startsWith(".")) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        val cmp: Comparator<FmItem> = when (sortMode) {
            1 -> compareByDescending<FmItem> { it.mod }
            2 -> compareByDescending<FmItem> { it.size }
            else -> compareBy<FmItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        shown = filtered.sortedWith(compareByDescending<FmItem> { it.dir }.then(cmp))
        adapter.notifyDataSetChanged()
        val ext = Environment.getExternalStorageDirectory().absolutePath
        titleTv.text = if (sel.isNotEmpty()) "✔ ${sel.size} محدد" else if (cur.absolutePath == ext) "الذاكرة الداخلية" else cur.name.ifEmpty { "/" }
        pathTv.text = cur.absolutePath
        selBar.visibility = if (sel.isNotEmpty()) View.VISIBLE else View.GONE
        pasteBar.visibility = if (clip.isNotEmpty()) View.VISIBLE else View.GONE
        pasteTv.text = "${if (clipCut) "✂ نقل" else "📋 نسخ"}: ${clip.size} عنصر"
        if (shown.isEmpty()) {
            emptyTv.text = if (loadErr) "⚠ مش قادر أقرا المجلد ده" else if (q.isNotEmpty()) "مفيش نتيجة" else "المجلد فاضي"
            emptyTv.visibility = View.VISIBLE
        } else emptyTv.visibility = View.GONE
    }

    private fun up() { if (!isTop(cur)) cur.parentFile?.let { load(it) } }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sel.isNotEmpty() -> { sel.clear(); refresh() }
            query.isNotEmpty() -> searchEt.setText("")
            !isTop(cur) -> up()
            else -> finish()
        }
    }

    // ===== القايمة =====
    private class Holder { lateinit var ic: TextView; lateinit var name: TextView; lateinit var info: TextView }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val h: Holder
            val row: View
            if (cv == null) {
                h = Holder()
                h.ic = IconTextView(this@FileManagerActivity).apply { textSize = 24f; gravity = Gravity.CENTER }
                h.name = ui.text("", 14f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
                h.info = ui.text("", 11f, th.muted).apply { setSingleLine() }
                val col = LinearLayout(this@FileManagerActivity).apply { orientation = LinearLayout.VERTICAL; addView(h.name); addView(h.info) }
                row = LinearLayout(this@FileManagerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
                    addView(h.ic, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
                    addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(10), 0, 0, 0) })
                }
                row.tag = h
            } else { row = cv; h = cv.tag as Holder }
            val fi = shown[p]
            val on = sel.contains(fi.f.absolutePath)
            h.ic.text = if (on) "✅" else FmUtil.icon(fi)
            h.name.text = fi.name
            h.name.gravity = Gravity.START; h.info.gravity = Gravity.START
            val date = fmt.format(Date(fi.mod))
            h.info.text = if (fi.dir) "${fi.count} عنصر  ·  $date" else "${FmUtil.size(fi.size)}  ·  $date"
            row.setBackgroundColor(if (on) ((th.primary and 0x00FFFFFF) or 0x33000000) else 0)
            return row
        }
    }

    private fun onTap(fi: FmItem) {
        if (sel.isNotEmpty()) { toggleSel(fi); return }
        if (fi.dir) load(fi.f) else openFile(fi.f, false)
    }

    private fun toggleSel(fi: FmItem) {
        val p = fi.f.absolutePath
        if (!sel.remove(p)) sel.add(p)
        refresh()
    }

    private fun selFiles(): List<File> = sel.map { File(it) }

    private fun openFile(f: File, external: Boolean) {
        val e = f.extension.lowercase()
        try {
            if (!external && (e in FmUtil.VIDEO || e in FmUtil.AUDIO)) {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    data = Uri.fromFile(f); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("landscape", e in FmUtil.VIDEO); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
                })
            } else {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(FmUtil.uriFor(this@FileManagerActivity, f), FmUtil.mime(f)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        } catch (ex: Exception) { LogStore.err("Fm:open", ex); toast("⚠ مفيش برنامج يفتح الملف ده") }
    }

    // ===== القايمة ⋮ =====
    private fun menu() {
        val sorts = arrayOf("الاسم", "الأحدث", "الأكبر حجماً")
        val items = arrayOf("📁 مجلد جديد", "↕ الترتيب: ${sorts[sortMode.coerceIn(0, 2)]}", if (showHidden) "👁 إخفاء الملفات المخفية" else "👁 إظهار الملفات المخفية", "☑ تحديد الكل", "🔄 تحديث")
        GAlert(this).setTitle("الفايل مانجر").setItems(items) { _, w ->
            when (w) {
                0 -> promptName("📁 مجلد جديد", "", "إنشاء") { n ->
                    when {
                        n.isEmpty() || n.contains("/") -> toast("الاسم مش صح")
                        File(cur, n).exists() -> toast("الاسم موجود قبل كده")
                        File(cur, n).mkdirs() -> load(cur)
                        else -> toast("⚠ ما قدرتش أعمل المجلد")
                    }
                }
                1 -> GAlert(this).setTitle("ترتيب حسب").setItems(sorts) { _, s ->
                    sortMode = s; prefs().edit().putInt("fm_sort", s).apply(); refresh()
                }.show()
                2 -> { showHidden = !showHidden; prefs().edit().putBoolean("fm_hidden", showHidden).apply(); refresh() }
                3 -> { shown.forEach { sel.add(it.f.absolutePath) }; refresh() }
                4 -> load(cur)
            }
        }.show()
    }

    private fun promptName(title: String, init: String, ok: String, onOk: (String) -> Unit) {
        val et = ui.input("الاسم", init)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(4)); addView(et) }
        GAlert(this).setTitle(title).setView(c)
            .setPositiveButton(ok) { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("إلغاء", null).show()
    }

    // ===== عمليات التحديد =====
    private fun setClip(cut: Boolean) {
        clip = selFiles(); clipCut = cut; sel.clear(); refresh()
        toast("اتحفظ — روح للمجلد المطلوب واضغط «الصق هنا»")
    }

    private fun paste() {
        val dest = cur; val src = clip; val cut = clipCut
        clip = emptyList(); refresh()
        toast("⏳ جاري ${if (cut) "النقل" else "النسخ"}…")
        Thread {
            var ok = 0; var fail = 0
            val made = ArrayList<File>()
            for (s in src) {
                try {
                    val same = s.parentFile?.absolutePath == dest.absolutePath
                    val inside = s.isDirectory && (dest.absolutePath == s.absolutePath || dest.absolutePath.startsWith(s.absolutePath + "/"))
                    if (inside) { fail++ }
                    else if (cut && same) { ok++ }
                    else {
                        val d = FmUtil.uniq(dest, s.name)
                        if (cut) { if (!s.renameTo(d)) { FmUtil.copy(s, d); s.deleteRecursively() } } else FmUtil.copy(s, d)
                        made.add(d); ok++
                    }
                } catch (e: Exception) { LogStore.err("Fm:paste", e); fail++ }
            }
            FmUtil.scan(this, made)
            runOnUiThread { toast("✓ تم: $ok" + (if (fail > 0) "  ·  فشل: $fail" else "")); if (!isDestroyed) load(cur) }
        }.start()
    }

    private fun deleteSel() {
        val files = selFiles()
        if (files.isEmpty()) return
        val msg = if (files.size == 1) "تمسح «${files[0].name}» نهائياً؟" else "تمسح ${files.size} عنصر نهائياً؟"
        GAlert(this).setTitle("🗑 حذف").setMessage(msg + "\nمش هتقدر ترجّعه.")
            .setPositiveButton("امسح") { _, _ ->
                Thread {
                    var ok = 0; var fail = 0
                    for (f in files) { try { if (f.deleteRecursively()) ok++ else fail++ } catch (e: Exception) { LogStore.err("Fm:del", e); fail++ } }
                    FmUtil.scan(this, files)
                    runOnUiThread { toast("✓ اتمسح: $ok" + (if (fail > 0) "  ·  فشل: $fail" else "")); if (!isDestroyed) load(cur) }
                }.start()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun shareSel() {
        val files = selFiles().filter { it.isFile }
        if (files.isEmpty()) { toast("المجلدات مش بتتشارك — حدّد ملفات"); return }
        try {
            val uris = ArrayList<Uri>(files.map { FmUtil.uriFor(this, it) })
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(FmUtil.mime(files[0]))
                    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "مشاركة"))
        } catch (e: Exception) { LogStore.err("Fm:share", e); toast("⚠ ما قدرتش أشارك") }
    }

    private fun moreSel() {
        val files = selFiles()
        if (files.size == 1) {
            val f = files[0]
            val items = if (f.isFile) arrayOf("✏ إعادة تسمية", "ℹ تفاصيل", "↗ فتح ببرنامج تاني") else arrayOf("✏ إعادة تسمية", "ℹ تفاصيل")
            GAlert(this).setTitle(f.name).setItems(items) { _, w ->
                when (w) { 0 -> rename(f); 1 -> details(f); 2 -> openFile(f, true) }
            }.show()
        } else {
            GAlert(this).setTitle("${files.size} عنصر").setItems(arrayOf("☑ تحديد الكل", "✖ إلغاء التحديد")) { _, w ->
                if (w == 0) shown.forEach { sel.add(it.f.absolutePath) } else sel.clear()
                refresh()
            }.show()
        }
    }

    private fun rename(f: File) {
        promptName("✏ إعادة تسمية", f.name, "تمام") { n ->
            val d = File(f.parentFile, n)
            when {
                n.isEmpty() || n.contains("/") -> toast("الاسم مش صح")
                n == f.name -> {}
                d.exists() -> toast("الاسم موجود قبل كده")
                f.renameTo(d) -> { FmUtil.scan(this, listOf(f, d)); load(cur) }
                else -> toast("⚠ ما قدرتش أغيّر الاسم")
            }
        }
    }

    private fun details(f: File) {
        Thread {
            val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.fold(0L) { a, x -> a + x.length() } else f.length()
            val n = if (f.isDirectory) (f.walkTopDown().count() - 1) else 0
            val txt = "الاسم: ${f.name}\nالمسار: ${f.absolutePath}\nالنوع: ${if (f.isDirectory) "مجلد ($n عنصر بداخله)" else (f.extension.ifEmpty { "ملف" })}\n" +
                "الحجم: ${FmUtil.size(size)}\nآخر تعديل: ${fmt.format(Date(f.lastModified()))}"
            runOnUiThread { if (!isDestroyed) GAlert(this).setTitle("ℹ تفاصيل").setMessage(txt).setPositiveButton("تمام", null).show() }
        }.start()
    }
}
