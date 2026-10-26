package com.tttt.subtitler

import android.graphics.drawable.GradientDrawable
import android.view.View

/*
 * Frosted button background for TV only.
 * The frost lives inside the button bounds only.
 * The launcher background behind the button is never blurred.
 * Strength is 55 percent opacity, inside the 50 to 60 percent range
 * the user asked for, with a soft white edge so the button reads
 * clearly from the couch.
 */
object TvBlur {
    const val FROST_ALPHA = 140

    fun frostDrawable(radiusDp: Float, density: Float): GradientDrawable {
        val d = GradientDrawable()
        val fill = (FROST_ALPHA shl 24) or 0x000000
        d.setColor(fill)
        d.setStroke(
            (1.5f * density + 0.5f).toInt(),
            (0x66 shl 24) or 0xFFFFFF
        )
        d.cornerRadius = radiusDp * density
        return d
    }

    fun <T : View> T.frosted(radiusDp: Float): T {
        val density = resources.displayMetrics.density
        background = frostDrawable(radiusDp, density)
        return this
    }

    fun <T : View> T.frostedButton(radiusDp: Float): T {
        val density = resources.displayMetrics.density
        background = frostDrawable(radiusDp, density)
        elevation = 4f * density
        return this
    }
}
