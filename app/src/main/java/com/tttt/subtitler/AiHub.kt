package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v364) 🤖 شاشة الذكاء الاصطناعي: 💬 نص · 💻 كود · 🖼 صورة · 📎 ملف — بأي مزوّد بمفتاحك (مفاتيح جيميناي الحالية).
 * الموديلات بتتجاب من GET {url}/models، ووصف تخصص كل موديل بيتخمّن من اسمه (مش من الخدمة) — فهو إرشاد مش ضمان.
 */
object AiModels {
    class Info(val id: String, val owner: String, val ctx: Int, val tags: List<String>, val desc: String) {
        val has: (String) -> Boolean = { t -> tags.contains(t) }
    }

    private val skipRx = Regex("embed|whisper|tts|rerank|moderation|dall|transcribe|speech|realtime|audio|imagen|stable-diffusion|flux|sdxl", RegexOption.IGNORE_CASE)
    fun isChat(id: String) = !skipRx.containsMatchIn(id)

    fun describe(rawId: String, owner: String, ctx: Int): Info {
        val id = rawId.removePrefix("models/")
        val l = id.lowercase()
        val tags = ArrayList<String>()
        if (Regex("cod(er|ex|e)\\b|codestral|devstral|starcoder|deepseek-coder|-code|/code|coding").containsMatchIn(l)) tags.add("كود")
        if (Regex("vision|-vl|vl-|4o|gpt-4\\.1|gpt-5|gemini|claude|llava|pixtral|llama-?4|qwen.*vl|omni|grok").containsMatchIn(l)) tags.add("صور")
        if (Regex("\\br1\\b|reason|thinking|think|\\bo1|\\bo3|\\bo4|opus|gpt-5|pro\\b|deepseek-v3\\.2|qwq|magistral").containsMatchIn(l)) tags.add("تفكير")
        if (Regex("flash|mini|haiku|lite|small|nano|instant|\\b(1|3|7|8|9)b\\b|turbo|fast|free").containsMatchIn(l)) tags.add("سريع")
        if (Regex("opus|sonnet|\\bpro\\b|large|405b|235b|70b|72b|gpt-5|gpt-4\\.1(?!-mini)|kimi|glm-?[45]|deepseek-v3|k2|k3").containsMatchIn(l)) tags.add("قوي")
        if (Regex("arab|aya|jais|allam|command-r|qwen|gemini|claude|gpt|llama-3\\.[1-3]|mistral-(large|medium)").containsMatchIn(l)) tags.add("عربي كويس")
        if (l.contains("fable")) { tags.addAll(listOf("كود", "قوي", "تفكير")) }
        if (l.contains("opus")) { tags.add("كود") }
        if (l.contains("sonnet")) { tags.addAll(listOf("كود", "قوي")) }
        if (l.contains("haiku")) { tags.add("سريع") }
        val uniq = tags.distinct(); tags.clear(); tags.addAll(uniq)
        val d = StringBuilder()
        when {
            else -> {
                if ("كود" in tags) d.append("متخصص في البرمجة. ")
                if ("تفكير" in tags && "كود" !in tags) d.append("كويس في الاستدلال والمسائل الصعبة. ")
                if ("سريع" in tags && "قوي" !in tags) d.append("سريع وخفيف للمهام اليومية والترجمة. ")
                if ("قوي" in tags) d.append("موديل كبير: جودة أعلى وأبطأ/أغلى غالبًا. ")
                if (d.isEmpty()) d.append("موديل عام للنصوص. ")
                if ("صور" in tags) d.append("غالبًا بيفهم الصور. ")
            }
        }
        if (ctx > 0) d.append(" · سياق ${if (ctx >= 1000000) "${ctx / 1000000}M" else "${ctx / 1000}K"}")
        return Info(id, owner, ctx, tags, d.toString().trim())
    }
}

object AiApi {
    class Src(val id: String, val label: String, val kind: String, val url: String, val key: String, val defModel: String, val preset: ExtLlm.Preset?)

    /** (v395) المصدر الوحيد: جيميناي بمفاتيحك */
    fun sources(): List<Src> = realSources()

    fun realSources(): List<Src> {
        val out = ArrayList<Src>()
        Cfg.allMainKeys().firstOrNull()?.let { out.add(Src("gemini", "Gemini (مفاتيحك الحالية)", "openai", "https://generativelanguage.googleapis.com/v1beta/openai", it, "gemini-flash-latest", null)) }
        return out
    }

    /** سبب مفهوم بالعربي لأي استثناء شبكة/خدمة */
    fun explain(e: Throwable): String = when (e) {
        is java.net.ConnectException -> "مفيش اتصال بالخدمة"
        is java.net.UnknownHostException -> "العنوان مش موجود أو مفيش نت"
        is java.net.SocketTimeoutException -> "مهلة — الخدمة مردّتش في الوقت"
        is ExtLlm.LlmErr -> e.message ?: ("HTTP " + e.code)
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun hdr(s: Src): Map<String, String> =
        if (s.kind == "anthropic") mapOf("x-api-key" to s.key, "anthropic-version" to "2023-06-01") else if (s.key.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer ${s.key}")

    private fun http(method: String, url: String, h: Map<String, String>, body: String?, readMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = readMs
            // (v393) أخطاء الشبكة (سيرفر واقف/مفيش نت/مهلة) بتتحوّل لرسالة عربي مفهومة بدل «Connection refused» الخام
            val code = try {
                for ((k, v) in h) c.setRequestProperty(k, v)
                if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
                c.responseCode
            } catch (e: ExtLlm.LlmErr) { throw e } catch (e: java.io.IOException) { throw ExtLlm.LlmErr(0, explain(e) + " — " + url.substringAfter("://").substringBefore('/')) }
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw ExtLlm.LlmErr(code, ExtLlm.errMsg(code, txt), c.getHeaderField("Retry-After")?.trim()?.toIntOrNull() ?: 0)
            return txt
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }

    fun models(s: Src): List<AiModels.Info> {
        val txt = http("GET", s.url + "/models" + (if (s.kind == "anthropic") "?limit=100" else ""), hdr(s), null, 30000)
        val arr = JSONObject(txt).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<AiModels.Info>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").takeIf { it.isNotBlank() } ?: continue
            val ctx = listOf("context_length", "context_window", "contextWindow", "max_context_length", "input_token_limit").map { o.optInt(it, 0) }.firstOrNull { it > 0 } ?: 0
            out.add(AiModels.describe(id, o.optString("owned_by", o.optString("provider", id.substringBefore('/', ""))), ctx))
        }
        return out
    }

    /** رسالة: role + نص + صورة اختيارية (base64 JPEG) */
    class Msg(val role: String, val text: String, val imgB64: String? = null)

    fun chat(s: Src, model: String, system: String, msgs: List<Msg>, maxTokens: Int = 4096): String {
        val arr = JSONArray()
        val body = JSONObject().put("model", model.removePrefix(if (s.id == "gemini") "models/" else "")).put("max_tokens", maxTokens)
        if (s.kind == "anthropic") {
            body.put("system", system)
            for (m in msgs) {
                val content: Any = if (m.imgB64 == null) m.text else JSONArray()
                    .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", m.imgB64)))
                    .put(JSONObject().put("type", "text").put("text", m.text))
                arr.put(JSONObject().put("role", m.role).put("content", content))
            }
            body.put("messages", arr)
            val r = JSONObject(http("POST", s.url + "/messages", hdr(s), body.toString(), 120000))
            val c = r.optJSONArray("content") ?: return ""
            return (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text", "") ?: "" }.trim()
        }
        arr.put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) {
            val content: Any = if (m.imgB64 == null) m.text else JSONArray()
                .put(JSONObject().put("type", "text").put("text", m.text))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + m.imgB64)))
            arr.put(JSONObject().put("role", m.role).put("content", content))
        }
        body.put("messages", arr)
        val r = JSONObject(http("POST", s.url + "/chat/completions", hdr(s), body.toString(), 120000))
        val msg = r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val c = msg.opt("content")
        return when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text", "") ?: "" }
            else -> ""
        }.trim()
    }
}

class AiHubActivity : Activity() {
    private lateinit var th: Theme
    private lateinit var ui: Ui
    private var src: AiApi.Src? = null
    private var model = ""
    private var mode = 0                       // 0 نص · 1 كود · 2 صورة · 3 ملف
    private var lang = "أي لغة"
    private val hist = Array(4) { ArrayList<AiApi.Msg>() }
    private var imgB64: String? = null
    private var imgBmp: Bitmap? = null
    private var fileUri: Uri? = null
    private var pendingSave = ""
    private var busy = false
    private var cache = HashMap<String, List<AiModels.Info>>()

    private lateinit var srcBtn: android.widget.Button
    private lateinit var modelBtn: android.widget.Button
    private lateinit var body: LinearLayout
    private lateinit var input: EditText
    private lateinit var fileBox: EditText
    private lateinit var output: EditText
    private lateinit var thumb: ImageView
    private lateinit var info: android.widget.TextView
    private lateinit var sendBtn: android.widget.Button

    private val modeNames = listOf("💬 نص", "💻 كود", "🖼 صورة", "📎 ملف")
    private val ext = mapOf("Kotlin" to "kt", "JavaScript/HTML" to "html", "Python" to "py", "Java" to "java", "Bash" to "sh", "أي لغة" to "txt")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.ensure(this)
        th = Themes.byId(try { Cfg.str("theme", "mx") } catch (_: Exception) { "mx" })
        ui = Ui(this, th); applyBars(th)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg); setPadding(ui.dp(10), ui.dp(30), ui.dp(10), ui.dp(6)) }
        val top = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.text("🤖 الذكاء الاصطناعي", 18f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.button("✕") { finish() }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(48), -2) })
        root.addView(top)
        srcBtn = ui.button("") { pickSource() }; modelBtn = ui.button("") { pickModelDialog() }
        val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        row.addView(srcBtn, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(modelBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        root.addView(ui.chips(modeNames, { modeNames[mode] }) { mode = modeNames.indexOf(it); buildBody() })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        // (v364) الشريط السفلي: 🤖 نشطة؛ باقي البوابات بتفتح شاشتها وتقفل دي
        val gates = BottomNav(this, ui, th, 6) { i ->
            if (i != 6) {
                NavAnim.set(6, i)
                if (i == 2) startActivity(Intent(this, BrowserActivity::class.java))
                else if (i == 3) startActivity(Intent(this, FileManagerActivity::class.java))
                else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", if (i == 0) "videos" else if (i == 1) "music" else if (i == 4) "youtube" else "tasks"))
                NavAnim.peek(this)
                finish()
            }
        }
        root.addView(gates.view, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        reloadSrc(); buildBody()
    }

    override fun onResume() {
        super.onResume()
        if (::srcBtn.isInitialized) {
            reloadSrc()
        }
    }

    private fun reloadSrc() {
        val all = AiApi.sources()
        val keep = Cfg.str("aihub_src")
        val prevId = src?.id ?: keep
        src = all.firstOrNull { it.id == src?.id } ?: all.firstOrNull { it.id == keep } ?: all.firstOrNull()
        // (v393) لو المصدر اللي كنت مختاره اختفى مش هيتحوّل في السر
        if (prevId.isNotEmpty() && src?.id != prevId && all.none { it.id == prevId }) toast("⚠ المصدر «$prevId» مش متاح دلوقتي ← اتحوّلت لـ «" + (src?.label ?: "؟") + "»", 5000L)
        model = src?.let { Cfg.str("aihub_model_" + it.id).ifEmpty { autoModel(it) } } ?: ""
        srcBtn.text = "🔌 " + (src?.label ?: "ضيف مزوّد"); setModelLabel()
    }

    private fun autoModel(s: AiApi.Src): String = s.defModel
    private fun setModelLabel() {
        val s = src
        modelBtn.text = "🧠 " + (if (s == null) "اختار موديل" else Cfg.str("aihub_model_" + s.id).let { if (it.isBlank() || it == "auto") "تلقائي" else it }) + " ▾"
    }

    private fun toast(m: String, ms: Long = 2600L) = Notice.show(this, m, ms)

    private fun pickSource() {
        val d = GDialog(this)
        val box = dlgBox()
        box.addView(ui.text("🔌 المصدر", 16f, th.primary, true))
        val all = AiApi.sources()
        if (all.isEmpty()) box.addView(ui.text("لسه مفيش مفتاح. ضيف مفتاح جيميناي من الإعدادات ← 🔑 المفاتيح.", 12f, th.muted))
        for (s in all) box.addView(ui.button((if (s.id == src?.id) "✅ " else "") + s.label) { src = s; Cfg.put("aihub_src", s.id); d.dismiss(); reloadSrc() })
        box.addView(ui.button("إغلاق") { d.dismiss() })
        showDlg(d, box)
    }

    private fun dlgBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.box(th.card, th.border, 18)
    }
    private fun showDlg(d: GDialog, box: View) {
        d.setContentView(ScrollView(this).apply { addView(box) })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); d.show()
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** (v374) زرار الموديل: قايمة صغيرة منسدلة (تلقائي + كل الموديلات اللي اتجابت) بدل الشاشة الكاملة؛ البحث والتفاصيل في آخرها */
    private fun pickModelDialog() {
        val s = src ?: run { toast("ضيف مزوّد الأول"); return }
        val cached = cache[s.id]
        if (cached != null) { modelDrop(s, cached); return }
        modelBtn.text = "⏳ بجيب الموديلات…"
        Thread {
            val r = runCatching { AiApi.models(s) }
            runOnUiThread {
                setModelLabel()
                val l = r.getOrNull()
                if (l == null) { toast("❌ " + AiApi.explain(r.exceptionOrNull() ?: Exception("فشل")).take(200), 6000L); return@runOnUiThread }
                val all = l.filter { AiModels.isChat(it.id) }
                cache[s.id] = all; modelDrop(s, all)
            }
        }.start()
    }

    private fun modelDrop(s: AiApi.Src, all: List<AiModels.Info>) {
        val cur = Cfg.str("aihub_model_" + s.id).let { if (it == "auto") "" else it }
        AiDrop.dropdown(this, ui, th, modelBtn, all.map { it.id }.filter { it != "auto" }, cur, "تلقائي", "🔍 بحث وتفاصيل الموديلات…" to { modelList(s, all) }) { v ->
            Cfg.put("aihub_model_" + s.id, v); model = v.ifEmpty { autoModel(s) }; setModelLabel()
            if (mode == 2 && v.isNotEmpty() && all.firstOrNull { it.id == v }?.let { "صور" !in it.tags && !v.startsWith("auto") } == true) toast("⚠ الموديل ده غالبًا مش بيشوف صور")
        }
    }

    private fun modelList(s: AiApi.Src, all: List<AiModels.Info>) {
        val d = GDialog(this); val box = dlgBox()
        box.addView(ui.text("🧠 الموديلات (${all.size})", 16f, th.primary, true))
        box.addView(ui.text("التخصص متخمّن من اسم الموديل — جرّب قبل ما تعتمد عليه.", 11.5f, th.muted))
        val search = ui.input("🔍 ابحث بالاسم", "", 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        var tag = "الكل"
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            val pref = when (mode) { 1 -> "كود"; 2 -> "صور"; else -> "" }
            val f = all.filter { (tag == "الكل" || tag in it.tags) && (q.isEmpty() || it.id.lowercase().contains(q)) }
                .sortedByDescending { if (pref.isNotEmpty() && pref in it.tags) 1 else 0 }
            for (m in f.take(60)) list.addView(ui.button(m.id + "\n" + (if (m.tags.isNotEmpty()) m.tags.joinToString(" · ") + "\n" else "") + m.desc) {
                model = m.id; Cfg.put("aihub_model_" + s.id, m.id); setModelLabel(); d.dismiss()
                if (mode == 2 && "صور" !in m.tags && !m.id.startsWith("auto")) toast("⚠ الموديل ده غالبًا مش بيشوف صور")
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 12.5f })
            if (f.size > 60) list.addView(ui.text("… و${f.size - 60} كمان — اكتب في البحث عشان تضيّق", 11.5f, th.muted))
            if (f.isEmpty()) list.addView(ui.text("مفيش نتايج", 12f, th.muted))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { fill() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        box.addView(search)
        box.addView(ui.chips(listOf("الكل", "كود", "صور", "تفكير", "سريع", "قوي", "عربي كويس"), { tag }) { tag = it; fill() })
        box.addView(ui.text("اختصار بالاسم:", 11.5f, th.muted))
        box.addView(ui.chips(listOf("—", "flash", "pro", "lite", "gemini"), { "" }) { search.setText(if (it == "—") "" else it) })
        box.addView(list); fill()
        box.addView(ui.button("✏ اكتب اسم موديل يدوي") {
            val e = ui.input("اسم الموديل", model, 1).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
            val d2 = GDialog(this); val b2 = dlgBox(); b2.addView(e)
            b2.addView(ui.button("حفظ", true) { val v = e.text.toString().trim(); if (v.isNotEmpty()) { model = v; Cfg.put("aihub_model_" + s.id, v); setModelLabel() }; d2.dismiss(); d.dismiss() })
            showDlg(d2, b2)
        })
        box.addView(ui.button("🔄 حدّث القايمة") { cache.remove(s.id); d.dismiss(); pickModelDialog() })
        box.addView(ui.button("إغلاق") { d.dismiss() })
        showDlg(d, box)
    }

    // ===== جسم الشاشة حسب الوضع =====
    private fun buildBody() {
        body.removeAllViews()
        val prevOut = if (::output.isInitialized) output.text.toString() else ""
        input = ui.input(when (mode) { 1 -> "اوصف الكود المطلوب أو الصق كودك وقول عاوز إيه"; 2 -> "اسأل عن الصورة"; 3 -> "التعليمات (مثلًا: لخّص الملف)"; else -> "اكتب رسالتك" }, "", 4)
        fileBox = ui.input("محتوى الملف (تقدر تعدّله قبل الإرسال)", "", 6).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        output = ui.input("الرد هيظهر هنا — تقدر تعدّله بإيدك", prevOut, 8)
        thumb = ImageView(this).apply { adjustViewBounds = true; maxHeight = ui.dp(180); setImageBitmap(imgBmp); visibility = if (imgB64 == null) View.GONE else View.VISIBLE }
        info = ui.text("", 11.5f, th.muted)
        if (mode == 1) body.addView(ui.chips(ext.keys.toList(), { lang }) { lang = it })
        if (mode == 2) {
            body.addView(ui.button("📷 اختار صورة") { pick("image/*", 1) })
            body.addView(thumb)
            body.addView(ui.chips(listOf("وصف الصورة", "استخرج النص", "ترجم النص لعربي"), { "" }) {
                input.setText(when (it) { "وصف الصورة" -> "اوصف الصورة بالتفصيل."; "استخرج النص" -> "استخرج كل النص الموجود في الصورة كما هو."; else -> "ترجم كل النص الموجود في الصورة للعربي." })
            })
        }
        if (mode == 3) {
            body.addView(ui.button("📎 حمّل ملف نصي/كود") { pick("*/*", 2) })
            body.addView(info); body.addView(fileBox)
            body.addView(ui.button("💾 احفظ التعديل في الملف الأصلي") { saveBack() })
            body.addView(ui.chips(listOf("لخّص", "ترجم لعربي", "صحّح الأخطاء", "اشرح الكود"), { "" }) { input.setText(it) })
        }
        body.addView(input)
        sendBtn = ui.button("▶ إرسال", true) { send() }; body.addView(sendBtn)
        body.addView(output)
        val r = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun small(t: String, f: () -> Unit) = r.addView(ui.button(t) { f() }, LinearLayout.LayoutParams(0, -2, 1f))
        small("📋 نسخ") { copy(output.text.toString()) }
        small("💾 حفظ كملف") { saveAs() }
        small("↩ للإدخال") { input.setText(output.text.toString()) }
        small("🗑 جديد") { hist[mode].clear(); output.setText(""); toast("بدأنا محادثة جديدة", 1400L) }
        body.addView(r)
        if (mode == 1) body.addView(ui.button("{ } انسخ أول بلوك كود فقط") { copy(Regex("```[a-zA-Z+#]*\\n([\\s\\S]*?)```").find(output.text.toString())?.groupValues?.get(1) ?: output.text.toString()) })
    }

    private fun systemFor(): String = when (mode) {
        1 -> "You are an expert software engineer. Answer with correct, runnable code in fenced code blocks (language: ${if (lang == "أي لغة") "choose the best fit" else lang}) followed by a short explanation in the user's language."
        2 -> "You analyze images accurately. Reply in the user's language."
        3 -> "You help the user work with the file content they provide. Follow the instruction exactly; when asked to edit or fix, return the full updated content. Reply in the user's language."
        else -> "You are a helpful assistant. Reply in the user's language."
    }

    private fun send() {
        val s = src ?: run { toast("ضيف مزوّد الأول"); return }
        if (model.isBlank()) { toast("اختار موديل الأول"); return }
        if (busy) return
        val q = input.text.toString().trim()
        if (q.isEmpty() && mode != 2) { toast("اكتب حاجة الأول"); return }
        if (mode == 2 && imgB64 == null) { toast("اختار صورة الأول"); return }
        val text = if (mode == 3) q + "\n\n--- FILE ---\n" + fileBox.text.toString().take(60000) else q.ifEmpty { "اوصف الصورة." }
        val h = hist[mode]
        val keepHist = mode == 0 || mode == 1
        val msgs = ArrayList<AiApi.Msg>(if (keepHist) h else emptyList())
        msgs.add(AiApi.Msg("user", text, if (mode == 2) imgB64 else null))
        busy = true; sendBtn.text = "⏳ بيفكر…"; sendBtn.isEnabled = false
        Thread {
            val r = runCatching { AiApi.chat(s, model, systemFor(), msgs.takeLast(20)) }
            runOnUiThread {
                busy = false; sendBtn.text = "▶ إرسال"; sendBtn.isEnabled = true
                val a = r.getOrNull()
                if (a == null) { toast("❌ " + s.label.substringBefore(" (") + ": " + AiApi.explain(r.exceptionOrNull() ?: Exception("فشل")).take(260), 8000L); return@runOnUiThread }
                if (keepHist) { h.add(AiApi.Msg("user", text)); h.add(AiApi.Msg("assistant", a)); input.setText("") }
                output.setText(a.ifEmpty { "(رد فاضي)" })
            }
        }.start()
    }

    private fun copy(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ai", t)); toast("✅ اتنسخ", 1200L)
    }

    private fun pick(mime: String, code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        try { @Suppress("DEPRECATION") startActivityForResult(i, code) } catch (e: Exception) { toast("مفيش مدير ملفات متاح") }
    }

    private fun saveAs() {
        val t = output.text.toString(); if (t.isBlank()) { toast("مفيش حاجة تتحفظ"); return }
        pendingSave = t
        val e = if (mode == 1) (ext[lang] ?: "txt") else "txt"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "ai_output.$e") }
        @Suppress("DEPRECATION") startActivityForResult(i, 3)
    }

    private fun saveBack() {
        val u = fileUri ?: run { toast("حمّل ملف الأول"); return }
        try { contentResolver.openOutputStream(u, "wt")?.use { it.write(fileBox.text.toString().toByteArray(Charsets.UTF_8)) }; toast("✅ اتحفظ في الملف الأصلي") }
        catch (e: Exception) { toast("❌ مقدرتش أكتب في الملف ده — استخدم «حفظ كملف»", 4000L) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        val u = d?.data ?: return
        if (c != RESULT_OK) return
        try {
            when (r) {
                1 -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                    var ss = 1; while (maxOf(o.outWidth, o.outHeight) / ss > 1600) ss *= 2
                    val bmp = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) } ?: throw Exception("الصورة مش مقروءة")
                    val bo = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo)
                    imgB64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                    imgBmp = bmp; thumb.setImageBitmap(bmp); thumb.visibility = View.VISIBLE
                }
                2 -> {
                    try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                    val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw Exception("الملف مش مقروء")
                    if (bytes.take(2000).any { it.toInt() == 0 }) throw Exception("الملف ده مش نصي (باينري)")
                    fileUri = u; fileBox.setText(String(bytes, Charsets.UTF_8))
                    info.text = "📄 ${u.lastPathSegment ?: "ملف"} · ${bytes.size / 1024} KB" + (if (bytes.size > 60000) " — هيتبعت أول 60,000 حرف بس" else "")
                }
                3 -> { contentResolver.openOutputStream(u, "wt")?.use { it.write(pendingSave.toByteArray(Charsets.UTF_8)) }; toast("✅ اتحفظ") }
            }
        } catch (e: Exception) { toast("❌ " + (e.message ?: "فشل").take(100), 4000L) }
    }
}

object AiDrop {
    fun dropdown(act: Activity, ui: Ui, th: Theme, anchor: View, items: List<String>, current: String, autoLabel: String, footer: Pair<String, () -> Unit>? = null, onPick: (String) -> Unit) {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(th.card, th.border, 14)
        }
        lateinit var pw: android.widget.PopupWindow
        fun row(label: String, on: Boolean, v: String) {
            col.addView(IconTextView(act).apply {
                text = (if (on) "✓ " else "") + label; textSize = 14f; setTextColor(if (on) th.primary else th.text)
                minHeight = ui.dp(40); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
                setOnClickListener { pw.dismiss(); onPick(v) }
            })
        }
        row(autoLabel, current.isBlank(), "")
        for (m in items) row(m, m == current, m)
        footer?.let { f -> col.addView(IconTextView(act).apply { text = f.first; textSize = 13f; setTextColor(th.primary); minHeight = ui.dp(40); gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START; setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); setOnClickListener { pw.dismiss(); f.second() } }) }
        val scroll = MaxHeightScroll(act, (act.resources.displayMetrics.heightPixels * 0.45f).toInt()).apply { addView(col) }
        pw = android.widget.PopupWindow(scroll, anchor.width.coerceAtLeast(ui.dp(240)), -2, true)
        pw.setBackgroundDrawable(ColorDrawable(0)); pw.elevation = ui.dp(8).toFloat()
        try { pw.showAsDropDown(anchor, 0, ui.dp(2)) } catch (_: Throwable) {}
    }
}
