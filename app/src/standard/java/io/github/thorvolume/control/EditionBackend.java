package io.github.thorvolume.control;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Standard 版副屏音量后端。
 *
 * <p>Shizuku Server 的连接、权限和 UserService 生命周期全部交给官方 API；
 * 本类只保留业务层需要的状态转换、请求排队和线程切换。</p>
 */
final class EditionBackend {
    private static final String SECONDARY_VOLUME_KEY = VolumeControl.SECONDARY_SETTING_KEY;
    private static final int REQUEST_CODE = 9417;
    private static final int OPERATION_SET = 1;
    private static final int OPERATION_ADJUST = 2;
    /** UserService 实现变化时递增，确保同 versionCode 的开发包也会替换旧 daemon。 */
    private static final int USER_SERVICE_IMPLEMENTATION_VERSION = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static final List<ServiceReadyCallback> READY_CALLBACKS = new ArrayList<ServiceReadyCallback>();
    private static final List<SecondaryVolumeCallback> PERMISSION_CALLBACKS = new ArrayList<SecondaryVolumeCallback>();

    private static ISecondaryVolumeService volumeService;
    private static Shizuku.UserServiceArgs userServiceArgs;
    private static boolean bindingUserService;
    private static String permissionDeniedMessage = "";
    /** 防止切换瞬间迟到的 ServiceConnection 回调重新挂回已释放的 Shizuku 服务。 */
    private static volatile boolean shizukuSelected = true;
    private static volatile BackendStatusListener statusListener;

    private interface ServiceReadyCallback {
        void onReady(boolean ready, String error);
    }

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER =
            new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() {
                    notifyStatus();
                }
            };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER =
            new Shizuku.OnBinderDeadListener() {
                @Override public void onBinderDead() {
                    synchronized (LOCK) {
                        volumeService = null;
                    }
                    dispatchReady(false, "Shizuku stopped");
                    dispatchPermission(false, "Shizuku stopped");
                }
            };

    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT_LISTENER =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQUEST_CODE) return;
                    boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    dispatchPermission(granted, granted ? "" : permissionDeniedMessage);
                    notifyStatus();
                }
            };

    private static final ServiceConnection USER_SERVICE_CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!shizukuSelected) {
                Shizuku.UserServiceArgs args;
                synchronized (LOCK) {
                    args = userServiceArgs;
                }
                if (args != null) {
                    try {
                        Shizuku.unbindUserService(args, USER_SERVICE_CONNECTION, true);
                    } catch (Throwable ignored) {}
                }
                return;
            }
            ISecondaryVolumeService service = ISecondaryVolumeService.Stub.asInterface(binder);
            synchronized (LOCK) {
                volumeService = service;
            }
            dispatchReady(service != null,
                    service == null ? "Shizuku volume service returned no Binder" : "");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                volumeService = null;
            }
            dispatchReady(false, "Shizuku volume service stopped");
        }
    };

    static {
        Shizuku.addBinderReceivedListener(BINDER_RECEIVED_LISTENER);
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER);
        Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT_LISTENER);
    }

    private EditionBackend() {}

    static boolean supportsBackendSelection() { return true; }

    static int selectedBackend(Context context) {
        return Prefs.getPrivilegedBackend(context);
    }

    static String selectedBackendLabel(Context context) {
        return context.getString(isRootSelected(context)
                ? R.string.backend_method_root : R.string.backend_method_shizuku);
    }

    /**
     * 切换后端时先保存唯一选项，再释放旧后端，确保后续请求不会同时走两条路径。
     */
    static void selectBackend(Context context, int backend) {
        int next = backend == Prefs.PRIVILEGED_BACKEND_ROOT
                ? Prefs.PRIVILEGED_BACKEND_ROOT : Prefs.PRIVILEGED_BACKEND_SHIZUKU;
        int previous = Prefs.getPrivilegedBackend(context);
        if (previous == next) return;

        Prefs.setPrivilegedBackend(context, next);
        shizukuSelected = next == Prefs.PRIVILEGED_BACKEND_SHIZUKU;
        if (previous == Prefs.PRIVILEGED_BACKEND_ROOT) RootBackend.release();
        else releaseShizuku();
        notifyStatus();
    }

    static String dependencySummary(Context context) {
        return context.getString(R.string.standard_dependencies);
    }

    static String backendStatus(Context context) {
        if (isRootSelected(context)) return RootBackend.backendStatus(context);
        return context.getString(Shizuku.pingBinder()
                ? R.string.backend_shizuku_connected
                : R.string.backend_shizuku_disconnected);
    }

    static boolean isBackendAvailable(Context context) {
        if (isRootSelected(context)) return RootBackend.isBackendAvailable(context);
        return Shizuku.pingBinder();
    }

    static boolean hasAuthorization(Context context) {
        if (isRootSelected(context)) return RootBackend.hasAuthorization(context);
        return hasShizukuAuthorization();
    }

    private static boolean hasShizukuAuthorization() {
        if (!Shizuku.pingBinder()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String authorizationStatus(Context context) {
        if (isRootSelected(context)) return RootBackend.authorizationStatus(context);
        return context.getString(hasShizukuAuthorization()
                ? R.string.authorization_shizuku_granted
                : R.string.authorization_shizuku_missing);
    }

    static String authorizationActionLabel(Context context) {
        if (isRootSelected(context)) return RootBackend.authorizationActionLabel(context);
        return context.getString(hasShizukuAuthorization()
                ? R.string.authorization_shizuku_recheck
                : R.string.authorization_shizuku_grant);
    }

    static void requestAuthorization(final Activity activity, final SecondaryVolumeCallback callback) {
        if (isRootSelected(activity)) {
            RootBackend.requestAuthorization(activity, callback);
            return;
        }
        requestShizukuAuthorization(activity, callback);
    }

    private static void requestShizukuAuthorization(final Activity activity,
                                                     final SecondaryVolumeCallback callback) {
        if (!Shizuku.pingBinder()) {
            complete(callback, false, 0, activity.getString(R.string.shizuku_not_running));
            return;
        }
        if (hasShizukuAuthorization()) {
            complete(callback, true, 0, "");
            return;
        }

        boolean startRequest;
        synchronized (LOCK) {
            startRequest = PERMISSION_CALLBACKS.isEmpty();
            if (callback != null) PERMISSION_CALLBACKS.add(callback);
            permissionDeniedMessage = activity.getString(R.string.shizuku_permission_denied);
        }
        if (!startRequest) return;

        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable error) {
            dispatchPermission(false, SecondaryVolumeResult.format(error));
        }
    }

    static void setStatusListener(BackendStatusListener listener) {
        statusListener = listener;
        RootBackend.setStatusListener(listener);
    }

    static void read(final Context context, final SecondaryVolumeCallback callback) {
        if (isRootSelected(context)) RootBackend.read(context, callback);
        else readDirect(context, callback);
    }

    static void set(final Context context, int value, final SecondaryVolumeCallback callback) {
        if (isRootSelected(context)) RootBackend.set(context, value, callback);
        else runShizuku(context, OPERATION_SET, clamp(value), callback);
    }

    static void adjust(final Context context, int delta, final SecondaryVolumeCallback callback) {
        if (isRootSelected(context)) RootBackend.adjust(context, delta, callback);
        else runShizuku(context, OPERATION_ADJUST, delta, callback);
    }

    /** 读取 Settings.System 不需要 Shizuku 授权，且会自动对应应用当前用户。 */
    private static void readDirect(final Context context,
                                   final SecondaryVolumeCallback callback) {
        final Context app = context.getApplicationContext();
        WORKER.execute(new Runnable() {
            @Override public void run() {
                if (isRootSelected(app)) {
                    complete(callback, false, 0, app.getString(R.string.backend_changed));
                    return;
                }
                try {
                    int value = Settings.System.getInt(app.getContentResolver(),
                            SECONDARY_VOLUME_KEY);
                    complete(callback, true, clamp(value), "");
                } catch (Throwable error) {
                    complete(callback, false, 0, SecondaryVolumeResult.format(error));
                }
            }
        });
    }

    private static void runShizuku(final Context context, final int operation, final int argument,
                                   final SecondaryVolumeCallback callback) {
        if (isRootSelected(context)) {
            complete(callback, false, 0, context.getString(R.string.backend_changed));
            return;
        }
        ensureVolumeService(context, new ServiceReadyCallback() {
            @Override public void onReady(boolean ready, String error) {
                if (!ready) {
                    complete(callback, false, 0, error);
                    return;
                }
                WORKER.execute(new Runnable() {
                    @Override public void run() {
                        if (isRootSelected(context)) {
                            complete(callback, false, 0,
                                    context.getString(R.string.backend_changed));
                            return;
                        }
                        ISecondaryVolumeService service;
                        synchronized (LOCK) {
                            service = volumeService;
                        }
                        if (!isServiceAlive(service)) {
                            complete(callback, false, 0, "Shizuku volume service is not connected");
                            return;
                        }
                        try {
                            Bundle result;
                            if (operation == OPERATION_SET) result = service.setVolume(argument);
                            else result = service.adjustVolume(argument);

                            if (SecondaryVolumeResult.isSuccess(result)) {
                                complete(callback, true, SecondaryVolumeResult.value(result), "");
                            } else {
                                complete(callback, false, 0, SecondaryVolumeResult.error(result));
                            }
                        } catch (Throwable error) {
                            synchronized (LOCK) {
                                if (volumeService == service) volumeService = null;
                            }
                            notifyStatus();
                            complete(callback, false, 0, SecondaryVolumeResult.format(error));
                        }
                    }
                });
            }
        });
    }

    private static void ensureVolumeService(Context context, ServiceReadyCallback callback) {
        if (isRootSelected(context)) {
            postReady(callback, false, context.getString(R.string.backend_changed));
            return;
        }
        boolean shouldBind = false;
        String error = "";
        synchronized (LOCK) {
            if (isServiceAlive(volumeService)) {
                postReady(callback, true, "");
                return;
            }
            if (callback != null) READY_CALLBACKS.add(callback);
            if (bindingUserService) return;
            if (!Shizuku.pingBinder()) {
                error = "Shizuku is not connected";
            } else if (!hasShizukuAuthorization()) {
                error = "Shizuku permission is not granted";
            } else {
                bindingUserService = true;
                shouldBind = true;
            }
        }

        if (error.length() > 0) {
            dispatchReady(false, error);
            return;
        }
        if (!shouldBind) return;

        try {
            Shizuku.bindUserService(getUserServiceArgs(context), USER_SERVICE_CONNECTION);
        } catch (Throwable bindError) {
            dispatchReady(false, "Unable to start Shizuku volume service: "
                    + SecondaryVolumeResult.format(bindError));
        }
    }

    private static Shizuku.UserServiceArgs getUserServiceArgs(Context context) {
        synchronized (LOCK) {
            if (userServiceArgs != null) return userServiceArgs;
            int version = userServiceVersion(context);
            userServiceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(context.getPackageName(), SecondaryVolumeUserService.class.getName()))
                    .processNameSuffix("thor_volume")
                    .debuggable(false)
                    .version(version)
                    .daemon(true)
                    .tag("thor-volume-user-service");
            return userServiceArgs;
        }
    }

    @SuppressWarnings("deprecation")
    private static int packageVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, version));
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static int userServiceVersion(Context context) {
        long version = (long) packageVersionCode(context) * 1000L
                + USER_SERVICE_IMPLEMENTATION_VERSION;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, version));
    }

    private static boolean isServiceAlive(ISecondaryVolumeService service) {
        return service != null && service.asBinder() != null && service.asBinder().pingBinder();
    }

    private static boolean isRootSelected(Context context) {
        boolean root = Prefs.getPrivilegedBackend(context) == Prefs.PRIVILEGED_BACKEND_ROOT;
        shizukuSelected = !root;
        return root;
    }

    /** 停止并销毁旧的 Shizuku UserService，避免切到 Root 后仍保留第二条后端。 */
    private static void releaseShizuku() {
        Shizuku.UserServiceArgs args;
        synchronized (LOCK) {
            volumeService = null;
            bindingUserService = false;
            args = userServiceArgs;
        }
        dispatchReady(false, "Backend changed");
        dispatchPermission(false, "Backend changed");
        if (args == null || !Shizuku.pingBinder()) return;
        try {
            Shizuku.unbindUserService(args, USER_SERVICE_CONNECTION, true);
        } catch (Throwable ignored) {
            // 旧后端已经从路由中移除；解绑失败不应阻止 Root 接管。
        }
    }

    private static void postReady(final ServiceReadyCallback callback,
                                  final boolean ready, final String error) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                callback.onReady(ready, error == null ? "" : error);
            }
        });
    }

    private static void dispatchReady(final boolean ready, final String error) {
        final List<ServiceReadyCallback> callbacks;
        synchronized (LOCK) {
            bindingUserService = false;
            callbacks = new ArrayList<ServiceReadyCallback>(READY_CALLBACKS);
            READY_CALLBACKS.clear();
        }
        MAIN.post(new Runnable() {
            @Override public void run() {
                for (ServiceReadyCallback callback : callbacks) {
                    callback.onReady(ready, error == null ? "" : error);
                }
                notifyStatus();
            }
        });
    }

    private static void dispatchPermission(final boolean granted, final String error) {
        final List<SecondaryVolumeCallback> callbacks;
        synchronized (LOCK) {
            callbacks = new ArrayList<SecondaryVolumeCallback>(PERMISSION_CALLBACKS);
            PERMISSION_CALLBACKS.clear();
        }
        for (SecondaryVolumeCallback callback : callbacks) {
            complete(callback, granted, 0, error);
        }
    }

    private static void notifyStatus() {
        final BackendStatusListener listener = statusListener;
        if (listener == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                BackendStatusListener current = statusListener;
                if (current != null) current.onStatusChanged();
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
