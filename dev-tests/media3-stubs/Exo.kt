package androidx.media3.exoplayer
import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.Player

class DefaultRenderersFactory(ctx: Context) { fun setEnableDecoderFallback(b: Boolean): DefaultRenderersFactory = this }
class SeekParameters { companion object { val EXACT = SeekParameters(); val CLOSEST_SYNC = SeekParameters() } }
interface ExoPlayer : Player {
    fun setAudioAttributes(a: androidx.media3.common.AudioAttributes, handleFocus: Boolean)
    fun setHandleAudioBecomingNoisy(b: Boolean)
    fun setSeekParameters(s: SeekParameters?)
    fun setVideoSurfaceView(sv: SurfaceView?)
    class Builder(ctx: Context, rf: DefaultRenderersFactory) { fun build(): ExoPlayer = throw NotImplementedError() }
}
