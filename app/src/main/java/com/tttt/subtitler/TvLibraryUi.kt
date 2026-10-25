package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * (v543 م3) «📚 مكتبة التلفزيون»: فولدرات الفيديو اللي على التلفزيون ← الفيديوهات (صور مصغّرة) ← دوسة = يشتغل على التلفزيون.
 * شاشة منفصلة (Dialog) — شاشة المكتبة العادية (LibraryUi) ما اتلمستش. القراءة كلها من RemoteLibrary (شبكة على خيوط خلفية).
 */
object TvLibraryUi {
    fun show(act: Activity) {
        val th = Themes.byId(Cfg.str("theme", "mx"))
        val ui = Ui(act, th)
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar)

        var inFolder: RFolder? = null
        var items: List<Any> = emptyList()

        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(28), ui.dp(10), ui.dp(6)) }
        val title = ui.text("📚 مكتبة التلفزيون", 18f, th.text, true)
        val status = ui.text("", 14f, th.muted).apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)) }
        val list = ListView(act).apply { divider = null; setBackgroundColor(Color.TRANSPARENT) }

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int): Any = items[i]
            override fun getItemId(i: Int): Long = i.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(i: Int) = if (items[i] is RFolder) 0 else 1

            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val it0 = items[i]
                if (it0 is RFolder) {
                    val row = (cv as? LinearLayout) ?: LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                        tag = arrayOf<TextView>(ui.text("", 16f, th.text, true), ui.text("", 12f, th.muted))
                        for (t in tag as Array<*>) addView(t as TextView)
                    }
                    @Suppress("UNCHECKED_CAST") val tv = row.tag as Array<TextView>
                    tv[0].text = "📁 " + it0.name
                    tv[1].text = it0.count.toString() + " فيديو" + (LibraryCore.fmtSize(it0.size).let { s -> if (s.isEmpty()) "" else " · $s" })
                    return row
                }
                val v = it0 as RVideo
                val holder: Array<View> = if (cv != null) (cv.tag as Array<View>) else {
                    val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22808080) }
                    val t1 = ui.text("", 15f, th.text, true).apply { maxLines = 2 }
                    val t2 = ui.text("", 12f, th.muted)
                    val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(t1); addView(t2) }
                    val r = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
                        addView(iv, LinearLayout.LayoutParams(ui.dp(112), ui.dp(63)))
                        addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
                    }
                    arrayOf<View>(r, iv, t1, t2).also { r.tag = it }
                }
                val row = holder[0]; val iv = holder[1] as ImageView
                (holder[2] as TextView).text = v.title
                (holder[3] as TextView).text = listOf(LibraryCore.fmtDur(v.durMs), LibraryCore.fmtSize(v.size)).filter { it.isNotEmpty() }.joinToString(" · ")
                iv.tag = v.id
                iv.setImageDrawable(null)
                val hit = RemoteLibrary.peekThumb(v)
                if (hit != null) iv.setImageBitmap(hit)
                else RemoteLibrary.thumb(act, v) { b -> if (b != null && iv.tag == v.id) iv.setImageBitmap(b) }
                return row
            }
        }
        list.adapter = adapter

        fun setItems(l: List<Any>) { items = l; adapter.notifyDataSetChanged(); list.setSelection(0) }

        fun loadFolders(refresh: Boolean) {
            inFolder = null
            title.text = "📚 مكتبة التلفزيون"
            val cached = if (refresh) null else RemoteLibrary.cachedFolders()
            if (cached != null) { status.text = ""; setItems(cached); return }
            status.text = "⏳ بجيب الفولدرات من التلفزيون…"
            setItems(emptyList())
            RemoteLibrary.folders(act, refresh) { l, err ->
                if (act.isFinishing || act.isDestroyed || inFolder != null) return@folders
                if (l == null) status.text = "⚠ " + (err ?: "فشل")
                else { status.text = if (l.isEmpty()) "مفيش فيديوهات على التلفزيون" else ""; setItems(l) }
            }
        }

        fun loadVideos(f: RFolder) {
            inFolder = f
            title.text = "📁 " + f.name
            val cached = RemoteLibrary.cachedVideos(f.key)
            if (cached != null) { status.text = ""; setItems(cached); return }
            status.text = "⏳ بجيب الفيديوهات…"
            setItems(emptyList())
            RemoteLibrary.videos(act, f.key) { l, err ->
                if (act.isFinishing || act.isDestroyed || inFolder?.key != f.key) return@videos
                if (l == null) status.text = "⚠ " + (err ?: "فشل")
                else { status.text = if (l.isEmpty()) "الفولدر فاضي" else ""; setItems(l) }
            }
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val x = items.getOrNull(pos)
            if (x is RFolder) loadVideos(x)
            else if (x is RVideo) {
                dlg.dismiss()
                PlayTarget.force(act, MediaSpec(uri = LibraryCore.tvUri(x.id), title = x.title))
            }
        }

        val back = ui.button("↩") { if (inFolder != null) loadFolders(false) else dlg.dismiss() }
        val refresh = ui.button("🔄") { RemoteLibrary.clear(); loadFolders(true) }
        head.addView(back, LinearLayout.LayoutParams(ui.dp(56), ui.dp(44)))
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
        head.addView(refresh, LinearLayout.LayoutParams(ui.dp(56), ui.dp(44)))
        root.addView(head)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(root)
        dlg.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && inFolder != null) { loadFolders(false); true } else false
        }
        dlg.show()
        loadFolders(false)
    }
}
