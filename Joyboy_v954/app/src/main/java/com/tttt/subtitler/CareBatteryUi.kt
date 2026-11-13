package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.util.Locale
import kotlin.math.abs

/**
 * (v830 · Joyboy Care) صفحة البطارية — اتعاد تصميمها: حلقة صغيرة بالنسبة (بدل البطارية العريضة اللي كانت واخدة الشاشة)
 * + بلاطات بكل البيانات اللي الجهاز بيعرضها فعلًا (حالة · حرارة · فولت · تيار · قدرة الشحن · سعة · دورات · نوع · الوقت لحد الاكتمال).
 * أي قيمة الجهاز مش بيعرضها بتتخفي (مفيش «مش متاح» بالجملة). كله من BatteryManager/ACTION_BATTERY_CHANGED، ومفيش تغيير إعدادات بصمت.
 */
class CareBatteryUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val k = CareKit(act, ui, th)
    private val pal = k.pal
    private var snap: CareSnap = CareMetrics.read(act)
    private lateinit var page: CarePage

    private class Info(val label: String, val value: String, val color: Int)

    private var infos: List<Info> = emptyList()
    private var statusText = ""
    private var subText = ""

    private fun long(bm: BatteryManager, id: Int): Long? = try { val v = bm.getLongProperty(id); if (v == Long.MIN_VALUE || v == 0L) null else v } catch (_: Throwable) { null }

    private fun readAll() {
        val out = ArrayList<Info>()
        var plug = ""; var health = ""; var tech = ""; var cycles = -1; var statusInt = -1
        try {
            val i: Intent? = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "جيدة"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "سخونة زيادة"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "تالفة"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "فولت زيادة"
                    BatteryManager.BATTERY_HEALTH_COLD -> "باردة"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "عطل غير محدد"
                    else -> ""
                }
                plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "شاحن حائط"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "لاسلكي"
                    else -> ""
                }
                tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""
                cycles = i.getIntExtra("android.os.extra.CYCLE_COUNT", -1)   // (API 34) لو الجهاز بيعرضها
                statusInt = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            }
        } catch (_: Throwable) {}

        val s = snap
        val pct = s.bat.value
        val bm = try { act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager } catch (_: Throwable) { null }

        statusText = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "بتشحن" + (if (plug.isNotEmpty()) " · $plug" else "")
            BatteryManager.BATTERY_STATUS_FULL -> "مشحونة بالكامل"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "على البطارية"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> if (plug.isNotEmpty()) "موصّلة ومش بتشحن" else "على البطارية"
            else -> if (s.charging) "بتشحن" else "على البطارية"
        }
        subText = ""
        if (bm != null && Build.VERSION.SDK_INT >= 28 && s.charging && statusInt != BatteryManager.BATTERY_STATUS_FULL) {
            try {
                val ms = bm.computeChargeTimeRemaining()
                if (ms > 0L) { val m = (ms / 60000L).toInt(); subText = "باقي على الاكتمال " + (if (m >= 60) "${m / 60} س " else "") + "${m % 60} د" }
            } catch (_: Throwable) {}
        }

        // الحرارة / الفولت
        s.temp.value?.let { t -> out.add(Info("الحرارة", String.format(Locale.US, "%.1f °م", t), if (t >= 45f) pal.bad else if (t >= 38f) pal.warn else pal.text)) }
        s.volt.value?.let { v -> out.add(Info("الفولت", String.format(Locale.US, "%.2f V", v / 1000f), pal.text)) }

        // التيار والقدرة (CURRENT_NOW: ميكرو أمبير غالبًا؛ لو القيمة صغيرة جدًا بنعتبرها ملّي أمبير)
        if (bm != null) {
            val cur = long(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (cur != null) {
                val mA = if (abs(cur) >= 10_000L) abs(cur) / 1000.0 else abs(cur).toDouble()
                out.add(Info(if (s.charging) "تيار الشحن" else "تيار الاستهلاك", String.format(Locale.US, "%.0f mA", mA), pal.text))
                val v = s.volt.value
                if (v != null && mA > 0) out.add(Info(if (s.charging) "قدرة الشحن" else "قدرة الاستهلاك", String.format(Locale.US, "%.1f W", mA * v / 1_000_000.0), pal.text))
            }
            // السعة: عدّاد الشحن (µAh) + تقدير السعة الكاملة من النسبة
            val cc = long(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (cc != null && cc > 0L) {
                val mAh = cc / 1000.0
                out.add(Info("الشحنة الحالية", String.format(Locale.US, "%.0f mAh", mAh), pal.text))
                if (pct != null && pct in 20..99) out.add(Info("السعة الكاملة (تقدير)", String.format(Locale.US, "%.0f mAh", mAh * 100.0 / pct), pal.text))
            }
            if (Build.VERSION.SDK_INT >= 34) {
                try { val soh = bm.getIntProperty(10); if (soh in 1..100) out.add(Info("صحة البطارية %", "$soh%", if (soh >= 80) pal.ok else if (soh >= 60) pal.warn else pal.bad)) } catch (_: Throwable) {}
            }
        }
        if (cycles > 0) out.add(Info("دورات الشحن", "$cycles", pal.text))
        if (health.isNotEmpty()) out.add(Info("حالة البطارية", health, if (health == "جيدة") pal.ok else pal.warn))
        if (tech.isNotBlank()) out.add(Info("النوع", tech, pal.text))
        try {
            val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
            out.add(Info("موفر الطاقة", if (pm.isPowerSaveMode) "شغّال" else "مقفول", if (pm.isPowerSaveMode) pal.warn else pal.text))
        } catch (_: Throwable) {}
        infos = out
    }

    private fun open(action: String) {
        try { act.startActivity(Intent(action)) } catch (_: Throwable) { try { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) { k.toast("مقدرتش أفتح الإعدادات") } }
    }

    private fun tile(i: Info): View {
        val c = k.col().apply { background = k.fill(pal.surface, 18); setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12)) }
        c.addView(k.t(i.label, 12f, pal.muted))
        k.add(c, k.t(i.value, 17f, i.color, true), 3)
        c.contentDescription = i.label + " " + i.value
        return c
    }

    private fun render() {
        val b = page.body
        val s = snap
        val pct = s.bat.value
        val color = if (pct == null) pal.muted else if (s.charging || pct > 30) pal.ok else if (pct > 15) pal.warn else pal.bad
        val ring = CircularMetricRing(act).apply {
            track = pal.line; this.color = color; textColor = pal.text; mutedColor = pal.muted
            strokePx = k.dp(12).toFloat(); bigSp = 34f
            big = pct?.let { "$it%" } ?: "—"; small = statusText
            contentDescription = "البطارية " + (pct?.toString() ?: "غير متاحة") + " بالمية، " + statusText
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            layoutParams = LinearLayout.LayoutParams(k.dp(168), k.dp(168))
            setFrac(0f, false); post { setFrac((pct ?: 0) / 100f, true) }
        }
        k.add(b, LinearLayout(act).apply { gravity = Gravity.CENTER; addView(ring) }, 6)
        if (subText.isNotEmpty()) k.add(b, k.t(subText, 13f, pal.text, true).apply { gravity = Gravity.CENTER }, 8)

        // بلاطات بعمودين
        var idx = 0
        while (idx < infos.size) {
            val r = k.rowH().apply { weightSum = 2f; gravity = Gravity.TOP }
            r.addView(tile(infos[idx]), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = k.dp(5) })
            if (idx + 1 < infos.size) r.addView(tile(infos[idx + 1]), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = k.dp(5) })
            else r.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = k.dp(5) })
            k.add(b, r, if (idx == 0) 16 else 10)
            idx += 2
        }

        k.add(b, k.listRow("🍃", CareColors.OLIVE, "استهلاك البطارية بالتطبيقات", "مين أكتر تطبيق بيستهلك") { open(Intent.ACTION_POWER_USAGE_SUMMARY) }, 18)
        k.add(b, k.listRow("⚡", CareColors.SAGE, "موفر الطاقة", "من إعدادات النظام") { open(Settings.ACTION_BATTERY_SAVER_SETTINGS) }, 8)
        k.add(b, k.listRow("🔋", CareColors.CARAMEL, "تحسين البطارية للتطبيقات", "التطبيقات المقيّدة في الخلفية") { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }, 8)
    }

    fun show() {
        readAll()
        page = k.page("البطارية")
        page.action("🔄", "تحديث القراءة") { snap = CareMetrics.read(act); readAll(); page.redraw() }
        page.onResume = { snap = CareMetrics.read(act); readAll(); page.redraw() }
        page.render { render() }
        page.show()
    }
}
