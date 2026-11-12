package com.newtube.mobile.ui.playback;

/**
 * NEWTUBE(snackbar): what a rating's Undo taps to put back the rating the video had before the tap.
 * Re-tapping the same thumb is not enough: liked, then Dislike, then Undo would clear the dislike
 * and leave the video unrated instead of liked. Thumbs are toggles, and liking clears a dislike
 * (and the reverse), so one tap always gets there.
 */
final class RatingUndo {
    static final int NONE = 0;
    static final int LIKE = 1;
    static final int DISLIKE = 2;

    private RatingUndo() {
    }

    static int rating(boolean likeOn, boolean dislikeOn) {
        return likeOn ? LIKE : dislikeOn ? DISLIKE : NONE;
    }

    /**
     * The thumb to tap so that {@code now} becomes {@code target}: {@code likeId}, {@code dislikeId},
     * or 0 when they already match.
     */
    static int tapFor(int now, int target, int likeId, int dislikeId) {
        if (now == target) {
            return 0;
        }
        if (target == LIKE) {
            return likeId;
        }
        if (target == DISLIKE) {
            return dislikeId;
        }
        return now == LIKE ? likeId : dislikeId; // back to unrated: switch off the one that is on
    }

    /** Digits, maybe in groups of three ("133", "1,234", "12 345"); "1.2K" is not one. */
    private static final java.util.regex.Pattern PLAIN_COUNT =
            java.util.regex.Pattern.compile("\\d{1,15}(?:[,.'\\s\u00a0\u202f]\\d{3})*");

    /**
     * MEETUBE(issue #45): the like count after this account's like came or went, as YouTube's
     * count does. {@code raw} is YouTube's count, which held the like when {@code likedInRaw}.
     * A rounded count ("1.2K") stays as it is.
     */
    static CharSequence likeCount(CharSequence raw, boolean likedInRaw, boolean likedNow) {
        if (raw == null || likedInRaw == likedNow || !PLAIN_COUNT.matcher(raw).matches()) {
            return raw;
        }
        String text = raw.toString();
        String digits = text.replaceAll("\\D", "");
        if (digits.length() > 15) {
            return raw;
        }
        long count = Long.parseLong(digits) + (likedNow ? 1 : -1);
        if (count < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(Long.toString(count));
        String separators = text.replaceAll("\\d", "");
        for (int i = out.length() - 3; i > 0 && !separators.isEmpty(); i -= 3) {
            out.insert(i, separators.charAt(0)); // grouped as YouTube grouped it
        }
        return out.toString();
    }
}
