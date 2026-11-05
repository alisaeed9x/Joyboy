package com.tttt.joyboylauncher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream

/** MRU list of foreground apps (fed by GestureService) + saved thumbnails. */
object RecentsStore {
    @Volatile var openRequested = false
    private const val KEY = "recents"
    private const val MAX = 12

    fun list(p: SharedPreferences): MutableList<String> =
        (p.getString(KEY, "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()

    private fun put(p: SharedPreferences, l: List<String>) {
        p.edit().putString(KEY, l.joinToString(",")).apply()
    }

    fun touch(p: SharedPreferences, pkg: String) {
        val l = list(p)
        if (l.firstOrNull() == pkg) return
        l.remove(pkg); l.add(0, pkg)
        while (l.size > MAX) l.removeAt(l.size - 1)
        put(p, l)
    }

    fun thumbFile(ctx: Context, pkg: String): File =
        File(File(ctx.cacheDir, "thumbs").apply { mkdirs() }, "$pkg.jpg")

    fun saveThumb(ctx: Context, pkg: String, bmp: Bitmap) {
        val w = 400
        val h = (bmp.height * w / bmp.width.toFloat()).toInt()
        val s = Bitmap.createScaledBitmap(bmp, w, h, true)
        FileOutputStream(thumbFile(ctx, pkg)).use { s.compress(Bitmap.CompressFormat.JPEG, 72, it) }
    }

    fun remove(p: SharedPreferences, ctx: Context, pkg: String) {
        val l = list(p); l.remove(pkg); put(p, l)
        thumbFile(ctx, pkg).delete()
    }

    fun clear(p: SharedPreferences, ctx: Context) {
        put(p, emptyList())
        File(ctx.cacheDir, "thumbs").listFiles()?.forEach { it.delete() }
    }
}

class RecentCard(val item: AppItem, val thumb: Bitmap?)

class RecentsAdapter(
    val items: MutableList<RecentCard>,
    private val w: Int,
    private val h: Int,
    private val click: (RecentCard, View) -> Unit
) : RecyclerView.Adapter<RecentsAdapter.VH>() {

    class VH(
        val root: LinearLayout, val icon: ImageView, val title: TextView,
        val frame: FrameLayout, val thumb: ImageView, val big: ImageView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val c = p.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(w + c.dp(16), h)
            setPadding(c.dp(8), 0, c.dp(8), 0)
        }
        val head = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(4), 0, c.dp(4), c.dp(8))
        }
        val icon = ImageView(c)
        val title = TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 14f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(c.dp(8), 0, 0, 0)
        }
        head.addView(icon, LinearLayout.LayoutParams(c.dp(28), c.dp(28)))
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val r = c.dp(24).toFloat()
        val frame = FrameLayout(c).apply {
            background = GradientDrawable().apply {
                cornerRadius = r
                setColor(c.themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) }
            }
        }
        val thumb = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val big = ImageView(c)
        frame.addView(thumb, -1, -1)
        frame.addView(big, FrameLayout.LayoutParams(c.dp(72), c.dp(72), Gravity.CENTER))
        root.addView(head, LinearLayout.LayoutParams(-1, c.dp(40)))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        return VH(root, icon, title, frame, thumb, big)
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val card = items[i]
        h.icon.setImageDrawable(card.item.copyIcon()); h.title.text = card.item.label
        if (card.thumb != null) {
            h.thumb.setImageBitmap(card.thumb); h.thumb.visibility = View.VISIBLE; h.big.visibility = View.GONE
        } else {
            h.thumb.visibility = View.GONE; h.big.visibility = View.VISIBLE
            h.big.setImageDrawable(card.item.copyIcon())
        }
        h.root.pressEffect()
        h.frame.setOnClickListener { click(card, h.frame) }
    }

    override fun getItemCount() = items.size
}
