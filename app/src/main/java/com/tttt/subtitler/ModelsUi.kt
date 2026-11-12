package com.tttt.subtitler

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * (v696) شاشة الموديلات الأوفلاين (الإعدادات ← 🎙 التفريغ والصوت). لكل موديل: الحالة · «اختار الملف» (SAF من غير نسخ) ·
 * «نزّل من التطبيق» (بالاستكمال) · نسخ الرابط للتنزيل من المتصفح. المستخدم حر في الطريقتين. كله أزرار عادية (يتنقل بالـD-pad).
 * ما اتجرّبتش على جهاز.
 */
object ModelsUi {
    const val REQ = 71
    private class Item(val rel: String, val title: String, val approxMb: Int, val url: String, val note: String)
    private var pendingRel: String? = null
    private val progTv = HashMap<String, TextView>()
    private var rebuild: (() -> Unit)? = null

    private fun items(): List<Item> {
        val l = ArrayList<Item>()
        Offline.models.firstOrNull { it.id == "turbo" }?.let { l.add(Item("whisper/" + it.file, "🎙 Whisper large-v3-turbo (q5_0)", it.approxMb, Offline.whisperUrl(it), "التفريغ — الأقوى للياباني والإنجليزي")) }
        for (m in LocalMt.models) l.add(Item("mt/" + m.file, "🌍 " + m.label, m.approxMb, LocalMt.url(m), "الترجمة — " + m.note))
        return l
    }

    private fun stateOf(it: Item): String {
        val min = it.approxMb * 600_000L
        if (ModelStore.attached(it.rel)) return "✅ مختار من الموبايل"
        val f = Offline.dir?.let { d -> File(d, it.rel) }
        if (f != null && f.isFile && f.length() > min) return "✅ موجود (" + (f.length() / 1_048_576L) + " ميجا)"
        val legacy = Offline.legacy?.let { d -> File(d, it.rel) }
        if (legacy != null && legacy.isFile && legacy.length() > min) return "✅ موجود (" + (legacy.length() / 1_048_576L) + " ميجا)"
        val p = f?.let { File(it.path + ".part") }
        if (p != null && p.isFile && p.length() > 0) return "⏸ تنزيل ناقص (" + (p.length() / 1_048_576L) + " من ~" + it.approxMb + " ميجا) — كمّل"
        return "⬇ مش موجود (~" + it.approxMb + " ميجا)"
    }

    fun section(act: Activity, ui: Ui, th: Theme): View {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun lp() = LinearLayout.LayoutParams(-1, -2)
        fun build() {
            box.removeAllViews(); progTv.clear()
            box.addView(ui.text(Offline.status() + "\n" + LocalMt.status(), 12f, th.muted), lp())
            // (v698 م7) مصدر المعالجة: الموبايل (التلفزيون يبعت الصوت والموبايل يفرّغ ويترجم) أو الجهاز ده
            val srcMobile = "📱 الموبايل"; val srcHere = "📍 الجهاز ده"
            box.addView(ui.text("⚙ مصدر المعالجة (التفريغ + الترجمة):", 12f, th.muted), lp().apply { topMargin = 14 })
            box.addView(ui.chips(listOf(srcMobile, srcHere), { if (Cfg.str(Offload.KEY, "") == "phone") srcMobile else srcHere }) { l ->
                val v = if (l == srcMobile) "phone" else ""
                Cfg.put(Offload.KEY, v)
                try { if (LinkSession.connected) LinkSession.setCfg(mapOf(Offload.KEY to v)) } catch (_: Throwable) {}   // التلفزيون هو اللي بيقرا القيمة
                build()
            }, lp())
            (Offload.status() + (Offload.workerStatus().let { if (it.isEmpty()) "" else "\n$it" })).trim().let { if (it.isNotEmpty()) box.addView(ui.text(it, 11.5f, th.muted), lp()) }
            box.addView(ui.text("الموديلات برّه التطبيق. اختار الملف من الموبايل، أو نزّله من هنا، أو نزّله من المتصفح وانسخه لـ Android/media/" + act.packageName + "/models/ (whisper أو mt) والتطبيق يلاقيه بالاسم.", 11f, th.muted), lp())
            box.addView(ui.text("🌍 موديل الترجمة:", 12f, th.muted), lp())
            box.addView(ui.chips(LocalMt.models.map { it.label }, { LocalMt.model().label }) { l ->
                LocalMt.models.firstOrNull { it.label == l }?.let { Cfg.put("mt_model", it.id); LocalMt.release() }
                build()
            }, lp())
            // (v747) 🪶 أصغر حجم: Whisper base (~60 ميجا) + موديل الترجمة 1.25-bit (~440 ميجا)
            box.addView(ui.button("🪶 أصغر حجم (Whisper base + ترجمة 1.25-bit ≈ 500 ميجا)") {
                Cfg.put("off_model", "base"); Cfg.put("tr_s_model", "base"); Cfg.put("mt_model", "q125"); LocalMt.release()
                Notice.show(act, if (LlmLib.stq) "اتظبط: base + 1.25-bit — نزّل الموديلين من تحت" else "اتظبط، بس البيلد ده من غير kernel الـ1.25-bit (JOY_STQ=1) — الترجمة مش هتشتغل بيه", 4500L)
                build()
            }, lp().apply { topMargin = 10 })
            for (it in items()) {
                box.addView(ui.text(it.title + "\n" + stateOf(it) + "\n" + it.note, 12.5f, th.text), lp().apply { topMargin = 18 })
                val prog = ui.text("", 11f, th.muted); progTv[it.rel] = prog
                box.addView(ui.button("📂 اختار الملف (من الموبايل)") { pick(act, it.rel) }, lp())
                val dlLabel = if (Offline.downloading()) "⏹ إلغاء التنزيل" else "⬇ نزّل من التطبيق"
                box.addView(ui.button(dlLabel) {
                    if (Offline.downloading()) { Offline.cancelDownload(); return@button }
                    download(act, it, prog)
                }, lp())
                box.addView(ui.button("🔗 انسخ الرابط (للمتصفح)") {
                    try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("model", it.url)); Notice.show(act, "اتنسخ الرابط", 1600L) }
                    catch (_: Throwable) { Notice.show(act, it.url, 6000L) }
                }, lp())
                if (ModelStore.attached(it.rel)) box.addView(ui.button("↩ فصل الملف المختار") { ModelStore.detach(it.rel); if (it.rel.startsWith("whisper/")) Offline.releaseWhisper() else LocalMt.release(); build() }, lp())
                box.addView(prog, lp())
            }
        }
        rebuild = { act.runOnUiThread { try { build() } catch (_: Throwable) {} } }
        build()
        return ui.section("📦 الموديلات الأوفلاين (تفريغ + ترجمة)", true, box)
    }

    private fun pick(act: Activity, rel: String) {
        try {
            pendingRel = rel
            act.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ)
        } catch (_: ActivityNotFoundException) {
            Notice.show(act, "الجهاز ده مفيهوش مختار ملفات — استخدم «نزّل من التطبيق» أو انسخ الملف يدويًا (USB/adb) لـ Android/media/" + act.packageName + "/models/", 6500L)
        } catch (e: Throwable) { LogStore.err("ModelsUi:pick", e); Notice.show(act, "تعذّر فتح مختار الملفات", 2500L) }
    }

    fun onResult(act: Activity, code: Int, d: Intent?) {
        val rel = pendingRel; pendingRel = null
        val uri = d?.data
        if (code != Activity.RESULT_OK || rel == null || uri == null) return
        val err = ModelStore.attach(act, rel, uri)
        if (err == null) {
            if (rel.startsWith("whisper/")) Offline.releaseWhisper() else LocalMt.release()   // يتحمّل من المسار الجديد
            Notice.show(act, "✅ الملف اتربط", 2200L)
        } else Notice.show(act, "⚠ " + err, 4500L)
        rebuild?.invoke()
    }

    private fun download(act: Activity, it: Item, prog: TextView) {
        prog.text = "⏳ بيبدأ التنزيل…"
        try { act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}   // منع نوم الشاشة أثناء التنزيل
        Offline.fetchModel(it.rel, it.url, it.approxMb,
            { a, t -> act.runOnUiThread { prog.text = "⬇ " + (a / 1_048_576L) + " / " + (if (t > a) (t / 1_048_576L).toString() else "~" + it.approxMb) + " ميجا" } },
            { err ->
                act.runOnUiThread {
                    try { act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}
                    Notice.show(act, if (err == null) "✅ الموديل نزل" else "❌ $err", 3500L)
                    rebuild?.invoke()
                }
            })
        rebuild?.invoke().also { }   // الزرار يتحوّل لـ «إلغاء»
        progTv[it.rel]?.text = "⏳ بيبدأ التنزيل…"
    }
}
