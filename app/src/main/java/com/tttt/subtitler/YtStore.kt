package com.tttt.subtitler

/** (v325) تخزين محلي: سجل · محفوظات · اشتراكات (في Cfg، سطر لكل عنصر) */
object YtStore {
    private const val SEP = "\u0001"
    const val HIST = "ytp_hist"; const val SAVED = "ytp_saved"; const val SUBS = "ytp_subs"
    const val DL = "ytp_dl"          // (v337) التحميلات: url = مكان الملف اللي اتحفظ
    private const val APPIDS = "ytp_appids"   // (v337) الفيديوهات اللي اتشغّلت من صفحة يوتيوب (عشان فولدر المصطادة يفصلها عن اللي من المتصفح)
    fun histOn() = Cfg.str("ytp_hist_on", "1") != "0"
    fun markApp(id: String) { if (id.isBlank()) return; val l = Cfg.str(APPIDS, "").split(',').filter { it.isNotBlank() && it != id }.toMutableList(); l.add(0, id); Cfg.put(APPIDS, l.take(600).joinToString(",")) }
    fun appIds(): Set<String> = Cfg.str(APPIDS, "").split(',').filter { it.isNotBlank() }.toHashSet()
    private fun clean(s: String) = s.replace('\n', ' ').replace(SEP, " ")

    fun read(key: String): MutableList<NpItem> = Cfg.str(key, "").lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
        val p = l.split(SEP)
        if (p.size < 7) null else NpItem(p[0], p[1], p[2], p[3], p[4], p[5].toLongOrNull() ?: -1L, 0L, "", p[6].toIntOrNull() ?: 0, p.getOrElse(7) { "" })
    }.toMutableList()

    fun write(key: String, l: List<NpItem>) {
        Cfg.put(key, l.joinToString("\n") { listOf(it.id, clean(it.title), clean(it.channel), it.channelUrl, it.thumb, it.dur.toString(), it.kind.toString(), it.url).joinToString(SEP) })
    }

    private fun same(a: NpItem, b: NpItem) = if (a.kind == 1 || b.kind == 1) a.channelUrl == b.channelUrl else a.id == b.id && a.url == b.url

    fun contains(key: String, x: NpItem) = read(key).any { same(it, x) }

    fun addFront(key: String, x: NpItem, cap: Int) {
        val l = read(key); l.removeAll { same(it, x) }; l.add(0, x)
        write(key, if (l.size > cap) l.subList(0, cap) else l)
    }
    /** بيرجّع true لو بقى موجود بعد التبديل */
    fun toggle(key: String, x: NpItem): Boolean {
        val l = read(key)
        return if (l.removeAll { same(it, x) }) { write(key, l); false } else { l.add(0, x); write(key, l); true }
    }
    fun remove(key: String, x: NpItem) { val l = read(key); if (l.removeAll { same(it, x) }) write(key, l) }

    // (v1291) «مش مهتم»: v:<id> فيديو · c:<قناة> قناة — بتتخبّى من اقتراحات الرئيسية
    private const val NOTINT = "ytp_notint"
    fun notInt(): Set<String> = Cfg.str(NOTINT, "").split(',').filter { it.isNotBlank() }.toHashSet()
    fun hide(tag: String) { val l = notInt().toMutableSet(); l.add(tag); Cfg.put(NOTINT, l.joinToString(",")) }
}
