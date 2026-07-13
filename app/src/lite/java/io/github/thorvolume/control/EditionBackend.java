package io.github.thorvolume.control;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lite 版副屏音量后端。
 *
 * <p>该版本不依赖 Shizuku，而是凭借 {@code WRITE_SETTINGS} 授权直接读写
 * AYN 的 {@link Settings.System} 私有设置项。耗时操作在单线程队列中串行执行，
 * 可避免连续按键产生“先读后写”竞争。</p>
 */
final class EditionBackend {
    /** AYN 固件保存副屏音量的系统设置键，合法值为 0～15。 */
    private static final String KEY = "secondary_screen_volume_level";
    /** 所有完成回调都切回主线程，保证上层可以安全更新 UI。 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private EditionBackend() {}

    static boolean usesShizuku() { return false; }

    static String backendStatus(Context context) {
        return context.getString(R.string.backend_lite_ready);
    }

    static boolean isBackendAvailable(Context context) {
        return true;
    }

    static boolean hasAuthorization(Context context) {
        try {
            // Android 6.0 起 WRITE_SETTINGS 需要用户在专用设置页单独授权。
            if (Build.VERSION.SDK_INT < 23) return true;
            return Settings.System.canWrite(context);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String authorizationStatus(Context context) {
        return context.getString(hasAuthorization(context)
                ? R.string.authorization_settings_allowed
                : R.string.authorization_settings_missing);
    }

    static String authorizationActionLabel(Context context) {
        return context.getString(hasAuthorization(context)
                ? R.string.authorization_settings_review
                : R.string.authorization_settings_allow);
    }

    static void requestAuthorization(Activity activity, SecondaryVolumeCallback callback) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                // 系统设置页没有同步授权结果，返回应用后由 onResume 重新检查。
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                complete(callback, false, 0, "");
            } else {
                complete(callback, true, 0, "");
            }
        } catch (Throwable error) {
            complete(callback, false, 0, format(error));
        }
    }

    static void setStatusListener(BackendStatusListener listener) {
        // Lite 没有外部服务连接，因此不存在需要监听的 Binder 状态变化。
    }

    static void read(final Context context, final SecondaryVolumeCallback callback) {
        WORKER.execute(new Runnable() {
            @Override public void run() {
                try {
                    int value = clamp(Settings.System.getInt(context.getContentResolver(), KEY, 0));
                    complete(callback, true, value, "");
                } catch (Throwable error) {
                    complete(callback, false, 0, format(error));
                }
            }
        });
    }

    static void set(final Context context, final int value, final SecondaryVolumeCallback callback) {
        WORKER.execute(new Runnable() {
            @Override public void run() {
                try {
                    int safe = clamp(value);
                    boolean ok = Settings.System.putInt(context.getContentResolver(), KEY, safe);
                    if (!ok) throw new IllegalStateException("Settings.System.putInt returned false");
                    complete(callback, true, safe, "");
                } catch (Throwable error) {
                    complete(callback, false, 0, format(error));
                }
            }
        });
    }

    static void adjust(final Context context, final int delta, final SecondaryVolumeCallback callback) {
        WORKER.execute(new Runnable() {
            @Override public void run() {
                try {
                    // read-modify-write 与其他副屏任务共享单线程，保持按键顺序。
                    int current = Settings.System.getInt(context.getContentResolver(), KEY, 0);
                    int target = clamp(current + delta);
                    boolean ok = Settings.System.putInt(context.getContentResolver(), KEY, target);
                    if (!ok) throw new IllegalStateException("Settings.System.putInt returned false");
                    complete(callback, true, target, "");
                } catch (Throwable error) {
                    complete(callback, false, 0, format(error));
                }
            }
        });
    }

    private static void complete(final SecondaryVolumeCallback callback, final boolean ok, final int value, final String error) {
        // 即使调用发生在工作线程，也向上层提供统一的主线程回调语义。
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onComplete(ok, value, error == null ? "" : error);
            }
        });
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static String format(Throwable error) {
        return error.getClass().getSimpleName() + ": " + safeMessage(error);
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null ? "" : value;
    }
}
