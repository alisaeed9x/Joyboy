package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.player.AudioEffects;
import com.newtube.mobile.player.AudioPreferences;
import com.newtube.mobile.ui.common.FocusRing;
import com.newtube.mobile.ui.common.GlassSwitch;
import com.newtube.mobile.ui.common.MobileSheets;

import java.text.DecimalFormat;
import java.util.Locale;

/** MEETUBE(issue #177): the phone's device equalizer, applied live to the playing session. */
final class EqualizerSheet {
    private final Context mContext;
    private final LayoutInflater mInflater;
    private final ChipGroup mPresets;
    private final ViewGroup mBandRows;
    @Nullable private AudioEffects.Bands mBands;
    private boolean mBinding;

    private EqualizerSheet(Context context, View content) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mPresets = content.findViewById(R.id.equalizer_presets);
        mBandRows = content.findViewById(R.id.equalizer_bands);
        mBands = AudioEffects.bands(context);

        GlassSwitch enabled = content.findViewById(R.id.equalizer_enabled);
        enabled.setChecked(AudioPreferences.isEqualizerEnabled(context));
        enabled.setOnCheckedChangeListener((button, on) -> AudioPreferences.setEqualizerEnabled(context, on));
        content.findViewById(R.id.equalizer_enabled_row).setOnClickListener(v -> enabled.toggle());

        if (mBands != null) {
            for (int preset = 0; preset < mBands.presets.length; preset++) {
                addPreset(mBands.presets[preset], preset);
            }
        }
        addPreset(context.getString(R.string.mobile_settings_equalizer_custom), AudioPreferences.CUSTOM);
        selectPreset();
        showBands();
        mPresets.setOnCheckedStateChangeListener((group, checked) -> {
            if (mBinding || checked.isEmpty()) {
                return;
            }
            Chip chip = group.findViewById(checked.get(0));
            AudioPreferences.setPreset(context, (int) chip.getTag(), chip.getText().toString());
            mBands = AudioEffects.bands(context);
            showBands();
        });

        View reset = content.findViewById(R.id.equalizer_reset);
        reset.setEnabled(mBands != null);
        reset.setOnClickListener(v -> {
            if (mBands != null) {
                AudioPreferences.setCustomLevels(context, new short[mBands.levels.length]);
                mBands = AudioEffects.bands(context);
                selectPreset();
                showBands();
            }
        });
    }

    static void show(SettingsPageFragment page) {
        BottomSheetDialog sheet = new BottomSheetDialog(page.requireContext());
        View content = LayoutInflater.from(sheet.getContext()).inflate(R.layout.sheet_mobile_equalizer, null);
        new EqualizerSheet(sheet.getContext(), content);
        sheet.setContentView(content);
        // Surface and elevation overlay come from bottomSheetDialogTheme, like the other sheets.
        sheet.setOnShowListener(dialog -> MobileSheets.expandTo(sheet, 0.85f));
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.setOnDismissListener(dialog -> page.rebuild());
        FocusRing.install(sheet.getWindow());
        FocusRing.focusFirstRow(sheet.getWindow());
        page.showDialog(sheet);
        sheet.show();
    }

    static CharSequence summary(Context context) {
        if (!AudioPreferences.isEqualizerEnabled(context)) {
            return context.getString(R.string.mobile_settings_off);
        }
        String preset = AudioPreferences.getPresetName(context);
        return AudioPreferences.getPreset(context) >= 0 && !preset.isEmpty() ? preset
                : context.getString(R.string.mobile_settings_equalizer_custom);
    }

    private void addPreset(String label, int preset) {
        Chip chip = (Chip) mInflater.inflate(R.layout.mobile_search_filter_chip, mPresets, false);
        chip.setId(View.generateViewId());
        chip.setTag(preset);
        chip.setText(label);
        chip.setEnabled(mBands != null);
        mPresets.addView(chip);
    }

    private void selectPreset() {
        mBinding = true;
        int preset = AudioPreferences.getPreset(mContext);
        if (mBands == null || preset < 0 || preset >= mBands.presets.length) {
            preset = AudioPreferences.CUSTOM;
        }
        for (int i = 0; i < mPresets.getChildCount(); i++) {
            Chip chip = (Chip) mPresets.getChildAt(i);
            if ((int) chip.getTag() == preset) {
                mPresets.check(chip.getId());
                break;
            }
        }
        mBinding = false;
    }

    private void showBands() {
        mBandRows.removeAllViews();
        if (mBands == null) {
            TextView unavailable = new TextView(mContext);
            unavailable.setText(R.string.mobile_settings_equalizer_unavailable);
            unavailable.setTextColor(androidx.core.content.ContextCompat.getColor(mContext,
                    R.color.mobile_color_on_surface_secondary));
            mBandRows.addView(unavailable);
            return;
        }
        AudioEffects.Bands bands = mBands;
        for (int band = 0; band < bands.levels.length; band++) {
            int index = band;
            View row = mInflater.inflate(R.layout.item_mobile_equalizer_band, mBandRows, false);
            String frequency = frequencyLabel(bands.frequencies[band]);
            ((TextView) row.findViewById(R.id.equalizer_band_frequency)).setText(frequency);
            TextView level = row.findViewById(R.id.equalizer_band_level);
            level.setText(levelLabel(bands.levels[band]));
            SeekBar slider = row.findViewById(R.id.equalizer_band_slider);
            slider.setContentDescription(frequency);
            slider.setMax(bands.maxLevel - bands.minLevel);
            slider.setProgress(bands.levels[band] - bands.minLevel);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        bands.levels[index] = (short) (bands.minLevel + progress);
                        level.setText(levelLabel(bands.levels[index]));
                        AudioPreferences.setCustomLevels(mContext, bands.levels);
                        selectPreset();
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
            mBandRows.addView(row);
        }
    }

    private static String frequencyLabel(int millihertz) {
        float hz = millihertz / 1000f;
        return hz >= 1000 ? new DecimalFormat("0.#").format(hz / 1000f) + " kHz" : Math.round(hz) + " Hz";
    }

    private static String levelLabel(short millibels) {
        return String.format(Locale.getDefault(), "%+.1f dB", millibels / 100f);
    }
}
