package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.provider.OpenableColumns
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** (v1283) تبويب «الذكاء»: شات مع جيميناي بنفس موديل التطبيق ومفاتيحه (بالتناوب)، إدخال بالكتابة أو المايك، وسجل محادثات محفوظ على الجهاز. */
class AiChat(val id: String, var title: String, var ts: Long, val msgs: ArrayList<Pair<String, String>>)

object AiStore {
    private fun file(c: Context) = File(c.filesDir, "ai_chats.json")
    @Synchronized fun load(c: Context): ArrayList<AiChat> {
        val out = ArrayList<AiChat>()
        try {
            val f = file(c); if (!f.exists()) return out
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val m = ArrayList<Pair<String, String>>()
                val ms = o.optJSONArray("m") ?: JSONArray()
                for (k in 0 until ms.length()) { val x = ms.getJSONObject(k); m.add(x.optString("r") to x.optString("t")) }
                out.add(AiChat(o.optString("id"), o.optString("title"), o.optLong("ts"), m))
            }
        } catch (e: Throwable) { LogStore.err("AiStore:load", e) }
        return out
    }
    @Synchronized fun save(c: Context, all: List<AiChat>) {
        try {
            val a = JSONArray()
            for (ch in all.sortedByDescending { it.ts }.take(200)) {
                val ms = JSONArray(); for (p in ch.msgs) ms.put(JSONObject().put("r", p.first).put("t", p.second))
                a.put(JSONObject().put("id", ch.id).put("title", ch.title).put("ts", ch.ts).put("m", ms))
            }
            file(c).writeText(a.toString())
        } catch (e: Throwable) { LogStore.err("AiStore:save", e) }
    }
}

class AiChatUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    companion object {
        const val REQ_VOICE = 7717
        const val REQ_FILE = 7718
        @Volatile private var keyStart = 0
        /** حالة كل مفتاح: -1 لسه ما اتجرّبش · 200 شغّال · غير كده كود الخطأ (0 = نت/مهلة) */
        val kcode = IntArray(32) { -1 }
        @Volatile private var autoTested = false
        private val TEXT_EXT = setOf("txt", "md", "csv", "json", "srt", "vtt", "ass", "xml", "html", "htm", "log", "kt", "java", "py", "js", "ts", "css", "yml", "yaml", "ini", "sh", "sql", "c", "cpp", "h")
        private const val SYSTEM = "إنت مساعد ذكاء اصطناعي جوه تطبيق Joyboy (مشغّل فيديو وترجمة). رد بنفس لغة المستخدم، ولو كتب بالعربي رد بالمصري الواضح المختصر، ومتألفش معلومات مش متأكد منها."
    }
    private val h = Handler(Looper.getMainLooper())
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
    private val scroll = ScrollView(act).apply { addView(col, LinearLayout.LayoutParams(-1, -2)); isFillViewport = false }
    private val input = EditText(act).apply {
        hint = "اكتب رسالتك…"; setTextColor(th.text); setHintTextColor(th.muted); textSize = 14.5f
        background = ui.box(th.surface, th.border, 22); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); maxLines = 5
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private var cur: AiChat? = null
    private var busy = false
    private var attName = ""
    private var attMime = ""
    private var attData: ByteArray? = null
    private var attText: String? = null
    private val statusTv = ui.text("", 11f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(3)); background = ui.box(th.card, th.border, 12) }
    private val chipRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(2), ui.dp(12), 0); visibility = View.GONE }
    private var barRef: LinearLayout? = null

    private fun pill(t: String, onClick: () -> Unit): View = ui.text(t, 13f, th.primary, true).apply {
        setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7)); gravity = Gravity.CENTER
        background = ui.box(th.card, th.border, 18); setOnClickListener { onClick() }
    }
    private fun circle(t: String, filled: Boolean, onClick: () -> Unit): View = ui.text(t, 18f, if (filled) th.onPrimary else th.text, true).apply {
        gravity = Gravity.CENTER; background = ui.box(if (filled) th.primary else th.card, if (filled) th.primary else th.border, 22)
        layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) }; setOnClickListener { onClick() }
    }

    init {
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(6)) }
        head.addView(pill("☰ المحادثات") { showHistory() })
        val ttl = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        ttl.addView(ui.text("✨ شات الذكاء الاصطناعي", 16f, th.text, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
        statusTv.setOnClickListener { showKeys() }
        ttl.addView(statusTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(3) })
        head.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("＋ جديدة") { newChat() })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(chipRow, LinearLayout.LayoutParams(-1, -2))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(10)) }
        bar.addView(circle("➤", true) { send(input.text.toString()) })
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(circle("🎙", false) { startVoice() })
        bar.addView(circle("📎", false) { pickFile() })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        barRef = bar
        // الشريط السفلي الزجاجي العايم بيغطي أسفل الصفحة: نرفع خانة الكتابة فوقه (0 لو مفيش شريط عايم)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val ex = try { MtFloat.barTop(act) } catch (_: Throwable) { 0 }
            val want = ui.dp(10) + ex
            if (bar.paddingBottom != want) bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, want)
        }
        renderEmpty()
        updateStatus()
        if (!autoTested && keys().isNotEmpty()) { autoTested = true; testKeys(false) }
    }

    private fun model(): String = try { Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT } } catch (_: Throwable) { Models.DEFAULT }
    private fun keys(): List<String> = try { Cfg.allMainKeys().filter { it != LocalKey.ID && it.isNotBlank() } } catch (_: Throwable) { emptyList() }

    fun refresh() {}

    // ===== الشاشة =====
    private fun renderEmpty() {
        col.removeAllViews()
        col.addView(ui.text("اسأل أي حاجة 👋", 18f, th.text, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(40), 0, ui.dp(4)) })
        col.addView(ui.text("بيشتغل بموديل التطبيق ومفاتيحك بالتناوب. اكتب أو دوس على المايك.", 12.5f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(14)) })
        for (s in listOf("لخّصلي فكرة فيلم بجملتين", "ترجملي الجملة دي بلهجة مصرية", "اشرحلي إزاي أظبط توقيت الترجمة")) {
            col.addView(pill(s) { input.setText(s); input.setSelection(s.length); input.requestFocus() }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        }
    }

    private fun clean(t: String): String = t.replace("**", "").replace(Regex("(?m)^\\s*[*-] "), "• ").trim()

    private fun bubble(role: String, text: String): View {
        val me = role == "u"
        val tv = ui.text(if (me) text else clean(text), 14.5f, if (me) th.onPrimary else th.text, false)
        tv.setTextIsSelectable(true)
        tv.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
        tv.background = ui.box(if (me) th.primary else th.card, if (me) th.primary else th.border, 16)
        tv.maxWidth = (act.resources.displayMetrics.widthPixels * 0.86f).toInt()
        tv.setOnLongClickListener { copy(text); true }
        val w = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        w.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (me) Gravity.START else Gravity.END })
        if (!me && !text.startsWith("⏳")) {
            val act2 = pill("⧉ نسخ") { copy(text) }
            w.addView(act2, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; setMargins(0, ui.dp(3), 0, 0) })
        }
        w.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(5), 0, ui.dp(5)) }
        return w
    }

    private fun copy(t: String) {
        try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ai", t)); Notice.show(act, "اتنسخ", 1200L) } catch (_: Throwable) {}
    }

    private fun toBottom() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun renderChat(c: AiChat) {
        col.removeAllViews()
        for (m in c.msgs) col.addView(bubble(m.first, m.second))
        toBottom()
    }

    // ===== المحادثات =====
    private fun newChat() {
        if (busy) { Notice.show(act, "استنى الرد يخلص", 1500L); return }
        cur = null; renderEmpty(); input.setText("")
    }

    private fun showHistory() {
        val chats = AiStore.load(act).sortedByDescending { it.ts }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        lateinit var dlg: Dialog
        box.addView(ui.button("➕ محادثة جديدة", true) { dlg.dismiss(); newChat() })
        if (chats.isEmpty()) box.addView(ui.text("مفيش محادثات محفوظة لسه.", 13f, th.muted).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(4)) })
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
        for (c in chats) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(8), ui.dp(6), ui.dp(8)); background = ui.box(th.card, th.border, 12) }
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            t.addView(ui.text(c.title.ifBlank { "محادثة" }, 14f, th.text, true).apply { maxLines = 1 })
            t.addView(ui.text(fmt.format(java.util.Date(c.ts)) + " · " + (c.msgs.size / 2 + c.msgs.size % 2) + " رسالة", 11f, th.muted))
            t.setOnClickListener { dlg.dismiss(); if (!busy) { cur = c; renderChat(c) } else Notice.show(act, "استنى الرد يخلص", 1500L) }
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.text("🗑", 16f, th.muted, false).apply {
                setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                setOnClickListener {
                    val all = AiStore.load(act); all.removeAll { it.id == c.id }; AiStore.save(act, all)
                    if (cur?.id == c.id) { cur = null; renderEmpty() }
                    dlg.dismiss(); showHistory()
                }
            })
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        }
        dlg = ui.sheet(act, "🕘 المحادثات المحفوظة", listOf<View>(box), false)
        dlg.show()
    }

    private fun persist(c: AiChat) {
        c.ts = System.currentTimeMillis()
        val all = AiStore.load(act); all.removeAll { it.id == c.id }; all.add(c); AiStore.save(act, all)
    }

    // ===== المرفقات =====
    private fun pickFile() {
        try { act.startActivityForResult(filePicker("*/*", "اختار صورة أو ملف", false), REQ_FILE) } catch (e: Throwable) { Notice.show(act, "مفيش تطبيق ملفات على الجهاز", 2200L) }
    }

    fun attachResult(u: Uri) {
        Thread {
            var err = ""
            var name = "ملف"; var mime = ""; var data: ByteArray? = null; var text: String? = null
            try {
                val cr = act.contentResolver
                mime = cr.getType(u) ?: ""
                try { cr.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) ?: name } } } catch (_: Throwable) {}
                val ext = name.substringAfterLast('.', "").lowercase()
                val isText = mime.startsWith("text/") || ext in TEXT_EXT || mime == "application/json" || mime == "application/xml"
                val inl = mime.startsWith("image/") || mime == "application/pdf" || mime.startsWith("audio/") || mime.startsWith("video/")
                if (!isText && !inl) err = "النوع ده مش مدعوم. ابعت صورة أو PDF أو صوت أو ملف نصي (txt/csv/json/srt…)"
                else {
                    val bytes = cr.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
                    if (bytes.isEmpty()) err = "الملف فاضي أو مش مقروء"
                    else if (isText) text = String(bytes, Charsets.UTF_8).take(120_000)
                    else if (bytes.size > 14 * 1024 * 1024) err = "الملف كبير (حد أقصى 14 ميجا)"
                    else { data = bytes; if (mime.isBlank()) mime = "application/octet-stream" }
                }
            } catch (e: Throwable) { err = "مقدرتش أقرأ الملف: " + (e.message ?: "").take(60) }
            h.post {
                if (err.isNotEmpty()) { Notice.show(act, err, 3200L); return@post }
                attName = name; attMime = mime; attData = data; attText = text
                showChip()
            }
        }.start()
    }

    private fun clearAtt() { attName = ""; attMime = ""; attData = null; attText = null; chipRow.removeAllViews(); chipRow.visibility = View.GONE }

    private fun showChip() {
        chipRow.removeAllViews()
        val kind = if (attMime.startsWith("image/")) "🖼 " else "📎 "
        val c = ui.text(kind + attName.take(34) + "  ✕", 12.5f, th.text).apply {
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); background = ui.box(th.card, th.border, 14)
            setOnClickListener { clearAtt() }
        }
        chipRow.addView(c, LinearLayout.LayoutParams(-2, -2))
        chipRow.visibility = View.VISIBLE
    }

    // ===== حالة المفاتيح =====
    private fun updateStatus() {
        val ks = keys()
        val n = ks.size
        var ok = 0; var bad = 0; var dead = 0
        for (i in 0 until n) { val c = kcode[i.coerceAtMost(31)]; if (c == 200) ok++ else if (c != -1) { bad++; if (c in intArrayOf(400, 401, 403, 404)) dead++ } }
        val dot: String; val msg: String
        if (n == 0) { dot = "🔴"; msg = "مفيش مفاتيح" }
        else if (ok == 0 && bad == 0) { dot = "⚪"; msg = "بيتجرّب…" }
        else if (ok == 0) { dot = "🔴"; msg = "المفاتيح واقفة" }
        else if (bad > 0) { dot = "🟡"; msg = "$ok من $n شغّالة" }
        else { dot = "🟢"; msg = "$n مفاتيح شغّالة" }
        statusTv.text = "$dot $msg · اضغط للتفاصيل"
    }

    private fun testKeys(reopen: Boolean) {
        val ks = keys(); val mdl = model()
        Thread {
            for (i in ks.indices) {
                val k = ks[i]
                val code = try { Api.generate(mdl, k, "رد بكلمة ok فقط", null, 16, 0.0, false, false); 200 } catch (e: ApiErr) { e.code } catch (_: NoKey) { 401 } catch (_: Throwable) { 0 }
                kcode[i.coerceAtMost(31)] = code
                h.post { updateStatus() }
            }
            h.post { updateStatus(); if (reopen) showKeys() }
        }.start()
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

    private var keysDlg: Dialog? = null
    private fun showKeys() {
        try { keysDlg?.dismiss() } catch (_: Throwable) {}
        val ks = keys()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.text("الموديل: " + model(), 12.5f, th.muted).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)) })
        if (ks.isEmpty()) box.addView(ui.text("🔴 مفيش مفاتيح. ضيف من الإعدادات ← المفاتيح.", 13.5f, th.text))
        for (i in ks.indices) {
            val k = ks[i]
            val tail = if (k.length > 8) "…" + k.takeLast(4) else ""
            val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); background = ui.box(th.card, th.border, 12) }
            row.addView(ui.text("مفتاح " + (i + 1) + " " + tail, 13.5f, th.text, true))
            row.addView(ui.text(codeLabel(kcode[i.coerceAtMost(31)]), 12f, th.muted))
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        }
        lateinit var dlg: Dialog
        if (ks.isNotEmpty()) box.addView(ui.button("🔍 اختبار كل المفاتيح", true) { for (i in ks.indices) kcode[i.coerceAtMost(31)] = -1; updateStatus(); dlg.dismiss(); testKeys(true); Notice.show(act, "بيتجرّب المفاتيح…", 1800L) })
        dlg = ui.sheet(act, "🔑 حالة المفاتيح", listOf<View>(box), false)
        keysDlg = dlg
        dlg.show()
    }

    // ===== الإرسال =====
    private fun send(textIn: String) {
        var t = textIn.trim()
        val hasAtt = attData != null || attText != null
        if (t.isEmpty() && !hasAtt) return
        if (busy) { Notice.show(act, "استنى الرد يخلص", 1500L); return }
        if (keys().isEmpty()) { Notice.show(act, "ضيف مفتاح جيميناي من الإعدادات ← المفاتيح", 3000L); return }
        if (t.isEmpty()) t = "حلّل المرفق ده وقولي أهم اللي فيه."
        val shown = if (hasAtt) ("📎 " + attName + "\n" + t) else t
        val effective = if (attText != null) (t + "\n\n--- محتوى الملف «" + attName + "» ---\n" + attText) else t
        val mime = attMime; val data = attData
        val c = cur ?: AiChat("c" + System.currentTimeMillis(), "", System.currentTimeMillis(), ArrayList()).also { cur = it; col.removeAllViews() }
        if (c.title.isEmpty()) c.title = t.take(40)
        c.msgs.add("u" to shown); persist(c)
        col.addView(bubble("u", shown))
        val wait = bubble("a", "⏳ بيفكّر…"); col.addView(wait)
        input.setText(""); clearAtt(); toBottom()
        try { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0) } catch (_: Throwable) {}
        busy = true
        val hist = ArrayList<Pair<String, String>>()
        for (m in c.msgs.takeLast(14)) hist.add(m)
        if (hist.isNotEmpty()) hist[hist.size - 1] = "u" to effective
        Thread {
            var reply: String? = null; var err = ""
            try { reply = ask(hist, if (data != null) mime else null, data) } catch (e: Throwable) { err = friendly(e) }
            h.post {
                busy = false
                updateStatus()
                col.removeView(wait)
                if (reply != null) { c.msgs.add("a" to reply); persist(c); col.addView(bubble("a", reply)) }
                else col.addView(bubble("a", "⚠ " + err))
                toBottom()
            }
        }.start()
    }

    private fun turnsOf(hist: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in hist) {
            val r = if (m.first == "u") "user" else "model"
            if (out.isEmpty() && r == "model") continue
            if (out.isNotEmpty() && out.last().first == r) out[out.size - 1] = r to (out.last().second + "\n" + m.second) else out.add(r to m.second)
        }
        return out
    }

    private class KeyFail(val msg: String) : Exception(msg)

    /** بيلف على مفاتيح التطبيق: أي مفتاح يفشل (404/400/401/403/429/5xx/نت) بيعيد نفس الرسالة تلقائيًا على المفتاح اللي بعده. المفتاح المرفوض (4xx) بيتسيب لباقي الإرسال، والمضغوط/النت بيتعاد في دورات (3). نفس الموديل بس. */
    private fun ask(hist: List<Pair<String, String>>, mime: String?, data: ByteArray?): String {
        val ks = keys(); if (ks.isEmpty()) throw NoKey()
        val mdl = model(); val turns = turnsOf(hist)
        val dead = BooleanArray(ks.size); val why = arrayOfNulls<String>(ks.size)
        var lastCode = -2
        for (round in 0 until 3) {
            var tried = 0
            for (j in ks.indices) {
                val idx = (keyStart + j) % ks.size
                if (dead[idx]) continue
                if (data != null && (FreeLlm.isWrapped(ks[idx]) || FreeLlm.isKey(ks[idx]))) { dead[idx] = true; why[idx] = "ما بيدعمش مرفقات"; continue }
                tried++
                try {
                    val r = if (data == null && (FreeLlm.isWrapped(ks[idx]) || FreeLlm.isKey(ks[idx])))
                        Api.generate(mdl, ks[idx], flat(turns), null, 2048, 0.7, false, false)
                    else Api.generateChat(mdl, ks[idx], SYSTEM, turns, mime, data, 2048, 0.7)
                    if (r.text.isNotBlank()) { keyStart = idx + 1; kcode[idx.coerceAtMost(31)] = 200; return r.text.trim() }
                    why[idx] = "رد فاضي" + (if (r.finish.isNotEmpty()) " (" + r.finish + ")" else ""); lastCode = -3
                } catch (e: NoKey) { throw e } catch (e: ApiErr) {
                    kcode[idx.coerceAtMost(31)] = e.code; lastCode = e.code
                    why[idx] = "" + e.code + " " + e.raw.take(60)
                    if (e.code in intArrayOf(400, 401, 403, 404)) dead[idx] = true
                } catch (e: Throwable) {
                    kcode[idx.coerceAtMost(31)] = 0; lastCode = 0
                    why[idx] = (e.message ?: e.javaClass.simpleName).take(60)
                }
            }
            if (tried == 0) break
            if (round < 2) try { Thread.sleep(1500L * (round + 1)) } catch (_: Throwable) {}
        }
        val sb = StringBuilder()
        for (i in ks.indices) if (why[i] != null) sb.append("\n• مفتاح ").append(i + 1).append(": ").append(why[i])
        val head = when {
            lastCode == 503 || lastCode == 500 || lastCode == 502 || lastCode == 504 -> "الموديل مضغوط من جوجل على كل المفاتيح. جرّب بعد شوية."
            lastCode == 429 -> "كل المفاتيح وصلت للحد دلوقتي. جرّب بعد شوية."
            lastCode == 404 -> "الموديل (" + mdl + ") رجّع 404 على كل المفاتيح."
            else -> "كل المفاتيح فشلت."
        }
        throw KeyFail(head + sb.toString())
    }

    private fun flat(turns: List<Pair<String, String>>): String {
        val sb = StringBuilder(SYSTEM).append("\n\n")
        for (m in turns) sb.append(if (m.first == "user") "المستخدم: " else "المساعد: ").append(m.second).append("\n")
        return sb.append("المساعد:").toString()
    }

    private fun friendly(e: Throwable): String = when {
        e is NoKey -> "مفيش مفتاح جيميناي. ضيف واحد من الإعدادات ← المفاتيح."
        e is KeyFail -> e.msg
        e is ApiErr && e.code == 503 -> "الموديل مضغوط من جوجل (503). جرّب تاني بعد شوية."
        e is ApiErr && e.code == 429 -> "وصلت للحد دلوقتي (429). جرّب بعد شوية."
        else -> (e.message ?: "حصل خطأ").take(160)
    }

    // ===== المايك =====
    private fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "اتكلم…")
            act.startActivityForResult(i, REQ_VOICE)
        } catch (e: Throwable) { Notice.show(act, "مفيش خدمة تعرّف على الكلام على الجهاز", 2500L) }
    }

    fun voiceResult(t: String) {
        val cur0 = input.text.toString()
        val n = if (cur0.isBlank()) t else cur0.trimEnd() + " " + t
        input.setText(n); input.setSelection(n.length); input.requestFocus()
    }
}
