package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * Search filter flags, OR-ed together. The set mirrors YouTube's own "Search filters" dialog
 * (2026): it no longer offers "Last hour", nor sorting by upload date or rating.
 */
public interface SearchOptions {
    int UPLOAD_DATE_TODAY = 0b10;
    int UPLOAD_DATE_THIS_WEEK = 0b100;
    int UPLOAD_DATE_THIS_MONTH = 0b1000;
    int UPLOAD_DATE_THIS_YEAR = 0b10000;

    int DURATION_UNDER_3 = 0b100000;
    int DURATION_BETWEEN_3_20 = 0b1000000;
    int DURATION_OVER_20 = 0b10000000;

    int TYPE_VIDEO = 0b100000000;
    int TYPE_CHANNEL = 0b1000000000;
    int TYPE_PLAYLIST = 0b10000000000;
    int TYPE_MOVIE = 0b100000000000;

    int FEATURE_LIVE = 0b1000000000000;
    int FEATURE_4K = 0b10000000000000;
    int FEATURE_HDR = 0b100000000000000;

    int SORT_BY_RELEVANCE = 0b1000000000000000;
    int SORT_BY_VIEW_COUNT = 0b100000000000000000;

    int FEATURE_HD = 1 << 19;
    int FEATURE_SUBTITLES = 1 << 20;
    int FEATURE_CREATIVE_COMMONS = 1 << 21;
    int FEATURE_360 = 1 << 22;
    int FEATURE_VR180 = 1 << 23;
    int FEATURE_3D = 1 << 24;
    int FEATURE_LOCATION = 1 << 25;
    int FEATURE_PURCHASED = 1 << 26;
    int TYPE_SHORTS = 1 << 27;
}
