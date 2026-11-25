package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
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
        @Volatile private var keyStart = 0
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
        ttl.addView(ui.text("✨ الذكاء الاصطناعي", 16f, th.text, true).apply { gravity = Gravity.CENTER })
        ttl.addView(ui.text(model() + " · " + keys().size + " مفاتيح", 10.5f, th.muted).apply { gravity = Gravity.CENTER })
        head.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("＋ جديدة") { newChat() })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(10)) }
        bar.addView(circle("➤", true) { send(input.text.toString()) })
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(circle("🎙", false) { startVoice() })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        renderEmpty()
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

    // ===== الإرسال =====
    private fun send(textIn: String) {
        val t = textIn.trim(); if (t.isEmpty()) return
        if (busy) { Notice.show(act, "استنى الرد يخلص", 1500L); return }
        if (keys().isEmpty()) { Notice.show(act, "ضيف مفتاح جيميناي من الإعدادات ← المفاتيح", 3000L); return }
        val c = cur ?: AiChat("c" + System.currentTimeMillis(), "", System.currentTimeMillis(), ArrayList()).also { cur = it; col.removeAllViews() }
        if (c.title.isEmpty()) c.title = t.take(40)
        c.msgs.add("u" to t); persist(c)
        col.addView(bubble("u", t))
        val wait = bubble("a", "⏳ بيفكّر…"); col.addView(wait)
        input.setText(""); toBottom()
        try { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0) } catch (_: Throwable) {}
        busy = true
        val prompt = buildPrompt(c)
        Thread {
            var reply: String? = null; var err = ""
            try { reply = ask(prompt) } catch (e: Throwable) { err = friendly(e) }
            h.post {
                busy = false
                col.removeView(wait)
                if (reply != null) { c.msgs.add("a" to reply); persist(c); col.addView(bubble("a", reply)) }
                else col.addView(bubble("a", "⚠ " + err))
                toBottom()
            }
        }.start()
    }

    private fun buildPrompt(c: AiChat): String {
        val sb = StringBuilder(SYSTEM).append("\n\n")
        for (m in c.msgs.takeLast(14)) sb.append(if (m.first == "u") "المستخدم: " else "المساعد: ").append(m.second).append("\n")
        sb.append("المساعد:")
        return sb.toString()
    }

    /** بيلف على مفاتيح التطبيق: لو مفتاح فشل (429/503/أي خطأ) بيجرّب اللي بعده، وبعد دورة كاملة بيستنى ويعيد (3 دورات). نفس الموديل بس. */
    private fun ask(prompt: String): String {
        val ks = keys(); if (ks.isEmpty()) throw NoKey()
        val mdl = model(); var last: Throwable? = null
        for (round in 0 until 3) {
            for (j in ks.indices) {
                val idx = (keyStart + j) % ks.size
                try {
                    val r = Api.generate(mdl, ks[idx], prompt, null, 2048, 0.7, false, false)
                    if (r.text.isNotBlank()) { keyStart = idx + 1; return r.text.trim() }
                } catch (e: NoKey) { throw e } catch (e: Throwable) { last = e }
            }
            if (round < 2) try { Thread.sleep(1500L * (round + 1)) } catch (_: Throwable) {}
        }
        throw last ?: Exception("الرد فاضي")
    }

    private fun friendly(e: Throwable): String = when {
        e is NoKey -> "مفيش مفتاح جيميناي. ضيف واحد من الإعدادات ← المفاتيح."
        e is ApiErr && e.code == 503 -> "الموديل مضغوط من جوجل (503) على كل المفاتيح. جرّب تاني بعد شوية."
        e is ApiErr && e.code == 429 -> "كل المفاتيح وصلت للحد دلوقتي (429). جرّب بعد شوية."
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
