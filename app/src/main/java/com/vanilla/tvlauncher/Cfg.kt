package com.vanilla.tvlauncher

import android.content.Context
import android.content.SharedPreferences

/** إعدادات محفوظة محليًا (FR-SET-1) — نسخة واحدة بس. */
object Cfg {
    private var p: SharedPreferences? = null
    fun init(c: Context) { if (p == null) p = c.applicationContext.getSharedPreferences("vtl", Context.MODE_PRIVATE) }
    fun str(k: String, d: String): String = p?.getString(k, d) ?: d
    fun int(k: String, d: Int): Int = p?.getInt(k, d) ?: d
    fun bool(k: String, d: Boolean): Boolean = p?.getBoolean(k, d) ?: d
    fun put(k: String, v: String) { p?.edit()?.putString(k, v)?.apply() }
    fun put(k: String, v: Int) { p?.edit()?.putInt(k, v)?.apply() }
    fun put(k: String, v: Boolean) { p?.edit()?.putBoolean(k, v)?.apply() }

    /** P15 (نسخ احتياطي): كل الإعدادات الحالية (نص/رقم/منطقي بس) */
    fun snapshot(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in p?.all ?: emptyMap<String, Any?>()) if (v is String || v is Int || v is Boolean) out[k] = v
        return out
    }
    /** P15 (استعادة): بيستبدل كل الإعدادات بـ m في عملية واحدة (commit متزامن عشان إعادة البناء بعدها تقرا الجديد) */
    fun restoreAll(m: Map<String, Any>): Boolean {
        val e = p?.edit() ?: return false
        e.clear()
        for ((k, v) in m) when (v) { is String -> e.putString(k, v); is Int -> e.putInt(k, v); is Boolean -> e.putBoolean(k, v) }
        return e.commit()
    }
}
