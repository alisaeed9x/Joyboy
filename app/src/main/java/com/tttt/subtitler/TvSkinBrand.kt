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

    /** (v628) كل شركة ليها هيكل رئيسية مختلف فعلًا (تابات / شريط تشغيل / سايدبار) بنفس الأقسام والوظائف */
    override fun arrangeHome(h: TvSkinHost, s: TvHomeSections): List<View>? = when (layout) {
        BrandLayout.SAMSUNG -> TvHomeLayouts.iconTabs(h, tokens, s)
        BrandLayout.LG -> TvHomeLayouts.launcherBand(h, tokens, s)
        BrandLayout.TCL -> TvHomeLayouts.topTabs(h, tokens, s)
        BrandLayout.XIAOMI -> TvHomeLayouts.sidebar(h, tokens, s)
    }

    /** (v629) الهيدر القديم اتشال: الرئيسية كلها بتتبني من arrangeHome بهيكل كل شركة */
    override fun homeHeader(h: TvSkinHost): View? = null


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
        card.inner.background = shape(h, pal.cardFill, tokens.radiusCard, pal.outline)
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
