package com.tttt.subtitler

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * (v288) البرومبتات المعدّلة من المستخدم. كل برومبت ليه مفتاح (key): لو فيه نسخة معدّلة محفوظة بتتستخدم بدل الأصلي فورًا
 * (من غير ريستارت)، ولو مفيش بيتستخدم الأصلي زي ما كان بالظبط. الملفات في Android/media/<الحزمة>/prompts (بتعدّي مسح التطبيق)
 * وبترجع لـ filesDir/prompts_custom لو المسار ده مش متاح.
 * المفاتيح: "prompts/xxx.txt" = ملف من assets · "blk:NAME" = بلوك في PromptBuilder · "mp:NAME" = برومبت وضع 2–4 (ModePrompts) · "eng:NAME" = برومبت جوه Engine.
 */
object PromptStore {
    @Volatile private var dir: File? = null
    private val map = ConcurrentHashMap<String, String>()
    /** بيزيد مع كل تعديل: أي كاش مبني على البرومبتات يقارنه */
    @Volatile var rev = 0; private set

    private fun fname(key: String) = key.replace("/", "--").replace(":", "__") + ".txt"
    private fun keyOf(name: String) = name.removeSuffix(".txt").replace("__", ":").replace("--", "/")

    fun init(c: Context) {
        val app = c.applicationContext
        val d = try {
            app.externalMediaDirs?.firstOrNull { it != null }?.let { File(it, "prompts") }
                ?.takeIf { (it.isDirectory || it.mkdirs()) && it.canWrite() }
        } catch (e: Throwable) { LogStore.err("PromptStore:dir", e); null }
            ?: File(app.filesDir, "prompts_custom").also { it.mkdirs() }
        dir = d
        load()
    }

    fun load() {
        val d = dir ?: return
        val m = HashMap<String, String>()
        try {
            d.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".txt")) {
                    val t = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { LogStore.err("PromptStore:read", e); null }
                    if (t != null && t.isNotBlank()) m[keyOf(f.name)] = t
                }
            }
        } catch (e: Throwable) { LogStore.err("PromptStore:load", e) }
        map.clear(); map.putAll(m); rev++
    }

    /** النسخة المعدّلة أو null لو مفيش */
    fun get(key: String): String? = map[key]
    /** المعدّل لو موجود وإلا الأصلي */
    fun pick(key: String, def: String): String = map[key] ?: def
    fun has(key: String) = map.containsKey(key)
    fun count() = map.size

    fun set(key: String, text: String): Boolean {
        if (text.isBlank()) { reset(key); return true }
        val d = dir ?: return false
        return try {
            d.mkdirs(); File(d, fname(key)).writeText(text, Charsets.UTF_8)
            map[key] = text; rev++; true
        } catch (e: Throwable) { LogStore.err("PromptStore:set", e); false }
    }

    fun reset(key: String) {
        try { dir?.let { File(it, fname(key)).delete() } } catch (e: Throwable) { LogStore.err("PromptStore:reset", e) }
        map.remove(key); rev++
    }

    fun resetAll() {
        for (k in map.keys.toList()) reset(k)
    }

    /** استبدال {{NAME}} بقيمها (للبرومبتات اللي جوه Engine) */
    fun fill(t: String, vararg kv: Pair<String, String>): String {
        var o = t
        for ((k, v) in kv) o = o.replace("{{$k}}", v)
        return o
    }

    /** علامة سياق القوالب (حرف خفي \u0001) بتتعرض في المحرر كـ {{CTX}} عشان تبان وتتعدّل */
    fun toEditor(t: String) = t.replace("\u0001", "{{CTX}}")
    fun fromEditor(t: String) = t.replace("{{CTX}}", "\u0001")
}

/** برومبتات كانت مكتوبة جوه Engine (تحويل اللهجة · التنقيح) — بقت قوالب فيها {{مكان}} عشان تتعدّل */
object EnginePrompts {
    const val CONV = """أنت محرر ترجمة محترف. الجمل دي مترجمة بالفصحى الحرفية. حوّل حقل translated في كل جملة للهجة {{TGT}} الحقيقية (زي ما أهلها بيتكلموا فعلًا) مع الحفاظ الكامل على المعنى والأسماء والأرقام وجنس المتكلم speaker_gender والمخاطَب addressee_gender.
- ماتدمجش ولا تقسّم جمل ولا تغيّر عددها أو ترتيبها. original للمرجع بس (لو الفصحى فيها غلط في المعنى صحّحه من original).
- 🔴 لازم ترجّع كل الجمل (حتى لو الجملة أصلًا قريبة من اللهجة) بالصياغة النهائية باللهجة.
{{DIALECT}}{{TONE}}
الجمل:
{{LIST}}

أرجع JSON فقط: {"rewrites":[{"idx":0,"translated":"النص باللهجة"}]}"""
    const val REFINE = """أنت مراجع ترجمة محترف. دي جمل فيلم/مسلسل/فيديو بالترتيب. الترجمة الحالية (translated) اتعملت على مقاطع صوتية منفصلة، كل مقطع حوالي دقيقة اتترجم لوحده من غير ما يشوف اللي قبله واللي بعده كويس — يعني غالبًا فيها جمل اتترجمت حرفيًا أو بمعنى الكلمة لوحدها، ولما الجملة تتقرا وسط اللي حواليها معناها الحقيقي بيبقى مختلف.
لكل جملة: i = رقمها، original = الأصل، en = نقل إنجليزي حرفي (لو موجود)، translated = الترجمة الحالية، speaker = جنس المتكلم، to = المخاطَب، gap = ثواني الصمت قبلها (فجوة كبيرة غالبًا = مشهد أو موضوع جديد).
{{ROSTER}}
الجمل:
{{ARR}}

طريقة الشغل: اقرا كل جملة مع 4-5 جمل قبلها وبعدها كأنك بتتفرج على المشهد، واسأل نفسك: هل الترجمة الحالية بتوصّل اللي المتكلم يقصده فعلًا هنا؟ صحّح الجملة لما تلاقي:
1) ترجمة حرفية معناها بيتغيّر مع السياق: تعبير اصطلاحي، سخرية، تلميح، مجاز، سؤال بلاغي، شتيمة أو مجاملة بتتقال بمعنى غير معناها الحرفي، أو رد على جملة سابقة.
2) فاعل أو مفعول أو ضمير محذوف في الأصل واتفهم غلط، أو مرجع الضمير (هو/هي/ده) مش هو اللي في المشهد.
3) جملة مكمّلة لجملة قبلها أو بعدها (اتقطعت بين مقطعين) وترجمتها مش متصلة بيها.
4) اسم أو مصطلح أو لقب متترجم بأشكال مختلفة، أو جنس/ضمير غلط، أو جملة مش مفهومة، أو بتتناقض مع اللي حواليها.
القواعد:
- حافظ على لهجة {{LANG}} وأسلوب {{STYLE}}، وخلّي الجملة قصيرة ومناسبة للعرض كترجمة (قد الأصلية تقريبًا).
- ماتغيّرش جملة سليمة ولا تحسّن أسلوب بس. التصحيح لازم يبقى لمعنى أدق أو أوضح من السياق.
- ممنوع تضيف أو تحذف أو تدمج جمل، والتوقيت مش بتاعك.
رجّع JSON فقط: {"corrections":[{"i":12,"why":"السبب في كلمتين تلاتة","translated":"النص المصحح الكامل للجملة"}]} — الجمل السليمة ماتتحطش. لو كله سليم: {"corrections":[]}"""
}

class PEntry(val key: String, val title: String, val note: String, val required: List<String>, val default: () -> String)
class PGroup(val title: String, val note: String, val entries: List<PEntry>)

object PromptCatalog {
    val langs = listOf("مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني", "فصحى")
    val styles = listOf("حرفي", "شعبي", "جرئ", "+18")
    private val caseTitle = linkedMapOf(
        "first" to "أول باتش (لغة الصوت لسه مش معروفة)",
        "ja" to "لو الصوت ياباني",
        "en" to "لو الصوت إنجليزي",
        "other" to "لو لغة تانية (كورية · صينية · …)")
    private const val TPL_REQ = "§ROSTER§"

    /** القوالب الأربعة (حسب لغة الصوت) للهجة + أسلوب معيّنين. [read] = قارئ assets الأصلي (من غير تعديلات) */
    fun templates(read: (String) -> String, lang: String, style: String): List<PEntry> {
        val pb = PromptBuilder(read)
        return caseTitle.map { (case, title) ->
            val id = pb.idFor(lang, style, case)
            PEntry("prompts/$id.txt", title, "قالب $lang · $style", listOf("{{DUR}}", "§ROSTER§", "{{CTX}}")) { read("prompts/$id.txt") }
        }
    }

    private fun mp(name: String, title: String, note: String, req: List<String>, d: () -> String) = PEntry("mp:$name", title, note, req, d)
    private fun blk(name: String, title: String, note: String, d: () -> String) = PEntry("blk:$name", title, note, emptyList(), d)
    private fun asset(read: (String) -> String, file: String, title: String, note: String, req: List<String>) =
        PEntry("prompts/$file", title, note, req) { read("prompts/$file") }

    fun groups(read: (String) -> String): List<PGroup> = listOf(
        PGroup("🧱 البلوكات اللي بتتضاف على كل قالب", "بتتحط بعد القالب الأساسي في وضع المفاتيح 1 (وبتتضاف للقوالب كلها).", listOf(
            blk("SPLIT_BLOCK", "تقسيم الجمل عند الوقفات", "") { PromptBuilder.SPLIT_BLOCK },
            blk("EXTRA_BLOCK", "التغطية الكاملة · الصوت الخافت · فصل المتحدثين · جودة الترجمة · المراجعة", "") { PromptBuilder.EXTRA_BLOCK },
            blk("SPEAKER_NAME_BLOCK", "اسم المتكلم", "بيتضاف لما «اسم المتكلم» شغّال") { PromptBuilder.SPEAKER_NAME_BLOCK },
            blk("TERM_BLOCK", "شرح المصطلحات الغريبة", "") { PromptBuilder.TERM_BLOCK },
            blk("SOUND_BLOCK", "الأصوات الخلفية (is_sound)", "بيتضاف لما «التقاط الأصوات الخلفية» شغّال") { PromptBuilder.SOUND_BLOCK },
            blk("NO_INVENT_BLOCK", "ممنوع الاختلاق", "") { PromptBuilder.NO_INVENT_BLOCK },
            blk("HOLE_BLOCK", "إعادة طلب مقطع قصير (ثغرة)", "") { PromptBuilder.HOLE_BLOCK },
            blk("HOLE_NEAR_BLOCK", "إعادة طلب مقطع قصير جنب كلام", "") { PromptBuilder.HOLE_NEAR_BLOCK })),
        PGroup("🌐 الوضع 2 — برومبت إنجليزي كامل (صوت ← ترجمة)", "بيتبعت في وضع المفاتيح 2. بيتركّب من القطع دي بالترتيب.", listOf(
            mp("MAIN_EN", "الأساسي (المهام)", "", listOf("{{TARGET}}", "{{STYLE}}")) { ModePrompts.MAIN_EN },
            mp("TIMING_EN", "قواعد التوقيت", "", emptyList()) { ModePrompts.TIMING_EN },
            mp("GENDER_EN", "قواعد الجنس والمخاطَب", "", emptyList()) { ModePrompts.GENDER_EN },
            mp("RULES_EN", "التقسيم والتغطية", "", emptyList()) { ModePrompts.RULES_EN },
            mp("QUALITY_EN", "جودة الترجمة", "", emptyList()) { ModePrompts.QUALITY_EN },
            mp("REVIEW_EN", "المراجعة قبل الرد", "بيتستخدم كمان في برومبت التفريغ (وضع 3/4)", emptyList()) { ModePrompts.REVIEW_EN },
            mp("NO_INVENT_EN", "ممنوع الاختلاق", "بيتستخدم كمان في برومبت التفريغ", emptyList()) { ModePrompts.NO_INVENT_EN },
            mp("SOUND_EN", "الأصوات الخلفية", "", emptyList()) { ModePrompts.SOUND_EN },
            mp("TERMS_EN", "شرح المصطلحات", "", emptyList()) { ModePrompts.TERMS_EN },
            mp("SPEAKER_EN", "اسم المتكلم", "بيتستخدم كمان في برومبت التفريغ", emptyList()) { ModePrompts.SPEAKER_EN },
            mp("CTX_EN", "سياق الحوار السابق", "", listOf("{{PREV}}")) { ModePrompts.CTX_EN },
            mp("HOLE_EN", "إعادة طلب مقطع قصير (ثغرة)", "بيتستخدم كمان في التفريغ", emptyList()) { ModePrompts.HOLE_EN },
            mp("HOLE_NEAR_EN", "إعادة طلب مقطع قصير جنب كلام", "بيتستخدم كمان في التفريغ", emptyList()) { ModePrompts.HOLE_NEAR_EN })),
        PGroup("📝 الوضعين 3 و4 — تفريغ ثم ترجمة (والأوفلاين)", "التفريغ: صوت ← نص أصلي. الترجمة: نص ← عربي (دفعات في وضع 3، وبالسياق الكامل في وضع 4 والأوفلاين).", listOf(
            mp("TRANSCRIBE_MAIN", "التفريغ — الأساسي", "", emptyList()) { ModePrompts.TRANSCRIBE_MAIN },
            mp("TRANSCRIBE_RULES", "التفريغ — التقسيم والتغطية", "", emptyList()) { ModePrompts.TRANSCRIBE_RULES },
            mp("TRANSLATE_MAIN", "الترجمة — الأساسي", "بيتستخدم في وضع 3 و4", listOf("{{TARGET}}", "{{STYLE}}")) { ModePrompts.TRANSLATE_MAIN },
            mp("CONTEXT_RULES", "الترجمة السياقية (وضع 4 والأوفلاين)", "", emptyList()) { ModePrompts.CONTEXT_RULES })),
        PGroup("🛠 المهام الجانبية", "اللي بيشتغل بعد الباتشات: تحويل اللهجة · التنقيح · الشخصيات · الضمائر · قالب البرومبت.", listOf(
            PEntry("eng:conv", "تحويل اللهجة", "من الفصحى للهجة المختارة. الأماكن: {{TGT}} اللهجة · {{DIALECT}} قواعدها · {{TONE}} الأسلوب · {{LIST}} الجمل", listOf("{{LIST}}", "{{TGT}}")) { EnginePrompts.CONV },
            PEntry("eng:refine", "التنقيح (الجزئي والنهائي)", "مراجعة الترجمة بالسياق. الأماكن: {{ARR}} الجمل · {{ROSTER}} الشخصيات · {{LANG}} · {{STYLE}}", listOf("{{ARR}}")) { EnginePrompts.REFINE },
            asset(read, "analyze_chars.txt", "تحليل الشخصيات", "الجدول التلقائي للشخصيات والمصطلحات", listOf("§DIALOGUE§")),
            asset(read, "pronoun_fix.txt", "تصحيح الضمائر", "", listOf("§TABLE§", "§LIST§")),
            asset(read, "tpl_translate.txt", "ترجمة قالب البرومبت للغات التانية", "وضع 1 بس", listOf("§LANG§", "§TEXT§")),
            asset(read, "ctx.txt", "سياق الباتش السابق (قوالب وضع 1)", "", listOf("§PREV§")),
            asset(read, "roster_base.txt", "قاعدة المفردات المرتبطة بالجنس", "بتتحط قبل جدول الشخصيات", emptyList()),
            asset(read, "roster_chars.txt", "جدول الشخصيات الثابت", "", listOf("{{LINES}}")),
            asset(read, "roster_gloss.txt", "المصطلحات الثابتة", "لازم يفضل فيه السطر «- §T§: §NOTE§» زي ما هو", listOf("- §T§: §NOTE§"))))
    )
}
