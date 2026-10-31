package com.tttt.subtitler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * (v681) معاينة مصغّرة لشكل السكن (ترتيب الشريط · الهيرو · الصفوف · الأيقونات) — بتتعرض جنب اسم كل سكن في «اختيار السكن»
 * عشان تشوف الترتيب قبل ما تختار. مرسومة بالكود (مفيش صور) وبألوان السمة الحالية.
 * المعرّفات: hub · classic · glass · samsung · lg · tcl · xiaomi
 */
class TvSkinPreview(ctx: Context, private val skinId: String, private val th: Theme) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val dark = Theme.lum(th.bg) < 0.35

    private val bg = th.bg
    private val card = if (dark) 0xFF2A3040.toInt() else 0xFFDADFE8.toInt()
    private val card2 = if (dark) 0xFF3A4258.toInt() else 0xFFC4CBD8.toInt()
    private val ink = if (dark) 0xFFE8ECF5.toInt() else 0xFF2A2F3A.toInt()
    private val pri = th.primary

    private fun rr(c: Canvas, l: Float, t: Float, rt: Float, b: Float, rad: Float, color: Int, stroke: Int = 0, sw: Float = 0f) {
        r.set(l, t, rt, b)
        p.style = Paint.Style.FILL; p.color = color
        c.drawRoundRect(r, rad, rad, p)
        if (sw > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = stroke; c.drawRoundRect(r, rad, rad, p); p.style = Paint.Style.FILL }
    }

    private fun dot(c: Canvas, x: Float, y: Float, rad: Float, color: Int) { p.style = Paint.Style.FILL; p.color = color; c.drawCircle(x, y, rad, p) }

    /** صف كروت متساوية من x0 لـx1 */
    private fun cardsRow(c: Canvas, x0: Float, x1: Float, y: Float, h: Float, n: Int, gap: Float, rad: Float, color: Int, focusIdx: Int = -1) {
        val w = (x1 - x0 - gap * (n - 1)) / n
        for (i in 0 until n) {
            val l = x0 + i * (w + gap)
            rr(c, l, y, l + w, y + h, rad, color)
            if (i == focusIdx) rr(c, l - 1.5f, y - 1.5f, l + w + 1.5f, y + h + 1.5f, rad + 1f, 0x00000000, Color.WHITE, 2.2f)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val u = w / 160f     // وحدة: العرض = 160
        // الشاشة
        rr(c, 0f, 0f, w, h, 6f * u, bg, if (dark) 0x66FFFFFF else 0x44000000, 1.2f * u)
        val m = 7f * u
        when (skinId) {
            "hub" -> {   // Android TV: تابات نص بخط تحت · هيرو قصير · بانرات تطبيقات · كروت 16:9
                var x = m
                for (i in 0 until 4) { rr(c, x, m, x + 18f * u, m + 3.5f * u, 1.5f * u, if (i == 0) ink else card2); x += 23f * u }
                rr(c, m, m + 6.5f * u, m + 18f * u, m + 7.8f * u, 0.6f * u, pri)
                rr(c, m, 20f * u, w - m, 52f * u, 4f * u, card2)
                rr(c, m + 5f * u, 38f * u, m + 40f * u, 42f * u, 1.5f * u, ink)
                rr(c, m + 5f * u, 45f * u, m + 24f * u, 47.5f * u, 1f * u, card)
                cardsRow(c, m, w - m, 57f * u, 14f * u, 4, 4f * u, 2.5f * u, pri and 0x00FFFFFF or 0x99000000.toInt(), 0)
                cardsRow(c, m, w - m, 75f * u, 15f * u, 4, 4f * u, 2.5f * u, card)
            }
            "classic" -> {   // كلاسيك: هيرو + صفوف أفقية متتالية (سجل · يوتيوب · فولدرات · تطبيقات)
                rr(c, m, m, w - m, 30f * u, 4f * u, card2)
                rr(c, m + 5f * u, 18f * u, m + 34f * u, 22f * u, 1.5f * u, ink)
                rr(c, m, 33f * u, m + 22f * u, 35.5f * u, 1f * u, ink)
                cardsRow(c, m, w - m, 38f * u, 12f * u, 5, 3f * u, 2.5f * u, card, 1)
                rr(c, m, 54f * u, m + 22f * u, 56.5f * u, 1f * u, ink)
                cardsRow(c, m, w - m, 59f * u, 12f * u, 5, 3f * u, 2.5f * u, card)
                for (i in 0 until 7) dot(c, m + 6f * u + i * 20f * u, 84f * u, 5.5f * u, if (i == 2) pri else card2)
            }
            "glass" -> {   // tvOS / Bento: لوحات زجاج متجاورة · أيقونات كبيرة مربعة مدوّرة بالنص
                rr(c, m, m, w * 0.62f, 44f * u, 7f * u, card2, if (dark) 0x66FFFFFF else 0x33000000, 1f * u)
                rr(c, m + 8f * u, 22f * u, m + 46f * u, 27f * u, 2f * u, ink)
                rr(c, w * 0.62f + 4f * u, m, w - m, 24f * u, 7f * u, card, if (dark) 0x44FFFFFF else 0x22000000, 1f * u)
                rr(c, w * 0.62f + 4f * u, 27f * u, w - m, 44f * u, 7f * u, card, if (dark) 0x44FFFFFF else 0x22000000, 1f * u)
                val s = 17f * u; val gx = (w - (s * 4 + 5f * u * 3)) / 2f
                for (i in 0 until 4) rr(c, gx + i * (s + 5f * u), 50f * u, gx + i * (s + 5f * u) + s, 50f * u + s, 5f * u, if (i == 1) pri else card2, if (i == 1) Color.WHITE else 0, 2f * u)
                cardsRow(c, m, w - m, 72f * u, 16f * u, 4, 5f * u, 5f * u, card)
            }
            "samsung" -> {   // Tizen: شريط أيقونات جانبي ضيق (يمين) · هيرو حاد بزرار «شاهد» · مربعات تطبيقات صغيرة
                val sideW = 13f * u
                rr(c, w - m - sideW, m, w - m, h - m, 3f * u, card)
                for (i in 0 until 5) dot(c, w - m - sideW / 2f, 17f * u + i * 14f * u, 3.2f * u, if (i == 0) pri else card2)
                val right = w - m - sideW - 5f * u
                rr(c, m, m, right, 46f * u, 1.5f * u, card2)
                rr(c, m + 5f * u, 24f * u, m + 40f * u, 28f * u, 1f * u, ink)
                rr(c, m + 5f * u, 33f * u, m + 30f * u, 41f * u, 1.5f * u, pri)
                for (i in 0 until 6) rr(c, m + i * 17.5f * u, 51f * u, m + i * 17.5f * u + 14f * u, 65f * u, 1.5f * u, if (i == 1) pri else card2)
                cardsRow(c, m, right, 69f * u, 17f * u, 4, 3f * u, 1.5f * u, card)
            }
            "lg" -> {   // webOS: من غير تابات · هيرو مدوّر · 4 كروت مميزات · دوك زجاجي بأيقونات بيضاء
                rr(c, m, m, w - m, 38f * u, 8f * u, card2)
                rr(c, m + 6f * u, 22f * u, m + 40f * u, 26f * u, 1.5f * u, ink)
                cardsRow(c, m, w - m, 42f * u, 17f * u, 4, 4f * u, 5f * u, card)
                rr(c, m + 6f * u, 66f * u, w - m - 6f * u, h - m, 8f * u, if (dark) 0x33FFFFFF else 0x33000000, if (dark) 0x55FFFFFF else 0x33000000, 1f * u)
                for (i in 0 until 6) rr(c, w / 2f - 6 * 12f * u / 2f + i * 12f * u + 1f * u, 69.5f * u, w / 2f - 6 * 12f * u / 2f + i * 12f * u + 11f * u, h - m - 3.5f * u, 3f * u, if (i == 1) Color.WHITE else 0xCCFFFFFF.toInt())
            }
            "tcl" -> {   // Google TV: كبسولة تابات علوية بتحديد أبيض · هيرو ملء العرض بنقط · صور 16:9 · دواير تطبيقات
                rr(c, m, m, m + 64f * u, m + 8f * u, 4f * u, card)
                rr(c, m + 2f * u, m + 1.5f * u, m + 18f * u, m + 6.5f * u, 3f * u, ink)
                for (i in 1 until 4) rr(c, m + 2f * u + i * 15f * u + 3f * u, m + 3f * u, m + 2f * u + i * 15f * u + 14f * u, m + 5f * u, 1f * u, card2)
                rr(c, m, 20f * u, w - m, 52f * u, 5f * u, card2)
                rr(c, m + 6f * u, 37f * u, m + 40f * u, 41f * u, 1.5f * u, ink)
                for (i in 0 until 3) dot(c, w / 2f - 5f * u + i * 5f * u, 48f * u, 1.1f * u, if (i == 0) ink else card)
                cardsRow(c, m, w - m, 57f * u, 13f * u, 4, 4f * u, 3f * u, card, 0)
                for (i in 0 until 6) dot(c, m + 8f * u + i * 22f * u, 83f * u, 6f * u, if (i == 0) pri else card2)
            }
            "xiaomi" -> {   // PatchWall: سايدبار بتسميات على الشمال · حائط بوسترات طولية · شبكة مربعة
                val sideW = 30f * u
                rr(c, m, m, m + sideW, h - m, 4f * u, card)
                for (i in 0 until 4) {
                    rr(c, m + 3f * u, 12f * u + i * 14f * u, m + sideW - 3f * u, 22f * u + i * 14f * u, 2.5f * u, if (i == 0) pri else card2)
                }
                val left = m + sideW + 5f * u
                val cw = (w - m - left - 3f * u * 4) / 5f
                for (i in 0 until 5) rr(c, left + i * (cw + 3f * u), m, left + i * (cw + 3f * u) + cw, 50f * u, 2f * u, card2, if (i == 0) Color.WHITE else 0, 1.8f * u)
                for (i in 0 until 5) rr(c, left + i * (cw + 3f * u), 55f * u, left + i * (cw + 3f * u) + cw, 55f * u + cw, 2.5f * u, card)
            }
            else -> {
                rr(c, m, m, w - m, 30f * u, 4f * u, card2)
                cardsRow(c, m, w - m, 38f * u, 16f * u, 4, 4f * u, 3f * u, card)
            }
        }
    }
}
