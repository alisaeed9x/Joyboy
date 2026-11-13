package com.tttt.subtitler

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

/** حالة كل قياس: حي من الـAPI · تقدير · مش متاح · أو محدود بصلاحية/جهاز */
enum class MState { LIVE, ESTIMATED, UNAVAILABLE, LIMITED }

/** قياس واحد: القيمة + الوحدة + مصدره + وقته + حالته + سبب القيد لو فيه */
class Metric<T>(val value: T?, val unit: String, val source: String, val at: Long, val state: MState, val note: String = "")

/** (v825 · Joyboy Care م1) درجة الصحة: heuristic معلنة الصيغة — مش قياس. دالة صرفة عشان تتختبر على JVM. */
object CareScore {
    class Item(val text: String, val points: Int)
    class Result(val score: Int?, val items: List<Item>)

    const val FORMULA = "نبدأ من 100 ونخصم بس من اللي اتقاس فعلًا: " +
        "التخزين الفاضي أقل من 15% (−10) أو أقل من 8% (−20) · " +
        "نظام أندرويد نفسه مبلّغ إن الرام قليلة lowMemory (−15) — الرام المتاحة القليلة لوحدها مش بتتخصم لأن أندرويد بيملاها عمدًا · " +
        "بطارية 30% أو أقل من غير شحن (−4) و15% أو أقل (−10) · " +
        "حرارة البطارية 38°م (−4) · 40°م (−10) · 45°م (−20). " +
        "قياس مش متاح بيتخطّى ومابيتخصمش. لو مفيش أي قياس الدرجة بتبقى «—». الدرجة دي تقدير إرشادي مش فحص طبي للجهاز."

    fun compute(ramLow: Boolean?, stoFreeFrac: Float?, batPct: Int?, charging: Boolean, tempC: Float?): Result {
        val items = ArrayList<Item>()
        var inputs = 0
        if (stoFreeFrac != null) { inputs++
            if (stoFreeFrac < 0.08f) items.add(Item("التخزين الفاضي أقل من 8%", -20))
            else if (stoFreeFrac < 0.15f) items.add(Item("التخزين الفاضي أقل من 15%", -10))
        }
        if (ramLow != null) { inputs++
            if (ramLow) items.add(Item("أندرويد مبلّغ إن الذاكرة قليلة (lowMemory)", -15))
        }
        if (batPct != null) { inputs++
            if (!charging && batPct <= 15) items.add(Item("البطارية 15% أو أقل ومش على الشاحن", -10))
            else if (!charging && batPct <= 30) items.add(Item("البطارية 30% أو أقل ومش على الشاحن", -4))
        }
        if (tempC != null) { inputs++
            if (tempC >= 45f) items.add(Item("حرارة البطارية عالية جدًا (45°م+)", -20))
            else if (tempC >= 40f) items.add(Item("حرارة البطارية عالية (40°م+)", -10))
            else if (tempC >= 38f) items.add(Item("حرارة البطارية مرتفعة شوية (38°م+)", -4))
        }
        if (inputs == 0) return Result(null, items)
        return Result((100 + items.sumOf { it.points }).coerceIn(0, 100), items)
    }
}

/** لقطة واحدة من قياسات الجهاز — كل رقم من API مدعوم أو مُعلَّم إنه مش متاح. بدون polling: بتتقرا عند الفتح/التحديث بس. */
class CareSnap(
    val ramUsed: Metric<Float>, val ramTotal: Long, val ramAvail: Long, val ramLow: Boolean?,
    val stoUsed: Metric<Float>, val stoTotal: Long, val stoFree: Long,
    val bat: Metric<Int>, val charging: Boolean,
    val temp: Metric<Float>, val volt: Metric<Int>,
    val at: Long
) {
    val score: CareScore.Result get() = CareScore.compute(
        ramLow, stoFree.takeIf { stoUsed.value != null && stoTotal > 0 }?.let { it.toFloat() / stoTotal },
        bat.value, charging, temp.value)
}

object CareMetrics {
    fun read(ctx: Context): CareSnap {
        val now = System.currentTimeMillis()

        // RAM — ActivityManager.MemoryInfo (API رسمي)
        var rTot = 0L; var rAv = 0L; var rLow: Boolean? = null
        val ram: Metric<Float> = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            rTot = mi.totalMem; rAv = mi.availMem; rLow = mi.lowMemory
            if (rTot > 0) Metric((rTot - rAv).toFloat() / rTot, "%", "ActivityManager.MemoryInfo", now, MState.LIVE)
            else Metric(null, "%", "ActivityManager.MemoryInfo", now, MState.UNAVAILABLE, "الجهاز رجّع إجمالي صفر")
        } catch (e: Throwable) { Metric(null, "%", "ActivityManager.MemoryInfo", now, MState.UNAVAILABLE, "فشلت القراءة") }

        // التخزين الداخلي — StatFs على /data
        var sTot = 0L; var sFree = 0L
        val sto: Metric<Float> = try {
            val st = StatFs(Environment.getDataDirectory().path)
            sTot = st.blockCountLong * st.blockSizeLong; sFree = st.availableBlocksLong * st.blockSizeLong
            if (sTot > 0) Metric((sTot - sFree).toFloat() / sTot, "%", "StatFs (التخزين الداخلي)", now, MState.LIVE)
            else Metric(null, "%", "StatFs (التخزين الداخلي)", now, MState.UNAVAILABLE, "حجم صفر")
        } catch (e: Throwable) { Metric(null, "%", "StatFs (التخزين الداخلي)", now, MState.UNAVAILABLE, "فشلت القراءة") }

        // البطارية / الحرارة / الفولت — ACTION_BATTERY_CHANGED (sticky)
        var charging = false
        var bat: Metric<Int> = Metric(null, "%", "BatteryManager", now, MState.UNAVAILABLE, "مفيش بيانات بطارية")
        var temp: Metric<Float> = Metric(null, "°م", "BatteryManager", now, MState.UNAVAILABLE, "الجهاز مش بيعرض الحرارة")
        var volt: Metric<Int> = Metric(null, "mV", "BatteryManager", now, MState.UNAVAILABLE, "الجهاز مش بيعرض الفولت")
        try {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scl = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (lvl >= 0 && scl > 0) bat = Metric((lvl * 100f / scl).toInt(), "%", "BatteryManager", now, MState.LIVE)
                val stt = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = stt == BatteryManager.BATTERY_STATUS_CHARGING || stt == BatteryManager.BATTERY_STATUS_FULL
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t != Int.MIN_VALUE && t > 0) temp = Metric(t / 10f, "°م", "BatteryManager (حرارة البطارية)", now, MState.LIVE)
                val v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                if (v > 0) volt = Metric(v, "mV", "BatteryManager", now, MState.LIVE)
            }
        } catch (_: Throwable) {}

        return CareSnap(ram, rTot, rAv, rLow, sto, sTot, sFree, bat, charging, temp, volt, now)
    }
}
