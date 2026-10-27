@file:OptIn(UnstableApi::class)
package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational

/** مشغّل الفيديو والترجمة (اتنقل من Main.kt في v100 — من غير أي تغيير في الكود) */
class PlayerActivity : Activity(), Host {
    // (v507) ريزوليوشن ديناميك لوضع التلفزيون
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(TvScale.wrap(newBase)) }
    companion object {
        private val liveList = ArrayList<java.lang.ref.WeakReference<PlayerActivity>>()
        /** (v518) ريموت الموبايل: تشغيل/إيقاف المشغّل الشغّال. بيرجّع false لو مفيش مشغّل فيديو مفتوح */
        fun remoteToggle(): Boolean {
            val o = synchronized(liveList) { liveList.mapNotNull { it.get() }.lastOrNull { !it.isFinishing && !it.isDestroyed } } ?: return false
            try { o.runOnUiThread { try { o.togglePlay() } catch (_: Throwable) {} } } catch (_: Throwable) { return false }
            return true
        }

        /** (v531) في مشغّل فيديو (برنامج) مفتوح دلوقتي؟ */
        fun hasLive(): Boolean = live() != null

        private fun live(): PlayerActivity? = synchronized(liveList) { liveList.mapNotNull { it.get() }.lastOrNull { !it.isFinishing && !it.isDestroyed } }

        private fun <T> onUi(o: PlayerActivity, ms: Long = 900L, f: () -> T): T? {
            val box = arrayOfNulls<Any>(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            try { o.runOnUiThread { try { box[0] = f() } catch (e: Throwable) { LogStore.err("Player:remote", e) }; latch.countDown() } } catch (_: Throwable) { return null }
            @Suppress("UNCHECKED_CAST")
            return if (latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)) box[0] as T? else null
        }

        /** (v520) حالة المشغّل للموبايل: عنوان · مكان · مدة · تشغيل · سرعة · احتواء · ترجمة · حجم/إزاحة الترجمة · صوت/جودة. null = مفيش مشغّل */
        /** (v596) الملف المحلي اللي المشغّل شغّاله (للسماعة الخاصة على الموبايل) */
        fun curUri(): Uri? = live()?.uri

        fun remoteState(): org.json.JSONObject? { val o = live() ?: return null; return onUi(o) { o.rState() } }

        /** (v520) أوامر المشغّل من الموبايل. v = قيمة (مللي ثانية / اتجاه) */
        fun remoteAct(a: String, v: Long): Boolean { val o = live() ?: return false; return onUi(o) { o.rAct(a, v) } == true }

        /** (v542) م1: RPC للتلفزيون-المخ. عمليات engine.* بتتنفّذ على خيط الطلب (مش الـUI)، والباقي على الـUI بمهلة 3 ثواني. null = مفيش مشغّل */
        fun brain(op: String, a: org.json.JSONObject): org.json.JSONObject? {
            val o = live() ?: return null
            return if (op.startsWith("engine.")) o.rpcBg(op, a) else onUi(o, 3000L) { o.rpc(op, a) }
        }

        /** (v542) لقطة حية من الفيديو كـJPEG (بتتنادى من خيط خلفي). null = مفيش لقطة (DRM/أسود/مفيش مشغّل) */
        fun brainFrame(maxDim: Int, q: Int): ByteArray? { val o = live() ?: return null; return o.frameJpeg(maxDim, q) }
    }

    private fun rState(): org.json.JSONObject {
        val j = org.json.JSONObject()
        j.put("title", try { notifTitle() } catch (_: Throwable) { "" })
        j.put("pos", try { player.currentPosition } catch (_: Throwable) { 0L })
        j.put("dur", durMs)
        j.put("playing", try { player.isPlaying } catch (_: Throwable) { false })
        j.put("speed", PlayerLogic.speedLabel(speed))
        j.put("fit", fitNameOf(curFit()))
        j.put("cc", ccOn)
        j.put("scale", Cfg.int("sub_scale", 100))
        j.put("vol", try { volTotal() } catch (_: Throwable) { -1 })   // (v568) صوت التلفزيون الحالي (0..200) — المتحكم على الموبايل يعرضه ويغيّره
        j.put("shift", offsetMs)
        j.put("audio", aTracks.size > 1)
        j.put("quality", qualPopFn != null && (qOpts.size > 1 || uri == null))
        j.put("tr", tvTrLabel())
        j.put("sf_side", sfSideName()); j.put("sf_nat", sfNativeCount()); j.put("sf_live", sfLive); j.put("sf_busy", sfBusy)   // (v529)
        // (v531) شاشة «مشغّل الفيديو» في الريموت = نفس تقسيمة مشغّل الموبايل: كل حالة الأزرار والكبسولات
        j.put("kind", "player")
        // (v596) مصدر الصوت للسماعة الخاصة: لينك http (مع الهيدرز) أو "local" = ملف على التلفزيون بيتقدّم من /file/cur
        try {
            val au = aurl ?: url ?: uri?.toString()?.takeIf { it.startsWith("http") }
            if (au != null && au.startsWith("http")) { j.put("src", au); j.put("hdr", org.json.JSONObject(HashMap<String, String>(hdr) as Map<*, *>)) }
            else if (uri != null) j.put("src", "local")
        } catch (_: Throwable) {}
        j.put("vw", vidW); j.put("vh", vidH)   // (v546)
        j.put("loop", Cfg.str("loop", "0") == "1")
        j.put("dual", try { SubStyle.load { k, d -> Cfg.str(k, d) }.dual } catch (_: Throwable) { 0 })
        j.put("bgon", Cfg.bool("ask_bg_exit", true))
        j.put("nots", try { NoticeLog.list().size } catch (_: Throwable) { 0 })
        j.put("locked", uiLocked)
        j.put("font", try { val f0 = SubStyle.load { k, d -> Cfg.str(k, d) }.font; SubStyle.fonts.firstOrNull { it.id == f0 }?.label ?: f0 } catch (_: Throwable) { "" })
        j.put("eng", try { TrWizard.summaryLine() } catch (_: Throwable) { "" })
        j.put("cnt", try { PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0).toString() + "% · " + list.size.toString() + " جملة" } catch (_: Throwable) { "" })
        j.put("trcolor", try { tvTrColor() } catch (_: Throwable) { 0xFFE53935.toInt() })
        // (v532) قوايم الجودة ولغة الصوت كنصوص + الحالي: الريموت بيعرضها على الموبايل ويختار منها من غير ما يفتح قايمة على شاشة التلفزيون
        try { val (ql, qc) = rQuals(); j.put("ql", org.json.JSONArray(ql)); j.put("qc", qc) } catch (_: Throwable) {}
        try { j.put("al", org.json.JSONArray(aTracks.map { it.name })); j.put("ac", aCur) } catch (_: Throwable) {}
        return j
    }

    // ===== (v542) م1: التلفزيون = المخ — نشر الحالة + أوامر RPC =====
    private var brainAt = 0L
    private var brainSubsRef: List<Sub>? = null
    private var brainEngAt = 0L
    private val brainEngBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val brainBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** بتتنادى من الـtick (200ms). مفيش شغل خالص لو مفيش ريموت بيسأل (/sync). الحساب التقيل للترجمة على خيط خلفي */
    private fun brainTick() {
        if (!BrainBus.active()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - brainAt < 500L) return
        brainAt = now
        try {
            val st = rState()
            st.put("vid", vid); st.put("ready", engineReady); st.put("started", engineStarted)
            BrainBus.setState(st.toString())
        } catch (_: Throwable) {}
        try {
            if (::engine.isInitialized) {
                val sr = engine.subs
                if (sr !== brainSubsRef && brainBusy.compareAndSet(false, true)) {
                    brainSubsRef = sr
                    Thread {
                        try { BrainBus.updateSubs(sr) } catch (e: Throwable) { LogStore.err("Player:brainSubs", e) } finally { brainBusy.set(false) }
                    }.start()
                }
            }
        } catch (_: Throwable) {}
        try {
            if (::engine.isInitialized && now - brainEngAt >= 1500L && brainEngBusy.compareAndSet(false, true)) {
                brainEngAt = now
                val e = engine
                Thread {
                    try {
                        val snap = BrainEngineJson.Snap(
                            e.coverageSegs(), e.pendingSegs(), e.refiningSegs.toList(), e.batches(), e.problems(), e.batchRows(),
                            e.failedCount(), e.fullyDone(), e.offlineBrief(), e.offlineDetail(),
                            covered = e.coveredSec(), pendingCount = e.pendingCount(), userPaused = e.userPaused, paused = e.paused,
                            transOnly = e.transOnly, keepGoing = e.keepGoing, convDialect = e.convDialect, toneStyle = e.toneStyle,
                            toneStrength = e.toneStrength, titleHint = e.titleHint, skipIfBaseLang = e.skipIfBaseLang,
                            baseSkipped = e.baseSkipped, baseLangCode = e.baseLangCode,
                            versions = e.versions.map { VerRow(it.name, it.dialect, it.subs.size) }, chars = e.charactersNow()
                        )
                        if (engine === e) BrainBus.setEngine(BrainEngineJson.encode(snap))
                    } catch (ex: Throwable) { LogStore.err("Player:brainEng", ex) } finally { brainEngBusy.set(false) }
                }.start()
            }
        } catch (_: Throwable) {}
    }

    // ===== (v546 م2-ج) وضع المتحكم: التشغيل والمحرك والترجمة على التلفزيون، والموبايل واجهة المشغّل الكاملة =====
    // قاعدة ب-1: مفيش شبكة على الـUI. الحالة من LinkSession.mirror، والأوامر async، واللقطة الحية من خيط خلفي.
    @Volatile private var rmFrames = false
    private var rmLsn: (() -> Unit)? = null
    private var rmPlaying = false
    private var rmLiveSeen = false
    private var rmLostAt = 0L
    private var rmDownAt = 0L
    private var rmOpenAt = 0L
    private var rmBeginAt = 0L
    private var rmTitle = ""
    private var rmFrameErr = 0
    private var mirror: MirrorPreview? = null   // (v570) فيديو من الموبايل نفسه على التلفزيون: الموبايل بيشغّل نفس الملف بكتم بدل لقطات JPEG

    /** الوضع البصري على الموبايل متعطّل في وضع المتحكم (بيشتغل على التلفزيون) */
    private fun visualStart() {
        if (remote) { Notice.show(this, "📺 الوضع البصري شغّال على التلفزيون", 2200L); return }
        visual.start()
    }

    private fun buildRemotePlayer() {
        handedOff = false
        player = RemotePlayback()
        rmOpenAt = android.os.SystemClock.uptimeMillis()
        status = "📺 متحكم في التلفزيون"
        try { LinkSession.start(this) } catch (e: Throwable) { LogStore.err("Player:remoteStart", e) }
        if (rmLsn == null) { val l: () -> Unit = { onRemoteChange() }; rmLsn = l; LinkSession.addListener(l) }
        // (v570) لو الفيديو ملف على الموبايل نفسه: نشغّله عندنا بنفس جودته الأصلية وبسلاسة (متزامن مع التلفزيون)؛ غير كده لقطات التلفزيون زي الأول
        val mu = intent.getStringExtra("mirror_uri") ?: ""
        var mirrored = false
        if (mu.isNotEmpty() && ::svRef.isInitialized) {
            try {
                val mp = MirrorPreview(this) { w, hh -> applyRemoteSize(w, hh) }
                mp.start(svRef, if (mu.startsWith("/")) Uri.fromFile(File(mu)) else Uri.parse(mu))
                mirror = mp; mirrored = true
            } catch (e: Throwable) { LogStore.err("Player:mirror", e); try { mirror?.release() } catch (_: Throwable) {}; mirror = null }
        }
        if (!mirrored) startRemoteFrames()
        h.post { onRemoteChange() }
    }

    private fun initRemoteEngine() {
        vid = LinkSession.mirror.st.optString("vid", "")
        engineReady = false; pendingBegin = false; engineStarted = false
        engine = RemoteEngine()
        visual = VisualMode({ visConfNow() }, { cur / 1000.0 }, { makeRetriever() }, { m -> visLog(m) }, { }, { grabSurface() }, log = { m -> visLog(m) })
        status = "📺 متحكم في التلفزيون"
        h.post { onRemoteChange() }
    }

    /** بتتنادى على الـUI كل ما /sync يجيب تغيير: تحديث زرار التشغيل والعنوان والسرعة وحالة الترجمة */
    private fun onRemoteChange() {
        if (!remote || isFinishing || isDestroyed || !::player.isInitialized || !::ctl.isInitialized) return
        val m = LinkSession.mirror; val st = m.st
        dirty = true
        if (m.gotFirst && m.live) { rmLiveSeen = true; rmLostAt = 0L }
        val ready = m.gotFirst && m.live && st.optLong("dur", 0L) > 0L
        if (ready && !engineReady) { engineReady = true; vid = st.optString("vid", vid) }
        val tt = st.optString("title", "")
        if (tt.isNotEmpty() && tt != rmTitle) { rmTitle = tt; notifTitleOv = tt; try { applyChromeFn() } catch (_: Throwable) {} }
        val tvVol = st.optInt("vol", -1)
        if (tvVol >= 0 && System.currentTimeMillis() - rmVolAt > 1500L) { rmVolP = tvVol; try { if (ctl.vol.progress != tvVol) ctl.vol.progress = tvVol } catch (_: Throwable) {} }
        val sp = PosExtrap.speedOf(st.optString("speed", "1")).toFloat()
        if (Math.abs(sp - speed) > 0.01f) { speed = sp; speedBRef?.text = "⏩ " + PlayerLogic.speedLabel(speed) }
        val started = st.optBoolean("started", false)
        if (started != engineStarted && System.currentTimeMillis() - rmBeginAt > 3000L) { engineStarted = started; updateTr() }
        setBuffering(!ready)
        val pl = player.isPlaying
        if (pl != rmPlaying) {
            rmPlaying = pl
            val tx = if (pl) "▮▮" else "▶"
            if (!buffering) { ctl.play.text = tx; fsPlayB.text = tx; if (::centerPlay.isInitialized) centerPlay.text = tx }
        }
    }

    /** من الـtick (200ms): انقطاع الاتصال، أو التلفزيون قفل الفيديو، أو التلفزيون ما فتحش الفيديو أصلًا */
    private fun remoteWatch() {
        mirror?.tick(resumedNow && !pipNow())   // (v570)
        val now = android.os.SystemClock.uptimeMillis()
        val m = LinkSession.mirror
        if (!LinkSession.connected) {
            if (rmDownAt == 0L) rmDownAt = now
            if (now - rmDownAt > 2500L) status = "📺 التلفزيون مش متصل — بحاول أوصّل تاني…"
            if (!rmLiveSeen && now - rmOpenAt > 30000L && !isFinishing) { Notice.show(this, "📺 ما قدرتش أوصل للتلفزيون", 2800L); finish() }
            return
        }
        if (rmDownAt != 0L) { rmDownAt = 0L; status = "📺 متحكم في التلفزيون" }
        if (m.gotFirst && !m.live) {
            if (rmLiveSeen) {
                if (rmLostAt == 0L) rmLostAt = now
                else if (now - rmLostAt > 4000L && !isFinishing) { Notice.show(this, "📺 التلفزيون قفل الفيديو", 2500L); finish() }
            } else if (now - rmOpenAt > 30000L && !isFinishing) { Notice.show(this, "📺 التلفزيون ما فتحش الفيديو", 2800L); finish() }
        } else rmLostAt = 0L
    }

    /** لقطة حية من التلفزيون (/frame) — خيط خلفي واحد. بتبطّأ لوحدها لو الشبكة بطيئة، وواقف = لقطة كل 6ث أو بعد seek */
    private fun startRemoteFrames() {
        if (rmFrames) return
        rmFrames = true
        Thread {
            var lastSig = ""; var lastAt = 0L; var fails = 0
            while (rmFrames && !isFinishing && !isDestroyed) {
                try {
                    val m = LinkSession.mirror
                    val now = System.currentTimeMillis()
                    if (!resumedNow || !m.live || !LinkSession.connected || pipNow()) { Thread.sleep(500L); continue }
                    val playing = m.st.optBoolean("playing", false)
                    val sig = m.st.optString("vid", "") + ":" + (m.position(now) / 1500L)
                    val due = if (rmFrameForce) { rmFrameForce = false; true } else if (playing) now - lastAt >= 600L else ((sig != lastSig && now - lastAt >= 350L) || now - lastAt >= 6000L)
                    if (!due) { Thread.sleep(120L); continue }
                    lastAt = now; lastSig = sig
                    val (c, b) = RemoteClient.getBytes("/frame?w=480&q=60", 6000)
                    if (c == 200 && b.isNotEmpty()) {
                        fails = 0
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
                        if (bmp != null) runOnUiThread { drawRemoteFrame(bmp) }
                    } else if (c == 204) {
                        Thread.sleep(700L)
                    } else {
                        fails += 1; Thread.sleep(minOf(400L * fails, 4000L))
                    }
                } catch (_: InterruptedException) { break }
                catch (e: Throwable) { try { Thread.sleep(800L) } catch (_: InterruptedException) { break } }
            }
        }.also { it.isDaemon = true; it.name = "RemoteFrames" }.start()
    }

    /** بيرسم اللقطة على نفس SurfaceView (من غير ExoPlayer) — مقاس السطح من مقاس اللقطة عشان الشكل يبقى زي التلفزيون */
    private var rmLastBmp: android.graphics.Bitmap? = null
    @Volatile private var rmFrameForce = false
    /** (v604) بعد التدوير السطح بيتعمل من جديد فاضي؛ والفيديو لو واقف مفيش فريم جديد جاي — فبنرسم آخر فريم تاني */
    private fun redrawLastRemoteFrame() {
        try {
            val b = rmLastBmp ?: return
            if (!remote || b.isRecycled || !::svRef.isInitialized) return
            val hd = svRef.holder; if (!hd.surface.isValid) return
            val cv = hd.lockCanvas() ?: return
            try { cv.drawBitmap(b, null, android.graphics.Rect(0, 0, cv.width, cv.height), null) } finally { hd.unlockCanvasAndPost(cv) }
        } catch (_: Throwable) {}
    }
    private fun drawRemoteFrame(bmp: android.graphics.Bitmap) {
        try {
            if (!remote || isFinishing || isDestroyed || !::svRef.isInitialized) return
            val oldB = rmLastBmp; rmLastBmp = bmp; if (oldB != null && oldB !== bmp) { try { oldB.recycle() } catch (_: Throwable) {} }
            if (bmp.width != vidW || bmp.height != vidH) {
                vidW = bmp.width; vidH = bmp.height; applyFit(svRef, videoBoxRef)
                // (v551) المتحكّم على الموبايل: الشاشة تتبع شكل فيديو التلفزيون — لاندسكيب لو الفيديو عريض، بورتريه لو طولي (مرة واحدة لكل فيديو، ومالهاش دعوة لو بدّلت بإيدك)
                if (!userRot && !autoRotDone && bmp.width > 0 && bmp.height > 0) {
                    autoRotDone = true
                    val wantLand = bmp.width >= bmp.height
                    runOnUiThread { try {
                        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        if (wantLand != isLand) requestedOrientation = if (wantLand) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    } catch (_: Throwable) {} }
                }
            }
            val hd = svRef.holder
            if (!hd.surface.isValid) return
            val cv = hd.lockCanvas() ?: return
            try { cv.drawBitmap(bmp, null, android.graphics.Rect(0, 0, cv.width, cv.height), null) } finally { hd.unlockCanvasAndPost(cv) }
            ytBusy(false)
        } catch (e: Throwable) { if (rmFrameErr++ < 3) LogStore.err("Player:remoteFrame", e) }
        finally { if (rmLastBmp !== bmp) { try { bmp.recycle() } catch (_: Throwable) {} } }
    }

    /** (v570) نفس منطق مقاس/اتجاه الشاشة بتاع drawRemoteFrame بس لمعاينة الفيديو المحلي (ExoPlayer) */
    private fun applyRemoteSize(w: Int, hh: Int) {
        try {
            if (!remote || isFinishing || isDestroyed || !::svRef.isInitialized || w <= 0 || hh <= 0) return
            if (w != vidW || hh != vidH) {
                vidW = w; vidH = hh; applyFit(svRef, videoBoxRef)
                if (!userRot && !autoRotDone) {
                    autoRotDone = true
                    val wantLand = w >= hh
                    val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    if (wantLand != isLand) requestedOrientation = if (wantLand) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
            ytBusy(false)
        } catch (e: Throwable) { LogStore.err("Player:mirrorSize", e) }
    }

    /** «▶ ترجمة» في وضع المتحكم = تستأنف/تبدأ ترجمة التلفزيون */
    private fun remoteBegin(auto: Boolean) {
        if (auto) return
        try { if (engine.fullyDone()) { Notice.show(this, "✓ الفيديو ده مترجم 100% على التلفزيون", 3000L); return } } catch (_: Throwable) {}
        dismissStrip()
        if (noSub) { noSub = false; if (!ccOn) ccToggleFn() }
        rmBeginAt = System.currentTimeMillis()
        engineStarted = true
        LinkSession.send("engine.resume")
        updateTr()
    }

    /** أوامر على الـUI thread (سريعة). النتيجة JSON صغير */
    private fun rpc(op: String, a: org.json.JSONObject): org.json.JSONObject {
        val res = org.json.JSONObject()
        when (op) {
            "state" -> res.put("st", rState())
            "act" -> res.put("handled", rAct(a.optString("a"), a.optLong("v")))
            "play" -> { if (!player.isPlaying) player.play() }
            "pause" -> player.pause()
            "seekto" -> player.seekTo(a.optLong("ms").coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE))
            "volume" -> player.volume = a.optDouble("v", 1.0).toFloat().coerceIn(0f, 1f)
            "speed.set" -> setSpeedFull(a.optDouble("v", 1.0).toFloat())
            "volume.set" -> applyVolumeP(a.optInt("p", 50))   // (v568) صوت التلفزيون من الموبايل
            "task.add" -> {
                val s = toolSrc()
                if (s == null) res.put("err", "nosrc")
                else try {
                    val q = a.optString("q", "متوسطة")
                    when (a.optString("kind")) {
                        "trim" -> Tools.trim(this, s, a.optLong("a"), a.optLong("b"), q)
                        "audio" -> Tools.audio(this, s, q)
                        "gif" -> Tools.gif(this, s, a.optLong("a"), a.optLong("b", 5000L), q)
                        "compress" -> Tools.compress(this, s, a.optInt("h", 480), q)
                        else -> res.put("err", "kind")
                    }
                } catch (e: Throwable) { LogStore.err("Player:task.add", e); res.put("err", e.message ?: "err") }
            }
            "track.audio" -> switchAudio(a.optInt("i"))
            "track.quality" -> rSetQ(a.optInt("i"))
            "conf.reload" -> {
                val nc = Cfg.snapshot(); conf = nc
                runOnUiThread { try { fit = Cfg.int("fit", fit).coerceIn(0, 3); fsFit = Cfg.int("fs_fit", fsFit).coerceIn(0, 3); if (::svRef.isInitialized) applyFit(svRef, videoBoxRef) } catch (_: Throwable) {} }   // (v568) ملاءمة الصورة من الموبايل
                runOnUiThread { try { if (::sub.isInitialized) { sub.style = natStyle(SubStyle.load { k, d -> Cfg.str(k, d) }); curIdx = -2 } } catch (_: Throwable) {} }
                runOnUiThread { try { applyChromeFn() } catch (_: Throwable) {} }   // (v604) sub_posy (ارتفاع الترجمة) كان بيتخزّن على التلفزيون من غير ما يتطبّق   // (v551) شكل الترجمة (خط/حجم/شفافية الخلفية) يتطبّق فورًا على شاشة التلفزيون
                if (::engine.isInitialized) {
                    engine.applyConf(nc)
                    engine.toneStyle = Cfg.str("tone_style", ""); engine.toneStrength = Cfg.str("tone_strength", "متوسطة")
                    val cd = Cfg.str("conv_dialect", "")
                    if (cd != engine.convDialect && !(cd.isNotEmpty() && cd == nc.lang)) engine.setConvDialect(cd, false)
                }
            }
            else -> res.put("unknown", true)
        }
        return res
    }

    /** أوامر المحرك: بتتنفّذ على خيط الطلب (مش الـUI). اللي محتاج الـUI بيتبعت بـrunOnUiThread من غير انتظار */
    private fun rpcBg(op: String, a: org.json.JSONObject): org.json.JSONObject {
        val res = org.json.JSONObject()
        if (!::engine.isInitialized) return res.put("err", "noengine")
        val e = engine
        val rep = { m: String -> BrainBus.post("opreport", op + "|" + m) }
        when (op) {
            "engine.get" -> {
                res.put("userPaused", e.userPaused).put("paused", e.paused).put("transOnly", e.transOnly)
                res.put("convDialect", e.convDialect).put("toneStyle", e.toneStyle).put("toneStrength", e.toneStrength)
                res.put("covered", e.coveredSec()).put("pending", e.pendingCount()).put("failed", e.failedCount()).put("fullyDone", e.fullyDone())
                res.put("subs", e.subs.size)
            }
            "engine.set" -> {
                val k = a.optString("name")
                when (k) {
                    "transOnly" -> e.transOnly = a.optBoolean("value")
                    "keepGoing" -> e.keepGoing = a.optBoolean("value")
                    "toneStyle" -> e.toneStyle = a.optString("value")
                    "toneStrength" -> e.toneStrength = a.optString("value")
                    "convDialect" -> e.convDialect = a.optString("value")
                    "titleHint" -> e.titleHint = a.optString("value")
                    "paused" -> e.paused = a.optBoolean("value")
                    "skipIfBaseLang" -> e.skipIfBaseLang = a.optBoolean("value")
                    "baseSkipped" -> e.baseSkipped = a.optBoolean("value")
                    "baseLangCode" -> e.baseLangCode = a.optString("value")
                    else -> res.put("unknown", true)
                }
            }
            "engine.characters" -> res.put("chars", BrainChars.encode(e.charactersNow()))
            "engine.analyzeChars" -> e.analyzeCharsNow(rep) { l -> BrainBus.post("opdone", op + "|" + BrainChars.encode(l).toString()) }
            "engine.pause" -> runOnUiThread { pauseTranslate() }
            "engine.resume" -> runOnUiThread { beginTranslate() }
            "engine.translateFrom" -> { e.translateFrom(a.optDouble("sec", 0.0)); runOnUiThread { engineKick() } }
            "engine.translateFromStart" -> { e.translateFromStart(); runOnUiThread { engineKick() } }
            "engine.redoAll" -> { e.redoAll(a.optBoolean("keep", true)); runOnUiThread { engineKick() } }
            "engine.redoFrom" -> { e.redoFrom(a.optInt("i"), a.optBoolean("keep", true)); runOnUiThread { engineKick() } }
            "engine.redoOnly" -> { e.redoOnly(a.optInt("i"), a.optBoolean("keep", true)); runOnUiThread { engineKick() } }
            "engine.retryProblems" -> { res.put("n", e.retryProblems()); runOnUiThread { engineKick() } }
            "engine.retryFailed" -> { e.retryFailed(); runOnUiThread { engineKick() } }
            "engine.removeDuplicates" -> res.put("n", e.removeDuplicates())
            "engine.saveVersion" -> e.saveVersion(a.optString("name", "نسخة"), a.optString("dialect", ""))
            "engine.applyVersion" -> res.put("n", e.applyVersion(a.optInt("i")))
            "engine.setConvDialect" -> e.setConvDialect(a.optString("d"), a.optBoolean("existing", true))
            "engine.adoptDialect" -> e.adoptDialect(a.optString("d"))
            "engine.langChanged" -> e.langChanged(a.optString("lang"), a.optDouble("pos", 0.0))
            "engine.saveNow" -> e.saveNow()
            "engine.importSubs" -> e.importSubs(SubCodec.arrayFromJson(a.optJSONArray("subs")))
            "engine.versions" -> {
                val arr = org.json.JSONArray()
                for (v in e.versions) arr.put(org.json.JSONObject().put("name", v.name).put("dialect", v.dialect).put("count", v.subs.size))
                res.put("versions", arr)
            }
            "engine.recap" -> e.recap(a.optDouble("upToSec", 0.0), rep) { txt -> BrainBus.post("opdone", op + "|" + txt) }
            "engine.rewriteAll" -> e.rewriteAll(a.optString("label"), a.optString("instruction"), a.optDouble("fromSec", 0.0), rep) { n -> BrainBus.post("opdone", op + "|" + n) }
            "engine.refineWholeNow" -> e.refineWholeNow(rep) { n -> BrainBus.post("opdone", op + "|" + n) }
            "engine.correctPronounsNow" -> e.correctPronounsNow(rep) { n -> BrainBus.post("opdone", op + "|" + n) }
            "engine.googleTranslateAll" -> e.googleTranslateAll(rep) { n -> BrainBus.post("opdone", op + "|" + n) }
            else -> res.put("unknown", true)
        }
        return res
    }

    /** لقطة حية JPEG للموبايل (من خيط خلفي — grabSurface بتستنى PixelCopy لحد 1.5ث) */
    fun frameJpeg(maxDim: Int, q: Int): ByteArray? {
        val b = try { grabSurface(maxDim) } catch (_: Throwable) { null } ?: return null
        return try {
            val bo = java.io.ByteArrayOutputStream()
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, q.coerceIn(30, 90), bo)
            bo.toByteArray()
        } catch (_: Throwable) { null } finally { try { b.recycle() } catch (_: Throwable) {} }
    }

    /** (v532) مسارات HLS/DASH بأعلى bitrate لكل ارتفاع (نفس منطق قايمة 🎞 في المشغّل) */
    private fun rHls(): List<Triple<Int, androidx.media3.common.Tracks.Group, Int>> {
        val tg = ArrayList<Triple<Int, androidx.media3.common.Tracks.Group, Int>>()
        for (g in (player.exo()?.currentTracks?.groups ?: emptyList<androidx.media3.common.Tracks.Group>())) if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO)
            for (ti in 0 until g.length) if (g.isTrackSupported(ti)) tg.add(Triple(g.getTrackFormat(ti).height, g, ti))
        return tg.filter { it.first > 0 }
            .sortedWith(compareByDescending<Triple<Int, androidx.media3.common.Tracks.Group, Int>> { it.first }.thenByDescending { it.second.getTrackFormat(it.third).bitrate })
            .distinctBy { it.first }
    }
    /** (v532) الجودات المتاحة كنصوص + رقم الحالية (يوتيوب بقايمة جوداته، أو HLS: «تلقائي» ثم الارتفاعات) */
    private fun rQuals(): Pair<List<String>, Int> {
        if (qOpts.size > 1) return qOpts.map { it.label } to qCur
        val hs = rHls()
        if (hs.size <= 1) return emptyList<String>() to 0
        val labels = listOf("تلقائي") + hs.map { it.first.toString() + "p" }
        val cur = if (hlsH == 0) 0 else 1 + hs.indexOfFirst { it.first == hlsH }.coerceAtLeast(0)
        return labels to cur
    }
    /** (v532) اختيار جودة برقمها في القايمة (من ريموت الموبايل) */
    private fun rSetQ(i: Int) {
        if (qOpts.size > 1) { switchYt(i); return }
        if (i == 0) { hlsAuto(); return }
        val q = rHls().getOrNull(i - 1) ?: return
        hlsPick(q.second, q.third, q.first)
    }

    /** (v531) مراجع أزرار شريط المشغّل (بتتسجّل وقت بناء الشريط) عشان ريموت الموبايل يدوس نفس الزرار */
    private val rcViews = HashMap<String, View>()
    private fun rcClick(k: String) { try { rcViews[k]?.performClick() } catch (e: Throwable) { LogStore.err("Player:rc:" + k, e) } }

    private fun rAct(a: String, v: Long): Boolean {
        val anchor = window.decorView
        when (a) {
            "toggle" -> togglePlay()
            "seek" -> tvSeek(v)
            "seekto" -> player.seekTo(v.coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE))
            "prev" -> stepEpisode(-1)
            "next" -> stepEpisode(1)
            "speed" -> tvCycleSpeed()
            "fit" -> cycleFitNow()
            "rotate" -> runOnUiThread { rotateVideoNow() }   // (v604) تدوير صورة الفيديو من الموبايل
            "cc" -> { ccOn = !ccOn; if (!ccOn) sub.show(null) else curIdx = -2 }
            "scale" -> tvSubScale(v.toInt())
            "shift" -> tvSubShift(v)
            "audio" -> audPopFn?.invoke(anchor)
            "quality" -> qualPopFn?.invoke(anchor)
            "qset" -> rSetQ(v.toInt())     // (v532) اختيار الجودة من قايمة الموبايل
            "aset" -> switchAudio(v.toInt())   // (v532) اختيار لغة الصوت من قايمة الموبايل
            "translate" -> translateMenu(anchor)
            "more" -> tvMoreDialog(anchor)
            "subs" -> tvSubTracksDialog(anchor)
            // (v531) أزرار الشريط الكامل اللي في مشغّل الموبايل
            "loop" -> { Cfg.put("loop", if (Cfg.str("loop", "0") == "1") "0" else "1"); say(if (Cfg.str("loop", "0") == "1") "🔂 تكرار الفيديو: شغّال" else "🔂 تكرار الفيديو: مقفول") }
            "log" -> toggleLog()
            "sent" -> { try { if (::sentDlg.isInitialized) sentDlg.show() } catch (_: Throwable) {} }
            "chars" -> showChars()
            "dual" -> rcClick("dual")
            "bgon" -> rcClick("bgon")
            "ai" -> rcClick("ai")
            "lock" -> setLock(!uiLocked)
            "close" -> { try { player.pause() } catch (_: Throwable) {}; try { saveRecent() } catch (_: Throwable) {}; try { retireEngine() } catch (_: Throwable) {}; finish() }   // (v560) كبسولة «✕» على الموبايل: اقفل الفيديو على التلفزيون
            "notice" -> noticePopup(anchor)
            "visual" -> visualSnap()
            "menu" -> tvMenuFn?.invoke(anchor)
            "tools" -> tvToolsFn?.invoke(anchor)
            "font" -> {
                val fs = SubStyle.fonts; val cf = SubStyle.load { k, d -> Cfg.str(k, d) }.font
                val n = fs[(fs.indexOfFirst { it.id == cf } + 1) % fs.size]
                Cfg.put("sub_font", n.id); try { restyleFn() } catch (_: Throwable) {}; say("🔤 " + n.label)
            }
            else -> { if (a.startsWith("subfile:")) sfRemote(a) else return false }   // (v529) 📄 ترجمة ملف الترجمة من الموبايل
        }
        return true
    }

    // ===== (v461) ريموت التلفزيون (وضع 📺 بس): يمين/شمال = تقديم/ترجيع 10ث · فوق/تحت = حجم الترجمة ·
    // أرقام = قفز بالوقت (آخر رقمين ثواني، اللي قبلهم دقايق — دوس OK أو استنى ثانيتين) · OK/تشغيل = تشغيل/إيقاف
    private val tvH = android.os.Handler(android.os.Looper.getMainLooper())
    private var tvDigits = ""
    private var tvBar: TvPlayerBar? = null
    /** (v477 م4) بناء شريط التحكم وربطه بدوال المشغّل (تلفزيون بس) */
    private fun tvInitBar(frame: FrameLayout) {
        val self = this
        val b = TvPlayerBar(this, ui, th, object : TvPlayerBar.Actions {
            override fun pos(): Long = try { player.currentPosition } catch (_: Throwable) { 0L }
            override fun dur(): Long = durMs
            override fun playing(): Boolean = try { player.isPlaying } catch (_: Throwable) { false }
            override fun toggle() { togglePlay() }
            override fun seekBy(ms: Long) { tvSeek(ms) }
            override fun seekTo(ms: Long) { try { player.seekTo(ms.coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE)) } catch (_: Throwable) {} }
            override fun prev() { stepEpisode(-1) }
            override fun next() { stepEpisode(1) }
            override fun speedLabel(): String = PlayerLogic.speedLabel(speed)
            override fun cycleSpeed() { tvCycleSpeed() }
            override fun fitLabel(): String = fitNameOf(curFit())
            override fun cycleFit() { cycleFitNow() }
            override fun ccOn(): Boolean = ccOn
            override fun toggleCc() { ccOn = !ccOn; if (!ccOn) sub.show(null) else curIdx = -2 }
            override fun subScale(d: Int) { tvSubScale(d) }
            override fun subShift(dMs: Long) { tvSubShift(dMs) }
            override fun hasAudio(): Boolean = aTracks.size > 1
            override fun audio(anchor: View) { audPopFn?.invoke(anchor) }
            // (v517) الشريط المبسّط (تلفزيون بس — التابلت شريطه القديم زي ما هو)
            override fun slim(): Boolean = DeviceUi.isTv(self)
            override fun trLabel(): String = tvTrLabel()
            override fun trColor(): Int = tvTrColor()
            override fun translate(anchor: View) { translateMenu(anchor) }
            override fun hasQuality(): Boolean = qualPopFn != null && (qOpts.size > 1 || uri == null)
            override fun quality(anchor: View) { qualPopFn?.invoke(anchor) }
            override fun subDialog() { tvSubDialog() }
            override fun more(anchor: View) { tvMoreDialog(anchor) }
            override fun subTracks(anchor: View) { tvSubTracksDialog(anchor) }
            override fun scrubPos(): Long = tvScrubTo
        })
        frame.addView(b.view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val sv = TvScrubOverlay(this, ui, th.primary)   // (v569) شريط التقديم بالأسهم (والشريط الكبير مخفي)
        frame.addView(sv.view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(ui.dp(64), 0, ui.dp(64), ui.dp(36)) })
        tvScrubOv = sv
        b.onVis = { tvChromeGate() }
        tvBar = b
    }
    // ===== (v517) شريط التلفزيون المبسّط: دوال الأزرار =====
    private var tvMenuFn: ((View) -> Unit)? = null
    private var tvToolsFn: ((View) -> Unit)? = null
    private fun tvCycleSpeed() {
        speed = PlayerLogic.nextSpeed(speed); try { player.setPlaybackSpeed(speed) } catch (_: Throwable) {}
        Cfg.p.edit().putString("speed", speed.toString()).putString("speed:$vid", speed.toString()).apply()
        speedBRef?.text = "⏩ " + PlayerLogic.speedLabel(speed)
    }
    private fun tvSubShift(dMs: Long) {
        offsetMs = (offsetMs + dMs).coerceIn(-10000L, 10000L)
        Cfg.p.edit().putString("sub_offset_ms:$vid", offsetMs.toString()).apply(); srtSoon(); curIdx = -2
        Notice.show(this, String.format("⏱ إزاحة الترجمة: %+.2fs", offsetMs / 1000.0), 1100L)
    }
    private fun tvTrLabel(): String = try {
        if (!engineStarted) "▶ ترجم"
        else (if (::engine.isInitialized && engine.userPaused) "⏸ " else if (trPct >= 100) "✅ " else "⏳ ") + trPct + "%"
    } catch (_: Throwable) { "▶ ترجم" }
    private fun tvTrColor(): Int = try {
        if (!engineStarted) 0xFFE53935.toInt()
        else if (::engine.isInitialized && engine.userPaused) 0xFF8D6E00.toInt() else if (trPct >= 100) 0xFF2E7D32.toInt() else 0xFF1F5FBF.toInt()
    } catch (_: Throwable) { 0xFFE53935.toInt() }
    /** حجم الترجمة وتوقيتها في نافذة واحدة بتفضل مفتوحة (± من غير ما تتقفل بعد كل دوسة) */
    private fun tvSubDialog() {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(26), ui.dp(22), ui.dp(26), ui.dp(22)); background = ui.box(0xF2181B20.toInt(), 0x33FFFFFF, 20)
        }
        val scaleTv = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val shiftTv = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        fun paint() {
            scaleTv.text = "🔤 حجم الترجمة: " + Cfg.int("sub_scale", 100) + "%"
            shiftTv.text = String.format("⏱ توقيت الترجمة: %+.2f ث", offsetMs / 1000.0)
        }
        fun pm(t: String, f: () -> Unit): TextView = TextView(this).apply {
            text = t; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.box(0x33FFFFFF, 0x22FFFFFF, 12); isFocusable = true; isClickable = true
            setPadding(ui.dp(26), ui.dp(8), ui.dp(26), ui.dp(8))
            setOnClickListener { f(); paint() }
        }
        fun line(lbl: TextView, minus: TextView, plus: TextView) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
            r.addView(minus, LinearLayout.LayoutParams(-2, -2)); r.addView(lbl, LinearLayout.LayoutParams(ui.dp(300), -2)); r.addView(plus, LinearLayout.LayoutParams(-2, -2))
            box.addView(r, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, ui.dp(6), 0, ui.dp(6)) })
        }
        line(scaleTv, pm("−") { tvSubScale(-10) }, pm("+") { tvSubScale(10) })
        line(shiftTv, pm("−") { tvSubShift(-250L) }, pm("+") { tvSubShift(250L) })
        paint()
        d.setContentView(box)
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setGravity(Gravity.BOTTOM); setLayout(-2, -2); attributes = attributes.apply { y = ui.dp(90) } }
        try { TvFocus.attachDialog(this, d, th.primary) } catch (_: Throwable) {}
        d.show()
    }
    /** ⋯ المزيد: الأدوات اللي مش لازمة على الشريط الأساسي */
    /** (v521) لو اتختار مسار ترجمة أصلي من الفيديو: يتعرض هو حتى لو المحرك عنده ترجمة (لحد ما يرجّع ترجمة البرنامج أو الفيديو يتغيّر) */
    @Volatile private var preferNative = false
    /** (v521) زرار 🌐 في شريط التلفزيون: مسارات الترجمة الأصلية في الفيديو (سوفت سب) + لغة/لهجة ترجمة البرنامج + إعداداتها */
    private fun tvSubTracksDialog(anchor: View) {
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        val tc = androidx.media3.common.C.TRACK_TYPE_TEXT
        val groups = try { (player.exo()?.currentTracks?.groups ?: emptyList<androidx.media3.common.Tracks.Group>()).filter { it.type == tc && it.length > 0 && it.isSupported } } catch (_: Throwable) { emptyList() }
        val textOff = try { (player.exo()?.trackSelectionParameters?.disabledTrackTypes?.contains(tc) ?: false) } catch (_: Throwable) { false }
        groups.forEachIndexed { i, g ->
            val f = g.getTrackFormat(0)
            val lang = (f.language ?: "").let { l -> if (l.isBlank() || l == "und") "" else try { java.util.Locale(l).getDisplayLanguage(java.util.Locale("ar")).ifBlank { l } } catch (_: Throwable) { l } }
            val name = listOf(f.label ?: "", lang).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "مسار " + (i + 1) }
            val on = g.isSelected && !textOff && preferNative
            add((if (on) "✓ " else "") + "💬 " + name + " (من الفيديو)") {
                try {
                    player.exo()?.let { xp -> xp.trackSelectionParameters = xp.trackSelectionParameters.buildUpon().setTrackTypeDisabled(tc, false)
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0)).build() }
                    preferNative = true; ccOn = true; curIdx = -2
                    Notice.show(this, "💬 " + name, 1500L)
                } catch (e: Throwable) { LogStore.err("Player:pickText", e) }
            }
        }
        if (groups.isNotEmpty() && preferNative) add("↩ رجّع ترجمة البرنامج") { preferNative = false; ccOn = true; curIdx = -2; Notice.show(this, "↩ ترجمة البرنامج", 1300L) }
        add("🌐 لغة / لهجة ترجمة البرنامج") { preferNative = false; langPopup(anchor) }
        // (v552) «ترجمة ملف الترجمة» اتشالت — السؤال بقى جوه ▶ ترجم لما الفيديو يبقى فيه سوفت صب
        add("⚙ إعدادات الترجمة") { modeSettings(anchor) }
        if (groups.isEmpty()) add("ℹ مفيش ترجمة أصلية في الفيديو ده (لو الترجمة محروقة على الصورة استخدم ▶ ترجم ← بصري/هارد سب)") { }
        GAlert(this).setTitle("💬 مسار الترجمة").setItems(labels.toTypedArray()) { _, w -> if (w in acts.indices) acts[w]() }.show()
    }
    // ===== (v529) 📄 ترجمة ملف الترجمة: سوفت سب (ملف جنب الفيديو / ملف تختاره / مسار جوه الفيديو) أو هارد سب (محروق على الصورة) — بجوجل أو جيميناي =====
    @Volatile private var sfBusy = ""
    @Volatile private var sfLive = ""   // "" | google | gemini: ترجمة مسار الفيديو الأصلي لحظيًا (لما مفيش استخراج كامل)
    @Volatile private var sfLiveGen = 0
    @Volatile private var sfPickEng = "google"
    private val sfLiveCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sfLiveAsk = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val sfLiveCtx = java.util.ArrayDeque<String>()
    private var sfSideKey = ""; private var sfSideNm = ""
    private fun sfSidecar(): java.io.File? = try { val u = uri; if (u == null) null else findSidecar(u) } catch (_: Throwable) { null }
    /** اسم ملف الترجمة اللي جنب الفيديو (متخزّن لكل فيديو عشان الريموت بيسأل كل ثانية) */
    private fun sfSideName(): String {
        val k = uri?.toString() ?: ""
        if (k != sfSideKey) { sfSideKey = k; sfSideNm = try { sfSidecar()?.name ?: "" } catch (_: Throwable) { "" } }
        return sfSideNm
    }
    private fun sfNativeCount(): Int = try { (player.exo()?.currentTracks?.groups ?: emptyList<androidx.media3.common.Tracks.Group>()).count { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.length > 0 && it.isSupported } } catch (_: Throwable) { 0 }

    private fun sfLiveText(t: String): String {
        if (sfLive.isEmpty()) return t
        sfLiveCache[t]?.let { return it }
        if (sfLiveAsk.putIfAbsent(t, true) == null) {
            val eng = sfLive
            val ctx = synchronized(sfLiveCtx) { val c = sfLiveCtx.joinToString("\n"); sfLiveCtx.addLast(t.replace("\n", " ")); while (sfLiveCtx.size > 3) sfLiveCtx.removeFirst(); c }
            Thread {
                try {
                    val r = if (eng == "gemini") SubFileTr.geminiOne(t, ctx) else SubFileTr.googleOne(t)
                    if (!r.isNullOrBlank() && sfLive == eng) { if (sfLiveCache.size > 3000) sfLiveCache.clear(); sfLiveCache[t] = r; sfLiveGen++ }
                } catch (e: Throwable) { LogStore.err("Player:sfLive", e) }
                finally { sfLiveAsk.remove(t) }
            }.also { it.isDaemon = true }.start()
        }
        return t
    }
    private fun sfStartLive(eng: String) {
        sfLive = eng; sfLiveCache.clear(); sfLiveAsk.clear(); synchronized(sfLiveCtx) { sfLiveCtx.clear() }
        try { player.exo()?.let { xp -> xp.trackSelectionParameters = xp.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build() } } catch (_: Throwable) {}
        preferNative = true; ccOn = true; curIdx = -2
        say("💬 بترجم ترجمة الفيديو الأصلية لحظيًا بـ " + (if (eng == "gemini") "جيميناي" else "جوجل") + " — ممكن تتأخر ثانية عن الصوت")
    }
    private fun sfStopLive() { sfLive = ""; preferNative = false; curIdx = -2 }

    private fun sfEngineChoice(what: String, then: (String) -> Unit) {
        val lang = Cfg.str("lang", "فصحى").ifBlank { "فصحى" }
        val labels = arrayOf("🌐 جوجل ترجمة — من غير مفتاح، سريع (فصحى)", "✨ جيميناي — بمفاتيحك، أدق بالسياق (لهجة $lang)")
        GAlert(this).setTitle("ترجم $what بإيه؟").setItems(labels) { _, w -> then(if (w == 0) "google" else "gemini") }.show()
    }

    private fun subFileDialog(anchor: View? = null) {
        val side = sfSidecar(); val nat = sfNativeCount()
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        add("💬 ترجمة السوفت سب" + (if (side != null) " — ملف «" + side.name + "»" else if (nat > 0) " — $nat مسار جوه الفيديو" else " (لو موجودة)")) { sfEngineChoice("السوفت سب") { e -> sfRun("soft", e) } }
        add("📂 اختار ملف ترجمة من الجهاز (SRT / ASS / VTT)") { sfEngineChoice("الملف") { e -> sfPickFile(e) } }
        add("🔤 ترجمة الهارد سب (المحروقة على الصورة) — بجيميناي") { sfHard() }
        if (sfLive.isNotEmpty()) add("⏹ وقّف الترجمة اللحظية للمسار الأصلي") { sfStopLive(); say("⏹ اتوقفت") }
        add("ℹ الهارد سب بيتقرا من الصورة فمحتاج جيميناي (مفتاح الوضع البصري)؛ جوجل مابيقرأش صور") { }
        GAlert(this).setTitle("📄 ترجمة ملف الترجمة").setItems(labels.toTypedArray()) { _, w -> if (w in acts.indices) acts[w]() }.show()
    }

    private fun sfRemote(a: String) {
        val p = a.split(":")
        val eng = if (p.getOrNull(2) == "gemini") "gemini" else "google"
        when (p.getOrNull(1)) {
            "soft" -> sfRun("soft", eng)
            "pick" -> sfPickFile(eng)
            "hard" -> sfHard()
            "off" -> { sfStopLive(); say("⏹ اتوقفت") }
            "dlg" -> subFileDialog(window.decorView)
        }
    }

    private fun sfPickFile(eng: String) {
        sfPickEng = eng
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 41) }
        catch (e: Throwable) { LogStore.err("Player:sfPick", e); say("مش قادر أفتح اختيار الملف") }
    }

    /** هارد سب: نفس بريست «ترجمة الهارد سب» (الوضع البصري بيقرا الترجمة المحروقة ويترجمها بجيميناي) */
    private fun sfHard() {
        try {
            if (!::visual.isInitialized) { say("الوضع البصري مش جاهز لسه — جرّب بعد ثواني"); return }
            val pr = TrPresets.byId(TrPresets.HARD_ID) ?: return
            applyPreset(pr, false, false, true)
        } catch (e: Throwable) { LogStore.err("Player:sfHard", e); say("⚠ تعذّر تشغيل ترجمة الهارد سب") }
    }

    private fun sfRun(kind: String, eng: String, picked: Uri? = null, track: Int = -1) {
        if (kind == "hard") { sfHard(); return }
        if (sfBusy.isNotEmpty()) { say("⏳ بترجم دلوقتي: $sfBusy"); return }
        sfBusy = "بقرا الترجمة…"; runOnUiThread { updateTr() }
        val engName = if (eng == "gemini") "جيميناي" else "جوجل"
        val vu = uri
        Thread {
            try {
                var cues: List<SubFileTr.Cue> = emptyList(); var label = ""
                if (picked != null) {
                    val b = contentResolver.openInputStream(picked)?.use { it.readBytes() } ?: ByteArray(0)
                    cues = SubFileTr.parse(SubFileTr.decode(b)); label = "الملف اللي اخترته"
                    if (cues.isEmpty()) { say("الملف ده مش ترجمة صالحة (SRT / ASS / VTT)"); return@Thread }
                } else {
                    val sc = if (track >= 0) null else sfSidecar()
                    if (sc != null) { cues = SubFileTr.parse(SubFileTr.decode(sc.readBytes())); label = sc.name }
                    if (track >= 0 && vu != null) { SubFileTr.readEmbeddedTrack(applicationContext, vu, track)?.let { cues = it.second; label = it.first + " من الفيديو" } }
                    else if (cues.isEmpty() && vu != null && (vu.scheme == "file" || vu.scheme == "content")) {
                        SubFileTr.readEmbedded(applicationContext, vu)?.let { cues = it.second; label = "مسار " + it.first + " من الفيديو" }
                    }
                }
                if (cues.isEmpty()) {
                    if (sfNativeCount() > 0 || nativeCue.isNotEmpty()) { runOnUiThread { sfStartLive(eng) }; return@Thread }   // مش قادر أطلّع المسار كله: ترجمة لحظية
                    say("مفيش سوفت سب في الفيديو ده (لا ملف جنبه ولا مسار جواه) — لو الترجمة محروقة على الصورة استخدم «ترجمة الهارد سب»")
                    return@Thread
                }
                if (SubFileTr.mostlyArabic(cues)) { say("💬 الترجمة دي عربي أصلًا"); return@Thread }
                say("📄 بترجم " + cues.size + " جملة من «" + label + "» بـ " + engName + "…")
                var lastP = 0L
                val out = SubFileTr.translate(cues, eng, { pr -> sfBusy = pr; val n = System.currentTimeMillis(); if (n - lastP > 6000L) { lastP = n; say(pr) } }, { isFinishing || isDestroyed })
                if (isFinishing || isDestroyed) return@Thread
                if (out.error.isNotEmpty()) { say("⚠ " + out.error); return@Thread }
                var w = 0
                while ((!::engine.isInitialized || !engineReady) && w++ < 40) { try { Thread.sleep(250L) } catch (_: InterruptedException) {} }
                sfApply(cues, out, engName)
            } catch (e: Throwable) { LogStore.err("Player:sfRun", e); say("⚠ فشلت الترجمة: " + (e.message ?: e.javaClass.simpleName).take(80)) }
            finally { sfBusy = ""; runOnUiThread { updateTr() } }
        }.also { it.isDaemon = true }.start()
    }

    private fun sfApply(cues: List<SubFileTr.Cue>, out: SubFileTr.Out, engName: String) {
        val subs = cues.mapIndexed { i, c ->
            val tr = out.texts.getOrNull(i) ?: ""
            Sub(c.start, c.end, c.text, if (tr.isBlank()) c.text else tr, "male", "unknown", "none", emptyList(), emptyList(), false, false)
        }
        runOnUiThread {
            try {
                if (!::engine.isInitialized || !engineReady) { say("المحرك لسه بيحمّل — جرّب تاني بعد ثواني"); return@runOnUiThread }
                if (engineStarted && !engine.userPaused) pauseTranslate()
                if (engine.subs.isNotEmpty()) engine.saveVersion("قبل ترجمة ملف الترجمة")
                engine.importSubs(subs)
                sfLive = ""; preferNative = false; ccOn = true; dirty = true; curIdx = -2
                Thread { try { engine.saveNow() } catch (e: Throwable) { LogStore.err("Player:sfSave", e) } }.start()
                say("✅ اتترجمت " + subs.size + " جملة بـ " + engName + (if (out.failed > 0) " — " + out.failed + " جملة فضلت بنصها الأصلي" else ""))
            } catch (e: Throwable) { LogStore.err("Player:sfApply", e); say("⚠ مقدرتش أطبّق الترجمة") }
        }
    }

    private fun tvMoreDialog(anchor: View) {
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        add("⏩ السرعة: " + PlayerLogic.speedLabel(speed)) { tvCycleSpeed(); Notice.show(this, "⏩ " + PlayerLogic.speedLabel(speed), 1100L) }
        add("⬛ الاحتواء: " + fitNameOf(curFit())) { cycleFitNow() }
        add("🧑 الشخصيات") { showChars() }
        add("📝 الجمل") { try { if (::sentDlg.isInitialized) sentDlg.show() } catch (_: Throwable) {} }
        add("📜 اللوج") { toggleLog() }
        add("⚙ إعدادات الترجمة") { modeSettings(anchor) }
        add("🌐 لغة / لهجة") { langPopup(anchor) }
        // (v552) «ترجمة ملف الترجمة» اتشالت — السؤال بقى جوه ▶ ترجم لما الفيديو يبقى فيه سوفت صب
        add("☰ القائمة الكاملة") { tvMenuFn?.invoke(anchor) }
        add("🧰 أدوات الفيديو") { tvToolsFn?.invoke(anchor) }
        GAlert(this).setTitle("المزيد").setItems(labels.toTypedArray()) { _, w -> if (w in acts.indices) acts[w]() }.show()
    }
    private var tvDigitRun: Runnable? = null
    // (v569) تقديم/تأخير بالريموت العادي: كل دوسة بتحرّك مؤشر (target) بس وبيظهر شريط فيه الدقيقة اللي هتروحلها + مدة الفيديو،
    // والـ seek الفعلي بيتنفّذ لما تسيب الزرار ~0.8 ثانية أو تدوس OK. Back = إلغاء.
    private var tvScrubTo = -1L
    private var tvScrubOrigin = 0L
    private var tvScrubOv: TvScrubOverlay? = null
    private val tvScrubCommit = Runnable { tvScrubDone(true) }
    private fun tvSeek(dMs: Long) {
        try {
            val dur = if (durMs > 0) durMs else Long.MAX_VALUE
            if (tvScrubTo < 0) { tvScrubOrigin = player.currentPosition; tvScrubTo = tvScrubOrigin }
            tvScrubTo = (tvScrubTo + dMs).coerceIn(0L, dur)
            if (tvBar?.visible == true) tvBar?.refresh()   // الشريط الكبير ظاهر: هو اللي بيعرض المؤشر
            else tvScrubOv?.show(tvScrubTo, tvScrubOrigin, durMs)
            tvH.removeCallbacks(tvScrubCommit); tvH.postDelayed(tvScrubCommit, 800L)
        } catch (e: Exception) { LogStore.err("Tv:seek", e) }
    }
    private fun tvScrubDone(commit: Boolean) {
        tvH.removeCallbacks(tvScrubCommit)
        val t = tvScrubTo; tvScrubTo = -1L
        tvScrubOv?.hide()
        try {
            if (commit && t >= 0) {
                // (v576) فيديو نت/مبعوت من الموبايل: السبينر يظهر فورًا مع القفزة (مش بعد ما يتأخر)؛ المحلي من غير سبينر
                if (tvSrcIsNet() && Math.abs(t - player.currentPosition) > 1500L) setBuffering(true)
                player.seekTo(t)
                if (tvSrcIsNet()) tvH.postDelayed({ try { if (buffering && player.exo()?.playbackState == Player.STATE_READY) setBuffering(false) } catch (_: Throwable) {} }, 700L)
            }
            tvBar?.let { if (it.visible) it.refresh() }
        } catch (e: Exception) { LogStore.err("Tv:seek", e) }
    }
    private fun tvSubScale(d: Int) {
        val n = (Cfg.int("sub_scale", 100) + d).coerceIn(50, 250)
        Cfg.put("sub_scale", n.toString()); try { restyleFn() } catch (_: Exception) {}
        Notice.show(this, "🔤 حجم الترجمة: $n%", 1100L)
    }
    private fun tvJump() {
        val buf = tvDigits; tvDigits = ""; tvDigitRun?.let { tvH.removeCallbacks(it) }; tvDigitRun = null
        if (buf.isEmpty()) return
        val sec = if (buf.length <= 2) buf.toIntOrNull() ?: 0 else (buf.dropLast(2).toIntOrNull() ?: 0) * 60 + (buf.takeLast(2).toIntOrNull() ?: 0)
        try { player.seekTo((sec * 1000L).coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE)); Notice.show(this, "⏱ " + PlayerLogic.clock(sec * 1000L), 1100L) } catch (e: Exception) { LogStore.err("Tv:jump", e) }
    }
    private fun tvDigit(d: Int) {
        if (tvDigits.length >= 4) tvDigits = ""
        tvDigits += d.toString()
        Notice.show(this, "⏱ روح لوقت: $tvDigits (آخر رقمين = ثواني)", 1500L)
        tvDigitRun?.let { tvH.removeCallbacks(it) }
        val r = Runnable { tvJump() }; tvDigitRun = r; tvH.postDelayed(r, 2200)
    }
    // (v462) الدفء الليلي في المشغّل (وضع TV + إعداد tv_warm من شاشة التلفزيون): طبقة برتقالية خفيفة فوق كل حاجة
    private var tvWarmV: View? = null
    private fun tvWarmApply() {
        try {
            val on = DeviceUi.isTv(this) && Cfg.str("tv_warm", "0") == "1"
            val decor = window.decorView as? android.view.ViewGroup ?: return
            if (on && tvWarmV == null) {
                val v = View(this).apply { setBackgroundColor(0x2EFF8C1A); isClickable = false; isFocusable = false }
                decor.addView(v, android.widget.FrameLayout.LayoutParams(-1, -1)); tvWarmV = v
            } else if (!on && tvWarmV != null) { decor.removeView(tvWarmV); tvWarmV = null }
        } catch (_: Exception) {}
    }

    // ===== (v463) لوحة نص الترجمة الجانبية (وضع التابلت): قايمة جمل list — دوسة تقفز، والحالية بتتلوّن وبتتابع =====
    private var trPanel: android.widget.LinearLayout? = null
    private var trBtn: android.widget.TextView? = null
    private var trScroll: android.widget.ScrollView? = null
    private var trListBox: android.widget.LinearLayout? = null
    private var trRows: ArrayList<android.widget.TextView> = ArrayList()
    private var trCur = -1
    private val trTick = object : Runnable { override fun run() { trRefresh(); if (trPanel?.visibility == View.VISIBLE) tvH.postDelayed(this, 600) } }
    private fun trDp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun trEnsureBtn() {
        try {
            if (!DeviceUi.isTablet(this) || trBtn != null) return
            val decor = window.decorView as? android.view.ViewGroup ?: return
            val b = android.widget.TextView(this).apply {
                text = "📜"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xB33A332A.toInt()) }
                setOnClickListener { trToggle() }
            }
            decor.addView(b, android.widget.FrameLayout.LayoutParams(trDp(52), trDp(52), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, trDp(16), trDp(96)) })
            trBtn = b
        } catch (_: Exception) {}
    }
    private fun trToggle() {
        try {
            val decor = window.decorView as? android.view.ViewGroup ?: return
            if (trPanel == null) {
                val panel = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                    setBackgroundColor(0xF2141210.toInt()); setPadding(trDp(12), trDp(12), trDp(12), trDp(12)); elevation = trDp(12).toFloat()
                }
                val head = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                head.addView(android.widget.TextView(this).apply { text = "📜 نص الترجمة"; textSize = 15f; setTextColor(0xFFFFB340.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(android.widget.TextView(this).apply { text = "✕"; textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setPadding(trDp(10), trDp(4), trDp(10), trDp(4)); setOnClickListener { trToggle() } })
                panel.addView(head)
                val sc = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false }
                val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                sc.addView(box); panel.addView(sc, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
                decor.addView(panel, android.widget.FrameLayout.LayoutParams(trDp(360), -1, Gravity.END))
                trPanel = panel; trScroll = sc; trListBox = box
            }
            val p = trPanel!!
            if (p.visibility == View.VISIBLE) { p.visibility = View.GONE; tvH.removeCallbacks(trTick) }
            else { p.visibility = View.VISIBLE; trBuild(); trCur = -1; trRefresh(); tvH.removeCallbacks(trTick); tvH.post(trTick) }
        } catch (e: Exception) { LogStore.err("Tr:toggle", e) }
    }
    private fun trBuild() {
        val box = trListBox ?: return
        box.removeAllViews(); trRows.clear()
        if (list.isEmpty()) {
            box.addView(android.widget.TextView(this).apply { text = "الترجمة لسه ما اتعملتش — دوس «ترجم» الأول"; textSize = 13f; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, trDp(14), 0, 0) })
            return
        }
        for ((i, sub) in list.withIndex()) {
            val main = if (sub.translated.isNotEmpty()) sub.translated else sub.original
            val tv = android.widget.TextView(this).apply {
                text = PlayerLogic.clock((sub.start * 1000).toLong()) + "  " + main + if (sub.translated.isNotEmpty() && sub.original.isNotEmpty() && sub.original != sub.translated) "\n" + sub.original else ""
                textSize = 13f; setTextColor(0xFFEEEEEE.toInt()); setPadding(trDp(8), trDp(7), trDp(8), trDp(7))
                setOnClickListener {
                    try { player.seekTo((sub.start * 1000).toLong()); if (!player.isPlaying) player.play() } catch (_: Exception) {}
                }
            }
            trRows.add(tv); box.addView(tv)
            if (i >= 4000) break   // حماية من قوائم ضخمة جدًا
        }
    }
    private fun trRefresh() {
        try {
            if (trPanel?.visibility != View.VISIBLE || list.isEmpty() || !::player.isInitialized) return
            val pos = player.currentPosition
            var lo = 0; var hi = list.size - 1; var idx = -1
            while (lo <= hi) { val mid = (lo + hi) / 2; if ((list[mid].start * 1000).toLong() <= pos) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
            if (idx == trCur || idx < 0) return
            trRows.getOrNull(trCur)?.apply { setBackgroundColor(0x00000000); setTextColor(0xFFEEEEEE.toInt()) }
            trCur = idx
            val row = trRows.getOrNull(idx) ?: return
            row.setBackgroundColor(0x55FFB340); row.setTextColor(0xFFFFFFFF.toInt())
            trScroll?.smoothScrollTo(0, (row.top - trDp(120)).coerceAtLeast(0))
        } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        TvKill.watch(this, ev)   // (v486) 0 لمدة 5 ثواني = إلغاء تثبيت
        // (v469) والقفل شغّال: مفاتيح الصوت الجانبية كمان تتبلع (حماية جيب/أطفال كاملة — اللمس كان محجوب أصلًا)
        if (remote && !DeviceUi.isRemote(this) && (ev.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || ev.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || ev.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE)) {
            if (ev.action == android.view.KeyEvent.ACTION_DOWN && !uiLocked) rmVolumeKey(ev.keyCode)   // (v568) زراير صوت الموبايل = صوت التلفزيون
            return true
        }
        if (uiLocked && (ev.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || ev.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || ev.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE)) return true
        // (v477 م4) تلفزيون: شريط تحكم بنمط MX/VLC. الشريط مخفي: ←/→ تقديم وتأخير · OK/↑/↓/Menu = إظهار الشريط. ظاهر: الأسهم بتتنقل بين الأزرار.
        // (v506) عدّاد «الحلقة الجاية»: بالريموت OK = شغّلها دلوقتي، رجوع = ألغي التشغيل التلقائي
        if (nextUpAt > 0L && DeviceUi.isRemote(this) && (ev.keyCode == android.view.KeyEvent.KEYCODE_BACK || ev.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || ev.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
            if (ev.action == android.view.KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                if (ev.keyCode == android.view.KeyEvent.KEYCODE_BACK) { cancelNextUp(); say("⏹ اتلغى التشغيل التلقائي") }
                else { val g = nextUpGo; cancelNextUp(); try { g?.invoke() } catch (e: Throwable) { LogStore.err("Player:nextNow", e) } }
            }
            return true
        }
        if (DeviceUi.isRemote(this)) {
            val bar = tvBar; val k = ev.keyCode; val down = ev.action == android.view.KeyEvent.ACTION_DOWN
            // (v569) تقديم معلّق: OK = نفّذ دلوقتي · Back = إلغاء (من غير ما يقفل المشغّل)
            if (tvScrubTo >= 0L && (k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER || k == android.view.KeyEvent.KEYCODE_BACK)) {
                if (down && ev.repeatCount == 0) tvScrubDone(k != android.view.KeyEvent.KEYCODE_BACK)
                return true
            }
            // (v569) زراير التقديم/الترجيع في الريموت (⏩ ⏪) بنفس شريط التقديم
            if (k == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == android.view.KeyEvent.KEYCODE_MEDIA_REWIND || k == android.view.KeyEvent.KEYCODE_MEDIA_STEP_FORWARD || k == android.view.KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD) {
                if (down) { val step = if (ev.repeatCount > 12) 60_000L else if (ev.repeatCount > 4) 30_000L else 10_000L; tvSeek(if (k == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == android.view.KeyEvent.KEYCODE_MEDIA_STEP_FORWARD) step else -step) }
                return true
            }
            if (k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || k == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
                if (down) tvVolume(k)
                return true
            }
            val media = k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY || k == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            if (media || k == android.view.KeyEvent.KEYCODE_MEDIA_NEXT || k == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                k in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
                if (down) {
                    when {
                        media -> togglePlay()
                        k == android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> stepEpisode(1)
                        k == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> stepEpisode(-1)
                        else -> tvDigit(k - android.view.KeyEvent.KEYCODE_0)
                    }
                }
                return true
            }
            if (bar != null && bar.visible && (k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN) &&
                currentFocus.let { f0 -> f0 == null || tvInBadges(f0) || tvInside(f0, bar.view) }) {
                // (v569) من فوق لتحت: العدّادات ← شريط التقدم (يمين/شمال = سحب) ← أزرار التحكم. فوق من الأزرار = شريط التقدم
                val f = currentFocus
                val inB = tvInBadges(f); val onT = bar.onTrack(f)
                if (k == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                    if (inB) return true
                    if (!onT) { if (down) { bar.focusTrack(); bar.poke() }; return true }
                    if (::badgeRowV.isInitialized) {
                        val l = ArrayList<View>(); badgeRowV.addFocusables(l, View.FOCUS_DOWN)
                        val t = l.firstOrNull { it.isShown }
                        if (t != null) { if (down) { t.requestFocus(); bar.poke() }; return true }
                    }
                    return true
                } else {
                    if (inB) { if (down) { bar.focusTrack(); bar.poke() }; return true }
                    if (onT) { if (down) { bar.focusMain(); bar.poke() }; return true }
                    return true
                }
            }
            if (bar != null && bar.visible) { bar.poke() }
            else if (tvOverlayFocused()) { /* (v500) في عنصر مفتوح في لوحة/قايمة جوه المشغّل: سيب الأسهم وOK توصله بدل ما نبلعها */ }   // والشريط ظاهر: الأسهم/OK بتتعامل عادي مع التركيز على الأزرار
            else when (k) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down) { val step = if (ev.repeatCount > 12) 60_000L else if (ev.repeatCount > 4) 30_000L else 10_000L; tvSeek(if (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) step else -step) }
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                    if (down) { if (tvDigits.isNotEmpty()) tvJump() else bar?.show() }   // (v509) OK = يظهر الأزرار المخفية (التشغيل/الإيقاف من زرار ▶ في الشريط)
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_MENU -> {
                    if (down) bar?.show()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(ev)
    }
    /** (v509) الأزرار العايمة الدايمة فوق الفيديو (عدّاد الترجمة، 👁، الكبسولات...) مش «لوحة مفتوحة» — كانت بتاخد التركيز وتبلع OK والأسهم */
    private var tvChrome: Set<View> = emptySet()
    private fun tvSnapChrome() { try { val b = videoBoxRef; tvChrome = (0 until b.childCount).map { b.getChildAt(it) }.filter { it.tag != "chromeFrame" }.toSet() } catch (_: Throwable) {} }
    /** (v509) تلفزيون: عدّاد الجمل/النت واللوج مخفيين تمامًا (ولا بياخدوا تركيز) إلا لما شريط التحكم يظهر بـ OK */
    private fun tvGateClosed(): Boolean = DeviceUi.isRemote(this) && tvBar?.visible != true
    private fun tvChromeGate() {
        try {
            if (!DeviceUi.isRemote(this)) return
            val on = !tvGateClosed()
            applyBadgeAlpha()
            if (::leftCol.isInitialized) { leftCol.visibility = if (on) View.VISIBLE else View.INVISIBLE; if (!on) leftCol.clearFocus() }
            if (::badgeRowV.isInitialized && !on) badgeRowV.clearFocus()
        } catch (e: Throwable) { LogStore.err("Tv:gate", e) }
    }
    private fun tvInside(v: View?, root: View): Boolean { var x = v; while (x != null) { if (x === root) return true; x = x.parent as? View }; return false }
    private fun tvInBadges(v: View?): Boolean { var x = v; while (x != null) { if (::badgeRowV.isInitialized && x === badgeRowV) return true; x = x.parent as? View }; return false }
    private fun tvIsChrome(v: View): Boolean { var x: View? = v; while (x != null) { if (x in tvChrome) return true; x = x.parent as? View }; return false }
    private fun tvOverlayFocused(): Boolean = try { val f = currentFocus; f != null && f.isShown && (f.isClickable || f is android.widget.EditText) && !tvIsChrome(f) } catch (_: Throwable) { false }
    /** (v568) على التلفزيون: يطبّق صوت جاي من الموبايل (0..100 صوت النظام، 101..200 تضخيم). لو الصوت ثابت (isVolumeFixed) يغيّر صوت المشغّل نفسه */
    private fun applyVolumeP(p0: Int) {
        val p = p0.coerceIn(0, 200)
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            if (am.isVolumeFixed) {   // (v604) التلفزيون صوته ثابت: العادي بصوت المشغّل نفسه، والتضخيم لسه شغّال (كان بيتقصّ عند 100)
                if (p <= 100) { player.volume = p / 100f; if ((Cfg.str("vol_boost", "0").toIntOrNull() ?: 0) != 0) setBoostPct(0) }
                else { player.volume = 1f; setBoostPct(p - 100) }
            } else {
                player.volume = 1f
                if (p <= 100) { SysVol.set(this, p); if ((Cfg.str("vol_boost", "0").toIntOrNull() ?: 0) != 0) setBoostPct(0) }
                else { SysVol.set(this, 100); setBoostPct(p - 100) }
            }
            try { if (::ctl.isInitialized) ctl.vol.progress = p } catch (_: Throwable) {}
            giShowFn(if (p <= 100) "🔊 $p%" else "🔊 $p%  تضخيم ${boostDbText(p - 100)}")
        } catch (e: Throwable) { LogStore.err("Player:applyVolumeP", e) }
    }
    // ===== (v568) وضع المتحكم: الصوت بيتغيّر على التلفزيون (مش على الموبايل) =====
    private var rmVolP = -1
    private var rmVolAt = 0L
    private var rmMuteBack = -1
    private fun rmSendVolume(p0: Int) {
        val p = p0.coerceIn(0, 200); rmVolP = p; rmVolAt = System.currentTimeMillis()
        LinkSession.send("volume.set", org.json.JSONObject().put("p", p))
        try { giShowFn(if (p <= 100) "📺 🔊 $p%" else "📺 🔊 $p%  تضخيم ${boostDbText(p - 100)}") } catch (_: Throwable) {}
    }
    private fun rmVolumeKey(k: Int) {
        val cur = if (rmVolP >= 0) rmVolP else LinkSession.mirror.st.optInt("vol", 50).let { if (it < 0) 50 else it }
        when (k) {
            android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> { if (cur > 0) { rmMuteBack = cur; rmSendVolume(0) } else rmSendVolume(if (rmMuteBack > 0) rmMuteBack else 50) }
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> rmSendVolume(cur + 5)
            else -> rmSendVolume(cur - 5)
        }
    }
    /** (v509) الصوت بالريموت: صوت النظام، ولو التلفزيون صوته ثابت نغيّر صوت المشغّل نفسه */
    private fun tvVolume(k: Int) {
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            if (k == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI); return }
            val up = k == android.view.KeyEvent.KEYCODE_VOLUME_UP
            if (am.isVolumeFixed) {
                val v = ((player.volume + if (up) 0.1f else -0.1f).coerceIn(0f, 1f)); player.volume = v
                if ((Cfg.str("vol_boost", "0").toIntOrNull() ?: 0) != 0) setBoostPct(0)   // (v604) نزّل الصوت = التضخيم يتصفّر
                try { if (::ctl.isInitialized) ctl.vol.progress = Math.round(v * 100) } catch (_: Throwable) {}
                Notice.show(this, "🔊 " + (v * 100).toInt() + "%", 900L)
            } else am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
        } catch (e: Throwable) { LogStore.err("Tv:vol", e) }
    }
    override fun finish() { super.finish(); try { overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Main:725", e) } }
    @Volatile var cur = 0L
    @Volatile var durMs = 0L
    @Volatile var status = ""
    @Volatile var dirty = true
    private var lastBatch = 0L
    lateinit var player: PlaybackBackend
    /** (v546 م2-ج) وضع المتحكم: التشغيل والمحرك والترجمة على التلفزيون، والموبايل واجهة بس (intent extra remote=true) */
    @Volatile var remote = false
    lateinit var sub: SubtitleView
    lateinit var visual: VisualMode
    lateinit var visOv: VisualOverlay
    lateinit var miniBar: DualProgress
    lateinit var st: TextView
    lateinit var floatBar: View
    lateinit var batchTv: TextView
    lateinit var logDrawer: LinearLayout
    lateinit var logHandle: TextView
    private lateinit var leftCol: LinearLayout
    /** الأزرار العايمة وإنت بتتفرج: شبه شفافة (75% شفافية)، ولما تدوس عليها بتبقى 100% وبترجع شفافة لما تتقفل */
    private val REST_A = 0.25f
    private val PROB_A = 0.45f
    private fun fadeTo(v: View, a: Float) { try { v.animate().alpha(a).setDuration(180).start() } catch (e: Exception) { LogStore.err("Main:744", e) } }
    private val floatFades = HashMap<View, Runnable>()
    private fun flashFloat(v: View, ms: Long = 3500L) {
        fadeTo(v, 1f); floatFades[v]?.let { h.removeCallbacks(it) }
        val r = Runnable { fadeTo(v, REST_A) }; floatFades[v] = r; h.postDelayed(r, ms)
    }
    /** (v95) زرار الروتيشن العايم (فوق 👁) + دالة بتحسب مكان الاتنين من ارتفاع الشريط السفلي عشان ما يدخلوش على شريحة الترجمة */
    lateinit var rotBtn: View
    var placeFloatFn: () -> Unit = {}
    var logOn = false   // اللوج مخفي افتراضيًا — اللسان ▸ بيفرده
    lateinit var logTv: TextView
    lateinit var logSv: ScrollView
    // (v541) م0-أ: المشغّل بيكلّم واجهة EngineBackend؛ الـ Engine الحقيقي محفوظ في realEngine ويُستخدم جوه initEngine/startEngine/retireEngine/Live بس
    lateinit var engine: EngineBackend
    private lateinit var realEngine: Engine
    lateinit var th: Theme
    lateinit var ui: Ui
    val logBuf = StringBuilder()
    val h = Handler(Looper.getMainLooper())
    var url: String? = null
    var aurl: String? = null
    private var qOpts: List<YtExtract.Opt> = emptyList()   // (v118) جودات يوتيوب
    private var qCur = -1
    private var hlsH = 0                                   // جودة HLS المثبّتة (0 = تلقائي)
    private var qualBRef: TextView? = null
    private val qualRowRefs = ArrayList<TextView>()          // (v120) زرار «🎞 الجودة» اللي جنب زرار الترجمة (في الشريط السفلي والواسع)
    private var qualPopFn: ((View) -> Unit)? = null
    // (v388) لغات الصوت (يوتيوب بأكتر من مسار صوت): مربع 🔊 جنب الجودة يبدّل اللغة في نفس الفيديو
    private class ATrack(val name: String, val lang: String, val url: String, val cur: Boolean)
    private var aTracks: List<ATrack> = emptyList()
    private var aCur = 0
    private var aOverride: String? = null
    private val audRowRefs = ArrayList<TextView>()
    private var audPopFn: ((View) -> Unit)? = null
    private fun parseATracks(sj: String) {
        try {
            val at = org.json.JSONArray(sj).optJSONObject(0)?.optJSONArray("at")
            aTracks = if (at == null) emptyList() else (0 until at.length()).map { k -> val o = at.getJSONObject(k); ATrack(o.optString("n"), o.optString("l"), o.optString("u"), o.optInt("c") == 1) }.filter { it.url.isNotEmpty() }
            aCur = aTracks.indexOfFirst { it.cur }.coerceAtLeast(0); aOverride = null
        } catch (e: Exception) { aTracks = emptyList(); LogStore.err("Player:atracks", e) }
        paintAud()
    }
    private fun paintAud() {
        val multi = aTracks.size > 1
        val lbl = "🔊 " + (aTracks.getOrNull(aCur)?.lang?.uppercase() ?: "") + " ▾"
        for (v in audRowRefs) { v.text = lbl; v.setTextColor(0xFF6FCF8A.toInt()); v.visibility = if (multi) View.VISIBLE else View.GONE }
    }
    private fun switchAudio(i: Int) {
        val t = aTracks.getOrNull(i) ?: return
        if (i == aCur) return
        val o = qOpts.getOrNull(qCur)
        if (o == null || o.audio == null) { Notice.show(this, "الجودة دي مدمجة بصوت واحد — اختار جودة تانية الأول", 2600L); return }
        aOverride = t.url; aCur = i
        switchYt(qCur, true)
        paintAud()
        Notice.show(this, "🔊 لغة الصوت: " + t.name, 2000L)
    }
    // (v188) زرار «⏳ المهام» جنب زرار الترجمة: عداد أحمر + قايمة الأدوات بتنزل لفوق
    private var tasksPopFn: ((View) -> Unit)? = null
    private val taskBadges = ArrayList<TextView>()
    private fun taskCount(): Int = TaskCenter.active() + BgJobs.jobs.count { it.active }
    private val taskListener: () -> Unit = {
        val n = taskCount()
        for (bd in taskBadges) { bd.text = if (n > 99) "99+" else n.toString(); bd.visibility = if (n > 0) View.VISIBLE else View.GONE }
    }
    private fun setQualLabel(h: Int) {
        qualBRef?.text = if (h > 0) h.toString() else "HD"
        for (v in qualRowRefs) v.text = if (h > 0) "${h}p ▾" else if (vidW > 0 && vidH > 0) "${minOf(vidW, vidH)}p ▾" else "الجودة ▾"
        paintQual()
    }
    /** (v134) عدد الجودات المتاحة للفيديو: يوتيوب (قايمة الجودات) أو مسارات HLS/DASH بارتفاعات مختلفة */
    private fun qualCount(): Int {
        if (qOpts.size > 1) return qOpts.size
        return try {
            val hs = HashSet<Int>()
            for (g in (player.exo()?.currentTracks?.groups ?: emptyList<androidx.media3.common.Tracks.Group>())) if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO)
                for (ti in 0 until g.length) if (g.isTrackSupported(ti)) { val ht = g.getTrackFormat(ti).height; if (ht > 0) hs.add(ht) }
            hs.size
        } catch (_: Throwable) { 0 }
    }
    /** (v134) رقم الجودة: أخضر هادي لو الفيديو له أكتر من جودة تختار منها، أحمر لو جودة واحدة بس */
    private fun paintQual() {
        val multi = qualCount() > 1
        val col = if (multi) 0xFF6FCF8A.toInt() else 0xFFFF6B6B.toInt()
        qualBRef?.setTextColor(col)
        // (v134) أكتر من جودة → زرار الجودة بيظهر؛ جودة واحدة → علامة صغيرة (الجودة + الحجم) فوق زرار الترجمة بدل الزرار
        qualBRef?.visibility = if (uri == null && multi) View.VISIBLE else View.GONE
        for (v in qualRowRefs) { v.setTextColor(col); v.visibility = if (multi) View.VISIBLE else View.GONE }
        val ht = qOpts.getOrNull(qCur)?.h?.takeIf { it > 0 } ?: if (vidW > 0 && vidH > 0) minOf(vidW, vidH) else 0
        for (b in qBadges) {
            b.text = "🎞 " + (if (ht > 0) "${ht}p" else "—") + (if (sizeTxt.isNotEmpty()) "  ·  $sizeTxt" else "")
            b.visibility = if (!multi && uri == null && url != null) View.VISIBLE else View.GONE
        }
    }
    private val qBadges = ArrayList<TextView>()
    private var sizeTxt = ""
    private var sizeBusy = false
    private var dlRunning = false
    private var dlDlg: android.app.Dialog? = null
    private fun isHlsUrl(): Boolean = (url ?: "").contains(".m3u8", true)
    private fun dlName(): String = (intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid)).substringBeforeLast('.', "").ifBlank { intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid) }

    /** (v135) مصدر 🧰 أدوات الفيديو: الفيديو الشغّال دلوقتي (محلي = Uri، نت = لينك مباشر؛ aurl = صوت منفصل لو موجود) */
    private fun toolSrc(): ToolSrc? {
        val u = uri?.toString() ?: url ?: return null
        return ToolSrc(u, if (uri == null) aurl else null, dlName(), durMs, vidW, vidH, player.currentPosition, engine.subs, HashMap(hdr))
    }
    /** (v134) حجم الفيديو الحالي: HEAD للينك المباشر/اليوتيوب (صورة+صوت)، وتقدير bitrate×المدة لـ HLS */
    fun refreshSize(show: Boolean = false) {
        if (uri != null || url == null || sizeBusy) return
        sizeBusy = true
        val o = qOpts.getOrNull(qCur); val u = o?.url ?: url!!; val au = o?.audio ?: aurl; val hs = HashMap(hdr)
        val hlsNow = o == null && isHlsUrl()
        val brEst = try { player.exo()?.videoFormat?.bitrate ?: -1 } catch (_: Throwable) { -1 }; val dur = durMs
        Thread {
            var n = -1L; var approx = false
            try {
                if (hlsNow) { if (brEst > 0 && dur > 0) { n = brEst / 8L * (dur / 1000L); approx = true } }
                else {
                    val a = Downloader.sizeOf(u, hs)
                    if (a > 0) { n = a; if (au != null) { val b = Downloader.sizeOf(au, hs); if (b > 0) n += b } }
                }
            } catch (_: Throwable) {}
            val txt = if (n > 0) (if (approx) "≈" else "") + Sniff.fmtSize(n) else ""
            runOnUiThread {
                sizeBusy = false; sizeTxt = txt; paintQual()
                if (show) Notice.show(this, if (txt.isNotEmpty()) "📦 حجم الفيديو: $txt" else "حجم الفيديو مش معروف من السيرفر", 2600L)
            }
        }.start()
    }
    private fun startDownload(job: Downloader.Job) {
        val app = applicationContext
        // (v137) التحميل بقى مهمة في تبويب «المهام»: إيقاف مؤقت / استكمال / إلغاء / إعادة، وممكن أكتر من تحميل ورا بعض
        TaskCenter.add(app, "download", (if (job.audioOnly) "🎧 " else "⬇ ") + job.name) { t ->
            job.ctl.cancelled = false; job.ctl.paused = false
            job.ctl.ext = { t.cancelled }
            t.onCancel = { job.ctl.cancelled = true }
            t.onPause = { p -> job.ctl.paused = p }
            val res = Downloader.run(app, job) { what, pct -> t.pct = pct.coerceIn(0, 99); t.msg = what; TaskCenter.changed() }
            if (!res.first) throw java.io.IOException(res.second)
            t.msg = ""; t.outPath = res.second
        }
        try { Notice.show(this, "⬇ اتضاف للتحميل — تابعه من تبويب «المهام» (إيقاف · استكمال · إلغاء)", 3200L) } catch (_: Throwable) {}
    }
    /** (v134) زرار ⬇: قايمة الجودات (بحجم كل واحدة) — الاختيار بيبدأ التحميل */
    fun downloadPopup(anchor: View) {
        dlDlg?.let { if (it.isShowing) { it.dismiss(); dlDlg = null; return } }
        val u0 = url ?: return
        val hs = HashMap(hdr); val nm = dlName()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }
        col.addView(ui.text("🎬 فيديو — اختار الجودة", 13f, th.muted, true).apply { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(4)) })
        fun addItem(label: String, job: Downloader.Job, sizeFn: (() -> Long)?) {
            val b = ui.button(label) { dlDlg?.dismiss(); startDownload(job) }
            col.addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
            if (sizeFn != null) Thread {
                val n = try { sizeFn() } catch (_: Throwable) { -1L }
                if (n > 0) runOnUiThread { b.text = label + "  ·  " + Sniff.fmtSize(n) }
            }.start()
        }
        if (qOpts.isNotEmpty()) {
            for (o in qOpts) addItem(o.label, Downloader.Job(nm, o.url, o.audio, false, o.h, hs)) {
                val a = Downloader.sizeOf(o.url, hs); if (a > 0 && o.audio != null) a + maxOf(0L, Downloader.sizeOf(o.audio, hs)) else a
            }
        } else if (isHlsUrl()) {
            val seen = HashSet<Int>(); val tl = ArrayList<Pair<Int, Int>>()   // (الارتفاع، bitrate)
            for (g in (player.exo()?.currentTracks?.groups ?: emptyList<androidx.media3.common.Tracks.Group>())) if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO)
                for (ti in 0 until g.length) if (g.isTrackSupported(ti)) { val f = g.getTrackFormat(ti); if (f.height > 0 && seen.add(f.height)) tl.add(Pair(f.height, f.bitrate)) }
            if (tl.isEmpty()) addItem("أعلى جودة متاحة", Downloader.Job(nm, u0, null, true, 0, hs), null)
            else for ((hh, br) in tl.sortedByDescending { it.first })
                addItem("${hh}p", Downloader.Job(nm, u0, null, true, hh, hs), if (br > 0 && durMs > 0) ({ br / 8L * (durMs / 1000L) }) else null)
            col.addView(ui.text("HLS: بيتحمّل ملف .ts (مقاطع من غير تشفير بس)", 11f, th.muted).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(2)) })
        } else {
            val ht = if (vidW > 0 && vidH > 0) minOf(vidW, vidH) else 0
            addItem("الفيديو بالجودة الحالية" + (if (ht > 0) " (${ht}p)" else ""), Downloader.Job(nm, u0, aurl, false, ht, hs)) {
                val a = Downloader.sizeOf(u0, hs); if (a > 0 && aurl != null) a + maxOf(0L, Downloader.sizeOf(aurl!!, hs)) else a
            }
        }
        // (v134) صوت فقط: M4A (AAC) — لو الصوت لينك لوحده (يوتيوب) بيتحمّل هو بس، وإلا بيتحمّل الفيديو ويتستخرج منه الصوت (أقل جودة عشان الحجم)
        col.addView(ui.text("🎧 صوت فقط", 13f, th.muted, true).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(4)) })
        val aOpt = qOpts.firstOrNull { it.audio != null }
        val loOpt = qOpts.filter { it.h > 0 }.minByOrNull { it.h } ?: qOpts.lastOrNull()
        val aJob: Downloader.Job = when {
            aOpt != null -> Downloader.Job(nm, aOpt.url, aOpt.audio, false, 1, hs, true)
            loOpt != null -> Downloader.Job(nm, loOpt.url, null, false, 1, hs, true)
            isHlsUrl() -> Downloader.Job(nm, u0, null, true, 1, hs, true)
            else -> Downloader.Job(nm, u0, aurl, false, 0, hs, true)
        }
        val aSrc = aJob.audio
        addItem("🎧 تحميل كصوت (M4A)", aJob, if (aSrc != null) ({ Downloader.sizeOf(aSrc, hs) }) else null)
        h.removeCallbacks(hideChrome); showChrome()
        dlDlg = GAlert(this).setTitle("⬇ تحميل").setView(col).setNegativeButton("إغلاق", null).show().also { d -> d.setOnDismissListener { if (dlDlg === d) dlDlg = null; showChrome() } }
    }
    var uri: Uri? = null
    private var httpDsf: DefaultHttpDataSource.Factory? = null
    val hdr = HashMap<String, String>()
    var touching = false
    lateinit var conf: Conf
    // قايمة الجمل (نسخة مرتبة + مصفوفات للبحث الثنائي)
    var list: List<Sub> = emptyList()
    var starts = LongArray(0); var ends = LongArray(0)
    private var hideTicks = 0
    private val seenKeys = HashSet<String>(); private val warnedKeys = HashSet<String>(); private var lastChkT = -1L
    private fun sentKey(q: Sub) = "${q.start}|${q.end}|${q.translated.hashCode()}"
    // الحوار والأصوات الخلفية بيتفصلوا: الحوار تحت، وصف الصوت (همهمة/موسيقى…) فوق الفيديو
    var spMap = IntArray(0); var spStarts = LongArray(0); var spEnds = LongArray(0)
    var sdMap = IntArray(0); var sdStarts = LongArray(0); var sdEnds = LongArray(0)
    lateinit var soundTv: TextView; var soundKey = ""
    var termFull = ""   // (v134) شرح المصطلح الظاهر فوق دلوقتي (بصيغة «المصطلح»: الشرح)
    private fun termName(t: String): String { val a = t.indexOf('«'); val b = t.indexOf('»'); return if (a >= 0 && b > a) t.substring(a + 1, b).trim() else t.trim().take(24) }
    /** (v134) دوس على ⓘ: الفيديو يقف ويظهر معنى المصطلح / المقصود منه في المشهد، وبيكمّل لما تقفل */
    fun showTermCard() {
        val full = termFull; if (full.isEmpty()) return
        val was = try { player.isPlaying } catch (_: Throwable) { false }
        try { player.pause() } catch (_: Throwable) {}
        val body = full.substringAfter('»', full).trimStart(':', '：', ' ', '\n').trim().ifBlank { full }
        fun resume() { if (was) { try { player.play() } catch (_: Throwable) {} } }
        GAlert(this).setTitle("ⓘ " + termName(full)).setMessage(body)
            .setPositiveButton("تمام") { _, _ -> resume() }
            .setOnCancelListener { resume() }
            .show()
    }
    var curIdx = -1; var curKey = ""; var lastRefresh = 0L
    // (v515) ترجمة الفيديو الأصلية (سوفت صب جوه الملف أو ملف ترجمة جنبه): بتظهر بنفس ستايل المشغّل بلون أبيض لما مفيش ترجمة من المحرك
    @Volatile private var nativeCue = ""
    private var natShown = false
    private var textPicked = false
    private var natStart = 0.0
    private var natLast = ""
    private fun natSub(t: String): Sub {
        if (t != natLast) { natLast = t; natStart = cur / 1000.0 }
        val tr = sfLiveText(t)   // (v529) ترجمة لحظية لو اتفعّلت من «📄 ترجمة ملف الترجمة»
        return Sub(natStart, natStart + 3.0, t, tr, "", "", "", emptyList(), emptyList(), false, false)
    }
    private fun natStyle(base: SubStyle): SubStyle = if (natShown) base.copy(uniOn = true, uniColor = "#FFFFFF") else base
    /** ملف ترجمة جنب الفيديو المحلي (نفس الاسم .srt/.ass/.ssa/.vtt/.ttml، أو name.ar.srt)؛ العربي أولًا */
    private fun findSidecar(u: Uri): java.io.File? = try {
        var path: String? = null
        if (u.scheme == "file") path = u.path
        else if (u.scheme == "content") contentResolver.query(u, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) path = c.getString(0) }
        val f = path?.let { java.io.File(it) }
        val dir = f?.parentFile
        if (f == null || dir == null) null else {
            val base = f.nameWithoutExtension.lowercase(); val exts = listOf("srt", "ass", "ssa", "vtt", "ttml")
            val c = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts && it.nameWithoutExtension.lowercase().let { n -> n == base || n.startsWith("$base.") } }.orEmpty()
            c.sortedWith(compareBy({ x -> val n = x.name.lowercase(); if (n.contains(".ar.") || n.contains("arab") || n.contains(".ara.")) 0 else 1 }, { x -> exts.indexOf(x.extension.lowercase()) }, { it.name })).firstOrNull()
        }
    } catch (e: Throwable) { LogStore.err("Player:sidecar", e); null }
    private fun mediaItemFor(u: Uri): MediaItem {
        val sc = findSidecar(u) ?: return MediaItem.fromUri(u)
        return try {
            val mime = when (sc.extension.lowercase()) { "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA; "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT; "ttml" -> androidx.media3.common.MimeTypes.APPLICATION_TTML; else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP }
            val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sc)).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            log("💬 لقيت ملف ترجمة جنب الفيديو: ${sc.name}")
            MediaItem.Builder().setUri(u).setSubtitleConfigurations(listOf(cfg)).build()
        } catch (e: Throwable) { LogStore.err("Player:sidecar2", e); MediaItem.fromUri(u) }
    }
    var offsetMs = 0L; var speed = 1f; var fit = 0; var fsFit = 2; var ccOn = true; var fitFsB: TextView? = null
    fun isLandNow() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** أفقي: وضع الشاشة الكاملة (الافتراضي تمديد) — رأسي: وضع منفصل (الافتراضي احتواء) */
    fun curFit() = if (isLandNow()) fsFit else fit
    lateinit var svRef: android.view.SurfaceView
    @Volatile var visNow = 0.0
    var vidW = 0; var vidH = 0
    lateinit var listView: ListView
    lateinit var counters: TextView
    lateinit var adapter: BaseAdapter
    lateinit var extras: View
    lateinit var videoBoxRef: FrameLayout
    lateinit var sentCap: LinearLayout   // (v322) كبسولة موحّدة: 🔊 صوتي | 👁 بصري
    lateinit var sentDiv: View
    lateinit var fsBadge: TransBadge
    lateinit var noticeBtn: TextView   // (v217) مثلث ▲ الإشعارات (v224: جنب زرار لاندسكيب/بورتريت)
    private var voice: VoiceClean.Playback? = null   // (v334) 🎙 صوت الكلام النضيف شغّال جنب الفيديو
    private var voiceBtnRef: TextView? = null
    private var trLgB: TextView? = null   // (v224) 🌐 و🔑 بقوا أيقونات صغيرة جنب زرار 🗣 في الشريط السفلي
    private var trKmB: TextView? = null
    lateinit var visBadge: TransBadge   // (v182) عدّاد جمل الوضع البصري — (v249) جنب كبسولة الصوت شمال فوق
    lateinit var netBadge: NetBadge   // (v249) كبسولة سحب النت — منفصلة، نفس مكان الكبسولتين
    private var badgeHotUntil = 0L   // (v249) لما تدوس كبسولة بتبقى 100% لحد الوقت ده، وبعدها ترجع للشفافية المضبوطة من الإعدادات
    private fun badgeHot() { badgeHotUntil = System.currentTimeMillis() + 4000; applyBadgeAlpha() }
    private lateinit var badgeRowV: LinearLayout
    /** (v323) العدّادات (صوتي/بصري/نت) مخفية خالص والشاشة نضيفة؛ لما أزرار المشغّل تظهر (لمسة) بتظهر 100%. BADGE_IDLE_A لو عايزها باهتة بدل مخفية (مثلًا 0.3f) */
    private val BADGE_IDLE_A = 0.3f   // (v432) عدّاد الجمل باهت 30% حتى والأزرار مخفية (كان 0.5)
    private fun applyBadgeAlpha() {
        if (!::sentCap.isInitialized || !::netBadge.isInitialized || !::badgeRowV.isInitialized) return
        val a = if (tvGateClosed()) 0f else if (chromeShown || (DeviceUi.isRemote(this) && tvBar?.visible == true) || System.currentTimeMillis() < badgeHotUntil) 1f else BADGE_IDLE_A   // (v509) تلفزيون: العدّادات مخفية خالص لحد ما OK يظهر الشريط
        if (badgeRowV.alpha != a) badgeRowV.alpha = a
        val v = if (a <= 0.01f) View.INVISIBLE else View.VISIBLE   // INVISIBLE = مايستقبلش لمس
        if (badgeRowV.visibility != v) badgeRowV.visibility = v
    }
    lateinit var fsBtnV: TextView
    lateinit var fsBarFs: TextView
    lateinit var fsBtnLp: FrameLayout.LayoutParams
    lateinit var centerPlay: TextView
    lateinit var ctl: Ctl
    lateinit var mem: MemRow
    lateinit var fsProgV: DualProgress
    // أشرطة التقدم: كاش مجالات التغطية والجمل المستنية (بيتحدّث لما الجمل أو عدد المستني يتغيّر بس)
    private var segSubsRef: List<Sub>? = null; private var segPendN = -1
    private var segCov: List<DoubleArray> = emptyList(); private var segPend: List<DoubleArray> = emptyList()
    private var segVis: List<DoubleArray> = emptyList(); private var segVisN = -1   // (v226) علامات الوضع البصري على الشريط
    private var visList: List<VisEntry> = emptyList()   // (v226) جمل الوضع البصري في قايمة 📝
    private fun updVis() { visList = try { if (::visual.isInitialized) visual.entries().sortedBy { it.start } else emptyList() } catch (_: Throwable) { emptyList() } }
    /** (v160) الأجزاء المحمّلة من الفيديو الأونلاين (بتتراكم أثناء المشاهدة وبتتدمج) */
    private val bufSegs = ArrayList<DoubleArray>()
    private fun addBuf(a: Double, b: Double) {
        if (b - a < 0.5) return
        var s = a; var e = b
        val it = bufSegs.iterator()
        while (it.hasNext()) { val x = it.next(); if (x[1] >= s - 1.0 && x[0] <= e + 1.0) { s = minOf(s, x[0]); e = maxOf(e, x[1]); it.remove() } }
        bufSegs.add(doubleArrayOf(s, e))
    }
    lateinit var fsEl: TextView
    lateinit var fsDu: TextView
    lateinit var fsPlayB: TextView
    lateinit var sentDlg: android.app.Dialog
    lateinit var logDlg: android.app.Dialog
    var chromeShown = false
    var fsOnly: List<View> = emptyList()
    private lateinit var topTitle: TextView   // (v330) اسم الفيديو الشغّال (فوق شمال، جنب كبسولات العدّ)
    private var fixTopRowFn: () -> Unit = {}
    private var tbView: View? = null   // (v265) الشريط العلوي (أزرار المشغّل)
    var lastMem = 0L
    var applyChromeFn: () -> Unit = {}
    var nextPanel: NextPanel? = null   // (v378) لوحة «التالي» تحت أزرار المشغّل (يوتيوب)
    var dismissPopFn: () -> Unit = {}
    var restyleFn: () -> Unit = {}
    private var resumeAfterSettings = false
    val hideChrome = Runnable { chromeShown = false; applyChromeFn() }
    var fullMode = false
    private var uiLocked = false
    private lateinit var lockOv: TextView
    private val hideLockOv = Runnable { if (::lockOv.isInitialized) lockOv.visibility = View.GONE }
    private var askSaved = false
    private var autoTr = false
    private var stripV: View? = null
    private val hideStrip = Runnable { dismissStrip() }
    fun dismissStrip() { h.removeCallbacks(hideStrip); stripV?.let { try { (it.parent as? ViewGroup)?.removeView(it) } catch (e: Exception) { LogStore.err("Main:823", e) } }; stripV = null }
    // (v91) شريط «كمّل / من الأول / بدون ترجمة» اتمسح بالكامل
    fun setLock(b: Boolean) {
        uiLocked = b
        if (b) { h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn(); say("🔒 الشاشة مقفولة — المس الشاشة واضغط على القفل لفتحها") }
        else { lockOv.visibility = View.GONE; showChrome() }
    }
    // ===== إشعار التحكم (v87): الحالة بتتبعت للخدمة، والأوامر بترجع من الإشعار على remote() =====
    private var lastNotifAt = 0L
    private var notifTitleOv: String? = null
    fun notifTitle(): String {
        val t = notifTitleOv ?: intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid)
        return t.substringBeforeLast('.', t)
    }
    /** (v360) اسم الفيديو + تحته سطر صغير بملخص إعداد الترجمة المحفوظ (بيفضل مع أي فيديو) */
    private fun titleWithSummary(): CharSequence {
        val t = notifTitle(); if (t.isBlank()) return ""
        val s = try { listOf(TrWizard.summaryLine(), TrWizard.langLine()).filter { it.isNotBlank() }.joinToString("\n") } catch (_: Throwable) { "" }   // (v413) + سطر كشف اللغة
        if (s.isBlank()) return t
        val sb = android.text.SpannableStringBuilder(t); val st = sb.length
        sb.append("\n").append(s)
        sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), st, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(0xFFBFD0FF.toInt()), st, sb.length, 0)
        return sb
    }
    fun pushNotif() {
        try {
            val st = NotifState
            st.title = notifTitle(); st.posMs = player.currentPosition.coerceAtLeast(0); st.durMs = if (durMs > 0) durMs else 0L
            st.playing = player.isPlaying
            st.trState = if (!::engine.isInitialized || !engineStarted) 0 else if (engine.userPaused) 2 else 1
            st.trPct = if (durMs > 0 && ::engine.isInitialized) PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0).toInt() else 0
            KeepAliveService.refresh()
        } catch (e: Throwable) { LogStore.err("pushNotif", e) }
    }
    fun exitAll() {
        if (remote) { rmFrames = false; finishAndRemoveTask(); return }   // (v568) إشعار التحكم: اقفل شاشة التحكم بس، التلفزيون يكمّل
        try { player.pause() } catch (e: Exception) { LogStore.err("exitAll", e) }
        saveRecent(); Thread { try { engine.saveNow() } catch (e: Exception) { LogStore.err("Player:exitSave", e) } }.start()   // (v287) فشل الحفظ كان صامت
        finishAndRemoveTask()
    }
    /** أوامر الإشعار: toggle / prev / next / seek / tr / exit */
    fun remote(cmd: String, arg: Long = 0L) {
        runOnUiThread {
            try {
                when (cmd) {
                    "toggle" -> togglePlay()
                    "prev" -> stepEpisode(-1)
                    "next" -> stepEpisode(1)
                    "seek" -> if (durMs > 0) player.seekTo(arg.coerceIn(0L, durMs))
                    "tr" -> if (engineStarted && !engine.userPaused) pauseTranslate() else beginTranslate()   // (v228) من غير translateFrom بعدها: كان بيلغي إعداد «ترجم من الأول» ويبدأ من مكان التشغيل
                    "exit" -> exitAll()
                }
                lastNotifAt = System.currentTimeMillis(); pushNotif()
            } catch (e: Throwable) { LogStore.err("remote:$cmd", e) }
        }
    }
    // (v469) أسماء أوضاع العرض: الثلاثة الأصلية من PlayerLogic + وضع رابع «100%» (حجم طبيعي 1:1 مع زوم بالقرص) بيتحسب هنا
    private var vidZoomF = 1f
    fun fitNameOf(m: Int): String = if (m == 3) "100%" else PlayerLogic.fitNames[m.coerceIn(0, 2)]
    fun cycleFitNow() {
        if (remote && !rmFitSkip) {   // (v604) الاحتواء/التكبير: موبايل ولا تلفزيون؟
            rmChoose("📐 شكل الصورة", "📱 على الموبايل بس", "📺 على التلفزيون", { rmFitSkip = true; try { cycleFitNow() } finally { rmFitSkip = false } }, {
                LinkSession.send("act", org.json.JSONObject().put("a", "fit"))
            })
            return
        }
        if (isLandNow()) { fsFit = (fsFit + 1) % 4; Cfg.p.edit().putString("fs_fit", fsFit.toString()).apply() }
        else { fit = (fit + 1) % 4; Cfg.p.edit().putString("fit", fit.toString()).apply() }
        fitFsB?.text = "⬛ " + fitNameOf(curFit()); applyFit(svRef, videoBoxRef)
        say("⬛ " + fitNameOf(curFit()) + (if (curFit() == 3) " — اقرص بصباعين للتكبير/التصغير" else ""))
    }
    var vid = ""
    // ===== استكمال/إعادة ترجمة + ترجمة في الخلفية =====
    lateinit var resumeCard: LinearLayout
    lateinit var resumeTv: TextView
    private var resumePending = false
    private var handedOff = false
    fun applyCard() { if (::resumeCard.isInitialized) resumeCard.visibility = if (resumePending && !pipNow()) View.VISIBLE else View.GONE }
    private fun placeCard() {
        if (!::resumeCard.isInitialized) return
        val lp = resumeCard.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = ui.dp(if (fullMode) 96 else 6); resumeCard.layoutParams = lp
    }
    /** الاختيار (كمّل / من الأول) بيتعمل قبل دخول الفيديو من المكتبة — هنا الترجمة بتكمّل تلقائي من غير كارت ولا وقفة */
    private fun maybePrompt(wasBg: Boolean) {
        engine.keepGoing = Cfg.bool("keepgoing", true)
        engine.paused = false
        resumePending = false; applyCard()
        status = "دوس «ترجمة» لما تحب تبدأ"
        if (wasBg) say("🌙 كان بيترجم في الخلفية — دوس «ترجمة» تكمّل من مكانه")
    }

    // ===== الترجمة بإيد المستخدم: مفيش بداية تلقائية. زرار «ترجمة» أحمر ← بيتبدّل بـ ⏸ إيقاف مؤقت / ▶ إلغاء الإيقاف =====
    private var engineStarted = false
    private var noSub = false                     // دخلت بـ «فرجة من غير ترجمة»: الترجمة مخفية والمحرك واقف لحد ما تدوس «ترجمة»
    var ccToggleFn: () -> Unit = {}
    private val trUpdaters = ArrayList<() -> Unit>()
    var tbCollapseFn: () -> Unit = {}
    private var lastWp = -2
    var trPct = 0   // نسبة الترجمة (بتتحدّث كل 200ms) — للشريحة الصغيرة في الشريط السفلي
    private val logAuto = Runnable { if (logOn) toggleLog() }     // لوحة الباتشات بتتلم لوحدها
    private val probAuto = Runnable { if (probOn) toggleProb() }  // كبسولة الباتش الفاشل بتتلم لوحدها
    fun updateTr() { applyModeCaps(); trUpdaters.forEach { try { it() } catch (e: Exception) { LogStore.err("Main:866", e) } } }
    // (v552) سوفت صب: لما تدوس «ترجم» (جيميناي/ويسبر/أي محرك) والفيديو فيه ترجمة جاهزة، بنسألك تترجمها بدل الصوت وتختار المسار — بدل زرار «ترجمة السوفت صب» القديم
    @Volatile private var softAskedFor = ""
    private fun softGate(): Boolean {
        val vu = uri ?: return false
        val key = vu.toString()
        if (softAskedFor == key || !::engine.isInitialized || engineStarted || engine.subs.isNotEmpty() || sfBusy.isNotEmpty()) return false
        softAskedFor = key
        Thread {
            try {
                val side = sfSidecar()
                val emb = if (vu.scheme == "file" || vu.scheme == "content") SubFileTr.embeddedTracks(applicationContext, vu) else emptyList()
                val nat = if (emb.isEmpty()) sfNativeCount() else 0
                if (side == null && emb.isEmpty() && nat == 0) { runOnUiThread { beginTranslate(false) }; return@Thread }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
                    fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
                    val eng = try { TrWizard.savedPlan()?.let { if (it.offline) "google" else "gemini" } } catch (_: Throwable) { null } ?: if (TrWizard.hasGeminiKey()) "gemini" else "google"
                    if (side != null) add("📄 ترجمة ملف «" + side.name + "» اللي جنب الفيديو") { sfRun("soft", eng, null, -2) }
                    emb.forEach { (i, nm) -> add("💬 ترجمة المسار: $nm") { sfRun("soft", eng, null, i) } }
                    if (emb.isEmpty() && nat > 0) add("💬 ترجمة مسار الفيديو الأصلي (لحظيًا)") { sfRun("soft", eng) }
                    add("📂 اختار ملف ترجمة من الجهاز") { sfPickFile(eng) }
                    add("🎙 ماترجمش الترجمة — ترجم الصوت") { beginTranslate(false) }
                    GAlert(this).setTitle("💬 الفيديو ده فيه ترجمة جاهزة").setItems(labels.toTypedArray()) { _, w -> if (w in acts.indices) acts[w]() }.show()
                }
            } catch (e: Throwable) { LogStore.err("Player:softGate", e); runOnUiThread { beginTranslate(false) } }
        }.also { it.isDaemon = true }.start()
        return true
    }

    fun beginTranslate(auto: Boolean = false) {
        if (remote) { remoteBegin(auto); return }
        if (!auto && softGate()) return
        if (Cfg.str("hard_mode", "0") == "1" && !SourceMode.keepHard()) {   // (v291) الهجين بهارد سب محتاج hard_mode=1 والصوت شغّال
            Cfg.put("hard_mode", "0")   // (v240) أي ترجمة صوتية بتطلّعك من وضع الهارد سب
            // (v261) ووقّف الوضع البصري بتاع الهارد سب كمان، وإلا بيفضل شغّال جنب الترجمة الصوتية
            try { if (::visual.isInitialized && visual.running) { visual.stop(); visOv.showBoxes(emptyList()); visAutoRun = false; visLog("👁 اتقفل — بدأت ترجمة صوتية") } } catch (_: Throwable) {}
        }
        try { val st = Throwable().stackTrace; LogStore.add("▶ طلب بدء الترجمة ← " + (st.getOrNull(1)?.let { it.methodName + ":" + it.lineNumber } ?: "?") + (if (autoTr) " (autotr)" else "")) } catch (_: Throwable) {}
        // (v467) اللغة الأساسية: البدء التلقائي بيحترمها، واليدوي (زرار «ترجم») بيفرض الترجمة ويلغي أي تخطي حصل في نفس الجلسة
        if (::engine.isInitialized) { engine.baseLangCode = Cfg.str("base_lang", "ar"); if (auto) engine.skipIfBaseLang = true else { engine.skipIfBaseLang = false; if (engine.baseSkipped) { engine.baseSkipped = false; engine.transOnly = false } } }
        if (::engine.isInitialized && !engineReady) { pendingBegin = true; return }   // البيانات المحفوظة لسه بتتحمّل: هتبدأ لوحدها أول ما تخلص
        // (v203) لو الفيديو مترجم 100% (مثلًا بعد تحديث لينكه): مفيش ترجمة تاني، الترجمة المحفوظة شغّالة. ولو ناقص بيكمّل الباقي بس (المخلّص بيتخطّى)
        if (::engine.isInitialized && !engineStarted && !freshOnce && (try { engine.fullyDone() } catch (_: Throwable) { false })) {
            dismissStrip()
            if (noSub) { noSub = false; if (!ccOn) ccToggleFn() }
            Notice.show(this, "✓ الفيديو ده مترجم 100% — مترجمتش حاجة (لو عايز تعيد: 🔁 إعادة)", 3600L)
            return
        }
        dismissStrip()
        if (noSub) { noSub = false; if (!ccOn) ccToggleFn() }   // بدأت ترجمة: رجّع إظهار الترجمة
        // (v134) الترجمة تبدأ من المكان اللي واقف فيه في الفيديو (مش من الأول): المؤشر بيتحط على باتش الوقت الحالي، واللي اتترجم قبل كده بيتخطّى
        val wasFresh = freshOnce
        val posNow = try { player.currentPosition } catch (_: Throwable) { cur }
        if (posNow > 0) cur = posNow
        // (v228) المؤشر (من الأول / من مكان التشغيل) بيتظبط **قبل** ما خيط المحرك يشتغل — قبل كده كان بيتظبط بعده فالمحرك يلحق يبعت باتشات مكان التشغيل (30 و31 و32) وبعدين يرجع يبعت 1 و2 و3
        if (!wasFresh) {
            // (v224) الافتراضي: الترجمة بتبدأ من أول باتش ناقص في الفيديو من الأول (مش من مكان التشغيل اللي اتكمّل تلقائيًا). ممكن تتغيّر من قايمة 🔑
            if (Cfg.str("trstart", "1") != "0") { try { engine.translateFromStart(); log("▶ الترجمة من أول الفيديو (أول جزء ناقص)") } catch (e: Throwable) { LogStore.err("Player:translateFromStart", e); say("⚠ تعذّر بدء الترجمة من أول الفيديو — جرّب ▶ ترجمة تاني") } }   // (v287)
            else if (posNow > 2000L) { try { engine.translateFrom(posNow / 1000.0); log("▶ الترجمة من " + fmtMs(posNow)) } catch (e: Throwable) { LogStore.err("Player:translateFrom", e); say("⚠ تعذّر بدء الترجمة من مكان التشغيل — جرّب ▶ ترجمة تاني") } }   // (v287)
        }
        if (!engineStarted) { engineStarted = true; engine.userPaused = false; startEngine() }
        else {
            // (v399) استئناف: لو الوضع المحفوظ اتغيّر من ⚙/الإعدادات بعد الإيقاف بنطبّقه على المحرك قبل ما نكمّل (كان بيكمّل بالوضع القديم)
            try { TrWizard.savedPlan()?.let { sp -> if (appliedSig.isNotEmpty() && planSig(sp) != appliedSig) { LogStore.add("🔄 الوضع المحفوظ اختلف عن اللي شغّال — بطبّقه قبل الاستئناف"); runPlanCore(sp, false) } } } catch (e: Throwable) { LogStore.err("Player:resumeSync", e) }
            engine.userPaused = false
            engineKick()
        }
        updateTr()
    }
    @Volatile private var appliedSig = ""
    /** (v399) بصمة الخطة (الوضع + تفريغ بس + المحرك + الطريقة + البريست) عشان نعرف لو اتغيّرت */
    private fun planSig(pl: TrWizard.Plan) = listOf(pl.mode, pl.only, pl.engine, pl.method, pl.onlineId, pl.lang, pl.model, pl.dialect, pl.dia ?: "-", Cfg.str("tr_mode_id", "")).joinToString("|")
    /** (v399) لو المحرك كان خلّص ووقف (مثلًا «تفريغ بس» خلص) والوضع اتغيّر: يبدأ الحلقة من جديد عشان الباقي يتترجم */
    private fun engineKick() {
        try { if (engineStarted && ::engine.isInitialized && engine.reopen()) { LogStore.add("♻ المحرك كان واقف — بشغّله تاني بالوضع الجديد"); startEngine() } } catch (e: Throwable) { LogStore.err("Player:engineKick", e) }
    }
    private var trChipV: View? = null
    private var trChipDismissed = false
    private fun updateTrChip() { trChipV?.visibility = if (engineStarted || trChipDismissed || pipNow()) View.GONE else View.VISIBLE }
    /** (v438) اسم موديل FreeLLM الشغّال على شريحة الترجمة */
    private fun modeChipTag(): String = if (Strict.freeText() || Strict.freeGem()) "🆓 " + FreeLlm.liveName().take(20) + " · " else ""
    fun pauseTranslate() { if (engineStarted) { engine.userPaused = true; updateTr() } }
    /** (v427) فيه محرك شغّال أو ترجمة موجودة؟ (الإعادة ملهاش معنى قبل كده) */
    private fun hasSubsNow(): Boolean = try { ::engine.isInitialized && (engineStarted || engine.subs.isNotEmpty()) } catch (_: Throwable) { false }
    /** صف الأزرار: [▶ ترجمة] (أحمر) قبل البداية — وبعد الضغط يختفي ويظهر مكانه [⏸ إيقاف مؤقت] [▶ إلغاء الإيقاف] */
    private fun makeTrRow(compact: Boolean): ViewGroup {
        // (v144) الشريط السفلي: صف بيلفّ لوحده (FlowRow) والعلامة جواه — مفيش عمود رأسي ولا عرض ثابت يخلي الأزرار تتقص أو الشريحة تعلق
        val row: ViewGroup = if (compact) FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            else LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        fun b(t: String, fill: Int, f: () -> Unit) = IconTextView(this).apply {
            text = t; textSize = if (compact) 12f else 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(if (compact) 10 else 8), 0, ui.dp(if (compact) 10 else 8), 0)
            background = ui.box(fill, 0x33FFFFFF, if (compact) 8 else 10); setOnClickListener { f() }
        }
        val hh = ui.dp(if (compact) 40 else 44); val m = ui.dp(if (compact) 3 else 4)
        fun lp(w: Int) = LinearLayout.LayoutParams(w, hh).apply { setMargins(m, m, m, m) }
        fun sq() = LinearLayout.LayoutParams(hh, hh).apply { setMargins(m, m, m, m) }   // (v440) زراير الأيقونة (⚙ 🔁 ⬇) مربعة بنفس الارتفاع بدل عرض بيتقص
        fun tidy(v: TextView) { v.setSingleLine(); v.includeFontPadding = false }
        val webVid = uri == null && (url != null || intent.getStringExtra("ytid") != null)   // (v378) يوتيوب: اللينك بيتجاب بعد بناء الشريط، فزرار الجودة كان بيختفي
        var dlRef: TextView? = null
        val dlB = b("⬇", 0xFF37474F.toInt()) { dlRef?.let { downloadPopup(it) } }
        dlRef = dlB
        var trRef: TextView? = null
        val tr = b("▶ ترجم ▾", 0xFFE53935.toInt()) { trRef?.let { translateMenu(it) } }   // (v254) قبل البداية: الإعداد المحفوظ أو شاشة «طريقة الترجمة»
        trRef = tr
        var wzRef: TextView? = null
        val wzB = b("⚙", 0xFF37474F.toInt()) { wzRef?.let { modeSettings(it) } }   // (v427) إعدادات الوضع الحالي (بتتطبّق لايف) — غير اللغة واللهجة
        wzRef = wzB
        var lgRef: TextView? = null
        val lgB = b("🌐", 0xFF37474F.toInt()) { lgRef?.let { langPopup(it) } }
        lgRef = lgB
        trLgB = lgB
        fun qBtn(): TextView {
            val cur = qOpts.getOrNull(qCur)?.h ?: 0
            val v = b(if (cur > 0) "${cur}p ▾" else "الجودة ▾", 0xFF37474F.toInt()) { }
            v.setOnClickListener { qualPopFn?.invoke(v) }
            qualRowRefs.add(v); return v
        }
        fun aBtn(): TextView {   // (v388) مربع لغة الصوت: بيظهر بس لو الفيديو له أكتر من لغة
            val v = b("🔊 ▾", 0xFF37474F.toInt()) { }
            v.setOnClickListener { audPopFn?.invoke(v) }
            audRowRefs.add(v); v.visibility = View.GONE; paintAud(); return v
        }
        if (compact) {
            // الشريط السفلي: شريحة صغيرة بنسبة الترجمة (⏳ 19%) — دوس عليها تتفرد: إيقاف/استئناف + اللغة، وبتتلم لوحدها بعد 5 ثواني
            var open = false
            val closeR = Runnable { open = false; updateTr() }
            var chipRef: TextView? = null
            val chipB = b("▶ ترجم ▾", 0xFFE53935.toInt()) { chipRef?.let { translateMenu(it) } }   // (v415) زرار واحد بقايمة منسدلة
            chipRef = chipB
            var rdRef: TextView? = null
            val rd = b("🔁", 0xFF6A1B9A.toInt()) { h.removeCallbacks(closeR); h.postDelayed(closeR, 8000); rdRef?.let { redoDialog(it) } }
            rdRef = rd
            val pz = b("▮▮", 0xFF455A64.toInt()) { if (::engine.isInitialized && engine.userPaused) beginTranslate() else pauseTranslate() }   // (v438) إيقاف مؤقت / كمّل ظاهر جنب «ترجم» (كان جوه القايمة بس)
            tidy(chipB); tidy(pz); pz.setPadding(0, 0, 0, 0); pz.textSize = 17f; wzB.setPadding(0, 0, 0, 0); rd.setPadding(0, 0, 0, 0); dlB.setPadding(0, 0, 0, 0)
            row.addView(chipB, lp(-2))   // (v415) زرار «ترجم» الواحد
            row.addView(pz, sq())
            row.addView(wzB, sq()); row.addView(rd, sq())   // (v427) ⚙ إعدادات الوضع + 🔁 إعادة جنبه   // (v224) زرار المهام اتشال؛ 🌐 و🔑 في الشريط السفلي جنب 🗣
            if (webVid) { row.addView(qBtn(), lp(-2)); row.addView(aBtn(), lp(-2)); row.addView(dlB, sq()) }
            trUpdaters.add {
                val paused = ::engine.isInitialized && engine.userPaused
                val started = engineStarted
                if (!started) open = false
                rd.visibility = if (hasSubsNow()) View.VISIBLE else View.GONE   // (v427) الإعادة بتظهر لما يبقى فيه ترجمة
                pz.visibility = if (started) View.VISIBLE else View.GONE
                pz.text = if (paused) "▶" else "▮▮"
                pz.background = ui.box(if (paused) 0xFF2E7D32.toInt() else 0xFF455A64.toInt(), 0x33FFFFFF, 8)
                lgB.visibility = if (caps().dialect) View.VISIBLE else View.GONE   // (v444) بس في الأوضاع اللي فيها لهجات
                if (!started) { chipB.text = if (sfBusy.isNotEmpty()) "⏳ جاري الترجمة…" else "▶ ترجم · " + modeShort() + " ▾"; chipB.background = ui.box(0xFFE53935.toInt(), 0x33FFFFFF, 12) }
                else {
                    chipB.text = (Offline.liveShort()?.let { "$it · " } ?: modeChipTag()) + (if (paused) "⏸ " else if (trPct >= 100) "✅ " else "⏳ ") + trPct + "%" + " ▾"
                    chipB.background = ui.box(if (paused) 0xFF8D6E00.toInt() else if (trPct >= 100) 0xFF2E7D32.toInt() else 0xFF1F5FBF.toInt(), 0x33FFFFFF, 12)
                }
            }
        } else {
            var rd2Ref: TextView? = null
            val rd2 = b("🔁", 0xFF6A1B9A.toInt()) { rd2Ref?.let { redoDialog(it) } }
            rd2Ref = rd2
            val pz2 = b("▮▮", 0xFF455A64.toInt()) { if (::engine.isInitialized && engine.userPaused) beginTranslate() else pauseTranslate() }   // (v438)
            tidy(tr); tidy(pz2); pz2.setPadding(0, 0, 0, 0); pz2.textSize = 19f; wzB.setPadding(0, 0, 0, 0); rd2.setPadding(0, 0, 0, 0); dlB.setPadding(0, 0, 0, 0)
            row.addView(tr, lp(0).apply { width = 0; weight = 3.6f })   // (v415) زرار «ترجم» الواحد
            row.addView(pz2, sq())
            row.addView(wzB, sq()); row.addView(rd2, sq())   // (v427) ⚙ + 🔁 جنب «ترجم»
            if (webVid) { row.addView(qBtn(), lp(0).apply { width = 0; weight = 1.2f }); row.addView(aBtn(), lp(0).apply { width = 0; weight = 0.9f }); row.addView(dlB, sq()) }
            trUpdaters.add {
                val paused = ::engine.isInitialized && engine.userPaused
                                tr.visibility = View.VISIBLE
                                tr.text = if (!engineStarted) (if (sfBusy.isNotEmpty()) "⏳ جاري الترجمة…" else "▶ ترجم · " + modeShort() + " ▾") else (Offline.liveShort()?.let { "$it · " } ?: modeChipTag()) + (if (paused) "⏸ " else if (trPct >= 100) "✅ " else "⏳ ") + trPct + "% ▾"
                rd2.visibility = if (hasSubsNow()) View.VISIBLE else View.GONE   // (v427)
                pz2.visibility = if (engineStarted) View.VISIBLE else View.GONE
                pz2.text = if (paused) "▶" else "▮▮"
                pz2.background = ui.box(if (paused) 0xFF2E7D32.toInt() else 0xFF455A64.toInt(), 0x33FFFFFF, 10)
            }
        }
        updateTr()
        if (!webVid) return row
        // (v134) علامة صغيرة فوق زرار الترجمة: الجودة + الحجم (بتظهر لما الفيديو له جودة واحدة بس) — دوس عليها تعرف الحجم
        val badge = IconTextView(this).apply {
            textSize = 10.5f; setTextColor(0xFFFF6B6B.toInt()); gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(2)); background = ui.box(0xCC141418.toInt(), 0x33FFFFFF, 10)
            setOnClickListener { refreshSize(true) }
        }
        qBadges.add(badge)
        if (compact) {
            // (v144) في الشريط السفلي العلامة بقت آخر عنصر في نفس الصف (مش فوق الشريحة) — فضغطة الشريحة بتفرد إعادة/اللغة عادي
            row.addView(badge, LinearLayout.LayoutParams(-2, ui.dp(28)).apply { setMargins(m, m, m, m) })
            paintQual()
            return row
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_HORIZONTAL }
        wrap.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, ui.dp(2), 0, 0) })
        wrap.addView(row, LinearLayout.LayoutParams(-1, -2))
        paintQual()
        return wrap
    }
    private val hideCard = Runnable { resumePending = false; applyCard() }
    private fun fmtMS(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }

    private var batchPanel: View? = null
    lateinit var batchBtn: View
    private lateinit var audBtn: View   // (v343) 🎧 حوّل لصوت
    private lateinit var modePill: TextView   // (v459) كبسولة عريضة لليوتيوب: «🎧 وضع الصوت / 🎬 وضع الفيديو» — تبديل جوه المشغّل نفسه · (v467) مكانها بقى جوّه صف الكبسولات جنب 👁 مش عايمة لوحدها
    private var badgeRowRef: LinearLayout? = null   // (v467) صف الكبسولات (🔊/👁/النت) — الكبسولة بتتنقل له أول ما يتبني
    private var ytAudioOnly = false
    private var audioHandoff: Intent? = null
    fun closeBatchPanel() {
        batchPanel?.let { p -> try { (p.parent as? ViewGroup)?.removeView(p) } catch (e: Exception) { LogStore.err("Main:938", e) } }
        batchPanel = null
        if (::batchBtn.isInitialized) fadeTo(batchBtn, REST_A)
    }
    /** زرار 🔄 العايم: لوحة صغيرة فوق الفيديو — «من الأول خالص» + باتشات الفيديو (📍 يوديك للباتش) + «ابدأ من هنا» / «ده بس» */
    fun batchDialog() {
        if (batchPanel != null) { closeBatchPanel(); return }
        val bl = engine.batches()
        if (bl.isEmpty()) { Notice.show(this, ("مدة الفيديو لسه مش معروفة — استنى ثانية وجرّب تاني").toString(), 2300L); return }
        val curC = try { engine.chunkOfSec(player.currentPosition / 1000.0) } catch (_: Exception) { 0 }
        var sel = curC
        val rows = ArrayList<Pair<Int, LinearLayout>>()
        val selTv = ui.text("", 12f, th.primary, true)
        // الشغل التقيل (مسح + حفظ على القرص) بعيد عن الـ UI thread — ده اللي كان بيهنّج ويقفل البرنامج
        fun go(label: String, work: () -> Unit) {
            closeBatchPanel(); resumePending = false; applyCard()
            Notice.show(this, (label).toString(), 2300L)
            engine.paused = true
            Thread {
                try { work() } catch (e: Throwable) { val m = "⚠ " + (e.message ?: e.toString()).take(120); log(m); runOnUiThread { say(m) }; engine.paused = false }
                dirty = true; curIdx = -2
                runOnUiThread { beginTranslate() }
            }.apply { isDaemon = true }.start()
        }
        fun paint() {
            rows.forEach { (i, r) -> r.background = ui.box(if (i == sel) (th.primary and 0x00FFFFFF) or 0x33000000 else th.card, th.border, 8) }
            selTv.text = "المختار: باتش ${sel + 1}  (${fmtMS(bl[sel.coerceIn(0, bl.size - 1)].start)})"
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(10)); background = ui.box(0xF2101418.toInt(), 0x33FFFFFF, 14); elevation = ui.dp(10).toFloat()
            isClickable = true
        }
        val head = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.text("🔄 ترجم باتش معين", 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconTextView(this).apply { text = "✕"; textSize = 18f; setTextColor(Color.WHITE); setPadding(ui.dp(10), ui.dp(2), ui.dp(4), ui.dp(2)); setOnClickListener { closeBatchPanel() } })
        col.addView(head)
        // (v468) فخ redoAll: اختيار صريح في لوحة الباتشات بدل الافتراضي الصامت keep=true — بيشارك redoKeep مع قايمة 🔁
        var keepBtn: android.widget.TextView? = null
        fun keepLabel() = if (redoKeep) "🗂 احتفظ بالقديم: شغّال (دوس للتبديل)" else "🗑 امسح وابدأ نضيف: شغّال (دوس للتبديل)"
        val kb = ui.button(keepLabel()) { redoKeep = !redoKeep; keepBtn?.text = keepLabel() }
        keepBtn = kb; col.addView(kb)
        col.addView(ui.button("🔁 ترجم من الأول خالص", true) { val k = redoKeep; player.seekTo(0); go("🔄 بترجم من الأول…") { engine.redoAll(k) } })
        col.addView(ui.text("📍 دوس على الباتش يوديك له، وبعدين اختار ابدأ", 11f, th.muted))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        var curRow: View? = null
        for (b in bl) {
            val i = b.idx
            val row = LinearLayout(this).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7))
                addView(IconTextView(this@PlayerActivity).apply { text = "📍"; textSize = 16f; setPadding(0, 0, ui.dp(8), 0) })
                addView(ui.text("باتش ${i + 1} · ${fmtMS(b.start)}–${fmtMS(b.end)} ${b.mark}" + (if (b.count > 0) " · ${b.count} جملة" else "") + (if (i == curC) "  ◀ هنا" else ""), 12f, th.text, i == curC), LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { sel = i; try { player.seekTo((engine.chunkStartSec(i) * 1000).toLong()) } catch (e: Exception) { LogStore.err("Main:985", e) }; paint() }
            }
            rows.add(i to row); list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
            if (i == curC) curRow = row
        }
        val content = findViewById<ViewGroup>(android.R.id.content)
        val maxH = (content.height * 0.26).toInt().coerceAtLeast(ui.dp(84))
        val sv = ScrollView(this).apply { addView(list) }
        col.addView(sv, LinearLayout.LayoutParams(-1, maxH))
        col.addView(selTv)
        val btns = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        btns.addView(ui.button("▶ ابدأ من الباتش ده") { val i = sel; val k = redoKeep; go("🔄 بترجم من باتش ${i + 1} وبعده…") { engine.redoFrom(i, k) } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        btns.addView(ui.button("☝ ده بس") { val i = sel; val k = redoKeep; go("🔄 بترجم باتش ${i + 1} لوحده…") { engine.redoOnly(i, k) } }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(btns)
        paint()
        val w = minOf(ui.dp(250), content.width - ui.dp(24)).coerceAtLeast(ui.dp(200))
        // شفافة بتغطي الشاشة: أي ضغطة بره القايمة بتقفلها، والفيديو باين وراها. القايمة صغيرة على الحافة (مش نص الشاشة)
        val scrim = FrameLayout(this).apply { isClickable = true; setOnClickListener { closeBatchPanel() } }
        scrim.addView(col, FrameLayout.LayoutParams(w, -2, Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { setMargins(0, ui.dp(8), ui.dp(70), ui.dp(8)) })   // RIGHT صريحة (END كانت بتتقلب لشمال جنب اللوج) + جنب زرار 🔄 مش فوقه
        content.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        batchPanel = scrim
        fadeTo(batchBtn, 1f)
        curRow?.let { r -> sv.post { sv.scrollTo(0, (r.top - ui.dp(40)).coerceAtLeast(0)) } }
    }

    /** نفس شغل «go» بتاع لوحة الباتشات: يمسح ويبدأ من جديد بعيد عن الـ UI thread */
    private fun redoGo(label: String, work: () -> Unit) {
        closeBatchPanel(); resumePending = false; applyCard()
        Notice.show(this, (label).toString(), 2300L)
        engine.paused = true
        Thread {
            try { work() } catch (e: Throwable) { val m = "⚠ " + (e.message ?: e.toString()).take(120); log(m); runOnUiThread { say(m) }; engine.paused = false }
            dirty = true; curIdx = -2
            runOnUiThread { beginTranslate() }
        }.apply { isDaemon = true }.start()
    }
    private var trMenuPop: android.widget.PopupWindow? = null
    /** (v415) زرار «ترجم» الواحد: قايمة منسدلة بالخيارات حسب الحالة + تقدم Whisper الحقيقي فوقها */
    fun translateMenu(anchor: View) {
        trMenuPop?.let { if (it.isShowing) { it.dismiss(); trMenuPop = null; return } }
        val started = engineStarted
        val paused = ::engine.isInitialized && engine.userPaused
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = glassPanelBg()
        }
        fun item(t: String, f: () -> Unit) = ui.fsBtn(t) { _ -> trMenuPop?.dismiss(); f() }.apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(42)).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) } }
        val head = if (!started) "اختار طريقة الترجمة" else (Offline.liveLine() ?: ("الترجمة: " + trPct + "%" + (if (paused) " (واقفة)" else "")))
        col.addView(ui.text(head, 12.5f, Color.WHITE, true).apply { setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(2)) })
        if (started) col.addView(ui.text("الوضع: " + modeName(), 11.5f, 0xFF9AA0A6.toInt()).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4)) })
        if (started && (Strict.freeText() || Strict.freeGem())) col.addView(ui.text("🆓 موديل FreeLLM الشغّال: " + FreeLlm.liveName(), 11.5f, 0xFFBFD0FF.toInt(), true).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4)) })
        if (!started) {
            // (v444) 3 طرق بس بدل 9: صوت←جيميناي (الأساسي) · وشبر على الجهاز (المترجم بيتختار تحت) + تفريغ بس
            fun grpLbl(t: String) = ui.text(t, 11.5f, 0xFF9AA0A6.toInt(), true).apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(2)) }
            col.addView(grpLbl("🎧 من الصوت مباشرة — أدق · محتاج نت"))
            col.addView(item("⭐ FreeLLM · جيميناي بكل مفاتيحك وموديلاته") { freeGemStart() })
            col.addView(grpLbl("🌗 على الجهاز — وشبر يفرّغ وبعدين مترجم"))
            col.addView(item("🌗 وشبر + " + wTrLabel()) { whisperStart() })
            val wr = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            for ((id, lb) in listOf("free" to "🆓 FreeLLM", "google" to "🌐 Libre")) {
                wr.addView(ui.fsBtn((if (id == wTr()) "✓ " else "") + lb) { _ -> Cfg.put("wtr", id); trMenuPop?.dismiss(); h.post { translateMenu(anchor) } },
                    ViewGroup.MarginLayoutParams(-2, ui.dp(32)).apply { setMargins(ui.dp(3), ui.dp(1), ui.dp(3), ui.dp(1)) })
            }
            col.addView(wr, LinearLayout.LayoutParams(-1, -2))
            col.addView(grpLbl("✍ تفريغ بس (من غير ترجمة)"))
            col.addView(item("☁ بجيميناي (من الصوت)") { startGeminiTranscribe() })
            col.addView(item("📴 على الجهاز بوشبر") { offlineQuick(true) })
        } else {
            col.addView(item(if (paused) "▶ كمّل الترجمة" else "⏸ إيقاف مؤقت") { if (paused) beginTranslate() else pauseTranslate() })
            col.addView(item("🔁 إعادة…") { h.post { redoDialog(anchor) } })
            col.addView(item("📊 تقدم الباتشات (كل باتش بنسبته)") { batchDialog() })
            col.addView(item("🔀 غيّر الوضع") { trWizard(true) })
            col.addView(item("🌙 كمّل الترجمة في الخلفية") { translateInBackground() })
        }
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val popW = minOf(resources.displayMetrics.widthPixels - ui.dp(24), ui.dp(300))
        col.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        val up = loc[1] > screenH / 2
        val avail = (if (up) loc[1] - ui.dp(12) else screenH - loc[1] - anchor.height - ui.dp(12)).coerceAtLeast(ui.dp(120))
        val popH = minOf(col.measuredHeight, avail)
        val pw = TvFocus.pop(this@PlayerActivity, scroll, popW, popH, true)
        Motion.popIn(scroll, up)   // (v445)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (trMenuPop === pw) trMenuPop = null; showChrome() }
        try { if (up) pw.showAsDropDown(anchor, 0, -(anchor.height + popH + ui.dp(2))) else pw.showAsDropDown(anchor, 0, ui.dp(2)) } catch (e: Throwable) { LogStore.err("Player:trMenu", e) }
        trMenuPop = pw
        h.removeCallbacks(hideChrome); showChrome(); h.removeCallbacks(hideChrome)
    }

    /** (v427) ☁ ترجمة بجيميناي: بيفتح خيارات جيميناي (جودة الصوت ← لهجة ← أسلوب ← شدة) وبعدها يبدأ الترجمة */
    private fun geminiPick() {
        if (!TrWizard.hasGeminiKey()) { say("☁ جيميناي محتاج مفتاح — ضيفه من الإعدادات ← 🔑 المفاتيح"); return }
        // (v435) من غير أسئلة: جودة الصوت + اللهجة + الأسلوب + الشدة بتتقرا من الإعدادات زي ما هي (وتتغيّر من ⚙ لو عايز)
        try {
            val id = if (AudioEnc.preset() == AudioEnc.FAST) "g_aac" else "g_wav"
            val dia = TrWizard.dialectWanted().ifBlank { "فصحى" }
            val pl = TrWizard.Plan("online", TrWizard.CURRENT, "", "", "gemini", dia = dia)
            Cfg.put("tr_mode_id", id); SourceMode.set(SourceMode.AUDIO)
            try { TrWizard.save(pl) } catch (e: Throwable) { LogStore.err("Player:geminiSave", e) }
            runPlan(pl)
            try { applyChromeFn() } catch (_: Throwable) {}
        } catch (e: Throwable) { LogStore.err("Player:geminiPick", e); say("تعذّر بدء الترجمة بجيميناي") }
    }

    /** (v440) 🆓 ترجمة FreeLLM الصوتية: زي جيميناي فلاش لايت — الصوت بيتبعت باتشات للموديلات المجانية وبترجع الترجمة جاهزة (من غير وشبر) */
    private fun freeGemStart() {
        if (!FreeLlm.audioReady()) { say("🆓 ضيف مفتاح جيميناي من الإعدادات ← 🔑 المفاتيح (أو رابط سيرفر FreeLLM)"); return }
        try {
            val dia = TrWizard.dialectWanted().ifBlank { "فصحى" }
            val pl = TrWizard.Plan("online", TrWizard.CURRENT, "", "", "gemini", dia = dia)
            Cfg.put("tr_mode_id", Strict.FREE_GEM); SourceMode.set(SourceMode.AUDIO)
            try { TrWizard.save(pl) } catch (e: Throwable) { LogStore.err("Player:freeGemSave", e) }
            runPlan(pl)
            try { applyChromeFn() } catch (_: Throwable) {}
            say("🆓 ترجمة FreeLLM — بيلف على كل مفاتيح جيميناي وكل موديلاتها، ولو موديل/مفتاح وقف بيكمّل بالتاني")
        } catch (e: Throwable) { LogStore.err("Player:freeGemStart", e); say("تعذّر بدء ترجمة FreeLLM") }
    }

    /** (v440) 🆓 وشبر + FreeLLM: تفريغ على الجهاز وترجمة بالموديلات المجانية (Gemini/Gemma/Groq) بمفاتيحك — الموزّع بيختار الأبعد عن حده */
    private fun freeStart() {
        if (!FreeLlm.ready()) { say("🆓 FreeLLM محتاج مفتاح Gemini أو Groq (أو رابط سيرفر) — اكتبه وادوس حفظ وبعدين ارجع دوس الزرار"); freeDialog(); return }
        offlineQuick(false, true)
    }

    private fun freeDialog() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(40, 20, 40, 0)
        fun et(hint: String, key: String) = android.widget.EditText(this).apply { this.hint = hint; setText(Cfg.str(key)); setSingleLine(true); box.addView(this) }
        val eg = et("مفتاح Gemini المجاني — لو سبته فاضي بياخد أول مفتاح جيميناي عندك", "fl_gem_key")
        val er = et("مفتاح Groq المجاني (console.groq.com/keys)", "fl_groq_key")
        val eu = et("اختياري: رابط سيرفر FreeLLM (مثلًا http://192.168.1.5:3000/v1)", "fl_url")
        val ek = et("اختياري: مفتاح السيرفر (LITELLM_MASTER_KEY)", "fl_srv_key")
        val em = et("اختياري: جروب/موديل السيرفر (large أو flash)", "fl_srv_model")
        val res = android.widget.TextView(this).apply { textSize = 12f; setPadding(0, 12, 0, 0); text = FreeLlm.statusText() }
        fun saveAll() {
            Cfg.put("fl_gem_key", eg.text.toString().trim()); Cfg.put("fl_groq_key", er.text.toString().trim())
            Cfg.put("fl_url", eu.text.toString().trim().trimEnd('/')); Cfg.put("fl_srv_key", ek.text.toString().trim()); Cfg.put("fl_srv_model", em.text.toString().trim())
            FreeLlm.resetAll()
        }
        val bt = android.widget.Button(this).apply {
            text = "🧪 جرّب"
            setOnClickListener {
                saveAll(); res.text = "⏳ بيجرّب…"
                Thread {
                    val r = FreeLlm.test()
                    runOnUiThread { res.text = (if (r.first) "✅ " else "❌ ") + r.second + "ms — " + r.third + "\n" + FreeLlm.statusText() }
                }.start()
            }
        }
        box.addView(bt); box.addView(res)
        val sv = android.widget.ScrollView(this).apply { addView(box) }
        android.app.AlertDialog.Builder(this).setTitle("🆓 FreeLLM").setView(sv)
            .setPositiveButton("حفظ") { _, _ ->
                saveAll()
                say("🆓 FreeLLM اتحفظ" + (if (FreeLlm.ready()) "" else " — ناقص مفتاح أو رابط سيرفر، مش هيشتغل لحد ما تكتبه") + " — بيتطبّق على الباتشات الجاية")
                try { applyChromeFn() } catch (_: Throwable) {}
            }
            .setNegativeButton("إلغاء", null).show()
    }

    /** (v427) ✍ تفريغ بجيميناي: النص بلغته الأصلية من غير ترجمة */
    private fun startGeminiTranscribe() {
        if (!TrWizard.hasGeminiKey()) { say("☁ الأونلاين محتاج مفتاح جيميناي — ضيفه من الإعدادات ← 🔑 المفاتيح"); return }
        val pl = TrWizard.Plan("online", "transcript", "", "", "gemini", only = true)
        Cfg.put("tr_mode_id", "ai_only"); SourceMode.set(SourceMode.AUDIO)
        try { TrWizard.save(pl) } catch (e: Throwable) { LogStore.err("Player:onQuickSave", e) }
        runPlan(pl)
    }

    // ===================== (v427) ⚙ إعدادات الوضع الحالي (بتتطبّق لايف) =====================
    private var setPop: android.widget.PopupWindow? = null

    /** الوضع الحالي: gem = ترجمة جيميناي · off = وشبر + Libre · txg = تفريغ جيميناي · txw = تفريغ وشبر */
    // ===================== (v444) إمكانيات كل وضع: الزراير والإعدادات اللي مش شغّالة في الوضع الحالي بتختفي =====================
    /** dialect = لهجة/لغة الترجمة · tone = أسلوب وعائلي/صريح/ضمائر/تنقيح (🗣) · visual = الوضع البصري 👁 · translates = بيترجم أصلًا */
    private class Caps(val dialect: Boolean, val tone: Boolean, val visual: Boolean, val translates: Boolean)
    private fun caps(mk: String = curModeKey()): Caps = when (mk) {
        "fgem" -> Caps(dialect = true, tone = true, visual = true, translates = true)     // صوت ← جيميناي (FreeLLM): كل حاجة شغّالة
        "free" -> Caps(true, true, false, true)                                   // وشبر + مترجم LLM: لهجات وأسلوب
        "off" -> Caps(false, false, false, true)                                          // وشبر + LibreTranslate: فصحى حرفية بس
        else -> Caps(false, false, false, false)                                          // تفريغ بس: مفيش ترجمة
    }
    private fun modeName(mk: String = curModeKey()): String = when (mk) {
        "fgem" -> "⭐ FreeLLM · صوت ← جيميناي"; "free" -> "🌗 وشبر + FreeLLM"
        "off" -> "🌗 وشبر + LibreTranslate (فصحى)"; "txg" -> "✍ تفريغ بجيميناي"; else -> "✍ تفريغ بوشبر"
    }
    private var trAiB: TextView? = null
    private var lastCapsSig = ""
    /** بيخفي/بيظهر الأزرار حسب الوضع (🌐 اللغة · 🗣 الأسلوب · 👁 البصري) — بيتنادى مع كل updateTr */
    private fun applyModeCaps() {
        try {
            val c = caps()
            trLgB?.visibility = if (c.dialect) View.VISIBLE else View.GONE
            trAiB?.visibility = if (c.tone) View.VISIBLE else View.GONE
            val sig = "" + c.dialect + c.tone + c.visual
            if (sig != lastCapsSig) { lastCapsSig = sig; try { applyChromeFn() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }
    /** المترجم المختار لوضع «وشبر»: free (لهجات) · google (LibreTranslate فصحى). الافتراضي أحسن واحد جاهز */
    private fun wTr(): String {
        val c = Cfg.str("wtr").trim()
        if (c == "free" && FreeLlm.ready()) return "free"
        if (c == "google") return "google"
        return if (FreeLlm.ready()) "free" else "google"
    }
    private fun wTrLabel(id: String = wTr()): String = when (id) { "free" -> "FreeLLM · لهجات"; else -> "LibreTranslate · فصحى" }
    private fun whisperStart() { when (wTr()) { "free" -> freeStart(); else -> offlineQuick() } }
    /** تبديل مترجم وضع وشبر والمحرك شغّال: بيتطبّق على الباتشات الجاية بنفس لغة الفيديو وموديل وشبر */
    private fun setWhisperTr(kind: String) {
        if (kind == "free" && !FreeLlm.ready()) { say("🆓 FreeLLM محتاج مفتاح جيميناي (أو Groq) — ضيفه من الإعدادات ← 🔑 المفاتيح"); return }
        Cfg.put("wtr", kind)
        Cfg.put("tr_mode_id", when (kind) { "free" -> Strict.FREE_TXT; else -> Strict.SEMI })
        liveOffline(null, null)
        say("🌗 المترجم بقى " + wTrLabel(kind) + " — من الباتش الجاي")
        updateTr()
    }

    /** (v445) خلفية القوايم المنسدلة: زجاج غامق بتدرّج + حد فاتح ورفيع بدل المستطيل المسطّح */
    private fun glassPanelBg(): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2242432.toInt(), 0xF5101017.toInt())).apply {
        cornerRadius = ui.dp(18).toFloat(); setStroke(maxOf(1, ui.dp(1)), 0x33FFFFFF)
    }
    /** (v450) اسم الوضع الحالي مختصر على زرار «ترجم» عشان تعرف الوضع من غير ما تفتح القايمة */
    private fun modeShort(): String = when (curModeKey()) {
        "off" -> "🌗 وشبر"; "free" -> "🆓 وشبر+Free"; "fgem" -> "☁ جيميناي"
        "txg" -> "✍ تفريغ ☁"; "txw" -> "✍ تفريغ وشبر"; else -> "تلقائي"
    }
    private fun curModeKey(): String {
        val id = Strict.id()
        if (id == "g_wav" || id == "g_aac") return "fgem"   // (v444) جيميناي المباشر بقى FreeLLM (نفس الوضع)
        if (id == Strict.SEMI) return "off"
        if (id == Strict.FREE_TXT) return "free"
        if (id == Strict.FREE_GEM) return "fgem"
        if (id == "ai_only") return "txg"
        if (id == "w_only") return "txw"
        val sp = TrWizard.savedPlan()
        if (sp != null) return if (sp.offline) (if (sp.only) "txw" else "off") else (if (sp.only) "txg" else "fgem")
        return if (Cfg.bool("stt_offline", false)) "off" else "fgem"
    }

    /** يطبّق الإعدادات الحالية على المحرك الشغّال من غير ما يبدأ ولا يعيد حاجة */
    private fun liveConf() {
        try { val nc = Cfg.snapshot(); conf = nc; if (::engine.isInitialized) engine.applyConf(nc) } catch (e: Throwable) { LogStore.err("Player:liveConf", e) }
    }

    /** لغة الفيديو / موديل وشبر بتتغيّر والمحرك شغّال: بتتحفظ وبتتطبّق على الباتشات الجاية (واللغة بتصلّح اللي اتفرّغ بلغة غلط) */
    private fun liveOffline(newLang: String?, newModel: String?) {
        val oldLang = Offline.lang()
        val lang = newLang ?: oldLang
        val mdl = newModel ?: Offline.model().id
        val only = curModeKey() == "txw"
        val freeMode = curModeKey() == "free"   // (v440) وضع وشبر + FreeLLM: نفس الكلام
        val pl = TrWizard.Plan("offline", "", lang, mdl, "google", "whisper", if (only) "" else if (freeMode) TrWizard.dialectWanted().ifBlank { "فصحى" } else "فصحى", false, free = if (only) "" else if (freeMode) "free" else "", only = only)
        try { TrWizard.save(pl) } catch (e: Throwable) { LogStore.err("Player:liveOffSave", e) }
        try { runPlanCore(pl, false) } catch (e: Throwable) { LogStore.err("Player:liveOff", e) }
        if (lang != oldLang) {
            try {
                if (::engine.isInitialized && (engineStarted || engine.subs.isNotEmpty())) {
                    engine.langChanged(lang, player.currentPosition.coerceAtLeast(0) / 1000.0)
                    say("🌐 لغة الفيديو بقت " + TrWizard.langLabel(lang) + " — بكمّل من مكانك وبعيد الباتشات اللي اتفرّغت بلغة غلط")
                } else say("🌐 لغة الفيديو بقت " + TrWizard.langLabel(lang))
            } catch (e: Throwable) { LogStore.err("Player:langChanged", e) }
        }
        try { applyChromeFn() } catch (_: Throwable) {}
    }

    private fun pickWhisperModel(m: Offline.Model) {
        if (!WhisperLib.loaded) { say("📴 الأوفلاين مش مدعوم على جهازك"); return }
        if (Offline.downloaded(m)) { liveOffline(null, m.id); say("🎧 موديل وشبر: " + m.label + " — بيتطبّق على الباتشات الجاية"); return }
        if (Offline.downloading()) { say("⬇ فيه تنزيل موديل شغّال — استنى يخلص وادوس تاني"); return }
        android.app.AlertDialog.Builder(this).setTitle("⬇ تنزيل موديل " + m.label)
            .setMessage("الموديل ده مش نازل (~" + m.approxMb + " ميجا، مرة واحدة، محتاج نت). تنزّله وتطبّقه؟")
            .setPositiveButton("نزّل وطبّق") { _, _ ->
                say("⬇ بنحمّل موديل " + m.label + "…")
                Offline.download(m, { a, t -> runOnUiThread { Notice.show(this, "⬇ موديل التفريغ: " + (a / 1048576) + " / " + (t / 1048576) + " ميجا", 900L) } }) { e ->
                    runOnUiThread { if (e != null) say("⚠ تنزيل الموديل فشل: $e") else { liveOffline(null, m.id); say("✅ موديل " + m.label + " نزل واتطبّق") } }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun libreDialog() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(40, 20, 40, 0)
        val eu = android.widget.EditText(this); eu.hint = "https://libretranslate.com"; eu.setText(Cfg.str("libre_url")); eu.setSingleLine(true)
        val ek = android.widget.EditText(this); ek.hint = "مفتاح API (لو محتاج)"; ek.setText(Cfg.str("libre_key")); ek.setSingleLine(true)
        box.addView(eu); box.addView(ek)
        android.app.AlertDialog.Builder(this).setTitle("🌐 LibreTranslate").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                Cfg.put("libre_url", eu.text.toString().trim().trimEnd('/')); Cfg.put("libre_key", ek.text.toString().trim())
                ExtTranslate.libreProv()?.let { ExtTranslate.clearBlock(it.id) }
                say("🌐 سيرفر LibreTranslate اتحفظ — بيتطبّق على الباتشات الجاية")
            }
            .setNegativeButton("إلغاء", null).show()
    }

    /** زرار ⚙ جنب «ترجم»: إعدادات الوضع الحالي (+ (v428) زرار 🌐 اللغة واللهجة والأسلوب جواه). كل تغيير بيتطبّق فورًا من غير إعادة */
    fun modeSettings(anchor: View) {
        setPop?.let { if (it.isShowing) { it.dismiss(); setPop = null; return } }
        val mk = curModeKey()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = glassPanelBg()
        }
        fun flow() = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun cLp() = ViewGroup.MarginLayoutParams(-2, ui.dp(34)).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) }
        fun lbl(t: String): View = LinearLayout(this).apply {   // (v440) عنوان قسم بخط فاصل فوقه عشان الإعدادات تتقسّم بوضوح
            orientation = LinearLayout.VERTICAL
            if (col.childCount > 2) addView(View(this@PlayerActivity).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(ui.dp(6), ui.dp(8), ui.dp(6), 0) })
            addView(ui.text(t, 11.5f, 0xFF9AA0A6.toInt(), true).apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(2)) })
        }
        fun item(t: String, f: () -> Unit) = ui.fsBtn(t) { _ -> setPop?.dismiss(); f() }.apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(42)).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) } }
        val head = when (mk) { "off" -> "🌗 إعدادات وشبر + LibreTranslate"; "free" -> "🆓 إعدادات وشبر + FreeLLM"; "fgem" -> "⭐ إعدادات FreeLLM · صوت ← جيميناي"; "txg" -> "☁ إعدادات تفريغ جيميناي"; else -> "📴 إعدادات تفريغ وشبر" }
        col.addView(ui.text(head, 13f, Color.WHITE, true).apply { setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(2)) })
        col.addView(ui.text("بتتطبّق فورًا وانت شغّال", 11f, 0xFF9AA0A6.toInt()).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4)) })
        // (v428) 🌐 اللغة واللهجة والأسلوب جوه الإعدادات (في كل الأوضاع) — بيفتح نفس قايمة 🌐 بتاعة الشريط
        if (caps(mk).dialect) col.addView(item("🌐 اللهجة والأسلوب واللغة: " + Cfg.str("conv_dialect", "").ifBlank { "فصحى" }) { langPopup(anchor) })
        else col.addView(ui.text(if (caps(mk).translates) "🌐 الوضع ده بيترجم فصحى حرفية بس — للهجات اختار مترجم FreeLLM تحت" else "مفيش ترجمة في الوضع ده (تفريغ بس)", 11f, 0xFF9AA0A6.toInt()).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4)) })

        fun audioSection() {
            col.addView(lbl("🎧 جودة الصوت المرفوع لجيميناي"))
            val wavNow = AudioEnc.preset() == AudioEnc.NORMAL
            val r = flow()
            r.addView(ui.fsBtn((if (wavNow) "✓ " else "") + "🧠 عادي (WAV)") { _ ->
                setPop?.dismiss(); Cfg.put(AudioEnc.PRESET_KEY, AudioEnc.NORMAL); if (Strict.id() == "g_aac") Cfg.put("tr_mode_id", "g_wav")
                say("🎧 جودة الصوت: WAV — الباتشات الجاية هتترفع بيها")
            }, cLp())
            r.addView(ui.fsBtn((if (!wavNow) "✓ " else "") + "🧠 توفير داتا (AAC)") { _ ->
                setPop?.dismiss(); Cfg.put(AudioEnc.PRESET_KEY, AudioEnc.FAST); if (Strict.id() == "g_wav") Cfg.put("tr_mode_id", "g_aac")
                say("🎧 جودة الصوت: AAC (داتا أقل) — الباتشات الجاية هتترفع بيها")
            }, cLp())
            col.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        fun keysSection() {
            val n = try { Cfg.keys("keys").size + Cfg.keys("extra").size + Cfg.keys("backup").size } catch (_: Throwable) { 0 }
            col.addView(lbl("🔑 مفاتيح جيميناي: $n مفتاح" + (if (mk == "fgem") " · كل مفتاح بيلف على كل الموديلات · ٧٥٪ بتنسخ و٢٥٪ بتترجم بالسياق" else "")))
            col.addView(item("➕ ضيف مفتاح جيميناي") {
                showKeyGuide { k ->
                    val cur = Cfg.keys("keys")
                    if (k in cur || k in Cfg.keys("extra") || k in Cfg.keys("backup")) Notice.show(this, "⚠ المفتاح ده موجود قبل كده", 2600L)
                    else { Cfg.p.edit().putString("keys", (cur + k).joinToString("\n")).apply(); Notice.show(this, "✓ اتحفظ المفتاح وبقى شغّال", 2200L); liveConf() }
                }
            })
        }
        fun whisperSection() {
            col.addView(lbl("🌍 لغة كلام الفيديو (للتفريغ)"))
            val curL = Offline.lang()
            val rl = flow()
            for (i in Offline.langIds.indices) {
                val id = Offline.langIds[i]
                rl.addView(ui.fsBtn((if (id == curL) "✓ " else "") + Offline.langLabels[i]) { _ -> setPop?.dismiss(); if (id != curL) liveOffline(id, null) }, cLp())
            }
            col.addView(rl, LinearLayout.LayoutParams(-1, -2))
            col.addView(lbl("🎧 موديل وشبر (الأكبر أدق وأبطأ · ⬇ = مش نازل)"))
            val curM = Offline.model().id
            val rm = flow()
            for (m in Offline.models) rm.addView(ui.fsBtn((if (m.id == curM) "✓ " else "") + m.label + (if (Offline.downloaded(m)) "" else " ⬇")) { _ -> setPop?.dismiss(); if (m.id != curM) pickWhisperModel(m) }, cLp())
            col.addView(rm, LinearLayout.LayoutParams(-1, -2))
        }

        fun freeSection() {
            col.addView(lbl("🆓 FreeLLM · الشغّال دلوقتي: " + FreeLlm.liveName()))
            col.addView(ui.text(if (curModeKey() == "fgem" && FreeLlm.serverUrl().isEmpty()) FreeLlm.rotationText() else FreeLlm.statusText(), 10.5f, 0xFF9AA0A6.toInt()).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4)) })
            col.addView(item("🆓 إعدادات FreeLLM (مفاتيح · سيرفر · تجربة)") { freeDialog() })
        }

        fun termSection() {
            val tnOn = TermNotes.enabled()
            col.addView(item("📖 نبذة المصطلحات الطبية: " + (if (tnOn) "✅ شغّال" else "⏸ مقفول")) {
                Cfg.put("term_notes", if (tnOn) "off" else "on"); say("📖 نبذة المصطلحات: " + (if (tnOn) "اتقفلت" else "اتفتحت"))
            })
        }
        fun translatorSection() {
            col.addView(lbl("🔤 المترجم (بعد تفريغ وشبر)"))
            val r = flow()
            for ((id, lb) in listOf("free" to "🆓 FreeLLM · لهجات", "google" to "🌐 LibreTranslate · فصحى")) {
                val on = (mk == "free" && id == "free") || (mk == "off" && id == "google")
                r.addView(ui.fsBtn((if (on) "✓ " else "") + lb) { _ -> setPop?.dismiss(); if (!on) setWhisperTr(id) }, cLp())
            }
            col.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        // (v444) كل وضع بيعرض اللي بيشتغل فيه بس
        when (mk) {
            "fgem" -> {
                audioSection(); keysSection(); freeSection()
                val swapOn = Cfg.str("tr_autoswap", "on") != "off"
                col.addView(item("🔄 بديل تلقائي لو جيميناي وقف: " + (if (swapOn) "✅ شغّال" else "⏸ مقفول")) {
                    Cfg.put("tr_autoswap", if (swapOn) "off" else "on"); say("🔄 البديل التلقائي: " + (if (swapOn) "اتقفل" else "اتفتح"))
                })
            }
            "free" -> { translatorSection(); whisperSection(); freeSection(); termSection() }
            "off" -> {
                translatorSection(); whisperSection()
                col.addView(item("🌐 سيرفر LibreTranslate: " + Cfg.str("libre_url").ifBlank { "الافتراضي" }) { libreDialog() })
                termSection()
            }
            "txg" -> { audioSection(); keysSection() }
            else -> whisperSection()
        }

        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val popW = minOf(resources.displayMetrics.widthPixels - ui.dp(24), ui.dp(340))
        col.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        val up = loc[1] > screenH / 2
        val avail = (if (up) loc[1] - ui.dp(12) else screenH - loc[1] - anchor.height - ui.dp(12)).coerceAtLeast(ui.dp(120))
        val popH = minOf(col.measuredHeight, avail)
        val pw = TvFocus.pop(this@PlayerActivity, scroll, popW, popH, true)
        Motion.popIn(scroll, up)   // (v445)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (setPop === pw) setPop = null; showChrome() }
        try { if (up) pw.showAsDropDown(anchor, 0, -(anchor.height + popH + ui.dp(2))) else pw.showAsDropDown(anchor, 0, ui.dp(2)) } catch (e: Throwable) { LogStore.err("Player:modeSettings", e) }
        setPop = pw
        h.removeCallbacks(hideChrome); showChrome(); h.removeCallbacks(hideChrome)
    }

    private var redoPop: android.widget.PopupWindow? = null
    private var redoKeep = true
    /** زرار «🔁 إعادة»: قايمة منسدلة صغيرة تحت/فوق الزرار (زي قايمة اللغة): من الأول / من الباتش ده وبعده / الباتش ده بس / باتش معين — مع اختيار نحتفظ بالقديمة (🗂) ولا نمسحها */
    fun redoDialog(anchor: View) {
        redoPop?.let { if (it.isShowing) { it.dismiss(); redoPop = null; return } }
        if (!::engine.isInitialized) return
        val curC = try { engine.chunkOfSec(player.currentPosition / 1000.0) } catch (_: Exception) { 0 }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = glassPanelBg()
        }
        fun item(t: String, f: (TextView) -> Unit) = ui.fsBtn(t, f).apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) } }
        fun keepText() = if (redoKeep) "💾 احتفظ بالقديمة في 🗂: شغّال" else "🗑 امسح القديمة: شغّال"
        col.addView(item(keepText()) { v -> redoKeep = !redoKeep; v.text = keepText() })
        col.addView(item("🔁 من الأول خالص") { _ -> redoPop?.dismiss(); val k = redoKeep; player.seekTo(0); redoGo("🔄 بترجم من الأول…") { engine.redoAll(k) } })
        col.addView(item("▶ من الباتش ده وبعده (باتش ${curC + 1})") { _ -> redoPop?.dismiss(); val k = redoKeep; redoGo("🔄 بترجم من باتش ${curC + 1} وبعده…") { engine.redoFrom(curC, k) } })
        col.addView(item("☝ الباتش ده بس (باتش ${curC + 1})") { _ -> redoPop?.dismiss(); val k = redoKeep; redoGo("🔄 بترجم باتش ${curC + 1} لوحده…") { engine.redoOnly(curC, k) } })
        col.addView(item("📋 اختار باتش معين…") { _ -> redoPop?.dismiss(); batchDialog() })
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val popW = minOf(resources.displayMetrics.widthPixels - ui.dp(24), ui.dp(280))
        col.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        val up = loc[1] > screenH / 2
        val avail = (if (up) loc[1] - ui.dp(12) else screenH - loc[1] - anchor.height - ui.dp(12)).coerceAtLeast(ui.dp(120))
        val popH = minOf(col.measuredHeight, avail)
        val pw = TvFocus.pop(this@PlayerActivity, scroll, popW, popH, true)
        Motion.popIn(scroll, up)   // (v445)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (redoPop === pw) redoPop = null; showChrome() }
        if (up) pw.showAsDropDown(anchor, 0, -(anchor.height + popH + ui.dp(2))) else pw.showAsDropDown(anchor, 0, ui.dp(2))
        redoPop = pw
        h.removeCallbacks(hideChrome); showChrome(); h.removeCallbacks(hideChrome)
    }

    /** يوقف المشغّل خالص، يسلّم الترجمة لخدمة الخلفية (إشعار بالتقدم والوقت المتبقي) ويطلع بره التطبيق من غير ما يقفله */
    fun translateInBackground(goHome: Boolean = true, src: String = "المشغّل (زرار 🌙)") {
        if (Cfg.allMainKeys().isEmpty() && conf.keys.isEmpty() && conf.backup.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        if (incognito) { say("🕶 الترجمة بالخلفية مش متاحة في التخفي"); return }
        if (handedOff || remote) return   // (v568) وضع المتحكم: الترجمة على التلفزيون، مفيش تسليم للخلفية على الموبايل
        handedOff = true
        closeSide(); resumePending = false
        say(if (src.contains("تلقائي")) "🌙 كمّلت الترجمة في الخلفية لأن ✔ «كمّل في الخلفية لما أخرج» مفعّل (من فوق في المشغّل) — تقفله من نفس الزرار" else "🌙 هكمّل الترجمة في الخلفية — التقدم في الإشعارات")
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:1017", e) }
        saveRecentForce()
        val vidNow = vid; val uriS = uri?.toString(); val urlS = aurl ?: url; val hdrC = HashMap(hdr)
        val app = applicationContext
        val eng = engine
        Thread {
            try { eng.stop(); eng.awaitStopped(4000); eng.saveNow() } catch (e: Exception) { LogStore.err("Main:1023", e) }
            BgJobs.enqueue(app, BgJob(vidNow, Recents.titleOf(vidNow), uriS, urlS, hdrC), src)
        }.start()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 12) } catch (e: Exception) { LogStore.err("Main:1027", e) }
        }
        if (goHome) try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { LogStore.err("Main:1029", e) }
        try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}
        if (!goHome && !pipNow()) returnToOrigin()   // (v339)
        if (pipNow()) finishAndRemoveTask() else finish()   // (v149) من نافذة الـ PiP: يشيل النافذة خالص
    }

    /** (v149) وضع «صوت بس» (من زرار الـ PiP): يشيل نافذة الـ PiP ويسيب الفيديو شغّال كصوت في الخلفية، ويوقف الترجمة */
    private var audioOnlyMode = false
    fun audioOnly() {
        audioOnlyMode = true
        try { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}   // (v468) وضع الصوت بس: الشاشة تنام عادي — الفلاج يرجع في onResume
        try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}
        try { if (!incognito) saveRecentForce() } catch (e: Exception) { LogStore.err("Player:audioOnly1", e) }
        val eng = engine
        try { retireEngine() } catch (e: Throwable) { LogStore.err("Player:audioOnly2", e) }   // وقف الترجمة (لو شغّالة)
        Thread { try { eng.awaitStopped(3000); eng.saveNow() } catch (e: Throwable) { LogStore.err("Player:audioOnly3", e) } }.start()
        try { if (!player.isPlaying) player.play() } catch (e: Exception) { LogStore.err("Player:audioOnly4", e) }
        try { moveTaskToBack(true) } catch (e: Exception) { LogStore.err("Player:audioOnly5", e) }
    }

    /** (v149) إيقاف نهائي (من زرار الـ PiP): يحفظ التقدم، يوقف الفيديو والمحرك وكل الترجمة في الخلفية والإشعارات، ويطلّع التطبيق من الرام */
    fun fullStop() {
        if (remote) { rmFrames = false; try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}; finishAndRemoveTask(); return }   // (v568)
        try { player.pause() } catch (e: Exception) { LogStore.err("Player:fullStop1", e) }
        try { if (!handedOff) saveRecentForce() } catch (e: Exception) { LogStore.err("Player:fullStop2", e) }
        try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}
        handedOff = true   // عشان onDestroy ما يحفظش تاني ولا يشغّل ترجمة خلفية
        val eng = engine; val app = applicationContext
        Thread {
            try { eng.stop(); eng.awaitStopped(3000); eng.saveNow() } catch (e: Throwable) { LogStore.err("Player:fullStop3", e) }
            try { BgJobs.jobs.filter { it.active }.forEach { BgJobs.stopAndWait(it.vid, 3000L) } } catch (e: Throwable) { LogStore.err("Player:fullStop4", e) }
            try { app.stopService(Intent(app, BgService::class.java)); app.stopService(Intent(app, KeepAliveService::class.java)) } catch (_: Throwable) {}
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
        finishAndRemoveTask()
    }

    // ===== حفظ SRT تلقائي جنب الفيديو لما الترجمة تخلص (أو لما تعدّل التزامن بعدها) =====
    var offFsB: TextView? = null
    private var lastSrtN = -1
    private var lastSrtOff = Long.MIN_VALUE
    private var srtToastShown = false   // (v104) إشعار «اتحفظ SRT» مرة واحدة بس في الجلسة
    private val srtRun = Runnable { srtCheck() }
    fun srtSoon() { h.removeCallbacks(srtRun); h.postDelayed(srtRun, 2500) }
    private fun srtCheck() {
        val u = uri?.toString() ?: return
        if (durMs <= 0 || engine.subs.isEmpty() /* (v229) حفظ SRT أوتوماتيك دايمًا */) return
        if (PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0) < 97) return
        val n = engine.subs.size
        if (n == lastSrtN && offsetMs == lastSrtOff) return
        lastSrtN = n; lastSrtOff = offsetMs
        val subsNow = engine.subs; val off = offsetMs; val app = applicationContext
        Thread { SrtWriter.save(app, u, subsNow, off)?.let { p -> runOnUiThread { log("💾 اتحفظ SRT: $p"); if (!srtToastShown) { srtToastShown = true; say("💾 اتحفظ SRT جنب الفيديو") } } } }.start()
    }

    // ===== (v167) السلايدر الموحّد للصوت: 0..100% صوت عادي، 100..200% تضخيم (أحمر) — LoudnessEnhancer على جلسة صوت المشغّل (الجزء الزيادة 0..100 = لحد +15dB) =====
    /** (v270) 0..100 = صوت النظام (زراري الفوليوم)، 100..200 = تضخيم والنظام على الآخر */
    fun volTotal(): Int {
        val b = (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0).coerceIn(0, 100)
        // (v604) على الأجهزة اللي صوتها ثابت، صوت النظام بيرجع دايمًا 100 — فالقيمة الحقيقية هي صوت المشغّل نفسه
        val fixed = !remote && try { (getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).isVolumeFixed } catch (_: Throwable) { false }
        val s = if (fixed) Math.round(player.volume * 100f).coerceIn(0, 100) else SysVol.get(this)
        return if (b > 0 && s >= 100) 100 + b else s
    }
    private var volWatch: SysVol.Watch? = null
    fun setBoostPct(b: Int) { Cfg.put("vol_boost", b.coerceIn(0, 100).toString()); applyBoost() }
    fun boostDbText(b: Int) = "+" + "%.1f".format(b * 0.15) + " dB"
    private var loud: android.media.audiofx.LoudnessEnhancer? = null
    fun applyBoost() {
        val pct = (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0).coerceIn(0, 100)
        try {
            if (!::player.isInitialized) return
            val sid = player.exo()?.audioSessionId ?: return
            if (sid == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET || sid == 0) return
            if (loud == null) loud = android.media.audiofx.LoudnessEnhancer(sid)
            loud?.setTargetGain(pct * 15)
            loud?.enabled = pct > 0
        } catch (e: Throwable) { LogStore.err("boost", e) }
    }

    // ===== PiP =====
    private var inPip = false
    private var resumedNow = false
    private var pipOv: PipSubBar? = null
    private val pipExitCheck = Runnable { if (!resumedNow && !isFinishing && !audioOnlyMode) closeAfterPip() }
    fun pipNow() = inPip || (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
    private fun closeAfterPip() {
        if (remote) { pipOv?.hide(); pipOv = null; finishAndRemoveTask(); return }   // (v568) مايوقفش التلفزيون
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:1057", e) }
        pipOv?.hide(); pipOv = null
        finishAndRemoveTask()
    }
    // القايمة الجانبية (الأفقي)
    lateinit var sideMenuV: FrameLayout
    lateinit var sidePanel: LinearLayout
    var sideOpen = false
    fun closeSide() { sideOpen = false; if (::sideMenuV.isInitialized) sideMenuV.visibility = View.GONE }

    // ===== Host =====
    override fun log(s: String) {
        status = s
        if (BrainBus.active()) BrainBus.post("log", s)
        synchronized(logBuf) {
            logBuf.append(fmtMs(cur)).append("  ").append(s).append('\n')
            if (logBuf.length > 30000) logBuf.delete(0, 10000)
        }
        LogStore.add(fmtMs(cur) + "  " + s)
    }
    private var lastBeat = 0L

    // ===== (v452) مؤقت النوم للفيديو: بعد 5-60 دقيقة أو لما الفيديو يخلص — الصوت بينزل تدريجيًا في آخر 4 ثواني وبعدها يقف =====
    private var sleepAtMs = 0L
    private var sleepEndOfVideo = false
    private val sleepTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || sleepAtMs <= 0L) return
            val left = sleepAtMs - System.currentTimeMillis()
            if (left <= 0L) { sleepFire(); return }
            if (left <= 10000L) { try { player.volume = (left / 10000f).coerceIn(0f, 1f) } catch (_: Throwable) {} }   // (v469) الخفوت التدريجي بقى في آخر 10 ثوانٍ (كان 4)
            h.postDelayed(this, if (left <= 10000L) 200L else 1000L)
        }
    }
    private fun sleepFire() {
        sleepAtMs = 0L; sleepEndOfVideo = false
        try { player.pause() } catch (_: Throwable) {}
        try { player.volume = 1f } catch (_: Throwable) {}
        say("😴 مؤقت النوم: الفيديو وقف")
    }
    /** min > 0 = بعد كذا دقيقة · -1 = لما الفيديو يخلص · 0 = إلغاء */
    private fun setSleepTimer(min: Int) {
        h.removeCallbacks(sleepTick)
        sleepAtMs = 0L; sleepEndOfVideo = false
        try { player.volume = 1f } catch (_: Throwable) {}
        if (min > 0) { sleepAtMs = System.currentTimeMillis() + min * 60_000L; h.postDelayed(sleepTick, 1000L) }
        else if (min < 0) sleepEndOfVideo = true
    }
    private fun sleepDialog() {
        var dlg: android.app.Dialog? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        fun add(label: String, on: Boolean, f: () -> Unit) {
            col.addView(ui.text(label, 15f, th.text, on).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12))
                background = ui.box(if (on) 0x445B9DFF else 0x14808080, if (on) 0xFF5B9DFF.toInt() else th.border, 12)
                setOnClickListener { f(); dlg?.dismiss() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        val leftMin = if (sleepAtMs > 0L) ((sleepAtMs - System.currentTimeMillis() + 59_999L) / 60_000L).toInt().coerceAtLeast(1) else 0
        if (sleepAtMs > 0L || sleepEndOfVideo) add("🚫 إلغاء المؤقت" + (if (leftMin > 0) " (فاضل $leftMin د)" else ""), false) { setSleepTimer(0); say("المؤقت اتلغى") }
        for (m in intArrayOf(5, 10, 15, 30, 45, 60)) add("⏱ بعد $m دقيقة", false) { setSleepTimer(m); say("😴 الفيديو هيقف بعد $m دقيقة (والصوت بينزل بهدوء)") }
        add("🎬 لما الفيديو ده يخلص", sleepEndOfVideo) { setSleepTimer(-1); say("😴 الفيديو هيقف في آخره (من غير ما يكمّل اللي بعده)") }
        dlg = ui.sheet(this, "😴 مؤقت النوم", listOf<View>(col)); dlg?.show()
    }
    // (v125) حفظ مكان الوقوف كل 3 ثواني (كان بيتحفظ عند الخروج بس → بعد PiP / تحديث النسخة بيرجع لمكان قديم)
    private var lastPosSave = 0L
    private var lastRecentSave = 0L
    private var lastLogLen = -1
    private var lastBatchTxt = ""
    private var lastCountersTxt = ""   // (v458) كاش نص العدّادات
    /** (v146) نص الباتشات (مفتاح · موديل · زمن الرد) والأسرع في كل دفعة أخضر */
    private fun batchStyled(): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        for ((t, fast) in engine.batchRows()) {
            if (sb.isNotEmpty()) sb.append('\n')
            val a = sb.length; sb.append(t)
            if (fast) sb.setSpan(android.text.style.ForegroundColorSpan(0xFF2ECC71.toInt()), a, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
    private var miniLog: View? = null
    private var miniLogTv: TextView? = null
    fun closeMiniLog() {
        miniLog?.let { p -> try { (p.parent as? ViewGroup)?.removeView(p) } catch (_: Exception) {} }
        miniLog = null; miniLogTv = null
    }
    /** (v125) اللوج الصغير: بيوضّح أنهي باتش بيترجم دلوقتي (دوس على عداد الجمل) — بيتحدّث لوحده ويتسكرول */
    fun toggleMiniLog() {
        if (miniLog != null) { closeMiniLog(); return }
        val tv = IconTextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(8)); layoutDirection = View.LAYOUT_DIRECTION_RTL
            text = batchStyled().ifBlank { "مفيش باتشات لسه — دوس ▶ ترجمة" }
        }
        miniLogTv = tv
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; isClickable = true
            background = ui.box(0xEE101418.toInt(), 0x33FFFFFF, 12)
        }
        val head = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(2)) }
        head.addView(ui.text("📋 الباتشات — بيترجم دلوقتي", 12f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconTextView(this).apply { text = "✕"; textSize = 16f; setTextColor(Color.WHITE); setPadding(ui.dp(10), ui.dp(2), ui.dp(8), ui.dp(2)); setOnClickListener { closeMiniLog() } })
        col.addView(head)
        col.addView(MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.42f).toInt()).apply { addView(tv, FrameLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2))
        val w = minOf(ui.dp(270), resources.displayMetrics.widthPixels - ui.dp(28))
        videoBoxRef.addView(col, FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.RIGHT).apply { setMargins(0, ui.dp(48), ui.dp(66), 0) })   // (v265) تحت الكبسولات يمين   // (v249) تحت الكبسولات شمال
        miniLog = col
    }
    private var logMode = 0
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); LogStore.add("⚠ onTrimMemory level=$level · ${LogStore.heapLine()}") }
    override fun onLowMemory() { super.onLowMemory(); LogStore.add("⚠ onLowMemory · ${LogStore.heapLine()}") }
    override fun status(s: String) { status = s; if (BrainBus.active()) BrainBus.post("status", s) }
    override fun changed() { dirty = true; srtSoon() }
    override fun notice(s: String) { if (BrainBus.active()) BrainBus.post("notice", s); runOnUiThread { try { Notice.show(this, s, 2600L) } catch (e: Throwable) { LogStore.err("Main:1089", e) } } }
    override fun position() = cur / 1000.0
    override fun playerDuration() = durMs / 1000.0

    // ===== نسخ كل الجمل بتوقيتات الظهور والاختفاء (وتحديد اللي مش بتظهر في المشغّل) =====
    private fun fmtT(ms: Long): String { val t = ms.coerceAtLeast(0L); return String.format("%d:%02d:%02d.%03d", t / 3600000, t / 60000 % 60, t / 1000 % 60, t % 1000) }
    /** الجمل اللي هتتحجب ومش هتظهر في المشغّل: السبب لكل جملة (بنفس منطق العرض: الأحدث بس لو الجمل متداخلة) */
    private fun hiddenReasons(): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (i in list.indices) {
            val q = list[i]
            if (q.translated.isBlank()) { out[i] = "النص المترجم فاضي"; continue }
            if (ends[i] <= starts[i]) { out[i] = "مدتها صفر أو سالبة (الاختفاء قبل أو مع الظهور)"; continue }
            val snd = q.isSound
            val map = if (snd) sdMap else spMap; val st = if (snd) sdStarts else spStarts; val en = if (snd) sdEnds else spEnds
            val maxN = if (snd) 2 else 1
            var seen = false; var t = starts[i]
            while (t <= ends[i]) {
                if (PlayerLogic.activeIndices(st, en, t, 0L, 400L, maxN).any { map[it] == i }) { seen = true; break }
                if (t == ends[i]) break
                t = minOf(t + 100L, ends[i])
            }
            if (!seen) out[i] = "متغطّية بجملة تانية متداخلة معاها زمنيًا (المشغّل بيعرض الأحدث بس)"
        }
        return out
    }
    fun sentencesReport(): String {
        val hidden = hiddenReasons()
        val sb = StringBuilder()
        sb.append("📝 الجمل — العدد: ${list.size}\n")
        sb.append("الأوقات بساعة المشغّل (ساعة:دقيقة:ثانية.ملّي)")
        if (offsetMs != 0L) sb.append(" — شاملة تزامن ${String.format("%+.2f", offsetMs / 1000.0)}ث")
        sb.append("\n")
        val short = list.indices.filter { ends[it] > starts[it] && ends[it] - starts[it] < 500L && it !in hidden }
        sb.append("⚠ جمل مش بتظهر في المشغّل: ${hidden.size}")
        if (hidden.isNotEmpty()) sb.append(" ← أرقامها: " + hidden.keys.sorted().joinToString("، ") { (it + 1).toString() })
        sb.append("\n⏱ جمل بتظهر أقل من نص ثانية: ${short.size}")
        if (short.isNotEmpty()) sb.append(" ← أرقامها: " + short.joinToString("، ") { (it + 1).toString() })
        sb.append("\n\n")
        for (i in list.indices) {
            val q = list[i]
            val a = starts[i] + offsetMs; val b = ends[i] + offsetMs
            sb.append("#${i + 1}  ظهور ${fmtT(a)}  ←  اختفاء ${fmtT(b)}  (مدة ${String.format("%.2f", (b - a) / 1000.0)}ث)")
            if (q.isSound) sb.append("  🔊 صوت/خلفية (بتظهر فوق الفيديو)")
            sb.append("\n")
            sb.append("ترجمة: ").append(q.translated.replace("\n", " / ")).append("\n")
            if (q.original.isNotBlank() && q.original != q.translated) sb.append("أصل: ").append(q.original.replace("\n", " / ")).append("\n")
            hidden[i]?.let { sb.append("⚠ مش بتظهر في المشغّل: ").append(it).append("\n") }
            sb.append("\n")
        }
        return sb.toString().trimEnd() + "\n"
    }
    private var pendingSentTxt: String? = null
    fun copySentences() {
        if (list.isEmpty()) { Notice.show(this, ("مفيش جمل لسه").toString(), 2300L); return }
        val txt = try { sentencesReport() } catch (e: Throwable) { Notice.show(this, ("تعذّر تجهيز الجمل").toString(), 2300L); return }
        // الكليبورد بيتعدّى بالـ Binder (حد ~1MB): لو النص كبير جدًا بنحفظه كملف نصي بدل ما يفشل
        if (txt.length > 250_000) {
            pendingSentTxt = txt
            try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "sentences.txt") }, 31) } catch (_: Exception) { pendingSentTxt = null }
            Notice.show(this, ("الجمل كتير — اختار مكان حفظ ملف نصي").toString(), 3600L)
            return
        }
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("sentences", txt))
            Notice.show(this, ("📋 اتنسخت ${list.size} جملة بتوقيتاتها").toString(), 2300L)
        } catch (_: Throwable) {
            pendingSentTxt = txt
            try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "sentences.txt") }, 31) } catch (_: Exception) { pendingSentTxt = null }
        }
    }

    private fun refreshList() {
        val l = engine.subs.sortedBy { it.start }
        list = l; starts = LongArray(l.size) { (l[it].start * 1000).toLong() }; ends = LongArray(l.size) { (l[it].end * 1000).toLong() }
        // متحدثين مختلفين ورا بعض (فاصل ≤ 0.35ث): الأول بيفضل ظاهر لحد ما التاني يخلص، فيبانوا في سطرين (- ... / - ...) بدل ما واحد يختفي ويتبدّل
        for (j in 0 until l.size - 1) {
            val a = l[j]; val b = l[j + 1]
            val gap = starts[j + 1] - ends[j]
            val differ = (a.speakerTag.isNotBlank() && b.speakerTag.isNotBlank() && a.speakerTag != b.speakerTag) || a.gender != b.gender
            if (gap in 0..350 && differ && !b.isContinuation && !a.isSong && !b.isSong && !a.isSound && !b.isSound && !a.translated.startsWith("«") && !b.translated.startsWith("«") &&
                ends[j] - starts[j] <= 4000 && ends[j + 1] - starts[j + 1] in 600..5000) ends[j] = ends[j + 1]
        }
        val sp = l.indices.filter { !l[it].isSound }; val sd = l.indices.filter { l[it].isSound && (Extras.deaf || !l[it].translated.trim().let { t -> t.startsWith("[") && t.endsWith("]") }) }   // (v105) أوصاف الأصوات القديمة [موسيقى] متظهرش
        spMap = sp.toIntArray(); spStarts = LongArray(sp.size) { starts[sp[it]] }; spEnds = LongArray(sp.size) { ends[sp[it]] }
        sdMap = sd.toIntArray(); sdStarts = LongArray(sd.size) { starts[sd[it]] }; sdEnds = LongArray(sd.size) { ends[sd[it]] }
        if (::sentDlg.isInitialized && sentDlg.isShowing) { updVis(); adapter.notifyDataSetChanged() }   // القايمة مش ظاهرة = مفيش داعي نرسمها كل ثانية
    }

    /** (v121) أي فيديو جديد يفتح = أي مشغّل قديم لسه حي (PiP / خلفية / تحت في الستاك) يتوقف ويتقفل خالص: صوت + ترجمة */
    fun killForNewVideo() {
        if (remote) { rmFrames = false; if (!isFinishing) finish(); return }   // (v546) مايوقفش التلفزيون
        try { visual.stop() } catch (_: Throwable) {}
        try { player.pause() } catch (_: Throwable) {}
        try { saveRecent() } catch (_: Throwable) {}
        try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}
        val eng0 = engine
        retireEngine()
        Thread { try { eng0.saveNow() } catch (e: Throwable) { LogStore.err("Player:newVideoSave", e) } }.start()   // (v287)
        if (!isFinishing) finish()
    }

    // ================= (v469) ميزات المشغّل (فريق B): سرعة كاملة · A-B · استوديو · فصول · التالي تلقائي · لقطة · شريط تزامن =================
    private var speedBRef: TextView? = null
    private var abA = -1L; private var abB = -1L
    private var sentFollow = true
    private var sentSearchFrom = 0
    private data class Chap(val ms: Long, val title: String)
    private var chapters: List<Chap> = emptyList()
    private var chapTicksRef: View? = null
    private var chapCur = -2
    private var nextUpCard: LinearLayout? = null
    private var nextUpTitleV: TextView? = null
    private var nextUpCountV: TextView? = null
    private var nextUpAt = 0L
    private var nextUpGo: (() -> Unit)? = null
    private var syncStripV: LinearLayout? = null
    private var syncValV: TextView? = null

    /** (v469) B3: تطبيق سرعة معيّنة وحفظها للفيديو الحالي (وترجع لوحدها لما يفتح تاني) + تحديث العام */
    fun setSpeedFull(f: Float) {
        speed = f.coerceIn(0.25f, 3f)
        try { player.setPlaybackSpeed(speed) } catch (_: Throwable) {}
        Cfg.p.edit().putString("speed", speed.toString()).putString("speed:$vid", speed.toString()).apply()
        speedBRef?.text = "⏩ " + PlayerLogic.speedLabel(speed)
        giShowFn("⏱ " + PlayerLogic.speedLabel(speed))
    }

    /** (v469) B3: لوحة السرعة الكاملة — منزلق 0.25×–3× + أزرار جاهزة */
    fun speedPanel() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12)) }
        val lbl = ui.text("⏩ " + PlayerLogic.speedLabel(speed), 16f, th.primary, true).apply { gravity = Gravity.CENTER }
        col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
        val sb = SeekBar(this).apply {
            max = 275; progress = (speed * 100 - 25).toInt().coerceIn(0, 275)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { setSpeedFull((p + 25) / 100f); lbl.text = "⏩ " + PlayerLogic.speedLabel(speed) } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(sb, LinearLayout.LayoutParams(-1, -2))
        val row = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for (p in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
            row.addView(ui.fsBtn(PlayerLogic.speedLabel(p)) { _ -> setSpeedFull(p); sb.progress = (p * 100 - 25).toInt().coerceIn(0, 275); lbl.text = "⏩ " + PlayerLogic.speedLabel(speed) }.apply { minimumWidth = ui.dp(58) }, ViewGroup.MarginLayoutParams(-2, ui.dp(38)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        }
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        col.addView(ui.text("السرعة بتتحفظ للفيديو ده وبتتطبّق تلقائيًا لما تفتحه تاني", 11f, th.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        ui.sheet(this, "⏩ سرعة التشغيل", listOf<View>(col))?.show()
    }

    /** (v469) B5: تعيين نقطتي حلقة A-B (لتعلّم اللغات: كرّر المقطع لحد ما تحفظه) */
    fun abSet(which: Int) {
        if (!::player.isInitialized) return
        val p = player.currentPosition
        if (which == 0) {
            abA = p; if (abB <= abA) abB = -1L
            say("🅰 نقطة أ: " + PlayerLogic.clock(abA) + (if (abB > abA) " · الحلقة شغالة لحد " + PlayerLogic.clock(abB) else " — عيّن نقطة ب من نفس القايمة"))
        } else {
            if (abA < 0 || p <= abA) { say("🅱 نقطة ب لازم تكون بعد أ — عيّن أ الأول وبعدين ب") ; return }
            abB = p; say("🔁 حلقة A-B شغالة: " + PlayerLogic.clock(abA) + " ← " + PlayerLogic.clock(abB) + " (الإلغاء من قايمة ⏱)")
        }
    }
    fun abClear() { if (abA >= 0 || abB >= 0) say("✖ حلقة A-B اتلغت"); abA = -1L; abB = -1L }
    private fun abTick() {
        if (abA >= 0 && abB > abA && ::player.isInitialized && player.isPlaying && cur >= abB) { try { player.seekTo(abA) } catch (_: Throwable) {} }
    }

    /** (v469) B9: استخراج الفصول من نص وصف — سطور تبدأ بطابع زمني (0:00 / 12:34 / 1:02:03) وبعدها عنوان */
    private fun parseChapters(desc: String): List<Chap> {
        val out = ArrayList<Chap>()
        val rx = Regex("^\\s*(?:(\\d{1,2}):)?([0-5]?\\d):([0-5]\\d)\\s+(.+)$")
        for (line in desc.lines()) {
            val m = rx.matchEntire(line.trim()) ?: continue
            val hh = m.groupValues[1].toLongOrNull() ?: 0L
            val mm = m.groupValues[2].toLongOrNull() ?: continue
            val ss = m.groupValues[3].toLongOrNull() ?: continue
            val t = m.groupValues[4].trim().trim('-', '–', '—', ' ').take(60)
            if (t.isNotEmpty()) out.add(Chap(((hh * 60 + mm) * 60 + ss) * 1000, t))
        }
        return out.distinctBy { it.ms }.sortedBy { it.ms }
    }

    /** (v469) B9: تحميل الفصول — وصف في الـ intent (لو أي مستدعٍ مرّره) أو ملف جانبي جنب الفيديو المحلي بنفس الاسم (.txt/.desc) */
    private fun loadChapters() {
        chapters = emptyList(); chapCur = -2
        intent.getStringExtra("desc")?.takeIf { it.isNotBlank() }?.let { chapters = parseChapters(it) }
        if (chapters.isNotEmpty()) { onChaptersReady(); return }
        val u = uri ?: return
        Thread {
            val found = try {
                val f = SrtWriter.pathOf(this, u.toString())
                val base = f?.nameWithoutExtension ?: ""
                val dir = f?.parentFile
                var txt: String? = null
                if (dir != null && base.isNotEmpty()) for (cand in listOf("$base.desc.txt", "$base.txt", "$base.desc")) { val cf = File(dir, cand); if (cf.isFile) { txt = cf.readText(); break } }
                txt
            } catch (_: Throwable) { null }
            if (!found.isNullOrBlank()) runOnUiThread { if (!isFinishing && !isDestroyed) { chapters = parseChapters(found); onChaptersReady() } }
        }.apply { isDaemon = true }.start()
    }
    private fun onChaptersReady() {
        chapTicksRef?.invalidate()
        if (chapters.isNotEmpty()) LogStore.add("📑 فصول: اتلقى ${chapters.size} فصل من الوصف")
    }
    fun chaptersSheet() {
        if (chapters.isEmpty()) { say("📑 مفيش فصول للفيديو ده — الفصول بتتقرا من وصف فيه سطور زي «12:34 عنوان الفصل» (للمحلي: ملف .txt بنفس اسم الفيديو جنبه)"); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) }
        var dlg: android.app.Dialog? = null
        for (c in chapters) {
            col.addView(ui.fsBtn("⏱ " + PlayerLogic.clock(c.ms) + "  ·  " + c.title) { _ -> dlg?.dismiss(); try { player.seekTo(c.ms); player.play() } catch (_: Throwable) {} }.apply { minimumWidth = ui.dp(220) })
        }
        val svw = ScrollView(this).apply { addView(col) }
        dlg = ui.sheet(this, "📑 فصول الفيديو (${chapters.size})", listOf<View>(svw)); dlg?.show()
    }

    /** (v469) B9: علامات الفصول — شريط شفاف بيترسم فوق شريط التقدم بالظبط (نقاط صفرا عند مواضع الفصول) */
    private inner class ChapTicks : View(this) {
        private val pnt = android.graphics.Paint().apply { color = 0xFFFFD54F.toInt(); style = android.graphics.Paint.Style.FILL }
        override fun onDraw(c: android.graphics.Canvas) {
            super.onDraw(c)
            if (chapters.isEmpty() || durMs <= 0 || width == 0) return
            val r = 3.2f * resources.displayMetrics.density
            for (ch in chapters) { val x = (ch.ms.toFloat() / durMs * width).coerceIn(0f, width.toFloat()); c.drawCircle(x, height / 2f, r, pnt) }
        }
    }

    /** (v469) B6: رفع الترجمة الإضافي (dp) من استوديو الترجمة — بيتركّب على هامش الأساس جوه applyChromeFn */
    fun subPosExtra(): Int = ui.dp(Cfg.int("sub_posy", 0).coerceIn(0, 240))

    /** (v469) نداء واحد من حلقة العرض (كل 200ms): حلقة A-B + عدّاد بطاقة التالي + تمييز الفصل الحالي */
    fun v469Tick() {
        abTick(); nextUpTick()
        if (chapters.isNotEmpty() && durMs > 0) {
            val ci = chapters.indexOfLast { it.ms <= cur }
            if (ci != chapCur) { chapCur = ci; chapTicksRef?.invalidate() }
        }
    }

    // ================= (v469) التالي تلقائي + لقطة + شريط تزامن + استوديو + بحث النص =================
    /** (v469) B13: بطاقة «التالي بعد…» — أي لمسة عليها تلغي الانتقال، واستنى 6 ثواني بيشتغل لوحده */
    private fun showNextUp(title: String, delayMs: Long = 5000L, go: () -> Unit) {
        cancelNextUp()
        nextUpGo = go; nextUpAt = System.currentTimeMillis() + delayMs
        val card = nextUpCard ?: run {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.box(0xE61A1C22.toInt(), 0x33FFFFFF, 14); alpha = REST_A
                val t1 = ui.text("", 13f, Color.WHITE, true); nextUpTitleV = t1; addView(t1)
                val t2 = ui.text("", 11f, 0xFFBFD0FF.toInt()); nextUpCountV = t2; addView(t2)
                addView(ui.text(if (DeviceUi.isRemote(this@PlayerActivity)) "OK = شغّل دلوقتي  ·  رجوع = إلغاء" else "دوس على الكارت لإلغاء التشغيل التلقائي", 10f, 0xFF9AA0A6.toInt()))
                setOnClickListener { cancelNextUp(); say("✖ التشغيل التلقائي للتالي اتلغى") }
            }
            videoBoxRef.addView(c, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(14), ui.dp(120)) })
            nextUpCard = c; c
        }
        nextUpTitleV?.text = "⏭ التالي: " + title.take(46)
        card.visibility = View.VISIBLE
        fadeTo(card, 1f)
    }
    fun cancelNextUp() { nextUpAt = 0L; nextUpGo = null; nextUpCard?.visibility = View.GONE }
    private fun nextUpTick() {
        if (nextUpAt <= 0L) return
        val left = nextUpAt - System.currentTimeMillis()
        if (left <= 0L) { val g = nextUpGo; cancelNextUp(); try { g?.invoke() } catch (e: Throwable) { LogStore.err("Player:v469Next", e) }; return }
        nextUpCountV?.text = "هيبدأ بعد " + (left / 1000 + 1) + " ث…"
    }
    /** (v469) B13: قرار «التالي» عند انتهاء الفيديو — يوتيوب من الاقتراحات، والمحلي من نفس الفولدر. الإيقاف من إعداد «autoplay_next» */
    fun v469AutoNext() {
        val yid = intent.getStringExtra("ytid")
        if (yid != null) {
            val n = try { YtNext.nextAfter(yid) } catch (_: Throwable) { null }
            if (n != null) {
                if (Cfg.str("autoplay_next", "1") == "1") {
                    // (v531) يوتيوب: يستنى ~20 ثانية (yt_next_delay) وفي الأثناء لوحة الفيديوهات القريبة تتفتح، وبعدها يشغّل التالي لوحده
                    showNextUp(n.title, Cfg.int("yt_next_delay", 20).coerceIn(3, 120) * 1000L) { playNextYt(n) }
                    try { nextPanel?.reveal() } catch (_: Throwable) {}
                } else playNextYt(n)
            }
            return
        }
        if (!hasNextEpisode()) return
        if (Cfg.str("autoplay_next", "1") != "1") { stepEpisode(1); return }
        showNextUp(nextEpisodeTitle()) { stepEpisode(1) }
    }
    private fun nextEpisodeTitle(): String {
        val p = libSibs() ?: return "الحلقة التالية"
        return (p.first.getOrNull(p.second + 1) ?: (if (wrapOn()) p.first.firstOrNull() else null))?.title ?: "الحلقة التالية"
    }

    /** (v469) B12: لقطة الإطار الحالي — بتتحفظ في صور الجهاز وبتتعرض للمشاركة على طول */
    fun snapFrame() {
        if (!::svRef.isInitialized || svRef.width <= 0 || svRef.height <= 0) { say("📸 مفيش إطار جاهز لسه"); return }
        val bmp = android.graphics.Bitmap.createBitmap(svRef.width, svRef.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res ->
                if (res != android.view.PixelCopy.SUCCESS) { say("📸 اللقطة فشلت — الإطار مش جاهز"); return@request }
                Thread {
                    var uriOut: android.net.Uri? = null
                    try {
                        val cv = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "subtitler_" + System.currentTimeMillis() + ".png")
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                            if (Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Subtitler")
                        }
                        uriOut = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        uriOut?.let { u -> contentResolver.openOutputStream(u)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    } catch (e: Throwable) { LogStore.err("Player:v469Snap", e) }
                    runOnUiThread {
                        val u = uriOut
                        if (u == null) { say("📸 ما اتحفظتش اللقطة"); return@runOnUiThread }
                        say("📸 اتحفظت اللقطة في «صور/Subtitler»")
                        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "مشاركة اللقطة")) } catch (e: Throwable) { LogStore.err("Player:v469SnapShare", e) }
                    }
                }.apply { isDaemon = true }.start()
            }, h)
        } catch (e: Throwable) { LogStore.err("Player:v469SnapReq", e); say("📸 اللقطة فشلت") }
    }

    /** (v469) B14: خطوة تزامن من الشريط العائم — نفس منطق أزرار ⏱ بالظبط (±0.25ث، حد ±10ث، حفظ لكل فيديو) */
    private fun syncStep(d: Long) {
        offsetMs = (offsetMs + d).coerceIn(-10000L, 10000L)
        Cfg.p.edit().putString("sub_offset_ms:$vid", offsetMs.toString()).apply()
        offFsB?.text = String.format("%+.2fs", offsetMs / 1000.0)
        syncValV?.text = String.format("%+.2f", offsetMs / 1000.0)
        curIdx = -2; srtSoon()
    }
    /** (v469) B14: شريط التزامن السريع العائم — زرّين −/+ بضغط مطوّل متكرر + عرض القيمة الحالية، وبخفوت الأزرار العائمة المعتاد */
    fun toggleSyncStrip() {
        syncStripV?.let { val show = it.visibility != View.VISIBLE; it.visibility = if (show) View.VISIBLE else View.GONE; if (show) syncValV?.text = String.format("%+.2f", offsetMs / 1000.0); return }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(5), ui.dp(6), ui.dp(5), ui.dp(6)); background = ui.box(0xCC14171C.toInt(), 0x33FFFFFF, 16); alpha = REST_A
        }
        fun repBtn(t: String, d: Long): TextView {
            val b = ui.fsCircle(t) { syncStep(d) }.apply { textSize = 16f }
            val rep = object : Runnable { override fun run() { syncStep(d); h.postDelayed(this, 110) } }
            b.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { fadeTo(col, 1f); h.postDelayed(rep, 420) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(rep); fadeTo(col, REST_A) }
                }
                false
            }
            return b
        }
        col.addView(repBtn("+", 250L), LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
        val valV = ui.text(String.format("%+.2f", offsetMs / 1000.0), 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(3), 0, ui.dp(3)) }
        syncValV = valV; col.addView(valV)
        col.addView(repBtn("−", -250L), LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
        col.addView(ui.text("⏱", 10f, 0xFF9AA0A6.toInt()).apply { gravity = Gravity.CENTER })
        videoBoxRef.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(6), 0) })
        syncStripV = col
        say("⏱ شريط التزامن السريع: ضغطة = ربع ثانية · ضغط مطوّل = تكرار سريع")
    }

    /** (v469) B6+B7: استوديو الترجمة — لوحة كاملة بمعاينة حية: حجم/لون/عتامة/هالة/موضع/خط/وضع مزدوج، وكلها بتتحفظ في Cfg */
    fun subStudio() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12)) }
        fun lbl(t: String) = ui.text(t, 12f, th.muted).apply { setPadding(0, ui.dp(9), 0, ui.dp(2)) }
        fun apply() { restyleFn(); curIdx = -2 }
        val prev = ui.text("الترجمة بالعربي — معاينة حية", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)); background = ui.box(0xFF000000.toInt(), 0x33FFFFFF, 10) }
        fun paintPrev() {
            val st = SubStyle.load { k, d -> Cfg.str(k, d) }
            prev.setTextColor(if (st.uniOn) runCatching { android.graphics.Color.parseColor(st.uniColor) }.getOrDefault(Color.WHITE) else Color.WHITE)
            prev.textSize = 15f * st.scale / 100f
            (prev.background as? GradientDrawable)?.setColor(android.graphics.Color.argb(if (st.noBg) 0 else st.bgOpa * 255 / 100, 0, 0, 0))
        }
        col.addView(prev, LinearLayout.LayoutParams(-1, -2))
        col.addView(lbl("📏 حجم الخط"))
        val sizeSb = SeekBar(this).apply { max = 140; progress = (Cfg.int("sub_scale", 100) - 60).coerceIn(0, 140)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { Cfg.put("sub_scale", (p + 60).toString()); apply(); paintPrev() } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            }) }
        col.addView(sizeSb, LinearLayout.LayoutParams(-1, -2))
        col.addView(lbl("🎨 لون النص الموحّد (أو سيبه لألوان المتحدثين)"))
        val colorRow = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val palette = listOf("#FFFFFF" to "أبيض", "#FFE600" to "أصفر", "#FF9F1C" to "برتقالي", "#00CFFF" to "سماوي", "#AEE2FF" to "أزرق فاتح", "#FBC9E0" to "وردي", "#CE93D8" to "بنفسجي", "#FFAB91" to "مرجاني")
        for ((hex, name) in palette) {
            val chip = ui.text("   ", 14f, Color.WHITE).apply {
                background = ui.box(android.graphics.Color.parseColor(hex), 0x66FFFFFF, 10); setPadding(ui.dp(16), ui.dp(9), ui.dp(16), ui.dp(9))
                setOnClickListener { Cfg.put("sub_uni_on", "1"); Cfg.put("sub_uni_color", hex); apply(); paintPrev(); say("🎨 لون النص: " + name) }
            }
            colorRow.addView(chip, ViewGroup.MarginLayoutParams(-2, -2).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        }
        colorRow.addView(ui.fsBtn("🎭 ألوان المتحدثين") { _ -> Cfg.put("sub_uni_on", "0"); apply(); paintPrev() }, ViewGroup.MarginLayoutParams(-2, ui.dp(36)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        col.addView(colorRow, LinearLayout.LayoutParams(-1, -2))
        col.addView(lbl("🌫 عتامة خلفية النص"))
        val bgSb = SeekBar(this).apply { max = 100; progress = if (Cfg.str("sub_nobg", "0") == "1") 0 else Cfg.int("sub_bgopa", 45)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { Cfg.put("sub_bgopa", p.toString()); Cfg.put("sub_nobg", "0"); apply(); paintPrev() } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            }) }
        col.addView(bgSb, LinearLayout.LayoutParams(-1, -2))
        col.addView(lbl("✨ هالة/حدود حول النص (بتوضّحه على أي خلفية)"))
        val blurSb = SeekBar(this).apply { max = 20; progress = Cfg.int("sub_blur", 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { Cfg.put("sub_blur", p.toString()); apply() } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            }) }
        col.addView(blurSb, LinearLayout.LayoutParams(-1, -2))
        col.addView(lbl("↕ موضع الترجمة (رفعها عن أسفل الشاشة)"))
        val posSb = SeekBar(this).apply { max = 240; progress = Cfg.int("sub_posy", 0).coerceIn(0, 240)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { Cfg.put("sub_posy", p.toString()); try { applyChromeFn() } catch (_: Throwable) {} } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            }) }
        col.addView(posSb, LinearLayout.LayoutParams(-1, -2))
        col.addView(lbl("🔤 نوع الخط ونمطه"))
        val fontRow = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for (f in SubStyle.fonts.take(10)) fontRow.addView(ui.fsBtn(f.label) { _ -> Cfg.put("sub_font", f.id); apply(); say("🔤 الخط: " + f.label) }, ViewGroup.MarginLayoutParams(-2, ui.dp(36)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        col.addView(fontRow, LinearLayout.LayoutParams(-1, -2))
        val styleRow = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for ((sid, sname) in SubStyle.fontStyles) styleRow.addView(ui.fsBtn(sname) { _ -> Cfg.put("sub_fontstyle", sid); apply() }, ViewGroup.MarginLayoutParams(-2, ui.dp(36)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        col.addView(styleRow, LinearLayout.LayoutParams(-1, -2))
        col.addView(lbl("💬 الترجمة المزدوجة (الأصلي + الترجمة معًا)"))
        val dualRow = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for ((did, dname) in listOf(0 to "فردي", 1 to "مزدوج: ترجمة فوق", 4 to "مزدوج: أصلي فوق", 3 to "الأصلي فقط")) dualRow.addView(ui.fsBtn((if (Cfg.int("sub_dual", 0) == did) "✓ " else "") + dname) { _ -> Cfg.put("sub_dual", did.toString()); apply(); say("💬 وضع الترجمة: " + dname) }, ViewGroup.MarginLayoutParams(-2, ui.dp(36)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        col.addView(dualRow, LinearLayout.LayoutParams(-1, -2))
        paintPrev()
        val svw = ScrollView(this).apply { addView(col) }
        ui.sheet(this, "🎨 استوديو الترجمة", listOf<View>(svw))?.show()
    }

    /** (v469) B8: بحث داخل النص — يقفز لتالي جملة فيها الكلمة (ترجمة أو أصل) وينزّل القايمة لها */
    fun sentSearch(q: String) {
        val t = q.trim(); if (t.isEmpty() || list.isEmpty()) { say("🔎 اكتب كلمة للبحث في النص الأول"); return }
        val n = list.size
        for (k in 1..n) {
            val i = (sentSearchFrom + k) % n
            val s = list[i]
            if (s.translated.contains(t, true) || s.original.contains(t, true)) {
                sentSearchFrom = i
                try { player.seekTo(starts[i] + offsetMs) } catch (_: Throwable) {}
                try { listView.smoothScrollToPositionFromTop(i, ui.dp(30)) } catch (_: Throwable) {}
                say("🔎 جملة " + (i + 1) + " من " + n + ": " + (if (s.translated.isNotEmpty()) s.translated else s.original).take(50))
                return
            }
        }
        say("🔎 مفيش جملة فيها «" + t.take(20) + "»")
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        smoothRotation()   // (v429)
        synchronized(liveList) {
            liveList.removeAll { it.get() == null }
            liveList.toList().forEach { r -> r.get()?.let { o -> if (o !== this) try { o.runOnUiThread { o.killForNewVideo() } } catch (_: Throwable) {} } }
            liveList.removeAll { it.get() !== this }
            liveList.add(java.lang.ref.WeakReference(this))
        }
        UiWatchdog.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Cfg.init(this); CrashLog.install(this); conf = Cfg.snapshot()
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        applyBars(th)
        speed = Cfg.str("speed", "1").toFloatOrNull() ?: 1f; fit = Cfg.int("fit", 0).coerceIn(0, 3); fsFit = Cfg.int("fs_fit", 2).coerceIn(0, 3); offsetMs = 0L   // (v469) الحد بقى 3 عشان وضع 100% الجديد
        uri = intent.data; url = intent.getStringExtra("url")
        remote = intent.getBooleanExtra("remote", false)
        if (remote) TvSync.install(this)   // (v568) مزامنة الإعدادات للتلفزيون بقت عامة (مش مرتبطة بشاشة التحكم بس)
        pendingYt = !remote && intent.getBooleanExtra("ytpend", false) && url == null && uri == null && intent.getStringExtra("ytid")?.length == 11   // (v351)
        try { if (!incognito && !hiddenSite) PubHistory.addVideoFrom(intent) } catch (_: Throwable) {}   // (v349) السجل العام
        try { if (!incognito) intent.getStringExtra("ytid")?.let { yid -> if (yid.length == 11) YtHistory.add(this, yid, intent.getStringExtra("title") ?: "") } } catch (_: Throwable) {}   // (v407) سجل يوتيوب المنفصل
        try {
            intent.getStringExtra("qlist")?.let { sj -> val qa = org.json.JSONArray(sj)
                qOpts = (0 until qa.length()).map { qi -> val o = qa.getJSONObject(qi); YtExtract.Opt(o.optString("l"), o.optString("u"), o.optString("a").ifEmpty { null }, o.optInt("h")) }; parseATracks(sj) }
        } catch (e: Exception) { LogStore.err("Player:qlist", e) }
        qCur = qOpts.indexOfFirst { it.url == url }
        aurl = intent.getStringExtra("aurl")?.takeIf { it.isNotEmpty() }   // (v118) صوت منفصل ليوتيوب (جودة عالية)
        noSub = intent.getBooleanExtra("nosub", false); askSaved = intent.getBooleanExtra("ask", false); autoTr = intent.getBooleanExtra("autotr", false)
        intent.getStringExtra("ua")?.takeIf { it.isNotEmpty() }?.let { hdr["User-Agent"] = it }
        intent.getStringExtra("ref")?.takeIf { it.isNotEmpty() }?.let { hdr["Referer"] = it }
        intent.getStringExtra("cookie")?.takeIf { it.isNotEmpty() }?.let { hdr["Cookie"] = it }

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val videoBox = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = ui.dp(14).toFloat() }; clipToOutline = true; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        videoBoxRef = videoBox
        val sv = SurfaceView(this); svRef = sv
        sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {   // (v604) تدوير الموبايل في وضع المتحكم
            override fun surfaceCreated(h: android.view.SurfaceHolder) { if (remote) redrawLastRemoteFrame() }
            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) { if (remote) { redrawLastRemoteFrame(); rmFrameForce = true } }
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
        })
        videoBox.addView(sv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        sub = SubtitleView(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; style = SubStyle.load { k, d -> Cfg.str(k, d) } }
        val subLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ui.dp(12) }
        visOv = VisualOverlay(this); videoBox.addView(visOv, FrameLayout.LayoutParams(-1, -1))
        visOv.area = { if (sv.width > 0 && sv.height > 0) android.graphics.RectF(sv.left.toFloat(), sv.top.toFloat(), sv.right.toFloat(), sv.bottom.toFloat()) else android.graphics.RectF(0f, 0f, videoBox.width.toFloat(), videoBox.height.toFloat()) }
        videoBox.addView(sub, subLp)
        sub.backdrop = sv
        st = IconTextView(this).apply { setTextColor(th.primary); textSize = 11f; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        videoBox.addView(st, FrameLayout.LayoutParams(-2, -2, Gravity.TOP))
        soundTv = IconTextView(this).apply {
            setTextColor(0xFFE8EAED.toInt()); textSize = 13f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
            gravity = Gravity.CENTER; maxLines = 2; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); background = ui.box(0x99000000.toInt(), 0x00000000, 14); visibility = View.GONE
        }
        // طبقة إيماءات شفافة فوق الفيديو والترجمة وتحت كل الأزرار (لازم تتضاف قبل درج اللوج وزرار 👁 وإلا بتبلع لمسهم)
        val gestureLayer = View(this)
        videoBox.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // (v134) فوق طبقة الإيماءات عشان علامة ⓘ تستقبل اللمس
        videoBox.addView(soundTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(28) })
        soundTv.setOnClickListener { showTermCard() }
        // أزرار عايمة فوق الفيديو: 📋 اللوج (يخفي/يظهر حالة الترجمة) و 👁 بصري
        batchTv = IconTextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10f; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(8).toFloat() }
            layoutDirection = View.LAYOUT_DIRECTION_RTL; typeface = android.graphics.Typeface.MONOSPACE
            setOnClickListener { toggleLog() }
        }
        // درج اللوج: اللوج + لسان صغير في نص حافته. الضغط على اللسان بيدخّل اللوج أقصى الشمال ويفرده تاني
        logHandle = IconTextView(this).apply {
            text = "◂"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); alpha = REST_A
            background = ui.box(0xCC14171C.toInt(), 0x33FFFFFF, 8); setOnClickListener { toggleLog() }
            visibility = View.GONE   // (v90) اللوج بقى بيتفتح من زرار 📋 جنب 📝 في الشريط السفلي
        }
        logHandle.text = if (logOn) "◂" else "▸"
        logDrawer = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
            addView(batchTv, LinearLayout.LayoutParams(-2, -2)); addView(logHandle, LinearLayout.LayoutParams(ui.dp(22), ui.dp(46)).apply { marginStart = ui.dp(2) }) }
        batchTv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!logOn) logDrawer.translationX = -(batchTv.width + ui.dp(2)).toFloat() }
        // عمود الشمال: كبسولة الباتش الفاشل (لو في) فوق، واللوج تحتها
        leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; clipChildren = false; clipToPadding = false }
        // (v100) اللوج العايم اتلغى: حالة الباتشات بقت جوه صفحة اللوجز (logCol)
        videoBox.addView(leftCol, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(0, ui.dp(44), 0, 0) })   // (v182) نازل تحت الكبسولات (v249: الكبسولات بقت أصغر فالمسافة 44)
        // 👁 بصري: دايرة عايمة فوق دايرة ✦
        // (v185) ضغطة واحدة = لقطة (بتتصوّر وتتترجم وتتحفظ مع الفيديو)؛ مفيش ضغطة مطوّلة، والتلقائي مابيتقفلش من الزرار ده (القايمة من عدّاد 👁 أقصى الشمال)
        floatBar = ui.fsCircle("👁") { flashFloat(floatBar); visualSnap() }.apply {
            textSize = 24f; alpha = REST_A
        }
        videoBox.addView(floatBar, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.END or Gravity.TOP).apply { setMargins(0, ui.dp(120), ui.dp(8), 0) })   // (v95) المكان بيتحسب في placeFloatFn
        // (v95) زرار الروتيشن: شاشة بسهمين دايريين زي MX Player — فوق 👁 على طول؛ بيقلب بين الرأسي والأفقي
        rotBtn = ui.fsCircle(Icons.ROT) { flashFloat(rotBtn); toggleFs() }.apply { textSize = 24f; alpha = REST_A }
        videoBox.addView(rotBtn, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END or Gravity.TOP).apply { setMargins(0, ui.dp(60), ui.dp(11), 0) })
        rotBtn.visibility = View.GONE   // (v97) زرار الروتيشن العايم اتلغى — الروتيشن في الشريط السفلي بس
        // (v343) 🎧 حوّل لصوت: بيكمّل نفس فيديو يوتيوب في مشغّل الموسيقى من نفس المكان (بيوفّر الداتا) — تحت 👁 على طول
        audBtn = ui.fsCircle("🎧") { switchToAudio() }.apply { textSize = 20f; alpha = REST_A; visibility = View.GONE }
        videoBox.addView(audBtn, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.END or Gravity.TOP).apply { setMargins(0, ui.dp(174), ui.dp(8), 0) })
        // (v459) كبسولة الوضع العريضة: بتاخد مكان 👁 العايمة (مكانها بيتحسب في placeFloatFn) وبتظهر لفيديوهات يوتيوب بس
        modePill = ui.text("🎧 وضع الصوت", 13.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; includeFontPadding = false
            setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9))
            background = ui.box(0xCC242832.toInt(), 0x33FFFFFF, 22)
            alpha = 0.92f; visibility = View.GONE
            setOnClickListener { flashFloat(modePill); toggleYtMode() }
        }
        videoBox.addView(modePill, FrameLayout.LayoutParams(-2, ui.dp(44), Gravity.END or Gravity.TOP).apply { setMargins(0, ui.dp(226), ui.dp(8), 0) })
        // 🔄 ترجم باتش معين: دايرة عايمة فوق 👁
        batchBtn = ui.fsCircle("🔄") { batchDialog() }.apply { textSize = 20f; alpha = REST_A; translationY = -ui.dp(60).toFloat() }
        // (v90) الزرار العايم 🔄 اتشال من الشاشة — «إعادة ترجمة» لسه في 🧰 وفي ☰
        // (v90) كبسولة «▶ ترجمة» الحمرا على الشمال اتشالت — زرار ▶ ترجمة الوحيد في الشريط السفلي
        // كبسولة جانبية للباتشات الفاشلة (زي اللوج): مخفية خالص لحد ما باتش يفشل، وبعدها بيظهر لسان ⚠ على الحافة الشمال — دوس عليه يفرد الكبسولة ودوس تاني يلمّها
        probBar = IconTextView(this).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5)); background = ui.box(0xE6B71C1C.toInt(), 0x33FFFFFF, 12)
            setOnClickListener { askRetryProblem() }
        }
        probHandle = IconTextView(this).apply {
            text = "⚠"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = ui.box(0xE6B71C1C.toInt(), 0x33FFFFFF, 8); alpha = PROB_A; setOnClickListener { toggleProb() }
        }
        probDrawer = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(probBar, LinearLayout.LayoutParams(-2, -2)); addView(probHandle, LinearLayout.LayoutParams(ui.dp(26), ui.dp(40)).apply { marginStart = ui.dp(2) }) }
        probBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!probOn) probDrawer.translationX = -(probBar.width + ui.dp(2)).toFloat() }
        leftCol.addView(probDrawer, 0, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(4) })
        // (v213) شريط حالة الوضع البصري: تحت شريط الباتش الفاشل (probDrawer) ومكان الكلام اللي كان بيظهر إشعارات. بيظهر بس والوضع البصري شغّال
        visBar = IconTextView(this).apply {
            setTextColor(0xFFE8EAED.toInt()); textSize = 10f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)); layoutDirection = View.LAYOUT_DIRECTION_RTL; alpha = 0.85f
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(8).toFloat() }
            maxWidth = ui.dp(230); visibility = View.GONE
        }
        // (v368) شريط حالة الوضع البصري («👁 تحليل N فريم» / «مسح @..ث») اتشال من الشاشة — الحالة في اللوج

        // زرار التشغيل الأوسط (بيظهر وقت الإيقاف) + شارة النسبة (شاشة كاملة) + فلاش السيك/الصوت/السطوع
        centerPlay = FadePop(this).apply {
            text = "▶"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE
            background = glassCircleBg(resources.displayMetrics.density)
            setOnClickListener { togglePlay() }
            Glass.pressable(this)   // (v445)
            isFocusable = false; isFocusableInTouchMode = false; defaultFocusHighlightEnabled = false   // (v576) تلفزيون: ماياخدش حلقة التركيز (المربع الأزرق) — الريموت بيتحكم في المشغّل من غيره
        }
        videoBox.addView(centerPlay, FrameLayout.LayoutParams(ui.dp(74), ui.dp(74), Gravity.CENTER))
        // (v249) كبسولات العدّادات: صغيرة، التلاتة جنب بعض شمال فوق الفيديو (صوتي · بصري · نت) والشفافية من الإعدادات (badge_alpha)
        fsBadge = TransBadge(this, th, true).apply { visibility = View.GONE; setOnClickListener { badgeHot(); toggleMiniLog(); h.postDelayed({ applyBadgeAlpha() }, 4100) }; setOnLongClickListener { badgeHot(); closeMiniLog(); versionsPopup(this); h.postDelayed({ applyBadgeAlpha() }, 4100); true } }   // (v125) دوسة = اللوج الصغير (أنهي باتش بيترجم) · ضغطة طويلة = قايمة الترجمات المحفوظة
        visBadge = TransBadge(this, th, true).apply { visibility = View.GONE; setOnClickListener { badgeHot(); visualDialog(); h.postDelayed({ applyBadgeAlpha() }, 4100) } }
        netBadge = NetBadge(this).apply { visibility = View.GONE; setOnClickListener { badgeHot(); netDialog(); h.postDelayed({ applyBadgeAlpha() }, 4100) } }
        // (v217) مثلث ▲ أحمر شفاف 70% جنب عدّاد الجمل (فوق اليمين): بيظهر لما فيه إشعارات، ولمسة عليه = قايمة منسدلة من الأحدث للأقدم
        noticeBtn = ui.text("▲", 13f, 0xFFFF3B30.toInt(), true).apply {
            visibility = View.GONE; alpha = 0.7f; gravity = Gravity.CENTER
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); background = ui.box(0x33FF3B30, 0x99FF3B30.toInt(), 20)
            setOnClickListener { noticePopup(this) }
        }
        val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        // (v322) عدّاد واحد: كبسولة فيها الصوتي والبصري بينهم فاصل (بدل كبسولتين ضيقين بيتقصّ نصهم)
        sentCap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; background = glassBtnBg(resources.displayMetrics.density, 12) }
        sentDiv = View(this).apply { setBackgroundColor(0x33FFFFFF); visibility = View.GONE }
        sentCap.addView(fsBadge, LinearLayout.LayoutParams(-2, -2))
        sentCap.addView(sentDiv, LinearLayout.LayoutParams(ui.dp(1), ui.dp(24)).apply { setMargins(ui.dp(1), 0, ui.dp(1), 0) })
        sentCap.addView(visBadge, LinearLayout.LayoutParams(-2, -2))
        badgeRow.addView(sentCap, LinearLayout.LayoutParams(-2, -2))
        badgeRow.addView(netBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(4) })
        val badgeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        badgeCol.addView(badgeRow, LinearLayout.LayoutParams(-2, -2))
        badgeRowRef = badgeRow
        badgeRowV = badgeCol
        videoBox.addView(badgeCol, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply { setMargins(0, ui.dp(8), ui.dp(10), 0) })   // (v265) الكبسولات يمين فوق
        applyBadgeAlpha()
        NoticeLog.attach(this) { paintNotices() }; paintNotices()
        val gi = IconTextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.box(0x99000000.toInt(), Color.TRANSPARENT, 24); visibility = View.GONE
        }
        videoBox.addView(gi, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val volInd = VertInd(this, "🔊", 2f); val briInd = VertInd(this, "☀️")
        videoBox.addView(volInd, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(24), 0) })
        videoBox.addView(briInd, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { setMargins(ui.dp(24), 0, 0, 0) })
        val indHide = Runnable { volInd.visibility = View.GONE; briInd.visibility = View.GONE }
        fun indShow(v: VertInd, f: Float) { v.set(f); h.removeCallbacks(indHide); h.postDelayed(indHide, 900) }
        // (v200) فقاعة الأدوات ✦ (📝 🕳 📥 📂) اتشالت — نفس الأوامر موجودة في قايمة 🧰 الأدوات
        fsOnly = listOf<View>(fsBadge, visBadge, netBadge)
        val giHide = Runnable { gi.visibility = View.GONE }
        fun giShow(t: String, g: Int) {
            gi.text = t
            val lp = gi.layoutParams as FrameLayout.LayoutParams
            lp.gravity = g; val edge = (videoBox.width * 0.18f).toInt(); lp.setMargins(edge, 0, edge, 0); gi.layoutParams = lp
            gi.visibility = View.VISIBLE; h.removeCallbacks(giHide); h.postDelayed(giHide, 800)
        }

        // ---- نفس خطوات الأصل: تحكم الشاشة الكاملة (أزرار الترجمة فوق + كبسولة التقدم تحت) ----
        fun curStyle() = SubStyle.load { k, d -> Cfg.str(k, d) }
        fun restyle() { sub.style = natStyle(curStyle()); curIdx = -2 }
        restyleFn = { restyle() }
        fun fontLabel(): String { val f = curStyle().font; return SubStyle.fonts.firstOrNull { it.id == f }?.label ?: f }
        fun entLabel(): String { val a = curStyle().anim; return SubStyle.entrances.firstOrNull { it.id == a }?.label ?: "افتراضي" }
        giShowFn = { m -> giShow(m, Gravity.CENTER) }
        fun fb(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); showChrome() }
        /** زرار دايري صغير للصف الأدوات اللي تحت جنب أزرار الشاشة */
        fun mini(t: String, f: (TextView) -> Unit): TextView {
            val ref = arrayOfNulls<TextView>(1)
            val v = ui.fsCircle(t) { ref[0]?.let { f(it) }; showChrome() }
            ref[0] = v; v.textSize = 12f; return v
        }
        // زرار تبديل عرض الترجمة: ترجمة ← أصلي ← ترجمة+أصلي تحت ← أصلي فوق+ترجمة تحت
        val dualCycle = listOf(0, 3, 1, 4); val dualShort = mapOf(0 to "فردي", 3 to "أصلي", 1 to "مزدوج", 4 to "مزدوج (أصلي فوق)", 2 to "إنجليزي")
        fun dualLabel() = "💬 وضع الترجمة: " + (dualShort[curStyle().dual] ?: "فردي")
        fun dualCircleBg() = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (curStyle().dual == 0) 0xE00F0F12.toInt() else 0xFF1F5FBF.toInt()); setStroke(ui.dp(1), 0x1FFFFFFF) }
        fun cycleDual(v: TextView) {
            val n = dualCycle[(dualCycle.indexOf(curStyle().dual) + 1) % dualCycle.size]
            Cfg.put("sub_dual", n.toString()); restyle(); v.text = "💬"; v.background = dualCircleBg()
            giShow(dualLabel(), Gravity.CENTER)
        }
        var fsSpeedB: TextView? = null
        var ccFsB: TextView? = null
        fun cycleSpeed() {
            speed = PlayerLogic.nextSpeed(speed); player.setPlaybackSpeed(speed)
            Cfg.p.edit().putString("speed", speed.toString()).putString("speed:$vid", speed.toString()).apply()   // (v469) الدورة كمان بتحفظ لكل فيديو
            fsSpeedB?.text = "⏩ " + PlayerLogic.speedLabel(speed)
            giShow("⏱ " + PlayerLogic.speedLabel(speed), Gravity.CENTER)
        }
        fun toggleCc() {
            ccOn = !ccOn; val a = if (ccOn) 1f else 0.4f
            ccFsB?.alpha = a; ctl.cc.alpha = a
            if (!ccOn) sub.show(null) else curIdx = -2
        }
        fun offFmt() = String.format("%+.2fs", offsetMs / 1000.0)   // (v468) عرض الإزاحة بدقة ربع ثانية
        fun setOff(d: Long) {
            offsetMs = (offsetMs + d).coerceIn(-10000L, 10000L); Cfg.p.edit().putString("sub_offset_ms:$vid", offsetMs.toString()).apply(); srtSoon()   // (v468) حد الإزاحة ±10ث
            offFsB?.text = offFmt(); curIdx = -2
        }

        ctl = ui.controls()
        // صفّين ثابتين من اليمين (نفس البداية ونفس الارتفاع 32dp، والمسافة بينهم 6dp = هامش 3+3)
        var popup: android.widget.PopupWindow? = null; var popupOwner: View? = null
        fun dismissPop() { try { popup?.dismiss() } catch (e: Exception) { LogStore.err("Main:1353", e) }; popup = null; popupOwner = null }
        dismissPopFn = { dismissPop() }
        fun after(keep: Boolean) { if (!keep) dismissPop(); showChrome(); if (popup != null) h.removeCallbacks(hideChrome) }
        fun pk(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); after(true) }.apply { minimumWidth = ui.dp(150) }
        fun pd(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); after(false) }.apply { minimumWidth = ui.dp(150) }
        fun gCol() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = glassPanelBg() }
        val gText = gCol(); val gAi = gCol(); val gTool = gCol()
        // الأزرار العلوية في صف واحد بس (في الرأسي الصف بيتسحب أفقيًا لو الأزرار أكتر من العرض بدل ما تنزل لسطر تاني)
        // الترتيب من اليمين: CC ← احتواء ← القائمة ← الجمل ← النص ← لهجة ← أدوات (كل زرار بيتضاف على يسار اللي قبله)
        val tbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fun tbAdd(v: View) { tbRow.addView(v, 0) }
        val tb = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(tbRow, FrameLayout.LayoutParams(-2, -2))
        }
        var tbScrolled = false
        tb.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!tbScrolled && tbRow.width > 0) { tbScrolled = true; tb.post { tb.scrollTo(tbRow.width, 0) } } }
        var textAtBottom = false
        fun togglePop(v: View, col: LinearLayout, above: Boolean) {
            val had = popupOwner === v; dismissPop()
            if (!had) {
                (col.parent as? android.view.ViewGroup)?.removeView(col)
                // (v125) القوايم الطويلة بتتسكرول وبتتحصر في المساحة المتاحة بدل ما تطلع بره الشاشة
                val dmP = resources.displayMetrics
                col.measure(View.MeasureSpec.makeMeasureSpec((dmP.widthPixels * 0.9f).toInt(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val goUp = above || (col === gText && textAtBottom)   // زرار في الشريط السفلي: القايمة تفتح فوقه
                val avail = (if (goUp) loc[1] - ui.dp(8) else dmP.heightPixels - loc[1] - v.height - ui.dp(8)).coerceAtLeast(ui.dp(120))
                val popH = minOf(col.measuredHeight, avail)
                val svP = android.widget.ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(col) }
                val pw = if (DeviceUi.isTv(this)) TvFocus.pop(this, svP, maxOf(col.measuredWidth, ui.dp(150)), popH, true)   // (v517) تلفزيون: القايمة بتاخد التركيز عشان الريموت يوصل لعناصرها
                    else android.widget.PopupWindow(svP, maxOf(col.measuredWidth, ui.dp(150)), popH, false)
                pw.isOutsideTouchable = true; pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
                if (DeviceUi.isTv(this)) pw.setOnDismissListener { if (popup === pw) { popup = null; popupOwner = null } }
                if (goUp) pw.showAsDropDown(v, 0, -(v.height + popH + ui.dp(2)))
                else pw.showAsDropDown(v, 0, ui.dp(2))
                popup = pw; popupOwner = v
            }
            showChrome(); if (popup != null) h.removeCallbacks(hideChrome)
        }
        fun grp(label: String, col: LinearLayout, above: Boolean = false): TextView = ui.fsBtn("$label ▾") { v -> togglePop(v, col, above) }
        gText.addView(pk("🔤 " + fontLabel()) { v ->
            val fs = SubStyle.fonts; val cf = curStyle().font
            val n = fs[(fs.indexOfFirst { it.id == cf } + 1) % fs.size]
            Cfg.put("sub_font", n.id); restyle(); v.text = "🔤 " + n.label
        })
        gText.addView(pk("🎨 استوديو الترجمة") { subStudio() })   // (v469) B6: لوحة ستايل كاملة بمعاينة حية
        gText.addView(pk("⏩ سرعة التشغيل…") { speedPanel() })   // (v469) B3: منزلق + جاهز + حفظ لكل فيديو
        gText.addView(pk("✨ " + entLabel()) { v ->
            val es = SubStyle.entrances; val ca = curStyle().anim
            val n = es[(es.indexOfFirst { it.id == ca } + 1) % es.size]
            Cfg.put("sub_anim", n.id); restyle(); v.text = "✨ " + n.label
        })
        val spB = pk("⏩ " + PlayerLogic.speedLabel(speed)) { cycleSpeed() }
        fsSpeedB = spB; speedBRef = spB; gText.addView(spB)   // (v469) مرجع كلاسي عشان استرجاع سرعة الفيديو يحدّث اللافتة
        run {
            val cs = curStyle()
            val bgTv = IconTextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
            fun bgLabel(p: Int) = "🌫 غمقان الخلفية: " + (if (p == 0) "شفافة" else "$p%")
            val p0 = if (cs.noBg) 0 else cs.bgOpa
            bgTv.text = bgLabel(p0)
            val bgSb = SeekBar(this).apply {
                max = 100; progress = p0
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                        bgTv.text = bgLabel(p)
                        if (!u) return
                        Cfg.put("sub_bgopa", p.toString()); Cfg.put("sub_nobg", "0"); restyle()
                        showChrome(); h.removeCallbacks(hideChrome)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { h.removeCallbacks(hideChrome) }
                    override fun onStopTrackingTouch(sb: SeekBar?) { showChrome(); h.removeCallbacks(hideChrome) }
                })
            }
            gText.addView(bgTv, LinearLayout.LayoutParams(ui.dp(150), -2))
            gText.addView(bgSb, LinearLayout.LayoutParams(ui.dp(150), -2))
        }
        val ccB = mini("CC") { toggleCc() }.apply { textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        ccFsB = ccB
        fun loopBg() = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (Cfg.str("loop", "0") == "1") 0xFF1F5FBF.toInt() else 0xE00F0F12.toInt()); setStroke(ui.dp(1), 0x1FFFFFFF) }
        val loopB = mini("🔂") { v -> Cfg.put("loop", if (Cfg.str("loop", "0") == "1") "0" else "1"); v.background = loopBg(); giShow(if (Cfg.str("loop", "0") == "1") "🔂 تكرار الفيديو: شغّال" else "🔂 تكرار الفيديو: مقفول", Gravity.CENTER) }.apply { background = loopBg() }
        val dualB = mini("💬") { v -> cycleDual(v) }.apply { background = dualCircleBg() }
        // (v208) زرار 🔑 بيبدّل وضع المفاتيح (1→2→3→4) ويوصل للمحرك الشغّال فورًا — زي زرار اللغة
        val voiceB = mini("🎙") { voiceMenu() }   // (v334) فصل صوت الكلام عن الموسيقى — يدوي بس
        voiceBtnRef = voiceB
   // وضع الترجمة (فردي / أصلي / مزدوج) — أيقونة بس من غير كلام
        ccToggleFn = { toggleCc() }
        if (noSub) { ccOn = false; ccB.alpha = 0.4f; ctl.cc.alpha = 0.4f }
        val fitB = fb("⬛ " + fitNameOf(curFit())) { v ->
            if (remote) { cycleFitNow(); return@fb }   // (v604) يسأل موبايل/تلفزيون
            if (isLandNow()) { fsFit = (fsFit + 1) % 4; Cfg.p.edit().putString("fs_fit", fsFit.toString()).apply() }
            else { fit = (fit + 1) % 4; Cfg.p.edit().putString("fit", fit.toString()).apply() }
            v.text = "⬛ " + fitNameOf(curFit()); applyFit(sv, videoBox)
        }
        fitFsB = fitB
        val menuB = IconGlyphButton(this, "menu").apply { background = ui.box(0xE0141418.toInt(), 0x1FFFFFFF, 12); setOnClickListener { togglePop(this, gTool, false) } }
        val bgOnB = IconGlyphButton(this, if (Cfg.bool("ask_bg_exit", true)) "check_box" else "box").apply {
            background = ui.box(0xE0141418.toInt(), 0x1FFFFFFF, 12)
            setOnClickListener {
                val on = !Cfg.bool("ask_bg_exit", true)
                Cfg.put("ask_bg_exit", if (on) "1" else "0"); iconName = if (on) "check_box" else "box"
                giShowFn(if (on) "هسألك لما تخرج: كمّل في الخلفية ولا لا" else "لما تخرج: الترجمة بتتقفل من غير سؤال ومن غير خلفية")
                showChrome()
            }
            setOnLongClickListener { giShow("اسألني عند الخروج: أكمّل الترجمة في الخلفية؟", Gravity.CENTER); true }
        }
        // (v118) زرار الجودة: يوتيوب بقايمة جودات (بحجمها التقريبي) · روابط HLS/DASH بمساراتها — للفرجة بداتا أقل
        val gQ = gCol()
        fun fillQ(): Boolean {
            gQ.removeAllViews()
            if (qOpts.size > 1) {
                for ((qi, o) in qOpts.withIndex()) gQ.addView(pd((if (qi == qCur) "✓ " else "") + o.label) { switchYt(qi) })
            } else {
                val tg = ArrayList<Triple<Int, androidx.media3.common.Tracks.Group, Int>>()
                for (g in (player.exo()?.currentTracks?.groups ?: emptyList<androidx.media3.common.Tracks.Group>())) if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO)
                    for (ti in 0 until g.length) if (g.isTrackSupported(ti)) tg.add(Triple(g.getTrackFormat(ti).height, g, ti))
                // (v120) أعلى bitrate لكل ارتفاع، والحجم الكلي تقريبي = bitrate × مدة الفيديو (HLS/DASH مفيهوش حجم جاهز)
                val hs = tg.filter { it.first > 0 }.sortedWith(compareByDescending<Triple<Int, androidx.media3.common.Tracks.Group, Int>> { it.first }.thenByDescending { it.second.getTrackFormat(it.third).bitrate }).distinctBy { it.first }
                fun szOf(q: Triple<Int, androidx.media3.common.Tracks.Group, Int>): String {
                    val br = q.second.getTrackFormat(q.third).bitrate.toLong()
                    return if (br > 0 && durMs > 0) " · ≈" + Sniff.fmtSize(br / 8 * (durMs / 1000)) else ""
                }
                if (hs.size > 1) {
                    gQ.addView(pd((if (hlsH == 0) "✓ " else "") + "تلقائي") { hlsAuto() })
                    for (q in hs) gQ.addView(pd((if (hlsH == q.first) "✓ " else "") + q.first + "p" + szOf(q)) { hlsPick(q.second, q.third, q.first) })
                }
            }
            return gQ.childCount > 0
        }
        val qualB = mini("HD") { v -> if (fillQ()) togglePop(v, gQ, true) else Notice.show(this, "مفيش جودات تانية للفيديو ده", 2300L) }
        qualB.visibility = if (uri == null) View.VISIBLE else View.GONE
        qualBRef = qualB
        qualPopFn = { v -> if (fillQ()) togglePop(v, gQ, true) else Notice.show(this, "مفيش جودات تانية للفيديو ده", 2300L) }
        // (v388) قايمة لغات الصوت
        val gA = gCol()
        fun fillA(): Boolean {
            gA.removeAllViews()
            for ((ai, t) in aTracks.withIndex()) gA.addView(pd((if (ai == aCur) "✓ " else "") + t.name) { switchAudio(ai) })
            return aTracks.size > 1
        }
        audPopFn = { v -> if (fillA()) togglePop(v, gA, true) else Notice.show(this, "مفيش لغات صوت تانية للفيديو ده", 2300L) }
        setQualLabel(qOpts.getOrNull(qCur)?.h ?: 0)
        val sentB = mini("📝") { sentDlg.show() }   // الجمل
        val charsB = mini("🧑") { showChars() }   // (v151) الشخصيات وجنسها — نافذة فوق الفيديو من غير ما تضغط شاشته
        val logB = mini("📜") { toggleLog() }   // (v90) اللوج — مكان 🔄 جنب الجمل
        // (v357) زرار «📃 التالي» (يوتيوب بس): يفتح لوحة الفيديوهات اللي بعد الحالي من غير ما تخرج من المشغّل · وزرار الجودة (HD) بقى ظاهر في الشريط العلوي لفيديوهات يوتيوب
        val nextListB = mini("📃") { openNextSheet() }; nextListB.visibility = if (intent.getStringExtra("ytid") != null) View.VISIBLE else View.GONE
        // التوقيت: زرار واحد ⏱ (في الشريط السفلي) بيفتح قايمة صغيرة: تقديم −0.1 / القيمة (ضغطة = رجوع للصفر) / تأخير +0.1 — زي MX Player
        // لوحة ⏱: تزامن الترجمة (تقديم −0.1 / القيمة / تأخير +0.1) — حجم الخط بالقرص (pinch)
        val gSub = gCol()
        gSub.addView(pk("⏱ تقديم −0.25") { setOff(-250) })   // (v468) خطوة ربع ثانية
        val offB = pk(offFmt()) { setOff(-offsetMs) }
        offFsB = offB; gSub.addView(offB)
        gSub.addView(pk("⏱ تأخير +0.25") { setOff(250) })   // (v468) خطوة ربع ثانية
        gSub.addView(pk("🅰 تعيين نقطة أ (حلقة A-B)") { abSet(0) })   // (v469) B5
        gSub.addView(pk("🅱 تعيين نقطة ب (حلقة A-B)") { abSet(1) })   // (v469) B5
        gSub.addView(pk("✖ إلغاء حلقة A-B") { abClear() })   // (v469) B5
        gSub.addView(pk("⏱ شريط التزامن السريع") { toggleSyncStrip() })   // (v469) B14
        // (v103) قايمة ☰ = قايمة منسدلة صغيرة فيها بس الأزرار اللي مش موجودة في المشغّل نفسه
        gTool.addView(pd("📤 تصدير SRT") { doExport() })
        gTool.addView(pd("📥 استيراد SRT") { doImport() })
        gTool.addView(pd("📂 فتح فيديو") { doOpen() })
        gTool.addView(pd("🕳 سد الفجوات") { engine.retryFailed(); Notice.show(this, ("بحاول أسد الفجوات").toString(), 2300L) })
        // (v87) ترجمات الفيديو: قايمة صغيرة منسدلة بالنسخ المحفوظة (دوسة على نسخة ترجّعها)
        val gVer = gCol()
        fun fillVer() {
            gVer.removeAllViews()
            gVer.addView(pk("‹ رجوع") { dismissPop(); togglePop(menuB, gTool, false) })
            gVer.addView(pd("💾 احفظ النسخة الحالية") { engine.saveVersion("نسخة " + fmtMs(player.currentPosition).substring(3)); Notice.show(this, "اتحفظت نسخة", 1800L) })
            val vs = engine.versions.toList()
            if (vs.isEmpty()) gVer.addView(ui.text("مفيش نسخ محفوظة لسه", 12f, Color.WHITE).apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)) })
            vs.forEachIndexed { i, v -> gVer.addView(pd("${v.name} — ${v.subs.size} جملة") { restoreVersion(i) }) }
        }
        gTool.addView(pk("🗂 ترجمات الفيديو ›") { fillVer(); dismissPop(); togglePop(menuB, gVer, false) })
        gTool.addView(pd("🔄 إعادة ترجمة") { batchDialog() })
        gTool.addView(pd("🎙 دبلجة عربي (تشغيل/إيقاف)") { dubToggle() })   // (v418)
        gTool.addView(pd("🗣 صوت الدبلجة: سلمى ⇄ شاكر") { dubVoiceSwap() })   // (v418)
        gTool.addView(pd("👁 الوضع البصري") { visualDialog() })
        gTool.addView(pd("🌙 ترجمة بالخلفية") { translateInBackground() })
        gTool.addView(pd("📶 استهلاك النت") { netDialog() })
        gTool.addView(pd("📜 ذكّرني") { recapDialog() })
        gTool.addView(pd("😴 مؤقت النوم") { sleepDialog() })   // (v452)
        gTool.addView(pd("📸 لقطة إطار") { snapFrame() })   // (v469) B12
        gTool.addView(pd("📑 فصول الفيديو") { chaptersSheet() })   // (v469) B9
        gTool.addView(pd("⏭ التالي تلقائي: " + if (Cfg.str("autoplay_next", "1") == "1") "شغّال" else "مقفول") { v -> val on = Cfg.str("autoplay_next", "1") != "1"; Cfg.put("autoplay_next", if (on) "1" else "0"); v.text = "⏭ التالي تلقائي: " + (if (on) "شغّال" else "مقفول") })   // (v469) B13
        // (v356) اتشالت من القايمة: ترجمة جوجل بقت اختيار جوه خط الترجمة (الإعدادات ▸ 🚀). الدالة googleTranslateNow() لسه موجودة.
        // gTool.addView(pd("🌍 ترجمة جوجل (من غير API)") { googleTranslateNow() })
        // (v87) الإعدادات: قايمة صغيرة منسدلة بالأقسام — دوسة على قسم تفتحه دايركت (من غير شاشة القايمة الكبيرة)
        val gSet = gCol()
        gSet.addView(pk("‹ رجوع") { dismissPop(); togglePop(menuB, gTool, false) })
        // (v212) نفس أقسام الإعدادات اللي بره بالظبط (SettingsTabs): كانت القايمة هنا بأسماء أقسام مش موجودة بره فكانت بتفتح القايمة الكبيرة
        var lastG = ""
        for (sc in SettingsTabs.sections.filter { it.id != "music" }) {
            if (sc.group != lastG && SettingsTabs.sections.count { it.group == sc.group && it.id != "music" } > 1) gSet.addView(ui.text(sc.group, 11f, 0xFF9AA0A6.toInt(), true).apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(2)) })
            lastG = sc.group
            gSet.addView(pd(sc.label) { openSettings(sc.id) })
        }
        gSet.addView(pd("📥 تحميل النص الأصلي (SRT)") { exportOriginalSrt() })
        // (v167) زرار «تضخيم الصوت» اتشال — التضخيم بقى جزء أحمر في سلايدر الصوت نفسه (بعد 100%)
        // (v135) 🧰 أدوات الفيديو (زرار في الشريط العلوي): قص · صوت · GIF · ضغط · ترجمة ثابتة — كل واحدة بتتحط في توبيب «المهام» وبتتحفظ لوحدها لما تخلص
        val gTools = gCol()
        val toolsUi = ToolsUi(this, ui, th)
        fun withSrc(f: (ToolSrc) -> Unit) { val ts = toolSrc(); if (ts == null) Notice.show(this, "مفيش فيديو شغّال", 2300L) else f(ts) }
        gTools.addView(ui.text("🧰 أدوات الفيديو", 12f, th.primary, true).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(4)) })
        gTools.addView(pd("✂ قص جزء من الفيديو") { withSrc { toolsUi.trim(it) } })
        gTools.addView(pd("🎧 تحويل لصوت") { withSrc { toolsUi.audio(it) } })
        gTools.addView(pd("🎞 عمل GIF") { withSrc { toolsUi.gif(it) } })
        gTools.addView(pd("📦 ضغط / تغيير الدقة") { withSrc { toolsUi.compress(it) } })
        gTools.addView(pd("🎬 ترجمة ثابتة (هارد ساب)") { withSrc { toolsUi.hardsub(it) } })
        gTools.addView(ui.text("النتيجة بتظهر في «📋 صفحة المهام» (زرار ⏳ جنب الترجمة)", 10f, 0xFF9AA0A6.toInt()).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(2)) })
        // (v188) صفحة المهام (التقدم + الناتج) في نافذة فوق المشغّل — الأدوات جواها بتشتغل على الفيديو الشغّال
        fun openTasksDialog() {
            val tu = TasksUi(this, ui, th) { k ->
                withSrc { ts ->
                    when (k) { "trim" -> toolsUi.trim(ts); "audio" -> toolsUi.audio(ts); "gif" -> toolsUi.gif(ts); "compress" -> toolsUi.compress(ts); "hardsub" -> toolsUi.hardsub(ts); else -> {} }
                }
            }
            val dlg = android.app.Dialog(this)
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
            box.addView(ui.button("✕ إغلاق") { dlg.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(8), ui.dp(12), 0) })
            box.addView(tu.root, LinearLayout.LayoutParams(-1, 0, 1f))
            dlg.setContentView(box)
            val l: () -> Unit = { tu.refresh() }
            TaskCenter.extra.add(l)
            dlg.setOnDismissListener { TaskCenter.extra.remove(l) }
            TvFocus.attachDialog(this, dlg, th.primary)   // (v480 م7)
            dlg.show()
            dlg.window?.setLayout(-1, -1)
        }
        val tasksPageB = pd("📋 صفحة المهام") { openTasksDialog() }
        gTools.addView(tasksPageB, 1)
        tasksPopFn = { v -> tasksPageB.text = "📋 صفحة المهام (" + taskCount() + ")"; togglePop(v, gTools, true) }
        val toolsB = IconGlyphButton(this, "toolbox").apply { background = ui.box(0xE0141418.toInt(), 0x1FFFFFFF, 12); setOnClickListener { togglePop(this, gTools, false) } }
        tvMenuFn = { v -> togglePop(v, gTool, true) }; tvToolsFn = { v -> togglePop(v, gTools, true) }   // (v517) ⋯ المزيد على التلفزيون
        // (v151) خيارات متعددة الاختيار + زرار واحد «طبّق المحدد»: بيتنفّذوا ورا بعض (تنقيح ← لهجة+ ← عائلي/صريح ← ضمائر ← دمج مكرر)
        val aiBase = linkedMapOf("refine" to "✍ تنقيح بالسياق", "dial" to "🔥 لهجة +", "family" to "🧹 عائلي", "explicit" to "🔞 صريح", "pron" to "🔧 ضمائر", "dedup" to "🧠 دمج مكرر")
        val aiBtns = HashMap<String, TextView>()
        val aiApplyB = pd("▶ طبّق المحدد (0)") { applyAiOptions() }
        fun paintAi() {
            for ((id, b) in aiBtns) { val on = id in aiSel; b.text = (if (on) "✓ " else "") + aiBase[id]; b.background = ui.box(if (on) 0xFF1F5FBF.toInt() else 0xE0141418.toInt(), 0x1FFFFFFF, 12) }
            aiApplyB.text = "▶ طبّق المحدد (${aiSel.size})"; aiApplyB.alpha = if (aiSel.isEmpty()) 0.45f else 1f
        }
        for ((id, base) in aiBase) {
            val b = pk(base) { _ ->
                if (!aiSel.remove(id)) { aiSel.add(id); if (id == "family") aiSel.remove("explicit"); if (id == "explicit") aiSel.remove("family") }   // عائلي وصريح عكس بعض
                paintAi()
            }
            aiBtns[id] = b; gAi.addView(b)
        }
        gAi.addView(aiApplyB); paintAi()
        // ===== الصف العلوي: الأساسي ظاهر دايمًا (☰ 📝 CC وضع-الترجمة Aa) والباقي بيتفرد بسهم ❮ وبيتلم بعد 5 ثواني =====
        fun tip(v: TextView, name: String): TextView { v.setOnLongClickListener { giShow(name, Gravity.CENTER); true }; return v }   // ضغطة طويلة = اسم الزرار
        // (v96) الزرار ❮ اتشال: كل أزرار الصف العلوي ظاهرة على طول (☰ القائمة + 🔤 النص)
        val tbExtra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fun exAdd(v: View) { tbExtra.addView(v, 0) }
        tbCollapseFn = { }
        menuB.setOnLongClickListener { giShow("القائمة", Gravity.CENTER); true }
        toolsB.setOnLongClickListener { giShow("أدوات الفيديو", Gravity.CENTER); true }
        tbRow.addView(menuB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }); tbAdd(tbExtra)

        val textB = tip(grp("🔤", gText), "النص")
        exAdd(textB)

        fsPlayB = IconTextView(this).apply {
            text = "▶"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); includeFontPadding = false
            setOnClickListener { togglePlay(); showChrome() }
        }
        fsEl = ui.text("0:00", 12f, Color.WHITE); fsDu = ui.text("0:00", 12f, Color.WHITE)
        fsProgV = DualProgress(this, th)
        // زي MX: صف الوقت+التقدم فوق، وتحته: 🔓 قفل (شمال) | ⏮ ⏯ ⏭ (النص) | ⛶ احتواء/تمديد + ⧉ نافذة صغيرة (يمين)
        val fsBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(ui.dp(14), ui.dp(34), ui.dp(14), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x66000000, 0xCC000000.toInt()))   // (v445) تدرّج أنعم
        }
        val timeRow = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        timeRow.addView(fsEl, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        // (v469) B9: غلاف حوالين شريط التقدم الكبير وجواه علامات الفصول فوق الشريط بالظبط
        val progWrap = FrameLayout(this)
        progWrap.addView(fsProgV, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        val chapTicks = ChapTicks(); chapTicksRef = chapTicks
        progWrap.addView(chapTicks, FrameLayout.LayoutParams(-1, -1))
        timeRow.addView(progWrap, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(fsDu, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        // صف تحت (فوق شريط الوقت، فوق القفل): A− A+ | ⏱ توقيت | ترجمة (إيقاف/استئناف + اللغة). بيلفّ لسطر تاني لو الشاشة ضيقة
        // الصف ده بيبدأ من اليمين: شريحة الترجمة بس (حجم الترجمة والتوقيت اتنقلوا لزرار Aa فوق)
        val auxRow = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        auxRow.addView(makeTrRow(true))
        val lgIcon = trLgB   // (v224) هيتحطوا جنب 🗣 في الشريط السفلي
        fsBar.addView(auxRow, LinearLayout.LayoutParams(-1, -2))
        fsBar.addView(timeRow, LinearLayout.LayoutParams(-1, ui.dp(30)))
        // صف الأدوات الصغيرة (فوق أزرار الشاشة) على اليمين: Aa (حجم/توقيت) · 📋 اللوج · 📝 الجمل · CC · 💬 وضع الترجمة
        val toolRow = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val toolR = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        fun barPill(b: TextView): TextView = b.apply { layoutParams = LinearLayout.LayoutParams(-2, ui.dp(30)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) }; minimumWidth = ui.dp(30); setPadding(ui.dp(8), 0, ui.dp(8), 0); textSize = 12f }
        val aiB = barPill(tip(grp("🗣", gAi, true), "لهجة (عائلي/صريح)"))
        trAiB = aiB; applyModeCaps()
        val syncB = barPill(tip(grp("⏱", gSub, true), "تزامن الترجمة (تقديم / تأخير)"))   // (v91) مكان Aa — حجم الخط بقى بالقرص (pinch) على الشاشة
        toolR.addView(syncB)
        rcViews["dual"] = dualB; rcViews["bgon"] = bgOnB; rcViews["ai"] = aiB   // (v531) ريموت الموبايل
        val toolBtns = listOf<TextView>(syncB, tip(logB, "اللوج"), tip(sentB, "الجمل"), tip(ccB, "إظهار/إخفاء الترجمة"), tip(loopB, "تكرار الفيديو"), tip(dualB, "وضع الترجمة"), tip(charsB, "الشخصيات"), tip(voiceB, "فصل صوت الكلام عن الموسيقى"), qualB, nextListB)
        toolBtns.drop(1).filter { it !== voiceB && it !== qualB && it !== nextListB }.forEach { b ->   // (v226) 🔑 اتشال من الشريط العلوي
            toolR.addView(b, LinearLayout.LayoutParams(if (b.text.startsWith("🔑")) ui.dp(42) else ui.dp(30), ui.dp(30)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        toolRow.addView(toolR, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        fsBar.addView(toolRow, LinearLayout.LayoutParams(-1, ui.dp(36)))
        // (v90) شمال: 🔓 ✨ 🧰 جنب بعض · النص: ⏮ ⏯ ⏭ كبار وموزّعين على عرض الشريط · يمين: 🔄(لاندسكيب/بورتريت) ⧉ ⛶ (⛶ على الحافة)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        // (v96) ثلاث أعمدة: شمال (وزن 1) | نص (⏮ ▶ ⏭ متسنطرين فعلًا على عرض الشاشة) | يمين (وزن 1) — فالنص دايمًا في نص الشريط بالظبط
        val leftB = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        val lockB = ui.fsCircle("🔓") { setLock(true) }
        val lockLp = { LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(4) } }
        fun smallIcon(v: TextView?) {
            if (v == null) return
            (v.parent as? ViewGroup)?.removeView(v); v.textSize = 15f; v.setPadding(0, 0, 0, 0)
            leftB.addView(v, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { marginStart = ui.dp(3) })
        }
        leftB.addView(lockB, lockLp()); leftB.addView(aiB); smallIcon(lgIcon)
        btnRow.addView(leftB, LinearLayout.LayoutParams(0, -2, 1f))
        val pillBg = { ui.box(0xE00F0F12.toInt(), 0x29FFFFFF, 24) }
        val prevB = ui.fsCircle("⏮") { stepOrSeek(-1); showChrome() }.apply { textSize = 20f; background = pillBg() }
        val nextB = ui.fsCircle("⏭") { stepOrSeek(1); showChrome() }.apply { textSize = 20f; background = pillBg() }
        fsPlayB.textSize = 30f
        fsPlayB.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE00F0F12.toInt()); setStroke(ui.dp(1), 0x29FFFFFF) }
        val mid = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER; setPadding(ui.dp(6), 0, ui.dp(6), 0) }
        mid.addView(prevB, LinearLayout.LayoutParams(ui.dp(60), ui.dp(44)).apply { marginEnd = ui.dp(8) })
        mid.addView(fsPlayB, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        mid.addView(nextB, LinearLayout.LayoutParams(ui.dp(60), ui.dp(44)).apply { marginStart = ui.dp(8) })
        btnRow.addView(mid, LinearLayout.LayoutParams(-2, -2))
        val rightB = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        fsBarFs = ui.fsCircle("⛶") { cycleFitNow(); showChrome() }
        val rotBarB = ui.fsCircle(Icons.ROT) { toggleFs(); showChrome() }.apply { textSize = 19f }   // لاندسكيب/بورتريت (أيقونة الروتيشن زي MX)
        val pipBarB = ui.fsCircle("⧉") { enterPip() }
        (noticeBtn.parent as? ViewGroup)?.removeView(noticeBtn)
        rightB.addView(noticeBtn, LinearLayout.LayoutParams(-2, ui.dp(34)).apply { marginEnd = ui.dp(8) })
        rightB.addView(rotBarB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(8) })
        rightB.addView(pipBarB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        rightB.addView(fsBarFs, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginStart = ui.dp(8) })
        btnRow.addView(rightB, LinearLayout.LayoutParams(0, -2, 1f))
        fsBar.addView(btnRow, LinearLayout.LayoutParams(-1, ui.dp(72)))
        // (v91) في العرض الواسع (لاندسكيب): ⏱ 📋 📝 CC 💬 بتتنقل للصف السفلي جنب 🔄؛ في الضيق (بورتريت) بتفضل في صفها
        var toolsWide = false
        fun placeTools(wide: Boolean) {
            if (toolsWide == wide) return; toolsWide = wide
            // (v101) كل الأزرار قليلة الاستعمال (☰ 🔤 ⏱ 💬 CC 📋 📝 🔁) في الشريط العلوي جنب عداد الجمل، مفرودة بطوله — والشريط السفلي فيه 🔓 ✨ + ⏮ ▶ ⏭ + أزرار الشاشة بس.
            // 🧰 اتشال: كل اللي فيه موجود في ☰ القائمة / عداد الجمل ▾ / شريحة الترجمة 🔁
            toolBtns.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            (menuB.parent as? ViewGroup)?.removeView(menuB); (textB.parent as? ViewGroup)?.removeView(textB)
            leftB.removeAllViews(); rightB.removeAllViews(); tbRow.removeAllViews()
            // toolBtns = [⏱ توقيت, 📋 لوج, 📝 جمل, CC, 🔁 تكرار, 💬 وضع الترجمة, 🧑 شخصيات]
            fun lp(i: Int) = LinearLayout.LayoutParams(if (i == 0) -2 else ui.dp(38), ui.dp(38)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
            leftB.addView(lockB, lockLp()); leftB.addView(aiB); smallIcon(lgIcon)
            (noticeBtn.parent as? ViewGroup)?.removeView(noticeBtn)
            rightB.addView(noticeBtn, LinearLayout.LayoutParams(-2, ui.dp(34)).apply { marginEnd = ui.dp(8) })
            rightB.addView(rotBarB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(8) })
            rightB.addView(pipBarB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            rightB.addView(fsBarFs, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginStart = ui.dp(8) })
            // من الشمال لليمين (LTR): 🔁 📋 📝 🔤 ⏱ 💬 CC ☰  ← يعني ☰ على اليمين جنب عداد الجمل
            tbRow.addView(toolBtns[4], lp(4)); tbRow.addView(toolBtns[1], lp(1)); tbRow.addView(toolBtns[2], lp(2)); tbRow.addView(toolBtns[6], lp(6))
            tbRow.addView(textB, LinearLayout.LayoutParams(-2, ui.dp(38)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            tbRow.addView(toolBtns[0], lp(0)); tbRow.addView(toolBtns[5], lp(5)); tbRow.addView(toolBtns[3], lp(3)); // (v378) 🎙 فصل الصوت و HD و 📃 اتشالوا من الشريط العلوي (الجودة رجعت جنب زرار الترجمة، والتالي بقى لوحة تحت الأزرار)
            // (v221) زرار 🔑 وضع المفاتيح (toolBtns[7]) كان بيتشال هنا ومابيترجّعش — فمكانش بيظهر خالص في الشريط العلوي
            tbRow.addView(bgOnB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            (toolsB.parent as? ViewGroup)?.removeView(toolsB)
            tbRow.addView(toolsB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            tbRow.addView(menuB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            textB.minimumWidth = ui.dp(38); textB.setPadding(ui.dp(8), 0, ui.dp(8), 0)
            textAtBottom = false
            tb.visibility = View.VISIBLE
            toolRow.visibility = View.GONE
        }
        // (v101) الزراير اللي على جنبين ⏮ ⏭ كتير: لو مكانها ضيّق بنصغّرها (حجم + مسافات + خط) بدل ما آخر زرار يتاكل
        placeTools(true)
        val sideOrig = HashMap<View, FloatArray>()
        var fitW = -1
        fun scaleSide(g: LinearLayout, avail: Int) {
            val kids = (0 until g.childCount).map { g.getChildAt(it) }
            if (kids.isEmpty()) return
            var need = 0f
            for (c in kids) {
                val lp = c.layoutParams as LinearLayout.LayoutParams
                if (!sideOrig.containsKey(c)) {
                    val ts = (c as? TextView)?.textSize ?: 0f
                    val nat = if (lp.width > 0) lp.width.toFloat() else { c.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); c.measuredWidth.toFloat() }
                    sideOrig[c] = floatArrayOf(lp.width.toFloat(), lp.height.toFloat(), lp.leftMargin.toFloat(), lp.rightMargin.toFloat(), ts, c.paddingLeft.toFloat(), c.paddingRight.toFloat(), nat, c.minimumWidth.toFloat())
                }
                val o = sideOrig[c]!!; need += o[7] + o[2] + o[3]
            }
            val f = if (need <= 0f) 1f else (avail / need).coerceIn(0.58f, 1f)
            for (c in kids) {
                val o = sideOrig[c] ?: continue; val lp = c.layoutParams as LinearLayout.LayoutParams
                lp.width = if (o[0] > 0) (o[0] * f).toInt() else o[0].toInt()
                lp.height = if (o[1] > 0) (o[1] * f).toInt() else o[1].toInt()
                lp.setMargins((o[2] * f).toInt(), lp.topMargin, (o[3] * f).toInt(), lp.bottomMargin)
                (c as? TextView)?.let { tv -> if (o[4] > 0) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o[4] * (0.5f + 0.5f * f)) }
                c.minimumWidth = (o[8] * f).toInt()
                c.setPadding((o[5] * f).toInt(), c.paddingTop, (o[6] * f).toInt(), c.paddingBottom)
                c.layoutParams = lp
            }
        }
        fun fitSides(w: Int) {
            if (w == fitW) return; fitW = w
            val mf = if (w >= ui.dp(720)) 1f else if (w >= ui.dp(480)) 0.86f else 0.76f
            (prevB.layoutParams as LinearLayout.LayoutParams).apply { width = (ui.dp(60) * mf).toInt(); height = (ui.dp(44) * mf).toInt(); marginEnd = (ui.dp(8) * mf).toInt() }.also { prevB.layoutParams = it }
            (nextB.layoutParams as LinearLayout.LayoutParams).apply { width = (ui.dp(60) * mf).toInt(); height = (ui.dp(44) * mf).toInt(); marginStart = (ui.dp(8) * mf).toInt() }.also { nextB.layoutParams = it }
            (fsPlayB.layoutParams as LinearLayout.LayoutParams).apply { width = (ui.dp(56) * mf).toInt(); height = (ui.dp(56) * mf).toInt() }.also { fsPlayB.layoutParams = it }
            val midW = (ui.dp(60) * 2 + ui.dp(8) * 2 + ui.dp(56)) * mf + ui.dp(12)
            val avail = ((w - ui.dp(28) - midW) / 2f - ui.dp(2)).toInt().coerceAtLeast(ui.dp(60))
            scaleSide(leftB, avail); scaleSide(rightB, avail)
        }
        fsBar.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> val w = r - l; if (w > 0) fsBar.post { placeTools(true); fitSides(w) } }
        fsBar.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot && chromeShown) applyChromeFn() }   // ارتفاع الشريط اتغيّر: ارفع الترجمة فوقه
        val chromeFrame = FrameLayout(this).apply { visibility = View.GONE; tag = "chromeFrame" }
        // في الرأسي الصفوف بتلفّ لسطر جديد (FlowRow) — من غير HorizontalScrollView لأنه بيدّي عرض لا نهائي فالصف عمره ما بيلفّ
        // (v330) الصف الفوق: اسم الفيديو (شمال) + كبسولات العدّ (يمين)؛ أزرار المشغّل نزلت درجة تحتهم عشان ما تغطّيش الكبسولات
        topTitle = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(5f, 0f, 1f, Color.BLACK)
            setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); background = ui.box(0x73000000, 0x1FFFFFFF, 12); visibility = View.GONE
        }
        chromeFrame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000)); isClickable = false; isFocusable = false },
            FrameLayout.LayoutParams(-1, ui.dp(120), Gravity.TOP))   // (v445) ظل علوي ناعم يطلّع الأزرار والعنوان
        chromeFrame.addView(topTitle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply { setMargins(ui.dp(12), ui.dp(10), ui.dp(190), 0) })
        chromeFrame.addView(tb, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply { setMargins(ui.dp(8), ui.dp(50), ui.dp(8), 0) }); tbView = tb
        fixTopRowFn = fn@{
            val bw = if (::badgeRowV.isInitialized && badgeRowV.visibility == View.VISIBLE) badgeRowV.width else 0
            val bh = if (::badgeRowV.isInitialized && badgeRowV.visibility == View.VISIBLE) badgeRowV.height else 0
            val tl = topTitle.layoutParams as FrameLayout.LayoutParams
            val rm = bw + ui.dp(24)
            if (tl.rightMargin != rm) { tl.rightMargin = rm; topTitle.layoutParams = tl }
            val tp = (ui.dp(8) + bh + ui.dp(6)).coerceAtLeast(ui.dp(46))
            val tbl = tb.layoutParams as FrameLayout.LayoutParams
            if (tbl.topMargin != tp) { tbl.topMargin = tp; tb.layoutParams = tbl; placeFloatFn() }
        }
        if (::badgeRowV.isInitialized) badgeRowV.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob -> if (r - l != orr - ol || b - t != ob - ot) fixTopRowFn() }   // (v265) في ملء الشاشة الهامش بيكبر عشان الشريط مايغطّيش الكبسولات   // (v90) يمين، جنب عداد الجمل
        chromeFrame.addView(fsBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(0, 0, 0, ui.dp(6)) })
        // زرار القفل الصغير: بيظهر لما تلمس الشاشة وهي مقفولة
        lockOv = ui.fsCircle("🔒") { setLock(false) }.apply { visibility = View.GONE }
        videoBox.addView(lockOv, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(22), 0, 0, ui.dp(22)) })
        videoBox.addView(chromeFrame, FrameLayout.LayoutParams(-1, -1))
        // (v362) لودينج كبير في نص الفيديو (لودينج ويندوز + عدّاد ثواني) وقت جلب رابط يوتيوب وأول تحميل — كان بس على زرار التشغيل الصغير ومخفي لما الشريط ظاهر
        ytBusyV = FrameLayout(this).apply {
            visibility = View.GONE; isClickable = false; isFocusable = false
            val colB = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            colB.addView(View(this@PlayerActivity).apply { foreground = ytBusyDr }, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)))
            ytBusyTv = TextView(this@PlayerActivity).apply { textSize = 13f; setTextColor(Color.WHITE); setShadowLayer(5f, 0f, 1f, Color.BLACK); gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
            colB.addView(ytBusyTv, LinearLayout.LayoutParams(-2, -2))
            addView(colB, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        videoBox.addView(ytBusyV, FrameLayout.LayoutParams(-1, -1))
        intent.getStringExtra("ytid")?.let { yid ->   // (v378)
            val fsLp = fsBar.layoutParams as FrameLayout.LayoutParams
            nextPanel = NextPanel(this, ui, th, videoBox, yid, { n -> playNextYt(n) },
                { px -> fsLp.bottomMargin = ui.dp(6) + px; fsBar.layoutParams = fsLp },
                { on -> if (on) { h.removeCallbacks(hideChrome); chromeShown = true; applyChromeFn() } else showChrome() },
                { val a = IntArray(2); val b = IntArray(2); fsProgV.getLocationOnScreen(a); videoBox.getLocationOnScreen(b); (a[1] - b[1]) + fsProgV.height })
        }
        // (v104) شرايط التقدم الـ3 بحجم رفيع جدًا دايمًا تحت الفيديو؛ لمسة على الشاشة بتظهر الشريط الكبير ولمسة تانية ترجّعها صغيرة
        miniBar = DualProgress(this, th).apply { thin = true; isClickable = false; isFocusable = false; alpha = 0.33f }
        videoBox.addView(miniBar, FrameLayout.LayoutParams(-1, 3, Gravity.BOTTOM))
        videoBox.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) { val hh = ((b - t) * 0.025f).toInt().coerceAtLeast(3); val lp = miniBar.layoutParams; if (lp.height != hh) { lp.height = hh; miniBar.layoutParams = lp } }
        }
        // زرار ⛶ (.fullscreen-btn): أسفل يسار الفيديو 10dp في الرأسي، 18dp في الشاشة الكاملة
        fsBtnV = ui.fsCircle("⛶") { toggleFs(); showChrome() }
        fsBtnLp = FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.BOTTOM or Gravity.LEFT)
        videoBox.addView(fsBtnV, fsBtnLp)
        // (v95) مكان 👁 والروتيشن: في النص بين الشريط العلوي والشريط السفلي (مش فوق شريحة الترجمة أبدًا)
        var modePillMoving = false
        placeFloatFn = fn@{
            val bh = videoBox.height; if (bh <= 0) return@fn
            val chromeOn = fullMode && chromeShown && !pipNow()
            val eyeH = ui.dp(44); val rotH = ui.dp(48); val gap = ui.dp(10)
            val zTop = ui.dp(84) + (if (chromeOn) ui.dp(48) else 0)   // (v97) تحت عداد الجمل خالص (v330) + الأزرار نزلت درجة
            val zBot = bh - (if (chromeOn) fsBar.height + ui.dp(16) else ui.dp(60))
            val room = zBot - zTop
            val both = rotH + gap + eyeH
            val showRot = false   // (v97) مفيش روتيشن عايم
            val stackH = if (showRot) both else eyeH
            val top = (if (chromeOn) zTop + (room - stackH) / 2 else zTop).coerceAtMost(zBot - stackH).coerceAtLeast(ui.dp(2))
            val rotLp = rotBtn.layoutParams as FrameLayout.LayoutParams
            val eyeLp = floatBar.layoutParams as FrameLayout.LayoutParams
            val eyeTop = if (showRot) top + rotH + gap else top
            if (rotLp.topMargin != top) { rotLp.topMargin = top; rotBtn.layoutParams = rotLp }
            if (eyeLp.topMargin != eyeTop) { eyeLp.topMargin = eyeTop; floatBar.layoutParams = eyeLp }
            if (::audBtn.isInitialized) { val al = audBtn.layoutParams as FrameLayout.LayoutParams; val at = eyeTop + ui.dp(52); if (al.topMargin != at) { al.topMargin = at; audBtn.layoutParams = al } }
            if (::modePill.isInitialized) {   // (v467) الكبسولة انتقلت لصف الكبسولات جنب 👁 (طلب صاحب التطبيق: جنب زرار الوضع البصري مش تحته) — فلا حساب مواضع ولا تغطية من زرار الترجمة بعد كده
                val br = badgeRowRef
                // (v471) كان بيتنقّل هنا مباشرة وده بيتنادى من مستمعات onLayoutChange → تعديل شجرة الواجهة جوه مرحلة الـ layout = NullPointerException في FrameLayout.layoutChildren (فورس كلوز بعد ثواني من التشغيل). دلوقتي بيتأجّل بـ post بعد ما الـ layout يخلص
                if (br != null && modePill.parent !== br && !modePillMoving) {
                    modePillMoving = true
                    modePill.post {
                        modePillMoving = false
                        try { val b2 = badgeRowRef; if (b2 != null && modePill.parent !== b2) { (modePill.parent as? ViewGroup)?.removeView(modePill); b2.addView(modePill, LinearLayout.LayoutParams(-2, ui.dp(34)).apply { marginStart = ui.dp(6) }) } } catch (e: Throwable) { LogStore.err("Player:modePill", e) }
                    }
                }
            }
            val rv = if (showRot) View.VISIBLE else View.GONE
            if (rotBtn.visibility != rv) rotBtn.visibility = rv
        }
        videoBox.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) placeFloatFn() }
        fsBar.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) placeFloatFn() }
        applyChromeFn = {
            val on = fullMode && chromeShown && !pipNow() && !DeviceUi.isRemote(this)   // (v524) تلفزيون: طبقة التحكم القديمة (اللمس) مشالة، شريط التلفزيون بس
            try { nextPanel?.onChrome(on) } catch (_: Throwable) {}
            val wasOn = chromeFrame.visibility == View.VISIBLE
            if (on) { chromeFrame.visibility = View.VISIBLE; if (wasOn && chromeFrame.alpha < 1f) { chromeFrame.animate().cancel(); chromeFrame.alpha = 1f; chromeFrame.translationY = 0f } }
            else {
                dismissPop(); tbCollapseFn()
                if (wasOn) {   // (v445) اختفاء ناعم (فيد + نزول خفيف) بدل القطع المفاجئ
                    chromeFrame.animate().cancel()
                    chromeFrame.animate().setStartDelay(0L).alpha(0f).translationY(ui.dp(10).toFloat()).setDuration(Skins.dur(160L))
                        .withEndAction { if (!(fullMode && chromeShown && !pipNow())) chromeFrame.visibility = View.GONE; chromeFrame.alpha = 1f; chromeFrame.translationY = 0f }.start()
                } else chromeFrame.visibility = View.GONE
            }
            if (on) { try { val tt = titleWithSummary(); if (topTitle.text.toString() != tt.toString()) topTitle.text = tt; topTitle.visibility = if (tt.isBlank()) View.GONE else View.VISIBLE; fixTopRowFn() } catch (_: Throwable) {} }   // (v330) (v360) + ملخص الإعداد
            if (on && !wasOn) { chromeFrame.animate().cancel(); chromeFrame.alpha = 0f; chromeFrame.translationY = ui.dp(22).toFloat(); chromeFrame.animate().setStartDelay(0L).alpha(1f).translationY(0f).setDuration(Skins.dur(280L)).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start() }
            else if (!on && !wasOn) { chromeFrame.animate().cancel(); chromeFrame.alpha = 1f; chromeFrame.translationY = 0f }
            centerPlay.visibility = if (on || pipNow() || uiLocked || busyShown()) View.GONE else if (buffering) View.VISIBLE else if (player.isPlaying) View.GONE else View.VISIBLE   // الشريط ظاهر = فيه زرار تشغيل تحت، فمنشيلش الأوسط فوق الترجمة
            val cv = (!fullMode || chromeShown) && !pipNow()
            floatBar.visibility = if (cv && caps().visual) View.VISIBLE else View.GONE   // (v444) البصري في وضع صوت←جيميناي بس · (v427) 👁 مابقاش ظاهر طول الوقت: بيظهر مع الشريط (أو في الوضع العادي)
            batchBtn.visibility = if (cv) View.VISIBLE else View.GONE
            if (::audBtn.isInitialized) audBtn.visibility = if (cv && intent.getStringExtra("ytid")?.length == 11) View.VISIBLE else View.GONE
            if (::modePill.isInitialized) modePill.visibility = if (cv && intent.getStringExtra("ytid")?.length == 11 && ytAudioUrl() != null) View.VISIBLE else View.GONE   // (v459)
            // (v430) في الشاشة الكاملة 👁 (وجنبه 🔄/🎧) مخفي 100% وبيظهر بس مع باقي أدوات المشغّل (لمسة) بوضوح كامل مش باهت
            if (fullMode) {
                floatBar.animate().cancel(); batchBtn.animate().cancel(); floatFades.remove(floatBar)?.let { h.removeCallbacks(it) }
                val ta = if (on) 1f else 0f
                floatBar.alpha = ta; batchBtn.alpha = ta
                if (::audBtn.isInitialized) { audBtn.animate().cancel(); audBtn.alpha = ta }
                if (::modePill.isInitialized) { modePill.animate().cancel(); modePill.alpha = 1f }   // (v467) الكبسولة جوّه صف الكبسولات: شفافيتها من الصف نفسه
            }
            if (!fullMode && ::modePill.isInitialized && modePill.visibility == View.VISIBLE && modePill.alpha < 0.9f) modePill.alpha = 1f   // (v467) ألفا الكبسولة من صفّها
            miniBar.visibility = if (!on && !pipNow()) View.VISIBLE else View.GONE
            updateTrChip(); applyBadgeAlpha()   // (v323) العدّادات بتظهر مع أزرار المشغّل بس
            fsBtnV.visibility = if (!fullMode) View.VISIBLE else View.GONE   // في الشاشة الكاملة ⛶ جوه الشريط السفلي
            subLp.bottomMargin = (if (on) maxOf(ui.dp(128), fsBar.height + ui.dp(12)) else ui.dp(12)) + subPosExtra(); sub.requestLayout()   // (v469) + رفع الاستوديو
            placeFloatFn()
        }

        // ---- لمس الفيديو: لمسة = إظهار/إخفاء الشريط (أو تشغيل/إيقاف في الوضع الرأسي)، لمستين = ±10ث، سحب رأسي = صوت (يمين) / سطوع (شمال) ----
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var volF = (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0).coerceIn(0, 100).let { b -> if (b > 0) 1f + b / 100f else am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }   // (v167) 0..1 صوت النظام، 1..2 تضخيم
        var briF = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
        var scrollLogged = false
        var hScrub = false; var scrubBase = 0L; var scrubTarget = 0L; var fast2x = false
        // ---- تحريك الفيديو لحظيًا مع الصباع (سحب أفقي على الفيديو أو سحب شريط التقدم) ----
        var lastScrub = 0L
        fun fastSeek(on: Boolean) { try { player.exo()?.setSeekParameters(if (on) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT) } catch (e: Exception) { LogStore.err("Main:1623", e) } }
        fun scrubTo(posMs: Long) { val now = System.currentTimeMillis(); if (now - lastScrub >= 90) { lastScrub = now; player.seekTo(posMs) } }
        fun deltaTxt(ms: Long) = (if (ms >= 0) "+" else "−") + PlayerLogic.clock(Math.abs(ms))
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { scrollLogged = false; hScrub = false; scrubBase = player.currentPosition; dismissPop(); dismissStrip(); return true }
            override fun onLongPress(e: MotionEvent) {
                if (hScrub || fast2x) return
                fast2x = true; try { player.setPlaybackSpeed(2f) } catch (e: Exception) { LogStore.err("Main:1630", e) }
                log("👆 ضغطة مطولة: 2×")
                giShow("2× ⏩", Gravity.CENTER)
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // اللمس على الشاشة ما بيشغّلش ولا بيوقّف الفيديو — التشغيل/الإيقاف من زرار التشغيل بس
                if (fullMode) {
                    if (chromeShown) { log("👆 ضغطة: إخفاء الشريط"); h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn() }
                    else { log("👆 ضغطة: إظهار الشريط"); showChrome() }
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val right = e.x > videoBox.width / 2f
                log(if (right) "👆👆 ضغطتين يمين: +10ث" else "👆👆 ضغطتين شمال: -10ث")
                player.seekTo((player.currentPosition + (if (right) 10000 else -10000)).coerceAtLeast(0))
                giShow(if (right) "10 ⏩" else "⏪ 10", (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL)
                // (v469) نبضة أنيميشن على مؤشر التقديم/الترجيع (تكبير لحظي ورجوع) عشان الإيماءة تبقى محسوسة بصريًا
                try { gi.animate().cancel(); gi.scaleX = 0.7f; gi.scaleY = 0.7f; gi.animate().scaleX(1.15f).scaleY(1.15f).setDuration(130).withEndAction { try { gi.animate().scaleX(1f).scaleY(1f).setDuration(160).start() } catch (_: Throwable) {} }.start() } catch (_: Throwable) {}
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (fast2x) return true
                if (e1 == null) return false
                // سحب أفقي على الفيديو: الفيديو بيتحرك معاك وانت ماسك (عرض الشاشة كله ≈ 3 دقايق)، ويثبت لما ترفع صباعك
                if (hScrub || !scrollLogged && Math.abs(e2.x - e1.x) > ui.dp(16) && Math.abs(e2.x - e1.x) > Math.abs(e2.y - e1.y) * 1.2f && durMs > 0) {
                    if (!hScrub) { hScrub = true; scrubBase = player.currentPosition; fastSeek(true); h.removeCallbacks(hideChrome); log("↔ سحب أفقي: تقديم/ترجيع") }
                    val span = minOf(durMs, 180_000L)
                    scrubTarget = (scrubBase + (e2.x - e1.x) / videoBox.width.coerceAtLeast(1) * span).toLong().coerceIn(0L, maxOf(0L, durMs))
                    scrubTo(scrubTarget)
                    giShow(PlayerLogic.clock(scrubTarget) + "  (" + deltaTxt(scrubTarget - scrubBase) + ")", Gravity.CENTER)
                    return true
                }
                if (Math.abs(dy) < Math.abs(dx)) return false
                // (v340) سحب لفوق من حرف الفيديو التحتاني = لوحة «التالي» (فيديوهات القايمة / المقترحة) زي يوتيوب
                if (!scrollLogged && !incognito && intent.getStringExtra("ytid") != null && e1.y > videoBox.height * 0.78f && e1.y - e2.y > ui.dp(56)) {
                    scrollLogged = true; log("↕ سحب لفوق: التالي"); openNextSheet(); return true
                }
                val d = dy / videoBox.height.coerceAtLeast(1) * 1.3f
                if (!scrollLogged) { scrollLogged = true; log(if (e1.x > videoBox.width / 2f) "↕ سحب: الصوت" else "↕ سحب: السطوع") }
                if (e1.x > videoBox.width / 2f && remote) {   // (v568) سحب الصوت في وضع المتحكم = صوت التلفزيون
                    volF = (volF + d).coerceIn(0f, 2f)
                    rmSendVolume(Math.round(volF * 100)); ctl.vol.progress = Math.round(volF * 100); indShow(volInd, volF)
                } else if (e1.x > videoBox.width / 2f) {
                    volF = (volF + d).coerceIn(0f, 2f)
                    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(minOf(volF, 1f) * mx), 0)
                    val bNow = Math.round((volF - 1f).coerceAtLeast(0f) * 100)
                    if (bNow != (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0)) setBoostPct(bNow)
                    if (bNow > 0) player.volume = 1f
                    ctl.vol.progress = Math.round(volF * 100)
                    indShow(volInd, volF)
                } else {
                    briF = (briF + d).coerceIn(0.02f, 1f)
                    val wl = window.attributes; wl.screenBrightness = briF; window.attributes = wl
                    indShow(briInd, briF)
                }
                return true
            }
        })
        // ===== قرص (pinch out/in) على الشاشة = تكبير/تصغير الترجمة (60%–200%) =====
        var pinching = false; var pinchLock = false; var pinchBase = 100; var pinchAcc = 1f
        var pinchVideo = false; var pinchZoomBase = 1f   // (v469) في وضع العرض 100%: القرص بيكبّر سطح الفيديو نفسه بدل حجم الترجمة
        val sgd = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
                if (pipNow() || uiLocked) return false
                pinching = true; pinchLock = true; pinchBase = Cfg.int("sub_scale", 100); pinchAcc = 1f
                pinchVideo = curFit() == 3; pinchZoomBase = vidZoomF
                val t = android.os.SystemClock.uptimeMillis()
                val c = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0f, 0f, 0); gd.onTouchEvent(c); c.recycle()   // ألغي أي إيماءة شغالة (ضغطة مطولة 2× / سحب)
                h.removeCallbacks(hideChrome); log("🤏 قرص: حجم الترجمة")
                return true
            }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                pinchAcc *= d.scaleFactor
                if (pinchVideo) {   // (v469) زوم الفيديو 50%–300% حوالين المركز
                    val z = (pinchZoomBase * pinchAcc).coerceIn(0.5f, 3f)
                    if (z != vidZoomF) { vidZoomF = z; applyFit(sv, videoBox) }
                    giShow("🔍 " + Math.round(z * 100) + "%", Gravity.CENTER)
                    return true
                }
                val n = Math.round(pinchBase * pinchAcc).coerceIn(60, 200)
                if (n != Cfg.int("sub_scale", 100)) { Cfg.put("sub_scale", n.toString()); restyle() }
                giShow("📏 $n%", Gravity.CENTER)
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) { pinching = false }
        })
        // ===== سحب التزامن: دوس على الترجمة واسحب يمين = تتأخر، شمال = تتقدّم (كل ~18dp = 0.1 ث) — بيتحفظ لكل فيديو =====
        var syncCand = false; var syncing = false; var sx = 0f; var syncStart = 0L
        fun hitSub(x: Float, y: Float): Boolean {
            if (pipNow() || !ccOn || sub.visibility != View.VISIBLE || sub.height <= 0) return false
            val slop = ui.dp(28)
            val cx = (sub.left + sub.right) / 2f; val half = maxOf(sub.boxWidthPx() / 2f, ui.dp(60).toFloat()) + slop
            return Math.abs(x - cx) <= half && y >= sub.top - slop && y <= sub.bottom + slop
        }
        gestureLayer.setOnTouchListener { _, ev ->
            if (uiLocked) {
                if (ev.actionMasked == MotionEvent.ACTION_UP && ev.eventTime - ev.downTime < 300) { lockOv.visibility = View.VISIBLE; h.removeCallbacks(hideLockOv); h.postDelayed(hideLockOv, 2500) }
                return@setOnTouchListener true
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (fast2x) { fast2x = false; try { player.setPlaybackSpeed(speed) } catch (e: Exception) { LogStore.err("Main:1711", e) }; gi.visibility = View.GONE }
                if (hScrub) { hScrub = false; fastSeek(false); player.seekTo(scrubTarget); if (ev.actionMasked == MotionEvent.ACTION_UP && !syncing) showChrome() }
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { pinchLock = false; pinching = false }
            sgd.onTouchEvent(ev)
            if (pinchLock || ev.pointerCount > 1) {   // القرص شغال (أو لسه صباع واحد باقي بعده): ما نبعتش اللمس لباقي الإيماءات
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) { pinching = false; pinchLock = false }
                return@setOnTouchListener true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { syncing = false; syncCand = hitSub(ev.x, ev.y); sx = ev.x; syncStart = offsetMs }
                MotionEvent.ACTION_MOVE -> if (syncCand && (syncing || Math.abs(ev.x - sx) > ui.dp(14))) {
                    if (!syncing) {
                        syncing = true; log("↔ سحب على الترجمة: تزامن")
                        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL; gd.onTouchEvent(c); c.recycle()
                        h.removeCallbacks(hideChrome)
                    }
                    val steps = Math.round((ev.x - sx) / ui.dp(18).toFloat())
                    val target = (syncStart + steps * 250L).coerceIn(-10000L, 10000L)   // (v468) نفس خطوة وحد أزرار التزامن
                    if (target != offsetMs) setOff(target - offsetMs)
                    giShow(String.format("⏱ %+.2fs", offsetMs / 1000.0) + (if (offsetMs > syncStart) "  تأخير" else if (offsetMs < syncStart) "  تقديم" else ""), Gravity.CENTER)
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (syncing) { syncing = false; syncCand = false; if (fullMode) showChrome(); return@setOnTouchListener true }
            }
            gd.onTouchEvent(ev); true
        }

        // ---- الكبسولة تحت الفيديو (الوضع الرأسي) ----
        ctl.play.setOnClickListener { togglePlay() }
        ctl.speed.setOnClickListener { cycleSpeed() }
        ctl.cc.setOnClickListener { toggleCc() }
        ctl.exp.setOnClickListener { doExport() }
        ctl.rot.setOnClickListener { toggleFs() }
        ctl.prev.setOnClickListener { val t = player.currentPosition - offsetMs; val i = starts.indexOfLast { it < t - 1500 }; player.seekTo(if (i >= 0) starts[i] + offsetMs else 0L) }
        ctl.next.setOnClickListener { val t = player.currentPosition - offsetMs; val i = starts.indexOfFirst { it > t + 200 }; if (i >= 0) player.seekTo(starts[i] + offsetMs) }
        ctl.vol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                if (!u) return
                if (remote) { rmSendVolume(p); return }   // (v568) السلايدر في وضع المتحكم = صوت التلفزيون
                player.volume = 1f   // (v270) الصوت العادي = صوت النظام
                if (p <= 100) { SysVol.set(this@PlayerActivity, p); if ((Cfg.str("vol_boost", "0").toIntOrNull() ?: 0) != 0) setBoostPct(0) }
                else { SysVol.set(this@PlayerActivity, 100); setBoostPct(p - 100) }
                giShow(if (p <= 100) "🔊 $p%" else "🔊 $p%  تضخيم ${boostDbText(p - 100)}", Gravity.CENTER)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        ctl.vol.max = 200; ctl.vol.progress = volTotal()
        // (v270) زراري الفوليوم أب/داون بيحرّكوا السلايدر (وبيصفّروا التضخيم لو الصوت نزل عن الآخر)
        volWatch?.stop()
        volWatch = if (remote) null else SysVol.watch(this) {
            if (SysVol.get(this) < 100 && (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0) != 0) setBoostPct(0)
            val t = volTotal(); volF = t / 100f
            if (ctl.vol.progress != t) ctl.vol.progress = t
        }
        fun seekFrac(f: Float) { if (durMs > 0) player.seekTo((f * durMs).toLong()) }
        // سحب شريط التقدم: الفيديو بيتحرك معاك لحظيًا + بيكتب الوقت والفرق، ويثبت لما ترفع صباعك
        var barStart = 0L
        val barDrag: (Boolean) -> Unit = { on -> if (on) { barStart = player.currentPosition; fastSeek(true); h.removeCallbacks(hideChrome) } else fastSeek(false) }
        val barScrub: (Float) -> Unit = { f -> if (durMs > 0) { val t = (f * durMs).toLong(); scrubTo(t); giShow(PlayerLogic.clock(t) + "  (" + deltaTxt(t - barStart) + ")", Gravity.CENTER) } }
        ctl.prog.onDrag = barDrag; ctl.prog.onScrub = barScrub
        fsProgV.onDrag = barDrag; fsProgV.onScrub = barScrub
        ctl.prog.onSeek = { f -> seekFrac(f) }
        fsProgV.onSeek = { f -> seekFrac(f); showChrome() }

        // ---- الجمل واللوجز (نوافذ سفلية بدل اللوحة اللي تحت الفيديو) ----
        logTv = ui.text("", 11f, th.text).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
        logSv = ScrollView(this).apply { addView(logTv); layoutParams = LinearLayout.LayoutParams(-1, -1) }
        adapter = object : BaseAdapter() {
            override fun getCount() = list.size + visList.size
            override fun getItem(i: Int): Any = if (i < list.size) list[i] else visList[i - list.size]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
                if (i >= list.size) {   // (v226) جمل الوضع البصري: شريط أزرق
                    val ve = visList[i - list.size]
                    val vrow = LinearLayout(this@PlayerActivity).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(2), 0, ui.dp(2)) }
                    val vbar = View(this@PlayerActivity).apply { setBackgroundColor(0xFF3B82F6.toInt()) }
                    val vcol = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
                    vcol.addView(ui.text("👁 " + PlayerLogic.clock((ve.start * 1000).toLong()), 10f, th.muted))
                    vcol.addView(ui.text(if (ve.translated.isEmpty()) ve.original else ve.translated, 14f, th.text))
                    if (ve.original.isNotBlank() && ve.original != ve.translated) vcol.addView(ui.text(ve.original, 11f, th.muted))
                    vrow.addView(vbar, LinearLayout.LayoutParams(ui.dp(4), -1)); vrow.addView(vcol, LinearLayout.LayoutParams(0, -2, 1f))
                    return vrow
                }
                val q = list[i]
                val row = LinearLayout(this@PlayerActivity).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(2), 0, ui.dp(2)) }
                val bar = View(this@PlayerActivity).apply { setBackgroundColor(if (q.gender == "female") SubStyle.FEMALE else SubStyle.MALE) }
                val col = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
                col.addView(ui.text(PlayerLogic.clock((q.start * 1000).toLong()), 10f, th.muted))
                col.addView(ui.text(if (q.translated.isEmpty()) q.original else q.withSpeaker(q.translated), 14f, th.text))
                if (q.original.isNotBlank() && q.original != q.translated) col.addView(ui.text(q.original, 11f, th.muted))
                row.addView(bar, LinearLayout.LayoutParams(ui.dp(4), -1)); row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                row.setBackgroundColor(if (i == curIdx) (th.primary and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
                return row
            }
        }
        listView = ListView(this).apply {
            this.adapter = this@PlayerActivity.adapter; divider = android.graphics.drawable.ColorDrawable(th.border); dividerHeight = 1
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            setOnItemClickListener { _, _, i, _ -> if (i < list.size) player.seekTo(starts[i] + offsetMs) else visList.getOrNull(i - list.size)?.let { player.seekTo((it.start * 1000).toLong()) } }
        }
        counters = ui.text("", 10f, th.muted).apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(2)) }
        val copyChip = IconTextView(this).apply {
            text = "📋 نسخ كل الجمل بالتوقيتات"; textSize = 12f; setTextColor(th.text); gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7)); background = ui.box(th.surface, th.border, 8)
            setOnClickListener { copySentences() }
        }
        val sentCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; layoutParams = LinearLayout.LayoutParams(-1, -1) }
        sentCol.addView(copyChip, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(6) })
        // (v469) B8: صف بحث داخل النص (يقفز ويسييك لتالي جملة مطابقة) + مفتاح المتابعة التلقائية للجملة الحالية
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val searchEt = android.widget.EditText(this).apply { hint = "🔎 ابحث في النص…"; setSingleLine(true); textSize = 13f; setTextColor(th.text); setHintTextColor(th.muted); setPadding(ui.dp(10), 0, ui.dp(10), 0); background = ui.box(th.surface, th.border, 8) }
        searchRow.addView(searchEt, LinearLayout.LayoutParams(0, ui.dp(40), 1f))
        searchRow.addView(ui.button("🔎 التالي") { sentSearch(searchEt.text.toString()) }, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { marginStart = ui.dp(6) })
        val followCb = android.widget.CheckBox(this).apply { text = "متابعة تلقائية"; isChecked = true; textSize = 12f; setTextColor(th.text); setOnCheckedChangeListener { _, on -> sentFollow = on } }
        searchRow.addView(followCb, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { marginStart = ui.dp(6) })
        sentCol.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        sentCol.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        sentDlg = ui.sheet(this, "📝 الجمل", listOf<View>(sentCol), true, frac = 0.5f)
        sentDlg.setOnShowListener { updVis(); adapter.notifyDataSetChanged() }
        val logCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; layoutParams = LinearLayout.LayoutParams(-1, -1) }
        fun logChip(t: String, f: () -> Unit) = IconTextView(this).apply {
            text = t; textSize = 12f; setTextColor(th.text); gravity = Gravity.CENTER; minHeight = ui.dp(44); setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7)); background = ui.box(th.surface, th.border, 8)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6); topMargin = ui.dp(4) }; setOnClickListener { f() }
        }
        fun logShow(m: Int) {
            logMode = m; lastLogLen = -1
            logTv.text = when (m) {
                1 -> LogStore.prevText(80000).ifBlank { "مفيش لوج لجلسة سابقة" }
                2 -> LogStore.crashText().ifBlank { "مفيش كراش متسجّل" }
                else -> synchronized(logBuf) { logBuf.toString() }
            }
            logSv.post { if (m == 0) logSv.fullScroll(View.FOCUS_DOWN) else logSv.scrollTo(0, 0) }
        }
        fun logCopy() {
            val body = when (logMode) { 1 -> LogStore.prevText(150000); 2 -> LogStore.crashText(); else -> LogStore.currentText(150000) }
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("log", LogStore.header(this) + "\n\n" + body))
            Notice.show(this, ("📋 اللوج اتنسخ").toString(), 2300L)
        }
        val logRow = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4)) }
        logRow.addView(logChip("📋 نسخ") { logCopy() }); logRow.addView(logChip("الحالي") { logShow(0) })
        logRow.addView(logChip("🕘 الجلسة اللي فاتت") { logShow(1) }); logRow.addView(logChip("💥 آخر كراش") { logShow(2) })
        logCol.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(logRow) }, LinearLayout.LayoutParams(-1, -2))
        (batchTv.parent as? android.view.ViewGroup)?.removeView(batchTv)
        batchTv.setOnClickListener(null); batchTv.isClickable = false; batchTv.setTextIsSelectable(true)
        logCol.addView(MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.14f).toInt()).apply { addView(batchTv, FrameLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(8), 0, ui.dp(8), ui.dp(6)) })
        logSv.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); logCol.addView(logSv)
        logDlg = ui.sheet(this, "📜 اللوجز", listOf<View>(logCol), true, frac = 0.45f)
        logDlg.setOnShowListener { logShow(0) }
        mem = ui.memRow()

        // (v103) القايمة الجانبية اتشالت: ☰ بقت قايمة منسدلة (gTool)
        val extrasCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(2), 0, ui.dp(20)) }
        extrasCol.addView(ctl.root)
        extrasCol.addView(makeTrRow(false), LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0) })
        val pRow = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(ui.button("🔄 إعادة ترجمة") { batchDialog() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
            addView(ui.button("🌙 ترجمة بالخلفية") { translateInBackground() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
        }
        extrasCol.addView(pRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0) })
        extrasCol.addView(counters); extrasCol.addView(mem.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        val scroll = ScrollView(this).apply { addView(extrasCol) }
        extras = scroll
        page.addView(videoBox, LinearLayout.LayoutParams(-1, ui.dp(220)))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val rootFrame = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        rootFrame.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootFrame)
        if (DeviceUi.isRemote(this)) { TvFocus.attach(this, th.primary); tvInitBar(rootFrame); tvSnapChrome(); tvChromeGate() }   // (v477 م4)
        applyFull(true)   // شاشة كاملة دايمًا (أفقي ورأسي) — الأزرار في الشريط العلوي وزرار ☰ القائمة
        // فيديو اتفتح من فولدرات الجهاز: يشتغل لاندسكيب مباشرة زي MX Player
        // الاتجاه الأولي من شكل الفيديو (من المكتبة) — وبعدها بيتصحح لوحده أول ما الأبعاد الحقيقية تظهر (onVideoSizeChanged)
        if (intent.getBooleanExtra("landscape", false) && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        videoBox.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob -> if (r - l != orr - ol || bt - t != ob - ot) applyFit(sv, videoBox) }

        buildPlayer()

        // المحرك + استرجاع التقدم المحفوظ
        freshOnce = intent.getBooleanExtra("fresh", false)
        initEngine()
        if (pendingYt) startYtPend()   // (v351)
        if (Build.VERSION.SDK_INT >= 33) { try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7) } catch (e: Exception) { LogStore.err("Main:1892", e) } }
        PlayerRemote.act = java.lang.ref.WeakReference(this)
        KeepAliveService.start(this)

        var tickErrN = 0
        h.post(object : Runnable {
            override fun run() {
                try {
                cur = player.currentPosition
                val d = player.duration
                if (d > 0) durMs = d
                if (!remote) v469Tick() else remoteWatch()   // (v469) حلقة A-B + عدّاد «التالي» + تمييز الفصل الحالي (v546: في وضع المتحكم التلفزيون هو اللي بيعملها)
                brainTick()   // (v542) نشر الحالة والترجمة للريموت (لو فيه ريموت بيسأل)
                run {
                    val nowP = System.currentTimeMillis()
                    if (nowP - lastPosSave >= 3000 && engineReady && !incognito && !remote && durMs > 0) {
                        lastPosSave = nowP
                        try {
                            if (cur > 1000 && cur < durMs - 4000) Cfg.p.edit().putString("lastpos", vid + "\t" + cur).apply()
                            else if (cur >= durMs - 4000 && (Cfg.p.getString("lastpos", "") ?: "").startsWith(vid + "\t")) Cfg.p.edit().remove("lastpos").apply()
                            saveYtProg()   // (v586)
                        } catch (_: Throwable) {}
                    }
                    if (nowP - lastRecentSave >= 20000 && !remote && player.isPlaying) { lastRecentSave = nowP; Thread { try { saveRecent() } catch (_: Throwable) {} }.apply { isDaemon = true }.start() }
                }
                val frac = if (durMs > 0) (cur.toFloat() / durMs).coerceIn(0f, 1f) else 0f
                val cov = if (durMs > 0) (engine.coveredSec() * 1000.0 / durMs).toFloat().coerceIn(0f, 1f) else 0f
                val pr = if (fullMode) fsProgV else ctl.prog
                if (!pr.dragging) pr.played = frac
                pr.translated = cov
                if (durMs > 0) {
                    val sr = engine.subs; val pn = engine.pendingCount()
                    if (sr !== segSubsRef || pn != segPendN) { segSubsRef = sr; segPendN = pn; segCov = engine.coverageSegs(); segPend = engine.pendingSegs() }
                }
                try { val vc = if (::visual.isInitialized) visual.count else 0; if (vc != segVisN) { segVisN = vc; segVis = if (vc == 0) emptyList() else visual.spans() } } catch (_: Throwable) {}
                val onlineSrc = url != null || uri?.scheme?.startsWith("http") == true
                if (onlineSrc && durMs > 0) { try { addBuf(cur / 1000.0, player.bufferedPosition / 1000.0) } catch (_: Throwable) {} }
                val refSegs = engine.refiningSegs
                pr.durSec = durMs / 1000.0; pr.cov = segCov; pr.pend = segPend; pr.refine = refSegs; pr.vis = segVis; pr.buf = if (onlineSrc) ArrayList(bufSegs) else emptyList()
                pr.invalidate()
                if (miniBar.visibility == View.VISIBLE) { miniBar.played = frac; miniBar.durSec = durMs / 1000.0; miniBar.cov = segCov; miniBar.pend = segPend; miniBar.refine = refSegs; miniBar.vis = segVis; miniBar.buf = pr.buf; miniBar.invalidate() }
                val tEl = PlayerLogic.clock(cur); val tDu = PlayerLogic.clock(durMs)
                if (fullMode) { fsEl.text = tEl; fsDu.text = tDu } else { ctl.tEl.text = tEl; ctl.tDur.text = tDu }
                val now = System.currentTimeMillis()
                if (now - lastNotifAt >= 1000) { lastNotifAt = now; pushNotif() }
                if (dirty && now - lastRefresh > 1000) { dirty = false; lastRefresh = now; refreshList(); curIdx = -2 }
                visNow = (cur - offsetMs) / 1000.0
                visOv.showBoxes(visual.boxesAt(visNow, cur / 1000.0))
                val act = PlayerLogic.activeIndices(spStarts, spEnds, cur, offsetMs, 400L, 1).map { spMap[it] }   // (v158) كل متحدث بيظهر لوحده (الأحدث بس)
                val sact = PlayerLogic.activeIndices(sdStarts, sdEnds, cur, offsetMs, 400L, 2).map { sdMap[it] }
                // مراقبة العرض: لو جملة عدّى وقتها وأنا شغّال عادي ومظهرتش في act → اتسجلت في اللوج بتوقيتها ونصها
                run {
                    val tNow = cur - offsetMs
                    for (ix in act) seenKeys.add(sentKey(list[ix]))
                    val pv = lastChkT
                    if (player.isPlaying && pv >= 0 && tNow - pv in 1L..1500L) {
                        for (q in list.indices) {
                            if (list[q].isSound || ends[q] <= pv || ends[q] > tNow || ends[q] - starts[q] < 600L) continue
                            val k = sentKey(list[q])
                            if (k !in seenKeys && warnedKeys.add(k)) LogStore.add("⚠ جملة #${q + 1} عدّى وقتها ومظهرتش في المشغّل (${fmtMs(starts[q] + offsetMs)} → ${fmtMs(ends[q] + offsetMs)}): ${list[q].translated.take(60)} | ظاهر وقتها: ${act.map { it + 1 }}")
                        }
                    }
                    lastChkT = tNow
                    if (seenKeys.size > 6000) seenKeys.clear()
                    if (warnedKeys.size > 2000) warnedKeys.clear()
                }
                val idx = act.lastOrNull() ?: -1
                val gs = PlayerLogic.orderSpeakers(act.map { list[it] })
                // جملة طويلة واحدة: بتتقسم لأجزاء بتظهر بالتتابع على مدة الجملة (التوقيت الأصلي ثابت)
                var parts: List<String> = emptyList(); var part = 0
                val ssn = sub.style
                val nat = ccOn && (gs.isEmpty() || preferNative) && nativeCue.isNotEmpty()   // (v515) (v521) preferNative = المستخدم اختار مسار الفيديو الأصلي ترجمة الفيديو الأصلية لما مفيش ترجمة من المحرك
                if (nat != natShown) { natShown = nat; try { sub.style = natStyle(SubStyle.load { k, d -> Cfg.str(k, d) }) } catch (e: Throwable) { LogStore.err("Player:natStyle", e) } }
                val key = act.joinToString(",") + ":" + part + (if (nat) "|N:" + nativeCue + (if (sfLive.isNotEmpty()) "|L" + sfLiveGen else "") else "")
                if (idx != curIdx || key != curKey) {
                    curIdx = idx; curKey = key
                    val shown: Sub? = when {
                        !ccOn -> null
                        gs.isEmpty() || preferNative -> if (nat) natSub(nativeCue) else null
                        parts.size > 1 -> gs[0].copy(translated = parts[part], isContinuation = gs[0].isContinuation && part == parts.size - 1)
                        else -> PlayerLogic.combine(gs)
                    }
                    sub.show(shown, gs)
                    if (pipNow()) pipOv?.set(if (shown == null) "" else sub.style.mainText(shown))
                    if (sentDlg.isShowing) { updVis(); adapter.notifyDataSetChanged() }
                    if (idx >= 0 && sentDlg.isShowing && !listView.isPressed && sentFollow) listView.smoothScrollToPositionFromTop(idx, ui.dp(30))   // (v469) المتابعة بقت اختيارية
                }
                // حارس العرض: جملة المفروض ظاهرة (curIdx >= 0) بس الـview مخفي أو ارتفاعه صفر → نرجّعه ونسجّل في اللوج
                if (ccOn && curIdx >= 0 && !sub.suppressed && !pipNow() && (sub.visibility != View.VISIBLE || sub.height <= 0)) {
                    if (++hideTicks >= 2) { hideTicks = 0; LogStore.add("⚠ الترجمة #${curIdx + 1} المفروض ظاهرة بس الـview مخفي/ارتفاعه صفر — اتصلّحت"); sub.visibility = View.VISIBLE; sub.requestLayout(); curIdx = -2 }
                } else hideTicks = 0
                // (v134) شرح المصطلحات: علامة ⓘ + اسم المصطلح بس بخط صغير (الشرح بيظهر لما تدوس عليها)؛ أوصاف الأصوات بتفضل زي ما هي
                val termIx = if (!ccOn) -1 else sact.firstOrNull { list[it].translated.trimStart().startsWith("«") } ?: -1
                val sTxt = if (!ccOn || sact.isEmpty()) "" else sact.joinToString("   ") { ix -> val t = list[ix].translated; if (t.trimStart().startsWith("«")) "ⓘ " + termName(t) else t }
                if (sTxt != soundKey) {
                    soundKey = sTxt; termFull = if (termIx >= 0) list[termIx].translated else ""
                    soundTv.textSize = if (termIx >= 0) 10.5f else 13f
                    soundTv.setTextColor(if (Extras.deaf && termIx < 0) 0xFFB8C0CC.toInt() else 0xFFE8EAED.toInt())
                    soundTv.text = sTxt; soundTv.visibility = if (sTxt.isEmpty()) View.GONE else View.VISIBLE
                }
                st.text = status
                run {   // (v213) شريط الوضع البصري
                    val vs = ""   // (v224) شريط حالة الوضع البصري مخفي (visbar=1 يرجّعه)
                    if (::visBar.isInitialized && vs != visBarTxt) { visBarTxt = vs; visBar.text = vs; visBar.visibility = if (vs.isBlank()) View.GONE else View.VISIBLE }
                }
                if (now - lastBatch > 700) {
                    lastBatch = now
                    val bs = batchStyled(); val od = try { engine.offlineDetail() } catch (_: Throwable) { null }; val bl = (od ?: "") + bs.toString()
                    if (bl != lastBatchTxt) { lastBatchTxt = bl; batchTv.text = bs; miniLogTv?.text = if (bl.isBlank()) "مفيش باتشات لسه" else if (od != null) android.text.TextUtils.concat(od + "\n", bs) else bs }
                    updateProblems()
                }
                if (now - lastBeat > 20000) { lastBeat = now; LogStore.add("💓 ${LogStore.heapLine()} · ${if (player.isPlaying) "بيشتغل" else "واقف"} @${fmtMs(cur)} · مترجم ${(engine.coveredSec() / 60).toInt()}د · ${engine.subs.size} جملة") }
                run { val pc = PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0); val wp = Offline.livePct() * 1000 + Offline.runIdx; if (pc != trPct || wp != lastWp) { trPct = pc; lastWp = wp; updateTr() } }
                if (fullMode) { fsBadge.set("🔊 " + PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0).toString() + "% · " + list.size.toString() + " جملة ▾", ((try { engine.offlineBrief() } catch (_: Throwable) { null }) ?: "")); fsBadge.setOn(engineStarted && !engine.userPaused, ((Strict.freeText() || Strict.freeGem()) && FreeLlm.waiting)); netBadge.set(NetUse.fmt(NetUse.localTotal()), NetUse.fmt(NetUse.onlineTotal())); applyBadgeAlpha() }   // (v236) نقطة خضراء = الوضع الصوتي شغّال
                if (fullMode && ::visual.isInitialized) {   // (v431) الجزء البصري (👁 + الفاصل) بيظهر بس لما الوضع البصري شغّال؛ غير كده الكبسولة صوتي بس
                    val vOn = visual.running; val vv = if (vOn) View.VISIBLE else View.GONE
                    if (visBadge.visibility != vv) visBadge.visibility = vv
                    if (sentDiv.visibility != vv) sentDiv.visibility = vv
                    if (vOn) visBadge.set("👁 " + visual.count, "")
                }   // (v358) كبسولة واحدة للصوتي والبصري: من غير نقطة ولا كلمة شغّال/واقف ولا فاصل — الحالة من الدايرة الحمراء
                if (now - lastMem > 4000) { lastMem = now; if (!fullMode) mem.update(this@PlayerActivity) }
                run { val ct = "جمل ${list.size} · 👁 ${visList.size} · تغطية ${PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0)}% · فجوات ${engine.failedCount()} · كوتة ${Quota.used(conf.model)}/${Models.quotaOf(conf.model)} · ${NetUse.badgeLine()}"; if (ct != lastCountersTxt) { lastCountersTxt = ct; counters.text = ct } }   // (v458) كانت بتتكتب وتتقاس 5 مرات/ثانية حتى لو ما اتغيّرتش
                if (logDlg.isShowing && logMode == 0) {
                    val nl = synchronized(logBuf) { logBuf.length }
                    if (nl != lastLogLen) {
                        lastLogLen = nl
                        val atBottom = !logSv.canScrollVertically(1)
                        logTv.text = synchronized(logBuf) { logBuf.toString() }
                        if (atBottom) logSv.post { logSv.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                } catch (e: Throwable) { if (tickErrN++ < 5) { try { LogStore.err("Player:tick", e) } catch (_: Throwable) {} } } finally { h.postDelayed(this, 200) }   // (v471) أي استثناء في حلقة العرض كان بيقفل التطبيق — دلوقتي بيتسجّل في اللوج والحلقة تكمل
            }
        })
        engineStarted = false; updateTr()
        // «🌐 ترجمة وفرجة» من المتصفح: الترجمة تبدأ لوحدها من غير ما تدوس ▶ ترجمة
        if (autoTr && !noSub && !askSaved && (Cfg.str("hard_mode", "0") != "1" || SourceMode.get() == SourceMode.HYBRID) && SourceMode.get() != SourceMode.VISUAL) videoBoxRef.post { if (!engineStarted && !isFinishing && !isDestroyed && !pendingYt) beginTranslate(auto = true) }
    }

    /** (v118) تبديل جودة يوتيوب في نفس المكان من غير ما التشغيل يتقطع كتير. الجودة بتتحفظ للفيديوهات الجاية (داتا أقل) */
    private fun switchYt(i: Int, force: Boolean = false) {
        val o = qOpts.getOrNull(i) ?: return
        if (i == qCur && !force) return
        try {
            val pos = player.currentPosition; val play = player.playWhenReady
            val f = httpDsf ?: DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true)
            val au = if (o.audio != null) (aOverride ?: o.audio) else null   // (v388) لغة الصوت اللي اخترتها بتفضل مع تبديل الجودة
            // (v120) أي موقع: من غير صوت منفصل بنسيب ExoPlayer يختار النوع (MP4/HLS…) من اللينك؛ يوتيوب بصوت منفصل بيتدمج
            val src = if (au != null) { val pf = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(f)
                androidx.media3.exoplayer.source.MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(Uri.parse(o.url))), pf.createMediaSource(MediaItem.fromUri(Uri.parse(au)))) }
            else DefaultMediaSourceFactory(f).createMediaSource(MediaItem.fromUri(Uri.parse(o.url)))
            player.exo()?.let { xp -> xp.setMediaSource(src, pos); xp.prepare() }; player.playWhenReady = play
            qCur = i; sizeTxt = ""; setQualLabel(o.h); refreshSize()
            if (ytAudioOnly) { ytAudioOnly = false; paintModePill() }   // (v459) تغيير الجودة بيرجّع الفيديو
            if (!force) { if (intent.getStringExtra("ytid") != null) Cfg.put("yt_maxh", o.h.toString())
                Notice.show(this, "🎞 الجودة: " + o.label, 2000L) }
        } catch (e: Exception) { LogStore.err("Player:switchYt", e); Notice.show(this, "ما قدرتش أغيّر الجودة", 2300L) }
    }
    /** (v459) رابط الصوت المنفصل الحالي (لغة الصوت المختارة أولًا، بعده الصوت الافتراضي، بعده أي جودة بصوت) */
    private fun ytAudioUrl(): String? = aOverride ?: aurl ?: qOpts.getOrNull(qCur)?.audio ?: qOpts.firstOrNull { it.audio != null }?.audio

    private var ytResolving = false   // (v467) جلب مسار الصوت عند الطلب شغّال
    private fun paintModePill() { if (::modePill.isInitialized) modePill.text = if (ytAudioOnly) "🎬 وضع الفيديو" else "🎧 وضع الصوت" }

    /** (v459) تبديل وضع الصوت/الفيديو لفيديو يوتيوب جوه المشغّل نفسه ومن نفس المكان: الصوت بس بيوفّر الداتا،
     *  والرجوع للفيديو بيعيد تشغيل الجودة الحالية بالدمج العادي (switchYt). */
    private fun toggleYtMode() {
        try {
            val play = player.playWhenReady
            if (!ytAudioOnly) {
                val au = ytAudioUrl() ?: run {
                    // (v467) مفيش مسار صوت جاهز (دخول handoff مثلًا): بنجيبه عند الطلب بدل ما نقول «ملوش» على طول
                    val yid = intent.getStringExtra("ytid")?.takeIf { it.length == 11 }
                    if (yid != null && !ytResolving) { ytResolving = true; Notice.show(this, "⏳ بجيب مسار الصوت…", 1500L)
                        Thread { val got = try { YtFast.video(yid, Cfg.int("yt_maxh", 0), true) } catch (_: Throwable) { null }
                            runOnUiThread { ytResolving = false
                                if (got != null && !isFinishing && !isDestroyed) { aurl = got.audio?.takeIf { it.isNotEmpty() } ?: aurl
                                    try { got.qlist.takeIf { it.isNotEmpty() }?.let { sj -> val qa = org.json.JSONArray(sj); qOpts = (0 until qa.length()).map { qi -> val o = qa.getJSONObject(qi); YtExtract.Opt(o.optString("l"), o.optString("u"), o.optString("a").ifEmpty { null }, o.optInt("h")) }; parseATracks(sj) } } catch (_: Exception) {}
                                    if (ytAudioUrl() != null) toggleYtMode() else Notice.show(this, "الفيديو ده ملوش مسار صوت منفصل", 2000L)
                                } else if (!isFinishing && !isDestroyed) Notice.show(this, "ما قدرتش أجيب مسار الصوت — جرّب تاني", 2200L) }
                        }.apply { isDaemon = true }.start()
                    } else Notice.show(this, "الفيديو ده ملوش مسار صوت منفصل", 2000L)
                    return }
                val pos = player.currentPosition
                val f = httpDsf ?: DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true)
                val src = DefaultMediaSourceFactory(f).createMediaSource(MediaItem.fromUri(Uri.parse(au)))
                player.exo()?.let { xp -> xp.setMediaSource(src, pos); xp.prepare() }; player.playWhenReady = play
                ytAudioOnly = true
                Notice.show(this, "🎧 وضع الصوت: صوت بس — بيوفّر الداتا والبطارية", 2000L)
            } else {
                ytAudioOnly = false
                switchYt(if (qCur >= 0) qCur else 0, force = true)   // بيرجع يدمج الفيديو + الصوت من نفس المكان
                Notice.show(this, "🎬 وضع الفيديو", 1500L)
            }
            paintModePill()
        } catch (e: Exception) { LogStore.err("Player:ytMode", e); Notice.show(this, "ما قدرتش أبدّل الوضع", 2200L) }
    }

    private fun hlsAuto() {
        hlsH = 0
        try { player.exo()?.let { xp -> xp.trackSelectionParameters = xp.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO).build() } } catch (e: Exception) { LogStore.err("Player:hlsAuto", e) }
        setQualLabel(0); Notice.show(this, "🎞 الجودة: تلقائي", 1800L)
    }
    private fun hlsPick(g: androidx.media3.common.Tracks.Group, i: Int, h: Int) {
        hlsH = h
        try { player.exo()?.let { xp -> xp.trackSelectionParameters = xp.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build() } } catch (e: Exception) { LogStore.err("Player:hlsPick", e) }
        setQualLabel(h); Notice.show(this, "🎞 الجودة: " + h + "p", 1800L)
    }

    private fun buildPlayer() {
        if (remote) { buildRemotePlayer(); return }
        val sv = svRef; val videoBox = videoBoxRef
        // (v406) تكبير من المشغّل المصغّر في يوتيوب: بناخد نفس المشغّل (شغّال ومحمّل) بدل ما نبني واحد جديد ونحمّل من الأول
        val ho = if (intent.getBooleanExtra("handoff", false) && uri == null) YtHandoff.take() else null
        handedOff = ho != null
        if (ho != null) {
            httpDsf = DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true)
            player = LocalPlayback(ho)
        } else {
        val castSrc = uri == null && CastCache.isCast(url)   // (v564) فيديو جاي من الموبايل: كاش مؤقت بيتمسح لما تخرج
        val factory = if (uri != null) DefaultMediaSourceFactory(this)
        else { val f = DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true); httpDsf = f; DefaultMediaSourceFactory(if (castSrc) CastCache.factory(this, f) else f) }
        // MKV وغيره: لو الديكودر الأول فشل (HEVC / 10-bit) جرّب اللي بعده بدل شاشة سودا
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val eb = ExoPlayer.Builder(this, rf).setMediaSourceFactory(factory)
        if (castSrc) eb.setLoadControl(CastCache.loadControl())
        player = LocalPlayback(eb.build())
        }
        // (v467) دخول الـ handoff (تكبير من مشغّل يوتيوب المصغّر): qOpts/aurl بيبقوا فاضيين فزرار الصوت/فيديو والجودات كانوا ميتين — بنجيب بيانات الستريم في الخلفية من غير ما نلمس التشغيل الشغّال
        if (ho != null && qOpts.isEmpty()) intent.getStringExtra("ytid")?.takeIf { it.length == 11 }?.let { yid ->
            Thread { val got = try { YtFast.video(yid, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null }
                if (got != null) runOnUiThread { if (!isFinishing && !isDestroyed && qOpts.isEmpty() && intent.getStringExtra("ytid") == yid) {
                    aurl = got.audio?.takeIf { it.isNotEmpty() } ?: aurl
                    try { got.qlist.takeIf { it.isNotEmpty() }?.let { sj -> val qa = org.json.JSONArray(sj); qOpts = (0 until qa.length()).map { qi -> val o = qa.getJSONObject(qi); YtExtract.Opt(o.optString("l"), o.optString("u"), o.optString("a").ifEmpty { null }, o.optInt("h")) }; parseATracks(sj) } } catch (e: Exception) { LogStore.err("Player:hoQlist", e) }
                    if (qCur < 0) qCur = qOpts.indexOfFirst { it.url == url }
                    paintModePill(); try { applyChromeFn() } catch (_: Throwable) {}
                } }
            }.apply { isDaemon = true }.start()
        }
        // (v149) المشغّل بيمسك الـ audio focus: أي تطبيق تاني شغّال صوت بيتوقف، ولو حد تاني خد الصوت المشغّل بيقف بدل ما الصوتين يتخلطوا
        try { player.exo()?.let { xp -> xp.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true); xp.setHandleAudioBecomingNoisy(true) } } catch (e: Throwable) { LogStore.err("Player:focus", e) }
        WebMute.pauseAll()
        player.exo()?.setVideoSurfaceView(sv)
        applyBoost()
        player.setPlaybackSpeed(speed)
        var firstFrame = false
        val plst = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { try { loud?.release() } catch (_: Throwable) {}; loud = null; applyBoost() }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) {
                    vidW = v.width; vidH = v.height; videoBox.post { applyFit(sv, videoBox) }
                    if (hlsH == 0 && qOpts.getOrNull(qCur) == null) setQualLabel(0)
                    // الفيديو يفتح على شكله الحقيقي: طولي → رأسي، عريض → أفقي (مرة واحدة، ومالهاش دعوة لو إنت بدّلت بإيدك)
                    if (!userRot && !autoRotDone && !pipNow()) {
                        autoRotDone = true
                        val wantLand = v.width >= v.height
                        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        if (wantLand != isLand) requestedOrientation = if (wantLand) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                }
            }
            override fun onIsPlayingChanged(p: Boolean) {
                val t = if (p) "▮▮" else "▶"; if (!buffering) { ctl.play.text = t; fsPlayB.text = t; if (::centerPlay.isInitialized) centerPlay.text = t }; centerPlay.visibility = if (pipNow() || busyShown() || (fullMode && chromeShown)) View.GONE else if (buffering) View.VISIBLE else if (p) View.GONE else View.VISIBLE
                // تشخيص الشاشة السودا: صوت شغّال ومفيش ولا فريم فيديو اتعرض بعد ٥ ثواني
                if (p && !firstFrame) h.postDelayed({ if (!firstFrame && !isFinishing) log("⚠️ مفيش فريم فيديو اتعرض بعد ٥ ثواني — غالبًا كودك/بروفايل الفيديو مش مدعوم على الجهاز (مثلًا HEVC 10-bit)") }, 5000)
            }
            override fun onPlaybackStateChanged(st: Int) {
                setBuffering(st == Player.STATE_BUFFERING)
                if (st == Player.STATE_READY) ytBusy(false)   // (v362)
                else if (st == Player.STATE_BUFFERING && !firstFrame && intent.getStringExtra("ytid") != null) ytBusy(true, "⏳ بيحمّل الفيديو…")
                if (st == Player.STATE_READY) h.postDelayed({ checkIdentityDuration() }, 1500)
                // (v97) الفيديو خلص: لو اللوب شغّال يعيد من الأول، وإلا يروح للحلقة اللي بعدها (لو في)
                if (st == Player.STATE_ENDED && !isFinishing) {
                    if (sleepEndOfVideo) { sleepEndOfVideo = false; say("😴 مؤقت النوم: الفيديو خلص") }
                    else if (Cfg.str("loop", "0") == "1") { player.seekTo(0); player.play() }
                    else if (hasNextEpisode() || intent.getStringExtra("ytid") != null) h.post { v469AutoNext() }   // (v469) بطاقة «التالي بعد…» بدل الانتقال الفوري
                    else if (uri != null && VideoScan.cache == null && VideoScan.hasPermission(this@PlayerActivity)) {   // (v531) المكتبة لسه ما اتفحصتش: افحص وكمّل للتالي
                        Thread {
                            try { VideoScan.scan(this@PlayerActivity) } catch (_: Throwable) {}
                            runOnUiThread { if (!isFinishing && !isDestroyed && hasNextEpisode()) v469AutoNext() }
                        }.also { it.isDaemon = true }.start()
                    }
                }
            }
            override fun onRenderedFirstFrame() { firstFrame = true; webFirstFrame = true; ytBusy(false) }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                nativeCue = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            }
            override fun onTracksChanged(t: Tracks) {
                try {
                    if (!textPicked) {
                        val tg = t.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 && it.isSupported }
                        if (tg.isNotEmpty()) {
                            textPicked = true
                            if (tg.none { it.isSelected }) {
                                val pick = tg.firstOrNull { g -> (g.getTrackFormat(0).language ?: "").lowercase().startsWith("ar") } ?: tg.first()
                                player.exo()?.let { xp -> xp.trackSelectionParameters = xp.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(pick.mediaTrackGroup, 0)).build() }
                            }
                            log("💬 ترجمة أصلية في الفيديو: ${tg.size} مسار")
                        }
                    }
                } catch (e: Throwable) { LogStore.err("Player:textTrack", e) }
                paintQual()
                if (sizeTxt.isEmpty()) refreshSize()
                for (g in t.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO || g.length == 0) continue
                    val f = g.getTrackFormat(0)
                    val desc = "${f.sampleMimeType ?: "?"} ${f.width}x${f.height}"
                    if (g.isSelected) log("🎞 فيديو: $desc")
                    else if (!g.isSupported) { log("⚠️ كودك الفيديو ($desc) مش مدعوم على الجهاز ده — هيشتغل صوت من غير صورة"); say("كودك الفيديو ($desc) مش مدعوم على جهازك") }
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (pendingYt) return   // (v351)
                log("❌ خطأ في التشغيل: ${e.errorCodeName} — ${e.cause?.message ?: e.message}")
                if (ytSilentRetryOnce()) return   // (v468) يوتيوب: محاولة صامتة واحدة بستريم فريش قبل أي ديالوج يدوي
                say("خطأ في تشغيل الفيديو — التفاصيل في 📜 اللوجز")
                offerLinkRefresh()
            }
        }
        player.exo()?.addListener(plst)
        if (handedOff) {   // (v406) المشغّل جاهز وشغّال: بنزامن الواجهة (حجم الفيديو/زرار التشغيل/اللودينج) لأن الأحداث دي عدّت قبل ما نسمع
            h.post {
                try {
                    player.exo()?.let { plst.onVideoSizeChanged(it.videoSize) }
                    plst.onIsPlayingChanged(player.isPlaying)
                    plst.onPlaybackStateChanged(player.playbackState)
                    if (player.playbackState == Player.STATE_READY) plst.onRenderedFirstFrame()
                } catch (_: Throwable) {}
            }
            return
        }
        if (pendingYt) return   // (v351) لسه بنجيب الرابط: المشغّل فاضي ولودينج ويندوز بيلف
        val au = aurl
        val xp0: ExoPlayer = player.exo() ?: return
        if (uri == null && au != null) {
            val pf = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(httpDsf ?: DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true))
            xp0.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(Uri.parse(url!!))), pf.createMediaSource(MediaItem.fromUri(Uri.parse(au)))))
        } else xp0.setMediaItem(uri?.let { mediaItemFor(it) } ?: MediaItem.fromUri(Uri.parse(url!!)))
        xp0.prepare(); xp0.playWhenReady = true
    }

    // ===== (v139) عزل كل فيديو عن اللي قبله =====
    /** مصدر الصوت متربط بالفيديو اللي المحرك اتعمل له (مش بيقرا حقول المشغّل الحيّة): المحرك القديم ما يقدرش يفتح فيديو جديد بالغلط */
    private class SrcSpec(val uri: Uri?, @Volatile var url: String?, val hdr: HashMap<String, String>)
    private var spec: SrcSpec? = null
    /** وسيط بين المحرك والمشغّل: لما المحرك يتقفل (خروج / تغيير فيديو) كل ندائاته للمشغّل بتتجاهل */
    private class GuardedHost(val inner: Host) : Host {
        @Volatile var dead = false
        @Volatile var lastPos = 0.0
        @Volatile var lastDur = 0.0
        override fun log(s: String) { if (!dead) inner.log(s) }
        override fun status(s: String) { if (!dead) inner.status(s) }
        override fun changed() { if (!dead) inner.changed() }
        override fun notice(s: String) { if (!dead) inner.notice(s) }
        override fun position(): Double = if (dead) lastPos else inner.position().also { lastPos = it }
        override fun playerDuration(): Double = if (dead) lastDur else inner.playerDuration().also { lastDur = it }
        fun kill() { if (!dead) { try { lastPos = inner.position(); lastDur = inner.playerDuration() } catch (_: Throwable) {}; dead = true } }
    }
    private var guard: GuardedHost? = null
    /** أي مكان بيقفل المحرك الحالي لازم يعدّي من هنا: يقطع ندائاته للمشغّل الأول، وبعدين يقفله ويختمه */
    private fun retireEngine() {
        if (remote) return   // (v546) وضع المتحكم: المحرك بتاع التلفزيون ومايتقفلش من الموبايل
        try { guard?.kill() } catch (_: Throwable) {}
        try { if (::engine.isInitialized) engine.stop() } catch (e: Throwable) { LogStore.err("Player:retire", e) }
    }

    // (v362) لودينج يوتيوب الكبير
    private var ytBusyV: FrameLayout? = null
    private var ytBusyTv: TextView? = null
    private val ytBusyDr = WinSpinnerDrawable()
    private var ytBusyT0 = 0L
    private var ytBusyMsg = ""
    private val ytBusyTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || ytBusyV?.visibility != View.VISIBLE) return
            val sec = (System.currentTimeMillis() - ytBusyT0) / 1000L
            ytBusyTv?.text = ytBusyMsg + (if (sec >= 2L) " · " + sec + " ث" else "")
            h.postDelayed(this, 500L)
        }
    }
    /** (v377) طبقة «بيحمّل الفيديو» ظاهرة (ليها سبينر خاص بيها) — زرار التشغيل الأوسط ماينفعش يعرض سبينر تاني فوقها */
    private fun busyShown(): Boolean = ytBusyV?.visibility == View.VISIBLE
    private fun ytBusy(on: Boolean, msg: String = "") {
        val v = ytBusyV ?: return
        if (on) {
            if (::centerPlay.isInitialized) { spinners.remove(centerPlay)?.stop(); centerPlay.foreground = null; centerPlay.visibility = View.GONE }
            ytBusyMsg = msg
            if (v.visibility != View.VISIBLE) { ytBusyT0 = System.currentTimeMillis(); v.visibility = View.VISIBLE; ytBusyDr.start(); h.removeCallbacks(ytBusyTick); ytBusyTick.run() }
            else ytBusyTv?.text = msg
        } else { h.removeCallbacks(ytBusyTick); v.visibility = View.GONE; ytBusyDr.stop()
            if (::centerPlay.isInitialized && buffering && !pipNow() && !uiLocked) { val sp = spinners.getOrPut(centerPlay) { WinSpinnerDrawable() }; centerPlay.text = ""; centerPlay.foreground = sp; sp.start(); centerPlay.visibility = View.VISIBLE } }
    }

    private var freshOnce = false
    /** (v351) المشغّل بيفتح فورًا بشكله الكامل واللودينج جواه؛ رابط الفيديو بيتجاب في الخلفية وبعدين التشغيل يبدأ في نفس الشاشة */
    @Volatile private var pendingYt = false
    private var pendTok = 0
    private fun startYtPend(fresh: Boolean = false) {
        val id = intent.getStringExtra("ytid") ?: return
        val tok = ++pendTok
        h.post { try { setBuffering(true); ytBusy(true, "⏳ بجيب الفيديو…") } catch (_: Throwable) {} }
        Thread {
            val got = try { YtFast.video(id, Cfg.int("yt_maxh", 0), fresh) } catch (_: Throwable) { null }   // (v362) YtExtract + NewPipe بالتوازي
            runOnUiThread {
                if (tok != pendTok || isFinishing || isDestroyed || !pendingYt) return@runOnUiThread
                val u = got?.url
                if (u == null || got == null) {
                    if (!fresh) { startYtPend(true); return@runOnUiThread }   // (v467) محاولة تانية صامتة بـ fresh (تخطي الكاش + إعادة فحص العملاء) قبل إزعاج المستخدم برسالة الفشل
                    try { setBuffering(false); ytBusy(false) } catch (_: Throwable) {}
                    val why = YtExtract.lastWhy.ifBlank { "السبب مش معروف (راجع اللوج)" }
                    GAlert(this).setTitle("⚠ ما قدرتش أجيب الفيديو")
                        .setMessage("يوتيوب ما رضيش يدّي لينك للفيديو ده من التطبيق.\n\n" + why)
                        .setPositiveButton("🔁 جرّب تاني") { _, _ -> startYtPend(true) }
                        .setNeutralButton("🌐 افتحه في المتصفح") { _, _ ->
                            try { startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", YtHistory.watchUrl(id)).putExtra("noauto", true)) } catch (_: Throwable) {}
                            closeNoBg() }
                        .setNegativeButton("إغلاق") { _, _ -> closeNoBg() }.show()
                    return@runOnUiThread
                }
                pendingYt = false
                url = u; aurl = got.audio?.takeIf { it.isNotEmpty() }
                try { ytBusy(true, "⏳ بيبدأ التشغيل…") } catch (_: Throwable) {}
                try {
                    got.qlist.takeIf { it.isNotEmpty() }?.let { sj -> val qa = org.json.JSONArray(sj)
                        qOpts = (0 until qa.length()).map { qi -> val o = qa.getJSONObject(qi); YtExtract.Opt(o.optString("l"), o.optString("u"), o.optString("a").ifEmpty { null }, o.optInt("h")) }; parseATracks(sj) }
                } catch (e: Exception) { LogStore.err("Player:pendQlist", e) }
                qCur = qOpts.indexOfFirst { it.url == url }
                hdr["User-Agent"] = got.ua; hdr["Referer"] = "https://www.youtube.com/"
                if (intent.getStringExtra("title").isNullOrBlank()) got.title.takeIf { it.isNotBlank() }?.let { intent.putExtra("title", it) }
                // نفس خطوات swapVideo: نقفل المحرك والمشغّل الفاضيين ونبنيهم على الرابط الحقيقي
                try { retireEngine() } catch (_: Throwable) {}
                try { loud?.release(); loud = null } catch (_: Throwable) {}
                try { player.release() } catch (_: Throwable) {}
                buildPlayer(); initEngine()
                try { refreshList(); updateTr() } catch (_: Throwable) {}
                val autoOk = autoTr && !noSub && !askSaved && (Cfg.str("hard_mode", "0") != "1" || SourceMode.get() == SourceMode.HYBRID) && SourceMode.get() != SourceMode.VISUAL
                if (autoOk) videoBoxRef.post { if (!engineStarted && !isFinishing && !isDestroyed) beginTranslate(auto = true) }
            }
        }.apply { isDaemon = true }.start()
    }
    /** يشغّل المحرك. لو الدخول كان «ابدأ من الأول وجديد»: المسح والحفظ على خيط الخلفية قبل ما الحلقة تبدأ (مش على الـ UI) */
    private fun startEngine() {
        if (remote) return
        val fresh = freshOnce; freshOnce = false
        if (fresh) { try { player.seekTo(0) } catch (e: Exception) { LogStore.err("Main:2041", e) }; cur = 0L }
        val eng = realEngine   // (v139) المحرك اللي اتطلب له التشغيل بالذات — مش اللي يبقى في الحقل وقت ما الخيط يشتغل
        Thread {
            if (fresh) try { eng.redoAll() } catch (e: Throwable) { val m = "⚠ " + (e.message ?: e.toString()).take(120); log(m); runOnUiThread { say(m) } }
            eng.run()
        }.apply { isDaemon = true }.start()
    }

    /** (v117) الدخول الفوري: تحميل الترجمة المحفوظة بيتم في خيط خلفي — الشاشة بتفتح على طول واللودنج بيلف لحد ما تتحمّل البيانات */
    @Volatile private var engineReady = false
    private var pendingBegin = false
    private fun initEngine() {
        if (remote) { initRemoteEngine(); return }
        vid = videoId()
        NetUse.init(); NetUse.online = (uri == null)   // عدّاد النت: فيديو الإنترنت بيتحسب لوحده
        if (uri == null && url != null && !incognito) webRegister()
        offsetMs = (Cfg.str("sub_offset_ms:$vid", "0").toLongOrNull() ?: 0L).coerceIn(-10000L, 10000L)   // (v468) قص القيم المحفوظة القديمة لحد ±10ث
        runCatching { offFsB?.text = String.format("%+.2fs", offsetMs / 1000.0) }
        // (v469) B3: سرعة الفيديو ده بالذات لو متسجّلة بتتطبّق فورًا · B9: تحميل فصول الوصف
        (Cfg.str("speed:$vid", "").toFloatOrNull())?.let { sp -> speed = sp.coerceIn(0.25f, 3f); try { player.setPlaybackSpeed(speed) } catch (_: Throwable) {}; speedBRef?.text = "⏩ " + PlayerLogic.speedLabel(speed) }
        loadChapters()
        lastSrtN = -1
        val wasBg = BgJobs.isActive(vid)
        val store = Store(File(filesDir, "progress"), Store.keyFor(vid))
        val pb = PromptBuilder { p -> assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        engineReady = false; pendingBegin = false
        // (v139) أي محرك قديم لسه شغال يتقفل الأول ونقطع ندائاته، وبعدين الجديد بيتربط بمصدر صوت فيديوه هو بس
        retireEngine()
        val sp = SrcSpec(uri, aurl ?: url, HashMap(hdr)); spec = sp
        val gh = GuardedHost(this); guard = gh
        val eng = Engine(conf, { makeSource(sp) }, store, gh, pb)
        eng.persistBlocked = true
        realEngine = eng
        engine = LocalEngine(eng)
        brainSubsRef = null
        BrainBus.reset()   // (v542) فيديو/محرك جديد: ترجمة الفيديو القديم ما تتبعتش للريموت
        engine.convDialect = Cfg.str("conv_dialect", "")
        engine.toneStyle = Cfg.str("tone_style", ""); engine.toneStrength = Cfg.str("tone_strength", "متوسطة")
        engine.titleHint = run {
            val t = (intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid)).substringBeforeLast('.', Recents.titleOf(vid))
            val folder = try { uri?.toString()?.let { SrtWriter.pathOf(applicationContext, it)?.parentFile?.name } } catch (_: Exception) { null } ?: ""
            // (v134) تنضيف الاسم قبل البحث على النت: شيل [الجروب] و(السنة) والجودة وصيغ الريبّ (1080p / x265 / WEB-DL …) عشان جوجل يلاقي العمل أسهل
            fun clean(x: String): String {
                val c = x.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
                    .replace(Regex("(?i)\\b(2160p|1080p|720p|480p|360p|x264|x265|h\\.?264|h\\.?265|hevc|web-?dl|web-?rip|blu-?ray|brrip|hdrip|hdtv|aac|ac3|mkv|mp4)\\b"), " ")
                    .replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
                return if (c.length >= 3) c else x
            }
            listOf(clean(t), clean(folder)).filter { it.isNotBlank() }.distinct().joinToString(" | ")
        }
        Live.engine = realEngine
        visual = VisualMode({ visConfNow() }, { cur / 1000.0 }, { makeRetriever() }, { m ->   // (v182) cur = نسخة volatile من مكان التشغيل — ExoPlayer بيرمي استثناء لو اتنادى من خيط تاني
            visLog(m)   // (v182) كل رسائل الوضع البصري بتتسجّل في اللوج
            // (v213) الوضع البصري بيشتغل في صمت: حالته بتظهر في شريطه (visBar) تحت شريط الباتش الفاشل، ومفيش إشعارات على الشاشة غير الأخطاء اللي لازم تتصلّح (مفتاح ناقص / مصدر مش مدعوم)
            val important = (m.startsWith("ضيف مفتاح") || m.contains("مش مدعوم") || m.startsWith("المفاتيح") || m.startsWith("⚠")) && !(visAutoRun && !m.startsWith("المفاتيح"))
            if (important) runOnUiThread { Notice.show(this, m, 2300L) }
        }, { }, { grabSurface() }, log = { m -> visLog(m) })
        // (v185) النتايج بتتحفظ مع الفيديو وبترجع لما تفتحه تاني (المتخفي: من غير حفظ)
        if (!incognito) try { visual.bind(File(File(filesDir, "visual"), Store.keyFor(vid) + ".json")) } catch (e: Throwable) { LogStore.err("Player:visualBind", e) }   // (v287) لو فشل نتايج البصري مش هتتحفظ
        // (v181) الوضع البصري تلقائي: بيشتغل لوحده مع فتح الفيديو لو فيه مفتاح للوضع البصري (ضغطة 👁 بتقفله/تشغّله، ومن الإعدادات تقدر تقفل التلقائي)
        if (VisualMode.auto() && conf.visKeys.any { it.length > 10 } && !incognito) {
            visAutoRun = true
            h.postDelayed({ if (!isFinishing && !isDestroyed && !visual.running) { visLog("👁 تشغيل تلقائي مع فتح الفيديو"); visual.mode = "scene"; visualStart() } }, 1200)
        }
        status = "⏳ بحمّل بيانات الفيديو…"
        val vidNow = vid
        Thread {
            // (كانت بتتعمل على الـ UI وبتجمّد الشاشة): إيقاف ترجمة الخلفية + قراءة وتحليل ملف الترجمة المحفوظة
            if (wasBg) try { BgJobs.stopAndWait(vidNow, 2500) } catch (e: Throwable) { LogStore.err("Player:stopBg", e) }
            val savedPos0 = try { eng.load() } catch (e: Throwable) { LogStore.err("Player:load", e); 0.0 }
            // (v125) آخر مكان وقوف فعلي (بيتحفظ كل 3 ث، حتى وإنت في PiP) أحدث من اللي في ملف الترجمة
            val lastPosSec = try { val s = Cfg.p.getString("lastpos", "") ?: ""; val i = s.indexOf('\t'); if (i > 0 && s.substring(0, i) == vidNow) (s.substring(i + 1).toLongOrNull() ?: 0L) / 1000.0 else 0.0 } catch (_: Throwable) { 0.0 }
            // (v586) يوتيوب: أحدث موضع من مخزن YtProg/recent.json (المصغّر والكامل بيكتبوا فيه) بيغلب القديم
            val ytMs = if (vidNow.startsWith("yt:")) try { YtProg.resumeNow(applicationContext, vidNow.removePrefix("yt:")) } catch (_: Throwable) { 0L } else 0L
            val savedPos = if (ytMs > 5000L) ytMs / 1000.0 else if (lastPosSec > 5.0) lastPosSec else savedPos0
            val rp = if (savedPos <= 5.0 && uri == null) {
                try { Recents.parse(File(filesDir, "recent.json").readText()).firstOrNull { it.id == vidNow }?.posSec ?: 0.0 } catch (_: Exception) { 0.0 }
            } else 0.0
            eng.persistBlocked = false
            runOnUiThread {
                if (isFinishing || isDestroyed || realEngine !== eng) return@runOnUiThread
                engineReady = true
                if (pendingYt) return@runOnUiThread   // (v351) لسه مفيش رابط: الاستكمال/السؤال بيحصلوا بعد ما initEngine تتعاد على الرابط الحقيقي
                val startMs = intent.getLongExtra("startms", 0L)   // (v343) جاي من تحويل صوت→فيديو: كمّل من نفس مكان الصوت
                if (startMs > 0L) intent.removeExtra("startms")   // (v351) بيتستهلك مرة واحدة
                if (startMs > 0L) { player.seekTo(startMs); cur = startMs; log("⏩ كملت من ${fmtMs(cur)}") }
                else if (savedPos > 5.0 && !freshOnce) { player.seekTo((savedPos * 1000).toLong()); cur = (savedPos * 1000).toLong(); log("⏩ كملت من ${fmtMs(cur)}") }
                else if (uri == null && !freshOnce && rp > 5.0) {
                    // رابط من الإنترنت: لو مفيش مكان وقوف في الترجمة، كمّل من آخر مكان اتفرجت عليه (من السجل)
                    player.seekTo((rp * 1000).toLong()); cur = (rp * 1000).toLong(); log("⏩ كملت من ${fmtMs(cur)}")
                }
                refreshList()
                LogStore.add("🎬 فتح فيديو $vidNow · ${LogStore.heapLine()}")
                maybePrompt(wasBg)
                if (pendingBegin) { pendingBegin = false; beginTranslate() }
            }
        }.apply { isDaemon = true }.start()
    }

    // ===== فشل لينك التحميل: تحديثه في الخلفية من صفحة الفيديو المحفوظة =====
    private var ytErrRetried = false   // (v468) محاولة يوتيوب الصامتة مرة واحدة لكل فتح
    /** (v468) خطأ تشغيل يوتيوب أثناء المشاهدة: بنجيب ستريم فريش بصمت (fresh=true) وبنكمّل من نفس المكان؛ true = اتعاملنا ولسه بنحاول، false = مش يوتيوب/اتجربت خلاص فيقع للمسار اليدوي */
    private fun ytSilentRetryOnce(): Boolean {
        val yid = intent.getStringExtra("ytid")?.takeIf { it.length == 11 } ?: return false
        if (ytErrRetried || isFinishing || isDestroyed) return false
        ytErrRetried = true
        val pos = try { player.currentPosition } catch (_: Throwable) { cur }
        log("🔁 (v468) خطأ يوتيوب — محاولة صامتة واحدة بستريم فريش من ${fmtMs(pos)}…")
        Thread {
            val got = try { YtFast.video(yid, Cfg.int("yt_maxh", 0), true) } catch (_: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (got == null) { say("خطأ في تشغيل الفيديو — التفاصيل في 📜 اللوجز"); offerLinkRefresh(); return@runOnUiThread }
                url = got.url; aurl = got.audio?.takeIf { it.isNotEmpty() }
                hdr["User-Agent"] = got.ua; hdr["Referer"] = "https://www.youtube.com/"
                try { httpDsf?.setDefaultRequestProperties(hdr) } catch (_: Throwable) {}
                try {
                    val au = aurl
                    if (au != null) {
                        val pf = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(httpDsf ?: DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true))
                        player.exo()?.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(Uri.parse(got.url))), pf.createMediaSource(MediaItem.fromUri(Uri.parse(au)))))
                    } else player.exo()?.setMediaItem(MediaItem.fromUri(Uri.parse(got.url)))
                    player.exo()?.prepare(); if (pos > 0L) player.seekTo(pos); player.playWhenReady = true
                    log("✓ (v468) الستريم الفريش اشتغل — كمّلنا من نفس المكان")
                } catch (ex: Exception) { LogStore.err("Player:ytSilentRetry", ex); offerLinkRefresh() }
            }
        }.apply { isDaemon = true }.start()
        return true
    }
    private var refreshAsked = false
    private var refreshTries = 0
    private fun offerLinkRefresh() {
        if (uri != null || url == null || refreshAsked || isFinishing || isDestroyed) return
        refreshAsked = true
        val appCtx = applicationContext; val id = vid
        Thread {
            val w = WebVideos.find(appCtx, id)
            val page = (w?.page?.takeIf { it.isNotEmpty() } ?: hdr["Referer"] ?: "")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!page.startsWith("http")) { askPageLink(); return@runOnUiThread }
                if (++refreshTries > 2) { refreshAsked = false; return@runOnUiThread }   // (v142) محاولتين بس في الجلسة عشان مايدخلش في لف
                startLinkRefresh(page)   // (v142) بيجيب اللينك لوحده من غير ما يسألك
            }
        }.apply { isDaemon = true }.start()
    }
    /** (v203) بيفتح صفحة الفيديو قدامك في المتصفح: تدوس تشغيل، والتطبيق بيلقط اللينك الجديد ويرجّعك للمشغّل بنفس الفيديو (ترجمته وتقدمه وإعداداته) */
    private fun repairInBrowser(page: String) {
        val id = vid; val old = url ?: ""; val t = intent.getStringExtra("title") ?: ""
        try { player.pause() } catch (_: Throwable) {}
        startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", page).putExtra("noauto", true)
            .putExtra("repair_id", id).putExtra("repair_url", old).putExtra("repair_title", t))
        finish()
    }
    /** مفيش لينك صفحة محفوظ: بنطلبه منك مرة واحدة ونحفظه جنب لينك الفيديو */
    private fun askPageLink() {
        val clip = try { (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: "" } catch (_: Exception) { "" }
        val et = EditText(this).apply { setText(if (clip.startsWith("http", true)) clip else ""); hint = "https://…"; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(48, 36, 48, 36); maxLines = 4 }
        val appCtx = applicationContext; val id = vid
        GAlert(this).setTitle("🔗 لينك صفحة الفيديو").setMessage("لينك الفيديو خلص ومفيش لينك الصفحة محفوظ. الصق لينك الصفحة اللي بيتشغّل فيها الفيديو في الموقع — هحفظه، وأفتحهالك: تدوس تشغيل وأنا ألقط اللينك الجديد وأكمّل بنفس الترجمة.").setView(et)
            .setPositiveButton("فتح الصفحة") { _, _ ->
                val u = et.text.toString().trim()
                if (!u.startsWith("http", true)) { Notice.show(this, "اللينك لازم يبدأ بـ http", 3000L); return@setPositiveButton }
                Thread { WebVideos.update(appCtx, id) { it.copy(page = u) } }.apply { isDaemon = true }.start()
                repairInBrowser(u)
            }.setNegativeButton("إلغاء", null).show()
    }
    private fun startLinkRefresh(page: String) {
        val old = url ?: return
        Notice.show(this, "⏳ بجيب لينك الفيديو من الصفحة…", 4000L)
        LinkRefresh.run(this, page, old, hdr["User-Agent"] ?: "") { r ->
            if (isFinishing || isDestroyed) return@run
            refreshAsked = false
            if (r != null && r.same) {
                applyNewLink(r)
                Notice.show(this, "✓ اتحدّث لينك الفيديو", 2500L)
                return@run
            }
            // (v142) مالقيناش نفس الفيديو بالظبط: بنعرضلك اللينكات اللي اتلقت في قايمة تختار منها هنا، من غير ما تروح تدوّر بإيدك
            val opts = r?.all?.distinct().orEmpty()
            if (r != null && opts.isNotEmpty()) {
                val labels = opts.mapIndexed { i, u -> val hh = Sniff.heightOf(u); (if (hh > 0) "${hh}p" else "لينك ${i + 1}") + " · " + Sniff.nameOf(u).take(40) }.toTypedArray()
                GAlert(this).setTitle("اختار لينك الفيديو (" + opts.size + ")")
                    .setItems(labels) { _, w -> applyNewLink(LinkRefresh.Res(opts[w], true, r.cookie, r.ua)); Notice.show(this, "✓ اتحدّث لينك الفيديو", 2500L) }
                    .setNegativeButton("إلغاء", null).show()
            } else {
                GAlert(this).setTitle("⚠ مالقيتش لينك الفيديو").setMessage("الصفحة ماطلّعتش أي لينك فيديو (غالبًا محمية أو محتاجة ضغطة على زرار التحميل).\nتفتح الصفحة تصطاد بنفسك؟")
                    .setPositiveButton("نعم") { _, _ -> repairInBrowser(page) }
                    .setNegativeButton("لا", null).show()
            }
        }
    }
    private fun applyNewLink(r: LinkRefresh.Res) {
        url = r.url
        if (r.cookie.isNotEmpty()) hdr["Cookie"] = r.cookie
        spec?.let { sp -> if (aurl == null) sp.url = r.url; if (r.cookie.isNotEmpty()) sp.hdr["Cookie"] = r.cookie }
        httpDsf?.setDefaultRequestProperties(hdr)
        val appCtx = applicationContext; val id = vid; val nu = r.url; val ck = r.cookie
        val refB = intent.getStringExtra("ref") ?: ""; val titleB = intent.getStringExtra("title") ?: ""
        Thread { WebVideos.update(appCtx, id) { it.copy(url = nu, cookie = if (ck.isNotEmpty()) ck else it.cookie, ts = System.currentTimeMillis()) }; if (!incognito) WebIdent.bindLink(id, nu, refB, titleB) }.apply { isDaemon = true }.start()
        try { val pos = player.currentPosition; player.exo()?.setMediaItem(MediaItem.fromUri(Uri.parse(r.url)), pos); player.exo()?.prepare(); player.playWhenReady = true } catch (e: Exception) { LogStore.err("Main:2115", e) }
    }

    // ===== الفيديوهات المصطادة: تسجيل في السجل + لقطة مصغّرة (الاسم من صفحة الموقع) =====
    @Volatile private var webFirstFrame = false
    private var webSnapTries = 0
    private fun webRegister() {
        val u = url ?: return
        val appCtx = applicationContext; val id = vid
        val ref = hdr["Referer"] ?: ""; val ua = hdr["User-Agent"] ?: ""; val ck = hdr["Cookie"] ?: ""
        val siteTitle = intent.getStringExtra("title")?.takeIf { it.isNotBlank() }
        val t0 = siteTitle ?: Sniff.nameOf(u)
        val kind = if (u.contains("videoplayback")) "YT" else Sniff.kindOf(u)
        val page = if (WebIdent.specific(ref)) ref else ""   // (v142) صفحة الفيديو بتتحفظ لوحدها (من الصفحة اللي اتصاد منها) عشان تحديث اللينك يشتغل من غير ما تدخلها بإيدك
        Thread { WebVideos.register(appCtx, id, u, ref, ua, ck, t0, kind, siteTitle != null, page, hiddenSite) }.apply { isDaemon = true }.start()
        h.postDelayed({ webSnap() }, 9000)
    }
    private fun webSnap() {
        if (isFinishing || isDestroyed || !::svRef.isInitialized) return
        val ready = webFirstFrame && svRef.width > 0 && svRef.height > 0
        if (!ready) { if (++webSnapTries < 4) h.postDelayed({ webSnap() }, 8000); return }
        val appCtx = applicationContext; val id = vid
        val bmp = android.graphics.Bitmap.createBitmap(svRef.width, svRef.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res ->
                if (res != android.view.PixelCopy.SUCCESS) return@request
                Thread { try { WebVideos.saveThumb(appCtx, id, bmp) } catch (e: Throwable) { LogStore.err("Main:2140", e) } }.apply { isDaemon = true }.start()
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { LogStore.err("Main:2142", e) }
    }

    /** زرار 📋 في المشغّل = نفس صفحة اللوجز بتاعة القايمة (اللوج العايم اتلغى) */
    fun toggleLog() { if (logDlg.isShowing) logDlg.dismiss() else logDlg.show() }

    /**
     * (v151) 🧑 الشخصيات وجنسها: نافذة سفلية فوق الفيديو (الفيديو مابيتضغطش ولا بيتوقف).
     * لو مفيش شخصيات متحللة لسه (فيديو اتترجم قبل كده، أو التحليل التلقائي ماجاش) بتحلّلها من الترجمة الحالية أول ما تفتح.
     */
    private var charsDlg: android.app.Dialog? = null
    private fun showChars() {
        if (charsDlg?.isShowing == true) { charsDlg?.dismiss(); return }
        if (!::engine.isInitialized) { Notice.show(this, "المحرك لسه بيحمّل — جرّب كمان لحظة", 2300L); return }
        val eng = engine
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val d = ui.sheet(this, "🧑 الشخصيات", listOf<View>(col), false, onClose = { charsDlg = null })
        charsDlg = d
        var busy = false
        var analyzeRef: () -> Unit = {}
        fun render(list: List<Chr>, msg: String) {
            col.removeAllViews()
            if (msg.isNotEmpty()) col.addView(ui.text(msg, 12f, th.muted).apply { setPadding(0, 0, 0, ui.dp(6)) })
            for (c in list) {
                val f = c.gender == "female"
                val row = LinearLayout(this).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9)); background = ui.box(th.surface, th.border, 10)
                }
                row.addView(ui.text(c.name, 15f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(ui.text(if (f) "👩 أنثى" else "👨 ذكر", 13f, if (f) 0xFFE0559A.toInt() else 0xFF4F8FE8.toInt(), true))
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(5) })
                if (c.role.isNotBlank()) col.addView(ui.text(c.role, 11f, th.muted).apply { setPadding(ui.dp(12), ui.dp(2), ui.dp(12), 0) })
            }
            col.addView(ui.button(if (busy) "⏳ بحلّل…" else "🔄 حلّل الشخصيات من الترجمة دلوقتي") { if (!busy) analyzeRef() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        }
        fun analyze() {
            busy = true
            render(eng.charactersNow(), "⏳ بحلّل الشخصيات من الترجمة… (ممكن ياخد شوية — تقدر تقفل النافذة)")
            eng.analyzeCharsNow(
                { m -> runOnUiThread { if (d.isShowing) Notice.show(this, m, 2600L) } },
                { list -> runOnUiThread { busy = false; if (d.isShowing) render(list, if (list.isEmpty()) "ملقيتش شخصيات واضحة في الجمل الحالية — كمّل الترجمة وجرّب تاني" else "") } }
            )
        }
        analyzeRef = { analyze() }
        val now = eng.charactersNow()
        d.show()
        if (now.isEmpty()) analyze() else render(now, "")
    }

    /** (v125) ⏮ ⏭: لو الفيديو من فولدر في المكتبة → الحلقة اللي قبلها/بعدها، وإلا (فيديو إنترنت/ملف لوحده) → الجملة اللي قبلها/بعدها (قبل كده كانت بتطلّع رسالة بس) */
    /** (v531) إخوات الفيديو الحالي في نفس الفولدر + مكانه. على التلفزيون الترتيب هو ترتيب صفحة «الفيديوهات» (tv_vid_sort، الأحدث افتراضيًا)، وعلى الموبايل بالاسم */
    private fun libSibs(): Pair<List<VideoItem>, Int>? {
        val curU = uri?.toString() ?: return null
        val all = VideoScan.cache ?: return null
        val me = all.firstOrNull { it.uri == curU } ?: return null
        val mode = if (DeviceUi.isTv(this)) Cfg.str("tv_vid_sort", VideoLib.SORT_NEW) else VideoLib.SORT_NAME
        val sib = VideoLib.sortVideos(all.filter { it.folderKey == me.folderKey }, mode)
        return Pair(sib, sib.indexOfFirst { it.uri == curU })
    }
    /** (v531) آخر فيديو في القايمة ← يرجع لأول فيديو (تلفزيون افتراضيًا؛ على الموبايل لو autoplay_wrap=1) */
    private fun wrapOn(): Boolean = Cfg.str("autoplay_wrap", if (DeviceUi.isTv(this)) "1" else "0") == "1"

    fun stepOrSeek(d: Int) {
        run {
            val p = libSibs()
            if (p != null && (p.first.getOrNull(p.second + d) != null || (wrapOn() && p.first.size > 1))) { stepEpisode(d); return }
        }
        val t = player.currentPosition - offsetMs
        if (starts.isNotEmpty()) {
            val i = if (d > 0) starts.indexOfFirst { it > t + 200 } else starts.indexOfLast { it < t - 1500 }
            if (i >= 0) { player.seekTo(starts[i] + offsetMs); return }
        }
        player.seekTo((player.currentPosition + d * 10000L).coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE))
    }
    /** الحلقة اللي بعدها (+1) أو اللي قبلها (-1) من نفس الفولدر: بيوقف ترجمة الحالية ويبدأ ترجمة الجديدة */
    fun stepEpisode(d: Int) {
        val p = libSibs() ?: run { say("الفيديو ده مش من مكتبة الجهاز"); return }
        val sib = p.first
        var t = sib.getOrNull(p.second + d)
        if (t == null && wrapOn() && sib.size > 1) { t = if (d > 0) sib.first() else sib.last(); say("🔁 رجعنا " + (if (d > 0) "لأول" else "لآخر") + " فيديو في القايمة") }   // (v531)
        if (t == null) { say(if (d > 0) "دي آخر حلقة في الفولدر" else "دي أول حلقة في الفولدر"); return }
        Cfg.put("lib_last", t.uri); Cfg.put("lib_last_f:" + t.folderKey, t.uri)
        say("▶ " + t.title)
        swapVideo(Uri.parse(t.uri))
    }

    /** بدّل الفيديو في نفس الشاشة من غير ما تفتح Activity جديدة */
    fun swapVideo(newUri: Uri) {
        stopVoice()   // (v334) الصوت النضيف بتاع الفيديو القديم
        saveRecent()
        try { visual.stop() } catch (e: Exception) { LogStore.err("Main:2164", e) }
        retireEngine()
        try { engine.saveNow() } catch (e: Exception) { LogStore.err("Main:2166", e) }
        try { loud?.release(); loud = null } catch (_: Throwable) {}
        try { player.release() } catch (e: Exception) { LogStore.err("Main:2167", e) }
        uri = newUri; url = null; aurl = null; hdr.clear()
        cur = 0L; durMs = 0L; vidW = 0; vidH = 0; curIdx = -2; curKey = ""; dirty = true
        nativeCue = ""; textPicked = false; natShown = false; natLast = ""; preferNative = false; sfLive = ""; sfLiveCache.clear(); sfLiveAsk.clear()
        synchronized(logBuf) { logBuf.setLength(0) }
        status = ""; conf = Cfg.snapshot()
        try { sub.show(null) } catch (e: Exception) { LogStore.err("Main:2172", e) }
        visOv.showBoxes(emptyList())
        resumePending = false; applyCard()
        engineStarted = false; autoRotDone = false
        buildPlayer(); initEngine()
        notifTitleOv = Recents.titleOf(vid)
        updateTr()
    }

    /** أفقي = ملء الشاشة (شريط الأزرار العلوي + كبسولة التقدم تظهر بلمسة)، رأسي = فيديو فوق والكبسولة وشبكة الأزرار تحت */
    private fun applyFull(f: Boolean) {
        if (pipNow()) return   // في PiP الشاشة بتتغير مقاسها (config) وماينفعش نرجّع الأزرار
        fullMode = f
        if (!f) closeSide()
        fitFsB?.text = "⬛ " + fitNameOf(curFit()); if (::svRef.isInitialized) applyFit(svRef, videoBoxRef)
        extras.visibility = if (f) View.GONE else View.VISIBLE
        val lp = videoBoxRef.layoutParams as LinearLayout.LayoutParams
        val dm = resources.displayMetrics
        lp.height = if (f) -1 else (minOf(dm.widthPixels, dm.heightPixels) - ui.dp(32)) * 9 / 16
        if (f) lp.setMargins(0, 0, 0, 0) else lp.setMargins(ui.dp(16), ui.dp(32), ui.dp(16), 0)
        videoBoxRef.layoutParams = lp
        videoBoxRef.background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = if (f) 0f else ui.dp(14).toFloat() }
        fsBtnLp.setMargins(ui.dp(10), 0, 0, ui.dp(10)); fsBtnV.layoutParams = fsBtnLp
        fsOnly.forEach { it.visibility = if (f && (it !== visBadge || (::visual.isInitialized && visual.running))) View.VISIBLE else View.GONE }   // (v431) 👁 البصري بس لو شغّال
        try { tbView?.let { v -> (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.rightMargin = ui.dp(8); v.layoutParams = lp } } } catch (_: Throwable) {}   // (v265)
        st.visibility = if (f) View.GONE else View.VISIBLE
        if (::logDrawer.isInitialized) { (leftCol.layoutParams as FrameLayout.LayoutParams).topMargin = ui.dp(44); logDrawer.visibility = View.VISIBLE; leftCol.requestLayout() }
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility = if (f) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else (if (Build.VERSION.SDK_INT >= 23 && th.isLight) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        placeCard()
        if (f) showChrome() else { h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn() }
        if (::svRef.isInitialized) videoBoxRef.post { applyFit(svRef, videoBoxRef) }   // (v429) بعد ما الـ layout يخلص بالمقاس الجديد (كان بيتحسب على المقاس القديم لفريم)
    }
    fun showChrome() { if (pipNow() || uiLocked || DeviceUi.isRemote(this)) return; chromeShown = true; applyChromeFn(); h.removeCallbacks(hideChrome); h.postDelayed(hideChrome, 3500) }
    fun togglePlay() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    /** (v97) في حلقة بعد الحالية في نفس الفولدر؟ (من غير رسالة) */
    private fun hasNextEpisode(): Boolean {
        if (remote) return false
        val p = libSibs() ?: return false
        return p.first.getOrNull(p.second + 1) != null || (wrapOn() && p.first.size > 1)   // (v531) + اللفّ لأول فيديو
    }
    private var userRot = false
    private var autoRotDone = false
    /** (v604) تدوير صورة الفيديو نفسها (0/90/180/270) — على التلفزيون بس (شاشته مابتلفّش) */
    private var rotDeg = 0
    private var rmFitSkip = false
    /** (v604) في وضع المتحكم: السؤال «على الموبايل ولا على التلفزيون؟» لأي زرار ليه أثر على الاتنين */
    private fun rmChoose(title: String, phoneLbl: String, tvLbl: String, onPhone: () -> Unit, onTv: () -> Unit) {
        try {
            var dlg: android.app.Dialog? = null
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
            col.addView(ui.button(phoneLbl) { dlg?.dismiss(); onPhone() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            col.addView(ui.button(tvLbl, true) { dlg?.dismiss(); onTv() }, LinearLayout.LayoutParams(-1, -2))
            dlg = ui.sheet(this, title, listOf<View>(col)); dlg.show()
        } catch (e: Throwable) { LogStore.err("Player:rmChoose", e); onPhone() }
    }
    /** (v604) على التلفزيون: لفّ صورة الفيديو 90° (للفيديوهات المصوّرة على الجنب) */
    private fun rotateVideoNow(deg: Int = (rotDeg + 90) % 360) {
        rotDeg = ((deg % 360) + 360) % 360
        try { if (::svRef.isInitialized && ::videoBoxRef.isInitialized) { applyFit(svRef, videoBoxRef); videoBoxRef.post { applyFit(svRef, videoBoxRef) } } } catch (_: Throwable) {}
        try { say("🔄 " + rotDeg + "°") } catch (_: Throwable) {}
    }
    /** (v429) الدوران بين البورتريه والشاشة الكاملة كان بيبان «نتشة»: الشاشة بتلف بأنيميشن النظام والسطح بيتمدّد لحظة بمقاس قديم. كروس-فيد بيخفي ده */
    private fun smoothRotation() {
        try { val a = window.attributes; if (a.rotationAnimation != 1) { a.rotationAnimation = 1; window.attributes = a } } catch (_: Throwable) {}   // ROTATION_ANIMATION_CROSSFADE
    }
    fun toggleFs() {
        if (remote) {   // (v604) متوصّل بتلفزيون: لفّ إيه بالظبط؟
            rmChoose("🔄 تدوير", "📱 لفّ شاشة الموبايل", "📺 لفّ الفيديو على التلفزيون (90°)", { toggleFsLocal() }, {
                LinkSession.send("act", org.json.JSONObject().put("a", "rotate"))
                try { say("📺 🔄 90°") } catch (_: Throwable) {}   // مابنغيّرش rotDeg هنا: التدوير بيتطبّق على التلفزيون بس (معاينة الموبايل مابتتلفّش عشان ماتتلفّش مرتين)
            })
            return
        }
        toggleFsLocal()
    }
    private fun toggleFsLocal() {
        smoothRotation()
        userRot = true
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    /** مكان وحجم نافذة الـ PiP على الشاشة [x, y, w, h] (من الـ decor بتاع الأكتيفيتي) */
    fun pipRect(): IntArray? = try {
        val v = window.decorView; val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (v.width > 0 && v.height > 0) intArrayOf(loc[0], loc[1], v.width, v.height) else null
    } catch (_: Exception) { null }
    fun canOverlay() = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)
    fun enterPip(fromUser: Boolean = true) {
        // شريط الترجمة بره نافذة PiP محتاج إذن "العرض فوق التطبيقات" (مرة واحدة)
        if (fromUser && !DeviceUi.isTv(this) && !canOverlay() && !Cfg.str("pip_overlay_asked", "0").equals("1")) {
            Cfg.put("pip_overlay_asked", "1")
            Notice.show(this, ("فعّل «العرض فوق التطبيقات» لـ Subtitler عشان الترجمة تظهر فوق، وارجع دوس ⧉ تاني").toString(), 3600L)
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return } catch (e: Exception) { LogStore.err("Main:2229", e) }
        }
        if (Build.VERSION.SDK_INT >= 26) try {
            val pb = PictureInPictureParams.Builder()
            if (vidW > 0 && vidH > 0) pb.setAspectRatio(Rational((vidW.toFloat() / vidH).coerceIn(0.45f, 2.3f).times(1000).toInt(), 1000))
            if (Build.VERSION.SDK_INT >= 31) try { pb.setSeamlessResizeEnabled(true) } catch (e: Throwable) { LogStore.err("Main:2234", e) }
            enterPictureInPictureMode(pb.build())
        } catch (_: Exception) { Notice.show(this, ("PiP مش مدعوم على الجهاز ده").toString(), 2300L) }
    }
    private var internalNav = false
    override fun startActivity(i: Intent?) { internalNav = true; super.startActivity(i) }
    @Suppress("DEPRECATION")
    override fun startActivityForResult(i: Intent?, rc: Int) { internalNav = true; super.startActivityForResult(i, rc) }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!internalNav && !isFinishing && Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && try { player.isPlaying } catch (_: Exception) { false }) enterPip(false)
    }
    fun doExport() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-subrip"; putExtra(Intent.EXTRA_TITLE, "subtitles.srt") }, 7) }
    fun doImport() { startActivityForResult(filePicker("*/*", "اختار ملف SRT", false), 9) }
    fun doOpen() { startActivityForResult(filePicker("video/*", "اختار فيديو", true), 8) }
    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); if (!pipNow()) applyFull(true); if (remote) { rmFrameForce = true; try { h.postDelayed({ redrawLastRemoteFrame() }, 250L) } catch (_: Throwable) {} } }
    override fun onPictureInPictureModeChanged(inPipNow: Boolean, c: Configuration) {
        super.onPictureInPictureModeChanged(inPipNow, c)
        inPip = inPipNow
        if (inPipNow) { try {
            closeSide(); dismissPopFn()
            h.removeCallbacks(hideChrome); chromeShown = false
            extras.visibility = View.GONE; st.visibility = View.GONE; floatBar.visibility = View.GONE; rotBtn.visibility = View.GONE; batchBtn.visibility = View.GONE; trChipV?.visibility = View.GONE; closeBatchPanel(); closeMiniLog(); probDrawer.visibility = View.GONE; logDrawer.visibility = View.GONE; logHandle.visibility = View.GONE
            fsOnly.forEach { it.visibility = View.GONE }; centerPlay.visibility = View.GONE
            visOv.showBoxes(emptyList())
            val lp = videoBoxRef.layoutParams as LinearLayout.LayoutParams; lp.height = -1; lp.setMargins(0, 0, 0, 0); videoBoxRef.layoutParams = lp; fsBtnV.visibility = View.GONE
            videoBoxRef.findViewWithTag<View>("chromeFrame")?.visibility = View.GONE
            sub.suppressed = true; applyCard()
            applyFit(svRef, videoBoxRef)
            // الترجمة في شريط صغير فوق الشاشة (تحت الستاتس بار) بدل جوه الفيديو
            if (canOverlay()) { pipOv = PipSubBar(this, { pipRect() }, listOf<Pair<String, () -> Unit>>("🌙 ترجم في الخلفية" to { translateInBackground(false, "شريط الـ PiP (زرار 🌙)") }, "🔊 صوت بس" to { audioOnly() }, "⏹ إيقاف نهائي" to { fullStop() })).also { it.show() } }
            curIdx = -2
        } catch (e: Exception) { LogStore.err("Main:2266", e) } }
        else {
            pipOv?.hide(); pipOv = null
            sub.suppressed = false; curIdx = -2; applyCard()
            applyFull(true)
            // لو الخروج من PiP كان بالـ ✕ (الأكتيفيتي مش ظاهرة) نقفل الفيديو ونخرج؛ لو كان توسيع، onResume بيلغي الفحص
            h.removeCallbacks(pipExitCheck); h.postDelayed(pipExitCheck, 500)
        }
    }

    private var lastFitTag = ""
    private fun applyFit(sv: SurfaceView, box: View) {
        if ((vidW == 0 || vidH == 0) && ::player.isInitialized) { val vs = player.videoSize(); if (vs.first > 0 && vs.second > 0) { vidW = vs.first; vidH = vs.second } }
        if (vidW == 0 || box.width == 0) return
        val mode = if (pipNow()) 0 else curFit()
        // (v469) وضع 100%: مقاس الفيديو الطبيعي 1:1 × عامل الزوم (القرص) — ممكن يعدّي الحاوية وبيتوسّط (FrameLayout بيقصّ الزيادة)
        val r90 = rotDeg % 180 != 0   // (v604) لفّ 90/270: المقاس المرئي بعد اللف هو اللي بيتظبط على الصندوق
        val (w, hh) = if (mode == 3) Pair((vidW * vidZoomF).toInt().coerceAtLeast(1), (vidH * vidZoomF).toInt().coerceAtLeast(1))
                      else { val (fa, fb2) = PlayerLogic.fitSize(box.width, box.height, if (r90) vidH else vidW, if (r90) vidW else vidH, mode); if (r90) Pair(fb2, fa) else Pair(fa, fb2) }
        try { sv.rotation = rotDeg.toFloat() } catch (_: Throwable) {}
        val lp = FrameLayout.LayoutParams(w, hh, Gravity.CENTER)
        sv.layoutParams = lp; sv.requestLayout(); box.requestLayout()
        val tag = "$mode:${box.width}x${box.height}:${vidW}x$vidH:$vidZoomF:$rotDeg"
        if (tag != lastFitTag) { lastFitTag = tag; LogStore.add("⬛ احتواء/تمديد: وضع=${fitNameOf(mode)} الحاوية=${box.width}x${box.height} الفيديو=${vidW}x$vidH ← السطح=${w}x$hh") }
    }

    private fun videoId(): String {
        val u = uri
        if (u != null) {
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return "f:" + c.getString(0) + ":" + c.getLong(1)
                }
            } catch (e: Exception) { LogStore.err("Main:2289", e) }
            // (v310) ملف مفتوح من الفايل مانجر (file://): نفس صيغة الهوية بتاعة المكتبة (الاسم:الحجم) فالتقدم والترجمة يتشاركوا
            if (u.scheme == "file") { try { val ff = java.io.File(u.path ?: ""); if (ff.isFile) return "f:" + ff.name + ":" + ff.length() } catch (e: Exception) { LogStore.err("Player:fileId", e) } }
            return "f:$u"
        }
        intent.getStringExtra("ytid")?.takeIf { it.length == 11 }?.let { return "yt:$it" }   // (v117) يوتيوب: التقدم والترجمة على معرّف الفيديو مش على لينك الستريم المتغيّر
        val ref0 = intent.getStringExtra("ref") ?: ""; val title0 = intent.getStringExtra("title") ?: ""
        // (v203) رجعنا من «إصلاح اللينك» في المتصفح: نفس هوية الفيديو القديم (الترجمة والتقدم والإعدادات) على اللينك الجديد
        intent.getStringExtra("forceid")?.takeIf { it.isNotEmpty() }?.let { fid -> if (!incognito) WebIdent.bindLink(fid, url ?: "", ref0, title0); return fid }
        val legacy = webId(url ?: "", ref0, title0)
        if (incognito) return legacy
        // (v142) نفس الفيديو بجودة تانية أو اتصاد تاني بلينك جديد = نفس الهوية القديمة (الترجمة والتقدم بيفضلوا)
        val hit = WebIdent.resolve(legacy, url ?: "", ref0, title0)
        idVia = if (hit.id != legacy) hit.via else null
        return hit.id
    }
    private var idVia: String? = null
    private var durChecked = false
    /** حماية من دمج غلط: لو الهوية جت من تطابق صفحة/عنوان والمدة مختلفة كتير عن اللي اتسجّل، اسأل المستخدم */
    private fun checkIdentityDuration() {
        val via = idVia ?: return
        if (durChecked || durMs <= 0) return
        durChecked = true
        val id = vid; val d = durMs / 1000.0
        Thread {
            val old = try { Recents.parse(File(filesDir, "recent.json").readText()).firstOrNull { it.id == id }?.durSec ?: 0.0 } catch (_: Exception) { 0.0 }
            if (old > 30 && Math.abs(old - d) > 5.0) runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Notice.ask(this, "⚠ مدة الفيديو (" + PlayerLogic.clock((d * 1000).toLong()) + ") مختلفة عن الفيديو المحفوظ بنفس الاسم (" + PlayerLogic.clock((old * 1000).toLong()) + ") — ممكن يكون فيديو تاني؟", "افصله", "هو نفسه", 12000L,
                    { WebIdent.blockKey(via); startActivity(Intent(this, PlayerActivity::class.java).apply { putExtras(intent) }); finish() }, { })
            }
        }.apply { isDaemon = true }.start()
    }
    /** (v138) هوية فيديو النت: الاسم في اللينك لوحده ما بيكفيش — لينكات زي /videoplayback أو master.m3u8 أو video.mp4 بتتكرر لفيديوهات مختلفة،
     *  فكانت ترجمة فيديو بتتحمّل على فيديو تاني. اللينك العادي (اسم ملف مميّز) بيفضل بنفس الهوية القديمة عشان التقدم المحفوظ ما يضيعش. */
    private fun webId(u: String, ref: String, title: String): String {
        val noFrag = u.substringBefore('#')
        val base = noFrag.substringBefore('?')
        val q = noFrag.substringAfter('?', "")
        fun qp(k: String): String? = q.split('&').firstNotNullOfOrNull { kv -> if (kv.substringBefore('=').equals(k, true)) kv.substringAfter('=', "").takeIf { it.isNotBlank() } else null }
        val file = base.substringAfterLast('/').lowercase()
        val generic = u.contains("videoplayback") || file.isBlank() ||
            Regex("(master|index|playlist|chunklist|manifest|stream|video|play|media|file|main|source|hls|dash)(\\.(m3u8|mpd|mp4|webm|ts|php|m4v))?").matches(file)
        val stable = listOf("id", "docid", "vid", "video", "video_id", "videoid", "v", "fid", "file", "name", "mid").firstNotNullOfOrNull { k -> qp(k)?.let { "$k=$it" } }
        if (!generic) return "u:$base"
        if (stable != null) return "u:" + (if (u.contains("videoplayback")) "gv" else base) + "?" + stable
        val page = ref.substringBefore('#').substringBefore('?').trim()
        val extra = listOf(title.trim(), if (page.length > 12 && page.substringAfter("://").contains('/')) page else "").filter { it.isNotBlank() }.joinToString("|")
        return if (extra.isNotBlank()) "u:$base|$extra" else "u:$base?" + Integer.toHexString(q.hashCode())
    }

    private fun makeSource(sp: SrcSpec): AudioSource {
        val lg: (String) -> Unit = { if (spec === sp) log(it) }
        val src = AudioSources.make(applicationContext, sp.uri, sp.url, sp.hdr, conf.audioTrack, lg)   // يوتيوب بصوت منفصل: sp.url فيه رابط الصوت
        lg("🎙 مصدر الصوت: " + (if (sp.uri != null) "ملف محلي" else if (src is HlsSource) "رابط HLS" else "رابط مباشر"))
        return src
    }

    fun fmtMs(ms: Long) = String.format("%02d:%02d:%02d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 8 && c == RESULT_OK) d?.data?.let { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { LogStore.err("Main:2308", e) }
            swapVideo(u)
        }
        if (r == 9 && c == RESULT_OK) d?.data?.let { u ->
            val parsed = try { PlayerLogic.parseSrt(contentResolver.openInputStream(u)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "") } catch (_: Exception) { emptyList() }
            if (parsed.isEmpty()) Notice.show(this, ("الملف مش SRT صالح").toString(), 2300L)
            else if (engine.subs.isNotEmpty()) Notice.show(this, ("فيه ترجمة موجودة بالفعل — الاستيراد بيشتغل على فيديو من غير ترجمة بس").toString(), 3600L)
            else {
                engine.importSubs(parsed.map { Sub(it.first, it.second, it.third, it.third, "male", "unknown", "none", emptyList(), emptyList(), false, false) })
                dirty = true; Thread { engine.saveNow() }.start()
                Notice.show(this, ("اتستورد ${parsed.size} جملة").toString(), 2300L)
            }
        }
        if (r == 41 && c == RESULT_OK) d?.data?.let { u -> sfRun("soft", sfPickEng, u) }   // (v529) ملف ترجمة اخترته من الجهاز
        if (r == 32) {
            val t = pendingOrigSrt; pendingOrigSrt = null
            if (c == RESULT_OK && t != null) d?.data?.let { u ->
                try { contentResolver.openOutputStream(u)?.use { it.write(t.toByteArray(Charsets.UTF_8)) }; Notice.show(this, ("تم حفظ SRT بالنص الأصلي").toString(), 2300L) }
                catch (_: Exception) { Notice.show(this, ("تعذّر حفظ الملف").toString(), 2300L) }
            }
        }
        if (r == 31) {
            val t = pendingSentTxt; pendingSentTxt = null
            if (c == RESULT_OK && t != null) d?.data?.let { u ->
                try { contentResolver.openOutputStream(u)?.use { it.write(t.toByteArray(Charsets.UTF_8)) }; Notice.show(this, ("تم حفظ الجمل").toString(), 2300L) }
                catch (_: Exception) { Notice.show(this, ("تعذّر حفظ الملف").toString(), 2300L) }
            }
        }
        if (r == 7 && c == RESULT_OK) d?.data?.let { u ->
            contentResolver.openOutputStream(u)?.use { it.write(PlayerLogic.toSrt(engine.subs, offsetMs).toByteArray()) }
            Notice.show(this, ("تم حفظ SRT").toString(), 2300L)
        }
    }

    private fun saveRecent() { if (!handedOff && !remote) saveRecentForce() }
    /** (v586) يوتيوب: نفس مخزن تقدم المشغّل المصغّر (YtProg) — عشان الخروج من تكبير الشاشة وفتح الفيديو تاني يكمّل من نفس المكان */
    private fun saveYtProg() {
        try {
            if (incognito || remote || hiddenSite) return
            val yid = intent.getStringExtra("ytid")?.takeIf { it.length == 11 } ?: return
            if (durMs <= 0 || cur < 1000L) return
            YtProg.save(yid, cur, durMs, intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid), "https://i.ytimg.com/vi/$yid/mqdefault.jpg")
        } catch (_: Throwable) {}
    }
    private fun saveRecentForce() {
        if (incognito) return
        saveYtProg()
        try { if (!hiddenSite && durMs > 0 && cur > 1000 && cur < durMs - 4000 && vid.isNotEmpty()) Cfg.p.edit().putString("lastpos", vid + "\t" + cur).apply() } catch (_: Throwable) {}
        if (!::engine.isInitialized || !engineReady) return
        try {
            if (!incognito && !hiddenSite) {
            val f = File(filesDir, "recent.json")
            val old = Recents.parse(try { f.readText() } catch (_: Exception) { "" })
            val r = Recent(vid, (if (uri == null) intent.getStringExtra("title")?.takeIf { it.isNotBlank() } else null) ?: Recents.titleOf(vid), url ?: "", uri?.toString() ?: "", cur / 1000.0, durMs / 1000.0, engine.subs.size, engine.coveredSec(), System.currentTimeMillis())
            f.writeText(Recents.toJson(Recents.upsert(old, r, 300)))
            // (v570) لينك مش يوتيوب (جاي من المتصفح): نحفظ مصغّرة من الفيديو الشغّال مرة واحدة عشان تظهر في «آخر المشغّلة» (بس من خيط خلفي — PixelCopy)
            if (uri == null && !url.isNullOrEmpty() && intent.getStringExtra("ytid") == null && cur > 3000L && android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                LinkThumb.saveIfMissing(applicationContext, vid) {
                    val b = grabSurface(640)
                    if (b == null) null else {
                        var sum = 0L; var n = 0
                        val sx = maxOf(1, b.width / 16); val sy = maxOf(1, b.height / 16)
                        var yy = 0
                        while (yy < b.height) { var xx = 0; while (xx < b.width) { val c = b.getPixel(xx, yy); sum += (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 3; n++; xx += sx }; yy += sy }
                        if (n > 0 && sum / n < 12) { b.recycle(); null } else b   // لقطة سودا: نجرّب في الحفظ الجاي
                    }
                }
            }
        } catch (e: Exception) { LogStore.err("Main:2341", e) }
    }
    /** أفقي: يرجع للرأسي من غير ما يقفل. رأسي: يحفظ التقدم ويوقف الفيديو ويقفل. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (remote) { leaveController(); return }   // (v568) شاشة التحكم: الخروج منها ما بيوقّفش الفيديو على التلفزيون
        if (DeviceUi.isRemote(this) && miniLog != null) { closeMiniLog(); return }   // (v509) ✕ الصغيرة في لوج الجمل: Back يقفلها
        if (tvBar?.visible == true) { tvBar?.hide(); return }   // (v477 م4) تلفزيون: Back بيقفل شريط التحكم الأول
        if (nextPanel?.consumeBack() == true) return   // (v378)
        if (sideOpen) { closeSide(); return }
        // (v110) علامة ✔ فوق: لو الترجمة لسه ما خلصتش، كمّلها في الخلفية أول ما أخرج
        // (v232) مفيش ترجمة في الخلفية من غير موافقتك: لو الترجمة لسه ما خلصتش بنسألك، و«لا» = بتتقفل خالص ومفيش أي استهلاك نت
        val unfinished = !incognito && !handedOff && engineStarted && durMs > 0 && PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0) < 99
        val hasKey = Cfg.allMainKeys().isNotEmpty() || conf.keys.isNotEmpty() || conf.backup.isNotEmpty()
        if (unfinished && hasKey && Cfg.bool("ask_bg_exit", true)) { askBgOnExit(); return }
        closeNoBg()
    }
    private fun netDialog() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("📶 استهلاك النت").setMessage(NetUse.report()).setPositiveButton("تمام", null).show()
    }
    private var exitAsk: android.app.AlertDialog? = null
    private fun askBgOnExit() {
        if (exitAsk?.isShowing == true) return
        exitAsk = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🌙 الترجمة لسه ما خلصتش")
            .setMessage("أكمّل الترجمة في الخلفية؟ (هيستهلك نت)\nلو اخترت «لا» الترجمة بتتقفل خالص ومفيش نت بيتصرف.")
            .setPositiveButton("🌙 أيوه، كمّل في الخلفية") { _, _ -> translateInBackground(false, "خروج (اخترت: كمّل في الخلفية)") }
            .setNegativeButton("⛔ لا، قفّل خالص") { _, _ -> closeNoBg() }
            .setNeutralButton("رجوع للفيديو", null)
            .show()
    }
    /** خروج من غير ترجمة خلفية: بيحفظ التقدم، يوقف المحرك وأي مهمة خلفية لنفس الفيديو، ويقفل */
    /** (v340) لوحة «التالي»: دوسة على فيديو = يفتح في مشغّل جديد (القديم بيتقفل ويحفظ تقدمه) وبيكمّل نفس وضع الترجمة */
    fun openNextSheet() {
        val cid = intent.getStringExtra("ytid") ?: return
        YtNext.show(this, ui, th, cid) { n -> playNextYt(n) }
    }
    private fun playNextYt(n: NpItem) {
        if (remote) { Notice.show(this, "📺 الفيديو اللي بعده بيتحدد من التلفزيون", 2200L); return }
        cancelNextUp()   // (v531) اخترت فيديو من اللوحة أثناء العدّاد
        say("⏳ بجيب «" + n.title.take(40) + "»…")
        val ret = intent.getStringExtra("ret") ?: ""; val nosub = intent.getBooleanExtra("nosub", false); val auto = intent.getBooleanExtra("autotr", false)
        Thread {
            val got = try { YtFast.video(n.id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null }   // (v362)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val url = got?.url
                if (url == null || got == null) { say("ما قدرتش أجيب الفيديو ده — جرّب غيره"); return@runOnUiThread }
                try { if (YtStore.histOn()) YtStore.addFront(YtStore.HIST, NpItem(n.id, n.title, n.channel, n.channelUrl, n.thumb, n.dur, n.count, n.age, 0, YtNp.watchUrl(n.id)), 200); YtStore.markApp(n.id) } catch (_: Throwable) {}
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("url", url); putExtra("aurl", got.audio ?: ""); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", got.ua)
                    got.qlist.takeIf { it.isNotEmpty() }?.let { putExtra("qlist", it) }
                    putExtra("title", n.title.ifBlank { got.title }); putExtra("ytid", n.id)
                    putExtra("nosub", nosub); putExtra("autotr", auto); putExtra("ret", ret)
                })
            }
        }.apply { isDaemon = true }.start()
    }
    @Suppress("DEPRECATION")
    /** (v343) 🎧 حوّل لصوت: بنقفل الفيديو (بيحفظ مكانه) ونفتح نفس الفيديو في مشغّل الموسيقى من نفس المكان */
    fun switchToAudio() {
        val id = intent.getStringExtra("ytid") ?: return
        audioHandoff = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("ytaudio", id).putExtra("ytaudtitle", intent.getStringExtra("title") ?: "").putExtra("ytaudpos", cur)
        // (v351) رابط الصوت اللي بيشتغل دلوقتي (لو صوت منفصل) بيتبعت جاهز: التحويل فوري من غير ما نجيب الفيديو من الصفر
        try {
            val au = (qOpts.getOrNull(qCur)?.audio ?: aurl)?.takeIf { it.startsWith("http") }
            if (au != null) audioHandoff?.putExtra("ytaudurl", au)?.putExtra("ytaudua", hdr["User-Agent"] ?: "")?.putExtra("ytauddur", durMs)
        } catch (e: Throwable) { LogStore.err("Player:audHandoff", e) }
        closeNoBg()
    }
    /** (v568) خروج من شاشة التحكم من غير أي أمر للتلفزيون: الفيديو يكمّل شغّال، وتقدر ترجع له من كبسولة «▶ اسم ✕» */
    private fun leaveController() {
        rmFrames = false
        try { returnToOrigin() } catch (_: Throwable) {}
        super.onBackPressed()
    }
    private fun closeNoBg() {
        if (remote) { leaveController(); return }
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:2347", e) }
        saveRecent()
        val eng = engine; val v = vid
        try { retireEngine() } catch (e: Throwable) { LogStore.err("Player:closeNoBg1", e) }
        Thread {
            try { eng.awaitStopped(3000); eng.saveNow() } catch (e: Throwable) { LogStore.err("Player:closeNoBg0", e) }   // (v287)
            try { if (BgJobs.isActive(v)) BgJobs.stop(v) } catch (e: Throwable) { LogStore.err("Player:closeNoBg2", e) }
        }.start()
        returnToOrigin()
        super.onBackPressed()
    }
    /** (v339) الرجوع من الفيديو يرجّعك للصفحة اللي فتحته منها (يوتيوب / المتصفح) بنفس مكانك — حتى لو النظام كان قفل الصفحة دي وإحنا في المشغّل */
    private fun returnToOrigin() {
        audioHandoff?.let { h -> audioHandoff = null; try { startActivity(h) } catch (e: Throwable) { LogStore.err("Player:audioHandoff", e) }; return }   // (v343)
        try {
            when (intent.getStringExtra("ret")) {
                "yt" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "youtube"))
                "browser" -> startActivity(Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                // (v570) فيديو اتفتح على التلفزيون من الموبايل/صف يوتيوب: الخروج يرجّعك للصفحة اللي كنت فيها (اللي تحت المشغّل)؛ ولو مفيش حاجة تحته (التطبيق ماكانش شغّال) للرئيسية
                TvDirect.RET -> if (isTaskRoot) startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        } catch (e: Throwable) { LogStore.err("Player:ret", e) }
    }
    // ===== أدوات الـ HTML اللي كانت ناقصة في النسخة النيتف =====
    // ===== لودينج ويندوز: بدل زرار ⏸/▶ اللي في النص أثناء التحميل (فيديو أونلاين / تنقّل / سيك) =====
    @Volatile private var buffering = false
    private val spinners = HashMap<View, WinSpinnerDrawable>()
    /** (v576) المصدر شبكة (يوتيوب/لينك/فيديو من الموبايل متبعوت للتلفزيون) ولا ملف محلي على الجهاز */
    private fun tvSrcIsNet(): Boolean {
        if (intent.getStringExtra("ytid") != null) return true
        val sc = try { player.exo()?.currentMediaItem?.localConfiguration?.uri?.scheme } catch (_: Throwable) { null }
        if (sc != null) return sc.startsWith("http")
        return url != null || uri?.scheme?.startsWith("http") == true
    }
    fun setBuffering(b0: Boolean) {
        val b = b0 && !(DeviceUi.isTv(this) && !tvSrcIsNet())   // (v576) تلفزيون + فيديو محلي: مفيش سبينر ولا مربع (القفز سريع)
        if (b == buffering && (b || spinners.isEmpty())) return
        buffering = b
        val targets = listOfNotNull<TextView>(if (::fsPlayB.isInitialized) fsPlayB else null, if (::ctl.isInitialized) ctl.play else null, if (::centerPlay.isInitialized) centerPlay else null)
        for (v in targets) {
            if (b && v === (if (::centerPlay.isInitialized) centerPlay else null) && busyShown()) continue   // (v377) سبينر واحد بس
            if (b) {
                val sp = spinners.getOrPut(v) { WinSpinnerDrawable() }
                v.text = ""; v.foreground = sp; sp.start()
            } else {
                spinners.remove(v)?.stop(); v.foreground = null
                v.text = if (player.isPlaying) "▮▮" else "▶"
            }
        }
        applyChromeFn()
    }
    private fun paintNotices() {
        if (!::noticeBtn.isInitialized) return
        val n = NoticeLog.list().size
        noticeBtn.visibility = View.VISIBLE   // (v221) ظاهر دايمًا عشان تلاقيه — خافت لو مفيش إشعارات
        noticeBtn.alpha = if (n == 0) 0.35f else 0.7f
        noticeBtn.text = if (n == 0) "▲" else "▲ $n"
    }

    private var noticePop: android.widget.PopupWindow? = null
    /** (v236) قايمة إشعارات صغيرة شفافة بتفتح لفوق (زي قوايم المشغّل): الإشعارات بس من الأحدث للأقدم، ضغطة طويلة = مسح الكل */
    private fun noticePopup(anchor: View) {
        noticePop?.let { if (it.isShowing) { it.dismiss(); noticePop = null; return } }
        val dm = resources.displayMetrics
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(2)); background = ui.box(0x99141418.toInt(), 0x22FFFFFF, 12)
        }
        val items = NoticeLog.list()
        for (it in items) col.addView(ui.text(it.msg, 11.5f, 0xFFF1F3F4.toInt()).apply { setPadding(0, 0, 0, ui.dp(6)) }, LinearLayout.LayoutParams(-1, -2))
        if (items.isEmpty()) col.addView(ui.text("مفيش إشعارات", 11.5f, 0xFFC9CED6.toInt()).apply { setPadding(0, 0, 0, ui.dp(6)) })
        val popW = minOf(dm.widthPixels - ui.dp(24), ui.dp(240))
        col.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val avail = (loc[1] - ui.dp(12)).coerceAtLeast(ui.dp(80))
        val popH = minOf(col.measuredHeight, avail, (dm.heightPixels * 0.4f).toInt())
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(col) }
        val pw = TvFocus.pop(this@PlayerActivity, scroll, popW, popH, true)
        Motion.popIn(scroll, false)   // (v445)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); pw.isOutsideTouchable = true
        col.setOnLongClickListener { NoticeLog.clear(); pw.dismiss(); true }
        pw.setOnDismissListener { if (noticePop === pw) noticePop = null }
        try { pw.showAsDropDown(anchor, 0, -(anchor.height + popH + ui.dp(2)), Gravity.END) } catch (e: Throwable) { LogStore.err("Player:noticePopup", e) }
        noticePop = pw
    }

    private fun say(m: String) = runOnUiThread { Notice.show(this, (m).toString(), 2300L) }

    // ===== (v418) دبلجة عربي (Edge TTS) =====
    private var dubPlay: DubPlay? = null
    @Volatile private var dubBusy = false
    @Volatile private var dubCancel = false

    private fun dubToggle() {
        val dp = dubPlay
        if (dp != null && dp.active) { dp.stop(); say("🎙 الدبلجة اتقفلت — صوت الفيديو الأصلي رجع"); return }
        if (dubBusy) { dubCancel = true; say("⏹ بلغي الدبلجة…"); return }
        val segs = Dub.segsOf(engine.subs)
        if (segs.isEmpty()) { say("مفيش جمل مترجمة لسه — ترجم الأول وبعدين دبّلج"); return }
        val dur = try { player.duration } catch (_: Throwable) { -1L }
        val total = if (dur > 0) dur / 1000.0 else 0.0
        val key = Store.keyFor(vid)
        val app = applicationContext
        dubBusy = true; dubCancel = false
        say("🎙 بجهّز الدبلجة (" + segs.size + " جملة) بصوت " + Dub.voiceLabel() + "…")
        Thread {
            var last = 0L
            val f = try {
                Dub.build(app, key, segs, total, { d, t -> val n = System.currentTimeMillis(); if (n - last > 4000L) { last = n; say("🎙 دبلجة " + d + "/" + t) } }, { dubCancel || isDestroyed })
            } catch (e: Throwable) { LogStore.err("Player:dub", e); null }
            dubBusy = false
            if (isDestroyed) return@Thread
            runOnUiThread {
                if (f == null) { say(if (dubCancel) "اتلغت الدبلجة" else "❌ الدبلجة فشلت — اتأكد إن النت شغّال وجرّب تاني"); return@runOnUiThread }
                val xoD = player.exo()
                if (xoD == null) { say("📺 الدبلجة مش متاحة وإنت متحكم في التلفزيون"); return@runOnUiThread }
                val dp2 = dubPlay ?: DubPlay(xoD).also { dubPlay = it }
                if (dp2.start(f)) say("✅ الدبلجة شغّالة — صوت الفيديو الأصلي اتوطّى") else say("❌ مقدرتش أشغّل ملف الدبلجة")
            }
        }.start()
    }

    private fun dubVoiceSwap() {
        val v = Dub.toggleVoice()
        say("🗣 الصوت دلوقتي: " + Dub.voiceLabel(v) + " — دوس دبلجة تاني عشان تتبنى بالصوت الجديد")
        dubPlay?.stop()
    }
    private fun touchSubs() = runOnUiThread { curIdx = -2; dirty = true }

    fun runTool(label: String, instruction: String, all: Boolean) {
        if (Cfg.allMainKeys().isEmpty() && conf.keys.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        say("بدأ: $label…")
        engine.rewriteAll(label, instruction, if (all) 0.0 else player.currentPosition / 1000.0, { _ -> }) { n ->
            say(if (n > 0) "✅ $label: اتغيّرت $n جملة (تقدر ترجع من 🗂 ترجمات الفيديو)" else "$label: مفيش جمل اتغيّرت"); touchSubs()
        }
    }
    var giShowFn: (String) -> Unit = {}

    private var pendingOrigSrt: String? = null
    /** (v153) تحميل SRT بالنص الأصلي المستخرج (من الإعدادات) — بيفتح اختيار مكان الحفظ */
    fun exportOriginalSrt() {
        val txt = PlayerLogic.toSrtOriginal(engine.subs, offsetMs)
        if (txt.isBlank()) { say("مفيش نص أصلي لسه"); return }
        val base = try { Recents.titleOf(vid).substringBeforeLast('.', Recents.titleOf(vid)) } catch (_: Throwable) { "video" }
        val safe = base.replace(Regex("[\\\\/:*?\"<>|]+"), " ").trim().ifEmpty { "video" }
        pendingOrigSrt = txt
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-subrip"; putExtra(Intent.EXTRA_TITLE, "$safe.orig.srt") }, 32) }
        catch (_: Exception) { pendingOrigSrt = null; say("تعذّر فتح اختيار مكان الحفظ") }
    }

    /** (v153) 🌍 ترجمة كل الجمل بجوجل (من غير API) وعرضها على الفيديو مكان الترجمة الحالية */
    fun googleTranslateNow() {
        if (engine.subs.none { !it.isSound && it.original.isNotBlank() }) { say("مفيش نص أصلي لسه — لازم الفيديو يتحلل الأول"); return }
        say("🌍 بترجم بجوجل… (من غير API)")
        engine.googleTranslateAll({ m -> say(m) }) { n -> say(if (n > 0) "✅ جوجل: اتترجمت $n جملة (تقدر ترجع من 🗂 ترجمات الفيديو)" else "جوجل: مفيش جمل اتغيّرت"); touchSubs() }
    }

    private val aiSel = linkedSetOf<String>()
    private val DIAL_INSTR get() = "زوّد شدة اللهجة الشعبية في كل جملة درجة واحدة: ألفاظ وتعبيرات الشارع والعامية المحلية (${conf.lang}) بدل الفصحى والكلام الرسمي، من غير ما تغيّر المعنى أو الجنس."
    private val FAMILY_INSTR = "نضّف الجملة من الألفاظ الخارجة والإيحاءات الجنسية وخليها عائلية ومناسبة لكل الأعمار مع الحفاظ على المعنى العام."
    private val EXPLICIT_INSTR = "رجّع الترجمة لمطابقة صراحة النص الأصلي بالظبط (الألفاظ والإيحاءات زي ما هي في الأصل من غير تلطيف ولا حذف)."

    /** (v151) بينفّذ كل الخيارات المحددة في قايمة ✨ بالترتيب بدوسة واحدة، وكل خطوة بتستنى اللي قبلها */
    fun applyAiOptions() {
        if (aiSel.isEmpty()) { say("اختار خيار واحد على الأقل من القايمة"); return }
        // (v360) من غير مفتاح جيميناي، المهام الجانبية بتكمّل بمحرك LLM المختار (conv_via) — نفس شرط Engine.bgCall
        val llmOk = ExtLlm.byId(Cfg.str("conv_via", "").trim())?.let { ExtLlm.configured(it) && ExtLlm.enabled(it) && ExtLlm.blockedWhy(it) == null } == true
        if (Cfg.allMainKeys().isEmpty() && conf.keys.isEmpty() && !llmOk) { say("ضيف مفتاح API (أو اختار محرك LLM للمهام الجانبية) الأول"); return }
        val order = listOf("refine", "dial", "family", "explicit", "pron", "dedup").filter { it in aiSel }
        val results = ArrayList<String>()
        var stopped = false
        fun rep(m: String) { if (m.startsWith("في عملية شغالة")) { stopped = true; say(m) } else say(m) }
        fun step(k: Int) {
            if (stopped) return
            if (k >= order.size) { say("✅ خلصت: " + results.joinToString(" · ") + " (تقدر ترجع من 🗂 ترجمات الفيديو)"); touchSubs(); return }
            fun fin(label: String, n: Int) { results.add("$label $n"); touchSubs(); step(k + 1) }
            when (order[k]) {
                "refine" -> { say("✍ (${k + 1}/${order.size}) تنقيح بالسياق…"); engine.refineWholeNow({ m -> rep(m) }) { n -> fin("تنقيح", n) } }
                "dial" -> { say("🔥 (${k + 1}/${order.size}) لهجة +…"); engine.rewriteAll("زيادة شدة اللهجة", DIAL_INSTR, 0.0, { m -> rep(m) }) { n -> fin("لهجة", n) } }
                "family" -> { say("🧹 (${k + 1}/${order.size}) عائلي…"); engine.rewriteAll("عائلي", FAMILY_INSTR, 0.0, { m -> rep(m) }) { n -> fin("عائلي", n) } }
                "explicit" -> { say("🔞 (${k + 1}/${order.size}) صريح…"); engine.rewriteAll("صريح", EXPLICIT_INSTR, 0.0, { m -> rep(m) }) { n -> fin("صريح", n) } }
                "pron" -> { say("🔧 (${k + 1}/${order.size}) ضمائر…"); engine.correctPronounsNow({ m -> rep(m) }) { n -> fin("ضمائر", n) } }
                else -> { val n = engine.removeDuplicates(); fin("دمج مكرر", n) }
            }
        }
        step(0)
    }



    private var langPop: android.widget.PopupWindow? = null
    /** 🌐 لغة الترجمة: قايمة صغيرة تحت الزرار بنفس شكل قوايم الشريط. فصحى = ترجمة حرفية بس، غير كده = حرفية + تحويل تلقائي كل ~3 باتشات */
    // ===== (v218) وضع المفاتيح: الأوضاع التلاتة + قايمة الاختيار جنب زرار الترجمة =====
    private val kmLabels = ArrayList<Pair<TextView, (Int) -> String>>()
    private var kmPop: android.widget.PopupWindow? = null

    fun applyKeyMode(n0: Int) {
        val n = KeyMode.SPLIT_A   // (v425) ثابت
        Cfg.put("kmode", n.toString())
        try { val nc = Cfg.snapshot(); conf = nc; if (::engine.isInitialized) engine.applyConf(nc) } catch (e: Throwable) { LogStore.err("Player:kmode", e) }
        for ((v, f) in kmLabels) v.text = f(n)
        LogStore.add("🔑 وضع المفاتيح اتغيّر لـ " + KeyMode.name(n))
    }


    /**
     * (v408) 📴 ترجمة أوفلاين — زرار واحد: لو مفيش موديل Whisper نازل بينزّل small (~185 ميجا)، وموديل ML Kit بيتنزّل لوحده، وبعدين يبدأ
     * تفريغ Whisper على الجهاز + ترجمة ML Kit (فصحى؛ اللهجة بتتحوّل زي إعدادك). اللغة: إعداد التفريغ أو الإنجليزي لو تلقائي. الخطة بتتحفظ (وإلا الاستئناف كان هيرجّع القديمة).
     */
    private var offBusy = false
    fun offlineQuick(only: Boolean = false, free: Boolean = false) {
        if (offBusy) return
        if (!WhisperLib.loaded) { say("📴 الأوفلاين مش مدعوم على جهازك"); return }
        if (Offline.downloading()) { say("⬇ فيه تنزيل موديل شغّال — استنى يخلص وادوس تاني"); return }
        offBusy = true
        val lang = Offline.lang()   // (v411) "" = تلقائي: Whisper بيكتشف اللغة، وموديل ML Kit بيتنزّل أول ما اللغة تتأكد (مع إشعار تقدم)
        val order = listOf(Offline.model()) + listOf("small", "base", "medium").mapNotNull { id -> Offline.models.firstOrNull { it.id == id } }
        val m = order.firstOrNull { Offline.downloaded(it) } ?: Offline.models.first { it.id == "small" }
        fun startIt() {
            val fr = free && !only   // (v440) وشبر + FreeLLM (free="free")
            val pl = TrWizard.Plan("offline", "", lang, m.id, "google", "whisper", if (only) "" else if (fr) TrWizard.dialectWanted().ifBlank { "فصحى" } else "فصحى", false, free = if (fr) "free" else "", only = only)   // (v425) وشبر + LibreTranslate · (v427) only = تفريغ بس بوشبر
            Cfg.put("tr_mode_id", if (only) "w_only" else if (fr) Strict.FREE_TXT else Strict.SEMI); SourceMode.set(SourceMode.AUDIO)
            try { TrWizard.save(pl) } catch (e: Throwable) { LogStore.err("Player:offQuickSave", e) }
            offBusy = false
            runPlan(pl)
        }
        fun stepMl() { startIt() }   // (v425) ML Kit اتشال
        fun go() {
            if (Offline.downloaded(m)) { stepMl(); return }
            say("⬇ بنحمّل موديل التفريغ " + m.label + " (~" + m.approxMb + " ميجا، مرة واحدة)…")
            Offline.download(m, { a, t -> runOnUiThread { Notice.show(this, "⬇ موديل التفريغ: " + (a / 1048576) + " / " + (t / 1048576) + " ميجا", 900L) } }) { e ->
                runOnUiThread { if (e != null) { offBusy = false; say("⚠ تنزيل موديل التفريغ فشل: $e") } else stepMl() }
            }
        }
        // (v413) لو في موديل مش نازل: شاشة صغيرة تسأل قبل التنزيل (بدل ما ينزّل لوحده)
        Thread {
            val needMl = false   // (v425)
            runOnUiThread {
                val needW = !Offline.downloaded(m)
                if (!needW && !needMl) { go(); return@runOnUiThread }
                val what = ArrayList<String>()
                if (needW) what.add("• موديل التفريغ Whisper " + m.label + " (~" + m.approxMb + " ميجا)")
                if (needMl) what.add("• موديل ترجمة اللغة (ML Kit ~30 ميجا)")
                android.app.AlertDialog.Builder(this).setTitle("⬇ محتاج تنزيل مرة واحدة")
                    .setMessage(what.joinToString("\n") + "\n\nمحتاج نت للتنزيل بس، وبعدها الأوفلاين بيشتغل من غير نت.")
                    .setPositiveButton("نزّل وابدأ") { _, _ -> go() }
                    .setNegativeButton("إلغاء") { _, _ -> offBusy = false }
                    .setOnCancelListener { offBusy = false }.show()
            }
        }.also { it.isDaemon = true }.start()
    }

    /** (v254) ▶ ترجمة قبل البداية: لو فيه إعداد محفوظ بيشتغل بيه على طول، وإلا بيسأل (أونلاين / أوفلاين ← لغة ← تفريغ ← ترجمة ← حفظ؟) */

    /** (v334) 🎙 فصل صوت الكلام عن الموسيقى — يدوي بس (VoiceClean: DPDFNet على قطع متوازية) */
    private fun voiceMenu() {
        try {
            val u = uri
            if (u == null || (u.scheme != "file" && u.scheme != "content")) { say("🎙 الفصل شغّال على الفيديو المحلي بس — نزّل الفيديو الأول"); return }
            val dur = player.duration.let { if (it > 0) it else 0L }
            val items = ArrayList<Pair<String, () -> Unit>>()
            val pb = voice
            val ready = VoiceClean.ready(this, u, dur)
            when {
                VoiceClean.busy -> items.add("⏹ وقّف الفصل اللي شغّال" to { VoiceClean.cancel() })
                VoiceClean.downloading() -> items.add("⏹ وقّف تنزيل الموديل" to { VoiceClean.cancelDownload() })
                pb != null && pb.active -> items.add("🔊 رجّع الصوت الأصلي" to { stopVoice() })
                ready != null -> items.add("🎙 شغّل صوت الكلام النضيف" to { startVoice(ready) })
                else -> items.add("🎙 ابدأ فصل صوت الكلام (" + VoiceClean.model().label + ")" to { startVoiceJob(u, dur) })
            }
            if (!VoiceClean.busy && !VoiceClean.downloading()) {
                for (m in VoiceClean.models) if (m.file != VoiceClean.model().file) items.add("⚙ المحرك: " + m.label + " — " + m.note to { VoiceClean.setModel(m.file); say("⚙ المحرك: " + m.label) })
                if (ready != null) items.add("🗑 امسح كل الصوت المفصول" to { stopVoice(); VoiceClean.deleteAll(this); say("🗑 اتمسح") })
            }
            android.app.AlertDialog.Builder(this).setTitle("🎙 فصل صوت الكلام").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
        } catch (e: Throwable) { LogStore.err("Player:voiceMenu", e); say("تعذّر فتح قايمة فصل الصوت") }
    }

    private fun startVoiceJob(u: Uri, dur: Long) {
        val m = VoiceClean.model()
        if (!VoiceClean.has(this, m)) {
            say("🎙 بنزّل موديل الفصل…")
            VoiceClean.download(this, m, { have, total -> runOnUiThread { voiceBtnRef?.text = if (total > 0) "⬇" + (have * 100 / total) + "%" else "⬇" + (have / 1_000_000) + "MB" } }) { err ->
                runOnUiThread {
                    voiceBtnRef?.text = "🎙"
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (err == null) startVoiceJob(u, dur) else say("🎙 التنزيل فشل: " + err)
                }
            }
            return
        }
        say("🎙 بدأ فصل صوت الكلام — الفيديو شغّال عادي لحد ما يخلص")
        VoiceClean.run(this, u, dur, { p -> runOnUiThread { voiceBtnRef?.text = "🎙" + p + "%" } }) { f, err ->
            runOnUiThread {
                voiceBtnRef?.text = "🎙"
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (f == null) { say("🎙 " + (if (err == "اتلغى") "اتلغى" else "فشل: " + err)); return@runOnUiThread }
                if (uri != u) { say("🎙 الفيديو اتغيّر — الصوت النضيف اتحفظ وهتلاقيه لما ترجع للفيديو"); return@runOnUiThread }
                say("✅ الصوت النضيف جاهز — بيشتغل دلوقتي"); startVoice(f)
            }
        }
    }

    private fun startVoice(f: File) {
        stopVoice()
        try { voice = VoiceClean.Playback(this, player.exo() ?: throw IllegalStateException("remote"), f).also { it.start() }; voiceBtnRef?.text = "🎙✓"; say("🎙 صوت الكلام النضيف شغّال") }
        catch (e: Throwable) { LogStore.err("Player:startVoice", e); stopVoice(); say("تعذّر تشغيل الصوت النضيف") }
    }

    private fun stopVoice() {
        try { voice?.stop() } catch (_: Throwable) {}
        voice = null; voiceBtnRef?.text = "🎙"
    }

    /** edit = true: جاي من زرار ⚙ (تغيير/مسح الإعداد من غير بدء ترجمة) */
    fun trWizard(edit: Boolean) {
        try {
            val oldLang = Offline.lang()   // (v413)
            TrWizard.keyModeHook = { n -> applyKeyMode(n) }   // (v275)
            TrWizard.sourceHook = { ed -> if (ed) say("💾 اتحفظ: " + SourceMode.line()) else runVisualOnly(); try { applyChromeFn() } catch (_: Throwable) {} }   // (v291)
            TrWizard.show(this, edit) { pl, save, start ->
                if (save) TrWizard.save(pl)
                if (start) runPlan(pl)
                else if (save) {
                    // (v399) تعديل الوضع (⚙) في نص الفيديو كان بيتحفظ بس ومابيوصلش للمحرك الشغّال (فالاستئناف يكمّل بالوضع القديم): دلوقتي بيتطبّق فورًا على المحرك من غير ما يبدأ الترجمة
                    val live = try { ::engine.isInitialized && (engineStarted || engine.subs.isNotEmpty()) } catch (_: Throwable) { false }
                    if (live) { runPlan(pl, false); say("💾 اتحفظ واتطبّق على الترجمة: " + TrWizard.describe(pl).replace("\n", " · ") + (if (engineStarted) "\n▶ دوس «إلغاء الإيقاف» تكمّل" else "")) }
                    else say("💾 اتحفظ: " + TrWizard.describe(pl).replace("\n", " · "))
                }
                if ((save || start) && pl.offline && pl.lang != oldLang) {   // (v413) غيّرت اللغة: كمّل من مكاني وصلّح الباتشات اللي باظت
                    try {
                        if (::engine.isInitialized && (engineStarted || engine.subs.isNotEmpty())) {
                            engine.langChanged(pl.lang, player.currentPosition.coerceAtLeast(0) / 1000.0)
                            say("🌐 اللغة بقت " + TrWizard.langLabel(pl.lang) + " — بكمّل من مكانك وبعيد الباتشات اللي اتفرّغت بلغة غلط")
                        }
                    } catch (e: Throwable) { LogStore.err("Player:langChanged", e) }
                }
                try { applyChromeFn() } catch (_: Throwable) {}   // (v360) ملخص الإعداد تحت اسم الفيديو
            }
        } catch (e: Throwable) { LogStore.err("Player:trWizard", e); say("تعذّر فتح شاشة طريقة الترجمة") }
    }

    /** بيطبّق الخطة على الإعدادات اللي المحرك بيقراها وبيبدأ الترجمة */
    /** (v291) بيشغّل الخطة وبعدها بيظبط البصري حسب المصدر (صوتي: يقفله · هجين: يشغّله بنوعه) */
    private fun runPlan(pl: TrWizard.Plan, startNow: Boolean = true) {
        runPlanCore(pl, startNow)
        if (!startNow) return   // (v399) تطبيق على المحرك بس: ماتلمسش الوضع البصري ولا تبدأ حاجة
        if (pl.onlineId == TrPresets.HARD_ID) return
        when (SourceMode.get()) {
            SourceMode.AUDIO -> try {
                Cfg.put("hard_mode", "0")
                if (::visual.isInitialized && visual.running) { visual.stop(); visOv.showBoxes(emptyList()); visAutoRun = false; visLog("🎙 صوتي بس — الوضع البصري اتقفل") }
            } catch (e: Throwable) { LogStore.err("Player:srcAudio", e) }
            SourceMode.HYBRID -> if (!Strict.noGemKeys()) {   // (v410) البصري جيميناي = أونلاين بس
                Cfg.put("hard_mode", if (SourceMode.kind() == "hard") "1" else "0")
                startVisualNow("🎙+👁 هجين · " + SourceMode.kindLabel())
            }
        }
    }

    /** (v291) 👁 بصري بس: الصوت واقف، والبصري بنوعه (نصوص الشاشة / هارد سب) */
    fun runVisualOnly() {
        try { if (::engine.isInitialized) { engine.transOnly = false; if (engineStarted) pauseTranslate() } } catch (_: Throwable) {}
        Cfg.put("hard_mode", if (SourceMode.kind() == "hard") "1" else "0")
        try { conf = Cfg.snapshot() } catch (e: Throwable) { LogStore.err("Player:visOnlySnapshot", e) }
        if (startVisualNow("👁 بصري بس · " + SourceMode.kindLabel())) say("👁 بصري بس · " + SourceMode.kindLabel() + " — الترجمة الصوتية واقفة")
    }

    private fun startVisualNow(tag: String): Boolean {
        try {
            if (!::visual.isInitialized) return false
            if (Strict.noGemKeys()) { say(Strict.NO_VISUAL_MSG); return false }   // (v410)
            if (!conf.visKeys.any { it.length > 10 }) { say("👁 ضيف مفتاح للوضع البصري الأول (الإعدادات ← 🔑 المفاتيح ← مفتاح الوضع البصري)"); return false }
            visAutoRun = false; visual.mode = "scene"
            if (!visual.running) visualStart()
            visLog(tag + " اتشغّل")
            return true
        } catch (e: Throwable) { LogStore.err("Player:startVisualNow", e); return false }
    }

    private fun runPlanCore(pl: TrWizard.Plan, startNow: Boolean = true) {
        TrWizard.apply(pl)
        appliedSig = planSig(pl)   // (v399)
        // (v297) اللهجة الفعلية بتتحط على المحرك في كل المسارات (كانت في الأوفلاين والسحابي بس) وبتظهر في إشعار واللوج
        try { if (::engine.isInitialized) engine.convDialect = Cfg.str("conv_dialect", "") } catch (_: Throwable) {}
        try {
            val eff = Cfg.str("conv_dialect", "").ifBlank { Cfg.str("lang", "فصحى") }.ifBlank { "فصحى" }
            LogStore.add("🗣 لهجة الترجمة الفعلية: " + eff + " (conv_dialect='" + Cfg.str("conv_dialect", "") + "' · lang='" + Cfg.str("lang", "") + "')")
            if (!pl.only) h.postDelayed({ try { Notice.show(this, "🗣 لهجة الترجمة: " + eff, 2600L) } catch (_: Throwable) {} }, 500L)
        } catch (_: Throwable) {}
        try { if (::engine.isInitialized) engine.transOnly = pl.only } catch (e: Throwable) { LogStore.err("Player:transOnly", e) }   // (v280) تفريغ بس
        if (pl.offline) {
            // الأوفلاين = وضع «نسخ ثم ترجمة سياقية» (Cfg.snapshot بيجبره لما Offline.on()): نفس إعدادات بريست الترانسكريبشن (مقاطع 60ث، من غير تنقيح ولا بصري)
            TrPresets.byId("transcript")?.let { TrPresets.apply(it) }
            if (pl.live) { Cfg.put("chunk", if (pl.engine == "sherpa") "20" else "30"); Cfg.put("trstart", "0") }   // (v266) لحظي: باتشات قصيرة تبدأ من مكان التشغيل
            try { val nc = Cfg.snapshot(); conf = nc; if (::engine.isInitialized) engine.applyConf(nc) } catch (e: Throwable) { LogStore.err("Player:runPlan", e) }
            for ((v, f) in kmLabels) v.text = f(KeyMode.clamp(Cfg.int("kmode", 3)))
            if (::engine.isInitialized) engine.convDialect = Cfg.str("conv_dialect", "")   // (v257) اللهجة اللي اخترتها في الشاشة
            LogStore.add("🎛 طريقة الترجمة: أوفلاين — لغة=" + pl.lang.ifEmpty { "تلقائي" } + " · محرك=" + pl.engine + " · موديل=" + (if (pl.engine == "sherpa") "SenseVoice" else pl.model) + " · ترجمة=" + pl.method + " · لهجة=" + pl.dialect.ifBlank { "فصحى" })
            if (startNow) say(if (pl.only) "✍ أوفلاين · " + TrWizard.langLabel(pl.lang) + " · تفريغ بس (من غير ترجمة)" else "📴 أوفلاين · " + TrWizard.langLabel(pl.lang) + " · " + (if (pl.method == "google") "ترجمة جوجل" else "ترجمة جيميناي") + "\n" + ExtTranslate.setupLine())
            if (startNow) beginTranslate() else engineKick()
            if (startNow) TrWizard.startNotices(pl).forEachIndexed { i, m -> h.postDelayed({ try { Notice.show(this, m, 4500L) } catch (e: Throwable) { LogStore.err("Player:startNotices", e) } }, 600L + i * 3200L) }   // (v255)
        } else if (pl.cloud) {
            // (v274) أونلاين بمحرك خارجي: نفس مسار «نسخ ثم ترجمة سياقية» (التفريغ سحابي) والترجمة بالسلسلة: المحرك المختار ← الباقي ← جوجل ← جيميناي بمفاتيحك
            TrPresets.byId("transcript")?.let { TrPresets.apply(it) }
            try { val nc = Cfg.snapshot(); conf = nc; if (::engine.isInitialized) engine.applyConf(nc) } catch (e: Throwable) { LogStore.err("Player:runPlan", e) }
            for ((v, f) in kmLabels) v.text = f(KeyMode.clamp(Cfg.int("kmode", 3)))
            if (::engine.isInitialized) engine.convDialect = Cfg.str("conv_dialect", "")
            LogStore.add("🎛 طريقة الترجمة: أونلاين بمحرك خارجي — ترجمة=" + pl.ext + " · تفريغ=" + (TrWizard.sttCandidate(pl.ext)?.id ?: "؟") + " · السلسلة: " + ExtTranslate.chainLabel())
            if (startNow) say(if (pl.only) "✍ تفريغ سحابي بس (من غير ترجمة)" else "☁ " + (ExtLlm.byId(pl.ext)?.label ?: pl.ext) + " · تفريغ سحابي\n" + ExtTranslate.setupLine())
            if (startNow) beginTranslate() else engineKick()
            if (startNow) TrWizard.startNotices(pl).forEachIndexed { i, m -> h.postDelayed({ try { Notice.show(this, m, 4500L) } catch (e: Throwable) { LogStore.err("Player:startNotices", e) } }, 600L + i * 3200L) }
        } else {
            val pr = TrPresets.byId(pl.onlineId)
            if (pr != null) applyPreset(pr, pl.only, false, startNow)
            else {
                try { val nc = Cfg.snapshot(); conf = nc; if (::engine.isInitialized) engine.applyConf(nc) } catch (e: Throwable) { LogStore.err("Player:runPlan", e) }
                if (startNow) beginTranslate() else engineKick()
            }
        }
    }

    private var presetPop: android.widget.PopupWindow? = null

    /** (v297) اختيار بريست والترجمة شغّالة/موجودة: يتطبّق على الباتشات الجاية بس (اللي اتترجم يفضل) ولا يعيد من الأول خالص (القديمة بتتحفظ في 🗂) */

    private fun applyPreset(pr: TrPresets.P, keepOnly: Boolean = false, fresh: Boolean = false, startNow: Boolean = true) {
        if (Strict.noGemini()) { Cfg.put("tr_mode_id", ""); Cfg.put("stt_offline", "0") }   // (v410) بريست أونلاين = جيميناي: اخرج من نص-أونلاين/أوفلاين
        if (!keepOnly) try { if (::engine.isInitialized) engine.transOnly = false } catch (_: Throwable) {}   // (v280)
        val kv = TrPresets.apply(pr)
        if (pr.id == TrPresets.HARD_ID) {
            // (v240) وضع ترجمة الهارد سب: بصري بس — الترجمة الصوتية واقفة، والوضع البصري بيترجم الهارد سب
            try { if (::engine.isInitialized && engineStarted) pauseTranslate() } catch (_: Throwable) {}
            try { conf = Cfg.snapshot() } catch (e: Throwable) { LogStore.err("Player:hardSnapshot", e) }   // (v287)
            try {
                if (::visual.isInitialized) {
                    if (!conf.visKeys.any { it.length > 10 }) { say("🔤 ضيف مفتاح للوضع البصري الأول (الإعدادات ← 🔑 المفاتيح ← مفتاح الوضع البصري)"); return }
                    visAutoRun = false; visual.mode = "scene"
                    if (visual.running) visual.stop()
                    visualStart(); visLog("🔤 وضع ترجمة الهارد سب اتشغّل")
                }
            } catch (e: Throwable) { LogStore.err("Player:hardMode", e) }
            say("🔤 وضع ترجمة الهارد سب شغّال — الترجمة الصوتية واقفة")
            return
        }
        try { val nc = Cfg.snapshot(); conf = nc; if (::engine.isInitialized) engine.applyConf(nc) } catch (e: Throwable) { LogStore.err("Player:preset", e) }
        for ((v, f) in kmLabels) v.text = f(KeyMode.clamp(Cfg.int("kmode", 3)))
        // الوضع البصري: يتظبط حسب البريست (يتقفل لو البريست قافله، ويشتغل لو فاتحه وفيه مفتاح بصري)
        try {
            if (::visual.isInitialized) {
                if (Cfg.str("vis_auto", "1") == "1") {
                    if (!visual.running && conf.visKeys.any { it.length > 10 } && !incognito) { visAutoRun = true; visual.mode = "scene"; visualStart(); visLog("👁 اتشغّل مع بريست " + pr.name) }
                } else if (visual.running) { visual.stop(); visOv.showBoxes(emptyList()); visAutoRun = false; visLog("👁 اتقفل مع بريست " + pr.name) }
            }
        } catch (e: Throwable) { LogStore.err("Player:presetVis", e) }
        LogStore.add("🎛 بريست الترجمة: " + pr.name + " — " + kv.entries.joinToString(" · ") { it.key + "=" + it.value })
        say(pr.icon + " " + pr.name + " — اتظبطت كل الإعدادات")
        if (fresh) { try { player.seekTo(0) } catch (_: Throwable) {}; redoGo("🔄 بترجم من الأول بالبريست: " + pr.name) { engine.redoAll(true) } } else if (startNow) beginTranslate() else engineKick()
    }

    fun langPopup(anchor: View) {
        langPop?.let { if (it.isShowing) { it.dismiss(); langPop = null; return } }
        if (!::engine.isInitialized) { say("استنى لحظة — الفيديو لسه بيتحمّل"); return }   // (v262) engine lateinit: الضغط قبل التحميل كان بيكرّش
        if (!caps().dialect) { say("🗣 اللهجة والأسلوب مش شغّالين في «" + modeName() + "» — اختار وضع من اللي فيها لهجات (FreeLLM / وشبر + مترجم LLM)"); return }   // (v444)
        val langs = listOf("فصحى", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني")
        val curL = Cfg.str("conv_dialect", "").ifBlank { "فصحى" }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = glassPanelBg()
        }
        // (v134) الشكل بقى صفوف أفقية: اللهجات جنب بعض · تحتها الأسلوب والشدة · تحتها أزرار التطبيق (كانت عمود طويل)
        fun flow() = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun cLp() = ViewGroup.MarginLayoutParams(-2, ui.dp(34)).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) }
        fun lbl(t: String) = ui.text(t, 11f, 0xFF9AA0A6.toInt()).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), 0, ui.dp(4), 0) }
        val rowL = flow()
        for (l in langs) rowL.addView(ui.fsBtn((if (l == curL) "✓ " else "") + (if (l == "فصحى") "فصحى (حرفية)" else l)) { _ ->
            langPop?.dismiss()
            val v = if (l == "فصحى") "" else l
            Cfg.p.edit().putString("conv_dialect", v).apply()
            TrWizard.syncSaved("conv_dialect", v)
            engine.setConvDialect(v, true)
            if (v.isNotEmpty() && v == conf.lang) { say("لهجة الترجمة في الإعدادات أصلًا $v — مفيش تحويل مطلوب"); touchSubs(); return@fsBtn }
            say(if (l == "فصحى") "الترجمة بالفصحى الحرفية" else "هحوّل للهجة $l من الباتش اللي إنت فيه وللقدّام، وبعدين اللي قبله — والباتشات الجديدة هتيجي باللهجة دي (النسخة القديمة اتحفظت في 🗂)")
            touchSubs()
        }, cLp())
        col.addView(rowL, LinearLayout.LayoutParams(-1, -2))
        // أسلوب الترجمة وشدته: بيتحفظوا وبيدخلوا في برومبت كل باتش جديد وكل تحويل لهجة، ومعاهم زرار تطبيق على اللي اتترجم فعلًا
        // (v134) الأسلوب والشدة بقوا سلايدرين بدل أزرار — بتحرّك واللي بتقف عليه بيتحفظ
        val toneStyles = listOf("حرفي", "شعبي", "جرئ", "+18"); val toneDegrees = listOf("خفيفة", "متوسطة", "شديدة")
        var stI = toneStyles.indexOf(Cfg.str("tone_style", "").ifBlank { "حرفي" }).coerceAtLeast(0)
        var dgI = toneDegrees.indexOf(Cfg.str("tone_strength", "متوسطة")).let { if (it < 0) 1 else it }
        fun curStyle() = toneStyles[stI]
        fun curDeg() = toneDegrees[dgI]
        val styleLbl = lbl(""); val degLbl = lbl("")
        fun mkSb(max: Int, p: Int) = android.widget.SeekBar(this).apply {
            this.max = max; progress = p; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
            val tint = android.content.res.ColorStateList.valueOf(th.primary)
            progressTintList = tint; thumbTintList = tint
        }
        val styleSb = mkSb(toneStyles.size - 1, stI); val degSb = mkSb(toneDegrees.size - 1, dgI)
        fun paintTone() {
            styleLbl.text = "الأسلوب:  " + curStyle() + "   (حرفي ◂ شعبي ◂ جرئ ◂ +18)"
            degLbl.text = "الشدة:  " + curDeg() + if (stI == 0) "   (بتشتغل مع شعبي / جرئ / +18 بس)" else ""
            degSb.alpha = if (stI == 0) 0.4f else 1f
        }
        styleSb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                stI = p; val v = if (p == 0) "" else toneStyles[p]; Cfg.put("tone_style", v); engine.toneStyle = v; paintTone()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                say(if (stI == 0) "الأسلوب حرفي — الباتشات الجاية من غير تعديل أسلوب" else "الأسلوب ${curStyle()} (${curDeg()}) — الباتشات الجاية هتتترجم بيه. لو عايزه على اللي اتترجم دوس «طبّق»")
            }
        })
        degSb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                dgI = p; Cfg.put("tone_strength", toneDegrees[p]); engine.toneStrength = toneDegrees[p]; paintTone()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                say(if (stI == 0) "الشدة بتشتغل مع شعبي / جرئ / +18 بس" else "الشدة ${curDeg()} — الباتشات الجاية هتتترجم بيها")
            }
        })
        col.addView(styleLbl, LinearLayout.LayoutParams(-1, -2)); col.addView(styleSb, LinearLayout.LayoutParams(-1, -2))
        col.addView(degLbl, LinearLayout.LayoutParams(-1, -2)); col.addView(degSb, LinearLayout.LayoutParams(-1, -2))
        paintTone()
        fun toneInstr(): String {
            val st = curStyle()
            val what = when (st) { "شعبي" -> "كلام الشارع الشعبي والتعبيرات اليومية بدل الرسمي"; "جرئ" -> "أجرأ وأكتر حرية في الألفاظ من غير تلطيف زيادة"; "+18" -> "صريح بلا تلطيف: الألفاظ والإيحاءات تطابق صراحة الأصل بالظبط"; else -> "" }
            return if (what.isEmpty()) "رجّع كل جملة لترجمة حرفية أمينة قريبة من الأصل من غير مبالغة في العامية أو الجرأة."
            else "أعد صياغة كل جملة بأسلوب «$st»: $what. الشدة: ${curDeg()}. حافظ على المعنى والجنس."
        }
        val rowA = flow()
        rowA.addView(ui.fsBtn("🔁 طبّق من هنا للآخر") { _ -> langPop?.dismiss(); runTool("أسلوب " + curStyle() + " (" + curDeg() + ")", toneInstr(), false) }, cLp())
        rowA.addView(ui.fsBtn("🔁 طبّق على الفيديو كله") { _ -> langPop?.dismiss(); runTool("أسلوب " + curStyle() + " (" + curDeg() + ")", toneInstr(), true) }, cLp())
        col.addView(rowA, LinearLayout.LayoutParams(-1, -2))
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val popW = minOf(resources.displayMetrics.widthPixels - ui.dp(24), ui.dp(430))
        col.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        val up = loc[1] > screenH / 2   // الزرار في النص التحتاني (الشريط السفلي) → القايمة تفتح فوقه
        val avail = (if (up) loc[1] - ui.dp(12) else screenH - loc[1] - anchor.height - ui.dp(12)).coerceAtLeast(ui.dp(120))
        val popH = minOf(col.measuredHeight, avail)
        val pw = TvFocus.pop(this@PlayerActivity, scroll, popW, popH, true)
        Motion.popIn(scroll, up)   // (v445)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (langPop === pw) langPop = null; showChrome() }
        if (up) pw.showAsDropDown(anchor, 0, -(anchor.height + popH + ui.dp(2))) else pw.showAsDropDown(anchor, 0, ui.dp(2))
        langPop = pw
        h.removeCallbacks(hideChrome); showChrome(); h.removeCallbacks(hideChrome)   // الشريط يفضل ظاهر وإنت بتختار
    }

    /** كبسولة جانبية: باتش ما اترجمش / رجع ناقص + إعادة على المفاتيح الاحتياطية. مخفية لحد ما يحصل فشل */
    lateinit var probBar: TextView
    lateinit var probHandle: TextView
    lateinit var probDrawer: LinearLayout
    private var probOn = false
    private var probIdx = -1
    fun toggleProb() {
        probOn = !probOn
        probDrawer.animate().translationX(if (probOn) 0f else -(probBar.width + ui.dp(2)).toFloat()).setDuration(220).start()
        probHandle.text = if (probOn) "◂" else "⚠"
        fadeTo(probHandle, if (probOn) 1f else PROB_A)
        h.removeCallbacks(probAuto); if (probOn) h.postDelayed(probAuto, 8000)
    }
    fun updateProblems() {
        val p = try { engine.problems() } catch (_: Exception) { emptyList() }
        if (p.isEmpty() || pipNow()) {
            probIdx = -1
            if (probDrawer.visibility != View.GONE) { probDrawer.visibility = View.GONE; probOn = false; probHandle.text = "⚠" }
            return
        }
        val b = p[0]; probIdx = b.idx
        val what = if (b.mark == "❌") "ما اترجمش" else "رجع ناقص"
        probBar.text = "باتش ${b.idx + 1} $what" + (if (p.size > 1) " (+${p.size - 1})" else "") + " · 🔁 إعادة؟"
        if (probDrawer.visibility != View.VISIBLE) {
            probDrawer.visibility = View.VISIBLE; probOn = false; probHandle.text = "⚠"
            probDrawer.post { probDrawer.translationX = -(probBar.width + ui.dp(2)).toFloat() }
        }
    }
    /** زرار الباتش الفاشل: من غير سؤال — بيعيد على طول وإشعار صغير (والإعادة التلقائية شغالة لوحدها كمان) */
    fun askRetryProblem() {
        val i = probIdx; if (i < 0) return
        val n = try { engine.retryProblems() } catch (_: Exception) { 0 }
        if (probOn) toggleProb()
        if (n > 0) Notice.show(this, if (n == 1) "🔁 بعيد ترجمة باتش ${i + 1}" else "🔁 بعيد ترجمة $n باتش", 2200L)
    }


    /** يرجّع نسخة محفوظة ويظبط لهجة الترجمة الجاية على لهجتها */
    fun restoreVersion(i: Int) {
        val v = engine.versions.getOrNull(i) ?: return
        val n = engine.applyVersion(i)
        val d = if (v.dialect == "فصحى") "" else v.dialect
        Cfg.p.edit().putString("conv_dialect", d).apply()
        TrWizard.syncSaved("conv_dialect", d)
        engine.adoptDialect(d)
        say("اتطبّقت نسخة «${v.name}» (اتغيّرت $n جملة)"); touchSubs()
    }
    private var verPop: android.widget.PopupWindow? = null
    /** قايمة صغيرة جنب عداد الجمل: الترجمات المحفوظة ولهجة كل واحدة — دوس على واحدة تبدّل ليها */
    fun versionsPopup(anchor: View) {
        verPop?.let { if (it.isShowing) { it.dismiss(); verPop = null; return } }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = glassPanelBg()
        }
        val curD = Cfg.str("conv_dialect", "").ifBlank { "فصحى" }
        col.addView(ui.text("🗂 الترجمات — الحالية: $curD", 11f, th.muted).apply { setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(4)) })
        val vs = engine.versions.toList()
        if (vs.isEmpty()) col.addView(ui.text("مفيش نسخ لسه — أول ما تغيّر اللهجة بتتحفظ النسخة القديمة هنا", 12f, th.muted).apply { setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)) })
        vs.forEachIndexed { i, v ->
            col.addView(ui.fsBtn("${v.dialect.ifBlank { "—" }} · ${v.subs.size} جملة" + (if (v.name.startsWith("نسخة") || v.name.startsWith("قبل") || v.name.startsWith("بعد")) " · ${v.name}" else "")) { _ ->
                verPop?.dismiss(); restoreVersion(i)
            }.apply { minimumWidth = ui.dp(190) })
        }
        col.addView(ui.fsBtn("💾 احفظ الحالية") { _ -> verPop?.dismiss(); engine.saveVersion("نسخة " + fmtMs(player.currentPosition).substring(3)); say("اتحفظت النسخة الحالية") }.apply { minimumWidth = ui.dp(190) })
        col.addView(ui.fsBtn("🗂 كل النسخ…") { _ -> verPop?.dismiss(); versionsDialog() }.apply { minimumWidth = ui.dp(190) })
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        col.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(220), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val pw = TvFocus.pop(this@PlayerActivity, scroll, ui.dp(220), minOf(col.measuredHeight, maxH), true)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (verPop === pw) verPop = null; showChrome() }
        verPop = pw
        pw.showAsDropDown(anchor, -(ui.dp(220) - anchor.width), ui.dp(2))
    }

    fun versionsDialog() {
        val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("🗂 ترجمات الفيديو (نسخ محفوظة في الجلسة)", 16f, th.primary, true))
        box.addView(ui.text("أي إعادة صياغة بتحفظ نسخة قبلها تلقائيًا. اضغط على نسخة عشان ترجّع نص الترجمة بتاعها.", 12f, th.muted))
        box.addView(ui.button("💾 احفظ النسخة الحالية") { engine.saveVersion("نسخة " + fmtMs(player.currentPosition).substring(3)); d.dismiss(); versionsDialog() })
        val vs = engine.versions.toList()
        if (vs.isEmpty()) box.addView(ui.text("مفيش نسخ محفوظة لسه", 13f, th.muted))
        vs.forEachIndexed { i, v ->
            box.addView(ui.button("${v.name} — ${v.dialect.ifBlank { "—" }} — ${v.subs.size} جملة") { restoreVersion(i); d.dismiss() })
        }
        d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        d.show()
    }

    fun recapDialog() {
        say("📜 بجهّز الملخص…")
        engine.recap(player.currentPosition / 1000.0, { m -> runOnUiThread { giShowFn(m) } }) { txt ->
            runOnUiThread {
                val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
                box.addView(ui.text("📜 ذكّرني بالأحداث", 16f, th.primary, true))
                box.addView(ui.text(txt, 14f, th.text).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)); setTextIsSelectable(true) })
                box.addView(ui.button("تمام") { d.dismiss() })
                d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
                d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
                ui.fullPage(d)
                d.show()
            }
        }
    }

    fun makeRetriever(): android.media.MediaMetadataRetriever? {
        return try {
            val r = android.media.MediaMetadataRetriever(); val u = uri; val l = url
            if (u != null) r.setDataSource(this, u) else if (l != null && !l.contains(".m3u8", true)) r.setDataSource(l, HashMap(hdr)) else return null
            r
        } catch (e: Exception) { try { visLog("👁 المسح من الملف مش ممكن (" + e.javaClass.simpleName + ": " + (e.message ?: "").take(70) + ") — بلقط الفريمات من الشاشة بدل كده") } catch (_: Throwable) {}; null }
    }


    fun visualSnap() {
        if (!::svRef.isInitialized || svRef.width <= 0 || svRef.height <= 0) { Notice.show(this, ("مفيش فيديو شغّال").toString(), 2300L); return }
        if (visBusy) { Notice.show(this, ("👁 لسه بترجم اللقطة اللي فاتت…").toString(), 2300L); return }
        visBusy = true
        val tok = ++visTok
        h.postDelayed({ if (visBusy && visTok == tok) { visBusy = false;  } }, 60_000)
        val wasPlaying = try { player.playWhenReady } catch (_: Exception) { false }
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:2522", e) }
        val curUs = player.currentPosition * 1000
        val t0 = (player.currentPosition - offsetMs) / 1000.0
                val done = { runOnUiThread { visBusy = false; if (wasPlaying && !isFinishing && !isDestroyed) try { player.play() } catch (e: Exception) { LogStore.err("Main:2525", e) } } }
        fun fallback() {
            Thread {
                val r = makeRetriever()
                val b = try { r?.getFrameAtTime(curUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                try { r?.release() } catch (e: Exception) { LogStore.err("Main:2530", e) }
                if (b != null) visual.snap(b, { t0 }, done) else { done() }
            }.start()
        }
        val bmp = android.graphics.Bitmap.createBitmap(svRef.width, svRef.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res -> if (res == android.view.PixelCopy.SUCCESS) visual.snap(bmp, { t0 }, done) else fallback() }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { fallback() }
    }
    private var visBusy = false
    /** (v182) تسجيل في اللوج من غير ما يلمس سطر الحالة بتاع الترجمة */
    private fun visLog(m: String) {
        try {
            synchronized(logBuf) { logBuf.append(fmtMs(cur)).append("  ").append(m).append('\n'); if (logBuf.length > 30000) logBuf.delete(0, 10000) }
            LogStore.add(fmtMs(cur) + "  " + m)
        } catch (_: Throwable) {}
    }
    /** (v182) لقطة فريم من سطح الفيديو (PixelCopy) بحجم أقصاه 640 — بتتنادى من خيط الوضع البصري، فيديوهات m3u8 */
    fun grabSurface(maxDim: Int = 960): android.graphics.Bitmap? {
        if (!::svRef.isInitialized) return null
        val sw = svRef.width; val sh = svRef.height
        if (sw <= 0 || sh <= 0 || !svRef.holder.surface.isValid) return null
        val sc = minOf(1f, maxDim.toFloat() / maxOf(sw, sh))
        val bmp = android.graphics.Bitmap.createBitmap((sw * sc).toInt().coerceAtLeast(2), (sh * sc).toInt().coerceAtLeast(2), android.graphics.Bitmap.Config.ARGB_8888)
        val latch = java.util.concurrent.CountDownLatch(1); val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res -> ok.set(res == android.view.PixelCopy.SUCCESS); latch.countDown() }, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) { bmp.recycle(); return null }
        val done = try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!done || !ok.get()) { bmp.recycle(); return null }
        return bmp
    }
    private var visSayAt = 0L
    /** (v330) الوضع البصري كان بياخد نسخة الإعدادات القديمة (conf) من وقت فتح المشغّل → مفتاح ضفته بعدها ما كانش بيشوفه فالحلقة بتقف فورًا. دلوقتي نسخة جديدة كل ~1.5 ثانية (من غير ما نلمس conf بتاعة المحرك) */
    @Volatile private var visConfV: Conf? = null
    @Volatile private var visConfAt = 0L
    private fun visConfNow(): Conf {
        val n = android.os.SystemClock.uptimeMillis(); val c = visConfV
        if (c == null || n - visConfAt > 1500L) { try { visConfV = Cfg.snapshot(); visConfAt = n } catch (_: Throwable) {} }
        return visConfV ?: conf
    }
    private lateinit var visBar: TextView
    private var visBarTxt = ""
    private var visAutoRun = false
    private var visTok = 0

    /**
     * (v358) لوحة الوضع البصري الصغيرة: زرار تشغيل/إيقاف صغير + «⋯» للقايمة الكاملة، وتحتهم جمل الوضع البصري بتوقيتها —
     * دوسة على جملة بتنقلك لمكانها في الفيديو. بتتحدّث لوحدها وهي مفتوحة والجملة الحالية متظلّلة.
     */
    fun visualDialog() {
        val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        visual.mode = "scene"
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.box(th.card, th.border, 18) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val title = ui.text("👁 الوضع البصري", 15f, th.primary, true)
        fun chip(t: String, f: () -> Unit) = IconTextView(this).apply {
            text = t; textSize = 12.5f; gravity = Gravity.CENTER; setTextColor(th.text); minHeight = ui.dp(34)
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); background = ui.box(th.surface, th.border, 10); setOnClickListener { f() }
        }
        val toggle = chip("") { }
        fun paintToggle() { toggle.text = if (visual.running) "⏹ إيقاف" else "▶ تشغيل" }
        toggle.setOnClickListener {
            if (visual.running) { visual.stop(); visLog("👁 الوضع البصري اتقفل (اللوحة)") } else { visual.mode = "scene"; visLog("👁 الوضع البصري اتشغّل (اللوحة)"); visualStart() }
            paintToggle()
        }
        paintToggle()
        val more = chip("⋯") { d.dismiss(); visualOptionsDialog() }
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })
        head.addView(more, LinearLayout.LayoutParams(-2, -2))
        box.addView(head, LinearLayout.LayoutParams(-1, -2))
        val info = ui.text("", 11f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        box.addView(info, LinearLayout.LayoutParams(-1, -2))
        updVis()
        val empty = ui.text("مفيش جمل بصرية لسه — شغّل الوضع البصري وهتظهر هنا بتوقيتها.", 13f, th.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)) }
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val lst = object : ListView(this) { override fun onMeasure(w: Int, hh: Int) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)) } }   // طول على قد الجمل لحد نص الشاشة
        var curV = -1
        val ad = object : BaseAdapter() {
            override fun getCount() = visList.size
            override fun getItem(i: Int): Any = visList[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
                val ve = visList[i]
                val row = LinearLayout(this@PlayerActivity).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(2), 0, ui.dp(2)) }
                val bar = View(this@PlayerActivity).apply { setBackgroundColor(0xFF3B82F6.toInt()) }
                val col = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
                col.addView(ui.text("⏱ " + PlayerLogic.clock((ve.start * 1000).toLong()), 10f, th.muted))
                col.addView(ui.text(if (ve.translated.isEmpty()) ve.original else ve.translated, 14f, th.text))
                if (ve.original.isNotBlank() && ve.original != ve.translated) col.addView(ui.text(ve.original, 11f, th.muted))
                row.addView(bar, LinearLayout.LayoutParams(ui.dp(4), -1)); row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                row.setBackgroundColor(if (i == curV) (th.primary and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
                return row
            }
        }
        lst.adapter = ad; lst.divider = android.graphics.drawable.ColorDrawable(th.border); lst.dividerHeight = 1
        lst.setOnItemClickListener { _, _, i, _ -> visList.getOrNull(i)?.let { player.seekTo((it.start * 1000).toLong()); d.dismiss() } }
        box.addView(lst, LinearLayout.LayoutParams(-1, -2))
        box.addView(empty, LinearLayout.LayoutParams(-1, -2))
        box.addView(chip("إغلاق") { d.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        lateinit var tick: Runnable
        fun refresh() {
            updVis()
            val c = cur / 1000.0
            val nv = visList.indexOfLast { it.start <= c }
            if (nv != curV) curV = nv
            ad.notifyDataSetChanged()
            val none = visList.isEmpty()
            lst.visibility = if (none) View.GONE else View.VISIBLE; empty.visibility = if (none) View.VISIBLE else View.GONE
            info.text = "عدد الجمل: " + visList.size + (if (!visual.running && visual.status.isNotBlank() && !visual.status.startsWith("👁")) "  ·  " + visual.status.take(60) else "")
            paintToggle()
        }
        tick = Runnable { if (d.isShowing) { refresh(); h.postDelayed(tick, 1500L) } }
        d.setOnDismissListener { h.removeCallbacks(tick) }
        d.setContentView(box)
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        refresh(); d.show(); h.postDelayed(tick, 1500L)
        // أول ما تفتح: انزل للجملة الحالية
        if (curV >= 0) lst.post { try { lst.setSelection((curV - 1).coerceAtLeast(0)) } catch (_: Throwable) {} }
    }

    /** القايمة الكبيرة القديمة (إعادة من الأول / من مكان التشغيل / لقطة / سجل / مسح) — بتتفتح من «⋯» جوه اللوحة الصغيرة */
    fun visualOptionsDialog() {
        val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("👁 الوضع البصري", 17f, th.primary, true))
        box.addView(ui.text("☁ Gemini: بيلقط فريم كل ثانية، بيتخطّى اللي مفيهوش تغيير أو نص، وبيجمّع الباقي في دفعات (30 فريم) تتقسم لمجموعات من 6 فريمات في صورة شبكة واحدة وتتبعت لجيميناي بالتوازي. الترجمة بتتعرض في مكان النص الأصلي، والترجمة الصوتية بتطلع فوق النص البصري لحد ما يختفي.\nبيشتغل تلقائي مع فتح الفيديو، والنتايج بتتحفظ مع الفيديو وترجع لما تفتحه تاني. ضغطة على 👁 = لقطة واحدة وتتحفظ.\nm3u8: بيلقط فريمات من الشاشة وقت التشغيل (النتيجة بتتأخر شوية).", 12f, th.muted))
        visual.mode = "scene"
        val st = ui.text(if (visual.running) "الحالة: شغّال — " + visual.status else "الحالة: واقف", 13f, th.text)
        box.addView(st)
        box.addView(ui.button(if (visual.running) "⏹ إيقاف" else "▶ تشغيل", true) {
            if (visual.running) { visual.stop(); visLog("👁 الوضع البصري اتقفل (القايمة)") } else {
                visLog("👁 الوضع البصري اتشغّل (القايمة)"); visualStart()
            }
            d.dismiss()
        })
        // (v185) إعادة الوضع البصري: من الأول (يمسح كل النتايج المحفوظة) أو من مكان التشغيل وقدّام بس
        box.addView(ui.button("🔁 إعادة الوضع البصري من الأول") {
            visual.stop(); visual.clear(); visOv.showBoxes(emptyList()); visual.mode = "scene"; visualStart()
            visLog("👁 إعادة الوضع البصري من الأول (القايمة)"); Notice.show(this, "👁 بيعيد تحليل الفيديو من الأول", 2300L); d.dismiss()
        })
        box.addView(ui.button("↪ إعادة من مكان التشغيل وقدّام") {
            val t = cur / 1000.0
            visual.stop(); visual.clearFrom(t); visOv.showBoxes(emptyList()); visual.mode = "scene"; visualStart()
            visLog("👁 إعادة الوضع البصري من " + fmtMs(cur) + " (القايمة)"); Notice.show(this, "👁 بيعيد التحليل من مكان التشغيل", 2300L); d.dismiss()
        })
        box.addView(ui.button("📸 لقطة واحدة دلوقتي") { d.dismiss(); visualSnap() })
        box.addView(ui.button("📜 سجل الترجمات البصرية والأحداث") { d.dismiss(); visualLogDialog() })
        box.addView(ui.button("🗑 مسح النتائج") { visual.clear(); visOv.showBoxes(emptyList()); d.dismiss() })
        d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        d.show()
    }

    /** (v206) سجل الوضع البصري: جدول الترجمات (من→إلى) + أحداث الظهور/التمديد/الرجوع/الاختفاء، مع نسخ */
    fun visualLogDialog() {
        val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("📜 سجل الوضع البصري", 17f, th.primary, true))
        val body = "🗺 الترجمات (" + visual.count + "):\n" + visual.tableText() + "\n\n🧬 الأحداث:\n" + visual.eventsText()
        box.addView(ui.text(body, 12f, th.text))
        box.addView(ui.button("📋 نسخ") {
            try { (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("visual", body)); Notice.show(this, "اتنسخ", 1500L) } catch (_: Throwable) {}
        })
        box.addView(ui.button("إغلاق") { d.dismiss() })
        d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        d.show()
    }

    /** الإعدادات من المشغّل: بتفتح شاشة الإعدادات فوق الفيديو من غير ما تقفله — الرجوع (Back) بيرجّعك للفيديو */
    private var settingsOpened = false
    /** (v124) تشغيل من تبويب متخفي: مفيش «فيديوهات الإنترنت المحفوظة» ولا «الأخيرة» ولا لقطة مصغّرة ولا ترجمة خلفية */
    private val incognito: Boolean by lazy { intent?.getBooleanExtra("incognito", false) == true }
    /** (v433) الفيديو جاي من موقع محجوب بزرار 🚨: بيتسجل في المصطادة مخفي (بالقفل) ومايدخلش «الأخيرة» ولا السجل العام */
    private val hiddenSite: Boolean by lazy {
        try {
            intent?.getBooleanExtra("hsite", false) == true ||
                listOf(intent?.getStringExtra("url"), intent?.getStringExtra("ref")).any { u -> SiteBlock.keyOf(u)?.let { k -> SiteBlock.has(this, k) } == true }
        } catch (_: Throwable) { false }
    }
    fun openSettings(tab: String? = null) {
        settingsOpened = true
        resumeAfterSettings = if (remote) false else try { player.isPlaying } catch (_: Exception) { false }
        if (!remote) try { player.pause() } catch (e: Exception) { LogStore.err("Main:2566", e) }   // (v568) وضع المتحكم: فتح الإعدادات ما بيوقّفش التلفزيون
        saveRecent(); Thread { engine.saveNow() }.start()
        // (v100) نفس اتجاه المشغّل بالظبط (من غير روتيشن) ومن غير حركة انتقال — كان بيجبر العرض فيعمل نتشة ويرجع
        startActivity(Intent(this, MainActivity::class.java).putExtra("from_player", true).putExtra("land", isLandNow()).apply { if (tab != null) putExtra("tab", tab) })
        try { overridePendingTransition(0, 0) } catch (e: Exception) { LogStore.err("Main:2570", e) }
    }
    override fun onResume() {
        super.onResume(); internalNav = false; resumedNow = true; h.removeCallbacks(pipExitCheck)
        tvWarmApply()   // (v462)
        trEnsureBtn()   // (v463) زرار لوحة النص في وضع التابلت
        WebMute.pauseAll()   // (v149) أي صوت WebView (متصفح/يوتيوب) يتقفل أول ما المشغّل يظهر
        if (audioOnlyMode) {   // رجعنا من وضع «صوت بس»: المحرك كان موقوف، نجهّزه من جديد (الترجمة بتبدأ لما تدوس ترجم)
            audioOnlyMode = false; engineStarted = false
            try { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}   // (v468) رجعنا لوضع الفيديو: الشاشة تفضل صاحية
            try { initEngine(); say("الترجمة كانت موقوفة في وضع الصوت — دوس ترجم لو عايز تكمّل") } catch (e: Throwable) { LogStore.err("Player:audioResume", e) }
        }
        if (Cfg.str("theme", "mx") != th.id) { recreate(); return }
        if (settingsOpened) {
            settingsOpened = false
            // (v122) رجعنا من الإعدادات: اللهجة / الأسلوب / الموديل / الشخصيات / المسرد لازم توصل للمحرك الشغّال فعلًا مش بس تتحفظ
            try {
                val nc = Cfg.snapshot(); conf = nc
                Extras.load()   // (v212) ميزات المشاهدة اتغيّرت من الإعدادات
                if (::engine.isInitialized) {
                    engine.applyConf(nc)
                    engine.toneStyle = Cfg.str("tone_style", ""); engine.toneStrength = Cfg.str("tone_strength", "متوسطة")
                    // (v212) تحويل اللهجة من الإعدادات: للباتشات الجاية (القديم من زرار اللغة 🌐)
                    val cd = Cfg.str("conv_dialect", "")
                    if (cd != engine.convDialect && !(cd.isNotEmpty() && cd == nc.lang)) engine.setConvDialect(cd, false)
                }
            } catch (e: Throwable) { LogStore.err("Player:applyConf", e) }
        }
        restyleFn()
        if (resumeAfterSettings) { resumeAfterSettings = false; try { player.play() } catch (e: Exception) { LogStore.err("Main:2576", e) } }
    }
    override fun onPause() { super.onPause(); LogStore.add("⏸ onPause · ${LogStore.heapLine()}"); resumedNow = false; if (!handedOff && !remote) { saveRecent(); Thread { engine.saveNow() }.start() } }
    override fun onStop() {
        super.onStop(); saveRecent()
        // ✕ على نافذة PiP: النظام بيوقف الأكتيفيتي وهي لسه في وضع PiP — نقفل الفيديو ونخرج (إلا لو الشاشة اتقفلت)
        val interactive = try { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive } catch (_: Exception) { true }
        if (pipNow() && interactive && !isFinishing && !audioOnlyMode) closeAfterPip()
    }
    override fun onDestroy() {
        dubCancel = true; try { dubPlay?.stop(); dubPlay = null } catch (_: Throwable) {}   // (v418)
        try { volWatch?.stop(); volWatch = null } catch (_: Throwable) {}
        LogStore.add("🔚 onDestroy (isFinishing=$isFinishing)")
        NoticeLog.detach(this)
        stopVoice()
        try { TaskCenter.extra.remove(taskListener) } catch (_: Throwable) {}
        h.removeCallbacksAndMessages(null)
        pipOv?.hide(); pipOv = null
        saveRecent()
        try { visual.stop() } catch (e: Exception) { LogStore.err("Main:2590", e) }
        rmFrames = false; try { rmLsn?.let { LinkSession.removeListener(it) } } catch (_: Throwable) {}; rmLsn = null
        try { mirror?.release() } catch (_: Throwable) {}; mirror = null   // (v570)
        if (::realEngine.isInitialized && Live.engine === realEngine) Live.engine = null
        retireEngine()
        if (!handedOff && !remote) try { engine.saveNow() } catch (e: Exception) { LogStore.err("Main:2593", e) }
        if (incognito && isFinishing) try { File(File(filesDir, "progress"), Store.keyFor(vid) + ".json").delete() } catch (_: Throwable) {}   // (v124) المتخفي: الترجمة المحفوظة بتتمسح أول ما تقفل
        player.release()
        // (v564) فيديو الموبايل على التلفزيون: امسح الكاش أول ما نخرج (لو مفيش مشغّل تاني بيستخدمه)
        if (CastCache.isCast(url) && synchronized(liveList) { liveList.none { val a = it.get(); a != null && a !== this && !a.isFinishing && !a.isDestroyed } }) CastCache.purge(applicationContext)
        synchronized(liveList) { liveList.removeAll { it.get() == null || it.get() === this } }
        val cur0 = PlayerRemote.act?.get()
        if (cur0 === this) PlayerRemote.act = null
        if (cur0 == null || cur0 === this) KeepAliveService.stop(this)
        super.onDestroy()
    }
}