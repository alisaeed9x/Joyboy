package com.tttt.subtitler

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Manufacturer-inspired Joyboy layouts. These use original Joyboy components and broad interaction
 * patterns; no proprietary screenshots, artwork, logos, or firmware are embedded.
 * Important: home() deliberately returns false so the existing launcher rows (apps, browser,
 * YouTube, local media and shortcuts) remain intact. Each Skin customizes the shared home header,
 * folder-card composition, focus tokens and the Skin-first Theme route instead of replacing the app.
 */
private enum class BrandLayout { SAMSUNG, LG, TCL, XIAOMI }

private class ManufacturerTvSkin(
    override val id: String,
    override val name: String,
    private val layout: BrandLayout,
    override val tokens: TvTokens,
    override val themeIds: List<String>
) : TvSkin {

    override fun home(h: TvSkinHost, focus: Boolean, reuse: Boolean): Boolean = false

    override fun homeHeader(h: TvSkinHost): View = when (layout) {
        BrandLayout.SAMSUNG -> samsungHeader(h)
        BrandLayout.LG -> lgHeader(h)
        BrandLayout.TCL -> tclHeader(h)
        BrandLayout.XIAOMI -> xiaomiHeader(h)
    }

    override fun folderCard(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit): View {
        // Keep the user's list preference and Samsung's familiar landscape folder cards intact.
        return when {
            size == TvFolderSize.LIST -> TvFolderCard.build(h, f, size, onOpen, tokens)
            layout == BrandLayout.SAMSUNG -> TvFolderCard.build(h, f, size, onOpen, tokens)
            else -> posterFolderCard(h, f, size, onOpen)
        }
    }

    /** Any legacy button labelled Themes enters the common Skin-first chooser. */
    override fun themes(h: TvSkinHost): Boolean {
        h.open(TvPage.LAYOUT)
        return true
    }

    private fun p(h: TvSkinHost) = TvPalette(h.theme)
    private fun shape(h: TvSkinHost, fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = h.ui.dp(radiusDp).toFloat()
            if (stroke != null) setStroke(h.ui.dp(1), stroke)
        }

    private fun header(h: TvSkinHost, title: String, subtitle: String, badge: String): View {
        val a = h.activity; val ui = h.ui; val pal = p(h)
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(8))
        }
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        titles.addView(ui.text(title, tokens.textTitle + 7f, pal.fg, true))
        titles.addView(ui.text(subtitle, tokens.textCaption + 1f, pal.fgSub).apply {
            maxLines = 2; setPadding(0, ui.dp(3), 0, 0)
        })
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val chip = TextView(a).apply {
            text = badge; textSize = tokens.textCaption; setTextColor(pal.onPrimary)
            gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = shape(h, pal.primary, tokens.radiusPill)
        }
        row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        return row
    }

    private fun navButton(h: TvSkinHost, icon: String, label: String, action: () -> Unit): View {
        val a = h.activity; val ui = h.ui; val pal = p(h)
        val tile = TvTileView(a, tokens.radiusPill).styled(tokens, pal.primary)
        tile.inner.background = shape(h, pal.surface, tokens.radiusPill, pal.border)
        tile.inner.addView(ui.text("$icon  $label", tokens.textBody + 1f, pal.fg, true).apply {
            gravity = Gravity.CENTER; maxLines = 2
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        tile.contentDescription = label
        tile.setOnClickListener { action() }
        return tile
    }

    private fun navStrip(h: TvSkinHost, compact: Boolean = false): View {
        val a = h.activity; val ui = h.ui
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            clipChildren = false; clipToPadding = false
            setPadding(ui.dp(12), ui.dp(3), ui.dp(12), ui.dp(7))
        }
        // Each family gets its own navigation glyph language as well as its own layout and focus style.
        val glyphs = when (layout) {
            BrandLayout.SAMSUNG -> listOf("⌂", "▦", "♧", "⚙")
            BrandLayout.LG -> listOf("⌂", "▤", "★", "⚙")
            BrandLayout.TCL -> listOf("⌂", "▣", "⌕", "⚙")
            BrandLayout.XIAOMI -> listOf("⌂", "▤", "◈", "⚙")
        }
        val items = listOf(
            Triple(glyphs[0], "الرئيسية", { h.goHome() }),
            Triple(glyphs[1], "السكنات والسمات", { h.open(TvPage.LAYOUT) }),
            Triple(glyphs[2], "الإشعارات", { h.open(TvPage.NOTIFICATIONS) }),
            Triple(glyphs[3], "المظهر", { h.open(TvPage.APPEARANCE) })
        )
        for ((icon, label, action) in items) {
            row.addView(navButton(h, icon, if (compact) label.substringBefore(' ') else label, action),
                LinearLayout.LayoutParams(ui.dp(if (compact) 90 else 154), ui.dp(if (compact) 52 else 50)).apply {
                    setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                })
        }
        return HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(row)
        }
    }

    private fun welcomeCard(h: TvSkinHost, title: String, message: String): View {
        val a = h.activity; val ui = h.ui; val pal = p(h)
        val card = TvTileView(a, tokens.radiusCard + 2).styled(tokens, pal.primary)
        card.inner.background = shape(h, pal.cardFill, tokens.radiusCard + 2, pal.border)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        }
        col.addView(ui.text(title, tokens.textTitle + 2f, pal.fg, true))
        col.addView(ui.text(message, tokens.textBody + 1f, pal.fgSub).apply {
            maxLines = 2; setPadding(0, ui.dp(5), 0, 0)
        })
        col.addView(ui.text("🧩 اختار السكن والسمة", tokens.textCaption + 1f, pal.primary, true).apply {
            setPadding(0, ui.dp(8), 0, 0)
        })
        card.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
        card.contentDescription = title
        card.tag = "skin:first"
        card.setOnClickListener { h.open(TvPage.LAYOUT) }
        card.layoutParams = LinearLayout.LayoutParams(-1, h.ui.dp(112)).apply {
            setMargins(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(8))
        }
        return card
    }

    private fun recentCard(h: TvSkinHost, style: Int): View? {
        val recent = h.lastRecent() ?: return null
        val a = h.activity; val ui = h.ui; val pal = p(h)
        val card = TvTileView(a, tokens.radiusCard + if (style == 1) 4 else 1).styled(tokens, pal.primary).apply {
            tag = "skin:first"; contentDescription = "كمّل المشاهدة: " + recent.title
            setOnClickListener { h.playRecent(recent) }
        }
        card.inner.background = shape(h, pal.cardFill, tokens.radiusCard + if (style == 1) 4 else 1, pal.border)
        if (style == 1) {
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val image = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(pal.border) }
            h.recentThumbInto(recent, image)
            col.addView(image, LinearLayout.LayoutParams(-1, ui.dp(118)))
            col.addView(ui.text("▶ كمّل المشاهدة", tokens.textCaption + 1f, pal.primary, true).apply {
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), 0)
            })
            col.addView(ui.text(recent.title, tokens.textBody + 1f, pal.fg, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(10))
            })
            card.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
            card.layoutParams = LinearLayout.LayoutParams(ui.dp(230), ui.dp(218)).apply {
                setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
            }
            return card
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL; clipChildren = true
        }
        val image = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(pal.border) }
        h.recentThumbInto(recent, image)
        row.addView(image, LinearLayout.LayoutParams(ui.dp(if (style == 2) 140 else 190), -1))
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
        }
        col.addView(ui.text(if (style == 2) "▶ تابع من حيث توقفت" else "▶ كمّل المشاهدة", tokens.textCaption + 1f, pal.primary, true))
        col.addView(ui.text(recent.title, tokens.textTitle + if (style == 2) 1f else 3f, pal.fg, true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, ui.dp(5), 0, ui.dp(4))
        })
        val meta = if (recent.posSec > 0.0) "${PlayerLogic.clock((recent.posSec * 1000).toLong())} · ${recent.percent}%" else "ابدأ من الأول"
        col.addView(ui.text(meta, tokens.textBody, pal.fgSub))
        val progress = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(pal.border)
        }
        if (recent.percent > 0) progress.addView(View(a).apply { setBackgroundColor(pal.primary) },
            LinearLayout.LayoutParams(0, -1, recent.percent.coerceIn(0, 100).toFloat()))
        if (recent.percent < 100) progress.addView(View(a),
            LinearLayout.LayoutParams(0, -1, (100 - recent.percent).coerceAtLeast(1).toFloat()))
        col.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(10) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        card.inner.addView(row, FrameLayout.LayoutParams(-1, -1))
        card.layoutParams = LinearLayout.LayoutParams(-1, ui.dp(if (style == 2) 138 else 154)).apply {
            setMargins(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(8))
        }
        return card
    }

    private fun samsungHeader(h: TvSkinHost): View {
        val ui = h.ui; val root = LinearLayout(h.activity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(header(h, "Joyboy", "Smart Hub-inspired · مكتبتك واختصاراتك", "SMART HUB"), LinearLayout.LayoutParams(-1, -2))
        root.addView(navStrip(h), LinearLayout.LayoutParams(-1, -2))
        root.addView(recentCard(h, 0) ?: welcomeCard(h, "أهلًا بيك في Joyboy", "شغّل فيديو محلي أو افتح مكتبتك من الصفوف التالية."), LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun lgHeader(h: TvSkinHost): View {
        val ui = h.ui; val pal = p(h)
        val root = LinearLayout(h.activity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(header(h, "Joyboy", "webOS-inspired · المحتوى في المقدمة", "HOME BAR"), LinearLayout.LayoutParams(-1, -2))
        val heroRow = LinearLayout(h.activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false
        }
        val poster = recentCard(h, 1)
        if (poster != null) {
            heroRow.addView(poster, LinearLayout.LayoutParams(ui.dp(244), ui.dp(228)).apply {
                setMargins(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(6))
            })
            heroRow.addView(welcomeCard(h, "كل مكتبتك في مكان واحد", "تنقّل بالريموت بين الفيديوهات والفولدرات، من غير ما نخفي أي صف من اللانشر."),
                LinearLayout.LayoutParams(0, ui.dp(228), 1f))
        } else {
            heroRow.addView(welcomeCard(h, "ابدأ المشاهدة", "آخر فيديو شغّلته هيظهر هنا تلقائيًا."), LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(heroRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(navStrip(h, compact = true), LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun tclHeader(h: TvSkinHost): View {
        val root = LinearLayout(h.activity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(header(h, "Joyboy", "TCL / Google TV-inspired · اختصارات ومحتوى سريع", "TV HOME"), LinearLayout.LayoutParams(-1, -2))
        // TCL uses a compact utility strip and a progress-led content card; Samsung keeps a
        // wider navigation strip and a horizontal resume card, so they are not color-only variants.
        root.addView(navStrip(h, compact = true), LinearLayout.LayoutParams(-1, -2))
        root.addView(recentCard(h, 2) ?: welcomeCard(h, "مكتبتك جاهزة", "الفيديوهات والتطبيقات الأصلية هتفضل ظاهرة أسفل الهيدر."), LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun xiaomiHeader(h: TvSkinHost): View {
        val a = h.activity; val ui = h.ui; val pal = p(h)
        val shell = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false; clipToPadding = false
        }
        val rail = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            background = shape(h, pal.surface, tokens.radiusCard, pal.border)
        }
        val items = listOf(
            Triple("⌂", "الرئيسية", { h.goHome() }),
            Triple("▦", "المكتبة", { h.refresh() }),
            Triple("🧩", "السكنات", { h.open(TvPage.LAYOUT) }),
            Triple("⚙", "المظهر", { h.open(TvPage.APPEARANCE) })
        )
        items.forEach { (icon, label, action) ->
            rail.addView(navButton(h, icon, label, action), LinearLayout.LayoutParams(ui.dp(84), ui.dp(56)).apply {
                setMargins(ui.dp(2), ui.dp(3), ui.dp(2), ui.dp(3))
            })
        }
        shell.addView(rail, LinearLayout.LayoutParams(ui.dp(92), -2))
        val main = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        main.addView(header(h, "Joyboy", "PatchWall-inspired · حائط محتواك المحلي", "WALL"), LinearLayout.LayoutParams(-1, -2))
        main.addView(recentCard(h, 2) ?: welcomeCard(h, "حائط الفيديوهات", "استعرض الفولدرات والتطبيقات من الصفوف التالية."), LinearLayout.LayoutParams(-1, -2))
        shell.addView(main, LinearLayout.LayoutParams(0, -2, 1f))
        return shell
    }

    /** Poster-style cards for webOS/PatchWall layouts. LIST remains the established list design. */
    private fun posterFolderCard(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit): View {
        val a = h.activity; val ui = h.ui; val pal = p(h)
        val geometry = when (size) {
            TvFolderSize.SHELF -> Triple(204, 138, 82)
            TvFolderSize.GRID_S -> Triple(166, 148, 82)
            TvFolderSize.GRID_L -> Triple(224, 184, 112)
            TvFolderSize.LIST -> Triple(300, 96, 54)
        }
        val card = TvTileView(a, tokens.radiusCard).styled(tokens, pal.primary)
        card.inner.background = shape(h, pal.cardFill, tokens.radiusCard, pal.border)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val imageBox = FrameLayout(a).apply { setBackgroundColor(pal.border); clipChildren = true }
        val image = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val placeholder = ui.text(f.icon.ifBlank { "📁" }, 30f, pal.fg).apply { gravity = Gravity.CENTER }
        imageBox.addView(image, FrameLayout.LayoutParams(-1, -1))
        imageBox.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        val firstVideo = f.videos.firstOrNull()
        if (firstVideo != null) h.loadThumb(firstVideo) { bmp ->
            if (bmp != null) { image.setImageBitmap(bmp); placeholder.visibility = View.GONE }
        }
        col.addView(imageBox, LinearLayout.LayoutParams(-1, ui.dp(geometry.third)))
        val title = ui.text(f.name, tokens.textBody + if (size == TvFolderSize.GRID_L) 1.5f else 0.5f, pal.fg, true).apply {
            maxLines = 2; minLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(ui.dp(9), ui.dp(7), ui.dp(9), 0)
        }
        col.addView(title)
        col.addView(ui.text("${f.videos.size} فيديو · ${VideoLib.fmtSize(f.totalSize)}", tokens.textCaption, pal.fgSub).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(9), ui.dp(3), ui.dp(9), ui.dp(8))
        })
        card.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
        card.setOnClickListener { onOpen() }
        card.setOnLongClickListener {
            h.menu("📁 " + f.name.take(40), listOf(
                "▶ افتح الفولدر" to onOpen,
                "ℹ️ تفاصيل الفولدر" to {
                    GAlert(a).setTitle("📁 " + f.name.take(40))
                        .setMessage(TvFolderCard.details(f, h.isSdFolder(f), f.videos.size))
                        .setNegativeButton("إغلاق", null).show()
                }
            ))
            true
        }
        card.contentDescription = f.name + "، " + f.videos.size + " فيديو"
        val width = if (size == TvFolderSize.LIST) -1 else ui.dp(geometry.first)
        card.layoutParams = LinearLayout.LayoutParams(width, ui.dp(geometry.second)).apply {
            setMargins(ui.dp(tokens.gap / 2), ui.dp(tokens.gap / 2), ui.dp(tokens.gap / 2), ui.dp(tokens.gap / 2))
        }
        return card
    }
}

object TvSkinSamsung : TvSkin by ManufacturerTvSkin(
    id = "samsung", name = "Samsung Smart Hub", layout = BrandLayout.SAMSUNG,
    tokens = TvTokens(radiusCard = 12, radiusThumb = 8, radiusPill = 18, gap = 10, pad = 14,
        textTitle = 19f, textBody = 14f, textCaption = 12f, focusStyle = TvFocusStyle.RING, focusScale = 1.05f, focusMs = 120L, pageMs = 190L),
    themeIds = listOf("default", "mxd", "win11d", "amoled_w", "light")
)

object TvSkinLg : TvSkin by ManufacturerTvSkin(
    id = "lg", name = "LG webOS", layout = BrandLayout.LG,
    tokens = TvTokens(radiusCard = 22, radiusThumb = 16, radiusPill = 28, gap = 12, pad = 16,
        textTitle = 20f, textBody = 14.5f, textCaption = 12.5f, focusStyle = TvFocusStyle.GLOW, focusScale = 1.06f, focusMs = 155L, pageMs = 230L),
    themeIds = listOf("mx", "light", "sky", "win11", "sakura")
)

object TvSkinTcl : TvSkin by ManufacturerTvSkin(
    id = "tcl", name = "TCL / Google TV", layout = BrandLayout.TCL,
    tokens = TvTokens(radiusCard = 16, radiusThumb = 12, radiusPill = 26, gap = 10, pad = 14,
        textTitle = 19f, textBody = 14f, textCaption = 12f, focusStyle = TvFocusStyle.LIFT, focusScale = 1.07f, focusMs = 145L, pageMs = 220L),
    themeIds = listOf("default", "mxd", "light", "win11d", "amoled_w")
)

object TvSkinXiaomi : TvSkin by ManufacturerTvSkin(
    id = "xiaomi", name = "Xiaomi / PatchWall", layout = BrandLayout.XIAOMI,
    tokens = TvTokens(radiusCard = 10, radiusThumb = 6, radiusPill = 14, gap = 8, pad = 10,
        textTitle = 18f, textBody = 13.5f, textCaption = 11.5f, focusStyle = TvFocusStyle.UNDERLINE, focusScale = 1.03f, focusMs = 105L, pageMs = 180L),
    themeIds = listOf("mx", "sky", "win11", "eye_sepia", "amoled_w")
)
