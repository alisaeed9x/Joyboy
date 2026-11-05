package com.tttt.joyboylauncher

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

class AppAdapter(
    var items: MutableList<Entry>,
    private val onSurface: Boolean,
    private val click: (Entry, View) -> Unit,
    private val longClick: (Entry, View) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(val root: LinearLayout, val img: ImageView, val tv: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val c = p.context
        val ll = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(-1, c.dp(96))
            setPadding(0, c.dp(8), 0, 0)
        }
        val img = ImageView(c).apply { layoutParams = LinearLayout.LayoutParams(c.dp(56), c.dp(56)) }
        val tv = TextView(c).apply {
            textSize = 11f; maxLines = 1; gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(c.dp(4), c.dp(6), c.dp(4), 0)
            if (onSurface) setTextColor(c.themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
            else { setTextColor(Color.WHITE); setShadowLayer(4f, 0f, 1f, 0x99000000.toInt()) }
        }
        ll.addView(img); ll.addView(tv)
        return VH(ll, img, tv)
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val e = items[i]
        h.root.scaleX = 1f; h.root.scaleY = 1f
        if (e === Entry.Placeholder) {
            h.root.alpha = 0f
            h.root.setOnClickListener(null); h.root.setOnLongClickListener(null); h.root.setOnTouchListener(null)
            return
        }
        h.root.alpha = 1f
        h.img.setImageDrawable(e.makeIcon()); h.tv.text = e.title()
        h.root.pressEffect()
        h.root.setOnClickListener { click(e, h.img) }
        h.root.setOnLongClickListener { longClick(e, h.root); true }
    }

    override fun getItemCount() = items.size
}

class PagesAdapter(
    var pages: List<MutableList<Entry>>,
    private val click: (Entry, View) -> Unit,
    private val longClick: (Entry, View) -> Unit
) : RecyclerView.Adapter<PagesAdapter.PH>() {

    class PH(val rv: RecyclerView) : RecyclerView.ViewHolder(rv)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): PH {
        val c = p.context
        val rv = RecyclerView(c).apply {
            layoutManager = object : GridLayoutManager(c, 4) { override fun canScrollVertically() = false }
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            setPadding(c.dp(12), c.dp(16), c.dp(12), 0); clipToPadding = false
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        return PH(rv)
    }

    override fun onBindViewHolder(h: PH, i: Int) {
        h.rv.adapter = AppAdapter(pages[i], false, click, longClick)
    }

    override fun getItemCount() = pages.size
}
