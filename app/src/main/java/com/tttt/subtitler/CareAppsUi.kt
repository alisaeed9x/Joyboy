package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * (v828 · Joyboy Care) مدير التطبيقات بشكل «إدارة التطبيق» عند شيومي: صفحة كاملة، أيقونة حقيقية لكل تطبيق، الحجم، الترتيب،
 * شرائح (مثبّتة · نظام · معطّلة · غير مستخدمة)، إلغاء تثبيت متعدد، وصفحة تفاصيل فيها فتح/معلومات/تعطيل/تفعيل/إلغاء تثبيت.
 *
 * ⚠ التعطيل: أندرويد مابيسمحش لتطبيق عادي يعطّل تطبيق تاني (محتاج صلاحية نظام CHANGE_COMPONENT_ENABLED_STATE). فزرار «تعطيل» بيفتح
 * صفحة معلومات التطبيق في النظام (اللي فيها تعطيل/إيقاف إجباري) ونفس الصفحة بترجّعنا ونحدّث الحالة، وفيه نسخ أمر ADB لو بتستخدم Termux/ADB.
 * مفيش تعطيل صامت ولا تحايل. الحذف بس عن طريق شاشة إلغاء التثبيت بتاعة أندرويد بتأكيد من النظام.
 */
class CareAppsUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val k = CareKit(act, ui, th)
    private val pal = k.pal
    private val h = Handler(Looper.getMainLooper())
    private var recs: List<AppRec>? = null
    private var usageOk = false
    private var usage: Map<String, Long>? = null
    private val sizes = ConcurrentHashMap<String, Long>()
    @Volatile private var closed = false
    private var filter = 0
    private var sortMode = 0
    private var query = ""
    private var limit = 60
    private var selecting = false
    private val picked = LinkedHashSet<String>()
    private lateinit var page: CarePage
    private var host: LinearLayout? = null

    private fun sz(b: Long): String = try { Formatter.formatShortFileSize(act, b) } catch (_: Throwable) { "${b / 1048576} MB" }
    private val sortNames = listOf("الاسم", "الحجم", "الأحدث تثبيتًا")

    private fun load() {
        Thread {
            val l = try { CareAppsRead.load(act) } catch (_: Throwable) { emptyList() }
            val ok = CareAppsRead.usageGranted(act)
            val u = if (ok) CareAppsRead.lastUsed(act, System.currentTimeMillis()) else null
            h.post { if (!closed) { recs = l; usageOk = ok; usage = u; page.redraw() } }
            if (ok) computeSizes(l)
        }.start()
    }

    /** الأحجام الحقيقية (StorageStats) في الخلفية: تطبيقات المستخدم الأول، وبنحدّث الشاشة كل شوية مش مع كل تطبيق. */
    private fun computeSizes(l: List<AppRec>) {
        val order = CareAppLogic.userApps(l) + CareAppLogic.systemApps(l)
        var n = 0
        for (r in order) {
            if (closed) return
            val s = CareAppsRead.sizeOf(act, r.pkg)
            if (s != null) sizes[r.pkg] = s
            n++
            if (n % 40 == 0) h.post { if (!closed) refreshList() }
        }
        h.post { if (!closed) refreshList() }
    }

    private fun current(): List<AppRec> {
        val l = recs ?: return emptyList()
        val base = when (filter) {
            0 -> CareAppLogic.userApps(l); 1 -> CareAppLogic.systemApps(l); 2 -> CareAppLogic.disabledApps(l)
            else -> if (usageOk) CareAppLogic.unusedApps(l, usage ?: emptyMap(), System.currentTimeMillis()) else emptyList()
        }
        return CareAppLogic.sorted(base.filter { CareAppLogic.matches(it, query) }, sortMode, sizes)
    }

    private fun sizeLine(r: AppRec): String {
        val real = sizes[r.pkg]
        return if (real != null) sz(real) else if (r.apkBytes > 0) "APK ${sz(r.apkBytes)}" else ""
    }

    private fun render() {
        val l = recs
        val body = page.body
        if (l == null) { k.add(body, k.t("بقرا التطبيقات المثبّتة…", 14f, pal.muted), 24); return }
        val now = System.currentTimeMillis()
        val nUser = CareAppLogic.userApps(l).size; val nSys = CareAppLogic.systemApps(l).size; val nDis = CareAppLogic.disabledApps(l).size
        val nUnused = if (usageOk) CareAppLogic.unusedApps(l, usage ?: emptyMap(), now).size else -1
        fun pick(i: Int) { filter = i; limit = 60; selecting = false; picked.clear(); page.redraw() }
        k.add(body, k.chipsRow(
            k.chip("مثبّتة ($nUser)", filter == 0) { pick(0) },
            k.chip("نظام ($nSys)", filter == 1) { pick(1) },
            k.chip("معطّلة ($nDis)", filter == 2) { pick(2) },
            k.chip("غير مستخدمة (" + (if (nUnused >= 0) nUnused.toString() else "—") + ")", filter == 3) { pick(3) }), 4)

        val et: EditText = k.searchField("ابحث بالاسم أو اسم الحزمة", query) { q -> query = q; limit = 60; h.removeCallbacks(redrawList); h.postDelayed(redrawList, 250L) }
        k.add(body, et, 10)

        if (filter == 3 && !usageOk) {
            val c = k.col()
            c.addView(k.t("«غير مستخدمة» محتاجة إذن «الوصول لبيانات الاستخدام» (إنت اللي بتمنحه من إعدادات النظام، واختياري). من غير إشارة استخدام حقيقية مش هخمّن.", 13f, pal.text))
            k.add(c, k.pill("⚙️ افتح إعداد الإذن") { try { act.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Throwable) {} }, 10)
            k.add(body, k.card(c), 10)
        }
        if (filter == 3 && usageOk) k.add(body, k.t("مثبّتة من أكتر من ${CareAppLogic.MIN_AGE_DAYS} يوم ومفيش استخدام مسجّل في آخر ${CareAppLogic.UNUSED_DAYS} يوم (تقدير حسب سجل أندرويد المحدود).", 11f, pal.muted), 8)
        if (filter == 1) k.add(body, k.t("تطبيقات النظام: التعطيل بيتم من صفحة معلومات التطبيق في النظام (لو الجهاز بيسمح) — عطّل بس اللي تعرفه.", 11f, pal.muted), 8)

        // القايمة نفسها في حاوية لوحدها: البحث والترتيب والأحجام بتعيد رسم القايمة بس (حقل البحث والكيبورد مابيتلمسوش)
        val hostCol = k.col()
        host = hostCol
        k.add(body, hostCol, 0)
        fillList()
    }

    private fun fillList() {
        val l = recs ?: return
        val hc = host ?: return
        hc.removeAllViews()
        val list = current()
        val info = k.rowH()
        info.addView(k.t("${list.size} تطبيق · ترتيب: ${sortNames[sortMode]}", 12f, pal.muted).apply {
            isClickable = true; setPadding(0, k.dp(8), 0, k.dp(8)); setOnClickListener { sortMode = (sortMode + 1) % 3; limit = 60; fillList() }
            contentDescription = "تغيير الترتيب. الحالي ${sortNames[sortMode]}"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (filter == 0 && list.isNotEmpty()) info.addView(k.t(if (selecting) "✕ إلغاء التحديد" else "🗑 إلغاء تثبيت متعدد", 12f, pal.accent, true).apply {
            isClickable = true; setPadding(k.dp(8), k.dp(8), 0, k.dp(8))
            setOnClickListener { selecting = !selecting; picked.clear(); fillList() }
        })
        k.add(hc, info, 6)
        if (usageOk) k.add(hc, k.t("الحجم = تطبيق + بيانات + كاش (من إحصاءات التخزين).", 10f, pal.muted), 0)
        else k.add(hc, k.t("الحجم المعروض = حجم ملف الـAPK بس. للحجم الكامل (بيانات + كاش) فعّل «الوصول لبيانات الاستخدام» لـJoyboy من إعدادات النظام.", 10f, pal.muted), 0)

        if (selecting) {
            val n = picked.size
            k.add(hc, k.pill(if (n == 0) "اختار تطبيقات للحذف" else "🗑 إلغاء تثبيت ($n)", n > 0) { if (picked.isNotEmpty()) confirmBatch(l) }, 8)
        }

        for (r in list.take(limit)) k.add(hc, appRow(r), 8)
        if (list.size > limit) k.add(hc, k.pill("عرض ${minOf(60, list.size - limit)} كمان", false) { limit += 60; fillList() }, 10)
        if (list.isEmpty() && !(filter == 3 && !usageOk)) k.add(hc, k.t("مفيش تطبيقات هنا.", 13f, pal.muted).apply { gravity = Gravity.CENTER }, 24)
    }

    /** تحديث القايمة بس لو الشاشة مرسومة (من غير ما نلمس حقل البحث)، وإلا رسم كامل. */
    private fun refreshList() { if (host?.parent != null) fillList() else page.redraw() }

    private val redrawList = Runnable { if (!closed) refreshList() }

    private fun appRow(r: AppRec): View {
        val row = k.rowH().apply { background = k.fill(pal.surface, 18); setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(10)); minimumHeight = k.dp(68); isClickable = true; isFocusable = true }
        val on = r.pkg in picked
        if (selecting && !r.system) row.addView(CheckBox(act).apply {
            isChecked = on; isClickable = false; isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(pal.accent)
        })
        row.addView(k.appIcon(r.pkg, 46))
        val tx = k.col()
        tx.addView(k.t(r.label, 15f, pal.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val sub = listOf(r.versionName, sizeLine(r)).filter { it.isNotEmpty() }.joinToString("  ·  ")
        k.add(tx, k.t(sub, 11f, pal.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, 2)
        row.addView(tx, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = k.dp(12); marginEnd = k.dp(8) })
        if (!r.enabled) row.addView(k.t("معطّل", 10f, pal.warn, true).apply { background = k.fill(pal.bg, 8); setPadding(k.dp(8), k.dp(3), k.dp(8), k.dp(3)) })
        else if (!selecting) row.addView(k.t("‹", 22f, pal.muted))
        row.contentDescription = r.label + (if (!r.enabled) "، معطّل" else "")
        row.setOnClickListener {
            if (selecting) {
                if (r.system) { k.toast("تطبيقات النظام مبتتحذفش من هنا"); return@setOnClickListener }
                if (!picked.add(r.pkg)) picked.remove(r.pkg)
                refreshList()
            } else CareAppDetail.open(k, r) { reloadOne(r.pkg) }
        }
        return row
    }

    private fun reloadOne(pkg: String) {
        Thread {
            val n = CareAppsRead.one(act, pkg)
            h.post {
                if (closed) return@post
                val cur = recs ?: return@post
                recs = if (n == null) cur.filter { it.pkg != pkg } else cur.map { if (it.pkg == pkg) n else it }
                refreshList()
            }
        }.start()
    }

    private fun confirmBatch(l: List<AppRec>) {
        val sel = l.filter { it.pkg in picked && !it.system }
        if (sel.isEmpty()) return
        val names = sel.take(12).joinToString("\n") { "• " + it.label } + (if (sel.size > 12) "\n… و${sel.size - 12} كمان" else "")
        k.confirm("إلغاء تثبيت ${sel.size} تطبيق", "هتظهر شاشة تأكيد من أندرويد لكل تطبيق، وإنت اللي بتوافق على كل واحد:\n\n$names", "كمّل") {
            for ((i, r) in sel.withIndex()) h.postDelayed({
                try { act.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + r.pkg))) } catch (_: Throwable) {}
            }, i * 450L)
            selecting = false; picked.clear()
            h.postDelayed({ if (!closed) { recs = null; page.redraw(); load() } }, sel.size * 450L + 1500L)
        }
    }

    fun show() {
        page = k.page("إدارة التطبيقات")
        page.action("🔄", "تحديث القايمة") { recs = null; sizes.clear(); page.redraw(); load() }
        page.onClose = { closed = true; h.removeCallbacksAndMessages(null) }
        page.render { render() }
        page.show()
        load()
    }
}

/** صفحة تفاصيل تطبيق (بتتفتح من المدير ومن مركز الخصوصية). onChanged بيتنده لما صفحة التفاصيل تتقفل عشان القايمة تتحدّث. */
object CareAppDetail {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun open(k: CareKit, r0: AppRec, onChanged: () -> Unit) {
        val act = k.act; val pal = k.pal
        var r: AppRec = r0
        var stats: LongArray? = null
        val page = k.page(r0.label)
        fun sz(b: Long): String = try { Formatter.formatShortFileSize(act, b) } catch (_: Throwable) { "${b / 1048576} MB" }
        fun settings(pkg: String) { try { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } catch (_: Throwable) { k.toast("مقدرتش أفتح الإعدادات") } }
        fun kv(c: LinearLayout, key: String, v: String) {
            val row = k.rowH()
            row.addView(k.t(key, 12f, pal.muted), LinearLayout.LayoutParams(k.dp(104), -2))
            row.addView(k.t(v, 13f, pal.text).apply { setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, -2, 1f))
            k.add(c, row, 8)
        }
        fun actionBtn(emoji: String, label: String, color: Int, go: () -> Unit): View {
            val c = k.col().apply { gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; isFocusable = true; setPadding(k.dp(4), k.dp(8), k.dp(4), k.dp(8)); contentDescription = label }
            c.addView(k.badge(emoji, color, 48))
            k.add(c, k.t(label, 12f, pal.text).apply { gravity = Gravity.CENTER }, 6)
            c.setOnClickListener { go() }
            return c
        }

        fun draw() {
            val body = page.body
            val head = k.col().apply { gravity = Gravity.CENTER_HORIZONTAL }
            head.addView(k.appIcon(r.pkg, 76))
            k.add(head, k.t(r.label, 21f, pal.text, true).apply { gravity = Gravity.CENTER }, 10)
            k.add(head, k.t("الإصدار ${r.versionName}" + (if (!r.enabled) "  ·  معطّل" else ""), 12f, if (r.enabled) pal.muted else pal.warn).apply { gravity = Gravity.CENTER }, 2)
            k.add(body, head, 6)

            // أزرار الإجراءات
            val acts = ArrayList<View>()
            val launch = try { act.packageManager.getLaunchIntentForPackage(r.pkg) } catch (_: Throwable) { null }
            if (launch != null && r.enabled) acts.add(actionBtn("▶", "فتح", CareColors.OLIVE) { try { act.startActivity(launch) } catch (_: Throwable) { k.toast("مقدرتش أفتحه") } })
            acts.add(actionBtn("ℹ️", "معلومات", CareColors.SKY) { settings(r.pkg) })
            acts.add(actionBtn(if (r.enabled) "⛔" else "✅", if (r.enabled) "تعطيل" else "تفعيل", CareColors.CARAMEL) { settings(r.pkg) })
            if (!r.system) acts.add(actionBtn("🗑", "إلغاء التثبيت", CareColors.TERRA) {
                try { act.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + r.pkg))) } catch (_: Throwable) { k.toast("مقدرتش أفتح شاشة الحذف") }
            })
            val grid = k.rowH().apply { weightSum = acts.size.toFloat() }
            for (a in acts) grid.addView(a, LinearLayout.LayoutParams(0, -2, 1f))
            k.add(body, grid, 16)

            // شرح التعطيل بصراحة (أندرويد مابيسمحش من تطبيق عادي)
            val dis = k.col()
            dis.addView(k.t(if (r.enabled) "إزاي التعطيل بيشتغل؟" else "إزاي التفعيل بيشتغل؟", 13f, pal.text, true))
            k.add(dis, k.t("أندرويد مابيسمحش لتطبيق عادي يعطّل/يفعّل تطبيق تاني. الزرار فوق بيفتح صفحة معلومات التطبيق في النظام اللي فيها «" +
                (if (r.enabled) "تعطيل" else "تفعيل") + "» و«إيقاف إجباري»، ولما ترجع هنا الحالة بتتحدّث لوحدها." +
                (if (r.system && r.enabled) "\n⚠ ده تطبيق نظام: تعطيله ممكن يوقّف خدمات (اتصال/إشعارات/تحديثات). عطّل بس اللي تعرفه." else ""), 12f, pal.muted), 4)
            k.add(dis, k.t("📋 نسخ أمر ADB لـ" + (if (r.enabled) "التعطيل" else "التفعيل") + " (Termux/ADB لاسلكي)", 12f, pal.accent, true).apply {
                isClickable = true; setPadding(0, k.dp(8), 0, k.dp(4))
                setOnClickListener { k.copy("adb", if (r.enabled) CareAppLogic.adbDisable(r.pkg) else CareAppLogic.adbEnable(r.pkg)) }
            }, 6)
            k.add(body, k.card(dis), 12)

            // التفاصيل
            val d = k.col()
            kv(d, "الحزمة", r.pkg)
            kv(d, "النسخة", "${r.versionName} (${r.versionCode})")
            kv(d, "اتثبّت", day.format(Date(r.firstInstall)))
            kv(d, "آخر تحديث", day.format(Date(r.lastUpdate)))
            kv(d, "مصدر التثبيت", CareAppLogic.installerName(r.installer))
            kv(d, "النوع", (if (r.system) "تطبيق نظام" else "مثبّت بواسطتك") + " · " + (if (r.enabled) "مفعّل" else "معطّل"))
            val st = stats
            if (st != null) {
                kv(d, "حجم التطبيق", sz(st[0])); kv(d, "البيانات", sz(st[1])); kv(d, "الكاش", sz(st[2]))
            } else {
                if (r.apkBytes > 0) kv(d, "حجم الـAPK", sz(r.apkBytes))
                kv(d, "البيانات والكاش", "غير متاح — محتاج إذن «الوصول لبيانات الاستخدام» لـJoyboy")
            }
            val sens = CarePerm.GROUPS.filter { CarePerm.granted(r, it).isNotEmpty() }
            val granted = r.requested.count { it in r.granted }
            kv(d, "الأذونات", "مُعلنة ${r.requested.size} · ممنوحة $granted")
            kv(d, "حساسة ممنوحة", if (sens.isEmpty()) "مفيش" else sens.joinToString(" · ") { it.title })
            k.add(d, k.t("«ممنوحة» = الموافقة موجودة. ده مش دليل إن التطبيق بيستخدمها فعلًا ولا إنه ضار.", 10f, pal.muted), 10)
            k.add(body, k.card(d), 12)
        }

        page.render { draw() }
        // بعد الرجوع من إعدادات النظام: نقرا حالة التطبيق من جديد (اتعطّل؟ اتفعّل؟ اتحذف؟)
        page.onResume = {
            Thread {
                val n = CareAppsRead.one(act, r.pkg)
                act.runOnUiThread { if (n == null) page.dismiss() else { r = n; page.redraw() } }
            }.start()
        }
        page.onClose = { onChanged() }
        page.show()
        // الأحجام الحقيقية لو الإذن ممنوح
        Thread {
            val s = if (CareAppsRead.usageGranted(act)) CareAppsRead.statsOf(act, r.pkg) else null
            if (s != null) act.runOnUiThread { stats = s; page.redraw() }
        }.start()
    }
}
