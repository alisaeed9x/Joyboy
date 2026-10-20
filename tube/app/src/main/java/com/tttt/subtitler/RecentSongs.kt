package com.tttt.subtitler

/** (v295) آخر الأغاني المشغّلة (الأحدث أولًا) — بتتسجل من MusicEngine.play وبتظهر في زرار 🕘 فوق صفحة الموسيقى */
object RecentSongs {
    private const val KEY = "mus_recent"
    private const val MAX = 80

    fun list(): List<String> = Cfg.str(KEY, "").split('\n').filter { it.isNotEmpty() }

    fun add(uri: String) {
        if (uri.isBlank()) return
        val l = ArrayList(list())
        l.remove(uri); l.add(0, uri)
        while (l.size > MAX) l.removeAt(l.size - 1)
        Cfg.put(KEY, l.joinToString("\n"))
    }

    fun clear() = Cfg.put(KEY, "")
}
