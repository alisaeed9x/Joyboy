package com.newtube.mobile.ui.playback;

import android.content.Context;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * MEETUBE(issue #139): a comment in the app's language, from Google Translate's public endpoint
 * (the one its web widget uses; no key). Blocking: call it off the main thread.
 */
final class CommentTranslator {
    private CommentTranslator() {
    }

    /** The translation, or null when the comment is already in {@code target}. */
    static String translate(String text, String target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl=" + target).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true); // POST: a long comment would not fit in the address
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            // Android's own "Dalvik/..." agent is answered with a 302 to a captcha page, a browser's is not.
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(("q=" + URLEncoder.encode(text, "UTF-8")).getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("translate: HTTP " + connection.getResponseCode());
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    body.write(buffer, 0, read);
                }
            }
            // [[["sentence", "original", ...], ...], null, "source language", ...]
            JSONArray reply = new JSONArray(body.toString("UTF-8"));
            if (target.equalsIgnoreCase(reply.optString(2))) {
                return null;
            }
            JSONArray sentences = reply.getJSONArray(0);
            StringBuilder translation = new StringBuilder();
            for (int i = 0; i < sentences.length(); i++) {
                translation.append(sentences.getJSONArray(i).optString(0));
            }
            return translation.toString().trim();
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Whether the comment reads as another language than the app's, by Android's on-device detector
     * (nothing leaves the phone). Without one (below Android 10) every comment offers it. Blocking.
     */
    static boolean isForeign(Context context, String text) {
        // Emoji, numbers, "!!!": nothing to translate, though the detector still names a language.
        if (text.codePoints().noneMatch(Character::isLetter)) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            return true;
        }
        android.view.textclassifier.TextClassificationManager manager =
                context.getSystemService(android.view.textclassifier.TextClassificationManager.class);
        if (manager == null) {
            return true;
        }
        android.view.textclassifier.TextLanguage language = manager.getTextClassifier().detectLanguage(
                new android.view.textclassifier.TextLanguage.Request.Builder(text).build());
        if (language.getLocaleHypothesisCount() == 0) {
            return false;
        }
        android.icu.util.ULocale top = language.getLocale(0);
        // new Locale(...) folds the old and new codes together ("he"/"iw", "id"/"in")
        String app = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        return language.getConfidenceScore(top) >= 0.5f && !new Locale(top.getLanguage()).getLanguage().equals(new Locale(app).getLanguage());
    }

    /** "English": the language {@link #targetLanguage} translates to, in its own UI words. */
    static String targetName(Context context) {
        Locale app = context.getResources().getConfiguration().getLocales().get(0);
        return app.getDisplayLanguage(app);
    }

    /** The app's language as Google names it ("zh-TW", not "zh"; Java's old "in" is "id"). */
    static String targetLanguage(Context context) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String language = locale.getLanguage();
        if ("in".equals(language)) {
            return "id";
        }
        if ("zh".equals(language)) {
            String country = locale.getCountry();
            return "TW".equals(country) || "HK".equals(country) || "Hant".equals(locale.getScript()) ? "zh-TW" : "zh-CN";
        }
        return language;
    }
}
