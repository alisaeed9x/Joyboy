package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.io.File
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * الشاشة الرئيسية: متصفح فيديوهات الجهاز زي MX Player.
 * المستوى الأول: فولدرات في عمود. المستوى التاني: فيديوهات الفولدر بصورة مصغّرة من الفيديو نفسه + ترتيب (الاسم/الأحدث/الأقدم/الحجم).
 */
class LibraryUi(
    private val act: Activity, private val ui: Ui, private val th: Theme,
    private val recents: () -> Map<String, Recent>,
    /** (v194) true = نفس الصفحة بالظبط بس لملفات الصوت (صفحة الموسيقى) */
    private val audio: Boolean = false,
    private val onPlay: (VideoItem) -> Unit
) {
    private val K = if (audio) "mus_" else ""
    private val noun = if (audio) "أغنية" else "فيديو"
    var onPlayFolder: (FolderItem) -> Unit = {}
    /** (v344) بحث محلي فاضي: اقتراحات يوتيوب (دوسة = تشغيل) ثم «ابحث في المتصفح» */
    var onYtPick: ((NpItem) -> Unit)? = null
    var onWebSearch: ((String) -> Unit)? = null
    var onUniSearch: ((String) -> Unit)? = null   // (v345) 🔎 بحث شامل
    /** (v345) نتايج البحث المحلي (من غير المخفي) — للبحث الشامل */
    fun searchHits(q: String): List<VideoItem> = try { VideoLib.search(visible(), q) } catch (_: Throwable) { emptyList() }
    fun playItem(v: VideoItem) = onPlay(v)
    private val ytBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(16)) }
    private val ytScroll = ScrollView(act).apply { visibility = View.GONE; isVerticalScrollBarEnabled = false; addView(ytBox, FrameLayout.LayoutParams(-1, -2)) }
    private var ytTok = 0
    private var ytLastQ = ""
    private val ytH = android.os.Handler(android.os.Looper.getMainLooper())
    private fun hideYtFallback() { ytTok++; if (ytScroll.visibility != View.GONE) ytScroll.visibility = View.GONE }
    private fun showYtFallback() {
        val q = query; val tok = ++ytTok
        ytScroll.visibility = View.VISIBLE
        YtFallback.pending(ui, th, ytBox, q)
        // مهلة صغيرة عشان مانبحثش في يوتيوب مع كل حرف بتكتبه
        ytH.postDelayed({
            if (tok != ytTok || query != q || act.isFinishing || act.isDestroyed) return@postDelayed
            YtFallback.fill(act, ui, th, ytBox, q, { tok != ytTok || query != q }, { n -> onYtPick?.invoke(n) }, { s -> onWebSearch?.invoke(s) })
        }, 700L)
    }
    /** القايمة المعروضة دلوقتي (بترتيبها) — طابور التشغيل للموسيقى */
    fun shownVideos(): List<VideoItem> = rows.filterIsInstance<VideoItem>()
    var onRefresh: () -> Unit = {}
    var onGrant: () -> Unit = {}
    var onSettings: (View) -> Unit = {}
    var onLink: () -> Unit = {}
    var onPick: () -> Unit = {}
    var onPull: () -> Unit = {}
    /** ترجمة في الخلفية: من القايمة (⋮) على الفيديو أو الفولدر */
    var onBg: (VideoItem) -> Unit = {}
    var onBgStop: (VideoItem) -> Unit = {}
    var onBgFolder: (FolderItem) -> Unit = {}
    var onQueue: () -> Unit = {}
    var onShareMany: (List<VideoItem>) -> Unit = {}
    var onPlayWeb: (WebVid) -> Unit = {}
    var onYtHistory: () -> Unit = {}   // (v407) فتح صفحة «سجل يوتيوب» المنفصلة
    private lateinit var queueBtn: TextView
    var onRename: (VideoItem) -> Unit = {}
    var onMove: (VideoItem) -> Unit = {}
    var onDetails: (VideoItem) -> Unit = {}
    var onShare: (VideoItem) -> Unit = {}
    var onDelete: (VideoItem) -> Unit = {}
    var onRenameFolder: (FolderItem) -> Unit = {}
    var onDeleteFolder: (FolderItem) -> Unit = {}
    fun allFolders(): List<FolderItem> = VideoLib.group(visible())
    var bgJob: (VideoItem) -> BgJob? = { null }
    fun refreshRows() {
        // ظهور/اختفاء صف «مجلد الترجمة في الخلفية» في الرئيسية
        val hasBg = rows.any { it === bgFolder }
        if (!audio && mode == "ready" && curFolder == null && !inHidden && query.isEmpty() && hasBg != BgJobs.jobs.isNotEmpty()) render()
        (listV.adapter as? BaseAdapter)?.notifyDataSetChanged()
        if (::queueBtn.isInitialized) { val n = BgJobs.jobs.count { it.active }; queueBtn.text = if (n > 0) "📋$n" else "📋"; queueBtn.textSize = if (n > 0) 13f else 17f }
    }
    private fun dots(f: (View) -> Unit) = IconTextView(act).apply {
        text = "⋮"; textSize = 22f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        setOnClickListener { f(this) }
    }
    private fun popup(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val pm = PopupMenu(act, anchor)
        items.forEachIndexed { i, it -> pm.menu.add(0, i, i, Icons.convert(it.first)) }
        pm.setOnMenuItemClickListener { m -> items[m.itemId].second(); true }
        pm.show()
    }

    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private var all: List<VideoItem> = emptyList()
    private var curFolder: String? = null
    private var mode = "scan"            // scan | perm | ready
    private var rows: List<Any> = emptyList()
    private var recMap: Map<String, Recent> = emptyMap()
    private var rootPos = 0
    private var query = ""
    // ===== (v299) التوزيع (ذكي / فولدرات / الكل) + وضع الشبكة =====
    private val DISTS = listOf("folders" to "📁 فولدرات", "flat" to "🗂 الكل")   // (v433) الوضع الذكي اتشال
    private var nodeIdx: Map<String, FolderItem> = emptyMap()
    private var gridOn: Boolean? = null
    private lateinit var gridBtn: TextView
    // ===== فولدر «المصطادة من الإنترنت» (بلون مميز) + التحديد المتعدد =====
    private val WEB_KEY = "__web__"
    private val WEBC = 0xFFB39DDB.toInt()   // لافندر هادي للعين (مش أخضر)
    private val webFolder = FolderItem(WEB_KEY, "الفيديوهات المصطادة", "", emptyList())
    private var webList: List<WebVid> = emptyList()
    private var webSrc = 0                       // (v337) فولدر المصطادة: 0 الكل · 1 من يوتيوب (اللي اتشغّل من صفحة يوتيوب) · 2 من المتصفح/اللينكات
    private var srcRow: View? = null
    private val YT_HIST = "🕘 سجل يوتيوب"
    private val WEB_HID = "🔒 المخفي"
    private val WEB_SRC = listOf("الكل", "▶ من يوتيوب", "🌐 من المتصفح", YT_HIST, WEB_HID)
    private var webHidList: List<WebVid> = emptyList()   // (v433) المصطادة من مواقع محجوبة 🚨 — بتتفتح بالقفل بس
    private val sel = LinkedHashSet<String>()
    private var selLevel = ""
    private val webThumbs = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024) { override fun sizeOf(k: String, v: android.graphics.Bitmap) = v.byteCount / 1024 }   // (v458) كاش بالحجم (KB) بدل عدد صور كاملة الحجم
    private val searchEt = EditText(act).apply {
        hint = if (audio) "🔍 ابحث باسم الأغنية أو الفنان" else "🔍 ابحث باسم الفيديو"; textSize = 14f; setSingleLine(); setTextColor(th.text); setHintTextColor(th.muted)
        layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); background = ui.box(th.card, th.border, 14)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { val q = e?.toString()?.trim() ?: ""; if (q != query) { query = q; render() } }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
    }

    private val backV = IconTextView(act).apply {
        text = "→"; textSize = 24f; setTextColor(th.primary); gravity = Gravity.CENTER; visibility = View.GONE
        setPadding(ui.dp(8), 0, ui.dp(12), 0); setOnClickListener { back() }
    }
    private val titleTv = ui.text(if (audio) "🎵 الموسيقى" else "📁 الفيديوهات", 19f, th.primary, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val subTv = ui.text("", 11f, th.muted).apply { setSingleLine() }
    private val listV = ListView(act)
    private var animLvl = ""
    private val stateBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28)) }
    private val stateTv = ui.text("", 14f, th.muted).apply { gravity = Gravity.CENTER }
    private val stateBar = ProgressBar(act)
    private val stateBtn = ui.button("", true) { onGrant() }
    private lateinit var refreshBtn: TextView
    private lateinit var selAllBtn: TextView
    private lateinit var selMenuBtn: TextView
    private lateinit var selCloseBtn: TextView
    private var normalBtns: List<View> = emptyList()

    // ===== ريفريش بالسحب + NEW + إشعار + زرار استكمال =====
    private val knownFile = File(act.filesDir, if (audio) "known_audio.txt" else "known_videos.txt")
    private val newFile = File(act.filesDir, if (audio) "new_audio.txt" else "new_videos.txt")
    private var newKeys: MutableSet<String> = try { newFile.readLines().filter { it.isNotEmpty() }.toMutableSet() } catch (_: Exception) { mutableSetOf() }
    private var userRefresh = false
    private var refreshing = false
    private var spinner: ObjectAnimator? = null
    private val ORANGE = 0xFFFF9F1C.toInt()
    // ===== المجلد المخفي: إخفاء منطقي بالـ videoId (الملف نفسه ما بيتحركش) =====
    private val hiddenFile = File(act.filesDir, if (audio) "hidden_audio.txt" else "hidden_videos.txt")
    private val hidden: MutableSet<String> = try { hiddenFile.readLines().filter { it.isNotEmpty() }.toMutableSet() } catch (_: Exception) { mutableSetOf() }
    private var inHidden = false
    /** (v295) صفحة الموسيقى: عرض آخر الأغاني المشغّلة بدل الفولدرات */
    private var inRecents = false
    private lateinit var recBtn: TextView
    private fun artRows() = Cfg.bool("mus_art_rows", true)
    private fun toggleArtRows() { Cfg.put("mus_art_rows", if (artRows()) "0" else "1"); Thumbs.clear(); (listV.adapter as? BaseAdapter)?.notifyDataSetChanged(); toastMsg(if (artRows()) "🖼 صور الأغاني في الفولدرات: شغّالة" else "🖼 صور الأغاني في الفولدرات: مقفولة") }
    private fun clearRecents() { RecentSongs.clear(); render(); toastMsg("🧹 اتمسح سجل آخر المشغّل") }
    private fun toggleRecents() {
        if (!audio) return
        if (sel.isNotEmpty()) clearSel()
        if (!inRecents) { rootPos = listV.firstVisiblePosition; inRecents = true; curFolder = null; if (query.isNotEmpty()) searchEt.setText("") }
        else inRecents = false
        render(); listV.setSelection(if (inRecents) 0 else rootPos)
    }
    private fun saveHidden() { try { hiddenFile.writeText(hidden.joinToString("\n")) } catch (_: Exception) {} }
    private fun isHidden(v: VideoItem) = v.videoId in hidden
    private fun visible(): List<VideoItem> = all.filter { !isHidden(it) }
    /** فتح المجلد المخفي بيعدّي على البوابة دي (المرحلة 3 هتحط فيها النمط/البصمة) */
    var gate: (open: () -> Unit) -> Unit = { it() }
    fun hideVideo(v: VideoItem) {
        hidden.add(v.videoId); saveHidden(); render()
        toastMsg("🙈 اتخفى — اسحب لتحت وكمّل السحب لحد 🔒 وسيب عشان تفتح المخفي")
    }
    fun unhideVideo(v: VideoItem) { hidden.remove(v.videoId); saveHidden(); render(); toastMsg("👁 رجع للقايمة") }
    fun openHidden() { inHidden = true; inRecents = false; secure(true); if (query.isNotEmpty()) searchEt.setText(""); render(); listV.setSelection(0) }
    /** قفل المخفي تاني (لما التطبيق يقعد في الخلفية فترة) */
    fun relock() { if (webSrc == 4) { webSrc = 0; secure(false); if (!inHidden) render() }; if (inHidden) { inHidden = false; secure(false); render() } }
    /** وهو المخفي مفتوح: ممنوع لقطة الشاشة وصورة الأخيرة بتبقى سودا */
    private fun secure(on: Boolean) { try { if (on) act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) } catch (_: Exception) {} }
    private val emptyTv = ui.text("", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(30), 0, ui.dp(30), 0); visibility = View.GONE }
    private fun keyOf(v: VideoItem) = v.folderKey + "|" + v.name + "|" + v.size

    private val ptr = IconTextView(act).apply {
        text = "🔄"; textSize = 20f; gravity = Gravity.CENTER; background = ui.box(th.surface, th.border, 22)
        elevation = ui.dp(6).toFloat(); alpha = 0f; translationY = -ui.dp(50).toFloat()
    }
    private val toastTv = IconTextView(act).apply {
        textSize = 13f; setTextColor(th.text); setPadding(ui.dp(16), ui.dp(9), ui.dp(16), ui.dp(9))
        background = ui.box(th.surface, ORANGE, 14); elevation = ui.dp(8).toFloat(); translationY = -ui.dp(100).toFloat()
    }
    private val resumeBtn = IconTextView(act).apply {
        text = "▶"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; elevation = ui.dp(8).toFloat(); visibility = View.GONE
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3B82F6.toInt()) }
    }
    private fun newBadge() = IconTextView(act).apply {
        text = "NEW"; textSize = 10f; setTextColor(0xFF111111.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(ui.dp(7), ui.dp(1), ui.dp(7), ui.dp(1)); background = ui.box(ORANGE, Color.TRANSPARENT, 10); visibility = View.GONE
    }
    private var toastRun: Runnable? = null
    private fun toastMsg(m: String) {
        toastTv.text = m; toastRun?.let { toastTv.removeCallbacks(it) }
        toastTv.animate().translationY(ui.dp(10).toFloat()).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        val r = Runnable { toastTv.animate().translationY(-ui.dp(100).toFloat()).setDuration(300).start() }
        toastRun = r; toastTv.postDelayed(r, 3200)
    }
    /** مقارنة القايمة القديمة المحفوظة بالجديدة: بيرجّع عدد الفيديوهات المضافة */
    private fun diff(list: List<VideoItem>): Int {
        val keys = list.map { keyOf(it) }
        var added = 0
        try {
            if (knownFile.exists()) {
                val old = knownFile.readLines().toHashSet()
                val add = keys.filter { it !in old }
                added = add.size
                if (added > 0) { newKeys.addAll(add) }
            }
            newKeys.retainAll(keys.toHashSet())
            knownFile.writeText(keys.joinToString("\n")); newFile.writeText(newKeys.joinToString("\n"))
        } catch (_: Exception) {}
        return added
    }
    private fun endPull() {
        spinner?.cancel(); spinner = null; refreshing = false; ptr.text = "🔄"
        listV.animate().translationY(0f).setDuration(250).start()
        ptr.animate().alpha(0f).translationY(-ui.dp(50).toFloat()).setDuration(250).start()
    }
    private fun dropAnim() {
        listV.post {
            for (i in 0 until listV.childCount) {
                val c = listV.getChildAt(i)
                c.alpha = 0f; c.translationY = -ui.dp(28).toFloat()
                c.animate().alpha(1f).translationY(0f).setStartDelay(i * 45L).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }
    private fun markPlayed(v: VideoItem) {
        if (newKeys.remove(keyOf(v))) try { newFile.writeText(newKeys.joinToString("\n")) } catch (_: Exception) {}
        Cfg.put(K + "lib_last", v.uri); Cfg.put(K + "lib_last_f:" + v.folderKey, v.uri)
        var ck = curFolder
        while (ck != null) { Cfg.put(K + "lib_last_f:" + ck, v.uri); ck = nodeIdx[ck]?.parentKey }   // (v299) زرار الاستكمال جوه فولدرات التوزيع الذكي
    }
    private fun updateResume() {
        val cf = curFolder
        val target = if (cf == null) Cfg.str(K + "lib_last") else Cfg.str(K + "lib_last_f:$cf")
        val v = if (inHidden || target.isEmpty()) null else visible().firstOrNull { it.uri == target && (cf == null || inNode(cf, it)) }
        if (v == null) { resumeBtn.visibility = View.GONE; return }
        resumeBtn.visibility = View.VISIBLE
        resumeBtn.setOnClickListener { markPlayed(v); onPlay(v) }
    }

    private fun sortKey() = Cfg.str(K + "lib_sort", VideoLib.SORT_NAME)

    // ===== (v299) التوزيع الذكي =====
    private fun dist(): String {
        // (v390) الافتراضي بقى «فولدرات» (مرتّبة بالاسم A→Z) بدل «ذكي»؛ مرة واحدة بس بتتطبّق على اللي قبل كده، وبعدها اختيارك بيتحفظ
        if (Cfg.str(K + "lib_dist_mig", "") != "390") { Cfg.put(K + "lib_dist", "folders"); Cfg.put(K + "lib_dist_mig", "390") }
        val d = Cfg.str(K + "lib_dist", "folders"); return if (DISTS.any { it.first == d }) d else "folders"
    }
    /** شجرة الفولدرات اللي بتتعرض: ذكي = مسلسلات/مغنيين مجمّعين + باقي الفولدرات الأصلية، غير كده الفولدرات الأصلية */
    private fun buildTree(src: List<VideoItem>): List<FolderItem> {
        val tops: List<FolderItem> = if (dist() == "smart") {
            val r = try { if (audio) SmartGroup.songs(src) { SongId.looksLikeSite(it) } else SmartGroup.videos(src) } catch (_: Exception) { null }
            if (r != null) r.nodes + VideoLib.group(r.rest) else VideoLib.group(src)
        } else VideoLib.group(src)
        val m = HashMap<String, FolderItem>()
        fun walk(f: FolderItem) { m[f.key] = f; f.kids.forEach { walk(it) } }
        tops.forEach { walk(it) }
        nodeIdx = m
        return tops
    }
    private fun inNode(cf: String, v: VideoItem): Boolean = nodeIdx[cf]?.videos?.any { x -> x.uri == v.uri } == true || v.folderKey == cf

    // ===== (v299) وضع الشبكة =====
    private fun grid(): Boolean = gridOn ?: Cfg.bool(K + "lib_grid", false).also { gridOn = it }
    private fun cols(): Int { val dm = act.resources.displayMetrics; return (dm.widthPixels / dm.density / (if (audio) 112f else 165f)).toInt().coerceIn(2, 5) }
    private fun cellW(n: Int): Int = (act.resources.displayMetrics.widthPixels - ui.dp(24) - n * ui.dp(8)) / n
    private fun toggleGrid() {
        val was = grid(); val c = cols()
        val firstItem = if (was) listV.firstVisiblePosition * c else listV.firstVisiblePosition
        gridOn = !was; Cfg.put(K + "lib_grid", if (!was) "1" else "0")
        gridBtn.text = if (!was) "☰" else "🧩"
        render(); listV.setSelection(if (!was) firstItem / c else firstItem)
    }

    private fun hbtn(t: String, f: () -> Unit): TextView = ui.circleBtn(t, false, f).apply {
        textSize = 16f; layoutParams = LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }
    }

    init {
        // الهيدر: العنوان يمين، وأزرار (رابط · فتح من الملفات · إعدادات · ريفريش) — الريفريش في أقصى الشمال
        val head = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(4)) }
        head.addView(backV)
        val tcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(titleTv); addView(subTv) }
        head.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        // ▣ تحديد الكل (ضغطة مطولة: تحديد / إلغاء) · ⋮ و ✕ بيظهروا بس وفيه تحديد
        selAllBtn = hbtn("▣") { toggleAll() }
        selAllBtn.setOnLongClickListener { a -> a.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); popup(a, listOf("☑ تحديد الكل" to { selectAll() }, "☐ إلغاء التحديد" to { clearSel() }) + (if (inWebNow()) listOf("🗑 مسح كل الفيديوهات المصطادة" to { webClearAll() }) else emptyList())); true }
        selMenuBtn = hbtn("⋮") { }.apply { visibility = View.GONE; setOnClickListener { selMenu(it) } }
        selCloseBtn = hbtn("✕") { clearSel() }.apply { visibility = View.GONE }
        head.addView(selAllBtn); head.addView(selMenuBtn); head.addView(selCloseBtn)
        queueBtn = hbtn("📋") { onQueue() }
        head.addView(queueBtn)
        recBtn = hbtn("🕘") { toggleRecents() }
        recBtn.setOnLongClickListener { a -> a.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); popup(a, listOf((if (artRows()) "🖼 صور الأغاني في الفولدرات: شغّالة (دوس للقفل)" else "🖼 صور الأغاني في الفولدرات: مقفولة (دوس للتشغيل)") to { toggleArtRows() }, "🧹 مسح سجل آخر المشغّل" to { clearRecents() })); true }
        if (audio) head.addView(recBtn)
        val linkBtn = hbtn("🔗") { onLink() }; head.addView(linkBtn)
        val pickBtn = hbtn("📂") { onPick() }; head.addView(pickBtn)
        lateinit var setRef: View
        val setBtn = hbtn("⚙️") { onSettings(setRef) }; setRef = setBtn; head.addView(setBtn)
        refreshBtn = hbtn("🔄") { refreshBtn.animate().rotationBy(360f).setDuration(600).start(); userRefresh = true; onRefresh() }
        head.addView(refreshBtn)
        normalBtns = if (audio) listOf(recBtn, setBtn, refreshBtn) else listOf(queueBtn, linkBtn, pickBtn, setBtn, refreshBtn)
        if (audio) { queueBtn.visibility = View.GONE; linkBtn.visibility = View.GONE; pickBtn.visibility = View.GONE }
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        // (v433) صف واحد بدل اتنين: الترتيب (شيبس) + زرار فولدرات/الكل + زرار الشبكة/القايمة
        gridBtn = hbtn(if (grid()) "☰" else "🧩") { toggleGrid() }
        lateinit var distBtn: TextView
        distBtn = hbtn(if (dist() == "flat") "🗂" else "📁") {
            Cfg.put(K + "lib_dist", if (dist() == "flat") "folders" else "flat")
            distBtn.text = if (dist() == "flat") "🗂" else "📁"
            curFolder = null; inRecents = false; sel.clear(); render(); listV.setSelection(0)
            toastMsg(if (dist() == "flat") "🗂 كل الملفات في قايمة واحدة" else "📁 عرض بالفولدرات")
        }
        val labels = VideoLib.sortLabels.map { it.second }
        val chips = ui.chips(labels, { VideoLib.sortLabelOf(sortKey()) }) { n -> Cfg.put(K + "lib_sort", VideoLib.sortKeyOf(n)); render() }
        val toolRow = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2)) }
        toolRow.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(chips) }, LinearLayout.LayoutParams(0, -2, 1f))
        toolRow.addView(distBtn); toolRow.addView(gridBtn)
        root.addView(toolRow, LinearLayout.LayoutParams(-1, -2))

        // (v337) تقسيم المصطادة (بيظهر جوه فولدر المصطادة بس): الكل / من يوتيوب / من المتصفح
        if (!audio) {
            val srcChips = ui.chips(WEB_SRC, { WEB_SRC[webSrc] }) { n -> if (n == YT_HIST) { onYtHistory() } else if (n == WEB_HID) { gate { webSrc = 4; secure(true); render(); listV.setSelection(0) } } else { if (webSrc == 4) secure(false); webSrc = WEB_SRC.indexOf(n).coerceAtLeast(0); render(); listV.setSelection(0) } }
            val sr = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), 0, ui.dp(8), 0); addView(srcChips); visibility = View.GONE }
            srcRow = sr; root.addView(sr, LinearLayout.LayoutParams(-1, -2))
        }
        // (v345) بحث شامل: 🔎 جنب الخانة بيفتح بحث واحد في الفيديوهات والموسيقى ويوتيوب
        val uniBtn = ui.text("🔎", 20f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = ui.box(th.card, th.border, 14)
            setOnClickListener { onUniSearch?.invoke(searchEt.text.toString().trim()) }
        }
        val sRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        sRow.addView(searchEt, LinearLayout.LayoutParams(0, -2, 1f))
        sRow.addView(uniBtn, LinearLayout.LayoutParams(ui.dp(46), ui.dp(44)).apply { marginStart = ui.dp(6) })
        root.addView(sRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(2)) })

        listV.apply {
            divider = null; dividerHeight = 0; setSelector(android.R.color.transparent); isVerticalScrollBarEnabled = false
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)); clipToPadding = false; cacheColorHint = Color.TRANSPARENT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            Motion.listAnim(this)
            adapter = object : BaseAdapter() {
                override fun getCount() = if (grid()) (rows.size + cols() - 1) / cols() else rows.size
                override fun getItem(i: Int): Any = rows.getOrNull(if (grid()) i * cols() else i) ?: i
                override fun getItemId(i: Int) = i.toLong()
                override fun getViewTypeCount() = 7
                override fun getItemViewType(i: Int): Int = if (grid()) cols() + 1 else when (rows[i]) { is FolderItem -> 0; is WebVid -> 2; else -> 1 }
                override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                    if (grid()) { val n = cols(); return (cv ?: newGridRow(n)).also { bindGridRow(it, i, n) } }
                    val r = rows[i]
                    return when (r) {
                        is FolderItem -> (cv ?: newFolderRow()).also { bindFolder(it, r) }
                        is WebVid -> (cv ?: newVideoRow()).also { bindWeb(it, r) }
                        else -> (cv ?: newVideoRow()).also { bindVideo(it, r as VideoItem) }
                    }
                }
            }
        }
        stateBox.addView(stateBar, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        stateBox.addView(stateTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(12) })
        stateBox.addView(stateBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(12) })
        val body = FrameLayout(act)
        body.addView(listV, FrameLayout.LayoutParams(-1, -1)); body.addView(stateBox, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyTv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        body.addView(ytScroll, FrameLayout.LayoutParams(-1, -1))
        body.addView(ptr, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        body.addView(toastTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        body.addView(resumeBtn, FrameLayout.LayoutParams(ui.dp(58), ui.dp(58), Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(ui.dp(20), 0, 0, ui.dp(24)) })
        // سحب لتحت من أول القايمة = ريفريش
        var y0 = 0f; var pulling = false; var pd = 0f; var wasLock = false
        val thr = ui.dp(60).toFloat(); val lockThr = ui.dp(125).toFloat()
        listV.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; pulling = false; wasLock = false; false }
                MotionEvent.ACTION_MOVE -> {
                    if (refreshing) return@setOnTouchListener false
                    val dy = e.rawY - y0
                    val atTop = listV.firstVisiblePosition == 0 && (listV.childCount == 0 || listV.getChildAt(0).top >= listV.paddingTop)
                    if (!pulling && dy > ui.dp(10) && atTop) {
                        pulling = true; y0 = e.rawY
                        val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; listV.onTouchEvent(c); c.recycle()
                    }
                    if (pulling) {
                        val cap = if (inHidden) ui.dp(95).toFloat() else ui.dp(150).toFloat()
                        pd = ((e.rawY - y0) * 0.5f).coerceIn(0f, cap)
                        listV.translationY = pd; ptr.translationY = pd - ui.dp(50)
                        ptr.alpha = (pd / ui.dp(40)).coerceIn(0f, 1f)
                        val lock = !inHidden && pd >= lockThr
                        if (lock != wasLock) {
                            wasLock = lock; ptr.text = if (lock) "🔒" else "🔄"
                            if (lock) ptr.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        ptr.rotation = if (lock) 0f else pd * 4f
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) {
                        pulling = false
                        if (wasLock && e.actionMasked == MotionEvent.ACTION_UP) {
                            wasLock = false; endPull(); gate { openHidden() }
                        } else if (pd >= thr && e.actionMasked == MotionEvent.ACTION_UP) {
                            refreshing = true; userRefresh = true
                            listV.animate().translationY(ui.dp(56).toFloat()).setDuration(150).start()
                            ptr.animate().translationY(ui.dp(6).toFloat()).alpha(1f).setDuration(150).start()
                            spinner = ObjectAnimator.ofFloat(ptr, "rotation", ptr.rotation, ptr.rotation + 360f).apply {
                                duration = 700; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start()
                            }
                            onPull()
                            ptr.postDelayed({ if (refreshing) { userRefresh = false; endPull() } }, 20000)
                        } else { wasLock = false; endPull() }
                        true
                    } else false
                }
                else -> false
            }
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ===== الحالات =====
    fun showScanning() { mode = "scan"; render() }
    fun showNoPermission() { mode = "perm"; render() }
    fun showVideos(list: List<VideoItem>) {
        val added = diff(list)
        all = list; mode = "ready"; render()
        if (userRefresh) {
            userRefresh = false; endPull(); dropAnim()
            toastMsg(if (added > 0) "✨ تم إضافة $added $noun جديد" else if (audio) "✅ مفيش أغاني جديدة" else "✅ مفيش فيديوهات جديدة")
        } else if (added > 0) { toastMsg("✨ تم إضافة $added $noun جديد"); dropAnim() }
    }
    val hasData: Boolean get() = mode == "ready" && all.isNotEmpty()

    /** رجوع من فولدر للقايمة الرئيسية. بترجّع true لو استهلكت الضغطة */
    fun back(): Boolean {
        if (sel.isNotEmpty()) { clearSel(); return true }
        if (query.isNotEmpty()) { searchEt.setText(""); return true }
        if (inHidden) { inHidden = false; secure(false); render(); listV.setSelection(rootPos); return true }
        if (inRecents) { inRecents = false; render(); listV.setSelection(rootPos); return true }
        if (webSrc == 4) { webSrc = 0; secure(false) }
        val cf = curFolder ?: return false
        curFolder = nodeIdx[cf]?.parentKey; render(); listV.setSelection(if (curFolder == null) rootPos else 0); return true
    }

    fun render() {
        resumeBtn.visibility = View.GONE
        recMap = try { recents() } catch (_: Exception) { emptyMap() }
        val sort = sortKey()
        when (mode) {
            "scan" -> { setState(true, "⏳ جاري فحص التخزين…", null); return }
            "perm" -> { setState(false, if (audio) "محتاج إذن الوصول لملفات الصوت عشان أعرض الموسيقى اللي على الجهاز." else "محتاج إذن الوصول للفيديوهات عشان أعرض فولدرات الجهاز.", "السماح بالوصول"); return }
        }
        if (all.isEmpty()) { emptyTv.visibility = View.GONE; setState(false, if (audio) "مفيش ملفات صوت اتلاقت على الجهاز.\nلو أغانيك موجودة دوس 🔄 للفحص من جديد." else "مفيش فيديوهات اتلاقت على الجهاز.\nلو فيديوهاتك موجودة دوس 🔄 للفحص من جديد، أو افتح فيديو من 📂.", null); return }
        val src = if (inHidden) all.filter { isHidden(it) } else visible()
        val cf = curFolder
        val inWeb = !inHidden && query.isEmpty() && cf == WEB_KEY
        srcRow?.visibility = if (inWeb) View.VISIBLE else View.GONE
        if (!audio && !inHidden && query.isEmpty() && (cf == null || inWeb)) WebVideos.load(act).let { all0 -> webList = all0.filter { !it.hidden }; webHidList = all0.filter { it.hidden } }
        val folders = buildTree(src)
        val folder = if (!inHidden && cf != null && !inWeb) nodeIdx[cf] else null
        if (!inHidden && cf != null && !inWeb && folder == null) curFolder = null
        if (query.isNotEmpty()) {
            val hits = VideoLib.search(src, query)
            rows = hits
            titleTv.text = "🔍 نتائج البحث"; subTv.text = "${hits.size} $noun"; backV.visibility = if (inHidden) View.VISIBLE else View.GONE
        } else if (inHidden) {
            rows = VideoLib.sortVideos(src, sort)
            titleTv.text = "🙈 المخفي"; subTv.text = "${src.size} $noun · ${VideoLib.fmtSize(src.sumOf { it.size })}"; backV.visibility = View.VISIBLE
        } else if (inWeb) {
            val yIds = YtStore.appIds()
            fun fromYt(w: WebVid) = w.id.startsWith("yt:") && w.id.removePrefix("yt:") in yIds
            val shown = when (webSrc) { 1 -> webList.filter { fromYt(it) }; 2 -> webList.filter { !fromYt(it) }; 4 -> webHidList; else -> webList }
            rows = shown
            titleTv.text = if (webSrc == 4) "🔒 المخفي من الإنترنت" else "🌐 المصطادة من الإنترنت"; subTv.text = if (webSrc == 0) "${webList.size} فيديو" else if (webSrc == 4) "${shown.size} فيديو" else "${shown.size} من ${webList.size} فيديو"; backV.visibility = View.VISIBLE
        } else if (inRecents) {
            val byUri = src.associateBy { it.uri }
            rows = RecentSongs.list().mapNotNull { byUri[it] }
            titleTv.text = "🕘 آخر المشغّل"; subTv.text = "${rows.size} $noun"; backV.visibility = View.VISIBLE
        } else if (folder != null) {
            val mix = ArrayList<Any>(); mix.addAll(VideoLib.sortFolders(folder.kids, sort)); mix.addAll(VideoLib.sortVideos(folder.own, sort)); rows = mix
            titleTv.text = (if (folder.icon.isNotEmpty()) folder.icon + " " else "📂 ") + folder.name; subTv.text = "${folder.videos.size} $noun · ${VideoLib.fmtSize(folder.totalSize)}"; backV.visibility = View.VISIBLE
        } else if (dist() == "flat") {
            val head0: List<Any> = if (audio) emptyList() else listOf<Any>(webFolder) + (if (BgJobs.jobs.isNotEmpty()) listOf<Any>(bgFolder) else emptyList<Any>())
            rows = head0 + VideoLib.sortVideos(src, sort)
            titleTv.text = if (audio) "🗂 كل الأغاني" else "🗂 كل الفيديوهات"; subTv.text = "${src.size} $noun · ${VideoLib.fmtSize(src.sumOf { it.size })}"; backV.visibility = View.GONE
        } else {
            // (v432) التوزيع الذكي: المسلسلات/الفنانين المجمّعين الأول (مرتّبين لوحدهم) وبعدهم الفولدرات العادية — مش مخلوطين بالأبجدية
            val smart = dist() == "smart"
            val (grp, plain) = if (smart) folders.partition { it.virtual } else Pair(emptyList<FolderItem>(), folders)
            val ordered = VideoLib.sortFolders(grp, sort) + VideoLib.sortFolders(plain, sort)
            rows = (if (audio) emptyList<Any>() else listOf<Any>(webFolder) + (if (BgJobs.jobs.isNotEmpty()) listOf<Any>(bgFolder) else emptyList<Any>())) + ordered
            titleTv.text = if (audio) "🎵 الموسيقى" else "📁 الفيديوهات"
            subTv.text = if (smart && grp.isNotEmpty()) "${grp.size} ${if (audio) "فنان" else "مسلسل"} · ${plain.size} مجلد · ${src.size} $noun" else "${folders.size} مجلد · ${src.size} $noun"
            backV.visibility = View.GONE
        }
        val lvl = if (query.isNotEmpty()) "q" else if (inHidden) "h" else if (inRecents) "r" else if (inWeb) "w" else "f:" + (curFolder ?: "")
        if (lvl != selLevel) { selLevel = lvl; sel.clear() }
        sel.retainAll(selectableKeys().toSet())
        emptyTv.text = if (rows.isNotEmpty()) "" else if (inWeb) "مفيش فيديوهات مصطادة لسه.\nأي فيديو تفتحه من لينك أو من المتصفح بيتسجل هنا لوحده بالاسم والتقدم." else if (inRecents && query.isEmpty()) "🕘 لسه ما شغّلتش أي أغنية.\nأي أغنية تشغّلها هتظهر هنا بالأحدث فوق." else if (inHidden && query.isEmpty()) "مفيش $noun مخفية.\nدوس ⋮ أو اضغط ضغطة مطولة على أي $noun واختار 🙈 الإخفاء." else if (query.isEmpty()) "كل الملفات مخفية.\nاسحب لتحت وكمّل لحد 🔒 وسيب عشان تفتح المخفي." else "مفيش نتائج."
        emptyTv.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        // (v344) بحث محلي فاضي → اقتراحات يوتيوب / البحث في المتصفح (لو مربوطين)
        if (query.isNotEmpty() && rows.isEmpty() && onYtPick != null && onWebSearch != null) { emptyTv.visibility = View.GONE; if (ytScroll.visibility != View.VISIBLE || ytBox.childCount == 0 || ytLastQ != query) { ytLastQ = query; showYtFallback() } }
        else { ytLastQ = ""; hideYtFallback() }
        stateBox.visibility = View.GONE; listV.visibility = View.VISIBLE
        (listV.adapter as BaseAdapter).notifyDataSetChanged()
        if (lvl != animLvl) { animLvl = lvl; Motion.replay(listV) }   // (v312) أنيميشن الصفوف مع كل مستوى جديد بس (مش مع كل حرف بحث)
        applySelHeader()
        updateResume()
        if (::recBtn.isInitialized) recBtn.text = if (inRecents) "📂" else "🕘"
    }

    private fun setState(busy: Boolean, msg: String, btn: String?) {
        titleTv.text = if (audio) "🎵 الموسيقى" else "📁 الفيديوهات"; subTv.text = ""; backV.visibility = View.GONE
        emptyTv.visibility = View.GONE; listV.visibility = View.GONE; stateBox.visibility = View.VISIBLE
        stateBar.visibility = if (busy) View.VISIBLE else View.GONE
        stateTv.text = msg
        stateBtn.visibility = if (btn != null) View.VISIBLE else View.GONE
        if (btn != null) stateBtn.text = btn
    }

    // ===== (v299) وضع الشبكة: كل صف في الـ ListView فيه n خلية (فولدر / فيديو / مصطاد) =====
    private class GC(val root: LinearLayout, val iv: ImageView, val ph: TextView, val dur: TextView, val tick: TextView, val cb: View, val badge: TextView,
                     val title: TextView, val sub: TextView, val bar: View, val fill: View, val rest: View, val dots: TextView)

    private fun newGridCell(n: Int): GC {
        val ph = IconTextView(act).apply { textSize = 28f; gravity = Gravity.CENTER; alpha = 0.45f; text = if (audio) "🎵" else "🎞" }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val dur = IconTextView(act).apply { textSize = 10f; setTextColor(Color.WHITE); setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1)); background = ui.box(0xCC000000.toInt(), Color.TRANSPARENT, 4); visibility = View.GONE }
        val tick = IconTextView(act).apply { text = "✓"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3B82F6.toInt()) }; visibility = View.GONE }
        val cb = newCheck()
        val badge = newBadge()
        val tw = cellW(n) - ui.dp(8)
        val thumbH = if (audio) tw else tw * 9 / 16
        val thumb = FrameLayout(act).apply {
            background = ui.box(0xFF000000.toInt(), th.border, 10); clipToOutline = true
            addView(ph, FrameLayout.LayoutParams(-1, -1)); addView(iv, FrameLayout.LayoutParams(-1, -1))
            addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            addView(tick, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.TOP or Gravity.END).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), 0) })
            addView(cb, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.TOP or Gravity.START))
        }
        val title = ui.text("", 13f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val sub = ui.text("", 11f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val dts = dots { }.apply { setPadding(ui.dp(6), ui.dp(2), ui.dp(2), ui.dp(2)) }
        val fill = View(act).apply { setBackgroundColor(th.primary) }
        val rest = View(act)
        val bar = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR; background = ui.box(th.border, Color.TRANSPARENT, 2); clipToOutline = true
            addView(fill, LinearLayout.LayoutParams(0, -1, 0f)); addView(rest, LinearLayout.LayoutParams(0, -1, 1f)); visibility = View.GONE
        }
        val trow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(dts, LinearLayout.LayoutParams(-2, -2)) }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6)); background = ui.box(th.card, th.border, 12)
            addView(thumb, LinearLayout.LayoutParams(-1, thumbH))
            addView(trow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            addView(sub, LinearLayout.LayoutParams(-1, -2))
            addView(bar, LinearLayout.LayoutParams(-1, ui.dp(3)).apply { topMargin = ui.dp(4) })
        }
        Glass.pressable(root)
        return GC(root, iv, ph, dur, tick, cb, badge, title, sub, bar, fill, rest, dts)
    }

    private fun newGridRow(n: Int): View {
        val row = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        val cells = ArrayList<GC>()
        for (k in 0 until n) {
            val c = newGridCell(n)
            row.addView(c.root, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
            cells.add(c)
        }
        row.tag = cells
        return row
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindGridRow(v: View, i: Int, n: Int) {
        val cells = v.tag as ArrayList<GC>
        for (k in 0 until n) bindCell(cells[k], rows.getOrNull(i * n + k))
    }

    private fun gridBar(c: GC, frac: Double) {
        c.bar.visibility = if (frac > 0.01) View.VISIBLE else View.GONE
        (c.fill.layoutParams as LinearLayout.LayoutParams).weight = frac.toFloat(); (c.rest.layoutParams as LinearLayout.LayoutParams).weight = (1.0 - frac).toFloat(); c.bar.requestLayout()
    }

    private fun bindCell(c: GC, item: Any?) {
        if (item == null) { c.root.visibility = View.INVISIBLE; c.root.setOnClickListener(null); c.root.setOnLongClickListener(null); return }
        c.root.visibility = View.VISIBLE
        c.iv.setImageDrawable(null); c.tick.visibility = View.GONE; c.badge.visibility = View.GONE; c.bar.visibility = View.GONE
        c.cb.visibility = View.VISIBLE; c.dots.visibility = View.VISIBLE; c.dur.visibility = View.GONE
        c.ph.text = if (audio) "🎵" else "🎞"; c.title.setTextColor(th.text)
        when (item) {
            is FolderItem -> bindGridFolder(c, item)
            is WebVid -> bindGridWeb(c, item)
            is VideoItem -> bindGridVideo(c, item)
        }
    }

    private fun bindGridFolder(c: GC, f: FolderItem) {
        if (f === webFolder || f === bgFolder) {
            val web = f === webFolder
            c.ph.text = if (web) "🌐" else "🌙"
            c.cb.visibility = View.GONE; c.dots.visibility = View.GONE
            if (web) {
                val n = webList.size
                c.title.text = "الفيديوهات المصطادة"; c.sub.text = if (n > 0) "$n فيديو" else "لسه مفيش"
                c.root.background = GradientDrawable().apply { cornerRadius = ui.dp(12).toFloat(); setColor((WEBC and 0x00FFFFFF) or 0x22000000); setStroke(ui.dp(2), WEBC) }
                c.root.setOnClickListener { if (sel.isEmpty()) { rootPos = listV.firstVisiblePosition; curFolder = WEB_KEY; render(); listV.setSelection(0) } }
            } else {
                val run = BgJobs.jobs.firstOrNull { it.state == "running" }
                c.title.text = "🌙 الترجمة في الخلفية"
                c.sub.text = "${BgJobs.jobs.count { it.active }} في الطابور" + (if (run != null) " · ${run.pct}%" else "")
                c.root.background = ui.box(th.card, th.border, 12)
                c.root.setOnClickListener { onQueue() }
            }
            c.root.setOnLongClickListener(null)
            return
        }
        val on = f.key in sel
        bindCheck(c.cb, on) { toggleSel(f.key) }
        c.root.background = if (on) ui.box(th.card, th.primary, 12, 2) else ui.box(th.card, th.border, 12)
        c.ph.text = f.icon.ifEmpty { "📁" }
        c.title.text = f.name
        c.sub.text = (if (f.kids.isNotEmpty()) "${f.kids.size} أجزاء · " else "") + "${f.videos.size} $noun · ${VideoLib.fmtSize(f.totalSize)}"
        c.dur.text = f.icon.ifEmpty { "📁" } + " " + f.videos.size; c.dur.visibility = View.VISIBLE
        val nn = f.videos.count { keyOf(it) in newKeys }
        c.badge.text = if (nn > 1) "NEW $nn" else "NEW"; c.badge.visibility = if (nn > 0) View.VISIBLE else View.GONE
        val first = f.videos.firstOrNull()
        if (first != null && (!audio || artRows())) {
            c.iv.tag = first.uri
            val bm = Thumbs.peek(first)
            if (bm != null) c.iv.setImageBitmap(bm) else Thumbs.load(act, first, audio) { b -> if (b != null && c.iv.tag == first.uri) c.iv.setImageBitmap(b) }
        }
        c.dots.setOnClickListener { x -> folderMenu(x, f, false) }
        c.root.setOnClickListener { if (sel.isNotEmpty()) toggleSel(f.key) else { rootPos = listV.firstVisiblePosition; curFolder = f.key; render(); listV.setSelection(0) } }
        c.root.setOnLongClickListener { cc -> cc.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); if (sel.isNotEmpty()) toggleSel(f.key) else folderMenu(c.dots, f, true); true }
    }

    private fun bindGridVideo(c: GC, vi: VideoItem) {
        c.title.text = vi.title
        val on = vi.videoId in sel
        bindCheck(c.cb, on) { toggleSel(vi.videoId) }
        c.root.background = if (on) ui.box(th.card, th.primary, 12, 2) else ui.box(th.card, th.border, 12)
        c.badge.visibility = if (keyOf(vi) in newKeys) View.VISIBLE else View.GONE
        c.dur.text = VideoLib.fmtDur(vi.durMs); c.dur.visibility = if (vi.durMs > 0) View.VISIBLE else View.GONE
        val nowPlaying = audio && MusicEngine.current?.uri == vi.uri
        c.title.setTextColor(if (nowPlaying) th.primary else th.text)
        val r = recMap[vi.videoId]
        val parts = ArrayList<String>()
        if (nowPlaying) parts.add(if (MusicEngine.isPlaying) "🔊 شغّالة" else "⏸ واقفة")
        if (r != null && r.subs > 0) parts.add("✓ مترجم ${r.percent}%")
        val bj = bgJob(vi)
        if (bj != null && bj.active) parts.add("🌙 ${bj.pct}%")
        parts.add(if (inHidden || inRecents) vi.folderName else if (audio) vi.artist else VideoLib.fmtSize(vi.size))
        c.sub.text = parts.filter { it.isNotEmpty() }.joinToString(" · ")
        val d = if (r != null && r.durSec > 0) r.durSec else vi.durMs / 1000.0
        gridBar(c, if (r != null && d > 0) (r.posSec / d).coerceIn(0.0, 1.0) else 0.0)
        c.tick.visibility = if (r != null && r.percent >= 97) View.VISIBLE else View.GONE
        c.iv.tag = vi.uri
        if (!audio || artRows()) {
            val bm = Thumbs.peek(vi)
            if (bm != null) c.iv.setImageBitmap(bm) else Thumbs.load(act, vi, audio) { b -> if (b != null && c.iv.tag == vi.uri) c.iv.setImageBitmap(b) }
        }
        c.dots.setOnClickListener { x -> videoMenu(x, vi, false) }
        c.root.setOnLongClickListener { cc -> cc.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); if (sel.isNotEmpty()) toggleSel(vi.videoId) else videoMenu(c.dots, vi, true); true }
        c.root.setOnClickListener { if (sel.isNotEmpty()) toggleSel(vi.videoId) else { markPlayed(vi); onPlay(vi) } }
    }

    private fun bindGridWeb(c: GC, w: WebVid) {
        val onSel = w.id in sel
        bindCheck(c.cb, onSel) { toggleSel(w.id) }
        c.title.text = if (w.named) w.title else "🔎 " + w.title
        val r = recMap[w.id]
        val d = r?.durSec ?: 0.0
        c.dur.text = VideoLib.fmtDur((d * 1000).toLong()); c.dur.visibility = if (d > 0) View.VISIBLE else View.GONE
        c.sub.text = w.url.substringAfter("://").substringBefore('/').removePrefix("www.")
        gridBar(c, if (r != null && d > 0) (r.posSec / d).coerceIn(0.0, 1.0) else 0.0)
        c.tick.visibility = if (r != null && r.percent >= 97) View.VISIBLE else View.GONE
        c.root.background = GradientDrawable().apply { cornerRadius = ui.dp(12).toFloat(); setColor(th.card); setStroke(ui.dp(2), if (onSel) th.primary else WEBC) }
        c.iv.tag = w.id; c.iv.setImageBitmap(webThumb(w))
        c.dots.setOnClickListener { x -> webMenu(x, w) }
        c.root.setOnLongClickListener { cc -> cc.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); if (sel.isNotEmpty()) toggleSel(w.id) else webMenu(c.dots, w, true); true }
        c.root.setOnClickListener { if (sel.isNotEmpty()) toggleSel(w.id) else onPlayWeb(w) }
    }

    // ===== صف الفولدر =====
    private fun newFolderRow(): View {
        val cb = newCheck()
        val icon = IconTextView(act).apply { text = "📁"; textSize = 24f; gravity = Gravity.CENTER; background = ui.box(th.surface, th.border, 12) }
        // (v432) صورة أول فيديو/أغنية جوه الفولدر (زي وضع الشبكة) بدل أيقونة 📁 بس — عشان تشوف إيه جواه
        val thumbIv = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, ui.dp(12).toFloat()) } }
        }
        val iconBox = FrameLayout(act).apply { addView(icon, FrameLayout.LayoutParams(-1, -1)); addView(thumbIv, FrameLayout.LayoutParams(-1, -1)) }
        val name = ui.text("", 15f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val info = ui.text("", 12f, th.primary)
        val path = ui.text("", 10f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        val fBadge = newBadge()
        val nameRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; addView(name, LinearLayout.LayoutParams(-2, -2, 1f)); addView(fBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) }) }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(nameRow); addView(info); addView(path) }
        val chev = IconTextView(act).apply { text = "‹"; textSize = 26f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(8), 0, ui.dp(4), 0) }
        val fDots = dots { }
        val card = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10)); background = ui.box(th.card, th.border, 14)
            addView(cb, LinearLayout.LayoutParams(ui.dp(38), ui.dp(48)))
            addView(iconBox, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
            addView(fDots)
            addView(chev)
        }
        Glass.pressable(card)
        val wrap = FrameLayout(act).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); addView(card, FrameLayout.LayoutParams(-1, -2)); tag = arrayOf(name, info, path, card, fBadge, fDots, cb, icon, thumbIv) }
        return wrap
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindFolder(v: View, f: FolderItem) {
        val t = v.tag as Array<View>
        (t[8] as ImageView).apply { visibility = View.GONE; tag = null }   // (v432)
        if (f === webFolder) {
            val n = webList.size
            (t[7] as TextView).text = "🌐"
            (t[0] as TextView).text = "الفيديوهات المصطادة من الإنترنت"
            (t[1] as TextView).text = if (n > 0) "$n فيديو · اللينك والاسم والتقدم محفوظين" else "لسه مفيش — أي فيديو تفتحه من لينك بيتسجل هنا لوحده"
            (t[2] as TextView).text = "اضغط على أي فيديو يكمّل من آخر مكان وقفت عنده"
            (t[4] as TextView).visibility = View.GONE
            t[5].visibility = View.GONE; t[6].visibility = View.GONE
            t[3].background = GradientDrawable().apply { cornerRadius = ui.dp(14).toFloat(); setColor((WEBC and 0x00FFFFFF) or 0x22000000); setStroke(ui.dp(2), WEBC) }
            t[3].setOnClickListener { if (sel.isEmpty()) { rootPos = listV.firstVisiblePosition; curFolder = WEB_KEY; render(); listV.setSelection(0) } }
            t[3].setOnLongClickListener(null)
            return
        }
        (t[7] as TextView).text = if (f === bgFolder) "🌙" else f.icon.ifEmpty { "📁" }
        if (f === bgFolder) {
            val act = BgJobs.jobs.count { it.active }; val run = BgJobs.jobs.firstOrNull { it.state == "running" }
            (t[0] as TextView).text = "🌙 مجلد الترجمة في الخلفية"
            (t[1] as TextView).text = "$act في الطابور" + (if (run != null) " · بيترجم: ${run.title} ${run.pct}%" else "")
            (t[2] as TextView).text = "اللي فوق بيترجم الأول · رتّبهم بـ ⬆ ⬇"
            (t[4] as TextView).visibility = View.GONE
            t[5].visibility = View.GONE; t[6].visibility = View.GONE
            t[3].background = ui.box(th.card, th.border, 14)
            t[3].setOnClickListener { onQueue() }
            t[3].setOnLongClickListener(null)
            return
        }
        t[5].visibility = View.VISIBLE
        t[6].visibility = if (sel.isNotEmpty()) View.VISIBLE else View.GONE   // (v432) المربع بيظهر بس لما التحديد يتفعّل (ضغطة مطوّلة ← تحديد) — الصف أنضف
        val on = f.key in sel
        bindCheck(t[6], on) { toggleSel(f.key) }
        t[3].background = if (on) ui.box(th.card, th.primary, 14, 2) else ui.box(th.card, th.border, 14)
        (t[0] as TextView).text = f.name
        val unit = if (f.virtual && !audio) "حلقة" else noun
        (t[1] as TextView).text = (if (f.kids.isNotEmpty()) "${f.kids.size} مواسم · " else "") + "${f.count} $unit · ${VideoLib.fmtSize(f.totalSize)}"
        run {   // (v432) صورة أول عنصر
            val first = f.videos.firstOrNull(); val iv = t[8] as ImageView
            if (first != null && (!audio || artRows())) {
                iv.tag = first.uri
                val bm = Thumbs.peek(first)
                if (bm != null) { iv.setImageBitmap(bm); iv.visibility = View.VISIBLE }
                else Thumbs.load(act, first, audio) { b -> if (b != null && iv.tag == first.uri) { iv.setImageBitmap(b); iv.visibility = View.VISIBLE } }
            }
        }
        (t[2] as TextView).text = f.path
        val nn = f.videos.count { keyOf(it) in newKeys }
        (t[4] as TextView).apply { text = if (nn > 1) "NEW $nn" else "NEW"; visibility = if (nn > 0) View.VISIBLE else View.GONE }
        t[3].setOnClickListener { if (sel.isNotEmpty()) toggleSel(f.key) else { rootPos = listV.firstVisiblePosition; curFolder = f.key; render(); listV.setSelection(0) } }
        t[5].setOnClickListener { v -> folderMenu(v, f, false) }
        t[3].setOnLongClickListener { c -> c.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); if (sel.isNotEmpty()) toggleSel(f.key) else folderMenu(t[5], f, true); true }
    }
    /** قايمة الفولدر (⋮ أو ضغطة مطولة): نفس أفعال قايمة الفيديو بس على الفولدر كله */
    private fun folderMenu(anchor: View, f: FolderItem, withSel: Boolean) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (withSel) { items += "☑ تحديد" to { toggleSel(f.key) }; items += "☑ تحديد الكل" to { selectAll() } }
        if (audio) { items += "▶ تشغيل الفولدر (${f.videos.size})" to { onPlayFolder(f) }; items += (if (artRows()) "🖼 صور الأغاني: شغّالة (قفل)" else "🖼 صور الأغاني: مقفولة (تشغيل)") to { toggleArtRows() } }
        else items += "🌙 ترجمة كل فيديوهات الفولدر في الخلفية (${f.videos.size})" to { onBgFolder(f) }
        items += "🙈 إخفاء الفولدر" to { hideFolder(f) }
        if (!f.virtual) items += "✏ إعادة تسمية الفولدر" to { onRenameFolder(f) }
        items += "ℹ تفاصيل الفولدر" to { folderDetails(f) }
        items += "🗑 مسح الفولدر بكل اللي فيه" to { onDeleteFolder(f) }
        popup(anchor, items)
    }
    private fun hideFolder(f: FolderItem) {
        f.videos.forEach { hidden.add(it.videoId) }; saveHidden(); render()
        toastMsg("🙈 اتخفى الفولدر (${f.videos.size} $noun) — اسحب لتحت وكمّل السحب لحد 🔒 وسيب عشان تفتح المخفي")
    }
    private fun folderDetails(f: FolderItem) {
        val tr = f.videos.count { recMap[it.videoId]?.let { r -> r.subs > 0 } == true }
        GAlert(act).setTitle("ℹ " + f.name)
            .setMessage("المسار: ${f.path}\nعدد الفيديوهات: ${f.videos.size}\nالحجم: ${VideoLib.fmtSize(f.totalSize)}\nالمترجم منهم (كله أو جزء): $tr")
            .setPositiveButton("تمام", null).show()
    }
    private val FolderItem.count: Int get() = videos.size
    private val bgFolder = FolderItem("__bg__", "مجلد الترجمة في الخلفية", "", emptyList())

    // ===== صف الفيديو =====
    private class VH(val iv: ImageView, val dur: TextView, val title: TextView, val meta: TextView, val state: TextView, val card: View, val badge: TextView, val fill: View, val rest: View, val tick: TextView, val dots: TextView, val cb: View)

    private fun newVideoRow(): View {
        val ph = IconTextView(act).apply { text = if (audio) "🎵" else "🎞"; textSize = 24f; gravity = Gravity.CENTER; alpha = 0.45f }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val dur = IconTextView(act).apply {
            textSize = 10f; setTextColor(Color.WHITE); setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1)); background = ui.box(0xCC000000.toInt(), Color.TRANSPARENT, 4)
        }
        val tick = IconTextView(act).apply { text = "✓"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3B82F6.toInt()) }; visibility = View.GONE }
        val thumb = FrameLayout(act).apply {
            background = ui.box(0xFF000000.toInt(), th.border, 10); clipToOutline = true
            addView(ph, FrameLayout.LayoutParams(-1, -1)); addView(iv, FrameLayout.LayoutParams(-1, -1))
            addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            addView(tick, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(4), ui.dp(4), 0, 0) })
        }
        val title = ui.text("", 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = ui.text("", 11f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val state = ui.text("", 11f, th.primary).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val vBadge = newBadge()
        val titleRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(vBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) }) }
        val fill = View(act).apply { setBackgroundColor(th.primary) }
        val rest = View(act)
        val bar = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; background = ui.box(th.border, Color.TRANSPARENT, 2); clipToOutline = true
            addView(fill, LinearLayout.LayoutParams(0, -1, 0f)); addView(rest, LinearLayout.LayoutParams(0, -1, 1f)); visibility = View.GONE }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(titleRow); addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(3) }); addView(state, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(2) })
            addView(bar, LinearLayout.LayoutParams(-1, ui.dp(3)).apply { topMargin = ui.dp(5) }) }
        val vDots = dots { }
        val vcb = newCheck()
        val card = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(2), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.box(th.card, th.border, 12)
            addView(vcb, LinearLayout.LayoutParams(ui.dp(38), ui.dp(48)))
            addView(thumb, LinearLayout.LayoutParams(ui.dp(if (audio) 72 else 128), ui.dp(72)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            addView(vDots)
        }
        Glass.pressable(card)
        return FrameLayout(act).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); addView(card, FrameLayout.LayoutParams(-1, -2)); tag = VH(iv, dur, title, meta, state, card, vBadge, fill, rest, tick, vDots, vcb) }
    }

    /** قايمة الفيديو: بتظهر من ⋮ ومن الضغطة المطولة على الفيديو */
    private fun videoMenu(anchor: View, vi: VideoItem, withSel: Boolean) {
        val bj = bgJob(vi); val running = bj != null && bj.active
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (withSel) { items += "☑ تحديد" to { toggleSel(vi.videoId) }; items += "☑ تحديد الكل" to { selectAll() } }
        if (!audio) items += (if (running) "⏹ إيقاف الترجمة في الخلفية" else if (bj?.state == "stopped") "▶ استئناف الترجمة في الخلفية (من مكان ما وقفت)" else "🌙 نقل لمجلد الترجمة في الخلفية") to { if (running) onBgStop(vi) else onBg(vi) }
        items += "▶ تشغيل" to { markPlayed(vi); onPlay(vi) }
        if (inHidden) items += "👁 إظهار الفيديو (يرجع للقايمة)" to { unhideVideo(vi) }
        else {
            items += "🙈 إخفاء الفيديو" to { hideVideo(vi) }
            items += "✏ إعادة تسمية" to { onRename(vi) }
            items += "📁 نقل" to { onMove(vi) }
        }
        items += "ℹ تفاصيل" to { onDetails(vi) }
        items += "📤 مشاركة" to { onShare(vi) }
        items += "🗑 حذف" to { onDelete(vi) }
        popup(anchor, items)
    }

    private fun bindVideo(v: View, vi: VideoItem) {
        val h = v.tag as VH
        h.title.text = vi.title
        h.cb.visibility = View.VISIBLE
        val on = vi.videoId in sel
        bindCheck(h.cb, on) { toggleSel(vi.videoId) }
        h.card.background = if (on) ui.box(th.card, th.primary, 12, 2) else ui.box(th.card, th.border, 12)
        h.badge.visibility = if (keyOf(vi) in newKeys) View.VISIBLE else View.GONE
        h.dur.text = VideoLib.fmtDur(vi.durMs); h.dur.visibility = if (vi.durMs > 0) View.VISIBLE else View.GONE
        h.meta.text = listOf(if (inHidden || inRecents) vi.folderName else "", vi.artist, VideoLib.fmtSize(vi.size), vi.ext.uppercase(), VideoLib.fmtDate(vi.dateMs)).filter { it.isNotEmpty() }.joinToString(" · ")
        val nowPlaying = audio && MusicEngine.current?.uri == vi.uri
        h.title.setTextColor(if (nowPlaying) th.primary else th.text)
        val r = recMap[vi.videoId]
        val parts = ArrayList<String>()
        if (r != null && r.posSec > 5) parts.add("▶ وقف عند " + PlayerLogic.clock((r.posSec * 1000).toLong()))
        if (r != null && r.subs > 0) parts.add("✓ مترجم ${r.percent}%")
        val d = if (r != null && r.durSec > 0) r.durSec else vi.durMs / 1000.0
        val frac = if (r != null && d > 0) (r.posSec / d).coerceIn(0.0, 1.0) else 0.0
        val bar = h.fill.parent as View
        bar.visibility = if (frac > 0.01) View.VISIBLE else View.GONE
        (h.fill.layoutParams as LinearLayout.LayoutParams).weight = frac.toFloat(); (h.rest.layoutParams as LinearLayout.LayoutParams).weight = (1.0 - frac).toFloat(); bar.requestLayout()
        h.tick.visibility = if (r != null && r.percent >= 97) View.VISIBLE else View.GONE
        if (nowPlaying) parts.add(if (MusicEngine.isPlaying) "🔊 شغّالة دلوقتي" else "⏸ واقفة مؤقتًا")
        val bj = bgJob(vi)
        if (bj != null && bj.active) {
            parts.add(if (bj.state == "queued") "⏳ في طابور الترجمة بالخلفية" else "🌙 بيترجم في الخلفية ${bj.pct}%" + (BgJobs.fmtRemain(bj.remainSec).let { if (it.isEmpty()) "" else " · باقي $it" }))
        } else if (bj != null && bj.state == "failed") parts.add("⚠ ترجمة الخلفية وقفت: " + bj.err)
        h.state.text = parts.joinToString(" · "); h.state.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        h.dots.setOnClickListener { v -> videoMenu(v, vi, false) }
        h.card.setOnLongClickListener { c -> c.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); if (sel.isNotEmpty()) toggleSel(vi.videoId) else videoMenu(h.dots, vi, true); true }
        h.iv.tag = vi.uri; h.iv.setImageDrawable(null)
        if (!audio || artRows()) {
            val c = Thumbs.peek(vi)
            if (c != null) h.iv.setImageBitmap(c)
            else Thumbs.load(act, vi, audio) { b -> if (b != null && h.iv.tag == vi.uri) h.iv.setImageBitmap(b) }
        }
        h.card.setOnClickListener { if (sel.isNotEmpty()) toggleSel(vi.videoId) else { markPlayed(vi); onPlay(vi) } }
    }

    // ===== صف فيديو مصطاد (بإطار لافندر) =====
    private fun webThumb(w: WebVid): android.graphics.Bitmap? {
        val f = WebVideos.thumbFile(act, w.id); if (!f.exists()) return null
        val k = f.path + f.lastModified()
        webThumbs.get(k)?.let { return it }
        // (v458) فكّ مصغّر (inSampleSize) بدل الصورة كاملة على الـ main thread أثناء السكرول
        val b = (try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var ss = 1; while ((o.outWidth / ss) > 480 || (o.outHeight / ss) > 480) ss *= 2
            o.inJustDecodeBounds = false; o.inSampleSize = ss
            android.graphics.BitmapFactory.decodeFile(f.path, o)
        } catch (_: Throwable) { null }) ?: return null
        webThumbs.put(k, b); return b
    }
    private fun bindWeb(v: View, w: WebVid) {
        val h = v.tag as VH
        h.cb.visibility = View.VISIBLE
        val onSel = w.id in sel
        bindCheck(h.cb, onSel) { toggleSel(w.id) }
        h.title.text = if (w.named) w.title else "🔎 " + w.title
        h.badge.visibility = View.GONE
        val r = recMap[w.id]
        val d = r?.durSec ?: 0.0
        h.dur.text = VideoLib.fmtDur((d * 1000).toLong()); h.dur.visibility = if (d > 0) View.VISIBLE else View.GONE
        val host = w.url.substringAfter("://").substringBefore('/').removePrefix("www.")
        h.meta.text = listOf(host, w.kind, VideoLib.fmtDate(w.ts)).filter { it.isNotEmpty() }.joinToString(" · ")
        val parts = ArrayList<String>()
        if (r != null && r.posSec > 5) parts.add("▶ وقف عند " + PlayerLogic.clock((r.posSec * 1000).toLong()))
        if (r != null && r.subs > 0) parts.add("✓ مترجم ${r.percent}%")
        h.state.text = parts.joinToString(" · "); h.state.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        val frac = if (r != null && d > 0) (r.posSec / d).coerceIn(0.0, 1.0) else 0.0
        val bar = h.fill.parent as View
        bar.visibility = if (frac > 0.01) View.VISIBLE else View.GONE
        (h.fill.layoutParams as LinearLayout.LayoutParams).weight = frac.toFloat(); (h.rest.layoutParams as LinearLayout.LayoutParams).weight = (1.0 - frac).toFloat(); bar.requestLayout()
        h.tick.visibility = if (r != null && r.percent >= 97) View.VISIBLE else View.GONE
        h.card.background = GradientDrawable().apply { cornerRadius = ui.dp(12).toFloat(); setColor(th.card); setStroke(ui.dp(2), if (onSel) th.primary else WEBC) }
        h.iv.tag = w.id; h.iv.setImageBitmap(webThumb(w))
        h.dots.setOnClickListener { x -> webMenu(x, w) }
        h.card.setOnLongClickListener { c -> c.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); if (sel.isNotEmpty()) toggleSel(w.id) else webMenu(h.dots, w, true); true }
        h.card.setOnClickListener { if (sel.isNotEmpty()) toggleSel(w.id) else onPlayWeb(w) }
    }
    private fun webMenu(anchor: View, w: WebVid, withSel: Boolean = false) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "☑ تحديد" to { toggleSel(w.id) }
        items += "☑ تحديد الكل" to { selectAll() }
        items += "▶ تشغيل (من آخر مكان وقفت عنده)" to { onPlayWeb(w) }
        items += "✏ إعادة تسمية" to { webRename(w) }
        items += "📋 نسخ الرابط" to {
            try { (act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("url", w.url)); toastMsg("📋 اتنسخ الرابط") } catch (_: Exception) {}
        }
        items += "🔧 تحديث اللينك من الصفحة" to { repairWeb(w) }
        items += "🔗 حفظ لينك الصفحة" to { webPageLink(w) }
        items += "🗑 مسح من السجل" to {
            GAlert(act).setTitle("🗑 مسح من المصطادة").setMessage("هيتمسح «${w.title}» من السجل (الترجمة المحفوظة مش هتتمسح). تمام؟")
                .setPositiveButton("امسح") { _, _ -> WebVideos.remove(act, w.id); render() }.setNegativeButton("إلغاء", null).show()
            Unit
        }
        items += "🗑 مسح كل الفيديوهات المصطادة (${(if (webSrc == 4) webHidList else webList).size})" to { webClearAll() }
        popup(anchor, items)
    }
    /** (v157) مسح كل المصطادة (من السجل بس — الترجمة المحفوظة مش بتتمسح) بعد تأكيد */
    private fun webClearAll() {
        val all = (if (webSrc == 4) webHidList else webList).map { it.id }.toSet()
        if (all.isEmpty()) return
        GAlert(act).setTitle("🗑 مسح كل المصطادة").setMessage("هيتمسح ${all.size} فيديو من سجل المصطادة (الترجمة المحفوظة مش هتتمسح). تمام؟")
            .setPositiveButton("امسح الكل") { _, _ -> WebVideos.removeMany(act, all); sel.clear(); render() }.setNegativeButton("إلغاء", null).show()
    }
    private fun webClearSelected(ws: List<WebVid>) {
        val ids = ws.map { it.id }.toSet()
        GAlert(act).setTitle("🗑 مسح المحدد").setMessage("هيتمسح ${ids.size} فيديو من سجل المصطادة (الترجمة المحفوظة مش هتتمسح). تمام؟")
            .setPositiveButton("امسح") { _, _ -> WebVideos.removeMany(act, ids); sel.clear(); render() }.setNegativeButton("إلغاء", null).show()
    }
    /** يحفظ لينك صفحة الفيديو (من الكليبورد أو بالكتابة) — ده اللي بيتفتح في الخلفية لما لينك التحميل يفشل */
    /** (v203) بيفتح صفحة الفيديو في المتصفح: تدوس تشغيل وهو يلقط اللينك الجديد ويرجّعك للمشغّل بنفس ترجمة الفيديو وتقدمه */
    private fun repairWeb(w: WebVid) {
        val page = w.page.ifEmpty { if (WebIdent.specific(w.ref)) w.ref else "" }
        if (page.isEmpty()) { webPageLink(w); return }   // مفيش صفحة محفوظة: احفظها الأول
        act.startActivity(android.content.Intent(act, BrowserActivity::class.java).putExtra("start", page).putExtra("noauto", true)
            .putExtra("repair_id", w.id).putExtra("repair_url", w.url).putExtra("repair_title", w.title))
    }
    private fun webPageLink(w: WebVid) {
        val clip = try { (act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: "" } catch (_: Exception) { "" }
        val start = if (clip.startsWith("http", true)) clip else w.page.ifEmpty { w.ref }
        val et = EditText(act).apply { setText(start); setSelection(text.length); layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); maxLines = 4 }
        GAlert(act).setTitle("🔗 لينك صفحة الفيديو").setMessage("الصق لينك الصفحة اللي بيتشغّل فيها الفيديو في الموقع. لو الفيديو ماشتغلش بعد كده هقدر أحدّث لينك التحميل منها.").setView(et)
            .setPositiveButton("حفظ") { _, _ ->
                val u = et.text.toString().trim()
                if (u.startsWith("http", true)) { WebVideos.update(act, w.id) { it.copy(page = u) }; render(); toastMsg("✓ اتحفظ لينك الصفحة") }
                else toastMsg("اللينك لازم يبدأ بـ http")
            }.setNegativeButton("إلغاء", null).show()
    }
    private fun webRename(w: WebVid) {
        val et = EditText(act).apply { setText(w.title); setSelection(text.length); layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
        GAlert(act).setTitle("✏ اسم الفيديو").setView(et)
            .setPositiveButton("حفظ") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { WebVideos.update(act, w.id) { it.copy(title = t, named = true) }; render() }
            }.setNegativeButton("إلغاء", null).show()
    }
    // ===== التحديد المتعدد =====
    private fun newCheck(): FrameLayout {
        val tv = IconTextView(act).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        return FrameLayout(act).apply { addView(tv, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER)); tag = tv }
    }
    private fun bindCheck(w: View, on: Boolean, f: () -> Unit) {
        val tv = w.tag as TextView
        tv.text = if (on) "✓" else ""
        tv.background = GradientDrawable().apply { cornerRadius = ui.dp(7).toFloat(); setStroke(ui.dp(2), if (on) th.primary else th.muted); setColor(if (on) th.primary else Color.TRANSPARENT) }
        w.setOnClickListener { f() }
    }
    private fun selectableKeys(): List<String> =
        rows.filterIsInstance<FolderItem>().filter { it !== bgFolder && it !== webFolder }.map { it.key } + rows.filterIsInstance<VideoItem>().map { it.videoId } + rows.filterIsInstance<WebVid>().map { it.id }
    private fun inWebNow() = rows.isNotEmpty() && rows.all { it is WebVid }
    private fun selWebs(): List<WebVid> = rows.filterIsInstance<WebVid>().filter { it.id in sel }
    private fun selFolders(): List<FolderItem> = rows.filterIsInstance<FolderItem>().filter { it.key in sel && it !== bgFolder && it !== webFolder }
    private fun selVideos(): List<VideoItem> = rows.filterIsInstance<VideoItem>().filter { it.videoId in sel }
    private fun toggleSel(key: String) { if (!sel.remove(key)) sel.add(key); render() }
    private fun selectAll() { sel.clear(); sel.addAll(selectableKeys()); render() }
    private fun clearSel() { sel.clear(); render() }
    private fun toggleAll() { val all = selectableKeys(); if (all.isNotEmpty() && sel.size >= all.size) clearSel() else selectAll() }
    private fun applySelHeader() {
        val on = sel.isNotEmpty()
        normalBtns.forEach { it.visibility = if (on) View.GONE else View.VISIBLE }
        selMenuBtn.visibility = if (on) View.VISIBLE else View.GONE
        selCloseBtn.visibility = if (on) View.VISIBLE else View.GONE
        selAllBtn.visibility = if (selectableKeys().isEmpty()) View.GONE else View.VISIBLE
        if (on) {
            val fs = selFolders(); val vs = selVideos()
            titleTv.text = "☑ " + sel.size + (if (fs.isNotEmpty()) " فولدر محدد" else " $noun محدد")
            subTv.text = "▣ تحديد الكل · ⋮ الخيارات · ✕ إلغاء"
        }
    }
    private fun hideMany(vs: List<VideoItem>) {
        vs.forEach { hidden.add(it.videoId) }; saveHidden(); sel.clear(); render()
        toastMsg("🙈 اتخفى ${vs.size} $noun — اسحب لتحت وكمّل السحب لحد 🔒 وسيب عشان تفتح المخفي")
    }
    private fun unhideMany(vs: List<VideoItem>) { vs.forEach { hidden.remove(it.videoId) }; saveHidden(); sel.clear(); render(); toastMsg("👁 رجع ${vs.size} $noun للقايمة") }
    private fun selDetails(vids: List<VideoItem>, folders: Int) {
        val tr = vids.count { recMap[it.videoId]?.let { r -> r.subs > 0 } == true }
        val dur = vids.sumOf { it.durMs }
        GAlert(act).setTitle("ℹ تفاصيل المحدد")
            .setMessage((if (folders > 0) "الفولدرات: $folders\n" else "") + "عدد الفيديوهات: ${vids.size}\nالحجم: ${VideoLib.fmtSize(vids.sumOf { it.size })}\nالمدة الكلية: ${VideoLib.fmtDur(dur)}\nالمترجم منهم (كله أو جزء): $tr")
            .setPositiveButton("تمام", null).show()
    }
    /** ⋮ العلوي وقت التحديد: نفس قايمة الفولدر/الفيديو بس على كل المحدد */
    private fun selMenu(anchor: View) {
        val ws = selWebs()
        if (ws.isNotEmpty()) {
            popup(anchor, listOf("🗑 مسح المحدد من السجل (${ws.size})" to { webClearSelected(ws) }, "☑ تحديد الكل" to { selectAll() }, "🗑 مسح كل الفيديوهات المصطادة" to { webClearAll() }))
            return
        }
        val fs = selFolders(); val vs = selVideos()
        val vids = if (fs.isNotEmpty()) fs.flatMap { it.videos } else vs
        if (vids.isEmpty()) return
        val synth = FolderItem("__sel__", if (fs.isNotEmpty()) "المحدد (${fs.size} فولدر)" else "المحدد (${vs.size} فيديو)", "", vids)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (audio) items += "▶ تشغيل المحدد (${vids.size})" to { clearSel(); onPlayFolder(synth) }
        else items += "🌙 ترجمة المحدد في الخلفية (${vids.size})" to { clearSel(); onBgFolder(synth) }
        if (inHidden) items += "👁 إظهار المحدد (يرجع للقايمة)" to { unhideMany(vids) }
        else items += "🙈 إخفاء المحدد" to { hideMany(vids) }
        if (fs.size == 1 && vs.isEmpty() && !fs[0].virtual) items += "✏ إعادة تسمية الفولدر" to { clearSel(); onRenameFolder(fs[0]) }
        if (fs.isEmpty() && vs.size == 1 && !inHidden) {
            items += "✏ إعادة تسمية" to { clearSel(); onRename(vs[0]) }
            items += "📁 نقل" to { clearSel(); onMove(vs[0]) }
        }
        items += "ℹ تفاصيل" to { if (fs.isEmpty() && vs.size == 1) { clearSel(); onDetails(vs[0]) } else selDetails(vids, fs.size) }
        if (fs.isEmpty()) items += "📤 مشاركة" to { clearSel(); onShareMany(vids) }
        items += "🗑 مسح المحدد" to { clearSel(); onDeleteFolder(synth) }
        popup(anchor, items)
    }
}
