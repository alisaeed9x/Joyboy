package com.tttt.subtitler

import android.content.Context

/**
 * (v821) بروفايلات ريموت IR المحفوظة (أكتر من واحد: تلفزيون · ساتلايت · جهاز تاني) — مخزّنة في إعدادات التطبيق الأساسية (Cfg.p)
 * فبتفضل بعد إعادة التشغيل والتحديث، وبتدخل في النسخة الاحتياطية (Backup) أوتوماتيك.
 *
 * الأكواد نفسها مش بتتنسخ: البروفايل بيشاور على ريموت في قاعدة IR الموجودة (irdb_*.txt) + تعديلات المستخدم اللي اختيارية (remap/custom).
 * الهجرة من v809–v820 (prefs «irtv»): الاختيارات القديمة بتتحوّل لبروفايلات والمفاتيح القديمة بتتساب زي ما هي (مفيش حاجة بتتمسح).
 */
object IrProfiles {
    private const val KEY = "ir_profiles_v1"
    private val lock = Any()
    @Volatile private var cache: ProfileDb? = null

    class Target(val profile: IrProfile?, val kind: String, val wifiLinked: Boolean)

    fun db(c: Context): ProfileDb {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            Cfg.ensure(c.applicationContext)
            val raw = try { Cfg.p.getString(KEY, null) } catch (_: Throwable) { null }
            val d = if (raw == null) migrateLegacy(c.applicationContext) else ProfileDb.fromJson(raw)
            cache = d
            if (raw == null) save(c)
            return d
        }
    }

    /** بيحفظ الحالة الحالية (بعد أي تعديل على db(c)) */
    fun save(c: Context) {
        synchronized(lock) {
            val d = cache ?: return
            try { Cfg.ensure(c.applicationContext); Cfg.p.edit().putString(KEY, d.toJson()).apply() } catch (e: Throwable) { LogStore.err("IrProfiles:save", e) }
        }
    }

    /** لو النسخة الاحتياطية اتسترجعت (Backup.apply) أو الإعدادات اتغيّرت من برّه */
    fun reload() { synchronized(lock) { cache = null } }

    // ============ الهجرة من النظام القديم (prefs "irtv")
    private fun migrateLegacy(c: Context): ProfileDb {
        val d = ProfileDb()
        try {
            val lp = c.getSharedPreferences("irtv", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            for (kind in listOf("tv", "sat")) {
                val sfx = if (kind == "sat") "_sat" else ""
                val brand = lp.getString("brand$sfx", "") ?: ""
                if (brand.isEmpty()) continue
                val sv = lp.getString("remote$sfx", "") ?: ""
                val name = sv.ifEmpty { IrTv.candidates(c, kind, brand, if (kind == "tv") IrTv.tvModel(c) else "").firstOrNull()?.name ?: "" }
                if (name.isEmpty() || IrTv.remoteOf(c, kind, brand, name) == null) continue
                d.add(IrProfile(d.newId(now), d.uniqueName(defaultName(kind, brand, name)), kind, brand, name, "", now, kind))
            }
            val hy = lp.getString("hybrid", "") ?: ""
            if (hy.isNotEmpty()) d.settings.mode = "irfirst"   // v820: «أزرار IR على الريموت الأساسي» ← نفس السلوك بالظبط
            val ik = lp.getString("ir_kind", null) ?: if (lp.getBoolean("ir_mode", false)) "tv" else ""
            if (ik == "sat") d.ofKind("sat").firstOrNull()?.let { d.target = it.id }
        } catch (e: Throwable) { LogStore.err("IrProfiles:migrate", e) }
        return d
    }

    fun defaultName(kind: String, brand: String, remote: String): String {
        val model = CatIndex.modelPart(brand, remote)
        return (if (kind == "sat") "🛰 " else "📺 ") + brand + (if (model.isNotEmpty()) " $model" else "")
    }

    // ============ إنشاء / تعديل
    /** حفظ ريموت من الكتالوج كبروفايل (ضغطة واحدة). التلفزيون المقترن بالواي فاي والمالوش بروفايل بيتربط بيه أوتوماتيك */
    fun saveFromCatalog(c: Context, e: CatEntry, role: String = e.kind, name: String? = null): IrProfile {
        val d = db(c)
        val now = System.currentTimeMillis()
        val cur = try { RemoteClient.current?.id ?: "" } catch (_: Throwable) { "" }
        val dev = if (role == "tv" && cur.isNotEmpty() && d.ofKind("tv").none { it.deviceId == cur }) cur else ""
        val p = IrProfile(d.newId(now), d.uniqueName(name ?: defaultName(role, e.brand, e.name)), role, e.brand, e.name, dev, now, e.kind)
        d.add(p)
        save(c)
        return p
    }

    fun replaceWith(c: Context, id: String, e: CatEntry): Boolean { val ok = db(c).replace(id, e.brand, e.name); if (ok) { db(c).byId(id)?.src = e.kind; save(c) }; return ok }
    fun rename(c: Context, id: String, name: String): Boolean { val ok = db(c).rename(id, name); if (ok) save(c); return ok }
    fun delete(c: Context, id: String): Boolean { val ok = db(c).delete(id); if (ok) save(c); return ok }
    fun setDefault(c: Context, id: String): Boolean { val ok = db(c).setDefault(id); if (ok) save(c); return ok }
    fun setTarget(c: Context, id: String) { db(c).target = if (db(c).byId(id) != null) id else ""; save(c) }

    // ============ الهدف
    /** بروفايل التلفزيون الأساسي (اللي بيتحط احتياطي للتلفزيون المقترن بالواي فاي دلوقتي) */
    fun mainTv(c: Context): IrProfile? {
        val d = db(c)
        val cur = try { RemoteClient.current?.id ?: "" } catch (_: Throwable) { "" }
        val tvs = d.ofKind("tv")
        if (cur.isNotEmpty()) tvs.firstOrNull { it.deviceId == cur }?.let { return it }
        val def = d.defaultFor("tv")
        if (def != null && (def.deviceId.isEmpty() || cur.isEmpty() || def.deviceId == cur)) return def
        return tvs.firstOrNull { it.deviceId.isEmpty() }
    }

    /** الجهاز اللي الأزرار بتتوجّه له دلوقتي: التلفزيون (واي فاي + IR احتياطي) أو بروفايل تاني (IR بس) */
    fun target(c: Context): Target {
        val d = db(c)
        val main = mainTv(c)
        val p = if (d.target.isNotEmpty()) d.byId(d.target) else null
        if (p != null) return if (p === main) Target(p, "tv", true) else Target(p, p.kind, false)
        return Target(main, "tv", true)
    }

    /** (id, اسم) كل الأهداف الممكنة: التلفزيون الأساسي ثم باقي البروفايلات */
    fun targets(c: Context): List<Pair<String, String>> {
        val d = db(c); val main = mainTv(c)
        val out = ArrayList<Pair<String, String>>()
        out.add("" to (if (main != null) main.name else "📺 التلفزيون"))
        for (p in d.profiles) if (p !== main) out.add(p.id to p.name)
        return out
    }

    /** الهدف اللي بعد الحالي (لزرار التبديل في الريموت) */
    fun nextTarget(c: Context): IrProfile? {
        val l = targets(c); if (l.size < 2) return null
        val cur = db(c).target
        val i = l.indexOfFirst { it.first == cur }.let { if (it < 0) 0 else it }
        val nx = l[(i + 1) % l.size].first
        setTarget(c, nx)
        return db(c).byId(nx)
    }

    // ============ الأكواد الفعلية للبروفايل
    fun remote(c: Context, p: IrProfile): IrTv.Remote? = IrTv.remoteOf(c, p.dbKind(), p.brand, p.remote)

    /** إشارة IR لأمر منطقي من بروفايل: الكود اليدوي ← إعادة الربط ← مفتاح الريموت ← (للريسيفرات) بدائل بدون أسهم */
    fun resolve(c: Context, p: IrProfile, cmd: CmdDef): IrTv.Sig? {
        val r = remote(c, p)
        for (k in cmd.irKeys) {
            p.custom[k]?.let { t -> IrTv.parseSig(t)?.let { return it } }
            r?.keys?.get(p.remap[k] ?: k)?.let { return it }
        }
        if (p.kind == "sat") for (k in cmd.irKeys) for (a in Cmds.satAliases(k)) r?.keys?.get(a)?.let { return it }
        return null
    }

    fun has(c: Context, p: IrProfile, cmd: CmdDef): Boolean = resolve(c, p, cmd) != null

    /** كل أزرار الريموت كما هي للبروفايل (مفاتيح الريموت + الأكواد اليدوية) */
    fun keysOf(c: Context, p: IrProfile): List<String> {
        val r = remote(c, p)
        val out = LinkedHashSet<String>()
        if (r != null) out.addAll(r.keys.keys)
        out.addAll(p.custom.keys)
        return out.toList()
    }
}
