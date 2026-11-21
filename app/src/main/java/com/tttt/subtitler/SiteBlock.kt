package com.tttt.subtitler

import android.content.Context

/**
 * (v412) قايمة المواقع المحجوبة بزرار 🚨 الطوارئ في المتصفح — (v1184) اتلغت: الزرار اتشال والـhas دايمًا false.
 * المفتاح = اسم الموقع الأساسي (google من www.google.com / news.google.com / google.com.eg) — لو أقصر من 4 حروف بيبقى الدومين كله (x.com)، والـ IP بيبقى كله.
 *  - الحجب (الغطا الأسود عند الدخول تاني): نفس الاسم الأساسي بالظبط.
 *  - مسح السجل: أي لينك الدومين بتاعه فيه الكلمة دي (زي ما طلبت: كل اللي فيه «google»).
 */
object SiteBlock {
    private val IP = Regex("^[0-9.:]+$")

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

    // (v1184) ميزة الحجب اتلغت مع زرار 🚨: مفيش قايمة محجوبة ولا بتتقرا من ملف — الدوال سايباها عشان باقي الكود يفضل يتكومبايل، وكلها من غير أثر.
    @Suppress("UNUSED_PARAMETER") fun list(c: Context): List<String> = emptyList()
    @Suppress("UNUSED_PARAMETER") fun has(c: Context, key: String): Boolean = false
    @Suppress("UNUSED_PARAMETER") fun add(c: Context, key: String): Boolean = false
    @Suppress("UNUSED_PARAMETER") fun remove(c: Context, key: String) {}
}
