package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray

/**
 * (v412) قايمة المواقع المحجوبة بزرار 🚨 الطوارئ في المتصفح.
 * المفتاح = اسم الموقع الأساسي (google من www.google.com / news.google.com / google.com.eg) — لو أقصر من 4 حروف بيبقى الدومين كله (x.com)، والـ IP بيبقى كله.
 *  - الحجب (الغطا الأسود عند الدخول تاني): نفس الاسم الأساسي بالظبط.
 *  - مسح السجل: أي لينك الدومين بتاعه فيه الكلمة دي (زي ما طلبت: كل اللي فيه «google»).
 */
object SiteBlock {
    private const val FILE = "br_blocked.json"
    private val IP = Regex("^[0-9.:]+$")
    private var keys: LinkedHashSet<String>? = null

    fun keyOf(url: String?): String? {
        val u = url ?: return null
        if (!u.startsWith("http", true)) return null
        val h = PopupGuard.hostOf(u)
        if (h.isEmpty()) return null
        if (IP.matches(h) || !h.contains('.')) return h
        val brand = PopupGuard.reg(h).substringBefore('.')
        return if (brand.length >= 4) brand else PopupGuard.reg(h)
    }

    /** لينك يتمسح من السجل لو مفتاح [key] محجوب؟ (الكلمة جوه الدومين) */
    fun purgeMatch(key: String, url: String): Boolean {
        val h = PopupGuard.hostOf(url)
        if (h.isEmpty()) return false
        return if (key.contains('.')) h == key || h.endsWith(".$key") else h.contains(key)
    }

    private fun load(c: Context): LinkedHashSet<String> = keys ?: LinkedHashSet<String>().also { s ->
        try { val a = JSONArray(java.io.File(c.filesDir, FILE).readText()); for (i in 0 until a.length()) a.optString(i, "").takeIf { it.isNotBlank() }?.let { s.add(it) } } catch (_: Exception) {}
        keys = s
    }
    private fun save(c: Context) {
        try { java.io.File(c.filesDir, FILE).writeText(JSONArray(load(c).toList()).toString()) } catch (e: Exception) { LogStore.err("SiteBlock", e) }
    }

    fun list(c: Context): List<String> = synchronized(this) { load(c).toList() }
    fun has(c: Context, key: String): Boolean = synchronized(this) { key in load(c) }
    /** true = اتضاف دلوقتي (مكانش موجود) */
    fun add(c: Context, key: String): Boolean = synchronized(this) { val r = load(c).add(key); if (r) save(c); r }
    fun remove(c: Context, key: String) { synchronized(this) { if (load(c).remove(key)) save(c) } }
}
