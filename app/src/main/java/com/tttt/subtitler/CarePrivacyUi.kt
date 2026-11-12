package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout

/**
 * (v827 · Joyboy Care م5) مركز الخصوصية: مين معلن وممنوح له إيه من الأذونات الحساسة.
 * «مُعلن» = مكتوب في مانيفست التطبيق · «ممنوح» = الموافقة موجودة دلوقتي. ده مش «استخدام فعلي»:
 * مفيش API لتطبيق عادي بيدّيني آخر استخدام — لوحة الخصوصية في إعدادات أندرويد (حسب الجهاز) هي المرجع. مفيش مراقبة مستمرة.
 */
class CarePrivacyUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val pal = CarePalette.of(th)
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private var recs: List<AppRec>? = null
    private var withSystem = false

    private val primary = mapOf(
        "camera" to "CAMERA", "mic" to "RECORD_AUDIO", "loc" to "ACCESS_FINE_LOCATION", "contacts" to "READ_CONTACTS",
        "phone" to "READ_PHONE_STATE", "sms" to "READ_SMS", "notif" to "POST_NOTIFICATIONS", "files" to "READ_EXTERNAL_STORAGE"
    )

    private fun add(v: View, top: Int = 8) { col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(top) }) }

    private fun openPerm(g: CarePerm.Group) {
        val perm = "android.permission." + (primary[g.key] ?: "CAMERA")
        try {
            act.startActivity(Intent("android.intent.action.MANAGE_PERMISSION_APPS").putExtra("android.intent.extra.PERMISSION_NAME", perm))
        } catch (_: Throwable) {
            try { act.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS)) } catch (_: Throwable) {}
        }
    }

    private fun load() {
        recs = null; render()
        Thread {
            val l = try { CareAppsRead.load(act) } catch (_: Throwable) { emptyList() }
            act.runOnUiThread { recs = l; render() }
        }.start()
    }

    private fun render() {
        col.removeAllViews()
        col.background = ColorDrawable(pal.bg); col.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(14))
        add(ui.text("الأرقام دي أذونات «مُعلنة» و«ممنوحة» دلوقتي — مش دليل إن التطبيق بيستخدمها فعلًا. آخر استخدام مش متاح لتطبيق عادي؛ شوفه في إعدادات الخصوصية بتاعة أندرويد (لوحة الخصوصية على أندرويد 12+ حسب جهازك).", 11f, pal.muted), 4)
        add(ui.button("🛡 افتح إعدادات الخصوصية") { try { act.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS)) } catch (_: Throwable) {} }, 8)
        val l = recs
        if (l == null) { add(ui.text("بقرا التطبيقات…", 13f, pal.muted), 10); return }
        add(ui.switchRow("اعرض تطبيقات النظام كمان", withSystem) { withSystem = it; render() }, 8)
        val pool = if (withSystem) l else CareAppLogic.userApps(l)
        add(ui.text("${pool.size} تطبيق اتفحص.", 11f, pal.muted), 4)

        for (g in CarePerm.GROUPS) {
            val decl = pool.filter { CarePerm.declared(it, g).isNotEmpty() }
            val grant = decl.filter { CarePerm.granted(it, g).isNotEmpty() }.sortedBy { it.label.lowercase() }
            val rows = ArrayList<View>()
            if (grant.isEmpty()) rows.add(ui.text("مفيش تطبيق ممنوح له الإذن ده.", 12f, pal.muted))
            for (r in grant.take(60)) {
                val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                t.addView(ui.text(r.label + (if (r.system) "  ⚙" else ""), 12f, pal.text, true).apply { maxLines = 1 })
                t.addView(ui.text(CarePerm.granted(r, g).joinToString(" · ") { CarePerm.short(it) }, 10f, pal.muted).apply { maxLines = 2 })
                rows.add(t.also { it.setPadding(0, ui.dp(3), 0, ui.dp(3)) })
            }
            if (grant.size > 60) rows.add(ui.text("معروض أول 60 من ${grant.size}.", 10f, pal.muted))
            rows.add(ui.text("مُعلن لـ ${decl.size} تطبيق (منهم ${decl.size - grant.size} من غير موافقة حاليًا).", 11f, pal.muted))
            if (g.key == "files") rows.add(ui.text("ملحوظة: «الوصول لكل الملفات» إذن خاص بيتمنح من صفحة مخصوصة، فممكن ما يظهرش كممنوح هنا.", 10f, pal.muted))
            rows.add(ui.button("⚙ إدارة إذن ${g.title.substringAfter(' ')} في النظام") { openPerm(g) })
            add(ui.section("${g.title} · ممنوح لـ ${grant.size} من ${decl.size}", false, *rows.toTypedArray()), 8)
        }
        add(ui.button("🔄 تحديث") { load() }, 12)
    }

    fun show() {
        load()
        val pg = CareKit(act, ui, th).page("مركز الخصوصية")   // (v828) صفحة كاملة
        pg.body.addView(col, LinearLayout.LayoutParams(-1, -2))
        pg.show()
    }
}
