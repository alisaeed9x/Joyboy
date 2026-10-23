package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v461/v462) شاشة التلفزيون على تقسيمة يوتيوب TV الرسمية:
 * شريط علوي (الشعار · بحث · ساعة) + ريل تنقّل جانبي + محتوى بصفوف أفقية
 * (كمّل مشاهدة · المفضلة · أحدث الفيديوهات) بكروت فيديو 16:9 بتكبر مع التركيز.
 * (v476) بقى لانشر كامل: بانر رئيسي + وصول سريع + تطبيقات الجهاز + زر Home.
 * بحث موحّد (مكتبة + أخيرة + يوتيوب) بصوت الريموت أو الكتابة من الموبايل (TypeServer)،
 * قفل PIN برقم سري على أقسام، تخصيص إظهار الصفوف، دفء ليلي، ووضع سكون.
 */
class TvActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var th: Theme
    private lateinit var content: LinearLayout
    private lateinit var saver: FrameLayout
    private lateinit var saverClock: android.widget.TextView
    private lateinit var clockTv: android.widget.TextView
    private lateinit var warmView: View
    private val h = Handler(Looper.getMainLooper())
    private var idleSec = 0
    private var favs: LinkedHashSet<String> = LinkedHashSet()
    private var lastScan: List<VideoItem> = emptyList()
    private var firstRail: View? = null

    private fun favLoad() { favs = LinkedHashSet(Cfg.str("tv_favs", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }) }
    private fun favSave() { Cfg.put("tv_favs", favs.joinToString(",")) }
    private fun favToggle(id: String, title: String) {
        if (!favs.remove(id)) { favs.add(id); Notice.show(this, "⭐ اتضافت للمفضلة: $title", 1600L) } else Notice.show(this, "⭐ اتشالت من المفضلة", 1400L)
        favSave(); showHome()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this); favLoad()
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        TypeServer.start(); TvWatchNext.sync(this)
        val rootF = FrameLayout(this).apply { setBackgroundColor(th.bg) }
        val rootCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        rootCol.addView(topBar(), LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        body.addView(rail(), LinearLayout.LayoutParams(ui.dp(92), -1))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(30))
        }
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        rootCol.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        rootF.addView(rootCol, FrameLayout.LayoutParams(-1, -1))
        warmView = View(this).apply { setBackgroundColor(0x2EFF8C1A); isClickable = false; isFocusable = false; visibility = if (Cfg.str("tv_warm", "0") == "1") View.VISIBLE else View.GONE }
        rootF.addView(warmView, FrameLayout.LayoutParams(-1, -1))
        saver = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        saverClock = ui.text("", 54f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val saverCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        saverCol.addView(saverClock, LinearLayout.LayoutParams(-1, -2))
        saverCol.addView(ui.text("📺 Subtitler — دوس أي زرار عشان تكمل", 15f, 0xFF9AA0A6.toInt()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        saver.addView(saverCol, FrameLayout.LayoutParams(-1, -1))
        rootF.addView(saver, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootF)
        TvFocus.attach(this, th.primary)   // (v490) حلقة تركيز واضحة على كل زرار/كارت في اللانشر
        showHome()
        h.post(tick)
        h.postDelayed({ if (!isDestroyed && !isFinishing) askDefaultHome() }, 1500L)   // (v485)
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())   // (v484) نظام 12 ساعة بتوقيت الجهاز
            if (::clockTv.isInitialized) clockTv.text = "🕐 $t"
            if (saver.visibility == View.VISIBLE) saverClock.text = t else { idleSec++; if (idleSec >= 180) showSaver() }
            h.postDelayed(this, 1000)
        }
    }

    private fun showSaver() { saver.visibility = View.VISIBLE; idleSec = 0 }
    private fun wake() { idleSec = 0; if (::saver.isInitialized && saver.visibility == View.VISIBLE) saver.visibility = View.GONE }
    override fun onUserInteraction() { super.onUserInteraction(); wake() }
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean { wake(); TvKill.watch(this, ev); return super.dispatchKeyEvent(ev) }   // (v486) 0 لمدة 5 ثواني = إلغاء تثبيت
    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }

    /** (v476) لانشر: زر Home وإحنا شغّالين = ارجع للرئيسية. Back عمره ما بيقفل اللانشر (بس بيرجّع للرئيسية). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) { wake(); showHome() }
    }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        wake()
        if (openFolder != null && foldersHost != null) { closeFolder(); return }   // (v490) من جوه فولدر: ارجع لقايمة الفولدرات
        showHome()
    }

    // ===== الشريط العلوي والريل (تقسيمة يوتيوب TV) =====
    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(8))
        }
        bar.addView(ui.text("📺 Subtitler", 22f, th.primary, true))
        val searchChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(ui.dp(16), ui.dp(9), ui.dp(16), ui.dp(9)); background = ui.box(th.surface, th.border, 22)
            setOnClickListener { showSearch() }
            setOnFocusChangeListener { v, f -> v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 22) }
        }
        searchChip.addView(ui.text("🔍  ابحث في الفيديوهات ويوتيوب…", 13.5f, th.muted))
        bar.addView(searchChip, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(18), 0, ui.dp(18), 0) })
        clockTv = ui.text("", 15f, th.text, true)
        bar.addView(clockTv)
        // (v483) أزرار اللانشر جنب الساعة: إعدادات البرنامج · إعدادات الأندرويد · تطبيقات السيستم · مشغّل افتراضي · إلغاء تثبيت
        bar.addView(barBtn("⚙️", "إعدادات البرنامج") { guardPin { openMain("settings") } })
        bar.addView(barBtn("🛠", "إعدادات الأندرويد") { openSystemSettings() })
        bar.addView(barBtn("📦", "تطبيقات السيستم") { openSystemApps() })
        bar.addView(barBtn("🏠", "خليه الافتراضي") { setAsDefaultLauncher() })
        bar.addView(barBtn("🗑", "إلغاء تثبيت اللانشر") { uninstallSelf() })
        return bar
    }

    /** (v483) زرار دائري صغير في الشريط العلوي بتركيز واضح + اسمه بيظهر تحته وهو متركّز */
    private fun barBtn(icon: String, label: String, onClick: () -> Unit): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            setPadding(ui.dp(11), ui.dp(7), ui.dp(11), ui.dp(7)); background = ui.box(th.surface, th.border, 22)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f ->
                v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 22, if (f) 3 else 1)
                v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(120).start()
                if (f) Notice.show(this@TvActivity, label, 1100L)
            }
        }
        b.addView(ui.text(icon, 18f, th.text).apply { gravity = Gravity.CENTER })
        b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(5), 0, ui.dp(5), 0) }
        b.contentDescription = label
        return b
    }

    /** يجرّب الـIntents بالترتيب لحد ما واحد يشتغل (أجهزة TV كتير بتشيل بعض شاشات الإعدادات) */
    private fun tryIntents(failMsg: String, vararg list: Intent) {
        for (i in list) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) { }
        }
        Notice.show(this, failMsg, 2400L)
    }

    private fun openSystemSettings() = tryIntents("⚠ إعدادات الأندرويد مش متاحة على الجهاز ده",
        Intent(android.provider.Settings.ACTION_SETTINGS),
        Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"))

    /** تطبيقات السيستم (مش تطبيقات البرنامج): صفحة «كل التطبيقات» في إعدادات الأندرويد عشان تمسح/توقف/تمسح الكاش */
    private fun openSystemApps() = tryIntents("⚠ شاشة التطبيقات مش متاحة على الجهاز ده",
        Intent(android.provider.Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS),
        Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.device.apps.AppsActivity"),
        Intent(android.provider.Settings.ACTION_SETTINGS))

    /** خلّي Subtitler المشغّل (Home) الافتراضي: RoleManager على أندرويد 10+، وإلا شاشة المشغّل الافتراضي في الإعدادات */
    @Suppress("DEPRECATION")
    private fun setAsDefaultLauncher() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                    if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) { Notice.show(this, "✅ Subtitler هو المشغّل الافتراضي بالفعل", 2200L); return }
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 83); return
                }
            }
        } catch (e: Throwable) { LogStore.err("TvHome:role", e) }
        tryIntents("⚠ اختار المشغّل الافتراضي من إعدادات الأندرويد ← التطبيقات الافتراضية",
            Intent(android.provider.Settings.ACTION_HOME_SETTINGS),
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))   // آخر حل: شاشة «اختار المشغّل» بتظهر لو في أكتر من واحد
    }

    /** (v485) هل Subtitler هو الشاشة الرئيسية (Home) الحالية؟ */
    private fun isDefaultHome(): Boolean = try {
        val ri = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ri?.activityInfo?.packageName == packageName
    } catch (_: Throwable) { false }

    /** (v485) أول ما اللانشر يفتح وهو مش الافتراضي: نافذة تسأل تخليه الافتراضي */
    private fun askDefaultHome() {
        if (Cfg.str("tv_ask_home", "1") == "0" || isDefaultHome()) return
        GAlert(this).setTitle("🏠 خلّي Subtitler المشغّل الافتراضي؟")
            .setMessage("دلوقتي شاشة التلفزيون الأصلية هي الرئيسية. تحب Subtitler يبقى هو الرئيسية؟ (تقدر ترجّعها لأي وقت، أو تمسح اللانشر بزرار 🗑 أو دوسة طويلة على 0 في الريموت)")
            .setPositiveButton("أيوه، خليه الافتراضي") { _, _ -> setAsDefaultLauncher() }
            .setNeutralButton("ما تسألنيش تاني") { _, _ -> Cfg.put("tv_ask_home", "0") }
            .setNegativeButton("بعدين", null).show()
    }

    /** إلغاء تثبيت Subtitler نفسه (لو حصلت مشكلة/بج في اللانشر) — بعد تأكيد */
    @Suppress("DEPRECATION")
    private fun uninstallSelf() {
        GAlert(this).setTitle("🗑 إلغاء تثبيت اللانشر").setMessage("هيتمسح Subtitler من الجهاز بالكامل (ببياناته). التلفزيون هيرجع للمشغّل الأصلي. متأكد؟")
            .setPositiveButton("امسح") { _, _ ->
                tryIntents("⚠ ما قدرتش أفتح شاشة إلغاء التثبيت",
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")),
                    Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName")))
            }.setNegativeButton("لأ", null).show()
    }

    private fun railBtn(icon: String, label: String, first: Boolean = false, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            setPadding(ui.dp(4), ui.dp(9), ui.dp(4), ui.dp(9)); background = ui.box(th.bg, Color.TRANSPARENT, 12)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> v.background = ui.box(if (f) th.card else th.bg, if (f) th.primary else Color.TRANSPARENT, 12) }
        }
        c.addView(ui.text(icon, 19f, th.text).apply { gravity = Gravity.CENTER })
        c.addView(ui.text(label, 10.5f, th.muted, true).apply { gravity = Gravity.CENTER })
        if (first) firstRail = c
        c.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(6), ui.dp(3), ui.dp(6), ui.dp(3)) }
        return c
    }

    private fun rail(): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, ui.dp(8), 0, ui.dp(8)); setBackgroundColor(th.card)
        }
        r.addView(railBtn("🏠", "الرئيسية", true) { showHome() })
        r.addView(railBtn("🔍", "بحث") { showSearch() })
        r.addView(railBtn("▶", "يوتيوب") { openMain("youtube") })
        r.addView(railBtn("🌐", "المتصفح") { openBrowser() })
        r.addView(railBtn("🎬", "الفيديوهات") { openMain("videos") })
        r.addView(railBtn("🎵", "الموسيقى") { openMain("music") })
        r.addView(railBtn("📂", "الملفات") { guardPin { startActivity(Intent(this, FileManagerActivity::class.java)) } })
        r.addView(railBtn("📋", "المهام") { openMain("tasks") })
        r.addView(railBtn("🎛", "الصفوف") { rowsDialog() })
        r.addView(railBtn("🔒", "قفل TV") { pinMenu() })
        r.addView(railBtn("🌡", "دفء ليلي") {
            val on = Cfg.str("tv_warm", "0") != "1"
            Cfg.put("tv_warm", if (on) "1" else "0"); warmView.visibility = if (on) View.VISIBLE else View.GONE
            Notice.show(this, if (on) "🌡 الدفء الليلي اشتغل" else "🌡 الدفء الليلي اتقفل", 1400L)
        })
        r.addView(railBtn("⚙️", "الإعدادات") { guardPin { openMain("settings") } })
        r.addView(railBtn("🌙", "نوم") { showSaver() })
        r.addView(railBtn("📱", "الموبايل") { openMain("videos") })
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(r) }
        return sc
    }

    // ===== الكروت والصفوف =====
    private fun card(wDp: Int, hDp: Int, fill: (LinearLayout) -> Unit, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9)); background = ui.box(th.surface, th.border, 14)
        }
        c.setOnFocusChangeListener { v, f ->
            v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 14)
            v.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(130).start()
            v.elevation = if (f) ui.dp(8).toFloat() else 0f
        }
        c.setOnClickListener { onClick() }
        if (onLong != null) c.setOnLongClickListener { onLong(); true }
        fill(c)
        c.layoutParams = LinearLayout.LayoutParams(ui.dp(wDp), ui.dp(hDp)).apply { setMargins(ui.dp(7), ui.dp(6), ui.dp(7), ui.dp(6)) }
        return c
    }

    private fun rowInto(parent: LinearLayout, title: String, cards: List<View>) {
        if (cards.isEmpty()) return
        parent.addView(ui.text(title, 17f, th.primary, true).apply { setPadding(ui.dp(6), ui.dp(14), 0, ui.dp(2)) })
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        cards.forEach { inner.addView(it) }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(inner) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun recents(): List<Recent> = Recents.parse(try { File(filesDir, "recent.json").readText() } catch (_: Exception) { "" })

    private fun recentCard(r: Recent): View = card(252, 120, { c ->
        c.addView(ui.text(r.title, 14.5f, th.text, true).apply { maxLines = 2 })
        c.addView(ui.text("⏸ وقف عند " + PlayerLogic.clock((r.posSec * 1000).toLong()) + " · تغطية " + r.percent + "%" + if (favs.contains(r.id)) " · ⭐" else "", 11.5f, th.muted))
        val track = FrameLayout(this).apply { background = ui.box(th.border, th.border, 4) }
        track.addView(View(this).apply { setBackgroundColor(th.primary) }, FrameLayout.LayoutParams(ui.dp((232 * r.percent.coerceIn(0, 100) / 100).coerceAtLeast(0)), ui.dp(5)))
        c.addView(track, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(8) })
    }, { playRecent(r) }, { favToggle(r.id, r.title) })

    private fun videoCard(v: VideoItem): View {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.border) }
        val c = card(232, 172, { cc ->
            cc.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(106)))
            cc.addView(ui.text(v.title + if (favs.contains(v.videoId)) " ⭐" else "", 12.5f, th.text, true).apply { maxLines = 2; setPadding(0, ui.dp(5), 0, 0) })
            cc.addView(ui.text("📁 " + v.folderName + if (v.durMs > 0) " · " + PlayerLogic.clock(v.durMs) else "", 10.5f, th.muted))
        }, { playFile(v.uri, v.landscape) }, { favToggle(v.videoId, v.title) })
        Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } }
        return c
    }

    // ===== (v476) لانشر قوي: بانر رئيسي + وصول سريع + تطبيقات الجهاز + كروت =====
    private var heroImg: ImageView? = null
    private var heroUri: String = ""
    private var heroBtn: View? = null

    private fun onPrimary(): Int = if (Theme.lum(th.primary) > 0.55) Color.BLACK else Color.WHITE

    /** زرار حبّة (pill) كبير بتركيز واضح: حدّ سميك + تكبير */
    private fun pill(t: String, primary: Boolean = false, f: () -> Unit): View {
        val b = LinearLayout(this).apply {
            isFocusable = true; isClickable = true; gravity = Gravity.CENTER
            setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12))
            background = ui.box(if (primary) th.primary else th.surface, if (primary) th.primary else th.border, 28)
            setOnClickListener { f() }
            setOnFocusChangeListener { v, hasF ->
                v.background = ui.box(if (primary) th.primary else if (hasF) th.card else th.surface, if (hasF) th.text else if (primary) th.primary else th.border, 28, if (hasF) 3 else 1)
                v.animate().scaleX(if (hasF) 1.07f else 1f).scaleY(if (hasF) 1.07f else 1f).setDuration(120).start()
            }
        }
        b.addView(ui.text(t, 15f, if (primary) onPrimary() else th.text, true))
        b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) }
        return b
    }

    private fun hero(recs: List<Recent>): View {
        val r = recs.firstOrNull()
        val box = FrameLayout(this).apply { background = ui.box(th.card, th.border, 24); clipToOutline = true }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f }
        heroImg = iv; heroUri = r?.uri ?: ""
        box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(30), ui.dp(22), ui.dp(30), ui.dp(18))
        }
        if (r != null) {
            col.addView(ui.text("▶ كمّل من حيث وقفت", 14f, th.primary, true))
            col.addView(ui.text(r.title, 28f, th.text, true).apply { maxLines = 2; setPadding(0, ui.dp(4), 0, ui.dp(2)) })
            col.addView(ui.text("⏸ " + PlayerLogic.clock((r.posSec * 1000).toLong()) + " · " + r.percent + "%", 14f, th.muted))
            val track = FrameLayout(this).apply { background = ui.box(th.border, th.border, 4) }
            track.addView(View(this).apply { setBackgroundColor(th.primary) }, FrameLayout.LayoutParams(ui.dp((320 * r.percent.coerceIn(0, 100) / 100).coerceAtLeast(0)), ui.dp(6)))
            col.addView(track, LinearLayout.LayoutParams(ui.dp(320), ui.dp(6)).apply { topMargin = ui.dp(10) })
        } else {
            col.addView(ui.text("أهلاً بيك 👋", 30f, th.text, true))
            col.addView(ui.text("ابدأ بفيديو من مكتبتك أو افتح يوتيوب", 16f, th.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(16), 0, 0) }
        val first = if (r != null) pill("▶ كمّل", true) { playRecent(r) } else pill("🎬 الفيديوهات", true) { openMain("videos") }
        heroBtn = first
        btns.addView(first)
        btns.addView(pill("🔍 بحث") { showSearch() })
        btns.addView(pill("▶ يوتيوب") { openMain("youtube") })
        col.addView(btns)
        box.addView(col, FrameLayout.LayoutParams(-1, -1))
        box.layoutParams = LinearLayout.LayoutParams(-1, ui.dp(250)).apply { setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(4)) }
        return box
    }

    private fun tile(icon: String, label: String, f: () -> Unit): View = card(168, 104, { c ->
        c.gravity = Gravity.CENTER
        c.addView(ui.text(icon, 30f, th.text).apply { gravity = Gravity.CENTER })
        c.addView(ui.text(label, 14f, th.text, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
    }, f)

    private fun quickTiles(): List<View> = listOf(
        tile("▶", "يوتيوب") { openMain("youtube") },
        tile("🌐", "المتصفح") { openBrowser() },
        tile("🎬", "الفيديوهات") { openMain("videos") },
        tile("🎵", "الموسيقى") { openMain("music") },
        tile("📂", "الملفات") { guardPin { startActivity(Intent(this, FileManagerActivity::class.java)) } },
        tile("📋", "المهام") { openMain("tasks") },
        tile("⚙️", "الإعدادات") { guardPin { openMain("settings") } }
    )

    /** تطبيقات الجهاز (Leanback الأول، وإلا اللانشر العادي) — من غير QUERY_ALL_PACKAGES (انظر <queries> في الـ Manifest) */
    private fun appCards(): List<View> {
        val pm = packageManager
        fun q(cat: String): List<android.content.pm.ResolveInfo> = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) } catch (_: Throwable) { emptyList() }
        var l = q(Intent.CATEGORY_LEANBACK_LAUNCHER)
        if (l.isEmpty()) l = q(Intent.CATEGORY_LAUNCHER)
        return l.filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }.take(40).map { appCard(it) }
    }

    private fun appCard(ri: android.content.pm.ResolveInfo): View {
        val pm = packageManager; val pkg = ri.activityInfo.packageName
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        try { iv.setImageDrawable(ri.activityInfo.loadBanner(pm) ?: ri.loadIcon(pm)) } catch (_: Throwable) { }
        return card(206, 142, { c ->
            c.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(84)))
            c.addView(ui.text(ri.loadLabel(pm).toString(), 12.5f, th.text, true).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, ui.dp(5), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        }, { launchApp(pkg) })
    }

    private fun launchApp(pkg: String) {
        bumpUse(pkg)
        try {
            val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else Notice.show(this, "⚠ التطبيق مش متاح", 1800L)
        } catch (e: Exception) { LogStore.err("Tv:app", e); Notice.show(this, "⚠ ما قدرتش أفتح التطبيق", 2000L) }
    }

    // ===== (v484) الشاشة الرئيسية الجديدة: دواير التطبيقات · آخر المشغّل · الفولدرات · اشتراكات يوتيوب · تخصيص =====
    private val SYS_BLOCK = listOf("settings", "setup", "provision", "wizard", "packageinstaller", "permissioncontroller", "inputmethod", "bugreport",
        "certinstaller", "systemui", "onetimeinitializer", "keychain", "companiondevice", "tvlauncher", "launcher", "oem", "backuprestore",
        "diagnostic", "tvrecommendations", "sharedstorage", "cts", "factory", "wfd", "miracast", "dreams", "tvsystem")

    /** تطبيقات أساسية بس: بنشيل تطبيقات السيستم الداخلية (الإعدادات/الإعداد الأول/…) إلا لو فعّلت «إظهار تطبيقات السيستم» من التخصيص */
    private fun essentialApps(): List<android.content.pm.ResolveInfo> {
        val pm = packageManager
        fun q(cat: String): List<android.content.pm.ResolveInfo> = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) } catch (_: Throwable) { emptyList() }
        val all = (q(Intent.CATEGORY_LEANBACK_LAUNCHER) + q(Intent.CATEGORY_LAUNCHER)).distinctBy { it.activityInfo.packageName }
        val showSys = Cfg.str("tv_apps_sys", "0") == "1"
        return all.filter { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) return@filter false
            if (showSys) return@filter true
            val fl = ri.activityInfo.applicationInfo.flags
            val pureSys = (fl and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && (fl and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            !(pureSys && SYS_BLOCK.any { pkg.lowercase().contains(it) })
        }.sortedWith(compareByDescending<android.content.pm.ResolveInfo> { appUse()[it.activityInfo.packageName] ?: 0 }
            .thenBy { it.loadLabel(pm).toString().lowercase() })   // (v485) الأكتر استخدامًا الأول
    }

    /** عدّاد فتح كل تطبيق من اللانشر (pkg=عدد) */
    private fun appUse(): Map<String, Int> = Cfg.str("tv_app_use", "").lineSequence().mapNotNull { l ->
        val i = l.lastIndexOf('='); if (i <= 0) null else l.substring(0, i) to (l.substring(i + 1).toIntOrNull() ?: 0)
    }.toMap()
    private fun bumpUse(pkg: String) {
        val m = LinkedHashMap(appUse()); m[pkg] = (m[pkg] ?: 0) + 1
        Cfg.put("tv_app_use", m.entries.joinToString("\n") { it.key + "=" + it.value })
    }

    /** دايرة تطبيق: اللي عليها التركيز بتكبر وإسمها بيتلوّن، والباقي أصغر (زي دواير الألبومات في مشغّل الموسيقى) */
    private fun appCircle(ri: android.content.pm.ResolveInfo): View {
        val pm = packageManager; val pkg = ri.activityInfo.packageName
        val dsz = 76
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(th.surface); setStroke(ui.dp(2), th.border) }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        try { iv.setImageDrawable(ri.loadIcon(pm)) } catch (_: Throwable) { }
        val name = ui.text(ri.loadLabel(pm).toString(), 11.5f, th.muted, true).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; isClickable = true
            clipChildren = false; clipToPadding = false
            setOnClickListener { launchApp(pkg) }
            setOnLongClickListener { openAppInfo(pkg); true }
            setOnFocusChangeListener { v, f ->
                (iv.background as android.graphics.drawable.GradientDrawable).setStroke(ui.dp(if (f) 4 else 2), if (f) th.primary else th.border)
                name.setTextColor(if (f) th.text else th.muted)
                v.animate().scaleX(if (f) 1.38f else 1f).scaleY(if (f) 1.38f else 1f).setDuration(140).start()
                v.elevation = if (f) ui.dp(10).toFloat() else 0f
            }
        }
        c.addView(iv, LinearLayout.LayoutParams(ui.dp(dsz), ui.dp(dsz)))
        c.addView(name, LinearLayout.LayoutParams(ui.dp(98), -2))
        c.layoutParams = LinearLayout.LayoutParams(ui.dp(104), -2).apply { setMargins(ui.dp(8), ui.dp(14), ui.dp(8), ui.dp(14)) }
        return c
    }

    private fun openAppInfo(pkg: String) = tryIntents("⚠ ما قدرتش أفتح معلومات التطبيق",
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    private fun hrow(items: List<View>): View {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
        items.forEach { inner.addView(it) }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(inner) }
    }
    private fun sectionTitle(t: String): View = ui.text(t, 17f, th.primary, true).apply { setPadding(ui.dp(6), ui.dp(14), 0, ui.dp(2)) }
    private fun host(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false }

    private fun histCard(r: Recent, byUri: Map<String, VideoItem>): View {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.border) }
        val vi = byUri[r.uri]
        val c = card(232, 176, { cc ->
            cc.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(100)))
            cc.addView(ui.text(r.title + if (favs.contains(r.id)) " ⭐" else "", 12.5f, th.text, true).apply { maxLines = 2; setPadding(0, ui.dp(5), 0, 0) })
            val track = FrameLayout(this).apply { background = ui.box(th.border, th.border, 4) }
            track.addView(View(this).apply { setBackgroundColor(th.primary) }, FrameLayout.LayoutParams(ui.dp((200 * r.percent.coerceIn(0, 100) / 100).coerceAtLeast(0)), ui.dp(4)))
            cc.addView(track, LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(6) })
        }, { playRecent(r) }, { favToggle(r.id, r.title) })
        if (vi != null) Thumbs.load(this, vi) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } }
        return c
    }

    /** آخر الفيديوهات المشغّلة (السجل): الأحدث أول واحد، والسهم بيمشي للأقدم فالأقدم */
    private fun fillHistory(hh: LinearLayout) {
        hh.removeAllViews()
        val recs = recents().take(40)
        if (recs.isEmpty()) return
        val byUri = lastScan.associateBy { it.uri }
        hh.addView(sectionTitle("🕘 آخر الفيديوهات المشغّلة"))
        hh.addView(hrow(recs.map { histCard(it, byUri) }))
    }

    private fun foldSort() = Cfg.str("tv_fold_sort", VideoLib.SORT_NAME)
    private fun vidSort() = Cfg.str("tv_vid_sort", VideoLib.SORT_NAME)

    /** قايمة الفيديوهات مترتبة فولدرات، وزرار ⋮ لترتيب الفولدرات وترتيب الفيديوهات جواها */
    private var openFolder: String? = null      // (v490) مفتاح الفولدر المفتوح (null = قايمة الفولدرات)
    private var foldersHost: LinearLayout? = null

    private fun closeFolder() {
        val back = openFolder; openFolder = null
        val fh = foldersHost ?: return
        fillFolders(fh)
        // رجّع التركيز على الفولدر اللي كنت فيه
        fh.post { try { (fh.findViewWithTag<View>("fold:$back") ?: fh.findViewWithTag<View>("fold:first"))?.requestFocus() } catch (_: Throwable) {} }
    }

    private fun gridInto(parent: LinearLayout, cards: List<View>, perRow: Int) {
        var k = 0
        while (k < cards.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false }
            for (c in cards.subList(k, minOf(k + perRow, cards.size))) row.addView(c)
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
            k += perRow
        }
    }

    /** (v490) كارت فولدر: صورة أول فيديو + الاسم + عدد الفيديوهات */
    private fun folderCard(f: FolderItem, first: Boolean): View {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.border) }
        val c = card(190, 150, { cc ->
            cc.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(84)))
            cc.addView(ui.text("📁 " + f.name, 13f, th.text, true).apply { maxLines = 1; setPadding(0, ui.dp(5), 0, 0) })
            cc.addView(ui.text(f.videos.size.toString() + " فيديو", 11f, th.muted))
        }, { openFolder = f.key; foldersHost?.let { fillFolders(it) } })
        c.tag = if (first) "fold:first" else "fold:" + f.key
        f.videos.firstOrNull()?.let { v -> Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } }
        return c
    }

    /** الفيديوهات: الأول الفولدرات كلها جنب بعض، ولما تدخل فولدر تظهر فيديوهاته */
    private fun fillFolders(fh: LinearLayout) {
        foldersHost = fh
        fh.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        head.addView(sectionTitle("🎬 الفيديوهات"))
        head.addView(pill("⋮ ترتيب") { sortMenu(fh) }.apply { setPadding(ui.dp(14), ui.dp(5), ui.dp(14), ui.dp(5)) })
        fh.addView(head)
        if (!VideoScan.hasPermission(this)) {
            fh.addView(ui.text("محتاج إذن الوصول للفيديوهات — افتح «الفيديوهات» مرة واحدة وادّي الإذن", 12.5f, th.muted).apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) })
            return
        }
        if (lastScan.isEmpty()) { fh.addView(ui.text("⏳ بقرا الفيديوهات…", 12.5f, th.muted).apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) }); return }
        val all = VideoLib.sortFolders(VideoLib.group(lastScan), foldSort())
        val open = openFolder?.let { k -> all.firstOrNull { it.key == k } }
        if (open == null) {
            openFolder = null
            val cards = all.take(60).mapIndexed { i, f -> folderCard(f, i == 0) }
            gridInto(fh, cards, 4)
            return
        }
        val back = pill("→ رجوع للفولدرات") { closeFolder() }.apply { setPadding(ui.dp(14), ui.dp(5), ui.dp(14), ui.dp(5)) }
        fh.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(back); addView(ui.text("📁 ${open.name} · ${open.videos.size}", 16f, th.primary, true).apply { setPadding(ui.dp(12), 0, 0, 0) })
        })
        val vids = VideoLib.sortVideos(open.videos, vidSort())
        val vcards = vids.take(120).map { videoCard(it) }
        gridInto(fh, vcards, 3)
        if (vids.size > 120) fh.addView(LinearLayout(this).apply { addView(tile("📂", "كل الـ ${vids.size}") { openMain("videos") }) })
        fh.post { try { vcards.firstOrNull()?.requestFocus() } catch (_: Throwable) {} }
    }

    private fun sortMenu(fh: LinearLayout) {
        fun lab(k: String) = VideoLib.sortLabelOf(k)
        val items = arrayOf("📁 ترتيب الفولدرات: " + lab(foldSort()), "🎞 ترتيب الفيديوهات جوه الفولدر: " + lab(vidSort()))
        GAlert(this).setTitle("⋮ الترتيب").setItems(items) { _, which ->
            val key = if (which == 0) "tv_fold_sort" else "tv_vid_sort"
            val cur = if (which == 0) foldSort() else vidSort()
            val labels = VideoLib.sortLabels.map { (if (it.first == cur) "✓ " else "") + it.second }.toTypedArray()
            GAlert(this).setTitle(if (which == 0) "📁 رتّب الفولدرات حسب" else "🎞 رتّب الفيديوهات جوه حسب")
                .setItems(labels) { _, i -> Cfg.put(key, VideoLib.sortLabels[i].first); fillFolders(fh) }
                .setNegativeButton("إلغاء", null).show()
        }.setNegativeButton("إغلاق", null).show()
    }

    /** أحدث فيديوهات اشتراكات يوتيوب (لو مسجّل دخول) */
    private fun fillYt(yh: LinearLayout) {
        if (!YtAccount.loggedIn()) return
        Thread {
            val items = try {
                val subs = YtStore.read(YtStore.SUBS)
                if (subs.isEmpty()) emptyList() else (YtRss.cachedFeed(subs) ?: YtRss.feed(subs, false, true)).items.filter { it.kind == 0 }.take(20)
            } catch (e: Throwable) { LogStore.err("TvHome:yt", e); emptyList() }
            runOnUiThread {
                if (isDestroyed || items.isEmpty()) return@runOnUiThread
                yh.removeAllViews()
                yh.addView(sectionTitle("▶ أحدث اشتراكاتك على يوتيوب"))
                yh.addView(hrow(items.map { ytCard(it) }))
            }
        }.start()
    }

    private fun ytCard(v: NpItem): View {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.border) }
        if (v.thumb.isNotBlank()) try { com.bumptech.glide.Glide.with(this).load(v.thumb).centerCrop().into(iv) } catch (_: Throwable) { }
        return card(232, 176, { cc ->
            cc.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(100)))
            cc.addView(ui.text(v.title, 12.5f, th.text, true).apply { maxLines = 2; setPadding(0, ui.dp(5), 0, 0) })
            cc.addView(ui.text(v.channel + if (v.age.isNotBlank()) " · " + v.age else "", 10.5f, th.muted).apply { maxLines = 1 })
        }, { openYt(v.id) })
    }

    private fun openYt(id: String) {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("force_phone", true); putExtra("from_tv", true); putExtra("tab", "youtube"); putExtra("yt_open", id)
            })
        } catch (e: Throwable) { LogStore.err("TvHome:ytopen", e); Notice.show(this, "⚠ ما قدرتش أفتح الفيديو", 2000L) }
    }

    private fun showHome() {
        content.removeAllViews(); heroBtn = null; heroImg = null
        try { YtAccount.init(this); YtRss.attach(this) } catch (_: Throwable) { }
        val hidden = Cfg.str("tv_rows_hide2", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // 1) دواير التطبيقات
        if ("apps" !in hidden) {
            val circles = essentialApps().map { appCircle(it) }
            if (circles.isNotEmpty()) {
                content.addView(sectionTitle("📲 تطبيقاتك"))
                content.addView(hrow(circles))
                heroBtn = circles.first()
            }
        }
        // 2) آخر المشغّل  3) الفولدرات  4) اشتراكات يوتيوب (بتتملي أول ما الفحص يخلص)
        val hh = host(); val fh = host(); val yh = host()
        if ("hist" !in hidden) { content.addView(hh); fillHistory(hh) }
        if ("vids" !in hidden) {
            content.addView(fh); fillFolders(fh)
            if (VideoScan.hasPermission(this)) Thread {
                val items = try { VideoScan.scan(this) } catch (_: Exception) { emptyList() }
                lastScan = items
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if ("hist" !in hidden) fillHistory(hh)   // دلوقتي نعرف نجيب مصغّرات السجل
                    fillFolders(fh)
                }
            }.start()
        }
        if ("yt" !in hidden) { content.addView(yh); fillYt(yh) }
        // 5) تخصيص
        content.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(18), 0, ui.dp(24)); addView(pill("🎛 تخصيص") { rowsDialog() }) })
        (heroBtn ?: firstRail)?.let { f -> content.post { try { f.requestFocus() } catch (_: Exception) {} } }
    }

    // ===== بحث موحّد: نص · صوت · كتابة من الموبايل =====
    private fun showSearch(prefill: String = "") {
        content.removeAllViews()
        content.addView(ui.text("🔍 بحث موحّد — مكتبتك وآخر المشاهدة ويوتيوب", 18f, th.primary, true).apply { setPadding(ui.dp(6), ui.dp(10), 0, ui.dp(6)) })
        val et = EditText(this).apply {
            setText(prefill); setSingleLine(); textSize = 16f; setTextColor(th.text); setHintTextColor(th.muted)
            hint = "اكتب اسم فيديو أو حلقة…"; setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.box(th.surface, th.border, 14)
        }
        content.addView(et, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun run(q: String) {
            results.removeAllViews()
            val ql = q.trim().lowercase()
            if (ql.isEmpty()) return
            val recHits = recents().filter { it.title.lowercase().contains(ql) }
            val libHits = lastScan.filter { it.title.lowercase().contains(ql) || it.folderName.lowercase().contains(ql) }
            rowInto(results, "📺 من آخر المشاهدة", recHits.take(12).map { recentCard(it) })
            rowInto(results, "🎬 من المكتبة", libHits.take(18).map { videoCard(it) })
            if (recHits.isEmpty() && libHits.isEmpty()) results.addView(ui.text("مفيش نتائج محلية لـ «$q»", 13f, th.muted).apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(4)) })
            rowInto(results, "▶ على يوتيوب", listOf(card(252, 92, { c ->
                c.addView(ui.text("ابحث عن «$q» في يوتيوب البرنامج", 14f, th.text, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -1))
            }, { openMain("youtube") })))
        }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        fun sbtn(t: String, f: () -> Unit): View {
            val b = LinearLayout(this).apply {
                isFocusable = true; isClickable = true; gravity = Gravity.CENTER
                setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.box(th.surface, th.border, 18)
                setOnClickListener { f() }; addView(ui.text(t, 13.5f, th.text, true))
            }
            b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) }
            return b
        }
        btns.addView(sbtn("🔍 ابحث") { run(et.text.toString()) })
        btns.addView(sbtn("🎤 بحث صوتي") { startVoice { q -> et.setText(q); run(q) } })
        btns.addView(sbtn("📱 اكتب من الموبايل") { phoneTypeDialog { q -> et.setText(q); run(q) } })
        content.addView(btns)
        content.addView(results)
        if (prefill.isNotEmpty()) run(prefill)
        content.post { try { et.requestFocus() } catch (_: Exception) {} }
    }

    private var voiceCb: ((String) -> Unit)? = null
    private fun startVoice(cb: (String) -> Unit) {
        voiceCb = cb
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "قول اسم الفيديو…")
            }
            startActivityForResult(i, 77)
        } catch (e: Exception) { LogStore.err("Tv:voice", e); Notice.show(this, "⚠ البحث الصوتي مش متاح على الجهاز ده", 2200L) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 77 && res == RESULT_OK) {
            val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            voiceCb?.invoke(q); voiceCb = null
        }
    }

    private fun phoneTypeDialog(apply: (String) -> Unit) {
        val url = TypeServer.url()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)) }
        col.addView(ui.text("امسح الكود بكاميرا موبايلك (أو افتح العنوان في المتصفح — لازم نفس الواي فاي) واكتب هناك:", 13f, th.text))
        if (url.isNotEmpty()) {
            val qr = QrCode.bitmap(url, ui.dp(190))
            if (qr != null) {
                val iv = ImageView(this).apply { setImageBitmap(qr); setBackgroundColor(Color.WHITE); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
                col.addView(iv, LinearLayout.LayoutParams(ui.dp(190), ui.dp(190)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(8) })
            }
        }
        col.addView(ui.text(if (url.isEmpty()) "⚠ مش متصل بواي فاي" else url, 17f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) })
        val got = ui.text(if (TypeServer.lastText.isEmpty()) "لسه ماوصلش حاجة…" else "وصل: " + TypeServer.lastText, 13f, th.muted)
        col.addView(got)
        val dlg = GAlert(this).setTitle("📱 اكتب من الموبايل").setView(col)
            .setPositiveButton("استخدم اللي وصل") { _, _ -> if (TypeServer.lastText.isNotEmpty()) apply(TypeServer.lastText) }
            .setNegativeButton("إغلاق", null).create()
        dlg.show()
        val poll = object : Runnable { override fun run() { if (!dlg.isShowing) return; got.text = if (TypeServer.lastText.isEmpty()) "لسه ماوصلش حاجة…" else "وصل: " + TypeServer.lastText; h.postDelayed(this, 1200) } }
        h.postDelayed(poll, 1200)
    }

    // ===== تخصيص الصفوف وقفل PIN =====
    private fun rowsDialog() {
        val ids = listOf("apps" to "📲 دواير التطبيقات", "hist" to "🕘 آخر الفيديوهات المشغّلة", "vids" to "🎬 الفيديوهات (فولدرات)", "yt" to "▶ اشتراكات يوتيوب", "sys" to "⚙ إظهار تطبيقات السيستم كمان")
        val hidden = Cfg.str("tv_rows_hide2", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        var sysOn = Cfg.str("tv_apps_sys", "0") == "1"
        val labels = ids.map { it.second }.toTypedArray()
        val checked = BooleanArray(ids.size) { if (ids[it].first == "sys") sysOn else ids[it].first !in hidden }
        GAlert(this).setTitle("🎛 تخصيص الشاشة الرئيسية")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val k = ids[which].first
                if (k == "sys") sysOn = isChecked else if (isChecked) hidden.remove(k) else hidden.add(k)
            }
            .setPositiveButton("تمام") { _, _ -> Cfg.put("tv_rows_hide2", hidden.joinToString(",")); Cfg.put("tv_apps_sys", if (sysOn) "1" else "0"); showHome() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun sha(s: String): String = try {
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (_: Exception) { s }

    private fun guardPin(next: () -> Unit) {
        if (Cfg.str("tv_pin", "").isEmpty()) { next(); return }
        numpad("🔒 اكتب الرقم السري") { pin -> if (sha(pin) == Cfg.str("tv_pin", "")) next() else Notice.show(this, "⚠ الرقم غلط", 1800L) }
    }

    private fun pinMenu() {
        if (Cfg.str("tv_pin", "").isEmpty()) {
            numpad("🔒 اختار رقم سري جديد (4 أرقام أو أكتر)") { p1 ->
                numpad("🔒 اكتبه تاني للتأكيد") { p2 ->
                    if (p1 == p2 && p1.length >= 4) { Cfg.put("tv_pin", sha(p1)); Notice.show(this, "🔒 القفل اتفعّل", 1800L) }
                    else Notice.show(this, "⚠ الرقمين مش زي بعض أو قصيرين", 2200L)
                }
            }
        } else {
            GAlert(this).setTitle("🔒 قفل TV").setMessage("القفل مفعّل على «الملفات» و«الإعدادات».")
                .setPositiveButton("إلغاء القفل") { _, _ -> guardPin { Cfg.put("tv_pin", ""); Notice.show(this, "🔓 القفل اتشال", 1600L) } }
                .setNegativeButton("سيبه", null).show()
        }
    }

    private fun numpad(title: String, done: (String) -> Unit) {
        var pin = ""
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(10)) }
        val dots = ui.text("○ ○ ○ ○", 22f, th.primary, true).apply { gravity = Gravity.CENTER }
        col.addView(dots)
        fun paintDots() { dots.text = if (pin.isEmpty()) "○ ○ ○ ○" else pin.map { "●" }.joinToString(" ") }
        lateinit var dlg: android.app.Dialog
        fun press(d: String) {
            when (d) {
                "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                "✓" -> { if (pin.isNotEmpty()) { dlg.dismiss(); done(pin) } ; return }
                else -> if (pin.length < 8) pin += d
            }
            paintDots()
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        for (r in 0 until 4) {
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            for (k in 0 until 3) {
                val key = keys[r * 3 + k]
                val b = ui.text(key, 19f, th.text, true).apply {
                    gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                    background = ui.box(th.surface, th.border, 12)
                    setOnClickListener { press(key) }
                    setOnFocusChangeListener { v, f -> v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 12) }
                }
                rowL.addView(b, LinearLayout.LayoutParams(ui.dp(64), ui.dp(52)).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            }
            col.addView(rowL)
        }
        dlg = GAlert(this).setTitle(title).setView(col).setNegativeButton("إلغاء", null).create()
        dlg.show()
    }

    // ===== تشغيل وفتح الأقسام =====
    /** (v480 م6) المتصفح بمؤشر الريموت — Back منه بيرجّع للرئيسية */
    private fun openBrowser() {
        try { startActivity(Intent(this, BrowserActivity::class.java).putExtra("from_tv", true)) } catch (e: Throwable) { LogStore.err("TvHome:browser", e) }
    }

    private fun openMain(tab: String?) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("force_phone", true)
            putExtra("from_tv", true)   // (v467) عشان رجوع Main من الزيارة دي يرجّع لواجهة التلفزيون
            if (tab != null) putExtra("tab", tab)
        })
    }

    private fun playFile(uriStr: String, landscape: Boolean) {
        try {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                data = Uri.parse(uriStr); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("landscape", landscape); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
            })
        } catch (e: Exception) { LogStore.err("Tv:play", e); Notice.show(this, "⚠ ما قدرتش أشغّل الملف ده", 2200L) }
    }

    private fun playRecent(r: Recent) {
        if (r.uri.isNotEmpty()) playFile(r.uri, true)
        else if (r.url.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("force_phone", true))
    }
}
