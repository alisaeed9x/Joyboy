package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v1055) شاشة منبثقة «كمّل من حيث وقفت ولا ابدأ من جديد؟» — بتظهر على الجهاز اللي بتختار منه الفيديو.
 * لو عدّت [delaySec] ثواني (٥) من غير اختيار ← بيبدأ من جديد. شغّالة بالريموت (D-pad) واللمس.
 */
/**
 * (v1056) «ابدأ من جديد» اللي اختارها الموبايل: التلفزيون ما يكمّلش من تقدّمه المحفوظ لنفس الفيديو.
 * الموبايل بيبعت startms = -1 ← TvLink بيسلّح العلم ده ← أول مشغّل (Player.kt أو MeeTube) يفتح الفيديو بياخده مرة واحدة ويبدأ من الصفر.
 */
object StartFresh {
    @Volatile private var until = 0L
    fun arm() { until = System.currentTimeMillis() + 20_000L }
    fun consume(): Boolean { val ok = System.currentTimeMillis() < until; until = 0L; return ok }
}

object ResumeAsk {
    private val h = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null
    private var tick: Runnable? = null

    /** (v1105 Y5) نفس هوية مشغّل Joyboy للملفات: f:الاسم:الحجم (اتنقلت من MtTranslate.localVid) */
    private fun localVid(a: Activity, u: String): String {
        try {
            val uri = android.net.Uri.parse(u)
            if (uri.scheme == "content") a.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) return "f:" + c.getString(0) + ":" + c.getLong(1) }
            if (uri.scheme == "file") { val f = java.io.File(uri.path ?: ""); if (f.isFile) return "f:" + f.name + ":" + f.length() }
        } catch (_: Throwable) {}
        return "f:$u"
    }

    /** مكان وقوف صالح للفيديو المحلي (بين ٥ث وقبل النهاية بـ٤ث)، أو 0 لو مفيش — للموبايل قبل ما يبعت الفيديو للتلفزيون */
    fun savedMs(a: Activity, lu: String): Long {
        try {
            if (lu.isBlank()) return 0L
            val id = localVid(a, lu)   // (v1105 Y5) كانت MtTranslate.joyVid
            var pos = 0L
            val s = Cfg.p.getString("lastpos", "") ?: ""
            val i = s.indexOf('\t')
            if (i > 0 && s.substring(0, i) == id) pos = s.substring(i + 1).toLongOrNull() ?: 0L
            var durMs = 0L
            val r = try { Recents.parse(java.io.File(a.filesDir, "recent.json").readText()).firstOrNull { it.id == id } } catch (_: Throwable) { null }
            if (r != null) { durMs = (r.durSec * 1000.0).toLong(); if (pos <= 5000L) pos = (r.posSec * 1000.0).toLong() }
            return if (pos > 5000L && (durMs <= 0L || pos < durMs - 4000L)) pos else 0L
        } catch (_: Throwable) { return 0L }
    }

    fun dismiss() {
        try { tick?.let { h.removeCallbacks(it) } } catch (_: Throwable) {}
        tick = null
        try { dlg?.setOnDismissListener(null); dlg?.dismiss() } catch (_: Throwable) {}
        dlg = null
    }

    /** onChoice(true) = كمّل من posMs · onChoice(false) = ابدأ من جديد (الافتراضي بعد المهلة) */
    fun show(a: Activity, posMs: Long, delaySec: Int = 5, onChoice: (Boolean) -> Unit) {
        dismiss()
        var done = false
        var left = delaySec
        fun finish(resume: Boolean) {
            if (done) return
            done = true
            dismiss()
            try { onChoice(resume) } catch (e: Throwable) { LogStore.err("ResumeAsk:cb", e) }
        }
        try {
            val dd = a.resources.displayMetrics.density
            val big = DeviceUi.isTv(a)
            val th = Themes.byId(Cfg.str("theme", "mx"))
            fun px(v: Int) = (v * dd).toInt()
            fun btn(label: String, onClick: () -> Unit): TextView = TextView(a).apply {
                text = label; textSize = if (big) 19f else 16f; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(px(18), px(if (big) 14 else 11), px(18), px(if (big) 14 else 11))
                fun paint(f: Boolean) {
                    background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(if (f) th.primary else th.card); setStroke(px(2), th.primary) }
                    setTextColor(if (f) Color.WHITE else th.text)
                }
                paint(false)
                setOnFocusChangeListener { _, f -> paint(f) }
                setOnClickListener { onClick() }
            }
            val bResume = btn("▶ كمّل من " + PlayerLogic.clock(posMs)) { finish(true) }
            val bFresh = btn("⏮ ابدأ من جديد  ($left)") { finish(false) }
            val root = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(px(22), px(18), px(22), px(20))
                background = GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(th.card); setStroke(px(1), th.primary) }
                addView(TextView(a).apply { text = "كمّل من حيث وقفت؟"; textSize = if (big) 22f else 18f; setTextColor(th.text); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
                addView(bResume, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
                addView(bFresh, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            }
            val d = Dialog(a)
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            d.setContentView(root)
            d.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(px(if (big) 460 else 300), -2)
                setGravity(Gravity.CENTER)
                setDimAmount(0.55f)
            }
            d.setCancelable(true)
            d.setOnCancelListener { finish(false) }
            dlg = d
            d.show()
            bResume.requestFocus()
            val r = object : Runnable {
                override fun run() {
                    if (done) return
                    left--
                    if (left <= 0) { finish(false); return }
                    bFresh.text = "⏮ ابدأ من جديد  ($left)"
                    h.postDelayed(this, 1000L)
                }
            }
            tick = r
            h.postDelayed(r, 1000L)
        } catch (e: Throwable) {
            LogStore.err("ResumeAsk:show", e)
            finish(false)
        }
    }
}
