package com.kidstories.app

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class Story(val id: Long, val title: String, val text: String)

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        const val API = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val stories = ArrayList<Story>()
    private var index = 0
    private var generating = false

    // views
    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var appTitle: TextView
    private lateinit var settingsBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var settingsScroll: ScrollView
    private lateinit var storyScroll: ScrollView
    private lateinit var counterTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var bodyTv: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var speakBtn: Button
    private lateinit var newBtn: Button
    private lateinit var keyEt: EditText
    private lateinit var ideaEt: EditText
    private lateinit var modelSp: Spinner
    private lateinit var ageSp: Spinner
    private lateinit var sizeLabel: TextView
    private lateinit var rateLabel: TextView

    private val modelList = ArrayList<String>()
    private lateinit var modelAdapter: ArrayAdapter<String>

    private val fontLabels = listOf("عادي واضح", "خط نسخ", "عريض مريح", "مستدير", "خط يد")
    private val themeLabels = listOf("فاتح", "كريمي", "ليلي")
    private val voiceLabels = listOf("صوت الموبايل (مجاني)", "جوجل TTS أونلاين (مجاني)")
    private val ageLabels = listOf("من 3 لـ 5 سنين", "من 6 لـ 8 سنين", "من 9 لـ 12 سنة")

    // speech
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var playToken = 0
    @Volatile private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kids_stories", MODE_PRIVATE)
        loadStories()
        index = prefs.getInt("index", 0).coerceIn(0, maxOf(0, stories.size - 1))
        buildUi()
        applyTheme()
        applyReading()
        showStory()
        initTts()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
        prefs.edit().putInt("index", index).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpeak()
        tts?.shutdown()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun makeBtn(label: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(14).toFloat()
        b.background = g
        b.setOnClickListener { onClick() }
        return b
    }

    private fun weighted(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, dp(52), 1f)
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        return lp
    }

    private fun label(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.setTextColor(Color.parseColor("#222222"))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(12), 0, dp(4))
        return tv
    }

    private fun spinner(items: List<String>): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        return s
    }

    private fun onSel(s: Spinner, f: (Int) -> Unit) {
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { f(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun onSeek(sb: SeekBar, f: (Int) -> Unit) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) f(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.fitsSystemWindows = true

        // top bar
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(Color.parseColor("#4B3F8F"))
        topBar.setPadding(dp(12), dp(6), dp(8), dp(6))
        appTitle = TextView(this)
        appTitle.text = "قصص الأطفال"
        appTitle.setTextColor(Color.WHITE)
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        appTitle.setTypeface(null, Typeface.BOLD)
        topBar.addView(appTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        settingsBtn = makeBtn("الإعدادات", Color.parseColor("#6B5FC7")) { toggleSettings() }
        topBar.addView(settingsBtn, LinearLayout.LayoutParams(dp(110), dp(44)))
        root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        statusTv = TextView(this)
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        statusTv.setPadding(dp(12), dp(4), dp(12), dp(4))
        statusTv.visibility = View.GONE
        root.addView(statusTv, LinearLayout.LayoutParams(-1, -2))

        // settings (hidden)
        settingsScroll = ScrollView(this)
        settingsScroll.setBackgroundColor(Color.parseColor("#ECE9F7"))
        settingsScroll.visibility = View.GONE
        val sp = LinearLayout(this)
        sp.orientation = LinearLayout.VERTICAL
        sp.setPadding(dp(16), dp(8), dp(16), dp(24))
        settingsScroll.addView(sp)

        sp.addView(label("مفتاح Gemini API"))
        keyEt = EditText(this)
        keyEt.setSingleLine()
        keyEt.setTextColor(Color.BLACK)
        keyEt.hint = "الصق المفتاح هنا"
        keyEt.setText(prefs.getString("key", ""))
        sp.addView(keyEt)

        sp.addView(label("الموديل"))
        val fetchBtn = makeBtn("جلب الموديلات", Color.parseColor("#2E7D6B")) { fetchModels() }
        sp.addView(fetchBtn, LinearLayout.LayoutParams(-1, dp(46)))
        val saved = prefs.getString("models", "") ?: ""
        saved.split("|").filter { it.isNotBlank() }.forEach { modelList.add(it) }
        if (!modelList.contains(DEFAULT_MODEL)) modelList.add(0, DEFAULT_MODEL)
        val cur = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        if (!modelList.contains(cur)) modelList.add(cur)
        modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelList)
        modelSp = Spinner(this)
        modelSp.adapter = modelAdapter
        modelSp.setSelection(modelList.indexOf(cur))
        sp.addView(modelSp)

        sp.addView(label("سن الطفل"))
        ageSp = spinner(ageLabels)
        ageSp.setSelection(prefs.getInt("age", 1).coerceIn(0, 2))
        sp.addView(ageSp)

        sp.addView(label("فكرة أو موضوع للقصة الجاية (اختياري)"))
        ideaEt = EditText(this)
        ideaEt.setTextColor(Color.BLACK)
        ideaEt.hint = "مثلاً: أرنب بيتعلم الصدق"
        ideaEt.setText(prefs.getString("idea", ""))
        sp.addView(ideaEt)

        sp.addView(label("الخط"))
        val fontSp = spinner(fontLabels)
        fontSp.setSelection(prefs.getInt("font", 0).coerceIn(0, fontLabels.size - 1))
        onSel(fontSp) { prefs.edit().putInt("font", it).apply(); applyReading() }
        sp.addView(fontSp)

        sizeLabel = label("")
        sp.addView(sizeLabel)
        val sizeSb = SeekBar(this)
        sizeSb.max = 24
        sizeSb.progress = prefs.getInt("size", 24) - 16
        sizeLabel.text = "حجم الخط: " + prefs.getInt("size", 24)
        onSeek(sizeSb) {
            prefs.edit().putInt("size", it + 16).apply()
            sizeLabel.text = "حجم الخط: " + (it + 16)
            applyReading()
        }
        sp.addView(sizeSb)

        sp.addView(label("الألوان"))
        val themeSp = spinner(themeLabels)
        themeSp.setSelection(prefs.getInt("theme", 1).coerceIn(0, 2))
        onSel(themeSp) { prefs.edit().putInt("theme", it).apply(); applyTheme() }
        sp.addView(themeSp)

        sp.addView(label("صوت القراءة"))
        val voiceSp = spinner(voiceLabels)
        voiceSp.setSelection(prefs.getInt("voice", 0).coerceIn(0, 1))
        onSel(voiceSp) { prefs.edit().putInt("voice", it).apply() }
        sp.addView(voiceSp)

        rateLabel = label("")
        sp.addView(rateLabel)
        val rateSb = SeekBar(this)
        rateSb.max = 100
        rateSb.progress = prefs.getInt("rate", 90) - 50
        rateLabel.text = "سرعة القراءة: " + prefs.getInt("rate", 90) + "%"
        onSeek(rateSb) {
            prefs.edit().putInt("rate", it + 50).apply()
            rateLabel.text = "سرعة القراءة: " + (it + 50) + "%"
        }
        sp.addView(rateSb)

        root.addView(settingsScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // story area
        storyScroll = ScrollView(this)
        val sc = LinearLayout(this)
        sc.orientation = LinearLayout.VERTICAL
        sc.setPadding(dp(18), dp(12), dp(18), dp(24))
        counterTv = TextView(this)
        counterTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        titleTv = TextView(this)
        titleTv.setTypeface(null, Typeface.BOLD)
        titleTv.setPadding(0, dp(6), 0, dp(10))
        bodyTv = TextView(this)
        for (t in listOf(counterTv, titleTv, bodyTv)) {
            t.textDirection = View.TEXT_DIRECTION_RTL
            t.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            sc.addView(t, LinearLayout.LayoutParams(-1, -2))
        }
        storyScroll.addView(sc)
        root.addView(storyScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // bottom bar
        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.setPadding(dp(6), dp(4), dp(6), dp(6))
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(makeBtn("السابقة", Color.parseColor("#5C6BC0")) { go(-1) }, weighted())
        speakBtn = makeBtn("اسمع القصة", Color.parseColor("#E07A1F")) { toggleSpeak() }
        row1.addView(speakBtn, weighted())
        row1.addView(makeBtn("التالية", Color.parseColor("#5C6BC0")) { go(1) }, weighted())
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        newBtn = makeBtn("قصة جديدة", Color.parseColor("#2E9B57")) { generate() }
        row2.addView(newBtn, weighted())
        row2.addView(makeBtn("حذف القصة", Color.parseColor("#C0392B")) { confirmDelete() }, weighted())
        bottomBar.addView(row1, LinearLayout.LayoutParams(-1, -2))
        bottomBar.addView(row2, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun toggleSettings() {
        val opening = settingsScroll.visibility != View.VISIBLE
        if (opening) {
            settingsScroll.visibility = View.VISIBLE
            storyScroll.visibility = View.GONE
            settingsBtn.text = "إغلاق"
        } else {
            saveSettings()
            settingsScroll.visibility = View.GONE
            storyScroll.visibility = View.VISIBLE
            settingsBtn.text = "الإعدادات"
        }
    }

    private fun applyTheme() {
        val t = prefs.getInt("theme", 1)
        val bg: Int
        val fg: Int
        when (t) {
            0 -> { bg = Color.parseColor("#FFFFFF"); fg = Color.parseColor("#1B1B1B") }
            2 -> { bg = Color.parseColor("#14171F"); fg = Color.parseColor("#E8E8E8") }
            else -> { bg = Color.parseColor("#F6EEDC"); fg = Color.parseColor("#3B2F1E") }
        }
        storyScroll.setBackgroundColor(bg)
        bottomBar.setBackgroundColor(bg)
        statusTv.setBackgroundColor(bg)
        statusTv.setTextColor(fg)
        titleTv.setTextColor(fg)
        bodyTv.setTextColor(fg)
        counterTv.setTextColor(fg)
    }

    private fun applyReading() {
        val size = prefs.getInt("size", 24).toFloat()
        val face = when (prefs.getInt("font", 0)) {
            0 -> Typeface.create("sans-serif", Typeface.NORMAL)
            1 -> Typeface.create("serif", Typeface.NORMAL)
            2 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            3 -> Typeface.create("casual", Typeface.NORMAL)
            else -> Typeface.create("cursive", Typeface.NORMAL)
        }
        bodyTv.typeface = face
        bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        bodyTv.setLineSpacing(0f, 1.5f)
        titleTv.typeface = Typeface.create(face, Typeface.BOLD)
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size + 6f)
    }

    private fun setStatus(msg: String?) {
        if (msg.isNullOrEmpty()) {
            statusTv.visibility = View.GONE
        } else {
            statusTv.text = msg
            statusTv.visibility = View.VISIBLE
        }
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("key", keyEt.text.toString().trim())
            .putString("model", modelSp.selectedItem?.toString() ?: DEFAULT_MODEL)
            .putInt("age", ageSp.selectedItemPosition)
            .putString("idea", ideaEt.text.toString().trim())
            .apply()
    }

    // ---------------------------------------------------------------- stories storage

    private fun storiesFile() = File(filesDir, "stories.json")

    private fun loadStories() {
        stories.clear()
        try {
            val f = storiesFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                stories.add(Story(o.getLong("id"), o.getString("title"), o.getString("text")))
            }
        } catch (e: Exception) {
        }
    }

    private fun saveStories() {
        try {
            val arr = JSONArray()
            for (s in stories) {
                arr.put(JSONObject().put("id", s.id).put("title", s.title).put("text", s.text))
            }
            storiesFile().writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    private fun showStory() {
        if (stories.isEmpty()) {
            counterTv.text = "لسه مفيش قصص"
            titleTv.text = ""
            bodyTv.text = "اضغط على «قصة جديدة» عشان نبدأ.\nقبلها افتح الإعدادات وحط مفتاح Gemini."
        } else {
            index = index.coerceIn(0, stories.size - 1)
            val s = stories[index]
            counterTv.text = "قصة " + (index + 1) + " من " + stories.size
            titleTv.text = s.title
            bodyTv.text = s.text
        }
        storyScroll.scrollTo(0, 0)
        prefs.edit().putInt("index", index).apply()
    }

    private fun go(d: Int) {
        if (stories.isEmpty()) return
        val n = index + d
        if (n < 0 || n >= stories.size) {
            setStatus(if (d < 0) "دي أول قصة" else "دي آخر قصة، اضغط قصة جديدة")
            ui.postDelayed({ setStatus(null) }, 2500)
            return
        }
        stopSpeak()
        index = n
        showStory()
    }

    private fun confirmDelete() {
        if (stories.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage("تمسح القصة دي؟")
            .setPositiveButton("امسح") { _, _ ->
                stopSpeak()
                stories.removeAt(index)
                if (index >= stories.size) index = maxOf(0, stories.size - 1)
                saveStories()
                showStory()
            }
            .setNegativeButton("لأ", null)
            .show()
    }

    // ---------------------------------------------------------------- Gemini

    private fun http(urlStr: String, body: String?, key: String): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.setRequestProperty("x-goog-api-key", key)
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(txt).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                txt.take(200)
            }
            throw Exception("خطأ $code: $msg")
        }
        return txt
    }

    private fun fetchModels() {
        saveSettings()
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) { setStatus("اكتب مفتاح Gemini الأول"); return }
        setStatus("بيجيب الموديلات...")
        Thread {
            try {
                val resp = http("$API?pageSize=1000", null, key)
                val arr = JSONObject(resp).getJSONArray("models")
                val names = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var ok = false
                    for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
                    if (ok) names.add(m.getString("name").removePrefix("models/"))
                }
                names.sort()
                ui.post {
                    val current = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
                    modelList.clear()
                    modelList.addAll(names)
                    if (!modelList.contains(DEFAULT_MODEL)) modelList.add(0, DEFAULT_MODEL)
                    if (!modelList.contains(current)) modelList.add(current)
                    modelAdapter.notifyDataSetChanged()
                    modelSp.setSelection(modelList.indexOf(current))
                    prefs.edit().putString("models", modelList.joinToString("|")).apply()
                    setStatus("تم، لقيت " + names.size + " موديل")
                    ui.postDelayed({ setStatus(null) }, 3000)
                }
            } catch (e: Exception) {
                ui.post { setStatus(e.message) }
            }
        }.start()
    }

    private fun buildPrompt(): String {
        val age = ageLabels[prefs.getInt("age", 1).coerceIn(0, 2)]
        val idea = prefs.getString("idea", "") ?: ""
        val old = stories.takeLast(30).joinToString("، ") { it.title }
        val sb = StringBuilder()
        sb.append("اكتب قصة أطفال جديدة بالكامل باللهجة المصرية العامية البسيطة (مش فصحى) للأطفال ")
        sb.append(age).append(".\n")
        sb.append("شروط القصة:\n")
        sb.append("- منطقية ومتماسكة: ليها بداية ووسط ونهاية، وكل حدث له سبب وبيجيب اللي بعده، من غير أحداث عشوائية.\n")
        sb.append("- فيها شخصيات بأسماء مصرية أو حيوانات لطيفة، وفيها قيمة تربوية جميلة من غير وعظ مباشر.\n")
        sb.append("- طولها حوالي 350 لـ 500 كلمة، مقسمة لفقرات قصيرة سهلة القراءة بصوت عالي.\n")
        sb.append("- من غير إيموجي ومن غير رموز غريبة، ومن غير مشاهد مخيفة أو عنيفة.\n")
        sb.append("- لازم تكون مختلفة عن القصص السابقة دي: ").append(if (old.isEmpty()) "لا يوجد" else old).append(".\n")
        if (idea.isNotEmpty()) sb.append("- فكرة القصة: ").append(idea).append(".\n")
        sb.append("رجّع الرد JSON فقط بالشكل ده: {\"title\":\"عنوان القصة\",\"story\":\"نص القصة كامل\"}")
        return sb.toString()
    }

    private fun parseStory(raw: String): Pair<String, String> {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        try {
            val o = JSONObject(clean)
            return Pair(o.optString("title", "قصة جديدة").trim(), o.getString("story").trim())
        } catch (e: Exception) {
        }
        val lines = clean.lines()
        return Pair(lines.firstOrNull()?.trim()?.take(60) ?: "قصة جديدة", lines.drop(1).joinToString("\n").trim())
    }

    private fun generate() {
        if (generating) return
        saveSettings()
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) {
            setStatus("اكتب مفتاح Gemini في الإعدادات الأول")
            if (settingsScroll.visibility != View.VISIBLE) toggleSettings()
            return
        }
        generating = true
        newBtn.isEnabled = false
        newBtn.text = "جاري التوليد..."
        setStatus("بيكتب القصة...")
        val model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        val prompt = buildPrompt()
        Thread {
            try {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", 1.0).put("responseMimeType", "application/json"))
                val resp = http("$API/$model:generateContent", body.toString(), key)
                val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
                val (title, text) = parseStory(sb.toString())
                if (text.isBlank()) throw Exception("الرد جه فاضي، جرب تاني")
                ui.post {
                    stories.add(Story(System.currentTimeMillis(), title, text))
                    index = stories.size - 1
                    saveStories()
                    stopSpeak()
                    showStory()
                    setStatus(null)
                }
            } catch (e: Exception) {
                ui.post { setStatus(e.message ?: "حصل خطأ") }
            } finally {
                ui.post {
                    generating = false
                    newBtn.isEnabled = true
                    newBtn.text = "قصة جديدة"
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- speech

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                var r = t.setLanguage(Locale("ar", "EG"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    r = t.setLanguage(Locale("ar"))
                }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "last$playToken") ui.post { setSpeaking(false) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == "last$playToken") ui.post { setSpeaking(false) }
                    }
                })
                ttsReady = true
            }
        }
    }

    private fun setSpeaking(b: Boolean) {
        speaking = b
        speakBtn.text = if (b) "إيقاف" else "اسمع القصة"
    }

    private fun toggleSpeak() {
        if (speaking) { stopSpeak(); return }
        if (stories.isEmpty()) return
        val s = stories[index]
        startSpeak(s.title + ".\n" + s.text)
    }

    private fun stopSpeak() {
        playToken++
        try { tts?.stop() } catch (e: Exception) {}
        try { player?.stop() } catch (e: Exception) {}
        setSpeaking(false)
    }

    private fun startSpeak(text: String) {
        val rate = prefs.getInt("rate", 90) / 100f
        val token = ++playToken
        if (prefs.getInt("voice", 0) == 0) {
            val t = tts
            if (!ttsReady || t == null) {
                setStatus("صوت الموبايل مش جاهز، جرب جوجل أونلاين من الإعدادات")
                return
            }
            t.stop()
            t.setSpeechRate(rate)
            val chunks = splitText(text, 3000)
            setSpeaking(true)
            for ((i, c) in chunks.withIndex()) {
                val id = if (i == chunks.size - 1) "last$token" else "c$token-$i"
                t.speak(c, TextToSpeech.QUEUE_ADD, null, id)
            }
        } else {
            setSpeaking(true)
            setStatus("بيحمّل الصوت...")
            Thread {
                try {
                    val chunks = splitText(text, 180)
                    var cur: ByteArray? = fetchTts(chunks[0])
                    for (i in chunks.indices) {
                        if (token != playToken) return@Thread
                        val data = cur ?: throw Exception("فشل تحميل الصوت، اتأكد من النت")
                        val f = File(cacheDir, "tts" + (i % 2) + ".mp3")
                        f.writeBytes(data)
                        val mp = MediaPlayer()
                        player = mp
                        val latch = CountDownLatch(1)
                        mp.setDataSource(f.absolutePath)
                        mp.setOnCompletionListener { latch.countDown() }
                        mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
                        mp.prepare()
                        if (token != playToken) { mp.release(); return@Thread }
                        mp.start()
                        try { mp.playbackParams = PlaybackParams().setSpeed(rate) } catch (e: Exception) {}
                        ui.post { setStatus(null) }
                        cur = if (i + 1 < chunks.size) fetchTts(chunks[i + 1]) else null
                        while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                            if (token != playToken) break
                        }
                        try { mp.release() } catch (e: Exception) {}
                        if (player === mp) player = null
                    }
                } catch (e: Exception) {
                    if (token == playToken) ui.post { setStatus(e.message ?: "حصل خطأ في الصوت") }
                } finally {
                    if (token == playToken) ui.post { setSpeaking(false) }
                }
            }.start()
        }
    }

    private fun fetchTts(text: String): ByteArray? {
        return try {
            val u = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=ar&q=" +
                URLEncoder.encode(text, "UTF-8")
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun splitText(t: String, max: Int): List<String> {
        val parts = t.split(Regex("(?<=[.!?؟،,\\n])")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (p in parts) {
            var piece = p
            while (piece.length > max) {
                var cut = piece.lastIndexOf(' ', max)
                if (cut <= 0) cut = max
                if (cur.isNotEmpty()) { out.add(cur.toString().trim()); cur = StringBuilder() }
                out.add(piece.substring(0, cut).trim())
                piece = piece.substring(cut).trim()
            }
            if (cur.length + piece.length + 1 > max && cur.isNotEmpty()) {
                out.add(cur.toString().trim())
                cur = StringBuilder()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(piece)
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        if (out.isEmpty()) out.add(t)
        return out
    }
}
