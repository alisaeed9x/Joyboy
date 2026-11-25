package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout

/**
 * (v1303) حالة مفاتيح Gemini في الإعدادات (منقولة من تبويب شات الذكاء المحذوف v1303):
 * سطر في صفحة المفاتيح بيوري الحالة (🟢 كلهم شغّالين · 🟡 فيه مشكلة · 🔴 مفيش/واقفين · ⚪ لسه ما اتجرّبتش)،
 * ودوسة بتفتح شيت فيه حالة كل مفتاح بالتفصيل + زرار «اختبار كل المفاتيح».
 */
object KeyStatus {
    /** حالة كل مفتاح: -1 لسه ما اتجرّبش · 200 شغّال · غير كده كود الخطأ (0 = نت/مهلة) */
    val kcode = IntArray(32) { -1 }
    @Volatile private var autoTested = false
    private val h = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null

    private fun keys(): List<String> = try { Cfg.allMainKeys().filter { it != LocalKey.ID && it.isNotBlank() } } catch (_: Throwable) { emptyList() }
    private fun model(): String = try { Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT } } catch (_: Throwable) { Models.DEFAULT }

    /** النقطة + سطر الحالة المختصر */
    fun summary(): Pair<String, String> {
        val ks = keys(); val n = ks.size
        var ok = 0; var bad = 0
        for (i in 0 until n) { val c = kcode[i.coerceAtMost(31)]; if (c == 200) ok++ else if (c != -1) bad++ }
        return when {
            n == 0 -> "🔴" to "مفيش مفاتيح"
            ok == 0 && bad == 0 -> "⚪" to "دوس للاختبار"
            ok == 0 -> "🔴" to "المفاتيح واقفة"
            bad > 0 -> "🟡" to "$ok من $n شغّالة"
            else -> "🟢" to "$n مفاتيح شغّالة"
        }
    }

    private fun codeLabel(c: Int): String = when (c) {
        -1 -> "⚪ لسه ما اتجرّبش"
        200 -> "🟢 شغّال"
        429 -> "🟡 وصل للحد دلوقتي (429)"
        503, 500, 502, 504 -> "🟡 الموديل مضغوط (" + c + ")"
        404 -> "🔴 الموديل مش متاح على المفتاح (404)"
        400 -> "🔴 طلب مرفوض (400)"
        401, 403 -> "🔴 المفتاح مرفوض أو مقفول (" + c + ")"
        0 -> "🟡 مشكلة نت أو مهلة"
        else -> "🟡 خطأ " + c
    }

    /** اختبار المفاتيح في الخلفية. onTick بتتنده على خيط الواجهة بعد كل مفتاح وفي الآخر. */
    fun test(act: Activity, ui: Ui, th: Theme, reopen: Boolean = false, onTick: (() -> Unit)? = null) {
        val ks = keys(); val mdl = model()
        Thread {
            for (i in ks.indices) {
                val k = ks[i]
                val code = try { Api.generate(mdl, k, "رد بكلمة ok فقط", null, 16, 0.0, false, false); 200 } catch (e: ApiErr) { e.code } catch (_: NoKey) { 401 } catch (_: Throwable) { 0 }
                kcode[i.coerceAtMost(31)] = code
                h.post { onTick?.invoke() }
            }
            h.post { onTick?.invoke(); if (reopen && !act.isFinishing && !act.isDestroyed) show(act, ui, th) }
        }.start()
    }

    /** سطر الإعدادات: الحالة المختصرة + دوسة بتفتح التفاصيل. بيجرّب المفاتيح تلقائيًا أول مرة. */
    fun row(act: Activity, ui: Ui, th: Theme): View {
        val t = ui.text("", 14f, th.text, true).apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        fun paint() { val s = summary(); t.text = "📶 حالة المفاتيح: " + s.first + " " + s.second }
        paint()
        if (!autoTested && keys().isNotEmpty()) {
            autoTested = true
            test(act, ui, th) { try { paint() } catch (_: Throwable) {} }
        }
        t.setOnClickListener { show(act, ui, th) { try { paint() } catch (_: Throwable) {} } }
        return t
    }

    /** شيت التفاصيل: حالة كل مفتاح + اختبار الكل */
    fun show(act: Activity, ui: Ui, th: Theme, onChange: (() -> Unit)? = null) {
        try { dlg?.dismiss() } catch (_: Throwable) {}
        val ks = keys()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.text("الموديل: " + model(), 12.5f, th.muted).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)) })
        if (ks.isEmpty()) box.addView(ui.text("🔴 مفيش مفاتيح. ضيفها من خانات المفاتيح فوق.", 13.5f, th.text))
        for (i in ks.indices) {
            val k = ks[i]
            val tail = if (k.length > 8) "…" + k.takeLast(4) else ""
            val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); background = ui.box(th.card, th.border, 12) }
            row.addView(ui.text("مفتاح " + (i + 1) + " " + tail, 13.5f, th.text, true))
            row.addView(ui.text(codeLabel(kcode[i.coerceAtMost(31)]), 12f, th.muted))
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        }
        lateinit var d2: Dialog
        if (ks.isNotEmpty()) box.addView(ui.button("🔍 اختبار كل المفاتيح", true) {
            for (i in ks.indices) kcode[i.coerceAtMost(31)] = -1
            onChange?.invoke()
            d2.dismiss()
            test(act, ui, th, reopen = true, onTick = onChange)
            Notice.show(act, "بيتجرّب المفاتيح…", 1800L)
        })
        d2 = ui.sheet(act, "🔑 حالة المفاتيح", listOf<View>(box), false)
        dlg = d2
        d2.show()
    }

    // ═══════ (v1305) التيست الصوتي: قياس سقف طول الدفعة الصوتية اللي كل مفتاح يستحملها قبل ما الدبلجة تفشل ═══════
    private var testVidUri = ""
    private var testVidName = ""

    /** زرار بيتضاف في صفحة إدارة المفاتيح: بيفتح شاشة التيست الصوتي */
    fun audioTestButton(act: Activity, ui: Ui, th: Theme): View =
        ui.button("🎙 تيست صوتي للمفاتيح (فيديو + طول دفعة)") { audioTest(act, ui, th) }

    private fun audioTest(act: Activity, ui: Ui, th: Theme) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val vidTv = ui.text(if (testVidUri.isEmpty()) "🎞 اضغط هنا لاختيار فيديو من مكتبتك" else "🎞 $testVidName", 14f, th.accent)
        vidTv.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
        vidTv.setOnClickListener {
            pickVideo(act, ui, th) { uri, name ->
                testVidUri = uri; testVidName = name
                vidTv.text = "🎞 $name"
            }
        }
        box.addView(vidTv)
        var secs = 60
        box.addView(ui.slider("طول الدفعة الصوتية", 60, 40, 120, "ث") { v -> secs = v })
        val info = ui.text("بنستخرج صوت أول المدة المختارة من الفيديو وبنبعته لكل مفتاح (نفس طريقة الدبلجة الحقيقية) — النتيجة بتقولك أكبر طول يستحمله كل مفتاح", 12f, th.muted)
        box.addView(info)
        val resBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val go = ui.button("▶ ابدأ التيست", true) { runAudioTest(act, ui, th, secs, resBox) }
        box.addView(go)
        box.addView(resBox)
        ui.sheet(act, "🎙 التيست الصوتي للمفاتيح", listOf(box), false).show()
    }

    /** شيت بيعرض فيديوهات مكتبة التطبيق وبيرجّع اللي يتختار */
    private fun pickVideo(act: Activity, ui: Ui, th: Theme, cb: (uri: String, name: String) -> Unit) {
        // (v1320) إصلاح «البيكر ما بيفتحش»: الشيت كان بيتعرض فاضي والصفوف بتتضاف بعد سكان كامل للمكتبة، وعلى مكتبة كبيرة كان بيفضل واقف على «بنحمّل…» فيبان إنه مش شغال — دلوقتي بنستخدم الكاش الجاهز لو موجود، وبنبني كل الصفوف قبل ما الشيت يظهر
        try {
            val cached = LocalLibrary.cached()
            if (cached != null) {
                showVideoPicker(act, ui, th, cached, cb)
                return
            }
            Notice.show(act, "⏳ بنحمّل مكتبة الفيديو…", 1800L)
            LocalLibrary.load(act, false) { vids, _ ->
                try {
                    showVideoPicker(act, ui, th, vids ?: emptyList(), cb)
                } catch (t: Throwable) {
                    LogStore.err("audioTestPick", t)
                    Notice.show(act, "⚠ تعذّر فتح قائمة الفيديوهات", 2600L)
                }
            }
        } catch (t: Throwable) {
            LogStore.err("audioTestPick", t)
            Notice.show(act, "⚠ تعذّر فتح قائمة الفيديوهات", 2600L)
        }
    }

    /** (v1320) بناء شيت اختيار الفيديو كاملًا قبل عرضه (fixedH عشان السكرول يبقى ثابت) */
    private fun showVideoPicker(act: Activity, ui: Ui, th: Theme, vids: List<VideoItem>, cb: (uri: String, name: String) -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val dlg = ui.sheet(act, "🎞 اختار فيديو", listOf(list), true)
        if (vids.isEmpty()) {
            val empty = ui.text("مفيش فيديوهات في المكتبة", 13f, th.muted)
            empty.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
            list.addView(empty)
        } else {
            vids.take(150).forEach { v ->
                val dur = if (v.durMs > 0) " (${v.durMs / 60000}:${"%02d".format((v.durMs / 1000) % 60)})" else ""
                val row = ui.text("${v.name}$dur", 14f, th.text)
                row.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
                row.setOnClickListener { dlg.dismiss(); cb(v.uri, v.name + dur) }
                list.addView(row)
            }
        }
        dlg.show()
    }

    private fun runAudioTest(act: Activity, ui: Ui, th: Theme, secs: Int, resBox: LinearLayout) {
        if (testVidUri.isEmpty()) { Notice.show(act, "اختار فيديو من مكتبتك الأول", 2200L); return }
        val ks = keys()
        if (ks.isEmpty()) { Notice.show(act, "مفيش مفاتيح — ضيف واحد من إدارة المفاتيح", 2600L); return }
        resBox.removeAllViews()
        val header = ui.text("⏳ بنستخرج ${secs}ث صوت من الفيديو…", 13f, th.accent)
        resBox.addView(header)
        val statRows = ks.mapIndexed { i, k ->
            val st = ui.text("⏳", 14f, th.muted)
            val rw = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.text("مفتاح ${i + 1}", 14f, th.text))
                addView(ui.text("  …${k.takeLast(4)}  ", 12f, th.muted))
                addView(st)
            }
            resBox.addView(rw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
            st
        }
        Thread {
            val src = try {
                AudioSources.make(act, android.net.Uri.parse(testVidUri), null, emptyMap(), 0) {}
            } catch (e: Throwable) {
                h.post { header.text = "🔴 ماقدرناش نقرا صوت الفيديو ده: ${e.message ?: ""}" }
                return@Thread
            }
            val chunk = try { src.wav(0.0, secs.toDouble()) } catch (_: Throwable) { null }
            try { src.close() } catch (_: Throwable) {}
            if (chunk == null) {
                h.post { header.text = "🔴 مفيش مسار صوت في أول $secs ثانية من الفيديو" }
                return@Thread
            }
            val pkBytes = try { AudioEnc.pack(chunk.bytes).bytes.size } catch (_: Throwable) { chunk.bytes.size }
            h.post { header.text = "🎚 الدفعة: ${secs}ث · الحجم المرسل ~${pkBytes / 1024}KB · بنجرب ${ks.size} مفتاح…" }
            for (i in ks.indices) {
                val k = ks[i]
                val t0 = System.currentTimeMillis()
                val msg = try {
                    Api.generate(model(), k, "دي تجربة قياس سعة — رد بكلمة ok فقط", chunk.bytes, maxTokens = 32, temp = 0.0, json = false, search = false)
                    "🟢 استحمل ${secs}ث في ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)}ث"
                } catch (e: ApiErr) {
                    "🔴 " + codeLabel(e.code)
                } catch (e: Throwable) {
                    "🔴 ${e.message ?: "خطأ غير متوقع"}"
                }
                h.post {
                    statRows[i].text = msg
                    statRows[i].setTextColor(if (msg.startsWith("🟢")) th.success else th.danger)
                }
            }
            h.post { header.text = "🎚 الدفعة: ${secs}ث · الحجم ~${pkBytes / 1024}KB — لو مفتاح فشل جرّب طول أقصر" }
        }.apply { isDaemon = true }.start()
    }
}
