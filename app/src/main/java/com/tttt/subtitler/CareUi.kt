package com.tttt.subtitler

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v828 · Joyboy Care — إعادة تصميم بشكل «أمان شيومي») لوحة العناية: حلقة كبيرة بالدرجة + زرار فحص + بلاطات (المنظّف · فحص الحماية ·
 * البطارية · تسريع · إدارة التطبيقات · الخصوصية). كل بلاطة بتفتح صفحة كاملة بسهم رجوع.
 * «فحص الجهاز» فحص حقيقي بمراحل (قياسات الجهاز · أمان الجهاز والتطبيقات · كاش Joyboy · كاش التطبيقات والمهملة · إعدادات أمان إضافية · التخزين) وبيطلّع قايمة ملاحظات كل واحدة بتودّيك للصفحة
 * اللي تعالجها. مفيش «تم تحسين» وهمي ومفيش قتل تطبيقات ولا تنضيف رام — الدرجة تقدير بصيغة معلنة (انظر «إزاي الدرجة اتحسبت؟»).
 */
class CareUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val k = CareKit(act, ui, th)
    private val pal = k.pal
    private val h = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var snap: CareSnap = CareMetrics.read(act)
    private var scanning = false
    @Volatile private var token = 0
    @Volatile private var closed = false
    private var stageNo = 0
    private var stageText = ""
    private var rows: List<Row>? = null
    private var secScore: Int? = null
    private var doneAt = 0L
    private var showFormula = false
    private var orbit: ScanOrbit? = null
    private var statusTv: TextView? = null
    private lateinit var page: CarePage

    private class Row(val emoji: String, val color: Int, val title: String, val sub: String, val go: () -> Unit)

    private fun sz(b: Long): String = try { Formatter.formatShortFileSize(act, b) } catch (_: Throwable) { "${b / 1048576} MB" }
    private fun scoreColor(s: Int?): Int = when { s == null -> pal.muted; s >= 75 -> pal.ok; s >= 50 -> pal.warn; else -> pal.bad }

    private fun go(name: String, f: () -> Unit) { try { f() } catch (e: Throwable) { LogStore.err("Care:$name", e) } }
    private fun openClean() = go("clean") { CareCleanUi(act, ui, th).show() }
    private fun openSecurity() = go("sec") { CareSecurityUi(act, ui, th).show() }
    private fun openBattery() = go("bat") { CareBatteryUi(act, ui, th).show() }
    private fun openSpeed() = go("speed") { CareSpeedUi(act, ui, th).show() }
    private fun openApps() = go("apps") { CareAppsUi(act, ui, th).show() }
    private fun openPrivacy() = go("priv") { CarePrivacyUi(act, ui, th).show() }
    private fun openDevice() = go("device") { CareDeviceUi(act, ui, th).show() }

    private fun buildRows(s: CareSnap, sec: CareSecurity.Result?, cacheBytes: Long, extra: List<CareScanExtra.Item>): List<Row> {
        val out = ArrayList<Row>()
        for (i in s.score.items) {
            val t = i.text
            when {
                t.contains("التخزين") -> out.add(Row("💾", CareColors.TERRA, t, "راجع الملفات الكبيرة والمكررات في المنظّف") { openClean() })
                t.contains("الذاكرة") -> out.add(Row("⚡", CareColors.CARAMEL, t, "شوف صفحة التسريع") { openSpeed() })
                else -> out.add(Row("🔋", CareColors.OLIVE, t, "شوف صفحة البطارية") { openBattery() })
            }
        }
        if (sec != null) for (f in sec.findings) if (f.sev >= CareSecurity.SEV_REVIEW)
            out.add(Row("🛡", if (f.sev == CareSecurity.SEV_ATTENTION) CareColors.ROSE else CareColors.CARAMEL, f.title, f.detail) { openSecurity() })
        if (cacheBytes >= 50L * 1024 * 1024)
            out.add(Row("🧹", CareColors.TERRA, "كاش Joyboy القديم حوالي ${sz(cacheBytes)}", "قابل للمراجعة والحذف بتأكيدك من المنظّف") { openClean() })
        for (x in extra) out.add(Row(x.emoji, x.color, x.title, x.sub) {
            when (x.target) {
                "clean" -> openClean(); "apps" -> openApps(); "security" -> openSecurity(); "battery" -> openBattery()
                "cache" -> CareScanExtra.openCacheScreen(act); "usage" -> CareScanExtra.openUsageAccess(act); "devopts" -> CareScanExtra.openDevOptions(act)
            }
        })
        return out
    }

    private fun startScan() {
        if (scanning) return
        scanning = true; rows = null; stageNo = 1; stageText = "قراءة الرام والتخزين والبطارية…"
        val tok = ++token
        page.redraw()
        Thread {
            fun stage(n: Int, t: String) = h.post {
                if (tok == token && !closed) { stageNo = n; stageText = t; statusTv?.text = t; orbit?.label = "$n/7"; orbit?.invalidate() }
            }
            val s = CareMetrics.read(act)
            if (tok != token) return@Thread
            stage(2, "بفحص أمان الجهاز والتطبيقات…")
            val apps = try { CareAppsRead.load(act) } catch (_: Throwable) { emptyList() }
            val sec = try { CareSecurity.review(apps, CareSecurityRead.env(act)) } catch (_: Throwable) { null }
            if (tok != token) return@Thread
            stage(3, "بدوّر على الكاش القابل للمراجعة…")
            val cache = try { CareClean.scanCache(listOfNotNull(act.cacheDir, act.externalCacheDir), System.currentTimeMillis()) } catch (_: Throwable) { emptyList() }
            if (tok != token) return@Thread
            val extra = ArrayList<CareScanExtra.Item>()
            stage(4, "بقيس كاش التطبيقات والتطبيقات الكبيرة والمهملة…")
            try { extra.addAll(CareScanExtra.apps(act, apps) { tok != token }) } catch (e: Throwable) { LogStore.err("Care:x4", e) }
            if (tok != token) return@Thread
            stage(5, "بفحص إعدادات الأمان الإضافية…")
            try { extra.addAll(CareScanExtra.settings(act, apps)) } catch (e: Throwable) { LogStore.err("Care:x5", e) }
            if (tok != token) return@Thread
            stage(6, "بفحص التخزين: مؤقت · APK · ملفات كبيرة…")
            try { extra.addAll(CareScanExtra.storage(act) { tok != token }) } catch (e: Throwable) { LogStore.err("Care:x6", e) }
            if (tok != token) return@Thread
            stage(7, "بجهّز التقرير…")
            val out = buildRows(s, sec, cache.sumOf { it.size }, extra)
            h.post {
                if (tok != token || closed) return@post
                snap = s; rows = out; secScore = sec?.score; doneAt = System.currentTimeMillis(); scanning = false
                page.redraw()
            }
        }.start()
    }

    private fun cancelScan() { token++; scanning = false; stageText = ""; page.redraw() }

    private fun grid(a: CareKit.Tile, b: CareKit.Tile) {
        val r = k.rowH().apply { weightSum = 2f; gravity = Gravity.TOP }
        r.addView(a.view, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = k.dp(6) })
        r.addView(b.view, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = k.dp(6) })
        k.add(page.body, r, 12)
    }

    private fun render() {
        val b = page.body
        val s = snap
        val sc = s.score.score
        val color = scoreColor(sc)
        val list = rows

        if (scanning) {
            val orb = ScanOrbit(act).apply {
                track = pal.line; this.color = pal.accent; textColor = pal.text; strokePx = k.dp(14).toFloat(); label = "$stageNo/7"
                contentDescription = "الفحص شغّال، المرحلة $stageNo من 7"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = LinearLayout.LayoutParams(k.dp(230), k.dp(230))
            }
            orbit = orb; orb.start()
            k.add(b, LinearLayout(act).apply { gravity = Gravity.CENTER; addView(orb) }, 8)
        } else {
            val ring = CircularMetricRing(act).apply {
                track = pal.line; this.color = color; textColor = pal.text; mutedColor = pal.muted
                strokePx = k.dp(14).toFloat(); bigSp = 60f
                big = sc?.toString() ?: "—"
                small = if (sc == null) "مفيش قياس" else if (list == null) "درجة تقديرية" else if (list.isEmpty()) "الجهاز كويس" else "${list.size} ملاحظة"
                contentDescription = "درجة صحة الجهاز التقديرية " + (sc?.toString() ?: "غير متاحة") + " من 100"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = LinearLayout.LayoutParams(k.dp(230), k.dp(230))
                setFrac(0f, false); post { setFrac((sc ?: 0) / 100f, true) }
            }
            orbit = null
            k.add(b, LinearLayout(act).apply { gravity = Gravity.CENTER; addView(ring) }, 8)
        }

        val st = k.t(
            if (scanning) stageText else if (list == null) "الدرجة دي تقدير من قياسات الجهاز الحقيقية — اضغط «فحص الجهاز» لتقرير كامل."
            else if (list.isEmpty()) "الفحص خلص ${clock.format(Date(doneAt))} · مفيش ملاحظات" else "الفحص خلص ${clock.format(Date(doneAt))} · راجع الملاحظات تحت",
            13f, if (scanning) pal.text else pal.muted
        ).apply { gravity = Gravity.CENTER }
        statusTv = st
        k.add(b, st, 8)
        val btn = k.pill(if (scanning) "⏹ إيقاف الفحص" else "🔍 فحص الجهاز", !scanning) { if (scanning) cancelScan() else startScan() }
        k.add(b, LinearLayout(act).apply { gravity = Gravity.CENTER; addView(btn) }, 12)

        if (!scanning && list != null) {
            if (list.isEmpty()) k.add(b, k.card(k.t("✓ كل القياسات المتاحة في الحدود الطبيعية، ومفيش ملاحظة أمان تستاهل مراجعة.", 13f, pal.text)), 16)
            else {
                k.add(b, k.t("ملاحظات الفحص", 14f, pal.muted, true), 18)
                for (r in list) k.add(b, k.listRow(r.emoji, r.color, r.title, r.sub) { r.go() }, 8)
            }
        }

        // البلاطات
        val cleaner = k.tile("🧹", CareColors.TERRA, "المنظّف", if (s.stoTotal > 0) "الفاضي ${sz(s.stoFree)}" else "كاش · ملفات كبيرة · مكررات") { openClean() }
        val sec = k.tile("🛡", CareColors.SKY, "فحص الحماية", secScore?.let { "درجة المراجعة $it" } ?: "مراجعة مخاطر · مش مضاد فيروسات") { openSecurity() }
        val pct = s.bat.value
        val battery = k.tile("🔋", CareColors.OLIVE, "البطارية", if (pct == null) "مش متاح" else "$pct%" + (if (s.charging) " · بتشحن" else "")) { openBattery() }
        val speed = k.tile("⚡", CareColors.CARAMEL, "تسريع", if (s.ramTotal > 0) "متاحة ${sz(s.ramAvail)} من ${sz(s.ramTotal)}" else "الذاكرة مش متاحة") { openSpeed() }
        val apps = k.tile("▦", CareColors.PLUM, "إدارة التطبيقات", "أيقونات · حجم · تعطيل · إلغاء تثبيت") { openApps() }
        val priv = k.tile("🔐", CareColors.SAGE, "الخصوصية", "مين ممنوح له كاميرا/مايك/موقع") { openPrivacy() }
        b.addView(View(act), LinearLayout.LayoutParams(1, k.dp(6)))
        grid(cleaner, sec); grid(battery, speed); grid(apps, priv)
        k.add(b, k.listRow("📱", CareColors.SKY, "معلومات الجهاز", "المعالج · كارت الشاشة · الشاشة · الكاميرات · الحساسات · النظام") { openDevice() }, 12)

        // شرح الدرجة
        k.add(b, k.t(if (showFormula) "▲ إزاي الدرجة اتحسبت؟" else "▼ إزاي الدرجة اتحسبت؟", 12f, pal.accent, true).apply {
            isClickable = true; setPadding(0, k.dp(10), 0, k.dp(6)); gravity = Gravity.CENTER
            setOnClickListener { showFormula = !showFormula; page.redraw() }
        }, 6)
        if (showFormula) {
            val notes = if (s.score.items.isEmpty()) "مفيش خصومات دلوقتي." else s.score.items.joinToString("\n") { "• ${it.text} (${it.points})" }
            k.add(b, k.card(k.t(CareScore.FORMULA, 12f, pal.text), k.t(notes, 12f, pal.muted).apply { setPadding(0, k.dp(8), 0, 0) }), 4)
        }
    }

    fun show() {
        page = k.page("مركز العناية")
        page.action("🔄", "تحديث القياسات") { snap = CareMetrics.read(act); page.redraw() }
        page.onResume = { if (!scanning) { snap = CareMetrics.read(act); page.redraw() } }
        page.onClose = { closed = true; token++; scanning = false; h.removeCallbacksAndMessages(null) }
        page.render { render() }
        page.show()
    }
}
