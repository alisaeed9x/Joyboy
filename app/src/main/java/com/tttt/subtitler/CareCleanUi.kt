package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v826 · Joyboy Care م4) المنظّف: «مرشّحين للمراجعة» مش «آمن للحذف». مفيش حاجة متحدّدة لوحدها،
 * والحذف بتأكيد صريح على مستوى العنصر، والحجم المعروض تقدير لحد ما الحذف ينجح فعلًا.
 * مفيش لمس لكاش تطبيقات تانية (أندرويد مانع). الفحص على Thread خلفي وقابل للإلغاء.
 */
class CareCleanUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val pal = CarePalette.of(th)
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var cancel = false
    private var cacheC: List<CareClean.Cand>? = null
    private var scan: CareClean.Scan? = null
    private var scanning = false
    private var busy = false
    private var progress = 0
    private var report = ""
    private val sel = HashSet<String>()
    private var sumTv: TextView? = null
    private var delBtn: View? = null

    private fun add(v: View, top: Int = 8) { col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(top) }) }
    private fun sz(b: Long): String = try { Formatter.formatShortFileSize(act, b) } catch (_: Throwable) { "${b / 1048576} MB" }
    private fun hasAll(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
    private fun box(v: View): View { v.background = ui.box(pal.surface, pal.line, 16); v.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); return v }

    private fun cacheDirs(): List<java.io.File> = listOfNotNull(act.cacheDir, act.externalCacheDir)

    private fun loadCache() {
        Thread {
            val r = try { CareClean.scanCache(cacheDirs(), System.currentTimeMillis()) } catch (_: Throwable) { emptyList() }
            act.runOnUiThread { cacheC = r; render() }
        }.start()
    }

    private fun allCands(): List<CareClean.Cand> = scan?.cands ?: emptyList()
    private fun selected(): List<CareClean.Cand> = allCands().filter { it.file.path in sel }

    private fun updateSummary() {
        val s = selected(); val tot = s.sumOf { it.size }
        sumTv?.text = if (s.isEmpty()) "مفيش حاجة متحدّدة." else "المتحدّد: ${s.size} عنصر · حوالي ${sz(tot)} (تقدير لحد ما الحذف ينجح)"
        delBtn?.alpha = if (s.isEmpty() || busy) 0.5f else 1f
    }

    private fun title(cat: Int) = when (cat) {
        CareClean.CAT_DUP -> "📑 ملفات مكررة (نفس المحتوى)"; CareClean.CAT_LARGE -> "📦 ملفات كبيرة (100MB+)"
        CareClean.CAT_APK -> "📲 ملفات APK"; CareClean.CAT_JUNK -> "🗑 ملفات مؤقتة وصور مصغّرة"; else -> "📁 فولدرات فاضية"
    }

    private fun row(c: CareClean.Cand): View {
        val cb = CheckBox(act).apply {
            isChecked = c.file.path in sel
            contentDescription = "تحديد " + c.file.name
            setOnCheckedChangeListener { _, on -> if (on) sel.add(c.file.path) else sel.remove(c.file.path); updateSummary() }
        }
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        t.addView(ui.text(c.file.name, 12f, pal.text, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        t.addView(ui.text((c.file.parent ?: "") + (if (c.cat != CareClean.CAT_EMPTY) "  ·  ${sz(c.size)}" else ""), 10f, pal.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            addView(cb); addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { cb.isChecked = !cb.isChecked }
        }
    }

    private fun catSection(cat: Int): View? {
        val list = allCands().filter { it.cat == cat }
        if (list.isEmpty()) return null
        val total = list.sumOf { it.size }
        val rows = ArrayList<View>()
        if (cat == CareClean.CAT_DUP) {
            val groups = list.groupBy { it.group }.values.sortedByDescending { it.first().size * it.size }.take(40)
            for (g in groups) {
                rows.add(ui.text("${g.size} نسخ · ${sz(g.first().size)} للنسخة — سيب نسخة واحدة على الأقل", 11f, pal.warn, true))
                for (c in g) rows.add(row(c))
            }
            if (list.groupBy { it.group }.size > 40) rows.add(ui.text("معروض أكبر 40 مجموعة بس.", 10f, pal.muted))
        } else {
            val shown = list.sortedByDescending { it.size }.take(80)
            for (c in shown) rows.add(row(c))
            if (list.size > shown.size) rows.add(ui.text("معروض أول ${shown.size} من ${list.size} (الأكبر الأول).", 10f, pal.muted))
        }
        val extra = if (cat == CareClean.CAT_EMPTY) "" else " · ${sz(total)}"
        return ui.section(title(cat) + " (${list.size})" + extra, false, *rows.toTypedArray())
    }

    private fun render() {
        col.removeAllViews()
        col.background = ColorDrawable(pal.bg); col.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(14))
        add(ui.text("القوائم دي «مرشّحين للمراجعة» مش «آمن للحذف». مفيش حاجة بتتحدّد لوحدها، وكل حذف بتأكيد منك ونهائي (مفيش سلة محذوفات). الأحجام تقدير لحد ما الحذف ينجح فعلًا.", 11f, pal.muted), 4)
        if (report.isNotEmpty()) add(box(ui.text(report, 12f, pal.text, true)), 8)

        // 1) كاش التطبيق
        val cc = cacheC
        val cacheBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        cacheBox.addView(ui.text("🗂 كاش Joyboy القديم", 14f, pal.text, true))
        cacheBox.addView(ui.text(
            if (cc == null) "بحسب…" else if (cc.isEmpty()) "مفيش ملفات كاش أقدم من يوم."
            else "${cc.size} ملف · حوالي ${sz(cc.sumOf { it.size })} — ملفات ما اتعدّلتش من 24 ساعة أو أكتر (عشان ما نلمسش تحميل شغّال). ممكن Joyboy يعيد بناءها لو احتاجها.", 12f, pal.text),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        cacheBox.addView(ui.text("كاش التطبيقات التانية مش بنقدر نمسحه من هنا (أندرويد مانع) — من إعدادات النظام لكل تطبيق.", 10f, pal.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        if (cc != null && cc.isNotEmpty()) cacheBox.addView(ui.button("🧹 امسح الكاش القديم") { if (!busy) confirmCache(cc) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        add(box(cacheBox), 8)

        // 2) ملفات التخزين
        val sb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        sb.addView(ui.text("💾 ملفات التخزين", 14f, pal.text, true))
        if (!hasAll()) {
            sb.addView(ui.text("فحص الملفات محتاج إذن «الوصول لكل الملفات» (الإذن معلن في التطبيق للمدير بتاع الملفات، وإنت اللي بتمنحه من إعدادات النظام). من غيره مقدرش أفحص غير كاش Joyboy.", 12f, pal.text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            sb.addView(ui.button("⚙️ افتح إعداد الإذن") {
                try { act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + act.packageName))) } catch (_: Throwable) {}
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
            sb.addView(ui.button("🔄 رجعت من الإعدادات — حدّث") { render() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        } else if (scanning) {
            sb.addView(ui.text("جاري الفحص… $progress ملف اتفحص (المكررات بتتحسب في الآخر وممكن تاخد وقت).", 12f, pal.text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            sb.addView(ui.button("⏹ إلغاء الفحص") { cancel = true }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        } else {
            val s = scan
            sb.addView(ui.text(
                if (s == null) "بيدوّر على: ملفات كبيرة · APK · مكررات (بالمحتوى SHA-256) · فولدرات فاضية. بيتخطّى فولدر Android والمخفي."
                else "اتفحص ${s.visited} ملف" + (if (s.truncated) " (وقف عند الحد الأقصى — النتيجة ناقصة)" else "") + (if (s.unreadable > 0) " · ${s.unreadable} فولدر مش مقروء" else "") +
                    " · ${s.cands.size} مرشّح",
                12f, pal.text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            sb.addView(ui.button(if (s == null) "🔍 افحص الملفات" else "🔍 افحص تاني", true) { if (!busy) startScan() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        }
        add(box(sb), 8)

        for (cat in listOf(CareClean.CAT_DUP, CareClean.CAT_LARGE, CareClean.CAT_JUNK, CareClean.CAT_APK, CareClean.CAT_EMPTY)) catSection(cat)?.let { add(it, 8) }

        if (allCands().isNotEmpty()) {
            val sum = ui.text("", 12f, pal.text, true); sumTv = sum
            add(sum, 12)
            val b = ui.button("🗑 احذف المحدد") { if (!busy) confirmDelete() }; delBtn = b
            add(b, 6)
            updateSummary()
        }
    }

    private fun startScan() {
        if (!hasAll() || scanning) return
        scanning = true; cancel = false; progress = 0; report = ""; sel.clear(); scan = null
        render()
        Thread {
            val r = try {
                CareClean.scanStorage(listOf(Environment.getExternalStorageDirectory()), { cancel }, { n -> progress = n; h.post { if (scanning) render() } })
            } catch (_: Throwable) { CareClean.Scan(emptyList(), 0, false, true, 0) }
            act.runOnUiThread {
                scanning = false
                if (r.cancelled) { report = "اتلغى الفحص."; scan = null } else { scan = r; report = "الفحص خلص: ${r.cands.size} مرشّح للمراجعة." }
                render()
            }
        }.start()
    }

    private fun confirmCache(cc: List<CareClean.Cand>) {
        val tot = cc.sumOf { it.size }
        GAlert(act).setTitle("مسح كاش Joyboy القديم")
            .setMessage("هتمسح ${cc.size} ملف كاش (حوالي ${sz(tot)}). Joyboy ممكن يعيد بناء الكاش وقت ما يحتاجه. تكمّل؟")
            .setPositiveButton("امسح") { _, _ -> runDelete(cc, cc, true) }.setNegativeButton("لا", null).show()
    }

    private fun confirmDelete() {
        val s = selected()
        if (s.isEmpty()) { report = "اختار عناصر الأول."; render(); return }
        val groups = allCands().filter { it.cat == CareClean.CAT_DUP }.groupBy { it.group }
        val bad = s.filter { it.cat == CareClean.CAT_DUP }.map { it.group }.distinct().filter { g -> groups[g]?.all { it.file.path in sel } == true }
        if (bad.isNotEmpty()) { report = "في ${bad.size} مجموعة مكررة نسخها كلها متحدّدة — سيب نسخة واحدة على الأقل من كل مجموعة."; render(); return }
        val tot = s.sumOf { it.size }
        GAlert(act).setTitle("تأكيد الحذف النهائي")
            .setMessage("هتحذف ${s.size} عنصر (حوالي ${sz(tot)}). الحذف نهائي ومفيش سلة محذوفات ومفيش تراجع. الأرقام تقدير — اللي اتحرّر فعلًا هيتعرض بعد الحذف.")
            .setPositiveButton("احذف نهائيًا") { _, _ -> runDelete(s, allCands(), false) }.setNegativeButton("لا", null).show()
    }

    private fun runDelete(items: List<CareClean.Cand>, all: List<CareClean.Cand>, isCache: Boolean) {
        busy = true; report = "جاري الحذف…"; render()
        Thread {
            val r = try { CareClean.delete(items, all) } catch (_: Throwable) { CareClean.DelResult(0L, 0, items.size, 0) }
            act.runOnUiThread {
                busy = false; sel.clear()
                report = "اتحذف ${r.deleted} عنصر · اتحرّر فعلًا ${sz(r.freed)}" + (if (r.skipped > 0) " · اتخطّى ${r.skipped} (اتغيّروا/اتمسحوا أو آخر نسخة)" else "") + (if (r.failed > 0) " · فشل ${r.failed}" else "")
                val s0 = scan
                if (s0 != null) scan = CareClean.Scan(s0.cands.filter { it.file.exists() }, s0.visited, s0.truncated, false, s0.unreadable)
                if (isCache) { cacheC = null; render(); loadCache() } else render()
            }
        }.start()
    }

    fun show() {
        render(); loadCache()
        val pg = CareKit(act, ui, th).page("المنظّف")   // (v828) صفحة كاملة بسهم رجوع زي باقي صفحات العناية
        pg.body.addView(col, LinearLayout.LayoutParams(-1, -2))
        pg.onClose = { cancel = true; h.removeCallbacksAndMessages(null) }
        pg.show()
    }
}
