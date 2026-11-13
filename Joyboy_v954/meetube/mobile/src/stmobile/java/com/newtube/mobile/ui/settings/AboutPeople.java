package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * MEETUBE(about): Settings > About's two pages of people - the contributors with what each did, and
 * everyone who opened an issue, read live from GitHub so the list never needs a release to grow.
 */
final class AboutPeople {
    private static final String OWNER = "yashoncode";
    private static final String ISSUES_URL = "https://api.github.com/repos/yashoncode/meetube/issues?state=all&per_page=100&page=";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    /** ponytail: read once per process; a new reporter shows after the app restarts. */
    @Nullable
    private static List<Reporter> sReporters;

    private static final class Reporter {
        final String login;
        final String avatar;
        int issues;

        Reporter(String login, String avatar) {
            this.login = login;
            this.avatar = avatar;
        }
    }

    private AboutPeople() {
    }

    static SettingsPages.Page contributors(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.header(context.getString(R.string.mobile_about_contributors_code)));
        rows.add(person("Capricornus007", context.getString(R.string.mobile_about_credit_zh_tw)));
        rows.add(person("MAKAKAIglitch", context.getString(R.string.mobile_about_credit_ru)));
        rows.add(SettingsRow.header(context.getString(R.string.mobile_about_contributors_built_on)));
        rows.add(person("yuliskov", context.getString(R.string.mobile_about_credit_smarttube)));
        rows.add(person("aleixrodriala", context.getString(R.string.mobile_about_credit_newtube)));
        rows.add(person("Kyant0", context.getString(R.string.mobile_about_credit_backdrop)));
        return new SettingsPages.Page(context.getString(R.string.mobile_about_contributors), rows);
    }

    static SettingsPages.Page reporters(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.note(context.getString(R.string.mobile_about_reporters_note)));
        rows.add(SettingsRow.custom(context.getString(R.string.mobile_about_reporters), AboutPeople::reporterList));
        return new SettingsPages.Page(context.getString(R.string.mobile_about_reporters), rows);
    }

    private static SettingsRow person(String login, String what) {
        return SettingsRow.custom(login, (parent, page) ->
                personView(parent, login, "https://github.com/" + login + ".png?size=96", what));
    }

    private static View personView(ViewGroup parent, String login, String avatar, CharSequence what) {
        Context context = parent.getContext();
        View row = LayoutInflater.from(context).inflate(R.layout.item_mobile_about_person, parent, false);
        ((TextView) row.findViewById(R.id.about_person_name)).setText(login);
        ((TextView) row.findViewById(R.id.about_person_what)).setText(what);
        Glide.with(context).load(avatar).circleCrop().into((ImageView) row.findViewById(R.id.about_person_avatar));
        row.setOnClickListener(v -> Utils.openLinkExt(context, "https://github.com/" + login));
        return row;
    }

    private static View reporterList(@NonNull ViewGroup parent, @NonNull SettingsAdapter.Listener page) {
        LinearLayout list = new LinearLayout(parent.getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        if (sReporters != null) {
            fill(list, sReporters);
            return list;
        }
        TextView status = (TextView) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_settings_note, list, false);
        status.setText(R.string.mobile_about_reporters_loading);
        list.addView(status);
        RxHelper.execute(RxHelper.fromCallable(AboutPeople::fetchReporters),
                reporters -> {
                    sReporters = reporters;
                    list.removeAllViews();
                    fill(list, reporters);
                },
                error -> status.setText(R.string.mobile_about_reporters_failed));
        return list;
    }

    private static void fill(LinearLayout list, List<Reporter> reporters) {
        for (Reporter reporter : reporters) {
            list.addView(personView(list, reporter.login, reporter.avatar, list.getResources()
                    .getQuantityString(R.plurals.mobile_about_reporters_count, reporter.issues, reporter.issues)));
        }
    }

    /** Every issue (not pull request) on the repo, counted per author, most issues first. */
    private static List<Reporter> fetchReporters() throws Exception {
        Map<String, Reporter> byLogin = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(ISSUES_URL + page).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            JSONArray issues;
            try (InputStream in = connection.getInputStream(); Scanner scanner = new Scanner(in, "UTF-8")) {
                issues = new JSONArray(scanner.useDelimiter("\\A").next());
            } finally {
                connection.disconnect();
            }
            for (int i = 0; i < issues.length(); i++) {
                JSONObject issue = issues.getJSONObject(i);
                JSONObject user = issue.optJSONObject("user");
                if (issue.has("pull_request") || user == null || "Bot".equals(user.optString("type"))) {
                    continue;
                }
                String login = user.getString("login");
                if (OWNER.equalsIgnoreCase(login)) {
                    continue;
                }
                Reporter reporter = byLogin.get(login);
                if (reporter == null) {
                    reporter = new Reporter(login, user.optString("avatar_url") + "&s=96");
                    byLogin.put(login, reporter);
                }
                reporter.issues++;
            }
            if (issues.length() < PAGE_SIZE) {
                break;
            }
        }
        List<Reporter> reporters = new ArrayList<>(byLogin.values());
        reporters.sort((a, b) -> a.issues != b.issues ? b.issues - a.issues : a.login.compareToIgnoreCase(b.login));
        return reporters;
    }
}
