package com.tttt.subtitler

import java.io.File
import java.security.MessageDigest

/**
 * (v826 · Joyboy Care م4) منطق المنظّف — java.io بس (من غير أندرويد) عشان يتختبر على JVM.
 * مبادئ: القائمة «مرشّحين للمراجعة» مش «آمن للحذف» · مفيش حذف تلقائي · الحجم المتوقع تقدير لحد ما الحذف ينجح فعلًا ·
 * الحذف بيتأكد إن الملف لسه موجود وما اتغيّرش من وقت الفحص · النسخة الأخيرة من أي مجموعة مكررة مش بتتحذف أبدًا.
 */
object CareClean {
    const val CAT_CACHE = 0
    const val CAT_LARGE = 1
    const val CAT_APK = 2
    const val CAT_DUP = 3
    const val CAT_EMPTY = 4
    const val CAT_JUNK = 5   // (v832) ملفات مؤقتة (.tmp/.crdownload/.part/…) وصور مصغّرة (.thumbnails)
    private val JUNK_EXT = setOf("tmp", "temp", "crdownload", "part", "partial", "bak", "chk", "dmp", "log", "old")
    fun isJunkName(n: String): Boolean { val i = n.lastIndexOf('.'); return i > 0 && n.substring(i + 1).lowercase() in JUNK_EXT }
    const val LARGE_BYTES = 100L * 1024 * 1024
    const val DUP_MIN_BYTES = 1L * 1024 * 1024
    const val CACHE_MIN_AGE_MS = 24L * 3600 * 1000
    private const val PARTIAL = 64 * 1024

    class Cand(val file: File, val cat: Int, val size: Long, val mtime: Long, val group: Int = -1)
    class Scan(val cands: List<Cand>, val visited: Int, val truncated: Boolean, val cancelled: Boolean, val unreadable: Int)
    class DelResult(val freed: Long, val deleted: Int, val failed: Int, val skipped: Int)

    /** كاش التطبيق نفسه: ملفات ما اتعدّلتش من يوم أو أكتر (عشان ما نلمسش تحميل/كاش شغّال دلوقتي). */
    fun scanCache(dirs: List<File>, now: Long, minAgeMs: Long = CACHE_MIN_AGE_MS): List<Cand> {
        val out = ArrayList<Cand>()
        val stack = ArrayList<File>()
        for (d in dirs) if (d.isDirectory) stack.add(d)
        while (stack.isNotEmpty()) {
            val d = stack.removeAt(stack.size - 1)
            val kids: Array<File> = (try { d.listFiles() } catch (_: Throwable) { null }) ?: continue
            for (k in kids) {
                try { if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue } catch (_: Throwable) { continue }
                if (k.isDirectory) stack.add(k)
                else { val m = k.lastModified(); if (now - m >= minAgeMs) out.add(Cand(k, CAT_CACHE, k.length(), m)) }
            }
        }
        return out
    }

    /** SHA-256: جزئي (أول 64KB) للتصفية السريعة أو كامل للحكم النهائي. null لو الملف مش مقروء. */
    fun hash(f: File, partial: Boolean): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            var left = if (partial) PARTIAL else Int.MAX_VALUE
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size, left)); if (n <= 0) break
                md.update(buf, 0, n); if (partial) left -= n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { null }

    /**
     * فحص التخزين: ملفات كبيرة · APK · مكررات (بالمحتوى مش بالاسم) · فولدرات فاضية.
     * بيتخطّى Android/ والمخفي ورموز الـsymlink. الفولدرات الأساسية (أول مستويين) مش بتتعرض كفاضية. كل ملف في قسم واحد بس.
     */
    fun scanStorage(
        roots: List<File>, cancel: () -> Boolean, onProgress: (Int) -> Unit = {},
        maxFiles: Int = 300_000, largeBytes: Long = LARGE_BYTES, dupMinBytes: Long = DUP_MIN_BYTES
    ): Scan {
        val bySize = HashMap<Long, ArrayList<File>>()
        val apks = ArrayList<File>(); val larges = ArrayList<File>(); val empties = ArrayList<File>(); val junks = ArrayList<File>()
        var visited = 0; var unreadable = 0; var truncated = false
        val stack = ArrayList<Pair<File, Int>>()
        for (r in roots) if (r.isDirectory) stack.add(r to 0)
        while (stack.isNotEmpty() && !truncated) {
            if (cancel()) return Scan(emptyList(), visited, false, true, unreadable)
            val (d, depth) = stack.removeAt(stack.size - 1)
            val kids = try { d.listFiles() } catch (_: Throwable) { null }
            if (kids == null) { unreadable++; continue }
            if (kids.isEmpty() && depth >= 2) empties.add(d)
            val inThumb = d.name == ".thumbnails" && depth >= 1
            for (k in kids) {
                if (visited >= maxFiles) { truncated = true; break }
                val n = k.name
                if (n.startsWith(".") && !inThumb && !(n == ".thumbnails" && depth >= 1 && k.isDirectory)) continue
                if (depth == 0 && n == "Android") continue
                try { if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue } catch (_: Throwable) { continue }
                if (k.isDirectory) { stack.add(k to depth + 1); continue }
                visited++
                if (visited % 500 == 0) onProgress(visited)
                val sz = k.length()
                if (inThumb || isJunkName(n)) { junks.add(k); continue }
                if (n.endsWith(".apk", ignoreCase = true)) apks.add(k)
                if (sz >= largeBytes) larges.add(k)
                if (sz >= dupMinBytes) bySize.getOrPut(sz) { ArrayList() }.add(k)
            }
        }
        // مكررات: نفس الحجم ← نفس أول 64KB ← نفس الهاش الكامل
        val dupGroups = ArrayList<List<File>>()
        for ((_, fs) in bySize) {
            if (fs.size < 2) continue
            val byPart = HashMap<String, ArrayList<File>>()
            for (f in fs) {
                if (cancel()) return Scan(emptyList(), visited, false, true, unreadable)
                val h = hash(f, true) ?: continue
                byPart.getOrPut(h) { ArrayList() }.add(f)
            }
            for (ps in byPart.values) {
                if (ps.size < 2) continue
                val byFull = HashMap<String, ArrayList<File>>()
                for (f in ps) {
                    if (cancel()) return Scan(emptyList(), visited, false, true, unreadable)
                    val h = hash(f, false) ?: continue
                    byFull.getOrPut(h) { ArrayList() }.add(f)
                }
                for (g in byFull.values) if (g.size >= 2) dupGroups.add(g.sortedBy { it.lastModified() })
            }
        }
        val out = ArrayList<Cand>(); val used = HashSet<String>()
        var gid = 0
        for (g in dupGroups) {
            for (f in g) { out.add(Cand(f, CAT_DUP, f.length(), f.lastModified(), gid)); used.add(f.path) }
            gid++
        }
        for (f in junks) if (used.add(f.path)) out.add(Cand(f, CAT_JUNK, f.length(), f.lastModified()))
        for (f in apks) if (used.add(f.path)) out.add(Cand(f, CAT_APK, f.length(), f.lastModified()))
        for (f in larges) if (used.add(f.path)) out.add(Cand(f, CAT_LARGE, f.length(), f.lastModified()))
        for (d in empties) out.add(Cand(d, CAT_EMPTY, 0L, d.lastModified()))
        return Scan(out, visited, truncated, false, unreadable)
    }

    /** عنصر الحذف مسموح؟ (ما اتغيّرش · الفولدر لسه فاضي · في نسخة تانية سليمة لو مكرر). بيرجّع سبب الرفض أو null. */
    private fun refuse(c: Cand, group: List<Cand>?): String? {
        val f = c.file
        if (!f.exists()) return "اتمسح قبل كده"
        if (c.cat == CAT_EMPTY) { if (!f.isDirectory || f.list()?.isEmpty() != true) return "الفولدر مابقاش فاضي" }
        else if (f.isDirectory || f.length() != c.size) return "الملف اتغيّر من وقت الفحص"
        if (c.cat == CAT_DUP) {
            val other = group?.any { it !== c && it.file.exists() } ?: false
            if (!other) return "دي آخر نسخة"
        }
        return null
    }

    /** الحذف الفعلي: بيرجّع المساحة اللي اتحرّرت فعلًا (ملفات اتأكدنا إنها اختفت) مش التقدير. */
    fun delete(sel: List<Cand>, all: List<Cand>): DelResult {
        val groups = all.filter { it.cat == CAT_DUP }.groupBy { it.group }
        var freed = 0L; var deleted = 0; var failed = 0; var skipped = 0
        for (c in sel) {
            if (refuse(c, groups[c.group]) != null) { skipped++; continue }
            val ok = try { c.file.delete() } catch (_: Throwable) { false }
            if (ok && !c.file.exists()) { freed += c.size; deleted++ } else failed++
        }
        return DelResult(freed, deleted, failed, skipped)
    }

    class Quick(val largeCount: Int, val largeBytes: Long, val apkCount: Int, val apkBytes: Long, val junkCount: Int, val junkBytes: Long, val visited: Int, val truncated: Boolean, val cancelled: Boolean)

    /** (v832) فحص سريع للتخزين (من غير هاش المكررات): عدد وحجم الكبير · APK · المؤقت/المصغّرات. للتقرير فقط — الحذف من المنظّف. */
    fun quick(roots: List<File>, cancel: () -> Boolean, maxFiles: Int = 250_000, largeBytes: Long = LARGE_BYTES): Quick {
        var lc = 0; var lb = 0L; var ac = 0; var ab = 0L; var jc = 0; var jb = 0L; var visited = 0; var truncated = false
        val stack = ArrayList<Pair<File, Int>>()
        for (r in roots) if (r.isDirectory) stack.add(r to 0)
        while (stack.isNotEmpty() && !truncated) {
            if (cancel()) return Quick(lc, lb, ac, ab, jc, jb, visited, false, true)
            val (d, depth) = stack.removeAt(stack.size - 1)
            val kids = (try { d.listFiles() } catch (_: Throwable) { null }) ?: continue
            val inThumb = d.name == ".thumbnails" && depth >= 1
            for (k in kids) {
                if (visited >= maxFiles) { truncated = true; break }
                val n = k.name
                if (n.startsWith(".") && !inThumb && !(n == ".thumbnails" && depth >= 1 && k.isDirectory)) continue
                if (depth == 0 && n == "Android") continue
                try { if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue } catch (_: Throwable) { continue }
                if (k.isDirectory) { stack.add(k to depth + 1); continue }
                visited++
                val sz = k.length()
                if (inThumb || isJunkName(n)) { jc++; jb += sz; continue }
                if (n.endsWith(".apk", ignoreCase = true)) { ac++; ab += sz }
                if (sz >= largeBytes) { lc++; lb += sz }
            }
        }
        return Quick(lc, lb, ac, ab, jc, jb, visited, truncated, false)
    }
}
