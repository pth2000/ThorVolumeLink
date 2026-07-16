package io.github.thorvolume.control;

import android.content.Context;
import android.net.Uri;
import android.provider.Settings;

/** AYN Thor 用于记录当前交互屏幕的系统设置。 */
final class FocusChangeSetting {
    static final String KEY = "focus_change";
    static final long MISSING_VALUE = -1L;

    private FocusChangeSetting() {}

    static Uri uri() {
        return Settings.System.getUriFor(KEY);
    }

    static long read(Context context) {
        return Settings.System.getLong(
                context.getContentResolver(), KEY, MISSING_VALUE);
    }

    static boolean isAvailable(long value) {
        return value != MISSING_VALUE;
    }

    /** AYN 固件中计数器为奇数时表示上屏，为偶数时表示下屏。 */
    static boolean targetsPrimary(long value) {
        return (value & 1L) == 1L;
    }

    static int volumeMode(long value) {
        if (!isAvailable(value)) return Prefs.MODE_MAIN;
        return targetsPrimary(value) ? Prefs.MODE_MAIN : Prefs.MODE_SECONDARY;
    }
}
