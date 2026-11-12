package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/**
 * MEETUBE(all subscriptions): long-press "All" on the Subscriptions strip - every subscribed channel,
 * A-Z as a list of rows, as YouTube's "All subscriptions"; a tap opens the channel. The channels are the strip's
 * own (one fetch for both).
 */
final class SubsChannelsSheet {
    private SubsChannelsSheet() {
    }

    static void show(Activity activity, List<Video> channels) {
        BottomSheetDialog sheet = Haptics.watch(new BottomSheetDialog(activity));
        float dp = activity.getResources().getDisplayMetrics().density;

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText(R.string.mobile_subs_all_channels);
        title.setTextColor(ContextCompat.getColor(activity, R.color.mobile_color_on_surface));
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(Math.round(20 * dp), Math.round(12 * dp), Math.round(20 * dp), Math.round(4 * dp));
        content.addView(title);

        List<Video> sorted = new ArrayList<>(channels);
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY); // "aceu" sits with "Aakash", as on YouTube
        sorted.sort((a, b) -> collator.compare(String.valueOf(a.getTitle()), String.valueOf(b.getTitle())));

        // Rows whatever shape the items have (VideoCardAdapter drew a channel without its
        // channel flags as a video card): avatar, name, ring when it is live.
        VideoCardAdapter.OnVideoClickListener open = channel -> {
            sheet.dismiss();
            MediaServiceManager.chooseChannelPresenter(activity, channel);
        };
        RecyclerView.Adapter<VideoCardAdapter.ChannelViewHolder> adapter =
                new RecyclerView.Adapter<VideoCardAdapter.ChannelViewHolder>() {
            @Override
            public VideoCardAdapter.ChannelViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new VideoCardAdapter.ChannelViewHolder(android.view.LayoutInflater.from(activity)
                        .inflate(R.layout.item_mobile_channel_card, parent, false), open);
            }

            @Override
            public void onBindViewHolder(VideoCardAdapter.ChannelViewHolder holder, int position) {
                holder.bind(sorted.get(position), null);
            }

            @Override
            public int getItemCount() {
                return sorted.size();
            }
        };
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        // Tall enough to scroll a few hundred channels; the sheet opens at full height.
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.85f)));

        sheet.setContentView(content);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.show();
    }
}
