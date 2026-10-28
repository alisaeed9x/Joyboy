package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Handler
import android.os.HandlerThread
import android.widget.EditText
import android.widget.Toast

/**
 * (v623) ريموت IR لتلفزيون TCL من بلاستر الأشعة تحت الحمراء اللي في الموبايل (ConsumerIrManager) — من غير واي فاي ولا ريموت خارجي.
 *  - الافتراضي: أكواد TCL أندرويد (بروتوكول Nikai 24 بت) المنشورة لموديلات 55P715 / 55T8B (RC833) — مش كل موديلات TCL بتستخدمها.
 *  - لو التلفزيون ما استجابش: دوسة طويلة على زرار 📡 في الكنترولر ← تعديل كود أي زرار (nikai:HEX6 أو nec:HEX2:HEX2) + تجربة.
 */
object IrTv {
    const val CARRIER = 38000
    /** ترتيب الأزرار في شاشة التعديل: مفتاح · اسم */
    val KEYS = listOf("power" to "⏻ تشغيل/إيقاف", "up" to "⬆ فوق", "down" to "⬇ تحت", "left" to "⬅ شمال", "right" to "➡ يمين", "ok" to "OK",
        "back" to "↩ رجوع", "home" to "🏠 رئيسية", "menu" to "☰ منيو", "vol_up" to "🔊 صوت +", "vol_dn" to "🔉 صوت −", "mute" to "🔇 كتم", "source" to "⎘ مصدر")
    /** TCL (Tasmota docs: TCL 55P715 / 55T8B) — Nikai 24-bit، MSB أول */
    private val DEFAULTS = mapOf("power" to "nikai:0D5F2A", "up" to "nikai:0A6F59", "down" to "nikai:0A7F58", "left" to "nikai:0A9F56", "right" to "nikai:0A8F57",
        "ok" to "nikai:00BFF4", "back" to "nikai:0D8F27", "home" to "nikai:0F7F08", "menu" to "nikai:013FEC", "vol_up" to "nikai:0D0F2F", "vol_dn" to "nikai:0D1F2E",
        "mute" to "nikai:0C0F3F", "source" to "nikai:05CFA3")

    private var th: HandlerThread? = null
    private var h: Handler? = null
    private fun worker(): Handler {
        h?.let { return it }
        val t = HandlerThread("ir-tx").also { it.start() }; th = t
        return Handler(t.looper).also { h = it }
    }

    private fun mgr(c: Context): ConsumerIrManager? = try { c.applicationContext.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager } catch (_: Throwable) { null }
    fun available(c: Context): Boolean = try { mgr(c)?.hasIrEmitter() == true } catch (_: Throwable) { false }

    fun code(k: String): String = Cfg.str("ir_$k", "").trim().ifEmpty { DEFAULTS[k] ?: "" }

    /** الكود (نص) ← نمط نبضات (µs) يبدأ بـON. null لو الصيغة غلط */
    fun pattern(spec: String): IntArray? {
        val s = spec.trim().lowercase()
        val parts = s.split(":").map { it.trim() }
        val out = ArrayList<Int>()
        try {
            when (parts[0]) {
                "nikai" -> {   // هيدر 4000/4000 · بت: 500 + (2000 للـ1 | 1000 للـ0) · فوتر 500 · فاصل
                    val v = parts[1].toLong(16); val n = 24
                    out.add(4000); out.add(4000)
                    for (i in n - 1 downTo 0) { out.add(500); out.add(if ((v shr i) and 1L == 1L) 2000 else 1000) }
                    out.add(500); out.add(8500)
                }
                "nec" -> {     // nec:العنوان:الأمر (8 بت لكل واحد) — LSB أول، ومعاهم المقلوب
                    val a = parts[1].toInt(16) and 0xFF; val c = parts[2].toInt(16) and 0xFF
                    val bytes = intArrayOf(a, a.inv() and 0xFF, c, c.inv() and 0xFF)
                    out.add(9000); out.add(4500)
                    for (b in bytes) for (i in 0 until 8) { out.add(560); out.add(if ((b shr i) and 1 == 1) 1690 else 560) }
                    out.add(560); out.add(40000)
                }
                else -> return null
            }
        } catch (_: Throwable) { return null }
        return out.toIntArray()
    }

    /** يبعت زرار (power/up/down/left/right/ok/back/home/…) — من خيط خلفي. بيرجّع false لو مفيش IR أو الكود غلط */
    fun send(c: Context, key: String): Boolean {
        val m = mgr(c) ?: return false
        val spec = code(key); if (spec.isEmpty()) return false
        val p = pattern(spec) ?: return false
        worker().post { try { m.transmit(CARRIER, p) } catch (e: Throwable) { LogStore.err("IrTv:tx", e) } }
        return true
    }

    /** شاشة تعديل الأكواد: اضغط على زرار ← عدّل الكود ← «جرّب» بيبعته فورًا. فاضي = الافتراضي */
    fun editor(act: Activity) {
        try {
            val names = KEYS.map { (k, n) -> n + "   " + code(k) + (if (Cfg.str("ir_$k", "").isNotBlank()) "  ✎" else "") }.toTypedArray()
            android.app.AlertDialog.Builder(act).setTitle("📡 أكواد IR (TCL)")
                .setItems(names) { _, i -> editOne(act, KEYS[i].first, KEYS[i].second) }
                .setNegativeButton("إغلاق", null).show()
        } catch (e: Throwable) { LogStore.err("IrTv:editor", e) }
    }

    private fun editOne(act: Activity, key: String, label: String) {
        val et = EditText(act).apply { setText(code(key)); setSingleLine(); hint = "nikai:0D5F2A  أو  nec:04:08"; setPadding(48, 32, 48, 32); layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR }
        android.app.AlertDialog.Builder(act).setTitle(label).setView(et)
            .setPositiveButton("حفظ") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isEmpty() || t == DEFAULTS[key]) Cfg.put("ir_$key", "")
                else if (pattern(t) == null) Toast.makeText(act, "صيغة غلط — استخدم nikai:HEX6 أو nec:HEX2:HEX2", Toast.LENGTH_LONG).show()
                else Cfg.put("ir_$key", t)
                editor(act)
            }
            .setNeutralButton("جرّب") { _, _ ->
                val t = et.text.toString().trim().ifEmpty { DEFAULTS[key] ?: "" }
                val p = pattern(t)
                if (p == null) Toast.makeText(act, "صيغة غلط", Toast.LENGTH_SHORT).show()
                else { val m = mgr(act); if (m == null) Toast.makeText(act, "مفيش IR في الموبايل", Toast.LENGTH_SHORT).show() else worker().post { try { m.transmit(CARRIER, p) } catch (e: Throwable) { LogStore.err("IrTv:test", e) } } }
                editOne(act, key, label)
            }
            .setNegativeButton("رجوع") { _, _ -> editor(act) }.show()
    }
}
