package com.tttt.subtitler

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v599 م4) السكِن 1 «Hub» (شكل Google TV):
 *  • التنقل: تابات فوق + صفوف (نفس هيكل كلاسيك المجرّب) و«كمّل» بانر كبير أول حاجة فوق الصفوف.
 *  • الفولدرات: كروت صفوف (TvFolderCard) برموز Hub (استدارة أكبر · تركيز بطلوع).
 *  • الإعدادات: لوحة سريعة جانبية بزرار Menu (على أي عنصر مالوش قايمة سياق)، فيها مدخل واحد للسكنات والسمات، وباقي اختصارات المظهر.
 *  • اختيار السكن/السمة موحّد: يبدأ بالسكن ثم يختار المستخدم السمة؛ التركيبات المحفوظة في مدير منفصل.
 *  • «شغّل آخر فيديو»: OK واحدة من أول الشاشة (البانر هو أول عنصر بيتركّز عليه).
 * باقي الخانات false = رسم كلاسيك المدمج؛ فمفيش حاجة اتكسرت.
 */
object TvSkinHub : TvSkin {
    override val id = "hub"
    override val name = "Hub (شكل Google TV)"
    override val themeIds = listOf("default", "mxd", "eye_night", "light", "win11d")
    override val tokens = TvTokens(radiusCard = 18, radiusThumb = 14, radiusPill = 30, gap = 10, pad = 14,
        textTitle = 19f, textBody = 14f, textCaption = 12.5f, focusStyle = TvFocusStyle.LIFT, focusScale = 1.08f, focusMs = 150L, pageMs = 240L)

    override fun folderCard(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit): View =
        TvFolderCard.build(h, f, size, onOpen, tokens)

    // ===== التابات (رئيسية · إشعارات · سمات) =====
    /** شريط تابات Hub: بيتعرض فوق الرئيسية وفوق صفحتي الإشعارات والسمات. active: 0 رئيسية · 1 إشعارات · 2 سمات */
    internal fun tabStrip(h: TvSkinHost, active: Int, tk: TvTokens = tokens, glass: Boolean = false): View {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(4))
        }
        // (v614) بوابة واحدة بس: 🏠 أيقونة من غير كلمة. «الإشعارات» و«السمات» اتشالوا من التابات (لسه بيتفتحوا من اللوحة السريعة / قايمة التصميم)
        val tabs = listOf<Triple<String, Int, () -> Unit>>(
            Triple("🏠", 0, { h.goHome() }))
        for ((label, idx, go) in tabs) {
            val pill = TvOwnLinear(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(8))
                tag = "hub:tab$idx"
            }
            val tv = ui.text(label, tk.textBody + 6f, if (idx == active) pal.onPrimary else pal.fg, idx == active)
            pill.addView(tv)
            fun paint(f: Boolean) {
                val fill = if (idx == active) pal.primary else if (f) 0x33FFFFFF else 0x14FFFFFF
                if (glass && idx != active) pill.background = TvSkinGlass.pane(pal.dark, ui.dp(tk.radiusPill).toFloat(), f, ui.dp(1))
                else pill.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(fill); cornerRadius = ui.dp(tk.radiusPill).toFloat()
                    if (f) setStroke(ui.dp(3), Color.WHITE) else if (idx != active) setStroke(ui.dp(1), pal.border)
                }
                pill.animate().scaleX(if (f) tk.focusScale else 1f).scaleY(if (f) tk.focusScale else 1f).setDuration(tk.focusDur()).start()
            }
            paint(false)
            pill.setOnFocusChangeListener { _, f -> paint(f) }
            pill.setOnClickListener { if (idx != active) go() }
            row.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
        }
        return row
    }

    // ===== بانر «كمّل» =====
    /** (v628) هيكل لوحة التحكم: عمود مشاهدة + عمود تطبيقات جانبي */
    override fun arrangeHome(h: TvSkinHost, s: TvHomeSections): List<View>? = TvHomeLayouts.dashboard(h, tokens, s)

    /** (v629) الهيدر القديم اتشال: الرئيسية بتتبني من arrangeHome */
    override fun homeHeader(h: TvSkinHost): View? = null

    @Suppress("unused")
    private fun legacyHomeHeader(h: TvSkinHost): View? {
        val b = continueBanner(h) ?: return null
        return LinearLayout(h.activity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false; addView(b) }   // (v622) زرار 🏠 اتشال من الرئيسية (كان بيدوس على نفسه ومش بيعمل حاجة)
    }

    internal fun continueBanner(h: TvSkinHost, tk: TvTokens = tokens, glass: Boolean = false): View? {
        val r = h.lastRecent() ?: return null
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val dm = a.resources.displayMetrics
        val bannerH = if (dm.heightPixels / dm.density < 520f) 150 else 190
        val thumbW = (bannerH * 16 / 9)

        val t = TvTileView(a, tk.radiusCard + 2).styled(tk, pal.primary)
        if (glass) t.inner.background = TvSkinGlass.pane(pal.dark, ui.dp(tk.radiusCard).toFloat(), false, ui.dp(1)) else t.inner.setBackgroundColor(pal.cardFill)
        t.tag = "skin:first"

        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, 0, 0, 0)   // (v622) الصورة لازقة في حواف الكارت زي كروت الفيديوهات اللي تحت؛ التقصيص بالزوايا بيتعمل من inner بتاع الكارت نفسه
        }
        val box = FrameLayout(a).apply { setBackgroundColor(pal.border); minimumHeight = ui.dp(bannerH - 10) }
        val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val play = ui.text("▶", bannerH * 0.22f, Color.WHITE).apply { gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 2f, Color.BLACK) }
        box.addView(iv, FrameLayout.LayoutParams(-1, -1)); box.addView(play, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        row.addView(box, LinearLayout.LayoutParams(ui.dp(thumbW - 10), -1))
        h.recentThumbInto(r, iv)

        fun TextView.fit(maxL: Int) {
            maxLines = maxL; ellipsize = TextUtils.TruncateAt.END; minWidth = 0
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(18), ui.dp(10), ui.dp(14), ui.dp(10))
        }
        col.addView(ui.text("▶ كمّل المشاهدة", tk.textCaption + 1f, pal.primary, true).apply { fit(1) })
        col.addView(ui.text(r.title, tk.textTitle + 5f, pal.fg, true).apply { fit(2); setPadding(0, ui.dp(4), 0, ui.dp(2)) })
        val pct = r.percent
        val meta = if (r.posSec > 0.0) "⏸ " + PlayerLogic.clock((r.posSec * 1000).toLong()) + (if (r.durSec > 0.0) " من " + PlayerLogic.clock((r.durSec * 1000).toLong()) else "") + " · " + pct + "%"
                   else "ابدأ من الأول"
        col.addView(ui.text(meta, tk.textBody, pal.fgSub).apply { fit(1) })
        // شريط التقدّم
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(pal.border) }
        if (pct > 0) bar.addView(View(a).apply { setBackgroundColor(pal.primary) }, LinearLayout.LayoutParams(0, -1, pct.toFloat()))
        if (pct < 100) bar.addView(View(a), LinearLayout.LayoutParams(0, -1, (100 - pct).toFloat().coerceAtLeast(0.001f)))
        col.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(6) })
        col.addView(ui.text("OK للتشغيل  ·  Menu إعدادات سريعة", tk.textCaption, pal.fgSub).apply { fit(1) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        t.inner.addView(row, FrameLayout.LayoutParams(-1, -1))

        t.setOnClickListener { h.playRecent(r) }
        t.contentDescription = "كمّل المشاهدة: " + r.title
        t.minimumHeight = ui.dp(bannerH)   // (v622) الكارت بيكبر مع المحتوى (سطر «OK للتشغيل» كان بيتقص من تحت)
        t.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(6)) }
        return t
    }

    // ===== لوحة الإعدادات السريعة (Menu) =====
    override fun menuKey(h: TvSkinHost): Boolean {
        quickPanel(h); return true
    }

    private fun quickPanel(h: TvSkinHost) {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val dlg = Dialog(a)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val rows = listOf(
            "🧩 السكنات والسمات" to TvPage.LAYOUT,
            "🖼 الخلفية" to TvPage.WALLPAPER,
            "🎛 كل إعدادات المظهر" to TvPage.APPEARANCE)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18))
            background = ui.box(pal.bg, pal.border, 0)
        }
        col.addView(ui.text("⚙ إعدادات سريعة", tokens.textTitle + 2f, pal.fg, true).apply { setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(12)) })
        var first: View? = null
        var rowLL: LinearLayout? = null   // (v613) صفين بس: 2 في الصف
        for ((idx, lp) in rows.withIndex()) {
            val label = lp.first; val page = lp.second
            val tv = ui.text(label, tokens.textBody + 1f, pal.fg).apply {
                gravity = Gravity.CENTER; maxLines = 2
                isFocusable = true; isClickable = true
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                background = ui.box(Color.TRANSPARENT, Color.TRANSPARENT, tokens.radiusCard)
                setOnFocusChangeListener { v, f ->
                    v.background = ui.box(if (f) pal.primary else Color.TRANSPARENT, Color.TRANSPARENT, tokens.radiusCard)
                    (v as TextView).setTextColor(if (f) pal.onPrimary else pal.fg)
                }
                setOnClickListener { dlg.dismiss(); h.open(page) }
            }
            if (first == null) first = tv
            if (idx % 2 == 0) { rowLL = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }; col.addView(rowLL, LinearLayout.LayoutParams(-1, -2)) }
            rowLL?.addView(tv, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        }
        dlg.setContentView(ScrollView(a).apply { addView(col); isFillViewport = true })
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = a.resources.displayMetrics
            w.setLayout((dm.widthPixels * 0.44f).toInt().coerceAtLeast(ui.dp(380)), WindowManager.LayoutParams.MATCH_PARENT)
            w.setGravity(Gravity.START)   // ناحية بداية الاتجاه (يمين في العربي)
            w.setDimAmount(0.55f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.setOnKeyListener { d, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_MENU && ev.action == android.view.KeyEvent.ACTION_UP) { d.dismiss(); true } else false
        }
        dlg.show()
        first?.post { try { first?.requestFocus() } catch (_: Throwable) {} }
    }

    // ===== الإشعارات (تاب) =====
    override fun notifications(h: TvSkinHost): Boolean {
        h.page { c ->
            val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
            c.addView(tabStrip(h, 1))
            val items = try { NoticeLog.list() } catch (_: Throwable) { emptyList() }
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(6)) }
            head.addView(ui.text("🔔 الإشعارات", tokens.textTitle + 5f, pal.fg, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (items.isNotEmpty()) {
                val clear = TvOwnLinear(a).apply {
                    gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                    setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)); tag = "hub:clear"
                }
                clear.addView(ui.text("🗑 مسح الكل", tokens.textBody + 1f, pal.fg))
                fun paint(f: Boolean) {
                    clear.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (f) 0x33FFFFFF else 0x14FFFFFF); cornerRadius = ui.dp(tokens.radiusPill).toFloat()
                        setStroke(ui.dp(if (f) 3 else 1), if (f) Color.WHITE else pal.border)
                    }
                }
                paint(false)
                clear.setOnFocusChangeListener { _, f -> paint(f) }
                clear.setOnClickListener { try { NoticeLog.clear() } catch (_: Throwable) {}; h.open(TvPage.NOTIFICATIONS) }
                head.addView(clear)
            }
            c.addView(head)
            if (items.isEmpty()) {
                c.addView(emptyState(h, TvEmpty.NO_NOTIFICATIONS) ?: View(a))
                return@page
            }
            var first = true
            for (n in items.take(40)) {
                val t = TvTileView(a, tokens.radiusCard).styled(tokens, pal.primary)
                t.inner.setBackgroundColor(pal.cardFill)
                if (first) { t.tag = "skin:first"; first = false }
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                }
                row.addView(ui.text("🔔", tokens.textTitle, pal.primary).apply { setPadding(0, 0, ui.dp(12), 0) })
                row.addView(ui.text(n.msg, tokens.textTitle, pal.fg).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(ui.text(ago(n.at), tokens.textCaption + 1f, pal.fgSub).apply { setPadding(ui.dp(12), 0, 0, 0) })
                t.inner.addView(row, FrameLayout.LayoutParams(-1, -2))
                t.setOnClickListener { }
                c.addView(t, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)) })
            }
            c.addView(View(a), LinearLayout.LayoutParams(-1, ui.dp(24)))
        }
        return true
    }

    internal fun ago(at: Long): String {
        val s = ((System.currentTimeMillis() - at) / 1000L).coerceAtLeast(0L)
        return when {
            s < 60L -> "دلوقتي"
            s < 3600L -> "من " + (s / 60L) + " د"
            s < 86400L -> "من " + (s / 3600L) + " س"
            else -> "من " + (s / 86400L) + " يوم"
        }
    }

    // ===== السمات (تاب): شبكة كروت ملوّنة بمعاينة مصغّرة =====
    override fun themes(h: TvSkinHost): Boolean {
        h.open(TvPage.LAYOUT)
        return true
    }

    // ===== هياكل التحميل =====
    override fun homeSkeleton(h: TvSkinHost): View? {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        val anims = ArrayList<android.animation.ObjectAnimator>()
        for (i in 0 until 3) {
            val box = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; background = ui.box(pal.cardFill, Color.TRANSPARENT, tokens.radiusCard); setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)) }
            box.addView(View(a).apply { background = ui.box(pal.border, Color.TRANSPARENT, tokens.radiusThumb) }, LinearLayout.LayoutParams(ui.dp(124), ui.dp(70)))
            val lines = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, 0, 0) }
            lines.addView(View(a).apply { background = ui.box(pal.border, Color.TRANSPARENT, 6) }, LinearLayout.LayoutParams(ui.dp(120), ui.dp(14)))
            lines.addView(View(a).apply { background = ui.box(pal.border, Color.TRANSPARENT, 6) }, LinearLayout.LayoutParams(ui.dp(80), ui.dp(10)).apply { topMargin = ui.dp(10) })
            box.addView(lines)
            row.addView(box, LinearLayout.LayoutParams(ui.dp(330), ui.dp(100)).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) })
            // نبضة خفيفة (بتقف أول ما الهيكل يتشال من الشاشة)
            if (Skins.dur(1000L) > 0L) anims.add(android.animation.ObjectAnimator.ofFloat(box, "alpha", 1f, 0.5f).apply { duration = 700L + i * 120L; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE })
        }
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { anims.forEach { try { it.start() } catch (_: Throwable) {} } }
            override fun onViewDetachedFromWindow(v: View) { anims.forEach { try { it.cancel() } catch (_: Throwable) {} } }
        })
        row.contentDescription = "بقرا الفيديوهات"
        return row
    }

    // ===== شاشات الفاضي =====
    override fun emptyState(h: TvSkinHost, kind: TvEmpty): View? {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val (icon, title, sub) = when (kind) {
            TvEmpty.NO_PERMISSION -> Triple("🔐", "محتاجين صلاحية الفيديوهات", "من غيرها مش هنعرف نعرض الفيديوهات اللي على الجهاز")
            TvEmpty.NO_VIDEOS -> Triple("🎬", "مفيش فيديوهات لسه", "وصّل فلاشة أو حط فيديوهات على الذاكرة الداخلية وهتظهر هنا لوحدها")
            TvEmpty.NO_NOTIFICATIONS -> Triple("🔔", "مفيش إشعارات", "أي إشعار بيظهر في التطبيق هيتسجّل هنا")
        }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(24), ui.dp(26), ui.dp(24), ui.dp(26)); background = ui.box(pal.cardFill, pal.border, tokens.radiusCard + 4)
        }
        col.addView(ui.text(icon, 44f, pal.fg).apply { gravity = Gravity.CENTER })
        col.addView(ui.text(title, tokens.textTitle + 3f, pal.fg, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        col.addView(ui.text(sub, tokens.textBody + 1f, pal.fgSub).apply { gravity = Gravity.CENTER })
        if (kind == TvEmpty.NO_PERMISSION) {
            val btn = TvOwnLinear(a).apply { gravity = Gravity.CENTER; isFocusable = true; isClickable = true; setPadding(ui.dp(26), ui.dp(11), ui.dp(26), ui.dp(11)); tag = "skin:first" }
            btn.addView(ui.text("اسمح بالفيديوهات", tokens.textBody + 2f, pal.onPrimary, true))
            fun paint(f: Boolean) { btn.background = android.graphics.drawable.GradientDrawable().apply { setColor(pal.primary); cornerRadius = ui.dp(tokens.radiusPill).toFloat(); if (f) setStroke(ui.dp(3), Color.WHITE) } }
            paint(false)
            btn.setOnFocusChangeListener { _, f -> paint(f); btn.animate().scaleX(if (f) tokens.focusScale else 1f).scaleY(if (f) tokens.focusScale else 1f).setDuration(tokens.focusDur()).start() }
            btn.setOnClickListener { h.requestVideoPermission() }
            col.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(16) })
        }
        col.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)) }
        return col
    }
}
