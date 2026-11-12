package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.GlassView;
import com.newtube.mobile.ui.common.GlassSource;

/**
 * MEETUBE(issue #188): the floating mini player's type and size (Settings > Customization), and
 * its compact corner buttons, small and set in from the corners so a tap on the picture to expand
 * does not land on Close or Audio.
 *
 * <p>Default is the 16:9 card (pinch to resize it). Minimal and Minimal 2 are a bar with the
 * picture, the title and channel, pause and close; Disabled has no card: minimizing closes.
 */
public final class MiniSize {
    public static final int DEFAULT = 0;
    public static final int MINIMAL = 1; // AMOLED black bar, title column then the picture
    public static final int MINIMAL2 = 2; // frosted glass pill, the picture then the title
    public static final int DISABLED = 3;

    private static final String PREFS = "mini_pip";
    private static final String KEY = "size_percent";
    private static final String KEY_TYPE = "type";
    private static final int MIN_PERCENT = 60;
    private static final int MAX_PERCENT = 160;

    private MiniSize() {
    }

    public static int type(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TYPE, DEFAULT);
    }

    public static void setType(Context context, int type) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_TYPE, type).apply();
    }

    static boolean isBar(Context context) {
        int type = type(context);
        return type == MINIMAL || type == MINIMAL2;
    }

    public static int percent(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 100);
    }

    public static void setPercent(Context context, int percent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY, Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent))).apply();
    }

    private static float dp(Context context) {
        return context.getResources().getDisplayMetrics().density;
    }

    /** The picture's size: the whole card, or the bar's 16:9 window. */
    public static int height(Context context) {
        if (isBar(context)) {
            return Math.round(64 * dp(context)); // a pinch sizes only the Default card
        }
        return Math.round(context.getResources().getDimension(R.dimen.meetube_mini_height) * percent(context) / 100f);
    }

    public static int width(Context context) {
        if (isBar(context)) {
            return Math.round(height(context) * 16f / 9f);
        }
        return Math.round(context.getResources().getDimension(R.dimen.meetube_mini_width) * percent(context) / 100f);
    }

    /** The card's own width: the picture for Default, a bar across the screen otherwise. */
    public static int cardWidth(Context context) {
        if (!isBar(context)) {
            return width(context);
        }
        float dp = dp(context);
        return Math.round(Math.min(context.getResources().getDisplayMetrics().widthPixels - 24 * dp, 520 * dp));
    }

    /** Where the picture starts inside the card: after Minimal's title column. */
    public static int pictureOffset(Context context) {
        return type(context) == MINIMAL ? Math.round(112 * dp(context)) : 0;
    }

    /** A pinch on the Default card: resizes it live and keeps the size. */
    static void pinch(View card, float factor) {
        Context context = card.getContext();
        setPercent(context, Math.round(percent(context) * factor));
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = width(context);
        params.height = height(context);
        card.setLayoutParams(params);
    }

    /** Sizes and styles the card and its buttons; call where the card's buttons are styled. */
    public static void apply(Activity activity) {
        float dp = dp(activity);
        boolean bar = isBar(activity);
        MaterialCardView card = activity.findViewById(R.id.mobile_mini_player);
        if (card != null) {
            ViewGroup.LayoutParams params = card.getLayoutParams();
            params.width = cardWidth(activity);
            params.height = height(activity);
            card.setLayoutParams(params);
            boolean frosted = type(activity) == MINIMAL2;
            card.setCardBackgroundColor(frosted ? Color.TRANSPARENT : Color.BLACK);
            card.setCardElevation(frosted ? 0 : 8 * dp); // a shadow shows through a see-through card
            card.setRadius(frosted ? height(activity) / 2f : 18 * dp);
            frost((ViewGroup) card.getChildAt(0), frosted, activity);
        }
        View frame = activity.findViewById(R.id.mobile_mini_player_frame);
        if (frame != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) frame.getLayoutParams();
            params.width = bar ? width(activity) : ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = bar ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER;
            params.setMarginStart(bar ? pictureOffset(activity) : 0);
            frame.setLayoutParams(params);
        }
        int box = Math.round(36 * dp);
        int pad = Math.round(9 * dp);
        int margin = Math.round(6 * dp);
        View close = activity.findViewById(R.id.mobile_mini_close);
        View audio = activity.findViewById(R.id.mobile_mini_audio);
        View pause = activity.findViewById(R.id.mobile_mini_play_pause);
        if (close != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) close.getLayoutParams();
            params.width = box;
            params.height = box;
            params.gravity = bar ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.TOP | Gravity.END;
            params.setMargins(margin, margin, margin, margin);
            close.setLayoutParams(params);
            close.setPadding(pad, pad, pad, pad);
        }
        if (audio != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) audio.getLayoutParams();
            params.width = bar ? 0 : box; // a bar has no audio button
            params.height = bar ? 0 : box;
            params.setMargins(margin, margin, margin, margin);
            audio.setLayoutParams(params);
            int audioPad = bar ? 0 : pad; // padding on a 0-size button still drew its icon over the bar
            audio.setPadding(audioPad, audioPad, audioPad, audioPad);
            // the buttons' fades set its visibility and alpha, never its scale: 0 keeps it unseen
            audio.setScaleX(bar ? 0f : 1f);
            audio.setScaleY(bar ? 0f : 1f);
        }
        if (pause != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) pause.getLayoutParams();
            params.width = bar ? box : Math.round(40 * dp);
            params.height = params.width;
            params.gravity = bar ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.CENTER;
            params.setMarginEnd(bar ? Math.round(48 * dp) : 0);
            pause.setLayoutParams(params);
        }
        View text = activity.findViewById(R.id.mobile_mini_text);
        if (text != null) {
            text.setVisibility(bar ? View.VISIBLE : View.GONE);
            if (bar) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) text.getLayoutParams();
                boolean first = type(activity) == MINIMAL;
                params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                params.width = first ? Math.round(94 * dp)
                        : cardWidth(activity) - width(activity) - Math.round(108 * dp);
                params.setMarginStart(first ? Math.round(12 * dp) : width(activity) + Math.round(10 * dp));
                text.setLayoutParams(params);
            }
        }
    }

    /**
     * Minimal 2's frost: a GlassView (the nav pill's glass) under the bar's content, blurring the
     * page (Home's or a channel's glass source; elsewhere its tint alone).
     */
    private static void frost(ViewGroup inner, boolean on, Activity activity) {
        View existing = inner.findViewWithTag("mini_frost");
        if (!on) {
            if (existing != null) {
                inner.removeView(existing);
            }
            return;
        }
        if (existing != null) {
            return;
        }
        GlassView glass = new GlassView(activity);
        glass.setTag("mini_frost");
        View source = activity.findViewById(R.id.mobile_blur_target);
        if (source == null) {
            source = activity.findViewById(R.id.mobile_channel_glass_source);
        }
        glass.setSource(source instanceof GlassSource ? (GlassSource) source : null);
        inner.addView(glass, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** The playing video's title and channel into the bar (a no-op for the card). */
    public static void bindText(Activity activity) {
        if (!isBar(activity)) {
            return;
        }
        TextView title = activity.findViewById(R.id.mobile_mini_title);
        TextView channel = activity.findViewById(R.id.mobile_mini_channel);
        Video video = MiniPlayerBridge.getVideo();
        if (title == null || channel == null || video == null) {
            return;
        }
        set(title, video.getTitle());
        set(channel, video.getAuthor());
    }

    private static void set(TextView view, CharSequence text) {
        if (!String.valueOf(view.getText()).equals(String.valueOf(text))) {
            view.setText(text);
        }
    }
}
