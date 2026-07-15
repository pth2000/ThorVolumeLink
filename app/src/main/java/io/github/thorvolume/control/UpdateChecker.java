package io.github.thorvolume.control;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 通过 GitHub 最新 Release 接口执行用户主动触发的版本检查。 */
final class UpdateChecker {
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_CHARS = 256 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    interface Callback {
        void onComplete(Result result);
    }

    static final class Result {
        final boolean ok;
        final boolean updateAvailable;
        final String currentVersion;
        final String latestVersion;
        final String releaseUrl;
        final String error;

        private Result(boolean ok, boolean updateAvailable, String currentVersion,
                       String latestVersion, String releaseUrl, String error) {
            this.ok = ok;
            this.updateAvailable = updateAvailable;
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.error = error;
        }

        static Result success(String current, String latest, String url) {
            return new Result(true, compareVersions(latest, current) > 0,
                    current, latest, url, "");
        }

        static Result failure(String current, String error) {
            return new Result(false, false, current, "", "",
                    error == null ? "" : error);
        }
    }

    private UpdateChecker() {}

    static void check(final Context context, final Callback callback) {
        final Context app = context.getApplicationContext();
        WORKER.execute(new Runnable() {
            @Override public void run() {
                final Result result = requestLatest(app);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (callback != null) callback.onComplete(result);
                    }
                });
            }
        });
    }

    private static Result requestLatest(Context context) {
        String current = BuildConfig.VERSION_NAME == null ? "" : BuildConfig.VERSION_NAME;
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(context.getString(R.string.release_api_url));
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "ThorVolumeLink/" + current);

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return Result.failure(current, context.getString(R.string.update_no_release));
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return Result.failure(current, context.getString(R.string.update_http_error,
                        Integer.valueOf(code)));
            }

            JSONObject release = new JSONObject(readResponse(connection.getInputStream()));
            String latest = release.optString("tag_name", "").trim();
            String releaseUrl = release.optString("html_url", "").trim();
            if (latest.length() == 0 || releaseUrl.length() == 0) {
                return Result.failure(current, context.getString(R.string.update_invalid_response));
            }
            return Result.success(current, latest, releaseUrl);
        } catch (Throwable error) {
            return Result.failure(current, context.getString(R.string.update_network_error));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readResponse(InputStream stream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        try {
            while ((count = reader.read(buffer)) >= 0) {
                if (result.length() + count > MAX_RESPONSE_CHARS) {
                    throw new IllegalStateException("GitHub response is too large");
                }
                result.append(buffer, 0, count);
            }
        } finally {
            reader.close();
        }
        return result.toString();
    }

    /** 比较常见的 v1.2.3、1.2.3-rc.1 等 Release 标签。 */
    static int compareVersions(String left, String right) {
        ParsedVersion a = ParsedVersion.parse(left);
        ParsedVersion b = ParsedVersion.parse(right);
        int size = Math.max(a.core.size(), b.core.size());
        for (int i = 0; i < size; i++) {
            long av = i < a.core.size() ? a.core.get(i).longValue() : 0L;
            long bv = i < b.core.size() ? b.core.get(i).longValue() : 0L;
            if (av != bv) return av > bv ? 1 : -1;
        }
        if (a.preRelease.length() == 0 && b.preRelease.length() == 0) return 0;
        if (a.preRelease.length() == 0) return 1;
        if (b.preRelease.length() == 0) return -1;
        return comparePreRelease(a.preRelease, b.preRelease);
    }

    private static int comparePreRelease(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        int size = Math.max(a.length, b.length);
        for (int i = 0; i < size; i++) {
            if (i >= a.length) return -1;
            if (i >= b.length) return 1;
            boolean an = isNumber(a[i]);
            boolean bn = isNumber(b[i]);
            if (an && bn) {
                long av = parseLong(a[i]);
                long bv = parseLong(b[i]);
                if (av != bv) return av > bv ? 1 : -1;
            } else if (an != bn) {
                return an ? -1 : 1;
            } else {
                int compared = a[i].compareToIgnoreCase(b[i]);
                if (compared != 0) return compared > 0 ? 1 : -1;
            }
        }
        return 0;
    }

    private static boolean isNumber(String value) {
        if (value == null || value.length() == 0) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static final class ParsedVersion {
        final List<Long> core;
        final String preRelease;

        ParsedVersion(List<Long> core, String preRelease) {
            this.core = core;
            this.preRelease = preRelease;
        }

        static ParsedVersion parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
            if (value.startsWith("v")) value = value.substring(1);
            int build = value.indexOf('+');
            if (build >= 0) value = value.substring(0, build);
            String pre = "";
            int dash = value.indexOf('-');
            if (dash >= 0) {
                pre = value.substring(dash + 1);
                value = value.substring(0, dash);
            }
            List<Long> numbers = new ArrayList<Long>();
            for (String part : value.split("\\.")) {
                if (isNumber(part)) numbers.add(Long.valueOf(parseLong(part)));
                else break;
            }
            if (numbers.isEmpty()) numbers.add(Long.valueOf(0L));
            return new ParsedVersion(numbers, pre);
        }
    }
}
