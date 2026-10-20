package com.tttt.subtitler

import android.app.Activity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference

/**
 * (v272) سحب قايمة الـ APIs من ريبو public-apis/public-apis أوتوماتيك (README.md الخام) ومعرفة نجح ولا فشل.
 * - بيتسحب لوحده عند فتح التطبيق (مرة كل 24 ساعة، ومن الإعدادات تقدر تسحب بإيدك)، وفي إشعار: بيسحب ← اتسحب (العدد) ← فشل (السبب).
 * - آخر نسخة ناجحة بتتحفظ في filesDir/public_apis.md فتفضل شغّالة لو السحب فشل أو مفيش نت.
 * - القايمة نفسها مجرد دليل (اسم/وصف/هل محتاج مفتاح/لينك) — مفيهاش طريقة الاستدعاء، فالمزوّدين اللي بيشتغلوا فعلًا هما اللي ليهم محوّل جاهز في ExtTranslate ويتطابقوا مع اسم في القايمة.
 */
object PublicApis {
    class Entry(val name: String, val link: String, val desc: String, val auth: String, val https: String, val category: String)

    const val URL_RAW = "https://raw.githubusercontent.com/public-apis/public-apis/master/README.md"
    private const val MIN_ENTRIES = 200            // أقل من كده = الملف مش شكل القايمة
    private const val EVERY_MS = 24L * 3600 * 1000

    @Volatile var entries: List<Entry> = emptyList(); private set
    /** idle | fetching | ok | fail */
    @Volatile var state = "idle"; private set
    @Volatile var lastError = ""; private set
    @Volatile private var fetching = false
    @Volatile var onChange: (() -> Unit)? = null

    private fun cacheFile(): File? = TrWizard.appCtx?.filesDir?.let { File(it, "public_apis.md") }

    /** آخر سحب ناجح (ms) أو 0 */
    fun lastOk(): Long = Cfg.str("papi_at", "0").toLongOrNull() ?: 0L

    /** المدخلات اللي ليها علاقة بالترجمة / التفريغ / الكلام */
    fun related(): List<Entry> {
        val rx = Regex("translat|transcri|speech|language detect|text.to.speech|speech.to.text", RegexOption.IGNORE_CASE)
        return entries.filter { rx.containsMatchIn(it.name + " " + it.desc) }
    }

    /** هل مدخل بالاسم/اللينك ده موجود في القايمة المسحوبة؟ (للمطابقة مع المحوّلات) */
    fun listed(keys: List<String>): Entry? = entries.firstOrNull { e ->
        val hay = (e.name + " " + e.link).lowercase()
        keys.any { hay.contains(it) }
    }

    /** بيحلّل نص README: عناوين ### = التصنيف، وصفوف الجدول: | [الاسم](لينك) | وصف | Auth | HTTPS | CORS | */
    fun parse(md: String): List<Entry> {
        val out = ArrayList<Entry>()
        var cat = ""
        val link = Regex("\\[(.*?)\\]\\((.*?)\\)")
        for (raw in md.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("### ")) { cat = line.removePrefix("### ").trim(); continue }
            if (!line.startsWith("| [")) continue
            val cells = line.trim('|').split("|").map { it.trim() }
            if (cells.size < 4) continue
            val m = link.find(cells[0]) ?: continue
            out.add(Entry(m.groupValues[1].trim(), m.groupValues[2].trim(), cells[1], cells[2].trim('`', ' '), cells[3], cat))
        }
        return out
    }

    /** يحمّل النسخة المحفوظة في الذاكرة (بيتنده مرة واحدة عند البداية) */


    private fun fire() { try { onChange?.invoke() } catch (_: Throwable) {} }

    private fun download(): String {
        var last: Exception? = null
        for (t in 1..3) {
            try {
                val c = URL(URL_RAW).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    c.setRequestProperty("User-Agent", "Subtitler")
                    val code = c.responseCode
                    if (code != 200) throw java.io.IOException("HTTP $code")
                    val txt = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                    NetUse.api(200L, txt)
                    return txt
                } finally { try { c.disconnect() } catch (_: Throwable) {} }
            } catch (e: Exception) { last = e; try { Thread.sleep(1200L * t) } catch (_: InterruptedException) {} }
        }
        throw last ?: java.io.IOException("فشل غير معروف")
    }

    /** يسحب القايمة في خيط خلفي. [manual] = من الزرار (بيطلّع إشعار حتى لو مفيش نت) */
    fun pull(act: Activity?, manual: Boolean) {
        if (fetching) return
        val ref = WeakReference(act)
        fun say(msg: String) { ref.get()?.let { try { Notice.show(it, msg, 3200L) } catch (_: Throwable) {} } }
        if (!TrWizard.online()) {
            if (manual) { state = "fail"; lastError = "مفيش نت"; fire(); say("❌ مفيش نت — مقدرتش أسحب قايمة الـ APIs") }
            return
        }
        fetching = true; state = "fetching"; fire()
        say("⏳ بسحب قايمة الـ APIs من الريبو…")
        Thread {
            try {
                val md = download()
                val l = parse(md)
                if (l.size < MIN_ENTRIES) throw java.io.IOException("الملف اللي نزل مش شكل القايمة (${l.size} مدخل بس)")
                entries = l
                try { cacheFile()?.writeText(md, Charsets.UTF_8) } catch (_: Throwable) {}
                Cfg.put("papi_at", System.currentTimeMillis().toString())
                state = "ok"; lastError = ""
                say("✅ اتسحبت قايمة الـ APIs: ${l.size} API (${related().size} ليها علاقة بالترجمة/الكلام)")
                LogStore.add("🌐 public-apis: اتسحب ${l.size} مدخل")
            } catch (e: Throwable) {
                state = "fail"; lastError = (e.message ?: e.javaClass.simpleName).take(100)
                say("❌ سحب قايمة الـ APIs فشل: $lastError" + if (entries.isNotEmpty()) " — شغّال بآخر نسخة محفوظة" else "")
                LogStore.add("🌐 public-apis: فشل السحب — $lastError")
            } finally { fetching = false; fire() }
        }.also { it.isDaemon = true; it.start() }
    }

    /** عند فتح التطبيق: يحمّل النسخة المحفوظة وبعدين يسحب جديد لو عدّى 24 ساعة (ولو الخيار شغّال). التأخير عشان الإشعار يظهر بعد ما الشاشة تفتح */
}
