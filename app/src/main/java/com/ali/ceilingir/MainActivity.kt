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
    private var extraViews: List<View> = emptyList()
    private fun setExtras(v: Int) { for (x in extraViews) x.visibility = v }
    private val h = Handler(Looper.getMainLooper())
    private val addrs = intArrayOf(0, 128, 1, 2, 4, 8, 16, 32, 64, 247, 239, 255)
    private var ai = 0
    private var cmd = 0
    private var lastA = -1
    private var lastC = -1
    private var scanning = false
    private var inSearch = false
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

    private fun sendScan(a: Int, c: Int) {
        lastA = a; lastC = c
        nec(a, c)
        status.text = "🔍 بجرّب… (${ai + 1}/${addrs.size}) كود $c"
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!scanning) return
            sendScan(addrs[ai], cmd)
            cmd++
            if (cmd > 255) { cmd = 0; ai = (ai + 1) % addrs.size }
            h.postDelayed(this, 700)
        }
    }

    private fun pause() { scanning = false; h.removeCallbacks(tick); searchBtn.text = "🔍" }

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
        if (inSearch && lastC >= 0) {
            pause()
            prefs.edit().putString(key, "$lastA:$lastC").apply()
            mark(key)
            status.text = "✅ اتسجل: $name — دوس 🔍 تكمل أو ✔ لو خلصت"
            return
        }
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
            inSearch = true; setExtras(View.VISIBLE)
            if (scanning) pause() else {
                scanning = true; searchBtn.text = "⏸"
                status.text = "قرّب الموبايل من الكلوب… ولما يستجيب دوس على الرمز اللي عمله"
                h.post(tick)
            }
        }
        searchBtn = sT
        val (bBox, _) = circle("↩", "رجّع", 56, 0xFF2A2A33.toInt()) {
            pause(); cmd = if (lastC <= 0) 0 else lastC - 1; sendScan(lastA.coerceAtLeast(0), cmd); cmd++
        }
        val (aBox, _) = circle("🔁", "تاني", 56, 0xFF2A2A33.toInt()) { pause(); if (lastC >= 0) sendScan(lastA, lastC) }
        val (dBox, _) = circle("✔", "خلصت", 56, 0xFF2E7D32.toInt()) {
            pause(); inSearch = false; setExtras(View.GONE); status.text = "جاهز"
        }
        extraViews = listOf(bBox, aBox, dBox)
        setExtras(View.GONE)
        root.addView(LinearLayout(this).apply {
            setPadding(0, dp(24), 0, 0); gravity = Gravity.CENTER
            addView(bBox); addView(sBox); addView(aBox); addView(dBox)
        })

        for ((k, _, _) in roles) if (prefs.getString(k, null) != null) mark(k)
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF141418.toInt()); addView(root) })
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
}
