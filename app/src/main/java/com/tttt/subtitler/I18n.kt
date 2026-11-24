package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * لغة التطبيق (v1261): تلقائي من لغة السيستم، وتختار يدوي من كل اللغات.
 * لغة الترجمة منفصلة وبتتبع لغة التطبيق افتراضيًا.
 * طبقة النصوص: النص العربي في الكود بيتبدّل بترجمته من قاموس محلي، والناقص بيتترجم كسولًا بـ FreeLlm ويتخزن على القرص.
 */
object I18n {
    val LANGS = listOf(
        "ar" to "العربية", "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "it" to "Italiano",
        "pt" to "Português", "ru" to "Русский", "tr" to "Türkçe", "fa" to "فارسی", "ur" to "اردو", "hi" to "हिन्दी",
        "bn" to "বাংলা", "id" to "Bahasa Indonesia", "ms" to "Bahasa Melayu", "th" to "ไทย", "vi" to "Tiếng Việt",
        "ko" to "한국어", "ja" to "日本語", "zh" to "中文", "nl" to "Nederlands", "pl" to "Polski", "uk" to "Українська",
        "ro" to "Română", "el" to "Ελληνικά", "he" to "עברית", "sv" to "Svenska", "cs" to "Čeština", "hu" to "Magyar", "sw" to "Kiswahili")
    private val WORD = mapOf("ar" to "اللغة", "en" to "Language", "es" to "Idioma", "fr" to "Langue", "de" to "Sprache", "it" to "Lingua",
        "pt" to "Idioma", "ru" to "Язык", "tr" to "Dil", "fa" to "زبان", "ur" to "زبان", "hi" to "भाषा", "bn" to "ভাষা", "id" to "Bahasa",
        "ms" to "Bahasa", "th" to "ภาษา", "vi" to "Ngôn ngữ", "ko" to "언어", "ja" to "言語", "zh" to "语言", "nl" to "Taal", "pl" to "Język",
        "uk" to "Мова", "ro" to "Limbă", "el" to "Γλώσσα", "he" to "שפה", "sv" to "Språk", "cs" to "Jazyk", "hu" to "Nyelv", "sw" to "Lugha")
    private val RTL = setOf("ar", "fa", "ur", "he")
    private val main = Handler(Looper.getMainLooper())
    private val mem = HashMap<String, String>()
    private val pending = LinkedHashSet<String>()
    private val views = WeakHashMap<TextView, CharSequence>()
    private var loadedFor = ""
    private var dir: File? = null
    @Volatile private var busy = false
    @Volatile private var inHook = false

    fun systemCode(): String {
        val c = Locale.getDefault().language.lowercase()
        val k = when (c) { "iw" -> "he"; "in" -> "id"; else -> c }
        return if (LANGS.any { it.first == k }) k else "en"
    }
    fun setting(): String = try { Cfg.str("app_lang", "auto") } catch (_: Throwable) { "auto" }
    fun code(): String { val s = setting(); return if (s == "auto" || LANGS.none { it.first == s }) systemCode() else s }
    fun nameOf(c: String): String = LANGS.firstOrNull { it.first == c }?.second ?: c
    /** كلمة "Language" بلغة السيستم دايمًا */
    fun languageWord(): String = WORD[systemCode()] ?: "Language"
    fun isRtl(): Boolean = code() in RTL
    fun translationCode(): String { val t = try { Cfg.str("tr_lang", "auto") } catch (_: Throwable) { "auto" }; return if (t == "auto" || LANGS.none { it.first == t }) code() else t }

    fun setAppLang(c: String) {
        Cfg.put("app_lang", c)
        Cfg.put("lang_chosen", "1")
        synchronized(this) { loadedFor = ""; mem.clear(); pending.clear() }
        refreshAll()
    }
    fun setTranslationLang(c: String) { Cfg.put("tr_lang", c) }

    private fun load(lang: String) {
        synchronized(this) {
            if (loadedFor == lang) return
            mem.clear(); loadedFor = lang
            try {
                val f = File(dir ?: return, "i18n_" + lang + ".json")
                if (f.exists()) { val o = JSONObject(f.readText()); val it = o.keys(); while (it.hasNext()) { val k = it.next(); mem[k] = o.getString(k) } }
            } catch (_: Throwable) {}
        }
    }
    private fun save(lang: String) {
        try {
            val o = JSONObject(); synchronized(this) { for ((k, v) in mem) o.put(k, v) }
            File(dir ?: return, "i18n_" + lang + ".json").writeText(o.toString())
        } catch (_: Throwable) {}
    }
    private fun hasArabic(s: String): Boolean { for (ch in s) if (ch in '\u0600'..'\u06FF') return true; return false }

    /** نص الواجهة بلغة التطبيق (العربي يرجع زي ما هو) */
    fun tr(s: String): String {
        val l = code()
        if (l == "ar" || s.length < 2 || s.length > 70 || s.indexOf('\n') >= 0 || !hasArabic(s)) return s
        load(l)
        synchronized(this) {
            val v = mem[s]
            if (v != null) return v
            if (pending.size < 400) pending.add(s)
        }
        schedule()
        return s
    }

    /** بيتنده من IconTextView.setText */
    fun hook(v: TextView, t: CharSequence?): CharSequence? {
        if (t == null || inHook) return t
        if (code() == "ar") return t
        val s = t.toString()
        val r = tr(s)
        if (r !== s) { synchronized(views) { views[v] = t }; return r }
        if (hasArabic(s) && s.length <= 70) synchronized(views) { views[v] = t }
        return t
    }

    private fun schedule() {
        if (busy) return
        busy = true
        main.postDelayed({ Thread { try { flush() } finally { busy = false } }.start() }, 900L)
    }

    private fun flush() {
        val l = code()
        if (l == "ar") return
        try { if (!FreeLlm.ready()) return } catch (_: Throwable) { return }
        var rounds = 0
        while (rounds++ < 6) {
            val batch: List<String> = synchronized(this) { pending.take(40) }
            if (batch.isEmpty()) break
            val ja = JSONArray(); for (b in batch) ja.put(b)
            val prompt = "Translate these Arabic app UI strings to " + nameOf(l) + " (" + l + "). Keep emojis, numbers and symbols exactly. Keep it short like a button label. Return ONLY a JSON array of strings, same length and order.\n" + ja.toString()
            var out: List<String>? = null
            try {
                val r = FreeLlm.generate(prompt, null, 4000, 0.2, true)
                var tx = r.text.trim()
                val a = tx.indexOf('['); val b = tx.lastIndexOf(']')
                if (a >= 0 && b > a) tx = tx.substring(a, b + 1)
                val arr = JSONArray(tx)
                if (arr.length() == batch.size) out = (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Throwable) {}
            synchronized(this) {
                if (loadedFor == l) {
                    if (out != null) for (i in batch.indices) mem[batch[i]] = out[i]
                }
                pending.removeAll(batch.toSet())
            }
            if (out == null) break
        }
        save(l)
        main.post { refreshAll() }
    }

    fun refreshAll() {
        val snap = synchronized(views) { views.entries.map { it.key to it.value } }
        inHook = false
        for ((v, o) in snap) try { v.text = o } catch (_: Throwable) {}
    }

    private fun apply(a: Activity) {
        try {
            val d = if (isRtl()) View.LAYOUT_DIRECTION_RTL else if (code() == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            a.window.decorView.layoutDirection = d
        } catch (_: Throwable) {}
    }

    private fun firstRun(a: Activity) {
        try { Cfg.ensure(a); if (Cfg.str("lang_chosen", "") == "1") return } catch (_: Throwable) { return }
        if (a !is Main) return
        val names = arrayOf("🌐 Auto").plus(LANGS.map { it.second })
        try {
            AlertDialog.Builder(a).setTitle("🌐 " + languageWord()).setCancelable(false).setItems(names) { _, i ->
                if (i == 0) { Cfg.put("app_lang", "auto"); Cfg.put("lang_chosen", "1") } else setAppLang(LANGS[i - 1].first)
                a.recreate()
            }.show()
        } catch (_: Throwable) {}
    }

    fun install(app: Application) {
        dir = app.filesDir
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(x: Activity, b: Bundle?) {}
            override fun onActivityStarted(x: Activity) {}
            override fun onActivityResumed(x: Activity) {
                try { Cfg.ensure(x) } catch (_: Throwable) {}
                apply(x); firstRun(x)
            }
            override fun onActivityPaused(x: Activity) {}
            override fun onActivityStopped(x: Activity) {}
            override fun onActivitySaveInstanceState(x: Activity, b: Bundle) {}
            override fun onActivityDestroyed(x: Activity) {}
        })
    }
}
