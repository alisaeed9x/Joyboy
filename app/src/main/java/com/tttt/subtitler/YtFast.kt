package com.tttt.subtitler

/**
 * (v1103 Y2) غلاف رفيع: يوتيوب القديم (YtProUi/YtWatch/YtChannel/YtThumbBtns) بينده YtFast.warm/video/audio —
 * جلب الروابط بقى من محرك MeeTube (MtYt)، فالغلاف بس بيوصّل. طبقة السباق القديمة (InnerTube + NewPipe) فضلت ممسوحة.
 */
object YtFast {
    fun clearCache() { MtYt.clearCache() }
    fun warm(id: String) { MtYt.warm(id) }
    fun video(id: String, maxH: Int, fresh: Boolean = false): MtYt.Got? = MtYt.video(id, maxH, fresh)
    fun audio(id: String, maxH: Int): YtPick? = MtYt.audio(id, maxH)
    fun warmOnTouch(v: android.view.View, id: () -> String) { MtYt.warmOnTouch(v, id) }
}
