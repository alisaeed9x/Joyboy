package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v345) «بحث شامل» واحد: خانة بحث بتدوّر في فيديوهات الجهاز + الموسيقى + يوتيوب مرة واحدة، والنتايج متقسّمة بعناوين.
 * فيديو/أغنية محلية: دوسة = تشغيل. يوتيوب: دوسة = فيديو، ضغطة طويلة = صوت بس. وآخر الصفحة «🌐 ابحث في المتصفح».
 */
object UniSearch {
    fun show(
        act: Activity, ui: Ui, th: Theme, q0: String,
        videos: (String) -> List<VideoItem>, music: (String) -> List<VideoItem>,
        onVideo: (VideoItem) -> Unit, onMusic: (VideoItem) -> Unit,
        onYtVideo: (NpItem) -> Unit, onYtAudio: (NpItem) -> Unit, onWeb: (String) -> Unit
    ) {
        val h = Handler(Looper.getMainLooper())
        var tok = 0
        lateinit var dlg: AlertDialog
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(12)) }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(box) }
        val et = EditText(act).apply {
            hint = "🔎 دوّر في فيديوهاتك وموسيقاك ويوتيوب"; textSize = 14f; setSingleLine(); setTextColor(th.text); setHintTextColor(th.muted)
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.box(th.card, th.border, 14)
            setText(q0)
        }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(8)); background = ui.box(th.bg, th.border, 18)
            addView(et, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, ui.dp(420)).apply { topMargin = ui.dp(8) })
        }
        fun head(t: String) = ui.text(t, 12.5f, th.muted, true).apply { setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(4)) }
        fun row(title: String, sub: String, onTap: () -> Unit, onLong: (() -> Unit)? = null): View = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9)); background = ui.box(0x14808080, th.border, 12)
            addView(ui.text(title, 13.5f, th.text, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (sub.isNotBlank()) addView(ui.text(sub, 11.5f, th.muted).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
            setOnClickListener { try { dlg.dismiss() } catch (_: Throwable) {}; onTap() }
            if (onLong != null) setOnLongClickListener { try { dlg.dismiss() } catch (_: Throwable) {}; onLong(); true }
        }
        fun add(v: View) = box.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(5) })
        fun webBtn(q: String): View = ui.text("🌐 ابحث في المتصفح عن «" + q.take(40) + "»", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(18), ui.dp(13), ui.dp(18), ui.dp(13)); background = ui.box(0xFF4C6FB5.toInt(), 0xFF4C6FB5.toInt(), 14)
            setOnClickListener { try { dlg.dismiss() } catch (_: Throwable) {}; onWeb(q) }
        }
        fun run(q: String) {
            val my = ++tok
            box.removeAllViews()
            if (q.length < 2) { box.addView(ui.text("اكتب حرفين على الأقل", 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(30), 0, 0) }); return }
            val vs: List<VideoItem> = (try { videos(q) } catch (_: Throwable) { emptyList<VideoItem>() }).take(6)
            val ms: List<VideoItem> = (try { music(q) } catch (_: Throwable) { emptyList<VideoItem>() }).take(6)
            if (vs.isNotEmpty()) { box.addView(head("🎞 فيديوهات على الجهاز (${vs.size})")); for (v in vs) add(row(v.title, VideoLib.fmtSize(v.size), { onVideo(v) })) }
            if (ms.isNotEmpty()) { box.addView(head("🎵 موسيقى على الجهاز (${ms.size})")); for (v in ms) add(row(v.title, "", { onMusic(v) })) }
            box.addView(head("▶ يوتيوب (دوسة = فيديو · ضغطة طويلة = صوت بس)"))
            val yt = ui.text("⏳ بدوّر في يوتيوب…", 13f, th.muted).apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) }
            box.addView(yt)
            val tail = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            tail.addView(webBtn(q), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            box.addView(tail)
            Thread {
                val r: List<NpItem>? = try { YtNp.init(); YtNp.searchFirst(q, "videos").items.filter { it.kind == 0 }.take(8) } catch (_: Throwable) { null }
                act.runOnUiThread {
                    if (my != tok || act.isFinishing || act.isDestroyed) return@runOnUiThread
                    val at = box.indexOfChild(yt)
                    box.removeView(yt)
                    if (r.isNullOrEmpty()) { box.addView(ui.text("مفيش نتايج من يوتيوب (أو مفيش نت)", 13f, th.muted).apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) }, at); return@runOnUiThread }
                    var k = at
                    for (n in r) {
                        val sub = listOf(n.channel).filter { it.isNotBlank() }.joinToString(" · ")
                        box.addView(row(n.title, sub, { onYtVideo(n) }, { onYtAudio(n) }), k++, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(5) })
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        et.addTextChangedListener(object : TextWatcher {
            private val go = Runnable { run(et.text.toString().trim()) }
            override fun afterTextChanged(e: Editable?) { h.removeCallbacks(go); h.postDelayed(go, 600L) }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        dlg = AlertDialog.Builder(act).setView(root).create()
        dlg.setOnDismissListener { tok++; h.removeCallbacksAndMessages(null) }
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.show()
        run(q0.trim())
    }
}
