package com.tttt.subtitler

/**
 * (v1035 — المرحلة 4) سجل واحد لأزرار شيت التفريغ حسب النوع — بدل قايمة مسطّحة واحدة لكل الأنواع.
 * الشيت (StudioUi.openDocSheet) بيرسم من هنا: «الأساسي» ظاهر، و«⋯ المزيد» مطوي. زرار جديد = سطر هنا بس،
 * فمفيش حاجة تتضاف في شاشة وتنقص في التانية. (شاشة العرض LectureUi بتفضل للقراءة: تبويبات + نسخ/مشاركة/متحدثين.)
 */
object DocActions {
    enum class A { PLAY, CHAT, OPEN, COMPLETE, INTEL, TRANSLATE_SRT, LINES, SHARE_TR, QUALITY, CLIP, TR_LIB, TR_SETTINGS, TX_SETTINGS, RESTART }

    /** (أساسي, مزيد) — «🗑 حذف» دايمًا آخر الشيت ومش في القايمتين. */
    class Layout(val primary: List<A>, val more: List<A>)

    fun layout(code: String, hasVideo: Boolean): Layout {
        val play = if (hasVideo) listOf(A.PLAY) else emptyList()
        val clip = if (hasVideo) listOf(A.CLIP) else emptyList()
        return when (code) {
            // ترجمة فيديو / مسلسل: الهدف الأساسي ترجمة + مشاهدة
            "subs", "show" -> Layout(
                play + listOf(A.TRANSLATE_SRT, A.LINES, A.SHARE_TR, A.COMPLETE),
                listOf(A.OPEN, A.QUALITY) + clip + listOf(A.TR_LIB, A.TR_SETTINGS, A.TX_SETTINGS, A.INTEL, A.RESTART)
            )
            // مكالمة: الدردشة هي شاشة العرض (open() بتحوّل ليها — فمفيش «فتح التفريغ» مكرر)
            "call" -> Layout(
                listOf(A.CHAT, A.COMPLETE, A.INTEL),
                listOf(A.TRANSLATE_SRT, A.LINES, A.SHARE_TR, A.TX_SETTINGS, A.TR_SETTINGS, A.RESTART)
            )
            // محاضرة / اجتماع / صوت عادي: قراءة + تنظيم + ذكاء؛ الترجمة والـSRT في «المزيد»
            else -> Layout(
                listOf(A.OPEN, A.COMPLETE, A.INTEL),
                play + listOf(A.TRANSLATE_SRT, A.LINES, A.SHARE_TR, A.QUALITY) + clip + listOf(A.TR_LIB, A.TR_SETTINGS, A.TX_SETTINGS, A.RESTART)
            )
        }
    }

    /** فلتر نوع السجل: (الاسم الظاهر, كود النوع أو "" = الكل). */
    fun historyFilters(): List<Pair<String, String>> =
        listOf("الكل" to "") + Studio.TxType.all.map { (it.emoji + " " + it.label) to it.code }
}
