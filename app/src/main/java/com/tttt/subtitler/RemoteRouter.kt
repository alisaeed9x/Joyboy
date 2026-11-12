package com.tttt.subtitler

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v821) الراوتر المركزي للريموت الموحّد. كل زرار في الريموت (باور · رجوع · رئيسية البرنامج · رئيسية التلفزيون · قايمة · أسهم · OK · صوت · كتم)
 * ليه أمر منطقي واحد (Cmds) ومعالج واحد (press) — والواي فاي وIR مستخبيين ورا الأمر:
 *  • التلفزيون متصل وسليم ← واي فاي (الأساسي). مش متاح أو فشل ← IR من بروفايل التلفزيون المحفوظ، من غير تدخل.
 *  • رجع الاتصال ← الواي فاي يرجع أساسي لوحده (مستمع RemoteClient + إعادة تجربة بعد فترة).
 *  • ساتلايت/جهاز تاني ← IR على بروفايله هو بس (مفيش أمر بيروح لجهاز غلط).
 *  • مفيش إرسال مزدوج إلا بإعداد صريح، وبأوامر مش «قلب» (باور/كتم) ومش متكرّرة.
 *  • الأوامر السريعة المتكررة (ارتداد اللمس) بتتفلتر بـ Gate، والـIR بيتنفّذ من طابور واحد بتباعد ثابت.
 */
object RemoteRouter {
    class Result(val transport: Transport, val sent: Boolean, val why: Why = Why.OK) {
        val note: String get() = if (sent) "" else RouterCore.explain(why, null)
    }

    private val gate = Gate()
    private val health = Health()
    private val main = Handler(Looper.getMainLooper())
    private val irExec = Executors.newSingleThreadExecutor { r -> Thread(r, "ir-send").apply { isDaemon = true } }
    private val irPending = AtomicInteger(0)
    @Volatile private var lastIrAt = 0L
    @Volatile private var hooked = false
    @Volatile private var hw: Boolean? = null
    @Volatile var lastTransport: Transport = Transport.NONE
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private const val IR_GAP_MS = 95L
    // (v855) الواي فاي فشل 3 مرات ورا بعض ومفيش ريموت IR محفوظ للتلفزيون ← نقترح «اظبط الريموت بالتجربة» (مرة كل 24 ساعة على الأكتر)
    private val wifiStreak = FailStreak(3, 24L * 3600_000L)
    private val needIrListeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(c: Context) {
        if (hooked) return
        hooked = true
        try { RemoteClient.init(c) } catch (_: Throwable) {}
        // رجوع الاتصال ← الواي فاي يرجع أساسي فورًا؛ وأي تغيير حالة ← تحديث مؤشر الحالة
        try {
            RemoteClient.addListener {
                if (RemoteClient.state == RemoteClient.St.CONNECTED) health.onConnected()
                fireStatus()
            }
        } catch (_: Throwable) {}
    }

    fun addStatusListener(l: () -> Unit) { listeners.add(l) }
    fun removeStatusListener(l: () -> Unit) { listeners.remove(l) }
    private fun fireStatus() { main.post { for (l in listeners) try { l() } catch (_: Throwable) {} } }

    /** (v855) الريموت المفتوح بيسمع هنا: لما الواي فاي يفشل كذا مرة ومفيش IR احتياطي ← يعرض اقتراح ضبط ريموت IR */
    fun addNeedIrListener(l: () -> Unit) { needIrListeners.add(l) }
    fun removeNeedIrListener(l: () -> Unit) { needIrListeners.remove(l) }
    private fun fireNeedIr() { main.post { for (l in needIrListeners) try { l() } catch (_: Throwable) {} } }

    /** «ما تسألنيش تاني» (forever) أو «لاحقًا» (24 ساعة) */
    fun snoozeIrSuggest(c: Context, forever: Boolean) {
        try { c.applicationContext.getSharedPreferences("irtv", Context.MODE_PRIVATE).edit().putLong("suggest_at", if (forever) Long.MAX_VALUE / 2 else System.currentTimeMillis()).apply() } catch (_: Throwable) {}
    }

    /** نتيجة إرسال واي فاي: النجاح بيصفّر العدّاد. الفشل المتكرر (والموبايل فيه بلاستر ومفيش بروفايل تلفزيون) بيطلّع الاقتراح */
    private fun noteWifi(ctx: Context, ok: Boolean) {
        if (ok) { wifiStreak.ok(); return }
        try {
            if (!irHardware(ctx) || IrProfiles.mainTv(ctx) != null) return
            val now = System.currentTimeMillis()
            val sp = ctx.getSharedPreferences("irtv", Context.MODE_PRIVATE)
            if (wifiStreak.fail(now, sp.getLong("suggest_at", 0L))) { sp.edit().putLong("suggest_at", now).apply(); fireNeedIr() }
        } catch (_: Throwable) {}
    }

    fun irHardware(c: Context): Boolean { hw?.let { return it }; val v = IrTv.available(c); hw = v; return v }

    private fun stName(): String = when (RemoteClient.state) {
        RemoteClient.St.CONNECTED -> "CONNECTED"; RemoteClient.St.CONNECTING -> "CONNECTING"; RemoteClient.St.LOST -> "LOST"; else -> "IDLE"
    }

    /** مؤشر الحالة الحي (Wi-Fi فعّال · IR فعّال · جاري الاتصال · الجهاز غير متاح · IR غير مدعوم) */
    fun status(c: Context): IrStatus {
        val t = IrProfiles.target(c); val d = IrProfiles.db(c)
        return StatusCore.of(t.kind, t.wifiLinked, stName(), health.isBad(System.currentTimeMillis()), irHardware(c), t.profile != null, d.settings.mode)
    }

    fun statusLabel(c: Context): String {
        val s = status(c); val t = IrProfiles.target(c)
        val base = StatusCore.label(s)
        return if (!t.wifiLinked && t.profile != null) "${t.profile.name} · $base" else base
    }

    /**
     * ينفّذ زرار منطقي. بيرجّع القرار فورًا؛ لو الأمر اتفشل نهائيًا (بعد الاحتياطي) [onFail] بيتنده على الـmain thread بسبب مقروء.
     * الأمر اللي اتفلتر (تكرار) بيرجع Why.DUPLICATE من غير إزعاج المستخدم.
     */
    fun press(c: Context, cmdId: String, onFail: ((String) -> Unit)? = null): Result {
        val ctx = c.applicationContext
        init(ctx)
        val cmd = Cmds.of(cmdId) ?: return Result(Transport.NONE, false, Why.UNKNOWN_CMD)
        val now = System.currentTimeMillis()
        if (!gate.allow(cmd, now)) return Result(Transport.NONE, false, Why.DUPLICATE)
        val d = IrProfiles.db(ctx)
        var tgt = IrProfiles.target(ctx)
        // الصوت/الكتم لما الهدف ريسيفر مفيهوش أزرار صوت (بإعداد صريح): يروح لتلفزيون المستخدم
        if (cmd.id in Cmds.VOLUME && !tgt.wifiLinked && d.settings.volFollowsTv && (tgt.profile == null || !IrProfiles.has(ctx, tgt.profile, cmd)))
            tgt = IrProfiles.Target(IrProfiles.mainTv(ctx), "tv", true)
        val p = tgt.profile
        val rc = RouteCtx(
            targetKind = tgt.kind, wifiLinked = tgt.wifiLinked,
            wifiConnected = RemoteClient.state == RemoteClient.St.CONNECTED, wifiBad = health.isBad(now), wifiCanExecute = wifiCanExecute(cmd),
            irHw = irHardware(ctx), hasProfile = p != null, irKeyPresent = p != null && IrProfiles.has(ctx, p, cmd),
            mode = d.settings.mode, dual = d.settings.dual
        )
        val plan = RouterCore.plan(cmd, rc)
        lastTransport = plan.transport
        val fail: (String) -> Unit = { m -> if (onFail != null) main.post { try { onFail(m) } catch (_: Throwable) {} } }
        when (plan.transport) {
            Transport.NONE -> { fail(RouterCore.explain(plan.why, cmd)); fireStatus(); return Result(Transport.NONE, false, plan.why) }
            Transport.IR -> {
                if (!sendIr(ctx, p!!, cmd) { ok -> if (!ok) fail("إرسال IR فشل — اتأكد من البلاستر") }) { fail(RouterCore.explain(Why.KEY_MISSING, cmd)); return Result(Transport.NONE, false, Why.KEY_MISSING) }
            }
            Transport.WIFI -> sendWifi(cmd) { ok -> noteWifi(ctx, ok); if (ok) health.ok() else { health.fail(System.currentTimeMillis()); fireStatus(); fail("التلفزيون ما ردّش") } }
            Transport.WIFI_THEN_IR -> {
                val once = AtomicBoolean(false)
                sendWifi(cmd) { ok ->
                    noteWifi(ctx, ok)
                    if (ok) health.ok()
                    else {
                        health.fail(System.currentTimeMillis()); fireStatus()
                        // الاحتياطي مرة واحدة بس، وأوامر «القلب» (كتم) مفيهاش احتياطي بعد محاولة واي فاي: ممكن تكون وصلت والرد ضاع فتتعكس
                        if (!cmd.toggle && p != null && once.compareAndSet(false, true)) {
                            lastTransport = Transport.IR
                            if (!sendIr(ctx, p, cmd) { ok2 -> if (!ok2) fail("الواي فاي وIR فشلوا") }) fail("التلفزيون ما ردّش ومفيش زرار IR مناسب")
                        } else fail("التلفزيون ما ردّش")
                    }
                }
            }
            Transport.BOTH -> {
                sendWifi(cmd) { ok -> noteWifi(ctx, ok); if (ok) health.ok() else health.fail(System.currentTimeMillis()) }
                if (p != null) sendIr(ctx, p, cmd) { }
            }
        }
        return Result(plan.transport, true, Why.OK)
    }

    /** الواي فاي مش هينفّذ أوامر الحقن لو التلفزيون على تطبيق تاني ومفيش «إمكانية وصول» (والتلفزيون بعت حالته) */
    private fun wifiCanExecute(cmd: CmdDef): Boolean {
        if (!cmd.needsAccess) return true
        val ctxName = RemoteClient.tvCtx
        if (ctxName.isEmpty()) return true
        return !(ctxName == "app" || ctxName == "other") || RemoteClient.tvAcc
    }

    private fun sendWifi(cmd: CmdDef, done: (Boolean) -> Unit) {
        val once = AtomicBoolean(false)
        val cb: (Boolean) -> Unit = { ok -> if (once.compareAndSet(false, true)) done(ok) }
        try {
            if (cmd.wifiKey != null) RemoteClient.key(cmd.wifiKey, cb)
            else if (cmd.wifiCmd != null) RemoteClient.cmd(cmd.wifiCmd, cb)
            else cb(false)
        } catch (_: Throwable) { cb(false) }
    }

    /** @return false لو مفيش كود لأمر ده في البروفايل (الإرسال الفعلي بيتم من طابور IR) */
    private fun sendIr(c: Context, p: IrProfile, cmd: CmdDef, done: (Boolean) -> Unit): Boolean {
        val sig = IrProfiles.resolve(c, p, cmd) ?: return false
        if (cmd.repeatable && irPending.get() >= 2) { done(true); return true }   // ماسك الزرار والبلاستر مش لاحق: متراكمش طابور
        irPending.incrementAndGet()
        try {
            irExec.execute {
                try {
                    val wait = IR_GAP_MS - (System.currentTimeMillis() - lastIrAt)
                    if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
                    val ok = IrTv.sendSig(c, sig)
                    lastIrAt = System.currentTimeMillis()
                    done(ok)
                } catch (_: Throwable) { done(false) } finally { irPending.decrementAndGet() }
            }
        } catch (_: Throwable) { irPending.decrementAndGet(); done(false) }
        return true
    }

    /** إرسال إشارة معيّنة (للتجربة في معاينة البروفايل قبل الحفظ) — مباشرة من غير توجيه */
    fun testSig(c: Context, sig: IrTv.Sig, done: (Boolean) -> Unit) {
        val ctx = c.applicationContext
        irExec.execute {
            val wait = IR_GAP_MS - (System.currentTimeMillis() - lastIrAt)
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
            val ok = try { IrTv.sendSig(ctx, sig) } catch (_: Throwable) { false }
            lastIrAt = System.currentTimeMillis()
            main.post { done(ok) }
        }
    }

    /** تشخيص للإعدادات (مش في الريموت العادي) */
    fun diag(c: Context): String {
        val d = IrProfiles.db(c)
        return IrTv.irInfo(c) + "\n" +
            "Wi-Fi: " + stName() + (if (health.isBad(System.currentTimeMillis())) " (فشل حديث — بيتجرّب تاني تلقائي)" else "") + "\n" +
            "البروفايلات: ${d.profiles.size} · آخر مسار: ${lastTransport.name}"
    }

    /** للاختبارات/إعادة الضبط */
    fun resetForTest() { gate.reset(); health.ok(); lastTransport = Transport.NONE }
}
