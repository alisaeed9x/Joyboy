package com.tttt.subtitler

import org.json.JSONObject
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v364) بيانات OmniRoute من هيدرات الرد (X-OmniRoute-Provider / Model / Decision / Latency-Ms / Response-Cost / Tokens-In / Tokens-Out /
 * Fallback-Attempts / Cache-Hit / Version). بتتسجّل لكل رد فيه الهيدرات دي (ترجمة أو شاشة الـ AI)، وبتتحفظ يوميًا آخر 35 يوم.
 * الخدمات المجانية/الاشتراكات ممكن تطلع تكلفتها 0 — ده من OmniRoute نفسه.
 */
object OmniMeta {
    class Meta(val provider: String, val model: String, val strategy: String, val latencyMs: Int, val costUsd: Double,
               val tokIn: Int, val tokOut: Int, val fallbacks: Int, val cacheHit: Boolean, val at: Long)
    class Sum(val req: Int, val cost: Double, val tIn: Long, val tOut: Long, val fb: Int, val prov: Map<String, Int>)

    @Volatile var last: Meta? = null
    @Volatile var version = ""

    private fun num(s: String?): Double = s?.trim()?.toDoubleOrNull() ?: 0.0
    private fun dayKey(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** بيتنادى بعد قراءة responseCode؛ مايعملش حاجة لو الرد مش من OmniRoute (مفيش هيدرات) */
    fun capture(c: HttpURLConnection) {
        try {
            val prov = c.getHeaderField("X-OmniRoute-Provider")?.trim().orEmpty()
            val dec = c.getHeaderField("X-OmniRoute-Decision")?.trim().orEmpty()
            val model = c.getHeaderField("X-OmniRoute-Model")?.trim().orEmpty()
            if (prov.isEmpty() && dec.isEmpty() && model.isEmpty()) return
            val parts = dec.split(";").mapNotNull { p -> p.trim().split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val lat = (c.getHeaderField("X-OmniRoute-Latency-Ms")?.trim()?.toDoubleOrNull() ?: parts["latency_ms"]?.toDoubleOrNull() ?: 0.0).toInt()
            val hit = c.getHeaderField("X-OmniRoute-Cache-Hit")?.trim()?.lowercase() in listOf("true", "1", "hit", "yes")
            c.getHeaderField("X-OmniRoute-Version")?.trim()?.takeIf { it.isNotEmpty() }?.let { version = it }
            val m = Meta(prov.ifEmpty { parts["provider"] ?: "?" }, model, parts["strategy"] ?: "", lat,
                num(c.getHeaderField("X-OmniRoute-Response-Cost")), num(c.getHeaderField("X-OmniRoute-Tokens-In")).toInt(),
                num(c.getHeaderField("X-OmniRoute-Tokens-Out")).toInt(), num(c.getHeaderField("X-OmniRoute-Fallback-Attempts")).toInt(), hit, System.currentTimeMillis())
            last = m; save(m)
        } catch (_: Throwable) {}
    }

    /** (v365) تسجيل رد جه من الراوتر الداخلي (مفيش هيدرات) */
    fun record(provider: String, model: String, strategy: String, latencyMs: Int, fallbacks: Int) {
        val m = Meta(provider, model, strategy, latencyMs, 0.0, 0, 0, fallbacks, false, System.currentTimeMillis())
        last = m; save(m)
    }

    @Synchronized private fun save(m: Meta) {
        if (!Cfg.isReady()) return
        val root = try { JSONObject(Cfg.str("omni_stats").ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
        val k = dayKey(m.at)
        val d = root.optJSONObject(k) ?: JSONObject().also { root.put(k, it) }
        d.put("r", d.optInt("r", 0) + 1); d.put("c", d.optDouble("c", 0.0) + m.costUsd)
        d.put("i", d.optLong("i", 0L) + m.tokIn); d.put("o", d.optLong("o", 0L) + m.tokOut); d.put("f", d.optInt("f", 0) + m.fallbacks)
        val p = d.optJSONObject("p") ?: JSONObject().also { d.put("p", it) }
        p.put(m.provider, p.optInt(m.provider, 0) + 1)
        root.keys().asSequence().toList().sorted().dropLast(35).forEach { root.remove(it) }
        Cfg.put("omni_stats", root.toString())
    }

    @Synchronized fun range(n: Int, now: Long = System.currentTimeMillis()): Sum {
        var req = 0; var cost = 0.0; var ti = 0L; var to = 0L; var fb = 0; val pv = HashMap<String, Int>()
        if (!Cfg.isReady()) return Sum(0, 0.0, 0L, 0L, 0, pv)
        val root = try { JSONObject(Cfg.str("omni_stats").ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
        for (i in 0 until n) {
            val o = root.optJSONObject(dayKey(now - i * 86_400_000L)) ?: continue
            req += o.optInt("r", 0); cost += o.optDouble("c", 0.0); ti += o.optLong("i", 0L); to += o.optLong("o", 0L); fb += o.optInt("f", 0)
            o.optJSONObject("p")?.let { p -> p.keys().forEach { pv[it] = (pv[it] ?: 0) + p.optInt(it, 0) } }
        }
        return Sum(req, cost, ti, to, fb, pv)
    }

    fun usd(v: Double): String = if (v <= 0.0) "\$0" else String.format(Locale.US, "\$%.4f", v)

    /** سطر شريط الحالة: حالة OmniRoute + آخر رد (مين رد، أنهي موديل، الزمن، تحويلات، كاش، تكلفة) */
    fun statusLine(): String {
        val head = if (InternalRouter.hasPool()) InternalRouter.summary() else "🔴 مفيش مفاتيح — ضيف مفتاح جيميناي"
        val m = last ?: return head
        val s = (System.currentTimeMillis() - m.at) / 1000
        val ago = if (s < 60) "${s}ث" else "${s / 60}د"
        return head + "\nآخر رد: " + m.provider + (if (m.model.isNotEmpty()) " · " + m.model else "") +
            (if (m.latencyMs > 0) " · ${m.latencyMs}ms" else "") + (if (m.fallbacks > 0) " · ↪ ${m.fallbacks} تحويل" else "") +
            (if (m.cacheHit) " · ⚡ كاش" else "") + (if (m.costUsd > 0) " · " + usd(m.costUsd) else "") + " · من $ago"
    }
}
