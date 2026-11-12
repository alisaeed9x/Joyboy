package com.liskovsoft.youtubeapi.search;

import android.util.Base64;

import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;

/**
 * Turns {@link SearchOptions} into YouTube's {@code sp} search param: a small protobuf, field 1 =
 * sort, field 2 = the filters. The field numbers and values are the ones YouTube's WEB "Search
 * filters" dialog sends (read from its searchFilterGroupRenderer params, 2026-10); the TV client
 * honours them too. It used to be a hand-filled table, and every combination missing from it
 * searched with no filter at all.
 */
final class SearchFilterHelper {
    private SearchFilterHelper() {
    }

    // {option, field number, value} inside the filters message, in field order.
    private static final int[][] FILTERS = {
            {SearchOptions.UPLOAD_DATE_TODAY, 1, 2},
            {SearchOptions.UPLOAD_DATE_THIS_WEEK, 1, 3},
            {SearchOptions.UPLOAD_DATE_THIS_MONTH, 1, 4},
            {SearchOptions.UPLOAD_DATE_THIS_YEAR, 1, 5},
            {SearchOptions.TYPE_VIDEO, 2, 1},
            {SearchOptions.TYPE_SHORTS, 2, 9},
            {SearchOptions.TYPE_CHANNEL, 2, 2},
            {SearchOptions.TYPE_PLAYLIST, 2, 3},
            {SearchOptions.TYPE_MOVIE, 2, 4},
            {SearchOptions.DURATION_UNDER_3, 3, 4},
            {SearchOptions.DURATION_BETWEEN_3_20, 3, 5},
            {SearchOptions.DURATION_OVER_20, 3, 2},
            {SearchOptions.FEATURE_HD, 4, 1},
            {SearchOptions.FEATURE_SUBTITLES, 5, 1},
            {SearchOptions.FEATURE_CREATIVE_COMMONS, 6, 1},
            {SearchOptions.FEATURE_3D, 7, 1},
            {SearchOptions.FEATURE_LIVE, 8, 1},
            {SearchOptions.FEATURE_PURCHASED, 9, 1},
            {SearchOptions.FEATURE_4K, 14, 1},
            {SearchOptions.FEATURE_360, 15, 1},
            {SearchOptions.FEATURE_LOCATION, 23, 1},
            {SearchOptions.FEATURE_HDR, 25, 1},
            {SearchOptions.FEATURE_VR180, 26, 1},
    };

    /** The URL-encoded {@code sp} param, or null for no filter. */
    public static String toParams(int options) {
        byte[] proto = options == -1 ? new byte[0] : proto(options);
        if (proto.length == 0) {
            return null;
        }
        try {
            return URLEncoder.encode(Base64.encodeToString(proto, Base64.NO_WRAP), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] proto(int options) {
        ByteArrayOutputStream filters = new ByteArrayOutputStream();
        int lastField = 0;
        for (int[] filter : FILTERS) {
            if ((options & filter[0]) != 0 && filter[1] != lastField) { // one value per field
                field(filters, filter[1], filter[2]);
                lastField = filter[1];
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if ((options & SearchOptions.SORT_BY_VIEW_COUNT) != 0) {
            field(out, 1, 3);
        }
        if (filters.size() > 0) {
            varint(out, 2 << 3 | 2);
            varint(out, filters.size());
            out.write(filters.toByteArray(), 0, filters.size());
        }
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, int number, int value) {
        varint(out, number << 3);
        varint(out, value);
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
