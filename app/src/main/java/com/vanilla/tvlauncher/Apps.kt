package com.vanilla.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * اكتشاف وتشغيل تطبيقات Android TV (P9). بنستخدم بس اللي النظام بيسمح بيه: تطبيقات فيها LEANBACK_LAUNCHER
 * (بيتطلب <queries> في الـ Manifest من Android 11). التطبيقات اللي ملهاش نشاط تلفزيوني ما بتظهرش (مش بنحاول نشغّلها).
 * الرجوع من التطبيق للانشر = سلوك النظام (Home/Back)؛ مش بنتحكم فيه.
 */
object AppsStore {
    @Suppress("DEPRECATION")
    fun list(ctx: Context): List<AppEntry> = try {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        AppsLogic.clean(pm.queryIntentActivities(i, 0).map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }, ctx.packageName)
    } catch (_: Throwable) { emptyList() }

    fun icon(ctx: Context, pkg: String): Drawable? = try {
        val pm = ctx.packageManager
        pm.getApplicationBanner(pkg) ?: pm.getApplicationIcon(pkg)
    } catch (_: Throwable) { null }

    /** false = ما اتفتحش (اتشال/مش متوافق) */
    fun launch(act: Activity, pkg: String): Boolean = try {
        val pm = act.packageManager
        val it = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (it == null) false else { act.startActivity(it); true }
    } catch (_: Throwable) { false }
}
