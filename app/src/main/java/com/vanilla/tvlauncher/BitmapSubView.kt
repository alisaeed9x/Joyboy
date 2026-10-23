package com.vanilla.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.media3.common.text.Cue

/**
 * يرسم الترجمة المصوّرة (PGS/DVB) — P15 (D-018). الـ View ده بيتحط فوق السطح بنفس مقاسه بالظبط (PlayerLayer.applyFit)،
 * والإحداثيات نسب من Cue زي media3. الحساب نفسه في SubLogic.bitmapRect (مختبر على JVM)؛ الرسم هنا غير مجرَّب على جهاز.
 */
class BitmapSubView(ctx: Context) : View(ctx) {
    private var cues: List<Cue> = emptyList()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    fun setCues(l: List<Cue>) { cues = l; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (q in cues) {
            val b = q.bitmap ?: continue
            if (b.isRecycled) continue
            val r = SubLogic.bitmapRect(w, h, b.width, b.height, q.position, q.positionAnchor, q.line, q.lineAnchor, q.size, q.bitmapHeight)
            if (r[2] <= r[0] || r[3] <= r[1]) continue
            dst.set(r[0], r[1], r[2], r[3]); c.drawBitmap(b, null, dst, paint)
        }
    }
}
