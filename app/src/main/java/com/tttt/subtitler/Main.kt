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
    private var ytTvMode = false   // (v532) جاي من زرار «يوتيوب» في الريموت: كل فيديو بيسأل «تشغّله على التلفزيون؟» والرجوع بيرجّع للريموت
    private var ytTvSent = false
    private var fromTv = false   // (v467) الزيارة دي جاية من واجهة التلفزيون: قفل الإعدادات/الرجوع من الجذر يرجّع لها
    private val baseLangs = listOf("ar" to "العربية", "en" to "English", "fr" to "Français", "es" to "Español", "de" to "Deutsch", "tr" to "Türkçe", "hi" to "हिन्दी", "ko" to "한국어", "ja" to "日本語", "zh" to "中文")   // (v467) اللغة الأساسية
    // (v469) D7 وضع النص الكبير: fontScale على مستوى الـ Context فبيكبّر كل نصوص الواجهة (المقاسة بـ SP) بنسبة ثابتة ٪٢٥
    override fun attachBaseContext(nb0: android.content.Context) {
        val newBase = TvScale.wrap(nb0)   // (v507)
        val big = try { val sp = newBase.getSharedPreferences("p", 0); try { sp.getBoolean("big_text", false) } catch (_: Throwable) { sp.getString("big_text", "") == "1" } } catch (_: Throwable) { false }
        if (!big) { super.attachBaseContext(newBase); return }
        val cfg = android.content.res.Configuration(newBase.resources.configuration); cfg.fontScale = 1.25f
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    private fun baseLangName(): String = baseLangs.firstOrNull { it.first == Cfg.str("base_lang", "ar") }?.second ?: "العربية"
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
    /** (v547 م3) مصدر مكتبة الفيديوهات الحالي (الموبايل افتراضيًا) وتبديله (true = التلفزيون) */
    private var libBackend: LibraryBackend = LocalLibrary
    private var libSourceFn: ((Boolean) -> Unit)? = null
    private var libStarted = false
    private var curTab = 0                       // (v117) 0 = الفيديوهات · 1 = يوتيوب
    private var tvShellOn = false           // (v516) يوتيوب جوه قشرة اللانشر (نفس الشريط والخلفية، من غير انتقال تطبيق جديد)
    private var tvBar: TvShellBar? = null
    private var navUi: BottomNav? = null
    private var warmView: View? = null   // (v469) D2 طبقة الفلتر الدافئ
    /** (v469) D2 فلتر راحة العين الدافئ: طبقة عنبرية شفافة فوق الشاشة الرئيسية (٠ = مقفول، ١٠٠ = أقصى دفء ≈ ٪٣٣ عتمة) */
    private fun refreshWarm() { val v = try { Cfg.int("warm", 0) } catch (_: Throwable) { 0 }.coerceIn(0, 100); warmView?.let { it.setBackgroundColor(android.graphics.Color.argb((v * 0.85f).toInt(), 255, 138, 42)); it.visibility = if (v > 0) View.VISIBLE else View.GONE } }
    private var musicUi: MusicUi? = null            // (v189) صفحة الموسيقى
    private var tasksUiRef: TasksUi? = null
    private var showTabFn: (Int) -> Unit = {}
    private var mediaSw: MediaSwitch? = null   // (v654) زرارين فيديوهات/موسيقى فوق «ميديا»
    private var musicOpenFn: (() -> Unit)? = null   // (v337) يفتح صفحة الموسيقى ومشغّلها الكامل (لتشغيل صوت يوتيوب)
    private var tasksDlgRefresh: (() -> Unit)? = null
    /** (v324) صفحة المهام طلعت من الشريط السفلي (مكانها بقى يوتيوب): بتتفتح من الزرار العايم (TasksFab) أو ⚙️ أو الويدجت، وبتتخبّى بالرجوع. رقمها الداخلي 5 */
    private fun showTasksDialog() { showTabFn(5) }
    private var tabBeforeTasks = 0
    private var tabBeforeSettings = 0   // (v370) تبويب ⚙️ الإعدادات (رقمه 7): الرجوع منه للصفحة اللي جيت منها
    private var settingsHostRef: FrameLayout? = null
    // (v339) فتح فيديو/صوت يوتيوب فورًا بلودينج جوه المشغّل + الرجوع لنفس المكان
    private var ytLoadTok = 0          // أي طلب فيديو جديد/إلغاء بيزوّده؛ النتيجة المتأخرة بتتجاهل
    private var ytLoadLaunched = false // المشغّل الحقيقي اتفتح (صفحة اللودينج بتتشال لما نرجع)
    private var ytAudTok = 0
    private var musicReturnTab = -1    // الصفحة اللي جينا منها لمشغّل الموسيقى (يوتيوب) عشان نرجعلها لما نقفله
    // (v324) زرار «المهام» العايم: دايرة حمراء بعدد المهام، بتتسحب لأي مكان (TasksFab.kt)
    private var tasksFabUi: TasksFab? = null
    private var pageGearUi: PageGear? = null   // (v820) زرار ⚙️ عايم لإعدادات الصفحة (فيديوهات · موسيقى · مهام)
    private var uniSearchFn: ((String) -> Unit)? = null   // (v345) بحث شامل
    private var stripUi: StatusStrip? = null   // (v345) شريط الحالة الموحّد
    private var dockUi: MiniDock? = null   // (v344) المشغّل المصغّر العايم
    /** (v344) «ابحث في المتصفح»: بيفتح المتصفح ببحث جوجل عن النص */
    private fun webSearch(q: String) { saveCfgFn?.invoke(); NavAnim.set(curTab, 2); startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"))) }
    private var fabHide = false
    /** (v336) الزرار العايم اتشال؛ الدالة دلوقتي بتحدّث عدّاد زرار «المهام» في الشريط السفلي */
    private fun updateTasksFab() { try { val a = TaskCenter.active() + BgJobs.jobs.count { it.active }; val t = TaskCenter.total() + BgJobs.jobs.size; val n = if (a > 0) a else t; navUi?.label(5, if (n > 0) "المهام ($n)" else "المهام"); tasksFabUi?.let { it.hidden = fabHide || curTab == 5 } } catch (_: Throwable) {} }
    private var toolsUiM: ToolsUi? = null
    private var pendingTool = ""
    private var pendingSrc: ToolSrc? = null
    override fun onCreate(b: Bundle?) {
        TabNav.pendingMain = null   // (v973)
        val t0Boot = android.os.SystemClock.uptimeMillis()   // (v834) قياس زمن onCreate + بداية عدّ السبلاش
        NetUse.init(); NetGate.apply(this)
        // (v834) NetLedger/NetLog بيقروا ملفات JSON (لحد 45 يوم) ويركّبوا ProxySelector: ما ينفعش يعطّلوا أول رسمة — خيط خلفي
        val actBoot = this
        Thread { try { NetLedger.start(actBoot); NetLog.start(actBoot) } catch (_: Throwable) {} }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        fromPlayer = intent?.getBooleanExtra("from_player", false) == true
        fromTv = intent?.getBooleanExtra("from_tv", false) == true
        ytTvMode = intent?.getBooleanExtra("tv_target", false) == true
        if (fromPlayer) setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(b)
        // (v985) استعادة PiP من أيقونة اللانشر — منقولة من SplashActivity بتاع MeeTube
        // (هي اللي كانت بتعملها قبل ما يتشال فلتر اللانشر منها): ضغطة الأيقونة والمشغّل
        // مثبّت في PiP لازم تكبّر المشغّل بدل ما تفتح الرئيسية. قبل أي حاجة تانية.
        if (intent?.action == Intent.ACTION_MAIN && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true) {
            try {
                if (com.newtube.mobile.MobileMainApplication.booted()?.restorePictureInPictureFromLauncher(this) == true) {
                    finish()
                    return
                }
            } catch (_: Throwable) {}
        }
        if (fromPlayer) requestedOrientation = if (intent?.getBooleanExtra("land", false) == true) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT   // الإعدادات من المشغّل: بنفس اتجاه المشغّل
        UiWatchdog.start()
        Cfg.init(this); CrashLog.install(this);
        if (!fromPlayer) DeviceUi.applyOrientation(this)   // (v494) وضع التلفزيون/التابلت على موبايل = لاندسكيب تلقائي
        if (DeviceUi.isTv(this) && !fromPlayer) fromTv = true   // (v482) وضع التلفزيون مختار: أي دخول لـMain (من أي شاشة/إشعار/رجوع من المشغّل) رجوعه للتلفزيون مش للموبايل
        // (v461) وضع التلفزيون: شاشة TV بدال واجهة الموبايل (إلا لو جاي بطلب صريح: تبويب/رابط/فتح من شاشة TV نفسها)
        if (DeviceUi.isTv(this) && intent?.getBooleanExtra("force_phone", false) != true && intent?.getStringExtra("tab") == null &&
            intent?.action != Intent.ACTION_VIEW && intent?.action != Intent.ACTION_SEND && intent?.getStringExtra("yt_open") == null &&
            intent?.getStringExtra(Links.EX_URL) == null) {   // (v531) لينك جاي من ريموت الموبايل/أي شاشة: ما يتحوّلش للانشر (كان بيتبلع لو Main مش شغّال)
            startActivity(Intent(this, TvActivity::class.java)); finish(); return
        }
        val th = Themes.current()
        val ui = Ui(this, th)
        applyBars(th)
        TvFocus.attach(this, th.primary)   // (v477) حلقة تركيز الريموت (تلفزيون بس)
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
                row.addView(et, LinearLayout.LayoutParams(0, ui.dp(48), 1f)); row.addView(del)
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
            }, LinearLayout.LayoutParams(-1, ui.dp(48)).apply { topMargin = ui.dp(6) })
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
        val gloss = ui.input("مسرد مصطلحات ثابت (كل سطر: الكلمة = ترجمتها)", Cfg.str("gloss"), 3)
        val flags = linkedMapOf("hitiming" to false, "soundtags" to true, "prefine" to true)   // (v458) اتشال من الخريطة: autochars/autopron/autotpl/gapfill (المحرك بيفرضهم شغالين دايمًا من v229 فمفاتيحهم كانت ميتة) وautosrt (مفيش أي كود بيقراه)
        val flagText = mapOf(
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
        val styles = listOf("تلقائي", "حرفي", "شعبي", "جرئ")
        var lang = Cfg.str("lang", "فصحى"); var style = Cfg.str("style", "تلقائي"); var themeId = th.id
        link = ui.input("رابط فيديو مباشر (mp4 / m3u8)", "")
        fun save() {
            if (noSaveCfg) return
            val e = Cfg.p.edit().putString("keys", keys.text.toString()).putString("backup", backup.text.toString())
                .putString("model", model.text.toString().trim()).putInt("chunk", chunkSec).putString("extra", extra.text.toString())
                .putString("gloss", gloss.text.toString())   // (v458) كان بيكتب "" دايمًا فالمسرد بيتمسح مع كل حفظ رغم إن المحرك بيقراه
                .putString("lang", lang).putString("style", style).putString("theme", themeId)
                .putString("viskeys", vkeys.text.toString())
            ahead.text.toString().trim().toIntOrNull()?.let { e.putInt("ahead", it) }
            atrack.text.toString().trim().toIntOrNull()?.let { e.putInt("atrack", it) }
            hlsAhead.text.toString().trim().toIntOrNull()?.let { e.putInt("hls_ahead", it.coerceIn(1, 8)) }
            flags.keys.forEachIndexed { i, k -> e.putBoolean(k, (flagViews[i] as SwitchRow).isChecked) }
            e.apply()
        }
        saveCfgFn = { save() }
        // (v891) الثيمات والسكنات (مربعات الثيم · سويتش داكن/فاتح · سكن الواجهة · استوديو السمات) اتشالت من الموبايل — الواجهة «يوتيوب زجاجي» ثابتة.
        // (v355) إعدادات يوتيوب جوه إعدادات البرنامج (بدل الدايلوج المنفصل)
        val ytQ = listOf("أعلى جودة", "1080p", "720p", "480p", "360p", "240p")
        val ytQv = intArrayOf(0, 1080, 720, 480, 360, 240)
        var settingsDlg0: TabbedDialog? = null; var qui0: QueueUi? = null
        val ytSettingsViews: () -> List<View> = { listOf(
            ui.section("🎨 المظهر (من MeeTube)", true,   // (v907) مصدر واحد: إعداد الثيم بتاع MeeTube نفسه (ThemeMode) — Joyboy وMeeTube بيتبعوه مع بعض
    ui.chips(listOf("⚙ النظام", "☀️ فاتح", "🌙 داكن"), { when (com.newtube.mobile.ui.common.ThemeMode.peek(this)) { com.newtube.mobile.ui.common.ThemeMode.LIGHT -> "☀️ فاتح"; com.newtube.mobile.ui.common.ThemeMode.DARK -> "🌙 داكن"; else -> "⚙ النظام" } }) { n ->
        com.newtube.mobile.ui.common.ThemeMode.set(this, when (n) { "☀️ فاتح" -> com.newtube.mobile.ui.common.ThemeMode.LIGHT; "🌙 داكن" -> com.newtube.mobile.ui.common.ThemeMode.DARK; else -> com.newtube.mobile.ui.common.ThemeMode.SYSTEM })
        recreate()
    },
    ui.noHint()),
            ui.section("▶ MeeTube (يوتيوب البرنامج)", true,
                ui.button("▶ افتح يوتيوب") { settingsDlg0?.dialog?.dismiss(); MeeTubeLink.open(this) },
                ui.button("⚙ إعدادات MeeTube الكاملة (المشغّل · الشكل · الحساب · التحميل)") { settingsDlg0?.dialog?.dismiss(); MeeTubeLink.openSettings(this) },
                ui.noHint()),
            ui.section("🎞 الجودة الافتراضية", true,
                ui.chips(ytQ, { ytQ[ytQv.indexOf(Cfg.int("yt_maxh", 0)).coerceAtLeast(0)] }) { n -> Cfg.put("yt_maxh", ytQv[ytQ.indexOf(n).coerceAtLeast(0)].toString()) },
                ui.switchRow("🔍 حدّة الفيديو (عرض SurfaceView زي المشغّل القديم)", Cfg.bool("sharp_surface", true)) { Cfg.put("sharp_surface", if (it) "1" else "0") },
                ui.noHint()),
            ui.section("⚡ السلاسة والفريمات", true,   // (v988)
                ui.chips(MtSmooth.hzChoices(this), { MtSmooth.hzLabel(this) }) { n -> MtSmooth.setHz(this, n) },
                ui.switchRow("مطابقة معدل الشاشة مع فريمات الفيديو (24/30 ← ×5/×4 من غير رعشة)", MtSmooth.matchOn()) { Cfg.put(MtSmooth.K_MATCH, if (it) "1" else "0") },
                ui.switchRow("تنبيه لو الفيديو التقيل اتفك بالمعالج (سوفتوير)", MtSmooth.hwWarnOn()) { Cfg.put(MtSmooth.K_HW_WARN, if (it) "1" else "0") },
                ui.noHint())) }
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
                info.addView(ui.noHint())
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
                        } else if (r.url.isNotEmpty() || Recents.ytIdOf(r) != null) { recentDlg?.dismiss(); reopenRecentLink(r) }   // (v568) يوتيوب يتفتح في يوتيوب بالمعرّف، وأي لينك تاني كفيديو (مش متصفح)
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
        // (v529) مفاتيح وصلت من الموبايل والإعدادات مفتوحة: حدّث الخانات من Cfg (وإلا الحفظ التلقائي بيكتب القديم فوق الجديد)
        KeysSync.reload = {
            runOnUiThread {
                try {
                    val was = noSaveCfg; noSaveCfg = true
                    keys.setText(Cfg.allMainKeys().joinToString("\n")); backup.setText(""); extra.setText(""); vkeys.setText(Cfg.str("viskeys"))
                    val m = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
                    model.setText(m); modelSel = m; modelDesc.text = descOf(m)
                    keyLoadersRef.forEach { it() }
                    refreshChip()
                    noSaveCfg = was
                } catch (e: Throwable) { LogStore.err("Main:keysReload", e) }
            }
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
        // (v420) عدد خانات التفريغ المتوازي (باتشات Whisper في نفس الوقت)
        val parOpts = listOf("خانة 1", "خانتين")
        val parNote = ui.noHint()
        val parChips = ui.chips(parOpts, { parOpts[(Offline.par()).coerceIn(1, 2) - 1] }) { l -> Cfg.put("stt_par", (parOpts.indexOf(l) + 1).toString()); parNote.text = if (Offline.model().id == "medium" && parOpts.indexOf(l) > 0) "⚠ موديل medium بيشتغل بخانة واحدة بغض النظر عن الاختيار (الرام)." else "الخانات أكتر = تفريغ أسرع لكن رام وحرارة أكتر. الافتراضي خانتين." }
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
        // (v569) SenseVoice (sherpa-onnx) اتشال من التطبيق — التفريغ على الجهاز Whisper بس
        val savedPlanTxt = ui.text("", 12f, th.muted)
        fun planRefresh() { savedPlanTxt.text = TrWizard.savedPlan()?.let { "المحفوظ لزرار «▶ ترجمة»: " + TrWizard.describe(it).replace("\n", " · ") } ?: "مفيش إعداد محفوظ لزرار «▶ ترجمة» — بيسألك كل مرة." }
        val planBtn = ui.button("⚙ طريقة الترجمة (نفس زرار ⚙ في المشغّل)") {
            Notice.show(this, "اختار النظام (جيميناي / وشبر + ترجمة أوفلاين) من زرار «▶ ترجم» جوه المشغّل", 3200L)   // (v743) المعالج القديم اتشال
        }
        planRefresh()
        val offLangChips = ui.chips(Offline.langLabels, { Offline.langLabels[Offline.langIds.indexOf(Offline.lang()).coerceAtLeast(0)] }) { val lid = Offline.langIds[Offline.langLabels.indexOf(it).coerceAtLeast(0)]; Cfg.put("stt_lang", lid); TrWizard.syncSaved("stt_lang", lid) ; if (lid == "ar" && Cfg.str("stt_engine", "whisper") == "sherpa") Notice.show(this, "⚠ (v468) SenseVoice (Sherpa) لا يدعم العربية — التفريغ العربي هيتعمل بـ Whisper تلقائيًا", 3200L) }
        val offPartLabels = listOf("⚡ لحظي (كل باتش)", "٣ دقايق", "٥ دقايق", "١٠ دقايق", "٢٠ دقيقة"); val offPartVals = listOf(1, 3, 5, 10, 20)
        val offPartChips = ui.chips(offPartLabels, { offPartLabels[offPartVals.indexOf(Cfg.int("ctx_min", 3)).let { if (it < 0) 1 else it }] }) { Cfg.put("ctx_min", offPartVals[offPartLabels.indexOf(it).coerceAtLeast(0)].toString()) }   // (v451) كان coerceAtLeast(1) فزرار «لحظي» (قيمة 1) كان بيتعرض «٣ دقايق» ومابيتعلّمش
        // (v998) وارد: طول جزء التفريغ (٥ أو ١٠ دقايق) — بيتقرا من WaridTx.chunkSec() عند بداية التفريغ، وحفظ فوري
        val waridChunkLabels = listOf("٥ دقائق", "١٠ دقائق")
        val waridChunkVals = listOf("300", "600")
        val waridChunkChips = ui.chips(waridChunkLabels, { waridChunkLabels[waridChunkVals.indexOf(Cfg.str(WaridTx.CHUNK_MIN_KEY, "300")).let { if (it < 0) 0 else it }] }) { Cfg.put(WaridTx.CHUNK_MIN_KEY, waridChunkVals[waridChunkLabels.indexOf(it).coerceAtLeast(0)]) }
        // (v999 ملاحظة 1) نموذج التفريغ المستقل عن نموذج الترجمة (Cfg lec_model): الأدق افتراضيًا.
        val lecModelLabels = listOf("🎯 الأدق (أبطأ)", "⚡ السريع")
        val lecModelVals = listOf(Lecture.LEC_MODEL_ACCURATE, Lecture.LEC_MODEL_FAST)
        val lecModelChips = ui.chips(lecModelLabels, { lecModelLabels[lecModelVals.indexOf(Lecture.lecModel()).let { if (it < 0) 0 else it }] }) { Cfg.put(Lecture.LEC_MODEL_KEY, lecModelVals[lecModelLabels.indexOf(it).coerceAtLeast(0)]) }
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
        val toneDegrees = listOf("خفيفة", "متوسطة", "شديدة")
        val toneDegChips = ui.chips(toneDegrees, { Cfg.str("tone_strength", "متوسطة").ifBlank { "متوسطة" } }) { Cfg.put("tone_strength", it) }
        val watchViews = listOf<View>(
            ui.section("🌙 الخروج من المشغّل", false,
                fxSwitch("اسألني لما أخرج: أكمّل الترجمة في الخلفية؟ (لو مقفول: الخروج بيقفل الترجمة خالص)", "ask_bg_exit", true)),
            ui.section("🔴 ترجمة حية فوق أي تطبيق", false,
                ui.noHint(),
                ui.button("▶ شغّل الترجمة الحية") { startActivity(Intent(this, LiveRequestActivity::class.java)) }))
        // (v235) نفس ترتيب الأقسام في كل أشكال الإعدادات (SettingsTabs): المفاتيح والمحرك · الترجمة وشكلها · الصوت والصورة · عام · الموسيقى. المسرد اتشال.
        // (v836) محتوى الأقسام lazy: بيتبني أول مرة القسم يتفتح بس (مش في onCreate) — TabDef بياخد دالة مش قايمة
        fun tab(id: String, content: () -> List<View>, preview: Boolean = false) = TabDef(id, SettingsTabs.label(id), content, preview, SettingsTabs.sub(id), SettingsTabs.group(id))
        val settingsDlg = TabbedDialog(this, ui, if (Cfg.tvOv != null) "📺 إعدادات التلفزيون المشتركة (مش الموبايل)" else "⚙️ الإعدادات", listOf(
            // ===== 🚀 خط الترجمة: الخطة (تفريغ ← ترجمة ← لهجة) + اللهجة والأسلوب + البرومبتات =====
            tab("general", { listOf<View>(
                ui.noHint(),
                savedPlanTxt, planBtn,
                ui.section("🗣 اللهجة والأسلوب والشدة", true,
                    ui.text("لهجة الترجمة (فصحى = من غير تحويل):", 12f, th.muted),
                    ui.chips(langs, { lang }) { lang = it; save(); val dv = if (it == "فصحى") "" else it; Cfg.put("conv_dialect", dv); TrWizard.syncSaved("conv_dialect", dv) },
                    ui.text("أسلوب الترجمة:", 12f, th.muted),
                    ui.chips(styles, { Cfg.str("tone_style", "").ifBlank { "تلقائي" } }) { style = it; save(); Cfg.put("tone_style", if (it == "تلقائي") "" else it) },
                    ui.text("الشدة (بتشتغل مع شعبي / جرئ / تلقائي بس):", 12f, th.muted), toneDegChips,
                    ui.noHint()).apply { if (Strict.hideTrOpts()) visibility = View.GONE },
                ui.section("🏠 اللغة الأساسية", false,
                    ui.noHint(),
                    ui.chips(baseLangs.map { it.second }, { baseLangName() }) { n -> Cfg.put("base_lang", baseLangs.firstOrNull { it.second == n }?.first ?: "ar") }),
                ui.button("📝 البرومبتات (شوف · انسخ · عدّل · استبدل)") { PromptsUi.show(this, ui) },
                ui.section("📖 مسرد المصطلحات الثابت", false,
                    ui.noHint(), gloss)) }),
            // ===== 🔑 المفاتيح: جيميناي · الوضع البصري · محركات خارجية =====
            tab("keys", { listOf<View>(
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
                    ui.noHint(), keysUi, backupUi, extraUi))) }),
            // ===== 🎙 التفريغ والصوت: موديلات الجهاز + إعدادات الصوت =====
            tab("audio", { listOf<View>(
                ui.noHint(),
                ui.section("🔊 الصوت", true, atrack, audioPresetTitle, audioPresetChips, audioPresetDesc, *fl("soundtags", "hitiming")),
                ModelsUi.section(this, ui, th),
                ui.section("📴 التفريغ على الجهاز (من غير مفتاح)", true,
                    ui.noHint(),
                    ui.text("حجم موديل Whisper:", 12f, th.muted), offModelChips, ui.text("⚡ خانات التفريغ المتوازي:", 12f, th.muted), parChips, parNote, offStatus, offDlBtn, offProg,
                    ui.text("لغة الكلام (لو عارفها بتقلّل أخطاء الاكتشاف التلقائي):", 12f, th.muted), offLangChips,
                    ui.noHint())) }),
            // ===== 🤖 الموديل والأداء =====
            tab("perf", { listOf<View>(
                ui.noHint(),
                                ui.section("🤖 الموديل والسرعة", true, modelChips, fetchModelsBtn, model, modelDesc,
                    ui.noHint(), speedBtn, speedOut, speedUseBtn),
                ui.section("⏱ التقطيع والتنقيح", false, chunk, ahead, hlsAhead,
                    ui.noHint(), offPartChips,
                    ui.switchRow("كمّل ترجمة باقي الفيديو بعد ما توصل لمكان التشغيل (بيرجع للأول لو فيه جزء ناقص)", Cfg.bool("keepgoing", true)) { Cfg.put("keepgoing", if (it) "1" else "0") },
                    *fl("prefine")),
                ui.section("🌐 وارد — طول جزء التفريغ", false,
                    ui.text("أجزاء التفريغ بطريقة وارد (٥ أو ١٠ دقايق) — بيتطبق على التفريغات الجديدة بس؛ تفريغ وارد شغال بيكمّل بنفس حجمه.", 12f, th.muted),
                    waridChunkChips),
                ui.section("🎙 نموذج التفريغ الاحتياطي (الطريقة العادية القديمة)", false,
                    ui.text("موديل الطريقة العادية القديمة بس — تفريغ الاستوديو الجديد بيشتغل بطريقة وارد (سلسلة موديلات تلقائية) ومبيتأثرش بالاختيار ده. ومبيغيّرش موديل الترجمة.", 12f, th.muted),
                    lecModelChips)) }),
            tab("visual", { listOf<View>(
                ui.section("🔑 مفتاح الوضع البصري فقط", true,
                    ui.noHint(), visKeysUi),
                                ui.section("👁 محرك الوضع البصري", false, visAutoChips,
                    ui.slider("المسافة اللي الوضع البصري بيفحصها قدّام مكان التشغيل", Cfg.int("vis_ahead", 180).coerceIn(30, 900), 30, 900, "ث") { Cfg.put("vis_ahead", it.toString()) },
                    ui.noHint())) }),
            // ===== 🎬 شكل الترجمة =====
            tab("look", { listOf<View>(
                ui.section("🔤 الخط والحجم", true, *sp.fonts.toTypedArray()),
                ui.section("✨ الأنيميشن", false, *sp.anim.toTypedArray()),
                *sp.look.toTypedArray(),
                ui.section("🔘 كبسولات العدّادات (صوتي · بصري · نت)", false,
                    ui.noHint(),
                    ui.slider("الظهور (100 = معتمة، أقل = أشفّ)", Cfg.int("badge_alpha", 55).coerceIn(15, 100), 15, 100, "%") { Cfg.put("badge_alpha", it.toString()) })) }, true),
            // (v606) المظهر: 7 أقسام بقوا 3 — الثيم (+ استوديو السمات + راحة العين) · الواجهة (السكِن + نوع الجهاز) · الحركة والوصول
            tab("appearance", { listOfNotNull<View>(
                ui.section("🎨 المظهر (من MeeTube)", true,   // (v907) مصدر واحد: إعداد الثيم بتاع MeeTube نفسه (ThemeMode) — Joyboy وMeeTube بيتبعوه مع بعض
    ui.chips(listOf("⚙ النظام", "☀️ فاتح", "🌙 داكن"), { when (com.newtube.mobile.ui.common.ThemeMode.peek(this)) { com.newtube.mobile.ui.common.ThemeMode.LIGHT -> "☀️ فاتح"; com.newtube.mobile.ui.common.ThemeMode.DARK -> "🌙 داكن"; else -> "⚙ النظام" } }) { n ->
        com.newtube.mobile.ui.common.ThemeMode.set(this, when (n) { "☀️ فاتح" -> com.newtube.mobile.ui.common.ThemeMode.LIGHT; "🌙 داكن" -> com.newtube.mobile.ui.common.ThemeMode.DARK; else -> com.newtube.mobile.ui.common.ThemeMode.SYSTEM })
        recreate()
    },
    ui.noHint()),
                if (MtGlass.on()) ui.section("🎬 مظهر المشغّل (MeeTube)", false,
                    ui.noHint(),
                    ui.chips(listOf("🫧 شفاف", "🧊 مفروست", "▫️ مينيمال"), { when (MtGlass.playerBtnLook()) { 1 -> "🧊 مفروست"; 2 -> "▫️ مينيمال"; else -> "🫧 شفاف" } }) { n ->
                        Cfg.put("player_btn_look", when (n) { "🧊 مفروست" -> "1"; "▫️ مينيمال" -> "2"; else -> "0" })
                    },
                    *MtLook.views(this, ui, th).toTypedArray()) else null,
                ui.section("🎨 الشكل", false,
                    ui.noHint(),
                    ui.noHint(),
                    ui.slider("درجة الدفء", Cfg.int("warm", 0), 0, 100, "%") { Cfg.put("warm", it.toString()); refreshWarm() }),
                ui.section("📱 نوع الجهاز (موبايل · تابلت · تلفزيون)", false,
                    ui.noHint(),
                    ui.chips(listOf("🔄 تلقائي", "📱 موبايل", "📟 تابلت", "📺 تلفزيون"), { DeviceUi.label(DeviceUi.pref()) }) { n ->
                        val m = when (n) { "📱 موبايل" -> DeviceUi.PHONE; "📟 تابلت" -> DeviceUi.TABLET; "📺 تلفزيون" -> DeviceUi.TV; else -> DeviceUi.AUTO }
                        Cfg.put("ui_mode", m)
                        Notice.show(this, "🖥 الواجهة: " + DeviceUi.label(m), 1400L)
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (m != DeviceUi.TV) putExtra("tab", "settings") })
                        finish()
                    }),
                ui.section("♿ الحركة والوصول", false,
                    ui.switchRow("🔠 وضع النص الكبير (تكبير خطوط الواجهة)", Cfg.bool("big_text", false)) { Cfg.put("big_text", if (it) "1" else "0"); recreate() },
                    ui.switchRow("🎞 انتقالات انسيابية بين التبويبات", Cfg.bool("anim_tabs", true)) { Cfg.put("anim_tabs", if (it) "1" else "0") },
                    ui.switchRow("📳 اهتزاز لمسي خفيف عند التنقّل بين التبويبات", Cfg.bool("haptics", true)) { Cfg.put("haptics", if (it) "1" else "0") })) }),
            tab("tvset", { listOf<View>(
                ui.text(if (Cfg.tvOv != null) "✏️ أنت دلوقتي بتعدّل نسخة التلفزيون من الإعدادات المشتركة (الترجمة · شكل الترجمة · الصوت…): كل حاجة بتغيّرها في أي قسم بتتحفظ على التلفزيون بس ومابتأثرش على الموبايل. لما تخلّص دوس «ارجع لإعدادات الموبايل»." else "الإعدادات المشتركة (الترجمة · شكل الترجمة · الصوت…) موجودة على الموبايل والتلفزيون. تقدر تعدّل نسخة التلفزيون لوحدها من غير ما نسخة الموبايل تتغيّر.", 12f, if (Cfg.tvOv != null) th.primary else th.muted),
                if (Cfg.tvOv != null) ui.button("↩ ارجع لإعدادات الموبايل", true) { TvEdit.exit(this) } else ui.button("✏️ عدّل إعدادات التلفزيون المشتركة (على التلفزيون بس)", true) { TvEdit.enter(this) },
                ui.switchRow("زامن تغييرات إعدادات الموبايل للتلفزيون تلقائياً (قفّله لو عايز كل جهاز بإعداداته)", Cfg.str("tv_sync_off", "0") != "1") { on -> Cfg.put("tv_sync_off", if (on) "0" else "1") },
                ui.noHint(),
                ui.button("📺 افتح إعدادات التلفزيون", true) { TvLookPad.show(this) },
                ui.button("🔧 افتح إعدادات البرنامج على شاشة التلفزيون") { try { RemoteClient.cmd("open_settings") } catch (_: Throwable) {} }) }),
            tab("youtube", ytSettingsViews),
            // ===== 🎵 الموسيقى =====
            
            TabDef("music", SettingsTabs.label("music"), { listOf<View>(
                ui.section("📝 الكلمات", true,
                                        ui.noHint(),
                    ui.switchRow("جيب الكلمات تلقائيًا لما أفتح المشغّل", Cfg.str("music_auto_lyr", "1") != "0") { Cfg.put("music_auto_lyr", if (it) "1" else "0") }),
                ui.section("🔤 شكل الكلمات", false,
                    ui.text("الخط:", 12f, th.muted), lyrFontChips,
                    ui.text("الحجم:", 12f, th.muted), lyrSizeChips),
                ui.section("🎵 المكتبة", false,
                    ui.text("لو أغاني جديدة ماظهرتش في صفحة الموسيقى، أعد الفحص:", 12f, th.muted),
                    ui.button("🔄 إعادة فحص الأغاني") { try { musicUi?.reload(true) } catch (_: Throwable) {}; Notice.show(this, "🔄 بفحص الأغاني…", 1600L) })) }, false, SettingsTabs.sub("music"), group = SettingsTabs.group("music")),
            // ===== 🛠 عام: الخروج · الترجمة الحية · الطابور · البطارية · الأمان · النسخة الاحتياطية · الثيم =====
            tab("more", { listOf<View>(
                watchViews[0],   // (v458) بعد شيل القسمين الميتين: الخروج من المشغّل
                                ui.section("▶ التشغيل", true,
                    ui.switchRow("كمّل الطابور تلقائيًا بعد إعادة تشغيل الجهاز", Cfg.bool("bg_autostart", true)) { Cfg.put("bg_autostart", if (it) "1" else "0") },
                    ui.noHint(),
                    ui.button("📋 افتح مجلد الترجمة في الخلفية") { settingsDlg0?.dialog?.dismiss(); qui0?.show() }),
                                ui.section("🔋 عشان الجهاز ما يقتلش الخدمة", true,
                    ui.noHint(),
                    ui.button("1) استثناء من توفير البطارية") { requestBatteryExemption() },
                    ui.button("2) السماح بالتشغيل التلقائي / الخلفية للتطبيق") { openAutoStartSettings() },
                    ui.noHint()),
                ) }),   // (v815) الاصطياد والترجمة الحية بقوا قسم لوحده (🔗) تحت
            tab("capture", { listOf<View>(
                ui.section("🔗 مراقب صفحات الفيديو في المتصفحات", true,
                    ui.noHint(),
                    ui.text((if (LinkHub.overlayOk(this)) "✅ العرض فوق التطبيقات: مسموح" else "⚠ العرض فوق التطبيقات: مش مسموح — ده الإذن الأساسي، دوس الزرار اللي تحت") + "\n" +
                        (if (LinkWatch.enabled(this)) "✅ إمكانية الوصول: متفعّلة (للمتصفحات + معرفة مصدر النسخ)" else "ℹ إمكانية الوصول: مش متفعّلة (اختيارية — للمتصفحات بس)"), 12f, th.text),
                    ui.button("🖼 اسمح بالعرض فوق التطبيقات (الأساسي)") { try { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 14) } catch (_: Throwable) {} },
                    ui.switchRow("لقط صفحات الفيديو في المتصفحات", !Cfg.bool("lw_web_off", false)) { Cfg.put("lw_web_off", if (it) "0" else "1") },
                    ui.button("⚙ إمكانية الوصول (اختياري — للمتصفحات)") { LinkWatch.openSettings(this) },
                    ui.noHint()),
                watchViews[1]) }),   // (v458) الترجمة الحية
            tab("security", { listOf<View>(
                            ui.section("🔒 الأمان", false, lockStatus,
                ui.noHint(),
                ui.button("🔑 تعيين / تغيير النمط") { lk.change { refreshLock() } }, bioBtn,
                ui.button("🗑 إزالة القفل") { lk.remove { refreshLock() } },
                ui.noHint()),
                ui.section("💾 نسخة احتياطية (مفاتيح · إعدادات · ثيم)", false,
                    ui.noHint(),
                    ui.text(Backup.autoTime(this).let { t -> if (t > 0) "آخر نسخة تلقائية: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(t)) else "لسه مفيش نسخة تلقائية" }, 12f, th.text),
                    ui.button("💾 احفظ نسخة في ملف") { backupToFile() },
                    ui.button("📂 استرجع من ملف") { backupFromFile() },
                    ui.button("♻ استرجع آخر نسخة تلقائية") { restoreAuto() },
                    ui.button("💾 نسخة شاملة (إعدادات + سجل المشاهدة)") { fullToFile() },
                    ui.button("📂 استرجع نسخة شاملة") { fullFromFile() })) })), sp.holder) { save(); refreshChip(); if (fromPlayer) finish() }
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
        lib.onUniSearch = { q -> globalSearch(q) }   // (v469) D4 بحث شامل موسّع (مكتبة + موسيقى + سجل يوتيوب + أقسام الإعدادات)
        lib.onDash = { a -> when (a) { "yt" -> { MeeTubeLink.open(this) }; "music" -> showTabFn(1); "browser" -> { save(); NavAnim.set(curTab, 2); startActivity(Intent(this, BrowserActivity::class.java)) }; "transfer" -> startActivity(Intent(this, FileTransferActivity::class.java)); "files" -> startActivity(Intent(this, FileManagerActivity::class.java)); "tvfiles" -> startActivity(Intent(this, TvFilesActivity::class.java)); "tvvideos" -> XferUi.browseTv(this) } }   // (v469) D3 بلاطات لوحة البداية · (v607) مركز النقل
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
        // (v547 م3) فيديو التلفزيون (uri = tv://id): عمليات الملفات/الترجمة بالخلفية ما بتتعملش من الموبايل
        fun tvItem(v: VideoItem): Boolean {
            if (!v.uri.startsWith(LibraryCore.SCHEME, true)) return false
            Notice.show(this, "📺 ده فيديو على التلفزيون — العملية دي مش متاحة من الموبايل", 3200L)
            return true
        }
        lib.onBg = { v -> if (!tvItem(v)) startBg(listOf(v)) }
        lib.onBgStop = { v -> BgJobs.stop(v.videoId); Notice.show(this, ("⏹ وقفت الترجمة في الخلفية (التقدم اتحفظ)").toString(), 2300L); lib.refreshRows() }
        lib.bgJob = { v -> BgJobs.find(v.videoId) }
        lib.onBgFolder = { f ->
          if (f.videos.firstOrNull()?.let { tvItem(it) } != true) {
            val todo = f.videos.filter { !BgJobs.isActive(it.videoId) }
            GAlert(this).setTitle("🌙 ترجمة الفولدر في الخلفية")
                .setMessage("هترجم ${todo.size} فيديو ورا بعض (واحد واحد). ده بيستهلك كوتة المفاتيح. تكمّل؟")
                .setPositiveButton("ابدأ") { _, _ -> startBg(todo) }.setNegativeButton("إلغاء", null).show()
          }
        }
        val ops = MediaOps(this, ui, th) { VideoScan.cache = null; scanFn(); if (MusicScan.items != null) { MusicScan.invalidate(); musicUi?.reload() } }
        mediaOps = ops
        // (v658) «📤 إرسال» من شريط التحديد: بيفتح نقل الملفات على الرادار والملفات المحددة جاهزة (من غير ما تختارها تاني)
        val sendVia: (List<VideoItem>) -> Unit = { vs ->
            try {
                startActivity(Intent(this, FileTransferActivity::class.java)
                    .putStringArrayListExtra("ft_uris", ArrayList(vs.map { it.uri }))
                    .putStringArrayListExtra("ft_names", ArrayList(vs.map { it.name }))
                    .putExtra("ft_sizes", vs.map { it.size }.toLongArray()))
            } catch (e: Throwable) { LogStore.err("Main:sendVia", e); Notice.show(this, "⚠ ما قدرتش أفتح نقل الملفات", 2600L) }
        }
        lib.onMoveMany = { vs -> if (vs.firstOrNull()?.let { tvItem(it) } != true) ops.moveMany(vs, lib.allFolders()) }
        lib.onSendMany = { vs -> if (vs.firstOrNull()?.let { tvItem(it) } != true) sendVia(vs) }
        lib.onTvPlay = { vs -> if (vs.firstOrNull()?.let { tvItem(it) } != true) TvQueue.start(this, vs) }
        lib.onRename = { v -> if (!tvItem(v)) ops.rename(v) }
        lib.onMove = { v -> if (!tvItem(v)) ops.move(v, lib.allFolders()) }
        lib.onShare = { v -> if (!tvItem(v)) ops.share(v) }
        lib.onShareMany = { vs -> if (vs.firstOrNull()?.let { tvItem(it) } != true) ops.shareMany(vs) }
        lib.onPlayWeb = { w -> save(); playWeb(w) }
        saveFn = { save() }
        lib.onYtHistory = { showYtHistory() }   // (v407) شريحة «🕘 سجل يوتيوب» في فولدر المصطادة
        lib.onDelete = { v -> if (!tvItem(v)) ops.delete(v) }
        lib.onRenameFolder = { f -> if (f.videos.firstOrNull()?.let { tvItem(it) } != true) ops.renameFolder(f) }
        lib.onDeleteFolder = { f -> if (f.videos.firstOrNull()?.let { tvItem(it) } != true) ops.deleteFolder(f) }
        lib.onDetails = { v -> if (!tvItem(v)) ops.details(v, Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }).firstOrNull { it.id == v.videoId }) }
        val qui = QueueUi(this, ui, th); queueUi = qui; qui0 = qui; settingsDlg0 = settingsDlg; settingsShow = { t -> settingsDlg.setFilter(null); if (fromPlayer) settingsDlg.show(t) else { showTabFn(7); settingsDlg.showEmbedded(t) } }
        // (v820) إعدادات الصفحة: نفس صفحة الإعدادات بس مفلترة على أقسام الصفحة (PageSettings) — الشاملة بتفضل زي ما هي (settingsShow بتشيل الفلتر)
        pageSettingsShow = { pg -> if (!fromPlayer) { settingsDlg.setFilter(PageSettings.sections(pg), PageSettings.title(pg)); showTabFn(7); settingsDlg.showEmbedded(null) } }
        settingsBack = { settingsDlg.back() }
        dismissSettings = { try { settingsDlg.dialog.dismiss() } catch (_: Throwable) {} }
        // رجّع الطابور المحفوظ (لو التطبيق اتقفل/اتمسح) وكمّل الترجمة
        if (!fromPlayer && BgJobs.restore(this) > 0) { BgService.start(this); libUi?.refreshRows() }
        lib.onQueue = { qui.show() }
        try { TaskStore.resume(this) } catch (_: Throwable) {}   // (v457) كمّل مهام الترميز اللي اتقطعت
        BgJobs.onChange = { runOnUiThread { if (!isDestroyed) { libUi?.refreshRows(); if (qui.showing) qui.refresh(); TaskCenter.changed(); updateTasksFab() } } }
        lib.gate = { open -> lk.gate(open) }
        // (v87) ⚙️ في المكتبة: قايمة صغيرة منسدلة بالأقسام، دوسة على قسم تفتحه دايركت (بدل شاشة القايمة الكبيرة)
        lib.onSettings = { _ -> settingsShow?.invoke(null) }   // (v370) ⚙️ فوق = نفس تبويب ⚙️ الإعدادات تحت (كانت قايمة منسدلة تانية)
        lib.onLink = { recentDlg?.show() }
        lib.onPick = { save(); pickVideo() }
        lib.onGrant = { requestPermissions(arrayOf(VideoScan.permission()), 11) }
        fun doScan(refresh: Boolean = false) {
            libStarted = true
            val be = libBackend
            if (!be.remote && !VideoScan.hasPermission(this)) { lib.showNoPermission(); return }
            val cached = be.cached()
            if (cached != null) lib.showVideos(cached) else if (!lib.hasData) lib.showScanning()
            be.load(this, refresh) { res, err ->
                if (isDestroyed || isFinishing || be !== libBackend) return@load
                if (res != null) lib.showVideos(res)
                else {
                    Notice.show(this, (err ?: "حصلت مشكلة").toString(), 4200L)
                    if (!lib.hasData) lib.showVideos(emptyList())
                }
            }
        }
        scanFn = { doScan() }
        // (v547 م3) تبديل مصدر المكتبة (الموبايل / التلفزيون) من القايمة الجانبية أو من شاشة الريموت
        libSourceFn = { tv ->
            if (tv && DeviceUi.isTv(this)) Notice.show(this, "الجهاز ده نفسه تلفزيون", 2500L)
            else {
                if (tv) { try { RemoteClient.init(this) } catch (_: Throwable) {} }
                if (tv && RemoteClient.lastTv() == null) Notice.show(this, "📺 مفيش تلفزيون مقترن — اقرنه من «ريموت التلفزيون»", 3800L)
                else {
                    libBackend = if (tv) TvLibrary else LocalLibrary
                    lib.backend = libBackend
                    lib.resetNav()
                    if (tv) { TvLibrary.invalidate(); lib.showScanning(); doScan(true) }
                    else { lib.showScanning(); doScan() }
                }
            }
        }
        lib.onSource = { tv -> libSourceFn?.invoke(tv) }
        // ريفريش: يمسح الفولدرات والصور المصغّرة المحفوظة ويفحص من الأول
        lib.onPull = { libBackend.invalidate(); doScan(true) }
        lib.onRefresh = { libBackend.invalidate(); libBackend.clearThumbs(); lib.showScanning(); doScan(true) }
        // (v468) تحديث تلقائي كل ١٠ ثوانٍ والقائمة ظاهرة: فحص خلفي صامت (من غير مسح كاش/صور ولا شاشة فحص) و showVideos بتتجاهل النتيجة لو البصمة ما اتغيّرتش
        lib.onAutoRefresh = { if (!libBackend.remote) doScan() }

        // (v124) الشريط السفلي: بوابتين — الفيديوهات (المكتبة) · المتصفح (تبويب يوتيوب اتشال)
        // (v135) بوابة تالتة: «المهام» (قص · صوت · GIF · ضغط · ترجمة ثابتة)
        toolsUiM = ToolsUi(this, ui, th)
        val tasksUi = TasksUi(this, ui, th) { k -> pickTool(k) }; tasksUiRef = tasksUi
        tasksUi.onOpenOut = { t -> openTaskOutput(t) }   // (v472) دوسة على مهمة خالصة تفتح الفيديو
        tasksUi.onClose = { showTabFn(if (tabBeforeTasks == 5) 0 else tabBeforeTasks) }   // (v324) ✕ في صفحة المهام
        // (v198) الشريط السفلي (البوابات) وشريط النظام بياخدوا لون مشغّل الموسيقى لما يتفتح، ويرجعوا لثيم البرنامج لما يتقفل
        @Suppress("DEPRECATION")
        fun setNavTint(c: Int?) {
            navUi?.tint(c)
            fabHide = c != null; updateTasksFab()
            pageGearUi?.hidden = c != null   // (v820) مشغّل الموسيقى الكامل فاتح: زرار الإعدادات يتخبّى
            try {
                // (v199) شريط الحالة (فوق) وشريط البوابات وشريط النظام (تحت) بنفس اللون: لون الغلاف وقت المشغّل، وإلا لون الثيم
                window.statusBarColor = c ?: (if (MtSkin.on()) 0 else th.bg); window.navigationBarColor = c ?: (if (MtSkin.on()) 0 else th.bg)   // (v897) شفافين: الخلفية الزجاجية تبان تحتهم
                if (th.isLight) {
                    var f = window.decorView.systemUiVisibility
                    f = if (c != null) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    if (android.os.Build.VERSION.SDK_INT >= 26) f = if (c != null) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    window.decorView.systemUiVisibility = f
                }
            } catch (_: Throwable) {}
        }
        val pane = SwipeFrame(this)   // (v654) سويب فيديوهات ↔ موسيقى
        // (v310) صفحة الموسيقى بتتبني أول ما تفتحها بس (كانت بتتبني كلها وقت فتح التطبيق وده كان أكتر حاجة بتبطّأ البداية)
        fun ensureMusic(): MusicUi {
            musicUi?.let { return it }
            val music = MusicUi(this, ui, th); musicUi = music; music.root.visibility = View.INVISIBLE
            music.onNavTint = { c -> setNavTint(c) }
            music.onYtDownload = { n -> ytDownload(n, true) }
            music.onArt = { dockUi?.refresh() }
            music.lib.onYtPick = { n -> playYtAudio(n.id, n.title, n.channel, n.thumb) }   // (v344) بحث الموسيقى: اقتراح يوتيوب = صوت
            music.lib.onWebSearch = { q -> webSearch(q) }
            music.lib.onUniSearch = { q -> globalSearch(q) }   // (v469) D4
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
            music.lib.onMoveMany = { vs -> ops.moveMany(vs, music.lib.allFolders()) }   // (v658)
            music.lib.onSendMany = { vs -> sendVia(vs) }
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
        // (v189) البوابات: 0 الفيديوهات · 1 الموسيقى · 2 المتصفح · 3 الملفات (شاشات لوحدها) · (v324) 4 يوتيوب
        // (v324) 5 = صفحة المهام: مش في الشريط، بتتفتح من الزرار العايم (الشريط ساعتها مفيهوش بوابة نشطة)
        showTabFn = { i ->
            if (i == 4) { MeeTubeLink.open(this) }   // (v888) يوتيوب = MeeTube دايمًا (موبايل وتابلت وتلفزيون) — بوابة Joyboy اتمسحت
            else if (i == 0 || i == 1 || i == 5 || i == 7) {
                val prevTab = curTab
                val prevView: View? = when (prevTab) { 0 -> lib.root; 1 -> musicUi?.root; 7 -> settingsHostRef; 5 -> tasksUi.root; else -> null }   // (v621) للأنيميشن الخارج
                if (i == 5 && prevTab != 5) tabBeforeTasks = prevTab
                if (i == 7 && prevTab != 7) tabBeforeSettings = prevTab
                if (prevTab == 7 && i != 7) { try { save(); refreshChip() } catch (_: Throwable) {} }
                curTab = i
                NavOrigin.mainTab(i)   // (v1006)
                NetLedger.tab(when (i) { 0 -> "فيديوهات"; 1 -> "يوتيوب"; 5 -> "المهام"; 7 -> "الإعدادات"; else -> "تبويب $i" })
                dockUi?.refresh()   // (v344) المشغّل المصغّر بيختفي
                stripUi?.hidden = (curTab == 5)   // (v345) في صفحة الموسيقى وبيظهر في الباقي
                lib.root.visibility = if (i == 0) View.VISIBLE else View.INVISIBLE   // (v940) كل التبويبات متقاسة ورا بعض: التبديل من غير layout تقيل
                if (i == 1) ensureMusic()
                musicUi?.root?.visibility = if (i == 1) View.VISIBLE else View.INVISIBLE
                tasksUi.root.visibility = if (i == 5) View.VISIBLE else View.INVISIBLE
                settingsHostRef?.visibility = if (i == 7) View.VISIBLE else View.INVISIBLE
                tasksDlgRefresh = if (i == 5) ({ tasksUi.refresh() }) else null
                syncNav()   // (v475)
                if (i == 1) { musicUi?.ensureLoaded(); musicUi?.applyNavTint() } else setNavTint(null)
                if (i == 5) tasksUi.refresh()
                updateTasksFab()
                pageGearUi?.sync(i)   // (v820) ⚙️ الصفحة: بيظهر في الفيديوهات/الموسيقى/المهام بس
                if (prevTab != i) {   // (v312) الصفحة الجديدة بتنزلق من ناحية اتجاه الانتقال (الشريط RTL: رقم أكبر = شمال)
                    val target: View? = when (i) { 0 -> lib.root; 1 -> musicUi?.root; 7 -> settingsHostRef; else -> tasksUi.root }
                    if (target != null && Cfg.bool("anim_tabs", true)) {
                        val dir = if (NavAnim.pos(i) > NavAnim.pos(prevTab)) -1 else 1
                        Motion.tabEnter(target, dir)
                        if (prevView != null && prevView !== target) Motion.tabExit(prevView, -dir) { curTab == prevTab }   // (v621)
                    }   // (v469) D8 مفتاح إيقاف الانتقالات
                    if (Cfg.bool("haptics", true)) try { window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) {}   // (v469) D8 اهتزاز لمسي خفيف
                }
            }
        }
        musicOpenFn = { showTabFn(1); musicUi?.showFull() }   // (v337)
        // (v621) تسخين: صفحتي يوتيوب والموسيقى بتتبنوا وإنت فاضي بعد ما الشاشة تظهر، بدل ما يتبنوا وقت الدوسة على التبويب (أول فتح كان بيهنّج)
        if (!fromPlayer && !tvShellOn && !DeviceUi.isTv(this) && Cfg.bool("prewarm_tabs", true)) {
            val dv = window.decorView
            dv.postDelayed({ if (!isFinishing && !isDestroyed) android.os.Looper.myQueue().addIdleHandler { if (!isFinishing && !isDestroyed) { try { ensureMusic(); musicUi?.ensureLoaded() } catch (e: Throwable) { LogStore.err("Main:prewarmMusic", e) } }; false } }, 1500L)   // (v940) الموسيقى تتبني وتتحمّل ورا الستار بدري
        }
        val tabletShell = DeviceUi.isTablet(this)   // (v462) قشرة التابلت: ريل جانبي زي يوتيوب التابلت بدل الشريط السفلي
        val nav = BottomNav(this, ui, th, 0, tabletShell) { i -> if (i >= BottomNav.YT_HOME) { MeeTubeLink.open(this) /* (v888) MeeTube */ } else if (i == 2) { save(); NavAnim.set(curTab, 2); startActivity(TabNav.front(Intent(this, BrowserActivity::class.java))) } else if (i == 3) { save(); NavAnim.set(curTab, 3); startActivity(TabNav.front(FilesHub.intent(this))) } else if (i == 7) { musicReturnTab = -1; settingsShow?.invoke(null) } else if (i == 0 && fromTv && !fromPlayer && TvFocus.goHome(this)) { /* (v482) تلفزيون: «الرئيسية» = شاشة التلفزيون */ } else { musicReturnTab = -1; showTabFn(i) } }   // (v310) 4 الملفات = شاشة منفصلة زي المتصفح · (v606) يوتيوب شغّال ← المتصفح: الفيديو يكمّل في PiP والاتنين مع بعض
        navUi = nav
        nav.onMoreToggle = { o -> pageGearUi?.moreOpen = o }   // (v820)
        if (!fromPlayer) {
            TaskCenter.listener = { if (!isDestroyed && !isFinishing) { tasksDlgRefresh?.invoke(); runOnUiThread { updateTasksFab() } } }
        }
        if (!DeviceUi.isTv(this)) lib.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)   // (v721) الخلفية المتغيّرة تظهر ورا المكتبة
        pane.addView(lib.root, FrameLayout.LayoutParams(-1, -1))
        tasksUi.root.visibility = View.INVISIBLE
        if (!DeviceUi.isTv(this)) tasksUi.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        pane.addView(tasksUi.root, FrameLayout.LayoutParams(-1, -1))   // (v231) صفحة المهام
        if (!fromPlayer) {   // (v370) الإعدادات صفحة كاملة (تبويب ⚙️) بدل الدايلوج — الدايلوج بيفضل بس لما الإعدادات تتفتح من المشغّل
            val host = FrameLayout(this).apply { visibility = View.INVISIBLE }
            settingsDlg.embedClose = { if (fromTv) { try { startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() } catch (_: Throwable) { showTabFn(0) } } else showTabFn(if (tabBeforeSettings == 7) 0 else tabBeforeSettings) }   // (v467) جاي من التلفزيون: قفل صفحة الإعدادات يرجّع لواجهة التلفزيون مش لتبويب الفيديوهات
            settingsDlg.embedInto(host)
            pane.addView(host, FrameLayout.LayoutParams(-1, -1)); settingsHostRef = host
        }
        // (v336) الزرار العايم (TasksFab) اتشال مؤقتًا — المهام بقت زرار في الشريط السفلي
        updateTasksFab()
        val shell = LinearLayout(this).apply { orientation = if (tabletShell) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        // (v654) «ميديا»: شريط فيديوهات/موسيقى فوق الصفحة (مش في اللايت ولا التلفزيون)
        val msw = MediaSwitch(this, ui, th) { t -> musicReturnTab = -1; showTabFn(t) }
        mediaSw = msw
        pane.enabledSwipe = { !fromPlayer && !tvShellOn && !packageName.endsWith(".lite") && (curTab == 0 || curTab == 1) }
        pane.onSwipe = { musicReturnTab = -1; showTabFn(if (curTab == 0) 1 else 0) }
        val paneWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        paneWrap.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
        val floatNav = MtGlass.on() && !tabletShell && !fromPlayer && !DeviceUi.isTv(this) && Build.VERSION.SDK_INT >= 31   // (v895) شريط سفلي عايم بزجاج حقيقي (أندرويد 12+)
        if (!floatNav && !packageName.endsWith(".lite") && !DeviceUi.isTv(this)) paneWrap.addView(msw.view, LinearLayout.LayoutParams(-1, -2))   // (v829) زرارين فيديوهات/موسيقى بقوا تحت (فوق الشريط السفلي مباشرة) بدل فوق الصفحة
        if (tabletShell) { shell.addView(paneWrap, LinearLayout.LayoutParams(0, -1, 1f)); shell.addView(nav.view, LinearLayout.LayoutParams(ui.dp(80), -1)) }
        else if (floatNav) { MtFloat.attach(this, th, ui, shell, paneWrap, nav, if (!packageName.endsWith(".lite")) msw else null) }
        else { shell.addView(paneWrap, LinearLayout.LayoutParams(-1, 0, 1f)); shell.addView(nav.view, LinearLayout.LayoutParams(-1, -2)) }
        val frame = FrameLayout(this).apply { if (!MtSkin.on()) setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_LTR }   // (v897) الخلفية المتحركة في الـwindow
        tvShellOn = fromTv && !fromPlayer && DeviceUi.isTv(this)
        if (tvShellOn) { try { (nav.view.parent as? android.view.ViewGroup)?.removeView(nav.view) } catch (_: Throwable) {} }   // (v524) تلفزيون: الشريط السفلي بتاع الموبايل (6 أزرار) مشال
        if (tvShellOn) {
            // (v527) تلفزيون: يوتيوب صفحة كاملة — الشريط العلوي (تطبيقات/ملفات/يوتيوب/متصفح) اتشال. الخلفية بس فضلت، وتابات يوتيوب (الرئيسية · الاشتراكات · أنا) جوه صفحة يوتيوب نفسها. Back = اللانشر.
            frame.addView(TvShell.backdrop(this, th), FrameLayout.LayoutParams(-1, -1))
            frame.addView(shell, FrameLayout.LayoutParams(-1, -1))
        } else {
            // (v789) الخلفية المتغيّرة (AppWall) اتشالت من الموبايل/التابلت (كانت بتنزّل صور من النت كل فترة) — خلفية التلفزيون ليها نظامها الخاص في TvHome
            frame.addView(shell, FrameLayout.LayoutParams(-1, -1))
        }
        // (v823) كبسولة ⚙️ الصفحة: عايمة بس فوق الصفحة (مش صف) — لازقة فوق الشريط السفلي
        if (!fromPlayer && !DeviceUi.isTv(this)) { pageGearUi = PageGear(this, frame, nav.view, tabletShell, ui, th, msw.view) { pg -> pageSettingsShow?.invoke(pg) }; pageGearUi?.sync(curTab) }   // (v829) msw.view = الكبسولة ترتفع فوق زرارين الميديا ومتغطّيهمش
        if (fromPlayer) {
            setContentView(FrameLayout(this))
            settingsDlg.show(intent?.getStringExtra("tab"))   // من غير قسم محدد = قايمة الإعدادات كاملة
            return
        }
        setContentView(frame)
        RotateHint.attach(this)   // (v823) زرار «لفّ الشاشة» لما اتجاه التليفون يخالف اتجاه التطبيق
        if (intent?.getStringExtra("tab") == null) SplashArt.show(this, b, t0Boot)   // (v922) الدخول من شريط MeeTube/المتصفح بتبويب معيّن = من غير سبلاش، ينقل على طول · (v592) سبلاش Joyboy عشوائي مرة كل تشغيل · (v834) المدة من أول onCreate
        try { LogStore.add("⏱ Main.onCreate = " + (android.os.SystemClock.uptimeMillis() - t0Boot) + "ms") } catch (_: Throwable) {}
        // (v469) D2 طبقة الفلتر الدافئ فوق محتوى الشاشة (ما بتمنعش اللمس: View عادية من غير مستمعات، والعناصر العائمة فوقها)
        warmView = View(this).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        frame.addView(warmView, FrameLayout.LayoutParams(-1, -1))
        refreshWarm()
        dockUi = MiniDock(this, frame, ui, th, { musicOpenFn?.invoke() }, { curTab == 1 }) { t -> musicUi?.artBitmap(t) }   // (v344)
        stripUi = StatusStrip(this, frame, ui, th) { showTabFn(5) }   // (v345)
        tasksFabUi = TasksFab(this, frame, ui) { showTasksDialog() }   // (v420) عايم: بيظهر وقت المهام الشغّالة بس
        if (!fromPlayer && b == null && !tvShellOn) { frame.alpha = 0f; frame.animate().setStartDelay(0L).alpha(1f).setDuration(160L).start() }   // (v312) بدل السبلاش: ظهور ناعم سريع
        if (!fromPlayer) {   // (v310) أيقونة «الموسيقى» في اللانشر (alias) أو رجوع من المتصفح/الملفات لتبويب معيّن
            val cn = intent?.component?.className ?: ""
            // (v339) لو الأكتيفيتي اتعمل لها إعادة إنشاء (النظام قتلها وإنت في المشغّل): ارجع لنفس البوابة مش للفيديوهات
            val savedTab = b?.getInt("cur_tab", -1) ?: -1
            val t0 = intent?.getStringExtra("tab") ?: when (savedTab) { 1 -> "music"; 4 -> (if (DeviceUi.isTv(this)) "youtube" else null); 5 -> "tasks"; else -> null }   // (v886)
            if (t0 == "music") frame.post { showTabFn(1) }
            else if (t0 == "tasks") frame.post { showTabFn(5) }
            else if (t0 == "settings") frame.post { settingsShow?.invoke(null) }
            else if (t0 == "youtube") frame.post { if (intent?.getStringExtra("yt_open") == null) { MeeTubeLink.open(this) }; openYtFromIntent(intent) }   // (v880) أيقونة/تايل يوتيوب ← MeeTube
            if (intent?.getBooleanExtra("lib_tv", false) == true) { intent?.removeExtra("lib_tv"); frame.post { libSourceFn?.invoke(true) } }   // (v691) التطبيق ماكانش شغّال: افتح مكتبة التلفزيون
        }
        intent?.getStringExtra("reopen_tab")?.let { t -> intent?.removeExtra("reopen_tab"); frame.post { if (!isFinishing && !isDestroyed) settingsShow?.invoke(t) } }
        // لو مفيش مفتاح Gemini: القايمة بتظهر مباشرة أول البرنامج، وبعدها الفحص
        frame.post { permFlow { ensureKeysSoft { keys.setText(Cfg.allMainKeys().joinToString("\n")); keyLoadersRef.forEach { it() }; refreshChip(); doScan() } } }
        try { TvSync.install(this) } catch (_: Throwable) {}
        // (v727) «وضع الواجهة» القديم اتشال من الموبايل: نرجّعه للأصلي من غير مزامنة   // (v568) مزامنة الإعدادات للتلفزيون من أي شاشة
        frame.post { handleLinkIntent(intent) }; frame.post { handleExternal(intent) }; frame.post { handleClipBubbleIntent(intent) }
    }
    /** (v345) المرحلة 1 «كتلة واحدة»: أي لينك جاي من برّه (مشاركة أو فتح). لينك يوتيوب ← قايمة «افتحه إزاي؟»، غير كده ← زي زمان */
    private fun ytIdOf(u: String): String? = Regex("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^\\s#]*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})").find(u)?.groupValues?.get(1)
    /** (v729) أزرار ويدجت اللينكات العايمة (LinkWatchService): نفس أزرار دايرة الكليبورد اللي جوه البرنامج، بس جاية من فوق أي تطبيق */
    private fun handleClipBubbleIntent(i: Intent?) {
        if (i == null || isFinishing || isDestroyed) return
        if (i.getBooleanExtra("tv_toggle", false)) { i.removeExtra("tv_toggle"); try { TvModeFab.toggle(this) } catch (e: Throwable) { LogStore.err("Main:tvToggle", e) }; return }   // (v734) بلاطة «وضع التلفزيون»
        if (i.getBooleanExtra("clip_history", false)) { i.removeExtra("clip_history"); return }   // (v733) بلاطة «سجل الكليبورد»
        val act = i.getStringExtra("clip_act") ?: return
        i.removeExtra("clip_act")
        val url = i.getStringExtra("clip_url") ?: ""
        if (url.isEmpty()) return
        if (act == "web") { try { openLinkAs(url, Links.WEB) } catch (e: Throwable) { LogStore.err("Main:clipWeb", e) }; return }   // (v735) لينك عادي ← متصفح Joyboy
        val c = ClipVideo(url, i.getStringExtra("clip_title") ?: "", null, i.getStringExtra("clip_play") ?: "", i.getStringExtra("clip_kind") ?: "WEB",
            i.getStringExtra("clip_ref") ?: "", i.getStringExtra("clip_ua") ?: "")
        try { if (act == "orb") playClip(c) else clipAction(act, c) } catch (e: Throwable) { LogStore.err("Main:clipBubble", e) }
    }
    private fun handleExternal(i: Intent?) {
        if (i == null || isFinishing || isDestroyed) return
        // (v1003 — المرحلة 5) مشاركة ملف صوت/فيديو من برّه (واتساب وغيره) → الاستوديو مباشرة.
        // الملف بيتنسخ لـ filesDir/studio_inbox الأول (إذن المشاركة مؤقت)، وبعدين تفريغ + فتح الشاشة.
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) {
            if (handleSharedMedia(i)) return
        }
        val raw = when (i.action) { Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT) ?: ""; Intent.ACTION_VIEW -> i.dataString ?: ""; else -> return }
        val url = Regex("https?://\\S+").find(raw)?.value ?: return
        i.action = null   // ما يتكررش لو الأكتيفيتي اتعمل لها إعادة إنشاء
        val id = ytIdOf(url)
        if (id == null) { link.setText(url); openLinkAs(url); return }
        playYt(id, "", false)   // (v467) بطلب صاحب التطبيق: لينك يوتيوب اللي البرنامج يمسكه بيفتح في يوتيوب عادي على طول — من غير قايمة «افتحه إزاي؟»
    }

    // (v1003) بيرجّع true لو الـintent كان فيه ملف صوت/فيديو واتستهلك (حتى لو النسخ فشل — اتعرض تنبيه)،
    // وfalse لو مفيش ملف وسائط (المتصل بيكمّل على مسار النص/اللينك القديم).
    private fun handleSharedMedia(i: Intent): Boolean {
        val uris = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (list != null) uris.addAll(list)
            val cd = i.clipData
            if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
        } else {
            i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            val cd = i.clipData
            if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
        }
        var picked: Uri? = null
        var pickedKind = ""
        for (u in uris) {
            val kind = sharedMediaKind(u)
            if (kind.isNotEmpty()) {
                picked = u
                pickedKind = kind
                break
            }
        }
        if (picked == null) return false
        i.action = null   // ما يتكررش لو الأكتيفيتي اتعمل لها إعادة إنشاء
        val uri = picked
        val kind = pickedKind
        Thread {
            try {
                val name = sharedDisplayName(uri)
                val ext = name.substringAfterLast('.', "").lowercase()
                val safeExt = when {
                    ext.isNotBlank() && ext.length <= 5 && ext.all { it.isLetterOrDigit() } -> "." + ext
                    kind == "audio" -> ".mp3"
                    else -> ".mp4"
                }
                val inbox = File(filesDir, "studio_inbox").also { it.mkdirs() }
                val base = TrLib.safeName(name.substringBeforeLast('.', name))
                var target = File(inbox, base + safeExt)
                var n = 2
                while (target.exists()) {
                    target = File(inbox, base + "_" + n + safeExt)
                    n++
                }
                contentResolver.openInputStream(uri)?.use { inp ->
                    target.outputStream().use { out -> inp.copyTo(out) }
                } ?: throw java.io.IOException("مقدرتش أقرا الملف المشترك")
                val title = name.substringBeforeLast('.', name).ifBlank { if (kind == "audio") "تسجيل صوتي" else "فيديو" }
                val d = Lecture.newDoc(applicationContext, kind, title, Uri.fromFile(target).toString(), "")
                // النوع بيتساب فاضي عمدًا — Lecture.start بيشغّل TxDetect ويكتشفه تلقائيًا.
                Lecture.start(applicationContext, d)
                runOnUiThread {
                    try {
                        val th = Themes.current()
                        val ui = Ui(this, th)
                        LectureUi(this, ui, th).open(d)
                    } catch (e: Throwable) {
                        LogStore.err("Main:shareOpen", e)
                        Notice.show(this, "⚠ اتحفظ الملف وبدأ التفريغ — افتحه من 🎙 الاستوديو", 3400L)
                    }
                }
            } catch (e: Throwable) {
                LogStore.err("Main:shareMedia", e)
                runOnUiThread { Notice.show(this, "⚠ مقدرتش أستقبل الملف المشترك: " + (e.message ?: "").take(80), 3400L) }
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    // نوع الوسائط من MIME الرسمي، ولو مش متاح من امتداد اسم الملف — فاضي لو مش صوت/فيديو.
    private fun sharedMediaKind(u: Uri): String {
        val mime = try { contentResolver.getType(u) ?: "" } catch (_: Throwable) { "" }
        if (mime.startsWith("audio/")) return "audio"
        if (mime.startsWith("video/")) return "video"
        val name = try {
            sharedDisplayName(u)
        } catch (_: Throwable) {
            ""
        }.lowercase()
        val audioExt = listOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".amr", ".3gp", ".wma")
        val videoExt = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".3gp", ".m4v", ".ts")
        if (audioExt.any { name.endsWith(it) }) return "audio"
        if (videoExt.any { name.endsWith(it) }) return "video"
        return ""
    }

    private fun sharedDisplayName(u: Uri): String {
        return try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
    // (v468) اتحذفت قايمة «افتحه إزاي؟» الميتة — لينكات يوتيوب بتفتح مباشرة من v467
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
            val sb = (if (MtGlass.on()) com.newtube.mobile.ui.common.GlassSlider(this) else SeekBar(this)).apply { layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR;   // (v905)
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
        if (!DeviceUi.isTv(this)) {   // (v501) وضع التلفزيون: مفيش PiP ولا تحكم سطوع من الفيديو — الصلاحيتين دول مش بيتطلبوا
        l += PermStep("overlay", "العرض فوق التطبيقات الأخرى", "عشان الترجمة تظهر تحت نافذة الـ PiP لما تخرج بره البرنامج",
            { Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this) },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 14) })
        l += PermStep("writesettings", "تعديل إعدادات النظام", "عشان التحكم في السطوع من الفيديو يشتغل",
            { Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(this) },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")), 14) })
        }
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
    fun openLink(u0: String) { openLinkAs(u0.trim()) }
    /** (v417) موزّع الروابط الواحد (Links.kt): how = auto (يتحدد من نوع اللينك) · video · video_tr · audio · web · dl — ret = الشاشة اللي يرجع لها المشغّل (browser/yt) */
    fun openLinkAs(u: String, how: String = Links.AUTO, title: String = "", ret: String = "") {
        if (u.isBlank() || isFinishing || isDestroyed) return
        val id = Links.ytId(u)
        val k = Links.kind(u)
        val web = { saveCfgFn?.invoke(); NavAnim.set(curTab, 2); startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", u)) }
        when (how) {
            Links.WEB -> web()
            Links.DL -> Links.download(this, u, title)
            Links.AUDIO -> if (id != null) playYtAudio(id, title, "", "") else openMusicLink(u, title)
            Links.VIDEO_TR -> if (id != null) playYt(id, title, true, true, 0L, null, ret) else openVideoLink(u, ret)
            Links.VIDEO -> if (id != null) playYt(id, title, false, false, 0L, null, ret) else openVideoLink(u, ret)
            else -> when {
                id != null -> playYt(id, title, false, false, 0L, null, ret)   // (v467) لينك يوتيوب بيفتح يوتيوب عادي على طول
                k == Links.Kind.AUDIO -> openMusicLink(u, title)
                k == Links.Kind.VIDEO -> openVideoLink(u, ret)
                Links.isYtPage(u) -> openYtPage(u)   // (v532) لينك قناة/قايمة يوتيوب ← يوتيوب البرنامج مش المتصفح
                else -> web()
            }
        }
    }
    /** (v568) إعادة فتح فيديو من «آخر المشغّلة»: يوتيوب ← قسم يوتيوب بالمعرّف (وفي وضع التلفزيون يتبعت للتلفزيون)؛ لينك مصطاد/مباشر ← مشغّل فيديو، عمره ما يروح للمتصفح */
    fun reopenRecentLink(r: Recent) {
        val yid = Recents.ytIdOf(r)
        if (yid != null) { openLinkAs(MtYt.watchUrl(yid), Links.VIDEO, r.title); return }
        if (PlayTarget.open(this, MediaSpec(url = r.url, title = r.title, startMs = (r.posSec * 1000).toLong(), how = Links.VIDEO), { reopenRecentLink(r) })) return
        openLinkAs(r.url, Links.VIDEO, r.title)
    }
    private fun openVideoLink(u: String, ret: String) {
        if (u.startsWith("file:", true) || u.startsWith("content:", true)) play("", Uri.parse(u), true, false, false, false, ret)
        else play(u, null, false, false, false, false, ret)
    }
    private fun openMusicLink(u: String, title: String) {
        try {
            val tr = Links.trackOf(u, title)
            if (curTab != 1) musicReturnTab = curTab
            showTabFn(1)
            MusicEngine.play(this, listOf(tr), 0)
            musicOpenFn?.invoke()
        } catch (e: Throwable) { LogStore.err("Main:linkAudio", e); Notice.show(this, "ما قدرتش أشغّل الصوت ده", 2800L) }
    }
    /** (v417) لينك جاي من شاشة تانية (المتصفح/الملفات) عن طريق Links.open */
    private fun handleLinkIntent(i: Intent?) {
        val u = i?.getStringExtra(Links.EX_URL) ?: return
        val how = i.getStringExtra(Links.EX_HOW) ?: Links.AUTO
        val t = i.getStringExtra(Links.EX_TITLE) ?: ""
        val r = i.getStringExtra(Links.EX_RET) ?: ""
        val lref = i.getStringExtra("link_ref") ?: ""; val lua = i.getStringExtra("link_ua") ?: ""; val lck = i.getStringExtra("link_cookie") ?: ""
        val lpage = i.getStringExtra("link_page") ?: ""   // (v820) لينك صفحة الفيديو (من المتصفح)
        i.removeExtra(Links.EX_URL); i.removeExtra("link_ref"); i.removeExtra("link_ua"); i.removeExtra("link_cookie"); i.removeExtra("link_page")
        val lstart = i.getLongExtra("link_startms", 0L); i.removeExtra("link_startms")   // (v543 م4) يوتيوب من الموبايل ومعاه مكان البداية
        // (v564) فيديو جاي من موبايل (tvcast=1): شغّله على طول بكاش مؤقت (من غير ترجمة/مفاتيح)
        if (CastCache.isCast(u)) {
            try { musicUi?.pause() } catch (_: Throwable) {}
            startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).putExtra("url", u).putExtra("title", t).putExtra("nosub", true).putExtra("landscape", true).putExtra("fresh", false))
            return
        }
        // (v680) باقي المسار المحلي (على الموبايل) — اتحوّل لدالة عشان نافذة «تلفزيون ولا موبايل» تقدر تكمّل بيه
        fun goLocal() {
        val lyt = Links.ytId(u)
        if (lstart > 0L && lyt != null && (how == Links.VIDEO || how == Links.VIDEO_TR || how == Links.AUTO)) {
            val tr = how == Links.VIDEO_TR
            playYt(lyt, t, tr, tr, lstart, null, r)
            return
        }
        if ((lref.isNotEmpty() || lua.isNotEmpty() || lck.isNotEmpty()) && Links.ytId(u) == null && (how == Links.VIDEO || how == Links.AUTO)) {
            // (v533) فيديو مصطاد من المتصفح (لينك مباشر محتاج Referer/Cookie): نشغّله فرجة بنفس الهيدرز
            try { musicUi?.pause() } catch (_: Throwable) {}
            startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).putExtra("url", u).putExtra("ref", lref).putExtra("ua", lua).putExtra("cookie", lck).putExtra("title", t).putExtra("nosub", true).putExtra("landscape", false).putExtra("fresh", false).putExtra("ask", false).apply { if (lpage.isNotEmpty()) putExtra("page", lpage) })
            return
        }
        openLinkAs(u, how, t, r)
        }
        // (v547 م4) «وضع التلفزيون» شغّال: يوتيوب/فيديو (حتى المصطاد بهيدرز) يتبعت للتلفزيون بدل ما يشتغل على الموبايل. false = الوضع مقفول فنكمّل محلي زي ما كان
        if (how == Links.VIDEO || how == Links.VIDEO_TR || how == Links.AUTO) {
            val kk = Links.kind(u)
            if (kk == Links.Kind.YT || kk == Links.Kind.VIDEO || lref.isNotEmpty() || lua.isNotEmpty() || lck.isNotEmpty()) {
                val hd = HashMap<String, String>()
                if (lref.isNotEmpty()) hd["ref"] = lref
                if (lua.isNotEmpty()) hd["ua"] = lua
                if (lck.isNotEmpty()) hd["cookie"] = lck
                if (PlayTarget.open(this, MediaSpec(url = u, headers = hd, title = t, startMs = lstart, how = if (how == Links.AUTO) "" else how), { goLocal() })) return
            }
        }
        goLocal()
    }
    fun play(u: String, uri: Uri?, landscape: Boolean = uri != null, fresh: Boolean = false, noSub: Boolean = false, ask: Boolean = false, ret: String = "") {
        try { musicUi?.pause() } catch (_: Throwable) {}
        if (u.isBlank() && uri == null) return
        if (noSub) startPlayerNow(u, uri, landscape, fresh, true, false, ret)   // فرجة من غير ترجمة: مش محتاج مفتاح
        else ensureKeys { startPlayerNow(u, uri, landscape, fresh, false, ask, ret) }
    }
    /** (v117) دخول فوري للمشغّل: مفيش قراءة لملف الترجمة هنا خالص — المشغّل بيحمّلها هو في الخلفية واللودنج بيظهر جواه */
    fun playChecked(v: VideoItem) {
        // (v547 م3) فيديو من مكتبة التلفزيون: يتشغّل على التلفزيون والموبايل يبقى متحكّم (PlayTarget → PlayerActivity remote=true)
        if (v.uri.startsWith(LibraryCore.SCHEME, true)) {
            if (!PlayTarget.force(this, MediaSpec(uri = v.uri, title = v.title))) Notice.show(this, "📺 ما اتبعتش للتلفزيون", 3000L)
            return
        }
        // (v564) «وضع التلفزيون» شغّال والفيديو من الموبايل نفسه: يتبعت للتلفزيون (يسحبه على دفعات ويمسح الكاش لما تخرج)
        if (PlayTarget.enabled() && PlayTarget.isLocal(v.uri) && !DeviceUi.isTv(this)) {
            // (v680) ملف على الموبايل + وضع التلفزيون: نافذة «تلفزيون ولا موبايل» (عشان اللي شغّال على التلفزيون ما يتقطعش)
            PlayTarget.askWhere(this, v.title,
                { if (!PlayTarget.force(this, MediaSpec(uri = v.uri, title = v.title))) Notice.show(this, "📺 ما اتبعتش للتلفزيون", 3000L) },
                { play("", Uri.parse(v.uri), v.landscape, false, false, !BgJobs.isActive(v.videoId)) })
            return
        }
        play("", Uri.parse(v.uri), v.landscape, false, false, !BgJobs.isActive(v.videoId))
    }
    fun startPlayerNow(u: String, uri: Uri?, landscape: Boolean = uri != null, fresh: Boolean = false, noSub: Boolean = false, ask: Boolean = false, ret: String = "") {
        val pi = Intent(this, PlayerActivity::class.java).pipTask(this).apply {
            if (uri != null) { data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } else putExtra("url", u)
            putExtra("landscape", landscape); putExtra("fresh", fresh); putExtra("nosub", noSub); putExtra("ask", ask)
            if (ret.isNotEmpty()) putExtra("ret", ret)   // (v417) الرجوع من المشغّل لنفس الشاشة (متصفح/يوتيوب)
            // لو فتحنا الإعدادات من المشغّل واخترنا فيديو جديد: امسح المشغّل القديم بدل ما يفضل تحته
            if (intent?.getBooleanExtra("from_player", false) == true) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        // (v978) فتح مباشر في MeeTube من غير PlayerActivity الوسيطة (تاسك زيادة = بطء + رعشة سودا)
        if (intent?.getBooleanExtra("from_player", false) == true || !MtPlay.fastOpen(this, pi)) startActivity(pi)
    }
    // ===== (v215) نسخة احتياطية كاملة: حفظ في ملف / استرجاع من ملف / استرجاع آخر نسخة تلقائية =====
    /** (v469) D4 بحث شامل موحّد: بيفتّش في مكتبة الفيديو + الموسيقى + سجل يوتيوب + أقسام الإعدادات، والدوسة على أي نتيجة بتقفز لها على طول */
    private fun globalSearch(q0: String) {
        val thx = Themes.current()
        val dens = resources.displayMetrics.density
        fun px(n: Int) = (n * dens).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(px(14), px(12), px(14), px(10)); setBackgroundColor(thx.bg) }
        val et = android.widget.EditText(this).apply { hint = "🔎 دوّر في المكتبة وسجل يوتيوب والإعدادات"; textSize = 15f; setSingleLine(); setText(q0); setTextColor(thx.text); setHintTextColor(thx.muted); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = android.widget.ScrollView(this).apply { addView(box); isVerticalScrollBarEnabled = false }
        col.addView(et, LinearLayout.LayoutParams(-1, -2)); col.addView(sv, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55f).toInt()).apply { topMargin = px(8) })
        val d = GDialog(this); d.setContentView(col)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(thx.bg))
        fun head(t: String) = android.widget.TextView(this).apply { text = t; textSize = 12.5f; setTextColor(thx.muted); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(px(8), px(14), px(8), px(4)) }
        fun row(t: String, sub: String, f: () -> Unit) = android.widget.TextView(this).apply { text = if (sub.isEmpty()) t else t + "\n" + sub; textSize = 14f; setTextColor(thx.text); setPadding(px(14), px(11), px(14), px(11)); setOnClickListener { try { d.dismiss() } catch (_: Throwable) {}; f() } }
        fun run(q: String) {
            box.removeAllViews()
            if (q.length < 2) { box.addView(android.widget.TextView(this).apply { text = "اكتب حرفين على الأقل"; textSize = 13f; setTextColor(thx.muted); setPadding(px(8), px(22), px(8), px(8)) }); return }
            try { val vs = libUi?.searchHits(q)?.take(8) ?: emptyList(); if (vs.isNotEmpty()) { box.addView(head("🎬 فيديوهات المكتبة")); for (v in vs) box.addView(row("🎬 " + v.title, v.folderName) { libUi?.playItem(v) }) } } catch (_: Throwable) {}
            try { val ms = musicUi?.lib?.searchHits(q)?.take(8) ?: emptyList(); if (ms.isNotEmpty()) { box.addView(head("🎵 الموسيقى")); for (v in ms) box.addView(row("🎵 " + v.title, v.folderName) { showTabFn(1); musicUi?.lib?.playItem(v) }) } } catch (_: Throwable) {}
            try { val hs = (PubHistory.list("v", "youtube") + PubHistory.list("a", "youtube")).filter { it.title.contains(q, true) }.take(8); if (hs.isNotEmpty()) { box.addView(head("🕘 سجل يوتيوب")); for (hh in hs) box.addView(row("▶ " + hh.title, "من سجل المشاهدة") { playYt(hh.ref, hh.title, false) }) } } catch (_: Throwable) {}
            val ss = SettingsTabs.sections.filter { it.label.contains(q, true) || it.sub.contains(q, true) }
            if (ss.isNotEmpty()) { box.addView(head("⚙️ أقسام الإعدادات")); for (sec in ss) box.addView(row(sec.label, sec.sub) { settingsShow?.invoke(sec.id) }) }
            if (box.childCount == 0) box.addView(android.widget.TextView(this).apply { text = "مفيش نتائج — جرّب كلمة تانية"; textSize = 13f; setTextColor(thx.muted); setPadding(px(8), px(22), px(8), px(8)) })
            box.addView(row("🌐 ابحث في المتصفح عن «" + q.take(30) + "»", "") { webSearch(q) })
        }
        val hh2 = android.os.Handler(android.os.Looper.getMainLooper()); val go = Runnable { run(et.text.toString().trim()) }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { hh2.removeCallbacks(go); hh2.postDelayed(go, 450L) }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, dd: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, dd: Int) {}
        })
        run(q0.trim())
        d.show()
    }

    // (v469) D5 نسخة شاملة: كل الإعدادات (Backup.toJson) + ملف سجل المشاهدة/الاستئناف في JSON واحد — المفضلة والتقييمات وقوائم التشغيل والإحصائيات جوّه الإعدادات أصلًا فبتتسافر معاها
    // (v1003) + قسم "studio": دوكيومنتات الاستوديو + intel + series/courses + فهرس الأصوات + excl/redact + علامة pinSet بس.
    private fun fullJson(): String = try { org.json.JSONObject().put("kind", "subtitler-full").put("ver", 469).put("cfg", Backup.toJson()).put("recent", try { File(filesDir, "recent.json").readText() } catch (_: Throwable) { "" }).put("studio", studioBackupSection()).toString() } catch (e: Throwable) { LogStore.err("Main:fullJson", e); Backup.toJson() }

    // (v1003) قسم الاستوديو في النسخة الشاملة — ملفات صغيرة نصية بس، من غير أي صوت:
    //  • صوت البصمات (voices/*.wav) مبيتسافرش — فهرس index.json بس (المفاتيح/الأسماء).
    //  • الـPIN مبيتسافرش أبدًا (لا الهاش ولا الملح) — pinSet علامة بس، والاسترجاع مبيسترجعوش.
    //  • السقوف: أي دوكيومنت JSON أكبر من 2MB بيتخطى (وبتتسجل ids بتاعته)، ولو المجموع عدّى 8MB
    //    بيتاخد أحدث 50 دوكيومنت بس (وبتتسجل truncated). النسخة التلقائية (Backup) لسه إعدادات بس.
    private fun studioBackupSection(): org.json.JSONObject {
        val root = org.json.JSONObject()
        val docs = org.json.JSONObject()
        val intel = org.json.JSONObject()
        val skipped = org.json.JSONArray()
        var totalBytes = 0L
        val byCreated = ArrayList<Triple<String, Long, String>>()
        try {
            val lecDir = File(filesDir, "lectures")
            for (f in lecDir.listFiles().orEmpty()) {
                if (!f.name.endsWith(".json")) continue
                val text = try { f.readText(Charsets.UTF_8) } catch (_: Throwable) { continue }
                if (f.name.endsWith(".intel.json")) {
                    intel.put(f.name.removeSuffix(".intel.json"), text)
                    continue
                }
                val id = f.name.removeSuffix(".json")
                if (text.length > 2 * 1024 * 1024) {
                    skipped.put(id)
                    continue
                }
                val created = try { org.json.JSONObject(text).optLong("created", f.lastModified()) } catch (_: Throwable) { f.lastModified() }
                byCreated.add(Triple(id, created, text))
                totalBytes += text.length
            }
        } catch (e: Throwable) {
            LogStore.err("Main:studioBackup", e)
        }
        var truncated = false
        val sorted = byCreated.sortedByDescending { it.second }
        val take = if (totalBytes > 8 * 1024 * 1024) 50 else sorted.size
        for ((idx, triple) in sorted.withIndex()) {
            val id = triple.first
            val text = triple.third
            if (idx >= take) {
                truncated = true
                skipped.put(id)
                continue
            }
            docs.put(id, text)
        }
        root.put("docs", docs)
        root.put("intel", intel)
        root.put("series", readSmallText(File(filesDir, "studio_series.json")))
        root.put("courses", readSmallText(File(filesDir, "studio_courses.json")))
        root.put("voicesIndex", readSmallText(File(File(filesDir, "voices"), "index.json")))
        root.put("excl", Cfg.str(StudioIntel.EXCL_KEY, ""))
        root.put("redact", StudioPrivacy.isRedactOn(this))
        root.put("pinSet", StudioPrivacy.isPinSet(this))
        root.put("skipped", skipped)
        root.put("truncated", truncated)
        return root
    }

    private fun readSmallText(f: File): String {
        return try {
            if (!f.exists() || f.length() > 1024 * 1024) "" else f.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    // (v1003) استرجاع قسم الاستوديو — بيرجّع ملخص عدّاد للوج (واتسجّل في LogStore):
    // دوكيومنت محلي أحدث (created أكبر) مبيتكتبش فوقه أبدًا. series/courses/index بيتكتبوا بس لو
    // الملف المحلي مش موجود (مش بيتدمجوا — الدمج ممكن يضيّع إدخالات أجدد). الـPIN مبيسترجعش.
    private fun restoreStudioSection(o: org.json.JSONObject): String {
        var restoredDocs = 0
        var keptLocal = 0
        var restoredIntel = 0
        var restoredShared = 0
        try {
            val lecDir = File(filesDir, "lectures").also { it.mkdirs() }
            val docs = o.optJSONObject("docs")
            if (docs != null) {
                val ks = docs.keys()
                while (ks.hasNext()) {
                    val id = ks.next()
                    val text = docs.optString(id, "")
                    if (text.isBlank()) continue
                    val incoming = try { org.json.JSONObject(text).optLong("created", 0L) } catch (_: Throwable) { 0L }
                    val local = File(lecDir, "$id.json")
                    if (local.exists()) {
                        val localCreated = try { org.json.JSONObject(local.readText(Charsets.UTF_8)).optLong("created", 0L) } catch (_: Throwable) { 0L }
                        if (localCreated > incoming) {
                            keptLocal++
                            continue
                        }
                    }
                    try {
                        local.writeText(text, Charsets.UTF_8)
                        restoredDocs++
                    } catch (e: Throwable) {
                        LogStore.err("Main:studioRestoreDoc", e)
                    }
                }
            }
            val intel = o.optJSONObject("intel")
            if (intel != null) {
                val ks = intel.keys()
                while (ks.hasNext()) {
                    val id = ks.next()
                    val text = intel.optString(id, "")
                    if (text.isBlank()) continue
                    try {
                        File(lecDir, "$id.intel.json").writeText(text, Charsets.UTF_8)
                        restoredIntel++
                    } catch (e: Throwable) {
                        LogStore.err("Main:studioRestoreIntel", e)
                    }
                }
            }
            restoredShared += restoreSharedStudioFile(File(filesDir, "studio_series.json"), o.optString("series", ""))
            restoredShared += restoreSharedStudioFile(File(filesDir, "studio_courses.json"), o.optString("courses", ""))
            restoredShared += restoreSharedStudioFile(File(File(filesDir, "voices").also { it.mkdirs() }, "index.json"), o.optString("voicesIndex", ""))
            val excl = o.optString("excl", "")
            if (excl.isNotBlank()) Cfg.put(StudioIntel.EXCL_KEY, excl)
            if (o.has("redact")) StudioPrivacy.setRedactOn(this, o.optBoolean("redact", false))
            if (o.optBoolean("pinSet", false)) LogStore.add("🔐 النسخة فيها PIN استوديو — مبيسترجعش، اعمل PIN جديد من الاستوديو")
        } catch (e: Throwable) {
            LogStore.err("Main:studioRestore", e)
        }
        val summary = "استوديو: $restoredDocs دوكيومنت، $restoredIntel intel، $restoredShared ملف مشترك، $keptLocal محلي أحدث اتساب"
        LogStore.add("📥 " + summary)
        return summary
    }

    private fun restoreSharedStudioFile(f: File, text: String): Int {
        if (text.isBlank()) return 0
        return try {
            if (f.exists() && f.length() > 0) 0 else {
                f.writeText(text, Charsets.UTF_8)
                1
            }
        } catch (e: Throwable) {
            LogStore.err("Main:studioRestoreShared", e)
            0
        }
    }
    private fun fullToFile() {
        try { saveCfgFn?.invoke() } catch (_: Throwable) {}
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "Subtitler-full-$stamp.json") }, 63) }
        catch (e: Throwable) { LogStore.err("Main:fullSave", e); Notice.show(this, "⚠ مش قادر أفتح اختيار الملف", 2600L) }
    }
    private fun fullFromFile() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 64) }
        catch (e: Throwable) { LogStore.err("Main:fullOpen", e); Notice.show(this, "⚠ مش قادر أفتح اختيار الملف", 2600L) }
    }
    private fun restoreFull(txt: String) {
        val o = try { org.json.JSONObject(txt) } catch (_: Throwable) { null }
        if (o == null || !o.has("cfg")) { restoreBackup(txt); return }   // ملف نسخة عادية (مش شاملة) — بيترجع بالمسار العادي
        try { val rec = o.optString("recent", ""); if (rec.isNotEmpty()) File(filesDir, "recent.json").writeText(rec) } catch (e: Throwable) { LogStore.err("Main:fullRecent", e) }
        if (o.has("studio")) {
            try { restoreStudioSection(o.optJSONObject("studio") ?: org.json.JSONObject()) } catch (e: Throwable) { LogStore.err("Main:fullStudio", e) }
        }
        restoreBackup(o.optString("cfg", ""))
    }

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
        if (r == ModelsUi.REQ) { ModelsUi.onResult(this, c, d); return }   // (v696) اختيار ملف موديل
        if (r == LectureUi.REQ) { LectureUi.onResult(this, c, d); return }   // (v991) اختيار فيديو/صوت للتفريغ
        // (v995) اختيار فيديو/صوت للاستوديو
        if (r == StudioUi.REQ) { StudioUi.onResult(this, c, d); return }
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
        if (r == 63) {   // (v469) D5 حفظ النسخة الشاملة
            if (c == RESULT_OK) d?.data?.let { u ->
                try { contentResolver.openOutputStream(u, "wt")?.use { oo -> oo.write(fullJson().toByteArray(Charsets.UTF_8)) }; Notice.show(this, "✅ اتحفظت النسخة الشاملة — احتفظ بيها في مكان آمن", 3400L) }
                catch (e: Throwable) { LogStore.err("Main:fullWrite", e); Notice.show(this, "⚠ فشل الحفظ", 2600L) }
            }
            return
        }
        if (r == 64) {   // (v469) D5 استرجاع النسخة الشاملة
            if (c == RESULT_OK) d?.data?.let { u ->
                try { restoreFull(contentResolver.openInputStream(u)?.use { ii -> String(ii.readBytes(), Charsets.UTF_8) } ?: "") }
                catch (e: Throwable) { LogStore.err("Main:fullRead", e); Notice.show(this, "⚠ مش قادر أقرا الملف", 2800L) }
            }
            return
        }
        if (r == 52) { if (c == RESULT_OK) d?.data?.let { onToolPicked(it) }; return }
        if (r == 53) { if (c == RESULT_OK) d?.data?.let { onSrtPicked(it) } else pendingSrc = null; return }
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
    /** (v606) PiP من صفحة يوتيوب: لو خرجت (Home/Recents) والفيديو شغّال في صفحة المشاهدة (بورتريه) أو الشريط السفلي، يتسلّم للمشغّل الكامل ويدخل PiP تلقائيًا */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
    }
    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis(); WebMute.pauseAll(); if (ytLoadLaunched) { ytLoadLaunched = false } }   // (v339) المشغّل ظهر فوقنا: شيل صفحة اللودينج
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
        if (hasFocus && !fromPlayer) window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) checkClipboard({ playClip(it) }, { a, c -> clipAction(a, c) }) }, 900)
    }
    /** لمسة على دايرة الكليبورد: فيديو مباشر يشتغل على طول، يوتيوب يتفتح عن طريق يوتيوب، وغير كده شاشة الصيد */
    /** فيديو من سجل «المصطادة»: يفتح بنفس الرابط والهيدرز ويكمّل من آخر مكان وقفت عنده. روابط يوتيوب بتنتهي — فبنفتح صفحة الفيديو */
    fun playWeb(w: WebVid) {
        if (w.kind == "YT") { if (w.id.startsWith("yt:")) playYt(w.id.removePrefix("yt:"), w.title, true, false) else if (w.ref.isNotEmpty()) openLink(w.ref) else Notice.show(this, ("رابط يوتيوب انتهت صلاحيته — افتحه من المتصفح تاني").toString(), 3600L); return }
        // (v543 م4) وضع التلفزيون: الفيديو المصطاد بيتبعت للتلفزيون بنفس الهيدرز (Referer/UA/Cookie)
        if (PlayTarget.open(this, MediaSpec(url = w.url, headers = mapOf("ref" to w.ref, "ua" to w.ua, "cookie" to w.cookie), title = w.title, how = Links.VIDEO), { playWeb(w) })) return
        ensureKeys {
            startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply {
                putExtra("url", w.url); putExtra("ref", w.ref); putExtra("ua", w.ua); putExtra("cookie", w.cookie)
                if (w.page.isNotEmpty()) putExtra("page", w.page)   // (v750)
                putExtra("title", w.title); putExtra("autotr", false)   // (v157) الترجمة بإيدك بس: زرار «ترجمة» في المشغّل
            })
        }
    }
    /** (v117) دخول مباشر لفيديو يوتيوب من معرّفه: بيجيب أحسن لينك فيه صوت وصورة ويفتح المشغّل (لو فشل بيفتح صفحته في المتصفح) */
    fun playYt(id: String, title: String, translate: Boolean = true, auto: Boolean = translate, startMs: Long = 0L, handoff: YtPick? = null, retOv: String = "") {
        // (v543 م4) وضع التلفزيون شغّال: يوتيوب بيتبعت للتلفزيون على طول (ytid + مكان البداية) وبعدين شاشة الريموت — من غير سؤال
        if ((handoff == null || PlayTarget.enabled()) && id.length == 11 && PlayTarget.open(this, MediaSpec(ytid = id, title = title, startMs = startMs, how = if (translate) Links.VIDEO_TR else Links.VIDEO), if (handoff == null) ({ playYt(id, title, translate, auto, startMs, null, retOv) }) else null)) {
            return
        }
        if (!translate && handoff == null && startMs <= 0L && askTv(id, title, retOv)) return   // (v532) «تشغّله على التلفزيون؟»
        if (!translate && handoff == null && MeeTubeLink.playYt(this, id, startMs)) return   // (v887) يوتيوب العادي ← مشغّل MeeTube
        if (false && translate && handoff == null && !DeviceUi.isTv(this) && id.length == 11) {   // (v924) مقفول: يوتيوب بترجمة = مشغّل Joyboy القديم · (v890) يوتيوب بترجمة ← مشغّل MeeTube + محرك الترجمة فوقه (نفس مفتاح جيميناي المطلوب)
            ensureKeys { if (!MeeTubeLink.playYt(this, id, startMs, auto)) ytStart(id, title, translate, auto, startMs, handoff, retOv) }
            return
        }
        playYtHere(id, title, translate, auto, startMs, handoff, retOv)
    }
    private fun playYtHere(id: String, title: String, translate: Boolean = true, auto: Boolean = translate, startMs: Long = 0L, handoff: YtPick? = null, retOv: String = "") {
        // (v339) مفتاح الترجمة الأول (لو ناقص بيسأل) وبعدها ندخل صفحة المشغّل فورًا
        if (translate) ensureKeys { ytStart(id, title, translate, auto, startMs, handoff, retOv) } else ytStart(id, title, translate, auto, startMs, handoff, retOv)
    }
    /** (v532) على الموبايل ومعاه تلفزيون مقترن: قبل ما فيديو يوتيوب يشتغل يسأل «تشغّله على التلفزيون؟». true = اتعرضت القايمة (والتشغيل هيكمّل من اختيارك) */
    private fun askTv(id: String, title: String, ret: String): Boolean {
        if (isFinishing || isDestroyed || fromPlayer || id.length != 11) return false
        if (DeviceUi.isTv(this)) return false
        val paired = try { RemoteClient.init(this); RemoteClient.lastTv() != null } catch (_: Throwable) { false }
        if (!paired) return false
        if (!ytTvMode) return false   // (v554) مفيش سؤال «تشغّله على التلفزيون؟»: بره وضع التلفزيون = الموبايل، جوّاه PlayTarget بيبعت لوحده
        val b = GAlert(this).setTitle("📺 تشغّله على التلفزيون؟").setMessage(title.ifBlank { "فيديو يوتيوب" })
            .setPositiveButton("📺 على التلفزيون") { _, _ -> sendYtToTv(id, title, ret) }
            .setNegativeButton("📱 هنا في الموبايل") { _, _ -> playYtHere(id, title, false, false, 0L, null, ret) }
        if (!ytTvMode) b.setNeutralButton("📱 هنا ومتسألنيش تاني") { _, _ -> Cfg.put("tv_ask", "0"); playYtHere(id, title, false, false, 0L, null, ret) }
        b.show()
        return true
    }
    /** (v532) يبعت الفيديو للتلفزيون ويفتح شاشة تحكم مشغّل الفيديو على الموبايل (جودة · لغة · تقديم · صوت…) */
    private fun sendYtToTv(id: String, title: String, ret: String) {
        Notice.show(this, "📺 بوصّل بالتلفزيون…", 2500L)
        Thread {
            val er: String? = try {
                if (!RemoteClient.ensureConnected(this, 8000L)) "ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else RemoteClient.openLink(YtHistory.watchUrl(id), title)
            } catch (e: Throwable) { LogStore.err("Main:ytToTv", e); "حصلت مشكلة" }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (er == null) {
                    ytTvSent = true
                    Notice.show(this, "📺 اتفتح على التلفزيون", 2800L)   // (v554) مفيش شاشة ريموت
                } else {
                    GAlert(this).setTitle("⚠ ما اتبعتش للتلفزيون").setMessage(er)
                        .setPositiveButton("📱 شغّله هنا") { _, _ -> playYtHere(id, title, false, false, 0L, null, ret) }
                        .setNegativeButton("إغلاق", null).show()
                }
            }
        }.also { it.isDaemon = true }.start()
    }
    /** (v532) لينك يوتيوب مش فيديو (قناة / قايمة تشغيل / بحث): بيتفتح في تاب يوتيوب بتاع البرنامج مش في المتصفح */
    private fun openYtPage(u: String) {
        try { MeeTubeLink.openUrl(this, u) } catch (e: Throwable) { LogStore.err("Main:ytPage", e) }   // (v888) قناة/قايمة/بحث ← MeeTube
    }
    private fun cancelYtLoad() { ytLoadTok++; ytLoadLaunched = false }
    /** (v339) بنفتح «صفحة المشغّل» فورًا (لودينج جواها) وبنجيب رابط الفيديو في الخلفية؛ لو فشل بنقفلها ونطلّع الدايلوج ونفضل في يوتيوب */
    /** (v351) بنفتح المشغّل الكامل فورًا؛ هو اللي بيجيب رابط الفيديو ويحمّل جواه (من غير شاشة اللودينج السودا القديمة) */
    private fun ytStart(id: String, title: String, translate: Boolean, auto: Boolean, startMs: Long = 0L, handoff: YtPick? = null, retOv: String = "") {
        if (id.length != 11) { ytStartLegacy(id, title, translate, auto, startMs); return }
        ytLoadLaunched = true
        val ret = if (retOv.isNotEmpty()) retOv else if (curTab == 4) "yt" else ""
        if (handoff != null) {   // (v406) المشغّل المصغّر اتسلّم (YtHandoff): المشغّل الكامل بياخده شغّال من غير ما يجيب الرابط ويحمّل من الأول
            startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply {
                putExtra("handoff", true)
                putExtra("url", handoff.url); putExtra("aurl", handoff.audio ?: ""); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", handoff.ua)
                if (handoff.qlist.isNotEmpty()) putExtra("qlist", handoff.qlist)
                putExtra("title", title.ifBlank { handoff.title }); putExtra("ytid", id)
                putExtra("nosub", !translate); putExtra("autotr", auto); putExtra("ret", ret); if (startMs > 0L) putExtra("startms", startMs)
            })
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply {
            putExtra("ytpend", true); putExtra("ytid", id); putExtra("title", title)
            putExtra("ref", "https://www.youtube.com/")
            putExtra("nosub", !translate); putExtra("autotr", auto); putExtra("ret", ret); if (startMs > 0L) putExtra("startms", startMs)
        })
    }
    private fun ytStartLegacy(id: String, title: String, translate: Boolean, auto: Boolean, startMs: Long = 0L) {
        val tok = ++ytLoadTok; ytLoadLaunched = false
        val ret = if (curTab == 4) "yt" else ""   // الرجوع من المشغّل لصفحة يوتيوب بس لو إحنا جينا منها (مش من المكتبة)
        Thread {
            val best = try { YtExtract.fetchPick(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null }   // (v889) من محرك MeeTube
            val np: YtPick? = null
            runOnUiThread {
                if (tok != ytLoadTok) return@runOnUiThread   // اتلغى (رجوع/✕) أو طلب أحدث
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (best == null && np != null) {
                    ytLoadLaunched = true
                    startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply {
                        putExtra("url", np.url); putExtra("aurl", np.audio ?: ""); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", np.ua)
                        if (np.qlist.isNotEmpty()) putExtra("qlist", np.qlist)
                        putExtra("title", title.ifBlank { np.title }); putExtra("ytid", id)
                        putExtra("nosub", !translate); putExtra("autotr", auto); putExtra("ret", ret); if (startMs > 0L) putExtra("startms", startMs)
                    })
                    return@runOnUiThread
                }
                if (best == null) {
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
                startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply {
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
        try { YtHistory.add(this, id, title, channel, true) } catch (_: Throwable) {}   // (v407) سجل يوتيوب المنفصل
        // (v339) بنفتح مشغّل الموسيقى الكامل فورًا (لودينج مكان الألبوم) وبنفتكر إحنا جينا منين عشان نرجع لنفس المكان لما نقفله
        // (v341) quiet = تالي/إعادة محاولة: الأغنية الحالية تكمّل وإحنا ماشيين، من غير ما نغيّر الشاشة ولا نفتح لودينج
        val tok = ++ytAudTok
        // (v429) جاي من صفحة المشاهدة: رابط الصوت جاهز عندها — نشغّله فورًا من نفس الموضع من غير جلب جديد
        var pu = preUrl; var pua = preUa; var sm = startMs
        // (v351) رابط الصوت جاهز من مشغّل الفيديو: نشغّله فورًا (نفس المكان) من غير لودينج ولا جلب جديد
        if (pu != null && !quiet) {
            try {
                if (curTab != 1) musicReturnTab = curTab
                showTabFn(1)
                val tr = YtAudio.track(id, title, channel, thumb, YtPick(pu!!, null, pua.ifBlank { "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36" }, title, 0, "", preDur))
                MusicEngine.play(this, listOf(tr), 0, false, sm)
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
            val a = try { MtYt.audio(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null }   // (v889) من محرك MeeTube
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
    /** (v716) فيديو (مش صوت) من غير جودة محدّدة ← بيسأل عن الجودة الأول (قايمة الجودات الحقيقية للفيديو). maxH >= 0 = الجودة اتحدّدت (0 = الأعلى) */
    fun ytDownload(n: NpItem, audioOnly: Boolean, maxH: Int = -1) {
        if (!audioOnly && maxH < 0) { ytDownloadAsk(n); return }
        val app = applicationContext
        val nm = n.title.ifBlank { n.id } + (if (!audioOnly && maxH > 0) " [${maxH}p]" else "")
        TaskCenter.add(app, "download", (if (audioOnly) "🎧 " else "⬇ ") + nm) { t ->
            t.msg = "بجيب الرابط…"; TaskCenter.changed()
            val p = (if (audioOnly) MtYt.audio(n.id) else MtYt.pick(n.id, maxH.coerceAtLeast(0))) ?: throw java.io.IOException("ما قدرتش أجيب رابط التحميل")
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
            Downloader.takeLastOut()?.let { t.outUri = it.first; t.mime = it.second }   // (v472) دوسة على المهمة الخالصة تفتح الفيديو
        }
        Notice.show(this, ("⬇ اتضاف للتحميل — تابعه من تبويب «المهام»").toString(), 3000L)
    }
    /** (v716) بيجيب جودات الفيديو ويعرض قايمة الاختيار؛ لو جودة واحدة بس أو الجلب فشل بيحمّل على طول */
    fun ytDownloadAsk(n: NpItem) {
        Notice.show(this, "⏳ بجيب الجودات…", 1500L)
        Thread {
            val opts = try { DlQuality.fromQlist(MtYt.video(n.id, 0)?.qlist ?: "") } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (opts.size <= 1) ytDownload(n, false, opts.firstOrNull()?.h ?: 0)
                else DlQuality.show(this, "⬇ اختار جودة التحميل", opts, { ytDownload(n, true) }) { o -> ytDownload(n, false, o.h) }
            }
        }.apply { isDaemon = true }.start()
    }
    /** (v472) دوسة على مهمة خالصة في صفحة المهام = افتح الفيديو/الصوت اللي طلع منها في مشغّل البرنامج */
    fun openTaskOutput(t: TaskItem) {
        val u: Uri? = t.outUri ?: (if (t.outPath.startsWith("/") && java.io.File(t.outPath).isFile) Uri.fromFile(java.io.File(t.outPath)) else if (t.state != 2 && t.partPath.isNotEmpty() && java.io.File(t.partPath).isFile) Uri.fromFile(java.io.File(t.partPath)) else null)
        if (u == null) { Notice.show(this, ("الملف مش متاح للفتح من هنا — موجود في: " + t.outPath.take(60)).toString(), 3400L); return }
        play("", u, !t.mime.startsWith("audio"))
    }
    fun playClip(c: ClipVideo) {
        fun go(u: String, ref: String, ua: String) {
            if (ref.isEmpty() && ua.isEmpty()) { play(u, null); return }
            ensureKeys { startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply { putExtra("url", u); putExtra("ref", ref); putExtra("ua", ua) }) }
        }
        if (c.playUrl.isNotEmpty()) { go(c.playUrl, c.ref, c.ua); return }
        val id = YtExtract.videoId(c.url)
        if (id == null) { openLink(c.url); return }   // مش يوتيوب ومفيش رابط مباشر: شاشة الصيد في المتصفح
        playYt(id, c.title.takeIf { it.isNotBlank() && it != c.url } ?: "", true, false)   // (v358) لينك يوتيوب: بيتفتح عن طريق يوتيوب
    }
    /** (v708) أزرار دواير الكليبورد بعد ما الفيديو يتلقط في الخلفية: tv أحمر · dl أزرق · tr أخضر (شاهد وترجم) · watch أصفر (شاهد بس). أي نوع فيديو (يوتيوب/فيسبوك/لينك مباشر/صفحة) */
    fun clipAction(act: String, c: ClipVideo) {
        if (isFinishing || isDestroyed) return
        val yid = if (c.kind == "YT") YtExtract.videoId(c.url) else null
        val title = c.title.takeIf { it.isNotBlank() && it != c.url } ?: ""
        val direct = c.playUrl.isNotEmpty()
        when (act) {
            "watch" -> if (yid != null) playYt(yid, title, false, false) else if (direct) clipPlay(c, true) else openLink(c.url)
            "tr" -> if (yid != null) playYt(yid, title, true, true) else if (direct) clipPlay(c, false) else openLink(c.url)
            "tv" -> {
                val ok = when {
                    yid != null -> PlayTarget.force(this, MediaSpec(ytid = yid, title = title, how = Links.VIDEO))
                    direct -> PlayTarget.force(this, MediaSpec(url = c.playUrl, headers = mapOf("ref" to c.ref, "ua" to c.ua, "cookie" to (try { android.webkit.CookieManager.getInstance().getCookie(c.playUrl) ?: "" } catch (_: Throwable) { "" })), title = title, how = Links.VIDEO))
                    else -> { Links.sendToTv(this, c.url); true }
                }
                if (!ok) Notice.show(this, "📺 ما اتبعتش للتلفزيون", 3000L)
            }
            "dl" -> when {
                yid != null -> ytDownload(NpItem(yid, title.ifBlank { yid }, "", "", "https://i.ytimg.com/vi/$yid/hqdefault.jpg", 0L, -1L, "", 0, c.url), false)
                direct -> clipDownload(c, title)
                else -> openLink(c.url)
            }
        }
    }
    /** (v708) تشغيل فيديو مباشر من دايرة الكليبورد بنفس الهيدرز (Referer/UA)؛ noSub = شاهد بس من غير ترجمة */
    private fun clipPlay(c: ClipVideo, noSub: Boolean) {
        val u = c.playUrl
        if (c.ref.isEmpty() && c.ua.isEmpty()) { play(u, null, false, false, noSub); return }
        val go = { startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply { putExtra("url", u); putExtra("ref", c.ref); putExtra("ua", c.ua); putExtra("title", c.title); putExtra("nosub", noSub); putExtra("autotr", !noSub) }) }
        if (noSub) go() else ensureKeys { go() }
    }
    /** (v708) تحميل فيديو مباشر (mp4/HLS) — مهمة في تبويب «المهام» زي تحميل المتصفح */
    private fun clipHeaders(c: ClipVideo): HashMap<String, String> {
        val hd = HashMap<String, String>()
        if (c.ua.isNotEmpty()) hd["User-Agent"] = c.ua
        if (c.ref.isNotEmpty()) hd["Referer"] = c.ref
        val ck = try { android.webkit.CookieManager.getInstance().getCookie(c.playUrl) ?: "" } catch (_: Throwable) { "" }
        if (ck.isNotEmpty()) hd["Cookie"] = ck
        return hd
    }
    /** (v716) HLS: بيجيب الجودات من الماستر ويسأل تحمّل بأنهي واحدة (جودة واحدة بس ← بيحمّل على طول) */
    private fun clipAskHls(c: ClipVideo, title: String) {
        Notice.show(this, "⏳ بجيب الجودات…", 1500L)
        val hd = clipHeaders(c)
        Thread {
            val vs = try { Downloader.variants(c.playUrl, hd) } catch (_: Throwable) { emptyList() }
            val sz = if (vs.size > 1) try { Downloader.variantBytes(vs, hd) } catch (_: Throwable) { emptyList() } else emptyList()   // (v975) الحجم الكلي لكل جودة
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (vs.size <= 1) clipDownload(c, title, vs.firstOrNull()?.url ?: c.playUrl, vs.firstOrNull()?.h ?: 0)
                else DlQuality.show(this, "⬇ اختار جودة التحميل",
                    vs.mapIndexed { i, v -> DlQuality.Opt(Downloader.variantLabel(v, sz.getOrElse(i) { 0L }), v.h, v.url, null, true) }, null) { o -> clipDownload(c, title, o.url, o.h) }
            }
        }.apply { isDaemon = true }.start()
    }
    private fun clipDownload(c: ClipVideo, title: String, u0: String = "", h0: Int = -1) {
        val hls = c.kind == "HLS" || c.playUrl.contains(".m3u8", true)
        if (hls && u0.isEmpty()) { clipAskHls(c, title); return }
        val app = applicationContext
        val nm = title.ifBlank { Sniff.nameOf(c.playUrl) }.take(90) + (if (h0 > 0) " [${h0}p]" else "")
        val hd = clipHeaders(c)
        val job = Downloader.Job(nm, u0.ifEmpty { c.playUrl }, null, hls, maxOf(h0, 0), hd)
        TaskCenter.add(app, "download", "⬇ " + job.name) { t ->
            job.ctl.cancelled = false; job.ctl.paused = false
            job.ctl.ext = { t.cancelled }
            t.onCancel = { job.ctl.cancelled = true }
            t.onPause = { p -> job.ctl.paused = p }
            val res = Downloader.run(app, job) { what, pct -> t.pct = pct.coerceIn(0, 99); t.msg = what; TaskCenter.changed() }
            if (!res.first) throw java.io.IOException(res.second)
            t.msg = ""; t.outPath = res.second
        }
        Notice.show(this, "⬇ اتضاف للتحميل — تابعه من تبويب «المهام»", 3000L)
    }
    /** (v350) عدّاد الفيديوهات الجديدة على أيقونة يوتيوب */
    /** (v355) فتح قسم من إعدادات البرنامج من أي شاشة (مثلًا ⚙ يوتيوب) */
    var settingsShow: ((String?) -> Unit)? = null
    var pageSettingsShow: ((String) -> Unit)? = null   // (v820) إعدادات صفحة معيّنة (PageSettings.VIDEOS/MUSIC/TASKS)
    var settingsBack: (() -> Unit)? = null   // (v370) رجوع جوه صفحة الإعدادات (قسم ← القايمة ← الصفحة السابقة)
    fun openSettingsTab(t: String) { try { settingsShow?.invoke(t) } catch (_: Throwable) {} }
    /** (v407) صفحة «سجل يوتيوب» المنفصلة — بتتفتح من فولدر المصطادة ومن الإعدادات ▸ يوتيوب (نفس البيانات) */
    private var saveFn: () -> Unit = {}   // (v407) save() دالة محلية في onCreate — بنمسكها هنا
    fun showYtHistory() { MeeTubeLink.open(this) }   // (v888) سجل يوتيوب جوه MeeTube
    private fun tvToLauncher(show: String) {
        try { startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tv_show", show)); finish() }
        catch (_: Throwable) { showTabFn(0) }
    }
    fun updateYtBadge() { navUi?.badge(4, 0) }
    /** (v475) الشريط السفلي: وانت في يوتيوب بيعرض (الرئيسية · الاشتراكات · أنا)؛ اللايت ترتيبه ثابت (الرئيسية · الاشتراكات · محلي · أنا) */
    fun syncNav() {
        tvBar?.select(if (curTab == 4) 2 else if (curTab == 0) 4 else -1)   // (v516)
        val n = navUi ?: return
        val liteApp = packageName.endsWith(".lite")
        n.mode(curTab == 4)
        mediaSw?.show(if (!liteApp && !fromPlayer && !tvShellOn && (curTab == 0 || curTab == 1)) curTab else -1)   // (v654)
        n.set(if (liteApp && curTab == 7) BottomNav.YT_ME else curTab)
    }
    fun openSettingsRoot() { try { settingsShow?.invoke(null) } catch (_: Throwable) {} }
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
                    Cfg.ensure(this)
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
        }, 3500L)
    }
    /** (v554) دخول/خروج وضع التلفزيون (الزرار العايم): بدّل مكتبة الفيديوهات بين الموبايل والتلفزيون */
    private var tvModeSeen = false
    private val tvModeSync: () -> Unit = {
        if (!isDestroyed && !isFinishing && !DeviceUi.isTv(this)) {
            val on = PlayTarget.enabled()
            try { libUi?.updateSourceBar() } catch (_: Throwable) {}   // (v565)
            if (on != tvModeSeen) {
                tvModeSeen = on
                if (on && libBackend !== TvLibrary) libSourceFn?.invoke(true)
                else if (!on && libBackend === TvLibrary) libSourceFn?.invoke(false)
            }
        }
    }
    override fun onPause() { try { DataCapsule.detach(this) } catch (_: Throwable) {}; super.onPause() }   // (v799) كبسولة الداتا في كل التبويبات
    override fun onResume() {
        super.onResume()
        TabNav.pendingMain?.let { t -> TabNav.pendingMain = null; try { onNewIntent(Intent().putExtra("tab", t)) } catch (e: Throwable) { LogStore.err("Main:pendingTab", e) } }   // (v973) REORDER_TO_FRONT من غير onNewIntent
        try { DataCapsule.attach(this) } catch (_: Throwable) {}   // (v799)
        try { tvModeSeen = PlayTarget.enabled(); TvModeFab.listeners.addIfAbsent(tvModeSync) } catch (_: Throwable) {}
        if (!fromPlayer) { updateYtBadge(); ytBackgroundSync() }
        // الرجوع من المشغّل أو من إعدادات الإذن: حدّث العرض (تقدم الترجمة) أو أعد الفحص
        if (!fromPlayer) CrashLog.showIfAny(this)
        if (!fromPlayer) ClipOverlay.ensure(this)   // (v735) مراقب الكليبورد بإذن العرض فوق التطبيقات (بيشتغل لوحده أول ما الإذن يتفعّل)
        if (!fromPlayer) ModelWatch.maybeCheck(this)   // (v141) فحص يومي لموديل flash-lite أحدث
        if (!fromPlayer) GhCi.onForeground(this)   // (v856) فشل ورك فلو GitHub ← إشعار + تحميل اللوج · تحديث جاهز ← تثبيت
        // المخفي بيتقفل تاني لو التطبيق قعد في الخلفية أكتر من دقيقة
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) { libUi?.relock(); musicUi?.lib?.relock() }
        stoppedAt = 0L
        if (!fromPlayer && libStarted) libUi?.let { if (it.hasData) it.render() else scanFn() }
    }
    override fun onDestroy() { try { AppWall.detach(this) } catch (_: Throwable) {}; try { TvModeFab.listeners.remove(tvModeSync) } catch (_: Throwable) {}; try { KeysSync.reload = null } catch (_: Throwable) {}; try { dockUi?.detach() } catch (_: Throwable) {}; try { stripUi?.detach() } catch (_: Throwable) {}; try { tasksFabUi?.detach() } catch (_: Throwable) {}; if (!fromPlayer) TaskCenter.listener = null; try { tasksFabUi?.detach() } catch (_: Throwable) {}; try { musicUi?.destroy() } catch (_: Throwable) {}; super.onDestroy() }
    /** (v327) إشعار فيديو جديد: افتح تفاصيله في بوابة يوتيوب (الدالة بتتركّب أول ما بوابة يوتيوب تتبني؛ showTabFn(4) بتبنيها) */
    private fun openYtFromIntent(i: Intent?) {
        val id = i?.getStringExtra("yt_open") ?: return
        i.removeExtra("yt_open")
        try { playYt(id, "", false) } catch (e: Throwable) { LogStore.err("Main:ytOpen", e) }   // (v888) إشعار فيديو جديد ← مباشرة للمشغّل (MeeTube أو Joyboy)
    }
    /** (v117) الرجوع من المتصفح بشريط البوابات: بيفتح البوابة اللي اخترتها */
    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        // (v985) استعادة PiP من أيقونة اللانشر — نفس فحص onCreate فوق (ضغطة الأيقونة
        // والمشغّل مثبّت في PiP لازم تكبّر المشغّل بدل ما تفتح الرئيسية).
        if (i?.action == Intent.ACTION_MAIN && i?.hasCategory(Intent.CATEGORY_LAUNCHER) == true) {
            try {
                if (com.newtube.mobile.MobileMainApplication.booted()?.restorePictureInPictureFromLauncher(this) == true) {
                    finish()
                    return
                }
            } catch (_: Throwable) {}
        }
        TabNav.pendingMain = null   // (v973) اتسلّم فعلًا
        if (DeviceUi.isTv(this) && !fromPlayer) fromTv = true   // (v482)
        // (v939) إعادة توجيه باردة كانت بتحصل في onCreate بس — intent جديد من غير وجهة صريحة (لا تبويب ولا yt_open ولا رابط)
        // كان بيتفتح على واجهة الموبايل بدل TvActivity: نفس شرط البداية بالظبط، بيتطبق هنا كمان قبل أي توزيع تبويب
        if (DeviceUi.isTv(this) && !fromPlayer && i?.getBooleanExtra("force_phone", false) != true && i?.getStringExtra("tab") == null &&
            i?.action != Intent.ACTION_VIEW && i?.action != Intent.ACTION_SEND && i?.getStringExtra("yt_open") == null &&
            i?.getStringExtra(Links.EX_URL) == null) {
            startActivity(Intent(this, TvActivity::class.java)); finish(); return
        }
        i?.getStringExtra("ytaudio")?.let { id ->   // (v343) 🎧 من مشغّل الفيديو: كمّل كصوت من نفس المكان
            try {
                playYtAudio(id, i?.getStringExtra("ytaudtitle") ?: "", "", "", false, (i?.getLongExtra("ytaudpos", 0L) ?: 0L).toInt(),
                    i?.getStringExtra("ytaudurl"), i?.getStringExtra("ytaudua") ?: "", i?.getLongExtra("ytauddur", 0L) ?: 0L)
            } catch (e: Throwable) { LogStore.err("Main:ytaudio", e); Notice.show(this, ("ما قدرتش أحوّل لصوت — جرّب تاني").toString(), 3000L) }
        }
        handleExternal(i); handleLinkIntent(i)   // (v345)
        handleClipBubbleIntent(i)   // (v729) أزرار الدايرة العايمة (LinkWatch) من فوق أي تطبيق
        // (v547 م3) «📚 مكتبة التلفزيون» من شاشة الريموت
        if (i?.getBooleanExtra("lib_tv", false) == true) { i.removeExtra("lib_tv"); libSourceFn?.invoke(true) }
        if (i?.getBooleanExtra("lib_phone", false) == true) { i.removeExtra("lib_phone"); libSourceFn?.invoke(false) }   // (v691) «📱 مكتبة الموبايل» من صفحة المشغّل
        if (i?.getBooleanExtra("fullmusic", false) == true) musicOpenFn?.invoke()   // (v344) ⤢ من المشغّل المصغّر في المتصفح/الملفات
        if (i?.getStringExtra("tab") != null) ytTvMode = i?.getBooleanExtra("tv_target", false) == true   // (v532)
        if (i?.getStringExtra("reopen_tab") != null) { val rt = i.getStringExtra("reopen_tab"); i.removeExtra("reopen_tab"); settingsShow?.invoke(rt) }   // (v820) المتصفح ← قسم من الإعدادات الشاملة (مثلًا 🔗 الاصطياد)
        when (i?.getStringExtra("tab")) { "videos" -> if (DeviceUi.isTv(this) && !fromPlayer) tvToLauncher("videos") else showTabFn(0); "music" -> showTabFn(1); "tasks" -> showTabFn(5); "settings" -> settingsShow?.invoke(null); "youtube" -> { if (i?.getStringExtra("yt_open") == null) { MeeTubeLink.open(this) }; openYtFromIntent(i) } }
    }
    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean { TvKill.watch(this, ev); if (TvFocus.handleKey(this, ev)) return true; return super.dispatchKeyEvent(ev) }   // (v477) Menu = دوسة طويلة
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!fromPlayer && ytTvMode && curTab == 4) { ytTvMode = false }   // (v603) الريموت القديم اتمسح: مفيش «رجوع للريموت»، بيكمّل الرجوع العادي

        if (!fromPlayer && curTab == 7) { settingsBack?.invoke(); return }   // (v370)
        if (!fromPlayer && curTab == 5) { showTabFn(if (tabBeforeTasks == 5) 0 else tabBeforeTasks); return }   // (v324) صفحة المهام ← للصفحة اللي جيت منها
        if (!fromPlayer && curTab == 1 && musicUi?.onBack() == true) return   // (v193) يقفل مشغّل الأسطوانة/الفولدر الأول
        if (!fromPlayer && curTab != 0) { if (fromTv && DeviceUi.isTv(this) && TvFocus.goHome(this)) return; showTabFn(0); return }   // (v477) تلفزيون: Back من أي قسم = الشاشة الرئيسية
        if (!fromPlayer && fromTv && curTab == 0 && libUi?.back() != true) { try { startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() } catch (_: Throwable) { super.onBackPressed() }; return }   // (v467) زيارة من التلفزيون: رجوع من الجذر يرجّع للتلفزيون
        if (!fromPlayer && libUi?.back() == true) return
        if (!fromPlayer && !tvShellOn && !DeviceUi.isTv(this)) { moveTaskToBack(true); return }   // (v973) Main هي الجذر: من غير finish عشان متكشفش تبويب شغّال تحتها (متصفح/ملفات/يوتيوب)
        super.onBackPressed()
    }
    override fun startActivity(i: Intent?) { super.startActivity(i); try { if (tvShellOn) { NavAnim.take(this); overridePendingTransition(0, 0) } else if (!NavAnim.take(this)) overridePendingTransition(R.anim.act_enter, R.anim.act_stay) } catch (e: Exception) { LogStore.err("Main:720", e) } }
    override fun finish() { super.finish(); try { if (fromPlayer || tvShellOn) overridePendingTransition(0, 0) else overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Main:721", e) } }
}
