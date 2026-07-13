package io.github.thorvolume.control;

import android.os.Bundle;

/** 主进程与 Shizuku UserService 之间传递音量操作结果的 Bundle 协议。 */
final class SecondaryVolumeResult {
    private static final String KEY_OK = "ok";
    private static final String KEY_VALUE = "value";
    private static final String KEY_ERROR = "error";

    private SecondaryVolumeResult() {}

    static Bundle success(int value) {
        Bundle result = new Bundle();
        result.putBoolean(KEY_OK, true);
        result.putInt(KEY_VALUE, clamp(value));
        result.putString(KEY_ERROR, "");
        return result;
    }

    static Bundle failure(Throwable error) {
        Bundle result = new Bundle();
        result.putBoolean(KEY_OK, false);
        result.putInt(KEY_VALUE, 0);
        result.putString(KEY_ERROR, format(error));
        return result;
    }

    static boolean isSuccess(Bundle result) {
        return result != null && result.getBoolean(KEY_OK, false);
    }

    static int value(Bundle result) {
        return result == null ? 0 : clamp(result.getInt(KEY_VALUE, 0));
    }

    static String error(Bundle result) {
        if (result == null) return "UserService returned no result";
        String value = result.getString(KEY_ERROR, "");
        return value == null ? "" : value;
    }

    static String format(Throwable error) {
        if (error == null) return "Unknown error";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.length() == 0 ? "" : ": " + message);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(15, value));
    }
}
