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

    /** اللهجة الدارجة للغة الترجمة: مصري للعربي، وللباقي صيغة المحكية بنفس اللغة */
    fun dialectName(): String { val c = translationCode(); return if (c == "ar") "مصري" else nameOf(c) + " (محكية دارجة، مش رسمية)" }

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

    private val DICT: List<Array<String>> = listOf(
        arrayOf("الفيديوهات","Videos","Vídeos","视频"),
        arrayOf("الموسيقى","Music","Música","音乐"),
        arrayOf("المتصفح","Browser","Navegador","浏览器"),
        arrayOf("المخزن","Storage","Almacenamiento","存储"),
        arrayOf("مركز التحميل","Downloads","Descargas","下载中心"),
        arrayOf("الإعدادات","Settings","Ajustes","设置"),
        arrayOf("المزيد","More","Más","更多"),
        arrayOf("الرئيسية","Home","Inicio","首页"),
        arrayOf("الاشتراكات","Subscriptions","Suscripciones","订阅"),
        arrayOf("أنا","Me","Yo","我"),
        arrayOf("الريموت","Remote","Mando","遥控"),
        arrayOf("الكليبورد","Clipboard","Portapapeles","剪贴板"),
        arrayOf("ريموت IR","IR remote","Mando IR","红外遥控"),
        arrayOf("العناية","Care","Cuidado","护理"),
        arrayOf("ميديا","Media","Medios","媒体"),
        arrayOf("محلي","Local","Local","本地"),
        arrayOf("مكتبة الترجمات","Subtitle library","Biblioteca de subtítulos","字幕库"),
        arrayOf("سجل اللوجات","Logs","Registros","日志"),
        arrayOf("وضع TV","TV mode","Modo TV","电视模式"),
        arrayOf("النت والداتا","Network & data","Red y datos","网络与数据"),
        arrayOf("مركز التحديث","Update center","Centro de actualizaciones","更新中心"),
        arrayOf("إرسال التحديث للتلفزيون","Send update to TV","Enviar actualización a la TV","发送更新到电视"),
        arrayOf("شرح الاستخدام","How to use","Cómo usar","使用说明"),
        arrayOf("الأقسام","Sections","Secciones","分区"),
        arrayOf("الترجمة","Translation","Traducción","翻译"),
        arrayOf("تحكم وريموت","Control & remote","Control y mando","控制与遥控"),
        arrayOf("النظام والتحديث","System & updates","Sistema y actualizaciones","系统与更新"),
        arrayOf("خط الترجمة","Translation pipeline","Flujo de traducción","翻译流程"),
        arrayOf("المفاتيح","Keys","Claves","密钥"),
        arrayOf("التفريغ والصوت","Transcription & audio","Transcripción y audio","转写与音频"),
        arrayOf("الموديل والأداء","Model & performance","Modelo y rendimiento","模型与性能"),
        arrayOf("شكل الترجمة","Subtitle style","Estilo de subtítulos","字幕样式"),
        arrayOf("المظهر","Appearance","Apariencia","外观"),
        arrayOf("التلفزيون","Television","Televisión","电视"),
        arrayOf("المشغّل","Player","Reproductor","播放器"),
        arrayOf("مشغّل الموسيقى","Music player","Reproductor de música","音乐播放器"),
        arrayOf("الخلفية والتشغيل","Background & running","Segundo plano","后台运行"),
        arrayOf("الاصطياد والترجمة الحية","Capture & live translation","Captura y traducción en vivo","捕获与实时翻译"),
        arrayOf("الأمان والنسخ","Security & backup","Seguridad y copia","安全与备份"),
        arrayOf("إغلاق","Close","Cerrar","关闭"),
        arrayOf("لاحقًا","Later","Más tarde","稍后"),
        arrayOf("تحديث","Update","Actualizar","更新"),
        arrayOf("تثبيت","Install","Instalar","安装"),
        arrayOf("مش دلوقتي","Not now","Ahora no","暂不"),
        arrayOf("تمام","OK","Vale","好的"),
        arrayOf("مفيش تحديثات جديدة","No new updates","Sin actualizaciones nuevas","没有新更新"),
        arrayOf("أنت على آخر تحديث","You are up to date","Estás al día","已是最新版本"),
        arrayOf("فيه تحديث جديد","Update available","Actualización disponible","有新更新"),
        arrayOf("تحميل التحديث","Download update","Descargar actualización","下载更新"),
        arrayOf("من الأول","From the start","Desde el inicio","从头开始"),
        arrayOf("بيفحص…","Checking…","Comprobando…","检查中…"),
        arrayOf("إلغاء","Cancel","Cancelar","取消"),
        arrayOf("حفظ","Save","Guardar","保存"),
        arrayOf("حذف","Delete","Eliminar","删除"),
        arrayOf("بحث","Search","Buscar","搜索"),
        arrayOf("فتح","Open","Abrir","打开"),
        arrayOf("تصدير","Export","Exportar","导出"),
        arrayOf("استيراد","Import","Importar","导入"),
        arrayOf("الجودة","Quality","Calidad","画质"),
        arrayOf("الجمل","Sentences","Frases","句子"),
        arrayOf("تحديث تلقائي","Auto update","Actualización automática","自动更新"),
        arrayOf("لهجة","Dialect","Dialecto","方言"),
        arrayOf("فصحى","Formal","Formal","标准语"),
        arrayOf("ملاحظات","Notes","Notas","备注"),
        arrayOf("ميزة جديدة","New","Nuevo","新功能"),
        arrayOf("تعديل","Changed","Cambio","修改"),
        arrayOf("إصلاح","Fixed","Arreglo","修复"),
        arrayOf("اللغة","Language","Idioma","语言"))
    private fun dictLook(s: String, l: String): String? {
        val col = when (l) { "en" -> 1; "es" -> 2; "zh" -> 3; else -> return null }
        for (r in DICT) if (r[0] == s) return r[col]
        val sp = s.indexOf(' ')
        if (sp > 0) { val pre = s.substring(0, sp); if (!hasArabic(pre)) { val rest = s.substring(sp + 1); for (r in DICT) if (r[0] == rest) return pre + " " + r[col] } }
        return null
    }

    /** نص الواجهة بلغة التطبيق (العربي يرجع زي ما هو) */
    fun tr(s: String): String {
        val l = code()
        if (l == "ar" || s.length < 2 || s.length > 200 || !hasArabic(s)) return s
        if (s.length > 70 && s.any { it in '0'..'9' }) return s
        if (LANGS.any { it.second == s }) return s
        dictLook(s, l)?.let { return it }
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
        if (hasArabic(s) && s.length <= 200) synchronized(views) { views[v] = t }
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
        for ((v, o) in snap) try { if (v is IconTextView) v.text = o else { val r = tr(o.toString()); if (r != v.text.toString()) v.text = r } } catch (_: Throwable) {}
    }

    private val swept = WeakHashMap<Activity, Boolean>()
    private fun sweep(v: View) {
        if (v is TextView) {
            try {
                if (v !is IconTextView) {
                    val t = v.text
                    if (t != null && t.isNotEmpty() && hasArabic(t.toString())) {
                        val r = tr(t.toString())
                        if (r != t.toString()) { synchronized(views) { views[v] = t }; v.text = r } else synchronized(views) { views[v] = t }
                    }
                    val h = v.hint
                    if (h != null && hasArabic(h.toString())) { val r = tr(h.toString()); if (r != h.toString()) v.hint = r }
                }
            } catch (_: Throwable) {}
        }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) sweep(v.getChildAt(i))
    }
    private fun watch(a: Activity) {
        if (synchronized(swept) { swept.put(a, true) } != null) return
        try {
            val root = a.window.decorView
            var posted = false
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (code() == "ar" || posted) return@addOnGlobalLayoutListener
                posted = true
                main.postDelayed({ posted = false; try { sweep(root) } catch (_: Throwable) {} }, 350L)
            }
        } catch (_: Throwable) {}
    }

    private fun apply(a: Activity) {
        watch(a)
        try {
            val d = if (isRtl()) View.LAYOUT_DIRECTION_RTL else if (code() == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            a.window.decorView.layoutDirection = d
        } catch (_: Throwable) {}
    }

    private fun firstRun(a: Activity) {
        try { Cfg.ensure(a); if (Cfg.str("lang_chosen", "") == "1") return } catch (_: Throwable) { return }
        if (a !is MainActivity) return
        try {
            LangPicker.show(a, languageWord(), "auto", false) { c ->
                if (c == "auto") { Cfg.put("app_lang", "auto"); Cfg.put("lang_chosen", "1") } else setAppLang(c)
                a.recreate()
            }
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
