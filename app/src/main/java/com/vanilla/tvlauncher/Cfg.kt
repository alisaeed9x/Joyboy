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
}
