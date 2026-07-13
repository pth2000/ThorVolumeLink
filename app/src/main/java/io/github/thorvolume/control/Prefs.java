package io.github.thorvolume.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/**
 * 应用配置及最近错误的持久化入口。
 *
 * <p>这里同时负责输入值校验和默认值回退，保证无障碍服务即使读到旧版本、
 * 损坏或越界的数据，也能继续使用安全配置运行。</p>
 */
final class Prefs {
    /** 音量键交由 Android 处理，仅控制主屏。 */
    static final int MODE_MAIN = 0;
    /** 音量键由本应用消费，仅控制副屏。 */
    static final int MODE_SECONDARY = 1;
    /** 主屏变化后，按比例同步副屏。 */
    static final int MODE_SYNC = 2;

    static final int CAPTURE_NONE = 0;
    static final int CAPTURE_SWITCH = 1;

    static final int NIGHT_SYSTEM = 0;
    static final int NIGHT_LIGHT = 1;
    static final int NIGHT_DARK = 2;

    private static final String FILE = "thor_volume_control";
    private static final String KEY_MODE = "mode";
    private static final String KEY_HOLD_MS = "hold_ms";
    private static final String KEY_STEP = "volume_step";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_CAPTURE_TARGET = "capture_target";
    private static final String KEY_SWITCH_CODE = "switch_key_code";
    private static final String KEY_SWITCH_SCAN = "switch_scan_code";
    private static final String KEY_MODE_KEY_ENABLED = "mode_key_enabled";
    private static final String KEY_NIGHT_MODE = "night_mode";
    private static final String KEY_ONBOARDING_SEEN = "onboarding_seen";

    /** 模式切换键绑定；优先使用设备扫描码，以兼容厂商自定义实体键。 */
    static final class Binding {
        final int keyCode;
        final int scanCode;

        Binding(int keyCode, int scanCode) {
            this.keyCode = keyCode;
            this.scanCode = scanCode;
        }

        boolean matches(KeyEvent event) {
            if (event == null) return false;
            // 同一实体键在不同固件上可能映射成不同 keyCode，扫描码通常更稳定。
            if (scanCode > 0 && event.getScanCode() > 0) return event.getScanCode() == scanCode;
            return keyCode != KeyEvent.KEYCODE_UNKNOWN && event.getKeyCode() == keyCode;
        }
    }

    private Prefs() {}

    static int getMode(Context context) {
        try {
            int value = prefs(context).getInt(KEY_MODE, MODE_SYNC);
            return value < MODE_MAIN || value > MODE_SYNC ? MODE_SYNC : value;
        } catch (Throwable ignored) {
            return MODE_SYNC;
        }
    }

    static void setMode(Context context, int mode) {
        int safe = mode < MODE_MAIN || mode > MODE_SYNC ? MODE_SYNC : mode;
        try {
            prefs(context).edit().putInt(KEY_MODE, safe).apply();
        } catch (Throwable error) {
            recordError(context, context.getString(R.string.error_save_mode), error);
        }
    }

    static int nextMode(Context context) {
        int next = (getMode(context) + 1) % 3;
        setMode(context, next);
        return next;
    }

    /** 是否允许已绑定实体键通过长按切换音量模式。 */
    static boolean isModeKeyEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_MODE_KEY_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    static void setModeKeyEnabled(Context context, boolean enabled) {
        try {
            prefs(context).edit().putBoolean(KEY_MODE_KEY_ENABLED, enabled).apply();
        } catch (Throwable error) {
            recordError(context, context.getString(R.string.error_save_mode_key_enabled), error);
        }
    }

    static Binding getSwitchBinding(Context context) {
        try {
            return new Binding(
                    prefs(context).getInt(KEY_SWITCH_CODE, KeyEvent.KEYCODE_BACK),
                    prefs(context).getInt(KEY_SWITCH_SCAN, 0));
        } catch (Throwable ignored) {
            return new Binding(KeyEvent.KEYCODE_BACK, 0);
        }
    }

    static boolean saveSwitchBinding(Context context, int keyCode, int scanCode) {
        // 音量键始终承担调节职责，不能再绑定为模式切换键。
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode <= 0) return false;
        try {
            prefs(context).edit()
                    .putInt(KEY_SWITCH_CODE, keyCode)
                    .putInt(KEY_SWITCH_SCAN, scanCode)
                    .putInt(KEY_CAPTURE_TARGET, CAPTURE_NONE)
                    .apply();
            return true;
        } catch (Throwable error) {
            recordError(context, context.getString(R.string.error_save_binding), error);
            return false;
        }
    }

    static String bindingLabel(Context context, Binding binding) {
        if (binding == null) return context.getString(R.string.binding_unset);
        if (binding.keyCode == KeyEvent.KEYCODE_BACK) return context.getString(R.string.key_back);
        if (binding.keyCode == KeyEvent.KEYCODE_HOME) return context.getString(R.string.key_home);
        if (binding.keyCode == KeyEvent.KEYCODE_APP_SWITCH) return context.getString(R.string.key_recents);
        String name = null;
        try {
            name = KeyEvent.keyCodeToString(binding.keyCode);
        } catch (Throwable ignored) {}
        if (name == null || name.length() == 0 || "KEYCODE_UNKNOWN".equals(name)) {
            return binding.scanCode > 0
                    ? context.getString(R.string.binding_scan_code, Integer.valueOf(binding.scanCode))
                    : context.getString(R.string.binding_unknown);
        }
        if (name.startsWith("KEYCODE_")) name = name.substring(8);
        name = name.replace('_', ' ');
        return binding.scanCode > 0 ? name + " · scan " + binding.scanCode : name;
    }

    static int getCaptureTarget(Context context) {
        try {
            return prefs(context).getInt(KEY_CAPTURE_TARGET, CAPTURE_NONE);
        } catch (Throwable ignored) {
            return CAPTURE_NONE;
        }
    }

    static void beginCapture(Context context) {
        try {
            prefs(context).edit().putInt(KEY_CAPTURE_TARGET, CAPTURE_SWITCH).apply();
        } catch (Throwable error) {
            recordError(context, context.getString(R.string.error_begin_capture), error);
        }
    }

    static void cancelCapture(Context context) {
        try {
            prefs(context).edit().putInt(KEY_CAPTURE_TARGET, CAPTURE_NONE).apply();
        } catch (Throwable ignored) {}
    }

    static int getHoldMs(Context context) {
        try {
            // 可配置范围为 300～2500 ms，默认 800 ms。
            int value = prefs(context).getInt(KEY_HOLD_MS, 800);
            return value < 300 || value > 2500 ? 800 : value;
        } catch (Throwable ignored) {
            return 800;
        }
    }

    static void setHoldMs(Context context, int value) {
        int safe = Math.max(300, Math.min(2500, value));
        try {
            prefs(context).edit().putInt(KEY_HOLD_MS, safe).apply();
        } catch (Throwable error) {
            recordError(context, context.getString(R.string.error_save_hold), error);
        }
    }

    static int getStep(Context context) {
        try {
            // 副屏总共只有 16 档，单次步长限制为 1～5 档。
            int value = prefs(context).getInt(KEY_STEP, 1);
            return value < 1 || value > 5 ? 1 : value;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    static void setStep(Context context, int value) {
        int safe = Math.max(1, Math.min(5, value));
        try {
            prefs(context).edit().putInt(KEY_STEP, safe).apply();
        } catch (Throwable error) {
            recordError(context, context.getString(R.string.error_save_step), error);
        }
    }

    static void resetControlSettings(Context context) {
        try {
            prefs(context).edit()
                    .putInt(KEY_SWITCH_CODE, KeyEvent.KEYCODE_BACK)
                    .putInt(KEY_SWITCH_SCAN, 0)
                    .putInt(KEY_HOLD_MS, 800)
                    .putInt(KEY_STEP, 1)
                    .putBoolean(KEY_MODE_KEY_ENABLED, true)
                    .putInt(KEY_CAPTURE_TARGET, CAPTURE_NONE)
                    .apply();
        } catch (Throwable error) {
            recordError(context, context.getString(R.string.error_reset_controls), error);
        }
    }

    static int getNightMode(Context context) {
        try {
            int value = prefs(context).getInt(KEY_NIGHT_MODE, NIGHT_SYSTEM);
            return value < NIGHT_SYSTEM || value > NIGHT_DARK ? NIGHT_SYSTEM : value;
        } catch (Throwable ignored) {
            return NIGHT_SYSTEM;
        }
    }

    static void setNightMode(Context context, int mode) {
        int safe = mode < NIGHT_SYSTEM || mode > NIGHT_DARK ? NIGHT_SYSTEM : mode;
        try {
            prefs(context).edit().putInt(KEY_NIGHT_MODE, safe).apply();
        } catch (Throwable error) {
            recordError(context, context.getString(R.string.error_save_night_mode), error);
        }
    }

    static boolean hasSeenOnboarding(Context context) {
        try {
            return prefs(context).getBoolean(KEY_ONBOARDING_SEEN, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void markOnboardingSeen(Context context) {
        try {
            prefs(context).edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply();
        } catch (Throwable ignored) {}
    }

    static String getLastError(Context context) {
        try {
            return prefs(context).getString(KEY_LAST_ERROR, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void clearLastError(Context context) {
        try {
            prefs(context).edit().putString(KEY_LAST_ERROR, "").apply();
        } catch (Throwable ignored) {}
    }

    static void recordError(Context context, String stage, Throwable error) {
        // 仅保存便于用户诊断的异常类型和消息，不持久化完整堆栈。
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        String message = error == null ? "" : error.getMessage();
        if (message == null) message = "";
        String text = stage + ": " + type + (message.length() == 0 ? "" : " — " + message);
        try {
            prefs(context).edit().putString(KEY_LAST_ERROR, text).apply();
        } catch (Throwable ignored) {}
    }

    static String modeLabel(Context context, int mode) {
        if (mode == MODE_MAIN) return context.getString(R.string.mode_main);
        if (mode == MODE_SECONDARY) return context.getString(R.string.mode_secondary);
        return context.getString(R.string.mode_sync);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
