package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v828 · Joyboy Care) صفحة «تسريع» = الرام. حلقة كبيرة بالذاكرة المتاحة + مستخدم/متاح/إجمالي، من `ActivityManager.MemoryInfo` بس.
 * مفيش زرار «تنضيف رام» ولا قتل تطبيقات: أندرويد بيدير الرام بنفسه، وقتل العمليات بيبطّأ الجهاز ويستهلك بطارية — والصفحة بتقول كده.
 * بدل الزرار: اختصارات حقيقية لإعدادات النظام اللي بتساعدك تلاقي التطبيقات التقيلة بنفسك.
 */
class CareSpeedUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val k = CareKit(act, ui, th)
    private val pal = k.pal
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var snap: CareSnap = CareMetrics.read(act)
    private lateinit var page: CarePage

    private fun sz(b: Long): String = try { Formatter.formatShortFileSize(act, b) } catch (_: Throwable) { "${b / 1048576} MB" }
    private fun open(action: String, fallback: String = Settings.ACTION_SETTINGS) {
        try { act.startActivity(Intent(action)) } catch (_: Throwable) { try { act.startActivity(Intent(fallback)) } catch (_: Throwable) { k.toast("مقدرتش أفتح الإعدادات") } }
    }

    private fun stat(label: String, value: String, color: Int): View {
        val c = k.col().apply { gravity = Gravity.CENTER_HORIZONTAL; background = k.fill(pal.surface, 18); setPadding(k.dp(8), k.dp(12), k.dp(8), k.dp(12)) }
        c.addView(k.t(value, 16f, color, true).apply { gravity = Gravity.CENTER })
        k.add(c, k.t(label, 11f, pal.muted).apply { gravity = Gravity.CENTER }, 3)
        c.contentDescription = "$label $value"
        return c
    }

    private fun render() {
        val b = page.body
        val s = snap
        val frac = s.ramUsed.value
        val color = if (frac == null) pal.muted else if (s.ramLow == true) pal.warn else pal.ok
        val ring = CircularMetricRing(act).apply {
            track = pal.line; this.color = color; textColor = pal.text; mutedColor = pal.muted
            strokePx = k.dp(16).toFloat(); bigSp = 34f
            big = if (s.ramTotal > 0) sz(s.ramAvail) else "—"; small = "ذاكرة متاحة"
            contentDescription = if (s.ramTotal > 0) "الذاكرة المتاحة ${sz(s.ramAvail)} من ${sz(s.ramTotal)}، المستخدمة ${(frac ?: 0f).times(100).toInt()} بالمية" else "الذاكرة غير متاحة"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            layoutParams = LinearLayout.LayoutParams(k.dp(240), k.dp(240))
            setFrac(0f, false); post { setFrac(frac, true) }
        }
        k.add(b, LinearLayout(act).apply { gravity = Gravity.CENTER; addView(ring) }, 12)

        val state = if (s.ramTotal <= 0) "مش متاح" else if (s.ramLow == true) "النظام مبلّغ إن الذاكرة قليلة" else "عادية"
        k.add(b, k.t("الحالة: $state", 14f, color, true).apply { gravity = Gravity.CENTER }, 10)

        if (s.ramTotal > 0) {
            val row = k.rowH().apply { weightSum = 3f }
            row.addView(stat("الإجمالي", sz(s.ramTotal), pal.text), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = k.dp(8) })
            row.addView(stat("متاحة", sz(s.ramAvail), pal.ok), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = k.dp(8) })
            row.addView(stat("مستخدمة", sz(s.ramTotal - s.ramAvail), pal.text), LinearLayout.LayoutParams(0, -2, 1f))
            k.add(b, row, 18)
        }

        k.add(b, k.t("لو الجهاز بطيء، جرّب:", 13f, pal.muted, true), 18)
        k.add(b, k.listRow("🔋", CareColors.CARAMEL, "التطبيقات الأكتر استهلاكًا", "اللي شغّال كتير في الخلفية بيظهر هناك") {
            open(Intent.ACTION_POWER_USAGE_SUMMARY)
        }, 8)
        k.add(b, k.listRow("📱", CareColors.OLIVE, "إدارة التطبيقات", "عطّل أو احذف اللي مش بتستخدمه") {
            try { CareAppsUi(act, ui, th).show() } catch (e: Throwable) { LogStore.err("Care:apps", e) }
        }, 8)
        k.add(b, k.listRow("⚙️", CareColors.SKY, "الخدمات قيد التشغيل", "من خيارات المطور لو مفعّلة") {
            open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        }, 8)

    }

    fun show() {
        page = k.page("تسريع")
        page.action("🔄", "تحديث القراءة") { snap = CareMetrics.read(act); page.redraw() }
        page.render { render() }
        page.show()
    }
}
