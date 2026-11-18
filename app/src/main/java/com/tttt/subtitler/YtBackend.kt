package com.tttt.subtitler

import org.schabi.newpipe.extractor.Page

/**
 * (v1103 Y2) واجهة الباك-إند البديل بتاع YtNp (كانت v793). النسخة القديمة (InnerTube عبر YtExtract) اتمسحت v889 —
 * فمفيش بديل غير NewPipe: alt() بترجّع null دايمًا وYtNp بيكمّل بـNewPipe. الواجهة فضلت عشان YtNp يفضل كما هو.
 */
interface YtBackend {
    val id: String
    val label: String
    fun channel(url: String): NpChan? = null
    fun channelFull(url: String): NpChanFull? = null
    fun searchFirst(q: String, filter: String): YtRes? = null
    fun searchMore(q: String, filter: String, p: Page): YtRes? = null
}

object YtBackends {
    fun alt(): YtBackend? = null
    fun shortsAsVideos(): Boolean = Cfg.str("yt_shorts_normal", "1") != "0"
}
