package com.tttt.subtitler.phonehome

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.AlarmClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.tttt.subtitler.PrayerTimes

/**
 * (v772) كتلة الساعة في نص اللانشر: ساعة كبيرة + التاريخ + مواقيت الصلاة (نسخة لايت من ودجت البرنامج) + زرارين:
 * 🖼 غيّر الخلفية (من مصدر خلفيات Joyboy — AppWall) و 🧹 تنضيف الرام.
 * المواقيت من PrayerTimes (نفس مصدر ودجت الصلاة وشاشة التلفزيون: Aladhan + كاش يومي).
 */
class HomeClock(
    private val act: Activity,
    private val compact: Boolean,
    private val onWall: () -> Unit,
    private val onRam: () -> Unit,
    private val onPrayerTap: () -> Unit,
    private val onThemes: () -> Unit = {}   // (v775) أيقونة ستور الثيمات
) {
    val view = LinearLayout(act)
    private var data: PrayerTimes.Data? = null

    private val shadow = 0x99000000.toInt()

    private fun dp(v: Int) = act.dp(v)

    private fun label(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(act).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setShadowLayer(6f, 0f, 1f, shadow)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private val timeTv = TextClock(act).apply {
        setFormat12Hour("h:mm")
        setFormat24Hour("HH:mm")
        textSize = if (compact) 56f else 72f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setShadowLayer(10f, 0f, 2f, shadow)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        includeFontPadding = false
    }

    private val dateTv = TextClock(act).apply {
        setFormat12Hour("EEEE، d MMMM")
        setFormat24Hour("EEEE، d MMMM")
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setShadowLayer(6f, 0f, 1f, shadow)
    }

    private val infoTv = label(12f, 0xCCFFFFFF.toInt())
    private val nextTv = label(15f, Color.WHITE, true)
    private val strip = LinearLayout(act)
    private val cellBox = HashMap<String, LinearLayout>()
    private val cellTime = HashMap<String, TextView>()

    private val wallBtn = pill("🖼 غيّر الخلفية") { onWall() }
    private val ramBtn = pill("🧹 تنضيف الرام") { onRam() }
    private val themeBtn = pill("🎨") { onThemes() }

    private fun pill(text: String, click: () -> Unit) = TextView(act).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(0x55000000)
            setStroke(dp(1), 0x44FFFFFF)
        }
        pressEffect()
        setOnClickListener { click() }
    }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.gravity = Gravity.CENTER_HORIZONTAL

        timeTv.setOnClickListener {
            try { act.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        }
        nextTv.text = "مواقيت الصلاة بتتحمّل…"
        nextTv.setOnClickListener { onPrayerTap() }
        infoTv.visibility = View.GONE

        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER
        for (k in PrayerTimes.ORDER) {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            val n = label(11f, 0xCCFFFFFF.toInt()).apply { text = PrayerTimes.ar(k) }
            val t = label(13f, Color.WHITE, true).apply { text = "--:--" }
            box.addView(n)
            box.addView(t)
            strip.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            cellBox[k] = box
            cellTime[k] = t
        }
        strip.visibility = View.GONE

        val btnRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRow.addView(wallBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
        btnRow.addView(ramBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
        btnRow.addView(themeBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })

        view.addView(timeTv, LinearLayout.LayoutParams(-2, -2))
        view.addView(dateTv, LinearLayout.LayoutParams(-2, -2))
        view.addView(infoTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        view.addView(nextTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (compact) 6 else 10) })
        if (!compact) view.addView(strip, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), dp(6), dp(20), 0) })
        view.addView(btnRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (compact) 8 else 12) })
    }

    fun setRamLabel(s: String) { ramBtn.text = s }

    /** (v774) ثيم بالذكاء: ألوان + حجم وخط الساعة */
    fun applyTheme(t: HomeTheme) {
        val soft = ColorUtils.setAlphaComponent(t.text, 0xCC)
        timeTv.setTextColor(t.text); dateTv.setTextColor(t.text); nextTv.setTextColor(t.text); infoTv.setTextColor(soft)
        for ((_, tv) in cellTime) tv.setTextColor(t.text)
        for (i in 0 until strip.childCount) ((strip.getChildAt(i) as? LinearLayout)?.getChildAt(0) as? TextView)?.setTextColor(soft)
        for (bt in listOf(wallBtn, ramBtn, themeBtn)) bt.background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat(); setColor(0x55000000); setStroke(dp(1), ColorUtils.setAlphaComponent(t.accent, 0x99))
        }
        if (t.clockSp > 0) timeTv.textSize = (if (compact) minOf(t.clockSp, 64) else t.clockSp).toFloat()
        if (t.clockFont.isNotBlank()) timeTv.typeface = Typeface.create(t.clockFont, Typeface.NORMAL)
    }

    /** مواقيت جديدة (من الـAPI أو الكاش) */
    fun setData(d: PrayerTimes.Data) {
        data = d
        val line = listOf(d.hijri, d.city).filter { it.isNotBlank() }.joinToString("  •  ")
        infoTv.text = line
        infoTv.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        for (k in PrayerTimes.ORDER) cellTime[k]?.text = d.times[k]?.let { PrayerTimes.fmt12(it) } ?: "--:--"
        refresh()
    }

    /** بيتنادى كل شوية: العدّاد + تمييز الصلاة الحالية والجاية */
    fun refresh() {
        val d = data ?: return
        val now = System.currentTimeMillis()
        val next = PrayerTimes.nextPrayer(d.times, now)
        val cur = PrayerTimes.currentPrayer(d.times, now)
        val left = ((next.second - now) / 60000L).coerceAtLeast(0L)
        val h = left / 60L
        val m = left % 60L
        nextTv.text = PrayerTimes.ar(next.first) + " " + (d.times[next.first]?.let { PrayerTimes.fmt12(it) } ?: "") +
            "  •  بعد " + (if (h > 0L) "$h س " else "") + "$m د"
        if (!compact) strip.visibility = View.VISIBLE
        for (k in PrayerTimes.ORDER) {
            val box = cellBox[k] ?: continue
            box.background = when (k) {
                next.first -> GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x66FFFFFF) }
                cur -> GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x33FFFFFF) }
                else -> null
            }
        }
    }
}
