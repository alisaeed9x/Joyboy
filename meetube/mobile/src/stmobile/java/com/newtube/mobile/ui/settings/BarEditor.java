package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.NavTabs;
import com.newtube.mobile.ui.common.Haptics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MEETUBE(bar editor): Settings > Customization > Tabs shows the bars as a stack you drag: a live
 * preview of the top and bottom bars, then a tile per item. Home and You stay put; Search and the
 * two tabs slide between them, and Search can go up into the top bar. A tap on a tab picks what it
 * shows, a tap on Search picks its bar. Each drop saves the whole order ({@link NavTabs#setLayout});
 * Browse rebuilds its bar when it comes back.
 */
final class BarEditor extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    // The stack's items: a tab is its section id (or NavTabs.NONE), Home is MediaGroup.TYPE_HOME.
    private static final int TOP_HEADER = -100;
    private static final int BOTTOM_HEADER = -101;
    private static final int SEARCH = -102;
    private static final int YOU = -103;
    private static final int CAST = -104; // the preview's top bar only
    private static final int VIEW_HEADER = 0;
    private static final int VIEW_TILE = 1;

    private final Context mContext;
    private final SettingsAdapter.Listener mPage;
    private final List<Integer> mItems = new ArrayList<>();
    private final LinearLayout mPreviewTop;
    private final LinearLayout mPreviewBottom;
    private ItemTouchHelper mTouchHelper;

    static SettingsRow row(Context context) {
        return SettingsRow.custom(context.getString(R.string.mobile_settings_tabs_bottom_bar), BarEditor::create);
    }

    private static View create(ViewGroup parent, SettingsAdapter.Listener page) {
        Context context = parent.getContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        FrameLayout preview = new FrameLayout(context);
        preview.setBackgroundResource(R.drawable.bg_bar_tile);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 148));
        int inset = context.getResources().getDimensionPixelSize(R.dimen.mobile_settings_row_inset);
        previewParams.setMargins(inset, dp(context, 8), inset, 0);
        root.addView(preview, previewParams);
        LinearLayout top = pill(context);
        preview.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40),
                Gravity.TOP | Gravity.END));
        LinearLayout bottom = pill(context);
        preview.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) top.getLayoutParams()).setMargins(0, dp(context, 12), dp(context, 12), 0);
        ((FrameLayout.LayoutParams) bottom.getLayoutParams()).bottomMargin = dp(context, 14);

        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setNestedScrollingEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipChildren(false);
        root.setClipChildren(false);
        BarEditor editor = new BarEditor(context, page, top, bottom);
        list.setAdapter(editor);
        editor.mTouchHelper = new ItemTouchHelper(editor.new Drag());
        editor.mTouchHelper.attachToRecyclerView(list);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private BarEditor(Context context, SettingsAdapter.Listener page, LinearLayout previewTop, LinearLayout previewBottom) {
        mContext = context;
        mPage = page;
        mPreviewTop = previewTop;
        mPreviewBottom = previewBottom;
        boolean onTop = NavTabs.searchOnTop(context);
        List<Integer> middle = new ArrayList<>();
        middle.add(NavTabs.get(context, NavTabs.FIRST));
        middle.add(NavTabs.get(context, NavTabs.SECOND));
        if (!onTop) {
            middle.add(Math.max(0, Math.min(2, NavTabs.searchPosition(context))), SEARCH);
        }
        mItems.add(TOP_HEADER);
        if (onTop) {
            mItems.add(SEARCH);
        }
        mItems.add(BOTTOM_HEADER);
        mItems.add(MediaGroup.TYPE_HOME);
        mItems.addAll(middle);
        mItems.add(YOU);
        syncPreview();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        int item = mItems.get(position);
        return item == TOP_HEADER || item == BOTTOM_HEADER ? VIEW_HEADER : VIEW_TILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int layout = viewType == VIEW_HEADER ? R.layout.item_mobile_settings_header : R.layout.item_mobile_bar_tile;
        return new RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {};
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int item = mItems.get(position);
        View view = holder.itemView;
        if (item == TOP_HEADER || item == BOTTOM_HEADER) {
            ((TextView) view).setText(item == TOP_HEADER ? R.string.mobile_settings_bar_top : R.string.mobile_settings_tabs_bottom_bar);
            return;
        }
        boolean fixed = item == MediaGroup.TYPE_HOME || item == YOU;
        ((ImageView) view.findViewById(R.id.bar_tile_icon)).setImageResource(icon(item));
        ((TextView) view.findViewById(R.id.bar_tile_title)).setText(title(item));
        ((TextView) view.findViewById(R.id.bar_tile_summary)).setText(summary(item));
        ImageView grip = view.findViewById(R.id.bar_tile_grip);
        grip.setImageResource(fixed ? R.drawable.ic_bar_lock : R.drawable.ic_bar_drag);
        boolean homeOff = item == MediaGroup.TYPE_HOME && !NavTabs.showHome(mContext);
        view.setAlpha(item == NavTabs.NONE || homeOff ? 0.6f : 1f);
        if (item == MediaGroup.TYPE_HOME) { // MEETUBE(issue #213): Home stays first but a tap removes it
            grip.setOnTouchListener(null);
            view.setOnClickListener(v -> pick(holder.getBindingAdapterPosition()));
            view.setClickable(true);
            return;
        }
        // The grip drags at once; a long press anywhere on the tile does too.
        grip.setOnTouchListener(fixed ? null : (v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mTouchHelper.startDrag(holder);
            }
            return false;
        });
        view.setOnClickListener(fixed ? null : v -> pick(holder.getBindingAdapterPosition()));
        view.setClickable(!fixed);
    }

    /** A tap: what a tab shows, or which bar Search sits in (the same sheet as any choice row). */
    private void pick(int position) {
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        Context c = mContext;
        int first = NavTabs.get(c, NavTabs.FIRST);
        int second = NavTabs.get(c, NavTabs.SECOND);
        int searchPosition = NavTabs.searchPosition(c);
        if (mItems.get(position) == SEARCH) {
            mPage.onRowClicked(SettingsRow.<Boolean>choice(c.getString(R.string.mobile_settings_search_place))
                    .option(c.getString(R.string.mobile_settings_search_place_bottom), false)
                    .option(c.getString(R.string.mobile_settings_search_place_top), true)
                    .bind(() -> NavTabs.searchOnTop(c), onTop -> NavTabs.setLayout(c, first, second, searchPosition, onTop)));
            return;
        }
        if (mItems.get(position) == MediaGroup.TYPE_HOME) {
            mPage.onRowClicked(SettingsRow.<Boolean>choice(c.getString(R.string.header_home))
                    .option(c.getString(R.string.mobile_settings_bar_home_shown), true)
                    .option(c.getString(R.string.mobile_settings_bar_home_removed),
                            c.getString(R.string.mobile_settings_bar_home_removed_desc), false)
                    .bind(() -> NavTabs.showHome(c), show -> NavTabs.setShowHome(c, show)));
            return;
        }
        int slot = 0;
        for (int i = mItems.indexOf(MediaGroup.TYPE_HOME) + 1; i < position; i++) {
            if (mItems.get(i) != SEARCH) {
                slot++;
            }
        }
        int chosenSlot = slot;
        SettingsRow.Choice<Integer> choice = SettingsRow.choice(c.getString(R.string.mobile_settings_bar_tab));
        for (int sectionId : NavTabs.CHOICES) {
            choice.option(c.getString(sectionTitle(sectionId)), sectionId);
        }
        choice.option(c.getString(R.string.mobile_settings_tabs_none), NavTabs.NONE);
        mPage.onRowClicked(choice.bind(() -> NavTabs.get(c, chosenSlot), sectionId -> {
            NavTabs.set(c, chosenSlot, sectionId);
            if (sectionId != NavTabs.NONE) {
                BrowsePresenter.instance(c).enableSection(sectionId, true);
            }
        }));
    }

    /** Moves the dragged item; false when it can't go there. Search jumps between the bars. */
    private boolean move(int from, int to) {
        int item = mItems.get(from);
        int bottomHeader = mItems.indexOf(BOTTOM_HEADER);
        int home = mItems.indexOf(MediaGroup.TYPE_HOME);
        int you = mItems.indexOf(YOU);
        if (item == SEARCH && from > home && to <= home) {
            to = bottomHeader; // up into the top bar
        } else if (item == SEARCH && from < bottomHeader && to >= bottomHeader) {
            to = home; // down to just after Home (Home moves up one as Search leaves)
        } else if (from < bottomHeader || to <= home || to >= you) {
            return false;
        }
        mItems.add(to, mItems.remove(from));
        notifyItemMoved(from, to);
        syncPreview();
        return true;
    }

    private void save() {
        boolean onTop = mItems.indexOf(SEARCH) < mItems.indexOf(BOTTOM_HEADER);
        List<Integer> tabs = new ArrayList<>();
        int searchPosition = NavTabs.searchPosition(mContext);
        for (int i = mItems.indexOf(MediaGroup.TYPE_HOME) + 1; i < mItems.indexOf(YOU); i++) {
            if (mItems.get(i) == SEARCH) {
                searchPosition = tabs.size();
            } else {
                tabs.add(mItems.get(i));
            }
        }
        NavTabs.setLayout(mContext, tabs.get(0), tabs.get(1), searchPosition, onTop);
        // A tab on the bar is switched on (#54): one turned off under You used to vanish from the bar too.
        for (int tab : tabs) {
            if (tab != NavTabs.NONE) {
                BrowsePresenter.instance(mContext).enableSection(tab, true);
            }
        }
        notifyItemChanged(mItems.indexOf(SEARCH)); // its line says where a drag would take it now
    }

    private final class Drag extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) {
                return 0;
            }
            int item = mItems.get(position);
            boolean draggable = item != TOP_HEADER && item != BOTTOM_HEADER && item != MediaGroup.TYPE_HOME && item != YOU;
            return makeMovementFlags(draggable ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder,
                              @NonNull RecyclerView.ViewHolder target) {
            boolean moved = move(holder.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            if (moved) {
                Haptics.tick(recyclerView);
            }
            return moved;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
            super.onSelectedChanged(holder, actionState);
            if (holder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                // Lifted off the stack: a touch bigger, raised, the grip lit.
                Haptics.longPress(holder.itemView);
                holder.itemView.animate().scaleX(1.05f).scaleY(1.05f).translationZ(dp(mContext, 14))
                        .setDuration(160).setInterpolator(new OvershootInterpolator(2f)).start();
                ((ImageView) holder.itemView.findViewById(R.id.bar_tile_grip)).setColorFilter(
                        ContextCompat.getColor(mContext, R.color.mobile_color_primary));
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            Haptics.click(holder.itemView);
            holder.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0)
                    .setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
            ((ImageView) holder.itemView.findViewById(R.id.bar_tile_grip)).clearColorFilter();
            save();
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }
    }

    // ---- the preview -------------------------------------------------------------------------

    private void syncPreview() {
        List<Integer> top = new ArrayList<>();
        top.add(CAST);
        List<Integer> bottom = new ArrayList<>();
        int bottomHeader = mItems.indexOf(BOTTOM_HEADER);
        for (int i = 0; i < mItems.size(); i++) {
            int item = mItems.get(i);
            if (item == TOP_HEADER || item == BOTTOM_HEADER || item == NavTabs.NONE
                    || (item == MediaGroup.TYPE_HOME && !NavTabs.showHome(mContext))) {
                continue;
            }
            (i < bottomHeader ? top : bottom).add(item);
        }
        sync(mPreviewTop, top);
        sync(mPreviewBottom, bottom);
    }

    /**
     * Puts the bar's icons in {@code want}'s order and slides each from where it was to where it
     * lands; one that just arrived pops in.
     */
    private void sync(LinearLayout bar, List<Integer> want) {
        Map<Integer, Integer> lefts = new HashMap<>();
        Map<Integer, View> views = new HashMap<>();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            lefts.put((Integer) child.getTag(), child.getLeft() + Math.round(child.getTranslationX()));
            views.put((Integer) child.getTag(), child);
        }
        boolean laidOut = bar.isLaidOut();
        bar.removeAllViews();
        for (int item : want) {
            View view = views.get(item);
            if (view == null) {
                view = previewIcon(item);
            }
            bar.addView(view);
        }
        if (!laidOut) {
            return;
        }
        bar.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                bar.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    Integer old = lefts.get((Integer) child.getTag());
                    child.animate().cancel();
                    if (old == null) {
                        child.setScaleX(0f);
                        child.setScaleY(0f);
                        child.animate().scaleX(1f).scaleY(1f).setDuration(320)
                                .setInterpolator(new OvershootInterpolator(2.5f)).start();
                    } else {
                        child.setTranslationX(old - child.getLeft());
                        child.animate().translationX(0f).setDuration(280)
                                .setInterpolator(new OvershootInterpolator(1.4f)).start();
                    }
                }
                return true;
            }
        });
    }

    private ImageView previewIcon(int item) {
        ImageView icon = new ImageView(mContext);
        icon.setTag(item);
        icon.setImageResource(item == CAST ? R.drawable.ic_mobile_cast : icon(item));
        icon.setImageTintList(ContextCompat.getColorStateList(mContext, R.color.mobile_color_on_surface));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(mContext, 9);
        icon.setPadding(pad, pad, pad, pad);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(mContext, 44), ViewGroup.LayoutParams.MATCH_PARENT));
        return icon;
    }

    private static LinearLayout pill(Context context) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(context, 6), 0, dp(context, 6), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 24));
        background.setColor(ContextCompat.getColor(context, R.color.mobile_color_card));
        background.setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.mobile_color_divider));
        pill.setBackground(background);
        return pill;
    }

    // ---- what each item looks like -------------------------------------------------------------

    private static int icon(int item) {
        switch (item) {
            case MediaGroup.TYPE_HOME:
                return R.drawable.ic_nav_home;
            case SEARCH:
                return R.drawable.ic_mobile_search;
            case YOU:
                return R.drawable.ic_nav_you;
            case MediaGroup.TYPE_SHORTS:
                return R.drawable.ic_nav_shorts;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.drawable.ic_nav_subscriptions;
            case VideoDownloads.SECTION_ID:
                return R.drawable.ic_nav_downloads;
            case com.liskovsoft.smartyoutubetv2.common.misc.LocalMedia.SECTION_ID:
                return R.drawable.ic_nav_media;
            case MediaGroup.TYPE_HISTORY:
                return R.drawable.ic_nav_history;
            default:
                return R.drawable.ic_mobile_add;
        }
    }

    private CharSequence title(int item) {
        switch (item) {
            case MediaGroup.TYPE_HOME:
                return mContext.getString(R.string.header_home);
            case SEARCH:
                return mContext.getString(R.string.mobile_search_button);
            case YOU:
                return mContext.getString(R.string.mobile_nav_you);
            case NavTabs.NONE:
                return mContext.getString(R.string.mobile_settings_bar_empty);
            default:
                return mContext.getString(sectionTitle(item));
        }
    }

    private CharSequence summary(int item) {
        switch (item) {
            case MediaGroup.TYPE_HOME:
                return mContext.getString(NavTabs.showHome(mContext)
                        ? R.string.mobile_settings_bar_home_summary : R.string.mobile_settings_bar_home_removed);
            case YOU:
                return mContext.getString(R.string.mobile_settings_bar_last);
            case SEARCH:
                return mContext.getString(mItems.indexOf(SEARCH) < mItems.indexOf(BOTTOM_HEADER)
                        ? R.string.mobile_settings_bar_search_down : R.string.mobile_settings_bar_search_up);
            case NavTabs.NONE:
                return mContext.getString(R.string.mobile_settings_bar_empty_summary);
            default:
                return mContext.getString(R.string.mobile_settings_bar_tab_summary);
        }
    }

    private static int sectionTitle(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_SHORTS:
                return R.string.header_shorts;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.string.header_subscriptions;
            case VideoDownloads.SECTION_ID:
                return R.string.header_downloads;
            case com.liskovsoft.smartyoutubetv2.common.misc.LocalMedia.SECTION_ID:
                return R.string.header_media;
            default:
                return R.string.header_history;
        }
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
