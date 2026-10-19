package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject

/**
 * (v215) ترميز النسخة الاحتياطية (نقي — متختبر): كل قيم الإعدادات بنوعها (نص / رقم / true-false / مجموعة) في JSON واحد.
 * مفيش قفل المجلد (lock_*): هاش النمط مالوش لازمة على جهاز تاني، وبيتعمل من جديد.
 */
object BackupCodec {
    const val FORMAT = "subtitler-backup"
    const val VERSION = 1
    /** مفاتيح مابتتحفظش: قفل المجلد + علامة استرجاع المفاتيح التلقائي */
    fun skip(k: String): Boolean = k == "kv_init" || k.startsWith("lock_")
    /** مفاتيح المفاتيح: النسخة الفاضية ما بتمسحش مفاتيح موجودة */
    val SECRET_FIELDS = setOf("keys", "backup", "extra", "viskeys", "mistral_keys", "cerebras_keys")

    fun encode(all: Map<String, Any?>, ts: Long): String {
        val prefs = JSONObject()
        for ((k, v) in all.toSortedMap()) {
            if (skip(k) || v == null) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> { o.put("t", "b"); o.put("v", v) }
                is Int -> { o.put("t", "i"); o.put("v", v) }
                is Long -> { o.put("t", "l"); o.put("v", v) }
                is Float -> { o.put("t", "f"); o.put("v", v.toDouble()) }
                is String -> { o.put("t", "s"); o.put("v", v) }
                is Set<*> -> { o.put("t", "set"); o.put("v", JSONArray(v.map { it.toString() })) }
                else -> continue
            }
            prefs.put(k, o)
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("ts", ts).put("prefs", prefs).toString(1)
    }

    class Item(val key: String, val type: String, val value: Any)

    /** بيرمي IllegalArgumentException لو الملف مش نسخة صالحة */
    fun decode(json: String): List<Item> {
        val root = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("الملف مش JSON") }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("مش ملف نسخة احتياطية من Subtitler")
        val prefs = root.optJSONObject("prefs") ?: throw IllegalArgumentException("النسخة فاضية")
        val out = ArrayList<Item>()
        val ks = prefs.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            if (skip(k)) continue
            val o = prefs.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "b" -> out.add(Item(k, "b", o.optBoolean("v")))
                "i" -> out.add(Item(k, "i", o.optInt("v")))
                "l" -> out.add(Item(k, "l", o.optLong("v")))
                "f" -> out.add(Item(k, "f", o.optDouble("v").toFloat()))
                "s" -> out.add(Item(k, "s", o.optString("v")))
                "set" -> { val a = o.optJSONArray("v") ?: continue; out.add(Item(k, "set", (0 until a.length()).map { i -> a.optString(i) }.toSet())) }
            }
        }
        return out
    }

    fun timestamp(json: String): Long = try { JSONObject(json).optLong("ts", 0L) } catch (_: Exception) { 0L }
}
