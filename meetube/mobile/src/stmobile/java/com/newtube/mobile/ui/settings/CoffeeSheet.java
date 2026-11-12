package com.newtube.mobile.ui.settings;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.FocusRing;
import com.newtube.mobile.ui.common.MobileSheets;

// MEETUBE(coffee): a warm thank-you sheet with local cups and a UPI fallback.
final class CoffeeSheet {
    private CoffeeSheet() { }

    static void show(Context context) {
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        Context themed = sheet.getContext();
        ViewGroup root = (ViewGroup) LayoutInflater.from(themed).inflate(R.layout.sheet_mobile_coffee, null);
        ChipGroup cups = root.findViewById(R.id.coffee_cups);
        TextView pay = root.findViewById(R.id.coffee_pay);
        int[] amount = {200}; // a cold coffee to start with
        int[] labels = {R.string.mobile_settings_coffee_cold};
        int[] prices = {200};
        for (int i = 0; i < prices.length; i++) {
            Chip chip = (Chip) LayoutInflater.from(themed).inflate(R.layout.mobile_search_filter_chip, cups, false);
            chip.setId(View.generateViewId());
            chip.setText(labels[i]);
            chip.setTag(prices[i]);
            cups.addView(chip);
            if (prices[i] == amount[0]) cups.check(chip.getId());
        }
        android.widget.EditText custom = root.findViewById(R.id.coffee_custom);
        Runnable label = () -> {
            pay.setEnabled(amount[0] > 0);
            pay.setText(amount[0] > 0 ? themed.getString(R.string.mobile_settings_coffee_pay, String.valueOf(amount[0]))
                    : themed.getString(R.string.mobile_settings_coffee_pay_custom));
        };
        label.run();
        cups.setOnCheckedStateChangeListener((group, checked) -> {
            if (checked.isEmpty()) return; // unchecked by typing an amount
            amount[0] = (int) group.findViewById(checked.get(0)).getTag();
            custom.setText(null); // a cup again: the typed amount goes
            label.run();
        });
        // Your own amount: typing unpicks the cup, clearing it with no cup picked leaves Pay off.
        custom.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (s.length() > 0) {
                    cups.clearCheck();
                    amount[0] = Integer.parseInt(s.toString()); // digits only, 6 at most
                } else if (cups.getCheckedChipId() == View.NO_ID) {
                    amount[0] = 0;
                }
                label.run();
            }
        });
        custom.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE && amount[0] > 0) {
                pay.performClick();
                return true;
            }
            return false;
        });
        root.findViewById(R.id.coffee_india).setOnClickListener(v -> {
            TransitionManager.beginDelayedTransition(root);
            v.setSelected(true);
            GradientDrawable selected = new GradientDrawable();
            float dp = themed.getResources().getDisplayMetrics().density;
            selected.setCornerRadius(22 * dp);
            selected.setColor(ContextCompat.getColor(themed, R.color.meetube_glass_selected));
            selected.setStroke(Math.round(2 * dp), ContextCompat.getColor(themed, R.color.meetube_accent));
            v.setBackground(selected);
            root.findViewById(R.id.coffee_india_panel).setVisibility(View.VISIBLE);
        });
        root.findViewById(R.id.coffee_elsewhere).setOnClickListener(v -> {
            Utils.openLinkExt(context, context.getString(R.string.mobile_settings_coffee_url));
            sheet.dismiss();
        });
        pay.setOnClickListener(v -> pay(themed, amount[0]));
        root.findViewById(R.id.coffee_copy).setOnClickListener(v -> copy(themed, R.string.mobile_settings_coffee_copied));
        View qr = root.findViewById(R.id.coffee_qr);
        qr.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        12 * view.getResources().getDisplayMetrics().density);
            }
        });
        qr.setClipToOutline(true);
        sheet.setContentView(root);
        sheet.setOnShowListener(dialog -> MobileSheets.expandTo(sheet, 0.9f));
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        FocusRing.install(sheet.getWindow());
        FocusRing.focusFirstRow(sheet.getWindow());
        sheet.show();
    }

    private static void pay(Context context, int amount) {
        String id = context.getString(R.string.mobile_settings_coffee_upi_id);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=" + id
                + "&pn=Yashwanth&cu=INR&am=" + amount + "&tn="
                + Uri.encode(context.getString(R.string.mobile_settings_coffee_payment_note))));
        // Straight to the UPI app (Android asks which one when there are several); resolveActivity
        // can't see them from Android 11 without a <queries> entry, so a missing app is the throw.
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            copy(context, R.string.mobile_settings_coffee_no_app);
        }
    }

    private static void copy(Context context, int message) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.mobile_settings_coffee_upi_label),
                    context.getString(R.string.mobile_settings_coffee_upi_id)));
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }
}
