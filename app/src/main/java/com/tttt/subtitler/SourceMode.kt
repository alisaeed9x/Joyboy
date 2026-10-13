package com.tttt.subtitler

/**
 * (v291) ⓪ مصدر الترجمة — قبل خط الترجمة (① تفريغ ← ② ترجمة ← ③ لهجة):
 *  🎙 صوتي بس (audio)  · 👁 بصري بس (visual) · 🎙+👁 هجين (hybrid)
 * والبصري ليه نوعين (tr_vkind): scene = نصوص الشاشة (الهارد سب بيتتجاهل) · hard = هارد سب.
 * فاضي = ماحدش اختار لسه: السلوك القديم من غير أي إجبار (البريستات هي اللي بتفتح/تقفل البصري).
 * hard_mode (اللي VisualMode.hardMode() بيقراه) بيتكتب من هنا حسب النوع.
 */
object SourceMode {
    const val AUDIO = "audio"; const val VISUAL = "visual"; const val HYBRID = "hybrid"
    fun get(): String = try { Cfg.str("tr_src", "").let { if (it == AUDIO || it == VISUAL || it == HYBRID) it else "" } } catch (_: Throwable) { "" }
    fun kind(): String = try { if (Cfg.str("tr_vkind", "scene") == "hard") "hard" else "scene" } catch (_: Throwable) { "scene" }
    fun visualOn(): Boolean = get() == VISUAL || get() == HYBRID
    fun keepHard(): Boolean = visualOn() && kind() == "hard"
    fun label(s: String = get()): String = when (s) { AUDIO -> "🎙 صوتي بس"; VISUAL -> "👁 بصري بس"; HYBRID -> "🎙+👁 هجين"; else -> "" }
    fun kindLabel(k: String = kind()): String = if (k == "hard") "🔤 هارد سب" else "👁 نصوص الشاشة"
    /** سطر الوصف: «🎯 هجين · 🔤 هارد سب» أو فاضي لو ماحدش اختار */
    fun line(): String { val s = get(); if (s.isEmpty()) return ""; return "🎯 " + label(s) + (if (s != AUDIO) " · " + kindLabel() else "") }
    fun prefix(): String = line().let { if (it.isEmpty()) "" else it + "\n" }
    fun set(src: String, kind: String? = null) { Cfg.put("tr_src", src); if (kind != null) Cfg.put("tr_vkind", kind) }
}
