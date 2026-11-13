package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * (v828 · Joyboy Care إعادة تصميم) أيقونات التطبيقات الحقيقية من PackageManager — بتتحمّل على Thread خلفي
 * وبتتخزّن في LruCache (بحجم محدود بالبايت). الـImageView بيتعلّم بالحزمة عشان القوائم اللي بتتعاد تدوير صفوفها ما تعرضش أيقونة غلط.
 */
object CareIcons {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)

    private fun load(ctx: Context, pkg: String, px: Int): Bitmap? = try {
        val dr = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        dr.setBounds(0, 0, px, px); dr.draw(c)
        bmp
    } catch (_: Throwable) { null }

    /** يحط الأيقونة في الـImageView (فورًا لو متخزّنة، أو بعد التحميل). لو الأيقونة مش موجودة بيفضل المكان الفاضي المدوّر. */
    fun bind(iv: ImageView, ctx: Context, pkg: String, px: Int, placeholder: Int) {
        iv.tag = pkg
        iv.setImageDrawable(null)
        iv.background = GradientDrawable().apply { setColor(placeholder); cornerRadius = px * 0.22f }
        val key = "$pkg@$px"
        val hit = cache.get(key)
        if (hit != null) { iv.setImageBitmap(hit); return }
        try {
            pool.execute {
                val b = load(ctx, pkg, px)
                if (b != null) {
                    cache.put(key, b)
                    iv.post { if (iv.tag == pkg) iv.setImageBitmap(b) }
                }
            }
        } catch (_: Throwable) {}
    }
}

/** شريط نسبة بسيط (زي شريط التخزين في تنظيف الذاكرة): مسار + تعبئة مدوّرة من اليمين (RTL). */
class CareBar(ctx: Context) : View(ctx) {
    var frac = 0f
    var track = 0; var color = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        p.color = track; c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.004f) { p.color = color; val fw = maxOf(h, w * f); c.drawRoundRect(RectF(w - fw, 0f, w, h), r, r, p) }
    }
}

/** (v830) ألوان الأيقونات الدايرية بتتولّد من لون سمة البرنامج (نفس اللون الأساسي بدرجات مختلفة) فتتماشى مع أي سمة، والنص عليها أبيض. */
object CareColors {
    var OLIVE = 0xFF6B7F3A.toInt(); var CARAMEL = 0xFFC8861A.toInt(); var TERRA = 0xFFC0603A.toInt()
    var COFFEE = 0xFF8A5A3C.toInt(); var SAGE = 0xFF5E9E7A.toInt(); var PLUM = 0xFF8A5A7A.toInt()
    var SKY = 0xFF4A8FB8.toInt(); var ROSE = 0xFFC2486A.toInt()

    private fun shift(base: Int, deg: Float): Int {
        val hsv = FloatArray(3); Color.colorToHSV(base, hsv)
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        hsv[1] = hsv[1].coerceIn(0.50f, 0.82f); hsv[2] = hsv[2].coerceIn(0.52f, 0.78f)
        return Color.HSVToColor(hsv)
    }

    fun applyTheme(th: Theme) {
        val p = th.primary
        OLIVE = shift(p, 0f); CARAMEL = shift(th.accent, 0f); TERRA = shift(p, 28f); COFFEE = shift(p, -28f)
        SAGE = shift(p, 120f); PLUM = shift(p, 250f); SKY = shift(p, 200f); ROSE = shift(p, 325f)
    }
}

/**
 * (v828) أدوات واجهة العناية: صفحات كاملة (مش bottom sheet) بسهم رجوع في اليمين زي تطبيق الأمان عند شيومي،
 * بلاطات، صفوف قوائم، أيقونات دايرية، شرائح، وحقل بحث. كله Views عادية (مفيش Compose ولا WebView).
 */
class CareKit(val act: Activity, val ui: Ui, val th: Theme) {
    val pal = CarePalette.of(th)
    init { CareColors.applyTheme(th) }
    private val den = act.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * den).toInt()

    fun fill(color: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun t(s: String, sp: Float = 14f, color: Int = pal.text, bold: Boolean = false): TextView = ui.text(s, sp, color, bold).apply {
        textDirection = View.TEXT_DIRECTION_RTL; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }
    fun col(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    fun rowH(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
    fun add(p: LinearLayout, v: View, top: Int = 0, w: Int = -1, h: Int = -2) { p.addView(v, LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }) }

    fun toast(s: String) { try { Toast.makeText(act, s, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
    fun copy(label: String, text: String) {
        try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text)); toast("اتنسخ") } catch (_: Throwable) {}
    }
    fun confirm(title: String, msg: String, yes: String, go: () -> Unit) {
        GAlert(act).setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> go() }.setNegativeButton("لا", null).show()
    }

    /** دايرة ملوّنة فيها أيقونة (إيموجي بيتحوّل لأيقونة Joyboy). */
    fun badge(emoji: String, color: Int, sizeDp: Int = 46): View {
        val f = FrameLayout(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }
        f.addView(ui.text(emoji, (sizeDp * 0.42f), Color.WHITE).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1))
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return f
    }

    /** بلاطة لوحة التحكم (أيقونة فوق + عنوان + سطر حالة). الـsubView بيرجّع للنص الفرعي عشان يتحدّث بعدين. */
    class Tile(val view: View, val sub: TextView)
    fun tile(emoji: String, color: Int, title: String, sub: String, go: () -> Unit): Tile {
        val c = col().apply { background = fill(pal.surface, 22); setPadding(dp(16), dp(16), dp(16), dp(16)); minimumHeight = dp(136); isClickable = true; isFocusable = true }
        val top = rowH(); top.addView(badge(emoji, color, 46)); top.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        c.addView(top, LinearLayout.LayoutParams(-1, -2))
        add(c, t(title, 17f, pal.text, true), 14)
        val s = t(sub, 12f, pal.muted).apply { maxLines = 2 }
        add(c, s, 3)
        c.contentDescription = "$title. $sub"
        c.setOnClickListener { go() }
        return Tile(c, s)
    }

    /** صف قايمة زي «موفر الطاقة» عند شيومي: دايرة أيقونة يمين، عنوان + سطر، وسهم شمال. */
    fun listRow(emoji: String, color: Int, title: String, sub: String, go: (() -> Unit)?): View {
        val r = rowH().apply { background = fill(pal.surface, 22); setPadding(dp(16), dp(14), dp(16), dp(14)); minimumHeight = dp(76) }
        r.addView(badge(emoji, color, 46))
        val tx = col()
        tx.addView(t(title, 16f, pal.text, true))
        if (sub.isNotEmpty()) add(tx, t(sub, 12f, pal.muted).apply { maxLines = 3 }, 2)
        r.addView(tx, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
        if (go != null) { r.addView(t("‹", 24f, pal.muted)); r.isClickable = true; r.isFocusable = true; r.setOnClickListener { go() } }
        r.contentDescription = title + (if (sub.isNotEmpty()) ". $sub" else "")
        return r
    }

    fun card(vararg v: View): LinearLayout {
        val c = col().apply { background = fill(pal.surface, 20); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        v.forEach { c.addView(it) }
        return c
    }

    fun pill(label: String, primary: Boolean = true, go: () -> Unit): TextView =
        t(label, 15f, if (primary) pal.onAccent else pal.text, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(52); setPadding(dp(28), dp(12), dp(28), dp(12))
            background = fill(if (primary) pal.accent else pal.surface, 26, if (primary) 0 else pal.line)
            isClickable = true; isFocusable = true; setOnClickListener { go() }
        }

    fun chip(label: String, on: Boolean, go: () -> Unit): View = t(label, 13f, if (on) pal.onAccent else pal.text, on).apply {
        background = fill(if (on) pal.accent else pal.surface, 18)
        setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true; isFocusable = true; setOnClickListener { go() }
    }

    fun chipsRow(vararg chips: View): View {
        val r = rowH()
        for ((i, c) in chips.withIndex()) r.addView(c, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
        return HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(r) }
    }

    fun searchField(hint: String, value: String, onChange: (String) -> Unit): EditText = EditText(act).apply {
        this.hint = hint; setText(value); setSingleLine(); textSize = 14f
        setTextColor(pal.text); setHintTextColor(pal.muted); layoutDirection = View.LAYOUT_DIRECTION_RTL
        textDirection = View.TEXT_DIRECTION_RTL; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        background = fill(pal.surface, 16); setPadding(dp(16), dp(12), dp(16), dp(12))
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { onChange(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    fun appIcon(pkg: String, sizeDp: Int): ImageView = ImageView(act).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        CareIcons.bind(this, act, pkg, dp(sizeDp), pal.line)
    }

    fun page(title: String): CarePage = CarePage(this, title)

    fun bar(frac: Float?, color: Int, hDp: Int = 10): CareBar = CareBar(act).apply {
        track = pal.line; this.color = color; this.frac = frac ?: 0f
        layoutParams = LinearLayout.LayoutParams(-1, dp(hDp))
    }
}

/** صفحة كاملة: هيدر (سهم رجوع يمين + عنوان + أزرار شمال) + جسم بيتمرّر. render{} بيتنده من جديد لما تحتاج تحدّث الشاشة. */
class CarePage(val k: CareKit, val title: String) {
    /** بيتنده كل ما الصفحة ترجع تاخد الفوكس (مثلًا بعد الرجوع من شاشة إعدادات النظام) عشان نحدّث الحالة. */
    var onResume: (() -> Unit)? = null
    private var hadFocus = false
    val dlg: GDialog = object : GDialog(k.act, android.R.style.Theme_DeviceDefault_NoActionBar) {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus && hadFocus) { try { onResume?.invoke() } catch (e: Throwable) { LogStore.err("Care:resume", e) } }
            if (hasFocus) hadFocus = true
        }
    }.apply { plain = true }
    val body: LinearLayout = k.col().apply { setPadding(k.dp(16), k.dp(4), k.dp(16), k.dp(24)) }
    private val actions = k.rowH()
    private val scroll = ScrollView(k.act).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
    private var builder: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val root = k.col().apply { setBackgroundColor(k.pal.bg) }

    /** (v927) الشريط السفلي بتاع البرنامج (زجاجي شفاف زي باقي الصفحات) تحت صفحة «مركز العناية» الرئيسية */
    fun withNav() {
        try {
            val act = k.act; val th = k.th
            val nav = BottomNav(act, k.ui, th, BottomNav.CARE) { i -> navTo(i) }
            if (MtFloat.eligible(act)) {
                nav.floatGlass()
                val src = JoyGlassSource(act).apply { setBackgroundColor(k.pal.bg) }
                root.removeView(scroll)
                src.addView(scroll, FrameLayout.LayoutParams(-1, -1))
                scroll.clipToPadding = false; scroll.setPadding(0, 0, 0, k.dp(96))
                val bar = MeeGlass.bar(act, th); bar.setSource(src)
                bar.addView(nav.view, FrameLayout.LayoutParams(-1, -2))
                val fl = FrameLayout(act)
                fl.addView(src, FrameLayout.LayoutParams(-1, -1))
                fl.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(k.dp(16), 0, k.dp(16), k.dp(12)) })
                root.addView(fl, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                root.addView(nav.view, LinearLayout.LayoutParams(-1, -2))
            }
        } catch (e: Throwable) { LogStore.err("Care:nav", e) }
    }

    private fun navTo(i: Int) {
        val act = k.act
        fun main(tab: String) = act.startActivity(android.content.Intent(act, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", tab))
        try {
            when {
                i == BottomNav.CARE -> return
                i >= BottomNav.YT_HOME && i <= BottomNav.YT_ME -> { dismiss(); MeeTubeLink.open(act) }
                i == 0 -> { dismiss(); main("videos") }
                i == 1 -> { dismiss(); main("music") }
                i == 2 -> { dismiss(); act.startActivity(android.content.Intent(act, BrowserActivity::class.java)) }
                i == 3 -> { dismiss(); act.startActivity(FilesHub.intent(act)) }
                i == 4 -> { dismiss(); main("youtube") }
                i == 5 -> { dismiss(); main("tasks") }
                i == 7 -> { dismiss(); main("settings") }
            }
        } catch (e: Throwable) { LogStore.err("Care:navTo", e) }
    }

    init {
        val head = k.rowH().apply { setPadding(k.dp(8), k.dp(8), k.dp(8), k.dp(4)) }
        val back = k.t("→", 28f, k.pal.text, true).apply {
            gravity = Gravity.CENTER; minimumWidth = k.dp(48); minimumHeight = k.dp(48)
            contentDescription = "رجوع"; isClickable = true; isFocusable = true; setOnClickListener { dlg.dismiss() }
        }
        head.addView(back)
        head.addView(k.t(title, 21f, k.pal.text, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = k.dp(6) })
        head.addView(actions)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(root)
        dlg.setOnShowListener { styleWindow() }
        dlg.setOnDismissListener { onClose?.invoke() }
    }

    private fun styleWindow() {
        val w = dlg.window ?: return
        try {
            w.setLayout(-1, -1)
            w.setBackgroundDrawable(ColorDrawable(k.pal.bg))
            w.statusBarColor = k.pal.bg; w.navigationBarColor = k.pal.bg
            val light = k.th.isLight
            if (Build.VERSION.SDK_INT >= 30) {
                val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                w.insetsController?.setSystemBarsAppearance(if (light) m else 0, m)
            } else if (Build.VERSION.SDK_INT >= 23) {
                @Suppress("DEPRECATION") run { w.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0 }
            }
        } catch (_: Throwable) {}
    }

    /** زرار صغير في الهيدر (شمال). */
    fun action(emoji: String, label: String, go: () -> Unit) {
        actions.addView(k.t(emoji, 22f, k.pal.text).apply {
            gravity = Gravity.CENTER; minimumWidth = k.dp(48); minimumHeight = k.dp(48)
            contentDescription = label; isClickable = true; isFocusable = true; setOnClickListener { go() }
        })
    }

    fun render(b: () -> Unit) { builder = b; redraw() }
    fun redraw() { body.removeAllViews(); try { builder?.invoke() } catch (e: Throwable) { LogStore.err("Care:page:$title", e) } }
    fun show() { dlg.show() }
    fun dismiss() { try { dlg.dismiss() } catch (_: Throwable) {} }
}
