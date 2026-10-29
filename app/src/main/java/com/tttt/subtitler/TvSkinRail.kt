package com.tttt.subtitler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v625 م4) السكِن 2 «Rail & Grid» (شكل Fire TV / Roku):
 *  • التنقل: ريل شمال (أيقونات بس) بيتوسّع بأسماء لما التركيز يدخله وبيرجع يتقفل لما يخرج منه.
 *    عناصره: الرئيسية · الفيديوهات · الإشعارات · الثيمات · الإعدادات (كلهم عناصر ريل).
 *  • الفولدرات: شبكة كروت عريضة 4×3 (صورة 16:9 فوق + الاسم + العدد والحجم) و«عرض المزيد» على دفعات.
 *  • الإعدادات: صفحات متداخلة + breadcrumb (الإعدادات ‹ المظهر) — كل خطوة في الـbreadcrumb بتتضغط للرجوع لها.
 *  • الرئيسية: بانر «كمّل» (أول عنصر بيتركّز عليه = OK واحدة) + أول 12 فولدر + «كل الفيديوهات».
 *  • التركيز: UNDERLINE (جاهز من م2). هيكل تحميل نابض للشبكة + شاشات الفاضي (بشكل Hub).
 * Back: بيرجّع للرئيسية (زي أي سكِن). مكان الساعة والحالة: الشريط العلوي المدمج زي ما هو.
 */
object TvSkinRail : TvSkin {
    override val id = "rail"
    override val name = "Rail & Grid (شكل Fire TV / Roku)"
    override val themeIds = listOf("default", "mxd", "amoled_w", "win11d")
    override val tokens = TvTokens(radiusCard = 12, radiusThumb = 10, radiusPill = 24, gap = 8, pad = 12,
        textTitle = 18f, textBody = 14f, textCaption = 12f, focusStyle = TvFocusStyle.UNDERLINE, focusScale = 1.04f, focusMs = 120L, pageMs = 200L)

    private enum class Sec { HOME, VIDEOS, NOTIF, THEMES, SETTINGS }

    private const val RAIL_W = 62        // dp — متقفّل (أيقونات)
    private const val RAIL_OPEN = 196    // dp — متوسّع (أيقونات + أسماء)
    private const val ROWS = 3           // 4×3

    private var sec = Sec.HOME
    private var path: List<String> = emptyList()   // مسار الإعدادات المتداخلة (breadcrumb)
    private var railFocus = false                  // بعد ضغطة على الريل: التركيز يفضل على عنصر الريل
    private var scanKicked = false                 // فحص واحد لكل دخول (من غير لفّة لا نهائية لو الفحص فشل)
    private var bypassSettings = false             // «كل إعدادات المظهر» تفتح الصفحة المدمجة مش صفحة السكِن

    override fun folderCard(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit): View =
        TvFolderCard.build(h, f, size, onOpen, tokens)

    // ===== الخانات =====
    override fun home(h: TvSkinHost, focus: Boolean, reuse: Boolean): Boolean {
        sec = Sec.HOME; path = emptyList(); railFocus = false; scanKicked = false
        render(h); return true
    }
    override fun notifications(h: TvSkinHost): Boolean { sec = Sec.NOTIF; railFocus = false; render(h); return true }
    override fun themes(h: TvSkinHost): Boolean { sec = Sec.THEMES; railFocus = false; render(h); return true }
    override fun settings(h: TvSkinHost): Boolean {
        if (bypassSettings) { bypassSettings = false; return false }
        sec = Sec.SETTINGS; path = emptyList(); railFocus = false; render(h); return true
    }
    override fun menuKey(h: TvSkinHost): Boolean { sec = Sec.SETTINGS; path = emptyList(); railFocus = false; render(h); return true }
    override fun homeSkeleton(h: TvSkinHost): View? = TvSkinHub.homeSkeleton(h)
    override fun emptyState(h: TvSkinHost, kind: TvEmpty): View? = TvSkinHub.emptyState(h, kind)

    private fun render(h: TvSkinHost) {
        val draw: (LinearLayout) -> Unit = { c ->
            try { build(h, c) } catch (e: Throwable) { LogStore.err("Rail:build", e) }
            railFocus = false
        }
        if (sec == Sec.HOME) h.homePage(draw) else h.page(draw)
    }

    private fun build(h: TvSkinHost, c: LinearLayout) {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val dm = a.resources.displayMetrics
        val wDp = dm.widthPixels / dm.density
        val cols = ((wDp - RAIL_W - 64f) / 250f).toInt().coerceIn(2, 4)
        val root = FrameLayout(a).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; clipChildren = false; clipToPadding = false; setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(12)) }
        val main = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false
        }
        root.addView(main, FrameLayout.LayoutParams(-1, -2).apply { leftMargin = ui.dp(RAIL_W + 14) })
        c.addView(root, LinearLayout.LayoutParams(-1, -2))
        when (sec) {
            Sec.HOME -> homeMain(h, main, pal, cols)
            Sec.VIDEOS -> videosMain(h, main, pal, cols)
            Sec.NOTIF -> notifMain(h, main, pal)
            Sec.THEMES -> themesMain(h, main, pal, cols)
            Sec.SETTINGS -> settingsMain(h, main, pal)
        }
        root.addView(buildRail(h, pal), FrameLayout.LayoutParams(ui.dp(RAIL_W), -2, Gravity.TOP or Gravity.START))   // آخر حاجة: فوق المحتوى لما يتوسّع
    }

    // ===== الريل =====
    private fun buildRail(h: TvSkinHost, pal: TvPalette): View {
        val a = h.activity; val ui = h.ui
        val rail = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; clipChildren = false
            setPadding(ui.dp(5), ui.dp(8), ui.dp(5), ui.dp(8)); elevation = ui.dp(8).toFloat()
            background = GradientDrawable().apply { setColor(if (pal.dark) 0xF0101319.toInt() else 0xF2FFFFFF.toInt()); cornerRadius = ui.dp(tokens.radiusCard + 6).toFloat(); setStroke(ui.dp(1), pal.border) }
        }
        val labels = ArrayList<TextView>()
        var open = false
        fun setOpen(o: Boolean) {
            if (o == open) return
            open = o
            val lp = rail.layoutParams as FrameLayout.LayoutParams
            val to = ui.dp(if (o) RAIL_OPEN else RAIL_W)
            if (o) labels.forEach { it.visibility = View.VISIBLE }
            ValueAnimator.ofInt(lp.width, to).apply {
                duration = tokens.pageDur()
                addUpdateListener { lp.width = it.animatedValue as Int; rail.layoutParams = lp }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) { if (!o) labels.forEach { it.visibility = View.GONE } } })
            }.start()
        }
        val items = listOf(Triple("🏠", "الرئيسية", Sec.HOME), Triple("🎬", "الفيديوهات", Sec.VIDEOS), Triple("🔔", "الإشعارات", Sec.NOTIF),
            Triple("🎨", "الثيمات", Sec.THEMES), Triple("⚙", "الإعدادات", Sec.SETTINGS))
        for ((ic, lb, s) in items) {
            val sel = s == sec
            val row = TvOwnLinear(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                isFocusable = true; isClickable = true; setPadding(ui.dp(10), ui.dp(11), ui.dp(10), ui.dp(11)); contentDescription = lb
                if (sel && railFocus) tag = "skin:first"
            }
            row.addView(ui.text(ic, 22f, if (sel) pal.primary else pal.fg).apply { gravity = Gravity.CENTER; minWidth = ui.dp(32) })
            val label = ui.text(lb, tokens.textBody + 1f, pal.fg, sel).apply { visibility = View.GONE; setPadding(ui.dp(12), 0, ui.dp(8), 0); maxLines = 1; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
            labels.add(label); row.addView(label)
            fun paint(f: Boolean) {
                row.background = GradientDrawable().apply {
                    setColor(if (f) 0x33FFFFFF else if (sel) (pal.primary and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
                    cornerRadius = ui.dp(tokens.radiusPill).toFloat()
                    if (f) setStroke(ui.dp(2), Color.WHITE)
                }
            }
            paint(false)
            row.setOnFocusChangeListener { _, f -> paint(f); if (f) setOpen(true) else rail.post { if (rail.findFocus() == null) setOpen(false) } }
            row.setOnClickListener {
                if (s == sec && s != Sec.SETTINGS) { if (s == Sec.HOME) { railFocus = true; render(h) }; return@setOnClickListener }
                sec = s; path = emptyList(); railFocus = true; scanKicked = false; render(h)
            }
            rail.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(2), 0, ui.dp(2)) })
        }
        return rail
    }

    // ===== مكوّنات مشتركة =====
    private fun title(h: TvSkinHost, pal: TvPalette, t: String, sub: String? = null): View {
        val ui = h.ui
        val col = LinearLayout(h.activity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(6)) }
        col.addView(ui.text(t, tokens.textTitle + 5f, pal.fg, true).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        if (sub != null) col.addView(ui.text(sub, tokens.textBody, pal.fgSub).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        return col
    }

    private fun pill(h: TvSkinHost, pal: TvPalette, label: String, strong: Boolean = false, onClick: () -> Unit): View {
        val a = h.activity; val ui = h.ui
        val p = TvOwnLinear(a).apply { gravity = Gravity.CENTER; isFocusable = true; isClickable = true; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)) }
        p.addView(ui.text(label, tokens.textBody + 1f, if (strong) pal.onPrimary else pal.fg, strong))
        fun paint(f: Boolean) {
            p.background = GradientDrawable().apply {
                setColor(if (strong) pal.primary else if (f) 0x33FFFFFF else 0x14FFFFFF); cornerRadius = ui.dp(tokens.radiusPill).toFloat()
                setStroke(ui.dp(if (f) 3 else 1), if (f) Color.WHITE else pal.border)
            }
        }
        paint(false); p.setOnFocusChangeListener { _, f -> paint(f) }; p.setOnClickListener { onClick() }
        return p
    }

    /** كارت فولدر عريض: صورة 16:9 + الاسم (سطرين) + العدد والحجم */
    private fun wideCard(h: TvSkinHost, f: FolderItem, pal: TvPalette): TvTileView {
        val a = h.activity; val ui = h.ui
        val t = TvTileView(a, tokens.radiusCard).styled(tokens, pal.primary)
        t.inner.setBackgroundColor(pal.cardFill)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val box = FrameLayout(a).apply { setBackgroundColor(pal.border) }
        val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val v0 = f.videos.firstOrNull()
        if (v0 != null) h.loadThumb(v0) { bmp -> iv.setImageBitmap(bmp) }
        else box.addView(ui.text("📁", 34f, pal.fg).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        col.addView(box, LinearLayout.LayoutParams(-1, ui.dp(104)))
        val info = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(10)) }
        info.addView(ui.text(f.name, tokens.textBody + 1f, pal.fg, true).apply {
            maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END; minWidth = 0
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        info.addView(ui.text(f.videos.size.toString() + " فيديو · " + VideoLib.fmtSize(f.totalSize), tokens.textCaption, pal.fgSub).apply { maxLines = 1; textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        col.addView(info)
        t.inner.addView(col, FrameLayout.LayoutParams(-1, -2))
        t.setOnClickListener { h.openFolder(f) }
        t.setOnLongClickListener { h.menu("📁 " + f.name.take(40), listOf("📂 افتح" to { h.openFolder(f) })); true }
        t.contentDescription = f.name
        return t
    }

    /** شبكة (cols × ROWS للصفحة) بكروت عريضة، «عرض المزيد» على دفعات. extra = كارت إضافي آخر الصفحة الأولى (مثلًا «كل الفيديوهات») */
    private fun grid(h: TvSkinHost, parent: LinearLayout, pal: TvPalette, list: List<FolderItem>, cols: Int, tagFirst: Boolean, limitOnePage: Boolean, onAll: (() -> Unit)?) {
        val a = h.activity; val ui = h.ui
        val page = cols * ROWS
        var shown = 0
        var firstTagged = !tagFirst
        fun addPage() {
            val end = minOf(shown + page, list.size)
            var k = shown; var firstNew: View? = null
            while (k < end) {
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
                for (j in 0 until cols) {
                    val f = if (k + j < end) list[k + j] else null
                    val lp = LinearLayout.LayoutParams(0, if (f != null) -2 else 1, 1f).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) }
                    if (f != null) {
                        val card = wideCard(h, f, pal)
                        if (!firstTagged) { card.tag = "skin:first"; firstTagged = true }
                        if (firstNew == null) firstNew = card
                        row.addView(card, lp)
                    } else row.addView(View(a), lp)
                }
                parent.addView(row, LinearLayout.LayoutParams(-1, -2)); k += cols
            }
            shown = end
            if (shown < list.size) {
                val more: TvTileView? = if (limitOnePage) onAll?.let { go -> moreTile(h, pal, "📂 كل الفيديوهات (" + list.size + " فولدر)") { go() } }
                                        else moreTile(h, pal, "⬇ عرض المزيد (" + (list.size - shown) + ")") {}
                if (more != null) {
                    if (!limitOnePage) more.setOnClickListener { parent.removeView(more); addPage(); firstNew?.requestFocus() }
                    parent.addView(more, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) })
                }
            }
        }
        addPage()
    }

    private fun moreTile(h: TvSkinHost, pal: TvPalette, label: String, onClick: () -> Unit): TvTileView {
        val ui = h.ui
        val t = TvTileView(h.activity, tokens.radiusCard).styled(tokens, pal.primary)
        t.inner.setBackgroundColor(pal.cardFill)
        t.inner.addView(ui.text(label, tokens.textTitle, pal.fg, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(16)) }, FrameLayout.LayoutParams(-1, -2))
        t.setOnClickListener { onClick() }
        return t
    }

    /** هيكل تحميل نابض بنفس شكل الشبكة */
    private fun skeletonGrid(h: TvSkinHost, pal: TvPalette, cols: Int): View {
        val a = h.activity; val ui = h.ui
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; contentDescription = "بقرا الفيديوهات" }
        val anims = ArrayList<ObjectAnimator>()
        for (r in 0 until 2) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            for (i in 0 until cols) {
                val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = ui.box(pal.cardFill, Color.TRANSPARENT, tokens.radiusCard) }
                box.addView(View(a).apply { background = ui.box(pal.border, Color.TRANSPARENT, tokens.radiusThumb) }, LinearLayout.LayoutParams(-1, ui.dp(104)))
                box.addView(View(a).apply { background = ui.box(pal.border, Color.TRANSPARENT, 6) }, LinearLayout.LayoutParams(ui.dp(120), ui.dp(12)).apply { setMargins(ui.dp(10), ui.dp(12), 0, 0) })
                box.addView(View(a).apply { background = ui.box(pal.border, Color.TRANSPARENT, 6) }, LinearLayout.LayoutParams(ui.dp(70), ui.dp(10)).apply { setMargins(ui.dp(10), ui.dp(8), 0, ui.dp(12)) })
                row.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) })
                if (Skins.dur(1000L) > 0L) anims.add(ObjectAnimator.ofFloat(box, "alpha", 1f, 0.5f).apply { duration = 700L + (r * cols + i) * 90L; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE })
            }
            wrap.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        wrap.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { anims.forEach { try { it.start() } catch (_: Throwable) {} } }
            override fun onViewDetachedFromWindow(v: View) { anims.forEach { try { it.cancel() } catch (_: Throwable) {} } }
        })
        return wrap
    }

    /** فولدرات جاهزة؟ لو لأ: هيكل تحميل + فحص مرة واحدة ثم إعادة رسم. بيرجّع null لو الفولدرات جاهزة (تتعرض) */
    private fun foldersOrState(h: TvSkinHost, c: LinearLayout, pal: TvPalette, cols: Int): List<FolderItem>? {
        if (!h.hasVideoPermission()) { c.addView(emptyState(h, TvEmpty.NO_PERMISSION) ?: View(h.activity)); return null }
        val fl = h.folders()
        if (fl == null) {
            c.addView(skeletonGrid(h, pal, cols))
            if (!scanKicked) { scanKicked = true; val mine = sec; h.ensureScan { if (sec == mine) render(h) } }
            return null
        }
        if (fl.isEmpty()) { c.addView(emptyState(h, TvEmpty.NO_VIDEOS) ?: View(h.activity)); return null }
        return fl
    }

    // ===== الأقسام =====
    private fun homeMain(h: TvSkinHost, m: LinearLayout, pal: TvPalette, cols: Int) {
        val banner = TvSkinHub.continueBanner(h, tokens)
        if (banner != null) m.addView(banner)
        m.addView(title(h, pal, "📁 فولدراتك"))
        val fl = foldersOrState(h, m, pal, cols) ?: return
        grid(h, m, pal, fl, cols, banner == null, true) { sec = Sec.VIDEOS; path = emptyList(); railFocus = false; render(h) }
    }

    private fun videosMain(h: TvSkinHost, m: LinearLayout, pal: TvPalette, cols: Int) {
        m.addView(title(h, pal, "🎬 الفيديوهات"))
        val fl = foldersOrState(h, m, pal, cols) ?: return
        m.getChildAt(0).contentDescription = "الفيديوهات: " + fl.size + " فولدر"
        grid(h, m, pal, fl, cols, !railFocus, false, null)
    }

    private fun notifMain(h: TvSkinHost, m: LinearLayout, pal: TvPalette) {
        val a = h.activity; val ui = h.ui
        val items = try { NoticeLog.list() } catch (_: Throwable) { emptyList() }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        head.addView(title(h, pal, "🔔 الإشعارات"), LinearLayout.LayoutParams(0, -2, 1f))
        if (items.isNotEmpty()) head.addView(pill(h, pal, "🗑 مسح الكل") { try { NoticeLog.clear() } catch (_: Throwable) {}; render(h) })
        m.addView(head)
        if (items.isEmpty()) { m.addView(emptyState(h, TvEmpty.NO_NOTIFICATIONS) ?: View(a)); return }
        for (n in items.take(40)) {
            val t = TvTileView(a, tokens.radiusCard).styled(tokens, pal.primary)
            t.inner.setBackgroundColor(pal.cardFill)
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
            row.addView(ui.text("🔔", tokens.textTitle, pal.primary).apply { setPadding(0, 0, ui.dp(12), 0) })
            row.addView(ui.text(n.msg, tokens.textTitle, pal.fg).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.text(TvSkinHub.ago(n.at), tokens.textCaption + 1f, pal.fgSub).apply { setPadding(ui.dp(12), 0, 0, 0) })
            t.inner.addView(row, FrameLayout.LayoutParams(-1, -2))
            t.setOnClickListener { }
            m.addView(t, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) })
        }
    }

    private fun themesMain(h: TvSkinHost, m: LinearLayout, pal: TvPalette, cols: Int) {
        val a = h.activity; val ui = h.ui
        m.addView(title(h, pal, "🎨 الثيمات", "OK على السمة بتطبّقها · «معرض الثيمات» من الإعدادات للسكِن + الأيقونات + الألوان مع بعض"))
        val all = Themes.all
        val per = (cols + 1).coerceAtMost(5)
        var k = 0
        while (k < all.size) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
            for (th in all.subList(k, minOf(k + per, all.size))) {
                val card = TvTileView(a, tokens.radiusCard).styled(tokens, pal.primary)
                card.inner.setBackgroundColor(th.bg)
                if (th.id == h.theme.id && !railFocus) card.tag = "skin:first"
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
                val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, ui.dp(8)) }
                for (colr in listOf(th.primary, th.accent, th.success, th.danger)) bar.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colr) } }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginEnd = ui.dp(6) })
                col.addView(bar)
                col.addView(ui.text((if (th.id == h.theme.id) "✔ " else "") + th.name, tokens.textBody + 1f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
                card.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
                card.contentDescription = "سمة " + th.name
                card.setOnClickListener { if (th.id != h.theme.id) h.setTheme(th.id) }
                row.addView(card, LinearLayout.LayoutParams(0, ui.dp(100), 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            }
            for (i in 0 until per - minOf(per, all.size - k)) row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
            m.addView(row, LinearLayout.LayoutParams(-1, -2)); k += per
        }
    }

    // ----- الإعدادات: صفحات متداخلة + breadcrumb -----
    private class Node(val label: String, val kids: List<Node> = emptyList(), val page: TvPage? = null, val builtin: Boolean = false)

    private val tree: List<Node> = listOf(
        Node("🎨 المظهر", listOf(
            Node("🖼 معرض الثيمات", page = TvPage.PRESETS),
            Node("🧩 التصميم والسمة", page = TvPage.LAYOUT),
            Node("🎛 كل إعدادات المظهر", page = TvPage.APPEARANCE, builtin = true))),
        Node("🖼 الخلفية والحجم", listOf(
            Node("🖼 الخلفية", page = TvPage.WALLPAPER),
            Node("📐 حجم الواجهة", page = TvPage.SCALE))),
        Node("📦 الأيقونات", listOf(
            Node("📦 باك الأيقونات", page = TvPage.ICON_PACK))))

    private fun settingsMain(h: TvSkinHost, m: LinearLayout, pal: TvPalette) {
        val a = h.activity; val ui = h.ui
        // المسار الحالي ← العُقدة
        var cur: List<Node> = tree
        val trail = ArrayList<Node>()
        for (lbl in path) { val n = cur.firstOrNull { it.label == lbl } ?: break; trail.add(n); cur = n.kids }
        // breadcrumb
        val crumbs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(8)) }
        val lastIdx = trail.size
        crumbs.addView(pill(h, pal, "⚙ الإعدادات", strong = lastIdx == 0) { path = emptyList(); railFocus = false; render(h) })
        for ((i, n) in trail.withIndex()) {
            crumbs.addView(ui.text("‹", tokens.textTitle + 4f, pal.fgSub).apply { setPadding(ui.dp(8), 0, ui.dp(8), 0) })
            crumbs.addView(pill(h, pal, n.label, strong = i == trail.size - 1) { path = path.take(i + 1); railFocus = false; render(h) })
        }
        m.addView(crumbs)
        var first = !railFocus
        for (n in cur) {
            val t = TvTileView(a, tokens.radiusCard).styled(tokens, pal.primary)
            t.inner.setBackgroundColor(pal.cardFill)
            if (first) { t.tag = "skin:first"; first = false }
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16)) }
            row.addView(ui.text(n.label, tokens.textTitle + 1f, pal.fg, true).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START }, LinearLayout.LayoutParams(0, -2, 1f))
            if (n.kids.isNotEmpty()) row.addView(ui.text("›", tokens.textTitle + 6f, pal.fgSub))
            t.inner.addView(row, FrameLayout.LayoutParams(-1, -2))
            t.contentDescription = n.label
            t.setOnClickListener {
                if (n.kids.isNotEmpty()) { path = path + n.label; railFocus = false; render(h) }
                else n.page?.let { p -> if (n.builtin) bypassSettings = true; h.open(p) }
            }
            m.addView(t, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) })
        }
    }
}
