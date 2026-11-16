package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * (v472) آخر نسخة سليمة من قايمة الاشتراكات على القرص: صفحة الاشتراكات بتفتح منها فورًا (من غير شاشة فاضية ولا «مفيش نتايج»)
 * والتحديث بيشتغل في الخلفية؛ لو فشل أو رجع فاضي النسخة القديمة بتفضل ظاهرة. بتتحدّث بس لما التحديث يجيب فيديوهات فعلًا.
 */
object YtSnap {
    private const val MAX = 150
    private fun file(ctx: Context, tab: Int) = File(ctx.applicationContext.filesDir, "yt_snap_$tab.json")

    @Synchronized fun save(ctx: Context, tab: Int, items: List<NpItem>) {
        try {
            val vids = items.filter { it.kind == 0 && it.id.isNotBlank() }.take(MAX)
            if (vids.isEmpty()) return
            val a = JSONArray()
            for (x in vids) a.put(JSONObject().put("i", x.id).put("t", x.title).put("c", x.channel).put("cu", x.channelUrl).put("th", x.thumb)
                .put("d", x.dur).put("n", x.count).put("a", x.age).put("k", x.kind).put("u", x.url).put("ts", x.ts).put("av", x.avatar))
            val f = file(ctx, tab); val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("at", System.currentTimeMillis()).put("items", a).toString()); tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    @Synchronized fun load(ctx: Context, tab: Int): List<NpItem>? {
        return try {
            val f = file(ctx, tab); if (!f.isFile) return null
            val a = JSONObject(f.readText()).getJSONArray("items"); val l = ArrayList<NpItem>()
            for (n in 0 until a.length()) {
                val x = a.getJSONObject(n); val ts = x.optLong("ts", 0L)
                l.add(NpItem(x.optString("i"), x.optString("t"), x.optString("c"), x.optString("cu"), x.optString("th"), x.optLong("d", -1L), x.optLong("n", -1L),
                    if (ts > 0L) YtRss.ageOf(ts) else x.optString("a"), x.optInt("k", 0), x.optString("u"), ts, x.optString("av")))
            }
            if (l.isEmpty()) null else l
        } catch (_: Throwable) { null }
    }
}
