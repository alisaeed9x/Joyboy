package com.tttt.subtitler

import android.content.Context
import java.io.File

/**
 * (v995 — المرحلة 1) «الاستوديو»: سجل أنواع المحتوى + ربط التفريغ بالترجمة وملف SRT.
 * طبقتين زي ما البرنامج بيعمل في باقي الأماكن:
 *  • طبقة خام: Lecture.Doc.segs زي ما اتفرّغت من الصوت (start/end/text/chunk/spk) — دي أغلى حاجة ومتتعدّلش.
 *  • طبقة عرض: بتتولّد من الخام من غير ما نعيد التفريغ الصوتي — displaySegs() للنص المعروض،
 *    وtranslateToSrt() للترجمة، وorganize/summarize/analyze للنصوص المشتقة.
 * تغيير النوع يدويًا (setType) بيغيّر طبقة العرض بس — الصوت مبيتفرّغش من جديد.
 * المرحلة 1 بتوصل lecture + subs لآخر الطريق. call/show/meeting/audio ليهم سجل وبرومبت بس،
 * والشاشات بتاعتهم بتيجي في المراحل الجاية.
 * ملحوظة: كل البرومبتات هنا JSON فقط وحرارة منخفضة (0.1) زي transcribePrompt في Lecture.kt.
 * لازم translateToSrt تتنده من خيط خلفي (شبكة + كتابة ملفات).
 */
object Studio {

    // مساحة أسماء ترجمات المحاضرات في مكتبة الترجمات (TrLib/Recents/Store).
    // lec:<lectureId> — مختلفة عن yt: و L: و f: الموجودين، ومستقرة حتى لو اتغيّر عنوان المحاضرة.
    const val LEC_PREFIX = "lec:"

    fun lecId(d: Lecture.Doc): String = LEC_PREFIX + d.id

    // ترجمات المحاضرات مالهاش «استكمال في الخلفية» من TrLib.startBg:
    // المصدر تفريغ نصي مش فيديو، وBgTranslate مبيعرفش يكمّل من lec: — فالواجهة متعرضش استكمال ليها.
    fun isLectureId(id: String): Boolean = id.startsWith(LEC_PREFIX)
    fun canResume(id: String): Boolean = !isLectureId(id)

    // ===================== سجل الأنواع =====================
    enum class TxType(
        val code: String,
        val label: String,
        val emoji: String,
        // اسم شاشة العرض (اسم الملف/الكائن اللي بيعرض النوع — للمرجع، مش كلاس فعلي هنا)
        val screen: String,
        val outputShape: String,
        val summaryShape: String
    ) {
        LECTURE(
            "lecture", "محاضرة", "🎓", "LectureUi",
            "ملف محاضرة (تفريغ حرفي + تنظيم + تلخيص)",
            "عناوين + نقط + مصطلحات"
        ),
        CALL(
            "call", "مكالمة مسجلة", "📞", "CallChatUi",
            "صفحة شات (فقاعة لكل طرف، نص كامل غير ملخّص)",
            "الموضوع + القرارات + المواعيد + مين وعد بإيه"
        ),
        SUBS(
            "subs", "ترجمة فيديو", "💬", "TrLib",
            "ملف SRT بالتوقيت (يظهر في مكتبة الترجمات 📚)",
            "ملخص سريع للحوار"
        ),
        SHOW(
            "show", "مسلسل / فيلم", "🎬", "TrLib",
            "ملف SRT + قاموس أسماء شخصيات ثابت",
            "أحداث + شخصيات + تقلبات"
        ),
        MEETING(
            "meeting", "اجتماع", "🤝", "MeetingUi",
            "محضر (قرارات / مهام / مواعيد)",
            "قرارات + مهام + مين مسؤول عن إيه"
        ),
        AUDIO(
            "audio", "صوت عادي", "🎙", "LectureUi",
            "نص مقسّم بالتوقيت",
            "ملخص عام"
        );

        // برومبت التفريغ بتاع النوع ده. lecture بيرجع برومبت Lecture.kt الأصلي حرفيًا (مفيش نسخة تانية منه هنا).
        fun prompt(durSec: Double, clean: Boolean, prev: String): String = when (this) {
            LECTURE -> Lecture.transcribePrompt(durSec, clean, prev, "lecture")
            CALL -> callPrompt(durSec, clean, prev)
            SUBS -> subsPrompt(durSec, clean, prev)
            SHOW -> showPrompt(durSec, clean, prev)
            MEETING -> meetingPrompt(durSec, clean, prev)
            AUDIO -> audioPrompt(durSec, clean, prev)
        }

        // برومبت المكالمة: متحدثين A/B (أو C لو فيه تالت)، حرفي، والمقاطعات عناصر منفصلة.
        // النبرة والأطراف بالأسماء والبصمات دي شغل المرحلة 3 — البرومبت ده بيثبّت بس شكل التفريغ.
        private fun callPrompt(durSec: Double, clean: Boolean, prev: String): String = buildString {
            append("أنت مفرّغ صوتي محترف بدقة عالية جدًا، متخصص في المكالمات المسجلة. اسمع المقطع الصوتي (مدته حوالي ${durSec.toInt()} ثانية) بتركيز كامل وفرّغ الكلام المنطوق نصًا حرفيًا بنفس اللغة المنطوقة فعلًا — من غير أي ترجمة ولا تلخيص ولا إضافة من عندك.\n\n")
            append("قواعد صارمة:\n")
            append("- 🔴 المتحدثين: ميّز بين الأصوات من نبرتها وطبقتها وطريقة كلامها: أول صوت = spk=\"A\"، التاني = spk=\"B\"، التالت لو فيه = spk=\"C\". لو الجملة الواحدة اتقالت من صوتين، افصلها لعنصرين.\n")
            append("- 🔴 لو طرف قاطع التاني أو رد عليه في نص جملته، سجّل المقاطعة كعنصر منفصل بتوقيتها. متدمجش كلام طرفين في عنصر واحد أبدًا.\n")
            append("- 🔴 لو المتحدث خلط عربي وإنجليزي في نفس الجملة، اكتب كل جزء بلغته بالظبط زي ما اتقال.\n")
            append("- كل جملة أو وحدة معنى (٣–١٥ ثانية) عنصر منفصل بـ start/end بالثواني نسبةً لبداية المقطع ده، والتوقيت لازم يبقى دقيق.\n")
            append("- تجاهل السكوت والمؤثرات غير الكلامية (خطوات، ضجيج، تنحنح).\n")
            append("- لو الصوت مش واضح اكتب أقرب تفسير منطقي من السياق، ومتخترعش كلام لو مفيش كلام مسموع.\n")
            append("- لو المقطع مفيهوش كلام خالص رجّع segments فاضية.\n")
            if (prev.isNotBlank()) append("\nسياق آخر كلام في المقطع اللي قبله (لتكملة جملة مقطوعة على الحدود وتوحيد كتابة الأسماء بس، متكررهوش): \"$prev\"\n")
            append("\nرجّع JSON فقط بدون شرح ولا markdown:\n")
            append("{\"lang\":\"اللغة الأساسية بالعربي\",\"segments\":[{\"start\":0.0,\"end\":5.2,\"spk\":\"A\",\"text\":\"...\"}]}")
        }

        // برومبت الترجمة (subs): حوار حرفي، متحدثين A/B/C، والتوقيت لازم يطابق الكلام بالظبط لأنه هيبقى SRT.
        // clean مالوش معنى هنا (مفيش كلام إداري في فيلم) فبيتجاهل.
        private fun subsPrompt(durSec: Double, clean: Boolean, prev: String): String = buildString {
            append("أنت مفرّغ صوتي محترف بدقة عالية جدًا، متخصص في الحوارات والأفلام والمسلسلات. اسمع المقطع الصوتي (مدته حوالي ${durSec.toInt()} ثانية) بتركيز كامل وفرّغ الكلام المنطوق نصًا حرفيًا بنفس اللغة المنطوقة فعلًا — من غير أي ترجمة ولا تلخيص ولا إضافة من عندك.\n\n")
            append("قواعد صارمة:\n")
            append("- 🔴 المتحدثين: ميّز بين الأصوات من نبرتها وطبقتها وطريقة كلامها: أول صوت = spk=\"A\"، التاني = spk=\"B\"، التالت = spk=\"C\" (وهكذا). لو صوت واحد بس بيتكلم طول المقطع سيب spk فاضي. لو الجملة الواحدة اتقالت من صوتين، افصلها لعنصرين.\n")
            append("- 🔴 لو المتحدث خلط عربي وإنجليزي في نفس الجملة، اكتب كل جزء بلغته بالظبط زي ما اتقال.\n")
            append("- كل جملة أو وحدة معنى (٣–١٥ ثانية) عنصر منفصل بـ start/end بالثواني نسبةً لبداية المقطع ده، والتوقيت لازم يبقى دقيق — التوقيت ده هيستعمل كترجمة (SRT) فلازم يطابق الكلام بالظبط.\n")
            append("- وصف صوت واضح (ضحك، موسيقى، بكاء) اكتبه بين قوسين مربعين زي [ضحك] لو مسموع بوضوح، غير كده تجاهل السكوت والمؤثرات غير الكلامية.\n")
            append("- لو الصوت مش واضح اكتب أقرب تفسير منطقي من السياق، ومتخترعش كلام لو مفيش كلام مسموع.\n")
            append("- لو المقطع مفيهوش كلام خالص رجّع segments فاضية.\n")
            if (prev.isNotBlank()) append("\nسياق آخر كلام في المقطع اللي قبله (لتكملة جملة مقطوعة على الحدود وتوحيد كتابة الأسماء بس، متكررهوش): \"$prev\"\n")
            append("\nرجّع JSON فقط بدون شرح ولا markdown:\n")
            append("{\"lang\":\"اللغة الأساسية بالعربي\",\"segments\":[{\"start\":0.0,\"end\":5.2,\"spk\":\"A\",\"text\":\"...\"}]}")
        }

        // برومبت المسلسل/الفيلم: زي subs، مع تثبيت كتابة أسماء الشخصيات زي ما اتقالت.
        private fun showPrompt(durSec: Double, clean: Boolean, prev: String): String = buildString {
            append("أنت مفرّغ صوتي محترف بدقة عالية جدًا، متخصص في المسلسلات والأفلام. اسمع المقطع الصوتي (مدته حوالي ${durSec.toInt()} ثانية) بتركيز كامل وفرّغ الكلام المنطوق نصًا حرفيًا بنفس اللغة المنطوقة فعلًا — من غير أي ترجمة ولا تلخيص ولا إضافة من عندك.\n\n")
            append("قواعد صارمة:\n")
            append("- 🔴 المتحدثين: ميّز بين الأصوات من نبرتها وطبقتها وطريقة كلامها: أول صوت = spk=\"A\"، التاني = spk=\"B\"، التالت = spk=\"C\" (وهكذا). لو صوت واحد بس بيتكلم طول المقطع سيب spk فاضي. لو الجملة الواحدة اتقالت من صوتين، افصلها لعنصرين.\n")
            append("- 🔴 أسماء الشخصيات: لو اتقال اسم شخصية بوضوح في الحوار اكتبه زي ما اتقال بالظبط من غير تغيير ولا اختصار، وثبّت نفس الكتابة كل مرة يتقال فيها.\n")
            append("- 🔴 لو المتحدث خلط عربي وإنجليزي في نفس الجملة، اكتب كل جزء بلغته بالظبط زي ما اتقال.\n")
            append("- كل جملة أو وحدة معنى (٣–١٥ ثانية) عنصر منفصل بـ start/end بالثواني نسبةً لبداية المقطع ده، والتوقيت لازم يبقى دقيق — التوقيت ده هيستعمل كترجمة (SRT) فلازم يطابق الكلام بالظبط.\n")
            append("- وصف صوت واضح (ضحك، موسيقى، بكاء) اكتبه بين قوسين مربعين زي [ضحك] لو مسموع بوضوح، غير كده تجاهل السكوت والمؤثرات غير الكلامية.\n")
            append("- لو الصوت مش واضح اكتب أقرب تفسير منطقي من السياق، ومتخترعش كلام لو مفيش كلام مسموع.\n")
            append("- لو المقطع مفيهوش كلام خالص رجّع segments فاضية.\n")
            if (prev.isNotBlank()) append("\nسياق آخر كلام في المقطع اللي قبله (لتكملة جملة مقطوعة على الحدود وتوحيد كتابة الأسماء بس، متكررهوش): \"$prev\"\n")
            append("\nرجّع JSON فقط بدون شرح ولا markdown:\n")
            append("{\"lang\":\"اللغة الأساسية بالعربي\",\"segments\":[{\"start\":0.0,\"end\":5.2,\"spk\":\"A\",\"text\":\"...\"}]}")
        }

        // برومبت الاجتماع: حرفي، متحدثين A/B/C، والقرارات والمهام مش في التفريغ (دي من التلخيص بعدين).
        private fun meetingPrompt(durSec: Double, clean: Boolean, prev: String): String = buildString {
            append("أنت مفرّغ صوتي محترف بدقة عالية جدًا، متخصص في الاجتماعات. اسمع المقطع الصوتي (مدته حوالي ${durSec.toInt()} ثانية) بتركيز كامل وفرّغ الكلام المنطوق نصًا حرفيًا بنفس اللغة المنطوقة فعلًا — من غير أي ترجمة ولا تلخيص ولا إضافة من عندك.\n\n")
            append("قواعد صارمة:\n")
            append("- 🔴 المتحدثين: ميّز بين الأصوات من نبرتها وطبقتها وطريقة كلامها: أول صوت = spk=\"A\"، التاني = spk=\"B\"، التالت = spk=\"C\" (وهكذا). لو الجملة الواحدة اتقالت من صوتين، افصلها لعنصرين.\n")
            append("- 🔴 لو المتحدث خلط عربي وإنجليزي في نفس الجملة، اكتب كل جزء بلغته بالظبط زي ما اتقال.\n")
            append("- كل جملة أو وحدة معنى (٣–١٥ ثانية) عنصر منفصل بـ start/end بالثواني نسبةً لبداية المقطع ده، والتوقيت لازم يبقى دقيق.\n")
            append("- تجاهل السكوت والمؤثرات غير الكلامية (خطوات، ضجيج، تنحنح).\n")
            append("- لو الصوت مش واضح اكتب أقرب تفسير منطقي من السياق، ومتخترعش كلام لو مفيش كلام مسموع.\n")
            append("- لو المقطع مفيهوش كلام خالص رجّع segments فاضية.\n")
            if (prev.isNotBlank()) append("\nسياق آخر كلام في المقطع اللي قبله (لتكملة جملة مقطوعة على الحدود بس، متكررهوش): \"$prev\"\n")
            append("\nرجّع JSON فقط بدون شرح ولا markdown:\n")
            append("{\"lang\":\"اللغة الأساسية بالعربي\",\"segments\":[{\"start\":0.0,\"end\":5.2,\"spk\":\"A\",\"text\":\"...\"}]}")
        }

        // برومبت الصوت العادي: نص حرفي مقسّم بالتوقيت، من غير أي افتراض عن نوع المحتوى.
        private fun audioPrompt(durSec: Double, clean: Boolean, prev: String): String = buildString {
            append("أنت مفرّغ صوتي محترف بدقة عالية جدًا. اسمع المقطع الصوتي (مدته حوالي ${durSec.toInt()} ثانية) بتركيز كامل وفرّغ الكلام المنطوق نصًا حرفيًا بنفس اللغة المنطوقة فعلًا — من غير أي ترجمة ولا تلخيص ولا إضافة من عندك.\n\n")
            append("قواعد صارمة:\n")
            append("- المتحدثين: لو فيه أكتر من صوت واضح ميّز بينهم من النبرة والطبقة: أول صوت = spk=\"A\"، التاني = spk=\"B\" (وهكذا). لو صوت واحد بس سيب spk فاضي.\n")
            append("- لو المتحدث خلط عربي وإنجليزي في نفس الجملة، اكتب كل جزء بلغته بالظبط زي ما اتقال.\n")
            append("- كل جملة أو وحدة معنى (٣–١٥ ثانية) عنصر منفصل بـ start/end بالثواني نسبةً لبداية المقطع ده، والتوقيت لازم يبقى دقيق.\n")
            append("- تجاهل السكوت والمؤثرات غير الكلامية (خطوات، ضجيج، تنحنح).\n")
            append("- لو الصوت مش واضح اكتب أقرب تفسير منطقي من السياق، ومتخترعش كلام لو مفيش كلام مسموع.\n")
            append("- لو المقطع مفيهوش كلام خالص رجّع segments فاضية.\n")
            if (prev.isNotBlank()) append("\nسياق آخر كلام في المقطع اللي قبله (لتكملة جملة مقطوعة على الحدود بس، متكررهوش): \"$prev\"\n")
            append("\nرجّع JSON فقط بدون شرح ولا markdown:\n")
            append("{\"lang\":\"اللغة الأساسية بالعربي\",\"segments\":[{\"start\":0.0,\"end\":5.2,\"spk\":\"A\",\"text\":\"...\"}]}")
        }

        companion object {
            // كود مش معروف أو فاضي = محاضرة (توافق خلفي مع المحاضرات القديمة اللي type بتاعها "").
            fun fromCode(code: String): TxType = values().firstOrNull { it.code == code } ?: LECTURE
            val all: List<TxType> get() = values().toList()
        }
    }

    // برومبت التفريغ حسب الكود — ده اللي Lecture.transcribePrompt بيندهه للأنواع غير المحاضرة.
    fun promptFor(code: String, durSec: Double, clean: Boolean, prev: String): String =
        TxType.fromCode(code).prompt(durSec, clean, prev)

    fun typeOf(d: Lecture.Doc): TxType = TxType.fromCode(d.type)

    // العنوان اللي يتعرض فوق المحتوى: العنوان التلقائي بيظهر بس لو مفيش عنوان حقيقي
    // (فاضي، أو الافتراضي "محاضرة"، أو اللينك نفسه — نفس قاعدة openSource في Lecture.kt).
    // عنوان المستخدم أو عنوان المصدر مبيتغطاش أبدًا بالتلقائي.
    fun displayTitle(d: Lecture.Doc): String {
        val t = d.title.trim()
        val generic = t.isEmpty() || t == "محاضرة" || t == d.link
        return if (generic && d.autoTitle.isNotBlank()) d.autoTitle else d.title
    }

    // ===================== طبقة العرض =====================
    // النص المعروض حسب النوع — مشتق من الخام (d.segs) من غير إعادة تفريغ.
    // محاضرة: زي ما هو. subs: سطور حوار (جمل ورا بعض لنفس المتحدث بتتدمج لو الفرق بينها ≤ 1.5 ثانية).
    // call/show/meeting/audio: زي الخام لحد ما شاشاتهم تيجي في المراحل الجاية.
    fun displaySegs(d: Lecture.Doc): List<Lecture.Seg> {
        val segs = synchronized(d) { d.segs.sortedBy { it.start } }
        return if (typeOf(d) == TxType.SUBS) mergeDialogue(segs) else segs
    }

    // دمج جمل متتالية لنفس المتحدث في سطر حوار واحد (للعرض بس — الخام في d.segs مبيتغيّرش).
    private fun mergeDialogue(segs: List<Lecture.Seg>): List<Lecture.Seg> {
        if (segs.size < 2) return segs
        val out = ArrayList<Lecture.Seg>()
        var cur: Lecture.Seg? = null
        for (s in segs) {
            val c = cur
            if (c != null && c.spk == s.spk && c.spk.isNotBlank() && s.start - c.end <= 1.5) {
                cur = Lecture.Seg(c.start, s.end, c.text + " " + s.text, c.chunk, c.spk)
            } else {
                if (c != null) out.add(c)
                cur = s
            }
        }
        if (cur != null) out.add(cur)
        return out
    }

    // تغيير النوع يدويًا: بيحفظ النوع الجديد، ولو النوع اتغيّر فعلًا بيمسح المشتقات النصية القديمة
    // (تنظيم/تلخيص/تحليل) لأنها اتبنت على شكل النوع القديم — وبتتولّد من جديد من الخام لما المستخدم يطلبها.
    // الصوت مبيتفرّغش من جديد أبدًا هنا.
    fun setType(ctx: Context, d: Lecture.Doc, type: String, auto: Boolean, conf: Double = 0.0): Lecture.Doc {
        val t = TxType.fromCode(type)
        synchronized(d) {
            val old = d.type.ifBlank { "lecture" }
            d.type = t.code
            d.typeAuto = auto
            d.typeConf = conf.coerceIn(0.0, 1.0)
            if (old != t.code) {
                d.organized = ""
                d.organizedWeb = false
                d.summary = ""
                d.summaryWeb = false
                d.summaryLen = "mid"
                d.analysis = ""
                d.analysisN = 0
                d.analysisHash = ""
            }
        }
        Lecture.save(ctx, d)
        return d
    }

    // ===================== التفريغ ← ترجمة SRT =====================
    class SrtResult(val srtPath: String?, val libId: String, val subs: Int, val failed: Int, val error: String) {
        val ok: Boolean get() = error.isEmpty() && subs > 0
    }

    // بياخد تفريغ جاهز (Doc.segs) ويحوّله لترجمة SRT بمحرك الترجمة الحالي (SubFileTr.translate):
    // Seg ← Cue (1:1، من غير chunk/spk) ← ترجمة باللهجة والأسلوب من الإعدادات ← List<Sub>
    // ← ملف SRT جنب الفيديو (SrtWriter.save، لو فيه ملف محلي) + تسجيل في مكتبة الترجمات 📚
    // (Store.save + Recents.saveProgress + TrLib.stamp) بنفس نمط باقي الترجمات.
    // eng: "gemini" أو "google" — فاضي = طريقة الترجمة من الإعدادات (TrWizard.method()).
    // الجمل اللي فشلت ترجمتها بتفضل بنصها الأصلي (زي ما المشغّل بيعمل) وعددها في failed.
    // لازم تتنده من خيط خلفي.
    fun translateToSrt(ctx: Context, d: Lecture.Doc, report: (String) -> Unit, cancel: () -> Boolean = { false }, eng: String? = null): SrtResult {
        val libId = lecId(d)
        val segs = synchronized(d) { d.segs.sortedBy { it.start } }
        if (segs.isEmpty()) return SrtResult(null, libId, 0, 0, "مفيش تفريغ لسه — فرّغ الأول")
        val cues = segs.map { SubFileTr.Cue(it.start, it.end, it.text) }
        val engine = if (eng == "gemini" || eng == "google") eng else TrWizard.method()
        val out = try {
            SubFileTr.translate(cues, engine, report, cancel)
        } catch (e: Throwable) {
            LogStore.err("Studio:tr", e)
            return SrtResult(null, libId, 0, cues.size, (e.message ?: e.javaClass.simpleName).take(100))
        }
        if (cancel()) return SrtResult(null, libId, 0, out.failed, "اتوقفت")
        val subs = cues.mapIndexed { i, c ->
            val tr = out.texts.getOrNull(i) ?: ""
            Sub(c.start, c.end, c.text, if (tr.isBlank()) c.text else tr, "male", "unknown", "none", emptyList(), emptyList(), false, false)
        }
        val title = displayTitle(d).ifBlank { "محاضرة" }
        val srtPath = try {
            SrtWriter.save(ctx, d.uri.ifBlank { null }, subs, 0L)
        } catch (_: Throwable) {
            null
        }
        try {
            val done = subs.map { doubleArrayOf(it.start, it.end) }
            val saved = Saved(60, d.lang, true, subs, done, emptyList(), emptyList(), emptyList(), emptyMap(), 0.0, 0)
            Store(File(ctx.filesDir, "progress"), Store.keyFor(libId)).save(saved)
        } catch (e: Throwable) {
            LogStore.err("Studio:store", e)
        }
        try {
            Recents.saveProgress(ctx, libId, title, d.link, d.uri, d.durSec, subs.size, d.durSec)
        } catch (e: Throwable) {
            LogStore.err("Studio:recents", e)
        }
        try {
            val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
            val engLabel = if (engine == "gemini") "جيميناي " + model.removePrefix("gemini-").removeSuffix("-latest") else "ترجمة جوجل"
            TrLib.stamp(ctx, libId, title, SubFileTr.effLang(), Cfg.str("tone_style", ""), Cfg.str("tone_strength", ""), model, engLabel)
        } catch (e: Throwable) {
            LogStore.err("Studio:stamp", e)
        }
        return SrtResult(srtPath, libId, subs.size, out.failed, out.error)
    }
}
