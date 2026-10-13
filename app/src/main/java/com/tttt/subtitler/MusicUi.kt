package com.tttt.subtitler

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

/** (v189) ملف صوت من الجهاز */
class Track(val id: Long, val uri: String, val title: String, val artist: String, val durMs: Long, val folderKey: String, val folderName: String)

/** فحص ملفات الصوت (MP3 / M4A / FLAC / OGG / WAV…) عن طريق MediaStore.
 *  (v194) كل أغنية بتتحوّل كمان لـ VideoItem عشان صفحة الموسيقى تستخدم نفس كلاس صفحة الفيديوهات (LibraryUi بوضع الصوت). */
object MusicScan {
    @Volatile var cache: List<Track>? = null
    @Volatile var items: List<VideoItem>? = null
    private val byId = java.util.concurrent.ConcurrentHashMap<Long, Track>()
    fun trackFor(v: VideoItem): Track? = byId[v.id]
    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    fun invalidate() { cache = null; items = null }

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<Track> {
        val out = ArrayList<Track>(); val vis = ArrayList<VideoItem>()
        val base: Uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.DISPLAY_NAME); add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.DURATION); add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME); add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED); add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }
        // من غير النغمات والتنبيهات
        val sel = "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND ${MediaStore.Audio.Media.IS_ALARM}=0"
        try {
            ctx.contentResolver.query(base, proj.toTypedArray(), sel, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArt = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iBuck = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iAdd = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                while (c.moveToNext()) {
                    val dur = c.getLong(iDur)
                    if (dur in 1..4999) continue   // أصوات قصيرة جدًا (إشعارات / كليكات)
                    val name = c.getString(iName) ?: continue
                    val title = (c.getString(iTitle) ?: "").ifBlank { name.substringBeforeLast('.') }
                    var artist = c.getString(iArt) ?: ""
                    if (artist == "<unknown>") artist = ""
                    val (key, fname, fpath) = VideoLib.folderInfo(c.getString(iData), if (iRel >= 0) c.getString(iRel) else null, c.getString(iBuck))
                    val id = c.getLong(iId)
                    val ov = SongId.override(id)
                    val ttl = ov?.first?.ifBlank { null } ?: title
                    val art = ov?.second?.ifBlank { null } ?: artist
                    val uri = ContentUris.withAppendedId(base, id).toString()
                    out.add(Track(id, uri, ttl, art, dur, key, fname))
                    vis.add(VideoItem(id, uri, name, key, fname, fpath, c.getLong(iSize), dur, c.getLong(iAdd), c.getLong(iMod), 0, 0, ttl, art))
                }
            }
        } catch (_: Exception) {}
        byId.clear(); out.forEach { byId[it.id] = it }
        cache = out; items = vis
        return out
    }
}

/** (v195) صفحة «🎵 الموسيقى» = نفس صفحة الفيديوهات بالظبط (LibraryUi بوضع الصوت) + شريط مشغّل زجاجي صغير تحت القايمة.
 *  دوسة على أغنية (أو على الشريط) بتفتح المشغّل الكامل: الدايرة الزجاجية (OrbView) بقوس تقدّم الأغنية، والكلمات الحيّة فوقها، وأزرار التحكم تحت.
 *  الصوت/التضخيم والتعرّف والبوستر والريفريش والكلمات الكاملة كلها في قايمة ⋮ فوق. */
class MusicUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    val root = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    var onGrant: () -> Unit = {}
    private val h = Handler(Looper.getMainLooper())

    /** نفس كلاس صفحة الفيديوهات بوضع الصوت — Main بيوصّل له الإعدادات والعمليات والبوابة */
    val lib: LibraryUi = LibraryUi(act, ui, th, { emptyMap() }, true) { v -> playFrom(v) }

    private val BLUE = 0xFF5B9DFF.toInt()
    private val C_DIM = 0xFFB4B7BF.toInt()
    private val OFF = 0x99FFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()

    // ===== أغلفة الأغاني (الحالية + اللي على الجنبين) =====
    private val artCache = android.util.LruCache<String, Bitmap>(14)
    private val artLoading = HashSet<String>()
    private val artNone = HashSet<String>()
    private var notifyNoPoster = false
    private var curUri: String? = null
    private var tintBmp: Bitmap? = null
    private var tintDone = false

    private val onEngine: () -> Unit = { val se = MusicEngine.takeStemErr(); if (se.isNotBlank()) errNote(se); paint(); refreshNow(); lib.refreshRows(); paintMini() }

    // ===== (v202) الإشعارات الكبيرة اتشالت: الأخطاء بس بتتسجّل ▲ حمرا فوق «إظهار الكلمات» (من الأحدث للأقدم) =====
    private val errLog = ArrayList<Pair<Long, String>>()
    /** (v223) رسالة صغيرة تأكيد للأوامر (كانت فاضية من v202 فكان الزرار بيشتغل من غير أي رد) */
    private fun quiet(m: String, ms: Long = 0L) { if (m.isNotBlank()) try { Notice.show(act, m, if (ms > 0L) ms else 2200L) } catch (_: Throwable) {} }
    private fun errNote(m: String) {
        errLog.add(0, Pair(System.currentTimeMillis(), m)); while (errLog.size > 60) errLog.removeAt(errLog.size - 1)
        LogStore.add("🎵 " + m)
        h.post { paintErr() }
    }
    private val errBtn = ui.text("▲", 15f, 0xFFFF3B30.toInt(), true).apply {
        gravity = Gravity.CENTER; visibility = View.GONE; setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2))
        background = ui.box(0x33FF3B30, 0x99FF3B30.toInt(), 20); setOnClickListener { errDialog() }
    }
    private fun paintErr() { errBtn.visibility = if (errLog.isEmpty()) View.GONE else View.VISIBLE; errBtn.text = "▲ " + errLog.size }
    private fun errDialog() {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        for ((tm, m) in errLog) {
            col.addView(ui.text(fmt.format(java.util.Date(tm)), 11f, th.muted), LinearLayout.LayoutParams(-1, -2))
            col.addView(ui.text(m, 13.5f, th.text).apply { setPadding(0, 0, 0, ui.dp(10)) }, LinearLayout.LayoutParams(-1, -2))
        }
        if (errLog.isEmpty()) col.addView(ui.text("مفيش أخطاء", 14f, th.muted))
        val sc = ScrollView(act).apply { addView(col) }
        GAlert(act).setTitle("▲ الأخطاء (من الأحدث للأقدم)").setView(sc)
            .setPositiveButton("تمام", null)
            .setNegativeButton("مسح") { _, _ -> errLog.clear(); paintErr() }.show()
    }

    // ===== الشريط الصغير (كبسولة زجاجية تحت القايمة، وتقدّم الأغنية كتعبئة فيها) =====
    private val nowTitle = ui.text("", 14f, Color.WHITE, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val nowSub = ui.text("", 11f, C_DIM).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val miniArt = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.box(0xFF2A2A36.toInt(), Color.TRANSPARENT, 12); clipToOutline = true }
    private val miniFill = View(act).apply { setBackgroundColor(0x40A070FF) }
    private val miniPlay = GlyphBtn(act, "play", 22)
    private val miniNext = GlyphBtn(act, "next", 20)
    private val miniBar = FrameLayout(act).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR; visibility = View.GONE
        background = ui.box(0xFF0E0E16.toInt(), 0x30FFFFFF, 30); clipToOutline = true
    }
    private var miniFillW = -1

    // ===== المشغّل الكامل =====
    private val full = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; isClickable = true }
    private val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF6A3A8A.toInt(), 0xFF2A1638.toInt(), 0xFF0A0610.toInt(), BLACK))
    private var bgColor = 0xFF6A3A8A.toInt()
    private var bgAnim: ValueAnimator? = null
    private val orb = OrbView(act).apply { accent = BLUE }
    private val fTitle = ui.text("مفيش حاجة شغّالة", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    private val fArtist = ui.text("", 12f, C_DIM).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    // (v245) قلم تعديل اسم الأغنية والفنان — جنب الاسم على طول
    private val editBtn = ui.text("✏️", 16f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; background = ui.box(0x33FFFFFF, 0x55FFFFFF, 18)
        setOnClickListener { renameSong() }
    }
    // (v251) علامات حالة التعرّف: ✓ الاسم صح · ✗ غلط · 🔄 إعادة تعرّف · وسطر «اتعرفت قبل كده»
    private fun idBtn(label: String, fill: Int, stroke: Int, onClick: () -> Unit) = ui.text(label, 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; background = ui.box(fill, stroke, 16); setOnClickListener { onClick() }
    }
    private val okBtn = idBtn("✓", 0x3328C76F, 0x8828C76F.toInt()) { confirmName() }
    private val badBtn = idBtn("✗", 0x33E5484D, 0x88E5484D.toInt()) { wrongSong() }
    private val reBtn = idBtn("🔄", 0x33FFFFFF, 0x55FFFFFF) { reIdentify() }
    /** (v270) 🤖 التعرّف التلقائي: شغّال (أخضر) / متوقف (باهت) */
    private val autoBtn: TextView = idBtn("🤖", 0x3328C76F, 0x8828C76F.toInt()) {
        SongId.setAuto(!SongId.autoOn()); paintAutoBtn()
        quiet(if (SongId.autoOn()) "🤖 التعرّف التلقائي شغّال" else "🤖 التعرّف التلقائي متوقف", 2000L)
    }
    private fun paintAutoBtn() { autoBtn.alpha = if (SongId.autoOn()) 1f else 0.4f }
    private val idState = ui.text("", 10.5f, C_DIM).apply { gravity = Gravity.CENTER; setSingleLine(); visibility = View.GONE }
    private val bShuffle = GlyphBtn(act, "shuffle", 24)
    private val bPrev = GlyphBtn(act, "prev", 28)
    private val bPlay = GlyphBtn(act, "play", 40)
    private val bNext = GlyphBtn(act, "next", 28)
    private val bRepeat = GlyphBtn(act, "repeat", 24)
    private var fullOn = false
    private val middle = FrameLayout(act)
    private val eq = EqView(act).apply { accent = BLUE }
    var onNavTint: (Int?) -> Unit = {}          // (v198) لون الشريط السفلي (البوابات) وهو المشغّل مفتوح؛ null = الشكل العادي
    private fun navCol(c: Int) = blend(c, BLACK, 0.80f)
    fun applyNavTint() { onNavTint(if (fullOn) navCol(bgColor) else null) }
    // شريط التقدم الطولي (v198): الوقت الحالي · السلايدر · مدة الأغنية
    private val seekBar = SeekBar(act).apply {
        max = 1000; layoutDirection = View.LAYOUT_DIRECTION_LTR; splitTrack = false
        progressTintList = android.content.res.ColorStateList.valueOf(BLUE)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
        thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        setPadding(ui.dp(14), 0, ui.dp(14), 0)
    }
    private val tCur = ui.text("0:00", 12f, 0xCCFFFFFF.toInt()).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; gravity = Gravity.CENTER }
    private val tTot = ui.text("0:00", 12f, 0xCCFFFFFF.toInt()).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; gravity = Gravity.CENTER }
    private var seeking = false
    private val lyrBtn = ui.text("📝 إظهار الكلمات", 11.5f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5)); background = ui.box(0x33FFFFFF, 0x55FFFFFF, 20)
        setOnClickListener { toggleLyrics() }
    }
    private var lyrFontBtn: TextView? = null
    private var srcBtn: TextView? = null
    private val volSpacer = View(act)
    private var volColView: View? = null
    private val volLp = FrameLayout.LayoutParams(ui.dp(42), ui.dp(150), Gravity.LEFT or Gravity.TOP)
    /** (v270) مكان سلايدر الصوت: تحت = نفس مكان الـspacer، وفوق = لحد أعلى الدايرة الصغيرة وقت الكلمات (متوازي مع قمتها) */
    private fun placeVol() {
        val v = volColView ?: return
        if (middle.width == 0 || volSpacer.height == 0) return
        val a = IntArray(2); val b = IntArray(2)
        volSpacer.getLocationOnScreen(a); middle.getLocationOnScreen(b)
        val x = a[0] - b[0]; val yTop = a[1] - b[1]; val yBot = yTop + volSpacer.height
        var top = yTop
        if (lyrOn && orb.height > 0) {
            val rt = (orb.top + lyrShift() + (orb.ringCy + orb.ringR) - 2f * orb.ringR * LYR_SCALE).toInt()
            top = minOf(yTop, maxOf(rt, 0))
        }
        val h = yBot - top
        if (volLp.leftMargin != x || volLp.topMargin != top || volLp.height != h) { volLp.leftMargin = x; volLp.topMargin = top; volLp.height = h; v.layoutParams = volLp }
    }
    private var srcNoteKey = ""
    // ===== (v200) أدوات المشغّل: ⏱ مؤقت النوم · ⏩ السرعة · 📃 التالي =====
    private fun pillBg(on: Boolean) = if (on) ui.box(0x445B9DFF, BLUE, 15) else ui.box(0x22FFFFFF, 0x33FFFFFF, 15)
    private fun toolPill(t: String, f: () -> Unit) = ui.text(t, 11f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        background = pillBg(false); setOnClickListener { f() }
    }
    private val bSleep = toolPill("⏱ نوم") { sleepDialog() }
    private val bSpeed = toolPill("⏩ 1×") { speedDialog() }
    private val bQueue = toolPill("📃 التالي") { queueDialog() }
    private val volIcon = ui.text("🔊", 15f, Color.WHITE).apply { gravity = Gravity.CENTER }
    private val volLbl = ui.text("100%", 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setSingleLine() }
    private val volBar = VertVolBar(act, BLUE, 0x40FFFFFF).apply { max = 200; progress = 100 }   // (v230) سلايدر الصوت بقى طولي
    private var lastToolSig = ""
    private var volTouch = false
    private fun speedLabel(s: Float): String = if (s == s.toInt().toFloat()) s.toInt().toString() + "×" else String.format(java.util.Locale.US, "%.2f", s).trimEnd('0') + "×"
    private fun paintTools() {
        val left = MusicEngine.sleepLeftMs(); val sec = (left + 999L) / 1000L
        val eot = MusicEngine.sleepEndOfTrack; val sp = MusicEngine.speed
        val rest = (MusicEngine.queue.size - MusicEngine.idx - 1).coerceAtLeast(0)
        MusicEngine.syncSys()   // (v270) زراري الفوليوم ↔ السلايدر
        val vp = MusicEngine.volPct
        val sig = "$sec|$eot|$sp|$rest|$vp"
        if (sig == lastToolSig) return
        lastToolSig = sig
        bSleep.text = when { eot -> "⏱ بعد دي"; sec > 0L -> "⏱ " + VideoLib.fmtDur(left); else -> "⏱ نوم" }
        bSleep.background = pillBg(eot || sec > 0L)
        bSpeed.text = "⏩ " + speedLabel(sp); bSpeed.background = pillBg(sp != 1f)
        bQueue.text = if (rest > 0) "📃 التالي ($rest)" else "📃 التالي"
        if (!volTouch) volBar.progress = vp
        volLbl.text = "$vp%"; volLbl.setTextColor(if (vp > 100) 0xFFFF5252.toInt() else Color.WHITE)
    }
    private fun optRow(label: String, on: Boolean, f: () -> Unit) = ui.text(label, 15f, th.text, on).apply {
        gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12))
        background = ui.box(if (on) 0x445B9DFF else 0x14808080, if (on) BLUE else th.border, 12); setOnClickListener { f() }
    }
    private fun sleepDialog() {
        var dlg: android.app.Dialog? = null
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        fun add(label: String, on: Boolean, f: () -> Unit) {
            col.addView(optRow(label, on) { f(); lastToolSig = ""; paintTools(); dlg?.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        if (MusicEngine.sleepLeftMs() > 0L || MusicEngine.sleepEndOfTrack) add("🚫 إلغاء المؤقت", false) { MusicEngine.setSleep(0); quiet("المؤقت اتلغى", 1500L) }
        for (m in intArrayOf(5, 10, 15, 30, 45, 60)) add("⏱ بعد $m دقيقة", false) { MusicEngine.setSleep(m); quiet("😴 هتقف بعد $m دقيقة (بفيد هادي)", 2200L) }
        add("🎵 لما الأغنية دي تخلص", MusicEngine.sleepEndOfTrack) { MusicEngine.setSleep(-1); quiet("😴 هتقف في آخر الأغنية دي", 2200L) }
        dlg = ui.sheet(act, "⏱ مؤقت النوم", listOf<View>(col)); dlg.show()
    }
    private fun speedDialog() {
        var dlg: android.app.Dialog? = null
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        for (sp in floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
            val lbl = speedLabel(sp) + (if (sp == 1f) "  (عادي)" else "")
            col.addView(optRow(lbl, MusicEngine.speed == sp) { MusicEngine.setSpeed(sp); lastToolSig = ""; paintTools(); quiet("⏩ السرعة " + speedLabel(sp), 1300L); dlg?.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        col.addView(ui.text("الصوت بيفضل بنفس الدرجة (من غير تخين أو رفع)", 11.5f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(2), 0, 0) })
        dlg = ui.sheet(act, "⏩ سرعة التشغيل", listOf<View>(col)); dlg.show()
    }
    private fun queueDialog() {
        val q = MusicEngine.queue; val cur = MusicEngine.idx
        if (q.isEmpty() || cur !in q.indices) { quiet("مفيش حاجة شغّالة", 1500L); return }
        var dlg: android.app.Dialog? = null
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        if (MusicEngine.shuffle) col.addView(ui.text("🔀 العشوائي شغّال — الترتيب هنا مش بالضرورة اللي هيشتغل", 11.5f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(6)) })
        val from = (cur - 1).coerceAtLeast(0); val to = minOf(q.size, cur + 60)
        for (i in from until to) {
            val t = q[i]; val nm = nameOf(t); val on = i == cur
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
                background = ui.box(if (on) 0x445B9DFF else 0x14808080, if (on) BLUE else th.border, 12)
                setOnClickListener { MusicEngine.play(act, q, i); dlg?.dismiss() }
            }
            row.addView(ui.text((if (on) "▶ " else "") + nm.first, 14.5f, th.text, on).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
            if (nm.second.isNotBlank()) row.addView(ui.text(nm.second, 12f, th.muted).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        if (to < q.size) col.addView(ui.text("… و " + (q.size - to) + " أغنية كمان", 12f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
        dlg = ui.sheet(act, "📃 التالي في القايمة", listOf<View>(col)); dlg.show()
    }
    /** (v200) سحب لتحت من الشريط العلوي = قفل المشغّل الكامل */
    private fun attachSwipeDown(handle: View) {
        var y0 = 0f; var dragging = false; var vt: VelocityTracker? = null
        val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; dragging = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - y0
                    if (!dragging && dy > slop) { dragging = true; handle.parent?.requestDisallowInterceptTouchEvent(true) }
                    if (dragging) { full.translationY = dy.coerceAtLeast(0f); full.alpha = 1f - (dy / full.height.coerceAtLeast(1)).coerceIn(0f, 0.6f) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                        val dy = e.rawY - y0; val vy = vt?.yVelocity ?: 0f
                        if (e.actionMasked == MotionEvent.ACTION_UP && (dy > full.height * 0.22f || vy > 1400f)) {
                            full.animate().translationY(full.height.toFloat()).alpha(0f).setDuration(180L).withEndAction { hideFull(); full.translationY = 0f; full.alpha = 1f }.start()
                        } else full.animate().translationY(0f).alpha(1f).setDuration(160L).start()
                    }
                    vt?.recycle(); vt = null; dragging = false
                }
            }
            true
        }
    }
    private fun canEq() = act.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED
    private fun askEq() {
        try { act.requestPermissions(arrayOf("android.permission.RECORD_AUDIO"), 14) } catch (_: Throwable) {}
    }


    /** تقديم/تأخير الكلمات لأغنية واحدة (بيتحفظ لكل أغنية). + = الكلمات تظهر أبكر */
    private fun offsetDialog() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1800L); return }
        val lbl = ui.text("", 16f, th.text, true).apply { gravity = Gravity.CENTER }
        fun paintOff() { lyrOffId = t.id; lbl.text = String.format(java.util.Locale.US, "%+.1f ثانية", lyrOffMs / 1000.0) }
        lyrOffId = t.id; lyrOffMs = Cfg.int("lyoff_${t.id}", 0); paintOff()
        fun bump(ms: Int) { lyrOffMs = (lyrOffMs + ms).coerceIn(-10000, 10000); Cfg.put("lyoff_${t.id}", lyrOffMs.toString()); paintOff(); lyrIdx = -2; lyrWordKey = ""; lyricsTick() }
        fun btn(txt: String, ms: Int) = ui.text(txt, 14f, th.text, true).apply {
            gravity = Gravity.CENTER; background = ui.box(0x14808080, th.border, 12)
            setOnClickListener { if (ms == 0) { lyrOffMs = 0; Cfg.put("lyoff_${t.id}", "0"); paintOff(); lyrIdx = -2; lyrWordKey = ""; lyricsTick() } else bump(ms) }
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        for ((tx, ms) in listOf("−0.5" to -500, "−0.1" to -100, "+0.1" to 100, "+0.5" to 500))
            row.addView(btn(tx, ms), LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        col.addView(ui.text("الكلمات متأخرة عن المغني؟ دوس + (تظهر أبكر). متقدمة؟ دوس − (تظهر أتأخر). بيتحفظ للأغنية دي بس.", 12f, th.muted))
        col.addView(lbl, LinearLayout.LayoutParams(-1, ui.dp(44)))
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        col.addView(btn("↺ صفّر", 0), LinearLayout.LayoutParams(-1, ui.dp(44)).apply { topMargin = ui.dp(8) })
        GAlert(act).setTitle("⏱ تقديم / تأخير الكلمات").setView(col).setPositiveButton("تمام", null).show()
    }

    /** اكتب اسم الأغنية والفنان بإيدك (لو التعرّف غلط) — بيتحفظ وبيعيد الكلمات والبوستر */
    private fun renameSong() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1800L); return }
        val n = nameOf(t)
        val etT = ui.input("اسم الأغنية", n.first)
        val etA = ui.input("اسم الفنان", n.second)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        col.addView(ui.text("الاسم الصح بيحسّن الكلمات والبوستر. بعد الحفظ بيجيب الكلمات من الأول.", 12f, th.muted))
        col.addView(etT); col.addView(etA)
        // (v251) Generate: جيميناي يسمع مقطع من الأغنية ويكتب الاسم والفنان في الخانتين
        var gen: TextView? = null
        gen = ui.text("✨ Generate (جيميناي يسمع ويكتب الاسم)", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; minHeight = ui.dp(44); background = ui.box(BLUE, BLUE, 12)
            setOnClickListener {
                if (genBusy) return@setOnClickListener
                genBusy = true; text = "⏳ جيميناي بيسمع…"
                val start = t.durMs / 1000.0 * (0.25 + 0.4 * Math.random())
                SongId.gemini(act, t, start, emptyList()) { m, msg ->
                    act.runOnUiThread {
                        genBusy = false; gen?.text = "✨ Generate (جيميناي يسمع ويكتب الاسم)"
                        if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                        if (m == null) quiet("🔎 " + msg, 3000L) else { etT.setText(m.title); etA.setText(m.artist) }
                    }
                }
            }
        }
        col.addView(gen, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(4) })
        GAlert(act).setTitle("✏️ اسم الأغنية والفنان").setView(col)
            .setPositiveButton("💾 احفظ") { _, _ ->
                val a = etT.text.toString().trim(); val b = etA.text.toString().trim()
                if (a.isEmpty()) quiet("اكتب اسم الأغنية", 2000L) else { applyIdentified(t, SongMatch(a, b, "")); SongId.setVerified(t.id, true); paintIdState() }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    // ===== (v250) نسخ بديلة للكلمات + ترجمة عربية للسطور الأجنبية =====
    private var verBusy = false

    /** قايمة النسخ المحفوظة محليًا للأغنية: اختار نسخة تانية، أو دوّر على نسخ إضافية (جيميناي يقترح صيغ الاسم من غير Google Search) */
    private fun versionsDialog() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        var dlg: android.app.Dialog? = null
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(2), 0, ui.dp(2), 0) }
        val list = LyricsEngine.candidates(act, t)
        val picked = LyricsEngine.pickedId(act, t)
        col.addView(ui.text("نسخ الكلمات اللي اتلقت للأغنية دي (محفوظة على الجهاز). دوس على نسخة عشان تستخدمها.", 12f, th.muted).apply { setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(8)) })
        if (list.isEmpty()) col.addView(ui.text("لسه مفيش نسخ محفوظة. دوس «🔎 دوّر على نسخ تانية» تحت.", 13.5f, th.text).apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(10)) })
        for (c in list) {
            val on = c.key() == picked
            val diff = if (t.durMs > 0 && c.dur > 0) Math.round(Math.abs(c.dur - t.durMs / 1000.0)).toInt() else -1
            val label = (if (on) "✅ " else "") + c.title + (if (c.artist.isNotBlank()) " — " + c.artist else "") + "\n" +
                LyricsEngine.srcLabel(c.src) + " · " + (if (c.synced.isNotBlank()) "⏱ متزامنة" else "📄 من غير توقيت") + " · " + LyricsEngine.lineCount(t, c) + " سطر" +
                (if (diff >= 0) " · فرق المدة " + diff + "ث" else "")
            col.addView(ui.text(label, 13.5f, th.text, on).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START; minHeight = ui.dp(52); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.box(if (on) 0x445B9DFF else 0x14808080, if (on) BLUE else th.border, 12)
                setOnClickListener { dlg?.dismiss(); pickVersion(t, c) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(5) })
        }
        col.addView(ui.text("🔎 دوّر على نسخ تانية (جيميناي يقترح صيغ الاسم)", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; minHeight = ui.dp(48); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.box(BLUE, BLUE, 12)
            setOnClickListener { dlg?.dismiss(); searchMoreVersions() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(4) })
        dlg = ui.sheet(act, "🎼 نسخ الكلمات", listOf<View>(col)); dlg.show()
    }

    /** (v250) زرار التبديل: بيلف على النسخ المحفوظة من المصادر المختلفة واحدة واحدة (1/3 ← 2/3 ← 3/3 ← 1/3…) */
    private fun cycleVersion() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        val list = LyricsEngine.candidates(act, t)
        if (list.size < 2) { quiet("لقيت مصدر واحد بس — اختار «دوّر على نسخ تانية»", 2400L); versionsDialog(); return }
        val cur = list.indexOfFirst { it.key() == LyricsEngine.pickedId(act, t) }
        val nx = (cur + 1) % list.size
        pickVersion(t, list[nx], (nx + 1).toString() + "/" + list.size)
    }

    private fun pickVersion(t: Track, c: LyrCand, pos: String = "") {
        if (LyricsEngine.isRunning(t, LyricsEngine.mode())) { quiet("لسه شغّال… استنى شوية", 1500L); return }
        val r = LyricsEngine.applyCand(act, t, nameHint(t), c)
        if (r == null) { quiet("النسخة دي فاضية", 1800L); return }
        lyrKey = null; lyr = null
        if (fullOn) loadLyrics(force = true)
        try { paintSrc() } catch (_: Throwable) {}
        quiet("🎼 " + (if (pos.isNotEmpty()) "$pos · " else "") + LyricsEngine.srcLabel(c.src) + " — " + (if (c.synced.isNotBlank()) "متزامنة" else "من غير توقيت"), 2000L)
    }

    private fun searchMoreVersions() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        if (verBusy || LyricsEngine.isRunning(t, LyricsEngine.mode())) { quiet("لسه شغّال… استنى شوية", 1500L); return }
        verBusy = true
        quiet("🤖 جيميناي بيقترح صيغ تانية للاسم…", 2500L)
        LyricsEngine.moreVersions(act, t, nameHint(t), { st -> act.runOnUiThread { if (fullOn) setStatus(st) } }) { n, msg ->
            act.runOnUiThread {
                verBusy = false
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (fullOn) setStatus("")
                quiet("🎼 " + msg, 2400L)
                if (n > 0 && fullOn) versionsDialog()
            }
        }
    }

    private fun startTranslation(r: LyricsResult, t: Track) {
        if (LyricsEngine.trRunning(t)) return
        if (r.lines.none { LyricsEngine.isForeign(it.text) }) return
        val sk = t.uri + "|" + r.lines.size + "|" + r.source
        if (trSkipKey == sk) return
        trSkipKey = sk
        quiet("🌐 بترجم السطور الأجنبية…", 1800L)
        LyricsEngine.translate(act, t, r, { st -> act.runOnUiThread { if (fullOn && lyr === r) setStatus(st) } }) { ok, msg ->
            act.runOnUiThread {
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (fullOn) setStatus("")
                if (!ok) { if (msg.isNotBlank()) errNote("🌐 " + t.title + ": " + msg); return@runOnUiThread }
                if (lyr === r && trOn()) { showLyrics(r); quiet("🌐 اتضافت الترجمة ✅", 1600L) }
            }
        }
    }

    // ===== (v196) خط وحجم الكلمات =====
    private fun lsz(): Float = (Cfg.str("lyr_size", "20").trim().toIntOrNull() ?: 20).coerceIn(14, 36).toFloat()
    private fun fontOpt(): FontOpt = SubStyle.fonts.firstOrNull { it.id == Cfg.str("lyr_font", "Noto Sans Arabic") } ?: SubStyle.fonts[0]
    private val lyrTfCache = HashMap<String, Typeface>()
    private fun lyrTfFor(f: FontOpt): Typeface {
        lyrTfCache[f.id]?.let { return it }
        var tf: Typeface? = null
        f.file?.let { fn ->
            try { tf = Typeface.Builder(act.assets, "fonts/$fn").setFontVariationSettings("'wght' ${f.weight}").build() } catch (_: Throwable) {}
            if (tf == null) try { tf = Typeface.createFromAsset(act.assets, "fonts/$fn") } catch (_: Throwable) {}
        }
        val out = tf ?: Typeface.create(if (f.serif) "serif" else "sans-serif", Typeface.BOLD)
        lyrTfCache[f.id] = out
        return out
    }
    private fun lyrTf(): Typeface = lyrTfFor(fontOpt())
    private fun restyleLyrics() { val r = lyr ?: return; showLyrics(r) }
    private fun bumpSize(dlt: Int) {
        val n = (lsz().toInt() + dlt).coerceIn(14, 36)
        Cfg.put("lyr_size", n.toString()); restyleLyrics()
        quiet("حجم الكلمات: $n", 900L)
    }
    private fun fontDialog() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        val rows = ArrayList<Pair<FontOpt, TextView>>()
        var fdlg: android.app.Dialog? = null
        // (v201) الحجم جنب الخط في نفس النافذة (A− / A+ طلعوا من شريط الكلمات)
        val sizeLbl = ui.text("", 15f, th.text, true).apply { gravity = Gravity.CENTER }
        fun paintSize() { sizeLbl.text = "حجم الكلمات: " + lsz().toInt() }
        fun sizeBtn(t: String, d: Int) = ui.text(t, 18f, th.text, true).apply { gravity = Gravity.CENTER; background = ui.box(0x14808080, th.border, 12); setOnClickListener { bumpSize(d); paintSize() } }
        paintSize()
        val sizeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        sizeRow.addView(sizeBtn("A−", -2), LinearLayout.LayoutParams(ui.dp(64), ui.dp(48)))
        sizeRow.addView(sizeLbl, LinearLayout.LayoutParams(0, -2, 1f))
        sizeRow.addView(sizeBtn("A+", 2), LinearLayout.LayoutParams(ui.dp(64), ui.dp(48)))
        col.addView(sizeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        fun paintRows() { val cur = fontOpt().id; rows.forEach { (f, tv) -> tv.background = ui.box(if (f.id == cur) 0x445B9DFF else 0x14808080, if (f.id == cur) BLUE else th.border, 10) } }
        SubStyle.fonts.forEach { f ->
            val tv = ui.text(f.label + " — كلمات الأغنية", 18f, th.text).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10)); typeface = lyrTfFor(f)
                setOnClickListener { Cfg.put("lyr_font", f.id); paintRows(); restyleLyrics(); fdlg?.dismiss() }   // (v223) اختيار الخط بيقفل النافذة
            }
            rows.add(f to tv); col.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        paintRows()
        val sc = ScrollView(act).apply { addView(col) }
        fdlg = GAlert(act).setTitle("🔤 خط وحجم الكلمات").setView(sc).setPositiveButton("تمام", null).show()
    }

    // ===== الكلمات: حيّة فوق الدايرة + صفحة كاملة (مصدرها الإنترنت + توقيت) =====
    private val lyrPrevTv = ui.text("", 13f, 0x80FFFFFF.toInt()).apply { setLineSpacing(0f, 1.1f); maxLines = 2; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val lyrCurTv = ui.text("", 25f, Color.WHITE).apply { setLineSpacing(0f, 1.08f); maxLines = 5; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val lyrNextTv = ui.text("", 13f, 0x80FFFFFF.toInt()).apply { setLineSpacing(0f, 1.1f); maxLines = 2; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val liveStatus = ui.text("", 12f, 0xB3FFFFFF.toInt()).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val liveBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; minimumHeight = ui.dp(120); setPadding(ui.dp(22), ui.dp(4), ui.dp(22), ui.dp(10)) }
    private val inner = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val lyrPanel = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val lyrStatus = ui.text("", 12.5f, 0xFFD0D3DA.toInt()).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)) }
    private val lyrBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(40)) }
    private val lyrScroll = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(lyrBox) }
    private val lyrViews = ArrayList<TextView>()
    private val trViews = ArrayList<TextView?>()   // (v250) ترجمة كل سطر (أو null) — بنفس ترتيب lyrViews
    private var lyrTr: List<String>? = null        // (v250) الترجمة العربية المحمّلة للكلمات المعروضة (بنفس ترتيب السطور)
    private var trSkipKey = ""                     // (v250) كلمات جرّبنا نترجمها وخلصت/فشلت — ما نكررش تلقائي
    /** (v270) الترجمة العربية للسطور الأجنبية أوتوماتيك دايمًا (السطور العربي/المصري ما بتتترجمش) */
    private fun trOn() = true
    private var lyrOn = false
    private var lyr: LyricsResult? = null
    private var lyrKey: String? = null        // uri|mode للي معروض دلوقتي
    private var lyrIdx = -2
    private var lyrWordKey = ""
    private var liveKey = ""
    private var liveIdx = -3
    private var shownVer = -1

    private fun autoLyr() = Cfg.str("music_auto_lyr", "1") != "0"

    private var autoOpenFor = ""
    /** (v270) الكلمات جت (أو كانت محفوظة) والمشغّل مفتوح ← يفتح صفحة الكلمات لوحده (نفس دوسة «إظهار الكلمات»)؛ ولو ما جتش ما بيحصلش حاجة. مرة واحدة لكل أغنية */
    private fun maybeAutoOpen(ck: String) {
        if (!autoLyr() || !fullOn || lyrOn || autoOpenFor == ck) return
        autoOpenFor = ck
        orb.postDelayed({
            val t = MusicEngine.current
            if (t != null && fullOn && !lyrOn && !act.isDestroyed && !act.isFinishing && lyrKey == ck && (lyr?.lines?.isNotEmpty() == true)) toggleLyrics()
        }, 450L)
    }

    private fun buildLyricsPanel() {
        // (v198) صف واحد متحرك أفقيًا فيه كل الأزرار: وضعي الكلمات · التصحيح · الخط والحجم — عشان الكلمات تاخد أكبر مساحة
        val tools = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(2)) }
        fun fb(t: String, f: () -> Unit) = ui.text(t, 12f, Color.WHITE).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11)); background = ui.box(0x22FFFFFF, 0x33FFFFFF, 10); setOnClickListener { f() }
        }
        fun add(v: View) { tools.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) }) }
        // (v270) أول زرار (أقصى اليمين): ريفريش الاسم والكلمات والبوستر
        add(fb("🔄 ريفريش") { refreshAll() })
        add(ui.text("📝 جلب الكلمات", 12.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11)); background = ui.box(BLUE, BLUE, 10)
            setOnClickListener { loadLyrics(force = true, manual = true) }
        })
        add(fb("🔁 إعادة") { redoLyrics(false) })
        add(fb("❌ الكلمات غلط") { redoLyrics(true) })
        add(fb("⏱ توقيت") { offsetDialog() })
        // (v270) دوسة = المصدر اللي بعده، دوسة طويلة = قايمة النسخ + البحث عن نسخ تانية؛ n/N = رقم المصدر من إجمالي المصادر
        srcBtn = fb("🔀 المصدر") { cycleVersion() }.apply { setOnLongClickListener { versionsDialog(); true } }; add(srcBtn!!)
        val fbtn = fb("🔤 خط وحجم") { fontDialog() }; lyrFontBtn = fbtn; add(fbtn)
        add(fb("⋯ المزيد") { moreSheet() })
        val hs = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(tools) }
        lyrPanel.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        lyrPanel.addView(lyrStatus, LinearLayout.LayoutParams(-1, -2))
        lyrScroll.apply { isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(ui.dp(46)) }
        lyrPanel.addView(lyrScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        lyrPanel.setBackgroundColor(0x00000000)
    }
    private fun setStatus(t: String) {
        lyrStatus.text = t; lyrStatus.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
        liveStatus.text = t; liveStatus.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
    }
    /** مفيش كلمات حاليًا: امسح السطور الحيّة (الرسالة بتظهر في سطر الحالة) */
    private fun liveReset() {
        liveKey = ""; liveIdx = -3
        lyrPrevTv.text = ""; lyrCurTv.text = ""; lyrNextTv.text = ""
        lyrPrevTv.visibility = View.GONE; lyrCurTv.visibility = View.GONE; lyrNextTv.visibility = View.GONE
    }
    private fun bigMsg(t: String) {
        lyrBox.removeAllViews(); lyrViews.clear(); trViews.clear(); lyrTr = null; lyrIdx = -2; lyr = null
        lyrBox.addView(ui.text(t, 15f, C_DIM).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(40), ui.dp(8), ui.dp(12)) })
        liveReset()
    }
    /** (v198) المسافة اللي الدواير بتنزلها: من أسفل الحلقة لحد ما تلمس اسم الأغنية بالظبط — من غير تصغير (فمفيش قص للأغلفة اللي على الجنبين) */
    private fun lyrShift(): Float = (orb.height / 2f - orb.ringR - ui.dp(3)).coerceAtLeast(0f)
    /** فتح/قفل صفحة الكلمات (بتتفتح بزرار «إظهار الكلمات» أو من ⋮) */
    private val LYR_SCALE = 0.3f     // (v201) حجم الدواير وقت الكلمات = 30% من الأصل
    private fun toggleLyrics() {
        if (MusicEngine.current == null) { quiet("شغّل أغنية الأول وبعدين دوس على الكلمات", 2200L); return }
        if (lyrOn) { hideLyrics(); return }
        lyrOn = true; lyrIdx = -2; lyrWordKey = ""
        paintLyrBtn()
        // الدواير بنفس حجمها بتنزل لتحت لحد اسم الأغنية، والكلمات بتظهر من وراها بفيد
        val shift = lyrShift()
        orb.pivotX = orb.width / 2f; orb.pivotY = orb.ringCy + orb.ringR      // (v201) بتصغر ناحية أسفل الحلقة
        val ringTop = orb.top + shift + (orb.ringCy + orb.ringR) - 2f * orb.ringR * LYR_SCALE
        (lyrPanel.layoutParams as FrameLayout.LayoutParams).bottomMargin = (middle.height - ringTop + ui.dp(4)).toInt().coerceIn(0, (middle.height - ui.dp(140)).coerceAtLeast(0))
        lyrPanel.requestLayout()
        orb.passThrough = true
        lyrPanel.animate().cancel(); lyrPanel.alpha = 0f; lyrPanel.visibility = View.VISIBLE
        lyrPanel.animate().alpha(1f).setStartDelay(120L).setDuration(340L).start()
        placeVol()
        orb.animate().cancel(); orb.animate().translationY(shift).scaleX(LYR_SCALE).scaleY(LYR_SCALE).setDuration(520L).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        loadLyrics(manual = true)
    }
    private fun paintLyrBtn() { lyrBtn.text = if (lyrOn) "🙈 إخفاء الكلمات" else "📝 إظهار الكلمات" }
    private fun hideLyrics() {
        if (!lyrOn) return
        lyrOn = false; paintLyrBtn(); orb.passThrough = false; placeVol()
        lyrPanel.animate().cancel(); lyrPanel.animate().setStartDelay(0L).alpha(0f).setDuration(220L).withEndAction { if (!lyrOn) lyrPanel.visibility = View.GONE }.start()
        orb.animate().cancel(); orb.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(460L).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }
    /** لما المشغّل نفسه يتقفل: رجّع الدايرة والكلمات لوضعهم فورًا (الأنيميشن مابيشتغلش على view مخفي) */
    private fun snapLyrReset() {
        lyrOn = false; paintLyrBtn(); orb.passThrough = false; placeVol()
        lyrPanel.animate().cancel(); lyrPanel.visibility = View.GONE; lyrPanel.alpha = 1f
        orb.animate().cancel(); orb.translationY = 0f; orb.scaleX = 1f; orb.scaleY = 1f
    }
    private fun nameHint(t: Track): Pair<String, String>? = nameOf(t).let { if (it.first.isBlank()) null else it }

    /** يعرض الكلمات المحفوظة للأغنية والوضع الحاليين، أو يبدأ الكتابة (تلقائيًا لو الكلمات التلقائية شغّالة) — ودايمًا فيه رسالة حالة ظاهرة */
    private fun loadLyrics(force: Boolean = false, manual: Boolean = false) {
        val t = MusicEngine.current ?: run { bigMsg("شغّل أغنية الأول"); setStatus(""); return }
        val m = LyricsEngine.mode()
        val ck = t.uri + "|" + m
        if (!force && lyrKey == ck && lyr != null) return
        lyrKey = ck; lyr = null; shownVer = LyricsEngine.version
        val c = LyricsEngine.cached(act, t, m)
        if (c != null && c.lines.isNotEmpty()) {
            showLyrics(c)
            if (!c.complete && !LyricsEngine.isRunning(t, m)) { setStatus("🎧 الكلمات ناقصة — بكمّلها…"); startLyrics(t, m, false, false) }
            else if (!c.complete) setStatus("🎧 بتتكتب… (${c.done} من ${c.total} مقطع)")
            else setStatus("")
            maybeAutoOpen(ck)
            return
        }
        if (c != null && c.complete) { bigMsg("ما لقيتش كلام لأغنية دي."); setStatus("جرّب 🔁 إعادة، أو ✍ كلمات يدوي (الصق الكلمات بنفسك)"); return }
        if (!manual && !autoLyr() && !LyricsEngine.isRunning(t, m)) { bigMsg("دوس على المكان ده عشان تجيب الكلمات."); setStatus("🎤 اضغط هنا للكلمات (الكلمات التلقائية مقفولة)"); return }
        startLyrics(t, m, false, false)
    }
    private fun redoLyrics(wrong: Boolean) {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        val m = LyricsEngine.mode()
        if (LyricsEngine.isRunning(t, m)) { quiet("لسه بكتب الكلام… استنى شوية", 1800L); return }
        LyricsEngine.clear(act, t, m); lyrKey = t.uri + "|" + m
        startLyrics(t, m, wrong, true)
    }
    private fun startLyrics(t: Track, m: String, retry: Boolean, fresh: Boolean) {
        val ck = t.uri + "|" + m
        lyrKey = ck
        if (LyricsEngine.isRunning(t, m)) { setStatus("🎧 بتتكتب في الخلفية… استنى"); if (lyr == null) bigMsg("⏳ بتتكتب… هتظهر هنا أول ما تجهز"); return }
        if (lyr == null) bigMsg("📝 بجيب الكلمات…")
        setStatus("📝 بجهّز الطلب…")
        LyricsEngine.start(act, t, m, nameHint(t), retry, fresh, { stage ->
            act.runOnUiThread { if (fullOn && lyrKey == ck) setStatus(stage) }
        }) { r, msg ->
            act.runOnUiThread {
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (!fullOn || lyrKey != ck) { if (msg.isNotBlank()) errNote("🎤 " + t.title + ": " + msg); return@runOnUiThread }
                val c = LyricsEngine.cached(act, t, m)
                if (msg.isNotBlank()) errNote("🎤 " + t.title + ": " + msg)
                if (c != null && c.lines.isNotEmpty()) { showLyrics(c); setStatus(""); maybeAutoOpen(ck) }
                else { bigMsg("⚠ " + msg.ifBlank { "ما لقيتش كلام" }); setStatus("جرّب 🔁 إعادة، أو ✍ كلمات يدوي (الصق الكلمات بنفسك)") }
            }
        }
    }
    /** لو الكلمات اتحفظت منها حتة جديدة وانت فاتحها (وضع الصوت بيتكتب مقطع مقطع) */
    private fun lyricsPoll() {
        val t = MusicEngine.current ?: return
        val m = LyricsEngine.mode()
        if (lyrKey != t.uri + "|" + m || LyricsEngine.version == shownVer) return
        shownVer = LyricsEngine.version
        val c = LyricsEngine.cached(act, t, m) ?: return
        if (c.lines.isNotEmpty() && c.lines.size != (lyr?.lines?.size ?: -1)) { showLyrics(c); if (!c.complete) setStatus("🎧 اتكتب ${c.done} من ${c.total} مقطع…") }
    }
    /** (v270) عدّاد المصادر على الزرار (1/3) + إشعار مرة واحدة لما الكلمات تتلاقي من أكتر من مصدر */
    private fun paintSrc() {
        val t = MusicEngine.current ?: return
        val list = LyricsEngine.candidates(act, t)
        val cur = list.indexOfFirst { it.key() == LyricsEngine.pickedId(act, t) }
        srcBtn?.text = if (list.isEmpty()) "🔀 المصدر" else "🔀 المصدر " + (if (cur >= 0) cur + 1 else 1) + "/" + list.size
        val nk = t.uri + "|" + list.size
        if (list.size >= 2 && srcNoteKey != nk) { srcNoteKey = nk; quiet("🎼 الكلمات اتلقت من ${list.size} مصادر — دوس 🔀 للتبديل بينهم", 3200L) }
    }
    private fun showLyrics(r: LyricsResult) {
        val trk = MusicEngine.current
        try { paintSrc() } catch (_: Throwable) {}
        lyr = r; lyrBox.removeAllViews(); lyrViews.clear(); trViews.clear(); lyrIdx = -2; lyrWordKey = ""; liveKey = ""; liveIdx = -3
        lyrTr = if (trOn() && trk != null) LyricsEngine.translation(act, trk, r) else null
        val tr = lyrTr
        if (r.title.isNotBlank() || r.artist.isNotBlank())
            lyrBox.addView(ui.text("🎵 " + listOf(r.title, r.artist).filter { it.isNotBlank() }.joinToString(" — ") + (if (r.source.isNotBlank()) "  ·  " + LyricsEngine.srcLabel(r.source) else ""), 12f, C_DIM).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(14)) })
        if (!r.synced) lyrBox.addView(ui.text("📄 كلمات من غير توقيت (مش متزامنة مع الصوت)", 11f, C_DIM).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(10)) })
        r.lines.forEachIndexed { i, l ->
            val seek = View.OnClickListener { if (r.synced) MusicEngine.seekTo(((l.start - lyrOffMs / 1000.0) * 1000).toInt().coerceAtLeast(0)) }
            val tx = tr?.getOrNull(i).orEmpty()
            val tv = ui.text(l.text, lsz(), C_DIM, true).apply {
                typeface = lyrTf(); gravity = Gravity.CENTER; setPadding(0, ui.dp(9), 0, if (tx.isNotBlank()) ui.dp(1) else ui.dp(9)); alpha = 0.6f
                setOnClickListener(seek)
            }
            lyrViews.add(tv); lyrBox.addView(tv, LinearLayout.LayoutParams(-1, -2))
            // (v250) الترجمة العربية بين قوسين تحت السطر الأجنبي (السطر الأصلي بيفضل زي ما هو)
            if (tx.isNotBlank()) {
                val tt = ui.text("(" + tx + ")", (lsz() * 0.72f).coerceAtLeast(12f), C_DIM).apply {
                    gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(9)); alpha = 0.5f; setOnClickListener(seek)
                }
                trViews.add(tt); lyrBox.addView(tt, LinearLayout.LayoutParams(-1, -2))
            } else trViews.add(null)
        }
        lyricsTick()
        if (trOn() && tr == null && trk != null) startTranslation(r, trk)
    }
    private var lyrOffId = -1L
    private var lyrOffMs = 0
    private fun lyricsTick() {
        val r = lyr ?: return
        val tid = MusicEngine.current?.id ?: -1L
        if (tid != lyrOffId) { lyrOffId = tid; lyrOffMs = Cfg.int("lyoff_$tid", 0) }   // (v223) إزاحة التوقيت لكل أغنية (+ = الكلمات أبكر)
        val sec = MusicEngine.posMs / 1000.0 + lyrOffMs / 1000.0
        val i = if (r.synced) LyricsEngine.indexAt(r.lines, sec) else -1   // (v243) كلمات من غير توقيت: من غير تحديد سطر
        if (!lyrOn) return
        if (i != lyrIdx) {
            lyrViews.getOrNull(lyrIdx)?.let { v -> v.text = r.lines[lyrIdx].text; v.setTextColor(C_DIM); v.alpha = 0.6f; v.textSize = lsz() }
            trViews.getOrNull(lyrIdx)?.alpha = 0.5f
            lyrIdx = i; lyrWordKey = ""
            trViews.getOrNull(i)?.alpha = 0.95f
            lyrViews.getOrNull(i)?.let { v -> v.alpha = 1f; v.textSize = lsz() + 4f; v.post { lyrScroll.smoothScrollTo(0, (v.top + (v.height + (trViews.getOrNull(i)?.height ?: 0)) / 2 - lyrScroll.height / 2).coerceAtLeast(0)) } }
        }
        // تلوين الكلمات تدريجيًا جوه السطر الشغّال (تقريبي: وقت السطر متقسّم على كلماته بالتساوي)
        if (i >= 0) {
            val l = r.lines[i]; val v = lyrViews[i]
            val words = l.text.split(" ").filter { it.isNotEmpty() }
            val frac = ((sec - l.start) / (l.end - l.start).coerceAtLeast(0.5)).coerceIn(0.0, 1.0)
            val k = Math.ceil(frac * words.size).toInt().coerceIn(0, words.size)
            val key = "$i:$k"
            if (key != lyrWordKey) {
                lyrWordKey = key
                var chars = 0; var seen = 0
                for (w in words) { if (seen >= k) break; val at = l.text.indexOf(w, chars); chars = at + w.length; seen++ }
                val sp = SpannableString(l.text)
                sp.setSpan(ForegroundColorSpan(BLUE), 0, chars.coerceIn(0, l.text.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(ForegroundColorSpan(Color.WHITE), chars.coerceIn(0, l.text.length), l.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                v.text = sp
            }
        }
    }
    /** الكلمات الحيّة فوق الدايرة: السطر اللي قبله (باهت) · الشغّال (كبير، والكلمات اللي اتقالت أبيض) · اللي بعده (باهت) */
    private fun liveTick(r: LyricsResult, sec: Double, i: Int) {
        if (r.lines.isEmpty()) return
        val l = r.lines.getOrNull(i)
        val words = l?.text?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
        val k = if (l != null && words.isNotEmpty())
            Math.ceil(((sec - l.start) / (l.end - l.start).coerceAtLeast(0.5)).coerceIn(0.0, 1.0) * words.size).toInt().coerceIn(0, words.size) else 0
        val trTxt = if (l != null) lyrTr?.getOrNull(i).orEmpty() else ""
        val key = "${r.lines.size}:$i:$k:${if (trTxt.isBlank()) 0 else 1}"
        if (key == liveKey) return
        liveKey = key
        val prev = r.lines.getOrNull(i - 1)?.text ?: ""
        val next = (if (i < 0) r.lines.firstOrNull() else r.lines.getOrNull(i + 1))?.text ?: ""
        lyrPrevTv.text = prev; lyrPrevTv.visibility = if (prev.isEmpty()) View.GONE else View.VISIBLE
        lyrNextTv.text = next; lyrNextTv.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
        if (l == null) { lyrCurTv.text = "♪ ♪ ♪"; lyrCurTv.setTextColor(0x80FFFFFF.toInt()) }
        else {
            var chars = 0; var seen = 0
            for (w in words) { if (seen >= k) break; val at = l.text.indexOf(w, chars); chars = at + w.length; seen++ }
            val e = chars.coerceIn(0, l.text.length)
            val sp = SpannableStringBuilder(l.text)
            sp.setSpan(ForegroundColorSpan(Color.WHITE), 0, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(0x8CFFFFFF.toInt()), e, l.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (trTxt.isNotBlank()) {   // (v250) الترجمة بين قوسين تحت السطر الأجنبي
                val st = sp.length
                sp.append("\n(").append(trTxt).append(")")
                sp.setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), st, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(RelativeSizeSpan(0.62f), st, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            lyrCurTv.text = sp
        }
        lyrCurTv.visibility = View.VISIBLE
        if (i != liveIdx) { liveIdx = i; lyrCurTv.alpha = 0f; lyrCurTv.animate().alpha(1f).setDuration(220L).start() }
    }

    // ===== بناء المشغّل الكامل =====
    private fun buildFull() {
        full.background = bg
        full.setPadding(0, ui.dp(6), 0, ui.dp(14))
        // شريط علوي: ⌄ · مقبض · ⋮
        val top = FrameLayout(act)
        top.addView(GlyphBtn(act, "chev", 24).apply { setOnClickListener { hideFull() } }, FrameLayout.LayoutParams(ui.dp(56), ui.dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(View(act).apply { background = ui.box(0x59FFFFFF, Color.TRANSPARENT, 3) }, FrameLayout.LayoutParams(ui.dp(40), ui.dp(5), Gravity.CENTER))
        val dots = GlyphBtn(act, "dots", 24).apply { setOnClickListener { v -> menu(v) } }
        top.addView(dots, FrameLayout.LayoutParams(ui.dp(56), ui.dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        // (v241) 🖼↻ ريفريش البوستر: بيدوّر في iTunes ثم Deezer ثم MusicBrainz، ولو الأغنية ملهاش غلاف بيجيب صورة المغني
        val posterBtn = ui.text("🖼↻", 18f, Color.WHITE).apply { gravity = Gravity.CENTER; setOnClickListener { MusicEngine.current?.let { coverChoices(it, HashSet()) } ?: quiet("شغّل أغنية الأول", 1500L) } }
        top.addView(posterBtn, FrameLayout.LayoutParams(ui.dp(56), ui.dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(56) })
        attachSwipeDown(top)

        liveReset(); liveStatus.visibility = View.GONE

        // الدايرة
        orb.onToggle = { toggle() }
        orb.onStep = { d -> userStep(d) }
        orb.onSeek = { f -> if (MusicEngine.durMs > 0) MusicEngine.seekTo((MusicEngine.durMs.toLong() * f).toInt()) }
        orb.seekLabel = { f -> VideoLib.fmtDur((MusicEngine.durMs.toLong() * f).toLong()) }

        // الأزرار
        val ctl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(14), 0, ui.dp(14), 0) }
        bShuffle.setOnClickListener { val on = MusicEngine.toggleShuffle(); paintMode(); quiet(if (on) "🔀 العشوائي شغّال" else "➡️ العشوائي اتقفل", 1300L) }
        bRepeat.setOnClickListener { cycleMode() }
        bPrev.setOnClickListener { if (MusicEngine.current != null) orb.commit(-1) }
        bNext.setOnClickListener { if (MusicEngine.current != null) orb.commit(1) }
        bPlay.setOnClickListener { toggle() }
        ctl.addView(bShuffle, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        ctl.addView(bPrev, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        ctl.addView(bPlay, LinearLayout.LayoutParams(0, ui.dp(58), 1.3f))
        ctl.addView(bNext, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        ctl.addView(bRepeat, LinearLayout.LayoutParams(0, ui.dp(52), 1f))

        inner.clipChildren = false; middle.clipChildren = false
        // الترتيب: [الكلمات] [الدايرة] [اسم الأغنية] — والوسط (inner) بيتغطى بصفحة الكلمات الكاملة لما تتفتح
        inner.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))
        // اسم الأغنية والفنان + زرار إظهار الكلمات جنبهم
        val titleCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(fTitle, LinearLayout.LayoutParams(-1, -2)); titleCol.addView(fArtist, LinearLayout.LayoutParams(-1, -2)); titleCol.addView(idState, LinearLayout.LayoutParams(-1, -2))
        val titleRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(18), 0, ui.dp(14), 0) }
        titleRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        // صفين صغيرين جنب بعض: [✏️ 🔄] فوق و[✓ ✗] تحت
        val idGrid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val r1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val r2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        r1.addView(editBtn, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { marginEnd = ui.dp(4) })
        r1.addView(reBtn, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
        r2.addView(okBtn, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { marginEnd = ui.dp(4) })
        r2.addView(badBtn, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
        idGrid.addView(r1); idGrid.addView(r2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(4) })
        val r3 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        r3.addView(autoBtn, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { marginEnd = ui.dp(4) })
        idGrid.addView(r3, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(4) }); paintAutoBtn()
        titleRow.addView(idGrid, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        val rightCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        rightCol.addView(errBtn, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(4) })
        rightCol.addView(lyrBtn, LinearLayout.LayoutParams(-2, -2))
        titleRow.addView(rightCol, LinearLayout.LayoutParams(-2, -2))
        // (v241) اسم الأغنية + زرار الكلمات + أدوات (نوم/سرعة/التالي) في عمود، وسلايدر الصوت الطولي جنب زرار الكلمات على اليمين
        val infoCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        infoCol.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2); bottomMargin = ui.dp(2) })
        val toolsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        for (p in listOf<View>(bSleep, bSpeed, bQueue)) toolsRow.addView(p, LinearLayout.LayoutParams(0, ui.dp(30), 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
        infoCol.addView(toolsRow, LinearLayout.LayoutParams(-1, ui.dp(34)).apply { topMargin = ui.dp(2) })
        // (v230) سلايدر الصوت (0–200%) طولي على حافة الشاشة اليمين: النسبة فوق · الشريط · أيقونة الصوت تحت
        volBar.onChange = { p -> MusicEngine.setVol(p); volLbl.text = "$p%"; volLbl.setTextColor(if (p > 100) 0xFFFF5252.toInt() else Color.WHITE) }
        volBar.onTouch = { down -> volTouch = down; if (!down) lastToolSig = "" }
        val volCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(6)); background = ui.box(0x22000000, 0x22FFFFFF, 18)
        }
        volCol.addView(volLbl, LinearLayout.LayoutParams(-1, -2))
        volCol.addView(volBar, LinearLayout.LayoutParams(-1, 0, 1f))
        volCol.addView(volIcon, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        val infoRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        infoRow.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))
        // (v270) سلايدر الصوت بقى طبقة فوق الصفحة (volCol) عشان يطول لفوق لحد أعلى نقطة في دايرة الغلاف وقت الكلمات؛ هنا مكانه بس (spacer) عشان التخطيط يفضل زي ما هو
        infoRow.addView(volSpacer, LinearLayout.LayoutParams(ui.dp(42), ui.dp(150)).apply { marginStart = ui.dp(4); marginEnd = ui.dp(8) })
        volSpacer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeVol() }
        middle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeVol() }
        volColView = volCol
        inner.addView(infoRow, LinearLayout.LayoutParams(-1, -2))
        // (v198) شريط تقدّم طولي: الوقت الحالي · السلايدر (أول الأغنية وآخرها) · المدة الكلية
        val seekRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(10), 0, ui.dp(10), 0) }
        seekRow.addView(tCur, LinearLayout.LayoutParams(ui.dp(44), -2))
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, ui.dp(34), 1f))
        seekRow.addView(tTot, LinearLayout.LayoutParams(ui.dp(44), -2))
        inner.addView(seekRow, LinearLayout.LayoutParams(-1, ui.dp(34)))
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && MusicEngine.durMs > 0) tCur.text = VideoLib.fmtDur((MusicEngine.durMs.toLong() * p / 1000L))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val d = MusicEngine.durMs
                if (d > 0) MusicEngine.seekTo((d.toLong() * (sb?.progress ?: 0) / 1000L).toInt())
            }
        })
        buildLyricsPanel(); lyrPanel.visibility = View.GONE
        // الكلمات ورا الدواير: لوحة الكلمات تحت، والدايرة (inner) فوقها
        middle.addView(lyrPanel, FrameLayout.LayoutParams(-1, -1)); middle.addView(inner, FrameLayout.LayoutParams(-1, -1))
        middle.addView(volCol, volLp)   // (v270) فوق كل حاجة عشان اللمس يوصله
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(top, LinearLayout.LayoutParams(-1, ui.dp(56)))
        col.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(eq, LinearLayout.LayoutParams(-1, ui.dp(96)).apply { setMargins(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(2)) })
        col.addView(ctl, LinearLayout.LayoutParams(-1, ui.dp(60)).apply { bottomMargin = ui.dp(2) })
        full.addView(col, FrameLayout.LayoutParams(-1, -1))
        setBg(bgColor)
    }
    private fun setBg(col: Int) {
        bg.setColors(intArrayOf(col, blend(col, BLACK, 0.55f), blend(col, BLACK, 0.88f), BLACK))
        if (fullOn) onNavTint(navCol(col))
    }
    private fun animateBg(to: Int) {
        bgAnim?.cancel()
        val from = bgColor
        bgAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 450L
            addUpdateListener { bgColor = it.animatedValue as Int; setBg(bgColor) }
            start()
        }
    }
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
    /** لون الخلفية واللون المميّز للكبسولة من الغلاف الحالي */
    private fun tint(b: Bitmap?) {
        if (tintDone && b === tintBmp) return
        tintDone = true; tintBmp = b
        val col = ArtColor.dominant(b) ?: 0xFF7B4A9E.toInt()       // من غير غلاف: بنفسجي زي التصميم
        animateBg(col)
        miniFill.setBackgroundColor((col and 0x00FFFFFF) or 0x4A000000)
    }

    // ===== قايمة ⋮ =====
    private fun menu(anchor: View) { moreSheet() }
    /** (v201) «⋮ المزيد»: 3 مجموعات (الكلمات · الأغنية · المشغّل) بدل 11 بند مخلوطين — وكل أمر في مكان واحد بس */
    private fun moreSheet() {
        var dlg: android.app.Dialog? = null
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(2), 0, ui.dp(2), 0) }
        fun head(t: String) { col.addView(ui.text(t, 12.5f, th.muted, true).apply { setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(4)) }) }
        fun item(label: String, on: Boolean = false, f: () -> Unit) {
            col.addView(ui.text(label, 14.5f, th.text, on).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START; minHeight = ui.dp(48); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.box(if (on) 0x445B9DFF else 0x14808080, if (on) BLUE else th.border, 12)
                setOnClickListener { dlg?.dismiss(); f() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(5) })
        }
        head("📝 الكلمات")
        item(if (autoLyr()) "⏹ إيقاف الكلمات التلقائية" else "▶ تشغيل الكلمات التلقائية") {
            Cfg.put("music_auto_lyr", if (autoLyr()) "0" else "1")
            quiet(if (autoLyr()) "🎤 الكلمات هتتجاب تلقائيًا لما تفتح المشغّل" else "🎤 الكلمات مش هتتجاب إلا لما تدوس عليها", 2400L)
        }
        head("🎵 الأغنية")
        item("🖼 اختار الألبوم / الغلاف") { MusicEngine.current?.let { coverChoices(it, HashSet()) } ?: quiet("شغّل أغنية الأول", 1500L) }
        item("🖼 البوستر غلط") { wrongPoster() }
        item("🎧 تعرّف على أغنية شغّالة في تطبيق تاني") { try { act.startActivity(android.content.Intent(act, SongRequestActivity::class.java)) } catch (_: Throwable) {} }
        dlg = ui.sheet(act, "⋮ المزيد", listOf<View>(col)); dlg.show()
    }
    private val repeatNames = arrayOf("➡️ تشغيل بالترتيب (من غير تكرار)", "🔁 تكرار الكل", "🔂 تكرار الأغنية دي")
    private fun cycleMode() {
        val m = MusicEngine.cycleMode(); paintMode()
        quiet(repeatNames[m], 1400L)
    }
    private fun paintMode() {
        bShuffle.set("shuffle", if (MusicEngine.shuffle) BLUE else OFF)
        val m = MusicEngine.mode
        bRepeat.set(if (m == 2) "repeat1" else "repeat", if (m == 0) OFF else BLUE)
    }
    fun showFull() {
        if (MusicEngine.current == null) return
        fullOn = true; full.visibility = View.VISIBLE; onNavTint(navCol(bgColor))
        if (!canEq() && Cfg.str("eq_asked", "") != "1") {
            Cfg.put("eq_asked", "1"); quiet("📊 المؤثر الموسيقي محتاج إذن «تسجيل الصوت» (للتحليل بس، مفيش تسجيل)", 3200L); askEq()
        }
        paint(); refreshNow(); lastToolSig = ""; paintTools()
        loadLyrics()
    }
    private fun hideFull() { autoOpenFor = ""; snapLyrReset(); eq.release(); fullOn = false; full.visibility = View.GONE; onNavTint(null) }
    /** الاسم المعروض: الاسم اللي اتحفظ من التعرف لو موجود، وإلا بيانات الملف */
    private fun nameOf(t: Track): Pair<String, String> { val o = SongId.override(t.id); return (o?.first?.ifBlank { null } ?: t.title) to (o?.second?.ifBlank { null } ?: t.artist) }

    // ===== الأغلفة =====
    /** غلاف المدمج في الملف، وإلا من الإنترنت (iTunes ← Deezer ← MusicBrainz/Cover Art Archive ← صورة المغني) */
    private fun fetchArt(t: Track): Bitmap? {
        var bmp: Bitmap? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(act, Uri.parse(t.uri))
            val b = if (Cfg.str("noemb_${t.id}", "").isEmpty()) r.embeddedPicture else null
            if (b != null) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var ss = 1; while (o.outWidth / ss > 900 || o.outHeight / ss > 900) ss *= 2
                bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
            }
        } catch (_: Throwable) {} finally { try { r.release() } catch (_: Throwable) {} }
        // (v268) غلاف الألبوم اللي الموبايل نفسه مفهرسه (MediaStore: مدمج أو cover.jpg/folder.jpg في فولدر الأغنية) — أوفلاين، قبل أي نت
        if (bmp == null && android.os.Build.VERSION.SDK_INT >= 29 && Cfg.str("noemb_${t.id}", "").isEmpty()) {
            try { bmp = act.contentResolver.loadThumbnail(Uri.parse(t.uri), android.util.Size(600, 600), null) } catch (_: Throwable) {}
        }
        if (bmp == null) { val n = nameOf(t); bmp = Poster.fetchSync(act, n.first, n.second) }
        return bmp
    }
    private fun ensureArt(t: Track?) {
        if (t == null) return
        val k = t.uri
        if (artCache.get(k) != null || k in artNone || !artLoading.add(k)) return
        Thread {
            val b = try { fetchArt(t) } catch (_: Throwable) { null }
            if (b != null) try { FaceFocus.compute(b) } catch (_: Throwable) {}
            act.runOnUiThread {
                artLoading.remove(k)
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (b != null) artCache.put(k, b) else artNone.add(k)
                if (k == MusicEngine.current?.uri) {
                    if (b == null && notifyNoPoster) errNote("🖼 مفيش بوستر تاني — هيفضل شكل الدايرة (🔄 ريفريش يرجّع البحث)")
                    notifyNoPoster = false
                }
                refreshArts()
            }
        }.apply { isDaemon = true }.start()
    }
    private fun forgetArt(t: Track) { artCache.remove(t.uri); artNone.remove(t.uri); tintDone = false }
    private fun refreshArts() {
        val cur = MusicEngine.current
        val pv = MusicEngine.neighbor(-1); val nx = MusicEngine.neighbor(1)
        ensureArt(cur)
        if (fullOn) { ensureArt(pv); ensureArt(nx) }
        val cb = cur?.let { artCache.get(it.uri) }
        orb.setArts(pv?.let { artCache.get(it.uri) }, cb, nx?.let { artCache.get(it.uri) }, pv != null, nx != null)
        if (cb == null) miniArt.setImageResource(R.drawable.no_poster) else miniArt.setImageBitmap(cb)
        tint(cb)
    }

    // ===== التشغيل =====
    /** دوسة على أغنية: طابور التشغيل = القايمة المعروضة بترتيبها (فولدر/بحث/كل الأغاني) */
    private fun playFrom(v: VideoItem) {
        val q = lib.shownVideos().ifEmpty { listOf(v) }
        val tracks = q.mapNotNull { MusicScan.trackFor(it) }
        val i = tracks.indexOfFirst { it.id == v.id }
        if (i < 0) { errNote("الأغنية دي مش موجودة في الفحص — دوس 🔄"); return }
        MusicEngine.play(act, tracks, i); showFull()
    }
    private fun playFolder(f: FolderItem) {
        val tracks = f.videos.sortedWith { a, b -> VideoLib.natural(a.name, b.name) }.mapNotNull { MusicScan.trackFor(it) }
        if (tracks.isEmpty()) return
        MusicEngine.play(act, tracks, 0); showFull()
    }
    private fun step(d: Int) { MusicEngine.step(d) }
    /** تالي/سابق من الدايرة (سحب أو زرار): بيرجّع true لو الأغنية اتغيّرت فعلًا (السابق بعد 3 ثواني بيرجّع لأول الأغنية بس) */
    private fun userStep(d: Int): Boolean {
        val before = MusicEngine.current?.uri
        step(d)
        val a = MusicEngine.current?.uri
        return a != null && a != before
    }
    private fun toggle() { MusicEngine.toggle() }
    private fun paint() {
        val pl = MusicEngine.isPlaying
        val g = if (pl) "pause" else "play"
        bPlay.set(g); miniPlay.set(g); orb.playing = pl; eq.playing = pl
        paintMode()
    }
    private fun paintMini() { miniBar.visibility = if (MusicEngine.current == null) View.GONE else View.VISIBLE }
    private fun updateMiniFill(frac: Float) {
        val w = (miniBar.width * frac.coerceIn(0f, 1f)).toInt()
        if (Math.abs(w - miniFillW) < 2) return
        miniFillW = w
        val lp = miniFill.layoutParams as FrameLayout.LayoutParams
        lp.width = w; miniFill.layoutParams = lp
    }
    private fun refreshNow() {
        val t = MusicEngine.current
        val nm = t?.let { nameOf(it) }
        nowTitle.text = nm?.first ?: ""; nowSub.text = nm?.second ?: ""
        fTitle.text = nm?.first ?: "مفيش حاجة شغّالة"; fArtist.text = nm?.second ?: ""
        orb.initial = (nm?.first ?: "♪").trim().take(1).ifEmpty { "♪" }
        if (t?.uri != curUri) {
            curUri = t?.uri
            if (t != null) { artNone.remove(t.uri); tintDone = false; autoIdentify(t) }
            if (t == null) { orb.progress = 0f; orb.timeText = "0:00"; miniFillW = -1; updateMiniFill(0f); liveReset() }
        }
        refreshArts(); paintIdState()
        // أغنية اتغيّرت والمشغّل مفتوح → حمّل كلماتها
        if (fullOn && t != null && lyrKey != t.uri + "|" + LyricsEngine.mode()) loadLyrics()
    }
    private fun tick() {
        if (MusicEngine.ready) {
            val d = MusicEngine.durMs; val p = MusicEngine.posMs
            if (d > 0) {
                val f = p.toFloat() / d
                orb.progress = f; orb.timeText = VideoLib.fmtDur(p.toLong())
                updateMiniFill(f)
                if (fullOn) {
                    if (!seeking) { seekBar.progress = (f * 1000f).toInt().coerceIn(0, 1000); tCur.text = VideoLib.fmtDur(p.toLong()) }
                    tTot.text = VideoLib.fmtDur(d.toLong())
                }
            }
            paint()
            if (fullOn) { lyricsTick(); lyricsPoll(); eq.bind(MusicEngine.sessionId, canEq()) }
        }
        if (fullOn) paintTools()
        h.postDelayed({ tick() }, if (fullOn) 120L else 300L)
    }

    // ===== الفحص (نفس طريقة الفيديوهات: كاش فوري + فحص في الخلفية) =====
    private var started = false
    fun ensureLoaded() { if (!started) { started = true; load() } }
    fun load() {
        if (!MusicScan.hasPermission(act)) { lib.showNoPermission(); return }
        val c = MusicScan.items
        if (c != null) lib.showVideos(c) else if (!lib.hasData) lib.showScanning()
        Thread {
            val res = try { MusicScan.scan(act); MusicScan.items ?: emptyList() } catch (_: Exception) { emptyList<VideoItem>() }
            act.runOnUiThread { if (!act.isDestroyed && !act.isFinishing) lib.showVideos(res) }
        }.apply { isDaemon = true }.start()
    }
    /** ريفريش (الزرار فوق أو السحب): يمسح الكاش ويفحص من الأول */
    fun reload(full: Boolean = false) {
        MusicScan.invalidate(); started = true
        if (full) { Thumbs.clear(); lib.showScanning() }
        load()
    }
    // ===== (v192) التعرف على الأغنية =====
    private var idBusy = false
    private var genBusy = false
    private val autoBusy = HashSet<Long>()
    private fun askToken(then: () -> Unit) {
        val et = android.widget.EditText(act).apply { hint = "توكن AudD"; setSingleLine(); setText(SongId.token()); setTextColor(th.text); setHintTextColor(th.muted) }
        GAlert(act).setTitle("🔎 توكن خدمة التعرف (AudD)")
            .setMessage("سجّل في audd.io وخد التوكن وحطه هنا (مرة واحدة). الخدمة ليها حد مجاني وبعده بتتحاسب، والمقطع بيتبعت لسيرفراتهم.")
            .setView(et)
            .setPositiveButton("حفظ") { _, _ -> if (et.text.toString().isNotBlank()) { SongId.saveToken(et.text.toString()); then() } }
            .setNegativeButton("إلغاء", null).show()
    }
    /** (v269) 🔎 تعرّف: بيجيب لحد 4 مرشحين وإنت بتختار الصح (➕ خيار آخر = مرشحين جداد من غير ما يتكرروا) ثم بتختار الألبوم/الغلاف */
    private fun identify(avoid: List<String> = emptyList()) {
        val t = MusicEngine.current
        if (t == null) { quiet("شغّل الأغنية الأول وبعدين دوس 🔎", 2400L); return }
        if (idBusy) { quiet("لسه بدوّر…", 1500L); return }
        idBusy = true; paintIdState()
        quiet("🔎 المفاتيح بتسمع أجزاء المقطع مع بعض…", 2500L)
        // لو الأغنية شغّالة ناخد من مكان التشغيل، وإلا من نص الأغنية
        val start = if (MusicEngine.posMs > 5000 && avoid.isEmpty()) MusicEngine.posMs / 1000.0 else maxOf(10.0, t.durMs / 2000.0)
        SongId.smart(act, t, start, avoid) { best, list, msg ->
            act.runOnUiThread {
                idBusy = false; paintIdState()
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (list.isEmpty()) {
                    val g = GAlert(act).setTitle("🔎 ما اتعرفتش").setMessage(msg)
                    g.setNeutralButton("توكن AudD") { _, _ -> askToken { } }
                    g.setNegativeButton("✏️ اكتب الاسم") { _, _ -> renameSong() }
                    g.setPositiveButton("تمام", null).show()
                } else if (best != null && avoid.isEmpty()) { lastOpts[t.id] = list.toMutableList(); autoApplyName(t, best) }   // (v270) اتفقوا: اسم + غلاف + كلمات أوتوماتيك
                else {
                    val all = list.toMutableList(); lastOpts[t.id] = list.toMutableList()
                    val tg = Poster.clean(t.title).trim()
                    if (SongId.tagsLookGood(t.title, t.artist) && all.none { sameName(it, SongMatch(tg, t.artist, "")) }) all.add(0, SongMatch(tg, t.artist, "", 100))   // (v270) اسم الملف نفسه أول خيار
                    showNameChoices(t, all)
                }
            }
        }
    }

    /** (v270) اتفاق على الأغنية: نحفظ الاسم، والغلاف والكلمات بيتجابوا لوحدهم (applyIdentified)، وبنقول لليوزر ✗ لو غلط */
    private fun autoApplyName(t: Track, m: SongMatch) {
        applyIdentified(t, m); paintIdState()
        quiet("✅ " + nameLine(m) + (if (m.votes > 1) "  (${m.votes} مقاطع اتفقوا)" else "") + " — بجيب الغلاف والكلمات…", 3500L)
    }

    private fun sameName(a: SongMatch, b: SongMatch) = Poster.clean(a.title).lowercase() == Poster.clean(b.title).lowercase() && a.artist.trim().lowercase() == b.artist.trim().lowercase()
    private fun nameLine(m: SongMatch) = listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" — ")

    private fun showNameChoices(t: Track, all: MutableList<SongMatch>) {
        if (act.isDestroyed || act.isFinishing) return
        val arr = all.map { m -> nameLine(m) + (if (m.album.isNotBlank()) "\n💿 " + m.album else "") + (if (m.conf in 1..99) "   (${m.conf}%)" else "") + (if (m.votes > 1) "   ✔ ${m.votes} مقاطع متفقة" else "") }.toTypedArray()
        GAlert(act).setTitle("🔎 اختار الأغنية الصح")
            .setItems(arr) { _, i -> pickName(t, all[i]) }
            .setPositiveButton("✏️ كتابة") { _, _ -> renameSong() }
            .setNeutralButton("🔁 جرّب تاني") { _, _ -> moreNames(t, all) }
            .setNegativeButton("إلغاء", null).show()
    }

    /** (v270) 🔁 جرّب تاني: ولا واحد من اللي اتعرضوا صح ← المفاتيح تسمع مقاطع تانية ومحدّش من الأسماء دي (ولا اللي اتقال إنها غلط) يرجع خالص */
    private fun moreNames(t: Track, all: MutableList<SongMatch>) {
        if (idBusy) { quiet("لسه بدوّر…", 1500L); return }
        idBusy = true; paintIdState()
        quiet("🔎 بدوّر تاني — الأسماء اللي فاتت مش هترجع…", 2500L)
        val tried = triedNames.getOrPut(t.id) { ArrayList() }
        all.forEach { n -> listOf(nameLine(n), n.title).filter { it.isNotBlank() && it !in tried }.forEach { tried.add(it) } }
        val avoid = tried.toList()
        val start = maxOf(8.0, t.durMs / 1000.0 * (0.15 + 0.6 * Math.random()))
        SongId.smart(act, t, start, avoid) { best, list, msg ->
            act.runOnUiThread {
                idBusy = false; paintIdState()
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                val fresh = list.filter { n -> all.none { sameName(it, n) } }
                if (fresh.isEmpty()) {
                    quiet("مفيش خيارات جديدة" + (if (msg.isNotBlank()) " — " + msg else "") + " · جرّب ✏️ كتابة", 3500L)
                    showNameChoices(t, all); return@runOnUiThread
                }
                lastOpts[t.id] = fresh.toMutableList()
                if (best != null) autoApplyName(t, best) else showNameChoices(t, fresh.toMutableList())
            }
        }
    }

    /** اليوزر اختار الاسم: بيتحفظ ومؤكَّد، وبعدها شاشة اختيار الألبوم/الغلاف */
    private fun pickName(t: Track, m: SongMatch) {
        applyIdentified(t, m); SongId.setVerified(t.id, true); paintIdState()
        quiet("✅ " + nameLine(m), 2000L)
        coverChoices(t, HashSet(), true)
    }

    // ===== (v269) اختيار الألبوم/الغلاف بإيدك =====
    private fun decodeThumb(b: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var ss = 1; while (o.outWidth / ss > max * 2 || o.outHeight / ss > max * 2) ss *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
    }

    /** بيجيب دفعة أغلفة جديدة (من غير ما يكرّر اللي اتعرض في [shown]) ويعرضها شبكة 3 في الصف. «➕ خيار آخر» = دفعة تانية */
    private fun coverChoices(t: Track, shown: MutableSet<String>, first: Boolean = false) {
        if (posterBusy) { quiet("لسه بدوّر… استنى شوية", 1500L); return }
        posterBusy = true
        quiet(if (shown.isEmpty()) "🖼 بجيب الألبومات المتاحة…" else "🖼 بجيب صور تانية…", 2500L)
        val n = nameOf(t)
        Thread {
            val fresh = try { Poster.nextOptions(act, n.first, n.second, shown, 6) } catch (_: Throwable) { emptyList<Poster.CoverOpt>() }
            val thumbs = fresh.map { try { decodeThumb(it.bytes, 300) } catch (_: Throwable) { null } }
            act.runOnUiThread {
                posterBusy = false
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (fresh.isEmpty()) {
                    quiet(if (first) "🖼 ملقيتش ألبومات للأغنية دي" else "🖼 مفيش صور تانية", 2800L)
                    return@runOnUiThread
                }
                val holder = arrayOfNulls<android.app.Dialog>(1)
                val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                for (rowStart in fresh.indices step 3) {
                    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; weightSum = 3f }
                    for (k in rowStart until rowStart + 3) {
                        if (k >= fresh.size) { row.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f)); continue }
                        val o = fresh[k]
                        val cell = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6)); background = ui.box(th.surface, th.border, 12) }
                        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; thumbs[k]?.let { setImageBitmap(it) } }
                        cell.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(104)))
                        cell.addView(ui.text(o.label.ifBlank { "—" }, 11f, th.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, ui.dp(3), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
                        Glass.pressable(cell)
                        cell.setOnClickListener { holder[0]?.dismiss(); applyCover(t, o) }
                        row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
                    }
                    grid.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
                holder[0] = GAlert(act).setTitle("🖼 اختار الألبوم / الغلاف الصح").setView(grid)
                    .setNeutralButton("➕ خيار آخر") { _, _ -> coverChoices(t, shown) }
                    .setNegativeButton("تخطي", null).show()
            }
        }.apply { isDaemon = true }.start()
    }

    /** الغلاف اللي اليوزر اختاره: بيتحفظ، ومش هيتغيّر لا بالمدمج ولا بالبحث التلقائي */
    private fun applyCover(t: Track, o: Poster.CoverOpt) {
        val n = nameOf(t)
        Poster.saveChosen(act, n.first, n.second, o.bytes)
        Cfg.put("noemb_${t.id}", "1")
        val b = (try { decodeThumb(o.bytes, 900) } catch (_: Throwable) { null }) ?: return
        try { FaceFocus.compute(b) } catch (_: Throwable) {}
        artCache.put(t.uri, b); artNone.remove(t.uri); tintDone = false
        refreshArts()
        quiet("🖼 اتحدّث الغلاف ✅", 1800L)
    }

    private val triedNames = HashMap<Long, ArrayList<String>>()
    private val lastOpts = HashMap<Long, MutableList<SongMatch>>()
    /** ✗ على الاسم: بيعرض النتايج التانية اللي كانت طلعت (من غير الاسم الغلط)؛ ولو مفيش أو ولا واحدة صح ← «🔁 جرّب تاني» بيدوّر من جديد من غير أي اسم اتعرض */
    private fun wrongSong() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        if (idBusy) { quiet("لسه بدوّر…", 1500L); return }
        val n = nameOf(t)
        SongId.resetKnown(t.id); paintIdState()
        val tried = triedNames.getOrPut(t.id) { ArrayList() }
        listOf(n.first + " — " + n.second, n.first).filter { it.isNotBlank() && it !in tried }.forEach { tried.add(it) }
        val bad = tried.map { it.trim().lowercase() }
        val alts = (lastOpts[t.id] ?: mutableListOf()).filter { m -> nameLine(m).trim().lowercase() !in bad && m.title.trim().lowercase() !in bad }.toMutableList()
        if (alts.isNotEmpty()) showNameChoices(t, alts) else identify(tried.toList())
    }
    /** (v251) ✓ الاسم صح: اتقفل ومش هندوّر عليه تاني */
    private fun confirmName() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        SongId.markKnown(t.id); SongId.setVerified(t.id, true); paintIdState()
        quiet("✔ الاسم اتأكد — مش هدوّر عليه تاني", 2200L)
    }
    private fun paintIdState() {
        val t = MusicEngine.current
        val s = when {
            t == null -> ""
            idBusy || autoBusy.contains(t.id) -> "🔎 جيميناي بيسمع جزء كبير…"
            SongId.verified(t.id) -> "✔ الاسم مؤكد"
            SongId.known(t.id) -> "✅ اتعرفت قبل كده"
            else -> ""
        }
        if (idState.text.toString() != s) idState.text = s
        idState.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        okBtn.alpha = if (t != null && SongId.verified(t.id)) 1f else 0.7f
    }
    /** (v251) أغنية جديدة اتشغّلت ولسه ما اتعرفتش: جيميناي يسمع جزء منها ويغيّر الاسم تلقائي (مرة واحدة بس) */
    private fun autoIdentify(t: Track) {
        if (!SongId.autoOn() || SongId.searched(t.id) || autoBusy.contains(t.id)) return
        if (Cfg.allMainKeys().isEmpty()) return
        if (SongId.override(t.id) != null || SongId.verified(t.id)) return          // (v270) اتسمّت قبل كده أو اليوزر أكّدها: ما نلمسهاش
        if (SongId.tagsLookGood(t.title, t.artist)) return                          // (v270) اسم الملف/التاج كويس (عنوان + فنان): ما نغيّرهوش تلقائي، والـ🔎 اليدوي موجود
        SongId.markTried(t.id)                                                       // (v270) نسجّل المحاولة من البداية: مفيش تكرار لو خرجت أو الطلب فشل
        autoBusy.add(t.id); paintIdState()
        val start = maxOf(10.0, t.durMs / 2000.0)
        SongId.smart(act, t, start, emptyList()) { m, lst, msg ->
            act.runOnUiThread {
                autoBusy.remove(t.id)
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (lst.isNotEmpty()) lastOpts[t.id] = lst.toMutableList()
                if (m == null) { SongId.markTried(t.id); paintIdState(); return@runOnUiThread }   // مفيش اتفاق: ما نغيّرش حاجة (🔎 يدوي بيعرض الخيارات)
                if (SongId.verified(t.id)) { paintIdState(); return@runOnUiThread }
                if (MusicEngine.current?.id == t.id) applyIdentified(t, m)
                else { val old = nameOf(t); SongId.saveOverride(t.id, m); SongId.markKnown(t.id); Poster.forget(act, old.first, old.second); LyricsEngine.clearAll(act, t) }
                paintIdState()
                quiet("🔎 اتعرفت: " + nameLine(m), 3000L)
            }
        }
    }
    /** 🔄 إعادة تعرّف: ينسى الحالة ويسمع مقطع تاني من الأغنية */
    private fun reIdentify() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        if (idBusy || autoBusy.contains(t.id)) { quiet("لسه بدوّر…", 1500L); return }
        SongId.resetKnown(t.id)
        identify()
    }
    /** ريفريش: الاسم (لو مش محفوظ يدويًا) والغلاف والكلمات من الأول */
    private fun refreshAll() {
        val t = MusicEngine.current ?: return
        if (LyricsEngine.isRunning(t, LyricsEngine.mode()) || idBusy) { quiet("لسه شغّال… استنى شوية", 1500L); return }
        val n = nameOf(t)
        Poster.resetSkip(n.first, n.second); Poster.forget(act, n.first, n.second); Cfg.put("noemb_${t.id}", "")
        forgetArt(t); refreshNow()
        LyricsEngine.clearAll(act, t); lyr = null; lyrKey = null
        if (SongId.override(t.id) == null) {
            idBusy = true
            quiet("🔎 بحدّث الاسم والكلمات والبوستر…", 2500L)
            SongId.auto(act, t, maxOf(10.0, t.durMs / 2000.0), emptyList()) { m, _ ->
                act.runOnUiThread {
                    idBusy = false
                    if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                    if (m != null) applyIdentified(t, m) else if (fullOn) loadLyrics(force = true)
                }
            }
        } else if (fullOn) loadLyrics(force = true)
    }
    private var posterBusy = false
    /** (v241) ريفريش البوستر بس: iTunes ← Deezer ← MusicBrainz ← صورة المغني؛ لو ملقاش حاجة الغلاف الحالي بيفضل */
    private fun refreshPoster() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        if (posterBusy) { quiet("لسه بدوّر… استنى شوية", 1500L); return }
        posterBusy = true
        quiet("🖼 بدوّر على البوستر (iTunes · Deezer · MusicBrainz · YouTube · Bing)…", 2500L)
        val n = nameOf(t); val uri = t.uri; val id = t.id
        Thread {
            val b = try { Poster.refetchSync(act, n.first, n.second) } catch (_: Throwable) { null }
            if (b != null) try { FaceFocus.compute(b) } catch (_: Throwable) {}
            act.runOnUiThread {
                posterBusy = false
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (b == null) { quiet("🖼 ملقيتش بوستر باسم «" + n.first + "»" + (if (n.second.isBlank()) "" else " / «" + n.second + "»"), 2800L); return@runOnUiThread }
                Cfg.put("noemb_$id", "1")
                artCache.put(uri, b); artNone.remove(uri); tintDone = false
                refreshArts()
                quiet("🖼 اتحدّث البوستر ✅", 1800L)
            }
        }.apply { isDaemon = true }.start()
    }
    /** البوستر غلط: انسى الغلاف ده (حتى لو كان مدمج في الملف) وجرّب اللي بعده (iTunes/Deezer/MusicBrainz/صورة المغني) */
    private fun wrongPoster() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        val n = nameOf(t)
        Poster.reject(act, n.first, n.second); Cfg.put("noemb_${t.id}", "1")
        notifyNoPoster = true; forgetArt(t)
        quiet("🖼 بدوّر على بوستر تاني…", 1800L)
        refreshNow()
    }

    /** اسم جديد اتأكد: احفظه، صفّر الغلاف القديم وهات الجديد، وعيد الكلمات */
    private fun applyIdentified(t: Track, m: SongMatch) {
        val old = nameOf(t)
        SongId.saveOverride(t.id, m); SongId.markKnown(t.id)
        Poster.forget(act, old.first, old.second); Poster.forget(act, m.title, m.artist)
        LyricsEngine.clearAll(act, t); lyrKey = null; lyr = null
        forgetArt(t); refreshNow()
        reload()
        if (fullOn) loadLyrics(force = true)
    }

    init {
        // الجسم: صفحة الفيديوهات-بتاعة-الصوت فوق + الكبسولة الصغيرة تحت
        body.addView(lib.root, LinearLayout.LayoutParams(-1, 0, 1f))
        miniBar.addView(miniFill, FrameLayout.LayoutParams(0, -1, Gravity.START))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(9), 0, ui.dp(6), 0) }
        row.addView(miniArt, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        col.addView(nowTitle, LinearLayout.LayoutParams(-1, -2)); col.addView(nowSub, LinearLayout.LayoutParams(-1, -2))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10); marginEnd = ui.dp(6) })
        row.addView(miniPlay, LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)))
        row.addView(miniNext, LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)))
        miniBar.addView(row, FrameLayout.LayoutParams(-1, -1))
        miniPlay.setOnClickListener { toggle() }
        miniNext.setOnClickListener { step(1) }
        miniBar.setOnClickListener { showFull() }
        body.addView(miniBar, LinearLayout.LayoutParams(-1, ui.dp(62)).apply { setMargins(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8)) })
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        MusicEngine.loadPrefs()
        buildFull()
        root.addView(full, FrameLayout.LayoutParams(-1, -1)); full.visibility = View.GONE

        lib.onPlayFolder = { f -> playFolder(f) }
        lib.onGrant = { onGrant() }
        lib.onPull = { reload() }
        lib.onRefresh = { reload(true) }
        MusicEngine.listeners.add(onEngine)
        refreshNow(); paint(); paintMini()
        tick()
    }

    /** معرّف جلسة الصوت الحالية (للإيكولايزر لاحقًا) — 0 لو مفيش مشغّل */
    val sessionId: Int get() = MusicEngine.sessionId

    /** زرار الرجوع: يقفل الكلمات الكاملة، بعدين المشغّل، وبعدين يرجع من الفولدر/البحث/المخفي (زي الفيديوهات) */
    fun onBack(): Boolean {
        if (fullOn) { if (lyrOn) hideLyrics() else hideFull(); return true }
        return lib.back()
    }
    /** بيفصل الشاشة عن المحرك — الموسيقى بتكمّل في الخلفية (الإيقاف من ✕ في الإشعار) */
    fun destroy() { h.removeCallbacksAndMessages(null); MusicEngine.listeners.remove(onEngine) }
    /** بيوقف مؤقتًا لو فيه حاجة شغّالة (مثلًا لما فيديو يتفتح) */
    fun pause() { MusicEngine.pause() }
}
