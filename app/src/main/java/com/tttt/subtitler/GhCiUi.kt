package com.tttt.subtitler

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v864) «مركز التحديث»: فوق مؤشّر آخر 3 بيلدات (وزرار ريفريش صغير جنبه)، وتحتها 3 زراير كل واحد تحته مؤشّر حالته:
 * 📺 تحديث التلفزيون · 📱 تحديث البرنامج · ⚙ الإعدادات (الإعدادات كلها مستخبية جواه).
 * الريبو والتوكن جوه البرنامج (GhCi) — مفيش حاجة بتتكتب هنا.
 */
object GhCiUi {

    fun show(a: Activity) {
        Cfg.ensure(a)
        val th = Themes.current()
        val ui = Ui(a, th)
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        fun onUi(f: () -> Unit) { a.runOnUiThread { try { f() } catch (_: Throwable) {} } }
        var appInd: TextView? = null
        var tvInd: TextView? = null
        var setInd: TextView? = null

        // ───── آخر 3 بيلدات + ريفريش صغير ─────
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        head.addView(ui.text("آخر 3 بيلدات", 13f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = ui.text("🔄", 17f, th.primary, true).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)) }
        head.addView(refresh, LinearLayout.LayoutParams(-2, -2))
        col.addView(Upd.centerViews(a, ui, th), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })   // (v1259)
        col.addView(head, LinearLayout.LayoutParams(-1, -2))
        val runBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(runBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })

        fun runLine(r: GhCi.Run): View {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(3), 0, ui.dp(3)) }
            val failed = GhCi.isFailed(r)
            val ic = when { r.status != "completed" -> "⏳"; r.conclusion == "success" -> "✅"; failed -> "❌"; r.conclusion == "cancelled" -> "⛔"; else -> "⚪" }
            val tv = ui.text(ic + " #" + r.num + " · " + r.name + " · " + YtAge.ageOf(r.startedMs), 12f, if (failed) th.text else th.muted)
                .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            if (failed) row.addView(ui.text("📥 لوج", 12f, th.primary, true).apply {
                setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); setOnClickListener { GhCi.downloadLogUi(a, r.id) }
            })
            return row
        }
        fun runsMsg(m: String) { runBox.removeAllViews(); runBox.addView(ui.text(m, 12f, th.muted)) }
        fun loadRuns() {
            onUi { LoadUi.set(refresh, "⏳", 18) }
            Thread {
                try {
                    if (GhCi.token().isBlank()) { onUi { runsMsg("⚠ النسخة دي مش متظبّطة للاتصال بـGitHub") }; return@Thread }
                    val rs = GhCi.runs().take(3)
                    onUi { if (rs.isEmpty()) runsMsg("مفيش بيلدات") else { runBox.removeAllViews(); rs.forEach { runBox.addView(runLine(it)) } } }
                } catch (e: Throwable) { LogStore.err("GhCiUi:runs", e); onUi { runsMsg("⚠ " + GhCi.explain(e)) } }
                finally { onUi { refresh.text = "🔄" } }
            }.apply { isDaemon = true; start() }
        }

        // ───── زرار + مؤشّر حالته تحته ─────
        fun block(label: String, primary: Boolean, f: () -> Unit): TextView {
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val ind = ui.text("", 11.5f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(3), 0, 0) }
            box.addView(ui.button(label, primary) { f() }, LinearLayout.LayoutParams(-1, -2))
            box.addView(ind, LinearLayout.LayoutParams(-1, -2))
            col.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            return ind
        }
        fun setApp(m: String) = onUi { appInd?.let { LoadUi.set(it, m) } }
        fun askInstall() {
            val ap = Upd.app ?: return
            val mb = (ap.size / 1048576L).toString()
            onUi {
                GAlert(a).setTitle("⬆ تحديث جديد")
                    .setMessage("في نسخة أحدث ($mb MB). تنزّلها وتثبّتها؟")
                    .setPositiveButton("نزّل وثبّت") { _, _ -> try { Upd.startApp(a) } catch (e: Throwable) { LogStore.err("GhCiUi:upd2", e) } }   // (v1357) نفس تنزيل مستطيل التحديث: per-ABI من الريليز بشريط تقدّم قابل للاستكمال (بطلب علي)
                    .setNegativeButton("لاحقًا", null).show()
            }
        }
        fun checkApp(userTap: Boolean) {
            val cur = "النسخة " + LinkVer.name(a)
            setApp("$cur · ⏳ بفحص...")
            Thread {
                try {
                    Upd.refreshNow(a)   // (v1357) «فحص» موحّد على مسار الريليزات (نفس مصدر مستطيل التحديث وبوب أب الفتح ونفس الحجم per-ABI) — مسار الـartifacts القديم اتحذف (بطلب علي)
                    val ap = Upd.app
                    if (ap == null) setApp("$cur · ✓ آخر نسخة")
                    else { setApp("$cur · ⬆ في نسخة أحدث (" + (ap.size / 1048576L) + " MB)"); if (userTap) askInstall() }
                } catch (e: Throwable) { LogStore.err("GhCiUi:upd", e); setApp("$cur · ⚠ " + GhCi.explain(e)) }
            }.apply { isDaemon = true; start() }
        }
        fun paintTv() = onUi { tvInd?.text = RemoteClient.tvStatus(a) }
        fun summary(): String = if (GhCi.watchOn()) "مراقبة فشل البيلدات شغّالة" else ""

        if (!DeviceUi.isTv(a)) {
            tvInd = block("📺 تحديث التلفزيون", false) {
                RemoteClient.forcePushFlow(a)
                h.postDelayed({ paintTv() }, 3000L); h.postDelayed({ paintTv() }, 9000L)
            }
            paintTv()
        }
        // (v1271) فحص التحديث: زرار صغير فوق في الشاشة + سطر حالة جنبه (بدل الزرار الكبير تحت)
        val topRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        appInd = ui.text("", 12f, th.muted).apply { setPadding(0, ui.dp(2), ui.dp(8), ui.dp(2)) }
        val chk = ui.text("🔍 فحص", 12f, if (th.isLight) android.graphics.Color.WHITE else android.graphics.Color.BLACK, true).apply {
            background = ui.box(th.primary, th.primary, 14); setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5))
            setOnClickListener { if (Upd.app != null) askInstall() else checkApp(true) }
        }
        topRow.addView(chk, LinearLayout.LayoutParams(-2, -2))
        topRow.addView(appInd, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(topRow, 0, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        // (v1271) سجل التغييرات بنفس عارض الكبسولات الجديد
        val notesBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(notesBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        Thread {
            try {
                val ver = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
                var txt = Upd.app?.notes.orEmpty()
                if (txt.isBlank()) txt = UpdateSheet.cumulative("0", true)
                if (txt.isNotBlank()) onUi { notesBox.removeAllViews(); notesBox.addView(ui.text("📝 آخر تغييرات", 13f, th.text, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) }); notesBox.addView(Notes.render(a, ui, th, txt)) }
            } catch (e: Throwable) { LogStore.err("GhCiUi:notes", e) }
        }.apply { isDaemon = true; start() }
        setInd = block("⚙ الإعدادات", false) { settings(a) { onUi { setInd?.text = summary() } } }
        setInd?.text = summary()

        refresh.setOnClickListener { loadRuns(); paintTv(); checkApp(false) }
        loadRuns(); checkApp(false)

        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(col) }
        GAlert(a).setTitle("🔄 مركز التحديث").setView(sv).setNegativeButton("إغلاق", null).show()
    }

    /** (v864) إعدادات مراقبة بيلدات GitHub (الفشل) — (v1357) إعدادات مسار التحديث القديم (الفحص الدوري/التنزيل التلقائي/واي فاي/الشحن/فرع main) اتحذفت كلها مع المسار نفسه (بطلب علي) */
    fun settings(a: Activity, onChange: () -> Unit) {
        val th = Themes.current()
        val ui = Ui(a, th)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        fun head(t: String) = col.addView(ui.text(t, 12.5f, th.primary, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        fun sw(label: String, key: String, def: Boolean, after: () -> Unit = {}) =
            col.addView(ui.switchRow(label, Cfg.bool(key, def)) { v -> Cfg.put(key, if (v) "1" else "0"); after(); onChange() })

        head("مراقبة البيلدات")
        sw("🔔 راقب فشل الورك فلوهات", "gh_watch", true) { GhCi.schedule(a) }
        sw("🔔 إشعار لما بيلد يفشل", "gh_notify", true)
        sw("📥 نزّل لوج الفشل مقدمًا", "gh_prelog", true)
        if (!DeviceUi.isTv(a)) {
            head("التلفزيون")
            sw("📺 ابعت التحديث للتلفزيون أول ما يتصل", "gh_tv_auto", true)
        }
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(col) }
        GAlert(a).setTitle("⚙ إعدادات التحديث").setView(sv).setNegativeButton("إغلاق", null).show()
    }
}
