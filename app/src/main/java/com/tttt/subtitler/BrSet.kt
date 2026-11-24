package com.tttt.subtitler

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage

/** (v1278) إعدادات المتصفح جوه الإعدادات الرئيسية (كانت في ⚙️ جوه المتصفح بس). بتكتب في نفس مفاتيح المتصفح (SharedPreferences "p") فالمتصفح بياخدها أول ما يتفتح. */
object BrSet {
    private val PROT = listOf("مقفول", "خفيف", "متوسط", "قوي")
    private fun p(a: Activity) = a.getSharedPreferences("p", 0)
    private fun protLevel(a: Activity): String {
        if (!p(a).getBoolean("adblock", true)) return "مقفول"
        return when (PopupGuard.modeOf(p(a).getString("pop_mode", "ذكي"))) { PopupGuard.OFF -> "خفيف"; PopupGuard.STRICT -> "قوي"; else -> "متوسط" }
    }
    private fun desc(l: String) = when (l) {
        "مقفول" -> "من غير أي حماية: الإعلانات والبوب-أب بتظهر زي ما هي."
        "خفيف" -> "بيمنع الإعلانات المعروفة بس. البوب-أب شغّال عادي."
        "قوي" -> "إعلانات + بوب-أب صارم: مفيش نافذة بتتفتح غير بلينك حقيقي دوست عليه."
        else -> "إعلانات + بوب-أب ذكي (الموصى بيه)."
    }
    fun views(a: Activity, ui: Ui, th: Theme): List<View> {
        val pr = p(a)
        fun lbl(t: String) = ui.text(t, 12f, th.muted, true).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) }
        lateinit var pd: android.widget.TextView
        val prot = ui.section("🛡 الحماية والفحص", true,
            lbl("🛡 الحماية (إعلانات · بوب-أب · تحويلات)"),
            ui.chips(PROT, { protLevel(a) }) { l ->
                pr.edit().putBoolean("adblock", l != "مقفول").putString("pop_mode", when (l) { "قوي" -> "صارم"; "متوسط" -> "ذكي"; else -> "مقفول" }).apply()
                pd.text = desc(l) },
            ui.text(desc(protLevel(a)), 11f, th.muted).also { pd = it },
            ui.switchRow("🔒 فصل لينكات الكبار في «سجل الخصوصية» المقفول", PrivacyFilter.on()) { Cfg.put("pf_on", if (it) "1" else "0") },
            ui.switchRow("🤖 الذكاء الصناعي يصنّف الصفحات (بيبعت الرابط والعنوان بس، مرة لكل موقع)", PrivacyFilter.aiOn()) { Cfg.put("pf_ai", if (it) "1" else "0") },
            ui.switchRow("🕶 احتفظ بالمتصفح المخفي لو خرجت من التطبيق", pr.getBoolean("br_priv_keep", true)) { pr.edit().putBoolean("br_priv_keep", it).apply() })
        val zN = listOf("صغير", "عادي", "كبير", "كبير جدًا"); val zV = listOf(85, 100, 120, 150)
        val homeEt = ui.input("https://www.google.com", pr.getString("br_home", "") ?: "").apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        homeEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { pr.edit().putString("br_home", s?.toString()?.trim() ?: "").apply() }
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
        })
        val browse = ui.section("🌐 التصفح", false,
            ui.switchRow("🖥 نسخة الكمبيوتر في التبويبات الجديدة", pr.getBoolean("br_desktop_default", false)) { pr.edit().putBoolean("br_desktop_default", it).apply() },
            ui.switchRow("💾 احفظ التبويبات لما أخرج وارجّعها", pr.getBoolean("br_save_tabs", true)) { v ->
                pr.edit().putBoolean("br_save_tabs", v).apply()
                if (!v) try { java.io.File(a.filesDir, "br_tabs.json").delete() } catch (_: Throwable) {} },
            lbl("🔠 حجم الخط في الصفحات"),
            ui.chips(zN, { zN[zV.indexOf(pr.getInt("br_zoom", 100)).coerceAtLeast(0)] }) { pick -> val i = zN.indexOf(pick); if (i >= 0) pr.edit().putInt("br_zoom", zV[i]).apply() },
            lbl("محرك البحث"),
            ui.chips(listOf("Google", "DuckDuckGo", "Bing"), { pr.getString("br_engine", "Google") ?: "Google" }) { pr.edit().putString("br_engine", it).apply() },
            lbl("الصفحة الرئيسية (فاضي = جوجل)"), homeEt)
        val dqN = listOf("الأعلى", "لحد 1080p", "لحد 720p", "لحد 480p", "لحد 360p"); val dqV = listOf("0", "1080", "720", "480", "360")
        val video = ui.section("🎞 الفيديو", false,
            lbl("الجودة الافتراضية لفيديوهات المتصفح"),
            ui.chips(dqN, { dqN[dqV.indexOf(pr.getString("br_defq", "0") ?: "0").coerceAtLeast(0)] }) { pick -> val i = dqN.indexOf(pick); if (i >= 0) pr.edit().putString("br_defq", dqV[i]).apply() })
        val data = ui.section("🧹 البيانات والخصوصية", false,
            ui.button("🍪 امسح الكوكيز وبيانات المواقع (هتتسجّل خروج من المواقع)") {
                try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
                Notice.show(a, "الكوكيز وبيانات المواقع اتمسحت", 2000L) },
            ui.button("🕘 امسح سجل المتصفح") { BrowserStore.clearHistory(a); PlayTarget.clearTvHistory(a, "browser"); Notice.show(a, "السجل اتمسح", 1800L) })
        return listOf(ui.noHint(), prot, browse, video, data)
    }
}
