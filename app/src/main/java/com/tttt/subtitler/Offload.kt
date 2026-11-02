package com.tttt.subtitler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.os.PowerManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * (v698 م7-أ) إحالة التفريغ والترجمة الأوفلاين من التلفزيون للموبايل.
 * التلفزيون (سيرفر) بيقطّع الصوت باتشات زي ما هو، ولما «مصدر المعالجة = الموبايل» والوضع «📴 أوفلاين كامل»
 * بيحط كل باتش (تفريغ) وكل شريحة جمل (ترجمة) في طابور. الموبايل (عميل) بيسحب بـ long-poll من /oj/next،
 * بيفرّغ/بيترجم بالموديلات اللي عنده واحد ورا التاني (Whisper ثم release ثم Hy-MT2) وبيرجّع النتيجة على /oj/done.
 * Engine نفسه ما اتغيّرش: Offline.transcribe بتحوّل هنا، وفي Engine.googleItemsRaw الترجمة بتعدّي على translate().
 */
object Offload {
    const val KEY = "proc_src"   // "phone" = الموبايل يعالج · غير كده = الجهاز ده

    // ───────────────────────── جانب التلفزيون ─────────────────────────
    private class Job(val id: String, val type: String, val args: JSONObject) {
        val done = CountDownLatch(1)
        @Volatile var res: JSONObject? = null
        @Volatile var taken = 0L
    }

    private val queue = LinkedBlockingQueue<Job>()
    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile private var lastPoll = 0L
    @Volatile private var phoneTx = false
    @Volatile private var phoneMt = false
    @Volatile private var phoneNote = ""   // (v699) الموبايل واقف مؤقتًا: hot = سخن · bat = بطارية قليلة
    @Volatile private var phoneSide = false   // الجهاز ده فيه الـworker يعني موبايل: عمره ما يحوّل

    /** التلفزيون + الوضع أوفلاين كامل + المستخدم اختار «الموبايل» */
    fun wanted(): Boolean = !phoneSide && TvLink.appCtx() != null && Strict.mt() && Cfg.str(KEY, "") == "phone"

    private fun phoneAlive(): Boolean = System.currentTimeMillis() - lastPoll < 25_000L

    /** حالة للعرض (إعدادات / إشعار) */
    fun status(): String = when {
        !wanted() -> ""
        !phoneAlive() -> "📱 الموبايل مش متصل — افتح الريموت على الموبايل"
        phoneNote.isNotEmpty() -> "📱 الموبايل واقف مؤقتًا — " + noteText(phoneNote)
        !phoneTx || !phoneMt -> "📱 الموبايل لسه ما نزّلش الموديلات (" + (if (!phoneTx) "التفريغ" else "") + (if (!phoneTx && !phoneMt) " + " else "") + (if (!phoneMt) "الترجمة" else "") + ")"
        else -> "📱 الموبايل جاهز (تفريغ + ترجمة)"
    }

    private fun noteText(n: String): String = when (n) { "hot" -> "الموبايل سخن، بيبرّد"; "bat" -> "البطارية قليلة، وصّله بالشاحن"; else -> n }

    /** يحط الشغلانة في الطابور ويستنى النتيجة. بيرمي Exception لو الموبايل مش موجود/ناقص (Engine بيعيد الباتش) */
    private fun run(type: String, args: JSONObject, log: (String) -> Unit, label: String): JSONObject {
        val t0 = System.currentTimeMillis()
        var noted = 0L
        // استنى الموبايل يظهر (حد أقصى 90ث) بإشعار كل 20ث
        while (!phoneAlive()) {
            if (System.currentTimeMillis() - t0 > 90_000L) throw Exception("الموبايل مش متصل — افتح الريموت على الموبايل وشغّل الأوفلاين")
            if (System.currentTimeMillis() - noted > 20_000L) { noted = System.currentTimeMillis(); log("📱 مستني الموبايل يتصل…") }
            Thread.sleep(1000L)
        }
        if (if (type == "tx") !phoneTx else !phoneMt) throw Exception("الموبايل لسه ما نزّلش موديل " + (if (type == "tx") "التفريغ" else "الترجمة"))
        val j = Job(UUID.randomUUID().toString(), type, args)
        jobs[j.id] = j; queue.add(j)
        try {
            var lastLog = System.currentTimeMillis()
            while (!j.done.await(3, TimeUnit.SECONDS)) {
                val now = System.currentTimeMillis()
                // الموبايل سحب الشغلانة وبعدين اختفى: ارجعها للطابور
                if (j.taken > 0L && !phoneAlive() && now - j.taken > 45_000L) { j.taken = 0L; queue.add(j); log("📱 الموبايل اتقطع — إعادة الباتش للطابور") }
                if (now - lastLog > 30_000L) { lastLog = now; log("📱 $label على الموبايل… (${(now - t0) / 1000}ث)" + (if (phoneNote.isNotEmpty()) " — " + noteText(phoneNote) else "")) }
                if (now - t0 > 25 * 60_000L) throw Exception("الموبايل ما ردّش في 25 دقيقة")
            }
            val r = j.res ?: throw Exception("رد فاضي من الموبايل")
            if (!r.optBoolean("ok", false)) throw Exception(r.optString("err", "فشل على الموبايل").take(120))
            return r
        } finally { jobs.remove(j.id); queue.remove(j) }
    }

    /** بديل Offline.transcribe: نفس المخرج (JSON نص + حالة اللغة) لكن الشغل على الموبايل */
    fun transcribe(wav: ByteArray, durSec: Double, terms: String, log: (String) -> Unit, sess: Offline.Session?): Api.Result {
        Offline.ranOffline.set(false)
        val a = JSONObject()
            .put("wav", Base64.encodeToString(wav, Base64.NO_WRAP))
            .put("dur", durSec).put("terms", terms)
            .put("ml", Offline.lang())                 // اللغة اليدوية بتاعة التلفزيون
            .put("sl", sess?.lang.orEmpty()).put("sh", sess?.hint.orEmpty())
            .put("idx", Offline.batchTag.get() ?: -1)
        val r = run("tx", a, log, "التفريغ")
        Offline.ranOffline.set(true)
        Offline.usedLang.set(r.optString("ul")); Offline.gotLang.set(r.optString("gl"))
        if (sess != null) { sess.lang = r.optString("sl"); sess.hint = r.optString("sh") }
        if (Offline.lang().isEmpty()) Offline.detectedLang = r.optString("dl")
        return Api.Result(r.optString("text"), "STOP")
    }

    /** بديل LocalMt.translateAll على الموبايل، شرايح 60 جملة. جملة تفشل تفضل بأصلها */
    fun translate(lines: List<String>, src: String, log: (String) -> Unit, onProgress: (Int, Int) -> Unit): List<String> {
        val out = ArrayList<String>(lines)
        var done = 0
        for (from in lines.indices step 60) {
            val to = minOf(lines.size, from + 60)
            val arr = JSONArray(); for (k in from until to) arr.put(lines[k])
            val r = run("mt", JSONObject().put("lines", arr).put("src", src), log, "الترجمة")
            val o = r.optJSONArray("out")
            if (o != null) for (k in 0 until minOf(o.length(), to - from)) { val t = o.optString(k, ""); if (t.isNotBlank()) out[from + k] = t }
            done = to; onProgress(done, lines.size)
        }
        return out
    }

    /** GET /oj/next?wait=ms&tx=0|1&mt=0|1 — الموبايل بيسحب شغلانة (long-poll) */
    fun next(r: HttpReq): HttpRes {
        lastPoll = System.currentTimeMillis()
        phoneTx = r.query["tx"] == "1"; phoneMt = r.query["mt"] == "1"
        phoneNote = r.query["pz"].orEmpty()
        if (phoneNote.isNotEmpty()) { try { Thread.sleep(4000L) } catch (_: InterruptedException) {}; lastPoll = System.currentTimeMillis(); return HttpRes.json(JSONObject().put("ok", true).put("job", false).toString()) }   // (v699) الموبايل بيبرّد/بطاريته قليلة: ما نسلّمهوش شغل
        val wait = (r.query["wait"]?.toLongOrNull() ?: 0L).coerceIn(0L, 25_000L)
        var j: Job? = try { queue.poll(wait, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
        lastPoll = System.currentTimeMillis()
        while (j != null && (j.done.count == 0L || !jobs.containsKey(j.id))) j = queue.poll()   // اتلغت
        if (j == null) return HttpRes.json(JSONObject().put("ok", true).put("job", false).toString())
        j.taken = System.currentTimeMillis()
        return HttpRes.json(JSONObject().put("ok", true).put("job", true).put("id", j.id).put("type", j.type).put("a", j.args).toString())
    }

    /** POST /oj/done {id, ok, ...} */
    fun done(r: HttpReq): HttpRes {
        val b = try { JSONObject(r.bodyText()) } catch (_: Exception) { return HttpRes.json("{\"ok\":false,\"err\":\"bad\"}", 403) }
        val j = jobs[b.optString("id")] ?: return HttpRes.json("{\"ok\":true,\"gone\":true}")
        j.res = b; j.done.countDown()
        return HttpRes.json("{\"ok\":true}")
    }

    // ───────────────────────── جانب الموبايل ─────────────────────────
    @Volatile private var workerOn = false
    @Volatile private var wGen = 0
    @Volatile private var busyNote = ""
    @Volatile private var paused = ""   // "" | "hot" | "bat"
    private val ui = Handler(Looper.getMainLooper())

    private class Hs(val temp: Float, val pct: Int, val chg: Boolean)
    private fun health(app: Context): Hs? = try {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) null else {
            val lvl = i.getIntExtra("level", -1); val sc = i.getIntExtra("scale", 100).coerceAtLeast(1)
            Hs(i.getIntExtra("temperature", 0) / 10f, if (lvl < 0) 100 else lvl * 100 / sc, i.getIntExtra("plugged", 0) != 0)
        }
    } catch (_: Throwable) { null }

    /** (v699) حماية: سخونة ≥ 43° ← وقّف السحب لحد ما تنزل تحت 40° · بطارية ≤ 15% من غير شاحن ← وقّف لحد 20% أو الشاحن */
    private fun updatePause(app: Context, h: Hs?) {
        if (h == null) return
        val np = when {
            h.temp >= 43f -> "hot"
            paused == "hot" && h.temp >= 40f -> "hot"
            !h.chg && h.pct <= 15 -> "bat"
            paused == "bat" && !h.chg && h.pct < 20 -> "bat"
            else -> ""
        }
        if (np == paused) return
        paused = np
        busyNote = if (np.isEmpty()) "" else "⏸ " + noteText(np)
        val msg = when (np) { "hot" -> "🌡 الموبايل سخن (" + "%.0f".format(h.temp) + "°) — الأوفلاين واقف لحد ما يبرد"; "bat" -> "🔋 البطارية " + h.pct + "% — الأوفلاين واقف، وصّل الشاحن"; else -> "▶ الأوفلاين كمّل" }
        ui.post { try { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }
        try { LogStore.add("Offload: " + msg) } catch (_: Throwable) {}
    }

    fun workerStatus(): String = if (!workerOn) "" else busyNote

    @Synchronized
    fun startWorker(ctx: Context) {
        if (TvLink.appCtx() != null) return   // التلفزيون ما بيشتغلش worker
        if (workerOn) return
        workerOn = true; phoneSide = true; wGen += 1
        val g = wGen; val app = ctx.applicationContext
        Thread { loop(app, g) }.also { it.isDaemon = true; it.name = "OffloadWorker" }.start()
    }

    @Synchronized
    fun stopWorker() { workerOn = false; wGen += 1; busyNote = "" }

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun loop(app: Context, g: Int) {
        var fails = 0
        while (workerOn && wGen == g) {
            if (!RemoteClient.ensureConnected(app, 4000L)) { nap(2000L); continue }
            val tx = try { Offline.txReady() } catch (_: Throwable) { false }
            val mt = try { LocalMt.ready() } catch (_: Throwable) { false }
            updatePause(app, health(app))
            val (c, t) = RemoteClient.getText("/oj/next?wait=20000&tx=" + (if (tx) 1 else 0) + "&mt=" + (if (mt) 1 else 0) + (if (paused.isNotEmpty()) "&pz=$paused" else ""), 30000)
            if (!workerOn || wGen != g) break
            if (c != 200) { fails += 1; nap(minOf(1000L * fails, 8000L)); continue }
            fails = 0
            val j = try { JSONObject(t) } catch (_: Exception) { continue }
            if (!j.optBoolean("job", false)) continue
            handle(app, j)
        }
    }

    private fun post(res: JSONObject) {
        for (i in 0 until 3) {
            val (c, _) = try { RemoteClient.postJsonMs("/oj/done", res, 20000) } catch (_: Throwable) { Pair(-1, "") }
            if (c == 200) return
            nap(800L)
        }
        LogStore.add("Offload: ما قدرتش أرجّع النتيجة للتلفزيون")
    }

    private fun handle(app: Context, j: JSONObject) {
        val id = j.optString("id"); val type = j.optString("type"); val a = j.optJSONObject("a") ?: JSONObject()
        val res = JSONObject().put("id", id)
        var wl: PowerManager.WakeLock? = null
        try { wl = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "joyboy:offload").also { it.acquire(15 * 60_000L) } } catch (_: Throwable) {}
        val lg: (String) -> Unit = { try { LogStore.add("Offload: " + it) } catch (_: Throwable) {} }
        try {
            if (type == "tx") {
                busyNote = "📴 بيفرّغ لصالح التلفزيون…" + (health(app)?.let { " (" + "%.0f".format(it.temp) + "° · " + it.pct + "%)" } ?: "")
                val wav = Base64.decode(a.optString("wav"), Base64.NO_WRAP)
                val sess = Offline.Session().also { it.lang = a.optString("sl"); it.hint = a.optString("sh") }
                Offline.batchTag.set(a.optInt("idx", -1))
                Offline.langOv.set(a.optString("ml"))
                var why = ""
                val r = try {
                    Offline.transcribe(wav, a.optDouble("dur", 0.0), a.optString("terms"), lg, { why = "موديل التفريغ على الموبايل مش جاهز أو فشل"; Api.Result("", "ERR") }, { }, sess)
                } finally { Offline.langOv.remove() }
                if (Offline.ranOffline.get() != true) res.put("ok", false).put("err", why.ifEmpty { "فشل التفريغ على الموبايل" })
                else res.put("ok", true).put("text", r.text)
                    .put("ul", Offline.usedLang.get().orEmpty()).put("gl", Offline.gotLang.get().orEmpty())
                    .put("sl", sess.lang).put("sh", sess.hint).put("dl", Offline.detectedLang)
            } else if (type == "mt") {
                busyNote = "📴 بيترجم لصالح التلفزيون…"
                val arr = a.optJSONArray("lines") ?: JSONArray()
                val src = ArrayList<String>(arr.length()); for (k in 0 until arr.length()) src.add(arr.optString(k, ""))
                Offline.releaseWhisper()   // رام: التفريغ والترجمة ورا بعض
                val out = LocalMt.translateAll(src, a.optString("src"), lg)
                val oa = JSONArray(); for (k in src.indices) oa.put(out.getOrNull(k) ?: "")
                res.put("ok", true).put("out", oa)
            } else res.put("ok", false).put("err", "نوع شغلانة مجهول")
        } catch (e: Throwable) {
            res.put("ok", false).put("err", (e.message ?: e.javaClass.simpleName).take(100))
            try { LogStore.add("Offload: " + (e.message ?: e.javaClass.simpleName).take(120)) } catch (_: Throwable) {}
        } finally {
            busyNote = if (paused.isNotEmpty()) "⏸ " + noteText(paused) else ""
            try { wl?.release() } catch (_: Throwable) {}
        }
        post(res)
    }
}
