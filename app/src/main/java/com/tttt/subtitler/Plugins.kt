package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** (v1256) بلاجن: تطبيقات صغيرة منفصلة بتتنزّل من ريبو Joyboy نفسه وبتتثبّت لوحدها. الـAPK الأساسي مابيكبرش. */
class PluginEntry(val id: String, val name: String, val desc: String, val pkg: String, val vcode: Long, val vname: String, val url: String, val sha256: String)

object Plugins {
    const val INDEX_URL = "https://raw.githubusercontent.com/alisaeed9x/Joyboy/main/plugins/index.json"
    const val AniyomiPkg = "com.tttt.joyboy.plugin.aniyomi"

    fun fetchIndex(): List<PluginEntry> {
        val a = JSONObject(StreamerNet.get(INDEX_URL)).optJSONArray("plugins") ?: return emptyList()
        val out = ArrayList<PluginEntry>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val e = PluginEntry(o.optString("id"), o.optString("name"), o.optString("desc"), o.optString("pkg"), o.optLong("versionCode"), o.optString("versionName"), o.optString("url"), o.optString("sha256"))
            if (e.id.isNotEmpty() && e.pkg.isNotEmpty()) out.add(e)
        }
        return out
    }

    @Suppress("DEPRECATION")
    fun installedCode(c: Context, pkg: String): Long? = try {
        val pi = c.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (_: Throwable) { null }

    fun installedName(c: Context, pkg: String): String = try { c.packageManager.getPackageInfo(pkg, 0).versionName ?: "" } catch (_: Throwable) { "" }

    /** البلاجن لازم يبقى موقّع بنفس مفتاح Joyboy (مفتاح البيلد بتاعك) وإلا مابنكلّمهوش */
    fun sigOk(c: Context, pkg: String): Boolean = try { c.packageManager.checkSignatures(c.packageName, pkg) == PackageManager.SIGNATURE_MATCH } catch (_: Throwable) { false }

    @Suppress("DEPRECATION")
    private fun sigBytes(pi: android.content.pm.PackageInfo?): ByteArray? = try { pi?.signatures?.firstOrNull()?.toByteArray() } catch (_: Throwable) { null }

    @Suppress("DEPRECATION")
    fun download(c: Context, e: PluginEntry): File {
        val dir = File(c.cacheDir, "plugins"); dir.mkdirs()
        val f = File(dir, e.id + ".apk")
        val tmp = File(dir, e.id + ".part")
        val con = java.net.URL(e.url).openConnection() as java.net.HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 60000; con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", StreamerNet.UA)
        try {
            if (con.responseCode !in 200..299) throw IOException("التنزيل فشل (HTTP " + con.responseCode + ")")
            con.inputStream.use { ins -> java.io.FileOutputStream(tmp).use { out -> ins.copyTo(out) } }
        } finally { try { con.disconnect() } catch (_: Throwable) {} }
        if (e.sha256.isNotEmpty()) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { ins -> val b = ByteArray(65536); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
            val h = md.digest().joinToString("") { "%02x".format(it) }
            if (!h.equals(e.sha256, true)) { tmp.delete(); throw IOException("الملف اتبدّل أو اتبوّظ (sha256 مش مطابق)") }
        }
        val pm = c.packageManager
        val info = pm.getPackageArchiveInfo(tmp.path, PackageManager.GET_SIGNATURES) ?: run { tmp.delete(); throw IOException("الملف مش APK صالح") }
        if (info.packageName != e.pkg) { tmp.delete(); throw IOException("الـAPK ده مش بتاع البلاجن ده") }
        val mine = sigBytes(pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES))
        val theirs = sigBytes(info)
        if (mine == null || theirs == null || !mine.contentEquals(theirs)) { tmp.delete(); throw IOException("البلاجن مش موقّع بمفتاح Joyboy، اترفض") }
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) throw IOException("مقدرتش أحفظ الملف")
        return f
    }
}

/** الجسر: البلاجن بيعرض ContentProvider بالـauthority "<package>.bridge" بدالة call(method, arg=JSON) وبيرد Bundle فيه "json" */
object PluginBridge {
    fun call(c: Context, pkg: String, method: String, arg: JSONObject? = null): JSONObject {
        if (Plugins.installedCode(c, pkg) == null) throw IOException("البلاجن مش متثبّت")
        if (!Plugins.sigOk(c, pkg)) throw IOException("البلاجن مش موقّع بنفس مفتاح Joyboy")
        val r = c.contentResolver.call(android.net.Uri.parse("content://" + pkg + ".bridge"), method, arg?.toString(), null) ?: throw IOException("البلاجن مردّش")
        val s = r.getString("json") ?: throw IOException("رد فاضي من البلاجن")
        val j = JSONObject(s)
        if (!j.optBoolean("ok", false)) throw IOException(j.optString("error", "البلاجن رجّع خطأ"))
        return j
    }

    /** مصادر إضافات Aniyomi اللي متثبّتة على الجهاز (من بلاجن Aniyomi). بترجّع فاضي لو البلاجن مش موجود */
    fun aniyomiSources(c: Context): List<StreamSource> {
        val pkg = Plugins.AniyomiPkg
        if (Plugins.installedCode(c, pkg) == null) return emptyList()
        val a = call(c, pkg, "sources").optJSONArray("sources") ?: return emptyList()
        val out = ArrayList<StreamSource>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(ExtSource(pkg, o.optString("id"), o.optString("name"), o.optString("lang"), c.applicationContext))
        }
        return out
    }
}

/** مصدر عبر البلاجن: كل عنصر بيتخزّن كـJSON كامل جوه url وبيترجّع للبلاجن زي ما هو */
class ExtSource(private val pkg: String, private val sid: String, nm: String, lang: String, private val ctx: Context) : StreamSource {
    override val id = "x:" + sid
    override val name = if (lang.isNotEmpty()) nm + " (" + lang + ")" else nm
    private fun items(j: JSONObject): List<JSONObject> { val a = j.optJSONArray("items") ?: return emptyList(); return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    override fun search(q: String, page: Int): List<SShow> =
        items(PluginBridge.call(ctx, pkg, "search", JSONObject().put("src", sid).put("q", q).put("page", page))).map { SShow(it.optString("title"), it.toString(), it.optString("thumb")) }
    override fun episodes(s: SShow): List<SEp> =
        items(PluginBridge.call(ctx, pkg, "episodes", JSONObject().put("src", sid).put("show", JSONObject(s.url)))).map { SEp(it.optString("title"), it.toString()) }
    override fun streams(e: SEp): List<SStream> =
        items(PluginBridge.call(ctx, pkg, "streams", JSONObject().put("src", sid).put("ep", JSONObject(e.url)))).map { SStream(it.optString("label", "فيديو"), it.optString("url"), it.optString("ref")) }
}

class PluginsUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private fun onUi(f: () -> Unit) { act.runOnUiThread { try { f() } catch (_: Throwable) {} } }

    fun show() {
        root.addView(ui.text("⏳ بجيب قايمة البلاجن…", 13f, th.muted))
        ui.sheet(act, "🧩 بلاجن", listOf<View>(root), solid = true).show()
        load()
    }

    private fun load() {
        Thread {
            val list = try { Plugins.fetchIndex() } catch (e: Throwable) { onUi { root.removeAllViews(); root.addView(ui.text("⚠ ماقدرتش أجيب القايمة: " + (e.message ?: e.javaClass.simpleName).take(80), 13f, th.danger)); root.addView(ui.button("🔄 جرّب تاني") { root.removeAllViews(); root.addView(ui.text("⏳ …", 13f, th.muted)); load() }) }; return@Thread }
            onUi { render(list) }
        }.also { it.isDaemon = true }.start()
    }

    private fun render(list: List<PluginEntry>) {
        root.removeAllViews()
        root.addView(ui.text("البلاجن بتتنزّل من ريبو Joyboy وبتتثبّت كتطبيق منفصل (الـAPK الأساسي مابيكبرش). بتتأكد من توقيعها قبل التثبيت.", 12f, th.muted))
        if (list.isEmpty()) root.addView(ui.text("مفيش بلاجن منشورة دلوقتي.", 13f, th.muted).apply { setPadding(0, ui.dp(10), 0, 0) })
        for (e in list) {
            val have = Plugins.installedCode(act, e.pkg)
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.box(th.surface, th.border, 12)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
            }
            card.addView(ui.text("🧩 " + e.name + (if (e.vname.isNotEmpty()) "   v" + e.vname else ""), 15f, th.text, true))
            if (e.desc.isNotEmpty()) card.addView(ui.text(e.desc, 12f, th.muted))
            if (e.url.isEmpty()) card.addView(ui.text("قريبًا: لسه ما اتنشرتش", 12f, th.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
            else if (have == null) card.addView(ui.button("⬇ تنزيل وتثبيت", true) { go(e) })
            else if (have < e.vcode) card.addView(ui.button("⬆ تحديث (v" + Plugins.installedName(act, e.pkg) + " ← v" + e.vname + ")", true) { go(e) })
            else card.addView(ui.text("✅ مثبّت v" + Plugins.installedName(act, e.pkg) + (if (Plugins.sigOk(act, e.pkg)) "" else "  ⚠ توقيع مختلف"), 13f, th.primary).apply { setPadding(0, ui.dp(6), 0, 0) })
            root.addView(card)
        }
        root.addView(ui.button("🔄 تحديث القايمة") { load() })
    }

    private fun go(e: PluginEntry) {
        Notice.show(act, "⏳ بنزّل " + e.name + "…", 2000L)
        Thread {
            try {
                val f = Plugins.download(act, e)
                onUi { TvUpdate.install(act, f) }
            } catch (x: Throwable) { onUi { Notice.show(act, "⚠ " + (x.message ?: "فشل").take(90), 3500L) } }
        }.also { it.isDaemon = true }.start()
    }
}
