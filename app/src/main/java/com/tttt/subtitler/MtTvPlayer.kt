package com.tttt.subtitler

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.newtube.mobile.ui.playback.JoyboyPlayerHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (v928) على التلفزيون: لما فيديو يوتيوب يتفتح في مشغّل MeeTube (TvDirect.playYt)، ريموت الموبايل لازم يفضل يتحكم فيه.
 * MtTranslate.attach/detach بيسجّلوا الـExoPlayer هنا، وTvLink.playerCmd بيحوّل أوامر/حالة الريموت له لما مفيش مشغّل Joyboy شغّال.
 * (v942) المرحلة 4 — الجزء التاني: تغطية rAct/rState الكاملة بتاعة Player.kt على مشغّل MeeTube.
 * الأوامر المتنفّذة: كل أوامر التشغيل والسرعة والحلقات والتكرار والصوت 0..200 (فوق 100 = تضخيم عبر MtMedia.setBoostPct)
 * والترجمة عبر MtTranslate.rpc (engine.resume/pause/translateFrom/redoAll/characters/set/setConvDialect) وcfgset:/mset:/wtr:/dialect:/tone:
 * والستايل عبر Cfg (scale/font/bgopa/posy/dual/bgon) وfit من Cfg (fitMode للجسر — تطبيق المقاس على السطح لسه على جانب MeeTube).
 * أي أمر مالوش مقابل حقيقي على مشغّل MeeTube = false بصراحة (قوايم MeeTube CC/الصوت/الجودة لسه في جافا :common/:mobile من غير نقطة دخول من :app).
 * الحالة بقت بنفس مفاتيح rState (كل الـ71 + spd)؛ اللي مالوش مقابل بياخد قيمة صادقة (false أو "" أو 0) مش مختلقة.
 * rpc(op, args): engine.* بتتفوّض لـMtTranslate.rpc (زي Player.rpcBg على خيط الطلب)، وsleep.set/vol.set/boost.set
 * وstate/act/play/pause/seekto/speed.set/conf.reload على الـUI عبر onUi؛ أي op تاني = {"ok":false,"err":"unknown"}.
 * الفلاج: mt_tv_player (افتراضي false — م4). بيتحكم في توجيه ملفات/لينكات التلفزيون لمشغّل MeeTube من MtPlay؛
 * مسار يوتيوب-على-التلفزيون بتاع v928 زي ما هو من غير الفلاج، والـlegacy هو الـfallback دايمًا.
 * اللي لسه على legacy (مش متنفّذ هنا): استيراد ترجمة من الموبايل عبر SfJob (بس TvLink.subUp بقى يجرّب MtTranslate.importSub)
 * وأدوات الميديا (dl/task) والوضع البصري والدبلجة والتدوير وPiP.
 */
object MtTvPlayer {
    const val KEY = "mt_tv_player"

    fun enabled(): Boolean = try { Cfg.bool(KEY, false) } catch (_: Throwable) { false }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var act: Activity? = null
    @Volatile private var player: ExoPlayer? = null
    @Volatile private var title = ""

    fun set(a: Activity, p: ExoPlayer, t: String) {
        if (!DeviceUi.isTv(a)) return
        act = a; player = p; title = t
    }

    fun clear(a: Activity) {
        if (act === a) { act = null; player = null; title = "" }
    }

    fun live(): Boolean {
        val a = act
        return player != null && a != null && !a.isFinishing && !a.isDestroyed
    }

    /** (v942) وضع الملاءمة الحالي من Cfg: 0 احتواء · 1 ملء (قص) · 2 تمديد. 3 (100%) متشال على التلفزيون زي Player.curFit */
    fun fitMode(): Int {
        val m = try { Cfg.int("fit", 0) } catch (_: Throwable) { 0 }
        val c = m.coerceIn(0, 3)
        return if (c == 3) 0 else c
    }

    /** (v942) اسم وضع الملاءمة للريموت — تطبيق المقاس على سطح MeeTube لسه على جانب جافا (الجسر يقرا fitMode) */
    fun fitName(): String {
        return PlayerLogic.fitNames[fitMode().coerceIn(0, 2)]
    }

    /** (v942) التضخيم الحالي 0..100 من MtMedia (اللي بيملكه) */
    private fun boostNow(): Int {
        return try { MtMedia.boostPct().coerceIn(0, 100) } catch (_: Throwable) { 0 }
    }

    /** (v942) الصوت الكلي 0..200 زي Player.volTotal: صوت المشغّل + التضخيم */
    private fun volTotalNow(p: ExoPlayer): Int {
        val base = try { (p.volume * 100).toInt().coerceIn(0, 100) } catch (_: Throwable) { 0 }
        return (base + boostNow()).coerceIn(0, 200)
    }

    /** (v942) طبّق صوت كلي 0..200: لحد 100 صوت مشغّل، وفوقها صوت كامل + تضخيم — زي Player.applyVolumeP */
    private fun applyVolTotal(p: ExoPlayer, total: Int) {
        val t = total.coerceIn(0, 200)
        if (t <= 100) {
            try { p.volume = t / 100f } catch (_: Throwable) {}
            try { MtMedia.setBoostPct(0) } catch (_: Throwable) {}
        } else {
            try { p.volume = 1f } catch (_: Throwable) {}
            try { MtMedia.setBoostPct(t - 100) } catch (_: Throwable) {}
        }
    }

    /** (v942) نداء محرك الترجمة عبر عقد MtTranslate.rpc. null = استثناء (مش رد) */
    private fun trRpc(op: String, args: JSONObject): JSONObject? {
        return try { MtTranslate.rpc(op, args) } catch (_: Throwable) { null }
    }

    /** (v942) نجاح رد MtTranslate.rpc: مفيش unknown ولا err. الرد الفاضي (من غير ok) نجاح زي Player.rpcBg */
    private fun trOk(r: JSONObject?): Boolean {
        if (r == null) return false
        if (r.optBoolean("unknown", false)) return false
        if (r.optString("err", "").isNotEmpty()) return false
        return true
    }

    /** (v942) المحرك شغّال؟ (engine.get بينجح بس لو فيه محرك) */
    private fun engineLive(): Boolean {
        return trOk(trRpc("engine.get", JSONObject()))
    }

    /** (v942) cfgset: من ريموت الموبايل — نفس المفاتيح والحدود بتاعة Player.rAct */
    private fun cfgSetRemote(kv: String): Boolean {
        val ix = kv.indexOf('|')
        if (ix <= 0) return false
        val k = kv.substring(0, ix)
        val vv = kv.substring(ix + 1)
        val fixed: String? = when (k) {
            "sub_scale" -> (vv.toIntOrNull() ?: 100).coerceIn(60, 200).toString()
            "sub_blur" -> (vv.toIntOrNull() ?: 0).coerceIn(0, 20).toString()
            "sub_posy" -> (vv.toIntOrNull() ?: 0).coerceIn(-60, 240).toString()
            "sub_uni_on" -> if (vv == "1") "1" else "0"
            "sub_uni_color" -> if (Regex("#[0-9A-Fa-f]{6}").matches(vv)) vv else null
            "sub_font" -> if (SubStyle.fonts.any { it.id == vv }) vv else null
            "sub_fontstyle" -> if (SubStyle.fontStyles.any { it.first == vv }) vv else null
            else -> null
        }
        if (fixed == null) return false
        try { Cfg.put(k, fixed) } catch (_: Throwable) { return false }
        if (k == "sub_uni_color") {
            try { Cfg.put("sub_uni_on", "1") } catch (_: Throwable) {}
        }
        return true
    }

    /** (v942) mset: من ريموت الموبايل — نفس مفاتيح Player.remoteMset (lang/model/aud/dual/swap/term) */
    private fun msetRemote(c: String): Boolean {
        val k = c.substringBefore(':')
        val v = c.substringAfter(':', "")
        when (k) {
            "lang" -> {
                if (v !in Offline.langIds) return false
                try { Cfg.put("stt_lang", v) } catch (_: Throwable) { return false }
                return true
            }
            "model" -> {
                if (Offline.models.none { it.id == v }) return false
                try { Cfg.put("off_model", v) } catch (_: Throwable) { return false }
                return true
            }
            "aud" -> {
                val wav = v == "wav"
                try { Cfg.put(AudioEnc.PRESET_KEY, if (wav) AudioEnc.NORMAL else AudioEnc.FAST) } catch (_: Throwable) { return false }
                try {
                    val cur = Cfg.str("tr_mode_id", "")
                    if (wav && cur == "g_aac") Cfg.put("tr_mode_id", "g_wav")
                    if (!wav && cur == "g_wav") Cfg.put("tr_mode_id", "g_aac")
                } catch (_: Throwable) {}
                return true
            }
            "dual" -> {
                val n = v.toIntOrNull() ?: return false
                try { Cfg.put("dual_listen", n.coerceIn(0, 2).toString()) } catch (_: Throwable) { return false }
                return true
            }
            "swap" -> {
                val on = try { Cfg.str("tr_autoswap", "on") != "off" } catch (_: Throwable) { true }
                try { Cfg.put("tr_autoswap", if (on) "off" else "on") } catch (_: Throwable) { return false }
                return true
            }
            "term" -> {
                val on = try { TermNotes.enabled() } catch (_: Throwable) { true }
                try { Cfg.put("term_notes", if (on) "off" else "on") } catch (_: Throwable) { return false }
                return true
            }
            else -> return false
        }
    }

    /** (v942) tone: لهجة الأسلوب — نفس صيغة Player.rAct (label|strength|apply). الـapply بيتجاهل: مفيش op لـrunTool في rpc */
    private fun toneRemote(rest: String): Boolean {
        if (!engineLive()) return false
        val pp = rest.split("|")
        val lbl = (pp.getOrNull(0) ?: "").ifBlank { "حرفي" }
        val dg = (pp.getOrNull(1) ?: "").ifBlank { "متوسطة" }
        val sv = if (lbl == "حرفي") "" else lbl
        try { Cfg.put("tone_style", sv) } catch (_: Throwable) { return false }
        try { Cfg.put("tone_strength", dg) } catch (_: Throwable) { return false }
        val okTone = trOk(trRpc("engine.tone", JSONObject().put("style", sv).put("strength", dg)))
        return okTone
    }

    /** (v942) اسم فولدر الملف من src (أحسن جهد — content:// مالوش فولدر) */
    private fun folderOf(src: String): String {
        val s = src.removePrefix("file://")
        if (!s.startsWith("/")) return ""
        val parent = s.substringBeforeLast('/', "")
        if (parent.isEmpty()) return ""
        return parent.substringAfterLast('/', "")
    }

    private fun <T> onUi(f: (ExoPlayer, Activity) -> T): T? {
        val p = player ?: return null
        val a = act ?: return null
        val box = arrayOfNulls<Any>(1)
        val latch = CountDownLatch(1)
        ui.post { try { box[0] = f(p, a) } catch (e: Throwable) { LogStore.err("MtTvPlayer", e) }; latch.countDown() }
        @Suppress("UNCHECKED_CAST")
        return if (latch.await(900L, TimeUnit.MILLISECONDS)) box[0] as T? else null
    }

    /** تشغيل/إيقاف من غير أمر (TvLink.exec "playpause"). */
    fun remoteToggle(): Boolean {
        if (!live()) return false
        onUi { p, _ ->
            if (p.isPlaying) p.pause() else p.play()
            true
        }
        return true
    }

    fun act(a: String, v: Long): Boolean {
        if (!live()) return false
        val handled = onUi { p, ac ->
            when (a) {
                "toggle", "playpause" -> {
                    if (p.isPlaying) p.pause() else p.play()
                    true
                }
                "seek" -> {
                    p.seekTo((p.currentPosition + v).coerceIn(0L, if (p.duration > 0L) p.duration else Long.MAX_VALUE))
                    true
                }
                "seekto" -> {
                    p.seekTo(v.coerceIn(0L, if (p.duration > 0L) p.duration else Long.MAX_VALUE))
                    true
                }
                "speedset" -> {
                    p.setPlaybackSpeed((v / 100f).coerceIn(0.25f, 4f))
                    true
                }
                "speed" -> {
                    val cur = try { p.playbackParameters.speed } catch (_: Throwable) { 1f }
                    p.setPlaybackSpeed(PlayerLogic.nextSpeed(cur))
                    true
                }
                "prev" -> {
                    val stepped = try { MtMedia.onStep(ac, -1) } catch (_: Throwable) { false }
                    if (!stepped) {
                        try { p.seekTo(0L) } catch (_: Throwable) {}
                    }
                    true
                }
                "next" -> {
                    val stepped = try { MtMedia.onStep(ac, 1) } catch (_: Throwable) { false }
                    if (!stepped) {
                        val d = try { p.duration } catch (_: Throwable) { 0L }
                        if (d > 0L) {
                            try { p.seekTo(d) } catch (_: Throwable) {}
                        } else {
                            return@onUi false
                        }
                    }
                    true
                }
                "loop" -> {
                    try { MtMedia.loopCycle() } catch (_: Throwable) {}
                    true
                }
                "loopset" -> {
                    val cur = try { MtMedia.loopMode() } catch (_: Throwable) { 0 }
                    val n = v.toInt().coerceIn(0, 2)
                    val target = if (n == cur) 0 else n
                    try {
                        Cfg.put("loop", if (target == 1) "1" else "0")
                        Cfg.put("autoplay_wrap", if (target == 2) "1" else "0")
                        p.repeatMode = if (target == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    } catch (_: Throwable) {}
                    true
                }
                "ab_a", "ab_b" -> {
                    try { MtMedia.abNext() } catch (_: Throwable) {}
                    true
                }
                "ab_x" -> {
                    try { MtMedia.abClear() } catch (_: Throwable) {}
                    true
                }
                "vol", "volume" -> {
                    applyVolTotal(p, v.toInt())
                    true
                }
                "fit" -> {
                    val n = (fitMode() + 1) % 3
                    try { Cfg.put("fit", n.toString()) } catch (_: Throwable) {}
                    try { JoyboyPlayerHost.onJoyboyAction(ac, "fit:" + n) } catch (_: Throwable) {}
                    true
                }
                "rotate" -> {
                    try { JoyboyPlayerHost.onJoyboyAction(ac, "rotate") } catch (_: Throwable) { false }
                }
                "cc" -> {
                    try { JoyboyPlayerHost.onJoyboyAction(ac, "cc") } catch (_: Throwable) { false }
                }
                "scale" -> {
                    val cur = try { Cfg.int("sub_scale", 100) } catch (_: Throwable) { 100 }
                    val n = (cur + v.toInt()).coerceIn(50, 250)
                    try { Cfg.put("sub_scale", n.toString()) } catch (_: Throwable) {}
                    try { MtTranslate.restyle() } catch (_: Throwable) {}
                    true
                }
                "shift" -> {
                    try {
                        MtTranslate.shiftOffset(v)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "shift0" -> {
                    try {
                        MtTranslate.resetOffset()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "audio" -> {
                    try { JoyboyPlayerHost.onJoyboyAction(ac, "audio") } catch (_: Throwable) { false }
                }
                "quality" -> {
                    try { JoyboyPlayerHost.onJoyboyAction(ac, "quality") } catch (_: Throwable) { false }
                }
                "qset", "aset" -> false
                "subs", "subdlg" -> {
                    try {
                        MtTranslate.showSoftSubs()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "translate" -> {
                    try {
                        MtTranslate.toggleTranslate()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "more", "menu", "tools" -> {
                    try {
                        MtTranslate.showMenu()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "modeset" -> {
                    try {
                        MtTranslate.translateMenu()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "studio" -> {
                    try {
                        MtTranslate.showStyle()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "visdlg", "netdlg" -> false
                "ytmode" -> {
                    try { JoyboyPlayerHost.onJoyboyAction(ac, "audio_only") } catch (_: Throwable) { false }
                }
                "syncstrip" -> {
                    try {
                        MtBar.toggleSyncStrip(ac)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "visual", "vsnap" -> {
                    try {
                        MtBar.visualSnap(ac)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "notice", "log" -> {
                    try {
                        MtBar.showLog(ac)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "sent" -> {
                    try {
                        MtTranslate.searchSentences()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "lock" -> {
                    try { JoyboyPlayerHost.onJoyboyAction(ac, "lock") } catch (_: Throwable) { false }
                }
                "pip" -> {
                    try { JoyboyPlayerHost.onJoyboyAction(ac, "pip") } catch (_: Throwable) { false }
                }
                "voice" -> {
                    try {
                        MtTranslate.dubToggle()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "nextlist" -> {
                    try { PlayQueueUi.show(ac) } catch (_: Throwable) { return@onUi false }
                    true
                }
                "dual" -> {
                    val cur = try { Cfg.int("sub_dual", 0) } catch (_: Throwable) { 0 }
                    val cyc = listOf(0, 3, 1, 4)
                    val n = cyc[(cyc.indexOf(cur) + 1) % cyc.size]
                    try { Cfg.put("sub_dual", n.toString()) } catch (_: Throwable) {}
                    try { MtTranslate.restyle() } catch (_: Throwable) {}
                    true
                }
                "dualset" -> {
                    val n = v.toInt().coerceIn(0, 4)
                    try { Cfg.put("sub_dual", n.toString()) } catch (_: Throwable) {}
                    try { MtTranslate.restyle() } catch (_: Throwable) {}
                    true
                }
                "bgon" -> {
                    val on = try { Cfg.bool("ask_bg_exit", true) } catch (_: Throwable) { true }
                    try { Cfg.put("ask_bg_exit", if (on) "0" else "1") } catch (_: Throwable) {}
                    true
                }
                "ai" -> {
                    try {
                        MtTranslate.translateMenu()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "font" -> {
                    val fs = SubStyle.fonts
                    val cf = try { SubStyle.load { k, d -> Cfg.str(k, d) }.font } catch (_: Throwable) { "Cairo" }
                    val n = fs[(fs.indexOfFirst { it.id == cf } + 1) % fs.size]
                    try { Cfg.put("sub_font", n.id) } catch (_: Throwable) {}
                    try { MtTranslate.restyle() } catch (_: Throwable) {}
                    true
                }
                "fontprev" -> {
                    val fs = SubStyle.fonts
                    val cf = try { SubStyle.load { k, d -> Cfg.str(k, d) }.font } catch (_: Throwable) { "Cairo" }
                    val n = fs[(fs.indexOfFirst { it.id == cf } - 1 + fs.size) % fs.size]
                    try { Cfg.put("sub_font", n.id) } catch (_: Throwable) {}
                    try { MtTranslate.restyle() } catch (_: Throwable) {}
                    true
                }
                "bgopa" -> {
                    val n = v.toInt().coerceIn(0, 100)
                    try { Cfg.put("sub_bgopa", n.toString()) } catch (_: Throwable) {}
                    try { Cfg.put("sub_nobg", if (n == 0) "1" else "0") } catch (_: Throwable) {}
                    try { MtTranslate.restyle() } catch (_: Throwable) {}
                    true
                }
                "posy" -> {
                    val cur = try { Cfg.int("sub_posy", 0) } catch (_: Throwable) { 0 }
                    val n = (cur + v.toInt()).coerceIn(-60, 240)
                    try { Cfg.put("sub_posy", n.toString()) } catch (_: Throwable) {}
                    try { MtTranslate.restyle() } catch (_: Throwable) {}
                    true
                }
                "redo" -> trOk(trRpc("engine.retranslate", JSONObject()))
                "chars" -> {
                    try {
                        MtBar.showChars(ac)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "tr_cont", "tr_resume" -> trOk(trRpc("engine.resume", JSONObject()))
                "tr_here" -> trOk(trRpc("engine.translateFrom", JSONObject().put("sec", p.currentPosition / 1000.0)))
                "tr_fresh" -> {
                    val okRedo = trOk(trRpc("engine.retranslate", JSONObject()))
                    if (!okRedo) {
                        false
                    } else {
                        try { p.seekTo(0L) } catch (_: Throwable) {}
                        trOk(trRpc("engine.resume", JSONObject()))
                    }
                }
                "tr_pause" -> trOk(trRpc("engine.pause", JSONObject()))
                "tr_cancel" -> trOk(trRpc("engine.cancel", JSONObject()))
                "tr_gem" -> {
                    try {
                        MtTranslate.setEngineMode("gemini")
                        if (!MtTranslate.isTranslating()) MtTranslate.toggleTranslate()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "tr_whisper" -> {
                    try {
                        MtTranslate.setEngineMode("whisper")
                        if (!MtTranslate.isTranslating()) MtTranslate.toggleTranslate()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "tr_gtx" -> {
                    try {
                        MtTranslate.setEngineMode("google")
                        if (!MtTranslate.isTranslating()) MtTranslate.toggleTranslate()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "tr_wtx" -> {
                    try {
                        MtTranslate.setEngineMode("transcribe")
                        if (!MtTranslate.isTranslating()) MtTranslate.toggleTranslate()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "tr_free", "tr_bg" -> false
                "tr_wiz" -> {
                    try {
                        MtTranslate.translateMenu()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                "close" -> {
                    try { p.pause() } catch (_: Throwable) {}
                    try { ac.finish() } catch (_: Throwable) {}
                    true
                }
                else -> {
                    if (a.startsWith("cfgset:")) {
                        cfgSetRemote(a.substring(7))
                    } else if (a.startsWith("mset:")) {
                        msetRemote(a.substring(5))
                    } else if (a.startsWith("wtr:")) {
                        val kind = a.substring(4)
                        if (kind == "free" || kind == "google") {
                            try { Cfg.put("wtr", kind) } catch (_: Throwable) {}
                            try { Cfg.put("tr_mode_id", if (kind == "free") Strict.FREE_TXT else Strict.SEMI) } catch (_: Throwable) {}
                            true
                        } else {
                            false
                        }
                    } else if (a.startsWith("dialect:")) {
                        if (!engineLive()) {
                            false
                        } else {
                            trOk(trRpc("engine.dialect", JSONObject().put("d", a.substring(8))))
                        }
                    } else if (a.startsWith("tone:")) {
                        toneRemote(a.substring(5))
                    } else if (a.startsWith("subnet:") || a.startsWith("subhunt:")) {
                        try {
                            MtTranslate.searchOnlineSubs()
                            true
                        } catch (_: Throwable) {
                            false
                        }
                    } else if (a.startsWith("ai:")) {
                        try {
                            MtTranslate.translateMenu()
                            true
                        } catch (_: Throwable) {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
        } ?: false
        return handled
    }

    fun state(): JSONObject? {
        if (!live()) return null
        return onUi { p, _ ->
            val t = try { p.mediaMetadata.title?.toString() } catch (_: Throwable) { null }
            val vs = try { p.videoSize } catch (_: Throwable) { null }
            val lm = try { MtMedia.loopMode() } catch (_: Throwable) { 0 }
            val vol = volTotalNow(p)
            val ab = try { MtMedia.abState() } catch (_: Throwable) { "" }
            val o = try { MtOrigin.current() } catch (_: Throwable) { null }
            val src = o?.src ?: ""
            val spd = try { p.playbackParameters.speed } catch (_: Throwable) { 1f }
            val durMs = if (p.duration > 0L) p.duration else 0L
            val posMs = try { p.currentPosition } catch (_: Throwable) { 0L }
            val cover = try { MtTranslate.coverStats() } catch (_: Throwable) { null }
            val subCount = cover?.first ?: 0
            val coveredSec = cover?.second ?: 0.0
            val pct = PlayerLogic.percent(coveredSec, durMs / 1000.0)
            val st = try { SubStyle.load { k, d -> Cfg.str(k, d) } } catch (_: Throwable) { null }
            val fontLabel = try { SubStyle.fonts.firstOrNull { it.id == st?.font }?.label ?: (st?.font ?: "") } catch (_: Throwable) { "" }
            val hdrJson = JSONObject()
            try { o?.headers?.forEach { (hk, hv) -> hdrJson.put(hk, hv) } } catch (_: Throwable) {}
            val netV = try { NetUse.trVideo() } catch (_: Throwable) { 0L }
            val netD = try { NetUse.trToday() } catch (_: Throwable) { 0L }
            val netTv = try {
                val u = android.os.Process.myUid()
                val rx = android.net.TrafficStats.getUidRxBytes(u)
                val tx = android.net.TrafficStats.getUidTxBytes(u)
                if (rx >= 0L && tx >= 0L) rx + tx else 0L
            } catch (_: Throwable) { 0L }
            val notsN = try { NoticeLog.list().size } catch (_: Throwable) { 0 }
            val engLine = try { TrWizard.summaryLine() } catch (_: Throwable) { "" }
            val msKeys = try { Cfg.keys("keys").size + Cfg.keys("extra").size + Cfg.keys("backup").size } catch (_: Throwable) { 0 }
            val msModel = try { Offline.model().id } catch (_: Throwable) { "" }
            val msLang = try { Offline.lang() } catch (_: Throwable) { "" }
            val msWav = try { AudioEnc.preset() == AudioEnc.NORMAL } catch (_: Throwable) { false }
            val msTerm = try { TermNotes.enabled() } catch (_: Throwable) { false }
            val vname = if (!t.isNullOrBlank()) t else title
            val vfolder = try { folderOf(src) } catch (_: Throwable) { "" }
            val wtr = try { Cfg.str("wtr", "free") } catch (_: Throwable) { "free" }
            val bgopa = try { if (Cfg.str("sub_nobg", "0") == "1") 0 else Cfg.int("sub_bgopa", 45) } catch (_: Throwable) { 45 }
            JSONObject()
                .put("kind", "player")
                .put("title", vname)
                .put("pos", posMs)
                .put("dur", durMs)
                .put("playing", p.isPlaying)
                .put("speed", PlayerLogic.speedLabel(spd))
                .put("spd", spd.toDouble())
                .put("wtr", wtr)
                .put("fit", fitName())
                .put("cc", false)
                .put("scale", try { Cfg.int("sub_scale", 100) } catch (_: Throwable) { 100 })
                .put("vol", vol)
                .put("shift", 0)
                .put("audio", false)
                .put("quality", false)
                .put("tr", if (subCount > 0) pct.toString() + "%" else "")
                .put("sf_side", "")
                .put("sf_nat", 0)
                .put("sf_live", false)
                .put("sf_busy", false)
                .put("tpart", false)
                .put("sf_mob", "")
                .put("net_busy", false)
                .put("net_more", 0)
                .put("net_v", netV)
                .put("net_d", netD)
                .put("net_tv", netTv)
                .put("sblur", try { Cfg.int("sub_blur", 0) } catch (_: Throwable) { 0 })
                .put("suni_on", try { Cfg.str("sub_uni_on", "0") == "1" } catch (_: Throwable) { false })
                .put("suni", try { Cfg.str("sub_uni_color", "#FFFFFF") } catch (_: Throwable) { "#FFFFFF" })
                .put("vname", vname)
                .put("vfolder", vfolder)
                .put("src", src)
                .put("hdr", hdrJson)
                .put("vw", vs?.width ?: 0)
                .put("vh", vs?.height ?: 0)
                .put("loop", lm != 0)
                .put("lm", lm)
                .put("dual", st?.dual ?: 0)
                .put("bgon", try { Cfg.bool("ask_bg_exit", true) } catch (_: Throwable) { true })
                .put("nots", notsN)
                .put("locked", false)
                .put("font", fontLabel)
                .put("eng", engLine)
                .put("cnt", if (subCount > 0) pct.toString() + "% · " + subCount.toString() + " جملة" else "")
                .put("trcolor", 0xFFE53935.toInt())
                .put("mode", "")
                .put("tstart", cover != null)
                .put("tpause", false)
                .put("vcnt", 0)
                .put("bgopa", bgopa)
                .put("ytmode", "")
                .put("ab", ab)
                .put("posy", try { Cfg.int("sub_posy", 0) } catch (_: Throwable) { 0 })
                .put("sf_done", false)
                .put("sf_n", subCount)
                .put("ms_mk", try { Cfg.str("tr_mode_id", "") } catch (_: Throwable) { "" })
                .put("ms_lang", msLang)
                .put("ms_model", msModel)
                .put("ms_wav", msWav)
                .put("ms_dual", try { Cfg.int("dual_listen", 1) } catch (_: Throwable) { 1 })
                .put("ms_swap", try { Cfg.str("tr_autoswap", "on") != "off" } catch (_: Throwable) { true })
                .put("ms_term", msTerm)
                .put("ms_keys", msKeys)
                .put("hsub", cover != null)
                .put("cdial", try { Cfg.str("conv_dialect", "").isNotEmpty() } catch (_: Throwable) { false })
                .put("tool", "")
                .put("toolp", 0)
                .put("ql", JSONArray())
                .put("qc", 0)
                .put("al", JSONArray())
                .put("ac", 0)
        }
    }

    /** (v942) RPC للتلفزيون-المخ على مشغّل MeeTube — نفس عقد PlayerActivity.brain بس على Mt. */
    fun rpc(op: String, args: JSONObject): JSONObject {
        if (op.startsWith("engine.")) {
            if (!live()) {
                return JSONObject().put("ok", false).put("err", "noplayer")
            }
            val r = trRpc(op, args)
            if (r == null) {
                return JSONObject().put("ok", false).put("err", "err")
            }
            if (r.optBoolean("unknown", false)) {
                return JSONObject().put("ok", false).put("err", "unknown")
            }
            if (!r.has("ok")) {
                r.put("ok", r.optString("err", "").isEmpty())
            }
            return r
        }
        if (!live()) {
            return JSONObject().put("ok", false).put("err", "noplayer")
        }
        when (op) {
            "sleep.set" -> {
                try { MtMedia.setSleep(args.optInt("min", 0)) } catch (_: Throwable) {}
                return JSONObject().put("ok", true)
            }
            "vol.set", "volume.set" -> {
                val total = args.optInt("p", args.optInt("v", 50))
                val ok = onUi { p, _ -> applyVolTotal(p, total) } != null
                return JSONObject().put("ok", ok)
            }
            "boost.set" -> {
                val b = args.optInt("b", args.optInt("p", args.optInt("v", 0)))
                try { MtMedia.setBoostPct(b) } catch (_: Throwable) {}
                return JSONObject().put("ok", true)
            }
            "state" -> {
                val s = state() ?: return JSONObject().put("ok", false).put("err", "noplayer")
                return JSONObject().put("ok", true).put("st", s)
            }
            "act" -> {
                val handled = act(args.optString("a"), args.optLong("v"))
                return JSONObject().put("ok", handled).put("handled", handled)
            }
            "play" -> {
                onUi { p, _ -> try { if (!p.isPlaying) p.play() } catch (_: Throwable) {} }
                return JSONObject().put("ok", true)
            }
            "pause" -> {
                onUi { p, _ -> try { p.pause() } catch (_: Throwable) {} }
                return JSONObject().put("ok", true)
            }
            "seekto" -> {
                val ms = args.optLong("ms", args.optLong("v", 0L))
                onUi { p, _ -> try { p.seekTo(ms.coerceIn(0L, if (p.duration > 0L) p.duration else Long.MAX_VALUE)) } catch (_: Throwable) {} }
                return JSONObject().put("ok", true)
            }
            "speed.set" -> {
                val s = args.optDouble("v", 1.0).toFloat()
                onUi { p, _ -> try { p.setPlaybackSpeed(s.coerceIn(0.25f, 4f)) } catch (_: Throwable) {} }
                return JSONObject().put("ok", true)
            }
            "volume" -> {
                val f = args.optDouble("v", 1.0).toFloat()
                onUi { p, _ -> try { p.volume = f.coerceIn(0f, 1f) } catch (_: Throwable) {} }
                return JSONObject().put("ok", true)
            }
            "conf.reload" -> {
                return JSONObject().put("ok", true)
            }
            else -> return JSONObject().put("ok", false).put("err", "unknown")
        }
    }

    private fun refOf(o: MtOrigin.Origin?): String {
        if (o == null) return ""
        if (o.videoId.length == 11) return "https://www.youtube.com/watch?v=" + o.videoId
        val s = o.src
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
        return ""
    }

    /** (v939) نفس عقد PlayerActivity.remoteHandoff: الوصف + المكان + الهيدرز — والتلفزيون بيوقّف مؤقتًا لو فيه مرجع. */
    fun remoteHandoff(): JSONObject? {
        if (!live()) return null
        return onUi { p, _ ->
            val o = try { MtOrigin.current() } catch (_: Throwable) { null }
            val ref = refOf(o)
            if (ref.isNotEmpty()) {
                try { p.pause() } catch (_: Throwable) {}
            }
            JSONObject()
                .put("ref", ref)
                .put("title", o?.title ?: title)
                .put("pos", p.currentPosition)
                .put("hdr_ref", o?.headers?.get("Referer") ?: "")
                .put("hdr_ua", o?.headers?.get("User-Agent") ?: "")
                .put("hdr_cookie", o?.headers?.get("Cookie") ?: "")
        }
    }

    /** (v939) نفس عقد PlayerActivity.remoteQueueCur: الوصف بس، من غير إيقاف. */
    fun remoteQueueCur(): JSONObject? {
        if (!live()) return null
        return onUi { _, _ ->
            val o = try { MtOrigin.current() } catch (_: Throwable) { null }
            JSONObject()
                .put("ref", refOf(o))
                .put("title", o?.title ?: title)
        }
    }

    private fun findVideoView(v: View): View? {
        if (v is SurfaceView || v is TextureView) return v
        if (v is ViewGroup) {
            for (k in 0 until v.childCount) {
                findVideoView(v.getChildAt(k))?.let { return it }
            }
        }
        return null
    }

    /** (v939) لقطة حية JPEG زي PlayerActivity.frameJpeg (نفس حدود الأبعاد/الجودة). null = مفيش سطح/DRM — الموبايل يعرض البوستر. */
    fun frameJpeg(maxDim: Int, q: Int): ByteArray? {
        val a = act ?: return null
        if (!live()) return null
        return try {
            val vv = findVideoView(a.window.decorView) ?: return null
            val bmp0: Bitmap = when (vv) {
                is TextureView -> try { vv.bitmap } catch (_: Throwable) { null }
                is SurfaceView -> {
                    if (vv.width <= 0 || vv.height <= 0) return null
                    val bmp = Bitmap.createBitmap(vv.width, vv.height, Bitmap.Config.ARGB_8888)
                    val latch = CountDownLatch(1)
                    val okBox = booleanArrayOf(false)
                    PixelCopy.request(vv, bmp, { res ->
                        okBox[0] = res == PixelCopy.SUCCESS
                        latch.countDown()
                    }, Handler(Looper.getMainLooper()))
                    if (!latch.await(1500L, TimeUnit.MILLISECONDS)) return null
                    if (!okBox[0]) return null
                    bmp
                }
                else -> null
            } ?: return null
            val md = maxDim.coerceIn(160, 960)
            val sc = minOf(1f, md.toFloat() / maxOf(bmp0.width, bmp0.height).toFloat())
            val bmp = if (sc < 1f) {
                Bitmap.createScaledBitmap(bmp0, (bmp0.width * sc).toInt().coerceAtLeast(1), (bmp0.height * sc).toInt().coerceAtLeast(1), true)
            } else {
                bmp0
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q.coerceIn(30, 90), out)
            if (bmp !== bmp0) {
                try { bmp0.recycle() } catch (_: Throwable) {}
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }
}
