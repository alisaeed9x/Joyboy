package androidx.media3.exoplayer
import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.Player

class DefaultRenderersFactory(ctx: Context) { fun setEnableDecoderFallback(b: Boolean): DefaultRenderersFactory = this }
interface ExoPlayer : Player {
    fun setVideoSurfaceView(sv: SurfaceView?)
    class Builder(ctx: Context, rf: DefaultRenderersFactory) { fun build(): ExoPlayer = throw NotImplementedError() }
}
