package com.ali.ceilingir

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var ir: ConsumerIrManager
    private lateinit var status: TextView
    private lateinit var searchBtn: TextView
    private enum class Phase { IDLE, BATCH, ASK, PICK, STEP, READY }
    private var phase = Phase.IDLE
    private lateinit var actions: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val addrs = intArrayOf(0, 128, 1, 2, 4, 8, 16, 32, 64, 247, 239, 255)
    private val total = addrs.size * 256
    private val BATCH_SIZE = 10
    private var pos = 0              // الموضع الحالي في كل الأكواد
    private var batchStart = 0
    private val batch = ArrayList<Pair<Int, Int>>()
    private var stepIdx = 0
    private var pickedKey = ""
    private var pickedName = ""
    private val circles = HashMap<String, TextView>()
    private val prefs by lazy { getSharedPreferences("codes", MODE_PRIVATE) }
    private val roles = listOf(
        Triple("power", "⏻", "تشغيل/إطفاء"),
        Triple("up", "🔆", "أقوى"),
        Triple("down", "🔅", "أهدى"),
        Triple("white", "❄️", "أبيض"),
        Triple("neutral", "☀️", "محايد"),
        Triple("warm", "🔥", "دافي"),
        Triple("dim", "🌙", "خافت")
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun nec(a: Int, c: Int) {
        val p = ArrayList<Int>()
        p.add(9000); p.add(4500)
        for (b in intArrayOf(a, a.inv() and 255, c, c.inv() and 255))
            for (i in 0..7) { p.add(560); p.add(if (((b shr i) and 1) == 1) 1690 else 560) }
        p.add(560)
        ir.transmit(38000, p.toIntArray())
    }

    private fun pill(text: String, fill: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(fill) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun setActions(vararg v: View) { actions.removeAllViews(); for (x in v) actions.addView(x) }

    private fun stopAll() { h.removeCallbacksAndMessages(null) }

    private fun toIdle() {
        stopAll(); phase = Phase.IDLE; actions.removeAllViews(); status.text = "جاهز"
        searchBtn.text = "🔍"
    }

    // ---------- دفعة بحث: 10 أكواد وتقف ----------
    private fun startBatch() {
        stopAll()
        if (pos >= total) {
            phase = Phase.IDLE; status.text = "خلصت كل الأكواد ومفيش حاجة. دوس 🔍 تبدأ من الأول"
            pos = 0; setActions(pill("✔ خلصت", 0xFF2E7D32.toInt()) { toIdle() }); return
        }
        phase = Phase.BATCH; batch.clear(); batchStart = pos
        searchBtn.text = "⏸"
        setActions(pill("⏸ وقّف", 0xFF555560.toInt()) { toIdle() })
        h.post(object : Runnable {
            override fun run() {
                if (phase != Phase.BATCH) return
                if (batch.size >= BATCH_SIZE || pos >= total) { askChanged(); return }
                val a = addrs[pos / 256]; val c = pos % 256
                batch.add(Pair(a, c)); pos++
                nec(a, c)
                status.text = "🔍 بجرّب ${batch.size}/$BATCH_SIZE — (عنوان ${a}) كود $c\nإجمالي: $pos من $total"
                h.postDelayed(this, 700)
            }
        })
    }

    private fun askChanged() {
        phase = Phase.ASK; searchBtn.text = "🔍"
        status.text = "اتبعت ${batch.size} أكواد ($batchStart → ${batchStart + batch.size - 1}).\nاللمبة اتغيرت؟"
        setActions(
            pill("✅ أيوه اتغيرت", 0xFF2E7D32.toInt()) { askWhat() },
            pill("➡ لأ، العشرة اللي بعدها", 0xFFC9A24A.toInt()) { startBatch() },
            pill("🔁 أعيد نفس العشرة", 0xFF2A2A33.toInt()) { pos = batchStart; startBatch() },
            pill("↩ العشرة اللي فاتت", 0xFF2A2A33.toInt()) { pos = (batchStart - BATCH_SIZE).coerceAtLeast(0); startBatch() },
            pill("✔ خلصت", 0xFF555560.toInt()) { toIdle() }
        )
    }

    private fun askWhat() {
        phase = Phase.PICK
        status.text = "اللمبة عملت إيه؟ دوس على الزر اللي يطابق اللي حصل (تشغيل، أقوى، أبيض…)"
        setActions(pill("↩ رجوع", 0xFF555560.toInt()) { askChanged() })
    }

    // ---------- تجربة العشرة واحد واحد ----------
    private fun startStep(key: String, name: String) {
        pickedKey = key; pickedName = name; stepIdx = 0; phase = Phase.STEP
        sendStep()
    }

    private fun sendStep() {
        val (a, c) = batch[stepIdx]
        nec(a, c)
        status.text = "🎯 $pickedName — كود ${stepIdx + 1}/${batch.size} (عنوان $a / كود $c)\nاللمبة استجابت؟"
        setActions(
            pill("✔ ده هو — احفظه", 0xFF2E7D32.toInt()) { saveStep() },
            pill("⏭ اللي بعده", 0xFFC9A24A.toInt()) { if (stepIdx < batch.size - 1) { stepIdx++; sendStep() } else status.text = "ده آخر كود في العشرة" },
            pill("⏮ اللي قبله", 0xFF2A2A33.toInt()) { if (stepIdx > 0) { stepIdx--; sendStep() } },
            pill("🔁 ابعت تاني", 0xFF2A2A33.toInt()) { sendStep() },
            pill("↩ مش لاقيه — رجوع", 0xFF555560.toInt()) { askChanged() }
        )
    }

    private fun saveStep() {
        val (a, c) = batch[stepIdx]
        prefs.edit().putString(pickedKey, "$a:$c").apply()
        mark(pickedKey)
        phase = Phase.READY
        status.text = "✅ اتسجل: $pickedName\nدوس ▶ تدوّر على الزر اللي بعده"
        setActions(
            pill("▶ ابحث عن الزر اللي بعده", 0xFFC9A24A.toInt()) { startBatch() },
            pill("✔ خلصت", 0xFF2E7D32.toInt()) { toIdle() }
        )
    }

    private fun circle(sym: String, label: String, size: Int, fill: Int, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val t = TextView(this).apply {
            text = sym; textSize = size * 0.34f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(3), Color.TRANSPARENT) }
            setOnClickListener { onClick() }
        }
        val l = TextView(this).apply { text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFAAAAAA.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(t); addView(l)
        }
        return Pair(box, t)
    }

    private fun mark(key: String) {
        (circles[key]?.background as? GradientDrawable)?.setStroke(dp(3), 0xFF4CAF50.toInt())
    }

    private fun roleTap(key: String, name: String) {
        if (phase == Phase.PICK) { startStep(key, name); return }
        if (phase == Phase.BATCH || phase == Phase.STEP) return
        val v = prefs.getString(key, null)
        if (v == null) { Toast.makeText(this, "الزرار ده لسه ما اتعلّمش — دوس 🔍", Toast.LENGTH_SHORT).show(); return }
        val (a, c) = v.split(":").map { it.toInt() }
        nec(a, c); status.text = name
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        for (x in v) addView(x)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF141418.toInt()); setPadding(dp(16), dp(40), dp(16), dp(40))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(16)); text = "جاهز" }
        root.addView(status)

        if (!ir.hasIrEmitter()) { status.text = "الموبايل ده مفيهوش IR"; setContentView(root); return }

        val btn = HashMap<String, View>()
        for ((k, sym, name) in roles) {
            val big = k == "power"
            val (box, t) = circle(sym, name, if (big) 104 else 80, if (big) 0xFFB3261E.toInt() else 0xFF2A2A33.toInt()) { roleTap(k, name) }
            circles[k] = t; btn[k] = box
        }
        root.addView(row(btn["power"]!!))
        root.addView(row(btn["up"]!!, btn["down"]!!))
        root.addView(row(btn["white"]!!, btn["neutral"]!!, btn["warm"]!!))
        root.addView(row(btn["dim"]!!))

        val (sBox, sT) = circle("🔍", "بحث", 72, 0xFFC9A24A.toInt()) {
            if (phase == Phase.IDLE) {
                status.text = "قرّب الموبايل من الكلوب وراقب اللمبة…"
                startBatch()
            } else toIdle()
        }
        searchBtn = sT
        root.addView(LinearLayout(this).apply { setPadding(0, dp(24), 0, 0); gravity = Gravity.CENTER; addView(sBox) })
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
        root.addView(actions)

        for ((k, _, _) in roles) if (prefs.getString(k, null) != null) mark(k)
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF141418.toInt()); addView(root) })
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
}
