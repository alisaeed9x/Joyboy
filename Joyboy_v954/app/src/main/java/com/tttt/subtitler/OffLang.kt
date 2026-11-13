package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * (v411) مرحلة 2 من الأوضاع التلاتة — لغة المصدر من Whisper + فحص الحروف، ودمج الجمل المقطّعة قبل الترجمة، وإشعار تحميل موديل اللغة.
 * ما بيلمسش جيميناي خالص.
 */
object OffLang {
    /** أسماء اللغات بالعربي بس (مش بحروفها الأصلية) */
    val names = mapOf(
        "en" to "إنجليزي", "ja" to "ياباني", "ko" to "كوري", "zh" to "صيني", "ar" to "عربي", "ru" to "روسي", "fr" to "فرنساوي",
        "es" to "إسباني", "tr" to "تركي", "de" to "ألماني", "it" to "إيطالي", "hi" to "هندي", "fa" to "فارسي", "ur" to "أوردو"
    )
    fun nameOf(code: String): String = names[code.substringBefore('-').lowercase()] ?: code

    /** كود Whisper أو اسم عربي/إنجليزي للغة ← كود من حرفين، أو "" لو مش معروف */
    fun norm(s: String): String {
        val t = s.trim().lowercase()
        if (t.isEmpty()) return ""
        val c = t.substringBefore('-').substringBefore('_')
        if (c in names) return c
        return when {
            t.contains("ياباني") || t.contains("japan") -> "ja"
            t.contains("كوري") || t.contains("korea") -> "ko"
            t.contains("صيني") || t.contains("chin") || t.contains("mandarin") -> "zh"
            t.contains("إنجليز") || t.contains("انجليز") || t.contains("english") -> "en"
            t.contains("روسي") || t.contains("russia") -> "ru"
            t.contains("فرنس") || t.contains("french") -> "fr"
            t.contains("إسبان") || t.contains("اسبان") || t.contains("spanish") -> "es"
            t.contains("تركي") || t.contains("turk") -> "tr"
            t.contains("ألماني") || t.contains("الماني") || t.contains("german") -> "de"
            t.contains("إيطال") || t.contains("ايطال") || t.contains("italia") -> "it"
            t.contains("هندي") || t.contains("hindi") -> "hi"
            else -> ""
        }
    }

    /**
     * تصويت اللغة لفيديو واحد: بيجمع حروف الجمل اللي اتفرّغت (كانا/هانجول/هان/سيريلي…) + اللغة اللي Whisper قالها.
     * الحروف هي الحكم في اللغات اللي بتتلخبط (ياباني/صيني/كوري)، وWhisper هو الحكم في اللغات اللاتينية.
     */
    class Vote {
        private var kana = 0; private var hangul = 0; private var han = 0; private var cyr = 0; private var arab = 0; private var deva = 0; private var latin = 0
        private var lines = 0
        private val seen = HashSet<String>()   // نفس الجملة لما تتعاد (إعادة محاولة) ماتتعدّش تاني
        @Volatile var lastAnnounced = ""
        fun add(texts: List<String>) {
            synchronized(this) {
                for (t in texts) {
                    if (t.isBlank() || !seen.add(t)) continue
                    lines++
                    for (ch in t) {
                        val c = ch.code
                        when {
                            c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF9F -> kana++
                            c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F -> hangul++
                            c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
                            c in 0x0400..0x04FF -> cyr++
                            c in 0x0600..0x06FF || c in 0x0750..0x077F -> arab++
                            c in 0x0900..0x097F -> deva++
                            ch.isLetter() && c < 0x250 -> latin++
                        }
                    }
                }
            }
        }
        /**
         * اللغة الأرجح لـ ML Kit / جوجل (zh بتتحوّل لـ zh-CN بره). [whisper] = اللغة اللي Whisper اكتشفها أو اللي اخترتها بإيدك ("" = مش معروفة).
         * بترجّع "" لو مفيش دليل كفاية (الاستدعاء بيستخدم "auto").
         */
        fun decide(whisper: String): String = synchronized(this) {
            val w = norm(whisper)
            when {
                kana >= 3 -> "ja"                          // كانا = ياباني دايمًا (الصيني مفيهوش كانا) حتى لو Whisper قال صيني
                hangul >= 3 -> "ko"
                han >= 4 && kana == 0 && hangul == 0 ->   // هان بس: صيني، إلا لو Whisper قال ياباني ولسه الأدلة قليلة (جمل قليلة)
                    if (w == "ja" && lines < 6) "ja" else "zh"
                cyr >= 6 -> if (w == "uk" || w == "bg") w else "ru"
                deva >= 4 -> "hi"
                arab >= 6 -> if (w == "fa" || w == "ur") w else "ar"
                latin >= 8 -> if (w.isNotEmpty() && w !in setOf("ja", "ko", "zh", "ru", "ar", "hi", "fa", "ur")) w else if (w.isEmpty()) "" else "en"   // Whisper قال لغة غير لاتينية والنص لاتيني ← الاكتشاف غلط، نرجع للإنجليزي
                else -> ""
            }
        }
        /** هل فيه تعارض بين Whisper والحروف (للّوج) */
        fun conflict(whisper: String): String? {
            val w = norm(whisper); val d = decide(whisper)
            return if (w.isNotEmpty() && d.isNotEmpty() && w != d) "Whisper قال ${nameOf(w)} بس الحروف بتقول ${nameOf(d)}" else null
        }
    }

    // ===== دمج الجمل المقطّعة قبل الترجمة =====
    private val TERMINAL = setOf('.', '!', '?', '…', '。', '！', '？', '؟', '」', '』', '"', '”', ')', '♪')
    private fun isCjk(c: Char): Boolean = c.code in 0x3040..0x30FF || c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF
    private const val MAX_LINES = 4
    private const val MAX_CHARS = 220

    /** بيجمّع الأسطر المتتالية اللي مش واقفة بعلامة نهاية جملة. بيرجّع قايمة مجموعات من فهارس الأسطر (كل سطر في مجموعة واحدة بالظبط) */
    fun group(lines: List<String>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var i = 0
        while (i < lines.size) {
            val g = arrayListOf(i)
            var len = lines[i].length
            while (i + 1 < lines.size && g.size < MAX_LINES) {
                val cur = lines[g.last()].trim()
                val nxt = lines[i + 1].trim()
                if (cur.isEmpty() || nxt.isEmpty()) break
                if (cur.last() in TERMINAL) break
                if (len + nxt.length + 1 > MAX_CHARS) break
                g.add(++i); len += nxt.length + 1
            }
            out.add(g); i++
        }
        return out
    }

    fun join(parts: List<String>): String {
        val sb = StringBuilder()
        for (p in parts.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (sb.isNotEmpty() && !(isCjk(sb.last()) || isCjk(p.first()))) sb.append(' ')
            sb.append(p)
        }
        return sb.toString()
    }

    /**
     * بيقسم الترجمة العربية [t] على [n] أسطر بنسبة طول الأصل [weights]، عند حدود الكلمات (وبيفضّل أقرب علامة وقف جنب نقطة القسمة).
     * بيرجّع null لو الكلمات أقل من عدد الأسطر (مش ممكن كل سطر ياخد كلمة) — المستدعي بيترجم الأسطر دي لوحدها.
     */
    fun split(t: String, weights: List<Int>): List<String>? {
        val n = weights.size
        val words = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (n == 1) return listOf(words.joinToString(" "))
        if (words.size < n) return null
        val total = weights.sum().coerceAtLeast(1)
        val cuts = ArrayList<Int>()   // مكان نهاية كل سطر (عدد الكلمات لحد هنا)
        var acc = 0
        for (k in 0 until n - 1) {
            acc += weights[k]
            var cut = Math.round(words.size * acc.toDouble() / total).toInt()
            val lo = (cuts.lastOrNull() ?: 0) + 1
            val hi = words.size - (n - 1 - k)
            cut = cut.coerceIn(lo, hi)
            // أقرب علامة وقف في حدود ±2 كلمة
            var best = cut; var bd = 99
            for (c in (cut - 2)..(cut + 2)) {
                if (c < lo || c > hi) continue
                val w = words[c - 1]
                if (w.isNotEmpty() && (w.last() in "،,.؟?!؛;:")) { val d = Math.abs(c - cut); if (d < bd) { bd = d; best = c } }
            }
            cuts.add(best)
        }
        cuts.add(words.size)
        val out = ArrayList<String>()
        var from = 0
        for (c in cuts) { out.add(words.subList(from, c).joinToString(" ")); from = c }
        return out
    }

    /** الدمج شغّال بس في مسار Whisper (نص-أونلاين / أوفلاين / أي تفريغ أوفلاين) — مش في جيميناي الأونلاين */
    fun mergeOn(): Boolean = Strict.noGemKeys() || Offline.configured()
}
