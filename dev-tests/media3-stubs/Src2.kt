package androidx.media3.exoplayer.source
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
interface MediaSource
class MergingMediaSource(vararg s: MediaSource) : MediaSource
class ProgressiveMediaSource { class Factory(f: DataSource.Factory) { fun createMediaSource(m: MediaItem): MediaSource = throw NotImplementedError() } }
class DefaultMediaSourceFactory(f: DataSource.Factory) { fun createMediaSource(m: MediaItem): MediaSource = throw NotImplementedError() }
