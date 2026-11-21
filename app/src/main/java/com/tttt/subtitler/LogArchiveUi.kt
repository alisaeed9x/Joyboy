package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v1167) شاشة «🗂 سجل اللوجات»: كل تشغيلة (لوج + جمل مترجمة) ليها كارت — صورة الفيديو · الاسم · التاريخ · عدد الجمل.
 * ▶ «شغّل بالترجمة دي» بيفتح الفيديو بجمل اللوج ده بالذات (مش آخر SRT ولا آخر ترجمة محفوظة) ومن غير ما يكتب على ترجمة الفيديو الأصلية.
 */
class LogArchiveUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val main = Handler(Looper.getMainLooper())
    private val amber = 0xFFF59E0B.toInt()
    private val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)
    private val listCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val summary: TextView = ui.text("", 12f, th.muted)
    private var dlg: Dialog? = null
    private var all: List<LogArchive.Meta> = emptyList()
    private var query = ""

    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = ui.dp(top) }

    fun show() {
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; layoutParams = lp(-1, -1) }
        content.addView(summary, lp(-1, -2))
        val search = ui.input("🔍 دوّر باسم الفيديو", "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; render() }
        })
        content.addView(search, lp(-1, -2, 6))
        content.addView(ui.button("🗑 مسح كل السجل") { clearAll() }, lp(-1, -2, 4))
        content.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(listCol) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(6) })
        val d = ui.sheet(act, "🗂 سجل اللوجات", listOf<View>(content), fixedH = true)
        dlg = d
        reload()
        d.show()
    }

    private fun reload() {
        Thread {
            val l = try { LogArchive.list(act) } catch (e: Throwable) { LogStore.err("LogArchiveUi:list", e); emptyList() }
            act.runOnUiThread { all = l; render() }
        }.apply { isDaemon = true }.start()
    }

    private fun render() {
        listCol.removeAllViews()
        val q = query
        val shown = if (q.length >= 1) all.filter { it.title.contains(q, true) } else all
        summary.text = if (all.isEmpty()) "لسه مفيش لوجات — أول ما تفتح فيديو وتترجمه هتتسجّل هنا لوحدها."
        else "${all.size} لوج محفوظ" + (if (shown.size != all.size) " · ظاهر ${shown.size}" else "") + " — كل لوج نسخة مستقلة بترجماته"
        for (m in shown) listCol.addView(card(m), lp(-1, -2, 8))
    }

    private fun card(m: LogArchive.Meta): View {
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.box(th.card, th.border, 16)
        }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.box(th.surface, th.border, 10); clipToOutline = true }
        top.addView(iv, LinearLayout.LayoutParams(ui.dp(112), ui.dp(64)))
        loadThumb(m, iv)
        val info = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        info.addView(ui.text(m.title.ifBlank { "فيديو" }, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        info.addView(ui.text("🕒 " + fmt.format(Date(m.ts)), 11.5f, th.muted), lp(-2, -2, 3))
        if (m.noTranslation) info.addView(ui.text("⚠ ما طلعش مع ترجمة", 12.5f, amber, true), lp(-2, -2, 3))
        else info.addView(ui.text("💬 ${m.translated} جملة مترجمة من ${m.subs}", 12f, th.primary, true), lp(-2, -2, 3))
        top.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
        c.addView(top, lp(-1, -2))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun add(v: View, w: Float) = row.addView(v, LinearLayout.LayoutParams(0, -2, w).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
        add(ui.button(if (m.noTranslation) "▶ شغّل (من غير ترجمة)" else "▶ شغّل بالترجمة دي", true) { play(m) }, 2f)
        add(ui.button("📜 اللوج") { showLog(m) }, 1f)
        add(ui.button("🗑") { del(m) }, 0.6f)
        c.addView(row, lp(-1, -2, 10))
        return c
    }

    /** الصورة: يوتيوب ← صورته · غير كده ← الصورة المحفوظة أو لقطة من الفيديو نفسه (لو لسه موجود) */
    private fun loadThumb(m: LogArchive.Meta, iv: ImageView) {
        try {
            val r = Recent(m.vid, m.title, m.url, m.uri, 0.0, 0.0, 0, 0.0, m.ts)
            if (Recents.ytIdOf(r) != null) LinkThumb.into(act, r, iv)
            else LinkThumb.forRecent(act, r) { b -> iv.setImageBitmap(b) }
        } catch (e: Throwable) { LogStore.err("LogArchiveUi:thumb", e) }
    }

    // ===================== تشغيل الفيديو بترجمات اللوج ده =====================
    private fun play(m: LogArchive.Meta) {
        try {
            LogArchive.setPending(m.vid, m.id)
            when {
                m.uri.isNotBlank() -> {
                    dlg?.dismiss()
                    act.startActivity(Intent(act, PlayerActivity::class.java).pipTask(act).apply {
                        data = Uri.parse(m.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra("landscape", true); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
                        putExtra("logarc", m.id)
                    })
                }
                m.url.startsWith("http", true) -> { dlg?.dismiss(); Links.open(act, m.url, Links.VIDEO, m.title, "") }
                else -> Notice.show(act, "مفيش مصدر محفوظ للفيديو ده — افتحه من المشغّل مرة", 2800L)
            }
        } catch (e: Throwable) { LogStore.err("LogArchiveUi:play", e); Notice.show(act, "ما قدرتش أفتح الفيديو", 2400L) }
    }

    // ===================== نص اللوج =====================
    private fun showLog(m: LogArchive.Meta) {
        Thread {
            val e = try { LogArchive.read(act, m.id) } catch (_: Throwable) { null }
            act.runOnUiThread {
                if (e == null) { Notice.show(act, "ما لقيتش اللوج ده", 2200L); return@runOnUiThread }
                val head = StringBuilder()
                head.append(m.title).append('\n').append(fmt.format(Date(m.ts))).append('\n')
                head.append(if (m.noTranslation) "⚠ ما طلعش مع ترجمة" else "💬 ${m.translated} جملة مترجمة من ${m.subs}").append("\n\n")
                val body = if (e.log.isBlank()) "(مفيش أسطر لوج)" else e.log
                val tv = ui.text(head.toString() + body, 11f, th.text).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR }
                val sc = ScrollView(act).apply { addView(tv) }
                val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(-1, -1) }
                col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                row.addView(ui.button("📋 نسخ اللوج") {
                    try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", head.toString() + body)); Notice.show(act, "📋 اتنسخ", 1600L) } catch (_: Throwable) {}
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
                row.addView(ui.button("▶ شغّل بالترجمة دي", true) { play(m) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
                col.addView(row, lp(-1, -2, 6))
                ui.sheet(act, "📜 لوج — " + m.title.take(40), listOf<View>(col), fixedH = true).show()
            }
        }.apply { isDaemon = true }.start()
    }

    // ===================== المسح =====================
    private fun del(m: LogArchive.Meta) {
        GAlert(act).setTitle("🗑 حذف اللوج ده؟")
            .setMessage("هيتمسح اللوج والترجمات المحفوظة معاه بس. الفيديو وترجمته الأصلية ما بيتأثروش.")
            .setPositiveButton("حذف") { _, _ -> Thread { LogArchive.delete(act, m.id); act.runOnUiThread { reload() } }.apply { isDaemon = true }.start() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun clearAll() {
        if (all.isEmpty()) return
        GAlert(act).setTitle("🗑 مسح كل السجل؟")
            .setMessage("هتتمسح كل اللوجات والترجمات المحفوظة معاها (${all.size} لوج). الفيديوهات وترجماتها الأصلية ما بتتأثرش.")
            .setPositiveButton("مسح الكل") { _, _ -> Thread { LogArchive.deleteAll(act); act.runOnUiThread { reload() } }.apply { isDaemon = true }.start() }
            .setNegativeButton("إلغاء", null).show()
    }
}
