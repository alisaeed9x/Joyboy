package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** (v1275) «فتح كلام» جزء أصلي من التطبيق: بنك الأسئلة (3745 سؤال في assets/fk/seed.json) + الأسئلة المولّدة + الإعدادات. كله محلي على الجهاز. */
object FkData {
    class Cat(val id: String, val name: String, val icon: String)
    class Group(val id: String, val name: String, val icon: String, val subs: List<Cat>)
    class Q(val id: String, val cat: String, val text: String, val b: Int, val r: Int, val d: Int, val gen: Boolean)

    /** أقسام +18: تنبيه كل مرة، و«كوكو واوا» كمان بكلمة سر */
    val ADULT = setOf("couples18", "engaged", "married")
    const val LOCKED = "couples18"
    const val PASS = "0138"

    val groups: List<Group> = listOf(
        Group("g_rel", "العلاقات", "❤️", listOf(Cat("love", "الحب والعلاقة", "❤️"), Cat("romance", "الرومانسية", "💕"), Cat("engaged", "مخطوبين", "💍"), Cat("married", "المتزوجين", "💑"), Cat("couples18", "كوكو واوا", "🔞"))),
        Group("g_conv", "شخصية ونقاش", "🧠", listOf(Cat("personality", "الشخصية", "🧠"), Cat("honesty", "الصراحة", "🔥"), Cat("whatif", "ماذا لو؟", "🎭"), Cat("long", "مواضيع طويلة", "🗣️"))),
        Group("g_fun", "ضحك ومواقف", "😂", listOf(Cat("funny", "الضحك والهزار", "😂"), Cat("embarrassment", "الإحراج", "😳"), Cat("riddles", "فوازير", "🧩"), Cat("jokes", "نكت", "😂"), Cat("jokes18", "نكت +18", "🔞"))),
        Group("g_mem", "الحياة والذكريات", "🌍", listOf(Cat("childhood", "الطفولة والماضي", "🧒"), Cat("life", "الحياة والمستقبل", "🌍"))),
        Group("family", "أسئلة أسرية", "👨‍👩‍👧", emptyList()),
        Group("g_girls", "بنات", "💅", listOf(Cat("girls_cooking", "طبخ", "🍳"), Cat("girls_makeup", "مكياج", "💄"), Cat("girls_relationships", "علاقات", "💞"), Cat("girls_secrets", "أسرار وحاجات سرية", "🤫"))),
        Group("g_youth", "شباب", "🎯", listOf(Cat("youth_talk", "كلام بين الشباب", "🗨️"), Cat("youth_relationships", "علاقات", "💬"), Cat("youth_situations", "مواقف", "🎬"))),
        Group("coworkers", "زملاء العمل", "💼", emptyList()),
        Group("g_media", "أفلام ومسلسلات", "🎬", listOf(Cat("series_foreign", "مسلسلات أجنبي", "🌐"), Cat("series_korean", "مسلسلات كوري", "🇰🇷"), Cat("series_arabic", "مسلسلات عربي", "🇪🇬"), Cat("series_anime", "أنمي مسلسلات", "🎌"), Cat("movies_foreign", "أفلام أجنبي", "🌐"), Cat("movies_korean", "أفلام كوري", "🇰🇷"), Cat("movies_arabic", "أفلام عربي", "🇪🇬"), Cat("movies_anime", "أنمي أفلام", "🎌")))
    )
    /** القسم المسطّح: لو المجموعة من غير فروع بتبقى هي نفسها قسم */
    fun cats(g: Group): List<Cat> = if (g.subs.isEmpty()) listOf(Cat(g.id, g.name, g.icon)) else g.subs
    fun allCats(): List<Cat> = groups.flatMap { cats(it) }
    fun catOf(id: String): Cat? = allCats().firstOrNull { it.id == id }
    fun groupOf(id: String): Group? = groups.firstOrNull { it.id == id }
    fun nameOf(id: String): String = if (id == "all") "🎲 الكل" else catOf(id)?.let { it.icon + " " + it.name } ?: groupOf(id)?.let { it.icon + " " + it.name } ?: id
    fun needsMedia(id: String) = id.startsWith("series_") || id.startsWith("movies_")
    private val mediaAlso = setOf("series", "movies")

    private lateinit var app: Context
    private lateinit var sp: android.content.SharedPreferences
    @Volatile private var seed: List<Q>? = null
    private val gen = ArrayList<Q>()
    private var genLoaded = false
    fun init(c: Context) { if (!::app.isInitialized) { app = c.applicationContext; sp = app.getSharedPreferences("fk", Context.MODE_PRIVATE) } }

    @Synchronized fun all(): List<Q> {
        var s = seed
        if (s == null) {
            val out = ArrayList<Q>(4000)
            try {
                val txt = app.assets.open("fk/seed.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val a = JSONArray(txt)
                for (i in 0 until a.length()) { val r = a.getJSONArray(i); out.add(Q("s$i", r.getString(0), r.getString(1), r.optInt(2, 30), r.optInt(3, 30), r.optInt(4, 30), false)) }
            } catch (e: Throwable) { LogStore.err("Fk:seed", e) }
            s = out; seed = s
        }
        if (!genLoaded) { genLoaded = true; try { val f = File(app.filesDir, "fk_gen.json"); if (f.exists()) { val a = JSONArray(f.readText()); for (i in 0 until a.length()) { val o = a.getJSONObject(i); gen.add(Q(o.getString("id"), o.getString("c"), o.getString("t"), o.optInt("b", 30), o.optInt("r", 30), o.optInt("d", 30), true)) } } } catch (e: Throwable) { LogStore.err("Fk:gen", e) } }
        return s + gen
    }
    @Synchronized fun addGenerated(l: List<Q>) {
        all(); gen.addAll(l)
        try { val a = JSONArray(); for (q in gen) a.put(JSONObject().put("id", q.id).put("c", q.cat).put("t", q.text).put("b", q.b).put("r", q.r).put("d", q.d)); File(app.filesDir, "fk_gen.json").writeText(a.toString()) } catch (e: Throwable) { LogStore.err("Fk:saveGen", e) }
    }
    fun norm(t: String): String = t.replace(Regex("[\\u064B-\\u0652\\s\\p{Punct}؟،]"), "")

    /** الأسئلة المتاحة للمود: all = كل حاجة عادية · مجموعة = أقسامها العادية · قسم = نفسه */
    fun pool(mood: String): List<Q> {
        val l = all()
        if (mood == "all") return l.filter { it.cat !in ADULT }
        val g = groupOf(mood)
        if (g != null) {
            val ids = (if (g.subs.isEmpty()) listOf(g.id) else g.subs.map { it.id }).filter { it !in ADULT }.toMutableSet()
            if (g.id == "g_media") ids.addAll(mediaAlso)
            return l.filter { it.cat in ids }
        }
        val ids = mutableSetOf(mood); if (mood.startsWith("series_")) ids.add("series"); if (mood.startsWith("movies_")) ids.add("movies")
        return l.filter { it.cat in ids }
    }
    fun count(mood: String): Int = pool(mood).size

    // ---- الإعدادات والحالة ----
    fun b(): Int = sp.getInt("b", 30); fun r(): Int = sp.getInt("r", 40); fun d(): Int = sp.getInt("d", 50)
    fun setB(v: Int) = sp.edit().putInt("b", v).apply(); fun setR(v: Int) = sp.edit().putInt("r", v).apply(); fun setD(v: Int) = sp.edit().putInt("d", v).apply()
    fun noRepeat(): Boolean = sp.getBoolean("norepeat", true); fun setNoRepeat(v: Boolean) = sp.edit().putBoolean("norepeat", v).apply()
    fun batch(): Int = sp.getInt("batch", 20); fun setBatch(v: Int) = sp.edit().putInt("batch", v).apply()
    fun mood(): String = sp.getString("mood", "all") ?: "all"; fun setMood(m: String) = sp.edit().putString("mood", m).apply()
    fun textSize(): Int = sp.getInt("tsize", 22); fun setTextSize(v: Int) = sp.edit().putInt("tsize", v).apply()
    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    @Synchronized fun seenToday(): MutableSet<String> { if (sp.getString("seen_day", "") != today()) { sp.edit().putString("seen_day", today()).putStringSet("seen", HashSet()).apply() }; return HashSet(sp.getStringSet("seen", HashSet()) ?: HashSet()) }
    @Synchronized fun markSeen(id: String) { val s = seenToday(); s.add(id); sp.edit().putStringSet("seen", s).apply() }
    fun resetSeen() = sp.edit().putStringSet("seen", HashSet()).apply()
    fun favs(): MutableSet<String> = HashSet(sp.getStringSet("favs", HashSet()) ?: HashSet())
    fun isFav(id: String) = id in favs()
    fun toggleFav(id: String): Boolean { val f = favs(); val on = if (id in f) { f.remove(id); false } else { f.add(id); true }; sp.edit().putStringSet("favs", f).apply(); return on }
    fun recent(): List<String> = (sp.getString("recent", "") ?: "").split("\u0001").filter { it.isNotBlank() }
    fun pushRecent(t: String) { val l = ArrayList(recent()); l.remove(t); l.add(0, t); sp.edit().putString("recent", l.take(30).joinToString("\u0001")).apply() }
    fun clearGenerated() { synchronized(this) { gen.clear(); try { File(app.filesDir, "fk_gen.json").delete() } catch (_: Throwable) {} } }
    fun genCount(): Int { all(); return synchronized(this) { gen.size } }

    /** اختيار السؤال الجاي: الأقرب للسلايدرز + عشوائية، من غير ما نكرر اللي اتشاف النهاردة */
    fun next(mood: String): Q? {
        val p = pool(mood); if (p.isEmpty()) return null
        var c = p
        if (noRepeat()) { val seen = seenToday(); val f = p.filter { it.id !in seen }; if (f.isEmpty()) { resetSeen() } else c = f }
        val bb = b(); val rr = r(); val dd = d(); val rnd = java.util.Random()
        var best: Q? = null; var bs = Int.MAX_VALUE
        for (i in 0 until minOf(c.size, 40)) { val q = c[rnd.nextInt(c.size)]; val s = Math.abs(q.b - bb) + Math.abs(q.r - rr) + Math.abs(q.d - dd) + rnd.nextInt(40); if (s < bs) { bs = s; best = q } }
        return best
    }
}
