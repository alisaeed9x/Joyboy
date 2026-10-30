package com.tttt.subtitler

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * (v653) نقل الملفات الذكي — هوية الجهاز.
 * اسم عشوائي لطيف أول مرة (Blue Falcon…) يقدر المستخدم يغيّره أو يولّد غيره أو يرجع للأصلي.
 * متخزّنة في ملف خاص (ft_private) مش في Cfg — عشان النسخ الاحتياطي ما ينقلش نفس الهوية لجهاز تاني.
 * مفيش أسماء أشخاص ولا أرقام موبايل بتتاخد تلقائي.
 */
object FtIdentity {
    private val ADJ = listOf(
        "Blue", "Smart", "Happy", "Swift", "Brave", "Lucky", "Cosmic", "Golden", "Silver", "Mighty",
        "Clever", "Sunny", "Calm", "Wild", "Rapid", "Bright", "Jolly", "Noble", "Cool", "Royal",
        "Misty", "Fiery", "Gentle", "Lively", "Quiet"
    )
    private val NOUN = listOf(
        "Falcon", "Tiger", "Fox", "Robot", "Panda", "Eagle", "Wolf", "Dolphin", "Lion", "Otter",
        "Hawk", "Comet", "Rocket", "Koala", "Phoenix", "Dragon", "Owl", "Lynx", "Orca", "Raven",
        "Galaxy", "Pixel", "Camel", "Gazelle", "Sphinx"
    )
    private val rnd = SecureRandom()
    private var app: Context? = null
    private var sp: SharedPreferences? = null

    @Synchronized fun init(ctx: Context) {
        if (sp != null) return
        app = ctx.applicationContext
        val p = ctx.applicationContext.getSharedPreferences("ft_private", Context.MODE_PRIVATE)
        sp = p
        if (p.getString("id", "").isNullOrEmpty()) {
            val b = ByteArray(8); rnd.nextBytes(b)
            p.edit().putString("id", b.joinToString("") { String.format("%02x", it) }).apply()
        }
        if (p.getString("name", "").isNullOrBlank()) {
            val n = generate(emptySet())
            p.edit().putString("orig", n).putString("name", n).apply()
        }
    }

    private fun p(): SharedPreferences = sp ?: throw IllegalStateException("FtIdentity not initialised")

    /** معرّف ثابت للجهاز ده (بيتولّد مرة واحدة) */
    fun id(): String = p().getString("id", "") ?: ""

    fun name(): String = p().getString("name", "") ?: "Joyboy"

    /** الاسم اللي اتولّد أول مرة (لزرار «استعادة الاسم الافتراضي») */
    fun original(): String = p().getString("orig", "") ?: name()

    fun isCustom(): Boolean = name() != original()

    fun sanitize(n: String): String =
        n.replace(Regex("[\\u0000-\\u001f\\u007f<>\"\\\\]"), " ").replace(Regex("\\s+"), " ").trim().take(32)

    /** @return false لو الاسم فاضي بعد التنضيف */
    fun setName(n: String): Boolean {
        val s = sanitize(n)
        if (s.isEmpty()) return false
        p().edit().putString("name", s).apply()
        return true
    }

    /** اسم عشوائي جديد (بيتجنّب الأسماء الظاهرة دلوقتي على الشبكة) ويتحفظ فورًا */
    fun regenerate(avoid: Set<String> = emptySet()): String {
        val n = generate(avoid)
        p().edit().putString("name", n).apply()
        return n
    }

    fun resetDefault(): String {
        val o = original().ifEmpty { generate(emptySet()) }
        p().edit().putString("name", o).putString("orig", o).apply()
        return o
    }

    private fun generate(avoid: Set<String>): String {
        val low = avoid.map { it.lowercase() }.toSet()
        for (i in 0 until 60) {
            val n = ADJ[rnd.nextInt(ADJ.size)] + " " + NOUN[rnd.nextInt(NOUN.size)]
            if (n.lowercase() !in low) return n
        }
        return ADJ[rnd.nextInt(ADJ.size)] + " " + NOUN[rnd.nextInt(NOUN.size)] + " " + (10 + rnd.nextInt(90))
    }

    /** phone · tablet · tv */
    fun type(): String {
        val c = app ?: return "phone"
        if (try { DeviceUi.isTv(c) } catch (_: Throwable) { false }) return "tv"
        val sw = try { c.resources.configuration.smallestScreenWidthDp } catch (_: Throwable) { 0 }
        return if (sw >= 600) "tablet" else "phone"
    }

    fun typeLabel(t: String): String = when (t) {
        "tv" -> "تلفزيون أندرويد"; "tablet" -> "تابلت"; "phone" -> "موبايل"; "pc" -> "كمبيوتر"; else -> "جهاز تاني"
    }

    // ───────── إعدادات خاصة بالنقل (في نفس الملف الخاص) ─────────
    /** استقبال تلقائي من الأجهزة الموثوقة اللي المستخدم حدّدها بنفسه (افتراضي: لأ) */
    fun autoTrusted(): Boolean = p().getBoolean("auto_trusted", false)
    fun setAutoTrusted(v: Boolean) { p().edit().putBoolean("auto_trusted", v).apply() }

    /** يفضّل جاهز للاستقبال حتى بعد قفل شاشة النقل (طول ما التطبيق شغّال) */
    fun keepReady(): Boolean = p().getBoolean("keep_ready", false)
    fun setKeepReady(v: Boolean) { p().edit().putBoolean("keep_ready", v).apply() }
}
