package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (v542) م1 — جانب التلفزيون من «التلفزيون = المخ، الموبايل = تحكم كامل».
 * بيتسجّل في TvLink.route (بنفس التوكن). كل المسارات هنا بتتنفّذ على خيوط HTTP (مش الـUI)،
 * والحالة بتتنشر من PlayerActivity.brainTick() عبر BrainBus (فمفيش انتظار على الـUI في /sync).
 *
 * المسارات: POST /rpc · GET /sync?since=&wait= · GET /frame?w=&q= · GET /lib/folders · GET /lib/videos?folder= ·
 * GET /lib/thumb?id= · GET|POST /cfg
 */
object BrainRpc {
    private fun jerr(code: Int, e: String) = HttpRes.json(JSONObject().put("ok", false).put("err", e).toString(), code)
    private fun jok(j: JSONObject) = HttpRes.json(j.put("ok", true).toString())

    /** نتايج الأوامر حسب cid: لو الموبايل أعاد نفس الأمر (انقطاع شبكة) يرجعله نفس الرد من غير تنفيذ تاني */
    private val done = object : LinkedHashMap<String, String>(128, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 100
    }

    /** أوامر قراءة بس (مسموحة لأي متحكّم حتى لو مش المالك) */
    private val readOps = setOf("state", "engine.get", "engine.versions", "control.take")

    private fun taskOp(op: String, a: JSONObject): JSONObject {
        if (op == "task.clear") { TaskCenter.clearFinished(); return JSONObject().put("ok", true) }
        if (op == "task.add") {
            val res = PlayerActivity.brain(op, a) ?: return JSONObject().put("ok", false).put("err", "noplayer")
            val bad = res.optString("err", "")
            return if (bad.isNotEmpty()) JSONObject().put("ok", false).put("err", bad) else JSONObject().put("ok", true).put("res", res)
        }
        val t = TaskCenter.items.firstOrNull { it.id == a.optInt("id", -1) } ?: return JSONObject().put("ok", false).put("err", "notask")
        when (op) {
            "task.stop" -> TaskCenter.stop(t)
            "task.cancel" -> TaskCenter.cancel(t)
            "task.pause" -> TaskCenter.pause(t, a.optBoolean("p", true))
            "task.resume" -> TaskCenter.pause(t, false)
            "task.retry" -> TaskCenter.retry(t)
            "task.remove" -> TaskCenter.remove(t)
            else -> return JSONObject().put("ok", false).put("err", "unknown")
        }
        return JSONObject().put("ok", true)
    }

    fun exec(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return jerr(403, "bad") }
        val op = j.optString("op"); val cid = j.optString("cid")
        val args = j.optJSONObject("args") ?: JSONObject()
        if (op.isEmpty()) return jerr(403, "noop")
        if (cid.isNotEmpty()) {
            val old = synchronized(done) { done[cid] }
            if (old != null) return HttpRes.json(old)
        }
        val me = r.header("x-token")
        if (op == "control.take") { BrainBus.owner.take(me, System.currentTimeMillis()); return jok(JSONObject()) }
        if (op !in readOps && !BrainBus.owner.claim(me, System.currentTimeMillis())) return jerr(409, "readonly")
        val out: JSONObject = try {
            if (op.startsWith("task.")) {
                taskOp(op, args)
            } else if (op == "load") {
                if (TvLink.openJson(args)) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("err", "badurl")
            } else {
                val res = PlayerActivity.brain(op, args)
                if (res == null) JSONObject().put("ok", false).put("err", "noplayer") else JSONObject().put("ok", true).put("res", res)
            }
        } catch (e: Throwable) {
            LogStore.err("BrainRpc:$op", e)
            JSONObject().put("ok", false).put("err", e.message ?: "err")
        }
        val body = out.toString()
        if (cid.isNotEmpty() && out.optBoolean("ok", false)) synchronized(done) { done[cid] = body }
        return HttpRes.json(body)
    }

    /** long-poll: يرد فورًا لو ver أكبر من since، وإلا يستنى لحد wait ms (حد أقصى 25ث) */
    fun sync(r: HttpReq): HttpRes {
        BrainBus.lastSync = System.currentTimeMillis()
        try { BrainBus.setTasks(TaskCenter.brainJson()) } catch (_: Throwable) {}
        val since = r.query["since"]?.toLongOrNull() ?: 0L
        val wait = (r.query["wait"]?.toLongOrNull() ?: 0L).coerceIn(0L, 25000L)
        val ver = if (BrainBus.ver > since) BrainBus.ver else BrainBus.await(since, wait)
        BrainBus.lastSync = System.currentTimeMillis()
        val st = try { JSONObject(BrainBus.stateJson) } catch (_: Throwable) { JSONObject() }
        val out = JSONObject().put("ver", ver).put("st", st).put("live", PlayerActivity.hasLive()).put("ctl", BrainBus.owner.isOwner(r.header("x-token"), System.currentTimeMillis()))
            .put("events", BrainBus.eventsSince(since))
            .put("tasks", try { JSONArray(BrainBus.tasksJson) } catch (_: Throwable) { JSONArray() })
            .put("eng", try { JSONObject(BrainBus.engineJson) } catch (_: Throwable) { JSONObject() })
        val d = BrainBus.subs.since(since)
        if (d.optBoolean("full", false)) out.put("subsGz", BrainZip.pack(d.toString())) else out.put("subs", d)
        return jok(out)
    }

    private val frameLock = Any()

    /** لقطة حية JPEG. 204 = مفيش لقطة (الموبايل يعرض البوستر) */
    fun frame(r: HttpReq): HttpRes {
        val w = (r.query["w"]?.toIntOrNull() ?: 480).coerceIn(160, 960)
        val q = r.query["q"]?.toIntOrNull() ?: 60
        val b = synchronized(frameLock) { PlayerActivity.brainFrame(w, q) } ?: return HttpRes(204)
        return HttpRes(200, "image/jpeg", b)
    }

    // ===== المكتبة =====
    private fun items(app: Context, refresh: Boolean): List<VideoItem>? {
        if (!VideoScan.hasPermission(app)) return null
        if (!refresh) VideoScan.cache?.let { return it }
        return try { VideoScan.scan(app).also { VideoScan.cache = it } } catch (_: Throwable) { VideoScan.cache }
    }

    /** (v543 م3) فيديو من مكتبة التلفزيون بالـid الرقمي (MediaStore) أو بالـvideoId ("f:name:size") — للينكات tv://<id> */
    fun findVideo(app: Context, key: String): VideoItem? {
        val l = items(app, false) ?: return null
        val id = key.toLongOrNull()
        return if (id != null) l.firstOrNull { it.id == id } else l.firstOrNull { it.videoId == key }
    }

    fun libFolders(app: Context, r: HttpReq): HttpRes {
        val l = items(app, r.query["refresh"] == "1") ?: return jerr(403, "perm")
        val a = JSONArray()
        for ((k, vs) in l.groupBy { it.folderKey }) {
            val f = vs.first()
            a.put(JSONObject().put("key", k).put("name", f.folderName).put("path", f.folderPath)
                .put("count", vs.size).put("size", vs.sumOf { it.size }).put("newest", vs.maxOf { it.dateMs }))
        }
        return jok(JSONObject().put("folders", a))
    }

    fun libVideos(app: Context, r: HttpReq): HttpRes {
        val l = items(app, false) ?: return jerr(403, "perm")
        val key = r.query["folder"] ?: ""
        val a = JSONArray()
        for (v in l) if (key.isEmpty() || v.folderKey == key)
            a.put(JSONObject().put("id", v.id).put("name", v.name).put("title", v.title).put("size", v.size).put("durMs", v.durMs)
                .put("added", v.added).put("modified", v.modified).put("w", v.w).put("h", v.h).put("vid", v.videoId).put("ext", v.ext))
        return jok(JSONObject().put("videos", a))
    }

    fun libThumb(app: Context, r: HttpReq): HttpRes {
        val id = r.query["id"]?.toLongOrNull() ?: return jerr(403, "bad")
        val vi = items(app, false)?.firstOrNull { it.id == id } ?: return jerr(404, "nf")
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        Thumbs.load(app, vi) { b -> bmp = b; latch.countDown() }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        val b = bmp ?: return HttpRes(204)
        val bo = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 70, bo)
        return HttpRes(200, "image/jpeg", bo.toByteArray())
    }

    // ===== الإعدادات (قايمة بيضاء؛ المفاتيح الحساسة عبر /keys بس) =====
    private val allowedExact = setOf("conv_dialect", "loop", "base_lang", "keepgoing", "chunk", "trstart", "ask_bg_exit")
    private val allowedPrefix = listOf("sub_", "tone_", "tr_", "stt_", "off_", "wtr")
    fun allowed(k: String): Boolean =
        k.isNotBlank() && !k.contains("key", true) && !k.contains("token", true) && (k in allowedExact || allowedPrefix.any { k.startsWith(it) })

    fun cfg(r: HttpReq): HttpRes {
        if (r.method == "POST") {
            val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return jerr(403, "bad") }
            val set = j.optJSONObject("set") ?: return jerr(403, "noset")
            var n = 0
            val ks = set.keys()
            while (ks.hasNext()) { val k = ks.next(); if (allowed(k)) { Cfg.put(k, set.optString(k)); n++ } }
            if (n > 0) try { PlayerActivity.brain("conf.reload", JSONObject()) } catch (e: Throwable) { LogStore.err("BrainRpc:cfg", e) }
            return jok(JSONObject().put("n", n))
        }
        val o = JSONObject()
        for (k in (r.query["keys"] ?: "").split(',')) if (allowed(k)) o.put(k, Cfg.str(k, ""))
        return jok(JSONObject().put("cfg", o))
    }
}
