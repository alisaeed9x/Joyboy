package com.tttt.joyboy.plugin.aniyomi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * جسر Joyboy مع البلاجن. call(method, arg=JSON) بيرد Bundle فيه "json".
 * بيقبل المنادي بس لو موقّع بنفس مفتاح البلاجن (يعني Joyboy).
 * المرحلة 1: ping + extensions (إضافات Aniyomi المثبّتة). التشغيل الفعلي للإضافات (sources/search/episodes/streams) في المرحلة 2.
 */
class BridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val out: JSONObject = try {
            val ctx = context ?: throw IllegalStateException("no context")
            val caller = callingPackage
            if (caller == null || ctx.packageManager.checkSignatures(caller, ctx.packageName) != PackageManager.SIGNATURE_MATCH) throw SecurityException("المنادي مش موقّع بنفس المفتاح")
            when (method) {
                "ping" -> JSONObject().put("ok", true).put("api", 1).put("stage", 1)
                "extensions" -> {
                    val arr = JSONArray()
                    for (pi in ctx.packageManager.getInstalledPackages(PackageManager.GET_CONFIGURATIONS)) {
                        val feats = pi.reqFeatures ?: continue
                        if (feats.none { it.name == "tachiyomi.animeextension" }) continue
                        val label = try { pi.applicationInfo?.loadLabel(ctx.packageManager)?.toString() ?: pi.packageName } catch (_: Throwable) { pi.packageName }
                        arr.put(JSONObject().put("pkg", pi.packageName).put("name", label).put("version", pi.versionName ?: ""))
                    }
                    JSONObject().put("ok", true).put("extensions", arr)
                }
                "sources" -> JSONObject().put("ok", true).put("sources", JSONArray())
                else -> JSONObject().put("ok", false).put("error", "الدالة دي لسه ما اتنفّذتش في نسخة البلاجن دي")
            }
        } catch (e: Throwable) {
            JSONObject().put("ok", false).put("error", (e.message ?: e.javaClass.simpleName).take(120))
        }
        return Bundle().apply { putString("json", out.toString()) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
