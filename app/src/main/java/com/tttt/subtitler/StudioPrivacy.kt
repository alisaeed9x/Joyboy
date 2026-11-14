package com.tttt.subtitler

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v1003 — المرحلة 5) خصوصية الاستوديو — منطق بس، من غير واجهة:
 *  • PIN رقمي (4–6 أرقام) على قسم المكالمات: ملح + هاش SHA-256، وقفل بعد المحاولات الغلط
 *    بنفس سلم LockCore.lockoutMs. المفاتيح studio_pin_* مش lock_* عشان BrainRpc بيزامن
 *    بادئة lock_ للتلفزيون — الـPIN مبيتسافرش للتلفزيون ولا في النسخ الاحتياطية (BackupCodec.skip).
 *    sessionUnlocked في الذاكرة بس — بتتصفّر لو العملية اتقفلت.
 *  • وضع الإخفاء (redaction): طمس أرقام/مبالغ/تواريخ وأسماء الأطراف بـ «•••» قبل التصدير أو المشاركة.
 *  • إحصائيات وملخص دوري للمكالمات — من النصوص المحفوظة بس، من غير أي طلب Gemini جديد.
 *  • قايمة استثناء التفريغ التلقائي — التفويض لـ StudioIntel (المصدر الوحيد للقايمة).
 *  • قص مقطع صوت — عبر Lecture.cutAudioClip، مع وقوع على مقطع نصي بس لو الصوت مش متاح.
 */
object StudioPrivacy {

    // ===================== PIN =====================
    const val PIN_HASH_KEY = "studio_pin_hash"
    const val PIN_SALT_KEY = "studio_pin_salt"
    const val PIN_FAILS_KEY = "studio_pin_fails"
    const val PIN_UNTIL_KEY = "studio_pin_until"
    const val REDACT_KEY = "studio_redact"

    @Volatile var sessionUnlocked: Boolean = false

    fun isPinSet(ctx: Context): Boolean = Cfg.str(PIN_HASH_KEY, "").isNotEmpty()

    private fun pinValid(pin: String): Boolean = pin.length in 4..6 && pin.all { it in '0'..'9' }

    // بيرجّع false لو الـPIN مش 4–6 أرقام — والمفاتيح القديمة بتفضل زي ما هي.
    fun setPin(ctx: Context, pin: String): Boolean {
        if (!pinValid(pin)) return false
        val salt = LockCore.newSalt()
        Cfg.put(PIN_SALT_KEY, salt)
        Cfg.put(PIN_HASH_KEY, LockCore.hashPin(salt, pin))
        Cfg.put(PIN_FAILS_KEY, "0")
        Cfg.put(PIN_UNTIL_KEY, "0")
        sessionUnlocked = true
        return true
    }

    // بيرجّع false والعدّ بيزيد لو الـPIN غلط أو لسه في فترة قفل؛ true وبيصفّر العدّ لو صح.
    fun checkPin(ctx: Context, pin: String): Boolean {
        val now = System.currentTimeMillis()
        val until = Cfg.str(PIN_UNTIL_KEY, "0").toLongOrNull() ?: 0L
        if (now < until) return false
        val salt = Cfg.str(PIN_SALT_KEY, "")
        val stored = Cfg.str(PIN_HASH_KEY, "")
        val ok = salt.isNotEmpty() && stored.isNotEmpty() && FtCrypto.safeEq(LockCore.hashPin(salt, pin), stored)
        if (ok) {
            Cfg.put(PIN_FAILS_KEY, "0")
            Cfg.put(PIN_UNTIL_KEY, "0")
            sessionUnlocked = true
            return true
        }
        val fails = (Cfg.str(PIN_FAILS_KEY, "0").toIntOrNull() ?: 0) + 1
        Cfg.put(PIN_FAILS_KEY, fails.toString())
        val wait = LockCore.lockoutMs(fails)
        if (wait > 0) Cfg.put(PIN_UNTIL_KEY, (now + wait).toString())
        return false
    }

    // ثواني القفل المتبقية (0 لو مفيش قفل) — الواجهة بتعرضها للمستخدم.
    fun lockoutRemainingSec(ctx: Context): Long {
        val left = (Cfg.str(PIN_UNTIL_KEY, "0").toLongOrNull() ?: 0L) - System.currentTimeMillis()
        return if (left > 0) (left + 999L) / 1000L else 0L
    }

    fun removePin(ctx: Context) {
        Cfg.put(PIN_HASH_KEY, "")
        Cfg.put(PIN_SALT_KEY, "")
        Cfg.put(PIN_FAILS_KEY, "0")
        Cfg.put(PIN_UNTIL_KEY, "0")
        sessionUnlocked = false
    }

    // ===================== وضع الإخفاء =====================
    fun isRedactOn(ctx: Context): Boolean = Cfg.bool(REDACT_KEY, false)

    fun setRedactOn(ctx: Context, v: Boolean) {
        Cfg.put(REDACT_KEY, if (v) "1" else "0")
    }

    // نفس أنماط الاستخراج في StudioIntel.extract (نسخة منها — extract مبيتنقلش من مكانه).
    // مفيش regex لأسماء أشخاص من نص حر — الأطراف المعروفين بس هم اللي بيتمسحوا.
    private val PHONE_RE = Regex("(?:\\+?20|0)?1[0125][0-9]{8}")
    private val AMOUNT_RE = Regex("[0-9][0-9,.]*\\s*(جنيه|ج\\.م|دولار|ريال|درهم|يورو|ألف|مليون|مليار)")
    private val DATE_RE = Regex("[0-9]{1,2}/[0-9]{1,2}(/[0-9]{2,4})?|بكرة|بعد بكرة|الأحد|الاثنين|الثلاثاء|الأربعاء|الخميس|الجمعة|السبت|يناير|فبراير|مارس|أبريل|مايو|يونيو|يوليو|أغسطس|سبتمبر|أكتوبر|نوفمبر|ديسمبر")

    fun redact(text: String, parties: List<String>): String {
        var out = text
        out = PHONE_RE.replace(out, "•••")
        out = AMOUNT_RE.replace(out, "•••")
        out = DATE_RE.replace(out, "•••")
        for (nm in parties) {
            val n = nm.trim()
            if (n.isBlank() || n == "أنا" || n == "طرف تاني") continue
            out = out.replace(n, "•••")
        }
        return out
    }

    // ===================== قايمة الاستثناء (تفويض) =====================
    fun excludedUris(ctx: Context): MutableSet<String> = StudioIntel.excludedUris(ctx)

    fun saveExcluded(ctx: Context, set: Set<String>) {
        StudioIntel.saveExcluded(ctx, set)
    }

    fun isExcluded(ctx: Context, uri: String): Boolean = StudioIntel.isExcluded(ctx, uri)

    fun addExcluded(ctx: Context, uri: String) {
        val set = excludedUris(ctx)
        if (set.add(uri.trim())) saveExcluded(ctx, set)
    }

    fun removeExcluded(ctx: Context, uri: String) {
        val set = excludedUris(ctx)
        if (set.remove(uri.trim())) saveExcluded(ctx, set)
    }

    // ===================== إحصائيات وملخص دوري =====================
    fun callDocs(ctx: Context): List<Lecture.Doc> {
        return try {
            Lecture.list(ctx).filter { Studio.typeOf(it).code == "call" }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    class Stats(val total: Int, val byType: Map<String, Int>, val callCount: Int, val totalCallSec: Double, val avgCallSec: Double, val topContacts: List<Pair<String, Int>>)

    fun stats(ctx: Context): Stats {
        val docs = try {
            Lecture.list(ctx)
        } catch (_: Throwable) {
            emptyList<Lecture.Doc>()
        }
        val byType = docs.groupBy { Studio.typeOf(it).code }.mapValues { it.value.size }
        val calls = docs.filter { Studio.typeOf(it).code == "call" }
        val totalSec = calls.sumOf { it.durSec }
        val avgSec = if (calls.isNotEmpty()) totalSec / calls.size else 0.0
        val contacts = HashMap<String, Int>()
        for (d in calls) {
            val names = synchronized(d) { d.parties.toList() }
            for (nm in names) {
                val n = nm.trim()
                if (n.isBlank() || n == "أنا") continue
                contacts[n] = (contacts[n] ?: 0) + 1
            }
        }
        val top = contacts.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
        return Stats(docs.size, byType, calls.size, totalSec, avgSec, top)
    }

    class DayDigest(val date: String, val count: Int, val totalDurSec: Double, val topics: List<String>, val decisions: List<String>)

    // ملخص المكالمات لآخر days يوم — من النصوص المحفوظة بس، من غير أي طلب Gemini جديد.
    // المواضيع/القرارات: من summary/organized لو موجودين، وإلا من minutes/promises المخزنة، وإلا فاضي.
    // created = وقت بداية التفريغ مش تاريخ المكالمة نفسها (Rec.dateSec مش متخزنة) — فاليوم هنا يوم التفريغ.
    fun digest(ctx: Context, days: Int): List<DayDigest> {
        val calls = callDocs(ctx)
        if (calls.isEmpty()) return emptyList()
        val from = if (days > 0) System.currentTimeMillis() - days.toLong() * 86400000L else 0L
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val byDay = LinkedHashMap<String, ArrayList<Lecture.Doc>>()
        for (d in calls) {
            if (d.created <= 0 || d.created < from) continue
            val day = try {
                fmt.format(Date(d.created))
            } catch (_: Throwable) {
                continue
            }
            byDay.getOrPut(day) { ArrayList() }.add(d)
        }
        val out = ArrayList<DayDigest>()
        for ((day, docs) in byDay.entries.sortedByDescending { it.key }) {
            val topics = ArrayList<String>()
            val decisions = ArrayList<String>()
            for (d in docs) {
                val sum = synchronized(d) { d.summary }
                val org = synchronized(d) { d.organized }
                val picked = docTopics(d, sum, org)
                for (t in picked) if (t !in topics) topics.add(t)
                val dec = docDecisions(ctx, d)
                for (x in dec) if (x !in decisions) decisions.add(x)
                if (topics.size >= 5 && decisions.size >= 5) break
            }
            val totalSec = docs.sumOf { it.durSec }
            out.add(DayDigest(day, docs.size, totalSec, topics.take(5), decisions.take(5)))
        }
        return out
    }

    // سطور مواضيع من ملخص/تنظيم الدوكيومنت — عناوين وسطور قصيرة بس، من غير نصوص طويلة.
    private fun docTopics(d: Lecture.Doc, sum: String, org: String): List<String> {
        val src = if (sum.isNotBlank()) sum else org
        if (src.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (line in src.lines()) {
            val t = line.trim().removePrefix("#").removePrefix("#").trim().removePrefix("-").removePrefix("•").trim()
            if (t.isBlank() || t.length > 120) continue
            out.add(t)
            if (out.size >= 3) break
        }
        return out
    }

    // قرارات/وعود الدوكيومنت — من minutes المخزنة في organized، وإلا من promises المخزنة (من غير طلب شبكة).
    private fun docDecisions(ctx: Context, d: Lecture.Doc): List<String> {
        val out = ArrayList<String>()
        try {
            val min = StudioIntel.minutes(ctx, d)
            for (x in min.decisions) out.add(x)
            for (x in min.tasks) out.add(x)
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) {
            try {
                for (p in StudioIntel.promises(ctx, d)) {
                    val who = p.who.ifBlank { "؟" }
                    val when = if (p.whenText.isNotBlank()) " (" + p.whenText + ")" else ""
                    out.add(who + ": " + p.what + when)
                }
            } catch (_: Throwable) {
            }
        }
        return out.take(3)
    }

    // ===================== قص ومشاركة مقطع =====================
    class ClipResult(val audio: File?, val text: String, val note: String)

    // بيقص الصوت من المصدر الأصلي (16kHz mono — جودة تفريغ، مش جودة الملف الأصلي) ويكتب ملف WAV
    // في الكاش. لو المصدر مش متاح (الملف اتمسح/اتنقل أو اللينك مبيتحلش) بيرجّع نص بس مع السبب.
    // لازم يتنده من خيط خلفي (القص ممكن يعمل شبكة للينكات).
    fun cutClip(ctx: Context, d: Lecture.Doc, startSec: Double, endSec: Double): ClipResult {
        val s = startSec.coerceAtLeast(0.0)
        val dur = if (d.durSec > 0) d.durSec else endSec
        val e = endSec.coerceIn(0.0, dur)
        val text = clipText(d, s, e)
        if (e - s < 0.5) return ClipResult(null, text, "المدى المحدد قصير أو مش صالح — النص بس")
        val chunk = try {
            Lecture.cutAudioClip(ctx, d, s, e)
        } catch (ex: Throwable) {
            LogStore.err("StudioPrivacy:cut", ex)
            null
        } ?: return ClipResult(null, text, "تعذّر قص الصوت — المصدر مش متاح دلوقتي (الملف اتمسح/اتنقل أو اللينك مبيتحلش) — النص بس")
        return try {
            val dir = File(ctx.cacheDir, "studio_clips").also { it.mkdirs() }
            val safe = TrLib.safeName(d.id + "_" + s.toInt() + "_" + e.toInt())
            val f = File(dir, safe + ".wav")
            f.writeBytes(chunk.bytes)
            ClipResult(f, text, "صوت")
        } catch (ex: Throwable) {
            LogStore.err("StudioPrivacy:cutWrite", ex)
            ClipResult(null, text, "تعذّرت كتابة ملف المقطع — النص بس")
        }
    }

    private fun clipText(d: Lecture.Doc, s: Double, e: Double): String {
        val segs = try {
            Studio.displaySegs(d)
        } catch (_: Throwable) {
            emptyList<Lecture.Seg>()
        }
        val sb = StringBuilder()
        for (seg in segs) {
            if (seg.end < s || seg.start > e) continue
            sb.append('[').append(Lecture.fmtTime(seg.start)).append("] ").append(seg.text).append('\n')
        }
        val t = sb.toString().trim()
        return if (t.isNotBlank()) t else "مفيش نص مفرّغ في المدى ده"
    }
}
