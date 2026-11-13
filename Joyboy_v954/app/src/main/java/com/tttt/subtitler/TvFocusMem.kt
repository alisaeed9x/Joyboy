package com.tttt.subtitler

import android.view.KeyEvent
import android.view.View

/**
 * (v597 م1) ذاكرة التركيز المشتركة بين السكِنز: كل شاشة (home / videos / folder / settings…) بتفتكر آخر عنصر اتركّز عليه
 * (بالـtag)، فلما ترجع بـBack يرجع التركيز لنفس العنصر مهما كان شكل السكِن.
 * (الفولدرات ليها استرجاع قديم في closeFolder — ده تعميمه لأي شاشة وأي سكِن.)
 */
object TvFocusMem {
    private val mem = HashMap<String, String>()

    fun note(screen: String, v: View?) {
        val tag = v?.tag as? String ?: return
        if (tag.isNotEmpty()) mem[screen] = tag
    }


    /** رجّع التركيز لآخر عنصر محفوظ للشاشة جوه root. true لو لقاه وركّز عليه */
    fun restore(root: View, screen: String): Boolean {
        val tag = mem[screen] ?: return false
        return try { root.findViewWithTag<View>(tag)?.requestFocus() == true } catch (_: Throwable) { false }
    }

    fun forget(screen: String) { mem.remove(screen) }
    fun clear() { mem.clear() }
}

/** (v597 م1) ترجمة أزرار الريموت الملونة لحدث السكِن */
object TvKeys {
    fun colorOf(keyCode: Int): TvColorKey? = when (keyCode) {
        KeyEvent.KEYCODE_PROG_RED -> TvColorKey.RED
        KeyEvent.KEYCODE_PROG_GREEN -> TvColorKey.GREEN
        KeyEvent.KEYCODE_PROG_YELLOW -> TvColorKey.YELLOW
        KeyEvent.KEYCODE_PROG_BLUE -> TvColorKey.BLUE
        else -> null
    }
}
