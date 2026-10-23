// Stubs لفحص شكل الاستدعاءات فقط — التواقيع مأخوذة من استخدام Subtitler v475 (مبني فعلًا). مش مشحونة في الـAPK.
package androidx.media3.common
import android.net.Uri

object C { const val TRACK_TYPE_AUDIO = 1; const val TRACK_TYPE_VIDEO = 2; const val TRACK_TYPE_TEXT = 3; const val TIME_UNSET = Long.MIN_VALUE + 1; const val SELECTION_FLAG_DEFAULT = 1; const val USAGE_MEDIA = 1; const val AUDIO_CONTENT_TYPE_MOVIE = 3 }
class AudioAttributes { class Builder { fun setUsage(u: Int) = this; fun setContentType(t: Int) = this; fun build() = AudioAttributes() } }
class VideoSize { val width = 0; val height = 0; val pixelWidthHeightRatio = 1f }
class Format { val language: String? = null; val label: String? = null; val sampleMimeType: String? = null; val width = 0; val height = 0; val channelCount = 0 }
class TrackGroup
class TrackSelectionOverride(val mediaTrackGroup: TrackGroup, trackIndex: Int)
class MediaItem private constructor() {
    companion object { @JvmStatic fun fromUri(u: Uri): MediaItem = MediaItem() }
    class SubtitleConfiguration { class Builder(u: Uri) { fun setMimeType(m: String?) = this; fun setLanguage(l: String?) = this; fun setLabel(l: String?) = this; fun setSelectionFlags(f: Int) = this; fun build() = SubtitleConfiguration() } }
    class Builder { fun setUri(u: Uri?) = this; fun setSubtitleConfigurations(l: List<SubtitleConfiguration>) = this; fun build() = MediaItem() }
}
open class PlaybackException : Exception() { val errorCodeName: String = "" }
class Tracks {
    val groups: List<Group> = emptyList()
    class Group { val type = 0; val length = 0; val isSelected = false; val isSupported = false; val mediaTrackGroup = TrackGroup(); fun getTrackFormat(i: Int) = Format() }
}
class TrackSelectionParameters {
    fun buildUpon() = Builder()
    class Builder { fun setOverrideForType(o: TrackSelectionOverride) = this; fun clearOverridesOfType(t: Int) = this; fun setTrackTypeDisabled(t: Int, d: Boolean) = this; fun setPreferredTextLanguage(l: String?) = this; fun setPreferredAudioLanguage(l: String?) = this; fun build() = TrackSelectionParameters() }
}
interface Player {
    companion object { const val STATE_IDLE = 1; const val STATE_BUFFERING = 2; const val STATE_READY = 3; const val STATE_ENDED = 4; const val REPEAT_MODE_OFF = 0; const val REPEAT_MODE_ONE = 1 }
    interface Listener {
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onTracksChanged(tracks: Tracks) {}
        fun onRenderedFirstFrame() {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
        fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {}
    }
    var playWhenReady: Boolean
    var repeatMode: Int
    val isPlaying: Boolean
    val playbackState: Int
    val currentPosition: Long
    val duration: Long
    val currentTracks: Tracks
    var trackSelectionParameters: TrackSelectionParameters
    fun addListener(l: Listener)
    fun setMediaItem(m: MediaItem, startPositionMs: Long)
    fun setMediaItem(m: MediaItem)
    fun setMediaSource(s: androidx.media3.exoplayer.source.MediaSource, startPositionMs: Long)
    fun setMediaSource(s: androidx.media3.exoplayer.source.MediaSource)
    fun prepare(); fun play(); fun pause(); fun seekTo(ms: Long); fun setPlaybackSpeed(s: Float); fun release()
}
