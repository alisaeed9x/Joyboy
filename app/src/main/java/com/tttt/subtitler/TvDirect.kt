package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.SurfaceView
import android.widget.ImageView
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * (v570) فتح مباشر على التلفزيون: فيديو يوتيوب / لينك مصطاد بييجي من الموبايل أو من صف يوتيوب في الرئيسية
 * بيفتح مشغّل الفيديو على طول (من غير ما يعدّي على MainActivity ولا صفحة يوتيوب الكبيرة).
 * ret="tv" = لما تخرج من الفيديو ترجع للصفحة اللي كنت فيها (أو للرئيسية لو مفيش حاجة تحت المشغّل).
 */
object TvDirect {
    const val RET = "tv"

    private fun base(ctx: Context): Intent = Intent(ctx, PlayerActivity::class.java).apply {
        if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("ret", RET); putExtra("fresh", false)
    }

    fun playYt(ctx: Context, id: String, title: String, startMs: Long = 0L, translate: Boolean = false) {
        ctx.startActivity(base(ctx).apply {
            putExtra("ytpend", true); putExtra("ytid", id); putExtra("title", title)
            putExtra("ref", "https://www.youtube.com/")
            putExtra("nosub", !translate); putExtra("autotr", translate)
            if (startMs > 0L) putExtra("startms", startMs)
        })
    }

    fun playCast(ctx: Context, url: String, title: String) {
        ctx.startActivity(base(ctx).apply {
            putExtra("url", url); putExtra("title", title); putExtra("nosub", true); putExtra("landscape", true)
        })
    }

    fun playLink(ctx: Context, url: String, title: String, ref: String, ua: String, cookie: String, startMs: Long = 0L) {
        ctx.startActivity(base(ctx).apply {
            putExtra("url", url); putExtra("title", title)
            if (ref.isNotEmpty()) putExtra("ref", ref); if (ua.isNotEmpty()) putExtra("ua", ua); if (cookie.isNotEmpty()) putExtra("cookie", cookie)
            putExtra("nosub", true); putExtra("landscape", true); putExtra("ask", false)
            if (startMs > 0L) putExtra("startms", startMs)
        })
    }

    /** true = اتفتح مباشرة. false = مش حالة مباشرة (نكمّل بالمسار القديم) */
    fun tryOpen(ctx: Context, u: String, how: String, title: String, ref: String, ua: String, ck: String, startMs: Long): Boolean {
        try {
            if (CastCache.isCast(u)) { closeOldPlayer(ctx); playCast(ctx, u, title); return true }
            if (how != Links.VIDEO && how != Links.VIDEO_TR && how != Links.AUTO) return false
            val yid = Links.ytId(u)
            val tr = how == Links.VIDEO_TR
            if (yid != null) {
                if (tr && Cfg.allMainKeys().isEmpty()) return false   // الترجمة محتاجة مفتاح: المسار القديم بيسأل عليه
                closeOldPlayer(ctx); playYt(ctx, yid, title, startMs, tr); return true
            }
            if (Links.kind(u) == Links.Kind.VIDEO || ref.isNotEmpty() || ua.isNotEmpty() || ck.isNotEmpty()) {
                closeOldPlayer(ctx); playLink(ctx, u, title, ref, ua, ck, startMs); return true
            }
        } catch (e: Throwable) { LogStore.err("TvDirect:open", e) }
        return false
    }

    /** فيديو تاني كان شغّال: نقفله عشان الخروج من الجديد مايرجّعش للقديم */
    private fun closeOldPlayer(ctx: Context) {
        try { (ctx as? PlayerActivity)?.let { if (!it.remote) it.finish() } } catch (_: Throwable) {}
    }
}

/**
 * (v570) مصغّرات للفيديوهات اللي جاية بلينك (مش ملف على الجهاز):
 * يوتيوب ← صورة يوتيوب نفسها؛ أي لينك تاني ← لقطة بيحفظها المشغّل مرة واحدة من الفيديو وهو شغّال.
 */
object LinkThumb {
    private val tried = HashSet<String>()

    private fun dir(ctx: Context): File = File(ctx.filesDir, "link_thumbs").also { it.mkdirs() }
    fun fileFor(ctx: Context, id: String): File = File(dir(ctx), Integer.toHexString(id.hashCode()) + "_" + id.length + ".jpg")

    fun ytThumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    /** بيحط الصورة على iv (من الملف المحفوظ أو من يوتيوب). مفيش صورة = بيسيب الخلفية */
    fun into(ctx: Context, r: Recent, iv: ImageView) {
        try {
            val yid = Recents.ytIdOf(r)
            if (yid != null) { com.bumptech.glide.Glide.with(ctx).load(ytThumb(yid)).centerCrop().into(iv); return }
            val f = fileFor(ctx, r.id)
            if (f.exists() && f.length() > 0L) {
                val b = BitmapFactory.decodeFile(f.absolutePath)
                if (b != null) iv.setImageBitmap(b)
            }
        } catch (e: Throwable) { LogStore.err("LinkThumb:into", e) }
    }

    /** المشغّل بينادي دي: لقطة من الفيديو الشغّال تتحفظ مرة واحدة (لينك مش يوتيوب) */
    fun saveIfMissing(ctx: Context, id: String, grab: () -> Bitmap?) {
        if (id.isEmpty()) return
        val f = fileFor(ctx, id)
        if (f.exists() && f.length() > 0L) return
        synchronized(tried) { if (!tried.add(id)) return }
        try {
            val b = grab() ?: run { synchronized(tried) { tried.remove(id) }; return }
            val w = 480; val h = (b.height * (w.toFloat() / b.width)).toInt().coerceAtLeast(2)
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            f.outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (s !== b) b.recycle()
        } catch (e: Throwable) { LogStore.err("LinkThumb:save", e); synchronized(tried) { tried.remove(id) } }
    }
}

/**
 * (v570) متحكّم الموبايل في فيديو من الموبايل نفسه شغّال على التلفزيون: بدل لقطات JPEG صغيرة من التلفزيون (بطيئة وجودتها وحشة)
 * الموبايل بيشغّل نفس الملف عنده بكتم، ماشي مع موضع/تشغيل/سرعة التلفزيون. مفيش نت ولا لقطات.
 */
class MirrorPreview(private val ctx: Context, private val onSize: (Int, Int) -> Unit) {
    private var exo: ExoPlayer? = null
    private var lastSync = 0L
    private var lastSeekAt = 0L

    fun start(sv: SurfaceView, uri: Uri) {
        val p = ExoPlayer.Builder(ctx).build()
        p.volume = 0f
        p.setVideoSurfaceView(sv)
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) onSize(v.width, v.height) }
        })
        p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
        p.playWhenReady = false
        p.prepare()
        exo = p
    }

    val active: Boolean get() = exo != null

    /** من تيك المشغّل (كل 200ms): بيتزامن مرة كل ~600ms */
    fun tick(visible: Boolean) {
        val p = exo ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSync < 600L) return
        lastSync = now
        try {
            val m = LinkSession.mirror
            if (!visible || !m.live || !m.gotFirst) { if (p.playWhenReady) p.playWhenReady = false; return }
            val st = m.st
            val playing = st.optBoolean("playing", false)
            val sp = PosExtrap.speedOf(st.optString("speed", "1")).toFloat()
            if (Math.abs(p.playbackParameters.speed - sp) > 0.01f) p.setPlaybackSpeed(sp)
            val want = m.position(System.currentTimeMillis())
            val dur = p.duration
            val tgt = if (dur > 0L) want.coerceIn(0L, dur - 50L) else want
            val diff = Math.abs(p.currentPosition - tgt)
            if (diff > (if (playing) 900L else 250L) && now - lastSeekAt > 500L) { lastSeekAt = now; p.seekTo(tgt) }
            if (p.playWhenReady != playing) p.playWhenReady = playing
        } catch (e: Throwable) { LogStore.err("MirrorPreview:tick", e) }
    }

    fun release() { try { exo?.release() } catch (_: Throwable) {}; exo = null }
}
