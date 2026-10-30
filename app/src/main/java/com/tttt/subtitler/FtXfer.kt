package com.tttt.subtitler

import android.content.Context
import android.media.MediaScannerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * (v653) محرّكات النقل. الشغل كله في مهام TaskCenter (kind=dm): بيفضل شغّال في الخلفية (TaskService) ويظهر في صفحة المهام،
 * والواجهة بتتابع الجلسة (FtSession) مباشرة.
 */

/** إرسال لجهاز شغّال عليه النقل الذكي: عرض موقّع ← موافقة المستلم ← أجزاء مشفّرة بيسحبها هو ← هاش */
object FtSend {
    fun start(app: Context, peer: FtPeer, picks: List<FtPick>, retry: Boolean = false): FtSession {
        val a = app.applicationContext
        FtIdentity.init(a); FtStore.init(a)
        val files = picks.mapIndexed { i, p ->
            FtFile(i, p.rel, p.size, XCat.catOf(p.rel), UriSrc(a, p.uri, p.size, p.rel.substringAfterLast('/'))).also { it.srcRef = p.src }
        }
        val s = FtSession(FtCrypto.randHex(16), true, peer.id, peer.name, peer.type, peer.ip, peer.port, peer.fp, files)
        s.retry = retry
        FtHub.add(s)
        TaskCenter.add(a, "dm", "📤 " + peer.name) { t -> work(a, t, s, peer) }
        return s
    }

    private fun failAll(s: FtSession, st: Int, msg: String) {
        for (f in s.files) { if (f.state == FtState.WAIT || f.state == FtState.RUN) { f.state = if (st == FtSession.CANCELLED) FtState.CANCEL else FtState.FAIL; f.err = msg } }
        s.finish(st, msg)
        FtLog.all(s)
        FtHub.changed()
    }

    private fun postCancel(s: FtSession) {
        val key = s.key
        val hd = if (key != null) mapOf("X-Ft-T" to FtCrypto.tag(key, s.sid, "x", 0, 0L)) else emptyMap()
        try { FtNet.call(s.peerIp, s.peerPort, "/ft/x", JSONObject().put("sid", s.sid).toString(), 2500, hd) } catch (_: Throwable) {}
    }

    private fun work(app: Context, t: TaskItem, s: FtSession, peer: FtPeer) {
        if (s.ended) { t.msg = s.msg; return }
        t.onCancel = { s.cancelled = true }
        try {
            s.status = FtSession.WAITING; s.msg = "مستني «" + peer.name + "» يوافق…"; t.msg = s.msg
            TaskCenter.changed(); FtHub.changed()
            val me = FtCrypto.identity(app)
            val myFp = FtCrypto.fingerprint(me.public.encoded)
            val ek = FtCrypto.newKeyPair()
            val epubS = FtCrypto.b64(ek.public.encoded)
            val sig = FtCrypto.b64(FtCrypto.sign(me.private, (s.sid + epubS).toByteArray(Charsets.UTF_8)))
            val arr = JSONArray()
            for (f in s.files) arr.put(JSONObject().put("n", f.rel).put("s", f.size))
            val from = JSONObject().put("id", FtIdentity.id()).put("name", FtIdentity.name()).put("type", FtIdentity.type())
                .put("port", FtPeers.PORT).put("pub", FtCrypto.b64(me.public.encoded))
            val body = JSONObject().put("v", FtPeers.VER).put("sid", s.sid).put("from", from).put("epub", epubS).put("sig", sig)
                .put("files", arr).put("retry", s.retry).toString()
            if (body.length > 250_000) throw IOException("القايمة كبيرة — ابعت على دفعات أصغر")

            // الطلب بيستنى موافقة المستخدم (لحد ~100 ثانية) — في خيط جانبي عشان الإلغاء يفضل شغّال
            var resp: Pair<Int, String> = Pair(-1, "")
            val th = Thread { resp = FtNet.call(peer.ip, peer.port, "/ft/offer", body, 105_000) }
            th.isDaemon = true; th.start()
            while (th.isAlive) {
                if (s.cancelled || t.cancelled) { s.cancelled = true; postCancel(s); failAll(s, FtSession.CANCELLED, "ألغيت الإرسال"); throw TaskCancelled() }
                th.join(300)
            }
            val code = resp.first
            if (code == 409) throw IOException("«" + peer.name + "» مشغول بطلبات تانية — جرّب كمان شوية")
            if (code == 400) throw IOException("«" + peer.name + "» محتاج تحديث للنسخة دي")
            if (code != 200) throw IOException(if (code < 0) "ما قدرتش أوصل لـ «" + peer.name + "»" else "«" + peer.name + "» رفض الاتصال ($code)")
            val j = JSONObject(resp.second)
            if (!j.optBoolean("accepted")) {
                val timeout = j.optString("reason") == "timeout"
                failAll(s, FtSession.REJECTED, if (timeout) "«" + peer.name + "» ما ردّش" else "«" + peer.name + "» رفض الاستقبال")
                t.msg = s.msg
                return
            }

            // تحقق من هوية المستلم + توقيعه على تبادل المفاتيح
            val rPubB = FtCrypto.unb64(j.optString("pub"))
            val rFp = FtCrypto.fingerprint(rPubB)
            if (peer.fp.isNotEmpty() && rFp != peer.fp) throw SecurityException("هوية الجهاز اتغيّرت بين البحث والإرسال — ألغيت العملية")
            val epubR = j.optString("epub")
            if (!FtCrypto.verify(FtCrypto.pubOf(rPubB), (s.sid + epubS + epubR).toByteArray(Charsets.UTF_8), FtCrypto.unb64(j.optString("sig"))))
                throw SecurityException("توقيع الرد غلط — ألغيت العملية")
            val secret = FtCrypto.ecdh(ek.private, FtCrypto.pubOf(FtCrypto.unb64(epubR)))
            val key = FtCrypto.sessionKey(secret, s.sid)
            s.sas = FtCrypto.sas(myFp, rFp, FtCrypto.unb64(epubS), FtCrypto.unb64(epubR))
            val acc = HashSet<Int>()
            val ja = j.optJSONArray("acc")
            if (ja != null) for (i in 0 until ja.length()) acc.add(ja.optInt(i, -1))
            for (f in s.files) { if (f.idx !in acc) { f.accepted = false; f.state = FtState.CANCEL; f.err = "المستلم استبعده" } }
            s.key = key
            s.startedAt = System.currentTimeMillis(); s.lastTouch = s.startedAt
            s.status = FtSession.RUNNING; s.msg = "بيتبعت…"
            FtHub.changed()

            var lastUi = 0L
            while (true) {
                if (s.cancelled || t.cancelled) { s.cancelled = true; postCancel(s); failAll(s, FtSession.CANCELLED, "ألغيت الإرسال"); throw TaskCancelled() }
                val open = s.acceptedFiles.filter { it.state == FtState.WAIT || it.state == FtState.RUN }
                if (open.isEmpty()) break
                if (System.currentTimeMillis() - s.lastTouch > 120_000L) {
                    for (f in open) { f.state = FtState.FAIL; f.err = "«" + peer.name + "» وقف السحب" }
                    FtLog.all(s)
                    break
                }
                s.sample()
                val now = System.currentTimeMillis()
                if (now - lastUi > 500L) {
                    lastUi = now
                    t.pct = s.percent().coerceIn(0, 99)
                    t.msg = Sniff.fmtSize(s.doneBytes()) + " / " + Sniff.fmtSize(s.totalBytes())
                    TaskCenter.changed(); FtHub.changed()
                }
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
            }
            val okN = s.doneFiles()
            val bad = s.acceptedFiles.size - okN
            FtLog.all(s)
            if (okN == 0) { s.finish(FtSession.FAILED, "مفيش ملف اتبعت"); t.msg = s.msg; FtHub.changed(); throw IOException(s.msg) }
            s.finish(FtSession.DONE, if (bad > 0) "خلص — $bad ملف فشل" else "خلص الإرسال")
            t.msg = s.msg
            FtHub.changed()
        } catch (e: TaskCancelled) { throw e
        } catch (e: Throwable) {
            LogStore.err("FtSend", e)
            if (!s.ended) failAll(s, FtSession.FAILED, (e.message ?: "فشل الإرسال").take(140))
            throw e
        }
    }
}

/** استقبال: بعد موافقة المستخدم بيسحب الأجزاء المشفّرة ويفكّها ويتحقق بالهاش وبعدها يحفظ (من غير ما يكتب فوق أي ملف) */
object FtRecv {
    private class Stop : RuntimeException()

    fun start(app: Context, s: FtSession) {
        TaskCenter.add(app.applicationContext, "dm", "📥 " + s.peerName) { t -> work(app.applicationContext, t, s) }
    }

    private fun destDir(app: Context, s: FtSession, f: FtFile): File {
        val sub = f.rel.substringBeforeLast('/', "")
        val root = if (sub.isNotEmpty() || s.saveMode == 1) Xfer.inboxDir(app, XCat.OTHER) else Xfer.inboxDir(app, XCat.catOf(f.name))
        val d = if (sub.isEmpty()) root else File(root, sub)
        d.mkdirs()
        return d
    }

    private fun work(app: Context, t: TaskItem, s: FtSession) {
        if (s.ended) { t.msg = s.msg; return }
        t.onCancel = { s.cancelled = true }
        val key = s.key
        if (key == null) { s.finish(FtSession.FAILED, "مفيش مفتاح جلسة"); FtHub.changed(); throw IOException(s.msg) }
        try { Thread.sleep(500) } catch (_: InterruptedException) {}   // نسيب المرسل يخلّص تبادل المفاتيح
        var lastUi = 0L
        for (f in s.acceptedFiles) {
            if (f.state == FtState.DONE) continue
            if (s.cancelled || t.cancelled) break
            f.state = FtState.RUN
            try {
                recvFile(app, t, s, f, key) {
                    s.sample()
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 400L) {
                        lastUi = now
                        t.pct = s.percent().coerceIn(0, 99)
                        t.msg = Sniff.fmtSize(s.doneBytes()) + " / " + Sniff.fmtSize(s.totalBytes())
                        TaskCenter.changed(); FtHub.changed()
                    }
                }
                f.state = FtState.DONE; f.done = f.size; f.err = ""
                notifyDone(s, key, f, true, "")
            } catch (_: Stop) { f.state = FtState.CANCEL; f.err = "اتلغى"
            } catch (e: Throwable) {
                if (s.cancelled || t.cancelled) { f.state = FtState.CANCEL; f.err = "اتلغى" }
                else {
                    f.state = FtState.FAIL; f.err = (e.message ?: "فشل").take(120)
                    LogStore.err("FtRecv", e)
                    notifyDone(s, key, f, false, f.err)
                }
            }
            FtLog.file(s, f)
            FtHub.changed()
        }
        if (s.cancelled || t.cancelled) {
            s.cancelled = true
            try { FtNet.call(s.peerIp, s.peerPort, "/ft/x", JSONObject().put("sid", s.sid).toString(), 2500, mapOf("X-Ft-T" to FtCrypto.tag(key, s.sid, "x", 0, 0L))) } catch (_: Throwable) {}
            for (f in s.files) { if (f.state == FtState.WAIT || f.state == FtState.RUN) { f.state = FtState.CANCEL; f.err = "اتلغى" } }
            s.finish(FtSession.CANCELLED, "اتلغى الاستقبال")
            FtLog.all(s); FtHub.changed()
            throw TaskCancelled()
        }
        val okN = s.doneFiles()
        val bad = s.acceptedFiles.size - okN
        FtLog.all(s)
        if (okN == 0) { s.finish(FtSession.FAILED, s.acceptedFiles.firstOrNull { it.err.isNotEmpty() }?.err ?: "فشل الاستقبال"); t.msg = s.msg; FtHub.changed(); throw IOException(s.msg) }
        s.finish(FtSession.DONE, if (bad > 0) "خلص — $bad ملف فشل" else "خلص الاستقبال")
        t.msg = s.msg
        FtHub.changed()
        try { Notice.show(app, "✅ وصل " + okN + " ملف من «" + s.peerName + "»", 3500L) } catch (_: Throwable) {}
    }

    private fun notifyDone(s: FtSession, key: ByteArray, f: FtFile, ok: Boolean, err: String) {
        try {
            FtNet.call(s.peerIp, s.peerPort, "/ft/d", JSONObject().put("sid", s.sid).put("f", f.idx).put("st", if (ok) "ok" else "fail").put("err", err).toString(), 4000,
                mapOf("X-Ft-T" to FtCrypto.tag(key, s.sid, "d", f.idx, 0L)))
        } catch (_: Throwable) {}
    }

    private fun recvFile(app: Context, t: TaskItem, s: FtSession, f: FtFile, key: ByteArray, progress: () -> Unit) {
        val dir = destDir(app, s, f)
        val tag = FtCrypto.hex(FtCrypto.sha256((s.peerId + "|" + f.rel + "|" + f.size).toByteArray(Charsets.UTF_8))).take(10)
        val part = File(dir, Xfer.safeName(f.name) + "." + tag + ".ftpart")
        val total = f.size
        val chunks = (total + FtCrypto.CHUNK - 1L) / FtCrypto.CHUNK
        val md = MessageDigest.getInstance("SHA-256")
        var next = 0L

        // استكمال: لو فيه جزء متحمّل من محاولة قبل كده من نفس الجهاز لنفس الملف، نكمّل من آخر حدّ جزء سليم
        if (part.exists()) {
            val have = part.length()
            val keepBytes = if (have >= total) total else (have / FtCrypto.CHUNK) * FtCrypto.CHUNK
            if (total == 0L || keepBytes <= 0L) part.delete()
            else {
                if (have != keepBytes) RandomAccessFile(part, "rw").use { it.setLength(keepBytes) }
                FileInputStream(part).use { fi ->
                    val b = ByteArray(262144)
                    var left = keepBytes
                    while (left > 0L) {
                        if (s.cancelled || t.cancelled) throw Stop()
                        val k = fi.read(b, 0, minOf(b.size.toLong(), left).toInt())
                        if (k < 0) break
                        md.update(b, 0, k); left -= k
                    }
                    if (left > 0L) { part.delete(); md.reset(); next = 0L; return@use }
                }
                if (part.exists()) { next = keepBytes / FtCrypto.CHUNK; if (keepBytes >= total) next = chunks; f.done = keepBytes }
            }
        }
        if (!dir.isDirectory) dir.mkdirs()

        FileOutputStream(part, true).use { out ->
            while (next < chunks) {
                if (s.cancelled || t.cancelled) throw Stop()
                val plain = fetchChunk(t, s, f, next, key)
                out.write(plain)
                md.update(plain)
                next++
                f.done = minOf(total, next * FtCrypto.CHUNK)
                s.lastTouch = System.currentTimeMillis()
                progress()
            }
        }

        // تحقق من سلامة الملف: هاش المرسل (موقّع بمفتاح الجلسة) لازم يطابق اللي اتحسب هنا
        s.msg = "بيتحقق من «" + f.name + "»…"
        FtHub.changed()
        val local = FtCrypto.hex(md.digest())
        val (c, txt) = FtNet.call(s.peerIp, s.peerPort, "/ft/h?sid=" + s.sid + "&f=" + f.idx, null, 900_000, mapOf("X-Ft-T" to FtCrypto.tag(key, s.sid, "h", f.idx, 0L)))
        s.msg = "بيستقبل…"
        if (c != 200) throw IOException("ما قدرتش أتحقق من الملف ($c) — اتحفظ الجزء المتحمّل وتقدر تعيد المحاولة")
        val jj = JSONObject(txt)
        val sha = jj.optString("sha")
        if (!FtCrypto.safeEq(FtCrypto.hmac(key, s.sid + "|H|" + f.idx + "|" + sha), jj.optString("mac"))) throw IOException("رد التحقق مش موثّق")
        if (!FtCrypto.safeEq(local, sha)) { part.delete(); throw IOException("الملف اتبوّظ في الطريق (الهاش مش متطابق) — اتمسح، أعد المحاولة") }

        val outFile = Xfer.uniqueFile(dir, Xfer.safeName(f.name))   // مابنكتبش فوق ملف موجود أبدًا
        if (!part.renameTo(outFile)) throw IOException("ما قدرتش أحفظ الملف")
        f.outPath = outFile.absolutePath
        try { MediaScannerConnection.scanFile(app, arrayOf(outFile.absolutePath), null, null) } catch (_: Throwable) {}
        try { VideoScan.cache = null } catch (_: Throwable) {}
    }

    private fun fetchChunk(t: TaskItem, s: FtSession, f: FtFile, n: Long, key: ByteArray): ByteArray {
        var tries = 0
        var bad = 0
        val path = "/ft/c?sid=" + s.sid + "&f=" + f.idx + "&n=" + n
        while (true) {
            if (s.cancelled || t.cancelled) throw Stop()
            val (c, b) = FtNet.callBytes(s.peerIp, s.peerPort, path, null, 20_000, mapOf("X-Ft-T" to FtCrypto.tag(key, s.sid, "c", f.idx, n)))
            if (c == 200) {
                try { return FtCrypto.decChunk(key, s.sid, f.idx, n, b) }
                catch (_: Throwable) { bad++; if (bad >= 3) throw IOException("جزء بايظ أو متلاعب فيه — النقل اتوقف") }
            } else if (c == 403 || c == 416 || c == 400) {
                throw IOException("المرسل رفض الطلب ($c)")
            } else if (c == 404) {
                tries++
                if (tries > 10) throw IOException("المرسل وقف الإرسال")
            } else {
                tries++
                if (tries >= 6) throw IOException("الاتصال انقطع — اتحفظ الجزء المتحمّل وتقدر تكمّل بعدين")
            }
            try { Thread.sleep(if (c == 404) 400L else 1500L) } catch (_: InterruptedException) {}
        }
    }
}

/**
 * بديل للتلفزيونات اللي لسه ما اتحدّثتش (من غير النقل الذكي): بنستخدم ربط الريموت الموجود (RemoteClient) + XferOut:
 * الموبايل بيقدّم الملف والتلفزيون بيسحبه. التقدم الفعلي من اللي التلفزيون سحبه. (من غير شاشة موافقة على التلفزيون — الموافقة هي الاقتران نفسه.)
 */
object FtLinkSend {
    /** يوصّل بالتلفزيون (يقترن لو لازم من غير كود). blocking — بيرجّع رسالة خطأ أو null */
    fun connect(app: Context, f: RemoteClient.Found): String? {
        var tv = try { RemoteClient.pairedList().firstOrNull { it.id == f.id } } catch (_: Throwable) { null }
        if (tv == null) {
            val (p, m) = RemoteClient.pair(f, "")
            if (p == null) return if (m == "need_code") "التلفزيون محتاج كود إقران — اقترن بيه الأول من مركز النقل القديم (الرادار) وبعدين ارجع" else if (m == "locked") "التلفزيون قافل الاقتران دلوقتي — جرّب بعد شوية" else m
            tv = p
        }
        tv.ip = f.ip; tv.port = f.port
        RemoteClient.saveTv(tv)
        if (RemoteClient.state != RemoteClient.St.CONNECTED || RemoteClient.current?.id != tv.id) RemoteClient.connect(app, tv, true)
        val end = System.currentTimeMillis() + 9000L
        while (System.currentTimeMillis() < end) {
            if (RemoteClient.state == RemoteClient.St.CONNECTED && RemoteClient.current?.id == tv.id) return null
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
        }
        return "ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
    }

    fun start(app: Context, peer: FtPeer, picks: List<FtPick>): FtSession {
        val a = app.applicationContext
        FtIdentity.init(a); FtStore.init(a)
        val files = picks.mapIndexed { i, p -> FtFile(i, p.rel, p.size, XCat.catOf(p.rel), null).also { it.srcRef = p.src } }
        val s = FtSession(FtCrypto.randHex(16), true, peer.id, peer.name, "tv", peer.ip, peer.port, "", files, true)
        FtHub.add(s)
        TaskCenter.add(a, "dm", "📤 " + peer.name) { t -> work(a, t, s, peer, picks) }
        return s
    }

    private fun work(app: Context, t: TaskItem, s: FtSession, peer: FtPeer, picks: List<FtPick>) {
        if (s.ended) { t.msg = s.msg; return }
        t.onCancel = { s.cancelled = true }
        var cur: XferOut.Item? = null
        try {
            s.status = FtSession.NEG; s.msg = "بوصّل بالتلفزيون…"; FtHub.changed()
            val lf = peer.link ?: throw IOException("التلفزيون مش بيدعم الربط")
            val er = connect(app, lf)
            if (er != null) throw IOException(er)
            s.startedAt = System.currentTimeMillis(); s.lastTouch = s.startedAt
            s.status = FtSession.RUNNING; s.msg = "بيتبعت…"; FtHub.changed()
            val tv = RemoteClient.current ?: throw IOException("مش متصل بالتلفزيون")
            val ip = RemoteClient.ipTowards(tv.ip)
            if (ip.isEmpty()) throw IOException("لازم الموبايل والتلفزيون على نفس الواي فاي")
            for ((i, f) in s.files.withIndex()) {
                if (s.cancelled || t.cancelled) throw TaskCancelled()
                if (f.size <= 0L) { f.state = FtState.FAIL; f.err = "حجم الملف مجهول"; FtLog.file(s, f); continue }
                f.state = FtState.RUN
                val it0 = XferOut.offer(app, picks[i].uri, f.name, f.size)
                if (it0 == null) { f.state = FtState.FAIL; f.err = "ما قدرتش أجهّز الملف"; FtLog.file(s, f); continue }
                cur = it0
                val (c, _) = RemoteClient.postJson("/pull", JSONObject().put("url", XferOut.urlFor(it0, ip)).put("name", f.name).put("size", f.size).put("cat", XCat.catOf(f.name)))
                if (c != 200) { f.state = FtState.FAIL; f.err = "التلفزيون رفض الملف ($c) — حدّث البرنامج عليه"; FtLog.file(s, f); XferOut.drop(it0); continue }
                var idle = 0; var lastMax = -1L; var lastUi = 0L
                while (true) {
                    if (s.cancelled || t.cancelled) { it0.cancelled = true; throw TaskCancelled() }
                    val m = it0.max
                    f.done = m.coerceAtMost(f.size)
                    s.sample()
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 500L) {
                        lastUi = now
                        t.pct = s.percent().coerceIn(0, 99); t.msg = Sniff.fmtSize(s.doneBytes()) + " / " + Sniff.fmtSize(s.totalBytes())
                        TaskCenter.changed(); FtHub.changed()
                    }
                    if (m >= f.size) { f.state = FtState.DONE; f.done = f.size; break }
                    idle = if (m == lastMax) idle + 1 else 0; lastMax = m
                    if (idle > 75) { f.state = FtState.FAIL; f.err = "التلفزيون وقف السحب"; break }
                    try { Thread.sleep(400) } catch (_: InterruptedException) {}
                }
                FtLog.file(s, f)
                val dropIt = it0
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ XferOut.drop(dropIt) }, 60000L)
                FtHub.changed()
            }
            val okN = s.doneFiles()
            if (okN == 0) { s.finish(FtSession.FAILED, s.files.firstOrNull { it.err.isNotEmpty() }?.err ?: "مفيش ملف اتبعت"); FtHub.changed(); throw IOException(s.msg) }
            s.finish(FtSession.DONE, if (okN < s.files.size) "خلص — " + (s.files.size - okN) + " ملف فشل" else "خلص الإرسال")
            t.msg = s.msg
            FtHub.changed()
        } catch (e: TaskCancelled) {
            cur?.cancelled = true
            for (f in s.files) { if (f.state == FtState.WAIT || f.state == FtState.RUN) { f.state = FtState.CANCEL; f.err = "اتلغى" } }
            s.finish(FtSession.CANCELLED, "ألغيت الإرسال"); FtLog.all(s); FtHub.changed()
            throw e
        } catch (e: Throwable) {
            LogStore.err("FtLinkSend", e)
            if (!s.ended) {
                for (f in s.files) { if (f.state == FtState.WAIT || f.state == FtState.RUN) { f.state = FtState.FAIL; f.err = (e.message ?: "فشل").take(120) } }
                s.finish(FtSession.FAILED, (e.message ?: "فشل الإرسال").take(140)); FtLog.all(s); FtHub.changed()
            }
            throw e
        }
    }
}
