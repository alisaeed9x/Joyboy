package com.tttt.subtitler

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * (v516) قشرة اللانشر لصفحات تانية (يوتيوب): نفس شريط التابات (أو الريل الجانبي على الشاشات الضيقة) ونفس خلفية اللانشر
 * فوق صفحة يوتيوب، عشان الانتقال بين «تطبيقات» و«يوتيوب» يحس إنه صفحة واحدة مش تطبيق جوه تطبيق.
 */
object TvWall {
    /** تعتيم الخلفية من الأپيرنس (٠..١٠٠٪) — الافتراضي ٥٠٪ زي زمان */
    fun scrimColor(th: Theme? = null): Int {
        val p = (Cfg.str("tv_scrim", "50").toIntOrNull() ?: 50).coerceIn(0, 100)
        // (v606) السمة الفاتحة نصها غامق: التعتيم الأسود كان بيخلّي الخلفية غامقة والنص الغامق مايبانش. دلوقتي السمة الفاتحة بتتغطّى بطبقة بيضا (حد أدنى 62%) فالنص والأيقونات يبانوا
        if (th != null && Theme.lum(th.bg) >= 0.5) {
            val a = if (p == 0) 0 else maxOf(p, 62)
            return Color.argb((a * 255 / 100).coerceIn(0, 255), 255, 255, 255)
        }
        return Color.argb((p * 255 / 100).coerceIn(0, 255), 0, 0, 0)
    }

    /** (v527) بيحمّل آخر خلفية نزّلها اللانشر (wall_cur.jpg) — مفيش خلفيات مدمجة. null = بدون خلفية (استدعيها من ثريد) */
    fun load(act: Activity): Bitmap? {
        val m = Cfg.str("tv_wall", "auto")
        if (m == "off") return null
        val f = File(act.filesDir, "wall_cur.jpg")
        if (!f.exists()) return null
        return try {
            val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, b)
            var ss = 1; while (b.outWidth / ss > 2200) ss *= 2
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
        } catch (_: Throwable) { null }
    }
}

object TvShell {
    val LABELS = listOf("الصفحة الرئيسية", "تطبيقات", "يوتيوب", "المتصفح", "الفيديوهات")   // (v525) «الملفات» اتشالت من الشريط
    val ICONS = listOf("🏠", "📱", "▶️", "🌐", "🎬")

    /** خلفية اللانشر: تدرّج + الصورة المختارة + تعتيم (بتتحمّل في ثريد وتظهر بتلاشي) */
    fun backdrop(act: Activity, th: Theme): View {
        val f = FrameLayout(act)
        if (Theme.lum(th.bg) < 0.2) f.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0A0F22.toInt(), 0xFF150E27.toInt()))
        else f.background = ColorDrawable(th.bg)
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; isFocusable = false; isClickable = false }
        val scrim = View(act).apply { setBackgroundColor(TvWall.scrimColor(th)); visibility = View.GONE; isFocusable = false; isClickable = false }
        f.addView(iv, FrameLayout.LayoutParams(-1, -1))
        f.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        Thread {
            val bmp = try { TvWall.load(act) } catch (_: Throwable) { null }
            if (bmp != null) act.runOnUiThread {
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                iv.setImageBitmap(bmp); scrim.visibility = View.VISIBLE
                iv.animate().alpha(1f).setDuration(250L).start()
            }
        }.apply { isDaemon = true }.start()
        return f
    }
}

/** شريط تابات اللانشر: أفقي (فوق) أو رأسي (ريل جانبي بأيقونات). التركيز على تاب يفتحه بعد 380ms من غير OK. */
class TvShellBar(private val act: Activity, private val ui: Ui, private val th: Theme, private val vertical: Boolean,
                 private val go: (Int) -> Unit, private val onSettings: () -> Unit) {
    val view: View
    private val tabs = ArrayList<TvOwnLinear>()
    private var cur = -1
    private var pend = -1
    private val h = Handler(Looper.getMainLooper())
    private val fg: Int = if (Theme.lum(th.bg) < 0.2) Color.WHITE else th.text

    private fun pillBg(color: Int, rDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = ui.dp(rDp).toFloat(); if (strokeDp > 0) setStroke(ui.dp(strokeDp), strokeColor) }

    private val dwell = Runnable {
        val i = pend; pend = -1
        if (i < 0 || i == cur || act.isDestroyed || act.isFinishing) return@Runnable
        if (tabs.getOrNull(i)?.isFocused != true) return@Runnable
        try { go(i) } catch (e: Throwable) { LogStore.err("TvShellBar:dwell", e) }
    }

    private fun style(b: TvOwnLinear, i: Int, f: Boolean) {
        val sel = i == cur
        b.background = pillBg(if (f) Color.WHITE else if (sel) 0x66FFFFFF else Color.TRANSPARENT, 24)
        (b.getChildAt(0) as? TextView)?.setTextColor(if (f) Color.BLACK else if (sel) fg else 0xCCFFFFFF.toInt())
    }

    fun select(i: Int) { cur = i; tabs.forEachIndexed { k, v -> style(v, k, v.isFocused) } }

    init {
        TvShell.LABELS.forEachIndexed { i, label ->
            val b = TvOwnLinear(act).apply {
                gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                if (vertical) setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) else setPadding(ui.dp(20), ui.dp(9), ui.dp(20), ui.dp(9))
                contentDescription = label
                setOnClickListener { try { go(i) } catch (e: Throwable) { LogStore.err("TvShellBar:click", e) } }
                setOnFocusChangeListener { v, f ->
                    style(v as TvOwnLinear, i, f)
                    h.removeCallbacks(dwell); pend = -1
                    if (f) {
                        if (vertical) Notice.show(act, label, 1100L)
                        if (i != cur) { pend = i; h.postDelayed(dwell, 380L) }
                    }
                }
            }
            b.addView(if (vertical) ui.text(TvShell.ICONS[i], 20f, fg).apply { gravity = Gravity.CENTER } else ui.text(label, 15f, fg, true).apply { gravity = Gravity.CENTER })
            b.layoutParams = if (vertical) LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(2), 0, ui.dp(2)) }
                             else LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }
            tabs.add(b)
        }
        fun roundBtn(icon: String, label: String, click: () -> Unit): View {
            val tv = ui.text(icon, 19f, fg).apply { gravity = Gravity.CENTER }
            return TvOwnLinear(act).apply {
                gravity = Gravity.CENTER; isFocusable = true; isClickable = true; contentDescription = label
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
                setOnClickListener { click() }
                setOnFocusChangeListener { v, f ->
                    v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (f) Color.WHITE else Color.TRANSPARENT) }
                    tv.setTextColor(if (f) Color.BLACK else fg)
                    v.animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).setDuration(Skins.dur(150L)).setInterpolator(DecelerateInterpolator()).start()
                    if (f) Notice.show(act, label, 1100L)
                }
                addView(tv)
                layoutParams = LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }
            }
        }
        if (vertical) {
            val rail = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)); background = pillBg(0x99000000.toInt(), 24, 0x33FFFFFF, 1)
            }
            tabs.forEach { rail.addView(it) }
            rail.addView(roundBtn("⚙️", "إعدادات البرنامج") { onSettings() }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)).apply { setMargins(0, ui.dp(6), 0, 0) } })
            view = FrameLayout(act).apply {
                setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
                addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(rail) }, FrameLayout.LayoutParams(-1, -1))
            }
        } else {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)); background = pillBg(0x99000000.toInt(), 30, 0x33FFFFFF, 1)
            }
            tabs.forEach { box.addView(it) }
            val bar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(32), ui.dp(18), ui.dp(32), ui.dp(8)); clipChildren = false
            }
            bar.addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(14) })
            bar.addView(roundBtn("⚙️", "إعدادات البرنامج") { onSettings() })
            view = bar
        }
    }
}
