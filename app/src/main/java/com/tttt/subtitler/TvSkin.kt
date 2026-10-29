package com.tttt.subtitler

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.widget.LinearLayout

/** (v599 م4) صفحات الإعدادات اللي السكِن يقدر يفتحها (من غير ما يعرف TvActivity) */
/** PRESETS is a legacy route redirected to Skin-first; PRESET_MANAGER preserves saved-combination import/export and color-key bindings. */
enum class TvPage { APPEARANCE, THEME, ICON_PACK, LAYOUT, WALLPAPER, SCALE, NOTIFICATIONS, PRESETS, PRESET_MANAGER }

/** (v600 م4) حالات «الفاضي» اللي السكِن يقدر يرسمها بشكله: مفيش صلاحية · مفيش فيديوهات · مفيش إشعارات */
enum class TvEmpty { NO_PERMISSION, NO_VIDEOS, NO_NOTIFICATIONS }

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
    /** (v599 م4) آخر فيديو/لينك اتشغّل (للبانر «كمّل»)، أو null */
    fun lastRecent(): Recent?
    fun playRecent(r: Recent)
    /** مصغّرة الفيديو/اللينك في ImageView (فيديو محلي من الفحص، أو مصغّرة اللينك) */
    fun recentThumbInto(r: Recent, iv: android.widget.ImageView)
    fun open(page: TvPage)
    /** (v600 م4) صفحة يرسمها السكِن بنفسه: بتمسح المحتوى وتصفّر البحث وتطلّع الأنيميشن وتركّز على أول عنصر (أو اللي tag بتاعه skin:first) */
    fun page(build: (LinearLayout) -> Unit)
    /** (v600 م4) غيّر السمة (الألوان) — بيعمل إعادة رسم كاملة */
    fun setTheme(id: String)
    /** (v600 م4) ارجع للرئيسية (نفس سلوك Back) */
    fun goHome()
    /** (v600 م4) اطلب صلاحية الفيديوهات */
    fun requestVideoPermission()
    /** (v625 م4) زي page() بس للرئيسية نفسها: التاب الحالي = الرئيسية (فالرجوع من الفيديو بيحدّثها زي الرئيسية المدمجة) */
    fun homePage(build: (LinearLayout) -> Unit)
    /** (v625 م4) فولدرات الفيديو مرتّبة بإعداد المستخدم، أو null لو الفحص ماخلصش / مفيش صلاحية */
    fun folders(): List<FolderItem>?
    fun hasVideoPermission(): Boolean
    /** (v625 م4) افحص الفيديوهات لو لازم (في الخلفية) وناد onDone على UI thread (حتى لو الفحص فشل أو مفيش صلاحية) */
    fun ensureScan(onDone: () -> Unit)
}

/**
 * (v628) أقسام الرئيسية الجاهزة والمتملّية (سجل · يوتيوب · فولدرات مثبّتة · فيديوهات · تطبيقات + الإعدادات).
 * السكِن بيرتّبها في هيكل بتاعه (تابات · سايدبار · لوحات) من غير ما يفقد أي وظيفة.
 * order = ترتيب المستخدم بعد استبعاد المخفي · views = القسم بالمعرّف (hist/yt/pin/vids/apps).
 */
class TvHomeSections(val order: List<String>, val views: Map<String, View>, val settingsTitle: View, val settings: View,
    /** (v630) بيانات التطبيقات الخام — كل سكِن يرسم تطبيقاته بشكله (بانر · مربع · دوك · أيقونة كبيرة) بدل الدواير المشتركة */
    val appItems: List<TvAppItem> = emptyList(),
    /** (v635) قايمة يوتيوب العمودية (3 فيديوهات تحت بعض، والباقي بيتحمّل لما توصل لآخرها) — للسكنات اللي يوتيوب فيها تاب لوحده */
    val ytList: (() -> View)? = null)

/** (v630) تطبيق واحد في الرئيسية: الاسم + الأيقونة (Bitmap) + لون الهالة + الفتح + الضغطة الطويلة */
class TvAppItem(val label: String, val icon: Bitmap?, val glow: Int?, val onClick: () -> Unit, val onLong: (() -> Unit)? = null)

/**
 * (v597 م1) عقد السكِن. كل سكِن بيطبّق نفس الخانات (slots):
 *  Home · FolderView · Settings · Notifications · Skin-first chooser · Search · ContextMenu + مكوّن كارت الفولدر + الأزرار الملونة.
 *
 * قاعدة الخانات: الدالة بترجّع true لو السكِن رسم الشاشة بنفسه، وfalse = «استخدم الرسم المدمج الحالي».
 * سكِن «كلاسيك» كل خاناته false فبيشتغل بالرسم الحالي بالظبط (من غير أي تغيير سلوك)، وكل سكِن جديد
 * بياخد على نفسه خانة بخانة. كارت الفولدر الوحيد اللي إجباري (لأن كل السكِنز لازم تعرضه).
 */
interface TvSkin {
    val id: String
    val name: String
    val tokens: TvTokens
    /** (v606) السمات المناسبة للسكِن (بتظهر ⭐ فوق في «التصميم ← السمة»). الأولى = الافتراضية. فاضية = كل السمات */
    val themeIds: List<String> get() = emptyList()

    fun home(h: TvSkinHost, focus: Boolean, reuse: Boolean): Boolean = false
    fun folderView(h: TvSkinHost, f: FolderItem): Boolean = false
    fun settings(h: TvSkinHost): Boolean = false
    fun notifications(h: TvSkinHost): Boolean = false
    /** Any legacy entry labelled Themes must go through the unified Skin-first chooser. */
    fun themes(h: TvSkinHost): Boolean { h.open(TvPage.LAYOUT); return true }
    fun search(h: TvSkinHost, query: String): Boolean = false
    fun contextMenu(h: TvSkinHost, title: String, items: List<Pair<String, () -> Unit>>): Boolean = false

    /** كارت فولدر بالحجم المطلوب (قايمة / شبكة صغيرة / شبكة كبيرة / شريط الرئيسية) */
    fun folderCard(h: TvSkinHost, f: FolderItem, size: TvFolderSize, onOpen: () -> Unit): View

    /** زرار ملوّن في الريموت (بعد اختصارات المستخدم). true = السكِن استهلكه */
    fun colorKey(h: TvSkinHost, key: TvColorKey): Boolean = false

    /** (v599 م4) عنصر بيتحط فوق صفوف الرئيسية (بانر «كمّل» مثلًا) — null = مفيش. بيتنده تاني كل ما الرئيسية تتفتح (التقدّم بيتحدّث) */
    fun homeHeader(h: TvSkinHost): View? = null

    /** (v628) رتّب أقسام الرئيسية بهيكل السكِن. بترجّع الـViews اللي تتضاف للصفحة بالترتيب، أو null = الترتيب العمودي المدمج */
    fun arrangeHome(h: TvSkinHost, s: TvHomeSections): List<View>? = null

    /** (v599 م4) زرار Menu وهو واقف على عنصر مالوش قايمة (مش دوسة طويلة). true = السكِن استهلكه */
    fun menuKey(h: TvSkinHost): Boolean = false

    /** (v600 م4) هيكل تحميل (skeleton) بيتعرض مكان صف الفيديوهات لحد ما الفحص يخلص — null = النص العادي */
    fun homeSkeleton(h: TvSkinHost): View? = null

    /** (v600 م4) شاشة «الفاضي» بشكل السكِن — null = الرسالة العادية */
    fun emptyState(h: TvSkinHost, kind: TvEmpty): View? = null

    fun onSelected(h: TvSkinHost) {}
    fun onDeselected(h: TvSkinHost) {}
}

/**
 * (v597 م1) سجل السكِنز: القايمة · السكِن الحالي (متحفوظ في Cfg «tv_skin») · التبديل الحي.
 * أي سكِن جديد بيتسجّل بـ register() — ومفيش حاجة تانية في الكود بتتغيّر.
 * لو السكِن المحفوظ مش موجود بنرجع لـOriginal S؛ ومعرّف classic محفوظ للتوافق الخلفي.
 */
object TvSkins {
    private val list = ArrayList<TvSkin>().apply {
        // Keep one entry per actual layout. Old preset combinations no longer appear as duplicate skins.
        add(TvSkinHub); add(TvSkinClassic); add(TvSkinGlass)
        add(TvSkinSamsung); add(TvSkinLg); add(TvSkinTcl); add(TvSkinXiaomi)
    }
    private val listeners = ArrayList<() -> Unit>()
    @Volatile private var curSkin: TvSkin? = null

    val all: List<TvSkin> get() = list.toList()

    fun register(s: TvSkin) { synchronized(list) { if (list.none { it.id == s.id }) list.add(s) } }

    fun cur(): TvSkin {
        curSkin?.let { return it }
        val storedId = try { Cfg.str("tv_skin", "glass") } catch (_: Throwable) { "glass" }
        // v626: Rail was an experimental extra layout. Preserve a safe, supported layout when upgrading.
        val id = if (storedId == "rail") "hub" else storedId
        val s = list.firstOrNull { it.id == id } ?: list.first { it.id == "classic" }
        if (storedId != s.id) try { Cfg.put("tv_skin", s.id) } catch (_: Throwable) {}
        curSkin = s
        return s
    }

    /** تبديل حي (من غير ريستارت) — بيحفظ الاختيار وينده المستمعين */
    fun set(id: String, host: TvSkinHost? = null) {
        val s = list.firstOrNull { it.id == id } ?: list.first { it.id == "classic" }
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
