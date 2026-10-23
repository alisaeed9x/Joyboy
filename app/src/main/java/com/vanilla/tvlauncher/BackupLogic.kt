package com.vanilla.tvlauncher

/**
 * نسخ احتياطي/استعادة (P15، D-018) — نص عادي سطر لكل إعداد، نقي (بيتختبر على JVM).
 * الصيغة: سطر ترويسة `VTLBACKUP 1` ثم `s<TAB>key<TAB>قيمة` (نص) · `i<TAB>key<TAB>رقم` · `b<TAB>key<TAB>true|false` · `H<TAB>نص سجل المشاهدة` (سطر واحد، \n مُهرَّب).
 * بيتحفظ: الإعدادات + سجل المشاهدة + ربط الترجمات الخارجية. **ما بيتحفظش:** كاش المكتبة (بيتعاد فحصه) ولا أي أسرار (مفيش أسرار في التطبيق أصلًا).
 */
object BackupLogic {
    const val HEADER = "VTLBACKUP 1"
    const val MAX_BYTES = 4_000_000
    class Data(val prefs: Map<String, Any>, val history: String)
    class Decoded(val data: Data?, val error: String?)

    private val KEY_OK = Regex("[A-Za-z0-9_.:-]{1,64}")
    fun validKey(k: String): Boolean = KEY_OK.matches(k)

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) { '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t"); else -> sb.append(c) }
        return sb.toString()
    }
    fun unesc(s: String): String {
        val sb = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { when (s[i + 1]) { 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); '\\' -> sb.append('\\'); else -> { sb.append(c); sb.append(s[i + 1]) } }; i += 2 }
            else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun encode(prefs: Map<String, Any?>, history: String): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (k in prefs.keys.sorted()) {
            if (!validKey(k)) continue
            when (val v = prefs[k]) {
                is Boolean -> sb.append("b\t").append(k).append('\t').append(v).append('\n')
                is Int -> sb.append("i\t").append(k).append('\t').append(v).append('\n')
                is Long -> sb.append("i\t").append(k).append('\t').append(v).append('\n')
                is String -> sb.append("s\t").append(k).append('\t').append(esc(v)).append('\n')
                else -> { }
            }
        }
        sb.append("H\t").append(esc(history)).append('\n')
        return sb.toString()
    }

    fun decode(text: String): Decoded {
        if (text.length > MAX_BYTES) return Decoded(null, "الملف كبير بشكل غير طبيعي")
        val lines = text.split("\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines[0] != HEADER) return Decoded(null, "الملف مش نسخة احتياطية من التطبيق ده")
        val prefs = LinkedHashMap<String, Any>(); var hist = ""; var sawH = false
        for ((n, ln) in lines.drop(1).withIndex()) {
            val p = ln.split("\t", limit = 3)
            if (p[0] == "H") { if (p.size < 2 || sawH) return Decoded(null, "سطر السجل غلط (سطر ${n + 2})"); hist = unesc(p.drop(1).joinToString("\t")); sawH = true; continue }
            if (p.size != 3 || !validKey(p[1])) return Decoded(null, "سطر تالف (سطر ${n + 2})")
            when (p[0]) {
                "s" -> prefs[p[1]] = unesc(p[2])
                "i" -> prefs[p[1]] = p[2].toIntOrNull() ?: return Decoded(null, "رقم غلط (سطر ${n + 2})")
                "b" -> prefs[p[1]] = when (p[2]) { "true" -> true; "false" -> false; else -> return Decoded(null, "قيمة غلط (سطر ${n + 2})") }
                else -> return Decoded(null, "نوع مجهول (سطر ${n + 2})")
            }
        }
        if (!sawH) return Decoded(null, "الملف ناقص (مفيش سجل مشاهدة) — يمكن اتقطع")
        return Decoded(Data(prefs, hist), null)
    }

    fun summary(d: Data, historyCount: Int): String = "${d.prefs.size} إعداد · $historyCount عنصر في السجل"
}
