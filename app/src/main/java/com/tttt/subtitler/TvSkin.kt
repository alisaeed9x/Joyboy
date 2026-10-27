package com.tttt.subtitler

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.widget.LinearLayout

/** الأزرار الملونة في الريموت */
enum class TvColorKey { RED, GREEN, YELLOW, BLUE }

/**
 * (v597 م1) اللي بيقدّمه TvActivity لأي سكِن — السكِن مابيلمسش TvActivity مباشرة، بيكلّمه من هنا بس.
 * (البيانات والمنطق كلهم في TvActivity/VideoLib/Cfg؛ السكِن بيرسم بس.)
 */
interface TvSkinHost {
    val activity: Activity
    val theme: Theme
    val ui: Ui
    /** حاوية محتوى الصفحة (اللي بيتمسح ويتملا عند كل تنقّل) */
    val content: LinearLayout
    /** صورة مصغّرة لفيديو — الـcallback بيتنده على UI thread ومش بيتنده لو الشاشة اتقفلت */
    fun loadThumb(v: VideoItem, onBitmap: (Bitmap) -> Unit)
    fun isSdFolder(f: FolderItem): Boolean
    fun openFolder(f: FolderItem)
    fun playVideo(v: VideoItem)
    /** قايمة سياق (دوسة OK الطويلة / زرار Menu) */
    fun menu(title: String, items: List<Pair<String, () -> Unit>>)
    /** ارسم الصفحة اللي واقف فيها من جديد (بعد تغيير سكِن/إعداد) */
    fun refresh()
}

/**
 * (v597 م1) عقد السكِن. كل سكِن بيطبّق نفس الخانات (slots):
 *  Home · FolderView · Settings · Notifications · Themes · Search · ContextMenu + مكوّن كارت الفولدر + الأزرار الملونة.
 *
 * قاعدة الخانات: الدالة بترجّع true لو السكِن رسم الشاشة بنفسه، وfalse = «استخدم الرسم المدمج الحالي».
 * سكِن «كلاسيك» كل خاناته false فبيشتغل بالرسم الحالي بالظبط (من غير أي تغيير سلوك)، وكل سكِن جديد
 * بياخد على نفسه خانة بخانة. كارت الفولدر الوحيد اللي إجباري (لأن كل السكِنز لازم تعرضه).
 */
interface TvSkin {
    val id: String
    val name: String
    val tokens: TvTokens

    fun home(h: TvSkinHost, focus: Boolean, reuse: Boolean): Boolean = false
    fun folderView(h: TvSkinHost, f: FolderItem): Boolean = false
    fun settings(h: TvSkinHost): Boolean = false
    fun notifications(h: TvSkinHost): Boolean = false
    fun themes(h: TvSkinHost): Boolean = false
    fun search(h: TvSkinHost, query: String): Boolean = false
    fun contextMenu(h: TvSkinHost, title: String, items: List<Pair<String, () -> Unit>>): Boolean = false

    /** كارت فولدر بالحجم المطلوب (قايمة / شبكة صغيرة / شبكة كبيرة / شريط الرئيسية) */
    fun folderCard(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit): View

    /** زرار ملوّن في الريموت (بعد اختصارات المستخدم). true = السكِن استهلكه */
    fun colorKey(h: TvSkinHost, key: TvColorKey): Boolean = false

    fun onSelected(h: TvSkinHost) {}
    fun onDeselected(h: TvSkinHost) {}
}

/**
 * (v597 م1) سجل السكِنز: القايمة · السكِن الحالي (متحفوظ في Cfg «tv_skin») · التبديل الحي.
 * أي سكِن جديد بيتسجّل بـ register() — ومفيش حاجة تانية في الكود بتتغيّر.
 * لو السكِن المحفوظ مش موجود (اتشال مثلًا) بنرجع لـ«كلاسيك».
 */
object TvSkins {
    private val list = ArrayList<TvSkin>().apply { add(TvSkinClassic) }
    private val listeners = ArrayList<() -> Unit>()
    @Volatile private var curSkin: TvSkin? = null

    val all: List<TvSkin> get() = list.toList()

    fun register(s: TvSkin) { synchronized(list) { if (list.none { it.id == s.id }) list.add(s) } }

    fun cur(): TvSkin {
        curSkin?.let { return it }
        val id = try { Cfg.str("tv_skin", "classic") } catch (_: Throwable) { "classic" }
        val s = list.firstOrNull { it.id == id } ?: list[0]
        curSkin = s
        return s
    }

    /** تبديل حي (من غير ريستارت) — بيحفظ الاختيار وينده المستمعين */
    fun set(id: String, host: TvSkinHost? = null) {
        val s = list.firstOrNull { it.id == id } ?: list[0]
        val old = curSkin
        if (old === s) return
        if (host != null) try { old?.onDeselected(host) } catch (_: Throwable) {}
        curSkin = s
        try { Cfg.put("tv_skin", s.id) } catch (_: Throwable) {}
        if (host != null) try { s.onSelected(host) } catch (_: Throwable) {}
        listeners.toList().forEach { try { it() } catch (_: Throwable) {} }
    }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun removeOnChange(l: () -> Unit) { listeners.remove(l) }
}
