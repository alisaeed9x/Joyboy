package com.newtube.mobile.player;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.youtubeapi.formatbuilders.mpdbuilder.YouTubeMPDBuilder;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;

/** MEETUBE(issue #177): one selection for DASH, SABR and recovery, without mutating cached metadata. */
final class PlaybackAudioFormats implements MediaItemFormatInfo {
    private final MediaItemFormatInfo mInfo;
    private final List<MediaFormat> mFormats;
    private final boolean mFiltered;
    final boolean stableVolume;

    private PlaybackAudioFormats(MediaItemFormatInfo info, boolean stable) {
        mInfo = info;
        stableVolume = stable;
        List<MediaFormat> formats = info.getAdaptiveFormats();
        boolean originalAudio = false;
        boolean drcAudio = false;
        if (!stable && formats != null) {
            for (MediaFormat format : formats) {
                if (isAudio(format)) {
                    originalAudio |= !format.isDrc();
                    drcAudio |= format.isDrc();
                }
            }
        }
        mFiltered = originalAudio && drcAudio;
        if (mFiltered) {
            List<MediaFormat> selected = new ArrayList<>();
            for (MediaFormat format : formats) {
                if (!isAudio(format) || !format.isDrc()) {
                    selected.add(format);
                }
            }
            mFormats = Collections.unmodifiableList(selected);
        } else {
            mFormats = formats; // Keep DRC-only audio; ON exposes exactly the service's formats.
        }
    }

    static PlaybackAudioFormats select(MediaItemFormatInfo info, boolean stable) {
        if (info instanceof PlaybackAudioFormats) {
            PlaybackAudioFormats selected = (PlaybackAudioFormats) info;
            if (selected.stableVolume == stable) {
                return selected;
            }
            info = selected.mInfo;
        }
        return new PlaybackAudioFormats(info, stable);
    }

    static boolean usesYouTubeBuilder(MediaItemFormatInfo info) {
        if (info instanceof PlaybackAudioFormats) {
            info = ((PlaybackAudioFormats) info).mInfo;
        }
        return info instanceof YouTubeMediaItemFormatInfo;
    }

    private static boolean isAudio(MediaFormat format) {
        return format != null && format.getMimeType() != null && format.getMimeType().startsWith("audio/");
    }

    @Override public List<MediaFormat> getAdaptiveFormats() { return mFormats; }
    @Override public InputStream createMpdStream() {
        return mFiltered ? YouTubeMPDBuilder.from(this).build() : mInfo.createMpdStream();
    }
    @Override public Observable<InputStream> createMpdStreamObservable() {
        return mFiltered ? Observable.fromCallable(this::createMpdStream) : mInfo.createMpdStreamObservable();
    }

    // The selection changes no other metadata, timing, authorization or service state.
    @Override public List<MediaFormat> getUrlFormats() { return mInfo.getUrlFormats(); }
    @Override public List<MediaSubtitle> getSubtitles() { return mInfo.getSubtitles(); }
    @Override public String getHlsManifestUrl() { return mInfo.getHlsManifestUrl(); }
    @Override public String getDashManifestUrl() { return mInfo.getDashManifestUrl(); }
    @Override public String getLengthSeconds() { return mInfo.getLengthSeconds(); }
    @Override public String getTitle() { return mInfo.getTitle(); }
    @Override public String getAuthor() { return mInfo.getAuthor(); }
    @Override public String getViewCount() { return mInfo.getViewCount(); }
    @Override public String getDescription() { return mInfo.getDescription(); }
    @Override public String getVideoId() { return mInfo.getVideoId(); }
    @Override public String getChannelId() { return mInfo.getChannelId(); }
    @Override public boolean isLive() { return mInfo.isLive(); }
    @Override public boolean isLiveContent() { return mInfo.isLiveContent(); }
    @Override public boolean containsMedia() { return mInfo.containsMedia(); }
    @Override public boolean containsSabrFormats() { return mInfo.containsSabrFormats(); }
    @Override public boolean isSabrVodEligible() { return mInfo.isSabrVodEligible(); }
    @Override public long getMediaReadyAtMs() { return mInfo.getMediaReadyAtMs(); }
    @Override public long getPrerollWaitMs() { return mInfo.getPrerollWaitMs(); }
    @Override public boolean isHlsVodSelected() { return mInfo.isHlsVodSelected(); }
    @Override public boolean containsDashFormats() { return mInfo.containsDashFormats(); }
    @Override public boolean containsHlsUrl() { return mInfo.containsHlsUrl(); }
    @Override public boolean containsDashUrl() { return mInfo.containsDashUrl(); }
    @Override public boolean containsUrlFormats() { return mInfo.containsUrlFormats(); }
    @Override public boolean hasExtendedHlsFormats() { return mInfo.hasExtendedHlsFormats(); }
    @Override public float getVolumeLevel() { return mInfo.getVolumeLevel(); }
    @Override public Float getAudioLoudnessDb() { return mInfo.getAudioLoudnessDb(); }
    @Override public List<String> createUrlList() { return mInfo.createUrlList(); }
    @Override public MediaItemStoryboard createStoryboard() { return mInfo.createStoryboard(); }
    @Override public boolean isUnplayable() { return mInfo.isUnplayable(); }
    @Override public boolean isUnknownError() { return mInfo.isUnknownError(); }
    @Override public boolean isBotCheckRequired() { return mInfo.isBotCheckRequired(); }
    @Override public String getPlayabilityReason() { return mInfo.getPlayabilityReason(); }
    @Override public boolean isStreamSeekable() { return mInfo.isStreamSeekable(); }
    @Override public String getStartTimestamp() { return mInfo.getStartTimestamp(); }
    @Override public String getUploadDate() { return mInfo.getUploadDate(); }
    @Override public long getStartTimeMs() { return mInfo.getStartTimeMs(); }
    @Override public int getStartSegmentNum() { return mInfo.getStartSegmentNum(); }
    @Override public int getSegmentDurationUs() { return mInfo.getSegmentDurationUs(); }
    @Override public String getPaidContentText() { return mInfo.getPaidContentText(); }
    @Override public String getVideoPlaybackUstreamerConfig() { return mInfo.getVideoPlaybackUstreamerConfig(); }
    @Override public String getServerAbrStreamingUrl() { return mInfo.getServerAbrStreamingUrl(); }
    @Override public String getPoToken() { return mInfo.getPoToken(); }
    @Override public String getVisitorCookie() { return mInfo.getVisitorCookie(); }
    @Override public ClientInfo getClientInfo() { return mInfo.getClientInfo(); }
    @Override public boolean isSynced() { return mInfo.isSynced(); }
    @Override public boolean isAuth() { return mInfo.isAuth(); }
    @Override public String getEventId() { return mInfo.getEventId(); }
    @Override public String getVisitorMonitoringData() { return mInfo.getVisitorMonitoringData(); }
    @Override public String getOfParam() { return mInfo.getOfParam(); }
    @Override public String getClickTrackingParams() { return mInfo.getClickTrackingParams(); }
    @Override public void setClickTrackingParams(String params) { mInfo.setClickTrackingParams(params); }
    @Override public boolean isCacheActual() { return mInfo.isCacheActual(); }
    @Override public void sync(MediaItemFormatInfo info) { mInfo.sync(info); }
}
