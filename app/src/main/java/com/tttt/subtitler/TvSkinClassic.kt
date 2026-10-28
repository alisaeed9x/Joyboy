package com.tttt.subtitler

import android.view.View

/**
 * (v597 م1) السكِن رقم 0 «كلاسيك» = الواجهة الحالية.
 * كل الخانات بترجّع false (استخدم الرسم المدمج) — فمفيش أي تغيير في السلوك؛ الجديد الوحيد هو كارت الفولدر (م2).
 */
object TvSkinClassic : TvSkin {
    override val id = "classic"
    override val name = "كلاسيك (الحالي)"
    override val themeIds = listOf("mx", "default", "light", "mxd", "eye_sepia")
    override val tokens: TvTokens = TvTokens.CLASSIC

    override fun folderCard(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit): View =
        TvFolderCard.build(h, f, size, onOpen, tokens)
}
