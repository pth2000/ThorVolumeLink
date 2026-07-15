package io.github.thorvolume.control;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.topjohnwu.superuser.Shell;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 使用 libsu 的单一 Root Shell 读写 AYN 副屏音量设置。 */
final class RootBackend {
    private static final String SETTINGS = "/system/bin/settings";
    private static final String KEY = VolumeControl.SECONDARY_SETTING_KEY;
    /** Android 为每个用户预留的 UID 范围，用应用 UID 推导实际用户编号。 */
    private static final int PER_USER_RANGE = 100000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 串行执行读改写，避免连续按键之间出现竞争。 */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static volatile BackendStatusListener statusListener;

    private RootBackend() {}

    static String backendStatus(Context context) {
        return context.getString(hasAuthorization(context)
                ? R.string.backend_root_ready : R.string.backend_root_inactive);
    }

    static boolean isBackendAvailable(Context context) {
        return hasAuthorization(context);
    }

    static boolean hasAuthorization(Context context) {
        try {
            Shell shell = Shell.getCachedShell();
            if (shell != null && shell.isAlive()) return shell.isRoot();
            return Boolean.TRUE.equals(Shell.isAppGrantedRoot());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String authorizationStatus(Context context) {
        return context.getString(hasAuthorization(context)
                ? R.string.authorization_root_granted : R.string.authorization_root_missing);
    }

    static String authorizationActionLabel(Context context) {
        return context.getString(hasAuthorization(context)
                ? R.string.authorization_root_recheck : R.string.authorization_root_grant);
    }

    /** 只有用户明确选择 Root 或点击授权按钮时才会创建 su Shell。 */
    static void requestAuthorization(final Activity activity,
                                     final SecondaryVolumeCallback callback) {
        WORKER.execute(new Runnable() {
            @Override public void run() {
                if (!isSelected(activity)) {
                    complete(callback, false, 0, activity.getString(R.string.backend_changed));
                    return;
                }
                try {
                    Shell shell = Shell.getShell();
                    boolean granted = shell != null && shell.isRoot();
                    complete(callback, granted, 0, granted ? ""
                            : activity.getString(R.string.root_permission_denied));
                } catch (Throwable error) {
                    complete(callback, false, 0,
                            activity.getString(R.string.root_permission_denied)
                                    + " " + SecondaryVolumeResult.format(error));
                } finally {
                    notifyStatus();
                }
            }
        });
    }

    static void setStatusListener(BackendStatusListener listener) {
        statusListener = listener;
    }

    static void read(final Context context, final SecondaryVolumeCallback callback) {
        final Context app = context.getApplicationContext();
        WORKER.execute(new Runnable() {
            @Override public void run() {
                if (!isSelected(app)) {
                    complete(callback, false, 0, app.getString(R.string.backend_changed));
                    return;
                }
                try {
                    complete(callback, true, readSetting(app), "");
                } catch (Throwable error) {
                    complete(callback, false, 0, SecondaryVolumeResult.format(error));
                }
            }
        });
    }

    static void set(final Context context, final int value,
                    final SecondaryVolumeCallback callback) {
        run(context, new RootOperation() {
            @Override public int execute(Context app, Shell shell, int userId) throws Exception {
                int safe = clamp(value);
                writeSetting(shell, userId, safe);
                return verifySetting(app, safe);
            }
        }, callback);
    }

    static void adjust(final Context context, final int delta,
                       final SecondaryVolumeCallback callback) {
        run(context, new RootOperation() {
            @Override public int execute(Context app, Shell shell, int userId) throws Exception {
                int target = clamp(readSetting(app) + delta);
                writeSetting(shell, userId, target);
                return verifySetting(app, target);
            }
        }, callback);
    }

    static void release() {
        try {
            Shell shell = Shell.getCachedShell();
            if (shell != null) shell.close();
        } catch (Throwable ignored) {}
        notifyStatus();
    }

    private interface RootOperation {
        int execute(Context app, Shell shell, int userId) throws Exception;
    }

    private static void run(final Context context, final RootOperation operation,
                            final SecondaryVolumeCallback callback) {
        final Context app = context.getApplicationContext();
        // 不能在 su 进程中使用“current”：Root UID 下它可能无法解析到前台应用用户。
        final int userId = Math.max(0, android.os.Process.myUid() / PER_USER_RANGE);
        WORKER.execute(new Runnable() {
            @Override public void run() {
                if (!isSelected(app)) {
                    complete(callback, false, 0, app.getString(R.string.backend_changed));
                    return;
                }
                try {
                    Shell shell = Shell.getShell();
                    if (shell == null || !shell.isRoot()) {
                        throw new IllegalStateException(app.getString(R.string.root_permission_denied));
                    }
                    if (!isSelected(app)) {
                        complete(callback, false, 0, app.getString(R.string.backend_changed));
                        return;
                    }
                    complete(callback, true, operation.execute(app, shell, userId), "");
                } catch (Throwable error) {
                    complete(callback, false, 0, SecondaryVolumeResult.format(error));
                } finally {
                    notifyStatus();
                }
            }
        });
    }

    /** 读取不需要 Root，ContentResolver 会自动使用应用所在的 Android 用户。 */
    private static int readSetting(Context context) throws Exception {
        return clamp(Settings.System.getInt(context.getContentResolver(), KEY));
    }

    private static void writeSetting(Shell shell, int userId, int value) throws Exception {
        Shell.Result result = shell.newJob()
                .add(settingsForUser(userId) + " put system " + KEY + " " + clamp(value))
                .exec();
        ensureSuccess(result);
    }

    private static int verifySetting(Context context, int expected) throws Exception {
        int actual = readSetting(context);
        if (actual != expected) {
            throw new IllegalStateException(
                    "secondary volume write was not applied: expected "
                            + expected + ", actual " + actual);
        }
        return actual;
    }

    private static String settingsForUser(int userId) {
        return SETTINGS + " --user " + Math.max(0, userId);
    }

    private static void ensureSuccess(Shell.Result result) {
        if (result != null && result.isSuccess()) return;
        int code = result == null ? Shell.Result.JOB_NOT_EXECUTED : result.getCode();
        String detail = result == null || result.getErr().isEmpty()
                ? "" : " : " + result.getErr().get(result.getErr().size() - 1);
        throw new IllegalStateException("settings exited with code " + code + detail);
    }

    private static boolean isSelected(Context context) {
        return Prefs.getPrivilegedBackend(context) == Prefs.PRIVILEGED_BACKEND_ROOT;
    }

    private static void notifyStatus() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                BackendStatusListener listener = statusListener;
                if (listener != null) listener.onStatusChanged();
            }
        });
    }

    private static void complete(final SecondaryVolumeCallback callback,
                                 final boolean ok, final int value, final String error) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onComplete(ok, value, error == null ? "" : error);
            }
        });
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(15, value));
    }
}
