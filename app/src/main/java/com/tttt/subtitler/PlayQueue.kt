package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * (v694) طابور التشغيل: فيديوهات (ملف / لينك / يوتيوب) بتتشغّل ورا بعض — لما الفيديو يخلّص التالي يشتغل لوحده.
 * كل جهاز عنده طابور بتاعه (الموبايل والتلفزيون): الموبايل بيعدّل طابور التلفزيون عن بعد (/queue)،
 * والتلفزيون بيكمّل لوحده حتى لو الموبايل قفل. التخزين في SharedPreferences منفصلة (مش Cfg) عشان ما يتزامنش بين الجهازين.
 *
 * العنصر = ref واحد: لينك http(s) (يوتيوب كمان) · ملف محلي (content:/file:/مسار) · tv://id (مكتبة التلفزيون).
 */
object PlayQueue {
    class Item(val id: String, val ref: String, val title: String)

    private val lock = Any()
    private var items = ArrayList<Item>()
    private var loaded = false
    private var appCtx: Context? = null
    private const val PREF = "play_queue"
    private const val KEY = "items"

    fun toJson(l: List<Item>): JSONArray {
        val a = JSONArray()
        for (q in l) a.put(JSONObject().put("id", q.id).put("ref", q.ref).put("t", q.title))
        return a
    }

    fun fromJson(a: JSONArray?): List<Item> {
        val out = ArrayList<Item>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val ref = o.optString("ref").trim()
            if (ref.isEmpty()) continue
            out.add(Item(o.optString("id").ifBlank { newId() }, ref, o.optString("t")))
        }
        return out
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    private fun ensure(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            appCtx = ctx.applicationContext
            try {
                val s = appCtx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
                items = ArrayList(fromJson(JSONArray(s)))
            } catch (e: Throwable) { LogStore.err("PlayQueue:load", e); items = ArrayList() }
            loaded = true
        }
    }

    private fun save() {
        try { appCtx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putString(KEY, toJson(items).toString())?.apply() } catch (e: Throwable) { LogStore.err("PlayQueue:save", e) }
    }

    fun list(ctx: Context): List<Item> { ensure(ctx); return synchronized(lock) { ArrayList(items) } }
    fun size(ctx: Context): Int { ensure(ctx); return synchronized(lock) { items.size } }
    fun hasNext(ctx: Context): Boolean = size(ctx) > 0

    fun add(ctx: Context, ref: String, title: String): Item? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        ensure(ctx)
        val q = Item(newId(), r, title.trim())
        synchronized(lock) { items.add(q); if (items.size > 300) items.removeAt(0); save() }
        return q
    }

    fun setAll(ctx: Context, l: List<Item>) { ensure(ctx); synchronized(lock) { items = ArrayList(l); save() } }
    fun clear(ctx: Context) { ensure(ctx); synchronized(lock) { items.clear(); save() } }
    fun remove(ctx: Context, id: String) { ensure(ctx); synchronized(lock) { items.removeAll { it.id == id }; save() } }

    /** يطلّع أول واحد (بيتشال من الطابور) */
    fun take(ctx: Context): Item? { ensure(ctx); return synchronized(lock) { if (items.isEmpty()) null else items.removeAt(0).also { save() } } }

    /** يطلّع واحد معيّن (للتشغيل دلوقتي) */
    fun takeId(ctx: Context, id: String): Item? {
        ensure(ctx)
        return synchronized(lock) { val i = items.indexOfFirst { it.id == id }; if (i < 0) null else items.removeAt(i).also { save() } }
    }

    /** يشغّل التالي على الجهاز ده. false = الطابور فاضي أو التشغيل فشل */
    fun playNext(ctx: Context): Boolean {
        val q = take(ctx) ?: return false
        return playHere(ctx, q)
    }

    /** يشغّل عنصر على الجهاز ده (تلفزيون: عن طريق نفس مسار /open — موبايل: المشغّل المحلي). startMs = كمّل من الثانية دي */
    fun playHere(ctx: Context, q: Item, startMs: Long = 0L): Boolean {
        val ref = q.ref
        return try {
            val isTv = DeviceUi.isTv(ctx)
            val local = ref.startsWith("content:", true) || ref.startsWith("file:", true) || ref.startsWith("/")
            val base = if (ctx is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
            if (local) {
                val u = if (ref.startsWith("/")) Uri.fromFile(java.io.File(ref)) else Uri.parse(ref)
                ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(base or Intent.FLAG_GRANT_READ_URI_PERMISSION).setData(u)
                    .putExtra("landscape", true).putExtra("fresh", false).putExtra("nosub", false).putExtra("autotr", false).putExtra("ask", false)
                    .apply { if (startMs > 0L) putExtra("startms", startMs) })
                true
            } else if (isTv) {
                TvLink.openJson(JSONObject().put("url", ref).put("title", q.title).put("how", Links.VIDEO).put("startms", startMs))
            } else if (ref.startsWith(LibraryCore.SCHEME, true)) {
                false   // فيديو داخل مكتبة التلفزيون: بيتشغّل على التلفزيون بس
            } else {
                PlayTarget.allowPhoneOnce()
                ctx.startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(base or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(Links.EX_URL, ref).putExtra(Links.EX_HOW, Links.VIDEO).putExtra(Links.EX_TITLE, q.title)
                    .apply { if (startMs > 0L) putExtra("link_startms", startMs) })
                true
            }
        } catch (e: Throwable) { LogStore.err("PlayQueue:play", e); false }
    }
}
