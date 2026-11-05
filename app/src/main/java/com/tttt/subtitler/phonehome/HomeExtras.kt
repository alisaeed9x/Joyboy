package com.tttt.subtitler.phonehome

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * (v773) إضافات لانشر الموبايل: الدوك المخصّص · الودجت (AppWidgetHost). (الثيم بالذكاء → HomeThemes.kt)
 */

// ───────── الدوك ─────────
object HomeDock {
    const val MAX = 5
    private const val K = "dock"

    /** false = المستخدم لسه ما خصّصش الدوك → بنعرض الافتراضي (اتصال/رسائل/متصفح/كاميرا) */
    fun customized(p: SharedPreferences): Boolean = p.contains(K)

    fun get(p: SharedPreferences): List<String> =
        (p.getString(K, "") ?: "").split("\n").filter { it.isNotBlank() }

    fun save(p: SharedPreferences, l: List<String>) {
        p.edit().putString(K, l.take(MAX).joinToString("\n")).apply()
    }

    fun has(p: SharedPreferences, cn: ComponentName): Boolean = get(p).contains(cn.flattenToString())

    /** false لو الدوك مليان */
    fun add(p: SharedPreferences, cn: ComponentName): Boolean {
        val l = get(p).toMutableList()
        val s = cn.flattenToString()
        if (s in l) return true
        if (l.size >= MAX) return false
        l.add(s); save(p, l)
        return true
    }

    fun remove(p: SharedPreferences, cn: ComponentName) {
        val s = cn.flattenToString()
        save(p, get(p).filter { it != s })
    }
}

// (v775) الثيم بالذكاء اتنقل لـ HomeThemes.kt (HomeTheme · HomeThemeStore · HomeAi · ThemeStudio)

// ───────── الودجت ─────────
/**
 * AppWidgetHost: إضافة/حذف/تكبير/تصغير ودجت أي تطبيق. الودجت بتتعرض في شاشة «الودجت» (overlay) بتتفتح من
 * ضغطة طويلة على الشاشة الرئيسية ← الودجت.
 */
class HomeWidgets(
    private val act: Activity,
    private val prefs: SharedPreferences,
    private val root: FrameLayout
) {
    companion object {
        const val HOST_ID = 7721
        const val REQ_BIND = 7701
        const val REQ_CFG = 7702
        private const val KEY = "widgets"
        private const val STEP = 60
    }

    private class W(val id: Int, var h: Int)

    private val host = AppWidgetHost(act, HOST_ID)
    private val mgr = AppWidgetManager.getInstance(act)
    private var pending = -1
    private var panel: FrameLayout? = null
    private var list: LinearLayout? = null

    val isOpen: Boolean get() = panel != null

    fun start() { try { host.startListening() } catch (_: Throwable) {} }
    fun stop() { try { host.stopListening() } catch (_: Throwable) {} }

    private fun dp(v: Int) = act.dp(v)

    // ----- تخزين: [{id,h}] -----
    private fun load(): MutableList<W> {
        val out = mutableListOf<W>()
        try {
            val a = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(W(o.getInt("id"), o.optInt("h", 160)))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun save(l: List<W>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("h", it.h)) }
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    // ----- الشاشة -----
    fun open() {
        if (panel != null) return
        val scrim = FrameLayout(act).apply { setBackgroundColor(0xF2101014.toInt()); isClickable = true }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(52), dp(16), dp(20))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = "🧩 الودجت"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(chip("＋ إضافة") { pick() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        head.addView(chip("✕") { close() }, LinearLayout.LayoutParams(-2, -2))
        col.addView(head, LinearLayout.LayoutParams(-1, -2))
        val lst = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        list = lst
        val sv = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        sv.addView(lst, FrameLayout.LayoutParams(-1, -2))
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        scrim.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel = scrim
        refill()
    }

    fun close() {
        panel?.let { root.removeView(it) }
        panel = null; list = null
    }

    private fun chip(t: String, click: () -> Unit) = TextView(act).apply {
        text = t; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x33FFFFFF) }
        pressEffect()
        setOnClickListener { click() }
    }

    private fun refill() {
        val lst = list ?: return
        lst.removeAllViews()
        val ws = load()
        if (ws.isEmpty()) {
            lst.addView(TextView(act).apply {
                text = "مفيش ودجت لسه — دوس «＋ إضافة»"
                setTextColor(0xAAFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER
                setPadding(0, dp(60), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        for (w in ws.toList()) buildItem(lst, w)
    }

    private fun buildItem(lst: LinearLayout, w: W) {
        val info = try { mgr.getAppWidgetInfo(w.id) } catch (_: Throwable) { null }
        if (info == null) { drop(w.id); return }
        val hv: AppWidgetHostView = try { host.createView(act, w.id, info) } catch (_: Throwable) { return }
        val wrap = FrameLayout(act).apply {
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0x14FFFFFF) }
            clipToOutline = true
        }
        wrap.addView(hv, FrameLayout.LayoutParams(-1, -1))
        val more = TextView(act).apply {
            text = "⋯"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
        }
        wrap.addView(more, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        more.setOnClickListener { menu(w, wrap, hv, more) }
        hv.setOnLongClickListener { menu(w, wrap, hv, more); true }
        lst.addView(wrap, LinearLayout.LayoutParams(-1, dp(w.h)).apply { bottomMargin = dp(12) })
        wrap.post { sizeHint(hv, w.h) }
    }

    private fun sizeHint(hv: AppWidgetHostView, hDp: Int) {
        try {
            val wDp = (root.width / act.resources.displayMetrics.density).toInt() - 32
            hv.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
        } catch (_: Throwable) {}
    }

    private fun menu(w: W, wrap: FrameLayout, hv: AppWidgetHostView, anchor: View) {
        PopupMenu(act, anchor).apply {
            menu.add(0, 1, 0, "أكبر")
            menu.add(0, 2, 1, "أصغر")
            menu.add(0, 3, 2, "حذف")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> resize(w, wrap, hv, STEP)
                    2 -> resize(w, wrap, hv, -STEP)
                    3 -> remove(w.id)
                }
                true
            }
        }.show()
    }

    private fun resize(w: W, wrap: FrameLayout, hv: AppWidgetHostView, d: Int) {
        w.h = (w.h + d).coerceIn(80, 640)
        wrap.layoutParams = (wrap.layoutParams as LinearLayout.LayoutParams).apply { height = dp(w.h) }
        save(load().map { if (it.id == w.id) W(it.id, w.h) else it })
        sizeHint(hv, w.h)
    }

    private fun drop(id: Int) {
        try { host.deleteAppWidgetId(id) } catch (_: Throwable) {}
        save(load().filter { it.id != id })
    }

    private fun remove(id: Int) { drop(id); refill() }

    // ----- اختيار وإضافة -----
    fun pick() {
        val pm = act.packageManager
        val all: List<AppWidgetProviderInfo> = try { mgr.installedProviders } catch (_: Throwable) { emptyList() }
        val provs = all
            .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
            .sortedBy { (it.loadLabel(pm) ?: "").lowercase() }
        if (provs.isEmpty()) { Toast.makeText(act, "مفيش ودجت متاحة على الجهاز", Toast.LENGTH_SHORT).show(); return }
        val names = provs.map { p ->
            val app = try { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() } catch (_: Throwable) { p.provider.packageName }
            (p.loadLabel(pm) ?: "ودجت") + "  —  " + app
        }.toTypedArray()
        AlertDialog.Builder(act).setTitle("اختار ودجت").setItems(names) { _, i -> bind(provs[i]) }.show()
    }

    private fun bind(info: AppWidgetProviderInfo) {
        val id = try { host.allocateAppWidgetId() } catch (_: Throwable) { -1 }
        if (id < 0) { Toast.makeText(act, "مقدرتش أحجز ودجت", Toast.LENGTH_SHORT).show(); return }
        pending = id
        val ok = try { mgr.bindAppWidgetIdIfAllowed(id, info.provider) } catch (_: Throwable) { false }
        if (ok) { configureOrAdd(id); return }
        // أول مرة: النظام بيطلب موافقة المستخدم على ربط الودجت
        val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        try { act.startActivityForResult(i, REQ_BIND) } catch (_: Throwable) {
            drop(id); pending = -1
            Toast.makeText(act, "الودجت دي مش مسموحة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureOrAdd(id: Int) {
        val info = try { mgr.getAppWidgetInfo(id) } catch (_: Throwable) { null }
        if (info?.configure != null) {
            try { host.startAppWidgetConfigureActivityForResult(act, id, 0, REQ_CFG, null); return } catch (_: Throwable) {}
        }
        addWidget(id)
    }

    private fun addWidget(id: Int) {
        val info = try { mgr.getAppWidgetInfo(id) } catch (_: Throwable) { null }
        if (info == null) { try { host.deleteAppWidgetId(id) } catch (_: Throwable) {}; return }
        val h = ((info.minHeight / act.resources.displayMetrics.density).toInt() + 16).coerceIn(90, 420)
        val l = load(); l.add(W(id, h)); save(l)
        pending = -1
        if (panel == null) open() else refill()
    }

    /** من onActivityResult بتاع النشاط — بترجع true لو النتيجة بتاعتنا */
    fun onResult(req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_BIND && req != REQ_CFG) return false
        var id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (id < 0) id = pending
        if (id < 0) return true
        if (res != Activity.RESULT_OK) {
            try { host.deleteAppWidgetId(id) } catch (_: Throwable) {}
            pending = -1
            return true
        }
        pending = id
        if (req == REQ_BIND) configureOrAdd(id) else addWidget(id)
        return true
    }
}
