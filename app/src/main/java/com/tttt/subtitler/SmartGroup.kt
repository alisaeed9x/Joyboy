package com.tttt.subtitler

/**
 * (v299) التوزيع الذكي للمكتبة — منطق نقي من غير Android (بيتختبر على JVM).
 *  • فيديو: حلقات نفس المسلسل من فولدرات مختلفة بتتجمّع في فولدر واحد (📺)، ولو فيه أكتر من موسم/جزء بتتقسم جواه لفولدرات مواسم (🎞).
 *  • صوت: أغاني نفس المغني (من التاج أو من «مغني - أغنية» في اسم الملف) بتتجمّع في فولدر المغني (🎤).
 *  • أي ملف ملوش مجموعة (أقل من 2) بيرجع في Result.rest ويفضل في فولدره الأصلي.
 * التقسيم بقواعد على الأسماء (S01E05 · 1x05 · الحلقة 5 · الموسم/الجزء الثاني · رقم في آخر الاسم) — من غير أي طلب لجيميناي.
 */
object SmartGroup {
    class Result(val nodes: List<FolderItem>, val rest: List<VideoItem>)

    /** كلمة من الاسم: orig للعرض، n بعد التطبيع (VideoLib.norm) للمقارنة */
    private class Tok(val orig: String, val n: String)
    private class Parsed(val series: List<Tok>, val season: Int, val ep: Int)

    private val SEP = Regex("[\\s._\\-\\[\\](){}+,،]+")
    private val RE_SE = Regex("^s(\\d{1,2})e(\\d{1,3})$")
    private val RE_X = Regex("^(\\d{1,2})x(\\d{1,3})$")
    private val RE_E = Regex("^(?:e|ep|eps)(\\d{1,3})$")
    private val RE_S = Regex("^s(\\d{1,2})$")
    private val RE_NUM = Regex("^\\d{1,3}$")

    private val NOISE = setOf(
        "2160p", "1080p", "720p", "480p", "360p", "4k", "uhd", "fhd", "hd", "hdtv", "hdrip", "webrip", "web", "dl", "webdl", "bluray", "brrip", "bdrip", "dvdrip",
        "x264", "x265", "h264", "h265", "hevc", "aac", "ac3", "dts", "10bit", "mp4", "mkv", "avi", "www", "com", "net", "org",
        "مترجم", "مترجمه", "مدبلج", "مدبلجه", "حصريا", "كامل", "كامله", "اون", "لاين", "اونلاين"
    )
    private val LEAD = setOf("مسلسل", "فيلم", "برنامج", "انمي", "كرتون", "مشاهده", "تحميل")
    private val EPW = setOf("الحلقه", "حلقه", "episode", "ep", "eps")
    private val SEW = setOf("الموسم", "موسم", "season", "الجزء", "جزء")
    private val ORD = mapOf(
        "الاول" to 1, "الاولي" to 1, "اول" to 1, "الاولى" to 1,
        "الثاني" to 2, "الثانيه" to 2, "ثاني" to 2, "التاني" to 2, "التانيه" to 2, "تاني" to 2,
        "الثالث" to 3, "الثالثه" to 3, "ثالث" to 3, "التالت" to 3, "التالته" to 3, "تالت" to 3,
        "الرابع" to 4, "الرابعه" to 4, "رابع" to 4, "الخامس" to 5, "الخامسه" to 5, "خامس" to 5,
        "السادس" to 6, "السادسه" to 6, "سادس" to 6, "السابع" to 7, "السابعه" to 7, "سابع" to 7,
        "الثامن" to 8, "الثامنه" to 8, "ثامن" to 8, "التاسع" to 9, "التاسعه" to 9, "تاسع" to 9,
        "العاشر" to 10, "العاشره" to 10, "عاشر" to 10
    )

    private fun numOf(t: String): Int? = if (RE_NUM.matches(t)) t.toIntOrNull() else ORD[t]

    private fun toks(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        for (o in SEP.split(s)) {
            if (o.isEmpty()) continue
            val n = VideoLib.norm(o).trim()
            if (n.isNotEmpty()) out.add(Tok(o, n))
        }
        return out
    }

    /** اسم المسلسل + الموسم + رقم الحلقة من اسم الملف (ولو الاسم مفيهوش اسم مسلسل بيرجع لاسم الفولدر) */
    private fun parse(name: String, folderName: String): Parsed? {
        var all = toks(name).filter { it.n !in NOISE }
        while (all.isNotEmpty() && all[0].n in LEAD) all = all.drop(1)
        var season = 0; var ep = 0; var cut = -1
        var j = 0
        while (j < all.size) {
            val t = all[j].n
            val se = RE_SE.matchEntire(t); val x = RE_X.matchEntire(t); val e = RE_E.matchEntire(t); val s = RE_S.matchEntire(t)
            if (se != null) {
                if (season == 0) season = se.groupValues[1].toInt()
                if (ep == 0) ep = se.groupValues[2].toInt()
                if (cut < 0) cut = j
            } else if (x != null) {
                if (season == 0) season = x.groupValues[1].toInt()
                if (ep == 0) ep = x.groupValues[2].toInt()
                if (cut < 0) cut = j
            } else if (e != null) {
                if (ep == 0) ep = e.groupValues[1].toInt()
                if (cut < 0) cut = j
            } else if (s != null) {
                if (season == 0) season = s.groupValues[1].toInt()
                if (cut < 0) cut = j
            } else if (t in EPW) {
                val k = if (j + 1 < all.size) numOf(all[j + 1].n) else null
                if (k != null) { if (ep == 0) ep = k; if (cut < 0) cut = j; j++ }
            } else if (t in SEW) {
                val k = if (j + 1 < all.size) numOf(all[j + 1].n) else null
                if (k != null) { if (season == 0) season = k; if (cut < 0) cut = j; j++ }
            }
            j++
        }
        var series: List<Tok>
        if (cut >= 0) {
            series = all.subList(0, cut)
            // «الاختيار 2 الحلقة 5»: الرقم اللي قبل كلمة الحلقة = رقم الموسم/الجزء
            if (season == 0 && series.size >= 2 && RE_NUM.matches(series.last().n)) {
                season = series.last().n.toInt(); series = series.dropLast(1)
            }
        } else if (all.size >= 2 && RE_NUM.matches(all.last().n)) {
            ep = all.last().n.toInt(); series = all.dropLast(1)
        } else if (all.size == 1 && RE_NUM.matches(all[0].n)) {
            ep = all[0].n.toInt(); series = emptyList()
        } else series = all
        if (series.isNotEmpty() && series.all { RE_NUM.matches(it.n) }) series = emptyList()
        if (series.isEmpty()) {
            if (folderName.isEmpty()) return null
            val f = parse(folderName, "") ?: return null
            return Parsed(f.series, if (season != 0) season else f.season, ep)
        }
        if (series.sumOf { it.n.length } < 3) return null
        return Parsed(series, season, ep)
    }

    private fun seasonOf(p: Parsed) = if (p.season == 0) 1 else p.season

    private fun seriesNode(k: String, l: List<Pair<VideoItem, Parsed>>): FolderItem {
        val sorted = l.sortedWith { a, b -> VideoLib.natural(a.first.name, b.first.name) }
        val name = sorted[0].second.series.joinToString(" ") { it.orig }.trim()
        val key = "sm:$k"
        val vids = sorted.map { it.first }
        val nF = vids.map { it.folderKey }.distinct().size
        val path = if (nF > 1) "من $nF فولدرات" else vids[0].folderPath
        val seasons = sorted.map { seasonOf(it.second) }.distinct().sorted()
        if (seasons.size < 2) return FolderItem(key, name, path, vids, icon = "📺", virtual = true)
        val kids = seasons.map { s ->
            val sv = sorted.filter { seasonOf(it.second) == s }.map { it.first }
            FolderItem("$key#$s", "الموسم $s", path, sv, icon = "🎞", parentKey = key, virtual = true)
        }
        return FolderItem(key, name, path, vids, kids = kids, icon = "📺", virtual = true)
    }

    /** فيديوهات: مسلسلات (≥2 حلقة) في nodes، والباقي في rest */
    fun videos(items: List<VideoItem>): Result {
        val byKey = LinkedHashMap<String, MutableList<Pair<VideoItem, Parsed>>>()
        val rest = ArrayList<VideoItem>()
        for (v in items) {
            val p = try { parse(v.title, v.folderName) } catch (_: Exception) { null }
            if (p == null) { rest.add(v); continue }
            byKey.getOrPut(p.series.joinToString("") { it.n }) { ArrayList() }.add(v to p)
        }
        val nodes = ArrayList<FolderItem>()
        for ((k, l) in byKey) {
            if (l.size < 2) { rest.add(l[0].first); continue }
            nodes.add(seriesNode(k, l))
        }
        return Result(nodes, rest)
    }

    // ===== الأغاني =====
    private val RE_SPLIT = Regex("\\s*(?:[,،&;/]|\\s(?:feat|ft|featuring)\\.?\\s)\\s*", RegexOption.IGNORE_CASE)
    private val RE_TRACKNO = Regex("^\\s*\\d{1,3}\\s*[.\\-)_]*\\s*")
    private val NO_ARTIST = setOf("various artists", "various", "unknown artist", "unknown", "va", "غير معروف", "فنان غير معروف", "مجهول")

    /** المغني الأساسي: من التاج، وإلا من «مغني - أغنية» في اسم الملف. فاضي = مش معروف */
    fun artistOf(v: VideoItem, junk: (String) -> Boolean): String {
        var a = v.artist.trim()
        if (a.isEmpty() || junk(a) || VideoLib.norm(a) in NO_ARTIST) {
            a = ""
            val base = v.name.substringBeforeLast('.', v.name).replace('_', ' ')
            val i = base.indexOf(" - ")
            if (i > 0) {
                val left = base.substring(0, i).replace(RE_TRACKNO, "").trim()
                if (left.length in 2..40 && left.any { it.isLetter() } && !junk(left) && VideoLib.norm(left) !in NO_ARTIST) a = left
            }
        }
        if (a.isEmpty()) return ""
        return (RE_SPLIT.split(a).firstOrNull { it.isNotBlank() } ?: a).trim()
    }

    /** أغاني: مغني عنده ≥2 أغنية في nodes (🎤)، والباقي في rest */
    fun songs(items: List<VideoItem>, junk: (String) -> Boolean): Result {
        val by = LinkedHashMap<String, MutableList<VideoItem>>()
        val names = HashMap<String, String>()
        val rest = ArrayList<VideoItem>()
        for (v in items) {
            val a = try { artistOf(v, junk) } catch (_: Exception) { "" }
            val k = VideoLib.norm(a).replace(" ", "")
            if (k.length < 2) { rest.add(v); continue }
            by.getOrPut(k) { ArrayList() }.add(v)
            if (!names.containsKey(k)) names[k] = a
        }
        val nodes = ArrayList<FolderItem>()
        for ((k, l) in by) {
            if (l.size < 2) { rest.addAll(l); continue }
            val sorted = l.sortedWith { a, b -> VideoLib.natural(a.title, b.title) }
            val nF = sorted.map { it.folderKey }.distinct().size
            val path = "${sorted.size} أغنية" + (if (nF > 1) " · من $nF فولدرات" else "")
            nodes.add(FolderItem("sa:$k", names[k] ?: k, path, sorted, icon = "🎤", virtual = true))
        }
        return Result(nodes, rest)
    }
}
