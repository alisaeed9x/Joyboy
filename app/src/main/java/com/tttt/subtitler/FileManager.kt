package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
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

/** (v310) عنصر في قايمة الفايل مانجر (sub = المجلد النسبي في نتايج البحث العميق) */
class FmItem(val f: File, val dir: Boolean, val size: Long, val mod: Long, val count: Int, val sub: String = "") {
    val name: String get() = f.name
}

/** (v310) أدوات الفايل مانجر: أيقونات · أحجام · نسخ/ضغط/فك بإلغاء وتقدّم · أسماء مش متكررة */
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

    /** نسخ ملف/مجلد (متداخل). cancel بيتفحص قبل كل ملف، onFile بيبلّغ باسم الملف الجاري */
    fun copyTree(src: File, dst: File, cancel: () -> Boolean, onFile: (File) -> Unit) {
        if (cancel()) return
        if (src.isDirectory) {
            dst.mkdirs()
            val kids = src.listFiles()
            if (kids != null) for (k in kids) copyTree(k, File(dst, k.name), cancel, onFile)
        } else { onFile(src); src.copyTo(dst, false) }
    }

    /** ضغط ZIP لمجموعة ملفات/مجلدات (المسارات جوه الأرشيف نسبية لمجلد كل عنصر) */
    fun zipTo(files: List<File>, out: File, cancel: () -> Boolean, onFile: (String) -> Unit) {
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(out))).use { z ->
            for (root in files) {
                val base = root.parentFile ?: root
                val all = if (root.isDirectory) root.walkTopDown().toList() else listOf(root)
                for (f in all) {
                    if (cancel()) return
                    val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                    if (rel.isEmpty()) continue
                    if (f.isDirectory) { z.putNextEntry(java.util.zip.ZipEntry("$rel/")); z.closeEntry() }
                    else {
                        onFile(f.name)
                        z.putNextEntry(java.util.zip.ZipEntry(rel))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
        }
    }

    /** فك ZIP في مجلد (مع حماية zip-slip). بيرجّع عدد الملفات */
    fun unzip(zip: File, dest: File, cancel: () -> Boolean, onFile: (String) -> Unit): Int {
        var n = 0
        val destPath = dest.canonicalPath
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(zip.inputStream())).use { zi ->
            var e = zi.nextEntry
            while (e != null) {
                if (cancel()) break
                val out = File(dest, e.name)
                val cp = out.canonicalPath
                if (cp != destPath && !cp.startsWith(destPath + File.separator)) throw SecurityException("مسار مش آمن جوه الأرشيف")
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    onFile(out.name)
                    out.outputStream().use { zi.copyTo(it) }
                    n++
                }
                zi.closeEntry()
                e = zi.nextEntry
            }
        }
        return n
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

/**
 * (v310) فايل مانجر شغّال على الملفات مباشرة (صلاحية «كل الملفات»):
 * تصفّح · بحث (عادي وعميق) · صور مصغّرة · تحديد · نسخ/نقل/لصق بتقدّم وإلغاء · ضغط/فك ZIP · إعادة تسمية · مشاركة ·
 * اختصارات مثبّتة ⭐ · شريط المساحة · أكبر الملفات · نسخ المسار
 */
class FileManagerActivity : Activity() {
    private var stripUi: StatusStrip? = null   // (v345)
    private var dockUi: MiniDock? = null   // (v344)
    override fun finish() { super.finish(); try { if (!NavAnim.take(this)) overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Fm:finish", e) } }

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
    private var deepOn = false
    private var deepMode = false
    private var pins = LinkedHashSet<String>()
    private var pendingSel: String? = null
    private var query = ""
    private var loadErr = false
    private var loaded = false
    private var loadToken = 0
    private val uiH = Handler(Looper.getMainLooper())
    // (v391) تبويبات زي MiXplorer: كل تبويب مجلد، وتقدر تسحب ملفات من تبويب لتاني (نقل / استخراج)
    private val tabs = ArrayList<File>()
    private var tabIdx = 0
    private lateinit var tabsRow: LinearLayout
    private var dragFiles: List<File> = emptyList()
    private var tabHoverRun: Runnable? = null   // (v460) الوقوف بالسحب فوق تبويب بيفتحه
    private fun armTabHover(i: Int) {
        disarmTabHover()
        if (i == tabIdx || i !in tabs.indices) return
        val r = Runnable { tabHoverRun = null; if (dragFiles.isNotEmpty() && i != tabIdx && i in tabs.indices) switchTab(i) }
        tabHoverRun = r; uiH.postDelayed(r, 700)
    }
    private fun disarmTabHover() { tabHoverRun?.let { try { uiH.removeCallbacks(it) } catch (_: Exception) {} }; tabHoverRun = null }
    private var fingerDown = false
    private var lastDragPos = -1
    private var swX = 0f; private var swY = 0f; private var swPos = -1
    private val deepRun = Runnable { val q = query.trim(); if (deepOn && q.length >= 2) runDeep(q) }

    private lateinit var titleTv: TextView
    private lateinit var pathTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var list: ListView
    private lateinit var emptyTv: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteTv: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var storTv: TextView
    private lateinit var storFill: View
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

    private fun loadPins() { pins = LinkedHashSet((prefs().getString("fm_pins", "") ?: "").split("\n").filter { it.isNotBlank() }) }
    private fun savePins() { prefs().edit().putString("fm_pins", pins.joinToString("\n")).apply() }

    private fun buildChips() {
        chipsRow.removeAllViews()
        for ((label, f) in roots()) chipsRow.addView(chip(label) { load(f) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        for (p in pins) {
            val c = chip("⭐ " + (File(p).name.ifEmpty { p })) { load(File(p)) }
            c.setOnLongClickListener { pins.remove(p); savePins(); buildChips(); toast("اتشال من الاختصارات"); true }
            chipsRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        th = Themes.byId(try { prefs().getString("theme", "mx") } catch (_: Exception) { "mx" })
        ui = Ui(this, th)
        applyBars(th)
        TvFocus.attach(this, th.primary)   // (v477)
        sortMode = prefs().getInt("fm_sort", 0)
        showHidden = prefs().getBoolean("fm_hidden", false)
        deepOn = prefs().getBoolean("fm_deep", false)
        loadPins()
        val startPath = intent?.getStringExtra("path") ?: prefs().getString("fm_last", null)
        if (startPath != null) { val d = File(startPath); if (d.isDirectory) cur = d }
        run {   // (v391) استرجاع التبويبات
            val saved = (prefs().getString("fm_tabs", "") ?: "").split("\n").filter { it.isNotBlank() }.map { File(it) }.filter { it.isDirectory }
            tabs.addAll(saved)
            tabIdx = prefs().getInt("fm_tab_i", 0)
            if (tabs.isEmpty()) { tabs.add(cur); tabIdx = 0 }
            tabIdx = tabIdx.coerceIn(0, tabs.size - 1)
            if (intent?.getStringExtra("path") != null) tabs[tabIdx] = cur else cur = tabs[tabIdx]
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(th.bg); setPadding(0, ui.dp(28), 0, 0)
        }

        // الهيدر: ✕ · العنوان/المسار · ↑ · ⋮
        titleTv = ui.text("", 16f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        pathTv = ui.text("", 11f, th.muted).apply {
            setSingleLine(); ellipsize = TextUtils.TruncateAt.START; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
            setOnLongClickListener { copyText(cur.absolutePath); true }
        }
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

        // (v391) شريط التبويبات
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow) }, LinearLayout.LayoutParams(-1, -2))

        // بحث داخل المجلد (أو في كل المجلدات الفرعية لو البحث العميق شغّال)
        searchEt = ui.input("🔍 بحث في المجلد", "").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    uiH.removeCallbacks(deepRun)
                    if (deepOn && query.trim().length >= 2) uiH.postDelayed(deepRun, 450)
                    else if (deepMode) { deepMode = false; load(cur) }
                    else refresh()
                }
            })
        }
        root.addView(searchEt, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), 0, ui.dp(12), 0) })

        // اختصارات الأماكن + المجلدات المثبّتة
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)) }
        buildChips()
        val chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) }
        root.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2))

        paintTabs()
        // شريط المساحة
        storTv = ui.text("", 11f, th.muted).apply { setSingleLine() }
        storFill = View(this).apply { setBackgroundColor(th.primary) }
        val storBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; weightSum = 1f; setBackgroundColor(th.border)
            addView(storFill, LinearLayout.LayoutParams(0, -1, 0f))
        }
        root.addView(storTv, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(2)) })
        root.addView(storBar, LinearLayout.LayoutParams(-1, ui.dp(3)).apply { setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(4)) })

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
            Motion.listAnim(this)
            setOnItemClickListener { _, _, pos, _ -> if (pos in shown.indices) onTap(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> if (pos in shown.indices) { toggleSel(shown[pos]); if (sel.contains(shown[pos].f.absolutePath)) armDrag(shown[pos]) }; true }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { fingerDown = true; swX = e.x; swY = e.y; swPos = (v as ListView).pointToPosition(e.x.toInt(), e.y.toInt()) }
                    MotionEvent.ACTION_UP -> {
                        fingerDown = false
                        // (v392) سلايد لليمين على مجلد = يفتح في تبويب لوحده
                        val dx = e.x - swX; val dy = e.y - swY
                        val it = shown.getOrNull(swPos)
                        if (it != null && it.dir && dx > ui.dp(90) && dx > Math.abs(dy) * 2f && !deepMode) openInNewTab(it.f)
                        swPos = -1
                    }
                    MotionEvent.ACTION_CANCEL -> { fingerDown = false; swPos = -1 }
                }
                false
            }
            setOnDragListener { v, ev ->
                when (ev.action) {
                    DragEvent.ACTION_DRAG_STARTED -> dragFiles.isNotEmpty()
                    DragEvent.ACTION_DRAG_LOCATION -> { lastDragPos = (v as ListView).pointToPosition(ev.x.toInt(), ev.y.toInt()); true }
                    DragEvent.ACTION_DROP -> {
                        val pos = (v as ListView).pointToPosition(ev.x.toInt(), ev.y.toInt())
                        val t = shown.getOrNull(pos)
                        if (t != null && t.dir) dropOn(t.f) else toast("سيبه على مجلد أو على تبويب")
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> { dragFiles = emptyList(); disarmTabHover(); paintTabs(); true }   // (v460) رسم التبويبات المؤجل أثناء السحب
                    else -> true
                }
            }
        }
        emptyTv = ui.text("", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(40), 0, 0); visibility = View.GONE }
        val body = FrameLayout(this)
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyTv, FrameLayout.LayoutParams(-1, -1))
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
        // (v313) شريط البوابات السفلي (صغير) زي باقي الشاشات: الفيديوهات · الموسيقى · المتصفح · الملفات (إحنا فيه) · (v324) يوتيوب
        val gates = BottomNav(this, ui, th, 3) { i ->
            if (i != 3) {
                NavAnim.set(3, i)
                if (i == 0 && TvFocus.on(this) && TvFocus.goHome(this)) { /* (v482) تلفزيون: الرئيسية = شاشة التلفزيون */ }
                else if (i == 2) startActivity(Intent(this, BrowserActivity::class.java))
                else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", if (i == 0) "videos" else if (i == 1) "music" else if (i == 4) "youtube" else if (i == 7) "settings" else "tasks"))
                NavAnim.peek(this)
                finish()
            }
        }
        root.addView(gates.view, LinearLayout.LayoutParams(-1, -2))

        // (v324) زرار المهام العايم (دايرة حمراء بالعدد) فوق القايمة؛ دوسة عليه بتفتح صفحة المهام في الرئيسية
        val host = FrameLayout(this)
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
        if (DeviceUi.isTablet(this)) buildDetailPane(host)   // (v462)
        // (v336) زرار المهام العايم اتشال — المهام في الشريط السفلي
        setContentView(host)
        fab = TasksFab(this, host, ui) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "tasks")) }   // (v420)
        dockUi = MiniDock(this, host, ui, th, { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("fullmusic", true)) })   // (v344)
        stripUi = StatusStrip(this, host, ui, th) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "tasks")) }   // (v345)
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

    private var fab: TasksFab? = null
    override fun onDestroy() { try { dockUi?.detach() } catch (_: Throwable) {}; try { stripUi?.detach() } catch (_: Throwable) {}; loadToken++; uiH.removeCallbacksAndMessages(null); try { fab?.detach() } catch (_: Throwable) {}; super.onDestroy() }

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
    private var fmLoaded = false
    private fun load(dir: File) {
        val deeper = dir != cur && dir.absolutePath.startsWith(cur.absolutePath)
        cur = dir; sel.clear(); query = ""; deepMode = false
        if (tabs.isNotEmpty()) { tabs[tabIdx.coerceIn(0, tabs.size - 1)] = dir; saveTabs(); paintTabs() }
        uiH.removeCallbacks(deepRun)
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
                if (!isDestroyed && token == loadToken) {
                    all = res; loadErr = arr == null; refresh(); list.setSelection(0); updateStorage()
                    if (fmLoaded) { Motion.slideIn(list, if (deeper) -1 else 1, 230L); Motion.replay(list) } else fmLoaded = true   // (v313) انتقال فولدر لفولدر
                    val ps = pendingSel; pendingSel = null
                    if (ps != null) {
                        val idx = shown.indexOfFirst { it.f.absolutePath == ps }
                        if (idx >= 0) { sel.add(ps); refresh(); list.setSelection(idx) }
                    }
                }
            }
        }.start()
    }

    /** بحث عميق: كل الملفات/المجلدات اللي اسمها فيه النص، تحت المجلد الحالي (لحد 3000 نتيجة) */
    private fun runDeep(q: String) {
        val token = ++loadToken
        val root = cur
        val ql = q.lowercase()
        emptyTv.text = "⏳ بدوّر في كل المجلدات…"; emptyTv.visibility = View.VISIBLE
        Thread {
            val res = ArrayList<FmItem>()
            try {
                val walk = root.walkTopDown().onEnter { d -> d == root || showHidden || !d.name.startsWith(".") }
                for (f in walk.takeWhile { token == loadToken && res.size < 3000 }) {
                    if (f == root) continue
                    if (!showHidden && f.name.startsWith(".")) continue
                    if (f.name.lowercase().contains(ql)) {
                        val sub = try { f.parentFile?.relativeTo(root)?.path ?: "" } catch (_: Exception) { "" }
                        res.add(FmItem(f, f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified(), 0, sub))
                    }
                }
            } catch (e: Exception) { LogStore.err("Fm:deep", e) }
            runOnUiThread {
                if (!isDestroyed && token == loadToken) { all = res; deepMode = true; loadErr = false; refresh(); list.setSelection(0) }
            }
        }.start()
    }

    private fun updateStorage() {
        try {
            val st = StatFs(cur.absolutePath)
            val total = st.blockCountLong * st.blockSizeLong
            val free = st.availableBlocksLong * st.blockSizeLong
            storTv.text = "💾 المتاح ${FmUtil.size(free)} من ${FmUtil.size(total)}"
            val frac = if (total > 0) ((total - free).toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
            (storFill.layoutParams as LinearLayout.LayoutParams).weight = frac
            storFill.setBackgroundColor(if (frac > 0.9f) th.danger else th.primary)
            storFill.requestLayout()
        } catch (_: Exception) { storTv.text = "" }
    }

    private fun refresh() {
        val q = query.trim().lowercase()
        val filtered = if (deepMode) all else all.filter { (showHidden || !it.name.startsWith(".")) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        val byName = compareBy<FmItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val cmp: Comparator<FmItem> = when (sortMode) {
            1 -> compareByDescending<FmItem> { it.mod }
            2 -> compareByDescending<FmItem> { it.size }
            3 -> compareBy<FmItem, String>(String.CASE_INSENSITIVE_ORDER) { it.f.extension }.then(byName)
            else -> byName
        }
        shown = filtered.sortedWith(compareByDescending<FmItem> { it.dir }.then(cmp))
        adapter.notifyDataSetChanged()
        val ext = Environment.getExternalStorageDirectory().absolutePath
        titleTv.text = if (sel.isNotEmpty()) "✔ ${sel.size} محدد" else if (deepMode) "🔎 ${shown.size} نتيجة" else if (cur.absolutePath == ext) "الذاكرة الداخلية" else cur.name.ifEmpty { "/" }
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
    private class Holder { lateinit var ic: TextView; lateinit var iv: ImageView; lateinit var name: TextView; lateinit var info: TextView }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val ctx = this@FileManagerActivity
            val h: Holder
            val row: View
            if (cv == null) {
                h = Holder()
                h.ic = IconTextView(ctx).apply { textSize = 24f; gravity = Gravity.CENTER }
                h.iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
                val box = FrameLayout(ctx)
                box.addView(h.ic, FrameLayout.LayoutParams(-1, -1))
                box.addView(h.iv, FrameLayout.LayoutParams(-1, -1))
                h.name = ui.text("", 14f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
                h.info = ui.text("", 11f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START }
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(h.name); addView(h.info) }
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    addView(box, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
                    addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(10), 0, 0, 0) })
                }
                row.tag = h
            } else { row = cv; h = cv.tag as Holder }
            val fi = shown[p]
            val on = sel.contains(fi.f.absolutePath)
            val e = fi.f.extension.lowercase()
            val thumb = !fi.dir && !on && (e in FmUtil.IMAGE || e in FmUtil.VIDEO)
            if (thumb) {
                h.iv.visibility = View.VISIBLE
                try { Glide.with(ctx).load(fi.f).centerCrop().override(ui.dp(88), ui.dp(88)).into(h.iv) } catch (_: Exception) {}
            } else {
                try { Glide.with(ctx).clear(h.iv) } catch (_: Exception) {}
                h.iv.setImageDrawable(null); h.iv.visibility = View.GONE
            }
            h.ic.text = if (on) "✅" else FmUtil.icon(fi)
            h.name.text = fi.name
            h.name.gravity = Gravity.START; h.info.gravity = Gravity.START
            val date = fmt.format(Date(fi.mod))
            val base = if (fi.dir) "${fi.count} عنصر  ·  $date" else "${FmUtil.size(fi.size)}  ·  $date"
            h.info.text = if (deepMode) "📂 ${fi.sub.ifEmpty { "." }}  ·  $base" else base
            row.setBackgroundColor(if (on) ((th.primary and 0x00FFFFFF) or 0x33000000) else 0)
            return row
        }
    }

    // (v462) لوحة تفاصيل جانبية في وضع التابلت (Master-Detail): بتتحدث مع كل دوسة على عنصر
    private var detailPane: LinearLayout? = null
    private var detailName: TextView? = null
    private var detailMeta: TextView? = null
    private var detailFile: File? = null
    private fun buildDetailPane(host: FrameLayout) {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE
            setBackgroundColor(th.card); setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); elevation = ui.dp(10).toFloat()
        }
        val nm = ui.text("", 16f, th.text, true); detailName = nm; pane.addView(nm)
        val mt = ui.text("", 12.5f, th.muted); detailMeta = mt; pane.addView(mt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        fun dbtn(label: String, fn: () -> Unit) {
            val b = ui.text(label, 14f, th.text, true).apply {
                setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11)); background = ui.box(th.surface, th.border, 12); Glass.pressable(this)
                setOnClickListener { detailFile?.let { f0 -> fn() } }
            }
            pane.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        dbtn("▶ فتح") { val f0 = detailFile!!; if (f0.isDirectory) load(f0) else openFile(f0, false) }
        dbtn("↗ مشاركة") { sel.clear(); sel.add(detailFile!!.absolutePath); shareSel() }
        dbtn("ℹ تفاصيل") { details(detailFile!!) }
        dbtn("✏ إعادة تسمية") { rename(detailFile!!) }
        dbtn("🗑 حذف") { sel.clear(); sel.add(detailFile!!.absolutePath); deleteSel() }
        dbtn("✕ إغلاق اللوحة") { detailPane?.visibility = View.GONE }
        host.addView(pane, FrameLayout.LayoutParams(ui.dp(290), -1, Gravity.END))
        detailPane = pane
    }
    private fun showDetail(fi: FmItem) {
        val pane = detailPane ?: return
        detailFile = fi.f; pane.visibility = View.VISIBLE
        detailName?.text = (if (fi.dir) "📁 " else "📄 ") + fi.f.name
        detailMeta?.text = if (fi.dir) "فولدر · " + (fi.f.list()?.size ?: 0) + " عنصر\n" + fi.f.absolutePath
            else FmUtil.size(fi.f.length()) + " · " + java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(fi.f.lastModified())) + "\n" + fi.f.absolutePath
    }

    private fun onTap(fi: FmItem) {
        showDetail(fi)
        if (sel.isNotEmpty()) { toggleSel(fi); return }
        if (fi.dir) load(fi.f) else openFile(fi.f, false)
    }

    // ===== (v391) تبويبات =====
    private fun saveTabs() { try { prefs().edit().putString("fm_tabs", tabs.joinToString("\n") { it.absolutePath }).putInt("fm_tab_i", tabIdx).apply() } catch (_: Exception) {} }
    private fun tabName(f: File): String = if (f.absolutePath == Environment.getExternalStorageDirectory().absolutePath) "📱 الداخلية" else "📁 " + f.name.ifEmpty { "/" }
    private fun paintTabs() {
        if (!::tabsRow.isInitialized) return
        if (dragFiles.isNotEmpty()) return   // (v460) أثناء السحب: إعادة بناء التبويبات بتقتل السحب — الرسم الكامل بيحصل بعد DRAG_ENDED
        tabsRow.removeAllViews()
        for ((i, f) in tabs.withIndex()) {
            val on = i == tabIdx
            val c = IconTextView(this).apply {
                text = tabName(f).take(18); textSize = 12.5f; setTextColor(if (on) th.primary else th.text); gravity = Gravity.CENTER
                setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
                background = ui.box(if (on) ((th.primary and 0x00FFFFFF) or 0x33000000) else th.surface, if (on) th.primary else th.border, 18)
                Glass.pressable(this)
                setOnClickListener { if (i != tabIdx) switchTab(i) }
                setOnLongClickListener { closeTab(i); true }
                // الإفلات على تبويب = نقل/استخراج لمجلده
                setOnDragListener { v, ev ->
                    when (ev.action) {
                        DragEvent.ACTION_DRAG_STARTED -> dragFiles.isNotEmpty()
                        DragEvent.ACTION_DRAG_ENTERED -> { v.scaleX = 1.12f; v.scaleY = 1.12f; armTabHover(i); true }   // (v460) وقوف 0.7ث بيفتح التبويب
                        DragEvent.ACTION_DRAG_EXITED -> { v.scaleX = 1f; v.scaleY = 1f; disarmTabHover(); true }
                        DragEvent.ACTION_DROP -> { v.scaleX = 1f; v.scaleY = 1f; disarmTabHover(); dropOn(tabs.getOrNull(i) ?: cur); true }
                        DragEvent.ACTION_DRAG_ENDED -> { v.scaleX = 1f; v.scaleY = 1f; disarmTabHover(); true }
                        else -> true
                    }
                }
            }
            tabsRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        tabsRow.addView(chip("＋") { addTab() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
    }
    /** (v392) فتح مجلد في تبويب جديد جنب التبويب الحالي */
    private fun openInNewTab(f: File) {
        if (tabs.size >= 8) { toast("أقصى عدد تبويبات 8"); return }
        tabs.add(tabIdx + 1, f); tabIdx++; saveTabs()
        toast("📂 اتفتح في تبويب جديد: " + f.name)
        load(f)
    }
    private fun switchTab(i: Int) { tabIdx = i.coerceIn(0, tabs.size - 1); saveTabs(); load(tabs[tabIdx]) }
    private fun addTab() {
        if (tabs.size >= 8) { toast("أقصى عدد تبويبات 8"); return }
        tabs.add(tabIdx + 1, cur); tabIdx++; saveTabs(); paintTabs(); toast("تبويب جديد — افتح فيه المجلد التاني")
    }
    private fun closeTab(i: Int) {
        if (tabs.size <= 1) { toast("ده آخر تبويب"); return }
        GAlert(this).setTitle("إغلاق التبويب").setMessage(tabName(tabs[i]))
            .setPositiveButton("إغلاق") { _, _ ->
                tabs.removeAt(i)
                if (tabIdx >= tabs.size) tabIdx = tabs.size - 1 else if (i < tabIdx) tabIdx--
                saveTabs(); load(tabs[tabIdx])
            }.setNegativeButton("إلغاء", null).show()
    }

    // ===== (v391) سحب وإفلات: ضغطة مطولة = تحديد، كمّل ضغط = الملف يتشال ويكبر ويتحرك =====
    private fun armDrag(fi: FmItem) {
        uiH.postDelayed({
            if (isDestroyed || !fingerDown || !sel.contains(fi.f.absolutePath)) return@postDelayed
            val idx = shown.indexOf(fi); if (idx < 0) return@postDelayed
            val row = list.getChildAt(idx - list.firstVisiblePosition) ?: return@postDelayed
            dragFiles = selFiles()
            try { row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
            val shadow = object : View.DragShadowBuilder(row) {
                override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
                    val w = (row.width * 1.12f).toInt(); val h = (row.height * 1.12f).toInt()
                    size.set(w, h); touch.set(w / 2, h / 2)
                }
                override fun onDrawShadow(canvas: android.graphics.Canvas) { canvas.scale(1.12f, 1.12f); row.draw(canvas) }
            }
            val data = android.content.ClipData.newPlainText("fm", dragFiles.joinToString("\n") { it.absolutePath })
            val ok = try { row.startDragAndDrop(data, shadow, null, 0) } catch (_: Throwable) { false }
            if (!ok) dragFiles = emptyList()
        }, 450L)
    }
    private fun isZip(f: File) = f.isFile && f.extension.equals("zip", true)
    /** الإفلات على مجلد (تبويب أو صف مجلد): نقل — ولو فيه ZIP يتعرض كمان «استخراج» */
    private fun dropOn(dest: File) {
        val files = dragFiles; dragFiles = emptyList()
        if (files.isEmpty() || !dest.isDirectory) return
        val movable = files.filter { s -> !(s.isDirectory && (dest.absolutePath == s.absolutePath || dest.absolutePath.startsWith(s.absolutePath + "/"))) }
        if (movable.isEmpty()) { toast("مينفعش تنقل مجلد جوه نفسه"); return }
        val zips = movable.filter { isZip(it) }
        val allSame = movable.all { it.parentFile?.absolutePath == dest.absolutePath }
        val what = if (movable.size == 1) movable[0].name else "${movable.size} عنصر"
        val dlg = GAlert(this).setTitle("إلى: " + (dest.name.ifEmpty { "/" })).setMessage(what + (if (zips.isNotEmpty()) "\n(فيه ${zips.size} ZIP: تقدر تستخرجه في المجلد ده)" else ""))
            .setPositiveButton(if (allSame) "نفس المكان" else "✂ نقل") { _, _ -> if (!allSame) moveInto(movable, dest, false) }
        if (zips.isNotEmpty()) dlg.setNeutralButton("📦 استخراج") { _, _ -> moveInto(movable, dest, true) }
        dlg.setNegativeButton("إلغاء", null).show()
    }
    /** نقل [src] لـ [dest]؛ extract=true: أي ZIP بيتفك في مجلد جديد جوه [dest] (الأصل بيفضل مكانه) والباقي بيتنقل */
    private fun moveInto(src: List<File>, dest: File, extract: Boolean) {
        busy(if (extract) "📦 استخراج / نقل" else "✂ نقل") { upd, cancelled ->
            var ok = 0; var fail = 0; var i = 0
            val made = ArrayList<File>()
            for (s in src) {
                if (cancelled()) break
                i++; val n = i
                upd("($n/${src.size}) ${s.name}")
                try {
                    if (extract && isZip(s)) {
                        val d = FmUtil.uniq(dest, s.nameWithoutExtension.ifEmpty { "extracted" })
                        d.mkdirs()
                        FmUtil.unzip(s, d, cancelled) { f -> upd("($n/${src.size}) $f") }
                        made.addAll(d.walkTopDown().filter { it.isFile }.toList()); ok++
                    } else if (s.parentFile?.absolutePath == dest.absolutePath) { ok++ }
                    else {
                        val d = FmUtil.uniq(dest, s.name)
                        if (!s.renameTo(d)) {
                            FmUtil.copyTree(s, d, cancelled) { f -> upd("($n/${src.size}) ${f.name}") }
                            if (cancelled()) d.deleteRecursively() else s.deleteRecursively()
                        }
                        if (!cancelled()) { made.add(d); ok++ }
                    }
                } catch (e: Exception) { LogStore.err("Fm:drop", e); fail++ }
            }
            FmUtil.scan(this, made)
            "✓ تم: $ok" + (if (fail > 0) "  ·  فشل: $fail" else "") + (if (cancelled()) "  ·  اتلغى" else "")
        }
    }

    private fun toggleSel(fi: FmItem) {
        val p = fi.f.absolutePath
        if (!sel.remove(p)) sel.add(p)
        refresh()
    }

    private fun selFiles(): List<File> = sel.map { File(it) }

    private fun openFile(f: File, external: Boolean) {
        val e = f.extension.lowercase()
        // (v417) ملف صوت ← مشغّل الموسيقى (كان بيفتح في مشغّل الفيديو)؛ المشغّل المصغّر العايم بيظهر هنا وبيفتح الكامل
        if (!external && e in FmUtil.AUDIO) { Links.playAudioHere(this, Uri.fromFile(f).toString(), f.nameWithoutExtension); toast("🎵 " + f.name); return }
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

    private fun copyText(t: String) {
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("path", t))
            toast("📋 اتنسخ")
        } catch (e: Exception) { LogStore.err("Fm:clip", e) }
    }

    // ===== القايمة ⋮ =====
    private fun menu() {
        val sorts = arrayOf("الاسم", "الأحدث", "الأكبر حجماً", "النوع")
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        add("📁 مجلد جديد") { newFolder() }
        add("↕ الترتيب: ${sorts[sortMode.coerceIn(0, 3)]}") {
            GAlert(this).setTitle("ترتيب حسب").setItems(sorts) { _, s ->
                sortMode = s; prefs().edit().putInt("fm_sort", s).apply(); refresh()
            }.show()
        }
        add(if (showHidden) "👁 إخفاء الملفات المخفية" else "👁 إظهار الملفات المخفية") {
            showHidden = !showHidden; prefs().edit().putBoolean("fm_hidden", showHidden).apply(); refresh()
        }
        add("☑ تحديد الكل") { shown.forEach { sel.add(it.f.absolutePath) }; refresh() }
        val here = cur.absolutePath
        add(if (here in pins) "⭐ شيل المجلد ده من الاختصارات" else "⭐ ثبّت المجلد ده في الاختصارات") {
            if (!pins.remove(here)) pins.add(here)
            savePins(); buildChips()
        }
        add(if (deepOn) "🔎 البحث العميق شغّال — اضغط لإيقافه" else "🔎 فعّل البحث في المجلدات الفرعية") {
            deepOn = !deepOn; prefs().edit().putBoolean("fm_deep", deepOn).apply()
            searchEt.hint = if (deepOn) "🔎 بحث في كل المجلدات تحت ده" else "🔍 بحث في المجلد"
            if (!deepOn && deepMode) { deepMode = false; load(cur) }
            toast(if (deepOn) "البحث بيدوّر في المجلدات الفرعية كمان" else "البحث في المجلد الحالي بس")
        }
        add("📊 أكبر الملفات هنا") { bigFiles() }
        add("📋 نسخ مسار المجلد") { copyText(cur.absolutePath) }
        add("🔄 تحديث") { load(cur) }
        GAlert(this).setTitle("الفايل مانجر").setItems(labels.toTypedArray()) { _, w -> if (w in acts.indices) acts[w]() }.show()
    }

    private fun newFolder() {
        promptName("📁 مجلد جديد", "", "إنشاء") { n ->
            when {
                n.isEmpty() || n.contains("/") -> toast("الاسم مش صح")
                File(cur, n).exists() -> toast("الاسم موجود قبل كده")
                File(cur, n).mkdirs() -> load(cur)
                else -> toast("⚠ ما قدرتش أعمل المجلد")
            }
        }
    }

    private fun promptName(title: String, init: String, ok: String, onOk: (String) -> Unit) {
        val et = ui.input("الاسم", init)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(4)); addView(et) }
        GAlert(this).setTitle(title).setView(c)
            .setPositiveButton(ok) { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("إلغاء", null).show()
    }

    /** نافذة تقدّم بزرار إلغاء: الشغل بيتعمل في خيط لوحده، والنتيجة (نص) بتتعرض كإشعار وبعدها المجلد بيتحدّث */
    private fun busy(title: String, work: (upd: (String) -> Unit, cancelled: () -> Boolean) -> String) {
        val tv = ui.text("⏳ …", 14f, th.text).apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(8)) }
        var cancel = false
        val dlg = GAlert(this).setTitle(title).setView(tv).setCancelable(false)
            .setNegativeButton("إلغاء") { _, _ -> cancel = true }.show()
        Thread {
            val msg = try { work({ t -> runOnUiThread { tv.text = t } }, { cancel }) }
            catch (e: Exception) { LogStore.err("Fm:busy", e); "⚠ حصلت مشكلة: ${e.message ?: ""}" }
            runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                if (!isDestroyed) { toast(msg); load(cur) }
            }
        }.start()
    }

    // ===== عمليات التحديد =====
    private fun setClip(cut: Boolean) {
        clip = selFiles(); clipCut = cut; sel.clear(); refresh()
        toast("اتحفظ — روح للمجلد المطلوب واضغط «الصق هنا»")
    }

    private fun paste() {
        val dest = cur; val src = clip; val cut = clipCut
        clip = emptyList(); refresh()
        busy(if (cut) "✂ نقل" else "📋 نسخ") { upd, cancelled ->
            var ok = 0; var fail = 0; var i = 0
            val made = ArrayList<File>()
            for (s in src) {
                if (cancelled()) break
                i++
                upd("($i/${src.size}) ${s.name}")
                try {
                    val same = s.parentFile?.absolutePath == dest.absolutePath
                    val inside = s.isDirectory && (dest.absolutePath == s.absolutePath || dest.absolutePath.startsWith(s.absolutePath + "/"))
                    if (inside) { fail++ }
                    else if (cut && same) { ok++ }
                    else {
                        val d = FmUtil.uniq(dest, s.name)
                        val n = i
                        if (cut) {
                            if (!s.renameTo(d)) {
                                FmUtil.copyTree(s, d, cancelled) { f -> upd("($n/${src.size}) ${f.name}") }
                                if (cancelled()) d.deleteRecursively() else s.deleteRecursively()
                            }
                        } else FmUtil.copyTree(s, d, cancelled) { f -> upd("($n/${src.size}) ${f.name}") }
                        if (!cancelled()) { made.add(d); ok++ }
                    }
                } catch (e: Exception) { LogStore.err("Fm:paste", e); fail++ }
            }
            FmUtil.scan(this, made)
            "✓ تم: $ok" + (if (fail > 0) "  ·  فشل: $fail" else "") + (if (cancelled()) "  ·  اتلغى" else "")
        }
    }

    private fun deleteSel() {
        val files = selFiles()
        if (files.isEmpty()) return
        val msg = if (files.size == 1) "تمسح «${files[0].name}» نهائياً؟" else "تمسح ${files.size} عنصر نهائياً؟"
        GAlert(this).setTitle("🗑 حذف").setMessage(msg + "\nمش هتقدر ترجّعه.")
            .setPositiveButton("امسح") { _, _ ->
                busy("🗑 حذف") { upd, cancelled ->
                    var ok = 0; var fail = 0
                    for (f in files) {
                        if (cancelled()) break
                        upd(f.name)
                        try { if (f.deleteRecursively()) ok++ else fail++ } catch (e: Exception) { LogStore.err("Fm:del", e); fail++ }
                    }
                    FmUtil.scan(this, files)
                    "✓ اتمسح: $ok" + (if (fail > 0) "  ·  فشل: $fail" else "") + (if (cancelled()) "  ·  اتلغى" else "")
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun shareSel() {
        val all = selFiles()
        if (all.isEmpty()) return
        val files = all.filter { it.isFile }
        if (files.size < all.size) { shareZipped(all); return }   // (v460) فولدر في التحديد: ضغط ومشاركة (كان ممنوع)
        try {
            val uris = ArrayList<Uri>(files.map { FmUtil.uriFor(this, it) })
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(FmUtil.mime(files[0]))
                    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "مشاركة"))
        } catch (e: Exception) { LogStore.err("Fm:share", e); toast("⚠ ما قدرتش أشارك") }
    }

    /** (v460) مشاركة فولدر/تحديد مختلط: ضغط ZIP مؤقت في الكاش (نفس zipTo بتاع «ضغط ZIP») ثم مشاركته كملف */
    private fun shareZipped(items: List<File>) {
        var cancelled = false
        val tv = ui.text("⏳ بنجهّز المشاركة…", 14f, th.text).apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(8)) }
        val dlg = GAlert(this).setTitle("↗ مشاركة").setView(tv).setCancelable(false)
            .setNegativeButton("إلغاء") { _, _ -> cancelled = true }.show()
        Thread {
            var out: File? = null
            try {
                val dirF = File(cacheDir, "fmshare").apply { mkdirs() }
                dirF.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
                val nm = if (items.size == 1) items[0].name.ifEmpty { "مشاركة" } + ".zip" else "مشاركة.zip"
                val o = File(dirF, nm)
                FmUtil.zipTo(items, o, { cancelled }) { n -> runOnUiThread { tv.text = "🗜 بنضغط: $n" } }
                if (!cancelled && o.exists() && o.length() > 0) out = o
            } catch (e: Exception) { LogStore.err("Fm:shareZip", e) }
            runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                val o = out
                if (o == null) { if (!isDestroyed && !cancelled) toast("⚠ ما قدرتش أجهّز المشاركة"); return@runOnUiThread }
                try {
                    val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, FmUtil.uriFor(this, o)).setType("application/zip")
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "مشاركة"))
                } catch (e: Exception) { LogStore.err("Fm:shareZip", e); toast("⚠ ما قدرتش أشارك") }
            }
        }.start()
    }

    private fun moreSel() {
        val files = selFiles()
        if (files.isEmpty()) return
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        if (files.size == 1) {
            val f = files[0]
            add("✏ إعادة تسمية") { rename(f) }
            add("ℹ تفاصيل") { details(f) }
            if (f.isFile) add("↗ فتح ببرنامج تاني") { openFile(f, true) }
            if (f.isFile && f.extension.equals("zip", true)) add("📦 فك الضغط هنا") { unzipHere(f) }
            if (deepMode) add("📂 افتح المجلد اللي فيه") { reveal(f) }
            add("📋 نسخ المسار") { copyText(f.absolutePath) }
        }
        add("🗜 ضغط ZIP") { zipSel(files) }
        add("☑ تحديد الكل") { shown.forEach { sel.add(it.f.absolutePath) }; refresh() }
        add("↔ عكس التحديد") { shown.forEach { val p = it.f.absolutePath; if (!sel.remove(p)) sel.add(p) }; refresh() }
        add("✖ إلغاء التحديد") { sel.clear(); refresh() }
        GAlert(this).setTitle(if (files.size == 1) files[0].name else "${files.size} عنصر").setItems(labels.toTypedArray()) { _, w -> if (w in acts.indices) acts[w]() }.show()
    }

    private fun reveal(f: File) {
        val p = f.parentFile ?: return
        pendingSel = f.absolutePath
        load(p)
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

    private fun zipSel(files: List<File>) {
        val def = if (files.size == 1) files[0].nameWithoutExtension.ifEmpty { files[0].name } + ".zip" else "أرشيف.zip"
        promptName("🗜 ضغط ZIP", def, "اضغط") { n ->
            if (n.isEmpty() || n.contains("/")) { toast("الاسم مش صح"); return@promptName }
            val out = FmUtil.uniq(cur, if (n.endsWith(".zip", true)) n else "$n.zip")
            busy("🗜 ضغط") { upd, cancelled ->
                FmUtil.zipTo(files, out, cancelled) { name -> upd(name) }
                if (cancelled()) { out.delete(); "اتلغى" }
                else { FmUtil.scan(this, listOf(out)); "✓ اتعمل ${out.name} (${FmUtil.size(out.length())})" }
            }
        }
    }

    private fun unzipHere(z: File) {
        val dest = FmUtil.uniq(z.parentFile ?: cur, z.nameWithoutExtension.ifEmpty { "extracted" })
        busy("📦 فك الضغط") { upd, cancelled ->
            dest.mkdirs()
            val n = FmUtil.unzip(z, dest, cancelled) { name -> upd(name) }
            FmUtil.scan(this, dest.walkTopDown().filter { it.isFile }.toList())
            if (cancelled()) "اتلغى (اتفكّ $n ملف)" else "✓ اتفكّ $n ملف ← ${dest.name}"
        }
    }

    private fun bigFiles() {
        val root = cur
        toast("⏳ بدوّر على أكبر الملفات…")
        Thread {
            val top = ArrayList<File>()
            try {
                val walk = root.walkTopDown().onEnter { d -> d == root || showHidden || !d.name.startsWith(".") }
                for (f in walk) {
                    if (!f.isFile) continue
                    top.add(f)
                    if (top.size > 80) { top.sortByDescending { it.length() }; while (top.size > 30) top.removeAt(top.size - 1) }
                }
            } catch (e: Exception) { LogStore.err("Fm:big", e) }
            top.sortByDescending { it.length() }
            val best = top.take(30)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (best.isEmpty()) { toast("مفيش ملفات هنا"); return@runOnUiThread }
                GAlert(this).setTitle("📊 أكبر الملفات").setItems(best.map { "${FmUtil.size(it.length())}  ·  ${it.name}" }.toTypedArray()) { _, w ->
                    if (w in best.indices) reveal(best[w])
                }.show()
            }
        }.start()
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
