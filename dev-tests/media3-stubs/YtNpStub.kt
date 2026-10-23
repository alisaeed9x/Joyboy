// بديل YtNp.kt لفحص الكومبايل بدون مكتبة NewPipe (الأصل بيتفحص بـ Gradle/Actions)
package com.vanilla.tvlauncher
class YtRes(val items: List<YtItem>, val hasMore: Boolean)
object YtNp { fun searchFirst(q: String): YtRes = throw NotImplementedError(); fun searchMore(q: String, r: YtRes): YtRes = throw NotImplementedError(); fun pick(id: String, maxH: Int): YtStream? = null }
