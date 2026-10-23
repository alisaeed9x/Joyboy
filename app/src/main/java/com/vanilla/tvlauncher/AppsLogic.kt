package com.vanilla.tvlauncher

/** تطبيق متاح للتشغيل من صفحة التطبيقات (P9). */
data class AppEntry(val pkg: String, val label: String)

/** منطق صفحة التطبيقات النقي: يشيل اللانشر نفسه والمكرر والفاضي، ويرتّب بالاسم (ترتيب طبيعي، من غير حساسية لحالة الحروف). */
object AppsLogic {
    fun clean(apps: List<AppEntry>, selfPkg: String): List<AppEntry> =
        apps.filter { it.pkg.isNotBlank() && it.pkg != selfPkg }
            .distinctBy { it.pkg }
            .map { if (it.label.isBlank()) AppEntry(it.pkg, it.pkg) else it }
            .sortedWith(Comparator { a, b -> LibraryLogic.naturalCompare(a.label, b.label) })
}
