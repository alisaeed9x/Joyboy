package com.tttt.joyboylauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Xml
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser

data class AppItem(val label: String, val cn: ComponentName, val icon: Drawable)

/** Loads ADW/Nova-style icon packs (appfilter.xml) = the "theme store" ecosystem. */
class IconPack(ctx: Context, val pkg: String) {
    private val map = HashMap<String, String>()
    private val res: Resources = ctx.packageManager.getResourcesForApplication(pkg)

    init {
        try {
            val id = res.getIdentifier("appfilter", "xml", pkg)
            val p: XmlPullParser = if (id != 0) res.getXml(id)
            else Xml.newPullParser().apply { setInput(res.assets.open("appfilter.xml"), null) }
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "item") {
                    val c = p.getAttributeValue(null, "component")
                    val d = p.getAttributeValue(null, "drawable")
                    if (c != null && d != null && c.startsWith("ComponentInfo{"))
                        map[c.substring(14, c.length - 1)] = d
                }
                ev = p.next()
            }
        } catch (_: Exception) {}
    }

    fun get(cn: ComponentName): Drawable? {
        val name = map["${cn.packageName}/${cn.className}"] ?: return null
        val id = res.getIdentifier(name, "drawable", pkg)
        return if (id == 0) null else ResourcesCompat.getDrawable(res, id, null)
    }

    companion object {
        fun installed(ctx: Context): List<Pair<String, String>> {
            val pm = ctx.packageManager
            val out = LinkedHashMap<String, String>()
            for (a in listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme"))
                pm.queryIntentActivities(Intent(a), 0).forEach {
                    out[it.activityInfo.packageName] = it.loadLabel(pm).toString()
                }
            return out.map { it.key to it.value }
        }
    }
}

object Apps {
    /** Material-You themed icon (Android 13+ monochrome layer). */
    private fun themed(ctx: Context, d: Drawable): Drawable {
        if (Build.VERSION.SDK_INT < 33 || d !is AdaptiveIconDrawable) return d
        val mono = d.monochrome ?: return d
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = ctx.getColor(if (night) android.R.color.system_accent1_800 else android.R.color.system_accent1_100)
        val fg = ctx.getColor(if (night) android.R.color.system_accent1_100 else android.R.color.system_accent1_700)
        val m = mono.mutate().also { it.setTint(fg) }
        return AdaptiveIconDrawable(ColorDrawable(bg), m)
    }

    fun load(ctx: Context, pack: IconPack?, themedIcons: Boolean): List<AppItem> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                val base = ri.loadIcon(pm)
                val icon = pack?.get(cn) ?: if (themedIcons) themed(ctx, base) else base
                AppItem(ri.loadLabel(pm).toString(), cn, icon)
            }
            .sortedBy { it.label.lowercase() }
    }
}
