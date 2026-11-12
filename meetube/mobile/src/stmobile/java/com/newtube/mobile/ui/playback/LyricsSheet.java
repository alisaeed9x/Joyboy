package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * MEETUBE(lyrics): the playing song's lyrics (found by {@link Lyrics}) in a panel under the video, like
 * YouTube Music's Lyrics tab. Timed lyrics light up line by line and keep the sung line in view;
 * plain ones are one selectable text, so any of it can be copied. Same frosted sheet and sizing as
 * the Description panel (DescriptionSheet), shown by the player (showPlayerSheet). The header names
 * the provider; a tap on it lists the others ({@link Chooser}).
 */
final class LyricsSheet {
    /** Light a line up this early, so it is there as it is sung rather than just after. */
    private static final long LEAD_MS = 300;
    /** After the listener scrolls, leave the lyrics where they put them this long. */
    private static final long HANDS_OFF_MS = 4_000;
    private static final long TICK_MS = 200;

    private LyricsSheet() {
    }

    /**
     * {@code position} is the player's position in ms (negative when unknown); {@code showSheet} shows
     * the provider chooser the way the player shows its sheets (showPlayerSheet). {@code transcript}, null
     * when the video has none, opens its transcript in this panel's place (given true when no provider has
     * the song): true when it did. It is also the chooser's last row.
     */
    static BottomSheetDialog create(@NonNull Activity activity, @NonNull String videoId, String title, String channel,
                                    @Nullable View video, @NonNull LongSupplier position,
                                    @NonNull Consumer<BottomSheetDialog> showSheet,
                                    @Nullable Predicate<Boolean> transcript) {
        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_lyrics, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDimAmount(0f); // the playing video above stays as it is, never dimmed
        }
        if (video != null) {
            DescriptionSheet.fillUnder(dialog, content, video);
            if (window != null) {
                leaveVideoFree(window, video);
            }
        } else {
            dialog.setCanceledOnTouchOutside(false); // fullscreen: a tap on the video keeps them too
        }
        bind(activity, content, dialog::dismiss, dialog::isShowing, videoId, title, channel, position, showSheet, transcript);
        return dialog;
    }

    // MEETUBE(lyrics-panel): fresh content resets the scroll and releases the previous follow loop.
    static Runnable bindPanel(@NonNull Activity activity, @NonNull CommentsPanelLayout panel, @NonNull String videoId,
                              String title, String channel, @NonNull LongSupplier position,
                              @NonNull Consumer<BottomSheetDialog> showSheet, @Nullable Predicate<Boolean> transcript) {
        ViewGroup sheet = (ViewGroup) panel.getSheet();
        View body = sheet.findViewById(R.id.lyrics_sheet_body);
        int index = sheet.indexOfChild(body);
        ViewGroup.LayoutParams params = body.getLayoutParams();
        sheet.removeView(body);
        View fresh = LayoutInflater.from(panel.getContext()).inflate(R.layout.mobile_lyrics_body, sheet, false);
        sheet.addView(fresh, index, params);
        return bind(activity, panel, panel::close, panel::isOpen, videoId, title, channel, position, showSheet, transcript);
    }

    private static Runnable bind(Activity activity, View content, Runnable dismiss, BooleanSupplier showing,
                                 String videoId, String title, String channel, LongSupplier position,
                                 Consumer<BottomSheetDialog> showSheet, @Nullable Predicate<Boolean> transcript) {
        boolean[] bound = {true};
        BooleanSupplier active = () -> bound[0] && showing.getAsBoolean();
        Runnable[] stopFollowing = {null};
        Chooser[] chooser = {null};
        View close = content.findViewById(R.id.lyrics_sheet_close);
        View provider = content.findViewById(R.id.lyrics_sheet_provider);
        close.setOnClickListener(v -> dismiss.run());
        provider.setVisibility(View.GONE);
        provider.setOnClickListener(null);
        Runnable release = releaseOnDetach(content.findViewById(R.id.lyrics_sheet_body), () -> {
            if (!bound[0]) {
                return;
            }
            bound[0] = false;
            if (stopFollowing[0] != null) {
                stopFollowing[0].run();
            }
            if (chooser[0] != null) {
                chooser[0].close();
            }
            close.setOnClickListener(null);
            provider.setOnClickListener(null);
        });
        Lyrics.fetch(videoId, title != null ? title : "", channel != null ? channel : "", song -> {
            if (!bound[0]) {
                return; // MEETUBE(lyrics-panel): an answer for a closed or rebound panel
            }
            content.findViewById(R.id.lyrics_sheet_loading).setVisibility(View.GONE);
            if (song == null) {
                if (transcript != null && active.getAsBoolean() && transcript.test(true)) {
                    dismiss.run();
                    return;
                }
                content.findViewById(R.id.lyrics_sheet_empty).setVisibility(View.VISIBLE);
                return;
            }
            Runnable showLyrics = () -> {
                if (!bound[0]) {
                    return;
                }
                if (stopFollowing[0] != null) {
                    stopFollowing[0].run();
                }
                stopFollowing[0] = show(content, song, position, active);
            };
            Runnable showTranscript = transcript == null ? null : () -> {
                if (active.getAsBoolean() && transcript.test(false)) {
                    dismiss.run();
                }
            };
            chooser[0] = new Chooser(activity, song, showSheet, showLyrics, showTranscript);
            provider.setVisibility(View.VISIBLE);
            provider.setOnClickListener(v -> chooser[0].open());
            showLyrics.run();
        });
        return release;
    }

    // MEETUBE(lyrics-panel): dialogs detach on dismiss; panels also release explicitly on close.
    static Runnable releaseOnDetach(View body, Runnable release) {
        View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                v.removeOnAttachStateChangeListener(this);
                release.run();
            }
        };
        body.addOnAttachStateChangeListener(listener);
        return () -> {
            body.removeOnAttachStateChangeListener(listener);
            release.run();
        };
    }

    /**
     * MEETUBE(lyrics): the minimize drag fades the lyrics out with the watch page, and back in if it is
     * let go, as it does the comments panel (MobilePlaybackActivity.applyMorph / resetMorph).
     */
    static void setMorphAlpha(@Nullable BottomSheetDialog sheet, float alpha) {
        Window window = sheet != null ? sheet.getWindow() : null;
        View decor = window != null ? window.peekDecorView() : null;
        if (decor != null) {
            decor.setAlpha(alpha);
        }
    }

    /**
     * The sheet's window ends at the video's bottom edge, and a touch outside it goes to the page
     * (FLAG_NOT_TOUCH_MODAL): the video takes taps as usual - controls, seek, fullscreen - and the
     * lyrics stay open. They close by the X, a drag down or Back.
     */
    static void leaveVideoFree(Window window, View video) {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        // BottomSheetDialog's own listener pads the sheet's top by the status bar once the sheet's top is
        // above it, which in this shortened window it always is (y 0): a status bar of empty glass over
        // the handle. The window never reaches the bar, so nothing of that is needed.
        View container = window.findViewById(com.google.android.material.R.id.container);
        if (container != null) {
            ViewCompat.setOnApplyWindowInsetsListener(container, (v, insets) -> insets);
        }
        View decor = window.getDecorView();
        int[] at = new int[2];
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (decor.getHeight() == 0) {
                return true;
            }
            decor.getLocationOnScreen(at);
            int bottom = at[1] + decor.getHeight();
            video.getLocationOnScreen(at);
            int height = bottom - (at[1] + video.getHeight());
            if (height <= 0 || height == decor.getHeight()) {
                return true; // in place (or the video fills the screen: nothing above to free)
            }
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.BOTTOM;
            params.height = height;
            window.setAttributes(params);
            return false;
        });
    }

    /** The lyrics of the provider the song {@link Lyrics.Song#shown shows}, in place of any shown before. */
    private static Runnable show(View content, Lyrics.Song song, LongSupplier position, BooleanSupplier showing) {
        Lyrics lyrics = song.lyrics(song.shown); // never null: only a provider with lyrics is shown
        String provider = Lyrics.PROVIDERS[song.shown];
        ((TextView) content.findViewById(R.id.lyrics_sheet_provider_name)).setText(provider);
        content.findViewById(R.id.lyrics_sheet_provider).setContentDescription(
                content.getContext().getString(R.string.mobile_lyrics_provider_description, provider));
        NestedScrollView scroll = content.findViewById(R.id.lyrics_sheet_scroll);
        scroll.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
        ((TextView) content.findViewById(R.id.lyrics_sheet_song)).setText(lyrics.title);
        ((TextView) content.findViewById(R.id.lyrics_sheet_artist)).setText(lyrics.artist);
        ((TextView) content.findViewById(R.id.lyrics_sheet_source))
                .setText(content.getContext().getString(R.string.mobile_lyrics_source, lyrics.source));
        LinearLayout lines = content.findViewById(R.id.lyrics_sheet_lines);
        lines.removeAllViews();
        lines.setTag(lyrics.times); // the follow() of lyrics shown before stops
        if (lyrics.times == null) {
            TextView text = line(lines, String.join("\n", lyrics.lines), 16, R.color.mobile_color_on_surface);
            text.setLineSpacing(0, 1.3f);
            text.setTextIsSelectable(true);
            lines.addView(text);
            return () -> {};
        }
        for (String words : lyrics.lines) {
            TextView line = line(lines, words, 22, R.color.mobile_color_on_surface_secondary);
            line.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            line.setPadding(0, 0, 0, Math.round(14 * content.getResources().getDisplayMetrics().density));
            lines.addView(line);
        }
        return follow(scroll, lines, lyrics.times, position, showing);
    }

    /**
     * Lights the line being sung and keeps it a third of the way down, until the sheet closes or other
     * lyrics take the lines' place.
     */
    private static Runnable follow(NestedScrollView scroll, LinearLayout lines, long[] times,
                                   LongSupplier position, BooleanSupplier showing) {
        int dim = ContextCompat.getColor(scroll.getContext(), R.color.mobile_color_on_surface_secondary);
        int lit = ContextCompat.getColor(scroll.getContext(), R.color.mobile_color_on_surface);
        long[] touchedAt = {-HANDS_OFF_MS};
        scroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                touchedAt[0] = SystemClock.uptimeMillis();
            }
            return false;
        });
        Runnable follow = new Runnable() {
            private int mCurrent = -1;

            @Override
            public void run() {
                if (!showing.getAsBoolean() || lines.getTag() != times) {
                    return;
                }
                long at = position.getAsLong() + LEAD_MS;
                int sung = -1;
                while (sung + 1 < times.length && times[sung + 1] <= at) {
                    sung++;
                }
                if (sung != mCurrent) {
                    if (mCurrent >= 0) {
                        ((TextView) lines.getChildAt(mCurrent)).setTextColor(dim);
                    }
                    mCurrent = sung;
                    if (sung >= 0) {
                        View line = lines.getChildAt(sung);
                        ((TextView) line).setTextColor(lit);
                        if (SystemClock.uptimeMillis() - touchedAt[0] > HANDS_OFF_MS) {
                            int y = lines.getTop() + line.getTop() - scroll.getHeight() / 3;
                            scroll.smoothScrollTo(0, Math.max(0, y));
                        }
                    }
                }
                lines.postDelayed(this, TICK_MS);
            }
        };
        lines.post(follow);
        return () -> {
            lines.removeCallbacks(follow);
            scroll.setOnTouchListener(null);
        };
    }

    private static TextView line(View parent, CharSequence text, int sp, int color) {
        TextView view = new TextView(parent.getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(ContextCompat.getColor(parent.getContext(), color));
        return view;
    }

    /**
     * MEETUBE(lyrics): BitChord's provider chooser (LyricsProviderSheet), in the player's single-choice
     * sheet: every provider with what it has for this song - the current one, lyrics found (tap to
     * use), none, being fetched, or not asked yet (tap to fetch). A pick with lyrics in hand switches
     * at once and closes the list; one not asked yet is fetched, and switched to when it answers with
     * lyrics unless another pick came after it. Neither the current provider nor one that had none can
     * be picked. For this song only, as in BitChord: the next one starts from the automatic choice.
     */
    private static final class Chooser {
        private final Activity mActivity;
        private final Lyrics.Song mSong;
        private final Consumer<BottomSheetDialog> mShowSheet;
        /** Shows the lyrics of the provider the song shows. */
        private final Runnable mShowLyrics;
        /** Opens the video's transcript in the lyrics' place; null when it has none. */
        @Nullable
        private final Runnable mShowTranscript;
        /** Providers asked from here whose answer is still to come. */
        private final boolean[] mAsking = new boolean[Lyrics.PROVIDERS.length];
        /** The last pick still being fetched, -1 when none. */
        private int mWanted = -1;
        /** The open list and its rows, null while closed. */
        @Nullable
        private BottomSheetDialog mList;
        @Nullable
        private LinearLayout mRows;

        Chooser(Activity activity, Lyrics.Song song, Consumer<BottomSheetDialog> showSheet, Runnable showLyrics,
                @Nullable Runnable showTranscript) {
            mActivity = activity;
            mSong = song;
            mShowSheet = showSheet;
            mShowLyrics = showLyrics;
            mShowTranscript = showTranscript;
        }

        void open() {
            if (mList != null) {
                return;
            }
            BottomSheetDialog list = Haptics.watch(new BottomSheetDialog(mActivity));
            View content = LayoutInflater.from(mActivity).inflate(R.layout.sheet_mobile_quality, null);
            list.setContentView(content);
            ((TextView) content.findViewById(R.id.quality_sheet_quality_title))
                    .setText(R.string.mobile_lyrics_provider_choose);
            mRows = content.findViewById(R.id.quality_sheet_quality_list);
            mList = list;
            list.setOnDismissListener(d -> {
                mList = null;
                mRows = null;
            });
            fill();
            mShowSheet.accept(list);
        }

        // MEETUBE(lyrics-panel): the chooser belongs to this binding, including late provider answers.
        void close() {
            mWanted = -1;
            if (mList != null) {
                mList.dismiss();
            }
        }

        /** A row per provider: its name over what it has for this song. */
        private void fill() {
            if (mRows == null) {
                return;
            }
            mRows.removeAllViews();
            int secondary = ContextCompat.getColor(mActivity, R.color.mobile_color_on_surface_secondary);
            for (int i = 0; i < Lyrics.PROVIDERS.length; i++) {
                int provider = i;
                boolean current = i == mSong.shown;
                boolean none = mSong.asked(i) && mSong.lyrics(i) == null;
                int status = current ? R.string.mobile_lyrics_provider_current
                        : none ? R.string.mobile_lyrics_provider_not_found
                        : mSong.asked(i) ? R.string.mobile_lyrics_provider_found
                        : mAsking[i] ? R.string.mobile_lyrics_provider_fetching
                        : R.string.mobile_lyrics_provider_not_fetched;
                View row = row(Lyrics.PROVIDERS[i], mActivity.getString(status), secondary, current);
                row.setOnClickListener(v -> pick(provider));
                row.setEnabled(!current && !none);
                row.setAlpha(none ? 0.5f : 1f);
                mRows.addView(row);
            }
            if (mShowTranscript != null) { // the video's captions, in place of any lyrics
                View row = row(mActivity.getString(R.string.mobile_transcript),
                        mActivity.getString(R.string.mobile_transcript_follow), secondary, false);
                row.setOnClickListener(v -> {
                    if (mList != null) {
                        mList.dismiss();
                    }
                    mShowTranscript.run();
                });
                mRows.addView(row);
            }
        }

        /** A row: its name over what it has, in smaller grey. */
        private View row(String name, String status, int secondary, boolean current) {
            SpannableStringBuilder label = new SpannableStringBuilder(name).append('\n');
            int start = label.length();
            label.append(status);
            label.setSpan(new RelativeSizeSpan(0.87f), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(secondary), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            View row = LayoutInflater.from(mActivity).inflate(R.layout.item_mobile_quality_row, mRows, false);
            ((TextView) row.findViewById(R.id.quality_row_label)).setText(label);
            row.findViewById(R.id.quality_row_check).setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            return row;
        }

        private void pick(int provider) {
            if (mSong.asked(provider)) { // lyrics in hand (a provider that had none can't be picked)
                switchTo(provider);
                return;
            }
            mWanted = provider;
            if (!mAsking[provider]) {
                mAsking[provider] = true;
                Lyrics.fetch(mSong, provider, lyrics -> {
                    mAsking[provider] = false;
                    if (lyrics != null && mWanted == provider) {
                        switchTo(provider);
                    } else {
                        fill(); // the row says what came back
                    }
                });
            }
            fill();
        }

        private void switchTo(int provider) {
            mWanted = -1;
            mSong.show(provider);
            mShowLyrics.run();
            if (mList != null) {
                mList.dismiss();
            }
        }
    }
}
