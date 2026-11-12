package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v828 · Joyboy Care) صفحة «فحص الحماية» بشكل شيومي: حلقة بدرجة المراجعة + قايمة ملاحظات (انتباه / مراجعة / معلومة)،
 * كل ملاحظة فيها التطبيقات المعنية (بأيقوناتها، تفتح تفاصيل التطبيق) وزرار لإعداد النظام المناسب.
 * ⚠ ده «مراجعة مخاطر» بقواعد معلنة مش مضاد فيروسات — مفيش محرك فحص ولا قاعدة تواقيع ومفيش رفع بيانات. الصفحة بتقول كده بصراحة.
 */
class CareSecurityUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val k = CareKit(act, ui, th)
    private val pal = k.pal
    private val h = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var res: CareSecurity.Result? = null
    private var apps: Map<String, AppRec> = emptyMap()
    private var at = 0L
    private var scanning = false
    @Volatile private var closed = false
    private var token = 0
    private val expanded = HashSet<String>()
    private var showFormula = false
    private lateinit var page: CarePage

    private fun scan() {
        if (scanning) return
        scanning = true; val tok = ++token
        page.redraw()
        Thread {
            val l = try { CareAppsRead.load(act) } catch (_: Throwable) { emptyList() }
            val r = try { CareSecurity.review(l, CareSecurityRead.env(act)) } catch (_: Throwable) { null }
            h.post {
                if (closed || tok != token) return@post
                apps = l.associateBy { it.pkg }; res = r; at = System.currentTimeMillis(); scanning = false
                page.redraw()
            }
        }.start()
    }

    private fun settings(action: String) {
        try { act.startActivity(Intent(action)) } catch (_: Throwable) { try { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) { k.toast("مقدرتش أفتح الإعدادات") } }
    }

    private fun fixAction(id: String): Pair<String, String>? = when (id) {
        "lock" -> "🔒 افتح إعدادات الأمان" to Settings.ACTION_SECURITY_SETTINGS
        "patch" -> "ℹ️ معلومات الجهاز والتحديثات" to Settings.ACTION_DEVICE_INFO_SETTINGS
        "adb" -> "⚙️ خيارات المطور" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        else -> null
    }

    private fun sevLabel(s: Int): Pair<String, Int> = when (s) {
        CareSecurity.SEV_ATTENTION -> "يحتاج انتباه" to pal.bad
        CareSecurity.SEV_REVIEW -> "يستاهل مراجعة" to pal.warn
        else -> "معلومة" to pal.muted
    }

    private fun findingCard(f: CareSecurity.Finding): View {
        val c = k.col().apply { background = k.fill(pal.surface, 20); setPadding(k.dp(16), k.dp(14), k.dp(16), k.dp(14)) }
        val (lbl, color) = sevLabel(f.sev)
        val head = k.rowH()
        head.addView(k.t(lbl, 11f, color, true).apply { background = k.fill(pal.bg, 8); setPadding(k.dp(10), k.dp(3), k.dp(10), k.dp(3)) })
        c.addView(head)
        k.add(c, k.t(f.title, 15f, pal.text, true), 8)
        k.add(c, k.t(f.detail, 12f, pal.muted), 4)
        if (f.points != 0) k.add(c, k.t("خصم ${-f.points} من الدرجة", 11f, pal.muted), 4)
        fixAction(f.id)?.let { (label, action) -> k.add(c, k.pill(label, false) { settings(action) }, 10) }
        if (f.pkgs.isNotEmpty()) {
            val open = f.id in expanded
            val shown = if (open) f.pkgs else f.pkgs.take(4)
            for (p in shown) {
                val rec = apps[p] ?: continue
                val row = k.rowH().apply { setPadding(0, k.dp(8), 0, k.dp(8)); isClickable = true; isFocusable = true; contentDescription = rec.label }
                row.addView(k.appIcon(rec.pkg, 36))
                row.addView(k.t(rec.label, 13f, pal.text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = k.dp(12); marginEnd = k.dp(8) })
                row.addView(k.t("‹", 20f, pal.muted))
                row.setOnClickListener { CareAppDetail.open(k, rec) { scan() } }
                k.add(c, row, 2)
            }
            if (f.pkgs.size > 4) k.add(c, k.t(if (open) "عرض أقل" else "عرض الكل (${f.pkgs.size})", 12f, pal.accent, true).apply {
                isClickable = true; setPadding(0, k.dp(8), 0, k.dp(4))
                setOnClickListener { if (open) expanded.remove(f.id) else expanded.add(f.id); page.redraw() }
            }, 4)
        }
        return c
    }

    private fun render() {
        val b = page.body
        val r = res
        val score = r?.score
        val color = if (score == null) pal.muted else if (score >= 85) pal.ok else if (score >= 60) pal.warn else pal.bad

        val ring = CircularMetricRing(act).apply {
            track = pal.line; this.color = color; textColor = pal.text; mutedColor = pal.muted
            strokePx = k.dp(14).toFloat(); bigSp = 50f
            big = if (scanning) "…" else score?.toString() ?: "—"
            small = if (scanning) "بفحص" else if (r == null) "لسه" else "درجة المراجعة"
            contentDescription = if (score == null) "فحص الحماية لسه ماتعملش" else "درجة مراجعة الحماية $score من 100"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            layoutParams = LinearLayout.LayoutParams(k.dp(210), k.dp(210))
            setFrac(0f, false); post { setFrac(if (scanning) 0f else (score ?: 0) / 100f, true) }
        }
        k.add(b, LinearLayout(act).apply { gravity = Gravity.CENTER; addView(ring) }, 8)

        val n = r?.findings?.count { it.sev >= CareSecurity.SEV_REVIEW } ?: 0
        val status = when {
            scanning -> "بقرا إعدادات الأمان والتطبيقات…"
            r == null -> "اضغط «افحص» عشان تراجع حماية الجهاز."
            n == 0 -> "مفيش ملاحظات تستاهل مراجعة وقت الفحص."
            else -> "$n ملاحظة تستاهل مراجعتك."
        }
        k.add(b, k.t(status, 14f, color, true).apply { gravity = Gravity.CENTER }, 10)
        k.add(b, k.t("مراجعة مخاطر بقواعد معلنة — مش مضاد فيروسات (مفيش محرك فحص ولا قاعدة تواقيع)، فمفيش «آمن 100%».", 11f, pal.muted).apply { gravity = Gravity.CENTER }, 6)

        val btn = k.pill(if (scanning) "بفحص…" else if (r == null) "🛡 افحص" else "🔄 افحص تاني", !scanning) { scan() }
        k.add(b, LinearLayout(act).apply { gravity = Gravity.CENTER; addView(btn) }, 14)

        if (r != null) {
            for (f in r.findings) k.add(b, findingCard(f), 12)
            if (r.findings.isEmpty()) k.add(b, k.card(k.t("✓ مفيش أي ملاحظة: قفل الشاشة موجود، تحديث الأمان حديث، ومفيش تطبيق يدوي بأذونات حساسة كتير.", 13f, pal.text)), 12)
            k.add(b, k.t("اتفحص ${clock.format(Date(at))} · ${apps.size} تطبيق", 10f, pal.muted).apply { gravity = Gravity.CENTER }, 14)
        }

        k.add(b, k.t(if (showFormula) "▲ إزاي الدرجة اتحسبت؟" else "▼ إزاي الدرجة اتحسبت؟", 12f, pal.accent, true).apply {
            isClickable = true; setPadding(0, k.dp(10), 0, k.dp(6)); gravity = Gravity.CENTER
            setOnClickListener { showFormula = !showFormula; page.redraw() }
        }, 10)
        if (showFormula) k.add(b, k.card(k.t(CareSecurity.FORMULA, 12f, pal.text)), 4)
    }

    fun show() {
        page = k.page("فحص الحماية")
        page.onClose = { closed = true; token++; h.removeCallbacksAndMessages(null) }
        page.render { render() }
        page.show()
        scan()
    }
}
