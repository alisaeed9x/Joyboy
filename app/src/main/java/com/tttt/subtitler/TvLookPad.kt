package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * (v720) إعدادات التلفزيون من الموبايل — (v725) شاشة واحدة بكل المجموعات: الشكل والسمات والحركة وباك الأيقونات · الخلفية · الأيقونات والتركيز ·
 * الرئيسية والصفوف · الساعة · الفيديوهات (ترتيب/عرض) · المتصفح والصلاة · أدوات الموبايل.
 * بتتغيّر من هنا وتظهر على التلفزيون فوراً (عن طريق /look على التلفزيون).
 * ألوان هادية للعين (أزرق/عنبري) وبدون أخضر.
 */
object TvLookPad {
    private val BG = 0xFF0F1626.toInt()
    private val CHIP = 0xFF1B2740.toInt()
    private val CHIP_LINE = 0xFF2E4066.toInt()
    private val SEL = 0xFF3B8DFF.toInt()
    private val TITLE = 0xFFF5C26B.toInt()
    private val SUB = 0xFFB0BEC5.toInt()

    fun show(act: Activity) {
        try {
            val dm = act.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            fun dpf(v: Float) = v * dm.density

            val d = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar)
            d.window?.setBackgroundDrawable(ColorDrawable(BG))
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            var st: JSONObject? = null
            var statusMsg = "بتّصل بالتلفزيون…"
            var render: () -> Unit = {}

            val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(BG) }

            // ----- الهيدر -----
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(16), dp(14), dp(16), dp(10))
            }
            head.addView(TextView(act).apply {
                text = "إعدادات التلفزيون"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(TextView(act).apply {
                text = "✕"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CHIP) }
                setOnClickListener { d.dismiss() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            root.addView(head, LinearLayout.LayoutParams(-1, -2))

            val body = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(16), dp(4), dp(16), dp(28))
            }
            val sc = ScrollView(act).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(body, FrameLayout.LayoutParams(-1, -2)) }
            root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

            // ----- شبكة / شرائح -----
            fun chip(label: String, sel: Boolean, fill: Int = CHIP, fg: Int = Color.WHITE, line: Int = CHIP_LINE, onTap: () -> Unit): View =
                TextView(act).apply {
                    text = label; textSize = 14f; gravity = Gravity.CENTER; maxLines = 2; typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(6), dp(8), dp(6), dp(8)); minHeight = dp(46)
                    setTextColor(if (sel && fill == CHIP) Color.WHITE else fg)
                    background = GradientDrawable().apply {
                        cornerRadius = dpf(14f)
                        setColor(if (sel && fill == CHIP) SEL else fill)
                        setStroke(if (sel) dp(2) else dp(1), if (sel) Color.WHITE else line)
                    }
                    isClickable = true; isFocusable = true
                    setOnClickListener { onTap() }
                }

            fun grid(items: List<View>, perRow: Int): View {
                val g = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                var i = 0
                while (i < items.size) {
                    val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                    for (k in 0 until perRow) {
                        val v = items.getOrNull(i + k) ?: View(act)
                        r.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                    }
                    g.addView(r, LinearLayout.LayoutParams(-1, -2))
                    i += perRow
                }
                return g
            }

            fun section(t: String, sub: String = "") {
                body.addView(TextView(act).apply { text = t; textSize = 16f; setTextColor(TITLE); typeface = Typeface.DEFAULT_BOLD },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(2) })
                if (sub.isNotEmpty()) body.addView(TextView(act).apply { text = sub; textSize = 12f; setTextColor(SUB) }, LinearLayout.LayoutParams(-1, -2))
            }

            // ----- إرسال للتلفزيون -----
            fun send(req: JSONObject) {
                Thread {
                    var newSt: JSONObject? = null
                    var msg = ""
                    try {
                        if (!RemoteClient.ensureConnected(act, 6000L)) msg = "مش قادر أوصل للتلفزيون. اتأكد إنه شغّال ومتوصل بنفس الواي فاي."
                        else {
                            val (code, txt) = RemoteClient.postJson("/look", req)
                            if (code == 200) newSt = JSONObject(txt).optJSONObject("st")
                            else if (code == 404) msg = "افتح الشاشة الرئيسية للتلفزيون الأول وجرّب تاني."
                            else msg = "حصلت مشكلة في الاتصال بالتلفزيون (" + code + ")."
                        }
                    } catch (e: Throwable) { LogStore.err("TvLookPad:send", e); msg = "حصلت مشكلة في الاتصال بالتلفزيون." }
                    act.runOnUiThread {
                        if (!d.isShowing) return@runOnUiThread
                        if (newSt != null) { st = newSt; statusMsg = "" } else if (msg.isNotEmpty()) statusMsg = msg
                        render()
                    }
                }.also { it.isDaemon = true }.start()
            }

            fun setKey(k: String, v: String) {
                val s = st ?: return
                try { s.optJSONObject("cfg")?.put(k, v) } catch (_: Throwable) {}
                render()
                send(JSONObject().put("set", JSONObject().put(k, v)))
            }

            fun cfgOf(k: String): String = st?.optJSONObject("cfg")?.optString(k, "") ?: ""

            fun optChips(key: String, opts: List<Pair<String, String>>, perRow: Int) {
                val cur = cfgOf(key)
                body.addView(grid(opts.map { (v, l) -> chip(l, v == cur) { setKey(key, v) } }, perRow), LinearLayout.LayoutParams(-1, -2))
            }

            fun label(t: String) {
                body.addView(TextView(act).apply { text = t; textSize = 14f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }

            fun toggle(t: String, key: String) {
                label(t)
                optChips(key, listOf("1" to "شغّال", "0" to "مقفول"), 2)
            }

            // (v725) كل المجموعات في شاشة واحدة، كل مجموعة بتتفتح وتتقفل بدوسة على عنوانها
            val opened = HashSet<String>().apply { add("look") }
            fun sec(key: String, title: String): Boolean {
                val on = key in opened
                body.addView(TextView(act).apply {
                    text = (if (on) "▾  " else "◂  ") + title; textSize = 16f; setTextColor(TITLE); typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(CHIP); setStroke(dp(1), CHIP_LINE) }
                    isClickable = true; isFocusable = true
                    setOnClickListener { if (key in opened) opened.remove(key) else opened.add(key); render() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                return on
            }

            fun pickList(s: JSONObject, arrKey: String, curKey: String, reqKey: String, perRow: Int) {
                val arr = s.optJSONArray(arrKey) ?: return
                val cur = s.optString(curKey)
                val vs = ArrayList<View>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    vs.add(chip(o.optString("name"), id == cur) { s.put(curKey, id); render(); send(JSONObject().put(reqKey, id)) })
                }
                body.addView(grid(vs, perRow), LinearLayout.LayoutParams(-1, -2))
            }

            val sortOpts = listOf("name" to "🔤 الاسم", "new" to "🕒 الأحدث", "old" to "⏳ الأقدم", "size" to "📦 الحجم")
            val viewOpts = listOf("grid" to "▦ كبيرة", "dense" to "▪ صغيرة", "list" to "☰ قائمة")
            val prayOpts = listOf("5" to "الهيئة المصرية", "4" to "أم القرى (مكة)", "3" to "رابطة العالم الإسلامي", "2" to "أمريكا الشمالية",
                "1" to "كراتشي", "8" to "الخليج", "9" to "الكويت", "10" to "قطر", "13" to "تركيا (ديانت)", "12" to "فرنسا")

            render = {
                body.removeAllViews()
                val s = st
                if (statusMsg.isNotEmpty()) {
                    body.addView(TextView(act).apply {
                        text = statusMsg; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(0xFF2B3A5C.toInt()) }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }
                if (s == null) {
                    body.addView(chip("جرّب تاني", false) { statusMsg = "بتّصل بالتلفزيون…"; render(); send(JSONObject()) },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                } else {
                    // ----- 🎨 الشكل -----
                    if (sec("look", "🎨 الشكل والسمات")) {
                        section("شكل الواجهة (السكن)", "بيتغيّر على التلفزيون في نفس اللحظة")
                        val curSkin = s.optString("skin")
                        val skins = s.optJSONArray("skins")
                        val skinViews = ArrayList<View>()
                        if (skins != null) for (i in 0 until skins.length()) {
                            val o = skins.optJSONObject(i) ?: continue
                            val id = o.optString("id")
                            skinViews.add(chip(o.optString("name"), id == curSkin) {
                                s.put("skin", id); render(); send(JSONObject().put("skin", id))
                            })
                        }
                        body.addView(grid(skinViews, 2), LinearLayout.LayoutParams(-1, -2))
                        toggle("اختيار السمة بعد السكن (مقفول = السكن بس ومفيش سؤال)", "tv_theme_after_skin")

                        section("الثيم (الألوان)", "الشاشة بتعيد تشغيل نفسها ثواني بعد تغيير الثيم")
                        val curTheme = s.optString("theme")
                        val themes = s.optJSONArray("themes")
                        val themeViews = ArrayList<View>()
                        if (themes != null) for (i in 0 until themes.length()) {
                            val o = themes.optJSONObject(i) ?: continue
                            val id = o.optString("id")
                            val bg = o.optInt("bg"); val pr = o.optInt("primary")
                            themeViews.add(chip(o.optString("name"), id == curTheme, fill = bg, fg = pr, line = pr) {
                                s.put("theme", id); render(); send(JSONObject().put("theme", id))
                            })
                        }
                        body.addView(grid(themeViews, 3), LinearLayout.LayoutParams(-1, -2))

                        section("نمط الحركة والتفاعل")
                        pickList(s, "motions", "motion", "motion", 2)
                        section("باك الأيقونات")
                        pickList(s, "iconpacks", "iconpack", "iconpack", 2)
                    }

                    // ----- 🖼 الخلفية -----
                    if (sec("wall", "🖼 الخلفية")) {
                        optChips("tv_wall", listOf("auto" to "تلقائي (Bing + صور)", "bing" to "Bing", "picsum" to "صور عشوائية", "off" to "بدون خلفية"), 2)
                        body.addView(chip("غيّر الخلفية دلوقتي", false, fill = 0xFF2B4B80.toInt()) { send(JSONObject().put("wallnext", true)) },
                            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                        label("بتتغيّر كل:")
                        optChips("tv_wall_sec", listOf("30" to "30 ثانية", "60" to "دقيقة", "300" to "5 دقايق", "900" to "15 دقيقة", "3600" to "ساعة", "0" to "ثابتة"), 3)
                        label("تعتيم الخلفية:")
                        optChips("tv_scrim", listOf("0" to "0%", "25" to "25%", "40" to "40%", "50" to "50%", "65" to "65%", "80" to "80%", "90" to "90%"), 4)
                        toggle("فلتر دافي مريح للعين", "tv_warm")
                    }

                    // ----- ⭕ الأيقونات -----
                    if (sec("icons", "⭕ الأيقونات والحجم")) {
                        optChips("tv_icon_shape", listOf("circle" to "دايرة", "squircle" to "مربع مدوّر"), 2)
                        label("حجم الأيقونة:")
                        optChips("tv_icon_px", listOf("64" to "64", "76" to "76", "86" to "86", "100" to "100", "116" to "116", "132" to "132"), 3)
                        label("أسماء التطبيقات:")
                        optChips("tv_names", listOf("focus" to "عند التركيز", "always" to "دايماً", "off" to "مخفية"), 3)
                        label("حجم الواجهة كلها:")
                        optChips("tv_scale_adj", listOf("50" to "50%", "70" to "70%", "85" to "85%", "100" to "100%", "115" to "115%", "130" to "130%", "160" to "160%", "200" to "200%"), 4)
                        section("التركيز على العناصر")
                        optChips("tv_lift", listOf("0" to "بدون", "1" to "خفيف", "2" to "متوسط", "3" to "كبير"), 4)
                        toggle("هالة حوالين العنصر", "tv_halo")
                    }

                    // ----- 🏠 الرئيسية -----
                    if (sec("home", "🏠 الرئيسية والصفوف")) {
                        section("الصفوف الظاهرة في الرئيسية", "دوسة تظهّر / تخفّي")
                        val rows = s.optJSONArray("rows")
                        val rowViews = ArrayList<View>()
                        if (rows != null) for (i in 0 until rows.length()) {
                            val o = rows.optJSONObject(i) ?: continue
                            val id = o.optString("id"); val on = o.optBoolean("on")
                            rowViews.add(chip((if (on) "✔  " else "✖  ") + o.optString("name"), on) { o.put("on", !on); render(); send(JSONObject().put("row", id)) })
                        }
                        body.addView(grid(rowViews, 1), LinearLayout.LayoutParams(-1, -2))
                        toggle("اختصار Google Play", "tv_play")
                    }

                    // ----- 🕒 الساعة -----
                    if (sec("clock", "🕒 الساعة وشاشة التوقف")) {
                        optChips("tv_clock24", listOf("0" to "12 ساعة", "1" to "24 ساعة"), 2)
                        label("شاشة التوقف بعد:")
                        optChips("tv_saver_min", listOf("1" to "دقيقة", "3" to "3 دقايق", "5" to "5 دقايق", "10" to "10 دقايق", "0" to "أبداً"), 3)
                    }

                    // ----- 🎬 الفيديوهات -----
                    if (sec("vids", "🎬 الفيديوهات (ترتيب وعرض)")) {
                        label("ترتيب الفولدرات:")
                        optChips("tv_fold_sort", sortOpts, 2)
                        label("ترتيب الفيديوهات جوه الفولدر:")
                        optChips("tv_vid_sort", sortOpts, 2)
                        label("عرض الفولدرات:")
                        optChips("tv_fold_view", viewOpts, 3)
                        label("عرض الفيديوهات جوه الفولدر:")
                        optChips("tv_vid_view", viewOpts, 3)
                    }

                    // ----- 🌐 المتصفح والصلاة وغيره -----
                    if (sec("misc", "🌐 المتصفح · مواقيت الصلاة · عام")) {
                        label("متصفح التلفزيون:")
                        optChips("tv_br_mode", listOf("ptr" to "مؤشر (ماوس)", "focus" to "تركيز (أسهم)"), 2)
                        label("طريقة حساب مواقيت الصلاة:")
                        optChips("tv_pray_method", prayOpts, 2)
                        toggle("سؤال «خلّي البرنامج هو الرئيسي؟» على التلفزيون", "tv_ask_home")
                    }
                }

                // ----- 📱 الموبايل + أدوات (من غير اتصال كمان) -----
                if (sec("phone", "📱 على الموبايل وأدوات")) {
                    body.addView(chip(TvModeFab.alwaysLabel(), false) { try { TvModeFab.toggleAlways(act) } catch (_: Throwable) {}; render() },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                    val syncOn = Cfg.str("tv_sync_off", "0") != "1"
                    body.addView(chip(if (syncOn) "🔄 مزامنة إعدادات الموبايل للتلفزيون: شغّالة (دوس تقفلها)" else "⛔ مزامنة إعدادات الموبايل للتلفزيون: مقفولة (دوس تشغّلها)", syncOn) {
                        Cfg.put("tv_sync_off", if (syncOn) "1" else "0"); render()
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                    body.addView(chip(if (TvEdit.active()) "↩ ارجع لإعدادات الموبايل (بتعدّل التلفزيون دلوقتي)" else "✏️ عدّل إعدادات التلفزيون المشتركة (ترجمة · شكل الترجمة · صوت) على التلفزيون بس", false, fill = 0xFF2B4B80.toInt()) {
                        d.dismiss(); if (TvEdit.active()) TvEdit.exit(act) else TvEdit.enter(act)
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                    body.addView(chip("🔧 افتح إعدادات البرنامج على شاشة التلفزيون", false, fill = 0xFF2B4B80.toInt()) { try { RemoteClient.cmd("open_settings") } catch (_: Throwable) {} },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }

                // ----- رجوع للافتراضي -----
                if (s != null) {
                    body.addView(chip("رجّع المظهر للوضع الافتراضي", false, fill = 0xFF5A2B2B.toInt(), fg = Color.WHITE, line = 0xFF8A4A4A.toInt()) {
                        android.app.AlertDialog.Builder(act)
                            .setTitle("رجوع للافتراضي")
                            .setMessage("هيرجّع إعدادات المظهر (الخلفية والأيقونات والتركيز والساعة) على التلفزيون للوضع الافتراضي.")
                            .setPositiveButton("أيوه") { _, _ -> send(JSONObject().put("reset", true)) }
                            .setNegativeButton("لأ", null).show()
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
                }
            }

            d.setContentView(root)
            render()
            d.show()
            send(JSONObject())
        } catch (e: Throwable) { LogStore.err("TvLookPad:show", e) }
    }
}
