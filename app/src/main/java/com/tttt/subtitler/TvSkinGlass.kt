package com.tttt.subtitler

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v601 م4) السكِن 6 «Glass» (شكل OriginOS): طبقات زجاج عايمة فوق الخلفية.
 *  • التنقل: نفس شريط التابات (رئيسية · إشعارات · سمات) + بانر «كمّل» — لكن كله زجاج شفاف بحواف كبيرة وتركيز متوهّج.
 *  • الفولدرات: كروت زجاجية بحواف كبيرة (كارت الفولدر الموحّد بخلفية زجاج). فتح الفولدر لسه بالعرض المدمج.
 *  • الإعدادات: لوحة زجاج عايمة في نص الشاشة بزرار Menu.
 *  • الإشعارات: شيت زجاجي بينزل من فوق.
 *  • السمات: معرض بمعاينة حيّة كبيرة — أي سمة بتتركّز عليها بتظهر في المعاينة فورًا، وOK بتطبّقها.
 * اللي مش مرسوم هنا بيرجع للرسم المدمج (false/null).
 */
object TvSkinGlass : TvSkin {
    override val id = "glass"
    override val name = "Glass (شكل OriginOS)"
    override val tokens = TvTokens(radiusCard = 26, radiusThumb = 20, radiusPill = 34, gap = 12, pad = 16,
        textTitle = 19f, textBody = 14f, textCaption = 12.5f, focusStyle = TvFocusStyle.GLOW, focusScale = 1.06f, focusMs = 200L, pageMs = 320L)

    /** لوح زجاج: تدرّج شفاف من فوق لتحت + حد رفيع (أبيض سميك لو متركّز) */
    fun pane(dark: Boolean, radiusPx: Float, focused: Boolean, strokePx: Int): GradientDrawable {
        val cols = if (dark) intArrayOf(0x4DFFFFFF, 0x1AFFFFFF) else intArrayOf(0xE8FFFFFF.toInt(), 0xB8FFFFFF.toInt())
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols).apply {
            cornerRadius = radiusPx
            if (focused) setStroke(strokePx * 3, Color.WHITE) else setStroke(strokePx, if (dark) 0x66FFFFFF else 0x33000000)
        }
    }

    override fun folderCard(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit): View {
        val v = TvFolderCard.build(h, f, size, onOpen, tokens)
        val pal = TvPalette(h.theme)
        (v as? TvTileView)?.inner?.background = pane(pal.dark, h.ui.dp(tokens.radiusCard).toFloat(), false, h.ui.dp(1))
        return v
    }

    override fun homeHeader(h: TvSkinHost): View? {
        val col = LinearLayout(h.activity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
        col.addView(TvSkinHub.tabStrip(h, 0, tokens, true))
        TvSkinHub.continueBanner(h, tokens, true)?.let { col.addView(it) }
        return col
    }

    // ===== الإعدادات: لوحة زجاج عايمة =====
    override fun menuKey(h: TvSkinHost): Boolean { settingsPanel(h); return true }

    private fun settingsPanel(h: TvSkinHost) {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val dlg = Dialog(a)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val rows = listOf(
            "🎨 السمات (معرض)" to TvPage.THEME, "🔔 الإشعارات" to TvPage.NOTIFICATIONS, "📦 باك الأيقونات" to TvPage.ICON_PACK,
            "🧩 تصميم الواجهة" to TvPage.LAYOUT, "🖼 خلفية اللانشر" to TvPage.WALLPAPER, "📐 حجم الواجهة" to TvPage.SCALE,
            "🎛 كل إعدادات المظهر" to TvPage.APPEARANCE)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (pal.dark) intArrayOf(0xE62A3040.toInt(), 0xE6141824.toInt()) else intArrayOf(0xF2FFFFFF.toInt(), 0xE6F2F4F8.toInt())).apply {
                cornerRadius = ui.dp(tokens.radiusCard + 6).toFloat(); setStroke(ui.dp(1), if (pal.dark) 0x66FFFFFF else 0x33000000)
            }
        }
        col.addView(ui.text("⚙ إعدادات سريعة", tokens.textTitle + 2f, pal.fg, true).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(10)) })
        var first: View? = null
        for ((label, page) in rows) {
            val tv = ui.text(label, tokens.textTitle, pal.fg).apply {
                isFocusable = true; isClickable = true
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                background = pane(pal.dark, ui.dp(tokens.radiusPill).toFloat(), false, 0).apply { setColor(Color.TRANSPARENT); setStroke(0, Color.TRANSPARENT) }
                setOnFocusChangeListener { v, f ->
                    v.background = if (f) pane(pal.dark, ui.dp(tokens.radiusPill).toFloat(), true, ui.dp(1)) else GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = ui.dp(tokens.radiusPill).toFloat() }
                }
                setOnClickListener { dlg.dismiss(); h.open(page) }
            }
            if (first == null) first = tv
            col.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(2), 0, ui.dp(2)) })
        }
        dlg.setContentView(ScrollView(a).apply { addView(col); isFillViewport = false })
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = a.resources.displayMetrics
            w.setLayout((dm.widthPixels * 0.46f).toInt().coerceAtLeast(ui.dp(340)), WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.setDimAmount(0.45f); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.setOnKeyListener { d, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_MENU && ev.action == android.view.KeyEvent.ACTION_UP) { d.dismiss(); true } else false
        }
        dlg.show()
        first?.post { try { first?.requestFocus() } catch (_: Throwable) {} }
    }

    // ===== الإشعارات: شيت زجاجي بينزل من فوق =====
    override fun notifications(h: TvSkinHost): Boolean {
        h.page { c ->
            val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
            c.addView(TvSkinHub.tabStrip(h, 1, tokens, true))
            val items = try { NoticeLog.list() } catch (_: Throwable) { emptyList() }
            val sheet = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                background = pane(pal.dark, ui.dp(tokens.radiusCard + 6).toFloat(), false, ui.dp(1))
            }
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(8)) }
            head.addView(ui.text("🔔 الإشعارات", tokens.textTitle + 4f, pal.fg, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (items.isNotEmpty()) {
                val clear = TvOwnLinear(a).apply { gravity = Gravity.CENTER; isFocusable = true; isClickable = true; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)); tag = "glass:clear" }
                clear.addView(ui.text("🗑 مسح الكل", tokens.textBody + 1f, pal.fg))
                clear.background = pane(pal.dark, ui.dp(tokens.radiusPill).toFloat(), false, ui.dp(1))
                clear.setOnFocusChangeListener { _, f -> clear.background = pane(pal.dark, ui.dp(tokens.radiusPill).toFloat(), f, ui.dp(1)) }
                clear.setOnClickListener { try { NoticeLog.clear() } catch (_: Throwable) {}; h.open(TvPage.NOTIFICATIONS) }
                head.addView(clear)
            }
            sheet.addView(head)
            if (items.isEmpty()) {
                sheet.addView(emptyState(h, TvEmpty.NO_NOTIFICATIONS) ?: View(a))
            } else {
                var first = true
                for (n in items.take(40)) {
                    val t = TvTileView(a, tokens.radiusCard - 4)
                    t.inner.background = pane(pal.dark, ui.dp(tokens.radiusCard - 4).toFloat(), false, ui.dp(1))
                    if (first) { t.tag = "skin:first"; first = false }
                    val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
                    row.addView(ui.text("🔔", tokens.textTitle, pal.primary).apply { setPadding(0, 0, ui.dp(12), 0) })
                    row.addView(ui.text(n.msg, tokens.textTitle, pal.fg).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(ui.text(TvSkinHub.ago(n.at), tokens.textCaption + 1f, pal.fgSub).apply { setPadding(ui.dp(12), 0, 0, 0) })
                    t.inner.addView(row, FrameLayout.LayoutParams(-1, -2))
                    t.setOnClickListener { }
                    sheet.addView(t, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
                }
            }
            c.addView(sheet, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(20)) })
            // نزول من فوق
            sheet.translationY = -ui.dp(70).toFloat(); sheet.alpha = 0f
            sheet.animate().translationY(0f).alpha(1f).setDuration(tokens.pageDur()).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        return true
    }

    // ===== السمات: معرض بمعاينة حيّة =====
    private fun preview(h: TvSkinHost, th: Theme): View {
        val a = h.activity; val ui = h.ui
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
            background = GradientDrawable().apply { setColor(th.bg); cornerRadius = ui.dp(tokens.radiusCard).toFloat(); setStroke(ui.dp(1), th.border) }
        }
        fun rect(fill: Int, r: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { setColor(fill); cornerRadius = ui.dp(r).toFloat(); setStroke(ui.dp(1), stroke) }
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; background = rect(th.surface, 18, th.border); setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)) }
        for ((i, tab) in listOf("🏠 الرئيسية", "🔔", "🎨").withIndex()) {
            bar.addView(ui.text(tab, 13f, if (i == 0) th.primary else th.muted, i == 0).apply { setPadding(0, 0, ui.dp(14), 0) })
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val ban = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; background = rect(th.card, 16, th.border); setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)) }
        ban.addView(View(a).apply { background = rect(th.border, 12) }, LinearLayout.LayoutParams(ui.dp(110), ui.dp(62)))
        val tx = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, 0, 0) }
        tx.addView(ui.text("▶ كمّل المشاهدة", 12f, th.primary, true))
        tx.addView(ui.text(th.name, 15f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val pb = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; background = rect(th.border, 3) }
        pb.addView(View(a).apply { background = rect(th.primary, 3) }, LinearLayout.LayoutParams(0, -1, 60f)); pb.addView(View(a), LinearLayout.LayoutParams(0, -1, 40f))
        tx.addView(pb, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(8) })
        ban.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(ban, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val cards = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for (k in 0 until 3) cards.addView(View(a).apply { background = rect(if (k == 0) th.accent else th.card, 12, th.border) }, LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        root.addView(cards, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        return root
    }

    override fun themes(h: TvSkinHost): Boolean {
        h.page { c ->
            val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
            c.addView(TvSkinHub.tabStrip(h, 2, tokens, true))
            c.addView(ui.text("🎨 معرض السمات", tokens.textTitle + 5f, pal.fg, true).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(2)) })
            c.addView(ui.text("حرّك على أي سمة وشوف المعاينة — OK بتطبّقها", tokens.textCaption + 1f, pal.fgSub).apply { setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(8)) })
            val holder = FrameLayout(a)
            holder.addView(preview(h, h.theme))
            c.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(8)) })
            val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(8)) }
            var shown = h.theme.id
            for (th in Themes.all) {
                val chip = TvTileView(a, tokens.radiusCard - 6)
                chip.inner.setBackgroundColor(th.bg)
                if (th.id == h.theme.id) chip.tag = "skin:first"
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
                val dots = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                for (colr in listOf(th.primary, th.accent, th.surface)) dots.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colr); setStroke(ui.dp(1), th.border) } }, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(6) })
                col.addView(dots)
                col.addView(ui.text((if (th.id == h.theme.id) "✔ " else "") + th.name, tokens.textBody, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPadding(0, ui.dp(8), 0, 0) })
                chip.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
                chip.onFocus = { f -> if (f && shown != th.id) { shown = th.id; holder.removeAllViews(); holder.addView(preview(h, th)) } }
                chip.contentDescription = "سمة " + th.name
                chip.setOnClickListener { if (th.id != h.theme.id) h.setTheme(th.id) }
                strip.addView(chip, LinearLayout.LayoutParams(ui.dp(168), ui.dp(100)).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) })
            }
            c.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(strip) })
            c.addView(View(a), LinearLayout.LayoutParams(-1, ui.dp(24)))
        }
        return true
    }

    // ===== هياكل التحميل =====
    override fun homeSkeleton(h: TvSkinHost): View? {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        val anims = ArrayList<ObjectAnimator>()
        for (i in 0 until 3) {
            val box = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; background = pane(pal.dark, ui.dp(tokens.radiusCard).toFloat(), false, ui.dp(1)); setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)) }
            box.addView(View(a).apply { background = GradientDrawable().apply { setColor(if (pal.dark) 0x33FFFFFF else 0x22000000); cornerRadius = ui.dp(tokens.radiusThumb).toFloat() } }, LinearLayout.LayoutParams(ui.dp(124), ui.dp(70)))
            row.addView(box, LinearLayout.LayoutParams(ui.dp(330), ui.dp(100)).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) })
            anims.add(ObjectAnimator.ofFloat(box, "alpha", 1f, 0.45f).apply { duration = 800L + i * 140L; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE })
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
        val v = TvSkinHub.emptyState(h, kind) ?: return null
        val pal = TvPalette(h.theme)
        v.background = pane(pal.dark, h.ui.dp(tokens.radiusCard + 6).toFloat(), false, h.ui.dp(1))
        return v
    }
}
