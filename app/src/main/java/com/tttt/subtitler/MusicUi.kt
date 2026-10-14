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

    private val onEngine: () -> Unit = { val se = MusicEngine.takeStemErr(); if (se.isNotBlank()) { errNote(se); quiet(se, 4500L) }; paint(); refreshNow(); lib.refreshRows(); paintMini() }

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
    // (v294) زرار واحد بس جنب اسم الأغنية: «المعلومات غلط» ← يدوّر من الأول (الاسم والمغني والألبوم + الغلاف والكلمات). باقي الأزرار القديمة اتشالت.
    private val wrongInfoBtn: TextView = ui.text("⚠ المعلومات غلط", 11.5f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); background = ui.box(0x33E5484D, 0x88E5484D.toInt(), 16)
        setOnClickListener { editInfoDialog() }
    }
    // (v301) ✏️ جنب «المعلومات غلط»: نافذة بتختار منها إيه اللي غلط (الاسم / البوستر / الكلمات) بـ checkbox وبيتصلّح اللي اخترته
    private val editInfoBtn: TextView = ui.text("✏️", 14f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(ui.dp(9), ui.dp(3), ui.dp(9), ui.dp(3)); background = ui.box(0x33FFFFFF, 0x66FFFFFF, 16)
        setOnClickListener { editInfoDialog() }
    }
    private val idState = ui.text("", 10.5f, C_DIM).apply { gravity = Gravity.CENTER; setSingleLine(); visibility = View.GONE }
    private val bShuffle = GlyphBtn(act, "shuffle", 24)
    private val bPrev = GlyphBtn(act, "prev", 28)
    private val bPlay = GlyphBtn(act, "play", 40)
    private val bNext = GlyphBtn(act, "next", 28)
    private val bRepeat = GlyphBtn(act, "repeat", 24)
    private var fullOn = false
    private val middle = FrameLayout(act)
    private val eq = EqView(act).apply {
        accent = BLUE
        onFrame = { bass, pulse -> try { danceFrame(bass, pulse) } catch (_: Throwable) {} }
        center = { orbCenter() }
    }
    /** (v320) مركز دايرة الألبوم ونص قطرها (بعد التصغير/الانزلاق وقت الكلمات) بإحداثيات middle — الإيكولايزر بقى خلفية ورا الدايرة والكلمات */
    private fun orbCenter(): FloatArray? {
        if (orb.width <= 0 || orb.height <= 0) return null
        val pts = floatArrayOf(orb.width / 2f, orb.ringCy); orb.matrix.mapPoints(pts)
        return floatArrayOf(orb.left + orb.translationX + pts[0], orb.top + orb.translationY + pts[1], orb.ringR * orb.scaleX)
    }
    // (v306) الكلمات بترقص على إيقاع الأغنية: السطر الشغّال بيكبّر ويهتز مع ضربة الباص (من Visualizer، ولو مفيش إذن حركة هادية)
    private fun danceOn() = Cfg.str("lyr_dance", "1") != "0"
    private var danceViews: List<View?> = emptyList()
    private fun danceReset() { for (v in danceViews) v?.apply { scaleX = 1f; scaleY = 1f; rotation = 0f; translationY = 0f; (this as? TextView)?.setShadowLayer(0f, 0f, 0f, 0) }; danceViews = emptyList() }
    private fun danceFrame(bass: Float, pulse: Float) {
        if (!fullOn || !danceOn()) { if (danceViews.isNotEmpty()) danceReset(); return }
        val tg = ArrayList<View?>()
        if (lyrOn) { tg.add(lyrViews.getOrNull(lyrIdx)); tg.add(trViews.getOrNull(lyrIdx)) } else if (lyrCurTv.visibility == View.VISIBLE) tg.add(lyrCurTv)
        for (v in danceViews) if (v != null && v !in tg) v.apply { scaleX = 1f; scaleY = 1f; rotation = 0f; translationY = 0f; (this as? TextView)?.setShadowLayer(0f, 0f, 0f, 0) }
        danceViews = tg
        val t = System.nanoTime() / 1e9
        val sc = 1f + 0.05f * pulse + 0.025f * bass
        val sway = (Math.sin(t * 3.1) * 1.1 * bass).toFloat()
        for ((k, v) in tg.withIndex()) {
            if (v == null) continue
            val s = if (k == 1) 1f + (sc - 1f) * 0.6f else sc   // سطر الترجمة بيتحرك أخف
            v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
            v.scaleX = s; v.scaleY = s; v.rotation = sway; v.translationY = -ui.dp(3) * pulse
            (v as? TextView)?.setShadowLayer(4f + 18f * bass + 10f * pulse, 0f, 0f, (BLUE and 0x00FFFFFF) or 0xAA000000.toInt())
        }
    }
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
    private fun placeVol() {}   // (v302) سلايدر الصوت اتشال (الصوت بقى بزراري الفوليوم)
    private var srcNoteKey = ""
    // ===== (v200) أدوات المشغّل: ⏱ مؤقت النوم · ⏩ السرعة · 📃 التالي =====
    private fun pillBg(on: Boolean) = if (on) ui.box(0x445B9DFF, BLUE, 15) else ui.box(0x22FFFFFF, 0x33FFFFFF, 15)
    private fun toolPill(t: String, f: () -> Unit) = ui.text(t, 10.5f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4))
        background = pillBg(false); setOnClickListener { f() }
    }
    private val bSleep = toolPill("⏱ نوم") { sleepDialog() }
    private val bSpeed = toolPill("⏩ 1×") { speedDialog() }
    private val bQueue = toolPill("📃 التالي") { queueDialog() }
    private val bStem = toolPill("🎼 الكل") { stemTap() }.apply { setOnLongClickListener { stemDialog(); true } }   // (v308) دوسة = الكل ← صوت ← موسيقى · دوسة طويلة = الإعدادات   // (v307) فصل الصوت عن الموسيقى بالذكاء الصناعي
    private fun stemLabel(): String {
        val st = MusicEngine.stem; val p = MusicEngine.stemPct
        return when {
            p >= 0 -> (if (st == 1) "🎤 " else if (st == 2) "🎸 " else "🎼 ") + p + "%"
            st == 1 -> "🎤 صوت"
            st == 2 -> "🎸 موسيقى"
            else -> "🎼 الكل"
        }
    }
    private var lastToolSig = ""
    private var lastVp = -1
    private fun speedLabel(s: Float): String = if (s == s.toInt().toFloat()) s.toInt().toString() + "×" else String.format(java.util.Locale.US, "%.2f", s).trimEnd('0') + "×"
    private fun paintTools() {
        val left = MusicEngine.sleepLeftMs(); val sec = (left + 999L) / 1000L
        val eot = MusicEngine.sleepEndOfTrack; val sp = MusicEngine.speed
        val rest = (MusicEngine.queue.size - MusicEngine.idx - 1).coerceAtLeast(0)
        MusicEngine.syncSys()   // (v270) زراري الفوليوم ↔ السلايدر
        val vp = MusicEngine.volPct
        val sig = "$sec|$eot|$sp|$rest|$vp|" + MusicEngine.stem + "|" + MusicEngine.stemPct
        if (sig == lastToolSig) return
        lastToolSig = sig
        bSleep.text = when { eot -> "⏱ بعد دي"; sec > 0L -> "⏱ " + VideoLib.fmtDur(left); else -> "⏱ نوم" }
        bSleep.background = pillBg(eot || sec > 0L)
        bSpeed.text = "⏩ " + speedLabel(sp); bSpeed.background = pillBg(sp != 1f)
        bQueue.text = if (rest > 0) "📃 التالي ($rest)" else "📃 التالي"
        bStem.text = stemLabel(); bStem.background = pillBg(MusicEngine.stem != 0)
        // (v302) إشعار صغير بنسبة الصوت لما تدوس فوليوم أب/داون (من 0 لـ 200%؛ فوق 100% = تضخيم)
        if (lastVp >= 0 && vp != lastVp && fullOn) quiet("🔊 $vp%" + (if (vp > 100) " — تضخيم " + MusicEngine.boostDbText() else ""), 900L)
        lastVp = vp
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
    // ===== (v307) فصل الصوت عن الموسيقى بالذكاء الصناعي (MDX-Net): 🎼 الكل · 🎤 المغني بس · 🎸 الموسيقى بس =====
    /** (v308) دوسة على الزرار: 🎼 صوت+موسيقى ← 🎤 صوت بس ← 🎸 موسيقى بس ← تاني. أول مرة بيطلب تنزيل الموديلات */
    private fun stemTap() {
        if (MusicEngine.current == null) { quiet("مفيش حاجة شغّالة", 1500L); return }
        if (!StemAi.libOk()) { quiet("🎚 الفصل مش شغّال على الجهاز ده (محتاج موبايل arm64): " + StemAi.libErr(), 4500L); return }
        val e = StemAi.engine()
        val go = {
            val k = MusicEngine.cycleStem(); lastToolSig = ""; paintTools()
            quiet(when (k) { 1 -> "🎤 صوت بس"; 2 -> "🎸 موسيقى بس"; else -> "🎼 صوت + موسيقى" }, 1500L)
        }
        if (StemAi.ready(act, e)) go() else stemDownload(e, go)
    }
    private fun stemDialog() {
        val cur = MusicEngine.current
        if (cur == null) { quiet("مفيش حاجة شغّالة", 1500L); return }
        var dlg: android.app.Dialog? = null
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        val e = StemAi.engine()
        fun pick(k: Int) {
            if (k == 0) { MusicEngine.setStem(0); quiet("🎼 الأغنية الأصلية", 1500L); return }
            if (!StemAi.libOk()) { quiet("🎚 الفصل مش شغّال على الجهاز ده (محتاج موبايل arm64): " + StemAi.libErr(), 4500L); return }
            val go = {
                MusicEngine.setStem(k)
                quiet(if (Karaoke.hasStem(act, cur, k)) (if (k == 1) "🎤 المغني بس" else "🎸 الموسيقى بس") else "🎚 بيفصل الأغنية… هتشتغل عادي ولما يخلص بيبدّل لوحده", 3200L)
            }
            if (StemAi.ready(act, e)) go() else stemDownload(e, go)
        }
        for ((k, lbl) in listOf(0 to "🎼 الكل — الأغنية الأصلية", 1 to "🎤 المغني بس (من غير موسيقى)", 2 to "🎸 الموسيقى بس (من غير مغني)")) {
            col.addView(optRow(lbl, MusicEngine.stem == k) { dlg?.dismiss(); pick(k); lastToolSig = ""; paintTools() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        col.addView(ui.text("⚙ محرك الفصل", 12.5f, th.muted, true).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(4)) })
        for (en in StemAi.engines) {
            val on = en.id == e.id
            val st = if (StemAi.ready(act, en)) "✅ نازل" else "⬇ " + StemAi.needMb(act, en) + " ميجا"
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
                background = ui.box(if (on) 0x445B9DFF else 0x14808080, if (on) BLUE else th.border, 12)
                setOnClickListener { StemAi.setEngine(en.id); dlg?.dismiss(); stemDialog() }
            }
            row.addView(ui.text(en.label + "  ·  " + st, 14.5f, th.text, on))
            row.addView(ui.text(en.note, 11.5f, th.muted))
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        if (Karaoke.hasStem(act, cur, 1) || Karaoke.hasStem(act, cur, 2)) {
            col.addView(optRow("🔄 أعيد فصل الأغنية دي بالمحرك ده", false) {
                dlg?.dismiss()
                if (!StemAi.libOk()) quiet("🎚 الفصل مش شغّال على الجهاز ده: " + StemAi.libErr(), 4500L)
                else if (StemAi.ready(act, e)) { MusicEngine.redoStem(); quiet("🎚 بيعيد الفصل…", 2500L) }
                else stemDownload(e) { MusicEngine.redoStem(); quiet("🎚 بيعيد الفصل…", 2500L) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        col.addView(optRow("🗑 امسح الأغاني المفصولة (توفير مساحة)", false) { dlg?.dismiss(); MusicEngine.setStem(0); StemAi.deleteStems(act); quiet("🗑 اتمسحت", 1500L) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        col.addView(ui.text("الفصل بياخد دقايق مرة واحدة لكل أغنية وبيتخزّن. وهو شغّال الأغنية بتشتغل عادي ولما يخلص بيبدّل لوحده. صوت + موسيقى = الأغنية الأصلية بالظبط.", 11.5f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(2), 0, 0) })
        dlg = ui.sheet(act, "🎚 فصل الصوت عن الموسيقى", listOf<View>(col)); dlg.show()
    }
    private fun stemDownload(e: StemAi.Eng, then: () -> Unit) {
        if (StemAi.downloading()) { quiet("⬇ تنزيل الموديلات شغّال…", 1800L); return }
        var dlg: android.app.Dialog? = null
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        col.addView(ui.text("محتاج تنزّل موديلات الفصل (" + StemAi.needMb(act, e) + " ميجا تقريبًا، مرة واحدة بس) وبتفضل على الموبايل.", 13.5f, th.text).apply { gravity = Gravity.CENTER; setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(8)) })
        val st = ui.text("", 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(8)) }
        col.addView(st)
        col.addView(optRow("⬇ نزّل دلوقتي", true) {
            st.text = "⬇ بيبدأ…"
            StemAi.download(act, e, { got, tot ->
                h.post { st.text = "⬇ " + (got * 100L / Math.max(tot, 1L)).toInt().coerceIn(0, 99) + "%  (" + (got / 1_000_000L) + " / " + (tot / 1_000_000L) + " ميجا)" }
            }, { err ->
                h.post {
                    try { dlg?.dismiss() } catch (_: Throwable) {}
                    if (err == null) { quiet("✅ موديلات الفصل نزلت", 1800L); then() }
                    else { errNote("🎚 تنزيل موديلات الفصل فشل: " + err); quiet("🎚 تنزيل الموديلات فشل: " + err, 4000L) }
                }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        col.addView(optRow("إلغاء", false) { StemAi.cancelDownload(); dlg?.dismiss() }, LinearLayout.LayoutParams(-1, -2))
        dlg = ui.sheet(act, "🎚 موديلات فصل الصوت", listOf<View>(col)); dlg.show()
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
        val etAl = ui.input("اسم الألبوم (بيتدوّر بيه على البوستر)", SongId.albumOf(t.id))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        col.addView(ui.text("الاسم الصح بيحسّن الكلمات والبوستر. بعد الحفظ بيجيب الكلمات من الأول.", 12f, th.muted))
        col.addView(etT); col.addView(etA); col.addView(etAl)
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
                        if (m == null) quiet("🔎 " + msg, 3000L) else { etT.setText(m.title); etA.setText(m.artist); if (m.album.isNotBlank()) etAl.setText(m.album) }
                    }
                }
            }
        }
        col.addView(gen, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(4) })
        GAlert(act).setTitle("✏️ اسم الأغنية والفنان").setView(col)
            .setPositiveButton("💾 احفظ") { _, _ ->
                val a = etT.text.toString().trim(); val b = etA.text.toString().trim()
                if (a.isEmpty()) quiet("اكتب اسم الأغنية", 2000L) else { applyIdentified(t, SongMatch(a, b, etAl.text.toString().trim())); SongId.setVerified(t.id, true); paintIdState() }
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
    private val winSpin = WinSpinnerDrawable()
    private fun bigMsg(t: String, loading: Boolean = false) {
        lyrBox.animate().cancel(); lyrBox.alpha = 1f
        lyrBox.removeAllViews(); lyrViews.clear(); trViews.clear(); lyrTr = null; lyrIdx = -2; lyr = null
        winSpin.stop()
        if (loading) {
            // (v298) لودينج بتاع ويندوز: 5 نقط بتلف وهو بيجيب الكلمات
            lyrBox.addView(ImageView(act).apply { setImageDrawable(winSpin) }, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(36) })
            winSpin.start()
        }
        lyrBox.addView(ui.text(t, 15f, C_DIM).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), if (loading) ui.dp(8) else ui.dp(40), ui.dp(8), ui.dp(12)) })
        liveReset()
    }
    /** (v198) المسافة اللي الدواير بتنزلها: من أسفل الحلقة لحد ما تلمس اسم الأغنية بالظبط — من غير تصغير (فمفيش قص للأغلفة اللي على الجنبين) */
    private fun lyrShift(): Float = (orb.height / 2f - orb.ringR - ui.dp(3)).coerceAtLeast(0f)
    /** فتح/قفل صفحة الكلمات (بتتفتح بزرار «إظهار الكلمات» أو من ⋮) */
    private val LYR_SCALE = 0.52f    // (v298) حجم الدواير وقت الكلمات = 52% من الأصل (كان 30%)
    private fun toggleLyrics() {
        if (MusicEngine.current == null) { quiet("شغّل أغنية الأول وبعدين دوس على الكلمات", 2200L); return }
        if (lyrOn) { hideLyrics(); return }
        lyrOn = true; eq.alphaMul = 0.5f; lyrIdx = -2; lyrWordKey = ""
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
        lyrOn = false; eq.alphaMul = 1f; paintLyrBtn(); orb.passThrough = false; placeVol()
        lyrPanel.animate().cancel(); lyrPanel.animate().setStartDelay(0L).alpha(0f).setDuration(220L).withEndAction { if (!lyrOn) lyrPanel.visibility = View.GONE }.start()
        orb.animate().cancel(); orb.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(460L).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }
    /** لما المشغّل نفسه يتقفل: رجّع الدايرة والكلمات لوضعهم فورًا (الأنيميشن مابيشتغلش على view مخفي) */
    private fun snapLyrReset() {
        lyrOn = false; eq.alphaMul = 1f; paintLyrBtn(); orb.passThrough = false; placeVol()
        lyrPanel.animate().cancel(); lyrPanel.visibility = View.GONE; lyrPanel.alpha = 1f
        orb.animate().cancel(); orb.translationY = 0f; orb.scaleX = 1f; orb.scaleY = 1f
    }
    private fun nameHint(t: Track): Pair<String, String>? = nameOf(t).let { if (it.first.isBlank()) null else it }

    /** يعرض الكلمات المحفوظة للأغنية والوضع الحاليين، أو يبدأ الكتابة (تلقائيًا لو الكلمات التلقائية شغّالة) — ودايمًا فيه رسالة حالة ظاهرة */
    private fun loadLyrics(force: Boolean = false, manual: Boolean = false) {
        val t = MusicEngine.current ?: run { bigMsg("شغّل أغنية الأول"); setStatus(""); return }
        val m = LyricsEngine.mode()
        val ck = t.uri + "|" + m
        if (!force && lyrKey == ck && lyr != null) { lyrBox.animate().cancel(); lyrBox.alpha = 1f; return }
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
    private var lyrPendingKey = ""
    private var lyrRunnable: Runnable? = null
    /** (v316) تغيير أغنية: الكلمات القديمة تختفي بفيد فورًا، وبناء الكلمات الجديدة (مئات الـ TextView على خيط الشاشة) يستنى لحد ما أنيميشن الكاروسيل يخلص — ده اللي كان بيسبّب التقطيع */
    private fun scheduleLoadLyrics(t: Track) {
        val ck = t.uri + "|" + LyricsEngine.mode()
        if (lyrPendingKey == ck) return
        lyrPendingKey = ck
        lyrRunnable?.let { idHandler.removeCallbacks(it) }
        lyrBox.animate().cancel(); lyrBox.animate().alpha(0f).setDuration(130L).start()
        val r = Runnable {
            lyrRunnable = null; lyrPendingKey = ""
            if (!act.isDestroyed && !act.isFinishing && fullOn && MusicEngine.current?.uri == t.uri) loadLyrics()
        }
        lyrRunnable = r; idHandler.postDelayed(r, 320L)
    }
    private fun redoLyrics(wrong: Boolean) {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        val m = LyricsEngine.mode()
        if (LyricsEngine.isRunning(t, m)) { quiet("لسه بكتب الكلام… استنى شوية", 1800L); return }
        LyricsEngine.clear(act, t, m); lyrKey = t.uri + "|" + m; lyr = null
        startLyrics(t, m, wrong, true)
    }
    private fun startLyrics(t: Track, m: String, retry: Boolean, fresh: Boolean) {
        val ck = t.uri + "|" + m
        lyrKey = ck
        if (LyricsEngine.isRunning(t, m)) { setStatus("🎧 بتتكتب في الخلفية… استنى"); if (lyr == null) bigMsg("⏳ بتتكتب… هتظهر هنا أول ما تجهز", true); return }
        if (lyr == null) bigMsg("📝 بجيب الكلمات…", true)
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
        winSpin.stop()
        val trk = MusicEngine.current
        try { paintSrc() } catch (_: Throwable) {}
        val fresh = lyrBox.childCount == 0 || lyr == null   // (v315) أول ظهور (أو بعد لودينج/أغنية تانية): فيد ناعم بدل ما الكلمات تنط فجأة
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
        lyrBox.animate().cancel()
        if (fresh || lyrBox.alpha < 1f) { lyrBox.alpha = 0f; lyrBox.animate().alpha(1f).setDuration(220L).start() } else lyrBox.alpha = 1f
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
        titleRow.addView(editInfoBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(5) })
        titleRow.addView(wrongInfoBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        val rightCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        rightCol.addView(errBtn, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(4) })
        rightCol.addView(lyrBtn, LinearLayout.LayoutParams(-2, -2))
        titleRow.addView(rightCol, LinearLayout.LayoutParams(-2, -2))
        // (v241) اسم الأغنية + زرار الكلمات + أدوات (نوم/سرعة/التالي) في عمود، وسلايدر الصوت الطولي جنب زرار الكلمات على اليمين
        val infoCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        infoCol.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2); bottomMargin = ui.dp(2) })
        val toolsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        for (p in listOf<View>(bSleep, bSpeed, bQueue, bStem)) toolsRow.addView(p, LinearLayout.LayoutParams(0, ui.dp(30), 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
        infoCol.addView(toolsRow, LinearLayout.LayoutParams(-1, ui.dp(34)).apply { topMargin = ui.dp(2) })
        // (v302) سلايدر الصوت اتشال: الصوت بزراري الفوليوم (0–200%)
        val infoRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        infoRow.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))
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
        middle.addView(eq, FrameLayout.LayoutParams(-1, -1))      // (v320) الإيكولايزر أول طبقة: ورا الكلمات وورا الدايرة
        middle.addView(lyrPanel, FrameLayout.LayoutParams(-1, -1)); middle.addView(inner, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(top, LinearLayout.LayoutParams(-1, ui.dp(56)))
        col.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))
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
        item(if (danceOn()) "💃 إيقاف حركة الكلمات مع الإيقاع" else "💃 تشغيل حركة الكلمات مع الإيقاع") {
            Cfg.put("lyr_dance", if (danceOn()) "0" else "1"); danceReset()
            quiet(if (danceOn()) "💃 الكلمات هترقص مع إيقاع الأغنية" else "الكلمات هتثبت", 2200L)
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
        if (MusicEngine.current?.let { autoBusy.contains(it.id) } == true) { bigMsg("🔎 بتعرّف على الأغنية الأول… وبعدها الغلاف والكلمات"); setStatus("🔎 المفاتيح بتسمع أجزاء مختلفة من الأغنية") } else loadLyrics()
        MusicEngine.current?.let { cur -> if (pendingNoKeys == cur.id) { pendingNoKeys = -1L; noKeysDialog(cur) } }   // (v294) كنا مستنيين تفتح المشغّل عشان نسألك عن المفتاح/الاسم
    }
    private fun hideFull() { autoOpenFor = ""; snapLyrReset(); eq.release(); fullOn = false; full.visibility = View.GONE; onNavTint(null) }
    /** الاسم المعروض: الاسم اللي اتحفظ من التعرف لو موجود، وإلا بيانات الملف */
    private fun nameOf(t: Track): Pair<String, String> {
        val o = SongId.override(t.id)
        // (v298) اسم الموقع في التاج (Albumaty.Com…) مش اسم مغني — لو مفيش مغني متعرّف عليه نسيب الخانة فاضية بدل ما نعرض الموقع
        val tagArtist = if (SongId.looksLikeSite(t.artist)) "" else t.artist
        val pt = FastId.partial[t.id]   // (v314) اللي اتعرف لحد دلوقتي أثناء التعرّف السريع (المغني أول ما يوصل)
        return (o?.first?.ifBlank { null } ?: pt?.first?.ifBlank { null } ?: t.title) to (o?.second?.ifBlank { null } ?: pt?.second?.ifBlank { null } ?: tagArtist)
    }

    // ===== الأغلفة =====
    /** غلاف المدمج في الملف، وإلا من الإنترنت (iTunes ← Deezer ← MusicBrainz/Cover Art Archive ← صورة المغني) */
    /** (v300) غلاف الملف نفسه (مدمج أو من فهرس الموبايل). [force] = حتى لو الغلاف المدمج اتعلّم «غلط» */
    private fun localArt(t: Track, force: Boolean): Bitmap? {
        var bmp: Bitmap? = null
        val blocked = !force && Cfg.str("noemb_${t.id}", "").isNotEmpty()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(act, Uri.parse(t.uri))
            val b = if (!blocked) r.embeddedPicture else null
            if (b != null) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var ss = 1; while (o.outWidth / ss > 900 || o.outHeight / ss > 900) ss *= 2
                bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
            }
        } catch (_: Throwable) {} finally { try { r.release() } catch (_: Throwable) {} }
        // (v268) غلاف الألبوم اللي الموبايل نفسه مفهرسه (MediaStore: مدمج أو cover.jpg/folder.jpg في فولدر الأغنية) — أوفلاين، قبل أي نت
        if (bmp == null && android.os.Build.VERSION.SDK_INT >= 29 && !blocked) {
            try { bmp = act.contentResolver.loadThumbnail(Uri.parse(t.uri), android.util.Size(600, 600), null) } catch (_: Throwable) {}
        }
        return bmp
    }
    /**
     * noemb_<id>: "" = غلاف الملف الأول · "1" = «البوستر غلط» (غلاف الملف مرفوض نهائيًا) ·
     * "2" = (v300) الاسم اتصحّح: النت الأول بالاسم الجديد (الأصلي + الإنجليزي)، وغلاف الملف احتياطي بس لو النت ملقاش حاجة
     */
    private fun fetchArt(t: Track, online: Boolean = true): Bitmap? {
        val flag = Cfg.str("noemb_${t.id}", "")
        var bmp: Bitmap? = if (flag == "2") null else localArt(t, false)
        if (bmp == null && online) { val n = nameOf(t); Poster.albumHint(n.first, n.second, SongId.albumOf(t.id)); bmp = Poster.fetchSync(act, n.first, n.second, SongId.alt(t.id)) }   // (v293) أونلاين بعد ما الاسم يتحدد
        if (bmp == null && online && flag == "2") bmp = localArt(t, true)
        return bmp
    }
    private fun ensureArt(t: Track?) {
        if (t == null) return
        val k = t.uri
        if (artCache.get(k) != null || k in artNone || !artLoading.add(k)) return
        val online = !autoBusy.contains(t.id)
        Thread {
            val b = try { fetchArt(t, online) } catch (_: Throwable) { null }
            if (b != null) try { FaceFocus.compute(b) } catch (_: Throwable) {}
            act.runOnUiThread {
                artLoading.remove(k)
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (b != null) artCache.put(k, b) else if (online) artNone.add(k)
                if (k == MusicEngine.current?.uri) {
                    if (b == null && online && notifyNoPoster) errNote("🖼 مفيش بوستر تاني — هيفضل شكل الدايرة (🔄 ريفريش يرجّع البحث)")
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
        // (v316) أغلفة الأغاني المجاورة (للكاروسيل) بتتجاب بعد ما غلاف الأغنية الحالية يخلص (اتجاب أو اتأكد إنه مفيش) — عشان ما تزاحمش الغلاف والكلمات على النت
        if (fullOn && cur != null && (artCache.get(cur.uri) != null || cur.uri in artNone)) { ensureArt(pv); ensureArt(nx) }
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
            // (v315) الأغنية اتغيّرت: العملية الجارية على القديمة توقف عند أول فاصل (من غير ما نحرق طلبات)، والجديدة تستنى ثانيتين ونص (لو بتعدّي بسرعة مفيش طلب يتبعت)
            if (curTrackId != -1L && curTrackId != (t?.id ?: -1L)) FastId.abandon(curTrackId)
            curTrackId = t?.id ?: -1L
            if (t != null) { artNone.remove(t.uri); tintDone = false; scheduleAutoId(t) }
            // (v316) أنيميشن تنقّل: الاسم والمغني يدخلوا بفيد + نزلة خفيفة بدل ما يتبدّلوا فجأة
            if (t != null) for (v in listOf<View>(fTitle, fArtist, nowTitle, nowSub)) { v.animate().cancel(); v.alpha = 0f; v.translationY = ui.dp(10).toFloat(); v.animate().alpha(1f).translationY(0f).setDuration(280L).start() }
            if (t == null) { orb.progress = 0f; orb.timeText = "0:00"; miniFillW = -1; updateMiniFill(0f); liveReset() }
        }
        refreshArts(); paintIdState()
        // أغنية اتغيّرت والمشغّل مفتوح → حمّل كلماتها
        if (fullOn && t != null && lyrKey != t.uri + "|" + LyricsEngine.mode() && !autoBusy.contains(t.id)) scheduleLoadLyrics(t)   // (v293) لو لسه بيتعرّف على الأغنية، الكلمات بتستنى الاسم الصح
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
                else if (list.first().conf >= 50) {   // (v306) مفيش اتفاق: من غير قايمة اختيارات — أحسن إجابة بتتطبّق أوتوماتيك والباقي بيتجاب لوحده (✗ لو غلط)
                    lastOpts[t.id] = list.toMutableList(); val top = list.first()
                    applyIdentified(t, top); paintIdState()
                    quiet("🔎 اتعرفت: " + nameLine(top) + " (أحسن تخمين ${top.conf}% — دوس «المعلومات غلط» لو مش صح)", 4200L)
                } else {
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
                if (best != null) autoApplyName(t, best)
                else if (fresh.first().conf >= 50) { val top = fresh.first(); applyIdentified(t, top); paintIdState(); quiet("🔎 اتعرفت: " + nameLine(top) + " (أحسن تخمين ${top.conf}%)", 4200L) }   // (v306)
                else showNameChoices(t, fresh.toMutableList())
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
        if (idBusy || autoBusy.contains(t.id)) { quiet("لسه بدوّر…", 1500L); return }
        if (Cfg.allMainKeys().isEmpty()) { noKeysDialog(t); return }              // (v294) من غير مفاتيح: وصّل مفتاح أو اكتب بنفسك
        val n = nameOf(t)
        SongId.resetKnown(t.id); paintIdState()
        val tried = triedNames.getOrPut(t.id) { ArrayList() }
        listOf(n.first + " — " + n.second, n.first).filter { it.isNotBlank() && it !in tried }.forEach { tried.add(it) }
        val bad = tried.map { it.trim().lowercase() }
        val alts = (lastOpts[t.id] ?: mutableListOf()).filter { m -> nameLine(m).trim().lowercase() !in bad && m.title.trim().lowercase() !in bad }.toMutableList()
        if (alts.isNotEmpty()) showNameChoices(t, alts) else identify(tried.toList())
    }
    /**
     * (v301) ✏️ تعديل: تختار إيه اللي غلط — الاسم/المغني · البوستر · الكلمات — (أكتر من واحد مع بعض) وبيتصلّح اللي اخترته.
     * الاسم: نفس مسار «المعلومات غلط» (بيعرض الاختيارات التانية أو يدوّر من الأول)، وأول ما الاسم الصح يتحدد بيجيب البوستر والكلمات تاني لوحده.
     */
    private fun editInfoDialog() {
        val t = MusicEngine.current ?: run { quiet("شغّل أغنية الأول", 1500L); return }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        val n = nameOf(t); val al = SongId.albumOf(t.id)
        col.addView(ui.text("علّم على اللي غلط. اللي مش معلّم بعتبره صح وببني عليه تصحيح الباقي بجيميناي:", 12.5f, th.muted).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4)) })
        col.addView(ui.text(n.first + (if (n.second.isNotBlank()) " — " + n.second else "") + (if (al.isNotBlank()) "\n💿 " + al else ""), 12f, th.text).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)) })
        fun box(label: String) = android.widget.CheckBox(act).apply {
            text = label; textSize = 15f; setTextColor(th.text); minHeight = ui.dp(44)
            buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
            setPadding(ui.dp(6), 0, ui.dp(6), 0)
        }
        val cName = box("🎵 اسم الأغنية غلط"); val cSinger = box("🎤 المغني غلط"); val cAlbum = box("💿 الألبوم غلط"); val cPoster = box("🖼 البوستر (الكفر) غلط"); val cLyr = box("📝 الكلمات غلط")
        for (c in listOf(cName, cSinger, cAlbum, cPoster, cLyr)) col.addView(c, LinearLayout.LayoutParams(-1, -2))
        col.addView(ui.text("الألبوم هو اللي بيتدوّر بيه على البوستر، فتصحيحه بيصلّح البوستر لوحده.", 11.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0) })
        GAlert(act).setTitle("⚠ المعلومات غلط").setView(col)
            .setPositiveButton("🔄 Regenerate") { _, _ ->
                if (!cName.isChecked && !cSinger.isChecked && !cAlbum.isChecked && !cPoster.isChecked && !cLyr.isChecked) { quiet("علّم على حاجة واحدة على الأقل", 1800L); return@setPositiveButton }
                fixSelected(t, cName.isChecked, cSinger.isChecked, cAlbum.isChecked, cPoster.isChecked, cLyr.isChecked)
            }
            .setNeutralButton("✍ اكتب بإيدك") { _, _ -> renameSong() }
            .setNegativeButton("إلغاء", null).show()
    }
    private var forceNewPoster = false
    private fun fixSelected(t: Track, name: Boolean, singer: Boolean, album: Boolean, poster: Boolean, lyrics: Boolean) {
        if (MusicEngine.current?.id != t.id) { quiet("الأغنية اتغيّرت — افتح التعديل تاني", 2000L); return }
        if (idBusy || autoBusy.contains(t.id)) { quiet("لسه بدوّر…", 1500L); return }
        if (name || singer || album) {
            val n = nameOf(t); val al = SongId.albumOf(t.id)
            val hasKeys = Cfg.allMainKeys().isNotEmpty()
            val anchors = (!name && n.first.isNotBlank()) || (!singer && n.second.isNotBlank()) || (!album && al.isNotBlank())
            if (!hasKeys) { if (name || singer) { noKeysDialog(t) } else quiet("محتاج مفتاح جيميناي — أو دوس ✍ اكتب بإيدك", 2500L); return }
            if (!anchors) { wrongSong(); return }   // مفيش حاجة صح نبني عليها: تعرّف من الصوت من الأول
            quiet("🔎 جيميناي بيصحّح على اللي صح…", 2500L)
            idBusy = true; paintIdState()
            val tried = triedNames.getOrPut(t.id) { ArrayList() }
            listOf(n.first + " — " + n.second, n.first).filter { it.isNotBlank() && it !in tried }.forEach { tried.add(it) }
            SongId.repair(SongMatch(n.first, n.second, al, 100, SongId.alt(t.id)?.first ?: "", SongId.alt(t.id)?.second ?: ""), name, singer, album, t.title, t.durMs / 1000.0) { m, msg ->
                act.runOnUiThread {
                    idBusy = false; paintIdState()
                    if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                    if (MusicEngine.current?.id != t.id) return@runOnUiThread
                    if (m == null) {
                        quiet("🔎 $msg", 3000L)
                        if (name || singer) wrongSong()   // التصحيح النصي فشل: نرجع للتعرّف من الصوت
                        else { if (poster) wrongPoster(); if (lyrics) redoLyrics(true) }
                        return@runOnUiThread
                    }
                    forceNewPoster = poster
                    applyIdentified(t, m); SongId.setVerified(t.id, true); paintIdState()
                    quiet("✅ اتصحّح: " + nameLine(m) + (if (m.album.isNotBlank()) " · 💿 " + m.album else ""), 3500L)
                }
            }
            return
        }
        if (poster) wrongPoster()
        if (lyrics) redoLyrics(true)
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
            idBusy || autoBusy.contains(t.id) -> FastId.stage[t.id] ?: "🔎 المفاتيح بتسمع أجزاء مختلفة من الأغنية…"
            SongId.verified(t.id) -> "✔ الاسم مؤكد"
            SongId.known(t.id) -> "✅ اتعرفت قبل كده"
            else -> ""
        }
        if (idState.text.toString() != s) idState.text = s
        idState.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }
    /** (v294) مفيش مفاتيح جيميناي والاسم مش اسم أغنية: بنقول لليوزر يوصّل مفتاح أو يكتب الاسم بنفسه */
    private var pendingNoKeys = -1L
    private fun noKeysDialog(t: Track) {
        if (act.isDestroyed || act.isFinishing) return
        GAlert(act).setTitle("🔎 مش عارف اسم الأغنية دي")
            .setMessage("اسم الملف مش باين إنه اسم أغنية، ومفيش مفتاح جيميناي متوصّل عشان يسمعها ويعرفها. وصّل مفتاح، أو اكتب الاسم والمغني بنفسك.")
            .setPositiveButton("🔑 وصّل مفتاح") { _, _ ->
                act.showKeyGuide { k ->
                    val cur = Cfg.keys("keys")
                    if (k in cur || k in Cfg.keys("extra") || k in Cfg.keys("backup")) quiet("⚠ المفتاح ده موجود قبل كده", 2400L)
                    else { Cfg.p.edit().putString("keys", (cur + k).joinToString("\n")).apply(); quiet("✓ اتحفظ المفتاح — بعرّف على الأغنية…", 2400L) }
                    SongId.resetKnown(t.id)
                    if (MusicEngine.current?.id == t.id) autoIdentify(t)
                }
            }
            .setNeutralButton("✏️ اكتب بنفسي") { _, _ -> renameSong() }
            .setNegativeButton("لاحقًا", null).show()
    }
    private var curTrackId = -1L
    private val idHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var idRunnable: Runnable? = null
    /** (v315) التعرّف التلقائي بعد ما الأغنية تفضل شغّالة ~2.5ث؛ أي تغيير أغنية قبلها بيلغي الانتظار */
    private fun scheduleAutoId(t: Track) {
        idRunnable?.let { idHandler.removeCallbacks(it) }
        val r = Runnable { idRunnable = null; if (!act.isDestroyed && !act.isFinishing && MusicEngine.current?.id == t.id) autoIdentify(t) }
        idRunnable = r; idHandler.postDelayed(r, 2500L)
    }
    /** (v251) أغنية جديدة اتشغّلت ولسه ما اتعرفتش: جيميناي يسمع مقاطع منها ويغيّر الاسم تلقائي (مرة واحدة بس).
     *  (v294) الاسم المش اسم أغنية (موقع/رقم/من غير مغني) بيتعامل كأنه مش موجود؛ مفيش اتفاق ← أحسن تخمين بثقة 60%+ بدل ما نسيب اسم الموقع. */
    private fun autoIdentify(t: Track) {
        if (!SongId.autoOn() || SongId.searched(t.id) || autoBusy.contains(t.id)) return
        if (SongId.override(t.id) != null || SongId.verified(t.id)) return          // اتسمّت قبل كده أو اليوزر أكّدها: ما نلمسهاش
        if (SongId.tagsLookGood(t.title, t.artist)) return                          // اسم الملف/التاج كويس (عنوان + فنان): مفيش حاجة تتبعت لجيميناي
        FastId.resume(t.id)
        SongId.markTried(t.id)                                                       // نسجّل المحاولة من البداية: مفيش تكرار لو خرجت أو الطلب فشل
        if (Cfg.allMainKeys().isEmpty()) { if (fullOn) noKeysDialog(t) else pendingNoKeys = t.id; return }
        autoBusy.add(t.id); paintIdState()
        val start = maxOf(10.0, t.durMs / 2000.0)
        // (v314) الأول التعرّف السريع (مغني ← اسم ← ألبوم)؛ لو فشل نكمّل بالطريقة القديمة الموزّعة على المفاتيح
        FastId.run(act, t, start, { act.runOnUiThread { if (!act.isDestroyed && !act.isFinishing && MusicEngine.current?.id == t.id) { refreshNow(); paintIdState() } } }) { fm, fmsg ->
            act.runOnUiThread {
                FastId.clear(t.id)
                if (act.isDestroyed || act.isFinishing) { autoBusy.remove(t.id); return@runOnUiThread }
                if (fm == null && fmsg == FastId.CANCELLED) {   // (v315) اليوزر سابها في النص: نسيب الحالة «ما اتدوّرش» عشان يكمّل (من مرحلة الاسم) لما يرجع لها
                    autoBusy.remove(t.id); Cfg.put("mknown_${t.id}", ""); return@runOnUiThread
                }
                if (fm == null || SongId.verified(t.id)) { slowIdentify(t, start); return@runOnUiThread }
                autoBusy.remove(t.id)
                if (MusicEngine.current?.id == t.id) applyIdentified(t, fm, skipRepair = true)
                else {
                    val old = nameOf(t); SongId.saveOverride(t.id, fm); SongId.markKnown(t.id); Poster.forget(act, old.first, old.second); LyricsEngine.clearAll(act, t)
                    if (Cfg.str("noemb_${t.id}", "").isEmpty()) Cfg.put("noemb_${t.id}", "2")
                }
                paintIdState()
                quiet("🔎 اتعرفت: " + nameLine(fm), 3000L)
            }
        }
    }
    /** الطريقة القديمة (مقاطع 30ث موزّعة على كل المفاتيح + اتفاق مقطعين) — احتياطي لو التعرّف السريع فشل */
    private fun slowIdentify(t: Track, start: Double) {
        SongId.smart(act, t, start, emptyList()) { m, lst, msg ->
            act.runOnUiThread {
                autoBusy.remove(t.id)
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (lst.isNotEmpty()) lastOpts[t.id] = lst.toMutableList()
                fun goOn() { if (MusicEngine.current?.id == t.id) { refreshArts(); if (fullOn) loadLyrics() } }
                if (SongId.verified(t.id)) { paintIdState(); goOn(); return@runOnUiThread }
                var pick = m; var guess = false
                if (pick == null) { val top = lst.firstOrNull(); if (top != null && top.conf >= 60) { pick = top; guess = true } }
                if (pick == null) {                                                  // جيميناي معرفش: كمّل بالاسم الحالي من غير ما نطلب منك تكتب
                    SongId.markTried(t.id); paintIdState(); goOn()
                    if (msg.isNotBlank()) errNote("🔎 " + msg)
                    return@runOnUiThread
                }
                if (MusicEngine.current?.id == t.id) applyIdentified(t, pick)
                else {
                    val old = nameOf(t); SongId.saveOverride(t.id, pick); SongId.markKnown(t.id); Poster.forget(act, old.first, old.second); LyricsEngine.clearAll(act, t)
                    if (Cfg.str("noemb_${t.id}", "").isEmpty()) Cfg.put("noemb_${t.id}", "2")
                    if (pick.tLat.isBlank() && pick.aLat.isBlank() && SongId.hasArabic(pick.title + pick.artist)) SongId.latinize(pick.title, pick.artist) { lt, la -> SongId.saveAlt(t.id, lt, la) }   // (v300)
                }
                paintIdState()
                quiet("🔎 اتعرفت: " + nameLine(pick) + (if (guess) " (تخمين — دوس «المعلومات غلط» لو مش صح)" else ""), if (guess) 4200L else 3000L)
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
            val st0 = maxOf(10.0, t.durMs / 2000.0)
            // (v314) التعرّف السريع (مغني ← اسم ← ألبوم) الأول، والقديم الموزّع على المفاتيح احتياطي
            FastId.run(act, t, st0, { act.runOnUiThread { if (!act.isDestroyed && !act.isFinishing) { refreshNow() } } }) { fm, _ ->
                act.runOnUiThread {
                    FastId.clear(t.id)
                    if (act.isDestroyed || act.isFinishing) { idBusy = false; return@runOnUiThread }
                    if (fm != null) { idBusy = false; applyIdentified(t, fm, skipRepair = true); return@runOnUiThread }
                    SongId.smart(act, t, st0, emptyList()) { m, lst, _ ->   // (v293) نفس التعرّف الموزّع على المفاتيح
                        act.runOnUiThread {
                            idBusy = false
                            if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                            if (lst.isNotEmpty()) lastOpts[t.id] = lst.toMutableList()
                            if (m != null) applyIdentified(t, m) else if (fullOn) loadLyrics(force = true)
                        }
                    }
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
        Poster.albumHint(n.first, n.second, SongId.albumOf(id))
        Thread {
            val b = try { Poster.refetchSync(act, n.first, n.second, SongId.alt(id)) } catch (_: Throwable) { null }
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
    private fun applyIdentified(t: Track, m: SongMatch, skipRepair: Boolean = false) {
        val old = nameOf(t); val oldAlbum = SongId.albumOf(t.id)
        val keys = Cfg.allMainKeys().isNotEmpty()
        val needAlbum = m.album.isBlank() && keys && m.title.isNotBlank()
        val needLat = m.tLat.isBlank() && m.aLat.isBlank() && SongId.hasArabic(m.title + m.artist) && keys
        if (skipRepair || (!needAlbum && !needLat)) { applyNow(t, m, old, oldAlbum); return }   // (v314) skipRepair: التعرّف السريع جاب الألبوم والصيغة الإنجليزي بالفعل
        // (v305) الألبوم فاضي أو الاسم عربي من غير صيغة إنجليزي: الاسم يظهر على طول، وجيميناي يكمّل الألبوم/السنة/الصيغة الإنجليزي في طلب نصي واحد قبل ما نجيب الغلاف والكلمات
        SongId.saveOverride(t.id, m); refreshNow()
        quiet("🔎 بجيب الألبوم والمعلومات الكاملة…", 2200L)
        SongId.repair(m, false, false, false, "", t.durMs / 1000.0) { r, _ ->
            act.runOnUiThread {
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                val m2 = if (r == null) m else SongMatch(m.title, m.artist, m.album.ifBlank { r.album }, m.conf, m.tLat.ifBlank { r.tLat }, m.aLat.ifBlank { r.aLat }, m.votes)
                applyNow(t, m2, old, oldAlbum)
            }
        }
    }
    private fun applyNow(t: Track, m: SongMatch, old: Pair<String, String>, oldAlbum: String) {
        SongId.saveOverride(t.id, m); SongId.markKnown(t.id)
        // (v300) الاسم اتغيّر: الغلاف القديم (المدمج في الملف) كان على اسم غلط ← الغلاف الجديد بيتجاب من النت أوتوماتيك من غير «البوستر غلط»
        // (v305) وكمان لو الألبوم اتغيّر
        val changed = Poster.clean(old.first).trim().lowercase() != Poster.clean(m.title).trim().lowercase() || old.second.trim().lowercase() != m.artist.trim().lowercase() ||
            oldAlbum.trim().lowercase() != m.album.trim().lowercase()
        if (changed && Cfg.str("noemb_${t.id}", "").isEmpty()) Cfg.put("noemb_${t.id}", "2")
        Poster.resetSkip(m.title, m.artist)
        Poster.forget(act, old.first, old.second); Poster.forget(act, m.title, m.artist)
        if (forceNewPoster) { forceNewPoster = false; Poster.reject(act, m.title, m.artist); Cfg.put("noemb_${t.id}", "1") }   // «البوستر غلط» اتعلّم: نرفض الغلاف الحالي حتى لو الاسم والألبوم ما اتغيّروش
        Poster.albumHint(m.title, m.artist, m.album)
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
