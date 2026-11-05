package com.tttt.joyboylauncher

import android.content.ComponentName
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

const val PAGE_CAP = 20

sealed class Entry {
    data class App(val item: AppItem) : Entry()
    class Folder(var name: String, val items: MutableList<AppItem>) : Entry()
    object Placeholder : Entry()
}

fun AppItem.copyIcon(): Drawable = icon.constantState?.newDrawable()?.mutate() ?: icon

fun Entry.makeIcon(): Drawable = when (this) {
    is Entry.App -> item.copyIcon()
    is Entry.Folder -> FolderDrawable(items)
    Entry.Placeholder -> ColorDrawable(0)
}

fun Entry.title(): String = when (this) {
    is Entry.App -> item.label
    is Entry.Folder -> name
    Entry.Placeholder -> ""
}

/** Folder icon: translucent rounded tile with up to 9 mini icons (3x3). */
class FolderDrawable(items: List<AppItem>) : Drawable() {
    private val icons = items.take(9).map { it.copyIcon() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        c.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), w * .28f, w * .28f, bg)
        val pad = w * .12f
        val cell = (w - pad * 2) / 3f
        val ic = cell * .82f
        val gap = (cell - ic) / 2f
        icons.forEachIndexed { i, d ->
            val l = b.left + pad + (i % 3) * cell + gap
            val t = b.top + pad + (i / 3) * cell + gap
            d.setBounds(l.toInt(), t.toInt(), (l + ic).toInt(), (t + ic).toInt())
            d.draw(c)
        }
    }

    override fun setAlpha(a: Int) { bg.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Persists workspace pages/folders as JSON in SharedPreferences. */
object LayoutStore {
    fun save(p: SharedPreferences, pages: List<List<Entry>>) {
        val root = JSONArray()
        pages.forEach { pg ->
            val a = JSONArray()
            pg.forEach { e ->
                when (e) {
                    is Entry.App -> a.put(e.item.cn.flattenToString())
                    is Entry.Folder -> a.put(
                        JSONObject().put("n", e.name)
                            .put("i", JSONArray(e.items.map { it.cn.flattenToString() }))
                    )
                    Entry.Placeholder -> {}
                }
            }
            root.put(a)
        }
        p.edit().putString("layout", root.toString()).apply()
    }

    fun restore(p: SharedPreferences, apps: List<AppItem>, hidden: Set<ComponentName>): MutableList<MutableList<Entry>> {
        val map = apps.associateBy { it.cn.flattenToString() }
        val used = HashSet<String>()
        hidden.forEach { used.add(it.flattenToString()) }
        val pages = mutableListOf<MutableList<Entry>>()
        try {
            val root = JSONArray(p.getString("layout", "[]"))
            for (i in 0 until root.length()) {
                val pg = mutableListOf<Entry>()
                val a = root.getJSONArray(i)
                for (j in 0 until a.length()) {
                    val o = a.get(j)
                    if (o is String) {
                        val ap = map[o]
                        if (ap != null && used.add(o)) pg.add(Entry.App(ap))
                    } else if (o is JSONObject) {
                        val ia = o.getJSONArray("i")
                        val list = mutableListOf<AppItem>()
                        for (k in 0 until ia.length()) {
                            val s = ia.getString(k)
                            val ap = map[s]
                            if (ap != null && used.add(s)) list.add(ap)
                        }
                        when (list.size) {
                            0 -> {}
                            1 -> pg.add(Entry.App(list[0]))
                            else -> pg.add(Entry.Folder(o.optString("n", "مجلد"), list))
                        }
                    }
                }
                pages.add(pg)
            }
        } catch (_: Exception) {}
        if (pages.isEmpty()) pages.add(mutableListOf())
        apps.filter { used.add(it.cn.flattenToString()) }.forEach { ap ->
            var pg = pages.last()
            if (pg.size >= PAGE_CAP) { pg = mutableListOf(); pages.add(pg) }
            pg.add(Entry.App(ap))
        }
        while (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.size - 1)
        return pages
    }
}
