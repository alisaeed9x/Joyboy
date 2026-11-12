package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(you-subscreen): Browse's top bar. On a bottom-nav tab it is the brand; a section opened
 * from a You row (Sports, Playlists, ...) is a sub-screen of You, so it gets a back arrow and the
 * section's name - like YouTube's You sub-pages - instead of a brand that gave no hint where the
 * user was or that back returns to You. MEETUBE(glass): both live in the floating brand pill.
 */
final class BrowseTopBar {
    private final TextView mTitle;
    private final View mBack;
    private final View mMark;
    private final View mBrandGlass;     // JOYBOY(v922)
    private final View mActionsGlass;   // JOYBOY(v922)
    private final View mTopRow;         // JOYBOY(v922)
    private boolean mSubScreen;
    private CharSequence mSubTitle;

    BrowseTopBar(Activity activity, Runnable onBack) {
        mTitle = activity.findViewById(R.id.mobile_title_bar);
        mBack = activity.findViewById(R.id.mobile_title_back);
        mMark = activity.findViewById(R.id.mobile_brand_mark);
        mBrandGlass = activity.findViewById(R.id.mobile_brand_glass);
        mActionsGlass = activity.findViewById(R.id.mobile_actions_glass);
        mTopRow = activity.findViewById(R.id.mobile_top_glass);
        syncMark(activity);
        mBack.setOnClickListener(v -> onBack.run());
        // MEETUBE(quotes): on a tab the brand pill shows a quote; on a sub-screen it goes back.
        QuoteCard quote = new QuoteCard(activity);
        activity.findViewById(R.id.mobile_brand_glass).setOnClickListener(v -> {
            if (mBack.getVisibility() == View.VISIBLE) {
                onBack.run();
            } else {
                com.newtube.mobile.ui.common.Motion.nudge(v);
                quote.show();
            }
        });
        // MEETUBE(greeting): held, on a tab or a sub-screen, it greets with three shortcuts.
        com.newtube.mobile.ui.common.Glass.blur(activity.findViewById(R.id.mobile_greet_overlay),
                activity.findViewById(R.id.mobile_blur_target));
        GreetingOverlay greeting = new GreetingOverlay((androidx.activity.ComponentActivity) activity);
        activity.findViewById(R.id.mobile_brand_glass).setOnLongClickListener(v -> {
            greeting.show();
            return true;
        });
    }

    /**
     * MEETUBE(app icon): the brand follows the picked icon, and shows or drops the app's name
     * (on resume: Settings may have changed either).
     */
    void syncMark(Activity activity) {
        com.newtube.mobile.ui.common.AppIcon.show((ImageView) mMark, true);
        show(mSubScreen, mSubTitle);
    }

    /**
     * JOYBOY(v922): مفيش كبسولة براند ولا كبسولة أزرار = الصف العلوي ما ياخدش أي حشو (ارتفاعه صفر)؛ لو سهم الشيبس ظهر
     * (جوه الصف) بيكبر لوحده ومعاه الحشو بيتظبط من مستمع التغيير في refreshTopPadding. مش بنخفي الصف نفسه عشان السهم.
     */
    void updateRow() {
        boolean any = mBrandGlass.getVisibility() == View.VISIBLE || mActionsGlass.getVisibility() == View.VISIBLE;
        float density = mTopRow.getResources().getDisplayMetrics().density;
        int v = any ? Math.round(8 * density) : 0;
        mTopRow.setPadding(mTopRow.getPaddingLeft(), v, mTopRow.getPaddingRight(), v);
    }

    /**
     * A tab shows the brand; a sub-screen shows the back arrow and {@code title} (the app name if
     * the section came without one - the arrow is what matters).
     */
    void show(boolean subScreen, CharSequence title) {
        mSubScreen = subScreen;
        mSubTitle = title;
        mBack.setVisibility(subScreen ? View.VISIBLE : View.GONE);
        mMark.setVisibility(subScreen ? View.GONE : View.VISIBLE);
        // JOYBOY(v922): على التبويبات لوجو يوتيوب (كبسولة البراند) اتشال بالكامل ومعاه المساحة؛ الكبسولة بتظهر بس في الشاشات الفرعية (سهم رجوع + الاسم)
        mBrandGlass.setVisibility(subScreen ? View.VISIBLE : View.GONE);
        updateRow();
        mTitle.setText(subScreen && title != null && title.length() > 0
                ? title : mTitle.getContext().getString(R.string.app_name));

        // MEETUBE(brand name): without the name, the icon sits alone in a round pill.
        boolean named = subScreen;   // JOYBOY(v916): كلمة «ميتيوب» اتشالت من التبويبات (الأيقونة بس)
        mTitle.setVisibility(named ? View.VISIBLE : View.GONE);
        float density = mMark.getResources().getDisplayMetrics().density;
        ((View) mMark.getParent()).setPaddingRelative(Math.round((named ? 14 : 11) * density), 0,
                Math.round((named ? 16 : 11) * density), 0);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mMark.getLayoutParams();
        lp.setMarginEnd(named ? Math.round(8 * density) : 0);
        mMark.setLayoutParams(lp);
    }
}
