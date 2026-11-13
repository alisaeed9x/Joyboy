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
    private fun fmtEvery(m: Long): String = when {
        m < 60L -> "$m دقيقة"
        m == 60L -> "ساعة"
        m == 120L -> "ساعتين"
        m / 60L > 10L -> (m / 60L).toString() + " ساعة"
        else -> (m / 60L).toString() + " ساعات"
    }

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
        var appArt: GhCi.Art? = null

        // ───── آخر 3 بيلدات + ريفريش صغير ─────
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        head.addView(ui.text("آخر 3 بيلدات", 13f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = ui.text("🔄", 17f, th.primary, true).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)) }
        head.addView(refresh, LinearLayout.LayoutParams(-2, -2))
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
            onUi { refresh.text = "⏳" }
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
        fun setApp(m: String) = onUi { appInd?.text = m }
        fun askInstall(art: GhCi.Art) {
            val mb = (art.size / 1048576L).toString()
            Thread {
                val have = try { GhCi.localUpdate(a, art.id) != null } catch (_: Throwable) { false }   // (v870) نازلة قبل كده (كاش/تنزيلات)؟
                onUi {
                    GAlert(a).setTitle("⬆ تحديث جديد")
                        .setMessage(if (have) "النسخة الجديدة منزّلة عندك في التنزيلات ومتثبتتش. تثبّتها دلوقتي (من غير تنزيل تاني)؟" else "في نسخة أحدث ($mb MB). تنزّلها وتثبّتها؟")
                        .setPositiveButton(if (have) "ثبّت" else "نزّل وثبّت") { _, _ -> download(a, art) { m -> setApp(m) } }
                        .setNegativeButton("لاحقًا", null).show()
                }
            }.apply { isDaemon = true; start() }
        }
        fun checkApp(userTap: Boolean) {
            val cur = "النسخة " + LinkVer.name(a)
            if (GhCi.token().isBlank()) { setApp("$cur · ⚠ مش متظبّط"); return }
            setApp("$cur · ⏳ بفحص...")
            Thread {
                try {
                    val art = GhCi.findUpdate(a); appArt = art
                    if (art == null) setApp("$cur · ✓ آخر نسخة")
                    else { setApp("$cur · ⬆ في نسخة أحدث (" + (art.size / 1048576L) + " MB)"); if (userTap) askInstall(art) }
                } catch (e: Throwable) { LogStore.err("GhCiUi:upd", e); setApp("$cur · ⚠ " + GhCi.explain(e)) }
            }.apply { isDaemon = true; start() }
        }
        fun paintTv() = onUi { tvInd?.text = RemoteClient.tvStatus(a) }
        fun summary(): String = listOfNotNull(
            if (GhCi.watchOn()) "مراقبة الفشل" else null, if (GhCi.autoUpd()) "تحديث تلقائي" else null,
            if (GhCi.wifiOnly()) "واي فاي بس" else null, "كل " + fmtEvery(GhCi.everyMin())
        ).joinToString(" · ")

        if (!DeviceUi.isTv(a)) {
            tvInd = block("📺 تحديث التلفزيون", false) {
                RemoteClient.forcePushFlow(a)
                h.postDelayed({ paintTv() }, 3000L); h.postDelayed({ paintTv() }, 9000L)
            }
            paintTv()
        }
        appInd = block("📱 تحديث البرنامج", true) { val k = appArt; if (k != null) askInstall(k) else checkApp(true) }
        setInd = block("⚙ الإعدادات", false) { settings(a) { onUi { setInd?.text = summary() } } }
        setInd?.text = summary()

        refresh.setOnClickListener { loadRuns(); paintTv(); checkApp(false) }
        loadRuns(); checkApp(false)

        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(col) }
        GAlert(a).setTitle("🔄 مركز التحديث").setView(sv).setNegativeButton("إغلاق", null).show()
    }

    /** (v864) كل إعدادات التحديث — مستخبية هنا بدل ما تبقى ظاهرة في المركز. بتتحفظ أول ما تغيّرها */
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

        head("الفحص")
        sw("🔔 راقب فشل الورك فلوهات", "gh_watch", true) { GhCi.schedule(a) }
        sw("⬆ دوّر على تحديث ونزّله تلقائي", "gh_autoupd", true) { GhCi.schedule(a) }
        val opts = listOf(15L, 30L, 60L, 180L, 360L, 720L)
        var evBtn: TextView? = null
        evBtn = ui.button("⏱ تكرار الفحص: كل " + fmtEvery(GhCi.everyMin())) {
            val cur = GhCi.everyMin(); val nx = opts.firstOrNull { it > cur } ?: opts.first()
            Cfg.put("gh_every", nx.toString()); GhCi.reschedule(a)
            evBtn?.text = "⏱ تكرار الفحص: كل " + fmtEvery(nx); onChange()
        }
        col.addView(evBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        head("التنزيل")
        sw("📶 التنزيل التلقائي على الواي فاي بس", "gh_wifi", true)
        sw("🔌 التنزيل التلقائي وهو بيشحن بس", "gh_charging", false)
        sw("🌿 فرع main بس", "gh_main_only", false)
        head("الإشعارات")
        sw("🔔 إشعارات (تحديث جديد / بيلد فشل)", "gh_notify", true)
        sw("📥 نزّل لوج الفشل مقدمًا", "gh_prelog", true)
        if (!DeviceUi.isTv(a)) {
            head("التلفزيون")
            sw("📺 ابعت التحديث للتلفزيون أول ما يتصل", "gh_tv_auto", true)
        }
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(col) }
        GAlert(a).setTitle("⚙ إعدادات التحديث").setView(sv).setNegativeButton("إغلاق", null).show()
    }

    private fun download(a: Activity, art: GhCi.Art, say: (String) -> Unit) {
        Thread {
            try {
                val local = GhCi.localUpdate(a, art.id)   // (v870) لو نازلة في التنزيلات ← ثبّتها من غير تنزيل
                if (local != null) {
                    Cfg.put("gh_upd_id", art.id.toString()); Cfg.put("gh_upd_asked", art.id.toString())
                    say("✓ لقيت النسخة في التنزيلات — بفتح التثبيت")
                    a.runOnUiThread { try { TvUpdate.install(a, local) } catch (e: Throwable) { LogStore.err("GhCiUi:install", e) } }
                    return@Thread
                }
                var lastPct = -1
                val f = GhCi.downloadUpdate(a, art) { done, total ->
                    if (total > 0L) { val p = (done * 100L / total).toInt(); if (p != lastPct) { lastPct = p; say("⏳ تنزيل التحديث… $p%") } }
                    else say("⏳ تنزيل التحديث… " + (done / 1048576L) + " MB")
                }
                Cfg.put("gh_upd_id", art.id.toString()); Cfg.put("gh_upd_asked", art.id.toString())
                say("✓ التنزيل خلص (واتحفظ في Download/Joyboy) — بفتح التثبيت")
                a.runOnUiThread { try { TvUpdate.install(a, f) } catch (e: Throwable) { LogStore.err("GhCiUi:install", e) } }
            } catch (e: GhCi.NoNewer) {
                say("✓ أنت على آخر نسخة (" + LinkVer.name(a) + ")")
            } catch (e: Throwable) { LogStore.err("GhCiUi:dl", e); say("⚠ التنزيل فشل: " + GhCi.explain(e)) }
        }.apply { isDaemon = true; start() }
    }
}
