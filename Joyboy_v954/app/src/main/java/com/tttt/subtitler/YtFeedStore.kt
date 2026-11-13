package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * (v929) التلفزيون: قوايم يوتيوب اللي الموبايل بعتها من MeeTube (TvLink /ytfeed) — أحدث الاشتراكات + الصفحة الرئيسية.
 * بتتحفظ في ملف عشان تفضل ظاهرة بعد إعادة تشغيل التلفزيون. signed: null = لسه ماوصلش حاجة · false = الموبايل مش مسجّل دخول · true = تمام.
 */
object YtFeedStore {
    @Volatile var signed: Boolean? = null; private set
    @Volatile var subs: List<NpItem> = emptyList(); private set
    @Volatile var home: List<NpItem> = emptyList(); private set
    @Volatile var at = 0L; private set
    @Volatile private var loginUntil = 0L
    @Volatile private var refreshUntil = 0L
    @Volatile var onChange: (() -> Unit)? = null
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "yt_feed.json")

    private fun items(a: JSONArray?): List<NpItem> {
        val out = ArrayList<NpItem>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.length != 11) continue
            out.add(NpItem(id, o.optString("t"), o.optString("ch"), "", o.optString("th"), o.optLong("d"), -1L, o.optString("age"), 0, MtYt.watchUrl(id)))
        }
        return out
    }

    private fun apply(j: JSONObject) {
        signed = if (j.has("signed")) j.optBoolean("signed") else null
        subs = items(j.optJSONArray("subs")); home = items(j.optJSONArray("home"))
        at = j.optLong("at", System.currentTimeMillis())
    }

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try { val f = file(ctx); if (f.isFile) apply(JSONObject(f.readText())) } catch (e: Throwable) { LogStore.err("YtFeed:load", e) }
    }

    /** وصل من الموبايل */
    fun put(ctx: Context, j: JSONObject) {
        apply(j); loaded = true
        try { file(ctx).writeText(j.toString()) } catch (e: Throwable) { LogStore.err("YtFeed:save", e) }
        if (signed == true) loginUntil = 0L
        try { onChange?.invoke() } catch (_: Throwable) {}
    }

    /** دوسة «سجّل الدخول» على التلفزيون: الموبايل بيشوفها في ping التالي (خلال ~4 ثواني) */
    fun requestLogin() { loginUntil = System.currentTimeMillis() + 20_000L }
    /** بيتقرا مرة واحدة (الموبايل بيسحبه في ping) */
    fun loginRequested(): Boolean { val r = System.currentTimeMillis() < loginUntil; loginUntil = 0L; return r }

    /** دوسة 🔄 على التلفزيون: الموبايل يجيب أحدث فيديوهات MeeTube ويبعتها دلوقتي */
    fun requestRefresh() { refreshUntil = System.currentTimeMillis() + 20_000L }
    fun refreshRequested(): Boolean { val r = System.currentTimeMillis() < refreshUntil; refreshUntil = 0L; return r }
}
