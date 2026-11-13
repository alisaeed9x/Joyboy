package com.newtube.mobile.ui.playback;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MobileSheets;

import java.util.List;

/**
 * Live-chat panel for the touch watch page. The hosting Activity owns the actual chat stream (it
 * receives the SmartTube {@code ChatReceiver} pushed by the reused {@code ChatController}); this
 * sheet just seeds itself from the current snapshot and appends new messages as they arrive via the
 * {@link Host} callback, auto-scrolling to the newest. Best-effort: on a non-live video the Activity
 * never shows the chat entry, so this sheet is only ever opened when a chat stream is active.
 */
public class LiveChatSheet extends BottomSheetDialogFragment {

    private static final String TAG_SHEET = "mobile_live_chat_sheet";

    /** Implemented by the hosting Activity to expose the live-chat stream to the sheet. */
    public interface Host {
        List<ChatItem> getChatSnapshot();
        void registerChatObserver(Observer observer);
        void unregisterChatObserver(Observer observer);
        /** The panel was closed by the user: stop the live-chat stream the Activity opened for it. */
        void onChatSheetDismissed();
        /** The upright page's video, which the panel stays under; null sideways (a side column). */
        @Nullable View getChatVideoArea();
        /** Sideways, the video steps aside for the column this wide (px); 0 gives it the room back. */
        void setChatColumn(int widthPx);
    }

    public interface Observer {
        void onChatItem(ChatItem item);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return Haptics.watch(super.onCreateDialog(savedInstanceState)); // MEETUBE(haptics)
    }

    private LiveChatAdapter mAdapter;
    private RecyclerView mList;
    private TextView mEmpty;
    private Host mHost;

    private final Observer mObserver = new Observer() {
        @Override
        public void onChatItem(ChatItem item) {
            if (mList == null) {
                return;
            }
            mList.post(() -> appendMessage(item));
        }
    };

    public static void show(@NonNull androidx.fragment.app.FragmentManager fm) {
        if (fm.findFragmentByTag(TAG_SHEET) != null) {
            return; // already open: a second panel stacked a second window on the first
        }
        new LiveChatSheet().show(fm, TAG_SHEET);
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (context instanceof Host) {
            mHost = (Host) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_mobile_live_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.chat_sheet_close).setOnClickListener(v -> dismissAllowingStateLoss());

        mEmpty = view.findViewById(R.id.chat_sheet_empty);
        mList = view.findViewById(R.id.chat_sheet_list);

        mAdapter = new LiveChatAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(true); // newest at the bottom, list starts pinned to the end
        mList.setLayoutManager(lm);
        mList.setAdapter(mAdapter);

        if (mHost != null) {
            List<ChatItem> snapshot = mHost.getChatSnapshot();
            mAdapter.setItems(snapshot);
            if (snapshot != null && !snapshot.isEmpty()) {
                mEmpty.setVisibility(View.GONE);
                mList.scrollToPosition(mAdapter.getItemCount() - 1);
            }
            mHost.registerChatObserver(mObserver);
        }
    }

    private void appendMessage(ChatItem item) {
        if (mAdapter == null) {
            return;
        }
        int count = mAdapter.addItem(item);
        if (count > 0) {
            mEmpty.setVisibility(View.GONE);
            mList.scrollToPosition(count - 1);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        placeBesideVideo();
        // The player rotates in place (configChanges) and its bars come and go after that: the
        // panel follows every layout of the page, not just the first one after a rotation.
        requireActivity().getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(mPageLayout);
    }

    @Override
    public void onStop() {
        requireActivity().getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(mPageLayout);
        super.onStop();
    }

    private final android.view.ViewTreeObserver.OnGlobalLayoutListener mPageLayout = this::placeBesideVideo;

    /**
     * MEETUBE(live chat, issue #32): never over the video. Upright the window is only the room under
     * the video; sideways it is a column on the right of it, YouTube's. Taps beside the window reach
     * the player (FLAG_NOT_TOUCH_MODAL), so the video stays playable while the chat runs.
     */
    private void placeBesideVideo() {
        Dialog dialog = getDialog();
        Window window = dialog != null ? dialog.getWindow() : null;
        if (window == null || mHost == null || !dialog.isShowing()) {
            return;
        }
        View page = requireActivity().getWindow().getDecorView();
        View video = mHost.getChatVideoArea();
        if (video == null) {
            // A card floating a margin in from the edges, rounded all round: off the screen's top
            // edge, Material also finds no status bar inset to leave room for.
            int margin = cardMarginPx(page);
            int column = Math.round(page.getWidth() * 0.4f);
            mHost.setChatColumn(column + 2 * margin); // the video beside the chat, never under it
            if (!resize(window, column, page.getHeight() - 2 * margin, Gravity.END | Gravity.CENTER_VERTICAL, margin)) {
                return;
            }
            MobileSheets.keepSystemBarsHidden(window); // over the fullscreen player
        } else {
            mHost.setChatColumn(0);
            int[] at = new int[2];
            video.getLocationInWindow(at);
            int under = page.getHeight() - (at[1] + video.getHeight());
            // A video filling the page (vertical fullscreen) leaves no room: take its lower half.
            int height = under < page.getHeight() / 3 ? page.getHeight() / 2 : under;
            if (!resize(window, ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM, 0)) {
                return;
            }
            MobileSheets.showSystemBars(window); // back from fullscreen: the page has its bars
        }
        window.setDimAmount(0f);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        // The side card is frosted square and rounded all round by its outline (roundCard). Posted:
        // a sheet's first frost runs as its window attaches and would put the sheet's corners back.
        boolean rounded = video != null;
        window.getDecorView().post(() -> com.newtube.mobile.ui.common.SheetGlass.frost(dialog, rounded));
        mColumn = video == null;
        // The side card leaves no room for system bars: hidden over the fullscreen player, they
        // still reach Material as insets, which capped the sheet a status bar's height short of its
        // window's top - an empty strip above the handle. Consumed at the root, no layer sees them.
        View decor = window.getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) ->
                mColumn ? WindowInsetsCompat.CONSUMED : ViewCompat.onApplyWindowInsets(v, insets));
        decor.requestApplyInsets();
        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (frame != null) {
            roundCard(frame, mColumn);
            frame.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.requestLayout();
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(frame);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    /**
     * MEETUBE(description): {@code dialog} (just shown) as the landscape side card the chat is: a
     * column on the right, the video still playing and touchable beside it. Returns the room in px
     * the video steps aside for (the host's {@link Host#setChatColumn}).
     * ponytail: the chat's own placeBesideVideo keeps its copy, as it also follows rotations.
     */
    static int placeSideCard(@NonNull Dialog dialog, @NonNull View page) {
        Window window = dialog.getWindow();
        if (window == null) {
            return 0;
        }
        int margin = cardMarginPx(page);
        int column = Math.round(page.getWidth() * 0.4f);
        resize(window, column, page.getHeight() - 2 * margin, Gravity.END | Gravity.CENTER_VERTICAL, margin);
        MobileSheets.keepSystemBarsHidden(window);
        window.setDimAmount(0f);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        window.getDecorView().post(() -> com.newtube.mobile.ui.common.SheetGlass.frost(dialog, false));
        // No bar insets: hidden over the player, they would still leave an empty strip up top.
        View decor = window.getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> WindowInsetsCompat.CONSUMED);
        decor.requestApplyInsets();
        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (frame != null) {
            roundCard(frame, true);
            frame.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.requestLayout();
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(frame);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
        return column + 2 * margin;
    }

    /** The panel is the landscape side card (not the upright one under the video). */
    private boolean mColumn;

    /** False when the window already has this size: every page layout lands here. */
    private static boolean resize(Window window, int width, int height, int gravity, int x) {
        WindowManager.LayoutParams lp = window.getAttributes();
        if (lp.width == width && lp.height == height && lp.gravity == gravity && lp.x == x) {
            return false;
        }
        lp.width = width;
        lp.height = height;
        lp.gravity = gravity;
        lp.x = x; // from the end edge, with gravity END
        window.setAttributes(lp);
        return true;
    }

    private static final float CARD_MARGIN_DP = 12f;
    private static final float CARD_CORNER_DP = 24f;

    /**
     * MEETUBE(live chat, issue #32): a landscape side panel as a card, every corner rounded (the
     * live chat here, a video's comments in MobilePlaybackActivity); {@code false} undoes it.
     */
    static void roundCard(View view, boolean card) {
        if (!card) {
            view.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            view.setClipToOutline(false);
            view.setForeground(null);
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        float corner = CARD_CORNER_DP * density;
        view.setForeground(com.newtube.mobile.ui.common.GlassView.rim(corner, density)); // the nav pill's glass rim
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), corner);
            }
        });
        view.setClipToOutline(true);
    }

    /** The margin a side card keeps from the screen's edges and the video, in px. */
    static int cardMarginPx(View view) {
        return Math.round(CARD_MARGIN_DP * view.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        // User closed the panel (X, swipe, back, scrim tap): tell the Activity to stop the live-chat
        // poll it opened for us. onDestroyView only tears down the observer - the poll outlives the
        // view during background audio, so its lifetime is tied to this explicit dismiss instead.
        // Stop following the page first: the layout that gives the video its room back must not
        // place the closing panel (and step the video aside) once more.
        requireActivity().getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(mPageLayout);
        if (mHost != null) {
            mHost.setChatColumn(0);
            mHost.onChatSheetDismissed();
        }
        super.onDismiss(dialog);
    }

    @Override
    public void onDestroyView() {
        if (mHost != null) {
            mHost.unregisterChatObserver(mObserver);
        }
        super.onDestroyView();
    }
}
