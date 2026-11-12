package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.slider.Slider;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.Locale;

/**
 * MEETUBE(customize): Customization's pickers drawn on the page instead of behind a sheet: a choice
 * becomes a strip of tiles, each a little picture of what it does (a phone in that theme, Home as a
 * feed or as rows, the app icon itself), and a tap picks it with a bounce. Interface size is a
 * slider with a live preview. The row keeps its choice's title and options, so search still finds
 * it and glows it.
 */
final class Tiles {
    // What a tile's picture shows.
    static final int THEME_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;
    static final int FEED = 3;
    static final int SHELVES = 4;
    static final int LIST = 5;
    static final int GRID = 6;
    static final int THUMB_DEFAULT = 7;
    static final int THUMB_START = 8;
    static final int THUMB_MIDDLE = 9;
    static final int THUMB_END = 10;
    /** The option's own icon (the app icon picker). */
    static final int ICON = 11;
    /** The option's own loader, spinning on a dark stage (white loaders vanish on a light card). */
    static final int LOADER = 12;
    static final int WATCH_COMPACT = 13;
    static final int WATCH_CLASSIC = 14;
    /** Search's round button in that corner, the bar slid away. */
    static final int FAB_RIGHT = 15;
    static final int FAB_LEFT = 16;
    // The bottom bar with its lit tab's glow.
    static final int GLOW_WHITE = 17;
    static final int GLOW_TABS = 18;
    static final int GLOW_ACCENT = 19;
    static final int GLOW_OFF = 20;
    // A switch's picture (toggle): what it turns on.
    static final int FX_WATCHED = 21;
    static final int FX_REFRESH = 22;
    static final int FX_SHORTS = 23;
    static final int FX_GLASS = 24;
    static final int FX_FROST = 25;
    static final int FX_MOTION = 26;
    static final int FX_PROGRESS = 27;
    static final int FX_SEARCH = 28;
    static final int FX_TYPING = 29;
    static final int FX_LABELS = 30;
    static final int FX_TOP_BAR = 31;
    // The player's buttons over a video (FX_FROST: frosted discs).
    static final int BUTTONS_SEE_THROUGH = 32;
    static final int BUTTONS_MINIMAL = 33;
    /** The top bar's brand pill: the icon, then the app's name. */
    static final int FX_BRAND_NAME = 34;
    // MEETUBE(issue #188): the mini player types, and the chip styles.
    static final int MINI_DEFAULT = 35;
    static final int MINI_MINIMAL = 36;
    static final int MINI_MINIMAL2 = 37;
    static final int MINI_OFF = 38;
    static final int CHIPS_SEGMENT = 39;
    static final int CHIPS_PILLS = 40;

    private static final float OFF_ALPHA = 0.55f;

    private Tiles() {
    }

    /**
     * {@code toggle} (a switch row) with a picture of what it does in front, in colour when on
     * and greyed out when off. A tap flips it in place, without rebuilding the page.
     */
    static SettingsRow toggle(@NonNull SettingsRow toggle, int picture) {
        SettingsRow row = toggle.asCustom();
        row.custom = (parent, page) -> {
            Context context = parent.getContext();
            View view = android.view.LayoutInflater.from(context).inflate(R.layout.item_mobile_settings_row, parent, false);
            ImageView icon = view.findViewById(R.id.settings_row_icon);
            TextView title = view.findViewById(R.id.settings_row_title);
            TextView summary = view.findViewById(R.id.settings_row_summary);
            android.widget.CompoundButton toggleView = view.findViewById(R.id.settings_row_switch);

            Picture drawable = new Picture(context, picture);
            icon.setImageDrawable(drawable);
            icon.setImageTintList(null);
            icon.setScaleType(ImageView.ScaleType.FIT_XY);
            int pad = dp(context, 5);
            icon.setPadding(pad, pad, pad, pad);
            GradientDrawable card = new GradientDrawable();
            card.setCornerRadius(dp(context, 12));
            card.setColor(ContextCompat.getColor(context, R.color.mobile_color_card));
            card.setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.mobile_color_divider));
            icon.setBackground(card);
            ViewGroup.MarginLayoutParams iconParams = (ViewGroup.MarginLayoutParams) icon.getLayoutParams();
            iconParams.width = dp(context, 76);
            iconParams.height = dp(context, 52);
            icon.setVisibility(View.VISIBLE);

            title.setText(row.title);
            CharSequence text = row.summaryText();
            summary.setText(text);
            summary.setVisibility(text != null ? View.VISIBLE : View.GONE);
            view.setContentDescription(row.title + (text != null ? ", " + text : ""));
            view.setAccessibilityDelegate(SettingsAdapter.SwitchRole.INSTANCE);

            boolean on = row.checked != null && row.checked.getAsBoolean();
            toggleView.setVisibility(View.VISIBLE);
            toggleView.setChecked(on);
            drawable.setLit(on);
            icon.setAlpha(on ? 1f : OFF_ALPHA);
            view.setOnClickListener(v -> {
                if (row.checked == null || row.toggle == null) {
                    return;
                }
                boolean next = !row.checked.getAsBoolean();
                Haptics.click(v);
                toggleView.setChecked(next);
                drawable.setLit(next);
                icon.animate().alpha(next ? 1f : OFF_ALPHA).setDuration(200).start();
                if (next) {
                    bounce(icon);
                }
                row.toggle.set(next);
                if (row.restart && page instanceof SettingsPageFragment) {
                    ((SettingsPageFragment) page).offerRestart();
                }
            });
            return view;
        };
        return row;
    }

    /** {@code choice} (a bound choice row) as tiles {@code heightDp} tall, a picture per option. */
    static SettingsRow of(@NonNull SettingsRow choice, int heightDp, int... pictures) {
        SettingsRow row = choice.asCustom();
        row.custom = (parent, page) -> strip(parent, page, row, heightDp, pictures);
        return row;
    }

    private static View strip(ViewGroup parent, SettingsAdapter.Listener page, SettingsRow row, int heightDp, int[] pictures) {
        Context context = parent.getContext();
        LinearLayout root = column(context);
        TextView summary = heading(root, row.title);

        // Four tiles a row at most: the loader picker has eight.
        int count = row.options.size();
        int columns = Math.min(4, count);
        GridLayout strip = new GridLayout(context);
        strip.setColumnCount(columns);
        strip.setClipChildren(false);
        root.setClipChildren(false);
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stripParams.topMargin = dp(context, 12);
        root.addView(strip, stripParams);

        for (int i = 0; i < count; i++) {
            SettingsRow.Option option = row.options.get(i);
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setContentDescription(option.label);

            FrameLayout frame = new FrameLayout(context);
            ImageView picture = new ImageView(context);
            boolean own = pictures[i] == ICON || pictures[i] == LOADER;
            int picturePad = dp(context, own ? 14 : pictures[i] >= GLOW_WHITE ? 6 : 8);
            picture.setPadding(picturePad, picturePad, picturePad, picturePad);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Drawable drawable = own ? ContextCompat.getDrawable(context, option.icon) : new Picture(context, pictures[i]);
            picture.setImageDrawable(drawable);
            if (pictures[i] == LOADER) {
                GradientDrawable stage = new GradientDrawable();
                stage.setCornerRadius(dp(context, 12));
                stage.setColor(0xFF1C1C1E);
                picture.setBackground(new android.graphics.drawable.InsetDrawable(stage, dp(context, 6)));
                if (drawable instanceof android.graphics.drawable.Animatable) {
                    ((android.graphics.drawable.Animatable) drawable).start();
                }
            }
            frame.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView check = new ImageView(context);
            check.setImageResource(R.drawable.ic_mobile_check);
            check.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            int checkPad = dp(context, 3);
            check.setPadding(checkPad, checkPad, checkPad, checkPad);
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL);
            badge.setColor(ContextCompat.getColor(context, R.color.mobile_color_primary));
            check.setBackground(badge);
            FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(context, 20), dp(context, 20),
                    Gravity.TOP | Gravity.END);
            checkParams.setMargins(0, dp(context, 6), dp(context, 6), 0);
            frame.addView(check, checkParams);
            tile.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));

            TextView label = new TextView(context);
            label.setText(option.label);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(context, 6);
            tile.addView(label, labelParams);

            int column = i % columns;
            GridLayout.LayoutParams tileParams = new GridLayout.LayoutParams(GridLayout.spec(i / columns),
                    GridLayout.spec(column, 1f));
            tileParams.width = 0;
            tileParams.setMarginStart(column == 0 ? 0 : dp(context, 5));
            tileParams.setMarginEnd(column == columns - 1 ? 0 : dp(context, 5));
            tileParams.topMargin = i < columns ? 0 : dp(context, 12);
            strip.addView(tile, tileParams);

            int which = i;
            tile.setOnClickListener(v -> {
                bounce(frame);
                if (which == row.currentIndex()) {
                    Haptics.tick(v);
                    return;
                }
                Haptics.click(v);
                select(strip, which, row, summary, true);
                // After the bounce: a theme change redraws every screen.
                v.postDelayed(() -> {
                    row.pick.pick(option.value);
                    if (row.restart && page instanceof SettingsPageFragment) {
                        ((SettingsPageFragment) page).offerRestart();
                    }
                }, 160);
            });
        }
        select(strip, row.currentIndex(), row, summary, false);
        return root;
    }

    /** Lights the picked tile: a red ring, a check that pops in, its name in red. */
    private static void select(ViewGroup strip, int index, SettingsRow row, TextView summary, boolean animate) {
        Context context = strip.getContext();
        for (int i = 0; i < strip.getChildCount(); i++) {
            ViewGroup tile = (ViewGroup) strip.getChildAt(i);
            FrameLayout frame = (FrameLayout) tile.getChildAt(0);
            TextView label = (TextView) tile.getChildAt(1);
            View check = frame.getChildAt(1);
            boolean on = i == index;
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(context, 16));
            background.setColor(ContextCompat.getColor(context, R.color.mobile_color_card));
            background.setStroke(dp(context, on ? 2 : 1), ContextCompat.getColor(context,
                    on ? R.color.mobile_color_primary : R.color.mobile_color_divider));
            frame.setBackground(background);
            label.setTextColor(ContextCompat.getColor(context,
                    on ? R.color.mobile_color_primary : R.color.mobile_color_on_surface_secondary));
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            tile.setSelected(on);
            check.animate().cancel();
            if (on && animate) {
                check.setVisibility(View.VISIBLE);
                check.setScaleX(0f);
                check.setScaleY(0f);
                check.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator(3f)).start();
            } else {
                check.setScaleX(1f);
                check.setScaleY(1f);
                check.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        }
        CharSequence description = index >= 0 ? row.options.get(index).description : null;
        summary.setText(description);
        summary.setVisibility(description != null ? View.VISIBLE : View.GONE);
    }

    private static void bounce(View view) {
        view.animate().cancel();
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction(() ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(new OvershootInterpolator(3f)).start())
                .start();
    }

    /** Interface size as a slider: the preview below grows and shrinks as it moves; saved on release. */
    static SettingsRow slider(@NonNull SettingsRow choice, @NonNull MainUIData data) {
        SettingsRow row = choice.asCustom();
        row.custom = (parent, page) -> {
            Context context = parent.getContext();
            LinearLayout root = column(context);
            TextView value = heading(root, row.title);
            float stored = data.getUIScale();
            int start = Math.max(80, Math.min(130, Math.round(stored * 20) * 5));
            value.setText(percent(start));
            value.setVisibility(View.VISIBLE);

            // Sizes relative to now: this screen is already drawn at the stored scale.
            LinearLayout preview = new LinearLayout(context);
            preview.setOrientation(LinearLayout.HORIZONTAL);
            preview.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(context, 12);
            preview.setPadding(pad, pad, pad, pad);
            GradientDrawable card = new GradientDrawable();
            card.setCornerRadius(dp(context, 16));
            card.setColor(ContextCompat.getColor(context, R.color.mobile_color_card));
            preview.setBackground(card);
            ImageView thumb = new ImageView(context);
            thumb.setImageDrawable(new Picture(context, THUMB_DEFAULT));
            preview.addView(thumb, new LinearLayout.LayoutParams(dp(context, 96), dp(context, 54)));
            LinearLayout lines = new LinearLayout(context);
            lines.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(context);
            title.setText(R.string.mobile_settings_interface_size_sample);
            title.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
            title.setMaxLines(2);
            TextView meta = new TextView(context);
            meta.setText(R.string.mobile_settings_interface_size_sample_meta);
            meta.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
            lines.addView(title);
            lines.addView(meta);
            LinearLayout.LayoutParams linesParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            linesParams.setMarginStart(dp(context, 12));
            preview.addView(lines, linesParams);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            previewParams.topMargin = dp(context, 12);
            root.addView(preview, previewParams);

            Slider slider = new Slider(context);
            slider.setValueFrom(80);
            slider.setValueTo(130);
            slider.setStepSize(5);
            slider.setValue(start);
            slider.setLabelFormatter(v -> percent(Math.round(v)));
            slider.setContentDescription(row.title);
            root.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            Runnable[] resize = new Runnable[1];
            resize[0] = () -> {
                float k = slider.getValue() / (stored * 100f);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15 * k);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * k);
                thumb.getLayoutParams().width = Math.round(dp(context, 96) * k);
                thumb.getLayoutParams().height = Math.round(dp(context, 54) * k);
                thumb.requestLayout();
                value.setText(percent(Math.round(slider.getValue())));
            };
            resize[0].run();
            slider.addOnChangeListener((s, v, fromUser) -> {
                if (fromUser) {
                    Haptics.tick(s);
                    resize[0].run();
                }
            });
            slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider s) {
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider s) {
                    float scale = s.getValue() / 100f;
                    if (Math.abs(scale - data.getUIScale()) > 0.001f) {
                        data.setUIScale(scale);
                        if (page instanceof SettingsPageFragment) {
                            ((SettingsPageFragment) page).offerRestart();
                        }
                    }
                }
            });
            return root;
        };
        return row;
    }

    private static String percent(int value) {
        return String.format(Locale.US, "%d%%", value);
    }

    static LinearLayout column(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int inset = context.getResources().getDimensionPixelSize(R.dimen.mobile_settings_row_inset);
        root.setPadding(inset, dp(context, 12), inset, dp(context, 12));
        return root;
    }

    /** The row's title like any row's; returns the summary line under it (hidden until set). */
    static TextView heading(LinearLayout root, CharSequence text) {
        Context context = root.getContext();
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(R.dimen.mobile_settings_row_title_size));
        root.addView(title);
        TextView summary = new TextView(context);
        summary.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(R.dimen.mobile_settings_row_summary_size));
        summary.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 2);
        root.addView(summary, params);
        return summary;
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /**
     * A tile's little picture, drawn from rounded blocks: a phone screen with Home's feed, its rows
     * or a list on it (in a given theme's colours), or a thumbnail with where its frame comes from.
     */
    private static final class Picture extends Drawable {
        private static final int ACCENT = 0xFFFF453A;
        private final int mKind;
        private final int mScreen;
        private final int mBlock;
        private final int mLine;
        /** Icons and glyphs: near-white on dark, near-black on light. */
        private final int mInk;
        private static final int[] SIRI = {0xFFFF6A88, 0xFFFFB86B, 0xFFB45CFF, 0xFF5E9BFF, 0xFF4FD8FF, 0xFFFF6A88};
        private static final int[] TAB_COLORS = {0xFF0A84FF, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158};
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Path mPath = new Path();

        Picture(Context context, int kind) {
            mKind = kind;
            boolean dark = kind == THEME_DARK || kind != THEME_LIGHT && kind != THEME_SYSTEM
                    && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            mScreen = dark ? 0xFF0F0F0F : 0xFFFFFFFF;
            mBlock = dark ? 0xFF2C2C30 : 0xFFE3E3E3;
            mLine = dark ? 0xFF404046 : 0xFFCFCFCF;
            mInk = dark ? 0xFFE8E8E8 : 0xFF303030;
        }

        /** A switch's picture: in colour when on, grey when off. */
        void setLit(boolean lit) {
            android.graphics.ColorMatrix grey = new android.graphics.ColorMatrix();
            grey.setSaturation(0f);
            mPaint.setColorFilter(lit ? null : new android.graphics.ColorMatrixColorFilter(grey));
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return; // the loops below step by the height
            }
            if (mKind >= THUMB_DEFAULT && mKind <= THUMB_END || mKind == FX_WATCHED) {
                thumbnail(canvas, new RectF(b));
                return;
            }
            if (mKind >= GLOW_WHITE) {
                scene(canvas, new RectF(b));
                return;
            }
            float h = b.height();
            float w = Math.min(b.width(), h * 0.58f);
            RectF phone = new RectF(b.exactCenterX() - w / 2, b.top, b.exactCenterX() + w / 2, b.bottom);
            if (mKind == THEME_SYSTEM) {
                // Half light, half dark, cut on the diagonal.
                phone(canvas, phone, THEME_LIGHT, 0xFFFFFFFF, 0xFFE3E3E3, 0xFFCFCFCF);
                canvas.save();
                mPath.reset();
                mPath.moveTo(phone.right, phone.top);
                mPath.lineTo(phone.right, phone.bottom);
                mPath.lineTo(phone.left, phone.bottom);
                mPath.close();
                canvas.clipPath(mPath);
                phone(canvas, phone, THEME_DARK, 0xFF0F0F0F, 0xFF2C2C30, 0xFF404046);
                canvas.restore();
            } else {
                phone(canvas, phone, mKind, mScreen, mBlock, mLine);
            }
        }

        private void phone(Canvas canvas, RectF phone, int kind, int screen, int block, int line) {
            float w = phone.width();
            float h = phone.height();
            float corner = w * 0.16f;
            mPath.reset();
            mPath.addRoundRect(phone, corner, corner, Path.Direction.CW);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(screen);
            canvas.drawPath(mPath, mPaint);
            canvas.save();
            canvas.clipPath(mPath);

            float p = w * 0.1f;
            float l = phone.left + p;
            float r = phone.right - p;
            float y = phone.top + p;
            boolean watch = kind == WATCH_COMPACT || kind == WATCH_CLASSIC;
            if (!watch) {
                block(canvas, l, y, l + w * 0.3f, y + h * 0.04f, ACCENT); // the logo
                y += h * 0.09f;
            }
            switch (kind) {
                case WATCH_COMPACT:
                case WATCH_CLASSIC: {
                    // The video edge to edge, its title, then the channel.
                    float videoBottom = phone.top + w * 0.62f;
                    mPaint.setColor(0xFF1C1C1E);
                    canvas.drawRect(phone.left, phone.top, phone.right, videoBottom, mPaint);
                    mPaint.setColor(ACCENT);
                    canvas.drawRect(phone.left, videoBottom - h * 0.01f, phone.left + w * 0.4f, videoBottom, mPaint);
                    y = videoBottom + p;
                    block(canvas, l, y, r - w * 0.12f, y + h * 0.03f, line);
                    y += h * 0.045f;
                    block(canvas, l, y, l + (r - l) * 0.45f, y + h * 0.022f, line);
                    y += h * 0.05f;
                    float avatar = h * 0.028f;
                    mPaint.setColor(block);
                    canvas.drawCircle(l + avatar, y + avatar, avatar, mPaint);
                    block(canvas, l + avatar * 2.6f, y + avatar * 0.6f, l + w * 0.34f, y + avatar * 1.4f, line);
                    if (kind == WATCH_COMPACT) {
                        // Subscribe and the actions on the channel's line, sideways.
                        int ink = screen == 0xFFFFFFFF ? 0xFF303030 : 0xFFE8E8E8;
                        pill(canvas, l + w * 0.38f, y, l + w * 0.6f, y + avatar * 2, ink);
                        for (float x = l + w * 0.64f; x < phone.right; x += w * 0.26f) {
                            pill(canvas, x, y, x + w * 0.22f, y + avatar * 2, block);
                        }
                        y += avatar * 2 + h * 0.04f;
                        block(canvas, l, y, r, y + h * 0.09f, block); // the top comment
                        y += h * 0.12f;
                    } else {
                        // Subscribe at the end of the channel's row, then the labelled buttons.
                        pill(canvas, r - w * 0.26f, y, r, y + avatar * 2, ACCENT);
                        y += avatar * 2 + h * 0.04f;
                        float button = w * 0.07f;
                        for (int i = 0; i < 4; i++) {
                            float x = l + (r - l) * (i + 0.5f) / 4;
                            mPaint.setColor(block);
                            canvas.drawCircle(x, y + button, button, mPaint);
                            block(canvas, x - w * 0.06f, y + button * 2.4f, x + w * 0.06f, y + button * 2.4f + h * 0.018f, line);
                        }
                        y += button * 2.4f + h * 0.05f;
                    }
                    while (y < phone.bottom) { // what plays next
                        block(canvas, l, y, r, y + h * 0.13f, block);
                        y += h * 0.16f;
                    }
                    break;
                }
                case SHELVES:
                    while (y < phone.bottom) {
                        block(canvas, l, y, l + w * 0.4f, y + h * 0.03f, line);
                        y += h * 0.055f;
                        float cell = w * 0.42f;
                        for (float x = l; x < phone.right; x += cell + w * 0.06f) {
                            block(canvas, x, y, x + cell, y + h * 0.13f, block);
                        }
                        y += h * 0.18f;
                    }
                    break;
                case LIST:
                    while (y < phone.bottom) {
                        float thumbRight = l + (r - l) * 0.45f;
                        block(canvas, l, y, thumbRight, y + h * 0.09f, block);
                        block(canvas, thumbRight + w * 0.06f, y + h * 0.01f, r, y + h * 0.035f, line);
                        block(canvas, thumbRight + w * 0.06f, y + h * 0.05f, r - w * 0.15f, y + h * 0.075f, line);
                        y += h * 0.13f;
                    }
                    break;
                case GRID:
                    float cell = (r - l - w * 0.08f) / 2;
                    for (int i = 0; y < phone.bottom; i++) {
                        float x = i % 2 == 0 ? l : l + cell + w * 0.08f;
                        block(canvas, x + cell * 0.12f, y, x + cell * 0.88f, y + h * 0.02f, line); // the stack behind
                        block(canvas, x, y + h * 0.03f, x + cell, y + h * 0.16f, block);
                        block(canvas, x, y + h * 0.18f, x + cell * 0.7f, y + h * 0.205f, line);
                        if (i % 2 == 1) {
                            y += h * 0.26f;
                        }
                    }
                    break;
                default: // the feed, also what the theme tiles show
                    while (y < phone.bottom) {
                        block(canvas, l, y, r, y + h * 0.24f, block);
                        y += h * 0.27f;
                        block(canvas, l, y, r - w * 0.15f, y + h * 0.03f, line);
                        y += h * 0.05f;
                        block(canvas, l, y, l + (r - l) * 0.5f, y + h * 0.03f, line);
                        y += h * 0.08f;
                    }
                    break;
            }
            if (kind == FAB_RIGHT || kind == FAB_LEFT) {
                // The bar slid away; Search floats in its corner.
                float radius = w * 0.14f;
                float cx = kind == FAB_LEFT ? l + radius : r - radius;
                float cy = phone.bottom - p - radius;
                mPaint.setColor(screen);
                canvas.drawCircle(cx, cy, radius, mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(Math.max(1f, w * 0.015f));
                mPaint.setColor(line);
                canvas.drawCircle(cx, cy, radius, mPaint);
                magnifier(canvas, cx, cy, radius * 0.5f, screen == 0xFFFFFFFF ? 0xFF303030 : 0xFFE8E8E8);
                canvas.restore();
                outline(canvas, w, line);
                return;
            }
            if (watch) {
                canvas.restore();
                outline(canvas, w, line);
                return;
            }
            // The floating bar, Home lit.
            float barTop = phone.bottom - p - h * 0.085f;
            mPaint.setColor(screen);
            mRect.set(l - p * 0.3f, barTop - p * 0.3f, r + p * 0.3f, phone.bottom - p * 0.7f);
            canvas.drawRoundRect(mRect, mRect.height() / 2, mRect.height() / 2, mPaint);
            block(canvas, l, barTop, r, phone.bottom - p, block);
            float dot = h * 0.022f;
            float cy = (barTop + phone.bottom - p) / 2;
            for (int i = 0; i < 4; i++) {
                mPaint.setColor(i == 0 ? ACCENT : line);
                canvas.drawCircle(l + (r - l) * (i + 0.5f) / 4, cy, dot, mPaint);
            }
            canvas.restore();
            outline(canvas, w, line);
        }

        /** The phone's edge, round {@link #mPath}. */
        private void outline(Canvas canvas, float w, int line) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1f, w * 0.015f));
            mPaint.setColor(line);
            canvas.drawPath(mPath, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }

        /** The landscape pictures: the bottom bar's glow, and what each switch turns on. */
        private void scene(Canvas canvas, RectF b) {
            float w = b.width();
            float h = b.height();
            float cx = b.centerX();
            float cy = b.centerY();
            switch (mKind) {
                case GLOW_WHITE:
                case GLOW_TABS:
                case GLOW_ACCENT:
                case GLOW_OFF: {
                    RectF bar = new RectF(b.left, cy - h * 0.22f, b.right, cy + h * 0.22f);
                    pill(canvas, bar.left, bar.top, bar.right, bar.bottom, mBlock);
                    int glow = mKind == GLOW_WHITE ? (mInk == 0xFF303030 ? 0xFF8E8E93 : mInk) : // grey on light, as NavBloom
                             mKind == GLOW_TABS ? TAB_COLORS[0] : ACCENT;
                    float first = bar.left + bar.width() / 8;
                    if (mKind != GLOW_OFF) {
                        glow(canvas, first, cy, bar.height() * 1.1f, glow);
                    }
                    for (int i = 0; i < 4; i++) {
                        mPaint.setColor(i == 0 ? (mKind == GLOW_OFF ? mInk : glow) : mKind == GLOW_TABS ? TAB_COLORS[i] : mLine);
                        canvas.drawCircle(bar.left + bar.width() * (i + 0.5f) / 4, cy, bar.height() * 0.2f, mPaint);
                    }
                    break;
                }
                case FX_LABELS: {
                    RectF bar = new RectF(b.left, cy - h * 0.3f, b.right, cy + h * 0.3f);
                    pill(canvas, bar.left, bar.top, bar.right, bar.bottom, mBlock);
                    float dot = bar.height() * 0.13f;
                    for (int i = 0; i < 4; i++) {
                        float x = bar.left + bar.width() * (i + 0.5f) / 4;
                        int color = i == 0 ? ACCENT : mLine;
                        mPaint.setColor(color);
                        canvas.drawCircle(x, cy - bar.height() * 0.12f, dot, mPaint);
                        block(canvas, x - dot * 1.8f, cy + bar.height() * 0.12f, x + dot * 1.8f, cy + bar.height() * 0.22f, color);
                    }
                    break;
                }
                case FX_TOP_BAR: {
                    // A feed under the top bar's two pills, kept on screen.
                    RectF screen = new RectF(b.left + h * 0.08f, b.top + h * 0.08f, b.right - h * 0.08f, b.bottom - h * 0.08f);
                    mPaint.setColor(mScreen);
                    canvas.drawRoundRect(screen, h * 0.16f, h * 0.16f, mPaint);
                    float in = h * 0.12f;
                    float top = screen.top + in;
                    float bottom = top + h * 0.2f;
                    pill(canvas, screen.left + in, top, cx, bottom, mBlock);
                    pill(canvas, screen.right - in - w * 0.22f, top, screen.right - in, bottom, mBlock);
                    mPaint.setColor(ACCENT);
                    canvas.drawCircle(screen.left + in + h * 0.1f, (top + bottom) / 2, h * 0.05f, mPaint);
                    for (int i = 0; i < 2; i++) { // the feed's cards
                        float y = bottom + h * 0.1f + i * h * 0.2f;
                        block(canvas, screen.left + in, y, screen.right - in, y + h * 0.12f, mLine);
                    }
                    break;
                }
                case MINI_DEFAULT:
                case MINI_MINIMAL:
                case MINI_MINIMAL2:
                case MINI_OFF: {
                    // A page of the feed with the mini player over its bottom: a corner card, a black
                    // bar, a frosted pill, or nothing (a struck-out card).
                    float corner = h * 0.14f;
                    float pw = Math.min(w, h * 1.5f);
                    RectF page = new RectF(cx - pw / 2, b.top, cx + pw / 2, b.bottom);
                    mPaint.setColor(mScreen);
                    canvas.drawRoundRect(page, corner, corner, mPaint);
                    float pad = h * 0.1f;
                    block(canvas, page.left + pad, page.top + pad, page.right - pad, page.top + h * 0.42f, mBlock);
                    block(canvas, page.left + pad, page.top + h * 0.48f, page.right - pad * 3, page.top + h * 0.54f, mLine);
                    float bottom = page.bottom - pad * 0.7f;
                    if (mKind == MINI_DEFAULT || mKind == MINI_OFF) {
                        float cw = pw * 0.44f;
                        RectF card = new RectF(page.right - pad * 0.7f - cw, bottom - cw * 9f / 16f, page.right - pad * 0.7f, bottom);
                        if (mKind == MINI_OFF) {
                            mPaint.setStyle(Paint.Style.STROKE);
                            mPaint.setStrokeWidth(Math.max(1f, h * 0.025f));
                            mPaint.setColor(mLine);
                            canvas.drawRoundRect(card, h * 0.06f, h * 0.06f, mPaint);
                            mPaint.setColor(ACCENT);
                            canvas.drawLine(card.left, card.bottom, card.right, card.top, mPaint);
                            mPaint.setStyle(Paint.Style.FILL);
                        } else {
                            video(canvas, card, h * 0.06f);
                            mPaint.setColor(0x73000000);
                            canvas.drawCircle(card.right - card.height() * 0.2f, card.top + card.height() * 0.2f, card.height() * 0.12f, mPaint);
                        }
                        break;
                    }
                    float bh = h * 0.26f;
                    RectF bar = new RectF(page.left + pad * 0.5f, bottom - bh, page.right - pad * 0.5f, bottom);
                    boolean frost = mKind == MINI_MINIMAL2;
                    if (frost) {
                        mPaint.setColor(0x993A3A44);
                        canvas.drawRoundRect(bar, bh / 2, bh / 2, mPaint);
                        mPaint.setStyle(Paint.Style.STROKE);
                        mPaint.setStrokeWidth(Math.max(1f, h * 0.015f));
                        mPaint.setColor(0x59FFFFFF);
                        canvas.drawRoundRect(bar, bh / 2, bh / 2, mPaint);
                        mPaint.setStyle(Paint.Style.FILL);
                    } else {
                        mPaint.setColor(0xFF000000);
                        canvas.drawRoundRect(bar, bh * 0.3f, bh * 0.3f, mPaint);
                    }
                    float in = bh * 0.14f;
                    float vw = (bh - in * 2) * 16f / 9f;
                    float textLeft;
                    float textRight;
                    RectF pic;
                    if (frost) { // the picture first, then the title
                        pic = new RectF(bar.left + bh * 0.3f, bar.top + in, bar.left + bh * 0.3f + vw, bar.bottom - in);
                        textLeft = pic.right + in;
                        textRight = bar.right - bh * 1.5f;
                    } else { // the title, then the picture
                        textLeft = bar.left + in * 2;
                        textRight = textLeft + bar.width() * 0.28f;
                        pic = new RectF(textRight + in, bar.top + in, textRight + in + vw, bar.bottom - in);
                    }
                    video(canvas, pic, in);
                    block(canvas, textLeft, bar.top + bh * 0.3f, textRight, bar.top + bh * 0.44f, 0xFFE8E8E8);
                    block(canvas, textLeft, bar.top + bh * 0.56f, textLeft + (textRight - textLeft) * 0.6f, bar.top + bh * 0.68f, 0xFF8E8E93);
                    mPaint.setColor(0x40FFFFFF);
                    for (int i = 0; i < 2; i++) {
                        canvas.drawCircle(bar.right - bh * (0.45f + 0.62f * i), bar.centerY(), bh * 0.26f, mPaint);
                    }
                    break;
                }
                case CHIPS_SEGMENT:
                case CHIPS_PILLS: {
                    // Home's topic chips: one glass track with the lit pill in it, or separate pills.
                    float ph = h * 0.32f;
                    float top = cy - ph / 2;
                    float chip = w * 0.24f;
                    float gap = w * 0.03f;
                    float left = b.left + w * 0.04f;
                    if (mKind == CHIPS_SEGMENT) {
                        pill(canvas, left, top - ph * 0.15f, b.right - w * 0.04f, top + ph * 1.15f, mBlock);
                        gap = 0;
                    }
                    for (int i = 0; i < 4 && left + chip <= b.right; i++) {
                        boolean lit = i == 0;
                        if (lit || mKind == CHIPS_PILLS) {
                            pill(canvas, left, top, left + chip, top + ph, lit ? mInk : mBlock);
                        }
                        block(canvas, left + chip * 0.25f, cy - ph * 0.1f, left + chip * 0.75f, cy + ph * 0.1f,
                                lit ? mScreen : mLine);
                        left += chip + gap;
                    }
                    break;
                }
                case FX_BRAND_NAME: {
                    float ph = h * 0.44f;
                    RectF brand = new RectF(cx - ph * 1.8f, cy - ph / 2, cx + ph * 1.8f, cy + ph / 2);
                    pill(canvas, brand.left, brand.top, brand.right, brand.bottom, mBlock);
                    float icon = brand.left + ph * 0.5f;
                    mPaint.setColor(ACCENT);
                    canvas.drawCircle(icon, cy, ph * 0.24f, mPaint);
                    block(canvas, icon + ph * 0.45f, cy - ph * 0.09f, brand.right - ph * 0.4f, cy + ph * 0.09f, mInk);
                    break;
                }
                case FX_MOTION: {
                    RectF bar = new RectF(b.left, cy - h * 0.22f, b.right, cy + h * 0.22f);
                    pill(canvas, bar.left, bar.top, bar.right, bar.bottom, mBlock);
                    for (int i = 0; i < 4; i++) {
                        mPaint.setColor(mLine);
                        canvas.drawCircle(bar.left + bar.width() * (i + 0.5f) / 4, cy, bar.height() * 0.2f, mPaint);
                    }
                    // The lens on its way from the first tab to the second, a trail behind it.
                    float lens = bar.height() * 0.44f;
                    float to = bar.left + bar.width() * 1.5f / 4;
                    for (int i = 3; i >= 1; i--) {
                        mPaint.setColor((mInk & 0x00FFFFFF) | (0x18 * (4 - i)) << 24);
                        canvas.drawCircle(to - lens * 0.7f * i, cy, lens, mPaint);
                    }
                    mPaint.setColor((mInk & 0x00FFFFFF) | 0x50000000);
                    canvas.drawCircle(to, cy, lens, mPaint);
                    mPaint.setColor(ACCENT);
                    canvas.drawCircle(to, cy, bar.height() * 0.2f, mPaint);
                    break;
                }
                case FX_GLASS: {
                    // Thumbnails scrolling under the bar; the lens magnifies them.
                    float barTop = cy + h * 0.02f;
                    RectF bar = new RectF(b.left, barTop, b.right, barTop + h * 0.4f);
                    stripes(canvas, b);
                    pill(canvas, bar.left, bar.top, bar.right, bar.bottom, (mScreen & 0x00FFFFFF) | 0x66000000);
                    float lx = bar.left + bar.width() * 0.3f;
                    float ly = bar.centerY();
                    float lr = bar.height() * 0.62f;
                    canvas.save();
                    mPath.reset();
                    mPath.addCircle(lx, ly, lr, Path.Direction.CW);
                    canvas.clipPath(mPath);
                    canvas.scale(1.4f, 1.4f, lx, ly);
                    stripes(canvas, b);
                    canvas.restore();
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(Math.max(1f, h * 0.035f));
                    mPaint.setColor(0xCCFFFFFF);
                    canvas.drawCircle(lx, ly, lr, mPaint);
                    mPaint.setStyle(Paint.Style.FILL);
                    break;
                }
                case BUTTONS_SEE_THROUGH:
                case BUTTONS_MINIMAL:
                case FX_FROST: {
                    // A dark video, its buttons over it: on tinted discs, frosted ones, or bare.
                    float corner = h * 0.14f;
                    mPath.reset();
                    mPath.addRoundRect(b, corner, corner, Path.Direction.CW);
                    canvas.save();
                    canvas.clipPath(mPath);
                    mPaint.setColor(0xFF23263A);
                    canvas.drawRect(b, mPaint);
                    mPaint.setColor(0xFFFFB300);
                    canvas.drawCircle(b.left + w * 0.78f, b.top + h * 0.28f, h * 0.14f, mPaint);
                    mPaint.setColor(0xFF3A4060);
                    Path hills = new Path();
                    hills.moveTo(b.left, b.bottom);
                    hills.lineTo(b.left + w * 0.3f, b.top + h * 0.45f);
                    hills.lineTo(b.left + w * 0.55f, b.top + h * 0.7f);
                    hills.lineTo(b.left + w * 0.8f, b.top + h * 0.4f);
                    hills.lineTo(b.right, b.bottom);
                    hills.close();
                    canvas.drawPath(hills, mPaint);
                    canvas.restore();
                    float gap = h * 0.5f;
                    for (int i = -1; i <= 1; i++) {
                        float r = i == 0 ? h * 0.22f : h * 0.16f;
                        float x = cx + gap * i;
                        int direction = i < 0 ? -1 : 1;
                        if (mKind == BUTTONS_MINIMAL) {
                            // the bare icon, a size up, on its soft shadow
                            triangle(canvas, x, cy + h * 0.015f, r * 0.68f, direction, 0x66000000);
                            triangle(canvas, x, cy, r * 0.6f, direction, 0xFFFFFFFF);
                            continue;
                        }
                        boolean frosted = mKind == FX_FROST;
                        mPaint.setColor(frosted ? 0x59FFFFFF : 0x73000000);
                        canvas.drawCircle(x, cy, r, mPaint);
                        mPaint.setStyle(Paint.Style.STROKE);
                        mPaint.setStrokeWidth(Math.max(1f, h * 0.025f));
                        mPaint.setColor(frosted ? 0x99FFFFFF : 0x40FFFFFF);
                        canvas.drawCircle(x, cy, r, mPaint);
                        mPaint.setStyle(Paint.Style.FILL);
                        triangle(canvas, x, cy, r * 0.45f, direction, 0xFFFFFFFF);
                    }
                    break;
                }
                case FX_PROGRESS: {
                    float l = b.left + w * 0.06f;
                    float r = b.right - w * 0.06f;
                    float head = l + (r - l) * 0.62f;
                    float t = h * 0.035f;
                    block(canvas, l, cy - t, r, cy + t, mLine);
                    block(canvas, l, cy - t, head, cy + t, ACCENT);
                    glow(canvas, head, cy, h * 0.36f, ACCENT);
                    mPaint.setColor(ACCENT);
                    canvas.drawCircle(head, cy, h * 0.1f, mPaint);
                    mPaint.setColor(0xFFFFFFFF);
                    canvas.drawCircle(head, cy, h * 0.045f, mPaint);
                    break;
                }
                case FX_SEARCH: {
                    // The screen's edge alight in Siri's colours, Search's bar at the top.
                    RectF screen = new RectF(b.left + h * 0.08f, b.top + h * 0.08f, b.right - h * 0.08f, b.bottom - h * 0.08f);
                    float corner = h * 0.16f;
                    mPaint.setColor(mScreen);
                    canvas.drawRoundRect(screen, corner, corner, mPaint);
                    mPaint.setShader(new android.graphics.SweepGradient(cx, cy, SIRI, null));
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(h * 0.1f);
                    mPaint.setAlpha(0x66);
                    canvas.drawRoundRect(screen, corner, corner, mPaint);
                    mPaint.setStrokeWidth(h * 0.04f);
                    mPaint.setAlpha(0xFF);
                    canvas.drawRoundRect(screen, corner, corner, mPaint);
                    mPaint.setShader(null);
                    mPaint.setStyle(Paint.Style.FILL);
                    pill(canvas, screen.left + h * 0.14f, screen.top + h * 0.14f, screen.right - h * 0.14f,
                            screen.top + h * 0.32f, mBlock);
                    break;
                }
                case FX_TYPING: {
                    float top = cy - h * 0.22f;
                    float bottom = cy + h * 0.22f;
                    pill(canvas, b.left, top, b.right, bottom, mBlock);
                    float glass = (bottom - top) * 0.28f;
                    magnifier(canvas, b.left + (bottom - top) * 0.55f, cy, glass, mInk);
                    float textLeft = b.left + (bottom - top) * 1.05f;
                    float textRight = b.right - w * 0.22f;
                    mPaint.setShader(new android.graphics.LinearGradient(textLeft, 0, textRight, 0, SIRI, null,
                            android.graphics.Shader.TileMode.CLAMP));
                    mRect.set(textLeft, cy - h * 0.06f, textRight, cy + h * 0.06f);
                    canvas.drawRoundRect(mRect, h * 0.06f, h * 0.06f, mPaint);
                    mPaint.setShader(null);
                    block(canvas, textRight + w * 0.03f, cy - h * 0.12f, textRight + w * 0.05f, cy + h * 0.12f, mInk); // the caret
                    break;
                }
                case FX_REFRESH: {
                    // Pull to refresh's spinner, and the pop it makes.
                    float sx = cx - h * 0.3f;
                    mPaint.setColor(mBlock);
                    canvas.drawCircle(sx, cy, h * 0.32f, mPaint);
                    float ring = h * 0.17f;
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeCap(Paint.Cap.ROUND);
                    mPaint.setStrokeWidth(h * 0.06f);
                    mPaint.setColor(ACCENT);
                    mRect.set(sx - ring, cy - ring, sx + ring, cy + ring);
                    canvas.drawArc(mRect, -60, 280, false, mPaint);
                    mPaint.setColor(mInk);
                    mPaint.setStrokeWidth(h * 0.05f);
                    for (int i = 1; i <= 2; i++) {
                        float wave = h * (0.16f + 0.14f * i);
                        mRect.set(sx + h * 0.2f - wave, cy - wave, sx + h * 0.2f + wave, cy + wave);
                        canvas.drawArc(mRect, -35, 70, false, mPaint);
                    }
                    mPaint.setStrokeCap(Paint.Cap.BUTT);
                    mPaint.setStyle(Paint.Style.FILL);
                    break;
                }
                case FX_SHORTS: {
                    // A shelf of portrait tiles, off the edge.
                    canvas.save();
                    canvas.clipRect(b);
                    float l = b.left + w * 0.05f;
                    block(canvas, l, b.top + h * 0.06f, l + w * 0.32f, b.top + h * 0.15f, mLine);
                    float tileH = h * 0.7f;
                    float tileW = tileH * 0.5625f;
                    for (float x = l; x < b.right; x += tileW + h * 0.08f) {
                        block(canvas, x, b.top + h * 0.24f, x + tileW, b.top + h * 0.24f + tileH, mBlock);
                        block(canvas, x + tileW * 0.15f, b.top + h * 0.8f, x + tileW * 0.7f, b.top + h * 0.86f, mLine);
                    }
                    canvas.restore();
                    break;
                }
                default:
                    break;
            }
        }

        /** Three thumbnails side by side (Liquid glass's picture draws them twice: plain and magnified). */
        private void stripes(Canvas canvas, RectF b) {
            int[] colors = {0xFFFF453A, 0xFFFFB300, 0xFF0A84FF};
            float gap = b.width() * 0.04f;
            float cell = (b.width() - gap * 2) / 3;
            for (int i = 0; i < 3; i++) {
                float x = b.left + i * (cell + gap);
                block(canvas, x, b.top, x + cell, b.top + b.height() * 0.62f, colors[i]);
            }
        }

        private void glow(Canvas canvas, float cx, float cy, float radius, int color) {
            mPaint.setShader(new android.graphics.RadialGradient(cx, cy, radius,
                    new int[] {(color & 0x00FFFFFF) | 0xA0000000, color & 0x00FFFFFF}, null,
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, mPaint);
            mPaint.setShader(null);
        }

        private void magnifier(Canvas canvas, float cx, float cy, float size, int color) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeWidth(Math.max(1f, size * 0.28f));
            mPaint.setColor(color);
            float r = size * 0.62f;
            float ox = cx - size * 0.15f;
            float oy = cy - size * 0.15f;
            canvas.drawCircle(ox, oy, r, mPaint);
            canvas.drawLine(ox + r * 0.72f, oy + r * 0.72f, cx + size * 0.75f, cy + size * 0.75f, mPaint);
            mPaint.setStrokeCap(Paint.Cap.BUTT);
            mPaint.setStyle(Paint.Style.FILL);
        }

        /** A play-style triangle, pointing right ({@code direction} 1) or left (-1). */
        private void triangle(Canvas canvas, float cx, float cy, float size, int direction, int color) {
            mPaint.setColor(color);
            Path path = new Path();
            path.moveTo(cx - size * 0.7f * direction, cy - size);
            path.lineTo(cx + size * direction, cy);
            path.lineTo(cx - size * 0.7f * direction, cy + size);
            path.close();
            canvas.drawPath(path, mPaint);
        }

        /** A little dark video frame: a sun over hills. */
        private void video(Canvas canvas, RectF r, float corner) {
            mPath.reset();
            mPath.addRoundRect(r, corner, corner, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mPath);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFF23263A);
            canvas.drawRect(r, mPaint);
            mPaint.setColor(0xFFFFB300);
            canvas.drawCircle(r.left + r.width() * 0.75f, r.top + r.height() * 0.3f, r.height() * 0.14f, mPaint);
            mPaint.setColor(0xFF3A4060);
            Path hills = new Path();
            hills.moveTo(r.left, r.bottom);
            hills.lineTo(r.left + r.width() * 0.35f, r.top + r.height() * 0.45f);
            hills.lineTo(r.left + r.width() * 0.6f, r.top + r.height() * 0.7f);
            hills.lineTo(r.left + r.width() * 0.85f, r.top + r.height() * 0.4f);
            hills.lineTo(r.right, r.bottom);
            hills.close();
            canvas.drawPath(hills, mPaint);
            canvas.restore();
        }

        private void pill(Canvas canvas, float left, float top, float right, float bottom, int color) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            mRect.set(left, top, right, bottom);
            canvas.drawRoundRect(mRect, mRect.height() / 2, mRect.height() / 2, mPaint);
        }

        /** A landscape thumbnail: hills under a sun, and where along the video its frame is from. */
        private void thumbnail(Canvas canvas, RectF b) {
            float h = Math.min(b.height(), b.width() * 0.5625f);
            RectF t = new RectF(b.left, b.centerY() - h / 2, b.right, b.centerY() + h / 2);
            float corner = h * 0.14f;
            mPath.reset();
            mPath.addRoundRect(t, corner, corner, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mPath);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mBlock);
            canvas.drawRect(t, mPaint);
            float position = mKind == THUMB_START ? 0.12f : mKind == THUMB_MIDDLE ? 0.5f : mKind == THUMB_END ? 0.88f : 0.7f;
            mPaint.setColor(0xFFFFB300);
            canvas.drawCircle(t.left + t.width() * position, t.top + h * 0.3f, h * 0.13f, mPaint);
            mPaint.setColor(mLine);
            Path hills = new Path();
            hills.moveTo(t.left, t.bottom);
            hills.lineTo(t.left + t.width() * 0.3f, t.top + h * 0.5f);
            hills.lineTo(t.left + t.width() * 0.5f, t.top + h * 0.7f);
            hills.lineTo(t.left + t.width() * 0.72f, t.top + h * 0.42f);
            hills.lineTo(t.right, t.bottom);
            hills.close();
            canvas.drawPath(hills, mPaint);
            if (mKind == THUMB_DEFAULT) {
                // The uploader's own: a title card's bold lettering.
                block(canvas, t.left + t.width() * 0.08f, t.top + h * 0.62f, t.left + t.width() * 0.6f, t.top + h * 0.8f, ACCENT);
            } else {
                float track = t.bottom - h * 0.06f;
                mPaint.setColor(0x66FFFFFF);
                canvas.drawRect(t.left, track, t.right, t.bottom, mPaint);
                mPaint.setColor(ACCENT);
                canvas.drawRect(t.left, track, t.left + t.width() * position, t.bottom, mPaint);
                if (mKind != FX_WATCHED) { // the watched line has no scrubber
                    canvas.drawCircle(t.left + t.width() * position, track, h * 0.08f, mPaint);
                }
            }
            canvas.restore();
        }

        private void block(Canvas canvas, float left, float top, float right, float bottom, int color) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            mRect.set(left, top, right, bottom);
            float radius = Math.min(mRect.height(), mRect.width()) * 0.3f;
            canvas.drawRoundRect(mRect, radius, radius, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
