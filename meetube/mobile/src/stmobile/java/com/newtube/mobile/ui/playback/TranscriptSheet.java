package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import okhttp3.Request;

/**
 * MEETUBE(transcript): the video's transcript under it, like YouTube's panel. YouTube's own
 * get_transcript refuses this app (LyricsSources), but its panel is the caption track, and the
 * player already holds those links: one is fetched as json3 (an event per caption) here. The
 * uploader's captions win over the automatic ones. The line being said lights up and stays in view
 * until the person scrolls; a tap plays from a line; the search field keeps the matching lines.
 */
final class TranscriptSheet {
    /** After the person scrolls, leave the list where they put it this long. */
    private static final long HANDS_OFF_MS = 4_000;
    private static final long TICK_MS = 250;

    private TranscriptSheet() {
    }

    /** One caption: when it starts and what is said. */
    private static final class Line {
        final long startMs;
        final String text;
        final String time;

        Line(long startMs, String text) {
            this.startMs = startMs;
            this.text = text;
            this.time = time(startMs);
        }
    }

    /** The captions a transcript is made from, or null when the video has none. */
    @Nullable
    static MediaSubtitle pick(@Nullable List<MediaSubtitle> tracks) {
        if (tracks == null) {
            return null;
        }
        MediaSubtitle auto = null;
        for (MediaSubtitle track : tracks) {
            String url = track != null ? track.getBaseUrl() : null;
            // ~150 machine translations ride along (tlang); the dub's timing variant is a copy.
            if (url == null || url.contains("tlang=") || url.contains("variant=timing-optimized")) {
                continue;
            }
            if (!"asr".equals(track.getType())) {
                return track; // the uploader's own
            }
            if (auto == null) {
                auto = track;
            }
        }
        return auto;
    }

    /** {@code noLyrics}: opened by the Lyrics panel in its place, so the header says the song has none. */
    static BottomSheetDialog create(@NonNull Activity activity, @NonNull MediaSubtitle track, @Nullable View video,
                                    @NonNull LongSupplier position, @NonNull LongConsumer seek, boolean noLyrics) {
        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_transcript, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDimAmount(0f); // the playing video above stays as it is, never dimmed
        }
        if (video != null) {
            DescriptionSheet.fillUnder(dialog, content, video);
            if (window != null) {
                LyricsSheet.leaveVideoFree(window, video);
            }
        } else {
            dialog.setCanceledOnTouchOutside(false); // fullscreen: a tap on the video keeps it too
        }
        bind(content, dialog::dismiss, dialog::isShowing, track, position, seek, noLyrics);
        return dialog;
    }

    // MEETUBE(transcript-panel): a fresh body resets the search, list and their listeners.
    static Runnable bindPanel(@NonNull CommentsPanelLayout panel, @NonNull MediaSubtitle track,
                              @NonNull LongSupplier position, @NonNull LongConsumer seek, boolean noLyrics) {
        ViewGroup sheet = (ViewGroup) panel.getSheet();
        View body = sheet.findViewById(R.id.transcript_sheet_body);
        int index = sheet.indexOfChild(body);
        ViewGroup.LayoutParams params = body.getLayoutParams();
        sheet.removeView(body);
        View fresh = LayoutInflater.from(panel.getContext()).inflate(R.layout.mobile_transcript_body, sheet, false);
        sheet.addView(fresh, index, params);
        return bind(panel, panel::close, panel::isOpen, track, position, seek, noLyrics);
    }

    private static Runnable bind(View content, Runnable dismiss, BooleanSupplier showing, MediaSubtitle track,
                                 LongSupplier position, LongConsumer seek, boolean noLyrics) {
        boolean[] bound = {true};
        BooleanSupplier active = () -> bound[0] && showing.getAsBoolean();
        Runnable[] stopFollowing = {null};
        View close = content.findViewById(R.id.transcript_sheet_close);
        EditText search = content.findViewById(R.id.transcript_sheet_search);
        close.setOnClickListener(v -> dismiss.run());
        Runnable release = LyricsSheet.releaseOnDetach(content.findViewById(R.id.transcript_sheet_body), () -> {
            if (!bound[0]) {
                return;
            }
            bound[0] = false;
            if (stopFollowing[0] != null) {
                stopFollowing[0].run();
            }
            close.setOnClickListener(null);
            if (search.hasFocus()) {
                search.clearFocus();
                android.view.inputmethod.InputMethodManager ime = (android.view.inputmethod.InputMethodManager)
                        content.getContext().getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (ime != null) {
                    ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
                }
            }
        });
        TextView language = content.findViewById(R.id.transcript_sheet_language);
        language.setMaxLines(1);
        language.setText(null);
        language.setVisibility(View.GONE);
        // Without the auto marker (TranslatedCaptionTrack.TRANSLATE_MARKER).
        String name = TextUtils.isEmpty(track.getName()) ? "" : track.getName().replace("*", "").trim();
        if (noLyrics) {
            String none = content.getContext().getString(R.string.mobile_lyrics_none);
            name = name.isEmpty() ? none : none + " · " + name;
            language.setMaxLines(2);
        }
        if (!name.isEmpty()) {
            language.setText(name);
            language.setVisibility(View.VISIBLE);
        }

        Handler main = new Handler(Looper.getMainLooper());
        String url = track.getBaseUrl().replaceAll("([?&])fmt=[^&]*", "$1fmt=json3");
        String json3 = url.contains("fmt=json3") ? url : url + "&fmt=json3";
        Lyrics.POOL.execute(() -> {
            List<Line> lines = null;
            try {
                lines = parse(Lyrics.body(new Request.Builder().url(json3).build()));
            } catch (Exception e) {
                android.util.Log.w("Transcript", "fetch failed: " + e);
            }
            List<Line> answer = lines;
            main.post(() -> {
                if (bound[0]) { // MEETUBE(transcript-panel): ignore the previous binding's answer
                    stopFollowing[0] = show(content, answer, position, seek, active);
                }
            });
        });
        return release;
    }

    private static Runnable show(View content, @Nullable List<Line> lines,
                                 LongSupplier position, LongConsumer seek, BooleanSupplier showing) {
        content.findViewById(R.id.transcript_sheet_loading).setVisibility(View.GONE);
        if (lines == null || lines.isEmpty()) {
            TextView empty = content.findViewById(R.id.transcript_sheet_empty);
            empty.setText(lines == null ? R.string.mobile_transcript_failed : R.string.mobile_transcript_none);
            empty.setVisibility(View.VISIBLE);
            return () -> {};
        }
        RecyclerView list = content.findViewById(R.id.transcript_sheet_list);
        LinearLayoutManager layout = new LinearLayoutManager(list.getContext());
        list.setLayoutManager(layout);
        Adapter adapter = new Adapter(lines, line -> seek.accept(line.startMs));
        list.setAdapter(adapter);
        list.setVisibility(View.VISIBLE);

        EditText search = content.findViewById(R.id.transcript_sheet_search);
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
                list.scrollToPosition(0);
            }
        };
        search.addTextChangedListener(watcher);

        long[] touchedAt = {-HANDS_OFF_MS};
        list.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                touchedAt[0] = SystemClock.uptimeMillis();
            }
            return false;
        });
        Runnable follow = new Runnable() {
            @Override
            public void run() {
                if (!showing.getAsBoolean() || !list.isAttachedToWindow()) {
                    return; // MEETUBE(transcript-panel): the panel stays attached while closed
                }
                int said = adapter.said(position.getAsLong());
                if (said >= 0 && SystemClock.uptimeMillis() - touchedAt[0] > HANDS_OFF_MS
                        && TextUtils.isEmpty(search.getText())) {
                    LinearSmoothScroller scroller = new LinearSmoothScroller(list.getContext()) {
                        @Override
                        protected int getVerticalSnapPreference() {
                            return SNAP_TO_START;
                        }

                        @Override
                        public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd,
                                                    int snapPreference) {
                            return boxStart + (boxEnd - boxStart) / 4 - viewStart; // a quarter down
                        }
                    };
                    View row = layout.findViewByPosition(said);
                    if (row == null || Math.abs(row.getTop() - list.getHeight() / 4) > row.getHeight()) {
                        scroller.setTargetPosition(said);
                        layout.startSmoothScroll(scroller);
                    }
                }
                list.postDelayed(this, TICK_MS);
            }
        };
        list.post(follow);
        return () -> {
            list.removeCallbacks(follow);
            list.stopScroll();
            list.setOnTouchListener(null);
            search.removeTextChangedListener(watcher);
        };
    }

    /** json3: {"events":[{"tStartMs":..,"segs":[{"utf8":".."}]}]}; a line-break event carries no words. */
    @NonNull
    private static List<Line> parse(String body) throws Exception {
        JSONArray events = new JSONObject(body).optJSONArray("events");
        List<Line> lines = new ArrayList<>();
        if (events == null) {
            return lines;
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            JSONArray segs = event != null ? event.optJSONArray("segs") : null;
            if (segs == null) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < segs.length(); j++) {
                JSONObject seg = segs.optJSONObject(j);
                if (seg != null) {
                    text.append(seg.optString("utf8"));
                }
            }
            String words = text.toString().replace('\n', ' ').replaceAll("\\s+", " ").trim();
            if (!words.isEmpty()) {
                lines.add(new Line(event.optLong("tStartMs"), words));
            }
        }
        return lines;
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<Line> mAll;
        private List<Line> mShown;
        private final java.util.function.Consumer<Line> mOnTap;
        @Nullable
        private Line mSaid;

        Adapter(List<Line> lines, java.util.function.Consumer<Line> onTap) {
            mAll = lines;
            mShown = lines;
            mOnTap = onTap;
        }

        void filter(String query) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) {
                mShown = mAll;
            } else {
                mShown = new ArrayList<>();
                for (Line line : mAll) {
                    if (line.text.toLowerCase(Locale.ROOT).contains(q)) {
                        mShown.add(line);
                    }
                }
            }
            notifyDataSetChanged();
        }

        /** Lights the line said at {@code positionMs}; its place in the shown list, or -1. */
        int said(long positionMs) {
            Line said = null;
            for (Line line : mAll) { // ponytail: linear scan per tick, a binary search if transcripts grow huge
                if (line.startMs > positionMs) {
                    break;
                }
                said = line;
            }
            if (said != mSaid) {
                int before = mShown.indexOf(mSaid);
                mSaid = said;
                if (before >= 0) {
                    notifyItemChanged(before);
                }
                int now = mShown.indexOf(said);
                if (now >= 0) {
                    notifyItemChanged(now);
                }
            }
            return said != null ? mShown.indexOf(said) : -1;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_mobile_transcript_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Line line = mShown.get(position);
            boolean said = line == mSaid;
            holder.time.setText(line.time);
            holder.text.setText(line.text);
            holder.text.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                    said ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary));
            holder.itemView.setSelected(said);
            holder.itemView.setOnClickListener(v -> mOnTap.accept(line));
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView time;
            final TextView text;

            Holder(View view) {
                super(view);
                time = view.findViewById(R.id.transcript_row_time);
                text = view.findViewById(R.id.transcript_row_text);
            }
        }
    }
}
