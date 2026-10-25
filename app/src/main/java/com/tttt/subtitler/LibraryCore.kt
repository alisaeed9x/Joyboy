package com.tttt.subtitler

import org.json.JSONObject

/** (v543 م3) منطق نقي لمكتبة التلفزيون على الموبايل: تحليل ردود /lib/* + ترتيب + تنسيق. من غير Android عشان يتختبر على JVM. */
data class RFolder(val key: String, val name: String, val path: String, val count: Int, val size: Long, val newest: Long)

data class RVideo(
    val id: Long, val name: String, val title: String, val size: Long, val durMs: Long,
    val added: Long, val modified: Long, val w: Int, val h: Int, val vid: String, val ext: String
) {
    /** نفس منطق VideoItem.dateMs: modified لو موجود وإلا added (ثواني) */
    val dateMs: Long get() = (if (modified > 0) modified else added) * 1000L
}

object LibraryCore {
    const val SCHEME = "tv://"

    /** رابط فيديو في مكتبة التلفزيون (التلفزيون يحلّه لملفه) */
    fun tvUri(id: Long): String = SCHEME + id

    /** يرجّع الـid لو الرابط من النوع ده، وإلا null */
    fun idOf(uri: String): Long? = if (uri.startsWith(SCHEME, true)) uri.substring(SCHEME.length).trim().toLongOrNull() else null

    /** @return null لو الرد مش JSON سليم أو ok=false */
    fun parseFolders(body: String): List<RFolder>? {
        val j = try { JSONObject(body) } catch (_: Exception) { return null }
        if (!j.optBoolean("ok", false)) return null
        val a = j.optJSONArray("folders") ?: return emptyList()
        val out = ArrayList<RFolder>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(RFolder(o.optString("key"), o.optString("name"), o.optString("path"), o.optInt("count"), o.optLong("size"), o.optLong("newest")))
        }
        return out
    }

    fun parseVideos(body: String): List<RVideo>? {
        val j = try { JSONObject(body) } catch (_: Exception) { return null }
        if (!j.optBoolean("ok", false)) return null
        val a = j.optJSONArray("videos") ?: return emptyList()
        val out = ArrayList<RVideo>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(RVideo(o.optLong("id"), o.optString("name"), o.optString("title"), o.optLong("size"), o.optLong("durMs"),
                o.optLong("added"), o.optLong("modified"), o.optInt("w"), o.optInt("h"), o.optString("vid"), o.optString("ext")))
        }
        return out
    }

    /** سبب الفشل من رد خطأ التلفزيون ({"ok":false,"err":"perm"}) */
    fun errOf(body: String): String = try { JSONObject(body).optString("err") } catch (_: Exception) { "" }

    fun foldersNewestFirst(l: List<RFolder>): List<RFolder> = l.sortedWith(compareByDescending<RFolder> { it.newest }.thenBy { it.name.lowercase() })

    fun videosNewestFirst(l: List<RVideo>): List<RVideo> = l.sortedWith(compareByDescending<RVideo> { it.dateMs }.thenBy { it.name.lowercase() })

    fun fmtDur(ms: Long): String {
        if (ms <= 0L) return ""
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, sec) else "%d:%02d".format(java.util.Locale.US, m, sec)
    }

    fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> "%.1f GB".format(java.util.Locale.US, b / (1024.0 * 1024 * 1024))
        b >= 1024L * 1024 -> "%d MB".format(java.util.Locale.US, b / (1024 * 1024))
        b > 0 -> "%d KB".format(java.util.Locale.US, b / 1024)
        else -> ""
    }

    /** اسم ملف كاش الصورة المصغّرة: لكل تلفزيون ولكل فيديو (وحجمه عشان لو الملف اتبدّل) */
    fun thumbFile(tvId: String, v: RVideo): String = (tvId + "_" + v.id + "_" + v.size).replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpg"
}
