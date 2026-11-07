package com.tttt.subtitler

import org.json.JSONObject
import java.net.URLEncoder

/**
 * (v814) «ابعت للموبايل» من اللابتوب (Joyboy Desk): اللابتوب بيحط الملفات في رد /ping (push) والموبايل بيسحبها لوحده
 * من /file/any (نفس مسار تحميل كتالوج التلفزيون) — من غير أي ضغطة على الموبايل. التكرار بيتشال بالـid.
 * بيشتغل بس مع الجهاز المقترن اللي الموبايل متصل بيه حالياً (RemoteClient.current).
 */
object DeskPush {
    private val seen = LinkedHashSet<String>()

    fun handle(j: JSONObject) {
        val a = j.optJSONArray("push") ?: return
        val tv = RemoteClient.current ?: return
        val ctx = try { RemoteClient.app() } catch (_: Throwable) { return }
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id"); val k = o.optString("k")
            if (id.isEmpty() || k.isEmpty()) continue
            val fresh = synchronized(seen) { val f = seen.add(id); if (seen.size > 200) { val it = seen.iterator(); it.next(); it.remove() }; f }
            if (!fresh) continue
            val name = Xfer.safeName(o.optString("n", "file")); val size = o.optLong("s"); val cat = o.optString("c").ifEmpty { XCat.catOf(name) }
            val url = "http://" + tv.ip + ":" + tv.port + "/file/any?k=" + URLEncoder.encode(k, "UTF-8")
            val hdr = mapOf("X-Token" to tv.token, "X-Ver" to LinkVer.code(ctx).toString())
            try {
                TaskCenter.add(ctx, "dm", "📥 $name") { t -> XNet.download(ctx, t, url, hdr, name, size, cat) }
            } catch (e: Throwable) { LogStore.err("DeskPush", e) }
        }
    }
}
