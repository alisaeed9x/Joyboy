package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v1003 — المرحلة 5) 🔒 خصوصية الاستوديو — الواجهة بس، والمنطق في StudioPrivacy.kt:
 *  • بوابة PIN للاستوديو كله (StudioUi.show + LectureUi.open — التانية بتغطي تدفق المشاركة من برّه).
 *  • مركز الخصوصية: PIN (تعيين/تغيير/إزالة) · وضع الإخفاء · إحصائيات المكالمات · ملخص يومي/أسبوعي ·
 *    قائمة استثناء التفريغ التلقائي.
 *  • مفيش زرار «حذف شامل لكل التفريغات» هنا عمدًا — الحذف من شاشة كل تفريغ لوحده (Lecture.deleteFull).
 *  • شاشة «العناصر المهمة» (StudioIntelUi.extractSheet) بتعرض الأرقام والمبالغ عمدًا فمبتتطمسش.
 */
class StudioPrivacyUi(private val act: Activity, private val ui: Ui, private val th: Theme) {

    private val main = Handler(Looper.getMainLooper())
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = ui.dp(top) }
    private fun vcol() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private fun hrow() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }

    private fun glassBg(r: Int): android.graphics.drawable.Drawable =
        if (MtGlass.on()) MtGlass.pill(th, MtGlass.fill(th), ui.dp(r).toFloat(), act.resources.displayMetrics.density) else ui.box(th.surface, th.border, 12)

    private fun pill(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView =
        ui.text(label, 12.5f, if (primary) onPrimary else if (danger) th.danger else th.text, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = if (primary) Glass.primaryFill(th, ui.dp(16).toFloat()) else glassBg(16)
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }

    private fun statRow(emoji: String, title: String, value: String): View = hrow().apply {
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        background = glassBg(14)
        addView(ui.text(emoji, 18f).apply { gravity = Gravity.CENTER }, lp(ui.dp(32), -2))
        addView(ui.text(title, 13.5f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        addView(ui.text(value, 13.5f, th.primary, true).apply { maxLines = 1 })
    }

    private fun pinInput(hint: String) = ui.input(hint, "").apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun pinOk(pin: String): Boolean = pin.length in 4..6 && pin.all { it in '0'..'9' }

    // ===================== بوابة PIN =====================
    // بتتنده من StudioUi.show() وLectureUi.open() — بعد أول فتح ناجح في الجلسة،
    // sessionUnlocked بيخلي أي بوابة تانية no-op من غير ما تسأل تاني.
    fun showPinGate(onUnlock: () -> Unit) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val status = ui.text("الاستوديو مقفول بـ PIN — اكتب الـ PIN عشان يفتح.", 12.5f, th.muted)
        val et = pinInput("PIN (4–6 أرقام)")
        lateinit var dd: Dialog
        col.addView(status, lp(-1, -2))
        col.addView(et, lp(-1, -2, 10))
        col.addView(pill("🔓 فتح الاستوديو", primary = true) {
            val pin = et.text.toString().trim()
            if (!pinOk(pin)) {
                status.text = "⚠ الـ PIN لازم يبقى 4–6 أرقام"
                return@pill
            }
            if (StudioPrivacy.checkPin(act, pin)) {
                try {
                    dd.dismiss()
                } catch (_: Throwable) {}
                onUnlock()
            } else {
                val left = StudioPrivacy.lockoutRemainingSec(act)
                status.text = if (left > 0) {
                    "⚠ PIN غلط — الاستوديو مقفول " + left + " ثانية"
                } else {
                    "⚠ PIN غلط — حاول تاني"
                }
            }
        }, lp(-1, -2, 10))
        col.addView(ui.text("الـ PIN بيتطلب مرة واحدة بس لحد ما التطبيق يتقفل — بعدها بيتطلب تاني.", 11.5f, th.muted), lp(-1, -2, 8))
        dd = ui.sheet(act, "🔒 الاستوديو مقفول", listOf<View>(col), fixedH = true)
        dd.show()
        try {
            et.requestFocus()
        } catch (_: Throwable) {}
    }

    // ===================== مركز الخصوصية =====================
    fun show() {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val body = vcol()
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun render() {
            body.removeAllViews()
            body.addView(ui.text("🔒 القفل (PIN)", 15f, th.text, true))
            val pinSet = StudioPrivacy.isPinSet(act)
            body.addView(ui.text(if (pinSet) "PIN متفعّل — الاستوديو بيتقفل بعد كل إغلاق للتطبيق، وبيتفتح بالـ PIN." else "مفيش PIN — أي حد بيفتح التليفون يقدر يفتح الاستوديو والمكالمات.", 12.5f, th.muted), lp(-1, -2, 4))
            val pinRow = hrow()
            pinRow.addView(pill(if (pinSet) "✎ تغيير PIN" else "🔒 تعيين PIN", primary = true) { setPinFlow { render() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            if (pinSet) {
                pinRow.addView(pill("🗑 إزالة PIN", danger = true) { removePinFlow { render() } }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(pinRow, lp(-1, -2, 8))
            body.addView(ui.text("🙈 وضع الإخفاء", 15f, th.text, true).apply { setPadding(0, ui.dp(14), 0, 0) })
            body.addView(ui.switchRow("🙈 وضع الإخفاء — طمس الأرقام والأسماء والمبالغ", StudioPrivacy.isRedactOn(act)) { v ->
                StudioPrivacy.setRedactOn(act, v)
                Notice.show(act, if (v) "🙈 الإخفاء اتفعّل — بيبان في العرض والنسخ والمشاركة والتصدير" else "الإخفاء اتقفل", 2800L)
            }, lp(-1, -2, 4))
            body.addView(ui.text("بيخفي الأرقام والأسماء والمبالغ في العرض والتصدير — مش في شاشة العناصر المهمة (دي بتعرضهم عمدًا عشان تضيفهم للتقويم أو جهات الاتصال). مذكرات Word/HTML بتفضل من غير إخفاء.", 11.5f, th.muted), lp(-1, -2, 4))
            body.addView(ui.text("📊 التقارير", 15f, th.text, true).apply { setPadding(0, ui.dp(14), 0, 0) })
            body.addView(pill("📊 إحصائيات المكالمات") { showStats() }, lp(-1, -2, 8))
            body.addView(pill("📰 ملخص يومي / أسبوعي للمكالمات") { showDigest() }, lp(-1, -2, 8))
            body.addView(pill("🚫 قائمة الاستثناء") { showExclusions() }, lp(-1, -2, 8))
            body.addView(ui.text("الحذف الشامل لكل التفريغات مش موجود هنا عمدًا — الحذف من شاشة كل تفريغ لوحده (🗑 حذف التفريغ).", 11.5f, th.muted).apply { setPadding(0, ui.dp(14), 0, 0) })
        }
        render()
        val dd = ui.sheet(act, "🔒 خصوصية الاستوديو", listOf<View>(col), fixedH = true)
        dd.show()
    }

    // ===================== PIN: تعيين / تغيير / إزالة =====================
    private fun setPinFlow(onDone: () -> Unit) {
        if (StudioPrivacy.isPinSet(act)) {
            pinEntryDialog("🔒 تغيير PIN", "اكتب الـ PIN الحالي الأول.") { old ->
                if (StudioPrivacy.checkPin(act, old)) {
                    newPinDialog(onDone)
                } else {
                    val left = StudioPrivacy.lockoutRemainingSec(act)
                    Notice.show(act, if (left > 0) "⚠ PIN غلط — مقفول " + left + " ثانية" else "⚠ PIN غلط", 3000L)
                }
            }
        } else {
            newPinDialog(onDone)
        }
    }

    private fun removePinFlow(onDone: () -> Unit) {
        pinEntryDialog("🗑 إزالة PIN", "اكتب الـ PIN الحالي عشان يتشال القفل.") { pin ->
            if (StudioPrivacy.checkPin(act, pin)) {
                StudioPrivacy.removePin(act)
                Notice.show(act, "✅ اتشال الـ PIN", 2200L)
                onDone()
            } else {
                val left = StudioPrivacy.lockoutRemainingSec(act)
                Notice.show(act, if (left > 0) "⚠ PIN غلط — مقفول " + left + " ثانية" else "⚠ PIN غلط", 3000L)
            }
        }
    }

    private fun pinEntryDialog(title: String, msg: String, onPin: (String) -> Unit) {
        val et = pinInput("PIN (4–6 أرقام)")
        GAlert(act).setTitle(title).setMessage(msg).setView(et)
            .setPositiveButton("متابعة") { _, _ ->
                val pin = et.text.toString().trim()
                if (!pinOk(pin)) {
                    Notice.show(act, "⚠ الـ PIN لازم يبقى 4–6 أرقام", 2600L)
                    return@setPositiveButton
                }
                onPin(pin)
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun newPinDialog(onDone: () -> Unit) {
        val et1 = pinInput("PIN جديد (4–6 أرقام)")
        val et2 = pinInput("أكّد الـ PIN الجديد")
        val box = vcol()
        box.addView(et1, lp(-1, -2))
        box.addView(et2, lp(-1, -2, 8))
        box.addView(ui.text("لو نسيت الـ PIN مش هتقدر تفتح الاستوديو — اكتبه صح مرتين.", 11.5f, th.muted), lp(-1, -2, 8))
        GAlert(act).setTitle("🔒 PIN جديد").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val p1 = et1.text.toString().trim()
                val p2 = et2.text.toString().trim()
                if (!pinOk(p1)) {
                    Notice.show(act, "⚠ الـ PIN لازم يبقى 4–6 أرقام", 2600L)
                    return@setPositiveButton
                }
                if (p1 != p2) {
                    Notice.show(act, "⚠ الـ PIN مش متطابق — اكتبه تاني", 2600L)
                    return@setPositiveButton
                }
                if (StudioPrivacy.setPin(act, p1)) {
                    Notice.show(act, "✅ اتفعّل الـ PIN", 2200L)
                    onDone()
                } else {
                    Notice.show(act, "⚠ مقدرتش أحفظ الـ PIN", 2600L)
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    // ===================== 📊 إحصائيات المكالمات =====================
    fun showStats() {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        val status = ui.text("⏳ بحسب الإحصائيات…", 12.5f, th.muted)
        col.addView(status, lp(-1, -2))
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        val dd = ui.sheet(act, "📊 إحصائيات الاستوديو", listOf<View>(col), fixedH = true)
        dd.show()
        Thread {
            val st = try {
                StudioPrivacy.stats(act)
            } catch (e: Throwable) {
                LogStore.err("StudioPrivacyUi:stats", e)
                null
            }
            main.post {
                val s = st ?: run {
                    status.text = "⚠ مقدرتش أحسب الإحصائيات"
                    return@post
                }
                status.text = ""
                list.addView(statRow("📚", "كل التفريغات", s.total.toString()), lp(-1, -2, 8))
                for ((code, n) in s.byType.entries.sortedByDescending { it.value }) {
                    val t = Studio.TxType.fromCode(code)
                    list.addView(statRow(t.emoji, t.label, n.toString()), lp(-1, -2, 6))
                }
                list.addView(statRow("📞", "المكالمات", s.callCount.toString()), lp(-1, -2, 6))
                list.addView(statRow("⏱", "إجمالي مدة المكالمات", Lecture.fmtTime(s.totalCallSec)), lp(-1, -2, 6))
                list.addView(statRow("⏱", "متوسط مدة المكالمة", Lecture.fmtTime(s.avgCallSec)), lp(-1, -2, 6))
                if (s.topContacts.isNotEmpty()) {
                    list.addView(ui.text("👥 أكتر جهات اتصال:", 14f, th.text, true).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(4)) })
                    for ((name, n) in s.topContacts) {
                        list.addView(statRow("👤", name, n.toString() + " مكالمة"), lp(-1, -2, 6))
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ===================== 📰 ملخص يومي / أسبوعي =====================
    fun showDigest() {
        var days = 7
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        val status = ui.text("", 12.5f, th.muted)
        fun load() {
            val d = days
            list.removeAllViews()
            status.text = "⏳ بحسب الملخص…"
            Thread {
                val res = try {
                    StudioPrivacy.digest(act, d)
                } catch (e: Throwable) {
                    LogStore.err("StudioPrivacyUi:digest", e)
                    emptyList<StudioPrivacy.DayDigest>()
                }
                main.post {
                    if (d != days) return@post
                    if (res.isEmpty()) {
                        status.text = "مفيش مكالمات متفرّغة في آخر " + d + " يوم."
                        return@post
                    }
                    status.text = ""
                    for (day in res) {
                        val card = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(16) }
                        card.addView(ui.text(day.date + " · " + day.count + " مكالمة · " + Lecture.fmtTime(day.totalDurSec), 13.5f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                        for (t in day.topics) {
                            card.addView(ui.text("• " + t, 12.5f, th.text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f) }, lp(-1, -2, 4))
                        }
                        for (x in day.decisions) {
                            card.addView(ui.text("✅ " + x, 12.5f, th.primary).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f) }, lp(-1, -2, 4))
                        }
                        list.addView(card, lp(-1, -2, 8))
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        val toggle = hrow()
        toggle.addView(pill("📰 آخر 7 أيام") { days = 7; load() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        toggle.addView(pill("📰 آخر 30 يوم") { days = 30; load() }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(ui.text("مين اتصل، والمواعيد والمهام المفتوحة، والمبالغ — من التفريغات المحفوظة بس، من غير أي طلب جديد.", 12f, th.muted), lp(-1, -2))
        col.addView(ui.text("ملحوظة: اليوم هنا يوم بداية التفريغ مش يوم المكالمة نفسها — تاريخ المكالمة الأصلي مش متخزن.", 11.5f, th.muted), lp(-1, -2, 4))
        col.addView(toggle, lp(-1, -2, 8))
        col.addView(status, lp(-1, -2, 6))
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(4) })
        val dd = ui.sheet(act, "📰 ملخص المكالمات", listOf<View>(col), fixedH = true)
        dd.show()
        load()
    }

    // ===================== 🚫 قائمة الاستثناء =====================
    fun showExclusions() {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        fun displayNameOf(u: String): String {
            val seg = try {
                Uri.parse(u).lastPathSegment ?: ""
            } catch (_: Throwable) {
                ""
            }
            val who = try {
                if (seg.isNotBlank()) CallKit.resolve(act, seg) else null
            } catch (_: Throwable) {
                null
            }
            return when {
                who != null && who.name.isNotBlank() -> who.name
                who != null && who.number.isNotBlank() -> who.number
                seg.isNotBlank() -> seg
                else -> u
            }
        }
        fun render() {
            list.removeAllViews()
            val set = try {
                StudioPrivacy.excludedUris(act)
            } catch (_: Throwable) {
                mutableSetOf<String>()
            }
            if (set.isEmpty()) {
                list.addView(ui.text("مفيش استثناءات — أي تسجيل مكالمة جديد هيتفرّغ تلقائي لو السويتش مفعّل.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) })
                return
            }
            for (u in set) {
                val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(16) }
                val top = hrow()
                val t = vcol()
                t.addView(ui.text(displayNameOf(u), 14f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                t.addView(ui.text(u.take(90), 11f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(ui.text("✕", 16f, th.danger, true).apply {
                    setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6))
                    isClickable = true
                    Glass.pressable(this)
                    setOnClickListener {
                        StudioPrivacy.removeExcluded(act, u)
                        render()
                        Notice.show(act, "✅ اتشال من الاستثناء — هيتفرّغ في الفحص الجاي لو السويتش مفعّل", 3000L)
                    }
                })
                row.addView(top)
                list.addView(row, lp(-1, -2, 8))
            }
        }
        col.addView(ui.text("التسجيلات دي مش هتتفرّغ تلقائي أبدًا — ولو شلت واحد من هنا، هيتفرّغ في الفحص الجاي لو السويتش مفعّل.", 12f, th.muted), lp(-1, -2))
        col.addView(pill("➕ إضافة تسجيل للاستثناء") { pickExclusion { render() } }, lp(-1, -2, 8))
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        val dd = ui.sheet(act, "🚫 قائمة الاستثناء", listOf<View>(col), fixedH = true)
        dd.show()
        render()
    }

    private fun pickExclusion(onDone: () -> Unit) {
        Notice.show(act, "⏳ بدوّر على التسجيلات…", 1800L)
        Thread {
            val recs = try {
                Lecture.scanAudio(act).filter { it.isRec }
            } catch (e: Throwable) {
                LogStore.err("StudioPrivacyUi:scan", e)
                emptyList<Lecture.Rec>()
            }
            val transcribed = try {
                Lecture.list(act).map { it.uri }.toHashSet()
            } catch (_: Throwable) {
                emptySet<String>()
            }
            val excluded = try {
                StudioPrivacy.excludedUris(act)
            } catch (_: Throwable) {
                emptySet<String>()
            }
            val cands = recs.filter { it.uri.toString() !in transcribed && it.uri.toString() !in excluded }.take(100)
            val labels = cands.map { r ->
                try {
                    CallKit.resolve(act, r.name).name.ifBlank { r.name.substringBeforeLast('.') }
                } catch (_: Throwable) {
                    r.name.substringBeforeLast('.')
                }
            }.toTypedArray()
            main.post {
                if (cands.isEmpty()) {
                    Notice.show(act, "مفيش تسجيلات جديدة تتضاف — كل التسجيلات يا متفرّغة يا مستثناة أصلًا", 3200L)
                    return@post
                }
                GAlert(act).setTitle("🚫 استثناء تسجيل").setMessage("اختار تسجيل عشان ميتفرّغش تلقائي أبدًا.").setItems(labels) { _, w ->
                    StudioPrivacy.addExcluded(act, cands[w].uri.toString())
                    Notice.show(act, "✅ اتضاف للاستثناء", 2200L)
                    onDone()
                }.setNegativeButton("إلغاء", null).show()
            }
        }.apply { isDaemon = true }.start()
    }
}
