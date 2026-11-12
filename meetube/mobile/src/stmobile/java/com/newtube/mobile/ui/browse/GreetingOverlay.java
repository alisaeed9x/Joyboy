package com.newtube.mobile.ui.browse;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.settings.MobileSettingsActivity;
import com.newtube.mobile.ui.settings.SettingsPages;

import java.util.Calendar;

/**
 * MEETUBE(greeting): a held brand pill frosts the whole screen (a full-screen glass over the page)
 * with a greeting for the time of day and three glass shortcuts: Settings, Customization and Check
 * for updates. A tap anywhere else, or Back, closes it.
 */
final class GreetingOverlay {
    private static final int[][] PERIODS = {
            {R.string.mobile_greet_morning, R.string.mobile_greet_morning_line},
            {R.string.mobile_greet_afternoon, R.string.mobile_greet_afternoon_line},
            {R.string.mobile_greet_evening, R.string.mobile_greet_evening_line},
            {R.string.mobile_greet_night, R.string.mobile_greet_night_line},
    };

    private final ComponentActivity mActivity;
    private final View mOverlay;
    private final View mContent;
    private final TextView mTitle;
    private final TextView mLine;
    private final OnBackPressedCallback mBack = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            hide();
        }
    };

    GreetingOverlay(ComponentActivity activity) {
        mActivity = activity;
        mOverlay = activity.findViewById(R.id.mobile_greet_overlay);
        mContent = activity.findViewById(R.id.mobile_greet_content);
        mTitle = activity.findViewById(R.id.mobile_greet_title);
        mLine = activity.findViewById(R.id.mobile_greet_line);
        mOverlay.setOnClickListener(v -> hide());

        ViewGroup content = (ViewGroup) mContent;
        addOption(content, R.drawable.ic_mobile_settings, R.string.header_settings,
                () -> open(null));
        addOption(content, R.drawable.ic_settings_customize, R.string.mobile_settings_customize,
                () -> open(SettingsPages.CUSTOMIZE));
        addOption(content, R.drawable.ic_mobile_update, R.string.check_for_updates, () -> {
            if (AppUpdatePresenter.isInAppUpdatesDisabled()) {
                open(SettingsPages.ABOUT); // store build: no updater, the version lives in About
            } else {
                AppUpdatePresenter.instance(mActivity).start(true);
            }
        });
    }

    private void addOption(ViewGroup parent, @DrawableRes int icon, @StringRes int label, Runnable action) {
        View option = LayoutInflater.from(mActivity).inflate(R.layout.item_mobile_greet_option, parent, false);
        TextView text = option.findViewById(R.id.mobile_greet_option_label);
        text.setText(label);
        text.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        option.setOnClickListener(v -> {
            hide();
            action.run();
        });
        parent.addView(option);
    }

    private void open(String pageId) {
        mActivity.startActivity(MobileSettingsActivity.intent(mActivity, pageId));
    }

    void show() {
        int[] period = PERIODS[period(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))];
        mTitle.setText(period[0]);
        mLine.setText(period[1]);

        mBack.remove(); // re-added last, so it is the callback Back reaches first
        mActivity.getOnBackPressedDispatcher().addCallback(mBack);

        mOverlay.animate().cancel();
        mContent.animate().cancel();
        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.setAlpha(0f);
        mOverlay.animate().alpha(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        mContent.setScaleX(0.92f);
        mContent.setScaleY(0.92f);
        mContent.animate().scaleX(1f).scaleY(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
    }

    private void hide() {
        mBack.remove();
        if (mOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        mOverlay.animate().cancel();
        mOverlay.animate().alpha(0f).setDuration(Motion.EXIT_MS)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> mOverlay.setVisibility(View.GONE)).start();
    }

    /** Index into PERIODS: morning 5-11, afternoon 12-16, evening 17-20, night 21-4. */
    static int period(int hour) {
        return hour >= 5 && hour < 12 ? 0 : hour >= 12 && hour < 17 ? 1 : hour >= 17 && hour < 21 ? 2 : 3;
    }
}
