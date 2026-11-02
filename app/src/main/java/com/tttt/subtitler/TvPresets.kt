package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * (v603 م5) تركيبة جاهزة = Skin + Icon Pack + Theme، محفوظة كـ preset منفصل عن قائمة السكنات.
 * [builtin] = جاهز من البرنامج (مايتمسحش)، وغير كده = من المستخدم (حفظ الحالي أو استيراد).
 */
class TvPreset(val id: String, val name: String, val skin: String, val pack: String, val theme: String, val builtin: Boolean)

/**
 * (v603 م5) مدير التركيبات المحفوظة (مش معرض اختيار السكن):
 *  - كل تركيبة تحفظ Skin + Theme + Icon Pack وتوفّر معاينة حيّة؛ باك الأيقونات المؤقت يرجع لو خرج المستخدم من غير تطبيق.
 *  - OK = تطبيق التركيبة. دوسة طويلة / Menu = ربط بزرار ملوّن · فكّ الربط · حذف.
 *  - تبديل سريع بالأزرار الملونة (🔴🟢🟡🔵): كل زرار ممكن يتربط بتركيبة ويشتغل من أي شاشة في اللانشر ([onColorKey]).
 *  - حفظ الحالي · تصدير/استيراد JSON · فحص ودمج التركيبات المحفوظة.
 */
object TvPresets {
    const val REQ_PICK = 6102
    private const val KEY_USER = "tv_presets"
    private const val KEY_BIND = "tv_preset_key_"
    private const val MAX_USER = 40
    private const val MAX_FILE = 64 * 1024
    private val ID_RE = Regex("^[A-Za-z0-9_.-]{1,40}$")
    private val main = Handler(Looper.getMainLooper())

    // ===== التركيبات الجاهزة (مدير متقدم، مش قائمة السكنات) =====
    private val BUILTIN = listOf(
        TvPreset("b_classic_room", "كلاسيك — غرفة تحكم", "classic", "default", "default", true),
        TvPreset("b_hub_blue", "Hub — أزرق هادي", "hub", "outline", "mxd", true),
        TvPreset("b_hub_warm", "Hub — كريمي دافئ", "hub", "rounded", "mx", true),
        TvPreset("b_glass_night", "Glass — ليل أموليد", "glass", "duotone", "amoled_w", true),
        TvPreset("b_glass_sky", "Glass — سماوي فاتح", "glass", "outline", "sky", true),
        TvPreset("b_eyes", "ريّح عينك — ليلي مريح", "classic", "filled", "eye_night", true)
    )

    // ===== قراءة / كتابة =====
    private fun skinOk(id: String) = TvSkins.all.any { it.id == id }
    private fun themeOk(id: String) = id == "custom" || Themes.all.any { it.id == id }
    private fun skinTokens(id: String): TvTokens = TvSkins.all.firstOrNull { it.id == id }?.tokens ?: TvTokens.CLASSIC
    private fun skinName(id: String): String = TvSkins.all.firstOrNull { it.id == id }?.name ?: id

    private fun userList(): List<TvPreset> {
        val out = ArrayList<TvPreset>()
        try {
            val a = JSONArray(Cfg.str(KEY_USER, "[]").ifBlank { "[]" })
            var migrated = false
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val nm = o.optString("name")
                val storedSkin = o.optString("skin")
                // v626: Rail was retired from the selectable layouts. Preserve old saved presets
                // and color-key bindings by mapping that legacy layout to the supported Hub skin.
                val sk = if (storedSkin == "rail") {
                    o.put("skin", "hub")
                    migrated = true
                    "hub"
                } else storedSkin
                val pk = o.optString("pack")
                val th = o.optString("theme")
                if (!ID_RE.matches(id) || nm.isBlank() || !skinOk(sk) || !themeOk(th) || !ID_RE.matches(pk)) continue
                out.add(TvPreset(id, nm, sk, pk, th, false))
            }
            if (migrated) try { Cfg.put(KEY_USER, a.toString()) } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        return out
    }

    private fun saveUser(l: List<TvPreset>) {
        val a = JSONArray()
        for (p in l) a.put(JSONObject().put("id", p.id).put("name", p.name).put("skin", p.skin).put("pack", p.pack).put("theme", p.theme))
        try { Cfg.put(KEY_USER, a.toString()) } catch (_: Throwable) {}
    }

    /** كل التركيبات المحفوظة: الجاهزة (اللي Skin وTheme بتوعها موجودين) + تركيبات المستخدم */
    fun list(): List<TvPreset> = BUILTIN.filter { skinOk(it.skin) && themeOk(it.theme) } + userList()

    private fun bindKey(k: TvColorKey) = KEY_BIND + k.name.lowercase()
    private fun bound(k: TvColorKey): String = try { Cfg.str(bindKey(k), "") } catch (_: Throwable) { "" }
    private fun keyLabel(k: TvColorKey) = when (k) { TvColorKey.RED -> "🔴 الأحمر"; TvColorKey.GREEN -> "🟢 الأخضر"; TvColorKey.YELLOW -> "🟡 الأصفر"; TvColorKey.BLUE -> "🔵 الأزرق" }
    private fun keyDot(k: TvColorKey) = when (k) { TvColorKey.RED -> "🔴"; TvColorKey.GREEN -> "🟢"; TvColorKey.YELLOW -> "🟡"; TvColorKey.BLUE -> "🔵" }

    // ===== الحالة الحقيقية (من غير تأثير المعاينة) =====
    @Volatile private var previewBase: String? = null
    @Volatile private var committed = false
    private var previewRun: Runnable? = null

    /** باك الأيقونات الحقيقي للمستخدم (مش اللي بيتعاين دلوقتي) */
    private fun realPack(): String = previewBase ?: IconPacks.activeId()

    private fun now(h: TvSkinHost) = TvPreset("now", "الحالي", TvSkins.cur().id, realPack(), h.theme.id, false)
    private fun same(p: TvPreset, q: TvPreset) = p.skin == q.skin && p.pack == q.pack && p.theme == q.theme

    // ===== التطبيق =====
    fun apply(h: TvSkinHost, p: TvPreset) {
        committed = true
        previewRun?.let { main.removeCallbacks(it) }; previewRun = null
        previewBase = null
        val a = h.activity
        fun finish() {
            if (a.isFinishing || a.isDestroyed) return
            try { TvSkins.set(p.skin, h) } catch (e: Throwable) { LogStore.err("TvPresets:skin", e) }
            Notice.show(a, "🎨 اتطبّق: " + p.name, 2600L)
            if (p.theme != h.theme.id && themeOk(p.theme)) h.setTheme(p.theme) else h.goHome()
        }
        val packId = if (p.pack == IconPacks.DEFAULT || IconPacks.list().any { it.id == p.pack }) p.pack else IconPacks.DEFAULT
        if (packId == IconPacks.activeId()) finish()
        else IconPacks.select(packId) { ok -> if (!ok) Notice.show(a, "⚠ باك الأيقونات ماتحمّلش — اتطبّق الباقي", 2800L); finish() }
    }

    /** الأزرار الملونة: لو الزرار مربوط بتركيبة بتتبدّل فورًا. false = مش مربوط (السكِن يقرر زي الأول) */
    fun onColorKey(h: TvSkinHost, k: TvColorKey): Boolean {
        val id = bound(k)
        if (id.isEmpty()) return false
        val p = list().firstOrNull { it.id == id } ?: return false
        if (same(p, now(h))) { Notice.show(h.activity, "🎨 دي التركيبة الحالية: " + p.name, 1800L); return true }
        apply(h, p); return true
    }

    // ===== معاينة باك الأيقونات (بتبدّل فعلًا وترجع) =====
    private fun previewPack(a: Activity, packId: String) {
        val target = if (packId == IconPacks.DEFAULT || IconPacks.list().any { it.id == packId }) packId else IconPacks.DEFAULT
        if (previewBase == null) previewBase = IconPacks.activeId()
        if (IconPacks.activeId() == target) return
        IconPacks.select(target) { ok -> if (ok && !a.isFinishing && !a.isDestroyed) IconPackUi.repaint(a) }
    }

    private fun endPreview(a: Activity) {
        previewRun?.let { main.removeCallbacks(it) }; previewRun = null
        val b = previewBase ?: return
        previewBase = null
        if (!committed && IconPacks.activeId() != b) IconPacks.select(b) { if (!a.isFinishing && !a.isDestroyed) IconPackUi.repaint(a) }
    }

    // ===== شاشة المعرض =====
    fun show(h: TvSkinHost) {
        endPreview(h.activity)
        committed = false; previewBase = null
        h.page { c ->
            val a = h.activity; val ui = h.ui; val pal = TvPalette(h.theme)
            val tk = TvSkins.cur().tokens
            val presets = list()
            val cur = now(h)
            val packNames = try { IconPacks.list().associate { it.id to it.name } } catch (_: Throwable) { emptyMap<String, String>() }
            fun packName(id: String) = packNames[id] ?: id

            c.addView(ui.text("🧰 التركيبات المحفوظة", tk.textTitle + 5f, pal.fg, true).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(2)) })
            c.addView(ui.text("دي تركيبات كاملة محفوظة (Skin + Theme + Icon Pack) وإدارة الأزرار الملونة؛ مش قائمة سكنات. الاختيار العادي يبدأ من «السكنات والسمات» ثم تختار السمة.", tk.textCaption + 1f, pal.fgSub).apply { setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(8)) })

            val holder = FrameLayout(a)
            holder.addView(preview(h, cur, packName(cur.pack)))
            holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { endPreview(a) }   // خرجت من المعرض من غير OK: رجّع باك الأيقونات
            })
            c.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(8)) })

            val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(8)) }
            var firstTagged = false
            for (p in presets) {
                val th = Themes.byId(p.theme)
                val chip = TvTileView(a, tk.radiusCard - 6).styled(tk, pal.primary)
                chip.inner.setBackgroundColor(th.bg)
                if (!firstTagged && same(p, cur)) { chip.tag = "skin:first"; firstTagged = true }
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
                val dots = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
                for (colr in listOf(th.primary, th.accent, th.surface)) dots.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colr); setStroke(ui.dp(1), th.border) } }, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(6) })
                val badges = TvColorKey.values().filter { bound(it) == p.id }.joinToString("") { keyDot(it) }
                if (badges.isNotEmpty()) dots.addView(ui.text(badges, 12f, th.text), LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(4) })
                col.addView(dots)
                col.addView(ui.text((if (same(p, cur)) "✔ " else "") + p.name, tk.textBody, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPadding(0, ui.dp(8), 0, 0) })
                col.addView(ui.text(skinName(p.skin).substringBefore(" (") + " · " + packName(p.pack).substringBefore(" —"), tk.textCaption, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPadding(0, ui.dp(2), 0, 0) })
                chip.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
                chip.onFocus = { f ->
                    if (f) {
                        holder.removeAllViews(); holder.addView(preview(h, p, packName(p.pack)))
                        previewRun?.let { main.removeCallbacks(it) }
                        val r = Runnable { previewRun = null; if (!a.isFinishing && !a.isDestroyed) previewPack(a, p.pack) }
                        previewRun = r; main.postDelayed(r, 350L)
                    }
                }
                chip.contentDescription = "تركيبة محفوظة " + p.name
                chip.setOnClickListener { apply(h, p) }
                chip.setOnLongClickListener { itemMenu(h, p); true }
                strip.addView(chip, LinearLayout.LayoutParams(ui.dp(196), ui.dp(118)).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) })
            }
            c.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(strip) })

            // صف الأفعال
            val acts = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(8)) }
            fun act(label: String, f: () -> Unit) {
                val t = TvTileView(a, tk.radiusCard - 6).styled(tk, pal.primary)
                t.inner.background = GradientDrawable().apply { setColor(pal.cardFill); setStroke(ui.dp(1), pal.border) }
                t.inner.addView(ui.text(label, tk.textBody + 1f, pal.fg, true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }, FrameLayout.LayoutParams(-1, -1))
                t.contentDescription = label
                t.setOnClickListener { f() }
                acts.addView(t, LinearLayout.LayoutParams(ui.dp(210), ui.dp(66)).apply { setMargins(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3)) })
            }
            act("💾 احفظ التركيبة الحالية") { saveCurrent(h) }
            act("📤 تصدير التركيبات") { exportAll(h) }
            act("📥 استيراد التركيبات") { importMenu(h) }
            c.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(acts) })
            c.addView(View(a), LinearLayout.LayoutParams(-1, ui.dp(24)))
        }
    }

    /** قائمة إجراءات التركيبة (دوسة طويلة / Menu) */
    private fun itemMenu(h: TvSkinHost, p: TvPreset) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("✅ طبّق التركيبة" to { apply(h, p) })
        for (k in TvColorKey.values()) {
            val on = bound(k) == p.id
            items.add(((if (on) "✔ " else "") + "اربطه بالزرار " + keyLabel(k)) to {
                try { Cfg.put(bindKey(k), p.id) } catch (_: Throwable) {}
                Notice.show(h.activity, keyDot(k) + " دلوقتي بيبدّل لـ «" + p.name + "» من أي شاشة", 2800L)
                show(h)
            })
        }
        if (TvColorKey.values().any { bound(it) == p.id }) items.add("🚫 فكّ ربط الأزرار" to {
            for (k in TvColorKey.values()) if (bound(k) == p.id) try { Cfg.put(bindKey(k), "") } catch (_: Throwable) {}
            show(h)
        })
        if (!p.builtin) items.add("🗑 احذف التركيبة" to {
            saveUser(userList().filter { it.id != p.id })
            for (k in TvColorKey.values()) if (bound(k) == p.id) try { Cfg.put(bindKey(k), "") } catch (_: Throwable) {}
            Notice.show(h.activity, "🗑 اتحذف: " + p.name, 2200L)
            show(h)
        })
        h.menu(p.name, items)
    }

    private fun saveCurrent(h: TvSkinHost) {
        val a = h.activity
        val ul = userList().toMutableList()
        if (ul.size >= MAX_USER) { Notice.show(a, "⚠ وصلت الحد (" + MAX_USER + " تركيبة) — امسح تركيبة الأول", 3200L); return }
        val all = list()
        var n = ul.size + 1
        while (all.any { it.name == "تنسيقي $n" }) n++
        val cur = now(h)
        if (all.any { same(it, cur) && !it.builtin }) { Notice.show(a, "ℹ التركيبة دي محفوظة قبل كده", 2400L); return }
        val p = TvPreset("u" + System.currentTimeMillis(), "تنسيقي $n", cur.skin, cur.pack, cur.theme, false)
        ul.add(p); saveUser(ul)
        Notice.show(a, "💾 اتحفظت التركيبة: " + p.name, 2400L)
        show(h)
    }

    // ===== المعاينة الحيّة (مصغّرة بشكل السكِن وألوان السمة) =====
    private fun preview(h: TvSkinHost, p: TvPreset, packLabel: String): View {
        val a = h.activity; val ui = h.ui
        val th = Themes.byId(p.theme); val pal = TvPalette(th); val tk = skinTokens(p.skin)
        fun rect(fill: Int, r: Int, stroke: Int = Color.TRANSPARENT, sw: Int = 1) = GradientDrawable().apply {
            setColor(fill); cornerRadius = ui.dp(r).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(ui.dp(sw), stroke)
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); background = rect(th.bg, tk.radiusCard + 6, th.border)
        }
        // شريط التابات + أيقونات الباك
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val tabs = listOf("🏠 الرئيسية", "🔔 الإشعارات", "🎨 السمات")
        for ((i, t) in tabs.withIndex()) bar.addView(ui.text(t, tk.textBody, if (i == 0) pal.onPrimary else pal.fg, true).apply {
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); background = rect(if (i == 0) pal.primary else pal.cardFill, tk.radiusPill)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        bar.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        for (g in listOf("🏠", "🔔", "🎨", "⚙")) bar.addView(ui.text(g, 20f, th.text).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { marginStart = ui.dp(6) })
        root.addView(bar)
        // بانر «كمّل»
        val ban = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)); background = rect(pal.cardFill, tk.radiusCard, th.border) }
        ban.addView(View(a).apply { background = rect(th.accent, tk.radiusThumb) }, LinearLayout.LayoutParams(ui.dp(92), ui.dp(52)))
        val tx = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        tx.addView(ui.text("▶ كمّل من حيث وقفت", tk.textBody + 1f, pal.fg, true))
        val track = FrameLayout(a).apply { background = rect(th.border, 3) }
        track.addView(View(a).apply { background = rect(th.primary, 3) }, FrameLayout.LayoutParams(ui.dp(120), ui.dp(5)))
        tx.addView(track, LinearLayout.LayoutParams(ui.dp(190), ui.dp(5)).apply { topMargin = ui.dp(8) })
        ban.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(ban, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        // كروت فولدر (الوسطانية متركّزة بأسلوب تركيز السكِن)
        val cards = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
        for (k in 0 until 3) {
            val focused = k == 1
            val card = FrameLayout(a).apply {
                background = when {
                    focused && tk.focusStyle == TvFocusStyle.RING -> rect(th.card, tk.radiusCard, Color.WHITE, 3)
                    focused && tk.focusStyle == TvFocusStyle.GLOW -> rect(th.card, tk.radiusCard, 0xFFFFFFFF.toInt(), 2)
                    focused && tk.focusStyle == TvFocusStyle.LIFT -> rect(th.card, tk.radiusCard, 0xDDFFFFFF.toInt(), 2)
                    else -> rect(th.card, tk.radiusCard, th.border)
                }
                if (focused) {
                    scaleX = tk.focusScale; scaleY = tk.focusScale
                    elevation = ui.dp(if (tk.focusStyle == TvFocusStyle.LIFT) 14 else 6).toFloat()
                }
            }
            card.addView(View(a).apply { background = rect(if (k == 0) th.accent else th.surface, tk.radiusThumb) }, FrameLayout.LayoutParams(-1, -1).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(18)) })
            if (focused && tk.focusStyle == TvFocusStyle.UNDERLINE) card.addView(View(a).apply { background = rect(th.primary, 2) }, FrameLayout.LayoutParams(-1, ui.dp(4), Gravity.BOTTOM).apply { setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(6)) })
            cards.addView(card, LinearLayout.LayoutParams(0, ui.dp(56), 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        }
        root.addView(cards, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        root.addView(ui.text("السكِن: " + skinName(p.skin) + "  ·  الأيقونات: " + packLabel + "  ·  الألوان: " + th.name, tk.textCaption, pal.fgSub).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0)
        })
        return root
    }

    // ===== التصدير =====
    private fun exportDirs(ctx: Context): List<File> {
        val out = ArrayList<File>()
        try { ctx.getExternalFilesDir("presets")?.let { out.add(it) } } catch (_: Throwable) {}
        try { out.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) } catch (_: Throwable) {}
        return out
    }

    private fun exportAll(h: TvSkinHost) {
        val a = h.activity
        val ul = userList()
        if (ul.isEmpty()) { Notice.show(a, "مفيش تركيبات خاصة بيك لسه — احفظ التركيبة الحالية الأول", 3400L); return }
        val arr = JSONArray()
        for (p in ul) arr.put(JSONObject().put("name", p.name).put("skin", p.skin).put("pack", p.pack).put("theme", p.theme))
        val json = JSONObject().put("app", "joyboy-presets").put("v", 1).put("presets", arr).toString(2)
        Thread {
            val saved = ArrayList<String>()
            for (d in exportDirs(a)) {
                try { d.mkdirs(); val f = File(d, "joyboy-presets.json"); f.writeText(json, Charsets.UTF_8); saved.add(f.absolutePath) } catch (_: Throwable) {}
            }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (saved.isEmpty()) Notice.show(a, "⚠ ماقدرتش أكتب الملف — مفيش مكان تخزين متاح", 3600L)
                else GAlert(a).setTitle("📤 اتصدّرت " + ul.size + " تركيبة").setMessage(saved.joinToString("\n") + "\n\nانقله لتلفزيون تاني (ابعته بـ 📤 من الموبايل) وافتح «📥 استيراد التركيبات».").setNegativeButton("تمام", null).show()
            }
        }.also { it.isDaemon = true }.start()
    }

    // ===== الاستيراد =====
    private fun inbox(ctx: Context): List<File> {
        val dirs = ArrayList<File?>()
        try { dirs.addAll(exportDirs(ctx)) } catch (_: Throwable) {}
        try { dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Joyboy")) } catch (_: Throwable) {}
        try { dirs.add(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)) } catch (_: Throwable) {}
        val seen = HashSet<String>(); val out = ArrayList<File>()
        for (d in dirs) {
            val fs = try { d?.listFiles { f -> val n = f.name.lowercase(); f.isFile && (n.endsWith(".joypresets") || (n.endsWith(".json") && (n.contains("preset") || n.contains("joyboy")))) && f.length() in 20..MAX_FILE.toLong() } } catch (_: Throwable) { null } ?: continue
            for (f in fs) if (seen.add(f.absolutePath)) out.add(f)
        }
        return out.sortedByDescending { it.lastModified() }.take(20)
    }

    private fun importMenu(h: TvSkinHost) {
        val a = h.activity
        val files = inbox(a)
        val rows = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        for (f in files) { rows.add("📄 " + f.name + "  (" + (f.length() / 1024).coerceAtLeast(1) + " ك.ب)"); acts.add { readFile(h, f) } }
        rows.add("📂 اختار من نظام الملفات…"); acts.add { pick(a) }
        GAlert(a).setTitle("📥 استيراد التركيبات")
            .setMessage(if (files.isEmpty()) "مفيش ملفات تركيبات جاهزة. حط ملف joyboy-presets.json في Download أو Movies/Joyboy (أو ابعته من الموبايل بـ 📤) وافتح الشاشة دي تاني، أو اختار من نظام الملفات." else "ملفات لقيتها — اختار:")
            .setItems(rows.toTypedArray()) { _, i -> acts.getOrNull(i)?.invoke() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun pick(a: Activity) {
        try { a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK) }
        catch (_: Throwable) { Notice.show(a, "⚠ التلفزيون مفيهوش مختار ملفات — حط الملف في Download أو Movies/Joyboy", 4200L) }
    }

    /** نتيجة اختيار الملف من نظام الملفات (من onActivityResult في TvActivity) */
    fun onPicked(h: TvSkinHost, uri: Uri?) {
        if (uri == null) return
        val a = h.activity
        Thread {
            val b = IconPacks.readUri(a, uri)
            a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) { if (b == null) Notice.show(a, "⚠ الملف مش متقري", 3200L) else importBytes(h, b) } }
        }.also { it.isDaemon = true }.start()
    }

    private fun readFile(h: TvSkinHost, f: File) {
        val a = h.activity
        Thread {
            val b = try { f.readBytes() } catch (_: Throwable) { null }
            a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) { if (b == null) Notice.show(a, "⚠ ماقدرتش أقرا " + f.name, 3000L) else importBytes(h, b) } }
        }.also { it.isDaemon = true }.start()
    }

    private fun clean(s: String): String = s.filter { !it.isISOControl() }.trim().take(40)

    /** فحص + تنضيف + دمج. بيرجّع (عدد المضاف، عدد المتخطّى) أو null لو الملف مش تركيبات Joyboy */
    internal fun parseInto(bytes: ByteArray, existing: MutableList<TvPreset>): Pair<Int, Int>? {
        if (bytes.size > MAX_FILE) return null
        val o = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Throwable) { return null }
        val app = o.optString("app")
        if (app.isNotEmpty() && app != "joyboy-presets") return null
        val arr = o.optJSONArray("presets") ?: return null
        var added = 0; var skipped = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i)
            if (e == null) { skipped++; continue }
            if (existing.size >= MAX_USER) { skipped += arr.length() - i; break }
            val storedSkin = e.optString("skin")
            // Match the on-device migration: older exported Rail combinations now map to Hub.
            val sk = if (storedSkin == "rail") "hub" else storedSkin
            val th = e.optString("theme"); val pk = e.optString("pack").ifEmpty { IconPacks.DEFAULT }
            if (!skinOk(sk) || !themeOk(th) || !ID_RE.matches(pk)) { skipped++; continue }
            val nm = clean(e.optString("name")).ifEmpty { "تركيبة مستوردة" }
            if (existing.any { it.skin == sk && it.pack == pk && it.theme == th }) { skipped++; continue }
            existing.add(TvPreset("u" + System.currentTimeMillis() + "_" + i, nm, sk, pk, th, false)); added++
        }
        return added to skipped
    }

    private fun importBytes(h: TvSkinHost, bytes: ByteArray) {
        val a = h.activity
        val ul = userList().toMutableList()
        val r = parseInto(bytes, ul)
        if (r == null) { GAlert(a).setTitle("❌ الملف مش تركيبات Joyboy").setMessage("الملف لازم يكون json طالع من «📤 تصدير التركيبات». مفيش حاجة اتغيّرت.").setNegativeButton("تمام", null).show(); return }
        if (r.first > 0) saveUser(ul)
        val msg = "اتضاف " + r.first + " تركيبة" + (if (r.second > 0) " · اتخطّيت " + r.second + " (مكرّر/غير صالح/سكِن أو سمة مش موجودة)" else "")
        Notice.show(a, (if (r.first > 0) "📥 " else "ℹ ") + msg, 4200L)
        if (r.first > 0) show(h)
    }
}
