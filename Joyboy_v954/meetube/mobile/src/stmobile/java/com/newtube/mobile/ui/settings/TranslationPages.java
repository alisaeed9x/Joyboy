package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.translate.TrCfg;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JOYBOY(translation engine): the pages behind Settings > "إعدادات محرك الترجمة". They sit under
 * MeeTube's own settings and are built from the same row kinds, so they look and move like the
 * rest. Every row reads and writes {@link TrCfg}: the engine's own keys.
 */
final class TranslationPages {
    private static final String[] DIALECTS = {"مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني", "فصحى"};
    private static final String[] STYLES = {"حرفي", "شعبي", "جرئ", "+18"};
    private static final String[] STRENGTHS = {"خفيفة", "متوسطة", "شديدة"};
    private static final String[][] BASE_LANGS = {
            {"ar", "العربية"}, {"en", "English"}, {"fr", "Français"}, {"es", "Español"}, {"de", "Deutsch"},
            {"tr", "Türkçe"}, {"hi", "हिन्दी"}, {"ko", "한국어"}, {"ja", "日本語"}, {"zh", "中文"}};
    private static final int[] CHUNKS = {30, 60, 90, 120};
    private static final String DEFAULT_MODEL = "gemini-flash-lite-latest";

    private TranslationPages() {
    }

    /** The door: one row per section of the engine's settings. */
    static SettingsPages.Page index(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.note("كل إعدادات الترجمة هنا: اللهجة والأسلوب، مفاتيح جيميناي، التفريغ والصوت، والموديل والسرعة."));
        rows.add(SettingsRow.page(R.drawable.ic_settings_captions, "🚀 خط الترجمة",
                "اللهجة · الأسلوب · الشدة · اللغة الأساسية", SettingsPages.TR_PIPELINE));
        rows.add(SettingsRow.page(R.drawable.ic_settings_privacy, "🔑 المفاتيح",
                "مفاتيح جيميناي", SettingsPages.TR_KEYS));
        rows.add(SettingsRow.page(R.drawable.ic_settings_audio, "🎙 التفريغ والصوت",
                "مسار الصوت · الأصوات الخلفية · دقة التوقيت", SettingsPages.TR_AUDIO));
        rows.add(SettingsRow.page(R.drawable.ic_settings_advanced, "🤖 الموديل والأداء",
                "الموديل · طول المقطع · المقاطع قدّام التشغيل · التنقيح", SettingsPages.TR_PERF));
        return new SettingsPages.Page("إعدادات محرك الترجمة", rows);
    }

    static SettingsPages.Page pipeline(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.note("الباتشات الجاية بتتترجم بالاختيارات دي."));

        SettingsRow.Choice<String> dialect = SettingsRow.choice("لهجة الترجمة");
        for (String d : DIALECTS) {
            dialect.option(d, d);
        }
        rows.add(dialect.summary("فصحى = من غير تحويل لهجة").bind(
                () -> TrCfg.str(context, "lang", "مصري"),
                value -> {
                    TrCfg.put(context, "lang", value);
                    TrCfg.put(context, "conv_dialect", "فصحى".equals(value) ? "" : value);
                }));

        SettingsRow.Choice<String> style = SettingsRow.choice("أسلوب الترجمة");
        for (String s : STYLES) {
            style.option(s, s);
        }
        rows.add(style.bind(
                () -> {
                    String v = TrCfg.str(context, "tone_style", "");
                    return v.isEmpty() ? "حرفي" : v;
                },
                value -> {
                    TrCfg.put(context, "style", value);
                    TrCfg.put(context, "tone_style", "حرفي".equals(value) ? "" : value);
                }));

        SettingsRow.Choice<String> strength = SettingsRow.choice("الشدة");
        for (String s : STRENGTHS) {
            strength.option(s, s);
        }
        rows.add(strength.summary("بتشتغل مع أسلوب شعبي / جرئ / +18 بس").bind(
                () -> {
                    String v = TrCfg.str(context, "tone_strength", "متوسطة");
                    return v.isEmpty() ? "متوسطة" : v;
                },
                value -> TrCfg.put(context, "tone_strength", value)));

        SettingsRow.Choice<String> base = SettingsRow.choice("اللغة الأساسية");
        for (String[] l : BASE_LANGS) {
            base.option(l[1], l[0]);
        }
        rows.add(base.summary("لو لغة الفيديو الأصلية هي دي، الترجمة التلقائية مش بتشتغل عليه (بيتفرّغ بس)").bind(
                () -> TrCfg.str(context, "base_lang", "ar"),
                value -> TrCfg.put(context, "base_lang", value)));

        return new SettingsPages.Page("🚀 خط الترجمة", rows);
    }

    static SettingsPages.Page keys(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.note("المفتاح اختياري. من غيره الترجمة بتشتغل بالتفريغ على الجهاز وترجمة جوجل المجانية؛ ومعاه بتبقى أدق وأسرع."));
        rows.add(SettingsRow.action("مفاتيح جيميناي",
                keySummary(context, "keys"),
                page -> editText(page, "مفاتيح جيميناي (مفتاح في كل سطر)", TrCfg.str(context, "keys", ""),
                        true, false, value -> {
                            TrCfg.put(context, "keys", value);
                            page.rebuild();
                        })));
        rows.add(SettingsRow.action("مفاتيح احتياطية",
                keySummary(context, "backup"),
                page -> editText(page, "مفاتيح احتياطية (مفتاح في كل سطر)", TrCfg.str(context, "backup", ""),
                        true, false, value -> {
                            TrCfg.put(context, "backup", value);
                            page.rebuild();
                        })));
        rows.add(SettingsRow.action("مفاتيح الوضع البصري",
                keySummary(context, "viskeys"),
                page -> editText(page, "مفاتيح الوضع البصري فقط", TrCfg.str(context, "viskeys", ""),
                        true, false, value -> {
                            TrCfg.put(context, "viskeys", value);
                            page.rebuild();
                        })));
        return new SettingsPages.Page("🔑 المفاتيح", rows);
    }

    static SettingsPages.Page audio(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.action("رقم مسار الصوت",
                "لو الفيديو فيه أكتر من لغة · الحالي: " + TrCfg.integer(context, "atrack", 1),
                page -> editText(page, "رقم مسار الصوت", String.valueOf(TrCfg.integer(context, "atrack", 1)),
                        false, true, value -> {
                            TrCfg.put(context, "atrack", value);
                            page.rebuild();
                        })));
        rows.add(SettingsRow.toggle("الأصوات الخلفية",
                "التقاط الهمهمات والموسيقى وعرضها كسطر وصف فوق الفيديو",
                () -> TrCfg.bool(context, "soundtags", true),
                on -> TrCfg.putBool(context, "soundtags", on)));
        rows.add(SettingsRow.toggle("دقة توقيت أعلى",
                "بيفك الصوت من قبل البداية بـ 3 ثواني — أبطأ شوية",
                () -> TrCfg.bool(context, "hitiming", false),
                on -> TrCfg.putBool(context, "hitiming", on)));
        rows.add(SettingsRow.note("موديلات التفريغ على الجهاز (Whisper) هتتضاف هنا مع نقل المحرك نفسه."));
        return new SettingsPages.Page("🎙 التفريغ والصوت", rows);
    }

    static SettingsPages.Page perf(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.action("الموديل",
                TrCfg.str(context, "model", DEFAULT_MODEL),
                page -> editText(page, "الموديل", TrCfg.str(context, "model", DEFAULT_MODEL),
                        false, false, value -> {
                            TrCfg.put(context, "model", value.trim().isEmpty() ? DEFAULT_MODEL : value.trim());
                            page.rebuild();
                        })));

        SettingsRow.Choice<Integer> chunk = SettingsRow.choice("طول المقطع");
        for (int c : CHUNKS) {
            chunk.option(c + " ثانية", c);
        }
        rows.add(chunk.bind(
                () -> nearest(CHUNKS, TrCfg.integer(context, "chunk", 60)),
                value -> TrCfg.put(context, "chunk", String.valueOf(value))));

        rows.add(SettingsRow.action("المقاطع قدّام التشغيل",
                "عدد المقاطع اللي بتترجم قدّام مكان التشغيل · الحالي: " + TrCfg.integer(context, "ahead", 3),
                page -> editText(page, "عدد المقاطع قدّام التشغيل", String.valueOf(TrCfg.integer(context, "ahead", 3)),
                        false, true, value -> {
                            TrCfg.put(context, "ahead", value);
                            page.rebuild();
                        })));
        rows.add(SettingsRow.action("باتشات m3u8 مقدّمًا",
                "عدد الباتشات اللي بتتحمّل مقدّمًا قدّام الترجمة · الحالي: " + TrCfg.integer(context, "hls_ahead", 3),
                page -> editText(page, "باتشات m3u8 مقدّمًا", String.valueOf(TrCfg.integer(context, "hls_ahead", 3)),
                        false, true, value -> {
                            TrCfg.put(context, "hls_ahead", value);
                            page.rebuild();
                        })));
        rows.add(SettingsRow.toggle("كمّل ترجمة باقي الفيديو",
                "بعد ما توصل لمكان التشغيل بيرجع للأول لو فيه جزء ناقص",
                () -> TrCfg.bool(context, "keepgoing", true),
                on -> TrCfg.putBool(context, "keepgoing", on)));
        rows.add(SettingsRow.toggle("تنقيح جزئي أثناء الترجمة",
                "كل مقطع يخلص، الجمل الجديدة بتتبعت للتنقيح وتتصحّح الترجمة الحرفية",
                () -> TrCfg.bool(context, "prefine", true),
                on -> TrCfg.putBool(context, "prefine", on)));
        return new SettingsPages.Page("🤖 الموديل والأداء", rows);
    }

    private static int nearest(int[] options, int value) {
        int best = options[0];
        for (int o : options) {
            if (Math.abs(o - value) < Math.abs(best - value)) {
                best = o;
            }
        }
        return best;
    }

    private static CharSequence keySummary(Context context, String key) {
        int n = TrCfg.keyCount(context, key);
        return n == 0 ? "فاضي" : n + (n == 1 ? " مفتاح" : " مفاتيح");
    }

    /** A text box in a dialog - the settings tree has no inline text row, so a text setting is an action. */
    private static void editText(@NonNull SettingsPageFragment page, String title, String current,
                                 boolean multiline, boolean numeric, Consumer<String> onSave) {
        Context context = page.requireContext();
        EditText input = new EditText(context);
        input.setText(current);
        input.setSelection(input.getText().length());
        if (numeric) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
        } else if (multiline) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setMinLines(3);
            input.setMaxLines(8);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setSingleLine(true);
        }
        FrameLayout box = new FrameLayout(context);
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        page.showDialog(new MaterialAlertDialogBuilder(context, R.style.MobileAlertDialog)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> onSave.accept(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }
}
