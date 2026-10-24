package com.tttt.subtitler

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * (v472) زرارين دايريين فوق كل صورة فيديو في يوتيوب (كارت · قناة · مقترحات · قوايم):
 * 🎧 صوت بس (مشغّل الموسيقى) و ⬇ تحميل (بيفتح اختيار الجودة وبعدها مهمة في «المهام»).
 * لمس أي زرار فيهم بيسخّن رابط الفيديو (YtFast.warm) فالنتيجة تبقى جاهزة لما ترفع صباعك.
 */
object YtThumbBtns {
    class Bar(val row: LinearLayout, val aud: View, val dl: View)

    fun attach(ctx: Context, ui: Ui, box: FrameLayout, sizeDp: Int = 36): Bar {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fun btn(icon: String, desc: String): View = IconGlyphButton(ctx, icon).apply {
            contentDescription = desc
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB8000000.toInt()) }
        }
        val aud = btn("headphones", "صوت بس"); val dl = btn("download", "تحميل")
        val tvUi = try { DeviceUi.isRemote(ctx) } catch (_: Throwable) { false }   // (v521) تلفزيون/تابلت بالريموت: أزرار اللمس دي بتظهر ورا حلقة التركيز — اتشالت (Menu = قايمة الفيديو)
        row.addView(aud, LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp)))
        row.addView(dl, LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp)).apply { marginStart = ui.dp(6) })
        box.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        if (tvUi) { row.visibility = View.GONE; aud.isFocusable = false; dl.isFocusable = false }
        return Bar(row, aud, dl)
    }

    /** ربط الأزرار بفيديو معيّن (بيتنده كل مرة الصف بيتعمله bind) */
    fun bind(aud: View, dl: View, id: String, onAudio: () -> Unit, onDl: () -> Unit) {
        aud.setOnClickListener { onAudio() }
        dl.setOnClickListener { onDl() }
        val warm = View.OnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) YtFast.warm(id); false }
        aud.setOnTouchListener(warm); dl.setOnTouchListener(warm)
    }
}

/** (v472) تحميل يوتيوب بجودة مختارة = مهمة في «المهام» (دايرة التقدم العايمة + العدّاد الإجمالي + فتح الفيديو بدوسة بعد ما يخلص) */
object YtDlTask {
    fun start(app: Context, n: NpItem, url: String, audio: String?, ua: String, audioOnly: Boolean, h: Int) {
        val nm = n.title.ifBlank { n.id }
        TaskCenter.add(app.applicationContext, "download", (if (audioOnly) "🎧 " else "⬇ ") + nm) { t ->
            t.msg = "بجهّز…"; TaskCenter.changed()
            val hs = mapOf("User-Agent" to ua, "Referer" to "https://www.youtube.com/")
            val job = if (audioOnly) Downloader.Job(nm, url, null, false, 1, hs, true)
                      else Downloader.Job(nm, url, audio?.takeIf { it.isNotBlank() }, url.contains(".m3u8", true), h, hs)
            job.ctl.cancelled = false; job.ctl.paused = false
            job.ctl.ext = { t.cancelled }
            t.onCancel = { job.ctl.cancelled = true }
            t.onPause = { pz -> job.ctl.paused = pz }
            val res = Downloader.run(app.applicationContext, job) { what, pct -> t.pct = pct.coerceIn(0, 99); t.msg = what; TaskCenter.changed() }
            if (!res.first) throw java.io.IOException(res.second)
            t.msg = ""; t.outPath = res.second
            Downloader.takeLastOut()?.let { t.outUri = it.first; t.mime = it.second }
            try { YtStore.addFront(YtStore.DL, NpItem(n.id, n.title, n.channel, n.channelUrl, n.thumb, n.dur, -1L, "⬇ " + (if (audioOnly) "صوت" else "فيديو") + " · " + res.second, 0, res.second), 300) } catch (_: Throwable) {}
        }
    }
}
