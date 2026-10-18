@file:OptIn(UnstableApi::class)
package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational

/** اختيار ملف من أي مدير ملفات (MiXplorer وغيره): GET_CONTENT + OPEN_DOCUMENT كخيار إضافي في نفس الـ chooser */
fun filePicker(mime: String, title: String, persist: Boolean): Intent {
    val get = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = mime
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (persist) Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION else 0))
    }
    return Intent.createChooser(get, title).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(open))
}

class MainActivity : Activity() {
    lateinit var link: EditText
    private var fromPlayer = false
    // (v215) نسخة احتياطية: save() بتاعة الإعدادات ما بتشتغلش بعد الاسترجاع (وإلا بتكتب القيم القديمة فوقه)
    private var noSaveCfg = false
    private var saveCfgFn: (() -> Unit)? = null
    private var dismissSettings: (() -> Unit)? = null
    private var libUi: LibraryUi? = null
    private var mediaOps: MediaOps? = null
    private var queueUi: QueueUi? = null
    private var lockUi: LockUi? = null
    private var stoppedAt = 0L
    private var keyLoadersRef: List<() -> Unit> = emptyList()
    private var permDone: (() -> Unit)? = null
    private var scanFn: () -> Unit = {}
    private var libStarted = false
    private var curTab = 0                       // (v117) 0 = الفيديوهات · 1 = يوتيوب
    private var ytUi: YtProUi? = null
    private var navUi: BottomNav? = null
    private var musicUi: MusicUi? = null            // (v189) صفحة الموسيقى
    private var tasksUiRef: TasksUi? = null
    private var showTabFn: (Int) -> Unit = {}
    private var musicOpenFn: (() -> Unit)? = null   // (v337) يفتح صفحة الموسيقى ومشغّلها الكامل (لتشغيل صوت يوتيوب)
    private var ytOpenFn: ((String) -> Unit)? = null   // (v327) فتح تفاصيل فيديو يوتيوب من إشعار
    private var tasksDlgRefresh: (() -> Unit)? = null
    /** (v324) صفحة المهام طلعت من الشريط السفلي (مكانها بقى يوتيوب): بتتفتح من الزرار العايم (TasksFab) أو ⚙️ أو الويدجت، وبتتخبّى بالرجوع. رقمها الداخلي 5 */
    private fun showTasksDialog() { showTabFn(5) }
    private var tabBeforeTasks = 0
    private var tabBeforeSettings = 0   // (v370) تبويب ⚙️ الإعدادات (رقمه 7): الرجوع منه للصفحة اللي جيت منها
    private var settingsHostRef: FrameLayout? = null
    // (v339) فتح فيديو/صوت يوتيوب فورًا بلودينج جوه المشغّل + الرجوع لنفس المكان
    private var ytLoadUi: YtLoadingPage? = null
    private var ytLoadTok = 0          // أي طلب فيديو جديد/إلغاء بيزوّده؛ النتيجة المتأخرة بتتجاهل
    private var ytLoadLaunched = false // المشغّل الحقيقي اتفتح (صفحة اللودينج بتتشال لما نرجع)
    private var ytAudTok = 0
    private var musicReturnTab = -1    // الصفحة اللي جينا منها لمشغّل الموسيقى (يوتيوب) عشان نرجعلها لما نقفله
    // (v324) زرار «المهام» العايم: دايرة حمراء بعدد المهام، بتتسحب لأي مكان (TasksFab.kt)
    private var tasksFabUi: TasksFab? = null
    private var uniSearchFn: ((String) -> Unit)? = null   // (v345) بحث شامل
    private var stripUi: StatusStrip? = null   // (v345) شريط الحالة الموحّد
    private var dockUi: MiniDock? = null   // (v344) المشغّل المصغّر العايم
    /** (v344) «ابحث في المتصفح»: بيفتح المتصفح ببحث جوجل عن النص */
    private fun webSearch(q: String) { saveCfgFn?.invoke(); NavAnim.set(curTab, 2); startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"))) }
    private var fabHide = false
    /** (v336) الزرار العايم اتشال؛ الدالة دلوقتي بتحدّث عدّاد زرار «المهام» في الشريط السفلي */
    private fun updateTasksFab() { try { val a = TaskCenter.active() + BgJobs.jobs.count { it.active }; val t = TaskCenter.total() + BgJobs.jobs.size; val n = if (a > 0) a else t; navUi?.label(5, if (n > 0) "المهام ($n)" else "المهام") } catch (_: Throwable) {} }
    private var toolsUiM: ToolsUi? = null
    private var pendingTool = ""
    private var pendingSrc: ToolSrc? = null
    override fun onCreate(b: Bundle?) {
        NetUse.init()
        fromPlayer = intent?.getBooleanExtra("from_player", false) == true
        if (fromPlayer) setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(b)
        if (fromPlayer) requestedOrientation = if (intent?.getBooleanExtra("land", false) == true) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT   // الإعدادات من المشغّل: بنفس اتجاه المشغّل
        UiWatchdog.start()
        Cfg.init(this); CrashLog.install(this); YtFast.startup();
        val th = Themes.byId(Cfg.str("theme", "mx"))
        val ui = Ui(this, th)
        applyBars(th)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(32), 0, ui.dp(110)); layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
        val keys = ui.input("مفاتيح Gemini (مفتاح في كل سطر)", Cfg.allMainKeys().joinToString("\n"), 3)
        val backup = ui.input("مفاتيح احتياطية (مفتاح في كل سطر)", "", 2)
        val extra = ui.input("مفاتيح إضافية (بتتضاف للأساسية — مفتاح في كل سطر)", "", 2)
        val vkeys = ui.input("مفاتيح الوضع البصري فقط (مفتاح في كل سطر)", Cfg.str("viskeys"), 2)
        // مفاتيح: كل مفتاح في خانة لوحده + زرار ＋ لإضافة أي عدد (الـ EditText الأصلي بيفضل هو مصدر الحقيقة ومش ظاهر)
        val keyLoaders = ArrayList<() -> Unit>()
        keyLoadersRef = keyLoaders
        // تنبيه تكرار المفتاح: لو نفس المفتاح في أي مكان تاني (أساسي/احتياطي/إضافي) بيظهر فوقه رقم المفتاح ومكانه
        val dupSources = listOf("الأساسية" to keys, "الاحتياطية" to backup, "الإضافية" to extra)
        val dupRefs = ArrayList<() -> Unit>()
        fun refreshDups() { dupRefs.forEach { it() } }
        fun dupHits(k: String, selfLabel: String, selfOrd: Int): List<String> {
            val out = ArrayList<String>()
            for ((lbl, et) in dupSources) {
                et.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, v ->
                    if (v == k && !(lbl == selfLabel && i + 1 == selfOrd)) out.add("$lbl رقم ${i + 1}")
                }
            }
            return out
        }
        fun keyRows(title: String, src: EditText, label: String): LinearLayout {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            var loading = false
            var addRowL: (String) -> Unit = {}
            fun sync() {
                if (loading) return
                src.setText((0 until list.childCount).map { (((list.getChildAt(it) as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(0) as EditText).text.toString().trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
            }
            fun refresh() {
                var ord = 0
                for (r in 0 until list.childCount) {
                    val w = list.getChildAt(r) as LinearLayout
                    val warn = w.getChildAt(0) as TextView
                    val k = ((w.getChildAt(1) as LinearLayout).getChildAt(0) as EditText).text.toString().trim()
                    if (k.isNotEmpty()) ord++
                    val hits = if (k.length > 10) dupHits(k, label, ord) else emptyList()
                    if (hits.isEmpty()) warn.visibility = View.GONE
                    else { warn.text = "⚠ المفتاح ده مستخدم قبل كده: " + hits.joinToString(" ، "); warn.visibility = View.VISIBLE }
                }
            }
            dupRefs.add { refresh() }
            fun addRow(v: String) {
                val et = EditText(this).apply {
                    hint = "مفتاح Gemini API"; setHintTextColor(th.muted); setTextColor(th.text); textSize = 13f; setText(v)
                    setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12), 0, ui.dp(12), 0); background = ui.box(th.surface, th.border, 10)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(e: android.text.Editable?) { sync(); refreshDups() }
                        override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                        override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                    })
                }
                val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
                val del = IconTextView(this).apply { text = "✕"; textSize = 16f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(12), 0, ui.dp(4), 0)
                    setOnClickListener { list.removeView(wrap); if (list.childCount == 0) addRowL(""); sync(); refreshDups() } }
                row.addView(et, LinearLayout.LayoutParams(0, ui.dp(44), 1f)); row.addView(del)
                val warn = IconTextView(this).apply { textSize = 12f; setTextColor(th.danger); visibility = View.GONE; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(2)) }
                wrap.addView(warn); wrap.addView(row)
                list.addView(wrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            }
            addRowL = { v -> addRow(v) }
            fun load() {
                loading = true; list.removeAllViews()
                src.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { addRow(it) }
                if (list.childCount == 0) addRow("")
                loading = false
                refreshDups()
            }
            keyLoaders.add { load() }
            box.addView(ui.text(title, 13f, th.muted, true).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
            box.addView(list)
            box.addView(IconTextView(this).apply {
                text = "＋  إضافة مفتاح"; textSize = 14f; setTextColor(th.text); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = ui.box(th.surface, th.border, 12)
                setOnClickListener { addRow(""); ((list.getChildAt(list.childCount - 1) as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(0).requestFocus() }
            }, LinearLayout.LayoutParams(-1, ui.dp(40)).apply { topMargin = ui.dp(6) })
            load()
            return box
        }
        val keysUi = keyRows("🔑 مفاتيح Gemini الأساسية", keys, "الأساسية")
        val backupUi = keyRows("🛟 مفاتيح احتياطية (أي باتش يرجع صفر جمل أو يفشل بيتترجم عليها بالترتيب)", backup, "الاحتياطية")
        val extraUi = keyRows("➕ مفاتيح إضافية (بتتضاف للأساسية)", extra, "الإضافية")
        val visKeysUi = keyRows("👁 مفتاح الوضع البصري", vkeys, "البصري")
        var modelSel = Cfg.str("model", Models.DEFAULT)
        val model = ui.input("الموديل (اكتب يدوي أو اختار من فوق)", modelSel)
        val modelNames = Models.builtin.map { it.id }
        val modelDesc = ui.text("", 12f, th.muted)
        fun descOf(id: String) = (Models.builtin.firstOrNull { it.id == id }?.desc ?: "موديل يدوي") + " — استهلاك النهارده: " + Quota.used(id) + " / " + Models.quotaOf(id) + " (تقريبي، بيتصفّر 00:00 PT)" +
            ModelWatch.pending().let { if (it.isEmpty()) "" else "\n🆕 موديل flash-lite أحدث اتلقى: " + it.joinToString("، ") + " — دوس «جلب كل الموديلات» واختاره" }
        modelDesc.text = descOf(modelSel)
        lateinit var applyModel: (String) -> Unit
        val modelChips = ui.chips(modelNames, { model.text.toString().trim() }) { applyModel(it) }
        val fetchModelsBtn = ui.button("🔄 جلب كل الموديلات من جوجل واختيار واحد") {
            val k = (keys.text.toString() + "\n" + extra.text.toString() + "\n" + backup.text.toString()).lines().map { it.trim() }.firstOrNull { it.length > 10 }
            if (k == null) { Notice.show(this, ("ضيف مفتاح API الأول").toString(), 2300L); return@button }
            Notice.show(this, ("⏳ بجيب الموديلات…").toString(), 2300L)
            Thread {
                try {
                    val rows = Api.listModels(k)
                    // الأحدث/الأهم فوق: gemini أولًا
                    val fresh = ModelWatch.candidates(rows.map { it.id }).toSet()
                    val sorted = rows.sortedWith(compareBy({ it.id !in fresh }, { !it.id.startsWith("gemini") }, { it.id }))
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (sorted.isEmpty()) { Notice.show(this, ("مفيش موديلات رجعت").toString(), 3600L); return@runOnUiThread }
                        val cur = model.text.toString().trim()
                        val labels = sorted.map { (if (it.id == cur) "✓ " else if (it.id in fresh) "🆕 " else "") + it.id + (if (it.display.isNotEmpty() && it.display != it.id) "\n" + it.display else "") }.toTypedArray()
                        GAlert(this).setTitle("اختار الموديل (${sorted.size})")
                            .setItems(labels) { _, which -> applyModel(sorted[which].id) }
                            .setNegativeButton("إلغاء", null).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread { Notice.show(this, ("⚠ فشل جلب الموديلات: " + (e.message ?: "").take(120)).toString(), 3600L) }
                }
            }.start()
        }
        var chunkSec = Cfg.int("chunk", 60).coerceIn(10, 600)
        // (v184) طول المقطع بقى أزرار ثابتة (30 / 60 / 90 / 120) بدل السلايدر — قيمة قديمة مش في الأزرار بتظهر على أقرب زرار ومابتتغيّرش إلا لو دوست
        val chunkOpts = listOf(30, 60, 90, 120)
        fun chunkLbl(v: Int) = "$v ثانية"
        val chunk = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text("طول المقطع", 14f, th.text, true))
            addView(ui.chips(chunkOpts.map { chunkLbl(it) }, { chunkLbl(chunkOpts.minByOrNull { Math.abs(it - chunkSec) }!!) }) { pick -> chunkSec = chunkOpts.first { chunkLbl(it) == pick } })
        }
        val ahead = ui.input("عدد المقاطع اللي بتترجم قدّام مكان التشغيل", Cfg.str("ahead", "3"))
        val hlsAhead = ui.input("روابط m3u8: عدد الباتشات اللي بتتحمّل مقدّمًا قدّام الترجمة (الباقي مابيتحمّلش لحد ما توصله)", Cfg.str("hls_ahead", "3"))
        val atrack = ui.input("رقم مسار الصوت (لو الفيديو فيه أكتر من لغة)", Cfg.str("atrack", "1"))
        val roster = ui.input("جدول الشخصيات: اسم:male أو female:وصف (سطر لكل شخصية). لو فاضي والتحليل التلقائي شغال هيتعبّى لوحده", Cfg.str("roster"), 3)
        val gloss = ui.input("مسرد مصطلحات ثابت (كل سطر: الكلمة = ترجمتها)", Cfg.str("gloss"), 3)
        val flags = linkedMapOf("autochars" to true, "autopron" to true, "autotpl" to true, "gapfill" to true, "hitiming" to false, "autosrt" to true, "soundtags" to true, "prefine" to true)
        val flagText = mapOf(
            "autochars" to "تحليل الشخصيات تلقائيًا", "autopron" to "تصحيح الضمائر تلقائيًا", "autotpl" to "ترجمة قالب الـ prompt للغات اللي ملهاش قالب جاهز",
            "gapfill" to "سدّ الفجوات تلقائيًا أثناء المشاهدة (بمفاتيح المراقبين/الاحتياطي، والجمل المستردة بين «»)",
            "autosrt" to "حفظ ملف SRT جنب الفيديو تلقائي لما الترجمة تخلص (محتاج «إدارة كل الملفات»)",
            "prefine" to "تنقيح جزئي أثناء الترجمة: كل مقطع يخلص، الجمل الجديدة (مع 25 جملة قبلها كسياق) بتتبعت بنصها الأصلي وترجمتها للتنقيح وتتصحّح الترجمة الحرفية — على مفاتيح تنقيح مخصوصة (البرنامج بيخصّصها لوحده من قايمة المفاتيح، ومفتاح الصور احتياطي لها) من غير ما ياخد من مفاتيح الترجمة؛ وفي الآخر بيحصل تنقيح كامل واحد",
            "hitiming" to "دقة توقيت أعلى (بيفك الصوت من قبل البداية بـ 3 ثواني — أبطأ شوية)",
            "soundtags" to "التقاط الأصوات الخلفية والهمهمات والموسيقى وعرضها كسطر وصف فوق الفيديو")
        val flagViews = flags.map { (k, d) -> ui.switchRow(flagText[k]!!, Cfg.bool(k, d)) { } }
        fun keyList(t: String) = t.lines().map { it.trim() }.filter { it.length > 10 }
        // ===== (v149) اختبار سرعة رد الـ AI من الإعدادات — زي لوج الباتشات في المشغّل: كل مفتاح × كل موديل + زمن الرد والأسرع =====
        class SpRes(val keyNo: Int, val model: String, val ms: Long, val ok: Boolean, val note: String)
        val speedOut = ui.text("", 12f, th.text).apply {
            typeface = android.graphics.Typeface.MONOSPACE; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.box(th.surface, th.border, 8); visibility = View.GONE
        }
        var speedWinner: String? = null
        var speedRunning = false
        val speedUseBtn = ui.button("🏁 استخدم الموديل الأسرع") {
            val w = speedWinner
            if (w != null) { applyModel(w); Notice.show(this, ("🏁 الموديل اتغيّر لـ " + w.removePrefix("gemini-")).toString(), 2600L) }
        }.apply { visibility = View.GONE }
        val speedBtn = ui.button("🏎 اختبر سرعة رد الـ AI (كل مفتاح × كل موديل)") {
            if (speedRunning) { Notice.show(this, ("الاختبار شغّال لسه…").toString(), 1800L); return@button }
            val ks = (keyList(keys.text.toString()) + keyList(extra.text.toString()) + keyList(backup.text.toString())).distinct().take(6)
            if (ks.isEmpty()) { Notice.show(this, ("ضيف مفتاح API الأول").toString(), 2300L); return@button }
            // (v149) الموديل اللي مختاره + flash-lite-latest، وكل المفاتيح على الاتنين
            val ms = listOf(model.text.toString().trim().ifEmpty { Models.DEFAULT }, "gemini-flash-lite-latest").distinct()
            val total = ks.size * ms.size
            val res = java.util.ArrayList<SpRes>()
            val left = java.util.concurrent.atomic.AtomicInteger(total)
            speedRunning = true; speedWinner = null
            speedUseBtn.visibility = View.GONE; speedOut.visibility = View.VISIBLE
            fun sec(t: Long) = String.format(java.util.Locale.US, "%.1f", t / 1000.0) + "ث"
            fun render(done: Boolean) {
                val rs = synchronized(res) { res.toList() }
                val okRs = rs.filter { it.ok }.sortedBy { it.ms }
                val sb = android.text.SpannableStringBuilder()
                if (!done) sb.append("⏳ شغّال… ").append(rs.size.toString()).append(" / ").append(total.toString())
                okRs.forEachIndexed { i, r ->
                    if (sb.isNotEmpty()) sb.append('\n')
                    val a = sb.length
                    sb.append("🔑").append(r.keyNo.toString()).append(" · ").append(r.model.removePrefix("gemini-")).append(": ").append(sec(r.ms))
                    if (i == 0) { sb.append(" 🏆⚡"); sb.setSpan(android.text.style.ForegroundColorSpan(0xFFF5B041.toInt()), a, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                for (r in rs.filter { !it.ok }.sortedWith(compareBy({ it.model }, { it.keyNo }))) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("🔑").append(r.keyNo.toString()).append(" · ").append(r.model.removePrefix("gemini-")).append(": ❌ ").append(r.note)
                }
                if (done && okRs.isNotEmpty()) {
                    sb.append("\n\n📊 متوسط كل موديل:")
                    okRs.groupBy { it.model }.entries.sortedBy { e -> e.value.map { it.ms }.average() }.forEach { e ->
                        sb.append("\n  ").append(e.key.removePrefix("gemini-")).append(": ").append(sec(e.value.map { it.ms }.average().toLong())).append(" (").append(e.value.size.toString()).append(" مفتاح)")
                    }
                }
                if (done && okRs.isEmpty()) sb.append("\n\nمفيش رد سليم من أي مفتاح — راجع المفاتيح أو النت")
                speedOut.text = sb
            }
            render(false)
            for (mi in ms.indices) for (ki in ks.indices) {
                val md = ms[mi]; val key = ks[ki]
                Thread {
                    val t0 = System.currentTimeMillis()
                    val r = try {
                        val o = Api.generate(md, key, "رد بكلمة واحدة بس: تمام", null, 200, 0.0, false)
                        val took = System.currentTimeMillis() - t0
                        if (o.text.isBlank()) SpRes(ki + 1, md, took, false, "رد فاضي " + o.finish) else SpRes(ki + 1, md, took, true, "")
                    } catch (e: Throwable) { SpRes(ki + 1, md, 0L, false, (e.message ?: e.toString()).take(60)) }
                    synchronized(res) { res.add(r) }
                    val fin = left.decrementAndGet() == 0
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (fin) {
                            speedRunning = false
                            val w = synchronized(res) { res.filter { it.ok }.minByOrNull { it.ms } }
                            speedWinner = w?.model
                            if (w != null) speedUseBtn.visibility = View.VISIBLE
                        }
                        render(fin)
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        val langs = listOf("مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني", "فصحى")
        val styles = listOf("حرفي", "شعبي", "جرئ", "+18")
        var lang = Cfg.str("lang", "فصحى"); var style = Cfg.str("style", "حرفي"); var themeId = th.id
        link = ui.input("رابط فيديو مباشر (mp4 / m3u8)", "")
        fun save() {
            if (noSaveCfg) return
            val e = Cfg.p.edit().putString("keys", keys.text.toString()).putString("backup", backup.text.toString())
                .putString("model", model.text.toString().trim()).putInt("chunk", chunkSec).putString("extra", extra.text.toString())
                .putString("roster", roster.text.toString()).putString("gloss", "")
                .putString("lang", lang).putString("style", style).putString("theme", themeId)
                .putString("viskeys", vkeys.text.toString())
            ahead.text.toString().trim().toIntOrNull()?.let { e.putInt("ahead", it) }
            atrack.text.toString().trim().toIntOrNull()?.let { e.putInt("atrack", it) }
            hlsAhead.text.toString().trim().toIntOrNull()?.let { e.putInt("hls_ahead", it.coerceIn(1, 8)) }
            flags.keys.forEachIndexed { i, k -> e.putBoolean(k, (flagViews[i] as Switch).isChecked) }
            e.apply()
        }
        saveCfgFn = { save() }
        val themeChips = ui.chips(Themes.all.map { it.name }, { Themes.byId(themeId).name }) { n ->
            themeId = Themes.all.first { it.name == n }.id; save()
            Cfg.p.edit().putString("theme", themeId).commit()
            if (!fromPlayer) intent?.putExtra("reopen_tab", "appearance")
            recreate()
        }
        // (v352) سويتش داكن/فاتح + أوضاع الواجهة (شكل الزراير والفولدرات والأنيميشن والأصوات)
        val darkSwitch = ui.switchRow("🌙 الوضع الداكن (سويتش داكن / فاتح)", !Themes.byId(themeId).isLight) { dark ->
            if (dark == !Themes.byId(themeId).isLight) return@switchRow
            themeId = Themes.partner(themeId)
            Cfg.p.edit().putString("theme", themeId).commit()
            if (!fromPlayer) intent?.putExtra("reopen_tab", "appearance")
            recreate()
        }
        val skinChips = ui.chips(Skins.all.map { it.name }, { Skins.cur().name }) { n ->
            val id = Skins.all.first { it.name == n }.id
            Cfg.p.edit().putString("skin", id).commit(); Skins.set(id)
            if (!fromPlayer) intent?.putExtra("reopen_tab", "appearance")
            recreate()
        }
        val skinSoundSwitch = ui.switchRow("🔊 أصوات اللمس (حسب الوضع)", Skins.soundOn()) { on -> Cfg.p.edit().putString("skin_sound", if (on) "1" else "0").apply() }
        // (v355) إعدادات يوتيوب جوه إعدادات البرنامج (بدل الدايلوج المنفصل)
        val ytQ = listOf("أعلى جودة", "1080p", "720p", "480p", "360p", "240p")
        val ytQv = intArrayOf(0, 1080, 720, 480, 360, 240)
        val ytLangN = listOf("العربية", "الأصلي", "English", "Français", "Español", "Türkçe", "Deutsch", "Русский", "हिन्दी")
        val ytLangV = listOf("ar", "orig", "en", "fr", "es", "tr", "de", "ru", "hi")
        var settingsDlg0: TabbedDialog? = null; var qui0: QueueUi? = null
        val ytSettingsViews: List<View> = listOf(
            ui.section("🎬 لما تدوس على فيديو", true,
                ui.chips(listOf("صفحة المشاهدة", "المشغّل مباشرة"), { if (YtOpen.mode() == YtOpen.PLAYER) "المشغّل مباشرة" else "صفحة المشاهدة" }) { n -> Cfg.put("yt_open_mode", if (n == "المشغّل مباشرة") YtOpen.PLAYER else YtOpen.PAGE) },
                ui.switchRow("▶ شغّل تلقائي في المشغّل المصغّر", YtOpen.autoPlay()) { Cfg.put("yt_mini_auto", if (it) "1" else "0") },
                ui.text("صفحة المشاهدة: الفيديو بيكبر بأنيميشن ومعاه الوصف والتعليقات والمقترحات والاشتراك، وزرار التكبير في ركن المشغّل المصغّر بيفتح مشغّلك الكامل من نفس الثانية. المشغّل مباشرة: الدوسة بتفتح المشغّل على طول.", 12f, th.muted)),
            ui.section("🎞 الجودة الافتراضية", true,
                ui.chips(ytQ, { ytQ[ytQv.indexOf(Cfg.int("yt_maxh", 0)).coerceAtLeast(0)] }) { n -> Cfg.put("yt_maxh", ytQv[ytQ.indexOf(n).coerceAtLeast(0)].toString()) },
                ui.text("أعلى جودة بيتفتح بيها الفيديو. على الداتا اختار 360p أو 480p.", 12f, th.muted)),
            ui.section("🔊 لغة صوت الفيديو", true,
                ui.chips(ytLangN, { ytLangN[ytLangV.indexOf(YtLang.pref()).coerceAtLeast(0)] }) { n -> Cfg.put("yt_alang", ytLangV[ytLangN.indexOf(n).coerceAtLeast(0)]); YtExtract.clearPicks(); YtFast.clearCache() },
                ui.text("لو الفيديو له أكتر من لغة صوت بيفتح بالعربي، ولو مفيش عربي بيفتح بالصوت الأصلي. وفي المشغّل بيظهر مربع 🔊 جنب الجودة تبدّل بين اللغات.", 12f, th.muted)),
            ui.section("🔔 الإشعارات والسجل", true,
                ui.switchRow("🔔 إشعارات الفيديوهات الجديدة من اشتراكاتي", YtNotify.enabled()) { YtNotify.setEnabled(this, it) },
                ui.switchRow("🕘 حفظ سجل المشاهدة", YtStore.histOn()) { Cfg.put("ytp_hist_on", if (it) "1" else "0") },
                ui.switchRow("🔄 الاشتراك يتسجّل على حسابي كمان (لو مسجّل الدخول)", YtSubs.syncOn()) { Cfg.put("yt_acc_sync", if (it) "1" else "0") },
                ui.button("🔔 رجّع إشعارات كل القنوات (بعد ما قفلت قنوات بالجرس)") { Cfg.put("ytp_notif_off", ""); Notice.show(this, ("🔔 رجعت إشعارات كل القنوات").toString(), 2200L) },
                ui.text("جرس 🔔 في صفحة كل قناة: إشعار بكل فيديو جديد أو بدون. اللايك والتعليق من صفحة المشاهدة بيتسجّلوا على حسابك بس لو مسجّل الدخول.", 12f, th.muted)),
            ui.section("👤 حساب جوجل", false,
                ui.text(if (YtAccount.loggedIn()) "مسجّل الدخول — الاشتراكات بتتزامن من حسابك." else "مش مسجّل. سجّل من تبويب يوتيوب ▸ أنت.", 13f, th.text),
                ui.button("▶ افتح يوتيوب") { settingsDlg0?.dialog?.dismiss(); showTabFn(4) },
                ui.button("🚪 تسجيل الخروج") { YtAccount.logout(); Notice.show(this, ("اتسجّل الخروج").toString(), 1800L) }),
            ui.section("🗑 مسح البيانات", false,
                ui.button("🗑 مسح سجل المشاهدة") { YtStore.write(YtStore.HIST, emptyList()); Notice.show(this, ("🗑 السجل اتمسح").toString(), 1800L) },
                ui.button("🗑 مسح سجل التحميلات") { YtStore.write(YtStore.DL, emptyList()); Notice.show(this, ("🗑 سجل التحميلات اتمسح").toString(), 1800L) }))
        // ===== الواجهة بشكل نسخة الـ HTML: دوائر علوية + كارت فيديو + كبسولة + شبكة أزرار + زرار الترجمة =====
        var recentDlg: android.app.Dialog? = null
        val recentFile = File(filesDir, "recent.json")
        val recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun uriOk(u: String): Boolean = try { contentResolver.openInputStream(Uri.parse(u))?.close(); true } catch (_: Exception) { false }
        fun rebuildRecents() {
            recentBox.removeAllViews()
            val recents = Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" })
            if (recents.isEmpty()) { recentBox.addView(ui.text("مفيش فيديوهات محفوظة لسه", 13f, th.muted)); return }
            recents.forEach { r ->
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                info.addView(ui.text(r.title, 14f, th.text, true))
                info.addView(ui.text("جمل ${r.subs} · تغطية ${r.percent}% · وقف عند ${PlayerLogic.clock((r.posSec * 1000).toLong())}", 11f, th.muted))
                val del = IconTextView(this).apply {
                    text = "🗑"; textSize = 18f; gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
                    setOnClickListener {
                        try { recentFile.writeText(Recents.toJson(Recents.remove(Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }), r.id))) } catch (e: Exception) { LogStore.err("Main:264", e) }
                        rebuildRecents()
                    }
                }
                val row = LinearLayout(this).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.box(th.surface, th.border, 8)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) }
                    addView(info, LinearLayout.LayoutParams(0, -2, 1f)); addView(del)
                    setOnClickListener {
                        save()
                        if (r.uri.isNotEmpty()) {
                            if (uriOk(r.uri)) { recentDlg?.dismiss(); play("", Uri.parse(r.uri)) }
                            else Notice.show(this@MainActivity, ("الملف ده مبقاش متاح (الصلاحية اتفقدت) — افتحه تاني من \"فتح فيديو\"").toString(), 3600L)
                        } else if (r.url.isNotEmpty()) { recentDlg?.dismiss(); play(r.url, null) }
                    }
                }
                recentBox.addView(row)
            }
        }
        val keyChipTv = ui.text("", 13f, th.text, true)
        val keyTailTv = ui.text("", 12f, th.muted)
        val keyPctTv = ui.text("", 13f, th.primary, true)
        val modelChipTv = ui.pillChip("") { }
        fun refreshChip() {
            val ks = (keyList(keys.text.toString()) + keyList(extra.text.toString())).distinct()
            keyChipTv.text = (if (ks.isEmpty()) "✗ " else "✓ ") + "🔀 " + ks.size
            keyTailTv.text = if (ks.isEmpty()) "مفيش مفتاح" else "AQ.A…" + ks[0].takeLast(5)
            val mid = model.text.toString().trim()
            val q = Models.quotaOf(mid).coerceAtLeast(1)
            keyPctTv.text = (Quota.used(mid) * 100 / q).toString() + "%"
            modelChipTv.text = "▾ " + mid.removePrefix("gemini-") + " ●"
        }
        applyModel = { id ->
            model.setText(id); modelSel = id; modelDesc.text = descOf(id)
            Cfg.p.edit().putString("model", id).apply()   // يتحفظ فورًا
            ModelWatch.refreshPending(); modelDesc.text = descOf(id)
            refreshChip()
        }
        val sp = styleParts(ui, th)
        val lk = LockUi(this, ui, th); lockUi = lk
        val lockStatus = ui.text("", 13f, th.text)
        lateinit var bioBtn: android.widget.Button
        fun refreshLock() {
            lockStatus.text = if (lk.isSet) "🔒 القفل شغّال (نمط" + (if (lk.bioOn) " + بصمة" else "") + ")" else "🔓 مفيش قفل لسه — بيتعمل أول مرة تفتح المخفي"
            bioBtn.text = if (!lk.bioAvailable) "👆 البصمة: مش متاحة على جهازك" else if (lk.bioOn) "👆 فتح بالبصمة: مفعّل (اضغط للإيقاف)" else "👆 فتح بالبصمة: مقفول (اضغط للتفعيل)"
            bioBtn.alpha = if (lk.isSet && lk.bioAvailable) 1f else 0.5f
        }
        bioBtn = ui.button("") {
            if (!lk.isSet) Notice.show(this, ("اعمل نمط الأول").toString(), 2300L)
            else if (lk.bioOn) { lk.disableBio(); refreshLock() } else lk.enableBio { refreshLock() }
        }
        refreshLock()
        fun fl(vararg k: String): Array<View> = k.map { key -> flagViews[flags.keys.indexOf(key)] }.toTypedArray()
        // (v167) بريسيت جودة الصوت المُرسَل للـ AI: سريع (AAC 32kbps) / نورمال (WAV خام)
        val audioPresetTitle = ui.text("جودة الصوت المُرسَل للـ AI", 13f, th.muted)
        val audioPresetDesc = ui.text("", 12f, th.muted)
        val apFast = "⚡ سريع"; val apNormal = "🎚 نورمال"
        fun apDesc() { audioPresetDesc.text = if (AudioEnc.preset() == AudioEnc.NORMAL) "WAV خام 16kHz من غير ضغط (≈32KB في الثانية) — أعلى دقة، لكن الرفع أبطأ (حوالي 8 أضعاف الحجم)." else "ضغط AAC بمعدل 32kbps (≈4KB في الثانية) — أصغر حوالي 8 مرات، والرفع أسرع. مناسب لمعظم الأفلام." }
        val audioPresetChips = ui.chips(listOf(apFast, apNormal), { if (AudioEnc.preset() == AudioEnc.NORMAL) apNormal else apFast }) {
            Cfg.put(AudioEnc.PRESET_KEY, if (it == apNormal) AudioEnc.NORMAL else AudioEnc.FAST); apDesc()
        }
        apDesc()
        // (v253) الترانسكريبشن الأوفلاين: Whisper على الجهاز من غير مفتاح. جيميناي بيترجم وهو الاحتياطي لو الأوفلاين فشل
        val offStatus = ui.text("", 12f, th.muted)
        val offProg = ui.text("", 12f, th.muted)
        lateinit var offDlBtn: android.widget.Button
        fun offRefresh() {
            offStatus.text = Offline.status() + "\n" + Offline.model().note
            offDlBtn.text = if (Offline.downloading()) "⏹ إلغاء التنزيل" else if (Offline.downloaded()) "🗑 مسح الموديل (دوسة طويلة)" else "⬇ تنزيل الموديل (~" + Offline.model().approxMb + " ميجا)"
        }
        val offModelChips = ui.chips(Offline.models.map { it.label }, { Offline.model().label }) { l ->
            Offline.models.firstOrNull { m -> m.label == l }?.let { m -> Cfg.put("off_model", m.id); TrWizard.syncSaved("off_model", m.id) }; offProg.text = ""; offRefresh()
        }
        offDlBtn = ui.button("") {
            if (Offline.downloading()) { Offline.cancelDownload() }
            else if (Offline.downloaded()) { Notice.show(this, "دوس دوسة طويلة على الزرار عشان تمسح الموديل", 2300L) }
            else {
                offProg.text = "⏳ بيبدأ التنزيل…"
                Offline.download(Offline.model(), { a, t -> runOnUiThread { offProg.text = "⬇ " + (a / 1048576) + " / " + (t / 1048576) + " ميجا" } },
                    { err -> runOnUiThread { offProg.text = if (err == null) "✅ الموديل نزل" else "❌ $err"; offDlBtn.postDelayed({ offRefresh() }, 500) } })
                offRefresh()
            }
        }
        offDlBtn.setOnLongClickListener {
            if (!Offline.downloading() && Offline.downloaded()) { Offline.delete(); offProg.text = ""; offRefresh(); true } else false
        }
        offRefresh()
        // (v257) محرك التفريغ (Whisper / SenseVoice) + الترجمة بعده + الخطة المحفوظة — كانوا في شاشة «▶ ترجمة» بس ومالهمش مكان في الإعدادات
        val sEng1 = "🎧 Whisper (كل اللغات)"; val sEng2 = "⭐ SenseVoice (إنجليزي · ياباني · كوري · صيني)"
        val sherpaStatus = ui.text("", 12f, th.muted)
        val sherpaProg = ui.text("", 12f, th.muted)
        lateinit var sherpaDlBtn: android.widget.Button
        fun sherpaRefresh() {
            val wantS = Cfg.str("stt_engine", "whisper") == "sherpa"
            val eff = Offline.engine().id
            sherpaStatus.text = "SenseVoice: " + SherpaStt.status() + "\nالمحرك الفعلي دلوقتي: " + (if (eff == "sherpa") "SenseVoice" else "Whisper" + (if (wantS) " (SenseVoice مش بيفهم لغة الكلام المختارة أو مش شغّال على جهازك)" else ""))
            sherpaDlBtn.text = if (Offline.downloading()) "⏹ إلغاء التنزيل" else if (SherpaStt.downloaded()) "🗑 مسح موديل SenseVoice (دوسة طويلة)" else "⬇ تنزيل موديل SenseVoice (~" + SherpaStt.MODEL_MB + " ميجا)"
        }
        val sttEngChips = ui.chips(listOf(sEng1, sEng2), { if (Cfg.str("stt_engine", "whisper") == "sherpa") sEng2 else sEng1 }) {
            Cfg.put("stt_engine", if (it == sEng2) "sherpa" else "whisper"); TrWizard.syncSaved("stt_engine", if (it == sEng2) "sherpa" else "whisper")
            if (it == sEng2 && !SherpaStt.downloaded()) Notice.show(this, "نزّل موديل SenseVoice الأول، وإلا جيميناي هو اللي هينسخ", 2800L)
            sherpaRefresh()
        }
        sherpaDlBtn = ui.button("") {
            if (Offline.downloading()) { Offline.cancelDownload() }
            else if (SherpaStt.downloaded()) { Notice.show(this, "دوس دوسة طويلة على الزرار عشان تمسح الموديل", 2300L) }
            else if (!SherpaStt.supported()) { Notice.show(this, "جهازك مش بيدعم SenseVoice (محتاج معالج 64-bit)", 2600L) }
            else {
                sherpaProg.text = "⏳ بيبدأ التنزيل…"
                Offline.downloadSherpa({ a, t -> runOnUiThread { sherpaProg.text = "⬇ " + (a / 1048576) + " / " + (t / 1048576) + " ميجا" } },
                    { err -> runOnUiThread { sherpaProg.text = if (err == null) "✅ الموديل نزل" else "❌ $err"; sherpaDlBtn.postDelayed({ sherpaRefresh() }, 500) } })
                sherpaRefresh()
            }
        }
        sherpaDlBtn.setOnLongClickListener {
            if (!Offline.downloading() && SherpaStt.downloaded()) { Offline.deleteSherpa(); sherpaProg.text = ""; sherpaRefresh(); true } else false
        }
        sherpaRefresh()
        val savedPlanTxt = ui.text("", 12f, th.muted)
        fun planRefresh() { savedPlanTxt.text = TrWizard.savedPlan()?.let { "المحفوظ لزرار «▶ ترجمة»: " + TrWizard.describe(it).replace("\n", " · ") } ?: "مفيش إعداد محفوظ لزرار «▶ ترجمة» — بيسألك كل مرة." }
        val planBtn = ui.button("⚙ طريقة الترجمة (نفس زرار ⚙ في المشغّل)") {
            try { TrWizard.show(this, true) { pl, save, _ -> if (save) { TrWizard.save(pl); planRefresh() } } }
            catch (e: Throwable) { LogStore.err("Main:trWizard", e); Notice.show(this, "تعذّر فتح شاشة طريقة الترجمة", 2300L) }
        }
        planRefresh()
        val offLangChips = ui.chips(Offline.langLabels, { Offline.langLabels[Offline.langIds.indexOf(Offline.lang()).coerceAtLeast(0)] }) { val lid = Offline.langIds[Offline.langLabels.indexOf(it).coerceAtLeast(0)]; Cfg.put("stt_lang", lid); TrWizard.syncSaved("stt_lang", lid) }
        val offPartLabels = listOf("⚡ لحظي (كل باتش)", "٣ دقايق", "٥ دقايق", "١٠ دقايق", "٢٠ دقيقة"); val offPartVals = listOf(1, 3, 5, 10, 20)
        val offPartChips = ui.chips(offPartLabels, { offPartLabels[offPartVals.indexOf(Cfg.int("ctx_min", 3)).coerceAtLeast(1)] }) { Cfg.put("ctx_min", offPartVals[offPartLabels.indexOf(it).coerceAtLeast(0)].toString()) }
        val cbLabels = listOf("٢ باتش", "٣ باتشات"); val cbVals = listOf(2, 3)   // (v400) وضع «وشبر + جيميناي بالسياق»
        val ctxBatchChips = ui.chips(cbLabels, { cbLabels[cbVals.indexOf(Cfg.int("ctx_batches", 3)).coerceAtLeast(1)] }) { Cfg.put("ctx_batches", cbVals[cbLabels.indexOf(it).coerceAtLeast(0)].toString()) }
        val vaOn = "⚡ تلقائي مع كل فيديو"; val vaOff = "✋ يدوي (بريسيتس / زرار 👁)"
        val visAutoChips = ui.chips(listOf(vaOn, vaOff), { if (VisualMode.auto()) vaOn else vaOff }) { Cfg.put("vis_open_auto", if (it == vaOn) "1" else "0") }   // (v249) مفتاح مستقل: الافتراضي يدوي
        // (v198) إعدادات مشغّل الموسيقى
        val lyrFontChips = ui.chips(SubStyle.fonts.map { it.label }, { (SubStyle.fonts.firstOrNull { f -> f.id == Cfg.str("lyr_font", "Noto Sans Arabic") } ?: SubStyle.fonts[0]).label }) { l ->
            SubStyle.fonts.firstOrNull { f -> f.label == l }?.let { f -> Cfg.put("lyr_font", f.id) }
        }
        val lyrSizeChips = ui.chips(listOf("16", "20", "24", "28", "32"), { (Cfg.str("lyr_size", "20").trim().toIntOrNull() ?: 20).toString() }) { Cfg.put("lyr_size", it) }
        // (v212) ميزات المشاهدة + أسلوب الترجمة الحية + تحويل اللهجة + الخروج بالخلفية: كانت في المشغّل بس. دلوقتي هنا كمان، وبنفس مفاتيح Cfg بتاعة المشغّل (الاتنين دايمًا متطابقين)
        fun fxPut(k: String, v: String) { Cfg.put(k, v); Extras.load() }
        fun fxSwitch(label: String, key: String, def: Boolean) = ui.switchRow(label, Cfg.bool(key, def)) { fxPut(key, if (it) "1" else "0") }
        val toneStyles = listOf("حرفي", "شعبي", "جرئ", "+18"); val toneDegrees = listOf("خفيفة", "متوسطة", "شديدة")
        val toneChips = ui.chips(toneStyles, { Cfg.str("tone_style", "").ifBlank { "حرفي" } }) { Cfg.put("tone_style", if (it == "حرفي") "" else it) }
        val toneDegChips = ui.chips(toneDegrees, { Cfg.str("tone_strength", "متوسطة").ifBlank { "متوسطة" } }) { Cfg.put("tone_strength", it) }
        val convOpts = listOf("فصحى (حرفية)", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني")
        val convChips = ui.chips(convOpts, { Cfg.str("conv_dialect", "").ifBlank { convOpts[0] } }) { val dv = if (it == convOpts[0]) "" else it; Cfg.put("conv_dialect", dv); TrWizard.syncSaved("conv_dialect", dv) }
        val watchViews = listOf<View>(
            ui.section("🎚 أسلوب وشدة الترجمة", false,
                ui.text("الأسلوب:", 12f, th.muted), toneChips, ui.text("الشدة (بتشتغل مع شعبي / جرئ / +18 بس):", 12f, th.muted), toneDegChips,
                ui.text("الباتشات الجاية بتتترجم بيه. «طبّق» على اللي اتترجم فعلًا من زرار اللغة 🌐 جوه المشغّل.", 12f, th.muted)),
            ui.section("🔄 تحويل اللهجة التلقائي", false, convChips,
                ui.text("فصحى (حرفية) = من غير تحويل. غير كده = الباتشات الجاية بتتحوّل للهجة دي. تحويل اللي اتترجم قبل كده من زرار اللغة 🌐 جوه المشغّل.", 12f, th.muted)),
            ui.section("🌙 الخروج من المشغّل", false,
                fxSwitch("اسألني لما أخرج: أكمّل الترجمة في الخلفية؟ (لو مقفول: الخروج بيقفل الترجمة خالص)", "ask_bg_exit", true)),
            ui.section("🔴 ترجمة حية فوق أي تطبيق", false,
                ui.text("بيلقط صوت الجهاز ويعرض ترجمة عايمة. محتاج أندرويد 10+ وإذن العرض فوق التطبيقات، وبعض التطبيقات (نتفليكس وغيره) بتمنع التقاط الصوت.", 12f, th.muted),
                ui.button("▶ شغّل الترجمة الحية") { startActivity(Intent(this, LiveRequestActivity::class.java)) }))
        // (v235) نفس ترتيب الأقسام في كل أشكال الإعدادات (SettingsTabs): المفاتيح والمحرك · الترجمة وشكلها · الصوت والصورة · عام · الموسيقى. المسرد اتشال.
        fun tab(id: String, content: List<View>, preview: Boolean = false) = TabDef(id, SettingsTabs.label(id), content, preview, SettingsTabs.sub(id), SettingsTabs.group(id))
        val settingsDlg = TabbedDialog(this, ui, "⚙️ الإعدادات", listOf(
            // ===== 🚀 خط الترجمة: الخطة (تفريغ ← ترجمة ← لهجة) + اللهجة والأسلوب + البرومبتات =====
            tab("general", listOf<View>(
                ui.text("اختار إيه اللي يترجم واللهجة والأسلوب هنا. المفاتيح في 🔑، والتفريغ في 🎙، والموديل والسرعة في 🤖.", 12f, th.muted),
                ui.section("🎯 إيه اللي يترجم؟", true,
                    ui.chips(listOf(SourceMode.label(SourceMode.AUDIO), SourceMode.label(SourceMode.VISUAL), SourceMode.label(SourceMode.HYBRID)), { SourceMode.label() }) { SourceMode.set(listOf(SourceMode.AUDIO, SourceMode.VISUAL, SourceMode.HYBRID).first { id -> SourceMode.label(id) == it }) },
                    ui.text("البصري:", 12f, th.muted),
                    ui.chips(listOf(SourceMode.kindLabel("scene"), SourceMode.kindLabel("hard")), { SourceMode.kindLabel() }) { Cfg.put("tr_vkind", if (it == SourceMode.kindLabel("hard")) "hard" else "scene") }),
                savedPlanTxt, planBtn,
                ui.section("🗣 اللهجة والأسلوب", true,
                    ui.text("اللهجة:", 12f, th.muted),
                    ui.chips(langs, { lang }) { lang = it; save(); val dv = if (it == "فصحى") "" else it; Cfg.put("conv_dialect", dv); TrWizard.syncSaved("conv_dialect", dv) },
                    ui.text("أسلوب الترجمة:", 12f, th.muted),
                    ui.chips(styles, { style }) { style = it; save() }),
                watchViews[0], watchViews[1],
                ui.button("📝 البرومبتات (شوف · انسخ · عدّل · استبدل)") { PromptsUi.show(this, ui) })),
            // ===== 🔑 المفاتيح: جيميناي · الوضع البصري · محركات خارجية =====
            tab("keys", listOf<View>(
                ui.section("🆓 من غير مفتاح؟ تمام", true,
                    ui.text("المفتاح اختياري. من غيره البرنامج بيشتغل بتفريغ على الجهاز (أوفلاين) + ترجمة جوجل المجانية. ومعاه الترجمة بتبقى أدق وأسرع (جيميناي بيفهم السياق).", 13f, th.text),
                    ui.button("🎙 جهّز التفريغ على الجهاز (من غير مفتاح)") { settingsShow?.invoke("audio") }),
                ui.section("✨ جيميناي", false,
                ui.button("❓ إزاي أجيب مفتاح Gemini؟ (وألصقه)", true) {
                    showKeyGuide { k ->
                        val cur = keys.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                        val hit = dupHits(k, "", -1)
                        if (hit.isNotEmpty()) Notice.show(this, ("⚠ المفتاح ده مستخدم قبل كده: " + hit.joinToString(" ، ")).toString(), 3600L)
                        else { keys.setText((cur + k).joinToString("\n")); keyLoaders.forEach { it() } }
                        Cfg.p.edit().putString("keys", keys.text.toString()).apply()
                    }
                },
                ui.button("📊 إحصائية الاستهلاك والكوتة") { StatsUi(this, ui, th).show() },
                                ui.section("🔑 كل المفاتيح", true,
                    ui.text("حط كل مفاتيحك هنا في قايمة واحدة — البرنامج بيوزّعها لوحده: مفاتيح للترجمة (أغلبها)، ومفتاح أو اتنين للتنقيح الجزئي أثناء الترجمة، ومفتاح احتياطي لو مفتاح اتعطّل (من 6 مفاتيح). التنقيح الجزئي بيبدأ من 3 مفاتيح.", 12f, th.muted), keysUi),
                                ui.section("👁 مفتاح الوضع البصري فقط", false,
                    ui.text("الوضع البصري (👁) بيشتغل بالمفاتيح اللي هنا بس، ومش بياخد أبدًا من مفاتيح الترجمة. لو سبتها فاضية الوضع البصري مش هيشتغل. ولما الوضع البصري مايكونش شغّال المفتاح ده بيشتغل كمفتاح احتياطي للترجمة والتنقيح.", 12f, th.muted), visKeysUi)),
                View(this))),
            // ===== 🎙 التفريغ والصوت: موديلات الجهاز + إعدادات الصوت =====
            tab("audio", listOf<View>(
                ui.text("التفريغ = تحويل الكلام لنص. محرك الجهاز مجاني وبيشتغل من غير نت (بتنزّل الموديل مرة واحدة). الباقي: مسار الصوت وجودته.", 12f, th.muted),
                ui.section("🔊 الصوت", true, atrack, audioPresetTitle, audioPresetChips, audioPresetDesc, *fl("soundtags", "hitiming")),
                ui.section("📴 التفريغ على الجهاز (من غير مفتاح)", true,
                    ui.text("اختيار التفريغ (جيميناي / على الجهاز / سحابي) من خط الترجمة. هنا بس تنزيل ومسح الموديلات واختيار المحرك واللغة.", 12f, th.muted),
                    ui.text("محرك التفريغ:", 12f, th.muted), sttEngChips, sherpaStatus, sherpaDlBtn, sherpaProg,
                    ui.text("حجم موديل Whisper:", 12f, th.muted), offModelChips, offStatus, offDlBtn, offProg,
                    ui.text("لغة الكلام (لو عارفها بتقلّل أخطاء الاكتشاف التلقائي):", 12f, th.muted), offLangChips,
                    ui.text("طول الجزء اللي بيتفرّغ ويتبعت للترجمة أول ما يجهز:", 12f, th.muted), offPartChips,
                    ui.text("وضع «وشبر + جيميناي بالسياق»: كام باتش يتبعتوا لجيميناي مع بعض:", 12f, th.muted), ctxBatchChips,
                    ui.text("التفريغ على الجهاز أبطأ من السحابة (بيعتمد على معالج موبايلك) — الترجمة بتظهر جزء ورا جزء. لو سرعته أقل من 1× كبّر الموديل لأصغر (base).", 12f, th.muted)))),
            tab("visual", listOf<View>(
                                ui.section("👁 محرك الوضع البصري", false, visAutoChips,
                    ui.slider("المسافة اللي الوضع البصري بيفحصها قدّام مكان التشغيل", Cfg.int("vis_ahead", 180).coerceIn(30, 900), 30, 900, "ث") { Cfg.put("vis_ahead", it.toString()) },
                    ui.switchRow("إظهار شريط حالة الوضع البصري فوق المشغّل", Cfg.str("visbar", "0") == "1") { Cfg.put("visbar", if (it) "1" else "0") },
                    ui.text("الوضع البصري بيعتمد على Gemini فقط (مفتاح «الوضع البصري فقط» في قسم 🔑 المفاتيح). بيلقط فريم كل ثانية، بيتخطّى اللي مفيهوش تغيير أو نص، وبيبعت الباقي دفعات (30 فريم) في شبكات من 6 فريمات بالتوازي. النتايج بتتحفظ مع الفيديو، وبين الطلبات راحة تلقائية لو جيميناي رفض (429). بيتطبق من أول تشغيل جديد.\n(v249) الوضع البصري ما بيشتغلش لوحده لما تدخل فيديو — بيشتغل من البريسيتس اللي فيها بصري (شاملة / هارد سب) أو من زرار 👁، إلا لو اخترت «تلقائي مع كل فيديو» فوق.", 12f, th.muted)))),
            // ===== 🤖 الموديل والأداء =====
            tab("perf", listOf<View>(
                ui.text("القيم دي بتتظبط لوحدها لما تختار بريست من ▶ ترجمة. اللي هنا هو اللي بيمشي لما تبدأ «بالإعدادات الحالية».", 12f, th.muted),
                                ui.section("🤖 الموديل والسرعة", true, modelChips, fetchModelsBtn, model, modelDesc,
                    ui.text("🏎 اختبار السرعة: بيبعت طلب صغير على كل مفتاح بالموديل المختار وflash-lite-latest ويوريك زمن الرد والأسرع (🏆). بيستهلك طلبات قليلة.", 12f, th.muted), speedBtn, speedOut, speedUseBtn),
                ui.section("⏱ التقطيع والتنقيح", false, chunk, ahead, hlsAhead,
                    ui.switchRow("كمّل ترجمة باقي الفيديو بعد ما توصل لمكان التشغيل (بيرجع للأول لو فيه جزء ناقص)", Cfg.bool("keepgoing", true)) { Cfg.put("keepgoing", if (it) "1" else "0") },
                    *fl("prefine")))),
            // ===== 🎬 شكل الترجمة =====
            tab("look", listOf<View>(
                ui.section("🔤 الخطوط", true, *sp.fonts.toTypedArray()),
                ui.section("✨ الأنيميشن", false, *sp.anim.toTypedArray()),
                ui.section("🎬 العرض والألوان", false, *sp.look.toTypedArray()),
                ui.section("🔘 كبسولات العدّادات (صوتي · بصري · نت)", false,
                    ui.text("الكبسولات الصغيرة شمال فوق الفيديو في الشاشة الكاملة. الشفافية دي هي حالتها العادية؛ لما تدوس كبسولة بتبقى 100% كام ثانية وترجع. بتتطبّق فورًا في المشغّل.", 12f, th.muted),
                    ui.slider("الظهور (100 = معتمة، أقل = أشفّ)", Cfg.int("badge_alpha", 55).coerceIn(15, 100), 15, 100, "%") { Cfg.put("badge_alpha", it.toString()) })), true),
            // ===== 🛠 عام: الخروج · الترجمة الحية · الطابور · البطارية · الأمان · النسخة الاحتياطية · الثيم =====
            tab("more", listOf<View>(
                watchViews[2],
                                ui.section("▶ التشغيل", true,
                    ui.switchRow("كمّل الطابور تلقائيًا بعد إعادة تشغيل الجهاز", Cfg.bool("bg_autostart", true)) { Cfg.put("bg_autostart", if (it) "1" else "0") },
                    ui.text("الطابور بيتحفظ، وبيكمّل حتى لو قفلت البرنامج أو مسحته من الأخيرة. الفيديو اللي فوق في «مجلد الترجمة في الخلفية» بيترجم الأول.", 12f, th.muted),
                    ui.button("📋 افتح مجلد الترجمة في الخلفية") { settingsDlg0?.dialog?.dismiss(); qui0?.show() }),
                                ui.section("🔋 عشان الجهاز ما يقتلش الخدمة", true,
                    ui.text("أجهزة شاومي / أوبو / فيفو / سامسونج / هواوي بتقتل الخدمات الخلفية. اعمل الخطوتين دول مرة واحدة:", 12f, th.muted),
                    ui.button("1) استثناء من توفير البطارية") { requestBatteryExemption() },
                    ui.button("2) السماح بالتشغيل التلقائي / الخلفية للتطبيق") { openAutoStartSettings() },
                    ui.text("وكمان: في قايمة التطبيقات الأخيرة اعمل «قفل 🔒» للتطبيق لو جهازك فيه الخيار ده.", 12f, th.muted)))),
            tab("live", listOf<View>(watchViews[3])),
            tab("security", listOf<View>(
                            ui.section("🔒 الأمان", false, lockStatus,
                ui.text("القفل بيحمي المجلد المخفي. لفتحه: اسحب لتحت في قايمة الفيديوهات وكمّل السحب لحد 🔒 وسيب.", 12f, th.muted),
                ui.button("🔑 تعيين / تغيير النمط") { lk.change { refreshLock() } }, bioBtn,
                ui.button("🗑 إزالة القفل") { lk.remove { refreshLock() } },
                ui.text("البصمة بتتحقق من بصمات جهازك المسجّلة في إعدادات الأندرويد (التطبيق مابيخزّنش بصمتك). لو نسيت النمط: «نسيت النمط؟» بيطلب قفل شاشة الجهاز.", 12f, th.muted)),
                ui.section("💾 نسخة احتياطية (مفاتيح · إعدادات · ثيم)", false,
                    ui.text("البرنامج بيحفظ نسخة كاملة لوحده (مشفّرة) في Android/media/com.tttt.subtitler/settings_backup.json وبتفضل موجودة بعد مسح البرنامج، ولما تثبّته من جديد بترجع لوحدها. وتقدر كمان تحفظ نسخة بإيدك في ملف (Downloads مثلًا) وترجّعها منه — الملف ده مش مشفّر وفيه مفاتيحك فاحتفظ بيه في مكان آمن. قفل المجلد مابيتحفظش (بتعمله من جديد).", 12f, th.muted),
                    ui.text(Backup.autoTime(this).let { t -> if (t > 0) "آخر نسخة تلقائية: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(t)) else "لسه مفيش نسخة تلقائية" }, 12f, th.text),
                    ui.button("💾 احفظ نسخة في ملف") { backupToFile() },
                    ui.button("📂 استرجع من ملف") { backupFromFile() },
                    ui.button("♻ استرجع آخر نسخة تلقائية") { restoreAuto() }))),
            tab("appearance", listOf<View>(
                ui.section("🎨 المظهر", false, darkSwitch, themeChips,
                    ui.text("🧩 وضع الواجهة (شكل الزراير · الفولدرات · الأنيميشن · الأصوات):", 13f, th.muted).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) },
                    skinChips, skinSoundSwitch))),
            tab("youtube", ytSettingsViews),
            // ===== 🎵 الموسيقى =====
            TabDef("music", SettingsTabs.label("music"), listOf<View>(
                ui.section("📝 الكلمات", true,
                                        ui.text("📝 الكلمات: جيميناي يجيب الكلمات الكاملة بالاسم، والتطبيق نفسه يحدّد من الصوت امتى المغني بيبدأ يغني ويوزّع السطور على لحظات الغنا (بيتخطى المقدمة والفواصل). 🎤 زرار «صوت / موسيقى / الكل» في المشغّل يفصل الصوت عن الموسيقى (تقريبي، للاستريو بس).", 12f, th.muted),
                    ui.switchRow("جيب الكلمات تلقائيًا لما أفتح المشغّل", Cfg.str("music_auto_lyr", "1") != "0") { Cfg.put("music_auto_lyr", if (it) "1" else "0") }),
                ui.section("🔤 شكل الكلمات", false,
                    ui.text("الخط:", 12f, th.muted), lyrFontChips,
                    ui.text("الحجم:", 12f, th.muted), lyrSizeChips),
                ui.section("🎵 المكتبة", false,
                    ui.text("لو أغاني جديدة ماظهرتش في صفحة الموسيقى، أعد الفحص:", 12f, th.muted),
                    ui.button("🔄 إعادة فحص الأغاني") { try { musicUi?.reload(true) } catch (_: Throwable) {}; Notice.show(this, "🔄 بفحص الأغاني…", 1600L) })), false, "الكلمات (جيميناي) · الخط والحجم · الكلمات التلقائية · فحص الأغاني", group = SettingsTabs.group("music"))
        ), sp.holder) { save(); refreshChip(); if (fromPlayer) finish() }
        // رابط مباشر + المحفوظة (نافذة سفلية): حقل الرابط بيتحط هنا
        link.hint = "الصق أي لينك: فيديو مباشر / يوتيوب / صفحة فيها فيديو"; link.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val linkPlay = ui.button("▶ شغّل / اصطد الرابط", true) {
            save(); val u = link.text.toString().trim()
            if (u.isEmpty()) Notice.show(this, ("الصق الرابط الأول").toString(), 2300L) else { recentDlg?.dismiss(); openLink(u) }
        }
        val linkBrowser = ui.button("🌐 افتح المتصفح (لصيد روابط الفيديو)") { save(); recentDlg?.dismiss(); startActivity(Intent(this, BrowserActivity::class.java)) }
        recentDlg = ui.sheet(this, "🔗 رابط · 📼 المحفوظة", listOf<View>(link, linkPlay, linkBrowser,
            ui.text("📼 فيديوهات محفوظة", 13f, th.muted).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, recentBox), false)
        recentDlg!!.setOnShowListener {
            rebuildRecents()
            // لو في الكليبورد لينك: حطه في الخانة لوحده
            try {
                val t = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (link.text.isNullOrBlank()) Regex("https?://\\S+").find(t)?.let { link.setText(it.value) }
            } catch (e: Exception) { LogStore.err("Main:382", e) }
        }
        refreshChip()

        // ===== الشاشة الرئيسية: متصفح فيديوهات الجهاز (فولدرات ← فيديوهات بصور مصغّرة) زي MX Player =====
        val lib = LibraryUi(this, ui, th, { Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }).associateBy { it.id } }) { v ->
            save(); playChecked(v)
        }
        libUi = lib
        lib.onYtPick = { n -> playYt(n.id, n.title) }   // (v344) بحث الفيديوهات: اقتراح يوتيوب = فيديو
        lib.onWebSearch = { q -> webSearch(q) }
        lib.onUniSearch = { q -> uniSearchFn?.invoke(q) }
        // ===== ترجمة في الخلفية: من ⋮ على الفيديو/الفولدر =====
        fun startBg(vs: List<VideoItem>) {
            ensureKeys {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 12) } catch (e: Exception) { LogStore.err("Main:395", e) }
                }
                var n = 0
                vs.forEach { v -> if (BgJobs.enqueue(this, BgJob(v.videoId, v.title, v.uri, null, emptyMap()), "المكتبة (ترجمة بالخلفية يدوي)")) n++ }
                Notice.show(this, (if (n > 0) "🌙 بدأت الترجمة في الخلفية ($n) — التقدم في الإشعارات" else "الفيديو ده بيترجم في الخلفية بالفعل").toString(), 3600L)
                libUi?.refreshRows()
            }
        }
        lib.onBg = { v -> startBg(listOf(v)) }
        lib.onBgStop = { v -> BgJobs.stop(v.videoId); Notice.show(this, ("⏹ وقفت الترجمة في الخلفية (التقدم اتحفظ)").toString(), 2300L); lib.refreshRows() }
        lib.bgJob = { v -> BgJobs.find(v.videoId) }
        lib.onBgFolder = { f ->
            val todo = f.videos.filter { !BgJobs.isActive(it.videoId) }
            GAlert(this).setTitle("🌙 ترجمة الفولدر في الخلفية")
                .setMessage("هترجم ${todo.size} فيديو ورا بعض (واحد واحد). ده بيستهلك كوتة المفاتيح. تكمّل؟")
                .setPositiveButton("ابدأ") { _, _ -> startBg(todo) }.setNegativeButton("إلغاء", null).show()
        }
        val ops = MediaOps(this, ui, th) { VideoScan.cache = null; scanFn(); if (MusicScan.items != null) { MusicScan.invalidate(); musicUi?.reload() } }
        mediaOps = ops
        lib.onRename = { v -> ops.rename(v) }
        lib.onMove = { v -> ops.move(v, lib.allFolders()) }
        lib.onShare = { v -> ops.share(v) }
        lib.onShareMany = { vs -> ops.shareMany(vs) }
        lib.onPlayWeb = { w -> save(); playWeb(w) }
        lib.onDelete = { v -> ops.delete(v) }
        lib.onRenameFolder = { f -> ops.renameFolder(f) }
        lib.onDeleteFolder = { f -> ops.deleteFolder(f) }
        lib.onDetails = { v -> ops.details(v, Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }).firstOrNull { it.id == v.videoId }) }
        val qui = QueueUi(this, ui, th); queueUi = qui; qui0 = qui; settingsDlg0 = settingsDlg; settingsShow = { t -> if (fromPlayer) settingsDlg.show(t) else { showTabFn(7); settingsDlg.showEmbedded(t) } }; settingsBack = { settingsDlg.back() }
        dismissSettings = { try { settingsDlg.dialog.dismiss() } catch (_: Throwable) {} }
        // رجّع الطابور المحفوظ (لو التطبيق اتقفل/اتمسح) وكمّل الترجمة
        if (!fromPlayer && BgJobs.restore(this) > 0) { BgService.start(this); libUi?.refreshRows() }
        lib.onQueue = { qui.show() }
        BgJobs.onChange = { runOnUiThread { if (!isDestroyed) { libUi?.refreshRows(); if (qui.showing) qui.refresh(); TaskCenter.changed(); updateTasksFab() } } }
        lib.gate = { open -> lk.gate(open) }
        // (v87) ⚙️ في المكتبة: قايمة صغيرة منسدلة بالأقسام، دوسة على قسم تفتحه دايركت (بدل شاشة القايمة الكبيرة)
        lib.onSettings = { _ -> settingsShow?.invoke(null) }   // (v370) ⚙️ فوق = نفس تبويب ⚙️ الإعدادات تحت (كانت قايمة منسدلة تانية)
        lib.onLink = { recentDlg?.show() }
        lib.onPick = { save(); pickVideo() }
        lib.onGrant = { requestPermissions(arrayOf(VideoScan.permission()), 11) }
        fun doScan() {
            libStarted = true
            if (!VideoScan.hasPermission(this)) { lib.showNoPermission(); return }
            val cached = VideoScan.cache
            if (cached != null) lib.showVideos(cached) else if (!lib.hasData) lib.showScanning()
            Thread {
                val res = try { VideoScan.scan(this) } catch (_: Exception) { emptyList<VideoItem>() }
                runOnUiThread { if (!isDestroyed && !isFinishing) lib.showVideos(res) }
            }.apply { isDaemon = true }.start()
        }
        scanFn = { doScan() }
        // ريفريش: يمسح الفولدرات والصور المصغّرة المحفوظة ويفحص التخزين من الأول
        lib.onPull = { VideoScan.cache = null; doScan() }
        lib.onRefresh = { VideoScan.cache = null; Thumbs.clear(); lib.showScanning(); doScan() }

        // (v124) الشريط السفلي: بوابتين — الفيديوهات (المكتبة) · المتصفح (تبويب يوتيوب اتشال)
        // (v135) بوابة تالتة: «المهام» (قص · صوت · GIF · ضغط · ترجمة ثابتة)
        toolsUiM = ToolsUi(this, ui, th)
        val tasksUi = TasksUi(this, ui, th) { k -> pickTool(k) }; tasksUiRef = tasksUi
        tasksUi.onClose = { showTabFn(if (tabBeforeTasks == 5) 0 else tabBeforeTasks) }   // (v324) ✕ في صفحة المهام
        // (v198) الشريط السفلي (البوابات) وشريط النظام بياخدوا لون مشغّل الموسيقى لما يتفتح، ويرجعوا لثيم البرنامج لما يتقفل
        @Suppress("DEPRECATION")
        fun setNavTint(c: Int?) {
            navUi?.tint(c)
            fabHide = c != null; updateTasksFab()
            try {
                // (v199) شريط الحالة (فوق) وشريط البوابات وشريط النظام (تحت) بنفس اللون: لون الغلاف وقت المشغّل، وإلا لون الثيم
                window.statusBarColor = c ?: th.bg; window.navigationBarColor = c ?: th.bg
                if (th.isLight) {
                    var f = window.decorView.systemUiVisibility
                    f = if (c != null) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    if (android.os.Build.VERSION.SDK_INT >= 26) f = if (c != null) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    window.decorView.systemUiVisibility = f
                }
            } catch (_: Throwable) {}
        }
        val pane = FrameLayout(this)
        // (v310) صفحة الموسيقى بتتبني أول ما تفتحها بس (كانت بتتبني كلها وقت فتح التطبيق وده كان أكتر حاجة بتبطّأ البداية)
        fun ensureMusic(): MusicUi {
            musicUi?.let { return it }
            val music = MusicUi(this, ui, th); musicUi = music; music.root.visibility = View.GONE
            music.onNavTint = { c -> setNavTint(c) }
            music.onYtDownload = { n -> ytDownload(n, true) }
            music.onArt = { dockUi?.refresh() }
            music.lib.onYtPick = { n -> playYtAudio(n.id, n.title, n.channel, n.thumb) }   // (v344) بحث الموسيقى: اقتراح يوتيوب = صوت
            music.lib.onWebSearch = { q -> webSearch(q) }
            music.lib.onUniSearch = { q -> uniSearchFn?.invoke(q) }
            music.onYtVideo = { n, pos -> MusicEngine.pause(); playYt(n.id, n.title, false, false, pos.toLong()) }   // (v343) 🎬 كمّل كفيديو من نفس المكان (من غير ترجمة)
            music.onYtPlay = { n -> playYtAudio(n.id, n.title, n.channel, n.thumb) }   // (v340)   // (v339) ⬇ تحميل صوت يوتيوب من مشغّل الموسيقى
            music.onLoadCancel = { ytAudTok++ }                 // قفلت المشغّل وإحنا بنجيب الصوت: النتيجة المتأخرة تتجاهل
            music.onFullClosed = {                              // (v339) قفلت مشغّل صوت يوتيوب: ارجع لصفحة يوتيوب في نفس المكان
                val t = musicReturnTab
                if (t >= 0 && curTab == 1) { musicReturnTab = -1; showTabFn(t) }
            }
            music.onGrant = { requestPermissions(arrayOf(MusicScan.permission()), 12) }
            // (v194) صفحة الموسيقى = نفس كلاس صفحة الفيديوهات: نفس الإعدادات والعمليات (إعادة تسمية/نقل/مشاركة/حذف/تفاصيل) والبوابة بتاعة المخفي
            music.lib.onSettings = lib.onSettings
            music.lib.gate = { open -> lk.gate(open) }
            music.lib.onRename = { v -> ops.rename(v) }
            music.lib.onMove = { v -> ops.move(v, music.lib.allFolders()) }
            music.lib.onShare = { v -> ops.share(v) }
            music.lib.onShareMany = { vs -> ops.shareMany(vs) }
            music.lib.onDelete = { v -> ops.delete(v) }
            music.lib.onRenameFolder = { f -> ops.renameFolder(f) }
            music.lib.onDeleteFolder = { f -> ops.deleteFolder(f) }
            music.lib.onDetails = { v -> ops.details(v, null) }
            pane.addView(music.root, 1, FrameLayout.LayoutParams(-1, -1))
            return music
        }
        uniSearchFn = { q ->   // (v345) بحث شامل: فيديوهات + موسيقى + يوتيوب
            val m = ensureMusic()
            UniSearch.show(this, ui, th, q, { x -> lib.searchHits(x) }, { x -> m.lib.searchHits(x) },
                { v -> save(); playChecked(v) }, { v -> m.lib.playItem(v) },
                { n -> playYt(n.id, n.title) }, { n -> playYtAudio(n.id, n.title, n.channel, n.thumb) }, { x -> webSearch(x) })
        }
        // (v325) بوابة يوتيوب (الخامسة): YtProUi — واجهة أصلية على NewPipeExtractor بدل صفحة الويب؛ بتتبني أول ما تفتحها
        fun ensureYt(): YtProUi {
            ytUi?.let { return it }
            val yt = YtProUi(this, ui, th,
                { Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }).associateBy { it.id } },
                { id, name, tr -> playYt(id, name, tr) },
                { u -> save(); NavAnim.set(curTab, 2); startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", u)) },
                { startActivityForResult(filePicker("*/*", "اختار ملف الاشتراكات", false), 47) },
                { n -> playYtAudio(n.id, n.title, n.channel, n.thumb) },
                { n, a -> ytDownload(n, a) },
                { PubHistoryUi.show(this, ui, th) { kind, src, ref, title -> when {
                    kind == "v" && src == "youtube" -> playYt(ref, title, false)
                    kind == "v" && src == "local" -> play("", android.net.Uri.parse(ref), true)
                    kind == "v" -> openLink(ref)
                    kind == "a" && src == "youtube" -> playYtAudio(ref, title, "", "")
                    else -> {}
                } } }, onPlayAt = { id, name, tr, ms -> playYt(id, name, tr, tr, ms) })
            ytUi = yt; yt.root.visibility = View.GONE; ytOpenFn = { vid -> yt.openVideo(vid) }
            pane.addView(yt.root, 1, FrameLayout.LayoutParams(-1, -1))
            pane.addView(yt.busyView, FrameLayout.LayoutParams(-1, -1))   // (v337) لودينج جلب رابط الفيديو فوق الصفحة
            return yt
        }
        // (v189) البوابات: 0 الفيديوهات · 1 الموسيقى · 2 المتصفح · 3 الملفات (شاشات لوحدها) · (v324) 4 يوتيوب
        // (v324) 5 = صفحة المهام: مش في الشريط، بتتفتح من الزرار العايم (الشريط ساعتها مفيهوش بوابة نشطة)
        showTabFn = { i ->
            if (i == 0 || i == 1 || i == 4 || i == 5 || i == 7) {
                val prevTab = curTab
                if (i == 5 && prevTab != 5) tabBeforeTasks = prevTab
                if (i == 7 && prevTab != 7) tabBeforeSettings = prevTab
                if (prevTab == 7 && i != 7) { try { save(); refreshChip() } catch (_: Throwable) {} }
                curTab = i
                dockUi?.refresh()   // (v344) المشغّل المصغّر بيختفي
                stripUi?.hidden = (curTab == 5)   // (v345) في صفحة الموسيقى وبيظهر في الباقي
                lib.root.visibility = if (i == 0) View.VISIBLE else View.GONE
                if (i == 1) ensureMusic()
                musicUi?.root?.visibility = if (i == 1) View.VISIBLE else View.GONE
                if (i == 4) ensureYt()
                ytUi?.root?.visibility = if (i == 4) View.VISIBLE else View.GONE
                tasksUi.root.visibility = if (i == 5) View.VISIBLE else View.GONE
                settingsHostRef?.visibility = if (i == 7) View.VISIBLE else View.GONE
                tasksDlgRefresh = if (i == 5) ({ tasksUi.refresh() }) else null
                navUi?.set(i)
                if (i == 1) { musicUi?.ensureLoaded(); musicUi?.applyNavTint() } else setNavTint(null)
                if (i == 4) ytUi?.refresh() else if (prevTab == 4) ytUi?.onHide()
                if (i == 5) tasksUi.refresh()
                updateTasksFab()
                if (prevTab != i) {   // (v312) الصفحة الجديدة بتنزلق من ناحية اتجاه الانتقال (الشريط RTL: رقم أكبر = شمال)
                    val target: View? = when (i) { 0 -> lib.root; 1 -> musicUi?.root; 4 -> ytUi?.root; 7 -> settingsHostRef; else -> tasksUi.root }
                    if (target != null) Motion.tabEnter(target, if (NavAnim.pos(i) > NavAnim.pos(prevTab)) -1 else 1)
                }
            }
        }
        musicOpenFn = { showTabFn(1); musicUi?.showFull() }   // (v337)
        val nav = BottomNav(this, ui, th, 0) { i -> if (i == 2) { save(); NavAnim.set(curTab, 2); startActivity(Intent(this, BrowserActivity::class.java)) } else if (i == 3) { save(); NavAnim.set(curTab, 3); startActivity(Intent(this, FileManagerActivity::class.java)) } else if (i == 7) { musicReturnTab = -1; settingsShow?.invoke(null) } else { musicReturnTab = -1; showTabFn(i) } }   // (v310) 4 الملفات = شاشة منفصلة زي المتصفح
        navUi = nav
        if (!fromPlayer) {
            TaskCenter.listener = { if (!isDestroyed && !isFinishing) { tasksDlgRefresh?.invoke(); runOnUiThread { updateTasksFab() } } }
        }
        pane.addView(lib.root, FrameLayout.LayoutParams(-1, -1))
        tasksUi.root.visibility = View.GONE
        pane.addView(tasksUi.root, FrameLayout.LayoutParams(-1, -1))   // (v231) صفحة المهام
        if (!fromPlayer) {   // (v370) الإعدادات صفحة كاملة (تبويب ⚙️) بدل الدايلوج — الدايلوج بيفضل بس لما الإعدادات تتفتح من المشغّل
            val host = FrameLayout(this).apply { visibility = View.GONE }
            settingsDlg.embedClose = { showTabFn(if (tabBeforeSettings == 7) 0 else tabBeforeSettings) }
            settingsDlg.embedInto(host)
            pane.addView(host, FrameLayout.LayoutParams(-1, -1)); settingsHostRef = host
        }
        // (v336) الزرار العايم (TasksFab) اتشال مؤقتًا — المهام بقت زرار في الشريط السفلي
        updateTasksFab()
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        shell.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(nav.view, LinearLayout.LayoutParams(-1, -2))
        val frame = FrameLayout(this).apply { setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        frame.addView(shell, FrameLayout.LayoutParams(-1, -1))
        ytLoadUi = YtLoadingPage(this, ui, th) { cancelYtLoad() }.also { frame.addView(it.root, FrameLayout.LayoutParams(-1, -1)) }
        if (fromPlayer) {
            setContentView(FrameLayout(this))
            settingsDlg.show(intent?.getStringExtra("tab"))   // من غير قسم محدد = قايمة الإعدادات كاملة
            return
        }
        setContentView(frame)
        dockUi = MiniDock(this, frame, ui, th, { musicOpenFn?.invoke() }, { curTab == 1 }) { t -> musicUi?.artBitmap(t) }   // (v344)
        stripUi = StatusStrip(this, frame, ui, th) { showTabFn(5) }   // (v345)
        if (!fromPlayer && b == null) { frame.alpha = 0f; frame.animate().setStartDelay(0L).alpha(1f).setDuration(160L).start() }   // (v312) بدل السبلاش: ظهور ناعم سريع
        if (!fromPlayer) {   // (v310) أيقونة «الموسيقى» في اللانشر (alias) أو رجوع من المتصفح/الملفات لتبويب معيّن
            val cn = intent?.component?.className ?: ""
            // (v339) لو الأكتيفيتي اتعمل لها إعادة إنشاء (النظام قتلها وإنت في المشغّل): ارجع لنفس البوابة مش للفيديوهات
            val savedTab = b?.getInt("cur_tab", -1) ?: -1
            val t0 = intent?.getStringExtra("tab") ?: when (savedTab) { 1 -> "music"; 4 -> "youtube"; 5 -> "tasks"; else -> null }
            if (cn.endsWith(".MusicLauncher") || t0 == "music") frame.post { showTabFn(1) }
            else if (t0 == "tasks") frame.post { showTabFn(5) }
            else if (t0 == "youtube") frame.post { showTabFn(4); openYtFromIntent(intent) }
        }
        intent?.getStringExtra("reopen_tab")?.let { t -> intent?.removeExtra("reopen_tab"); frame.post { if (!isFinishing && !isDestroyed) settingsShow?.invoke(t) } }
        // لو مفيش مفتاح Gemini: القايمة بتظهر مباشرة أول البرنامج، وبعدها الفحص
        frame.post { permFlow { ensureKeysSoft { keys.setText(Cfg.allMainKeys().joinToString("\n")); keyLoadersRef.forEach { it() }; refreshChip(); doScan() } } }
        frame.post { handleExternal(intent) }   // (v345) لينك جاي من تطبيق تاني (مشاركة / فتح)
    }
    /** (v345) المرحلة 1 «كتلة واحدة»: أي لينك جاي من برّه (مشاركة أو فتح). لينك يوتيوب ← قايمة «افتحه إزاي؟»، غير كده ← زي زمان */
    private fun ytIdOf(u: String): String? = Regex("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^\\s#]*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})").find(u)?.groupValues?.get(1)
    private fun handleExternal(i: Intent?) {
        if (i == null || isFinishing || isDestroyed) return
        val raw = when (i.action) { Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT) ?: ""; Intent.ACTION_VIEW -> i.dataString ?: ""; else -> return }
        val url = Regex("https?://\\S+").find(raw)?.value ?: return
        i.action = null   // ما يتكررش لو الأكتيفيتي اتعمل لها إعادة إنشاء
        val id = ytIdOf(url)
        if (id == null) { link.setText(url); openLink(url); return }
        val items = arrayOf("🎬 شغّله فيديو", "🎬 شغّله وترجمه", "🎧 شغّله صوت بس (داتا أقل)", "🌐 افتحه في المتصفح")
        android.app.AlertDialog.Builder(this).setTitle("لينك يوتيوب — افتحه إزاي؟").setItems(items) { _, w ->
            when (w) {
                0 -> playYt(id, "", false)
                1 -> playYt(id, "", true)
                2 -> playYtAudio(id, "", "", "")
                else -> { saveCfgFn?.invoke(); NavAnim.set(curTab, 2); startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", url)) }
            }
        }.show()
    }
    class StyleParts(val holder: View, val fonts: List<View>, val anim: List<View>, val look: List<View>)
    private fun styleParts(ui: Ui, th: Theme): StyleParts {
        val prev = SubtitleView(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val demo = Sub(0.0, 5.0, "I met Ahmed in Cairo yesterday and we talked for hours about everything", "قابلت أحمد في القاهرة امبارح واتكلمنا ساعات عن كل حاجة في الدنيا",
            "female", "unknown", "none", listOf("أحمد"), listOf("القاهرة"), false, false, -1, "", false, "", false, "I met Ahmed in Cairo yesterday")
        val holder = FrameLayout(this).apply { setBackgroundColor(0xFF1B2733.toInt()); setPadding(0, ui.dp(30), 0, ui.dp(10)); addView(prev, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        fun st() = SubStyle.load { k, d -> Cfg.str(k, d) }
        fun showDemo() {
            val p = st()
            prev.show(demo)
        }
        fun put(k: String, v: String) { Cfg.put(k, v); prev.style = st(); prev.show(null); showDemo() }
        fun slider(label: String, k: String, d: Int, lo: Int, hi: Int, unit: String): LinearLayout {
            val tv = ui.text("$label: ${Cfg.int(k, d)}$unit", 13f, th.text)
            val sb = SeekBar(this).apply {
                max = hi - lo; progress = Cfg.int(k, d).coerceIn(lo, hi) - lo
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tv.text = "$label: ${p + lo}$unit"; if (u) put(k, (p + lo).toString()) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(sb) }
        }
        fun sw(t: String, k: String, d: Boolean) = ui.switchRow(t, Cfg.bool(k, d)) { put(k, if (it) "1" else "0") }
        val dualNames = listOf("ترجمة فقط", "ترجمة فوق + أصلي تحت", "إنجليزي + عربي", "نص أصلي فقط", "أصلي فوق + ترجمة تحت")
        val anims = SubStyle.entrances
        val fonts = SubStyle.fonts
        val fontsV = listOf<View>(
            ui.text("خط الترجمة (كل الـ 22 خط متضمنين)", 13f, th.muted),
            ui.chips(fonts.map { it.label }, { fonts.firstOrNull { f -> f.id == st().font }?.label ?: "" }) { n -> put("sub_font", fonts.first { it.label == n }.id) },
            ui.text("نمط الخط", 13f, th.muted),
            ui.chips(SubStyle.fontStyles.map { it.second }, { SubStyle.fontStyles.first { f -> f.first == st().fontStyle }.second }) { n -> put("sub_fontstyle", SubStyle.fontStyles.first { it.second == n }.first) },
            slider("حجم النص", "sub_scale", 100, 60, 200, "%")
        )
        val animV = listOf<View>(
            ui.text("أنيميشن ظهور الترجمة", 13f, th.muted), ui.chips(anims.map { it.label }, { anims.first { a -> a.id == st().anim }.label }) { n -> put("sub_anim", anims.first { it.label == n }.id) },
            slider("سرعة الأنيميشن", "sub_aspeed", 250, 50, 600, "ms")
        )
        val lookV = listOf<View>(
            ui.section("🎬 وضع العرض", true,
                ui.chips(dualNames, { dualNames[st().dual] }) { put("sub_dual", dualNames.indexOf(it).toString()) },
                sw("نص أبيض عادي بحجم ثابت (بدون ألوان الجنس والأسماء وكلمة التأكيد والتكبير التلقائي)", "sub_plain", false)),
            ui.section("🎨 اللون", false,
                sw("لون نص موحّد", "sub_uni_on", false),
                ui.chips(SubStyle.unifiedPalette, { st().uniColor }) { put("sub_uni_color", it) }),
            ui.section("✂️ تقسيم الجمل", false,
            sw("الجملة الطويلة على سطرين فوق بعض (عند الفاصلة/النقطة، أو بالنص لو عدّت 70% من عرض الفيديو)", "sub_two_lines", true)),
            ui.section("🌫 الخلفية", false,
                sw("إخفاء الخلفية", "sub_nobg", false),
                slider("غمقان الخلفية (0 = شفافة)", "sub_bgopa", 45, 0, 100, "%"),
                slider("نعومة حواف الخلفية (blur) — 0 = بدون", "sub_blur", 0, 0, 20, ""))
        )
        prev.style = st(); holder.post { showDemo() }
        return StyleParts(holder, fontsV, animV, lookV)
    }
    // ===== صلاحيات: بتتطلب كلها أول ما تفتح البرنامج (زي MX Player) — كل صلاحية مرة واحدة بس =====
    private class PermStep(val id: String, val title: String, val why: String, val granted: () -> Boolean, val launch: () -> Unit)
    private fun permSteps(): List<PermStep> {
        val l = ArrayList<PermStep>()
        val media = if (Build.VERSION.SDK_INT >= 33) arrayOf(VideoScan.permission(), "android.permission.POST_NOTIFICATIONS") else arrayOf(VideoScan.permission())
        l += PermStep("media", "الوصول للفيديوهات والإشعارات", "عشان تظهر فولدرات فيديوهاتك وإشعار تقدم الترجمة في الخلفية",
            { media.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED } }, { requestPermissions(media, 13) })
        if (Build.VERSION.SDK_INT >= 30) l += PermStep("allfiles", "إدارة كل الملفات", "عشان تغيّر اسم الفيديو أو تنقله أو تحذفه من غير ما النظام يسألك كل مرة",
            { android.os.Environment.isExternalStorageManager() },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")), 14) })
        l += PermStep("overlay", "العرض فوق التطبيقات الأخرى", "عشان الترجمة تظهر تحت نافذة الـ PiP لما تخرج بره البرنامج",
            { Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this) },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 14) })
        l += PermStep("writesettings", "تعديل إعدادات النظام", "عشان التحكم في السطوع من الفيديو يشتغل",
            { Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(this) },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")), 14) })
        l += PermStep("battery", "تجاهل توفير البطارية", "عشان الترجمة في الخلفية ماتتقفلش لما الشاشة تتقفل",
            { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")), 14) })
        return l
    }
    private var permQueue: ArrayList<PermStep> = ArrayList()
    fun permFlow(done: () -> Unit) {
        val todo = permSteps().filter { !it.granted() && !Cfg.bool("perm_asked_" + it.id, false) }
        if (todo.isEmpty()) { done(); return }
        permDone = done
        permQueue = ArrayList(todo)
        GAlert(this).setTitle("🔐 صلاحيات البرنامج")
            .setMessage("البرنامج هيطلب كام صلاحية مرة واحدة بس عشان يشتغل كامل زي MX Player:\n\n" + todo.joinToString("\n") { "• " + it.title + " — " + it.why } + "\n\nفي كل شاشة فعّل المفتاح ورجّع للبرنامج.")
            .setCancelable(false).setPositiveButton("تمام، يلا") { _, _ -> nextPerm() }.setNegativeButton("بعدين") { _, _ -> permQueue.clear(); permDone?.invoke(); permDone = null }.show()
    }
    private fun nextPerm() {
        while (permQueue.isNotEmpty()) {
            val st = permQueue.removeAt(0)
            if (st.granted()) continue
            Cfg.put("perm_asked_" + st.id, "1")
            try { st.launch(); return } catch (_: Exception) { continue }
        }
        val d = permDone; permDone = null; d?.invoke()
    }

    fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) { Notice.show(this, ("✅ الاستثناء شغّال بالفعل").toString(), 2300L); return }
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { LogStore.err("Main:602", e) }
        }
    }
    /** صفحة «التشغيل التلقائي» عند الشركات اللي بتقتل الخلفية؛ لو مش لاقيها بيفتح إعدادات التطبيق */
    fun openAutoStartSettings() {
        val c = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity"
        )
        for ((pkg, cls) in c) {
            try { startActivity(Intent().setClassName(pkg, cls)); return } catch (e: Exception) { LogStore.err("Main:618", e) }
        }
        Notice.show(this, ("مفيش صفحة مخصوصة لجهازك — هفتحلك إعدادات التطبيق: فعّل «التشغيل التلقائي» و«بدون قيود» للبطارية").toString(), 3600L)
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { LogStore.err("Main:621", e) }
    }

    // ===== (v137) أدوات الفيديو من تبويب «المهام»: الأداة → اختيار الفيديو → نافذة الإعدادات → تبدأ =====
    fun pickTool(kind: String) {
        pendingTool = kind
        // (v139) قايمة الفيديوهات اللي جوه التطبيق نفسه؛ مدير الملفات بقى زرار احتياطي جواها
        VideoPicker.show(this, "اختار الفيديو", { v -> onToolVideo(kind, v) }, { pickToolFromFiles() })
    }
    private fun pickToolFromFiles() {
        try { startActivityForResult(filePicker("video/*", "اختار الفيديو", true), 52) } catch (e: Exception) { Notice.show(this, "مفيش تطبيق ملفات يفتح", 2400L) }
    }
    private fun onToolVideo(kind: String, v: VideoItem) {
        Notice.show(this, "بقرا الفيديو…", 1500L)
        Thread {
            val subs = try { Store(File(filesDir, "progress"), Store.keyFor(v.videoId)).load()?.subs ?: emptyList() } catch (_: Throwable) { emptyList<Sub>() }
            var dur = v.durMs; var w = v.w; var h = v.h
            if (dur <= 0 || w <= 0 || h <= 0) {
                val pr = Tools.probe(applicationContext, v.uri)
                if (dur <= 0) dur = pr.durMs; if (w <= 0) w = pr.w; if (h <= 0) h = pr.h
            }
            val src = ToolSrc(v.uri, null, v.title, dur, w, h, 0L, subs)
            runOnUiThread { if (!isDestroyed && !isFinishing) runTool(kind, src) }
        }.apply { isDaemon = true }.start()
    }
    private fun onToolPicked(u: Uri) {
        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val kind = pendingTool
        Notice.show(this, "بقرا الفيديو…", 1500L)
        Thread {
            var name = "video"; var size = 0L
            try { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) } } } catch (_: Exception) {}
            val pr = Tools.probe(applicationContext, u.toString())
            val subs = try { Store(File(filesDir, "progress"), Store.keyFor("f:$name:$size")).load()?.subs ?: emptyList() } catch (_: Throwable) { emptyList<Sub>() }
            val src = ToolSrc(u.toString(), null, name.substringBeforeLast('.', name), pr.durMs, pr.w, pr.h, 0L, subs)
            runOnUiThread { if (!isDestroyed && !isFinishing) runTool(kind, src) }
        }.start()
    }
    private fun runTool(kind: String, src: ToolSrc) {
        val tu = toolsUiM ?: return
        when (kind) {
            "trim" -> tu.trim(src); "audio" -> tu.audio(src); "gif" -> tu.gif(src); "compress" -> tu.compress(src)
            "hardsub" -> if (src.subs.none { it.translated.isNotBlank() || it.original.isNotBlank() }) askSrt(src) else tu.hardsub(src)
        }
    }
    private fun askSrt(src: ToolSrc) {
        GAlert(this).setTitle("🎬 مفيش ترجمة محفوظة للفيديو ده")
            .setMessage("اختار ملف ترجمة SRT عشان يتحرق في الفيديو، أو ترجم الفيديو في المشغّل الأول.")
            .setPositiveButton("اختار ملف SRT") { _, _ -> pendingSrc = src; try { startActivityForResult(filePicker("*/*", "اختار ملف الترجمة (SRT)", false), 53) } catch (_: Exception) {} }
            .setNegativeButton("إلغاء", null).show()
    }
    private fun onSrtPicked(u: Uri) {
        val src = pendingSrc ?: return; pendingSrc = null
        val text = try { contentResolver.openInputStream(u)?.use { String(it.readBytes(), Charsets.UTF_8) } } catch (_: Exception) { null } ?: ""
        val subs = PlayerLogic.parseSrt(text.removePrefix("\uFEFF")).map { (a, b, t) -> Sub(a, b, t, t, "", "", "", emptyList(), emptyList(), false, false) }
        if (subs.isEmpty()) { Notice.show(this, "الملف ده مش SRT صالح", 2600L); return }
        toolsUiM?.hardsub(ToolSrc(src.uri, null, src.name, src.durMs, src.w, src.h, 0L, subs))
    }

    fun pickVideo() { startActivityForResult(filePicker("video/*", "اختار فيديو", true), 1) }
    /** landscape: الفيديو من الجهاز بيفتح لاندسكيب مباشرة (زي MX) إلا لو معروف إنه طولي */
    /** لينك فيديو مباشر → يشتغل على طول؛ أي لينك تاني (يوتيوب / صفحة) → شاشة الصيد بتفتح الصفحة وتلقط الفيديوهات */
    fun openLink(u0: String) {
        val u = u0.trim()
        if (Sniff.isDirect(u)) play(u, null) else startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", u))
    }
    fun play(u: String, uri: Uri?, landscape: Boolean = uri != null, fresh: Boolean = false, noSub: Boolean = false, ask: Boolean = false) {
        try { musicUi?.pause() } catch (_: Throwable) {}
        if (u.isBlank() && uri == null) return
        if (noSub) startPlayerNow(u, uri, landscape, fresh, true)   // فرجة من غير ترجمة: مش محتاج مفتاح
        else ensureKeys { startPlayerNow(u, uri, landscape, fresh, false, ask) }
    }
    /** (v117) دخول فوري للمشغّل: مفيش قراءة لملف الترجمة هنا خالص — المشغّل بيحمّلها هو في الخلفية واللودنج بيظهر جواه */
    fun playChecked(v: VideoItem) {
        play("", Uri.parse(v.uri), v.landscape, false, false, !BgJobs.isActive(v.videoId))
    }
    fun startPlayerNow(u: String, uri: Uri?, landscape: Boolean = uri != null, fresh: Boolean = false, noSub: Boolean = false, ask: Boolean = false) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            if (uri != null) { data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } else putExtra("url", u)
            putExtra("landscape", landscape); putExtra("fresh", fresh); putExtra("nosub", noSub); putExtra("ask", ask)
            // لو فتحنا الإعدادات من المشغّل واخترنا فيديو جديد: امسح المشغّل القديم بدل ما يفضل تحته
            if (intent?.getBooleanExtra("from_player", false) == true) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
    }
    // ===== (v215) نسخة احتياطية كاملة: حفظ في ملف / استرجاع من ملف / استرجاع آخر نسخة تلقائية =====
    private fun backupToFile() {
        try { saveCfgFn?.invoke() } catch (_: Throwable) {}   // اللي اتكتب في الخانات ولسه ما اتحفظش
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "Subtitler-backup-$stamp.json") }, 61) }
        catch (e: Throwable) { LogStore.err("Main:backupSave", e); Notice.show(this, "⚠ مش قادر أفتح اختيار الملف", 2600L) }
    }
    private fun backupFromFile() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 62) }
        catch (e: Throwable) { LogStore.err("Main:backupOpen", e); Notice.show(this, "⚠ مش قادر أفتح اختيار الملف", 2600L) }
    }
    private fun restoreAuto() {
        val j = Backup.readAuto(this)
        if (j == null) { Notice.show(this, "مفيش نسخة تلقائية على الجهاز", 2600L); return }
        val t = BackupCodec.timestamp(j)
        val d = if (t > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(t)) else "—"
        Notice.ask(this, "أسترجع النسخة التلقائية ($d)؟ هتتبدّل الإعدادات الحالية.", "استرجع", "لا", 6000L, { restoreBackup(j) }, { })
    }
    private fun restoreBackup(json: String) {
        val n = try { Backup.apply(json) } catch (e: Throwable) { Notice.show(this, ("⚠ " + (e.message ?: "الملف مش صالح")).toString(), 3400L); return }
        noSaveCfg = true
        try { Extras.load() } catch (_: Throwable) {}
        Notice.show(this, ("✅ اتسترجع $n إعداد — بعيد فتح البرنامج").toString(), 2800L)
        if (!fromPlayer) intent?.putExtra("reopen_tab", "security")
        dismissSettings?.invoke()
        if (fromPlayer) finish() else recreate()
    }
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 61) {
            if (c == RESULT_OK) d?.data?.let { u ->
                try {
                    contentResolver.openOutputStream(u, "wt")?.use { o -> o.write(Backup.toJson().toByteArray(Charsets.UTF_8)) }
                    Notice.show(this, "✅ اتحفظت النسخة — احتفظ بيها في مكان آمن (فيها مفاتيحك)", 3600L)
                } catch (e: Throwable) { LogStore.err("Main:backupWrite", e); Notice.show(this, ("⚠ فشل الحفظ: " + (e.message ?: "").take(80)).toString(), 3200L) }
            }
            return
        }
        if (r == 62) {
            if (c == RESULT_OK) d?.data?.let { u ->
                try { restoreBackup(contentResolver.openInputStream(u)?.use { i -> String(i.readBytes(), Charsets.UTF_8) } ?: "") }
                catch (e: Throwable) { LogStore.err("Main:backupRead", e); Notice.show(this, ("⚠ مش قادر أقرا الملف: " + (e.message ?: "").take(80)).toString(), 3200L) }
            }
            return
        }
        if (r == 52) { if (c == RESULT_OK) d?.data?.let { onToolPicked(it) }; return }
        if (r == 53) { if (c == RESULT_OK) d?.data?.let { onSrtPicked(it) } else pendingSrc = null; return }
        if (r == 47) { if (c == RESULT_OK) d?.data?.let { ytUi?.importUri(it) }; return }   // (v118) ملف اشتراكات يوتيوب
        if (lockUi?.onResult(r, c == RESULT_OK) == true) return
        if (mediaOps?.onResult(r, c == RESULT_OK) == true) return
        if (r == 14) { nextPerm(); return }
        if (r == 1 && c == RESULT_OK) d?.data?.let { try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { LogStore.err("Main:665", e) }; play("", it) }
    }
    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 13) { scanFn(); nextPerm(); return }
        if (rc == 12) { if (res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) musicUi?.load() else Notice.show(this, "من غير إذن الصوتيات مش هقدر أعرض الموسيقى", 3200L); return }
        if (rc != 11) return
        if (res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) { scanFn(); return }
        Notice.show(this, ("من غير إذن الفيديوهات مش هقدر أعرض فولدرات الجهاز").toString(), 3600L)
        // لو اتمنع نهائيًا: افتح إعدادات التطبيق
        if (!shouldShowRequestPermissionRationale(VideoScan.permission())) {
            try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { LogStore.err("Main:675", e) }
        }
    }
    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis(); WebMute.pauseAll(); try { ytUi?.onPause() } catch (_: Throwable) {}; if (ytLoadLaunched) { ytLoadLaunched = false; ytLoadUi?.hide() } }   // (v339) المشغّل ظهر فوقنا: شيل صفحة اللودينج
    // (v339) الأكتيفيتي بقت بتتحمّل الدوران بنفسها (من غير إعادة إنشاء) — كانت بتتعمل لها إعادة إنشاء لما المشغّل يلفّ الشاشة فنرجع للفيديوهات بدل يوتيوب
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        if (fromPlayer) return
        try { if (libStarted) libUi?.let { if (it.hasData) it.render() } } catch (_: Throwable) {}
        try { musicUi?.lib?.let { if (it.hasData) it.render() } } catch (_: Throwable) {}
    }
    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); if (!fromPlayer) o.putInt("cur_tab", curTab) }   // (v339)
    /** أندرويد 10+ مابيسمحش بقراءة الكليبورد غير والتطبيق عليه الفوكس — فالفحص بيتعمل هنا */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !fromPlayer) window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) checkClipboard { playClip(it) } }, 900)
    }
    /** لمسة على دايرة الكليبورد: فيديو مباشر يشتغل على طول، يوتيوب يتفتح عن طريق يوتيوب، وغير كده شاشة الصيد */
    /** فيديو من سجل «المصطادة»: يفتح بنفس الرابط والهيدرز ويكمّل من آخر مكان وقفت عنده. روابط يوتيوب بتنتهي — فبنفتح صفحة الفيديو */
    fun playWeb(w: WebVid) {
        if (w.kind == "YT") { if (w.id.startsWith("yt:")) playYt(w.id.removePrefix("yt:"), w.title, true, false) else if (w.ref.isNotEmpty()) openLink(w.ref) else Notice.show(this, ("رابط يوتيوب انتهت صلاحيته — افتحه من المتصفح تاني").toString(), 3600L); return }
        ensureKeys {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("url", w.url); putExtra("ref", w.ref); putExtra("ua", w.ua); putExtra("cookie", w.cookie)
                putExtra("title", w.title); putExtra("autotr", false)   // (v157) الترجمة بإيدك بس: زرار «ترجمة» في المشغّل
            })
        }
    }
    /** (v117) دخول مباشر لفيديو يوتيوب من معرّفه: بيجيب أحسن لينك فيه صوت وصورة ويفتح المشغّل (لو فشل بيفتح صفحته في المتصفح) */
    fun playYt(id: String, title: String, translate: Boolean = true, auto: Boolean = translate, startMs: Long = 0L) {
        // (v339) مفتاح الترجمة الأول (لو ناقص بيسأل) وبعدها ندخل صفحة المشغّل فورًا
        if (translate) ensureKeys { ytStart(id, title, translate, auto, startMs) } else ytStart(id, title, translate, auto, startMs)
    }
    private fun cancelYtLoad() { ytLoadTok++; ytLoadLaunched = false; ytLoadUi?.hide() }
    /** (v339) بنفتح «صفحة المشغّل» فورًا (لودينج جواها) وبنجيب رابط الفيديو في الخلفية؛ لو فشل بنقفلها ونطلّع الدايلوج ونفضل في يوتيوب */
    /** (v351) بنفتح المشغّل الكامل فورًا؛ هو اللي بيجيب رابط الفيديو ويحمّل جواه (من غير شاشة اللودينج السودا القديمة) */
    private fun ytStart(id: String, title: String, translate: Boolean, auto: Boolean, startMs: Long = 0L) {
        if (id.length != 11) { ytStartLegacy(id, title, translate, auto, startMs); return }
        ytLoadLaunched = true
        val ret = if (curTab == 4) "yt" else ""
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("ytpend", true); putExtra("ytid", id); putExtra("title", title)
            putExtra("ref", "https://www.youtube.com/")
            putExtra("nosub", !translate); putExtra("autotr", auto); putExtra("ret", ret); if (startMs > 0L) putExtra("startms", startMs)
        })
    }
    private fun ytStartLegacy(id: String, title: String, translate: Boolean, auto: Boolean, startMs: Long = 0L) {
        val tok = ++ytLoadTok; ytLoadLaunched = false
        val ret = if (curTab == 4) "yt" else ""   // الرجوع من المشغّل لصفحة يوتيوب بس لو إحنا جينا منها (مش من المكتبة)
        ytLoadUi?.show(title)
        Thread {
            val best = try { YtExtract.fetchPick(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null }
            // (v326) لو YtExtract فشل نجرّب NewPipeExtractor قبل ما نطلّع دايلوج الفشل
            val np = if (best == null) try { YtNp.init(); YtNp.pick(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null } else null
            runOnUiThread {
                if (tok != ytLoadTok) return@runOnUiThread   // اتلغى (رجوع/✕) أو طلب أحدث
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (best == null && np != null) {
                    ytLoadLaunched = true
                    startActivity(Intent(this, PlayerActivity::class.java).apply {
                        putExtra("url", np.url); putExtra("aurl", np.audio ?: ""); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", np.ua)
                        if (np.qlist.isNotEmpty()) putExtra("qlist", np.qlist)
                        putExtra("title", title.ifBlank { np.title }); putExtra("ytid", id)
                        putExtra("nosub", !translate); putExtra("autotr", auto); putExtra("ret", ret); if (startMs > 0L) putExtra("startms", startMs)
                    })
                    return@runOnUiThread
                }
                if (best == null) {
                    ytLoadUi?.hide()
                    // (v119) مفيش رمي أوتوماتيك على المتصفح: بنقولك السبب وإنت تختار (تعيد / تفتحه في المتصفح / تقفل)
                    val why = YtExtract.lastWhy.ifBlank { "السبب مش معروف (راجع اللوج)" }
                    GAlert(this).setTitle("⚠ ما قدرتش أجيب الفيديو")
                        .setMessage("يوتيوب ما رضيش يدّي لينك للفيديو ده من التطبيق.\n\n" + why)
                        .setPositiveButton("🔁 جرّب تاني") { _, _ -> ytStart(id, title, translate, auto, startMs) }
                        .setNeutralButton("🌐 افتحه في المتصفح") { _, _ -> startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", YtHistory.watchUrl(id)).putExtra("noauto", true)) }
                        .setNegativeButton("إغلاق", null).show()
                    return@runOnUiThread
                }
                val ttl = title.ifBlank { best.title }
                ytLoadLaunched = true
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("url", best.url); putExtra("aurl", best.audio ?: ""); putExtra("qlist", best.optsJson()); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", best.ua)
                    putExtra("title", ttl); putExtra("ytid", id)
                    putExtra("nosub", !translate); putExtra("autotr", auto); putExtra("ret", ret); if (startMs > 0L) putExtra("startms", startMs)
                })
            }
        }.apply { isDaemon = true }.start()
    }
    /** (v337) 🎧 صوت بس: بنجيب رابط الصوت بس (من غير داتا فيديو) ونشغّله في مشغّل الموسيقى — الاسم والمغني من عنوان الفيديو والكلمات/الغلاف بنفس طريقة الأغاني */
    fun playYtAudio(id: String, title: String, channel: String, thumb: String, quiet: Boolean = false, startMs: Int = 0,
                    preUrl: String? = null, preUa: String = "", preDur: Long = 0L) {
        MusicEngine.ytStep = { d -> ytAudioStep(d) }   // (v341) التالي/إعادة المحاولة لصوت يوتيوب
        YtNext.markPlayed(id)
        try { PubHistory.add("a", "youtube", title, id) } catch (_: Throwable) {}   // (v349) السجل العام
        // (v339) بنفتح مشغّل الموسيقى الكامل فورًا (لودينج مكان الألبوم) وبنفتكر إحنا جينا منين عشان نرجع لنفس المكان لما نقفله
        // (v341) quiet = تالي/إعادة محاولة: الأغنية الحالية تكمّل وإحنا ماشيين، من غير ما نغيّر الشاشة ولا نفتح لودينج
        val tok = ++ytAudTok
        // (v351) رابط الصوت جاهز من مشغّل الفيديو: نشغّله فورًا (نفس المكان) من غير لودينج ولا جلب جديد
        if (preUrl != null && !quiet) {
            try {
                if (curTab != 1) musicReturnTab = curTab
                showTabFn(1)
                val tr = YtAudio.track(id, title, channel, thumb, YtPick(preUrl, null, preUa.ifBlank { "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36" }, title, 0, "", preDur))
                MusicEngine.play(this, listOf(tr), 0, false, startMs)
                musicOpenFn?.invoke()
                return
            } catch (e: Throwable) { LogStore.err("Main:preAudio", e) }   // فشل؟ نكمّل بالطريقة العادية تحت
        }
        if (!quiet) {
            if (curTab != 1) musicReturnTab = curTab
            showTabFn(1)
            musicUi?.showYtLoading(title, channel)
        }
        Thread {
            // (v354) أسرع: نفس الاستخراج السريع بتاع الفيديو (متوازي + كاش) وبعدها NewPipe كاحتياطي — والكاش بيخلّي التحويل لفيديو فوري
            val a = try { YtFast.audio(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null }   // (v362) YtExtract + NewPipe بالتوازي + كاش
            runOnUiThread {
                if (tok != ytAudTok) return@runOnUiThread   // قفلت المشغّل أو دوست على حاجة تانية
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (a == null) {
                    if (!quiet) { musicUi?.endYtLoading(); musicUi?.closeFull() }   // ← بيرجّعك لصفحة يوتيوب (onFullClosed)
                    else MusicEngine.pause()
                    Notice.show(this, ("ما قدرتش أجيب صوت الفيديو ده — جرّب تاني أو شغّله فيديو").toString(), 3800L); return@runOnUiThread
                }
                val tr = YtAudio.track(id, title, channel, thumb, a)
                MusicEngine.play(this, listOf(tr), 0, false, startMs)
                if (!quiet) {
                    musicOpenFn?.invoke()
                    // (v345) اللودينج يفضل ظاهر لحد ما الصوت يشتغل فعلًا (مش لحد ما الرابط يتجاب) — حد أقصى 30 ثانية
                    val hh = android.os.Handler(android.os.Looper.getMainLooper()); val t0 = System.currentTimeMillis()
                    val poll = object : Runnable { override fun run() {
                        if (tok != ytAudTok || isFinishing || isDestroyed) return
                        if (MusicEngine.isPlaying || System.currentTimeMillis() - t0 > 30000L) { musicUi?.endYtLoading(); return }
                        hh.postDelayed(this, 200L)
                    } }
                    hh.postDelayed(poll, 200L)
                }
            }
        }.apply { isDaemon = true }.start()
    }
    @Volatile private var ytStepBusy = false
    /** (v341) d=1: التالي (قايمة التشغيل أو المقترحات) · d=0: نفس الفيديو برابط جديد. الأغنية الحالية بتفضل شغّالة لحد ما الجديدة تجهز */
    private fun ytAudioStep(d: Int): Boolean {
        val cur = YtAudio.itemOf(MusicEngine.current) ?: return false
        if (d == 0) { playYtAudio(cur.id, cur.title, cur.channel, cur.thumb, true); return true }
        if (d < 0) return false
        if (ytStepBusy) return true
        ytStepBusy = true
        Notice.show(this, ("⏳ بجيب الفيديو التالي…").toString(), 1500L)
        Thread {
            val n = try { YtNext.nextAfter(cur.id) } catch (_: Throwable) { null }
            runOnUiThread {
                ytStepBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (n == null) { MusicEngine.pause(); Notice.show(this, ("ما لقيتش فيديو تالي — دوس 📃 التالي واختار").toString(), 3200L); return@runOnUiThread }
                if (YtAudio.itemOf(MusicEngine.current)?.id != cur.id) return@runOnUiThread   // غيّرت الأغنية في الأثناء
                playYtAudio(n.id, n.title, n.channel, n.thumb, true)
            }
        }.apply { isDaemon = true }.start()
        return true
    }
    /** (v337) ⬇ تحميل فيديو يوتيوب (صوت بس أو فيديو بالجودة الافتراضية) — مهمة في تبويب المهام، والملف في التنزيلات/Subtitler، ومعلوماته بتتسجّل (إعدادات يوتيوب ← التحميلات المسجّلة) */
    fun ytDownload(n: NpItem, audioOnly: Boolean) {
        val app = applicationContext
        val nm = n.title.ifBlank { n.id }
        TaskCenter.add(app, "download", (if (audioOnly) "🎧 " else "⬇ ") + nm) { t ->
            t.msg = "بجيب الرابط…"; TaskCenter.changed()
            YtNp.init()
            val p = (if (audioOnly) YtNp.pickAudio(n.id) else YtNp.pick(n.id, Cfg.int("yt_maxh", 0))) ?: throw java.io.IOException("ما قدرتش أجيب رابط التحميل")
            val hs = mapOf("User-Agent" to p.ua, "Referer" to "https://www.youtube.com/")
            val job = if (audioOnly) Downloader.Job(nm, p.url, null, false, 1, hs, true)
                      else Downloader.Job(nm, p.url, p.audio, p.url.contains(".m3u8", true), p.h, hs)
            job.ctl.cancelled = false; job.ctl.paused = false
            job.ctl.ext = { t.cancelled }
            t.onCancel = { job.ctl.cancelled = true }
            t.onPause = { pz -> job.ctl.paused = pz }
            val res = Downloader.run(app, job) { what, pct -> t.pct = pct.coerceIn(0, 99); t.msg = what; TaskCenter.changed() }
            if (!res.first) throw java.io.IOException(res.second)
            t.msg = ""; t.outPath = res.second
            try {   // معلومات التحميل: العنوان/القناة/الصورة/المدة + مكان الملف (في خانة الوقت بتظهر)
                YtStore.addFront(YtStore.DL, NpItem(n.id, n.title, n.channel, n.channelUrl, n.thumb, n.dur, -1L, "⬇ " + (if (audioOnly) "صوت" else "فيديو") + " · " + res.second, 0, res.second), 300)
            } catch (_: Throwable) {}
        }
        Notice.show(this, ("⬇ اتضاف للتحميل — تابعه من تبويب «المهام»").toString(), 3000L)
    }
    fun playClip(c: ClipVideo) {
        fun go(u: String, ref: String, ua: String) {
            if (ref.isEmpty() && ua.isEmpty()) { play(u, null); return }
            ensureKeys { startActivity(Intent(this, PlayerActivity::class.java).apply { putExtra("url", u); putExtra("ref", ref); putExtra("ua", ua) }) }
        }
        if (c.playUrl.isNotEmpty()) { go(c.playUrl, c.ref, c.ua); return }
        val id = YtExtract.videoId(c.url)
        if (id == null) { openLink(c.url); return }   // مش يوتيوب ومفيش رابط مباشر: شاشة الصيد في المتصفح
        playYt(id, c.title.takeIf { it.isNotBlank() && it != c.url } ?: "", true, false)   // (v358) لينك يوتيوب: بيتفتح عن طريق يوتيوب
    }
    /** (v350) عدّاد الفيديوهات الجديدة على أيقونة يوتيوب */
    /** (v355) فتح قسم من إعدادات البرنامج من أي شاشة (مثلًا ⚙ يوتيوب) */
    var settingsShow: ((String?) -> Unit)? = null
    var settingsBack: (() -> Unit)? = null   // (v370) رجوع جوه صفحة الإعدادات (قسم ← القايمة ← الصفحة السابقة)
    fun openSettingsTab(t: String) { try { settingsShow?.invoke(t) } catch (_: Throwable) {} }
    fun updateYtBadge() { navUi?.badge(4, YtNotify.badgeCount()) }
    private var ytBgAt = 0L
    /** (v404) مزامنة يوتيوب في الخلفية أول ما التطبيق يفتح (ومرة كل 20 دقيقة لو رجعت له): بعد ما الشاشة تظهر بـ3.5 ثانية وبأولوية خلفية
     *  عشان ما تبطّأش فتح البرنامج. بتجيب اشتراكات حسابك (لو مسجّل ومرّ وقت من آخر مزامنة) وبعدين بتعدّ الفيديوهات الجديدة
     *  من آخر مرة فتحت فيها الاشتراكات وتظهر العدّاد (1 2 3…) على زرار يوتيوب تحت. المشغّل بيفتح عادي ومش بيستنى حاجة. */
    private fun ytBackgroundSync() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (ytBgAt != 0L && now - ytBgAt < 20L * 60_000L) return
        ytBgAt = now
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            Thread {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
                try {
                    Cfg.ensure(this); YtRss.attach(this); YtAccount.init(this)
                    if (YtAccount.loggedIn() && YtAccount.syncDue(30L * 60_000L)) {
                        try { val n = YtAccount.syncSubs(); LogStore.add("🔄 مزامنة يوتيوب عند فتح البرنامج: +$n قناة") }
                        catch (e: Throwable) { LogStore.add("⚠ مزامنة يوتيوب عند الفتح فشلت: " + (e.message ?: e.javaClass.simpleName).take(100)) }
                    }
                    YtNotify.refreshBadge(this, true) { runOnUiThread { updateYtBadge() } }
                    YtNotify.schedule(this)
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
        }, 3500L)
    }
    override fun onResume() {
        super.onResume()
        if (!fromPlayer) { updateYtBadge(); ytBackgroundSync() }
        // الرجوع من المشغّل أو من إعدادات الإذن: حدّث العرض (تقدم الترجمة) أو أعد الفحص
        if (!fromPlayer) CrashLog.showIfAny(this)
        if (!fromPlayer) ModelWatch.maybeCheck(this)   // (v141) فحص يومي لموديل flash-lite أحدث
        // المخفي بيتقفل تاني لو التطبيق قعد في الخلفية أكتر من دقيقة
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) { libUi?.relock(); musicUi?.lib?.relock() }
        stoppedAt = 0L
        if (!fromPlayer && libStarted) libUi?.let { if (it.hasData) it.render() else scanFn() }
        if (!fromPlayer && curTab == 4) ytUi?.refresh()
    }
    override fun onDestroy() { try { dockUi?.detach() } catch (_: Throwable) {}; try { ytUi?.destroy() } catch (_: Throwable) {}; try { stripUi?.detach() } catch (_: Throwable) {}; if (!fromPlayer) TaskCenter.listener = null; try { tasksFabUi?.detach() } catch (_: Throwable) {}; try { musicUi?.destroy() } catch (_: Throwable) {}; super.onDestroy() }
    /** (v327) إشعار فيديو جديد: افتح تفاصيله في بوابة يوتيوب (الدالة بتتركّب أول ما بوابة يوتيوب تتبني؛ showTabFn(4) بتبنيها) */
    private fun openYtFromIntent(i: Intent?) {
        val id = i?.getStringExtra("yt_open") ?: return
        i.removeExtra("yt_open")
        ytOpenFn?.invoke(id)
    }
    /** (v117) الرجوع من المتصفح بشريط البوابات: بيفتح البوابة اللي اخترتها */
    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        i?.getStringExtra("ytaudio")?.let { id ->   // (v343) 🎧 من مشغّل الفيديو: كمّل كصوت من نفس المكان
            try {
                playYtAudio(id, i?.getStringExtra("ytaudtitle") ?: "", "", "", false, (i?.getLongExtra("ytaudpos", 0L) ?: 0L).toInt(),
                    i?.getStringExtra("ytaudurl"), i?.getStringExtra("ytaudua") ?: "", i?.getLongExtra("ytauddur", 0L) ?: 0L)
            } catch (e: Throwable) { LogStore.err("Main:ytaudio", e); Notice.show(this, ("ما قدرتش أحوّل لصوت — جرّب تاني").toString(), 3000L) }
        }
        handleExternal(i)   // (v345)
        if (i?.getBooleanExtra("fullmusic", false) == true) musicOpenFn?.invoke()   // (v344) ⤢ من المشغّل المصغّر في المتصفح/الملفات
        when (i?.getStringExtra("tab")) { "videos" -> showTabFn(0); "music" -> showTabFn(1); "tasks" -> showTabFn(5); "youtube" -> { showTabFn(4); openYtFromIntent(i) } }
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!fromPlayer && ytLoadUi?.visible == true) { cancelYtLoad(); return }   // (v339) لسه بنجيب الفيديو: رجوع = إلغاء ونفضل في يوتيوب
        if (!fromPlayer && curTab == 4 && ytUi?.back() == true) return   // (v118) رجوع جوه يوتيوب الأول
        if (!fromPlayer && curTab == 7) { settingsBack?.invoke(); return }   // (v370)
        if (!fromPlayer && curTab == 5) { showTabFn(if (tabBeforeTasks == 5) 0 else tabBeforeTasks); return }   // (v324) صفحة المهام ← للصفحة اللي جيت منها
        if (!fromPlayer && curTab == 1 && musicUi?.onBack() == true) return   // (v193) يقفل مشغّل الأسطوانة/الفولدر الأول
        if (!fromPlayer && curTab != 0) { showTabFn(0); return }
        if (!fromPlayer && libUi?.back() == true) return
        super.onBackPressed()
    }
    override fun startActivity(i: Intent?) { super.startActivity(i); try { if (!NavAnim.take(this)) overridePendingTransition(R.anim.act_enter, R.anim.act_stay) } catch (e: Exception) { LogStore.err("Main:720", e) } }
    override fun finish() { super.finish(); try { if (fromPlayer) overridePendingTransition(0, 0) else overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Main:721", e) } }
}
