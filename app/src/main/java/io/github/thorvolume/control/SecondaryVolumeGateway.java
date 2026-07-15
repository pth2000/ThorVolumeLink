package io.github.thorvolume.control;

import android.app.Activity;
import android.content.Context;

/**
 * 主源码集访问副屏音量后端的统一入口。
 *
 * <p>{@code standard} 与 {@code lite} 源码集各自提供一个同名的
 * {@link EditionBackend}，Gradle 会按当前 Product Flavor 只编译其中一个。
 * 这样上层按键和界面代码无需感知具体授权及 IPC 实现。</p>
 */
final class SecondaryVolumeGateway {
    private SecondaryVolumeGateway() {}

    static boolean supportsBackendSelection() { return EditionBackend.supportsBackendSelection(); }
    static int selectedBackend(Context context) { return EditionBackend.selectedBackend(context); }
    static String selectedBackendLabel(Context context) { return EditionBackend.selectedBackendLabel(context); }
    static void selectBackend(Context context, int backend) { EditionBackend.selectBackend(context, backend); }
    static String dependencySummary(Context context) { return EditionBackend.dependencySummary(context); }
    static String backendStatus(Context context) { return EditionBackend.backendStatus(context); }
    static boolean isBackendAvailable(Context context) { return EditionBackend.isBackendAvailable(context); }
    static boolean hasAuthorization(Context context) { return EditionBackend.hasAuthorization(context); }
    static String authorizationStatus(Context context) { return EditionBackend.authorizationStatus(context); }
    static String authorizationActionLabel(Context context) { return EditionBackend.authorizationActionLabel(context); }
    static void requestAuthorization(Activity activity, SecondaryVolumeCallback callback) { EditionBackend.requestAuthorization(activity, callback); }
    static void setStatusListener(BackendStatusListener listener) { EditionBackend.setStatusListener(listener); }
    static void read(Context context, SecondaryVolumeCallback callback) { EditionBackend.read(context, callback); }
    static void set(Context context, int value, SecondaryVolumeCallback callback) { EditionBackend.set(context, value, callback); }
    static void adjust(Context context, int delta, SecondaryVolumeCallback callback) { EditionBackend.adjust(context, delta, callback); }
}
