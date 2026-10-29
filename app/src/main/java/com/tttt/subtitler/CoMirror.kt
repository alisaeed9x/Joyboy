package com.tttt.subtitler

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

/**
 * (v647) كاست ②: مرآة معكوسة — صفحة متصفح التلفزيون بتتعرض على الموبايل (لقطات JPEG صغيرة بتتسحب من /bframe).
 * اللمس على الصورة بيتبعت للتلفزيون: ضغطة (mtap) وسحب (mscroll) بنسب من حجم الصورة.
 */
class CoMirror(ctx: Context, private val onClose: () -> Unit) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
    private val hint = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; text = "📺 بجيب صفحة التلفزيون…" }
    @Volatile private var running = false
    private var bw = 0; private var bh = 0
    private var dx = 0f; private var dy = 0f; private var lastY = 0f; private var dt = 0L
    private var acc = 0f; private var lastSend = 0L; private var moved = false

    init {
        visibility = View.GONE; setBackgroundColor(Color.BLACK)
        addView(img, FrameLayout.LayoutParams(-1, -1))
        addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun start() {
        if (running) return
        running = true; bw = 0; bh = 0; img.setImageBitmap(null)
        hint.text = "📺 بجيب صفحة التلفزيون…"; hint.visibility = View.VISIBLE
        visibility = View.VISIBLE
        Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false; visibility = View.GONE }

    private fun loop() {
        CoSurf.post(JSONObject().put("op", "openweb"))   // لو المتصفح مش مفتوح على التلفزيون يفتحه
        var last = 0; var fails = 0
        while (running) {
            val (c, b) = RemoteClient.getBytes("/bframe?w=640&q=45&h=$last", 4000)
            if (!running) break
            if (c == 200 && b.isNotEmpty()) {
                fails = 0; last = java.util.Arrays.hashCode(b)
                val bmp = try { BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Throwable) { null }
                if (bmp != null) ui.post { if (running) { bw = bmp.width; bh = bmp.height; img.setImageBitmap(bmp); hint.visibility = View.GONE } }
            } else if (c == 200) {
                fails = 0; try { Thread.sleep(80) } catch (_: InterruptedException) {}   // الصفحة ما اتغيّرتش
            } else if (c == 204) {
                ui.post { if (running) { hint.text = "افتح المتصفح على التلفزيون…"; hint.visibility = View.VISIBLE } }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            } else {
                if (++fails >= 6) {
                    running = false
                    ui.post { try { Notice.show(context, "📺 المرآة وقفت — التلفزيون مش بيرد", 2600L) } catch (_: Throwable) {}; onClose() }
                } else try { Thread.sleep(400) } catch (_: InterruptedException) {}
            }
        }
    }

    /** مستطيل الصورة جوه الـView (FIT_CENTER): offX, offY, عرض, ارتفاع */
    private fun rect(): FloatArray? {
        if (bw <= 0 || bh <= 0 || width <= 0 || height <= 0) return null
        val s = Math.min(width.toFloat() / bw, height.toFloat() / bh)
        val w = bw * s; val h = bh * s
        return floatArrayOf((width - w) / 2f, (height - h) / 2f, w, h)
    }

    private fun flushScroll() {
        val r = rect() ?: return
        if (acc == 0f) return
        CoSurf.post(JSONObject().put("op", "mscroll").put("r", (-acc / r[3]).toDouble()))
        acc = 0f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y; lastY = e.y; dt = System.currentTimeMillis(); moved = false; acc = 0f }
            MotionEvent.ACTION_MOVE -> {
                val d = e.y - lastY; lastY = e.y
                if (!moved && (Math.abs(e.x - dx) > slop || Math.abs(e.y - dy) > slop)) moved = true
                if (moved) {
                    acc += d
                    val n = System.currentTimeMillis()
                    if (n - lastSend >= 60L) { flushScroll(); lastSend = n }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && System.currentTimeMillis() - dt < 400L) {
                    val r = rect()
                    if (r != null) {
                        val rx = (e.x - r[0]) / r[2]; val ry = (e.y - r[1]) / r[3]
                        if (rx in 0f..1f && ry in 0f..1f) CoSurf.post(JSONObject().put("op", "mtap").put("rx", rx.toDouble()).put("ry", ry.toDouble()))
                    }
                } else flushScroll()
            }
        }
        return true
    }
}
