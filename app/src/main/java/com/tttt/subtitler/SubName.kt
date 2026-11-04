package com.tttt.subtitler

/**
 * (v696) تحليل أسماء الفيديوهات والفولدرات وملفات الترجمة: عنوان + سنة + موسم + حلقة، وكلمات البحث، واختيار الحلقة الصح
 * من ZIP فيه كل الحلقات. منطق صافي (من غير Android) عشان يتفحص بـJVM.
 */
object SubName {
    class Info(
        /** العنوان نضيف (من غير جودة / سنة / رقم الحلقة) */
        val title: String,
        /** نفس العنوان بس لو الرقم اللي في آخره ممكن يكون جزء منه («Rocky 2») */
        val titleAlt: String,
        val year: Int,
        /** -1 = مش معروف */
        val season: Int,
        /** -1 = مش مسلسل / مش معروفة */
        val episode: Int,
        /** رقم الحلقة تخمين (رقم لوحده في الاسم) */
        val weakEp: Boolean,
        /** الاسم الأصلي من غير امتداد */
        val raw: String
    ) {
        val isSeries: Boolean get() = episode >= 0
        fun label(): String {
            val sb = StringBuilder(title.ifBlank { raw })
            if (episode >= 0) {
                if (season >= 0) sb.append(String.format(java.util.Locale.US, " S%02dE%02d", season, episode)) else sb.append(" E").append(episode)
            } else if (year > 0) sb.append(" (").append(year).append(")")
            return sb.toString()
        }
    }

    private val AR_DIGITS = "٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹"
    private const val LAT_DIGITS = "01234567890123456789"
    fun normDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) { val i = AR_DIGITS.indexOf(ch); sb.append(if (i >= 0) LAT_DIGITS[i] else ch) }
        return sb.toString()
    }

    private val EXTS = Regex("(?i)\\.(mkv|mp4|avi|mov|m4v|webm|flv|ts|srt|ass|ssa|vtt|sub|zip|gz|rar|7z)$")
    fun stripExt(n: String): String {
        var s = n.trim()
        while (true) { val m = EXTS.find(s) ?: return s; s = s.substring(0, m.range.first) }
    }

    private val BRACKETS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}")
    private val WS = Regex("\\s+")
    private val YEAR = Regex("(?<!\\d)((?:19|20)\\d\\d)(?!\\d)")
    private val SXE = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})\\s*-?\\s*e(\\d{1,4})(?![0-9])")
    private val NXN = Regex("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![a-z0-9])")
    private val SEASON_EP = Regex("(?i)(?:season|الموسم|موسم)\\s*(\\d{1,2})\\s*[-,]?\\s*(?:episode|ep|e|الحلقة|حلقة)\\s*(\\d{1,4})")
    private val EP_WORD = Regex("(?i)(?:(?<![a-z])episode|(?<![a-z])ep|(?<![a-z])e|الحلقة|حلقة)\\s*(\\d{1,4})(?![a-z0-9])")
    private val SEASON_ONLY = Regex("(?i)(?:(?<![a-z])season|الموسم|موسم)\\s*(\\d{1,2})(?![a-z0-9])|^s(\\d{1,2})$")
    private val BARE = Regex("(?<=[\\p{L}\\p{N}])[\\s\\-]+(\\d{1,4})(?:v\\d)?(?=[\\s\\-]|$)")
    private val NOISE = Regex("(?i)(?<![a-z0-9])(2160p|1440p|1080p|1080i|720p|576p|480p|360p|4k|uhd|hdr10?|hdr|bluray|blu-ray|bdrip|brrip|bdremux|web-?dl|webrip|hdtv|dvdrip|dvd|remux|x ?26[45]|h ?26[45]|hevc|avc|aac ?\\d?|ac3|dts|ddp ?\\d?|atmos|flac|10 ?bit|8 ?bit|multi|dual|dubbed|subbed|repack|proper|extended|unrated|internal|amzn|dsnp|hmax|hulu|yify|yts|rarbg|eztv|ettv)(?![a-z0-9])")
    private val BAD_NUMS = setOf(264, 265, 360, 480, 576, 720, 1080, 1440, 2160)

    /** اسم ملف أو فولدر ← معلومات. مفيش استثناءات */
    fun parse(name: String): Info {
        val raw = stripExt(normDigits(name)).trim()
        var s = BRACKETS.replace(raw, " ")
        s = s.replace('_', ' ').replace('.', ' ')
        s = WS.replace(s, " ").trim()
        val yearV = YEAR.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1

        var season = -1; var ep = -1; var cut = -1; var weak = false
        SXE.find(s)?.let { season = it.groupValues[1].toInt(); ep = it.groupValues[2].toInt(); cut = it.range.first }
        if (ep < 0) NXN.find(s)?.let { season = it.groupValues[1].toInt(); ep = it.groupValues[2].toInt(); cut = it.range.first }
        if (ep < 0) SEASON_EP.find(s)?.let { season = it.groupValues[1].toInt(); ep = it.groupValues[2].toInt(); cut = it.range.first }
        if (ep < 0) EP_WORD.find(s)?.let { ep = it.groupValues[1].toInt(); cut = it.range.first }
        var seasonOnly = false
        if (ep < 0) SEASON_ONLY.find(s)?.let {
            val g = it.groupValues[1].ifEmpty { it.groupValues[2] }
            season = g.toIntOrNull() ?: -1; cut = it.range.first; seasonOnly = season >= 0
        }

        var end = s.length
        if (cut >= 0) end = minOf(end, cut)
        YEAR.find(s)?.let { if (it.range.first > 0) end = minOf(end, it.range.first) }
        NOISE.find(s)?.let { if (it.range.first > 0) end = minOf(end, it.range.first) }
        var head = s.substring(0, end).trim()
        var alt = head

        // رقم لوحده في آخر العنوان («Show - 12» / «One Piece 1015») ← حلقة بس تخمين
        if (ep < 0 && !seasonOnly) {
            val m = BARE.findAll(head).lastOrNull()
            if (m != null) {
                val v = m.groupValues[1].toInt()
                val isYear = v in 1900..2100
                if (v !in BAD_NUMS && !isYear) {
                    ep = v; weak = true
                    head = head.substring(0, m.range.first).trim()
                }
            }
        }
        val title = head.trim(' ', '-', '_', '.', ',', ':').trim()
        val titleAlt = alt.trim(' ', '-', '_', '.', ',', ':').trim()
        return Info(title, if (weak) titleAlt else title, yearV, season, ep, weak, raw)
    }

    private val GENERIC = setOf(
        "download", "downloads", "movies", "movie", "videos", "video", "camera", "dcim", "series", "tv", "shows", "show", "anime", "film", "films",
        "internal", "storage", "sdcard", "emulated", "0", "new folder", "mnt", "root", "primary", "media", "data", "files", "sd", "usb",
        "أفلام", "مسلسلات", "فيديو", "فيديوهات", "تحميلات", "التنزيلات", "كاميرا", "ترجمة", "subs", "subtitles", "sub"
    )
    fun isGeneric(t: String): Boolean {
        val x = t.trim().lowercase()
        return x.length < 2 || x in GENERIC || x.all { it.isDigit() || it == ' ' }
    }

    fun tokens(s: String): List<String> {
        val STOP = setOf("the", "a", "an", "of", "and", "to", "in", "و", "في", "من")
        return normDigits(s).lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in STOP }
    }

    /** نسبة تطابق كلمات (0..1) — تغطية كلمات الأولى في التانية + Jaccard */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a).toSet(); val tb = tokens(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val cover = inter / ta.size
        val jac = inter / ta.union(tb).size
        return 0.7 * cover + 0.3 * jac
    }

    /** تشابه نسخة الفيديو (جودة / مصدر / جروب) — بنفس الكلمات اللي مش عنوان */
    fun releaseSim(a: String, b: String): Double {
        fun rel(x: String) = tokens(x).filter { t -> NOISE.containsMatchIn(t) || t.length >= 3 && t.any { it.isDigit() } || t.length in 3..10 }.toSet()
        val ra = rel(a); val rb = rel(b)
        if (ra.isEmpty() || rb.isEmpty()) return 0.0
        return ra.intersect(rb).size.toDouble() / ra.union(rb).size
    }

    /** مفتاح ثابت لعنوان (لفولدر الكاش) */
    fun slug(t: String): String {
        val x = tokens(t).joinToString("-")
        return x.take(60).ifEmpty { "x" }
    }

    /**
     * اسم الفيديو + اسم الفولدر ← أحسن معلومات. العنوان من الفيديو لو واضح، وإلا من الفولدر؛ الموسم من أيهما.
     */
    fun build(videoName: String, folderName: String): Info {
        val v = parse(videoName)
        val f = if (folderName.isBlank()) null else parse(folderName)
        val fGood = f != null && f.title.length >= 3 && !isGeneric(f.title)
        val vGood = v.title.length >= 3 && !isGeneric(v.title)
        var title = v.title; var alt = v.titleAlt
        if (!vGood && fGood) { title = f!!.title; alt = f.titleAlt }
        else if (vGood && fGood && (v.episode >= 0)) {
            val tv = tokens(v.title).toSet(); val tf = tokens(f!!.title).toSet()
            if (tv.intersect(tf).isEmpty()) { title = f.title; alt = f.title }                // اسم الحلقة بس ← اسم المسلسل من الفولدر
            else if (tf.containsAll(tv) && tf.size > tv.size) { title = f.title; alt = f.title }   // الفولدر فيه الاسم الكامل
        }
        val season = if (v.season >= 0) v.season else (f?.season ?: -1)
        val year = if (v.year > 0) v.year else (f?.year ?: -1)
        return Info(title.ifBlank { v.raw }, alt.ifBlank { title.ifBlank { v.raw } }, year, season, v.episode, v.weakEp, v.raw)
    }

    /** أسماء ملفات جوه باك ← الأرقام (بالترتيب) اللي بتطابق (موسم، حلقة) */
    fun matchEpisodes(names: List<String>, season: Int, episode: Int): List<Int> {
        if (episode < 0) return emptyList()
        val out = ArrayList<Int>()
        for ((i, n) in names.withIndex()) {
            val p = parse(n)
            if (p.episode != episode) continue
            if (season >= 0 && p.season >= 0 && p.season != season) continue
            out.add(i)
        }
        return out
    }

    private val AR_TOK = Regex("(?i)(?:^|[\\s._\\-\\[\\](){}])(ar|ara|arab|arabic|عربي|عربى|العربية)(?=$|[\\s._\\-\\[\\](){}])")
    private val EN_TOK = Regex("(?i)(?:^|[\\s._\\-\\[\\](){}])(en|eng|english|انجليزي|إنجليزي)(?=$|[\\s._\\-\\[\\](){}])")
    /** لغة الملف من اسمه: ar / en / "" (مش معروف) */
    fun langOfName(name: String): String = when {
        AR_TOK.containsMatchIn(name) -> "ar"
        EN_TOK.containsMatchIn(name) -> "en"
        else -> ""
    }
}
