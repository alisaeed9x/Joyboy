package com.tttt.subtitler

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** سطر في سجل النقل. dir: out (مُرسَل) · in (مُستلَم) — state: ok · fail · cancel. path: الملف المستلَم، أو مصدر الملف المُرسَل (مسار أو Uri) */
class FtHist(
    val ts: Long, val dir: String, val peerName: String, val peerId: String,
    val name: String, val size: Long, val state: String, val path: String, val note: String
)

/** جهاز موثوق: المستخدم حدّده بنفسه، والاستقبال التلقائي منه مشروط ببصمة هويته (لو اتغيّرت بيرجع يسأل) */
class FtTrusted(val id: String, val fp: String, val name: String)

/**
 * (v653) سجل النقل + الأجهزة الموثوقة. السجل في filesDir/ft_history.json (آخر 600 عملية منتهية)،
 * والموثوقين في الملف الخاص ft_private. العمليات الجارية بتتعرض من الجلسات الحية (FtHub) مش من هنا.
 */
object FtStore {
    private const val MAX = 600
    private val list = CopyOnWriteArrayList<FtHist>()
    private var file: File? = null
    private var sp: SharedPreferences? = null

    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        val app = ctx.applicationContext
        sp = app.getSharedPreferences("ft_private", Context.MODE_PRIVATE)
        val f = File(app.filesDir, "ft_history.json")
        file = f
        try {
            if (f.isFile) {
                val a = JSONArray(String(f.readBytes(), Charsets.UTF_8))
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    list.add(FtHist(o.optLong("t"), o.optString("d"), o.optString("pn"), o.optString("pi"), o.optString("n"), o.optLong("s"), o.optString("st"), o.optString("p"), o.optString("nt")))
                }
            }
        } catch (e: Throwable) { LogStore.err("FtStore:load", e) }
    }

    fun history(): List<FtHist> = list.toList()

    @Synchronized fun add(h: FtHist) {
        list.add(0, h)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save()
    }

    @Synchronized fun remove(h: FtHist) { list.remove(h); save() }

    @Synchronized fun clear() { list.clear(); save() }

    private fun save() {
        val f = file ?: return
        try {
            val a = JSONArray()
            for (h in list) a.put(JSONObject().put("t", h.ts).put("d", h.dir).put("pn", h.peerName).put("pi", h.peerId).put("n", h.name).put("s", h.size).put("st", h.state).put("p", h.path).put("nt", h.note))
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(a.toString().toByteArray(Charsets.UTF_8))
            if (!tmp.renameTo(f)) { f.writeBytes(tmp.readBytes()); tmp.delete() }
        } catch (e: Throwable) { LogStore.err("FtStore:save", e) }
    }

    // ───────── الأجهزة الموثوقة ─────────
    fun trusted(): List<FtTrusted> {
        val out = ArrayList<FtTrusted>()
        try {
            val a = JSONArray(sp?.getString("trusted", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotEmpty()) out.add(FtTrusted(id, o.optString("fp"), o.optString("n")))
            }
        } catch (_: Throwable) {}
        return out
    }

    fun isTrusted(id: String, fp: String): Boolean = fp.isNotEmpty() && trusted().any { it.id == id && it.fp == fp }

    @Synchronized fun trust(id: String, fp: String, name: String) {
        if (id.isEmpty() || fp.isEmpty()) return
        val all = trusted().filter { it.id != id }.toMutableList()
        all.add(FtTrusted(id, fp, name))
        persist(all)
    }

    @Synchronized fun untrust(id: String) { persist(trusted().filter { it.id != id }) }

    private fun persist(all: List<FtTrusted>) {
        val a = JSONArray()
        for (t in all) a.put(JSONObject().put("id", t.id).put("fp", t.fp).put("n", t.name))
        sp?.edit()?.putString("trusted", a.toString())?.apply()
    }
}
