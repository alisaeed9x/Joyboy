package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject

/**
 * (v821) المنطق الصافي (من غير Android) لريموت IR الموحّد — متفصل عن الواجهة عشان يتختبر على JVM:
 *  1) الأوامر المنطقية (زرار واحد = أمر واحد) وربطها بأوامر الواي فاي ومفاتيح IR
 *  2) قواعد الراوتر المركزي (واي فاي أساسي ← IR احتياطي · منع التكرار · الصحة · الحالة)
 *  3) قاعدة بروفايلات IR المحفوظة (متعددة · JSON متوافق للخلف)
 *  4) فهرس الكتالوج: ماركات ← موديلات ← بروفايلات + البحث
 */

// ============================================================ 1) الأوامر المنطقية
class CmdDef(
    val id: String,
    /** مفتاح /key على التلفزيون (واي فاي) — أو null لو مفيش */
    val wifiKey: String?,
    /** أمر /cmd على التلفزيون (واي فاي) — للصوت والكتم */
    val wifiCmd: String?,
    /** مفاتيح IR بالترتيب (الأول هو الأساسي، والباقي بدائل لو الأساسي مش في الريموت) */
    val irKeys: List<String>,
    val label: String,
    /** باور/كتم: الأمر بيقلب حالة، فمينفعش يتكرر ولا يتبعت مرتين */
    val toggle: Boolean = false,
    /** أسهم/صوت: ماسك الزرار بيكرّر */
    val repeatable: Boolean = false,
    /** الواي فاي محتاج «إمكانية الوصول» لو فيه تطبيق تاني قدّام على التلفزيون */
    val needsAccess: Boolean = false,
    val minGapMs: Long = 120L
)

object Cmds {
    const val POWER = "power"; const val BACK = "back"; const val HOME_APP = "home_app"; const val HOME_TV = "home_tv"
    const val MENU = "menu"; const val UP = "up"; const val DOWN = "down"; const val LEFT = "left"; const val RIGHT = "right"
    const val OK = "ok"; const val OK_LONG = "ok_long"; const val VOL_UP = "vol_up"; const val VOL_DOWN = "vol_down"; const val MUTE = "mute"

    /** الأزرار المنطقية الـ13 اللي في الريموت الموحّد (+ OK_LONG = ضغطة طويلة على نفس زرار OK) */
    val all: List<CmdDef> = listOf(
        CmdDef(POWER, null, null, listOf("POWER"), "Power", toggle = true, minGapMs = 1500L),
        CmdDef(BACK, "back", null, listOf("BACK", "EXIT"), "Back", needsAccess = true),
        CmdDef(HOME_APP, "home", null, emptyList(), "Joyboy Home"),
        CmdDef(HOME_TV, "tvhome", null, listOf("HOME"), "TV Home"),
        CmdDef(MENU, "menu", null, listOf("MENU"), "Menu", needsAccess = true),
        CmdDef(UP, "up", null, listOf("UP"), "Up", repeatable = true, needsAccess = true, minGapMs = 60L),
        CmdDef(DOWN, "down", null, listOf("DOWN"), "Down", repeatable = true, needsAccess = true, minGapMs = 60L),
        CmdDef(LEFT, "left", null, listOf("LEFT"), "Left", repeatable = true, needsAccess = true, minGapMs = 60L),
        CmdDef(RIGHT, "right", null, listOf("RIGHT"), "Right", repeatable = true, needsAccess = true, minGapMs = 60L),
        CmdDef(OK, "ok", null, listOf("OK", "SEL", "ENTER"), "OK", needsAccess = true),
        // ضغطة طويلة: الواي فاي بيبعت ok_long؛ على IR مفيش «ضغطة طويلة» فبتتبعت دوسة OK عادية (حد معروف)
        CmdDef(OK_LONG, "ok_long", null, listOf("OK", "SEL", "ENTER"), "OK (long)", needsAccess = true, minGapMs = 700L),
        CmdDef(VOL_UP, null, "vol_up", listOf("VOLUP"), "Volume +", repeatable = true, minGapMs = 60L),
        CmdDef(VOL_DOWN, null, "vol_down", listOf("VOLDN"), "Volume -", repeatable = true, minGapMs = 60L),
        CmdDef(MUTE, null, "mute", listOf("MUTE"), "Mute", toggle = true, minGapMs = 450L)
    )
    private val byId = all.associateBy { it.id }
    fun of(id: String): CmdDef? = byId[id]

    /** الأوامر اللي بتروح لصوت التلفزيون */
    val VOLUME = setOf(VOL_UP, VOL_DOWN, MUTE)
    /** سويتش v820 القديم «أزرار IR على الريموت الأساسي»: الباور + الأسهم + OK + الكتم */
    val LEGACY_HYBRID = setOf(POWER, UP, DOWN, LEFT, RIGHT, OK, OK_LONG, MUTE)

    /** مفتاح من اللي بيتبعت على الواي فاي /key ← الأمر المنطقي (للتوافق مع الكود القديم اللي بيستخدم أسماء المفاتيح) */
    fun fromWifiKey(k: String): CmdDef? = all.firstOrNull { it.wifiKey == k }

    /** بدائل مفتاح IR لو الريموت مفيهوش المفتاح الأساسي — للريسيفرات اللي مفيهاش أسهم (قنوات/صوت بيتحركوا في القوايم) */
    fun satAliases(irKey: String): List<String> = when (irKey) {
        "UP" -> listOf("CHUP"); "DOWN" -> listOf("CHDN"); "LEFT" -> listOf("VOLDN"); "RIGHT" -> listOf("VOLUP")
        else -> emptyList()
    }
}

// ============================================================ 2) الراوتر
enum class Transport { NONE, WIFI, IR, WIFI_THEN_IR, BOTH }
enum class Why {
    OK, NO_IR_HW, NO_PROFILE, KEY_MISSING, IR_UNSUPPORTED, WIFI_DOWN, WIFI_OFF_MODE, UNKNOWN_CMD, DUPLICATE, WRONG_DEVICE
}
class Plan(val transport: Transport, val why: Why = Why.OK)

class RouteCtx(
    /** نوع الجهاز الهدف (tv/sat/other) */
    val targetKind: String,
    /** الهدف هو التلفزيون المقترن بالواي فاي (فقط ده ممكن يتبعتله واي فاي) */
    val wifiLinked: Boolean,
    val wifiConnected: Boolean,
    /** فشل واي فاي حديث (لسه ما رجعش) */
    val wifiBad: Boolean,
    /** الواي فاي يقدر ينفّذ الأمر ده فعلًا (مثلًا مفيش «إمكانية وصول» والتلفزيون على تطبيق تاني ← لأ) */
    val wifiCanExecute: Boolean,
    val irHw: Boolean,
    val hasProfile: Boolean,
    /** المفتاح اللازم للأمر موجود في بروفايل الهدف */
    val irKeyPresent: Boolean,
    /** auto (الافتراضي) | wifi | ir | irfirst (v820 القديم) */
    val mode: String = "auto",
    val dual: Set<String> = emptySet()
)

object RouterCore {
    fun irReason(cmd: CmdDef, c: RouteCtx): Why = when {
        cmd.irKeys.isEmpty() -> Why.IR_UNSUPPORTED
        !c.irHw -> Why.NO_IR_HW
        !c.hasProfile -> Why.NO_PROFILE
        !c.irKeyPresent -> Why.KEY_MISSING
        else -> Why.OK
    }

    /**
     * القرار الوحيد لتوجيه أي زرار:
     *  - الواي فاي أساسي لما التلفزيون متصل وسليم؛ IR بيتولّى لما الواي فاي مش متاح (أو فشل) — بدون إرسال الاتنين.
     *  - ساتلايت/جهاز تاني ← IR على بروفايله هو بس (مش بيتبعت لأي جهاز غلط).
     *  - إرسال الاتنين (BOTH) بس لو المستخدم فعّله صراحة لأمر مش «قلب» (باور/كتم) ومش بيتكرر.
     */
    fun plan(cmd: CmdDef, c: RouteCtx): Plan {
        val ir = irReason(cmd, c)
        val irOk = ir == Why.OK
        val hasWifiPath = c.targetKind == "tv" && c.wifiLinked && (cmd.wifiKey != null || cmd.wifiCmd != null)
        if (!hasWifiPath) {
            if (irOk) return Plan(Transport.IR)
            // جهاز مش واي فاي (ساتلايت…) والأمر ملوش مفتاح IR ← الأمر مش للجهاز ده
            if (c.targetKind != "tv" && cmd.irKeys.isEmpty()) return Plan(Transport.NONE, Why.WRONG_DEVICE)
            return Plan(Transport.NONE, ir)
        }
        val wifiUp = c.wifiConnected && !c.wifiBad && c.wifiCanExecute
        when (c.mode) {
            "wifi" -> return if (c.wifiConnected) Plan(Transport.WIFI) else Plan(Transport.NONE, Why.WIFI_DOWN)
            "ir" -> {
                if (irOk) return Plan(Transport.IR)
                return if (c.wifiConnected) Plan(Transport.WIFI) else Plan(Transport.NONE, ir)
            }
            "irfirst" -> if (irOk && cmd.id in Cmds.LEGACY_HYBRID) return Plan(Transport.IR)
        }
        // auto
        if (wifiUp) {
            if (irOk && cmd.id in c.dual && !cmd.toggle && !cmd.repeatable) return Plan(Transport.BOTH)
            return if (irOk) Plan(Transport.WIFI_THEN_IR) else Plan(Transport.WIFI)
        }
        if (irOk) return Plan(Transport.IR)
        if (c.wifiConnected) return Plan(Transport.WIFI)   // IR مش متاح لكن التلفزيون متصل: أحسن محاولة بالواي فاي
        return Plan(Transport.NONE, if (ir == Why.NO_IR_HW || ir == Why.IR_UNSUPPORTED) Why.WIFI_DOWN else ir)
    }

    /** رسالة للمستخدم لما الأمر ما اتبعتش (بالمصري) */
    fun explain(w: Why, cmd: CmdDef?): String = when (w) {
        Why.OK, Why.DUPLICATE -> ""
        Why.NO_IR_HW -> "الموبايل مفيهوش بلاستر IR — والتلفزيون مش متصل بالواي فاي"
        Why.NO_PROFILE -> "مفيش ريموت IR محفوظ للجهاز ده — افتح 📡 ريموت IR واحفظ واحد"
        Why.KEY_MISSING -> "الزرار ${cmd?.label ?: ""} مش موجود في ريموت IR المحفوظ"
        Why.IR_UNSUPPORTED -> "${cmd?.label ?: "الزرار"} بيشتغل بالواي فاي بس"
        Why.WIFI_DOWN -> "التلفزيون مش متصل بالواي فاي ومفيش IR احتياطي"
        Why.WIFI_OFF_MODE -> "الواي فاي مقفول من إعدادات IR"
        Why.UNKNOWN_CMD -> "أمر غير معروف"
        Why.WRONG_DEVICE -> "الزرار ده مش للجهاز المختار (${cmd?.label ?: ""})"
    }
}

/** منع التكرار: نفس الأمر ما يتبعتش مرتين في فترة قصيرة (ارتداد اللمس) — الفترة بتختلف بنوع الأمر */
class Gate {
    private val last = HashMap<String, Long>()
    @Synchronized fun allow(cmd: CmdDef, now: Long): Boolean {
        val l = last[cmd.id]
        if (l != null && now - l < cmd.minGapMs) return false
        last[cmd.id] = now
        return true
    }
    @Synchronized fun reset() { last.clear() }
}

/** صحة الواي فاي: بعد فشل بنعتبره «وحش» فترة (الأوامر بتروح IR مباشرة)، وبنرجع نجرّبه تاني تلقائيًا؛ الرجوع المتصل بيصفّيها فورًا */
class Health(private val badMs: Long = 12_000L) {
    @Volatile private var badUntil = 0L
    fun fail(now: Long) { badUntil = now + badMs }
    fun ok() { badUntil = 0L }
    fun onConnected() { badUntil = 0L }
    fun isBad(now: Long): Boolean = now < badUntil
}

enum class IrStatus { WIFI_ACTIVE, IR_ACTIVE, CONNECTING, UNAVAILABLE, IR_UNSUPPORTED }

object StatusCore {
    /** state: CONNECTED | CONNECTING | IDLE | LOST */
    fun of(targetKind: String, wifiLinked: Boolean, state: String, wifiBad: Boolean, irHw: Boolean, hasProfile: Boolean, mode: String): IrStatus {
        val irUsable = irHw && hasProfile
        if (!(targetKind == "tv" && wifiLinked)) return if (!irHw) IrStatus.IR_UNSUPPORTED else if (hasProfile) IrStatus.IR_ACTIVE else IrStatus.UNAVAILABLE
        if (mode == "ir") return if (!irHw) IrStatus.IR_UNSUPPORTED else if (hasProfile) IrStatus.IR_ACTIVE else IrStatus.UNAVAILABLE
        val wifiUp = state == "CONNECTED" && !wifiBad
        if (wifiUp) return IrStatus.WIFI_ACTIVE
        if (irUsable && mode != "wifi") return IrStatus.IR_ACTIVE
        if (state == "CONNECTED") return IrStatus.WIFI_ACTIVE   // متصل لكن مفيش بديل: أحسن محاولة
        if (state == "CONNECTING") return IrStatus.CONNECTING
        return IrStatus.UNAVAILABLE
    }
    fun label(s: IrStatus): String = when (s) {
        IrStatus.WIFI_ACTIVE -> "Wi-Fi فعّال"
        IrStatus.IR_ACTIVE -> "IR فعّال"
        IrStatus.CONNECTING -> "جاري الاتصال…"
        IrStatus.UNAVAILABLE -> "الجهاز غير متاح"
        IrStatus.IR_UNSUPPORTED -> "IR غير مدعوم"
    }
}

// ============================================================ 3) البروفايلات
class IrProfile(
    val id: String,
    var name: String,
    /** tv | sat | other */
    var kind: String,
    var brand: String,
    /** اسم الريموت في قاعدة IR (IrTv.Remote.name) */
    var remote: String,
    /** معرّف التلفزيون المقترن بالواي فاي (فاضي = غير مربوط بتلفزيون معيّن) */
    var deviceId: String = "",
    var created: Long = 0L,
    /** قاعدة الأكواد اللي الريموت جه منها: tv | sat (فاضي = يتحسب من النوع) */
    var src: String = ""
) {
    fun dbKind(): String = if (src == "tv" || src == "sat") src else if (kind == "sat") "sat" else "tv"

    /** إعادة ربط (متقدّم): مفتاح الأمر ← مفتاح تاني من نفس الريموت. مثال: BACK ← EXIT */
    val remap = LinkedHashMap<String, String>()
    /** كود يدوي (متقدّم): مفتاح الأمر ← "PROTO|a|b" أو "RAW|freq|pulses" بنفس صيغة القاعدة */
    val custom = LinkedHashMap<String, String>()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("brand", brand).put("remote", remote)
        .put("device", deviceId).put("created", created).put("src", src)
        .put("remap", JSONObject().also { o -> for ((k, v) in remap) o.put(k, v) })
        .put("custom", JSONObject().also { o -> for ((k, v) in custom) o.put(k, v) })

    companion object {
        fun fromJson(o: JSONObject): IrProfile? {
            val id = o.optString("id"); if (id.isEmpty()) return null
            val kind = o.optString("kind", "tv").let { if (it == "sat" || it == "other") it else "tv" }
            val p = IrProfile(id, o.optString("name", id), kind, o.optString("brand"), o.optString("remote"), o.optString("device"), o.optLong("created", 0L), o.optString("src"))
            o.optJSONObject("remap")?.let { m -> for (k in m.keys()) p.remap[k] = m.optString(k) }
            o.optJSONObject("custom")?.let { m -> for (k in m.keys()) p.custom[k] = m.optString(k) }
            return p
        }
    }
}

class IrSettings {
    /** auto | wifi | ir | irfirst */
    var mode = "auto"
    /** الصوت/الكتم بيروح لبروفايل التلفزيون حتى لو الهدف ساتلايت مفيهوش أزرار صوت */
    var volFollowsTv = false
    /** أوامر مسموح تتبعت واي فاي + IR مع بعض (صراحةً من المستخدم) */
    val dual = LinkedHashSet<String>()
}

class ProfileDb {
    val profiles = ArrayList<IrProfile>()
    val defaults = LinkedHashMap<String, String>()
    var target = ""
    val settings = IrSettings()
    private var seq = 0

    fun byId(id: String): IrProfile? = profiles.firstOrNull { it.id == id }
    fun ofKind(kind: String): List<IrProfile> = profiles.filter { it.kind == kind }
    fun defaultFor(kind: String): IrProfile? = defaults[kind]?.let { byId(it) } ?: profiles.firstOrNull { it.kind == kind }

    fun newId(now: Long): String { var id: String; do { id = "p" + now.toString(36) + (seq++).toString(36) } while (byId(id) != null); return id }

    fun uniqueName(base: String): String {
        if (profiles.none { it.name == base }) return base
        var n = 2; while (profiles.any { it.name == "$base $n" }) n++
        return "$base $n"
    }

    fun add(p: IrProfile): IrProfile {
        profiles.add(p)
        if (defaults[p.kind] == null || byId(defaults[p.kind]!!) == null) defaults[p.kind] = p.id
        return p
    }

    fun rename(id: String, name: String): Boolean {
        val p = byId(id) ?: return false
        val n = name.trim(); if (n.isEmpty()) return false
        p.name = n; return true
    }

    fun delete(id: String): Boolean {
        val p = byId(id) ?: return false
        profiles.remove(p)
        if (defaults[p.kind] == id) { val nx = profiles.firstOrNull { it.kind == p.kind }; if (nx != null) defaults[p.kind] = nx.id else defaults.remove(p.kind) }
        if (target == id) target = ""
        return true
    }

    /** استبدال: نفس البروفايل (الاسم + الجهاز المرتبط + الافتراضي) بريموت تاني من القاعدة. التعديلات اليدوية بتتمسح لأن الأكواد اتغيّرت */
    fun replace(id: String, brand: String, remote: String): Boolean {
        val p = byId(id) ?: return false
        p.brand = brand; p.remote = remote; p.remap.clear(); p.custom.clear(); return true
    }

    fun setDefault(id: String): Boolean { val p = byId(id) ?: return false; defaults[p.kind] = id; return true }

    fun toJson(): String {
        val o = JSONObject().put("v", 1)
        o.put("profiles", JSONArray().also { a -> for (p in profiles) a.put(p.toJson()) })
        o.put("defaults", JSONObject().also { d -> for ((k, v) in defaults) d.put(k, v) })
        o.put("target", target)
        o.put("settings", JSONObject().put("mode", settings.mode).put("volFollowsTv", settings.volFollowsTv).put("dual", JSONArray().also { a -> for (x in settings.dual) a.put(x) }))
        return o.toString()
    }

    companion object {
        /** قراءة متسامحة: حقول مجهولة (نسخة أحدث) بتتتجاهل، وبروفايل بايظ بيتخطّى من غير ما يضيّع الباقي */
        fun fromJson(s: String?): ProfileDb {
            val db = ProfileDb()
            if (s.isNullOrBlank()) return db
            try {
                val o = JSONObject(s)
                val a = o.optJSONArray("profiles")
                if (a != null) for (i in 0 until a.length()) { val p = a.optJSONObject(i)?.let { IrProfile.fromJson(it) } ?: continue; if (db.byId(p.id) == null) db.profiles.add(p) }
                o.optJSONObject("defaults")?.let { d -> for (k in d.keys()) { val v = d.optString(k); if (db.byId(v) != null) db.defaults[k] = v } }
                db.target = o.optString("target").let { if (db.byId(it) != null) it else "" }
                o.optJSONObject("settings")?.let { st ->
                    db.settings.mode = st.optString("mode", "auto").let { if (it in setOf("auto", "wifi", "ir", "irfirst")) it else "auto" }
                    db.settings.volFollowsTv = st.optBoolean("volFollowsTv", false)
                    st.optJSONArray("dual")?.let { da -> for (i in 0 until da.length()) { val c = da.optString(i); if (Cmds.of(c) != null) db.settings.dual.add(c) } }
                }
                for (k in listOf("tv", "sat", "other")) if (db.defaults[k] == null) db.profiles.firstOrNull { it.kind == k }?.let { db.defaults[k] = it.id }
            } catch (_: Exception) { }
            return db
        }
    }

    /** صيغة كود يدوي سليمة؟ ("N|addr|cmd" · "RAW|freq|ints…") */
    object SigText {
        private val PROTOS = setOf("N", "NX", "S", "R5", "R6", "S12", "S15", "S20")
        fun valid(t: String): Boolean {
            val p = t.trim().split('|', limit = 3)
            if (p.size < 3) return false
            return try {
                if (p[0] == "RAW") {
                    val f = p[1].trim().toInt(); val ints = p[2].trim().split(Regex("\\s+")).map { it.toInt() }
                    f in 20000..60000 && ints.size >= 2 && ints.all { it > 0 }
                } else p[0] in PROTOS && p[1].trim().toLong() >= 0 && p[2].trim().toLong() >= 0
            } catch (_: Exception) { false }
        }
    }
}

// ============================================================ 4) الكتالوج
/** ريموت من قاعدة IR كما يراه الكتالوج (من غير الأكواد نفسها). sig = بروتوكول+عنوان زرار الباور (لتجميع الريموتات المتوافقة) */
class CatEntry(val brand: String, val name: String, val kind: String, val keys: Set<String>, val sig: String)

class BrandInfo(val name: String, val count: Int)
class CatModel(val brand: String, val kind: String, val key: String, val label: String, val entries: List<CatEntry>)
class SearchHit(val model: CatModel, val score: Int)

class CatIndex(val entries: List<CatEntry>) {
    companion object {
        val POPULAR = listOf("Samsung", "LG", "TCL", "Toshiba", "Sharp", "Sony", "Hisense", "Panasonic", "Philips", "Haier")
        private val ALIASES = mapOf("redmi" to "xiaomi", "mi" to "xiaomi", "mitv" to "xiaomi", "lge" to "lg", "dish" to "dish tv", "tatasky" to "tata sky", "tataplay" to "tata sky")

        fun norm(s: String): String { val b = StringBuilder(); for (ch in s.lowercase()) if (ch.isLetterOrDigit()) b.append(ch); return b.toString() }

        /** اسم الموديل (رقم الريموت/التلفزيون) من اسم الريموت: بيشيل اسم الماركة و«#N» و«(Mi)» و«(عدد الأزرار)». فاضي = أكواد عامة للماركة */
        fun modelPart(brand: String, name: String): String {
            var s = name.trim()
            s = s.replace(Regex("\\s*#\\d+"), "").replace(Regex("\\s*\\(\\s*Mi\\s*\\)", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
            s = s.trim()
            if (s.startsWith(brand, ignoreCase = true)) s = s.substring(brand.length)
            s = s.replace('_', ' ').trim(' ', '-', ':', '.', '/')
            return s.trim()
        }
    }

    private val groups: List<CatModel> by lazy {
        val m = LinkedHashMap<String, MutableList<CatEntry>>()
        for (e in entries) m.getOrPut(e.kind + "\u0001" + e.brand + "\u0001" + norm(modelPart(e.brand, e.name))) { ArrayList() }.add(e)
        m.map { (_, l) ->
            val f = l[0]; val label = modelPart(f.brand, f.name)
            CatModel(f.brand, f.kind, norm(label).ifEmpty { "generic" }, label, l.sortedWith(compareByDescending<CatEntry> { it.keys.size }.thenBy { it.name }))
        }
    }

    private val brandCount: Map<String, Map<String, Int>> by lazy {
        val m = HashMap<String, HashMap<String, Int>>()
        for (e in entries) { val b = m.getOrPut(e.kind) { HashMap() }; b[e.brand] = (b[e.brand] ?: 0) + 1 }
        m
    }

    fun brands(kind: String): List<BrandInfo> = (brandCount[kind] ?: emptyMap()).map { BrandInfo(it.key, it.value) }.sortedBy { it.name.lowercase() }

    /** الـ10 ماركات الشهيرة بالترتيب المطلوب (اللي موجودة في القاعدة بس) */
    fun popular(): List<BrandInfo> {
        val tv = brandCount["tv"] ?: emptyMap()
        return POPULAR.mapNotNull { p -> tv.entries.firstOrNull { it.key.equals(p, true) }?.let { BrandInfo(it.key, it.value) } }
    }

    /** «ماركات تانية»: أكتر الماركات ريموتات من غير العشرة الشهيرة */
    fun otherBrands(limit: Int = 48): List<BrandInfo> {
        val pop = popular().map { it.name.lowercase() }.toSet()
        return brands("tv").filter { it.name.lowercase() !in pop }.sortedWith(compareByDescending<BrandInfo> { it.count }.thenBy { it.name.lowercase() }).take(limit)
    }

    fun models(kind: String, brand: String): List<CatModel> =
        groups.filter { it.kind == kind && it.brand.equals(brand, true) }
            .sortedWith(compareBy<CatModel> { it.key == "generic" }.thenBy { it.label.lowercase() })

    /**
     * بروفايلات موديل: الريموتات الخاصة بالموديل ده الأول، وبعدها ريموتات تانية من نفس الماركة بنفس عنوان IR (متوافقة فعلًا)
     * وبعدها باقي ريموتات الماركة. مفيش افتراض إن كل ريموتات الماركة بنفس الأكواد — المستخدم بيجرّب ويحفظ اللي يشتغل.
     */
    fun profilesFor(m: CatModel): List<CatEntry> {
        val first = m.entries
        val addrs = first.map { sigAddr(it.sig) }.filter { it.isNotEmpty() }.toSet()
        val rest = entries.filter { it.kind == m.kind && it.brand.equals(m.brand, true) && first.none { f -> f === it } }
        val same = rest.filter { sigAddr(it.sig) in addrs }.sortedWith(compareByDescending<CatEntry> { it.keys.size }.thenBy { it.name })
        val others = rest.filter { it !in same }.sortedWith(compareByDescending<CatEntry> { it.keys.size }.thenBy { it.name })
        return first + same + others
    }
    private fun sigAddr(s: String) = s

    fun find(kind: String, brand: String, name: String): CatEntry? = entries.firstOrNull { it.kind == kind && it.brand == brand && it.name == name }

    /**
     * بحث ذكي: ماركة · موديل تلفزيون · رقم ريموت · ماركة/موديل ريسيفر. كل كلمة لازم تتلاقى (AND)، من غير حساسية لحروف كبيرة/صغيرة ولا شرطات/مسافات (AA59-00443A = aa5900443a).
     */
    fun search(query: String, limit: Int = 60): List<SearchHit> {
        val toks = query.lowercase().split(Regex("[\\s,/،]+")).map { norm(it) }.filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val popSet = POPULAR.map { it.lowercase() }.toSet()
        val out = ArrayList<SearchHit>()
        for (g in groups) {
            val b = norm(g.brand); val lab = norm(g.label)
            val names = g.entries.joinToString(" ") { norm(it.name) }
            var score = 0; var ok = true
            for (t0 in toks) {
                val t = ALIASES[t0]?.let { norm(it) } ?: t0
                val s = when {
                    b == t -> 60
                    lab == t -> 70
                    b.startsWith(t) -> 40
                    lab.startsWith(t) -> 45
                    lab.contains(t) -> 30
                    b.contains(t) -> 20
                    names.contains(t) -> 12
                    else -> -1
                }
                if (s < 0) { ok = false; break }
                score += s
            }
            if (!ok) continue
            if (g.brand.lowercase() in popSet) score += 5
            out.add(SearchHit(g, score))
        }
        return out.sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.model.brand.lowercase() }.thenBy { it.model.label.lowercase() }).take(limit)
    }
}
