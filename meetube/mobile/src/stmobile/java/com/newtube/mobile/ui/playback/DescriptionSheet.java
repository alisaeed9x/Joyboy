package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MEETUBE(description): YouTube's Description panel - the title, a Likes / Views / date row and the
 * description, selectable so any of it can be copied. MEETUBE(description-panel): shared by the
 * portrait page's panel and the fullscreen / Shorts dialog.
 */
final class DescriptionSheet {
    /** "1.8B views" -> "1.8B" over "Views"; other shapes (other languages) show whole. */
    private static final Pattern VIEWS = Pattern.compile("^(.*\\d\\S*)\\s+views?$", Pattern.CASE_INSENSITIVE);
    /** "Oct 25, 2009" -> "Oct 25" over "2009", as YouTube stacks it. */
    private static final Pattern DATE = Pattern.compile("^(.+?),?\\s+(\\d{4})$");
    /** "1:23" or "1:02:03" in the description, the shapes YouTube links. */
    private static final Pattern TIMESTAMP = Pattern.compile("\\b(?:(\\d{1,2}):)?(\\d{1,2}):([0-5]\\d)\\b");

    private DescriptionSheet() {
    }

    /** MEETUBE(description): the sections under the description; any left null is not shown. */
    static final class Extras {
        /** Opens the transcript; null for a video without captions. */
        @Nullable
        Runnable transcript;
        @Nullable
        CharSequence channel;
        @Nullable
        CharSequence subscribers;
        @Nullable
        android.graphics.drawable.Drawable avatar;
        /** The watch page's Subscribe: the sheet's one taps it and follows its label and state. */
        @Nullable
        TextView subscribe;
        @Nullable
        Runnable openChannel;
        /** MEETUBE(exact counts): Video details' whole numbers ("12,345,678"); -1 = the short form above. */
        long views = -1;
        long likes = -1;
        /** MEETUBE(issue #175): a description timestamp seeks the video here (ms); null = plain text. */
        @Nullable
        java.util.function.LongConsumer seek;
        /** The video's length: a timestamp past it stays plain text, as on YouTube; 0 or less = unknown. */
        long durationMs;
    }

    /**
     * Empty parts are left out. With {@code video} (the portrait page) the sheet fills the screen
     * under it exactly, however short the text; without it (fullscreen) it is a plain sheet.
     */
    static BottomSheetDialog create(@NonNull Activity activity, CharSequence title, @Nullable CharSequence likes,
                                    @Nullable String views, @Nullable String date, CharSequence description,
                                    @Nullable View video, @NonNull Extras extras) {
        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_description, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDimAmount(0f); // the playing video above stays as it is, never dimmed
        }
        if (video != null) {
            fillUnder(dialog, content, video);
        }

        bind(content, dialog::dismiss, title, likes, views, date, description, extras);
        return dialog;
    }

    // MEETUBE(description-panel): fresh content resets the scroll, rows and Subscribe's listeners.
    static void bindPanel(@NonNull CommentsPanelLayout panel, CharSequence title, @Nullable CharSequence likes,
                          @Nullable String views, @Nullable String date, CharSequence description,
                          @NonNull Extras extras) {
        ViewGroup sheet = (ViewGroup) panel.getSheet();
        View body = sheet.findViewById(R.id.description_sheet_body);
        int index = sheet.indexOfChild(body);
        ViewGroup.LayoutParams params = body.getLayoutParams();
        sheet.removeView(body);
        View fresh = LayoutInflater.from(panel.getContext()).inflate(R.layout.mobile_description_body, sheet, false);
        sheet.addView(fresh, index, params);
        bind(panel, panel::close, title, likes, views, date, description, extras);
    }

    private static void bind(View content, Runnable dismiss, CharSequence title, @Nullable CharSequence likes,
                             @Nullable String views, @Nullable String date, CharSequence description, Extras extras) {
        android.content.Context context = content.getContext();
        content.findViewById(R.id.description_sheet_close).setOnClickListener(v -> dismiss.run());
        ((TextView) content.findViewById(R.id.description_sheet_title)).setText(title);
        TextView text = content.findViewById(R.id.description_sheet_text);
        // A selectable TextView with autoLink still clicks the ClickableSpan under a tap.
        text.setText(withTimestamps(description, extras));
        text.setVisibility(TextUtils.isEmpty(description) ? View.GONE : View.VISIBLE);

        LinearLayout stats = content.findViewById(R.id.description_sheet_stats);
        if (!TextUtils.isEmpty(likes)) {
            addStat(stats, likes, context.getString(R.string.mobile_description_likes));
        }
        if (!TextUtils.isEmpty(views)) {
            Matcher m = VIEWS.matcher(views.trim());
            addStat(stats, m.matches() ? m.group(1) : views, m.matches() ? context.getString(R.string.mobile_description_views) : null);
        }
        if (!TextUtils.isEmpty(date)) {
            Matcher m = DATE.matcher(date.trim());
            addStat(stats, m.matches() ? m.group(1) : date, m.matches() ? m.group(2) : null);
        }
        stats.setVisibility(stats.getChildCount() > 0 ? View.VISIBLE : View.GONE);

        if (extras.transcript != null) {
            content.findViewById(R.id.description_sheet_transcript).setVisibility(View.VISIBLE);
            content.findViewById(R.id.description_sheet_transcript_show).setOnClickListener(v -> {
                dismiss.run();
                extras.transcript.run();
            });
        }
        bindChannel(content, dismiss, extras);

        LinearLayout details = content.findViewById(R.id.description_sheet_details_rows);
        if (!TextUtils.isEmpty(date)) {
            addDetail(details, R.drawable.ic_desc_calendar, R.string.mobile_description_date, date);
        }
        java.text.NumberFormat whole = java.text.NumberFormat.getIntegerInstance();
        if (extras.views > 0) {
            addDetail(details, R.drawable.ic_desc_videos, R.string.mobile_description_views, whole.format(extras.views));
        } else if (!TextUtils.isEmpty(views)) {
            Matcher m = VIEWS.matcher(views.trim());
            addDetail(details, R.drawable.ic_desc_videos, R.string.mobile_description_views, m.matches() ? m.group(1) : views);
        }
        if (extras.likes > 0) {
            addDetail(details, R.drawable.ic_watch_thumb_up_outline, R.string.mobile_description_likes, whole.format(extras.likes));
        } else if (!TextUtils.isEmpty(likes)) {
            addDetail(details, R.drawable.ic_watch_thumb_up_outline, R.string.mobile_description_likes, likes);
        }
        content.findViewById(R.id.description_sheet_details)
                .setVisibility(details.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * Sizes the sheet to the comments panel's place, measured on screen before its first frame: from
     * the video's bottom edge down to where the watch page ends, above the navigation bar. The
     * dialog's frame runs on under that bar, so it is lifted by the difference, and the sheet drops
     * its padding for the bar.
     */
    static void fillUnder(BottomSheetDialog dialog, View content, View video) {
        int minPx = Math.round(240 * content.getResources().getDisplayMetrics().density);
        View page = video.getRootView().findViewById(R.id.mobile_watch_area);
        content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            private final int[] mAt = new int[2];

            @Override
            public boolean onPreDraw() {
                View sheet = (View) content.getParent();
                View frame = sheet != null ? (View) sheet.getParent() : null;
                if (frame == null || frame.getHeight() == 0) {
                    return true;
                }
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) frame.getLayoutParams();
                frame.getLocationOnScreen(mAt);
                int frameBottom = mAt[1] + frame.getHeight() + lp.bottomMargin; // where it ends unlifted
                int bottom = frameBottom;
                if (page != null && page.getHeight() > 0) {
                    page.getLocationOnScreen(mAt);
                    bottom = Math.min(bottom, mAt[1] + page.getHeight());
                }
                int lift = frameBottom - bottom;
                int padBottom = lift > 0 ? 0 : sheet.getPaddingBottom();
                video.getLocationOnScreen(mAt);
                int height = bottom - (mAt[1] + video.getHeight());
                int contentHeight = height - sheet.getPaddingTop() - padBottom;
                if (height < minPx || (contentHeight == content.getMinimumHeight()
                        && lp.bottomMargin == lift && sheet.getPaddingBottom() == padBottom)) {
                    return true;
                }
                if (lp.bottomMargin != lift) {
                    lp.bottomMargin = lift;
                    frame.setLayoutParams(lp);
                }
                sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(), sheet.getPaddingRight(), padBottom);
                content.setMinimumHeight(contentHeight);
                dialog.getBehavior().setMaxHeight(height);
                return false; // laid out again at that height before anything is drawn
            }
        });
    }

    private static void bindChannel(View content, Runnable dismiss, Extras extras) {
        if (TextUtils.isEmpty(extras.channel)) {
            return;
        }
        content.findViewById(R.id.description_sheet_channel).setVisibility(View.VISIBLE);
        ((TextView) content.findViewById(R.id.description_sheet_channel_name)).setText(extras.channel);
        TextView subs = content.findViewById(R.id.description_sheet_channel_subs);
        subs.setText(extras.subscribers);
        subs.setVisibility(TextUtils.isEmpty(extras.subscribers) ? View.GONE : View.VISIBLE);
        android.graphics.drawable.Drawable avatar = extras.avatar;
        if (avatar instanceof android.graphics.drawable.TransitionDrawable) {
            // MEETUBE(issue #159): Glide's cross-fade; a copy restarts it on the placeholder layer.
            android.graphics.drawable.TransitionDrawable fade = (android.graphics.drawable.TransitionDrawable) avatar;
            avatar = fade.getDrawable(fade.getNumberOfLayers() - 1);
        }
        if (avatar != null && avatar.getConstantState() != null) {
            ((android.widget.ImageView) content.findViewById(R.id.description_sheet_channel_avatar))
                    .setImageDrawable(avatar.getConstantState().newDrawable().mutate());
        }
        if (extras.openChannel != null) {
            // The whole row, picture and name (Subscribe takes its own taps).
            content.findViewById(R.id.description_sheet_channel_row).setOnClickListener(v -> {
                dismiss.run();
                extras.openChannel.run();
            });
        }
        TextView subscribe = content.findViewById(R.id.description_sheet_subscribe);
        TextView source = extras.subscribe;
        if (source == null || source.getVisibility() != View.VISIBLE) {
            subscribe.setVisibility(View.GONE);
            return;
        }
        Runnable mirror = () -> {
            subscribe.setText(source.getText());
            subscribe.setSelected(source.isSelected());
            // Not the page's current colour: its pill folds into a mark once subscribed and fades
            // its label out, which left this one blank (issue #159). The same pair it settles on.
            subscribe.setTextColor(ContextCompat.getColor(subscribe.getContext(), source.isSelected()
                    ? R.color.mobile_color_on_surface : R.color.mobile_color_on_inverse_surface));
        };
        mirror.run();
        subscribe.setOnClickListener(v -> source.performClick());
        // The watch page's pill changes when YouTube answers (or after a confirm): follow it.
        View.OnLayoutChangeListener follow = (v, l, t, r, b, ol, ot, or, ob) -> mirror.run();
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                subscribe.post(mirror);
            }
        };
        source.addTextChangedListener(watcher);
        source.addOnLayoutChangeListener(follow);
        // MEETUBE(description-panel): the body detaches on rebind; the dialog's body detaches on dismiss.
        subscribe.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                source.removeTextChangedListener(watcher);
                source.removeOnLayoutChangeListener(follow);
                subscribe.removeCallbacks(mirror);
                subscribe.removeOnAttachStateChangeListener(this);
            }
        });
    }

    /** MEETUBE(issue #175): the description's timestamps seek the video, as on YouTube. */
    private static CharSequence withTimestamps(CharSequence description, Extras extras) {
        java.util.function.LongConsumer seek = extras.seek;
        if (seek == null || TextUtils.isEmpty(description)) {
            return description;
        }
        android.text.SpannableString text = new android.text.SpannableString(description);
        Matcher m = TIMESTAMP.matcher(description);
        while (m.find()) {
            String hours = m.group(1);
            long ms = ((hours != null ? Long.parseLong(hours) * 3600 : 0)
                    + Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3))) * 1000;
            if (extras.durationMs > 0 && ms > extras.durationMs) {
                continue;
            }
            text.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    seek.accept(ms);
                }
            }, m.start(), m.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    /** A Video details row: icon, what it is, then the value at the end. */
    private static void addDetail(LinearLayout rows, int icon, int label, CharSequence value) {
        android.content.Context context = rows.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(12 * density);
        row.setPadding(0, pad, 0, pad);
        android.widget.ImageView image = new android.widget.ImageView(context);
        image.setImageResource(icon);
        image.setColorFilter(ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
        int size = Math.round(20 * density);
        row.addView(image, new LinearLayout.LayoutParams(size, size));
        TextView name = stat(rows, context.getString(label), 14, R.color.mobile_color_on_surface_secondary, false);
        name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); // by its icon, not centered like a stat
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(Math.round(14 * density));
        row.addView(name, nameLp);
        TextView text = stat(rows, value, 14, R.color.mobile_color_on_surface, true);
        text.setFontFeatureSettings("tnum");
        text.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(text);
        rows.addView(row);
    }

    /** One column: the number big and bold, what it counts small beneath. */
    private static void addStat(LinearLayout row, CharSequence value, @Nullable CharSequence label) {
        LinearLayout column = new LinearLayout(row.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(stat(row, value, 18, R.color.mobile_color_on_surface, true));
        if (!TextUtils.isEmpty(label)) {
            column.addView(stat(row, label, 13, R.color.mobile_color_on_surface_secondary, false));
        }
        row.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private static TextView stat(View row, CharSequence text, int sp, int color, boolean bold) {
        TextView view = new TextView(row.getContext());
        view.setText(text);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(ContextCompat.getColor(row.getContext(), color));
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }
}
