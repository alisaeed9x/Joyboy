// Stubs لفحص شكل الاستدعاءات فقط — التواقيع مأخوذة من استخدام Subtitler v475 (مبني فعلًا). مش مشحونة في الـAPK.
package androidx.media3.common
import android.net.Uri

object C { const val TRACK_TYPE_AUDIO = 1; const val TRACK_TYPE_VIDEO = 2; const val TRACK_TYPE_TEXT = 3; const val TIME_UNSET = Long.MIN_VALUE + 1 }
class Format { val language: String? = null; val label: String? = null; val sampleMimeType: String? = null; val width = 0; val height = 0; val channelCount = 0 }
class TrackGroup
class TrackSelectionOverride(val mediaTrackGroup: TrackGroup, trackIndex: Int)
class MediaItem private constructor() { companion object { @JvmStatic fun fromUri(u: Uri): MediaItem = MediaItem() } }
open class PlaybackException : Exception() { val errorCodeName: String = "" }
class Tracks {
    val groups: List<Group> = emptyList()
    class Group { val type = 0; val length = 0; val isSelected = false; val isSupported = false; val mediaTrackGroup = TrackGroup(); fun getTrackFormat(i: Int) = Format() }
}
class TrackSelectionParameters {
    fun buildUpon() = Builder()
    class Builder { fun setOverrideForType(o: TrackSelectionOverride) = this; fun clearOverridesOfType(t: Int) = this; fun build() = TrackSelectionParameters() }
}
interface Player {
    companion object { const val STATE_IDLE = 1; const val STATE_BUFFERING = 2; const val STATE_READY = 3; const val STATE_ENDED = 4 }
    interface Listener {
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onTracksChanged(tracks: Tracks) {}
        fun onRenderedFirstFrame() {}
    }
    var playWhenReady: Boolean
    val isPlaying: Boolean
    val playbackState: Int
    val currentPosition: Long
    val duration: Long
    val currentTracks: Tracks
    var trackSelectionParameters: TrackSelectionParameters
    fun addListener(l: Listener)
    fun setMediaItem(m: MediaItem, startPositionMs: Long)
    fun setMediaItem(m: MediaItem)
    fun prepare(); fun play(); fun pause(); fun seekTo(ms: Long); fun setPlaybackSpeed(s: Float); fun release()
}
