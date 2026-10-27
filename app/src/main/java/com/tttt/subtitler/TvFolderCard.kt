package com.tttt.subtitler

import android.graphics.Outline
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** أحجام كارت الفولدر: قايمة · شبكة صغيرة · شبكة كبيرة · شريط الرئيسية (عرض ثابت) */
enum class TvFolderSize {
    LIST, GRID_S, GRID_L, SHELF;
    companion object {
        /** من إعداد «tv_fold_view» الموجود: list / dense / grid */
        fun fromView(v: String): TvFolderSize = when (v) { "list" -> LIST; "dense" -> GRID_S; else -> GRID_L }
    }
}

/** عدد الملفات الفعلي جوه الفولدر (كل الأنواع مش الفيديو بس) — بيتحسب في الخلفية ويتخزّن */
object TvFolderStats {
    private val cache = ConcurrentHashMap<String, Int>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }

    /** القيمة لو اتحسبت قبل كده (-1 = مش معروف) */
    fun cached(f: FolderItem): Int? = cache[f.key]

    /** done بيتنده على thread خلفي — اللي بينده هو اللي يرجّع لـ UI thread */
    fun fileCount(f: FolderItem, done: (Int) -> Unit) {
        cache[f.key]?.let { done(it); return }
        // فولدر افتراضي (مواسم) أو مفتاح مش مسار حقيقي: مفيش مجلد نعدّه
        if (f.virtual || f.kids.isNotEmpty() || !f.key.startsWith("/")) { cache[f.key] = -1; done(-1); return }
        pool.execute {
            val n = try { File(f.key).listFiles()?.count { it.isFile } ?: -1 } catch (_: Throwable) { -1 }
            cache[f.key] = n
            done(n)
        }
    }
}

/**
 * (v597 م2) كارت الفولدر الجديد على التلفزيون — بيحل مشكلة الاسم العربي اللازق في الصورة:
 *  • الصورة بعرض ثابت على جنب، والنص في عمود لوحده جنبها بمسافة ثابتة من الناحيتين (الغلطة القديمة كانت حشو على الناحية الغلط في RTL).
 *  • الاسم: سطرين بالكتير ثم «…»، واتجاه النص تلقائي حسب أول حرف (dir=auto) ومحاذاته دايمًا ناحية الصورة.
 *  • تحته: عدد الفيديوهات · عدد الملفات، وبعدهم الحجم والمخزن (فلاشة/داخلية).
 */
object TvFolderCard {
    private class Geo(val cardH: Int, val thumbW: Int, val thumbH: Int, val nameSp: Float, val metaSp: Float)

    private fun geo(size: TvFolderSize): Geo = when (size) {
        // الارتفاع الكلي = حشو التركيز (10dp) + حشو الصف (12dp) + أكبر من (الصورة | سطرين اسم + سطرين تفاصيل)
        TvFolderSize.LIST -> Geo(100, 128, 72, 17f, 12.5f)
        TvFolderSize.GRID_S -> Geo(92, 104, 58, 14.5f, 11.5f)
        TvFolderSize.GRID_L -> Geo(124, 176, 99, 18f, 13f)
        TvFolderSize.SHELF -> Geo(100, 124, 70, 15.5f, 12f)
    }

    private fun availDp(dm: DisplayMetrics): Float = dm.widthPixels / dm.density - 68f

    /** كام كارت في الصف (القايمة = 1) */
    fun perRow(size: TvFolderSize, dm: DisplayMetrics): Int {
        if (size == TvFolderSize.LIST || size == TvFolderSize.SHELF) return 1
        val minW = if (size == TvFolderSize.GRID_L) 400f else 270f
        return (availDp(dm) / (minW + 16f)).toInt().coerceIn(1, 6)
    }

    /** عرض الكارت بالـdp في الشبكة/الشريط (القايمة = ملء العرض) */
    fun widthDp(size: TvFolderSize, dm: DisplayMetrics): Int = when (size) {
        TvFolderSize.LIST -> -1
        TvFolderSize.SHELF -> 330
        else -> ((availDp(dm) / perRow(size, dm)) - 16f).toInt().coerceAtLeast(200)
    }

    fun metaCounts(videos: Int, files: Int): String = "$videos فيديو · $files ملف"
    fun metaSize(f: FolderItem, sd: Boolean): String = VideoLib.fmtSize(f.totalSize) + " · " + (if (sd) "💾 فلاشة" else "📱 داخلية")

    fun details(f: FolderItem, sd: Boolean, files: Int): String =
        "المسار: " + f.path.ifBlank { f.name } + "\n" +
        "الفيديوهات: " + f.videos.size + "\n" +
        "الملفات: " + maxOf(files, f.videos.size) + "\n" +
        "الحجم (الفيديوهات): " + VideoLib.fmtSize(f.totalSize) + "\n" +
        "المخزن: " + (if (sd) "فلاشة" else "الذاكرة الداخلية")

    fun build(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit, tk: TvTokens = TvTokens.CLASSIC): View {
        val a = h.activity; val ui = h.ui
        val pal = TvPalette(h.theme)
        val g = geo(size)
        val sd = h.isSdFolder(f)
        val t = TvTileView(a, tk.radiusCard).styled(tk, pal.primary)
        t.inner.setBackgroundColor(pal.cardFill)

        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }

        // الصورة: عرض ثابت (مش بتتضغط ولا بتتمدّد) + حواف مدوّرة + أيقونة فولدر لحد ما الصورة تيجي
        val box = FrameLayout(a).apply {
            setBackgroundColor(pal.border)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, ui.dp(tk.radiusThumb).toFloat()) }
            }
        }
        val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val ph = ui.text("📁", g.thumbH * 0.32f, pal.fgSub).apply { gravity = Gravity.CENTER }
        box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        box.addView(ph, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        row.addView(box, LinearLayout.LayoutParams(ui.dp(g.thumbW), ui.dp(g.thumbH)))
        f.videos.firstOrNull()?.let { v -> h.loadThumb(v) { bmp -> iv.setImageBitmap(bmp); ph.visibility = View.GONE } }

        // عمود النص: مسافة ثابتة من الناحيتين (ناحية الصورة وناحية الحافة) — ده اللي كان ناقص
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
        }
        fun TextView.fit(maxL: Int) {
            maxLines = maxL; ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG      // dir=auto: الاتجاه من أول حرف في الاسم
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START        // دايمًا ناحية الصورة، عربي أو إنجليزي
        }
        val name = ui.text(f.name, g.nameSp, pal.fg, true).apply { fit(2) }
        val vids = f.videos.size
        val metaA = ui.text(metaCounts(vids, vids), g.metaSp, pal.fgSub).apply { fit(1); setPadding(0, ui.dp(3), 0, 0) }
        val metaB = ui.text(metaSize(f, sd), g.metaSp, pal.fgSub).apply { fit(1); setPadding(0, ui.dp(1), 0, 0) }
        col.addView(name); col.addView(metaA); col.addView(metaB)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        t.inner.addView(row, FrameLayout.LayoutParams(-1, -1))

        // عدد الملفات الحقيقي (كل الأنواع) — بيظهر لما يتحسب، وبعدها بنحدّث السطر
        var files = vids
        TvFolderStats.fileCount(f) { n ->
            a.runOnUiThread {
                if (a.isDestroyed || a.isFinishing) return@runOnUiThread
                if (n > vids) { files = n; metaA.text = metaCounts(vids, n) }
            }
        }

        t.setOnClickListener { onOpen() }
        t.setOnLongClickListener {
            h.menu("📁 " + f.name.take(40), listOf(
                "▶ افتح الفولدر" to onOpen,
                "ℹ️ تفاصيل الفولدر" to {
                    GAlert(a).setTitle("📁 " + f.name.take(40)).setMessage(details(f, sd, files)).setNegativeButton("إغلاق", null).show()
                }
            ))
            true
        }
        t.contentDescription = f.name + "، " + metaCounts(vids, vids)

        val m = ui.dp(tk.gap / 2)
        t.layoutParams = when (size) {
            TvFolderSize.LIST -> LinearLayout.LayoutParams(-1, ui.dp(g.cardH))
            else -> LinearLayout.LayoutParams(ui.dp(widthDp(size, a.resources.displayMetrics)), ui.dp(g.cardH))
        }.apply { setMargins(m, m, m, m) }
        return t
    }
}
