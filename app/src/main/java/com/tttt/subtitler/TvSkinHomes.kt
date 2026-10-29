package com.tttt.subtitler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v630) كل سكن بتصميم تلفزيون حقيقي مختلف — مش نفس الـViews متبدّلة الأماكن. الفرق في 4 حاجات مستقلة:
 *  شريط التنقل · شكل الـHero · شكل أيقونات التطبيقات (من appItems الخام مش الدواير المشتركة) · شكل كروت الفولدرات.
 *  • Hub     = Android TV (Leanback): تابات نص بخط تحت · Hero قصير غامق · «التطبيقات المفضلة» بانرات عريضة · «التالي للتشغيل» كروت 16:9 بعنوان تحتها.
 *  • Glass   = Apple tvOS: Hero كبير بعنوان في النص · أيقونات كبيرة مربعة مدوّرة في صف متوسّط (الاسم بيظهر عند التركيز) · رفوف كروت كبيرة.
 *  • Samsung = Tizen: شريط أيقونات جانبي ضيق · Hero حاد بزرار «شاهد الآن» · مربعات تطبيقات صغيرة · كروت حادة.
 *  • LG      = webOS: من غير تابات · Hero مدوّر · 4 كروت مميزات · دوك تطبيقات زجاجي بأيقونات بيضاء مدوّرة · كروت دايرية كبيرة.
 *  • TCL     = Google TV: كبسولة تابات علوية بتحديد أبيض · Hero ملء الشاشة بنقط · «أبرز اختياراتك» صور 16:9 من غير عناوين · دواير تطبيقات.
 *  • Xiaomi  = PatchWall: سايدبار بتسميات على الشمال · حائط بوسترات طولية · شبكة تطبيقات مربعة.
 * الأقسام (يوتيوب · سجل · إعدادات) هي نفس الـViews المتملّية؛ التطبيقات بتيجي بيانات خام (TvAppItem)؛ الفولدرات من h.folders().
 */
object TvHomeLayouts {

    private const val APP_CIRCLE = 0
    private const val APP_BANNER = 1
    private const val APP_SQUARE = 2
    private const val APP_DOCK = 3
    private const val APP_BIG = 4
    private const val APP_GRID = 5

    private const val HERO_GTV = 0
    private const val HERO_ANDROID = 1
    private const val HERO_TIZEN = 2
    private const val HERO_WEBOS = 3
    private const val HERO_TVOS = 4

    // ───────────────────────── أدوات عامة ─────────────────────────
    private fun detach(v: View): View { (v.parent as? ViewGroup)?.removeView(v); return v }

    /** ضيف View لعمود وحافظ على LayoutParams بتاعها لو LinearLayout (مهم لارتفاع الـHero الثابت) */
    private fun put(parent: LinearLayout, v: View) {
        val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(-1, -2)
        parent.addView(detach(v), lp)
    }

    private fun box(h: TvSkinHost, vertical: Boolean): LinearLayout = LinearLayout(h.activity).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        clipChildren = false; clipToPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int, strokeW: Int): GradientDrawable =
        GradientDrawable().apply { setColor(fill); cornerRadius = radius; setStroke(strokeW, stroke) }

    private fun pick(s: TvHomeSections, ids: Set<String>): List<View> =
        s.order.filter { it in ids }.mapNotNull { s.views[it] }

    private fun has(s: TvHomeSections, id: String): Boolean = id in s.order && s.views[id] != null

    private fun screenDp(h: TvSkinHost): Int {
        val dm = h.activity.resources.displayMetrics
        return (dm.widthPixels / dm.density).toInt()
    }

    private fun clock(): String = try {
        java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).format(java.util.Date())
    } catch (_: Throwable) { "" }

    private fun darken(c: Int, f: Float): Int =
        Color.rgb(((c shr 16 and 255) * f).toInt(), ((c shr 8 and 255) * f).toInt(), ((c and 255) * f).toInt())

    private fun panel(h: TvSkinHost, t: TvTokens, child: View, fill: Int, stroke: Int, padDp: Int = 8, radius: Int = t.radiusCard + 6): View {
        val ui = h.ui
        return FrameLayout(h.activity).apply {
            background = shape(fill, ui.dp(radius).toFloat(), stroke, ui.dp(1))
            setPadding(ui.dp(padDp), ui.dp(padDp), ui.dp(padDp), ui.dp(padDp))
            clipChildren = false; clipToPadding = false
            addView(detach(child), FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun gapLp(h: TvSkinHost, topDp: Int = 10): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = h.ui.dp(topDp) }

    /** أعمدة بأوزان؛ heightDp > 0 = ارتفاع ثابت للكل */
    private fun columns(h: TvSkinHost, items: List<Pair<View, Float>>, heightDp: Int = 0): View {
        val ui = h.ui
        val row = box(h, false)
        items.forEachIndexed { i, item ->
            row.addView(item.first, LinearLayout.LayoutParams(0, if (heightDp > 0) ui.dp(heightDp) else -2, item.second).apply { if (i > 0) marginStart = ui.dp(12) })
        }
        return row
    }

    private fun hscroll(h: TvSkinHost, items: List<View>): View {
        val inner = box(h, false)
        items.forEach { inner.addView(it) }
        return HorizontalScrollView(h.activity).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(inner)
        }
    }

    /** صف أفقي متوسّط (tvOS): لو المحتوى أقل من الشاشة يتوسّط، ولو أكتر يتسكرول */
    private fun hscrollCentered(h: TvSkinHost, items: List<View>): View {
        val inner = box(h, false).apply { gravity = Gravity.CENTER_HORIZONTAL }
        items.forEach { inner.addView(it) }
        return HorizontalScrollView(h.activity).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFillViewport = true
            addView(inner, FrameLayout.LayoutParams(-1, -2))
        }
    }

    /** شبكة عمودية بعدد أعمدة ثابت (من غير سكرول أفقي) */
    private fun grid(h: TvSkinHost, items: List<View>, cols: Int): View {
        val col = box(h, true)
        var row: LinearLayout? = null
        items.forEachIndexed { i, v ->
            if (i % cols == 0) { row = box(h, false); col.addView(row, LinearLayout.LayoutParams(-1, -2)) }
            row!!.addView(v)
        }
        return col
    }

    /** عنوان قسم: 0 شريط لون · 1 نص صغير مفرّد · 2 كبسولة · 3 نص متوسط (Leanback) · 4 نص كبير (Google TV / tvOS) */
    private fun heading(h: TvSkinHost, t: TvTokens, text: String, style: Int): View {
        val ui = h.ui; val pal = TvPalette(h.theme); val a = h.activity
        val row = box(h, false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(6)) }
        when (style) {
            0 -> {
                row.addView(View(a).apply { setBackgroundColor(pal.primary) }, LinearLayout.LayoutParams(ui.dp(4), ui.dp(22)).apply { marginEnd = ui.dp(8) })
                row.addView(ui.text(text, t.textTitle + 3f, pal.fg, true))
            }
            1 -> row.addView(ui.text(text, t.textCaption + 1f, pal.fgSub, true).apply { letterSpacing = 0.08f })
            2 -> row.addView(ui.text(text, t.textBody + 1f, pal.onPrimary, true).apply {
                background = shape(pal.primary, ui.dp(t.radiusPill).toFloat(), pal.primary, 0)
                setPadding(ui.dp(14), ui.dp(5), ui.dp(14), ui.dp(5))
            })
            3 -> row.addView(ui.text(text, t.textBody + 2f, pal.fg, true))
            else -> row.addView(ui.text(text, t.textTitle + 4f, pal.fg, true))
        }
        return row
    }

    // ───────────────────────── مكوّنات مرسومة ─────────────────────────
    private fun tile(h: TvSkinHost, t: TvTokens, radius: Int, fill: Int, stroke: Int): TvTileView {
        val pal = TvPalette(h.theme)
        val c = TvTileView(h.activity, radius).styled(t, pal.primary)
        c.inner.background = shape(fill, h.ui.dp(radius).toFloat(), stroke, h.ui.dp(1))
        return c
    }

    private fun scrim(h: TvSkinHost): View = View(h.activity).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xEE000000.toInt(), 0x00000000))
    }

    private fun progressBar(h: TvSkinHost, percent: Int): View {
        val a = h.activity; val pal = TvPalette(h.theme)
        val bar = box(h, false).apply { setBackgroundColor(0x55FFFFFF) }
        val p = percent.coerceIn(0, 100)
        if (p > 0) bar.addView(View(a).apply { setBackgroundColor(pal.primary) }, LinearLayout.LayoutParams(0, -1, p.toFloat()))
        if (p < 100) bar.addView(View(a), LinearLayout.LayoutParams(0, -1, (100 - p).coerceAtLeast(1).toFloat()))
        return bar
    }

    private fun meta(r: Recent): String =
        if (r.posSec > 0.0) PlayerLogic.clock((r.posSec * 1000).toLong()) + " · " + r.percent + "%" else "ابدأ من الأول"

    private fun dots(h: TvSkinHost, n: Int): View {
        val a = h.activity; val ui = h.ui
        val row = box(h, false).apply { gravity = Gravity.CENTER_VERTICAL }
        for (i in 0 until n) {
            row.addView(View(a).apply {
                background = shape(if (i == 0) Color.WHITE else 0x66FFFFFF, ui.dp(4).toFloat(), 0, 0)
            }, LinearLayout.LayoutParams(ui.dp(if (i == 0) 16 else 7), ui.dp(7)).apply { marginEnd = ui.dp(5) })
        }
        return row
    }

    /** زرار دايري صغير في الشريط (إعدادات/سكنات) */
    private fun roundButton(h: TvSkinHost, t: TvTokens, glyph: String, action: () -> Unit): View {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val tv: TextView = ui.text(glyph, t.textBody + 3f, pal.fg, true)
        val c = TvOwnLinear(a).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true; contentDescription = glyph
            setOnClickListener { action() }
        }
        c.addView(tv)
        fun paint(f: Boolean) {
            c.background = shape(if (f) pal.fg else 0x00000000, ui.dp(20).toFloat(), 0x00000000, 0)
            tv.setTextColor(if (f) (if (pal.dark) Color.BLACK else Color.WHITE) else pal.fg)
        }
        paint(false)
        c.setOnFocusChangeListener { _, f -> paint(f) }
        c.layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(2) }
        return c
    }

    // ───────────────────────── Hero (5 أشكال) ─────────────────────────
    private fun hero(h: TvSkinHost, t: TvTokens, kind: Int): View {
        val r = h.lastRecent()
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val radius = when (kind) { HERO_GTV -> 0; HERO_ANDROID -> 8; HERO_TIZEN -> 2; HERO_WEBOS -> 28; else -> 24 }
        val heightDp = when (kind) { HERO_GTV -> 236; HERO_ANDROID -> 150; HERO_TIZEN -> 206; HERO_WEBOS -> 192; else -> 280 }
        val card = tile(h, t, radius, pal.cardFill, pal.border)
        if (r != null) {
            card.tag = "skin:first"
            card.contentDescription = "كمّل المشاهدة: " + r.title
            card.setOnClickListener { h.playRecent(r) }
        } else { card.isFocusable = false; card.isClickable = false }
        val img = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(pal.border) }
        if (r != null) h.recentThumbInto(r, img)
        card.inner.addView(img, FrameLayout.LayoutParams(-1, -1))
        if (kind == HERO_ANDROID) card.inner.addView(View(a).apply { setBackgroundColor(0xBB0B1530.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        if (kind == HERO_WEBOS) card.inner.addView(View(a).apply { setBackgroundColor(0x55000000) }, FrameLayout.LayoutParams(-1, -1))
        card.inner.addView(scrim(h), FrameLayout.LayoutParams(-1, -1))

        val title = r?.title ?: "أهلًا بيك في Joyboy"
        val sub = if (r != null) meta(r) else "شغّل أي فيديو وهيظهر هنا للمتابعة"
        val pct = r?.percent ?: 0
        val col = box(h, true)
        fun ttl(sz: Float): TextView = ui.text(title, sz, Color.WHITE, true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, ui.dp(5), 0, ui.dp(4))
        }
        when (kind) {
            HERO_GTV -> {
                col.gravity = Gravity.BOTTOM; col.setPadding(ui.dp(26), ui.dp(14), ui.dp(26), ui.dp(18))
                col.addView(ui.text("▶ JOYBOY", t.textCaption + 2f, Color.WHITE, true).apply { letterSpacing = 0.12f })
                col.addView(ttl(t.textTitle + 13f))
                col.addView(ui.text("رائج  |  " + sub, t.textBody, 0xFFE3E3E3.toInt()))
                col.addView(dots(h, 4), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(12) })
            }
            HERO_ANDROID -> {
                col.gravity = Gravity.CENTER_VERTICAL; col.setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(10))
                col.addView(ui.text("كمّل المشاهدة", t.textCaption, 0xFFB7C4E8.toInt()))
                col.addView(ttl(t.textTitle + 8f))
                col.addView(ui.text(sub, t.textBody, 0xFFC9D2EC.toInt()))
            }
            HERO_TIZEN -> {
                col.gravity = Gravity.BOTTOM; col.setPadding(ui.dp(22), ui.dp(12), ui.dp(22), ui.dp(16))
                col.addView(ui.text("CONTINUE WATCHING", t.textCaption, pal.primary, true).apply { letterSpacing = 0.14f })
                col.addView(ttl(t.textTitle + 10f))
                col.addView(ui.text(sub, t.textBody, 0xFFDDDDDD.toInt()))
                col.addView(ui.text("▶  شاهد الآن", t.textBody, Color.WHITE, true).apply {
                    background = shape(0x22FFFFFF, ui.dp(4).toFloat(), Color.WHITE, ui.dp(1))
                    setPadding(ui.dp(18), ui.dp(7), ui.dp(18), ui.dp(7))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(10) })
            }
            HERO_WEBOS -> {
                col.gravity = Gravity.TOP; col.setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(16))
                col.addView(ui.text("JOYBOY", t.textCaption + 1f, Color.WHITE, true).apply { letterSpacing = 0.2f })
                col.addView(ttl(t.textTitle + 14f))
                col.addView(View(a), LinearLayout.LayoutParams(-1, 0, 1f))
                val bottom = box(h, false).apply { gravity = Gravity.CENTER_VERTICAL }
                bottom.addView(ui.text(sub, t.textBody, 0xFFEEEEEE.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
                bottom.addView(ui.text("شاهد الآن", t.textCaption + 1f, pal.onPrimary, true).apply {
                    background = shape(pal.primary, ui.dp(t.radiusPill).toFloat(), pal.primary, 0)
                    setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
                })
                col.addView(bottom, LinearLayout.LayoutParams(-1, -2))
            }
            else -> {
                col.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; col.setPadding(ui.dp(24), ui.dp(14), ui.dp(24), ui.dp(26))
                col.addView(ttl(t.textTitle + 17f).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
                col.addView(ui.text(sub, t.textBody + 1f, 0xFFE6E6E6.toInt()).apply { gravity = Gravity.CENTER })
                col.addView(progressBar(h, pct), LinearLayout.LayoutParams(ui.dp(200), ui.dp(4)).apply { topMargin = ui.dp(10) })
            }
        }
        card.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
        val side = if (kind == HERO_GTV || kind == HERO_TVOS) 0 else 12
        card.layoutParams = LinearLayout.LayoutParams(-1, ui.dp(heightDp)).apply { setMargins(ui.dp(side), ui.dp(6), ui.dp(side), ui.dp(10)) }
        return card
    }

    // ───────────────────────── أيقونات التطبيقات (6 أشكال) ─────────────────────────
    /** أيقونة دايرية متقطّعة → مربع مدوّر ممتلئ (tvOS) */
    private fun squircle(src: Bitmap, px: Int, fill: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val path = Path().apply { addRoundRect(0f, 0f, px.toFloat(), px.toFloat(), px * 0.24f, px * 0.24f, Path.Direction.CW) }
        cv.clipPath(path)
        cv.drawColor(fill)
        val w = (px * 1.18f).toInt()
        val l = (px - w) / 2
        cv.drawBitmap(src, null, Rect(l, l, l + w, l + w), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun appTile(h: TvSkinHost, t: TvTokens, item: TvAppItem, kind: Int): View {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val base = item.glow ?: pal.primary
        val boxW: Int; val boxH: Int; val radius: Int; val iconDp: Int; val fill: Int
        when (kind) {
            APP_CIRCLE -> { boxW = 64; boxH = 64; radius = 32; iconDp = 64; fill = 0xFFF1F3F4.toInt() }
            APP_BANNER -> { boxW = 150; boxH = 86; radius = 8; iconDp = 38; fill = darken(base, 0.55f) }
            APP_SQUARE -> { boxW = 68; boxH = 68; radius = 8; iconDp = 40; fill = darken(base, 0.5f) }
            APP_DOCK -> { boxW = 56; boxH = 56; radius = 15; iconDp = 40; fill = 0xEEFFFFFF.toInt() }
            APP_BIG -> { boxW = 96; boxH = 96; radius = 22; iconDp = 96; fill = 0x00000000 }
            else -> { boxW = 78; boxH = 78; radius = 12; iconDp = 46; fill = darken(base, 0.5f) }
        }
        val src = item.icon
        val iconBmp: Bitmap? = if (kind == APP_BIG && src != null) squircle(src, ui.dp(iconDp), base) else src
        val iv = ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (iconBmp != null) setImageBitmap(iconBmp)
        }
        val holder = FrameLayout(a).apply { clipChildren = false }
        if (kind == APP_BANNER) {
            val inside = box(h, true).apply { gravity = Gravity.CENTER }
            inside.addView(iv, LinearLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp)))
            inside.addView(ui.text(item.label, t.textCaption + 0.5f, Color.WHITE, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0)
            }, LinearLayout.LayoutParams(-1, -2))
            holder.addView(inside, FrameLayout.LayoutParams(-1, -1))
        } else {
            holder.addView(iv, FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp), Gravity.CENTER))
        }
        val showLabel = kind != APP_BANNER && kind != APP_DOCK
        val lab: TextView = ui.text(item.label, if (kind == APP_BIG) t.textBody + 1.5f else t.textCaption, pal.fg, kind == APP_BIG).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setPadding(0, ui.dp(6), 0, 0)
            if (kind == APP_BIG) alpha = 0f else if (kind == APP_SQUARE) alpha = 0.8f
        }
        val root = TvOwnLinear(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false
            contentDescription = item.label
            setOnClickListener { item.onClick() }
        }
        val lg = item.onLong
        if (lg != null) root.setOnLongClickListener { lg(); true }
        root.addView(holder, LinearLayout.LayoutParams(ui.dp(boxW), ui.dp(boxH)))
        if (showLabel) root.addView(lab, LinearLayout.LayoutParams(ui.dp(boxW + 14), -2))
        fun paint(f: Boolean) {
            holder.background = shape(fill, ui.dp(radius).toFloat(), if (f) Color.WHITE else 0x00000000, if (f) ui.dp(3) else ui.dp(1))
            val sc = if (f) (if (kind == APP_BIG) 1.18f else if (kind == APP_BANNER) 1.08f else 1.12f) else 1f
            root.animate().scaleX(sc).scaleY(sc).translationY(if (f && kind == APP_BIG) -ui.dp(8).toFloat() else 0f).setDuration(t.focusDur()).start()
            if (kind == APP_BIG) lab.animate().alpha(if (f) 1f else 0f).setDuration(t.focusDur()).start()
            else if (kind == APP_SQUARE) lab.alpha = if (f) 1f else 0.8f
        }
        paint(false)
        root.setOnFocusChangeListener { _, f -> paint(f) }
        root.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(7), ui.dp(12), ui.dp(7), ui.dp(12)) }
        return root
    }

    private fun appRow(h: TvSkinHost, t: TvTokens, s: TvHomeSections, kind: Int): View {
        if (s.appItems.isEmpty()) return s.views["apps"]?.let { detach(it) } ?: View(h.activity)
        return hscroll(h, s.appItems.map { appTile(h, t, it, kind) })
    }

    private fun appGrid(h: TvSkinHost, t: TvTokens, s: TvHomeSections, kind: Int, cols: Int): View {
        if (s.appItems.isEmpty()) return s.views["apps"]?.let { detach(it) } ?: View(h.activity)
        return grid(h, s.appItems.map { appTile(h, t, it, kind) }, cols)
    }

    // ───────────────────────── كروت الفولدرات (3 أنماط نص) ─────────────────────────
    /** mode: 0 = العنوان فوق الصورة (بوستر) · 1 = الصورة فوق والعنوان تحت · 2 = صورة بس والعنوان بيظهر عند التركيز */
    private fun mediaCard(h: TvSkinHost, t: TvTokens, f: FolderItem, wDp: Int, hDp: Int, radius: Int, mode: Int): View {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val card = tile(h, t, radius, pal.cardFill, pal.border)
        val img = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(pal.border) }
        val ph = ui.text(f.icon.ifBlank { "📁" }, 30f, pal.fg).apply { gravity = Gravity.CENTER }
        val first = f.videos.firstOrNull()
        if (first != null) h.loadThumb(first) { bmp -> img.setImageBitmap(bmp); ph.visibility = View.GONE }
        val count = f.videos.size.toString() + " فيديو"
        fun labelCol(): LinearLayout {
            val col = box(h, true).apply { gravity = Gravity.BOTTOM; setPadding(ui.dp(9), ui.dp(8), ui.dp(9), ui.dp(9)) }
            col.addView(ui.text(f.name, t.textBody, Color.WHITE, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            })
            col.addView(ui.text(count, t.textCaption, 0xFFCCCCCC.toInt()))
            return col
        }
        when (mode) {
            0 -> {
                card.inner.addView(img, FrameLayout.LayoutParams(-1, -1))
                card.inner.addView(ph, FrameLayout.LayoutParams(-1, -1))
                card.inner.addView(scrim(h), FrameLayout.LayoutParams(-1, -1))
                card.inner.addView(labelCol(), FrameLayout.LayoutParams(-1, -1))
            }
            2 -> {
                card.inner.addView(img, FrameLayout.LayoutParams(-1, -1))
                card.inner.addView(ph, FrameLayout.LayoutParams(-1, -1))
                val ov = FrameLayout(a).apply { visibility = View.GONE }
                ov.addView(scrim(h), FrameLayout.LayoutParams(-1, -1))
                ov.addView(labelCol(), FrameLayout.LayoutParams(-1, -1))
                card.inner.addView(ov, FrameLayout.LayoutParams(-1, -1))
                card.onFocus = { fo -> ov.visibility = if (fo) View.VISIBLE else View.GONE }
            }
            else -> {
                val colAll = box(h, true)
                val imgBox = FrameLayout(a)
                imgBox.addView(img, FrameLayout.LayoutParams(-1, -1))
                imgBox.addView(ph, FrameLayout.LayoutParams(-1, -1))
                colAll.addView(imgBox, LinearLayout.LayoutParams(-1, ui.dp(hDp * 62 / 100)))
                colAll.addView(ui.text(f.name, t.textBody, pal.fg, true).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(ui.dp(9), ui.dp(6), ui.dp(9), 0)
                })
                colAll.addView(ui.text(count, t.textCaption, pal.fgSub).apply { setPadding(ui.dp(9), ui.dp(2), ui.dp(9), ui.dp(6)) })
                card.inner.addView(colAll, FrameLayout.LayoutParams(-1, -1))
            }
        }
        card.setOnClickListener { h.openFolder(f) }
        card.setOnLongClickListener {
            h.menu("📁 " + f.name.take(40), listOf("▶ افتح الفولدر" to { h.openFolder(f) }))
            true
        }
        card.contentDescription = f.name + "، " + f.videos.size + " فيديو"
        val m = ui.dp(6)
        card.layoutParams = LinearLayout.LayoutParams(ui.dp(wDp), ui.dp(hDp)).apply { setMargins(m, m, m, m) }
        return card
    }

    /** قسم فولدرات بيتملّى لما الفحص يخلص؛ build بيحوّل القايمة لـView بشكل السكِن */
    private fun foldersSection(h: TvSkinHost, build: (List<FolderItem>) -> View): View {
        val ui = h.ui; val pal = TvPalette(h.theme)
        val holder = box(h, true)
        fun fill() {
            holder.removeAllViews()
            val fs = h.folders()
            val perm = h.hasVideoPermission()
            if (fs == null || fs.isEmpty()) {
                val msg = if (!perm) "📂 اضغط لمنح صلاحية الفيديوهات" else if (fs == null) "⏳ بقرا الفيديوهات…" else "مفيش فولدرات فيديو لسه"
                holder.addView(ui.text(msg, 16f, pal.fgSub).apply {
                    setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                    if (!perm) { isFocusable = true; setOnClickListener { h.requestVideoPermission() } }
                })
            } else holder.addView(build(fs), LinearLayout.LayoutParams(-1, -2))
        }
        fill()
        if (h.folders() == null && h.hasVideoPermission()) h.ensureScan { fill() }
        return holder
    }

    private fun cols(h: TvSkinHost, cardW: Int, reservedDp: Int): Int =
        ((screenDp(h) - reservedDp) / (cardW + 12)).coerceIn(2, 9)

    private fun featureCard(h: TvSkinHost, t: TvTokens, glyph: String, title: String, sub: String, fill: Int, fg: Int, action: () -> Unit): View {
        val ui = h.ui; val pal = TvPalette(h.theme)
        val card = tile(h, t, 22, fill, pal.border)
        val col = box(h, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
        col.addView(ui.text(glyph, 28f, fg))
        col.addView(ui.text(title, t.textTitle, fg, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        col.addView(ui.text(sub, t.textCaption + 1f, fg).apply { alpha = 0.8f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        card.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
        card.setOnClickListener { action() }
        card.contentDescription = title
        return card
    }

    // ───────────────────────── Hub = Android TV (Leanback) ─────────────────────────
    fun dashboard(h: TvSkinHost, t: TvTokens, s: TvHomeSections): List<View> {
        val ui = h.ui; val pal = TvPalette(h.theme)
        val home = ArrayList<View>()
        home.add(hero(h, t, HERO_ANDROID))
        if (has(s, "apps")) { home.add(heading(h, t, "التطبيقات المفضلة", 3)); home.add(appRow(h, t, s, APP_BANNER)) }
        if (has(s, "vids")) {
            home.add(heading(h, t, "التالي للتشغيل", 3))
            home.add(foldersSection(h) { fs -> hscroll(h, fs.map { mediaCard(h, t, it, 176, 128, 6, 1) }) })
        }
        home.addAll(pick(s, setOf("hist", "yt", "pin")))
        val lib = ArrayList<View>()
        if (has(s, "vids")) {
            val c = cols(h, 176, 40)
            lib.add(foldersSection(h) { fs -> grid(h, fs.map { mediaCard(h, t, it, 176, 128, 6, 1) }, c) })
        }
        val apps = ArrayList<View>()
        if (has(s, "apps")) apps.add(appGrid(h, t, s, APP_BANNER, cols(h, 150, 40)))
        val tabs = ArrayList<Pair<String, List<View>>>()
        tabs.add(Pair("الرئيسية", home))
        tabs.add(Pair("المكتبة", lib))
        tabs.add(Pair("التطبيقات", apps))
        tabs.add(Pair("الإعدادات", listOf(s.settings)))
        val (navView, body) = tabStrip(h, t, tabs.filter { it.second.isNotEmpty() }, style = 4)
        val bar = box(h, false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(2)) }
        bar.addView(navView, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ui.text(clock(), t.textBody + 1f, pal.fgSub, true))
        return listOf(bar, body)
    }

    // ───────────────────────── Glass = Apple tvOS ─────────────────────────
    fun bento(h: TvSkinHost, t: TvTokens, s: TvHomeSections): List<View> {
        val pal = TvPalette(h.theme)
        val fill = (pal.surface and 0x00FFFFFF) or 0x99000000.toInt()
        val out = ArrayList<View>()
        out.add(hero(h, t, HERO_TVOS))
        if (has(s, "apps") && s.appItems.isNotEmpty()) out.add(hscrollCentered(h, s.appItems.map { appTile(h, t, it, APP_BIG) }))
        else if (has(s, "apps")) out.add(appRow(h, t, s, APP_BIG))
        if (has(s, "vids")) {
            out.add(heading(h, t, "التالي", 4))
            out.add(foldersSection(h) { fs -> hscroll(h, fs.map { mediaCard(h, t, it, 264, 190, 16, 1) }) })
        }
        val pair = ArrayList<Pair<View, Float>>()
        if (has(s, "hist")) pair.add(Pair<View, Float>(panel(h, t, s.views["hist"]!!, fill, pal.border, 10, 26), 1f))
        if (has(s, "yt")) pair.add(Pair<View, Float>(panel(h, t, s.views["yt"]!!, fill, pal.border, 10, 26), 1f))
        if (pair.isNotEmpty()) out.add(columns(h, pair).also { it.layoutParams = gapLp(h, 14) })
        if (has(s, "pin")) out.add(panel(h, t, s.views["pin"]!!, fill, pal.border, 10, 26).also { it.layoutParams = gapLp(h, 14) })
        out.add(panel(h, t, s.settings, fill, pal.border, 10, 26).also { it.layoutParams = gapLp(h, 14) })
        return out
    }

    // ───────────────────────── Samsung = Tizen ─────────────────────────
    fun iconTabs(h: TvSkinHost, t: TvTokens, s: TvHomeSections): List<View> {
        val ui = h.ui; val pal = TvPalette(h.theme)
        val home = ArrayList<View>()
        home.add(hero(h, t, HERO_TIZEN))
        if (has(s, "apps")) home.add(appRow(h, t, s, APP_SQUARE))
        home.addAll(pick(s, setOf("hist")))
        if (has(s, "vids")) {
            home.add(heading(h, t, "مقترح لك", 1))
            home.add(foldersSection(h) { fs -> hscroll(h, fs.map { mediaCard(h, t, it, 236, 150, 2, 1) }) })
        }
        home.addAll(pick(s, setOf("yt", "pin")))
        val media = ArrayList<View>()
        if (has(s, "vids")) {
            val c = cols(h, 236, 100)
            media.add(foldersSection(h) { fs -> grid(h, fs.map { mediaCard(h, t, it, 236, 150, 2, 1) }, c) })
        }
        val apps = ArrayList<View>()
        if (has(s, "apps")) apps.add(appGrid(h, t, s, APP_SQUARE, cols(h, 68, 100)))
        val tabs = ArrayList<Pair<String, List<View>>>()
        tabs.add(Pair("🏠", home))
        tabs.add(Pair("🎞", media))
        tabs.add(Pair("▦", apps))
        tabs.add(Pair("⚙", listOf(s.settings)))
        val (navView, body) = tabStrip(h, t, tabs.filter { it.second.isNotEmpty() }, style = 5)
        val rail = FrameLayout(h.activity).apply {
            background = shape((pal.surface and 0x00FFFFFF) or 0x66000000, ui.dp(30).toFloat(), 0x00000000, 0)
            setPadding(ui.dp(5), ui.dp(12), ui.dp(5), ui.dp(12))
            addView(navView, FrameLayout.LayoutParams(-1, -2))
        }
        val row = box(h, false)
        row.addView(rail, LinearLayout.LayoutParams(ui.dp(64), -2))
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
        return listOf(row)
    }

    // ───────────────────────── LG = webOS ─────────────────────────
    fun launcherBand(h: TvSkinHost, t: TvTokens, s: TvHomeSections): List<View> {
        val pal = TvPalette(h.theme)
        val card = (pal.surface and 0x00FFFFFF) or 0xAA000000.toInt()
        val dock = (pal.surface and 0x00FFFFFF) or 0xB0000000.toInt()
        val out = ArrayList<View>()
        out.add(hero(h, t, HERO_WEBOS))
        val feats = ArrayList<Pair<View, Float>>()
        val r = h.lastRecent()
        if (r != null) feats.add(Pair<View, Float>(featureCard(h, t, "▶", "آخر تشغيل", r.title.take(30), pal.primary, pal.onPrimary) { h.playRecent(r) }, 1f))
        val yt = s.appItems.firstOrNull { it.label == "يوتيوب" }
        if (yt != null) feats.add(Pair<View, Float>(featureCard(h, t, "📺", "يوتيوب", "اشتراكاتك وفيديوهاتك", card, pal.fg) { yt.onClick() }, 1f))
        val br = s.appItems.firstOrNull { it.label == "المتصفح" }
        if (br != null) feats.add(Pair<View, Float>(featureCard(h, t, "🌐", "المتصفح", "افتح أي موقع", card, pal.fg) { br.onClick() }, 1f))
        feats.add(Pair<View, Float>(featureCard(h, t, "🎨", "المظهر", "السكنات والسمات", card, pal.fg) { h.open(TvPage.LAYOUT) }, 1f))
        out.add(heading(h, t, "ميزات", 1))
        out.add(columns(h, feats, 124))
        if (has(s, "apps")) {
            out.add(heading(h, t, "قائمة التطبيقات", 1))
            out.add(panel(h, t, appRow(h, t, s, APP_DOCK), dock, pal.border, 6, 36).also { it.layoutParams = gapLp(h, 2) })
        }
        if (has(s, "vids")) {
            out.add(heading(h, t, "مكتبتك", 1))
            out.add(foldersSection(h) { fs -> hscroll(h, fs.map { mediaCard(h, t, it, 220, 210, 28, 1) }) })
        }
        val pair = ArrayList<Pair<View, Float>>()
        if (has(s, "hist")) pair.add(Pair<View, Float>(panel(h, t, s.views["hist"]!!, card, pal.border, 10, 28), 1f))
        if (has(s, "yt")) pair.add(Pair<View, Float>(panel(h, t, s.views["yt"]!!, card, pal.border, 10, 28), 1f))
        if (pair.isNotEmpty()) out.add(columns(h, pair).also { it.layoutParams = gapLp(h, 14) })
        out.addAll(pick(s, setOf("pin")))
        out.add(heading(h, t, "الإعدادات", 1)); out.add(detach(s.settings))
        return out
    }

    // ───────────────────────── TCL = Google TV ─────────────────────────
    fun topTabs(h: TvSkinHost, t: TvTokens, s: TvHomeSections): List<View> {
        val ui = h.ui; val pal = TvPalette(h.theme)
        val home = ArrayList<View>()
        home.add(hero(h, t, HERO_GTV))
        if (has(s, "vids")) {
            home.add(heading(h, t, "أبرز اختياراتك", 4))
            home.add(foldersSection(h) { fs -> hscroll(h, fs.map { mediaCard(h, t, it, 168, 95, 12, 2) }) })
        }
        home.addAll(pick(s, setOf("hist", "pin")))
        if (has(s, "apps")) { home.add(heading(h, t, "تطبيقاتك", 4)); home.add(appRow(h, t, s, APP_CIRCLE)) }
        val apps = ArrayList<View>()
        if (has(s, "apps")) apps.add(appGrid(h, t, s, APP_CIRCLE, cols(h, 78, 40)))
        val tabs = ArrayList<Pair<String, List<View>>>()
        tabs.add(Pair("الرئيسية", home))
        tabs.add(Pair("يوتيوب", pick(s, setOf("yt"))))
        tabs.add(Pair("التطبيقات", apps))
        tabs.add(Pair("الإعدادات", listOf(s.settings)))
        val (navView, body) = tabStrip(h, t, tabs.filter { it.second.isNotEmpty() }, style = 3)
        val capsule = FrameLayout(h.activity).apply {
            background = shape(pal.cardFill, ui.dp(t.radiusPill).toFloat(), pal.border, ui.dp(1))
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            addView(navView, FrameLayout.LayoutParams(-2, -2))
        }
        val tools = box(h, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(pal.cardFill, ui.dp(t.radiusPill).toFloat(), pal.border, ui.dp(1))
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        tools.addView(roundButton(h, t, "⚙") { h.open(TvPage.APPEARANCE) })
        tools.addView(roundButton(h, t, "🧩") { h.open(TvPage.LAYOUT) })
        val top = box(h, false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(4)) }
        top.addView(capsule, LinearLayout.LayoutParams(-2, -2))
        top.addView(tools, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(10) })
        top.addView(View(h.activity), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ui.text(clock() + "   Joyboy TV", t.textBody, pal.fgSub, true))
        return listOf(top, body)
    }

    // ───────────────────────── Xiaomi = PatchWall ─────────────────────────
    fun sidebar(h: TvSkinHost, t: TvTokens, s: TvHomeSections): List<View> {
        val pal = TvPalette(h.theme)
        val ui = h.ui
        val wall = ArrayList<View>()
        if (has(s, "hist")) wall.add(detach(s.views["hist"]!!))
        if (has(s, "vids")) {
            val c = cols(h, 128, 230)
            wall.add(foldersSection(h) { fs -> grid(h, fs.map { mediaCard(h, t, it, 128, 190, 8, 0) }, c) })
        }
        wall.addAll(pick(s, setOf("pin")))
        val apps = ArrayList<View>()
        if (has(s, "apps")) apps.add(appGrid(h, t, s, APP_GRID, cols(h, 78, 230)))
        val tabs = ArrayList<Pair<String, List<View>>>()
        tabs.add(Pair("▶ المشاهدة", wall))
        tabs.add(Pair("📺 يوتيوب", pick(s, setOf("yt"))))
        tabs.add(Pair("🧩 التطبيقات", apps))
        tabs.add(Pair("⚙ الإعدادات", listOf(s.settings)))
        val (navView, body) = tabStrip(h, t, tabs.filter { it.second.isNotEmpty() }, style = 2)
        val side = FrameLayout(h.activity).apply {
            background = shape(pal.cardFill, ui.dp(t.radiusCard + 4).toFloat(), pal.border, ui.dp(1))
            setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
            addView(navView, FrameLayout.LayoutParams(-1, -2))
        }
        // السايدبار على الشمال (عكس Samsung اللي على اليمين)
        val row = box(h, false).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        row.addView(side, LinearLayout.LayoutParams(ui.dp(170), -2))
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
        return listOf(row)
    }

    // ───────────────────────── محرّك التابات ─────────────────────────
    /**
     * style: 2 = سايدبار عمودي بتسميات (Xiaomi) · 3 = كبسولات بتحديد أبيض (Google TV) ·
     *        4 = نص بخط تحت (Android TV) · 5 = شريط أيقونات عمودي (Tizen). التبديل بالتركيز أو OK.
     */
    private fun tabStrip(h: TvSkinHost, t: TvTokens, tabs: List<Pair<String, List<View>>>, style: Int): Pair<View, View> {
        val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
        val vertical = style == 2 || style == 5
        val bodies = ArrayList<View>()
        for (tab in tabs) {
            val col = box(h, true)
            tab.second.forEach { put(col, it) }
            bodies.add(col)
        }
        val body = FrameLayout(a)
        bodies.forEach { body.addView(it, FrameLayout.LayoutParams(-1, -2)) }

        val nav = box(h, vertical)
        val chips = ArrayList<TextView>()
        var sel = 0
        fun paint() { for (k in chips.indices) paintChip(h, t, pal, chips[k], k == sel, chips[k].isFocused, style) }
        fun choose(i: Int) {
            sel = i
            for (k in bodies.indices) bodies[k].visibility = if (k == i) View.VISIBLE else View.GONE
            paint()
        }
        tabs.forEachIndexed { i, tab ->
            val c: TextView = ui.text(tab.first, if (style == 5) t.textBody + 8f else t.textBody + 2f, pal.fg, true).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                if (style == 4) setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(10)) else setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8))
                setOnClickListener { choose(i) }
                setOnFocusChangeListener { _, f -> if (f) choose(i) else paint() }
            }
            chips.add(c)
            val lp = if (vertical) LinearLayout.LayoutParams(-1, ui.dp(if (style == 5) 54 else 52)).apply { bottomMargin = ui.dp(6) }
                     else LinearLayout.LayoutParams(-2, ui.dp(if (style == 4) 44 else 42)).apply { marginEnd = ui.dp(if (style == 4) 14 else 4) }
            nav.addView(c, lp)
        }
        if (bodies.isNotEmpty()) choose(0)
        return Pair(nav, body)
    }

    private fun paintChip(h: TvSkinHost, t: TvTokens, pal: TvPalette, c: TextView, selected: Boolean, focused: Boolean, style: Int) {
        val ui = h.ui
        val darkText = 0xFF202124.toInt()
        val sc = if (focused) (if (style == 4) 1.04f else 1.06f) else 1f
        c.scaleX = sc; c.scaleY = sc
        if (style == 4) {
            val bar = GradientDrawable().apply { setColor(if (selected) (if (focused) pal.fg else pal.primary) else 0x00000000) }
            val ld = LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(bar))
            ld.setLayerGravity(0, Gravity.BOTTOM); ld.setLayerHeight(0, ui.dp(3))
            c.background = ld
            c.setTextColor(if (selected || focused) pal.fg else pal.fgSub)
            return
        }
        val r = ui.dp(when (style) { 2 -> t.radiusCard; 5 -> 16; else -> t.radiusPill }).toFloat()
        val fill: Int = when (style) {
            3 -> if (selected) pal.fg else 0x00000000
            5 -> if (selected) ((pal.fg and 0x00FFFFFF) or 0x2E000000) else 0x00000000
            else -> if (selected) pal.primary else pal.surface
        }
        val stroke: Int = if (focused) pal.fg else if (style == 3 || style == 5) 0x00000000 else pal.border
        c.background = shape(fill, r, stroke, ui.dp(if (focused) 3 else 1))
        val txt: Int = when (style) {
            3 -> if (selected) (if (pal.dark) darkText else Color.WHITE) else pal.fg
            5 -> pal.fg
            else -> if (selected) pal.onPrimary else pal.fg
        }
        c.setTextColor(txt)
    }
}
